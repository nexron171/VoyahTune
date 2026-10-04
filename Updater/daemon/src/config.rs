use serde::{Deserialize, Serialize};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::{
    fs,
    fs::OpenOptions,
    io::{self, Read, Write},
    path::Path,
};

pub const DEFAULT_CATALOG_URL: &str = release_core::catalog::CATALOG_URL;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    #[serde(default)]
    pub infrastructure: release_core::infrastructure::Infrastructure,
    pub schema: u32,
    pub catalog_url: String,
    pub source_generation: u64,
    #[serde(default)]
    pub dns_enabled: Option<bool>,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            infrastructure: release_core::infrastructure::Infrastructure::compiled(),
            schema: 1,
            catalog_url: DEFAULT_CATALOG_URL.into(),
            source_generation: 0,
            dns_enabled: None,
        }
    }
}

pub fn normalize_url(value: &str) -> io::Result<String> {
    if value.is_empty() {
        return Ok(String::new());
    }
    if value.len() > 4096 || value.bytes().any(|c| c.is_ascii_control()) {
        return Err(invalid("Некорректный URL каталога"));
    }
    let url = url::Url::parse(value.trim()).map_err(|_| invalid("Некорректный URL каталога"))?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
    {
        return Err(invalid("Нужен HTTPS URL без логина, пароля и фрагмента"));
    }
    // The catalog URL may use any HTTPS host; its address never changes the trust key.
    Ok(url.to_string())
}

pub fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

pub fn load(root: &Path) -> io::Result<Config> {
    let file = match OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(root.join("settings.json"))
    {
        Ok(file) => file,
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            let initial = Config::default();
            save(root, &initial)?;
            return Ok(initial);
        }
        Err(e) => return Err(e),
    };
    if !file.metadata()?.is_file() {
        return Err(invalid("Настройки не являются файлом"));
    }
    let mut bytes = vec![];
    file.take(16 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > 16 * 1024 {
        return Err(invalid("Настройки слишком велики"));
    }
    let mut config: Config =
        serde_json::from_slice(&bytes).map_err(|_| invalid("Настройки повреждены"))?;
    if config.schema != 1 {
        return Err(invalid("Неизвестный формат настроек"));
    }
    let selected = release_core::infrastructure::Infrastructure::compiled();
    if config.infrastructure != selected {
        // A USB installation can replace the infrastructure. Invalidate any
        // pending selection while retaining the user's shared catalog and DNS choice.
        config.infrastructure = selected;
        config.source_generation = config
            .source_generation
            .checked_add(1)
            .ok_or_else(|| invalid("Счётчик источника исчерпан"))?;
        save(root, &config)?;
    }
    config.catalog_url = normalize_url(&config.catalog_url)?;
    Ok(config)
}

pub fn save(root: &Path, config: &Config) -> io::Result<()> {
    let bytes =
        serde_json::to_vec_pretty(config).map_err(|_| invalid("Не удалось записать настройки"))?;
    let temporary = root.join(".settings.new");
    // The root directory is private and a process lock serializes writers.
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&temporary)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    fs::rename(temporary, root.join("settings.json"))?;
    fs::File::open(root)?.sync_all()
}

pub fn change_url(root: &Path, current: &mut Config, input: &str) -> io::Result<bool> {
    let url = normalize_url(input)?;
    if current.catalog_url == url {
        return Ok(false);
    }
    let mut next = current.clone();
    next.catalog_url = url;
    next.source_generation = current
        .source_generation
        .checked_add(1)
        .ok_or_else(|| invalid("Счётчик источника исчерпан"))?;
    // Do not change live state unless persistence succeeded. Later catalog/download
    // results must carry this generation and cannot be applied to another source.
    save(root, &next)?;
    *current = next;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn address_is_host_independent_and_persists_across_restart() {
        let root = tempfile::tempdir().unwrap();
        let mut config = load(root.path()).unwrap();
        assert_eq!(config.catalog_url, DEFAULT_CATALOG_URL);
        assert!(change_url(
            root.path(),
            &mut config,
            "https://updates.example.org/releases/index.json"
        )
        .unwrap());
        assert_eq!(load(root.path()).unwrap(), config);
        assert_eq!(config.source_generation, 1);
        assert!(!change_url(
            root.path(),
            &mut config,
            "https://updates.example.org/releases/index.json"
        )
        .unwrap());
        assert_eq!(config.source_generation, 1);
        assert_eq!(
            fs::metadata(root.path().join("settings.json"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }
    #[test]
    fn usb_profile_change_invalidates_selection_and_preserves_shared_catalog_and_dns() {
        let root = tempfile::tempdir().unwrap();
        let mut old = Config::default();
        old.infrastructure = match old.infrastructure {
            release_core::infrastructure::Infrastructure::Pi => {
                release_core::infrastructure::Infrastructure::Od
            }
            _ => release_core::infrastructure::Infrastructure::Pi,
        };
        old.catalog_url = "https://old-profile.example/catalog.json".into();
        old.dns_enabled = Some(true);
        save(root.path(), &old).unwrap();
        let current = load(root.path()).unwrap();
        assert_eq!(
            current.infrastructure,
            release_core::infrastructure::Infrastructure::compiled()
        );
        assert_eq!(current.catalog_url, old.catalog_url);
        assert_eq!(current.dns_enabled, Some(true));
        assert_eq!(current.source_generation, 1);
        assert_eq!(load(root.path()).unwrap(), current);
    }
    #[test]
    fn invalid_address_does_not_replace_settings() {
        let root = tempfile::tempdir().unwrap();
        let mut config = load(root.path()).unwrap();
        for url in [
            "http://example.org/index.json",
            "file:///data/local/file",
            "https://user:pass@example.org/x",
            "https://example.org/#x",
            "https://example.org/\n",
        ] {
            assert!(change_url(root.path(), &mut config, url).is_err(), "{url}");
            assert_eq!(load(root.path()).unwrap(), Config::default());
        }
    }
    #[test]
    fn damaged_settings_are_not_silently_reset_and_links_are_rejected() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("settings.json"), b"broken").unwrap();
        assert!(load(root.path()).is_err());
        assert_eq!(
            fs::read(root.path().join("settings.json")).unwrap(),
            b"broken"
        );
        fs::remove_file(root.path().join("settings.json")).unwrap();
        let other = root.path().join("other");
        fs::write(&other, b"untouched").unwrap();
        std::os::unix::fs::symlink(&other, root.path().join("settings.json")).unwrap();
        assert!(load(root.path()).is_err());
        assert_eq!(fs::read(other).unwrap(), b"untouched");
    }
}

/// Save the URL and the next-install DNS choice together; changing DNS alone does not invalidate an archive.
pub fn change_settings(
    root: &Path,
    current: &mut Config,
    input: &str,
    dns: Option<bool>,
) -> io::Result<bool> {
    let url = normalize_url(input)?;
    let changed = current.catalog_url != url;
    let mut next = current.clone();
    next.catalog_url = url;
    next.dns_enabled = dns;
    if changed {
        next.source_generation = next
            .source_generation
            .checked_add(1)
            .ok_or_else(|| invalid("Счётчик источника исчерпан"))?;
    }
    save(root, &next)?;
    *current = next;
    Ok(changed)
}

#[cfg(test)]
mod dns_tests {
    use super::*;
    #[test]
    fn old_settings_default_to_preserving_dns_and_changes_are_atomic() {
        let original: Config = serde_json::from_value(
            serde_json::json!({"schema":1,"infrastructure":release_core::infrastructure::Infrastructure::compiled(),"catalogUrl":DEFAULT_CATALOG_URL,"sourceGeneration":0}),
        )
        .unwrap();
        assert_eq!(original.dns_enabled, None);
        let root = tempfile::tempdir().unwrap();
        let mut config = original;
        assert!(
            !change_settings(root.path(), &mut config, DEFAULT_CATALOG_URL, Some(true)).unwrap()
        );
        assert_eq!(load(root.path()).unwrap().dns_enabled, Some(true));
        let before = config.clone();
        assert!(
            change_settings(root.path(), &mut config, "http://example.com", Some(false)).is_err()
        );
        assert_eq!(config, before);
        assert_eq!(load(root.path()).unwrap(), before);
    }
}
