//! OTA checks. HTTPS catalog and archive digest define the release; there is no publisher signature.
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
pub fn compatible(c: &Claims, installed: &str, same_version: bool) -> Result<()> {
    crate::infrastructure::Infrastructure::from_version(&c.version)?.require(
        crate::infrastructure::Infrastructure::from_version(installed)?,
    )?;
    let parse = crate::infrastructure::release_version;
    let target = parse(&c.version)?;
    let current = parse(installed)?;
    if target < current || (target == current && !same_version) {
        return Err(invalid("OTA не поддерживает этот путь обновления"));
    }
    Ok(())
}
pub fn verify_payload(p: &Payload, c: &Claims) -> Result<()> {
    p.verify()?;
    if p.manifest.removal_only || p.manifest.release_version != c.version {
        return Err(invalid("Manifest не соответствует выбранному релизу"));
    }
    p.manifest
        .requirements
        .as_ref()
        .ok_or_else(|| invalid("Нет требований релиза"))?
        .validate()?;
    for (file, package) in std::iter::once(("native.apk", payload::NATIVE)).chain(
        p.manifest
            .recipe
            .packages
            .iter()
            .map(|p| (p.artifact.as_str(), p.package.as_str())),
    ) {
        let (actual_id, actual_code, actual_version) = crate::apk_identity::read(&p.file(file)?)?;
        let version = semver::Version::parse(&c.version).map_err(|e| invalid(e.to_string()))?;
        if version.major > 999 || version.minor > 999 || version.patch > 999 {
            return Err(invalid("Версия вне диапазона versionCode"));
        }
        let expected_code = version
            .major
            .checked_mul(1_000_000)
            .and_then(|v| v.checked_add(version.minor * 1000))
            .and_then(|v| v.checked_add(version.patch))
            .ok_or_else(|| invalid("versionCode overflow"))?;
        if actual_id != package || actual_version != c.version || actual_code != expected_code {
            return Err(invalid(format!(
                "Package ID или версия APK {package} не совпадает с релизом"
            )));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ota_preserves_profile_and_accepts_legacy_od_versions() {
        let mut c = Claims {
            version: "3.22.0-pi".into(),
            archive_sha256: "a".repeat(64),
            archive_size: 1,
        };
        assert!(compatible(&c, "3.21.0-pi", false).is_ok());
        assert!(compatible(&c, "3.21.0-od", false).is_err());
        assert!(compatible(&c, "3.21.0", false).is_err());
        c.version = "3.22.0-od".into();
        assert!(compatible(&c, "3.21.0", false).is_ok());
        assert!(compatible(&c, "3.22.0-od", false).is_err());
        assert!(compatible(&c, "3.22.0-od", true).is_ok());
    }
    #[test]
    fn rejects_downgrade_and_requires_explicit_same_version() {
        let c = Claims {
            version: "3.15.0".into(),
            archive_sha256: "a".repeat(64),
            archive_size: 1,
        };
        assert!(compatible(&c, "3.15.0", false).is_err());
        assert!(compatible(&c, "3.15.0", true).is_ok());
        assert!(compatible(&c, "3.16.0", true).is_err());
        assert!(compatible(&c, "3.14.0", false).is_ok());
    }
}
