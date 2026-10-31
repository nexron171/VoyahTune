use super::*;
use std::io::{BufRead, BufReader};
use std::net::TcpListener;
use std::{fs, os::unix::fs::PermissionsExt};

fn peer_line(reader: &mut impl BufRead) -> String {
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    line.trim().into()
}

fn serve_qconn(listener: TcpListener, report: String, short_write: bool) {
    let mut uploaded = Vec::new();
    for service in ["service file", "service launcher"] {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut input = BufReader::new(stream.try_clone().unwrap());
        stream.write_all(b"QCONN\r\n\xff\xfd\x22").unwrap();
        let mut negotiation = [0; 6];
        input.read_exact(&mut negotiation).unwrap();
        assert_eq!(negotiation, [255, 252, 34, 255, 254, 1]);
        stream.write_all(b"OK\r\n<qconn-broker>").unwrap();
        assert_eq!(peer_line(&mut input), service);
        if service == "service file" {
            stream.write_all(b"OK\r\n<qconn-file>").unwrap();
            assert!(peer_line(&mut input).ends_with(":501:180"));
            stream
                .write_all(b"o:7:8180:0:\"file\"\r\n<qconn-file>")
                .unwrap();
            loop {
                let command = peer_line(&mut input);
                if command == "c:7" {
                    stream.write_all(b"o\r\n<qconn-file>").unwrap();
                    break;
                }
                let fields: Vec<_> = command.split(':').collect();
                assert_eq!(&fields[..2], ["w", "7"]);
                assert_eq!(
                    usize::from_str_radix(fields[2], 16).unwrap(),
                    uploaded.len()
                );
                let count = usize::from_str_radix(fields[3], 16).unwrap();
                let mut chunk = vec![0; count];
                input.read_exact(&mut chunk).unwrap();
                uploaded.extend(chunk);
                let reply = if short_write { count - 1 } else { count };
                stream
                    .write_all(format!("o:{reply:x}:0:0\r\n<qconn-file>").as_bytes())
                    .unwrap();
                if short_write {
                    return;
                }
            }
        } else {
            stream.write_all(b"OK\r\n<qconn-launcher>").unwrap();
            let command = peer_line(&mut input);
            assert!(command.contains(&hex::encode(Sha256::digest(&uploaded))));
            assert_eq!(uploaded, SCRIPT);
            stream.write_all(b"OK 42\r\n<qconn-launcher>").unwrap();
            assert_eq!(peer_line(&mut input), "continue");
            stream
                .write_all(format!("{RESULT_PREFIX}{report}\n").as_bytes())
                .unwrap();
        }
    }
}

#[test]
fn direct_tcp_uses_the_same_script_and_preserves_skip_or_failure_results() {
    for (skipped, ok) in [(false, true), (true, true), (false, false)] {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let report = serde_json::json!({"ok":ok, "action":"apply", "model":"H97Y",
            "themes":[{},{},{}], "skipped":skipped, "reason":"unsupported_theme"});
        let peer = thread::spawn(move || serve_qconn(listener, report.to_string(), false));
        let result = run(&Endpoint::Tcp(address), "apply", "H97Y");
        if ok {
            assert_eq!(result.unwrap()["skipped"], skipped);
        } else {
            assert!(result.is_err());
        }
        peer.join().unwrap();
    }
}

#[test]
fn short_upload_never_starts_the_font_operation() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let peer = thread::spawn(move || serve_qconn(listener, String::new(), true));
    assert!(run(&Endpoint::Tcp(address), "apply", "H97X").is_err());
    peer.join().unwrap();
}

#[test]
#[ignore = "Read-only check on an explicitly selected connected H97X/H97Y"]
fn qconn_live_navigation_font_inspection() {
    use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};
    let path = std::path::PathBuf::from(std::env::var("VOYAHTUNE_TEST_ADB").unwrap());
    let serial = std::env::var("VOYAHTUNE_TEST_SERIAL").unwrap();
    let output = Command::new(&path)
        .args(["-s", &serial, "shell", PROPERTIES_COMMAND])
        .output()
        .unwrap();
    assert!(output.status.success());
    let model = match VehicleModel::detect(&String::from_utf8_lossy(&output.stdout)) {
        VehicleModel::H97X => "H97X",
        VehicleModel::H97Y => "H97Y",
        VehicleModel::Other => panic!("Unsupported test vehicle"),
    };
    let report = run(
        &Endpoint::Adb {
            path: &path,
            serial: &serial,
        },
        "inspect",
        model,
    )
    .unwrap();
    assert_eq!(report["themes"].as_array().unwrap().len(), 3);
    println!("{report}");
}

#[test]
fn qconn_upload_checks_bytes_and_waits_for_launcher_completion() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("adb");
    fs::write(
        &path,
        r#"#!/usr/bin/env python3
import hashlib, json, pathlib, re, sys
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
    arguments = json.loads(bytes.fromhex(re.search("sys.argv=json.loads\(bytes.fromhex\('([^']+)'", request)[1]))
    assert arguments[1:] == ['inspect', '--model', 'H97X']
    send(b'OK 42\r\n<qconn-launcher>')
    assert line() == 'continue'
    send(b'OK\r\nVOYAHTUNE_NAVIGATION_FONT_RESULT {"ok":true}\n')
else:
    raise AssertionError(service)
"#,
    )
    .unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
    let endpoint = Endpoint::Adb {
        path: &path,
        serial: "test",
    };
    let script = vec![b'x'; 7000];
    let output = run_script(&endpoint, &script, "inspect", "H97X").unwrap();
    assert!(output.contains("VOYAHTUNE_NAVIGATION_FONT_RESULT {\"ok\":true}"));
    assert!(run_script(&endpoint, &script, "inspect", "H97C").is_err());
    assert!(run_script(&endpoint, &script, "arbitrary", "H97X").is_err());
}

#[test]
fn launcher_delivers_complete_progress_lines_before_operation_finishes() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let (observed, received) = std::sync::mpsc::channel();
    let peer = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut input = BufReader::new(stream.try_clone().unwrap());
        stream.write_all(b"QCONN\r\n").unwrap();
        let mut negotiation = [0; 6];
        input.read_exact(&mut negotiation).unwrap();
        stream.write_all(b"<qconn-broker>").unwrap();
        assert_eq!(peer_line(&mut input), "service launcher");
        stream.write_all(b"OK\r\n<qconn-launcher>").unwrap();
        assert_eq!(peer_line(&mut input), "start/flags 8000 test");
        stream.write_all(b"OK 42\r\n<qconn-launcher>").unwrap();
        assert_eq!(peer_line(&mut input), "continue");
        stream.write_all(b"VOYAHTUNE_TURN_SOUND_PROG").unwrap();
        stream
            .write_all(b"RESS {\"stage\":\"backup_di\"}\n")
            .unwrap();
        received.recv_timeout(Duration::from_secs(3)).unwrap();
        stream.write_all(b"finished\n").unwrap();
    });
    let mut lines = Vec::new();
    let output = Connection::open(&Endpoint::Tcp(address))
        .unwrap()
        .launch("test", &mut |line| {
            lines.push(line.to_owned());
            if line.contains("backup_di") {
                observed.send(()).unwrap();
            }
        })
        .unwrap();
    peer.join().unwrap();
    assert_eq!(
        lines,
        [
            "VOYAHTUNE_TURN_SOUND_PROGRESS {\"stage\":\"backup_di\"}\n",
            "finished\n"
        ]
    );
    assert!(output.ends_with("finished\n"));
}
