//! Durable installation sessions; device operations keep their independent operation IDs.
mod logcat;
mod transport;

use crate::{events::Event, recovery, Error, Result};
use chrono::Utc;
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::{BufRead, BufReader, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Condvar, Mutex,
    },
    thread::{self, JoinHandle},
    time::Duration,
};

const INTERVAL: Duration = Duration::from_secs(5);

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    InProgress,
    Success,
    Error,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionInfo {
    pub schema_version: u32,
    pub session_id: String,
    pub installer_version: String,
    pub started_at: String,
    pub updated_at: String,
    pub ended_at: Option<String>,
    pub status: Status,
    pub selected_release: Option<Value>,
    pub dns: String,
    pub device_serial: Option<String>,
    pub final_reason: Option<Value>,
    pub vehicle_check_errors: Vec<Value>,
    pub transport: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Record {
    pub session_id: String,
    pub sequence: u64,
    pub timestamp: String,
    pub kind: String,
    pub name: String,
    pub step_id: Option<String>,
    pub operation_id: Option<String>,
    pub data: Value,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Chunk {
    pub schema_version: u32,
    pub session_id: String,
    pub chunk_id: String,
    pub chunk_index: u64,
    pub created_at: String,
    pub final_chunk: bool,
    pub first_sequence: Option<u64>,
    pub last_sequence: Option<u64>,
    pub session: SessionInfo,
    pub logs: Vec<Record>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SpoolItem {
    journal_end_offset: u64,
    request: Chunk,
}

struct State {
    info: SessionInfo,
    journal: File,
    sequence: u64,
    offset: u64,
    chunk_index: u64,
}

struct Shared {
    directory: PathBuf,
    state: Mutex<State>,
    stopping: AtomicBool,
    wake: Condvar,
    timer: Mutex<()>,
    finish_lock: Mutex<()>,
    sender: mpsc::Sender<PathBuf>,
    collector: Mutex<Option<JoinHandle<()>>>,
    ticker: Mutex<Option<JoinHandle<()>>>,
    _lock: File,
}

#[derive(Clone)]
pub struct InstallationLog(Arc<Shared>);

impl InstallationLog {
    pub fn begin(root: &Path, adb_path: &Path, release: Option<Value>) -> Result<Self> {
        Self::recover_interrupted(root)?;
        fs::create_dir_all(root)?;
        let directory = tempfile::Builder::new()
            .prefix("installation-")
            .rand_bytes(24)
            .tempdir_in(root)?
            .keep();
        let id = directory
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned();
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(directory.join("session.lock"))?;
        lock.try_lock_exclusive()?;
        fs::create_dir(directory.join("outbox"))?;
        let now = Utc::now().to_rfc3339();
        let info = SessionInfo {
            schema_version: 1,
            session_id: id,
            installer_version: env!("CARGO_PKG_VERSION").into(),
            started_at: now.clone(),
            updated_at: now,
            ended_at: None,
            status: Status::InProgress,
            selected_release: release,
            dns: "keep".into(),
            device_serial: None,
            final_reason: None,
            vehicle_check_errors: Vec::new(),
            transport: "https".into(),
        };
        recovery::write_json(&directory.join("session.json"), &info)?;
        let journal = OpenOptions::new()
            .create(true)
            .append(true)
            .read(true)
            .open(directory.join("logs.jsonl"))?;
        let (sender, receiver) = mpsc::channel();
        recovery::write_json(&directory.join("session-request.json"), &info)?;
        transport::start(receiver, directory.clone(), info.clone());
        let session = Self(Arc::new(Shared {
            directory,
            state: Mutex::new(State {
                info,
                journal,
                sequence: 0,
                offset: 0,
                chunk_index: 0,
            }),
            stopping: AtomicBool::new(false),
            wake: Condvar::new(),
            timer: Mutex::new(()),
            finish_lock: Mutex::new(()),
            sender,
            collector: Mutex::new(None),
            ticker: Mutex::new(None),
            _lock: lock,
        }));
        session.record("step", "session-started", None, None, json!({}))?;
        let weak = Arc::downgrade(&session.0);
        *session.0.ticker.lock().unwrap() = Some(thread::spawn(move || loop {
            let Some(shared) = weak.upgrade() else {
                break;
            };
            let guard = shared.timer.lock().unwrap();
            let _wait = shared
                .wake
                .wait_timeout_while(guard, INTERVAL, |_| {
                    !shared.stopping.load(Ordering::Acquire)
                })
                .unwrap();
            if shared.stopping.load(Ordering::Acquire) {
                break;
            }
            if let Err(error) = InstallationLog(shared.clone()).cut() {
                eprintln!("Session chunk failed: {error}");
            }
            drop(_wait);
            drop(shared);
        }));
        *session.0.collector.lock().unwrap() = Some(logcat::start(
            Arc::downgrade(&session.0),
            adb_path.to_owned(),
        ));
        Ok(session)
    }

    pub fn info(&self) -> SessionInfo {
        self.0.state.lock().unwrap().info.clone()
    }
    pub fn directory(&self) -> PathBuf {
        self.0.directory.clone()
    }
    pub fn active(&self) -> bool {
        self.info().status == Status::InProgress
    }

    pub fn record(
        &self,
        kind: &str,
        name: &str,
        step: Option<&str>,
        operation: Option<&str>,
        data: Value,
    ) -> Result<()> {
        if !matches!(kind, "step" | "user_choice" | "logcat") {
            return Err(Error::new("LOG_KIND", "Неизвестный тип записи журнала"));
        }
        let mut state = self.0.state.lock().unwrap();
        if state.info.status != Status::InProgress {
            return Ok(());
        }
        let record = Record {
            session_id: state.info.session_id.clone(),
            sequence: state.sequence + 1,
            timestamp: Utc::now().to_rfc3339(),
            kind: kind.into(),
            name: name.into(),
            step_id: step.map(str::to_owned),
            operation_id: operation.map(str::to_owned),
            data,
        };
        let mut bytes = serde_json::to_vec(&record)?;
        bytes.push(b'\n');
        let previous_length = state.journal.metadata()?.len();
        if let Err(error) = state
            .journal
            .write_all(&bytes)
            .and_then(|_| state.journal.flush())
        {
            let _ = state.journal.set_len(previous_length);
            return Err(error.into());
        }
        state.sequence = record.sequence;
        if matches!(name, "plan-failed" | "devices-failed") {
            state
                .info
                .vehicle_check_errors
                .push(json!({"timestamp":record.timestamp,"step":name,"error":record.data}));
            recovery::write_json(&self.0.directory.join("session.json"), &state.info)?;
        }
        Ok(())
    }

    pub fn event(&self, event: &Event) -> Result<()> {
        self.record("step", &event.kind, event.step_id.as_deref(), Some(&event.operation_id),
            json!({"message":event.message,"eventTimestamp":event.timestamp,"eventSequence":event.sequence,"details":event.data}))
    }

    pub fn metadata(&self, release: Option<Value>, dns: Option<&str>) -> Result<()> {
        let mut state = self.0.state.lock().unwrap();
        if state.info.status != Status::InProgress {
            return Ok(());
        }
        if let Some(release) = release {
            state.info.selected_release = if release.is_null()
                || release.get("cleared").and_then(Value::as_bool) == Some(true)
            {
                None
            } else if let (Some(Value::Object(mut existing)), Value::Object(update)) =
                (state.info.selected_release.clone(), &release)
            {
                if existing.get("version") == update.get("version") {
                    existing.extend(update.clone());
                    Some(Value::Object(existing))
                } else {
                    Some(release)
                }
            } else {
                Some(release)
            };
        }
        if let Some(dns) = dns {
            if !matches!(dns, "keep" | "on" | "off") {
                return Err(Error::new("LOG_DNS", "Неверный выбор DNS"));
            }
            state.info.dns = dns.into();
        }
        state.info.updated_at = Utc::now().to_rfc3339();
        recovery::write_json(&self.0.directory.join("session.json"), &state.info)
    }

    pub fn bind_device(&self, serial: &str) -> Result<()> {
        let mut state = self.0.state.lock().unwrap();
        if state.info.status != Status::InProgress {
            return Ok(());
        }
        if let Some(existing) = &state.info.device_serial {
            if existing != serial {
                return Err(Error::new(
                    "SESSION_DEVICE_CHANGED",
                    "Для другого автомобиля начните новую сессию установки",
                ));
            }
        } else {
            state.info.device_serial = Some(serial.into());
            state.info.updated_at = Utc::now().to_rfc3339();
            recovery::write_json(&self.0.directory.join("session.json"), &state.info)?;
        }
        Ok(())
    }

    fn cut(&self) -> Result<PathBuf> {
        let mut state = self.0.state.lock().unwrap();
        state.journal.sync_data()?;
        let mut reader = BufReader::new(File::open(self.0.directory.join("logs.jsonl"))?);
        reader.seek(SeekFrom::Start(state.offset))?;
        let mut logs = Vec::new();
        let mut end = state.offset;
        for line in reader.lines() {
            let line = line?;
            end += line.len() as u64 + 1;
            logs.push(serde_json::from_str::<Record>(&line)?);
        }
        state.info.updated_at = Utc::now().to_rfc3339();
        let index = state.chunk_index + 1;
        let request = Chunk {
            schema_version: 1,
            session_id: state.info.session_id.clone(),
            chunk_id: format!("{}:{index}", state.info.session_id),
            chunk_index: index,
            created_at: Utc::now().to_rfc3339(),
            final_chunk: state.info.status != Status::InProgress,
            first_sequence: logs.first().map(|r| r.sequence),
            last_sequence: logs.last().map(|r| r.sequence),
            session: state.info.clone(),
            logs,
        };
        let path = self
            .0
            .directory
            .join("outbox")
            .join(format!("{index:012}.json"));
        recovery::write_json(
            &path,
            &SpoolItem {
                journal_end_offset: end,
                request,
            },
        )?;
        state.offset = end;
        state.chunk_index = index;
        recovery::write_json(&self.0.directory.join("session.json"), &state.info)?;
        let _ = self.0.sender.send(path.clone());
        Ok(path)
    }

    pub fn finish(&self, status: Status, reason: Option<Value>) -> Result<()> {
        let _finishing = self.0.finish_lock.lock().unwrap();
        if status == Status::InProgress {
            return Err(Error::new("SESSION_STATUS", "Нужен итоговый статус"));
        }
        if !self.active() {
            return Ok(());
        }
        let reason = if status == Status::Error {
            Some(reason.unwrap_or_else(
                || json!({"code":"SESSION_ERROR","message":"Установка завершилась ошибкой"}),
            ))
        } else {
            None
        };
        self.0.stopping.store(true, Ordering::Release);
        self.0.wake.notify_all();
        for worker in [&self.0.collector, &self.0.ticker] {
            if let Some(handle) = worker.lock().unwrap().take() {
                let _ = handle.join();
            }
        }
        self.record(
            "step",
            "session-finished",
            None,
            None,
            json!({"status":status,"finalReason":reason}),
        )?;
        {
            let mut state = self.0.state.lock().unwrap();
            state.info.status = status;
            state.info.final_reason = reason;
            let now = Utc::now().to_rfc3339();
            state.info.ended_at = Some(now.clone());
            state.info.updated_at = now;
            recovery::write_json(&self.0.directory.join("session.json"), &state.info)?;
        }
        self.cut()?;
        Ok(())
    }

    pub fn export(&self, destination: &Path) -> Result<()> {
        let mut state = self.0.state.lock().unwrap();
        state.journal.flush()?;
        fs::create_dir_all(destination)?;
        for name in [
            "session.json",
            "session-request.json",
            "session.ack",
            "logs.jsonl",
            "delivery-attempts.jsonl",
        ] {
            let source = self.0.directory.join(name);
            if source.is_file() {
                fs::copy(source, destination.join(name))?;
            }
        }
        fs::create_dir_all(destination.join("outbox"))?;
        for entry in fs::read_dir(self.0.directory.join("outbox"))? {
            let entry = entry?;
            fs::copy(
                entry.path(),
                destination.join("outbox").join(entry.file_name()),
            )?;
        }
        Ok(())
    }

    pub fn recover_interrupted(root: &Path) -> Result<()> {
        if !root.exists() {
            return Ok(());
        }
        for entry in fs::read_dir(root)? {
            let directory = entry?.path();
            let metadata = directory.join("session.json");
            if !metadata.is_file() {
                continue;
            }
            let lock = OpenOptions::new()
                .create(true)
                .truncate(false)
                .read(true)
                .write(true)
                .open(directory.join("session.lock"))?;
            if lock.try_lock_exclusive().is_err() {
                continue;
            }
            let mut info: SessionInfo = serde_json::from_reader(File::open(&metadata)?)?;
            let was_active = info.status == Status::InProgress;
            let mut final_saved = false;
            let mut index = 0;
            let mut offset = 0;
            for item in fs::read_dir(directory.join("outbox"))? {
                let path = item?.path();
                if path.extension().is_some_and(|ext| ext == "json") {
                    let spool: SpoolItem = serde_json::from_reader(File::open(path)?)?;
                    if spool.request.final_chunk {
                        final_saved = true;
                    }
                    if spool.request.chunk_index > index {
                        index = spool.request.chunk_index;
                        offset = spool.journal_end_offset;
                    }
                }
            }
            if !was_active && final_saved {
                continue;
            }
            let reason = json!({"code":"SESSION_INTERRUPTED","message":"Установщик завершился до фиксации результата","displayTitle":"Сессия прервана"});
            if was_active {
                info.status = Status::Error;
                info.final_reason = Some(reason);
                let now = Utc::now().to_rfc3339();
                info.ended_at = Some(now.clone());
                info.updated_at = now;
            }
            let mut reader = BufReader::new(File::open(directory.join("logs.jsonl"))?);
            reader.seek(SeekFrom::Start(offset))?;
            let mut logs = Vec::new();
            // A power loss may leave an incomplete last record; earlier records remain usable.
            for line in reader.lines() {
                if let Ok(record) = serde_json::from_str::<Record>(&line?) {
                    logs.push(record);
                }
            }
            let request = Chunk {
                schema_version: 1,
                session_id: info.session_id.clone(),
                chunk_id: format!("{}:{}", info.session_id, index + 1),
                chunk_index: index + 1,
                created_at: Utc::now().to_rfc3339(),
                final_chunk: true,
                first_sequence: logs.first().map(|r| r.sequence),
                last_sequence: logs.last().map(|r| r.sequence),
                session: info.clone(),
                logs,
            };
            recovery::write_json(
                &directory
                    .join("outbox")
                    .join(format!("{:012}.json", index + 1)),
                &SpoolItem {
                    journal_end_offset: fs::metadata(directory.join("logs.jsonl"))?.len(),
                    request,
                },
            )?;
            recovery::write_json(&metadata, &info)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    fn begin(root: &Path) -> InstallationLog {
        InstallationLog::begin(
            root,
            &root.join("missing-adb"),
            Some(json!({"version":"4.1.1-od"})),
        )
        .unwrap()
    }

    fn read_chunk(path: PathBuf) -> Chunk {
        serde_json::from_reader::<_, SpoolItem>(File::open(path).unwrap())
            .unwrap()
            .request
    }

    #[test]
    fn chunks_are_disjoint_and_final_chunk_contains_exact_reason() {
        let root = tempfile::tempdir().unwrap();
        let session = begin(root.path());
        session
            .record(
                "user_choice",
                "dns-selected",
                Some("review"),
                None,
                json!({"dns":"on"}),
            )
            .unwrap();
        session.metadata(None, Some("on")).unwrap();
        let first = read_chunk(session.cut().unwrap());
        session
            .record(
                "step",
                "plan-failed",
                None,
                None,
                json!({"error":{"code":"CUNBA_DETECTED"}}),
            )
            .unwrap();
        let second = read_chunk(session.cut().unwrap());
        assert!(first.last_sequence.unwrap() < second.first_sequence.unwrap());
        assert_eq!(second.session.vehicle_check_errors.len(), 1);
        assert_eq!(second.session.status, Status::InProgress);
        assert_eq!(second.session.dns, "on");
        let reason = json!({"code":"CUNBA_DISABLE_FAILED","message":"Не удалось отключить Cunba","displayTitle":"Не удалось отключить Cunba"});
        session.finish(Status::Error, Some(reason.clone())).unwrap();
        let final_chunk = read_chunk(session.directory().join("outbox/000000000003.json"));
        assert!(final_chunk.final_chunk);
        assert_eq!(final_chunk.session.final_reason, Some(reason));
        assert_eq!(final_chunk.session.status, Status::Error);
        assert!(final_chunk.session.ended_at.is_some());
        session.finish(Status::Success, None).unwrap();
        assert_eq!(session.info().status, Status::Error);
        if let Ok(output) = std::env::var("INSTALLER_LOG_SCHEMA_EXAMPLES") {
            fs::create_dir_all(&output).unwrap();
            recovery::write_json(
                &Path::new(&output).join("session.json"),
                &final_chunk.session,
            )
            .unwrap();
            recovery::write_json(&Path::new(&output).join("chunk.json"), &final_chunk).unwrap();
        }
    }

    #[test]
    fn retries_reuse_the_exact_chunk_and_wait_three_times() {
        struct Failing {
            ids: Vec<String>,
        }
        impl transport::Transport for Failing {
            fn send(
                &mut self,
                chunk: &Chunk,
                timeout: Duration,
            ) -> std::result::Result<(), String> {
                assert_eq!(timeout, Duration::from_secs(2));
                self.ids.push(serde_json::to_string(chunk).unwrap());
                Err("network unavailable".into())
            }
        }
        let root = tempfile::tempdir().unwrap();
        let session = begin(root.path());
        let chunk = read_chunk(session.cut().unwrap());
        let mut sender = Failing { ids: vec![] };
        let sleeps = AtomicUsize::new(0);
        let mut attempts = vec![];
        assert!(!transport::deliver(
            &mut sender,
            &chunk,
            |duration| {
                assert_eq!(duration, Duration::from_millis(300));
                sleeps.fetch_add(1, Ordering::Relaxed);
            },
            |attempt, _| attempts.push(attempt)
        ));
        assert_eq!(attempts, vec![1, 2, 3, 4]);
        assert_eq!(sleeps.load(Ordering::Relaxed), 3);
        assert!(sender.ids.iter().all(|id| id == &sender.ids[0]));
        session.finish(Status::Success, None).unwrap();
    }

    #[test]
    fn interrupted_session_is_recovered_without_touching_live_sessions() {
        let root = tempfile::tempdir().unwrap();
        let session = begin(root.path());
        session
            .record("logcat", "output", None, None, json!({"text":"last line"}))
            .unwrap();
        InstallationLog::recover_interrupted(root.path()).unwrap();
        assert_eq!(session.info().status, Status::InProgress);
        // Stop workers without writing a final status, emulating process termination.
        session.0.stopping.store(true, Ordering::Release);
        session.0.wake.notify_all();
        for worker in [&session.0.collector, &session.0.ticker] {
            if let Some(handle) = worker.lock().unwrap().take() {
                handle.join().unwrap();
            }
        }
        let directory = session.directory();
        drop(session);
        InstallationLog::recover_interrupted(root.path()).unwrap();
        let final_chunk = read_chunk(directory.join("outbox/000000000001.json"));
        assert_eq!(final_chunk.session.status, Status::Error);
        assert_eq!(
            final_chunk.session.final_reason.unwrap()["code"],
            "SESSION_INTERRUPTED"
        );
        assert!(final_chunk.logs.iter().any(|log| log.kind == "logcat"));
        InstallationLog::recover_interrupted(root.path()).unwrap();
        assert!(!directory.join("outbox/000000000002.json").exists());
    }

    #[test]
    fn cannot_switch_devices_and_finished_session_does_not_accept_new_records() {
        let root = tempfile::tempdir().unwrap();
        let session = begin(root.path());
        session.bind_device("CAR-A").unwrap();
        assert!(session.bind_device("CAR-B").is_err());
        session
            .metadata(Some(json!({"cleared":true})), None)
            .unwrap();
        assert!(session.info().selected_release.is_none());
        session.finish(Status::Success, None).unwrap();
        let before = fs::metadata(session.directory().join("logs.jsonl"))
            .unwrap()
            .len();
        session
            .record("step", "late", None, None, json!({}))
            .unwrap();
        assert_eq!(
            before,
            fs::metadata(session.directory().join("logs.jsonl"))
                .unwrap()
                .len()
        );
    }

    #[test]
    fn periodic_cuts_continue_after_failed_delivery() {
        let root = tempfile::tempdir().unwrap();
        let session = begin(root.path());
        thread::sleep(Duration::from_millis(5300));
        let first = read_chunk(session.directory().join("outbox/000000000001.json"));
        assert!(!first.final_chunk);
        session
            .record("user_choice", "continue", None, None, json!({}))
            .unwrap();
        session.finish(Status::Success, None).unwrap();
        let last = read_chunk(session.directory().join("outbox/000000000002.json"));
        assert!(last.first_sequence > first.last_sequence);
        assert!(last.final_chunk);
    }
}
