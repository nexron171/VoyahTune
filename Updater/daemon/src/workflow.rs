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
    let mut rt = shared.lock().unwrap();
    change(&mut rt.state);
    state::save(root(), "state.json", &rt.state)
}
fn snapshot(shared: &Shared) -> State {
    shared.lock().unwrap().state.clone()
}
pub fn phase(shared: &Shared, name: &str, step: &str) -> io::Result<()> {
    crate::log(root(), &format!("phase={name} {step}"))?;
    update(shared, |s| {
        s.phase = name.into();
        s.step = step.into();
        s.error = None;
        s.bytes = 0;
        s.total = 0;
    })
}
fn fail(shared: &Shared, error: &io::Error, repair: bool) {
    let message = error.to_string();
    let _ = crate::log(root(), &format!("FAILED {message}"));
    let _ = update(shared, |s| s.fail(message.clone(), repair));
    crate::telemetry::finish(Some(&message));
}
pub fn start() -> io::Result<(Shared, mpsc::Sender<Job>)> {
    let config = config::load(root()).map_err(|e| e.to_string());
    let stored = root().join("state.json");
    let mut state = if stored.exists() {
        match state::read::<State>(&stored) {
            Ok(state) => state,
            Err(e) => {
                fs::copy(&stored, root().join("state.corrupt.json"))?;
                let mut broken = State::fresh("0.0.0".into(), "unknown".into());
                broken.phase = "repair-required".into();
                broken.error = Some(e.to_string());
                broken.notice = Some("error".into());
                broken
            }
        }
    } else {
        let boot: serde_json::Value =
            state::read(Path::new("/system/etc/voyahtune-ota-bootstrap.json"))?;
        let infrastructure: release_core::infrastructure::Infrastructure = serde_json::from_value(
            boot.get("infrastructure")
                .cloned()
                .unwrap_or(serde_json::json!("od")),
        )
        .map_err(|e| invalid(&e.to_string()))?;
        infrastructure
            .require(release_core::infrastructure::Infrastructure::compiled())
            .map_err(|e| invalid(&e.to_string()))?;
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
    if state.fingerprint.is_empty() {
        return Err(invalid("Не определена прошивка ГУ"));
    }
    crate::telemetry::initialize(root())?;
    crate::telemetry::resume();
    state.recover_interrupted(&device::boot());
    if state.phase == "committed" {
        crate::telemetry::finish(None);
    } else if state.error.is_some() {
        crate::telemetry::finish(state.error.as_deref());
    }
    if let Ok(c) = &config {
        if state.source_generation != c.source_generation && !state.repair() {
            state.clear_od_catalog();
            state.selected = None;
            state.phase = "idle".into();
            state.source_generation = c.source_generation;
        }
    }
    state::save(root(), "state.json", &state)?;
    let shared = Arc::new(Mutex::new(Runtime { config, state }));
    let (tx, rx) = mpsc::channel();
    let worker = shared.clone();
    thread::spawn(move || run(worker, rx));
    Ok((shared, tx))
}
pub fn queue(shared: &Shared, tx: &mpsc::Sender<Job>, job: Job) -> io::Result<()> {
    let mut rt = shared.lock().unwrap();
    if rt.state.busy() || rt.state.repair() {
        return Err(invalid(
            "Обновление занято или требуется USB-восстановление",
        ));
    }
    match job {
        Job::Download if rt.state.selected.is_none() => {
            return Err(invalid("Сначала проверьте наличие релиза"))
        }
        Job::Apply if rt.state.phase != "verified" => return Err(invalid("Релиз ещё не проверен")),
        _ => {}
    }
    rt.config.as_ref().map_err(|e| invalid(e))?;
    if matches!(job, Job::Download | Job::Apply) {
        let config = rt.config.as_ref().map_err(|error| invalid(error))?;
        let release = rt
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
            serde_json::json!({"version":release.version,"infrastructure":"od"}),
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
    rt.state.phase = match job {
        Job::Check(_) => "checking",
        Job::Download => "downloading",
        Job::Apply => "preparing",
    }
    .into();
    rt.state.error = None;
    rt.state.completed_steps = 0;
    rt.state.total_steps = 0;
    rt.state.notice = None;
    rt.state.step = match job {
        Job::Check(_) => "Проверка каталога релизов",
        Job::Download => "Скачивание архива",
        Job::Apply => "Проверка условий установки",
    }
    .into();
    state::save(root(), "state.json", &rt.state)?;
    tx.send(job).map_err(|_| invalid("Исполнитель остановлен"))
}
fn run(shared: Shared, rx: mpsc::Receiver<Job>) {
    let s = snapshot(&shared);
    if s.phase == "reboot-pending" || s.phase == "validating" {
        if let Err(e) = crate::install::validate(&shared) {
            fail(&shared, &e, true);
        }
    }
    loop {
        match rx.recv_timeout(Duration::from_secs(15)) {
            Ok(job) => {
                let apply = matches!(job, Job::Apply);
                let result = match job {
                    Job::Check(same) => check(&shared, same, false),
                    Job::Download => download(&shared),
                    Job::Apply => crate::install::apply(&shared),
                };
                let _ = device::wake(false);
                if let Err(e) = result {
                    let repair = apply && snapshot(&shared).installation_started();
                    fail(&shared, &e, repair);
                }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                let s = snapshot(&shared);
                if !s.busy()
                    && !s.repair()
                    && !crate::telemetry::active()
                    && shared.lock().unwrap().config.is_ok()
                    && matches!(device::prop("sys.boot_completed").as_deref(), Ok("1"))
                    && state::due(&s, device::wall(), &device::boot(), device::uptime())
                {
                    let reserved = {
                        let mut rt = shared.lock().unwrap();
                        if rt.state.busy() || rt.state.repair() {
                            false
                        } else {
                            rt.state.phase = "checking".into();
                            true
                        }
                    };
                    if !reserved {
                        continue;
                    }
                    if let Err(e) = check(&shared, false, true) {
                        let _ = crate::log(root(), &format!("automatic_check_failed {e}"));
                        let _ = update(&shared, |s| {
                            s.phase = "idle".into();
                            s.error = Some(e.to_string());
                        });
                    }
                }
            }
        }
        let s = snapshot(&shared);
        if s.notice.is_some()
            && !s.notice_opened
            && matches!(device::prop("sys.boot_completed").as_deref(), Ok("1"))
        {
            if device::open_ui().is_ok() {
                let _ = update(&shared, |s| s.notice_opened = true);
            }
        }
    }
}
fn selected(shared: &Shared) -> io::Result<(Release, ota::Claims)> {
    let s = snapshot(shared);
    let generation = shared
        .lock()
        .unwrap()
        .config
        .as_ref()
        .map_err(|e| invalid(e))?
        .source_generation;
    if s.source_generation != generation {
        return Err(invalid("Релиз выбран из прежнего источника"));
    }
    let r = s.selected.ok_or_else(|| invalid("Нет выбранного релиза"))?;
    for version in [&r.version, &s.installed_version] {
        release_core::infrastructure::Infrastructure::from_version(version)
            .and_then(|i| i.require(release_core::infrastructure::Infrastructure::compiled()))
            .map_err(|e| invalid(&e.to_string()))?;
    }
    let c = ota::verify(&r).map_err(|e| invalid(&e.to_string()))?;
    if !crate::restore_ui::enabled()
        && c.version == s.installed_version
        && !s.installed_archive_sha256.is_empty()
        && c.archive_sha256 != s.installed_archive_sha256
    {
        return Err(invalid(
            "Повторная установка разрешена только для того же архива",
        ));
    }
    if crate::restore_ui::enabled() {
        catalog_selection::verify_selection(
            &r,
            &s.installed_version,
            s.same_version,
            release_core::infrastructure::Infrastructure::Od,
        )
        .map_err(|error| invalid(&error.to_string()))?;
    } else {
        ota::compatible(&c, &s.installed_version, s.same_version)
            .map_err(|e| invalid(&e.to_string()))?;
    }
    if device::prop("ro.build.fingerprint")? != s.fingerprint {
        return Err(invalid(
            "Прошивка ГУ изменилась: установите VoyahTune через USB",
        ));
    }
    Ok((r, c))
}
fn check(shared: &Shared, same: bool, automatic: bool) -> io::Result<()> {
    let (url, generation) = {
        let rt = shared.lock().unwrap();
        let c = rt.config.as_ref().map_err(|e| invalid(e))?;
        (c.catalog_url.clone(), c.source_generation)
    };
    if automatic {
        update(shared, |s| {
            s.last_auto_wall = device::wall();
            s.last_auto_boot = device::boot();
            s.last_auto_uptime = device::uptime();
        })?;
    }
    if url.is_empty() {
        return Err(invalid("Адрес каталога не настроен. Укажите HTTPS адрес каталога для установленной инфраструктуры."));
    }
    update(shared, State::clear_od_catalog)?;
    phase(shared, "checking", "Проверка каталога релизов")?;
    if device::prop("ro.build.fingerprint")? != snapshot(shared).fingerprint {
        return Err(invalid("Прошивка ГУ изменилась. Требуется USB-установка"));
    }
    let catalog = network::catalog(&url)?;
    let current = snapshot(shared);
    release_core::infrastructure::Infrastructure::from_version(&current.installed_version)
        .and_then(|i| i.require(release_core::infrastructure::Infrastructure::compiled()))
        .map_err(|e| invalid(&e.to_string()))?;
    let mut rejected = None;
    let mut selection = catalog_selection::choose(
        &catalog,
        &current.installed_version,
        same,
        release_core::infrastructure::Infrastructure::compiled(),
        |error| rejected = Some(error.to_string()),
    );
    if crate::restore_ui::enabled() && !automatic && !same && matches!(&selection, Ok(None)) {
        selection = catalog_selection::choose(
            &catalog,
            &current.installed_version,
            true,
            release_core::infrastructure::Infrastructure::Od,
            |error| rejected = Some(error.to_string()),
        );
    }
    let selection_error = selection.as_ref().err().map(ToString::to_string);
    let mut rt = shared.lock().unwrap();
    if rt
        .config
        .as_ref()
        .map_err(|e| invalid(e))?
        .source_generation
        != generation
    {
        return Err(invalid("Источник каталога изменён"));
    }
    if crate::restore_ui::enabled() {
        rt.state.catalog_versions = Some(
            catalog
                .releases
                .iter()
                .filter(|release| {
                    release_core::infrastructure::Infrastructure::from_version(&release.version)
                        .is_ok_and(|profile| {
                            profile == release_core::infrastructure::Infrastructure::Od
                        })
                        && ota::verify(release)
                            .and_then(|claims| {
                                ota::compatible(&claims, &current.installed_version, true)
                            })
                            .is_ok()
                })
                .map(|release| release.version.clone())
                .collect(),
        );
    }
    if crate::restore_ui::enabled() {
        state::save(root(), "catalog.json", &catalog)?;
    }
    rt.state.source_generation = generation;
    rt.state.same_version = selection
        .as_ref()
        .ok()
        .and_then(|release| release.as_ref())
        .is_some_and(|release| {
            catalog_selection::same_release_version(&release.version, &rt.state.installed_version)
        });
    rt.state.selected = selection.ok().flatten();
    rt.state.phase = "idle".into();
    rt.state.error = None;
    rt.state.step = if rt.state.selected.is_some() {
        "Релиз доступен для скачивания"
    } else {
        "Новых совместимых релизов нет"
    }
    .into();
    if automatic {
        if let Some(r) = &rt.state.selected {
            if r.version != rt.state.installed_version {
                rt.state.notice = Some(r.version.clone());
                rt.state.notice_opened = false;
            }
        }
    }
    state::save(root(), "state.json", &rt.state)?;
    drop(rt);
    if let Some(error) = rejected {
        crate::log(root(), &format!("catalog_release_skipped {error}"))?;
    }
    if let Some(error) = selection_error {
        return Err(invalid(&error));
    }
    Ok(())
}
fn download(shared: &Shared) -> io::Result<()> {
    let (release, claims) = selected(shared)?;
    device::wake(true)?;
    phase(shared, "downloading", "Скачивание архива")?;
    // A full archive plus extraction and PackageManager reserve; actual expanded size is checked below.
    device::space(
        root(),
        release.payload.size.saturating_mul(3) + 256 * 1024 * 1024,
    )?;
    let archive = root().join("release.zip");
    let partial = root().join("release.part");
    for p in [&archive, &partial] {
        if p.exists() {
            fs::remove_file(p)?;
        }
    }
    if root().join("staging").exists() {
        fs::remove_dir_all(root().join("staging"))?;
    }
    network::download(
        &release.payload.url,
        &partial,
        release.payload.size,
        &release.payload.sha256,
        |n| {
            let _ = update(shared, |s| {
                s.bytes = n;
                s.total = release.payload.size;
            });
        },
    )?;
    fs::rename(&partial, &archive)?;
    phase(shared, "verifying", "Распаковка и проверка релиза")?;
    let mut zip =
        zip::ZipArchive::new(fs::File::open(&archive)?).map_err(|e| invalid(&e.to_string()))?;
    let mut expanded = 0u64;
    for i in 0..zip.len() {
        expanded = expanded
            .checked_add(zip.by_index(i).map_err(|e| invalid(&e.to_string()))?.size())
            .ok_or_else(|| invalid("Размер ZIP переполнен"))?;
    }
    device::space(root(), expanded.saturating_add(256 * 1024 * 1024))?;
    drop(zip);
    network::extract(&archive, &root().join("staging"), |n, total| {
        let _ = update(shared, |s| {
            s.bytes = n;
            s.total = total;
        });
    })?;
    let p = Payload::open(&root().join("staging")).map_err(|e| invalid(&e.to_string()))?;
    p.require_infrastructure(release_core::infrastructure::Infrastructure::compiled())
        .map_err(|e| invalid(&e.to_string()))?;
    verify_payload(&p, &claims)?;
    crate::install::compatible(&p)?;
    phase(shared, "verified", "Релиз проверен. Можно начать установку")
}
fn verify_payload(payload: &Payload, claims: &ota::Claims) -> io::Result<()> {
    if crate::restore_ui::enabled() && payload.manifest.release_version != claims.version {
        return Err(invalid("Manifest не соответствует выбранному релизу"));
    }
    ota::verify_payload(payload, claims).map_err(|error| invalid(&error.to_string()))
}

pub fn select_release(shared: &Shared, version: &str) -> io::Result<()> {
    if !crate::restore_ui::enabled() {
        return Err(invalid("Выбор версии не поддерживается этой службой"));
    }
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
    release_core::infrastructure::Infrastructure::from_version(&release.version)
        .and_then(|profile| profile.require(release_core::infrastructure::Infrastructure::Od))
        .map_err(|error| invalid(&error.to_string()))?;
    catalog_selection::verify_selection(
        &release,
        &runtime.state.installed_version,
        true,
        release_core::infrastructure::Infrastructure::Od,
    )
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
    let p = Payload::open(&root().join("staging")).map_err(|e| invalid(&e.to_string()))?;
    p.require_infrastructure(release_core::infrastructure::Infrastructure::compiled())
        .map_err(|e| invalid(&e.to_string()))?;
    verify_payload(&p, &claims)?;
    Ok((p, claims))
}
