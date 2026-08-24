#!/bin/sh
# Удаление Open Voyah v@VERSION@-LIGHT — полный откат к состоянию ДО установки.
# LIGHT ничего не инжектит и не трогает init.logcat.sh/Frida — чистим только priv-app + whitelist + оба APK.
if [ ! -f ./dns-overlay.sh ]; then
    echo "!!! Не найден ./dns-overlay.sh — удаление прервано до изменения устройства."
    exit 1
fi
. ./dns-overlay.sh || {
    echo "!!! Не удалось загрузить ./dns-overlay.sh — удаление прервано."
    exit 1
}
for ydns_required in ydns_prepare_helper restore_yandex_dns; do
    if ! command -v "$ydns_required" >/dev/null 2>&1; then
        echo "!!! dns-overlay.sh не содержит $ydns_required — удаление прервано."
        exit 1
    fi
done
if ! ydns_prepare_helper restore; then
    echo "!!! Не удалось подготовить DNS-overlay helper — удаление прервано."
    exit 1
fi

adb root >/dev/null 2>&1
adb wait-for-device
adb root >/dev/null 2>&1
# /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
# ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA — сначала прогнать
# install.sh, он снимет verity и перезагрузит).
adb disable-verity >/dev/null 2>&1
adb remount >/dev/null 2>&1
adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null'

echo "=== Откат DNS-overlay ==="
if ! restore_yandex_dns; then
    echo "!!! Не удалось восстановить/отключить DNS-overlay — остальные компоненты не удалялись."
    exit 1
fi

# --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
echo "=== Удаление APK Open Voyah ==="
adb shell am force-stop ru.big.town.anative >/dev/null 2>&1
adb shell am force-stop ru.big.town.restoremode >/dev/null 2>&1
# Android 11 app-data удаляет только PackageManager/installd. Raw rm из /data/user_de ломает
# /data_mirror encryption state и может сделать следующий Native незапускаемым.
if ! adb shell '
    pm uninstall ru.big.town.anative >/dev/null 2>&1 || true
    pm uninstall --user 0 ru.big.town.anative >/dev/null 2>&1 || true
    pm uninstall ru.big.town.restoremode >/dev/null 2>&1 || true
    if pm path ru.big.town.anative 2>/dev/null | grep -q "^package:/data/app/"; then exit 1; fi
    if pm path ru.big.town.restoremode 2>/dev/null | grep -q "^package:"; then exit 1; fi
'; then
    echo "!!! PackageManager не завершил удаление APK Open Voyah — перезагрузка отменена."
    exit 1
fi
echo "  PackageManager удалил user-data, RestoreMode и Native update; raw app-data не трогаем."
if ! adb shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/priv-app/.Native.apk.voyahtune.new && rm -rf /system/priv-app/Native && test ! -e /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml && test ! -e /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new && test ! -e /system/priv-app/.Native.apk.voyahtune.new && test ! -e /system/priv-app/Native"; then
    echo "!!! Не удалось полностью удалить системные файлы Open Voyah — перезагрузка отменена."
    exit 1
fi
# Light не создаёт full-hook файлы. Здесь остаются только наши собственные helper/state/log paths;
# CE/DE, profiles и Android/data уже принадлежат PackageManager и вручную не удаляются.
if ! adb shell '
    rm -f \
        /data/local/bin/apollo_tech.js \
        /data/local/bin/apollo_tech.js.new \
        /data/local/bin/keyboard_lock_en.js \
        /data/local/bin/keyboard_ru.js \
        /data/local/bin/voyahtune_keyboard_en_config.json \
        /data/local/bin/voyahtune_keyboard_ru_config.json \
        /data/local/bin/voyahtune_skb_qwerty_ru.json \
        /data/local/tmp/voyahtune_keyboard.pid \
        /data/local/tmp/voyahtune_keyboard.attempt \
        /data/local/tmp/voyahtune_keyboard.txt \
        /data/local/tmp/voyahtune_keyboard.txt.try \
        /data/local/tmp/voyahtune_apollo.pid \
        /data/local/tmp/voyahtune_apollo.attempt \
        /data/local/tmp/voyahtune_apollo.txt \
        /data/local/tmp/voyahtune_apollo.txt.try \
        /data/local/tmp/voyah_apollo.pid \
        /data/local/tmp/voyah_apollo.down \
        /data/local/tmp/voyah_apollo.disabled \
        /data/local/tmp/voyah_apollo.txt \
        /data/local/tmp/voyah_apollo.txt.1 \
        /data/local/tmp/voyah_apollo.txt.try \
        /data/local/tmp/open_voyah_dns_overlay.sh \
        /data/local/tmp/open_voyah_yandex_dns.apk \
        /sdcard/tmp/voyahtune_native_log.txt \
        /sdcard/tmp/voyah_native_log.txt && \
    rm -rf /data/local/open_voyah && \
    for path in \
        /data/local/bin/apollo_tech.js \
        /data/local/bin/apollo_tech.js.new \
        /data/local/bin/keyboard_lock_en.js \
        /data/local/bin/keyboard_ru.js \
        /data/local/bin/voyahtune_keyboard_en_config.json \
        /data/local/bin/voyahtune_keyboard_ru_config.json \
        /data/local/bin/voyahtune_skb_qwerty_ru.json \
        /data/local/tmp/voyahtune_keyboard.pid \
        /data/local/tmp/voyahtune_keyboard.attempt \
        /data/local/tmp/voyahtune_keyboard.txt \
        /data/local/tmp/voyahtune_keyboard.txt.try \
        /data/local/tmp/voyahtune_apollo.pid \
        /data/local/tmp/voyahtune_apollo.attempt \
        /data/local/tmp/voyahtune_apollo.txt \
        /data/local/tmp/voyahtune_apollo.txt.try \
        /data/local/tmp/voyah_apollo.pid \
        /data/local/tmp/voyah_apollo.down \
        /data/local/tmp/voyah_apollo.disabled \
        /data/local/tmp/voyah_apollo.txt \
        /data/local/tmp/voyah_apollo.txt.1 \
        /data/local/tmp/voyah_apollo.txt.try \
        /data/local/tmp/open_voyah_dns_overlay.sh \
        /data/local/tmp/open_voyah_yandex_dns.apk \
        /data/local/open_voyah \
        /sdcard/tmp/voyahtune_native_log.txt \
        /sdcard/tmp/voyah_native_log.txt; do
        if [ -e "$path" ] || [ -L "$path" ]; then exit 1; fi
    done
'; then
    echo "!!! Не удалось полностью удалить собственные helper/state-файлы Open Voyah — перезагрузка отменена."
    exit 1
fi

# Light создаёт эти fail-closed ключи при установке прямого Apollo; полный откат удаляет их.
echo "=== Очистка Settings.Global ==="
if ! adb shell '
    for setting_name in \
        open_voyah_apollo_master open_voyah_apollo_legacy_hook_enabled \
        open_voyah_apollo_asc open_voyah_apollo_sdb \
        open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat; do
        settings delete global "$setting_name" >/dev/null 2>&1 || exit 1
    done
    settings delete global voyahtune_keyboard_mode >/dev/null 2>&1 || exit 1
'; then
    echo "!!! Не удалось полностью очистить Settings.Global — перезагрузка отменена."
    exit 1
fi
echo "  Настройки Open Voyah очищены."

# Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb reboot
