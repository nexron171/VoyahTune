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

`load.bin` разрешает attach только при точном
`Settings.Global open_voyah_apollo_legacy_hook_enabled=1`. Отсутствующее, нечитаемое, `0`
или любое другое значение fail-closed: master, profile и heartbeat сбрасываются, attach не
выполняется. Если PID-marker доказывает наличие нашего агента в точно той же process
identity, переход `1` → `0` однократно выгружает его через `am force-stop`. Сам JS повторяет
проверку opt-in до SHA-256 и разрешения OEM-классов. PID-marker v2 включает boot UUID;
старый marker без UUID никогда не является основанием для `force-stop` после reboot.

При явном opt-in остаются legacy-gate: `open_voyah_apollo_master=1`, точные pinned SHA-256
VehicleSetting/CanBusService и поддерживаемый профиль. Generic `onVehicleStateChanged` не хукается ни
на direct H97X, ни на legacy 97C, поэтому поток CAN-событий вообще не пересекает GumJS. Переходы
master по-прежнему обрабатывает `ContentObserver`, heartbeat каждые 30 секунд перепроверяет gate и
редкий пропуск observer, а при активном legacy master выполняется activation resync не чаще одного
раза в пять минут. Bounded stock-resync после OFF/gate-loss остаётся отдельным fail-closed путём.

### Установка и диагностика

1. Оба установщика до `disable-verity` и любых `/system` mutations записывают и читают обратно `0`
   для legacy opt-in, master, profile и heartbeat, а также отклоняют чужого владельца
   `com.qinggan.permission.WRITE_CANBUS`. Любая ошибка останавливает установку до записи в `/system`.
2. После перезагрузки `open_voyah_apollo_legacy_hook_enabled`, master, profile и heartbeat должны
   быть равны `0`. В full `/data/local/tmp/voyah_apollo.disabled` создан, а `voyah_apollo.pid`
   отсутствует; light вообще не устанавливает Apollo loader/Frida-файлы. `[apollo] hook ready` в
   direct-only режиме не ожидается: агент не загружался.
3. Legacy-перехват доступен только в full и включается только для отдельной стояночной диагностики:

   ```sh
   adb shell settings put global open_voyah_apollo_legacy_hook_enabled 1
   adb shell am force-stop com.qinggan.app.vehiclesetting
   ```

   После этого открыть VehicleSetting и проверять `logcat -s VoyahApollo VOYAH`,
   `/data/local/tmp/voyah_apollo.txt` и `[apollo] hook ready`. Этот opt-in не нужен для direct TLC/GLA/TSR.
4. Выключение legacy-диагностики:

   ```sh
   adb shell settings put global open_voyah_apollo_legacy_hook_enabled 0
   ```

   Watchdog закроет gate и выгрузит подтверждённый агент. Для отката запустить full
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
