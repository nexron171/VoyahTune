use crate::{inventory::Inventory, payload::Payload, vehicle_options::InstallOptions, Result};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Install,
    Remove,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Dns {
    Keep,
    On,
    Off,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub action: Action,
    pub dns: Dns,
    #[serde(default)]
    pub options: InstallOptions,
    pub serial: String,
    pub inventory_token: String,
    pub confirmed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Step {
    pub id: String,
    pub title: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    #[serde(default)]
    pub oem_options_available: bool,
    #[serde(default)]
    pub installer_version: String,
    #[serde(default)]
    pub release_version: String,
    #[serde(default)]
    pub payload_sha256: String,
    #[serde(default)]
    pub recipe_sha256: String,
    #[serde(default)]
    pub recipe: Option<crate::recipe::Recipe>,
    pub request: Request,
    pub inventory: Inventory,
    pub operation: String,
    pub warnings: Vec<String>,
    pub steps: Vec<Step>,
}

impl Plan {
    pub fn set_options(&mut self, options: InstallOptions) -> Result<()> {
        options.validate(self.request.action, self.oem_options_available)?;
        self.request.options = options;
        self.steps = operation_steps(self.request.action, options);
        Ok(())
    }
}

/// Packages whose installed key differs from the selected release. Only our two
/// applications may be reset automatically; other package failures remain errors.
pub fn signature_resets(
    inventory: &Inventory,
    payload: &Payload,
    action: Action,
) -> Result<Vec<String>> {
    if action == Action::Remove {
        return Ok(Vec::new());
    }
    let mut resets = Vec::new();
    for (id, artifact) in [
        (crate::payload::NATIVE, "native.apk"),
        (crate::payload::RESTORE, "restore_mode.apk"),
    ] {
        let installed = inventory.packages.get(id);
        let base = if id == crate::payload::NATIVE {
            inventory.base_native.as_ref()
        } else {
            None
        };
        if installed.is_none() && base.is_none() {
            continue;
        }
        let expected = crate::payload::verified_signers(&payload.file(artifact)?)?;
        if installed
            .into_iter()
            .chain(base)
            .any(|p| !p.signers.is_empty() && p.signers != expected)
        {
            resets.push(id.to_owned());
        }
    }
    Ok(resets)
}

pub fn plan(inventory: Inventory, payload: &Payload, action: Action, dns: Dns) -> Result<Plan> {
    let mut warnings = vec!["Совпадение компонентов не означает проверку всех функций на этой прошивке. Автомобиль должен стоять на парковке; питание нельзя отключать до завершения.".into()];
    warnings.extend(inventory.problems.iter().cloned());
    let operation = if action == Action::Remove {
        "remove"
    } else {
        "install"
    };
    if action == Action::Remove {
        warnings.push("Будут удалены VoyahTune, его настройки и журналы; DNS будет восстановлен из сохранённого исходного состояния. Заводская прошивка и состояние загрузчика не восстанавливаются.".into());
    }
    if action == Action::Remove && payload.manifest.build_revision == "builtin-remover" {
        warnings.push("Используются встроенные правила удаления известных компонентов. Для релиза с новыми файлами откройте его payload ZIP перед удалением; неизвестный будущий формат требует обновления установщика.".into());
    }
    let resets = signature_resets(&inventory, payload, action).unwrap_or_default();
    if !resets.is_empty() {
        warnings.push(format!("Другой ключ подписи: {}. Эти приложения будут автоматически удалены вместе с настройками и данными, затем установлены заново.", resets.join(", ")));
    }
    let options = InstallOptions::default();
    let steps = operation_steps(action, options);
    use sha2::{Digest, Sha256};
    Ok(Plan {
        oem_options_available: false,
        installer_version: crate::compatibility::INSTALLER_VERSION.into(),
        release_version: payload.manifest.release_version.clone(),
        payload_sha256: payload.identity()?,
        recipe_sha256: hex::encode(Sha256::digest(serde_json::to_vec(&serde_json::to_value(
            &payload.manifest.recipe,
        )?)?)),
        recipe: Some(payload.manifest.recipe.clone()),
        request: Request {
            action,
            dns,
            options,
            serial: inventory.serial.clone(),
            inventory_token: inventory.token.clone(),
            confirmed: false,
        },
        inventory,
        operation: operation.into(),
        warnings,
        steps,
    })
}

/// Presentation boundaries must match every step emitted by Engine::execute.
fn operation_steps(action: Action, options: InstallOptions) -> Vec<Step> {
    let mut steps = vec![
        ("preflight", "Подготовка файлов релиза"),
        ("root", "Получение системного доступа"),
        ("updater-prepare", "Остановка OTA-службы и сохранение логов"),
        (
            "turn-sound-restore",
            "Восстановление штатного звука поворотников",
        ),
        ("navigation-fonts-check", "Проверка тем приборки"),
    ];
    steps.push(("system", "Подготовка системного раздела"));
    if action == Action::Install {
        steps.push((
            "third-party-loaders",
            "Проверка и отключение сторонних решений",
        ));
    }
    if action == Action::Remove {
        steps.extend([
            ("navigation-fonts", "Восстановление шрифтов приборки"),
            ("deactivate", "Отключение Apollo"),
            ("dns", "Восстановление DNS"),
            (
                "restore-logcat",
                "Восстановление оригинального init.logcat.sh",
            ),
            ("runtime", "Остановка и удаление boot-hook"),
            ("files", "Удаление hooks и временных файлов"),
            ("packages", "Удаление приложений"),
            ("reboot", "Перезагрузка автомобиля"),
            ("settings", "Очистка настроек после перезагрузки"),
        ]);
    } else {
        steps.extend([
            ("backup", "Сохранение файлов перед заменой"),
            ("signing-reset", "Переустановка при смене подписи"),
            ("runtime", "Остановка старых hooks"),
            ("apollo-migration", "Отключение старой активации Apollo"),
            ("files", "Установка файлов релиза"),
            ("boot-hooks", "Установка boot-hook"),
        ]);
        steps.extend([
            ("native", "Установка Native и разрешений"),
            ("packages", "Установка RestoreMode и настроек"),
            ("dns", "Настройка DNS"),
            ("updater-bootstrap", "Подготовка первого запуска OTA"),
        ]);
        if options.fake_account {
            steps.push(("fake-account", "Активация Fake аккаунта"));
        }
        steps.extend([
            ("navigation-fonts", "Шрифты навигации приборки"),
            ("reboot", "Перезагрузка автомобиля"),
            ("verify", "Проверка Native и готовности OTA"),
        ]);
    }
    steps
        .into_iter()
        .map(|(id, title)| Step {
            id: id.into(),
            title: title.into(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn installation_request_preserves_explicit_vehicle_choices() {
        let request: Request = serde_json::from_value(json!({
            "action": "install", "dns": "keep", "serial": "CAR-001",
            "inventoryToken": "token", "confirmed": true,
            "options": {"fakeAccount": true, "disableChineseNavigation": true}
        }))
        .unwrap();
        let saved = serde_json::to_value(request).unwrap();
        assert_eq!(saved["options"]["fakeAccount"], true);
        assert_eq!(saved["options"]["disableChineseNavigation"], true);
    }

    #[test]
    fn older_requests_leave_both_vehicle_options_disabled() {
        let request: Request = serde_json::from_value(json!({
            "action": "install", "dns": "keep", "serial": "CAR-001",
            "inventoryToken": "token", "confirmed": true
        }))
        .unwrap();
        assert_eq!(request.options, InstallOptions::default());
    }

    #[test]
    fn vehicle_choices_update_the_reviewed_plan_and_enforce_availability() {
        for action in [Action::Install, Action::Remove] {
            for available in [false, true] {
                for fake_account in [false, true] {
                    for disable_chinese_navigation in [false, true] {
                        let mut plan = fixture(action);
                        plan.oem_options_available = available;
                        let options = InstallOptions {
                            fake_account,
                            disable_chinese_navigation,
                        };
                        let allowed = !options.any() || (available && action == Action::Install);
                        assert_eq!(plan.set_options(options).is_ok(), allowed);
                        if allowed {
                            assert_eq!(plan.request.options, options);
                            assert_eq!(
                                plan.steps
                                    .iter()
                                    .filter(|step| step.id == "fake-account")
                                    .count(),
                                usize::from(fake_account)
                            );
                        } else {
                            assert_eq!(plan.request.options, InstallOptions::default());
                        }
                    }
                }
            }
        }
        let mut plan = fixture(Action::Install);
        plan.oem_options_available = true;
        plan.set_options(InstallOptions {
            fake_account: true,
            disable_chinese_navigation: false,
        })
        .unwrap();
        let fake = plan
            .steps
            .iter()
            .position(|step| step.id == "fake-account")
            .unwrap();
        assert_eq!(plan.steps[fake].title, "Активация Fake аккаунта");
        assert_eq!(plan.steps[fake - 1].id, "updater-bootstrap");
        plan.set_options(InstallOptions::default()).unwrap();
        assert!(!plan.steps.iter().any(|step| step.id == "fake-account"));
    }

    #[test]
    fn install_prepares_third_party_solutions_immediately_after_remount() {
        let steps = operation_steps(Action::Install, InstallOptions::default());
        let ids: Vec<_> = steps.iter().map(|step| step.id.as_str()).collect();
        assert_eq!(
            &ids[..8],
            &[
                "preflight",
                "root",
                "updater-prepare",
                "turn-sound-restore",
                "navigation-fonts-check",
                "system",
                "third-party-loaders",
                "backup"
            ]
        );
        assert!(!ids.contains(&"permission"));
    }

    #[test]
    fn navigation_fonts_are_applied_before_reboot_and_restored_before_removal() {
        {
            let install = operation_steps(Action::Install, InstallOptions::default());
            let font = install
                .iter()
                .position(|step| step.id == "navigation-fonts")
                .unwrap();
            assert_eq!(install[font + 1].id, "reboot");
            let remove = operation_steps(Action::Remove, InstallOptions::default());
            let font = remove
                .iter()
                .position(|step| step.id == "navigation-fonts")
                .unwrap();
            let files = remove.iter().position(|step| step.id == "files").unwrap();
            assert!(font < files);
        }
    }

    #[test]
    fn removal_does_not_require_matching_release_or_apk_signatures() {
        assert_eq!(fixture(Action::Remove).operation, "remove");
    }

    fn fixture(action: Action) -> Plan {
        let inventory: Inventory = serde_json::from_value(json!({
            "serial":"test", "fingerprint":"test", "model":"test", "sdk":30,
            "abi":"arm64-v8a", "problems":[], "baseNative":null,
            "packages":{"ru.big.town.anative":{
                "path":"/system/priv-app/Native/Native.apk", "sha256":"verified", "signers":[],
                "build":{"schema":1,"infrastructure":"unified","product":"VoyahTune","component":"native",
                    "releaseVersion":"9.0.0","buildRevision":"future","runtimeHashes":{}}
            }},
            "files":{}, "foreignFiles":{}, "remnants":[], "state":"partial",
            "version":null, "token":"test"
        }))
        .unwrap();
        let payload = Payload {
            root: Default::default(),
            manifest: serde_json::from_value(json!({"schema":1,"infrastructure":crate::infrastructure::Infrastructure::compiled(),"product":"VoyahTune",
                "releaseVersion":"1.0.0","buildRevision":"older","artifacts":[]}))
            .unwrap(),
        };
        plan(inventory, &payload, action, Dns::Keep).unwrap()
    }
}
