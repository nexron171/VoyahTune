use super::*;

pub(super) fn transfer(action: &str, package: &str) -> io::Result<()> {
    command(
        "/system/bin/sh",
        &[
            "-c",
            concat!(
                "application_data_action=$1\napplication_package=$2\n",
                include_str!("../../../../Packaging/installer/common/application-data.sh"),
            ),
            "application-data",
            action,
            package,
        ],
        1800,
    )
    .map(|_| ())
}

pub(super) fn reinstall(
    package: &str,
    mut install: impl FnMut() -> io::Result<()>,
    mut backup: impl FnMut() -> io::Result<()>,
    mut uninstall: impl FnMut() -> io::Result<()>,
    mut restore: impl FnMut() -> io::Result<()>,
) -> io::Result<()> {
    let result = install();
    if result.as_ref().is_err_and(|error| {
        error
            .to_string()
            .contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
    }) {
        backup()?;
        uninstall()?;
        install()?;
    } else {
        result?;
    }
    restore().map_err(|error| invalid(&format!("{package}: восстановление данных: {error}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn signature_reset_preserves_order_and_stops_at_every_failure() {
        for failure in ["", "backup", "uninstall", "retry", "restore", "storage"] {
            let calls = RefCell::new(Vec::new());
            let mut attempts = 0;
            let step = |name| {
                calls.borrow_mut().push(name);
                if name == failure {
                    Err(invalid(name))
                } else {
                    Ok(())
                }
            };
            let result = reinstall(
                payload::RESTORE,
                || {
                    attempts += 1;
                    if attempts == 1 {
                        calls.borrow_mut().push("install");
                        Err(invalid(if failure == "storage" {
                            "INSTALL_FAILED_INSUFFICIENT_STORAGE"
                        } else {
                            "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
                        }))
                    } else {
                        step("retry")
                    }
                },
                || step("backup"),
                || step("uninstall"),
                || step("restore"),
            );
            assert_eq!(result.is_ok(), failure.is_empty());
            let expected = ["install", "backup", "uninstall", "retry", "restore"];
            let count = if failure == "storage" {
                1
            } else {
                expected
                    .iter()
                    .position(|value| *value == failure)
                    .map_or(5, |index| index + 1)
            };
            assert_eq!(*calls.borrow(), expected[..count]);
        }
    }

    #[test]
    fn successful_install_restores_pending_backup_without_uninstalling() {
        let mut restored = false;
        reinstall(
            payload::RESTORE,
            || Ok(()),
            || panic!(),
            || panic!(),
            || {
                restored = true;
                Ok(())
            },
        )
        .unwrap();
        assert!(restored);
    }
}
