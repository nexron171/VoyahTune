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

`full` содержит Frida-перехваты для руля, VirtualDisplay, launcher, multidisplay, ADAS entitlement и
опциональной штатной клавиатуры. `light` не
содержит Frida и `load.bin`. Read-only диагностика Apollo входит в оба варианта.

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
сторонние APK, которые OEM launcher скрывает. PackageManager сканируется один раз за процесс launcher,
без polling. Клик передаёт OEM AppLauncher идентификатор выбранного экрана, после чего приложение
попадает в тот же physical-window путь. Плавающая кнопка Home
подавляется штатными launcher-предикатами, а событие `TOP_ACTIVITY_CHANGED` повторно показывает док
при возврате из fullscreen OEM-экрана (например угловой камеры) к оконному приложению.

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

## Зафиксированный DNS RRO

`vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk` — статический RRO для
`/vendor/overlay`. Его SHA-256:

```text
c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d
```

Device-helper проверяет checksum, Android API 30, ожидаемую конфигурацию `eth0` и ownership-marker.
Неизвестный чужой overlay не перезаписывается и не удаляется. Windows-установщики DNS не меняют;
для этого отдельно запускается `install-yandex-dns.bat`.

## Apollo/ADAS: entitlement как в voboost + read-only диагностика

Full-релиз содержит минимальный `apollo_tech.js`, повторяющий
`tmp/voboost-script/agents/adas-activation-mod.js`: в процессе
`com.qinggan.app.vehiclesetting` он подменяет только `BaiduProviderUtil.doQuerySubscribeInfo()`
(активная, не истёкшая подписка на 30 дней) и `doQueryNOALearnInfo()` (`"1"`, обучение NOA
завершено). Hook не отправляет CAN-команды, не подписывается на CAN callback и не вызывает прежний
`DriveAssistanceAdasStatusManager.asyncQueryAdasSubData()`.

`load.bin` не читает Apollo Settings. В уже существующем 10-секундном watchdog добавлена только
проверка process identity VehicleSetting через `pidof`: каждая identity получает не более одной
попытки Frida-injection, успешной или неуспешной. После штатного перезапуска VehicleSetting новая
identity получает новую попытку, поэтому entitlement восстанавливается без открытия Apollo Tech.
Full installer перезагружает ГУ; VehicleSetting штатно стартует при загрузке. Light-релиз не содержит
Frida и удаляет hook при переходе full → light.

Старые opt-in/master/profile/heartbeat ключи по-прежнему удаляются установщиками как одноразовая
миграция. Remove останавливает VehicleSetting, выгружает eternalized agent и удаляет новый exact
PID/attempt marker и лог, поэтому цикл remove → install начинает работу с чистого состояния.

Прямой H97X Binder-контур:

- работает в full и light как независимая read-only диагностика; entitlement доступен только в full;
- перед подключением проверяет установленную CanBus schema и `WRITE_CANBUS` permission;
- не подписывается на общий поток CAN callback (`TX28/TX29`);
- при открытом разделе читает текущие PLC/GLA/TSR через `TX57` и положение селектора через `TX6`;
- не содержит команд активации, `TX58`, `TX77`, entitlement-вектора или delayed confirmation;
- изолирован в приватном процессе `:apollo`.

Каждый синхронный vendor Binder call имеет одноразовый 15-секундный deadline. При зависании
завершается только процесс `:apollo`, основной Native/SetModes продолжает работать. Schema
PM/ClassLoader-проверка начинается при первом UI demand, имеет latest-only очередь глубиной один,
а её worker завершается после 30 секунд простоя. Demand принадлежит Binder-owner процесса
RestoreMode: смерть клиента освобождает transport без TTL, heartbeat или lease polling.

## Full loader и нагрузка

Постоянный 10-секундный watchdog full-варианта обслуживает VD, launcher, keymanager, multidisplay,
VehicleSetting и opt-in Qinggan IME. Режим клавиатуры читается только при появлении новой qgime
identity; в выключенном по умолчанию состоянии это одно чтение на жизнь процесса. Каждая точная identity получает не более одной
тяжёлой Frida-попытки; ошибка не создаёт повторяющийся injection loop, а новый процесс получает новую
попытку. Owner/busy lock loops имеют sleep и конечный budget, поэтому повреждённый lock path не
создаёт 100% CPU spin.

Это не меняет политику внутренних автомобильных watchdog: редкие собственные проверки, нужные для
возврата целевого состояния, сохраняются. Оптимизация направлена прежде всего на работу,
размножаемую входящим потоком CAN-событий.

Режимы вождения/энергии защищены от стартового OEM Eco/EV: после успешного wake-restore CAN-feedback
30 секунд не может заменить сохранённое значение и при несовпадении допускает не более одной
корректирующей restore-попытки. После этого окна стабильное изменение со стороны машины снова считается
источником истины и сохраняется; руль и VoyahTune сохраняют выбор сразу.

Сохранённые настройки Dock и кнопок руля не зависят от открытия экранов RestoreMode. Native
запрашивает их публикацию один раз при старте `SetModesService` и один раз внутри coalesced-сессии
физического пробуждения автомобиля. Запрос explicit и защищён signature-permission; периодического
опроса для этой синхронизации нет.

## Установка и диагностика Apollo

Full installer сам перезагружает ГУ; открывать Apollo Tech или штатные настройки для загрузки hook
не требуется. Успех виден по `[apollo] hook ready profile=voboost` в
`/data/local/tmp/voyahtune_apollo.txt` или logcat tag `VoyahApollo`. Раздел Apollo Tech показывает
штатные состояния функций, но CAN-команды не отправляет: после открытия entitlement сами функции
включаются и настраиваются через штатный экран автомобиля. В light `[apollo] hook ready` не
ожидается.

## Версия и структура релиза

В установщиках стоит плейсхолдер `@VERSION@`; номер подставляет `make_release.sh`. Папка готового
релиза плоская, потому что установщики ищут APK и helper-файлы рядом с собой.

Начиная с 3.2.2 собранные релизы в git не хранятся. Старые варианты доступны по тегам, например:

```bash
git show v3.2:Releases/v3.2/install.sh
```
