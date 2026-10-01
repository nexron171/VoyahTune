use release_core::catalog::Release;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{self, Read, Write},
    os::unix::fs::OpenOptionsExt,
    path::Path,
};
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct State {
    pub schema: u32,
    pub phase: String,
    pub step: String,
    #[serde(default)]
    pub completed_steps: u32,
    #[serde(default)]
    pub total_steps: u32,
    pub error: Option<String>,
    pub installed_version: String,
    pub fingerprint: String,
    pub source_generation: u64,
    pub installed_archive_sha256: String,
    pub selected: Option<Release>,
    pub same_version: bool,
    pub bytes: u64,
    pub total: u64,
    pub notice: Option<String>,
    pub notice_opened: bool,
    pub last_auto_wall: u64,
    pub last_auto_boot: String,
    pub last_auto_uptime: u64,
    pub apply_boot: String,
}
impl State {
    pub fn busy(&self) -> bool {
        matches!(
            self.phase.as_str(),
            "checking"
                | "downloading"
                | "verifying"
                | "preparing"
                | "applying"
                | "reboot-pending"
                | "validating"
        )
    }
    pub fn repair(&self) -> bool {
        self.phase == "repair-required"
    }
    pub fn finish_result(&mut self, reset_errors: bool) {
        // Opening the menu may finish success, but only the owner's explicit
        // Finish resets errors. An active installation and USB repair stay durable.
        if self.busy() || self.repair() || (self.phase != "committed" && !reset_errors) {
            return;
        }
        self.phase = "idle".into();
        self.step = "Готово к проверке обновлений".into();
        self.selected = None;
        self.same_version = false;
        self.bytes = 0;
        self.total = 0;
        self.completed_steps = 0;
        self.total_steps = 0;
        self.error = None;
        self.notice = None;
        self.notice_opened = true;
        self.apply_boot.clear();
    }
    pub fn installation_started(&self) -> bool {
        matches!(
            self.phase.as_str(),
            "applying" | "reboot-pending" | "validating" | "repair-required"
        )
    }
    pub fn fail(&mut self, message: String, repair: bool) {
        let retry_apply = !repair && self.phase == "preparing" && self.selected.is_some();
        self.phase = if repair {
            "repair-required"
        } else if retry_apply {
            "verified"
        } else {
            "failed"
        }
        .into();
        self.error = Some(message);
        self.step = if retry_apply {
            "Устраните причину ошибки и повторите установку"
        } else {
            "Установите релиз через USB с компьютера"
        }
        .into();
        self.notice = if repair { Some("error".into()) } else { None };
        self.notice_opened = false;
    }
    pub fn recover_interrupted(&mut self, boot: &str) {
        if self.phase == "applying" || (self.phase == "reboot-pending" && self.apply_boot == boot) {
            self.fail(
                "Установка была прервана; автоматическое продолжение отключено".into(),
                true,
            );
        } else if self.phase == "preparing" {
            self.fail(
                "Предварительная проверка прервана. Можно повторить установку".into(),
                false,
            );
        } else if matches!(
            self.phase.as_str(),
            "checking" | "downloading" | "verifying"
        ) {
            self.fail(
                "Подготовка обновления прервана. Выполните проверку и загрузку повторно".into(),
                false,
            );
        }
    }
    pub fn fresh(version: String, fingerprint: String) -> Self {
        Self {
            schema: 1,
            phase: "idle".into(),
            step: "Готово к проверке обновлений".into(),
            completed_steps: 0,
            total_steps: 0,
            error: None,
            installed_version: version,
            fingerprint,
            source_generation: 0,
            installed_archive_sha256: String::new(),
            selected: None,
            same_version: false,
            bytes: 0,
            total: 0,
            notice: None,
            notice_opened: false,
            last_auto_wall: 0,
            last_auto_boot: String::new(),
            last_auto_uptime: 0,
            apply_boot: String::new(),
        }
    }
}
pub fn save(root: &Path, name: &str, value: &impl Serialize) -> io::Result<()> {
    let temporary = root.join(format!(".{name}.new"));
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&temporary)?;
    serde_json::to_writer(&mut file, value)?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(temporary, root.join(name))?;
    fs::File::open(root)?.sync_all()
}
pub fn read<T: serde::de::DeserializeOwned>(path: &Path) -> io::Result<T> {
    let f = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    let mut bytes = Vec::new();
    f.take(1024 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > 1024 * 1024 {
        return Err(crate::config::invalid("Состояние слишком велико"));
    }
    serde_json::from_slice(&bytes)
        .map_err(|e| crate::config::invalid(&format!("Состояние повреждено: {e}")))
}
pub fn due(s: &State, wall: u64, boot: &str, uptime: u64) -> bool {
    if s.last_auto_boot.is_empty() {
        return true;
    }
    if wall < s.last_auto_wall || wall - s.last_auto_wall < 86400 {
        return false;
    }
    s.last_auto_boot != boot || uptime.saturating_sub(s.last_auto_uptime) >= 86400
}
#[cfg(test)]
mod tests {
    use super::*;
    fn preparing() -> State {
        let mut s = State::fresh("3.15.0".into(), "rom".into());
        s.phase = "preparing".into();
        s.same_version = true;
        s.selected = Some(Release {
            version: "3.15.0".into(),
            published_at: String::new(),
            channel: "stable".into(),
            notes_url: String::new(),
            payload: release_core::catalog::Archive {
                url: "https://example.com/release.zip".into(),
                size: 100,
                sha256: "a".repeat(64),
                manifest_schema: 4,
            },
            requirements: Default::default(),
        });
        s
    }
    #[test]
    fn preflight_failure_keeps_download_for_retry_across_restart() {
        let t = tempfile::tempdir().unwrap();
        let mut s = preparing();
        assert!(s.busy());
        let repair = s.installation_started();
        s.fail("Автомобиль не в P".into(), repair);
        save(t.path(), "state.json", &s).unwrap();
        let mut restored: State = read(&t.path().join("state.json")).unwrap();
        restored.recover_interrupted("boot");
        assert_eq!(restored.phase, "verified");
        assert_eq!(restored.error.as_deref(), Some("Автомобиль не в P"));
        assert!(!restored.busy() && !restored.repair());
        assert_eq!(restored.selected.unwrap().payload.sha256, "a".repeat(64));
        assert!(restored.same_version);
        assert!(restored.notice.is_none());
    }
    #[test]
    fn finishing_success_returns_to_check_without_forgetting_installed_release() {
        let mut s = preparing();
        s.phase = "committed".into();
        s.installed_version = "3.19.0".into();
        s.installed_archive_sha256 = "b".repeat(64);
        s.notice = Some("success".into());
        s.completed_steps = 8;
        s.total_steps = 8;
        s.finish_result(false);
        assert_eq!(s.phase, "idle");
        assert_eq!(s.step, "Готово к проверке обновлений");
        assert!(s.selected.is_none() && s.notice.is_none());
        assert_eq!((s.completed_steps, s.total_steps), (0, 0));
        assert_eq!(s.installed_version, "3.19.0");
        assert_eq!(s.installed_archive_sha256, "b".repeat(64));
    }
    #[test]
    fn interrupted_preflight_is_retryable_but_applying_requires_repair() {
        let t = tempfile::tempdir().unwrap();
        for phase in ["preparing", "applying"] {
            let mut s = preparing();
            s.phase = phase.into();
            save(t.path(), "state.json", &s).unwrap();
            let mut restored: State = read(&t.path().join("state.json")).unwrap();
            restored.recover_interrupted("next-boot");
            assert_eq!(
                restored.phase,
                if phase == "preparing" {
                    "verified"
                } else {
                    "repair-required"
                }
            );
        }
    }
    #[test]
    fn explicit_finish_clears_preparation_errors_and_selection_across_restart() {
        let t = tempfile::tempdir().unwrap();
        for phase in ["failed", "verified", "idle"] {
            let mut s = preparing();
            s.phase = phase.into();
            s.error = Some("Ошибка подготовки".into());
            s.bytes = 100;
            s.total = 100;
            s.completed_steps = 1;
            s.total_steps = 8;
            s.installed_archive_sha256 = "b".repeat(64);
            s.last_auto_wall = 123;
            s.source_generation = 7;
            s.finish_result(true);
            save(t.path(), "state.json", &s).unwrap();
            let restored: State = read(&t.path().join("state.json")).unwrap();
            assert_eq!(restored.phase, "idle");
            assert!(restored.error.is_none() && restored.selected.is_none());
            assert!(!restored.same_version);
            assert_eq!((restored.bytes, restored.total), (0, 0));
            assert_eq!((restored.completed_steps, restored.total_steps), (0, 0));
            assert_eq!(restored.installed_version, "3.15.0");
            assert_eq!(restored.installed_archive_sha256, "b".repeat(64));
            assert_eq!(
                (restored.last_auto_wall, restored.source_generation),
                (123, 7)
            );
        }
    }
    #[test]
    fn opening_menu_keeps_errors_and_downloaded_release() {
        for phase in ["failed", "verified", "idle"] {
            let mut s = preparing();
            s.phase = phase.into();
            s.error = Some("Ошибка".into());
            let before = serde_json::to_value(&s).unwrap();
            s.finish_result(false);
            assert_eq!(serde_json::to_value(&s).unwrap(), before);
        }
    }
    #[test]
    fn finish_never_cancels_active_work_or_usb_repair() {
        for phase in [
            "checking",
            "downloading",
            "verifying",
            "preparing",
            "applying",
            "reboot-pending",
            "validating",
            "repair-required",
        ] {
            let mut s = preparing();
            s.phase = phase.into();
            s.error = Some("Ошибка".into());
            let before = serde_json::to_value(&s).unwrap();
            s.finish_result(true);
            assert_eq!(serde_json::to_value(&s).unwrap(), before);
        }
    }
    #[test]
    fn errors_after_apply_boundary_and_reboot_failure_require_repair() {
        for phase in ["applying", "reboot-pending", "validating"] {
            let mut s = preparing();
            s.phase = phase.into();
            let repair = s.installation_started();
            s.fail("Ошибка записи или запуска".into(), repair);
            assert!(s.repair());
            assert_eq!(s.notice.as_deref(), Some("error"));
        }
        let mut pending = preparing();
        pending.phase = "reboot-pending".into();
        pending.apply_boot = "before".into();
        let mut after = pending.clone();
        after.recover_interrupted("after");
        assert_eq!(after.phase, "reboot-pending");
        pending.recover_interrupted("before");
        assert!(pending.repair());
    }
    #[test]
    fn daily_attempt_survives_restart_and_clock_rollback() {
        let mut s = State::fresh("3.14.0".into(), "rom".into());
        s.last_auto_wall = 100000;
        s.last_auto_boot = "boot".into();
        s.last_auto_uptime = 20;
        assert!(!due(&s, 200000, "boot", 30));
        assert!(!due(&s, 99999, "new", 90000));
        assert!(!due(&s, 100001, "new", 30));
        assert!(due(&s, 186400, "boot", 86420));
        assert!(due(&s, 186400, "new", 30));
    }
    #[test]
    fn journal_is_atomic_and_private() {
        let t = tempfile::tempdir().unwrap();
        let s = State::fresh("3.14.0".into(), "rom".into());
        save(t.path(), "state.json", &s).unwrap();
        let r: State = read(&t.path().join("state.json")).unwrap();
        assert_eq!(r.installed_version, "3.14.0");
    }
}

#[cfg(test)]
mod progress_compatibility_tests {
    use super::*;
    #[test]
    fn older_journal_has_no_fabricated_step_progress() {
        let mut old =
            serde_json::to_value(State::fresh("3.16.0".into(), "firmware".into())).unwrap();
        old.as_object_mut().unwrap().remove("completedSteps");
        old.as_object_mut().unwrap().remove("totalSteps");
        let parsed: State = serde_json::from_value(old).unwrap();
        assert_eq!((parsed.completed_steps, parsed.total_steps), (0, 0));
    }
}
