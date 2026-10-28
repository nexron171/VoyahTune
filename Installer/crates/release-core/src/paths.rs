/// Portable paths: reject traversal, Windows drives/devices and ambiguous names on macOS/Windows.
pub fn safe_path(name: &str) -> bool {
    !name.is_empty()
        && name.len() < 1024
        && name.split('/').all(|part| {
            !part.is_empty()
                && part != "."
                && part != ".."
                && !part.ends_with('.')
                && part
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
                && !matches!(
                    part.split('.')
                        .next()
                        .unwrap()
                        .to_ascii_lowercase()
                        .as_str(),
                    "con"
                        | "prn"
                        | "aux"
                        | "nul"
                        | "com1"
                        | "com2"
                        | "com3"
                        | "com4"
                        | "com5"
                        | "com6"
                        | "com7"
                        | "com8"
                        | "com9"
                        | "lpt1"
                        | "lpt2"
                        | "lpt3"
                        | "lpt4"
                        | "lpt5"
                        | "lpt6"
                        | "lpt7"
                        | "lpt8"
                        | "lpt9"
                )
        })
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::{fs, os::unix::fs::symlink};

    #[test]
    fn destination_link_or_linked_parent_cannot_reach_a_write() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().canonicalize().unwrap();
        fs::create_dir(root.join("owned")).unwrap();
        fs::create_dir(root.join("external")).unwrap();
        symlink(root.join("external"), root.join("owned/link")).unwrap();
        assert!(no_links(&root.join("owned/link/agents.dex")).is_err());
        assert!(no_links(&root.join("owned/link")).is_err());
        assert!(no_links(&root.join("owned/missing/agents.dex")).is_ok());
    }
}

pub fn no_links(path: &std::path::Path) -> std::io::Result<()> {
    let mut current = std::path::PathBuf::new();
    for part in path.components() {
        current.push(part);
        match std::fs::symlink_metadata(&current) {
            Ok(m) if m.file_type().is_symlink() => {
                return Err(std::io::Error::other(format!(
                    "Ссылка в целевом пути {}",
                    current.display()
                )))
            }
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}
