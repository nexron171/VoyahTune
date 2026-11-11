use crate::{config::invalid, device, protocol};
use release_core::payload;
use std::{
    fs, io,
    os::unix::fs::{MetadataExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::Mutex,
};

pub fn component() -> &'static str {
    "ru.big.town.updater/.UpdaterActivity"
}
pub fn capabilities() -> Vec<&'static str> {
    vec![
        "install-step-progress",
        "dns-settings",
        "unified-runtime-v1",
        "catalog-releases",
        "select-release",
        "application-log-v1",
        "turn-sound-v1",
    ]
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

// Both signed UI packages have independent PackageManager identities.
fn registered_apk(xml: &str, uid: u32, package: &str) -> io::Result<PathBuf> {
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
                    if name.as_deref() == Some(package) {
                        if found.is_some() || shared || id != Some(uid) || uid < 10000 {
                            return Err(invalid("Недопустимый UID UI"));
                        }
                        let code = PathBuf::from(path.ok_or_else(|| invalid("Нет пути UI"))?);
                        if !(code.starts_with("/data/app")
                            || (package == payload::UPDATER
                                && (code == Path::new(payload::UPDATER_PATH)
                                    || Some(code.as_path())
                                        == Path::new(payload::UPDATER_PATH).parent())))
                            || code.components().any(|p| {
                                !matches!(
                                    p,
                                    std::path::Component::RootDir | std::path::Component::Normal(_)
                                )
                            })
                        {
                            return Err(invalid("Недопустимый путь UI"));
                        }
                        found = Some(
                            if package == payload::UPDATER
                                && Some(code.as_path()) == Path::new(payload::UPDATER_PATH).parent()
                            {
                                PathBuf::from(payload::UPDATER_PATH)
                            } else if code.extension().is_some_and(|s| s == "apk") {
                                code
                            } else {
                                code.join("base.apk")
                            },
                        );
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
    found.ok_or_else(|| invalid("UI не зарегистрирован"))
}

fn trust_anchor(package: &str) -> &Path {
    Path::new(if package == payload::UPDATER {
        payload::UPDATER_PATH
    } else {
        payload::NATIVE_PATH
    })
}

fn verify_client_signature(
    package: &str,
    path: &Path,
    mut signers: impl FnMut(&Path) -> io::Result<Vec<String>>,
    package_name: impl FnOnce(&Path) -> io::Result<String>,
) -> io::Result<()> {
    let trusted_signers = signers(trust_anchor(package))?;
    let client_signers = signers(path)?;
    if trusted_signers.is_empty()
        || trusted_signers != client_signers
        || package_name(path)? != package
    {
        return Err(invalid(
            "Подпись интерфейса не соответствует доверенному системному APK",
        ));
    }
    Ok(())
}

pub fn authorize(uid: u32) -> io::Result<()> {
    let packages = fs::read_to_string("/data/system/packages.list")?;
    let package = [payload::RESTORE, payload::UPDATER]
        .into_iter()
        .find(|package| protocol::package_uid(&packages, package) == Some(uid))
        .ok_or_else(|| invalid("Клиент не имеет доступа"))?;
    let path = registered_apk(
        &fs::read_to_string("/data/system/packages.xml")?,
        uid,
        package,
    )?;
    let client = identity(&path, false)?;
    let anchor = identity(trust_anchor(package), true)?;
    let before = (client, anchor);
    let mut cache = VERIFIED
        .lock()
        .map_err(|_| invalid("Проверка интерфейса недоступна"))?;
    if cache.as_ref() == Some(&before) {
        return Ok(());
    }
    verify_client_signature(
        package,
        &path,
        |path| payload::verified_signers(path).map_err(|error| invalid(&error.to_string())),
        |path| {
            release_core::apk_identity::read(path)
                .map(|identity| identity.0)
                .map_err(|error| invalid(&error.to_string()))
        },
    )?;
    // Cache only immutable file identities that stayed unchanged throughout verification.
    let current = (
        identity(&path, false)?,
        identity(trust_anchor(package), true)?,
    );
    if current != before {
        return Err(invalid("APK изменился во время проверки"));
    }
    *cache = Some(current);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn updater_keeps_access_after_native_signer_changes() {
        let client = Path::new("/data/app/updater/base.apk");
        for native_signer in ["old-key", "new-key"] {
            verify_client_signature(
                payload::UPDATER,
                client,
                |path| {
                    Ok(vec![if path == Path::new(payload::NATIVE_PATH) {
                        native_signer.into()
                    } else {
                        "old-key".into()
                    }])
                },
                |_| Ok(payload::UPDATER.into()),
            )
            .unwrap();
        }
        assert!(verify_client_signature(
            payload::RESTORE,
            Path::new("/data/app/restore/base.apk"),
            |path| Ok(vec![if path == Path::new(payload::NATIVE_PATH) {
                "new-key".into()
            } else {
                "old-key".into()
            }]),
            |_| Ok(payload::RESTORE.into())
        )
        .is_err());
    }

    #[test]
    fn rejects_untrusted_signers_and_wrong_package_identity() {
        let path = Path::new("/data/app/updater/base.apk");
        for (client_signer, claimed_package) in [
            ("other-key", payload::UPDATER),
            ("usb-key", "unrelated.package"),
        ] {
            assert!(verify_client_signature(
                payload::UPDATER,
                path,
                |candidate| Ok(vec![if candidate == Path::new(payload::UPDATER_PATH) {
                    "usb-key".into()
                } else {
                    client_signer.into()
                }]),
                |_| Ok(claimed_package.into())
            )
            .is_err());
        }
        assert!(verify_client_signature(
            payload::UPDATER,
            path,
            |_| Ok(vec![]),
            |_| Ok(payload::UPDATER.into())
        )
        .is_err());
    }

    #[test]
    fn only_registered_unique_restore_identity_is_accepted() {
        let valid = r#"<packages><package name="ru.big.town.restoremode" userId="10123" codePath="/data/app/restore-123"/></packages>"#;
        assert_eq!(
            registered_apk(valid, 10123, payload::RESTORE).unwrap(),
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
            assert!(
                registered_apk(&bad, 10123, payload::RESTORE).is_err(),
                "{bad}"
            );
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
    fn independent_updater_accepts_system_or_data_registration() {
        for path in [
            "/data/app/updater/base.apk",
            payload::UPDATER_PATH,
            "/system/priv-app/VoyahTuneUpdater",
        ] {
            let xml = format!(
                r#"<packages><package name="ru.big.town.updater" userId="10124" codePath="{path}"/></packages>"#
            );
            assert_eq!(
                registered_apk(&xml, 10124, payload::UPDATER).unwrap(),
                if path == "/system/priv-app/VoyahTuneUpdater" {
                    Path::new(payload::UPDATER_PATH)
                } else {
                    Path::new(path)
                }
            );
            assert!(registered_apk(&xml, 10124, payload::RESTORE).is_err());
        }
    }
}
