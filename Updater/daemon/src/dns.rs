//! Reuse the desktop's device helper; no DNS commands or paths come from IPC.
use crate::{config::invalid, device};
use release_core::payload::Payload;
use std::{fs, io, path::Path};

const HELPER: &str = include_str!("../../../Packaging/installer/common/dns-overlay-device.sh");
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
pub fn prepare(desired: Option<bool>) -> io::Result<Action> {
    // Keeping DNS must remain possible even if an external DNS setup is unknown.
    if desired.is_none() {
        return Ok(Action::Keep);
    }
    {
        return Ok(if desired == Some(true) {
            Action::Install
        } else {
            Action::Disable
        });
    }
}
pub fn apply(p: &Payload, action: Action) -> io::Result<()> {
    if action == Action::Keep {
        return Ok(());
    }
    {
        let _ = device::command("/system/bin/mount", &["-o", "rw,remount", "/vendor"], 20);
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
    fn settings_validate_current_dns_without_blocking_the_ota_choice() {
        for (state, desired, expected) in [
            ("on", Some(true), Action::Keep),
            ("off", Some(false), Action::Keep),
            ("on", Some(false), Action::Disable),
            ("off", Some(true), Action::Install),
        ] {
            assert_eq!(plan(state, desired).unwrap(), expected);
        }
        for state in ["external", "broken", "unknown"] {
            assert!(plan(state, Some(true)).is_err());
            assert_eq!(plan(state, None).unwrap(), Action::Keep);
            assert_eq!(prepare(Some(true)).unwrap(), Action::Install);
        }
    }
    #[test]
    fn user_choice_controls_the_dns_action() {
        for (desired, expected) in [
            (None, Action::Keep),
            (Some(true), Action::Install),
            (Some(false), Action::Disable),
        ] {
            assert_eq!(prepare(desired).unwrap(), expected);
        }
    }
}
