//! Reuse the desktop's device helper; no DNS commands or paths come from IPC.
use crate::{config::invalid, device};
use release_core::payload::{self, Payload};
use std::{fs, io, path::Path};

const HELPER: &str = include_str!("../../../Packaging/od/installer/common/dns-overlay-device.sh");
const DNS_SHA: &str = "c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d";
const INCOMING: &str = "/data/local/tmp/open_voyah_yandex_dns.apk";

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Action {
    Keep,
    Install,
    Disable,
}

pub fn plan(status: &str, desired: Option<bool>) -> io::Result<Action> {
    match (status, desired) {
        (_, None) | ("on", Some(true)) | ("off", Some(false)) => Ok(Action::Keep),
        ("off", Some(true)) => Ok(Action::Install),
        ("on", Some(false)) => Ok(Action::Disable),
        _ => Err(invalid(
            "Состояние DNS не определено или изменено извне; настройка отменена",
        )),
    }
}
fn helper(action: &str) -> io::Result<String> {
    let path = Path::new(crate::ROOT).join("dns-helper.sh");
    // The directory is root-private. Only the immutable build-time helper is
    // written here; neither script bytes nor its path come from IPC.
    device::no_links(&path)?;
    fs::write(&path, HELPER)?;
    device::command("/system/bin/sh", &[path.to_str().unwrap(), action], 30)
}
pub fn status() -> io::Result<String> {
    let status = helper("status")?;
    if !["on", "off", "external", "broken"].contains(&status.as_str()) {
        return Err(invalid("DNS helper вернул неизвестное состояние"));
    }
    Ok(status)
}
pub fn prepare(p: &Payload, desired: Option<bool>) -> io::Result<Action> {
    // Keeping DNS must remain possible even if an external DNS setup is unknown.
    if desired.is_none() {
        return Ok(Action::Keep);
    }
    let action = plan(&status()?, desired)?;
    if action == Action::Install {
        let apk = p.file("dns.apk").map_err(|e| invalid(&e.to_string()))?;
        if payload::sha256(&apk).map_err(|e| invalid(&e.to_string()))? != DNS_SHA {
            return Err(invalid("DNS APK не совпадает с поддерживаемым SHA-256"));
        }
    }
    if action != Action::Keep {
        let _ = device::command("/system/bin/mount", &["-o", "rw,remount", "/vendor"], 20);
        let probe = Path::new("/vendor/.voyahtune-dns-rwtest");
        device::no_links(probe)?;
        let file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(probe)?;
        file.sync_all()?;
        fs::remove_file(probe)?;
    }
    Ok(action)
}
pub fn apply(p: &Payload, action: Action) -> io::Result<()> {
    if action == Action::Keep {
        return Ok(());
    }
    if action == Action::Install {
        device::atomic_copy(
            &p.file("dns.apk").map_err(|e| invalid(&e.to_string()))?,
            Path::new(INCOMING),
            0o600,
        )?;
    }
    let result = helper(if action == Action::Install {
        "install"
    } else {
        "disable"
    });
    if action == Action::Install {
        let _ = fs::remove_file(INCOMING);
    }
    result?;
    // The helper writes pending overlay state; actual activation follows reboot.
    Ok(())
}
pub fn title(action: Action) -> &'static str {
    match action {
        Action::Keep => "Сохранение текущего DNS",
        Action::Install => "Установка Яндекс DNS",
        Action::Disable => "Возврат к стандартному DNS",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dns_choices_follow_current_state_and_never_replace_unknown_dns() {
        assert_eq!(plan("on", Some(true)).unwrap(), Action::Keep);
        assert_eq!(plan("off", Some(false)).unwrap(), Action::Keep);
        assert_eq!(plan("on", Some(false)).unwrap(), Action::Disable);
        assert_eq!(plan("off", Some(true)).unwrap(), Action::Install);
        for status in ["external", "broken", "unknown"] {
            assert!(plan(status, Some(true)).is_err());
            assert!(plan(status, Some(false)).is_err());
            assert_eq!(plan(status, None).unwrap(), Action::Keep);
        }
    }
}
