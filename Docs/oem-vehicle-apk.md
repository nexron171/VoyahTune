# Штатный VehicleCenter: интерфейсы для VoyahTune

## Исследованный APK

Статическое исследование от 29 сентября 2026 года:

- Файл: `tmp/car_apks/com.qinggan.app.vehicle.apk`.
- SHA-256: `49d2c81574b3633b27ea91cd54b31b928378d751660c421e4d98d7576deed6ad`.
- Package: `com.qinggan.app.vehicle`; versionName `1.0`, versionCode `1`;
  BuildConfig: `release`, TAG `VehicleCenter`.
- minSdk 27, target/compileSdk 30 (Android 11), shared UID `android.uid.system`.
- Три DEX; native-библиотеки `libpag.so` и `libffavc.so` для arm64-v8a,
  armeabi-v7a и armeabi; PAG-анимации климата/сидений, шрифты, `VoiceSearch.json`.
- JADX 1.5.6: исходники и ресурсы выгружены в
  `tmp/car_apks/decompiled/qinggan_vehicle/`; завершение с 107 ошибками.
  Восстановленный Java не следует считать точной или собираемой копией исходника.

Номер `1.0` не устанавливает версию прошивки или комплектацию автомобиля.
Происхождение APK по конкретной машине не подтверждено. В APK есть общие SDK
и платформенные ветки H56/H97/97C; их наличие не доказывает поддержку функций
на официальных SE и Sport+ 2025–2026. Автомобильные проверки не выполнялись.

## Состав приложения

Главный экран — `com.qinggan.app.vehicletype.ui.activity.AirActivity`:
климат, передние и задние сиденья, ароматизация, с условным показом вкладок.
Есть отдельный `AirSecondRowActivity`, редакторы имён памяти сидений и ароматов,
`SeatService`, два варианта `VoiceListenerService` (`ui` и `ui97`).

В прикладных пакетах `com.qinggan.app.vehiclebase.model` и `model97` находятся
контроллеры климата, автомобиля и сценариев, обработчики голосовых команд,
память сидений/зеркал, интеграция TSP и DataCenter. Включённые библиотеки
медиа, карт и общие перечисления CAN сами по себе не означают отдельной
доступной функции этого приложения.

Manifest также декларирует `BLEControlService`, однако соответствующий класс
не обнаружен среди выгруженных Java-исходников. Рабочий BLE-интерфейс этим
исследованием не подтверждён.
