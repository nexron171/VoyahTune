# План доработки телефонных номеров

Цель: показывать полный международный номер и корректно сопоставлять контакты, не меняя строку набора,
не принуждая PBAP sync и не добавляя polling. По умолчанию функция выключена.

## Этап 0. Получить точный OEM ABI

1. На подключённой голове сохранить APK без изменения устройства:

   ```sh
   adb pull /system/priv-app/BluetoothPhone-release-signed/BluetoothPhone-release-signed.apk
   ```

2. Зафиксировать APK SHA-256, Android version/API 30 и package version.
3. Декомпилировать и найти:

   - все overload/call-sites `com.qinggan.bluetoothphone.util.Util.getAmendNumber`;
   - lifecycle и thread требования `PbapProfileManager`;
   - contact lookup, HFP incoming/outgoing и dial path;
   - различия с `com.qinggan.aar_phone.util.Util` в launcher.

Результат этапа — version-pinned таблица `process → class → method → signature → purpose`. До неё
production hook не писать.

## Этап 1. Наблюдение без персональных данных

Сделать временный logging-only probe с явным opt-in. Для каждого вызова фиксировать только:

- null/empty;
- длину;
- наличие `+`, пробелов, скобок и дефисов;
- класс префикса `7`, `8`, `86`, `375`, other;
- необратимый salted hash и обезличенный caller class.

Номер, имя контакта и полный stack trace в лог не писать. Probe не меняет return value, не вызывает
PBAP и имеет одну попытку injection на exact process identity.

## Этап 2. Разделить display, matching и dialing

Нужны три независимых функции:

1. `displayNumber`: null-safe trim, сохраняет ведущий `+` и международный код; декоративное
   форматирование допускается только в UI.
2. `comparisonKey`: удаляет только separators и строит регионально ограниченные эквиваленты. Для РФ
   `+7xxxxxxxxxx`, `7xxxxxxxxxx` и `8xxxxxxxxxx` могут совпадать только при корректной длине; для
   `+375` и других стран код не удаляется.
3. `dialNumber`: возвращает оригинальную dialable строку. Короткие номера, `*`, `#`, паузы,
   extensions и emergency/service numbers не переписываются.

Нельзя подменять один общий OEM helper, пока не доказано, что его результат не используется всеми
тремя путями. Предпочтение — hook узких display и matching call-sites.

## Этап 3. Собственный fail-closed агент

- exact overload `getAmendNumber(String)` или более узкие подтверждённые методы;
- `null → ""` только там, где так делает OEM original;
- сохранённый original и pass-through при неизвестном формате;
- идемпотентная установка и точный ready/failure marker;
- никаких `startSync()`, force-stop или периодических Settings reads;
- full-only переключатель в «Другое», default off;
- первое включение применяется после reboot/безопасного рестарта машины, чтобы не остановить
  persistent BluetoothPhone во время звонка.

Покрыть минимум два процесса отдельными manifest records:

- `phone-numbers-bt → com.qinggan.bluetoothphone`;
- `phone-numbers-launcher → com.qinggan.app.launcher`.

Даже одинаковый JS обязан иметь отдельное process-state/identity в atomic manifest и status.
Добавление этих записей должно сопровождаться новой полной manifest/status schema и обновлением
generator, loader, installer и UI parser; дописывать восьмую строку в текущий exact v1 нельзя.

## Этап 4. API 30 harness

Расширить emulator-only harness:

- flavor `com.qinggan.bluetoothphone`;
- stubs обеих `Util` с точными signatures;
- наблюдаемый `PbapProfileManager`, чтобы тест доказывал ноль автоматических sync;
- matrices: null/empty, `+7`, `7`, `8`, `+375`, `+86`, separators, short/service, `*`/`#`, extension;
- contact/HFP variants и original/off mode;
- duplicate injection на той же identity не допускается.

Harness остаётся строго API 30 emulator-only и никогда не ставится на автомобиль.

## Этап 5. On-car acceptance

После отдельного согласования и сборки проверить:

1. известный контакт — входящий и исходящий звонок;
2. неизвестный российский, городской и зарубежный номер;
3. отображение в launcher card и полном Phone UI;
4. фактическую строку набора для обычных/коротких/служебных номеров;
5. естественный PBAP sync после HFP connection;
6. отсутствие повторного cache clear, observer storm, 10-секундного retry и роста CPU;
7. безопасный off/reboot rollback к OEM original.

## Критерий готовности

Функция готова только если одинаковый контакт находится во всех подтверждённых формах, UI сохраняет
полный международный номер, dial path байт-в-байт остаётся OEM-original и за одну поездку нет ни одного
дополнительного PBAP sync.
