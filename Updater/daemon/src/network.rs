use crate::config::invalid;
use release_core::catalog::Catalog;
use release_core::infrastructure::Infrastructure;
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::Path,
    time::Duration,
};
fn agent(seconds: u64, body_timeout: Option<Duration>) -> ureq::Agent {
    ureq::Agent::config_builder()
        .https_only(true)
        .max_redirects(5)
        .timeout_global(Some(Duration::from_secs(seconds)))
        .timeout_connect(Some(Duration::from_secs(20)))
        .timeout_recv_body(body_timeout)
        .build()
        .new_agent()
}
fn payload_body_timeout(infrastructure: Infrastructure) -> Option<Duration> {
    match infrastructure {
        // ureq's body timeout covers the entire transfer, not inactivity between reads.
        // OD payloads use the global one-hour budget; PI retains its existing policy.
        Infrastructure::Od => None,
        Infrastructure::Pi => Some(Duration::from_secs(45)),
    }
}
pub fn catalog(url: &str) -> io::Result<Catalog> {
    let client = agent(60, Some(Duration::from_secs(45)));
    let mut request = client.get(url);
    if crate::restore_ui::enabled() {
        request = request.header("Cache-Control", "no-cache");
    }
    let mut response = request
        .call()
        .map_err(|e| invalid(&format!("Каталог: {e}")))?;
    let mut bytes = Vec::new();
    response
        .body_mut()
        .as_reader()
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() > 4 * 1024 * 1024 {
        return Err(invalid("Слишком большой каталог"));
    }
    let c: release_core::catalog::UpdateCatalog =
        serde_json::from_slice(&bytes).map_err(|e| invalid(&e.to_string()))?;
    c.resolve().map_err(|e| invalid(&e.to_string()))
}
pub fn download(
    url: &str,
    path: &Path,
    size: u64,
    sha: &str,
    progress: impl Fn(u64),
) -> io::Result<()> {
    let mut response = agent(3600, payload_body_timeout(Infrastructure::compiled()))
        .get(url)
        .call()
        .map_err(|e| invalid(&format!("Скачивание: {e}")))?;
    let mut reader = response.body_mut().as_reader();
    let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
    let mut hash = Sha256::new();
    let mut bytes = 0u64;
    let mut buffer = [0u8; 65536];
    let mut reported = 0;
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        bytes += n as u64;
        if bytes > size {
            return Err(invalid("Архив превысил заявленный размер"));
        }
        file.write_all(&buffer[..n])?;
        hash.update(&buffer[..n]);
        if bytes - reported >= 1024 * 1024 {
            progress(bytes);
            reported = bytes;
        }
    }
    if bytes != size || hex::encode(hash.finalize()) != sha {
        return Err(invalid("Размер или SHA-256 архива не совпадает"));
    }
    file.sync_all()?;
    progress(bytes);
    Ok(())
}
/// Only a newly created private directory is accepted. Never extract into a live tree.
pub fn extract(source: &Path, target: &Path, progress: impl Fn(u64, u64)) -> io::Result<()> {
    fs::create_dir(target)?;
    let mut archive =
        zip::ZipArchive::new(File::open(source)?).map_err(|e| invalid(&e.to_string()))?;
    if archive.len() > 4096 {
        return Err(invalid("Слишком много файлов ZIP"));
    }
    let mut names = BTreeSet::new();
    let mut paths = BTreeMap::new();
    let mut total = 0u64;
    let count = archive.len();
    for index in 0..count {
        let mut entry = archive
            .by_index(index)
            .map_err(|e| invalid(&e.to_string()))?;
        let name = entry.name().trim_end_matches('/').to_owned();
        let kind = entry.unix_mode().unwrap_or(0) & 0o170000;
        if !release_core::paths::safe_path(&name)
            || !names.insert(name.to_ascii_lowercase())
            || ![0, 0o100000, 0o040000].contains(&kind)
        {
            return Err(invalid("Опасный или повторный путь ZIP"));
        }
        let parts: Vec<_> = name.split('/').collect();
        let mut partial = String::new();
        for (i, p) in parts.iter().enumerate() {
            if i > 0 {
                partial.push('/');
            }
            partial.push_str(p);
            let dir = i + 1 < parts.len() || entry.is_dir();
            if paths
                .insert(partial.to_ascii_lowercase(), (partial.clone(), dir))
                .is_some_and(|(old, d)| old != partial || d != dir)
            {
                return Err(invalid("Коллизия путей ZIP"));
            }
        }
        total = total
            .checked_add(entry.size())
            .ok_or_else(|| invalid("Переполнение ZIP"))?;
        if total > 4 * 1024 * 1024 * 1024 {
            return Err(invalid("Распакованный архив слишком велик"));
        }
        let out = target.join(name);
        if entry.is_dir() {
            fs::create_dir_all(out)?;
            continue;
        }
        fs::create_dir_all(out.parent().unwrap())?;
        let mut file = OpenOptions::new().write(true).create_new(true).open(out)?;
        let size = entry.size();
        let written = io::copy(&mut (&mut entry).take(size + 1), &mut file)?;
        if written != size {
            return Err(invalid("Размер файла ZIP не совпадает"));
        }
        file.sync_all()?;
        progress(index as u64 + 1, count as u64);
    }
    File::open(target)?.sync_all()?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, thread};

    #[test]
    fn continuous_payload_transfer_outlasts_body_budget_only_for_od() {
        fn receive(infrastructure: Infrastructure) -> io::Result<Vec<u8>> {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket.set_nodelay(true).unwrap();
                let mut request = [0; 4096];
                socket.read(&mut request).unwrap();
                socket
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\n")
                    .unwrap();
                for byte in b"payload" {
                    if socket.write_all(&[*byte]).is_err() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(60));
                }
            });

            // Scale the legacy 45-second budget to 200ms for a short streaming test.
            let body_timeout = payload_body_timeout(infrastructure).map(|timeout| timeout / 225);
            let mut response = agent(5, body_timeout)
                .get(format!("http://{address}/payload"))
                .config()
                .https_only(false)
                .build()
                .call()
                .unwrap();
            let mut bytes = Vec::new();
            let result = response.body_mut().as_reader().read_to_end(&mut bytes);
            drop(response);
            server.join().unwrap();
            result.map(|_| bytes)
        }

        assert_eq!(receive(Infrastructure::Od).unwrap(), b"payload");
        let error = receive(Infrastructure::Pi).unwrap_err();
        assert!(error.to_string().contains("timeout: receive body"));
    }

    #[test]
    fn extraction_rejects_escape_and_collision_before_writing_outside_stage() {
        for names in [["../outside", "file"], ["A/file", "a/other"]] {
            let t = tempfile::tempdir().unwrap();
            let archive = t.path().join("release.zip");
            let mut zip = zip::ZipWriter::new(File::create(&archive).unwrap());
            for name in names {
                zip.start_file(name, zip::write::SimpleFileOptions::default())
                    .unwrap();
                zip.write_all(b"test").unwrap();
            }
            zip.finish().unwrap();
            assert!(extract(&archive, &t.path().join("staging"), |_, _| {}).is_err());
            assert!(!t.path().join("outside").exists());
        }
    }
    #[test]
    fn extraction_refuses_existing_destination() {
        let t = tempfile::tempdir().unwrap();
        assert!(extract(&t.path().join("missing"), t.path(), |_, _| {}).is_err());
    }
}
