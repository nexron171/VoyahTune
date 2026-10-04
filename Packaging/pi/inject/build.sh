#!/bin/sh
# Сборка инъекционных агентов для frida-inject 16.2.1 (android-arm64, Android 11).
#
# -B iife  ОБЯЗАТЕЛЬНО: frida-compile 17 по умолчанию пишет bundle-формат Frida 17
#          (заголовок "📦" + таблица файлов), который GumJS 16.2.1 на голове не парсит.
#          iife даёт один самодостаточный скрипт, понятный обоим рантаймам.
# -c       минификация: launcherdock 112K -> 31K, меньше parse на cold boot внутри
#          30-секундного timeout в load.bin. Строковые литералы (ready-маркеры,
#          имена Java-классов, ключи Settings.Global) не изменяются.
# -S       без inline source-map (+57K base64 на launcherdock). Для отладочной
#          сборки уберите -S — стеки исключений в logcat станут читаемыми.
# -T none  исходники — чистый JS; флаг страхует от случайного TS-прохода.
set -e

DIR=$1
echo "*$DIR*"

# Python frida-compile 17.x поддерживает -B/-T; npm frida-compile 16.2.1 — нет.
# Если в PATH стоит npm-версия, явно берём python-бинарь.
FC=frida-compile
if ! "$FC" --help 2>&1 | grep -q -- "-B, --bundle-format"; then
    for cand in /usr/local/bin/frida-compile "$HOME/.local/bin/frida-compile"; do
        if [ -x "$cand" ] && "$cand" --help 2>&1 | grep -q -- "-B, --bundle-format"; then
            FC="$cand"
            break
        fi
    done
fi
echo "Using frida-compile: $FC"

mkdir -p $DIR/bin

echo "Compilling..."
for f in $DIR/*.js; do
    echo "  ##$f##"
    [ -f "$f" ] && "$FC" "$f" -B iife -c -S -T none -o "$DIR/bin/$(basename "$f")"
done
echo "Done: ./bin"
