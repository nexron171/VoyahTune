use super::{Chunk, SessionInfo, SpoolItem};
use chrono::Utc;
use serde_json::json;
use std::{fs::File, io::Write, path::PathBuf, sync::mpsc, thread, time::Duration};

#[cfg(not(test))]
use std::fs::OpenOptions;

const RETRY_DELAY: Duration = Duration::from_millis(300);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);

pub(super) trait Transport {
    // Implementations must honor this deadline, including connection and response reads.
    fn send(&mut self, chunk: &Chunk, timeout: Duration) -> std::result::Result<(), String>;
}

const SESSION_URL: &str = "https://155.212.170.149/api/v1/installation_sessions";
const CHUNK_URL: &str = "https://155.212.170.149/api/v1/installation_log_chunks";

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
    delay: impl Fn(Duration),
    mut observe: impl FnMut(u32, &std::result::Result<(), String>),
) -> bool {
    for attempt in 1..=4 {
        let result = transport.send(chunk, REQUEST_TIMEOUT);
        observe(attempt, &result);
        if result.is_ok() {
            return true;
        }
        if attempt < 4 {
            delay(RETRY_DELAY);
        }
    }
    false
}

// Unit tests exercise HTTP explicitly against loopback, never against production.
#[cfg(test)]
pub(super) fn start(_: mpsc::Receiver<PathBuf>, _: PathBuf, _: SessionInfo) {}

#[cfg(not(test))]
pub(super) fn start(
    receiver: mpsc::Receiver<PathBuf>,
    directory: PathBuf,
    initial_session: SessionInfo,
) {
    thread::spawn(move || {
        let mut transport = HttpTransport::new(directory.clone(), initial_session);
        for attempt in 1..=4 {
            let result = transport.register();
            if let Ok(mut file) = OpenOptions::new()
                .create(true)
                .append(true)
                .open(directory.join("delivery-attempts.jsonl"))
            {
                let record = json!({"sessionId":transport.initial_session.session_id,"endpoint":"installation_sessions",
                    "attempt":attempt,"timestamp":Utc::now().to_rfc3339(),"success":result.is_ok(),"error":result.as_ref().err()});
                let _ = writeln!(file, "{record}");
            }
            if result.is_ok() {
                break;
            }
            if attempt < 4 {
                thread::sleep(RETRY_DELAY);
            }
        }
        for path in receiver {
            let result = (|| -> crate::Result<()> {
                let item: SpoolItem = serde_json::from_reader(File::open(&path)?)?;
                let mut attempts = OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(directory.join("delivery-attempts.jsonl"))?;
                let delivered = deliver(
                    &mut transport,
                    &item.request,
                    thread::sleep,
                    |attempt, result| {
                        let record = json!({"sessionId":item.request.session_id,"chunkId":item.request.chunk_id,
                        "attempt":attempt,"timestamp":Utc::now().to_rfc3339(),"success":result.is_ok(),"error":result.as_ref().err()});
                        let _ = serde_json::to_writer(&mut attempts, &record);
                        let _ = attempts.write_all(b"\n");
                        let _ = attempts.flush();
                    },
                );
                if delivered {
                    // ACKs are separate: immutable chunks remain available for diagnostics.
                    crate::recovery::write_json(
                        &path.with_extension("ack"),
                        &json!({"chunkId":item.request.chunk_id,"acknowledgedAt":Utc::now().to_rfc3339()}),
                    )?;
                }
                Ok(())
            })();
            if let Err(error) = result {
                eprintln!("Session delivery failed: {error}");
            }
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
