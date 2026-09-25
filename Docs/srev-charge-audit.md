# Установка уровня поддержания заряда SREV

Проверено 2026-09-24 по локальным декомпиляциям `tmp/VehicleSetting.apk` и
`tmp/CanBusService.apk`. Совпадение этих APK с прошивкой тестируемого ГУ не проверено.
Это аудит программного пути, не подтверждение выполнения на автомобиле.

## Штатная установка

1. `ChargeAdjustProgressView.onTouchEvent` при отпускании ползунка вызывает
   `onProgressChanged(vehicleState, percent, true)`.
2. `DrivePreferenceFragment.onProgressChanged` для `SREV_SOC_SET` преобразует процент
   в `(percent - 25) / 5`: 25% → 0, 50% → 5, 80% → 11. Затем вызывает
   `CanBusTool.setVehicleStateBack(SREV_SOC_SET, level, true)`.
3. `CanBusTool` сразу вызывает `CanBusManager.setVehicleState`. Параметр `true`
   включает повторное чтение состояния через 3000 мс и обновление подписчиков,
   **не повторную отправку и не дополнительную команду сохранения**.
4. `CanBusManager` вызывает `ICanBusService.setVehicleState`, Binder TX58.
   В Parcel передаются признак объекта, ordinal установленного enum, stable ID 1196
   и числовой уровень 0…11. Возврат без исключения подтверждает вызов сервиса,
   но не принятие уставки автомобилем.
5. В H97C `setVehicleState` направляет `SREV_SOC_SET` в `setIVI_chassisSet1`.
   Уровень записывается в `mSREVSocSet`, затем в поле из 4 бит с началом 25
   (`IntelByteOrderUtils`) и отправляется через `FillCommand(105, buf, 8)`.
   **105 здесь — идентификатор команды OEM-протокола, не установленный аудитом CAN ID.**
   При отрицательном результате отправки сервис пишет `setIVI_chassisSet1 fail`,
   но не возвращает этот результат вызывающему TX58 приложению.

Опорные места:

- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/basevehiclesetting/ChargeAdjustProgressView.java:81`
- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/vehiclesetting/fragments/drivepreference/DrivePreferenceFragment.java:1820`, `:1874`
- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/basevehiclesetting/canbustools/CanBusTool.java:403`, `:126`
- `tmp/VehicleSetting_jadx/sources/com/qinggan/canbus/CanBusManager.java:1248`
- `tmp/CanBusService_jadx/sources/com/qinggan/canbus/ICanBusService.java:2103`
- `tmp/CanBusService_jadx/sources/com/qinggan/canbus/service/protocol/dongfeng_h97c/DongfengH97CCanBusComponentImpl.java:6095`, `:7018`

## Режим и обратная связь

Штатный выбор SREV — отдельный `IVI_SOC_MODESET = 4` в TX77.
Ползунок показывается только для режима 4 (`setPowerModePic`). Однако интерфейс
переключается оптимистично, до подтверждения VCU. В проверенном setter уровня
нет условия «текущий режим обязательно SREV» и нет обязательной задержки.
Следовательно, отказ старой последовательности «уровень, затем SREV» **не доказан**.
Новая последовательность VoyahTune с ожиданием режима — обоснованная стратегия
составной команды, но не буквальное воспроизведение обязательного OEM-протокола.

H97C принимает `VCU_Indication` (в исходнике указан 0x2FA): режим — биты 50…52 + 1,
уровень — биты 56…59. Сервис обновляет `mVehicleStatusCacheMap`, сохраняет уровень
при ACC != 0 и уведомляет слушателей. TX57 читает этот кэш; это не синхронный запрос
к VCU и не гарантия свежести сообщения. Проверка обратного уровня надёжнее самого
возврата TX58, но для диагностики нужны также входящие сообщения и их время.

Опорные места: `DrivePreferenceFragment.java:895`, `:913`;
`DongfengH97CCanBusComponentImpl.java:1756`, `:6930`, `:7103`.

## Два механизма сохранения

- CanBusService сохраняет подтверждённое значение в `QGSettings.System` с ключом
  `SREV_SOC_SET`. Это делается по обратной связи при ACC != 0, а не произвольной
  записью профиля вызывающим приложением.
- `DrivePreferenceFragment.setPowerChange` обновляет экран и вызывает
  `savePowerChange`, который при ACC != 0 записывает значение в свойство
  `persist.qinggan.account.uid.power<userid>`. Это отдельное сохранение профиля.
  При открытии меню для негостевого аккаунта `getPowerChange` берёт значение
  из свойства профиля; для гостя использует полученный VehicleState.
  Подписка фрагмента удаляется при его уничтожении.
- `VehicleSettingService.checkPowerChange` читает свойство профиля и заново
  отправляет `SREV_SOC_SET` при входе аккаунта в соответствующей ветке прошивки.
  Зарубежная ветка входа вызывает другой путь `resetOverseaDriveMode`.

Таким образом, уставка в VCU, сохранённый профиль и число в штатном меню могут
расходиться. Это возможное объяснение симптома, **не установленная причина**.
VoyahTune сейчас не синхронизирует свойство OEM-профиля. До определения активного
аккаунта и поведения конкретной прошивки записывать туда предполагаемый UID нельзя.

Опорные места: `DrivePreferenceFragment.java:569`, `:677`, `:1838`–`:1871`;
`VehicleSettingService.java:181`–`:195`, `:424`–`:434`.

## Что проверить на ГУ

Сравнить ручную установку ползунком и голосовую установку одного процента:
исходные режим/уровень TX57, запрос TX58, ошибки `setIVI_chassisSet1`, последующее
`VCU_SREVSocFdk` и обратный уровень TX57, число в меню до/после его повторного
открытия, значение профиля активного аккаунта. Проверить отдельно при уже включённом
SREV и при переходе из другого режима. Не путать установленный порог с текущим
процентом батареи: заряд батареи не должен мгновенно становиться равным уставке.

Новая проверка VoyahTune различает неподтверждённый режим и неподтверждённый уровень,
но без этого сравнения нельзя утверждать, что исходная проблема устранена.
