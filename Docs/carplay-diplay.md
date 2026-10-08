# CarPlay: интеграция DiPlay и обновление исходников

## Что включено в VoyahTune

DiPlay **0.2.14**, upstream commit **`7887bb7bf2b52258e663a2a4ea1332382ed80ad8`**.
Источник: [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).

Исходники находятся в [RestoreMode/diplay](../RestoreMode/diplay/README.md).
Это копия исходных библиотечных модулей, а не Git submodule и не загружаемый APK:

- `:diplay-common` — штатные экраны, настройки, управление сессией и Android-сервисы;
- `:diplay-shared` — AirPlay/iAP2, транспорты, MFi, аудио/видео и JNI;
- upstream-модуль `mobile` не подключён. Всё включается в APK **RestoreMode**.

Вторая иконка **VoyahTune CarPlay** в лаунчере — дополнительная Activity того же
пакета `ru.big.town.restoremode`. Отдельного установленного приложения DiPlay
для этой реализации не требуется. Сессия работает в процессе RestoreMode;
выделенного процесса или нового IPC-сервиса движка CarPlay здесь нет.

Подключение модулей и каталога зависимостей `diplayLibs` задано в
[settings.gradle.kts](../RestoreMode/settings.gradle.kts), зависимость приложения
и импорт authentication assets — в [app/build.gradle.kts](../RestoreMode/app/build.gradle.kts).
Версия APK определяется версией VoyahTune; версия импортированного DiPlay учитывается отдельно.

## Запуск, виджет и сессия

Выбор: **Главный экран → Виджеты приложений → VoyahTune CarPlay**.
Размер **1×1** открывает Activity; **8×5–12×5** встраивают штатные экраны через
существующий VirtualDisplay Native. **12×5** занимает всю область сетки рядом
со штатным доком: сетка содержит 12 колонок и 5 рядов. Это не скрывает док и
не означает заполнение всей физической панели. Для 1×1 автозапуск при отрисовке
главного экрана отключён.

Маршруты запуска:

1. Иконка лаунчера открывает
   [CarPlayLauncherActivity](../RestoreMode/app/src/main/java/ru/big/town/restoremode/carplay/CarPlayLauncherActivity.java).
   Она связывается с `Native.SetModesService` и отправляет существующее сообщение
   `34`: `left = CarPlayTarget.ID`, `right = ""`. Native открывает CarPlay на дисплее 0.
2. Виджет передаёт тот же идентификатор через существующий контракт embedded Surface
   и ввода. Плитка 1×1 запрашивает отдельное окно вместо создания Surface.
3. [CarPlayTarget](../SharedAndroid/src/main/java/ru/big/town/common/CarPlayTarget.java)
   задаёт специальный адрес
   `ru.big.town.restoremode/com.shilapi.xcertplay.DiPlayActivity`.
   Это идентификатор компонента, **не имя отдельного пакета**.
4. Native через `AppDisplayLauncher` завершает прежние UI-задачи CarPlay и открывает
   штатную `DiPlayActivity` на нужном дисплее. Фильтр задач выбирает только компоненты
   `com.shilapi.xcertplay.*` внутри RestoreMode, чтобы не закрыть Dashboard и настройки VoyahTune.
5. `DiPlayActivity` проверяет настройки подключения и открывает `CarPlayHostActivity`
   на своём дисплее. При существующей сессии используется штатный механизм её подхвата.

`CarPlayHostActivity` и `CarPlayBackgroundSession` сохранены без изменений относительно
зафиксированного upstream. `CarPlayBackgroundSession` — объект в памяти процесса.
Для переноса окна используется завершение Activity task без force-stop пакета.
Наша правка `DiPlaySessionService.onTaskRemoved` сохраняет сервис/сессию при таком
завершении; отключение остаётся явным действием DiPlay. Поэтому удаление UI-задачи
из недавних приложений тоже не является командой отключения CarPlay.

Готовый upstream `MapEmbedService` предназначен для дополнительного потока карты;
он не используется как способ встраивания полного экрана в этой реализации.

## Изменения относительно upstream

Пути в таблице относятся к `RestoreMode/diplay`; Kotlin-файлы расположены в
`<модуль>/src/main/java/com/shilapi/xcertplay/`.

| Файл | Изменение, которое нужно сохранить при обновлении |
| --- | --- |
| `common/build.gradle.kts`, `shared/build.gradle` | Каталог `diplayLibs`, ссылка `:diplay-shared` вместо upstream `:shared` |
| `common/…/DiPlayActivity.kt` | Вызов начальных настроек VoyahTune; передача текущего `launchDisplayId` при открытии проекции |
| `common/…/DiPlayBootstrap.kt` | Вызов начальных настроек VoyahTune перед штатной загрузкой authentication assets |
| `common/…/DiPlaySessionService.kt` | Сохранение сессии при удалении UI task на Voyah |
| `common/…/VoyahCarPlayDefaults.kt` — добавлен | Однократно задаёт OEM label `VoyahTune`, audio focus, `STREAM_MUSIC` для подсказок, отключает HEVC. Маркер — `voyah_carplay.initialized` |
| `shared/…/VoyahPlatformPolicy.kt` — добавлен | Отключает BYD-интеграции и локальный ADB |
| `shared/…/adb/LocalAdb.kt` | Возвращает `UNSUPPORTED` до открытия соединения при отключённом ADB |
| `shared/…/hud/BydOutputSettings.kt` | Не позволяет включить BYD outputs через сохранённые настройки |
| `shared/…/hud/BydNavigationOutputs.kt` | Не запускает отключённые OEM outputs и их автомобильную очистку |
| `common/src/test/…/VoyahSessionTransferTest.kt` — добавлен | Проверяет сохранение сервиса после `onTaskRemoved` |

Протоколы AirPlay/iAP2/MFi, кодеки и JNI не переписывались. Исходные лицензии,
[LICENSE](../RestoreMode/diplay/LICENSE) и
[THIRD_PARTY_NOTICES](../RestoreMode/diplay/docs/THIRD_PARTY_NOTICES.md), сохранены.
Отключённый LocalAdb означает также недоступность зависящих от него функций hotspot/BYD,
даже если соответствующие пункты остаются в штатных экранах.

Изменения на стороне VoyahTune:

- [Манифест RestoreMode](../RestoreMode/app/src/main/AndroidManifest.xml): отдельная
  launcher Activity, удаление launcher-фильтра штатной `DiPlayActivity`, отдельный
  task affinity и `singleTask` для неё; отключение `BootReceiver` DiPlay, удаление
  `AdbClusterActivity` и `MyCarAppService`. Остальные штатные компоненты приходят из библиотек.
- `CarPlayLauncherActivity`, общий `CarPlayTarget` и `ic_carplay.xml` — точка входа и идентификация.
- `SettingsAppPicker`, `MainSettingsFragment` — адресный выбор CarPlay и пресеты размеров;
  `TileOrderStore` — проверка существования пакета для специального идентификатора;
  `DashboardActivity` — отображение имени/иконки, маршрут 1×1 и большой встроенный экран.
- [AppDisplayLauncher](../Native/app/src/main/java/ru/big/town/anative/AppDisplayLauncher.java)
  и [SetModesService](../Native/app/src/main/java/ru/big/town/anative/SetModesService.java) —
  адресный запуск и фильтрация задач CarPlay. При первом внедрении нужны оба обновлённых APK.

## Зависимости и authentication assets

RestoreMode использует **AGP 9.3.0 / Gradle 9.5.0 / compileSdk 37**; итоговые
**minSdk 30 / targetSdk 35** сохранены. У библиотек DiPlay свои параметры сборки;
они не повышают targetSdk конечного приложения. Каталог upstream хранится в
[diplay/gradle/libs.versions.toml](../RestoreMode/diplay/gradle/libs.versions.toml).
Например, `androidx.core:core:1.19.0` требует compileSdk 37 и AGP ≥9.1.
Локальная сборка проверена с JDK 21. Нужны NDK **28.2.13676358** для DiPlay и
**27.0.12077973** для существующей голосовой части RestoreMode.

Для LOCAL MFi перед сборкой RestoreMode нужно задать `DIPLAY_AUTH_ASSETS_DIR` —
абсолютный путь к локальной папке следующего содержания:

```text
offline-mfi/
  identity.pk8
  certificate.p7b
```

Эти файлы включаются в APK как assets; в исходники и Git их не добавляют.
Сборка добавляет всю указанную папку, поэтому в ней должны быть только выбранные
runtime assets. Если переменная задана, Gradle проверяет наличие обоих файлов;
он не проверяет криптографическое соответствие пары. Если переменная отсутствует,
сборка допустима, но на чистой установке LOCAL MFi без identity не заработает.
Значение должно быть доступно именно процессу Gradle, в том числе при запуске через сборщик релиза.

Для исходного эксперимента использовалась пара из официального APK DiPlay 0.2.14;
подпись APK, SHA-256 и соответствие публичного ключа сертификату были проверены.
Обновление исходников **не скачивает и не обновляет identity автоматически**.

`DiPlayBootstrap` копирует пару из assets в `noBackupFilesDir/offline-mfi` только
при отсутствии этой директории. Замена assets в новом APK не обновит уже сохранённую
пару. При необходимости смены identity требуется отдельная миграция с проверкой
новой пары и восстановления после ошибки; удалять все данные RestoreMode для этого
не следует. Настройки старого отдельного тестового APK в RestoreMode не мигрируются.

## Порядок обновления DiPlay

1. Зафиксировать выбранные тег и commit нового upstream. Взять чистые исходники
   текущего commit, новой версии и нашу копию для трёхстороннего сравнения.
2. Сравнить `common`, `shared`, каталог зависимостей и лицензии. Перенести изменения
   с сохранением правок из таблицы. Не заменять целиком каталог поверх наших файлов:
   это потеряет адаптацию; простое копирование новых файлов также оставит удалённые upstream файлы.
3. Проверить изменения в `DiPlayActivity`, `CarPlayHostActivity`,
   `CarPlayBackgroundSession`, `DiPlaySessionService`, `AndroidMediaSink` и запуске
   контроллера. Особое внимание — владение сессией, `onTaskRemoved`, смена Surface,
   разрешения и проверка параметров до подключения.
4. Проверить все новые OEM/ADB вызовы и компоненты манифеста: существующие guards
   не гарантируют отключения функций, добавленных upstream. Не включать BYD-службы
   и boot-автозапуск при обновлении библиотеки.
5. Согласовать SDK, AGP, Gradle, Kotlin/Compose, NDK и зависимости всего RestoreMode.
   Сохранить отдельные имена модулей и каталог `diplayLibs`. Повышение compileSdk
   само по себе не является основанием менять targetSdk приложения.
6. Проверить наличие выбранной identity и её совместимость. Смену identity и
   миграцию настроек рассматривать отдельно от обновления исходников.
7. Выполнить минимальные проверки ниже; проверить итоговый merged manifest,
   launcher-компоненты, ABI, подпись и наличие выбранных assets в APK.
8. На ГУ проверить настройку USB/wireless, 1×1, 8×5 и 12×5, касания и смену размера,
   переходы виджет ↔ Activity при активном подключении, звук/Siri/звонки,
   фон, отключение, переподключение и сон/пробуждение.
9. Обновить указанные в начале документа версию/commit и таблицу наших изменений.
   Выпускать обновление в составе VoyahTune по [правилам релиза](releasing.md),
   с версией из корневого `versions.json`. Официальный APK DiPlay не обновляет
   встроенный движок.

Сначала задать `JAVA_HOME`, SDK и `DIPLAY_AUTH_ASSETS_DIR` в окружении сборки.
Из корня репозитория версия для локальной OD-проверки получается так:

```sh
voyah_release_version="$(python3 -c 'import json; print(json.load(open("versions.json"))["voyahtune"])')-od"
```

Минимальные команды после обновления (пути `-p` относительно корня):

```sh
RestoreMode/gradlew -p RestoreMode :app:assembleDebug :app:testDebugUnitTest \
  -PvoyahReleaseVersion="$voyah_release_version" -PvoyahInfrastructure=od
RestoreMode/gradlew -p RestoreMode :diplay-common:testDebugUnitTest \
  --tests com.shilapi.xcertplay.VoyahSessionTransferTest
Native/gradlew -p Native :app:assembleDebug :app:testDebugUnitTest \
  --tests ru.big.town.anative.CarPlayTargetTest \
  --tests ru.big.town.anative.AppDisplayTaskPlanTest \
  -PvoyahReleaseVersion="$voyah_release_version" -PvoyahInfrastructure=od
```

При изменении протокола или транспорта добавить соответствующие upstream-тесты
затронутой части. Полный расширенный набор не требуется запускать для каждого
обновления автоматически. Успешные JVM-тесты не подтверждают работу переноса на OEM Android.

Обновление движка требует пересборки RestoreMode. Native пересобирается при изменении
контракта запуска/переноса; исходное внедрение требует согласованной пары Native/RestoreMode.
Installer обычно не требует пересборки, пока не меняется установка или встроенные
в него ресурсы. Сборка/публикация payload и установка на автомобиль выполняются отдельно.

## Состояние проверки

На 8 октября 2026 интегрированные RestoreMode/Native собраны; прошли 191 JVM-тест
RestoreMode, 8 целевых тестов Native и тест сохранения сервиса DiPlay на Robolectric API 30.
Проверены подписи APK, launcher-компоненты и соответствие встроенных assets выбранной паре.
Интегрированная пара ещё не установлена на ГУ. Подтверждённое ранее беспроводное
подключение отдельного тестового APK не заменяет испытаний встроенной реализации.
