# Иконка VoyahTune

`app-icon.png` — общий исходник иконки установщиков и RestoreMode (1024 × 1024).
Это выбранная пользователем картинка автомобиля из
`tmp/Gemini_Generated_Image_v9tofsv9tofsv9to.png`, сохранённая здесь для передачи
через Git. Исходный файл из `tmp/` для сборок больше не нужен.

После замены исходника выполните из корня репозитория:

```sh
npm ci --prefix Installer/desktop
node Installer/scripts/generate-icons.mjs
```

Нужны Node.js и `cwebp` из libwebp (macOS: `brew install webp`,
Ubuntu: `sudo apt install webp`). Скрипт экспортирует PNG/ICO/ICNS через Tauri,
обновляет все плотности launcher/adaptive/round иконок RestoreMode и Play Store PNG.
Содержимое исходной картинки не ретушируется.

Сохраните исходник и все изменённые ресурсы в Git. Затем пересоберите
[настольные установщики](../../Installer/BUILDING.md) и
[новый автомобильный релиз](../../Docs/releasing.md): готовые бинарники и APK
не меняются от одной замены ресурсов в исходниках.
