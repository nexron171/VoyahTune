# Packaging — источник комплекта релиза

Здесь лежит всё, что попадает в релиз, кроме APK, собираемых из `Native/` и `RestoreMode/`.
`Releases/` — сборочный вывод; править релизные файлы вручную там не нужно.

```bash
./make_release.sh 3.2.2
```

Результат:

```text
Releases/build/VoyahTune-3.2.2/
Releases/build/VoyahTune-3.2.2-light/
Releases/dist/VoyahTune-3.2.2.zip
Releases/dist/VoyahTune-3.2.2-light.zip
```

Флаги: `--full-only`, `--light-only`, `--no-build`, `--no-zip`.

## Состав

`full` содержит Frida-перехваты для руля, VirtualDisplay, launcher, multidisplay, статуса Apollo и
опциональной штатной клавиатуры. `light` не
содержит Frida и `load.bin`. Управление сохранёнными Apollo-функциями входит в оба варианта.

| Папка | Что | Куда идёт |
|---|---|---|
| `tools/` | ADB и `frida-inject-16.2.1-android-arm64` | full целиком; light — только ADB |
| `inject/` | основные hooks и opt-in keyboard agents/config | только full |
| `system/` | `load.bin`, init RC/wrapper, permission whitelist | full; whitelist также в light |
| `vendor-overlay/` | зафиксированный DNS RRO APK и provenance | full и light |
| `installer/common/` | общие DNS helper-файлы | full и light |
| `installer/full/`, `installer/light/` | установщики и удаление | соответствующий вариант |

Light всё равно требует `adb root` и запись в `/system` для APK и permission whitelist. Full не
заменяет штатный `/system/etc/init.logcat.sh`: загрузочная обвязка живёт в собственном
`voyahtune.load.rc`. `init.logcat.original.sh` нужен только для безопасной миграции старого релиза.

Одиночное стороннее приложение запускается обычной задачей целевого пакета на физическом дисплее,
поэтому системный top activity принадлежит этому пакету. Два кэшированных WindowManager hook в
`system_server` глобально ужимают окна всех сторонних приложений в настроенный прямоугольник и применяют
per-app DPI независимо от источника запуска. На `SCREEN_OFF` hooks снимаются, после пробуждения ставятся
обратно. Native перед одиночным запуском закрывает активный VD-host; VirtualDisplay остаётся только для
split двух приложений.

`launcherdock.js` также добавляет в штатные списки «Все приложения» обоих физических экранов launchable
сторонние APK, которые OEM launcher скрывает. Снимок PackageManager кэшируется между системными
`PACKAGE_ADDED`/`PACKAGE_REMOVED`/`PACKAGE_CHANGED`; динамический receiver инвалидирует его и через
штатный `AllAppDataManager.reload()` обновляет открытые списки обоих экранов. Периодического polling нет.
Для synthetic-записей используются валидные placeholder-ресурсы OEM, а после штатного bind подставляются
настоящие иконка и название приложения. Клик передаёт OEM AppLauncher `mScreenId` самого `AllAppBarView`
(с проверочным fallback по дисплею view), поэтому пассажирский All Apps запускает приложение на пассажирском
экране. Отдельно обработана пассажирская home-лента `SecondAllAppAdapter`, которая читает тот же источник.
После этого приложение попадает в тот же physical-window путь. Плавающая кнопка Home
подавляется штатными launcher-предикатами, а событие `TOP_ACTIVITY_CHANGED` повторно показывает док
при возврате из fullscreen OEM-экрана (например угловой камеры) к оконному приложению.

Слоты 1/2 настраиваются только для водительского дока. Пассажирские Air/Seat не переопределяются;
при каждой синхронизации конфигурации Native сбрасывает legacy-ключи прежних сборок в `none/0`.
При опущенной панели водительский OEM one-button dock заменяется компактной раскладкой из Home и
только тех пользовательских слотов, которым назначено приложение. Если
назначений нет, остаётся один Home; набор центрируется по высоте. Все штатные кнопки, включая All Apps
и климат, скрыты. Пассажирский compact dock остаётся штатным (только Home).
Пока интеграционная проверка автомобиля не показала иное, высота compact viewport зафиксирована
как 560 px (обычный режим — 720 px).

`action.qg.layout.start_change` сохраняет foreground-задачу каждого физического экрана, а
`action.qg.layout.changed` возвращает те же task id после OEM-анимации. Одновременно system_server
пересчитывает рамки уже открытых обычных приложений. `SplitHostActivity` читает текущее состояние до
создания VirtualDisplay и при каждой последующей смене высоты меняет корневой viewport; штатный
`SurfaceView.surfaceChanged` вызывает `VirtualDisplay.resize`, поэтому новый и уже активный split
получают актуальный размер.

Per-app DPI хранится в `DrivePreferences` и event-driven зеркалируется в
`Settings.Global/voyahtune_dpi_<package>` при изменении, старте Native и пробуждении. `WIN_RELOAD`
сбрасывает только кэш WindowManager hook; периодического чтения настроек нет. Переход в «Авто» явно
публикует `0`, поэтому ранее выбранный DPI не остаётся зависшим.

В разделе «Другое» full-варианта есть два выключенных по умолчанию взаимоисключающих режима штатной
Qinggan-клавиатуры. «Английская раскладка» запрещает IME сохранять китайский input mode; «Русская
клавиатура» переносит из voboost полноценную ЙЦУКЕН-раскладку и переключение EN ↔ RU. Выбор
зеркалируется в `Settings.Global/voyahtune_keyboard_mode`. При изменении Native перезапускает только
`com.qinggan.app.qgime`, чтобы выгрузить прежний eternalized hook. `load.bin` читает режим ровно один
раз на новую exact process identity и делает не более одной попытки injection; постоянного Settings
polling нет. Light и remove выгружают qgime, удаляют agents/config/markers и возвращают штатный IME.

## Режим ручной мойки

Карточка на главном экране выполняет только разовую активацию после обязательного диалога
подтверждения. Native через Android 11 `ICanBusService` читает передачу TX6, допускает операцию
только в P и отправляет один TX58 `CAR_CLEANING_MODE_SWITCH=1`. VoyahTune не читает состояние
багажника, не управляет им, не проверяет текущую активность режима и не формирует сырые CAN-кадры.

Операция fire-and-forget: после принятой команды автомобиль гасит экран, поэтому UI не ожидает и не
показывает подтверждение успеха, не блокирует карточку и не ведёт request ID. Внутренний
device-protected marker нужен только для event-driven сброса request-бита TX58=0 на sleep/shutdown;
если событие сна было пропущено, одна следующая попытка выполняется на реальном SCREEN_ON/CarPower
wake. Таймера, CAN-подписки, периодического опроса и восстановления режима мойки при пробуждении нет.

## Зафиксированный DNS RRO

`vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk` — статический RRO для
`/vendor/overlay`. Его SHA-256:

```text
c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d
```

Device-helper проверяет checksum, Android API 30, ожидаемую конфигурацию `eth0` и ownership-marker.
Неизвестный чужой overlay не перезаписывается и не удаляется. Windows-установщики DNS не меняют;
для этого отдельно запускается `install-yandex-dns.bat`.

## Apollo/ADAS: сохранённые подписка/экзамен и функции VoyahTune

Full-релиз содержит `apollo_tech.js`. Переключатель «Активация функций Apollo» в разделе Apollo Tech
хранится в `DrivePreferences`, как остальные настройки автомобиля, и по умолчанию выключен. Кнопка
«Применить» применяет его сразу, а автоматическая restore-цепочка — через 10 секунд после
старта/пробуждения.

Loader по-прежнему получает технический app-private файл с текущим Linux `boot_id`, однако этот файл
больше не является пользовательским источником истины. Native заново публикует его на каждом запуске
из сохранённого значения. Такая граница не позволяет старому файлу преждевременно активировать hook
до загрузки актуальных настроек.

При включённой настройке hook повторяет ответы подписки и экзамена voboost
(`BaiduProviderUtil.doQuerySubscribeInfo()` и `doQueryNOALearnInfo()`), capability SDB и status
getters. Видимость блока полностью оставлена штатной логике VehicleSettings: hook не вызывает
`setVisibility`, не меняет `isShowAdas` и не обновляет фрагмент принудительно.
Скрытые на 97X строки отдельных функций не раскрываются. Глобальный `AppCommonUtils.is97X()` и локальные селекторы
97X/97XY не подменяются. Hook не отправляет CAN-команды, не проверяет Parking, не подписывается на
CAN callback и не запускает TSP/polling менеджера.

В уже существующем 10-секундном watchdog loader сначала дешёво проверяет exact `boot_id` transport-
файла. В состоянии off он не вызывает даже `pidof` VehicleSetting. После изменения сохранённой
настройки Native перезапускает только `com.qinggan.app.vehiclesetting`, и новая process identity получает одну попытку
Frida-injection. При выключении процесс также перезапускается, чтобы eternalized hook не оставался в
памяти. Light-релиз не содержит Frida и держит переключатель недоступным.

Старые opt-in/master/profile/heartbeat ключи по-прежнему удаляются установщиками как одноразовая
миграция. Remove останавливает VehicleSetting, выгружает eternalized agent и удаляет новый exact
PID/attempt marker и лог, поэтому цикл remove → install начинает работу с чистого состояния.

Сами функции TLC, распознавания светофоров, звука зелёного сигнала и дорожных знаков принадлежат
VoyahTune. Переключатели сохраняют только целевые значения и не читают текущее состояние машины.
При ручном «Применить» и в автоматической цепочке пробуждения Native передаёт OEM CanBusService два
упорядоченных `TX77` bundle: сначала полный 18-битный capability/entitlement-снимок штатного формата,
затем пользовательские PLC/GLA/TSR switches. Полный снимок обязателен, потому что H97X собирает общий
кадр `0x40A` с нуля; частичный bundle выключил бы соседние ACC/ICA/NOA-возможности. Это позволяет как
включить, так и выключить функцию, не раскрывая штатные
97X-строки и не вводя отдельные CAN subscriptions, Parking gate или фоновый опрос. Автоматическое
восстановление начинается через 10 секунд после wake-события, чтобы Android 11 и OEM CanBus успели
полностью подняться; ручное применение остаётся немедленным.

Полный entitlement-снимок отправляется только когда включена хотя бы одна Apollo-функция и повторяет
штатный all-on вектор активной подписки. При всех выключенных функциях VoyahTune посылает только
выключенные PLC/GLA/TSR switches и не формирует all-off `0x40A`, который мог бы затронуть ACC/ICA.

## Full loader и нагрузка

Постоянный 5-секундный watchdog full-варианта обслуживает VD, launcher, keymanager, multidisplay,
VehicleSetting и opt-in Qinggan IME. Режим клавиатуры читается только при появлении новой qgime
identity; в выключенном по умолчанию состоянии это одно чтение на жизнь процесса. Обычно каждая точная
identity получает не более одной тяжёлой Frida-попытки. Узкое исключение — idempotent multidisplay-agent:
он стартует первым, подтверждает точный ready-marker и имеет persistent лимит из трёх попыток
(быстрый 2-секундный повтор для чистой ранней ошибки/потерянного marker, затем bounded 20/60 секунд).
Owner/busy lock loops имеют sleep и конечный budget, поэтому повреждённый lock path не создаёт 100% CPU
spin.

Внутри цикла порядок приоритетов — multidisplay whitelist, launcher dock, затем VD/system_server.
Поэтому появление иконок после wake/restart launcher не ждёт потенциально долгого VD attach, а новая
identity launcher обнаруживается максимум за 5 секунд.

Loader включается в `post-fs-data` после синхронного `setenforce`, но остаётся `class late_start`.
Перед общим watchdog он до 15 секунд ждёт `com.qinggan.systemservice` и устанавливает server whitelist
раньше, чем приложения успеют закэшировать OEM-ответ. Это устраняет основной источник «иногда работает
после перезапуска приложения». Activity-level `mEnable`, Home/SplitHost и top-task race намеренно не
переопределяются вслепую; подробный разбор находится в
`Docs/multidisplay-transfer-audit.md`.

Это не меняет политику внутренних автомобильных watchdog: редкие собственные проверки, нужные для
возврата целевого состояния, сохраняются. Оптимизация направлена прежде всего на работу,
размножаемую входящим потоком CAN-событий.

Набор Frida-агентов зафиксирован в `system/voyahtune-hook-manifest.json`: для каждого hook указаны
точные `id`, process, script и SHA-256. После изменения JS manifest обновляется командой
`Packaging/update_hook_manifest.sh`. Unix/Windows installer проверяет все семь отображений и хэшей
до первого изменения устройства, останавливает прежний loader, устанавливает scripts и только затем
атомарно публикует manifest. При старте `load.bin` повторно проверяет schema, полный exact mapping и
все hashes. Любая ошибка отключает весь набор fail-closed — смешанная версия hooks не запускается.

`load.bin` также ведёт единый bounded status-contract в
`/data/local/tmp/voyahtune-hook-status.v1`. Файл заменяется через temp + `mv` и только при реальном
изменении состояния. То же значение event-driven передаётся root-only методом существующего
RestoreMode ContentProvider и атомарно сохраняется в отдельные preferences; `Settings.Global` для
этого не используется. Экран «Другое» показывает loader, manifest и семь hooks, читая snapshot в
уже существующем цикле RAM/CPU раз в 5 секунд. Вне видимого раздела ни timer, ни чтение status не
работают. Ошибка доставки provider получает максимум три попытки для данной revision, а не вечный
retry-loop.

После воспроизведения отказа жеста или системной кнопки read-only bundle снимается командой:

```bash
./Packaging/tools/capture_multidisplay_diagnostics.sh 3cad9c17 ./md-failure
```

Скрипт не меняет настройки и процессы ГУ; он сохраняет hook markers/logs, полный logcat и нужные
`dumpsys` для различения whitelist, activity-level `mEnable` и неверного top task.

Для безопасной host-проверки exact process identity добавлен
`Utils/android11-oem-stubs`. Harness рассчитан только на Android 11/API 30 emulator, требует явный
ADB serial и отказывается заменять или удалять не принадлежащие ему OEM packages. Статический guard:

```bash
bash Utils/android11-oem-stubs/tests/static-checks.sh
```

Исследовательские функции из Voboost намеренно не добавлены в hook-set без проверки OEM ABI:
результаты аудита `phone-num`/`weather-widget` находятся в
`Docs/voboost-phone-weather-audit.md`, а поэтапный безопасный план телефонных номеров — в
`Docs/phone-number-improvement-plan.md`.

Режимы вождения/энергии защищены от стартового OEM Eco/EV: после успешного wake-restore CAN-feedback
30 секунд не может заменить сохранённое значение и при несовпадении допускает не более одной
корректирующей restore-попытки. После этого окна стабильное изменение со стороны машины снова считается
источником истины и сохраняется; руль и VoyahTune сохраняют выбор сразу.

Сохранённые настройки Dock и кнопок руля не зависят от открытия экранов RestoreMode. Native
запрашивает их публикацию один раз при старте `SetModesService` и один раз внутри coalesced-сессии
физического пробуждения автомобиля. Запрос explicit и защищён signature-permission; периодического
опроса для этой синхронизации нет.

## Power Hold (Leave Car)

Power Hold не является сохраняемой настройкой и не восстанавливается при пробуждении. После
подтверждения в VoyahTune карточка переходит в `ACTIVATING`, а Native выполняет штатную Android 11
цепочку: TX6 проверяет точный `P`, TX57 читает `BMS_SOC_DISPLAY` и допускает заряд не ниже 15%, затем
один TX77 отправляет только `POWER_HOLD_MODE_TIME=15`, `SCENE_MODE_EXTENDER_SET=1` и
`POWER_HOLD_MODE_SWITCH=1`. Сырые кадры `0x6c/0x77` не используются, поэтому VoyahTune не подменяет
соседние значения заряда, рекуперации или режима движения.

Принятый TX77 означает только «команда отправлена». Статус `ACTIVE` появляется исключительно после
feedback `POWER_HOLD_MODE_SWITCH=1` из общего process-wide `CanBusEventHub`. Feedback switch `0`
возвращает карточку в `INACTIVE`, а `POWER_HOLD_MODE_WARNING` уточняет выход по низкому заряду или
истечению времени. При потере CAN-сессии состояние становится `UNKNOWN`; после reconnect выполняется
один узкий TX57 seed. Подтверждение имеет единственный 10-секундный timeout, без periodic polling,
отдельной callback-регистрации, sleep-cleanup или app-owned lease.

## Установка и диагностика Apollo

Full installer сам перезагружает ГУ. При сохранённой включённой настройке успех виден
по `[apollo] hook ready profile=persisted-target` в `/data/local/tmp/voyahtune_apollo.txt` или logcat tag
`VoyahApollo`; hook подменяет данные подписки и экзамена, но не заставляет VehicleSettings показывать
соответствующий блок. Перезагрузка ГУ не сбрасывает пользовательский выбор: Native восстанавливает
его вместе с функциональными переключателями VoyahTune. В light `[apollo] hook ready` не ожидается,
но функциональные переключатели продолжают работать.

## Версия и структура релиза

В установщиках стоит плейсхолдер `@VERSION@`; номер подставляет `make_release.sh`. Папка готового
релиза плоская, потому что установщики ищут APK и helper-файлы рядом с собой.

Начиная с 3.2.2 собранные релизы в git не хранятся. Старые варианты доступны по тегам, например:

```bash
git show v3.2:Releases/v3.2/install.sh
```
