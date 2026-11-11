use super::*;
use std::os::unix::fs::PermissionsExt;

#[test]
fn system_updater_signer_reset_reboots_without_resetting_vehicle_apps() {
    for mode in [
        "success",
        "signature",
        "remount-reboot",
        "space",
        "reboot-failure",
        "stale-registration",
    ] {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().canonicalize().unwrap();
        let adb = root.join("adb");
        fs::write(root.join("mode"), mode).unwrap();
        fs::create_dir_all(root.join("device/system/priv-app/VoyahTuneUpdater")).unwrap();
        fs::write(root.join("registered"), "old-key").unwrap();
        fs::write(root.join("boot"), "boot-before").unwrap();
        let runtime = root.join("device/data/local/bin");
        fs::create_dir_all(&runtime).unwrap();
        fs::write(runtime.join("voyahtune-update.block"), "maintenance").unwrap();
        fs::write(
            runtime.join("voyahtune-native-migration.boot"),
            "boot-before",
        )
        .unwrap();
        fs::write(
            root.join("device/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk"),
            "new-key",
        )
        .unwrap();
        fs::write(root.join("updater.apk"), "new-key").unwrap();
        fs::write(&adb, r#"#!/bin/sh
set -eu
root=$(dirname "$0")
mode=$(cat "$root/mode")
if [ "$1" = -s ]; then shift 2; fi
printf '%s\n' "$*" >> "$root/commands"
case "$1" in
    install)
        case "$mode" in
            success) echo Success; exit 0 ;;
            space) echo 'Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]' >&2; exit 1 ;;
        esac
        if [ -e "$root/registered" ]; then
            echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' >&2
            exit 1
        fi
        echo Success ;;
    uninstall) echo 'Failure [DELETE_FAILED_INTERNAL_ERROR]' >&2; exit 1 ;;
    reboot)
        test ! -e "$root/device/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk"
        [ "$mode" != reboot-failure ] || exit 1
        [ "$mode" = stale-registration ] || rm -f "$root/registered"
        count=$(cat "$root/reboots" 2>/dev/null || echo 0)
        count=$((count + 1))
        echo "$count" > "$root/reboots"
        echo "boot-after-$count" > "$root/boot" ;;
    push) cp "$2" "$root/device$3" ;;
    shell)
        script=$(cat)
        printf '%s\n' "$script" >> "$root/commands"
        case "$script" in
            *'VOYAHTUNE_BIN_DIRECTORY'*)
                {
                    printf '%s\n' 'chown() { :; }' 'sync() { :; }'
                    printf '%s\n' "$script"
                } | VOYAHTUNE_BIN_DIRECTORY="$root/device/data/local/bin" VOYAHTUNE_BOOT_SOURCE="$root/boot" sh ;;
            *'/system/.ovw_rwtest'*)
                if [ "$mode" = remount-reboot ] && [ "$(cat "$root/reboots")" = 1 ]; then
                    echo RO
                else
                    echo RW
                fi ;;
            *'pm uninstall ru.big.town.updater'*)
                {
                    printf '%s\n' 'pm() { case "$1" in uninstall) return 1 ;; path) echo package:/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk ;; esac; }'
                    printf '%s\n' "$script" | sed "s|/system/priv-app/|$root/device/system/priv-app/|g"
                } | sh ;;
            *'cat /proc/sys/kernel/random/boot_id'*) cat "$root/boot" ;;
            *'pm path ru.big.town.updater'*) test ! -e "$root/registered" ;;
            *'getprop sys.boot_completed'*) echo 1 ;;
            *'getprop init.svc.voyahtune_updater'*) echo stopped ;;
            *'mv -f'*'VoyahTuneUpdater.apk'*)
                {
                    printf '%s\n' 'chown() { :; }' 'restorecon() { :; }'
                    printf '%s\n' "$script" | sed "s|/system/priv-app/|$root/device/system/priv-app/|g"
                } | sh ;;
            *) echo RW ;;
        esac ;;
    root|wait-for-device|disable-verity|remount) : ;;
    *) exit 93 ;;
esac
"#).unwrap();
        fs::set_permissions(&adb, fs::Permissions::from_mode(0o755)).unwrap();
        let payload = Payload {
            root: root.clone(),
            manifest: serde_json::from_value(json!({
                "schema": 4, "infrastructure": "unified", "product": "VoyahTune",
                "releaseVersion": "4.7.1", "buildRevision": "test",
                "artifacts": [{"name": "voyahtune-updater.apk", "path": "updater.apk", "size": 7, "sha256": "0".repeat(64)}]
            }))
            .unwrap(),
        };
        let engine = Engine::new(
            &adb,
            payload,
            "CAR-001",
            &root.join("logs"),
            Arc::new(|_| {}),
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
        let result = engine.install_updater();
        assert_eq!(
            result.is_ok(),
            matches!(mode, "success" | "signature" | "remount-reboot"),
            "{mode}: {result:?}"
        );
        if result.is_ok() {
            assert_eq!(
                fs::read_to_string(
                    root.join("device/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk")
                )
                .unwrap(),
                "new-key"
            );
        }
        if matches!(mode, "signature" | "remount-reboot" | "stale-registration") {
            assert!(runtime.join("voyahtune-update.block").is_file());
            let boot = fs::read_to_string(root.join("boot")).unwrap();
            assert_ne!(boot.trim(), "boot-before");
            assert_eq!(
                fs::read_to_string(runtime.join("voyahtune-native-migration.boot")).unwrap(),
                boot,
                "The current boot must remain blocked after clearing update.block before the final reboot"
            );
        }
        let commands = fs::read_to_string(root.join("commands")).unwrap();
        assert_eq!(
            commands.lines().filter(|line| *line == "reboot").count(),
            if mode == "remount-reboot" {
                2
            } else {
                usize::from(!matches!(mode, "success" | "space"))
            }
        );
        assert!(!commands.contains("ru.big.town.anative"));
        assert!(!commands.contains("ru.big.town.restoremode"));
        assert_eq!(
            commands
                .lines()
                .filter(|line| line.starts_with("install "))
                .count(),
            if matches!(mode, "signature" | "remount-reboot") {
                2
            } else {
                1
            }
        );
    }
}
