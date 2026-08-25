# Аудит Voboost: phone-num и weather-widget

Дата аудита: 2026-08-24. Исследование read-only; код Voboost не переносился.

## Phone numbers

`tmp/voboost-script/agents/phone-num-mod.js` внедряется в
`com.qinggan.bluetoothphone`, заменяет
`com.qinggan.bluetoothphone.util.Util.getAmendNumber` на `toString().trim()` и затем безусловно
запускает `PbapProfileManager.startSync()`.

Эта реализация непригодна для прямого переноса:

- `null.toString()` аварийно, хотя OEM-вариант принимает null;
- конкретный overload `getAmendNumber(String)` не выбран;
- конфигурация `enableFullNumber` и `phone-num-config.json` фактически не используются;
- неуспешный поиск OEM-класса всё равно заканчивается сообщением об успешном старте агента;
- обязательный PBAP sync очищает и массово перестраивает contacts/call-log caches, provider и речевой
  словарь; штатный HFP lifecycle уже запускает sync сам;
- Voboost feature указывает неверную цель `com.qinggan.systemservice`, тогда как сам агент требует
  `com.qinggan.bluetoothphone`;
- feature создаёт ещё не загруженный `ConfigManager`, поэтому, вероятно, остаётся выключенным.

Launcher содержит вторую копию телефонной библиотеки:
`tmp/launcher_jadx/sources/com/qinggan/aar_phone/util/Util.java`. Её
`getAmendNumber(String)` удаляет `+`, префикс `86`, пробелы и дефисы. Результат используется не только
для отображения: он попадает в lookup контакта и call log. Сравнение контактов в launcher в основном
точное, а специальная эквивалентность реализована только для `+86`.

По сохранённым логам PBAP уже импортирует полные `+7`/`+375` и смешанные российские `8`/`7`/`+7`
формы. Полные номера и имена в этот документ намеренно не включены. Значит, требуется разделить две
задачи: сохранение международного номера для отображения и каноническое сопоставление контактов.
Один глобальный trim-only hook обе задачи не решает и способен ухудшить matching.

Точного `BluetoothPhone-release-signed.apk` в репозитории нет. Фактический путь из логов:
`/system/priv-app/BluetoothPhone-release-signed/BluetoothPhone-release-signed.apk`. До реализации его
нужно получить с головы и сверить классы/overloads/call-sites с launcher.

## Weather widget

`tmp/voboost-script/agents/weather-widget-mod.js` глобально перехватывает
`okhttp3.RealCall.enqueue` в `com.qinggan.app.launcher` для weather/AQI и общего reverse-geocode.
Внутри неблокирующего API он синхронно выполняет внешние HTTP-запросы, вручную вызывает callback, а
затем всё равно вызывает original `enqueue`. Один запрос поэтому может получить два terminal callback.

Это production-blocker:

- внешний запрос способен блокировать UI/caller thread;
- original failure после fake success переводит OEM WeatherManager с 5-минутного refresh на
  повторение через 10 секунд;
- общий `/cp/geo/regeocode` используется не только погодой, но получает фиктивные Beijing adcodes;
- нет общего client, cache, coalescing, rate limit или backoff;
- часть данных выдумана: sunrise/sunset, moon times, AQI/PM оценки, 8-дневный прогноз из 5-дневного;
- UTC форматируется как local time, северный ветер `0°` обрабатывается неверно;
- feature не передаёт обязательный `api_key`, а RPC config перекрывает fallback-файл;
- тесты не проверяют hook, threading и exactly-once callback.

Текущий weather agent переносить нельзя. Безопасная будущая архитектура:

1. Native получает и кэширует погоду, хранит API key в private storage и делает bounded async IO.
2. Launcher adapter перехватывает только узкие
   `TspManager.getLiveWeather(...)` и `getAqiForecastInfo(...)`, но не общий geocoder/OkHttp.
3. Для каждого OEM request гарантируется ровно один callback.
4. Используются реальные timezone/sunrise/sunset/AQI, cache/coalescing и bounded backoff.
5. Сохраняется штатный OEM 5-минутный scheduler; новый постоянный polling не добавляется.

## Решение

- `phone-num`: исследование продолжить по отдельному плану; Voboost-код не копировать.
- `weather-widget`: оставить вне продукта до полного rewrite и отдельной privacy/network модели.
- Учитывать лицензию PolyForm Noncommercial у Voboost перед любым прямым заимствованием.
