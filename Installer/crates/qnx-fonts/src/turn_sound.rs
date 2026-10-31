use super::{execute_script_with_progress, Endpoint};
use release_core::{Error, Result};
use serde_json::{json, Value};

const SCRIPT: &str = include_str!("../../../../Packaging/new/install/turn_signal_sound.py");
const PREFIX: &str = "VOYAHTUNE_TURN_SOUND_RESULT ";

fn sounds(key: &str) -> Result<(&'static [u8], &'static [u8])> {
    let pair: (&[u8], &[u8]) = match key {
        "Alfa_Romeo" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Alfa_Romeo.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Alfa_Romeo.da.wav"),
        ),
        "Audi" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Audi.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Audi.da.wav"),
        ),
        "Aurus" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Aurus.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Aurus.da.wav"),
        ),
        "BMW" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/BMW.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/BMW.da.wav"),
        ),
        "Bentley" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Bentley.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Bentley.da.wav"),
        ),
        "Ferrari_ICE" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Ferrari_ICE.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Ferrari_ICE.da.wav"),
        ),
        "Ferrari_Luce" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Ferrari_Luce.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Ferrari_Luce.da.wav"),
        ),
        "Infiniti" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Infiniti.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Infiniti.da.wav"),
        ),
        "Land_Rover" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Land_Rover.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Land_Rover.da.wav"),
        ),
        "Lexus_LFA" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Lexus_LFA.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Lexus_LFA.da.wav"),
        ),
        "Lexus_LS" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Lexus_LS.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Lexus_LS.da.wav"),
        ),
        "Mercedes" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Mercedes.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Mercedes.da.wav"),
        ),
        "Mini_Cooper" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Mini_Cooper.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Mini_Cooper.da.wav"),
        ),
        "Porsche" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Porsche.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Porsche.da.wav"),
        ),
        "Range_Rover" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Range_Rover.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Range_Rover.da.wav"),
        ),
        "Tesla" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Tesla.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Tesla.da.wav"),
        ),
        "Toyota_Century" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Toyota_Century.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Toyota_Century.da.wav"),
        ),
        "Volvo" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Volvo.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Volvo.da.wav"),
        ),
        "Xiaomi_SU7" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/Xiaomi_SU7.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/Xiaomi_SU7.da.wav"),
        ),
        "original" => (
            include_bytes!("../../../../Packaging/new/turn-sounds/original.di.wav"),
            include_bytes!("../../../../Packaging/new/turn-sounds/original.da.wav"),
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
