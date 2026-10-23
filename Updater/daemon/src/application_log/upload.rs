use super::storage::{file_hash, Upload, FILE_NAME, MAX_FILE_BYTES};
use serde_json::Value;
use std::{
    fs,
    io::{self, Read},
    path::{Path, PathBuf},
    sync::mpsc,
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use ureq::unversioned::multipart::{Form, Part};

const ENDPOINT: &str = "https://voyahtune.ru/api/v1/application_sessions";
const MAX_RESPONSE: u64 = 64 * 1024;

pub enum Event {
    Progress(String),
    Finished {
        result: Result<(), String>,
        retry_at_ms: u64,
    },
}

#[derive(Debug)]
struct Failure {
    message: String,
    retry: bool,
    retry_after: Option<Duration>,
}

impl Failure {
    fn permanent(error: impl ToString) -> Self {
        Self {
            message: error.to_string(),
            retry: false,
            retry_after: None,
        }
    }

    fn transient(error: impl ToString) -> Self {
        Self {
            message: error.to_string(),
            retry: true,
            retry_after: None,
        }
    }
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .https_only(true)
        .http_status_as_error(false)
        .max_redirects(0)
        .timeout_connect(Some(Duration::from_secs(15)))
        .timeout_global(Some(Duration::from_secs(180)))
        .build()
        .new_agent()
}

pub fn spawn(path: PathBuf, upload: Upload) -> io::Result<mpsc::Receiver<Event>> {
    let (sender, receiver) = mpsc::channel();
    thread::Builder::new()
        .name("application-log-upload".into())
        .spawn(move || {
            let delay = upload.retry_at_ms.saturating_sub(now_ms());
            if delay > 0 {
                let _ = sender.send(Event::Progress(format!(
                    "Повтор выгрузки через {} с…",
                    delay.div_ceil(1000)
                )));
                thread::sleep(Duration::from_millis(delay));
            }
            let _ = sender.send(Event::Progress("Выгружаем запись логов на сервер…".into()));
            let (result, retry_at_ms) = match send(&path, &upload, ENDPOINT, &agent()) {
                Ok(()) => (Ok(()), 0),
                Err(error) => {
                    let now = now_ms();
                    let retry_at = if error.retry {
                        now.saturating_add(
                            retry_delay(
                                upload.attempt.saturating_sub(1),
                                error.retry_after,
                                now % 1000,
                            )
                            .as_millis() as u64,
                        )
                    } else {
                        0
                    };
                    (Err(error.message), retry_at)
                }
            };
            let _ = sender.send(Event::Finished {
                result,
                retry_at_ms,
            });
        })?;
    Ok(receiver)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn retry_delay(attempt: u32, retry_after: Option<Duration>, jitter_ms: u64) -> Duration {
    let backoff = Duration::from_secs((2u64 << attempt.min(5)).min(60));
    backoff.max(retry_after.unwrap_or_default()) + Duration::from_millis(jitter_ms % 1000)
}

fn parse_retry_after(value: Option<&str>) -> Option<Duration> {
    let value = value?;
    if let Ok(seconds) = value.parse() {
        return Some(Duration::from_secs(seconds));
    }
    let date = chrono::DateTime::parse_from_rfc2822(value).ok()?;
    Some(
        (date.with_timezone(&chrono::Utc) - chrono::Utc::now())
            .to_std()
            .unwrap_or_default(),
    )
}

fn send(path: &Path, upload: &Upload, endpoint: &str, agent: &ureq::Agent) -> Result<(), Failure> {
    let size = fs::metadata(path).map_err(Failure::permanent)?.len();
    if size == 0
        || size > MAX_FILE_BYTES
        || size != upload.size
        || file_hash(path).map_err(Failure::permanent)? != upload.sha256
    {
        return Err(Failure::permanent(
            "Файл изменился после подготовки выгрузки",
        ));
    }
    let part = Part::file(path)
        .map_err(Failure::permanent)?
        .file_name(FILE_NAME)
        .mime_str("text/plain")
        .map_err(Failure::permanent)?;
    let form = Form::new()
        .text("sessionId", &upload.session_id)
        .text("appVersion", &upload.app_version)
        .part("file", part);
    // Form owns multipart headers and streams the closed file without buffering its contents.
    let mut response = agent
        .post(endpoint)
        .send(form)
        .map_err(Failure::transient)?;
    let status = response.status().as_u16();
    let retry_after = parse_retry_after(
        response
            .headers()
            .get("Retry-After")
            .and_then(|value| value.to_str().ok()),
    );
    let mut bytes = Vec::new();
    response
        .body_mut()
        .as_reader()
        .take(MAX_RESPONSE + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| Failure {
            message: error.to_string(),
            retry: matches!(status, 200 | 201 | 429) || (500..600).contains(&status),
            retry_after,
        })?;
    let body: Value = if bytes.len() as u64 <= MAX_RESPONSE {
        serde_json::from_slice(&bytes).unwrap_or(Value::Null)
    } else {
        Value::Null
    };
    if matches!(status, 200 | 201) {
        if body["accepted"] == true
            && body["sessionId"] == upload.session_id
            && body["appVersion"] == upload.app_version
            && body["size"] == upload.size
            && body["sha256"] == upload.sha256
        {
            return Ok(());
        }
        return Err(Failure::transient(
            "Сервер не подтвердил приём текущего файла",
        ));
    }
    let message = body["error"]["message"]
        .as_str()
        .unwrap_or("сервер отклонил запись логов");
    Err(Failure {
        message: format!("HTTP {status}: {message}"),
        retry: status == 429 || (500..600).contains(&status),
        retry_after,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Write};
    use std::net::TcpListener;

    fn exchange(
        status: u16,
        body: Value,
        upload: &Upload,
        path: &Path,
    ) -> (Result<(), Failure>, Vec<u8>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut reader = BufReader::new(socket.try_clone().unwrap());
            let mut headers = String::new();
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                headers.push_str(&line);
                if line == "\r\n" {
                    break;
                }
            }
            let mut payload = Vec::new();
            if headers
                .to_ascii_lowercase()
                .contains("transfer-encoding: chunked")
            {
                loop {
                    let mut length = String::new();
                    reader.read_line(&mut length).unwrap();
                    let length = usize::from_str_radix(length.trim(), 16).unwrap();
                    if length == 0 {
                        break;
                    }
                    let offset = payload.len();
                    payload.resize(offset + length, 0);
                    reader.read_exact(&mut payload[offset..]).unwrap();
                    let mut newline = [0; 2];
                    reader.read_exact(&mut newline).unwrap();
                }
            } else {
                let length: usize = headers
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse().unwrap())
                    })
                    .unwrap();
                payload.resize(length, 0);
                reader.read_exact(&mut payload).unwrap();
            }
            assert!(headers
                .to_ascii_lowercase()
                .contains("content-type: multipart/form-data; boundary="));
            let body = body.to_string();
            write!(socket, "HTTP/1.1 {status} Response\r\nContent-Type: application/json\r\nContent-Length: {}\r\nRetry-After: 5\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            payload
        });
        let agent = ureq::Agent::config_builder()
            .https_only(false)
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(5)))
            .build()
            .new_agent();
        let result = send(
            path,
            upload,
            &format!("http://{address}/application_sessions"),
            &agent,
        );
        (result, server.join().unwrap())
    }

    #[test]
    fn multipart_streams_exact_bytes_and_requires_matching_acceptance() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join(FILE_NAME);
        let contents = "10-19 12:00:00.123456 10 20 E AndroidRuntime: ошибка\n";
        fs::write(&path, contents).unwrap();
        let upload = Upload {
            session_id: "550e8400-e29b-41d4-a716-446655440000".into(),
            app_version: "4.3.3-od".into(),
            size: contents.len() as u64,
            sha256: file_hash(&path).unwrap(),
            pending: true,
            accepted: false,
            attempt: 1,
            retry_at_ms: 0,
            error: None,
        };
        let accepted = serde_json::json!({"accepted":true,"sessionId":upload.session_id,"appVersion":upload.app_version,"size":upload.size,"sha256":upload.sha256});
        for status in [200, 201] {
            let (result, payload) = exchange(status, accepted.clone(), &upload, &path);
            result.unwrap();
            let text = String::from_utf8(payload).unwrap();
            for field in [
                "name=\"sessionId\"",
                "name=\"appVersion\"",
                "name=\"file\"",
                "filename=\"system-log.txt\"",
                "text/plain",
                &upload.session_id,
                &upload.app_version,
                contents,
            ] {
                assert!(text.contains(field), "missing {field}");
            }
        }
        assert!(
            exchange(200, serde_json::json!({"accepted":false}), &upload, &path)
                .0
                .is_err()
        );
        let conflict = exchange(
            409,
            serde_json::json!({"error":{"message":"conflict"}}),
            &upload,
            &path,
        )
        .0
        .unwrap_err();
        assert!(!conflict.retry);
        for status in [429, 503] {
            let error = exchange(status, Value::Null, &upload, &path).0.unwrap_err();
            assert!(error.retry);
            assert_eq!(error.retry_after, Some(Duration::from_secs(5)));
        }
        fs::write(&path, "changed").unwrap();
        assert!(!send(&path, &upload, ENDPOINT, &agent()).unwrap_err().retry);
    }

    #[test]
    fn backoff_honors_retry_after_with_jitter() {
        assert_eq!(
            retry_delay(0, Some(Duration::from_secs(5)), 123),
            Duration::from_millis(5123)
        );
        assert!(retry_delay(2, None, 0) > retry_delay(1, None, 0));
        assert_eq!(parse_retry_after(Some("5")), Some(Duration::from_secs(5)));
    }
}
