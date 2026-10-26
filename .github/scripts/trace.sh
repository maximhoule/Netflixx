#!/usr/bin/env bash
# Records a Perfetto trace of the app while it's driven like a remote (held arrows, tabs, a show
# page), then trace_analysis.py explains what the slow frames spent their time on.
set -u
PKG=com.maxim.marquee
OUT=trace
mkdir -p "$OUT"
# Step log (kept with the results): which step ran when, and whether the device and app were up.
step() { echo "$(date +%T) $* | device: $(timeout 10 adb get-state 2>&1) | app pid: $(timeout 10 adb shell pidof $PKG 2>&1)" >> "$OUT/steps.txt"; }
trap 'step "script end"; timeout 30 adb logcat -d > "$OUT/logcat.txt" 2>&1' EXIT
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.4; done; }
fast() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }
# The menu is a bar along the top: Home, Series, Movies, Sports, My List, Settings, Search, Profile.
# (Numbers kept from the old side menu: 0 profile, 1 search, 2 home, 3 sports, 4 series, 5 movies, 6 my list, 7 settings.)
tab() {
  case "$1" in 2) n=0;; 4) n=1;; 5) n=2;; 3) n=3;; 6) n=4;; 7) n=5;; 1) n=6;; 0) n=7;; *) n=0;; esac
  fast DPAD_UP 60; sleep 0.6; fast DPAD_LEFT 10; [ "$n" -gt 0 ] && fast DPAD_RIGHT "$n"; key DPAD_CENTER
}
KBD=""
hold() { # hold LINUX_KEYCODE SECONDS: a real held button (Android auto-repeats it)
  [ -z "$KBD" ] && KBD=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/add device/{d=$4} /KEY_RIGHT/{print d; exit}')
  adb shell "sendevent $KBD 1 $1 1; sendevent $KBD 0 0 0; sleep $2; sendevent $KBD 1 $1 0; sendevent $KBD 0 0 0"
}
RIGHT=106; LEFT=105; DOWN=108; UP=103

adb root >/dev/null; sleep 3; adb wait-for-device
adb shell settings put global stay_on_while_plugged_in 7
adb install -r Marquee.apk >/dev/null
python3 .github/scripts/seed_prefs.py /tmp/marquee.xml /tmp/marquee_global.xml
APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell mkdir -p /data/data/$PKG/shared_prefs
adb push /tmp/marquee.xml /data/data/$PKG/shared_prefs/marquee.xml >/dev/null
adb push /tmp/marquee_global.xml /data/data/$PKG/shared_prefs/marquee_global.xml >/dev/null
adb shell chown -R "$APPUID:$APPUID" /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/marquee.xml /data/data/$PKG/shared_prefs/marquee_global.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs

step "Warm up once"
# Warm up once (caches filled, like a box that's been used), then compile like the TV does.
adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 20
key DPAD_DOWN; hold $RIGHT 2; hold $DOWN 2; hold $UP 3
adb shell cmd package compile -m speed-profile -f $PKG >/dev/null 2>&1
adb shell am force-stop $PKG; sleep 2

record() { # record NAME SECONDS: starts a trace in the background
  adb shell rm -f /data/misc/perfetto-traces/$1.pftrace
  adb shell perfetto --background -o /data/misc/perfetto-traces/$1.pftrace -t ${2}s -b 64mb \
    -a $PKG sched freq gfx view input am dalvik binder_driver >/dev/null 2>&1
  sleep 1.5
}
finish() { sleep "$2"; adb pull /data/misc/perfetto-traces/$1.pftrace "$OUT/$1.pftrace" >/dev/null 2>&1; }

step "1) Launch"
# 1) Launch to Home.
record launch 14
adb shell am start -n $PKG/.MainActivity >/dev/null
finish launch 14
sleep 4

step "2) Held"
# 2) Held arrows on Home: right along a row, back left, down the rows, up again.
record home_hold 26
key DPAD_DOWN; sleep 1
hold $RIGHT 4; sleep 1; hold $LEFT 3; sleep 1; hold $DOWN 3; sleep 1; hold $RIGHT 3; sleep 1; hold $UP 4
finish home_hold 4

step "3) Single"
# 3) Single presses at a steady pace (like browsing by hand).
record home_taps 22
for i in 1 2 3 4 5 6 7 8; do key DPAD_RIGHT; sleep 0.35; done
for i in 1 2 3 4; do key DPAD_DOWN; sleep 0.6; done
for i in 1 2 3 4 5 6; do key DPAD_LEFT; sleep 0.35; done
for i in 1 2 3 4; do key DPAD_UP; sleep 0.6; done
finish home_taps 3

step "4) Tabs"
# 4) Tabs: Series, Movies, Sports, Home, with a held arrow on each.
record tabs 30
tab 4; sleep 2; key DPAD_DOWN; hold $RIGHT 2
tab 5; sleep 2; key DPAD_DOWN; hold $RIGHT 2
tab 3; sleep 2; key DPAD_DOWN; hold $RIGHT 2
tab 2; sleep 2
finish tabs 4

step "5) A show"
# 5) A show page: open, scroll episodes, back.
record details 22
key DPAD_DOWN; sleep 1; key DPAD_CENTER; sleep 5
key DPAD_DOWN; sleep 1; hold $RIGHT 2; key DPAD_DOWN; sleep 1; hold $RIGHT 2; sleep 1
key BACK; sleep 3
finish details 4

ls -la "$OUT"
exit 0
