use crate::{Error, Result, PROTOCOL_VERSION};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::Path,
    sync::{Arc, Mutex},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub protocol_version: u32,
    pub operation_id: String,
    pub sequence: u64,
    pub timestamp: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub step_id: Option<String>,
    pub message: String,
    pub data: Value,
}
pub type EventCallback = Arc<dyn Fn(&Event) + Send + Sync>;
struct Inner {
    sequence: u64,
    journal: Option<File>,
}
#[derive(Clone)]
pub struct Events {
    operation_id: String,
    inner: Arc<Mutex<Inner>>,
    callback: EventCallback,
}
impl Events {
    pub fn new(
        operation_id: String,
        journal: Option<&Path>,
        callback: EventCallback,
    ) -> Result<Self> {
        let file = journal
            .map(|p| OpenOptions::new().create(true).append(true).open(p))
            .transpose()?;
        Ok(Self {
            operation_id,
            inner: Arc::new(Mutex::new(Inner {
                sequence: 0,
                journal: file,
            })),
            callback,
        })
    }
    pub fn emit(
        &self,
        kind: &str,
        step_id: Option<&str>,
        message: impl Into<String>,
        data: Value,
    ) -> Result<()> {
        let mut inner = self
            .inner
            .lock()
            .map_err(|_| Error::new("JOURNAL_LOCK", "Журнал операции недоступен"))?;
        inner.sequence += 1;
        let event = Event {
            protocol_version: PROTOCOL_VERSION,
            operation_id: self.operation_id.clone(),
            sequence: inner.sequence,
            timestamp: Utc::now().to_rfc3339(),
            kind: kind.into(),
            step_id: step_id.map(str::to_owned),
            message: message.into(),
            data,
        };
        if let Some(file) = inner.journal.as_mut() {
            serde_json::to_writer(&mut *file, &event)?;
            file.write_all(b"\n")?;
            file.flush()?;
            if kind != "command-output" {
                file.sync_data()?;
            }
        }
        (self.callback)(&event);
        Ok(())
    }
    pub fn quiet() -> Self {
        Self::new("diagnostics".into(), None, Arc::new(|_| {})).unwrap()
    }
}
