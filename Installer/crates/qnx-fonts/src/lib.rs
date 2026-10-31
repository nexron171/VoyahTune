pub mod turn_sound;

// Shared QNX transport for USB installation and on-device operations.

use release_core::{Error, Result};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    net::{Shutdown, SocketAddr, TcpStream},
    path::Path,
    process::{Child, Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, Instant},
};

const FILE_PROMPT: &[u8] = b"<qconn-file>";
const LAUNCHER_PROMPT: &[u8] = b"<qconn-launcher>";
const OUTPUT_LIMIT: usize = 256 * 1024;
const SCRIPT: &[u8] = include_bytes!("../../../../Packaging/new/install/navigation_fonts.py");
const RESULT_PREFIX: &str = "VOYAHTUNE_NAVIGATION_FONT_RESULT ";

pub enum Endpoint<'a> {
    Adb { path: &'a Path, serial: &'a str },
    Tcp(SocketAddr),
}

pub fn apply_on_device(model: &str) -> Result<serde_json::Value> {
    run(
        &Endpoint::Tcp(([172, 16, 104, 41], 8000).into()),
        "apply",
        model,
    )
}

pub fn run(endpoint: &Endpoint<'_>, action: &str, model: &str) -> Result<serde_json::Value> {
    parse_result(&run_script(endpoint, SCRIPT, action, model)?, action, model)
}

pub fn parse_result(output: &str, action: &str, model: &str) -> Result<serde_json::Value> {
    let results: Vec<_> = output
        .lines()
        .filter_map(|line| line.trim().strip_prefix(RESULT_PREFIX))
        .collect();
    if results.len() != 1 {
        return Err(
            Error::new("QNX_FONT_RESULT", "Приборка не подтвердила обработку тем").detail(output),
        );
    }
    let result: serde_json::Value = serde_json::from_str(results[0])?;
    if result["ok"] != true
        || result["action"] != action
        || result["model"] != model
        || !result["themes"]
            .as_array()
            .is_some_and(|themes| themes.len() == 3)
    {
        return Err(Error::new(
            "QNX_FONT_FAILED",
            "Не удалось обработать шрифты навигации приборки",
        )
        .detail(result));
    }
    Ok(result)
}

fn failure(detail: impl std::fmt::Display) -> Error {
    Error::new(
        "QNX_TRANSPORT",
        "Не удалось выполнить операцию с темами приборки",
    )
    .detail(detail)
}

struct Connection {
    child: Option<Child>,
    socket: Option<TcpStream>,
    input: Box<dyn Write>,
    output: Receiver<std::io::Result<Vec<u8>>>,
    buffer: Vec<u8>,
    deadline: Instant,
}

impl Connection {
    fn open(endpoint: &Endpoint<'_>) -> Result<Self> {
        if let Endpoint::Tcp(address) = endpoint {
            let socket = TcpStream::connect_timeout(address, Duration::from_secs(5))?;
            socket.set_read_timeout(Some(Duration::from_secs(30)))?;
            socket.set_write_timeout(Some(Duration::from_secs(30)))?;
            return Self::start(
                socket.try_clone()?,
                Box::new(socket.try_clone()?),
                None,
                Some(socket),
            );
        }
        let Endpoint::Adb { path, serial } = endpoint else {
            unreachable!()
        };
        let mut command = Command::new(path);
        command
            .args([
                "-s",
                *serial,
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
        let mut child = command.spawn()?;
        let input = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let mut stderr = child.stderr.take().unwrap();
        thread::spawn(move || {
            let _ = std::io::copy(&mut stderr, &mut std::io::sink());
        });
        Self::start(stdout, Box::new(input), Some(child), None)
    }

    fn start(
        mut reader: impl Read + Send + 'static,
        input: Box<dyn Write>,
        child: Option<Child>,
        socket: Option<TcpStream>,
    ) -> Result<Self> {
        let (sender, output) = mpsc::sync_channel(32);
        thread::spawn(move || {
            let mut bytes = [0; 8192];
            loop {
                match reader.read(&mut bytes) {
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
        let mut connection = Self {
            child,
            socket,
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

    fn launch(&mut self, command: &str, output: &mut dyn FnMut(&str)) -> Result<String> {
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
        let mut processed = 0;
        loop {
            while let Some(offset) = self.buffer[processed..]
                .iter()
                .position(|byte| *byte == b'\n')
            {
                let end = processed + offset + 1;
                output(&String::from_utf8_lossy(&self.buffer[processed..end]));
                processed = end;
            }
            if !self.receive()? {
                break;
            }
        }
        Ok(String::from_utf8_lossy(&self.buffer).into_owned())
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        if let Some(child) = self.child.as_mut() {
            let _ = child.kill();
            let _ = child.wait();
        }
        if let Some(socket) = self.socket.as_ref() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

fn run_script(endpoint: &Endpoint<'_>, script: &[u8], action: &str, model: &str) -> Result<String> {
    if !["inspect", "apply", "restore"].contains(&action) || !["H97X", "H97Y"].contains(&model) {
        return Err(failure("Unsupported font operation or vehicle model"));
    }
    execute_script(endpoint, script, &[action, "--model", model])
}

fn execute_script(endpoint: &Endpoint<'_>, script: &[u8], arguments: &[&str]) -> Result<String> {
    execute_script_with_progress(endpoint, script, arguments, &mut |_| {})
}

fn execute_script_with_progress(
    endpoint: &Endpoint<'_>,
    script: &[u8],
    arguments: &[&str],
    output: &mut dyn FnMut(&str),
) -> Result<String> {
    let nonce = tempfile::Builder::new()
        .prefix("qnx-operation-")
        .tempdir()?;
    let token = nonce.path().file_name().unwrap().to_string_lossy();
    let remote = format!("/var/data/voyahtune-{token}.py");
    Connection::open(endpoint)?.upload(&remote, script)?;
    let hash = hex::encode(Sha256::digest(script));
    let mut argv = vec![remote.as_str()];
    argv.extend_from_slice(arguments);
    let argv = hex::encode(serde_json::to_vec(&argv)?);
    let bootstrap = format!(
            "import hashlib,json,os,sys; p='{remote}'; d=open(p,'rb').read(); assert hashlib.sha256(d).hexdigest()=='{hash}'; os.unlink(p); sys.argv=json.loads(bytes.fromhex('{argv}')); exec(compile(d,p,'exec'))"
        );
    Connection::open(endpoint)?.launch(
        &format!("/usr/bin/python3 python3 -u -B -c \"{bootstrap}\""),
        output,
    )
}

#[cfg(all(test, unix))]
mod tests;
