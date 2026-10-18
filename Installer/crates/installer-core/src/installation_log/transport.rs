use super::{Chunk, SessionInfo, SpoolItem, UploadProgress, UploadSnapshot};
use chrono::Utc;
use serde_json::json;
#[cfg(test)]
use std::fs;
use std::{
    fs::{File, OpenOptions},
    io::{BufReader, Write},
    path::{Path, PathBuf},
    sync::{mpsc, Arc, Mutex},
    thread::{self, JoinHandle},
    time::Duration,
};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

pub(super) enum Message {
    Chunk(PathBuf),
    Finish,
}

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

fn deliver_file(
    transport: &mut dyn Transport,
    directory: &Path,
    path: &Path,
    automatic: bool,
) -> crate::Result<bool> {
    let item: SpoolItem = serde_json::from_reader(BufReader::new(File::open(path)?))?;
    if automatic
        && item
            .request
            .logs
            .iter()
            .any(|record| record.kind == "logcat")
    {
        return Ok(true);
    }
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

fn run_delivery_loop(
    receiver: mpsc::Receiver<Message>,
    directory: &Path,
    transport: &mut dyn Transport,
    request_lock: &Mutex<()>,
) {
    while let Ok(message) = receiver.recv() {
        match message {
            Message::Chunk(path) => {
                let _request = request_lock.lock().unwrap();
                if let Err(error) = deliver_file(transport, directory, &path, true) {
                    eprintln!("Session delivery failed: {error}");
                }
            }
            Message::Finish => break,
        }
    }
}

#[cfg(not(test))]
pub(super) fn start(
    receiver: mpsc::Receiver<Message>,
    directory: PathBuf,
    initial: SessionInfo,
    request_lock: Arc<Mutex<()>>,
) -> Option<JoinHandle<()>> {
    Some(thread::spawn(move || {
        let mut transport = HttpTransport::new(directory.clone(), initial);
        {
            let _request = request_lock.lock().unwrap();
            if let Err(error) = transport.register() {
                eprintln!("Session registration failed: {error}");
            }
        }
        run_delivery_loop(receiver, &directory, &mut transport, &request_lock);
    }))
}

// Tests drive delivery explicitly against loopback or an in-memory transport.
#[cfg(test)]
pub(super) fn start(
    _: mpsc::Receiver<Message>,
    _: PathBuf,
    _: SessionInfo,
    _: Arc<Mutex<()>>,
) -> Option<JoinHandle<()>> {
    None
}

pub(super) fn upload(
    snapshot: UploadSnapshot,
    progress: impl FnMut(UploadProgress),
) -> crate::Result<UploadProgress> {
    let mut transport =
        HttpTransport::new(snapshot.directory.clone(), snapshot.initial_session.clone());
    upload_with(snapshot, &mut transport, progress)
}

fn upload_with(
    mut snapshot: UploadSnapshot,
    transport: &mut dyn Transport,
    mut progress: impl FnMut(UploadProgress),
) -> crate::Result<UploadProgress> {
    progress(snapshot.progress.clone());
    for path in &snapshot.chunks {
        let delivered = {
            let _request = snapshot.request_lock.lock().unwrap();
            deliver_file(transport, &snapshot.directory, path, false)?
        };
        if !delivered {
            return Err(crate::Error::new(
                "LOG_UPLOAD_FAILED",
                format!(
                    "Не удалось выгрузить логи. Отправлено пакетов: {} из {}. Повторите выгрузку.",
                    snapshot.progress.uploaded_chunks, snapshot.progress.total_chunks
                ),
            ));
        }
        snapshot.progress.uploaded_chunks += 1;
        progress(snapshot.progress.clone());
    }
    Ok(snapshot.progress)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::installation_log::{InstallationLog, Status};
    use std::{
        io::{BufRead, BufReader, Read},
        net::TcpListener,
    };

    #[derive(Default)]
    struct RecordingTransport {
        requests: Vec<Chunk>,
        fail_at: Option<usize>,
    }

    impl Transport for RecordingTransport {
        fn send(&mut self, chunk: &Chunk, timeout: Duration) -> Result<(), String> {
            assert_eq!(timeout, REQUEST_TIMEOUT);
            self.requests.push(chunk.clone());
            if self.fail_at == Some(self.requests.len()) {
                Err("offline".into())
            } else {
                Ok(())
            }
        }
    }

    #[test]
    fn automatic_delivery_excludes_logcat_and_stops_after_session_finish() {
        for status in [Status::Success, Status::Error] {
            let root = tempfile::tempdir().unwrap();
            let session =
                InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
            for index in 0..240 {
                session
                    .record("logcat", "output", None, None, json!({"index":index}))
                    .unwrap();
                if index % 60 == 0 {
                    session
                        .record("step", "progress", None, None, json!({"index":index}))
                        .unwrap();
                    session
                        .record("user_choice", "continue", None, None, json!({}))
                        .unwrap();
                }
            }
            session.finish(status.clone(), None).unwrap();
            assert!(session.0.collector.lock().unwrap().is_none());
            assert!(session.0.packer.lock().unwrap().is_none());
            assert!(session.0.delivery.lock().unwrap().is_none());
            let snapshot = session.upload_snapshot().unwrap();
            let directory = session.directory();
            let (sender, receiver) = mpsc::channel();
            for path in &snapshot.chunks {
                sender.send(Message::Chunk(path.clone())).unwrap();
            }
            sender.send(Message::Finish).unwrap();
            let (finished, completion) = mpsc::channel();
            let worker = thread::spawn(move || {
                let mut transport = RecordingTransport {
                    fail_at: Some(2),
                    ..Default::default()
                };
                run_delivery_loop(receiver, &directory, &mut transport, &Mutex::new(()));
                finished.send(transport.requests).unwrap();
            });
            let requests = completion.recv_timeout(Duration::from_secs(5)).unwrap();
            worker.join().unwrap();
            assert!(sender.send(Message::Finish).is_err());
            let automatic_events: Vec<_> = requests.iter().flat_map(|chunk| &chunk.logs).collect();
            assert_eq!(automatic_events.len(), 10);
            assert!(automatic_events
                .iter()
                .all(|record| record.kind != "logcat"));
            assert_eq!(automatic_events.first().unwrap().name, "session-started");
            assert_eq!(automatic_events.last().unwrap().name, "session-finished");
            assert_eq!(requests.last().unwrap().session.status, status);
            assert!(requests.last().unwrap().final_chunk);
            let mut manual = RecordingTransport::default();
            upload_with(snapshot, &mut manual, |_| {}).unwrap();
            let events: Vec<_> = manual
                .requests
                .iter()
                .flat_map(|chunk| &chunk.logs)
                .collect();
            assert_eq!(events.len(), 250);
            assert_eq!(
                events
                    .iter()
                    .filter(|record| record.kind == "logcat")
                    .count(),
                240
            );
            assert_eq!(
                events
                    .iter()
                    .map(|record| record.sequence)
                    .collect::<Vec<_>>(),
                (1..=250).collect::<Vec<_>>()
            );
            for request in requests {
                let repeated = manual
                    .requests
                    .iter()
                    .find(|chunk| chunk.chunk_id == request.chunk_id)
                    .unwrap();
                assert_eq!(
                    serde_json::to_value(repeated).unwrap(),
                    serde_json::to_value(request).unwrap()
                );
            }
        }
    }

    #[test]
    fn manual_upload_freezes_all_events_and_reports_acknowledged_progress() {
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        for _ in 0..250 {
            session
                .record("step", "before-click", None, None, json!({}))
                .unwrap();
        }
        let snapshot = session.upload_snapshot().unwrap();
        assert_eq!(snapshot.progress.total_events, 251);
        assert_eq!(snapshot.progress.total_chunks, 3);
        assert_eq!(
            session.upload_snapshot().err().unwrap().code,
            "LOG_UPLOAD_BUSY"
        );
        let mut transport = RecordingTransport::default();
        let mut updates = Vec::new();
        let result = upload_with(snapshot, &mut transport, |progress| {
            session
                .record("logcat", "after-click", None, None, json!({}))
                .unwrap();
            updates.push(progress);
        })
        .unwrap();
        assert_eq!(result.uploaded_chunks, 3);
        assert_eq!(
            updates
                .iter()
                .map(|p| p.uploaded_chunks)
                .collect::<Vec<_>>(),
            vec![0, 1, 2, 3]
        );
        assert!(updates
            .iter()
            .all(|p| p.total_chunks == 3 && p.total_events == 251));
        assert!(session.active());
        assert_eq!(
            transport
                .requests
                .iter()
                .flat_map(|chunk| chunk.logs.iter().map(|record| record.sequence))
                .collect::<Vec<_>>(),
            (1..=251).collect::<Vec<_>>()
        );
        assert!(transport
            .requests
            .iter()
            .all(|chunk| chunk.logs.len() <= 100 && !chunk.final_chunk));
        let first_requests = transport.requests.clone();
        session.finish(Status::Success, None).unwrap();
        assert!(session.0.collector.lock().unwrap().is_none());
        assert!(session.0.packer.lock().unwrap().is_none());
        transport.requests.clear();
        let result =
            upload_with(session.upload_snapshot().unwrap(), &mut transport, |_| {}).unwrap();
        assert_eq!(result.total_events, 256);
        assert_eq!(
            serde_json::to_value(&transport.requests[..3]).unwrap(),
            serde_json::to_value(first_requests).unwrap()
        );
        assert_eq!(
            transport
                .requests
                .iter()
                .map(|chunk| chunk.logs.len())
                .sum::<usize>(),
            256
        );
        assert!(transport.requests.last().unwrap().final_chunk);
        assert_eq!(
            transport.requests.last().unwrap().session.status,
            Status::Success
        );
    }

    #[test]
    fn manual_upload_failure_stops_without_retries_and_next_click_can_resend() {
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        for _ in 0..210 {
            session
                .record("step", "event", None, None, json!({}))
                .unwrap();
        }
        session
            .finish(Status::Error, Some(json!({"code":"TEST_FAILURE"})))
            .unwrap();
        let snapshot = session.upload_snapshot().unwrap();
        let originals: Vec<_> = snapshot
            .chunks
            .iter()
            .map(|path| fs::read(path).unwrap())
            .collect();
        assert!(!session.directory().join("delivery-attempts.jsonl").exists());
        assert!(!session.directory().join("session.ack").exists());
        let mut transport = RecordingTransport {
            fail_at: Some(2),
            ..Default::default()
        };
        let mut updates = Vec::new();
        assert_eq!(
            upload_with(snapshot, &mut transport, |p| updates
                .push(p.uploaded_chunks))
            .unwrap_err()
            .code,
            "LOG_UPLOAD_FAILED"
        );
        assert_eq!(transport.requests.len(), 2);
        assert_eq!(updates, vec![0, 1]);
        transport.fail_at = None;
        transport.requests.clear();
        let snapshot = session.upload_snapshot().unwrap();
        assert_eq!(
            snapshot
                .chunks
                .iter()
                .map(|path| fs::read(path).unwrap())
                .collect::<Vec<_>>(),
            originals
        );
        let result = upload_with(snapshot, &mut transport, |_| {}).unwrap();
        assert_eq!(result.uploaded_chunks, 3);
        assert_eq!(result.total_events, 212);
        assert_eq!(
            transport.requests.last().unwrap().session.final_reason,
            Some(json!({"code":"TEST_FAILURE"}))
        );
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
