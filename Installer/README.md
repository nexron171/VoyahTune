# VoyahTune Installer

GUI на Tauri/Svelte напрямую использует Rust-движок. Пользовательского CLI и sidecar
нет. Установщик выпускается отдельно от релизов VoyahTune.
Проверки на fake ADB не заменяют испытания на автомобиле.

GUI общий для PI и OD. Каталог показывает отдельные версии, например `3.22.0-pi`
и `3.22.0-od`; пользователь выбирает нужную. Backend использует инфраструктуру
проверенного payload. Новый payload требует Installer 1.5.0 и `infrastructure-v1`.

При запуске GUI обновляет общий каталог и показывает доступные версии VoyahTune.
Требования к установщику проверяются после скачивания по манифесту. Выберите и скачайте релиз или откройте локальный `payload_VERSION.zip` (VERSION включает `-pi`/`-od`).
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

Установщик устанавливает выбранный релиз VoyahTune. Релиз с той же инфраструктурой
можно установить поверх предыдущего. Для смены PI/OD сначала удалите VoyahTune,
затем выберите и установите другой релиз. Выбор делает пользователь; специального
сценария перехода в GUI нет.

## Исходники

- `crates/release-core/`: общие с updater модели каталога, payload/recipe и проверки APK.
- `crates/installer-core/`: загрузка/кэш компьютера, ADB, план и исполнение;
  прежние пути импорта моделей экспортируют типы из `release-core`.
- `crates/installer-build/`: утилита разработчика для payload/host verification и сборки.
- `desktop/`: интерфейс, прямые Tauri commands и события библиотеки.
- `../Releases/ota/index.json`: основной простой каталог новых релизов; соседний `index-beta.json` пополняется только по явной просьбе, без изменения основного. `releases/index.json` остаётся для прежних установщиков.
- `tests/fixture-driver.rs`: внутренний адаптер fake ADB, не включается в GUI и требует `VOYAH_FAKE_ROOT`.

[Контракты](../Docs/installer-protocol.md) · [Архитектура](../Docs/installer-architecture.md) ·
[Карта процесса](../Docs/installer-classic-port.md) · [Сборка](BUILDING.md) ·
[Выпуск и публикация](../Docs/releasing.md).

## Сборка и проверки

```sh
./make_release.sh 3.22.0 --od --payload
./Installer/scripts/build-all-macos.sh --mac
cargo test --manifest-path Installer/Cargo.toml
npm --prefix Installer/desktop run check
npm --prefix Installer/desktop run build
cargo build --release --manifest-path Installer/Cargo.toml -p installer-core --example fixture-driver
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.22.0-od" python3 Installer/tests/integration.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.22.0-od" python3 Installer/tests/test_classic_port.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.22.0-od" python3 Installer/tests/test_modes.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.22.0-od" python3 Installer/tests/test_canbus.py
python3 Installer/tests/test_release.py
python3 Installer/tests/test_catalog_publish.py
python3 Installer/scripts/sync-classic-commands.py --check
```

Готовые APK, payload и GUI хранятся в игнорируемом `Releases/`; исключения — основной `Releases/ota/index.json` и отдельный `Releases/ota/index-beta.json`. Первая команда
не собирает GUI, вторая не собирает Android. Windows/Linux выбираются отдельными
платформенными флагами. Для PI в команде `make_release.sh` укажите `--pi`; полный номер payload получает
суффикс `-pi`. Общий GUI собирается без флага профиля. Обычные Gradle debug-сборки используют OD. Оба профиля собираются
последовательно, поскольку используют общие Android/Rust output-каталоги.
Запуск Windows GUI проверяется отдельно от кросс-сборки; результаты прежних испытаний на автомобиле описаны
в [архитектуре установщика](../Docs/installer-architecture.md).
