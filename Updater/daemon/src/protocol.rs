use serde::Deserialize;
use std::io::{self, BufRead};

pub const MAX_REQUEST: usize = 8192;
pub const SOCKET_NAME: &str = "voyahtune_updater";
pub const UI_PACKAGE: &str = "ru.big.town.updater";

#[derive(Debug, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case", deny_unknown_fields)]
pub enum Request {
    Status {},
    GetSettings {},
    SetSettings {
        url: String,
        dns_enabled: Option<bool>,
    },
    SetCatalogUrl {
        url: String,
    },
    Logs {},
    Check {
        #[serde(default)]
        same_version: bool,
    },
    Download {},
    Apply {},
    Finish {},
    Dismiss {},
}

pub fn read_request(reader: &mut impl BufRead) -> io::Result<Request> {
    let mut bytes = vec![];
    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            return Err(super::config::invalid("Незавершённый запрос"));
        }
        let newline = available.iter().position(|b| *b == b'\n');
        let length = newline.map_or(available.len(), |n| n + 1);
        if bytes.len() + length > MAX_REQUEST {
            return Err(super::config::invalid("Запрос слишком велик"));
        }
        bytes.extend_from_slice(&available[..length]);
        reader.consume(length);
        if newline.is_some() {
            break;
        }
    }
    serde_json::from_slice(&bytes)
        .map_err(|_| super::config::invalid("Неизвестная команда или формат запроса"))
}

/// packages.list is written by PackageManager, not by applications. The updater UI
/// must be installed as a system package by the trusted USB installer. No shared UID.
pub fn ui_uid(packages: &str) -> Option<u32> {
    let rows: Vec<_> = packages
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            Some((parts.next()?, parts.next()?.parse::<u32>().ok()?))
        })
        .collect();
    let matches: Vec<_> = rows
        .iter()
        .filter(|(name, _)| *name == UI_PACKAGE)
        .collect();
    if matches.len() != 1 {
        return None;
    }
    let uid = matches[0].1;
    if uid < 10000 || rows.iter().filter(|(_, id)| *id == uid).count() != 1 {
        return None;
    }
    Some(uid)
}

/// Verify the active package comes from our immutable system directory, not a
/// same-name user APK or /data/app update. The first USB installer owns this path.
pub fn system_ui_uid(xml: &str) -> Option<u32> {
    use quick_xml::{events::Event, Reader};
    let mut reader = Reader::from_str(xml);
    reader.config_mut().expand_empty_elements = true;
    let mut depth: usize = 0;
    let mut root_seen = false;
    let mut found = None;
    loop {
        match reader.read_event().ok()? {
            Event::Start(element) if depth == 1 && element.name().as_ref() == "package" => {
                let mut name = None;
                let mut path = None;
                let mut uid = None;
                let mut shared = false;
                for attr in element.attributes() {
                    let attr = attr.ok()?;
                    let value = attr
                        .normalized_value(quick_xml::XmlVersion::Explicit1_0)
                        .ok()?
                        .into_owned();
                    match attr.key.as_ref() {
                        "name" => name = Some(value),
                        "codePath" => path = Some(value),
                        "userId" => uid = value.parse::<u32>().ok(),
                        "sharedUserId" => shared = true,
                        _ => {}
                    }
                }
                if name.as_deref() == Some(UI_PACKAGE) {
                    if found.is_some()
                        || shared
                        || !matches!(
                            path.as_deref(),
                            Some("/system/priv-app/VoyahTuneUpdater")
                                | Some("/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk")
                        )
                    {
                        return None;
                    }
                    found = uid.filter(|id| *id >= 10000);
                    found?;
                }
                depth += 1;
            }
            Event::Start(element) => {
                if depth == 0 {
                    if root_seen || element.name().as_ref() != "packages" {
                        return None;
                    }
                    root_seen = true;
                }
                depth += 1;
            }
            Event::End(_) => {
                depth = depth.checked_sub(1)?;
            }
            Event::Eof => return if root_seen && depth == 0 { found } else { None },
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_external_archive_url_or_install_command_is_accepted() {
        for request in [
            "{\"command\":\"install\"}\n", "{\"command\":\"status\",\"url\":\"https://evil.test\"}\n",
            "{\"command\":\"set_catalog_url\",\"url\":\"https://example.org\",\"archive\":\"/tmp/a.zip\"}\n",
            "{\"command\":\"status\"}",
        ] { assert!(read_request(&mut request.as_bytes()).is_err(), "{request}"); }
        assert!(matches!(
            read_request(&mut &b"{\"command\":\"status\"}\n"[..]).unwrap(),
            Request::Status {}
        ));
        assert!(read_request(&mut &vec![b' '; MAX_REQUEST + 1][..]).is_err());
    }
    #[test]
    fn user_apk_cannot_impersonate_system_interface() {
        let xml = |path: &str| {
            format!("<packages><package name=\"ru.big.town.updater\" userId=\"10123\" codePath=\"{path}\"/></packages>")
        };
        assert_eq!(
            system_ui_uid(&xml("/system/priv-app/VoyahTuneUpdater")),
            Some(10123)
        );
        assert_eq!(system_ui_uid(&xml("/data/app/ru.big.town.updater")), None);
        assert_eq!(
            system_ui_uid(&xml("/system/priv-app/VoyahTuneUpdater-other")),
            None
        );
        assert_eq!(
            system_ui_uid(
                &xml("/system/priv-app/VoyahTuneUpdater").replace("userId", "sharedUserId")
            ),
            None
        );
        assert_eq!(system_ui_uid("<packages>"), None);
        let duplicate = xml("/system/priv-app/VoyahTuneUpdater").replace("</packages>",
            "<package name=\"ru.big.town.updater\" userId=\"10124\" codePath=\"/system/priv-app/VoyahTuneUpdater\"/></packages>");
        assert_eq!(system_ui_uid(&duplicate), None);
    }
    #[test]
    fn app_identity_requires_unique_uid_in_system_registry() {
        assert_eq!(
            ui_uid("ru.big.town.updater 10123 0 /data/user/0/ru.big.town.updater\nother 10124 0"),
            Some(10123)
        );
        assert_eq!(ui_uid("ru.big.town.updater 10123 0\nother 10123 0"), None);
        assert_eq!(ui_uid("ru.big.town.updater 1000 0"), None);
        assert_eq!(ui_uid("other 10123 0"), None);
        assert_eq!(
            ui_uid("ru.big.town.updater 10123 0\nru.big.town.updater 10124 0"),
            None
        );
    }
}
