#!/bin/sh
# Установка Open Voyah v@VERSION@. Запускать из папки релиза (бэкапы падают в ./backup).
# Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
#   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
#   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS),
#   3) dormant legacy Apollo diagnostic (direct-only не инжектит VehicleSetting).
# Boot-хук = свои RC-сервисы /system/etc/init/voyahtune.*.rc (setenforce 0 + load.bin watchdog).
# Штатный /system/etc/init.logcat.sh не меняем, кроме узкой миграции нашего legacy-файла.
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
if ! ydns_prepare_helper; then
    echo "!!! Не удалось подготовить DNS-overlay helper — установка прервана."
    exit 1
fi

# Полный локальный preflight до первого ADB-вызова: legacy-hook нельзя убирать, если архив неполон.
for FULL_REQUIRED_ASSET in load.bin steeringwheelkeys.js launcherdock.js multidisplay.js vd_bypass.js \
        apollo_tech.js frida-inject-16.2.1-android-arm64 voyahtune.load.rc \
        voyahtune.load.sh init.logcat.original.sh native.apk restore_mode.apk \
        privapp-permissions-ru.big.town.anative.xml; do
    if [ ! -s "$FULL_REQUIRED_ASSET" ]; then
        echo "!!! Отсутствует или пуст обязательный файл $FULL_REQUIRED_ASSET — устройство не изменялось."
        exit 1
    fi
done

adb root
adb wait-for-device
adb root

# Direct-only update не наследует legacy opt-in/stale gate. До disable-verity и любых
# /system mutations принудительно пишем и читаем обратно все fail-closed ключи.
echo "=== Preflight direct-only Apollo (VehicleSetting hook OFF) ==="
for APOLLO_SAFE_KEY in \
        open_voyah_apollo_legacy_hook_enabled \
        open_voyah_apollo_master \
        open_voyah_apollo_profile_supported \
        open_voyah_apollo_profile_heartbeat; do
    if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then
        echo "!!! Не удалось записать $APOLLO_SAFE_KEY=0 — установка прервана до записи в /system."
        exit 1
    fi
    APOLLO_SAFE_STATE=$(adb shell settings get global "$APOLLO_SAFE_KEY" 2>/dev/null \
        | tr -d '\r')
    if [ "$APOLLO_SAFE_STATE" != "0" ]; then
        echo "!!! $APOLLO_SAFE_KEY не подтвердил 0 — установка прервана до записи в /system."
        exit 1
    fi
done
echo "  Legacy opt-in, master, profile и heartbeat закрыты."

# Native full self-owns the signature permission needed by its fail-closed CAN writer. Android keeps
# the first installed declaration: an old VoyahTweaks owner would silently make our Native incompatible.
# Check before disable-verity/remount/touch, so a conflict leaves /system unchanged.
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
            echo "    Удалите несовместимый пакет и повторите full install; /system ещё не изменялся."
            exit 1
        fi
        echo "  Permission уже принадлежит ru.big.town.anative — совместимое обновление."
        ;;
    *)
        echo "  Permission ещё не объявлен — его создаст full Native."
        ;;
esac

# --- Гарантируем ЗАПИСЫВАЕМЫЙ /system --------------------------------------------------------
# Без этого на стоковой/после-OTA голове dm-verity держит /system read-only → push в /system даёт
# "I/O error" и установка падает. disable-verity вступает в силу ТОЛЬКО после РЕБУТА. Делаем
# ИДЕМПОТЕНТНО: если /system уже записываем (готовая голова) — ребута НЕ будет; иначе снимаем verity,
# ОДИН раз перезагружаемся и продолжаем. adb remount = OverlayFS поверх read-only/динамических
# разделов (устойчивее сырого mount -o rw,remount). При невозможности — прерываемся, НЕ трогая /system.
system_is_writable() {
    adb remount >/dev/null 2>&1
    adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null' >/dev/null 2>&1
    [ "$(adb shell 'touch /system/.ovw_rwtest 2>/dev/null && rm -f /system/.ovw_rwtest && echo RW || echo RO' | tr -d '\r')" = "RW" ]
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

install_required_data_file() {
    DATA_SOURCE="$1"
    DATA_TARGET="$2"
    DATA_MODE="$3"
    DATA_STAGE="$DATA_TARGET.voyahtune.new"
    if ! adb push "$DATA_SOURCE" "$DATA_STAGE"; then
        adb shell "rm -f '$DATA_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось передать $DATA_SOURCE — установка прервана."
        return 1
    fi
    if ! adb shell "chown 0:0 '$DATA_STAGE' && chmod '$DATA_MODE' '$DATA_STAGE' && mv -f '$DATA_STAGE' '$DATA_TARGET' && test -f '$DATA_TARGET'"; then
        adb shell "rm -f '$DATA_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось атомарно установить $DATA_TARGET — установка прервана."
        return 1
    fi
}

install_required_system_file() {
    SYSTEM_SOURCE="$1"
    SYSTEM_STAGE="$2"
    SYSTEM_TARGET="$3"
    SYSTEM_MODE="$4"
    if ! adb push "$SYSTEM_SOURCE" "$SYSTEM_STAGE"; then
        adb shell "rm -f '$SYSTEM_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось передать $SYSTEM_SOURCE — установка прервана."
        return 1
    fi
    if ! adb shell "chown 0:0 '$SYSTEM_STAGE' && chmod '$SYSTEM_MODE' '$SYSTEM_STAGE' && restorecon '$SYSTEM_STAGE' && mv -f '$SYSTEM_STAGE' '$SYSTEM_TARGET' && restorecon '$SYSTEM_TARGET' && sync && test -f '$SYSTEM_TARGET'"; then
        adb shell "rm -f '$SYSTEM_STAGE'" >/dev/null 2>&1
        echo "!!! Не удалось атомарно установить $SYSTEM_TARGET — установка прервана."
        return 1
    fi
}

BACKUP_DIR="backup"
mkdir -p "$BACKUP_DIR" || {
    echo "!!! Не удалось подготовить $BACKUP_DIR — установка прервана до перезаписи файлов."
    exit 1
}

# Бэкап файла с головы перед перезаписью. Если бэкап уже есть — не трогаем (сохраняем оригинал).
backup_pull() {
    if [ -e "$BACKUP_DIR/$2" ]; then
        if [ -f "$BACKUP_DIR/$2" ] && [ -s "$BACKUP_DIR/$2" ]; then
            echo "Backup: $BACKUP_DIR/$2 уже есть — пропуск (сохраняем оригинал)"
            return 0
        fi
        echo "!!! Существующий backup $BACKUP_DIR/$2 пуст или не является файлом."
        return 1
    fi
    BACKUP_REMOTE_STATE=$(adb shell "if [ -f '$1' ]; then echo PRESENT; elif [ -e '$1' ]; then echo ERROR; else echo ABSENT; fi" 2>/dev/null) || {
        echo "!!! Не удалось проверить $1 перед backup."
        return 1
    }
    BACKUP_REMOTE_STATE=$(printf '%s' "$BACKUP_REMOTE_STATE" | tr -d '\r')
    case "$BACKUP_REMOTE_STATE" in
        ABSENT)
            echo "Backup: $1 отсутствует, пропуск"
            return 0
            ;;
        PRESENT)
            rm -f "$BACKUP_DIR/$2.new"
            if adb pull "$1" "$BACKUP_DIR/$2.new" >/dev/null 2>&1 \
                    && [ -s "$BACKUP_DIR/$2.new" ] \
                    && mv -f "$BACKUP_DIR/$2.new" "$BACKUP_DIR/$2"; then
                echo "Backup: $1 -> $BACKUP_DIR/$2"
                return 0
            fi
            rm -f "$BACKUP_DIR/$2.new"
            echo "!!! Не удалось сохранить существующий $1 — установка прервана."
            return 1
            ;;
        *)
            echo "!!! $1 существует, но не является доступным regular file — установка прервана."
            return 1
            ;;
    esac
}

# Одноразовый симметричный backup для нового файла: запоминаем и исходное отсутствие. Без .absent
# повторная установка приняла бы нашу предыдущую версию за заводской «оригинал».
backup_pull_with_absent() {
    if [ -e "$BACKUP_DIR/$2" ]; then
        if [ ! -f "$BACKUP_DIR/$2" ] || [ ! -s "$BACKUP_DIR/$2" ]; then
            echo "!!! Существующий backup $BACKUP_DIR/$2 пуст или не является файлом."
            return 1
        fi
        echo "Backup: исходное состояние $2 уже сохранено — пропуск"
        return 0
    fi
    if [ -e "$BACKUP_DIR/$2.absent" ]; then
        if [ -f "$BACKUP_DIR/$2.absent" ]; then
            echo "Backup: исходное отсутствие $2 уже сохранено — пропуск"
            return 0
        fi
        echo "!!! Marker $BACKUP_DIR/$2.absent не является файлом."
        return 1
    fi
    REMOTE_STATE=$(adb shell "if [ -e '$1' ]; then echo PRESENT; else echo ABSENT; fi" 2>/dev/null | tr -d '\r')
    case "$REMOTE_STATE" in
        PRESENT)
            rm -f "$BACKUP_DIR/$2.new"
            if adb pull "$1" "$BACKUP_DIR/$2.new" >/dev/null 2>&1 \
                    && mv -f "$BACKUP_DIR/$2.new" "$BACKUP_DIR/$2"; then
                echo "Backup: $1 -> $BACKUP_DIR/$2"
                return 0
            fi
            rm -f "$BACKUP_DIR/$2.new"
            echo "!!! Не удалось сохранить существующий $1"
            return 1
            ;;
        ABSENT)
            if : > "$BACKUP_DIR/$2.absent"; then
                echo "Backup: $1 изначально отсутствует -> $BACKUP_DIR/$2.absent"
                return 0
            fi
            echo "!!! Не удалось создать $BACKUP_DIR/$2.absent"
            return 1
            ;;
        *)
            echo "!!! Не удалось определить исходное состояние $1"
            return 1
            ;;
    esac
}

# Непосредственный предыдущий full-релиз заменял OEM init.logcat.sh. Мигрируем только файл с нашим
# ownership-marker; любой неизвестный/OEM-вариант оставляем без изменений.
LEGACY_INIT_MARKER="# init.logcat.sh Open Voyah:"
LEGACY_INIT_DEVICE="/system/etc/init.logcat.sh"
LEGACY_INIT_FALLBACK="init.logcat.original.sh"
LEGACY_INIT_ROLLBACK_SOURCE="$BACKUP_DIR/init.logcat.voyahtune-legacy.sh"
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
        echo "!!! Не удалось передать rollback-копию legacy init.logcat.sh."
        return 1
    fi
    if ! adb shell "chown 0:0 '$LEGACY_INIT_DEVICE.voyahtune.rollback' && chmod 644 '$LEGACY_INIT_DEVICE.voyahtune.rollback' && /system/bin/sh -n '$LEGACY_INIT_DEVICE.voyahtune.rollback' && restorecon '$LEGACY_INIT_DEVICE.voyahtune.rollback' && mv -f '$LEGACY_INIT_DEVICE.voyahtune.rollback' '$LEGACY_INIT_DEVICE' && restorecon '$LEGACY_INIT_DEVICE' && sync"; then
        echo "!!! Не удалось вернуть legacy init.logcat.sh. Не перезагружайте ГУ; повторите установку."
        return 1
    fi
    if ! legacy_init_state || [ "$LEGACY_INIT_STATE" != "LEGACY" ]; then
        echo "!!! Rollback legacy init.logcat.sh не подтверждён. Не перезагружайте ГУ."
        return 1
    fi
    LEGACY_INIT_MIGRATED=0
    echo "  Legacy init.logcat.sh восстановлен после неудачной установки RC."
}

migrate_legacy_init_logcat() {
    if ! legacy_init_state; then
        echo "!!! Не удалось проверить $LEGACY_INIT_DEVICE — установка прервана."
        return 1
    fi

    case "$LEGACY_INIT_STATE" in
        CLEAN)
            echo "  Штатный init.logcat.sh не содержит marker VoyahTune — оставляем без изменений."
            return 0
            ;;
        MISSING)
            echo "  $LEGACY_INIT_DEVICE отсутствует — миграция legacy-hook не требуется."
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
    if [ -f "$BACKUP_DIR/init.logcat.sh" ]; then
        if valid_legacy_init_source "$BACKUP_DIR/init.logcat.sh"; then
            LEGACY_INIT_SOURCE="$BACKUP_DIR/init.logcat.sh"
            echo "  Найден OEM-backup старого установщика: $LEGACY_INIT_SOURCE"
        else
            echo "  backup/init.logcat.sh не прошёл проверку — используем чистый fallback."
        fi
    fi
    if ! valid_legacy_init_source "$LEGACY_INIT_SOURCE"; then
        echo "!!! Найден legacy init.logcat.sh, но $LEGACY_INIT_SOURCE не прошёл проверку — установка прервана."
        return 1
    fi

    echo "  Найден boot-hook старого VoyahTune — восстанавливаем штатное логирование."
    if ! adb push "$LEGACY_INIT_SOURCE" "$LEGACY_INIT_DEVICE.voyahtune.new"; then
        adb shell "rm -f '$LEGACY_INIT_DEVICE.voyahtune.new'" >/dev/null 2>&1
        echo "!!! Не удалось передать чистый init.logcat.sh — установка прервана."
        return 1
    fi
    LEGACY_INIT_MIGRATED=1
    if ! adb shell "chown 0:0 '$LEGACY_INIT_DEVICE.voyahtune.new' && chmod 644 '$LEGACY_INIT_DEVICE.voyahtune.new' && /system/bin/sh -n '$LEGACY_INIT_DEVICE.voyahtune.new' && restorecon '$LEGACY_INIT_DEVICE.voyahtune.new' && mv -f '$LEGACY_INIT_DEVICE.voyahtune.new' '$LEGACY_INIT_DEVICE' && restorecon '$LEGACY_INIT_DEVICE' && sync"; then
        adb shell "rm -f '$LEGACY_INIT_DEVICE.voyahtune.new'" >/dev/null 2>&1
        if ! rollback_legacy_init_logcat; then
            echo "!!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите installer."
        fi
        echo "!!! Не удалось атомарно восстановить init.logcat.sh — установка прервана."
        return 1
    fi

    if ! legacy_init_state || [ "$LEGACY_INIT_STATE" != "CLEAN" ]; then
        if ! rollback_legacy_init_logcat; then
            echo "!!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите installer."
        fi
        echo "!!! Legacy-marker остался после восстановления init.logcat.sh — установка прервана."
        return 1
    fi
    echo "  Legacy init.logcat.sh успешно удалён из boot path."
}

boot_hook_cleanup_stage() {
    # /system/etc/init сканирует все regular files, поэтому staging обязательно держим снаружи.
    adb shell "rm -f /system/etc/.voyahtune.setenforce.rc.new /system/etc/.voyahtune.load.rc.new /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.setenforce.rc.rollback /system/etc/.voyahtune.load.rc.rollback /system/etc/.voyahtune.load.sh.rollback" >/dev/null 2>&1
}

boot_hook_cleanup_snapshot() {
    adb shell "rm -f /system/etc/.voyahtune.setenforce.rc.previous /system/etc/.voyahtune.setenforce.rc.absent /system/etc/.voyahtune.load.rc.previous /system/etc/.voyahtune.load.rc.absent /system/etc/.voyahtune.load.sh.previous /system/etc/.voyahtune.load.sh.absent" >/dev/null 2>&1
}

boot_hook_final_state() {
    BOOT_HOOK_FINAL_STATE=$(adb shell "if [ -x /system/etc/init.voyahtune.load.sh ] && grep -qF '/data/local/bin/load.bin' /system/etc/init.voyahtune.load.sh && [ -r /system/etc/init/voyahtune.load.rc ] && grep -qF 'on post-fs-data' /system/etc/init/voyahtune.load.rc && grep -qF '/system/bin/setenforce 0' /system/etc/init/voyahtune.load.rc && grep -qF 'service voyahtune_load' /system/etc/init/voyahtune.load.rc && grep -qF 'on property:sys.boot_completed=1' /system/etc/init/voyahtune.load.rc && grep -qF 'enable voyahtune_load' /system/etc/init/voyahtune.load.rc; then echo READY; elif [ ! -e /system/etc/init.voyahtune.load.sh ] && [ ! -e /system/etc/init/voyahtune.setenforce.rc ] && [ ! -e /system/etc/init/voyahtune.load.rc ]; then echo ABSENT; else echo PARTIAL; fi" 2>/dev/null) || return 1
    BOOT_HOOK_FINAL_STATE=$(printf '%s' "$BOOT_HOOK_FINAL_STATE" | tr -d '\r')
    case "$BOOT_HOOK_FINAL_STATE" in
        READY|ABSENT|PARTIAL) return 0 ;;
        *) return 1 ;;
    esac
}

boot_hook_snapshot() {
    boot_hook_cleanup_snapshot || return 1
    adb shell "if [ -f /system/etc/init/voyahtune.setenforce.rc ]; then cp -p /system/etc/init/voyahtune.setenforce.rc /system/etc/.voyahtune.setenforce.rc.previous; else : > /system/etc/.voyahtune.setenforce.rc.absent; fi && if [ -f /system/etc/init/voyahtune.load.rc ]; then cp -p /system/etc/init/voyahtune.load.rc /system/etc/.voyahtune.load.rc.previous; else : > /system/etc/.voyahtune.load.rc.absent; fi && if [ -f /system/etc/init.voyahtune.load.sh ]; then cp -p /system/etc/init.voyahtune.load.sh /system/etc/.voyahtune.load.sh.previous; else : > /system/etc/.voyahtune.load.sh.absent; fi && sync"
}

boot_hook_rollback() {
    BOOT_HOOK_ROLLBACK_FAILED=0
    # Если старого load.rc не было, сначала деактивируем новый composite. Если был — wrapper и
    # standalone setenforce восстанавливаются раньше старого load.rc, который возвращается последним.
    adb shell "restore_one() { if [ -f \"\$1.previous\" ]; then cp -p \"\$1.previous\" \"\$1.rollback\" && mv -f \"\$1.rollback\" \"\$2\"; elif [ -f \"\$1.absent\" ]; then rm -f \"\$2\"; else return 1; fi; }; if [ -f /system/etc/.voyahtune.load.rc.absent ]; then rm -f /system/etc/init/voyahtune.load.rc && restore_one /system/etc/.voyahtune.load.sh /system/etc/init.voyahtune.load.sh && restore_one /system/etc/.voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc; elif [ -f /system/etc/.voyahtune.load.rc.previous ]; then restore_one /system/etc/.voyahtune.load.sh /system/etc/init.voyahtune.load.sh && restore_one /system/etc/.voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc && restore_one /system/etc/.voyahtune.load.rc /system/etc/init/voyahtune.load.rc; else exit 1; fi && for f in /system/etc/init/voyahtune.setenforce.rc /system/etc/init/voyahtune.load.rc /system/etc/init.voyahtune.load.sh; do [ ! -e \"\$f\" ] || restorecon \"\$f\" || exit 1; done && sync" >/dev/null 2>&1 || BOOT_HOOK_ROLLBACK_FAILED=1
    boot_hook_cleanup_stage
    if [ "$BOOT_HOOK_ROLLBACK_FAILED" = 0 ]; then
        boot_hook_cleanup_snapshot
    fi
    [ "$BOOT_HOOK_ROLLBACK_FAILED" = 0 ]
}

install_boot_hooks() {
    for BOOT_HOOK_SOURCE in voyahtune.load.rc voyahtune.load.sh; do
        if [ ! -f "$BOOT_HOOK_SOURCE" ]; then
            echo "!!! Не найден $BOOT_HOOK_SOURCE — boot-hook не изменён."
            return 1
        fi
    done
    if ! adb shell "mkdir -p /system/etc/init"; then
        echo "!!! Не удалось подготовить /system/etc/init — boot-hook не установлен."
        return 1
    fi

    boot_hook_cleanup_stage
    if ! adb push voyahtune.load.sh /system/etc/.voyahtune.load.sh.new; then
        boot_hook_cleanup_stage
        echo "!!! Не удалось передать voyahtune.load.sh — boot-hook не изменён."
        return 1
    fi
    if ! adb push voyahtune.load.rc /system/etc/.voyahtune.load.rc.new; then
        boot_hook_cleanup_stage
        echo "!!! Не удалось передать voyahtune.load.rc — boot-hook не изменён."
        return 1
    fi

    if ! adb shell "chown 0:0 /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && chmod 755 /system/etc/.voyahtune.load.sh.new && chmod 644 /system/etc/.voyahtune.load.rc.new && grep -qF '/data/local/bin/load.bin' /system/etc/.voyahtune.load.sh.new && grep -qF 'on post-fs-data' /system/etc/.voyahtune.load.rc.new && grep -qF '/system/bin/setenforce 0' /system/etc/.voyahtune.load.rc.new && grep -qF 'service voyahtune_load' /system/etc/.voyahtune.load.rc.new && grep -qF 'on property:sys.boot_completed=1' /system/etc/.voyahtune.load.rc.new && grep -qF 'enable voyahtune_load' /system/etc/.voyahtune.load.rc.new && restorecon /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && sync"; then
        boot_hook_cleanup_stage
        echo "!!! Не удалось подготовить права/SELinux labels boot-hook — рабочая версия сохранена."
        return 1
    fi
    if ! boot_hook_snapshot; then
        boot_hook_cleanup_stage
        boot_hook_cleanup_snapshot
        echo "!!! Не удалось сохранить текущий boot-hook перед обновлением — рабочая версия не изменена."
        return 1
    fi

    # Composite load.rc содержит и setenforce-action, и service/property: один rename активирует всё.
    if ! adb shell "mv -f /system/etc/.voyahtune.load.sh.new /system/etc/init.voyahtune.load.sh && mv -f /system/etc/.voyahtune.load.rc.new /system/etc/init/voyahtune.load.rc && restorecon /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.load.rc && sync"; then
        if boot_hook_rollback; then
            echo "!!! Не удалось завершить boot-hook; предыдущая версия восстановлена."
            return 1
        else
            echo "!!! Не удалось завершить boot-hook И его rollback. Не перезагружайте ГУ; повторите установку."
            return 2
        fi
    fi

    BOOT_HOOK_STATE=$(adb shell "if [ -x /system/etc/init.voyahtune.load.sh ] && grep -qF '/data/local/bin/load.bin' /system/etc/init.voyahtune.load.sh && [ -r /system/etc/init/voyahtune.load.rc ] && grep -qF 'on post-fs-data' /system/etc/init/voyahtune.load.rc && grep -qF '/system/bin/setenforce 0' /system/etc/init/voyahtune.load.rc && grep -qF 'service voyahtune_load' /system/etc/init/voyahtune.load.rc && grep -qF 'on property:sys.boot_completed=1' /system/etc/init/voyahtune.load.rc && grep -qF 'enable voyahtune_load' /system/etc/init/voyahtune.load.rc; then echo READY; else echo BROKEN; fi" 2>/dev/null) || {
        if boot_hook_rollback; then
            echo "!!! ADB не смог проверить boot-hook; предыдущая версия восстановлена."
            return 1
        else
            echo "!!! ADB не смог проверить boot-hook и rollback. Не перезагружайте ГУ; повторите установку."
            return 2
        fi
    }
    BOOT_HOOK_STATE=$(printf '%s' "$BOOT_HOOK_STATE" | tr -d '\r')
    if [ "$BOOT_HOOK_STATE" != "READY" ]; then
        if boot_hook_rollback; then
            echo "!!! Проверка boot-hook не пройдена; предыдущая версия восстановлена."
            return 1
        else
            echo "!!! Проверка boot-hook и rollback не прошли. Не перезагружайте ГУ; повторите установку."
            return 2
        fi
    fi
    # Уже установленный вариант PR мог оставить отдельный action; composite делает его избыточным.
    if ! adb shell "rm -f /system/etc/init/voyahtune.setenforce.rc && test ! -e /system/etc/init/voyahtune.setenforce.rc && sync"; then
        echo "  ПРЕДУПРЕЖДЕНИЕ: obsolete voyahtune.setenforce.rc не удалён; повторный setenforce идемпотентен."
    fi
    boot_hook_cleanup_snapshot
    echo "  Boot-hook установлен и проверен."
}

echo "=== Бэкап перезаписываемых файлов в $BACKUP_DIR/ ==="
backup_pull /data/local/bin/load.bin               load.bin || exit 1
backup_pull /data/local/bin/steeringwheelkeys.js   steeringwheelkeys.js || exit 1
backup_pull /data/local/bin/launcherdock.js        launcherdock.js || exit 1
backup_pull /data/local/bin/multidisplay.js        multidisplay.js || exit 1
backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js || exit 1
if ! backup_pull_with_absent /data/local/bin/apollo_tech.js apollo_tech.js; then
    echo "!!! Apollo backup не создан — установка прервана до перезаписи файла."
    exit 1
fi
backup_pull /data/local/bin/frida-inject           frida-inject || exit 1
backup_pull /system/priv-app/Native/Native.apk     Native.apk || exit 1
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml || exit 1

# ВАЖНО: всё в /data/local/bin доступно загрузочному RC-сервису.
# /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo "=== Frida-инфраструктура (руль + VirtualDisplay + dormant Apollo diagnostic) ==="
if ! adb shell "mkdir -p /data/local/bin"; then
    echo "!!! Не удалось подготовить /data/local/bin — установка прервана."
    exit 1
fi
install_required_data_file load.bin /data/local/bin/load.bin 755 || exit 1
install_required_data_file steeringwheelkeys.js /data/local/bin/steeringwheelkeys.js 644 || exit 1
install_required_data_file launcherdock.js /data/local/bin/launcherdock.js 644 || exit 1
install_required_data_file multidisplay.js /data/local/bin/multidisplay.js 644 || exit 1
install_required_data_file vd_bypass.js /data/local/bin/vd_bypass.js 644 || exit 1
install_required_data_file apollo_tech.js /data/local/bin/apollo_tech.js 644 || exit 1
install_required_data_file frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject 755 || exit 1

echo "=== Миграция boot-hook предыдущего full-релиза ==="
if ! migrate_legacy_init_logcat; then
    exit 1
fi

echo "=== Boot-хук: свои RC-сервисы (setenforce 0 + запуск load.bin) ==="
# /system уже сделан записываемым выше (verity → overlay); отдельный remount не нужен.
install_boot_hooks
BOOT_HOOK_INSTALL_STATUS=$?
if [ "$BOOT_HOOK_INSTALL_STATUS" -ne 0 ]; then
    # Возвращать legacy-hook безопасно только когда RC остался неизменённым или его rollback подтверждён.
    # При status=2 на устройстве может быть частично опубликованный RC-комплект: не создаём второй path.
    if [ "$BOOT_HOOK_INSTALL_STATUS" -eq 1 ]; then
        if boot_hook_final_state; then
            case "$BOOT_HOOK_FINAL_STATE" in
                ABSENT)
                    if ! rollback_legacy_init_logcat; then
                        echo "!!! Новый RC и legacy rollback не установлены. Не перезагружайте ГУ; повторите installer."
                    fi
                    ;;
                READY)
                    echo "  Предыдущий полный RC-комплект сохранён; legacy init.logcat.sh не возвращаем."
                    ;;
                PARTIAL)
                    echo "!!! После rollback остался неполный RC-комплект; legacy hook не возвращаем во избежание двух boot-path."
                    echo "    Не перезагружайте ГУ; повторите installer."
                    ;;
            esac
        else
            echo "!!! Не удалось проверить RC после rollback; legacy hook не возвращаем во избежание двух boot-path."
            echo "    Не перезагружайте ГУ; восстановите ADB и повторите installer."
        fi
    else
        echo "!!! RC rollback не подтверждён; чистый init.logcat.sh оставлен, чтобы не создать второй boot-hook."
        echo "    Не перезагружайте ГУ; восстановите соединение ADB и повторите installer."
    fi
    exit 1
fi
LEGACY_INIT_MIGRATED=0

# NB: install.sh — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
# остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
# (autoLight / wiperCold / floatingBack, читаются из его prefs) тоже сохраняются.
# Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.sh.
# Порядок для чистого baseline (после порчи от старого remove): remove.sh → install.sh.
echo "=== Native.apk в /system/priv-app ==="
if ! adb shell "mkdir -p /system/priv-app/Native && chmod 755 /system/priv-app/Native"; then
    echo "!!! Не удалось подготовить /system/priv-app/Native — установка прервана."
    exit 1
fi
install_required_system_file native.apk /system/priv-app/.Native.apk.voyahtune.new /system/priv-app/Native/Native.apk 644 || exit 1
adb shell "ls -all /system/priv-app/Native"

# Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
if ! adb shell "mkdir -p /system/etc/permissions"; then
    echo "!!! Не удалось подготовить /system/etc/permissions — установка прервана."
    exit 1
fi
install_required_system_file privapp-permissions-ru.big.town.anative.xml \
    /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new \
    /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644 || exit 1

# Включить power hold (leave car), если опция выключена или отсутствует
LEAVECAR=$(adb shell getprop persist.app.feature.leavecar | tr -d '\r')
if [ "$LEAVECAR" != "true" ]; then
    echo "Enabling leave car (power hold)..."
    adb shell setprop persist.app.feature.leavecar true
fi

# Freeform (окна resizable — нужно для VirtualDisplay-сплита; применяется после ребута)
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1

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

adb reboot
