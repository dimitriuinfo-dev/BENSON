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
- 2026-06: Built ISOLATED on-screen Voice Diagnostic harness `app/voicediag.tsx`:
  talks DIRECTLY to ExpoSpeechRecognitionModule (bypasses voiceAgent/orchestrator gating/wake
  word/foreground service). Shows 4 checkpoints live (permission, session start, engine returned,
  transcript), mic-energy (RMS) proof, full event trace with timestamps, device recognizer caps
  (isRecognitionAvailable/default service/on-device support), language (ro/en/de) + on-device
  engine toggles, and optional auto-route of the final transcript through the SAME runMission the
  typed path uses (reply shown on screen). Entry point: gold button in Debug Panel.
- Registered route in app/_layout.tsx. Wrote VOICE_DIAGNOSTIC_GUIDE.md (RO).
- Verified: tsc 0 errors, eslint clean, full Metro Android graph (1800 modules) resolves &
  transforms (only container hermesc binary step fails — env limitation, not code).

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
