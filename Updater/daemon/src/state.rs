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
    pub error: Option<String>,
    pub installed_version: String,
    pub installed_sequence: u64,
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
            "checking" | "downloading" | "verifying" | "applying" | "reboot-pending" | "validating"
        )
    }
    pub fn repair(&self) -> bool {
        self.phase == "repair-required"
    }
    pub fn fresh(version: String, fingerprint: String) -> Self {
        Self {
            schema: 1,
            phase: "idle".into(),
            step: "Готово к проверке обновлений".into(),
            error: None,
            installed_version: version,
            installed_sequence: 0,
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
    if s.last_auto_wall == 0 {
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
