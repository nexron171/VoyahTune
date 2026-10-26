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
            "/data/local/bin/app_client.js",
            "/data/local/bin/fullscreen_client.js",
            "/data/local/bin/fullscreen_client.js.voyahtune.new",
            "/data/local/tmp/voyahtune_app_client.old",
        ];
        if legacy_present {
            for path in retired {
                let file = root.join("device").join(path.trim_start_matches('/'));
                fs::create_dir_all(file.parent().unwrap()).unwrap();
                fs::write(file, b"legacy client").unwrap();
            }
        }

        let destination = "/data/local/bin/voyahtune-new/app-client.js";
        let client = b"current app client";
        fs::write(root.join("client.js"), client).unwrap();
        let mut payload = Payload {
            root: root.clone(),
            manifest: serde_json::from_value(json!({
                "schema": 4,
                "infrastructure": "unified",
                "product": "VoyahTune",
                "releaseVersion": "4.4.0",
                "buildRevision": "test",
                "artifacts": [{
                    "name": "client.js", "path": "client.js", "size": client.len(),
                    "sha256": payload::sha256(&root.join("client.js")).unwrap()
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
                artifact: "client.js".into(),
                destination: destination.into(),
                mode: 0o644,
                phase: crate::recipe::Phase::Files,
            });
        }
        let engine = Engine::new(
            &adb_path,
            payload,
            "CAR-001",
            &root.join("logs"),
            Arc::new(|_| {}),
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
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
