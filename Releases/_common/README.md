# Releases/_common — общие файлы релиза

Здесь лежит всё, что попадает в релиз **кроме собранных APK**. Раньше эти файлы копировались из
предыдущей папки релиза вручную, поэтому одни и те же бинарники лежали в 15 копиях, а инжект-скрипты
приходилось править прямо внутри уже выпущенного релиза (файл был одновременно исходником и артефактом).

**Правим здесь, а не в `Releases/v<версия>/`.** Папки релизов — сборочные артефакты.

Собрать релиз:

```bash
./make_release.sh 3.2.2
```

## Что где

| Папка | Что | Куда идёт |
|---|---|---|
| `tools/` | `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`, `frida-inject-16.2.1-android-arm64` | только full |
| `inject/` | Frida-скрипты: `vd_bypass.js`, `launcherdock.js`, `steeringwheelkeys.js` | только full |
| `system/` | `load.bin`, `init.logcat.sh`, `init.logcat.original.sh`, `privapp-permissions-…xml` | full целиком; в light — только `privapp-permissions` |
| `installer/full/` | `install.sh`, `install.bat`, `remove.sh`, `remove.bat` | full |
| `installer/light/` | `install.sh`, `remove.sh` | light |

Light — не-root набор: без инжекта, frida, `load.bin` и boot-хука, поэтому у него **свои**, более короткие
установщики. Windows-набор для light исторически не выпускался; если понадобится — положить сюда
`install.bat`/`remove.bat` и добавить копирование `tools/` в light-ветку `make_release.sh`.

## Версия в установщиках

В шапке каждого установщика стоит плейсхолдер `@VERSION@` — `make_release.sh` подставляет туда номер
релиза при копировании. Руками номер версии в этих файлах **не проставлять**, иначе он «застынет».

## Папка релиза — плоская

`install.sh` ищет файлы рядом с собой, поэтому `make_release.sh` раскладывает содержимое всех подпапок
в одну плоскую `Releases/v<версия>/`. Подпапки существуют только здесь, для навигации.
