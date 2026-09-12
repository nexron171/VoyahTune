# VoyahTune Installer — состояние реализации

Актуально: 2026-09-08. Engine 0.3.0. Все текущие изменения сохранены в рабочем
дереве; коммит и push не выполнялись.

## Дополнение 2026-09-12: конфликт WRITE_CANBUS

- В GUI/CLI добавлено уведомление, если `com.voyah.hl.service` владеет WRITE_CANBUS
  **или** установлен для пользователя 0 (`pm list packages --user 0`), с отдельным
  подтверждением удаления. Бэкап VoyahHlCTRL → подготовка `/system` → force-stop /
  uninstall user 0 → удаление системной папки / package_cache → reboot → проверка
  освобождения разрешения и отсутствия пакета → продолжение Full/Light. Отказ отображается как отмена.
- CLI: `--remove-voyah-hl-service`, интерактивное решение через stdin;
  без решения код 3 и `operation-paused`. Формат описан в `Installer/README.md`.
- Проверено на fake ADB; есть отдельный набор `Installer/tests/test_canbus.py`,
  не требующий готового payload. Применение к реальному автомобилю не проверялось.
- Релиз 3.10.0 пересобран с проверкой через ИЛИ для macOS Universal и Windows x64:
  `Releases/dist/VoyahTune-3.10.0-installers/`. Linux исключён по просьбе пользователя.
  Готовые macOS/Windows сохранены из общей сборки, упаковка Linux остановлена;
  итоговые ZIP и метаданные сформированы штатными функциями release.py.
  Проверки встроенного payload/ADB, NSIS, CRC ZIP и SHA-256 прошли. GUI/CLI macOS
  имеют обе архитектуры. Все 16 сценариев test_canbus.py прошли на готовом macOS CLI.
  Логи: `Releases/cache/installer-all-Fh0G3h/` и
  `Releases/cache/setup/canbus-release-3.10.0-or.log`.
  Пакеты без Developer ID/notarization и Authenticode; на реальном автомобиле
  и чистых целевых ОС этот выпуск не проверялся.
- Ниже сохранены результаты предыдущей проверки от 2026-09-08.

## Главное решение

GUI/CLI исполняет **Rust-порт старого install/remove**, сохраняя порядок действий
и условия остановки. GUI не запускает `.sh/.bat` и не добавляет проверок автомобиля.
Точная карта процесса: [installer-classic-port.md](installer-classic-port.md).

Код процесса: `Installer/crates/installer-core/src/engine.rs`, метод `execute()`.
Шаги GUI: `plans.rs::classic_steps`. Удалённые команды: `classic_commands.rs`.

## Сделано

- Разделены Full, Light и единое удаление по классическим сценариям.
- Удалены блокировки по инвентаризации/token, API/ABI, metadata/SemVer, remote SHA,
  receipt/ownership и старому device lock. Host file mutex операций также убран.
- Восстановлены прежние порядок подготовки /system и бэкапа, stop/teardown,
  транзакция boot-hook, rollback legacy init.logcat, правила проверки Native.
- Full проверяет backup, Light допускает ошибку backup pull. Best-effort команды
  журналируются без нового запрета продолжения. Remove заканчивается reboot.
- Сохранены согласованные ручное подтверждение автомобиля и автоматический сброс
  приложения при несовпадении ключа. Remove не проверяет подписи.
- Старый lock/owner очищается после root, без создания нового. Старые служебные
  записи движка не используются как условия установки/удаления.
- GUI получает события через Tauri capability; текущий шаг и завершённые шаги
  обновляются независимо от ограничения списка событий. Есть журнал и отчёт.
- GUI `info` читает комплект; `verify` оставлен явной проверкой и используется сборкой.
- `make_release.sh` по умолчанию собирает классические Full/Light ZIP.
  `--installers` — три автономных GUI/CLI пакета; `--mac`, `--windows`, `--linux`
  включают этот режим и выбирают платформы. Флаги можно сочетать.
- macOS Universal (arm64+x86_64), Windows/Linux x64. `--mac` не запускает Colima.
  Повторная сборка версии заменяет локальные результаты после успеха.
- Весь payload и ADB внутри установщиков. Бинарники только в игнорируемом Releases;
  ручного release manifest для разработчика нет. Иконка общая с RestoreMode.

## Проверки текущего порта

- Сравнительные тесты `Installer/tests/test_classic_port.py` запускают оригинальные
  shell-сценарии и Rust-порт на отдельных fake ADB. Проверяются Full, Light, remove,
  последовательный переход Full → Light → remove, ошибки backup/настройки/публикации,
  отсутствие нового hash-gate, read-only remove, старые записи движка и шаги GUI.
- Дополнительные сценарии проверяют сброс/сохранение ключей, повтор установки
  RestoreMode при signature error и отсутствие сброса при нехватке места.
- `sync-classic-commands.py --check` проверяет соответствие тел adb shell исходникам.
- Старые тесты, требовавшие отвергнутых ownership/hash/token-блокировок, заменены
  проверками соответствия классическому процессу.
- Логи проверок: `Releases/cache/classic-port/`.

## Результат текущей проверки

- 19 тестов порта, включая сравнительные сценарии оригинал/Rust, legacy rollback,
  переход Full → Light → remove, обработку подписей и совпадение шагов GUI: успешно.
- 8 интеграционных тестов подключения, подтверждения, отмены и диагностики: успешно.
- 9 unit tests ядра и 1 сборщика, clippy, Svelte check: успешно.
- macOS Universal с payload 3.10.0 собран. GUI и CLI содержат arm64+x86_64;
  явный verify комплекта, чтение info и CRC ZIP проверены.
- Текущий ZIP: `Releases/dist/VoyahTune-3.10.0-macos-classic-port.zip`.
  Предыдущие `macos-fixed.zip` относятся к старому движку; для этого порта используйте
  новый файл. Исходный tar/build-info: `Releases/build/installer-classic-port-macos/`.
- Windows/Linux с текущим портом не пересобирались; физическое ГУ не использовалось.

## Сборка и продолжение

```sh
./make_release.sh VERSION
./make_release.sh VERSION --mac
./make_release.sh VERSION --installers
```

Для пересборки только desktop с уже готовым payload:

```sh
./Installer/scripts/build-all-macos.sh --mac \
  --payload Releases/build/installer-payload-3.10.0 \
  --output Releases/build/installer-classic-port-macos
```

Окружение и кэши описаны в [BUILDING.md](../Installer/BUILDING.md), выпуск —
в [releasing.md](releasing.md). Кэши Cargo/Rustup: Releases/cache/{cargo,rustup};
Rust 1.98.1. Windows/Linux из macOS используют Colima profile v и подготовленные
контейнеры; исходники и окружение Windows/Linux в Installer/build-env.

## Что остаётся проверить на целевых системах

- Реальные автомобиль, USB, Full/Light/update/remove и смена подписи на прошивке.
  В ходе этого исправления команды на автомобиль не отправлялись.
- Запуск новой сборки на чистой Windows/Linux и обоих Mac CPU; подпись/нотаризация
  нативных пакетов отдельно пока не настроены.
- Поведение при физическом отключении USB/питания проверять по классическому
  процессу; не добавлять собственные условия продолжения под видом диагностики.
