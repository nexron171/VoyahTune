#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use installer_core::{
    adb::Adb,
    canbus::RemovalConsent,
    catalog::{Cache, Catalog, CatalogSettings},
    engine::{default_adb, Engine},
    engineering_menu,
    events::{Event, Events},
    payload::{self, Payload},
    plans::{Action, Dns, Plan, Request},
    recovery, session, Error, Result,
};
use serde_json::{json, Value};
use std::{
    fs,
    io::{BufRead, BufReader},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};
use tauri::{Emitter, Manager, State};

#[derive(Default)]
struct Running {
    busy: bool,
    cancel: Option<Arc<AtomicBool>>,
    consent: Option<Arc<RemovalConsent>>,
    operation_dir: Option<PathBuf>,
    payload: Option<PathBuf>,
    prepared: Option<(Plan, String, PathBuf)>,
    catalog: Option<Catalog>,
}
#[derive(Default)]
struct Runtime(Mutex<Running>);
fn bundle(app: &tauri::AppHandle) -> Result<PathBuf> {
    let resources = app
        .path()
        .resource_dir()
        .map_err(|e| Error::new("RESOURCES", "Не найдены ресурсы приложения").detail(e))?;
    let path = if cfg!(target_os = "linux") {
        resources
            .parent()
            .and_then(|p| p.parent())
            .ok_or_else(|| Error::new("RESOURCES", "Неверная структура Linux-пакета"))?
            .join("share/voyahtune-installer/bundle")
    } else {
        resources.join("bundle")
    };
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/bundle");
    Ok(
        if cfg!(debug_assertions) && !path.join("host-tools.json").exists() {
            dev
        } else {
            path
        },
    )
}
fn saved_recovery_root(serial: &str) -> Result<PathBuf> {
    if serial.is_empty() || serial.len() > 200 {
        return Err(Error::new("INVALID_SERIAL", "Некорректный автомобиль"));
    }
    // Match the operation directory identity; never use the serial as a path.
    let key: String = serial
        .as_bytes()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    Ok(recovery::data_dir()?
        .parent()
        .unwrap()
        .join("recovery")
        .join(key))
}
fn operation_payload(app: &tauri::AppHandle, serial: &str, action: Action) -> Result<Payload> {
    if action == Action::Remove {
        let saved = saved_recovery_root(serial)?;
        if let Ok(text) = fs::read_to_string(saved.join("current.json")) {
            let value: String = serde_json::from_str(&text).unwrap_or_default();
            if value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit()) {
                if let Ok(payload) = Payload::open(&saved.join(value)) {
                    return Ok(payload);
                }
            }
        }
    }
    let selected = app.state::<Runtime>().0.lock().unwrap().payload.clone();
    if let Some(root) = selected {
        return Payload::open(&root);
    }
    if action == Action::Remove {
        return Payload::open(&bundle(app)?.join("recovery"));
    }
    Err(Error::new(
        "PAYLOAD_REQUIRED",
        "Выберите и скачайте релиз или откройте локальный ZIP",
    ))
}
fn select_payload(app: &tauri::AppHandle, payload: Payload) -> Result<Value> {
    let runtime = app.state::<Runtime>();
    let mut state = runtime.0.lock().unwrap();
    if let Some(release) = state.catalog.as_ref().and_then(|catalog| {
        catalog
            .releases
            .iter()
            .find(|release| release.version == payload.manifest.release_version)
    }) {
        release.require_installer_version(env!("CARGO_PKG_VERSION"))?;
    }
    state.payload = Some(payload.root.clone());
    state.prepared = None;
    Ok(
        json!({"manifest":payload.manifest,"payloadRoot":payload.root,"toolingVersion":env!("CARGO_PKG_VERSION")}),
    )
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
    r.cancel = Some(Arc::new(AtomicBool::new(false)));
    Ok(())
}
fn operation_busy(runtime: &Runtime) -> bool {
    runtime.0.lock().map(|r| r.busy).unwrap_or(true)
}
fn release(runtime: &Runtime) {
    if let Ok(mut r) = runtime.0.lock() {
        r.busy = false;
        r.cancel = None;
        r.consent = None;
    }
}
#[tauri::command]
async fn release_info(app: tauri::AppHandle, path: Option<String>) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let path = path
            .filter(|p| !p.trim().is_empty())
            .ok_or_else(|| Error::new("PAYLOAD_REQUIRED", "Укажите ZIP или папку релиза"))?;
        let path = PathBuf::from(path);
        let cancel = handle
            .state::<Runtime>()
            .0
            .lock()
            .unwrap()
            .cancel
            .clone()
            .unwrap();
        let payload = if path
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("zip"))
        {
            Cache::user()?.import(&path, &cancel, &|event| {
                let _ = handle.emit("payload-progress", event);
            })?
        } else {
            let root = if path.is_file() {
                path.parent().unwrap()
            } else {
                &path
            };
            Payload::open(root)?
        };
        if payload.manifest.removal_only {
            return Err(Error::new("PAYLOAD_REQUIRED", "Выбран ресурсы удаления"));
        }
        select_payload(&handle, payload)
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось проверить релиз").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
fn catalog_settings() -> Result<CatalogSettings> {
    Cache::user()?.catalog_settings()
}

#[tauri::command]
fn save_catalog_url(runtime: State<Runtime>, url: String) -> Result<CatalogSettings> {
    reserve(&runtime)?;
    let result = Cache::user().and_then(|cache| cache.save_catalog_url(&url));
    if result.is_ok() {
        runtime.0.lock().unwrap().catalog = None;
    }
    release(&runtime);
    result
}

#[tauri::command]
async fn release_catalog(app: tauri::AppHandle, refresh: bool) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let state = Cache::user()?.state(refresh)?;
        handle.state::<Runtime>().0.lock().unwrap().catalog = Some(state.catalog.clone());
        let mut value = serde_json::to_value(&state)?;
        if let Ok(embedded) = Payload::load(&bundle(&handle)?.join("payload")) {
            value["cached"]
                .as_array_mut()
                .unwrap()
                .push(json!({"version":embedded.manifest.release_version,"path":embedded.root}));
        }
        for (release, entry) in state
            .catalog
            .releases
            .iter()
            .zip(value["catalog"]["releases"].as_array_mut().unwrap())
        {
            if let Err(error) = release.require_installer_version(env!("CARGO_PKG_VERSION")) {
                entry["compatible"] = json!(false);
                entry["incompatibility"] = json!(error.message);
            }
        }
        Ok(value)
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось получить каталог").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
async fn open_release_link(app: tauri::AppHandle, url: String) -> Result<()> {
    let allowed = app
        .state::<Runtime>()
        .0
        .lock()
        .unwrap()
        .catalog
        .as_ref()
        .is_some_and(|c| {
            c.releases.iter().any(|r| r.notes_url == url)
                || c.installer_downloads.iter().any(|i| i.url == url)
        });
    if !allowed {
        return Err(Error::new(
            "LINK_INVALID",
            "Ссылка отсутствует в проверенном каталоге",
        ));
    }
    tauri::async_runtime::spawn_blocking(move || {
        #[cfg(target_os = "macos")]
        let result = std::process::Command::new("open").arg(&url).status();
        #[cfg(target_os = "linux")]
        let result = std::process::Command::new("xdg-open").arg(&url).status();
        #[cfg(windows)]
        let result = std::process::Command::new("rundll32.exe")
            .args(["url.dll,FileProtocolHandler", &url])
            .status();
        if !result?.success() {
            return Err(Error::new(
                "LINK_OPEN",
                "Не удалось открыть ссылку в браузере",
            ));
        }
        Ok(())
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось открыть браузер").detail(e))?
}
#[tauri::command]
async fn download_payload(app: tauri::AppHandle, version: String) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let (release, cancel) = {
            let runtime = handle.state::<Runtime>();
            let state = runtime.0.lock().unwrap();
            let entry = state
                .catalog
                .as_ref()
                .and_then(|c| c.releases.iter().find(|r| r.version == version))
                .cloned()
                .ok_or_else(|| Error::new("RELEASE_MISSING", "Обновите список и выберите релиз"))?;
            (entry, state.cancel.clone().unwrap())
        };
        let payload = Cache::user()?.download(&release, &cancel, &|event| {
            let _ = handle.emit("payload-progress", event);
        })?;
        select_payload(&handle, payload)
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось скачать релиз").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
async fn delete_payload(app: tauri::AppHandle, path: String) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let path = PathBuf::from(path);
        let canonical = path.canonicalize()?;
        Cache::user()?.remove(&path)?;
        let runtime = handle.state::<Runtime>();
        let mut state = runtime.0.lock().unwrap();
        let deselected = state.payload.as_ref() == Some(&canonical);
        if deselected {
            state.payload = None;
            state.prepared = None;
        }
        Ok(json!({"deselected":deselected}))
    })
    .await
    .map_err(|e| Error::new("WORKER", "Не удалось удалить скачанный релиз").detail(e))
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
    let result = tauri::async_runtime::spawn_blocking(move || {
        let devices = Adb::new(default_adb(&bundle(&handle)?), Events::quiet())?.devices()?;
        Ok(json!({"devices":devices}))
    })
    .await
    .map_err(|e| Error::new("WORKER", "Ошибка рабочего процесса").detail(e))
    .and_then(|v| v);
    release(&app.state::<Runtime>());
    result
}
#[tauri::command]
async fn plan(app: tauri::AppHandle, serial: String, action: Action, dns: Dns) -> Result<Value> {
    reserve(&app.state::<Runtime>())?;
    let handle = app.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let payload = operation_payload(&handle, &serial, action)?;
        let plan = session::plan(
            &default_adb(&bundle(&handle)?),
            &payload,
            &serial,
            action,
            dns,
        )?;
        let digest = payload::sha256(&payload.root.join("manifest.json"))?;
        handle.state::<Runtime>().0.lock().unwrap().prepared =
            Some((plan.clone(), digest, payload.root.clone()));
        Ok(serde_json::to_value(plan)?)
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
    app.state::<Runtime>().0.lock().unwrap().prepared = None;
    release(&app.state::<Runtime>());
    result
}
fn run_operation(app: &tauri::AppHandle, request: Request) -> Result<()> {
    let (mut plan, digest, root) = app
        .state::<Runtime>()
        .0
        .lock()
        .unwrap()
        .prepared
        .clone()
        .ok_or_else(|| Error::new("PLAN_REQUIRED", "Сначала проверьте автомобиль и план"))?;
    if plan.request.serial != request.serial || plan.request.action != request.action {
        return Err(Error::new(
            "PLAN_CHANGED",
            "Выбор автомобиля или действия изменился. Постройте план заново.",
        ));
    }
    // A second GUI must not delete cached APKs halfway through an installation.
    let cache = Cache::user()?;
    let _cache_guard = if root.starts_with(cache.root.join("payloads")) {
        Some(cache.retain_for_operation()?)
    } else {
        None
    };
    let payload = Payload::open(&root)?;
    if payload::sha256(&payload.root.join("manifest.json"))? != digest {
        return Err(Error::new(
            "PAYLOAD_CHANGED",
            "Релиз изменился. Постройте план заново.",
        ));
    }
    // DNS is an explicit choice on the review page, after initial diagnosis.
    plan.request = request.clone();
    let cancel = app
        .state::<Runtime>()
        .0
        .lock()
        .unwrap()
        .cancel
        .clone()
        .unwrap();
    let consent = Arc::new(RemovalConsent::new(false));
    app.state::<Runtime>().0.lock().unwrap().consent = Some(consent.clone());
    let handle = app.clone();
    let output_cancel = cancel.clone();
    let callback = Arc::new(move |event: &Event| {
        if handle.emit("installer-event", event).is_err() {
            output_cancel.store(true, Ordering::Relaxed);
        }
    });
    if request.action != Action::Remove {
        let recovery_root = saved_recovery_root(&request.serial)?;
        payload.save_removal(&recovery_root.join(&digest))?;
        fs::create_dir_all(&recovery_root)?;
        // Retain the cleanup recipe before mutation, including interrupted installs.
        recovery::write_json(&recovery_root.join("current.json"), &digest)?;
    }
    let mut engine = Engine::new(
        &default_adb(&bundle(app)?),
        payload,
        &request.serial,
        &recovery::data_dir()?,
        callback,
        cancel,
    )?;
    app.state::<Runtime>().0.lock().unwrap().operation_dir = Some(engine.operation.dir.clone());
    recovery::write_json(&engine.operation.dir.join("plan.json"), &plan)?;
    engine.canbus_consent = Some(consent);
    match engine.run(request) {
        Err(e) if e.code == "CANCELLED" => Ok(()),
        result => result,
    }
}
#[tauri::command]
fn resolve_canbus_conflict(runtime: State<Runtime>, approved: bool) -> Result<()> {
    let state = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?;
    state
        .consent
        .as_ref()
        .ok_or_else(|| Error::new("NOT_RUNNING", "Операция уже завершена"))?
        .answer(approved);
    Ok(())
}
#[tauri::command]
fn cancel(runtime: State<Runtime>) -> Result<()> {
    let state = runtime
        .0
        .lock()
        .map_err(|_| Error::new("DESKTOP_LOCK", "Состояние недоступно"))?;
    state
        .cancel
        .as_ref()
        .ok_or_else(|| Error::new("NOT_RUNNING", "Операция уже завершена"))?
        .store(true, Ordering::Relaxed);
    Ok(())
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
        &json!({"schema":1,"events":events,"originalOperationDirectory":operation_dir,"note":"Резервные копии файлов остаются в исходной папке операции."}),
    )?;
    Ok(path.display().to_string())
}
fn main() {
    tauri::Builder::default()
        .manage(Runtime::default())
        .invoke_handler(tauri::generate_handler![
            engineering_code,
            release_catalog,
            catalog_settings,
            save_catalog_url,
            download_payload,
            delete_payload,
            open_release_link,
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
                if operation_busy(&window.state::<Runtime>()) {
                    api.prevent_close();
                    let _ = window.emit("installer-close-blocked", ());
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("Не удалось запустить VoyahTune Installer")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                if operation_busy(&app.state::<Runtime>()) {
                    api.prevent_exit();
                    let _ = app.emit("installer-close-blocked", ());
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn busy_cancel_and_consent_are_released_between_operations() {
        let runtime = Runtime::default();
        reserve(&runtime).unwrap();
        assert!(operation_busy(&runtime));
        assert_eq!(reserve(&runtime).unwrap_err().code, "OPERATION_BUSY");
        let cancel = runtime.0.lock().unwrap().cancel.clone().unwrap();
        cancel.store(true, Ordering::Relaxed);
        runtime.0.lock().unwrap().consent = Some(Arc::new(RemovalConsent::new(false)));
        release(&runtime);
        assert!(runtime.0.lock().unwrap().consent.is_none());
        reserve(&runtime).unwrap();
        assert!(!runtime
            .0
            .lock()
            .unwrap()
            .cancel
            .as_ref()
            .unwrap()
            .load(Ordering::Relaxed));
        assert!(cancel.load(Ordering::Relaxed));
        release(&runtime);
        assert!(!runtime.0.lock().unwrap().busy);
    }
}
