use std::io;

#[derive(Debug, PartialEq, Eq)]
pub enum Command {
    StartService,
    Health,
    Version,
    RepairStatus,
}

pub fn parse(mut arguments: impl Iterator<Item = String>) -> io::Result<Command> {
    let command = match arguments.next().as_deref() {
        None => Command::StartService,
        Some("--health") => Command::Health,
        Some("--version") => Command::Version,
        Some("--repair-status") => Command::RepairStatus,
        Some(argument) => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("Unknown updater argument: {argument}"),
            ));
        }
    };
    if arguments.next().is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Updater commands do not accept additional arguments",
        ));
    }
    Ok(command)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn command(arguments: &[&str]) -> io::Result<Command> {
        parse(arguments.iter().map(|argument| (*argument).to_owned()))
    }

    #[test]
    fn installer_health_request_is_not_a_service_start() {
        assert_eq!(command(&["--health"]).unwrap(), Command::Health);
        assert_eq!(command(&[]).unwrap(), Command::StartService);
    }

    #[test]
    fn invalid_diagnostics_cannot_fall_back_to_service_start() {
        for arguments in [
            vec!["--heath"],
            vec!["--unsupported"],
            vec!["--health", "--version"],
            vec!["--version", "extra"],
        ] {
            assert_eq!(
                command(&arguments).unwrap_err().kind(),
                io::ErrorKind::InvalidInput
            );
        }
    }

    #[test]
    fn diagnostic_commands_remain_available() {
        for (argument, expected) in [
            ("--version", Command::Version),
            ("--repair-status", Command::RepairStatus),
        ] {
            assert_eq!(command(&[argument]).unwrap(), expected);
        }
    }
}
