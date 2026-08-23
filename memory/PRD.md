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

## 2026-06 (fork) — DEPLOY BLOCKER FIX: missing backend/ (backend/.env + minimal server)
- Deploy stopped at the first gate: no backend/.env. Root cause: repo is frontend-only (native Expo)
  but the platform image is expo_mongo_base_image, which requires a backend service — the whole
  backend/ dir was absent (supervisor `backend` = FATAL: uvicorn server:app in /app/backend, no dir).
- FIX: created /app/backend with:
  * .env — MONGO_URL="mongodb://localhost:27017", DB_NAME="benson_database", CORS_ORIGINS="*"
  * server.py — minimal FastAPI, /api/ + /api/health, CORS; no business logic (BENSON is 100%
    on-device, this only satisfies the deploy health-check contract).
  * requirements.txt — pip freeze after installing fastapi + uvicorn[standard] + python-dotenv.
- VERIFIED: `supervisorctl restart backend` → RUNNING; curl /api/health → {"status":"ok"}, /api/ →
  {"message":"BENSON backend online"}. Deploy first-gate unblocked; user can re-Publish.
- (expo dev-server still FATAL — unrelated fork infra path /app/frontend; does not affect deploy.)


- SYMPTOM (user): after BENSON listened/spoke, OTHER apps' audio (video/radio) cut after a fraction
  of a second and never recovered — only a full phone restart fixed it. STOP in-app didn't help.
- ROOT CAUSE: lib/agents/openaiTTS.ts set the GLOBAL expo-av audio mode to
  InterruptionModeAndroid.DoNotMix (→ AUDIOFOCUS_GAIN, permanent EXCLUSIVE focus) with
  staysActiveInBackground:true. Once set, every expo-av playback (TTS + the wake chime, which fires
  on every wake) grabbed exclusive focus and held it for the whole process — starving all other apps'
  audio until the process was killed. Native capture (benson-audio-capture, VOICE_RECOGNITION
  AudioRecord) does NOT touch audio focus — confirmed not the cause.
- FIX: new lib/agents/audioMode.ts — NORMAL_MODE = InterruptionModeAndroid.DuckOthers +
  shouldDuckAndroid:true (transient GAIN_TRANSIENT_MAY_DUCK: only briefly lowers other audio while
  BENSON speaks, then releases so the other app resumes on its own). RELEASED_MODE = same but
  staysActiveInBackground:false to force expo-av to abandon focus. openaiTTS.ts now uses
  setNormalAudioMode() (was DoNotMix). index.tsx: setNormalAudioMode() at init (so device-TTS/chime
  users get the safe mode from the start); enterSilentMode() + notification STOP now unloadWakeChime()
  + releaseAudioFocusMode() → guaranteed focus release WITHOUT a restart (fixes "STOP didn't help");
  exitSilentMode() restores setNormalAudioMode()+preloadWakeChime; toggleMute() releases focus on
  (lets you watch video while BENSON listens silently) and restores on off. wakeChime.ts gained
  unloadWakeChime().
- tsc 0 errors; eslint clean on new/edited lib files, index.tsx at pre-existing baseline. NATIVE +
  OS-runtime behavior → user verifies on device (play music → trigger wake/command → music resumes,
  no restart). testing_agent cannot exercise audio focus (native-only app won't load on web).


- Reaffirmed: wake word "Benson" hands-free stays the PRIMARY feature — silent/mute are opt-in and
  off by default; when neither is active, wake word works exactly as before.
- NOTIFICATION TOGGLE (option d, JS-only, no rebuild): the foreground-service notification already
  exposes STOP + LISTEN actions. STOP → enterSilentMode (done earlier); LISTEN now → exitSilentMode
  when silenced (else the usual manual-activation fallback). So the top-bar notification is a full
  off/on toggle reachable outside the app. (Quick Settings Tile / option a was NOT chosen.)
- "DOAR MUT" (mute-only) mode: NEW muted state + mutedRef + AsyncStorage 'bensonMuted'. Keeps ALL
  listening (wake word + commands) but suppresses ALL sound: speak() & speakText() bail on
  (silenced||muted); the wake chime is skipped when muted; toggleMute() cuts any in-progress TTS on,
  speaks a short confirm on off. Restored in init. Distinct from silenced (which stops listening).
- ENTRY POINTS for mute: (1) on-screen — BensonMainScreen TopControls now shows two top-right pills
  when active: "MUT"/"MUT PORNIT" (gold-filled when on) + "OPREȘTE" (full off); the big red
  "BENSON E OPRIT" banner still replaces them while silenced. New props muted/onToggleMute. (2) voice —
  MUTE_ON_PATTERN ("mod mut"/"fără sunet"/"oprește sunetul"/"nu mai vorbi"/"taci dar ascultă"/"mute")
  & MUTE_OFF_PATTERN ("pornește sunetul"/"cu sunet"/"vorbește din nou"/"unmute"), both directions
  (mic stays on in mute). Checked before SILENCE_ON so "taci dar ascultă" ≠ full-off "taci".
- Learn Mode (behavior training via conversation: editable facts + personalized behavior rules,
  local) — user-requested, DEFERRED to backlog (do after this + phone test), per user 2026-06.
- tsc 0 errors; eslint at pre-existing baseline (no new errors). NATIVE-ONLY → user verifies on build.


- PROBLEM (user): BENSON wouldn't fully stop on demand — kept making mic clicks/pops + TTS/chimes
  even when the user needed total silence (meetings/public). Needed an easy, sticky "off".
- FIX: global silent/off kill switch. index.tsx: silenced state + silencedRef (set synchronously),
  AsyncStorage 'bensonSilenced'. enterSilentMode() stops ALL listening (stopWakeScan, stopRecognition,
  pauseHotword, clears wake/conv/listening flags) and ALL sound (stopSpeaking, stopOpenAITTS,
  setSpeaking false). exitSilentMode() persists off, restarts service + resumePassiveWake + one spoken
  "Am revenit". Guards added (bail while silenced) to: speak, speakText, doStartListening,
  startLocalWakeLoop, resumePassiveWake, handleWakeDetected, handleMedallionTap, wake-chime preview,
  the boot service-start effect, and enterChatMode (no greet/listen on boot if left muted) — so NO
  auto-resume path (endSub, AppState 'active', wake events, a11y reminder) can turn it back on. Never
  self-reactivates; only an explicit user action clears it.
- ENTRY POINTS (easy access): (1) on-screen control — components/BensonMainScreen.tsx SilenceButton:
  top-right gold "SILENȚIOS" pill when active; big red full-width "BENSON E OPRIT · atinge ca să
  pornești" banner when silenced (new props silenced/onToggleSilence). (2) notification STOP action
  now calls enterSilentMode (was a partial stop). (3) voice command SILENCE_ON_PATTERN
  ("taci"/"liniște"/"mod silențios"/"oprește-te complet"/"gura" + EN/DE) handled in
  trySettingsVoiceCommand — ENTER only (exit needs the tap/notification since mic is off). Entering is
  silent by design (no TTS ack); exiting speaks the confirmation.
- Also this batch: RECENTS voice command (native openRecents, needs rebuild) + REMINDER INTERVAL 5/15/30.
- tsc 0 errors; eslint at pre-existing baseline (no new issues). NATIVE-ONLY app → user verifies on
  the Android build; testing_agent cannot exercise it (native module imports throw on web/Expo Go).


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
## 2026-06 (fork) — WHISPER MODEL: bundled → RUNTIME DOWNLOAD (deploy-readiness fix)
- CONTEXT: deployment_agent readiness check before user's "Publish" found the real blocker: local
  Whisper STT (default engine + the wake-word VAD loop) loaded a BUNDLED asset
  (android/app/src/main/assets/models/ggml-base.bin) via isBundleAsset:true, but the ~141MB model is
  git-ignored (/whisper-models) and ABSENT from the Emergent build container. plugins/withBundledAssets.js
  silently skips a missing source → a "Publish"-built APK would ship WITHOUT the model → on-device
  listening (commands AND wake word) silently broken. User builds via Emergent Publish and no longer
  has the model file → chose RUNTIME DOWNLOAD (2026-06).
- FIX (lib/agents/localWhisperEngine.ts): model is now fetched ONCE at first launch to the app's
  writable doc dir and opened from there (isBundleAsset:false). URL =
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin (exact 147,951,465 bytes,
  verified — size check forces a clean re-download of a partial/corrupt file). Uses
  expo-file-system/legacy createDownloadResumable (progress). New subscribeWhisperStatus() broadcasts
  idle/downloading{progress}/ready/error; ensureModel() is idempotent + single in-flight; getContext()
  = ensureModel → initWhisper(filePath,isBundleAsset:false). preloadLocalWhisper unchanged signature
  (auto-runs at boot when engine='local', the default, so download starts on first launch).
- UI (app/index.tsx): subscribeWhisperStatus → whisperStatus state → top banner (mirrors a11y banner):
  "Se descarcă modelul de voce… N%" while downloading, and an error banner with a REÎNCEARCĂ button
  (calls preloadLocalWhisper) on failure. So a fresh install never looks frozen.
- IMPACT: withBundledAssets.js Whisper entry is now dead-but-harmless (still safely skips). Porcupine
  is NOT active (no Picovoice key/.ppn) → wake word uses the local Whisper VAD loop, so this one
  download covers BOTH wake word + command capture. Emergent-Publish APKs are now self-sufficient.
- VERIFIED: tsc 0 errors; eslint localWhisperEngine.ts clean; index.tsx at its pre-existing 15-item
  baseline (no new). Native-only → user verifies on the built APK (needs Wi-Fi on first launch).

## 2026-06 (fork) — DEPLOY BLOCKER #2 FIXED: repo layout drift (app at root → /app/frontend)
- SYMPTOM: user's Publish failed at the FIRST build step:
  "[BUILD] prepare build context: read envs: read env file frontend\.env: open ... (no such file)".
  Also expo preview supervisor was FATAL ("couldn't chdir to /app/frontend: ENOENT").
- ROOT CAUSE: the BENSON repo was imported with the Expo app at the /app ROOT, but the entire
  platform (/, entrypoint.sh, supervisor `directory=/app/frontend`, and the deploy build context)
  is hardcoded to expect the Expo app at /app/frontend (reads /app/frontend/.env, app.json,
  node_modules, scripts/sync-shims.sh). Structural mismatch → deploy + preview both broken.
- FIX (with explicit user approval): relocated the whole Expo app from /app into /app/frontend
  (app/, assets/, components/, constants/, hooks/, lib/, modules/, plugins/, scripts/, src/, tools/,
  app.json, package.json, tsconfig.json, eslint.config.js, node_modules/, .gitignore, docs). Kept
  /app/backend, /app/memory, /app/.git, /app/.emergent at root. Relative imports unaffected (whole
  tree moved together). Created /app/frontend/.env with placeholder EXPO_PACKAGER_HOSTNAME /
  EXPO_PACKAGER_PROXY_URL / EXPO_PUBLIC_BACKEND_URL / EXPO_TUNNEL_SUBDOMAIN lines (entrypoint sed +
  deploy manage_secrets populate them). Fixed /app/frontend/.gitignore (removed broad
  .env/.env.*/*.env ignores → keep only .env*.local) so the deploy build context can read .env.
- ALSO: generated /app/frontend/yarn.lock (via `yarn install`) and removed the mismatched npm
  package-lock.json — the pipeline installs with `yarn --frozen-lockfile`, which needs a yarn.lock.
- VERIFIED: tsc 0 errors from /app/frontend; no hardcoded /app/ paths in source; backend health ok;
  expo + backend + mongodb all RUNNING; deployment_agent: frontend/.env blocker GONE,
  expo_backend_reachable=true, dockerignore_blocks=false, dependency_manifests_valid=true. Only
  remaining deployment_agent findings are iOS-only (Android-only app by design → not blockers for the
  user's Android APK). READY to re-Publish for Android.
