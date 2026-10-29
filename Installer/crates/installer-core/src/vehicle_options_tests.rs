use super::*;
use crate::events::Events;
use std::{fs, os::unix::fs::PermissionsExt};

struct Device {
    directory: tempfile::TempDir,
    adb: Adb,
}

impl Device {
    fn new() -> Self {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        let adb_path = root.join("adb");
        fs::write(
            &adb_path,
            r#"#!/usr/bin/env python3
import os
import re
import subprocess
import sys
from pathlib import Path

base = Path(__file__).resolve().parent
script = sys.stdin.read()
if 'getprop ' in script:
    if (base / 'properties-error').exists():
        sys.exit(1)
    print((base / 'properties').read_text())
    sys.exit(0)
script = re.sub(r'(?<![\w/])/(system|sdcard|private|tmp|data|fake_login)(?=/|[\s\x27";]|$)',
                lambda match: str(base / 'device' / match[1]), script)
environment = dict(os.environ, PATH=str(base / 'bin') + ':/usr/bin:/bin')
sys.exit(subprocess.run(['/bin/sh', '-s'], input=script, text=True, env=environment).returncode)
"#,
        )
        .unwrap();
        fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
        fs::write(root.join("properties"), "qinggan/free:11/test\nH97C").unwrap();
        fs::create_dir(root.join("bin")).unwrap();
        for (name, script) in [
            ("mount", "#!/bin/sh\nexit 0\n"),
            (
                "chown",
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$(dirname \"$0\")/../owners\"\n",
            ),
            (
                "cp",
                "#!/bin/sh\nif [ -f \"$(dirname \"$0\")/../fail-copy\" ]; then exit 1; fi\nexec /bin/cp \"$@\"\n",
            ),
        ] {
            let path = root.join("bin").join(name);
            fs::write(&path, script).unwrap();
            fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
        }
        for path in [
            "private/configs/token",
            "system/priv-app",
            "sdcard",
            "data/local",
        ] {
            fs::create_dir_all(root.join("device").join(path)).unwrap();
        }
        let adb = Adb::new(&adb_path, Events::quiet())
            .unwrap()
            .with_device("CAR-001")
            .unwrap();
        Self { directory, adb }
    }

    fn path(&self, relative: &str) -> std::path::PathBuf {
        self.directory.path().join("device").join(relative)
    }

    fn write(&self, relative: &str, contents: &str) {
        let path = self.path(relative);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, contents).unwrap();
    }

    fn backup_account(&self) -> std::path::PathBuf {
        self.path("data/local/voyahtune-backups/fake-account/accountInfo")
    }
}

#[test]
fn vehicle_options_exclude_both_h97xy_identities_from_all_model_properties() {
    let device = Device::new();
    for (properties, expected) in [
        ("qinggan/free:11/test\nH97C", true),
        ("jenkins-PRJ_H97x_Platform_8155\nH97C", false),
        ("msmnile_gvmq\nH97YSA8155\nH97C", false),
        ("free-11-chinese-20260731-7.3.1", true),
    ] {
        fs::write(device.directory.path().join("properties"), properties).unwrap();
        assert_eq!(available(&device.adb).unwrap(), expected, "{properties}");
    }
    fs::write(device.directory.path().join("properties"), "\n").unwrap();
    assert!(available(&device.adb).is_err());
    fs::write(device.directory.path().join("properties-error"), "").unwrap();
    assert!(available(&device.adb).is_err());
}

#[test]
fn fake_account_activates_oem_flags_and_preserves_the_original_on_the_car() {
    let device = Device::new();
    let account = "private/configs/token/accountInfo";
    device.write(account, "Original owner account");

    activate_fake_account(&device.adb).unwrap();

    assert!(device.path("fake_login").is_file());
    assert!(device.path("tmp/fake_login").is_file());
    assert_eq!(
        fs::read_to_string(device.path(account)).unwrap(),
        "Owner%+79991234567%1%100001%100001\n"
    );
    assert_eq!(
        fs::metadata(device.path(account))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o444
    );
    assert_eq!(
        fs::read_to_string(device.backup_account()).unwrap(),
        "Original owner account"
    );
    assert_eq!(
        fs::metadata(device.backup_account())
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    let owners = fs::read_to_string(device.directory.path().join("owners")).unwrap();
    assert!(owners
        .lines()
        .any(|line| line.starts_with("0:0 ") && line.ends_with(".accountInfo.voyahtune-new")));

    fs::set_permissions(device.path(account), fs::Permissions::from_mode(0o600)).unwrap();
    device.write(account, "Another account after reinstall");
    activate_fake_account(&device.adb).unwrap();
    assert_eq!(
        fs::read_to_string(device.backup_account()).unwrap(),
        "Original owner account"
    );
}

#[test]
fn fake_account_remembers_an_originally_absent_account() {
    let device = Device::new();
    activate_fake_account(&device.adb).unwrap();
    activate_fake_account(&device.adb).unwrap();
    assert!(!device.backup_account().exists());
    assert!(device
        .path("data/local/voyahtune-backups/fake-account/accountInfo.absent")
        .is_file());
}

#[test]
fn fake_account_backup_failure_keeps_the_account_and_flags_unchanged() {
    let device = Device::new();
    device.write("private/configs/token/accountInfo", "Original account");
    fs::write(device.directory.path().join("fail-copy"), "").unwrap();
    assert!(activate_fake_account(&device.adb).is_err());
    assert_eq!(
        fs::read_to_string(device.path("private/configs/token/accountInfo")).unwrap(),
        "Original account"
    );
    assert!(!device.path("fake_login").exists());
    assert!(!device.path("tmp/fake_login").exists());
    fs::remove_file(device.directory.path().join("fail-copy")).unwrap();
    activate_fake_account(&device.adb).unwrap();
    assert_eq!(
        fs::read_to_string(device.backup_account()).unwrap(),
        "Original account"
    );
}

#[test]
fn navigation_is_removed_only_after_a_complete_persistent_backup() {
    let device = Device::new();
    device.write("system/priv-app/dueros/dueros.apk", "OEM navigation APK");
    device.write(
        "system/priv-app/dueros/lib/arm/libnavigation.so",
        "OEM library",
    );

    disable_chinese_navigation(&device.adb).unwrap();
    assert!(!device.path("system/priv-app/dueros").exists());
    assert_eq!(
        fs::read_to_string(device.path("sdcard/dueros/dueros.apk")).unwrap(),
        "OEM navigation APK"
    );
    assert_eq!(
        fs::read_to_string(device.path("sdcard/dueros/lib/arm/libnavigation.so")).unwrap(),
        "OEM library"
    );

    disable_chinese_navigation(&device.adb).unwrap();
    device.write("system/priv-app/dueros/dueros.apk", "OEM update");
    disable_chinese_navigation(&device.adb).unwrap();
    assert!(!device.path("system/priv-app/dueros").exists());
    assert_eq!(
        fs::read_to_string(device.path("sdcard/dueros/dueros.apk")).unwrap(),
        "OEM navigation APK"
    );
}

#[test]
fn navigation_copy_errors_preserve_the_source_and_allow_a_retry() {
    let device = Device::new();
    device.write("system/priv-app/dueros/dueros.apk", "OEM navigation APK");
    fs::write(device.directory.path().join("fail-copy"), "").unwrap();
    assert!(disable_chinese_navigation(&device.adb).is_err());
    assert!(device.path("system/priv-app/dueros/dueros.apk").is_file());
    assert!(!device.path("sdcard/dueros").exists());

    fs::remove_file(device.directory.path().join("fail-copy")).unwrap();
    device.write(
        "sdcard/.dueros.voyahtune-new/incomplete.apk",
        "partial copy",
    );
    disable_chinese_navigation(&device.adb).unwrap();
    assert!(device.path("sdcard/dueros/dueros.apk").is_file());
    assert!(!device.path("sdcard/dueros/incomplete.apk").exists());
}

#[test]
fn existing_unverified_backups_are_preserved_and_stop_changes() {
    let device = Device::new();
    device.write("system/priv-app/dueros/dueros.apk", "Original navigation");
    device.write("sdcard/dueros/unrelated", "Existing backup");
    assert!(disable_chinese_navigation(&device.adb).is_err());
    assert!(device.path("system/priv-app/dueros/dueros.apk").is_file());
    assert_eq!(
        fs::read_to_string(device.path("sdcard/dueros/unrelated")).unwrap(),
        "Existing backup"
    );

    device.write("private/configs/token/accountInfo", "Original account");
    device.write(
        "data/local/voyahtune-backups/fake-account/unrelated",
        "Existing backup",
    );
    assert!(activate_fake_account(&device.adb).is_err());
    assert_eq!(
        fs::read_to_string(device.path("private/configs/token/accountInfo")).unwrap(),
        "Original account"
    );
}

#[test]
fn navigation_without_an_installed_dueros_is_idempotent() {
    let device = Device::new();
    disable_chinese_navigation(&device.adb).unwrap();
    assert!(!device.path("sdcard/dueros").exists());
}
