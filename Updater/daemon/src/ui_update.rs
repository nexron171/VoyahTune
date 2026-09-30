//! Runs from the OTA-delivered loader, never from the installed daemon's IPC.
//! Only the system UI APK is replaced; the daemon, init and bootstrap stay intact.
use crate::{
    config::invalid,
    device,
    install::{BLOCK, LOCK},
    state::{self, State},
};
use fs2::FileExt;
use release_core::{
    apk_identity,
    payload::{self, BuildMetadata},
};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    fs::OpenOptions,
    io,
    os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    thread,
    time::{Duration, Instant},
};

const HELPER: &str = "/data/local/bin/voyahtune-ui-maintenance";
const NEXT: &str = "/data/local/bin/voyahtune-ui-next.apk";
const BACKUP: &str = "/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk.ui-backup";
const JOURNAL: &str = "ui-update.json";
const UI: &str = "ru.big.town.updater";

fn error(e: impl ToString) -> io::Error {
    invalid(&e.to_string())
}
fn cmd(program: &str, args: &[&str]) -> io::Result<String> {
    device::command(program, args, 30)
}
fn hash(path: &Path) -> io::Result<String> {
    payload::sha256(path).map_err(error)
}

fn trusted_file(path: &Path) -> io::Result<()> {
    device::no_links(path)?;
    let m = fs::symlink_metadata(path)?;
    if !m.is_file() || m.uid() != 0 || m.permissions().mode() & 0o022 != 0 {
        return Err(invalid(
            "UI update: файл не принадлежит root или доступен для записи",
        ));
    }
    Ok(())
}

fn package_path(package: &str) -> io::Result<String> {
    let result = cmd("/system/bin/pm", &["path", "--user", "0", package])?;
    let paths: Vec<_> = result
        .lines()
        .filter_map(|s| s.strip_prefix("package:"))
        .collect();
    if paths.len() != 1 || !paths[0].starts_with('/') {
        return Err(invalid("UI update: неоднозначный путь APK"));
    }
    Ok(paths[0].into())
}

fn stable(s: &State) -> bool {
    s.schema == 1 && matches!(s.phase.as_str(), "idle" | "committed")
}

fn same_release(s: &State, native: &BuildMetadata, restore: &BuildMetadata) -> bool {
    stable(s)
        && native.schema == 3
        && restore.schema == 3
        && native.product == "VoyahTune"
        && restore.product == "VoyahTune"
        && native.component == payload::NATIVE
        && restore.component == payload::RESTORE
        && native.release_version == s.installed_version
        && native.release_version == restore.release_version
        && native.build_revision == restore.build_revision
        && native.runtime_hashes == restore.runtime_hashes
}

fn cached_version(output: &str, code: u64) -> bool {
    output
        .lines()
        .any(|s| s.trim() == format!("package:{UI} versionCode:{code}"))
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Journal {
    schema: u32,
    target: String,
    original: String,
    original_code: u64,
    boot: String,
    rollback: bool,
}

// These decisions are also used after a power loss between journal, rename and reboot.
#[derive(Debug, PartialEq)]
enum Decision {
    Complete,
    Install,
    Rollback,
    RolledBack,
    Stop,
}
fn decide(j: &Journal, boot: &str, system: &str, active_ok: bool) -> Decision {
    if j.rollback {
        if boot != j.boot && system == j.target {
            return Decision::Rollback;
        }
        return if boot != j.boot && system == j.original && active_ok {
            Decision::RolledBack
        } else {
            Decision::Stop
        };
    }
    if system == j.target && active_ok && boot != j.boot {
        return Decision::Complete;
    }
    if boot == j.boot {
        return Decision::Stop;
    } // A requested reboot did not happen.
    if system == j.target {
        return Decision::Rollback;
    }
    if system == j.original {
        return Decision::Install;
    } // Interrupted before rename.
    Decision::Stop // Another installer changed the system APK; never overwrite its work.
}

struct InstallLock(PathBuf);
impl InstallLock {
    fn acquire(path: &Path, boot: &str) -> io::Result<Self> {
        device::no_links(path)?;
        match fs::create_dir(path) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                let owner = fs::read_to_string(path.join("owner"))?;
                let old_boot = fs::read_to_string(path.join("boot"))?;
                if !matches!(owner.trim(), "ota" | "ui-update") || old_boot.trim() == boot {
                    return Err(invalid("UI update: другая установка использует ГУ"));
                }
                fs::remove_file(path.join("owner"))?;
                fs::remove_file(path.join("boot"))?;
                fs::remove_dir(path)?;
                fs::create_dir(path)?;
            }
            Err(e) => return Err(e),
        }
        let lock = Self(path.into());
        fs::write(path.join("boot"), boot)?;
        fs::write(path.join("owner"), "ui-update")?;
        fs::File::open(path)?.sync_all()?;
        Ok(lock)
    }
}
impl Drop for InstallLock {
    fn drop(&mut self) {
        for file in ["owner", "boot"] {
            let _ = fs::remove_file(self.0.join(file));
        }
        let _ = fs::remove_dir(&self.0);
    }
}

struct RestartDaemon;
impl Drop for RestartDaemon {
    fn drop(&mut self) {
        let _ = cmd("/system/bin/setprop", &["ctl.start", "voyahtune_updater"]);
    }
}

fn writable_system() -> io::Result<()> {
    let _ = cmd("/system/bin/mount", &["-o", "rw,remount", "/system"]);
    let _ = cmd("/system/bin/mount", &["-o", "rw,remount", "/"]);
    // atomic_copy below is the final writeability check; a failed remount never permits reboot.
    Ok(())
}

fn require_parked() -> io::Result<()> {
    let status = cmd(
        "/system/bin/content",
        &[
            "call",
            "--uri",
            "content://ru.big.town.anative.ota",
            "--method",
            "preflight",
        ],
    )?;
    if status.contains("error=")
        || !status.contains("otaReady=true")
        || !status.contains("parked=true")
        || !status.contains("stationary=true")
    {
        return Err(invalid(
            "UI update: не подтверждены P, нулевая скорость и связь приложений",
        ));
    }
    Ok(())
}

fn reboot() -> io::Result<()> {
    require_parked()?;
    cmd("/system/bin/sync", &[])?;
    cmd("/system/bin/reboot", &[])?;
    // Keep the installation lock and daemon stop while shutdown is in progress.
    thread::sleep(Duration::from_secs(30));
    Err(invalid("UI update: ГУ не выполнило перезагрузку"))
}

fn active_matches(code: u64, expected: &str) -> io::Result<bool> {
    if package_path(UI)? != crate::UI_APK {
        return Ok(false);
    }
    let version = cmd(
        "/system/bin/pm",
        &["list", "packages", "--user", "0", "--show-versioncode", UI],
    )?;
    Ok(hash(Path::new(crate::UI_APK))? == expected && cached_version(&version, code))
}

fn maintain(root: &Path, boot: &str) -> io::Result<()> {
    let before: State = state::read(&root.join("state.json"))?;
    if !stable(&before) || Path::new(BLOCK).exists() {
        return Err(invalid("UI update: OTA ещё не завершена"));
    }
    let _lock = InstallLock::acquire(Path::new(LOCK), boot)?;
    // With the install lock held, apply cannot cross its file-writing boundary.
    let fresh: State = state::read(&root.join("state.json"))?;
    if !stable(&fresh) {
        return Err(invalid("UI update: служба начала новую операцию"));
    }
    cmd("/system/bin/setprop", &["ctl.stop", "voyahtune_updater"])?;
    let _restart = RestartDaemon;
    for _ in 0..40 {
        if device::prop("init.svc.voyahtune_updater")? == "stopped" {
            break;
        }
        thread::sleep(Duration::from_millis(250));
    }
    if device::prop("init.svc.voyahtune_updater")? != "stopped" {
        return Err(invalid("UI update: служба не остановилась"));
    }
    let s: State = state::read(&root.join("state.json"))?;
    if !stable(&s)
        || Path::new(BLOCK).exists()
        || s.fingerprint != device::prop("ro.build.fingerprint")?
    {
        return Err(invalid("UI update: состояние OTA или прошивка изменились"));
    }

    let native_path = package_path(payload::NATIVE)?;
    let restore_path = package_path(payload::RESTORE)?;
    for path in [&native_path, &restore_path] {
        trusted_file(Path::new(path))?;
    }
    let native = payload::apk_metadata(Path::new(&native_path))
        .map_err(error)?
        .ok_or_else(|| invalid("UI update: нет метаданных Native"))?;
    let restore = payload::apk_metadata(Path::new(&restore_path))
        .map_err(error)?
        .ok_or_else(|| invalid("UI update: нет метаданных RestoreMode"))?;
    if !same_release(&s, &native, &restore) {
        return Err(invalid(
            "UI update: установленные приложения не соответствуют завершённому релизу",
        ));
    }
    if payload::verified_signers(Path::new(&native_path)).map_err(error)?
        != payload::verified_signers(Path::new(&restore_path)).map_err(error)?
    {
        return Err(invalid(
            "UI update: подписи Native и RestoreMode отличаются",
        ));
    }
    for (name, path) in [
        ("voyahtune-ui-next.apk", NEXT),
        ("voyahtune-ui-maintenance", HELPER),
    ] {
        trusted_file(Path::new(path))?;
        if native.runtime_hashes.get(name) != Some(&hash(Path::new(path))?) {
            return Err(invalid(
                "UI update: staged-файл не совпадает с подписанными метаданными",
            ));
        }
    }
    let target = hash(Path::new(NEXT))?;
    let (package, code, _) = apk_identity::read(Path::new(NEXT)).map_err(error)?;
    if package != UI {
        return Err(invalid("UI update: неверный package ID"));
    }
    trusted_file(Path::new(crate::UI_APK))?;
    if package_path(UI)? != crate::UI_APK {
        return Err(invalid(
            "UI update: активный Updater перекрыт APK из /data/app",
        ));
    }
    let current_hash = hash(Path::new(crate::UI_APK))?;
    let journal_path = root.join(JOURNAL);
    let journal: Option<Journal> = if journal_path.exists() {
        Some(state::read(&journal_path)?)
    } else {
        None
    };
    if journal.is_none() && active_matches(code, &target)? {
        return Ok(());
    }
    if s.installed_archive_sha256.is_empty() && journal.is_none() {
        return Err(invalid(
            "UI update: нет подтверждённой OTA, замена запрещена",
        ));
    }

    if let Some(mut j) = journal {
        if j.schema != 1 || j.target != target {
            return Err(invalid("UI update: незавершённая замена другого APK"));
        }
        let good = active_matches(
            if j.rollback { j.original_code } else { code },
            if j.rollback { &j.original } else { &target },
        )?;
        match decide(&j, boot, &current_hash, good) {
            Decision::Complete => {
                crate::log(root, &format!("ui_update_complete sha256={target}"))?;
                writable_system()?;
                if Path::new(BACKUP).exists() {
                    fs::remove_file(BACKUP)?;
                }
                fs::remove_file(&journal_path)?;
                fs::File::open(root)?.sync_all()?;
                return Ok(());
            }
            Decision::RolledBack => {
                return Err(invalid(
                    "UI update: выполнен откат, автоматический повтор запрещён",
                ))
            }
            Decision::Rollback => {
                require_parked()?;
                trusted_file(Path::new(BACKUP))?;
                if hash(Path::new(BACKUP))? != j.original {
                    return Err(invalid("UI update: резервный APK повреждён"));
                }
                j.rollback = true;
                j.boot = boot.into();
                state::save(root, JOURNAL, &j)?;
                writable_system()?;
                cmd("/system/bin/am", &["force-stop", "--user", "0", UI])?;
                device::atomic_copy(Path::new(BACKUP), Path::new(crate::UI_APK), 0o644)?;
                crate::log(root, "ui_update_rollback_reboot")?;
                return reboot();
            }
            Decision::Stop => {
                return Err(invalid(
                    "UI update: перезагрузка не подтверждена или системный APK изменён",
                ))
            }
            Decision::Install => {}
        }
        // An interrupted rename can be retried, but never on the same boot as the first attempt.
    }
    let (_, old_code, _) = apk_identity::read(Path::new(crate::UI_APK)).map_err(error)?;
    if code <= old_code {
        return Err(invalid("UI update: требуется больший versionCode"));
    }
    if payload::verified_signers(Path::new(NEXT)).map_err(error)?
        != payload::verified_signers(Path::new(crate::UI_APK)).map_err(error)?
    {
        return Err(invalid("UI update: подпись нового интерфейса отличается"));
    }
    require_parked()?;
    writable_system()?;
    device::atomic_copy(Path::new(crate::UI_APK), Path::new(BACKUP), 0o644)?;
    let journal = Journal {
        schema: 1,
        target,
        original: current_hash,
        original_code: old_code,
        boot: boot.into(),
        rollback: false,
    };
    // Journal and rollback copy precede the atomic replacement.
    state::save(root, JOURNAL, &journal)?;
    cmd("/system/bin/am", &["force-stop", "--user", "0", UI])?;
    device::atomic_copy(Path::new(NEXT), Path::new(crate::UI_APK), 0o644)?;
    crate::log(root, "ui_update_install_reboot")?;
    reboot()
}

pub fn run() -> io::Result<()> {
    if unsafe { libc::geteuid() } != 0 {
        return Err(invalid("UI update: требуется root"));
    }
    let root = Path::new(crate::ROOT);
    for _ in 0..30 {
        if root.is_dir() {
            break;
        }
        thread::sleep(Duration::from_secs(1));
    }
    // init owns this directory; don't weaken permissions or invent an independent bootstrap.
    device::no_links(root)?;
    let guard_path = root.join("ui-update.lock");
    device::no_links(&guard_path)?;
    let guard = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(guard_path)?;
    if guard.try_lock_exclusive().is_err() {
        return Ok(());
    }
    let boot = device::boot();
    let deadline = Instant::now() + Duration::from_secs(600);
    while Instant::now() < deadline {
        if device::prop("sys.boot_completed")? == "1" && !Path::new(BLOCK).exists() {
            if let Ok(s) = state::read::<State>(&root.join("state.json")) {
                if s.repair() {
                    return Err(invalid(
                        "UI update: требуется USB-восстановление основной OTA",
                    ));
                }
                if stable(&s) {
                    return maintain(root, &boot);
                }
            }
        }
        thread::sleep(Duration::from_secs(3));
    }
    Err(invalid("UI update: истёк срок ожидания завершения OTA"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn journal() -> Journal {
        Journal {
            schema: 1,
            target: "new".into(),
            original: "old".into(),
            original_code: 2,
            boot: "boot1".into(),
            rollback: false,
        }
    }
    #[test]
    fn interrupted_replacement_requires_another_boot_and_known_bytes() {
        let j = journal();
        assert_eq!(decide(&j, "boot1", "new", true), Decision::Stop);
        assert_eq!(decide(&j, "boot2", "new", true), Decision::Complete);
        assert_eq!(decide(&j, "boot2", "old", false), Decision::Install);
        assert_eq!(decide(&j, "boot2", "external", false), Decision::Stop);
    }
    #[test]
    fn failed_package_scan_rolls_back_once() {
        let mut j = journal();
        assert_eq!(decide(&j, "boot2", "new", false), Decision::Rollback);
        j.rollback = true;
        j.boot = "boot2".into();
        assert_eq!(decide(&j, "boot2", "old", true), Decision::Stop);
        assert_eq!(decide(&j, "boot3", "old", true), Decision::RolledBack);
        assert_eq!(decide(&j, "boot3", "new", false), Decision::Rollback);
    }
    #[test]
    fn operations_and_repair_never_allow_maintenance() {
        let mut s = State::fresh("3.20.0".into(), "rom".into());
        for phase in [
            "checking",
            "downloading",
            "verifying",
            "verified",
            "preparing",
            "applying",
            "reboot-pending",
            "validating",
            "repair-required",
            "failed",
        ] {
            s.phase = phase.into();
            assert!(!stable(&s), "{phase}");
        }
        for phase in ["idle", "committed"] {
            s.phase = phase.into();
            assert!(stable(&s));
        }
        s.schema = 2;
        assert!(!stable(&s));
    }
    #[test]
    fn package_manager_version_must_be_the_exact_package() {
        assert!(cached_version(
            "package:ru.big.town.updater versionCode:3\n",
            3
        ));
        assert!(!cached_version(
            "package:ru.big.town.updater.other versionCode:3",
            3
        ));
        assert!(!cached_version(
            "package:ru.big.town.updater versionCode:2",
            3
        ));
    }
    #[test]
    fn changed_release_or_inconsistent_signed_metadata_blocks_ui_replacement() {
        let s = State::fresh("3.20.0".into(), "rom".into());
        let native = BuildMetadata {
            schema: 3,
            product: "VoyahTune".into(),
            component: payload::NATIVE.into(),
            release_version: "3.20.0".into(),
            build_revision: "revision".into(),
            recipe_sha256: Some("recipe".into()),
            runtime_hashes: [("voyahtune-ui-next.apk".into(), "expected".into())].into(),
        };
        let mut restore = native.clone();
        restore.component = payload::RESTORE.into();
        assert!(same_release(&s, &native, &restore));
        restore
            .runtime_hashes
            .insert("voyahtune-ui-next.apk".into(), "different".into());
        assert!(!same_release(&s, &native, &restore));
        restore = native.clone();
        restore.component = payload::RESTORE.into();
        restore.build_revision = "rebuilt-same-version".into();
        assert!(!same_release(&s, &native, &restore));
        restore.build_revision = native.build_revision.clone();
        let old = State::fresh("3.19.0".into(), "rom".into());
        assert!(!same_release(&old, &native, &restore));
    }
    #[test]
    fn installation_lock_excludes_ota_desktop_and_another_worker() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().canonicalize().unwrap().join("installation");
        let lock = InstallLock::acquire(&path, "boot1").unwrap();
        assert!(InstallLock::acquire(&path, "boot1").is_err());
        drop(lock);
        fs::create_dir(&path).unwrap();
        fs::write(path.join("owner"), "desktop").unwrap();
        fs::write(path.join("boot"), "old-boot").unwrap();
        assert!(InstallLock::acquire(&path, "boot1").is_err());
        assert_eq!(fs::read_to_string(path.join("owner")).unwrap(), "desktop");
        fs::write(path.join("owner"), "ota").unwrap();
        let lock = InstallLock::acquire(&path, "boot1").unwrap();
        assert_eq!(fs::read_to_string(path.join("owner")).unwrap(), "ui-update");
        drop(lock);
        assert!(!path.exists());
    }
}
