use std::{io, thread, time::Duration};

const MAX_RETRIES: usize = 3;
const RETRY_DELAY: Duration = Duration::from_secs(10);

pub(super) fn run<T>(check: impl FnMut() -> io::Result<T>) -> io::Result<T> {
    retry_check(check, |retry, delay, error| {
        let _ = crate::log(
            super::root(),
            &format!(
                "postboot_check_retry retry={retry}/{MAX_RETRIES} delay_seconds={} error={error}",
                delay.as_secs()
            ),
        );
        thread::sleep(delay);
    })
}

fn retry_check<T>(
    mut check: impl FnMut() -> io::Result<T>,
    mut before_retry: impl FnMut(usize, Duration, &io::Error),
) -> io::Result<T> {
    let mut result = check();
    for retry in 1..=MAX_RETRIES {
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
    fn successful_check_finishes_without_waiting() {
        let mut attempts = 0;
        let result = retry_check(
            || {
                attempts += 1;
                Ok("verified release")
            },
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
