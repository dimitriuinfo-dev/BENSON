# TRUTH.md — Ground Truth Baseline

Generated: 2026-07-16, Phase 0 of the Recovery & Rebuild plan. Supersedes RESUME.md and
FINAL_BUILD_REPORT.md as the authoritative status source — those two are kept for history but
their claims are treated as UNVERIFIED until re-confirmed here with fresh on-device evidence.

## Phase 0.1 — git reconciliation

- HEAD: `3342a38` (chore: commit accumulated BENSON 5-36 feature backlog).
- Working tree (uncommitted, all from this session): `RESUME.md`, `app/index.tsx`,
  `lib/agents/voiceAgent.ts`, `modules/benson-accessibility/.../BensonAccessibilityService.kt`,
  `modules/benson-foreground-service/**` (build.gradle, manifest, 4 Kotlin files modified, 2 new:
  `BensonHealthWorker.kt`, `BensonQsTileService.kt`). 446 insertions / 92 deletions across 12
  tracked files, plus 7 untracked `benson_diag*.png` debug screenshots (not source, not committed).
- Every claim in `FINAL_BUILD_REPORT.md` (Items 0-4: "CODE DONE, UNVERIFIED on-device" for 1-4,
  Item 0 "PARTIAL") is marked **UNVERIFIED** here — none were re-tested this session; this
  session's work was entirely about the voice pipeline itself, not those features.
- `RESUME.md`'s prior "stale android/ folder" root-cause theory was investigated further and
  **retracted mid-session** (see its own current text) — the real bugs were elsewhere (below).

## Phase 0.2 — clean build check

**PASS**, with fresh evidence this session (repeated, most recently at 09:13:46 device-local
time):
- `npx expo run:android` — `BUILD SUCCESSFUL` from current HEAD + all uncommitted work.
- Installs: `adb shell dumpsys package com.benson.butler` → `lastUpdateTime=2026-07-16 09:13:46`.
- Launches without crash: `adb shell pidof com.benson.butler` → live pid (`27114` as of this
  writing), `adb logcat -d | grep FATAL EXCEPTION|AndroidRuntime` → zero matches across the whole
  session's testing.
- Accessibility Service binds correctly when enabled: `dumpsys accessibility` → `Bound
  services:{Service[label=Benson, ...]}` (non-empty).

## Phase 0.3 — what provably works RIGHT NOW (with evidence)

- **Build/install/launch**: PASS (above).
- **Native wake-word loop runs continuously without churning**: PASS — after the
  SpeechRecognizer-instance-reuse fix, `BensonHotword` tag shows steady ~2-6s burst cycling with
  occasional clean `NO_MATCH`, no more back-to-back `NO_MATCH`/`SERVER_DISCONNECTED` pairs every
  400-500ms (was confirmed broken before the fix: `ConcurrentSodaManager: Initializing SODA` on
  nearly every burst).
- **Mic → STT → JS → Mission Orchestrator pipeline is wired end-to-end**: PASS, partial-content
  evidence — `[MissionOrchestrator] rawText= penson normalizedText=` (Metro log,
  09:1x) proves a real spoken utterance reached the orchestrator; separately, an earlier session
  utterance ("Sun-o pe Hanna pe WhatsApp") was captured in full, correctly planned into a mission,
  and reached `WAITING_FOR_CONFIRMATION` with a spoken "Confirmi?" the user confirmed hearing.
- **TTS is audible**: PASS — user directly confirmed hearing "Confirmi?" this session.
- **Accessibility resurrection anchor (Guardian) code path exists and compiles**: code-complete,
  see Phase 0.4 — NOT yet verified against a real kill.

## Phase 0.4 — code-complete but UNVERIFIED (needs TEST READY / human)

1. **STT silence-timeout fix** (`lib/agents/voiceAgent.ts`, `androidIntentOptions` added to
   `startRecognition`, live via Metro since ~09:19) — intended to fix the exact "penson" (bare
   wake word, cut off before the rest of the sentence) failure above. No successful full-sentence
   capture observed with this fix active yet.
2. **Mission-Orchestrator confirmation-resume fix** (`app/index.tsx` — `speakText(...,
   onFinished)` replacing fire-and-forget `speak()` after "Confirmi?"/"Pentru cine?") — intended
   to fix "asked to confirm, then nothing" (user's exact words). Not yet re-exercised after the
   fix landed.
3. **Guardian resurrection** (item-0c: `BensonAccessibilityService.maybeResurrect`, heartbeat via
   `BensonWatchdogReceiver`, `ACTION_REVIVE`, WorkManager `BensonHealthWorker`, QS tile) — never
   tested against a real `adb shell am kill` or an organic OxygenOS kill. Spoken "BENSON a revenit
   online" line never heard.
4. **Items 1-4** (contacts voice search, native calls, WhatsApp calls, organic UI) — unchanged
   from `FINAL_BUILD_REPORT.md`: code-complete, zero on-device voice verification this session or
   last.

## Phase 0.5 — confirmed FIXED this session (bug → fix → evidence)

- **Native hotword loop destroyed/recreated `SpeechRecognizer` every ~1-2s** → now reuses one
  instance across bursts → evidence: burst cadence and SODA-init frequency both dropped
  dramatically post-fix (see 0.3).
- **Mission Orchestrator confirmation questions used fire-and-forget `speak()`** → switched to
  `speakText()` with an `onFinished` resume callback → code fixed, verification pending (0.4-2).
- **`android/local.properties` had no `sdk.dir` after a clean prebuild** → fixed directly, build
  succeeds.
- **JS-side STT (`ExpoSpeechRecognitionModule.start()`) had no silence-timeout tuning**, unlike
  the native hotword burst which already had it → added matching `androidIntentOptions` → fixed,
  verification pending (0.4-1).

## Phase 0.6 — confirmed FAIL / retracted theories

- "Stale generated `android/` folder missing native modules" — **retracted**, was based on a
  flawed test (grepping a compressed `.apk` as plain text). The real installed code always had the
  wake-word/overlay modules.
- Accessibility Service was found fully OFF (not just disconnected) once this session — root
  cause: **operator error** (this agent's own `adb shell am force-stop` calls, which Android
  itself auto-disables an app's accessibility service for — confirmed reproducible: happened twice
  more later in the session whenever `force-stop` was used for testing). Not a device/OEM bug.
  Operational note: **do not use `force-stop` to simulate an OxygenOS kill** — it clears
  accessibility Settings state, which a real OEM background-kill does not; use `adb shell am kill
  <pid>` instead for Guardian testing per the plan's own Phase 1.3 instruction.
- Not yet fixed, out of scope of this recovery plan: the on-screen greeting still says "Master,
  sunt aici" (`components/BensonMainScreen.tsx` or its greeting-string source), contradicting
  `BENSON_ENGINEERING_RULES.md`'s explicit "no hardcoded Master default" rule. Flagged, not
  touched — outside this plan's voice-core scope and outside this agent's UI-file authorization.

## Phase 1 — Audio chain forensic diagnosis (superseded the original 10/10 plan; see
## AUDIO_DIAGNOSIS_REPORT.md for full detail, this section is the compact stage table)

Architecture correction, PROVEN by source inspection (not assumed): no dedicated wake-word
engine, no Porcupine/Picovoice/Vosk/Whisper, no model file, no raw `AudioRecord`. Both passive
"Benson" detection and command capture use plain `android.speech.SpeechRecognizer`; the "wake
word" is a regex (`WAKE_REGEX`) matched against whatever that recognizer transcribes.

| Stage | Runtime evidence | Status | Decisive marker | Notes |
|---|---|---|---|---|
| Permission | granted, `USER_SET`, appops `allow`/running | PROVEN PASS | `dumpsys package`/`appops get` | |
| Recorder creation | created every burst | PROVEN PASS | `RECORDER_CREATE session=wake-N` | |
| Recorder running | `isMainThread=true` always | PROVEN PASS | `RECORDER_STARTED ... isMainThread=true` | rules out threading as a cause |
| Audio activity (RMS proxy) | non-flat, 5-10 range | PROVEN PASS | `PCM_STATS_SUBSTITUTE_RMS rms=9.76` | no raw PCM access exists in this architecture, see report §3 |
| Wake engine init | N/A — no dedicated engine | N/A | `WAKE_ENGINE_INIT success=true` (regex setup only) | |
| Model packaging | N/A — no model file | N/A | — | |
| Recognition producing ANY text (passive, pre-fix) | **86/86 sessions: zero** | **PROVEN FAIL — root cause #1** | `STT_ERROR ... code=7 name=ERROR_NO_MATCH` × 86 | wrong language (system de-DE, user speaks ro-RO) |
| Recognition producing ANY text (passive, post-fix) | `SR_CONFIG language=ro-RO` confirmed active | FIX DEPLOYED | `SR_CONFIG session=wake-10 mode=PASSIVE_WAKE language=ro-RO` | live re-verification of a passive `WAKE_ACCEPTED` NOT PROVEN |
| Wake detection (native) | never observed, pre- or post-fix | NOT PROVEN | — | test attempts landed on JS conv-mode path instead |
| Native→JS bridge | untested this run (no native detection to relay) | BLOCKED | — | |
| Command transcript (JS path, post language-fix) | correct text produced | PROVEN PASS | `STT_PARTIAL text="Sun o pe Baby pe WhatsApp"` | |
| Partial vs final result | **library never fires true final on this device** | **PROVEN FAIL — root cause #2** | 0 `STT_FINAL`, 4 `STT_PARTIAL`, 0 `ORCHESTRATOR_HANDOFF_REQUESTED` in the capture window | `EXTRA_PARTIAL_RESULTS=false` requested, not honored by this device's recognizer service |
| Orchestrator handoff | fix deployed, not freshly re-confirmed live | FIX DEPLOYED, NOT PROVEN | — | partial-transcript fallback added |

```
FIRST BROKEN STAGE: Passive/command recognition never produced a transcript (wrong language)
DECISIVE EVIDENCE: STT_ERROR session=wake-37 code=7 name=ERROR_NO_MATCH (86/86 passive sessions)
ROOT CAUSE: RecognizerIntent never set EXTRA_LANGUAGE; silently used device system locale
  (de-DE) instead of the user's spoken language (ro-RO).
FIX: setSttLanguage() JS->native bridge persists the language; runHotwordBurst() reads it fresh
  per burst and sets EXTRA_LANGUAGE. CONFIRMED ACTIVE post-rebuild.
SECOND ROOT CAUSE (downstream, only reachable once the first was fixed): this device's
  recognition service fires onPartialResults() with a complete, correct transcript but never
  calls the true onResults() callback despite EXTRA_PARTIAL_RESULTS=false being requested — the
  JS result handler only acted on isFinal=true, so correct transcripts were silently discarded.
FIX: partial-transcript fallback (lastPartialTranscriptRef, session-id-keyed) in app/index.tsx's
  resultSub/endSub — processes the last captured partial if a session ends without ever firing a
  true final result. DEPLOYED, TYPE-CHECKED, NOT YET independently re-verified live end-to-end.
DIRECT STT RESULT: 3 correct transcripts captured (pre-second-fix, all discarded); 0/5 fresh
  post-both-fixes live confirmations obtained before this report was written.
WAKE WORD RESULT: 0/5 — no WAKE_ACCEPTED observed in this diagnostic run, pre- or post-fix.
```

Full detail, all evidence lines, and the complete stage-by-stage narrative: `AUDIO_DIAGNOSIS_REPORT.md`.

## Next: resume Phase 1 (vital core) verification

Once the Defect B fix (partial-transcript fallback) gets a fresh live confirmation (a spoken
command that produces `TRANSCRIPT_ACCEPTED` + `ORCHESTRATOR_HANDOFF_COMPLETED` in the same
BENSON_AUDIO capture), re-run the original 10/10 wake-word + direct-STT consecutive-success plan.
Requires the human for each `TEST READY` prompt.
