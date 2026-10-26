# VoyahTune Installer

GUI на Tauri/Svelte напрямую использует Rust-движок. Пользовательского CLI и sidecar
нет. Установщик выпускается отдельно от релизов VoyahTune.
Проверки на fake ADB не заменяют испытания на автомобиле.

Новый релиз использует единый payload из `Packaging/new/`, без выбора варианта инфраструктуры.
Installer 1.8.2 применяет общий recipe и миграцию прежней инфраструктуры;
RunYN пропускается на H97X/H97Y, на остальных моделях устанавливается.
[Контракт общего payload и OTA](../Docs/installer-payload.md). Прежние payload поддерживаются.

При запуске GUI обновляет общий каталог и показывает доступные версии VoyahTune.
При выборе релиза проверяется `minimumInstallerVersion` из каталога; после скачивания
дополнительно проверяются требования манифеста. По умолчанию используется
[каталог на voyahtune.ru](https://voyahtune.ru/api/v1/releases).
Payload загружается по `url` выбранной записи (`/api/v1/releases/:id/download`).
Версия в manifest может отличаться от названия записи каталога; это не блокирует загрузку.
Адрес можно изменить кнопкой настроек рядом с обновлением списка; сохранённый URL остаётся между запусками.
«По умолчанию» возвращает встроенный адрес. Сохранение сразу перечитывает каталог;
UI показывает его URL и все версии, включая beta. Кэши разных URL разделены;
при ошибке сети доступна копия только выбранного источника с предупреждением.
Локальные архивы остаются отдельными строками «Локальный релиз».
Выберите и скачайте релиз или откройте локальный `payload_VERSION.zip`.
Интерфейс использует тёмную тему. Список прокручивается и показывает четыре обычные
строки релизов. Загрузка отображается полосой прогресса с процентом и объёмом;
проверка и распаковка — отдельным состоянием.
При обрыве соединения установщик автоматически повторяет запрос и продолжает
скачивание с сохранённого места. Скачанная часть остаётся после отмены, ошибки
и перезапуска программы: повторно нажмите «Выбрать» у того же релиза.
Докачка требует поддержки HTTP Range сервером; без неё архив скачивается заново.
Перед использованием готовый ZIP проверяется по размеру и SHA-256.
В таблице релизов видны статусы «Скачан» и «Выбран». Кнопка «Выбрать» в строке
скачивает недостающий релиз и проверяет его. «Удалить» очищает только этот релиз
из кэша компьютера; исходный ZIP и файлы автомобиля сохраняются.
Выберите «Установка» или «Удаление», затем нажмите «Далее». Для удаления VoyahTune с автомобиля
релиз не требуется. Причина недоступности «Далее» показана рядом с кнопкой.
После подключения доступны проверка автомобиля и DNS. Скачанные релизы
работают без сети; удаление известных компонентов использует небольшие встроенные
ресурсы и сохранённый рецепт без скачивания APK.

Общий релиз устанавливается поверх прежних установок: recipe удаляет старые загрузчики,
Frida и хуки и размещает новый runtime. Перезагрузка выгружает прежние eternalized-хуки.
Старой root-службе требуется первичное обновление через USB; совместимая служба
выполняет дальнейшие OTA тем же общим payload без замены своего бинарника.

## Исходники

- `crates/release-core/`: общие с updater модели каталога, payload/recipe и проверки APK.
- `crates/installer-core/`: загрузка/кэш компьютера, ADB, план и исполнение;
  прежние пути импорта моделей экспортируют типы из `release-core`.
- `crates/installer-build/`: утилита разработчика для payload/host verification и сборки.
- `desktop/`: интерфейс, прямые Tauri commands и события библиотеки.
- `tests/fixture-driver.rs`: внутренний адаптер fake ADB, не включается в GUI и требует `VOYAH_FAKE_ROOT`.

[Контракты](../Docs/installer-protocol.md) · [Архитектура](../Docs/installer-architecture.md) ·
[Карта процесса](../Docs/installer-classic-port.md) · [Сборка](BUILDING.md) ·
[Выпуск и публикация](../Docs/releasing.md).

## Сборка и проверки

```sh
./make_release.sh 4.5.0 --payload --minimum-ota-version 4.5.0
./Installer/scripts/build-all-macos.sh --mac
cargo test --manifest-path Installer/Cargo.toml
npm --prefix Installer/desktop run check
npm --prefix Installer/desktop run build
cargo build --release --manifest-path Installer/Cargo.toml -p installer-core --example fixture-driver
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-4.5.0" python3 Installer/tests/integration.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-4.5.0" python3 Installer/tests/test_installation.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-4.5.0" python3 Installer/tests/test_modes.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-4.5.0" python3 Installer/tests/test_canbus.py
python3 Installer/tests/test_release.py
```

Готовые APK, payload и GUI хранятся в игнорируемом `Releases/`. Первая команда
не собирает GUI, вторая не собирает Android. Windows/Linux выбираются отдельными
платформенными флагами. Номер 4.5.0 в примере условный; минимальная OTA-версия
определяется совместимостью root-службы. Общий GUI собирается без флага профиля.
Подписанные metadata APK формируются общим сборщиком по [контракту payload](../Docs/installer-payload.md).
Запуск Windows GUI проверяется отдельно от кросс-сборки; результаты прежних испытаний на автомобиле описаны
в [архитектуре установщика](../Docs/installer-architecture.md).
