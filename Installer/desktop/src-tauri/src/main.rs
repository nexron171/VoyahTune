#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use installer_core::{engineering_menu, plans::Request, recovery, Error, Result};
use serde_json::{json, Value};
use std::{
    fs,
    io::{BufRead, BufReader, Read, Write},
    path::PathBuf,
    process::{ChildStdin, Command, Stdio},
    sync::Mutex,
};
use tauri::{Emitter, Manager, State};
#[derive(Default)]
struct Running {
    busy: bool,
    stdin: Option<ChildStdin>,
    operation_dir: Option<PathBuf>,
    payload: Option<PathBuf>,
}
#[derive(Default)]
struct Runtime(Mutex<Running>);
fn paths(app: &tauri::AppHandle) -> Result<(PathBuf, PathBuf)> {
    let resources = app
        .path()
        .resource_dir()
        .map_err(|e| Error::new("RESOURCES", "Не найдены ресурсы приложения").detail(e))?;
    // Keep Android ELF payloads outside usr/lib: linuxdeploy treats every ELF
    // there as a host library and would attempt to modify the car's binaries.
    let bundle = if cfg!(target_os = "linux") {
        resources
            .parent()
            .and_then(|p| p.parent())
            .ok_or_else(|| Error::new("RESOURCES", "Неверная структура Linux-пакета"))?
            .join("share/voyahtune-installer/bundle")
    } else {
        resources.join("bundle")
    };
    let name = if cfg!(windows) {
        "voyahtune.exe"
    } else {
        "voyahtune"
    };
    let exe = std::env::current_exe()?;
    let executable = exe.parent().unwrap().join(name);
    // Development uses precisely the same prepared bundle as packaging.
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if cfg!(debug_assertions) && !bundle.join("host-tools.json").exists() {
        let target = if cfg!(target_os = "macos") {
            if cfg!(target_arch = "aarch64") {
                "aarch64-apple-darwin"
            } else {
                "x86_64-apple-darwin"
            }
        } else if cfg!(windows) {
            "x86_64-pc-windows-msvc"
        } else {
            "x86_64-unknown-linux-gnu"
        };
        return Ok((
            dev.join(format!(
                "binaries/voyahtune-{target}{}",
                if cfg!(windows) { ".exe" } else { "" }
            )),
            dev.join("resources/bundle"),
        ));
    }
    Ok((executable, bundle))
}
fn cli(app: &tauri::AppHandle, args: &[String]) -> Result<Command> {
    let (exe, bundle) = paths(app)?;
    if !exe.is_file() {
        return Err(Error::new(
            "CLI_MISSING",
            "В установщике отсутствует исполняемый модуль",
        )
        .detail(exe.display()));
    }
    let mut command = Command::new(exe);
    command
        .arg("--bundle")
        .arg(bundle)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(payload) = app
        .state::<Runtime>()
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?
        .payload
        .clone()
    {
        command.arg("--payload").arg(payload);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    Ok(command)
}
fn query(app: &tauri::AppHandle, args: &[String]) -> Result<Value> {
    let output = cli(app, args)?.output()?;
    let value: Value = serde_json::from_slice(&output.stdout).map_err(|e| {
        Error::new("CLI_PROTOCOL", "Исполняемый модуль вернул неверный ответ").detail(format!(
            "{e}\n{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        ))
    })?;
    if !output.status.success() {
        return Err(
            serde_json::from_value(value["error"].clone()).unwrap_or_else(|_| {
                Error::new("CLI_FAILED", "Команда завершилась ошибкой").detail(value.to_string())
            }),
        );
    }
    Ok(value)
}
fn reserve(runtime: &Runtime) -> Result<()> {
    let mut r = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние приложения недоступно"))?;
    if r.busy {
        return Err(Error::new(
            "OPERATION_BUSY",
            "Дождитесь завершения текущей операции",
        ));
    }
    r.busy = true;
    Ok(())
}
fn release(runtime: &Runtime) {
    if let Ok(mut r) = runtime.0.lock() {
        r.busy = false;
        r.stdin = None;
    }
}
#[tauri::command]
async fn release_info(app: tauri::AppHandle, path: Option<String>) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        // Load the selected package. Full integrity checks belong to explicit verify/build.
        let mut args = vec!["info".to_owned()];
        if let Some(path) = path.filter(|p| !p.trim().is_empty()) {
            // cli() must not append the previous selection a second time.
            let previous = handle.state::<Runtime>().0.lock().unwrap().payload.take();
            args.extend(["--payload".into(), path]);
            let value = query(&handle, &args);
            handle.state::<Runtime>().0.lock().unwrap().payload = previous;
            let value = value?;
            handle.state::<Runtime>().0.lock().unwrap().payload =
                value["payloadRoot"].as_str().map(PathBuf::from);
            Ok(value)
        } else {
            query(&handle, &args)
        }
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось проверить релиз").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
fn engineering_code(date: Option<String>) -> Result<engineering_menu::EngineeringCode> {
    engineering_menu::calculate(date.as_deref())
}
#[tauri::command]
async fn devices(app: tauri::AppHandle) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || query(&handle, &["devices".into()]))
        .await
        .map_err(|e| Error::new("WORKER", "Ошибка рабочего процесса").detail(e))
        .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
async fn plan(app: tauri::AppHandle, serial: String, action: String, dns: String) -> Result<Value> {
    if !["full", "light", "remove"].contains(&action.as_str())
        || !["keep", "on", "off"].contains(&dns.as_str())
    {
        return Err(Error::new("ARGUMENTS", "Неизвестное действие"));
    }
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        query(
            &handle,
            &[
                "plan".into(),
                "--device".into(),
                serial,
                "--action".into(),
                action,
                "--dns".into(),
                dns,
            ],
        )
    })
    .await
    .map_err(|e| Error::new("WORKER", "Ошибка рабочего процесса").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
async fn apply(app: tauri::AppHandle, request: Request) -> Result<()> {
    if !request.confirmed {
        return Err(Error::new("CONFIRMATION_REQUIRED", "Подтвердите план"));
    }
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || run_operation(&handle, request))
        .await
        .map_err(|e| Error::new("WORKER", "Ошибка рабочего процесса").detail(e))
        .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
fn run_operation(app: &tauri::AppHandle, request: Request) -> Result<()> {
    app.state::<Runtime>().0.lock().unwrap().operation_dir = None;
    let action = serde_json::to_value(request.action)?
        .as_str()
        .unwrap()
        .to_owned();
    let dns = serde_json::to_value(request.dns)?
        .as_str()
        .unwrap()
        .to_owned();
    let mut child = cli(
        app,
        &[
            "apply".into(),
            "--device".into(),
            request.serial,
            "--action".into(),
            action,
            "--dns".into(),
            dns,
            "--token".into(),
            request.inventory_token,
            "--yes".into(),
            "--interactive".into(),
        ],
    )?
    .stdin(Stdio::piped())
    .spawn()?;
    app.state::<Runtime>().0.lock().unwrap().stdin = child.stdin.take();
    let stderr = child.stderr.take().unwrap();
    let stderr = std::thread::spawn(move || {
        let mut s = String::new();
        let _ = stderr.take(1024 * 1024).read_to_string(&mut s);
        s
    });
    let mut last_error = None;
    for line in BufReader::new(child.stdout.take().unwrap()).lines() {
        match line {
            Ok(line) => match serde_json::from_str::<Value>(&line) {
                Ok(event) => {
                    let directory = event["data"]["logDirectory"]
                        .as_str()
                        .map(PathBuf::from)
                        .or_else(|| {
                            event["data"]["reportPath"]
                                .as_str()
                                .and_then(|p| PathBuf::from(p).parent().map(PathBuf::from))
                        });
                    if let Some(directory) = directory {
                        app.state::<Runtime>().0.lock().unwrap().operation_dir = Some(directory);
                    }
                    if event["type"] == "error" {
                        last_error = serde_json::from_value::<Error>(event["error"].clone()).ok();
                    }
                    if matches!(
                        event["type"].as_str(),
                        Some("operation-failed" | "operation-cancelled" | "operation-paused")
                    ) {
                        last_error = serde_json::from_value::<Error>(
                            event["data"]["report"]["error"].clone(),
                        )
                        .ok();
                    }
                    let _ = app.emit("installer-event", event);
                }
                Err(e) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(
                        Error::new("CLI_PROTOCOL", "Повреждён поток событий установщика").detail(e),
                    );
                }
            },
            Err(e) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(e.into());
            }
        }
    }
    let status = child.wait()?;
    let detail = stderr.join().unwrap_or_default();
    if status.code() == Some(130) && last_error.as_ref().is_some_and(|e| e.code == "CANCELLED") {
        return Ok(());
    }
    if !status.success() {
        return Err(last_error.unwrap_or_else(|| {
            Error::new("CLI_EXIT", "Исполняемый модуль неожиданно завершился")
                .detail(format!("{status}\n{detail}"))
        }));
    }
    Ok(())
}
#[tauri::command]
fn resolve_canbus_conflict(runtime: State<Runtime>, approved: bool) -> Result<()> {
    let mut state = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?;
    let stdin = state
        .stdin
        .as_mut()
        .ok_or_else(|| Error::new("NOT_RUNNING", "Операция уже завершена"))?;
    writeln!(
        stdin,
        "{}",
        serde_json::json!({"confirmRemoveVoyahHlService":approved})
    )?;
    stdin.flush()?;
    Ok(())
}
#[tauri::command]
fn cancel(runtime: State<Runtime>) -> Result<()> {
    let mut r = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?;
    if let Some(stdin) = r.stdin.as_mut() {
        stdin.write_all(b"{\"cancel\":true}\n")?;
        stdin.flush()?;
        return Ok(());
    }
    Err(Error::new(
        "NOT_RUNNING",
        "Операция ещё не запущена или уже завершена",
    ))
}
#[tauri::command]
fn operation_events(runtime: State<Runtime>) -> Result<Vec<Value>> {
    let directory = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?
        .operation_dir
        .clone();
    let Some(directory) = directory else {
        return Ok(Vec::new());
    };
    let mut events = std::collections::VecDeque::new();
    let mut milestones = Vec::new();
    for line in BufReader::new(fs::File::open(directory.join("events.jsonl"))?).lines() {
        if let Ok(event) = serde_json::from_str::<Value>(&line?) {
            let kind = event["type"].as_str().unwrap_or("");
            if kind.starts_with("step-") || kind.starts_with("operation-") {
                milestones.push(event);
            } else {
                events.push_back(event);
                if events.len() > 1500 {
                    events.pop_front();
                }
            }
        }
    }
    milestones.extend(events);
    milestones.sort_by_key(|event| event["sequence"].as_u64().unwrap_or(0));
    Ok(milestones)
}
#[tauri::command]
fn save_report(events: Vec<Value>, runtime: State<Runtime>) -> Result<String> {
    let dir = recovery::data_dir()?.join("exports");
    fs::create_dir_all(&dir)?;
    let path = dir.join(format!(
        "report-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
    ));
    fs::create_dir(&path)?;
    let operation_dir = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?
        .operation_dir
        .clone();
    if let Some(source) = &operation_dir {
        for name in [
            "events.jsonl",
            "plan.json",
            "report.json",
            "backup.json",
            "after.json",
            "receipt.json",
        ] {
            let file = source.join(name);
            if file.is_file() {
                fs::copy(file, path.join(name))?;
            }
        }
    }
    recovery::write_json(
        &path.join("gui.json"),
        &json!({"schema":1,"events":events,"originalOperationDirectory":operation_dir,"note":"Резервные копии APK и файлов остаются в исходной папке операции."}),
    )?;
    Ok(path.display().to_string())
}
fn main() {
    tauri::Builder::default()
        .manage(Runtime::default())
        .invoke_handler(tauri::generate_handler![
            engineering_code,
            release_info,
            devices,
            plan,
            apply,
            cancel,
            resolve_canbus_conflict,
            operation_events,
            save_report
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window
                    .state::<Runtime>()
                    .0
                    .lock()
                    .map(|r| r.busy)
                    .unwrap_or(true)
                {
                    api.prevent_close();
                    let _ = window.emit("installer-close-blocked", ());
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("Не удалось запустить VoyahTune Installer");
}
