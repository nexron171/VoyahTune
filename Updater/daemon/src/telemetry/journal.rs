use crate::{config::invalid, state};
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, BufRead, BufReader, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

pub const MAX_BYTES: usize = 1024 * 1024 - 1;
const MAX_RECORDS: usize = 100;

pub struct Journal {
    pub directory: PathBuf,
    pub session: Value,
    pub paused: bool,
    sequence: u64,
    chunk_index: u64,
    pending: Vec<Value>,
    file: File,
}

pub fn timestamp_after(previous: &str) -> String {
    let now = chrono::Utc::now();
    let previous = chrono::DateTime::parse_from_rfc3339(previous).ok();
    let next = previous.map_or(now, |previous| {
        now.max(previous.with_timezone(&chrono::Utc) + chrono::Duration::milliseconds(1))
    });
    next.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

impl Journal {
    pub fn create(
        root: &Path,
        release: Value,
        dns: &str,
        serial: Option<String>,
    ) -> io::Result<Self> {
        fs::create_dir_all(root)?;
        let mut random = [0u8; 16];
        std::io::Read::read_exact(&mut File::open("/dev/urandom")?, &mut random)?;
        let id = format!("ota-{}", hex::encode(random));
        let directory = root.join(&id);
        fs::create_dir(&directory)?;
        fs::create_dir(directory.join("chunks"))?;
        let now = timestamp_after("");
        let versions: Value = serde_json::from_str(include_str!("../../../../versions.json"))?;
        let session = json!({
            "schemaVersion":1, "sessionId":id, "installerVersion":versions["installer"],
            "isOta":true, "startedAt":now, "updatedAt":now, "endedAt":null,
            "status":"in_progress", "selectedRelease":release, "dns":dns,
            "deviceSerial":serial, "finalReason":null, "vehicleCheckErrors":[], "transport":"https"
        });
        state::save(&directory, "session.json", &session)?;
        File::create(directory.join("logs.jsonl"))?.sync_all()?;
        state::save(root, "active.json", &json!({"sessionId":id}))?;
        Self::open(&directory)
    }

    pub fn open(directory: &Path) -> io::Result<Self> {
        let session: Value = state::read(&directory.join("session.json"))?;
        let mut chunk_index = 0;
        let mut delivered_sequence = 0;
        let mut chunks: Vec<_> = fs::read_dir(directory.join("chunks"))?
            .map(|entry| entry.map(|entry| entry.path()))
            .collect::<io::Result<_>>()?;
        chunks.retain(|path| {
            path.extension()
                .is_some_and(|extension| extension == "json")
        });
        chunks.sort();
        for path in chunks.iter().rev() {
            let chunk: Value = state::read(path)?;
            chunk_index = chunk_index.max(chunk["chunkIndex"].as_u64().unwrap_or(0));
            if let Some(last) = chunk["lastSequence"].as_u64() {
                delivered_sequence = last;
                break;
            }
        }
        let path = directory.join("logs.jsonl");
        let mut reader = BufReader::new(File::open(&path)?);
        let mut sequence = delivered_sequence;
        let mut pending = vec![];
        let mut valid_bytes = 0;
        loop {
            let mut line = String::new();
            let length = reader.read_line(&mut line)?;
            if length == 0 {
                break;
            }
            if !line.ends_with('\n') {
                break;
            }
            let record: Value = serde_json::from_str(&line)?;
            let current = record["sequence"]
                .as_u64()
                .ok_or_else(|| invalid("Invalid OTA log sequence"))?;
            sequence = sequence.max(current);
            if current > delivered_sequence {
                pending.push(record);
            }
            valid_bytes += length as u64;
        }
        let file = OpenOptions::new().append(true).write(true).open(&path)?;
        file.set_len(valid_bytes)?;
        let mut journal = Self {
            directory: directory.into(),
            session,
            paused: false,
            sequence,
            chunk_index,
            pending,
            file,
        };
        journal.flush(false, false)?;
        Ok(journal)
    }

    pub fn latest_chunk(&self) -> PathBuf {
        self.directory
            .join("chunks")
            .join(format!("{:012}.json", self.chunk_index))
    }

    pub fn active(&self) -> bool {
        self.session["status"] == "in_progress"
    }

    pub fn record(&mut self, kind: &str, name: &str, data: Value) -> io::Result<()> {
        if !self.active() {
            return Ok(());
        }
        let encoded = serde_json::to_string(&data)?;
        if encoded.len() > 64 * 1024 {
            let mut fragments = Vec::new();
            let mut remaining = encoded.as_str();
            while !remaining.is_empty() {
                let mut end = remaining.len().min(32 * 1024);
                while !remaining.is_char_boundary(end) {
                    end -= 1;
                }
                fragments.push(&remaining[..end]);
                remaining = &remaining[end..];
            }
            for (index, fragment) in fragments.iter().enumerate() {
                self.record(kind, name, json!({"encodedDataFragment":fragment,"fragmentIndex":index,"fragmentCount":fragments.len()}))?;
            }
            return Ok(());
        }
        self.sequence += 1;
        let record = json!({
            "sessionId":self.session["sessionId"], "sequence":self.sequence,
            "timestamp":timestamp_after(""), "kind":kind, "name":name,
            "stepId":name, "operationId":null, "data":data
        });
        serde_json::to_writer(&mut self.file, &record)?;
        self.file.write_all(b"\n")?;
        self.pending.push(record);
        self.flush(false, false)
    }

    fn chunk(&self, records: &[Value], final_chunk: bool) -> Value {
        let index = self.chunk_index + 1;
        json!({
            "schemaVersion":1, "sessionId":self.session["sessionId"],
            "chunkId":format!("{}:{index}", self.session["sessionId"].as_str().unwrap()),
            "chunkIndex":index, "createdAt":timestamp_after(""), "finalChunk":final_chunk,
            "firstSequence":records.first().map(|record| &record["sequence"]),
            "lastSequence":records.last().map(|record| &record["sequence"]),
            "session":self.session, "logs":records
        })
    }

    pub fn flush(&mut self, remainder: bool, final_chunk: bool) -> io::Result<()> {
        if final_chunk && self.pending.is_empty() && self.chunk_index > 0 {
            let previous: Value = state::read(
                &self
                    .directory
                    .join("chunks")
                    .join(format!("{:012}.json", self.chunk_index)),
            )?;
            if previous["finalChunk"] == true && previous["session"] == self.session {
                return Ok(());
            }
        }
        loop {
            let mut count = self.pending.len().min(MAX_RECORDS);
            let mut chunk = self.chunk(
                &self.pending[..count],
                final_chunk && count == self.pending.len(),
            );
            let mut oversized = false;
            while serde_json::to_vec(&chunk)?.len() > MAX_BYTES {
                oversized = true;
                if count <= 1 {
                    return Err(invalid("OTA log record exceeds request limit"));
                }
                count -= 1;
                chunk = self.chunk(&self.pending[..count], false);
            }
            if !remainder && count < MAX_RECORDS && !oversized {
                return Ok(());
            }
            if count == 0 && !final_chunk {
                return Ok(());
            }
            self.file.sync_data()?;
            let filename = format!("{:012}.json", self.chunk_index + 1);
            state::save(&self.directory.join("chunks"), &filename, &chunk)?;
            self.chunk_index += 1;
            self.pending.drain(..count);
            if self.pending.is_empty() {
                return Ok(());
            }
        }
    }

    pub fn finish(&mut self, error: Option<&str>) -> io::Result<()> {
        if !self.active() {
            return Ok(());
        }
        self.record("step", "installation.finished", json!({"error":error}))?;
        let now = timestamp_after(self.session["updatedAt"].as_str().unwrap_or(""));
        self.session["updatedAt"] = now.clone().into();
        self.session["endedAt"] = now.into();
        self.session["status"] = if error.is_some() { "error" } else { "success" }.into();
        self.session["finalReason"] = error
            .map(|message| json!({"code":"OTA_FAILED", "message":message}))
            .unwrap_or(Value::Null);
        state::save(&self.directory, "session.json", &self.session)?;
        self.flush(true, true)
    }
}

pub fn retry_delay(attempt: u32) -> u64 {
    let base = 2u64.saturating_pow(attempt.min(8)).min(300);
    let jitter = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_millis() as u64;
    base * 1000 + jitter
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create(root: &Path) -> Journal {
        Journal::create(
            root,
            json!({"version":"4.2.0-od","infrastructure":"od"}),
            "keep",
            None,
        )
        .unwrap()
    }

    fn chunks(directory: &Path) -> Vec<Value> {
        let mut paths: Vec<_> = fs::read_dir(directory.join("chunks"))
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .filter(|path| {
                path.extension()
                    .is_some_and(|extension| extension == "json")
            })
            .collect();
        paths.sort();
        paths
            .iter()
            .map(|path| {
                assert!(fs::metadata(path).unwrap().len() <= 1024 * 1024);
                state::read(path).unwrap()
            })
            .collect()
    }

    #[test]
    fn chunks_and_reboot_resume_keep_one_session_and_contiguous_sequences() {
        let root = tempfile::tempdir().unwrap();
        let mut journal = create(root.path());
        let directory = journal.directory.clone();
        let id = journal.session["sessionId"].clone();
        for index in 0..99 {
            journal
                .record("logcat", "output", json!({"line":index}))
                .unwrap();
        }
        assert!(chunks(&directory).is_empty());
        journal.record("step", "ready", json!({})).unwrap();
        assert_eq!(chunks(&directory)[0]["logs"].as_array().unwrap().len(), 100);
        journal.record("step", "reboot", json!({})).unwrap();
        journal.flush(true, true).unwrap();
        let checkpoint = chunks(&directory).pop().unwrap();
        assert_eq!(checkpoint["session"]["status"], "in_progress");
        assert_eq!(checkpoint["finalChunk"], true);
        drop(journal);
        let mut resumed = Journal::open(&directory).unwrap();
        resumed.record("step", "boot", json!({})).unwrap();
        resumed.finish(None).unwrap();
        let chunks = chunks(&directory);
        let records: Vec<_> = chunks
            .iter()
            .flat_map(|chunk| chunk["logs"].as_array().unwrap())
            .collect();
        for (index, record) in records.iter().enumerate() {
            assert_eq!(record["sequence"], index + 1);
            assert_eq!(record["sessionId"], id);
        }
        for (index, chunk) in chunks.iter().enumerate() {
            assert_eq!(chunk["chunkIndex"], index + 1);
            assert_eq!(chunk["session"]["isOta"], true);
            assert_eq!(chunk["sessionId"], id);
        }
        assert_eq!(chunks.last().unwrap()["session"]["status"], "success");
    }

    #[test]
    fn byte_limit_splits_large_events_without_losing_content() {
        let root = tempfile::tempdir().unwrap();
        let mut journal = create(root.path());
        let data = json!({"text":"много логов".repeat(150_000)});
        journal.record("step", "large", data.clone()).unwrap();
        journal
            .finish(Some("Проверка запуска не пройдена"))
            .unwrap();
        let chunks = chunks(&journal.directory);
        assert!(chunks.len() > 1);
        let restored: String = chunks
            .iter()
            .flat_map(|chunk| chunk["logs"].as_array().unwrap())
            .filter_map(|record| record["data"]["encodedDataFragment"].as_str())
            .collect();
        assert_eq!(serde_json::from_str::<Value>(&restored).unwrap(), data);
        let final_session = &chunks.last().unwrap()["session"];
        assert_eq!(final_session["status"], "error");
        assert_eq!(
            final_session["finalReason"]["message"],
            "Проверка запуска не пройдена"
        );
        assert!(final_session["endedAt"].is_string());
    }

    #[test]
    fn interrupted_tail_is_recovered_and_terminal_status_is_immutable() {
        let root = tempfile::tempdir().unwrap();
        let mut journal = create(root.path());
        let directory = journal.directory.clone();
        journal.record("step", "before-crash", json!({})).unwrap();
        drop(journal);
        OpenOptions::new()
            .append(true)
            .open(directory.join("logs.jsonl"))
            .unwrap()
            .write_all(b"{partial")
            .unwrap();
        let mut journal = Journal::open(&directory).unwrap();
        journal.finish(Some("Interrupted")).unwrap();
        journal.finish(None).unwrap();
        assert_eq!(journal.session["status"], "error");
        assert_eq!(chunks(&directory)[0]["firstSequence"], 1);
        assert_eq!(chunks(&directory)[0]["lastSequence"], 2);
    }

    #[test]
    fn updated_timestamp_advances_even_after_clock_rollback() {
        let future = "2099-01-01T00:00:00.000Z";
        assert!(timestamp_after(future).as_str() > future);
    }
}
