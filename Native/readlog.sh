#!/bin/bash
FN=$1

adb logcat *:D -b all > $FN &

sleep 2 &&
kill `pgrep -f 'adb logcat'`

ALL=` wc -l $FN | cut -d' ' -f1`

FLINE=`grep -n "$FN" "$FN" | head -n1 | cut -d':' -f1`

let NUMLINES=$((ALL - FLINE))
echo "ALL =" $ALL
echo "FLINE =" $FLINE
echo "NUMLINES =" $NUMLINES

tail -n $NUMLINES $FN > $FN.filter
