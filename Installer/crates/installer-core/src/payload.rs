use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    fs::File,
    io::Read,
    path::{Component, Path, PathBuf},
};

pub const NATIVE: &str = "ru.big.town.anative";
pub const RESTORE: &str = "ru.big.town.restoremode";
pub const NATIVE_PATH: &str = "/system/priv-app/Native/Native.apk";
pub const WHITELIST: &str = "/system/etc/permissions/privapp-permissions-ru.big.town.anative.xml";
pub const FULL_NAMES: &[&str] = &[
    "load.bin",
    "steeringwheelkeys.js",
    "launcherdock.js",
    "multidisplay.js",
    "vd_bypass.js",
    "app_client.js",
    "apollo_tech.js",
    "keyboard_lock_en.js",
    "keyboard_ru.js",
    "voyahtune_keyboard_en_config.json",
    "voyahtune_keyboard_ru_config.json",
    "voyahtune_skb_qwerty_ru.json",
    "frida-inject",
    "voyahtune.load.rc",
    "voyahtune.load.sh",
];
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Variant {
    Full,
    Light,
}
impl Variant {
    pub fn name(self) -> &'static str {
        match self {
            Self::Full => "full",
            Self::Light => "light",
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BuildMetadata {
    #[serde(default)]
    pub recipe_sha256: Option<String>,
    pub schema: u32,
    pub product: String,
    pub component: String,
    pub variant: Variant,
    pub release_version: String,
    pub build_revision: String,
    #[serde(default)]
    pub runtime_hashes: std::collections::BTreeMap<String, String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Artifact {
    pub name: String,
    pub path: String,
    pub variant: Option<Variant>,
    pub sha256: String,
    pub size: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Manifest {
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
    /// Installation uses classic per-file checks; full integrity verification is explicit.
    pub fn load(root: &Path) -> Result<Self> {
        let root = root.canonicalize()?;
        let manifest: Manifest = serde_json::from_reader(File::open(root.join("manifest.json"))?)?;
        if ![1, 2].contains(&manifest.schema)
            || manifest.product != "VoyahTune"
            || semver::Version::parse(&manifest.release_version).is_err()
        {
            return Err(Error::new(
                "PAYLOAD_SCHEMA",
                "Неподдерживаемый формат или версия комплекта",
            ));
        }
        let payload = Self { root, manifest };
        Ok(payload)
    }
    pub fn artifact(&self, name: &str, variant: Option<Variant>) -> Result<&Artifact> {
        self.manifest
            .artifacts
            .iter()
            .find(|a| a.name == name && a.variant == variant)
            .ok_or_else(|| {
                Error::new(
                    "PAYLOAD_MISSING",
                    "В комплекте отсутствует обязательный файл",
                )
                .detail(format!("{name} {variant:?}"))
            })
    }
    pub fn path(&self, a: &Artifact) -> Result<PathBuf> {
        if Path::new(&a.path)
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
        {
            return Err(Error::new("PAYLOAD_PATH", "Недопустимый путь в комплекте").detail(&a.path));
        }
        let path = self.root.join(&a.path).canonicalize()?;
        if !path.starts_with(&self.root) {
            return Err(Error::new(
                "PAYLOAD_PATH",
                "Файл выходит за пределы комплекта",
            ));
        }
        Ok(path)
    }
    pub fn file(&self, name: &str, variant: Option<Variant>) -> Result<PathBuf> {
        self.path(self.artifact(name, variant)?)
    }
    pub fn verify(&self) -> Result<()> {
        self.manifest.recipe.validate()?;
        if self.manifest.schema == 1
            && serde_json::to_vec(&self.manifest.recipe)?
                != serde_json::to_vec(&crate::recipe::Recipe::default())?
        {
            return Err(Error::new(
                "RECIPE_SIGNATURE",
                "Изменяемый манифест требует payload schema 2",
            ));
        }
        let recipe_sha = hex::encode(Sha256::digest(serde_json::to_vec(&serde_json::to_value(
            &self.manifest.recipe,
        )?)?));
        let mut seen = BTreeSet::new();
        for a in &self.manifest.artifacts {
            if !seen.insert((a.name.clone(), a.variant.map(|v| v.name()))) {
                return Err(Error::new(
                    "PAYLOAD_DUPLICATE",
                    "Дублирующийся файл в комплекте",
                ));
            }
            let path = self.path(a)?;
            if a.size == 0 || path.metadata()?.len() != a.size || sha256(&path)? != a.sha256 {
                return Err(Error::new(
                    "PAYLOAD_HASH",
                    "Файл установщика повреждён. Загрузите полный комплект заново.",
                )
                .detail(&a.path));
            }
        }
        for variant in [Variant::Full, Variant::Light] {
            for name in ["native.apk", "restore_mode.apk"] {
                let metadata =
                    apk_metadata(&self.file(name, Some(variant))?)?.ok_or_else(|| {
                        Error::new("APK_METADATA", "APK не содержит метаданные сборки")
                    })?;
                let signers = verified_signers(&self.file(name, Some(variant))?)?;
                if signers != verified_signers(&self.file(name, Some(Variant::Full))?)? {
                    return Err(Error::new(
                        "APK_SIGNERS",
                        "Full и Light подписаны разными ключами",
                    ));
                }
                if self.manifest.schema == 2
                    && metadata.recipe_sha256.as_deref() != Some(&recipe_sha)
                {
                    return Err(Error::new(
                        "RECIPE_SIGNATURE",
                        "Манифест действий не совпадает с подписанным APK",
                    )
                    .detail(name));
                }
                for file in self.manifest.recipe.runtime(variant) {
                    let source_variant = file.variant_artifact.then_some(variant);
                    if metadata.runtime_hashes.get(&file.artifact)
                        != Some(&self.artifact(&file.artifact, source_variant)?.sha256)
                    {
                        return Err(Error::new(
                            "APK_RUNTIME_HASH",
                            "Подписанные хеши компонентов не совпадают с комплектом",
                        )
                        .detail(&file.artifact));
                    }
                }
                let component = if name == "native.apk" {
                    NATIVE
                } else {
                    RESTORE
                };
                if metadata.schema != 1
                    || metadata.product != "VoyahTune"
                    || metadata.component != component
                    || metadata.variant != variant
                    || metadata.release_version != self.manifest.release_version
                    || metadata.build_revision != self.manifest.build_revision
                {
                    return Err(Error::new(
                        "APK_METADATA",
                        "Метаданные APK не соответствуют комплекту",
                    )
                    .detail(name));
                }
            }
        }
        for file in &self.manifest.recipe.files {
            for variant in &file.variants {
                self.artifact(&file.artifact, file.variant_artifact.then_some(*variant))?;
            }
        }
        for package in &self.manifest.recipe.packages {
            for variant in &package.variants {
                let apk = self.file(
                    &package.artifact,
                    package.variant_artifact.then_some(*variant),
                )?;
                verified_signers(&apk)?;
                let metadata = apk_metadata(&apk)?.ok_or_else(|| {
                    Error::new("APK_METADATA", "APK не содержит метаданные сборки")
                })?;
                if metadata.component != package.package
                    || metadata.variant != *variant
                    || metadata.release_version != self.manifest.release_version
                    || metadata.build_revision != self.manifest.build_revision
                {
                    return Err(Error::new(
                        "APK_METADATA",
                        "Метаданные приложения не совпадают с манифестом",
                    )
                    .detail(&package.package));
                }
            }
        }
        for name in ["dns-helper.sh", "dns.apk", "init.logcat.original.sh"] {
            self.artifact(name, None)?;
        }
        Ok(())
    }
}
pub fn destination(name: &str) -> Option<(String, u32)> {
    match name {
        "native.apk" => Some((NATIVE_PATH.into(), 0o644)),
        "whitelist.xml" => Some((WHITELIST.into(), 0o644)),
        "voyahtune.load.rc" => Some(("/system/etc/init/voyahtune.load.rc".into(), 0o644)),
        "voyahtune.load.sh" => Some(("/system/etc/init.voyahtune.load.sh".into(), 0o755)),
        name if FULL_NAMES.contains(&name) => Some((
            format!("/data/local/bin/{name}"),
            if ["load.bin", "frida-inject"].contains(&name) {
                0o755
            } else {
                0o644
            },
        )),
        _ => None,
    }
}
pub fn sha256(path: &Path) -> Result<String> {
    let mut file = File::open(path)?;
    let mut hash = Sha256::new();
    let mut b = [0u8; 65536];
    loop {
        let n = file.read(&mut b)?;
        if n == 0 {
            break;
        }
        hash.update(&b[..n]);
    }
    Ok(hex::encode(hash.finalize()))
}
pub fn apk_metadata(path: &Path) -> Result<Option<BuildMetadata>> {
    let mut zip = zip::ZipArchive::new(File::open(path)?)
        .map_err(|e| Error::new("APK_FORMAT", "Не удалось прочитать APK").detail(e))?;
    let mut f = match zip.by_name("assets/voyahtune-build.json") {
        Ok(f) => f,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(Error::new("APK_FORMAT", "Повреждён APK").detail(e)),
    };
    if f.size() > 65536 {
        return Err(Error::new(
            "APK_METADATA",
            "Слишком большой файл метаданных APK",
        ));
    }
    let mut bytes = Vec::new();
    f.by_ref().take(65537).read_to_end(&mut bytes)?;
    Ok(Some(serde_json::from_slice(&bytes)?))
}
/// Verify v2 signatures AND the APK content digest. apksig::Apk::verify alone only
/// verifies the signing block, so it must not be used as a content-integrity check.
/// The current release keys use RSA. Unsupported schemes fail closed.
pub fn verified_signers(path: &Path) -> Result<Vec<String>> {
    use apksig::{Apk, ValueSigningBlock};
    let fail = |e: String| {
        Error::new("APK_SIGNATURE", "Не удалось подтвердить подпись APK")
            .detail(format!("{}: {e}", path.display()))
    };
    let apk = Apk::new(path.to_path_buf())?;
    let block = apk.get_signing_block().map_err(|e| fail(e.to_string()))?;
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
                for sig in &signer.signatures.signatures_data {
                    let algo = &sig.signature_algorithm_id;
                    let digest = signer
                        .signed_data
                        .digests
                        .digests_data
                        .iter()
                        .find(|d| d.signature_algorithm_id == *algo)
                        .ok_or_else(|| fail("Missing content digest".into()))?;
                    algo.verify(&signer.pub_key.data, data, &sig.signature)
                        .map_err(&fail)?;
                    if apk.digest(algo).map_err(|e| fail(e.to_string()))? != digest.digest {
                        return Err(fail("APK content digest mismatch".into()));
                    }
                }
                // Bind both the verified key and signed certificates; PackageManager additionally
                // checks its Android-specific certificate/rotation policy at installation time.
                let certs = &signer.signed_data.certificates.certificates_data;
                if certs.is_empty() {
                    return Err(fail("Missing certificate".into()));
                }
                let mut hash = Sha256::new();
                hash.update(&signer.pub_key.data);
                for c in certs {
                    hash.update(&c.certificate);
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
        return Err(Error::new("HOST_TOOLS", "Неполный комплект инструментов"));
    }
    let mut has_adb = false;
    for file in host.files {
        if Path::new(&file.path)
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
            || !file.path.starts_with("adb/")
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
                "Комплектный ADB или его библиотека повреждены",
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
            "В комплекте нет ADB для этой платформы",
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
            .find(|p| p.extension().is_some_and(|e| e == "app"))
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
        let directory = if candidate.file_name().is_some_and(|f| f == "manifest.json") {
            candidate.parent().unwrap().to_path_buf()
        } else {
            candidate
        };
        if directory.join("manifest.json").is_file() {
            return Ok(directory);
        }
    }
    Err(Error::new("PAYLOAD_MISSING", "Не найден встроенный комплект релиза VoyahTune").retry("Повторно распакуйте или переустановите полный установщик. Для диагностики можно указать путь к manifest.json."))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_owned_destinations() {
        assert!(destination("/system/bin/sh").is_none());
        assert!(destination("../../bin/sh").is_none());
        assert_eq!(destination("load.bin").unwrap().1, 0o755);
    }
}
