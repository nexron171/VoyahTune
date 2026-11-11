use release_core::{payload::Payload, recipe::Recipe};
use std::{fs, io::Write, path::Path};

fn fixture(root: &Path, format: u32) {
    fixture_with_readiness(root, format, Some(1));
}

fn fixture_with_readiness(root: &Path, format: u32, readiness: Option<u32>) {
    fs::create_dir_all(root.join("common")).unwrap();
    let recipe = Recipe::default();
    for name in recipe
        .files
        .iter()
        .map(|f| f.artifact.as_str())
        .chain(recipe.packages.iter().map(|p| p.artifact.as_str()))
        .chain(["dns-helper.sh", "dns.apk", "init.logcat.original.sh"])
    {
        if !name.ends_with("-agents.json") && !name.ends_with("-runtime-manifest.json") {
            fs::write(root.join("common").join(name), name).unwrap();
        }
    }
    let file = fs::File::create(root.join("common/restore_mode.apk")).unwrap();
    let mut apk = zip::ZipWriter::new(file);
    apk.start_file(
        "assets/voyahtune-build.json",
        zip::write::SimpleFileOptions::default(),
    )
    .unwrap();
    let mut metadata = serde_json::json!({
        "schema":3,"product":"VoyahTune","component":"ru.big.town.restoremode",
        "infrastructure":"unified","releaseVersion":"4.4.1-beta","buildRevision":"same-label-build",
        "payloadFormat":format,"runtimeHashes":{}
    });
    if let Some(schema) = readiness {
        metadata["runtimeReadinessSchema"] = schema.into();
    }
    apk.write_all(serde_json::to_vec(&metadata).unwrap().as_slice())
        .unwrap();
    apk.finish().unwrap();
}

#[test]
fn fixed_payload_loads_without_external_manifests() {
    let root = tempfile::tempdir().unwrap();
    fixture(root.path(), 5);
    let payload = Payload::load(root.path()).unwrap();
    assert_eq!(payload.manifest.release_version, "4.4.1-beta");
    assert_eq!(payload.manifest.build_revision, "same-label-build");
    assert!(payload
        .file("voyahtune-new-voyahtune-inject-arm")
        .unwrap()
        .is_file());
    assert!(payload
        .manifest
        .artifacts
        .iter()
        .all(|a| !a.name.ends_with("manifest.json") && !a.name.ends_with("-agents.json")));
    assert!(!root.path().join("manifest.json").exists());
}

#[test]
fn usb_payload_requires_updater_apk() {
    let root = tempfile::tempdir().unwrap();
    fixture(root.path(), 5);
    fs::remove_file(root.path().join("common/voyahtune-updater.apk")).unwrap();
    assert!(Payload::load(root.path()).is_err());
}

#[test]
fn usb_rejects_previous_or_unknown_runtime_readiness_before_installation() {
    for schema in [None, Some(2)] {
        let root = tempfile::tempdir().unwrap();
        fixture_with_readiness(root.path(), 5, schema);
        assert_eq!(
            Payload::load(root.path()).err().unwrap().code,
            "PAYLOAD_RUNTIME_PROTOCOL"
        );
    }
}

#[test]
fn future_format_requires_updated_installer() {
    let root = tempfile::tempdir().unwrap();
    fixture(root.path(), 99);
    assert_eq!(
        Payload::load(root.path()).err().unwrap().code,
        "INSTALLER_UPDATE_REQUIRED"
    );
}

#[test]
fn legacy_payload_formats_remain_unsupported() {
    for format in [0, 3, 4] {
        let root = tempfile::tempdir().unwrap();
        fixture(root.path(), format);
        assert_eq!(
            Payload::load(root.path()).err().unwrap().code,
            "PAYLOAD_SCHEMA_UNSUPPORTED"
        );
    }
}

#[test]
fn rebuilt_beta_has_a_different_content_identity() {
    let root = tempfile::tempdir().unwrap();
    fixture(root.path(), 5);
    let first = Payload::load(root.path()).unwrap();
    let first_id = first.identity().unwrap();
    fs::write(
        root.path().join("common/voyahtune-new-agents.dex"),
        "new agent code",
    )
    .unwrap();
    let second = Payload::load(root.path()).unwrap();
    assert_eq!(
        first.manifest.release_version,
        second.manifest.release_version
    );
    assert_ne!(first_id, second.identity().unwrap());
}

#[test]
fn ota_uses_fixed_plan_without_metadata_compatibility_gates() {
    let root = tempfile::tempdir().unwrap();
    fixture_with_readiness(root.path(), 99, None);
    let payload = Payload::for_ota(root.path()).unwrap();
    let loader = payload
        .manifest
        .recipe
        .files
        .iter()
        .find(|file| file.artifact == "voyahtune-new-voyahtune-loader")
        .unwrap();
    assert_eq!(
        loader.destination,
        "/data/local/bin/voyahtune-new/voyahtune-loader"
    );
    assert_eq!(loader.mode, 0o755);
    assert_eq!(
        payload.file("native.apk").unwrap(),
        root.path()
            .join("common/native.apk")
            .canonicalize()
            .unwrap()
    );
    fs::write(
        root.path().join("common/restore_mode.apk"),
        "incomplete APK",
    )
    .unwrap();
    assert!(Payload::for_ota(root.path()).is_ok());
}

#[test]
fn recovery_remains_usable_after_original_payload_is_deleted() {
    let source = tempfile::tempdir().unwrap();
    fixture(source.path(), 5);
    let recovery = tempfile::tempdir().unwrap();
    let payload = Payload::load(source.path()).unwrap();
    let original_helper = fs::read(payload.file("dns-helper.sh").unwrap()).unwrap();
    payload
        .save_removal(&recovery.path().join("payload"))
        .unwrap();
    drop(payload);
    source.close().unwrap();
    let saved = Payload::open(&recovery.path().join("payload")).unwrap();
    assert!(saved.manifest.removal_only);
    assert_eq!(saved.manifest.artifacts.len(), 2);
    assert_eq!(
        fs::read(saved.file("dns-helper.sh").unwrap()).unwrap(),
        original_helper
    );
    assert!(saved
        .manifest
        .recipe
        .remove_directories
        .contains(&"/data/local/bin/voyahtune-new".into()));
}
