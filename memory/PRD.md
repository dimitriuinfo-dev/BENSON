# BENSON — PRD / Working Memory

## Original problem statement
Existing Expo/RN/TS Android app (native custom Kotlin modules). Working: Waze nav, WhatsApp
pre-fill, command orchestrator (typed commands via Debug Panel). BROKEN: voice input — spoken
commands produce NO reaction (neither wake word nor manual mic button). Typed commands pass the
full pipeline correctly. Diagnose & fix: microphone → expo-speech-recognition → transcript →
orchestrator. Device: OnePlus Nord 4, OxygenOS 15.

## Hard environment facts (must respect)
- App uses custom native modules (benson-accessibility, benson-foreground-service,
  benson-audio-capture, benson-overlay, etc.) + expo-speech-recognition. It CANNOT run in
  Expo Go or web preview. Only real Android dev/EAS builds run it.
- Emergent cloud container CANNOT run the app on the user's physical device and CANNOT run adb on
  it. Final runtime proof must come from the user's device. Honesty over theater.
- User develops Metro locally on Windows; Emergent preview/Metro is not their runtime.
- Repo now lives at /app root (imported from github.com/dimitriuinfo-dev/BENSON, main).
  Heavy debug artifacts (root *.png, *.log, audio-dumps) were excluded to keep workspace lean;
  app assets (assets/**) are intact.

## Architecture of the voice path (verified by source reading)
- Typed (works): Debug Panel → runMission (src/core/orchestrator) directly.
- Voice: mic → expo-speech-recognition (ExpoSpeechRecognitionModule) → 'result'/'end' events
  → lib/agents/voiceAgent.ts (emit bus) → app/index.tsx resultSub/endSub
  → scheduleAssembledDispatch → handleIncomingText → runMission (SAME as typed).
- Since transcript→orchestrator == typed path (works), the break is upstream (mic→transcript OR
  the live-app gating that starts the recognizer).
- Native lib contract confirmed: 'result' event = { results:[{transcript,...}], isFinal:bool };
  voiceAgent parsing `ev.results[0].transcript` is CORRECT. interimResults:true required for
  partials. Prior report (AUDIO_DIAGNOSIS_REPORT.md) found: Defect A wrong locale (fixed), Defect
  B device fires only partials not finals (partial-fallback added). User says still no reaction.
- Live-app listen-start is gated behind fragile points:
  1. Medallion first tap → toggleConvMode() → speakText(greeting, onFinished=doStartListening).
     Device TTS onDone documented to sometimes never fire → listening never starts.
  2. Medallion later taps / wakeWordSub → doStartListening() guarded by
     listeningRef/loadingRef/speakingRef — any stuck true → silent no-op.

## Work done (this session) — dates
- 2026-06: Imported BENSON repo into workspace. Installed deps. Verified native event contract
  from expo-speech-recognition@3.1.3 source.
- 2026-06: Built ISOLATED on-screen Voice Diagnostic harness `app/voicediag.tsx` (later enhanced
  with interim-results toggle + partial/final counters) + Debug Panel entry button.
- 2026-08: DEVICE EVIDENCE obtained by product owner via the diagnostic + logcat:
  cloud engine failed 2/2 (ERROR_NO_MATCH code 7, ZERO partials) on OnePlus Nord 4 / OxygenOS;
  local Whisper engine transcribed successfully same session ("su no pe Hana pe WhatsApp").
- 2026-08: FIX — switched default STT engine to 'local' (Whisper) in `app/index.tsx`:
  * useState default 'cloud'→'local'; sttEngineRef default 'cloud'→'local'.
  * One-time migration in settings-restore: existing installs with stored 'cloud' are moved to
    'local' once (flag 'bensonSttDefaultLocalMigrated_v1'), no manual Settings change needed;
    an explicit re-pick afterwards is preserved. Whisper preloaded on local.
  * tsc clean. Only startRecognition call site (index.tsx:1597) uses sttEngineRef → now local.
  RATIONALE: cloud recognizer is an OEM black box (android.speech.SpeechRecognizer via
  expo-speech-recognition) — unfixable from JS with any guarantee; local is offline + proven on
  this device.
  SCOPE/HONEST LIMIT: this fixes the MANUAL medallion + conversation-mode COMMAND capture (they
  call startRecognition → local, bypassing the device recognizer). It does NOT fix wake-word
  ("Benson") detection — that is a separate NATIVE path in BensonForegroundService.kt still using
  the same failing device SpeechRecognizer. Whisper is not a wake-word engine; native wake-word
  redesign is a separate, larger task.
- 2026-08: PHONE OPERATOR — Phase 1 (JS-only, no rebuild). Extended lib/agents/tools.ts (no new
  files): exposed existing native executeCommand({steps}) as 3 LLM tools — tapOnScreen (click by
  visible text/desc), enterText (set_text into editable field), pressBack. With existing readScreen
  (screenBridge snapshot) + openApp + fillForm, the LLM tool-use loop now operates ANY app like a
  finger: open → readScreen → tap → readScreen → type → tap. Raised MAX_TOOL_ITERATIONS 4→10 in
  claudeAgent.ts + openaiAgent.ts. tsc+lint clean. Testable via JS reload (no rebuild).
  Phase 2 (needs native rebuild): add scroll action to BensonCommandExecutor.kt + scrollScreen tool
  (reach off-screen content); optional getScreenSnapshot() native getter. Honest limit: "close app"
  = Home/recents only (Android forbids force-stop via accessibility).

## Branch / sync
- My session work is committed by the platform to branch `conflict_210826_1714` (main = clean
  base 709107d). /app == that branch. Merging to main = user's Save-to-GitHub / GitHub action
  (agent cannot push directly).

## Prior notes

## Verification status (honest)
- NO automated test possible: native-only app, no device, won't render on web (native module
  imports throw at import). testing_agent cannot exercise this. Root cause NOT yet fixed by design
  — diagnostic instrument built first (per user agreement) to LOCATE the break on-device.

## Next tasks (after user returns device screenshot)
- If transcript appears in harness but not live app → fix the live-app gating in app/index.tsx
  (decouple listen-start from TTS onFinished; add/verify watchdog; audit stuck refs).
- If harness step 2/3 fails → recognizer/permission/device: switch to on-device engine + model
  download flow, or alternate recognition service; verify RECORD_AUDIO not revoked.
- Only then apply the targeted fix and re-verify on device.

## Backlog / notes
- Do NOT re-add 49 speculative files. Minimal, isolated changes only.
- Cannot promise Accessibility Service self-reactivation (Android forbids it) — never claim it.
