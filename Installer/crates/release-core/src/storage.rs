use crate::{Error, Result};
use serde::Serialize;
use std::{io::Write, path::Path};
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
