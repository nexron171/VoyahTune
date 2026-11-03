use crate::config::invalid;
use serde_json::{json, Value};
use std::io;

pub fn restore() -> io::Result<()> {
    qnx_fonts::turn_sound::on_device("restore", "original", 100)
        .map_err(|error| invalid(&error.to_string()))?;
    Ok(())
}

pub fn call(
    action: &str,
    sound: &str,
    volume: u8,
    progress: &mut dyn FnMut(&str),
) -> io::Result<Value> {
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
