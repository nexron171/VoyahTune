//! Logcat selection for an active installation; never import historical buffers.
pub const BUFFERS: &[&str] = &[
    "-b",
    "main",
    "-b",
    "system",
    "-b",
    "crash",
    "-v",
    "threadtime",
];
pub const NOW_COMMAND: &str = "date '+%m-%d %H:%M:%S.%N' | cut -c1-18";

pub fn valid_boundary(value: &str) -> bool {
    value.len() == 18
        && value.bytes().enumerate().all(|(index, byte)| match index {
            2 => byte == b'-',
            5 => byte == b' ',
            8 | 11 => byte == b':',
            14 => byte == b'.',
            _ => byte.is_ascii_digit(),
        })
}

pub fn relevant(line: &str, since: &str) -> bool {
    let Some(timestamp) = line.get(..18).filter(|time| valid_boundary(time)) else {
        return false;
    };
    if timestamp <= since {
        return false;
    }
    let mut fields = line[18..].split_whitespace();
    let (_pid, _tid, level) = (fields.next(), fields.next(), fields.next());
    let tag = fields.collect::<Vec<_>>().join(" ");
    let tag = tag
        .split(':')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    let text = line.to_ascii_lowercase();
    let ours = [
        "voyahtune",
        "ru.big.town",
        "restoremode",
        "anative",
        "cunba",
        "frida",
        "voyahtweaks",
    ]
    .iter()
    .any(|name| text.contains(name));
    let installation = [
        "packagemanager",
        "packageinstaller",
        "installd",
        "dex2oat",
        "bootreceiver",
        "bootanimation",
    ]
    .iter()
    .any(|name| tag.starts_with(name));
    let crash = [
        "androidruntime",
        "debug",
        "tombstoned",
        "crash_dump",
        "watchdog",
    ]
    .iter()
    .any(|name| tag.starts_with(name));
    let critical_system = matches!(level, Some("E" | "F"))
        && matches!(
            tag.as_str(),
            "activitymanager" | "systemserver" | "zygote" | "init" | "libc"
        );
    let lifecycle = matches!(
        tag.as_str(),
        "activitymanager" | "systemserver" | "zygote" | "init"
    ) && [
        "boot",
        "system_server",
        "anr in",
        "fatal",
        "died",
        "killing",
    ]
    .iter()
    .any(|word| text.contains(word));
    let own_service =
        (tag.contains("$$$") || tag.starts_with("voyah") || tag.starts_with("native"))
            && !matches!(level, Some("D" | "V"));
    ours || own_service || installation || crash || critical_system || lifecycle
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn keeps_installation_and_crashes_but_not_history_or_can_noise() {
        let since = "10-16 12:00:00.000";
        for text in [
            "I PackageManager: installed",
            "E AndroidRuntime: FATAL EXCEPTION",
            "I ActivityManager: Start ru.big.town.anative",
            "I loader: frida failed",
            "E libc: fatal signal",
            "W ActivityManager: ANR in application",
        ] {
            assert!(
                relevant(&format!("10-16 12:00:01.000  10  11 {text}"), since),
                "{text}"
            );
        }
        for text in [
            "D CanBusService: speed",
            "E ais_v4l2_proxy: camera",
            "I auditd: denied",
            "D ADASManager: update",
        ] {
            assert!(!relevant(
                &format!("10-16 12:00:01.000  10  11 {text}"),
                since
            ));
        }
        assert!(!relevant(
            "10-16 11:59:00.000 10 11 E AndroidRuntime: old crash",
            since
        ));
        assert!(!relevant("--------- beginning of kernel", since));
        assert!(!valid_boundary("10-16 12:00:00.%N"));
    }
}
