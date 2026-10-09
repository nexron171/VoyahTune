# DiPlay в VoyahTune: спецификация интеграции и восстановления

Этот документ — контракт адаптации, по которому восстанавливают интеграцию после
полной замены исходников DiPlay. Он описывает текущее рабочее дерево на **9 октября
2026**, включая ещё не закоммиченные изменения; это не описание опубликованного
релиза. Имена upstream-классов могут измениться: при обновлении сохранять поведение
и границы ответственности, а не только переносить строки патча.

Правила работы лежат в [корневом AGENTS.md](../AGENTS.md), локальные правила DiPlay —
в [RestoreMode/diplay/AGENTS.md](../RestoreMode/diplay/AGENTS.md). Этот документ —
единственная полная спецификация; README и локальный AGENTS содержат ссылки на неё.

## 1. Происхождение и границы

- База: **DiPlay 0.2.15**, tag `v0.2.15`, commit **`b940efe81ebe6fe930caac71e19d9624723e6b7f`**,
  репозиторий [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).
- Копия [RestoreMode/diplay](../RestoreMode/diplay/README.md) — vendored source,
  не submodule. Модули `common` и `shared` импортированы; `mobile` не используется.
- Иконка `ic_car_home.png` и таблица манёвров взяты/адаптированы из VoyahPlay,
  commit **`850b196b09dead3180b027a6f77ad4e040a9bea0`**. Таблица имеет происхождение
  GPL-3.0 и наблюдалась в форке на H97; это не подтверждение всех OEM-прошивок Voyah.
- Сохранять [LICENSE](../RestoreMode/diplay/LICENSE),
  [THIRD_PARTY_NOTICES](../RestoreMode/diplay/docs/THIRD_PARTY_NOTICES.md),
  лицензии вложенных компонентов и атрибуцию заимствованных частей.
- DiPlay входит в **один APK RestoreMode** (`ru.big.town.restoremode`). Иконка
  **VoyahTune CarPlay** — ещё одна точка входа того же APK. Отдельный APK DiPlay
  не устанавливается, официальное обновление его APK не обновляет встроенную копию.
- Протоколы AirPlay/iAP2/MFi, кодеки и JNI целиком не переписаны: наши изменения
  подключают UI, источники автомобиля, маршруты аудио и жизненный цикл сессии.
- Java-база Native/RestoreMode общая. Эта интеграция не требует правок PI/OD loader,
  Frida или root-службы. Не переносить её в `Packaging/pi/` и не создавать второй CAN-клиент.

## 2. Архитектура и обязательные границы

```mermaid
flowchart LR
    UI[VoyahTune: Dashboard / Settings / Launcher] --> CP[DiPlayActivity / CarPlayHostActivity]
    CP --> Engine[diplay-common + diplay-shared]
    Engine <--> Phone[iPhone: USB / wireless]
    Engine --> Session[DiPlaySessionService]
    Session --> Bridge[CarPlayVehicleBridge]
    Bridge <-->|Messenger 120–123| Native[Native: CarPlayVehicleController]
    Native <--> CAN[CanBusEventHub / OEM CAN]
    Native --> Cluster[CarPlayClusterNavigation / OEM cluster]
    Native -->|signature broadcast Siri| Bridge
    Bridge --> Integration[VoyahCarPlayIntegration]
    Integration --> Engine
```

| Слой | Ответственность и инвариант |
| --- | --- |
| `RestoreMode/app` | Дизайн VoyahTune, настройки, выбор виджетов, Messenger-клиент Native. Здесь находятся зависимости от API приложения. |
| `diplay-common` | Штатные экраны подключения/проекции, сервис сессии, интерфейсы подключения UI и автомобиля. Не импортирует `ru.big.town.restoremode.R` или классы Native. |
| `diplay-shared` | Протоколы, медиатранспорт, буферы скорости/манёвров. iAP2/аудиопотоки не вызывают OEM Binder напрямую. |
| `Native` | Единственный доступ к автомобильным данным через общий `CanBusEventHub`, запуск на дисплеях и запись манёвров в OEM cluster. |
| `SharedAndroid` | Общие идентификаторы `CarPlayTarget` и контракт `CarPlayVehicleProtocol`; обе стороны компилируются с одним контрактом. |

`VoyahApplication.onCreate()` вне процесса `:ota` устанавливает два адаптера:

1. `VoyahCarPlayIntegration.install(new CarPlayVehicleBridge(this))`.
2. `CarPlayHomeUi.install(CarPlayHomeScreen::new)`.
3. `CarPlayWindowUi.install(new CarPlayWindowPolicy())`.

Первый включает наличие интеграции (`available`) и источники Voyah, второй заменяет
только стартовый экран, третий задаёт безопасную область окна и текст ошибки
аутентификации. Их отсутствие не должно требовать ссылки библиотеки на
приложение: штатный home остаётся fallback. Статические регистрации не должны
удерживать Activity; bridge хранит application context, фабрика создаёт отдельный View.

## 3. Сборка, манифест и authentication assets

[RestoreMode/settings.gradle.kts](../RestoreMode/settings.gradle.kts) подключает
`:diplay-common`, `:diplay-shared` и каталог `diplayLibs`. Внутри common зависимость
на `:shared` заменена на `:diplay-shared`, обращения к каталогу — на `diplayLibs`.
[app/build.gradle.kts](../RestoreMode/app/build.gradle.kts) зависит от common;
`common` экспортирует shared. Не подключать upstream `mobile`, applicationId или его launcher.

Текущая конфигурация: AGP **9.3.0**, Gradle **9.5.0**, compileSdk **37**; приложение
сохраняет minSdk **30**, targetSdk **35**. Библиотеки имеют minSdk 25 (upstream 0.2.15); targetSdk 37
в shared не означает смены targetSdk APK. Проверенное окружение — JDK 21, NDK
**28.2.13676358** для DiPlay и **27.0.12077973** для голосовой части RestoreMode.
Release ABI приложения — arm64-v8a, debug включает arm64-v8a и x86_64; shared также
умеет armeabi-v7a, но итоговый набор определяется приложением. Версия APK — VoyahTune
из `versions.json`, версия DiPlay ведётся отдельно в этой спецификации.

Манифест библиотеки объединяется с
[манифестом приложения](../RestoreMode/app/src/main/AndroidManifest.xml). Сохранить:

| Компонент | Итоговое требование |
| --- | --- |
| `DiPlayActivity` | `singleTask`, affinity `${applicationId}.carplay.settings`, label VoyahTune CarPlay, иконка `@mipmap/ic_carplay_launcher`; upstream intent-фильтры удалены через `tools:node="removeAll"`. |
| `CarPlayLauncherActivity` | Единственный launcher-вход CarPlay; exported, affinity `.carplay.launcher`, `excludeFromRecents`, прозрачная тема, иконка `@mipmap/ic_carplay_launcher`. Основной launcher VoyahTune сохраняется. |
| `CarPlayHostActivity` | `singleTask`, affinity `${applicationId}.carplay`, resizeable/configChanges и штатный USB attach intent из common. |
| `DiPlaySessionService` | Штатный foreground service connectedDevice/microphone/location с учётом разрешений; не отдельный процесс. |
| `BootReceiver` | `enabled=false` через manifest override. Наличие опции boot в штатном UI не включает этот receiver. |
| `PhoneBluetoothReceiver`, `UpdateApkProvider` | Удалены manifest overrides. `REQUEST_INSTALL_PACKAGES` от standalone DiPlay также удалено; обновлением встроенного движка управляет VoyahTune. |
| `AdbClusterActivity`, `MyCarAppService` | Удалены manifest merge overrides. Не возвращать при обновлении. |
| Native IPC | RestoreMode запрашивает `ru.big.town.anative.permission.BIND_SET_MODES_SERVICE`; служба Native защищена этой signature-permission. APK должны иметь совместимую подпись. |

Название приложения и `MainActivity` используют собственный нетранслируемый ресурс
`voyahtune_app_name`. Не использовать для них `app_name`: локализованные варианты
DiPlay (`values-ru`, `values-uk`, китайские locale) иначе перекрывают имя приложения.

Локальная пара хранится в `Credentials/CarPlayKeys/offline-mfi/` от корня проекта.
Весь `Credentials/` исключён из Git; файлы передаются отдельно от исходников.
Gradle автоматически использует эту локальную папку. Другой источник можно задать
через окружение самого Gradle/сборщика релиза:

```sh
export DIPLAY_AUTH_ASSETS_DIR="$PWD/Credentials/CarPlayKeys"
```

Для LOCAL MFi Gradle получает **внешний** `DIPLAY_AUTH_ASSETS_DIR` со структурой:

```text
offline-mfi/
  identity.pk8
  certificate.p7b
```

Задача `prepare<Variant>CarPlayAuthentication` копирует в generated assets только
эти два непустых файла. Посторонние файлы из папки credentials в APK не попадают.
Release-сборка останавливается, если пары нет; явный `DIPLAY_AUTH_ASSETS_DIR` имеет
приоритет над локальной папкой и также требует полной пары. Без обоих источников
source-only debug-сборка остаётся возможной, но LOCAL MFi в ней не заработает.
Проверка наличия файлов не доказывает соответствие ключа сертификату; bootstrap
проверяет пару при загрузке. Упаковку и наличие обоих assets в итоговом APK проверять
отдельно от подписи APK. Ключи и сертификаты не выводить в логи.

`DiPlayBootstrap.ensure()` сначала применяет `VoyahCarPlayDefaults`, затем для
LOCAL MFi устанавливает assets в `noBackupFilesDir/offline-mfi` через staging,
проверку `LocalMfiAuthenticationClient.load` и rename. Существующая директория
не перезаписывается; новые assets в APK не заменяют установленную identity.
Process-local `ready` предотвращает повторную загрузку. Обновление identity — отдельная
миграция с проверкой новой пары и восстановлением после ошибки, без очистки данных
всего RestoreMode. Имя/формат каталога и identity должны пережить замену upstream.

В исходном эксперименте использовалась пара из APK DiPlay 0.2.14. Для восстановления
потребуется отдельно сохранённый доверенный runtime-артефакт: документ и исходники
не содержат ключей, сертификатов или механизма их автоматического получения.
Не печатать их содержимое в документацию, логи, патчи и Git. Настройки отдельного
старого экспериментального APK в RestoreMode автоматически не мигрируют.

## 4. Запуск, виджет и владение сессией

Launcher-иконки независимы от значка стартового экрана и заглушки виджета
`drawable-nodpi/carplay_home_logo.png`. Исходник CarPlay —
[artwork/carplay-launcher.png](../RestoreMode/artwork/carplay-launcher.png)
(центрированная композиция: зелёный автомобиль под крупным символом CarPlay), основного приложения —
[artwork/restoremode-launcher.png](../RestoreMode/artwork/restoremode-launcher.png)
(серый автомобиль). Ресурсы находятся в `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}`:
`ic_carplay_launcher[_round].webp`, `ic_restoremode[_round].webp` — 48/72/96/144/192 px;
adaptive foreground `ic_carplay_launcher_foreground.webp` и `restoremode.webp` —
108/162/216/324/432 px, изображение занимает весь слой без полей по выбору пользователя.
Adaptive XML в `mipmap-anydpi-v26` задаёт тёмный фон и системную маску, которая может
обрезать края композиции; round-варианты legacy имеют круглую альфа-маску. WebP сохраняется без потерь,
512 px PNG лежат в `app/src/main/ic_carplay_launcher-playstore.png` и
`ic_restoremode-playstore.png`. При обновлении upstream сохранять эти app-ресурсы.

`CarPlayTarget` задаёт стабильные значения:

```text
PACKAGE  = ru.big.town.restoremode
ACTIVITY = com.shilapi.xcertplay.DiPlayActivity
LAUNCHER = ru.big.town.restoremode.carplay.CarPlayLauncherActivity
ID       = ru.big.town.restoremode/com.shilapi.xcertplay.DiPlayActivity
```

`ID` — компонент, а не имя пакета. `launchIntent` создаёт MAIN + LAUNCHER с явным
компонентом. `matches` для CarPlay принимает только `com.shilapi.xcertplay.*` внутри
RestoreMode: пакетная фильтрация без имени Activity закроет также Dashboard/настройки.
Для обычного `PACKAGE` тот же helper явно открывает `MainActivity`, а не случайный
первый launcher-компонент пакета.

OD All Apps в `Packaging/od/inject/launcherdock.js` содержит две отдельные записи
одного APK. Их ключи — package для VoyahTune и package/`CarPlayLauncherActivity`
для CarPlay; `AppBean.packageName` в обоих случаях остаётся настоящим package.
Subtype различает записи при добавлении, повторном bind и клике. Название и иконка
берутся из соответствующего ActivityInfo, поэтому CarPlay получает зелёный значок.
Обновление/удаление пакета сбрасывает кэш обеих иконок и пересобирает списки.
Клик сохраняет физический экран владельца All Apps (0/1) и явный компонент,
включая пассажирскую ленту. PI-хуки этим изменением не затрагиваются.
Стартовая и проекционная Activity CarPlay включены в `ourInsetActivity`: док остаётся
видимым над зарезервированной ими полосой, как над Dashboard.

- Launcher связывается с Native и посылает существующее сообщение **34**, `arg1=1`,
  `left=CarPlayTarget.ID`, `right=""`, `displayId` своего окна. Native принимает
  только физические 0/1; старые вызовы без displayId сохраняют водительский экран 0.
  Тайм-аут ожидания службы — 5 секунд.
- `SettingsAppPicker` отображает отдельный логический выбор CarPlay, не дублируя
  launcher как обычный пакет. `TileOrderStore` проверяет существование `PACKAGE`.
- **Настройки → Главный экран → Виджеты приложений → CarPlay → Добавить виджет CarPlay**:
  отдельная постоянно видимая карточка, без поиска CarPlay среди установленных приложений.
  `MainSettingsFragment.showCarPlayWidgetSizes` предлагает **1×1** и **8×5–12×5**;
  тот же диалог используется при выборе CarPlay через общий список приложений.
  Создаётся обычный AppWidgetStore.Entry с CarPlayTarget.ID, применяется общий лимит
  20 виджетов, затем TileOrderStore.sync и обновление списка. **12×5** заполняет сетку
  из 12 колонок и 5 рядов рядом с OEM-доком; это не вся физическая панель.
- `DashboardActivity`: 1×1 открывает отдельное окно, не создаёт embedded Surface
  и не автозапускается при простой отрисовке Dashboard. `CarPlayWidgetPlaceholder`
  для виджета с единственным профилем CarPlay показывает чёрный фон и центральную
  кнопку `carplay_home_logo`: 64dp для 1×1, 112dp для большого виджета. Штатные
  подписи, badge и launcher controls скрыты; долгое нажатие позволяет двигать плитку.
  Большие размеры используют VirtualDisplay Native и штатную передачу ввода.
  Перед их ручным запуском освобождаются другие активные виджеты CarPlay.
  Отложенный autoStart не перехватывает проекцию с основного дисплея.
- `AppDisplayLauncher` завершает прежние UI tasks CarPlay, включая уже перенесённые
  Android на целевой дисплей после release VirtualDisplay; Dashboard не затрагивается.
  Дожидается удаления и запускает целевой компонент с `launchDisplayId`.
  При открытии на физическом дисплее после удаления tasks уведомляет Dashboard
  освободить виджет и вернуть заглушку. При переходе в другой виджет общего broadcast
  нет: Dashboard уже освободил исходный, уведомление могло бы закрыть и новый.
  Не применять force-stop пакета. CarPlay исключён из обычного `DockLaunchGuard`.
- `DiPlayActivity.openProjection()` передаёт displayId собственного `decorView`
  (fallback 0) в `ActivityOptions`, `CarPlayHostActivity` открывается с REORDER_TO_FRONT.
  Без этого проекция может оказаться на физическом экране вместо виджета.

`CarPlayBackgroundSession` — штатный объект в памяти процесса: controller, sink,
размер, display, ownerDisplayId, owner и stopAction. ownerDisplayId фиксируется при
store из WindowManager владельца (доступен уже в onCreate до attach decor).
Это не персистентная сессия и не отдельный IPC-server. На том же дисплее допустим
штатный adopt. При доступной VoyahCarPlayIntegration и другом ownerDisplayId новый
CarPlayHostActivity **не принимает старую сессию**: вызывает stop, ждёт завершения
асинхронного teardown и запускает новый controller/sink с размерами нового окна.
Повторные попытки используют очередь stopWaiters, не создавая параллельный controller.
При переключении виджет ↔ Activity iPhone переподключается для согласования размера.
Не заменять этот порядок вторым параллельно работающим controller/decoder.

В `DiPlaySessionService.onTaskRemoved` при выключенной `vehicleIntegrationEnabled`
выполняется ранний return: сессия не завершается при переносе task и удалении окна
из recents. Остановка — явное действие пользователя/сессии или проверка смены дисплея новым
хостом; одного факта исчезновения UI недостаточно.
`onCreate` вызывает `VoyahCarPlayIntegration.start`, `onDestroy` — `stop` и reset.
Сервис START_NOT_STICKY; сохранение в рамках процесса не обещает восстановление
после force-stop, смерти процесса или перезагрузки.

`MapEmbedService` upstream обслуживает дополнительную карту, не встраивание полного
экрана. Не подменять им VirtualDisplay-путь.

## 5. Стартовый экран, настройки и автоподключение

`CarPlayHomeUi` — интерфейс между common и app. `Factory.create(context, actions)`
возвращает `Screen(view, updateAutoConnect, update)`. Actions предоставляют wireless,
USB, settings, back, choosePhone, disconnect, setAutoConnect, hotspotSettings. В common
остаётся выполнение действий; app только рисует экран и передаёт события.

`DiPlayActivity.render()` создаёт этот Screen **только для page=home**, вставляет его
в fillViewport ScrollView; остальные страницы DiPlay штатные. Каждый render сбрасывает
старую ссылку Screen. `refreshStatus()` обновляет home и штатные controls, включая
ошибки auth/hotspot, выбранный телефон, подключение и наличие сессии.

`CarPlayHomeScreen` использует SettingsComponents, SettingsDesign и ресурсы
`settings_*`: Arimo, фон/цвета, скругления, ripple/focus. Сохранять **три равные крупные
карточки в один горизонтальный ряд**: беспроводное подключение, USB, настройки.
Компактный режим — высота <450dp или ширина <700dp; меняются размеры, не число колонок.
Карточки минимум 190/260dp по высоте, экран прокручивается при нехватке места.
В углу заголовка — предоставленный пользователем PNG `drawable-nodpi/carplay_home_logo.png`
(192×192), ImageView 46dp с FIT_CENTER без tint; цвета и прозрачность оригинала сохранены.
Значок декоративный: accessibility читает соседний заголовок CarPlay.
Работающая сессия меняет действие первой карточки на «Открыть CarPlay».
Статус, переключатель, выбор iPhone, ошибки и отключение — отдельные элементы.
При установленной VoyahCarPlayIntegration штатный раздел DiPlay WHEEL_KEYS скрыт
через filteredSection: ни карточка кнопок руля, ни её Siri/назначения/настройки
службы не попадают в UI и поисковый индекс. Настройки руля и Siri доступны через
VoyahTune; скрытие не изменяет сохранённые preferences или обработку медиаклавиш.
Справа внизу всегда доступна кнопка «Выйти»: вызывает Actions.back →
DiPlayActivity.onBackPressedDispatcher.onBackPressed(), как системное «Назад».
Она не вызывает disconnect/stop сессии и не меняет автоподключение.
**Таймера и кнопки «Отмена» нет**: пользователь отменил эту идею.

`CarPlayWindowUi` оборачивает home/settings и весь корневой View проекции, включая
видео и жесты, общей безопасной областью. `CarPlayWindowPolicy` из app сохраняет
видимые системные панели. На физических дисплеях 0/1 он резервирует 145dp OEM-дока
и `status_bar_height`, используя плотность дисплея, а не масштаб UI DiPlay.
Уже учтённое смещение окна вычитается из OEM-отступов; Android insets объединяются
через максимум, а не сложение. Insets потребляются обёрткой один раз, чтобы внутренний
экран подключения не повторял их. На VirtualDisplay OEM-отступы не добавляются.
Размер видео и передаваемая телефону геометрия берутся из оставшейся области;
координаты касаний используют тот же viewport. Без app-policy библиотека сохраняет
свой исходный режим. Сохранённые предпочтения fullscreen не удаляются.

Раздел **VoyahTune → CarPlay** имеет стабильный идентификатор **9** (`SettingsSection.CARPLAY`),
nav id `navCarPlay`, `savesImmediately=true`. `CarPlaySettingsFragment` через публичный
`CarPlaySettingsAccess` читает те же preferences и ту же process-local сессию:

- `DISCONNECTED`, `CONNECTING` (`hasSession`), `CONNECTED` (`active`);
- выбранный iPhone и общий переключатель;
- «Отключить» при подключении или активной сессии, через штатный асинхронный `stop`;
- «Открыть экран CarPlay» с `page=home`, REORDER_TO_FRONT — без автоподключения.

Обновление состояния — раз в секунду только в resumed-разделе; обработчики убираются
в pause/destroyView. Отметка «Подключён» берётся из сессии, не из сохранённого MAC.
На время остановки кнопка блокируется до callback. Библиотека не должна зависеть
от Fragment/SettingsActivity, адаптер не должен копировать preferences в DrivePreferences.

### Русский интерфейс

Все строковые ресурсы common/shared покрыты `values-ru`, включая изображение,
аудиофокус, USB-разрешения, карту и диагностику. Вынесенные из кода тексты
I2C-диагностики и уведомления с кнопкой «Отключить» находятся в собственных
`values/strings_voyah_ui.xml` и `values-ru/strings_voyah_ui.xml`.
Названия продуктов/протоколов, команды, исходные ошибки и технические отчёты
сохраняются; текст внутри изображений не изменяется. Язык проекции iPhone
этой локализацией не управляется.

`AppLocale.wrap` при доступной VoyahCarPlayIntegration однократно выбирает русский,
если язык ещё не задан: маркер `diplay/voyah_russian_default_initialized`.
Существующий `app_language`, платформенный выбор Android 13+ и завершённая
платформенная миграция имеют приоритет, в том числе явный выбор системного языка.
Последующее переключение языка не перезаписывается. Wrap/enforce применяется
также в MainActivity, CarPlayVideoActivity и AdbClusterActivity; сервис подключения
получает локализованный base context. Штатные home/host/image crop уже используют
AppLocale. Не заменять это глобальной подменой Locale во всём процессе.
Новые штатные Appearance/Language/About-экраны 0.2.15 сохранены; тема интерфейса
DiPlay отделена от темы проекции CarPlay по SWReason. Стартовый экран остаётся
реализацией VoyahTune с тремя карточками.

При обновлении upstream проверять **все** строковые ключи common/shared и форматные
параметры через SettingsTranslationsTest, а не только префикс settings_.
Новые тексты, заданные непосредственно в UI-коде, также переносить в ресурсы.
AppLocaleTest проверяет default ru и сохранение выбора на Android до/после API 33.

### Условия автоподключения

Одна настройка `diplay/auto_connect` используется на новом home, в разделе VoyahTune
и штатном пункте DiPlay «Подключаться при открытии». По умолчанию false. Без выбранного
телефона новый переключатель недоступен; ранее включённое значение можно выключить.

1. `onCreate` без extra `page` ставит `automaticConnectionPending`; при восстановлении
   берёт сохранённый флаг Bundle (по умолчанию false). `onNewIntent` без `page` ставит
   новый запрос. Любое наличие extra `page` означает явную навигацию, не автозапуск.
2. В `onResume` запрос потребляется один раз. Нужны home, живое окно, отсутствие
   `setupError` и сессии, включённый auto_connect и сохранённый `phone_address`.
3. Вызывается обычный `connect(autoConnectWireless(context))`. Транспорт определяется
   `default_connection_mode`: `wireless`, `usb`, `last_used` (fallback для неизвестного
   значения). Последний режим берётся из AirPlayPersistence; не принудительно wireless.
4. `connect` сохраняет штатные проверки hotspot/его параметров, телефона и разрешений;
   затем записывает выбранный транспорт и открывает проекцию на текущем дисплее.
   Сохранённый MAC не доказывает, что телефон сейчас рядом/доступен. Проблемы prerequisites
   могут оставить пользователя на настройке подключения, а не открыть проекцию.
5. Возврат из проекции, внутренних настроек и обычная смена размера не повторяют запрос.
   Установка переключателя сама по себе не запускает подключение в этот момент.
6. Существующая сессия при обычном launcher-входе открывается сразу по штатному пути,
   независимо от auto_connect; это показ текущей проекции, не новое подключение.

Android Back из проекции открывает home с `page=home`, сохраняя сессию. Штатный свайп
вниз тремя пальцами (число настраивается) открывает меню DiPlay с переходом в полные
настройки. Автозапуск при загрузке устройства — другой механизм; **BootReceiver в
нашем APK отключён**, его настройка не должна молча включаться при обновлении.
Новый upstream Bluetooth wake отключён: `standaloneAppEnabled=false` скрывает его
переключатель, блокирует receiver и extra автозапуска. Сохранённый preference не
стирается. Запуск в обход Native нарушил бы владение Activity/виджетом (§4).

## 6. Автомобильный IPC, скорость и тема

Контракт: [CarPlayVehicleProtocol.java](../SharedAndroid/src/main/java/ru/big/town/common/CarPlayVehicleProtocol.java).
Направления и типы Bundle обязательны:

| Message.what | Направление | Поля/правило |
| --- | --- | --- |
| 120 WATCH | RestoreMode → Native | `replyTo`: Messenger подписчика. Один текущий владелец. |
| 121 UNWATCH | RestoreMode → Native | `replyTo`: снять только свою подписку. |
| 122 STATE | Native → replyTo | `speedKmh: int`, `gear: int`, `swReason: int`, `speedTime: long`. Неизвестные значения −1. |
| 123 NAVIGATION | RestoreMode → Native | `replyTo` владельца; `navigationActive: boolean`, `navigationTime: long`, при active также `navigationIcon: int`, `navigationDistance: int`, `navigationRoad: String`. |

Все timestamp — **SystemClock.elapsedRealtime**, миллисекунды одной загрузки ГУ,
не UTC и не время iPhone. WATCH/UNWATCH/NAVIGATION обрабатывает SetModesService;
проверку signature permission службы и совместимость подписей APK сохранять.

`CarPlayVehicleBridge` регистрируется Application, запускается сервисом сессии,
биндится к `ru.big.town.anative/.SetModesService`. При неудаче bind/dead binding
повтор через 5 секунд. Каждая новая связь получает новое поколение Messenger:
поздний ответ старого соединения игнорируется. Потеря связи очищает speed/night;
stop снимает подписку, Siri receiver, retry и navigation tick. Привязка к Activity
вместо сервиса нарушит Siri/данные в фоне и при переносе между дисплеями.

`CarPlayVehicleController` использует `CanBusEventHub` с interests CONNECTION,
GEAR, VEHICLE_SPEED, VEHICLE_STATE и фильтром **1072**. Запрашивает скорость/передачу
раз в секунду, vehicle state каждый десятый тик. Потеря CAN сбрасывает snapshot;
потеря подписчика останавливает polling и закрывает subscription.

Источник скорости: `CanBusEvent.Kind.VEHICLE_SPEED`, interest **1 << 7**, callback
OEM **16**, getter `getVehicleSpeed` в текущем CAN Binder — transaction **26**,
значение км/ч. Это дополнение к существующему hub/router, не новый транспорт.
OEM transaction проверять на целевой прошивке, не заменять условным показателем GPS.

`VoyahVehicleSpeedSource` реализует штатный `VehicleSpeedSource`:

- передача `0=P`, `1=R`, `2=N`, `3=D`, другое неизвестно;
- скорость допустима 0…300 км/ч, преобразование **км/ч / 3.6 → м/с**;
- максимум 32 отсчёта; возраст 0…3000ms, strictly newer timestamp; stale/unknown
  не превращается в нулевую скорость или передачу D;
- смена передачи очищает старые отсчёты; start/stop/reset очищают состояние;
- `drain` отдаёт свежие отсчёты с передачей и опустошает очередь.

`CarPlayHostActivity`: при available включены identification.locationInformationEnabled,
vehicleSpeedEnabled и config.locationReportingEnabled. `VehicleSpeedLocationProvider`
оборачивает GPS provider, а если GPS выключен — пустой provider. Благодаря этому
`$PASCD` со скоростью/передачей доступен по запросу iPhone без выдачи фиктивных GPS-координат.
Не связывать включение скорости Voyah с пользовательским GPS-переключателем.
Передача GPS iPhone по умолчанию включена: loadLocationReportingEnabled возвращает
true при отсутствии `xcertplay_airplay/location_reporting_enabled`. Сохранённые true/false
сохраняются, включая явное отключение на существующей установке. Разрешение точного
местоположения по-прежнему требуется; default не выдаёт Android permissions.

SWReason **2/3/4** (`dark`, `tunnel`, `darkstart`) → night=true, **0/1** → false,
прочее → null. `VoyahCarPlayIntegration.update` хранит состояние и отправляет изменение
через текущий controller. `CarPlayHostActivity.syncAirPlayDarkMode` использует
`night ?: darkMode`; `CarPlayController.setVehicleNightMode` ставит `setNightMode`
в touchExecutor, проверяя closed и неизменность activeSession, перехватывая shutdown
race. Не выполнять сетевую отправку из CAN callback. Настройка темы самого iPhone
может влиять на видимый результат. **Заряд и запас хода не подключены**.

## 7. Siri, кнопки руля, медиа и звонки

В `DrivePreferences`: `carPlaySiriEnabled` (false по умолчанию) и
`voiceAssistantSteeringPress` (`short`/`long`, неизвестное нормализуется к long).
`VoiceSteeringPolicy` публикует `carplay_siri` на **противоположное** нажатие голосовой
кнопки: ассистент short → Siri long, ассистент long → Siri short.

`VoiceSettingsPage` содержит переключатель и пояснение действия; `SplitConfigSync`
применяет policy при экспорте slots `steerVoiceShort`/`steerVoiceLong` в Native.
Сохранённые пользовательские назначения не перезаписываются и возвращаются при
выключении функции. `SteeringSettingsFragment` показывает занятые слоты и переход
к голосовым настройкам. Не хранить назначение Siri вторым независимым preference.

`SetModesReceiverDynamic` обрабатывает действие `carplay_siri` и отправляет
`ru.big.town.restoremode.CARPLAY_SIRI` с package RestoreMode и signature permission.
Bridge принимает broadcast только при started и включённом `carPlaySiriEnabled`,
затем вызывает `VoyahCarPlayIntegration.requestSiri()` на текущем controller из
`CarPlayBackgroundSession`. Без сессии действие не запускает новую сессию.

При `VoyahCarPlayIntegration.available` в `CarPlayHostActivity.dispatchKeyEvent`
и `WheelKeyService` отключён штатный перехват Siri-клавиш. Иначе появятся двойные
вызовы или обход нашего выключателя. Это не отключает штатные медиаклавиши, Android
MediaSession или обработку звонков DiPlay. Их нельзя выдавать за дополнительно
реализованный OEM интерфейс звонков/медиаданных на приборке: BYD outputs отключены.

## 8. Манёвры на приборке

`VoyahNavigation` имеет отдельный owner-guarded `VoyahNavigationState`, использует
парсер `BydHudRouteState(keepAcrossNoRoute=false)`, но **не включает BYD outputs**.
`CarPlayController` обязан вызвать:

- `begin(this)` при start после проверки closed и при активации новой сессии;
- `onFrame(this, frame)` из обработки iAP2 route frame;
- `end(this)` при окончании текущей сессии и close.

Старый controller не очищает состояние нового владельца. No route/end очищает сразу,
штатная route-state устаревает через **30 секунд** без обновлений. Snapshot заменяет
NUL пробелом, ограничивает дорогу 120 символами, расстояние неотрицательное в метрах.

Таблица `VoyahManeuverCodes` (тип Apple → OEM icon):

| Типы | Icon | Значение |
| --- | --- | --- |
| 1, 20 | 7 | налево |
| 2, 21 | 3 | направо |
| 13, 22, 49, 52 | 8 | плавно налево |
| 14, 23, 50, 53 | 2 | плавно направо |
| 47 / 48 | 6 / 4 | резко налево / направо |
| 4, 18, 19, 26 | 5; при drivingSide=1 → 0 | разворот |
| 3, 5, 8, 9, 11, 51 | 1 | прямо |
| 6, 7, 28…46 | 9; при drivingSide=1 → 0 | круговое движение |
| прочее | 0 | неизвестный тип, без выдуманной стрелки |

Bridge отправляет snapshot раз в секунду сообщением 123. Native принимает его только
от `replyTo` текущего живого WATCH-владельца. При смене владельца, UNWATCH и потере
подписчика маршрут очищается. `CarPlayNavigationLease` принимает icon 0…9,
неотрицательное расстояние, timestamp не из будущего, возраст **<5000ms**; иначе
очищает. Пропадание обновлений также очищает через 5 секунд. Это отдельный timeout
доставки, не замена 30-секундному сроку route-state.

`CarPlayClusterNavigation`:

- явная служба `com.qinggan.cluster/.service.InstrumentClusterService`, action
  `com.qinggan.cluster.InstrumentClusterService`;
- проверка системного пакета и descriptor
  `com.qinggan.cluster.IInstrumentClusterManagerService`;
- OEM ClassLoader через createPackageContext(INCLUDE_CODE | IGNORE_SECURITY),
  `$Stub.asInterface`, классы `com.qinggan.cluster.info.NaviInfo`, `GuideState`;
- вызов **updateNaviInfo(NaviInfo)**: GuideState GUIDING/MAN_STOP, currentRoadName="",
  turnRoadName=road, destName="", display=active, tbtIconId=icon,
  nextDistance=метры; при очистке пустые/нулевые значения;
- один worker, объединение промежуточных snapshot, проверка актуальности Binder и
  запрошенного состояния после загрузки OEM-классов. Не копировать номера Binder
  transaction/Parcel layout из другой прошивки и не блокировать main/iAP2 thread;
- bind/retry с ограничением 5 секунд, отслеживание собственных записей для очистки;
  destroy делает ограниченную best-effort очистку и освобождает ресурсы.

Нужны оба APK с этим контрактом. На машине ещё требуется подтвердить права вызова,
видимость/значения OEM-иконок и расстояний. После force-stop Native MAN_STOP не
гарантирован. Поле приборки общее со штатной навигацией; арбитраж с OEM-навигаторами
не реализован. Это стрелка/расстояние/дорога, не полный видеопоток карты на приборку.

## 9. Аудио, видео, defaults и отключённые OEM-функции

DiPlay разделяет PCM по `AudioStreamId(type, audioType)`; сохранить раздельные renderer,
буферы и AudioTrack. `CarPlayHostActivity` передаёт `voyahAudioRouting=available` в
`AndroidMediaSink`, который выбирает override по логическому AudioChannel:

| Канал | Автоматический OEM stream |
| --- | --- |
| Музыка MEDIA | 3 |
| Навигация NAVIGATION | 1 |
| Звонок PHONE | 6 |
| Siri ASSISTANT | 22 |

Классификацию делает штатный AudioChannelMapper: media, telephony, speech recognition,
navigation/default/alert с учётом advanced mapping. Не назначать канал только по
частоте PCM или номеру transport stream. Ручные ненулевые каналы музыки/навигации
имеют приоритет. AudioChannelPreview для Automatic должен пробовать те же 3/1.

AudioTrack создаётся с legacy stream, затем проверяются initialized и фактический
`track.streamType`. `VoyahAudioRouting.accepted` требует точного совпадения. При
ошибке/подмене stream выполняется fallback на штатные AudioAttributes; лог содержит
requested/actual/accepted/fallback. Независимая громкость при fallback не обещается.
Stream IDs установлены по SDK SE 2025 rc5.1/Sport+ 13.1; четыре независимых регулятора
на автомобиле ещё нужно подтвердить.

В 0.2.15 сохранены upstream восстановление prebuffer после underrun с учётом
оставшегося PCM и operating-rate hint основному видеодекодеру с fallback.
Экспериментальный Bluetooth audio остаётся отдельной выключенной по умолчанию
настройкой: при включении штатный вывод DiPlay отключается, поэтому независимые
OEM streams из таблицы к этому режиму не относятся. Проверка звонков/Siri/навигации
в Bluetooth audio на Voyah не выполнена.

`VoyahCarPlayDefaults.initialize` вызывается из DiPlayActivity и DiPlayBootstrap.
Основные defaults применяются один раз по `voyah_carplay/initialized`:
audio focus=true, HEVC=false. Инициализация автоматического navigation
channel выполняется **до** раннего return по этому маркеру:

- store `xcertplay_airplay`, если ключа `navigation_audio_channel` нет → записать **0**;
- если ключ есть — сохранить его, включая ручной выбор;
- это исключает наследование legacy `navigation_stream_type=3`; отдельно отличить
  старый ручной выбор в одном только legacy-ключе от прежнего default невозможно.

Видеоадаптация цвета уже есть в upstream: `CarPlayPicture`, `CarPlayPicturePanel`,
TextureView/ColorMatrixColorFilter; saturation/contrast 0…200 (100 нейтрально),
brightness −50…50, warmth −100…100. Настройки хранятся в `carplay_picture`. Это не
наш новый видеокодек. В SurfaceView/smooth video или без hardware acceleration
настройка цвета недоступна; при обновлении не обещать её для любого renderer.

`VoyahPlatformPolicy.vehicleIntegrationEnabled=false` и `localAdbEnabled=false`
сохраняют изоляцию от BYD. `standaloneAppEnabled=false` блокирует обновление
отдельного APK и независимый Bluetooth launch. Важны не только константы, но и guards:

- `LocalAdb.connect` → UNSUPPORTED **до** socket/connect/авторизации;
- новый `BydBootStartRepair.apply` сразу возвращает пустой результат; его UI скрыт;
- мастер DiLink не открывается ни при первом запуске, ни через `page=setup`, ни из
  настроек. Запрошенная setup-страница заменяется на home;
- About не показывает upstream updater; check/download/install дополнительно
  защищены policy. Исходники update оставлены для сопровождения, но недоступны в APK UI;
- `BydOutputSettings` запрещает enabled/available/navigationAvailable, battery,
  wheel speed, cluster stream pause, video-while-parked, cluster song и call controls;
- `BydNavigationOutputs` не выполняет OEM recovery/start/map-shown/route outputs и
  OEM cleanup. Разбор route/локальный overlay может остаться штатным;
- `DiPlaySessionService.onTaskRemoved` использует ту же policy для сохранения сессии.

Не включать глобальный BYD-флаг ради манёвров Voyah. Новые upstream OEM/ADB/boot entry
points нужно проверять отдельно: эти guards не покрывают ещё не существовавший код.
В DiPlayActivity скрыты advanced vehicle data при выключенном vehicleIntegrationEnabled
и ADB-раздел при выключенном localAdbEnabled; скрыта подсказка о недоступной штатной
навигации. Экспорт диагностики не добавляет отключённый vehicle-data probe.
Другие штатные настройки hotspot могут оставаться видимыми, но LocalAdb-зависимые
операции недоступны. На публичные Android USB/wireless transport этот запрет не распространяется.

Пользовательские строки во всех семи локалях (default, ru, uk, es, ar, zh-rCN,
zh-rTW) не содержат BYD/比亚迪/比亞迪. Формулировки нейтральные; подсказка теста звука
не обещает назначение stream 14, подсказка жестов не привязана к климату другой марки.
DEFAULT_OEM_LABEL в AirPlayPersistence — **Voyah Free**, в том числе при сбросе
значения. Кнопка возврата к автомобилю в меню CarPlay использует
`common/src/main/res/raw/ic_car_home.png` (логотип VOYAH). В VoyahCarPlayDefaults
до проверки initialized однократно заменяется прежнее имя «VoyahTune» на
«Voyah Free», маркер `voyah_carplay/car_brand_initialized`. Другие пользовательские
имена и выбранные вручную изображения сохраняются. Изменения имени/иконки
передаются iPhone при следующем подключении. Имена ресурсов, preferences,
классов, протоколов, технические логи и лицензии/атрибуция upstream сохраняются.
При обновлении повторить проверку текстов всех локалей и placeholders.

## 10. Полная карта адаптаций

### 10.1. Изменённые файлы vendored upstream

Сверено побайтово с чистым commit из §1: **41 изменённый upstream-файл**, включая
сборку, тесты и изображение (без собственных AGENTS/README). Пути ниже относительно `RestoreMode/diplay`. Эта таблица
дополняет поведенческие контракты выше; удаление/переименование upstream-файла
требует найти новую точку подключения с той же ролью.

| Файл | Что восстановить |
| --- | --- |
| [common/build.gradle.kts](../RestoreMode/diplay/common/build.gradle.kts) | Переименовать catalog → diplayLibs, dependency → :diplay-shared; сохранить Compose/тестовое окружение. |
| [shared/build.gradle](../RestoreMode/diplay/shared/build.gradle) | Использовать diplayLibs; сохранить JNI/NDK и library namespace. |
| [common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt) | initializeAutomaticNavigationAudioChannel: 0 только при отсутствии нового ключа; DEFAULT_OEM_LABEL=Voyah Free; location_reporting_enabled по умолчанию true, сохранённый выбор не перезаписывать. |
| [common/src/main/java/com/shilapi/xcertplay/AppLocale.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/AppLocale.kt) | Однократный русский default только для встроенного Voyah и при отсутствии выбранного языка (§5). |
| [common/src/main/java/com/shilapi/xcertplay/MainActivity.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/MainActivity.kt) | Локализованный context и строки I2C-диагностики из ресурсов. |
| [common/src/main/java/com/shilapi/xcertplay/CarPlayVideoActivity.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayVideoActivity.kt) | AppLocale.wrap/enforce для отдельного видеоэкрана. |
| [common/src/main/java/com/shilapi/xcertplay/AdbClusterActivity.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/AdbClusterActivity.kt) | AppLocale.wrap/enforce для отдельного экрана карты. |
| [common/src/main/java/com/shilapi/xcertplay/AudioChannelPreview.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/AudioChannelPreview.kt) | Automatic использует OEM 3/1; проверять фактический AudioTrack stream и fallback. |
| [common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt) | available → источники скорости/темы/аудиомаршруты Voyah; Siri keys переданы Native. В snapshot сохранять ownerDisplayId; при смене дисплея ждать stop и запускать новую сессию с новым размером (§4); подключить CarPlayWindowUi к корневому View и window policy (§5). |
| [common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt) | Defaults; projection launchDisplayId; фабрика home + обновление состояния; CarPlayWindowUi для границ окна и ошибки auth; одноразовый запрос автоподключения при открытии; скрытие WHEEL_KEYS при доступной VoyahCarPlayIntegration (§5); guards OEM/ADB-разделов и vehicle-data probe в диагностике (§9). |
| [common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt) | Вызов VoyahCarPlayDefaults.initialize до штатной загрузки identity. |
| [common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt) | start/stop интеграции по жизни сервиса, сохранение сессии при onTaskRemoved на Voyah; локализованный context и уведомление. |
| [common/src/main/java/com/shilapi/xcertplay/PhoneBluetoothReceiver.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/PhoneBluetoothReceiver.kt) | Ранний return по standaloneAppEnabled: запуск только через VoyahTune/Native. |
| [common/src/test/java/com/shilapi/xcertplay/PhoneBluetoothReceiverTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/PhoneBluetoothReceiverTest.kt) | Даже сохранённый opt-in и событие выбранного телефона не запускают отдельный task. |
| [common/src/main/java/com/shilapi/xcertplay/WheelKeyService.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/WheelKeyService.kt) | Guard штатного Siri-перехвата при available, остальная обработка клавиш сохраняется. |
| [common/src/main/res/raw/ic_car_home.png](../RestoreMode/diplay/common/src/main/res/raw/ic_car_home.png) | Бинарный ресурс из указанного commit VoyahPlay; не путать с launcher mipmap/ic_carplay_launcher и значком стартового экрана drawable-nodpi/carplay_home_logo.png. |
| [common/src/test/java/com/shilapi/xcertplay/AppLocaleTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/AppLocaleTest.kt) | Default ru и сохранение явного выбора языка при Voyah integration. |
| [common/src/test/java/com/shilapi/xcertplay/SettingsTranslationsTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/SettingsTranslationsTest.kt) | Полное покрытие русского перевода common/shared и совпадение форматных параметров. |
| [common/src/test/java/com/shilapi/xcertplay/SmoothVideoHostLifecycleTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/SmoothVideoHostLifecycleTest.kt) | Смена дисплея Voyah: ждать единственного stop, не принимать старый controller, новый размер 1280×600 вместо 1920×990. |
| [common/src/test/java/com/shilapi/xcertplay/LocationReportingSettingsTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/LocationReportingSettingsTest.kt) | GPS default on; сохранение явного отключения, permission flow и переподключение сессии. |
| [common/src/test/java/com/shilapi/xcertplay/WheelSiriWindowTest.kt](../RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/WheelSiriWindowTest.kt) | Дополнительная проверка отсутствия конкурирующего Siri-перехвата на Voyah. |
| [shared/src/main/java/com/shilapi/xcertplay/hud/BydBootStartRepair.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/hud/BydBootStartRepair.kt) | Блокировка ADB boot repair до загрузки ключей и подключения. |
| [shared/src/test/java/com/shilapi/xcertplay/media/AudioRebufferCapacityTest.kt](../RestoreMode/diplay/shared/src/test/java/com/shilapi/xcertplay/media/AudioRebufferCapacityTest.kt) | Upstream reflection-тест учитывает дополнительный параметр AudioRenderer и проверяет восстановление буфера с voyahAudioRouting=true. |
| [shared/src/main/java/com/shilapi/xcertplay/adb/LocalAdb.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/adb/LocalAdb.kt) | Ранний UNSUPPORTED по localAdbEnabled. |
| [shared/src/main/java/com/shilapi/xcertplay/hud/BydNavigationOutputs.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/hud/BydNavigationOutputs.kt) | Guards OEM запуска/route/cleanup; не включать BYD ради VoyahNavigation. |
| [shared/src/main/java/com/shilapi/xcertplay/hud/BydOutputSettings.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/hud/BydOutputSettings.kt) | Guards вычисляемых enabled/available и OEM-настроек независимо от старых preferences. |
| [shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt) | Флаг voyahAudioRouting до renderer, explicit override, stream validation, fallback и диагностический лог. |
| [shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt) | setVehicleNightMode с проверкой activeSession; begin/end/onFrame владельца VoyahNavigation. |
| [common/src/main/res/values/strings.xml](../RestoreMode/diplay/common/src/main/res/values/strings.xml)<br>[common/src/main/res/values-ru/strings.xml](../RestoreMode/diplay/common/src/main/res/values-ru/strings.xml)<br>[common/src/main/res/values-uk/strings.xml](../RestoreMode/diplay/common/src/main/res/values-uk/strings.xml)<br>[common/src/main/res/values-es/strings.xml](../RestoreMode/diplay/common/src/main/res/values-es/strings.xml)<br>[common/src/main/res/values-ar/strings.xml](../RestoreMode/diplay/common/src/main/res/values-ar/strings.xml)<br>[common/src/main/res/values-zh-rCN/strings.xml](../RestoreMode/diplay/common/src/main/res/values-zh-rCN/strings.xml)<br>[common/src/main/res/values-zh-rTW/strings.xml](../RestoreMode/diplay/common/src/main/res/values-zh-rTW/strings.xml) | Семь локализаций: нейтральные тексты без BYD, достоверные подсказки аудиоканалов и жестов; сохранить resource IDs и placeholders; полный русский перевод common (§5). |

Дополнительно адаптированы шесть `common/src/main/res/values-*/strings_settings_layout.xml`
(ru, uk, es, ar, zh-rCN, zh-rTW): нейтральное сообщение о недоступности штатной
навигации без названия BYD. Они входят в указанные 41 файл.

Не импортированы из дерева common/shared/gradle baseline:
`gradle/gradle-daemon-jvm.properties`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties` (используется wrapper RestoreMode),
`shared/.gitignore` и дублирующий `shared/BYD_HUD_ICONS_NOTICE.md`.
Содержание последнего сохранено в
[assets/byd-hud-icons/NOTICE.txt](../RestoreMode/diplay/shared/src/main/assets/byd-hud-icons/NOTICE.txt),
рядом с лицензией этих assets. Удалённых Kotlin/Java/JNI-файлов в common/shared
относительно baseline при этой сверке нет.

В `docs/` сохранены THIRD_PARTY_NOTICES, исходный `UPSTREAM-README.md` и
`licenses/` (AGPL DiAuto и лицензии зависимостей). При следующем импорте сохранять
эти документы вместе с исходниками, не ограничиваться ссылками в notices.

### 10.2. Собственные файлы внутри DiPlay

Эти **9 файлов реализации** не придут из upstream; их нужно сохранить/перенести
вместе с собственными тестами. `AGENTS.md` и наш `README.md` также не заменять upstream-версией.

| Файл | Назначение |
| --- | --- |
| [common/src/main/java/com/shilapi/xcertplay/CarPlayHomeUi.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHomeUi.kt) | Контракт фабрики app-owned home и callbacks/state. |
| [common/src/main/java/com/shilapi/xcertplay/CarPlayWindowUi.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlayWindowUi.kt) | App-policy безопасных границ, единая обёртка UI/видео, потребление insets и текст ошибки auth (§5). |
| [common/src/main/java/com/shilapi/xcertplay/CarPlaySettingsAccess.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/CarPlaySettingsAccess.kt) | Java-friendly доступ настроек VoyahTune к preferences/состоянию/stop DiPlay. |
| [common/src/main/java/com/shilapi/xcertplay/VoyahCarPlayDefaults.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/VoyahCarPlayDefaults.kt) | Defaults с отдельными миграциями навигационного канала и названия кнопки автомобиля. |
| [common/src/main/java/com/shilapi/xcertplay/VoyahCarPlayIntegration.kt](../RestoreMode/diplay/common/src/main/java/com/shilapi/xcertplay/VoyahCarPlayIntegration.kt) | Регистрация Host, available, скорость, night, Siri через snapshot. |
| [shared/src/main/java/com/shilapi/xcertplay/VoyahPlatformPolicy.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/VoyahPlatformPolicy.kt) | Ограничения BYD/ADB/standaloneAppEnabled false. |
| [shared/src/main/java/com/shilapi/xcertplay/transport/VoyahVehicleSpeedSource.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/transport/VoyahVehicleSpeedSource.kt) | Буфер/единицы/передачи/возраст данных. |
| [shared/src/main/java/com/shilapi/xcertplay/hud/VoyahNavigation.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/hud/VoyahNavigation.kt) | Независимый route owner, таблица иконок, snapshot. |
| [shared/src/main/java/com/shilapi/xcertplay/media/VoyahAudioRouting.kt](../RestoreMode/diplay/shared/src/main/java/com/shilapi/xcertplay/media/VoyahAudioRouting.kt) | OEM streams и критерий accepted. |

Собственные ресурсы локализации:
[values/strings_voyah_ui.xml](../RestoreMode/diplay/common/src/main/res/values/strings_voyah_ui.xml)
и [values-ru/strings_voyah_ui.xml](../RestoreMode/diplay/common/src/main/res/values-ru/strings_voyah_ui.xml).
Сохранять обе версии при замене upstream.
Собственный `common/src/test/java/com/shilapi/xcertplay/VoyahEmbeddedEntryPointsTest.kt`
проверяет setup-навигацию, блокировку standalone updater и Bluetooth extra.

Собственные тесты common: `VoyahSessionTransferTest`, `VoyahCarPlayIntegrationTest`,
`VoyahAudioDefaultsTest`, `VoyahCarBrandingTest`, `CarPlayAutoConnectOnOpenTest`, `CarPlaySettingsAccessTest`, `CarPlayWindowUiTest`.
Shared: `VoyahNavigationTest`, `VoyahAudioRoutingTest`. Они перечислены с путями в §12.

### 10.3. Код вне vendored дерева

Замена только `RestoreMode/diplay` не восстанавливает перечисленные ниже точки.
Сохранить их в checkout и заново связать с новой версией библиотеки.

| Файл/группа | Контракт |
| --- | --- |
| [RestoreMode/settings.gradle.kts](../RestoreMode/settings.gradle.kts)<br>[RestoreMode/app/build.gradle.kts](../RestoreMode/app/build.gradle.kts)<br>[RestoreMode/gradle/libs.versions.toml](../RestoreMode/gradle/libs.versions.toml)<br>[RestoreMode/gradle/wrapper/gradle-wrapper.properties](../RestoreMode/gradle/wrapper/gradle-wrapper.properties) | Модули, зависимости, AGP/Gradle, assets и ABI (§3). |
| [RestoreMode/app/src/main/AndroidManifest.xml](../RestoreMode/app/src/main/AndroidManifest.xml)<br>[Native/app/src/main/AndroidManifest.xml](../Native/app/src/main/AndroidManifest.xml) | Overrides компонентов/launcher/boot и signature permission (§3). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/VoyahApplication.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/VoyahApplication.java) | Установка bridge, home factory и window policy вне :ota (§2). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayWindowPolicy.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayWindowPolicy.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlaySafeArea.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlaySafeArea.java) | Физические OEM-отступы, вычитание смещения окна, отсутствие резерва дока в виджетах (§5). |
| [Packaging/od/inject/launcherdock.js](../Packaging/od/inject/launcherdock.js) | Две записи одного APK, ActivityInfo label/icon, явный компонент и сохранение экрана All Apps (§4). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayLauncherActivity.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayLauncherActivity.java)<br>[SharedAndroid/src/main/java/ru/big/town/common/CarPlayTarget.java](../SharedAndroid/src/main/java/ru/big/town/common/CarPlayTarget.java) | Адресный launcher и стабильный component ID (§4). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/ui/dialogs/SettingsAppPicker.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/ui/dialogs/SettingsAppPicker.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/main/MainSettingsFragment.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/main/MainSettingsFragment.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/dashboard/tiles/TileOrderStore.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/dashboard/tiles/TileOrderStore.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/dashboard/DashboardActivity.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/dashboard/DashboardActivity.java)<br>[RestoreMode/app/src/main/res/layout/settings_main_carplay_widget.xml](../RestoreMode/app/src/main/res/layout/settings_main_carplay_widget.xml) | Отдельная карточка добавления CarPlay; общий picker, имя/иконка, размеры, 1×1 и embedded (§4). |
| [Native/app/src/main/java/ru/big/town/anative/AppDisplayLauncher.java](../Native/app/src/main/java/ru/big/town/anative/AppDisplayLauncher.java)<br>[Native/app/src/main/java/ru/big/town/anative/AppDisplayTaskPlan.java](../Native/app/src/main/java/ru/big/town/anative/AppDisplayTaskPlan.java)<br>[Native/app/src/main/java/ru/big/town/anative/SetModesService.java](../Native/app/src/main/java/ru/big/town/anative/SetModesService.java) | Запуск и фильтрация tasks, перенос/cleanup дисплеев, сообщения 34/120/121/123, destroy контроллера (§4/6). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayWidgetPlaceholder.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayWidgetPlaceholder.java)<br>[RestoreMode/app/src/main/res/layout/tile_embedded_app.xml](../RestoreMode/app/src/main/res/layout/tile_embedded_app.xml) | Чёрный фон с центральной кнопкой CarPlay в неактивном виджете, 1×1 ярлык, скрытие при старте проекции (§4). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayHomeScreen.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayHomeScreen.java)<br>[RestoreMode/app/src/main/res/drawable/carplay_home_wireless.xml](../RestoreMode/app/src/main/res/drawable/carplay_home_wireless.xml)<br>[RestoreMode/app/src/main/res/drawable/carplay_home_usb.xml](../RestoreMode/app/src/main/res/drawable/carplay_home_usb.xml)<br>[RestoreMode/app/src/main/res/values/carplay_home.xml](../RestoreMode/app/src/main/res/values/carplay_home.xml)<br>[RestoreMode/app/src/main/res/drawable-nodpi/carplay_home_logo.png](../RestoreMode/app/src/main/res/drawable-nodpi/carplay_home_logo.png) | Home в дизайн-системе; собственные иконки и строки (§5). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/carplay/CarPlaySettingsFragment.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/carplay/CarPlaySettingsFragment.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/core/SettingsSection.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/core/SettingsSection.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/core/SettingsSectionLayouts.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/core/SettingsSectionLayouts.java)<br>[RestoreMode/app/src/main/res/layout/settings_carplay.xml](../RestoreMode/app/src/main/res/layout/settings_carplay.xml)<br>[RestoreMode/app/src/main/res/layout/view_settings_navigation_rail.xml](../RestoreMode/app/src/main/res/layout/view_settings_navigation_rail.xml) | Раздел 9, navigation, общий переключатель и живой статус (§5). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayVehicleBridge.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayVehicleBridge.java)<br>[SharedAndroid/src/main/java/ru/big/town/common/CarPlayVehicleProtocol.java](../SharedAndroid/src/main/java/ru/big/town/common/CarPlayVehicleProtocol.java)<br>[Native/app/src/main/java/ru/big/town/anative/CarPlayVehicleController.java](../Native/app/src/main/java/ru/big/town/anative/CarPlayVehicleController.java) | Двунаправленный IPC, owner/generation/подписки (§6/8). |
| [Native/app/src/main/java/ru/big/town/anative/CanBusEvent.java](../Native/app/src/main/java/ru/big/town/anative/CanBusEvent.java)<br>[Native/app/src/main/java/ru/big/town/anative/CanBusEventRouter.java](../Native/app/src/main/java/ru/big/town/anative/CanBusEventRouter.java)<br>[Native/app/src/main/java/ru/big/town/anative/CanBusEventHub.java](../Native/app/src/main/java/ru/big/town/anative/CanBusEventHub.java) | Скорость, interests, OEM callback/getter в общем CAN hub (§6). |
| [RestoreMode/app/src/main/java/ru/big/town/restoremode/voice/commands/VoiceSteeringPolicy.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/voice/commands/VoiceSteeringPolicy.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/voice/VoiceSettingsPage.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/voice/VoiceSettingsPage.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/integration/config/SplitConfigSync.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/integration/config/SplitConfigSync.java)<br>[RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/steering/SteeringSettingsFragment.java](../RestoreMode/app/src/main/java/ru/big/town/restoremode/settings/sections/steering/SteeringSettingsFragment.java)<br>[RestoreMode/app/src/main/res/layout/settings_steering_reserved.xml](../RestoreMode/app/src/main/res/layout/settings_steering_reserved.xml)<br>[Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java](../Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java) | Выбор нажатия Siri, пояснение, экспорт действия и broadcast (§7). |
| [Native/app/src/main/java/ru/big/town/anative/CarPlayNavigationLease.java](../Native/app/src/main/java/ru/big/town/anative/CarPlayNavigationLease.java)<br>[Native/app/src/main/java/ru/big/town/anative/CarPlayClusterNavigation.java](../Native/app/src/main/java/ru/big/town/anative/CarPlayClusterNavigation.java) | Срок действия snapshot и OEM writer (§8). |

## 11. Восстановление после полной замены upstream

### 11.1. Что сохранить до замены

Зафиксировать точный исходный commit и новый целевой commit. Сохранить текущее
рабочее дерево адаптаций **включая untracked-файлы** из §10; один `git diff` их не
содержит. Не опираться только на названия Voyah*: UI facade и изменения штатных
файлов имеют другие имена. Не считать старый опубликованный commit заменой текущей
реализации: сверить staged/unstaged/untracked отдельно. Создание коммита, push и
публикация по-прежнему требуют явного поручения пользователя.

Получить три независимых дерева: чистый старый upstream (commit §1), наша текущая
адаптация, чистый новый upstream. Рабочие сравнения держать в `tmp/`, не в каталогах
приложений. Старый baseline нужен именно чистый: экспериментальный checkout может
содержать дополнительные изменения, не вошедшие в продукт.

Authentication assets и бинарную иконку сохранить отдельно от текстового diff.
Для identity сохранить доступ к доверенному артефакту и возможность проверить
соответствие пары, не добавляя секреты в Git. Для иконки достаточно ресурса из
репозитория или указанного commit VoyahPlay. Локальные абсолютные пути на машине
разработчика не являются частью переносимой процедуры.

### 11.2. Порядок восстановления

1. **База и сборка.** Импортировать новые common/shared и зависимости. Учитывать
   удалённые/переименованные upstream-файлы; не накладывать новую версию поверх старой
   вслепую. Сохранить local AGENTS/README/лицензии. Восстановить модули и manifest
   overrides §3. Проверить новые receivers/services/OEM/ADB entry points.
2. **Ограничения платформы и identity.** Вернуть policy и все guards §9, defaults,
   отдельную миграцию аудиоканала, bootstrap assets. Не включать BYD global flag,
   boot receiver и не стирать preferences/noBackupFilesDir ради прохождения запуска.
3. **Сессия и дисплеи.** Найти в новой версии аналоги controller/sink owner и фоновой
   сессии. Вернуть сервисные start/stop callbacks, onTaskRemoved, ownerDisplayId и
   передачу displayId; при смене дисплея нужен stop → новый controller с новым размером.
   Проверить компонентные идентификаторы/affinity против CarPlayTarget/Native.
4. **UI и preferences.** Подключить home factory только к home, обновление статуса,
   одноразовый запрос автоподключения и Java facade настроек. Восстановить app-owned
   экран, раздел 9, связь Application. Проверить, что explicit page=home/settings
   всегда доступен без автозапуска и таймера.
5. **Автомобильные данные.** Подключить Host, IPC и speed provider, включая режим без
   GPS, SWReason в активную сессию. Проверить единицы, timestamp, null/−1 и очистку.
6. **Siri.** Сохранить публикацию противоположного нажатия, защиту broadcast и запрет
   конкурирующих upstream Siri-key handlers. Не заменять сохранённые steering actions.
7. **Манёвры.** Подключить route frame новой версии к owner-bound state, события
   start/end/close; восстановить snapshot → message 123 → lease → OEM writer.
   Сверить формат iAP2 route и enum maneuver types с новой версией.
8. **Аудио/видео.** Найти новое место создания AudioTrack; восстановить logical-channel
   routing/explicit overrides/validation/fallback/preview. Сохранить независимые
   renderer. Проверить штатный TextureView picture path, не обходить ограничения
   SurfaceView, не менять кодеки без необходимости.
9. **Контроль полноты.** Для каждой строки §10 отметить новую точку реализации и тест
   либо явно согласованное удаление функции. Конфликт при merge разрешается по
   контракту поведения, не безусловным ours/theirs. Отсутствие конфликта не доказывает,
   что новый upstream вообще вызывает сохранённый adapter.
10. **Проверка и фиксация baseline.** Выполнить проверки затронутых контрактов §12,
    описать непроверенное на ГУ, обновить commit/версии upstream и карту §10.
    Новые APK/релиз оформляются по [правилам выпуска](releasing.md); не подменять
    внутреннюю версию VoyahTune версией DiPlay.

Если upstream полностью поменял архитектуру, заново реализовать требуемые интерфейсы
из §§2–9 на его новых extension points. Копирование старой Activity целиком вместе
со старым протокольным стеком не является обновлением: получится два несовместимых
владельца сессии. На выходе должен остаться один движок, один набор preferences и
одна подписка на автомобиль на сессию.

## 12. Критерии восстановления и проверки

Для чистой правки этой документации сборка не нужна. После обновления кода выбирать
минимальный набор по затронутым строкам; расширенный набор — только по явному запросу.
При полной замене DiPlay затронуты все библиотечные контракты ниже. Успех JVM-тестов
и эмулятора не подтверждает OEM Binder, независимую громкость или связь с iPhone.

| Контракт | Существующие целевые тесты |
| --- | --- |
| Два значка OD и безопасная область | `Packaging/tests/test_carplay_allapps.js`; app `CarPlaySafeAreaTest`; common `CarPlayWindowUiTest`, `CarPlayHostVideoSurfaceTest` |
| Task/component/display | Native `CarPlayTargetTest`, `AppDisplayTaskPlanTest`; common `VoyahSessionTransferTest`, `SmoothVideoHostLifecycleTest`, `LauncherIntentTest` |
| Автоподключение и настройки | common `CarPlayAutoConnectOnOpenTest`, `CarPlaySettingsAccessTest`, `DefaultConnectionModeTest`, `VoyahEmbeddedEntryPointsTest`, `PhoneBluetoothReceiverTest` |
| Скорость, gear, SWReason | common `VoyahCarPlayIntegrationTest`; Native `CanBusEventRouterTest` |
| Siri | app `VoiceSteeringPolicyTest`; common `WheelSiriWindowTest` (включает Voyah guard) |
| Манёвры | shared `hud.VoyahNavigationTest`; Native `CarPlayNavigationLeaseTest` |
| Аудио | shared `media.VoyahAudioRoutingTest`, `media.LegacyAudioFallbackTest`, `airplay.CarPlayAudioStreamIsolationTest`; common `VoyahAudioDefaultsTest` |

Пути тестов: common — `RestoreMode/diplay/common/src/test/java/com/shilapi/xcertplay/`,
shared — `RestoreMode/diplay/shared/src/test/java/com/shilapi/xcertplay/`,
Native — `Native/app/src/test/java/ru/big/town/anative/`,
app — `RestoreMode/app/src/test/java/ru/big/town/restoremode/voice/commands/`.
Это проверки отдельных контрактов, не полное покрытие: реальный выбор дисплея,
динамический OEM ClassLoader, Binder-доступ и готовый UI требуют отдельной проверки.

Примеры команд из корня; настроить JAVA_HOME/SDK/NDK и внешний DIPLAY_AUTH_ASSETS_DIR.
Не копировать локальные пути Gradle cache/SDK из машины автора.

```sh
voyah_release_version="$(python3 -c 'import json; print(json.load(open("versions.json"))["voyahtune"])')-od"

RestoreMode/gradlew -p RestoreMode :app:assembleDebug \
  -PvoyahReleaseVersion="$voyah_release_version" -PvoyahInfrastructure=od

RestoreMode/gradlew -p RestoreMode :diplay-common:testDebugUnitTest \
  --tests com.shilapi.xcertplay.VoyahSessionTransferTest \
  --tests com.shilapi.xcertplay.VoyahCarPlayIntegrationTest \
  --tests com.shilapi.xcertplay.VoyahAudioDefaultsTest \
  --tests com.shilapi.xcertplay.CarPlayAutoConnectOnOpenTest \
  --tests com.shilapi.xcertplay.CarPlaySettingsAccessTest \
  --tests com.shilapi.xcertplay.DefaultConnectionModeTest \
  --tests com.shilapi.xcertplay.LauncherIntentTest \
  --tests com.shilapi.xcertplay.WheelSiriWindowTest

RestoreMode/gradlew -p RestoreMode :diplay-shared:testDebugUnitTest \
  --tests com.shilapi.xcertplay.hud.VoyahNavigationTest \
  --tests com.shilapi.xcertplay.media.VoyahAudioRoutingTest \
  --tests com.shilapi.xcertplay.media.LegacyAudioFallbackTest \
  --tests com.shilapi.xcertplay.airplay.CarPlayAudioStreamIsolationTest

RestoreMode/gradlew -p RestoreMode :app:testDebugUnitTest \
  --tests ru.big.town.restoremode.voice.commands.VoiceSteeringPolicyTest

Native/gradlew -p Native :app:assembleDebug :app:testDebugUnitTest \
  --tests ru.big.town.anative.CarPlayTargetTest \
  --tests ru.big.town.anative.AppDisplayTaskPlanTest \
  --tests ru.big.town.anative.CanBusEventRouterTest \
  --tests ru.big.town.anative.CarPlayNavigationLeaseTest \
  -PvoyahReleaseVersion="$voyah_release_version" -PvoyahInfrastructure=od
```

При изменениях только RestoreMode/DiPlay сборка Native нужна лишь если затронуты
его исходники/контракты. При изменении сообщений 120–123, единиц или owner semantics
проверять обе стороны. Проверить merged manifest (компоненты, boot, permission,
process/affinity), итоговые ABI/подписи/наличие assets, не выводя содержимое identity.

Контроль на устройстве после полного обновления:

| Сценарий | Требуемый результат |
| --- | --- |
| Чистые данные и обновление поверх | Bootstrap работает; старые identity/preferences не потеряны; явные audio channels не сброшены. |
| Launcher / 1×1 / 8×5 / 12×5 | Правильный дисплей, один ряд карточек, работающие касания/resize; 1×1 не создаёт Surface. |
| Виджет ↔ Activity во время сессии | Старый UI уничтожен, iPhone переподключён, новый размер согласован; старый controller закрыт до нового, сервис/CAN-подписка перезапущены; неактивный виджет чёрный с иконкой, Dashboard не закрывается. |
| Auto on/off, нет телефона, Back, home/settings | Нет нежелательных циклов запуска; все три UI показывают одно значение, таймера нет. |
| Раздел CarPlay | Живое состояние; Disconnect завершает сессию, opening home не делает reconnect. |
| Скорость/передача, отключённый GPS | km/h→m/s, корректные P/R/N/D, stale/unknown не передаются как действительные данные. |
| SWReason | Только известные 2/3/4 тёмные, 0/1 светлые; восстановление темы после reconnect. |
| Siri | Оба short/long варианта, отключённая опция, фон и виджет; ровно один вызов, сохранённые назначения возвращаются. |
| Аудио | Музыка/подсказки/телефон/Siri одновременно и отдельно, независимая регулировка, проверка фактических streams и fallback. |
| Навигация | Иконка/метры/дорога; end route, disconnect, потеря bridge, смена owner, timeout; отсутствие старой стрелки. |
| Фон/сон/пробуждение/потеря Native | Нет зависшей подписки/устаревших данных, есть переподключение или понятное неактивное состояние. |

Обновление движка поставляется через новый RestoreMode APK. Для изменения Native
IPC/CAN/cluster/запуска нужен согласованный Native APK. **Установщик/root-служба
не требуют изменения только из-за обновления DiPlay**; пересборка установщика нужна
при изменении его исполняемого кода, recipe или встроенных ресурсов по общим правилам.
Релизы Payload/Installers, установка на автомобиль, S3, коммит и push не следуют
автоматически из задачи обновления библиотеки.

## 13. Подтверждённое состояние и ограничения

Зафиксированная карта §10 сверена с чистым upstream commit по текущим исходникам.

Обновление до **0.2.15** выполнено трёхсторонним переносом с 0.2.14, без замены
собственных адаптеров. Собран debug RestoreMode **4.0.0-od**; APK не устанавливался.
Пройдены целевые тесты сессии/смены дисплея, автоподключения, скорости/темы, Siri,
GPS defaults, локализации и маршрутизации. Дополнительный набор нового поведения:
19 common-тестов (appearance, Wi-Fi choices, запреты standalone entry points) и
73 shared-теста (навигация Voyah, audio routing/isolation/rebuffer, video operating rate,
P2P recovery и USB queues) — без ошибок. Проверены merged manifest, ABI arm64-v8a/x86_64
и наличие внешних runtime identity assets без раскрытия содержимого. Версии в
`versions.json` не менялись. Связь с iPhone, Bluetooth audio, OEM-громкость и приборка
после этого обновления на автомобиле не проверялись.

Исторические проверки до обновления:
В предыдущих шагах скомпилированы Native/RestoreMode и пройдены целевые проверки
скорости/темы, Siri, аудио, манёвров, сессии и автоподключения. Последний UI-раздел
проверен компиляцией и 8 тестами CarPlayAutoConnectOnOpenTest/CarPlaySettingsAccessTest.
Debug RestoreMode **4.0.0-od** установлен и DiPlayActivity открыт на Android-эмуляторе;
визуальную проверку пользователь решил выполнить сам. Это не подтверждение качества
всех размеров UI или реального подключения телефона.

Ранее пользователь подтвердил беспроводное подключение экспериментальной отдельной
сборки. Новые автомобильные интеграции, OEM AudioTrack routes и приборка на машине
пока не подтверждены. Заряд/запас хода, собственная панель управления автомобилем
в интерфейсе iPhone CarPlay, OEM-арбитраж навигации и автоматическое обновление identity
не входят в реализованный контракт. Не добавлять их при обновлении upstream как
неявное «восстановление» существующих функций.
