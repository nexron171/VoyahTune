use super::*;
use std::os::unix::fs::PermissionsExt;

#[test]
fn client_files_are_installed_and_retired_files_are_removed() {
    for legacy_present in [false, true] {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().canonicalize().unwrap();
        let adb_path = root.join("adb");
        fs::write(
            &adb_path,
            r#"#!/bin/sh
set -eu
root=$(dirname "$0")
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
    push) cp "$2" "$root/device$3" ;;
    shell)
        script=$(cat)
        printf '%s\n' "$script" >> "$root/commands"
        case "$script" in
            *'chmod 00755 /data/local/bin'*)
                mkdir -p "$root/device/data/local/bin" "$root/device/data/local/tmp"
                exit 0 ;;
        esac
        {
            printf '%s\n' 'chown() { :; }' 'am() { :; }' 'settings() { echo null; }'
            printf '%s\n' "$script" | sed "s|/data/|$root/device/data/|g"
        } > "$root/command.sh"
        sh "$root/command.sh" ;;
    *) exit 91 ;;
esac
"#,
        )
        .unwrap();
        fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();

        let retired = [
            "/data/local/bin/app_java-agent.dex",
            "/data/local/bin/fullscreen_java-agent.dex",
            "/data/local/bin/fullscreen_java-agent.dex.voyahtune.new",
            "/data/local/tmp/voyahtune_app_client.old",
        ];
        if legacy_present {
            for path in retired {
                let file = root.join("device").join(path.trim_start_matches('/'));
                fs::create_dir_all(file.parent().unwrap()).unwrap();
                fs::write(file, b"legacy client").unwrap();
            }
        }

        let destination = "/data/local/bin/voyahtune-new/agents.dex";
        let client = b"current Java agent";
        fs::write(root.join("java-agent.dex"), client).unwrap();
        let mut payload = Payload {
            root: root.clone(),
            manifest: serde_json::from_value(json!({
                "schema": 4,
                "infrastructure": "unified",
                "product": "VoyahTune",
                "releaseVersion": "4.4.0",
                "buildRevision": "test",
                "artifacts": [{
                    "name": "java-agent.dex", "path": "java-agent.dex", "size": client.len(),
                    "sha256": payload::sha256(&root.join("java-agent.dex")).unwrap()
                }],
                "recipe": {
                    "schema": 3, "engine": "qinggan-v3",
                    "directories": [{"path": "/data/local/bin/voyahtune-new", "mode": 493}],
                    "files": [], "packages": [], "removeFiles": retired,
                    "removeDirectories": [], "removePrefixes": [], "removePackages": []
                }
            }))
            .unwrap(),
        };
        {
            payload.manifest.recipe.files.push(crate::recipe::CopyFile {
                artifact: "java-agent.dex".into(),
                destination: destination.into(),
                mode: 0o644,
                phase: crate::recipe::Phase::Files,
            });
        }
        let mut engine = Engine::new(
            &adb_path,
            payload,
            "CAR-001",
            &root.join("logs"),
            Arc::new(|_| {}),
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
        let mut maintenance = RuntimeMaintenance::new(RuntimeOperation::Install);
        for step in [RuntimeStep::Block, RuntimeStep::Stop] {
            maintenance.execute(step, || Ok::<_, Error>(())).unwrap();
        }
        engine.runtime_maintenance = Some(maintenance);
        let result = engine.recipe_files();
        result.unwrap();
        {
            assert_eq!(
                fs::read(
                    root.join("device")
                        .join(destination.trim_start_matches('/'))
                )
                .unwrap(),
                client
            );
            for path in retired {
                if path != destination {
                    assert!(!root
                        .join("device")
                        .join(path.trim_start_matches('/'))
                        .exists());
                }
            }
        }
    }
}

#[test]
fn destination_or_staging_symlink_is_rejected_before_adb_push() {
    use std::os::unix::fs::symlink;
    for linked in ["destination", "staging", "parent"] {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().canonicalize().unwrap();
        fs::create_dir_all(root.join("device/data/local/bin/runtime")).unwrap();
        fs::create_dir(root.join("external")).unwrap();
        fs::write(root.join("external/user.dex"), b"user bytes").unwrap();
        let destination = "/data/local/bin/runtime/agents.dex";
        let staging = "/data/local/bin/runtime/agents.dex.voyahtune.new";
        let runtime = root.join("device/data/local/bin/runtime");
        match linked {
            "destination" => {
                symlink(root.join("external/user.dex"), runtime.join("agents.dex")).unwrap()
            }
            "staging" => symlink(
                root.join("external/user.dex"),
                runtime.join("agents.dex.voyahtune.new"),
            )
            .unwrap(),
            _ => {
                fs::remove_dir(&runtime).unwrap();
                symlink(root.join("external"), &runtime).unwrap();
            }
        }
        let adb = root.join("adb");
        fs::write(
            &adb,
            r#"#!/bin/sh
set -eu
root=$(dirname "$0")
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
    push) echo write >> "$root/writes"; cp "$2" "$root/device$3" ;;
    shell)
        { printf '%s\n' 'chown() { :; }' 'restorecon() { :; }' 'sync() { :; }'
          cat | sed "s|/data/|$root/device/data/|g"
        } > "$root/script.sh"
        sh "$root/script.sh" ;;
    *) exit 91 ;;
esac
"#,
        )
        .unwrap();
        fs::set_permissions(&adb, fs::Permissions::from_mode(0o755)).unwrap();
        let payload = Payload {
            root: root.clone(),
            manifest: serde_json::from_value(json!({
                "schema": 4, "infrastructure": "unified", "product": "VoyahTune",
                "releaseVersion": "4.4.1", "buildRevision": "test", "artifacts": []
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
        fs::write(root.join("source.dex"), b"new agent").unwrap();
        assert!(
            engine
                .push_file(&root.join("source.dex"), staging, destination, 0o644, false)
                .is_err(),
            "{linked}"
        );
        assert!(!root.join("writes").exists(), "ADB wrote through {linked}");
        assert_eq!(
            fs::read(root.join("external/user.dex")).unwrap(),
            b"user bytes"
        );
    }
}
