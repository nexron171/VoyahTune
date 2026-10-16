use super::*;

const DEADLINE: Duration = Duration::from_secs(180);
const STABLE: Duration = Duration::from_secs(10);
const NATIVE_HEALTH: &str = "content call --uri content://ru.big.town.anative.ota --method health";
const RESTORE_HEALTH: &str = "content call --uri content://ru.big.town.restoremode.restoremodecontentprovider --method otaHealth";

fn bundle_value<'a>(output: &'a str, key: &str) -> Option<&'a str> {
    let body = output
        .trim()
        .strip_prefix("Result: Bundle[{")?
        .strip_suffix("}]")?;
    body.split(", ").find_map(|entry| {
        let (name, value) = entry.split_once('=')?;
        (name == key).then_some(value)
    })
}

fn require_health(output: &str, key: &str, version: &str, component: &str) -> Result<()> {
    if bundle_value(output, key) != Some("true")
        || bundle_value(output, "version") != Some(version)
        || bundle_value(output, "error").is_some()
    {
        return Err(Error::new(
            "HEALTH_PENDING",
            format!("Не подтверждена готовность {component}"),
        )
        .detail(output));
    }
    Ok(())
}

#[derive(Default)]
struct Stability {
    identity: String,
    since: Option<Instant>,
}

impl Stability {
    fn observe(&mut self, identity: &str, now: Instant) -> bool {
        if self.identity != identity || self.since.is_none() {
            self.identity = identity.into();
            self.since = Some(now);
        }
        now.duration_since(self.since.unwrap()) >= STABLE
    }

    fn reset(&mut self) {
        self.since = None;
    }
}

impl Engine {
    pub(super) fn readiness_progress(&self, message: &str, deadline: Instant) -> Result<()> {
        let remaining = deadline.saturating_duration_since(Instant::now());
        self.adb.events.emit(
            "readiness-progress",
            Some("verify"),
            message,
            json!({"deadlineUnixMs": chrono::Utc::now().timestamp_millis() + remaining.as_millis() as i64}),
        )
    }

    pub(super) fn readiness_command(&self, script: &str, deadline: Instant) -> Result<String> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(Error::new("CANCELLED", "Проверка остановлена"));
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining < Duration::from_secs(2) {
            return Err(Error::new("HEALTH_TIMEOUT", "Истекло время проверки"));
        }
        let seconds = remaining.as_secs().saturating_sub(1).min(10);
        self.adb.shell(
            &format!(
                "/system/bin/timeout {seconds} /system/bin/sh -c {}\n",
                quote(script)
            ),
            Duration::from_secs(seconds + 1),
        )
    }

    fn readiness_identity(&self, deadline: Instant) -> Result<String> {
        let identity = self.readiness_command(
            "set -e\n[ \"$(getprop sys.boot_completed)\" = 1 ]\nservice check activity | grep -q ': found'\nservice check package | grep -q ': found'\ncat /proc/sys/kernel/random/boot_id\npidof system_server\npidof ru.big.town.anative\npidof ru.big.town.restoremode",
            deadline,
        )?;
        if identity.lines().count() != 4 || identity.lines().any(|line| line.trim().is_empty()) {
            return Err(Error::new(
                "HEALTH_PENDING",
                "Android или приложения ещё не готовы",
            ));
        }
        Ok(identity)
    }

    fn readiness_sample(&self, deadline: Instant, start_sent: &mut bool) -> Result<String> {
        self.readiness_progress("Проверяем USB-соединение с автомобилем", deadline)?;
        if self.readiness_command("id -u", deadline)? != "0" {
            let remaining = deadline.saturating_duration_since(Instant::now());
            self.adb
                .run(&["root"], None, Duration::from_secs(10).min(remaining))?
                .checked("Не удалось запросить системный доступ после перезагрузки")?;
            return Err(Error::new(
                "HEALTH_PENDING",
                "Ожидание ADB с системным доступом",
            ));
        }
        self.readiness_progress("Ожидаем завершения загрузки Android", deadline)?;
        self.readiness_command(
            "[ \"$(getprop sys.boot_completed)\" = 1 ] && service check package | grep -q ': found'",
            deadline,
        )?;
        self.readiness_progress("Проверяем отсутствие активной Cunba", deadline)?;
        crate::cunba::check(
            &self.adb,
            deadline.saturating_duration_since(Instant::now()),
        )?;
        crate::voyahtweaks::check(
            &self.adb,
            deadline.saturating_duration_since(Instant::now()),
        )?;
        self.readiness_command("[ ! -e /data/local/voyahtune-install.lock ]", deadline)
            .map_err(|error| {
                Error::new("HEALTH_PENDING", "Идёт другая операция установки или OTA").detail(error)
            })?;
        self.readiness_progress("Проверяем запуск Native", deadline)?;
        let native = self
            .readiness_command(NATIVE_HEALTH, deadline)
            .map_err(|error| {
                Error::new("HEALTH_PENDING", "Native не отвечает на запрос готовности")
                    .detail(error)
            });
        if native.as_ref().map_or(true, |output| {
            bundle_value(output, "otaReady") != Some("true")
        }) && !*start_sent
        {
            *start_sent = true;
            // Delivery timeout is not proof of a failed service start.
            if let Err(error) = self.readiness_command(c::NATIVE_BROADCAST, deadline) {
                self.warning(&error);
            }
        }
        self.readiness_progress("Проверяем запуск RestoreMode", deadline)?;
        let restore = self
            .readiness_command(RESTORE_HEALTH, deadline)
            .map_err(|error| {
                Error::new(
                    "HEALTH_PENDING",
                    "RestoreMode не отвечает на запрос готовности",
                )
                .detail(error)
            })?;
        require_health(
            &restore,
            "ready",
            &self.payload.manifest.release_version,
            "RestoreMode",
        )?;
        let native = native?;
        require_health(
            &native,
            "otaReady",
            &self.payload.manifest.release_version,
            "Native",
        )?;
        self.readiness_progress("Проверяем работу службы обновлений", deadline)?;
        let before = self.readiness_identity(deadline)?;
        self.readiness_command(
            "[ \"$(getprop init.svc.voyahtune_updater)\" = running ]",
            deadline,
        )
        .map_err(|error| Error::new("HEALTH_PENDING", "OTA-служба не запущена").detail(error))?;
        let response = self
            .readiness_command("/data/local/bin/voyahtune-updater --health", deadline)
            .map_err(|error| {
                Error::new("HEALTH_PENDING", "OTA-служба не отвечает").detail(error)
            })?;
        let status: serde_json::Value = serde_json::from_str(&response)?;
        if status["schema"] != 1
            || status["ok"] != true
            || status["uid"] != 0
            || status["infrastructure"] != "od"
            || !status["pid"].as_u64().is_some_and(|pid| pid > 0)
            || !status["capabilities"]
                .as_array()
                .is_some_and(|capabilities| {
                    capabilities
                        .iter()
                        .any(|value| value == "restoremode-ota-ui-v1")
                })
        {
            return Err(
                Error::new("HEALTH_PENDING", "Несовместимый ответ OTA-службы").detail(response),
            );
        }
        self.readiness_command("[ ! -e /data/local/voyahtune-install.lock ]", deadline)?;
        let after = self.readiness_identity(deadline)?;
        if before != after {
            return Err(Error::new(
                "HEALTH_PENDING",
                "Android или приложения перезапустились",
            ));
        }
        Ok(format!("{after}\n{}", status["pid"]))
    }

    pub(super) fn readiness_diagnostics(&self) {
        for (name, command) in [
            ("logcat.txt", "logcat -b main -b system -b crash -d -t 400"),
            ("native-data.txt", "ls -ldZ /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative; dumpsys package ru.big.town.anative; dumpsys activity exit-info ru.big.town.anative"),
            ("services.txt", "getprop init.svc.voyahtune_updater; pidof system_server; dumpsys activity services ru.big.town.anative"),
        ] {
            let output = self.readiness_command(command, Instant::now() + Duration::from_secs(6));
            let text = match output { Ok(text) => text, Err(error) => error.to_string() };
            let _ = fs::write(self.operation.dir.join(name), text);
        }
    }

    pub(super) fn verify_od_installation(&self) -> Result<()> {
        let deadline = Instant::now() + DEADLINE;
        self.prepare_od_native_data(deadline)?;
        self.verify_od_readiness_until(deadline)
    }

    fn confirmed_readiness_failure(&self) -> Option<Error> {
        let probe = format!("{}\nprobe_native_data", super::native_data::PROBE);
        let state = self
            .readiness_command(&probe, Instant::now() + Duration::from_secs(6))
            .ok()?;
        if state.starts_with("BROKEN|") || state.starts_with("UNREGISTERED|") {
            return Some(
                Error::new(
                    "NATIVE_DATA_INVALID",
                    "Повреждена регистрация или каталоги данных Native",
                )
                .detail(state)
                .retry("Повторите установку для восстановления Native с сохранением данных."),
            );
        }
        if !state.starts_with("READY|") {
            return None;
        }
        for (command, key, component) in [
            (NATIVE_HEALTH, "otaReady", "Native"),
            (RESTORE_HEALTH, "ready", "RestoreMode"),
        ] {
            let output = self
                .readiness_command(command, Instant::now() + Duration::from_secs(6))
                .ok()?;
            if bundle_value(&output, key).is_some()
                && require_health(
                    &output,
                    key,
                    &self.payload.manifest.release_version,
                    component,
                )
                .is_err()
            {
                return Some(
                    Error::new(
                        "READINESS_FAILED",
                        format!("{component} не подтвердил готовность после установки"),
                    )
                    .detail(output)
                    .retry("Посмотрите диагностический отчёт и повторите установку."),
                );
            }
        }
        None
    }

    pub(super) fn verify_od_readiness(&self) -> Result<()> {
        self.verify_od_readiness_until(Instant::now() + DEADLINE)
    }

    fn verify_od_readiness_until(&self, deadline: Instant) -> Result<()> {
        let mut stability = Stability::default();
        let mut start_sent = false;
        let mut verified_identity = String::new();
        let mut last = Error::new("HEALTH_PENDING", "Ожидание Android и приложений");
        while Instant::now() < deadline {
            if self.cancel.load(Ordering::Relaxed) {
                return Err(Error::new("CANCELLED", "Проверка остановлена"));
            }
            let sample = self.readiness_sample(deadline, &mut start_sent).and_then(|identity| {
                if verified_identity != identity {
                    self.readiness_progress("Проверяем установленные файлы приложений", deadline)?;
                    for (package, artifact) in [(NATIVE, "native.apk"), (RESTORE, "restore_mode.apk")] {
                        self.readiness_command(
                            &active_apk_script(package, &self.payload.artifact(artifact)?.sha256, None),
                            deadline,
                        ).map_err(|error| Error::new("HEALTH_PENDING", format!("Не подтверждён установленный APK {package}")).detail(error))?;
                    }
                    self.readiness_command("cmd package resolve-activity --brief --user 0 -n ru.big.town.restoremode/.OtaActivity | grep -Fx -e 'ru.big.town.restoremode/.OtaActivity' -e 'ru.big.town.restoremode/ru.big.town.restoremode.OtaActivity'", deadline)?;
                    verified_identity = identity.clone();
                }
                Ok(identity)
            });
            match sample {
                Ok(identity)
                    if Instant::now() < deadline
                        && stability.observe(&identity, Instant::now()) =>
                {
                    return Ok(())
                }
                Ok(_) => {
                    self.readiness_progress(
                        "Проверки пройдены. Наблюдаем стабильность служб",
                        deadline,
                    )?;
                }
                Err(error) if error.code == "CANCELLED" => return Err(error),
                Err(error)
                    if crate::cunba::is_blocking_error(&error)
                        || crate::voyahtweaks::is_blocking_error(&error) =>
                {
                    return Err(error)
                }
                Err(error) => {
                    stability.reset();
                    self.adb.events.emit(
                        "readiness-pending",
                        Some("verify"),
                        &error.message,
                        json!({
                            "detail": error.detail,
                            "code": error.code,
                            "deadlineUnixMs": chrono::Utc::now().timestamp_millis()
                                + deadline.saturating_duration_since(Instant::now()).as_millis() as i64
                        }),
                    )?;
                    last = error;
                }
            }
            thread::sleep(
                Duration::from_secs(2).min(deadline.saturating_duration_since(Instant::now())),
            );
        }
        self.readiness_progress(
            "Время проверки истекло. Собираем диагностический отчёт",
            deadline,
        )?;
        let confirmed_failure = self.confirmed_readiness_failure();
        self.readiness_diagnostics();
        if let Some(error) = confirmed_failure {
            return Err(error);
        }
        Err(Error::new(
            "READINESS_UNCONFIRMED",
            format!(
                "Файлы установлены, готовность не подтверждена: {}",
                last.message
            ),
        )
        .detail(last)
        .retry("Проверьте самостоятельно, что всё работает. В случае проблем повторите установку."))
    }

    pub fn verify_installed(&mut self, request: Request) -> Result<()> {
        if !request.confirmed
            || request.action != Action::Install
            || request.serial != self.adb.serial.as_deref().unwrap_or("")
            || self.payload.manifest.infrastructure != crate::infrastructure::Infrastructure::Od
        {
            return Err(Error::new(
                "VERIFICATION_NOT_ALLOWED",
                "Нет подтверждённой установки OD",
            ));
        }
        self.adb.require_single()?;
        let result = self.step(
            "verify",
            "Проверка Native и готовности OTA",
            |engine| engine.verify_od_readiness(),
        );
        self.finish_report(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn health_requires_exact_fields_and_version() {
        assert!(require_health(
            "Result: Bundle[{otaReady=true, version=4.1.1-od}]",
            "otaReady",
            "4.1.1-od",
            "Native"
        )
        .is_ok());
        for output in [
            "",
            "Result: Bundle[{otaReady=false, version=4.1.1-od}]",
            "Result: Bundle[{otaReady=true, version=4.1.0-od}]",
            "Result: Bundle[{otaReady=true, version=4.1.1-od, error=failed}]",
        ] {
            assert!(require_health(output, "otaReady", "4.1.1-od", "Native").is_err());
        }
    }

    #[test]
    fn stable_health_must_restart_after_failure_or_process_change() {
        let now = Instant::now();
        let mut stability = Stability::default();
        assert!(!stability.observe("boot/pid1", now));
        assert!(!stability.observe("boot/pid1", now + Duration::from_secs(9)));
        assert!(stability.observe("boot/pid1", now + STABLE));
        assert!(!stability.observe("boot/pid2", now + STABLE));
        stability.reset();
        assert!(!stability.observe("boot/pid2", now + Duration::from_secs(30)));
    }

    #[cfg(unix)]
    #[test]
    fn broadcast_timeout_allows_health_recovery_and_verification_never_reinstalls() {
        use std::os::unix::fs::PermissionsExt;
        let directory = tempfile::tempdir().unwrap();
        let adb_path = directory.path().join("adb");
        fs::write(&adb_path, r#"#!/bin/sh
if [ "$1" = '-s' ]; then shift 2; fi
if [ "$1" = devices ]; then
    printf 'List of devices attached\nCAR-001 device\n'
    exit 0
fi
root=$(dirname "$0")
script=$(cat)
printf '%s\n' "$script" >> "$root/commands"
case "$script" in
    *'VOYAHTWEAKS_FOUND'*)
        if [ -e "$root/voyahtweaks" ]; then
            printf 'VOYAHTWEAKS_FOUND\tloader\n'
        else
            echo VOYAHTWEAKS_CLEAR
        fi ;;
    *'CUNBA_FOUND'*)
        if [ -e "$root/cunba" ]; then
            printf 'CUNBA_FOUND\tfile: /data/local/bin/frida-inject2\n'
        elif [ -e "$root/cunba-check-failed" ]; then
            exit 1
        else
            echo CUNBA_CLEAR
        fi ;;
    *'probe_native_data'*)
        if [ -e "$root/data-broken" ]; then echo 'BROKEN|boot:1:2|10068';
        elif [ -e "$root/data-ready" ]; then echo 'READY|boot:1:2|10068';
        else echo UNAVAILABLE; fi ;;
    *'id -u'*) echo 0 ;;
    *'am broadcast'*) touch "$root/started"; echo sent >> "$root/broadcasts"; exit 124 ;;
    *'--method health'*)
        if [ -e "$root/started" ]; then
            echo 'Result: Bundle[{otaReady=true, version=4.1.1-od}]'
        else
            echo 'Result: Bundle[{otaReady=false, version=4.1.1-od}]'
        fi ;;
    *'--method otaHealth'*) echo 'Result: Bundle[{ready=true, version=4.1.1-od}]' ;;
    *'cat /proc/sys/kernel/random/boot_id'*) printf 'boot-1\n101\n102\n103\n' ;;
    *'voyahtune-updater --health'*) echo '{"schema":1,"ok":true,"uid":0,"pid":104,"infrastructure":"od","capabilities":["restoremode-ota-ui-v1"]}' ;;
    *'getprop'*|*'voyahtune-install.lock'*|*'sha256sum'*|*'resolve-activity'*) exit 0 ;;
    *) echo 'Unexpected command' >&2; exit 1 ;;
esac
"#).unwrap();
        fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
        let manifest = json!({
            "schema":1, "infrastructure":"od", "product":"VoyahTune",
            "releaseVersion":"4.1.1-od", "buildRevision":"test",
            "artifacts":[
                {"name":"native.apk","path":"native.apk","sha256":"0".repeat(64),"size":1},
                {"name":"restore_mode.apk","path":"restore_mode.apk","sha256":"0".repeat(64),"size":1}
            ]
        });
        fs::write(directory.path().join("manifest.json"), manifest.to_string()).unwrap();
        let payload = Payload {
            root: directory.path().into(),
            manifest: serde_json::from_value(manifest).unwrap(),
        };
        let captured = Arc::new(std::sync::Mutex::new(Vec::new()));
        let captured_events = captured.clone();
        let mut engine = Engine::new(
            &adb_path,
            payload,
            "CAR-001",
            &directory.path().join("logs"),
            Arc::new(move |event| captured_events.lock().unwrap().push(event.clone())),
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
        let mut sent = false;
        let deadline = Instant::now() + Duration::from_secs(60);
        for (marker, code) in [
            ("cunba", "CUNBA_DETECTED"),
            ("voyahtweaks", "VOYAHTWEAKS_DETECTED"),
            ("cunba-check-failed", "CUNBA_CHECK_FAILED"),
        ] {
            fs::write(directory.path().join(marker), "").unwrap();
            let error = engine.verify_od_readiness_until(deadline).unwrap_err();
            assert_eq!(error.code, code);
            assert!(!directory.path().join("broadcasts").exists());
            assert_eq!(engine.finish_report(Err(error)).unwrap_err().code, code);
            let report: serde_json::Value = serde_json::from_slice(
                &fs::read(engine.operation.dir.join("report.json")).unwrap(),
            )
            .unwrap();
            assert_eq!(report["success"], false);
            assert!(report.get("warning").is_none());
            fs::remove_file(directory.path().join(marker)).unwrap();
        }
        assert!(engine.readiness_sample(deadline, &mut sent).is_err());
        assert!(sent);
        assert!(engine.readiness_sample(deadline, &mut sent).is_ok());
        let progress: Vec<_> = captured
            .lock()
            .unwrap()
            .iter()
            .filter(|event| event.kind == "readiness-progress")
            .cloned()
            .collect();
        for stage in [
            "Проверяем запуск Native",
            "Проверяем запуск RestoreMode",
            "Проверяем работу службы обновлений",
        ] {
            assert!(progress
                .iter()
                .any(|event| event.message == stage && event.step_id.as_deref() == Some("verify")));
        }
        assert!(progress
            .iter()
            .all(|event| event.data["deadlineUnixMs"].as_i64().is_some()));
        assert_eq!(
            fs::read_to_string(directory.path().join("broadcasts")).unwrap(),
            "sent\n"
        );
        fs::remove_file(directory.path().join("started")).unwrap();
        assert!(engine.readiness_sample(deadline, &mut sent).is_err());
        fs::write(directory.path().join("started"), "").unwrap();
        let error = engine
            .verify_od_readiness_until(Instant::now())
            .unwrap_err();
        assert_eq!(error.code, "READINESS_UNCONFIRMED");
        assert!(error.retryable);
        assert_eq!(
            engine.finish_report(Err(error)).unwrap_err().code,
            "READINESS_UNCONFIRMED"
        );
        let report_path = engine.operation.dir.join("report.json");
        let warning_report: serde_json::Value =
            serde_json::from_slice(&fs::read(&report_path).unwrap()).unwrap();
        assert_eq!(warning_report["success"], false);
        assert_eq!(warning_report["readinessConfirmed"], false);
        assert_eq!(warning_report["error"]["code"], "READINESS_UNCONFIRMED");
        assert!(warning_report.get("warning").is_none());
        for code in [
            "ADB_COMMAND_FAILED",
            "CANCELLED",
            "NATIVE_DATA_REPAIR_FAILED",
            "READINESS_FAILED",
        ] {
            let error = engine
                .finish_report(Err(Error::new(code, "test")))
                .unwrap_err();
            assert_eq!(error.code, code);
            let report: serde_json::Value =
                serde_json::from_slice(&fs::read(&report_path).unwrap()).unwrap();
            assert_eq!(report["success"], false);
            assert!(report.get("warning").is_none());
        }
        fs::write(directory.path().join("data-broken"), "").unwrap();
        assert_eq!(
            engine.confirmed_readiness_failure().unwrap().code,
            "NATIVE_DATA_INVALID"
        );
        fs::remove_file(directory.path().join("data-broken")).unwrap();
        fs::write(directory.path().join("data-ready"), "").unwrap();
        fs::remove_file(directory.path().join("started")).unwrap();
        assert_eq!(
            engine.confirmed_readiness_failure().unwrap().code,
            "READINESS_FAILED"
        );
        fs::write(directory.path().join("started"), "").unwrap();
        assert!(engine.confirmed_readiness_failure().is_none());
        engine
            .verify_installed(Request {
                action: Action::Install,
                dns: Dns::Keep,
                serial: "CAR-001".into(),
                inventory_token: String::new(),
                confirmed: true,
            })
            .unwrap();
        let commands = fs::read_to_string(directory.path().join("commands")).unwrap();
        for forbidden in [
            "pm install",
            "pm uninstall",
            "install-existing",
            "reboot",
            "setprop",
            "mkdir",
            "rm -",
        ] {
            assert!(
                !commands.contains(forbidden),
                "Unexpected mutation: {forbidden}"
            );
        }
        assert_eq!(
            fs::read_to_string(directory.path().join("broadcasts")).unwrap(),
            "sent\n"
        );
        let report: serde_json::Value =
            serde_json::from_slice(&fs::read(engine.operation.dir.join("report.json")).unwrap())
                .unwrap();
        assert_eq!(report["success"], true);
    }
}
