//! Application orchestration shared by the GUI and library integration tests.
use crate::{
    adb::Adb,
    events::Events,
    inventory,
    payload::Payload,
    plans::{self, Action, Dns, Plan},
    Result,
};
use std::{path::Path, time::Duration};

pub fn plan(
    adb_path: &Path,
    payload: &Payload,
    serial: &str,
    action: Action,
    dns: Dns,
) -> Result<Plan> {
    if action != Action::Remove && payload.manifest.removal_only {
        return Err(crate::Error::new(
            "PAYLOAD_REQUIRED",
            "Выберите релиз для установки",
        ));
    }
    payload.verify()?;
    let adb = Adb::new(adb_path, Events::quiet())?.with_device(serial)?;
    adb.require_single()?;
    for args in [&["root"][..], &["wait-for-device"][..], &["root"][..]] {
        let _ = adb.run(args, None, Duration::from_secs(20));
    }
    let plan = plans::plan(
        inventory::diagnose(&adb, payload, action),
        payload,
        action,
        dns,
    )?;
    Ok(plan)
}
