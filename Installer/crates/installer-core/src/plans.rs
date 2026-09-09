use crate::{
    inventory::Inventory,
    payload::{Payload, Variant},
    Result,
};
use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Full,
    Light,
    Remove,
}
impl Action {
    pub fn variant(self) -> Option<Variant> {
        match self {
            Self::Full => Some(Variant::Full),
            Self::Light => Some(Variant::Light),
            Self::Remove => None,
        }
    }
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
    pub request: Request,
    pub inventory: Inventory,
    pub operation: String,
    pub warnings: Vec<String>,
    pub steps: Vec<Step>,
}
/// Packages whose installed key differs from the selected release. Only our two
/// applications may be reset automatically; other package failures remain errors.
pub fn signature_resets(
    inventory: &Inventory,
    payload: &Payload,
    action: Action,
) -> Result<Vec<String>> {
    let Some(variant) = action.variant() else {
        return Ok(Vec::new());
    };
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
        let expected = crate::payload::verified_signers(&payload.file(artifact, Some(variant))?)?;
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
    let mut warnings=vec!["Совпадение компонентов не означает проверку всех функций на этой прошивке. Автомобиль должен стоять на парковке; питание нельзя отключать до завершения.".into()];
    warnings.extend(inventory.problems.iter().cloned());
    let operation = if action == Action::Remove {
        "remove"
    } else if inventory.state == "absent" {
        "install"
    } else if inventory.state != "complete" {
        warnings.push("Установлен старый или неполный комплект. Сначала будут сохранены найденные файлы, затем восстановлен выбранный набор.".into());
        "repair"
    } else if inventory.variant != action.variant() {
        "switch"
    } else if inventory.version.as_deref() == Some(&payload.manifest.release_version) {
        "repair"
    } else {
        "update"
    };
    if action == Action::Remove {
        warnings.push("Будут удалены оба набора, их настройки и журналы; DNS будет восстановлен из сохранённого исходного состояния. Заводская прошивка и состояние загрузчика не восстанавливаются.".into());
    }
    let resets = signature_resets(&inventory, payload, action).unwrap_or_default();
    if !resets.is_empty() {
        warnings.push(format!("Другой ключ подписи: {}. Эти приложения будут автоматически удалены вместе с настройками и данными, затем установлены заново.", resets.join(", ")));
    }
    let steps = classic_steps(action);
    Ok(Plan {
        request: Request {
            action,
            dns,
            serial: inventory.serial.clone(),
            inventory_token: inventory.token.clone(),
            confirmed: false,
        },
        inventory,
        operation: operation.into(),
        warnings,
        steps: steps
            .into_iter()
            .map(|(id, title)| Step {
                id: id.into(),
                title: title.into(),
            })
            .collect(),
    })
}

/// Shared with the GUI plan; these are presentation boundaries in the classic flow.
fn classic_steps(action: Action) -> Vec<(&'static str, &'static str)> {
    let mut s = vec![
        ("preflight", "Подготовка файлов комплекта"),
        ("root", "Получение системного доступа"),
    ];
    if action != Action::Remove {
        s.push(("permission", "Проверка владельца CAN-разрешения"));
    }
    s.push(("system", "Подготовка системного раздела"));
    if action == Action::Remove {
        s.extend([
            ("deactivate", "Отключение Apollo"),
            ("dns", "Восстановление DNS"),
            ("migration", "Миграция старого init.logcat.sh"),
            ("runtime", "Остановка и удаление boot-hook"),
            ("files", "Удаление hooks и временных файлов"),
            ("settings", "Очистка настроек VoyahTune"),
            ("packages", "Удаление приложений"),
            ("reboot", "Перезагрузка автомобиля"),
        ]);
    } else {
        s.extend([
            ("backup", "Сохранение файлов перед заменой"),
            ("signing-reset", "Переустановка при смене подписи"),
            ("runtime", "Остановка старых hooks"),
        ]);
        if action == Action::Full {
            s.extend([
                ("files", "Установка Frida и скриптов"),
                ("migration", "Миграция старого init.logcat.sh"),
                ("boot-hooks", "Установка boot-hook"),
            ]);
        }
        s.extend([
            ("native", "Установка Native и разрешений"),
            ("packages", "Установка RestoreMode и настроек"),
            ("dns", "Настройка DNS"),
            ("reboot", "Перезагрузка автомобиля"),
            ("verify", "Проверка запуска Native"),
        ]);
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn removal_does_not_require_matching_release_or_apk_signatures() {
        let inventory: Inventory = serde_json::from_value(json!({
            "serial":"test", "fingerprint":"test", "model":"test", "sdk":30,
            "abi":"arm64-v8a", "problems":[], "baseNative":null,
            "packages":{"ru.big.town.anative":{
                "path":"/system/priv-app/Native/Native.apk", "sha256":"verified", "signers":[],
                "build":{"schema":1,"product":"VoyahTune","component":"native","variant":"full",
                    "releaseVersion":"9.0.0","buildRevision":"future","runtimeHashes":{}}
            }},
            "files":{}, "foreignFiles":{}, "remnants":[], "state":"partial",
            "variant":null, "version":null, "token":"test"
        }))
        .unwrap();
        let payload = Payload {
            root: Default::default(),
            manifest: serde_json::from_value(json!({"schema":1,"product":"VoyahTune",
                "releaseVersion":"1.0.0","buildRevision":"older","artifacts":[]}))
            .unwrap(),
        };
        assert_eq!(
            plan(inventory, &payload, Action::Remove, Dns::Keep)
                .unwrap()
                .operation,
            "remove"
        );
    }
}
