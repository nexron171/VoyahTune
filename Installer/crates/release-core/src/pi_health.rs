use crate::{Error, Result};
use serde::Deserialize;
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub schema: u32,
    pub infrastructure: String,
    pub loader_pid: u32,
    pub loader_start_ticks: String,
    pub boot_id: String,
    pub updated_uptime_seconds: u64,
    pub state: String,
}
impl Status {
    pub fn validate(
        &self,
        boot: &str,
        uptime: u64,
        start_ticks: &str,
        cmdline: &[u8],
        service: &str,
    ) -> Result<()> {
        if self.schema != 1
            || self.infrastructure != "pi"
            || self.state != "running"
            || self.loader_pid == 0
            || self.boot_id != boot
            || self.loader_start_ticks != start_ticks
            || self.updated_uptime_seconds > uptime
            || uptime - self.updated_uptime_seconds > 15
            || cmdline.split(|b| *b == 0).next() != Some(b"/data/local/bin/loaderFrida".as_slice())
            || service != "running"
        {
            return Err(Error::new(
                "PI_LOADER_NOT_READY",
                "Не подтверждён живой PI watchdog текущей загрузки",
            ));
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn heartbeat_identity_and_executable_must_all_match() {
        let s: Status = serde_json::from_str(r#"{"schema":1,"infrastructure":"pi","loaderPid":42,"loaderStartTicks":"300","bootId":"boot","updatedUptimeSeconds":50,"state":"running"}"#).unwrap();
        assert!(s
            .validate(
                "boot",
                60,
                "300",
                b"/data/local/bin/loaderFrida\0",
                "running"
            )
            .is_ok());
        for (boot, now, start, cmd, service) in [
            (
                "old",
                60,
                "300",
                b"/data/local/bin/loaderFrida\0".as_slice(),
                "running",
            ),
            (
                "boot",
                66,
                "300",
                b"/data/local/bin/loaderFrida\0".as_slice(),
                "running",
            ),
            (
                "boot",
                49,
                "300",
                b"/data/local/bin/loaderFrida\0".as_slice(),
                "running",
            ),
            (
                "boot",
                60,
                "301",
                b"/data/local/bin/loaderFrida\0".as_slice(),
                "running",
            ),
            ("boot", 60, "300", b"another".as_slice(), "running"),
            (
                "boot",
                60,
                "300",
                b"/data/local/bin/loaderFrida\0".as_slice(),
                "stopped",
            ),
        ] {
            assert!(s.validate(boot, now, start, cmd, service).is_err());
        }
    }
}
