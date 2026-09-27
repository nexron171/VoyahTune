//! Release catalog, verified downloads and offline cache. No vehicle commands here.
use crate::{
    compatibility::{Requirements, INSTALLER_VERSION},
    payload::{self, Payload},
    recovery, Error, Result,
};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};

pub const CATALOG_URL: &str =
    "https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Installer/releases/index.json";
const MAX_CATALOG: u64 = 4 * 1024 * 1024;
const MAX_ARCHIVE: u64 = 2 * 1024 * 1024 * 1024;
const MAX_EXTRACTED: u64 = 4 * 1024 * 1024 * 1024;
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
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CachedPayload {
    pub version: String,
    pub path: PathBuf,
    pub manifest_sha256: String,
    pub deletable: bool,
}
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogState {
    pub catalog: Catalog,
    pub cached: Vec<CachedPayload>,
    pub warning: Option<String>,
    pub installer_version: String,
}
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub stage: String,
    pub bytes: u64,
    pub total: u64,
}
pub type ProgressCallback<'a> = &'a dyn Fn(Progress);
#[derive(Clone)]
pub struct Cache {
    pub root: PathBuf,
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
fn network(e: impl ToString) -> Error {
    Error::new(
        "DOWNLOAD_FAILED",
        "Не удалось скачать файл. Проверьте подключение или откройте локальный ZIP.",
    )
    .detail(e)
    .retry("Повторите загрузку или выберите сохранённый релиз.")
}
fn cancelled(cancel: &AtomicBool) -> Result<()> {
    if cancel.load(Ordering::Relaxed) {
        Err(Error::new("CANCELLED", "Загрузка отменена"))
    } else {
        Ok(())
    }
}
fn agent(seconds: u64) -> ureq::Agent {
    ureq::Agent::config_builder()
        .https_only(true)
        .max_redirects(5)
        .timeout_global(Some(Duration::from_secs(seconds)))
        .timeout_connect(Some(Duration::from_secs(10)))
        .timeout_recv_body(Some(Duration::from_secs(30)))
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .new_agent()
}
impl Cache {
    pub fn user() -> Result<Self> {
        let data = recovery::data_dir()?;
        Ok(Self {
            root: data.parent().unwrap().join("downloads"),
        })
    }
    fn lock(&self) -> Result<File> {
        self.lock_with(false)
    }
    /// Keep cached files available while another GUI instance may manage downloads.
    pub fn retain_for_operation(&self) -> Result<File> {
        self.lock_with(true)
    }
    fn lock_with(&self, shared: bool) -> Result<File> {
        fs::create_dir_all(&self.root)?;
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(self.root.join("cache.lock"))?;
        let locked = if shared {
            fs2::FileExt::try_lock_shared(&file)
        } else {
            file.try_lock_exclusive()
        };
        locked.map_err(|e| {
            Error::new("CACHE_BUSY", "Другой экземпляр установщика использует кэш").detail(e)
        })?;
        Ok(file)
    }
    pub fn state(&self, refresh: bool) -> Result<CatalogState> {
        self.state_with(|| self.fetch_catalog(), refresh)
    }
    fn state_with(
        &self,
        fetch: impl FnOnce() -> Result<Catalog>,
        refresh: bool,
    ) -> Result<CatalogState> {
        let _lock = self.lock()?;
        let stored = self.root.join("catalog.json");
        let mut warning = None;
        let mut catalog = None;
        if refresh {
            match fetch() {
                Ok(value) => {
                    recovery::write_json(&stored, &value)?;
                    catalog = Some(value);
                }
                Err(e) => warning = Some(e.message + " " + &e.detail),
            }
        }
        if catalog.is_none() && stored.is_file() {
            let result: Result<Catalog> = (|| {
                let mut value: Catalog = serde_json::from_reader(File::open(&stored)?)?;
                value.validate()?;
                Ok(value)
            })();
            match result {
                Ok(value) => catalog = Some(value),
                Err(e) => warning = Some(e.to_string()),
            }
        }
        Ok(CatalogState {
            catalog: catalog.unwrap_or_else(Catalog::empty),
            cached: self.list()?,
            warning,
            installer_version: INSTALLER_VERSION.into(),
        })
    }
    fn fetch_catalog(&self) -> Result<Catalog> {
        fetch_catalog(&agent(15), CATALOG_URL)
    }
    pub fn list(&self) -> Result<Vec<CachedPayload>> {
        let folder = self.root.join("payloads");
        let mut list = vec![];
        if !folder.exists() {
            return Ok(list);
        }
        for entry in fs::read_dir(folder)? {
            let path = entry?.path();
            if path
                .file_name()
                .is_none_or(|n| !digest_valid(&n.to_string_lossy()))
            {
                continue;
            }
            if let Ok(p) = Payload::load(&path) {
                list.push(CachedPayload {
                    version: p.manifest.release_version,
                    manifest_sha256: payload::sha256(&path.join("manifest.json"))?,
                    path,
                    deletable: true,
                });
            }
        }
        list.sort_by(|a, b| {
            semver::Version::parse(&b.version)
                .ok()
                .cmp(&semver::Version::parse(&a.version).ok())
        });
        Ok(list)
    }
    /// Only content-addressed entries owned by this cache may be removed.
    /// Imported source ZIPs, external folders and bundled recovery are untouched.
    pub fn remove(&self, path: &Path) -> Result<()> {
        let _lock = self.lock()?;
        let folder = self.root.join("payloads");
        let digest = path.file_name().and_then(|s| s.to_str()).unwrap_or("");
        if !digest_valid(digest) || path.parent() != Some(folder.as_path()) {
            return Err(Error::new(
                "CACHE_PATH",
                "Удалять можно только скачанные релизы из кэша",
            ));
        }
        let root = self.root.canonicalize()?;
        if folder.canonicalize()? != root.join("payloads")
            || fs::symlink_metadata(path)?.file_type().is_symlink()
            || path.canonicalize()? != root.join("payloads").join(digest)
        {
            return Err(Error::new(
                "CACHE_PATH",
                "Небезопасный путь релиза в кэше",
            ));
        }
        let receipts = self.root.join("receipts");
        if receipts.exists() {
            if receipts.canonicalize()? != root.join("receipts") {
                return Err(Error::new("CACHE_PATH", "Небезопасный путь квитанций кэша"));
            }
            match fs::remove_file(receipts.join(digest)) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
            }
        }
        fs::remove_dir_all(path)?;
        Ok(())
    }
    pub fn download(
        &self,
        release: &Release,
        cancel: &AtomicBool,
        progress: ProgressCallback,
    ) -> Result<Payload> {
        cancelled(cancel)?;
        payload::validate_schema(release.payload.manifest_schema)?;
        release.requirements.validate()?;
        https_url(&release.payload.url)?;
        if !digest_valid(&release.payload.sha256)
            || release.payload.size == 0
            || release.payload.size > MAX_ARCHIVE
        {
            return Err(invalid("Некорректный архив"));
        }
        let _lock = self.lock()?;
        let target = self.root.join("payloads").join(&release.payload.sha256);
        let receipt = self.root.join("receipts").join(&release.payload.sha256);
        if let Ok(payload) = Payload::open(&target) {
            if fs::read_to_string(&receipt).ok().as_deref()
                == Some(&payload::sha256(&target.join("manifest.json"))?)
            {
                cancelled(cancel)?;
                verify_release(&payload, release)?;
                return Ok(payload);
            }
        }
        let mut archive = tempfile::NamedTempFile::new_in(&self.root)?;
        cancelled(cancel)?;
        let mut response = agent(1800)
            .get(&release.payload.url)
            .call()
            .map_err(network)?;
        let mut reader = response.body_mut().as_reader();
        transfer(
            &mut reader,
            archive.as_file_mut(),
            release.payload.size,
            cancel,
            progress,
            "download",
        )?;
        if archive.as_file().metadata()?.len() != release.payload.size
            || payload::sha256(archive.path())? != release.payload.sha256
        {
            return Err(Error::new(
                "DOWNLOAD_HASH",
                "Размер или SHA-256 загруженного архива не совпадает с каталогом",
            ));
        }
        self.accept(archive.path(), Some(release), cancel, progress)
    }
    pub fn import(
        &self,
        path: &Path,
        cancel: &AtomicBool,
        progress: ProgressCallback,
    ) -> Result<Payload> {
        let _lock = self.lock()?;
        if path.metadata()?.len() > MAX_ARCHIVE {
            return Err(Error::new("ARCHIVE_SIZE", "Слишком большой архив"));
        }
        self.accept(path, None, cancel, progress)
    }
    fn accept(
        &self,
        archive: &Path,
        expected: Option<&Release>,
        cancel: &AtomicBool,
        progress: ProgressCallback,
    ) -> Result<Payload> {
        let digest = payload::sha256(archive)?;
        let folder = self.root.join("payloads");
        fs::create_dir_all(&folder)?;
        let stage = tempfile::Builder::new()
            .prefix(".staging-")
            .tempdir_in(&folder)?;
        extract(archive, stage.path(), cancel, progress)?;
        progress(Progress {
            stage: "verify".into(),
            bytes: 0,
            total: 0,
        });
        cancelled(cancel)?;
        let payload = Payload::open(stage.path())?;
        if payload.manifest.removal_only {
            return Err(Error::new(
                "PAYLOAD_REQUIRED",
                "Это ресурсы удаления, а не установки",
            ));
        }
        if payload.manifest.schema != 4 {
            return Err(Error::new(
                "PAYLOAD_SCHEMA",
                "Импорт ZIP требует новый единый payload. Старую папку можно открыть отдельно.",
            ));
        }
        if let Some(release) = expected {
            verify_release(&payload, release)?;
        }
        cancelled(cancel)?;
        let target = folder.join(digest);
        if target.exists() {
            if let Ok(p) = Payload::open(&target) {
                if payload::sha256(&target.join("manifest.json"))?
                    == payload::sha256(&stage.path().join("manifest.json"))?
                {
                    return Ok(p);
                }
            }
            // Keep corrupt data for diagnosis; never expose a partial replacement.
            fs::rename(
                &target,
                self.root
                    .join(format!("corrupt-{}", chrono::Utc::now().timestamp_millis())),
            )?;
        }
        fs::rename(stage.path(), &target)?;
        fs::create_dir_all(self.root.join("receipts"))?;
        fs::write(
            self.root.join("receipts").join(target.file_name().unwrap()),
            payload::sha256(&target.join("manifest.json"))?,
        )?;
        progress(Progress {
            stage: "ready".into(),
            bytes: 0,
            total: 0,
        });
        Payload::load(&target)
    }
}
fn fetch_catalog(client: &ureq::Agent, url: &str) -> Result<Catalog> {
    let mut response = client.get(url).call().map_err(network)?;
    let bytes = response
        .body_mut()
        .with_config()
        .limit(MAX_CATALOG)
        .read_to_vec()
        .map_err(network)?;
    let mut catalog: Catalog = serde_json::from_slice(&bytes)?;
    catalog.validate()?;
    Ok(catalog)
}
fn verify_release(payload: &Payload, release: &Release) -> Result<()> {
    if payload.manifest.release_version != release.version
        || payload.manifest.schema != release.payload.manifest_schema
        || payload.manifest.requirements.as_ref() != Some(&release.requirements)
    {
        return Err(Error::new(
            "CATALOG_MISMATCH",
            "Манифест архива не соответствует выбранному релизу",
        ));
    }
    Ok(())
}
fn transfer(
    reader: &mut impl Read,
    writer: &mut impl Write,
    limit: u64,
    cancel: &AtomicBool,
    progress: ProgressCallback,
    stage: &str,
) -> Result<u64> {
    let mut buffer = [0u8; 128 * 1024];
    let mut count = 0;
    let mut reported = 0;
    loop {
        cancelled(cancel)?;
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        count += n as u64;
        if count > limit {
            return Err(Error::new(
                "ARCHIVE_SIZE",
                "Превышен допустимый размер файла",
            ));
        }
        writer.write_all(&buffer[..n])?;
        if count - reported >= 1024 * 1024 {
            progress(Progress {
                stage: stage.into(),
                bytes: count,
                total: limit,
            });
            reported = count;
        }
    }
    writer.flush()?;
    Ok(count)
}
fn extract(
    source: &Path,
    target: &Path,
    cancel: &AtomicBool,
    progress: ProgressCallback,
) -> Result<()> {
    let mut archive =
        zip::ZipArchive::new(File::open(source)?).map_err(|e| invalid(e.to_string()))?;
    if archive.len() > 4096 {
        return Err(invalid("Слишком много файлов в архиве"));
    }
    let mut total = 0u64;
    let mut entries = BTreeSet::new();
    let mut paths = BTreeMap::new();
    let count = archive.len();
    for i in 0..count {
        cancelled(cancel)?;
        let mut entry = archive.by_index(i).map_err(|e| invalid(e.to_string()))?;
        let name = entry.name().trim_end_matches('/').to_owned();
        if !safe_path(&name) || !entries.insert(name.to_ascii_lowercase()) {
            return Err(invalid("Недопустимый или повторный путь в ZIP"));
        }
        let kind = entry.unix_mode().unwrap_or(0) & 0o170000;
        if kind != 0 && kind != 0o100000 && kind != 0o040000 {
            return Err(invalid("Ссылки и специальные файлы в ZIP запрещены"));
        }
        let mut partial = String::new();
        let parts: Vec<_> = name.split('/').collect();
        for (index, part) in parts.iter().enumerate() {
            if index > 0 {
                partial.push('/');
            }
            partial.push_str(part);
            let directory = index + 1 < parts.len() || entry.is_dir();
            let previous = paths.insert(partial.to_ascii_lowercase(), (partial.clone(), directory));
            if previous.is_some_and(|(old, dir)| old != partial || dir != directory) {
                return Err(invalid("Конфликт путей или регистра в ZIP"));
            }
        }
        total = total
            .checked_add(entry.size())
            .ok_or_else(|| invalid("Переполнение размера ZIP"))?;
        if total > MAX_EXTRACTED {
            return Err(invalid("Слишком большой распакованный релиз"));
        }
        let output = target.join(&name);
        if entry.is_dir() {
            fs::create_dir_all(output)?;
            continue;
        }
        fs::create_dir_all(output.parent().unwrap())?;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output)?;
        let size = entry.size();
        if transfer(&mut entry, &mut file, size, cancel, &|_| {}, "extract")? != size {
            return Err(invalid("Неполный файл ZIP"));
        }
        progress(Progress {
            stage: "extract".into(),
            bytes: (i + 1) as u64,
            total: count as u64,
        });
    }
    Ok(())
}
/// Portable paths: reject traversal, Windows drives/devices and ambiguous names on macOS/Windows.
pub fn safe_path(name: &str) -> bool {
    !name.is_empty()
        && name.len() < 1024
        && name.split('/').all(|part| {
            !part.is_empty()
                && part != "."
                && part != ".."
                && !part.ends_with('.')
                && part
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
                && !matches!(
                    part.split('.')
                        .next()
                        .unwrap()
                        .to_ascii_lowercase()
                        .as_str(),
                    "con"
                        | "prn"
                        | "aux"
                        | "nul"
                        | "com1"
                        | "com2"
                        | "com3"
                        | "com4"
                        | "com5"
                        | "com6"
                        | "com7"
                        | "com8"
                        | "com9"
                        | "lpt1"
                        | "lpt2"
                        | "lpt3"
                        | "lpt4"
                        | "lpt5"
                        | "lpt6"
                        | "lpt7"
                        | "lpt8"
                        | "lpt9"
                )
        })
}
#[cfg(test)]
mod tests {
    use super::*;
    fn server(status: &str, body: &str) -> (String, std::thread::JoinHandle<()>) {
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/catalog", listener.local_addr().unwrap());
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let worker = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buffer = [0; 2048];
            let _ = stream.read(&mut buffer);
            stream.write_all(response.as_bytes()).unwrap();
        });
        (url, worker)
    }
    #[test]
    fn network_catalog_refresh_and_invalid_response_preserve_offline_copy() {
        let dir = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: dir.path().into(),
        };
        let client = ureq::Agent::config_builder()
            .https_only(false)
            .build()
            .new_agent();
        let mut a = Catalog::empty();
        a.generated_at = "A".into();
        a.releases.push(Release {
            version: "3.13.0".into(),
            published_at: "2026-09-27".into(),
            channel: "stable".into(),
            notes_url: "https://example.org/A".into(),
            requirements: Requirements::default(),
            payload: Archive {
                url: "https://example.org/A.zip".into(),
                size: 100,
                sha256: "a".repeat(64),
                manifest_schema: 4,
            },
        });
        let (url, worker) = server("200 OK", &serde_json::to_string(&a).unwrap());
        assert_eq!(
            cache
                .state_with(|| fetch_catalog(&client, &url), true)
                .unwrap()
                .catalog
                .generated_at,
            "A"
        );
        worker.join().unwrap();
        for (status, body) in [
            ("200 OK", "bad JSON"),
            ("404 Not Found", "missing"),
            ("500 Error", "failed"),
        ] {
            let (url, worker) = server(status, body);
            let state = cache
                .state_with(|| fetch_catalog(&client, &url), true)
                .unwrap();
            assert_eq!(state.catalog.generated_at, "A");
            assert!(state.warning.is_some());
            worker.join().unwrap();
        }
        let offline = cache.state_with(|| Err(network("offline")), true).unwrap();
        assert_eq!(offline.catalog.generated_at, "A");
        assert!(offline.warning.is_some());
        a.generated_at = "B".into();
        let selected = a.releases[0].clone();
        let mut next = selected.clone();
        next.version = "3.13.1".into();
        next.payload.url = "https://example.org/B.zip".into();
        next.payload.sha256 = "b".repeat(64);
        a.releases.push(next);
        // Recreate the cache handle, as on the next GUI launch.
        let reopened = Cache {
            root: dir.path().into(),
        };
        let (url, worker) = server("200 OK", &serde_json::to_string(&a).unwrap());
        let refreshed = reopened
            .state_with(|| fetch_catalog(&client, &url), true)
            .unwrap();
        assert_eq!(refreshed.catalog.generated_at, "B");
        assert!(refreshed
            .catalog
            .releases
            .iter()
            .any(|r| r.version == "3.13.1"));
        assert_eq!(selected.version, "3.13.0");
        assert_eq!(selected.payload.sha256, "a".repeat(64));
        worker.join().unwrap();
        assert!(https_url("http://127.0.0.1/archive.zip").is_err());
    }
    #[test]
    fn remove_cache_preserves_sources_and_other_entries_and_respects_lock() {
        let tmp = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: tmp.path().join("downloads"),
        };
        let digest = "a".repeat(64);
        let target = cache.root.join("payloads").join(&digest);
        let other = cache.root.join("payloads").join("b".repeat(64));
        fs::create_dir_all(&target).unwrap();
        fs::create_dir_all(&other).unwrap();
        fs::create_dir_all(cache.root.join("receipts")).unwrap();
        fs::write(target.join("apk"), "payload").unwrap();
        fs::write(cache.root.join("receipts").join(&digest), "receipt").unwrap();
        let source = tmp.path().join("source.zip");
        fs::write(&source, "original").unwrap();
        let lock = cache.lock().unwrap();
        assert_eq!(cache.remove(&target).unwrap_err().code, "CACHE_BUSY");
        fs2::FileExt::unlock(&lock).unwrap();
        drop(lock);
        let in_use = cache.retain_for_operation().unwrap();
        let another_reader = cache.retain_for_operation().unwrap();
        assert_eq!(cache.remove(&target).unwrap_err().code, "CACHE_BUSY");
        fs2::FileExt::unlock(&in_use).unwrap();
        fs2::FileExt::unlock(&another_reader).unwrap();
        assert_eq!(cache.remove(tmp.path()).unwrap_err().code, "CACHE_PATH");
        assert_eq!(
            cache
                .remove(&cache.root.join("payloads/../").join(&digest))
                .unwrap_err()
                .code,
            "CACHE_PATH"
        );
        cache.remove(&target).unwrap();
        assert!(!target.exists());
        assert!(!cache.root.join("receipts").join(&digest).exists());
        assert!(other.is_dir());
        assert_eq!(fs::read_to_string(source).unwrap(), "original");
    }
    #[cfg(unix)]
    #[test]
    fn remove_cache_rejects_links_outside_cache() {
        use std::os::unix::fs::symlink;
        let tmp = tempfile::tempdir().unwrap();
        let cache = Cache {
            root: tmp.path().join("downloads"),
        };
        let outside = tmp.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        fs::write(outside.join("keep"), "keep").unwrap();
        fs::create_dir_all(cache.root.join("payloads")).unwrap();
        let target = cache.root.join("payloads").join("a".repeat(64));
        symlink(&outside, &target).unwrap();
        assert_eq!(cache.remove(&target).unwrap_err().code, "CACHE_PATH");
        fs::remove_file(&target).unwrap();
        fs::remove_dir(cache.root.join("payloads")).unwrap();
        symlink(&outside, cache.root.join("payloads")).unwrap();
        fs::create_dir_all(outside.join("a".repeat(64))).unwrap();
        assert_eq!(cache.remove(&target).unwrap_err().code, "CACHE_PATH");
        assert!(outside.join("keep").exists());
    }
    #[test]
    fn cancellation_and_writer_errors_do_not_succeed() {
        struct FullDisk;
        impl Write for FullDisk {
            fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
                Err(std::io::Error::other("disk full"))
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        assert!(transfer(
            &mut &b"bytes"[..],
            &mut FullDisk,
            10,
            &AtomicBool::new(false),
            &|_| {},
            "download"
        )
        .is_err());
    }
    #[test]
    fn archive_identity_must_match_catalog_requirements_and_version() {
        let requirements = Requirements::default();
        let mut payload=Payload {root:PathBuf::new(),manifest:serde_json::from_value(serde_json::json!({
            "schema":4,"product":"VoyahTune","releaseVersion":"3.13.0","buildRevision":"fixture",
            "requirements":requirements,"artifacts":[]
        })).unwrap()};
        let release = Release {
            version: "3.13.0".into(),
            published_at: String::new(),
            channel: "stable".into(),
            notes_url: "https://example.org".into(),
            requirements,
            payload: Archive {
                url: "https://example.org/payload.zip".into(),
                size: 1,
                sha256: "a".repeat(64),
                manifest_schema: 4,
            },
        };
        assert!(verify_release(&payload, &release).is_ok());
        payload
            .manifest
            .requirements
            .as_mut()
            .unwrap()
            .min_installer_version = "0.1.0".into();
        assert_eq!(
            verify_release(&payload, &release).unwrap_err().code,
            "CATALOG_MISMATCH"
        );
        payload.manifest.requirements = Some(release.requirements.clone());
        payload.manifest.release_version = "3.13.1".into();
        assert!(verify_release(&payload, &release).is_err());
    }
    #[test]
    fn rejects_nonportable_archive_paths() {
        for name in [
            "../x", "/x", "C:/x", "x\\y", "x//y", "x/./y", "CON.txt", "a.", "a/../b", "",
        ] {
            assert!(!safe_path(name), "{name}");
        }
        assert!(safe_path("common/voyahtune.load.sh"));
    }
    #[test]
    fn transfer_limits_and_cancellation_are_enforced() {
        let mut output = vec![];
        let cancel = AtomicBool::new(false);
        assert!(transfer(&mut &b"1234"[..], &mut output, 3, &cancel, &|_| {}, "test").is_err());
        cancel.store(true, Ordering::Relaxed);
        assert_eq!(
            transfer(&mut &b"123"[..], &mut output, 3, &cancel, &|_| {}, "test")
                .unwrap_err()
                .code,
            "CANCELLED"
        );
    }
    #[test]
    fn future_release_remains_visible_and_semver_is_numeric() {
        let release = |version: &str| Release {
            version: version.into(),
            published_at: "2026-09-26".into(),
            channel: "stable".into(),
            notes_url: "https://example.org/notes".into(),
            payload: Archive {
                url: "https://example.org/file.zip".into(),
                size: 1,
                sha256: "a".repeat(64),
                manifest_schema: 4,
            },
            requirements: Requirements {
                min_installer_version: "99.0.0".into(),
                required_capabilities: vec![],
            },
        };
        let mut c = Catalog::empty();
        c.releases = vec![release("3.9.0"), release("3.13.0")];
        c.validate().unwrap();
        assert_eq!(c.releases[0].version, "3.13.0");
        assert!(c.releases[0].requirements.validate().is_err());
        c.releases.push(release("3.13.0"));
        assert!(c.validate().is_err());
    }
    #[test]
    fn archive_symlinks_and_case_collisions_do_not_extract() {
        for entries in [
            vec![("../escape", false)],
            vec![("A/file", false), ("a/other", false)],
            vec![("link", true)],
        ] {
            let tmp = tempfile::tempdir().unwrap();
            let path = tmp.path().join("test.zip");
            let mut writer = zip::ZipWriter::new(File::create(&path).unwrap());
            for (name, symlink) in entries {
                if symlink {
                    writer
                        .add_symlink(name, "/tmp", zip::write::SimpleFileOptions::default())
                        .unwrap();
                } else {
                    writer
                        .start_file(name, zip::write::SimpleFileOptions::default())
                        .unwrap();
                    writer.write_all(b"x").unwrap();
                }
            }
            writer.finish().unwrap();
            let dest = tmp.path().join("out");
            fs::create_dir(&dest).unwrap();
            assert!(extract(&path, &dest, &AtomicBool::new(false), &|_| {}).is_err());
        }
    }
}
