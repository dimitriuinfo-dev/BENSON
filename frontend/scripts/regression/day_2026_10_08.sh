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

# P3 (15.10.2026) — GARDĂ DE VALIDITATE. Dovedit azi de două ori: când telefonul se deconectează
# de pe USB, `adb logcat` scrie doar "- waiting for device -", nicio injecție nu ajunge, iar suita
# raporta senin **10 din 10 FAIL** — adică acuza produsul pentru un cablu. Exact genul de „eșec
# repetat" pe care Masterul a cerut să-l lămuresc înainte de a declara testul stabil.
# O suită care nu poate rula trebuie să spună BLOCKED, nu FAIL.
if ! "$ADB" devices 2>/dev/null | grep -q "device$"; then
  echo "BLOCKED: niciun telefon conectat (adb devices gol). Suita NU a rulat."
  echo "BLOCKED|DEVICE_ABSENT|suita nu a rulat" > "$LOGFILE"
  exit 2
fi

START_TS=$(date +%s)
start_capture
debug_inject_enable true

# Verificăm că injecția chiar ajunge la aplicație înainte de a interpreta vreun rezultat.
# Fără asta, o aplicație care tocmai repornește (după `adb install`) produce FAIL-uri false.
if ! grep -qE "DEBUG_INJECT.*action=set_enabled value=true" "$CAPTURE"; then
  sleep 3
  debug_inject_enable true
  if ! grep -qE "DEBUG_INJECT.*action=set_enabled value=true" "$CAPTURE"; then
    echo "BLOCKED: aplicația nu răspunde la DEBUG_INJECT (pornește încă?). Suita NU a rulat."
    echo "BLOCKED|APP_NOT_READY|suita nu a rulat" > "$LOGFILE"
    stop_capture; exit 2
  fi
fi

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
# FIX_HARNESS_WA_MESSAGE_WAIT_1 (14.10.2026) — TESTUL era gresit, nu produsul. Spre deosebire de
# apel/video (care intreaba IMEDIAT), mesajul scrie intai draftul VIZIBIL in WhatsApp si abia apoi
# intreaba "Trimit?" — comportament DOVEDIT si intentionat (CLAUDE.md, RUNDA WA_VISIBLE_DRAFT,
# 03.10: "draftul se scrie ÎNAINTE de «Trimit?», nu după «da»"). Dovada din log, 17:15:
#   21.246 WA_WRITE_DEEPLINK_START -> 22.226 TARGET_REACQUIRE_OK elapsedMs=918 -> 23.430 inject "nu"
# „nu" ajungea inainte sa existe intrebarea, deci nu avea ce confirma. Verificat ca NU e cauzat de
# FIX_TREE_CAP_1: cu TREE_CAP_RAISED=false (plafon 400, ca inainte) testul cade identic.
# `leaked=no` in toate rulatile — zero mesaje trimise, siguranta a tinut mereu.
# P3 (15.10.2026) — fereastra FIXA inlocuita cu asteptare pe CONDITIE: asteptam pana cand
# intrebarea de confirmare chiar exista in log, apoi raspundem. Un `sleep` fix era fie prea scurt
# (draftul WhatsApp inca se scria -> "nu" ajungea inaintea intrebarii -> FAIL), fie inutil de lung.
wait_for_log() { # $1 = pattern, $2 = secunde maxim (definit devreme: folosit si de YT, si de WA)
  local i=0
  while [ "$i" -lt "$2" ]; do
    grep -qE "$1" "$CAPTURE" && return 0
    sleep 1; i=$((i + 1))
  done
  return 1
}
wait_for_log 'TTS_NATIVE_REQUEST.*Mesaj c|WA_ASK|Dorești să-l trimit' 12 || sleep 4
run_no_action_test "WA_MESSAGE_NO_ACTION" 5 'SESSION.*close reason=wa_confirm_no' 'WA_WRITE_SENT_VERIFIED|WA_INTENT'

# 4. YouTube caută + pornește (confirmă "da") + oprește. yt search leaves a play CONFIRMATION
#    pending ("Îl pornesc?") — "opreste" immediately after would be read as declining to play
#    (opreste IS a NO_WORD), not as a pause command. Resolve it with "da" first.
run_test "YT_SEARCH" "cauta madonna pe youtube" 3 'NATIVE_ROUTE.*action=youtube_search_confirm target="madonna"'
# P3 — a treia fereastră fixă eliminată. Dovada (21:40): „da" era injectat la +3,3 s, dar căutarea
# pe ecran se încheie abia la +6 s; „da" nu avea ce confirma și cădea la agent. Așteptăm întrebarea.
wait_for_log 'YOUTUBE_SEARCH_CONFIRM.*found=true|Îl pornesc' 12 || true
run_test "YT_PLAY_CONFIRM" "da" 6 'CONFIRM_RESULT.*verdict=YES'
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
