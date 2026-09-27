# Состав и сборка payload

Один `payload_VERSION.zip` содержит `manifest.json`, один Native APK, один RestoreMode
APK, инфраструктуру OTA и полный runtime. Вариантов установки, supportedModes и системного флага режима нет.

Источники: `Native/`, `RestoreMode/`, `SharedAndroid/`, `Packaging/inject/`,
`Packaging/system/`, `Packaging/tools/`, `Updater/`, DNS helper и overlay из `Packaging/`.
[Payload spec](../Packaging/installer/payload-spec.json) задаёт роли, права и
накопительную очистку. `.js/.json` обнаруживаются автоматически; новые собственные
имена используют `voyahtune_`/`voyahtune-`.

Payload schema 4, recipe schema 3 / `qinggan-v3`, APK metadata schema 3.
Минимум Installer 1.2.0, capabilities `qinggan-v3`, `single-package-v1`, `files-v1`,
`ota-bootstrap-v1`. Сборщик сначала собирает ARM64 updater и его APK, затем передаёт
их хеши вместе с recipe в metadata Native и RestoreMode.
Требования внутри payload и записи каталога совпадают. Старые архивы не преобразуются
новым установщиком; для установки используйте новый релиз. Опубликованные
записи старых релизов не переписываются.

Исполнитель использует recipe для copy/replace/remove/directories/attributes.
Новый собственный файл в рамках этих операций не требует пересборки GUI. Старый путь
при удалении исходника остаётся в `removeFiles`, включая пропущенные релизы.
Новые системные роли и семантика требуют новой capability/версии установщика.
[Полный контракт](installer-protocol.md).

Сборка: `./make_release.sh VERSION --payload`. Результаты:
`Releases/dist/payload_VERSION.zip`, `payload_VERSION.json` (запись каталога),
`Releases/build/installer-payload-VERSION/`. Desktop toolchain не запускается.
Подписанные metadata APK содержат runtime hashes и recipe digest; сборщик сверяет
подписи, хеши и версии перед упаковкой ZIP.

Классический `VoyahTune-VERSION.zip` со скриптами — отдельный формат доставки.
Его списки `.sh/.bat` обновляются отдельно; файловый recipe эти скрипты не исполняют.
Первую установку инфраструктуры OTA выполняйте GUI 1.2.0: классический ZIP
не реализует новый протокол bootstrap/блокировки/USB-диагностики.
GUI скачивает payload через каталог либо импортирует ZIP. ADB и GUI в payload не входят.
