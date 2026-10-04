use crate::{
    adb::{quote, Adb},
    payload::{self, BuildMetadata, Payload, NATIVE, NATIVE_PATH, RESTORE},
    Error, Result,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Package {
    pub path: String,
    pub sha256: String,
    pub build: Option<BuildMetadata>,
    pub signers: Vec<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Inventory {
    pub serial: String,
    pub fingerprint: String,
    pub model: String,
    pub sdk: u32,
    pub abi: String,
    pub problems: Vec<String>,
    pub packages: BTreeMap<String, Package>,
    pub base_native: Option<Package>,
    pub files: BTreeMap<String, String>,
    pub foreign_files: BTreeMap<String, String>,
    pub remnants: Vec<String>,
    pub state: String,
    pub version: Option<String>,
    pub token: String,
}
pub fn file_hash(adb: &Adb, path: &str) -> Result<Option<String>> {
    let q = quote(path);
    // Same file test as classic backup_pull: follow a link to a regular file,
    // skip an absent/dangling entry, and report an unreadable/non-file source.
    let script = format!("set -e\nif [ -f {q} ]; then sha256sum {q}; elif [ -e {q} ]; then echo 'NOT_FILE'; else echo 'ABSENT'; fi\n");
    let result = adb.read(&script)?;
    if result == "ABSENT" {
        return Ok(None);
    }
    let h = result.split_whitespace().next().unwrap_or("");
    if h.len() != 64 || !h.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(
            Error::new("FILE_INSPECTION", "Не удалось проверить файл автомобиля")
                .detail(format!("{path}: {result}")),
        );
    }
    Ok(Some(h.into()))
}
fn package(adb: &Adb, path: &str, verify_signature: bool) -> Result<Package> {
    let hash = file_hash(adb, path)?.ok_or_else(|| {
        Error::new("PACKAGE_DISAPPEARED", "APK исчез во время проверки").detail(path)
    })?;
    if !verify_signature {
        // Removal must also work for APKs with old, unsupported or damaged signatures.
        // Do not use unverified build metadata as proof of file ownership.
        return Ok(Package {
            path: path.into(),
            sha256: hash,
            build: None,
            signers: Vec::new(),
        });
    }
    let tmp = tempfile::tempdir()?;
    let local = tmp.path().join("package.apk");
    adb.pull(path, &local)?;
    if payload::sha256(&local)? != hash {
        return Err(Error::new(
            "PACKAGE_CHANGED",
            "APK изменился во время проверки. Нажмите «Обновить».",
        ));
    }
    let (build, signers) = match payload::verified_signers(&local) {
        Ok(signers) => (payload::apk_metadata(&local).unwrap_or(None), signers),
        Err(error) => {
            adb.events.emit(
                "diagnostic-warning",
                adb.step.as_deref(),
                "Подпись старого APK не распознана. Совместимость обновления проверит Android.",
                serde_json::json!({"path":path,"detail":error.detail}),
            )?;
            (None, Vec::new())
        }
    };
    Ok(Package {
        path: path.into(),
        sha256: hash,
        build,
        signers,
    })
}
/// Optional ownership evidence for backup/restore. Missing or broken signatures
/// must never block removal, and APKs are read only into temporary verification files.
pub fn signed_runtime_hashes(adb: &Adb) -> BTreeSet<(String, String)> {
    let mut paths = BTreeSet::from([NATIVE_PATH.to_owned()]);
    for id in [NATIVE, RESTORE] {
        if let Ok(active) = adb.package_paths(id) {
            paths.extend(active);
        }
    }
    paths
        .into_iter()
        .filter_map(|path| package(adb, &path, true).ok()?.build)
        .filter(|b| {
            b.schema == 3
                && b.product == "VoyahTune"
                && [NATIVE, RESTORE].contains(&b.component.as_str())
        })
        .flat_map(|b| b.runtime_hashes)
        .collect()
}
pub fn inspect(adb: &Adb, payload: &Payload) -> Result<Inventory> {
    inspect_for_action(adb, payload, crate::plans::Action::Install)
}
pub fn inspect_for_action(
    adb: &Adb,
    payload: &Payload,
    action: crate::plans::Action,
) -> Result<Inventory> {
    let verify_signature = action != crate::plans::Action::Remove;
    let device = adb.require_single()?;
    let props=adb.read("set -e\ngetprop ro.build.fingerprint\ngetprop ro.product.model\ngetprop ro.build.version.sdk\ngetprop ro.product.cpu.abilist\n")?;
    let p: Vec<_> = props.lines().collect();
    if p.len() != 4 {
        return Err(
            Error::new("DEVICE_PROPERTIES", "Не удалось прочитать свойства Android").detail(props),
        );
    }
    let sdk = p[2]
        .parse()
        .map_err(|_| Error::new("DEVICE_PROPERTIES", "Неизвестная версия Android"))?;
    let packages_text = adb.read("pm list packages --user 0\n")?;
    if !packages_text.lines().any(|l| l == "package:android") {
        return Err(
            Error::new("PACKAGE_MANAGER", "PackageManager не вернул список пакетов")
                .detail(packages_text),
        );
    }
    let mut problems = Vec::new();
    if sdk != 30 {
        problems.push(format!(
            "Профиль рассчитан на Android 11 (API 30); обнаружен API {sdk}."
        ));
    }
    if !p[3].split(',').any(|s| s == "arm64-v8a") {
        problems.push("Не найдена архитектура arm64-v8a.".into());
    }
    for component in [
        "com.qinggan.canbus.service",
        "com.qinggan.keymanager.service",
    ] {
        if !packages_text
            .lines()
            .any(|l| l == format!("package:{component}"))
        {
            problems.push(format!("Не найден обязательный компонент {component}."));
        }
    }
    let mut packages = BTreeMap::new();
    for id in std::iter::once(NATIVE)
        .chain(
            payload
                .manifest
                .recipe
                .packages
                .iter()
                .map(|p| p.package.as_str()),
        )
        .chain(
            payload
                .manifest
                .recipe
                .remove_packages
                .iter()
                .map(String::as_str),
        )
    {
        if packages_text.lines().any(|l| l == format!("package:{id}")) {
            let paths = adb.package_paths(id)?;
            if paths.len() != 1 {
                return Err(Error::new(
                    "APK_PATH",
                    "Не удалось однозначно определить активный APK",
                )
                .detail(id));
            }
            packages.insert(id.into(), package(adb, &paths[0], verify_signature)?);
        }
    }
    let base_native = if file_hash(adb, NATIVE_PATH)?.is_some() {
        Some(package(adb, NATIVE_PATH, verify_signature)?)
    } else {
        None
    };
    let mut files = BTreeMap::new();
    for file in payload
        .manifest
        .recipe
        .files
        .iter()
        .filter(|f| f.artifact != "native.apk")
    {
        if let Some(hash) = file_hash(adb, &file.destination)? {
            files.insert(file.destination.clone(), hash);
        }
    }
    let mut foreign_files = BTreeMap::new();
    for name in ["load.bin", "loaderFrida", "frida-inject"] {
        let path = payload::destination(name).unwrap().0;
        if let Some(hash) = files.get(&path).cloned() {
            let marker = if name == "load.bin" {
                adb.read("head -n 24 /data/local/bin/load.bin\n")?
                    .contains("Frida-оркестратор Open Voyah")
            } else {
                false
            };
            let signed_owned = packages.values().chain(base_native.iter()).any(|p| {
                p.build.as_ref().is_some_and(|b| {
                    b.product == "VoyahTune" && b.runtime_hashes.get(name) == Some(&hash)
                })
            });
            if payload.artifact(name).is_ok_and(|a| hash != a.sha256) && !marker && !signed_owned {
                files.remove(&path);
                foreign_files.insert(path, hash);
            }
        }
    }
    let mut script = String::from("set -e\n");
    for path in payload
        .manifest
        .recipe
        .cleanup_files()
        .iter()
        .chain(payload.manifest.recipe.remove_directories.iter())
    {
        script.push_str(&format!(
            "if [ -e {} ] || [ -L {} ]; then printf '%s\\n' {}; fi\n",
            quote(path),
            quote(path),
            quote(path)
        ));
    }
    let remnants = adb
        .read(&script)?
        .lines()
        .map(str::to_owned)
        .collect::<Vec<_>>();
    let (mut state, version) = classify(&packages, &base_native, &files, payload);
    if state == "absent" && !remnants.is_empty() {
        state = "remnants".into();
    }
    let mut i = Inventory {
        serial: device.serial,
        fingerprint: p[0].into(),
        model: p[1].into(),
        sdk,
        abi: p[3].into(),
        problems,
        packages,
        base_native,
        files,
        foreign_files,
        remnants,
        state,
        version,
        token: String::new(),
    };
    // Running hooks create/remove PID files and rotate logs. Those diagnostics
    // must not invalidate a plan; APKs, executable files and identity still do.
    let mut stable = i.clone();
    stable
        .remnants
        .retain(|p| !p.starts_with("/data/local/tmp/") && !p.starts_with("/sdcard/"));
    if stable.state == "remnants" && stable.remnants.is_empty() && stable.files.is_empty() {
        stable.state = "absent".into();
    }
    i.token = hex::encode(Sha256::digest(serde_json::to_vec(&(
        stable,
        &payload.manifest,
    ))?));
    Ok(i)
}
fn classify(
    packages: &BTreeMap<String, Package>,
    base: &Option<Package>,
    files: &BTreeMap<String, String>,
    payload: &Payload,
) -> (String, Option<String>) {
    if packages.is_empty() && base.is_none() {
        return (
            if files.is_empty() {
                "absent"
            } else {
                "remnants"
            }
            .into(),
            None,
        );
    }
    let builds: Vec<_> = packages
        .iter()
        .filter(|(id, _)| id.as_str() == NATIVE || id.as_str() == RESTORE)
        .map(|(_, p)| p)
        .chain(base.iter())
        .filter_map(|p| p.build.as_ref())
        .collect();
    if !packages.contains_key(NATIVE) || !packages.contains_key(RESTORE) || base.is_none() {
        return ("partial".into(), None);
    }
    if builds.len() != 3
        || builds
            .iter()
            .any(|b| b.schema != 3 || b.product != "VoyahTune")
    {
        return ("unknown".into(), None);
    }
    let b = builds[0];
    if builds.iter().any(|x| {
        x.release_version != b.release_version
            || x.build_revision != b.build_revision
            || x.infrastructure != b.infrastructure
    }) {
        return ("mixed".into(), None);
    }
    let mut state = "complete";
    // Classify an older release using its signed hashes. An unknown retired
    // target is reported as partial, never guessed from the current release.
    for (name, hash) in &b.runtime_hashes {
        if let Some(package) = payload
            .manifest
            .recipe
            .packages
            .iter()
            .find(|p| &p.artifact == name)
        {
            if packages
                .get(&package.package)
                .is_none_or(|p| &p.sha256 != hash)
            {
                state = "partial";
            }
            continue;
        }
        let Some(file) = payload
            .manifest
            .recipe
            .files
            .iter()
            .find(|f| &f.artifact == name)
        else {
            state = "partial";
            continue;
        };
        if files.get(&file.destination) != Some(hash) {
            state = "partial";
        }
    }
    if b.runtime_hashes.is_empty() {
        state = "unknown";
    }
    for package in &payload.manifest.recipe.packages {
        if !packages.contains_key(&package.package) {
            state = "partial";
        }
    }
    // A receipt or metadata alone is not proof that installed bytes match this release.
    if b.release_version == payload.manifest.release_version
        && b.build_revision == payload.manifest.build_revision
    {
        for (id, name) in [(NATIVE, "native.apk"), (RESTORE, "restore_mode.apk")] {
            if packages[id].sha256 != payload.artifact(name).unwrap().sha256 {
                state = "partial";
            }
        }
        if base.as_ref().unwrap().sha256 != payload.artifact("native.apk").unwrap().sha256 {
            state = "mixed";
        }
        if files.get(payload::WHITELIST) != Some(&payload.artifact("whitelist.xml").unwrap().sha256)
        {
            state = "partial";
        }
        for file in payload.manifest.recipe.runtime() {
            if files.get(&file.destination)
                != Some(&payload.artifact(&file.artifact).unwrap().sha256)
            {
                state = "partial";
            }
        }
        for package in payload.manifest.recipe.packages.iter() {
            if packages.get(&package.package).map(|p| &p.sha256)
                != Some(&payload.artifact(&package.artifact).unwrap().sha256)
            {
                state = "partial";
            }
        }
    }
    (state.into(), Some(b.release_version.clone()))
}

/// UI information only: unavailable metadata must not become an installation gate.
pub fn diagnose(adb: &Adb, payload: &Payload, action: crate::plans::Action) -> Inventory {
    match inspect_for_action(adb, payload, action) {
        Ok(i) => i,
        Err(e) => {
            let _ = adb.events.emit(
                "diagnostic-warning",
                adb.step.as_deref(),
                &e.message,
                serde_json::json!({"detail":e.detail}),
            );
            Inventory {
                serial: adb.serial.clone().unwrap_or_default(),
                fingerprint: String::new(),
                model: "Автомобиль".into(),
                sdk: 0,
                abi: String::new(),
                problems: vec![format!(
                    "Состояние определено не полностью: {}. Это не блокирует операцию.",
                    e.message
                )],
                packages: Default::default(),
                base_native: None,
                files: Default::default(),
                foreign_files: Default::default(),
                remnants: vec![],
                state: "unknown".into(),
                version: None,
                token: String::new(),
            }
        }
    }
}
