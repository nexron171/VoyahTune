mod journal;
mod logcat;
mod transport;

use crate::{config::invalid, state};
use journal::Journal;
use serde_json::{json, Value};
use std::{
    fs, io,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex, OnceLock,
    },
    thread,
    time::Duration,
};

static CHANNELS: OnceLock<Channels> = OnceLock::new();
static ACTIVE: AtomicBool = AtomicBool::new(false);

enum Event {
    Begin {
        release: Value,
        dns: String,
    },
    Record {
        kind: String,
        name: String,
        data: Value,
    },
    Finish(Option<String>),
    Reboot(mpsc::Sender<io::Result<()>>),
}

struct LogcatLine {
    session_id: String,
    text: String,
}
struct Channels {
    events: mpsc::Sender<Event>,
    logcat: mpsc::SyncSender<LogcatLine>,
    collecting: Arc<Mutex<Option<String>>>,
}

pub fn initialize(root: &Path) -> io::Result<()> {
    if !crate::restore_ui::enabled() {
        return Ok(());
    }
    let (events, receiver) = mpsc::channel();
    let (logcat, lines) = mpsc::sync_channel(512);
    let collecting = Arc::new(Mutex::new(None));
    CHANNELS
        .set(Channels {
            events,
            logcat,
            collecting: collecting.clone(),
        })
        .map_err(|_| invalid("OTA logger already initialized"))?;
    let root = root.to_owned();
    thread::spawn(move || run(root, receiver, lines, collecting));
    thread::spawn(logcat::run);
    Ok(())
}

fn recover(folder: &Path) -> io::Result<Option<Journal>> {
    let pointer = folder.join("active.json");
    if !pointer.exists() {
        return Ok(None);
    }
    let saved: Value = state::read(&pointer)?;
    let id = saved["sessionId"]
        .as_str()
        .ok_or_else(|| invalid("Invalid OTA session ID"))?;
    if !id.starts_with("ota-")
        || !id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    {
        return Err(invalid("Invalid OTA session path"));
    }
    let mut journal = Journal::open(&folder.join(id))?;
    // Seal the previous process's tail before the sender snapshots old packets.
    journal.flush(true, !journal.active())?;
    Ok(Some(journal))
}

fn run(
    root: PathBuf,
    events: mpsc::Receiver<Event>,
    lines: mpsc::Receiver<LogcatLine>,
    collecting: Arc<Mutex<Option<String>>>,
) {
    let folder = root.join("installation-sessions");
    if let Err(error) = fs::create_dir_all(&folder) {
        eprintln!("OTA log directory: {error}");
        return;
    }
    let mut journal = match recover(&folder) {
        Ok(journal) => journal,
        Err(error) => {
            eprintln!("OTA log recovery: {error}");
            None
        }
    };
    if journal.as_ref().is_some_and(Journal::active) {
        ACTIVE.store(true, Ordering::Release);
    }
    let (delivery, requests) = mpsc::channel();
    let outbox = folder.clone();
    let existing = transport::pending(&outbox).unwrap_or_default();
    thread::spawn(move || transport::run(outbox, requests, existing));
    loop {
        *collecting.lock().unwrap() = journal
            .as_ref()
            .filter(|journal| journal.active() && !journal.paused)
            .and_then(|journal| journal.session["sessionId"].as_str().map(str::to_owned));
        match events.recv_timeout(Duration::from_millis(20)) {
            Ok(event) => {
                let result = process(event, &root, &mut journal, &lines, &collecting, &delivery);
                if let Err(error) = result {
                    eprintln!("OTA journal: {error}");
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
        }
        for line in lines.try_iter().take(100) {
            if let Err(error) = append_logcat(&mut journal, line) {
                eprintln!("OTA logcat journal: {error}");
            }
        }
    }
}

fn append_logcat(journal: &mut Option<Journal>, line: LogcatLine) -> io::Result<()> {
    if let Some(journal) = journal.as_mut().filter(|journal| {
        journal.active() && !journal.paused && journal.session["sessionId"] == line.session_id
    }) {
        journal.record("logcat", "logcat.output", json!({"text":line.text}))?;
    }
    Ok(())
}

fn process(
    event: Event,
    root: &Path,
    journal: &mut Option<Journal>,
    lines: &mpsc::Receiver<LogcatLine>,
    collecting: &Mutex<Option<String>>,
    delivery: &mpsc::Sender<transport::Flush>,
) -> io::Result<()> {
    match event {
        Event::Begin { release, dns } => {
            ACTIVE.store(true, Ordering::Release);
            if let Some(current) = journal.as_mut().filter(|journal| journal.active()) {
                if current.session["dns"] != dns {
                    current.session["dns"] = dns.into();
                    current.session["updatedAt"] = journal::timestamp_after(
                        current.session["updatedAt"].as_str().unwrap_or(""),
                    )
                    .into();
                    state::save(&current.directory, "session.json", &current.session)?;
                }
                return Ok(());
            }
            *collecting.lock().unwrap() = None;
            *journal = None;
            clear_previous(root)?;
            let mut current =
                Journal::create(&root.join("installation-sessions"), release, &dns, None)?;
            current.record(
                "user_choice",
                "release.selected",
                json!({"release":current.session["selectedRelease"],"dns":dns}),
            )?;
            *journal = Some(current);
        }
        Event::Record { kind, name, data } => {
            if let Some(current) = journal.as_mut() {
                current.record(&kind, &name, data)?;
            }
        }
        Event::Finish(error) => {
            *collecting.lock().unwrap() = None;
            for line in lines.try_iter() {
                append_logcat(journal, line)?;
            }
            if let Some(current) = journal.as_mut() {
                current.finish(error.as_deref())?;
            }
            ACTIVE.store(false, Ordering::Release);
        }
        Event::Reboot(reply) => {
            *collecting.lock().unwrap() = None;
            let result = (|| {
                for line in lines.try_iter() {
                    append_logcat(journal, line)?;
                }
                if let Some(current) = journal.as_mut() {
                    current.paused = true;
                    current.record(
                        "step",
                        "system.reboot",
                        json!({"message":"Перезагрузка ГУ"}),
                    )?;
                    current.flush(true, true)?;
                    return Ok(Some(current.latest_chunk()));
                }
                Ok(None)
            })();
            match result {
                Ok(Some(path)) => {
                    if let Err(error) = delivery.send(transport::Flush { path, reply }) {
                        eprintln!("OTA pre-reboot delivery deferred: sender stopped");
                        let _ = error.0.reply.send(Ok(()));
                    }
                }
                Ok(None) => {
                    let _ = reply.send(Ok(()));
                }
                Err(error) => {
                    let _ = reply.send(Err(error));
                }
            }
        }
    }
    Ok(())
}

fn clear_previous(root: &Path) -> io::Result<()> {
    let folder = root.join("installation-sessions");
    for entry in fs::read_dir(&folder)? {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_string_lossy().starts_with("ota-") && entry.file_type()?.is_dir() {
            fs::remove_dir_all(entry.path())?;
        } else if name == "active.json" || name == ".active.json.new" {
            fs::remove_file(entry.path())?;
        }
    }
    for name in ["updater.log", "updater.log.1"] {
        match fs::remove_file(root.join(name)) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

fn enqueue(event: Event) -> io::Result<()> {
    if !crate::restore_ui::enabled() {
        return Ok(());
    }
    CHANNELS
        .get()
        .ok_or_else(|| invalid("OTA logger unavailable"))?
        .events
        .send(event)
        .map_err(|_| invalid("OTA logger stopped"))
}

pub fn begin(_root: &Path, release: Value, dns: &str) -> io::Result<()> {
    if crate::restore_ui::enabled() {
        ACTIVE.store(true, Ordering::Release);
    }
    enqueue(Event::Begin {
        release,
        dns: dns.into(),
    })
}

pub fn record(kind: &str, name: &str, data: Value) {
    let _ = enqueue(Event::Record {
        kind: kind.into(),
        name: name.into(),
        data,
    });
}

pub fn finish(error: Option<&str>) {
    let _ = enqueue(Event::Finish(error.map(str::to_owned)));
}

pub fn before_reboot() -> io::Result<()> {
    if !crate::restore_ui::enabled() {
        return Ok(());
    }
    let (reply, result) = mpsc::channel();
    enqueue(Event::Reboot(reply))?;
    result
        .recv()
        .map_err(|_| invalid("OTA log finalization interrupted"))?
}

pub fn collecting_session() -> Option<String> {
    CHANNELS
        .get()
        .and_then(|channels| channels.collecting.lock().unwrap().clone())
}

pub fn logcat_line(session_id: &str, text: String) -> io::Result<()> {
    let Some(channels) = CHANNELS.get() else {
        return Ok(());
    };
    if collecting_session().as_deref() == Some(session_id) {
        channels
            .logcat
            .send(LogcatLine {
                session_id: session_id.into(),
                text,
            })
            .map_err(|_| invalid("OTA log collector stopped"))?;
    }
    Ok(())
}

pub fn active() -> bool {
    ACTIVE.load(Ordering::Acquire)
}

pub fn resume() {
    record(
        "step",
        "service.resumed",
        json!({"bootId":crate::device::boot()}),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovered_tail_is_not_mixed_into_new_live_chunk() {
        let root = tempfile::tempdir().unwrap();
        let mut journal =
            Journal::create(root.path(), json!({"version":"4.2.1-od"}), "keep", None).unwrap();
        journal.record("step", "old-process", json!({})).unwrap();
        drop(journal);
        let mut journal = recover(root.path()).unwrap().unwrap();
        journal.record("step", "new-process", json!({})).unwrap();
        journal.flush(true, false).unwrap();
        let chunk: Value = state::read(&journal.latest_chunk()).unwrap();
        assert_eq!(chunk["logs"].as_array().unwrap().len(), 1);
        assert_eq!(chunk["logs"][0]["name"], "new-process");
    }

    #[test]
    fn reboot_flushes_in_background_and_waits_for_delivery_acknowledgement() {
        let root = tempfile::tempdir().unwrap();
        fs::create_dir(root.path().join("installation-sessions")).unwrap();
        let (_line_sender, lines) = mpsc::sync_channel(8);
        let collecting = Mutex::new(None);
        let (delivery, requests) = mpsc::channel();
        let mut journal = None;
        process(
            Event::Begin {
                release: json!({"version":"4.2.0-od"}),
                dns: "keep".into(),
            },
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        let (reply, acknowledgement) = mpsc::channel();
        process(
            Event::Reboot(reply),
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        let request = requests.try_recv().unwrap();
        let chunk: Value = state::read(&request.path).unwrap();
        assert_eq!(chunk["finalChunk"], true);
        assert_eq!(chunk["session"]["status"], "in_progress");
        assert!(acknowledgement.try_recv().is_err());
        // The writer can keep processing while the sender owns the pending acknowledgement.
        process(
            Event::Record {
                kind: "step".into(),
                name: "still-responsive".into(),
                data: json!({}),
            },
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        request.reply.send(Ok(())).unwrap();
        acknowledgement.recv().unwrap().unwrap();
    }

    #[test]
    fn new_session_clears_sent_and_unsent_logs_but_reboot_keeps_them() {
        let root = tempfile::tempdir().unwrap();
        let folder = root.path().join("installation-sessions");
        fs::create_dir(&folder).unwrap();
        let (_line_sender, lines) = mpsc::sync_channel(8);
        let collecting = Mutex::new(None);
        let (delivery, _requests) = mpsc::channel();
        let mut journal = None;
        let begin = || Event::Begin {
            release: json!({"version":"4.2.0-od"}),
            dns: "keep".into(),
        };
        process(
            begin(),
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        let old = journal.as_ref().unwrap().directory.clone();
        journal.as_mut().unwrap().flush(true, true).unwrap();
        let chunk = journal.as_ref().unwrap().latest_chunk();
        journal = recover(&folder).unwrap();
        assert_eq!(journal.as_ref().unwrap().directory, old);
        assert!(chunk.exists());
        process(
            begin(),
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        assert_eq!(journal.as_ref().unwrap().directory, old);
        process(
            Event::Finish(Some("failed".into())),
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        fs::write(chunk.with_extension("sent"), b"true").unwrap();
        fs::write(root.path().join("updater.log"), b"legacy").unwrap();
        process(
            begin(),
            root.path(),
            &mut journal,
            &lines,
            &collecting,
            &delivery,
        )
        .unwrap();
        assert!(!old.exists());
        assert!(!root.path().join("updater.log").exists());
        assert_ne!(journal.as_ref().unwrap().directory, old);
        assert_eq!(fs::read_dir(&folder).unwrap().count(), 2);
    }
}
