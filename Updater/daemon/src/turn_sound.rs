use crate::{config::invalid, device};
use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};
use serde_json::{json, Value};
use std::io;

fn supported() -> io::Result<bool> {
    let properties = device::command("/system/bin/sh", &["-c", PROPERTIES_COMMAND], 20)?;
    if properties.trim().is_empty() {
        return Err(invalid("Не удалось определить модель автомобиля"));
    }
    Ok(VehicleModel::detect(&properties) != VehicleModel::Other)
}

pub fn restore() -> io::Result<()> {
    if supported()? {
        qnx_fonts::turn_sound::on_device("restore", "original", 100)
            .map_err(|error| invalid(&error.to_string()))?;
    }
    Ok(())
}

pub fn call(
    action: &str,
    sound: &str,
    volume: u8,
    progress: &mut dyn FnMut(&str),
) -> io::Result<Value> {
    if !supported()? {
        return Err(invalid("Смена звука доступна только для SE и Sport+"));
    }
    let result = qnx_fonts::turn_sound::run_with_progress(
        &qnx_fonts::Endpoint::Tcp(([172, 16, 104, 41], 8000).into()),
        action,
        sound,
        volume,
        progress,
    )
    .map_err(|error| invalid(&error.to_string()))?;
    Ok(json!({"schema":1,"ok":true,"turnSound":result}))
}
