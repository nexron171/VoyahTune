# Updater

Самостоятельный root-процесс `voyahtune-updater`, отдельный APK интерфейса `app`
(`ru.big.town.updater`) и отдельные библиотеки `ui` и `client`. RestoreMode открывает внешний экран.
Исполнитель и интерфейс продолжают работать при остановке и замене RestoreMode.

Реализованы проверка каталога, загрузка архива, применение, проверка после reboot,
отдельное меню, суточное уведомление, прогресс по шагам и выбор Яндекс DNS.
На успешном результате и при любой ошибке доступна кнопка «Завершить»,
возвращающая на начальный экран проверки обновлений. Ошибки подготовки сбрасываются
в новой службе; у прежних служб сбрасывается представление. После ошибки записи
USB-блокировка службы сохраняется. При отсутствии связи сброс службы откладывается
до её восстановления; активная операция продолжает выполняться.
Интерфейс и root-служба устанавливаются и обновляются через USB актуальным Installer.
OTA сохраняет их установленными; [контракт](../Docs/updater-ui-ota.md).
Вход из RestoreMode открывает новую Activity и начальный экран; прежний `finish` поддерживается.
Служба сохраняет журналы сессии локально и отправляет чанки на voyahtune.ru с `isOta: true`, продолжая сессию после reboot. UI позволяет выбрать версию и переустановить текущую; кнопок журнала в UI нет. Компоненты включены в payload
и Installer. [Приёмка на ГУ](../Docs/ota-acceptance.md) выполняется отдельно:
локальная сборка не является проверкой автомобиля.

Оформление соответствует [дизайн-системе настроек RestoreMode](../Docs/settings-design-system.md):
Arimo, голубые действия, карточки, закреплённая нижняя панель и этапы в боковой
навигации. UI работает через внешний интерфейс `UpdateService`; JSON schema 1 сохраняется.

Контракт и ограничения описаны в [документации updater](../Docs/ota-updater.md).

## Локальная сборка

Используются Android SDK 35, NDK 27.0.12077973 и Rust из
[окружения проекта](../Docs/releasing.md). Путь к SDK задаётся в локальном
`local.properties`, как для Native и RestoreMode. Cargo-зависимости должны быть в кэше.

Из `Updater/`:

```sh
./gradlew --offline :app:assembleDebug :updater-ui:testDebugUnitTest
cargo test --locked --offline --manifest-path daemon/Cargo.toml
python3 build-daemon.py --ndk "$ANDROID_NDK_HOME"
```

Root executable: `build/daemon/arm64-v8a/voyahtune-updater`.
APK интерфейса: `app/build/outputs/apk/debug/app-debug.apk`; единый payload включает
его release-сборку как `common/voyahtune-updater.apk`. Для сборки executable под эмулятор
передать `--abi x86_64`; это не проверяет OEM init и SELinux.
APK подписывается настроенным Gradle debug-ключом, как существующие Android-проекты;
для воспроизводимого выпуска необходимо использовать один сохранённый ключ.
На компьютере executable не запускает службу: необходимы Android UID 0 и сокет init.

Проверки UI/root, Native/RestoreMode и mock автомобиля запускаются из корня через
`python3 Utils/test_android_integration.py --serial emulator-5554`.
Покрытие и ограничения — [интеграционные проверки](../Docs/ota-updater.md#интеграционные-проверки-без-автомобиля).

OTA допускает установку по единственному условию `minimumOtaVersion`. Хеши, размер, подписи APK, совпадение версий/metadata и capabilities не служат предварительными блокировками. APK Updater и root-служба обновляются через USB; OTA не заменяет их.
