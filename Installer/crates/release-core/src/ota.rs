//! Signed OTA metadata. The catalog is discovery; only a pinned key authorizes a release.
use crate::{
    catalog::Release,
    payload::{self, Payload},
    Error, Result,
};
use rsa::{pkcs8::DecodePublicKey, traits::PublicKeyParts, Pkcs1v15Sign, RsaPublicKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Envelope {
    pub body: String,
    pub signature: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Claims {
    pub schema: u32,
    pub version: String,
    pub sequence: u64,
    pub archive_sha256: String,
    pub archive_size: u64,
    pub manifest_sha256: String,
    pub min_updater_version: String,
    pub source_versions: String,
    pub firmware_policy: String,
    pub capabilities: Vec<String>,
    pub apk_signers: BTreeMap<String, Vec<String>>,
}
fn invalid(message: impl Into<String>) -> Error {
    Error::new("OTA_INVALID", message)
}
pub fn verify(release: &Release, public_key_der: &[u8]) -> Result<Claims> {
    if !release.ota {
        return Err(invalid("Релиз не разрешён для OTA"));
    }
    let envelope = release
        .ota_metadata
        .as_ref()
        .ok_or_else(|| invalid("Нет подписанных OTA metadata"))?;
    if envelope.body.len() > 64 * 1024 || envelope.signature.len() > 2048 {
        return Err(invalid("Metadata слишком велики"));
    }
    let key =
        RsaPublicKey::from_public_key_der(public_key_der).map_err(|e| invalid(e.to_string()))?;
    if key.n().bits() < 2048 {
        return Err(invalid("Недопустимый ключ OTA"));
    }
    let signature = hex::decode(&envelope.signature).map_err(|e| invalid(e.to_string()))?;
    key.verify(
        Pkcs1v15Sign::new::<Sha256>(),
        &Sha256::digest(envelope.body.as_bytes()),
        &signature,
    )
    .map_err(|_| invalid("Подпись OTA metadata не совпадает с доверенным ключом"))?;
    let c: Claims = serde_json::from_str(&envelope.body)?;
    if c.schema != 1
        || c.sequence == 0
        || c.version != release.version
        || c.archive_sha256 != release.payload.sha256
        || c.archive_size != release.payload.size
        || !digest(&c.manifest_sha256)
        || c.firmware_policy != "bootstrap-fingerprint"
        || c.apk_signers.len() != 2
    {
        return Err(invalid("Подписанные metadata не соответствуют релизу"));
    }
    for package in [payload::NATIVE, payload::RESTORE] {
        if !c
            .apk_signers
            .get(package)
            .is_some_and(|s| !s.is_empty() && s.iter().all(|s| digest(s)))
        {
            return Err(invalid("Нет доверенного signer APK"));
        }
    }
    semver::Version::parse(&c.min_updater_version).map_err(|e| invalid(e.to_string()))?;
    semver::VersionReq::parse(&c.source_versions).map_err(|e| invalid(e.to_string()))?;
    Ok(c)
}
fn digest(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub fn compatible(
    c: &Claims,
    installed: &str,
    updater: &str,
    sequence: u64,
    same_version: bool,
) -> Result<()> {
    let parse = |s: &str| semver::Version::parse(s).map_err(|e| invalid(e.to_string()));
    let target = parse(&c.version)?;
    let current = parse(installed)?;
    if parse(updater)? < parse(&c.min_updater_version)?
        || c.capabilities.iter().any(|c| c != "qinggan-ota-v1")
    {
        return Err(invalid("Требуется обновить службу через USB"));
    }
    if target < current
        || c.sequence < sequence
        || (target == current && !same_version)
        || (target > current && c.sequence <= sequence)
        || !semver::VersionReq::parse(&c.source_versions)
            .map_err(|e| invalid(e.to_string()))?
            .matches(&current)
    {
        return Err(invalid("OTA не поддерживает этот путь обновления"));
    }
    Ok(())
}
pub fn verify_payload(p: &Payload, c: &Claims) -> Result<()> {
    p.verify()?;
    if p.manifest.removal_only
        || p.manifest.release_version != c.version
        || payload::sha256(&p.root.join("manifest.json"))? != c.manifest_sha256
    {
        return Err(invalid("Manifest не соответствует подписанному релизу"));
    }
    for (file, package) in [
        ("native.apk", payload::NATIVE),
        ("restore_mode.apk", payload::RESTORE),
    ] {
        let (actual_id, actual_code, actual_version) = crate::apk_identity::read(&p.file(file)?)?;
        let version = semver::Version::parse(&c.version).map_err(|e| invalid(e.to_string()))?;
        if version.major > 999 || version.minor > 999 || version.patch > 999 {
            return Err(invalid("Версия вне диапазона versionCode"));
        }
        let expected_code = version
            .major
            .checked_mul(1_000_000)
            .and_then(|v| v.checked_add(version.minor * 1000))
            .and_then(|v| v.checked_add(version.patch))
            .ok_or_else(|| invalid("versionCode overflow"))?;
        if actual_id != package || actual_version != c.version || actual_code != expected_code {
            return Err(invalid(format!(
                "Package ID или версия APK {package} не совпадает с релизом"
            )));
        }
        if c.apk_signers.get(package) != Some(&payload::verified_signers(&p.file(file)?)?) {
            return Err(invalid(format!("Не совпадает подпись {package}")));
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_downgrade_and_requires_explicit_same_version() {
        let c = Claims {
            schema: 1,
            version: "3.14.0".into(),
            sequence: 2,
            archive_sha256: String::new(),
            archive_size: 1,
            manifest_sha256: String::new(),
            min_updater_version: "0.1.0".into(),
            source_versions: ">=3.14.0, <4.0.0".into(),
            firmware_policy: "bootstrap-fingerprint".into(),
            capabilities: vec!["qinggan-ota-v1".into()],
            apk_signers: BTreeMap::new(),
        };
        assert!(compatible(&c, "3.14.0", "0.1.0", 2, false).is_err());
        assert!(compatible(&c, "3.14.0", "0.1.0", 2, true).is_ok());
        assert!(compatible(&c, "3.15.0", "0.1.0", 2, true).is_err());
        assert!(compatible(&c, "3.14.0", "0.1.0", 3, true).is_err());
    }
}
