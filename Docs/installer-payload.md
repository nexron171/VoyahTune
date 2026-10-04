# Состав и сборка payload

Каждый `payload_VERSION.zip` содержит `manifest.json`, один Native APK, один RestoreMode
APK, инфраструктуру OTA и runtime выбранного профиля. PI дополнительно содержит
RunYN (`runyn.apk`, `big.town.runyn`) для штатной навигационной карточки приборной панели. Полный VERSION включает
`-pi`/`-od`, например `payload_3.22.0-pi.zip`. Java-исходники общие; профиль задаётся
при сборке содержимого релиза и не переключается в настройках Android.

Источники: `Native/`, `RestoreMode/`, `SharedAndroid/`, `Updater/` и выбранный каталог
`Packaging/pi/` либо `Packaging/od/`. Frida hooks, конфигурации, loader и установочные
скрипты между ними не смешиваются. Общий DNS overlay находится в `Packaging/vendor-overlay/`.
[Общий spec](../Packaging/installer/payload-spec.json) задаёт роли, права и накопительную
очистку. Сборщик адаптирует его для PI: выбирает Go loader и добавляет RunYN. Собственные `.js/.json` выбранного каталога обнаруживаются автоматически;
новые имена используют `voyahtune_`/`voyahtune-`.

Payload schema 4, recipe schema 3 / `qinggan-v3`, APK metadata schema 3.
Поле `infrastructure` (`pi` или `od`) входит в manifest и подписанные metadata обоих APK.
Минимум Installer 1.5.0; capabilities: `qinggan-v3`, `single-package-v1`, `files-v1`,
`ota-bootstrap-v1`, `infrastructure-v1`. Сборщик собирает ARM64 updater и его APK,
PI также собирает RunYN. Хеши updater, runtime и RunYN вместе с recipe передаются
в metadata Native и RestoreMode.
PI использует Go `loaderFrida`, OD — shell `load.bin`; валидатор проверяет loader,
профиль APK и суффикс версии. Старые metadata без поля считаются OD; новые содержат поле.

GUI общий и умеет устанавливать оба профиля. Инфраструктуру он берёт из проверенного
payload. Каталог также общий: версии `3.22.0-pi` и `3.22.0-od` — отдельные строки.
OTA-служба фильтрует свой суффикс перед выбором версии/скачиванием и дополнительно
проверяет совпадение manifest/metadata. Старые версии без суффикса относятся к OD.
Каталог содержит только version/url/size/sha256; requirements находятся в payload.
Старые схемы архивов не преобразуются; опубликованные записи не переписываются.

Исполнитель использует recipe для copy/replace/remove/directories/attributes.
Новый файл в рамках этих операций не требует пересборки GUI, если не входит в его
встроенный recovery/remover. Старый путь при удалении исходника остаётся в `removeFiles`,
включая пропущенные релизы. Новые системные роли и семантика требуют новой capability
и версии установщика. [Полный контракт](installer-protocol.md).

```sh
./make_release.sh 3.22.0 --od --payload
./make_release.sh 3.22.0 --pi --payload
```

Команды выполняются последовательно. Первая создаёт
`Releases/dist/payload_3.22.0-od.zip`, `payload_3.22.0-od.json` (запись каталога),
`Releases/build/installer-payload-3.22.0-od/`; вторая — соответствующие файлы `-pi`.
Можно передать полный номер с совпадающим суффиксом; противоречащий флагу суффикс
отклоняется. Desktop toolchain не запускается. Подписанные metadata содержат runtime
hashes и recipe digest; сборщик сверяет подписи, хеши, профиль и версии до упаковки ZIP.

Классический `VoyahTune-3.22.0-od.zip` или `VoyahTune-3.22.0-pi.zip` со скриптами —
отдельный формат доставки. Его списки `.sh/.bat` обновляются отдельно; файловый recipe
эти скрипты не исполняет. Первую установку актуальной OTA-инфраструктуры выполняйте
общим GUI 1.5.0: классический ZIP не реализует OTA bootstrap, общую блокировку
и USB-диагностику. ADB и GUI в payload не входят.
