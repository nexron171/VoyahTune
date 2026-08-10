# DNS RRO: происхождение зафиксированного APK

`framework-res__config_ethernet_interfaces_yandexdns.apk` — готовый статический RRO для Android 11
в VoyahOS. В релизе установщик копирует его в:

```
/vendor/overlay/framework-res__config_ethernet_interfaces_yandexdns.apk
```

## Provenance

- Источник: вложение `InstallQGyandexdns.zip` из
  [поста AutoGrid №51](https://t.me/autogridapp/51), опубликованного 3 августа 2026 года.
- SHA-256 исходного ZIP: `d858592ffbd84d56e976e44cd5acef01ed0ab44cfff56f1bc8bb2d360323897e`.
- Путь APK внутри ZIP:
  `InstallQGyandexdns/QGyandexdns/framework-res__config_ethernet_interfaces_yandexdns.apk`.
- Размер APK: `8538` байт.
- SHA-256 APK: `c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d`.

В приложенном архиве отдельная лицензия на исходники или бинарник не указана. Этот файл фиксирует
техническое происхождение артефакта; checksum обеспечивает воспроизводимое использование именно
исследованного бинарника, но сам по себе не является подтверждением авторства.

## Проверенные свойства APK

- это пассивный resource-only RRO: `classes.dex` и исполняемого кода нет;
- package: `dev.dt2.qgyandexdns`;
- target package: `android`;
- `android:isStatic="true"`;
- `android:priority="100"`;
- `android:hasCode="false"`;
- `targetName` отсутствует;
- ресурс: `array/config_ethernet_interfaces` для `eth0`, `eth0:210`, `eth0:220`;
- DNS: `77.88.8.8`, `77.88.8.1`;
- подпись: Android Debug, RSA 2048;
- SHA-256 сертификата:
  `17c1ffff147efdb27d576cee66acb46384cb40f981ca274ff522515b4d75483b`;
- APK Signature Scheme: v1, v2 и v3.

DNS не читаются из внешнего XML во время работы. Три строки
`array/config_ethernet_interfaces` уже скомпилированы в `resources.arsc` этого APK; Android
подмешивает их к ресурсам пакета `android` при загрузке framework. Поэтому изменение файла или
переключение выбора вступает в силу после перезагрузки, когда `EthernetTracker` заново читает массив.

## Правило обновления

Не заменять APK молча. Для новой версии нужно повторно проверить manifest, ресурсы и подпись,
зафиксировать новый источник и checksum здесь и одновременно осознанно обновить ожидаемый SHA-256
в корневом `make_release.sh`, `installer/common/dns-overlay.sh`, `dns-overlay.bat` и
`dns-overlay-device.sh`. Сборщик проверяет, что все runtime-константы совпадают с pinned hash.
