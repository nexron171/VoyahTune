use crate::{
    config::{self, invalid, Config},
    device, network,
    state::{self, State},
};
use release_core::{catalog::Release, ota, payload::Payload};
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
    let _ = update(shared, |s| {
        s.phase = if repair { "repair-required" } else { "failed" }.into();
        s.error = Some(message);
        s.step = "Установите релиз через USB с компьютера".into();
        if repair {
            s.notice = Some("error".into());
            s.notice_opened = false;
        }
    });
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
    if state.phase == "applying"
        || (state.phase == "reboot-pending" && state.apply_boot == device::boot())
    {
        state.phase = "repair-required".into();
        state.error = Some("Установка была прервана; автоматическое продолжение отключено".into());
        state.notice = Some("error".into());
        state.notice_opened = false;
    } else if matches!(
        state.phase.as_str(),
        "checking" | "downloading" | "verifying"
    ) {
        state.phase = "failed".into();
        state.error =
            Some("Подготовка обновления прервана. Выполните проверку и загрузку повторно".into());
    }
    if let Ok(c) = &config {
        if state.source_generation != c.source_generation && !state.repair() {
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
    // Set busy before acknowledging: double taps cannot enqueue parallel work.
    rt.state.phase = match job {
        Job::Check(_) => "checking",
        Job::Download => "downloading",
        Job::Apply => "applying",
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
                    fail(&shared, &e, apply);
                }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                let s = snapshot(&shared);
                if !s.busy()
                    && !s.repair()
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
    let key = fs::read("/system/etc/voyahtune-ota-key.der")?;
    let c = ota::verify(&r, &key).map_err(|e| invalid(&e.to_string()))?;
    if c.version == s.installed_version
        && s.installed_sequence > 0
        && (c.sequence != s.installed_sequence || c.archive_sha256 != s.installed_archive_sha256)
    {
        return Err(invalid(
            "Повторная установка разрешена только для того же архива",
        ));
    }
    ota::compatible(
        &c,
        &s.installed_version,
        env!("CARGO_PKG_VERSION"),
        s.installed_sequence,
        s.same_version,
    )
    .map_err(|e| invalid(&e.to_string()))?;
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
    phase(shared, "checking", "Проверка каталога релизов")?;
    if device::prop("ro.build.fingerprint")? != snapshot(shared).fingerprint {
        return Err(invalid("Прошивка ГУ изменилась. Требуется USB-установка"));
    }
    let catalog = network::catalog(&url)?;
    let current = snapshot(shared);
    let key = fs::read("/system/etc/voyahtune-ota-key.der")?;
    let mut chosen = None;
    let mut rejected = None;
    for r in catalog
        .ota_releases()
        .filter(|r| r.channel == "stable" && (!same || r.version == current.installed_version))
    {
        let result = ota::verify(r, &key).and_then(|c| {
            ota::compatible(
                &c,
                &current.installed_version,
                env!("CARGO_PKG_VERSION"),
                current.installed_sequence,
                same,
            )
        });
        match result {
            Ok(()) => {
                chosen = Some(r.clone());
                break;
            }
            Err(e) => {
                rejected = Some(e.to_string());
            }
        }
    }
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
    rt.state.source_generation = generation;
    rt.state.same_version = same;
    rt.state.selected = chosen;
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
    if let Some(e) = rejected {
        crate::log(root(), &format!("catalog_release_skipped {e}"))?;
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
    ota::verify_payload(&p, &claims).map_err(|e| invalid(&e.to_string()))?;
    crate::install::compatible(&p)?;
    phase(shared, "verified", "Релиз проверен. Можно начать установку")
}
pub fn verified(shared: &Shared) -> io::Result<(Payload, ota::Claims)> {
    let (_, claims) = selected(shared)?;
    let p = Payload::open(&root().join("staging")).map_err(|e| invalid(&e.to_string()))?;
    ota::verify_payload(&p, &claims).map_err(|e| invalid(&e.to_string()))?;
    Ok((p, claims))
}
