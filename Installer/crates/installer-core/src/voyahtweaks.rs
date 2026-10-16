use crate::{adb::Adb, infrastructure::Infrastructure, plans::Action, Error, Result};
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(15);

// Feed this through stdin: putting the probe in argv would match its own process.
pub(crate) const PROBE: &str = include_str!("voyahtweaks_probe.sh");

pub(crate) fn check_installation(
    adb: &Adb,
    infrastructure: Infrastructure,
    action: Action,
) -> Result<()> {
    if infrastructure == Infrastructure::Od && action == Action::Install {
        check(adb, TIMEOUT)?;
    }
    Ok(())
}

pub(crate) fn check(adb: &Adb, timeout: Duration) -> Result<()> {
    let output = adb
        .shell(PROBE, timeout.min(TIMEOUT))
        .map_err(|error| inspection_error(error.to_string()))?;
    validate(&output)
}

fn inspection_error(detail: impl ToString) -> Error {
    Error::new(
        "VOYAHTWEAKS_CHECK_FAILED",
        "Не удалось проверить наличие VoyahTweaks. Установка заблокирована.",
    )
    .detail(detail)
    .retry("Проверьте подключение и системный доступ, затем повторите проверку автомобиля.")
}

fn validate(output: &str) -> Result<()> {
    for line in output.lines() {
        if let Some(evidence) = line.strip_prefix("VOYAHTWEAKS_FOUND\t") {
            return Err(Error::new(
                "VOYAHTWEAKS_DETECTED",
                "Найдены признаки VoyahTweaks. Установка VoyahTune заблокирована.",
            )
            .detail(evidence)
            .retry("Остановите установку или согласитесь на отключение VoyahTweaks с перезагрузкой и повторной проверкой."));
        }
    }
    if output.trim() == "VOYAHTWEAKS_CLEAR" {
        Ok(())
    } else {
        Err(inspection_error(output))
    }
}

pub(crate) fn is_blocking_error(error: &Error) -> bool {
    matches!(
        error.code.as_str(),
        "VOYAHTWEAKS_DETECTED" | "VOYAHTWEAKS_CHECK_FAILED"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::Events;

    #[test]
    fn evidence_overrides_clear_and_incomplete_output_blocks() {
        let error = validate("VOYAHTWEAKS_CLEAR\nVOYAHTWEAKS_FOUND\tstartup").unwrap_err();
        assert_eq!(error.code, "VOYAHTWEAKS_DETECTED");
        assert!(is_blocking_error(&error));
        assert!(validate("VOYAHTWEAKS_CLEAR").is_ok());
        for output in ["", "permission denied", "VOYAHTWEAKS_CLEAR\nunknown"] {
            assert_eq!(
                validate(output).unwrap_err().code,
                "VOYAHTWEAKS_CHECK_FAILED"
            );
        }
    }

    #[test]
    fn pi_and_removal_do_not_run_device_commands() {
        let adb = Adb::new(std::env::current_exe().unwrap(), Events::quiet()).unwrap();
        check_installation(&adb, Infrastructure::Pi, Action::Install).unwrap();
        check_installation(&adb, Infrastructure::Od, Action::Remove).unwrap();
    }
}
