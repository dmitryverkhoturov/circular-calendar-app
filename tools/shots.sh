#!/bin/bash
# Usage: tools/shots.sh [OUT_DIR]
# Demo screenshots of every theme at several zoom levels (mock user, no real calendar).
# Needs a running emulator/device with the app installed. Also used as the visual
# pass of the release checklist (docs/RELEASING.md).
export MSYS_NO_PATHCONV=1
A=/c/Android/platform-tools/adb.exe
P=com.circularcalendar.app/.MainActivity
O="${1:-$(dirname "$0")/out/shots}"; mkdir -p "$O"

# Android demo mode status bar: 12:00, full battery and signal, no notifications.
$A shell settings put global sysui_demo_allowed 1
sb() { timeout 8 $A shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
sb enter; sb clock -e hhmm 1200; sb battery -e level 100 -e plugged false
sb network -e wifi show -e level 4 -e fully true; sb network -e mobile show -e datatype none -e level 4 -e fully true
sb notifications -e visible false

$A shell am start -n $P >/dev/null; sleep 4
$A shell am start -n $P --ez demo true >/dev/null; sleep 2

PRESETS="${PRESETS:-0:dusk 1:cartoon 2:paper 3:plum 4:ink 5:chalk 6:classic}"
# name:zoom:th:cx:cy[:rf[:orbits[:secs]]] — th = camera rotation (rad), cx/cy = circle centre in
# screen fractions; with cx/cy empty the screen centre goes to the "now" radius at rf
# (rf -N = label row N: 1 day, 2 hours, 3 minutes, 4 seconds), secs = date offset from now.
# "orbits" turns orbits on.
# The circle is always centred horizontally; R ≈ 480 px at zoom 1 on a 1080x2400 screen.
VIEWS="${VIEWS:-full:1.08:0:0.5:0.5 rise:2.6:0:0.5:0.85 month:7:0:0.5:1.7 side:2.4:-1.5708:1.25:0.5 diag:3.5:0.42:0.0:1.0 close:8:-0.32:1.6:2.0 days:22:1.476:::0.967::475200}"
# Rotation sequence, Dusk only: the same view turned step by step.
ROT="rot0:1.25:0:0.5:0.55 rot1:1.25:0.5:0.5:0.55 rot2:1.25:1.0:0.5:0.55"
for p in $PRESETS; do
  i=${p%%:*}; pn=${p#*:}
  $A shell am start -n $P --ei preset "$i" >/dev/null; sleep 2
  V="$VIEWS"; [ "$pn" = dusk ] && V="$VIEWS $ROT"
  for v in $V; do
    IFS=: read -r vn z th cx cy rf ex secs <<< "$v"
    [ "$ex" = orbits ] && { $A shell am start -n $P --ez orbits true >/dev/null; sleep 2; }
    if [ -n "$cx" ]; then
      $A shell am start -n $P --ef zoom "$z" --ef secs 0 --ef rf 0 --ef th "$th" --ef cx "$cx" --ef cy "$cy" >/dev/null
    else
      $A shell am start -n $P --ef zoom "$z" --ef secs "${secs:-0}" --ef rf "${rf:-1}" --ef th "$th" >/dev/null
    fi
    sleep 7
    sb enter; sb clock -e hhmm 1200; sleep 1
    $A exec-out screencap -p > "$O/${pn}_${vn}.png"
    echo "$O/${pn}_${vn}.png"
    [ "$ex" = orbits ] && $A shell am start -n $P --ez orbits false >/dev/null
  done
done
$A shell am start -n $P --ei preset 0 >/dev/null
sb exit
