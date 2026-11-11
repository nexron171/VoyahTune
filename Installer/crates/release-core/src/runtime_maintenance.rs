use serde::Serialize;
use std::{fmt, io};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Operation {
    Install,
    Remove,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Step {
    Block,
    Stop,
    Copy,
    Cleanup,
    Remove,
    Reboot,
    Check,
    RepairRequired,
}

#[derive(Debug)]
pub struct SequenceError;

impl fmt::Display for SequenceError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("Runtime maintenance sequence is incomplete or already failed")
    }
}
impl std::error::Error for SequenceError {}
impl From<SequenceError> for crate::Error {
    fn from(error: SequenceError) -> Self {
        crate::Error::new("RUNTIME_MAINTENANCE", error.to_string())
    }
}
impl From<SequenceError> for io::Error {
    fn from(error: SequenceError) -> Self {
        io::Error::other(error)
    }
}

pub struct RuntimeMaintenance {
    operation: Operation,
    next: Option<Step>,
    trace: Vec<Step>,
    reboot_required: bool,
}

impl RuntimeMaintenance {
    pub fn new(operation: Operation) -> Self {
        Self {
            operation,
            next: Some(Step::Block),
            trace: Vec::new(),
            reboot_required: false,
        }
    }

    pub fn after_reboot(previous: &str, current: &str) -> Result<Self, SequenceError> {
        if previous.is_empty() || current.is_empty() || previous == current {
            return Err(SequenceError);
        }
        Ok(Self {
            operation: Operation::Install,
            next: Some(Step::Check),
            trace: Vec::new(),
            reboot_required: false,
        })
    }

    pub fn execute<T, E: From<SequenceError>>(
        &mut self,
        step: Step,
        action: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, E> {
        if self.next != Some(step) || step == Step::RepairRequired {
            self.fail();
            return Err(SequenceError.into());
        }
        self.trace.push(step);
        if step == Step::Stop {
            self.reboot_required = true;
        }
        let value = match action() {
            Ok(value) => value,
            Err(error) => {
                self.fail();
                return Err(error);
            }
        };
        self.next = match step {
            Step::Block => Some(Step::Stop),
            Step::Stop => Some(if self.operation == Operation::Install {
                Step::Copy
            } else {
                Step::Remove
            }),
            Step::Copy => Some(Step::Cleanup),
            Step::Cleanup | Step::Remove => Some(Step::Reboot),
            Step::Reboot => {
                self.reboot_required = false;
                if self.operation == Operation::Install {
                    Some(Step::Check)
                } else {
                    None
                }
            }
            Step::Check => None,
            _ => unreachable!(),
        };
        Ok(value)
    }

    pub fn trace(&self) -> &[Step] {
        &self.trace
    }

    pub fn reboot_required(&self) -> bool {
        self.reboot_required
    }

    fn fail(&mut self) {
        self.next = Some(Step::RepairRequired);
        if self.trace.last() != Some(&Step::RepairRequired) {
            self.trace.push(Step::RepairRequired);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn installation_stops_without_waiting_for_agent_acknowledgements() {
        let mut transaction = RuntimeMaintenance::new(Operation::Install);
        let mut actions = Vec::new();
        for step in [
            Step::Block,
            Step::Stop,
            Step::Copy,
            Step::Cleanup,
            Step::Reboot,
            Step::Check,
        ] {
            transaction
                .execute(step, || {
                    actions.push(step);
                    Ok::<_, io::Error>(())
                })
                .unwrap();
        }
        assert_eq!(
            actions,
            [
                Step::Block,
                Step::Stop,
                Step::Copy,
                Step::Cleanup,
                Step::Reboot,
                Step::Check
            ]
        );
    }

    #[test]
    fn no_write_action_runs_before_forced_stop() {
        let mut transaction = RuntimeMaintenance::new(Operation::Install);
        let mut writes = 0;
        let result: io::Result<()> = transaction.execute(Step::Copy, || {
            writes += 1;
            Ok(())
        });
        assert!(result.is_err());
        assert_eq!(writes, 0);
    }

    #[test]
    fn cleanup_failure_blocks_reboot_and_cannot_be_resumed_in_place() {
        let mut transaction = RuntimeMaintenance::new(Operation::Install);
        for step in [Step::Block, Step::Stop, Step::Copy] {
            transaction
                .execute(step, || Ok::<_, io::Error>(()))
                .unwrap();
        }
        assert!(transaction
            .execute(Step::Cleanup, || Err::<(), _>(io::Error::other("failed")))
            .is_err());
        let mut rebooted = false;
        assert!(transaction
            .execute(Step::Reboot, || {
                rebooted = true;
                Ok::<_, io::Error>(())
            })
            .is_err());
        assert!(!rebooted);
        assert!(transaction.reboot_required());
    }

    #[test]
    fn failed_stop_requires_reboot_without_allowing_file_writes() {
        let mut transaction = RuntimeMaintenance::new(Operation::Install);
        transaction
            .execute(Step::Block, || Ok::<_, io::Error>(()))
            .unwrap();
        assert!(!transaction.reboot_required());
        assert!(transaction
            .execute(Step::Stop, || Err::<(), _>(io::Error::other("kill failed")))
            .is_err());
        let mut copied = false;
        assert!(transaction
            .execute(Step::Copy, || {
                copied = true;
                Ok::<_, io::Error>(())
            })
            .is_err());
        assert!(!copied);
        assert!(transaction.reboot_required());
    }

    #[test]
    fn postboot_validation_requires_a_different_kernel_boot() {
        assert!(RuntimeMaintenance::after_reboot("boot", "boot").is_err());
        let mut transaction = RuntimeMaintenance::after_reboot("old", "new").unwrap();
        transaction
            .execute(Step::Check, || Ok::<_, io::Error>(()))
            .unwrap();
    }
}
