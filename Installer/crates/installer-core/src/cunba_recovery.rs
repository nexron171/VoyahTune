//! Reversible OD-only isolation of the inspected Cunba loader.
use crate::{
    adb::Adb,
    cunba,
    events::{EventCallback, Events},
    infrastructure::Infrastructure,
    payload::Payload,
    recovery::{self, Operation},
    Error, Result,
};
use serde::Serialize;
use serde_json::json;
use std::{
    path::Path,
    thread,
    time::{Duration, Instant},
};

const SCRIPT: &str = include_str!("cunba_recovery.sh");
const JOURNAL: &str = "/data/local/voyahtune-cunba-recovery";
const COMMAND_TIMEOUT: Duration = Duration::from_secs(35);
const BOOT_TIMEOUT: Duration = Duration::from_secs(180);
const OBSERVATION: Duration = Duration::from_secs(30);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Outcome {
    pub report_path: String,
}

fn progress(adb: &Adb, message: &str) -> Result<()> {
    adb.events
        .emit("cunba-progress", Some("cunba"), message, json!({}))
}

fn execute(adb: &Adb, action: &str) -> Result<()> {
    // action is selected by the orchestrator, never supplied by the caller.
    adb.shell(
        &format!("action={action}\n{SCRIPT}"),
        Duration::from_secs(90),
    )?;
    Ok(())
}

fn root(adb: &Adb) -> Result<()> {
    adb.run(&["root"], None, Duration::from_secs(15))?
        .checked("Не удалось запросить root")?;
    adb.run(&["wait-for-device"], None, COMMAND_TIMEOUT)?
        .checked("ГУ не вернулось по USB")?;
    if adb.shell("id -u", Duration::from_secs(10))? != "0" {
        return Err(Error::new(
            "CUNBA_ROOT_REQUIRED",
            "Для отключения Cunba нужен root",
        ));
    }
    Ok(())
}

fn verify(adb: &Adb, previous_boot: &str) -> Result<()> {
    progress(adb, "Ожидаем новую загрузку Android")?;
    let deadline = Instant::now() + BOOT_TIMEOUT;
    let mut stable: Option<(String, Instant)> = None;
    while Instant::now() < deadline {
        let sample = (|| {
            root(adb)?;
            let identity = adb.shell(
                "set -e\n[ \"$(getprop sys.boot_completed)\" = 1 ]\nservice check activity | grep -q ': found'\nservice check package | grep -q ': found'\ncat /proc/sys/kernel/random/boot_id\nfor name in system_server com.qinggan.app.launcher; do\n pid=$(pidof \"$name\"); [ -n \"$pid\" ]; cat /proc/$pid/stat; done",
                Duration::from_secs(10),
            )?;
            if identity.lines().next() == Some(previous_boot) || identity.lines().count() != 3 {
                return Err(Error::new(
                    "CUNBA_BOOT_PENDING",
                    "Полная перезагрузка ещё не подтверждена",
                ));
            }
            let output = adb.shell(
                &format!("CUNBA_RECOVERY_VERIFY=1\n{}", cunba::PROBE),
                Duration::from_secs(20),
            )?;
            if output.trim() != "CUNBA_CLEAR" {
                return Err(Error::new(
                    "CUNBA_STILL_ACTIVE",
                    "После перезагрузки обнаружены компоненты Cunba",
                )
                .detail(output));
            }
            // /proc/stat includes counters; only PID and process start time identify a generation.
            let identity = identity
                .lines()
                .enumerate()
                .map(|(index, line)| {
                    if index == 0 {
                        return line.to_owned();
                    }
                    let (pid, rest) = line.split_once(' ').unwrap_or_default();
                    let fields = rest
                        .rsplit_once(')')
                        .map(|(_, tail)| tail)
                        .unwrap_or_default();
                    format!(
                        "{}:{}",
                        pid,
                        fields.split_whitespace().nth(19).unwrap_or_default()
                    )
                })
                .collect::<Vec<_>>()
                .join("\n");
            Ok(identity)
        })();
        match sample {
            Ok(identity) => {
                let (old, since) = stable.get_or_insert_with(|| (identity.clone(), Instant::now()));
                if *old != identity {
                    *old = identity;
                    *since = Instant::now();
                }
                if since.elapsed() >= OBSERVATION {
                    return Ok(());
                }
            }
            Err(error) if error.code == "CUNBA_STILL_ACTIVE" => return Err(error),
            Err(_) => stable = None,
        }
        thread::sleep(Duration::from_secs(3));
    }
    Err(Error::new(
        "CUNBA_VERIFY_TIMEOUT",
        "Не удалось подтвердить отсутствие Cunba и стабильную загрузку Android",
    ))
}

pub fn disable(
    adb_path: &Path,
    payload: &Payload,
    serial: &str,
    confirmed: bool,
    logs: &Path,
    callback: EventCallback,
) -> Result<Outcome> {
    if !confirmed
        || payload.manifest.infrastructure != Infrastructure::Od
        || payload.manifest.removal_only
    {
        return Err(Error::new(
            "CUNBA_CONSENT_REQUIRED",
            "Подтвердите отключение Cunba перед установкой OD",
        ));
    }
    payload.verify()?;
    let operation = Operation::create(serial, logs)?;
    let events = Events::new(
        operation.id.clone(),
        Some(&operation.dir.join("events.jsonl")),
        callback,
    )?;
    let adb = Adb::new(adb_path, events)?.with_device(serial)?;
    let result: Result<()> = (|| {
        adb.require_single()?;
        root(&adb)?;
        let state = adb.shell(
            &format!("if [ -d {JOURNAL} ]; then cat {JOURNAL}/state; else echo absent; fi"),
            COMMAND_TIMEOUT,
        )?;
        if state != "absent" {
            // An interrupted operation must restore first; never overwrite its backups.
            if matches!(
                state.as_str(),
                "preparing" | "prepared" | "applying" | "reboot-pending" | "restoring"
            ) {
                progress(
                    &adb,
                    "Восстанавливаем изменения прерванного отключения Cunba",
                )?;
                execute(&adb, "rollback").map_err(|error| Error::new("CUNBA_ROLLBACK_REQUIRED", "Не удалось восстановить Cunba. Установка заблокирована; восстановите USB-соединение и повторите восстановление.").detail(error))?;
            }
            return Err(Error::new("CUNBA_RECOVERY_EXISTS", "Сохранена предыдущая операция Cunba. Установка заблокирована до повторной диагностики.")
            .detail(state));
        }
        match cunba::check(&adb, COMMAND_TIMEOUT) {
            Err(error) if error.code == "CUNBA_DETECTED" => {}
            Err(error) => return Err(error),
            Ok(()) => {
                return Err(Error::new(
                    "CUNBA_NOT_FOUND",
                    "Cunba не обнаружена. Выполните новую проверку автомобиля.",
                ))
            }
        }
        progress(
            &adb,
            "Сохраняем состояние Cunba и проверяем возможность обратимого отключения",
        )?;
        let result: Result<()> = (|| {
            execute(&adb, "prepare")?;
            let boot = adb.shell("cat /proc/sys/kernel/random/boot_id", COMMAND_TIMEOUT)?;
            progress(&adb, "Отключаем загрузчик и инжекторы Cunba")?;
            execute(&adb, "apply")?;
            progress(&adb, "Перезагружаем ГУ для выгрузки внедрённых агентов")?;
            adb.run(&["reboot"], None, Duration::from_secs(15))?
                .checked("Не удалось перезагрузить ГУ")?;
            verify(&adb, &boot)?;
            execute(&adb, "commit")?;
            Ok(())
        })();
        let result: Result<()> = match result {
            Ok(()) => Ok(()),
            Err(error) => {
                progress(
                    &adb,
                    "Отключение не подтверждено. Восстанавливаем исходные файлы и настройки Cunba",
                )?;
                let rollback = (|| {
                    root(&adb)?;
                    let present = adb.shell(
                        &format!("[ -d {JOURNAL} ] && echo PRESENT || echo ABSENT"),
                        COMMAND_TIMEOUT,
                    )?;
                    if present == "PRESENT" {
                        execute(&adb, "rollback")?;
                    }
                    Ok::<_, Error>(())
                })();
                Err(match rollback {
                Ok(()) => Error::new("CUNBA_DISABLE_FAILED", "Не удалось отключить Cunba. Изменения восстановлены, дальнейшая установка невозможна.").detail(error),
                Err(rollback_error) => Error::new("CUNBA_ROLLBACK_REQUIRED", "Не удалось завершить откат Cunba. Установка заблокирована; восстановите USB-соединение и повторите восстановление.")
                    .detail(format!("Отключение: {error}\nОткат: {rollback_error}")),
            })
            }
        };
        result
    })();
    let report = operation.dir.join("report.json");
    recovery::write_json(
        &report,
        &json!({"operation": "disable-cunba", "success": result.is_ok(), "error": result.as_ref().err(), "serial": serial}),
    )?;
    adb.events.emit(
        "cunba-finished",
        Some("cunba"),
        "Диагностика отключения Cunba завершена",
        json!({"reportPath": report.to_string_lossy(), "success": result.is_ok()}),
    )?;
    result?;
    Ok(Outcome {
        report_path: report.to_string_lossy().into_owned(),
    })
}
