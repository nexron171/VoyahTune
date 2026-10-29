use crate::config::invalid;
use release_core::{
    catalog::{Catalog, Release, UpdateRelease},
    infrastructure::{release_version, Infrastructure},
    ota::Claims,
    payload::Payload,
    Error, Result,
};
use serde::Deserialize;
use std::{io, path::Path};

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

pub fn payload(directory: &Path) -> io::Result<Payload> {
    Payload::for_ota(directory).map_err(|error| invalid(&error.to_string()))
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
    fn payload_uses_compiled_paths_without_metadata_admission_checks() {
        let directory = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(directory.path().join("common")).unwrap();
        std::fs::write(directory.path().join("common/native.apk"), "APK bytes").unwrap();
        let payload = payload(directory.path()).unwrap();
        assert_eq!(
            std::fs::read_to_string(payload.file("native.apk").unwrap()).unwrap(),
            "APK bytes"
        );
        assert!(payload.manifest.requirements.is_some());
    }
}
