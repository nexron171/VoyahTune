use crate::{
    config::invalid,
    device::{self, command},
    workflow::{self, Shared},
};
use release_core::payload::{self, Payload};
use std::{
    fs,
    io::{self, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
    thread,
    time::{Duration, Instant},
};
pub const BLOCK: &str = "/data/local/bin/voyahtune-update.block";
pub const LOCK: &str = "/data/local/voyahtune-install.lock";
const STABLE: &[&str] = &[
    "voyahtune-updater",
    "voyahtune-updater.apk",
    "voyahtune.updater.rc",
    "voyahtune-ota-key.der",
    "voyahtune-ota-bootstrap.json",
];
fn error(e: impl ToString) -> io::Error {
    invalid(&e.to_string())
}
fn root() -> &'static Path {
    Path::new(crate::ROOT)
}
pub fn compatible(p: &Payload) -> io::Result<()> {
    for f in &p.manifest.recipe.files {
        if STABLE.contains(&f.artifact.as_str()) || f.artifact == "voyahtune.load.rc" {
            // Updating the updater or the init contract requires USB in this first OTA line.
            if f.artifact != "voyahtune-ota-bootstrap.json"
                && payload::sha256(Path::new(&f.destination)).map_err(error)?
                    != p.artifact(&f.artifact).map_err(error)?.sha256
            {
                return Err(invalid(&format!(
                    "{} требует установки через USB",
                    f.artifact
                )));
            }
        }
        device::no_links(Path::new(&f.destination))?;
    }
    for path in &p.manifest.recipe.remove_files {
        if path.contains("updater")
            || path == BLOCK
            || path.contains("voyahtune-ota-")
            || path.starts_with(LOCK)
        {
            return Err(invalid("Recipe пытается удалить инфраструктуру OTA"));
        }
    }
    if !p.manifest.recipe.remove_directories.is_empty() {
        // Existing legacy loader lock is the only accepted directory tombstone.
        for path in &p.manifest.recipe.remove_directories {
            if path != "/data/local/tmp/voyah_load.lock" {
                return Err(invalid("Удаление каталога требует USB"));
            }
        }
    }
    Ok(())
}
fn package_path(package: &str) -> io::Result<String> {
    let result = command("/system/bin/pm", &["path", package], 20)?;
    let paths: Vec<_> = result
        .lines()
        .filter_map(|l| l.strip_prefix("package:"))
        .collect();
    if paths.len() != 1 {
        return Err(invalid(&format!(
            "Неоднозначный путь APK {package}: {result}"
        )));
    }
    Ok(paths[0].into())
}
fn native_status(safety: bool) -> io::Result<String> {
    command(
        "/system/bin/content",
        &[
            "call",
            "--uri",
            "content://ru.big.town.anative.ota",
            "--method",
            if safety { "preflight" } else { "health" },
        ],
        20,
    )
}
fn preflight(p: &Payload) -> io::Result<()> {
    compatible(p)?;
    let status = native_status(true)?;
    if status.contains("error=")
        || !status.contains("otaReady=true")
        || !status.contains("parked=true")
        || !status.contains("stationary=true")
    {
        return Err(invalid(&format!(
            "Не подтверждены связь приложений, P и нулевая скорость: {status}"
        )));
    }
    for (file, package) in [
        ("native.apk", payload::NATIVE),
        ("restore_mode.apk", payload::RESTORE),
    ] {
        if payload::verified_signers(Path::new(&package_path(package)?)).map_err(error)?
            != payload::verified_signers(&p.file(file).map_err(error)?).map_err(error)?
        {
            return Err(invalid(&format!(
                "Подпись установленного {package} отличается"
            )));
        }
    }
    let _ = command("/system/bin/mount", &["-o", "rw,remount", "/system"], 20);
    let _ = command("/system/bin/mount", &["-o", "rw,remount", "/"], 20);
    let probe = Path::new("/system/.voyahtune-ota-rwtest");
    device::no_links(probe)?;
    let f = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(probe)?;
    f.sync_all()?;
    fs::remove_file(probe)?;
    let system_bytes: u64 = p
        .manifest
        .recipe
        .files
        .iter()
        .filter(|f| f.destination.starts_with("/system/"))
        .map(|f| p.artifact(&f.artifact).map(|a| a.size).unwrap_or(0))
        .sum();
    device::space(Path::new("/system"), system_bytes + 64 * 1024 * 1024)?;
    let apk_bytes = p.artifact("native.apk").map_err(error)?.size
        + p.artifact("restore_mode.apk").map_err(error)?.size;
    device::space(root(), apk_bytes.saturating_mul(3) + 256 * 1024 * 1024)?;
    Ok(())
}
struct OperationLock;
impl OperationLock {
    fn acquire() -> io::Result<Self> {
        match fs::create_dir(LOCK) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                if fs::read_to_string(Path::new(LOCK).join("owner"))?.trim() != "ota" {
                    return Err(invalid("Компьютерный установщик использует ГУ"));
                }
                let old = fs::read_to_string(Path::new(LOCK).join("boot"))?;
                if old.trim() == device::boot() {
                    return Err(invalid("Другая установка использует ГУ"));
                }
                device::no_links(Path::new(LOCK))?;
                fs::remove_file(Path::new(LOCK).join("boot"))?;
                fs::remove_file(Path::new(LOCK).join("owner"))?;
                fs::remove_dir(LOCK)?;
                fs::create_dir(LOCK)?;
            }
            Err(e) => return Err(e),
        }
        fs::write(Path::new(LOCK).join("boot"), device::boot())?;
        fs::write(Path::new(LOCK).join("owner"), "ota")?;
        Ok(Self)
    }
}
impl Drop for OperationLock {
    fn drop(&mut self) {
        let _ = fs::remove_file(Path::new(LOCK).join("boot"));
        let _ = fs::remove_file(Path::new(LOCK).join("owner"));
        let _ = fs::remove_dir(LOCK);
    }
}
pub fn apply(shared: &Shared) -> io::Result<()> {
    let _lock = OperationLock::acquire()?;
    let (p, claims) = workflow::verified(shared)?;
    workflow::phase(shared, "applying", "Проверка условий установки")?;
    preflight(&p)?;
    device::wake(true)?;
    let fresh = native_status(true)?;
    if fresh.contains("error=")
        || !fresh.contains("otaReady=true")
        || !fresh.contains("parked=true")
        || !fresh.contains("stationary=true")
    {
        return Err(invalid(&format!(
            "Состояние автомобиля изменилось перед установкой: {fresh}"
        )));
    }
    let mut block = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o644)
        .custom_flags(libc::O_NOFOLLOW)
        .open(BLOCK)?;
    block.write_all(b"OTA in progress; USB repair required if interrupted\n")?;
    block.sync_all()?;
    fs::File::open("/data/local/bin")?.sync_all()?;
    workflow::phase(shared, "applying", "Остановка приложений и загрузчика")?;
    command("/system/bin/setprop", &["ctl.stop", "voyahtune_load"], 10)?;
    for _ in 0..20 {
        if device::prop("init.svc.voyahtune_load")? == "stopped" {
            break;
        }
        thread::sleep(Duration::from_millis(250));
    }
    if device::prop("init.svc.voyahtune_load")? != "stopped" {
        return Err(invalid("Загрузчик hooks не остановился"));
    }
    for package in [payload::NATIVE, payload::RESTORE] {
        command(
            "/system/bin/am",
            &["force-stop", "--user", "0", package],
            20,
        )?;
    }
    // Injected agents are unloaded by the mandatory reboot. Stop in-flight injector workers.
    let _ = command(
        "/system/bin/pkill",
        &["-f", "/data/local/bin/frida-inject"],
        10,
    );
    for f in &p.manifest.recipe.files {
        if STABLE.contains(&f.artifact.as_str()) {
            continue;
        }
        workflow::phase(shared, "applying", &format!("Установка {}", f.artifact))?;
        device::atomic_copy(
            &p.file(&f.artifact).map_err(error)?,
            Path::new(&f.destination),
            f.mode,
        )?;
    }
    for dir in &p.manifest.recipe.directories {
        device::no_links(Path::new(&dir.path))?;
        fs::create_dir_all(&dir.path)?;
        fs::set_permissions(&dir.path, fs::Permissions::from_mode(dir.mode))?;
    }
    for attr in &p.manifest.recipe.attributes {
        device::no_links(Path::new(&attr.path))?;
        fs::set_permissions(&attr.path, fs::Permissions::from_mode(attr.mode))?;
    }
    for path in &p.manifest.recipe.remove_files {
        if p.manifest
            .recipe
            .files
            .iter()
            .any(|f| f.destination == *path)
        {
            continue;
        }
        if path.starts_with("/sdcard/") {
            continue;
        } // Legacy external logs are outside OTA ownership.
          // Unlink an obsolete symlink itself; never traverse its target.
        device::no_links(Path::new(path).parent().unwrap())?;
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    for path in &p.manifest.recipe.remove_directories {
        device::no_links(Path::new(path))?;
        match fs::remove_dir_all(path) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    workflow::phase(shared, "applying", "Установка APK с сохранением данных")?;
    let restore = Path::new("/data/local/tmp/voyahtune-restore-ota.apk");
    device::atomic_copy(&p.file("restore_mode.apk").map_err(error)?, restore, 0o644)?;
    for apk in [payload::NATIVE_PATH, restore.to_str().unwrap()] {
        let output = command(
            "/system/bin/pm",
            &["install", "-r", "--user", "0", apk],
            180,
        )?;
        if !output.lines().any(|s| s.trim() == "Success") {
            return Err(invalid(&format!("PackageManager: {output}")));
        }
    }
    fs::remove_file(restore)?;
    // Persist reboot intent only after every file and APK operation succeeded.
    workflow::update(shared, |s| {
        s.phase = "reboot-pending".into();
        s.step = "Перезагрузка ГУ".into();
        s.apply_boot = device::boot();
    })?;
    crate::log(
        root(),
        &format!(
            "reboot_pending version={} sequence={}",
            claims.version, claims.sequence
        ),
    )?;
    fs::remove_file(BLOCK)?;
    fs::File::open("/data/local/bin")?.sync_all()?;
    command("/system/bin/sync", &[], 30)?;
    command("/system/bin/reboot", &[], 15)?;
    // A failed reboot must not leave a permanently busy UI or report success.
    thread::sleep(Duration::from_secs(30));
    Err(invalid("ГУ не перезагрузилось после команды reboot"))
}
fn target_pids(target: &str) -> io::Result<Vec<u32>> {
    let mut pids = Vec::new();
    for entry in fs::read_dir("/proc")? {
        let entry = entry?;
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        if let Ok(cmdline) = fs::read(entry.path().join("cmdline")) {
            if cmdline.split(|b| *b == 0).next() == Some(target.as_bytes()) {
                pids.push(pid);
            }
        }
    }
    Ok(pids)
}
fn hook_health() -> io::Result<String> {
    let data = fs::read_to_string("/data/local/tmp/voyahtune-hook-status.v1")?;
    if !data.starts_with("v=1;loader=running;pid=") {
        return Err(invalid("Загрузчик hooks не готов"));
    }
    let mut parts = data.trim().split(';');
    parts.next();
    parts.next();
    let pid = parts
        .next()
        .and_then(|s| s.strip_prefix("pid="))
        .and_then(|s| s.parse::<u32>().ok())
        .ok_or_else(|| invalid("Нет PID загрузчика"))?;
    if pid == 0 || !Path::new(&format!("/proc/{pid}")).exists() {
        return Err(invalid("Процесс загрузчика отсутствует"));
    }
    let targets = [
        ("vd-bypass", "system_server", false),
        ("steering-wheel", "com.qinggan.keymanager.service", false),
        ("launcher-dock", "com.qinggan.app.launcher", false),
        ("multi-display", "com.qinggan.systemservice", false),
        ("apollo-tech", "com.qinggan.app.vehiclesetting", true),
        ("keyboard-en", "com.qinggan.app.qgime", true),
        ("keyboard-ru", "com.qinggan.app.qgime", true),
    ];
    let statuses: Vec<_> = parts.collect();
    for (name, target, optional) in targets {
        let prefix = format!("{name}=");
        let value = statuses
            .iter()
            .find_map(|s| s.strip_prefix(&prefix))
            .ok_or_else(|| invalid(&format!("Нет статуса hook {name}")))?;
        let (state, pid) = value
            .split_once(':')
            .ok_or_else(|| invalid("Некорректный статус hook"))?;
        let live = target_pids(target)?;
        match state {
            "active" if live.contains(&pid.parse::<u32>().map_err(error)?) => {}
            "disabled" if optional => {}
            "waiting" if live.is_empty() => {}
            _ => return Err(invalid(&format!("Hook {name}: {state}, PID {pid}"))),
        }
    }
    // These mandatory agents publish identity markers outside the UI status contract.
    for (target, marker) in [
        ("com.qinggan.canbus.service", "voyahtune_acc_restore.pid"),
        (
            "com.qinggan.app.vehiclesetting",
            "voyahtune_drive_reset.pid",
        ),
    ] {
        for pid in target_pids(target)? {
            let stat = fs::read_to_string(format!("/proc/{pid}/stat"))?;
            let start = stat
                .rsplit_once(") ")
                .and_then(|(_, rest)| rest.split_whitespace().nth(19))
                .ok_or_else(|| invalid("Нет времени старта процесса hook"))?;
            let expected = format!("v2:{}:{pid}:{start}", device::boot());
            if fs::read_to_string(format!("/data/local/tmp/{marker}"))?.trim() != expected {
                return Err(invalid(&format!("Hook {marker} ещё не запущен в {target}")));
            }
        }
    }
    Ok(data)
}
pub fn validate(shared: &Shared) -> io::Result<()> {
    workflow::phase(shared, "validating", "Ожидание запуска Android и служб")?;
    let start = Instant::now();
    while device::prop("sys.boot_completed")? != "1" {
        if start.elapsed() > Duration::from_secs(180) {
            return Err(invalid("Android не завершил загрузку за 180 секунд"));
        }
        thread::sleep(Duration::from_secs(3));
    }
    command(
        "/system/bin/cmd",
        &[
            "package",
            "install-existing",
            "--user",
            "0",
            "--wait",
            payload::NATIVE,
        ],
        60,
    )?;
    command(
        "/system/bin/am",
        &[
            "broadcast",
            "-a",
            "com.qinggan.intent.QINGGAN_BOOT_COMPLETE",
            "-n",
            "ru.big.town.anative/.SetModesReceiverStatic",
        ],
        30,
    )?;
    let expected = shared
        .lock()
        .unwrap()
        .state
        .selected
        .clone()
        .ok_or_else(|| invalid("Нет релиза для проверки после загрузки"))?;
    let claims =
        release_core::ota::verify(&expected, &fs::read("/system/etc/voyahtune-ota-key.der")?)
            .map_err(error)?;
    // Query actual registered packages, not staging hashes. Android owns installed version/signature state.
    for package in [payload::NATIVE, payload::RESTORE] {
        package_path(package)?;
        let dump = command("/system/bin/dumpsys", &["package", package], 30)?;
        if !dump
            .lines()
            .any(|l| l.trim() == format!("versionName={}", claims.version))
        {
            return Err(invalid(&format!("Не запущена ожидаемая версия {package}")));
        }
    }
    let mut ready_since = None;
    let deadline = Instant::now() + Duration::from_secs(180);
    let mut last = "Службы не готовы".to_owned();
    let mut previous_pids = None;
    while Instant::now() < deadline {
        let result = (|| -> io::Result<String> {
            let native = native_status(false)?;
            if native.contains("error=") || !native.contains("otaReady=true") {
                return Err(invalid(&native));
            }
            hook_health()?;
            let native_pid = command("/system/bin/pidof", &[payload::NATIVE], 10)?;
            let restore_pid = command("/system/bin/pidof", &[payload::RESTORE], 10)?;
            Ok(format!("{native_pid}/{restore_pid}"))
        })();
        match result {
            Ok(pids) => {
                if previous_pids.as_ref().is_some_and(|p| p != &pids) {
                    return Err(invalid("Приложение перезапустилось во время проверки"));
                }
                previous_pids = Some(pids);
                let since = ready_since.get_or_insert_with(Instant::now);
                if since.elapsed() >= Duration::from_secs(30) {
                    let events = command(
                        "/system/bin/logcat",
                        &["-b", "events", "-d", "-t", "4000", "-v", "brief"],
                        20,
                    )?;
                    if events.lines().any(|line| {
                        ["am_crash", "am_anr"].iter().any(|k| line.contains(k))
                            && [payload::NATIVE, payload::RESTORE]
                                .iter()
                                .any(|p| line.contains(p))
                    }) {
                        return Err(invalid("В журнале новой загрузки есть crash/ANR VoyahTune"));
                    }
                    workflow::update(shared, |s| {
                        s.phase = "committed".into();
                        s.step = "Обновление установлено, службы работают".into();
                        s.installed_version = claims.version.clone();
                        s.installed_sequence = claims.sequence;
                        s.installed_archive_sha256 = claims.archive_sha256.clone();
                        s.selected = None;
                        s.error = None;
                        s.notice = Some("success".into());
                        s.notice_opened = false;
                    })?;
                    for path in [root().join("release.zip"), root().join("release.part")] {
                        if path.exists() {
                            if let Err(e) = fs::remove_file(path) {
                                let _ = crate::log(root(), &format!("cache_cleanup_warning {e}"));
                            }
                        }
                    }
                    if root().join("staging").exists() {
                        if let Err(e) = fs::remove_dir_all(root().join("staging")) {
                            let _ = crate::log(root(), &format!("cache_cleanup_warning {e}"));
                        }
                    }
                    return Ok(());
                }
            }
            Err(e) => {
                last = e.to_string();
                ready_since = None;
            }
        }
        thread::sleep(Duration::from_secs(3));
    }
    Err(invalid(&format!(
        "Компоненты не прошли проверку запуска: {last}"
    )))
}
