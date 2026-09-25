# Сервисный режим подвески

Переключатель в конце «Настроек автомобиля» сохраняет `suspensionMaintenance` и
сразу отправляет команду через Native. То же значение используется виджетом,
действием кнопки `toggle_suspension_maintenance` и голосовыми командами
`suspension_maintenance:on/off`. Виджет можно включить в настройках главного экрана;
по умолчанию он скрыт. Назначение на кнопки руля доступно в Full.

## Штатный протокол

- `VehicleState.ASC_MAINTAIN_SWITCH`, стабильный ID `711`.
- Включение: `2`, выключение: `1`.
- Native использует `OemVehicleStateTransport.sendVehicleState` (штатный setter,
  TX58), а не готовый raw CAN-кадр. CanBusService выбирает раскладку соседних полей.
- В исследованном CanBusService параметр обрабатывается в `setIVI_chassisSet1`:
  два младших бита полезной нагрузки, `FillCommand(105, ...)`, то есть кадр `0x69`.

Основания в локальной декомпиляции:

- `tmp/VehicleSetting_jadx/sources/com/qinggan/canbus/VehicleState.java`: `ASC_MAINTAIN_SWITCH(711)`.
- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/vehiclesetting/databinding/FragmentSafetyMaintenanceBindingImpl.java`:
  `HintSwitch.setHintSwitchValue(fragmentSuspensionMaintain, DFVehicleState.ASC_MAINTAIN_SWITCH, 2, 1)`.
- `tmp/VehicleSetting_jadx/sources/com/qinggan/app/basevehiclesetting/widgets/HintSwitch.java`:
  первый аргумент значения задаёт `onValue`, второй — `offValue`.
- `SafetyMaintenanceFragment`: действие вызывает `CanBusTool.setVehicleStateBack(vehicleState, i, false)`.
- В `tmp/car_apks/decompiled/VehicleSettings_Sport+_13.1` тот же ID и обработчик режима.
- `tmp/CanBusService_jadx/sources/com/qinggan/canbus/service/protocol/dongfeng/DongfengCanBusComponentImpl.java`:
  формирование штатного кадра в `setIVI_chassisSet1`.

Константы `DFVehicleState.ASC_MAINTAIN_CLOSED/OPEND` со значениями `0/1` не используются:
они расходятся с привязкой фактического переключателя VehicleSettings и командами пользователя.

## Сохранение и восстановление

Поле добавлено в конец курсора RestoreMode (колонка 32), старые индексы сохранены.
Native сохраняет его в `cacheSuspensionMaintenance` вместе с остальным снимком и
использует этот кеш, если провайдер недоступен при старте. При отсутствии настройки
значение по умолчанию — выключено.

В общий `CanRestorePlan` добавлена операция с явным значением, поэтому запуск,
пробуждение и ручное применение восстанавливают сохранённое состояние через
существующий механизм. Голос и кнопка сохраняют новое значение после успешной
отправки в OEM-сервис и синхронизируют открытые экраны через `SETTING_SYNCED`.
Физическое подтверждение от автомобиля и отдельные таймеры отмены не добавлены.

## Голос

В справочнике группа «Настройки автомобиля». Примеры:

- «включить сервисный режим подвески», «включи режим обслуживания подвески»;
- «выключить сервисный режим подвески», «отключить режим обслуживания подвески»;
- «заблокировать подвеску», «заблокируй подвеску», «заблокировать подвеска»;
- «разблокировать подвеску», «разблокируй подвеску».

Изменение высоты («поднять подвеску») остаётся отдельной командой Outing.
Нормализатор склоняет существительные, но не исправляет опечатки в глаголах
блокировки/разблокировки. Смешанные и отрицательные команды отклоняются.

Проверено локально: компиляция обоих приложений FullDebug и их JVM-тесты, включая
OEM-значения, новые голосовые фразы, противоположные намерения и группы справочника.
На автомобиль изменения не устанавливались, физическая работа режима не проверялась.
