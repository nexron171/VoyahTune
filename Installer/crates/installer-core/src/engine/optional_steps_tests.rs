use super::*;
use crate::events::Event;
use std::{os::unix::fs::PermissionsExt, sync::Mutex};

fn fixture(root: &Path, scenario: &str) -> (Engine, Arc<Mutex<Vec<Event>>>) {
    let adb_path = root.join("adb");
    fs::write(root.join("scenario"), scenario).unwrap();
    fs::write(
        &adb_path,
        r#"#!/bin/sh
set -eu
root=$(dirname "$0")
if [ "$1" = -s ]; then shift 2; fi
if [ "$1" = shell ] && [ "$2" = sh ]; then
    cat >/dev/null
    case "$(cat "$root/scenario")" in
        properties-failure) echo 'Cannot read model properties' >&2; exit 1 ;;
        empty-model) exit 0 ;;
        other-model) echo H97C ;;
        qconn-failure) echo H97X ;;
        *) exit 92 ;;
    esac
else
    printf 'QNX connection failed\n'
    exit 1
fi
"#,
    )
    .unwrap();
    fs::set_permissions(&adb_path, fs::Permissions::from_mode(0o755)).unwrap();
    let payload = Payload {
        root: root.into(),
        manifest: serde_json::from_value(json!({
            "schema": 4,
            "infrastructure": "unified",
            "product": "VoyahTune",
            "releaseVersion": "4.4.1",
            "buildRevision": "test",
            "artifacts": [],
            "recipe": {
                "schema": 3,
                "engine": "qinggan-v3",
                "files": [],
                "packages": [],
                "removeFiles": [],
                "removeDirectories": [],
                "removePrefixes": [],
                "removePackages": []
            }
        }))
        .unwrap(),
    };
    let events = Arc::new(Mutex::new(Vec::new()));
    let captured = events.clone();
    let engine = Engine::new(
        &adb_path,
        payload,
        "CAR-001",
        &root.join("logs"),
        Arc::new(move |event| captured.lock().unwrap().push(event.clone())),
        Arc::new(AtomicBool::new(false)),
    )
    .unwrap();
    (engine, events)
}

#[test]
fn navigation_font_failures_only_log_warnings_and_allow_the_next_step() {
    for (scenario, expected_detail) in [
        ("properties-failure", "Cannot read model properties"),
        ("empty-model", "Empty model properties"),
        ("qconn-failure", "QNX connection failed"),
        ("other-model", ""),
    ] {
        for action in ["inspect", "apply", "restore"] {
            let directory = tempfile::tempdir().unwrap();
            let (mut engine, events) = fixture(directory.path(), scenario);
            let step_id = if action == "inspect" {
                "navigation-fonts-check"
            } else {
                "navigation-fonts"
            };
            engine
                .optional_step(step_id, "Шрифты приборки", |engine| {
                    engine.navigation_fonts(action)
                })
                .unwrap();
            let completed = engine.operation.dir.join("next-step-completed");
            engine
                .step("next", "Следующий шаг", |_| {
                    fs::write(&completed, b"completed")?;
                    Ok(())
                })
                .unwrap();

            assert!(completed.exists());
            let events = events.lock().unwrap();
            assert!(events.iter().any(|event| {
                event.kind == "step-completed" && event.step_id.as_deref() == Some(step_id)
            }));
            let warnings: Vec<_> = events
                .iter()
                .filter(|event| event.kind == "diagnostic-warning")
                .collect();
            if scenario == "other-model" {
                assert!(warnings.is_empty());
                assert!(events.iter().any(|event| event.data["skipped"] == true));
            } else {
                assert_eq!(warnings.len(), 1, "{scenario}/{action}");
                assert!(warnings[0].data["detail"]
                    .as_str()
                    .unwrap()
                    .contains(expected_detail));
                let journal =
                    fs::read_to_string(engine.operation.dir.join("events.jsonl")).unwrap();
                assert!(journal.contains(expected_detail));
            }
            assert!(!events.iter().any(|event| matches!(
                event.kind.as_str(),
                "step-failed" | "step-warning" | "step-paused" | "operation-failed"
            )));
        }
    }
}

#[test]
fn optional_step_diagnostic_write_errors_do_not_fail_the_step() {
    let directory = tempfile::tempdir().unwrap();
    let (mut engine, events) = fixture(directory.path(), "other-model");
    let report = engine.operation.dir.join("navigation-fonts-apply.json");
    fs::create_dir(&report).unwrap();

    engine
        .optional_step("navigation-fonts", "Шрифты приборки", |_| {
            recovery::write_json(&report, &json!({"ok": true}))
        })
        .unwrap();

    let events = events.lock().unwrap();
    assert!(events.iter().any(|event| {
        event.kind == "diagnostic-warning" && !event.data["detail"].as_str().unwrap().is_empty()
    }));
    assert_eq!(events.last().unwrap().kind, "step-completed");
}

#[test]
fn optional_steps_preserve_explicit_cancellation() {
    let directory = tempfile::tempdir().unwrap();
    let (mut engine, events) = fixture(directory.path(), "other-model");
    engine.cancel.store(true, Ordering::Relaxed);
    let error = engine
        .optional_step("navigation-fonts", "Шрифты приборки", |_| {
            panic!("Cancelled operation must not run")
        })
        .unwrap_err();

    assert_eq!(error.code, "CANCELLED");
    assert!(events.lock().unwrap().is_empty());
}

#[test]
fn required_step_errors_still_stop_the_operation() {
    let directory = tempfile::tempdir().unwrap();
    let (mut engine, events) = fixture(directory.path(), "other-model");
    let error = engine
        .step("files", "Установка файлов", |_| {
            Err(Error::new("WRITE_FAILED", "Запись не выполнена"))
        })
        .unwrap_err();

    assert_eq!(error.code, "WRITE_FAILED");
    assert_eq!(events.lock().unwrap().last().unwrap().kind, "step-failed");
}
