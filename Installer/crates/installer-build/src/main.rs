use clap::Parser;
use installer_core::{
    payload::{self, Artifact, Manifest, Payload, Variant},
    recovery::write_json,
    Error, Result,
};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
};
#[derive(Parser)]
#[command(about = "Сборка единого offline payload Full + Light из исходников проекта")]
struct Args {
    #[arg(long)]
    root: PathBuf,
    #[arg(long)]
    version: String,
    #[arg(long)]
    revision: String,
    #[arg(long)]
    output: PathBuf,
    #[arg(long)]
    skip_android: bool,
}
fn main() {
    if let Err(e) = run(Args::parse()) {
        eprintln!("{e}");
        std::process::exit(1)
    }
}
fn run(args: Args) -> Result<()> {
    let root = args.root.canonicalize()?;
    if args.version.is_empty() || args.version.contains('/') || args.revision.is_empty() {
        return Err(Error::new("BUILD_ARGUMENTS", "Укажите версию и revision"));
    }
    let (recipe, source_spec) = discover(&root)?;
    recipe.validate()?;
    let parent = args.output.parent().unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let recipe_file = parent.join(format!(".recipe-{}.json", std::process::id()));
    fs::write(
        &recipe_file,
        serde_json::to_vec(&serde_json::to_value(&recipe)?)?,
    )?;
    let sources_file = parent.join(format!(".sources-{}.json", std::process::id()));
    write_json(&sources_file, &source_spec)?;
    if !args.skip_android {
        for project in ["Native", "RestoreMode"] {
            #[cfg(not(windows))]
            let mut cmd = Command::new(root.join(project).join("gradlew"));
            #[cfg(windows)]
            let mut cmd = {
                let mut c = Command::new("cmd.exe");
                c.args(["/d", "/c", "gradlew.bat"]);
                c
            };
            let status = cmd
                .current_dir(root.join(project))
                .args([
                    "--no-daemon",
                    "assembleFullRelease",
                    "assembleLightRelease",
                    &format!("-PvoyahReleaseVersion={}", args.version),
                    &format!("-PvoyahBuildRevision={}", args.revision),
                    &format!(
                        "-PvoyahInstallRecipe={}",
                        recipe_file.canonicalize()?.display()
                    ),
                    &format!(
                        "-PvoyahReleaseSources={}",
                        sources_file.canonicalize()?.display()
                    ),
                ])
                .status()?;
            if !status.success() {
                return Err(Error::new(
                    "ANDROID_BUILD",
                    format!("Сборка {project} завершилась ошибкой"),
                ));
            }
        }
    }
    let parent = args.output.parent().unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    let stage = args
        .output
        .with_extension(format!("staging-{}", std::process::id()));
    fs::create_dir(&stage)?;
    let result = (|| {
        let mut manifest = Manifest {
            schema: 2,
            recipe,
            product: "VoyahTune".into(),
            release_version: args.version,
            build_revision: args.revision,
            artifacts: Vec::new(),
        };
        for item in source_spec["artifacts"]
            .as_array()
            .ok_or_else(|| Error::new("SOURCE_SCHEMA", "Нет списка artifacts"))?
        {
            let name = item["name"]
                .as_str()
                .ok_or_else(|| Error::new("SOURCE_SCHEMA", "Нет имени файла"))?;
            let source = item["source"]
                .as_str()
                .ok_or_else(|| Error::new("SOURCE_SCHEMA", "Нет пути исходника"))?;
            if Path::new(source)
                .components()
                .any(|c| !matches!(c, std::path::Component::Normal(_)))
            {
                return Err(Error::new("SOURCE_PATH", "Недопустимый путь исходника").detail(source));
            }
            let source = root.join(source).canonicalize()?;
            if !source.starts_with(&root) {
                return Err(Error::new(
                    "SOURCE_PATH",
                    "Исходник выходит за пределы проекта",
                ));
            }
            let variant: Option<Variant> = serde_json::from_value(item["variant"].clone())?;
            copy(&stage, &source, name, variant, &mut manifest)?;
        }
        write_json(&stage.join("manifest.json"), &manifest)?;
        Payload::open(&stage)?;
        if args.output.exists() {
            return Err(Error::new("OUTPUT_EXISTS","Папка payload уже существует. Укажите новый output; готовый комплект не перезаписывается."));
        }
        fs::rename(&stage, &args.output)?;
        println!(
            "{}",
            serde_json::json!({"payload":args.output,"releaseVersion":manifest.release_version,"files":manifest.artifacts.len()})
        );
        Ok(())
    })();
    let _ = fs::remove_file(recipe_file);
    let _ = fs::remove_file(sources_file);
    if result.is_err() {
        let _ = fs::remove_dir_all(&stage);
    }
    result
}
// Developer inputs are application sources and Packaging files, not a release manifest.
fn discover(root: &Path) -> Result<(installer_core::recipe::Recipe, serde_json::Value)> {
    use installer_core::recipe::{CopyFile, Phase, Recipe};
    let mut recipe = Recipe::default();
    let mut artifacts = Vec::new();
    for variant in ["full", "light"] {
        for (name, project) in [
            ("native.apk", "Native"),
            ("restore_mode.apk", "RestoreMode"),
        ] {
            artifacts.push(serde_json::json!({"name":name,"variant":variant,
                "source":format!("{project}/app/build/outputs/apk/{variant}/release/app-{variant}-release.apk")}));
        }
    }
    // Hooks/configs are discovered automatically, including newly added owned files.
    let mut inject = fs::read_dir(root.join("Packaging/inject"))?
        .map(|entry| entry.map(|e| e.path()))
        .collect::<std::io::Result<Vec<_>>>()?;
    inject.sort();
    let names: Vec<String> = inject
        .iter()
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e == "js" || e == "json"))
        .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
        .collect();
    recipe.files.retain(|f| {
        let is_hook = f.artifact.ends_with(".js") || f.artifact.ends_with(".json");
        !is_hook || names.contains(&f.artifact)
    });
    for name in names {
        if !recipe.files.iter().any(|f| f.artifact == name) {
            recipe.files.push(CopyFile {
                destination: format!("/data/local/bin/{name}"),
                artifact: name,
                variant_artifact: false,
                variants: vec![Variant::Full],
                mode: 0o644,
                phase: Phase::Files,
            });
        }
    }
    recipe.remove_prefixes.extend([
        "/data/local/bin/voyahtune-".into(),
        "/data/local/bin/voyahtune_".into(),
    ]);
    for file in &recipe.files {
        if file.artifact == "native.apk" {
            continue;
        }
        let name = &file.artifact;
        let source = match name.as_str() {
            "whitelist.xml" => {
                "Packaging/system/privapp-permissions-ru.big.town.anative.xml".into()
            }
            "frida-inject" => "Packaging/tools/frida-inject-16.2.1-android-arm64".into(),
            "load.bin" | "voyahtune.load.rc" | "voyahtune.load.sh" => {
                format!("Packaging/system/{name}")
            }
            _ => format!("Packaging/inject/{name}"),
        };
        artifacts.push(serde_json::json!({"name":name,"variant":null,"source":source}));
    }
    for (name, source) in [
        (
            "init.logcat.original.sh",
            "Packaging/system/init.logcat.original.sh",
        ),
        (
            "dns-helper.sh",
            "Packaging/installer/common/dns-overlay-device.sh",
        ),
        (
            "dns.apk",
            "Packaging/vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk",
        ),
    ] {
        artifacts.push(serde_json::json!({"name":name,"variant":null,"source":source}));
    }
    recipe.validate()?;
    Ok((
        recipe,
        serde_json::json!({"schema":1,"artifacts":artifacts}),
    ))
}
fn copy(
    stage: &Path,
    source: &Path,
    name: &str,
    variant: Option<Variant>,
    manifest: &mut Manifest,
) -> Result<()> {
    if name.is_empty()
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        || name == "."
        || name == ".."
    {
        return Err(Error::new("SOURCE_PATH", "Недопустимое имя артефакта").detail(name));
    }
    let path = format!("{}/{}", variant.map(|v| v.name()).unwrap_or("common"), name);
    let target = stage.join(&path);
    fs::create_dir_all(target.parent().unwrap())?;
    fs::copy(source, &target)?;
    manifest.artifacts.push(Artifact {
        name: name.into(),
        path,
        variant,
        sha256: payload::sha256(&target)?,
        size: target.metadata()?.len(),
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn checkout_payload_contains_every_required_full_file() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let (recipe, sources) = discover(&root).unwrap();
        recipe.validate().unwrap();
        for name in payload::FULL_NAMES {
            let entry = sources["artifacts"]
                .as_array()
                .unwrap()
                .iter()
                .find(|a| a["name"] == *name)
                .unwrap_or_else(|| panic!("Missing {name}"));
            assert!(
                root.join(entry["source"].as_str().unwrap()).is_file(),
                "Missing source for {name}"
            );
        }
        assert!(recipe.files.iter().any(|f| f.artifact == "app_client.js"));
        assert!(!recipe
            .files
            .iter()
            .any(|f| f.artifact == "fullscreen_client.js"));
    }
    #[test]
    fn discovers_new_hook_and_keeps_cleanup_after_its_removal() {
        let root = std::env::temp_dir().join(format!("voyahtune-discovery-{}", std::process::id()));
        let inject = root.join("Packaging/inject");
        fs::create_dir_all(&inject).unwrap();
        let hook = inject.join("voyahtune-discovery.js");
        fs::write(&hook, "// test hook").unwrap();
        fs::write(inject.join("notes.txt"), "not an installable file").unwrap();
        let (recipe, sources) = discover(&root).unwrap();
        assert!(recipe
            .files
            .iter()
            .any(|f| f.artifact == "voyahtune-discovery.js"));
        assert!(sources["artifacts"]
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["name"] == "voyahtune-discovery.js"));
        assert!(!sources.to_string().contains("notes.txt"));
        fs::remove_file(hook).unwrap();
        let (recipe, _) = discover(&root).unwrap();
        assert!(!recipe
            .files
            .iter()
            .any(|f| f.artifact == "voyahtune-discovery.js"));
        assert!(recipe
            .remove_prefixes
            .iter()
            .any(|p| "/data/local/bin/voyahtune-discovery.js".starts_with(p)));
        fs::write(
            inject.join("unowned.js"),
            "// must not claim third-party paths",
        )
        .unwrap();
        assert!(discover(&root).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
