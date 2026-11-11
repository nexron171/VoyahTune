use super::*;

impl Engine {
    pub(super) fn install_updater(&self) -> Result<()> {
        let path = self.payload.file("voyahtune-updater.apk")?;
        let arguments = [
            "install",
            "-r",
            "-d",
            "--user",
            "0",
            path.to_str()
                .ok_or_else(|| self.fail("Некорректный путь Updater", path.display()))?,
        ];
        let mut output = self.raw(&arguments)?;
        if signature_update_rejected(&format!("{}\n{}", output.stdout, output.stderr)) {
            self.reset_updater_signature(&path)?;
            output = self.raw(&arguments)?;
        }
        require_package_success(output, "Updater не установлен")?;
        Ok(())
    }

    fn reset_updater_signature(&self, apk: &Path) -> Result<()> {
        self.adb.events.emit(
            "package-reset",
            self.adb.step.as_deref(),
            "Другой ключ Updater: сбрасываем системную регистрацию и перезагружаем Android",
            json!({"package": payload::UPDATER}),
        )?;
        let previous_boot = self.shell("cat /proc/sys/kernel/random/boot_id\n")?;
        if previous_boot.is_empty() {
            return Err(self.fail(
                "Не удалось определить загрузку Android",
                "Empty boot identity",
            ));
        }
        self.shell(include_str!(
            "../../../../../Packaging/installer/common/reset-updater-signature.sh"
        ))?;
        self.raw(&["reboot"])?
            .checked("Не удалось перезагрузить Android для смены ключа Updater")?;
        let deadline = Instant::now() + Duration::from_secs(180);
        loop {
            let boot = self.adb.shell(
                "[ \"$(getprop sys.boot_completed)\" = 1 ] && cat /proc/sys/kernel/random/boot_id\n",
                Duration::from_secs(10),
            );
            if boot.is_ok_and(|boot| !boot.is_empty() && boot != previous_boot) {
                break;
            }
            if Instant::now() >= deadline {
                return Err(self.fail(
                    "Android не перезагрузился для смены ключа Updater",
                    previous_boot,
                ));
            }
            thread::sleep(Duration::from_secs(2));
        }
        self.root_sequence(true)?;
        self.writable(false)?;
        self.stop_updater_service()?;
        self.shell(include_str!(
            "../../../../../Packaging/installer/common/prepare-runtime-maintenance-device.sh"
        ))?;
        self.shell("if pm path ru.big.town.updater 2>/dev/null | grep -q '^package:'; then echo 'Updater registration survived reboot' >&2; exit 1; fi\n")?;
        self.push_file(
            apk,
            "/system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk.voyahtune.new",
            payload::UPDATER_PATH,
            0o644,
            true,
        )
    }
}

#[cfg(all(test, unix))]
mod tests;
