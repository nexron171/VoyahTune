# Процесс установки GUI: порт классических скриптов

Актуально для Installer 1.8.3 и единого payload. GUI не запускает host install/remove:
порядок и команды исполняет Rust. [Контракт recipe](installer-protocol.md).

## Где менять процесс

- `Installer/crates/installer-core/src/engine.rs`: `execute()` задаёт порядок;
  методы ниже реализуют действия и обработку ошибок. Это исполняемая логика процесса.
- `Installer/crates/installer-core/src/plans.rs`: `classic_steps()` задаёт названия
  и порядок отображаемых шагов установки и удаления.
- `Installer/crates/installer-core/src/device_commands.rs`: тела **удалённых**
  команд `adb shell`, перенесённые из скриптов. Здесь нет запуска host-сценариев.
- `Installer/desktop/src/App.svelte`: отображение событий, текущего шага,
  завершённых шагов, ошибок и журнала. Здесь нет логики установки на автомобиль.

Команды автомобиля сопровождаются непосредственно в `device_commands.rs`,
общие helpers — в `Packaging/installer/common/`. Старые host-скрипты и генератор
команд удалены. GUI применяет общий recipe из `Packaging/new/`; единый remover
очищает как текущий runtime, так и известные файлы прежних установок.

## Порядок действий

| Шаг | Установка | Удаление |
| --- | --- | --- |
| Root/system | root, remount, при необходимости reboot | root, remount и проверка записи |
| Backup/signatures | Backup системных/runtime-файлов, без копий Native/RestoreMode APK; при другом APK-ключе reset данных | Без проверки APK-подписей |
| Runtime/files | Остановка loader, полный recipe, legacy init.logcat и boot transaction | Остановка, восстановление DNS/legacy, удаление runtime |
| Apps | Native/whitelist, RestoreMode и RunYN по условию модели | PackageManager uninstall и удаление системных файлов |
| Завершение | Reboot, CE/DE и запуск Native | Reboot |

Прежняя проверка checked/best-effort результатов сохранена. Единый релиз
устанавливается поверх предыдущего: recipe заменяет runtime и очищает старые
компоненты. Перезагрузка выгружает прежние eternalized-хуки.

Windows-вариант старого permission-check допускает неизвестного владельца;
эта особенность сохранена. Проверка синтаксиса legacy init.logcat через локальный
`sh -n` выполняется на macOS/Linux, как в `.sh`; Windows использует marker-проверки
из `.bat`. POSIX-shell для Windows устанавливать не нужно.

## Явно согласованные отличия и интерфейс

- Подготовительный шаг отключает известные конфликтующие решения. При обнаружении
  VoyahHlCTRL системная папка сохраняется на компьютере, выполняются force-stop,
  uninstall с `-k`, очистка системных файлов и перезагрузка. Ошибка резервирования
  или сохраняющийся конфликт останавливают процесс. Порядок задаётся в
  `engine.rs::resolve_canbus_conflict`; [подробности подготовки](installer-architecture.md#подготовка-без-дополнительных-диалогов).
- Пользователь вручную подтверждает единственный подключённый автомобиль.
- При несовпадении подписи Native/RestoreMode соответствующее приложение удаляется
  с данными и устанавливается заново. Native требует промежуточного reboot, чтобы
  Android убрал старую регистрацию; RestoreMode допускает повтор после конкретного
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` о подписи. Иные ошибки APK не запускают сброс.
- Выбор установки/удаления и DNS выполняется в GUI.
- Отмена — явное действие пользователя между шагами. Восстановление loader следует
  прежнему exit-recovery: до принятого reboot.
- Журнал и резервные копии находятся на компьютере. `backup/` рядом с каталогами
  операций одного автомобиля сохраняет предыдущие файлы, как каталог старого релиза.

Диагностическая инвентаризация показывает известные сведения, но не блокирует
установку из-за metadata, версии, хешей старого набора или изменения token.
`inventoryToken` оставлен в структуре Request, условием запуска он не является.

Установка использует общую с OTA блокировку на ГУ и снимает её перед финальной
перезагрузкой; postflight использует отдельную ограниченную по времени блокировку.
Старые receipt/ownership/hash-записи движка не используются как условия продолжения.
GUI предотвращает повторное нажатие во время своей операции.

SHA/подписи всего payload проверяются при сборке, импорте/выборе и перед применением.
Новая доставка использует каталог, SHA ZIP и атомарный кэш. Подписанные metadata
не вводят проверку ownership автомобиля. Remove не проверяет подписи установленных APK.

## Как проверять изменения

```sh
cargo test --manifest-path Installer/Cargo.toml -p installer-core -p installer-build
cargo build --release --manifest-path Installer/Cargo.toml -p installer-core --example fixture-driver
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" python3 Installer/tests/test_canbus.py
python3 Installer/tests/test_release.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" \
  python3 Installer/tests/test_installation.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" \
  python3 Installer/tests/integration.py
```

`test_installation.py` проверяет реальные эффекты Rust-движка на fake ADB:
права каталогов, очистку старых клиентов, отказ при ошибках записи/backup,
восстановление boot-файлов и сохранение данных при совместимых подписях.
`test_unified_installation.py` проверяет установку, повторную установку и удаление
единого runtime, восстановление чужого загрузчика и отказ при конфликте подписи RunYN.
Этим сценариям нужен заранее собранный подписанный unified payload.

При изменении установки обновляйте соответствующий метод движка и поведенческий
сценарий. Новый install/remove-шаг должен появиться и в `classic_steps()`.
Новые файлы schema 4 исполняются по recipe без изменения движка.
