//! Build-time selection of the Frida infrastructure; legacy metadata belongs to OD.
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Infrastructure {
    Unified,
    Pi,
    #[default]
    Od,
}
impl Infrastructure {
    /// Infrastructure is the final prerelease suffix; unlabelled legacy releases belong to OD.
    pub fn from_version(version: &str) -> Result<Self> {
        Ok(Self::explicit_version(version)?.unwrap_or(Self::Od))
    }
    pub fn explicit_version(version: &str) -> Result<Option<Self>> {
        let version = semver::Version::parse(version)
            .map_err(|e| Error::new("RELEASE_VERSION", "Некорректная версия релиза").detail(e))?;
        Ok(match version.pre.as_str().rsplit('-').next() {
            Some("pi") => Some(Self::Pi),
            Some("od") => Some(Self::Od),
            _ => None,
        })
    }
    pub fn require_version(self, version: &str) -> Result<()> {
        match Self::explicit_version(version)? {
            None if self == Self::Unified => Ok(()),
            Some(actual) if actual == self => Ok(()),
            _ => Err(Error::new(
                "INFRASTRUCTURE_VERSION",
                "Суффикс версии не соответствует инфраструктуре релиза",
            )),
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unified => "unified",
            Self::Pi => "pi",
            Self::Od => "od",
        }
    }
    pub fn compiled() -> Self {
        env!("VOYAH_INFRASTRUCTURE")
            .parse()
            .expect("validated build infrastructure")
    }
    pub fn uses_restore_ui(self) -> bool {
        self != Self::Pi
    }
    pub fn matches_version(self, version: &str) -> bool {
        if self == Self::Unified {
            Self::explicit_version(version).is_ok_and(|profile| profile.is_none())
        } else {
            Self::from_version(version).is_ok_and(|profile| profile == self)
        }
    }
    pub fn require(self, expected: Self) -> Result<()> {
        if self == expected {
            return Ok(());
        }
        Err(Error::new(
            "INFRASTRUCTURE_MISMATCH",
            format!(
                "Релиз содержит инфраструктуру {}, а установщик/служба предназначены для {}",
                self.as_str(),
                expected.as_str()
            ),
        ))
    }
}
/// Compare release numbers without treating a profile-only suffix as a prerelease.
pub fn release_version(version: &str) -> Result<semver::Version> {
    let mut parsed = semver::Version::parse(version)
        .map_err(|e| Error::new("RELEASE_VERSION", "Некорректная версия релиза").detail(e))?;
    if Infrastructure::explicit_version(version)?.is_some() {
        let pre = parsed
            .pre
            .as_str()
            .rsplit_once('-')
            .map(|(pre, _)| pre)
            .unwrap_or("");
        parsed.pre = semver::Prerelease::new(pre)
            .map_err(|e| Error::new("RELEASE_VERSION", "Некорректная версия релиза").detail(e))?;
    }
    Ok(parsed)
}
impl std::str::FromStr for Infrastructure {
    type Err = String;
    fn from_str(value: &str) -> std::result::Result<Self, String> {
        match value {
            "unified" => Ok(Self::Unified),
            "pi" => Ok(Self::Pi),
            "od" => Ok(Self::Od),
            _ => Err("Infrastructure must be unified, pi or od".into()),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unified_release_has_no_pi_od_suffix() {
        for version in ["4.4.1", "4.4.1-beta"] {
            Infrastructure::Unified.require_version(version).unwrap();
            assert!(Infrastructure::Unified.matches_version(version));
        }
        for version in ["4.4.1-od", "4.4.1-pi", "4.4.1-beta-od"] {
            assert!(Infrastructure::Unified.require_version(version).is_err());
            assert!(!Infrastructure::Unified.matches_version(version));
        }
    }
    #[test]
    fn version_suffix_is_not_a_release_channel() {
        for (version, profile, prerelease) in [
            ("3.22.0-pi", Infrastructure::Pi, false),
            ("3.22.0-od+build", Infrastructure::Od, false),
            ("3.22.0-beta.1-pi+test", Infrastructure::Pi, true),
            ("3.22.0-beta.1-od", Infrastructure::Od, true),
            ("3.21.0", Infrastructure::Od, false),
            ("3.21.0-beta.1", Infrastructure::Od, true),
        ] {
            assert_eq!(Infrastructure::from_version(version).unwrap(), profile);
            assert_eq!(
                !release_version(version).unwrap().pre.is_empty(),
                prerelease
            );
        }
        assert!(Infrastructure::Pi.require_version("3.22.0-od").is_err());
        assert!(Infrastructure::Od.require_version("3.22.0").is_err());
        assert!(Infrastructure::Pi
            .require_version("3.22.0-beta.1-pi+test")
            .is_ok());
    }
    #[test]
    fn selection_is_exact_and_mismatch_is_rejected() {
        assert!("PI".parse::<Infrastructure>().is_err());
        assert!(Infrastructure::Pi.require(Infrastructure::Od).is_err());
        assert!(Infrastructure::Od.require(Infrastructure::Od).is_ok());
    }
    #[test]
    fn legacy_metadata_defaults_to_od() {
        assert_eq!(Infrastructure::default(), Infrastructure::Od);
        let mut metadata = serde_json::json!({"schema":3,"product":"VoyahTune","component":"native","releaseVersion":"1.0.0","buildRevision":"test"});
        assert_eq!(
            serde_json::from_value::<crate::payload::BuildMetadata>(metadata.clone())
                .unwrap()
                .infrastructure,
            Infrastructure::Od
        );
        metadata["infrastructure"] = serde_json::json!("pi");
        assert_eq!(
            serde_json::from_value::<crate::payload::BuildMetadata>(metadata.clone())
                .unwrap()
                .infrastructure,
            Infrastructure::Pi
        );
        metadata["infrastructure"] = serde_json::json!("wrong");
        assert!(serde_json::from_value::<crate::payload::BuildMetadata>(metadata).is_err());
    }
}
