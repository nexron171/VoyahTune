use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};

pub const STABILITY_WINDOW: Duration = Duration::from_secs(10);
pub const RUNTIME_READINESS_SCHEMA: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessIdentity {
    pub pid: u32,
    pub start_ticks: String,
}

impl ProcessIdentity {
    fn valid(&self, absent_allowed: bool) -> bool {
        if self.pid == 0 {
            return absent_allowed && self.start_ticks.is_empty();
        }
        self.start_ticks.parse::<u64>().is_ok_and(|ticks| ticks > 0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeReadiness {
    pub schema: u32,
    pub boot: String,
    pub loader: ProcessIdentity,
    pub native_id: String,
    pub runtime_id: String,
    pub required_changes: u64,
    pub targets: BTreeMap<String, ProcessIdentity>,
}

impl RuntimeReadiness {
    pub fn parse(text: &str) -> Result<Self> {
        let value: Self = serde_json::from_str(text).map_err(|error| {
            Error::new("RUNTIME_READINESS_INVALID", "Некорректный ответ загрузчика").detail(error)
        })?;
        if value.schema != RUNTIME_READINESS_SCHEMA
            || value.boot.is_empty()
            || value.native_id.is_empty()
            || value.runtime_id.is_empty()
            || !value.loader.valid(false)
            || value.targets.is_empty()
            || value
                .targets
                .iter()
                .any(|(name, identity)| name.is_empty() || !identity.valid(true))
        {
            return Err(Error::new(
                "RUNTIME_READINESS_INVALID",
                "Загрузчик не подтвердил идентичность runtime",
            ));
        }
        Ok(value)
    }

    pub fn generation(&self) -> (String, String, String) {
        (
            self.boot.clone(),
            self.native_id.clone(),
            self.runtime_id.clone(),
        )
    }
}

#[derive(Default)]
pub struct Stability {
    identity: String,
    since: Option<Instant>,
}

impl Stability {
    pub fn observe(&mut self, identity: &str, now: Instant) -> bool {
        if self.identity != identity || self.since.is_none() {
            self.identity = identity.into();
            self.since = Some(now);
        }
        now.duration_since(self.since.unwrap()) >= STABILITY_WINDOW
    }

    pub fn reset(&mut self) {
        self.since = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};

    fn response() -> Value {
        json!({"schema":1,"boot":"boot-1","loader":{"pid":10,"startTicks":"100"},
            "nativeId":"native","runtimeId":"java","requiredChanges":0,"targets":{
                "acc":{"pid":20,"startTicks":"200"},"phone":{"pid":0,"startTicks":""}}})
    }

    #[test]
    fn accepts_current_processes_and_absent_targets() {
        let snapshot = RuntimeReadiness::parse(&response().to_string()).unwrap();
        assert_eq!(snapshot.targets["phone"].pid, 0);
        assert_eq!(
            snapshot.generation(),
            ("boot-1".into(), "native".into(), "java".into())
        );
    }

    #[test]
    fn rejects_missing_or_malformed_process_identity() {
        for (field, value) in [
            ("schema", json!(2)),
            ("boot", json!("")),
            ("nativeId", json!("")),
            ("runtimeId", json!("")),
            ("loader", json!({"pid":0,"startTicks":""})),
            ("targets", json!({})),
            ("targets", json!({"acc":{"pid":0,"startTicks":"200"}})),
            ("targets", json!({"acc":{"pid":20,"startTicks":""}})),
            ("targets", json!({"acc":{"pid":20,"startTicks":"invalid"}})),
        ] {
            let mut snapshot = response();
            snapshot[field] = value;
            assert!(
                RuntimeReadiness::parse(&snapshot.to_string()).is_err(),
                "{snapshot}"
            );
        }
        assert!(RuntimeReadiness::parse("{}").is_err());
        assert!(RuntimeReadiness::parse("not JSON").is_err());
    }

    #[test]
    fn one_window_observes_loader_targets_and_services_together() {
        let now = Instant::now();
        let mut stability = Stability::default();
        let first = RuntimeReadiness::parse(&response().to_string()).unwrap();
        let key = |runtime: &RuntimeReadiness| {
            format!(
                "android/services\n{}",
                serde_json::to_string(runtime).unwrap()
            )
        };
        assert!(!stability.observe(&key(&first), now));
        assert!(stability.observe(&key(&first), now + STABILITY_WINDOW));
        let mut restarted = first.clone();
        restarted.loader.start_ticks = "300".into();
        assert!(!stability.observe(&key(&restarted), now + STABILITY_WINDOW));
        assert!(stability.observe(&key(&restarted), now + STABILITY_WINDOW * 2));
        restarted.targets.get_mut("acc").unwrap().start_ticks = "400".into();
        assert!(!stability.observe(&key(&restarted), now + STABILITY_WINDOW * 2));
        restarted = first.clone();
        restarted.required_changes = 2;
        assert!(!stability.observe(&key(&restarted), now + STABILITY_WINDOW * 3));
        stability.reset();
        assert!(!stability.observe(&key(&restarted), now + STABILITY_WINDOW * 4));
    }

    #[test]
    fn repeated_endpoint_identity_does_not_hide_intermediate_changes() {
        let now = Instant::now();
        let mut stability = Stability::default();
        let first = RuntimeReadiness::parse(&response().to_string()).unwrap();
        assert!(!stability.observe(&serde_json::to_string(&first).unwrap(), now));
        let mut repeated = first.clone();
        repeated.required_changes += 2;
        assert_eq!(repeated.generation(), first.generation());
        assert!(!stability.observe(
            &serde_json::to_string(&repeated).unwrap(),
            now + STABILITY_WINDOW,
        ));
        assert!(stability.observe(
            &serde_json::to_string(&repeated).unwrap(),
            now + STABILITY_WINDOW * 2,
        ));
    }
}
