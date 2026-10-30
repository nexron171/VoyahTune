use super::*;

pub(super) const PROBE: &str = include_str!("native_data_probe.sh");
const REPAIR: &str =
    include_str!("../../../../../Packaging/installer/common/repair-native-data.sh");

impl Engine {
    pub(super) fn prepare_od_native_data(&self, deadline: Instant) -> Result<()> {
        self.wait_boot(true)?;
        self.readiness_progress("Проверяем каталог DE Native", deadline)?;
        self.postflight_shell(REPAIR).map_err(|error| {
            Error::new(
                "NATIVE_DATA_REPAIR_FAILED",
                "Не удалось восстановить каталог DE Native",
            )
            .detail(error)
        })?;
        Ok(())
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::process::Command;

    #[test]
    fn missing_de_repairs_in_order_and_preserves_existing_data() {
        for (de_present, registered, install_succeeds) in [
            (true, true, true),
            (false, true, true),
            (false, false, true),
            (false, true, false),
        ] {
            let directory = tempfile::tempdir().unwrap();
            let de = directory.path().join("de");
            let ce = directory.path().join("ce");
            fs::write(&ce, "preferences").unwrap();
            if de_present {
                fs::create_dir(&de).unwrap();
            }
            let script =
                REPAIR.replace("/data/user_de/0/ru.big.town.anative", &de.to_string_lossy());
            let fixture = r#"
am() { printf 'am %s\n' "$*" >> "$TEST_ROOT/commands"; }
pm() {
    printf 'pm %s\n' "$*" >> "$TEST_ROOT/commands"
    [ "$REGISTERED" = true ]
}
cmd() {
    printf 'cmd %s\n' "$*" >> "$TEST_ROOT/commands"
    [ "$INSTALL_SUCCEEDS" = true ] || return 1
    mkdir "$TEST_ROOT/de"
}
"#;
            let output = Command::new("/bin/sh")
                .args(["-c", &format!("{fixture}\n{script}")])
                .env("TEST_ROOT", directory.path())
                .env("REGISTERED", registered.to_string())
                .env("INSTALL_SUCCEEDS", install_succeeds.to_string())
                .output()
                .unwrap();
            assert_eq!(output.status.success(), install_succeeds);
            assert_eq!(fs::read_to_string(&ce).unwrap(), "preferences");
            let commands =
                fs::read_to_string(directory.path().join("commands")).unwrap_or_default();
            if de_present {
                assert!(commands.is_empty());
            } else {
                assert_eq!(
                    commands.lines().collect::<Vec<_>>(),
                    [
                        "am force-stop --user 0 ru.big.town.anative",
                        "pm uninstall -k --user 0 ru.big.town.anative",
                        "cmd package install-existing --user 0 ru.big.town.anative",
                    ]
                );
            }
        }
    }
}
