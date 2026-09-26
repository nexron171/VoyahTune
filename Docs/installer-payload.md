# Состав и сборка payload

Один `payload_VERSION.zip` содержит `manifest.json` и общие APK/runtime-артефакты
для Full/Light. На релиз собираются ровно один Native и один RestoreMode APK,
с одинаковыми package ID и ключами для двух режимов. Различие — внешние файлы
и `Settings.Global.voyahtune_install_mode`, а не build flavor.

Источники: `Native/`, `RestoreMode/`, `SharedAndroid/`, `Packaging/inject/`,
`Packaging/system/`, `Packaging/tools/`, DNS helper и overlay из `Packaging/`.
[Payload spec](../Packaging/installer/payload-spec.json) задаёт специальные роли,
режимы, права и накопительную очистку. `.js/.json` обнаруживаются автоматически;
новые собственные имена используют `voyahtune_`/`voyahtune-`. По умолчанию они Full;
явная запись в spec позволяет выбрать Light/оба режима и собственную цель.

Исполнитель использует recipe для copy/replace/remove/directories/attributes.
Добавление или удаление такого файла не требует пересборки установщика. Старый путь
при удалении исходника нужно оставить в `removeFiles`, включая пропущенные релизы.
Новые системные роли, новые команды и семантика требуют версии установщика/capability.
[Схемы, примеры и ограничения операций](installer-protocol.md).

Сборка: `./make_release.sh VERSION --payload`. Результаты:
`Releases/dist/payload_VERSION.zip`, `payload_VERSION.json` (заготовка записи каталога),
`Releases/build/installer-payload-VERSION/`. Desktop toolchain не запускается.
Metadata schema 2 подписана внутри APK: общие runtime hashes, recipe digest,
`supportedModes`. Сборщик сверяет подписи, хеши и версии до упаковки ZIP.

Классические Full/Light ZIP остаются отдельным форматом доставки с одной и той же
парой APK. Их списки `.sh/.bat` обновляются отдельно; новый recipe не исполняется
классическими сценариями. GUI скачивает payload через каталог либо импортирует ZIP.
ADB и host GUI-файлы в payload не входят.
