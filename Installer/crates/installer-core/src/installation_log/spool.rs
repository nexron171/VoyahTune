use super::{Chunk, Record, SessionInfo, SpoolItem, Status};
use crate::{recovery, Result};
use chrono::Utc;
use std::{
    fs::File,
    io::{BufRead, BufReader, Read, Seek, SeekFrom},
    path::{Path, PathBuf},
};

const MAX_CHUNK_BYTES: usize = 512 * 1024;
const MAX_CHUNK_RECORDS: usize = 500;

#[derive(Default)]
pub(super) struct Cursor {
    pub offset: u64,
    pub index: u64,
}

pub(super) fn write_chunks(
    directory: &Path,
    info: &SessionInfo,
    cursor: &mut Cursor,
    snapshot_end: u64,
    mut notify: impl FnMut(&Path),
) -> Result<PathBuf> {
    loop {
        let index = cursor.index + 1;
        let mut request = Chunk {
            schema_version: 1,
            session_id: info.session_id.clone(),
            chunk_id: format!("{}:{index}", info.session_id),
            chunk_index: index,
            created_at: Utc::now().to_rfc3339(),
            final_chunk: false,
            first_sequence: None,
            last_sequence: None,
            session: info.clone(),
            logs: Vec::new(),
        };
        // Reserve space for sequence numbers and commas in the compact HTTP body.
        let mut bytes = serde_json::to_vec(&request)?.len() + 64;
        let mut journal = File::open(directory.join("logs.jsonl"))?;
        journal.seek(SeekFrom::Start(cursor.offset))?;
        let mut reader = BufReader::new(journal.take(snapshot_end - cursor.offset));
        let mut end = cursor.offset;
        let mut line = String::new();
        while request.logs.len() < MAX_CHUNK_RECORDS {
            line.clear();
            let length = reader.read_line(&mut line)?;
            if length == 0 {
                break;
            }
            // Recovery can encounter an interrupted final append.
            if !line.ends_with('\n') {
                end += length as u64;
                break;
            }
            let record: Record = serde_json::from_str(&line)?;
            let record_bytes = serde_json::to_vec(&record)?.len() + 1;
            if !request.logs.is_empty() && bytes + record_bytes > MAX_CHUNK_BYTES {
                break;
            }
            // An indivisible oversized record travels alone; never truncate evidence.
            bytes += record_bytes;
            end += length as u64;
            request.logs.push(record);
        }
        request.first_sequence = request.logs.first().map(|record| record.sequence);
        request.last_sequence = request.logs.last().map(|record| record.sequence);
        request.final_chunk = info.status != Status::InProgress && end == snapshot_end;
        let path = directory.join("outbox").join(format!("{index:012}.json"));
        recovery::write_json(
            &path,
            &SpoolItem {
                journal_end_offset: end,
                request,
            },
        )?;
        cursor.offset = end;
        cursor.index = index;
        notify(&path);
        if end == snapshot_end {
            return Ok(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::installation_log::InstallationLog;
    use serde_json::json;
    use std::fs;

    #[test]
    fn oversized_record_is_preserved_alone_and_partial_tail_is_not_uploaded() {
        use std::io::Write;

        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        let text = "x".repeat(MAX_CHUNK_BYTES + 1);
        session
            .record("step", "large-report", None, None, json!({"text":text}))
            .unwrap();
        session.finish(Status::Success, None).unwrap();
        let directory = session.directory();
        let large: SpoolItem = serde_json::from_reader(
            File::open(directory.join("outbox/000000000002.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(large.request.logs.len(), 1);
        assert_eq!(large.request.logs[0].data["text"], text);

        let mut journal = fs::OpenOptions::new()
            .append(true)
            .open(directory.join("logs.jsonl"))
            .unwrap();
        let offset = journal.metadata().unwrap().len();
        journal.write_all(b"{\"sequence\":").unwrap();
        let mut cursor = Cursor { offset, index: 3 };
        let path = write_chunks(
            &directory,
            &session.info(),
            &mut cursor,
            journal.metadata().unwrap().len(),
            |_| {},
        )
        .unwrap();
        let tail: SpoolItem = serde_json::from_reader(File::open(path).unwrap()).unwrap();
        assert!(tail.request.logs.is_empty());
        assert!(tail.request.final_chunk);
    }

    #[test]
    fn backlog_is_bounded_contiguous_and_ends_with_one_final_chunk() {
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        for _ in 0..620 {
            session
                .record(
                    "logcat",
                    "output",
                    None,
                    None,
                    json!({"text":"x".repeat(2048)}),
                )
                .unwrap();
        }
        session.finish(Status::Success, None).unwrap();
        let mut paths: Vec<_> = fs::read_dir(session.directory().join("outbox"))
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        paths.sort();
        assert!(paths.len() > 2);
        let mut sequences = Vec::new();
        let mut final_count = 0;
        for (index, path) in paths.iter().enumerate() {
            let item: SpoolItem = serde_json::from_reader(File::open(path).unwrap()).unwrap();
            assert!(serde_json::to_vec(&item.request).unwrap().len() <= MAX_CHUNK_BYTES);
            assert!(item.request.logs.len() <= MAX_CHUNK_RECORDS);
            if item.request.final_chunk {
                final_count += 1;
                assert_eq!(index, paths.len() - 1);
            }
            sequences.extend(item.request.logs.iter().map(|record| record.sequence));
        }
        assert_eq!(final_count, 1);
        assert_eq!(sequences, (1..=622).collect::<Vec<_>>());
    }

    #[test]
    fn packing_does_not_lock_appends_or_include_records_after_snapshot() {
        let root = tempfile::tempdir().unwrap();
        let session = InstallationLog::begin(root.path(), Path::new("/missing-adb"), None).unwrap();
        let first = session
            .cut_with(|_| {
                // This would deadlock if packing held the event writer's mutex.
                session
                    .record("step", "during-packing", None, None, json!({}))
                    .unwrap();
            })
            .unwrap();
        let item: SpoolItem = serde_json::from_reader(File::open(first).unwrap()).unwrap();
        assert_eq!(item.request.last_sequence, Some(1));
        session.finish(Status::Success, None).unwrap();
        let last: SpoolItem = serde_json::from_reader(
            File::open(session.directory().join("outbox/000000000002.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(last.request.first_sequence, Some(2));
        assert_eq!(last.request.last_sequence, Some(3));
    }
}
