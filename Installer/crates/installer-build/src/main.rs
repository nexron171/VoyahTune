use clap::{Parser, Subcommand};
use installer_core::infrastructure::Infrastructure;
use installer_core::{
    payload::{self, Artifact, Manifest, Payload},
    recovery::write_json,
    Error, Result,
};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
};
#[derive(Parser)]
#[command(about = "Инструменты сборки VoyahTune (не включаются в GUI)")]
struct Cli {
    #[command(subcommand)]
    command: CommandKind,
}
#[derive(Subcommand)]
enum CommandKind {
    Build(Args),
    PrepareUnifiedRuntime {
        #[arg(long)]
        root: PathBuf,
        #[arg(long)]
        output: PathBuf,
    },
    VerifyPayload {
        path: PathBuf,
    },
    VerifyHost {
        path: PathBuf,
    },
    VerifyOta {
        entry: PathBuf,
        payload: PathBuf,
    },
    VerifyCatalog {
        path: PathBuf,
    },
    Recovery {
        #[arg(long)]
        root: PathBuf,
        #[arg(long)]
        output: PathBuf,
    },
}
#[derive(clap::Args)]
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
    let result = match Cli::parse().command {
        CommandKind::PrepareUnifiedRuntime { root, output } => (|| -> Result<()> {
            let (recipe, sources) = discover_unified(&root.canonicalize()?)?;
            fs::create_dir_all(&output)?;
            write_json(&output.join("recipe.json"), &recipe)?;
            write_json(&output.join("sources.json"), &sources)?;
            println!("{}", output.display());
            Ok(())
        })(),
        CommandKind::VerifyOta {
            entry,
            payload: directory,
        } => (|| -> Result<()> {
            let entry: installer_core::catalog::UpdateRelease =
                serde_json::from_slice(&fs::read(entry)?)?;
            let claims = installer_core::ota::verify(&entry.into_release())?;
            installer_core::ota::verify_payload(&Payload::open(&directory)?, &claims)?;
            println!("{}", serde_json::to_string(&claims)?);
            Ok(())
        })(),
        CommandKind::Recovery { root, output } => build_recovery(&root, &output),
        CommandKind::Build(args) => run(args),
        CommandKind::VerifyPayload { path } => (|| -> Result<()> {
            let p = Payload::open(&path)?;
            let mut signers = std::collections::BTreeMap::new();
            if !p.manifest.removal_only {
                for (name, id) in [
                    ("native.apk", payload::NATIVE),
                    ("restore_mode.apk", payload::RESTORE),
                ] {
                    signers.insert(id, payload::verified_signers(&p.file(name)?)?);
                }
            }
            println!(
                "{}",
                serde_json::json!({"valid":true,"manifest":p.manifest,"payloadRoot":p.root,"apkSigners":signers})
            );
            Ok(())
        })(),
        CommandKind::VerifyCatalog { path } => (|| -> Result<()> {
            let catalog: installer_core::catalog::UpdateCatalog =
                serde_json::from_slice(&fs::read(path)?)?;
            catalog.validate()?;
            println!("{}", serde_json::to_string_pretty(&catalog)?);
            Ok(())
        })(),
        CommandKind::VerifyHost { path } => {
            payload::verify_host(&path).map(|_| println!("{}", serde_json::json!({"valid":true})))
        }
    };
    if let Err(e) = result {
        eprintln!("{e}");
        std::process::exit(1)
    }
}
fn run(args: Args) -> Result<()> {
    let root = args.root.canonicalize()?;
    if args.version.is_empty() || args.version.contains('/') || args.revision.is_empty() {
        return Err(Error::new("BUILD_ARGUMENTS", "Укажите версию и revision"));
    }
    Infrastructure::Unified.require_version(&args.version)?;
    let status = Command::new("sh")
        .arg(root.join("Packaging/new/build.sh"))
        .status()?;
    if !status.success() {
        return Err(Error::new(
            "UNIFIED_BUILD",
            "Не удалось собрать новый runtime",
        ));
    }
    let (recipe, source_spec) = discover_unified(&root)?;
    recipe.validate()?;
    fs::create_dir_all(root.join("Updater/build"))?;
    write_json(
        &root.join("Updater/build/bootstrap.json"),
        &serde_json::json!({"schema":1,"version":args.version,"infrastructure":Infrastructure::Unified}),
    )?;
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
        let ndk = std::env::var_os("ANDROID_NDK_HOME")
            .map(PathBuf::from)
            .or_else(|| {
                std::env::var_os("ANDROID_HOME").map(|p| PathBuf::from(p).join("ndk/27.0.12077973"))
            })
            .ok_or_else(|| {
                Error::new("NDK_MISSING", "Задайте ANDROID_NDK_HOME для сборки updater")
            })?;
        let status = Command::new("python3")
            .arg(root.join("Updater/build-daemon.py"))
            .arg("--ndk")
            .arg(ndk)
            .status()?;
        if !status.success() {
            return Err(Error::new(
                "UPDATER_BUILD",
                "Не удалось собрать root-службу",
            ));
        }
        for project in ["RunYN", "Native", "RestoreMode"] {
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
                    "assembleRelease",
                    &format!("-PvoyahPayloadFormat={}", payload::PAYLOAD_FORMAT),
                    &format!(
                        "-PvoyahMinimumInstallerVersion={}",
                        installer_core::compatibility::Requirements::unified()
                            .min_installer_version
                    ),
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
            infrastructure: Infrastructure::Unified,
            schema: payload::PAYLOAD_FORMAT,
            removal_only: false,
            requirements: Some(installer_core::compatibility::Requirements::unified()),
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
            copy(&stage, &source, name, &mut manifest)?;
        }
        Payload::open(&stage)?;
        if args.output.exists() {
            return Err(Error::new("OUTPUT_EXISTS","Папка payload уже существует. Укажите новый output; готовый релиз не перезаписывается."));
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
fn build_recovery(root: &Path, output: &Path) -> Result<()> {
    let infrastructure = Infrastructure::Unified;
    let (mut recipe, sources) = base_payload(root)?;
    recipe.remove_directories = recipe.cleanup_directories();
    fs::create_dir_all(output)?;
    if output.join("manifest.json").exists() {
        fs::remove_file(output.join("manifest.json"))?;
    }
    let mut manifest = Manifest {
        infrastructure,
        schema: payload::PAYLOAD_FORMAT,
        removal_only: true,
        requirements: Some(installer_core::compatibility::Requirements::unified()),
        recipe,
        product: "VoyahTune".into(),
        release_version: env!("CARGO_PKG_VERSION").into(),
        build_revision: "builtin-remover".into(),
        artifacts: vec![],
    };
    for item in sources["artifacts"].as_array().unwrap().iter().filter(|a| {
        ["dns-helper.sh", "init.logcat.original.sh"].contains(&a["name"].as_str().unwrap())
    }) {
        copy(
            output,
            &root.join(item["source"].as_str().unwrap()),
            item["name"].as_str().unwrap(),
            &mut manifest,
        )?;
    }
    Payload::open(output)?;
    Ok(())
}
// Developer inputs are application sources and Packaging files, not a release manifest.
fn base_payload(root: &Path) -> Result<(installer_core::recipe::Recipe, serde_json::Value)> {
    let recipe = installer_core::recipe::Recipe::default();
    let mut artifacts = Vec::new();
    for name in recipe
        .files
        .iter()
        .map(|file| file.artifact.as_str())
        .chain(
            recipe
                .packages
                .iter()
                .map(|package| package.artifact.as_str()),
        )
        .chain(["init.logcat.original.sh", "dns-helper.sh", "dns.apk"])
    {
        let source = if let Some((_, filename, _)) = payload::NATIVE_RUNTIME_ROLES
            .iter()
            .find(|(role, _, _)| *role == name)
        {
            format!("Packaging/new/build/runtime/{filename}")
        } else {
            match name {
            "native.apk" => "Native/app/build/outputs/apk/release/app-release.apk",
            "restore_mode.apk" => "RestoreMode/app/build/outputs/apk/release/app-release.apk",
            "runyn.apk" => "RunYN/app/build/outputs/apk/release/app-release.apk",
            "whitelist.xml" => "Packaging/system/privapp-permissions-ru.big.town.anative.xml",
            "voyahtune-updater" => "Updater/build/daemon/arm64-v8a/voyahtune-updater",
            "voyahtune.updater.rc" => "Packaging/system/voyahtune.updater.rc",
            "voyahtune-ota-bootstrap.json" => "Updater/build/bootstrap.json",
            "voyahtune.load.sh" => "Packaging/new/system/voyahtune.load.sh",
            "voyahtune.load.rc" => "Packaging/new/system/voyahtune.load.rc",
            "init.logcat.original.sh" => "Packaging/system/init.logcat.original.sh",
            "dns-helper.sh" => "Packaging/installer/common/dns-overlay-device.sh",
            "dns.apk" => {
                "Packaging/vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk"
            }
            _ => return Err(Error::new("SOURCE_SCHEMA", "Неизвестная базовая роль").detail(name)),
        }.to_owned()
        };
        artifacts.push(serde_json::json!({"name": name, "source": source}));
    }
    recipe.validate()?;
    Ok((
        recipe,
        serde_json::json!({"schema": 1, "infrastructure": "unified", "artifacts": artifacts}),
    ))
}

fn discover_unified(root: &Path) -> Result<(installer_core::recipe::Recipe, serde_json::Value)> {
    base_payload(root)
}

fn copy(stage: &Path, source: &Path, name: &str, manifest: &mut Manifest) -> Result<()> {
    if name.is_empty()
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        || name == "."
        || name == ".."
    {
        return Err(Error::new("SOURCE_PATH", "Недопустимое имя артефакта").detail(name));
    }
    let path = format!("common/{name}");
    let target = stage.join(&path);
    fs::create_dir_all(target.parent().unwrap())?;
    fs::copy(source, &target)?;
    manifest.artifacts.push(Artifact {
        name: name.into(),
        path,
        sha256: payload::sha256(&target)?,
        size: target.metadata()?.len(),
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtin_remover_covers_old_and_new_runtime_without_built_unified_resources() {
        let checkout = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let root = std::env::temp_dir().join(format!("voyahtune-recovery-{}", std::process::id()));
        for relative in [
            "Packaging/system/init.logcat.original.sh",
            "Packaging/installer/common/dns-overlay-device.sh",
        ] {
            let destination = root.join(relative);
            fs::create_dir_all(destination.parent().unwrap()).unwrap();
            fs::copy(checkout.join(relative), destination).unwrap();
        }
        let output = root.join("recovery");
        build_recovery(&root, &output).unwrap();
        let payload = Payload::open(&output).unwrap();
        assert!(payload.manifest.removal_only);
        assert_eq!(payload.manifest.artifacts.len(), 2);
        let recipe = &payload.manifest.recipe;
        for directory in [
            "/data/local/bin/voyahtune-new",
            "/data/local/tmp/voyahtune-new",
            "/data/local/tmp/voyahtune-pi",
        ] {
            assert!(
                recipe.remove_directories.contains(&directory.into()),
                "{directory}"
            );
        }
        for file in [
            "/data/local/bin/loaderFrida",
            "/data/local/bin/injects.json",
            "/data/local/bin/clusternavi.js",
        ] {
            assert!(recipe.cleanup_files().contains(&file.into()), "{file}");
        }
        for path in [
            "/data/local/bin/load.bin",
            "/data/local/bin/frida-inject",
            "/system/etc/init/voyahtune.load.rc",
            "/system/etc/init.voyahtune.load.sh",
        ] {
            assert!(recipe.cleanup_files().contains(&path.into()), "{path}");
        }
        assert_eq!(payload.manifest.infrastructure, Infrastructure::Unified);
        assert!(!root.join("Packaging/new").exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn build_defaults_to_common_payload_and_recovery_is_universal() {
        let argv = [
            "installer-build",
            "build",
            "--root",
            ".",
            "--output",
            "out",
            "--version",
            "4.5.0",
            "--revision",
            "test",
        ];
        let Cli {
            command: CommandKind::Build(args),
        } = Cli::try_parse_from(argv).unwrap()
        else {
            panic!("Expected build")
        };
        assert_eq!(args.version, "4.5.0");
        for infrastructure in ["pi", "od", "unified"] {
            let mut obsolete = argv.to_vec();
            obsolete.extend(["--infrastructure", infrastructure]);
            assert!(Cli::try_parse_from(obsolete).is_err());
        }
        let mut invalid = argv.to_vec();
        invalid.extend(["--infrastructure", "typo"]);
        assert!(Cli::try_parse_from(invalid).is_err());
        assert!(Cli::try_parse_from([
            "installer-build",
            "recovery",
            "--root",
            ".",
            "--output",
            "out"
        ])
        .is_ok());
    }
    #[test]
    fn common_recipe_replaces_both_runtimes_and_conditionally_installs_runyn() {
        use installer_core::recipe::PackageCondition;
        let root = std::env::temp_dir();
        let (mut recipe, sources) = discover_unified(&root).unwrap();
        assert_eq!(sources["infrastructure"], "unified");
        for obsolete in [
            "load.bin",
            "loaderFrida",
            "frida-inject",
            "injects.json",
            "voyahtune_drive_reset.js",
        ] {
            assert!(!recipe.files.iter().any(|file| file.artifact == obsolete));
            assert!(recipe
                .remove_files
                .contains(&format!("/data/local/bin/{obsolete}")));
            assert!(!sources["artifacts"]
                .as_array()
                .unwrap()
                .iter()
                .any(|source| source["name"] == obsolete));
        }
        assert!(!recipe
            .remove_files
            .iter()
            .any(|path| path.contains("updater")));
        assert_eq!(recipe.packages.len(), 2);
        let runyn = recipe
            .packages
            .iter_mut()
            .find(|package| package.package == payload::RUNYN)
            .unwrap();
        assert_eq!(runyn.condition, Some(PackageCondition::ExceptH97xy));
        runyn.condition = None;
        assert!(recipe.validate().is_err());
        recipe.validate().unwrap_err();
    }
}
