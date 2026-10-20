#!/usr/bin/env bash
# Drives Marquee like a TV remote and records what happens. Never stops early: every step runs,
# and problems are collected in emu/summary.txt.
set -u
PKG=com.maxim.marquee
OUT=emu
mkdir -p "$OUT/shots"
n=0
summary() { echo "$*" | tee -a "$OUT/summary.txt"; }

alive() {
  if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
    summary "APP NOT RUNNING after step: $1"
  fi
}
shot() {
  n=$((n + 1))
  adb exec-out screencap -p > "$(printf '%s/shots/%02d_%s.png' "$OUT" "$n" "$1")"
  alive "$1"
}
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.4; done; }
fast() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }

adb root >/dev/null; sleep 3; adb wait-for-device
adb shell settings put global stay_on_while_plugged_in 7
adb shell svc power stayon true

summary "== install"
adb install -r Marquee.apk 2>&1 | tail -1 | tee -a "$OUT/summary.txt"
python3 .github/scripts/seed_prefs.py /tmp/marquee.xml /tmp/marquee_global.xml | tee -a "$OUT/summary.txt"
APPUID=$(adb shell stat -c %u /data/data/$PKG | tr -d '\r')
adb shell mkdir -p /data/data/$PKG/shared_prefs
adb push /tmp/marquee.xml /data/data/$PKG/shared_prefs/marquee.xml >/dev/null
adb push /tmp/marquee_global.xml /data/data/$PKG/shared_prefs/marquee_global.xml >/dev/null
adb shell chown -R "$APPUID:$APPUID" /data/data/$PKG/shared_prefs
adb shell chmod 660 /data/data/$PKG/shared_prefs/marquee.xml /data/data/$PKG/shared_prefs/marquee_global.xml
adb shell restorecon -R /data/data/$PKG/shared_prefs

adb logcat -c
summary "== cold start"
adb shell am start -W -n $PKG/.MainActivity | grep -E "TotalTime|WaitTime" | tee -a "$OUT/summary.txt"
sleep 3; shot start_3s
sleep 15; shot home
adb shell dumpsys gfxinfo $PKG reset >/dev/null

summary "== browse rows"
key DPAD_DOWN; sleep 2; shot row1
key DPAD_RIGHT DPAD_RIGHT; sleep 3; shot row1_rest_expanded
for i in 1 2 3 4 5; do key DPAD_DOWN; sleep 2.5; shot "scroll_down_$i"; done
fast DPAD_RIGHT 25; sleep 2; shot fast_right
fast DPAD_DOWN 8; sleep 2; shot fast_down
fast DPAD_UP 12; sleep 3; shot back_to_top
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo_browse.txt"

summary "== details"
key DPAD_DOWN DPAD_RIGHT; sleep 2
key DPAD_CENTER; sleep 7; shot detail
key DPAD_DOWN; sleep 2; shot detail_down
key DPAD_DOWN DPAD_DOWN; sleep 3; shot detail_more
key BACK; sleep 3; shot back_from_detail

summary "== play without a stream addon (should show the No sources screen)"
key DPAD_CENTER; sleep 6
key DPAD_CENTER; sleep 25; shot play_no_sources
key BACK; sleep 2; key BACK; sleep 2; shot after_problem

summary "== long press menu"
adb shell input keyevent --longpress DPAD_CENTER; sleep 2; shot long_press_menu
key BACK; sleep 1

summary "== search"
fast DPAD_LEFT 8; sleep 1; shot rail_open
key DPAD_UP DPAD_CENTER; sleep 3; shot search
key DPAD_RIGHT DPAD_CENTER DPAD_LEFT DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_CENTER
sleep 15; shot search_results_bat

summary "== settings"
fast DPAD_LEFT 4; sleep 1
fast DPAD_DOWN 5; key DPAD_CENTER; sleep 3; shot settings
fast DPAD_DOWN 10; sleep 2; shot settings_lower
fast DPAD_DOWN 12; sleep 2; shot settings_bottom

summary "== series and movies tabs"
fast DPAD_LEFT 3; sleep 1; fast DPAD_UP 3; key DPAD_CENTER; sleep 8; shot series_tab
fast DPAD_LEFT 3; sleep 1; key DPAD_DOWN DPAD_CENTER; sleep 8; shot movies_tab
fast DPAD_LEFT 3; sleep 1; key DPAD_DOWN DPAD_CENTER; sleep 3; shot my_list

summary "== profiles"
fast DPAD_LEFT 3; sleep 1; fast DPAD_UP 6; key DPAD_CENTER; sleep 3; shot profiles
key DPAD_RIGHT DPAD_CENTER; sleep 3; shot profile_add
key BACK; sleep 2; shot profiles_after_cancel
key DPAD_DOWN DPAD_CENTER; sleep 2; shot manage_profiles
key BACK; sleep 1; key BACK; sleep 2; shot profiles_closed

summary "== player (public test stream)"
adb shell am start -n $PKG/.PlayerActivity \
  --es url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" --es type movie \
  --es metaId tt1254207 --es videoId tt1254207 --es name "Big\ Buck\ Bunny" --es streamName "Test\ stream" >/dev/null
sleep 20; shot player_playing
key DPAD_RIGHT; sleep 1; shot player_seek_flash
key DPAD_UP; sleep 2; shot player_controls
# Controls hide after 5 s without a key press; bring them back right before navigating them.
key DPAD_UP; key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; sleep 2; shot player_panel
key BACK; sleep 1; key BACK; sleep 1; key BACK; sleep 3; shot after_player

summary "== results"
adb logcat -d > "$OUT/logcat.txt"
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo_end.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  summary "CRASHES:"; grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -150 | tee -a "$OUT/summary.txt"
else
  summary "No crashes."
fi
grep -E "ANR in $PKG" "$OUT/logcat.txt" | tee -a "$OUT/summary.txt" || true
grep -E "^.{0,40}[EW] .*($PKG|AndroidRuntime|ExoPlayer|MediaCodec)" "$OUT/logcat.txt" | grep -v "chatty" | head -120 > "$OUT/errors_warnings.txt" || true
grep -E " Marquee *:" "$OUT/logcat.txt" > "$OUT/marquee_log.txt" || true
summary "App log lines: $(wc -l < "$OUT/marquee_log.txt")"; grep -E "search '|GET failed" "$OUT/marquee_log.txt" | head -40 | tee -a "$OUT/summary.txt"
grep -E "Janky frames|Total frames|50th|90th|99th" "$OUT/gfxinfo_browse.txt" | head -8 | tee -a "$OUT/summary.txt"
exit 0
