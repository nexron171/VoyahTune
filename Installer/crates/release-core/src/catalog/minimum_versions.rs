use super::Release;
use crate::{
    infrastructure::{release_version, Infrastructure},
    Error, Result,
};
use semver::Version;

fn minimum_version(value: &str) -> Result<Version> {
    let version = Version::parse(value).map_err(|error| {
        Error::new(
            "CATALOG_INVALID",
            "Некорректная минимальная версия в каталоге",
        )
        .detail(error)
    })?;
    if !version.pre.is_empty() || !version.build.is_empty() {
        return Err(Error::new(
            "CATALOG_INVALID",
            "Минимальная версия должна иметь формат major.minor.patch без суффиксов",
        ));
    }
    Ok(version)
}

impl Release {
    pub(super) fn validate_minimum_versions(&self) -> Result<()> {
        if release_version(&self.version)?.major >= 4
            && Infrastructure::from_version(&self.version)? == Infrastructure::Od
            && (self.minimum_installer_version.is_none() || self.minimum_ota_version.is_none())
        {
            return Err(Error::new(
                "CATALOG_INVALID",
                "Для релизов OD 4+ требуются minimumInstallerVersion и minimumOtaVersion",
            ));
        }
        if self.minimum_installer_version.is_none() && self.minimum_ota_version.is_some() {
            return Err(Error::new(
                "CATALOG_INVALID",
                "Для minimumOtaVersion требуется minimumInstallerVersion",
            ));
        }
        for value in [&self.minimum_installer_version, &self.minimum_ota_version]
            .into_iter()
            .flatten()
        {
            minimum_version(value)?;
        }
        Ok(())
    }

    pub fn require_installer_version(&self, installed: &str) -> Result<()> {
        self.validate_minimum_versions()?;
        let Some(minimum) = &self.minimum_installer_version else {
            return Ok(());
        };
        let current = Version::parse(installed).map_err(|error| {
            Error::new("INSTALLER_VERSION", "Некорректная версия установщика").detail(error)
        })?;
        if current < minimum_version(minimum)? {
            return Err(Error::new(
                "INSTALLER_UPDATE_REQUIRED",
                format!(
                    "Для релиза {} скачайте VoyahTune Installer {} или новее. Текущая версия установщика: {}.",
                    self.version, minimum, installed
                ),
            ));
        }
        Ok(())
    }

    pub fn require_ota_version(&self, installed: &str) -> Result<()> {
        self.validate_minimum_versions()?;
        let Some(minimum) = &self.minimum_ota_version else {
            return Ok(());
        };
        if release_version(installed)? < minimum_version(minimum)? {
            return Err(Error::new(
                "OTA_USB_REQUIRED",
                format!(
                    "Релиз {} доступен по воздуху начиная с VoyahTune {}. Установлена версия {}. Установите обновление через USB с помощью VoyahTune Installer {} или новее.",
                    self.version, minimum, installed, self.minimum_installer_version.as_deref().unwrap()
                ),
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catalog::UpdateCatalog;

    fn catalog_entry(minimum_installer: &str, minimum_ota: &str) -> Release {
        serde_json::from_value::<UpdateCatalog>(serde_json::json!({
            "releases": [{
                "version": "4.1.0-od", "url": "https://example.org/payload.zip",
                "size": 1, "sha256": "a".repeat(64),
                "minimumInstallerVersion": minimum_installer,
                "minimumOtaVersion": minimum_ota
            }]
        }))
        .unwrap()
        .resolve()
        .unwrap()
        .releases
        .remove(0)
    }

    #[test]
    fn installer_compares_components_and_includes_required_version_in_error() {
        let release = catalog_entry("1.7.0", "4.1.0");
        for installed in ["1.6.9", "1.7.0-beta"] {
            let error = release.require_installer_version(installed).unwrap_err();
            assert_eq!(error.code, "INSTALLER_UPDATE_REQUIRED");
            assert!(error.message.contains("скачайте VoyahTune Installer 1.7.0"));
        }
        for installed in ["1.7.0", "1.10.0", "2.0.0"] {
            release.require_installer_version(installed).unwrap();
        }
    }

    #[test]
    fn ota_requires_usb_below_minimum_and_ignores_profile_suffix() {
        let release = catalog_entry("1.7.0", "4.1.0");
        for installed in ["4.0.0", "4.0.0-od", "4.1.0-beta-od"] {
            let error = release.require_ota_version(installed).unwrap_err();
            assert_eq!(error.code, "OTA_USB_REQUIRED");
            assert!(error.message.contains("USB"));
            assert!(error.message.contains("Installer 1.7.0"));
        }
        for installed in ["4.1.0", "4.1.0-od", "4.10.0-od"] {
            release.require_ota_version(installed).unwrap();
        }
    }

    #[test]
    fn invalid_or_incomplete_minimums_are_rejected() {
        let mut release = catalog_entry("1.7.0", "4.1.0");
        for invalid in ["", "1.7", "one", "1.7.0-od", "1.7.0+build"] {
            release.minimum_installer_version = Some(invalid.into());
            assert!(release.validate_minimum_versions().is_err());
        }
        release.minimum_installer_version = None;
        assert!(release.validate_minimum_versions().is_err());
        release.minimum_ota_version = None;
        assert!(release.validate_minimum_versions().is_err());
        release.version = "3.21.0".into();
        release.require_installer_version("1.0.0").unwrap();
        release.require_ota_version("3.21.0").unwrap();
    }

    #[test]
    fn minimums_survive_offline_cache_and_ota_journal_roundtrip() {
        let release = catalog_entry("1.7.0", "4.1.0");
        let stored = serde_json::to_vec(&release).unwrap();
        let restored: Release = serde_json::from_slice(&stored).unwrap();
        assert!(restored.require_installer_version("1.6.0").is_err());
        assert!(restored.require_ota_version("4.0.0-od").is_err());
    }

    #[test]
    fn initial_v4_release_accepts_the_current_installer_and_payload_versions() {
        let release = catalog_entry("1.6.0", "4.0.0");
        release.require_installer_version("1.6.0").unwrap();
        release.require_ota_version("4.0.0-od").unwrap();
        assert!(release.require_installer_version("1.5.0").is_err());
        assert!(release.require_ota_version("3.22.0-od").is_err());
    }
}
