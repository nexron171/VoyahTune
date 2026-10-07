use std::io;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    Native,
    Restore,
    Runyn,
    Dns,
    Sync,
    Reboot,
}

/// The UI package replacement ends the visible session; only reboot finalization may follow it.
pub fn execute(
    embedded_ui: bool,
    runyn: bool,
    mut perform: impl FnMut(Step) -> io::Result<()>,
) -> io::Result<()> {
    let order = if embedded_ui {
        [
            Step::Native,
            Step::Runyn,
            Step::Dns,
            Step::Sync,
            Step::Restore,
            Step::Reboot,
        ]
    } else {
        [
            Step::Native,
            Step::Restore,
            Step::Runyn,
            Step::Dns,
            Step::Sync,
            Step::Reboot,
        ]
    };
    for step in order {
        if step != Step::Runyn || runyn {
            perform(step)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn restore_is_the_last_od_operation_before_reboot() {
        let mut actual = Vec::new();
        execute(true, false, |step| {
            actual.push(step);
            Ok(())
        })
        .unwrap();
        assert_eq!(
            actual,
            [
                Step::Native,
                Step::Dns,
                Step::Sync,
                Step::Restore,
                Step::Reboot
            ]
        );
    }
    #[test]
    fn legacy_pi_order_is_preserved() {
        let mut actual = Vec::new();
        execute(false, true, |step| {
            actual.push(step);
            Ok(())
        })
        .unwrap();
        assert_eq!(
            actual,
            [
                Step::Native,
                Step::Restore,
                Step::Runyn,
                Step::Dns,
                Step::Sync,
                Step::Reboot
            ]
        );
    }
    #[test]
    fn failures_never_reach_later_writes_or_reboot() {
        for failure in [Step::Native, Step::Dns, Step::Sync, Step::Restore] {
            let mut actual = Vec::new();
            let result = execute(true, false, |step| {
                actual.push(step);
                if step == failure {
                    Err(io::Error::other("injected failure"))
                } else {
                    Ok(())
                }
            });
            assert!(result.is_err());
            assert_eq!(actual.last(), Some(&failure));
            assert!(!actual.contains(&Step::Reboot));
        }
    }
}
