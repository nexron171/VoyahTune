mod config;
mod device;
mod dns;
mod install;
mod install_tail;
mod network;
mod protocol;
mod restore_ui;
mod state;
mod ui_update;
mod workflow;

use fs2::FileExt;
use serde_json::{json, Value};
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
use std::{
    fs,
    fs::OpenOptions,
    io::{self, BufReader, Read, Write},
    os::{
        fd::FromRawFd,
        unix::{
            fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
            net::{UnixListener, UnixStream},
        },
    },
    path::Path,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const ROOT: &str = "/data/local/voyahtune-updater";
const UI_APK: &str = "/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk";
const LOG_LIMIT: u64 = 512 * 1024;

fn log(root: &Path, event: &str) -> io::Result<()> {
    let path = root.join("updater.log");
    if fs::symlink_metadata(&path).is_ok_and(|m| m.len() >= LOG_LIMIT) {
        fs::rename(&path, root.join("updater.log.1"))?;
    }
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    writeln!(file, "{time} {event}")?;
    file.sync_data()
}

fn logs(root: &Path) -> io::Result<String> {
    let mut result = String::new();
    for name in ["updater.log.1", "updater.log"] {
        match OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(root.join(name))
        {
            Ok(file) => {
                let mut bytes = vec![];
                file.take(LOG_LIMIT + 65536).read_to_end(&mut bytes)?;
                result.push_str(&String::from_utf8_lossy(&bytes));
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    Ok(result)
}

#[cfg(target_os = "android")]
fn peer_uid(stream: &UnixStream) -> io::Result<u32> {
    let mut credentials: libc::ucred = unsafe { std::mem::zeroed() };
    let mut length = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut credentials as *mut _ as *mut libc::c_void,
            &mut length,
        )
    };
    if result != 0 {
        return Err(io::Error::last_os_error());
    }
    if length as usize != std::mem::size_of::<libc::ucred>() {
        return Err(config::invalid("Нет удостоверения клиента"));
    }
    Ok(credentials.uid)
}
#[cfg(not(target_os = "android"))]
fn peer_uid(_: &UnixStream) -> io::Result<u32> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Root-служба предназначена для Android",
    ))
}

fn authorize(stream: &UnixStream) -> io::Result<()> {
    let uid = peer_uid(stream)?;
    if restore_ui::enabled() {
        return restore_ui::authorize(uid);
    }
    // Only the separately installed system UI can use the control channel.
    let apk = fs::symlink_metadata(UI_APK)?;
    if !apk.is_file() || apk.uid() != 0 || apk.permissions().mode() & 0o022 != 0 {
        return Err(config::invalid(
            "Отсутствует доверенный системный интерфейс",
        ));
    }
    let packages = fs::read_to_string("/data/system/packages.list")?;
    let package_settings = fs::read_to_string("/data/system/packages.xml")?;
    if protocol::ui_uid(&packages) != Some(uid)
        || protocol::system_ui_uid(&package_settings) != Some(uid)
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Клиент не имеет доступа",
        ));
    }
    Ok(())
}

fn listener_from_init() -> io::Result<UnixListener> {
    let variable = format!("ANDROID_SOCKET_{}", protocol::SOCKET_NAME);
    let fd = std::env::var(variable)
        .ok()
        .and_then(|s| s.parse::<i32>().ok())
        .filter(|fd| *fd > 2)
        .ok_or_else(|| config::invalid("Служба должна запускаться через Android init"))?;
    if unsafe { libc::listen(fd, 8) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { UnixListener::from_raw_fd(fd) })
}

fn run() -> io::Result<()> {
    if unsafe { libc::geteuid() } != 0 || !cfg!(target_os = "android") {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "Требуется Android UID 0",
        ));
    }
    let root = Path::new(ROOT);
    // init creates this directory before starting the process. Do not follow a substituted link.
    let metadata = fs::symlink_metadata(root)?;
    if !metadata.is_dir() || metadata.uid() != 0 || metadata.permissions().mode() & 0o077 != 0 {
        return Err(config::invalid("Недопустимые права каталога updater"));
    }
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(root.join("daemon.lock"))?;
    lock.try_lock_exclusive()?;
    log(root, "service_started")?;
    let (shared, jobs) = workflow::start()?;
    let listener = listener_from_init()?;
    for stream in listener.incoming() {
        let mut stream = stream?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        if authorize(&stream).is_err() {
            continue;
        }
        let response: io::Result<Value> = (|| match protocol::read_request(&mut BufReader::new(
            &stream,
        ))? {
            protocol::Request::Status {} => {
                let rt = shared.lock().unwrap();
                Ok(
                    json!({"schema":1,"ok":true,"serviceVersion":env!("CARGO_PKG_VERSION"),
                        "capabilities":restore_ui::capabilities(),"pid":std::process::id(),"uid":0,"state":rt.state,"settings":rt.config.as_ref().ok().map(config::Config::response),
                        "settingsError":rt.config.as_ref().err()}),
                )
            }
            protocol::Request::GetSettings {} => {
                let rt = shared.lock().unwrap();
                if rt.state.busy() {
                    return Err(config::invalid("Дождитесь завершения операции"));
                }
                let status = dns::status();
                Ok(
                    json!({"schema":1,"ok":true,"settings":rt.config.as_ref().ok().map(config::Config::response),
                    "dnsStatus":status.as_ref().ok(),"dnsError":status.as_ref().err().map(ToString::to_string)}),
                )
            }
            protocol::Request::SetSettings { url, dns_enabled } => {
                let mut rt = shared.lock().unwrap();
                if rt.state.busy() || rt.state.repair() {
                    return Err(config::invalid("Сейчас нельзя менять настройки"));
                }
                if dns_enabled.is_some() {
                    dns::plan(&dns::status()?, dns_enabled)?;
                }
                let current = rt.config.as_mut().map_err(|e| config::invalid(e))?;
                let changed = config::change_settings(root, current, &url, dns_enabled)?;
                if changed {
                    rt.state.selected = None;
                    rt.state.notice = None;
                    rt.state.phase = "idle".into();
                    rt.state.step = "Источник изменён. Проверьте каталог".into();
                }
                state::save(root, "state.json", &rt.state)?;
                Ok(
                    json!({"schema":1,"ok":true,"settings":rt.config.as_ref().ok().map(config::Config::response)}),
                )
            }
            protocol::Request::SetCatalogUrl { url } => {
                let mut rt = shared.lock().unwrap();
                if rt.state.busy() {
                    return Err(config::invalid("Нельзя менять источник во время операции"));
                }
                let current = rt.config.as_mut().map_err(|e| config::invalid(e))?;
                if config::change_url(root, current, &url)? {
                    rt.state.selected = None;
                    rt.state.notice = None;
                    if !rt.state.repair() {
                        rt.state.phase = "idle".into();
                        rt.state.step = "Источник изменён. Проверьте каталог".into();
                    }
                    state::save(root, "state.json", &rt.state)?;
                    log(root, "catalog_source_changed")?;
                }
                Ok(
                    json!({"schema":1,"ok":true,"settings":rt.config.as_ref().ok().map(config::Config::response)}),
                )
            }
            protocol::Request::Check { same_version } => {
                workflow::queue(&shared, &jobs, workflow::Job::Check(same_version))?;
                Ok(json!({"schema":1,"ok":true}))
            }
            protocol::Request::Download {} => {
                workflow::queue(&shared, &jobs, workflow::Job::Download)?;
                Ok(json!({"schema":1,"ok":true}))
            }
            protocol::Request::Apply {} => {
                workflow::queue(&shared, &jobs, workflow::Job::Apply)?;
                Ok(json!({"schema":1,"ok":true}))
            }
            protocol::Request::Finish { reset_errors } => {
                workflow::update(&shared, |s| {
                    s.finish_result(reset_errors);
                })?;
                Ok(json!({"schema":1,"ok":true}))
            }
            protocol::Request::Dismiss {} => {
                workflow::update(&shared, |s| {
                    s.notice = None;
                    s.notice_opened = true;
                })?;
                Ok(json!({"schema":1,"ok":true}))
            }
            protocol::Request::Logs {} => Ok(json!({"schema":1,"ok":true,"logs":logs(root)?})),
        })();
        let response =
            response.unwrap_or_else(|e| json!({"schema":1,"ok":false,"error":e.to_string()}));
        // A client disconnect is not a daemon failure. No input is passed to shell/process execution.
        let _ = writeln!(stream, "{response}");
    }
    Ok(())
}

fn main() {
    if std::env::args().nth(1).as_deref() == Some("--update-ui") {
        if let Err(error) = ui_update::run() {
            let _ = log(Path::new(ROOT), &format!("ui_update_error {error}"));
            std::process::exit(1);
        }
        return;
    }
    if std::env::args().nth(1).as_deref() == Some("--version") {
        println!(
            "{}",
            json!({"version":env!("CARGO_PKG_VERSION"),"ipcSchema":1,"capabilities":restore_ui::capabilities(),"infrastructure":release_core::infrastructure::Infrastructure::compiled()})
        );
        return;
    }
    if std::env::args().nth(1).as_deref() == Some("--repair-status") {
        if unsafe { libc::geteuid() } != 0 {
            std::process::exit(1);
        }
        match state::read::<state::State>(&Path::new(ROOT).join("state.json")) {
            Ok(s) if s.schema == 1 && s.repair() => {
                println!("repair-required");
                return;
            }
            _ => std::process::exit(1),
        }
    }
    if let Err(error) = run() {
        let _ = log(Path::new(ROOT), &format!("service_fatal {error}"));
        eprintln!("voyahtune-updater: {error}");
        std::process::exit(1);
    }
}
