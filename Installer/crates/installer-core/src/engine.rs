//! Common Rust engine; classic commands are derived from Packaging/od/installer/device.
//! Each checked result below corresponds to a checked branch in those scripts.
//! GUI steps, diagnostics and journals do not add vehicle preconditions.
//! The approved VoyahHlCTRL remediation is an opt-in extension to the permission step.
use crate::{
    adb::{quote, Adb},
    canbus::{self, RemovalConsent},
    classic_commands as c,
    events::{EventCallback, Events},
    inventory,
    payload::{self, Payload, NATIVE, NATIVE_PATH, RESTORE},
    plans::{self, Action, Dns, Request},
    recovery::{self, Operation, DEVICE_STATE},
    Error, Result,
};
use serde_json::json;
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};
// pkill uses status 1 for an already absent process; other failures remain warnings.
const STOP_LOADER: &str =
    include_str!("../../../../Packaging/od/installer/common/stop-loader-device.sh");
const LEGACY_INIT: &str = "/system/etc/init.logcat.sh";
const LEGACY_MARKER: &str = "# init.logcat.sh Open Voyah:";
// Postflight commands may repair package registration. Keep each command exclusive,
// but let the device release its lock even if the desktop disconnects.
fn postflight_script(script: &str, owner: &str, timeout_seconds: u32) -> String {
    let owner = quote(owner);
    format!(
        r#"lock=/data/local/voyahtune-install.lock
[ ! -L "$lock" ] || exit 1
mkdir "$lock" 2>/dev/null || {{ echo 'OTA или другой установщик уже использует ГУ'; exit 1; }}
release_postflight_lock() {{
    [ "$(cat "$lock/owner" 2>/dev/null)" = {owner} ] || return 1
    rm -f "$lock/owner" "$lock/boot" && rmdir "$lock" && sync
}}
printf '%s' {owner} > "$lock/owner" || {{ rmdir "$lock"; exit 1; }}
trap 'release_postflight_lock' EXIT
trap 'exit 1' HUP INT TERM
cat /proc/sys/kernel/random/boot_id > "$lock/boot" && chmod 700 "$lock" || exit 1
/system/bin/timeout {timeout_seconds} /system/bin/sh -c {command}
command_status=$?
release_postflight_lock || exit 1
trap - EXIT
exit "$command_status"
"#,
        command = quote(script)
    )
}
// PackageManager may retain an older /data/app update with the same versionCode.
// Never uninstall it (that risks preferences); replace it and verify the active bytes.
fn active_apk_script(package: &str, expected: &str, repair: Option<&str>) -> String {
    let repair = repair.map(|path| format!(
        "source_hash=$(sha256sum {} ) || exit 1\n[ \"${{source_hash%% *}}\" = {expected} ] || exit 1\nresult=$(pm install -r --user 0 {}) || exit 1\nprintf '%s\\n' \"$result\" | grep -qx Success || exit 1\n",
        quote(path), quote(path)
    )).unwrap_or_else(|| "exit 1\n".into());
    format!(
        r#"active_matches() {{
    paths=$(pm path --user 0 {package}) || return 1
    case "$paths" in package:*) ;; *) return 1 ;; esac
    active=${{paths#package:}}
    case "$active" in /*) ;; *) return 1 ;; esac
    case "$active" in *'
'*) return 1 ;; esac
    digest=$(sha256sum "$active") || return 1
    [ "${{digest%% *}}" = {expected} ]
}}
if ! active_matches; then
    {repair}
fi
active_matches || {{ echo 'Активный APK {package} не совпадает с payload'; exit 1; }}
"#,
        package = quote(package),
        expected = quote(expected)
    )
}
// A missing Android service is not evidence of damaged package registration.
// Include the system_server generation so a restart invalidates both observations.
const UPDATER_PROBE: &str = r#"
android_generation() {
    [ "$(getprop sys.boot_completed)" = 1 ] || return 1
    generation=$(pidof system_server) || return 1
    [ -n "$generation" ] || return 1
    service check activity | grep -q ': found' || return 1
    service check package | grep -q ': found' || return 1
    printf '%s' "$generation"
}
probe_updater() {
    before=$(android_generation) || { echo UNAVAILABLE; return; }
    packages=$(pm list packages --user 0) || { echo UNAVAILABLE; return; }
    # An empty/partial response from PackageManager must not trigger uninstall.
    printf '%s\n' "$packages" | grep -qx 'package:android' || { echo UNAVAILABLE; return; }
    state=BROKEN
    if printf '%s\n' "$packages" | grep -qx 'package:ru.big.town.updater'; then
        paths=$(pm path --user 0 ru.big.town.updater) || { echo UNAVAILABLE; return; }
        if printf '%s\n' "$paths" | grep -q '^package:' \
                && [ -d /data/user/0/ru.big.town.updater ] \
                && [ -d /data/user_de/0/ru.big.town.updater ]; then
            state=READY
        fi
    fi
    after=$(android_generation) || { echo UNAVAILABLE; return; }
    [ "$before" = "$after" ] || { echo UNAVAILABLE; return; }
    echo "$state:$after"
}
"#;
pub struct Engine {
    pub adb: Adb,
    pub payload: Payload,
    pub operation: Operation,
    pub cancel: Arc<AtomicBool>,
    pub canbus_consent: Option<Arc<RemovalConsent>>,
    restart_loader: bool,
    legacy_migrated: bool,
    ota_locked: bool,
    installed_runtime_hashes: BTreeSet<(String, String)>,
}
impl Engine {
    pub fn new(
        adb_path: &Path,
        payload: Payload,
        serial: &str,
        logs: &Path,
        callback: EventCallback,
        cancel: Arc<AtomicBool>,
    ) -> Result<Self> {
        let operation = Operation::create(serial, logs)?;
        let events = Events::new(
            operation.id.clone(),
            Some(&operation.dir.join("events.jsonl")),
            callback,
        )?;
        let adb = Adb::new(adb_path, events)?.with_device(serial)?;
        Ok(Self {
            adb,
            payload,
            operation,
            cancel,
            canbus_consent: None,
            restart_loader: false,
            legacy_migrated: false,
            ota_locked: false,
            installed_runtime_hashes: BTreeSet::new(),
        })
    }
    pub fn run(&mut self, request: Request) -> Result<()> {
        if !request.confirmed || request.serial != self.adb.serial.as_deref().unwrap_or("") {
            return Err(Error::new(
                "CONFIRMATION_REQUIRED",
                "Подтвердите найденный автомобиль и план операции",
            ));
        }
        let mut result = self.execute(request);
        if let Err(error) = self.ota_unlock() {
            if result.is_ok() {
                result = Err(error);
            } else {
                self.warning(&error);
            }
        }
        if result.is_err() {
            self.ignore("if [ -x /data/local/bin/voyahtune-updater ]; then setprop ctl.start voyahtune_updater; fi\n");
        }
        // Restart the loader only before the accepted final reboot.
        if self.restart_loader {
            self.ignore("setprop ctl.start voyahtune_load 2>/dev/null || true\n");
        }
        let mut report = match &result {
            Ok(()) => {
                json!({"success":true,"operationId":self.operation.id,"releaseVersion":self.payload.manifest.release_version,"installerVersion":env!("CARGO_PKG_VERSION"),"payloadManifest":self.payload.manifest})
            }
            Err(e) => json!({"success":false,"operationId":self.operation.id,"error":e,
                "recovery":"Завершённые изменения могли сохраниться. Повторите установку или удаление после устранения указанной причины."}),
        };
        report["releaseVersion"] = json!(self.payload.manifest.release_version);
        report["installerVersion"] = json!(crate::compatibility::INSTALLER_VERSION);
        report["manifestSha256"] =
            json!(payload::sha256(&self.payload.root.join("manifest.json"))?);
        report["payloadManifest"] = json!(self.payload.manifest);
        if let Ok(bytes) = fs::read(self.operation.dir.join("mode.json")) {
            report["mode"] = serde_json::from_slice(&bytes)?;
        }
        recovery::write_json(&self.operation.dir.join("report.json"), &report)?;
        self.adb.events.emit(
            if result.is_ok() {
                "operation-completed"
            } else if result.as_ref().is_err_and(|e| e.code == "CANCELLED") {
                "operation-cancelled"
            } else if result.as_ref().is_err_and(|e| e.code == "ACTION_REQUIRED") {
                "operation-paused"
            } else {
                "operation-failed"
            },
            self.adb.step.as_deref(),
            if result.is_ok() {
                "Операция завершена"
            } else {
                "Операция остановлена"
            },
            json!({"report":report,"reportPath":self.operation.dir.join("report.json")}),
        )?;
        result
    }
    fn step(
        &mut self,
        id: &str,
        title: &str,
        f: impl FnOnce(&mut Self) -> Result<()>,
    ) -> Result<()> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(Error::new("CANCELLED", "Операция остановлена между шагами"));
        }
        self.adb.step = Some(id.into());
        self.adb
            .events
            .emit("step-started", Some(id), title, json!({}))?;
        match f(self) {
            Ok(()) => self
                .adb
                .events
                .emit("step-completed", Some(id), title, json!({})),
            Err(e) => {
                self.adb.events.emit(
                    match e.code.as_str() {
                        "CANCELLED" => "step-cancelled",
                        "ACTION_REQUIRED" => "step-paused",
                        _ => "step-failed",
                    },
                    Some(id),
                    &e.message,
                    json!({"error":e}),
                )?;
                Err(e)
            }
        }
    }
    fn raw(&self, args: &[&str]) -> Result<crate::adb::Output> {
        let timeout = if self.adb.step.as_deref() == Some("verify") {
            Duration::from_secs(if args == ["wait-for-device"] { 180 } else { 30 })
        } else {
            Duration::MAX
        };
        self.adb.run(args, None, timeout)
    }
    fn shell(&self, script: &str) -> Result<String> {
        if self.adb.step.as_deref() == Some("verify") {
            self.adb.shell(script, Duration::from_secs(30))
        } else {
            self.adb.shell(script, Duration::MAX)
        }
    }
    fn postflight_shell(&self, script: &str) -> Result<String> {
        self.adb.shell(
            &postflight_script(
                script,
                &format!("desktop:{}:postflight", self.operation.id),
                180,
            ),
            Duration::from_secs(210),
        )
    }
    fn native_broadcast(&self) -> Result<()> {
        let before = self.shell("pidof system_server\n")?;
        let owner = format!("desktop:{}:postflight", self.operation.id);
        let command = postflight_script(c::NATIVE_BROADCAST, &owner, 15);
        let first = self.adb.shell(&command, Duration::from_secs(25));
        if let Err(error) = first {
            let after = self.shell("pidof system_server\n").unwrap_or_default();
            if before.is_empty() || after.is_empty() || before == after {
                return Err(error);
            }
            if self
                .shell("[ ! -e /data/local/voyahtune-install.lock ] && echo CLEAR\n")
                .unwrap_or_default()
                != "CLEAR"
            {
                return Err(error);
            }
            self.warning(&error);
            let mut ready = false;
            for _ in 0..15 {
                if self
                    .shell("getprop sys.boot_completed\n")
                    .unwrap_or_default()
                    == "1"
                    && self.shell("pidof system_server\n").unwrap_or_default() == after
                {
                    ready = true;
                    break;
                }
                thread::sleep(Duration::from_secs(3));
            }
            if !ready {
                return Err(self.fail(
                    "Android не восстановился после рестарта system_server",
                    error,
                ));
            }
            if self
                .shell("pidof ru.big.town.anative\n")
                .unwrap_or_default()
                .is_empty()
            {
                self.adb.shell(&command, Duration::from_secs(25))?;
            }
        }
        Ok(())
    }
    fn ignore(&self, script: &str) {
        if let Err(e) = self.shell(script) {
            self.warning(&e);
        }
    }
    fn warning(&self, e: &Error) {
        let _ = self.adb.events.emit(
            "diagnostic-warning",
            self.adb.step.as_deref(),
            &e.message,
            json!({"detail":e.detail}),
        );
    }
    fn fail(&self, message: &str, detail: impl std::fmt::Display) -> Error {
        Error::new("CLASSIC_STEP_FAILED", message).detail(detail)
    }
    fn root_sequence(&self, checked: bool) -> Result<()> {
        for args in [&["root"][..], &["wait-for-device"][..], &["root"][..]] {
            let r = self.raw(args);
            if checked {
                r?.checked("Не удалось восстановить ADB после загрузки")?;
            } else if let Err(e) = r {
                self.warning(&e);
            }
        }
        Ok(())
    }
    fn execute(&mut self, r: Request) -> Result<()> {
        self.adb.events.emit(
            "operation-started",
            None,
            "План принят",
            json!({"logDirectory":self.operation.dir}),
        )?;
        self.step(
            "preflight",
            "Подготовка файлов релиза",
            |e| {
                e.adb.require_single()?;
                e.local_files(r.action)
            },
        )?;
        self.step(
            "root",
            "Получение системного доступа",
            |e| {
                e.root_sequence(false)?;
                Ok(())
            },
        )?;
        self.step(
            "updater-lock",
            "Блокировка установки и сохранение OTA-логов",
            |e| e.ota_prepare(),
        )?;
        self.ignore(&format!("rm -f {DEVICE_STATE}/lock/owner 2>/dev/null; rmdir {DEVICE_STATE}/lock 2>/dev/null; rmdir {DEVICE_STATE} 2>/dev/null; true\n"));
        if r.action != Action::Remove {
            self.step(
                "permission",
                "Проверка владельца CAN-разрешения",
                |e| e.permission_owner(),
            )?;
        }
        self.step(
            "system",
            "Подготовка системного раздела",
            |e| e.writable(r.action == Action::Remove),
        )?;
        self.ota_lock()?; // remount may have rebooted; retain this desktop operation across boot.
        self.ignore("setprop ctl.stop voyahtune_updater 2>/dev/null || true\n");
        if r.action == Action::Install {
            self.step("backup", "Сохранение файлов перед заменой", |e| e.backup())?;

            self.step(
                "signing-reset",
                "Переустановка при смене подписи",
                |e| e.signing_reset(r.action),
            )?;

            self.step("runtime", "Остановка старых hooks", |e| {
                e.freeze()
            })?;

            self.step(
                "apollo-migration",
                "Отключение старой активации Apollo",
                |e| {
                    e.shell(include_str!(
                        "../../../../Packaging/od/installer/common/apollo-safe-device.sh"
                    ))
                    .map(|_| ())
                },
            )?;

            self.step("files", "Установка файлов релиза", |e| e.recipe_files())?;

            self.step(
                "migration",
                "Миграция старого init.logcat.sh",
                |e| e.migrate_legacy(),
            )?;
            self.step("boot-hooks", "Установка boot-hook", |e| {
                e.boot_hooks()
            })?;

            self.step("native", "Установка Native и разрешений", |e| e.native())?;
            self.step(
                "packages",
                "Установка RestoreMode и настроек",
                |e| e.packages(),
            )?;
            self.step("dns", "Настройка DNS", |e| e.dns_choice(r.dns))?;
            self.step("updater-bootstrap", "Подготовка первого запуска OTA", |e| {
                e.shell("rm -f /data/local/voyahtune-updater/state.json /data/local/voyahtune-updater/.state.json.new /data/local/bin/voyahtune-update.block && sync\n")?;
                Ok(())
            })?;
            self.step(
                "reboot",
                "Перезагрузка автомобиля",
                |e| {
                    e.shell("am force-stop ru.big.town.anative && am force-stop ru.big.town.restoremode\n")?;
                    e.ota_unlock()?;
                    e.raw(&["reboot"])?.checked("ADB не принял перезагрузку")?;
                    e.restart_loader = false;
                    Ok(())
                },
            )?;
            self.step(
                "verify",
                "Проверка Native и готовности OTA",
                |e| {
                    e.wait_boot(true)?;
                    e.native_ready()?;
                    e.updater_ready()?;
                    let service = e.shell("getprop init.svc.voyahtune_updater\n")?;
                    if service != "running" {
                        return Err(e.fail("Root-служба OTA не запустилась", service));
                    }
                    let version: serde_json::Value = serde_json::from_str(
                        &e.shell("/data/local/bin/voyahtune-updater --version\n")?,
                    )?;
                    if version["ipcSchema"] != 1
                        || version["infrastructure"] != e.payload.manifest.infrastructure.as_str()
                    {
                        return Err(e.fail("Несовместимый IPC или инфраструктура updater", version));
                    }
                    if e.payload.manifest.infrastructure
                        == crate::infrastructure::Infrastructure::Pi
                    {
                        e.pi_loader_ready()?;
                    }
                    Ok(())
                },
            )?;
        } else {
            self.step("deactivate", "Отключение Apollo", |e| {
                e.apollo_safe(false)?;
                thread::sleep(Duration::from_secs(3));
                Ok(())
            })?;
            self.step("dns", "Восстановление DNS", |e| {
                e.dns("restore")
            })?;
            self.step(
                "migration",
                "Миграция старого init.logcat.sh",
                |e| e.migrate_legacy(),
            )?;
            self.step(
                "runtime",
                "Остановка и удаление boot-hook",
                |e| e.remove_boot(),
            )?;
            self.step(
                "files",
                "Удаление hooks и временных файлов",
                |e| e.remove_files(),
            )?;
            self.step(
                "settings",
                "Очистка настроек VoyahTune",
                |e| e.shell(c::REMOVE_SETTINGS).map(|_| ()),
            )?;
            self.step("packages", "Удаление приложений", |e| {
                e.ignore("am force-stop ru.big.town.anative\n");
                e.ignore("am force-stop ru.big.town.restoremode\n");
                e.shell(c::REMOVE_PACKAGES)?;
                e.shell(c::REMOVE_SYSTEM)?;
                Ok(())
            })?;
            // The classic remover ends with adb reboot, without postflight inventory.
            self.step(
                "reboot",
                "Перезагрузка автомобиля",
                |e| {
                    e.ota_unlock()?;
                    e.raw(&["reboot"])?.checked("ADB не принял перезагрузку")?;
                    Ok(())
                },
            )?;
        }
        Ok(())
    }
    fn ota_lock(&mut self) -> Result<()> {
        let owner = quote(&format!("desktop:{}", self.operation.id));
        self.shell(&format!(r#"lock=/data/local/voyahtune-install.lock
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
[ ! -L "$lock" ] || exit 1
if mkdir "$lock" 2>/dev/null; then :
else
    old=$(cat "$lock/owner" 2>/dev/null)
    if [ "$old" = {owner} ]; then :
    elif [ "$old" = ota ] && {{ [ "$(cat "$lock/boot" 2>/dev/null)" != "$boot" ] || [ "$(/data/local/bin/voyahtune-updater --repair-status 2>/dev/null)" = repair-required ]; }}; then
        rm -f "$lock/owner" "$lock/boot" && rmdir "$lock" && mkdir "$lock" || exit 1
    else echo 'OTA или другой установщик уже использует ГУ'; exit 1; fi
fi
printf '%s' {owner} > "$lock/owner" && printf '%s' "$boot" > "$lock/boot" && chmod 700 "$lock" && sync
"#))?;
        self.ota_locked = true;
        Ok(())
    }
    fn ota_unlock(&mut self) -> Result<()> {
        if !self.ota_locked {
            return Ok(());
        }
        let owner = quote(&format!("desktop:{}", self.operation.id));
        self.adb.shell(&format!(r#"# release desktop installation lock
lock=/data/local/voyahtune-install.lock
[ ! -L "$lock" ] || exit 1
if [ -d "$lock" ]; then
    [ "$(cat "$lock/owner" 2>/dev/null)" = {owner} ] || {{ echo 'Владелец блокировки изменился'; exit 1; }}
    rm -f "$lock/owner" "$lock/boot" && rmdir "$lock" || exit 1
fi
[ ! -e "$lock" ] && sync
"#), Duration::from_secs(30))?;
        self.ota_locked = false;
        Ok(())
    }
    fn ota_prepare(&mut self) -> Result<()> {
        self.ota_lock()?;
        self.ignore("setprop ctl.stop voyahtune_updater 2>/dev/null || true\n");
        for _ in 0..20 {
            if self.shell("getprop init.svc.voyahtune_updater\n")? != "running" {
                break;
            }
            thread::sleep(Duration::from_millis(250));
        }
        if self.shell("getprop init.svc.voyahtune_updater\n")? == "running" {
            return Err(self.fail(
                "Не удалось остановить OTA-службу",
                "Повторите после завершения OTA",
            ));
        }
        let diagnostics = self.operation.dir.join("ota-diagnostics");
        fs::create_dir_all(&diagnostics)?;
        for name in [
            "state.json",
            "state.corrupt.json",
            "updater.log",
            "updater.log.1",
            "command.log",
        ] {
            let remote = format!("/data/local/voyahtune-updater/{name}");
            if self.shell(&format!(
                "if [ -f {} ]; then echo PRESENT; fi\n",
                quote(&remote)
            ))? == "PRESENT"
            {
                self.adb.pull(&remote, &diagnostics.join(name))?;
            }
        }
        Ok(())
    }
    fn local_files(&self, a: Action) -> Result<()> {
        if a != Action::Remove && self.payload.manifest.removal_only {
            return Err(Error::new(
                "PAYLOAD_REQUIRED",
                "Ресурсы удаления нельзя использовать для установки",
            ));
        }
        let mut names = vec!["dns-helper.sh"];
        if a != Action::Remove {
            names.extend(["dns.apk", "whitelist.xml"]);
        }
        names.push("init.logcat.original.sh");

        self.payload.verify()?;

        for name in names {
            let p = self.payload.file(name)?;
            if !p.is_file() || p.metadata()?.len() == 0 {
                return Err(self.fail("Нет обязательного файла релиза", name));
            }
        }
        if a == Action::Install {
            for name in ["native.apk", "restore_mode.apk"] {
                let p = self.payload.file(name)?;
                if p.metadata()?.len() == 0 {
                    return Err(self.fail("Пустой обязательный APK", name));
                }
            }
        }
        // ydns_prepare_helper's existing pinned RRO check, not a new payload gate.
        if a != Action::Remove
            && payload::sha256(&self.payload.file("dns.apk")?)?
                != "c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d"
        {
            return Err(self.fail(
                "SHA-256 DNS RRO APK не совпадает с зафиксированным",
                "dns.apk",
            ));
        }
        Ok(())
    }
    fn permission_owner(&self) -> Result<()> {
        let dump = self.shell("dumpsys package permissions\n")?;
        if self.has_canbus_conflict(&dump)? {
            return self.resolve_canbus_conflict();
        }
        self.validate_permission_owner(&dump)
    }
    fn hl_service_installed(&self) -> Result<bool> {
        let packages = self.shell("pm list packages --user 0\n")?;
        Ok(packages
            .lines()
            .any(|line| line.trim().strip_prefix("package:") == Some(canbus::PACKAGE)))
    }
    fn has_canbus_conflict(&self, dump: &str) -> Result<bool> {
        Ok(canbus::owner(dump) == Some(Some(canbus::PACKAGE)) || self.hl_service_installed()?)
    }
    fn validate_permission_owner(&self, dump: &str) -> Result<()> {
        if let Some(owner) = canbus::owner(dump) {
            if owner != Some(NATIVE) && !(cfg!(windows) && owner.is_none()) {
                return Err(self.fail(
                    "WRITE_CANBUS принадлежит другому пакету",
                    owner.unwrap_or("Владелец не определён"),
                ));
            }
        }
        Ok(())
    }
    fn resolve_canbus_conflict(&self) -> Result<()> {
        if let Some(consent) = &self.canbus_consent {
            consent.begin();
        }
        if self.canbus_consent.as_ref().and_then(|c| c.decision()) != Some(true) {
            self.adb.events.emit("canbus-conflict", self.adb.step.as_deref(), canbus::NOTICE,
                json!({"package":canbus::PACKAGE,"awaitingConfirmation":self.canbus_consent.is_some()}))?;
        }
        let Some(consent) = &self.canbus_consent else {
            return Err(Error::new("ACTION_REQUIRED", canbus::NOTICE)
                .retry("Подтвердите удаление com.voyah.hl.service в окне установщика."));
        };
        loop {
            if self.cancel.load(Ordering::Relaxed) || consent.decision() == Some(false) {
                return Err(Error::new(
                    "CANCELLED",
                    "Удаление com.voyah.hl.service отменено. Установка не продолжена.",
                )
                .retry(
                    "Повторите установку, когда будете готовы удалить конфликтующее приложение.",
                ));
            }
            if consent.decision() == Some(true) {
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }
        self.adb.events.emit("canbus-removal-started", self.adb.step.as_deref(),
            "Удаление com.voyah.hl.service подтверждено. Сохраняем системную папку перед удалением.", json!({}))?;
        // Back up before remount or uninstall; a failed pull must not remove the app.
        if self.shell("if [ -d /system/priv-app/VoyahHlCTRL ]; then echo PRESENT; fi\n")?
            == "PRESENT"
        {
            let backup = self.operation.dir.join("VoyahHlCTRL");
            self.raw(&["pull", canbus::DIRECTORY, &backup.to_string_lossy()])?
                .checked("Не удалось сохранить VoyahHlCTRL перед удалением")?;
            self.adb.events.emit(
                "canbus-backup",
                self.adb.step.as_deref(),
                "Копия VoyahHlCTRL сохранена на компьютере",
                json!({"path":backup}),
            )?;
        }
        self.writable(false)?;
        // Preparation may reboot the car. Recheck both reasons for removal.
        let dump = self.shell("dumpsys package permissions\n")?;
        if !self.has_canbus_conflict(&dump)? {
            self.validate_permission_owner(&dump)?;
            self.adb.events.emit("canbus-conflict-resolved", self.adb.step.as_deref(),
                "После подготовки раздела com.voyah.hl.service отсутствует и не владеет WRITE_CANBUS. Удаление не требуется.", json!({}))?;
            return Ok(());
        }
        if self.cancel.load(Ordering::Relaxed) {
            return Err(Error::new(
                "CANCELLED",
                "Удаление com.voyah.hl.service отменено",
            ));
        }
        self.ignore("am force-stop com.voyah.hl.service\n");
        // A system package may reject uninstall, or already be uninstalled for user 0.
        // Its system APK and stale permission registration still need removal.
        self.ignore("pm uninstall --user 0 com.voyah.hl.service\n");
        self.shell("rm -rf /system/priv-app/VoyahHlCTRL && rm -rf /data/system/package_cache/*\n")?;
        self.adb.events.emit(
            "canbus-removal-reboot",
            self.adb.step.as_deref(),
            "Перезагрузка для освобождения WRITE_CANBUS. Не отключайте кабель и питание.",
            json!({}),
        )?;
        self.raw(&["reboot"])?
            .checked("ADB не принял перезагрузку после удаления VoyahHlCTRL")?;
        self.wait_boot(true)?;
        let dump = self.shell("dumpsys package permissions\n")?;
        if canbus::owner(&dump) == Some(Some(canbus::PACKAGE)) {
            return Err(self.fail(
                "WRITE_CANBUS не освободилось после удаления VoyahHlCTRL",
                canbus::PACKAGE,
            ));
        }
        if self.hl_service_installed()? {
            return Err(self.fail(
                "com.voyah.hl.service осталось установленным после удаления VoyahHlCTRL",
                "Пакет присутствует у пользователя 0 после перезагрузки",
            ));
        }
        self.validate_permission_owner(&dump)?;
        self.adb.events.emit(
            "canbus-conflict-resolved",
            self.adb.step.as_deref(),
            "Конфликт WRITE_CANBUS устранён. Продолжаем установку VoyahTune.",
            json!({}),
        )?;
        Ok(())
    }
    fn is_writable(&self) -> bool {
        let _ = self.raw(&["remount"]);
        self.ignore(c::MOUNT);
        self.shell(c::RW_TEST).is_ok_and(|s| s == "RW")
    }
    fn writable(&self, remove: bool) -> Result<()> {
        let _ = self.raw(&["disable-verity"]);
        if remove {
            let _ = self.raw(&["remount"]);
            self.ignore(c::MOUNT);
            if self.shell(c::REMOVE_RW_TEST).unwrap_or_default() != "RW" {
                return Err(self.fail(
                    "/system недоступен для записи",
                    "Повторите подготовку системного раздела старым install-процессом",
                ));
            }
        } else {
            if !self.is_writable() {
                let _ = self.raw(&["reboot"]);
                self.wait_boot(false)?;
                thread::sleep(Duration::from_secs(3));
                self.root_sequence(false)?;
            }
            if !self.is_writable() {
                return Err(self.fail(
                    "/system остался доступен только для чтения",
                    "disable-verity и remount не сделали раздел записываемым",
                ));
            }
        }
        Ok(())
    }
    fn wait_boot(&self, checked: bool) -> Result<()> {
        let wait = self.raw(&["wait-for-device"]);
        if checked {
            wait?.checked("ADB не вернулся после перезагрузки")?;
        }
        let mut booted = false;
        for _ in 0..60 {
            if self
                .shell("getprop sys.boot_completed\n")
                .unwrap_or_default()
                == "1"
            {
                booted = true;
                break;
            }
            thread::sleep(Duration::from_secs(5));
        }
        if checked {
            if !booted {
                return Err(self.fail("Android не завершил загрузку", "sys.boot_completed != 1"));
            }
            self.root_sequence(true)?;
        }
        Ok(())
    }
    fn backup_dir(&self) -> PathBuf {
        self.operation.dir.parent().unwrap().join("backup")
    }
    fn backup(&mut self) -> Result<()> {
        self.installed_runtime_hashes = inventory::signed_runtime_hashes(&self.adb);
        let dir = self.backup_dir();
        let made = fs::create_dir_all(&dir);

        made?;

        let mut paths = vec![(
            payload::WHITELIST.to_owned(),
            "privapp-permissions-ru.big.town.anative.xml".to_owned(),
        )];

        let mut runtime_backup = vec![];
        for name in [
            "loaderFrida",
            "injects.json",
            "clusternavi.js",
            "phone-num.js",
            "load.bin",
            "steeringwheelkeys.js",
            "launcherdock.js",
            "multidisplay.js",
            "vd_bypass.js",
            "frida-inject",
        ] {
            runtime_backup.push((format!("/data/local/bin/{name}"), name.into()));
        }
        runtime_backup.extend(paths);
        paths = runtime_backup;

        for (remote, name) in paths {
            let dst = dir.join(&name);

            if dst.exists() {
                if dst.is_file() && dst.metadata()?.len() > 0 {
                    continue;
                }
                return Err(self.fail(
                    "Существующий backup пуст или не является файлом",
                    dst.display(),
                ));
            }
            let q = quote(&remote);
            match self.shell(&format!("if [ -f {q} ]; then echo PRESENT; elif [ -e {q} ]; then echo ERROR; else echo ABSENT; fi\n"))?.as_str(){
                "ABSENT"=>continue,"PRESENT"=>{},other=>return Err(self.fail("Не удалось сохранить существующий файл",format!("{remote}: {other}"))),}
            if self
                .installed_runtime_hashes
                .iter()
                .any(|(artifact, _)| artifact == &name)
                && inventory::file_hash(&self.adb, &remote)?.is_some_and(|hash| {
                    self.installed_runtime_hashes
                        .contains(&(name.clone(), hash))
                })
            {
                continue;
            }
            let tmp = dir.join(format!("{name}.new"));
            let _ = fs::remove_file(&tmp);
            self.adb.pull(&remote, &tmp)?;
            if tmp.metadata()?.len() == 0 {
                return Err(self.fail("Резервная копия пуста", remote));
            }
            fs::rename(tmp, dst)?;
        }
        Ok(())
    }
    fn apollo_safe(&self, install: bool) -> Result<()> {
        let keys = if install {
            vec![
                "open_voyah_apollo_legacy_hook_enabled",
                "open_voyah_apollo_master",
                "open_voyah_apollo_profile_supported",
                "open_voyah_apollo_profile_heartbeat",
            ]
        } else {
            vec![
                "open_voyah_apollo_master",
                "open_voyah_apollo_legacy_hook_enabled",
            ]
        };
        for key in keys {
            self.shell(&format!("settings put global {key} 0\n"))?;
            if self
                .shell(&format!("settings get global {key}\n"))
                .unwrap_or_default()
                != "0"
            {
                return Err(self.fail("Apollo не подтвердил отключение", key));
            }
        }
        Ok(())
    }
    fn freeze(&mut self) -> Result<()> {
        self.restart_loader = true;
        // An absent init service is already stopped, not a failure.
        if let Err(error) = self.stop_runtime_for_update() {
            self.warning(&error);
        }
        self.apollo_safe(true)?;
        self.ignore("am force-stop com.qinggan.app.vehiclesetting\n");

        self.ignore(c::APOLLO_FILES);

        for key in [
            "open_voyah_apollo_legacy_hook_enabled",
            "open_voyah_apollo_master",
            "open_voyah_apollo_asc",
            "open_voyah_apollo_sdb",
            "open_voyah_apollo_profile_supported",
            "open_voyah_apollo_profile_heartbeat",
        ] {
            self.ignore(&format!("settings delete global {key}\n"));
        }

        Ok(())
    }
    fn push_file(
        &self,
        local: &Path,
        stage: &str,
        target: &str,
        mode: u32,
        system: bool,
    ) -> Result<()> {
        let result = (|| {
            self.adb.push(local, stage)?;
            let q = quote(stage);
            let t = quote(target);
            self.shell(&format!(
                "chown 0:0 {q} && chmod {mode:o} {q} && {}mv -f {q} {t} && {}test -f {t}\n",
                if system {
                    format!("restorecon {q} && ")
                } else {
                    String::new()
                },
                if system {
                    format!("restorecon {t} && sync && ")
                } else {
                    String::new()
                }
            ))?;
            Ok(())
        })();
        if result.is_err() {
            self.ignore(&format!("rm -f {}\n", quote(stage)));
        }
        result
    }
    fn recipe_files(&self) -> Result<()> {
        self.shell(c::PREPARE_DATA_DIRECTORIES)?;
        let recipe = &self.payload.manifest.recipe;
        for directory in recipe.directories.iter() {
            let p = quote(&directory.path);
            self.shell(&format!(
                "mkdir -p {p} && chown 0:0 {p} && chmod {:o} {p}\n",
                directory.mode
            ))?;
        }
        for file in recipe.files.iter().filter(|f| {
            f.phase == crate::recipe::Phase::Files
                && !["native.apk", "whitelist.xml"].contains(&f.artifact.as_str())
        }) {
            if file.artifact == "voyahtune-updater.apk" {
                self.shell("mkdir -p /system/priv-app/VoyahTuneUpdater && chown 0:0 /system/priv-app/VoyahTuneUpdater && chmod 755 /system/priv-app/VoyahTuneUpdater\n")?;
            }
            let stage = format!("{}.voyahtune.new", file.destination);
            self.push_file(
                &self.payload.file(&file.artifact)?,
                &stage,
                &file.destination,
                file.mode,
                file.destination.starts_with("/system/"),
            )?;
        }
        for attr in recipe.attributes.iter() {
            let p = quote(&attr.path);
            self.shell(&format!("chown 0:0 {p} && chmod {:o} {p}\n", attr.mode))?;
        }

        if self.payload.manifest.infrastructure == crate::infrastructure::Infrastructure::Od {
            self.shell(c::APP_CLIENT_MIGRATION)?;
        }

        // Retired exact paths are cumulative. Never delete an active target.
        for path in &recipe.remove_files {
            if !recipe.files.iter().any(|f| f.destination == *path) {
                self.shell(&format!("rm -f {}\n", quote(path)))?;
            }
        }
        Ok(())
    }
    fn native(&self) -> Result<()> {
        self.shell("mkdir -p /system/priv-app/Native && chmod 755 /system/priv-app/Native\n")?;
        self.push_file(
            &self.payload.file("native.apk")?,
            "/system/priv-app/.Native.apk.voyahtune.new",
            NATIVE_PATH,
            0o644,
            true,
        )?;

        self.ignore("ls -all /system/priv-app/Native\n");

        self.shell("mkdir -p /system/etc/permissions\n")?;
        self.push_file(
            &self.payload.file("whitelist.xml")?,
            "/system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new",
            payload::WHITELIST,
            0o644,
            true,
        )
    }
    fn packages(&self) -> Result<()> {
        if self
            .shell("getprop persist.app.feature.leavecar\n")
            .unwrap_or_default()
            != "true"
        {
            self.ignore("setprop persist.app.feature.leavecar true\n");
        }

        for key in [
            "enable_freeform_support",
            "force_resizable_activities",
            "hidden_api_policy",
        ] {
            self.ignore(&format!("settings put global {key} 1\n"));
        }

        self.install_restore(&self.payload.file("restore_mode.apk")?)?;
        for package in self
            .payload
            .manifest
            .recipe
            .packages
            .iter()
            .filter(|p| p.package == payload::RUNYN)
        {
            let path = self.payload.file(&package.artifact)?;
            payload::verified_signers(&path)?;
            if release_core::apk_identity::read(&path)?.0 != payload::RUNYN {
                return Err(self.fail("Неверный package ID RunYN", &package.artifact));
            }
            self.raw(&[
                "install",
                "-r",
                "-g",
                path.to_str()
                    .ok_or_else(|| self.fail("Некорректный путь RunYN", path.display()))?,
            ])?
            .checked("RunYN не установлен; существующие данные сохранены")?;
        }
        Ok(())
    }
    fn verify_active_apk(&self, package: &str, artifact: &str, repair: Option<&str>) -> Result<()> {
        let expected = &self.payload.artifact(artifact)?.sha256;
        self.postflight_shell(&active_apk_script(package, expected, repair))?;
        Ok(())
    }
    fn updater_ready(&self) -> Result<()> {
        if self.payload.manifest.infrastructure == crate::infrastructure::Infrastructure::Od {
            return self.updater_ready_od();
        }
        // ROM 650 can register a new system APK without its DE directory.
        // PackageManager must create app data with the correct UID and context.
        const READY: &str = r#"
            if pm list packages --user 0 2>/dev/null | grep -qx 'package:ru.big.town.updater' \
                    && pm path ru.big.town.updater 2>/dev/null | grep -q '^package:' \
                    && [ -d /data/user/0/ru.big.town.updater ] \
                    && [ -d /data/user_de/0/ru.big.town.updater ]; then
                echo READY
            else
                echo BROKEN
            fi
        "#;
        if self.shell(READY)? != "READY" {
            self.postflight_shell("cmd package install-existing --user 0 ru.big.town.updater\n")?;
        }
        if self.shell(READY)? != "READY" {
            self.postflight_shell("am force-stop ru.big.town.updater && pm uninstall -k --user 0 ru.big.town.updater\n")?;
            self.postflight_shell("cmd package install-existing --user 0 ru.big.town.updater\n")?;
        }
        if self.shell(READY)? != "READY" {
            return Err(self.fail(
                "PackageManager не создал CE/DE Updater",
                "ru.big.town.updater",
            ));
        }
        self.verify_active_apk(
            "ru.big.town.updater",
            "voyahtune-updater.apk",
            Some("/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk"),
        )?;
        Ok(())
    }
    fn updater_ready_od(&self) -> Result<()> {
        let probe = format!("{UPDATER_PROBE}\nprobe_updater\n");
        let deadline = Instant::now() + Duration::from_secs(90);
        let mut previous = String::new();
        let mut repairs = 0;
        let mut warned = false;
        for _ in 0..30 {
            if Instant::now() >= deadline {
                break;
            }
            let state = self.shell(&probe)?;
            if state == "UNAVAILABLE" {
                previous.clear();
                if !warned {
                    self.warning(&self.fail(
                        "Ожидание системных служб Android перед проверкой Updater",
                        "PackageManager или ActivityManager недоступен; восстановление пакета отложено",
                    ));
                    warned = true;
                }
            } else if state != previous {
                previous = state;
            } else if state.starts_with("READY:") {
                // Even a successful probe can race a later system_server restart.
                match self.verify_active_apk(
                    "ru.big.town.updater",
                    "voyahtune-updater.apk",
                    Some("/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk"),
                ) {
                    Ok(()) => return Ok(()),
                    Err(error) => {
                        let after = self.shell(&probe)?;
                        if after != "UNAVAILABLE" && after == state {
                            return Err(error);
                        }
                        self.warning(&error);
                        previous.clear();
                    }
                }
            } else if state.starts_with("BROKEN:") {
                let command = match repairs {
                    0 => "cmd package install-existing --user 0 ru.big.town.updater",
                    1 => "am force-stop ru.big.town.updater && pm uninstall -k --user 0 ru.big.town.updater && cmd package install-existing --user 0 ru.big.town.updater",
                    _ => return Err(self.fail("PackageManager не создал CE/DE Updater", state)),
                };
                // Recheck inside the lock immediately before any mutation. On a
                // service restart, return to diagnosis instead of blindly retrying it.
                let guarded = format!(
                    "{UPDATER_PROBE}\nexpected={}\n\
                     [ \"$(probe_updater)\" = \"$expected\" ] || {{ echo RECHECK; exit 0; }}\n\
                     {command}\nstatus=$?\n\
                     if [ \"$status\" -ne 0 ]; then\n\
                         after=$(probe_updater)\n\
                         if [ \"$after\" = UNAVAILABLE ] || [ \"${{after##*:}}\" != \"${{expected##*:}}\" ]; then\n\
                             echo RECHECK; exit 0\n\
                         fi\n\
                     fi\nexit \"$status\"\n",
                    quote(&state),
                );
                if self.postflight_shell(&guarded)?.lines().last() != Some("RECHECK") {
                    repairs += 1;
                }
                previous.clear();
            } else {
                return Err(self.fail("Неизвестный результат проверки Updater", state));
            }
            thread::sleep(Duration::from_secs(2));
        }
        Err(self.fail(
            "Android не восстановился для проверки Updater",
            "Системные службы или регистрация пакета нестабильны; повторите установку после загрузки ГУ",
        ))
    }
    fn pi_loader_ready(&self) -> Result<()> {
        let mut last = Error::new("PI_LOADER_NOT_READY", "PI watchdog ещё не запустился");
        for _ in 0..20 {
            let result = (|| -> Result<()> {
                let data = self.shell("cat /data/local/tmp/voyahtune-pi-loader-status.json\n")?;
                let status: release_core::pi_health::Status = serde_json::from_str(&data)?;
                let stat = self.shell(&format!("cat /proc/{}/stat\n", status.loader_pid))?;
                let start = stat
                    .rsplit_once(") ")
                    .and_then(|(_, rest)| rest.split_whitespace().nth(19))
                    .ok_or_else(|| {
                        Error::new("PI_LOADER_IDENTITY", "Нет времени старта PI loader")
                    })?;
                let uptime = self
                    .shell("cat /proc/uptime\n")?
                    .split_whitespace()
                    .next()
                    .and_then(|s| s.parse::<f64>().ok())
                    .ok_or_else(|| Error::new("PI_LOADER_IDENTITY", "Нет uptime"))?
                    as u64;
                status.validate(
                    &self.shell("cat /proc/sys/kernel/random/boot_id\n")?,
                    uptime,
                    start,
                    self.shell(&format!("cat /proc/{}/cmdline\n", status.loader_pid))?
                        .as_bytes(),
                    &self.shell("getprop init.svc.voyahtune_load\n")?,
                )
            })();
            match result {
                Ok(()) => return Ok(()),
                Err(error) => last = error,
            }
            thread::sleep(Duration::from_secs(1));
        }
        Err(last)
    }
    fn native_ready(&self) -> Result<()> {
        if self.shell(c::NATIVE_READY).unwrap_or_default() != "READY" {
            self.postflight_shell(
                "pm uninstall -k --user 0 ru.big.town.anative >/dev/null 2>&1 || true\n",
            )?;
            self.postflight_shell("cmd package install-existing --user 0 ru.big.town.anative\n")?;
        }
        if self.shell(c::NATIVE_READY).unwrap_or_default() != "READY" {
            return Err(self.fail("PackageManager не создал CE/DE Native", NATIVE));
        }
        self.verify_active_apk(NATIVE, "native.apk", Some(NATIVE_PATH))?;
        self.verify_active_apk(RESTORE, "restore_mode.apk", None)?;
        if self
            .payload
            .manifest
            .recipe
            .packages
            .iter()
            .any(|p| p.package == payload::RUNYN)
        {
            self.verify_active_apk(payload::RUNYN, "runyn.apk", None)?;
        }
        self.native_broadcast()?;
        for _ in 0..20 {
            if !self
                .shell("pidof ru.big.town.anative\n")
                .unwrap_or_default()
                .is_empty()
            {
                return Ok(());
            }
            thread::sleep(Duration::from_secs(1));
        }
        Err(self.fail(
            "Native не запустился после восстановления package data",
            NATIVE,
        ))
    }
    fn legacy_command(&self, s: &str) -> String {
        s.replace("$LEGACY_INIT_DEVICE", LEGACY_INIT)
            .replace("$LEGACY_INIT_MARKER", LEGACY_MARKER)
    }
    fn legacy_state(&self) -> Result<String> {
        self.shell(&self.legacy_command(c::LEGACY_STATE))
    }
    fn syntax_check(&self, p: &Path) -> bool {
        // .bat validators use marker checks; .sh validators also run local sh -n.
        #[cfg(windows)]
        {
            let _ = p;
            true
        }
        #[cfg(not(windows))]
        {
            std::process::Command::new("/bin/sh")
                .arg("-n")
                .arg(p)
                .output()
                .is_ok_and(|o| o.status.success())
        }
    }
    fn valid_original(&self, p: &Path) -> bool {
        fs::read(p).is_ok_and(|bytes| {
            let s = String::from_utf8_lossy(&bytes);
            s.lines()
                .next()
                .is_some_and(|l| l.trim_end_matches('\r') == "#!/system/bin/sh")
                && s.contains("/system/bin/logcat")
                && !s.contains(LEGACY_MARKER)
        }) && self.syntax_check(p)
    }
    fn rollback_legacy(&mut self) -> Result<()> {
        if !self.legacy_migrated {
            return Ok(());
        }
        let p = self.backup_dir().join("init.logcat.voyahtune-legacy.sh");
        if !fs::read(&p).is_ok_and(|s| String::from_utf8_lossy(&s).contains(LEGACY_MARKER))
            || !self.syntax_check(&p)
        {
            return Err(self.fail("Rollback-копия init.logcat.sh повреждена", p.display()));
        }
        self.adb
            .push(&p, &format!("{LEGACY_INIT}.voyahtune.rollback"))?;
        self.shell(&self.legacy_command(c::LEGACY_ROLLBACK))?;
        if self.legacy_state()? != "LEGACY" {
            return Err(self.fail("Rollback init.logcat.sh не подтверждён", LEGACY_INIT));
        }
        self.legacy_migrated = false;
        Ok(())
    }
    fn migrate_legacy(&mut self) -> Result<()> {
        match self.legacy_state()?.as_str() {
            "CLEAN" | "MISSING" => return Ok(()),
            "LEGACY" => {}
            s => return Err(self.fail("Не удалось проверить init.logcat.sh", s)),
        }
        let dir = self.backup_dir();
        fs::create_dir_all(&dir)?;
        let previous = dir.join("init.logcat.voyahtune-legacy.sh");
        let temp = dir.join("init.logcat.voyahtune-legacy.sh.new");
        let _ = fs::remove_file(&temp);
        self.adb.pull(LEGACY_INIT, &temp)?;
        if !fs::read(&temp).is_ok_and(|s| String::from_utf8_lossy(&s).contains(LEGACY_MARKER))
            || !self.syntax_check(&temp)
        {
            let _ = fs::remove_file(&temp);
            return Err(self.fail(
                "Копия legacy init.logcat.sh не прошла проверку",
                LEGACY_INIT,
            ));
        }
        fs::rename(temp, previous)?;
        let backup = dir.join("init.logcat.sh");
        let source = if backup.is_file() && self.valid_original(&backup) {
            backup
        } else {
            self.payload.file("init.logcat.original.sh")?
        };
        if !self.valid_original(&source) {
            return Err(self.fail(
                "Не прошёл проверку исходный init.logcat.sh",
                source.display(),
            ));
        }
        let stage = format!("{LEGACY_INIT}.voyahtune.new");
        if let Err(e) = self.adb.push(&source, &stage) {
            self.ignore(&format!("rm -f {stage}\n"));
            return Err(e);
        }
        self.legacy_migrated = true;
        let result = (|| {
            self.shell(&self.legacy_command(c::LEGACY_PUBLISH))?;
            if self.legacy_state()? != "CLEAN" {
                return Err(self.fail("Legacy-marker остался после восстановления", LEGACY_INIT));
            }
            Ok(())
        })();
        if result.is_err() {
            self.ignore(&format!("rm -f {stage}\n"));
            if let Err(e) = self.rollback_legacy() {
                self.warning(&e);
            }
        }
        result
    }
    fn boot_rollback(&self) -> Result<()> {
        let result = self.shell(c::BOOT_ROLLBACK);
        self.ignore(c::BOOT_CLEAN_STAGE);
        if result.is_ok() {
            self.ignore(c::BOOT_CLEAN_SNAPSHOT);
        }
        result.map(|_| ())
    }
    fn pi_boot_check(&self, staged: bool) -> Result<String> {
        let mut script = String::new();
        for (artifact, staged_path, installed_path) in [
            (
                "voyahtune.load.sh",
                "/system/etc/.voyahtune.load.sh.new",
                "/system/etc/init.voyahtune.load.sh",
            ),
            (
                "voyahtune.load.rc",
                "/system/etc/.voyahtune.load.rc.new",
                "/system/etc/init/voyahtune.load.rc",
            ),
        ] {
            let path = quote(if staged { staged_path } else { installed_path });
            let hash = &self.payload.artifact(artifact)?.sha256;
            script.push_str(&format!(
                "digest=$(sha256sum {path}) || exit 1\n[ \"${{digest%% *}}\" = {} ] || exit 1\n",
                quote(hash)
            ));
        }
        Ok(script)
    }
    fn boot_prepare(&self) -> Result<()> {
        if self.payload.manifest.infrastructure == crate::infrastructure::Infrastructure::Od {
            self.shell(c::BOOT_PREPARE)?;
        } else {
            let mut command = self.pi_boot_check(true)?;
            command.push_str("chown 0:0 /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && chmod 755 /system/etc/.voyahtune.load.sh.new && chmod 644 /system/etc/.voyahtune.load.rc.new && restorecon /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && sync\n");
            self.shell(&command)?;
        }
        Ok(())
    }
    fn boot_ready(&self) -> Result<String> {
        if self.payload.manifest.infrastructure == crate::infrastructure::Infrastructure::Od {
            return self.shell(c::BOOT_READY);
        }
        self.shell(
            &(self.pi_boot_check(false)?
                + "test -x /system/etc/init.voyahtune.load.sh && echo READY\n"),
        )
    }
    fn boot_transaction(&self) -> Result<()> {
        for name in ["voyahtune.load.rc", "voyahtune.load.sh"] {
            self.payload.file(name)?;
        }
        self.shell("mkdir -p /system/etc/init\n")?;
        self.ignore(c::BOOT_CLEAN_STAGE);
        let prepare = (|| {
            self.adb.push(
                &self.payload.file("voyahtune.load.sh")?,
                "/system/etc/.voyahtune.load.sh.new",
            )?;
            self.adb.push(
                &self.payload.file("voyahtune.load.rc")?,
                "/system/etc/.voyahtune.load.rc.new",
            )?;
            self.boot_prepare()?;
            Ok(())
        })();
        if prepare.is_err() {
            self.ignore(c::BOOT_CLEAN_STAGE);
            return prepare;
        }
        let snapshot = self
            .shell(c::BOOT_CLEAN_SNAPSHOT)
            .and_then(|_| self.shell(c::BOOT_SNAPSHOT));
        if let Err(e) = snapshot {
            self.ignore(c::BOOT_CLEAN_STAGE);
            self.ignore(c::BOOT_CLEAN_SNAPSHOT);
            return Err(e);
        }
        let publish = (|| {
            self.shell(c::BOOT_PUBLISH)?;
            if self.boot_ready()? != "READY" {
                return Err(self.fail("Проверка boot-hook не пройдена", "BOOT_HOOK_STATE != READY"));
            }
            Ok(())
        })();
        if let Err(e) = publish {
            if let Err(rollback) = self.boot_rollback() {
                return Err(Error::new(
                    "BOOT_ROLLBACK_FAILED",
                    "Не удалось завершить boot-hook и его rollback",
                )
                .detail(format!("{e}\n{rollback}")));
            }
            return Err(e);
        }
        self.ignore(c::BOOT_REMOVE_OLD);
        self.ignore(c::BOOT_CLEAN_SNAPSHOT);
        Ok(())
    }
    fn boot_hooks(&mut self) -> Result<()> {
        let result = self.boot_transaction();
        if let Err(e) = &result {
            if e.code != "BOOT_ROLLBACK_FAILED"
                && self.shell(c::BOOT_STATE).is_ok_and(|s| s == "ABSENT")
            {
                if let Err(rollback) = self.rollback_legacy() {
                    self.warning(&rollback);
                }
            }
        } else {
            self.legacy_migrated = false;
        }
        result
    }
    fn stop_runtime_for_update(&self) -> Result<()> {
        self.stop_service()?;
        self.shell(STOP_LOADER)?;
        self.shell("rm -f /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyah_load.v2.lock && rm -rf /data/local/tmp/voyah_load.lock\n")?;
        Ok(())
    }
    fn stop_service(&self) -> Result<()> {
        let state = self.shell("getprop init.svc.voyahtune_load\n")?;
        if state.is_empty() || state == "stopped" {
            return Ok(());
        }
        self.shell("setprop ctl.stop voyahtune_load\n")?;
        for _ in 0..10 {
            let state = self.shell("getprop init.svc.voyahtune_load\n")?;
            if state.is_empty() || state == "stopped" {
                return Ok(());
            }
            thread::sleep(Duration::from_secs(1));
        }
        Err(self.fail("voyahtune_load не остановлен", "init.svc.voyahtune_load"))
    }
    fn remove_boot(&mut self) -> Result<()> {
        if let Err(e) = self.stop_service() {
            if let Err(rollback) = self.rollback_legacy() {
                self.warning(&rollback);
            }
            return Err(e);
        }
        self.shell(c::REMOVE_BOOT)?;
        self.legacy_migrated = false;
        self.ignore(c::REMOVE_TRANSACTIONS);
        Ok(())
    }
    fn remove_files(&mut self) -> Result<()> {
        self.installed_runtime_hashes = inventory::signed_runtime_hashes(&self.adb);
        self.ignore(STOP_LOADER);
        self.ignore(
            "rm -f /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyah_load.v2.lock\n",
        );
        self.ignore("rm -rf /data/local/tmp/voyah_load.lock\n");
        self.ignore(c::REMOVE_PROCESSES);
        self.ignore("am force-stop com.qinggan.app.vehiclesetting\n");
        self.ignore("am force-stop com.qinggan.app.qgime\n");
        self.ignore(c::STOP_FULLSCREEN);
        for package in [
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexmaps",
            "com.yango.maps.android",
        ] {
            self.ignore(&format!("am force-stop {}\n", quote(package)));
        }
        // The classic remover restores host backups when present and otherwise
        // removes these generic files. No ownership/hash database is consulted.
        self.restore_host_file("load.bin");
        self.restore_host_file("loaderFrida");
        for command in [
            c::REMOVE_EARLY_VD,
            c::REMOVE_EARLY_HOOKS,
            c::REMOVE_EARLY_APOLLO,
            c::REMOVE_EARLY_KEYBOARD,
            c::REMOVE_EARLY_FULLSCREEN,
            c::REMOVE_EARLY_MANIFEST,
        ] {
            self.ignore(command);
        }
        self.restore_host_file("frida-inject");
        self.shell(c::REMOVE_FILES)?;
        self.shell("test ! -e /data/local/bin/voyahtune-hook-manifest.json && test ! -e /data/local/tmp/voyahtune-hook-status.v1\n")?;
        self.shell(c::REMOVE_CLIENT_CHECK)?;
        self.ignore(
            "am force-stop ru.big.town.updater; pm uninstall --user 0 ru.big.town.updater\n",
        );
        self.shell("rm -f /data/local/bin/voyahtune-update.block && rm -rf /data/local/voyahtune-updater /system/priv-app/VoyahTuneUpdater\n")?;

        for path in self.payload.manifest.recipe.cleanup_files() {
            // The exact loader path was already removed or restored from a foreign
            // host backup above. Do not erase that restored foreign file again.
            if path == "/data/local/bin/loaderFrida" {
                continue;
            }
            self.shell(&format!("rm -f {}\n", quote(&path)))?;
        }
        for path in self
            .payload
            .manifest
            .recipe
            .remove_directories
            .iter()
            .chain(
                self.payload
                    .manifest
                    .recipe
                    .directories
                    .iter()
                    .map(|d| &d.path),
            )
        {
            self.shell(&format!("rm -rf {}\n", quote(path)))?;
        }

        Ok(())
    }
    fn restore_host_file(&self, name: &str) {
        let backup = self.backup_dir().join(name);
        let target = format!("/data/local/bin/{name}");
        if backup.is_file() {
            if payload::sha256(&backup).is_ok_and(|hash| {
                self.payload.artifact(name).is_ok_and(|a| a.sha256 == hash)
                    || self
                        .installed_runtime_hashes
                        .contains(&(name.to_owned(), hash))
            }) {
                self.ignore(&format!("rm -f {target}\n"));
                return;
            }
            let _ = self.adb.push(&backup, &target);
        } else {
            self.ignore(&format!("rm -f {target}\n"));
        }
    }
    fn dns(&self, action: &str) -> Result<()> {
        self.adb.push(
            &self.payload.file("dns-helper.sh")?,
            "/data/local/tmp/open_voyah_dns_overlay.sh",
        )?;
        self.shell("chmod 0700 /data/local/tmp/open_voyah_dns_overlay.sh\n")?;
        if action == "install" {
            let _ = self.raw(&["remount"]);
            self.adb.push(
                &self.payload.file("dns.apk")?,
                "/data/local/tmp/open_voyah_yandex_dns.apk",
            )?;
            self.shell("chmod 0600 /data/local/tmp/open_voyah_yandex_dns.apk\n")?;
        } else if action != "status" {
            let _ = self.raw(&["remount"]);
        }
        let result = self.shell(&format!(
            "sh /data/local/tmp/open_voyah_dns_overlay.sh {}\n",
            quote(action)
        ));
        if action == "install" {
            self.ignore("rm -f /data/local/tmp/open_voyah_yandex_dns.apk\n");
        }
        result.map(|_| ())
    }
    fn dns_choice(&self, choice: Dns) -> Result<()> {
        self.adb.push(
            &self.payload.file("dns-helper.sh")?,
            "/data/local/tmp/open_voyah_dns_overlay.sh",
        )?;
        self.shell("chmod 0700 /data/local/tmp/open_voyah_dns_overlay.sh\n")?;
        let state = self.shell("sh /data/local/tmp/open_voyah_dns_overlay.sh status\n")?;
        match state.as_str() {
            "external" | "broken" => Ok(()),
            "on" | "off" => match choice {
                Dns::Keep => Ok(()),
                Dns::On => self.dns("install"),
                Dns::Off => self.dns("disable"),
            },
            _ => Err(self.fail("DNS helper вернул неизвестное состояние", state)),
        }
    }
    fn signing_reset(&self, action: Action) -> Result<()> {
        // Explicitly requested extension. Unknown metadata never blocks install;
        // removal never inspects signatures. Android remains authoritative.
        let inventory = inventory::diagnose(&self.adb, &self.payload, action);
        let resets = plans::signature_resets(&inventory, &self.payload, action).unwrap_or_default();
        if resets.iter().any(|s| s == RESTORE) {
            self.uninstall_restore()?;
        }
        if resets.iter().any(|s| s == NATIVE) {
            self.adb.events.emit(
                "package-reset",
                self.adb.step.as_deref(),
                "Другой ключ Native: удаляем приложение с данными",
                json!({"package":NATIVE}),
            )?;
            self.ignore("pm uninstall ru.big.town.anative\n");
            self.ignore("pm uninstall --user 0 ru.big.town.anative\n");
            self.shell("rm -f /system/priv-app/Native/Native.apk && sync\n")?;
            self.raw(&["reboot"])?
                .checked("Не удалось перезагрузить автомобиль после смены ключа")?;
            self.wait_boot(true)?;
            self.writable(false)?;
        }
        Ok(())
    }
    fn uninstall_restore(&self) -> Result<()> {
        self.adb.events.emit(
            "package-reset",
            self.adb.step.as_deref(),
            "Другой ключ RestoreMode: удаляем приложение с данными",
            json!({"package":RESTORE}),
        )?;
        // Same pm-path condition as the classic remover; no signature gate.
        self.shell("pm uninstall ru.big.town.restoremode >/dev/null 2>&1 || true\nif pm path ru.big.town.restoremode 2>/dev/null | grep -q '^package:'; then exit 1; fi\n")?;
        Ok(())
    }
    fn install_restore(&self, p: &Path) -> Result<()> {
        let s = p
            .to_str()
            .ok_or_else(|| self.fail("Не удалось прочитать путь APK", p.display()))?;
        let mut out = self.raw(&["install", "-r", "-g", s])?;
        if out.code != 0 && signature_update_rejected(&format!("{}\n{}", out.stdout, out.stderr)) {
            self.uninstall_restore()?;
            out = self.raw(&["install", "-r", "-g", s])?;
        }
        out.checked("RestoreMode не установлен")?;
        Ok(())
    }
}
fn signature_update_rejected(output: &str) -> bool {
    output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        && output.to_ascii_lowercase().contains("signature")
}
pub fn default_adb(bundle: &Path) -> PathBuf {
    bundle
        .join("adb")
        .join(if cfg!(windows) { "adb.exe" } else { "adb" })
}

#[cfg(all(test, unix))]
mod runtime_stop_tests {
    use super::*;
    use std::{os::unix::fs::PermissionsExt, process::Command};

    #[test]
    fn active_apk_repair_preserves_data_and_rejects_false_success() {
        // The fake PackageManager intentionally ignores versionName: both APKs have
        // the same release number, as in the USB regression after an OTA update.
        for (mode, repair, success, installs) in [
            ("current", true, true, 0),
            ("stale", true, true, 1),
            ("false-success", true, false, 1),
            ("failure", true, false, 1),
            ("ambiguous", false, false, 0),
            ("stale", false, false, 0),
            ("bad-source", true, false, 0),
        ] {
            let dir = tempfile::tempdir().unwrap();
            let source = dir.path().join("system.apk");
            let active = dir.path().join("active.apk");
            let calls = dir.path().join("calls");
            fs::write(
                &source,
                if mode == "bad-source" {
                    b"wrong"
                } else {
                    b"fresh"
                },
            )
            .unwrap();
            fs::write(
                &active,
                if mode == "current" {
                    b"fresh"
                } else {
                    b"older"
                },
            )
            .unwrap();
            let expected_file = dir.path().join("expected.apk");
            fs::write(&expected_file, b"fresh").unwrap();
            let expected = payload::sha256(&expected_file).unwrap();
            let mock = r#"
sha256sum() { shasum -a 256 "$@"; }
pm() {
    case "$1" in
        path)
            printf 'package:%s\n' "$ACTIVE"
            [ "$MODE" != ambiguous ] || printf 'package:/another.apk\n'
            return 0 ;;
        install)
            [ "$*" = "install -r --user 0 $SOURCE" ] || exit 91
            echo install >> "$CALLS"
            [ "$MODE" != failure ] || return 1
            [ "$MODE" = false-success ] || cp "$SOURCE" "$ACTIVE"
            echo Success ;;
        *) exit 92 ;;
    esac
}
"#;
            let script = active_apk_script(
                NATIVE,
                &expected,
                if repair {
                    Some(source.to_str().unwrap())
                } else {
                    None
                },
            );
            let out = Command::new("sh")
                .args(["-c", &format!("{mock}\n{script}")])
                .env("MODE", mode)
                .env("SOURCE", &source)
                .env("ACTIVE", &active)
                .env("CALLS", &calls)
                .output()
                .unwrap();
            assert_eq!(out.status.success(), success, "{mode}: {:?}", out);
            assert_eq!(
                fs::read_to_string(calls)
                    .unwrap_or_default()
                    .lines()
                    .count(),
                installs,
                "{mode}"
            );
        }
    }

    #[test]
    fn absent_loader_is_success_but_real_pkill_errors_are_preserved() {
        for code in [0, 1, 2, 127] {
            let output = Command::new("sh")
                .args([
                    "-c",
                    &format!("pkill() {{ return {code}; }}\n{STOP_LOADER}"),
                ])
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(if code <= 1 { 0 } else { code }));
        }
    }

    #[test]
    fn legacy_loader_stop_uses_exact_argv_and_does_not_match_command_text() {
        let dir = tempfile::tempdir().unwrap();
        let proc = dir.path().join("proc");
        let locks = dir.path().join("locks");
        fs::create_dir(&locks).unwrap();
        for (pid, cmdline) in [
            (42, b"/system/bin/sh\0/data/local/bin/load.bin\0".as_slice()),
            (
                43,
                b"/system/bin/sh\0-c\0pkill -f /data/local/bin/load.bin\0".as_slice(),
            ),
            (44, b"/other/process\0/data/local/bin/load.bin\0".as_slice()),
        ] {
            let path = proc.join(pid.to_string());
            fs::create_dir_all(&path).unwrap();
            fs::write(
                path.join("stat"),
                format!("{pid} (sh) S {}300 0\n", "0 ".repeat(18)),
            )
            .unwrap();
            fs::write(path.join("cmdline"), cmdline).unwrap();
        }
        for (name, pid) in [
            ("voyahtune_load.v2.lock", 42),
            ("voyah_load.v2.lock", 43),
            ("voyah_load.lock", 44),
        ] {
            std::os::unix::fs::symlink(pid.to_string(), locks.join(name)).unwrap();
        }
        let script = STOP_LOADER
            .replace("/proc/", &format!("{}/", proc.display()))
            .replace("/data/local/tmp/", &format!("{}/", locks.display()));
        let output = Command::new("sh").args(["-c", &format!(
            "pkill() {{ [ \"$*\" = '-x loaderFrida' ] || exit 99; return 1; }}\nkill() {{ printf 'stopped:%s\\n' \"$1\"; rm -f {}/$1/cmdline; }}\n{script}", proc.display()
        )]).output().unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert_eq!(String::from_utf8_lossy(&output.stdout), "stopped:42\n");
    }

    #[test]
    fn hook_stop_skips_empty_input_and_only_targets_owned_hooks() {
        let dir = tempfile::tempdir().unwrap();
        let kill = dir.path().join("kill");
        fs::write(
            &kill,
            "#!/bin/sh\nprintf '%s\\n' \"$*\"\nexit \"${TEST_KILL_STATUS:-0}\"\n",
        )
        .unwrap();
        fs::set_permissions(&kill, fs::Permissions::from_mode(0o755)).unwrap();
        let path = format!(
            "{}:{}",
            dir.path().display(),
            std::env::var("PATH").unwrap()
        );
        for (rows, expected, code) in [
            ("", "", 0),
            ("root 101 1 frida-inject -s /data/local/bin/vd_bypass.js\nroot 102 1 frida-inject -s /other/app.js\nroot 103 1 unrelated-process", "-9 101\n", 0),
            ("root 101 1 frida-inject -s /data/local/bin/vd_bypass.js", "-9 101\n", 1),
        ] {
            let output = Command::new("sh")
                .env("PATH", &path)
                .env("TEST_ROWS", rows)
                .env("TEST_KILL_STATUS", code.to_string())
                .args(["-c", &format!("ps() {{ [ -z \"$TEST_ROWS\" ] || printf '%s\\n' \"$TEST_ROWS\"; }}\n{}", c::REMOVE_PROCESSES)])
                .output()
                .unwrap();
            assert_eq!(String::from_utf8_lossy(&output.stdout), expected);
            assert_eq!(output.status.success(), code == 0);
        }
    }
}
