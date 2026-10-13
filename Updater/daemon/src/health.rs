use crate::{config::invalid, protocol};
use serde_json::Value;
use std::{
    io::{self, BufRead, BufReader, Read, Write},
    os::unix::net::UnixStream,
    time::Duration,
};

pub fn root_request_allowed(request: &protocol::Request) -> bool {
    matches!(request, protocol::Request::Status {})
}

pub fn query() -> io::Result<Value> {
    let mut stream = UnixStream::connect(format!("/dev/socket/{}", protocol::SOCKET_NAME))?;
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    stream.write_all(b"{\"command\":\"status\"}\n")?;
    read_response(&mut BufReader::new(stream))
}

fn read_response(reader: &mut impl BufRead) -> io::Result<Value> {
    let mut line = String::new();
    reader.take(65537).read_line(&mut line)?;
    if line.len() > 65536 || !line.ends_with('\n') {
        return Err(invalid("Неполный или слишком большой ответ OTA-службы"));
    }
    let response: Value =
        serde_json::from_str(&line).map_err(|error| invalid(&error.to_string()))?;
    if response["schema"] != 1
        || response["ok"] != true
        || response["infrastructure"] != "od"
        || !response["pid"].as_u64().is_some_and(|pid| pid > 0)
    {
        return Err(invalid("OTA-служба не подтвердила готовность протокола"));
    }
    Ok(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn root_diagnostics_cannot_change_updater_state() {
        assert!(root_request_allowed(&protocol::Request::Status {}));
        for request in [
            protocol::Request::Apply {},
            protocol::Request::Download {},
            protocol::Request::Check {
                same_version: false,
            },
            protocol::Request::Finish { reset_errors: true },
            protocol::Request::Dismiss {},
        ] {
            assert!(!root_request_allowed(&request));
        }
    }

    #[test]
    fn health_requires_a_complete_live_response() {
        assert!(read_response(&mut Cursor::new(
            b"{\"schema\":1,\"ok\":true,\"infrastructure\":\"od\",\"pid\":5}\n"
        ))
        .is_ok());
        for response in [
            "{}\n",
            "{\"ok\":false}\n",
            "{\"schema\":1,\"ok\":true,\"infrastructure\":\"od\",\"pid\":5}",
        ] {
            assert!(read_response(&mut Cursor::new(response)).is_err());
        }
    }
}
