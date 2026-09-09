use crate::{events::Events, Error, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub serial: String,
    pub state: String,
    pub model: Option<String>,
    pub product: Option<String>,
}
#[derive(Debug)]
pub struct Output {
    pub code: i32,
    pub stdout: String,
    pub stderr: String,
}
impl Output {
    pub fn checked(self, message: &str) -> Result<Self> {
        if self.code == 0 {
            Ok(self)
        } else {
            Err(classify_error(
                message,
                &format!("{}\n{}", self.stdout, self.stderr),
                self.code,
            ))
        }
    }
}
#[derive(Clone)]
pub struct Adb {
    path: PathBuf,
    pub serial: Option<String>,
    pub events: Events,
    pub step: Option<String>,
}
impl Adb {
    pub fn new(path: impl AsRef<Path>, events: Events) -> Result<Self> {
        let path = path.as_ref().canonicalize().map_err(|e| {
            Error::new(
                "ADB_MISSING",
                "Не найден комплектный ADB. Восстановите полный комплект установщика.",
            )
            .detail(e)
        })?;
        Ok(Self {
            path,
            serial: None,
            events,
            step: None,
        })
    }
    pub fn with_device(mut self, serial: &str) -> Result<Self> {
        if serial.is_empty()
            || serial.len() > 200
            || serial.chars().any(|c| c.is_control() || c.is_whitespace())
        {
            return Err(Error::new(
                "INVALID_SERIAL",
                "Некорректный идентификатор устройства",
            ));
        }
        self.serial = Some(serial.into());
        Ok(self)
    }
    pub fn run(&self, args: &[&str], input: Option<&[u8]>, timeout: Duration) -> Result<Output> {
        let mut command = Command::new(&self.path);
        if let Some(serial) = &self.serial {
            command.args(["-s", serial]);
        }
        command
            .args(args)
            .stdin(if input.is_some() {
                Stdio::piped()
            } else {
                Stdio::null()
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        self.events.emit("command-started", self.step.as_deref(), format!("adb {}", args.join(" ")), json!({"serial":self.serial,"timeoutSeconds":timeout.as_secs(),"script":input.map(String::from_utf8_lossy)}))?;
        let child = command.spawn().map_err(|e| {
            Error::new("ADB_START_FAILED", "Не удалось запустить комплектный ADB").detail(e)
        })?;
        let mut child = ChildGuard(child);
        // Drain both pipes concurrently; a full pipe must never deadlock push/pull or a failing shell.
        let (tx, rx) = mpsc::channel();
        for (stream, mut reader) in [
            (
                "stdout",
                Box::new(child.stdout.take().unwrap()) as Box<dyn Read + Send>,
            ),
            (
                "stderr",
                Box::new(child.stderr.take().unwrap()) as Box<dyn Read + Send>,
            ),
        ] {
            let tx = tx.clone();
            thread::spawn(move || {
                let mut buffer = [0u8; 8192];
                loop {
                    match reader.read(&mut buffer) {
                        Ok(0) => break,
                        Ok(n) => {
                            if tx.send((stream, buffer[..n].to_vec())).is_err() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            });
        }
        drop(tx);
        let writer = input.map(|input| {
            let bytes = input.to_vec();
            let mut stdin = child.stdin.take().unwrap();
            thread::spawn(move || stdin.write_all(&bytes))
        });
        let started = Instant::now();
        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let mut exit_status = None;
        loop {
            match rx.recv_timeout(Duration::from_millis(25)) {
                Ok((stream, bytes)) => {
                    self.events.emit(
                        "command-output",
                        self.step.as_deref(),
                        String::from_utf8_lossy(&bytes),
                        json!({"stream":stream}),
                    )?;
                    let output = if stream == "stdout" {
                        &mut stdout
                    } else {
                        &mut stderr
                    };
                    output.extend(bytes);
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    if exit_status.is_some() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(25));
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            if exit_status.is_none() {
                exit_status = child.try_wait()?;
            }
            if started.elapsed() >= timeout {
                let _ = child.kill();
                let _ = child.wait();
                return Err(Error::new("ADB_TIMEOUT", "ADB не завершил команду за отведённое время").detail(format!("adb {}\n{}\n{}",args.join(" "),String::from_utf8_lossy(&stdout),String::from_utf8_lossy(&stderr))).retry("Проверьте подключение автомобиля и повторите операцию после проверки состояния."));
            }
        }
        if let Some(writer) = writer {
            writer
                .join()
                .map_err(|_| Error::new("ADB_STDIN", "Не удалось передать сценарий в ADB"))??;
        }
        let output = Output {
            code: exit_status.unwrap().code().unwrap_or(-1),
            stdout: String::from_utf8_lossy(&stdout).replace('\r', ""),
            stderr: String::from_utf8_lossy(&stderr).replace('\r', ""),
        };
        self.events.emit(
            "command-finished",
            self.step.as_deref(),
            format!("Код завершения: {}", output.code),
            json!({"exitCode":output.code,"durationMs":started.elapsed().as_millis()}),
        )?;
        Ok(output)
    }
    pub fn shell(&self, script: &str, timeout: Duration) -> Result<String> {
        let output = self.run(&["shell", "sh", "-s"], Some(script.as_bytes()), timeout)?;
        let output = output
            .checked("Команда на автомобиле завершилась ошибкой")
            .map_err(|mut error| {
                error.detail = format!(
                    "Шаг: {}\nКоманда:\n{}\n{}",
                    self.step.as_deref().unwrap_or("проверка"),
                    script,
                    error.detail
                );
                error
            })?;
        Ok(output.stdout.trim().to_owned())
    }
    pub fn read(&self, script: &str) -> Result<String> {
        self.shell(script, Duration::from_secs(20))
    }
    pub fn package_paths(&self, package: &str) -> Result<Vec<String>> {
        let script = format!("pm path {}\n", quote(package));
        let output = self.run(
            &["shell", "sh", "-s"],
            Some(script.as_bytes()),
            Duration::from_secs(20),
        )?;
        parse_package_paths(output).map_err(|mut error| {
            error.detail = format!(
                "Шаг: {}\nКоманда: {}\n{}",
                self.step.as_deref().unwrap_or("проверка"),
                script.trim(),
                error.detail
            );
            error
        })
    }
    pub fn ensure_root(&self) -> Result<()> {
        self.require_single()?;
        self.run(&["root"], None, Duration::from_secs(20))?
            .checked("Не удалось запросить root-доступ")?;
        let until = Instant::now() + Duration::from_secs(40);
        loop {
            if let Ok(uid) = self.read("id -u\n") {
                if uid == "0" {
                    return Ok(());
                }
                if !uid.is_empty() {
                    return Err(Error::new(
                        "ROOT_UNAVAILABLE",
                        "Прошивка не разрешает системный доступ через ADB",
                    )
                    .detail(format!("uid={uid}")));
                }
            }
            if Instant::now() >= until {
                return Err(Error::new(
                    "ROOT_TIMEOUT",
                    "ADB не вернулся после запроса системного доступа",
                ));
            }
            thread::sleep(Duration::from_secs(1));
        }
    }
    pub fn devices(&self) -> Result<Vec<Device>> {
        let mut global = self.clone();
        global.serial = None;
        let output = global
            .run(&["devices", "-l"], None, Duration::from_secs(12))?
            .checked("Не удалось получить список устройств")?;
        parse_devices(&output.stdout)
    }
    pub fn require_single(&self) -> Result<Device> {
        let devices = self.devices()?;
        if devices.is_empty() {
            return Err(Error::new("NO_DEVICE", "Автомобиль не найден").retry(
                "Проверьте кабель, включите отладку по USB в инженерном меню и нажмите «Обновить».",
            ));
        }
        if devices.len() != 1 {
            return Err(Error::new("MULTIPLE_DEVICES", "Найдено несколько устройств").detail(serde_json::to_string(&devices)?).retry("Отключите другие Android-устройства и эмуляторы, затем нажмите «Обновить»."));
        }
        let device = devices.into_iter().next().unwrap();
        if device.state != "device" {
            return Err(Error::new(
                if device.state == "unauthorized" {
                    "ADB_UNAUTHORIZED"
                } else {
                    "ADB_OFFLINE"
                },
                if device.state == "unauthorized" {
                    "Разрешите отладку по USB на экране автомобиля"
                } else {
                    "Устройство обнаружено, но не отвечает"
                },
            )
            .detail(&device.state)
            .retry("Исправьте подключение и нажмите «Обновить»."));
        }
        if self
            .serial
            .as_ref()
            .is_some_and(|serial| *serial != device.serial)
        {
            return Err(Error::new(
                "DEVICE_CHANGED",
                "Подключён другой автомобиль. Требуется новое подтверждение.",
            ));
        }
        Ok(device)
    }
    pub fn push(&self, local: &Path, remote: &str) -> Result<()> {
        self.run(
            &[
                "push",
                local
                    .to_str()
                    .ok_or_else(|| Error::new("PATH_ENCODING", "Путь не представлен в UTF-8"))?,
                remote,
            ],
            None,
            Duration::MAX,
        )?
        .checked("Не удалось передать файл на автомобиль")?;
        Ok(())
    }
    pub fn pull(&self, remote: &str, local: &Path) -> Result<()> {
        self.run(
            &[
                "pull",
                remote,
                local
                    .to_str()
                    .ok_or_else(|| Error::new("PATH_ENCODING", "Путь не представлен в UTF-8"))?,
            ],
            None,
            Duration::MAX,
        )?
        .checked("Не удалось сохранить файл с автомобиля")?;
        Ok(())
    }
}
pub fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
pub fn parse_devices(output: &str) -> Result<Vec<Device>> {
    if !output
        .lines()
        .any(|l| l.trim() == "List of devices attached")
    {
        return Err(Error::new(
            "ADB_DEVICES_FORMAT",
            "ADB вернул неизвестный формат списка устройств",
        )
        .detail(output));
    }
    let mut devices = Vec::new();
    for line in output
        .lines()
        .skip_while(|l| l.trim() != "List of devices attached")
        .skip(1)
        .filter(|l| !l.trim().is_empty())
    {
        let fields: Vec<_> = line.split_whitespace().collect();
        if fields.len() < 2 {
            return Err(
                Error::new("ADB_DEVICES_FORMAT", "Неполная запись устройства").detail(line),
            );
        }
        let field = |prefix: &str| {
            fields
                .iter()
                .find_map(|x| x.strip_prefix(prefix))
                .map(str::to_owned)
        };
        devices.push(Device {
            serial: fields[0].into(),
            state: fields[1].into(),
            model: field("model:"),
            product: field("product:"),
        });
    }
    Ok(devices)
}
fn classify_error(message: &str, detail: &str, code: i32) -> Error {
    let (kind, text) = if detail.contains("unauthorized") {
        (
            "ADB_UNAUTHORIZED",
            "Подтвердите разрешение отладки по USB на автомобиле",
        )
    } else if detail.contains("device offline")
        || detail.contains("no devices/emulators")
        || detail.contains("device not found")
    {
        ("ADB_CONNECTION_LOST", "Потеряно соединение с автомобилем")
    } else if detail.contains("No space left") {
        (
            "DEVICE_NO_SPACE",
            "На автомобиле недостаточно свободного места",
        )
    } else if detail.contains("Read-only file system") {
        ("SYSTEM_READ_ONLY", "Системный раздел недоступен для записи")
    } else {
        ("ADB_COMMAND_FAILED", message)
    };
    Error::new(kind, text)
        .detail(format!("exit={code}\n{detail}"))
        .retry(
            "Устраните указанную причину и повторите операцию; состояние будет проверено заново.",
        )
}
// Every error path must reap the local process, including journal I/O failures.
struct ChildGuard(std::process::Child);
impl std::ops::Deref for ChildGuard {
    type Target = std::process::Child;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
impl std::ops::DerefMut for ChildGuard {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}
impl Drop for ChildGuard {
    fn drop(&mut self) {
        if self.0.try_wait().ok().flatten().is_none() {
            let _ = self.0.kill();
        }
        let _ = self.0.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn includes_offline_and_unauthorized() {
        let d = parse_devices("List of devices attached\ncar device product:p model:Voyah_Free\nphone unauthorized\nemulator-5554 offline\n").unwrap();
        assert_eq!(d.len(), 3);
        assert_eq!(d[1].state, "unauthorized");
        assert!(parse_devices("daemon failed").is_err());
    }
    #[test]
    fn quotes_shell_input() {
        assert_eq!(quote("a'b;$(x)"), "'a'\\''b;$(x)'");
    }
}

// Android returns exit=1 with empty output when a package does not exist.
// An actual shell/PackageManager error must not be mistaken for successful removal.
fn parse_package_paths(output: Output) -> Result<Vec<String>> {
    if output.code == 1 && output.stdout.trim().is_empty() && output.stderr.trim().is_empty() {
        return Ok(Vec::new());
    }
    let output = output.checked("Не удалось проверить регистрацию приложения")?;
    if !output.stderr.trim().is_empty() {
        return Err(
            Error::new("PACKAGE_QUERY", "Android вернул ошибку проверки приложения")
                .detail(output.stderr),
        );
    }
    output
        .stdout
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            line.trim()
                .strip_prefix("package:")
                .filter(|path| path.starts_with('/'))
                .map(str::to_owned)
                .ok_or_else(|| {
                    Error::new(
                        "PACKAGE_QUERY",
                        "Android вернул неизвестный ответ о приложении",
                    )
                    .detail(line)
                })
        })
        .collect()
}
#[cfg(test)]
mod package_query_tests {
    use super::*;
    #[test]
    fn missing_package_is_not_a_command_failure() {
        for code in [0, 1] {
            assert!(parse_package_paths(Output {
                code,
                stdout: String::new(),
                stderr: String::new()
            })
            .unwrap()
            .is_empty());
        }
        assert_eq!(
            parse_package_paths(Output {
                code: 0,
                stdout: "package:/data/app/test/base.apk\n".into(),
                stderr: String::new()
            })
            .unwrap(),
            vec!["/data/app/test/base.apk"]
        );
        for (code, stdout, stderr) in [
            (1, "", "Security exception"),
            (127, "", "pm: not found"),
            (0, "Failure [error]", ""),
            (1, "package:/test.apk", ""),
        ] {
            assert!(parse_package_paths(Output {
                code,
                stdout: stdout.into(),
                stderr: stderr.into()
            })
            .is_err());
        }
    }
}
