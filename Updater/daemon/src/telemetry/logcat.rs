use super::{collecting_session as session_id, logcat_line, record};
use serde_json::json;
use std::{
    io::{self, Read},
    os::fd::AsRawFd,
    process::{Command, Stdio},
    thread,
    time::Duration,
};

pub fn run() {
    loop {
        let Some(id) = session_id() else {
            thread::sleep(Duration::from_millis(250));
            continue;
        };
        let result = collect(&id);
        if let Err(error) = result {
            record("step", "logcat.error", json!({"message":error.to_string()}));
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn collect(id: &str) -> io::Result<()> {
    let mut child = Command::new("/system/bin/logcat")
        .args(["-b", "all", "-v", "threadtime"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut stdout = child.stdout.take().unwrap();
    let descriptor = stdout.as_raw_fd();
    let flags = unsafe { libc::fcntl(descriptor, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(descriptor, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
    {
        let error = io::Error::last_os_error();
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    let mut pending = Vec::new();
    let mut buffer = [0u8; 8192];
    let result = (|| -> io::Result<()> {
        loop {
            if session_id().as_deref() != Some(id) {
                break Ok(());
            }
            match stdout.read(&mut buffer) {
                Ok(0) => break Err(io::Error::other("logcat stopped")),
                Ok(length) => {
                    pending.extend_from_slice(&buffer[..length]);
                    while let Some(end) = pending
                        .iter()
                        .position(|byte| *byte == b'\n')
                        .map(|index| index + 1)
                        .or_else(|| (pending.len() >= 8192).then_some(8192))
                    {
                        let text = String::from_utf8_lossy(&pending[..end]).into_owned();
                        logcat_line(id, text)?;
                        pending.drain(..end);
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(50))
                }
                Err(error) => break Err(error),
            }
        }
    })();
    let _ = child.kill();
    let _ = child.wait();
    result
}
