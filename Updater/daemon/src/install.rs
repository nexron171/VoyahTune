use crate::{
    config::invalid,
    device::{self, command},
    workflow::{self, Shared},
};
use release_core::{
    payload::{self, Payload},
    runtime_maintenance::{Operation as RuntimeOperation, RuntimeMaintenance, Step as RuntimeStep},
};
use std::{
    fs,
    io::{self, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
    thread,
    time::{Duration, Instant},
};

mod application_data;
mod postboot_check;

pub const BLOCK: &str = "/data/local/bin/voyahtune-update.block";
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
        recipe::OTA_STABLE_ARTIFACTS as STABLE,
        recipe::{CopyFile, Phase},
    };

    #[test]
    fn runyn_signature_conflict_reinstalls_once_and_preserves_unrelated_errors() {
        let conflict = Err("pm: exit status 1: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]");
        for (name, reset, replies, success) in [
            ("normal", true, vec![Ok("Success")], true),
            (
                "signature",
                true,
                vec![conflict, Ok("Success"), Ok("Success")],
                true,
            ),
            (
                "signature-stdout",
                true,
                vec![
                    Ok("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
                    Ok("Success"),
                    Ok("Success"),
                ],
                true,
            ),
            ("other-package", false, vec![conflict], false),
            (
                "storage",
                true,
                vec![Err("INSTALL_FAILED_INSUFFICIENT_STORAGE")],
                false,
            ),
            ("timeout", true, vec![Err("Таймаут /system/bin/pm")], false),
            (
                "invalid-apk",
                true,
                vec![Ok("Failure [INSTALL_FAILED_INVALID_APK]")],
                false,
            ),
            (
                "uninstall-failure",
                true,
                vec![conflict, Err("DELETE_FAILED_INTERNAL_ERROR")],
                false,
            ),
            (
                "uninstall-no-success",
                true,
                vec![conflict, Ok("Failure [DELETE_FAILED_INTERNAL_ERROR]")],
                false,
            ),
            (
                "retry-failure",
                true,
                vec![conflict, Ok("Success"), Err("INSTALL_FAILED_INVALID_APK")],
                false,
            ),
            (
                "repeat-signature",
                true,
                vec![conflict, Ok("Success"), conflict],
                false,
            ),
        ] {
            let expected_calls = replies.len();
            let mut replies = std::collections::VecDeque::from(replies);
            let mut commands = Vec::new();
            let arguments = [
                "install",
                "-r",
                "-d",
                "--user",
                "0",
                "/data/local/tmp/RunYN bundle.apk",
            ];
            let result = install_apk_command(&arguments, reset, |arguments| {
                commands.push(
                    arguments
                        .iter()
                        .map(|value| value.to_string())
                        .collect::<Vec<_>>(),
                );
                replies
                    .pop_front()
                    .expect("unexpected extra retry")
                    .map(String::from)
                    .map_err(invalid)
            });
            assert_eq!(result.is_ok(), success, "{name}: {result:?}");
            assert_eq!(commands.len(), expected_calls, "{name}");
            assert_eq!(commands[0], arguments);
            if commands.len() > 1 {
                assert_eq!(commands[1], ["uninstall", payload::RUNYN]);
            }
            if commands.len() > 2 {
                assert_eq!(commands[2], arguments);
            }
            assert!(replies.is_empty(), "{name}");
        }
    }

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
        p.manifest.requirements = Some(release_core::compatibility::Requirements::unified());
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
    fn runtime_files_are_copied_by_the_ota_executor() {
        let dir = tempfile::tempdir().unwrap();
        let mut p = compatibility_fixture(dir.path());
        for name in ["voyahtune-new-voyahtune-loader", "voyahtune-new-agents.dex"] {
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
    }
}

fn package_model(payload: &Payload) -> io::Result<release_core::vehicle_model::VehicleModel> {
    use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};
    if !payload
        .manifest
        .recipe
        .packages
        .iter()
        .any(|package| package.condition.is_some())
    {
        return Ok(VehicleModel::Other);
    }
    let properties = command("/system/bin/sh", &["-c", PROPERTIES_COMMAND], 20)?;
    if properties.trim().is_empty() {
        return Err(invalid("Не удалось определить модель для установки RunYN"));
    }
    Ok(VehicleModel::detect(&properties))
}

fn prepare_system() -> io::Result<()> {
    {
        let _ = command("/system/bin/mount", &["-o", "rw,remount", "/system"], 20);
        let _ = command("/system/bin/mount", &["-o", "rw,remount", "/"], 20);
        return Ok(());
    }
}
fn install_apk(package: &str, apk: &Path) -> io::Result<()> {
    let mut arguments = vec!["install", "-r"];
    {
        arguments.push("-d");
    }
    arguments.extend([
        "--user",
        "0",
        apk.to_str().ok_or_else(|| invalid("Invalid APK path"))?,
    ]);
    let install = || {
        install_apk_command(&arguments, package == payload::RUNYN, |arguments| {
            command("/system/bin/pm", arguments, 180)
        })
    };
    if package == payload::RESTORE {
        application_data::reinstall(
            package,
            install,
            || application_data::transfer("backup", package),
            || {
                let output = command("/system/bin/pm", &["uninstall", package], 180)?;
                require_package_success(output)
            },
            || application_data::transfer("restore", package),
        )
    } else {
        install()
    }
}

fn install_apk_command(
    arguments: &[&str],
    reset_runyn_on_conflict: bool,
    mut run: impl FnMut(&[&str]) -> io::Result<String>,
) -> io::Result<()> {
    let mut result = run(arguments).and_then(require_package_success);
    if reset_runyn_on_conflict
        && result.as_ref().is_err_and(|error| {
            error
                .to_string()
                .contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        })
    {
        // Full removal clears the old signing identity and RunYN's disposable data.
        run(&["uninstall", payload::RUNYN]).and_then(require_package_success)?;
        result = run(arguments).and_then(require_package_success);
    }
    result
}

fn require_package_success(output: String) -> io::Result<()> {
    if output.lines().any(|line| line.trim() == "Success") {
        Ok(())
    } else {
        Err(invalid(&format!("PackageManager: {output}")))
    }
}

fn reset_native_signature(shared: &Shared) -> io::Result<()> {
    application_data::transfer("backup", payload::NATIVE)?;
    command(
        "/system/bin/sh",
        &[
            "-c",
            include_str!("../../../Packaging/installer/common/reset-native-signature.sh"),
        ],
        180,
    )?;
    workflow::update(shared, |state| {
        state.phase = "signature-reboot-pending".into();
        state.step = "Перезагрузка для смены подписи Native".into();
        state.apply_boot = device::boot();
    })?;
    command("/system/bin/sync", &[], 30)?;
    crate::telemetry::before_reboot()?;
    command("/system/bin/reboot", &[], 15)?;
    thread::sleep(Duration::from_secs(30));
    Err(invalid("ГУ не перезагрузилось для смены подписи Native"))
}

pub fn resume_signature_change(shared: &Shared) -> io::Result<()> {
    let start = Instant::now();
    while device::prop("sys.boot_completed")? != "1" {
        if start.elapsed() > Duration::from_secs(180) {
            return Err(invalid(
                "Android не завершил загрузку для продолжения установки",
            ));
        }
        thread::sleep(Duration::from_secs(3));
    }
    apply_and_finalize(shared, false)
}

pub fn apply(shared: &Shared) -> io::Result<()> {
    apply_and_finalize(shared, true)
}

fn apply_and_finalize(shared: &Shared, allow_native_reset: bool) -> io::Result<()> {
    let result = apply_with_signature_reset(shared, allow_native_reset);
    let installation_started = shared.lock().unwrap().state.installation_started();
    if let Err(error) = &result {
        if installation_started {
            workflow::fail(shared, error, true);
            let finalization = crate::install_tail::finalize_failure(
                || {
                    let synchronized = command("/system/bin/sync", &[], 30).map(|_| ());
                    let flushed = crate::telemetry::before_reboot();
                    synchronized.and(flushed)
                },
                || command("/system/bin/reboot", &[], 15).map(|_| ()),
            );
            if let Err(reboot_error) = finalization {
                let _ = crate::log(root(), &format!("failed_install_reboot {reboot_error}"));
            }
        }
    }
    result
}

fn apply_with_signature_reset(shared: &Shared, allow_native_reset: bool) -> io::Result<()> {
    let (p, claims) = workflow::verified(shared)?;
    let model = package_model(&p)?;
    workflow::phase(
        shared,
        if allow_native_reset {
            "preparing"
        } else {
            "applying"
        },
        "Проверка условий установки",
    )?;
    workflow::update(shared, |s| {
        s.completed_steps = 0;
        s.total_steps = 8;
    })?;
    device::wake(true)?;
    crate::turn_sound::restore();
    prepare_system()?;
    let dns_choice = shared
        .lock()
        .unwrap()
        .config
        .as_ref()
        .map_err(|e| invalid(e))?
        .dns_enabled;
    let dns_action = crate::dns::prepare(dns_choice)?;

    workflow::update(shared, |s| s.completed_steps = 1)?;
    // Persist the non-retryable boundary before blocking app launches or changing release files.
    workflow::phase(shared, "applying", "Блокировка запуска приложений")?;
    let mut maintenance = RuntimeMaintenance::new(RuntimeOperation::Install);
    maintenance.execute(RuntimeStep::Block, || {
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
        command(
            "/system/bin/sh",
            &[
                "-c",
                include_str!(
                    "../../../Packaging/installer/common/prepare-runtime-maintenance-device.sh"
                ),
            ],
            30,
        )?;
        Ok::<_, io::Error>(())
    })?;
    maintenance.execute(RuntimeStep::Stop, || {
        command(
            "/system/bin/sh",
            &[
                "-c",
                include_str!("../../../Packaging/installer/common/force-stop-runtime-device.sh"),
            ],
            30,
        )?;
        Ok::<_, io::Error>(())
    })?;
    {
        device::atomic_copy(
            &p.file("init.logcat.original.sh").map_err(error)?,
            Path::new("/system/etc/init.logcat.sh"),
            0o644,
        )?;
        command(
            "/system/bin/sh",
            &[
                "-c",
                include_str!("../../../Packaging/installer/common/disable-third-party-loaders.sh"),
            ],
            60,
        )?;
    }
    workflow::phase(shared, "applying", "Отключение старой активации Apollo")?;
    command(
        "/system/bin/sh",
        &[
            "-c",
            include_str!("../../../Packaging/installer/common/apollo-safe-device.sh"),
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
    maintenance.execute(RuntimeStep::Copy, || {
        for f in &p.manifest.recipe.files {
            if !f.delivered_by_ota() {
                continue;
            }
            workflow::phase(shared, "applying", &format!("Установка {}", f.artifact))?;
            device::atomic_copy(
                &p.file(&f.artifact).map_err(error)?,
                Path::new(&f.destination),
                f.mode,
            )?;
        }
        Ok::<_, io::Error>(())
    })?;
    workflow::update(shared, |s| s.completed_steps = 3)?;
    workflow::phase(shared, "applying", "Права доступа и очистка старых файлов")?;
    maintenance.execute(RuntimeStep::Cleanup, || {
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
        Ok::<_, io::Error>(())
    })?;
    workflow::update(shared, |s| s.completed_steps = 4)?;
    let restore = Path::new("/data/local/tmp/voyahtune-restore-ota.apk");
    device::atomic_copy(&p.file("restore_mode.apk").map_err(error)?, restore, 0o644)?;
    use crate::install_tail::Step;
    crate::install_tail::execute(
        p.manifest
            .recipe
            .packages
            .iter()
            .any(|package| package.package == payload::RUNYN && package.applies_to(model)),
        |step| {
            match step {
                Step::Native => {
                    workflow::phase(shared, "applying", "Установка Native")?;
                    let result = install_apk(payload::NATIVE, Path::new(payload::NATIVE_PATH));
                    if allow_native_reset
                        && result.as_ref().is_err_and(|error| {
                            error
                                .to_string()
                                .contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                        })
                    {
                        return reset_native_signature(shared);
                    }
                    result?;
                    {
                        repair_native_data()?;
                        application_data::transfer("restore", payload::NATIVE)?;
                    }
                    workflow::update(shared, |s| s.completed_steps = 5)?;
                }
                Step::Restore => {
                    {
                        workflow::update(shared, |s| s.completed_steps = 7)?;
                        workflow::phase(shared, "applying", "Установка RestoreMode")?;
                    }
                    install_apk(payload::RESTORE, restore)?;
                    fs::remove_file(restore)?;
                }
                Step::Runyn => {
                    for package in p.manifest.recipe.packages.iter().filter(|package| {
                        package.package == payload::RUNYN && package.applies_to(model)
                    }) {
                        workflow::phase(shared, "applying", "Установка RunYN")?;
                        let path = Path::new("/data/local/tmp/voyahtune-runyn-ota.apk");
                        device::atomic_copy(
                            &p.file(&package.artifact).map_err(error)?,
                            path,
                            0o644,
                        )?;
                        install_apk(payload::RUNYN, path)?;
                        fs::remove_file(path)?;
                    }
                }
                Step::Dns => {
                    workflow::phase(shared, "applying", crate::dns::title(dns_action))?;
                    let result = crate::dns::apply(&p, dns_action);
                    {
                        if let Err(error) = result {
                            let _ = crate::log(root(), &format!("dns_update_warning {error}"));
                        }
                    }
                    workflow::update(shared, |s| s.completed_steps = 6)?;
                }
                Step::NavigationFonts => {
                    workflow::phase(shared, "applying", "Шрифты навигации приборки")?;
                    apply_navigation_fonts(model)?;
                }
                Step::Sync => {
                    workflow::phase(shared, "applying", "Синхронизация перед перезагрузкой")?;
                    command("/system/bin/sync", &[], 30)?;
                }
                Step::Reboot => {
                    maintenance.execute(RuntimeStep::Reboot, || {
                        workflow::update(shared, |s| s.completed_steps = 8)?;
                        // Persist reboot intent only after every file and APK operation succeeded.
                        workflow::update(shared, |s| {
                            s.phase = "reboot-pending".into();
                            s.step = "Перезагрузка ГУ".into();
                            s.apply_boot = device::boot();
                        })?;
                        crate::log(
                            root(),
                            &format!("reboot_pending version={}", claims.version),
                        )?;
                        fs::remove_file(BLOCK)?;
                        fs::File::open("/data/local/bin")?.sync_all()?;
                        command("/system/bin/sync", &[], 30)?;
                        crate::telemetry::before_reboot()?;
                        command("/system/bin/reboot", &[], 15)?;
                        Ok::<_, io::Error>(())
                    })?;
                }
            }
            Ok(())
        },
        |error| {
            let _ = crate::log(root(), &format!("navigation_fonts_warning {error}"));
        },
    )?;
    // A failed reboot must not leave a permanently busy UI or report success.
    thread::sleep(Duration::from_secs(30));
    Err(invalid("ГУ не перезагрузилось после команды reboot"))
}

fn apply_navigation_fonts(model: release_core::vehicle_model::VehicleModel) -> io::Result<()> {
    use release_core::vehicle_model::VehicleModel;
    let model = match model {
        VehicleModel::H97X => "H97X",
        VehicleModel::H97Y => "H97Y",
        VehicleModel::Other => return Ok(()),
    };
    let report = qnx_fonts::apply_on_device(model).map_err(error)?;
    crate::state::save(root(), "navigation-fonts-apply.json", &report)?;
    crate::log(root(), &format!("navigation_fonts {report}"))
}

pub fn validate(shared: &Shared) -> io::Result<()> {
    let previous_boot = shared.lock().unwrap().state.apply_boot.clone();
    let mut maintenance = RuntimeMaintenance::after_reboot(&previous_boot, &device::boot())?;
    maintenance.execute(RuntimeStep::Check, || validate_after_reboot(shared))
}

fn validate_after_reboot(shared: &Shared) -> io::Result<()> {
    workflow::phase(shared, "validating", "Ожидание запуска Android и служб")?;
    let start = Instant::now();
    while device::prop("sys.boot_completed")? != "1" {
        if start.elapsed() > Duration::from_secs(180) {
            return Err(invalid("Android не завершил загрузку за 180 секунд"));
        }
        thread::sleep(Duration::from_secs(3));
    }
    {
        let (payload, claims) = postboot_check::run(|| {
            repair_native_data()?;
            let (payload, claims) = workflow::verified(shared)?;
            Ok((payload, claims))
        })?;
        {
            postboot_check::hooks(|| {
                command(
                    "/data/local/bin/voyahtune-new/voyahtune-loader",
                    &["--check-hooks"],
                    10,
                )
            })?;
        }
        return finish_installation(shared, &payload, &claims);
    }
}

fn repair_native_data() -> io::Result<()> {
    command(
        "/system/bin/sh",
        &[
            "-c",
            include_str!("../../../Packaging/installer/common/repair-native-data.sh"),
        ],
        180,
    )
    .map(|_| ())
}

fn finish_installation(
    shared: &Shared,
    p: &Payload,
    claims: &release_core::ota::Claims,
) -> io::Result<()> {
    for package in [payload::NATIVE, payload::RESTORE] {
        application_data::transfer("cleanup", package)?;
    }
    workflow::update(shared, |s| {
        s.phase = "committed".into();
        s.step = { "Обновление установлено" }.into();
        s.installed_version = if p.manifest.release_version.is_empty() {
            claims.version.clone()
        } else {
            p.manifest.release_version.clone()
        };
        s.installed_archive_sha256 = claims.archive_sha256.clone();
        s.selected = None;
        s.error = None;
        s.notice = Some("success".into());
        s.notice_opened = false;
    })?;
    crate::telemetry::finish(None);
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
    Ok(())
}
