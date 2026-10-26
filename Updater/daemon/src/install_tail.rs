use std::io;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    Native,
    Restore,
    Runyn,
    Dns,
    NavigationFonts,
    Sync,
    Reboot,
}

/// The UI package replacement ends the visible session; only reboot finalization may follow it.
pub fn execute(runyn: bool, mut perform: impl FnMut(Step) -> io::Result<()>) -> io::Result<()> {
    let order = [
        Step::Native,
        Step::Runyn,
        Step::Dns,
        Step::NavigationFonts,
        Step::Sync,
        Step::Restore,
        Step::Reboot,
    ];
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
    fn restore_is_the_last_operation_before_reboot() {
        let mut actual = Vec::new();
        execute(false, |step| {
            actual.push(step);
            Ok(())
        })
        .unwrap();
        assert_eq!(
            actual,
            [
                Step::Native,
                Step::Dns,
                Step::NavigationFonts,
                Step::Sync,
                Step::Restore,
                Step::Reboot
            ]
        );
    }
    #[test]
    fn runyn_is_installed_before_the_ui_is_replaced() {
        let mut actual = Vec::new();
        execute(true, |step| {
            actual.push(step);
            Ok(())
        })
        .unwrap();
        assert_eq!(
            actual,
            [
                Step::Native,
                Step::Runyn,
                Step::Dns,
                Step::NavigationFonts,
                Step::Sync,
                Step::Restore,
                Step::Reboot
            ]
        );
    }
    #[test]
    fn failures_never_reach_later_writes_or_reboot() {
        for failure in [
            Step::Native,
            Step::Dns,
            Step::NavigationFonts,
            Step::Sync,
            Step::Restore,
        ] {
            let mut actual = Vec::new();
            let result = execute(false, |step| {
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
