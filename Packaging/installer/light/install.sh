#!/bin/sh
# Установка Open Voyah v@VERSION@-LIGHT. Запускать из папки релиза (бэкапы падают в ./backup).
# LIGHT = без Frida/root-инъекций: режимы вождения, прямой Binder Apollo, автосвет, прогрев/статусы, дворники,
# поездки, Power Hold, режим мойки, звук пешеходов, плавающая кнопка «Назад», установка приложений,
# ярлыки приложений (обычный запуск). БЕЗ сплита/дока/VirtualDisplay, БЕЗ Frida и любой root-инъекции,
# БЕЗ раздела «Кнопки на руле». init.logcat.sh системы НЕ трогаем.
if [ ! -f ./dns-overlay.sh ]; then
    echo "!!! Не найден ./dns-overlay.sh — установка прервана до изменения устройства."
    exit 1
fi
. ./dns-overlay.sh || {
    echo "!!! Не удалось загрузить ./dns-overlay.sh — установка прервана."
    exit 1
}
for ydns_required in ydns_prepare_helper ydns_query_state choose_yandex_dns install_yandex_dns disable_yandex_dns; do
    if ! command -v "$ydns_required" >/dev/null 2>&1; then
        echo "!!! dns-overlay.sh не содержит $ydns_required — установка прервана."
        exit 1
    fi
done
for LIGHT_REQUIRED_ASSET in native.apk restore_mode.apk \
        privapp-permissions-ru.big.town.anative.xml; do
    if [ ! -s "$LIGHT_REQUIRED_ASSET" ]; then
        echo "!!! Отсутствует или пуст обязательный файл $LIGHT_REQUIRED_ASSET — устройство не изменялось."
        exit 1
    fi
done
if ! ydns_prepare_helper; then
    echo "!!! Не удалось подготовить DNS-overlay helper — установка прервана."
    exit 1
fi

adb root
adb wait-for-device
adb root

# Full -> Light transition barrier. Match only the project-owned absolute runtime paths; bracket
# classes prevent pgrep/pkill from matching their own remote command line. TERM gets a bounded grace
# period, then KILL prevents an orphan keymanager injector from opening scripts during deletion.
stop_full_hook_runtime_for_light() {
    adb shell '
        command -v pgrep >/dev/null 2>&1 || exit 1
        command -v pkill >/dev/null 2>&1 || exit 1
        setprop ctl.stop voyahtune_load 2>/dev/null || true
        pkill -TERM -f "/data/local/bin/load[.]bin" 2>/dev/null || true
        pkill -TERM -f "/data/local/bin/frida[-]inject" 2>/dev/null || true
        stop_wait=0
        while pgrep -f "/data/local/bin/load[.]bin" >/dev/null 2>&1 \
                || pgrep -f "/data/local/bin/frida[-]inject" >/dev/null 2>&1; do
            [ "$stop_wait" -lt 10 ] || break
            sleep 1
            stop_wait=$((stop_wait + 1))
        done
        if pgrep -f "/data/local/bin/load[.]bin" >/dev/null 2>&1 \
                || pgrep -f "/data/local/bin/frida[-]inject" >/dev/null 2>&1; then
            pkill -KILL -f "/data/local/bin/load[.]bin" 2>/dev/null || true
            pkill -KILL -f "/data/local/bin/frida[-]inject" 2>/dev/null || true
            kill_wait=0
            while pgrep -f "/data/local/bin/load[.]bin" >/dev/null 2>&1 \
                    || pgrep -f "/data/local/bin/frida[-]inject" >/dev/null 2>&1; do
                [ "$kill_wait" -lt 5 ] || exit 1
                sleep 1
                kill_wait=$((kill_wait + 1))
            done
        fi
    '
}

# Disable the parsed-on-next-boot entry before deleting data assets. If a later rm fails, the
# renamed non-.rc file is inert and the transition stays fail-closed on the next reboot.
remove_full_hook_runtime_for_light() {
    adb shell '
        ACTIVE_RC=/system/etc/init/voyahtune.load.rc
        DISABLED_RC=/system/etc/init/voyahtune.load.rc.voyahtune-light-disabled
        rm -f "$DISABLED_RC" || exit 1
        if [ -e "$ACTIVE_RC" ] || [ -L "$ACTIVE_RC" ]; then
            mv -f "$ACTIVE_RC" "$DISABLED_RC" || exit 1
        fi
        [ ! -e "$ACTIVE_RC" ] && [ ! -L "$ACTIVE_RC" ] || exit 1
        rm -f \
            /system/etc/init.voyahtune.load.sh \
            /system/etc/init/voyahtune.load.sh \
            /system/etc/init/voyahtune.setenforce.rc \
            /system/etc/.voyahtune.load.sh.new \
            /system/etc/.voyahtune.load.sh.previous \
            /system/etc/.voyahtune.load.sh.absent \
            /system/etc/.voyahtune.load.sh.rollback \
            /system/etc/.voyahtune.load.rc.new \
            /system/etc/.voyahtune.load.rc.previous \
            /system/etc/.voyahtune.load.rc.absent \
            /system/etc/.voyahtune.load.rc.rollback \
            /system/etc/.voyahtune.setenforce.rc.new \
            /system/etc/.voyahtune.setenforce.rc.previous \
            /system/etc/.voyahtune.setenforce.rc.absent \
            /system/etc/.voyahtune.setenforce.rc.rollback \
            "$DISABLED_RC" || exit 1

        # load.bin/frida-inject могли существовать до VoyahTune, а их backup хранится в каталоге
        # исходного Full-релиза. Light не угадывает ownership generic binaries: project loader
        # удаляется только по двум ASCII marker, generic injector остаётся неактивным без boot path.
        if [ -f /data/local/bin/load.bin ] \
                && grep -qF 'LOG_TAG="vt_load_bin"' /data/local/bin/load.bin 2>/dev/null \
                && grep -qF 'HOOK_MANIFEST=/data/local/bin/voyahtune-hook-manifest.json' \
                    /data/local/bin/load.bin 2>/dev/null; then
            rm -f /data/local/bin/load.bin || exit 1
        fi
        rm -f /data/local/bin/apollo_tech.js \
            /data/local/bin/apollo_tech.js.new \
            /data/local/bin/apollo_tech.js.voyahtune.new \
            /data/local/bin/load.bin.voyahtune.new \
            /data/local/bin/frida-inject.voyahtune.new \
            /data/local/bin/vd_bypass.js \
            /data/local/bin/vd_bypass.js.voyahtune.new \
            /data/local/bin/steeringwheelkeys.js \
            /data/local/bin/steeringwheelkeys.js.voyahtune.new \
            /data/local/bin/launcherdock.js \
            /data/local/bin/launcherdock.js.voyahtune.new \
            /data/local/bin/multidisplay.js \
            /data/local/bin/multidisplay.js.voyahtune.new \
            /data/local/bin/keymng2.js \
            /data/local/bin/keyboard_lock_en.js \
            /data/local/bin/keyboard_lock_en.js.voyahtune.new \
            /data/local/bin/keyboard_ru.js \
            /data/local/bin/keyboard_ru.js.voyahtune.new \
            /data/local/bin/voyahtune_keyboard_en_config.json \
            /data/local/bin/voyahtune_keyboard_en_config.json.voyahtune.new \
            /data/local/bin/voyahtune_keyboard_ru_config.json \
            /data/local/bin/voyahtune_keyboard_ru_config.json.voyahtune.new \
            /data/local/bin/voyahtune_skb_qwerty_ru.json \
            /data/local/bin/voyahtune_skb_qwerty_ru.json.voyahtune.new \
            /data/local/bin/voyahtune-hook-manifest.json \
            /data/local/bin/voyahtune-hook-manifest.json.voyahtune.new \
            /data/local/tmp/voyahtune_load.v2.lock \
            /data/local/tmp/voyah_load.v2.lock \
            /data/local/tmp/voyahtune_vd.pid \
            /data/local/tmp/voyahtune_vd.attempt \
            /data/local/tmp/voyahtune_vd_bypass.txt \
            /data/local/tmp/voyahtune_vd_bypass.txt.try \
            /data/local/tmp/voyahtune_swk_km.pid \
            /data/local/tmp/voyahtune_swk_km.busy \
            /data/local/tmp/voyahtune_swk_km.attempt \
            /data/local/tmp/voyahtune_swk.txt \
            /data/local/tmp/voyahtune_swk.try \
            /data/local/tmp/voyahtune_lnch.pid \
            /data/local/tmp/voyahtune_lnch.attempt \
            /data/local/tmp/voyahtune_lnch.txt \
            /data/local/tmp/voyahtune_lnch.txt.try \
            /data/local/tmp/voyahtune_md.pid \
            /data/local/tmp/voyahtune_md.attempt \
            /data/local/tmp/voyahtune_md.txt \
            /data/local/tmp/voyahtune_md.txt.try \
            /data/local/tmp/voyahtune_apollo.pid \
            /data/local/tmp/voyahtune_apollo.attempt \
            /data/local/tmp/voyahtune_apollo.txt \
            /data/local/tmp/voyahtune_apollo.txt.try \
            /data/local/tmp/voyahtune_keyboard.pid \
            /data/local/tmp/voyahtune_keyboard.attempt \
            /data/local/tmp/voyahtune_keyboard.txt \
            /data/local/tmp/voyahtune_keyboard.txt.try \
            /data/local/tmp/voyahtune_load.txt \
            /data/local/tmp/voyahtune-hook-status.v1 \
            /data/local/tmp/voyahtune-hook-status.v1.*.new \
            /data/local/tmp/voyah_vd.pid \
            /data/local/tmp/voyah_swk_ss.pid \
            /data/local/tmp/voyah_swk_km.pid \
            /data/local/tmp/voyah_swk_km.busy \
            /data/local/tmp/voyah_km.pid \
            /data/local/tmp/voyah_lnch.pid \
            /data/local/tmp/voyah_md.pid \
            /data/local/tmp/voyah_apollo.pid \
            /data/local/tmp/voyah_apollo.down \
            /data/local/tmp/voyah_apollo.disabled \
            /data/local/tmp/voyah_load.txt \
            /data/local/tmp/voyah_vd_bypass.txt \
            /data/local/tmp/voyah_vd_bypass.txt.try \
            /data/local/tmp/voyah_keymng.txt \
            /data/local/tmp/voyah_swk.txt \
            /data/local/tmp/voyah_swk.txt.try \
            /data/local/tmp/voyah_lnch.txt \
            /data/local/tmp/voyah_lnch.txt.try \
            /data/local/tmp/voyah_md.txt \
            /data/local/tmp/voyah_md.txt.try \
            /data/local/tmp/voyah_apollo.txt \
            /data/local/tmp/voyah_apollo.txt.1 \
            /data/local/tmp/voyah_apollo.txt.try || exit 1
        rm -rf /data/local/tmp/voyah_load.lock || exit 1
        for removed_path in \
                /system/etc/init/voyahtune.load.rc \
                /system/etc/init.voyahtune.load.sh \
                /system/etc/init/voyahtune.load.sh \
                /data/local/bin/vd_bypass.js \
                /data/local/bin/steeringwheelkeys.js \
                /data/local/bin/launcherdock.js \
                /data/local/bin/multidisplay.js \
                /data/local/bin/apollo_tech.js \
                /data/local/bin/keyboard_lock_en.js \
                /data/local/bin/keyboard_ru.js \
                /data/local/bin/voyahtune-hook-manifest.json \
                /data/local/tmp/voyahtune-hook-status.v1; do
            [ ! -e "$removed_path" ] && [ ! -L "$removed_path" ] || exit 1
        done
        ! pgrep -f "/data/local/bin/load[.]bin" >/dev/null 2>&1 || exit 1
        ! pgrep -f "/data/local/bin/frida[-]inject" >/dev/null 2>&1 || exit 1
        sync
    '
}

LIGHT_HOOK_BARRIER_PHASE=0
light_install_exit() {
    LIGHT_INSTALL_EXIT_STATUS=$?
    trap - 0
    case "$LIGHT_HOOK_BARRIER_PHASE" in
        1)
            echo "  ПРЕДУПРЕЖДЕНИЕ: Light install прерван до teardown; пробуем вернуть full hook-loader."
            adb shell 'setprop ctl.start voyahtune_load 2>/dev/null || true' >/dev/null 2>&1 || true
            ;;
        2)
            echo "  ПРЕДУПРЕЖДЕНИЕ: teardown начат и оставлен fail-closed; hook-loader не перезапускается."
            ;;
    esac
    exit "$LIGHT_INSTALL_EXIT_STATUS"
}
trap light_install_exit 0

# Android 11 keeps app CE/DE state behind PackageManager/installd and /data_mirror. Never repair
# these roots with mkdir/rm. After the reboot that scans the system APK, install-existing creates
# the user state; a -k cycle self-heals packages damaged by older removers without deleting prefs.
wait_for_android_boot() {
    adb wait-for-device || return 1
    BOOT_WAIT=0
    while [ "$BOOT_WAIT" -lt 60 ]; do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5
        BOOT_WAIT=$((BOOT_WAIT + 1))
    done
    [ "$BOOT_WAIT" -lt 60 ] || return 1
    adb root >/dev/null 2>&1 || return 1
    adb wait-for-device || return 1
    adb root >/dev/null 2>&1 || return 1
}

native_user_data_ready() {
    [ "$(adb shell '
        if pm list packages --user 0 2>/dev/null | grep -qx "package:ru.big.town.anative" \
                && pm path ru.big.town.anative 2>/dev/null | grep -q "^package:" \
                && [ -d /data/user/0/ru.big.town.anative ] \
                && [ -d /data/user_de/0/ru.big.town.anative ]; then
            echo READY
        else
            echo BROKEN
        fi
    ' 2>/dev/null | tr -d '\r')" = "READY" ]
}

ensure_native_user_ready() {
    echo "=== Проверка PackageManager data Native (Android 11 CE+DE) ==="
    if ! native_user_data_ready; then
        echo "  Native не зарегистрирован полностью — восстанавливаем через installd."
        adb shell "pm uninstall -k --user 0 ru.big.town.anative >/dev/null 2>&1 || true" || return 1
        NATIVE_INSTALL_RESULT=$(adb shell \
            "cmd package install-existing --user 0 --wait ru.big.town.anative" 2>&1) || {
            echo "!!! install-existing Native завершился ошибкой: $NATIVE_INSTALL_RESULT"
            return 1
        }
        echo "  $NATIVE_INSTALL_RESULT"
    fi
    if ! native_user_data_ready; then
        echo "!!! Native APK найден, но PackageManager не создал оба CE/DE data-каталога."
        return 1
    fi
    adb shell "am broadcast -a com.qinggan.intent.QINGGAN_BOOT_COMPLETE -n ru.big.town.anative/.SetModesReceiverStatic >/dev/null" \
        || return 1
    NATIVE_START_WAIT=0
    while [ "$NATIVE_START_WAIT" -lt 20 ]; do
        [ -n "$(adb shell pidof ru.big.town.anative 2>/dev/null | tr -d '\r')" ] && {
            echo "  Native запущен; CE/DE и process attach подтверждены."
            return 0
        }
        sleep 1
        NATIVE_START_WAIT=$((NATIVE_START_WAIT + 1))
    done
    echo "!!! Native не запустился после восстановления package data; установка не подтверждена."
    return 1
}

# Оба флейвора Native владеют signature-разрешением прямой записи в CanBus. Чужой первый владелец
# сделал бы установленный APK несовместимым, поэтому конфликт проверяется до изменения /system.
echo "=== Preflight владельца com.qinggan.permission.WRITE_CANBUS ==="
CANBUS_PERMISSION_DUMP=$(adb shell dumpsys package permissions 2>/dev/null)
if [ $? -ne 0 ]; then
    echo "!!! PackageManager permissions недоступны — установка прервана до записи в /system."
    exit 1
fi
case "$CANBUS_PERMISSION_DUMP" in
    *"Permission [com.qinggan.permission.WRITE_CANBUS]"*)
        CANBUS_PERMISSION_OWNER=$(printf '%s\n' "$CANBUS_PERMISSION_DUMP" | awk '
            /Permission \[com\.qinggan\.permission\.WRITE_CANBUS\]/ { in_block=1; next }
            in_block && /Permission \[/ { exit }
            in_block && /sourcePackage=/ {
                sub(/^.*sourcePackage=/, ""); gsub(/[[:space:]]/, ""); print; exit
            }')
        if [ "$CANBUS_PERMISSION_OWNER" != "ru.big.town.anative" ]; then
            if [ -n "$CANBUS_PERMISSION_OWNER" ]; then
                echo "!!! com.qinggan.permission.WRITE_CANBUS уже принадлежит $CANBUS_PERMISSION_OWNER."
            else
                echo "!!! Владелец com.qinggan.permission.WRITE_CANBUS не определён однозначно."
            fi
            echo "    Удалите несовместимый пакет и повторите light install; /system ещё не изменялся."
            exit 1
        fi
        echo "  Permission уже принадлежит ru.big.town.anative — совместимое обновление."
        ;;
    *)
        echo "  Permission ещё не объявлен — его создаст Native."
        ;;
esac

# --- Гарантируем ЗАПИСЫВАЕМЫЙ /system --------------------------------------------------------
# Light тоже пишет в /system (priv-app + whitelist привилегий), поэтому подготовка нужна ровно та же,
# что и в full. Без неё на стоковой/после-OTA голове dm-verity держит /system read-only → push даёт
# "I/O error" и установка падает. disable-verity вступает в силу ТОЛЬКО после РЕБУТА. Делаем
# ИДЕМПОТЕНТНО: если /system уже записываем (готовая голова) — ребута НЕ будет; иначе снимаем verity,
# ОДИН раз перезагружаемся и продолжаем. adb remount = OverlayFS поверх read-only/динамических
# разделов (устойчивее сырого mount -o rw,remount). При невозможности — прерываемся, НЕ трогая /system.
system_is_writable() {
    adb remount >/dev/null 2>&1
    adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null' >/dev/null 2>&1
    [ "$(adb shell 'touch /system/.ovw_rwtest 2>/dev/null && rm -f /system/.ovw_rwtest && echo RW || echo RO' | tr -d '\r')" = "RW" ]
}

# Push into an inactive path on the same /system filesystem, publish with rename only after
# ownership/mode/SELinux checks pass, and leave no stage file on an aborted Light transition.
install_required_system_file() {
    SYSTEM_SOURCE="$1"
    SYSTEM_STAGE="$2"
    SYSTEM_TARGET="$3"
    SYSTEM_MODE="$4"
    if ! adb push "$SYSTEM_SOURCE" "$SYSTEM_STAGE"; then
        adb shell "rm -f '$SYSTEM_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось передать $SYSTEM_SOURCE — финальная перезагрузка отменена."
        return 1
    fi
    if ! adb shell "chown 0:0 '$SYSTEM_STAGE' && chmod '$SYSTEM_MODE' '$SYSTEM_STAGE' && restorecon '$SYSTEM_STAGE' && mv -f '$SYSTEM_STAGE' '$SYSTEM_TARGET' && restorecon '$SYSTEM_TARGET' && sync && test -f '$SYSTEM_TARGET'"; then
        adb shell "rm -f '$SYSTEM_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось атомарно установить $SYSTEM_TARGET — финальная перезагрузка отменена."
        return 1
    fi
}

echo "=== Готовим /system к записи (verity → overlay) ==="
adb disable-verity 2>&1 | sed 's/^/  /'
if ! system_is_writable; then
    echo "  /system ещё read-only → перезагрузка ОДИН раз (применяем disable-verity)..."
    adb reboot
    adb wait-for-device
    i=0
    while [ $i -lt 60 ]; do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5; i=$((i + 1))
    done
    sleep 3
    adb root >/dev/null 2>&1; adb wait-for-device; adb root >/dev/null 2>&1
fi
if ! system_is_writable; then
    echo "!!! /system ОСТАЁТСЯ read-only — установка прервана (в /system ничего не тронуто)."
    echo "    Причины: заблокирован загрузчик (disable-verity не срабатывает) / прошивка с EROFS"
    echo "    (несжимаемая read-only ФС) / verity не снимается на этой сборке."
    echo "    Проверьте вручную: adb disable-verity ; adb reboot ; adb root ; adb remount ; adb shell mount | grep system"
    exit 1
fi
echo "  /system записываем — продолжаем."

BACKUP_DIR="backup"
mkdir -p "$BACKUP_DIR"

# Бэкап файла с головы перед перезаписью. Если бэкап уже есть — не трогаем (сохраняем оригинал).
backup_pull() {
    if [ -f "$BACKUP_DIR/$2" ]; then
        echo "Backup: $BACKUP_DIR/$2 уже есть — пропуск (сохраняем оригинал)"
        return
    fi
    if adb pull "$1" "$BACKUP_DIR/$2" >/dev/null 2>&1; then
        echo "Backup: $1 -> $BACKUP_DIR/$2"
    else
        echo "Backup: $1 отсутствует, пропуск"
    fi
}

echo "=== Бэкап перезаписываемых файлов в $BACKUP_DIR/ ==="
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# Старый Full мог жить прямо в OEM init.logcat.sh. Light не содержит проверенного OEM fallback и
# поэтому не имеет права переписывать неизвестный системный файл: такой переход выполняется только
# через full remove, где есть backup/rollback transaction.
LEGACY_FULL_BOOT_STATE=$(adb shell '
    if [ ! -e /system/etc/init.logcat.sh ]; then
        echo CLEAN
    elif [ ! -f /system/etc/init.logcat.sh ]; then
        echo ERROR
    else
        grep -qF "# init.logcat.sh Open Voyah:" /system/etc/init.logcat.sh 2>/dev/null
        legacy_grep_status=$?
        if [ "$legacy_grep_status" -eq 0 ]; then
            echo LEGACY
        elif [ "$legacy_grep_status" -eq 1 ]; then
            echo CLEAN
        else
            echo ERROR
        fi
    fi
' 2>/dev/null | tr -d '\r')
if [ "$LEGACY_FULL_BOOT_STATE" != CLEAN ]; then
    echo "!!! Найден legacy Full hook в init.logcat.sh. Сначала выполните full remove, затем Light install."
    exit 1
fi

# Ниже больше нет reboot до финальной установки. Сначала freeze всех exact root-процессов, затем
# отключаем Apollo/IME и только после этого атомарно убираем boot path перед data assets.
echo "=== Переход Full → Light: остановка root hook runtime ==="
LIGHT_HOOK_BARRIER_PHASE=1
if ! stop_full_hook_runtime_for_light; then
    echo "!!! Full hook-loader или его in-flight injector не остановился; teardown отменён."
    exit 1
fi

for APOLLO_SAFE_KEY in \
        open_voyah_apollo_legacy_hook_enabled \
        open_voyah_apollo_master \
        open_voyah_apollo_profile_supported \
        open_voyah_apollo_profile_heartbeat; do
    if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then
        echo "!!! Не удалось записать $APOLLO_SAFE_KEY=0; full runtime будет возвращён."
        exit 1
    fi
    APOLLO_SAFE_STATE=$(adb shell settings get global "$APOLLO_SAFE_KEY" 2>/dev/null \
        | tr -d '\r')
    if [ "$APOLLO_SAFE_STATE" != "0" ]; then
        echo "!!! $APOLLO_SAFE_KEY не подтвердил 0; full runtime будет возвращён."
        exit 1
    fi
done
adb shell am force-stop com.qinggan.app.vehiclesetting 2>/dev/null
adb shell am force-stop com.qinggan.app.qgime 2>/dev/null

LIGHT_HOOK_BARRIER_PHASE=2
if ! remove_full_hook_runtime_for_light; then
    echo "!!! Full hook runtime удалён не полностью. Не перезагружайте ГУ; повторите Light install."
    exit 1
fi
for APOLLO_OLD_KEY in open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_master \
        open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported \
        open_voyah_apollo_profile_heartbeat voyahtune_keyboard_mode; do
    adb shell settings delete global "$APOLLO_OLD_KEY" 2>/dev/null
done
echo "  Full boot path, project Frida assets и runtime-маркеры удалены; Light остаётся без watchdog."

# NB: install.sh — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
# остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
# (autoLight / wiperCold / floatingBack) тоже сохраняются.
# Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.sh.
echo "=== Native.apk в /system/priv-app (нужны привилегированные пермишены для CAN-функций) ==="
# /system уже сделан записываемым выше (verity, overlay) — отдельный remount не нужен.
if ! adb shell "mkdir -p /system/priv-app/Native && chown 0:0 /system/priv-app/Native && chmod 755 /system/priv-app/Native && restorecon /system/priv-app/Native"; then
    echo "!!! Не удалось подготовить /system/priv-app/Native — финальная перезагрузка отменена."
    exit 1
fi
install_required_system_file native.apk \
    /system/priv-app/.Native.apk.voyahtune.new \
    /system/priv-app/Native/Native.apk 644 || exit 1

# Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
if ! adb shell "mkdir -p /system/etc/permissions && chown 0:0 /system/etc/permissions && chmod 755 /system/etc/permissions && restorecon /system/etc/permissions"; then
    echo "!!! Не удалось подготовить /system/etc/permissions — финальная перезагрузка отменена."
    exit 1
fi
install_required_system_file privapp-permissions-ru.big.town.anative.xml \
    /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new \
    /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644 || exit 1
LIGHT_HOOK_BARRIER_PHASE=0

# Включить power hold (leave car), если опция выключена или отсутствует
LEAVECAR=$(adb shell getprop persist.app.feature.leavecar | tr -d '\r')
if [ "$LEAVECAR" != "true" ]; then
    echo "Enabling leave car (power hold)..."
    adb shell setprop persist.app.feature.leavecar true
fi

if ! adb install -r -g restore_mode.apk; then
    echo "!!! RestoreMode не установлен — исправьте ошибку и повторите installer до перезагрузки."
    exit 1
fi

echo "=== DNS для доступа через T-Box ==="
if ! YDNS_CURRENT="$(ydns_query_state)"; then
    echo "!!! Не удалось определить текущее состояние DNS-overlay — финальная перезагрузка отменена."
    exit 1
fi
case "$YDNS_CURRENT" in
    on|off|external|broken) ;;
    *)
        echo "!!! DNS-overlay helper вернул неизвестное состояние: $YDNS_CURRENT"
        exit 1
        ;;
esac
echo "Текущее состояние DNS-overlay: $YDNS_CURRENT"
YDNS_REQUEST=
if ! choose_yandex_dns "$YDNS_CURRENT"; then
    echo "!!! Не удалось получить выбор DNS-overlay — финальная перезагрузка отменена."
    exit 1
fi
case "${YDNS_REQUEST:-keep}" in
    on)
        install_yandex_dns || {
            echo "!!! Установка DNS-overlay завершилась ошибкой — финальная перезагрузка отменена."
            exit 1
        }
        ;;
    off)
        disable_yandex_dns || {
            echo "!!! Отключение DNS-overlay завершилось ошибкой — финальная перезагрузка отменена."
            exit 1
        }
        ;;
    keep)
        echo "DNS-overlay: оставляем текущее состояние без изменений."
        ;;
    *)
        echo "!!! Неизвестный выбор DNS-overlay: ${YDNS_REQUEST:-<пусто>}"
        exit 1
        ;;
esac

# Ребут нужен, чтобы менеджер пакетов перечитал privapp-whitelist для /system/priv-app.
if ! adb reboot; then
    echo "!!! ADB не принял финальную перезагрузку; установка не подтверждена."
    exit 1
fi
if ! wait_for_android_boot; then
    echo "!!! ГУ не завершило загрузку после установки; проверьте ADB и повторите installer."
    exit 1
fi
if ! ensure_native_user_ready; then
    echo "!!! Установка файлов завершена, но Native lifecycle не восстановлен."
    exit 1
fi
echo "Установка завершена и проверена."
