use clap::{Parser, Subcommand, ValueEnum};
use installer_core::{
    adb::Adb,
    canbus::RemovalConsent,
    engine::{default_adb, Engine},
    engineering_menu,
    events::Events,
    inventory,
    payload::Payload,
    plans::{self, Action, Dns, Request},
    recovery, Error, Result,
};
use serde_json::json;
use std::{
    io::{self, BufRead, Write},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
#[derive(Parser)]
#[command(
    name = "voyahtune",
    version,
    about = "Самодостаточный установщик VoyahTune. Все команды работают с комплектным ADB."
)]
struct Cli {
    #[arg(long, global = true, help = "Папка bundle, содержащая payload и adb")]
    bundle: Option<PathBuf>,
    #[arg(
        long,
        global = true,
        help = "Внешняя папка payload или её manifest.json"
    )]
    payload: Option<PathBuf>,
    #[command(subcommand)]
    command: Commands,
}
#[derive(Clone, Copy, ValueEnum)]
enum Choice {
    Full,
    Light,
    Remove,
}
impl From<Choice> for Action {
    fn from(v: Choice) -> Self {
        match v {
            Choice::Full => Self::Full,
            Choice::Light => Self::Light,
            Choice::Remove => Self::Remove,
        }
    }
}
#[derive(Clone, Copy, ValueEnum)]
enum DnsChoice {
    Keep,
    On,
    Off,
}
impl From<DnsChoice> for Dns {
    fn from(v: DnsChoice) -> Self {
        match v {
            DnsChoice::Keep => Self::Keep,
            DnsChoice::On => Self::On,
            DnsChoice::Off => Self::Off,
        }
    }
}
#[derive(Subcommand)]
enum Commands {
    /// Код инженерного меню для указанной даты; по умолчанию дата UTC+8.
    EngineeringCode {
        #[arg(long)]
        date: Option<String>,
    },
    /// Проверить только постоянные инструменты, без релизного payload и автомобиля.
    VerifyHost,
    /// Проверить комплект установщика, не обращаясь к автомобилю.
    Verify {
        #[arg(
            long,
            help = "Проверить только payload при кросс-сборке; ADB не запускается"
        )]
        payload_only: bool,
    },
    /// Прочитать сведения о комплекте без дополнительных проверок целостности.
    Info,
    /// Один запрос списка ADB-устройств. Ничего не изменяет.
    Devices,
    /// Показать сведения об автомобиле и шаги для ручного подтверждения.
    Plan {
        #[arg(long)]
        device: String,
        #[arg(long, value_enum)]
        action: Choice,
        #[arg(long, value_enum, default_value = "keep")]
        dns: DnsChoice,
        #[arg(long, help = "Сохранить план для apply-plan без внешнего JSON-парсера")]
        output: Option<PathBuf>,
    },
    /// Выполнить сохранённый план после его проверки пользователем.
    ApplyPlan {
        file: PathBuf,
        #[arg(long)]
        yes: bool,
        #[arg(long)]
        logs: Option<PathBuf>,
        #[arg(long)]
        interactive: bool,
        #[arg(
            long,
            help = "Разрешить удаление com.voyah.hl.service, его данных и системной папки с перезагрузкой"
        )]
        remove_voyah_hl_service: bool,
    },
    /// Применить ранее подтверждённый план. Требуются серийный номер, токен и --yes.
    Apply {
        #[arg(long)]
        device: String,
        #[arg(long)]
        token: String,
        #[arg(long, value_enum)]
        action: Choice,
        #[arg(long, value_enum, default_value = "keep")]
        dns: DnsChoice,
        #[arg(long)]
        yes: bool,
        #[arg(long)]
        logs: Option<PathBuf>,
        #[arg(
            long,
            help = "Принимать отмену и решение confirmRemoveVoyahHlService через JSON stdin"
        )]
        interactive: bool,
        #[arg(
            long,
            help = "Разрешить удаление com.voyah.hl.service, его данных и системной папки с перезагрузкой"
        )]
        remove_voyah_hl_service: bool,
    },
}
fn main() {
    if let Err(error) = run(Cli::parse()) {
        let kind = match error.code.as_str() {
            "CANCELLED" => "cancelled",
            "ACTION_REQUIRED" => "notification",
            _ => "error",
        };
        println!("{}", json!({"type":kind,"error":error}));
        std::process::exit(match error.code.as_str() {
            "CANCELLED" => 130,
            "ACTION_REQUIRED" => 3,
            _ => 1,
        });
    }
}
fn run(cli: Cli) -> Result<()> {
    // The saved file contains the same explicit action, serial and state token
    // used by the GUI. Engine repeats inventory; a stale file cannot skip it.
    if let Commands::ApplyPlan {
        file,
        yes,
        logs,
        interactive,
        remove_voyah_hl_service,
    } = cli.command
    {
        let plan: plans::Plan = serde_json::from_reader(std::fs::File::open(file)?)?;
        return run(Cli {
            bundle: cli.bundle,
            payload: cli.payload,
            command: Commands::Apply {
                device: plan.request.serial,
                token: plan.request.inventory_token,
                action: match plan.request.action {
                    Action::Full => Choice::Full,
                    Action::Light => Choice::Light,
                    Action::Remove => Choice::Remove,
                },
                dns: match plan.request.dns {
                    Dns::Keep => DnsChoice::Keep,
                    Dns::On => DnsChoice::On,
                    Dns::Off => DnsChoice::Off,
                },
                yes,
                logs,
                interactive,
                remove_voyah_hl_service,
            },
        });
    }
    if let Commands::EngineeringCode { date } = cli.command {
        println!(
            "{}",
            serde_json::to_string(&engineering_menu::calculate(date.as_deref())?)?
        );
        return Ok(());
    }
    let exe = std::env::current_exe()?;
    let folder = exe.parent().unwrap();
    let default_bundle =
        if cfg!(target_os = "macos") && folder.file_name().is_some_and(|n| n == "MacOS") {
            folder.parent().unwrap().join("Resources/bundle")
        } else if cfg!(target_os = "linux")
            && folder.file_name().is_some_and(|n| n == "bin")
            && !folder.join("bundle").exists()
        {
            folder
                .parent()
                .unwrap()
                .join("share/voyahtune-installer/bundle")
        } else {
            folder.join("bundle")
        };
    let bundle = cli.bundle.unwrap_or(default_bundle);
    if matches!(
        cli.command,
        Commands::VerifyHost
            | Commands::Verify {
                payload_only: false
            }
    ) {
        installer_core::payload::verify_host(&bundle)?;
    }
    if let Commands::VerifyHost = cli.command {
        println!(
            "{}",
            json!({"valid":true,"toolingVersion":env!("CARGO_PKG_VERSION")})
        );
        return Ok(());
    }
    if let Commands::Devices = cli.command {
        let adb = Adb::new(default_adb(&bundle), Events::quiet())?;
        println!("{}", json!({"devices":adb.devices()?}));
        return Ok(());
    }
    let payload_root = installer_core::payload::locate(&bundle, &exe, cli.payload.as_deref())?;
    let payload = if matches!(cli.command, Commands::Verify { .. }) {
        Payload::open(&payload_root)?
    } else {
        Payload::load(&payload_root)?
    };
    match cli.command {
        Commands::Verify { .. } | Commands::Info => {
            println!(
                "{}",
                json!({"valid":matches!(cli.command, Commands::Verify { .. }),"manifest":payload.manifest,"payloadRoot":payload.root,"toolingVersion":env!("CARGO_PKG_VERSION")})
            )
        }
        Commands::Plan {
            device,
            action,
            dns,
            output,
        } => {
            let adb = Adb::new(default_adb(&bundle), Events::quiet())?.with_device(&device)?;
            // Match the classic scripts: root first, then inspect protected files.
            adb.require_single()?;
            for args in [&["root"][..], &["wait-for-device"][..], &["root"][..]] {
                let _ = adb.run(args, None, std::time::Duration::from_secs(20));
            }
            let plan = plans::plan(
                inventory::diagnose(&adb, &payload, action.into()),
                &payload,
                action.into(),
                dns.into(),
            )?;
            if let Some(path) = output {
                recovery::write_json(&path, &plan)?;
            }
            println!("{}", serde_json::to_string(&plan)?);
        }
        Commands::Apply {
            device,
            token,
            action,
            dns,
            yes,
            logs,
            interactive,
            remove_voyah_hl_service,
        } => {
            if !yes {
                return Err(Error::new("CONFIRMATION_REQUIRED","Операция изменяет автомобиль. Проверьте результат plan и передайте --yes для подтверждения."));
            }
            let cancel = Arc::new(AtomicBool::new(false));
            let consent = (interactive || remove_voyah_hl_service)
                .then(|| Arc::new(RemovalConsent::new(remove_voyah_hl_service)));
            if interactive {
                let c = cancel.clone();
                let input_consent = consent.clone().unwrap();
                std::thread::spawn(move || {
                    for line in io::stdin().lock().lines() {
                        let Ok(line) = line else {
                            break;
                        };
                        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&line) {
                            if value["cancel"] == true {
                                c.store(true, Ordering::Relaxed);
                                break;
                            }
                            if let Some(approved) = value["confirmRemoveVoyahHlService"].as_bool() {
                                input_consent.answer(approved);
                            }
                        }
                    }
                    // EOF means the controlling GUI/script has disappeared.
                    c.store(true, Ordering::Relaxed);
                });
            }
            let output_cancel = cancel.clone();
            let callback = Arc::new(move |event: &installer_core::events::Event| {
                let mut out = io::stdout().lock();
                if serde_json::to_writer(&mut out, event).is_err()
                    || out.write_all(b"\n").is_err()
                    || out.flush().is_err()
                {
                    output_cancel.store(true, Ordering::Relaxed);
                }
            });
            let mut engine = Engine::new(
                &default_adb(&bundle),
                payload,
                &device,
                &logs.unwrap_or(recovery::data_dir()?),
                callback,
                cancel,
            )?;
            engine.canbus_consent = consent;
            engine.run(Request {
                action: action.into(),
                dns: dns.into(),
                serial: device,
                inventory_token: token,
                confirmed: yes,
            })?;
        }
        _ => unreachable!(),
    }
    Ok(())
}
