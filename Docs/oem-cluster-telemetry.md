# Данные автомобильных карточек приборной панели

Справочник по локальному исследованию от 3 октября 2026 года для нового виджета
VoyahTune. Основная цель — официальные SE и Sport+ 2025–2026, Android 11.
Наличие метода SDK отдельно от его реализации и фактического получения данных
на автомобиле. Здесь описана возможность чтения, а не готовая функция VoyahTune.

## Исследованные материалы

Версии ниже прочитаны непосредственно из APK через `aapt dump badging`.
Названия файлов с обозначениями комплектаций — метки локального архива,
а не доказательство установленной прошивки или совместимости.

| APK | Package / версия | SHA-256 |
| --- | --- | --- |
| `InstrumentScreen.apk` | `com.qinggan.instrumentcard`, 1.0 (1) | `e4aa2da61c9473571f256da5bc1bd9f584fbfb3227260df26438e0f828e845cd` |
| `Cluster.apk` | `com.qinggan.cluster`, 1.2.0 (7) | `1a394bf793a467dd78b95ca1ed2004df62c280df4b58b043629257889d8e4f38` |
| `CanBusService.apk` | `com.qinggan.canbus.service`, 1.2.0 (7) | `96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c` |
| `CarSignalService.apk` | `com.qinggan.carsignal.service`, versionName/versionCode отсутствуют в выводе aapt | `2bb3e7df1cd672578deeb0e106da8a864adff7d9431784a9a27cbcb8e189f887` |
| `VehicleSettings_Sport+_13.1.apk` | `com.qinggan.app.vehiclesetting`, 1.0 (1) | `b61e44456bdf15039fcde5c2ab070e640eef37a788d0e11b2520b2a90abf47b6` |
| `VehicleSettings_SportEdition_2025_rc5.1.apk` | `com.qinggan.app.vehiclesetting`, 1.0 (1) | `5a99fa2a78da3d303bba1bbe46adc201095f2fd9e778c0bcde6291e920a3ed33` |

Cluster и InstrumentScreen сохранены в сессии 27 сентября с H97X,
IPK firmware `H97XSA8155-DAILY-20251010221730-USR`. При чтении машины
3 октября хеши всех четырёх установленных служб в первых строках таблицы
совпали с локальными APK; `dumpsys qg.canbus` показал тип 13402 (H97X).
Android fingerprint содержит `PRJ_H97x_Platform_8155-650`, Android 11.
Это привязывает исследование к данной машине; поддержку остальных
SE/Sport+ нужно подтверждать отдельно.

Использованы сохранённые декомпиляции:

- `tmp/theme-session-20260927/instrumentscreen/` и `cluster/`;
- `tmp/CanBusService_jadx/` (Java), `tmp/canbus_jadx/` (в том числе manifest);
- `tmp/carsignal_jadx/`;
- `tmp/car_apks/decompiled/VehicleSettings_Sport+_13.1/` и
  `VehicleSettings_SportEdition_2025_rc5.1/`.

Эти каталоги не поставляются с репозиторием. Далее пути OEM-классов указаны
относительно `sources/com/qinggan/` соответствующей декомпиляции. JADX может
восстановить методы неполностью; например, `EnergyTripView.notifyData()` из
Sport+ не восстановлен. Его преобразования времени не считаются доказанными.

## Цепочка от знакомой медиакарточки

1. `cluster/display/fragments/ClusterMediaFragment.java:94` создаёт
   `Cluster-Media-Display` и связывает его с категорией `qg.car.cluster.MEDIA`.
2. `instrumentcard/ScreenActivity.java` создаёт медийный `PanelView`.
   В manifest он объявлен с `android.intent.action.MEDIA_SCREEN`.
   Прикладные панели этого APK — медиа, телефон/контакты и темы.
3. VoyahTune использует именно этот дисплей:
   [ClusterMediaHostActivity](../Native/app/src/main/java/ru/big/town/anative/ClusterMediaHostActivity.java).
   Геометрия внедрения 66/200/574/464 при DPI 160 относится к медиакарточке
   простой темы приборки, а не к сетке главного экрана RestoreMode.
4. `cluster/service/InstrumentClusterService.java:570` и
   `service/protocol/voyah/VoyahInstrumentCluster.java:336` обмениваются
   сообщениями с приборкой через `libqg_cluster.so`. В исследованной Java-части
   видны переключение отображаемых областей, медиа/телефон/навигация, настройки,
   тема, единицы и версия IPK. Готового API полного состояния перечисленных
   автомобильных карточек не найдено.

Следовательно, доступ к `Cluster-Media-Display` сам по себе не предоставляет
показания соседних карточек. В этих двух APK не найдены прикладная реализация
карточек расхода/давления и их родная анимация энергии. Предположение о рендере
на стороне IPK согласуется с разделением Android-панелей и сокетного протокола.
Живое исследование ниже подтвердило доступность QNX Neutrino за адресом
приборки; точный процесс рендера и расположение его графических ресурсов
ещё не установлены.

Отдельный доступный путь к части тех же автомобильных показателей — OEM
CanBusService. `canbus/service/CanBusComponentFactory.java:33` выбирает
`DongfengH97CCanBusComponentImpl` для типов 134, H97Y=13401 и H97X=13402.
Прямую связь каждого Android-поля с изображением на приборке ещё надо сверить
по одновременным показаниям; совпадение названий этого не доказывает.

## Доступ для Native

CanBusService экспортирован с action `com.qinggan.canbus.CanBusService`,
package `com.qinggan.canbus.service`; `onBind()` возвращает Binder
`com.qinggan.canbus.ICanBusService`. В исследованных getters статистики нет
проверки WRITE_CANBUS; это отличается от некоторых команд записи. Чтение через
`su 0 service call` на подключённой машине выполнено. Чтение новых полей из
процесса Native отдельно не проверялось; существующее соединение уже используется
для других событий. Root-ответ getter не заменяет проверку callback в Native.

В VoyahTune уже есть единое соединение
[CanBusEventHub](../Native/app/src/main/java/ru/big/town/anative/CanBusEventHub.java).
Оно использует `addCallback` (28), `removeCallback` (29), обрабатывает двери,
КПП, освещение, наружную температуру и VehicleState. Неизвестные callback-коды
сейчас намеренно игнорируются. Для телеметрии надо расширить эту цепочку,
а не создавать вторую подписку или второй reader `/dev/cis_can`.

| Данные | Getter, transaction | Callback, transaction |
| --- | --- | --- |
| Расход для графика | `getEnergyConsumptionPercent`, 79 | `onEnergyConsumptionPercentChanged`, 59 |
| Средние значения и поездки | `getEnergyConsumptionInfo`, 80 | `onEnergyConsumptionInfoChanged`, 60 |
| Давление | `getTPMSInfo`, 70 | `onTPMSInfoChange`, 45 |
| Одометр/запас хода | `getOdometer`, 1 | `onOdometerChanged`, 25 |
| Скорость | `getVehicleSpeed`, 26 | `onVehicleSpeedChanged`, 16 |
| Обороты — кандидат | `getEngineSpeed`, 27 | `onEngineSpeedChanged`, 13 |
| Напряжение — кандидат | `getBatteryState`, 19 | `onBatteryStateChanged`, 26 |

Номера зафиксированы по `canbus/ICanBusService.java` и
`ICanBusServiceCallback.java` версии выше. Это два разных направления Binder;
совпадающие номера в колонках не означают одинаковую операцию. Форматы Parcel
нужно сверить по установленной версии перед реализацией.

`getCanRawData()` не даёт произвольный доступ ко всем CAN-кадрам: в исследованной
ветке H97C `onCanRawData()` вызывается для 0x21A и 0x2FE. Наличие этого getter
не доказывает доступность через него мощности, температур шин или оборотов.

## Уточнение на машине с выбранной карточкой «Поток энергии»

Пользователь выбрал карточку вручную. Через ADB выполнено только чтение:
списки процессов/пакетов/слоёв, хеши, диагностические снимки, известные getters
и уже доступные файлы QNX. Установка APK, перехваты процессов, команды управления
CAN, переключение тем и карточек не выполнялись.

- `screencap -d 4` для физического выхода приборки вернул чёрный кадр 1920×720,
  хотя пользователь сообщил об открытой карточке. Это согласуется с рендером
  вне Android-композиции; один чёрный кадр сам по себе не доказывает архитектуру.
- Слой `cluster_sync_display` — служебный `View` размером 1×1 в
  `ClusterActivity.showPoint()`, а не поток изображения карточки.
- В Android уже смонтированы NFS-ресурсы
  `172.16.104.41:/var → /mnt/qnx/var` и
  `172.16.104.41:/update → /mnt/qnx/update`. Новые mount не создавались.
  Сокет Android Cluster соединён с этим же адресом на порту 80; номер порта
  не означает HTTP: Java/JNI использует собственный протокол.
- Прочитан `/mnt/qnx/var/pps/icm/rx`: есть скорость, передача, её валидность,
  READY, двери и другие статусы; в снятом объекте нет мощности, направлений
  между двигателем/генератором/батареей/колёсами, температур шин или оборотов.
- `/mnt/qnx/var/pps/cluster/linecfg` — конфигурация оснащения;
  `/mnt/qnx/var/data/cardinfo.txt` — последовательность числовых значений
  без доказанной расшифровки. Не использовать их как состояние энергии.
- Прочитан `pps/icm/x5b0`; `x3a9` и `x3ab` при чтении через NFS вернули I/O error.
  Имена файлов не устанавливают содержимое или возможность подписки через NFS.
- Диагностические порты доступны: SSH сообщает OpenSSH 8.5, Telnet —
  `QNX Neutrino (localhost)`. Вход root запросил пароль; пароль не передавался,
  сессия закрыта, команды внутри QNX не выполнялись.
- Скопирован для анализа `IpkUpdateService-release.apk`: штатные интерфейсы
  относятся к обновлению/передаче файлов, готового getter состояния анимации
  не обнаружено. Консольная декомпиляция завершилась с одной ошибкой.
  Упоминаемый там `/data/data/cluster_lang.zip` на машине отсутствует.

После этого пользователь исключил родную анимацию из требований. Извлечение
QNX-ресурсов и дальнейший вход в QNX не продолжаются в рамках этого этапа.
Родная картинка карточки шин также не извлечена; это отдельное ограничение.
