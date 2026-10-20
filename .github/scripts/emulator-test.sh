#!/usr/bin/env bash
# Drives Marquee like a TV remote and records what happens. Never stops early: every step runs,
# and problems are collected in emu/summary.txt.
set -u
PKG=com.maxim.marquee
OUT=emu
mkdir -p "$OUT/shots" "$OUT/frames"
n=0
summary() { echo "$*" | tee -a "$OUT/summary.txt"; }
alive() { adb shell pidof "$PKG" >/dev/null 2>&1 || summary "APP NOT RUNNING after step: $1"; }
shot() {
  n=$((n + 1))
  adb exec-out screencap -p > "$(printf '%s/shots/%02d_%s.png' "$OUT" "$n" "$1")"
  alive "$1"
}
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.4; done; }
fast() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }
# Side menu: 0 profile, 1 search, 2 home, 3 sports, 4 series, 5 movies, 6 my list, 7 settings
tab() { fast DPAD_LEFT 8; sleep 0.6; fast DPAD_UP 10; [ "$1" -gt 0 ] && fast DPAD_DOWN "$1"; key DPAD_CENTER; }
measure_start() { adb shell dumpsys gfxinfo $PKG reset >/dev/null; }
measure_end() {
  adb shell dumpsys gfxinfo $PKG framestats > "$OUT/frames/$1.txt"
  python3 .github/scripts/framestats.py "$1" "$OUT/frames/$1.txt" | tee -a "$OUT/summary.txt"
}

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
summary "== cold start (first ever: nothing saved yet)"
adb shell am start -W -n $PKG/.MainActivity | grep -E "TotalTime" | tee -a "$OUT/summary.txt"
sleep 3; shot start_3s
sleep 15; shot home

summary "== browse rows"
measure_start
key DPAD_DOWN; sleep 2; shot row2
key DPAD_RIGHT DPAD_RIGHT; sleep 3; shot row2_rest_expanded
for i in 1 2 3 4 5; do key DPAD_DOWN; sleep 2; done; shot scrolled_down
measure_end browse_slow
measure_start
fast DPAD_RIGHT 25; sleep 1; fast DPAD_DOWN 8; sleep 1; fast DPAD_UP 12; sleep 3
measure_end browse_fast_held_keys
shot back_to_top

summary "== details"
measure_start
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT; sleep 2
key DPAD_CENTER; sleep 7; shot detail
key DPAD_DOWN; sleep 2; key DPAD_DOWN DPAD_DOWN; sleep 3; shot detail_more
key BACK; sleep 3; shot back_from_detail
measure_end details

summary "== sports tab"
measure_start
tab 3; sleep 6; shot sports_tab
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT; sleep 2; shot sports_browse
measure_end sports
tab 2; sleep 3; shot home_sports_row

summary "== play without a stream addon (should show the No sources screen)"
key DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 6
key DPAD_CENTER; sleep 25; shot play_no_sources
key BACK; sleep 2; key BACK; sleep 2

summary "== search"
measure_start
tab 1; sleep 3; shot search
key DPAD_RIGHT DPAD_CENTER DPAD_LEFT DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_CENTER
sleep 15; shot search_results_bat
measure_end search

summary "== settings, series, movies, my list"
tab 7; sleep 3; shot settings
fast DPAD_DOWN 25; sleep 2; shot settings_bottom
measure_start
tab 4; sleep 8; shot series_tab
tab 5; sleep 8; shot movies_tab
measure_end tabs
tab 6; sleep 3; shot my_list

summary "== profiles"
tab 0; sleep 3; shot profiles
key BACK; sleep 2

summary "== player (public test stream + addon subtitle file)"
SUBURL="https://raw.githubusercontent.com/maximhoule/Netflixx/main/.github/scripts/test-subtitles.srt"
adb shell am start -n $PKG/.PlayerActivity \
  --es url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" --es type movie \
  --es metaId tt1254207 --es videoId tt1254207 --es name "Big\ Buck\ Bunny" --es streamName "Test\ stream" \
  --es subs "'[{\"url\":\"$SUBURL\",\"lang\":\"eng\",\"label\":\"ENG\"}]'" >/dev/null
sleep 20; shot player_playing
measure_start
key DPAD_UP; sleep 2; shot player_controls
key DPAD_UP; key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; sleep 2
key DPAD_RIGHT DPAD_UP DPAD_UP DPAD_DOWN DPAD_CENTER; sleep 4; shot addon_sub_selected
key BACK; sleep 1; key BACK; sleep 3; shot subtitle_on_video
measure_end player_ui
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:|Java Heap|Native Heap|Graphics" | head -6 | sed 's/^/player memory: /' | tee -a "$OUT/summary.txt"
key BACK; sleep 1; key BACK; sleep 3; shot after_player

summary "== hide a row from the long-press menu"
tab 2; sleep 3; key DPAD_DOWN; sleep 2
adb shell input keyevent --longpress DPAD_CENTER; sleep 2; shot long_press_menu_hide
key DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 3; shot row_hidden
tab 3; sleep 3; shot sports_after_hide
tab 7; sleep 2; fast DPAD_DOWN 14; sleep 1; shot settings_hidden_rows

adb logcat -d > "$OUT/logcat_first_run.txt"
grep -E "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat_first_run.txt" | head -10 | sed 's/^/first run skipped: /' | tee -a "$OUT/summary.txt"
adb logcat -c

summary "== after the TV's background optimisation (app compiled with its profile)"
adb shell cmd package compile -m speed-profile -f $PKG 2>&1 | tail -1 | tee -a "$OUT/summary.txt"
adb shell am force-stop $PKG; sleep 2
adb shell am start -W -n $PKG/.MainActivity | grep -E "TotalTime" | tee -a "$OUT/summary.txt"
sleep 2; shot compiled_start_2s
sleep 8
measure_start
key DPAD_DOWN; sleep 1.5; key DPAD_RIGHT DPAD_RIGHT; sleep 2
for i in 1 2 3 4 5; do key DPAD_DOWN; sleep 1.5; done
fast DPAD_RIGHT 20; sleep 1; fast DPAD_UP 10; sleep 2
measure_end browse_compiled
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:" | head -2 | sed 's/^/home memory: /' | tee -a "$OUT/summary.txt"

summary "== results"
adb logcat -d > "$OUT/logcat.txt"
grep -E "Choreographer.*Skipped [0-9]+ frames" "$OUT/logcat.txt" | head -10 | sed 's/^/compiled skipped: /' | tee -a "$OUT/summary.txt"
cat "$OUT/logcat_first_run.txt" "$OUT/logcat.txt" > "$OUT/logcat_all.txt"; mv "$OUT/logcat_all.txt" "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  summary "CRASHES:"; grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -150 | tee -a "$OUT/summary.txt"
else
  summary "No crashes."
fi
grep -E "ANR in $PKG" "$OUT/logcat.txt" | tee -a "$OUT/summary.txt" || true
grep -E " Marquee *:" "$OUT/logcat.txt" > "$OUT/marquee_log.txt" || true
summary "App log lines: $(wc -l < "$OUT/marquee_log.txt")"; grep -E "GET failed" "$OUT/marquee_log.txt" | head -20 | tee -a "$OUT/summary.txt"
grep -E "ExoPlayerImplInternal|MediaCodecRenderer|AudioTrack|DefaultAudioSink" "$OUT/logcat.txt" | grep -E " E | W " | head -40 > "$OUT/player_errors.txt" || true
summary "Player warnings/errors: $(wc -l < "$OUT/player_errors.txt")"; head -10 "$OUT/player_errors.txt" | tee -a "$OUT/summary.txt"
grep -E "Long monitor contention|StrictMode|OutOfMemory|GC freed.*paused [0-9]{3,}" "$OUT/logcat.txt" | grep "$(adb shell pidof $PKG 2>/dev/null | tr -d '\r') " | head -10 | sed 's/^/contention: /' | tee -a "$OUT/summary.txt" || true
exit 0
