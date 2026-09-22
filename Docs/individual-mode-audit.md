# Проверка включения Individual с руля

Дата: 2026-09-22. Проверен код VoyahTune на коммите `254e6e3` и локальные декомпиляции
VehicleSetting / CanBusService. Симптом: после Individual с руля передняя ось опущена,
задняя поднята; повторный выбор режима в штатном меню выравнивает машину.

## Результат

В текущем пути Individual нет прямой команды изменения высоты отдельной оси или
сервисного режима подвески. Пропущенная команда выравнивания в просмотренном штатном
пути также не найдена. Причина симптома пока не доказана; команды автомобиля в рамках
этой проверки не изменялись и не отправлялись.

Есть отличие на уровне запросов к штатному сервису: VoyahTune объединяет три поля в один
TX77, а штатное меню делает два TX77. Однако просмотренный H97C-сервис в обоих случаях
отправляет CAN-кадры в одинаковом порядке: сначала режим, затем руль/педаль. Разницу
в границах задач и времени отправки нельзя без лога объявлять причиной перекоса.

## Что отправляет VoyahTune

`SetModesReceiverDynamic.cycleMode` → `MainActivity.sendDriveModeCommand` →
`DriveModeCanTransport.dispatch` → `OemVehicleStateTransport.sendBundle`:

| Поле VehicleState | Stable ID | Значение для Individual |
|---|---:|---|
| `DRIVING_MODE_SET` | 545 | 5 |
| `EPS_MODE_SET` | 722 | 2 или 3, из штатного профиля |
| `PROP_MODE_SET` | 782 | 1, 2 или 3, из штатного профиля |

`OemIndividualDriveProfileReader` читает `drive_mode_steeringWheelAssist<accountId>` и
`drive_mode_runState<accountId>` из `content://qinggan.settings/global`. Для отсутствующих
ключей используются штатные значения 2 и 1. Ошибка доступа или недопустимое значение
останавливает отправку всего профиля. Обычный формат accountInfo разбирается по тому же
предпоследнему полю, что и OEM; пограничные случаи гостевого/повреждённого аккаунта
обрабатываются иначе и требуют проверки фактически выбранного профиля на машине.

В этой операции нет `ASC_MODE_SELECT`, `ASC_MAINTAIN_SWITCH` или команд высот колёс.
Запоминание режима после отправки пишет настройки приложения и не посылает дополнительный
CAN-запрос.

Исходники приложения:

- `Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java`, `cycleMode`.
- `Native/app/src/main/java/ru/big/town/anative/DriveModeCanPolicy.java`, `planFor`.
- `Native/app/src/main/java/ru/big/town/anative/OemIndividualDriveProfileReader.java`.
- `Native/app/src/main/java/ru/big/town/anative/OemVehicleStateTransport.java`, `transactBundle`.

## Сравнение со штатным меню

В локальных VehicleSetting, Sport+ 13.1, SportEdition 2025 rc5.1 и PI Rest 2023 rc7.2
`DrivePreferenceFragment.setDriveMode` / `DriveModeIntentService.setDriveMode` сначала
передают `DRIVING_MODE_SET=5`, затем отдельным запросом `EPS_MODE_SET` и `PROP_MODE_SET`.
Прямого вызова выравнивания подвески в этом пути нет.

Штатный код также может менять цвет подсветки при включённой привязке к режиму.
Для платформ вне семейства 97C он добавляет профиль рекуперации. Условие
`is97CBasePlatform()` включает 97C, 97Y и 97X, поэтому для них отдельная рекуперация
при выборе Individual не добавляется. Это не свидетельство отсутствующей команды подвески.

В `DongfengH97CCanBusComponentImpl.ModeSettingTask.setVehicleMode`:

- `DRIVING_MODE_SET` записывается в `0x0A5`, биты 61–63; отправляется через `FillCommand(108, …)`.
- `EPS_MODE_SET` и `PROP_MODE_SET` записываются в `0x1BE`, биты 0–1 и 51–52;
  отправляются через `FillCommand(104, …)`.
- Отправка 108 стоит перед 104 даже при объединённом запросе.
- Остальные поля этих кадров формирует штатный сервис, а не фиксированный raw-шаблон VoyahTune.

Локальные первоисточники (не входят в Git):

- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/vehiclesetting/fragments/drivepreference/DrivePreferenceFragment.java`, строки 745–812.
- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/vehiclesetting/fragments/drivepreference/DriveModeIntentService.java`, строки 344–402.
- `tmp/CanBusService_jadx/sources/com/qinggan/canbus/service/protocol/dongfeng_h97c/DongfengH97CCanBusComponentImpl.java`, строки 7480–7558, 7935–7953, 8073–8085.
- `tmp/VehicleSetting_jadx/sources/com/qinggan/utils/AppCommonUtils.java`, `is97CBasePlatform`.

## Что пока не подтверждено

TX77 возвращает 0 сразу после постановки `ModeSettingTask` в очередь. Это не подтверждает
успешную отправку обоих кадров или принятие режима блоком подвески. VoyahTune возвращает
`ACCEPTED_UNCONFIRMED`; обработчик руля после этого обновляет локальный режим и при
разрешённом запоминании сохраняет выбор. Если штатный сервис не отправил второй кадр,
текущий вызывающий код об этом не узнаёт. Это возможный сценарий, а не установленная причина.

Для сопоставления нужны версия установленного VoyahTune, модель/прошивка, исходный режим,
параметры Individual и системный лог одного проблемного выбора с руля и последующего
исправляющего выбора в штатном меню. Важны `H97CCanBus`, `DrivePreferenceFragment`,
`DriveModeIntentService`, `IndividualProfile` и `OemVehicleState`; особенно ошибки
`CAN_MSG_IVI_pwrSet_0A5 fail` / `CAN_MSG_IVI_chassisSet_1BE fail`.
Обычный файл `NativeLog` содержит только PID Native и не покрывает штатный CanBusService.

Если потребуется дополнительное чтение состояния, штатный API имеет:
`ASC_MODE_SELECT=750`, `ASC_ADJUST_DIREACT=751`, `ASC_MODE_SET_FB=785`,
`ASC_MAINTAIN_SWITCH_FB=959`, `ASC_ADJUST_SUSTEMP=1060`,
`ASC_LF_HEIGHT/ASC_RF_HEIGHT/ASC_LR_HEIGHT/ASC_RR_HEIGHT=1267…1270`.
Это диагностические значения; их наличие в enum само по себе не гарантирует свежие данные
на конкретной прошивке.

## Проверка

`DriveModeCanPolicyTest` и `OemIndividualDriveProfileReaderTest`: 12 тестов пройдены.
Они подтверждают формирование параметров и разбор профиля, но не работу подвески на автомобиле.
