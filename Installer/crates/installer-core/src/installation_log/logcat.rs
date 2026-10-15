use super::{InstallationLog, Shared};
use crate::{adb::Adb, events::Events};
use serde_json::json;
use std::{
    io::{BufRead, BufReader},
    path::PathBuf,
    process::{Command, Stdio},
    sync::{atomic::Ordering, Arc, Mutex, Weak},
    thread::{self, JoinHandle},
    time::Duration,
};

fn record(shared: &Weak<Shared>, name: &str, data: serde_json::Value) {
    if let Some(shared) = shared.upgrade() {
        if let Err(error) = InstallationLog(shared).record("logcat", name, None, None, data) {
            eprintln!("Logcat journal failed: {error}");
        }
    }
}

fn stopped(shared: &Weak<Shared>) -> bool {
    shared
        .upgrade()
        .is_none_or(|s| s.stopping.load(Ordering::Acquire))
}

fn pause(shared: &Weak<Shared>) {
    for _ in 0..10 {
        if stopped(shared) {
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
}

pub(super) fn start(shared: Weak<Shared>, adb_path: PathBuf) -> JoinHandle<()> {
    thread::spawn(move || {
        let mut previous_boot = String::new();
        let cursor = Arc::new(Mutex::new(String::new()));
        let mut segment = 0;
        let mut disconnected = false;
        while !stopped(&shared) {
            let Some(owner) = shared.upgrade() else {
                break;
            };
            let session = InstallationLog(owner);
            let adb = match Adb::new(&adb_path, Events::quiet()) {
                Ok(adb) => adb,
                Err(_) => {
                    drop(session);
                    pause(&shared);
                    continue;
                }
            };
            let serial = match session.info().device_serial {
                Some(serial) => serial,
                None => {
                    // Never collect from an arbitrary phone when several devices are connected.
                    let devices = adb.devices().unwrap_or_default();
                    if devices.len() != 1 || devices[0].state != "device" {
                        drop(session);
                        pause(&shared);
                        continue;
                    }
                    let serial = devices[0].serial.clone();
                    if session.bind_device(&serial).is_err() {
                        drop(session);
                        pause(&shared);
                        continue;
                    }
                    serial
                }
            };
            drop(session);
            let adb = match adb.with_device(&serial) {
                Ok(adb) => adb,
                Err(_) => break,
            };
            let boot = match adb.shell(
                "cat /proc/sys/kernel/random/boot_id",
                Duration::from_secs(3),
            ) {
                Ok(boot) if !boot.is_empty() => boot,
                _ => {
                    if !disconnected {
                        record(
                            &shared,
                            "gap-started",
                            json!({"serial":serial,"reason":"ADB unavailable"}),
                        );
                        disconnected = true;
                    }
                    pause(&shared);
                    continue;
                }
            };
            segment += 1;
            let same_boot = previous_boot == boot;
            let since = if same_boot {
                cursor.lock().unwrap().clone()
            } else {
                String::new()
            };
            if !same_boot {
                cursor.lock().unwrap().clear();
            }
            previous_boot = boot.clone();
            let mut command = Command::new(&adb_path);
            command.args(["-s", &serial, "logcat", "-b", "all", "-v", "threadtime"]);
            // On a new boot read the available ring buffers, including early startup failures.
            if !since.is_empty() {
                command.args(["-T", &since]);
            }
            command
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                command.creation_flags(0x08000000);
            }
            let mut child = match command.spawn() {
                Ok(child) => child,
                Err(error) => {
                    record(&shared, "gap-started", json!({"reason":error.to_string()}));
                    pause(&shared);
                    continue;
                }
            };
            record(
                &shared,
                "connected",
                json!({"serial":serial,"bootId":boot,"segment":segment,"resumedAfterGap":disconnected,"resumeTimestamp":since}),
            );
            let mut readers = Vec::new();
            for (stream, pipe) in [
                (
                    "stdout",
                    Box::new(child.stdout.take().unwrap()) as Box<dyn std::io::Read + Send>,
                ),
                (
                    "stderr",
                    Box::new(child.stderr.take().unwrap()) as Box<dyn std::io::Read + Send>,
                ),
            ] {
                let weak = shared.clone();
                let cursor = cursor.clone();
                let boot = boot.clone();
                readers.push(thread::spawn(move || {
                    let mut reader = BufReader::new(pipe);
                    loop {
                        // Bound a single record even when a producer omits newlines.
                        let mut bytes = Vec::new();
                        while bytes.len() < 65536 {
                            let buffer = match reader.fill_buf() {
                                Ok(buffer) => buffer,
                                Err(_) => return,
                            };
                            if buffer.is_empty() {
                                break;
                            }
                            let length = buffer
                                .iter()
                                .position(|b| *b == b'\n')
                                .map_or(buffer.len(), |n| n + 1)
                                .min(65536 - bytes.len());
                            let ended = buffer[length - 1] == b'\n';
                            bytes.extend_from_slice(&buffer[..length]);
                            reader.consume(length);
                            if ended {
                                break;
                            }
                        }
                        if bytes.is_empty() {
                            break;
                        }
                        let text = String::from_utf8_lossy(&bytes).into_owned();
                        if stream == "stdout"
                            && text.as_bytes().get(2) == Some(&b'-')
                            && text.as_bytes().get(5) == Some(&b' ')
                            && text.as_bytes().get(8) == Some(&b':')
                        {
                            if let Some(timestamp) = text.get(..18) {
                                *cursor.lock().unwrap() = timestamp.into();
                            }
                        }
                        record(
                            &weak,
                            "output",
                            json!({"bootId":boot,"segment":segment,"stream":stream,"text":text}),
                        );
                    }
                }));
            }
            loop {
                if stopped(&shared) {
                    let _ = child.kill();
                    break;
                }
                match child.try_wait() {
                    Ok(Some(_)) | Err(_) => break,
                    Ok(None) => {}
                }
                thread::sleep(Duration::from_millis(100));
            }
            let _ = child.kill();
            let status = child.wait().ok().and_then(|s| s.code());
            for reader in readers {
                let _ = reader.join();
            }
            record(
                &shared,
                "gap-started",
                json!({"bootId":boot,"segment":segment,"exitCode":status,"reason":if stopped(&shared) {"session_finished"} else {"stream_disconnected"}}),
            );
            disconnected = true;
            pause(&shared);
        }
    })
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use crate::installation_log::{Record, Status};
    use std::{fs, os::unix::fs::PermissionsExt, time::Instant};

    #[test]
    fn reconnects_across_boots_and_retains_both_streams_in_one_session() {
        let root = tempfile::tempdir().unwrap();
        let adb_path = root.path().join("adb");
        fs::write(root.path().join("boot"), "boot-one").unwrap();
        fs::write(
            &adb_path,
            r#"#!/bin/sh
root=$(dirname "$0")
case "$*" in
    'devices -l') printf 'List of devices attached\nCAR device model:Voyah\n' ;;
    *'shell sh -s') cat >/dev/null; cat "$root/boot" ;;
    *logcat*)
        boot=$(cat "$root/boot")
        printf '10-15 12:00:00.000  100  100 I TEST: %s\n' "$boot"
        echo logcat-stderr >&2
        echo boot-two > "$root/boot"
        ;;
    *) exit 1 ;;
esac
"#,
        )
        .unwrap();
        fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
        let session =
            InstallationLog::begin(&root.path().join("sessions"), &adb_path, None).unwrap();
        let deadline = Instant::now() + Duration::from_secs(6);
        let records = loop {
            let text = fs::read_to_string(session.directory().join("logs.jsonl")).unwrap();
            let records: Vec<Record> = text
                .lines()
                .filter_map(|line| serde_json::from_str(line).ok())
                .collect();
            if records.iter().any(|r| {
                r.data
                    .get("text")
                    .and_then(|v| v.as_str())
                    .is_some_and(|s| s.contains("boot-two"))
            }) {
                break records;
            }
            assert!(Instant::now() < deadline, "Second boot was not captured");
            thread::sleep(Duration::from_millis(30));
        };
        session.finish(Status::Success, None).unwrap();
        assert!(records
            .iter()
            .all(|r| r.session_id == session.info().session_id));
        assert!(records.iter().any(|r| r.name == "gap-started"));
        assert!(records
            .iter()
            .any(|r| r.data.get("stream").and_then(|v| v.as_str()) == Some("stderr")));
        assert!(records
            .iter()
            .any(|r| r.data.get("bootId").and_then(|v| v.as_str()) == Some("boot-one")));
        assert!(records
            .iter()
            .any(|r| r.data.get("bootId").and_then(|v| v.as_str()) == Some("boot-two")));
    }
}
