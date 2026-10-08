#!/usr/bin/env bash
# ZIUA 2026-10-08 v3, G3 — SUITA DE REGRESIE. Rulează după fiecare pas (D0, P4, P5, V, A, H).
#
# Comenzi tastate injectate prin DEBUG_INJECT (native pipeline, handleNativeCommandFlow),
# logcat pe fișier. Cazuri: WhatsApp apel/video/mesaj către Ingrid -> confirmare -> "Nu" ->
# zero acțiune; YouTube caută + oprește; "oprește muzica".
#
# NOTĂ ONESTĂ (descoperit azi, scriind acest script): confirmarea WhatsApp NU trece prin
# NativeConfirmationListener.kt (Pasul 3/3b) — trece prin pendingWaAction/handlePendingWaAnswer,
# alimentat de startFollowUpCapture -> CaptureEngine (modul separat, benson-audio-capture).
# Fereastra de confirmare fixată la Pasul 3 se aplică doar drumului JS-mission mai vechi
# (startConfirmationListening: governed apps/vignete/notițe), nu drumului WA. Testul de mai jos
# verifică comportamentul WA real (care funcționează deja), nu fereastra din Pasul 3.
#
# Usage: ADB_PATH=/path/to/adb.exe INGRID_CONTACT="Ingrid" ./day_2026_10_08.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGFILE="$SCRIPT_DIR/day_2026_10_08_last_run.log"
CAPTURE="$SCRIPT_DIR/day_2026_10_08_capture.log"
ADB="${ADB_PATH:-adb}"
PKG="com.benson.butler"
INGRID="${INGRID_CONTACT:-Ingrid}"

FAIL_COUNT=0
RESULTS=()

adb_shell() { "$ADB" shell "$@"; }

LOGCAT_PID=""
start_capture() {
  : > "$CAPTURE"
  "$ADB" logcat -v time ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I '*:S' >"$CAPTURE" 2>&1 &
  LOGCAT_PID=$!
  sleep 1
}
stop_capture() { [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null; }
trap stop_capture EXIT

capture_line_count() { wc -l <"$CAPTURE" 2>/dev/null || echo 0; }
capture_since() {
  local offset="$1" wait_s="$2" pattern="$3"
  sleep "$wait_s"
  tail -n "+$((offset + 1))" "$CAPTURE" | grep -E "$pattern" | tail -5
}
# $1 = offset, $2 = pattern that must be ABSENT for the case to pass
absent_since() {
  local offset="$1" pattern="$2"
  tail -n "+$((offset + 1))" "$CAPTURE" | grep -E "$pattern"
}

debug_inject_enable() { adb_shell am broadcast -a com.benson.butler.DEBUG_INJECT_ENABLE --ez enabled "$1" -p "$PKG" >/dev/null; sleep 1; }
debug_inject() { adb_shell am broadcast -a com.benson.butler.DEBUG_INJECT --es text "'$1'" -p "$PKG" >/dev/null; }

run_test() {
  local id="$1" input="$2" wait_s="$3" pattern="$4"
  echo "--- $id ---"
  local offset; offset="$(capture_line_count)"
  debug_inject "$input"
  local match; match="$(capture_since "$offset" "$wait_s" "$pattern")"
  if [ -n "$match" ]; then
    echo "PASS: $id"; echo "$match"
    RESULTS+=("PASS|$id|$(echo "$match" | tail -1)")
  else
    echo "FAIL: $id"
    RESULTS+=("FAIL|$id|(no match for: $pattern)")
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# $1 = id, $2 = wait seconds, $3 = pattern that must be PRESENT (the cancel path),
# $4 = pattern that must be ABSENT (proof zero execution happened)
run_no_action_test() {
  local id="$1" wait_s="$2" present_pattern="$3" absent_pattern="$4"
  local offset; offset="$(capture_line_count)"
  debug_inject "nu"
  local match; match="$(capture_since "$offset" "$wait_s" "$present_pattern")"
  local leaked; leaked="$(absent_since "$offset" "$absent_pattern")"
  if [ -n "$match" ] && [ -z "$leaked" ]; then
    echo "PASS: $id"; echo "$match"
    RESULTS+=("PASS|$id|$(echo "$match" | tail -1)")
  else
    echo "FAIL: $id"
    [ -z "$match" ] && echo "  missing: $present_pattern"
    [ -n "$leaked" ] && echo "  leaked execution: $leaked"
    RESULTS+=("FAIL|$id|present=$([ -n "$match" ] && echo ok || echo MISSING) leaked=$([ -n "$leaked" ] && echo YES || echo no)")
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

START_TS=$(date +%s)
start_capture
debug_inject_enable true

# 1. WhatsApp apel -> Ingrid -> confirmare -> "Nu" -> zero acțiune
# NOTĂ: ferestrele astea sunt vulnerabile la o buclă de self-echo observată azi (BENSON își aude
# propriul TTS "Incă nu știu sa fac asta." din ciclurile normale de follow-up capture, îl transcrie
# și "nu"-ul din el consumă singur confirmarea pendinte înainte ca injecția noastră să ajungă) —
# wait scurtat, pentru fereastră minimă de interferență. Dacă FAIL cu leaked=YES, verifică log-ul
# manual înainte să tratezi ca regresie reală (poate fi doar cursa asta).
run_test "WA_CALL_ASK" "suna la $INGRID pe whatsapp" 2 'WA_PARSE.*kind=call'
run_no_action_test "WA_CALL_NO_ACTION" 4 'SESSION.*close reason=wa_confirm_no' 'WA_INTENT|WA_NATIVE_CALL_VERIFY'

# 2. WhatsApp video -> Ingrid -> confirmare -> "Nu" -> zero acțiune
run_test "WA_VIDEO_ASK" "video cu $INGRID" 2 'WA_PARSE.*kind=video'
run_no_action_test "WA_VIDEO_NO_ACTION" 4 'SESSION.*close reason=wa_confirm_no' 'WA_INTENT|WA_NATIVE_CALL_VERIFY'

# 3. WhatsApp mesaj -> Ingrid -> confirmare -> "Nu" -> zero acțiune
run_test "WA_MESSAGE_ASK" "scrie-i lui $INGRID ca ajung tarziu" 2 'WA_PARSE.*kind=message'
run_no_action_test "WA_MESSAGE_NO_ACTION" 4 'SESSION.*close reason=wa_confirm_no' 'WA_WRITE_SENT_VERIFIED|WA_INTENT'

# 4. YouTube caută + pornește (confirmă "da") + oprește. yt search leaves a play CONFIRMATION
#    pending ("Îl pornesc?") — "opreste" immediately after would be read as declining to play
#    (opreste IS a NO_WORD), not as a pause command. Resolve it with "da" first.
run_test "YT_SEARCH" "cauta madonna pe youtube" 3 'NATIVE_ROUTE.*action=youtube_search_confirm target="madonna"'
run_test "YT_PLAY_CONFIRM" "da" 5 'CONFIRM_RESULT.*verdict=YES'
run_test "YT_STOP" "opreste muzica" 5 'NATIVE_ROUTE.*action=media_ctl target="pause"'

# 5. "oprește muzica" de sine stătător
run_test "STOP_MUZICA" "opreste muzica" 5 'NATIVE_ROUTE.*action=media_ctl target="pause"'

debug_inject_enable false
stop_capture
END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

{
  echo "ZIUA 2026-10-08 G3 regression — run at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "elapsed_seconds=$ELAPSED"
  for r in "${RESULTS[@]}"; do echo "$r"; done
} > "$LOGFILE"

echo ""
echo "=== SUMMARY (elapsed ${ELAPSED}s) ==="
cat "$LOGFILE"

if [ "$FAIL_COUNT" -eq 0 ]; then exit 0; else exit 1; fi
