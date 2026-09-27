mod config;
mod protocol;

use fs2::FileExt;
use serde_json::{json, Value};
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
use std::{
    fs,
    fs::OpenOptions,
    io::{self, BufReader, Read, Seek, SeekFrom, Write},
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
const LOG_LIMIT: u64 = 256 * 1024;

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
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(root.join("updater.log"))?;
    let size = file.metadata()?.len();
    file.seek(SeekFrom::Start(size.saturating_sub(48 * 1024)))?;
    let mut bytes = vec![];
    file.take(48 * 1024).read_to_end(&mut bytes)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
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
    let mut settings = config::load(root);
    log(root, "service_started")?;
    if settings.is_err() {
        log(root, "settings_invalid; preserved for diagnosis")?;
    }
    let listener = listener_from_init()?;
    for stream in listener.incoming() {
        let mut stream = stream?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        if authorize(&stream).is_err() {
            continue;
        }
        let response: io::Result<Value> =
            (|| match protocol::read_request(&mut BufReader::new(&stream))? {
                protocol::Request::Status {} => Ok(json!({
                    "schema": 1, "ok": true, "serviceVersion": env!("CARGO_PKG_VERSION"),
                    "pid": std::process::id(), "uid": 0,
                    "state": if settings.is_ok() { "ready" } else { "settings_error" },
                    "settings": settings.as_ref().ok(),
                    "error": settings.as_ref().err().map(ToString::to_string),
                    "capabilities": ["status", "catalog_settings", "logs"]
                })),
                protocol::Request::SetCatalogUrl { url } => {
                    let current = settings.as_mut().map_err(|_| {
                        config::invalid(
                            "Настройки повреждены. Сохраните логи и используйте USB-установщик",
                        )
                    })?;
                    if config::change_url(root, current, &url)? {
                        log(root, "catalog_source_changed")?;
                    }
                    Ok(json!({"schema":1,"ok":true,"settings":current}))
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
    if let Err(error) = run() {
        eprintln!("voyahtune-updater: {error}");
        std::process::exit(1);
    }
}
