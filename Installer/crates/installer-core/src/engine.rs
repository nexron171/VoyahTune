//! Rust port of Packaging/installer/{full,light}/install.sh and full/remove.sh.
//! Each checked result below corresponds to a checked branch in those scripts.
//! GUI steps, diagnostics and journals do not add vehicle preconditions.
//! The approved VoyahHlCTRL remediation is an opt-in extension to the permission step.
use crate::{
    adb::{quote, Adb},
    canbus::{self, RemovalConsent},
    classic_commands as c,
    events::{EventCallback, Events},
    inventory,
    payload::{self, Payload, Variant, NATIVE, NATIVE_PATH, RESTORE},
    plans::{self, Action, Dns, Request},
    recovery::{self, Operation, DEVICE_STATE},
    Error, Result,
};
use serde_json::json;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::Duration,
};
const LEGACY_INIT: &str = "/system/etc/init.logcat.sh";
const LEGACY_MARKER: &str = "# init.logcat.sh Open Voyah:";
pub struct Engine {
    pub adb: Adb,
    pub payload: Payload,
    pub operation: Operation,
    pub cancel: Arc<AtomicBool>,
    pub canbus_consent: Option<Arc<RemovalConsent>>,
    restart_loader: bool,
    legacy_migrated: bool,
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
        })
    }
    pub fn run(&mut self, request: Request) -> Result<()> {
        if !request.confirmed || request.serial != self.adb.serial.as_deref().unwrap_or("") {
            return Err(Error::new(
                "CONFIRMATION_REQUIRED",
                "Подтвердите найденный автомобиль и план операции",
            ));
        }
        let result = self.execute(request);
        // full_install_exit / light_install_exit: only before Light teardown, or
        // before the accepted final reboot for Full. Do not restart after reboot.
        if self.restart_loader {
            self.ignore("setprop ctl.start voyahtune_load 2>/dev/null || true\n");
        }
        let report = match &result {
            Ok(()) => json!({"success":true,"operationId":self.operation.id}),
            Err(e) => json!({"success":false,"operationId":self.operation.id,"error":e,
                "recovery":"Завершённые изменения могли сохраниться. Повторите установку или удаление после устранения указанной причины."}),
        };
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
        self.adb.run(args, None, Duration::MAX)
    }
    fn shell(&self, script: &str) -> Result<String> {
        self.adb.shell(script, Duration::MAX)
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
            "Подготовка файлов комплекта",
            |e| {
                e.adb.require_single()?;
                e.local_files(r.action)
            },
        )?;
        self.step("root","Получение системного доступа",|e|{
            e.root_sequence(false)?;
            // Migration of our obsolete mutex only. Failure is diagnostic, never a gate.
            e.ignore(&format!("rm -f {DEVICE_STATE}/lock/owner 2>/dev/null; rmdir {DEVICE_STATE}/lock 2>/dev/null; rmdir {DEVICE_STATE} 2>/dev/null; true\n"));Ok(())
        })?;
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
        if let Some(v) = r.action.variant() {
            self.step("backup", "Сохранение файлов перед заменой", |e| e.backup(v))?;
            self.step(
                "signing-reset",
                "Переустановка при смене подписи",
                |e| e.signing_reset(r.action),
            )?;
            self.step("runtime", "Остановка старых hooks", |e| {
                e.freeze(v)
            })?;
            if v == Variant::Full {
                self.step("files", "Установка Frida и скриптов", |e| e.full_files())?;
                self.step(
                    "migration",
                    "Миграция старого init.logcat.sh",
                    |e| e.migrate_legacy(),
                )?;
                self.step("boot-hooks", "Установка boot-hook", |e| {
                    e.boot_hooks()
                })?;
            }
            self.step("native", "Установка Native и разрешений", |e| e.native(v))?;
            self.step(
                "packages",
                "Установка RestoreMode и настроек",
                |e| e.packages(v),
            )?;
            self.step("dns", "Настройка DNS", |e| e.dns_choice(r.dns))?;
            self.step(
                "reboot",
                "Перезагрузка автомобиля",
                |e| {
                    e.raw(&["reboot"])?.checked("ADB не принял перезагрузку")?;
                    e.restart_loader = false;
                    Ok(())
                },
            )?;
            self.step("verify", "Проверка запуска Native", |e| {
                e.wait_boot(true)?;
                e.native_ready()
            })?;
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
                    e.raw(&["reboot"])?.checked("ADB не принял перезагрузку")?;
                    Ok(())
                },
            )?;
        }
        Ok(())
    }
    fn local_files(&self, a: Action) -> Result<()> {
        let mut names = vec!["dns-helper.sh"];
        if a != Action::Remove {
            names.extend(["dns.apk", "whitelist.xml"]);
        }
        if a != Action::Light {
            names.push("init.logcat.original.sh");
        }
        if a == Action::Full {
            names.extend(payload::FULL_NAMES.iter().copied());
        }
        for name in names {
            let p = self.payload.file(name, None)?;
            if !p.is_file() || p.metadata()?.len() == 0 {
                return Err(self.fail("Нет обязательного файла комплекта", name));
            }
        }
        if let Some(v) = a.variant() {
            for name in ["native.apk", "restore_mode.apk"] {
                let p = self.payload.file(name, Some(v))?;
                if p.metadata()?.len() == 0 {
                    return Err(self.fail("Пустой обязательный APK", name));
                }
            }
        }
        // ydns_prepare_helper's existing pinned RRO check, not a new payload gate.
        if a != Action::Remove
            && payload::sha256(&self.payload.file("dns.apk", None)?)?
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
                json!({"package":canbus::PACKAGE,"awaitingConfirmation":self.canbus_consent.is_some(),"cliFlag":"--remove-voyah-hl-service"}))?;
        }
        let Some(consent) = &self.canbus_consent else {
            return Err(Error::new("ACTION_REQUIRED", canbus::NOTICE)
                .retry("Для удаления com.voyah.hl.service повторите команду с --remove-voyah-hl-service или используйте --interactive."));
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
    fn backup(&self, v: Variant) -> Result<()> {
        let dir = self.backup_dir();
        let made = fs::create_dir_all(&dir);
        if v == Variant::Full {
            made?;
        }
        let mut paths = vec![
            (NATIVE_PATH.to_owned(), "Native.apk".to_owned()),
            (
                payload::WHITELIST.to_owned(),
                "privapp-permissions-ru.big.town.anative.xml".to_owned(),
            ),
        ];
        if v == Variant::Full {
            let mut full = vec![];
            for name in [
                "load.bin",
                "steeringwheelkeys.js",
                "launcherdock.js",
                "multidisplay.js",
                "vd_bypass.js",
                "frida-inject",
            ] {
                full.push((format!("/data/local/bin/{name}"), name.into()));
            }
            full.extend(paths);
            paths = full;
        }
        for (remote, name) in paths {
            let dst = dir.join(&name);
            if v == Variant::Light {
                if !dst.is_file() {
                    let _ = self.adb.pull(&remote, &dst);
                }
                continue;
            }
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
    fn freeze(&mut self, v: Variant) -> Result<()> {
        if v == Variant::Light && self.shell(c::LIGHT_LEGACY_STATE).unwrap_or_default() != "CLEAN" {
            return Err(self.fail(
                "Найден legacy Full hook. Сначала выполните удаление",
                LEGACY_INIT,
            ));
        }
        self.restart_loader = true;
        self.ignore(if v == Variant::Full {
            c::STOP_FULL
        } else {
            c::STOP_LIGHT
        });
        self.apollo_safe(true)?;
        self.ignore("am force-stop com.qinggan.app.vehiclesetting\n");
        if v == Variant::Light {
            self.ignore("am force-stop com.qinggan.app.qgime\n");
            self.restart_loader = false;
            self.shell(c::LIGHT_TEARDOWN)?;
        } else {
            self.ignore(c::APOLLO_FILES);
        }
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
        if v == Variant::Light {
            self.ignore("settings delete global voyahtune_keyboard_mode\n");
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
    fn full_files(&self) -> Result<()> {
        self.shell("mkdir -p /data/local/bin\n")?;
        for name in payload::FULL_NAMES
            .iter()
            .filter(|s| !s.starts_with("voyahtune.load."))
        {
            let dst = format!("/data/local/bin/{name}");
            self.push_file(
                &self.payload.file(name, None)?,
                &format!("{dst}.voyahtune.new"),
                &dst,
                if ["load.bin", "frida-inject"].contains(name) {
                    0o755
                } else {
                    0o644
                },
                false,
            )?;
        }
        self.shell(c::APP_CLIENT_MIGRATION)?;
        self.shell("rm -f /data/local/bin/voyahtune-hook-manifest.json /data/local/bin/voyahtune-hook-manifest.json.voyahtune.new\n")?;
        Ok(())
    }
    fn native(&self, v: Variant) -> Result<()> {
        self.shell(if v==Variant::Light{"mkdir -p /system/priv-app/Native && chown 0:0 /system/priv-app/Native && chmod 755 /system/priv-app/Native && restorecon /system/priv-app/Native\n"}else{"mkdir -p /system/priv-app/Native && chmod 755 /system/priv-app/Native\n"})?;
        self.push_file(
            &self.payload.file("native.apk", Some(v))?,
            "/system/priv-app/.Native.apk.voyahtune.new",
            NATIVE_PATH,
            0o644,
            true,
        )?;
        if v == Variant::Full {
            self.ignore("ls -all /system/priv-app/Native\n");
        }
        self.shell(if v==Variant::Light{"mkdir -p /system/etc/permissions && chown 0:0 /system/etc/permissions && chmod 755 /system/etc/permissions && restorecon /system/etc/permissions\n"}else{"mkdir -p /system/etc/permissions\n"})?;
        self.push_file(
            &self.payload.file("whitelist.xml", None)?,
            "/system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new",
            payload::WHITELIST,
            0o644,
            true,
        )
    }
    fn packages(&self, v: Variant) -> Result<()> {
        if self
            .shell("getprop persist.app.feature.leavecar\n")
            .unwrap_or_default()
            != "true"
        {
            self.ignore("setprop persist.app.feature.leavecar true\n");
        }
        if v == Variant::Full {
            for key in [
                "enable_freeform_support",
                "force_resizable_activities",
                "hidden_api_policy",
            ] {
                self.ignore(&format!("settings put global {key} 1\n"));
            }
        }
        self.install_restore(&self.payload.file("restore_mode.apk", Some(v))?)
    }
    fn native_ready(&self) -> Result<()> {
        if self.shell(c::NATIVE_READY).unwrap_or_default() != "READY" {
            self.shell("pm uninstall -k --user 0 ru.big.town.anative >/dev/null 2>&1 || true\n")?;
            self.shell("cmd package install-existing --user 0 --wait ru.big.town.anative\n")?;
        }
        if self.shell(c::NATIVE_READY).unwrap_or_default() != "READY" {
            return Err(self.fail("PackageManager не создал CE/DE Native", NATIVE));
        }
        self.shell(c::NATIVE_BROADCAST)?;
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
            self.payload.file("init.logcat.original.sh", None)?
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
    fn boot_transaction(&self) -> Result<()> {
        for name in ["voyahtune.load.rc", "voyahtune.load.sh"] {
            self.payload.file(name, None)?;
        }
        self.shell("mkdir -p /system/etc/init\n")?;
        self.ignore(c::BOOT_CLEAN_STAGE);
        let prepare = (|| {
            self.adb.push(
                &self.payload.file("voyahtune.load.sh", None)?,
                "/system/etc/.voyahtune.load.sh.new",
            )?;
            self.adb.push(
                &self.payload.file("voyahtune.load.rc", None)?,
                "/system/etc/.voyahtune.load.rc.new",
            )?;
            self.shell(c::BOOT_PREPARE)?;
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
            if self.shell(c::BOOT_READY)? != "READY" {
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
    fn remove_files(&self) -> Result<()> {
        self.ignore("pkill -f /data/local/bin/load.bin\n");
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
        Ok(())
    }
    fn restore_host_file(&self, name: &str) {
        let backup = self.backup_dir().join(name);
        let target = format!("/data/local/bin/{name}");
        if backup.is_file() {
            let _ = self.adb.push(&backup, &target);
        } else {
            self.ignore(&format!("rm -f {target}\n"));
        }
    }
    fn dns(&self, action: &str) -> Result<()> {
        self.adb.push(
            &self.payload.file("dns-helper.sh", None)?,
            "/data/local/tmp/open_voyah_dns_overlay.sh",
        )?;
        self.shell("chmod 0700 /data/local/tmp/open_voyah_dns_overlay.sh\n")?;
        if action == "install" {
            let _ = self.raw(&["remount"]);
            self.adb.push(
                &self.payload.file("dns.apk", None)?,
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
            &self.payload.file("dns-helper.sh", None)?,
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
