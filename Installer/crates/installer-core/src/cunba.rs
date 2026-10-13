use crate::{adb::Adb, infrastructure::Infrastructure, plans::Action, Error, Result};
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(15);

// Feed this through stdin: putting the probe in argv would match its own process.
const PROBE: &str = include_str!("cunba_probe.sh");

pub(crate) fn check_installation(
    adb: &Adb,
    infrastructure: Infrastructure,
    action: Action,
) -> Result<()> {
    if infrastructure == Infrastructure::Od && action == Action::Install {
        check(adb, TIMEOUT)?;
    }
    Ok(())
}

pub(crate) fn check(adb: &Adb, timeout: Duration) -> Result<()> {
    let output = adb
        .shell(PROBE, timeout.min(TIMEOUT))
        .map_err(|error| inspection_error(error.to_string()))?;
    validate(&output)
}

fn inspection_error(detail: impl ToString) -> Error {
    Error::new(
        "CUNBA_CHECK_FAILED",
        "Не удалось проверить наличие CUNBA. Установка заблокирована.",
    )
    .detail(detail)
    .retry("Проверьте подключение и системный доступ, затем повторите проверку автомобиля.")
}

fn validate(output: &str) -> Result<()> {
    for line in output.lines() {
        if let Some(evidence) = line.strip_prefix("CUNBA_FOUND\t") {
            return Err(Error::new(
                "CUNBA_DETECTED",
                "Обнаружены признаки CUNBA. Установка VoyahTune заблокирована.",
            )
            .detail(evidence)
            .retry("Удалите CUNBA и её остаточные файлы штатным способом, перезагрузите автомобиль и повторите проверку. Совместная работа хуков может вызывать перезапуски оболочки."));
        }
    }
    if output.trim() == "CUNBA_CLEAR" {
        Ok(())
    } else {
        Err(inspection_error(output))
    }
}

pub(crate) fn is_blocking_error(error: &Error) -> bool {
    matches!(error.code.as_str(), "CUNBA_DETECTED" | "CUNBA_CHECK_FAILED")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_individual_signal_blocks_even_with_a_clear_marker() {
        for evidence in ["file", "package", "process", "startup"] {
            let error = validate(&format!("CUNBA_CLEAR\nCUNBA_FOUND\t{evidence}")).unwrap_err();
            assert_eq!(error.code, "CUNBA_DETECTED");
            assert_eq!(error.detail, evidence);
            assert!(is_blocking_error(&error));
        }
    }

    #[test]
    fn only_a_complete_clear_result_allows_installation() {
        assert!(validate("CUNBA_CLEAR").is_ok());
        for output in ["", "permission denied", "CUNBA_CLEAR\nunknown"] {
            assert_eq!(validate(output).unwrap_err().code, "CUNBA_CHECK_FAILED");
        }
    }

    #[cfg(unix)]
    mod device_probe {
        use super::*;
        use std::{fs, os::unix::fs::PermissionsExt, path::Path, process::Command};

        struct Device {
            directory: tempfile::TempDir,
            script: String,
        }

        impl Device {
            fn new() -> Self {
                let directory = tempfile::tempdir().unwrap();
                let mut script = PROBE.to_owned();
                for root in [
                    "sdcard",
                    "storage",
                    "data",
                    "system",
                    "vendor",
                    "odm",
                    "product",
                    "system_ext",
                ] {
                    script = script.replace(
                        &format!("/{root}/"),
                        &format!("{}/{root}/", directory.path().display()),
                    );
                }
                let device = Self { directory, script };
                for command in ["id", "pm", "ps"] {
                    device.write(&format!("bin/{command}"), "#!/bin/sh\ncat \"$0.output\"\n");
                    fs::set_permissions(
                        device.directory.path().join(format!("bin/{command}")),
                        fs::Permissions::from_mode(0o755),
                    )
                    .unwrap();
                }
                device.write("bin/id.output", "0\n");
                device.write(
                    "bin/pm.output",
                    "package:android\npackage:ru.big.town.anative\n",
                );
                device.write(
                    "bin/ps.output",
                    "PID ARGS\n1 init\n42 frida-inject -p 100 -s /data/local/bin/launcherdock.js\n",
                );
                device
            }

            fn write(&self, path: &str, content: &str) {
                let target = self.directory.path().join(path);
                fs::create_dir_all(target.parent().unwrap()).unwrap();
                fs::write(target, content).unwrap();
            }

            fn run(&self) -> Result<()> {
                self.write("probe.sh", &self.script);
                let output = Command::new("/bin/sh")
                    .arg(self.directory.path().join("probe.sh"))
                    .env(
                        "PATH",
                        format!("{}/bin:/usr/bin:/bin", self.directory.path().display()),
                    )
                    .output()
                    .unwrap();
                if !output.status.success() {
                    return Err(inspection_error(String::from_utf8_lossy(&output.stderr)));
                }
                validate(String::from_utf8_lossy(&output.stdout).trim())
            }
        }

        #[test]
        fn each_device_signal_alone_blocks_without_an_active_injector() {
            for (path, content) in [
                ("sdcard/Download/cunba/patch/launcher2.bin", "patch"),
                ("data/local/bin/frida-inject2", "binary"),
                (
                    "bin/pm.output",
                    "package:android\npackage:com.Cunba.lantu\n",
                ),
                (
                    "bin/ps.output",
                    "PID ARGS\n123 frida-inject2 -p 456 -s /other/launcher2.bin\n",
                ),
                ("system/etc/init/vendor.rc", "# cunba autostart\n"),
                (
                    "data/adb/service.d/custom.sh",
                    "frida-inject -s /other/systemserver.bin\n",
                ),
            ] {
                let device = Device::new();
                device.write(path, content);
                assert_eq!(device.run().unwrap_err().code, "CUNBA_DETECTED", "{path}");
            }
        }

        #[test]
        fn our_injector_is_allowed_but_incomplete_inspection_is_blocked() {
            assert!(Device::new().run().is_ok());
            for (path, content) in [
                ("bin/id.output", "2000\n"),
                ("bin/pm.output", "Error: package service unavailable\n"),
                ("bin/ps.output", "PID ARGS\n"),
            ] {
                let device = Device::new();
                device.write(path, content);
                assert_eq!(device.run().unwrap_err().code, "CUNBA_CHECK_FAILED");
            }
        }

        #[test]
        fn pi_and_removal_do_not_run_the_probe() {
            let adb =
                Adb::new(Path::new("/usr/bin/false"), crate::events::Events::quiet()).unwrap();
            for (infrastructure, action) in [
                (Infrastructure::Pi, Action::Install),
                (Infrastructure::Pi, Action::Remove),
                (Infrastructure::Od, Action::Remove),
            ] {
                assert!(check_installation(&adb, infrastructure, action).is_ok());
            }
            assert_eq!(
                check_installation(&adb, Infrastructure::Od, Action::Install)
                    .unwrap_err()
                    .code,
                "CUNBA_CHECK_FAILED"
            );
        }
    }
}
