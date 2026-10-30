use super::*;

const SCRIPT: &str = include_str!("../../../../../Packaging/installer/common/application-data.sh");
const START_RUNTIME: &str =
    include_str!("../../../../../Packaging/installer/common/start-restored-runtime.sh");

impl Engine {
    pub(super) fn application_data(&self, action: &str, package: &str) -> Result<()> {
        let mut script = format!(
            "application_data_action={}\napplication_package={}\n{SCRIPT}",
            quote(action),
            quote(package),
        );
        let postflight = self.adb.step.as_deref() == Some("verify");
        if postflight && action == "restore" && package == NATIVE {
            script.push('\n');
            script.push_str(START_RUNTIME);
        }
        let result = self.adb.shell(
            &postflight_script(&script, 1800),
            Duration::from_secs(1830),
        );
        result.map(|_| ()).map_err(|error| {
            Error::new(
                "APPLICATION_DATA_FAILED",
                "Не удалось сохранить или восстановить данные приложения",
            )
            .detail(error)
            .retry("Не удаляйте /data/local/voyahtune-application-backups на ГУ. Повторите установку после устранения ошибки.")
        })
    }
}
