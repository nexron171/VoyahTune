# VoyahTune Installer

Нативный GUI на Tauri/Svelte и общий Rust CLI для установки Full/Light и удаления VoyahTune. Реализация находится в разработке, на реальном автомобиле не проверялась.

**[Состояние реализации, результаты проверок и оставшиеся задачи](../Docs/installer-implementation-status.md)** — актуальная точка продолжения (2026-09-08).

- `crates/installer-core/` — ADB, инвентаризация, планы, операции и отчёты.
- `crates/installer-cli/` — самостоятельный CLI и JSONL-протокол.
- `crates/installer-build/` — сборка общего payload Full + Light из APK и `Packaging/`.
- `desktop/` — Svelte GUI, Tauri bridge и настройки нативной упаковки.
- `scripts/build.mjs` — сборка ресурсов, комплектного ADB, CLI и приложения.
- `tests/` — имитатор ADB и сквозные проверки без автомобиля.
- [Согласованный HTML-прототип](prototype/README.md).
- [Целевая архитектура](../Docs/installer-architecture.md).

Старые install/remove в `Packaging/installer/` — эталон Rust-порта. GUI не запускает их как host-скрипты.
[Карта процесса и места изменения шагов](../Docs/installer-classic-port.md). Тестовые бинарники и payload в `Releases/` не являются готовым релизом для распространения.

## Сборка релиза

```sh
./make_release.sh 3.3.0                # Full/Light ZIP со скриптами
./make_release.sh 3.3.0 --installers   # три автономных GUI/CLI-установщика
./make_release.sh 3.3.0 --mac          # только macOS Universal; также --windows, --linux
```

Флаги платформ можно сочетать. Повторная сборка заменяет локальный выпуск той же
версии после успеха; при ошибке сохраняется предыдущий результат.

Готовые установщики, APK и payload хранятся только в игнорируемом `Releases/`.
Ручных release-files.json/recipe.json нет. Состав определяется Rust-сборщиком по
исходникам Android и файлам Packaging, служебные хеши и метаданные генерируются.
Внутри `.app`, NSIS и Linux `.run` находятся ADB и полный Full/Light payload.

- [Пошаговый выпуск релиза](../Docs/releasing.md).
- [Подготовка окружения и сборка установщиков](BUILDING.md).

## Запуск из скриптов

`voyahtune` автоматически находит ADB и payload внутри своего пакета. В Windows
имя — `voyahtune.exe`; в macOS — `VoyahTune Installer.app/Contents/MacOS/voyahtune`;
Linux: `./VoyahTune-Installer.run --cli ...`. Выбор внешнего `--payload` оставлен
для диагностики, при обычной установке не требуется.

```sh
./voyahtune verify
./voyahtune devices
./voyahtune plan --device SERIAL --action full --dns keep --output ./plan.json
# Просмотрите plan.json, проверьте автомобиль и действие, затем подтвердите:
./voyahtune apply-plan ./plan.json --yes
```

Для Light используется `--action light`; для удаления обоих наборов — `--action remove`. `--dns on|off|keep` выбирается при составлении плана; удаление всегда восстанавливает собственные изменения DNS. `apply-plan` не требует Python, jq или другого JSON-парсера. Сведения о состоянии информационные: изменение inventoryToken не блокирует выполнение выбранного действия.

Команда `info` читает сведения о комплекте без дополнительных проверок, `verify`
явно проверяет целостность комплекта без автомобиля.

Прямой вызов также поддерживается:

```sh
./voyahtune apply --device SERIAL --action full --dns keep --token TOKEN --yes
```

`TOKEN` — поле `request.inventoryToken` из `plan`. Сам по себе выбор action не запускает запись. Команды `devices` и `plan` делают одну проверку; повторную проверку запускает пользователь. Несколько устройств, включая offline/unauthorized, блокируют продолжение.

После подтверждения автомобиля команда `plan` запрашивает `adb root` до чтения
защищённых файлов, как классический скрипт. API/ABI, штатные пакеты и версия
установленного комплекта дают сведения для плана, без дополнительных запретов.
Для чтения известного пути не требуется возможность просматривать весь `/data`;
ссылка на обычный файл допустима. Ошибки фактических команд остаются в отчёте.

Если `WRITE_CANBUS` принадлежит `com.voyah.hl.service` (VoyahHlCTRL) **или** пакет
установлен для пользователя 0, GUI показывает
уведомление с действиями «Удалить приложение и продолжить» и «Отменить установку».
До отдельного согласия установщик не удаляет приложение и не готовит `/system` к записи.
После согласия он сохраняет `/system/priv-app/VoyahHlCTRL` в папку операции на компьютере,
подготавливает `/system`, выполняет `am force-stop` и `pm uninstall --user 0`, удаляет
системную папку и содержимое `/data/system/package_cache/`, затем перезагружает автомобиль.
Наличие пакета определяется точным совпадением имени в `pm list packages --user 0`.
Перед удалением и после перезагрузки проверяются оба условия; только после устранения
конфликта продолжается
Full/Light. Ошибка резервного копирования останавливает удаление. Пользовательские
данные и функции удаляемого приложения не сохраняются. Remove эту проверку пропускает.

CLI требует отдельного флага `--remove-voyah-hl-service` у `apply` или `apply-plan`:
обычный `--yes` не разрешает удаление этого приложения. Без флага и `--interactive`
выдаются уведомление `canbus-conflict`, событие `operation-paused` и код `3`.
В интерактивном режиме после `canbus-conflict` передайте через stdin
`{"confirmRemoveVoyahHlService":true}` для удаления либо `false` для отмены.
До уведомления ответы игнорируются. Отказ, отмена или закрытие stdin во время ожидания
завершают операцию с `operation-cancelled` и кодом `130`, без удаления приложения.
Другие владельцы разрешения обрабатываются по прежним правилам.

При смене ключа Native или RestoreMode GUI/CLI автоматически удаляет соответствующее
приложение с настройками и данными и устанавливает APK из комплекта. Дополнительного
подтверждения и флага нет: сброс указан в плане и журнале. Native проходит удаление
системного APK и промежуточную перезагрузку для очистки регистрации Android. Если
ключи совпадают, данные сохраняются. Ответ Android `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
о подписи RestoreMode также вызывает один цикл удаления/повторной установки; другие
ошибки установки не приводят к сбросу.

Действие `remove` не проверяет подписи установленных APK — удаление доступно со старым
ключом и даже с повреждённой подписью. Целостность комплекта проверяет сборка и явный `verify`.

Постоянная блокировка на автомобиле не создаётся. Старый `lock/owner` очищается
best effort при запуске установки/удаления. Host file lock операций удалён;
GUI предотвращает повторный запуск в своём окне. Не запускайте параллельные операции.

Коды завершения: `0` — успех, `1` — ошибка операции/комплекта, `2` — неверные аргументы CLI, `3` — требуется отдельное решение об удалении VoyahHlCTRL, `130` — отмена. `apply` и `apply-plan` выводят JSONL с именем шага, исходным выводом и конкретной ошибкой. `--interactive` принимает `{"cancel":true}` и решение об удалении VoyahHlCTRL через stdin; закрытие stdin или потеря stdout тоже запрашивает остановку на границе шага или во время ожидания решения.

`--logs /path/to/logs` у apply/apply-plan меняет каталог журналов. Без него используется пользовательский каталог данных приложения. Отчёт содержит точный путь; рядом лежит events.jsonl; резервные файлы сохраняются в общем backup/ рядом с операциями автомобиля. GUI экспортирует полный журнал и JSON-отчёты, сохраняя резервные APK в исходной папке операции.

## Проверка без автомобиля

```sh
cargo +1.98.1 test --manifest-path Installer/Cargo.toml
cargo +1.98.1 build --release --manifest-path Installer/Cargo.toml -p installer-cli
VOYAH_TEST_CLI="$PWD/Installer/target/release/installer-cli" \
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-dev-v2" \
python3 Installer/tests/integration.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-dev-v2" \
python3 Installer/tests/test_classic_port.py
python3 Installer/scripts/sync-classic-commands.py --check
# Независимые тесты уведомления/удаления VoyahHlCTRL, без готового payload:
VOYAH_TEST_CLI="$PWD/Installer/target/release/installer-cli" python3 Installer/tests/test_canbus.py
```

Для GUI: `npm --prefix Installer/desktop run dev`, затем `http://127.0.0.1:1420/?fixture=ready`. Дополнительные сценарии: none, multiple, unauthorized, root-error, events-error, canbus-conflict. Fixtures исключаются из production.

Текущие macOS Universal и Windows/Linux x86-64 сборки — тестовые пакеты. Перед распространением остаются восстановление после аварий, проверки реальной прошивки, проверки на чистых целевых ОС и подпись пакетов; точный список находится в файле состояния реализации.
