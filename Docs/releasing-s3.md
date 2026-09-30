# Выпуск в Yandex Object Storage

Этот путь размещает payload в `s3://voyahtune/vVERSION/`, самостоятельные
установщики — в `s3://voyahtune/Installers/INSTALLER_VERSION/`, а каталог — в
GitHub `master-od`. Версии VoyahTune и Installer независимы. `3.17.0` ниже — пример нового номера;
не заменяйте байты уже опубликованной версии. Полная среда и проверки описаны
в [релизном процессе](releasing.md) и [сборке Installer](../Installer/BUILDING.md).

## Доступ

Нужны Python 3.12+, AWS CLI v2 с поддержкой `s3api put-object --if-none-match`,
доступ профиля к HeadObject/PutObject и публичное чтение объектов выпуска.
Статический ключ вводится локально, не сохраняется в репозитории:

```sh
aws configure --profile voyahtune
# Регион: ru-central1
```

На macOS AWS CLI можно установить через `brew install awscli`.
Скрипт задаёт endpoint `https://storage.yandexcloud.net` и регион сам.
Он не меняет ACL, политики бакета или его публичность.

## Сборка

Из чистого, зафиксированного checkout после профильных тестов:

```sh
RELEASE_VERSION=3.17.0
./make_release.sh "$RELEASE_VERSION" --payload
./Installer/scripts/build-all-macos.sh --mac --windows \
  --output "Releases/build/release-${RELEASE_VERSION}-installers"
./Installer/scripts/build-all-macos.sh --windows-arch x86 \
  --output "Releases/build/release-${RELEASE_VERSION}-windows-x86"
Installer/target/release/installer-build verify-payload \
  "Releases/build/installer-payload-${RELEASE_VERSION}"
```

Выбирайте только запрошенные/затронутые платформы. Первый вызов desktop создаёт
macOS Universal (arm64+x86_64) и Windows x64, второй — Windows x86. Они выполняются
последовательно и не должны перезаписывать папки результатов друг друга.
Версия Installer берётся из Cargo; сам новый номер VoyahTune её не повышает.
Сборка APK подставляет versionName/versionCode из версии payload.

Если JDK 21 не зарегистрирован в `/usr/libexec/java_home`, используйте существующий
Homebrew JDK: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
Для updater нужен `ANDROID_HOME` или `ANDROID_NDK_HOME`. Не устанавливайте новый JDK
до проверки уже имеющейся среды. Скрипты находят Rust в `Releases/cache/cargo`;
для ручных cargo-команд настройте CARGO_HOME/RUSTUP_HOME по BUILDING.md.

Перед упаковкой проверьте версии и metadata APK, сертификаты относительно предыдущего
релиза, `lipo -archs` macOS GUI и `verify-host` для ресурсов .app. Для Windows
проверьте архивы NSIS через 7z, затем архитектуру извлечённого GUI и его bundle
через `installer-build verify-host`. Для x86 ADB EXE/DLL также должны быть 32-битными.
Эти проверки не заменяют запуск GUI на Windows или установку на автомобиль.

## Папка публикации

Скрипт [upload-release-s3.py](../Installer/scripts/upload-release-s3.py) принимает
готовую плоскую папку. Обязательны:

- `payload_VERSION.zip` — исходный проверенный архив без повторной упаковки;
- `payload_VERSION.json` — ровно version/url/size/sha256, URL вида
  `https://storage.yandexcloud.net/voyahtune/vVERSION/payload_VERSION.zip`;
- `SHA256SUMS` — все остальные файлы папки, формат `sha256`, два пробела, имя файла.

Добавьте описание релиза по необходимости. Установщики и сведения об их сборке
публикуются отдельно в `Installers/INSTALLER_VERSION/`; в папке payload их быть не должно.
Подпапки, симлинки и файлы вне SHA256SUMS отклоняются. Имена — латинские буквы,
цифры, точка, подчёркивание и дефис. Папка должна содержать только публичные материалы.

Пример подготовки двух независимых папок после сборки всех трёх установщиков:

```sh
python3 - "$RELEASE_VERSION" <<'PY'
import hashlib, json, shutil, subprocess, sys, tomllib
from pathlib import Path
version = sys.argv[1]
installer = tomllib.loads(Path('Installer/Cargo.toml').read_text())['workspace']['package']['version']
out = Path('Releases/dist') / f's3-v{version}'
out.mkdir()  # Для возобновления используйте готовую папку; не пересоздавайте байты.
for suffix in ('zip', 'json'):
    shutil.copy2(Path('Releases/dist') / f'payload_{version}.{suffix}', out)
entry_path = out / f'payload_{version}.json'
entry = json.loads(entry_path.read_text())
entry['url'] = f'https://storage.yandexcloud.net/voyahtune/v{version}/payload_{version}.zip'
entry_path.write_text(json.dumps(entry, indent=2) + '\n')
for path in out.iterdir():
    if path.name.startswith('VoyahTune-Installer-'):
        raise ValueError('Installer must be published under Installers/INSTALLER_VERSION')
installers = Path('Releases/dist') / f'installers-{installer}'
installers.mkdir()
app = Path('Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app')
subprocess.run(['ditto', '-c', '-k', '--keepParent', str(app),
                str(installers / f'VoyahTune-Installer-{installer}-macos.zip')], check=True)
for arch, folder in [('x64', f'release-{version}-installers'), ('x86', f'release-{version}-windows-x86')]:
    source = Path('Releases/build') / folder
    record = json.loads((source / 'build-info.json').read_text())
    if record['installerVersion'] != installer or record['embeddedPayload']:
        raise ValueError('Unexpected installer version or embedded payload')
    shutil.copy2(source / f'windows-{arch}.exe', installers / f'VoyahTune-Installer-{installer}-windows-{arch}.exe')
# Добавьте публичные RELEASE-NOTES в папку payload и BUILD-INFO.json в папку
# установщиков до вычисления SHA256SUMS.
def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
for folder in (out, installers):
    (folder / 'SHA256SUMS').write_text(''.join(
        f'{sha(p)}  {p.name}\n' for p in sorted(folder.iterdir()) if p.name != 'SHA256SUMS'))
print(out, installers)
PY
```

Для другого состава платформ адаптируйте только шаг упаковки. Не публикуйте сведения
о непроверенных платформах. Относительные ссылки из Docs при копировании release notes
заменяйте публичными ссылками. После любых правок файлов обновите SHA256SUMS до загрузки.

## Выгрузка и проверка

```sh
# Только локальная проверка и список назначения, без сети и изменений.
python3 Installer/scripts/upload-release-s3.py "$RELEASE_VERSION" --dry-run

# Загрузка и обновление локального OTA-каталога после всех успешных HEAD.
python3 Installer/scripts/upload-release-s3.py "$RELEASE_VERSION" --update-catalog

# Независимая публикация macOS Universal и Windows x64/x86 GUI.
INSTALLER_VERSION=1.3.1  # фактическая версия из Installer/Cargo.toml
python3 Installer/scripts/upload-installers-s3.py "$INSTALLER_VERSION" --dry-run
python3 Installer/scripts/upload-installers-s3.py "$INSTALLER_VERSION"
python3 Installer/scripts/upload-installers-s3.py "$INSTALLER_VERSION" --check-remote

# Только проверка уже опубликованного комплекта, без изменений и скачивания.
python3 Installer/scripts/upload-release-s3.py "$RELEASE_VERSION" --check-remote
```

Без `--update-catalog` выполняется только загрузка payload. `--directory` задаёт другую
плоскую папку; по умолчанию используется `Releases/dist/s3-vVERSION` относительно
репозитория. `--profile` и `--bucket` переопределяют `voyahtune` (URL entry должен
соответствовать выбранному бакету). Для каталога доступны `--index` и `--builder`.
Режимы `--dry-run`/`--check-remote` нельзя сочетать с `--update-catalog`.

Сначала проверяются локальный состав, SHA-256, payload entry и неизменность уже
известной версии в локальном каталоге, затем — весь набор существующих S3-ключей.
Совпадающие размер и SHA-256 metadata позволяют пропустить объект. Отсутствие
metadata или несовпадение останавливают процесс без перезаписи. Ошибка доступа
не трактуется как отсутствие объекта. Нельзя обходить конфликт заменой хешей:
восстановите исходные файлы либо выпустите новый номер.

Новые файлы передаются через conditional PutObject `If-None-Match: *`:
даже конкурентная запись не приводит к замене объекта. Передача снабжается
Content-MD5, который проверяет Yandex. Это поддерживается
[API PutObject](https://yandex.cloud/en/docs/storage/s3/api-ref/object/upload).
Используется один PUT на файл, максимум 5 GB; multipart-загрузка не реализована.
На время передачи создаётся локальная копия одного файла — нужно свободное место
для самого большого артефакта. Разрыв передачи может потребовать повторить этот PUT.

После загрузки выполняются авторизованный и публичный HEAD. Публичный HEAD проверяет
размер и переданное значение sha256 в metadata; перенаправления отклоняются.
Повторного скачивания/хеширования удалённых архивов нет. Это проверка доступности
и metadata, а не независимое подтверждение SHA-256 удалённых байтов. По умолчанию
скрипт не меняет ACL, не удаляет объекты, не вызывает git и не собирает приложения.

При прерывании повторите команду с теми же файлами: совпадающие объекты пропускаются.
Частично загруженный комплект остаётся в S3, каталог при ошибке не обновляется.
После ручного обновления публичных файлов под тем же номером metadata может быть
недостоверной; такой случай требует отдельного расследования, не обхода проверки.

## Каталог и Git

Флаг `--update-catalog` использует существующий merge/валидатор и атомарную запись,
добавляя новую версию с сохранением старых. Альтернатива для уже загруженного payload:

```sh
python3 Installer/scripts/update-catalog.py \
  --entry "Releases/dist/s3-v${RELEASE_VERSION}/payload_${RELEASE_VERSION}.json" \
  --verify-head
```

При явно запрошенной полной проверке используйте `--verify-remote` вместо
`--verify-head`: она скачивает архив. Оба флага одновременно запрещены.
Для любого обновления каталога нужен собранный `installer-build`.

Commit/push выполняются только в согласованном объёме. Перед push проверьте remote
и свежесть ветки, чтобы сохранить параллельно опубликованные записи:

```sh
git fetch github.com master-od
git diff -- Releases/ota/index.json
git diff --check
git add Releases/ota/index.json
git commit -m "Publish VoyahTune ${RELEASE_VERSION} in OTA catalog"
# Если master-od является предком HEAD и здесь собраны согласованные исходники:
git push github.com HEAD:master-od
```

Установщики не хранятся в index: там только payload. Их ссылки имеют вид
`https://storage.yandexcloud.net/voyahtune/Installers/INSTALLER_VERSION/имя-файла`.
При исправлении GUI с новой версией публикуйте новый префикс, не перезаписывайте
старые байты. Уже опубликованный payload и его запись каталога при этом не меняются.
В папке установщиков нужны три файла (macOS Universal, Windows x64 и x86) и свой
`SHA256SUMS`; `BUILD-INFO.json` можно добавить до подсчёта контрольных сумм.
Удаление устаревших GUI из старой папки автомобильного релиза выполняйте только
после проверки новых публичных URL. Если `SHA256SUMS` и другие метаданные старой
папки перечисляют удалённые GUI, удалите эти устаревшие метаданные вместе с GUI;
payload ZIP и его JSON entry должны остаться побайтово прежними.
Проверьте небольшой
[публичный каталог](https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Releases/ota/index.json)
после push и выдайте пользователю ссылки на файлы в S3. При задержке CDN не
подменяйте проверку ответа ветки проверкой произвольного файла.

Для изменений только этих скриптов, документации и скилла пересборка GUI не нужна:
исполняемый код установщика и его ресурсы не меняются.
