use super::*;
use release_core::catalog::UpdateCatalog;

fn selected_runtime(installed: &str, target: &str, minimum: &str) -> Shared {
    let catalog = serde_json::from_value::<UpdateCatalog>(serde_json::json!({
        "releases": [{
            "version": target,
            "url": "https://example.org/payload.zip",
            "size": 1,
            "sha256": "a".repeat(64),
            "minimumInstallerVersion": "1.7.11",
            "minimumOtaVersion": minimum
        }]
    }))
    .unwrap()
    .resolve()
    .unwrap();
    let mut state = State::fresh(installed.into(), "test-firmware".into());
    state.selected = catalog.releases.into_iter().next();
    state.same_version = true;
    Arc::new(Mutex::new(Runtime {
        config: Ok(Config::default()),
        state,
    }))
}

#[test]
fn persisted_selection_is_rechecked_before_download_and_apply() {
    if !crate::restore_ui::enabled() {
        return;
    }
    for phase in ["idle", "verified"] {
        let below_minimum = selected_runtime("4.1.0-od", "4.2.0-od", "4.2.0");
        below_minimum.lock().unwrap().state.phase = phase.into();
        assert!(selected(&below_minimum)
            .unwrap_err()
            .to_string()
            .contains("OTA_USB_REQUIRED"));

        let downgrade = selected_runtime("4.2.0-od", "4.1.0-od", "4.0.0");
        downgrade.lock().unwrap().state.phase = phase.into();
        assert!(selected(&downgrade)
            .unwrap_err()
            .to_string()
            .contains("OTA не поддерживает этот путь обновления"));
    }
}

#[test]
fn catalog_version_cannot_hide_an_older_payload() {
    if !crate::restore_ui::enabled() {
        return;
    }
    let payload = Payload {
        root: std::path::PathBuf::from("unused"),
        manifest: serde_json::from_value(serde_json::json!({
            "schema": 4,
            "infrastructure": "od",
            "product": "VoyahTune",
            "releaseVersion": "4.1.0-od",
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
    let claims = ota::Claims {
        version: "4.2.0-od".into(),
        archive_sha256: "a".repeat(64),
        archive_size: 1,
    };
    assert!(verify_payload(&payload, &claims)
        .unwrap_err()
        .to_string()
        .contains("Manifest не соответствует выбранному релизу"));
}

#[test]
fn payload_opening_ignores_installer_minimum_only_for_od() {
    use release_core::{
        compatibility::Requirements, infrastructure::Infrastructure, payload::Manifest,
        recipe::Recipe,
    };

    let directory = tempfile::tempdir().unwrap();
    let infrastructure = Infrastructure::compiled();
    let mut requirements = Requirements::infrastructure();
    requirements.min_installer_version = "99.0.0".into();
    let manifest = Manifest {
        infrastructure,
        removal_only: false,
        requirements: Some(requirements),
        recipe: Recipe::default(),
        schema: 4,
        product: "VoyahTune".into(),
        release_version: format!("4.3.0-{}", infrastructure.as_str()),
        build_revision: "test".into(),
        artifacts: Vec::new(),
    };
    fs::write(
        directory.path().join("manifest.json"),
        serde_json::to_vec(&manifest).unwrap(),
    )
    .unwrap();

    let error = open_ota_payload(directory.path())
        .err()
        .unwrap()
        .to_string();
    let expected = match infrastructure {
        Infrastructure::Od => "PAYLOAD_MISSING",
        Infrastructure::Pi => "INSTALLER_UPDATE_REQUIRED",
    };
    assert!(error.contains(expected), "{error}");
}
