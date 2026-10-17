use release_core::{
    catalog::{Catalog, Release},
    infrastructure::Infrastructure,
    ota, Result,
};

pub(super) fn same_release_version(left: &str, right: &str) -> bool {
    let parse = release_core::infrastructure::release_version;
    matches!((parse(left), parse(right)), (Ok(left), Ok(right)) if left == right)
}

fn require_ota_version(
    release: &Release,
    installed: &str,
    infrastructure: Infrastructure,
) -> Result<()> {
    if infrastructure == Infrastructure::Od {
        release.require_ota_version(installed)?;
    }
    Ok(())
}

pub(super) fn verify_selection(
    release: &Release,
    installed: &str,
    same_version: bool,
    infrastructure: Infrastructure,
) -> Result<ota::Claims> {
    let claims = ota::verify(release)?;
    ota::compatible(&claims, installed, same_version)?;
    require_ota_version(release, installed, infrastructure)?;
    Ok(claims)
}

pub(super) fn choose(
    catalog: &Catalog,
    installed: &str,
    same_version: bool,
    infrastructure: Infrastructure,
    mut on_rejected: impl FnMut(&release_core::Error),
) -> Result<Option<Release>> {
    for release in catalog.ota_releases().filter(|release| {
        Infrastructure::from_version(&release.version).is_ok_and(|value| value == infrastructure)
            && (infrastructure == Infrastructure::Od || release.channel == "stable")
            && (!same_version || same_release_version(&release.version, installed))
    }) {
        let compatibility = ota::verify(release)
            .and_then(|claims| ota::compatible(&claims, installed, same_version));
        if let Err(error) = compatibility {
            on_rejected(&error);
            continue;
        }

        require_ota_version(release, installed, infrastructure)?;
        return Ok(Some(release.clone()));
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use release_core::catalog::UpdateCatalog;

    fn catalog() -> Catalog {
        serde_json::from_value::<UpdateCatalog>(serde_json::json!({"releases": [
            {"version":"4.0.1-od", "url":"https://example.org/4.0.1.zip", "size":1,
                "sha256":"a".repeat(64), "minimumInstallerVersion":"1.7.0", "minimumOtaVersion":"4.0.0"},
            {"version":"4.1.0-od", "url":"https://example.org/4.1.0.zip", "size":1,
                "sha256":"b".repeat(64), "minimumInstallerVersion":"1.7.0", "minimumOtaVersion":"4.1.0"}
        ]})).unwrap().resolve().unwrap()
    }

    #[test]
    fn newest_usb_release_does_not_fall_back_to_an_older_release() {
        let error = choose(&catalog(), "4.0.0-od", false, Infrastructure::Od, |_| {}).unwrap_err();
        assert_eq!(error.code, "OTA_USB_REQUIRED");
    }

    #[test]
    fn explicit_selection_rejects_downgrades_and_checks_the_ota_minimum() {
        let catalog = catalog();
        let release = &catalog.releases[0];
        assert_eq!(release.version, "4.1.0-od");
        assert_eq!(
            verify_selection(release, "4.0.0-od", false, Infrastructure::Od)
                .unwrap_err()
                .code,
            "OTA_USB_REQUIRED"
        );
        assert!(verify_selection(release, "4.2.0-od", true, Infrastructure::Od).is_err());
        assert!(verify_selection(release, "4.1.0-od", true, Infrastructure::Od).is_ok());
        assert!(verify_selection(release, "4.1.0-od", false, Infrastructure::Od).is_err());

        let mut rebuilt = release.clone();
        rebuilt.payload.sha256 = "c".repeat(64);
        assert!(verify_selection(&rebuilt, "4.1.0-od", true, Infrastructure::Od).is_ok());
        assert!(verify_selection(&rebuilt, "4.1.0", true, Infrastructure::Od).is_ok());
        rebuilt.minimum_ota_version = Some("4.0.0".into());
        assert!(verify_selection(&rebuilt, "4.0.0-od", false, Infrastructure::Od).is_ok());
        rebuilt.minimum_ota_version = Some("4.2.0".into());
        assert_eq!(
            verify_selection(&rebuilt, "4.1.0-od", true, Infrastructure::Od)
                .unwrap_err()
                .code,
            "OTA_USB_REQUIRED"
        );
    }

    #[test]
    fn current_release_can_be_reinstalled_without_selecting_an_older_release() {
        assert!(
            choose(&catalog(), "4.1.0-od", true, Infrastructure::Od, |_| {})
                .unwrap()
                .is_some()
        );
        assert!(
            choose(&catalog(), "4.2.0-od", true, Infrastructure::Od, |_| {})
                .unwrap()
                .is_none()
        );
        assert!(same_release_version("4.1.0", "4.1.0-od"));
        assert!(!same_release_version("4.1.0-beta-od", "4.1.0-od"));
    }

    #[test]
    fn od_catalog_includes_beta_and_can_reinstall_it() {
        let mut catalog = catalog();
        catalog.releases[0].version = "4.1.0-beta-od".into();
        catalog.releases[0].channel = "prerelease".into();
        catalog.releases[0].minimum_ota_version = Some("4.0.0".into());
        catalog.validate().unwrap();
        let selected = choose(&catalog, "4.0.0-od", false, Infrastructure::Od, |_| {})
            .unwrap()
            .unwrap();
        assert_eq!(selected.version, "4.1.0-beta-od");
        let same = choose(&catalog, "4.1.0-beta-od", true, Infrastructure::Od, |_| {})
            .unwrap()
            .unwrap();
        assert_eq!(same.version, "4.1.0-beta-od");
        assert!(
            choose(&catalog, "4.1.0-od", false, Infrastructure::Od, |_| {})
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn pi_still_excludes_prereleases() {
        let mut catalog = catalog();
        catalog.releases[0].version = "4.1.0-beta-pi".into();
        catalog.releases[0].channel = "prerelease".into();
        catalog.validate().unwrap();
        assert!(
            choose(&catalog, "4.0.0-pi", false, Infrastructure::Pi, |_| {})
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn pi_keeps_its_previous_selection_policy() {
        let mut catalog = catalog();
        for release in &mut catalog.releases {
            release.version = release.version.replace("-od", "-pi");
        }
        let selected = choose(&catalog, "4.0.0-pi", false, Infrastructure::Pi, |_| {})
            .unwrap()
            .unwrap();
        assert_eq!(selected.version, "4.1.0-pi");
    }
}
