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

mkdir -p $DIR/bin

echo "Compilling..."
for f in $DIR/*.js; do
    echo "  ##$f##"
    [ -f $f ] && frida-compile "$f" -B iife -c -S -T none -o "$DIR/bin/$(basename $f)"
done
echo "Done: ./bin"
