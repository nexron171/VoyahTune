//! Minimal bounded reader for the manifest root of Android binary XML.
use crate::{Error, Result};
use std::{fs::File, io::Read, path::Path};
fn bad() -> Error {
    Error::new("APK_IDENTITY", "Некорректный AndroidManifest.xml APK")
}
fn u16at(b: &[u8], p: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(
        b.get(p..p + 2).ok_or_else(bad)?.try_into().unwrap(),
    ))
}
fn u32at(b: &[u8], p: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(
        b.get(p..p + 4).ok_or_else(bad)?.try_into().unwrap(),
    ))
}
fn len8(b: &[u8], p: &mut usize) -> Result<usize> {
    let a = *b.get(*p).ok_or_else(bad)?;
    *p += 1;
    Ok(if a & 128 != 0 {
        let v = *b.get(*p).ok_or_else(bad)?;
        *p += 1;
        (((a & 127) as usize) << 8) | v as usize
    } else {
        a as usize
    })
}
fn len16(b: &[u8], p: &mut usize) -> Result<usize> {
    let a = u16at(b, *p)?;
    *p += 2;
    Ok(if a & 32768 != 0 {
        let v = u16at(b, *p)?;
        *p += 2;
        (((a & 32767) as usize) << 16) | v as usize
    } else {
        a as usize
    })
}
fn strings(chunk: &[u8]) -> Result<Vec<String>> {
    let header = u16at(chunk, 2)? as usize;
    let count = u32at(chunk, 8)? as usize;
    let utf8 = u32at(chunk, 16)? & 256 != 0;
    let start = u32at(chunk, 20)? as usize;
    if count > 65536 || header < 28 || start < header + count * 4 {
        return Err(bad());
    }
    (0..count)
        .map(|i| {
            let mut p = start
                .checked_add(u32at(chunk, header + i * 4)? as usize)
                .ok_or_else(bad)?;
            if utf8 {
                len8(chunk, &mut p)?;
                let n = len8(chunk, &mut p)?;
                if chunk.get(p + n) != Some(&0) {
                    return Err(bad());
                }
                String::from_utf8(chunk.get(p..p + n).ok_or_else(bad)?.to_vec()).map_err(|_| bad())
            } else {
                let n = len16(chunk, &mut p)?;
                if n > 1024 * 1024 {
                    return Err(bad());
                }
                if u16at(chunk, p + n * 2)? != 0 {
                    return Err(bad());
                }
                let data = (0..n)
                    .map(|i| u16at(chunk, p + i * 2))
                    .collect::<Result<Vec<_>>>()?;
                String::from_utf16(&data).map_err(|_| bad())
            }
        })
        .collect()
}
pub fn read(path: &Path) -> Result<(String, u64, String)> {
    let mut zip = zip::ZipArchive::new(File::open(path)?).map_err(|_| bad())?;
    let mut f = zip.by_name("AndroidManifest.xml").map_err(|_| bad())?;
    if f.size() > 2 * 1024 * 1024 {
        return Err(bad());
    }
    let mut b = vec![];
    f.read_to_end(&mut b)?;
    parse(&b)
}
fn parse(b: &[u8]) -> Result<(String, u64, String)> {
    if u16at(b, 0)? != 3 || u32at(b, 4)? as usize != b.len() {
        return Err(bad());
    }
    let mut p = u16at(b, 2)? as usize;
    let mut pool = None;
    while p < b.len() {
        let kind = u16at(b, p)?;
        let header = u16at(b, p + 2)? as usize;
        let size = u32at(b, p + 4)? as usize;
        if header < 8 || size < header {
            return Err(bad());
        }
        let c = b
            .get(p..p.checked_add(size).ok_or_else(bad)?)
            .ok_or_else(bad)?;
        if kind == 1 {
            if pool.is_some() {
                return Err(bad());
            }
            pool = Some(strings(c)?);
        }
        if kind == 0x102 {
            let pool = pool.as_ref().ok_or_else(bad)?;
            let string = |index: u32| pool.get(index as usize).map(String::as_str).ok_or_else(bad);
            if header != 16 || string(u32at(c, 20)?)? != "manifest" {
                return Err(bad());
            }
            let start = header + u16at(c, 24)? as usize;
            let attr_size = u16at(c, 26)? as usize;
            let count = u16at(c, 28)? as usize;
            if attr_size < 20 || start < 36 || count > 128 {
                return Err(bad());
            }
            let (mut package, mut code, mut name) = (None, None, None);
            for i in 0..count {
                let offset = start + i * attr_size;
                let a = c.get(offset..offset + attr_size).ok_or_else(bad)?;
                let key = string(u32at(a, 4)?)?;
                let ns = u32at(a, 0)?;
                let raw = u32at(a, 8)?;
                let text = || {
                    if raw != u32::MAX {
                        string(raw).map(str::to_owned)
                    } else if a[15] == 3 {
                        string(u32at(a, 16)?).map(str::to_owned)
                    } else {
                        Err(bad())
                    }
                };
                if ns == u32::MAX && key == "package" {
                    if package.is_some() {
                        return Err(bad());
                    }
                    package = Some(text()?);
                }
                if ns != u32::MAX && string(ns)? == "http://schemas.android.com/apk/res/android" {
                    match key {
                        "versionCode" => {
                            if code.is_some() || ![0x10, 0x11].contains(&a[15]) {
                                return Err(bad());
                            }
                            code = Some(u32at(a, 16)? as u64);
                        }
                        "versionCodeMajor" if u32at(a, 16)? != 0 => return Err(bad()),
                        "versionName" => {
                            if name.is_some() {
                                return Err(bad());
                            }
                            name = Some(text()?);
                        }
                        _ => {}
                    }
                }
            }
            return Ok((
                package.ok_or_else(bad)?,
                code.ok_or_else(bad)?,
                name.ok_or_else(bad)?,
            ));
        }
        p += size;
    }
    Err(bad())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn truncated_or_text_manifest_is_rejected() {
        for b in [&b""[..], &b"<manifest/>"[..], &[3, 0, 8, 0, 8, 0, 0, 0][..]] {
            assert!(parse(b).is_err());
        }
    }
}
