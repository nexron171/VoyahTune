use super::journal::{retry_delay, MAX_BYTES};
use crate::{config::invalid, state};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs, io,
    path::{Path, PathBuf},
    sync::mpsc,
    time::{Duration, Instant},
};

const ENDPOINT: &str = "https://voyahtune.ru/api/v1/installation_log_chunks";

fn send(path: &Path) -> io::Result<Option<Duration>> {
    let body: Value = state::read(path)?;
    let bytes = serde_json::to_vec(&body)?;
    if bytes.len() > MAX_BYTES {
        return Err(invalid("OTA chunk exceeds 1 MiB"));
    }
    send_with(
        path,
        ENDPOINT,
        &ureq::Agent::config_builder()
            .https_only(true)
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(10)))
            .build()
            .new_agent(),
        &bytes,
    )
}

fn send_with(
    path: &Path,
    endpoint: &str,
    agent: &ureq::Agent,
    bytes: &[u8],
) -> io::Result<Option<Duration>> {
    let response = agent
        .post(endpoint)
        .header("Content-Type", "application/json")
        .send(bytes)
        .map_err(|error| invalid(&error.to_string()))?;
    let status = response.status().as_u16();
    if status == 200 {
        state::save(
            path.parent().unwrap(),
            &format!("{}.sent", path.file_stem().unwrap().to_string_lossy()),
            &true,
        )?;
        return Ok(None);
    }
    if status == 429 || status >= 500 {
        let delay = response
            .headers()
            .get("Retry-After")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| {
                value
                    .parse::<u64>()
                    .ok()
                    .map(Duration::from_secs)
                    .or_else(|| {
                        chrono::DateTime::parse_from_rfc2822(value)
                            .ok()
                            .map(|time| {
                                (time.with_timezone(&chrono::Utc) - chrono::Utc::now())
                                    .to_std()
                                    .unwrap_or_default()
                            })
                    })
            })
            .unwrap_or(Duration::from_secs(1));
        return Ok(Some(delay));
    }
    // Keep rejected requests for diagnosis; never mutate or repeatedly submit a conflicting chunk.
    state::save(
        path.parent().unwrap(),
        &format!("{}.rejected", path.file_stem().unwrap().to_string_lossy()),
        &serde_json::json!({"httpStatus":status}),
    )?;
    Ok(None)
}

fn pending(root: &Path) -> io::Result<Vec<PathBuf>> {
    let mut paths = vec![];
    for session in fs::read_dir(root)? {
        let folder = session?.path().join("chunks");
        if !folder.is_dir() {
            continue;
        }
        for item in fs::read_dir(folder)? {
            let path = item?.path();
            if path
                .extension()
                .is_some_and(|extension| extension == "json")
                && !path.with_extension("sent").exists()
                && !path.with_extension("rejected").exists()
            {
                paths.push(path);
            }
        }
    }
    let active: Option<Value> = state::read(&root.join("active.json")).ok();
    let active_directory = active
        .as_ref()
        .and_then(|value| value["sessionId"].as_str())
        .map(|id| root.join(id));
    paths.sort_by_key(|path| {
        (
            active_directory
                .as_ref()
                .is_some_and(|directory| path.starts_with(directory)),
            path.clone(),
        )
    });
    paths.reverse();
    Ok(paths)
}

pub struct Flush {
    pub path: PathBuf,
    pub reply: mpsc::Sender<io::Result<()>>,
}

fn retry_rejected_after_boot(root: &Path) -> io::Result<()> {
    for session in fs::read_dir(root)? {
        let folder = session?.path().join("chunks");
        if !folder.is_dir() {
            continue;
        }
        for entry in fs::read_dir(folder)? {
            let path = entry?.path();
            if path
                .extension()
                .is_some_and(|extension| extension == "rejected")
            {
                fs::remove_file(path)?;
            }
        }
    }
    Ok(())
}

pub fn run(root: PathBuf, requests: mpsc::Receiver<Flush>) {
    if let Err(error) = retry_rejected_after_boot(&root) {
        eprintln!("OTA outbox recovery: {error}");
    }
    run_with(root, requests, send);
}

fn run_with(
    root: PathBuf,
    requests: mpsc::Receiver<Flush>,
    mut deliver: impl FnMut(&Path) -> io::Result<Option<Duration>>,
) {
    let mut retries: BTreeMap<PathBuf, (u32, Instant)> = BTreeMap::new();
    let mut deferred_until_restart = BTreeSet::new();
    loop {
        match requests.recv_timeout(Duration::from_millis(100)) {
            Ok(request) => {
                if !request.path.with_extension("sent").exists() {
                    if let Err(error) = deliver(&request.path) {
                        eprintln!("OTA pre-reboot delivery deferred: {error}");
                    }
                }
                if !request.path.with_extension("sent").exists() {
                    deferred_until_restart.insert(request.path.clone());
                }
                // The immutable packet is already durable, including after a failed attempt.
                let _ = request.reply.send(Ok(()));
                continue;
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
        }
        retries.retain(|path, _| path.exists());
        deferred_until_restart.retain(|path: &PathBuf| path.exists());
        match pending(&root) {
            Ok(paths) => {
                if let Some(path) = paths.into_iter().find(|path| {
                    !deferred_until_restart.contains(path)
                        && retries
                            .get(path)
                            .is_none_or(|(_, due)| *due <= Instant::now())
                }) {
                    let attempt = retries.get(&path).map_or(0, |(attempt, _)| *attempt);
                    let delay = match deliver(&path) {
                        Ok(None) => {
                            retries.remove(&path);
                            continue;
                        }
                        Ok(Some(delay)) => delay.max(Duration::from_millis(retry_delay(attempt))),
                        Err(error) => {
                            eprintln!("OTA log delivery: {error}");
                            Duration::from_millis(retry_delay(attempt))
                        }
                    };
                    retries.insert(path, (attempt.saturating_add(1), Instant::now() + delay));
                }
            }
            Err(error) => eprintln!("OTA log outbox: {error}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{BufRead, BufReader, Read, Write},
        net::TcpListener,
        thread,
    };

    #[test]
    fn reboot_ack_waits_for_attempt_and_network_failure_keeps_packet() {
        let root = tempfile::tempdir().unwrap();
        let folder = root.path().join("ota-test/chunks");
        fs::create_dir_all(&folder).unwrap();
        let path = folder.join("000000000001.json");
        fs::write(&path, b"packet").unwrap();
        let folder = root.path().to_owned();
        let (requests, receiver) = mpsc::channel();
        let (started, attempt) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let worker = thread::spawn(move || {
            run_with(folder, receiver, |_| {
                started.send(()).unwrap();
                wait.recv().unwrap();
                Err(io::Error::other("offline"))
            })
        });
        let (reply, acknowledgement) = mpsc::channel();
        requests
            .send(Flush {
                path: path.clone(),
                reply,
            })
            .unwrap();
        attempt.recv_timeout(Duration::from_secs(2)).unwrap();
        assert!(acknowledgement.try_recv().is_err());
        release.send(()).unwrap();
        acknowledgement
            .recv_timeout(Duration::from_secs(2))
            .unwrap()
            .unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"packet");
        assert!(!path.with_extension("sent").exists());
        assert!(matches!(
            attempt.recv_timeout(Duration::from_millis(250)),
            Err(mpsc::RecvTimeoutError::Timeout)
        ));
        drop(requests);
        worker.join().unwrap();
    }

    #[test]
    fn retry_keeps_identical_request_and_marks_only_acknowledged_chunks() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("000000000001.json");
        let bytes = br#"{"sessionId":"ota-test","chunkId":"ota-test:1"}"#;
        fs::write(&path, bytes).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let server = thread::spawn(move || {
            let mut bodies = Vec::new();
            for status in ["503 Service Unavailable", "200 OK"] {
                let (mut stream, _) = listener.accept().unwrap();
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut length = 0;
                loop {
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    if line == "\r\n" {
                        break;
                    }
                    if let Some(value) = line.to_lowercase().strip_prefix("content-length:") {
                        length = value.trim().parse().unwrap();
                    }
                }
                let mut body = vec![0u8; length];
                reader.read_exact(&mut body).unwrap();
                bodies.push(body);
                write!(stream, "HTTP/1.1 {status}\r\nRetry-After: 12\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
            }
            bodies
        });
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .build()
            .new_agent();
        assert_eq!(
            send_with(&path, &endpoint, &agent, bytes).unwrap(),
            Some(Duration::from_secs(12))
        );
        assert!(!path.with_extension("sent").exists());
        assert_eq!(send_with(&path, &endpoint, &agent, bytes).unwrap(), None);
        assert!(path.with_extension("sent").exists());
        assert_eq!(fs::read(&path).unwrap(), bytes);
        let bodies = server.join().unwrap();
        assert_eq!(bodies[0], bodies[1]);
    }
}
