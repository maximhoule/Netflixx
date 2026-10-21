#!/usr/bin/env bash
# Same remote-control script on two builds, alternating Base / New twice, same emulator.
set -u
PKG=com.maxim.marquee
OUT=perf
mkdir -p "$OUT/frames"
summary() { echo "$*" | tee -a "$OUT/summary.txt"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.4; done; }
fast() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }
# A real held remote button: key down on the emulator's keyboard device, so Android sends its own
# auto-repeats (repeatCount > 0) exactly like a remote held down, then key up.
KBD=""
hold() { # hold LINUX_KEYCODE SECONDS
  [ -z "$KBD" ] && KBD=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/add device/{d=$4} /KEY_RIGHT/{print d; exit}')
  adb shell "sendevent $KBD 1 $1 1; sendevent $KBD 0 0 0; sleep $2; sendevent $KBD 1 $1 0; sendevent $KBD 0 0 0"
}
measure_start() { adb shell dumpsys gfxinfo $PKG reset >/dev/null; }
measure_end() {
  adb shell dumpsys gfxinfo $PKG framestats > "$OUT/frames/$1.txt"
  python3 .github/scripts/framestats.py "$1" "$OUT/frames/$1.txt" | tee -a "$OUT/summary.txt"
}

adb root >/dev/null; sleep 3; adb wait-for-device
adb shell settings put global stay_on_while_plugged_in 7
python3 .github/scripts/seed_prefs.py /tmp/marquee.xml /tmp/marquee_global.xml >/dev/null

seed() {
  APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
  adb shell mkdir -p /data/data/$PKG/shared_prefs
  adb push /tmp/marquee.xml /data/data/$PKG/shared_prefs/marquee.xml >/dev/null
  adb push /tmp/marquee_global.xml /data/data/$PKG/shared_prefs/marquee_global.xml >/dev/null
  adb shell chown -R "$APPUID:$APPUID" /data/data/$PKG/shared_prefs
  adb shell chmod 660 /data/data/$PKG/shared_prefs/marquee.xml /data/data/$PKG/shared_prefs/marquee_global.xml
  adb shell restorecon -R /data/data/$PKG/shared_prefs
}

browse() {
  measure_start
  key DPAD_DOWN; sleep 2; key DPAD_RIGHT DPAD_RIGHT; sleep 3
  for i in 1 2 3 4 5; do key DPAD_DOWN; sleep 2; done
  measure_end "$1 browse"
  measure_start
  fast DPAD_RIGHT 25; sleep 1; fast DPAD_DOWN 8; sleep 1; fast DPAD_UP 12; sleep 3
  measure_end "$1 held-keys"
  measure_start
  hold 106 3; sleep 1; hold 105 3; sleep 1; hold 108 2; sleep 1; hold 103 2; sleep 2
  measure_end "$1 real-hold"
  measure_start
  key DPAD_DOWN DPAD_DOWN DPAD_RIGHT; sleep 2; key DPAD_CENTER; sleep 6; key DPAD_DOWN; sleep 2; key BACK; sleep 3
  measure_end "$1 details"
}

run() {
  local v=$1 r=$2
  summary "== $v round $r"
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r "$v.apk" >/dev/null
  seed
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity | grep TotalTime | sed "s/^/$v first launch /" | tee -a "$OUT/summary.txt"
  sleep 18
  browse "$v r$r first-run"
  adb logcat -d | grep -E "Skipped [0-9]+ frames" | sed "s/^/$v r$r /" | tee -a "$OUT/summary.txt"
  adb shell cmd package compile -m speed-profile -f $PKG >/dev/null 2>&1
  adb shell am force-stop $PKG; sleep 2
  adb shell am start -W -n $PKG/.MainActivity | grep TotalTime | sed "s/^/$v compiled launch /" | tee -a "$OUT/summary.txt"
  sleep 12
  browse "$v r$r compiled"
}

for r in 1 2; do
  run Base $r
  run New $r
done
exit 0
