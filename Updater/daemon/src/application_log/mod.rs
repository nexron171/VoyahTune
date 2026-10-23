mod capture;
mod storage;
mod upload;

use crate::config::invalid;
use capture::Capture;
use serde::Deserialize;
use serde_json::Value;
use std::{
    fs, io,
    path::Path,
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};
use storage::Store;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    Status,
    Start,
    Stop,
    Upload,
    Delete,
}

struct Request {
    action: Action,
    version: Option<String>,
    reply: mpsc::SyncSender<io::Result<Value>>,
}

pub struct Service(mpsc::Sender<Request>);

impl Service {
    pub fn start(root: &Path) -> io::Result<Self> {
        let directory = root.join("application-log");
        let (requests, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("application-log".into())
            .spawn(move || {
                let store = fs::read_to_string("/proc/sys/kernel/random/boot_id")
                    .and_then(|boot| Store::open(directory, boot.trim()));
                match store {
                    Ok(store) => run(store, receiver),
                    Err(error) => {
                        for request in receiver {
                            let _ = request.reply.send(Err(invalid(&format!(
                                "Системный журнал недоступен: {error}"
                            ))));
                        }
                    }
                }
            })?;
        Ok(Self(requests))
    }

    pub fn call(&self, action: Action, version: Option<String>) -> io::Result<Value> {
        let (reply, response) = mpsc::sync_channel(1);
        self.0
            .send(Request {
                action,
                version,
                reply,
            })
            .map_err(|_| invalid("Служба системного журнала остановлена"))?;
        response
            .recv_timeout(Duration::from_secs(4))
            .map_err(|_| invalid("Системный журнал не ответил вовремя"))?
    }
}

fn run(mut store: Store, requests: mpsc::Receiver<Request>) {
    let mut capture = None;
    let mut upload_events = None;
    let mut retry_capture = Instant::now();
    let mut checkpoint = Instant::now();
    if let Some(upload) = store.pending_upload() {
        match upload::spawn(store.path(), upload) {
            Ok(receiver) => upload_events = Some(receiver),
            Err(error) => {
                let _ = store.finish_upload(Err(error.to_string()), 0);
            }
        }
    }
    loop {
        match requests.recv_timeout(Duration::from_millis(25)) {
            Ok(request) => {
                let result = (|| {
                    match request.action {
                        Action::Status => {}
                        Action::Start => {
                            if !store.enabled() {
                                store.start(capture::current_boundary()?)?;
                                retry_capture = Instant::now();
                            }
                        }
                        Action::Stop => {
                            capture = None;
                            store.stop()?;
                        }
                        Action::Delete => {
                            capture = None;
                            store.delete()?;
                        }
                        Action::Upload => {
                            capture = None;
                            let upload =
                                store.prepare_upload(request.version.as_deref().unwrap_or(""))?;
                            match upload::spawn(store.path(), upload) {
                                Ok(receiver) => upload_events = Some(receiver),
                                Err(error) => {
                                    store.finish_upload(Err(error.to_string()), 0)?;
                                    return Err(error);
                                }
                            }
                        }
                    }
                    Ok(store.snapshot(capture.is_some()))
                })();
                let _ = request.reply.send(result);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }

        if let Some(events) = &upload_events {
            while let Ok(event) = events.try_recv() {
                match event {
                    upload::Event::Progress(message) => store.set_message(message),
                    upload::Event::Finished {
                        result,
                        retry_at_ms,
                    } => {
                        if let Err(error) = store.finish_upload(result, retry_at_ms) {
                            store.set_message(format!(
                                "Не удалось сохранить результат выгрузки: {error}"
                            ));
                        }
                    }
                }
            }
            if store.pending_upload().is_none() {
                upload_events = None;
            }
        }

        if store.enabled() && capture.is_none() && Instant::now() >= retry_capture {
            match Capture::start(store.since()) {
                Ok(process) => {
                    capture = Some(process);
                    store.set_message("Записываем все системные логи".into());
                }
                Err(error) => {
                    store.set_message(format!("Ожидаем системный logcat: {error}"));
                    retry_capture = Instant::now() + Duration::from_secs(1);
                }
            }
        }
        if let Some(process) = capture.as_mut() {
            if let Err(error) = process.poll(&mut store) {
                store.set_message(format!("Ошибка записи: {error}"));
                capture = None;
                retry_capture = Instant::now() + Duration::from_secs(1);
            }
        }
        if store.enabled() && checkpoint.elapsed() >= Duration::from_secs(1) {
            if let Err(error) = store.checkpoint() {
                store.set_message(format!("Не удалось сохранить журнал: {error}"));
            }
            checkpoint = Instant::now();
        }
    }
}
