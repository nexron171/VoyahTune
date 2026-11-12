use crate::{
    payload::{self, NATIVE_PATH, RESTORE, WHITELIST},
    Error, Result,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

const RUNTIME_DIRECTORIES: &[&str] = &[
    "/data/local/bin/voyahtune-new",
    "/data/local/tmp/voyahtune-new",
    "/data/local/tmp/voyahtune-pi",
];

const RETIRED_RUNTIME_FILES: &str =
    include_str!("../../../../Packaging/new/config/retired-files.txt");

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Phase {
    Files,
    BootHooks,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CopyFile {
    pub artifact: String,
    pub destination: String,
    pub mode: u32,
    pub phase: Phase,
}
pub const OTA_STABLE_ARTIFACTS: &[&str] = &[
    "voyahtune-updater",
    "voyahtune-updater.apk",
    "voyahtune.updater.rc",
    "voyahtune-ota-bootstrap.json",
];

impl CopyFile {
    pub fn delivered_by_ota(&self) -> bool {
        !OTA_STABLE_ARTIFACTS.contains(&self.artifact.as_str())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InstallPackage {
    pub artifact: String,
    pub package: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub condition: Option<PackageCondition>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum PackageCondition {
    ExceptH97xy,
}

impl InstallPackage {
    pub fn applies_to(&self, model: crate::vehicle_model::VehicleModel) -> bool {
        self.condition.is_none() || model.needs_runyn()
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Attributes {
    pub path: String,
    pub mode: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Recipe {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub directories: Vec<Attributes>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attributes: Vec<Attributes>,
    pub schema: u32,
    pub engine: String,
    pub files: Vec<CopyFile>,
    pub packages: Vec<InstallPackage>,
    pub remove_files: Vec<String>,
    pub remove_directories: Vec<String>,
    pub remove_prefixes: Vec<String>,
    pub remove_packages: Vec<String>,
}
impl Default for Recipe {
    fn default() -> Self {
        let names = [
            "native.apk",
            "whitelist.xml",
            "voyahtune-updater",
            "voyahtune-updater.apk",
            "voyahtune.updater.rc",
            "voyahtune-ota-bootstrap.json",
            "voyahtune.load.sh",
            "voyahtune.load.rc",
        ];
        let files = names
            .into_iter()
            .chain(
                payload::NATIVE_RUNTIME_ROLES
                    .iter()
                    .map(|(name, _, _)| *name),
            )
            .map(|name| {
                let (destination, mode) =
                    payload::destination(name).expect("compiled installation destination");
                let phase = if ["voyahtune.load.sh", "voyahtune.load.rc"].contains(&name) {
                    Phase::BootHooks
                } else {
                    Phase::Files
                };
                CopyFile {
                    artifact: name.into(),
                    destination,
                    mode,
                    phase,
                }
            })
            .collect();
        Self {
            schema: 3,
            engine: "qinggan-v3".into(),
            files,
            packages: vec![
                InstallPackage {
                    artifact: "restore_mode.apk".into(),
                    package: RESTORE.into(),
                    condition: None,
                },
                InstallPackage {
                    artifact: "runyn.apk".into(),
                    package: payload::RUNYN.into(),
                    condition: Some(PackageCondition::ExceptH97xy),
                },
            ],
            directories: vec![Attributes {
                path: "/data/local/bin/voyahtune-new".into(),
                mode: 0o755,
            }],
            attributes: vec![],
            remove_files: RETIRED_RUNTIME_FILES.lines().map(str::to_owned).collect(),
            remove_directories: vec![
                "/data/local/tmp/voyah_load.lock".into(),
                "/data/local/tmp/voyahtune-pi".into(),
            ],
            remove_prefixes: vec![],
            remove_packages: vec![],
        }
    }
}
fn clean(path: &str) -> bool {
    path.starts_with('/')
        && path.split('/').skip(1).all(|p| {
            !p.is_empty()
                && p != "."
                && p != ".."
                && p.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        })
}
// Fixed ownership boundary, not an execution list. New project files live under
// its own namespace; shared/legacy names require the existing guarded handlers.
fn owned(path: &str) -> bool {
    clean(path)
        && ([
            "voyahtune-updater",
            "voyahtune-updater.apk",
            "voyahtune.updater.rc",
            "voyahtune-ota-bootstrap.json",
            "voyahtune.load.rc",
            "voyahtune.load.sh",
        ]
        .iter()
        .any(|name| payload::destination(name).is_some_and(|(p, _)| p == path))
            || RUNTIME_DIRECTORIES.contains(&path)
            || path == NATIVE_PATH
            || path == WHITELIST
            || RETIRED_RUNTIME_FILES.lines().any(|retired| retired == path)
            || include_str!("cleanup_paths.txt").lines().any(|p| p == path)
            || include_str!("pi-cleanup-paths.txt")
                .lines()
                .any(|p| p == path)
            || [
                "/system/etc/init/voyahtune.setenforce.rc",
                "/system/etc/init/voyahtune.load.sh",
            ]
            .contains(&path)
            || [
                "/data/local/bin/voyahtune-",
                "/data/local/bin/voyahtune_",
                "/data/local/tmp/voyahtune_",
                "/sdcard/tmp/voyahtune_",
            ]
            .iter()
            .any(|p| path.starts_with(p) && !path[p.len()..].is_empty()))
}
impl Recipe {
    pub fn runyn_service(&self, model: crate::vehicle_model::VehicleModel) -> Option<CopyFile> {
        self.packages
            .iter()
            .any(|package| package.package == payload::RUNYN && package.applies_to(model))
            .then(|| CopyFile {
                artifact: crate::runyn::TELNET_ARTIFACT.into(),
                destination: crate::runyn::TELNET_DESTINATION.into(),
                mode: 0o644,
                phase: Phase::Files,
            })
    }

    pub fn validate(&self) -> Result<()> {
        let fail = |detail: String| {
            Error::new(
                "RECIPE_INVALID",
                "План установки содержит неподдерживаемое действие или путь",
            )
            .detail(detail)
        };
        if self.schema != 3 || self.engine != "qinggan-v3" {
            return Err(Error::new(
                "ENGINE_REQUIRED",
                "Этот релиз требует другую версию установщика",
            )
            .detail(&self.engine));
        }
        if self.files.len() > 512 || self.packages.len() > 64 || self.remove_files.len() > 2048 {
            return Err(fail("Слишком большой план установки".into()));
        }
        if !(1..=2).contains(&self.packages.len())
            || !self.remove_packages.is_empty()
            || !self.remove_prefixes.is_empty()
        {
            return Err(fail(
                "Неподдерживаемые пакетные операции или prefix cleanup".into(),
            ));
        }
        if !self.packages.iter().any(|package| {
            package.package == payload::RUNYN
                && package.condition == Some(PackageCondition::ExceptH97xy)
        }) {
            return Err(fail(
                "Общий payload требует условную установку RunYN".into(),
            ));
        }
        for attr in self.directories.iter().chain(&self.attributes) {
            if !owned(&attr.path)
                || !attr.path.starts_with("/data/local/")
                || !attr
                    .path
                    .split('/')
                    .any(|p| p.starts_with("voyahtune_") || p.starts_with("voyahtune-"))
                || ![0o644, 0o755].contains(&attr.mode)
            {
                return Err(fail(attr.path.clone()));
            }
        }
        let mut destinations = BTreeSet::new();
        for file in &self.files {
            if !owned(&file.destination)
                || !destinations.insert(&file.destination)
                || ![0o644, 0o755].contains(&file.mode)
            {
                return Err(fail(file.destination.clone()));
            }
            if RETIRED_RUNTIME_FILES
                .lines()
                .any(|retired| retired == file.destination)
                || (file
                    .destination
                    .starts_with("/data/local/bin/voyahtune-new/")
                    && file.destination.ends_with(".js"))
            {
                return Err(fail(format!(
                    "Retired runtime target: {}",
                    file.destination
                )));
            }
            let boot = ["voyahtune.load.rc", "voyahtune.load.sh"].contains(&file.artifact.as_str());
            if (file.phase == Phase::BootHooks) != boot {
                return Err(fail(file.artifact.clone()));
            }
            // Special engine roles have fixed targets and restoration semantics.
            if ([
                "voyahtune-updater",
                "voyahtune-updater.apk",
                "voyahtune.updater.rc",
                "voyahtune-ota-bootstrap.json",
                "native.apk",
                "whitelist.xml",
                "voyahtune.load.rc",
                "voyahtune.load.sh",
            ]
            .contains(&file.artifact.as_str())
                || payload::NATIVE_RUNTIME_ROLES
                    .iter()
                    .any(|(role, _, _)| *role == file.artifact))
                && payload::destination(&file.artifact)
                    .is_none_or(|(p, m)| p != file.destination || m != file.mode)
            {
                return Err(fail(file.artifact.clone()));
            }
        }
        for name in [
            "native.apk",
            "whitelist.xml",
            "voyahtune.load.rc",
            "voyahtune.load.sh",
        ] {
            if !self.files.iter().any(|f| f.artifact == name) {
                return Err(fail(format!("Обязательная роль: {name}")));
            }
        }
        for (role, _, _) in payload::NATIVE_RUNTIME_ROLES {
            if !self.files.iter().any(|file| file.artifact == *role) {
                return Err(fail(format!("Обязательная роль: {role}")));
            }
        }
        let mut ids = BTreeSet::new();
        for package in &self.packages {
            if !ids.insert(&package.package)
                || !((package.package == RESTORE && package.artifact == "restore_mode.apk")
                    || (package.package == payload::RUNYN && package.artifact == "runyn.apk"))
                || (package.condition.is_some() && package.package != payload::RUNYN)
                || !package.package.split('.').all(|s| {
                    !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                })
            {
                return Err(fail(package.package.clone()));
            }
        }
        if !self
            .packages
            .iter()
            .any(|p| p.package == RESTORE && p.artifact == "restore_mode.apk")
        {
            return Err(fail("Обязательная роль: RestoreMode".into()));
        }
        for id in &self.remove_packages {
            if !id.starts_with("ru.voyahtune.")
                || !id.split('.').all(|s| {
                    !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                })
            {
                return Err(fail(id.clone()));
            }
        }
        let mut names = BTreeSet::new();
        for file in &self.files {
            if !names.insert(&file.artifact) {
                return Err(fail(format!("Повторная цель артефакта: {}", file.artifact)));
            }
        }
        for path in &self.remove_files {
            if !owned(path) || [NATIVE_PATH, WHITELIST].contains(&path.as_str()) {
                return Err(fail(path.clone()));
            }
        }
        for path in &self.remove_directories {
            if path != "/data/local/tmp/voyah_load.lock"
                && !(owned(path)
                    && path.starts_with("/data/local/")
                    && path
                        .split('/')
                        .any(|p| p.starts_with("voyahtune_") || p.starts_with("voyahtune-")))
            {
                return Err(fail(path.clone()));
            }
        }
        for path in &self.remove_prefixes {
            if ["/data/local/bin/voyahtune-", "/data/local/bin/voyahtune_"].contains(&path.as_str())
            {
                continue;
            }
            if !clean(path)
                || !path.starts_with("/data/local/tmp/voyahtune_")
                || !path.ends_with('.')
            {
                return Err(fail(path.clone()));
            }
        }
        Ok(())
    }
    pub fn runtime(&self) -> impl Iterator<Item = &CopyFile> {
        self.files
            .iter()
            .filter(move |f| f.artifact != "native.apk")
    }
    pub fn cleanup_files(&self) -> Vec<String> {
        self.remove_files
            .iter()
            .cloned()
            .chain(RETIRED_RUNTIME_FILES.lines().map(String::from))
            .chain(std::iter::once(
                crate::runyn::TELNET_DESTINATION.to_string(),
            ))
            .chain(std::iter::once(
                "/data/local/bin/voyahtune-native-migration.boot".to_string(),
            ))
            .chain(
                self.files
                    .iter()
                    .filter(|file| {
                        !["native.apk", "whitelist.xml"].contains(&file.artifact.as_str())
                    })
                    .map(|file| file.destination.clone()),
            )
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }

    pub fn cleanup_directories(&self) -> Vec<String> {
        // Removal must cover every runtime even when using an older saved recipe.
        self.remove_directories
            .iter()
            .map(String::as_str)
            .chain(
                self.directories
                    .iter()
                    .map(|directory| directory.path.as_str()),
            )
            .chain(RUNTIME_DIRECTORIES.iter().copied())
            .map(String::from)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removal_covers_owned_runtime_without_removing_active_installation_targets() {
        let mut recipe = Recipe::default();
        recipe.directories.push(Attributes {
            path: "/data/local/bin/voyahtune-extra".into(),
            mode: 0o755,
        });
        for directory in RUNTIME_DIRECTORIES {
            assert!(recipe
                .cleanup_directories()
                .contains(&directory.to_string()));
        }
        assert!(recipe
            .cleanup_directories()
            .contains(&"/data/local/bin/voyahtune-extra".into()));
        assert!(!recipe
            .remove_directories
            .contains(&"/data/local/bin/voyahtune-new".into()));
        assert!(!recipe
            .remove_directories
            .contains(&"/data/local/tmp/voyahtune-new".into()));
        for file in [
            "/data/local/tmp/voyahtune_acc_restore.js.install",
            "/data/local/tmp/voyahtune_drive_reset.js.install",
            "/data/local/tmp/voyahtune_load.bin.install",
            "/data/local/tmp/voyahtune_phone_numbers.pid",
            "/data/local/tmp/voyahtune_vehicle_air.pid",
        ] {
            assert!(recipe.cleanup_files().contains(&file.to_string()));
        }
        for file in ["load.bin", "loaderFrida", "frida-inject"] {
            assert!(recipe
                .cleanup_files()
                .contains(&format!("/data/local/bin/{file}")));
        }
        for file in &recipe.files {
            assert!(!recipe.remove_files.contains(&file.destination));
        }
        recipe.validate().unwrap();
    }

    #[test]
    fn telnet_service_follows_runyn_package_selection() {
        use crate::vehicle_model::VehicleModel;
        let mut recipe = Recipe::default();
        assert!(recipe.runyn_service(VehicleModel::H97X).is_none());
        assert!(recipe.runyn_service(VehicleModel::H97Y).is_none());
        let service = recipe.runyn_service(VehicleModel::Other).unwrap();
        assert_eq!(
            service.destination,
            "/system/etc/init/voyahtune.runyn.telnet.rc"
        );
        assert_eq!(service.mode, 0o644);
        assert!(recipe.cleanup_files().contains(&service.destination));
        recipe
            .packages
            .retain(|package| package.package != payload::RUNYN);
        assert!(recipe.runyn_service(VehicleModel::Other).is_none());
    }

    #[test]
    fn conditional_runyn_is_skipped_only_on_h97xy() {
        let package: InstallPackage = serde_json::from_value(serde_json::json!({
            "artifact": "runyn.apk", "package": payload::RUNYN, "condition": "except-h97xy"
        }))
        .unwrap();
        use crate::vehicle_model::VehicleModel;
        assert!(!package.applies_to(VehicleModel::H97X));
        assert!(!package.applies_to(VehicleModel::H97Y));
        assert!(package.applies_to(VehicleModel::Other));
        let restore = Recipe::default().packages.remove(0);
        assert!(restore.applies_to(VehicleModel::H97X));
    }
    #[test]
    fn accepts_new_owned_file_without_an_engine_change() {
        let mut recipe = Recipe::default();
        recipe.files.push(CopyFile {
            artifact: "voyahtune-new.json".into(),

            destination: "/data/local/bin/voyahtune-new.json".into(),
            mode: 0o644,
            phase: Phase::Files,
        });
        recipe.validate().unwrap();
        assert!(recipe
            .cleanup_files()
            .contains(&"/data/local/bin/voyahtune-new.json".into()));
    }
    #[test]
    fn rejects_unsafe_deletion_and_unknown_engine_before_adb() {
        for path in [
            "/system/bin/sh",
            "/data/local/bin/../other",
            "/data",
            "/data/local/bin/foreign-injector",
        ] {
            let mut recipe = Recipe::default();
            recipe.remove_files.push(path.into());
            assert!(recipe.validate().is_err(), "{path}");
        }
        let recipe = Recipe {
            engine: "future-engine".into(),
            ..Recipe::default()
        };
        assert_eq!(recipe.validate().unwrap_err().code, "ENGINE_REQUIRED");
    }
    #[test]
    fn native_runtime_requires_every_compiled_component() {
        for role in [
            "voyahtune-new-voyahtune-loader",
            "voyahtune-new-voyahtune-inject",
            "voyahtune-new-voyahtune-inject-arm",
            "voyahtune-new-libvoyahtune-agent-arm.so",
            "voyahtune-new-agents.dex",
            "voyahtune-new-libvoyahtune-agent.so",
        ] {
            let mut recipe = Recipe::default();
            recipe.files.retain(|file| file.artifact != role);
            assert!(recipe.validate().is_err(), "missing {role} accepted");
        }
    }

    #[test]
    fn retired_runtime_cannot_be_reintroduced_as_an_install_target() {
        let mut recipe = Recipe::default();
        recipe.files.push(CopyFile {
            artifact: "old-agent".into(),
            destination: "/data/local/bin/phone-num.js".into(),
            mode: 0o644,
            phase: Phase::Files,
        });
        assert!(recipe.validate().is_err());
    }

    #[test]
    fn rejects_shell_and_duplicate_targets() {
        let mut value = serde_json::to_value(Recipe::default()).unwrap();
        value["shell"] = serde_json::json!("rm -rf /");
        assert!(serde_json::from_value::<Recipe>(value).is_err());
        let mut recipe = Recipe::default();
        recipe.files.push(recipe.files[0].clone());
        assert!(recipe.validate().is_err());
    }
}

#[cfg(test)]
mod ota_delivery_tests {
    use super::*;

    #[test]
    fn ota_preserves_its_running_service_but_delivers_the_new_injection_runtime() {
        for (name, deliver) in [
            ("voyahtune-updater", false),
            ("voyahtune-updater.apk", false),
            ("voyahtune.updater.rc", false),
            ("voyahtune-ota-bootstrap.json", false),
            ("agents.dex", true),
            ("libvoyahtune-agent.so", true),
            ("libvoyahtune-agent-arm.so", true),
            ("voyahtune-inject-arm", true),
            ("voyahtune-loader", true),
        ] {
            let file = CopyFile {
                artifact: name.into(),
                destination: "/data/local/bin/test".into(),
                mode: 0o644,
                phase: Phase::Files,
            };
            assert_eq!(file.delivered_by_ota(), deliver, "{name}");
        }
    }
}
