//! Runtime mode is diagnostic before install; every mode transition is allowed.
use crate::{adb::Adb, payload::Variant, Error, Result};
use serde::{Deserialize, Serialize};
pub const KEY: &str = "voyahtune_install_mode";
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CurrentMode {
    Absent,
    Light,
    Full,
    Unknown,
}

pub fn inspect(adb: &Adb) -> CurrentMode {
    match adb.read(&format!("settings get global {KEY}\n")).as_deref() {
        Ok("full") => CurrentMode::Full,
        Ok("light") => CurrentMode::Light,
        _ => CurrentMode::Unknown,
    }
}
pub fn commit(adb: &Adb, variant: Variant) -> Result<()> {
    adb.read(&format!("settings put global {KEY} {}\n", variant.name()))?;
    if adb.read(&format!("settings get global {KEY}\n"))? != variant.name() {
        return Err(Error::new(
            "MODE_WRITE_FAILED",
            "Режим приложения не подтвердился после записи. Повторите установку.",
        ));
    }
    Ok(())
}
pub fn clear(adb: &Adb) -> Result<()> {
    adb.read(&format!("settings delete global {KEY}\n"))?;
    let value = adb.read(&format!("settings get global {KEY}\n"))?;
    if value != "null" && !value.is_empty() {
        return Err(Error::new(
            "MODE_WRITE_FAILED",
            "Не удалось удалить флаг режима. Повторите удаление.",
        ));
    }
    Ok(())
}
