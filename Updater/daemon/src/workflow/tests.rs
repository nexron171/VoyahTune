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
    let common = release_core::infrastructure::Infrastructure::compiled()
        == release_core::infrastructure::Infrastructure::Unified;
    for phase in ["idle", "verified"] {
        let below_minimum = selected_runtime(
            "4.1.0-od",
            if common { "4.2.0" } else { "4.2.0-od" },
            "4.2.0",
        );
        below_minimum.lock().unwrap().state.phase = phase.into();
        assert!(selected(&below_minimum)
            .unwrap_err()
            .to_string()
            .contains("OTA_USB_REQUIRED"));

        let downgrade = selected_runtime(
            "4.2.0-od",
            if common { "4.1.0" } else { "4.1.0-od" },
            "4.0.0",
        );
        downgrade.lock().unwrap().state.phase = phase.into();
        assert!(selected(&downgrade).is_ok());
    }
}

#[test]
fn only_legacy_od_skips_payload_and_catalog_validation() {
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
    let result = verify_payload(&payload, &claims);
    if release_core::infrastructure::Infrastructure::compiled()
        == release_core::infrastructure::Infrastructure::Od
    {
        result.unwrap();
    } else {
        assert!(result.is_err());
    }
}

#[test]
fn payload_opening_skips_metadata_validation_only_for_od() {
    use release_core::{
        compatibility::Requirements, infrastructure::Infrastructure, payload::Manifest,
        recipe::Recipe,
    };

    let directory = tempfile::tempdir().unwrap();
    let infrastructure = Infrastructure::compiled();
    let mut requirements = if infrastructure == Infrastructure::Unified {
        Requirements::unified()
    } else {
        Requirements::infrastructure()
    };
    requirements.min_installer_version = "99.0.0".into();
    let manifest = Manifest {
        infrastructure,
        removal_only: false,
        requirements: Some(requirements),
        recipe: Recipe::default(),
        schema: 4,
        product: "VoyahTune".into(),
        release_version: if infrastructure == Infrastructure::Unified {
            "4.3.0".into()
        } else {
            format!("4.3.0-{}", infrastructure.as_str())
        },
        build_revision: "test".into(),
        artifacts: Vec::new(),
    };
    fs::write(
        directory.path().join("manifest.json"),
        serde_json::to_vec(&manifest).unwrap(),
    )
    .unwrap();

    match infrastructure {
        Infrastructure::Unified => {
            let error = open_ota_payload(directory.path())
                .err()
                .unwrap()
                .to_string();
            assert!(error.contains("INFRASTRUCTURE_RECIPE"), "{error}");
        }
        Infrastructure::Od => assert!(open_ota_payload(directory.path()).is_ok()),
        Infrastructure::Pi => {
            let error = open_ota_payload(directory.path())
                .err()
                .unwrap()
                .to_string();
            assert!(error.contains("INSTALLER_UPDATE_REQUIRED"), "{error}");
        }
    }
}
