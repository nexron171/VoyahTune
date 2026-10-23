use crate::{adb::Adb, Error, Result};
use serde_json::json;
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(15);
const DIAGNOSTIC_TIMEOUT: Duration = Duration::from_secs(5);
const DIAGNOSTICS: &str = include_str!("voyahtweaks_diagnostics.sh");

// Feed this through stdin: putting the probe in argv would match its own process.
pub(crate) const PROBE: &str = include_str!("voyahtweaks_probe.sh");

pub(crate) fn check(adb: &Adb, timeout: Duration) -> Result<()> {
    let output = adb
        .shell(PROBE, timeout.min(TIMEOUT))
        .map_err(|error| inspection_error(error.to_string()))?;
    let result = validate(&output);
    if matches!(&result, Err(error) if error.code == "VOYAHTWEAKS_DETECTED") {
        log_diagnostics(adb, timeout.min(DIAGNOSTIC_TIMEOUT));
    }
    result
}

fn log_diagnostics(adb: &Adb, timeout: Duration) {
    let mut diagnostic_adb = adb.clone();
    diagnostic_adb.step = Some("voyahtweaks-diagnostics".into());
    if let Err(error) = diagnostic_adb.shell(DIAGNOSTICS, timeout) {
        // Diagnostic failures must not replace the detected loader evidence.
        let _ = diagnostic_adb.events.emit(
            "voyahtweaks-diagnostics-failed",
            diagnostic_adb.step.as_deref(),
            "Не удалось полностью прочитать диагностические файлы VoyahTweaks",
            json!({"error": error}),
        );
    }
}

fn inspection_error(detail: impl ToString) -> Error {
    Error::new(
        "VOYAHTWEAKS_CHECK_FAILED",
        "Не удалось проверить наличие VoyahTweaks.",
    )
    .detail(detail)
    .retry("Проверьте подключение и системный доступ, затем повторите проверку автомобиля.")
}

fn validate(output: &str) -> Result<()> {
    for line in output.lines() {
        if let Some(evidence) = line.strip_prefix("VOYAHTWEAKS_FOUND\t") {
            return Err(Error::new(
                "VOYAHTWEAKS_DETECTED",
                "Найдены признаки VoyahTweaks.",
            )
            .detail(evidence)
            .retry("Остановите установку или согласитесь на отключение VoyahTweaks с перезагрузкой и повторной проверкой."));
        }
    }
    if output.trim() == "VOYAHTWEAKS_CLEAR" {
        Ok(())
    } else {
        Err(inspection_error(output))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::Events;

    #[test]
    fn evidence_overrides_clear_and_incomplete_output_blocks() {
        let error = validate("VOYAHTWEAKS_CLEAR\nVOYAHTWEAKS_FOUND\tstartup").unwrap_err();
        assert_eq!(error.code, "VOYAHTWEAKS_DETECTED");
        assert!(validate("VOYAHTWEAKS_CLEAR").is_ok());
        for output in ["", "permission denied", "VOYAHTWEAKS_CLEAR\nunknown"] {
            assert_eq!(
                validate(output).unwrap_err().code,
                "VOYAHTWEAKS_CHECK_FAILED"
            );
        }
    }

    #[cfg(unix)]
    mod diagnostics {
        use super::*;
        use crate::events::Event;
        use std::{fs, os::unix::fs::PermissionsExt, sync::Arc};

        struct Device {
            directory: tempfile::TempDir,
            adb: Adb,
        }

        impl Device {
            fn new() -> Self {
                let directory = tempfile::tempdir().unwrap();
                let adb_path = directory.path().join("adb");
                fs::write(
                    &adb_path,
                    r#"#!/bin/sh
directory=$(dirname "$0")
script=$(cat)
case "$script" in
    *'VOYAHTWEAKS_DIAGNOSTIC_BEGIN'*)
        if [ -f "$directory/disconnected" ]; then
            echo 'device offline' >&2
            exit 1
        fi
        printf '%s\n' "$script" |
            sed "s|/data/local/tmp/load.bin|$directory/load.bin|g; s|/system/etc/init.logcat.sh|$directory/init.logcat.sh|g" |
            /bin/sh
        ;;
    *'VOYAHTWEAKS_FOUND'*) cat "$directory/probe-result" ;;
    *) exit 1 ;;
esac
"#,
                )
                .unwrap();
                fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
                fs::write(
                    directory.path().join("probe-result"),
                    "VOYAHTWEAKS_FOUND\tfile: /data/local/tmp/load.bin\n",
                )
                .unwrap();
                let events = Events::new(
                    "vehicle-check".into(),
                    Some(&directory.path().join("events.jsonl")),
                    Arc::new(|_| {}),
                )
                .unwrap();
                let adb = Adb::new(adb_path, events).unwrap();
                Self { directory, adb }
            }

            fn events(&self) -> Vec<Event> {
                fs::read_to_string(self.directory.path().join("events.jsonl"))
                    .unwrap()
                    .lines()
                    .map(|line| serde_json::from_str(line).unwrap())
                    .collect()
            }

            fn assert_detected(&self) {
                let error = check(&self.adb, TIMEOUT).unwrap_err();
                assert_eq!(error.code, "VOYAHTWEAKS_DETECTED");
                assert_eq!(error.detail, "file: /data/local/tmp/load.bin");
            }
        }

        #[test]
        fn file_contents_reach_the_journal_without_becoming_probe_results_or_commands() {
            let device = Device::new();
            let loader = format!(
                "#!/system/bin/sh\nVOYAHTWEAKS_CLEAR\ntouch {}\n# end without newline",
                crate::adb::quote(&device.directory.path().join("executed").to_string_lossy()),
            );
            let startup = "#!/system/bin/sh\n  echo 'startup contents'\n";
            fs::write(device.directory.path().join("load.bin"), &loader).unwrap();
            fs::write(device.directory.path().join("init.logcat.sh"), startup).unwrap();

            device.assert_detected();

            let output: String = device
                .events()
                .iter()
                .filter(|event| {
                    event.step_id.as_deref() == Some("voyahtweaks-diagnostics")
                        && event.kind == "command-output"
                        && event.data["stream"] == "stdout"
                })
                .map(|event| event.message.as_str())
                .collect();
            assert!(output.contains(&loader));
            assert!(output.contains(startup));
            assert_eq!(output.matches("VOYAHTWEAKS_DIAGNOSTIC_BEGIN").count(), 2);
            assert_eq!(output.matches("VOYAHTWEAKS_DIAGNOSTIC_END").count(), 2);
            assert!(!device.directory.path().join("executed").exists());
            assert_eq!(
                fs::read_to_string(device.directory.path().join("load.bin")).unwrap(),
                loader
            );

            fs::write(
                device.directory.path().join("probe-result"),
                "VOYAHTWEAKS_CLEAR\n",
            )
            .unwrap();
            let previous_events = device.events().len();
            check(&device.adb, TIMEOUT).unwrap();
            assert!(device.events()[previous_events..]
                .iter()
                .all(|event| event.step_id.as_deref() != Some("voyahtweaks-diagnostics")));
        }

        #[test]
        fn missing_files_and_adb_failure_preserve_the_detection() {
            let device = Device::new();
            let startup = "# startup is still collected when the loader is missing\n";
            fs::write(device.directory.path().join("init.logcat.sh"), startup).unwrap();
            device.assert_detected();
            let output: String = device
                .events()
                .iter()
                .filter(|event| event.kind == "command-output")
                .map(|event| event.message.as_str())
                .collect();
            assert!(output.contains("VOYAHTWEAKS_DIAGNOSTIC_UNAVAILABLE\tmissing"));
            assert!(output.contains(startup));

            fs::write(device.directory.path().join("disconnected"), "").unwrap();
            device.assert_detected();
            assert!(device.events().iter().any(|event| {
                event.kind == "voyahtweaks-diagnostics-failed"
                    && event.data["error"]["code"] == "ADB_CONNECTION_LOST"
            }));
        }
    }
}
