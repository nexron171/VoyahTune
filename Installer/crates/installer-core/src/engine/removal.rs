use super::{c, Engine, Error, Result};
use crate::adb::quote;
use std::{
    thread,
    time::{Duration, Instant},
};

fn new_boot_probe(previous_boot: &str) -> String {
    format!(
        r#"
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
[ -n "$boot" ] && [ "$boot" != {previous_boot} ] || exit 1
[ "$(getprop sys.boot_completed)" = 1 ] || exit 1
service check activity | grep -q ': found' || exit 1
service check package | grep -q ': found' || exit 1
echo READY
"#,
        previous_boot = quote(previous_boot)
    )
}

// Read only key names back to the host; unrelated Global Settings values stay on the device.
const REMOVE_GLOBAL_SETTINGS: &str = r#"
owned_global_keys() {
    settings_snapshot=$(settings list global) || return 1
    printf '%s\n' "$settings_snapshot" | while IFS='=' read -r setting_name setting_value; do
        case "$setting_name" in
            voyahtune_*|open_voyah_*|enable_freeform_support|force_resizable_activities)
                printf '%s\n' "$setting_name" ;;
        esac
    done
}

setting_names=$(owned_global_keys) || exit 1
while IFS= read -r setting_name; do
    [ -n "$setting_name" ] || continue
    settings delete global "$setting_name" >/dev/null || exit 1
done <<EOF
$setting_names
EOF

remaining_settings=$(owned_global_keys) || exit 1
if [ -n "$remaining_settings" ]; then
    printf 'Global Settings не очищены:\n%s\n' "$remaining_settings" >&2
    exit 1
fi
"#;

const REMOVE_NATIVE_DATA: &str = r#"
for directory in \
    /data/user/0/ru.big.town.anative \
    /data/user_de/0/ru.big.town.anative \
    /data/data/ru.big.town.anative; do
    rm -rf "$directory" || exit 1
    if [ -e "$directory" ] || [ -L "$directory" ]; then
        printf 'Данные Native не удалены: %s\n' "$directory" >&2
        exit 1
    fi
done
"#;

impl Engine {
    pub(super) fn remove_data_after_reboot(&self, previous_boot: &str) -> Result<()> {
        // Eternalized Launcher/SystemUI agents can republish keys until their processes restart.
        let probe = new_boot_probe(previous_boot);
        let deadline = Instant::now() + Duration::from_secs(300);
        while Instant::now() < deadline {
            if self
                .adb
                .shell(&probe, Duration::from_secs(10))
                .is_ok_and(|state| state == "READY")
            {
                self.root_sequence(true)?;
                return self.finish_data_removal();
            }
            thread::sleep(Duration::from_secs(2));
        }
        Err(Error::new(
            "REMOVAL_BOOT_TIMEOUT",
            "Новая загрузка Android не подтверждена; очистка настроек не завершена",
        ))
    }

    pub(super) fn remove_native_data(&self) -> Result<()> {
        self.shell(REMOVE_NATIVE_DATA).map(|_| ()).map_err(|error| {
            Error::new(
                "NATIVE_DATA_REMOVE_FAILED",
                "Не удалось удалить данные Native",
            )
            .detail(error)
        })
    }

    fn finish_data_removal(&self) -> Result<()> {
        let script = format!(
            "{REMOVE_NATIVE_DATA}\n{}\n{REMOVE_GLOBAL_SETTINGS}",
            c::REMOVE_SETTINGS
        );
        self.postflight_shell(&script).map(|_| ()).map_err(|error| {
            Error::new(
                "REMOVAL_DATA_CLEANUP_FAILED",
                "Не удалось очистить данные Native и настройки VoyahTune",
            )
            .detail(error)
        })
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::{fs, os::unix::fs::symlink, process::Command};

    const SETTINGS_FIXTURE: &str = r#"
settings() {
    [ "$2" = global ] || return 90
    case "$1" in
        list)
            if [ "$TEST_FAILURE" = list ]; then return 1; fi
            if [ "$TEST_FAILURE" = verification ] && [ -e "$TEST_ROOT/listed" ]; then return 1; fi
            touch "$TEST_ROOT/listed"
            cat "$TEST_ROOT/settings" ;;
        delete)
            if [ "$3" = voyahtune_dpi_com.example.player ]; then
                case "$TEST_FAILURE" in
                    delete) return 1 ;;
                    retained) return 0 ;;
                esac
            fi
            awk -F= -v key="$3" '$1 != key' "$TEST_ROOT/settings" > "$TEST_ROOT/settings.new" || return 1
            mv "$TEST_ROOT/settings.new" "$TEST_ROOT/settings" ;;
        *) return 91 ;;
    esac
}
"#;

    #[test]
    fn cleanup_requires_a_new_completed_boot_and_ready_android_services() {
        for (boot, completed, services, expected) in [
            ("old-boot", "1", "found", false),
            ("new-boot", "0", "found", false),
            ("", "1", "found", false),
            ("new-boot", "1", "not found", false),
            ("new-boot", "1", "found", true),
        ] {
            let fixture = r#"
cat() { printf '%s\n' "$TEST_BOOT"; }
getprop() { printf '%s\n' "$TEST_COMPLETED"; }
service() { printf 'Service %s: %s\n' "$2" "$TEST_SERVICES"; }
"#;
            let output = Command::new("/bin/sh")
                .args(["-c", &format!("{fixture}\n{}", new_boot_probe("old-boot"))])
                .env("TEST_BOOT", boot)
                .env("TEST_COMPLETED", completed)
                .env("TEST_SERVICES", services)
                .output()
                .unwrap();
            assert_eq!(
                output.status.success(),
                expected,
                "{boot}/{completed}/{services}: {output:?}"
            );
            assert_eq!(
                String::from_utf8_lossy(&output.stdout).trim() == "READY",
                expected
            );
        }
    }

    #[test]
    fn global_cleanup_removes_dynamic_keys_and_preserves_unrelated_settings() {
        let directory = tempfile::tempdir().unwrap();
        let settings = directory.path().join("settings");
        let unrelated = "adb_enabled=1\ndevice_name=with spaces = voyahtune_value\nother_voyahtune_key=keep\nopen_voyager=keep\n";
        fs::write(
            &settings,
            format!(
                "{unrelated}voyahtune_dock1=player\nvoyahtune_dpi_com.example.player=null\nvoyahtune_dock1SplitPresetId=a=b\nvoyahtune_cluster_navigation_enabled=1\nvoyahtune_future_setting=1\nopen_voyah_apollo_master=1\nopen_voyah_future_setting=1\nenable_freeform_support=1\nforce_resizable_activities=1\n"
            ),
        )
        .unwrap();
        let script = format!(
            "{SETTINGS_FIXTURE}\n{}\n{REMOVE_GLOBAL_SETTINGS}",
            c::REMOVE_SETTINGS
        );
        for _ in 0..2 {
            let output = Command::new("/bin/sh")
                .args(["-c", &script])
                .env("TEST_ROOT", directory.path())
                .env("TEST_FAILURE", "")
                .output()
                .unwrap();
            assert!(output.status.success(), "{output:?}");
            assert_eq!(fs::read_to_string(&settings).unwrap(), unrelated);
            assert!(output.stdout.is_empty());
        }
    }

    #[test]
    fn global_cleanup_rejects_read_delete_and_verification_failures() {
        for failure in ["list", "delete", "retained", "verification"] {
            let directory = tempfile::tempdir().unwrap();
            let settings = directory.path().join("settings");
            fs::write(
                &settings,
                "adb_enabled=1\nvoyahtune_dpi_com.example.player=320\n",
            )
            .unwrap();
            let script = format!(
                "{SETTINGS_FIXTURE}\n{}\n{REMOVE_GLOBAL_SETTINGS}",
                c::REMOVE_SETTINGS
            );
            let output = Command::new("/bin/sh")
                .args(["-c", &script])
                .env("TEST_ROOT", directory.path())
                .env("TEST_FAILURE", failure)
                .output()
                .unwrap();
            assert!(!output.status.success(), "{failure}: {output:?}");
            assert!(fs::read_to_string(&settings)
                .unwrap()
                .contains("adb_enabled=1"));
        }
    }

    #[test]
    fn native_cleanup_removes_ce_de_and_legacy_data_without_touching_other_apps() {
        for legacy_symlink in [false, true] {
            let directory = tempfile::tempdir().unwrap();
            let data = directory.path().join("data");
            for parent in ["user/0", "user_de/0", "data"] {
                let parent = data.join(parent);
                fs::create_dir_all(parent.join("ru.big.town.other")).unwrap();
                fs::write(parent.join("ru.big.town.other/settings"), "keep").unwrap();
                let native = parent.join("ru.big.town.anative");
                if legacy_symlink && parent == data.join("data") {
                    symlink(data.join("user/0/ru.big.town.anative"), &native).unwrap();
                } else {
                    fs::create_dir_all(native.join("shared_prefs")).unwrap();
                    fs::write(native.join("shared_prefs/NativePrefs.xml"), "old settings").unwrap();
                }
            }
            let script = REMOVE_NATIVE_DATA.replace("/data/", &format!("{}/", data.display()));
            for _ in 0..2 {
                let output = Command::new("/bin/sh")
                    .args(["-c", &script])
                    .output()
                    .unwrap();
                assert!(output.status.success(), "{output:?}");
                for parent in ["user/0", "user_de/0", "data"] {
                    let parent = data.join(parent);
                    assert!(fs::symlink_metadata(parent.join("ru.big.town.anative")).is_err());
                    assert_eq!(
                        fs::read_to_string(parent.join("ru.big.town.other/settings")).unwrap(),
                        "keep"
                    );
                }
            }
        }
    }

    #[test]
    fn native_cleanup_rejects_failed_or_ineffective_removal() {
        for failure in ["error", "retained"] {
            let directory = tempfile::tempdir().unwrap();
            let native = directory.path().join("data/user/0/ru.big.town.anative");
            fs::create_dir_all(&native).unwrap();
            fs::write(native.join("settings"), "old settings").unwrap();
            let script = REMOVE_NATIVE_DATA
                .replace("/data/", &format!("{}/data/", directory.path().display()));
            let fixture = "rm() { [ \"$TEST_FAILURE\" = retained ]; }";
            let output = Command::new("/bin/sh")
                .args(["-c", &format!("{fixture}\n{script}")])
                .env("TEST_FAILURE", failure)
                .output()
                .unwrap();
            assert!(!output.status.success(), "{failure}: {output:?}");
            assert!(native.join("settings").exists());
        }
    }
}
