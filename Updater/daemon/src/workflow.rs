use crate::{
    config::{self, invalid, Config},
    device, network,
    state::{self, State},
};
use release_core::{catalog::Release, ota, payload::Payload};
mod catalog_selection;
#[cfg(test)]
mod tests;
use std::{
    fs, io,
    path::Path,
    sync::{mpsc, Arc, Mutex},
    thread,
    time::Duration,
};
#[derive(Clone, Copy)]
pub enum Job {
    Check(bool),
    Download,
    Apply,
}
pub struct Runtime {
    pub config: Result<Config, String>,
    pub state: State,
}

pub type Shared = Arc<Mutex<Runtime>>;

fn root() -> &'static Path {
    Path::new(crate::ROOT)
}

pub fn update(shared: &Shared, change: impl FnOnce(&mut State)) -> io::Result<()> {
    let mut runtime = shared.lock().unwrap();
    change(&mut runtime.state);
    state::save(root(), "state.json", &runtime.state)
}

fn snapshot(shared: &Shared) -> State {
    shared.lock().unwrap().state.clone()
}

pub fn phase(shared: &Shared, name: &str, step: &str) -> io::Result<()> {
    crate::log(root(), &format!("phase={name} {step}"))?;
    update(shared, |state| {
        state.phase = name.into();
        state.step = step.into();
        state.error = None;
        state.bytes = 0;
        state.total = 0;
    })
}

pub(crate) fn fail(shared: &Shared, error: &io::Error, repair: bool) {
    let message = error.to_string();
    let _ = crate::log(root(), &format!("FAILED {message}"));
    let _ = update(shared, |state| state.fail(message.clone(), repair));
    crate::telemetry::finish(Some(&message));
}

pub fn start() -> io::Result<(Shared, mpsc::Sender<Job>)> {
    let config = config::load(root()).map_err(|error| error.to_string());
    let stored = root().join("state.json");
    let mut state = if stored.exists() {
        match state::read::<State>(&stored) {
            Ok(state) => state,
            Err(error) => {
                fs::copy(&stored, root().join("state.corrupt.json"))?;
                let mut broken = State::fresh("0.0.0".into(), "unknown".into());
                broken.phase = "repair-required".into();
                broken.error = Some(error.to_string());
                broken.notice = Some("error".into());
                broken
            }
        }
    } else {
        let boot: serde_json::Value =
            state::read(Path::new("/system/etc/voyahtune-ota-bootstrap.json"))?;
        State::fresh(
            boot["version"]
                .as_str()
                .ok_or_else(|| invalid("Нет версии bootstrap"))?
                .into(),
            device::prop("ro.build.fingerprint")?,
        )
    };
    if state.schema != 1 {
        return Err(invalid("Неизвестный формат OTA journal"));
    }

    crate::telemetry::initialize(root())?;
    crate::telemetry::resume();
    state.recover_interrupted(&device::boot());
    if state.phase == "committed" {
        crate::telemetry::finish(None);
    } else if state.error.is_some() {
        crate::telemetry::finish(state.error.as_deref());
    }
    if let Ok(config) = &config {
        if state.source_generation != config.source_generation && !state.repair() {
            state.clear_catalog();
            state.selected = None;
            state.phase = "idle".into();
            state.source_generation = config.source_generation;
        }
    }
    state::save(root(), "state.json", &state)?;
    let shared = Arc::new(Mutex::new(Runtime { config, state }));
    let (sender, receiver) = mpsc::channel();
    let worker = shared.clone();
    thread::spawn(move || run(worker, receiver));
    Ok((shared, sender))
}

pub fn queue(shared: &Shared, sender: &mpsc::Sender<Job>, job: Job) -> io::Result<()> {
    let mut runtime = shared.lock().unwrap();
    if runtime.state.busy() || runtime.state.repair() {
        return Err(invalid(
            "Обновление занято или требуется USB-восстановление",
        ));
    }
    match job {
        Job::Download if runtime.state.selected.is_none() => {
            return Err(invalid("Сначала проверьте наличие релиза"))
        }
        Job::Apply if runtime.state.phase != "verified" => {
            return Err(invalid("Сначала скачайте и распакуйте релиз"))
        }
        _ => {}
    }
    runtime.config.as_ref().map_err(|error| invalid(error))?;
    if matches!(job, Job::Download | Job::Apply) {
        let config = runtime.config.as_ref().map_err(|error| invalid(error))?;
        let release = runtime
            .state
            .selected
            .as_ref()
            .ok_or_else(|| invalid("Нет выбранного релиза"))?;
        let dns = match config.dns_enabled {
            Some(true) => "on",
            Some(false) => "off",
            None => "keep",
        };
        crate::telemetry::begin(
            root(),
            serde_json::json!({"version":release.version,"infrastructure":release_core::infrastructure::Infrastructure::compiled()}),
            dns,
        )?;
        crate::telemetry::record(
            "user_choice",
            if matches!(job, Job::Apply) {
                "install.confirmed"
            } else {
                "download.confirmed"
            },
            serde_json::json!({"dns":dns}),
        );
    } else {
        crate::telemetry::finish(Some("Пользователь начал новую проверку каталога"));
    }
    // Set busy before acknowledging: double taps cannot enqueue parallel work.
    runtime.state.phase = match job {
        Job::Check(_) => "checking",
        Job::Download => "downloading",
        Job::Apply => "preparing",
    }
    .into();
    runtime.state.error = None;
    runtime.state.completed_steps = 0;
    runtime.state.total_steps = 0;
    runtime.state.notice = None;
    runtime.state.step = match job {
        Job::Check(_) => "Проверка каталога релизов",
        Job::Download => "Скачивание архива",
        Job::Apply => "Проверка условий установки",
    }
    .into();
    state::save(root(), "state.json", &runtime.state)?;
    sender
        .send(job)
        .map_err(|_| invalid("Исполнитель остановлен"))
}

fn run(shared: Shared, receiver: mpsc::Receiver<Job>) {
    let state = snapshot(&shared);
    if state.phase == "signature-reboot-pending" {
        if let Err(error) = crate::install::resume_signature_change(&shared) {
            fail(&shared, &error, true);
        }
    } else if state.phase == "reboot-pending" || state.phase == "validating" {
        if let Err(error) = crate::install::validate(&shared) {
            fail(&shared, &error, true);
        }
    }
    loop {
        match receiver.recv_timeout(Duration::from_secs(15)) {
            Ok(job) => {
                let apply = matches!(job, Job::Apply);
                let result = match job {
                    Job::Check(same) => check(&shared, same, false),
                    Job::Download => download(&shared),
                    Job::Apply => crate::install::apply(&shared),
                };
                let _ = device::wake(false);
                if let Err(error) = result {
                    let repair = apply && snapshot(&shared).installation_started();
                    fail(&shared, &error, repair);
                }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                let state = snapshot(&shared);
                if !state.busy()
                    && !state.repair()
                    && !crate::telemetry::active()
                    && shared.lock().unwrap().config.is_ok()
                    && matches!(device::prop("sys.boot_completed").as_deref(), Ok("1"))
                    && state::due(&state, device::wall(), &device::boot(), device::uptime())
                {
                    let reserved = {
                        let mut runtime = shared.lock().unwrap();
                        if runtime.state.busy() || runtime.state.repair() {
                            false
                        } else {
                            runtime.state.phase = "checking".into();
                            true
                        }
                    };
                    if !reserved {
                        continue;
                    }
                    if let Err(error) = check(&shared, false, true) {
                        let _ = crate::log(root(), &format!("automatic_check_failed {error}"));
                        let _ = update(&shared, |state| {
                            state.phase = "idle".into();
                            state.error = Some(error.to_string());
                        });
                    }
                }
            }
        }
        let state = snapshot(&shared);
        if state.notice.is_some()
            && !state.notice_opened
            && matches!(device::prop("sys.boot_completed").as_deref(), Ok("1"))
        {
            if device::open_ui().is_ok() {
                let _ = update(&shared, |state| state.notice_opened = true);
            }
        }
    }
}

fn selected(shared: &Shared) -> io::Result<(Release, ota::Claims)> {
    let state = snapshot(shared);
    let generation = shared
        .lock()
        .unwrap()
        .config
        .as_ref()
        .map_err(|error| invalid(error))?
        .source_generation;
    if state.source_generation != generation {
        return Err(invalid("Релиз выбран из прежнего источника"));
    }
    let release = state
        .selected
        .ok_or_else(|| invalid("Нет выбранного релиза"))?;
    let claims = catalog_selection::verify_selection(&release, &state.installed_version)
        .map_err(|error| invalid(&error.to_string()))?;
    Ok((release, claims))
}

fn check(shared: &Shared, same: bool, automatic: bool) -> io::Result<()> {
    let (url, generation) = {
        let runtime = shared.lock().unwrap();
        let config = runtime.config.as_ref().map_err(|error| invalid(error))?;
        (config.catalog_url.clone(), config.source_generation)
    };
    if automatic {
        update(shared, |state| {
            state.last_auto_wall = device::wall();
            state.last_auto_boot = device::boot();
            state.last_auto_uptime = device::uptime();
        })?;
    }
    if url.is_empty() {
        return Err(invalid(
            "Адрес каталога не настроен. Укажите HTTPS адрес каталога обновлений.",
        ));
    }
    update(shared, State::clear_catalog)?;
    phase(shared, "checking", "Проверка каталога релизов")?;

    let catalog = network::catalog(&url)?;
    let current = snapshot(shared);

    let mut selection = catalog_selection::choose(&catalog, &current.installed_version, same);
    if !automatic && !same && matches!(&selection, Ok(None)) {
        selection = catalog_selection::choose(&catalog, &current.installed_version, true);
    }
    let selection_error = selection.as_ref().err().map(ToString::to_string);
    let mut runtime = shared.lock().unwrap();
    if runtime
        .config
        .as_ref()
        .map_err(|error| invalid(error))?
        .source_generation
        != generation
    {
        return Err(invalid("Источник каталога изменён"));
    }
    {
        runtime.state.catalog_versions = Some(
            catalog
                .releases
                .iter()
                .map(|release| release.version.clone())
                .collect(),
        );
    }
    {
        state::save(root(), "catalog.json", &catalog)?;
    }
    runtime.state.source_generation = generation;
    runtime.state.same_version = selection
        .as_ref()
        .ok()
        .and_then(|release| release.as_ref())
        .is_some_and(|release| {
            catalog_selection::same_release_version(
                &release.version,
                &runtime.state.installed_version,
            )
        });
    runtime.state.selected = selection.ok().flatten();
    runtime.state.phase = "idle".into();
    runtime.state.error = None;
    runtime.state.step = if runtime.state.selected.is_some() {
        "Релиз доступен для скачивания"
    } else {
        "Новых совместимых релизов нет"
    }
    .into();
    if automatic {
        if let Some(release) = &runtime.state.selected {
            if catalog_selection::is_newer_release_version(
                &release.version,
                &runtime.state.installed_version,
            ) {
                runtime.state.notice = Some(release.version.clone());
                runtime.state.notice_opened = false;
            }
        }
    }
    state::save(root(), "state.json", &runtime.state)?;
    drop(runtime);
    if let Some(error) = selection_error {
        return Err(invalid(&error));
    }
    Ok(())
}

fn download(shared: &Shared) -> io::Result<()> {
    let (release, _) = selected(shared)?;
    device::wake(true)?;
    phase(shared, "downloading", "Скачивание архива")?;

    let archive = root().join("release.zip");
    let partial = root().join("release.part");
    for path in [&archive, &partial] {
        if path.exists() {
            fs::remove_file(path)?;
        }
    }
    if root().join("staging").exists() {
        fs::remove_dir_all(root().join("staging"))?;
    }
    network::download(&release.payload.url, &partial, |processed_bytes| {
        let _ = update(shared, |state| {
            state.bytes = processed_bytes;
            state.total = release.payload.size;
        });
    })?;
    fs::rename(&partial, &archive)?;
    phase(shared, "verifying", "Распаковка релиза")?;

    network::extract(
        &archive,
        &root().join("staging"),
        |processed_bytes, total| {
            let _ = update(shared, |state| {
                state.bytes = processed_bytes;
                state.total = total;
            });
        },
    )?;
    crate::release::payload(&root().join("staging"))?;
    phase(
        shared,
        "verified",
        "Релиз распакован. Можно начать установку",
    )
}

pub fn select_release(shared: &Shared, version: &str) -> io::Result<()> {
    let mut runtime = shared.lock().unwrap();
    if runtime.state.busy() || runtime.state.repair() {
        return Err(invalid(
            "Обновление занято или требуется USB-восстановление",
        ));
    }
    let catalog: release_core::catalog::Catalog = state::read(&root().join("catalog.json"))?;
    let generation = runtime
        .config
        .as_ref()
        .map_err(|error| invalid(error))?
        .source_generation;
    if generation != runtime.state.source_generation || runtime.state.catalog_versions.is_none() {
        return Err(invalid("Обновите каталог перед выбором релиза"));
    }
    let release = catalog
        .releases
        .into_iter()
        .find(|release| release.version == version)
        .ok_or_else(|| invalid("Релиз отсутствует в текущем каталоге"))?;
    catalog_selection::verify_selection(&release, &runtime.state.installed_version)
        .map_err(|error| invalid(&error.to_string()))?;
    crate::telemetry::finish(Some("Пользователь изменил выбранный релиз"));
    runtime.state.same_version =
        catalog_selection::same_release_version(version, &runtime.state.installed_version);
    runtime.state.selected = Some(release);
    runtime.state.phase = "idle".into();
    runtime.state.step = "Релиз выбран. Можно скачать архив".into();
    runtime.state.error = None;
    runtime.state.bytes = 0;
    runtime.state.total = 0;
    state::save(root(), "state.json", &runtime.state)
}

pub fn verified(shared: &Shared) -> io::Result<(Payload, ota::Claims)> {
    let (_, claims) = selected(shared)?;
    let payload = crate::release::payload(&root().join("staging"))?;
    Ok((payload, claims))
}
