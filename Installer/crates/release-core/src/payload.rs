use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    fs::File,
    io::Read,
    path::{Component, Path, PathBuf},
};

pub const PAYLOAD_FORMAT: u32 = 5;

pub fn validate_schema(schema: u32) -> Result<()> {
    if schema < PAYLOAD_FORMAT {
        return Err(Error::new(
            "PAYLOAD_SCHEMA_UNSUPPORTED",
            "Архив прежнего формата не поддерживается. Выберите новый релиз VoyahTune.",
        ));
    }
    if schema != PAYLOAD_FORMAT {
        return Err(Error::new(
            "INSTALLER_UPDATE_REQUIRED",
            "Этот формат релиза требует обновления установщика",
        ));
    }
    Ok(())
}

pub const NATIVE: &str = "ru.big.town.anative";
pub const RESTORE: &str = "ru.big.town.restoremode";
pub const UPDATER: &str = "ru.big.town.updater";
pub const RUNYN: &str = "big.town.runyn";
pub const NATIVE_PATH: &str = "/system/priv-app/Native/Native.apk";
pub const UPDATER_PATH: &str = "/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk";
pub const WHITELIST: &str = "/system/etc/permissions/privapp-permissions-ru.big.town.anative.xml";
pub const NATIVE_RUNTIME_ROLES: &[(&str, &str, u32)] = &[
    ("voyahtune-new-voyahtune-loader", "voyahtune-loader", 0o755),
    ("voyahtune-new-voyahtune-inject", "voyahtune-inject", 0o755),
    (
        "voyahtune-new-voyahtune-inject-arm",
        "voyahtune-inject-arm",
        0o755,
    ),
    (
        "voyahtune-new-libvoyahtune-agent.so",
        "libvoyahtune-agent.so",
        0o644,
    ),
    (
        "voyahtune-new-libvoyahtune-agent-arm.so",
        "libvoyahtune-agent-arm.so",
        0o644,
    ),
    ("voyahtune-new-agents.dex", "agents.dex", 0o644),
    ("voyahtune-new-stop-loader.sh", "stop-loader.sh", 0o755),
    (
        "voyahtune-new-voyahtune_keyboard_en_config.json",
        "voyahtune_keyboard_en_config.json",
        0o644,
    ),
    (
        "voyahtune-new-voyahtune_keyboard_ru_config.json",
        "voyahtune_keyboard_ru_config.json",
        0o644,
    ),
    (
        "voyahtune-new-voyahtune_skb_qwerty_ru.json",
        "voyahtune_skb_qwerty_ru.json",
        0o644,
    ),
];
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BuildMetadata {
    pub infrastructure: crate::infrastructure::Infrastructure,
    #[serde(default)]
    pub recipe_sha256: Option<String>,
    #[serde(default)]
    pub payload_format: u32,
    #[serde(default)]
    pub minimum_installer_version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_readiness_schema: Option<u32>,
    pub schema: u32,
    pub product: String,
    pub component: String,
    pub release_version: String,
    pub build_revision: String,
    #[serde(default)]
    pub runtime_hashes: std::collections::BTreeMap<String, String>,
}

impl BuildMetadata {
    fn require_runtime_protocol(&self) -> Result<()> {
        if self.runtime_readiness_schema != Some(crate::runtime_readiness::RUNTIME_READINESS_SCHEMA)
        {
            return Err(Error::new(
                "PAYLOAD_RUNTIME_PROTOCOL",
                "Архив использует неподдерживаемый протокол загрузчика. Выберите актуальный релиз VoyahTune.",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Artifact {
    pub name: String,
    pub path: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Manifest {
    pub infrastructure: crate::infrastructure::Infrastructure,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub removal_only: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub requirements: Option<crate::compatibility::Requirements>,
    #[serde(default)]
    pub recipe: crate::recipe::Recipe,
    pub schema: u32,
    pub product: String,
    pub release_version: String,
    pub build_revision: String,
    pub artifacts: Vec<Artifact>,
}

#[derive(Clone)]
pub struct Payload {
    pub root: PathBuf,
    pub manifest: Manifest,
}

impl Payload {
    pub fn open(root: &Path) -> Result<Self> {
        let payload = Self::load(root)?;
        payload.verify()?;
        Ok(payload)
    }

    pub fn load(root: &Path) -> Result<Self> {
        Self::from_layout(root, true)
    }

    pub fn for_ota(root: &Path) -> Result<Self> {
        Self::from_layout(root, false)
    }

    fn from_layout(root: &Path, verify_metadata: bool) -> Result<Self> {
        let root = root.canonicalize()?;
        let restore = root.join("common/restore_mode.apk");
        let removal_only = !restore.exists()
            && !root.join("common/native.apk").exists()
            && root.join("common/dns-helper.sh").is_file()
            && root.join("common/init.logcat.original.sh").is_file();
        let mut requirements = crate::compatibility::Requirements::unified();
        let (release_version, build_revision) = if removal_only {
            (env!("CARGO_PKG_VERSION").into(), "builtin-remover".into())
        } else if verify_metadata {
            let metadata = apk_metadata(&restore)?
                .ok_or_else(|| Error::new("APK_METADATA", "Нет метаданных релиза в RestoreMode"))?;
            validate_schema(metadata.payload_format)?;
            metadata.require_runtime_protocol()?;
            if let Some(minimum) = metadata.minimum_installer_version {
                requirements.min_installer_version = minimum;
            }
            requirements.validate()?;
            metadata
                .infrastructure
                .require_version(&metadata.release_version)?;
            (metadata.release_version, metadata.build_revision)
        } else {
            let metadata = apk_metadata_value(&restore)
                .ok()
                .flatten()
                .unwrap_or_default();
            (
                metadata["releaseVersion"]
                    .as_str()
                    .unwrap_or_default()
                    .into(),
                metadata["buildRevision"]
                    .as_str()
                    .unwrap_or_default()
                    .into(),
            )
        };
        let mut recipe = crate::recipe::Recipe::default();
        if removal_only {
            recipe.remove_directories = recipe.cleanup_directories();
        }
        let names: BTreeSet<&str> = if removal_only {
            ["dns-helper.sh", "init.logcat.original.sh"]
                .into_iter()
                .collect()
        } else {
            recipe
                .files
                .iter()
                .map(|file| file.artifact.as_str())
                .chain(
                    recipe
                        .packages
                        .iter()
                        .map(|package| package.artifact.as_str()),
                )
                .chain([
                    "dns-helper.sh",
                    "dns.apk",
                    "init.logcat.sh",
                    "init.logcat.original.sh",
                ])
                .collect()
        };
        let mut artifacts = Vec::new();
        for name in names {
            let path = format!("common/{name}");
            let source = root.join(&path);
            let (size, digest) = if verify_metadata {
                let canonical = source.canonicalize()?;
                if !canonical.starts_with(&root)
                    || source.symlink_metadata()?.file_type().is_symlink()
                {
                    return Err(Error::new("PAYLOAD_PATH", "Файл выходит за пределы релиза"));
                }
                (source.metadata()?.len(), sha256(&source)?)
            } else {
                (0, String::new())
            };
            artifacts.push(Artifact {
                name: name.into(),
                path,
                size,
                sha256: digest,
            });
        }
        let manifest = Manifest {
            schema: PAYLOAD_FORMAT,
            infrastructure: crate::infrastructure::Infrastructure::Unified,
            removal_only,
            requirements: Some(requirements),
            recipe,
            product: "VoyahTune".into(),
            release_version,
            build_revision,
            artifacts,
        };
        Ok(Self { root, manifest })
    }

    pub fn identity(&self) -> Result<String> {
        let mut hash = Sha256::new();
        for artifact in &self.manifest.artifacts {
            hash.update((artifact.name.len() as u64).to_le_bytes());
            hash.update(artifact.name.as_bytes());
            hash.update(sha256(&self.path(artifact)?)?.as_bytes());
        }
        Ok(hex::encode(hash.finalize()))
    }

    pub fn save_removal(&self, destination: &Path) -> Result<()> {
        let parent = destination
            .parent()
            .ok_or_else(|| Error::new("RECOVERY_PATH", "Нет папки восстановления"))?;
        std::fs::create_dir_all(parent)?;
        let stage = tempfile::tempdir_in(parent)?;
        let mut manifest = self.manifest.clone();
        manifest.removal_only = true;
        manifest.artifacts.retain(|artifact| {
            ["dns-helper.sh", "init.logcat.original.sh"].contains(&artifact.name.as_str())
        });
        for artifact in &manifest.artifacts {
            let target = stage.path().join(&artifact.path);
            std::fs::create_dir_all(target.parent().unwrap())?;
            std::fs::copy(self.path(artifact)?, target)?;
        }
        Self::open(stage.path())?;
        // Versioned recovery directories are immutable and separate from download cache.
        if !destination.exists() {
            std::fs::rename(stage.path(), destination)?;
        }
        Ok(())
    }

    pub fn artifact(&self, name: &str) -> Result<&Artifact> {
        self.manifest
            .artifacts
            .iter()
            .find(|artifact| artifact.name == name)
            .ok_or_else(|| {
                Error::new("PAYLOAD_MISSING", "В релизе отсутствует обязательный файл").detail(name)
            })
    }

    pub fn path(&self, artifact: &Artifact) -> Result<PathBuf> {
        if !crate::paths::safe_path(&artifact.path)
            || Path::new(&artifact.path)
                .components()
                .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(
                Error::new("PAYLOAD_PATH", "Недопустимый путь в релизе").detail(&artifact.path)
            );
        }
        let path = self.root.join(&artifact.path).canonicalize()?;
        if !path.starts_with(&self.root) {
            return Err(Error::new("PAYLOAD_PATH", "Файл выходит за пределы релиза"));
        }
        Ok(path)
    }

    pub fn file(&self, name: &str) -> Result<PathBuf> {
        self.path(self.artifact(name)?)
    }

    pub fn verify(&self) -> Result<()> {
        self.manifest.recipe.validate()?;
        if !self
            .manifest
            .requirements
            .as_ref()
            .is_some_and(|requirements| {
                requirements
                    .required_capabilities
                    .iter()
                    .any(|capability| capability == "unified-runtime-v1")
            })
        {
            return Err(Error::new(
                "REQUIREMENTS_INVALID",
                "Общий payload требует unified-runtime-v1",
            ));
        }
        let loader = "voyahtune-new-voyahtune-loader";
        if !self
            .manifest
            .recipe
            .files
            .iter()
            .any(|file| file.artifact == loader)
        {
            return Err(Error::new(
                "INFRASTRUCTURE_RECIPE",
                "Загрузчик не соответствует инфраструктуре релиза",
            ));
        }
        let recipe_sha = hex::encode(Sha256::digest(serde_json::to_vec(&serde_json::to_value(
            &self.manifest.recipe,
        )?)?));
        let mut seen = BTreeSet::new();
        for artifact in &self.manifest.artifacts {
            if !seen.insert(artifact.name.clone()) {
                return Err(Error::new(
                    "PAYLOAD_DUPLICATE",
                    "Дублирующийся файл в релизе",
                ));
            }
            let path = self.path(artifact)?;
            if artifact.size == 0
                || path.metadata()?.len() != artifact.size
                || sha256(&path)? != artifact.sha256
            {
                return Err(Error::new(
                    "PAYLOAD_HASH",
                    "Файл установщика повреждён. Загрузите полный релиз заново.",
                )
                .detail(&artifact.path));
            }
        }
        if self.manifest.removal_only {
            for name in ["dns-helper.sh", "init.logcat.original.sh"] {
                self.file(name)?;
            }
            return Ok(());
        }
        for (name, component) in [("native.apk", NATIVE), ("restore_mode.apk", RESTORE)] {
            let apk = self.file(name)?;
            verified_signers(&apk)?;
            let metadata = apk_metadata(&apk)?
                .ok_or_else(|| Error::new("APK_METADATA", "APK не содержит метаданные сборки"))?;
            metadata.require_runtime_protocol()?;
            if metadata.recipe_sha256.as_deref() != Some(&recipe_sha) {
                return Err(Error::new(
                    "RECIPE_SIGNATURE",
                    "План установки не совпадает с подписанным APK",
                )
                .detail(name));
            }
            for file in self.manifest.recipe.runtime() {
                if metadata.runtime_hashes.get(&file.artifact)
                    != Some(&self.artifact(&file.artifact)?.sha256)
                {
                    return Err(Error::new(
                        "APK_RUNTIME_HASH",
                        "Подписанные хеши компонентов не совпадают с релизом",
                    )
                    .detail(&file.artifact));
                }
            }
            for package in self
                .manifest
                .recipe
                .packages
                .iter()
                .filter(|package| package.artifact != "restore_mode.apk")
            {
                if metadata.runtime_hashes.get(&package.artifact)
                    != Some(&self.artifact(&package.artifact)?.sha256)
                {
                    return Err(Error::new(
                        "APK_RUNTIME_HASH",
                        "Подписанный хеш приложения не совпадает с релизом",
                    )
                    .detail(&package.artifact));
                }
            }
            if metadata.schema != 3
                || metadata.payload_format != PAYLOAD_FORMAT
                || metadata.infrastructure != self.manifest.infrastructure
                || metadata.product != "VoyahTune"
                || metadata.component != component
                || metadata.release_version != self.manifest.release_version
                || metadata.build_revision != self.manifest.build_revision
            {
                return Err(
                    Error::new("APK_METADATA", "Метаданные APK не соответствуют релизу")
                        .detail(name),
                );
            }
        }
        let updater = self.file("voyahtune-updater.apk")?;
        if crate::apk_identity::read(&updater)?.0 != UPDATER
            || verified_signers(&updater)? != verified_signers(&self.file("native.apk")?)?
        {
            return Err(Error::new(
                "APK_IDENTITY",
                "Неверный пакет или подпись Updater",
            ));
        }
        for file in &self.manifest.recipe.files {
            self.artifact(&file.artifact)?;
        }
        for package in &self.manifest.recipe.packages {
            let path = self.file(&package.artifact)?;
            verified_signers(&path)?;
            if crate::apk_identity::read(&path)?.0 != package.package {
                return Err(
                    Error::new("APK_IDENTITY", "Package ID APK не соответствует recipe")
                        .detail(&package.artifact),
                );
            }
            if package.package == RUNYN && false {
                return Err(Error::new(
                    "INFRASTRUCTURE_RECIPE",
                    "RunYN не поддерживается прежним OD payload",
                ));
            }
        }
        for name in [
            "dns-helper.sh",
            "dns.apk",
            "init.logcat.sh",
            "init.logcat.original.sh",
        ] {
            self.artifact(name)?;
        }
        Ok(())
    }
}

pub fn destination(name: &str) -> Option<(String, u32)> {
    if let Some((_, filename, mode)) = NATIVE_RUNTIME_ROLES
        .iter()
        .find(|(role, _, _)| *role == name)
    {
        return Some((format!("/data/local/bin/voyahtune-new/{filename}"), *mode));
    }
    match name {
        "native.apk" => Some((NATIVE_PATH.into(), 0o644)),
        "voyahtune-updater" => Some(("/data/local/bin/voyahtune-updater".into(), 0o755)),
        "voyahtune-updater.apk" => Some((UPDATER_PATH.into(), 0o644)),
        "voyahtune.updater.rc" => Some(("/system/etc/init/voyahtune.updater.rc".into(), 0o644)),
        "voyahtune-ota-bootstrap.json" => {
            Some(("/system/etc/voyahtune-ota-bootstrap.json".into(), 0o644))
        }
        "whitelist.xml" => Some((WHITELIST.into(), 0o644)),
        "voyahtune.load.rc" => Some(("/system/etc/init/voyahtune.load.rc".into(), 0o644)),
        "voyahtune.load.sh" => Some(("/system/etc/init.voyahtune.load.sh".into(), 0o755)),
        _ => None,
    }
}

pub fn sha256(path: &Path) -> Result<String> {
    let mut file = File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 65536];
    loop {
        let bytes_read = file.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        hash.update(&buffer[..bytes_read]);
    }
    Ok(hex::encode(hash.finalize()))
}

fn apk_metadata_value(path: &Path) -> Result<Option<serde_json::Value>> {
    let mut zip = zip::ZipArchive::new(File::open(path)?)
        .map_err(|error| Error::new("APK_FORMAT", "Не удалось прочитать APK").detail(error))?;
    let mut metadata_file = match zip.by_name("assets/voyahtune-build.json") {
        Ok(metadata_file) => metadata_file,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(error) => return Err(Error::new("APK_FORMAT", "Повреждён APK").detail(error)),
    };
    if metadata_file.size() > 65536 {
        return Err(Error::new(
            "APK_METADATA",
            "Слишком большой файл метаданных APK",
        ));
    }
    let mut bytes = Vec::new();
    metadata_file.by_ref().take(65537).read_to_end(&mut bytes)?;
    let value: serde_json::Value = serde_json::from_slice(&bytes)?;
    Ok(Some(value))
}

pub fn apk_metadata(path: &Path) -> Result<Option<BuildMetadata>> {
    apk_metadata_value(path)?
        .map(serde_json::from_value)
        .transpose()
        .map_err(Into::into)
}
/// Verify v2 signatures AND the APK content digest. apksig::Apk::verify alone only
/// verifies the signing block, so it must not be used as a content-integrity check.
/// The current release keys use RSA. Unsupported schemes fail closed.
pub fn verified_signers(path: &Path) -> Result<Vec<String>> {
    use apksig::{Apk, ValueSigningBlock};
    let fail = |error: String| {
        Error::new("APK_SIGNATURE", "Не удалось подтвердить подпись APK")
            .detail(format!("{}: {error}", path.display()))
    };
    let apk = Apk::new(path.to_path_buf())?;
    let block = apk
        .get_signing_block()
        .map_err(|error| fail(error.to_string()))?;
    let mut result = Vec::new();
    for block in block.content {
        if let ValueSigningBlock::SignatureSchemeV2Block(v2) = block {
            for signer in v2.signers.signers_data {
                let data = signer.signed_data.to_u8();
                let data = data
                    .get(4..)
                    .ok_or_else(|| fail("Invalid signed data".into()))?;
                if signer.signatures.signatures_data.is_empty() {
                    return Err(fail("Empty signature list".into()));
                }
                for signature in &signer.signatures.signatures_data {
                    let algorithm = &signature.signature_algorithm_id;
                    let digest = signer
                        .signed_data
                        .digests
                        .digests_data
                        .iter()
                        .find(|candidate| candidate.signature_algorithm_id == *algorithm)
                        .ok_or_else(|| fail("Missing content digest".into()))?;
                    algorithm
                        .verify(&signer.pub_key.data, data, &signature.signature)
                        .map_err(&fail)?;
                    if apk
                        .digest(algorithm)
                        .map_err(|error| fail(error.to_string()))?
                        != digest.digest
                    {
                        return Err(fail("APK content digest mismatch".into()));
                    }
                }
                // Bind both the verified key and signed certificates; PackageManager additionally
                // checks its Android-specific certificate/rotation policy at installation time.
                let certificates = &signer.signed_data.certificates.certificates_data;
                if certificates.is_empty() {
                    return Err(fail("Missing certificate".into()));
                }
                let mut hash = Sha256::new();
                hash.update(&signer.pub_key.data);
                for certificate in certificates {
                    hash.update(&certificate.certificate);
                }
                result.push(hex::encode(hash.finalize()));
            }
        }
    }
    result.sort();
    result.dedup();
    if result.is_empty() {
        return Err(fail("RSA v2 signature required".into()));
    }
    Ok(result)
}

#[derive(Deserialize)]
struct HostFiles {
    schema: u32,
    files: Vec<HostFile>,
    #[serde(default)]
    platform: Option<String>,
}

#[derive(Deserialize)]
struct HostFile {
    path: String,
    sha256: String,
}

pub fn verify_host(bundle: &Path) -> Result<()> {
    let root = bundle.canonicalize()?;
    let host: HostFiles = serde_json::from_reader(File::open(root.join("host-tools.json"))?)?;
    if host.schema != 1 || host.files.is_empty() {
        return Err(Error::new("HOST_TOOLS", "Неполный набор инструментов"));
    }
    let mut has_adb = false;
    for file in host.files {
        if Path::new(&file.path)
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
            || !crate::paths::safe_path(&file.path.replace('+', "_"))
            || !(file.path.starts_with("adb/") || file.path.starts_with("recovery/"))
        {
            return Err(Error::new(
                "HOST_TOOLS_PATH",
                "Недопустимый путь инструмента",
            ));
        }
        let path = root.join(&file.path).canonicalize()?;
        if !path.starts_with(&root) || sha256(&path)? != file.sha256 {
            return Err(Error::new(
                "HOST_TOOLS_HASH",
                "Встроенный ADB или его библиотека повреждены",
            )
            .detail(file.path));
        }
        if file.path
            == if host.platform.as_deref() == Some("windows")
                || (host.platform.is_none() && cfg!(windows))
            {
                "adb/adb.exe"
            } else {
                "adb/adb"
            }
        {
            has_adb = true;
        }
    }
    if !has_adb {
        return Err(Error::new(
            "ADB_MISSING",
            "В установщике нет ADB для этой платформы",
        ));
    }
    Ok(())
}

/// Explicit release selection wins; never search arbitrary parent directories.
pub fn locate(bundle: &Path, executable: &Path, explicit: Option<&Path>) -> Result<PathBuf> {
    let external = explicit
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("VOYAHTUNE_PAYLOAD").map(PathBuf::from));
    let mut candidates = Vec::new();
    if let Some(path) = external {
        candidates.push(path);
    } else {
        candidates.push(bundle.join("payload"));
        if let Some(app) = executable
            .ancestors()
            .find(|path| path.extension().is_some_and(|extension| extension == "app"))
        {
            if let Some(parent) = app.parent() {
                candidates.push(parent.join("payload"));
            }
        }
        if let Some(parent) = executable.parent() {
            candidates.push(parent.join("payload"));
        }
    }
    for candidate in candidates {
        let directory = candidate;
        if directory.join("common/restore_mode.apk").is_file()
            || directory.join("common/dns-helper.sh").is_file()
        {
            return Ok(directory);
        }
    }
    Err(Error::new("PAYLOAD_MISSING", "Не найден встроенный релиз VoyahTune").retry("Повторно распакуйте или переустановите полный установщик. Для диагностики можно указать каталог payload."))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_owned_destinations() {
        assert!(destination("/system/bin/sh").is_none());
        assert!(destination("../../bin/sh").is_none());
        assert!(destination("load.bin").is_none());
        assert_eq!(
            destination("voyahtune-new-voyahtune-inject").unwrap().1,
            0o755
        );
        assert_eq!(
            destination("voyahtune-new-libvoyahtune-agent.so")
                .unwrap()
                .1,
            0o644
        );
    }
}
