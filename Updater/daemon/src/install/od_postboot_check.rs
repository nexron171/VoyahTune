use std::{io, thread, time::Duration};

const MAX_RETRIES: usize = 3;
const HOOK_RETRIES: usize = 18;
const RETRY_DELAY: Duration = Duration::from_secs(10);

pub(super) fn run<T>(check: impl FnMut() -> io::Result<T>) -> io::Result<T> {
    run_with_retries(check, MAX_RETRIES)
}

pub(super) fn hooks<T>(check: impl FnMut() -> io::Result<T>) -> io::Result<T> {
    run_with_retries(check, HOOK_RETRIES)
}

fn run_with_retries<T>(check: impl FnMut() -> io::Result<T>, retries: usize) -> io::Result<T> {
    retry_check(check, retries, |retry, delay, error| {
        let _ = crate::log(
            super::root(),
            &format!(
                "postboot_check_retry retry={retry}/{retries} delay_seconds={} error={error}",
                delay.as_secs()
            ),
        );
        thread::sleep(delay);
    })
}

fn retry_check<T>(
    mut check: impl FnMut() -> io::Result<T>,
    retries: usize,
    mut before_retry: impl FnMut(usize, Duration, &io::Error),
) -> io::Result<T> {
    let mut result = check();
    for retry in 1..=retries {
        let Err(error) = &result else {
            return result;
        };
        before_retry(retry, RETRY_DELAY, error);
        result = check();
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hook_check_waits_for_serial_startup_and_propagates_persistent_failure() {
        for recover in [true, false] {
            let mut attempts = 0;
            let mut delayed = Duration::ZERO;
            let result = retry_check(
                || {
                    attempts += 1;
                    if recover && attempts == 7 {
                        Ok(())
                    } else {
                        Err(io::Error::other("hook acc: failed"))
                    }
                },
                HOOK_RETRIES,
                |_, delay, _| delayed += delay,
            );
            if recover {
                result.unwrap();
                assert_eq!(delayed, Duration::from_secs(60));
            } else {
                assert_eq!(result.unwrap_err().to_string(), "hook acc: failed");
                assert_eq!(attempts, 19);
                assert_eq!(delayed, Duration::from_secs(180));
            }
        }
    }

    #[test]
    fn successful_check_finishes_without_waiting() {
        let mut attempts = 0;
        let result = retry_check(
            || {
                attempts += 1;
                Ok("verified release")
            },
            MAX_RETRIES,
            |_, _, _| panic!("A successful check must not schedule a retry"),
        );

        assert_eq!(result.unwrap(), "verified release");
        assert_eq!(attempts, 1);
    }

    #[test]
    fn temporary_service_failure_can_recover_on_any_retry() {
        for failures in 1..=3 {
            let mut attempts = 0;
            let mut retries = Vec::new();
            let result = retry_check(
                || {
                    attempts += 1;
                    if attempts <= failures {
                        Err(io::Error::other("cmd: Can't find service: package"))
                    } else {
                        Ok("verified release")
                    }
                },
                MAX_RETRIES,
                |retry, delay, error| {
                    retries.push((retry, delay));
                    assert_eq!(error.to_string(), "cmd: Can't find service: package");
                },
            );

            assert_eq!(result.unwrap(), "verified release");
            assert_eq!(attempts, failures + 1);
            assert_eq!(
                retries,
                (1..=failures)
                    .map(|retry| (retry, Duration::from_secs(10)))
                    .collect::<Vec<_>>()
            );
        }
    }

    #[test]
    fn persistent_failure_returns_the_last_error_after_three_delays() {
        let mut attempts = 0;
        let mut retries = Vec::new();
        let result: io::Result<()> = retry_check(
            || {
                attempts += 1;
                Err(io::Error::new(
                    io::ErrorKind::NotFound,
                    format!("Native data unavailable: attempt {attempts}"),
                ))
            },
            MAX_RETRIES,
            |retry, delay, error| {
                retries.push((retry, delay));
                assert_eq!(
                    error.to_string(),
                    format!("Native data unavailable: attempt {retry}")
                );
            },
        );

        let error = result.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::NotFound);
        assert_eq!(error.to_string(), "Native data unavailable: attempt 4");
        assert_eq!(attempts, 4);
        assert_eq!(
            retries,
            vec![
                (1, Duration::from_secs(10)),
                (2, Duration::from_secs(10)),
                (3, Duration::from_secs(10)),
            ]
        );
    }
}
