//! Resumable archive transfer. The caller holds the exclusive cache lock.
use super::{cancelled, network, payload, Archive, Error, Progress, ProgressCallback, Result};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    sync::atomic::AtomicBool,
    time::{Duration, Instant},
};

// Bound individual requests rather than the total time needed for a large ZIP.
const CHUNK_SIZE: u64 = 4 * 1024 * 1024;
const MAX_RETRIES: u32 = 5;

pub(super) fn fetch(
    client: &ureq::Agent,
    archive: &Archive,
    path: &Path,
    cancel: &AtomicBool,
    progress: ProgressCallback,
) -> Result<()> {
    fetch_with_wait(client, archive, path, cancel, progress, &wait)
}

fn fetch_with_wait(
    client: &ureq::Agent,
    archive: &Archive,
    path: &Path,
    cancel: &AtomicBool,
    progress: ProgressCallback,
    pause: &dyn Fn(Duration, &AtomicBool) -> Result<()>,
) -> Result<()> {
    cancelled(cancel)?;
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path)?;
    if file.metadata()?.len() > archive.size {
        file.set_len(0)?;
    }
    let mut failures = 0;
    loop {
        cancelled(cancel)?;
        let before = file.metadata()?.len();
        if before == archive.size {
            break;
        }
        match fetch_chunk(client, archive, &mut file, cancel, progress) {
            Ok(()) => failures = 0,
            Err(e) => {
                cancelled(cancel)?;
                // Local disk errors and invalid HTTP responses cannot be fixed by reconnecting.
                if e.code != "DOWNLOAD_FAILED" {
                    return Err(e);
                }
                let after = file.metadata()?.len();
                if after > before {
                    failures = 0;
                }
                if failures == MAX_RETRIES {
                    return Err(e.retry(
                        "Скачанная часть сохранена. Повторно выберите релиз, чтобы продолжить загрузку.",
                    ));
                }
                progress(Progress {
                    stage: "retry".into(),
                    bytes: after,
                    total: archive.size,
                });
                pause(Duration::from_secs(1 << failures), cancel)?;
                failures += 1;
            }
        }
    }
    file.sync_all()?;
    drop(file);
    progress(Progress {
        stage: "verify".into(),
        bytes: archive.size,
        total: archive.size,
    });
    cancelled(cancel)?;
    if payload::sha256(path)? != archive.sha256 {
        // Never keep a complete corrupt file: the next selection must start cleanly.
        fs::remove_file(path)?;
        return Err(Error::new(
            "DOWNLOAD_HASH",
            "SHA-256 загруженного архива не совпадает с каталогом. Повторите загрузку.",
        ));
    }
    cancelled(cancel)
}

fn protocol(detail: impl ToString) -> Error {
    Error::new(
        "DOWNLOAD_RESPONSE",
        "Сервер вернул некорректный ответ при загрузке релиза",
    )
    .detail(detail)
}

fn fetch_chunk(
    client: &ureq::Agent,
    archive: &Archive,
    file: &mut File,
    cancel: &AtomicBool,
    progress: ProgressCallback,
) -> Result<()> {
    let offset = file.metadata()?.len();
    let requested_end = (offset + CHUNK_SIZE).min(archive.size) - 1;
    let mut response = client
        .get(&archive.url)
        .header("Accept-Encoding", "identity")
        .header("Range", format!("bytes={offset}-{requested_end}"))
        .call()
        .map_err(|e| match e {
            ureq::Error::StatusCode(408 | 429 | 500 | 502 | 503 | 504)
            | ureq::Error::Io(_)
            | ureq::Error::Timeout(_)
            | ureq::Error::HostNotFound
            | ureq::Error::ConnectionFailed
            | ureq::Error::Protocol(_) => network(e),
            _ => protocol(e),
        })?;
    cancelled(cancel)?;
    let headers = response.headers();
    if headers
        .get("Content-Encoding")
        .is_some_and(|v| v.as_bytes() != b"identity")
    {
        return Err(protocol("Ожидались несжатые байты ZIP"));
    }
    let (start, end) = match response.status().as_u16() {
        206 => {
            let value = headers
                .get("Content-Range")
                .and_then(|v| v.to_str().ok())
                .ok_or_else(|| protocol("Нет Content-Range"))?;
            let (start, end, total) =
                parse_range(value).ok_or_else(|| protocol("Некорректный Content-Range"))?;
            if start != offset || end < start || end > requested_end || total != archive.size {
                return Err(protocol(
                    "Content-Range не соответствует запросу и каталогу",
                ));
            }
            (start, end + 1)
        }
        // A server may ignore Range. Validate headers before replacing the saved prefix.
        200 => (0, archive.size),
        status => return Err(protocol(format!("Неожиданный HTTP {status}"))),
    };
    if let Some(length) = headers.get("Content-Length") {
        if length.to_str().ok().and_then(|s| s.parse::<u64>().ok()) != Some(end - start) {
            return Err(protocol(
                "Content-Length не соответствует размеру архива/диапазона",
            ));
        }
    }
    if start == 0 {
        file.set_len(0)?;
    }
    file.seek(SeekFrom::Start(start))?;
    progress(Progress {
        stage: "download".into(),
        bytes: start,
        total: archive.size,
    });
    let mut reader = response.body_mut().as_reader();
    let mut buffer = [0u8; 128 * 1024];
    let mut count = start;
    let mut reported = start;
    loop {
        cancelled(cancel)?;
        // Read once more at the expected boundary to reject oversized chunked bodies.
        let n = reader.read(&mut buffer).map_err(network)?;
        if n == 0 {
            break;
        }
        if n as u64 > end - count {
            file.set_len(start)?;
            return Err(protocol("Превышен размер ответа"));
        }
        file.write_all(&buffer[..n])?;
        count += n as u64;
        if count - reported >= 1024 * 1024 {
            progress(Progress {
                stage: "download".into(),
                bytes: count,
                total: archive.size,
            });
            reported = count;
        }
    }
    if count != end {
        return Err(network("Соединение закрыто до окончания загрузки"));
    }
    progress(Progress {
        stage: "download".into(),
        bytes: count,
        total: archive.size,
    });
    Ok(())
}

fn parse_range(value: &str) -> Option<(u64, u64, u64)> {
    let (range, total) = value.strip_prefix("bytes ")?.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    Some((start.parse().ok()?, end.parse().ok()?, total.parse().ok()?))
}

fn wait(duration: Duration, cancel: &AtomicBool) -> Result<()> {
    let started = Instant::now();
    while started.elapsed() < duration {
        cancelled(cancel)?;
        std::thread::sleep(
            (duration - started.elapsed().min(duration)).min(Duration::from_millis(100)),
        );
    }
    cancelled(cancel)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    use std::{
        net::TcpListener,
        sync::{atomic::Ordering, Mutex},
        thread,
    };

    struct Reply {
        bytes: Vec<u8>,
        hold: Duration,
    }
    fn reply(status: &str, headers: &str, body: &[u8]) -> Reply {
        let mut bytes =
            format!("HTTP/1.1 {status}\r\nConnection: close\r\n{headers}\r\n").into_bytes();
        bytes.extend_from_slice(body);
        Reply {
            bytes,
            hold: Duration::ZERO,
        }
    }
    fn full(body: &[u8]) -> Reply {
        reply(
            "200 OK",
            &format!("Content-Length: {}\r\n", body.len()),
            body,
        )
    }
    fn range(start: usize, end: usize, body: &[u8]) -> Reply {
        reply(
            "206 Partial Content",
            &format!(
                "Content-Range: bytes {start}-{}/{total}\r\nContent-Length: {}\r\n",
                end - 1,
                end - start,
                total = body.len()
            ),
            &body[start..end],
        )
    }
    fn server(replies: Vec<Reply>) -> (String, thread::JoinHandle<Vec<String>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}/archive.zip", listener.local_addr().unwrap());
        let worker = thread::spawn(move || {
            let mut requests = vec![];
            for reply in replies {
                let deadline = Instant::now() + Duration::from_secs(10);
                let mut stream = loop {
                    match listener.accept() {
                        Ok((stream, _)) => break stream,
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(Instant::now() < deadline, "missing request");
                            thread::sleep(Duration::from_millis(5));
                        }
                        Err(e) => panic!("{e}"),
                    }
                };
                stream.set_nonblocking(false).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                stream
                    .set_write_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut request = vec![];
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    stream.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                requests.push(String::from_utf8(request).unwrap().to_ascii_lowercase());
                // Cancellation may close the connection before the whole reply is sent.
                let _ = stream.write_all(&reply.bytes);
                thread::sleep(reply.hold);
            }
            requests
        });
        (url, worker)
    }
    fn client() -> ureq::Agent {
        ureq::Agent::config_builder()
            .proxy(None)
            .timeout_global(Some(Duration::from_secs(3)))
            .build()
            .new_agent()
    }
    fn archive(url: String, body: &[u8]) -> Archive {
        Archive {
            url,
            size: body.len() as u64,
            sha256: hex::encode(Sha256::digest(body)),
            manifest_schema: 4,
        }
    }
    fn fetch_now(client: &ureq::Agent, archive: &Archive, path: &Path) -> Result<()> {
        fetch_with_wait(
            client,
            archive,
            path,
            &AtomicBool::new(false),
            &|_| {},
            &|_, _| Ok(()),
        )
    }

    #[test]
    fn interrupted_transfer_resumes_and_retries_transient_http_errors() {
        let body = b"archive contents";
        let (url, worker) = server(vec![
            reply("200 OK", "Content-Length: 16\r\n", &body[..5]),
            reply("503 Unavailable", "Content-Length: 0\r\n", b""),
            range(5, body.len(), body),
        ]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        let progress = Mutex::new(vec![]);
        fetch_with_wait(
            &client(),
            &archive(url, body),
            &path,
            &AtomicBool::new(false),
            &|p| progress.lock().unwrap().push(p),
            &|_, _| Ok(()),
        )
        .unwrap();
        assert_eq!(fs::read(path).unwrap(), body);
        let requests = worker.join().unwrap();
        assert!(requests[0].contains("range: bytes=0-15"));
        assert!(requests[1].contains("range: bytes=5-15"));
        assert!(requests[2].contains("range: bytes=5-15"));
        assert!(requests
            .iter()
            .all(|r| r.contains("accept-encoding: identity")));
        let progress = progress.lock().unwrap();
        assert!(progress.iter().any(|p| p.stage == "retry" && p.bytes == 5));
        assert_eq!(progress.last().unwrap().stage, "verify");
    }

    #[test]
    fn exhausted_retries_preserve_prefix_for_next_launch() {
        let body = b"archive contents";
        let (url, worker) = server(
            (0..=MAX_RETRIES)
                .map(|_| reply("503 Unavailable", "Content-Length: 0\r\n", b""))
                .collect(),
        );
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        fs::write(&path, &body[..5]).unwrap();
        assert_eq!(
            fetch_now(&client(), &archive(url, body), &path)
                .unwrap_err()
                .code,
            "DOWNLOAD_FAILED"
        );
        assert_eq!(fs::read(&path).unwrap(), &body[..5]);
        assert_eq!(worker.join().unwrap().len(), 6);
        let (url, worker) = server(vec![range(5, body.len(), body)]);
        fetch_now(&client(), &archive(url, body), &path).unwrap();
        assert_eq!(fs::read(&path).unwrap(), body);
        assert!(worker.join().unwrap()[0].contains("range: bytes=5-15"));
        // A completed part is reverified without a network request.
        fetch_now(
            &client(),
            &archive("http://127.0.0.1:1/offline".into(), body),
            &path,
        )
        .unwrap();
    }

    #[test]
    fn server_ignoring_range_replaces_saved_prefix() {
        let body = b"archive contents";
        let (url, worker) = server(vec![full(body)]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        fs::write(&path, b"wrong prefix").unwrap();
        fetch_now(&client(), &archive(url, body), &path).unwrap();
        assert_eq!(fs::read(path).unwrap(), body);
        assert!(worker.join().unwrap()[0].contains("range: bytes=12-15"));
    }

    #[test]
    fn large_archive_uses_bounded_ranges_through_redirects() {
        let body = vec![42; CHUNK_SIZE as usize + 17];
        let (url, worker) = server(vec![
            reply(
                "302 Found",
                "Location: /signed.zip\r\nContent-Length: 0\r\n",
                b"",
            ),
            range(0, CHUNK_SIZE as usize, &body),
            range(CHUNK_SIZE as usize, body.len(), &body),
        ]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        fetch_now(&client(), &archive(url, &body), &path).unwrap();
        assert_eq!(fs::read(path).unwrap(), body);
        let requests = worker.join().unwrap();
        assert!(requests[1].starts_with("get /signed.zip "));
        assert!(requests[1].contains("range: bytes=0-4194303"));
        assert!(requests[2].starts_with("get /archive.zip "));
        assert!(requests[2].contains("range: bytes=4194304-4194320"));
    }

    #[test]
    fn invalid_responses_do_not_modify_partial_or_retry() {
        for response in [
            reply(
                "206 Partial Content",
                "Content-Range: bytes 0-15/16\r\nContent-Length: 16\r\n",
                b"",
            ),
            reply(
                "206 Partial Content",
                "Content-Range: bytes 5-15/17\r\nContent-Length: 11\r\n",
                b"",
            ),
            reply(
                "206 Partial Content",
                "Content-Range: bytes 5-16/16\r\nContent-Length: 12\r\n",
                b"",
            ),
            reply("206 Partial Content", "Content-Length: 11\r\n", b""),
            reply("200 OK", "Content-Length: 99\r\n", b""),
            reply(
                "200 OK",
                "Content-Encoding: gzip\r\nContent-Length: 16\r\n",
                b"",
            ),
            reply("404 Not Found", "Content-Length: 0\r\n", b""),
            reply("416 Range Not Satisfiable", "Content-Length: 0\r\n", b""),
        ] {
            let (url, worker) = server(vec![response]);
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join("file.part");
            fs::write(&path, b"saved").unwrap();
            assert_eq!(
                fetch_now(&client(), &archive(url, b"archive contents"), &path)
                    .unwrap_err()
                    .code,
                "DOWNLOAD_RESPONSE"
            );
            assert_eq!(fs::read(path).unwrap(), b"saved");
            assert_eq!(worker.join().unwrap().len(), 1);
        }
    }

    #[test]
    fn corrupt_completed_archive_is_deleted_before_next_selection() {
        let body = b"archive contents";
        let (url, worker) = server(vec![range(5, body.len(), body), full(body)]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        fs::write(&path, b"wrong").unwrap();
        let archive = archive(url, body);
        assert_eq!(
            fetch_now(&client(), &archive, &path).unwrap_err().code,
            "DOWNLOAD_HASH"
        );
        assert!(!path.exists());
        fetch_now(&client(), &archive, &path).unwrap();
        assert_eq!(fs::read(path).unwrap(), body);
        worker.join().unwrap();
    }

    #[test]
    fn cancellation_during_transfer_and_retry_preserves_bytes() {
        let body = vec![42; 2 * 1024 * 1024];
        let (url, worker) = server(vec![full(&body)]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        let cancel = AtomicBool::new(false);
        let e = fetch_with_wait(
            &client(),
            &archive(url, &body),
            &path,
            &cancel,
            &|p| {
                if p.stage == "download" && p.bytes >= 1024 * 1024 {
                    cancel.store(true, Ordering::Relaxed);
                }
            },
            &|_, _| panic!("cancelled downloads must not retry"),
        )
        .unwrap_err();
        assert_eq!(e.code, "CANCELLED");
        assert!(fs::metadata(&path).unwrap().len() >= 1024 * 1024);
        worker.join().unwrap();
        cancel.store(false, Ordering::Relaxed);
        let saved = fs::read(&path).unwrap();
        let (url, worker) = server(vec![reply("503 Unavailable", "Content-Length: 0\r\n", b"")]);
        let e = fetch_with_wait(
            &client(),
            &archive(url, &body),
            &path,
            &cancel,
            &|p| {
                if p.stage == "retry" {
                    cancel.store(true, Ordering::Relaxed);
                }
            },
            &wait,
        )
        .unwrap_err();
        assert_eq!(e.code, "CANCELLED");
        assert_eq!(fs::read(path).unwrap(), saved);
        worker.join().unwrap();
    }

    #[test]
    fn timeout_keeps_received_bytes_and_resumes() {
        let body = b"archive contents";
        let mut stalled = reply("200 OK", "Content-Length: 16\r\n", &body[..5]);
        stalled.hold = Duration::from_millis(200);
        let (url, worker) = server(vec![stalled, range(5, body.len(), body)]);
        let client = ureq::Agent::config_builder()
            .proxy(None)
            .timeout_global(Some(Duration::from_secs(3)))
            .timeout_recv_body(Some(Duration::from_millis(50)))
            .build()
            .new_agent();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        fetch_now(&client, &archive(url, body), &path).unwrap();
        assert_eq!(fs::read(path).unwrap(), body);
        assert!(worker.join().unwrap()[1].contains("range: bytes=5-15"));
    }

    #[test]
    fn oversized_chunked_response_is_rejected_without_hash_acceptance() {
        let (url, worker) = server(vec![reply(
            "200 OK",
            "Transfer-Encoding: chunked\r\n",
            b"4\r\nabcd\r\n0\r\n\r\n",
        )]);
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("file.part");
        assert_eq!(
            fetch_now(&client(), &archive(url, b"abc"), &path)
                .unwrap_err()
                .code,
            "DOWNLOAD_RESPONSE"
        );
        assert_eq!(fs::metadata(path).unwrap().len(), 0);
        worker.join().unwrap();
    }
}
