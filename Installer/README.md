# VoyahTune Installer

GUI на Tauri/Svelte напрямую использует Rust-движок. Пользовательского CLI и sidecar
нет. Установщик выпускается отдельно от релизов VoyahTune.
Проверки на fake ADB не заменяют испытания на автомобиле.

При запуске GUI обновляет каталог и показывает доступные версии VoyahTune.
Требования к установщику проверяются после скачивания по манифесту. Выберите и скачайте релиз или откройте локальный `payload_VERSION.zip`.
Интерфейс использует тёмную тему. Список прокручивается и показывает четыре обычные
строки релизов. Загрузка отображается полосой прогресса с процентом и объёмом;
проверка и распаковка — отдельным состоянием.
В таблице релизов видны статусы «Скачан» и «Выбран». Кнопка «Выбрать» в строке
скачивает недостающий релиз и проверяет его. «Удалить» очищает только этот релиз
из кэша компьютера; исходный ZIP и файлы автомобиля сохраняются.
Выберите «Установка» или «Удаление», затем нажмите «Далее». Для удаления VoyahTune с автомобиля
релиз не требуется. Причина недоступности «Далее» показана рядом с кнопкой.
После подключения доступны проверка автомобиля и DNS. Скачанные релизы
работают без сети; удаление известных компонентов использует небольшие встроенные
ресурсы и сохранённый рецепт без скачивания APK.

Установщик устанавливает выбранный релиз VoyahTune. Можно установить его поверх
предыдущего релиза либо предварительно удалить VoyahTune.

## Исходники

- `crates/release-core/`: общие с updater модели каталога, payload/recipe и проверки APK.
- `crates/installer-core/`: загрузка/кэш компьютера, ADB, план и исполнение;
  прежние пути импорта моделей экспортируют типы из `release-core`.
- `crates/installer-build/`: утилита разработчика для payload/host verification и сборки.
- `desktop/`: интерфейс, прямые Tauri commands и события библиотеки.
- `../Releases/ota/index.json`: простой каталог новых релизов; `releases/index.json` остаётся для прежних установщиков.
- `tests/fixture-driver.rs`: внутренний адаптер fake ADB, не включается в GUI и требует `VOYAH_FAKE_ROOT`.

[Контракты](../Docs/installer-protocol.md) · [Архитектура](../Docs/installer-architecture.md) ·
[Карта процесса](../Docs/installer-classic-port.md) · [Сборка](BUILDING.md) ·
[Выпуск и публикация](../Docs/releasing.md).

## Сборка и проверки

```sh
./make_release.sh 3.13.0 --payload
./Installer/scripts/build-all-macos.sh --mac
cargo test --manifest-path Installer/Cargo.toml
npm --prefix Installer/desktop run check
npm --prefix Installer/desktop run build
cargo build --release --manifest-path Installer/Cargo.toml -p installer-core --example fixture-driver
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.13.0" python3 Installer/tests/integration.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.13.0" python3 Installer/tests/test_classic_port.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.13.0" python3 Installer/tests/test_modes.py
VOYAH_TEST_PAYLOAD="$PWD/Releases/build/installer-payload-3.13.0" python3 Installer/tests/test_canbus.py
python3 Installer/tests/test_release.py
python3 Installer/tests/test_catalog_publish.py
python3 Installer/scripts/sync-classic-commands.py --check
```

Готовые APK, payload и GUI хранятся в игнорируемом `Releases/`; исключение — публикуемый `Releases/ota/index.json`. Первая команда
не собирает GUI, вторая не собирает Android. Windows/Linux выбираются отдельными
платформенными флагами. В текущей переработке проверяется только macOS;
Windows проверяется отдельно; результаты текущих испытаний на автомобиле описаны
в [архитектуре установщика](../Docs/installer-architecture.md).
