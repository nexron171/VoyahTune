use super::*;
use release_core::catalog::UpdateCatalog;

fn selected_runtime(installed: &str, target: &str, minimum: &str) -> Shared {
    let catalog = serde_json::from_value::<UpdateCatalog>(serde_json::json!({
        "releases": [{
            "version": target,
            "url": "https://example.org/payload.zip",
            "size": 1,
            "sha256": "a".repeat(64),
            "minimumInstallerVersion": "1.7.11",
            "minimumOtaVersion": minimum
        }]
    }))
    .unwrap()
    .resolve()
    .unwrap();
    let mut state = State::fresh(installed.into(), "test-firmware".into());
    state.selected = catalog.releases.into_iter().next();
    state.same_version = true;
    Arc::new(Mutex::new(Runtime {
        config: Ok(Config::default()),
        state,
    }))
}

#[test]
fn persisted_selection_is_rechecked_before_download_and_apply() {
    for phase in ["idle", "verified"] {
        let below_minimum = selected_runtime("4.1.0", "4.2.0", "4.2.0");
        below_minimum.lock().unwrap().state.phase = phase.into();
        assert!(selected(&below_minimum)
            .unwrap_err()
            .to_string()
            .contains("OTA_USB_REQUIRED"));

        let downgrade = selected_runtime("4.2.0", "4.1.0", "4.0.0");
        downgrade.lock().unwrap().state.phase = phase.into();
        assert!(selected(&downgrade).is_ok());
    }
}
