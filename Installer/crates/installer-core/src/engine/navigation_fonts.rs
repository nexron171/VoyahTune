use super::*;
use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};

const SCRIPT: &[u8] = include_bytes!("../../../../../Packaging/new/install/navigation_fonts.py");
const RESULT_PREFIX: &str = "VOYAHTUNE_NAVIGATION_FONT_RESULT ";

fn supported_model(properties: &str) -> Option<&'static str> {
    match VehicleModel::detect(properties) {
        VehicleModel::H97X => Some("H97X"),
        VehicleModel::H97Y => Some("H97Y"),
        VehicleModel::Other => None,
    }
}

fn parse_result(output: &str, action: &str, model: &str) -> Result<serde_json::Value> {
    let results: Vec<_> = output
        .lines()
        .filter_map(|line| line.trim().strip_prefix(RESULT_PREFIX))
        .collect();
    if results.len() != 1 {
        return Err(
            Error::new("QNX_FONT_RESULT", "Приборка не подтвердила обработку тем").detail(output),
        );
    }
    let result: serde_json::Value = serde_json::from_str(results[0])?;
    if result["ok"] != true
        || result["action"] != action
        || result["model"] != model
        || !result["themes"]
            .as_array()
            .is_some_and(|themes| themes.len() == 3)
    {
        return Err(Error::new(
            "QNX_FONT_FAILED",
            "Не удалось обработать шрифты навигации приборки",
        )
        .detail(result));
    }
    Ok(result)
}

impl Engine {
    pub(super) fn navigation_fonts(&self, action: &str) -> Result<()> {
        let properties = self.shell(PROPERTIES_COMMAND)?;
        if properties.trim().is_empty() {
            return Err(self.fail(
                "Не удалось определить модель автомобиля",
                "Empty model properties",
            ));
        }
        let Some(model) = supported_model(&properties) else {
            self.adb.events.emit(
                "diagnostic",
                self.adb.step.as_deref(),
                "Шрифты приборки: для этой модели изменений нет",
                json!({"skipped": true}),
            )?;
            return Ok(());
        };
        let output = self.adb.qnx_navigation_fonts(SCRIPT, action, model)?;
        let result = parse_result(&output, action, model)?;
        recovery::write_json(
            &self
                .operation
                .dir
                .join(format!("navigation-fonts-{action}.json")),
            &result,
        )?;
        self.adb.events.emit(
            "diagnostic",
            self.adb.step.as_deref(),
            "Проверены три темы приборки; цифровые поля сохранены",
            result,
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_h97x_and_h97y_use_the_qnx_font_operation() {
        assert_eq!(supported_model("H97XSA8155\nH97C"), Some("H97X"));
        assert_eq!(
            supported_model("jenkins-PRJ_H97Y_Platform_8155"),
            Some("H97Y")
        );
        for model in ["H97C", "H97A", "H56Y", "", "msmnile_gvmq"] {
            assert_eq!(supported_model(model), None);
        }
    }

    #[test]
    fn qconn_acknowledgement_is_not_a_successful_theme_transaction() {
        assert!(parse_result("OK 123\n", "apply", "H97X").is_err());
        let output = format!(
            "{RESULT_PREFIX}{}\n",
            json!({
                "ok": true, "action": "apply", "model": "H97X", "themes": [{},{},{}]
            })
        );
        assert!(parse_result(&output, "apply", "H97X").is_ok());
        assert!(parse_result(&output, "restore", "H97X").is_err());
        assert!(parse_result(&output, "apply", "H97Y").is_err());
        assert!(parse_result(&(output.clone() + &output), "apply", "H97X").is_err());
        assert!(
            parse_result(&format!("{RESULT_PREFIX}{{\"ok\":false}}"), "apply", "H97X").is_err()
        );
    }
}
