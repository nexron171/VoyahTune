# VoyahTune Installer

GUI на Tauri/Svelte напрямую использует Rust-движок. Пользовательского CLI и sidecar
нет. Установщик 1.0.0 выпускается отдельно от версий автомобильного комплекта.
Проверки на fake ADB не заменяют испытания на автомобиле.

При запуске GUI обновляет каталог, показывает доступные версии VoyahTune и требования
к установщику. Выберите и скачайте комплект или откройте локальный `payload_VERSION.zip`.
После проверки доступны Full/Light, DNS и подключение автомобиля. Скачанные комплекты
работают без сети; удаление известных компонентов использует небольшие встроенные
ресурсы и сохранённый рецепт без скачивания APK.

Full/Light используют одну пару APK. Режим задаётся системным флагом во время установки.
Оба перехода разрешены поверх. Light всегда удаляет hooks/Frida без очистки данных, затем обновляет APK и фиксирует режим. После Full рекомендуется полное удаление, но оно не обязательно.
При том же ключе подписи данные сохраняются; смена ключа переустанавливает соответствующее
приложение с очисткой данных. VoyahHlCTRL удаляется только после отдельного согласия.

## Исходники

- `crates/installer-core/`: payload, каталог/загрузка/кэш, режимы, ADB, план и исполнение.
- `crates/installer-build/`: утилита разработчика для payload/host verification и сборки.
- `desktop/`: интерфейс, прямые Tauri commands и события библиотеки.
- `releases/index.json`: каталог опубликованных payload и ссылок обновления инструмента.
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

Готовые APK, payload и GUI хранятся в игнорируемом `Releases/`. Первая команда
не собирает GUI, вторая не собирает Android. Windows/Linux выбираются отдельными
платформенными флагами. В текущей переработке проверяется только macOS;
Windows проверяется отдельно; результаты текущих испытаний на автомобиле описаны
в [архитектуре установщика](../Docs/installer-architecture.md).
