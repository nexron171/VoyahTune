use crate::{Error, Result};
use serde::{Deserialize, Serialize};

pub const INSTALLER_VERSION: &str = env!("CARGO_PKG_VERSION");
// Compatible OD payload changes do not raise the established installer minimum.
const RESTOREMODE_OTA_MIN_INSTALLER_VERSION: &str = "1.7.7";

pub const CAPABILITIES: &[&str] = &[
    "qinggan-v3",
    "single-package-v1",
    "files-v1",
    "ota-bootstrap-v1",
    "infrastructure-v1",
    "restoremode-ota-ui-v1",
    "unified-runtime-v1",
];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Requirements {
    pub min_installer_version: String,
    pub required_capabilities: Vec<String>,
}

impl Default for Requirements {
    fn default() -> Self {
        Self {
            min_installer_version: "1.2.0".into(),
            required_capabilities: CAPABILITIES
                .iter()
                .filter(|s| {
                    ![
                        "infrastructure-v1",
                        "restoremode-ota-ui-v1",
                        "unified-runtime-v1",
                    ]
                    .contains(s)
                })
                .map(|s| s.to_string())
                .collect(),
        }
    }
}

impl Requirements {
    pub fn infrastructure() -> Self {
        Self {
            min_installer_version: "1.5.0".into(),
            required_capabilities: CAPABILITIES
                .iter()
                .filter(|s| !["restoremode-ota-ui-v1", "unified-runtime-v1"].contains(s))
                .map(|s| s.to_string())
                .collect(),
        }
    }

    pub fn restoremode_ota() -> Self {
        let mut result = Self::infrastructure();
        result.min_installer_version = RESTOREMODE_OTA_MIN_INSTALLER_VERSION.into();
        result
            .required_capabilities
            .push("restoremode-ota-ui-v1".into());
        result
    }
    pub fn unified() -> Self {
        let mut result = Self::restoremode_ota();
        result.min_installer_version = "1.8.2".into();
        result
            .required_capabilities
            .push("unified-runtime-v1".into());
        result
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

    #[test]
    fn embedded_ui_requirement_keeps_compatible_installer_minimum() {
        let od = Requirements::restoremode_ota();
        assert_eq!(od.min_installer_version, "1.7.7");
        assert!(od
            .required_capabilities
            .iter()
            .any(|c| c == "restoremode-ota-ui-v1"));
        od.validate().unwrap();
        let pi = Requirements::infrastructure();
        assert_eq!(pi.min_installer_version, "1.5.0");
        assert!(!pi
            .required_capabilities
            .iter()
            .any(|c| c == "restoremode-ota-ui-v1"));
        assert!(!Requirements::default()
            .required_capabilities
            .iter()
            .any(|c| c == "restoremode-ota-ui-v1"));
    }
}
