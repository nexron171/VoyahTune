use super::*;
use release_core::vehicle_model::{VehicleModel, PROPERTIES_COMMAND};

#[cfg(test)]
const RESULT_PREFIX: &str = "VOYAHTUNE_NAVIGATION_FONT_RESULT ";
#[cfg(test)]
use qnx_fonts::parse_result;

fn supported_model(properties: &str) -> Option<&'static str> {
    match VehicleModel::detect(properties) {
        VehicleModel::H97X => Some("H97X"),
        VehicleModel::H97Y => Some("H97Y"),
        VehicleModel::Other => None,
    }
}

impl Engine {
    pub(super) fn navigation_fonts(&self, action: &str) -> Result<()> {
        let properties = self.adb.read(PROPERTIES_COMMAND)?;
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
        let result = self.adb.qnx_navigation_fonts(action, model)?;
        recovery::write_json(
            &self
                .operation
                .dir
                .join(format!("navigation-fonts-{action}.json")),
            &result,
        )?;
        let skipped = result["skipped"] == true;
        self.adb.events.emit(
            if skipped {
                "diagnostic-warning"
            } else {
                "diagnostic"
            },
            self.adb.step.as_deref(),
            if skipped {
                "Версия тем приборки не поддерживается; шрифты оставлены без изменений"
            } else {
                "Проверены три темы приборки; цифровые поля сохранены"
            },
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

    #[test]
    fn unsupported_theme_reports_allow_installation_and_removal_to_continue() {
        for model in ["H97X", "H97Y"] {
            for action in ["inspect", "apply", "restore"] {
                let mut report = json!({
                    "ok": true,
                    "action": action,
                    "model": model,
                    "skipped": true,
                    "reason": "unsupported_theme",
                    "themes": [
                        {"path": "gauge_normal.kzb", "supported": true},
                        {"path": "gauge_classic.kzb", "supported": false,
                         "error": "Unsupported theme size"},
                        {"path": "gauge_elegant.kzb", "supported": true}
                    ]
                });
                let output = format!("{RESULT_PREFIX}{report}\n");
                let parsed = parse_result(&output, action, model).unwrap();
                assert_eq!(parsed["skipped"], true);
                assert_eq!(parsed["themes"][1]["supported"], false);

                report["ok"] = json!(false);
                let output = format!("{RESULT_PREFIX}{report}\n");
                assert!(parse_result(&output, action, model).is_err());
            }
        }
    }
}
