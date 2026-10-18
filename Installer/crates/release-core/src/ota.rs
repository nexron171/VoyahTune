//! OTA checks. HTTPS catalog and archive digest define the release; there is no publisher signature.
use crate::{
    catalog::{Catalog, Release},
    infrastructure::Infrastructure,
    payload::validation::ValidationTarget,
    payload::{self, Payload},
    Error, Result,
};
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Claims {
    pub version: String,
    pub archive_sha256: String,
    pub archive_size: u64,
}

fn invalid(message: impl Into<String>) -> Error {
    Error::new("OTA_INVALID", message)
}

pub fn verify(release: &Release) -> Result<Claims> {
    let mut catalog = Catalog::empty();
    catalog.releases.push(release.clone());
    catalog.validate()?;
    Ok(Claims {
        version: release.version.clone(),
        archive_sha256: release.payload.sha256.clone(),
        archive_size: release.payload.size,
    })
}

pub fn compatible(claims: &Claims, installed: &str, same_version: bool) -> Result<()> {
    crate::infrastructure::Infrastructure::from_version(&claims.version)?.require(
        crate::infrastructure::Infrastructure::from_version(installed)?,
    )?;
    let parse = crate::infrastructure::release_version;
    let target = parse(&claims.version)?;
    let current = parse(installed)?;
    if target < current || (target == current && !same_version) {
        return Err(invalid("OTA не поддерживает этот путь обновления"));
    }
    Ok(())
}

pub fn verify_payload(payload: &Payload, claims: &Claims) -> Result<()> {
    payload.verify()?;
    if payload.manifest.removal_only || payload.manifest.release_version != claims.version {
        return Err(invalid("Manifest не соответствует выбранному релизу"));
    }
    verify_requirements(payload)?;
    for (file, package) in std::iter::once(("native.apk", payload::NATIVE)).chain(
        payload
            .manifest
            .recipe
            .packages
            .iter()
            .map(|package| (package.artifact.as_str(), package.package.as_str())),
    ) {
        let (actual_id, actual_code, actual_version) =
            crate::apk_identity::read(&payload.file(file)?)?;
        let version =
            semver::Version::parse(&claims.version).map_err(|error| invalid(error.to_string()))?;
        if version.major > 999 || version.minor > 999 || version.patch > 999 {
            return Err(invalid("Версия вне диапазона versionCode"));
        }
        let expected_code = version
            .major
            .checked_mul(1_000_000)
            .and_then(|code| code.checked_add(version.minor * 1000))
            .and_then(|code| code.checked_add(version.patch))
            .ok_or_else(|| invalid("versionCode overflow"))?;
        if actual_id != package || actual_version != claims.version || actual_code != expected_code
        {
            return Err(invalid(format!(
                "Package ID или версия APK {package} не совпадает с релизом"
            )));
        }
    }
    Ok(())
}

fn verify_requirements(payload: &Payload) -> Result<()> {
    let requirements = payload
        .manifest
        .requirements
        .as_ref()
        .ok_or_else(|| invalid("Нет требований релиза"))?;
    let target = match payload.manifest.infrastructure {
        Infrastructure::Od => ValidationTarget::OdOta,
        Infrastructure::Pi => ValidationTarget::Installer,
    };
    target.validate_requirements(requirements)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ota_preserves_profile_and_accepts_legacy_od_versions() {
        let mut claims = Claims {
            version: "3.22.0-pi".into(),
            archive_sha256: "a".repeat(64),
            archive_size: 1,
        };
        assert!(compatible(&claims, "3.21.0-pi", false).is_ok());
        assert!(compatible(&claims, "3.21.0-od", false).is_err());
        assert!(compatible(&claims, "3.21.0", false).is_err());
        claims.version = "3.22.0-od".into();
        assert!(compatible(&claims, "3.21.0", false).is_ok());
        assert!(compatible(&claims, "3.22.0-od", false).is_err());
        assert!(compatible(&claims, "3.22.0-od", true).is_ok());
    }

    #[test]
    fn rejects_downgrade_and_requires_explicit_same_version() {
        let claims = Claims {
            version: "3.15.0".into(),
            archive_sha256: "a".repeat(64),
            archive_size: 1,
        };
        assert!(compatible(&claims, "3.15.0", false).is_err());
        assert!(compatible(&claims, "3.15.0", true).is_ok());
        assert!(compatible(&claims, "3.16.0", true).is_err());
        assert!(compatible(&claims, "3.14.0", false).is_ok());
    }

    #[test]
    fn final_payload_validation_ignores_installer_minimum_only_for_od() {
        use crate::{compatibility::Requirements, payload::Manifest, recipe::Recipe};

        let mut requirements = Requirements::restoremode_ota();
        requirements.min_installer_version = "99.0.0".into();
        let mut payload = Payload {
            root: "unused".into(),
            manifest: Manifest {
                infrastructure: Infrastructure::Od,
                removal_only: false,
                requirements: Some(requirements),
                recipe: Recipe::default(),
                schema: 4,
                product: "VoyahTune".into(),
                release_version: "4.3.0-od".into(),
                build_revision: "test".into(),
                artifacts: Vec::new(),
            },
        };
        verify_requirements(&payload).unwrap();

        payload.manifest.infrastructure = Infrastructure::Pi;
        assert_eq!(
            verify_requirements(&payload).unwrap_err().code,
            "INSTALLER_UPDATE_REQUIRED"
        );

        payload.manifest.infrastructure = Infrastructure::Od;
        payload
            .manifest
            .requirements
            .as_mut()
            .unwrap()
            .required_capabilities
            .push("future-payload-operation".into());
        assert_eq!(
            verify_requirements(&payload).unwrap_err().code,
            "OTA_USB_REQUIRED"
        );
    }
}
