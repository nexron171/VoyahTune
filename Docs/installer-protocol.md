# Контракт установщика и payload

Версия GUI/движка определяется `Installer/Cargo.toml` (первый независимый выпуск —
1.0.0). Версия VoyahTune внутри payload независима от неё. Каталог имеет schemaVersion 1,
новый payload — schema 3, recipe — schema 2 / engine `qinggan-v2`.

## Совместимость

До обращения к автомобилю проверяются `requirements.minInstallerVersion` (SemVer)
и `requirements.requiredCapabilities`. Текущие возможности: `qinggan-v2`,
`runtime-mode-v1`, `files-v1`. Изменение состава в рамках этих операций не требует
пересборки GUI. Новый обработчик/семантика получает новую capability и минимальную
версию; неизвестные schema, поля операций и capability отклоняются целиком.
Ошибка `INSTALLER_UPDATE_REQUIRED` сообщает требуемую и текущую версию.

Старые payload schema 1/2 можно открыть как локальную папку: отдельный адаптер
сохраняет прежние роли и flavor APK. Новый ZIP и каталог принимают только schema 3.
Планы старого пользовательского CLI не импортируются. Пользовательского CLI нет.

## Каталог

Отслеживаемый файл: [index.json](../Installer/releases/index.json). Публичная ветка
GitHub — `nexron171/VoyahTune`, `master-od`; remote проекта называется `github.com`,
а не `origin`. URL чтения:
`https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Installer/releases/index.json`.
В каталог добавляются только опубликованные и проверенные архивы. Недоступность URL
не препятствует локальному импорту ZIP или использованию скачанных комплектов.
Порядок сборки, загрузки asset и публикации index описан в
[инструкции выпуска](releasing.md).

Пример записи (сокращённый SHA замените хешем реального ZIP):

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-09-27T00:00:00Z",
  "installerDownloads": [],
  "releases": [{
    "version": "3.13.0",
    "publishedAt": "2026-09-27T00:00:00Z",
    "channel": "stable",
    "notesUrl": "https://github.com/nexron171/VoyahTune/releases/tag/v3.13.0",
    "payload": {
      "url": "https://github.com/nexron171/VoyahTune/releases/download/v3.13.0/payload_3.13.0.zip",
      "size": 123456,
      "sha256": "REPLACE_WITH_64_HEX_CHARACTERS",
      "manifestSchema": 3
    },
    "requirements": {
      "minInstallerVersion": "1.0.0",
      "requiredCapabilities": ["qinggan-v2", "runtime-mode-v1", "files-v1"]
    }
  }]
}
```

`installerDownloads` содержит объекты `version`, `platform` (`macos`, `windows`,
`linux`) и HTTPS `url`. Несовместимые новые версии остаются видны. Сортировка — SemVer.
Невалидный/недоступный сетевой каталог не заменяет последний сохранённый.

## Payload и файловые операции

В корне ZIP лежит `manifest.json`: `schema`, `product`, `releaseVersion`,
`buildRevision`, `requirements`, `recipe`, `artifacts`. Для удаления существует
отдельный внутренний `removalOnly: true` комплект, непригодный для установки.
Артефакт имеет `name`, относительный `path`, `size`, `sha256`; в schema 3 у Native и
RestoreMode нет `variant`. В архиве ровно одна общая пара APK. DNS APK также общий.
Подписанные APK metadata schema 2 содержат `supportedModes: ["full", "light"]`,
общий `recipeSha256` и `runtimeHashes`.

Источник recipe — [payload-spec.json](../Packaging/installer/payload-spec.json).
Сборщик добавляет обнаруженные `.js/.json` из `Packaging/inject`, формирует recipe
и передаёт его в обе Gradle-сборки. Пример новой Full-конфигурации:

```json
{
  "artifact": "voyahtune_example.json",
  "variantArtifact": false,
  "variants": ["full"],
  "destination": "/data/local/bin/voyahtune_example.json",
  "mode": 420,
  "phase": "files"
}
```

`files` копирует/атомарно заменяет файл с владельцем root:root и правами 0644 (420)
или 0755 (493). `variants` выбирает Full/Light; файл для Light не требует Full runtime.
`directories` создаёт собственные каталоги; `attributes` задаёт права уже существующего
собственного пути. Элементы обоих массивов: `path`, `mode`, `variants`.
`removeFiles` — накопительный перечень устаревших собственных файлов; текущие цели
recipe исключаются из очистки при обновлении. При удалении очищаются также текущие
файлы recipe. `removeDirectories` — точные собственные каталоги для полного удаления.
Tombstones старых выпусков сохраняются: пользователь может пропускать версии.
В schema 2 нет произвольного shell/exec, новых произвольных пакетов или prefix-delete.
Системные пути ограничены существующими ролями; новые собственные файлы допускаются
под `voyahtune_`/`voyahtune-` в поддерживаемых `/data/local/bin`, `/data/local/tmp`
и `/sdcard/tmp`. Для каталогов/attributes поддерживается только `/data/local`.

Native, whitelist, RestoreMode, boot и DNS остаются типизированными обработчиками.
Их fixed destination и обязательные режимы нельзя переопределить файловым recipe.
Изменение содержимого произвольного файла задаётся его заменой; общего текстового
редактора чужих системных файлов в протоколе нет.

## Фазы и восстановление

| Фаза | Политика |
| --- | --- |
| Проверка локального payload | Все хеши, metadata APK, recipe и совместимость до записи на ГУ |
| Root и диагностика | Прежняя root/wait/root последовательность; затем повторная проверка режима |
| CAN permission | Прежняя проверка; удаление VoyahHlCTRL только с отдельным согласием |
| Remount | Прежняя процедура, включая перезагрузку при необходимости |
| Backup / подписи | Прежние Full/Light правила backup; CE/DE reset только при смене подписи |
| Runtime / files | Full: остановка hooks и миграция app_client; Light: soft cleanup до замены APK; затем файловый recipe |
| Full boot | Backup/миграция init.logcat, staging/publish/rollback boot-hook |
| Native / RestoreMode / DNS | Прежние процедуры APK, whitelist и DNS overlay |
| Commit режима | Force-stop обоих приложений, запись Settings.Global и read-back |
| Reboot / verify | Прежнее ожидание загрузки и проверка Native |
| Remove | Отключение, DNS restore, boot cleanup, recipe cleanup, настройки, приложения, очистка режима, reboot |

Ошибка не означает отката всех выполненных шагов. Сохраняются прежние локальные
транзакции boot/legacy и перезапуск loader до принятой финальной перезагрузки.
Журналы и backup сохраняются на компьютере; удаление не зависит от проверки
подписей установленных APK. Рецепт удаления сохраняется отдельно от download cache
перед изменениями, чтобы быть доступным и после прерванной установки.

## Runtime-режим

Единый ключ Settings.Global: `voyahtune_install_mode`, значения строго `full`/`light`.
Общий `InstallMode` инициализируется в `Application.attachBaseContext`, до providers.
Валидный режим кэшируется на процесс; отсутствующий/ошибочный/недоступный ключ —
`UNKNOWN`, Full-функции выключены, следующее обращение повторяет чтение.
После смены режима установщик останавливает приложения и перезагружает ГУ.
Full loader также проверяет ключ; пока Settings недоступен или режим не full,
он не активирует hooks. Реальное время активации на ранней загрузке проверяется на ГУ.

Все переходы Full/Light разрешены. Старый режим читается только для справки;
отсутствие флага, метаданных или ошибка чтения не блокируют установку и не требуют
ответа пользователя о прежнем комплекте.

Каждая установка Light сначала выполняет soft cleanup: останавливает приложения
без очистки данных, мигрирует собственный legacy init.logcat с backup/rollback,
останавливает и удаляет boot-loader, хуки, Frida и Full-only файлы/каталоги recipe.
Удаляются известные runtime-пути, в том числе `/data/local/bin/load.bin` и
`/data/local/bin/frida-inject`; произвольные сторонние файлы не сканируются.
CE/DE, Settings.Global, DNS и общие Light-файлы сохраняются. Затем APK обновляются
поверх. При одинаковой подписи данные остаются; прежний сценарий смены ключа
отдельно предупреждает о сбросе.

При ошибке cleanup процесс останавливается до установки APK, commit и финального
reboot. После удаления boot path loader при ошибке не перезапускается. Флаг `light`
записывается только после успешных шагов, с обязательным read-back. Финальный reboot
выгружает оставшиеся агенты в системных процессах. На ГУ это требует проверки.
GUI рекомендует предварительное удаление Full, объясняет потерю данных при полном
удалении и разрешает Light поверх без такого удаления.

Отдельный Remove очищает приложения, данные и флаг; soft cleanup не вызывает Remove.
Классические `.sh/.bat` остаются отдельным форматом. Они пишут/удаляют runtime-флаг;
их старый Light-пакет по-прежнему требует отдельной миграции для legacy init.logcat,
поскольку не содержит проверенного OEM fallback. Общий GUI payload содержит fallback.

## Доставка

Каталог и redirects — HTTPS. ZIP проверяется по размеру/SHA-256 до распаковки,
затем проверяется весь payload. Пути выхода, ссылки, специальные файлы, коллизии
регистра и дубликаты запрещены. Ограничения: 2 GiB ZIP, 4 GiB распакованных данных,
4096 entries. Публикация в кэш атомарна; отменённая загрузка не становится готовой.
SHA-256 связывает архив с HTTPS-каталогом, но не является самостоятельной подписью
издателя. План закрепляет manifest digest; перед выполнением файлы проверяются заново.
EOF внешнего CLI больше не участвует в recovery: GUI использует библиотеку напрямую.
