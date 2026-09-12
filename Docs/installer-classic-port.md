# Процесс установки GUI/CLI: порт классических скриптов

Актуально на 2026-09-08. GUI **не запускает install.sh/install.bat/remove.sh**.
Порядок, ветвления, повторы и обработка результатов перенесены в Rust.
Дополнительные проверки автомобиля и причины остановки вводить нельзя.

## Где менять процесс

- `Installer/crates/installer-core/src/engine.rs`: `execute()` задаёт порядок;
  методы ниже реализуют действия и обработку ошибок. Это исполняемая логика процесса.
- `Installer/crates/installer-core/src/plans.rs`: `classic_steps()` задаёт названия
  и порядок отображаемых шагов Full, Light и удаления.
- `Installer/crates/installer-core/src/classic_commands.rs`: тела **удалённых**
  команд `adb shell`, перенесённые из скриптов. Здесь нет запуска host-сценариев.
  Над каждым фрагментом указан исходный файл и строка.
- `Installer/desktop/src/App.svelte`: отображение событий, текущего шага,
  завершённых шагов, ошибок и журнала. Здесь нет логики установки на автомобиль.

Эталоны: `Packaging/installer/full/install.sh`, `light/install.sh`, `full/remove.sh`
и соответствующие `.bat`. Единое удаление использует полный remover независимо
от установленного набора, как было согласовано для GUI.

## Порядок действий

| Шаг | Full | Light | Удаление обоих наборов |
|---|---|---|---|
| Подготовка | Обязательные файлы, DNS helper/RRO | APK, whitelist, DNS helper/RRO | DNS helper, fallback init.logcat |
| Root | root → wait-for-device → root | То же | То же |
| CAN permission | Проверка WRITE_CANBUS из старого install | То же | Нет |
| /system | disable-verity; remount; при необходимости один reboot | То же | remount и проверка записи, без нового reboot-цикла |
| Бэкап | Восемь прежних файлов; ошибка останавливает | Native/whitelist; ошибка pull не останавливает | Нет общего обязательного бэкапа |
| Подпись | Согласованная переустановка при смене ключа | То же | Подписи не проверяются |
| Runtime | best-effort stop, отключение старого Apollo | Проверка legacy Full; stop; teardown Full | Сначала отключение Apollo, восстановление DNS, миграция init.logcat |
| Файлы | Атомарная установка Frida/JS/JSON | Очистка Full входит в teardown | stop сервиса; удаление boot/runtime; возврат host-бэкапов load.bin/frida при наличии |
| Boot | Миграция legacy init.logcat, транзакция boot-hook с rollback | Свой boot-hook не устанавливается | Удаление штатным полным remover-процессом |
| Приложения | Native/whitelist, настройки, RestoreMode | Native/whitelist, power hold, RestoreMode | Очистка прежнего списка Settings; PackageManager uninstall; удаление priv-app/whitelist |
| DNS | Выбор GUI передаётся прежнему helper | То же | Выполнен до удаления компонентов |
| Завершение | reboot, ожидание Android, восстановление CE/DE и проверка запуска Native | То же | Заканчивается reboot; новой postflight-инвентаризации нет |

Если классический скрипт игнорирует результат команды, Rust пишет его в журнал
и продолжает. Если скрипт проверяет результат, Rust останавливает соответствующий
шаг с конкретной командой и выводом. Это относится, например, к best-effort stop,
необязательным freeform-настройкам, `disable-verity`, бэкапу Light и cleanup stage.
Проверки boot-hook/rollback и Native lifecycle сохранены: они уже есть в скриптах.

Windows-вариант старого permission-check допускает неизвестного владельца;
эта особенность сохранена. Проверка синтаксиса legacy init.logcat через локальный
`sh -n` выполняется на macOS/Linux, как в `.sh`; Windows использует marker-проверки
из `.bat`. POSIX-shell для Windows устанавливать не нужно.

## Явно согласованные отличия и интерфейс

- GUI/CLI при владельце WRITE_CANBUS `com.voyah.hl.service` **или** наличии этого
  пакета у пользователя 0 показывает уведомление
  и предлагает удалить VoyahHlCTRL. Только после отдельного согласия сохраняется
  системная папка на компьютере, подготавливается `/system`, выполняются force-stop,
  uninstall для user 0, удаление `/system/priv-app/VoyahHlCTRL` и очистка package_cache.
  Затем обязательны reboot, ожидание Android/root и повторная проверка владельца разрешения и наличия пакета.
  Ошибка бэкапа или сохранение конфликта после reboot останавливают процесс.
  Эта ветка GUI/CLI находится в `engine.rs::resolve_canbus_conflict`, согласие —
  в `canbus.rs`. Классические `.sh/.bat` сохраняют прежний отказ при этом владельце.
- Пользователь вручную подтверждает единственный подключённый автомобиль.
- При несовпадении подписи Native/RestoreMode соответствующее приложение удаляется
  с данными и устанавливается заново. Native требует промежуточного reboot, чтобы
  Android убрал старую регистрацию; RestoreMode допускает повтор после конкретного
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` о подписи. Иные ошибки APK не запускают сброс.
- Выбор Full/Light/единого удаления и DNS выполняется в GUI.
- Отмена — явное действие пользователя между шагами. Восстановление loader следует
  прежнему exit-recovery: Full до принятого reboot, Light только до начала teardown.
- Журнал и резервные копии находятся на компьютере. `backup/` рядом с каталогами
  операций одного автомобиля сохраняет предыдущие файлы, как каталог старого релиза.

Диагностическая инвентаризация показывает известные сведения, но не блокирует
установку из-за metadata, версии, хешей старого набора или изменения token.
`inventoryToken` оставлен в CLI JSON для совместимости, условием запуска он не является.

Постоянного mutex на автомобиле и файлового mutex операций на компьютере нет.
Старый `/data/local/voyahtune-installer/lock/owner` очищается best effort после root;
новый маркер не создаётся. Старые receipt/ownership/hash-записи движка не используются
как условия продолжения. GUI предотвращает повторное нажатие во время своей операции.

SHA/подписи всего payload проверяются сборкой и явной командой `verify`.
Обычный GUI читает сведения командой `info`; фактическая установка использует прежние
проверки обязательных файлов и прежнюю проверку DNS RRO. Нет обязательного хеширования
переданных файлов, общего ownership-каталога или проверки всех APK после reboot.

## Как проверять изменения

```sh
python3 Installer/scripts/sync-classic-commands.py --check
cargo test --manifest-path Installer/Cargo.toml -p installer-core -p installer-build
python3 Installer/tests/test_canbus.py
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
Новые файлы, которые требуется устанавливать, добавляйте одновременно в старый
сценарий и порт; автоматическое попадание в payload само по себе не меняет процесс.
