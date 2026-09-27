//! Release index format shared by desktop and Android; no network or ADB.
use crate::{compatibility::Requirements, Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
const MAX_ARCHIVE: u64 = 2 * 1024 * 1024 * 1024;
pub const CATALOG_URL: &str =
    "https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Installer/releases/index.json";
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
    /// Explicit opt-in for device updates; old/unmarked releases remain desktop-only.
    #[serde(default, skip_serializing_if = "is_false")]
    pub ota: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ota_metadata: Option<crate::ota::Envelope>,
    pub published_at: String,
    pub channel: String,
    pub notes_url: String,
    pub payload: Archive,
    pub requirements: Requirements,
}
fn is_false(value: &bool) -> bool {
    !value
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
        self.releases.iter().filter(|release| release.ota)
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
            let version = semver::Version::parse(&r.version).map_err(|e| invalid(e.to_string()))?;
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
            https_url(&r.notes_url)?;
        }
        for i in &self.installer_downloads {
            semver::Version::parse(&i.version).map_err(|e| invalid(e.to_string()))?;
            if !["macos", "windows", "linux"].contains(&i.platform.as_str()) {
                return Err(invalid("Неизвестная платформа"));
            }
            https_url(&i.url)?;
        }
        self.releases.sort_by(|a, b| {
            semver::Version::parse(&b.version)
                .unwrap()
                .cmp(&semver::Version::parse(&a.version).unwrap())
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
