# Подпись OTA-релиза

OTA использует общий `Installer/releases/index.json`. Запись содержит `ota: true` и
`otaMetadata: {body, signature}`. Без подписанных metadata служба не разрешает релиз,
даже если установлен маркер. Desktop читает эти поля и продолжает показывать все релизы.

`body` — строка JSON в UTF-8; подпись RSA PKCS#1 v1.5 / SHA-256 вычисляется над точными
байтами этой строки, `signature` — hex. Повторная сериализация содержимого body
потребует новой подписи. RSA-ключ должен иметь не менее 2048 бит.

Schema 1 связывает версию, монотонный `sequence`, SHA-256/размер ZIP, SHA-256 manifest,
минимальную версию updater, диапазон исходных версий, capabilities и signer обоих APK.
Политика `bootstrap-fingerprint` разрешает обновление только на той OEM-прошивке,
на которой USB-установщик подготовил службу. После OEM-прошивки требуется USB-установка.

Доверенный открытый ключ — `Packaging/system/voyahtune-ota-key.der` (SPKI DER).
Закрытый ключ текущей локальной сборки хранится в игнорируемом
`Releases/keys/ota-release.pem`; он не включается в APK, ZIP, GUI или Git.
Для выпуска с другого компьютера нужен тот же закрытый ключ. Смена адреса каталога
не изменяет ключ доверия. Ротация ключей в первой OTA-линии требует USB.

Подготовить запись без публикации:

```sh
python3 Installer/scripts/sign-ota.py \
  --entry Releases/dist/payload_3.14.0.json \
  --archive Releases/dist/payload_3.14.0.zip \
  --payload Releases/build/installer-payload-3.14.0 \
  --key Releases/keys/ota-release.pem --sequence 1 \
  --output Releases/dist/payload_3.14.0.ota.json
```

Инструмент проверяет размер/SHA архива и существующие подписи/metadata payload.
`installer-build verify-ota ENTRY PUBLIC_KEY PAYLOAD_DIRECTORY` отдельно проверяет
подпись metadata и соответствие распакованному релизу. Публикация выполняется обычным
`update-catalog.py --entry … --verify-remote`; скрипт подписания ничего не публикует.
Metadata опубликованной версии неизменяемы; разрешено отключать/включать её маркер.

В Android-сборках `versionCode = major × 1000000 + minor × 1000 + patch`, каждый
компонент версии 0–999. `versionName` соответствует `voyahReleaseVersion`.
Тестовая повторная установка той же версии требует отдельного действия в меню;
она не понижает сохранённый sequence и не создаёт уведомление о новой версии.
