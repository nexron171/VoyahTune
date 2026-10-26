use crate::config::invalid;
use release_core::{
    catalog::{Catalog, Release, UpdateRelease},
    infrastructure::{release_version, Infrastructure},
    ota::Claims,
    payload::{Artifact, Manifest, Payload},
    recipe::Recipe,
    Error, Result,
};
use serde::Deserialize;
use std::{fs::File, io, path::Path};

#[derive(Deserialize)]
struct DownloadCatalog {
    releases: Vec<DownloadRelease>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DownloadRelease {
    version: String,
    url: String,
    #[serde(default)]
    size: u64,
    minimum_ota_version: Option<String>,
}

pub fn catalog(bytes: &[u8]) -> io::Result<Catalog> {
    let source: DownloadCatalog =
        serde_json::from_slice(bytes).map_err(|error| invalid(&error.to_string()))?;
    let mut catalog = Catalog::empty();
    catalog.releases = source
        .releases
        .into_iter()
        .map(|release| {
            UpdateRelease {
                version: release.version,
                url: release.url,
                size: release.size,
                sha256: String::new(),
                minimum_installer_version: None,
                minimum_ota_version: release.minimum_ota_version,
            }
            .into_release()
        })
        .collect();
    catalog.releases.sort_by(|left, right| {
        release_version(&right.version)
            .ok()
            .cmp(&release_version(&left.version).ok())
    });
    Ok(catalog)
}

pub fn select(release: &Release, installed: &str) -> Result<Claims> {
    let minimum = release.minimum_ota_version.as_deref().ok_or_else(|| {
        Error::new(
            "OTA_USB_REQUIRED",
            "Для релиза не указана minimumOtaVersion",
        )
    })?;
    let minimum = semver::Version::parse(minimum).map_err(|error| {
        Error::new("OTA_USB_REQUIRED", "Некорректная minimumOtaVersion").detail(error)
    })?;
    let mut current = release_version(installed)?;
    current.pre = semver::Prerelease::EMPTY;
    current.build = semver::BuildMetadata::EMPTY;
    if current < minimum {
        return Err(Error::new(
            "OTA_USB_REQUIRED",
            format!("Для обновления требуется VoyahTune {minimum} или новее. Установите релиз через USB."),
        ));
    }
    Ok(Claims {
        version: release.version.clone(),
        archive_sha256: String::new(),
        archive_size: release.payload.size,
    })
}

#[derive(Deserialize)]
struct ArtifactPath {
    name: String,
    path: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallFiles {
    #[serde(default)]
    release_version: String,
    artifacts: Vec<ArtifactPath>,
    recipe: Recipe,
}

pub fn payload(directory: &Path) -> io::Result<Payload> {
    // Only installation instructions are read; release metadata is not an admission gate.
    let files: InstallFiles = serde_json::from_reader(File::open(directory.join("manifest.json"))?)
        .map_err(|error| invalid(&error.to_string()))?;
    Ok(Payload {
        root: directory.canonicalize()?,
        manifest: Manifest {
            infrastructure: Infrastructure::Unified,
            removal_only: false,
            requirements: None,
            recipe: files.recipe,
            schema: 4,
            product: "VoyahTune".into(),
            release_version: files.release_version,
            build_revision: String::new(),
            artifacts: files
                .artifacts
                .into_iter()
                .map(|artifact| Artifact {
                    name: artifact.name,
                    path: artifact.path,
                    sha256: String::new(),
                    size: 0,
                })
                .collect(),
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn only_ota_minimum_controls_selection() {
        let catalog = catalog(
            &serde_json::to_vec(&json!({"releases": [{
                "version": "4.2.0-beta", "url": "https://example.org/payload.zip",
                "sha256": "outdated", "minimumInstallerVersion": "invalid",
                "minimumOtaVersion": "4.1.0", "extra": true
            }]}))
            .unwrap(),
        )
        .unwrap();
        let release = &catalog.releases[0];
        assert!(select(release, "4.1.0-beta").is_ok());
        assert!(select(release, "4.2.0-beta").is_ok());
        assert!(select(release, "4.9.0").is_ok());
        assert_eq!(
            select(release, "4.0.0").unwrap_err().code,
            "OTA_USB_REQUIRED"
        );
        let mut missing = release.clone();
        missing.minimum_ota_version = None;
        assert_eq!(
            select(&missing, "4.9.0").unwrap_err().code,
            "OTA_USB_REQUIRED"
        );
    }

    #[test]
    fn payload_reads_file_instructions_without_metadata_or_hashes() {
        let directory = tempfile::tempdir().unwrap();
        let mut recipe = serde_json::to_value(Recipe::default()).unwrap();
        recipe["schema"] = json!(99);
        recipe["engine"] = json!("future");
        std::fs::write(
            directory.path().join("manifest.json"),
            serde_json::to_vec(&json!({
                "schema": 99, "releaseVersion": "catalog-label-differs", "requirements": "ignored",
                "artifacts": [{"name": "native.apk", "path": "native.apk", "sha256": "wrong"}],
                "recipe": recipe
            }))
            .unwrap(),
        )
        .unwrap();
        std::fs::write(directory.path().join("native.apk"), "APK bytes").unwrap();
        let payload = payload(directory.path()).unwrap();
        assert_eq!(
            std::fs::read_to_string(payload.file("native.apk").unwrap()).unwrap(),
            "APK bytes"
        );
    }
}
