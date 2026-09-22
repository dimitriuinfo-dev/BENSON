#!/usr/bin/env bash
# BENSON regression harness — Round H1, BENSON_CANON.md Section 2.
#
# Drives the 6 device-proven paths listed in the canon via `adb shell input text` on the main
# screen's existing text field, then asserts on log lines that ALREADY exist in the app's own
# source (never invented — see the comment above each run_test call for the exact file/line).
# Reads logcat only. Never touches execution/brain/STT-TTS logic.
#
# Output: scripts/regression/last_run.log (PASS/FAIL table) and last_run_capture.log (the full
# raw capture, kept for evidence — never deleted). Non-zero exit if any row is FAIL.
#
# Usage: ADB_PATH=/path/to/adb.exe ./run.sh   (ADB_PATH optional, defaults to "adb" on PATH)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGFILE="$SCRIPT_DIR/last_run.log"
CAPTURE="$SCRIPT_DIR/last_run_capture.log"
ADB="${ADB_PATH:-adb}"
PKG="com.benson.butler"

# Empirically measured on the OnePlus Nord 4 test device (1080x2414, main screen, keyboard
# closed) — the "Scrie o comandă sau un nume..." field position is stable across every reply
# length observed this session (the screen is not a ScrollView, unlike app/debug.tsx). Re-measure
# if the harness ever runs on a different device/resolution.
FIELD_X=537
FIELD_Y=1880

FAIL_COUNT=0
RESULTS=()

adb_shell() { "$ADB" shell "$@"; }

tap_field() { adb_shell input tap "$FIELD_X" "$FIELD_Y"; sleep 1; }

# `adb shell input text` with a literal space gets re-split by the DEVICE's shell — only the
# first word reaches the `input` command. %s is the documented escape for a space. Discovered
# the hard way this session (see conversation history 2026-09-22).
send_text() {
  local encoded="${1// /%s}"
  adb_shell input text "$encoded"
}

# Diacritics (ă/î/ș/ț) crash `adb shell input text` with a NullPointerException on this device's
# active IME (no key-character-map entry for them) — every string below is deliberately
# diacritic-free. The app's own regexes accept both spellings (e.g. `caut[ăa]`, `m[ăa]`), so this
# does not change what's being tested.
dispatch() {
  # BUG FOUND ON FIRST RUN (2026-09-22): several test inputs launch a DIFFERENT app (Waze,
  # Calculator, YouTube, Spotify) that then owns the foreground. Every dispatch must explicitly
  # bring BENSON back to the foreground first — the harness cannot assume it still is.
  adb_shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 2
  tap_field
  send_text "$1"
  sleep 1
  adb_shell input keyevent 66
}

# BUG FOUND ON THIRD RUN (2026-09-22): repeated `adb logcat -c` + `-d` cycles are vulnerable to
# ring-buffer eviction on this device — the buffer is shared across EVERY process, and as the
# harness installs/foregrounds more apps (Calculator, Waze, YouTube, Spotify), each one's own
# native logging competes for the same fixed-size buffer and can push our BENSON_AUDIO/
# ReactNativeJS lines out before we read them, seconds later. Confirmed by manually reproducing
# every "failed" step in isolation — every one succeeded, and fast (well under its wait budget),
# proving the app was never the problem. Fix: ONE persistent background `logcat` piped straight to
# a file (no ring buffer involved) for the whole run; each test greps only the lines appended
# since ITS OWN start (by line-count offset), never the whole file and never a cleared buffer.
LOGCAT_PID=""
start_capture() {
  : > "$CAPTURE"
  "$ADB" logcat -v time ReactNativeJS:I BENSON_AUDIO:I '*:S' >"$CAPTURE" 2>&1 &
  LOGCAT_PID=$!
  sleep 1
}
stop_capture() {
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
}
trap stop_capture EXIT

capture_line_count() { wc -l <"$CAPTURE" 2>/dev/null || echo 0; }

# $1 = starting line offset, $2 = wait seconds, $3 = ERE assertion pattern
capture_since() {
  local offset="$1" wait_s="$2" pattern="$3"
  sleep "$wait_s"
  tail -n "+$((offset + 1))" "$CAPTURE" | grep -E "$pattern" | tail -5
}

# Diagnostic-only: did the dispatch even reach handleIncomingText, regardless of whether the
# expected outcome followed? Used to label a FAIL as "never dispatched" vs. "dispatched but
# expected line absent" in the report, without changing PASS/FAIL itself.
dispatch_reached_since() {
  local offset="$1"
  tail -n "+$((offset + 1))" "$CAPTURE" | grep -qE 'WA_PAYLOAD_RAW_STT.*viaVoice=false'
}

# $1 = test id, $2 = input text (empty = no dispatch, just observe), $3 = wait seconds,
# $4 = ERE assertion pattern
run_test() {
  local id="$1" input="$2" wait_s="$3" pattern="$4"
  echo "--- $id ---"
  local offset
  offset="$(capture_line_count)"
  if [ -n "$input" ]; then dispatch "$input"; fi
  local match
  match="$(capture_since "$offset" "$wait_s" "$pattern")"
  if [ -n "$match" ]; then
    echo "PASS: $id"
    echo "$match"
    RESULTS+=("PASS|$id|$(echo "$match" | tail -1)")
  else
    local reached_note="dispatch confirmed (WA_PAYLOAD_RAW_STT seen)"
    dispatch_reached_since "$offset" || reached_note="dispatch NEVER REACHED THE APP"
    echo "FAIL: $id ($reached_note)"
    RESULTS+=("FAIL|$id|(no match for: $pattern) [$reached_note]")
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

START_TS=$(date +%s)
start_capture

# 6. Calculator — run first: cheapest smoke test, must always pass before spending time on the
#    slower paths. On THIS device the installed calculator is "Rechner" (OnePlus/German locale),
#    a fuzzy name match, which the Confirmation Gate routes through EXEC_TRACE_FAILURE
#    reason=SINGLE_MATCH_NEEDS_CONFIRMATION (missionOrchestrator.ts) instead of an immediate
#    launch — confirmed by manual capture 2026-09-22 (my first harness run wrongly assumed a
#    direct launch here). Two dispatches: the command, then "da". Assertion for step b:
#    src/executors/appLauncherExecutor.ts:283 (EXEC_TRACE_FOREGROUND_VERIFY).
run_test "6a_OPEN_CALCULATOR_ASK" "deschide calculatorul" 6 \
  'EXEC_TRACE_FAILURE.*SINGLE_MATCH_NEEDS_CONFIRMATION|CONFIRM_LISTEN_ARM'
run_test "6b_OPEN_CALCULATOR_CONFIRM" "da" 8 \
  'EXEC_TRACE_FOREGROUND_VERIFY.*confirmedByEvent=true'

# 1. Waze navigation with correct destination. Waze is routed through the governed mission tool
#    (src/core/mission/tools/wazeTool.ts via missionExecutor, EXEC_TRACE_EXECUTOR
#    executor=governed:waze), NOT androidActionExecutor's generic APP_FOREGROUND_REQUEST path —
#    confirmed by manual capture 2026-09-22 (my first assumption about which executor handles
#    Waze was wrong). WazeTool logs the accepted deep-link URL via plain console.log, captured
#    under the ReactNativeJS tag: '[WazeTool]', 'accepted', 'waze_app', '<url with destination>'.
run_test "1_WAZE_NAV" "du-ma la Sibiu" 12 \
  'WazeTool.*accepted.*[Ss]ibiu'

# 2. Open app by name. Same assertion as path 6, different target.
run_test "2_OPEN_APP_YOUTUBE" "deschide youtube" 10 \
  'EXEC_TRACE_FOREGROUND_VERIFY.*youtube.*confirmedByEvent=true'

# 3. Spotify: search -> play -> verify -> pause. BUG FOUND THIS ROUND: the proven exact title
#    ("Workout Mix Radio", CLAUDE.md, 2026-09-16) is a substring of MULTIPLE of today's 5 live
#    candidates ("Workout Rock Mix", "...Workout Mix Radio", "High Energy Workout Mix", "Angry
#    Workout Mix", "Fun Happy Workout Mix") — matchDisambiguationPick's token fallback
#    (missionOrchestrator.ts:721-722) returns the FIRST candidate containing any shared token
#    ("workout"), which is the WRONG one, not the exact-title one. Fixed by using the ordinal
#    picker instead ("a doua" — missionOrchestrator.ts:711-716, checked BEFORE the substring/token
#    fallback), targeting candidate index 1, the confirmed-stable position of the intended title
#    across every run this round. Assertions: src/executors/mediaSearchExecutor.ts:329
#    (MEDIA_SEARCH_DONE), :451 (MEDIA_SELECT_DONE — only reached past the playback_not_verified
#    failure branch, so it IS the play+verify step), src/executors/mediaGovernor.ts:91 (MEDIA_ACT).
run_test "3a_SPOTIFY_SEARCH" "cauta workout mix radio pe spotify" 15 \
  'MEDIA_SEARCH_DONE.*provider=spotify.*candidates=[1-9]'
run_test "3b_SPOTIFY_PLAY_VERIFY" "a doua" 15 \
  'MEDIA_SELECT_DONE.*provider=spotify'
run_test "3c_SPOTIFY_PAUSE" "pauza" 8 \
  'MEDIA_ACT.*action=pause.*success=true'

# 4. Conversation with turn memory — two replies, second refers to the first.
#    Assertions: app/index.tsx BRAIN_INTENT/ROUTE (brain-routed reply) and TTS_BLOCK_END
#    reason=success (finalized, not a watchdog timeout) — verified live this session
#    (DEVICE_PASS_BENSON_CHAT_VOICE_TURN_MEMORY_2026-09-22).
run_test "4a_CONV_MEMORY_SET" "Ma numesc Rares." 10 \
  'BRAIN_INTENT.*kind=speak'
run_test "4b_CONV_MEMORY_RECALL" "Cum ma numesc?" 12 \
  'TTS_BLOCK_END.*reason=success'

# 5. Ambiguity -> clarify -> correction -> execution ("deschide radio" -> correction -> opened).
#    Assertions: app/index.tsx UI_STATE_JS state=CONFIRMING (clarify reached) then the same
#    EXEC_TRACE_FOREGROUND_VERIFY as path 2/6 (the correction actually launched something).
run_test "5a_AMBIGUOUS_OPEN" "deschide radio" 3 \
  'UI_STATE_JS.*state=CONFIRMING'
run_test "5b_CORRECTION_LAUNCH" "Magic FM" 10 \
  'EXEC_TRACE_FOREGROUND_VERIFY.*confirmedByEvent=true'

stop_capture
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

{
  echo "BENSON regression harness — run at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "elapsed_seconds=$ELAPSED"
  for r in "${RESULTS[@]}"; do echo "$r"; done
} > "$LOGFILE"

echo ""
echo "=== SUMMARY (elapsed ${ELAPSED}s) ==="
cat "$LOGFILE"

if [ "$FAIL_COUNT" -eq 0 ]; then
  exit 0
else
  exit 1
fi
