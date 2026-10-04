//! Release index format shared by desktop and Android; no network or ADB.
use crate::{compatibility::Requirements, Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
const MAX_ARCHIVE: u64 = 2 * 1024 * 1024 * 1024;
pub const CATALOG_URL: &str = env!("VOYAH_CATALOG_URL");
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Archive {
    pub url: String,
    pub size: u64,
    pub sha256: String,
    pub manifest_schema: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Release {
    pub version: String,
    pub published_at: String,
    pub channel: String,
    pub notes_url: String,
    pub payload: Archive,
    pub requirements: Requirements,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InstallerDownload {
    pub version: String,
    pub platform: String,
    pub url: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Catalog {
    pub schema_version: u32,
    pub generated_at: String,
    pub releases: Vec<Release>,
    pub installer_downloads: Vec<InstallerDownload>,
}
impl Catalog {
    /// Eligibility is separate from firmware, updater and payload compatibility checks.
    pub fn ota_releases(&self) -> impl Iterator<Item = &Release> {
        self.releases.iter()
    }
    pub fn installer_updates(
        &self,
        requirements: &Requirements,
        platform: &str,
    ) -> Vec<InstallerDownload> {
        let Ok(minimum) = semver::Version::parse(&requirements.min_installer_version) else {
            return vec![];
        };
        let mut updates: Vec<_> = self
            .installer_downloads
            .iter()
            .filter(|u| {
                u.platform == platform
                    && semver::Version::parse(&u.version).is_ok_and(|v| v >= minimum)
            })
            .cloned()
            .collect();
        updates.sort_by(|a, b| {
            semver::Version::parse(&b.version)
                .unwrap()
                .cmp(&semver::Version::parse(&a.version).unwrap())
        });
        updates
    }
    pub fn empty() -> Self {
        Self {
            schema_version: 1,
            generated_at: String::new(),
            releases: vec![],
            installer_downloads: vec![],
        }
    }
    pub fn validate(&mut self) -> Result<()> {
        if self.schema_version != 1 || self.releases.len() > 1000 {
            return Err(invalid("Неподдерживаемый каталог релизов"));
        }
        let mut versions = BTreeSet::new();
        for r in &self.releases {
            let version = crate::infrastructure::release_version(&r.version)?;
            if !versions.insert(&r.version)
                || !["stable", "prerelease"].contains(&r.channel.as_str())
                || (r.channel == "stable" && !version.pre.is_empty())
                || r.payload.size == 0
                || r.payload.size > MAX_ARCHIVE
                || !digest_valid(&r.payload.sha256)
                || r.payload.manifest_schema < 3
            {
                return Err(invalid(format!("Некорректная запись {}", r.version)));
            }
            // Future requirements are displayed, not rejected with the entire catalog.
            semver::Version::parse(&r.requirements.min_installer_version)
                .map_err(|e| invalid(e.to_string()))?;
            https_url(&r.payload.url)?;
            if !r.notes_url.is_empty() {
                https_url(&r.notes_url)?;
            }
        }
        for i in &self.installer_downloads {
            semver::Version::parse(&i.version).map_err(|e| invalid(e.to_string()))?;
            if !["macos", "windows", "linux"].contains(&i.platform.as_str()) {
                return Err(invalid("Неизвестная платформа"));
            }
            https_url(&i.url)?;
        }
        self.releases.sort_by(|a, b| {
            crate::infrastructure::release_version(&b.version)
                .unwrap()
                .cmp(&crate::infrastructure::release_version(&a.version).unwrap())
        });
        Ok(())
    }
}
fn invalid(detail: impl ToString) -> Error {
    Error::new("CATALOG_INVALID", "Не удалось прочитать каталог релизов").detail(detail)
}
fn digest_valid(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn https_url(value: &str) -> Result<()> {
    let url = url::Url::parse(value).map_err(|e| invalid(e.to_string()))?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(invalid("Требуется HTTPS URL без учётных данных"));
    }
    Ok(())
}

/// Public catalog for OTA and the new installer. Internal UI/cache models stay separate.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateCatalog {
    pub releases: Vec<UpdateRelease>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateRelease {
    pub version: String,
    pub url: String,
    pub size: u64,
    pub sha256: String,
}
impl UpdateRelease {
    pub fn into_release(self) -> Release {
        Release {
            version: self.version.clone(),
            published_at: String::new(),
            channel: if crate::infrastructure::release_version(&self.version)
                .is_ok_and(|v| !v.pre.is_empty())
            {
                "prerelease".into()
            } else {
                "stable".into()
            },
            notes_url: String::new(),
            payload: Archive {
                url: self.url,
                size: self.size,
                sha256: self.sha256,
                manifest_schema: 4,
            },
            requirements: Requirements::default(),
        }
    }
}
impl UpdateCatalog {
    pub fn resolve(self) -> Result<Catalog> {
        let mut catalog = Catalog::empty();
        catalog.releases = self
            .releases
            .into_iter()
            .map(UpdateRelease::into_release)
            .collect();
        catalog.validate()?;
        Ok(catalog)
    }
    pub fn validate(&self) -> Result<()> {
        self.clone().resolve().map(|_| ())
    }
}
#[cfg(test)]
mod update_tests {
    use super::*;
    #[test]
    fn shared_catalog_keeps_both_profiles_and_profile_only_versions_are_stable() {
        let catalog = UpdateCatalog {
            releases: ["3.22.0-pi", "3.22.0-od", "3.23.0-beta.1-pi", "3.21.0"]
                .into_iter()
                .map(|version| UpdateRelease {
                    version: version.into(),
                    url: "https://example.org/payload.zip".into(),
                    size: 1,
                    sha256: "a".repeat(64),
                })
                .collect(),
        }
        .resolve()
        .unwrap();
        assert_eq!(catalog.releases.len(), 4);
        assert_eq!(
            catalog
                .releases
                .iter()
                .filter(|r| r.channel == "stable")
                .count(),
            3
        );
        let pi: Vec<_> = catalog
            .ota_releases()
            .filter(|r| {
                crate::infrastructure::Infrastructure::from_version(&r.version).unwrap()
                    == crate::infrastructure::Infrastructure::Pi
                    && r.channel == "stable"
            })
            .collect();
        assert_eq!(pi.len(), 1);
        assert_eq!(pi[0].version, "3.22.0-pi");
    }
    #[test]
    fn simple_catalog_validates_identity_urls_and_duplicates() {
        let entry = UpdateRelease {
            version: "3.15.0".into(),
            url: "https://example.org/payload.zip".into(),
            size: 149407190,
            sha256: "a".repeat(64),
        };
        let mut c = UpdateCatalog {
            releases: vec![entry.clone()],
        };
        c.validate().unwrap();
        c.releases.push(entry);
        assert!(c.validate().is_err());
        c.releases.pop();
        c.releases[0].url = "http://example.org/payload.zip".into();
        assert!(c.validate().is_err());
        c.releases[0].url = "https://example.org/payload.zip".into();
        c.releases[0].sha256 = "invalid".into();
        assert!(c.validate().is_err());
    }
    #[test]
    fn old_catalog_and_removed_metadata_are_rejected() {
        assert!(
            serde_json::from_str::<UpdateCatalog>(r#"{"schemaVersion":1,"releases":[]}"#).is_err()
        );
        assert!(serde_json::from_str::<UpdateRelease>(r#"{"version":"3.15.0","url":"https://example.org/a","size":1,"sha256":"a","signature":"x"}"#).is_err());
    }
}
