use crate::{
    payload::{self, Variant, NATIVE_PATH, RESTORE, WHITELIST},
    Error, Result,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

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
    pub variant_artifact: bool,
    pub variants: Vec<Variant>,
    pub destination: String,
    pub mode: u32,
    pub phase: Phase,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InstallPackage {
    pub artifact: String,
    pub package: String,
    pub variant_artifact: bool,
    pub variants: Vec<Variant>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Recipe {
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
        serde_json::from_str(include_str!("legacy-recipe.json")).expect("frozen legacy recipe")
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
        && (path == NATIVE_PATH
            || path == WHITELIST
            || payload::FULL_NAMES
                .iter()
                .any(|n| payload::destination(n).is_some_and(|d| d.0 == path))
            || include_str!("cleanup_paths.txt").lines().any(|p| p == path)
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
            .any(|p| path.starts_with(p) && !path[p.len()..].contains('/')))
}
impl Recipe {
    pub fn validate(&self) -> Result<()> {
        let fail = |detail: String| {
            Error::new(
                "RECIPE_INVALID",
                "Манифест содержит неподдерживаемое действие или путь",
            )
            .detail(detail)
        };
        if self.schema != 1 || self.engine != "qinggan-v1" {
            return Err(Error::new(
                "ENGINE_REQUIRED",
                "Этот комплект требует другую версию установщика",
            )
            .detail(&self.engine));
        }
        if self.files.len() > 512 || self.packages.len() > 64 || self.remove_files.len() > 2048 {
            return Err(fail("Слишком большой манифест".into()));
        }
        let mut destinations = BTreeSet::new();
        for file in &self.files {
            if !owned(&file.destination)
                || !destinations.insert(&file.destination)
                || ![0o644, 0o755].contains(&file.mode)
                || file.variants.is_empty()
            {
                return Err(fail(file.destination.clone()));
            }
            let boot = ["voyahtune.load.rc", "voyahtune.load.sh"].contains(&file.artifact.as_str());
            if (file.phase == Phase::BootHooks) != boot {
                return Err(fail(file.artifact.clone()));
            }
            // Special engine roles have fixed targets and restoration semantics.
            if [
                "native.apk",
                "whitelist.xml",
                "load.bin",
                "frida-inject",
                "voyahtune.load.rc",
                "voyahtune.load.sh",
            ]
            .contains(&file.artifact.as_str())
                && payload::destination(&file.artifact)
                    .is_none_or(|(p, m)| p != file.destination || m != file.mode)
            {
                return Err(fail(file.artifact.clone()));
            }
        }
        for (name, variants) in [
            ("native.apk", vec![Variant::Full, Variant::Light]),
            ("whitelist.xml", vec![Variant::Full, Variant::Light]),
            ("load.bin", vec![Variant::Full]),
            ("frida-inject", vec![Variant::Full]),
            ("voyahtune.load.rc", vec![Variant::Full]),
            ("voyahtune.load.sh", vec![Variant::Full]),
        ] {
            if !self.files.iter().any(|f| {
                f.artifact == name
                    && f.variants == variants
                    && f.variant_artifact == (name == "native.apk")
            }) {
                return Err(fail(format!("Обязательная роль: {name}")));
            }
        }
        let mut ids = BTreeSet::new();
        for package in &self.packages {
            if !ids.insert(&package.package)
                || package.variants.is_empty()
                || !(package.package == RESTORE || package.package.starts_with("ru.voyahtune."))
                || !package.package.split('.').all(|s| {
                    !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                })
            {
                return Err(fail(package.package.clone()));
            }
        }
        if !self.packages.iter().any(|p| {
            p.package == RESTORE
                && p.artifact == "restore_mode.apk"
                && p.variant_artifact
                && p.variants == [Variant::Full, Variant::Light]
        }) {
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
            if !owned(path)
                || [
                    NATIVE_PATH,
                    WHITELIST,
                    "/data/local/bin/load.bin",
                    "/data/local/bin/frida-inject",
                ]
                .contains(&path.as_str())
            {
                return Err(fail(path.clone()));
            }
        }
        for path in &self.remove_directories {
            if path != "/data/local/tmp/voyah_load.lock" {
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
    pub fn runtime(&self, variant: Variant) -> impl Iterator<Item = &CopyFile> {
        self.files
            .iter()
            .filter(move |f| f.artifact != "native.apk" && f.variants.contains(&variant))
    }
    pub fn cleanup_files(&self) -> Vec<String> {
        self.remove_files
            .iter()
            .cloned()
            .chain(
                self.files
                    .iter()
                    .filter(|f| {
                        !["native.apk", "whitelist.xml", "load.bin", "frida-inject"]
                            .contains(&f.artifact.as_str())
                    })
                    .map(|f| f.destination.clone()),
            )
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_new_owned_file_without_an_engine_change() {
        let mut recipe = Recipe::default();
        recipe.files.push(CopyFile {
            artifact: "voyahtune-new.json".into(),
            variant_artifact: false,
            variants: vec![Variant::Full, Variant::Light],
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
            "/data/local/bin/frida-inject",
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
    fn rejects_shell_and_duplicate_targets() {
        let mut value = serde_json::to_value(Recipe::default()).unwrap();
        value["shell"] = serde_json::json!("rm -rf /");
        assert!(serde_json::from_value::<Recipe>(value).is_err());
        let mut recipe = Recipe::default();
        recipe.files.push(recipe.files[0].clone());
        assert!(recipe.validate().is_err());
    }
}
