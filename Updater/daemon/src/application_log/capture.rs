use super::storage::{timestamp, Store};
use crate::config::invalid;
use std::{
    io::{self, Read},
    os::fd::AsRawFd,
    process::{Child, ChildStdout, Command, Stdio},
};

const MAX_LINE_BYTES: usize = 16 * 1024;

pub struct Capture {
    child: Child,
    stdout: ChildStdout,
    pending: Vec<u8>,
    boundary: Option<String>,
}

pub fn current_boundary() -> io::Result<String> {
    let output = Command::new("/system/bin/sh")
        .args(["-c", "date '+%m-%d %H:%M:%S.%N' | cut -c1-21"])
        .output()?;
    let value = String::from_utf8_lossy(&output.stdout).trim().to_owned();
    if !output.status.success() || timestamp(&value).is_none() {
        return Err(invalid("Не удалось определить время начала записи"));
    }
    Ok(value)
}

fn arguments(since: Option<&str>) -> Vec<String> {
    let mut args: Vec<String> = [
        "-b",
        "all",
        "-v",
        "threadtime",
        "-v",
        "usec",
        "-v",
        "printable",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect();
    if let Some(since) = since {
        args.extend(["-T".to_owned(), since.to_owned()]);
    }
    args.push("*:V".into());
    args
}

impl Capture {
    pub fn start(since: Option<String>) -> io::Result<Self> {
        let mut command = Command::new("/system/bin/logcat");
        command
            .args(arguments(since.as_deref()))
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(target_os = "android")]
        {
            use std::os::unix::process::CommandExt;
            // A daemon crash must not leave a second collector behind after init restarts it.
            unsafe {
                command.pre_exec(|| {
                    if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0 {
                        return Err(io::Error::last_os_error());
                    }
                    Ok(())
                });
            }
        }
        let mut child = command.spawn()?;
        let stdout = child.stdout.take().unwrap();
        let descriptor = stdout.as_raw_fd();
        let flags = unsafe { libc::fcntl(descriptor, libc::F_GETFL) };
        if flags < 0
            || unsafe { libc::fcntl(descriptor, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
        {
            let error = io::Error::last_os_error();
            let _ = child.kill();
            let _ = child.wait();
            return Err(error);
        }
        Ok(Self {
            child,
            stdout,
            pending: Vec::new(),
            boundary: since,
        })
    }

    pub fn poll(&mut self, store: &mut Store) -> io::Result<()> {
        let mut buffer = [0u8; 8192];
        // Bound each turn so noisy system logs cannot starve stop/status requests.
        for _ in 0..32 {
            match self.stdout.read(&mut buffer) {
                Ok(0) => return Err(invalid("Системный logcat завершился; перезапускаем запись")),
                Ok(length) => self.ingest(&buffer[..length], store)?,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }

    fn ingest(&mut self, bytes: &[u8], store: &mut Store) -> io::Result<()> {
        self.pending.extend_from_slice(bytes);
        while let Some(end) = self
            .pending
            .iter()
            .position(|byte| *byte == b'\n')
            .map(|index| index + 1)
            .or_else(|| (self.pending.len() >= MAX_LINE_BYTES).then_some(MAX_LINE_BYTES))
        {
            let end = end.min(MAX_LINE_BYTES);
            let text = String::from_utf8_lossy(&self.pending[..end]);
            let historical = self
                .boundary
                .as_deref()
                .zip(timestamp(&text))
                .is_some_and(|(boundary, timestamp)| timestamp < boundary);
            if !historical {
                store.append(&text)?;
                if timestamp(&text).is_some() {
                    // The boundary only filters startup history; later clock corrections are valid.
                    self.boundary = None;
                }
            }
            self.pending.drain(..end);
        }
        Ok(())
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_buffers_and_levels_are_collected_without_pid_filter() {
        let boot = arguments(None);
        assert!(boot.windows(2).any(|pair| pair == ["-b", "all"]));
        assert!(boot.contains(&"*:V".to_owned()));
        assert!(!boot.iter().any(|arg| arg.contains("pid") || arg == "-T"));
        let manual = arguments(Some("10-19 12:00:00.123456"));
        assert!(manual
            .windows(2)
            .any(|pair| pair == ["-T", "10-19 12:00:00.123456"]));
    }

    #[test]
    fn manual_capture_skips_history_but_keeps_system_crashes_and_other_apps() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = Store::open(directory.path().into(), "boot").unwrap();
        store.start("10-19 12:00:00.000000".into()).unwrap();
        let mut child = Command::new("sh")
            .args(["-c", "exit 0"])
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let stdout = child.stdout.take().unwrap();
        let mut capture = Capture {
            child,
            stdout,
            pending: Vec::new(),
            boundary: store.since(),
        };
        capture.ingest(b"10-19 11:59:59.999999 1 2 I OldApp: history\n10-19 12:00:01.000000 1 2 E AndroidRun", &mut store).unwrap();
        capture
            .ingest(
                b"time: FATAL EXCEPTION\n10-19 12:00:02.000000 3 4 D OtherApp: debug\n10-19 11:00:00.000000 3 4 I System: clock adjusted\n",
                &mut store,
            )
            .unwrap();
        store.stop().unwrap();
        let text = std::fs::read_to_string(store.path()).unwrap();
        assert!(!text.contains("history"));
        assert!(text.contains("AndroidRuntime: FATAL EXCEPTION"));
        assert!(text.contains("OtherApp: debug"));
        assert!(text.contains("System: clock adjusted"));
    }
}
