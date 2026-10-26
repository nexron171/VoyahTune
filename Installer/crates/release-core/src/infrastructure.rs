//! Identity of the single runtime shipped by all VoyahTune builds.
use crate::{Error, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Infrastructure {
    #[default]
    Unified,
}

impl Infrastructure {
    pub fn require_version(self, version: &str) -> Result<()> {
        let version = parse_version(version)?;
        if legacy_suffix(&version) {
            return Err(Error::new(
                "INFRASTRUCTURE_VERSION",
                "Общий релиз не содержит суффикс инфраструктуры",
            ));
        }
        Ok(())
    }

    pub fn as_str(self) -> &'static str {
        "unified"
    }

    pub fn compiled() -> Self {
        Self::Unified
    }
}

fn parse_version(version: &str) -> Result<semver::Version> {
    semver::Version::parse(version)
        .map_err(|error| Error::new("RELEASE_VERSION", "Некорректная версия релиза").detail(error))
}

fn legacy_suffix(version: &semver::Version) -> bool {
    matches!(version.pre.as_str().rsplit('-').next(), Some("pi" | "od"))
}

/// Old installed version labels are normalized only for upgrade comparisons.
pub fn release_version(version: &str) -> Result<semver::Version> {
    let mut parsed = parse_version(version)?;
    if legacy_suffix(&parsed) {
        let pre = parsed
            .pre
            .as_str()
            .rsplit_once('-')
            .map(|(pre, _)| pre)
            .unwrap_or("");
        parsed.pre = semver::Prerelease::new(pre).map_err(|error| {
            Error::new("RELEASE_VERSION", "Некорректная версия релиза").detail(error)
        })?;
    }
    Ok(parsed)
}

impl std::str::FromStr for Infrastructure {
    type Err = String;
    fn from_str(value: &str) -> std::result::Result<Self, String> {
        match value {
            "unified" => Ok(Self::Unified),
            _ => Err("Only the unified infrastructure is supported".into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_versions_accept_channels_and_reject_legacy_profiles() {
        for version in ["4.4.1", "4.4.1-beta", "4.4.1-beta.2+build"] {
            Infrastructure::Unified.require_version(version).unwrap();
        }
        for version in ["4.4.1-od", "4.4.1-pi", "4.4.1-beta-od", "invalid"] {
            assert!(Infrastructure::Unified.require_version(version).is_err());
        }
    }

    #[test]
    fn legacy_installed_versions_keep_release_and_channel_precedence() {
        for (installed, normalized) in [
            ("3.22.0-pi", "3.22.0"),
            ("3.22.0-od+build", "3.22.0+build"),
            ("3.22.0-beta.1-pi", "3.22.0-beta.1"),
            ("4.4.1-beta", "4.4.1-beta"),
        ] {
            assert_eq!(release_version(installed).unwrap().to_string(), normalized);
        }
        assert!(release_version("broken").is_err());
    }

    #[test]
    fn metadata_has_only_one_runtime_identity() {
        assert_eq!(
            "unified".parse::<Infrastructure>().unwrap(),
            Infrastructure::Unified
        );
        assert_eq!(
            serde_json::to_string(&Infrastructure::Unified).unwrap(),
            "\"unified\""
        );
        for value in ["pi", "od", "UNIFIED", "", "unknown"] {
            assert!(value.parse::<Infrastructure>().is_err());
            assert!(serde_json::from_value::<Infrastructure>(serde_json::json!(value)).is_err());
        }
    }
}
