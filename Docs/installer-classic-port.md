# Процесс установки GUI: порт классических скриптов

Актуально для единого процесса Installer 1.1.0. GUI не запускает host install/remove:
порядок и команды исполняет Rust. [Контракт recipe](installer-protocol.md).

## Где менять процесс

- `Installer/crates/installer-core/src/engine.rs`: `execute()` задаёт порядок;
  методы ниже реализуют действия и обработку ошибок. Это исполняемая логика процесса.
- `Installer/crates/installer-core/src/plans.rs`: `classic_steps()` задаёт названия
  и порядок отображаемых шагов установки и удаления.
- `Installer/crates/installer-core/src/classic_commands.rs`: тела **удалённых**
  команд `adb shell`, перенесённые из скриптов. Здесь нет запуска host-сценариев.
  Над каждым фрагментом указан исходный файл и строка.
- `Installer/desktop/src/App.svelte`: отображение событий, текущего шага,
  завершённых шагов, ошибок и журнала. Здесь нет логики установки на автомобиль.

Эталоны: `Packaging/installer/device/install.sh`, `Packaging/installer/device/remove.sh`
и соответствующие `.bat`. Единое удаление использует полный remover независимо
от установленного набора, как было согласовано для GUI.

## Порядок действий

| Шаг | Установка | Удаление |
| --- | --- | --- |
| Root/system | root, remount, при необходимости reboot | root, remount и проверка записи |
| Backup/signatures | Backup; при другом APK-ключе отдельный reset данных | Без проверки APK-подписей |
| Runtime/files | Остановка loader, полный recipe, legacy init.logcat и boot transaction | Остановка, восстановление DNS/legacy, удаление runtime |
| Apps | Native/whitelist и RestoreMode | PackageManager uninstall и удаление системных файлов |
| Завершение | Reboot, CE/DE и запуск Native | Reboot |

Прежняя проверка checked/best-effort результатов сохранена. Нет выбора вариантов
установки и специальных переходов между ними. Существующий релиз обновляется
обычным процессом поверх либо после удаления.

Windows-вариант старого permission-check допускает неизвестного владельца;
эта особенность сохранена. Проверка синтаксиса legacy init.logcat через локальный
`sh -n` выполняется на macOS/Linux, как в `.sh`; Windows использует marker-проверки
из `.bat`. POSIX-shell для Windows устанавливать не нужно.

## Явно согласованные отличия и интерфейс

- GUI при владельце WRITE_CANBUS `com.voyah.hl.service` **или** наличии этого
  пакета у пользователя 0 показывает уведомление
  и предлагает удалить VoyahHlCTRL. Только после отдельного согласия сохраняется
  системная папка на компьютере, подготавливается `/system`, выполняются force-stop,
  uninstall для user 0, удаление `/system/priv-app/VoyahHlCTRL` и очистка package_cache.
  Затем обязательны reboot, ожидание Android/root и повторная проверка владельца разрешения и наличия пакета.
  Ошибка бэкапа или сохранение конфликта после reboot останавливают процесс.
  Эта ветка GUI находится в `engine.rs::resolve_canbus_conflict`, согласие —
  в `canbus.rs`. Классические `.sh/.bat` сохраняют прежний отказ при этом владельце.
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

Постоянного mutex на автомобиле и файлового mutex операций на компьютере нет.
Старый `/data/local/voyahtune-installer/lock/owner` очищается best effort после root;
новый маркер не создаётся. Старые receipt/ownership/hash-записи движка не используются
как условия продолжения. GUI предотвращает повторное нажатие во время своей операции.

SHA/подписи всего payload проверяются при сборке, импорте/выборе и перед применением.
Новая доставка использует каталог, SHA ZIP и атомарный кэш. Подписанные metadata
не вводят проверку ownership автомобиля. Remove не проверяет подписи установленных APK.

## Как проверять изменения

```sh
python3 Installer/scripts/sync-classic-commands.py --check
cargo test --manifest-path Installer/Cargo.toml -p installer-core -p installer-build
cargo build --release --manifest-path Installer/Cargo.toml -p installer-core --example fixture-driver
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" python3 Installer/tests/test_canbus.py
python3 Installer/tests/test_release.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" \
  python3 Installer/tests/test_classic_port.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-VERSION" \
  python3 Installer/tests/integration.py
```

`test_classic_port.py` запускает настоящий старый host-скрипт **только в тестах**
на изолированном fake ADB, затем Rust-порт на такой же фикстуре. Сравниваются
результат завершения, файлы, настройки и пакеты; отдельные сценарии подставляют
ошибки в checked и best-effort команды. Это проверка соответствия исходному процессу,
а не тест, повторяющий только новую реализацию.

При изменении классического сценария обновите соответствующий Rust-метод и тест
соответствия. Для изменившихся удалённых команд обновите привязку в
`sync-classic-commands.py` и запустите его без `--check`, затем форматирование Rust.
Новый install/remove-шаг должен появиться и в `classic_steps()`.
Новые файлы schema 4 исполняются по recipe без изменения движка. Если выпускается
также классический ZIP, его фиксированные списки обновляются отдельно.
