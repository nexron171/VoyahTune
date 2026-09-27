# Updater

Самостоятельный root-процесс `voyahtune-updater` и Android-интерфейс обновлений
`ru.big.town.updater`. Не зависят от процессов Native, RestoreMode и загрузчика hooks.

Сейчас реализована основа: init-служба, настройки URL каталога, состояние, журнал,
просмотр/выгрузка логов и открытие меню из VoyahTune. Проверка каталога, скачивание,
применение релиза и проверка после перезагрузки ещё не подключены. Кнопки этих операций
отключены. Компоненты пока не включены в payload установщика и не проверялись на ГУ.

Контракт и ограничения описаны в [документации updater](../Docs/ota-updater.md).

## Локальная сборка

Используются Android SDK 35, NDK 27.0.12077973 и Rust из
[окружения проекта](../Docs/releasing.md). Путь к SDK задаётся в локальном
`local.properties`, как для Native и RestoreMode. Cargo-зависимости должны быть в кэше.

Из `Updater/`:

```sh
./gradlew --offline assembleDebug assembleRelease
cargo test --locked --offline --manifest-path daemon/Cargo.toml
python3 build-daemon.py --ndk "$ANDROID_NDK_HOME"
```

Результаты: `app/build/outputs/apk/` и
`build/daemon/arm64-v8a/voyahtune-updater`. Для сборки executable под эмулятор
передать `--abi x86_64`; это не проверяет OEM init и SELinux.
APK подписывается настроенным Gradle debug-ключом, как существующие Android-проекты;
для воспроизводимого выпуска необходимо использовать один сохранённый ключ.
На компьютере executable не запускает службу: необходимы Android UID 0 и сокет init.
