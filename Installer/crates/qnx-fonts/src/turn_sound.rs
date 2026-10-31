use super::{execute_script_with_progress, Endpoint};
use release_core::{Error, Result};
use serde_json::{json, Value};

const SCRIPT: &str = include_str!("../../../../Packaging/new/install/turn_signal_sound.py");
const PREFIX: &str = "VOYAHTUNE_TURN_SOUND_RESULT ";

fn sounds(key: &str) -> Result<(&'static [u8], &'static [u8])> {
    let pair: (&[u8], &[u8]) = match key {
        "Alfa_Romeo" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_alfa_romeo_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_alfa_romeo_tock.wav"),
        ),
        "Audi" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_audi_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_audi_tock.wav"),
        ),
        "BMW" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_bmw_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_bmw_tock.wav"),
        ),
        "Ferrari_Luce" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_ferrari_luce_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_ferrari_luce_tock.wav"),
        ),
        "Land_Rover" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_land_rover_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_land_rover_tock.wav"),
        ),
        "Lexus_LFA" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_lexus_lfa_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_lexus_lfa_tock.wav"),
        ),
        "Lexus_LS" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_lexus_ls_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_lexus_ls_tock.wav"),
        ),
        "Mercedes" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_mercedes_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_mercedes_tock.wav"),
        ),
        "Mini_Cooper" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_mini_cooper_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_mini_cooper_tock.wav"),
        ),
        "Porsche" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_porsche_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_porsche_tock.wav"),
        ),
        "Volvo" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_volvo_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_volvo_tock.wav"),
        ),
        "Xiaomi_SU7" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_xiaomi_su7_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_xiaomi_su7_tock.wav"),
        ),
        "original" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_original_tick.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/turn_signal_original_tock.wav"),
        ),
        _ => {
            return Err(Error::new(
                "TURN_SOUND_SELECTION",
                "Неизвестный звук поворотников",
            ))
        }
    };
    Ok(pair)
}

pub fn run(endpoint: &Endpoint<'_>, action: &str, sound: &str, volume: u8) -> Result<Value> {
    run_with_progress(endpoint, action, sound, volume, &mut |_| {})
}

pub fn run_with_progress(
    endpoint: &Endpoint<'_>,
    action: &str,
    sound: &str,
    volume: u8,
    progress: &mut dyn FnMut(&str),
) -> Result<Value> {
    if !["status", "apply", "restore"].contains(&action) {
        return Err(Error::new(
            "TURN_SOUND_ACTION",
            "Неизвестная операция со звуком",
        ));
    }
    let mut request = json!({"action":action});
    if action == "apply" {
        if !(15..=100).contains(&volume) {
            return Err(Error::new(
                "TURN_SOUND_VOLUME",
                "Громкость должна быть от 15 до 100%",
            ));
        }
        let (di, da) = sounds(sound)?;
        request["sound"] = json!(sound);
        request["volume"] = json!(volume);
        request["sounds"] = json!({"di":hex::encode(di),"da":hex::encode(da)});
    }
    let script = format!(
        "import json\nREQUEST = json.loads(bytes.fromhex('{}'))\n{SCRIPT}",
        hex::encode(request.to_string())
    );
    progress("connecting");
    let output = execute_script_with_progress(endpoint, script.as_bytes(), &[], &mut |line| {
        if let Some(data) = line.trim().strip_prefix("VOYAHTUNE_TURN_SOUND_PROGRESS ") {
            if let Ok(event) = serde_json::from_str::<Value>(data) {
                if let Some(stage) = event["stage"].as_str() {
                    progress(stage);
                }
            }
        }
    })?;
    let report = parse_result(&output, action)?;
    if action == "apply" && (report["sound"] != sound || report["volume"] != volume) {
        return Err(Error::new(
            "TURN_SOUND_SELECTION",
            "Приборка подтвердила другой звук или громкость",
        ));
    }
    Ok(report)
}

pub fn on_device(action: &str, sound: &str, volume: u8) -> Result<Value> {
    run(
        &Endpoint::Tcp(([172, 16, 104, 41], 8000).into()),
        action,
        sound,
        volume,
    )
}

fn parse_result(output: &str, action: &str) -> Result<Value> {
    let reports: Vec<_> = output
        .lines()
        .filter_map(|line| line.trim().strip_prefix(PREFIX))
        .collect();
    if reports.len() == 1 {
        let report: Value = serde_json::from_str(reports[0])?;
        if report["ok"] == true
            && report["action"] == action
            && report["enabled"].is_boolean()
            && (action == "status" || report["enabled"] == (action == "apply"))
        {
            return Ok(report);
        }
    }
    Err(Error::new(
        "TURN_SOUND_FAILED",
        "Приборка не подтвердила изменение звука поворотников",
    )
    .detail(output))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requires_completed_operation_not_launcher_acknowledgement() {
        assert!(parse_result("OK 42", "restore").is_err());
        let output = format!(
            "{PREFIX}{}",
            json!({"ok":true,"action":"restore","enabled":false})
        );
        assert!(parse_result(&output, "restore").is_ok());
        assert!(parse_result(&output, "apply").is_err());
        let wrong_state = format!(
            "{PREFIX}{}",
            json!({"ok":true,"action":"restore","enabled":true})
        );
        assert!(parse_result(&wrong_state, "restore").is_err());
        assert!(parse_result(&format!("{output}\n{output}"), "restore").is_err());
        assert!(sounds("../bad").is_err());
        assert!(sounds("original").is_ok());
    }
}
