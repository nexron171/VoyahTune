use crate::{config::invalid, device, protocol};
use release_core::{infrastructure::Infrastructure, payload};
use std::{
    fs, io,
    os::unix::fs::{MetadataExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::Mutex,
};

pub fn enabled() -> bool {
    Infrastructure::compiled() == Infrastructure::Od
}
pub fn component() -> &'static str {
    if enabled() {
        "ru.big.town.restoremode/.OtaActivity"
    } else {
        "ru.big.town.updater/.MainActivity"
    }
}
pub fn capabilities() -> Vec<&'static str> {
    let mut result = vec!["install-step-progress", "dns-settings"];
    if enabled() {
        result.push("restoremode-ota-ui-v1");
    }
    result
}

#[derive(Debug, PartialEq, Eq)]
struct Identity {
    path: PathBuf,
    dev: u64,
    ino: u64,
    size: u64,
    modified: (i64, i64),
    changed: (i64, i64),
}
fn identity(path: &Path, system: bool) -> io::Result<Identity> {
    device::no_links(path)?;
    let m = fs::symlink_metadata(path)?;
    if !m.is_file()
        || !(m.uid() == 0 || (!system && m.uid() == 1000))
        || m.permissions().mode() & 0o022 != 0
    {
        return Err(invalid("Недопустимые права APK интерфейса обновления"));
    }
    Ok(Identity {
        path: path.to_path_buf(),
        dev: m.dev(),
        ino: m.ino(),
        size: m.len(),
        modified: (m.mtime(), m.mtime_nsec()),
        changed: (m.ctime(), m.ctime_nsec()),
    })
}
static VERIFIED: Mutex<Option<(Identity, Identity)>> = Mutex::new(None);

// PackageManager owns this registry; only the unique installed RestoreMode UID is accepted.
fn registered_apk(xml: &str, uid: u32) -> io::Result<PathBuf> {
    use quick_xml::{events::Event, Reader};
    let mut reader = Reader::from_str(xml);
    reader.config_mut().expand_empty_elements = true;
    let mut depth: usize = 0;
    let mut root = false;
    let mut found = None;
    loop {
        match reader
            .read_event()
            .map_err(|_| invalid("Повреждён реестр APK"))?
        {
            Event::Start(e) => {
                if depth == 0 {
                    if root || e.name().as_ref() != "packages" {
                        return Err(invalid("Недопустимый реестр APK"));
                    }
                    root = true;
                }
                if depth == 1 && e.name().as_ref() == "package" {
                    let mut name = None;
                    let mut id = None;
                    let mut path = None;
                    let mut shared = false;
                    for a in e.attributes() {
                        let a = a.map_err(|_| invalid("Недопустимый атрибут APK"))?;
                        let value = a
                            .normalized_value(quick_xml::XmlVersion::Explicit1_0)
                            .map_err(|_| invalid("Недопустимый атрибут APK"))?
                            .into_owned();
                        match a.key.as_ref() {
                            "name" => name = Some(value),
                            "userId" => id = value.parse::<u32>().ok(),
                            "codePath" => path = Some(value),
                            "sharedUserId" => shared = true,
                            _ => {}
                        }
                    }
                    if name.as_deref() == Some(payload::RESTORE) {
                        if found.is_some() || shared || id != Some(uid) || uid < 10000 {
                            return Err(invalid("Недопустимый UID RestoreMode"));
                        }
                        let code =
                            PathBuf::from(path.ok_or_else(|| invalid("Нет пути RestoreMode"))?);
                        if !code.starts_with("/data/app")
                            || code.components().any(|p| {
                                !matches!(
                                    p,
                                    std::path::Component::RootDir | std::path::Component::Normal(_)
                                )
                            })
                        {
                            return Err(invalid("Недопустимый путь RestoreMode"));
                        }
                        found = Some(if code.extension().is_some_and(|s| s == "apk") {
                            code
                        } else {
                            code.join("base.apk")
                        });
                    }
                }
                depth += 1;
            }
            Event::End(_) => {
                depth = depth
                    .checked_sub(1)
                    .ok_or_else(|| invalid("Повреждён реестр APK"))?;
            }
            Event::Eof => break,
            _ => {}
        }
    }
    if !root || depth != 0 {
        return Err(invalid("Неполный реестр APK"));
    }
    found.ok_or_else(|| invalid("RestoreMode не зарегистрирован"))
}

pub fn authorize(uid: u32) -> io::Result<()> {
    if protocol::package_uid(
        &fs::read_to_string("/data/system/packages.list")?,
        payload::RESTORE,
    ) != Some(uid)
    {
        return Err(invalid("Клиент не имеет доступа"));
    }
    let path = registered_apk(&fs::read_to_string("/data/system/packages.xml")?, uid)?;
    let restore = identity(&path, false)?;
    let anchor = identity(Path::new(payload::NATIVE_PATH), true)?;
    let before = (restore, anchor);
    let mut cache = VERIFIED
        .lock()
        .map_err(|_| invalid("Проверка интерфейса недоступна"))?;
    if cache.as_ref() == Some(&before) {
        return Ok(());
    }
    let native_signers = payload::verified_signers(Path::new(payload::NATIVE_PATH))
        .map_err(|e| invalid(&e.to_string()))?;
    let restore_signers = payload::verified_signers(&path).map_err(|e| invalid(&e.to_string()))?;
    if native_signers.is_empty()
        || native_signers != restore_signers
        || release_core::apk_identity::read(&path)
            .map_err(|e| invalid(&e.to_string()))?
            .0
            != payload::RESTORE
    {
        return Err(invalid(
            "Подпись интерфейса не соответствует установленному Native",
        ));
    }
    // Cache only immutable file identities that stayed unchanged throughout verification.
    let current = (
        identity(&path, false)?,
        identity(Path::new(payload::NATIVE_PATH), true)?,
    );
    if current != before {
        return Err(invalid("APK изменился во время проверки"));
    }
    *cache = Some(current);
    Ok(())
}

fn runtime_process(name: &str) -> bool {
    (name == payload::RESTORE || name.starts_with("ru.big.town.restoremode:"))
        && name != "ru.big.town.restoremode:ota"
}
pub fn stop_runtime() -> io::Result<()> {
    let uid = protocol::package_uid(
        &fs::read_to_string("/data/system/packages.list")?,
        payload::RESTORE,
    )
    .ok_or_else(|| invalid("Нет уникального UID RestoreMode"))?;
    for entry in fs::read_dir("/proc")? {
        let path = entry?.path();
        let Some(pid) = path
            .file_name()
            .and_then(|s| s.to_str())
            .and_then(|s| s.parse::<i32>().ok())
        else {
            continue;
        };
        let Ok(cmd) = fs::read(path.join("cmdline")) else {
            continue;
        };
        let name = String::from_utf8_lossy(cmd.split(|b| *b == 0).next().unwrap_or_default());
        if !runtime_process(&name) || fs::metadata(&path).map(|m| m.uid()).ok() != Some(uid) {
            continue;
        }
        if unsafe { libc::kill(pid, libc::SIGKILL) } != 0 {
            let error = io::Error::last_os_error();
            if error.raw_os_error() != Some(libc::ESRCH) {
                return Err(error);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_registered_unique_restore_identity_is_accepted() {
        let valid = r#"<packages><package name="ru.big.town.restoremode" userId="10123" codePath="/data/app/restore-123"/></packages>"#;
        assert_eq!(
            registered_apk(valid, 10123).unwrap(),
            Path::new("/data/app/restore-123/base.apk")
        );
        for bad in [
            valid.replace("userId", "sharedUserId"),
            valid.replace("10123", "10124"),
            valid.replace("/data/app/restore-123", "/data/local/tmp/restore.apk"),
            valid.replace(
                "/data/app/restore-123",
                "/data/app/../local/tmp/restore.apk",
            ),
            valid.replace("</packages>", ""),
            valid.replace("<packages>", "<other>"),
        ] {
            assert!(registered_apk(&bad, 10123).is_err(), "{bad}");
        }
        assert_eq!(
            protocol::package_uid(
                "ru.big.town.restoremode 10123 0\nother 10123 0",
                payload::RESTORE
            ),
            None
        );
    }
    #[test]
    fn ota_process_survives_runtime_stop() {
        assert!(runtime_process("ru.big.town.restoremode"));
        assert!(runtime_process("ru.big.town.restoremode:worker"));
        assert!(!runtime_process("ru.big.town.restoremode:ota"));
        assert!(!runtime_process("ru.big.town.restoremode.other"));
        assert!(!runtime_process("ru.big.town.anative"));
    }
}
