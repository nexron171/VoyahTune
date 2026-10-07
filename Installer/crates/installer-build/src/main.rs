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
    infrastructure: Infrastructure,
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
    args.infrastructure.require_version(&args.version)?;
    let (recipe, source_spec) = discover(&root, args.infrastructure)?;
    recipe.validate()?;
    fs::create_dir_all(root.join("Updater/build"))?;
    write_json(
        &root.join("Updater/build/bootstrap.json"),
        &serde_json::json!({"schema":1,"version":args.version,"infrastructure":args.infrastructure}),
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
    if args.infrastructure == Infrastructure::Pi {
        let status = Command::new("sh")
            .arg(root.join("Packaging/pi/loaderFrida/build.sh"))
            .status()?;
        if !status.success() {
            return Err(Error::new(
                "PI_LOADER_BUILD",
                "Не удалось собрать PI loaderFrida",
            ));
        }
    }
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
            .env("VOYAH_INFRASTRUCTURE", args.infrastructure.as_str())
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
        if args.infrastructure == Infrastructure::Pi {
            let status = Command::new(root.join("Updater/gradlew"))
                .current_dir(root.join("Updater"))
                .args(["--offline", "assembleRelease"])
                .status()?;
            if !status.success() {
                return Err(Error::new(
                    "UPDATER_BUILD",
                    "Не удалось собрать интерфейс обновления",
                ));
            }
            // Gradle maps a canonical source path to one signed artifact name.
            // Keep delivery and stable roles as separate files, even when bytes match.
            fs::copy(
                root.join("Updater/app/build/outputs/apk/release/app-release.apk"),
                root.join("Updater/build/ui-next.apk"),
            )?;
        }
        let projects = if args.infrastructure == Infrastructure::Pi {
            vec!["RunYN", "Native", "RestoreMode"]
        } else {
            vec!["Native", "RestoreMode"]
        };
        for project in projects {
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
                    &format!("-PvoyahReleaseVersion={}", args.version),
                    &format!("-PvoyahInfrastructure={}", args.infrastructure.as_str()),
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
            infrastructure: args.infrastructure,
            schema: 4,
            removal_only: false,
            requirements: Some(if args.infrastructure == Infrastructure::Od {
                installer_core::compatibility::Requirements::restoremode_ota()
            } else { installer_core::compatibility::Requirements::infrastructure() }),
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
        write_json(&stage.join("manifest.json"), &manifest)?;
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
    let infrastructure = Infrastructure::Od;
    let (recipe, sources) = discover(root, infrastructure)?;
    fs::create_dir_all(output)?;
    let mut manifest = Manifest {
        infrastructure,
        schema: 4,
        removal_only: true,
        requirements: Some(installer_core::compatibility::Requirements::infrastructure()),
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
    write_json(&output.join("manifest.json"), &manifest)?;
    Payload::open(output)?;
    Ok(())
}
// Developer inputs are application sources and Packaging files, not a release manifest.
fn discover(
    root: &Path,
    infrastructure: Infrastructure,
) -> Result<(installer_core::recipe::Recipe, serde_json::Value)> {
    use installer_core::recipe::{CopyFile, Phase, Recipe};
    let spec = root.join("Packaging/installer/payload-spec.json");
    let mut recipe: Recipe = serde_json::from_slice(&fs::read(spec)?)?;
    let profile = format!("Packaging/{}", infrastructure.as_str());
    // OD embeds the OTA Activity in RestoreMode; PI keeps its existing UI delivery.
    if infrastructure == Infrastructure::Od {
        recipe.files.retain(|file| {
            !["voyahtune-updater.apk", "voyahtune-ui-maintenance", "voyahtune-ui-next.apk"]
                .contains(&file.artifact.as_str())
        });
    }
    if infrastructure == Infrastructure::Pi {
        for file in &mut recipe.files {
            if file.artifact == "load.bin" {
                file.artifact = "loaderFrida".into();
                file.destination = "/data/local/bin/loaderFrida".into();
            }
        }
        recipe.files.push(CopyFile {
            artifact: "injects.json".into(),
            destination: "/data/local/bin/injects.json".into(),
            mode: 0o644,
            phase: Phase::Files,
        });
        recipe
            .packages
            .push(installer_core::recipe::InstallPackage {
                artifact: "runyn.apk".into(),
                package: payload::RUNYN.into(),
            });
    }
    for path in include_str!("../../release-core/src/pi-cleanup-paths.txt")
        .lines()
        .filter(|p| !p.is_empty())
    {
        if !recipe.remove_files.iter().any(|p| p == path) {
            recipe.remove_files.push(path.into());
        }
    }
    if !recipe
        .remove_directories
        .iter()
        .any(|p| p == "/data/local/tmp/voyahtune-pi")
    {
        recipe
            .remove_directories
            .push("/data/local/tmp/voyahtune-pi".into());
    }
    let mut artifacts = Vec::new();
    if infrastructure == Infrastructure::Pi {
        artifacts.push(serde_json::json!({"name":"runyn.apk", "source":"RunYN/app/build/outputs/apk/release/app-release.apk"}));
    }
    for (name, project) in [
        ("native.apk", "Native"),
        ("restore_mode.apk", "RestoreMode"),
    ] {
        artifacts.push(serde_json::json!({"name":name,
            "source":format!("{project}/app/build/outputs/apk/release/app-release.apk")}));
    }
    // Hooks/configs are discovered automatically, including newly added owned files.
    let mut inject = fs::read_dir(root.join(format!("{profile}/inject")))?
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
        !is_hook
            || ["voyahtune-ota-bootstrap.json", "injects.json"].contains(&f.artifact.as_str())
            || names.contains(&f.artifact)
    });
    for name in names {
        if !recipe.files.iter().any(|f| f.artifact == name) {
            recipe.files.push(CopyFile {
                destination: format!("/data/local/bin/{name}"),
                artifact: name,
                mode: 0o644,
                phase: Phase::Files,
            });
        }
    }
    for file in &recipe.files {
        if file.artifact == "native.apk" {
            continue;
        }
        let name = &file.artifact;
        let source = match name.as_str() {
            "voyahtune-updater" => "Updater/build/daemon/arm64-v8a/voyahtune-updater".into(),
            "voyahtune-ui-maintenance" => {
                "Updater/build/daemon/arm64-v8a/voyahtune-ui-maintenance".into()
            }
            "voyahtune-ui-next.apk" => "Updater/build/ui-next.apk".into(),
            "voyahtune-updater.apk" => {
                "Updater/app/build/outputs/apk/release/app-release.apk".into()
            }
            "voyahtune-ota-bootstrap.json" => "Updater/build/bootstrap.json".into(),
            "voyahtune.updater.rc" if infrastructure == Infrastructure::Od => format!("Packaging/od/system/{name}"),
            "voyahtune.updater.rc" => format!("Packaging/system/{name}"),
            "whitelist.xml" => {
                "Packaging/system/privapp-permissions-ru.big.town.anative.xml".into()
            }
            "frida-inject" => format!("{profile}/tools/frida-inject-16.2.1-android-arm64"),
            "loaderFrida" => "Releases/build/pi/loaderFrida/arm64-v8a/loaderFrida".into(),
            "injects.json" => "Packaging/pi/loaderFrida/injects.json".into(),
            "load.bin" | "voyahtune.load.rc" | "voyahtune.load.sh" => {
                format!("{profile}/system/{name}")
            }
            _ => format!("{profile}/inject/{name}"),
        };
        artifacts.push(serde_json::json!({"name":name,"source":source}));
    }
    for (name, source) in [
        (
            "init.logcat.original.sh",
            format!("{profile}/system/init.logcat.original.sh"),
        ),
        (
            "dns-helper.sh",
            format!("{profile}/installer/common/dns-overlay-device.sh"),
        ),
        (
            "dns.apk",
            "Packaging/vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk"
                .into(),
        ),
    ] {
        artifacts.push(serde_json::json!({"name":name,"source":source}));
    }
    recipe.validate()?;
    Ok((
        recipe,
        serde_json::json!({"schema":1,"infrastructure":infrastructure,"artifacts":artifacts}),
    ))
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
    fn build_requires_profile_and_recovery_is_universal() {
        let argv = [
            "installer-build",
            "build",
            "--root",
            ".",
            "--output",
            "out",
            "--version",
            "3.21.0-od",
            "--revision",
            "test",
        ];
        assert!(Cli::try_parse_from(argv).is_err());
        for infrastructure in ["pi", "od"] {
            let mut valid = argv.to_vec();
            valid.extend(["--infrastructure", infrastructure]);
            assert!(Cli::try_parse_from(valid).is_ok());
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
    fn checkout_payload_contains_every_required_runtime_file() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let (recipe, sources) = discover(&root, Infrastructure::Od).unwrap();
        for name in ["voyahtune-updater.apk", "voyahtune-ui-maintenance", "voyahtune-ui-next.apk"] {
            assert!(!recipe.files.iter().any(|f| f.artifact == name));
            assert!(!sources["artifacts"].as_array().unwrap().iter().any(|a| a["name"] == name));
        }
        recipe.validate().unwrap();
        for name in payload::RUNTIME_NAMES.iter().filter(|name| {
            ![
                "loaderFrida",
                "injects.json",
                "clusternavi.js",
                "phone-num.js",
            ]
            .contains(name)
        }) {
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
    fn pi_payload_selects_go_loader_and_only_pi_hook_sources() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let (recipe, sources) = discover(&root, Infrastructure::Pi).unwrap();
        assert!(recipe.files.iter().any(|f| f.artifact == "loaderFrida"
            && f.destination == "/data/local/bin/loaderFrida"
            && f.mode == 0o755));
        assert!(recipe.files.iter().any(|f| f.artifact == "injects.json"));
        assert!(recipe
            .packages
            .iter()
            .any(|p| p.artifact == "runyn.apk" && p.package == payload::RUNYN));
        assert!(sources["artifacts"]
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["name"] == "runyn.apk"
                && a["source"] == "RunYN/app/build/outputs/apk/release/app-release.apk"));
        for absent in [
            "load.bin",
            "app_client.js",
            "voyahtune_acc_restore.js",
            "voyahtune_drive_reset.js",
        ] {
            assert!(
                !recipe.files.iter().any(|f| f.artifact == absent),
                "{absent}"
            );
        }
        for present in ["clusternavi.js", "phone-num.js"] {
            assert!(recipe.files.iter().any(|f| f.artifact == present));
        }
        for source in sources["artifacts"].as_array().unwrap() {
            let name = source["name"].as_str().unwrap();
            if name.ends_with(".js") {
                assert!(source["source"]
                    .as_str()
                    .unwrap()
                    .starts_with("Packaging/pi/inject/"));
            }
        }
        let (od, _) = discover(&root, Infrastructure::Od).unwrap();
        for path in [
            "/data/local/bin/loaderFrida",
            "/data/local/bin/injects.json",
            "/data/local/bin/clusternavi.js",
            "/data/local/bin/phone-num.js",
        ] {
            assert!(od.remove_files.iter().any(|p| p == path));
        }
    }
    #[test]
    fn discovers_new_hook_and_keeps_cleanup_after_its_removal() {
        let root = std::env::temp_dir().join(format!("voyahtune-discovery-{}", std::process::id()));
        let inject = root.join("Packaging/od/inject");
        fs::create_dir_all(&inject).unwrap();
        let spec = root.join("Packaging/installer/payload-spec.json");
        fs::create_dir_all(spec.parent().unwrap()).unwrap();
        fs::copy(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../../Packaging/installer/payload-spec.json"),
            &spec,
        )
        .unwrap();
        let hook = inject.join("voyahtune-discovery.js");
        fs::write(&hook, "// test hook").unwrap();
        fs::write(inject.join("notes.txt"), "not an installable file").unwrap();
        let (recipe, sources) = discover(&root, Infrastructure::Od).unwrap();
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
        // Retired paths are explicit source history, not inferred from a prefix.
        let mut retired: installer_core::recipe::Recipe =
            serde_json::from_slice(&fs::read(&spec).unwrap()).unwrap();
        retired
            .remove_files
            .push("/data/local/bin/voyahtune-discovery.js".into());
        fs::write(&spec, serde_json::to_vec(&retired).unwrap()).unwrap();
        let (recipe, _) = discover(&root, Infrastructure::Od).unwrap();
        assert!(!recipe
            .files
            .iter()
            .any(|f| f.artifact == "voyahtune-discovery.js"));
        assert!(recipe
            .remove_files
            .iter()
            .any(|p| p == "/data/local/bin/voyahtune-discovery.js"));
        fs::write(
            inject.join("unowned.js"),
            "// must not claim third-party paths",
        )
        .unwrap();
        assert!(discover(&root, Infrastructure::Od).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
