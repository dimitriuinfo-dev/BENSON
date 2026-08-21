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

## 2026-06 (fork) — RECENTS voice command + REMINDER INTERVAL setting
- RECENTS ("arată aplicațiile recente"): NEEDS NATIVE REBUILD (Kotlin). Added openRecents() mirroring
  goHome — BensonAccessibilityService.kt fun openRecents()=performGlobalAction(GLOBAL_ACTION_RECENTS)
  (no canPerformGestures needed, stays false); BensonAccessibilityModule.kt AsyncFunction("openRecents");
  benson-accessibility index.js + index.d.ts export; BensonCommandExecutor.kt gained a "recents" step.
  JS: index.tsx imports openRecents, new SHOW_RECENTS_PATTERN (RO "aplicații recente/deschise", bare
  "recente", "multitasking", "comutator aplicații" + EN/DE), handled in trySettingsVoiceCommand
  (checked before the orchestrator): if accessibility connected → openRecents()+confirm, else honest
  "need the service on" reply.
- REMINDER INTERVAL (Settings): the gentle accessibility reminder cadence is now user-picked 5/15/30
  min. index.tsx: reminderMins state + a11yReminderMsRef (watchdog onStatus reads the ref since it's
  set up once), AsyncStorage 'bensonA11yReminderMins', changeReminderMins() handler, Settings section
  "REAMINTIRE ACCESIBILITATE" (3 chips, chip pattern). Restored in init.
- tsc 0 errors; eslint at the file's pre-existing 15-item baseline (no new issues). Native Kotlin
  cannot be gradle-compiled in this container — openRecents mirrors goHome exactly (low risk).


- WAKE CHIME VOLUME (Settings): lib/agents/wakeChime.ts gained setWakeChimeVolume(v) (0=off..1)
  applied live to the loaded sound; playWakeChime() no-ops when volume<=0. index.tsx: new wakeVolume
  state + AsyncStorage key 'bensonWakeChimeVolume' (restored in init, applied to module), handler
  changeWakeVolume() persists + previews. Settings section "SUNET LA TREZIRE" = 4 chips
  Oprit(0)/Încet(0.3)/Mediu(0.6)/Tare(1.0), mirrors the VOICE ENGINE chip pattern.
- GENTLE ACCESSIBILITY REMINDER: index.tsx watchdog onStatus now re-speaks a short calm cue every
  5 min while the service stays off, gated to foreground + idle (!listening/!loading/!speaking) so
  it never nags or talks over a turn. onDropped seeds a11yLastReminderRef so its immediate message
  counts as the first; reconnect resets the timer. The red banner (visual) is unchanged.
- tsc 0 errors; eslint back to the file's pre-existing baseline (no new issues from these changes).


- WAKE CHIME (Feature A, JS-only): assets/sounds/wake.wav (~11KB, two-note rising ding, generated).
  New lib/agents/wakeChime.ts (expo-av, same stack as openaiTTS) preload+play, never throws.
  Preloaded in init(); played at the very start of handleWakeDetected() so every recognized
  "Benson" gets an instant non-verbal confirmation before mic handoff. tsc+lint clean.
- ACCESSIBILITY-DOWN BANNER (Feature C, JS-only): accessibilityWatchdog.ts gained an onStatus(connected)
  callback (fires every 60s poll + on every AppState 'active' foreground return, via a new AppState
  listener inside the watch). index.tsx: new accessibilityDown state driven by onStatus; a persistent
  RED banner over the main screen (shown only phase==='chat' && !setupWizardOpen && !appPermOpen) with
  "DESCHIDE SETĂRILE" → openAccessibilitySettings(). Complements the existing spoken alert + tappable
  notification. tsc+lint clean.
- CLOSE APP (Feature B): product owner picked "honest, return-to-BENSON only" (no gestures — see
  device-safety note). appLauncherExecutor.ts CLOSE_APP now returns an honest spoken message
  ("Nu pot închide complet {app} — Android nu-mi permite asta. Am revenit la tine.") instead of the
  old "Am revenit în Benson." Reliable swipe-close in Recents was rejected: it needs
  android:canPerformGestures=true, confirmed (2026-07-09) to trigger OxygenOS/ColorOS anti-spyware
  auto-disable of the WHOLE service on this device — stays false. tsc+lint clean.


- WAKE WORD (local Whisper, free): reviewed app/index.tsx + voiceAgent.ts. Implementation is
  COMPLETE & coherent: startWakeScan/stopWakeScan exported from voiceAgent.ts; single wake path
  handleWakeDetected() (no duplication) fed by BOTH the native listener (wakeWordSub) and the local
  Whisper VAD loop (startLocalWakeLoop); resumePassiveWake() picks engine (local default / native
  fallback — the one remaining resumeHotword() at ~1646 is the intentional 'native' branch). Mic
  handoff coherent across every reply path (mission/governance/routeCommand): after a reply, if not
  conv-mode & wakeTriggered → resumePassiveWake() restarts passive scan. tsc 0 errors. No dangling
  resumeHotword. User must final-test on native Android APK (no web/Expo Go possible).
- SETUP WIZARD (components/onboarding/SetupWizard.tsx): enforced critical-permission gating.
  * Critical (blocking) = Microfon + Serviciul de Accesibilitate ONLY (per user 2026-06). Battery
    downgraded critical:true→false.
  * StepScreen: a critical step that isn't 'granted' now DISABLES "CONTINUĂ" (blocked hint shown) so
    the user cannot skip past mic/accessibility. Optional steps advance freely.
  * "N/M ACTIVE" progress indicator added to both StepScreen header and Dashboard.
  * Dashboard: "GATA" (finish) disabled until allCriticalGranted; shows missing-critical hint, or a
    green success line "✓ Tot ce e esențial este activat" when done. finish() also guards internally.
  * tsc + eslint clean on the file.

- ROMANIAN DEFAULTS (index.tsx): langRef, replyLangRef, useState lang, restore fallback all
  'en-GB'→'ro-RO'. This was the "engleză proastă" root cause — LLM got lang='en-GB' so replied
  English, and local Whisper decoded Romanian speech as English. Agent replies use
  replyLangRef.current (buildSystemPrompt: "Respond in language ${lang}") → now Romanian.
- HANNAH / contact matching (src/core/contacts/contactResolver.ts): added a Levenshtein fuzzy tier
  (matchesFuzzy) after exact/partial tiers. Threshold = ceil(maxLen/3), both strings >=3 chars,
  matches whole string + each name token. Rescues "Hana"→"Hannah". 2+ hits → 'ambiguous' (asks).
- MIC ALWAYS-RESPONSIVE (index.tsx): (prev) local default + migration; NEW: miss feedback (explicit
  manual/wake attempt that captures nothing now SPEAKS "Nu am auzit nimic, mai încearcă" instead of
  silence) via sttTriggerRef + sessionGotResultRef; watchdog (listenWatchdogRef) force-stops a hung
  session (cloud 12s / local 65s) so mic is never stuck. Local VAD window is natural (60s pre-speech,
  2.5s end-silence) — no change needed.
- PHONE OPERATOR (lib/agents/tools.ts): tapOnScreen/enterText/pressBack (prev) + NEW scrollScreen,
  all backed by native executeCommand. MAX_TOOL_ITERATIONS 4→10 (claude+openai agents).
- SCROLL NATIVE (BensonCommandExecutor.kt): added "scroll" action (doScroll + findScrollable),
  ACTION_SCROLL_FORWARD/BACKWARD on first scrollable node. NEEDS REBUILD; cannot gradle-compile in
  this container (low risk, mirrors existing style).
- WAKE WORD (Porcupine/Picovoice): playbook obtained. BLOCKED on external creds only the user can
  provide: (1) Picovoice AccessKey (console.picovoice.ai), (2) custom Benson_android.ppn keyword
  file. Best arch = integrate Porcupine into existing BensonForegroundService (single AudioRecord,
  fan out PCM to Porcupine + recognizer). Not built yet — needs key+ppn + native rebuild.
- VERIFIED: tsc 0 errors, eslint clean on all touched JS, Metro Android graph 1800 modules resolve
  (Hermes binary step is a container-only limitation).
