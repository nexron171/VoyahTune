//! Explicit consent for removing the known conflicting system application.
use std::sync::atomic::{AtomicU8, Ordering};

pub const PACKAGE: &str = "com.voyah.hl.service";
pub const DIRECTORY: &str = "/system/priv-app/VoyahHlCTRL";
pub const NOTICE: &str = "Обнаружено конфликтующее приложение com.voyah.hl.service (VoyahHlCTRL): оно установлено для пользователя 0 или владеет разрешением WRITE_CANBUS. Для установки VoyahTune его нужно удалить. Установщик сохранит копию системной папки на компьютере, удалит приложение для пользователя 0 и его системную папку, очистит кэш пакетов и перезагрузит автомобиль. Функции удалённого приложения станут недоступны; его пользовательские данные не сохраняются.";

// Idle -> waiting -> approved/declined. Unsolicited or repeated answers are ignored.
pub struct RemovalConsent(AtomicU8);
impl RemovalConsent {
    pub fn new(approved: bool) -> Self {
        Self(AtomicU8::new(if approved { 2 } else { 0 }))
    }
    pub fn begin(&self) {
        let _ = self
            .0
            .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst);
    }
    pub fn answer(&self, approved: bool) {
        let _ = self.0.compare_exchange(
            1,
            if approved { 2 } else { 3 },
            Ordering::SeqCst,
            Ordering::SeqCst,
        );
    }
    pub fn decision(&self) -> Option<bool> {
        match self.0.load(Ordering::SeqCst) {
            2 => Some(true),
            3 => Some(false),
            _ => None,
        }
    }
}

/// None: permission absent; Some(None): declared, but owner not reported.
pub fn owner(dump: &str) -> Option<Option<&str>> {
    dump.split_once("Permission [com.qinggan.permission.WRITE_CANBUS]")
        .map(|(_, rest)| {
            rest.split("Permission [")
                .next()
                .unwrap_or(rest)
                .lines()
                .find_map(|line| line.trim().strip_prefix("sourcePackage=").map(str::trim))
                .filter(|owner| !owner.is_empty())
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_is_confined_to_permission_block() {
        assert_eq!(owner("Permission [other]\n sourcePackage=android"), None);
        assert_eq!(owner("Permission [com.qinggan.permission.WRITE_CANBUS]\n sourcePackage=\nPermission [other]\n sourcePackage=android"), Some(None));
        assert_eq!(owner("Permission [com.qinggan.permission.WRITE_CANBUS]\r\n sourcePackage=com.voyah.hl.service\r\nPermission [other]\n sourcePackage=android"), Some(Some(PACKAGE)));
    }
    #[test]
    fn consent_requires_a_pending_request_and_cannot_be_reversed() {
        let consent = RemovalConsent::new(false);
        consent.answer(true);
        assert_eq!(consent.decision(), None);
        consent.begin();
        consent.answer(false);
        consent.answer(true);
        assert_eq!(consent.decision(), Some(false));
    }
}
