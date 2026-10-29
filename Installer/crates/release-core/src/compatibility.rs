use crate::{Error, Result};
use serde::{Deserialize, Serialize};

pub const INSTALLER_VERSION: &str = env!("CARGO_PKG_VERSION");

pub const CAPABILITIES: &[&str] = &[
    "qinggan-v3",
    "single-package-v1",
    "files-v1",
    "ota-bootstrap-v1",
    "infrastructure-v1",
    "restoremode-ota-ui-v1",
    "unified-runtime-v1",
    "fixed-payload-v1",
];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Requirements {
    pub min_installer_version: String,
    pub required_capabilities: Vec<String>,
}

impl Default for Requirements {
    fn default() -> Self {
        Self::unified()
    }
}

impl Requirements {
    pub fn unified() -> Self {
        Self {
            min_installer_version: "1.8.5".into(),
            required_capabilities: CAPABILITIES
                .iter()
                .map(|capability| capability.to_string())
                .collect(),
        }
    }

    pub fn validate(&self) -> Result<()> {
        let required = semver::Version::parse(&self.min_installer_version).map_err(|e| {
            Error::new(
                "REQUIREMENTS_INVALID",
                "Некорректная минимальная версия установщика",
            )
            .detail(e)
        })?;
        if semver::Version::parse(INSTALLER_VERSION).unwrap() < required {
            return Err(Error::new(
                "INSTALLER_UPDATE_REQUIRED",
                format!(
                    "Релиз требует VoyahTune Installer {} или новее. Установлена версия {}",
                    required, INSTALLER_VERSION
                ),
            ));
        }
        if let Some(cap) = self
            .required_capabilities
            .iter()
            .find(|s| !CAPABILITIES.contains(&s.as_str()))
        {
            return Err(Error::new(
                "INSTALLER_UPDATE_REQUIRED",
                "Релиз требует новые возможности установщика. Обновите VoyahTune Installer.",
            )
            .detail(cap));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compatibility_is_minimum_not_exact_version() {
        Requirements::default().validate().unwrap();
        let mut requirements = Requirements::default();
        requirements.min_installer_version = "99.0.0".into();
        assert_eq!(
            requirements.validate().unwrap_err().code,
            "INSTALLER_UPDATE_REQUIRED"
        );
        requirements.min_installer_version = "0.1.0".into();
        requirements.required_capabilities.push("unknown".into());
        assert!(requirements.validate().is_err());
    }
}
