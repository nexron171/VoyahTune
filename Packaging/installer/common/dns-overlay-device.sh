#!/system/bin/sh
# Device-side transaction helper for the optional static Yandex DNS RRO.
# It never reboots Android. The host installer performs the one final reboot.

umask 077

TARGET="/vendor/overlay/framework-res__config_ethernet_interfaces_yandexdns.apk"
VENDOR_DIR="/vendor"
OVERLAY_DIR="/vendor/overlay"
OVERLAY_CONFIG="$OVERLAY_DIR/config/config.xml"
PACKAGE="dev.dt2.qgyandexdns"
EXPECTED_SHA256="c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d"
INCOMING="/data/local/tmp/open_voyah_yandex_dns.apk"
STATE_ROOT="/data/local/open_voyah"
STATE_DIR="$STATE_ROOT/qgdns"
STATE_FILE="$STATE_DIR/state"
ORIGINAL_APK="$STATE_DIR/original.apk"
LOCK_DIR="$STATE_ROOT/qgdns.lock"
SCHEMA="1"
TEMP_PATH=""
LOCK_HELD="0"

say() {
    printf '%s\n' "$*" >&2
}

fail() {
    say "DNS-overlay: $*"
    return 1
}

cleanup() {
    if [ -n "$TEMP_PATH" ]; then
        rm -f "$TEMP_PATH" >/dev/null 2>&1
        TEMP_PATH=""
    fi
    if [ "$LOCK_HELD" = "1" ]; then
        rm -rf "$LOCK_DIR" >/dev/null 2>&1
        LOCK_HELD="0"
    fi
}

trap cleanup EXIT
trap 'cleanup; exit 130' HUP INT TERM

hash_file() {
    file="$1"
    [ -f "$file" ] && [ -r "$file" ] || return 1
    if command -v sha256sum >/dev/null 2>&1; then
        result="$(sha256sum "$file" 2>/dev/null | awk '{print $1}')"
    elif command -v toybox >/dev/null 2>&1; then
        result="$(toybox sha256sum "$file" 2>/dev/null | awk '{print $1}')"
    else
        return 1
    fi
    [ -n "$result" ] || return 1
    printf '%s\n' "$result"
}

valid_sha256() {
    candidate="$1"
    [ "${#candidate}" -eq 64 ] || return 1
    case "$candidate" in *[!0-9a-f]*) return 1 ;; esac
    return 0
}

acquire_lock() {
    mkdir -p "$STATE_ROOT" || return 1
    chmod 0700 "$STATE_ROOT" || return 1
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        LOCK_HELD="1"
        printf '%s\n' "$$" > "$LOCK_DIR/pid" || return 1
        return 0
    fi

    lock_pid="$(sed -n '1p' "$LOCK_DIR/pid" 2>/dev/null || true)"
    if [ -n "$lock_pid" ] && kill -0 "$lock_pid" 2>/dev/null; then
        fail "другая операция DNS-overlay уже выполняется (PID $lock_pid)"
        return 1
    fi
    rm -rf "$LOCK_DIR" || return 1
    mkdir "$LOCK_DIR" || return 1
    LOCK_HELD="1"
    printf '%s\n' "$$" > "$LOCK_DIR/pid" || return 1
    return 0
}

release_lock() {
    if [ "$LOCK_HELD" = "1" ]; then
        rm -rf "$LOCK_DIR" || return 1
        LOCK_HELD="0"
    fi
    return 0
}

target_hash() {
    [ -f "$TARGET" ] || return 1
    hash_file "$TARGET"
}

reset_state_vars() {
    STATE_SCHEMA=""
    STATE_ORIGIN=""
    STATE_ACTIVE=""
    STATE_INSTALLED_SHA256=""
    STATE_ORIGINAL_SHA256=""
}

load_state() {
    reset_state_vars
    [ -f "$STATE_FILE" ] || return 1

    while IFS='=' read -r key value; do
        case "$key" in
            schema) STATE_SCHEMA="$value" ;;
            origin) STATE_ORIGIN="$value" ;;
            active) STATE_ACTIVE="$value" ;;
            installed_sha256) STATE_INSTALLED_SHA256="$value" ;;
            original_sha256) STATE_ORIGINAL_SHA256="$value" ;;
        esac
    done < "$STATE_FILE"

    [ "$STATE_SCHEMA" = "$SCHEMA" ] || return 2
    case "$STATE_ORIGIN" in absent|present) ;; *) return 2 ;; esac
    case "$STATE_ACTIVE" in 0|1) ;; *) return 2 ;; esac
    valid_sha256 "$STATE_INSTALLED_SHA256" || return 2
    if [ "$STATE_ORIGIN" = "present" ]; then
        [ -f "$ORIGINAL_APK" ] || return 2
        [ -n "$STATE_ORIGINAL_SHA256" ] || return 2
        [ "$(hash_file "$ORIGINAL_APK")" = "$STATE_ORIGINAL_SHA256" ] || return 2
    else
        [ "$STATE_ORIGINAL_SHA256" = "-" ] || return 2
    fi
    return 0
}

write_state() {
    new_active="$1"
    mkdir -p "$STATE_DIR" || return 1
    chmod 0700 "$STATE_DIR" || return 1
    TEMP_PATH="$STATE_DIR/state.tmp.$$"
    {
        printf 'schema=%s\n' "$SCHEMA"
        printf 'origin=%s\n' "$STATE_ORIGIN"
        printf 'active=%s\n' "$new_active"
        printf 'installed_sha256=%s\n' "$STATE_INSTALLED_SHA256"
        printf 'original_sha256=%s\n' "$STATE_ORIGINAL_SHA256"
    } > "$TEMP_PATH" || return 1
    chmod 0600 "$TEMP_PATH" || return 1
    mv -f "$TEMP_PATH" "$STATE_FILE" || return 1
    TEMP_PATH=""
    STATE_ACTIVE="$new_active"
    return 0
}

ensure_vendor_writable() {
    if [ ! -d "$OVERLAY_DIR" ]; then
        TEMP_PATH="$VENDOR_DIR/.open_voyah_qgdns_rwtest.$$"
        : > "$TEMP_PATH" 2>/dev/null || {
            TEMP_PATH=""
            fail "/vendor недоступен для записи"
            return 1
        }
        rm -f "$TEMP_PATH" || return 1
        TEMP_PATH=""
        mkdir -p "$OVERLAY_DIR" || return 1
        chown 0:0 "$OVERLAY_DIR" || return 1
        chmod 0755 "$OVERLAY_DIR" || return 1
        restorecon "$OVERLAY_DIR" >/dev/null 2>&1 || true
    fi

    TEMP_PATH="$OVERLAY_DIR/.open_voyah_qgdns_rwtest.$$"
    : > "$TEMP_PATH" 2>/dev/null || {
        TEMP_PATH=""
        fail "$OVERLAY_DIR недоступен для записи"
        return 1
    }
    rm -f "$TEMP_PATH" || return 1
    TEMP_PATH=""
    return 0
}

finalize_target() {
    expected_hash="$1"
    [ -f "$TARGET" ] || return 1
    chown 0:0 "$TARGET" || return 1
    chmod 0644 "$TARGET" || return 1
    restorecon "$TARGET" >/dev/null 2>&1 || return 1
    [ "$(target_hash)" = "$expected_hash" ] || return 1
    sync
    return 0
}

atomic_copy() {
    source_file="$1"
    expected_hash="$2"
    [ -f "$source_file" ] || return 1
    [ "$(hash_file "$source_file")" = "$expected_hash" ] || return 1

    TEMP_PATH="$OVERLAY_DIR/.open_voyah_qgdns.stage.$$"
    cp "$source_file" "$TEMP_PATH" || return 1
    [ "$(hash_file "$TEMP_PATH")" = "$expected_hash" ] || return 1
    chown 0:0 "$TEMP_PATH" || return 1
    chmod 0644 "$TEMP_PATH" || return 1
    mv -f "$TEMP_PATH" "$TARGET" || return 1
    TEMP_PATH=""
    finalize_target "$expected_hash"
}

check_duplicate_package() {
    package_paths="$(pm path "$PACKAGE" 2>/dev/null | tr -d '\r')"
    [ -z "$package_paths" ] && return 0
    [ "$package_paths" = "package:$TARGET" ] && return 0
    fail "$PACKAGE уже найден в другом месте: $package_paths"
}

check_tbox_layout() {
    command -v ip >/dev/null 2>&1 || {
        fail "утилита ip недоступна — нельзя проверить конфигурацию T-Box link"
        return 1
    }
    tbox_addresses="$(ip -4 addr show dev eth0 2>/dev/null || ip addr show dev eth0 2>/dev/null)"
    for required_address in 172.16.104.40/24 172.16.110.40/24 172.16.120.40/24; do
        printf '%s\n' "$tbox_addresses" | grep -F "$required_address" >/dev/null 2>&1 || {
            fail "текущий eth0 не содержит $required_address; полный RRO для этой прошивки не применяется"
            return 1
        }
    done
    return 0
}

overlay_manager_reports_error() {
    command -v cmd >/dev/null 2>&1 || return 1
    overlay_dump="$(cmd overlay dump "$PACKAGE" 2>/dev/null || true)"
    case "$overlay_dump" in
        *STATE_NO_IDMAP*|*STATE_MISSING_TARGET*|*STATE_DISABLED*) return 0 ;;
    esac
    return 1
}

initialize_state() {
    mkdir -p "$STATE_DIR" || return 1
    chmod 0700 "$STATE_DIR" || return 1
    STATE_ACTIVE="0"
    STATE_INSTALLED_SHA256="$EXPECTED_SHA256"

    if [ -f "$TARGET" ]; then
        STATE_ORIGIN="present"
        STATE_ORIGINAL_SHA256="$(target_hash)" || return 1
        TEMP_PATH="$STATE_DIR/original.tmp.$$"
        cp "$TARGET" "$TEMP_PATH" || return 1
        [ "$(hash_file "$TEMP_PATH")" = "$STATE_ORIGINAL_SHA256" ] || return 1
        chmod 0600 "$TEMP_PATH" || return 1
        mv -f "$TEMP_PATH" "$ORIGINAL_APK" || return 1
        TEMP_PATH=""
    else
        STATE_ORIGIN="absent"
        STATE_ORIGINAL_SHA256="-"
        rm -f "$ORIGINAL_APK" || return 1
    fi
    write_state 0
}

rollback_to_origin() {
    if [ "$STATE_ORIGIN" = "present" ]; then
        atomic_copy "$ORIGINAL_APK" "$STATE_ORIGINAL_SHA256" || return 1
    else
        rm -f "$TARGET" || return 1
        sync
    fi
    write_state 0
}

status_overlay() {
    if load_state; then
        :
    else
        rc=$?
        if [ "$rc" -eq 2 ]; then
            printf 'broken\n'
        elif [ -e "$TARGET" ]; then
            printf 'external\n'
        else
            printf 'off\n'
        fi
        return 0
    fi

    current_hash="$(target_hash 2>/dev/null || true)"
    if [ "$STATE_ACTIVE" = "1" ]; then
        if [ -z "$current_hash" ]; then
            # Typical after OTA/adb-overlayfs cleanup: state remains in /data, vendor file is gone.
            printf 'off\n'
        elif { [ "$current_hash" = "$STATE_INSTALLED_SHA256" ] || [ "$current_hash" = "$EXPECTED_SHA256" ]; } && [ ! -f "$OVERLAY_CONFIG" ] && ! overlay_manager_reports_error; then
            printf 'on\n'
        else
            printf 'broken\n'
        fi
        return 0
    fi

    if [ -z "$current_hash" ]; then
        printf 'off\n'
    elif { [ "$current_hash" = "$STATE_INSTALLED_SHA256" ] || [ "$current_hash" = "$EXPECTED_SHA256" ]; } && [ ! -f "$OVERLAY_CONFIG" ]; then
        # Recoverable interrupted install/disable: the managed bytes are still in place.
        printf 'on\n'
    else
        printf 'broken\n'
    fi
}

install_overlay() {
    android_sdk="$(getprop ro.build.version.sdk 2>/dev/null || true)"
    [ "$android_sdk" = "30" ] || {
        fail "поддерживается только проверенная база Android 11 / API 30 (на устройстве API $android_sdk)"
        return 1
    }
    [ -f "$INCOMING" ] || {
        fail "не найден входной APK $INCOMING"
        return 1
    }
    [ "$(hash_file "$INCOMING")" = "$EXPECTED_SHA256" ] || {
        fail "SHA-256 входного APK не совпадает с зафиксированным"
        return 1
    }
    [ ! -f "$OVERLAY_CONFIG" ] || {
        fail "обнаружен $OVERLAY_CONFIG; без явной записи overlay в OEM config установка небезопасна"
        return 1
    }
    check_tbox_layout || return 1
    check_duplicate_package || return 1
    ensure_vendor_writable || return 1

    if load_state; then
        current_hash="$(target_hash 2>/dev/null || true)"
        if [ "$STATE_ACTIVE" = "1" ]; then
            if [ -z "$current_hash" ]; then
                # Vendor overlay disappeared (for example after OTA); turn the stale state into a
                # normal inactive state and continue with a fresh install.
                write_state 0 || return 1
            elif [ "$current_hash" = "$EXPECTED_SHA256" ]; then
                STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
                finalize_target "$EXPECTED_SHA256" || return 1
                write_state 1 || return 1
                say "DNS-overlay уже установлен Open Voyah."
                return 0
            elif [ "$current_hash" != "$STATE_INSTALLED_SHA256" ]; then
                fail "установленный APK изменён вне Open Voyah"
                return 1
            fi
        fi
        if [ "$STATE_ACTIVE" = "0" ] && [ -n "$current_hash" ] && [ "$current_hash" != "$STATE_ORIGINAL_SHA256" ]; then
            if [ "$current_hash" = "$EXPECTED_SHA256" ]; then
                STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
                finalize_target "$EXPECTED_SHA256" || return 1
                write_state 1 || return 1
                say "DNS-overlay: завершено восстановление прерванной установки."
                return 0
            fi
            [ "$current_hash" = "$STATE_INSTALLED_SHA256" ] || {
                fail "целевой файл изменён вне Open Voyah"
                return 1
            }
        fi
    else
        rc=$?
        [ "$rc" -eq 1 ] || {
            fail "повреждено сохранённое состояние $STATE_FILE"
            return 1
        }
        initialize_state || {
            fail "не удалось сохранить исходное состояние"
            return 1
        }
    fi

    before_hash="$(target_hash 2>/dev/null || true)"
    if ! atomic_copy "$INCOMING" "$EXPECTED_SHA256"; then
        after_hash="$(target_hash 2>/dev/null || true)"
        if [ "$after_hash" = "$EXPECTED_SHA256" ]; then
            STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
            if finalize_target "$EXPECTED_SHA256" && write_state 1; then
                say "DNS-overlay: завершена прерванная атомарная запись."
                return 0
            fi
            fail "APK записан, но его metadata/state не зафиксированы; повторите installer"
            return 1
        fi
        if [ -n "$before_hash" ] && [ "$after_hash" = "$before_hash" ]; then
            fail "не удалось обновить APK; предыдущая управляемая версия сохранена"
            return 1
        fi
        rollback_to_origin >/dev/null 2>&1 || true
        fail "не удалось атомарно записать APK в $TARGET"
        return 1
    fi
    STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
    if ! write_state 1; then
        # Bytes are already valid and recoverable: a repeated install recognizes EXPECTED_SHA256
        # even if the previous state file still contains an older pinned hash.
        fail "APK записан, но состояние не зафиксировано; повторите installer до перезагрузки"
        return 1
    fi
    say "DNS-overlay установлен; он вступит в силу после общей перезагрузки."
    return 0
}

disable_overlay() {
    if load_state; then
        :
    else
        rc=$?
        if [ "$rc" -eq 1 ]; then
            if [ -e "$TARGET" ]; then
                say "Найден внешний DNS-overlay — оставляем его без изменений."
            else
                say "DNS-overlay Open Voyah не установлен."
            fi
            return 0
        fi
        fail "повреждено сохранённое состояние $STATE_FILE"
        return 1
    fi
    current_hash="$(target_hash 2>/dev/null || true)"
    if [ "$STATE_ACTIVE" = "0" ] && [ -z "$current_hash" ]; then
        say "DNS-overlay Open Voyah уже выключен."
        return 0
    fi

    if [ -n "$current_hash" ] && [ "$current_hash" != "$STATE_INSTALLED_SHA256" ] && [ "$current_hash" != "$EXPECTED_SHA256" ]; then
        fail "целевой APK изменён вне Open Voyah; удаление отменено"
        return 1
    fi
    if [ "$current_hash" = "$EXPECTED_SHA256" ]; then
        STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
    fi
    # Commit the recoverable inactive intent first. A crash after this point is represented as
    # active=0 + managed target and can be resumed by either install or disable.
    write_state 0 || return 1
    [ -n "$current_hash" ] || {
        say "DNS-overlay Open Voyah выключен; vendor overlay уже отсутствовал."
        return 0
    }
    ensure_vendor_writable || return 1
    if ! rm -f "$TARGET" || [ -e "$TARGET" ]; then
        write_state 1 >/dev/null 2>&1 || true
        fail "не удалось удалить управляемый APK"
        return 1
    fi
    sync
    say "DNS-overlay Open Voyah выключен; штатные ресурсы вернутся после общей перезагрузки."
}

restore_overlay() {
    if load_state; then
        :
    else
        rc=$?
        if [ "$rc" -eq 1 ]; then
            say "Состояния DNS-overlay Open Voyah нет — откатывать нечего."
            return 0
        fi
        fail "повреждено сохранённое состояние $STATE_FILE"
        return 1
    fi

    current_hash="$(target_hash 2>/dev/null || true)"
    if [ "$STATE_ORIGIN" = "present" ] && [ "$current_hash" = "$STATE_ORIGINAL_SHA256" ]; then
        ensure_vendor_writable || return 1
        finalize_target "$STATE_ORIGINAL_SHA256" || return 1
        rm -rf "$STATE_DIR" || return 1
        say "Исходный DNS-overlay уже восстановлен."
        return 0
    fi
    if [ -n "$current_hash" ] && [ "$current_hash" != "$STATE_INSTALLED_SHA256" ] && [ "$current_hash" != "$EXPECTED_SHA256" ]; then
        fail "неизвестный файл появился при выключенном DNS-overlay; откат отменён"
        return 1
    fi

    if [ "$current_hash" = "$EXPECTED_SHA256" ]; then
        STATE_INSTALLED_SHA256="$EXPECTED_SHA256"
    fi
    write_state 0 || return 1
    if [ "$STATE_ORIGIN" = "present" ]; then
        ensure_vendor_writable || return 1
        atomic_copy "$ORIGINAL_APK" "$STATE_ORIGINAL_SHA256" || {
            fail "не удалось восстановить исходный APK"
            return 1
        }
    else
        if [ -n "$current_hash" ]; then
            ensure_vendor_writable || return 1
            rm -f "$TARGET" || return 1
            [ ! -e "$TARGET" ] || return 1
        fi
        sync
    fi
    rm -rf "$STATE_DIR" || return 1
    say "Исходное состояние DNS-overlay восстановлено."
}

run_locked_action() {
    action_function="$1"
    acquire_lock || return 1
    "$action_function"
    action_rc=$?
    if ! release_lock && [ "$action_rc" -eq 0 ]; then
        action_rc=1
    fi
    return "$action_rc"
}

if [ "${YDNS_DEVICE_HELPER_LIBRARY:-0}" != "1" ]; then
    case "${1:-}" in
        status)  status_overlay ;;
        install) run_locked_action install_overlay ;;
        disable) run_locked_action disable_overlay ;;
        restore) run_locked_action restore_overlay ;;
        *)
            say "Использование: $0 {status|install|disable|restore}"
            exit 2
            ;;
    esac
fi
