use serde::Deserialize;
use std::io::{self, BufRead};

pub const MAX_REQUEST: usize = 8192;
pub const SOCKET_NAME: &str = "voyahtune_updater";

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
    ApplicationLog {
        action: crate::application_log::Action,
        app_version: Option<String>,
    },
    Check {
        #[serde(default)]
        same_version: bool,
    },
    SelectRelease {
        version: String,
    },
    Download {},
    Apply {},
    Finish {
        #[serde(default)]
        reset_errors: bool,
    },
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

pub fn package_uid(packages: &str, package: &str) -> Option<u32> {
    let rows: Vec<_> = packages
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            Some((parts.next()?, parts.next()?.parse::<u32>().ok()?))
        })
        .collect();
    let matches: Vec<_> = rows.iter().filter(|(name, _)| *name == package).collect();
    if matches.len() != 1 {
        return None;
    }
    let uid = matches[0].1;
    if uid < 10000 || rows.iter().filter(|(_, id)| *id == uid).count() != 1 {
        return None;
    }
    Some(uid)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn application_log_has_no_external_path_or_upload_destination() {
        let request = read_request(&mut &b"{\"command\":\"application_log\",\"action\":\"upload\",\"app_version\":\"4.3.3-od\"}\n"[..]).unwrap();
        assert!(!crate::health::root_request_allowed(&request));
        assert!(matches!(
            request,
            Request::ApplicationLog {
                action: crate::application_log::Action::Upload,
                ..
            }
        ));
        for input in [
            "{\"command\":\"application_log\",\"action\":\"upload\",\"path\":\"/data/secret\"}\n",
            "{\"command\":\"application_log\",\"action\":\"upload\",\"url\":\"https://example.org\"}\n",
            "{\"command\":\"application_log\",\"action\":\"shell\"}\n",
        ] {
            assert!(read_request(&mut input.as_bytes()).is_err());
        }
    }
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
    fn finish_resets_errors_only_when_explicitly_requested() {
        for (input, expected) in [
            ("{\"command\":\"finish\"}\n", false),
            ("{\"command\":\"finish\",\"reset_errors\":true}\n", true),
        ] {
            match read_request(&mut input.as_bytes()).unwrap() {
                Request::Finish { reset_errors } => assert_eq!(reset_errors, expected),
                _ => panic!("Unexpected command"),
            }
        }
        assert!(
            read_request(&mut &b"{\"command\":\"finish\",\"reset_errors\":\"yes\"}\n"[..]).is_err()
        );
    }

    #[test]
    fn app_identity_requires_unique_uid_in_system_registry() {
        assert_eq!(
            package_uid("ru.big.town.restoremode 10123 0 /data/user/0/ru.big.town.restoremode\nother 10124 0", "ru.big.town.restoremode"),
            Some(10123)
        );
        assert_eq!(
            package_uid(
                "ru.big.town.restoremode 10123 0\nother 10123 0",
                "ru.big.town.restoremode"
            ),
            None
        );
        assert_eq!(
            package_uid("ru.big.town.restoremode 1000 0", "ru.big.town.restoremode"),
            None
        );
        assert_eq!(
            package_uid("other 10123 0", "ru.big.town.restoremode"),
            None
        );
        assert_eq!(
            package_uid(
                "ru.big.town.restoremode 10123 0\nru.big.town.restoremode 10124 0",
                "ru.big.town.restoremode"
            ),
            None
        );
    }
}
