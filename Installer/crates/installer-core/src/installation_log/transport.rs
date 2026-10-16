#[cfg(test)]
use std::fs;
use super::{Chunk, SessionInfo, SpoolItem};
use chrono::Utc;
#[cfg(not(test))]
use fs2::FileExt;
use serde_json::json;
use std::{
    fs::{File, OpenOptions},
    io::{BufReader, Write},
    path::{Path, PathBuf},
    sync::mpsc,
    thread,
    time::Duration,
};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

pub(super) trait Transport {
    // Implementations must honor this deadline, including connection and response reads.
    fn send(&mut self, chunk: &Chunk, timeout: Duration) -> std::result::Result<(), String>;
}

const SESSION_URL: &str = "https://voyahtune.ru/api/v1/installation_sessions";
const CHUNK_URL: &str = "https://voyahtune.ru/api/v1/installation_log_chunks";

struct HttpTransport {
    agent: ureq::Agent,
    session_url: String,
    chunk_url: String,
    initial_session: SessionInfo,
    directory: PathBuf,
    registered: bool,
}

impl HttpTransport {
    fn new(directory: PathBuf, initial_session: SessionInfo) -> Self {
        let agent = ureq::Agent::config_builder()
            .https_only(true)
            .max_redirects(0)
            .timeout_global(Some(REQUEST_TIMEOUT))
            .tls_config(
                ureq::tls::TlsConfig::builder()
                    .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                    .build(),
            )
            .build()
            .new_agent();
        let registered = directory.join("session.ack").is_file();
        Self {
            agent,
            session_url: SESSION_URL.into(),
            chunk_url: CHUNK_URL.into(),
            initial_session,
            directory,
            registered,
        }
    }

    fn post(&self, url: &str, key: &str, value: &impl serde::Serialize) -> Result<(), String> {
        let body = serde_json::to_vec(value).map_err(|error| error.to_string())?;
        let response = self
            .agent
            .post(url)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .send(body.as_slice())
            .map_err(|error| format!("POST {url}: {error}"))?;
        if !response.status().is_success() {
            return Err(format!("POST {url}: HTTP {}", response.status()));
        }
        Ok(())
    }

    fn register(&mut self) -> Result<(), String> {
        if self.registered {
            return Ok(());
        }
        self.post(
            &self.session_url,
            &self.initial_session.session_id,
            &self.initial_session,
        )?;
        crate::recovery::write_json(&self.directory.join("session.ack"),
            &json!({"sessionId":self.initial_session.session_id,"acknowledgedAt":Utc::now().to_rfc3339()}))
            .map_err(|error| error.to_string())?;
        self.registered = true;
        Ok(())
    }
}

impl Transport for HttpTransport {
    fn send(&mut self, chunk: &Chunk, _: Duration) -> Result<(), String> {
        self.register()?;
        self.post(&self.chunk_url, &chunk.chunk_id, chunk)
    }
}

pub(super) fn deliver(
    transport: &mut dyn Transport,
    chunk: &Chunk,
    mut observe: impl FnMut(u32, &std::result::Result<(), String>),
) -> bool {
    let result = transport.send(chunk, REQUEST_TIMEOUT);
    observe(1, &result);
    result.is_ok()
}

#[cfg(test)]

fn pending_chunks(directory: &Path) -> crate::Result<Vec<PathBuf>> {
    let mut paths = Vec::new();
    for entry in fs::read_dir(directory.join("outbox"))? {
        let path = entry?.path();
        if path
            .extension()
            .is_some_and(|extension| extension == "json")
            && !path.with_extension("ack").exists()
        {
            paths.push(path);
        }
    }
    paths.sort();
    if let Some(last) = paths.last() {
        let item: SpoolItem = serde_json::from_reader(BufReader::new(File::open(last)?))?;
        if item.request.final_chunk {
            // Final status must not wait behind a large logcat backlog.
            paths.rotate_right(1);
        }
    }
    Ok(paths)
}

fn deliver_file(
    transport: &mut dyn Transport,
    directory: &Path,
    path: &Path,
) -> crate::Result<bool> {
    let item: SpoolItem = serde_json::from_reader(BufReader::new(File::open(path)?))?;
    let mut attempts = OpenOptions::new()
        .create(true)
        .append(true)
        .open(directory.join("delivery-attempts.jsonl"))?;
    let delivered = deliver(transport, &item.request, |attempt, result| {
        let record = json!({
            "sessionId": item.request.session_id,
            "chunkId": item.request.chunk_id,
            "attempt": attempt,
            "timestamp": Utc::now().to_rfc3339(),
            "success": result.is_ok(),
            "error": result.as_ref().err()
        });
        let _ = serde_json::to_writer(&mut attempts, &record);
        let _ = attempts.write_all(b"\n");
        let _ = attempts.flush();
    });
    if delivered {
        crate::recovery::write_json(
            &path.with_extension("ack"),
            &json!({"chunkId": item.request.chunk_id, "acknowledgedAt": Utc::now().to_rfc3339()}),
        )?;
    }
    Ok(delivered)
}

#[cfg(not(test))]
fn run_sender(
    receiver: mpsc::Receiver<PathBuf>,
    directory: PathBuf,
    initial_session: SessionInfo,
) -> crate::Result<()> {
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(directory.join("delivery.lock"))?;
    if lock.try_lock_exclusive().is_err() {
        return Ok(());
    }
    let mut transport = HttpTransport::new(directory.clone(), initial_session);
    let registered = transport.register();
    let mut attempts = OpenOptions::new()
        .create(true)
        .append(true)
        .open(directory.join("delivery-attempts.jsonl"))?;
    writeln!(
        attempts,
        "{}",
        json!({
            "sessionId": transport.initial_session.session_id,
            "endpoint": "installation_sessions",
            "timestamp": Utc::now().to_rfc3339(),
            "success": registered.is_ok(),
            "error": registered.err()
        })
    )?;
    run_delivery_loop(&mut transport, receiver, &directory)
}

fn run_delivery_loop(
    transport: &mut dyn Transport,
    receiver: mpsc::Receiver<PathBuf>,
    directory: &Path,
) -> crate::Result<()> {
    // Only newly announced packets are eligible; disk outboxes are local diagnostics.
    while let Ok(path) = receiver.recv() {
        if let Err(error) = deliver_file(transport, directory, &path) {
            eprintln!("Session delivery failed: {error}");
        }
    }
    Ok(())
}

// Tests drive the sender explicitly against loopback or an in-memory transport.
#[cfg(test)]
pub(super) fn start(_: mpsc::Receiver<PathBuf>, _: PathBuf, _: SessionInfo) {}

#[cfg(not(test))]
pub(super) fn start(receiver: mpsc::Receiver<PathBuf>, directory: PathBuf, initial: SessionInfo) {
    thread::spawn(move || {
        if let Err(error) = run_sender(receiver, directory, initial) {
            eprintln!("Session sender stopped: {error}");
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::installation_log::{InstallationLog, Status};
    use std::{
        io::{BufRead, BufReader, Read},
        net::TcpListener,
    };

    #[test]
    fn failed_outbox_survives_restart_and_final_status_is_delivered_first() {
        struct Sender {
            failing: bool,
            requests: Vec<String>,
        }
        impl Transport for Sender {
            fn send(&mut self, chunk: &Chunk, _: Duration) -> Result<(), String> {
                self.requests.push(serde_json::to_string(chunk).unwrap());
                if self.failing {
                    Err("offline".into())
                } else {
                    Ok(())
                }
            }
        }
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        let directory = session.directory();
        let first = session.cut_with(true, |_| {}).unwrap();
        let original = fs::read(&first).unwrap();
        let mut sender = Sender {
            failing: true,
            requests: vec![],
        };
        assert!(!deliver_file(&mut sender, &directory, &first).unwrap());
        assert!(!first.with_extension("ack").exists());
        session.finish(Status::Success, None).unwrap();
        drop(session);
        InstallationLog::recover_interrupted(root.path()).unwrap();
        let pending = pending_chunks(&directory).unwrap();
        assert_eq!(pending.len(), 2);
        assert_eq!(pending[0].file_name().unwrap(), "000000000002.json");
        let final_item: SpoolItem =
            serde_json::from_reader(File::open(&pending[0]).unwrap()).unwrap();
        assert!(final_item.request.final_chunk);
        assert_eq!(final_item.request.session.status, Status::Success);
        sender.failing = false;
        for path in pending {
            assert!(deliver_file(&mut sender, &directory, &path).unwrap());
        }
        assert!(pending_chunks(&directory).unwrap().is_empty());
        assert_eq!(fs::read(first).unwrap(), original);
        assert_eq!(sender.requests[0], *sender.requests.last().unwrap());
    }

    #[test]
    fn sender_does_not_replay_saved_or_failed_packets() {
        struct Recovering {
            calls: usize,
            first_request: Option<String>,
        }
        impl Transport for Recovering {
            fn send(&mut self, chunk: &Chunk, _: Duration) -> Result<(), String> {
                let request = serde_json::to_string(chunk).unwrap();
                assert_eq!(self.first_request.get_or_insert(request.clone()), &request);
                self.calls += 1;
                if self.calls <= 4 {
                    Err("temporary failure".into())
                } else {
                    Ok(())
                }
            }
        }
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        session.finish(Status::Success, None).unwrap();
        let directory = session.directory();
        drop(session);
        let (sender, receiver) = mpsc::channel();
        sender
            .send(pending_chunks(&directory).unwrap()[0].clone())
            .unwrap();
        drop(sender);
        let mut transport = Recovering {
            calls: 0,
            first_request: None,
        };
        run_delivery_loop(&mut transport, receiver, &directory).unwrap();
        assert_eq!(transport.calls, 1);
        assert!(!pending_chunks(&directory).unwrap().is_empty());
        let (sender, receiver) = mpsc::channel();
        drop(sender);
        run_delivery_loop(&mut transport, receiver, &directory).unwrap();
        assert_eq!(transport.calls, 1);
    }

    fn server(
        statuses: Vec<u16>,
    ) -> (
        String,
        thread::JoinHandle<Vec<(String, String, serde_json::Value)>>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = format!("http://{}", listener.local_addr().unwrap());
        let handle = thread::spawn(move || {
            let mut requests = Vec::new();
            for status in statuses {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut reader = BufReader::new(socket.try_clone().unwrap());
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                let request = line.trim().to_owned();
                let mut length = 0;
                let mut key = String::new();
                loop {
                    line.clear();
                    reader.read_line(&mut line).unwrap();
                    if line == "\r\n" {
                        break;
                    }
                    let (name, value) = line.split_once(':').unwrap();
                    if name.eq_ignore_ascii_case("content-length") {
                        length = value.trim().parse().unwrap();
                    }
                    if name.eq_ignore_ascii_case("idempotency-key") {
                        key = value.trim().into();
                    }
                }
                let mut body = vec![0; length];
                reader.read_exact(&mut body).unwrap();
                requests.push((request, key, serde_json::from_slice(&body).unwrap()));
                write!(
                    socket,
                    "HTTP/1.1 {status} Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
                .unwrap();
            }
            requests
        });
        (address, handle)
    }

    #[test]
    fn creates_session_before_chunks_and_reuses_json_after_failures() {
        let root = tempfile::tempdir().unwrap();
        let session =
            InstallationLog::begin(root.path(), std::path::Path::new("/missing-adb"), None)
                .unwrap();
        let initial = session.info();
        session.finish(Status::Success, None).unwrap();
        let spool: SpoolItem = serde_json::from_reader(
            File::open(session.directory().join("outbox/000000000001.json")).unwrap(),
        )
        .unwrap();
        let (url, server) = server(vec![503, 201, 500, 204, 200]);
        let mut transport = HttpTransport::new(session.directory(), initial.clone());
        transport.agent = ureq::Agent::config_builder()
            .max_redirects(0)
            .timeout_global(Some(REQUEST_TIMEOUT))
            .build()
            .new_agent();
        transport.session_url = format!("{url}/api/v1/installation_sessions");
        transport.chunk_url = format!("{url}/api/v1/installation_log_chunks");
        assert!(!session.directory().join("session.ack").exists());
        assert!(transport.send(&spool.request, REQUEST_TIMEOUT).is_err());
        assert!(!session.directory().join("session.ack").exists());
        assert!(transport.send(&spool.request, REQUEST_TIMEOUT).is_err());
        assert!(session.directory().join("session.ack").exists());
        assert!(transport.send(&spool.request, REQUEST_TIMEOUT).is_ok());
        assert!(transport.send(&spool.request, REQUEST_TIMEOUT).is_ok());
        let requests = server.join().unwrap();
        for request in &requests[..2] {
            assert_eq!(request.0, "POST /api/v1/installation_sessions HTTP/1.1");
            assert_eq!(request.1, initial.session_id);
            assert_eq!(request.2, serde_json::to_value(&initial).unwrap());
        }
        for request in &requests[2..] {
            assert_eq!(request.0, "POST /api/v1/installation_log_chunks HTTP/1.1");
            assert_eq!(request.1, spool.request.chunk_id);
            assert_eq!(request.2, serde_json::to_value(&spool.request).unwrap());
        }
    }

    #[test]
    fn redirect_is_not_acknowledged() {
        let root = tempfile::tempdir().unwrap();
        let session =
            InstallationLog::begin(root.path(), std::path::Path::new("/missing-adb"), None)
                .unwrap();
        let (url, server) = server(vec![302]);
        let mut transport = HttpTransport::new(session.directory(), session.info());
        transport.agent = ureq::Agent::config_builder()
            .max_redirects(0)
            .timeout_global(Some(REQUEST_TIMEOUT))
            .build()
            .new_agent();
        transport.session_url = url;
        assert!(transport.register().is_err());
        assert!(!session.directory().join("session.ack").exists());
        server.join().unwrap();
        session.finish(Status::Success, None).unwrap();
    }
}
