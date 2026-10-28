# Единый native/Java runtime

Runtime предназначен для Android 11 ARM32 и ARM64. ARM64 Go-supervisor выбирает
helper и библиотеку по ELF целевого процесса, bootstrap загружает общий Java DEX.
LSPlant устанавливает перехваты.
OEM-различия определяются доступными классами, сигнатурами и моделью автомобиля.

## Состав

- `java/` — общий registry, управление модулями и ресурсами, OEM-адаптеры. Все 16
  статических определений из `config/agents.json` и динамические `client.<package>`
  используют один runtime. Окна, телефон, руль, клавиатура, ACC/reset, Apollo,
  MultiDisplay, Launcher, RDS и приборка имеют самостоятельные владельцы ресурсов.
- `native/` — ARM/Thumb и ARM64 attach, bootstrap, JNI/LSPlant, восстановление состояния потока.
  Версии зависимостей и локальные патчи закреплены в `dependencies.lock.json`.
- `loader/` — планы модулей, ограничение повторов, выбор клавиатуры, Apollo admission,
  durable journal, аутентифицированные status/quiesce и восстановление после сбоя.
- `config/` — schema 2 manifest, настройки клавиатуры и точные устаревшие пути.
- `system/` — init/wrapper/stop для сервиса `voyahtune_load`.

Один resident принадлежит boot ID/PID/startTicks/UID и поколениям native/Java кода.
Повторный запуск supervisor восстанавливает его из журнала без повторного attach.
`active` публикуется после завершения helper, освобождения tracee и полного ACK всех
обязательных модулей. Смена живого DEX/SO даёт `restart-required`.

В `AgentRuntime` переключатель `USE_DIRECT_HOOKS = true` выбирает прямые
LSPlant-перехваты без обхода DEX и подготовки вызывающих методов. Значение `false`
включает сохранённый путь с `AndroidCallers` и освобождением временного индекса после
применения плана и quiesce. Смена значения требует пересборки runtime и перезапуска
целевых процессов; штатная установка выполняет reboot.

Если ART успел встроить тело целевого метода в вызывающий код
до установки перехвата, такой вызов может обойти подмену. Ранняя установка уменьшает
это окно для JIT, но не гарантирует перехват уже скомпилированного AOT-кода.
Правила ART 11 нельзя переносить на другие версии Android без проверки.
Таймеры, listeners и перехваты принадлежат модулю; disable/quiesce ждут завершения
выполняющихся callbacks и worker.

## Сборка и проверки

Нужны Python 3.12+, Go 1.23+, JDK 21, host C++20 compiler, Rust из
`Installer/rust-toolchain.toml`, Android SDK/NDK/CMake из
`native/dependencies.lock.json` и закреплённый Gson из локального Gradle cache.
`ANDROID_SDK_ROOT` и `JAVA_HOME` переопределяют пути среды.

```sh
sh Packaging/new/check.sh
sh Packaging/new/build.sh
```

`check.sh` выполняет обязательные 450 JSON-контрактов через production Java/Go/native/
Rust компоненты, тесты registry/модулей и общие установочные проверки.
OEM/ptrace/JNI boundary в host-тестах может быть подменён; исполнение на Android
проверяется отдельно. `build.sh` повторяет обязательный gate перед DEX и создаёт
`build/runtime/`, `build/system/` и `build/release-inputs.json` для общего recipe.
Он не собирает APK, Payload или Installer и ничего не устанавливает.

В runtime входят `voyahtune-loader`, ARM64 `voyahtune-inject`/`libvoyahtune-agent.so`,
ARM32 `voyahtune-inject-arm`/`libvoyahtune-agent-arm.so`,
`agents.dex`, `agents.json`, `runtime-manifest.json`, `stop-loader.sh` и JSON-настройки.
Manifest содержит SHA-256 компонентов и поколения native/Java. Обновление только
supervisor не меняет Java generation. Исполняемые файлы имеют режим 755, данные — 644.

## Установка, обновление и удаление

USB и OTA используют единый recipe и порядок обслуживания: блокировка admission,
quiesce/drain, остановка сервиса, замена компонентов, очистка точных старых путей,
перезагрузка и postboot-проверка. Если supervisor погиб, установленный loader
может завершить resident по durable journal через offline quiesce. Ошибка или
неподтверждённое завершение запрещают замену файлов. Текущий boot marker не даёт
подключать новую реализацию к процессам со старыми агентами до reboot.

Первое обновление root-службы выполняется через USB с Installer 1.8.4; требуется
его пересборка из-за нового порядка установки и встроенных helpers. Последующие
совместимые OTA сохраняют собственные стабильные компоненты службы. Допуск OTA
определяет `minimumOtaVersion`: при выпуске первого совместимого релиза минимум
равен базовой версии этого VoyahTune payload. [Процесс выпуска](../../Docs/releasing.md).

Удаление quiesces native runtime до остановки, очищает компоненты и журналы,
выполняет reboot и завершает очистку собственных настроек. Имена прежней Frida
остаются только для распознавания старых установок и их очистки/восстановления.
`Utils/frida` сохранён как отдельный инструмент ручной диагностики и не включён
в сборку, runtime, payload или автоматическое восстановление. APK Native/RestoreMode
не резервируются.

## Диагностика

```sh
/data/local/bin/voyahtune-new/voyahtune-loader --check
/data/local/bin/voyahtune-new/voyahtune-loader --health
/data/local/bin/voyahtune-new/voyahtune-loader --check-hooks
/data/local/bin/voyahtune-new/voyahtune-loader --control-status
/data/local/bin/voyahtune-new/voyahtune-loader --quiesce --control-timeout 60s
```

`--check` валидирует компоненты без инъекций; `--check-hooks` дополнительно
проверяет живые identity и полные ACK. Состояние находится в
`/data/local/tmp/voyahtune-new/`; Native получает совместимый `publishHookStatusV1`.
Остановка сервиса сама по себе не подтверждает выгрузку resident: сначала нужен
успешный quiesce. `stop-loader.sh` соблюдает этот порядок.
После quiesce тот же resident требует перезапуска процесса, даже если поколение
кода не изменилось. Ошибки Java содержат ограниченную цепочку причин; полный стек
пишется в logcat с тегом `VoyahTuneAgent`.

Проверки на VoyEmu A11 подтверждают ARM64 attach/recovery/cancellation и отдельные
UI/OEM ABI, но не выполнение CAN-команд и всех функций на автомобиле. Производственный
init сохраняет существующую SELinux-политику проекта; Enforcing на стенде с su-доменом
не доказывает совместимость политики автомобиля. VoyEmu staging не отключает JIT.
