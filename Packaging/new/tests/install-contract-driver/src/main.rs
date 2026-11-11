use release_core::{
    paths::no_links,
    recipe::Recipe,
    runtime_maintenance::{Operation, RuntimeMaintenance, Step},
};
use serde_json::{json, Value};
use std::{
    fs,
    io::{self, Read},
    path::Path,
};

fn lifecycle(input: &Value) -> io::Result<Value> {
    let events = input["events"]
        .as_array()
        .ok_or_else(|| io::Error::other("events missing"))?;
    let has = |event| events.iter().any(|value| value.as_str() == Some(event));
    let removal = has("remove");
    let mut maintenance = RuntimeMaintenance::new(if removal {
        Operation::Remove
    } else {
        Operation::Install
    });
    let steps: &[Step] = if removal {
        &[Step::Block, Step::Stop, Step::Remove, Step::Reboot]
    } else {
        &[
            Step::Block,
            Step::Stop,
            Step::Copy,
            Step::Cleanup,
            Step::Reboot,
            Step::Check,
        ]
    };
    for &step in steps {
        let result = maintenance.execute(step, || {
            // Device command/IPC failures are boundary inputs; ordering and stop-on-error are production code.
            if (step == Step::Stop && has("stop-failure"))
                || (step == Step::Copy && has("copy-failure"))
            {
                return Err(io::Error::other("device operation failed"));
            }
            Ok(())
        });
        if result.is_err() {
            break;
        }
    }
    Ok(json!({"trace": maintenance.trace()}))
}

fn device_path(root: &Path, path: &str) -> std::path::PathBuf {
    root.join(path.trim_start_matches('/'))
}

fn cleanup(input: &Value) -> io::Result<Value> {
    let directory = tempfile::tempdir()?;
    let root = directory.path().canonicalize()?;
    let runtime = root.join("data/local/bin/voyahtune-new");
    fs::create_dir_all(&runtime)?;
    let files = input["legacyFiles"]
        .as_array()
        .ok_or_else(|| io::Error::other("legacyFiles missing"))?;
    for name in files {
        fs::write(runtime.join(name.as_str().unwrap()), b"old runtime")?;
    }
    let settings = root.join("user-settings.json");
    if input["userSettings"] == true {
        fs::write(&settings, b"user choice")?;
    }
    for path in Recipe::default().cleanup_files() {
        let destination = device_path(&root, &path);
        no_links(destination.parent().unwrap())?;
        match fs::remove_file(destination) {
            Ok(()) => (),
            Err(error) if error.kind() == io::ErrorKind::NotFound => (),
            Err(error) => return Err(error),
        }
    }
    let remaining: Vec<_> = files
        .iter()
        .filter(|name| runtime.join(name.as_str().unwrap()).exists())
        .cloned()
        .collect();
    Ok(json!({"legacyFiles": remaining, "userSettings": settings.exists()}))
}

fn ota() -> io::Result<Value> {
    let directory = tempfile::tempdir()?;
    let root = directory.path().canonicalize()?;
    let recipe = Recipe::default();
    let source = root.join("new");
    fs::write(&source, b"new service")?;
    for file in &recipe.files {
        let target = device_path(&root, &file.destination);
        fs::create_dir_all(target.parent().unwrap())?;
        fs::write(&target, b"installed service")?;
        if file.delivered_by_ota() {
            fs::copy(&source, &target)?;
        }
    }
    let stable = recipe
        .files
        .iter()
        .find(|file| file.artifact == "voyahtune-updater")
        .ok_or_else(|| io::Error::other("OTA service absent from recipe"))?;
    let stable_replaced =
        fs::read(device_path(&root, &stable.destination))? != b"installed service";
    Ok(json!({"stableComponentReplaced": stable_replaced}))
}

#[cfg(unix)]
fn symlink_install() -> io::Result<Value> {
    let directory = tempfile::tempdir()?;
    let root = directory.path().canonicalize()?;
    let external = root.join("external.dex");
    fs::write(&external, b"user file")?;
    let target = root.join("agents.dex");
    std::os::unix::fs::symlink(&external, &target)?;
    let mut written = 0;
    let result = no_links(&target).and_then(|()| {
        fs::write(&target, b"new agent")?;
        written += 1;
        Ok(())
    });
    Ok(
        json!({"filesWritten": written, "state": if result.is_err() { "failed" } else { "installed" }}),
    )
}

#[cfg(not(unix))]
fn symlink_install() -> io::Result<Value> {
    Err(io::Error::other("This contract requires a Unix filesystem"))
}

fn observe(case: &Value) -> io::Result<Value> {
    let input = &case["input"];
    if case["contract"] == "installation.lifecycle" {
        return lifecycle(input);
    }
    match input["operation"].as_str() {
        Some("cleanup") => cleanup(input),
        Some("ota") => ota(),
        Some("install") => symlink_install(),
        _ => Err(io::Error::other("unsupported installation operation")),
    }
}

fn main() -> io::Result<()> {
    let mut request = String::new();
    io::stdin().read_to_string(&mut request)?;
    let request: Value = serde_json::from_str(&request)?;
    let results: Vec<_> = request["cases"]
        .as_array()
        .unwrap()
        .iter()
        .map(|case| match observe(case) {
            Ok(observed) => json!({"id": case["id"], "observed": observed}),
            Err(error) => json!({"id": case["id"], "error": error.to_string()}),
        })
        .collect();
    println!("{}", json!({"schema": 1, "results": results}));
    Ok(())
}
