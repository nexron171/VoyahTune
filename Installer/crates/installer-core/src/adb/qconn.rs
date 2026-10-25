//! Bounded access to the vehicle's existing QNX service through USB ADB.

use super::{Adb, ChildGuard};
use crate::{Error, Result};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    process::{ChildStdin, Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, Instant},
};

const FILE_PROMPT: &[u8] = b"<qconn-file>";
const LAUNCHER_PROMPT: &[u8] = b"<qconn-launcher>";
const OUTPUT_LIMIT: usize = 256 * 1024;

fn failure(detail: impl std::fmt::Display) -> Error {
    Error::new(
        "QNX_TRANSPORT",
        "Не удалось выполнить операцию с темами приборки",
    )
    .detail(detail)
}

struct Connection {
    _child: ChildGuard,
    input: ChildStdin,
    output: Receiver<std::io::Result<Vec<u8>>>,
    buffer: Vec<u8>,
    deadline: Instant,
}

impl Connection {
    fn open(adb: &Adb) -> Result<Self> {
        let serial = adb
            .serial
            .as_ref()
            .ok_or_else(|| failure("ADB serial is required"))?;
        let mut command = Command::new(&adb.path);
        command
            .args([
                "-s",
                serial,
                "shell",
                "-T",
                "toybox",
                "nc",
                "-w",
                "5",
                "172.16.104.41",
                "8000",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        let mut child = ChildGuard(command.spawn()?);
        let input = child.stdin.take().unwrap();
        let mut stdout = child.stdout.take().unwrap();
        let mut stderr = child.stderr.take().unwrap();
        let (sender, output) = mpsc::sync_channel(32);
        thread::spawn(move || {
            let mut bytes = [0; 8192];
            loop {
                match stdout.read(&mut bytes) {
                    Ok(0) => break,
                    Ok(count) => {
                        if sender.send(Ok(bytes[..count].to_vec())).is_err() {
                            break;
                        }
                    }
                    Err(error) => {
                        let _ = sender.send(Err(error));
                        break;
                    }
                }
            }
        });
        thread::spawn(move || {
            let _ = std::io::copy(&mut stderr, &mut std::io::sink());
        });
        let mut connection = Self {
            _child: child,
            input,
            output,
            buffer: Vec::new(),
            deadline: Instant::now() + Duration::from_secs(180),
        };
        connection.until(b"QCONN\r\n")?;
        connection.input.write_all(b"\xff\xfc\x22\xff\xfe\x01")?;
        connection.input.flush()?;
        connection.until(b"<qconn-broker>")?;
        Ok(connection)
    }

    fn receive(&mut self) -> Result<bool> {
        let remaining = self.deadline.saturating_duration_since(Instant::now());
        match self
            .output
            .recv_timeout(remaining.min(Duration::from_secs(30)))
        {
            Ok(bytes) => {
                self.buffer.extend(bytes?);
                if self.buffer.len() > OUTPUT_LIMIT {
                    return Err(failure("QNX response exceeds the limit"));
                }
                Ok(true)
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => Ok(false),
            Err(mpsc::RecvTimeoutError::Timeout) => Err(failure("QNX response timed out")),
        }
    }

    fn until(&mut self, token: &[u8]) -> Result<Vec<u8>> {
        loop {
            if let Some(position) = self
                .buffer
                .windows(token.len())
                .position(|part| part == token)
            {
                return Ok(self.buffer.drain(..position + token.len()).collect());
            }
            if !self.receive()? {
                return Err(failure(String::from_utf8_lossy(&self.buffer)));
            }
        }
    }

    fn send(&mut self, command: &str) -> Result<()> {
        if command.contains(['\r', '\n']) {
            return Err(failure("A single protocol command is required"));
        }
        self.input.write_all(command.as_bytes())?;
        self.input.write_all(b"\n")?;
        self.input.flush()?;
        Ok(())
    }

    fn command(&mut self, command: &str, prompt: &[u8]) -> Result<String> {
        self.send(command)?;
        Ok(String::from_utf8_lossy(&self.until(prompt)?)
            .trim()
            .to_owned())
    }

    fn upload(&mut self, path: &str, data: &[u8]) -> Result<()> {
        if !self.command("service file", FILE_PROMPT)?.contains("OK") {
            return Err(failure("QNX file service unavailable"));
        }
        // QNX O_CREAT | O_EXCL | O_WRONLY; never truncate an existing file.
        let response = self.command(&format!("o:\"{path}\":501:180"), FILE_PROMPT)?;
        let descriptor = response
            .strip_prefix("o:")
            .and_then(|rest| rest.split(':').next())
            .and_then(|value| u32::from_str_radix(value, 16).ok())
            .ok_or_else(|| failure(&response))?;
        for (index, chunk) in data.chunks(2048).enumerate() {
            self.send(&format!(
                "w:{descriptor:x}:{:x}:{:x}",
                index * 2048,
                chunk.len()
            ))?;
            self.input.write_all(chunk)?;
            self.input.flush()?;
            let response = self.until(FILE_PROMPT)?;
            let header = String::from_utf8_lossy(&response);
            let count = header
                .trim()
                .strip_prefix("o:")
                .and_then(|rest| rest.split([':', '\r', '\n']).next())
                .and_then(|value| usize::from_str_radix(value, 16).ok());
            if count != Some(chunk.len()) {
                return Err(failure(header));
            }
        }
        if !self
            .command(&format!("c:{descriptor:x}"), FILE_PROMPT)?
            .starts_with("o\r\n")
        {
            return Err(failure("QNX file close failed"));
        }
        Ok(())
    }

    fn launch(&mut self, command: &str) -> Result<String> {
        if !self
            .command("service launcher", LAUNCHER_PROMPT)?
            .contains("OK")
        {
            return Err(failure("QNX launcher unavailable"));
        }
        let response = self.command(&format!("start/flags 8000 {command}"), LAUNCHER_PROMPT)?;
        if !response.lines().any(|line| {
            line.trim()
                .strip_prefix("OK ")
                .is_some_and(|pid| pid.parse::<u32>().is_ok())
        }) {
            return Err(failure(response));
        }
        self.send("continue")?;
        while self.receive()? {}
        Ok(String::from_utf8_lossy(&self.buffer).into_owned())
    }
}

impl Adb {
    pub(crate) fn qnx_navigation_fonts(
        &self,
        script: &[u8],
        action: &str,
        model: &str,
    ) -> Result<String> {
        if !["inspect", "apply", "restore"].contains(&action) || !["H97X", "H97Y"].contains(&model)
        {
            return Err(failure("Unsupported font operation or vehicle model"));
        }
        let nonce = tempfile::Builder::new().prefix("qnx-font-").tempdir()?;
        let token = nonce.path().file_name().unwrap().to_string_lossy();
        let remote = format!("/var/data/voyahtune-{token}.py");
        Connection::open(self)?.upload(&remote, script)?;
        let hash = hex::encode(Sha256::digest(script));
        let bootstrap = format!(
            "import hashlib,os,sys; p='{remote}'; d=open(p,'rb').read(); assert hashlib.sha256(d).hexdigest()=='{hash}'; os.unlink(p); sys.argv=[p,'{action}','--model','{model}']; exec(compile(d,p,'exec'))"
        );
        Connection::open(self)?.launch(&format!(
            "/usr/bin/python3 python3 -u -B -c \"{bootstrap}\""
        ))
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use crate::events::Events;
    use std::{fs, os::unix::fs::PermissionsExt};

    #[test]
    fn qconn_upload_checks_bytes_and_waits_for_launcher_completion() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("adb");
        fs::write(
            &path,
            r#"#!/usr/bin/env python3
import hashlib, pathlib, re, sys
root = pathlib.Path(__file__).parent
source = sys.stdin.buffer
target = sys.stdout.buffer
def send(value):
    target.write(value); target.flush()
def line():
    return source.readline().decode().strip()
send(b'QCONN\r\n\xff\xfd\x22')
assert source.read(6) == b'\xff\xfc\x22\xff\xfe\x01'
send(b'OK\r\n<qconn-broker>')
service = line()
if service == 'service file':
    send(b'OK\r\n<qconn-file>')
    request = line()
    assert request.endswith(':501:180')
    name = pathlib.Path(request.split('"')[1]).name
    send(b'o:7:8180:0:"file"\r\n<qconn-file>')
    data = bytearray()
    while True:
        request = line()
        if request == 'c:7':
            (root / name).write_bytes(data)
            send(b'o\r\n<qconn-file>')
            source.read()
            break
        operation, descriptor, offset, count = request.split(':')
        assert operation == 'w' and descriptor == '7' and int(offset, 16) == len(data)
        chunk = source.read(int(count, 16)); data.extend(chunk)
        send(('o:%x:0:0\r\n<qconn-file>' % len(chunk)).encode())
elif service == 'service launcher':
    send(b'OK\r\n<qconn-launcher>')
    request = line()
    name = pathlib.Path(re.search("p='([^']+)'", request)[1]).name
    expected = re.search("hexdigest\(\)=='([^']+)'", request)[1]
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected
    assert "'inspect','--model','H97X'" in request
    send(b'OK 42\r\n<qconn-launcher>')
    assert line() == 'continue'
    send(b'OK\r\nVOYAHTUNE_NAVIGATION_FONT_RESULT {"ok":true}\n')
else:
    raise AssertionError(service)
"#,
        )
        .unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
        let adb = Adb::new(&path, Events::quiet())
            .unwrap()
            .with_device("test")
            .unwrap();
        let script = vec![b'x'; 7000];
        let output = adb
            .qnx_navigation_fonts(&script, "inspect", "H97X")
            .unwrap();
        assert!(output.contains("VOYAHTUNE_NAVIGATION_FONT_RESULT {\"ok\":true}"));
        assert!(adb
            .qnx_navigation_fonts(&script, "inspect", "H97C")
            .is_err());
        assert!(adb
            .qnx_navigation_fonts(&script, "arbitrary", "H97X")
            .is_err());
    }

    #[test]
    #[ignore = "Read-only check on an explicitly selected connected H97X/H97Y"]
    fn qconn_live_navigation_font_inspection() {
        let adb = Adb::new(
            std::env::var("VOYAHTUNE_TEST_ADB").unwrap(),
            Events::quiet(),
        )
        .unwrap()
        .with_device(&std::env::var("VOYAHTUNE_TEST_SERIAL").unwrap())
        .unwrap();
        let properties = adb
            .read(release_core::vehicle_model::PROPERTIES_COMMAND)
            .unwrap();
        let model = match release_core::vehicle_model::VehicleModel::detect(&properties) {
            release_core::vehicle_model::VehicleModel::H97X => "H97X",
            release_core::vehicle_model::VehicleModel::H97Y => "H97Y",
            _ => panic!("Unsupported test vehicle"),
        };
        let output = adb
            .qnx_navigation_fonts(
                include_bytes!("../../../../../Packaging/new/install/navigation_fonts.py"),
                "inspect",
                model,
            )
            .unwrap();
        let line = output
            .lines()
            .find_map(|line| {
                line.trim()
                    .strip_prefix("VOYAHTUNE_NAVIGATION_FONT_RESULT ")
            })
            .expect(&output);
        let report: serde_json::Value = serde_json::from_str(line).unwrap();
        assert_eq!(report["ok"], true, "{output}");
        assert_eq!(report["themes"].as_array().unwrap().len(), 3);
        println!("{report}");
    }
}
