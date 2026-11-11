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

pub fn execute(
    runyn: bool,
    mut perform: impl FnMut(Step) -> io::Result<()>,
    mut log_navigation_fonts_error: impl FnMut(&io::Error),
) -> io::Result<()> {
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
            if let Err(error) = perform(step) {
                if step == Step::NavigationFonts {
                    log_navigation_fonts_error(&error);
                } else {
                    return Err(error);
                }
            }
        }
    }
    Ok(())
}

pub fn finalize_failure(
    synchronize: impl FnOnce() -> io::Result<()>,
    reboot: impl FnOnce() -> io::Result<()>,
) -> io::Result<()> {
    let synchronized = synchronize();
    let rebooted = reboot();
    rebooted.and(synchronized)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn failed_installation_always_attempts_reboot_even_if_synchronization_fails() {
        for sync_fails in [false, true] {
            for reboot_fails in [false, true] {
                let actions = std::cell::RefCell::new(Vec::new());
                let result = finalize_failure(
                    || {
                        actions.borrow_mut().push("sync");
                        if sync_fails {
                            Err(io::Error::other("sync failed"))
                        } else {
                            Ok(())
                        }
                    },
                    || {
                        actions.borrow_mut().push("reboot");
                        if reboot_fails {
                            Err(io::Error::other("reboot failed"))
                        } else {
                            Ok(())
                        }
                    },
                );
                assert_eq!(*actions.borrow(), ["sync", "reboot"]);
                assert_eq!(result.is_ok(), !sync_fails && !reboot_fails);
            }
        }
    }
    #[test]
    fn restore_is_the_last_operation_before_reboot() {
        let mut actual = Vec::new();
        execute(
            false,
            |step| {
                actual.push(step);
                Ok(())
            },
            |error| panic!("Unexpected font error: {error}"),
        )
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
    fn runyn_is_installed_before_restoremode() {
        let mut actual = Vec::new();
        execute(
            true,
            |step| {
                actual.push(step);
                Ok(())
            },
            |error| panic!("Unexpected font error: {error}"),
        )
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
    fn required_step_failures_never_reach_later_writes_or_reboot() {
        for failure in [
            Step::Native,
            Step::Runyn,
            Step::Dns,
            Step::Sync,
            Step::Restore,
            Step::Reboot,
        ] {
            let mut actual = Vec::new();
            let result = execute(
                true,
                |step| {
                    actual.push(step);
                    if step == failure {
                        Err(io::Error::other("injected failure"))
                    } else {
                        Ok(())
                    }
                },
                |error| panic!("Required step failure was ignored: {error}"),
            );
            assert!(result.is_err());
            assert_eq!(actual.last(), Some(&failure));
            assert_eq!(actual.contains(&Step::Reboot), failure == Step::Reboot);
        }
    }

    #[test]
    fn navigation_font_failures_are_logged_and_installation_reaches_reboot() {
        for runyn in [false, true] {
            for kind in [
                io::ErrorKind::TimedOut,
                io::ErrorKind::PermissionDenied,
                io::ErrorKind::InvalidData,
                io::ErrorKind::Other,
            ] {
                let mut actual = Vec::new();
                let mut warnings = Vec::new();
                execute(
                    runyn,
                    |step| {
                        actual.push(step);
                        if step == Step::NavigationFonts {
                            Err(io::Error::new(kind, "font operation failed"))
                        } else {
                            Ok(())
                        }
                    },
                    |error| warnings.push((error.kind(), error.to_string())),
                )
                .unwrap();

                assert_eq!(warnings, [(kind, "font operation failed".into())]);
                assert!(actual.contains(&Step::Sync));
                assert!(actual.contains(&Step::Restore));
                assert_eq!(actual.last(), Some(&Step::Reboot));
                assert_eq!(actual.contains(&Step::Runyn), runyn);
            }
        }
    }
}
