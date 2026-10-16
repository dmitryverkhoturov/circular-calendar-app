#!/bin/bash
# Usage: tools/shots.sh [OUT_DIR]
# Demo screenshots of every theme at several zoom levels (mock user, no real calendar).
# Needs a running emulator/device with the app installed. Also used as the visual
# pass of the release checklist (docs/RELEASING.md).
export MSYS_NO_PATHCONV=1
A=/c/Android/platform-tools/adb.exe
P=com.circularcalendar.app/.MainActivity
O="${1:-$(dirname "$0")/out/shots}"; mkdir -p "$O"

# Clean status bar: 9:41, full battery, no notifications.
$A shell settings put global sysui_demo_allowed 1
sb() { timeout 8 $A shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
sb enter; sb clock -e hhmm 0941; sb battery -e level 100 -e plugged false
sb network -e wifi show -e level 4; sb network -e mobile show -e datatype none -e level 4
sb notifications -e visible false

$A shell am start -n $P >/dev/null; sleep 4
$A shell am start -n $P --ez demo true >/dev/null; sleep 2

PRESETS="${PRESETS:-0:dusk 1:cartoon 2:paper 3:plum 4:ink 5:chalk 6:classic}"
# name:zoom:rf:th — rf = where the screen centre sits on the "now" radius (1 = rim),
# th = camera rotation in radians (the circle can be turned with two fingers).
VIEWS="${VIEWS:-full:1:0:0 half:2.1:0.31:0 month:7:0.84:0 week:30:0.965:0 tilt:1.8:0.35:0.55 spin:5:0.8:-0.7}"
# Rotation sequence, Dusk only: the same view turned step by step.
ROT="rot0:1.6:0.12:0 rot1:1.6:0.12:0.8 rot2:1.6:0.12:1.6"
for p in $PRESETS; do
  i=${p%%:*}; pn=${p#*:}
  $A shell am start -n $P --ei preset "$i" >/dev/null; sleep 2
  V="$VIEWS"; [ "$pn" = dusk ] && V="$VIEWS $ROT"
  for v in $V; do
    IFS=: read -r vn z f th <<< "$v"
    $A shell am start -n $P --ef zoom "$z" --ef secs 0 --ef rf "$f" --ef th "${th:-0}" >/dev/null; sleep 7
    sb enter; sb clock -e hhmm 0941; sleep 1
    $A exec-out screencap -p > "$O/${pn}_${vn}.png"
    echo "$O/${pn}_${vn}.png"
  done
done
$A shell am start -n $P --ei preset 0 >/dev/null
sb exit
