use crate::config::invalid;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};
static COMMAND_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

pub fn command(program: &str, args: &[&str], seconds: u64) -> io::Result<String> {
    let _guard = COMMAND_LOCK.lock().unwrap();
    let path = Path::new(crate::ROOT).join("command.log");
    let out = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&path)?;
    let mut child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(out.try_clone()?)
        .stderr(out)
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let status = loop {
        if let Some(s) = child.try_wait()? {
            break s;
        }
        if Instant::now() > deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(invalid(&format!("Таймаут {program}")));
        }
        thread::sleep(Duration::from_millis(100));
    };
    let mut bytes = Vec::new();
    File::open(path)?.take(512 * 1024).read_to_end(&mut bytes)?;
    let text = String::from_utf8_lossy(&bytes).trim().to_owned();
    crate::log(
        Path::new(crate::ROOT),
        &format!("command {program} {:?}: {}", args, text),
    )?;
    if !status.success() {
        return Err(invalid(&format!("{program}: {status}: {text}")));
    }
    Ok(text)
}
pub fn prop(name: &str) -> io::Result<String> {
    command("/system/bin/getprop", &[name], 10)
}
pub fn boot() -> String {
    fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .unwrap_or_default()
        .trim()
        .into()
}
pub fn uptime() -> u64 {
    fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok())
        .unwrap_or(0.0) as u64
}
pub fn wall() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
pub fn open_ui() -> io::Result<()> {
    command(
        "/system/bin/am",
        &[
            "start",
            "--user",
            "0",
            "-n",
            crate::restore_ui::component(),
        ],
        20,
    )
    .and_then(|s| {
        if s.contains("Error:") {
            Err(invalid(&s))
        } else {
            Ok(())
        }
    })
}
pub fn space(path: &Path, required: u64) -> io::Result<()> {
    use std::os::unix::ffi::OsStrExt;
    let name =
        std::ffi::CString::new(path.as_os_str().as_bytes()).map_err(|_| invalid("Путь statvfs"))?;
    let mut v: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(name.as_ptr(), &mut v) } != 0 {
        return Err(io::Error::last_os_error());
    }
    let available = (v.f_bavail as u64).saturating_mul(v.f_frsize as u64);
    if available < required {
        return Err(invalid(&format!(
            "Недостаточно места {}: нужно {}, доступно {} байт",
            path.display(),
            required,
            available
        )));
    }
    Ok(())
}
pub fn no_links(path: &Path) -> io::Result<()> {
    let mut current = std::path::PathBuf::new();
    for part in path.components() {
        current.push(part);
        match fs::symlink_metadata(&current) {
            Ok(m) if m.file_type().is_symlink() => {
                return Err(invalid(&format!(
                    "Ссылка в целевом пути {}",
                    current.display()
                )))
            }
            Ok(_) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}
pub fn atomic_copy(source: &Path, target: &Path, mode: u32) -> io::Result<()> {
    no_links(target)?;
    let parent = target
        .parent()
        .ok_or_else(|| invalid("Нет каталога назначения"))?;
    fs::create_dir_all(parent)?;
    let stage = target.with_file_name(format!(
        "{}.voyahtune.new",
        target.file_name().unwrap().to_string_lossy()
    ));
    no_links(&stage)?;
    let mut input = File::open(source)?;
    let mut out = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(mode)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&stage)?;
    io::copy(&mut input, &mut out)?;
    out.set_permissions(fs::Permissions::from_mode(mode))?;
    out.sync_all()?;
    command("/system/bin/chown", &["0:0", stage.to_str().unwrap()], 10)?;
    if target.starts_with("/system") {
        command("/system/bin/restorecon", &[stage.to_str().unwrap()], 10)?;
    }
    fs::rename(stage, target)?;
    File::open(parent)?.sync_all()?;
    if target.starts_with("/system") {
        command("/system/bin/restorecon", &[target.to_str().unwrap()], 10)?;
    }
    Ok(())
}
pub fn wake(hold: bool) -> io::Result<()> {
    let path = if hold {
        "/sys/power/wake_lock"
    } else {
        "/sys/power/wake_unlock"
    };
    OpenOptions::new()
        .write(true)
        .open(path)?
        .write_all(b"voyahtune_ota\n")
}
