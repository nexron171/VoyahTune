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

`full` содержит Frida-перехваты для руля, VirtualDisplay, launcher и multidisplay. `light` не
содержит Frida и `load.bin`. Прямой Native Binder-контур Apollo входит в оба варианта.

| Папка | Что | Куда идёт |
|---|---|---|
| `tools/` | ADB и `frida-inject-16.2.1-android-arm64` | full целиком; light — только ADB |
| `inject/` | `vd_bypass.js`, `launcherdock.js`, `steeringwheelkeys.js`, `multidisplay.js` | только full |
| `system/` | `load.bin`, init RC/wrapper, permission whitelist | full; whitelist также в light |
| `vendor-overlay/` | зафиксированный DNS RRO APK и provenance | full и light |
| `installer/common/` | общие DNS helper-файлы | full и light |
| `installer/full/`, `installer/light/` | установщики и удаление | соответствующий вариант |

Light всё равно требует `adb root` и запись в `/system` для APK и permission whitelist. Full не
заменяет штатный `/system/etc/init.logcat.sh`: загрузочная обвязка живёт в собственном
`voyahtune.load.rc`. `init.logcat.original.sh` нужен только для безопасной миграции старого релиза.

## Зафиксированный DNS RRO

`vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk` — статический RRO для
`/vendor/overlay`. Его SHA-256:

```text
c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d
```

Device-helper проверяет checksum, Android API 30, ожидаемую конфигурацию `eth0` и ownership-marker.
Неизвестный чужой overlay не перезаписывается и не удаляется. Windows-установщики DNS не меняют;
для этого отдельно запускается `install-yandex-dns.bat`.

## Apollo/ADAS: только прямой Native-контур

Legacy VehicleSetting/Frida hook удалён. В релизе нет `apollo_tech.js`, opt-in, Apollo PID-marker,
profile heartbeat и master-переключателя. `load.bin` не читает Apollo Settings, не ищет
`com.qinggan.app.vehiclesetting` и не выполняет Apollo-инъекцию.

Установщики один раз очищают следы старых версий: записывают безопасные нули в прежние ключи,
останавливают VehicleSetting, удаляют старый agent/marker/log и затем удаляют устаревшие
`Settings.Global`. Это миграция, а не runtime polling. Скрипты удаления также никогда не
восстанавливают `apollo_tech.js` из backup старого релиза.

Прямой H97X Binder-контур:

- работает в full и light без VehicleSetting и Frida;
- перед подключением проверяет установленную CanBus schema и `WRITE_CANBUS` permission;
- не подписывается на общий поток CAN callback (`TX28/TX29`);
- читает состояние при подключении, явном запросе UI и непосредственно перед записью;
- не делает delayed `TX57` confirmation и автоматические фоновые entitlement-resync;
- изолирован в приватном процессе `:apollo`.

Каждый синхронный vendor Binder call имеет одноразовый 15-секундный deadline. При зависании
завершается только процесс `:apollo`, основной Native/SetModes продолжает работать. Schema
PM/ClassLoader-проверка начинается при первом UI demand, имеет latest-only очередь глубиной один,
а её worker завершается после 30 секунд простоя. Demand принадлежит Binder-owner процесса
RestoreMode: смерть клиента освобождает transport без TTL, heartbeat или lease polling.

## Full loader и нагрузка

Постоянный 10-секундный watchdog full-варианта обслуживает только VD, launcher, keymanager и
multidisplay. Повторная тяжёлая Frida-инъекция в тот же PID после ошибки ограничена backoff
10/20/40/60 секунд; новый PID сбрасывает задержку. Owner/busy lock loops имеют sleep и конечный
budget, поэтому повреждённый lock path не создаёт 100% CPU spin.

Это не меняет политику внутренних автомобильных watchdog: редкие собственные проверки, нужные для
возврата целевого состояния, сохраняются. Оптимизация направлена прежде всего на работу,
размножаемую входящим потоком CAN-событий.

## Установка и диагностика Apollo

После установки достаточно открыть раздел Apollo Tech. В `logcat` должны быть сообщения
`ApolloTlcService`; `[apollo] hook ready` больше не существует и не ожидается. Старые ключи
`open_voyah_apollo_legacy_hook_enabled`, `open_voyah_apollo_master`, profile/heartbeat и файл
`/data/local/bin/apollo_tech.js` после миграции отсутствуют.

Первичные проверки выполняются на неподвижном автомобиле в `P` со стояночным тормозом.

## Версия и структура релиза

В установщиках стоит плейсхолдер `@VERSION@`; номер подставляет `make_release.sh`. Папка готового
релиза плоская, потому что установщики ищут APK и helper-файлы рядом с собой.

Начиная с 3.2.2 собранные релизы в git не хранятся. Старые варианты доступны по тегам, например:

```bash
git show v3.2:Releases/v3.2/install.sh
```
