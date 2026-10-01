#!/bin/bash
# Usage: seam.sh NAME [am-start extras for camera...]
# Tiled frame vs reference (dbg 512) frame -> diff stats + images.
export MSYS_NO_PATHCONV=1
A=/c/Android/platform-tools/adb.exe
S="${SEAM_OUT:-$(dirname "$0")/out}"; mkdir -p "$S"; D="$(dirname "$0")"
N=$1; shift
P=com.circularcalendar.app/.MainActivity
if [ $# -gt 0 ]; then $A shell am start -n $P "$@" >/dev/null 2>&1; sleep 3; fi
$A shell am start -n $P --ei dbg 0 >/dev/null 2>&1; sleep 6
$A exec-out screencap -p > $S/${N}_t.png
$A shell am start -n $P --ei dbg 512 >/dev/null 2>&1; sleep 4
$A exec-out screencap -p > $S/${N}_r.png
$A shell am start -n $P --ei dbg 0 >/dev/null 2>&1
python "$D/seam_diff.py" "$S/${N}_t.png" "$S/${N}_r.png" "$S/${N}_d.png"
