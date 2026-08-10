#!/bin/sh
# Host-side integration for the optional static Yandex DNS RRO.
# This file is sourced by install.sh/remove.sh from the flat release directory.

YDNS_APK_NAME="framework-res__config_ethernet_interfaces_yandexdns.apk"
YDNS_EXPECTED_SHA256="c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d"
YDNS_REMOTE_HELPER="/data/local/tmp/open_voyah_dns_overlay.sh"
YDNS_REMOTE_APK="/data/local/tmp/open_voyah_yandex_dns.apk"
YDNS_ADB="${YDNS_ADB:-adb}"

ydns_release_dir() {
    CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd
}

YDNS_RELEASE_DIR="$(ydns_release_dir)"
YDNS_APK="$YDNS_RELEASE_DIR/$YDNS_APK_NAME"
YDNS_DEVICE_HELPER="$YDNS_RELEASE_DIR/dns-overlay-device.sh"

ydns_host_hash() {
    ydns_hash_file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$ydns_hash_file" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$ydns_hash_file" | awk '{print $1}'
    else
        return 1
    fi
}

ydns_prepare_helper() {
    ydns_prepare_mode="${1:-install}"
    if ! command -v "$YDNS_ADB" >/dev/null 2>&1; then
        echo "!!! adb не найден в PATH." >&2
        return 1
    fi
    if [ ! -f "$YDNS_DEVICE_HELPER" ]; then
        echo "!!! Не найден $YDNS_DEVICE_HELPER." >&2
        return 1
    fi
    if [ "$ydns_prepare_mode" = "restore" ]; then
        return 0
    fi
    if [ ! -f "$YDNS_APK" ]; then
        echo "!!! Не найден $YDNS_APK." >&2
        return 1
    fi
    ydns_actual_hash="$(ydns_host_hash "$YDNS_APK")" || {
        echo "!!! Нет sha256sum/shasum для проверки DNS RRO APK." >&2
        return 1
    }
    if [ "$ydns_actual_hash" != "$YDNS_EXPECTED_SHA256" ]; then
        echo "!!! SHA-256 DNS RRO APK не совпадает с зафиксированным." >&2
        return 1
    fi
    return 0
}

ydns_push_device_helper() {
    "$YDNS_ADB" push "$YDNS_DEVICE_HELPER" "$YDNS_REMOTE_HELPER" >&2 || return 1
    "$YDNS_ADB" shell chmod 0700 "$YDNS_REMOTE_HELPER" >&2 || return 1
}

ydns_run_device() {
    ydns_action="$1"
    ydns_push_device_helper || return 1
    case "$ydns_action" in
        install)
            "$YDNS_ADB" remount >&2 || true
            "$YDNS_ADB" push "$YDNS_APK" "$YDNS_REMOTE_APK" >&2 || return 1
            "$YDNS_ADB" shell chmod 0600 "$YDNS_REMOTE_APK" >&2 || return 1
            "$YDNS_ADB" shell sh "$YDNS_REMOTE_HELPER" install
            ydns_rc=$?
            "$YDNS_ADB" shell rm -f "$YDNS_REMOTE_APK" >/dev/null 2>&1 || true
            return "$ydns_rc"
            ;;
        disable|restore)
            "$YDNS_ADB" remount >&2 || true
            "$YDNS_ADB" shell sh "$YDNS_REMOTE_HELPER" "$ydns_action"
            ;;
        status)
            "$YDNS_ADB" shell sh "$YDNS_REMOTE_HELPER" status
            ;;
        *) return 2 ;;
    esac
}

ydns_query_state() {
    ydns_run_device status
}

install_yandex_dns() {
    ydns_run_device install
}

disable_yandex_dns() {
    ydns_run_device disable
}

restore_yandex_dns() {
    ydns_run_device restore
}

ydns_restore_terminal() {
    if [ -n "${YDNS_STTY_OLD:-}" ]; then
        stty "$YDNS_STTY_OLD" >/dev/null 2>&1 || true
        YDNS_STTY_OLD=""
    fi
}

choose_yandex_dns() {
    ydns_current="$1"
    YDNS_REQUEST="keep"

    case "$ydns_current" in
        external)
            echo "Найден внешний DNS-overlay, не установленный Open Voyah; оставляем его без изменений."
            return 0
            ;;
        broken)
            echo "Состояние DNS-overlay неоднозначно; для безопасности оставляем его без изменений."
            return 0
            ;;
        on) ydns_selected="on" ;;
        off) ydns_selected="off" ;;
        *) return 1 ;;
    esac

    if [ ! -t 0 ] || [ ! -t 1 ]; then
        echo "Неинтерактивный запуск: состояние DNS-overlay не меняется."
        return 0
    fi
    if ! command -v stty >/dev/null 2>&1 || ! command -v dd >/dev/null 2>&1; then
        echo "Нет stty/dd для интерактивного меню: состояние DNS-overlay не меняется."
        return 0
    fi

    YDNS_STTY_OLD="$(stty -g)" || return 1
    trap 'ydns_restore_terminal; exit 130' HUP INT TERM
    stty -echo -icanon min 1 time 0 || {
        ydns_restore_terminal
        trap - HUP INT TERM
        return 1
    }

    while :; do
        if [ "$ydns_selected" = "on" ]; then
            printf '\r\033[KИспользовать DNS Yandex?  [Да]   Нет   (←/→, Enter)'
        else
            printf '\r\033[KИспользовать DNS Yandex?   Да   [Нет]  (←/→, Enter)'
        fi

        ydns_key="$(dd bs=1 count=1 2>/dev/null)"
        case "$ydns_key" in
            "$(printf '\033')")
                # Arrow keys send ESC + two bytes. A short VTIME prevents a lone Escape from
                # leaving the installer blocked forever waiting for bytes that will not arrive.
                stty min 0 time 1 || true
                ydns_tail="$(dd bs=2 count=1 2>/dev/null)"
                stty min 1 time 0 || true
                case "$ydns_tail" in
                    '[A'|'[D') ydns_selected="on" ;;
                    '[B'|'[C') ydns_selected="off" ;;
                esac
                ;;
            ''|"$(printf '\r')") break ;;
            y|Y) ydns_selected="on"; break ;;
            n|N) ydns_selected="off"; break ;;
        esac
    done

    ydns_restore_terminal
    trap - HUP INT TERM
    printf '\n'
    YDNS_REQUEST="$ydns_selected"
    return 0
}
