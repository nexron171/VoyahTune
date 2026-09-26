# Sport+: штатное восстановление профиля движения при ACC ON

Дата: 2026-09-26. Статическое исследование, без изменения Native или команд автомобилю.

## Основной вывод

Для Sport+ / H97X предыдущее обобщение «гость получает Eco, аккаунт получает сохранённый
профиль» неполно. В изученном CanBusService H97X включён в `isH97OverSea()`:
экспортная машина при ACC OFF получает `VehicleAccountInfo=1` независимо от аккаунта.
При следующем ACC ON этот флаг выбирает принудительный Eco с EPS=2 и PROP=1.
VehicleSettings Sport+ 13.1 дополнительно исключает восстановление этих трёх полей
из аккаунтного кеша для экспортных машин.

OEM callback `onAccStateChanged(2)` остаётся обоснованным кандидатом для ранней попытки
VoyahTune: он отправляется после постановки штатного Eco в очередь. Он не подтверждает
исполнение CAN, не завершает все процессы авторизации и не исключает более поздний
сброс при событии входа/выхода из аккаунта.

## Источники и границы применимости

- VehicleSettings: `tmp/car_apks/VehicleSettings_Sport+_13.1.apk` и
  `tmp/car_apks/decompiled/VehicleSettings_Sport+_13.1/sources/`.
- OEM transport: `tmp/CanBusService.apk` и `tmp/CanBusService_jadx/sources/`.
  Это отдельный локальный образ; совпадение с CanBusService на конкретном Sport+
  не проверено. Его фабрика явно направляет H97X в изученный компонент H97C.
- Название `13.1` взято из имени входного APK. Его manifest указывает `versionName=1.0`,
  поэтому это имя не следует выдавать за независимо установленную версию прошивки.
- Привязка Sport+ 2026 к H97X соответствует существующему исследованию
  `Docs/voice-seat-commands-plan.md`. На целевой машине требуется подтвердить type ID
  и версии APK. Ни фактический аккаунт, ни конфигурация машины здесь не считывались.

SHA-256 локальных APK для воспроизводимости:

```text
VehicleSettings_Sport+_13.1.apk
b61e44456bdf15039fcde5c2ab070e640eef37a788d0e11b2520b2a90abf47b6
CanBusService.apk
96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c
```

Ниже сокращение S означает каталог sources VehicleSettings Sport+ 13.1, C —
`tmp/CanBusService_jadx/sources/`. Номера строк относятся к текущим декомпиляциям.

## 1. Почему экспортная ветка применяется к H97X

- S `com/qinggan/vehicle/VehicleConfigHelper.java:31–63`: type ID берётся из NVRAM 43;
  базовый 134 преобразуется в 13402 при соответствующем `ro.build.product` или
  `ro.build.product_3rdparty`.
- S `com/qinggan/utils/AppCommonUtils.java:104–165,185–208,259–260`:
  13402 означает H97X; `isOverSeaVehicle()` включает H97X. Здесь нет дополнительного
  условия MARKET2. Отдельная функция `is97X_OverSea_Area()` в этом выборе не используется.
- C `com/qinggan/canbus/service/CanBusComponentFactory.java:25–37`:
  134, 13401 и 13402 обслуживает `DongfengH97CCanBusComponentImpl`.
- C `com/qinggan/canbus/service/protocol/dongfeng_h97c/DongfengH97CCanBusComponentImpl.java:1848–1860`:
  `isH97X()` проверяет 13402, `isH97OverSea()` возвращает true для H97Y или H97X.

## 2. Точный выбор ветки при включении питания

В компоненте H97C метод `onBCM_PEPSChangeData`, C:4645–4768, разбирает CAN BCM_PEPS
0x2C1, поле питания — биты 0–2. Сырое значение 1 преобразуется в ACC 0, значение 2 —
в ACC 2. Обработка перехода выполняется только при отличии от `getAccStatus()`.

При переходе в ACC 0, C:4678–4689:

```text
если isH97OverSea() ИЛИ аккаунт гостевой:
    QGSettings.System[VehicleAccountInfo] = 1
иначе:
    QGSettings.System[VehicleAccountInfo] = 2
```

При переходе в ACC 2, C:4690–4744, перечитывается именно сохранённый
`VehicleAccountInfo` (default=1), а не актуальный `AccountInfoBean`:

| Флаг | DRIVING_MODE_SET | EPS_MODE_SET | PROP_MODE_SET | Дополнение |
|---|---:|---:|---:|---|
| 1 | 1 = Eco | 2 | 1 | Для H97Y/H97X ещё HUM_ENERGY_PTREGEN_LEVL=4 |
| Иное значение, штатно 2 | QGSettings.System, default 1 | QGSettings.System, default 2 | QGSettings.System, default 1 | Отдельный TX77 с прочитанными полями |

Таким образом, для обычного цикла OFF → ON на H97X используется первая строка даже
при авторизованном пользователе. Лог `VehicleAccountInfo = 1 (Guest)` здесь сам по себе
не доказывает, что пользователь действительно вышел из аккаунта.

Нюанс холодного старта: `mAccState` первоначально равен -1 (C:105), а флаг
`VehicleAccountInfo` в этой цепочке обновляется только на ACC OFF. Если первое
наблюдаемое состояние сразу 2, выбирается ранее сохранённый флаг/default. Поэтому
«при каждом ON безусловно Eco» было бы слишком сильным утверждением: справедливо
штатное поведение после OFF; изменённый/старый флаг может изменить первый проход.

Вызов `setVehicleAndAirConditionBundleState` создаёт `ModeSettingTask` и вызывает
`execute(1)` (C:8073–8085). Только после постановки профиля и других действий ON
записываются ACC_STATUS=2, mAccState=2 и отправляется `onAccStateChanged(2)` (C:4760–4766).
Событие `BCM_PEPS_POWER_MODE` отправлено раньше — C:4654–4657 — и такого порядка
относительно штатного восстановления не обеспечивает.

## 3. Что именно является «сохранённым профилем»

Нужно различать три хранилища:

1. **Последние OEM-уставки.** `initVehicleLastStatus` читает
   `QGSettings.System` по имени enum, C:627–631. Ключи:
   `DRIVING_MODE_SET`, `EPS_MODE_SET`, `PROP_MODE_SET`. В них нет суффикса account ID.
   Поэтому account-ветка CanBusService читает общие последние уставки, а не напрямую
   облачный профиль определённого пользователя.
2. **Аккаунтный снимок VehicleSettings.** Поля JSON `drivingMode`, `epsMode`,
   `propMode` включены в `VehicleMemoryManager.backupSettings`, S:89–91. Кеш в
   `QGSettings.Secure` использует вычисляемый ключ с user ID
   (`ManagerSideProvider.createKey`, S `com/qinggan/datacenter/ManagerSideProvider.java:192–211`).
   H97X использует DataCenter, а не TSP-ветку (`VehicleMemoryManager.useDataCenter`, S:574–575).
3. **Редактируемый Individual.** `QGSettings.Global` с ключами
   `drive_mode_steeringWheelAssist<accountId>` и `drive_mode_runState<accountId>`.
   Их пишет `IndividualEditActivity.saveSettings`, S:87–120; штатный выбор Individual
   читает их в `DrivePreferenceFragment.setDriveMode`, S:785–788.

При обработке TX77 CanBusService обновляет кеш и сохраняет EPS/PROP (C:7489–7514),
режим (C:7537–7566) через `saveVehicleStateData` → `QGSettings.System.putInt` (C:6930–6935).
Это происходит **до** отправки кадров 0x0A5 и 0x1BE (C:7935–7952). Сохранённая уставка
и callback DRIVING_MODE_SET не являются доказательством успешного применения ECU.

Следовательно, отправка Sport через VoyahTune может записать последние OEM-уставки,
но при следующем обычном ON экспортная ветка снова явно поставит Eco и сохранит его.
Просто наличие Sport в QGSettings.System не включает восстановление Sport на H97X.

## 4. Аккаунтная синхронизация Sport+ не возвращает прежний режим

S `com/qinggan/app/vehiclesetting/fragments/charge/VehicleSettingService.java:395–419`:
при ACC 0 → 2 и негостевом аккаунте вызывается `startSync(false)`.

S `com/qinggan/app/vehiclesetting/accountdata/VehicleMemoryManager.java`:

- 299–321: сначала локальный аккаунтный кеш, при отсутствии — DataCenter/TSP.
- 378–469: формирование bundle.
- 407–415 и 434–440: DRIVING_MODE_SET, EPS_MODE_SET и PROP_MODE_SET попадают в bundle
  только при `fromUser=true && !isOverSeaVehicle()`.
- 417–433: рекуперация и энергорежим тоже исключены для экспортных машин.
- 454–455: DRIVING_MODE_RETAIN=2 добавляется только для неэкспортных машин.

Для H97X поле режима исключено как из обычного `startSync(false)`, так и из
`startSync(true)`, который вызывается отдельным QGBus-событием с задержкой 1000 мс
(S `VehicleSettingService.java:308–324`). Наличие параметров в JSON не означает их
обратную отправку автомобилю. Сохранение/выгрузка и восстановление имеют разные фильтры.

Это уточняет прежнее предположение о возможном позднем перезаписывании режима
обычной аккаунтной синхронизацией: в изученной Sport+ 13.1 оно для этой тройки
не подтверждается. Другие поля синхронизации существуют, но выходят за рамки этого аудита.

## 5. Отдельные события, которые всё же могут снова поставить Eco

S `VehicleSettingService.java:175–203`:

- `com.qinggan.account.logined`: после смены ключа кеша экспортная ветка вызывает
  `resetOverseaDriveMode()` и возвращается. В ней нет условия «только другой пользователь».
  Передаваемая в `resetKey` проверка того же пользователя этот вызов не отменяет.
- `com.qinggan.account.exited`: вызывает `startReset()`. Если аккаунт уже гостевой,
  выполняется `resetSettings()` на worker.

S `VehicleMemoryManager.java:509–584`:

| Событие | Состав запроса |
|---|---|
| Вход, экспортная машина | DRIVING_MODE_SET=1, IVI_SOC_MODESET=1, HUM_ENERGY_PTREGEN_LEVL=4; EPS/PROP отдельно не заданы |
| Выход с переходом в гостя | DRIVING_MODE_SET=1, EPS_MODE_SET=2, PROP_MODE_SET=1, HUM_ENERGY_PTREGEN_LEVL=4; для H97X IVI_SOC_MODESET=1 |

Значит поздний login broadcast способен отправить новый Eco уже после ранней попытки
VoyahTune на ACC. Частота и порядок таких broadcast при реальном пробуждении неизвестны
без системного лога; нельзя считать, что это происходит на каждом wake.

## 6. DRIVING_MODE_RETAIN не отменяет явный сброс в Eco

У поля stable ID 1065. В C:464–469 при инициализации H97Y/H97X или гостю посылается 1
(OEM-лог: disabled), остальным — 2 (enabled). Оно кодируется в 0x1BE, биты 42–43
(C:7505–7508).

Но ветка ACC ON проверяет VehicleAccountInfo, а не DRIVING_MODE_RETAIN. Поэтому
установка RETAIN=2 сама по себе не устраняет явный TX77 с Eco. Ручная подмена
VehicleAccountInfo=2 тоже не является устойчивым исправлением: на следующем OFF
экспортная ветка вернёт 1. Эти параметры в рамках аудита не изменялись.

## 7. Следствие для раннего восстановления VoyahTune

Для следующей реализации кандидат — отдельная одноразовая отправка профиля движения
на OEM `onAccStateChanged(2)`, через существующий DriveModeCanTransport. Подписка должна
быть на OEM ACC, а не Android CarPower ON. Интерфейс Sport+ подтверждает callback 39,
getAccStatus TX66 и запись bundle TX77.

Порядок штатного ON:

```text
BCM_PEPS показывает ON
  → публикуется BCM_PEPS_POWER_MODE
  → штатный Eco / последние уставки ставятся в очередь ModeSettingTask
  → ACC_STATUS и mAccState становятся 2
  → OEM onAccStateChanged(2)
  → возможна наша ранняя постановка профиля после штатной
```

В изученном пути задачи используют `AsyncTask.execute`; переопределения его executor
в просмотренном коде CanBusService не найдено. Для штатной последовательной очереди
это обеспечивает порядок постановки штатного и нашего профиля, но не успешность CAN.

При подключении уже после ON доступен TX66, однако чтение текущего 2 само по себе
не отличает новую поездку от reconnect в уже начавшейся поездке. Нужны учёт цикла ACC,
отмена при OFF, защита от дублей и приоритет явного пользовательского выбора.
Текущий CanBusEventHub callback 39 не декодирует.

Существующую попытку на D пока разумно сохранять до проверки на автомобиле. Основания
для проверки — принятие ранней команды исполнительными блоками и возможный поздний
login/logout reset; обычный startSync(false) Sport+ сам профиль не перезаписывает.

## 8. Что подтвердить на целевом Sport+

1. H97X / type ID 13402, происхождение и совпадение CanBusService с исследованным APK.
2. В одном системном логе OFF → wake → ACC ON → D сопоставить:
   `onaccsta`, `VehicleAccountInfo`, `onAccStateChanged`, постановки TX77 и
   `setVehicleMode init entry.getKey`, `resetOverseaDriveMode`, `resetSettings`,
   `syncCacheToCan vehicleBundle`.
3. Отличить OEM-публикацию DRIVING_MODE_SET от CAN feedback. DRIVING_MODE_SET_FB
   поступает из VCU (C:4124–4127), но не кодирует все профили один к одному;
   для проверки всего профиля нужны также соответствующие подтверждения параметров.
4. Проверить холодную загрузку, обычный wake, reconnect Native и поздний вход в аккаунт
   отдельно. Не считать фиксированную задержку или один callback доказательством
   окончательной готовности.

Для этих проверок нужен лог OEM-процессов, а не только NativeLog с PID Native.
