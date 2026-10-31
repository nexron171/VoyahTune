mod application_log;
mod command_line;
mod config;
mod device;
mod dns;
mod health;
mod install;
mod install_tail;
mod network;
mod protocol;
mod release;
mod restore_ui;
mod state;
mod telemetry;
mod turn_sound;
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
    time::Duration,
};

const ROOT: &str = "/data/local/voyahtune-updater";
const LOG_LIMIT: u64 = 512 * 1024;

fn log(_root: &Path, event: &str) -> io::Result<()> {
    {
        telemetry::record("step", "daemon.event", json!({"message":event}));
        return Ok(());
    }
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
    {
        return restore_ui::authorize(uid);
    }
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
    let application_log = {
        match application_log::Service::start(root) {
            Ok(service) => Some(service),
            Err(error) => {
                let _ = log(root, &format!("application_log_error {error}"));
                None
            }
        }
    };
    log(root, "service_started")?;
    let (shared, jobs) = workflow::start()?;
    let listener = listener_from_init()?;
    for stream in listener.incoming() {
        let mut stream = stream?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        let root_health = peer_uid(&stream).is_ok_and(|uid| uid == 0);
        if !root_health && authorize(&stream).is_err() {
            continue;
        }
        let response: io::Result<Value> = (|| {
            let request = protocol::read_request(&mut BufReader::new(&stream))?;
            if root_health && !health::root_request_allowed(&request) {
                return Err(config::invalid("Root diagnostics only support status"));
            }
            match request {
                protocol::Request::TurnSound { action, sound, volume, progress } => {
                    let runtime = shared.lock().unwrap();
                    if runtime.state.busy() || runtime.state.repair() {
                        return Err(config::invalid("Дождитесь завершения установки или восстановления"));
                    }
                    turn_sound::call(&action, sound.as_deref().unwrap_or("original"), volume.unwrap_or(60), &mut |stage| {
                        if progress {
                            let event = json!({"schema":1,"ok":true,"event":"turn_sound_progress","stage":stage});
                            // A disconnected UI must not interrupt a cluster transaction.
                            let _ = writeln!(stream, "{event}");
                        }
                    })
                }
                protocol::Request::Status {} => {
                    let rt = shared.lock().unwrap();
                    let mut response = json!({"schema":1,"ok":true,"serviceVersion":env!("CARGO_PKG_VERSION"),
                        "capabilities":restore_ui::capabilities(),"pid":std::process::id(),"uid":0,"state":rt.state,"settings":rt.config.as_ref().ok().map(config::Config::response),
                        "settingsError":rt.config.as_ref().err()});
                    {
                        response["infrastructure"] = json!(release_core::infrastructure::Infrastructure::compiled());
                    }
                    Ok(response)
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
                        telemetry::finish(Some("Пользователь изменил источник релизов"));
                        rt.state.clear_catalog();
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
                        telemetry::finish(Some("Пользователь изменил источник релизов"));
                        rt.state.clear_catalog();
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
                protocol::Request::SelectRelease { version } => {
                    workflow::select_release(&shared, &version)?;
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
                        if s.phase == "idle" && reset_errors {
                            telemetry::finish(Some(
                                "Пользователь завершил подготовку без установки",
                            ));
                        }
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
                protocol::Request::ApplicationLog { action, app_version } => application_log
                    .as_ref()
                    .ok_or_else(|| config::invalid("Системный журнал недоступен: требуется обновление root-службы через USB"))?
                    .call(action, app_version),
            }
        })();
        let response =
            response.unwrap_or_else(|e| json!({"schema":1,"ok":false,"error":e.to_string()}));
        // A client disconnect is not a daemon failure. No input is passed to shell/process execution.
        let _ = writeln!(stream, "{response}");
    }
    Ok(())
}

fn main() {
    {
        let command = match command_line::parse(std::env::args().skip(1)) {
            Ok(command) => command,
            Err(error) => {
                eprintln!("{error}");
                std::process::exit(2);
            }
        };
        if command == command_line::Command::Health {
            if unsafe { libc::geteuid() } != 0 {
                std::process::exit(1);
            }
            match health::query() {
                Ok(response) => println!("{response}"),
                Err(error) => {
                    eprintln!("{error}");
                    std::process::exit(1);
                }
            }
            return;
        }
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
