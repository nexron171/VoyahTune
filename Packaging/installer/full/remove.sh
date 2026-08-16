#!/bin/sh
# Удаление Open Voyah v@VERSION@ — полный откат к состоянию ДО установки нашего приложения.
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
if [ ! -s ./init.logcat.original.sh ]; then
    echo "!!! Нет переходного init.logcat.original.sh — удаление прервано до изменения устройства."
    exit 1
fi

LEGACY_INIT_MARKER="# init.logcat.sh Open Voyah:"
LEGACY_INIT_DEVICE="/system/etc/init.logcat.sh"
LEGACY_INIT_FALLBACK="init.logcat.original.sh"
LEGACY_INIT_ROLLBACK_SOURCE="backup/init.logcat.voyahtune-legacy.sh"
LEGACY_INIT_MIGRATED=0

legacy_init_state() {
    LEGACY_INIT_STATE=$(adb shell "if [ ! -e '$LEGACY_INIT_DEVICE' ]; then echo MISSING; elif [ ! -f '$LEGACY_INIT_DEVICE' ]; then echo ERROR; else grep -qF '$LEGACY_INIT_MARKER' '$LEGACY_INIT_DEVICE' 2>/dev/null; legacy_grep_status=\$?; if [ \$legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ \$legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" 2>/dev/null) || return 1
    LEGACY_INIT_STATE=$(printf '%s' "$LEGACY_INIT_STATE" | tr -d '\r')
    return 0
}

valid_legacy_init_source() {
    [ -f "$1" ] && [ -s "$1" ] \
        && [ "$(LC_ALL=C sed -n '1{s/\r$//;p;}' "$1")" = "#!/system/bin/sh" ] \
        && grep -qF "/system/bin/logcat" "$1" \
        && ! grep -qF "$LEGACY_INIT_MARKER" "$1" \
        && sh -n "$1"
}

preserve_legacy_init_logcat() {
    mkdir -p backup || return 1
    rm -f "$LEGACY_INIT_ROLLBACK_SOURCE.new"
    if ! adb pull "$LEGACY_INIT_DEVICE" "$LEGACY_INIT_ROLLBACK_SOURCE.new" >/dev/null 2>&1; then
        echo "!!! Не удалось сохранить legacy init.logcat.sh для rollback."
        return 1
    fi
    if [ ! -s "$LEGACY_INIT_ROLLBACK_SOURCE.new" ] \
            || ! grep -qF "$LEGACY_INIT_MARKER" "$LEGACY_INIT_ROLLBACK_SOURCE.new" \
            || ! sh -n "$LEGACY_INIT_ROLLBACK_SOURCE.new"; then
        rm -f "$LEGACY_INIT_ROLLBACK_SOURCE.new"
        echo "!!! Копия legacy init.logcat.sh не прошла проверку — миграция отменена."
        return 1
    fi
    if ! mv -f "$LEGACY_INIT_ROLLBACK_SOURCE.new" "$LEGACY_INIT_ROLLBACK_SOURCE"; then
        rm -f "$LEGACY_INIT_ROLLBACK_SOURCE.new"
        echo "!!! Не удалось зафиксировать rollback-копию legacy init.logcat.sh."
        return 1
    fi
}

rollback_legacy_init_logcat() {
    [ "$LEGACY_INIT_MIGRATED" = 1 ] || return 0
    if [ ! -s "$LEGACY_INIT_ROLLBACK_SOURCE" ] \
            || ! grep -qF "$LEGACY_INIT_MARKER" "$LEGACY_INIT_ROLLBACK_SOURCE" \
            || ! sh -n "$LEGACY_INIT_ROLLBACK_SOURCE"; then
        echo "!!! Rollback-копия legacy init.logcat.sh повреждена: $LEGACY_INIT_ROLLBACK_SOURCE"
        return 1
    fi
    if ! adb push "$LEGACY_INIT_ROLLBACK_SOURCE" "$LEGACY_INIT_DEVICE.voyahtune.rollback"; then
        return 1
    fi
    if ! adb shell "chown 0:0 '$LEGACY_INIT_DEVICE.voyahtune.rollback' && chmod 644 '$LEGACY_INIT_DEVICE.voyahtune.rollback' && /system/bin/sh -n '$LEGACY_INIT_DEVICE.voyahtune.rollback' && restorecon '$LEGACY_INIT_DEVICE.voyahtune.rollback' && mv -f '$LEGACY_INIT_DEVICE.voyahtune.rollback' '$LEGACY_INIT_DEVICE' && restorecon '$LEGACY_INIT_DEVICE' && sync"; then
        echo "!!! Не удалось вернуть legacy init.logcat.sh. Не перезагружайте ГУ."
        return 1
    fi
    if ! legacy_init_state || [ "$LEGACY_INIT_STATE" != "LEGACY" ]; then
        return 1
    fi
    LEGACY_INIT_MIGRATED=0
}

# Позволяет remover откатить непосредственную предыдущую full-установку. Чужой/OEM-файл не трогаем.
migrate_legacy_init_logcat() {
    if ! legacy_init_state; then
        echo "!!! Не удалось проверить $LEGACY_INIT_DEVICE — удаление прервано."
        return 1
    fi
    case "$LEGACY_INIT_STATE" in
        CLEAN)
            echo "  Штатный init.logcat.sh не содержит marker VoyahTune — оставляем без изменений."
            return 0
            ;;
        MISSING)
            echo "  $LEGACY_INIT_DEVICE отсутствует — legacy-hook восстанавливать не нужно."
            return 0
            ;;
        LEGACY) ;;
        *)
            echo "!!! Неизвестный результат проверки init.logcat.sh: $LEGACY_INIT_STATE"
            return 1
            ;;
    esac

    if ! preserve_legacy_init_logcat; then
        return 1
    fi

    LEGACY_INIT_SOURCE="$LEGACY_INIT_FALLBACK"
    if [ -f backup/init.logcat.sh ]; then
        if valid_legacy_init_source backup/init.logcat.sh; then
            LEGACY_INIT_SOURCE="backup/init.logcat.sh"
            echo "  Найден OEM-backup старого установщика: $LEGACY_INIT_SOURCE"
        else
            echo "  backup/init.logcat.sh не прошёл проверку — используем чистый fallback."
        fi
    fi
    if ! valid_legacy_init_source "$LEGACY_INIT_SOURCE"; then
        echo "!!! Найден legacy init.logcat.sh, но $LEGACY_INIT_SOURCE не прошёл проверку — удаление прервано."
        return 1
    fi

    echo "  Найден boot-hook старого VoyahTune — восстанавливаем штатное логирование."
    if ! adb push "$LEGACY_INIT_SOURCE" "$LEGACY_INIT_DEVICE.voyahtune.new"; then
        adb shell "rm -f '$LEGACY_INIT_DEVICE.voyahtune.new'" >/dev/null 2>&1
        echo "!!! Не удалось передать чистый init.logcat.sh — удаление прервано."
        return 1
    fi
    LEGACY_INIT_MIGRATED=1
    if ! adb shell "chown 0:0 '$LEGACY_INIT_DEVICE.voyahtune.new' && chmod 644 '$LEGACY_INIT_DEVICE.voyahtune.new' && /system/bin/sh -n '$LEGACY_INIT_DEVICE.voyahtune.new' && restorecon '$LEGACY_INIT_DEVICE.voyahtune.new' && mv -f '$LEGACY_INIT_DEVICE.voyahtune.new' '$LEGACY_INIT_DEVICE' && restorecon '$LEGACY_INIT_DEVICE' && sync"; then
        adb shell "rm -f '$LEGACY_INIT_DEVICE.voyahtune.new'" >/dev/null 2>&1
        if ! rollback_legacy_init_logcat; then
            echo "!!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите remove."
        fi
        echo "!!! Не удалось атомарно восстановить init.logcat.sh — удаление прервано."
        return 1
    fi
    if ! legacy_init_state || [ "$LEGACY_INIT_STATE" != "CLEAN" ]; then
        if ! rollback_legacy_init_logcat; then
            echo "!!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите remove."
        fi
        echo "!!! Legacy-marker остался после восстановления init.logcat.sh — удаление прервано."
        return 1
    fi
    echo "  Legacy init.logcat.sh успешно удалён из boot path."
}

stop_voyahtune_service() {
    VOYAHTUNE_SERVICE_STATE=$(adb shell getprop init.svc.voyahtune_load 2>/dev/null) || {
        echo "!!! Не удалось прочитать состояние voyahtune_load."
        return 1
    }
    VOYAHTUNE_SERVICE_STATE=$(printf '%s' "$VOYAHTUNE_SERVICE_STATE" | tr -d '\r')
    case "$VOYAHTUNE_SERVICE_STATE" in
        ""|stopped)
            echo "  voyahtune_load уже остановлен или ещё не зарегистрирован."
            return 0
            ;;
    esac

    if ! adb shell "setprop ctl.stop voyahtune_load"; then
        echo "!!! init не принял stop для voyahtune_load."
        return 1
    fi
    VOYAHTUNE_STOP_WAIT=0
    while [ "$VOYAHTUNE_STOP_WAIT" -lt 10 ]; do
        VOYAHTUNE_SERVICE_STATE=$(adb shell getprop init.svc.voyahtune_load 2>/dev/null) || return 1
        VOYAHTUNE_SERVICE_STATE=$(printf '%s' "$VOYAHTUNE_SERVICE_STATE" | tr -d '\r')
        case "$VOYAHTUNE_SERVICE_STATE" in
            ""|stopped)
                echo "  voyahtune_load остановлен."
                return 0
                ;;
        esac
        sleep 1
        VOYAHTUNE_STOP_WAIT=$((VOYAHTUNE_STOP_WAIT + 1))
    done
    echo "!!! voyahtune_load остался в состоянии '$VOYAHTUNE_SERVICE_STATE' — удаление прервано."
    return 1
}

adb root
adb wait-for-device
adb root
# /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
# ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA — сначала прогнать
# install.sh, он снимет verity и перезагрузит).
adb disable-verity >/dev/null 2>&1
adb remount >/dev/null 2>&1
adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null'
if [ "$(adb shell 'touch /system/.ovw_remove_rwtest 2>/dev/null && rm -f /system/.ovw_remove_rwtest && echo RW || echo RO' | tr -d '\r')" != "RW" ]; then
    echo "!!! /system недоступен для записи — удаление прервано до изменения компонентов."
    exit 1
fi

# До любых удалений переводим master в 0 и проверяем фактическое значение. При ошибке живой hook
# не выгружается: пользователь может исправить adb/Settings и безопасно повторить remove.
if ! adb shell settings put global open_voyah_apollo_master 0; then
    echo "!!! Не удалось выключить Apollo master — удаление прервано до выгрузки hook."
    exit 1
fi
APOLLO_MASTER_STATE=$(adb shell settings get global open_voyah_apollo_master 2>/dev/null | tr -d '\r')
if [ "$APOLLO_MASTER_STATE" != "0" ]; then
    echo "!!! Apollo master не подтвердил состояние 0 — удаление прервано до выгрузки hook."
    exit 1
fi
if ! adb shell settings put global open_voyah_apollo_legacy_hook_enabled 0; then
    echo "!!! Не удалось закрыть legacy Apollo opt-in — удаление прервано до выгрузки hook."
    exit 1
fi
APOLLO_LEGACY_OPT_IN_STATE=$(adb shell settings get global open_voyah_apollo_legacy_hook_enabled \
    2>/dev/null | tr -d '\r')
if [ "$APOLLO_LEGACY_OPT_IN_STATE" != "0" ]; then
    echo "!!! Legacy Apollo opt-in не подтвердил 0 — удаление прервано."
    exit 1
fi
# Живой hook получает короткое окно на один best-effort штатный subscription resync.
sleep 3

echo "=== Откат DNS-overlay ==="
if ! restore_yandex_dns; then
    echo "!!! Не удалось восстановить/отключить DNS-overlay — остальные компоненты не удалялись."
    exit 1
fi

echo "=== Миграция boot-hook предыдущего full-релиза ==="
if ! migrate_legacy_init_logcat; then
    echo "!!! DNS уже восстановлен, boot-компоненты не удалялись. Исправьте ошибку и повторите remove."
    exit 1
fi

# --- Boot-хук: init.logcat уже мигрирован; остановить init-service и только затем удалить файлы ---
echo "=== Остановка и удаление voyahtune RC-сервисов ==="
if ! stop_voyahtune_service; then
    if ! rollback_legacy_init_logcat; then
        echo "!!! Legacy init.logcat.sh также не удалось вернуть. Не перезагружайте ГУ; повторите remove."
    fi
    exit 1
fi
if ! adb shell "rm -f /system/etc/init/voyahtune.load.rc /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.setenforce.rc && test ! -e /system/etc/init/voyahtune.load.rc && test ! -e /system/etc/init.voyahtune.load.sh && test ! -e /system/etc/init/voyahtune.setenforce.rc"; then
    echo "!!! Не удалось удалить voyahtune RC-файлы — удаление прервано."
    echo "    Не перезагружайте ГУ; восстановите ADB и повторите remove."
    exit 1
fi
LEGACY_INIT_MIGRATED=0
if ! adb shell "rm -f /system/etc/.voyahtune.setenforce.rc.new /system/etc/.voyahtune.load.rc.new /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.setenforce.rc.previous /system/etc/.voyahtune.setenforce.rc.absent /system/etc/.voyahtune.load.rc.previous /system/etc/.voyahtune.load.rc.absent /system/etc/.voyahtune.load.sh.previous /system/etc/.voyahtune.load.sh.absent /system/etc/.voyahtune.setenforce.rc.rollback /system/etc/.voyahtune.load.rc.rollback /system/etc/.voyahtune.load.sh.rollback"; then
    echo "  ПРЕДУПРЕЖДЕНИЕ: часть неактивных transaction-файлов не очищена; boot-hook уже удалён."
fi

# --- Остановить наши живые Frida-хуки и load.bin (до ребута) ---
adb shell "pkill -f /data/local/bin/load.bin" 2>/dev/null
adb shell "rm -f /data/local/tmp/voyah_load.v2.lock" 2>/dev/null
adb shell "rm -rf /data/local/tmp/voyah_load.lock" 2>/dev/null
adb shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|steeringwheelkeys|launcherdock|multidisplay|apollo_tech' | grep -v grep | awk '{print \$2}' | xargs kill -9" 2>/dev/null
# Eternalized agent живёт в target без frida-inject; force-stop выгружает его до финального reboot.
adb shell "am force-stop com.qinggan.app.vehiclesetting" 2>/dev/null

# --- Убрать наши Frida-файлы (или вернуть бэкап, если что-то было до нас) ---
if [ -f backup/load.bin ]; then adb push backup/load.bin /data/local/bin/load.bin; else adb shell "rm -f /data/local/bin/load.bin"; fi
adb shell "rm -f /data/local/bin/vd_bypass.js"
adb shell "rm -f /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/keymng2.js"   # keymng2 — легаси до объединения хуков руля
# Apollo-файл имеет симметричный backup: восстанавливаем прежний либо подтверждённое отсутствие.
if [ -f backup/apollo_tech.js ]; then
    if ! adb push backup/apollo_tech.js /data/local/bin/apollo_tech.js.new; then
        adb shell "rm -f /data/local/bin/apollo_tech.js.new" 2>/dev/null
        echo "!!! Не удалось восстановить backup/apollo_tech.js — удаление прервано."
        exit 1
    fi
    if ! adb shell "chmod 644 /data/local/bin/apollo_tech.js.new && mv -f /data/local/bin/apollo_tech.js.new /data/local/bin/apollo_tech.js"; then
        adb shell "rm -f /data/local/bin/apollo_tech.js.new" 2>/dev/null
        echo "!!! Не удалось завершить восстановление apollo_tech.js — удаление прервано."
        exit 1
    fi
elif [ -f backup/apollo_tech.js.absent ]; then
    if ! adb shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new"; then
        echo "!!! Не удалось вернуть подтверждённо отсутствовавший apollo_tech.js — удаление прервано."
        exit 1
    fi
else
    echo "Backup metadata для apollo_tech.js нет — неизвестный существующий файл оставлен без изменений"
    if ! adb shell "rm -f /data/local/bin/apollo_tech.js.new"; then
        echo "!!! Не удалось удалить временный apollo_tech.js.new — удаление прервано."
        exit 1
    fi
fi
if [ -f backup/frida-inject ]; then adb push backup/frida-inject /data/local/bin/frida-inject; else adb shell "rm -f /data/local/bin/frida-inject"; fi
# Маркеры переинжекта (pid-файлы) — чтобы следующая установка гарантированно переинжектила хуки
adb shell "rm -f /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_swk_km.busy /data/local/tmp/voyah_swk.*.try /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid /data/local/tmp/voyah_md.pid /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try"
# Почистить конфиг дока и кнопок руля в Settings.Global, чтобы чистая переустановка
# не подхватила старые назначения до первой синхронизации из RestoreMode.
adb shell settings delete global voyahtune_dock1 2>/dev/null
adb shell settings delete global voyahtune_dock2 2>/dev/null
adb shell settings delete global voyahtune_dock1Dpi 2>/dev/null
adb shell settings delete global voyahtune_dock2Dpi 2>/dev/null
adb shell settings delete global voyahtune_steerStarShort 2>/dev/null
adb shell settings delete global voyahtune_steerStarLong 2>/dev/null
adb shell settings delete global voyahtune_steerDvrShort 2>/dev/null
adb shell settings delete global voyahtune_steerDvrLong 2>/dev/null
adb shell settings delete global voyahtune_steerVoiceShort 2>/dev/null
adb shell settings delete global voyahtune_steerVoiceLong 2>/dev/null
adb shell settings delete global voyahtune_steerPhoneShort 2>/dev/null
adb shell settings delete global voyahtune_steerPhoneLong 2>/dev/null
adb shell settings delete global open_voyah_apollo_master 2>/dev/null
adb shell settings delete global open_voyah_apollo_legacy_hook_enabled 2>/dev/null
adb shell settings delete global open_voyah_apollo_asc 2>/dev/null
adb shell settings delete global open_voyah_apollo_sdb 2>/dev/null
adb shell settings delete global open_voyah_apollo_profile_supported 2>/dev/null
adb shell settings delete global open_voyah_apollo_profile_heartbeat 2>/dev/null

# --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
adb shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb shell "rm -rf /system/priv-app/Native"
adb shell "ls -all /system/priv-app/Native"
adb shell pm uninstall ru.big.town.anative
adb shell pm uninstall ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative
# Полностью вычистить data-каталоги Native: pm uninstall системного priv-app не всегда их удаляет,
# а протухшие данные ломают СЛЕДУЮЩУЮ установку (краш zygote при монтировании data_de/null/…).
adb shell "rm -rf /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative /data/data/ru.big.town.anative"

# --- Откат наших global settings (freeform для VirtualDisplay-сплита) ---
adb shell settings delete global enable_freeform_support
adb shell settings delete global force_resizable_activities
# Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb reboot
