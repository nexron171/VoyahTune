use crate::{adb::Adb, plans::Action, Error, Result};
use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};
use serde::{Deserialize, Serialize};
use std::time::Duration;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct InstallOptions {
    pub fake_account: bool,
    pub disable_chinese_navigation: bool,
}

impl InstallOptions {
    pub fn any(self) -> bool {
        self.fake_account || self.disable_chinese_navigation
    }

    pub fn validate(self, action: Action, available: bool) -> Result<()> {
        if self.any() && (action != Action::Install || !available) {
            return Err(Error::new(
                "VEHICLE_OPTIONS_UNAVAILABLE",
                "Fake аккаунт и отключение китайской навигации доступны только при установке на автомобили, отличные от H97X/Y",
            ));
        }
        Ok(())
    }
}

pub fn available(adb: &Adb) -> Result<bool> {
    let properties = adb.read(PROPERTIES_COMMAND)?;
    if properties.trim().is_empty() {
        return Err(Error::new(
            "DEVICE_MODEL_UNKNOWN",
            "Не удалось определить модель для дополнительных настроек автомобиля",
        ));
    }
    Ok(VehicleModel::detect(&properties) == VehicleModel::Other)
}

pub(crate) fn activate_fake_account(adb: &Adb) -> Result<()> {
    perform(adb, "fake-account")
}

pub(crate) fn disable_chinese_navigation(adb: &Adb) -> Result<()> {
    perform(adb, "disable-navigation")
}

fn perform(adb: &Adb, action: &str) -> Result<()> {
    let script = format!(
        "action={}\n{}",
        crate::adb::quote(action),
        include_str!("vehicle-options-device.sh")
    );
    let output = adb.shell(&script, Duration::MAX)?;
    adb.events.emit(
        "vehicle-option-completed",
        adb.step.as_deref(),
        output,
        serde_json::json!({"option": action}),
    )?;
    Ok(())
}

#[cfg(all(test, unix))]
#[path = "vehicle_options_tests.rs"]
mod tests;
