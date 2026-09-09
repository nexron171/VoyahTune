use crate::{adb::Adb, inventory::file_hash, payload::sha256, Error, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};
pub const DEVICE_STATE: &str = "/data/local/voyahtune-installer";
pub struct Operation {
    pub dir: PathBuf,
    pub id: String,
}
impl Operation {
    pub fn create(serial: &str, root: &Path) -> Result<Self> {
        let device = hex::encode(Sha256::digest(serial.as_bytes()));
        let base = root.join(&device[..16]);
        fs::create_dir_all(&base)?;
        let id = format!(
            "{}-{}",
            chrono::Utc::now().format("%Y%m%dT%H%M%S%3f"),
            std::process::id()
        );
        let dir = tempfile::Builder::new()
            .prefix(&format!("{id}-"))
            .tempdir_in(&base)?
            .keep();
        let id = dir.file_name().unwrap().to_string_lossy().into_owned();
        Ok(Self { dir, id })
    }
    pub fn snapshot(&self, adb: &Adb, paths: &[String]) -> Result<Vec<Snapshot>> {
        let folder = self.dir.join("backup");
        fs::create_dir_all(&folder)?;
        let mut result = Vec::new();
        for (i, path) in paths.iter().enumerate() {
            let hash = file_hash(adb, path)?;
            let local = format!("backup/{i}");
            if let Some(h) = &hash {
                let p = self.dir.join(&local);
                adb.pull(path, &p)?;
                if sha256(&p)? != *h {
                    return Err(Error::new(
                        "BACKUP_HASH",
                        "Резервная копия отличается от исходного файла",
                    )
                    .detail(path));
                }
            }
            result.push(Snapshot {
                path: path.clone(),
                sha256: hash,
                local,
            });
            write_json(&self.dir.join("backup.json"), &result)?;
        }
        Ok(result)
    }
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Snapshot {
    pub path: String,
    pub sha256: Option<String>,
    pub local: String,
}
pub fn write_json(path: &Path, data: &impl Serialize) -> Result<()> {
    let mut f = tempfile::NamedTempFile::new_in(
        path.parent()
            .ok_or_else(|| Error::new("REPORT_PATH", "Не определена папка отчёта"))?,
    )?;
    serde_json::to_writer_pretty(&mut f, data)?;
    f.write_all(b"\n")?;
    f.as_file().sync_all()?;
    // persist atomically replaces the destination on Unix and Windows. Never
    // unlink the previous journal: a crash must leave either complete version.
    f.persist(path).map_err(|e| Error::from(e.error))?;
    Ok(())
}
pub fn data_dir() -> Result<PathBuf> {
    directories::ProjectDirs::from("ru", "VoyahTune", "Installer")
        .map(|p| p.data_local_dir().join("operations"))
        .ok_or_else(|| Error::new("DATA_DIRECTORY", "Не удалось определить папку журналов"))
}
