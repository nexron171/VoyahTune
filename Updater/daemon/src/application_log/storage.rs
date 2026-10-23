use crate::{config::invalid, state};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
    fs::{self, File, OpenOptions},
    io::{self, BufRead, BufReader, Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

pub const MAX_FILE_BYTES: u64 = 15_000_000;
pub const TAIL_LINES: usize = 100;
pub const FILE_NAME: &str = "system-log.txt";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Upload {
    pub session_id: String,
    pub app_version: String,
    pub size: u64,
    pub sha256: String,
    pub pending: bool,
    pub accepted: bool,
    #[serde(default)]
    pub attempt: u32,
    #[serde(default)]
    pub retry_at_ms: u64,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RecordingState {
    enabled: bool,
    boot_id: String,
    since: Option<String>,
    upload: Option<Upload>,
    message: String,
}

pub struct Store {
    directory: PathBuf,
    state: RecordingState,
    file: Option<File>,
    size: u64,
    tail: VecDeque<String>,
    limit: u64,
}

pub fn validate_version(version: &str) -> io::Result<()> {
    if version.trim().is_empty()
        || version.chars().count() > 64
        || version.chars().any(char::is_control)
    {
        return Err(invalid("Некорректная версия приложения"));
    }
    Ok(())
}

fn open_log(path: &Path, append: bool) -> io::Result<File> {
    OpenOptions::new()
        .read(true)
        .write(append)
        .create(append)
        .append(append)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
}

impl Store {
    pub fn open(directory: PathBuf, boot_id: &str) -> io::Result<Self> {
        fs::create_dir_all(&directory)?;
        let metadata = fs::symlink_metadata(&directory)?;
        if !metadata.is_dir() {
            return Err(invalid("Каталог системного журнала недоступен"));
        }
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
        let state = match state::read(&directory.join("state.json")) {
            Ok(value) => value,
            Err(error) if error.kind() == io::ErrorKind::NotFound => RecordingState::default(),
            Err(error) => return Err(error),
        };
        let mut store = Self {
            directory,
            state,
            file: None,
            size: 0,
            tail: VecDeque::new(),
            limit: MAX_FILE_BYTES,
        };
        if let Some(upload) = &store.state.upload {
            if upload.pending {
                store.state.enabled = false;
            }
        }
        // A new boot replays the available logd buffers before following live events.
        if store.state.boot_id != boot_id {
            store.state.boot_id = boot_id.to_owned();
            store.state.since = None;
        }
        if store.state.message.is_empty() {
            store.state.message = "Запись остановлена".into();
        }
        match open_log(&store.path(), false) {
            Ok(file) => {
                store.size = file.metadata()?.len();
                if store.size > store.limit {
                    return Err(invalid("Размер системного журнала превышает 15 МБ"));
                }
                for line in BufReader::new(file).lines() {
                    store.add_tail(line?);
                }
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        if store.enabled() {
            store.file = Some(open_log(&store.path(), true)?);
        }
        store.save()?;
        Ok(store)
    }

    pub fn path(&self) -> PathBuf {
        self.directory.join(FILE_NAME)
    }

    pub fn enabled(&self) -> bool {
        self.state.enabled
    }

    pub fn since(&self) -> Option<String> {
        self.state.since.clone()
    }

    pub fn pending_upload(&self) -> Option<Upload> {
        self.state
            .upload
            .as_ref()
            .filter(|upload| upload.pending)
            .cloned()
    }

    pub fn start(&mut self, since: String) -> io::Result<()> {
        self.require_idle_upload()?;
        if self.enabled() {
            return Ok(());
        }
        self.file = Some(open_log(&self.path(), true)?);
        self.state.enabled = true;
        self.state.since = Some(since);
        self.state.message = "Запись всех системных логов включена".into();
        if let Err(error) = self.save() {
            self.state.enabled = false;
            self.file = None;
            return Err(error);
        }
        Ok(())
    }

    pub fn stop(&mut self) -> io::Result<()> {
        self.state.enabled = false;
        self.state.message = "Запись остановлена, файл сохранён".into();
        self.checkpoint()?;
        self.file = None;
        Ok(())
    }

    pub fn delete(&mut self) -> io::Result<()> {
        self.require_idle_upload()?;
        self.stop()?;
        match fs::remove_file(self.path()) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        self.size = 0;
        self.tail.clear();
        self.state.upload = None;
        self.state.message = "Файлы логов удалены".into();
        self.save()
    }

    pub fn append(&mut self, line: &str) -> io::Result<()> {
        if !self.enabled() {
            return Ok(());
        }
        if line.len() as u64 > self.limit {
            return Err(invalid("Строка журнала превышает лимит файла"));
        }
        // Invalidate a submitted identity before any bytes can change, including after a crash.
        if self.state.upload.take().is_some() {
            self.save()?;
        }
        if self.size + line.len() as u64 > self.limit {
            self.file = None;
            fs::remove_file(self.path())?;
            self.size = 0;
            self.tail.clear();
        }
        if self.file.is_none() {
            self.file = Some(open_log(&self.path(), true)?);
        }
        if let Err(error) = self.file.as_mut().unwrap().write_all(line.as_bytes()) {
            self.size = self.file.as_ref().unwrap().metadata()?.len();
            return Err(error);
        }
        self.size += line.len() as u64;
        self.add_tail(line.trim_end_matches(['\r', '\n']).to_owned());
        if let Some(timestamp) = timestamp(line) {
            self.state.since = Some(timestamp.to_owned());
        }
        Ok(())
    }

    fn add_tail(&mut self, line: String) {
        self.tail.push_back(line);
        while self.tail.len() > TAIL_LINES {
            self.tail.pop_front();
        }
    }

    pub fn prepare_upload(&mut self, version: &str) -> io::Result<Upload> {
        self.require_idle_upload()?;
        self.stop()?;
        validate_version(version)?;
        if self.size == 0 {
            return Err(invalid("Нет записанных логов для отправки"));
        }
        let hash = file_hash(&self.path())?;
        let mut upload = match &self.state.upload {
            Some(upload) if upload.size == self.size && upload.sha256 == hash => upload.clone(),
            _ => Upload {
                session_id: session_id()?,
                app_version: version.to_owned(),
                size: self.size,
                sha256: hash,
                pending: false,
                accepted: false,
                attempt: 0,
                retry_at_ms: 0,
                error: None,
            },
        };
        upload.pending = true;
        upload.accepted = false;
        upload.attempt = upload.attempt.saturating_add(1);
        upload.error = None;
        self.state.upload = Some(upload.clone());
        self.state.message = "Выгружаем запись логов на сервер…".into();
        if let Err(error) = self.save() {
            self.state.upload.as_mut().unwrap().pending = false;
            return Err(error);
        }
        Ok(upload)
    }

    pub fn finish_upload(
        &mut self,
        result: Result<(), String>,
        retry_at_ms: u64,
    ) -> io::Result<()> {
        if let Some(upload) = self.state.upload.as_mut() {
            upload.pending = false;
            upload.accepted = result.is_ok();
            upload.retry_at_ms = retry_at_ms;
            upload.error = result.as_ref().err().cloned();
        }
        self.state.message = match result {
            Ok(()) => "Запись логов принята сервером. Файл сохранён на устройстве".into(),
            Err(error) => format!("Не удалось выгрузить логи: {error}. Файл сохранён"),
        };
        self.save()
    }

    pub fn set_message(&mut self, message: String) {
        self.state.message = message;
    }

    pub fn checkpoint(&self) -> io::Result<()> {
        if let Some(file) = &self.file {
            file.sync_data()?;
        }
        self.save()
    }

    fn save(&self) -> io::Result<()> {
        state::save(&self.directory, "state.json", &self.state)
    }

    fn require_idle_upload(&self) -> io::Result<()> {
        if self.pending_upload().is_some() {
            return Err(invalid("Дождитесь завершения выгрузки логов"));
        }
        Ok(())
    }

    pub fn snapshot(&self, capturing: bool) -> Value {
        let upload_status = match &self.state.upload {
            _ if self.enabled() => "none",
            Some(upload) if upload.pending => "uploading",
            Some(upload) if upload.accepted => "success",
            Some(_) => "error",
            None => "none",
        };
        json!({
            "schema": 1, "ok": true,
            "logging": {
                "enabled": self.enabled(), "capturing": capturing,
                "uploading": self.pending_upload().is_some(),
                "size": self.size, "limit": self.limit, "path": self.path(),
                "message": self.state.message,
                "sessionId": self.state.upload.as_ref().map(|upload| &upload.session_id),
                "uploadStatus": upload_status,
                "uploadAttempt": self.state.upload.as_ref().map(|upload| upload.attempt),
                "uploadError": self.state.upload.as_ref().and_then(|upload| upload.error.as_deref()),
                "log": self.tail.iter().cloned().collect::<Vec<_>>().join("\n")
            }
        })
    }
}

pub fn timestamp(line: &str) -> Option<&str> {
    let value = line.get(..21)?;
    (value.bytes().enumerate().all(|(index, byte)| match index {
        2 => byte == b'-',
        5 => byte == b' ',
        8 | 11 => byte == b':',
        14 => byte == b'.',
        _ => byte.is_ascii_digit(),
    }))
    .then_some(value)
}

pub fn file_hash(path: &Path) -> io::Result<String> {
    let mut file = open_log(path, false)?;
    let mut hash = Sha256::new();
    let mut buffer = [0; 65536];
    loop {
        let length = file.read(&mut buffer)?;
        if length == 0 {
            break;
        }
        hash.update(&buffer[..length]);
    }
    Ok(hex::encode(hash.finalize()))
}

fn session_id() -> io::Result<String> {
    let mut bytes = [0u8; 16];
    File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let value = hex::encode(bytes);
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &value[..8],
        &value[8..12],
        &value[12..16],
        &value[16..20],
        &value[20..]
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stop_restart_appends_and_rotation_never_exceeds_limit() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = Store::open(directory.path().into(), "boot-one").unwrap();
        store.limit = 12;
        store.start("10-19 12:00:00.000000".into()).unwrap();
        store.append("first\n").unwrap();
        store.stop().unwrap();
        store.append("ignored\n").unwrap();
        assert_eq!(fs::read_to_string(store.path()).unwrap(), "first\n");
        store.start("10-19 12:01:00.000000".into()).unwrap();
        store.append("again\n").unwrap();
        assert_eq!(fs::metadata(store.path()).unwrap().len(), 12);
        store.append("new\n").unwrap();
        assert_eq!(fs::read_to_string(store.path()).unwrap(), "new\n");
        assert_eq!(store.tail.len(), 1);
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 2);
        store.delete().unwrap();
        assert!(!store.path().exists());
        assert!(!store.enabled());
        assert!(store.tail.is_empty());
    }

    #[test]
    fn boot_replays_buffers_only_when_recording_was_enabled() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = Store::open(directory.path().into(), "boot-one").unwrap();
        store.start("10-19 12:00:00.000000".into()).unwrap();
        store
            .append("10-19 12:00:01.123456 1 2 E AndroidRuntime: crash\n")
            .unwrap();
        store.checkpoint().unwrap();
        drop(store);
        let store = Store::open(directory.path().into(), "boot-one").unwrap();
        assert_eq!(store.since().as_deref(), Some("10-19 12:00:01.123456"));
        drop(store);
        let mut store = Store::open(directory.path().into(), "boot-two").unwrap();
        assert!(store.enabled());
        assert!(store.since().is_none());
        assert_eq!(store.tail.len(), 1);
        store.stop().unwrap();
        drop(store);
        let store = Store::open(directory.path().into(), "boot-three").unwrap();
        assert!(!store.enabled());
        assert!(store.path().exists());
    }

    #[test]
    fn retries_keep_identity_until_content_changes_and_upload_freezes_file() {
        let directory = tempfile::tempdir().unwrap();
        let mut store = Store::open(directory.path().into(), "boot").unwrap();
        store.start("10-19 12:00:00.000000".into()).unwrap();
        for index in 0..110 {
            store.append(&format!("строка {index}\n")).unwrap();
        }
        assert_eq!(store.tail.len(), 100);
        assert_eq!(store.tail.front().unwrap(), "строка 10");
        let original = store.prepare_upload("4.3.3-od").unwrap();
        assert!(!store.enabled());
        assert!(store.start("later".into()).is_err());
        assert!(store.delete().is_err());
        drop(store);
        let mut store = Store::open(directory.path().into(), "next-boot").unwrap();
        assert_eq!(
            store.pending_upload().unwrap().session_id,
            original.session_id
        );
        store.finish_upload(Err("network".into()), 5000).unwrap();
        let retry = store.prepare_upload("4.3.4-od").unwrap();
        assert_eq!(retry.session_id, original.session_id);
        assert_eq!(retry.app_version, original.app_version);
        assert_eq!(retry.sha256, original.sha256);
        assert_eq!(retry.attempt, original.attempt + 1);
        assert_eq!(retry.retry_at_ms, 5000);
        store.finish_upload(Ok(()), 0).unwrap();
        store.start("later".into()).unwrap();
        store.append("next\n").unwrap();
        let next = store.prepare_upload("4.3.4-od").unwrap();
        assert_ne!(next.session_id, original.session_id);
        assert_eq!(next.app_version, "4.3.4-od");
    }
}
