use super::*;

pub(super) const PROBE: &str = include_str!("native_data_probe.sh");

fn repair_script(expected: &str, digest: &str) -> String {
    format!(
        r#"{PROBE}
expected={expected}
[ "$(probe_native_data)" = "$expected" ] || {{ echo RECHECK; exit 0; }}
expected_uid=${{expected##*|}}
state=${{expected%%|*}}
{verify_system_apk}
if [ "$state" = BROKEN ]; then
    {verify_active_apk}
    echo NATIVE_DATA_PHASE=unregister-preserving-data
    am force-stop --user 0 ru.big.town.anative || exit 1
    pm uninstall -k --user 0 ru.big.town.anative || exit 1
elif [ "$state" != UNREGISTERED ]; then
    echo RECHECK
    exit 0
fi
echo NATIVE_DATA_PHASE=register-existing
cmd package install-existing --user 0 --wait ru.big.town.anative || exit 1
actual=$(probe_native_data)
case "$actual" in READY\|*) ;; *) echo "$actual"; exit 1 ;; esac
[ "${{actual##*|}}" = "$expected_uid" ] || exit 1
{verify_active_apk}
printf '%s\n' "$actual"
"#,
        expected = quote(expected),
        verify_system_apk = format!(
            "digest=$(sha256sum {}) || exit 1\n[ \"${{digest%% *}}\" = {} ] || exit 1",
            quote(NATIVE_PATH),
            quote(digest)
        ),
        verify_active_apk = active_apk_script(NATIVE, digest, None),
    )
}

impl Engine {
    pub(super) fn prepare_od_native_data(&self, deadline: Instant) -> Result<()> {
        if self.payload.manifest.infrastructure != crate::infrastructure::Infrastructure::Od {
            return Err(Error::new(
                "NATIVE_DATA_REPAIR_NOT_ALLOWED",
                "Восстановление доступно только для OD",
            ));
        }
        let mut previous = String::new();
        while deadline.saturating_duration_since(Instant::now()) >= Duration::from_secs(2) {
            self.readiness_progress("Проверяем каталоги данных Native", deadline)?;
            if !self
                .readiness_command("id -u", deadline)
                .is_ok_and(|uid| uid == "0")
            {
                if self.cancel.load(Ordering::Relaxed) {
                    return Err(Error::new("CANCELLED", "Проверка остановлена"));
                }
                let _ = self.adb.run(&["root"], None, Duration::from_secs(5));
                previous.clear();
            } else {
                let current =
                    self.readiness_command(&format!("{PROBE}\nprobe_native_data"), deadline);
                match current {
                    Ok(state) if state.starts_with("READY|") => return Ok(()),
                    Ok(state)
                        if state.starts_with("BROKEN|") || state.starts_with("UNREGISTERED|") =>
                    {
                        if state == previous {
                            crate::cunba::check(
                                &self.adb,
                                deadline.saturating_duration_since(Instant::now()),
                            )?;
                            crate::voyahtweaks::check(
                                &self.adb,
                                deadline.saturating_duration_since(Instant::now()),
                            )?;
                            self.readiness_progress(
                                "Восстанавливаем регистрацию Native с сохранением данных",
                                deadline,
                            )?;
                            let seconds = deadline
                                .saturating_duration_since(Instant::now())
                                .as_secs()
                                .saturating_sub(2)
                                .min(60);
                            if seconds < 5 {
                                break;
                            }
                            if self.cancel.load(Ordering::Relaxed) {
                                return Err(Error::new("CANCELLED", "Восстановление остановлено"));
                            }
                            let script =
                                repair_script(&state, &self.payload.artifact("native.apk")?.sha256);
                            let result = self.adb.shell(
                                &postflight_script(
                                    &script,
                                    &format!("desktop:{}:native-data", self.operation.id),
                                    seconds as u32,
                                ),
                                Duration::from_secs(seconds + 1),
                            );
                            match result {
                                Ok(output) if output.lines().last() == Some("RECHECK") => {
                                    previous.clear()
                                }
                                Ok(output)
                                    if output
                                        .lines()
                                        .last()
                                        .is_some_and(|line| line.starts_with("READY|")) =>
                                {
                                    return Ok(())
                                }
                                result => {
                                    self.readiness_diagnostics();
                                    return Err(Error::new(
                                        "NATIVE_DATA_REPAIR_FAILED",
                                        "Не удалось восстановить каталоги данных Native",
                                    )
                                    .detail(format!("{result:?}"))
                                    .retry("Повторите установку после завершения загрузки Android. Данные Native не очищались."));
                                }
                            }
                        } else {
                            previous = state;
                        }
                    }
                    Err(error) if error.code == "CANCELLED" => return Err(error),
                    _ => previous.clear(),
                }
            }
            thread::sleep(
                Duration::from_secs(2).min(deadline.saturating_duration_since(Instant::now())),
            );
        }
        Err(Error::new(
            "NATIVE_DATA_UNCONFIRMED",
            "Android недоступен для проверки каталогов Native",
        )
        .retry("Подключите автомобиль и повторите установку."))
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::process::Command;

    #[test]
    fn probe_distinguishes_missing_data_from_unavailable_android() {
        for mode in [
            "ready",
            "missing-de",
            "owner",
            "context",
            "unregistered",
            "package-down",
            "restarted",
        ] {
            let directory = tempfile::tempdir().unwrap();
            fs::create_dir(directory.path().join("ce")).unwrap();
            if mode != "missing-de" {
                fs::create_dir(directory.path().join("de")).unwrap();
            }
            let probe = PROBE
                .replace(
                    "/data/user/0/ru.big.town.anative",
                    &directory.path().join("ce").to_string_lossy(),
                )
                .replace(
                    "/data/user_de/0/ru.big.town.anative",
                    &directory.path().join("de").to_string_lossy(),
                );
            let fixture = r#"
android_identity() {
    if [ "$TEST_MODE" = restarted ] && [ -e "$TEST_ROOT/observed" ]; then echo boot:2:3;
    else touch "$TEST_ROOT/observed"; echo boot:1:2; fi
}
pm() {
    if [ "$TEST_MODE" = package-down ]; then return 1; fi
    if [ "$1" = path ]; then echo package:/system/priv-app/Native/Native.apk; return; fi
    echo package:android
    if [ "$TEST_MODE" != unregistered ]; then echo package:ru.big.town.anative; fi
}
dumpsys() { echo '    userId=10068'; }
stat() { if [ "$TEST_MODE" = owner ]; then echo 0:0; else echo 10068:10068; fi; }
ls() { if [ "$TEST_MODE" = context ]; then echo u:object_r:system_data_file:s0; else echo u:object_r:privapp_data_file:s0:c512,c768; fi; }
probe_native_data
"#;
            let output = Command::new("/bin/sh")
                .args(["-c", &format!("{probe}\n{fixture}")])
                .env("TEST_ROOT", directory.path())
                .env("TEST_MODE", mode)
                .output()
                .unwrap();
            assert!(output.status.success());
            let expected = match mode {
                "ready" => "READY|boot:1:2|10068",
                "unregistered" => "UNREGISTERED|boot:1:2|10068",
                "package-down" | "restarted" => "UNAVAILABLE",
                _ => "BROKEN|boot:1:2|10068",
            };
            assert_eq!(
                String::from_utf8(output.stdout).unwrap().trim(),
                expected,
                "{mode}"
            );
        }
    }
    #[test]
    fn repair_preserves_data_resumes_registration_and_rechecks_identity() {
        for (initial, expected, mode, succeeds, removes) in [
            (
                "BROKEN|boot:1:2|10068",
                "BROKEN|boot:1:2|10068",
                "ok",
                true,
                true,
            ),
            (
                "UNREGISTERED|boot:1:2|10068",
                "UNREGISTERED|boot:1:2|10068",
                "ok",
                true,
                false,
            ),
            (
                "READY|boot:1:2|10068",
                "BROKEN|boot:1:2|10068",
                "ok",
                true,
                false,
            ),
            (
                "BROKEN|boot:2:3|10068",
                "BROKEN|boot:1:2|10068",
                "ok",
                true,
                false,
            ),
            (
                "BROKEN|boot:1:2|10068",
                "BROKEN|boot:1:2|10068",
                "broken",
                false,
                true,
            ),
            (
                "BROKEN|boot:1:2|10068",
                "BROKEN|boot:1:2|10068",
                "uid-changed",
                false,
                true,
            ),
            (
                "BROKEN|boot:1:2|10068",
                "BROKEN|boot:1:2|10068",
                "wrong-apk",
                false,
                false,
            ),
        ] {
            let directory = tempfile::tempdir().unwrap();
            fs::write(directory.path().join("state"), initial).unwrap();
            let commands = r#"
probe_native_data() { cat "$TEST_ROOT/state"; }
sha256sum() { if [ "$TEST_MODE" = wrong-apk ]; then echo 'wrong apk'; else echo 'expected apk'; fi; }
am() { echo "am $*" >> "$TEST_ROOT/commands"; }
pm() {
    case "$1" in
        path) echo 'package:/system/priv-app/Native/Native.apk' ;;
        uninstall)
            [ "$*" = 'uninstall -k --user 0 ru.big.town.anative' ] || exit 20
            echo "$*" >> "$TEST_ROOT/commands"
            echo 'UNREGISTERED|boot:1:2|10068' > "$TEST_ROOT/state"
            ;;
        *) exit 21 ;;
    esac
}
cmd() {
    [ "$*" = 'package install-existing --user 0 --wait ru.big.town.anative' ] || exit 22
    echo "$*" >> "$TEST_ROOT/commands"
    case "$TEST_MODE" in
        broken) echo 'BROKEN|boot:1:2|10068' > "$TEST_ROOT/state" ;;
        uid-changed) echo 'READY|boot:1:2|10069' > "$TEST_ROOT/state" ;;
        *) echo 'READY|boot:1:2|10068' > "$TEST_ROOT/state" ;;
    esac
}
"#;
            let script = repair_script(expected, "expected").replace(PROBE, commands);
            let output = Command::new("/bin/sh")
                .args(["-c", &script])
                .env("TEST_ROOT", directory.path())
                .env("TEST_MODE", mode)
                .output()
                .unwrap();
            assert_eq!(
                output.status.success(),
                succeeds,
                "{initial} {mode}: {output:?}"
            );
            let executed =
                fs::read_to_string(directory.path().join("commands")).unwrap_or_default();
            assert_eq!(
                executed.contains("uninstall -k"),
                removes,
                "{initial} {mode}"
            );
            if initial != expected {
                assert!(executed.is_empty());
                assert_eq!(String::from_utf8(output.stdout).unwrap().trim(), "RECHECK");
            }
        }
    }

    #[test]
    fn recovery_requires_stable_evidence_and_is_od_only() {
        let directory = tempfile::tempdir().unwrap();
        let adb_path = directory.path().join("adb");
        fs::write(
            &adb_path,
            r#"#!/bin/sh
root=$(dirname "$0")
script=$(cat)
printf '%s\n' "$script" >> "$root/commands"
case "$script" in
    *'VOYAHTWEAKS_FOUND'*) echo VOYAHTWEAKS_CLEAR ;;
    *'CUNBA_FOUND'*) echo CUNBA_CLEAR ;;
    *'pm uninstall -k'*)
        echo repaired >> "$root/repairs"
        echo 'READY|boot:1:2|10068' > "$root/state"
        echo 'READY|boot:1:2|10068' ;;
    *'probe_native_data'*)
        cat "$root/state"
        echo observed >> "$root/observations" ;;
    *'id -u'*) echo 0 ;;
    *) exit 1 ;;
esac
"#,
        )
        .unwrap();
        fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
        let manifest = json!({
            "schema":1, "infrastructure":"od", "product":"VoyahTune",
            "releaseVersion":"4.2.0-od", "buildRevision":"test",
            "artifacts":[{"name":"native.apk","path":"native.apk","sha256":"0".repeat(64),"size":1}]
        });
        let mut engine = Engine::new(
            &adb_path,
            Payload {
                root: directory.path().into(),
                manifest: serde_json::from_value(manifest).unwrap(),
            },
            "CAR-001",
            &directory.path().join("logs"),
            Arc::new(|_| {}),
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
        fs::write(directory.path().join("state"), "READY|boot:1:2|10068").unwrap();
        engine
            .prepare_od_native_data(Instant::now() + Duration::from_secs(10))
            .unwrap();
        assert!(!directory.path().join("repairs").exists());
        fs::write(directory.path().join("state"), "UNAVAILABLE").unwrap();
        assert_eq!(
            engine
                .prepare_od_native_data(Instant::now() + Duration::from_secs(3))
                .unwrap_err()
                .code,
            "NATIVE_DATA_UNCONFIRMED"
        );
        assert!(!directory.path().join("repairs").exists());
        fs::write(directory.path().join("observations"), "").unwrap();
        fs::write(directory.path().join("state"), "BROKEN|boot:1:2|10068").unwrap();
        engine
            .prepare_od_native_data(Instant::now() + Duration::from_secs(15))
            .unwrap();
        assert_eq!(
            fs::read_to_string(directory.path().join("repairs"))
                .unwrap()
                .lines()
                .count(),
            1
        );
        assert_eq!(
            fs::read_to_string(directory.path().join("observations"))
                .unwrap()
                .lines()
                .count(),
            2
        );
        engine.payload.manifest.infrastructure = crate::infrastructure::Infrastructure::Pi;
        let before = fs::read(directory.path().join("commands")).unwrap();
        assert_eq!(
            engine
                .prepare_od_native_data(Instant::now() + Duration::from_secs(10))
                .unwrap_err()
                .code,
            "NATIVE_DATA_REPAIR_NOT_ALLOWED"
        );
        assert_eq!(fs::read(directory.path().join("commands")).unwrap(), before);
    }
}
