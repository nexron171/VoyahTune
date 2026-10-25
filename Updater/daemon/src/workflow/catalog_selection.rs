use release_core::{
    catalog::{Catalog, Release},
    infrastructure::Infrastructure,
    ota, Result,
};

pub(super) fn same_release_version(left: &str, right: &str) -> bool {
    let parse = release_core::infrastructure::release_version;
    matches!((parse(left), parse(right)), (Ok(left), Ok(right)) if left == right)
}

pub(super) fn is_newer_release_version(candidate: &str, installed: &str) -> bool {
    let parse = release_core::infrastructure::release_version;
    matches!(
        (parse(candidate), parse(installed)),
        (Ok(candidate), Ok(installed)) if candidate.cmp_precedence(&installed).is_gt()
    )
}

pub(super) fn verify_selection(
    release: &Release,
    installed: &str,
    same_version: bool,
    infrastructure: Infrastructure,
) -> Result<ota::Claims> {
    if infrastructure == Infrastructure::Unified {
        if !infrastructure.matches_version(&release.version) {
            return Err(release_core::Error::new(
                "INFRASTRUCTURE_MISMATCH",
                "Выберите общий релиз VoyahTune",
            ));
        }
        crate::od_release::select(release, installed)?;
        return ota::verify(release);
    }
    if infrastructure == Infrastructure::Od {
        return crate::od_release::select(release, installed);
    }
    let claims = ota::verify(release)?;
    ota::compatible(&claims, installed, same_version)?;
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
        infrastructure.matches_version(&release.version)
            && (infrastructure.uses_restore_ui() || release.channel == "stable")
            && (!same_version || same_release_version(&release.version, installed))
    }) {
        if infrastructure.uses_restore_ui() {
            verify_selection(release, installed, same_version, infrastructure)?;
            return Ok(Some(release.clone()));
        }
        let compatibility = ota::verify(release)
            .and_then(|claims| ota::compatible(&claims, installed, same_version));
        if let Err(error) = compatibility {
            on_rejected(&error);
            continue;
        }

        return Ok(Some(release.clone()));
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use release_core::catalog::UpdateCatalog;

    #[test]
    fn notification_requires_a_newer_release_number() {
        for (candidate, installed, expected) in [
            ("4.3.0", "4.4.0", false),
            ("4.4.0", "4.4.0", false),
            ("4.4.1", "4.4.0", true),
            ("4.10.0", "4.9.0", true),
            ("4.9.0", "4.10.0", false),
            ("5.0.0", "4.9.9", true),
        ] {
            assert_eq!(
                is_newer_release_version(candidate, installed),
                expected,
                "{installed} -> {candidate}"
            );
        }
    }

    #[test]
    fn notification_ignores_infrastructure_suffixes_and_build_metadata() {
        for (candidate, installed, expected) in [
            ("4.4.0", "4.4.0-od", false),
            ("4.4.0", "4.4.0-pi", false),
            ("4.4.0-od", "4.4.0", false),
            ("4.4.0+2", "4.4.0+1", false),
            ("4.4.0+2", "4.4.0-od+1", false),
            ("4.4.1", "4.4.0-od", true),
            ("4.4.1-od", "4.4.0-od", true),
        ] {
            assert_eq!(
                is_newer_release_version(candidate, installed),
                expected,
                "{installed} -> {candidate}"
            );
        }
    }

    #[test]
    fn notification_respects_prerelease_precedence() {
        for (candidate, installed, expected) in [
            ("4.5.0-beta", "4.4.0", true),
            ("4.4.0-beta", "4.4.0", false),
            ("4.4.0", "4.4.0-beta", true),
            ("4.4.0-beta.10", "4.4.0-beta.2", true),
            ("4.4.0-beta.2", "4.4.0-beta.10", false),
            ("4.4.0-beta", "4.4.0-beta-od", false),
            ("4.4.0-od", "4.4.0-beta-od", true),
        ] {
            assert_eq!(
                is_newer_release_version(candidate, installed),
                expected,
                "{installed} -> {candidate}"
            );
        }
    }

    #[test]
    fn notification_requires_valid_release_versions() {
        for (candidate, installed) in [
            ("invalid", "4.4.0"),
            ("4.5.0", "invalid"),
            ("", "4.4.0"),
            ("4.5.0", ""),
        ] {
            assert!(!is_newer_release_version(candidate, installed));
        }
    }

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
    fn explicit_selection_only_checks_the_ota_minimum_for_od() {
        let catalog = catalog();
        let release = &catalog.releases[0];
        assert_eq!(release.version, "4.1.0-od");
        assert_eq!(
            verify_selection(release, "4.0.0-od", false, Infrastructure::Od)
                .unwrap_err()
                .code,
            "OTA_USB_REQUIRED"
        );
        assert!(verify_selection(release, "4.2.0-od", true, Infrastructure::Od).is_ok());
        assert!(verify_selection(release, "4.1.0-od", true, Infrastructure::Od).is_ok());
        assert!(verify_selection(release, "4.1.0-od", false, Infrastructure::Od).is_ok());

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
                .is_some()
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

    #[test]
    fn common_catalog_requires_usb_once_and_ignores_profiled_releases() {
        let catalog = serde_json::from_value::<UpdateCatalog>(serde_json::json!({"releases": [
            {"version":"4.5.0", "url":"https://example.org/common.zip", "size":1,
                "sha256":"a".repeat(64), "minimumInstallerVersion":"1.8.1", "minimumOtaVersion":"4.4.0"},
            {"version":"9.0.0-od", "url":"https://example.org/od.zip", "size":1,
                "sha256":"b".repeat(64), "minimumInstallerVersion":"1.7.7", "minimumOtaVersion":"4.0.0"},
            {"version":"9.0.0-pi", "url":"https://example.org/pi.zip", "size":1,
                "sha256":"c".repeat(64)}
        ]})).unwrap().resolve().unwrap();
        for installed in ["4.3.0-od", "4.3.0-pi"] {
            assert_eq!(
                choose(&catalog, installed, false, Infrastructure::Unified, |_| {})
                    .unwrap_err()
                    .code,
                "OTA_USB_REQUIRED"
            );
        }
        for installed in ["4.4.0", "4.4.0-od", "4.4.0-pi"] {
            let release = choose(&catalog, installed, false, Infrastructure::Unified, |_| {})
                .unwrap()
                .unwrap();
            assert_eq!(release.version, "4.5.0");
            assert_eq!(
                verify_selection(&release, installed, false, Infrastructure::Unified)
                    .unwrap()
                    .archive_sha256,
                "a".repeat(64)
            );
        }
        assert_eq!(
            verify_selection(
                &catalog.releases[0],
                "4.4.0",
                false,
                Infrastructure::Unified
            )
            .unwrap_err()
            .code,
            "INFRASTRUCTURE_MISMATCH"
        );
    }

    #[test]
    fn common_beta_can_be_reinstalled_and_rejects_corrupt_catalog_hash() {
        let mut catalog = catalog();
        catalog.releases[0].version = "4.5.0-beta".into();
        catalog.releases[0].channel = "prerelease".into();
        catalog.releases[0].minimum_ota_version = Some("4.4.0".into());
        catalog.validate().unwrap();
        let selected = choose(
            &catalog,
            "4.5.0-beta",
            true,
            Infrastructure::Unified,
            |_| {},
        )
        .unwrap()
        .unwrap();
        assert_eq!(selected.version, "4.5.0-beta");
        catalog.releases[0].payload.sha256 = "invalid".into();
        assert!(choose(&catalog, "4.4.0", false, Infrastructure::Unified, |_| {}).is_err());
    }
}
