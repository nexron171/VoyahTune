# Packaging — источник комплекта релиза

Здесь лежит всё, что попадает в релиз **кроме APK (Android application package, пакетов Android),
собираемых из `Native/` и `RestoreMode/`**. Зафиксированный vendor RRO APK хранится здесь же как
проверяемый prebuilt. Раньше файлы копировались из предыдущей папки релиза вручную, поэтому одни и
те же бинарники лежали в 15 копиях, а инжект-скрипты приходилось править прямо внутри уже выпущенного
релиза (файл был одновременно исходником и артефактом).

**Правим здесь.** `Releases/` — сборочный вывод, он целиком в `.gitignore` и в репозитории не хранится.

```bash
./make_release.sh 3.2.2
```

даёт:

```
Releases/build/VoyahTune-3.2.2/         ← готовая папка (плоская, как её видит пользователь)
Releases/build/VoyahTune-3.2.2-light/
Releases/dist/VoyahTune-3.2.2.zip       ← архив для раздачи
Releases/dist/VoyahTune-3.2.2-light.zip
```

Флаги: `--full-only`, `--light-only`, `--no-build` (только переразложить файлы), `--no-zip`.

## Что где

ADB/`adb` (Android Debug Bridge) — служебный интерфейс управления Android-устройством с компьютера.
`full` — полный комплект с Frida-перехватами; `light` — сокращённый комплект без них. Прямой
Binder-контур Apollo входит в оба варианта и Frida не использует.

| Папка | Что | Куда идёт |
|---|---|---|
| `tools/` | `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, `frida-inject-16.2.1-android-arm64` | full — целиком; light — только adb-трио (frida там не нужна) |
| `inject/` | Frida-скрипты: `vd_bypass.js`, `launcherdock.js`, `steeringwheelkeys.js`, `multidisplay.js`, `apollo_tech.js` | только full |
| `system/` | `load.bin`, атомарный composite `voyahtune.load.rc`, `voyahtune.load.sh`, переходный чистый `init.logcat.original.sh`, `privapp-permissions-…xml` | full целиком; в light — только `privapp-permissions` |
| `vendor-overlay/` | Зафиксированный DNS RRO APK и его provenance | APK — в full и light; README в релиз не копируется |
| `installer/common/` | Четыре общих helper-файла для установки/отката DNS RRO, включая отдельный Windows `install-yandex-dns.bat` | full и light, плоско рядом с основными установщиками |
| `README.txt` | Пользовательская инструкция по установке и устранению ошибок на Windows/macOS | в корень каждого full/light-релиза и ZIP-архива |
| `installer/full/` | `install.sh`, `install.bat`, `remove.sh`, `remove.bat` | full |
| `installer/light/` | `install.sh`, `install.bat`, `remove.sh`, `remove.bat` | light |

Light — набор без Frida-инъекции, `load.bin` и загрузочного перехвата, поэтому у него **свои**, более
короткие установщики. Он не является вариантом без прав суперпользователя: установка APK и списка
разрешений всё равно требует команды `adb root` и записи
в `/system`. `.bat` требуют `adb.exe` рядом с собой, поэтому adb кладётся и в light (список файлов —
`LIGHT_TOOLS` в `make_release.sh`); на Unix `.sh` рассчитывают на системный adb.

Full-установщик больше не заменяет штатный `/system/etc/init.logcat.sh`: загрузочная обвязка живёт в
одном composite `voyahtune.load.rc`. `init.logcat.original.sh` остаётся в full-комплекте как безопасный
переход с непосредственного предыдущего релиза, установившего файл с явным marker `init.logcat.sh Open
Voyah`. Неизвестный OEM-файл миграция не перезаписывает; при наличии `backup/init.logcat.sh`
приоритет имеет сохранённый с конкретной головы оригинал.

## Зафиксированный DNS RRO

`vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk` — статический RRO для
`/vendor/overlay`, заменяющий DNS в конфигурации Ethernet-интерфейсов VoyahOS. Его происхождение,
manifest и подпись описаны в `vendor-overlay/README.md`.

Перед любым запуском Gradle `make_release.sh` проверяет точный SHA-256 APK:

```
c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d
```

Проверка использует доступный `sha256sum` либо `shasum`. Отсутствующий APK, несовпавший checksum,
отсутствующая `installer/common/`, `README.txt` или любой из четырёх обязательных helper-файлов
останавливают сборку. APK, инструкция и все четыре helper-файла копируются в плоский корень обоих
вариантов релиза.

Full/light Windows `install.bat` полностью неинтерактивны и DNS не меняют. Для явной установки
Yandex DNS пользователь после успешной основной установки отдельно запускает
`install-yandex-dns.bat`; он проверяет состояние overlay, не перезаписывает чужой/неоднозначный
overlay, устанавливает управляемый RRO и перезагружает устройство. PowerShell Windows-процессу не
требуется. Все `.bat` используют только ASCII/английские сообщения, чтобы не зависеть от code page
стандартного `cmd.exe`. Unix-`install.sh` и его меню DNS остаются без изменений.

Device-helper хранит ownership/rollback-состояние в `/data/local/open_voyah/qgdns`, проверяет
checksum, Android API 30, наличие ожидаемых адресов `172.16.{104,110,120}.40/24` на `eth0`,
возможность записи в `/vendor/overlay` и выполняет замену через временный файл + атомарный `mv`.
Чужой overlay без ownership-marker не удаляется. При remove исходный файл
восстанавливается, а если его до Open Voyah не было — удаляется только известный установленный hash.
Прошивки с `/vendor/overlay/config/config.xml` намеренно отклоняются: автоматически редактировать
OEM-порядок RRO небезопасно.

## Apollo/ADAS: прямой режим в full и light

Прямой H97X Binder-контур Native доступен в full и light, от VehicleSetting/Frida profile не
зависит и проверяет установленную схему CanBus до подключения. `apollo_tech.js` остаётся только в
full-архиве как legacy/диагностический инструмент, но обычный direct-only режим не инжектит его в
`com.qinggan.app.vehiclesetting`.

Native Apollo работает без постоянной подписки `ICanBusServiceCallback`: он не вызывает OEM
`TX28/TX29` и поэтому не добавляет получателя в глобальную рассылку всех CAN-событий. Состояние
читается только при подключении/явном запросе UI и непосредственно перед командой. После успешного
`TX58` команда считается принятой без отложенного проверочного `TX57`; UI получает оптимистическое
состояние, а следующий явный запрос при необходимости обновит его из автомобиля. Автоматических
повторов и фонового восстановления Apollo-entitlement после сна также нет.

`load.bin` читает `Settings.Global open_voyah_apollo_legacy_hook_enabled` ровно один раз при старте
процесса loader. Отсутствующее, нечитаемое, `0` или любое другое значение fail-closed: один bounded
startup cleanup независимо записывает нули в master/profile/старый heartbeat, однократно читает эти
три ключа для проверки и затем полностью выходит из Apollo-контура. Если PID-marker доказывает
наличие нашего агента в точно той же process identity, cleanup непосредственно перед остановкой ещё
раз проверяет identity и только тогда вызывает `am force-stop`; сменившийся или чужой процесс не
останавливается. PID-marker v2 включает boot UUID, поэтому старый marker без UUID также никогда не
является основанием для `force-stop` после reboot.

Единственное разрешающее значение — точное
`Settings.Global open_voyah_apollo_legacy_hook_enabled=1`; оно предназначено только для явной
legacy-диагностики.

После startup-фазы постоянный 10-секундный watchdog обслуживает только VD, launcher, keymanager и
multidisplay: в нём нет Apollo Settings/PID/marker/attach/log операций. Ошибка cleanup не создаёт
retry — loader пишет одно итоговое сообщение и ждёт явного перезапуска сервиса. При startup opt-in=1
loader сначала ищет уже работающий `com.qinggan.app.vehiclesetting`. Exact already-attached marker —
полный no-op без Settings writes и повторной инъекции. Во всех остальных исходах старый profile и
heartbeat один раз сбрасываются и проверяются; только свежая подтверждённая process identity получает
одну попытку attach. Отсутствие цели, ошибка проверки/attach или последующий restart VehicleSetting
не вызывают автоматических PID-проверок и повторов. Сам JS отдельно повторяет проверку opt-in до
SHA-256 и разрешения OEM-классов.

Потеря PID-marker не оставляет уже загруженный legacy-agent активным. Observer самого opt-in и
системный `ACTION_SCREEN_ON` читают точное значение ключа, а provider проверяет его при входе и
непосредственно перед fake. Любое значение, кроме `1`, включая ошибку чтения, переводит агент в
монотонное fail-passive состояние до перезапуска процесса: fake больше не возвращается, master/profile
обнуляются, receiver и observer снимаются, их `HandlerThread` завершается, provider-hook
восстанавливается отложенно после текущего вызова. Если fake мог уже попасть в OEM-cache, текущий
provider-вызов либо ровно один отдельный stock-query без retry обновляет cache перед окончательным
отключением. JS сам не вызывает `force-stop` и после self-disarm не включает hook повторно; новый
opt-in требует обычной повторной инъекции в новом экземпляре процесса.

При явном opt-in остаются legacy-gate: `open_voyah_apollo_master=1`, точные pinned SHA-256
VehicleSetting/CanBusService и поддерживаемый профиль. Generic `onVehicleStateChanged` не хукается ни
на direct H97X, ни на legacy 97C, поэтому поток CAN-событий вообще не пересекает GumJS. Переходы
master и opt-in обрабатывает `ContentObserver`, а wake-восстановление — динамический системный
`ACTION_SCREEN_ON`; оба доставляются одним dedicated background `HandlerThread`, не main-потоком
VehicleSetting. SCREEN_ON перечитывает gate/master и допускает максимум один activation-query через
общий debounce. Interval/periodic poll отсутствуют. Bounded stock-resync после конкретного
OFF/gate-loss остаётся отдельной цепочкой максимум из трёх попыток, а не фоновым poll.

Старый `open_voyah_apollo_profile_heartbeat` больше не является liveness-сигналом: JS его не читает
и не публикует. Установщики и loader только обнуляют этот legacy cleanup key при миграции, чтобы не
оставлять значение от предыдущих версий.

### Установка и диагностика

1. Оба установщика до `disable-verity` и любых `/system` mutations записывают и читают обратно `0`
   для legacy opt-in, master, profile и legacy heartbeat cleanup key, а также отклоняют чужого владельца
   `com.qinggan.permission.WRITE_CANBUS`. Любая ошибка останавливает установку до записи в `/system`.
2. После перезагрузки `open_voyah_apollo_legacy_hook_enabled`, master, profile и legacy heartbeat
   cleanup key должны быть равны `0`. В full `/data/local/tmp/voyah_apollo.disabled` создан, а `voyah_apollo.pid`
   отсутствует; light вообще не устанавливает Apollo loader/Frida-файлы. `[apollo] hook ready` в
   direct-only режиме не ожидается: агент не загружался.
3. Legacy-перехват доступен только в full и включается только для отдельной стояночной диагностики:

   ```sh
   adb shell settings put global open_voyah_apollo_legacy_hook_enabled 1
   adb shell settings get global open_voyah_apollo_legacy_hook_enabled  # должно быть ровно 1
   adb shell am force-stop com.qinggan.app.vehiclesetting
   # Открыть VehicleSetting на головном устройстве и убедиться, что появился новый PID:
   adb shell pidof com.qinggan.app.vehiclesetting
   adb shell setprop ctl.restart voyahtune_load
   adb shell getprop init.svc.voyahtune_load
   ```

   `pidof` до `ctl.restart` обязан вернуть PID уже открытого нового процесса, а последнее значение
   состояния сервиса — `running`. Затем проверять `logcat -s VoyahApollo VOYAH`,
   `/data/local/tmp/voyah_apollo.txt` и `[apollo] hook ready`. Если target ещё не работал, attach
   завершился ошибкой или VehicleSetting был перезапущен позже, автоматического reattach нет:
   повторить `force-stop` → открыть VehicleSetting → проверить новый PID →
   `setprop ctl.restart voyahtune_load`. Если `init.svc.voyahtune_load` отсутствует, нужен reboot с
   корректно установленным full init-конфигом. Этот opt-in не нужен для direct TLC/GLA/TSR.
4. Выключение legacy-диагностики:

   ```sh
   adb shell settings put global open_voyah_apollo_legacy_hook_enabled 0
   adb shell settings get global open_voyah_apollo_legacy_hook_enabled  # должно быть ровно 0
   adb shell am force-stop com.qinggan.app.vehiclesetting
   adb shell setprop ctl.restart voyahtune_load
   ```

   Сам JS событийно замечает opt-out и монотонно переходит в pass-through; явный `force-stop`
   немедленно убирает eternalized агент даже при потерянном marker, а restart loader выполняет
   единственный проверенный cleanup. Постоянного опроса после этого нет. Для отката запустить full
   `remove.sh`/`remove.bat` из той же папки рядом с её `backup/`.

Все первичные проверки выполняются на неподвижном автомобиле в `P` со стояночным тормозом.

## Версия в установщиках

В шапке каждого установщика стоит плейсхолдер `@VERSION@` — `make_release.sh` подставляет туда номер
релиза при копировании. Руками номер версии в этих файлах **не проставлять**, иначе он «застынет».

## Папка релиза — плоская

Установщики ищут APK и helper-файлы рядом с собой, поэтому `make_release.sh` раскладывает содержимое
нужных подпапок в одну плоскую папку сборки. Подпапки существуют только здесь, для навигации.

## Где брать старые релизы

Начиная с 3.2.2 собранные релизы в git не хранятся. Всё, что выпускалось раньше, лежит в истории
репозитория по тегам (`git tag`) — например `git show v3.2:Releases/v3.2/install.sh`.
Если история будет переписана (вырезание `Releases/`), старые сборки останутся только в архивной
копии репозитория — см. заметку о рерайте в описании релизного процесса.
