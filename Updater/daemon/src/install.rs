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
    "voyahtune-ota-bootstrap.json",
];
fn error(e: impl ToString) -> io::Error {
    invalid(&e.to_string())
}
fn root() -> &'static Path {
    Path::new(crate::ROOT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use release_core::{
        payload::Artifact,
        recipe::{CopyFile, Phase},
    };

    fn compatibility_fixture(root: &Path) -> Payload {
        let root = root.canonicalize().unwrap();
        let mut p = Payload {
            root: root.clone(),
            manifest: serde_json::from_value(serde_json::json!({
                "schema": 4, "infrastructure": release_core::infrastructure::Infrastructure::compiled(), "product": "VoyahTune", "releaseVersion": "3.15.0",
                "buildRevision": "test", "artifacts": [],
                "recipe": {"schema": 3, "engine": "qinggan-v3", "files": [],
                    "packages": [], "removeFiles": [], "removeDirectories": [],
                    "removePrefixes": [], "removePackages": []}
            }))
            .unwrap(),
        };
        if crate::restore_ui::enabled() {
            p.manifest.requirements = Some(release_core::compatibility::Requirements::restoremode_ota());
        }
        for name in STABLE.iter().copied().chain(["voyahtune.load.rc"]) {
            let installed = root.join(name);
            let archived = root.join(format!("archive-{name}"));
            fs::write(&installed, b"installed").unwrap();
            let bytes: &[u8] = if name == "voyahtune.load.rc" {
                b"installed"
            } else {
                b"different"
            };
            fs::write(&archived, bytes).unwrap();
            p.manifest.artifacts.push(Artifact {
                name: name.into(),
                path: archived.file_name().unwrap().to_str().unwrap().into(),
                sha256: payload::sha256(&archived).unwrap(),
                size: bytes.len() as u64,
            });
            p.manifest.recipe.files.push(CopyFile {
                artifact: name.into(),
                destination: installed.to_str().unwrap().into(),
                mode: 0o644,
                phase: Phase::Files,
            });
        }
        p
    }

    #[test]
    fn embedded_daemon_rejects_releases_that_remove_its_ui() {
        if !crate::restore_ui::enabled() { return; }
        let dir = tempfile::tempdir().unwrap();
        let mut p = compatibility_fixture(dir.path());
        compatible(&p).unwrap();
        p.manifest.requirements = Some(release_core::compatibility::Requirements::infrastructure());
        assert!(compatible(&p).unwrap_err().to_string().contains("не содержит встроенный экран OTA"));
    }

    #[test]
    fn active_apk_hash_rejects_old_bytes_even_for_same_release() {
        let dir = tempfile::tempdir().unwrap();
        let mut p = compatibility_fixture(dir.path());
        let staged = dir.path().join("native.apk");
        fs::write(&staged, b"new Native 3.16.0").unwrap();
        p.manifest.artifacts.push(Artifact {
            name: "native.apk".into(),
            path: "native.apk".into(),
            sha256: payload::sha256(&staged).unwrap(),
            size: fs::metadata(&staged).unwrap().len(),
        });
        let active = dir.path().join("active.apk");
        fs::write(&active, b"old Native 3.16.0").unwrap();
        assert!(check_active_apk(&p, "native.apk", &active)
            .unwrap_err()
            .to_string()
            .contains("не совпадает с payload"));
        fs::copy(&staged, &active).unwrap();
        check_active_apk(&p, "native.apk", &active).unwrap();
        fs::remove_file(&active).unwrap();
        assert!(check_active_apk(&p, "native.apk", &active).is_err());
        assert!(check_active_apk(&p, "missing.apk", &staged).is_err());
    }

    #[test]
    fn different_updater_archive_does_not_block_release() {
        let dir = tempfile::tempdir().unwrap();
        let p = compatibility_fixture(dir.path());
        compatible(&p).unwrap();
        for name in STABLE {
            assert_eq!(fs::read(p.root.join(name)).unwrap(), b"installed");
        }
    }

    #[test]
    fn ui_delivery_is_copied_by_the_existing_ota_file_executor() {
        let dir = tempfile::tempdir().unwrap();
        let mut p = compatibility_fixture(dir.path());
        for name in ["voyahtune-ui-maintenance", "voyahtune-ui-next.apk"] {
            assert!(!STABLE.contains(&name));
            let source = p.root.join(name);
            fs::write(&source, b"new delivery").unwrap();
            p.manifest.artifacts.push(Artifact {
                name: name.into(),
                path: name.into(),
                sha256: payload::sha256(&source).unwrap(),
                size: 12,
            });
            p.manifest.recipe.files.push(CopyFile {
                artifact: name.into(),
                destination: p
                    .root
                    .join(format!("installed-{name}"))
                    .to_str()
                    .unwrap()
                    .into(),
                mode: 0o644,
                phase: Phase::Files,
            });
        }
        compatible(&p).unwrap();
    }

    #[test]
    fn changed_loader_init_still_requires_usb() {
        let dir = tempfile::tempdir().unwrap();
        let p = compatibility_fixture(dir.path());
        fs::write(p.root.join("voyahtune.load.rc"), b"different init contract").unwrap();
        assert!(compatible(&p)
            .unwrap_err()
            .to_string()
            .contains("voyahtune.load.rc требует установки через USB"));
    }

    #[test]
    fn keyboard_not_started_is_not_an_injection_failure() {
        for name in ["keyboard-en", "keyboard-ru"] {
            check_hook_state(name, "unknown:0", true, &[]).unwrap();
            assert!(check_hook_state(name, "unknown:0", true, &[42]).is_err());
            assert!(check_hook_state(name, "unknown:42", true, &[]).is_err());
            for state in ["failed:0", "injecting:0", "active:42"] {
                assert!(check_hook_state(name, state, true, &[]).is_err());
            }
        }
        assert!(check_hook_state("apollo-tech", "unknown:0", true, &[]).is_err());
        assert!(check_hook_state("vd-bypass", "unknown:0", false, &[]).is_err());
    }

    #[test]
    fn live_hook_requires_active_matching_pid_or_optional_disabled() {
        check_hook_state("vd-bypass", "active:42", false, &[42]).unwrap();
        check_hook_state("keyboard-ru", "disabled:42", true, &[42]).unwrap();
        check_hook_state("vd-bypass", "waiting:0", false, &[]).unwrap();
        for state in ["active:41", "waiting:0", "failed:42", "disabled:42"] {
            assert!(check_hook_state("vd-bypass", state, false, &[42]).is_err());
        }
    }

    #[test]
    fn missing_native_data_is_reported_without_recreating_it() {
        let dir = tempfile::tempdir().unwrap();
        let ce = dir.path().join("user/0").join(payload::NATIVE);
        let de = dir.path().join("user_de/0").join(payload::NATIVE);
        assert!(check_native_data(dir.path())
            .unwrap_err()
            .to_string()
            .contains("user/0"));
        assert!(!ce.exists());
        fs::create_dir_all(&ce).unwrap();
        assert!(check_native_data(dir.path())
            .unwrap_err()
            .to_string()
            .contains("user_de/0"));
        assert!(!de.exists());
        fs::create_dir_all(de).unwrap();
        check_native_data(dir.path()).unwrap();
    }
}

pub fn compatible(p: &Payload) -> io::Result<()> {
    p.require_infrastructure(release_core::infrastructure::Infrastructure::compiled())
        .map_err(error)?;
    if crate::restore_ui::enabled() && !p.restoremode_ota() {
        return Err(invalid("Этот релиз не содержит встроенный экран OTA. Установите его через USB"));
    }
    for f in &p.manifest.recipe.files {
        // STABLE files are never installed by OTA, so their archive bytes may differ.
        // The loader init contract is applied and still requires an exact match.
        if f.artifact == "voyahtune.load.rc"
            && payload::sha256(Path::new(&f.destination)).map_err(error)?
                != p.artifact(&f.artifact).map_err(error)?.sha256
        {
            return Err(invalid(&format!(
                "{} требует установки через USB",
                f.artifact
            )));
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
            if ![
                "/data/local/tmp/voyah_load.lock",
                "/data/local/tmp/voyahtune-pi",
            ]
            .contains(&path.as_str())
            {
                return Err(invalid("Удаление каталога требует USB"));
            }
        }
    }
    Ok(())
}
fn package_path(package: &str) -> io::Result<String> {
    let result = command("/system/bin/pm", &["path", "--user", "0", package], 20)?;
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
fn check_active_apk(p: &Payload, artifact: &str, active: &Path) -> io::Result<()> {
    let expected = &p.artifact(artifact).map_err(error)?.sha256;
    let actual = payload::sha256(active).map_err(error)?;
    if actual != *expected {
        return Err(invalid(&format!(
            "Активный {artifact} ({}) не совпадает с payload: {actual}, ожидался {expected}",
            active.display()
        )));
    }
    Ok(())
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
        let installed = package_path(package)?;
        let installed_metadata = payload::apk_metadata(Path::new(&installed))
            .map_err(error)?
            .ok_or_else(|| {
                invalid("Установленный APK не содержит подписанных метаданных инфраструктуры")
            })?;
        installed_metadata
            .infrastructure
            .require(p.manifest.infrastructure)
            .map_err(error)?;
        if payload::verified_signers(Path::new(&installed)).map_err(error)?
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
        + p.manifest
            .recipe
            .packages
            .iter()
            .map(|package| p.artifact(&package.artifact).map(|a| a.size))
            .collect::<release_core::Result<Vec<_>>>()
            .map_err(error)?
            .iter()
            .sum::<u64>();
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
fn install_apk(p: &Payload, package: &str, artifact: &str, apk: &Path) -> io::Result<()> {
    let output = command("/system/bin/pm", &["install", "-r", "--user", "0", apk.to_str().ok_or_else(|| invalid("Invalid APK path"))?], 180)?;
    if !output.lines().any(|s| s.trim() == "Success") {
        return Err(invalid(&format!("PackageManager: {output}")));
    }
    check_active_apk(p, artifact, Path::new(&package_path(package)?))
}

pub fn apply(shared: &Shared) -> io::Result<()> {
    let _lock = OperationLock::acquire()?;
    let (p, claims) = workflow::verified(shared)?;
    workflow::phase(shared, "preparing", "Проверка условий установки")?;
    workflow::update(shared, |s| {
        s.completed_steps = 0;
        s.total_steps = 8;
    })?;
    preflight(&p)?;
    let dns_choice = shared
        .lock()
        .unwrap()
        .config
        .as_ref()
        .map_err(|e| invalid(e))?
        .dns_enabled;
    let dns_action = crate::dns::prepare(&p, dns_choice)?;
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
    workflow::update(shared, |s| s.completed_steps = 1)?;
    // Persist the non-retryable boundary before blocking app launches or changing release files.
    workflow::phase(shared, "applying", "Блокировка запуска приложений")?;
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
        if crate::restore_ui::enabled() && package == payload::RESTORE {
            crate::restore_ui::stop_runtime()?;
            continue;
        }
        command(
            "/system/bin/am",
            &["force-stop", "--user", "0", package],
            20,
        )?;
    }
    workflow::phase(shared, "applying", "Отключение старой активации Apollo")?;
    command(
        "/system/bin/sh",
        &[
            "-c",
            include_str!("../../../Packaging/od/installer/common/apollo-safe-device.sh"),
        ],
        60,
    )?;
    // Injected agents are unloaded by the mandatory reboot. Stop in-flight injector workers.
    let _ = command(
        "/system/bin/pkill",
        &["-f", "/data/local/bin/frida-inject"],
        10,
    );
    workflow::update(shared, |s| s.completed_steps = 2)?;
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
    workflow::update(shared, |s| s.completed_steps = 3)?;
    workflow::phase(shared, "applying", "Права доступа и очистка старых файлов")?;
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
    workflow::update(shared, |s| s.completed_steps = 4)?;
    let restore = Path::new("/data/local/tmp/voyahtune-restore-ota.apk");
    device::atomic_copy(&p.file("restore_mode.apk").map_err(error)?, restore, 0o644)?;
    use crate::install_tail::Step;
    crate::install_tail::execute(crate::restore_ui::enabled(),
        p.manifest.recipe.packages.iter().any(|package| package.package == payload::RUNYN), |step| {
        match step {
            Step::Native => {
                workflow::phase(shared, "applying", "Установка Native")?;
                install_apk(&p, payload::NATIVE, "native.apk", Path::new(payload::NATIVE_PATH))?;
                workflow::update(shared, |s| s.completed_steps = 5)?;
            }
            Step::Restore => {
                if crate::restore_ui::enabled() {
                    workflow::update(shared, |s| s.completed_steps = 7)?;
                    workflow::phase(shared, "applying", "Завершение установки. Экран закроется перед перезагрузкой")?;
                } else {
                    workflow::phase(shared, "applying", "Установка RestoreMode")?;
                }
                install_apk(&p, payload::RESTORE, "restore_mode.apk", restore)?;
                fs::remove_file(restore)?;
                if !crate::restore_ui::enabled() { workflow::update(shared, |s| s.completed_steps = 6)?; }
            }
            Step::Runyn => {
                for package in p.manifest.recipe.packages.iter().filter(|package| package.package == payload::RUNYN) {
                    workflow::phase(shared, "applying", "Установка RunYN")?;
                    let path = Path::new("/data/local/tmp/voyahtune-runyn-ota.apk");
                    device::atomic_copy(&p.file(&package.artifact).map_err(error)?, path, 0o644)?;
                    install_apk(&p, payload::RUNYN, &package.artifact, path)?;
                    fs::remove_file(path)?;
                }
            }
            Step::Dns => {
                workflow::phase(shared, "applying", crate::dns::title(dns_action))?;
                crate::dns::apply(&p, dns_action)?;
                workflow::update(shared, |s| s.completed_steps = if crate::restore_ui::enabled() { 6 } else { 7 })?;
            }
            Step::Sync => {
                workflow::phase(shared, "applying", "Синхронизация перед перезагрузкой")?;
                command("/system/bin/sync", &[], 30)?;
            }
            Step::Reboot => {
                workflow::update(shared, |s| s.completed_steps = 8)?;
                // Persist reboot intent only after every file and APK operation succeeded.
                workflow::update(shared, |s| {
                    s.phase = "reboot-pending".into();
                    s.step = "Перезагрузка ГУ".into();
                    s.apply_boot = device::boot();
                })?;
                crate::log(root(), &format!("reboot_pending version={}", claims.version))?;
                fs::remove_file(BLOCK)?;
                fs::File::open("/data/local/bin")?.sync_all()?;
                command("/system/bin/sync", &[], 30)?;
                command("/system/bin/reboot", &[], 15)?;
            }
        }
        Ok(())
    })?;
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
fn check_hook_state(name: &str, value: &str, optional: bool, live: &[u32]) -> io::Result<()> {
    let (state, pid) = value
        .split_once(':')
        .ok_or_else(|| invalid("Некорректный статус hook"))?;
    let pid = pid.parse::<u32>().map_err(error)?;
    match state {
        "active" if pid != 0 && live.contains(&pid) => Ok(()),
        "disabled" if optional => Ok(()),
        "waiting" if pid == 0 && live.is_empty() => Ok(()),
        // load.bin reads the keyboard setting only on qgime's first process attach.
        // Until then both keyboard lanes report unknown:0; this is not an injection failure.
        "unknown"
            if matches!(name, "keyboard-en" | "keyboard-ru") && pid == 0 && live.is_empty() =>
        {
            Ok(())
        }
        _ => Err(invalid(&format!("Hook {name}: {state}, PID {pid}"))),
    }
}

fn check_native_data(data: &Path) -> io::Result<()> {
    for directory in ["user/0", "user_de/0"] {
        let path = data.join(directory).join(payload::NATIVE);
        if !path.is_dir() {
            return Err(invalid(&format!(
                "Нет каталога данных Native: {}",
                path.display()
            )));
        }
    }
    Ok(())
}

fn pi_health() -> io::Result<String> {
    let data = fs::read_to_string("/data/local/tmp/voyahtune-pi-loader-status.json")?;
    let status: release_core::pi_health::Status = serde_json::from_str(&data).map_err(error)?;
    let stat = fs::read_to_string(format!("/proc/{}/stat", status.loader_pid))?;
    let start = stat
        .rsplit_once(") ")
        .and_then(|(_, rest)| rest.split_whitespace().nth(19))
        .ok_or_else(|| invalid("Нет process identity PI loader"))?;
    let cmdline = fs::read(format!("/proc/{}/cmdline", status.loader_pid))?;
    status
        .validate(
            &device::boot(),
            device::uptime(),
            start,
            &cmdline,
            &device::prop("init.svc.voyahtune_load")?,
        )
        .map_err(error)?;
    // This confirms watchdog liveness only. Vehicle behavior is accepted separately by the user.
    Ok(data)
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
        let live = target_pids(target)?;
        check_hook_state(name, value, optional, &live)?;
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
    // apply() has already installed both APKs for user 0. Postboot only observes
    // registration/data; repeating install-existing --wait can hang on this OEM ROM.
    let packages = command("/system/bin/pm", &["list", "packages", "--user", "0"], 30)?;
    for package in [payload::NATIVE, payload::RESTORE] {
        if !packages
            .lines()
            .any(|line| line.trim() == format!("package:{package}"))
        {
            return Err(invalid(&format!(
                "APK {package} не установлен для пользователя 0"
            )));
        }
    }
    check_native_data(Path::new("/data"))?;
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
    // Reopen the verified payload: versionName alone cannot distinguish rebuilt releases,
    // and dumpsys also lists the inactive system package underneath a /data/app update.
    let (p, claims) = workflow::verified(shared)?;
    for (package, artifact) in [
        (payload::NATIVE, "native.apk"),
        (payload::RESTORE, "restore_mode.apk"),
    ] {
        check_active_apk(&p, artifact, Path::new(&package_path(package)?))?;
    }
    for package in p
        .manifest
        .recipe
        .packages
        .iter()
        .filter(|p| p.package == payload::RUNYN)
    {
        check_active_apk(
            &p,
            &package.artifact,
            Path::new(&package_path(payload::RUNYN)?),
        )?;
    }
    let mut ready_since = None;
    let deadline = Instant::now() + Duration::from_secs(180);
    let mut last = "Службы не готовы".to_owned();
    let mut previous_pids = None;
    let mut previous_hooks = None;
    while Instant::now() < deadline {
        let result = (|| -> io::Result<String> {
            let native = native_status(false)?;
            if native.contains("error=") || !native.contains("otaReady=true") {
                return Err(invalid(&native));
            }
            let hooks = match p.manifest.infrastructure {
                release_core::infrastructure::Infrastructure::Pi => pi_health()?,
                release_core::infrastructure::Infrastructure::Od => hook_health()?,
            };
            if previous_hooks.as_ref() != Some(&hooks) {
                crate::log(root(), &format!("postboot_hooks {}", hooks.trim()))?;
                previous_hooks = Some(hooks);
            }
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
                let message = e.to_string();
                if last != message {
                    crate::log(root(), &format!("postboot_wait {message}"))?;
                }
                last = message;
                ready_since = None;
            }
        }
        thread::sleep(Duration::from_secs(3));
    }
    Err(invalid(&format!(
        "Компоненты не прошли проверку запуска: {last}"
    )))
}
