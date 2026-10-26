//! Desktop release validation. Device OTA uses Claims without invoking these checks.
use crate::{
    catalog::{Catalog, Release},
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
        verify_package_identity(
            package,
            &crate::apk_identity::read(&payload.file(file)?)?,
            &claims.version,
        )?;
    }
    Ok(())
}

fn verify_package_identity(
    package: &str,
    (actual_id, actual_code, actual_version): &(String, u64, String),
    release_version: &str,
) -> Result<()> {
    if actual_id != package {
        return Err(invalid(format!(
            "Package ID APK {package} не совпадает с recipe"
        )));
    }
    // RunYN has an independent version; the verified payload selects its exact bytes.
    if package == payload::RUNYN {
        return Ok(());
    }

    let version =
        semver::Version::parse(release_version).map_err(|error| invalid(error.to_string()))?;
    if version.major > 999 || version.minor > 999 || version.patch > 999 {
        return Err(invalid("Версия вне диапазона versionCode"));
    }
    let expected_code = version
        .major
        .checked_mul(1_000_000)
        .and_then(|code| code.checked_add(version.minor * 1000))
        .and_then(|code| code.checked_add(version.patch))
        .ok_or_else(|| invalid("versionCode overflow"))?;
    if actual_version != release_version || *actual_code != expected_code {
        return Err(invalid(format!(
            "Версия APK {package} не совпадает с релизом"
        )));
    }
    Ok(())
}

fn verify_requirements(payload: &Payload) -> Result<()> {
    let requirements = payload
        .manifest
        .requirements
        .as_ref()
        .ok_or_else(|| invalid("Нет требований релиза"))?;
    requirements.validate()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unified_runyn_version_is_independent_but_package_id_is_required() {
        for (code, version) in [(1, "1.0"), (9_000_000, "9.0.0"), (4_004_000, "4.4.0")] {
            verify_package_identity(
                payload::RUNYN,
                &(payload::RUNYN.into(), code, version.into()),
                "4.4.0",
            )
            .unwrap();
        }
        assert!(verify_package_identity(
            payload::RUNYN,
            &(payload::RESTORE.into(), 1, "1.0".into()),
            "4.4.0",
        )
        .is_err());
    }

    #[test]
    fn main_packages_require_release_versions() {
        for (package, release) in [(payload::NATIVE, "4.4.0"), (payload::RESTORE, "4.4.0")] {
            for (code, version) in [(1, release), (4_004_000, "1.0")] {
                assert!(verify_package_identity(
                    package,
                    &(package.into(), code, version.into()),
                    release,
                )
                .is_err());
            }
            verify_package_identity(
                package,
                &(package.into(), 4_004_000, release.into()),
                release,
            )
            .unwrap();
        }
    }
}
