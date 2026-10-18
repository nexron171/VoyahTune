use super::*;
use crate::{
    catalog::UpdateCatalog,
    compatibility::{Requirements, INSTALLER_VERSION},
    infrastructure::Infrastructure,
    recipe::Recipe,
};

fn future_installer_manifest() -> Manifest {
    let mut requirements = Requirements::restoremode_ota();
    requirements.min_installer_version = "99.0.0".into();
    Manifest {
        infrastructure: Infrastructure::Od,
        removal_only: false,
        requirements: Some(requirements),
        recipe: Recipe::default(),
        schema: MANIFEST_SCHEMA,
        product: "VoyahTune".into(),
        release_version: "4.3.0-od".into(),
        build_revision: "test".into(),
        artifacts: Vec::new(),
    }
}

fn write_manifest(directory: &Path, manifest: &Manifest) {
    crate::storage::write_json(&directory.join("manifest.json"), manifest).unwrap();
}

#[test]
fn installer_minimum_blocks_desktop_but_not_od_ota() {
    let directory = tempfile::tempdir().unwrap();
    let manifest = future_installer_manifest();
    write_manifest(directory.path(), &manifest);

    assert_eq!(
        Payload::load(directory.path()).err().unwrap().code,
        "INSTALLER_UPDATE_REQUIRED"
    );
    let payload = Payload::load_for_target(directory.path(), ValidationTarget::OdOta).unwrap();
    assert_eq!(
        payload.manifest.requirements.unwrap().min_installer_version,
        "99.0.0"
    );
}

#[test]
fn od_ota_still_checks_artifact_integrity() {
    let directory = tempfile::tempdir().unwrap();
    let mut manifest = future_installer_manifest();
    let artifact_path = directory.path().join("native.apk");
    std::fs::write(&artifact_path, b"original").unwrap();
    manifest.artifacts.push(Artifact {
        name: "native.apk".into(),
        path: "native.apk".into(),
        sha256: sha256(&artifact_path).unwrap(),
        size: 8,
    });
    write_manifest(directory.path(), &manifest);
    std::fs::write(&artifact_path, b"tampered").unwrap();

    assert_eq!(
        Payload::open_for_od_ota(directory.path())
            .err()
            .unwrap()
            .code,
        "PAYLOAD_HASH"
    );
}

#[test]
fn od_ota_reports_unsupported_capabilities_and_schema_as_service_requirements() {
    let directory = tempfile::tempdir().unwrap();
    let mut manifest = future_installer_manifest();
    manifest
        .requirements
        .as_mut()
        .unwrap()
        .required_capabilities
        .push("future-payload-operation".into());
    write_manifest(directory.path(), &manifest);
    let error = Payload::open_for_od_ota(directory.path()).err().unwrap();
    assert_eq!(error.code, "OTA_USB_REQUIRED");
    assert_eq!(error.detail, "future-payload-operation");

    for (schema, expected) in [(3, "PAYLOAD_SCHEMA_UNSUPPORTED"), (99, "OTA_USB_REQUIRED")] {
        std::fs::write(
            directory.path().join("manifest.json"),
            format!("{{\"schema\":{schema}}}"),
        )
        .unwrap();
        assert_eq!(
            Payload::open_for_od_ota(directory.path())
                .err()
                .unwrap()
                .code,
            expected
        );
    }
}

#[test]
fn od_ota_keeps_recipe_and_required_metadata_validation() {
    let directory = tempfile::tempdir().unwrap();
    let mut manifest = future_installer_manifest();
    manifest.recipe.files[0].destination = "/system/bin/sh".into();
    write_manifest(directory.path(), &manifest);
    assert_eq!(
        Payload::open_for_od_ota(directory.path())
            .err()
            .unwrap()
            .code,
        "RECIPE_INVALID"
    );

    manifest = future_installer_manifest();
    manifest.requirements = None;
    write_manifest(directory.path(), &manifest);
    assert_eq!(
        Payload::open_for_od_ota(directory.path())
            .err()
            .unwrap()
            .code,
        "REQUIREMENTS_MISSING"
    );
}

#[test]
fn od_ota_entry_point_rejects_pi_payload_and_desktop_keeps_its_minimum() {
    let directory = tempfile::tempdir().unwrap();
    let mut manifest = future_installer_manifest();
    manifest.infrastructure = Infrastructure::Pi;
    manifest.release_version = "4.3.0-pi".into();
    write_manifest(directory.path(), &manifest);

    assert_eq!(
        Payload::open_for_od_ota(directory.path())
            .err()
            .unwrap()
            .code,
        "INFRASTRUCTURE_MISMATCH"
    );
    assert_eq!(
        Payload::open(directory.path()).err().unwrap().code,
        "INSTALLER_UPDATE_REQUIRED"
    );
}

#[test]
fn catalog_minimum_versions_are_independent_for_each_installation_method() {
    for (installer_minimum, ota_minimum, desktop_allowed, ota_allowed) in [
        ("99.0.0", "4.2.0", false, true),
        ("0.1.0", "99.0.0", true, false),
    ] {
        let mut catalog = serde_json::from_value::<UpdateCatalog>(serde_json::json!({
            "releases": [{
                "version": "4.3.0-od",
                "url": "https://example.org/payload.zip",
                "size": 1,
                "sha256": "a".repeat(64),
                "minimumInstallerVersion": installer_minimum,
                "minimumOtaVersion": ota_minimum
            }]
        }))
        .unwrap()
        .resolve()
        .unwrap();
        let release = catalog.releases.remove(0);
        assert_eq!(
            release.require_installer_version(INSTALLER_VERSION).is_ok(),
            desktop_allowed
        );
        assert_eq!(release.require_ota_version("4.2.0-od").is_ok(), ota_allowed);
    }
}
