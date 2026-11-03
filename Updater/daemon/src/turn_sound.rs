use crate::config::invalid;
use serde_json::{json, Value};
use std::{io, path::Path};

pub fn restore() {
    restore_with(
        || {
            qnx_fonts::turn_sound::on_device("restore", "original", 100)
                .map(|_| ())
                .map_err(|error| invalid(&error.to_string()))
        },
        |error| {
            let _ = crate::log(
                Path::new(crate::ROOT),
                &format!("turn_sound_restore_warning {error}"),
            );
        },
    );
}

fn restore_with(restore: impl FnOnce() -> io::Result<()>, warning: impl FnOnce(&io::Error)) {
    if let Err(error) = restore() {
        warning(&error);
    }
}

pub fn call(
    action: &str,
    sound: &str,
    volume: u8,
    progress: &mut dyn FnMut(&str),
) -> io::Result<Value> {
    let result = qnx_fonts::turn_sound::run_with_progress(
        &qnx_fonts::Endpoint::Tcp(([172, 16, 104, 41], 8000).into()),
        action,
        sound,
        volume,
        progress,
    )
    .map_err(|error| invalid(&error.to_string()))?;
    Ok(json!({"schema":1,"ok":true,"turnSound":result}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn automatic_restore_logs_qnx_errors_and_continues() {
        for kind in [
            io::ErrorKind::ConnectionRefused,
            io::ErrorKind::TimedOut,
            io::ErrorKind::PermissionDenied,
            io::ErrorKind::InvalidData,
        ] {
            let mut warnings = Vec::new();
            restore_with(
                || Err(io::Error::new(kind, "QNX restore failed")),
                |error| warnings.push((error.kind(), error.to_string())),
            );
            assert_eq!(warnings, [(kind, "QNX restore failed".into())]);
        }
    }

    #[test]
    fn successful_restore_does_not_log_a_warning() {
        let mut restored = false;
        restore_with(
            || {
                restored = true;
                Ok(())
            },
            |error| panic!("Unexpected restore error: {error}"),
        );
        assert!(restored);
    }
}
