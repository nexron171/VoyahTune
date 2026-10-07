use super::{Cache, CATALOG_URL};
use crate::{recovery, Error, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{fs, path::PathBuf};

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogSettings {
    pub catalog_url: String,
}

fn validate_url(value: &str) -> Result<()> {
    let invalid = || {
        Error::new(
            "CATALOG_URL_INVALID",
            "Укажите HTTPS URL каталога без логина, пароля и фрагмента (#)",
        )
    };
    let url = url::Url::parse(value).map_err(|_| invalid())?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || value.chars().any(char::is_whitespace)
    {
        return Err(invalid());
    }
    Ok(())
}

impl Cache {
    pub fn catalog_settings(&self) -> Result<CatalogSettings> {
        let settings = match fs::read(self.root.join("catalog-settings.json")) {
            Ok(bytes) => serde_json::from_slice::<CatalogSettings>(&bytes)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => CatalogSettings {
                catalog_url: CATALOG_URL.into(),
            },
            Err(error) => return Err(error.into()),
        };
        validate_url(&settings.catalog_url)?;
        Ok(settings)
    }

    pub fn save_catalog_url(&self, value: &str) -> Result<CatalogSettings> {
        let settings = CatalogSettings {
            catalog_url: value.trim().into(),
        };
        validate_url(&settings.catalog_url)?;
        let _lock = self.lock()?;
        recovery::write_json(&self.root.join("catalog-settings.json"), &settings)?;
        Ok(settings)
    }

    pub(super) fn catalog_path(&self) -> Result<PathBuf> {
        let url = self.catalog_settings()?.catalog_url;
        // The unkeyed cache belongs only to the original GitHub catalog.
        let name = if url == "https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Releases/ota/index.json" {
            "ota-catalog.json".into()
        } else {
            format!("ota-catalog-{:x}.json", Sha256::digest(url.as_bytes()))
        };
        Ok(self.root.join(name))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catalog::Catalog;

    #[test]
    fn gitlab_default_does_not_reuse_the_old_github_cache() {
        let directory = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: directory.path().into(),
        };
        let mut previous = Catalog::empty();
        previous.generated_at = "old-github-catalog".into();
        recovery::write_json(&cache.root.join("ota-catalog.json"), &previous).unwrap();
        cache
            .save_catalog_url(
                "https://gitlab.com/openvoyah/voyahtune/-/raw/master/Releases/ota/index-v4.json",
            )
            .unwrap();
        assert!(cache.state(false).unwrap().catalog.generated_at.is_empty());
    }

    #[test]
    fn saved_catalog_url_survives_reopening_the_cache() {
        let directory = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: directory.path().into(),
        };
        assert_eq!(cache.catalog_settings().unwrap().catalog_url, CATALOG_URL);
        cache
            .save_catalog_url(" https://example.org/index-beta.json ")
            .unwrap();
        let reopened = Cache {
            root: directory.path().into(),
        };
        assert_eq!(
            reopened.catalog_settings().unwrap().catalog_url,
            "https://example.org/index-beta.json"
        );
    }

    #[test]
    fn invalid_urls_preserve_saved_settings() {
        let directory = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: directory.path().into(),
        };
        cache
            .save_catalog_url("https://example.org/index.json")
            .unwrap();
        for invalid in [
            "",
            "garbage",
            "http://example.org/index.json",
            "file:///tmp/index.json",
            "https://user:pass@example.org/index.json",
            "https://example.org/index.json#fragment",
            "https://example.org/a b",
        ] {
            assert!(cache.save_catalog_url(invalid).is_err(), "{invalid}");
        }
        assert_eq!(
            cache.catalog_settings().unwrap().catalog_url,
            "https://example.org/index.json"
        );
    }

    #[test]
    fn catalog_switch_keeps_offline_copies_separate() {
        let directory = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: directory.path().into(),
        };
        let mut original = Catalog::empty();
        original.generated_at = "original".into();
        cache.state_with(|| Ok(original), true).unwrap();
        cache
            .save_catalog_url("https://example.org/index-beta.json")
            .unwrap();
        let unavailable = cache
            .state_with(|| Err(Error::new("NETWORK", "offline")), true)
            .unwrap();
        assert!(unavailable.catalog.generated_at.is_empty());
        assert!(unavailable.warning.is_some());
        let mut alternate = Catalog::empty();
        alternate.generated_at = "alternate".into();
        cache.state_with(|| Ok(alternate), true).unwrap();
        cache.save_catalog_url(CATALOG_URL).unwrap();
        assert_eq!(cache.state(false).unwrap().catalog.generated_at, "original");
        cache
            .save_catalog_url("https://example.org/index-beta.json")
            .unwrap();
        assert_eq!(
            cache.state(false).unwrap().catalog.generated_at,
            "alternate"
        );
    }
}
