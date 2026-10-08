#!/usr/bin/env bash
# V4a corpus — 50 comenzi tastate (RO/DE/EN), shadow interpretation only. Logcat pe fișier,
# raport PASS = match=true, FAIL = match=false, cu motivul.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGFILE="$SCRIPT_DIR/v4a_corpus_last_run.log"
CAPTURE="$SCRIPT_DIR/v4a_corpus_capture.log"
ADB="${ADB_PATH:-adb}"
PKG="com.benson.butler"
SERIAL_ARGS=()
[ -n "${DEVICE_SERIAL:-}" ] && SERIAL_ARGS=(-s "$DEVICE_SERIAL")

adb_shell() { "$ADB" "${SERIAL_ARGS[@]}" shell "$@"; }
debug_inject_enable() { adb_shell am broadcast -a com.benson.butler.DEBUG_INJECT_ENABLE --ez enabled "$1" -p "$PKG" >/dev/null; }
debug_inject() { adb_shell am broadcast -a com.benson.butler.DEBUG_INJECT --es text "'$1'" -p "$PKG" >/dev/null; }

LOGCAT_PID=""
start_capture() {
  : > "$CAPTURE"
  "$ADB" "${SERIAL_ARGS[@]}" logcat -v time BENSON_AUDIO:I '*:S' >"$CAPTURE" 2>&1 &
  LOGCAT_PID=$!
  sleep 1
}
stop_capture() { [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null; }
trap stop_capture EXIT

# $1 = command text, $2 = needs_wa_cleanup (yes/no)
run_one() {
  debug_inject "$1"
  sleep 5
  if [ "$2" = "yes" ]; then
    debug_inject "nu"
    sleep 3
  fi
}

start_capture
debug_inject_enable true

CMDS=(
  "suna-o pe Hannah pe whatsapp|yes"
  "video cu Hannah|yes"
  "scrie-i lui Hannah ca ajung tarziu|yes"
  "ruf Hannah auf WhatsApp an|yes"
  "call Hannah on whatsapp|yes"
  "cauta madonna pe youtube|yes"
  "cauta anime pe youtube|yes"
  "search cats on youtube|yes"
  "suche auf youtube nach musik|yes"
  "opreste muzica|no"
  "stop the music|no"
  "pune pauza|no"
  "deschide calculatorul|no"
  "deschide setarile|no"
  "open chrome|no"
  "offne die kamera|no"
  "deschide spotify|no"
  "cat e ceasul|no"
  "wie spaet ist es|no"
  "what time is it|no"
  "cum e vremea azi|no"
  "cine esti tu|no"
  "povesteste-mi o gluma|no"
  "ce zi este azi|no"
  "spune-mi ceva interesant|no"
  "esti ocupat|no"
  "multumesc|no"
  "bine|no"
  "ok|no"
  "nu stiu|no"
  "fa ceva|no"
  "ajuta-ma|no"
  "suna-o pe mama|yes"
  "trimite un mesaj lui Ingrid|yes"
  "cauta pisici dragute pe youtube|yes"
  "deschide whatsapp|no"
  "pune muzica|no"
  "next song|no"
  "skip|no"
  "volum mai mare|no"
  "inchide aplicatia|no"
  "mergi acasa|no"
  "suna-l pe tata pe whatsapp|yes"
  "trimite-i un mesaj Hannei ca am ajuns|yes"
  "video call cu Hannah|yes"
  "ruf mal Hannah an|yes"
  "cauta retete de prajituri pe youtube|yes"
  "opreste tot|no"
  "esti treaz|no"
  "benson esti acolo|no"
)

echo "Running ${#CMDS[@]} corpus commands..."
i=0
for entry in "${CMDS[@]}"; do
  i=$((i+1))
  cmd="${entry%%|*}"
  cleanup="${entry##*|}"
  echo "[$i/${#CMDS[@]}] $cmd"
  run_one "$cmd" "$cleanup"
done

debug_inject_enable false
sleep 3
stop_capture

grep "INTERP_SHADOW" "$CAPTURE" > "$LOGFILE"
TOTAL=$(wc -l < "$LOGFILE")
MATCHED=$(grep -c "match=true" "$LOGFILE" || true)
echo ""
echo "=== V4a CORPUS SUMMARY ==="
echo "total_shadow_lines=$TOTAL matched=$MATCHED"
cat "$LOGFILE"
