# BENSON — Audio Chain Forensic Diagnosis and Repair

Date: 2026-07-16. Device: OnePlus Nord 4 (CPH2663), serial 9c1464eb, OxygenOS 15, system locale de-DE.

## 1. Files inspected

- `modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/BensonForegroundService.kt` — native passive "Benson" loop.
- `modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/BensonForegroundServiceModule.kt` — JS↔native bridge.
- `lib/agents/voiceAgent.ts` — JS wrapper around `expo-speech-recognition`.
- `app/index.tsx` — orchestration: mic button, wake-word listener, `doStartListening`, `handleIncomingText` → `runMission`.
- `node_modules/expo-speech-recognition/android/src/main/java/expo/modules/speechrecognition/ExpoSpeechService.kt` — third-party library's native Android implementation (read, not modified).
- `node_modules/expo-speech-recognition/android/src/main/java/expo/modules/speechrecognition/ExpoSpeechRecognitionModule.kt` — confirms `interimResults` → `RecognizerIntent.EXTRA_PARTIAL_RESULTS` mapping.
- `app/debug.tsx` — Debug Panel (audio diagnostics toggle added here).

## 2. Files modified

- `modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/AudioDiag.kt` — **new**, the `BENSON_AUDIO` logging authority + diagnostics flag.
- `modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/BensonForegroundService.kt` — instrumentation (service lifecycle, `SR_*`, `WAKE_*`, `PASSIVE_*`, `SCREEN_STATE` receiver, `MIC_HANDOVER`) + the language fix (`EXTRA_LANGUAGE` on the passive-loop intent).
- `modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/BensonForegroundServiceModule.kt` — `isAudioDiagnosticsEnabled`/`setAudioDiagnosticsEnabled`/`logAudioDiag`/`setSttLanguage` bridge functions.
- `modules/benson-foreground-service/index.js` / `index.d.ts` — JS wrappers for the above.
- `app/index.tsx` — JS-side `BENSON_AUDIO` instrumentation (`STT_REQUESTED`/`STT_PRECHECK`/`STT_PARTIAL`/`STT_FINAL`/`STT_ERROR`/`STT_STOPPED`/`ORCHESTRATOR_HANDOFF_*`/`APP_STATE`/`WAKE_EVENT_RECEIVED_IN_JS`/`WAKE_HANDOFF_TO_STT`) + two functional fixes (`setSttLanguage` calls on language change/restore; the partial-transcript fallback in `resultSub`/`endSub`).
- `app/debug.tsx` — `AudioDiagnosticsPanel` (toggle + Guardian recovery-events readout).

**Checkpoint note:** the instrumentation and the two functional fixes ended up touching the same functions in the same files (the language fix sits directly inside the instrumented `runHotwordBurst()`; the partial-transcript fix sits directly inside the instrumented `resultSub`/`endSub`). Cleanly separating them into `item-audio-instrumentation` and `item-audio-fix` commits would require manual patch surgery (splitting individual hunks within the same function) which is high-risk to do via automated tooling. Per the escape valve in the operating instructions, these are committed together as one checkpoint, documented honestly here rather than presenting a fake clean split. **See "Commits" below.**

## 3. Engine and model identification

**There is no dedicated wake-word engine, no Porcupine/Picovoice/Vosk/Whisper, no keyword/model file, no direct `AudioRecord` PCM pipeline, and no wake-word confidence score.** Confirmed by direct source inspection (not assumed from file names) — grepped the entire repo for `porcupine|picovoice|vosk|whisper|\.ppn|\.pv|AudioRecord`; every match was a code comment referencing the underlying Android `AudioRecord` that `SpeechRecognizer` itself uses internally, plus one comment explicitly confirming "No third-party wake-word SDK (e.g. Picovoice) is used here."

Both passive listening and command capture use plain `android.speech.SpeechRecognizer`:
- **Passive ("Benson") path**: native, inside `BensonForegroundService.kt`. Repeated `SpeechRecognizer` sessions (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`); the transcribed text is matched against `WAKE_REGEX` (a Kotlin `Regex`, not a trained model) for fuzzy variants of "benson".
- **Command-capture path**: JS, via `expo-speech-recognition`'s `ExpoSpeechRecognitionModule`, wrapped in `lib/agents/voiceAgent.ts`.

Because there is no raw `AudioRecord` access, **PCM frame counters, `framesRead`/`bytesRead`, and true peak-amplitude/RMS-from-samples are not obtainable** in this architecture. `onRmsChanged(rmsdB: Float)` (a `SpeechRecognizer` lifecycle callback reporting a coarse relative input level) is the closest legitimate substitute and is what `PCM_STATS_SUBSTITUTE_RMS` reports, rate-limited to ~1/s.

## 4. Audio configuration (as actually found, not assumed)

| | Passive (native) | Command capture (JS) |
|---|---|---|
| Action | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` | same (via library) |
| Language model | `LANGUAGE_MODEL_FREE_FORM` | library default |
| `EXTRA_LANGUAGE` | **not set before this session's fix** (silently used device system locale, `de-DE`) | explicitly set to `langRef.current` (`ro-RO` in this user's config) |
| `EXTRA_PARTIAL_RESULTS` | `false` | `false` (via `interimResults: false`) — **not honored by this device's recognition service**, see §6 |
| Silence timing | `MINIMUM_LENGTH=800ms`, `COMPLETE_SILENCE=1800ms`, `POSSIBLY_COMPLETE=1200ms` (tuned in a prior session) | same values, added this session (previously unset, relying on OS defaults) |
| Audio source | Not set explicitly by either path — `RecognizerIntent` has no direct audio-source extra; the recognition SERVICE (Google's, on this device) owns source selection internally. Not configurable at this level; no raw `AudioRecord`. | |
| Sample rate / channel / encoding | Not directly observable or configurable — abstracted away entirely by `SpeechRecognizer`. | |

## 5. Microphone ownership

- **Passive loop**: one reused `SpeechRecognizer` instance inside `BensonForegroundService` (fixed in a prior session to stop destroying/recreating it every burst).
- **Command capture**: a separate `SpeechRecognizer` session inside the `expo-speech-recognition` native module.
- **Handover**: `pauseHotword()`/`resumeHotword()` (native `Promise`-backed, resolves only after `stopHotwordLoop()`/`startHotwordLoop()` actually run on the main looper) — this session, `pauseHotword()` was moved to be called **unconditionally** at the top of `doStartListening()` (previously only 2 of 15+ call sites called it), closing a real confirmed mic-contention gap (interleaved `ro-RO`/`de-DE` sessions, one with real detected speech cut off mid-utterance by the other starting). `MIC_HANDOVER` markers confirm clean handoffs post-fix (`wakeRecorderStopped=true`, `wasRunning=false` on the JS side taking over).

## 6. PCM/audio-activity evidence

86 consecutive passive sessions were captured in the pre-fix baseline test. Representative:

```
RECORDER_STARTED session=wake-37 component=hotword_loop success=true isMainThread=true
STT_READY_FOR_SPEECH session=wake-37
STT_BEGINNING_OF_SPEECH session=wake-37
PCM_STATS_SUBSTITUTE_RMS session=wake-37 rms=9.76
STT_END_OF_SPEECH session=wake-37
STT_ERROR session=wake-37 code=7 name=ERROR_NO_MATCH
```

`isMainThread=true` on every single `RECORDER_STARTED` line across the whole test — **threading is not the defect** (root-cause candidate #3, rejected with direct evidence). Real, non-flat RMS (typically 5-10) and regular `onBeginningOfSpeech` firing prove the microphone itself is capturing real audio — **the mic/permission/recorder chain is not dead** (candidates #1, #2, #4 rejected).

## 7. Wake candidate / detection evidence

`WAKE_REGEX_CONFIG pattern="\b(benson|bensen|bensson|benzon|bentson|bennson|bänson|bensn|benzine|benzin|penson|penzon|benz[ăa]|ben\s+son)\b[,.!?]?\s*(.*)"` — logged once per service start.

**Pre-fix**: across all 86 passive sessions, `WAKE_EVALUATION` (which only fires inside `onResults`) **never appeared once** — every session terminated via `onError(ERROR_NO_MATCH)` instead, meaning the recognizer never produced ANY transcript to evaluate, matched or not. This rules out candidate #8 (regex not matching a produced transcript) as the primary defect for the passive path and points earlier in the chain — to recognition quality itself.

## 8. STT lifecycle evidence — the two real defects found

### Defect A (native passive path): wrong recognition language

`getprop persist.sys.locale` / `ro.product.locale` / `settings get system system_locales` all confirm this device's system locale is **`de-DE`**. `RecognizerIntent` for the passive loop never set `EXTRA_LANGUAGE`, so recognition silently ran in German while the user spoke Romanian. This is root-cause candidate **#14 from the required list, confirmed**.

**Decisive evidence**: 86/86 passive sessions with real speech-detected audio (`onBeginningOfSpeech`, non-flat RMS) produced zero successful transcriptions, while the JS command-capture path — which *did* explicitly pass `ro-RO` — succeeded repeatedly the same night (see Defect B below for why those successes were nonetheless being discarded downstream).

**Fix**: JS now persists the user's selected language via a new native bridge function `setSttLanguage(lang)` (called on every language change and on the boot-time restore-from-storage effect) into the same `benson_watchdog_prefs` `SharedPreferences` file already used by the Guardian subsystem. `runHotwordBurst()` reads this fresh on every burst (not cached once) and sets `EXTRA_LANGUAGE` when present, falling back to the previous (system-default) behavior if never set.

**Post-fix evidence**: `SR_CONFIG session=wake-10 mode=PASSIVE_WAKE language=ro-RO` — confirmed active immediately after rebuild/install.

### Defect B (JS command-capture path): correct transcripts silently discarded

With Defect A's fix alone, the **JS conversation-mode path** (which already passed `ro-RO` even before this session) started producing excellent, fully correct transcripts:

```
STT_PARTIAL session=js-1784186710275 text="Benson Sun o pe Ana pe WhatsApp"
STT_STOPPED session=js-1784186710275 ...
STT_PARTIAL session=js-1784186743217 text="Sun o pe Baby pe WhatsApp"
STT_STOPPED session=js-1784186743217 ...
STT_PARTIAL session=js-1784186756276 text="Deschide YouTube"
STT_STOPPED session=js-1784186756276 ...
```

**But every one of these was discarded.** `app/index.tsx`'s result listener only acts when `isFinal === true`:
```js
if (!isFinal) return;
```
Across this entire capture window: **0 `STT_FINAL` events, 4 `STT_PARTIAL` events, 0 `ORCHESTRATOR_HANDOFF_REQUESTED` events.** Direct source inspection of the library (`ExpoSpeechService.kt`) confirms `interimResults: false` correctly maps to `RecognizerIntent.EXTRA_PARTIAL_RESULTS = false` — but this specific device's recognition service does not honor that extra: it fires `onPartialResults()` (→ `isFinal: false`) with a complete, correct transcript, then ends the session via `onError`/timeout without ever calling the true `onResults()` callback that would set `isFinal: true`. This is a known category of Android/OEM recognizer-service fragmentation, not a bug in this app's own request.

**Decisive evidence line**: `STT_PARTIAL session=js-1784186743217 component=js_stt text="Sun o pe Baby pe WhatsApp"` immediately followed by `STT_STOPPED` — a perfectly good, fully-formed command, discarded.

**Fix**: the JS result listener now tracks the last non-empty partial transcript per session (`lastPartialTranscriptRef`, keyed by session id so a stale partial from an earlier, already-handled session can never leak forward). If a session ends (`'end'` event) without ever having received a true final result, the tracked partial is used as the effective transcript and passed to `handleIncomingText` — the same function a genuine final result would have reached. Not a library patch (would be lost on `npm install`); a defensive consumer-side fallback.

**Post-fix status**: fix implemented, type-checked, and deployed to the device. A fresh live end-to-end confirmation (new `TRANSCRIPT_ACCEPTED source=partial_fallback` + `ORCHESTRATOR_HANDOFF_COMPLETED` in the same test run) was **not obtained** before this report was written — the device's screen repeatedly went to sleep between check-in windows, and no further command was captured in the final verification window. The fix is a direct, narrowly-targeted, low-risk consumer of already-proven-correct data (the exact transcripts shown above), so confidence is high, but this is stated plainly as **NOT YET independently re-verified live**, per the "do not report the voice path as fixed merely because ..." instruction.

## 9. Microphone ownership evidence (Defect A/B interaction with the earlier mic-contention fix)

`MIC_HANDOVER` markers post-fix show clean handoffs:
```
MIC_HANDOVER from=hotword_loop to=stt wakeRecorderStopped=pending
VOICE_MODE actual=off component=hotword_loop
MIC_HANDOVER from=hotword_loop to=stt wakeRecorderStopped=true wakeRecorderReleased=true wasRunning=false
```
No `ERROR_RECOGNIZER_BUSY` observed anywhere in any capture this session — candidate #11 rejected.

## 10. First broken stage (per the required stage table)

| Stage | Runtime evidence | Status | Decisive marker |
|---|---|---|---|
| Microphone permission | granted, `USER_SET`, appops `allow` and actively `running` | PASS | `dumpsys package` / `appops get RECORD_AUDIO` |
| Foreground service alive | `SERVICE_CREATE`, `SERVICE_FOREGROUND_STARTED` fire on every launch | PASS | native lifecycle markers |
| Passive recognizer created | `SR_CREATE`/`RECORDER_CREATE` per burst | PASS | `RECORDER_CREATE session=wake-N` |
| Listener attached | reused listener, set once | PASS | code inspection |
| Passive `startListening` called | `isMainThread=true` every time | PASS | `RECORDER_STARTED ... isMainThread=true` |
| Ready for speech / audio activity | `STT_READY_FOR_SPEECH`, `STT_BEGINNING_OF_SPEECH`, non-flat RMS | PASS | `PCM_STATS_SUBSTITUTE_RMS rms=9.76` |
| **Passive partial/final text (pre-fix)** | **never produced — always `ERROR_NO_MATCH`** | **FAIL — first broken stage (Defect A)** | `STT_ERROR session=wake-37 code=7 name=ERROR_NO_MATCH` × 86 |
| WAKE_REGEX evaluation | never reached (no text to evaluate) pre-fix | BLOCKED by above | — |
| Command transcript (JS path, post Defect-A-fix) | produced correctly, but discarded | FAIL — second broken stage (Defect B) | `STT_PARTIAL ... text="Sun o pe Baby pe WhatsApp"` then `STT_STOPPED`, zero `STT_FINAL` |
| Orchestrator handoff | never reached this session (blocked by Defect B until its fix, unverified live post-fix) | NOT PROVEN post-fix | — |

## 11. Root cause (both, in order)

1. **`FIRST BROKEN STAGE: Passive partial/final text (recognition)`** — `DECISIVE EVIDENCE: STT_ERROR session=wake-37 thread=main component=hotword_loop code=7 name=ERROR_NO_MATCH` (representative of all 86/86 passive sessions) — `ROOT CAUSE: RecognizerIntent for the passive loop never set EXTRA_LANGUAGE, silently defaulting to the device's system locale (de-DE) while the user speaks Romanian.`
2. Second, downstream defect (only reachable once #1 is fixed): `STT_PARTIAL session=js-1784186743217 component=js_stt text="Sun o pe Baby pe WhatsApp"` followed by `STT_STOPPED` with zero `STT_FINAL` — `ROOT CAUSE: this device's recognition service ignores EXTRA_PARTIAL_RESULTS=false and never calls the true final callback, so app/index.tsx's isFinal-only result handler silently discarded every correct transcript.`

## 12. Fixes applied

1. `setSttLanguage()` native bridge + `runHotwordBurst()` reads it fresh per burst, sets `EXTRA_LANGUAGE`. **Confirmed active post-rebuild** (`SR_CONFIG ... language=ro-RO`).
2. Partial-transcript fallback in `app/index.tsx`'s result/end listeners. **Deployed, type-checked, not yet independently re-verified live end-to-end** (see §8).

## 13. Wake-word path — honest, separate status

`DIRECT VOICE PATH` (JS command capture): transcription now works (Defect A fixed); full pipeline (transcription → orchestrator) has the Defect B fix deployed but not freshly re-confirmed live.

`WAKE-WORD PATH` (native passive "Benson" detection): the passive loop uses the **same underlying recognizer** as command capture and received the **same language fix** — mechanically it should now succeed the same way command capture did post-fix. However, **no session in this diagnostic run ever produced `WAKE_ACCEPTED`/`WAKE_DETECTED_NATIVE` with hard evidence**, pre- or post-language-fix — the passive loop was still cycling in the background during the final test windows but the user's spoken test attempts landed on the JS/conversation-mode path (already active), not fresh isolated wake-word-only attempts. This is reported honestly as **unconfirmed**, not assumed fixed by extension.

```
DIRECT VOICE PATH: FIX DEPLOYED, LIVE RE-VERIFICATION PENDING
WAKE-WORD PATH: FIX DEPLOYED (same language mechanism), LIVE RE-VERIFICATION PENDING
ROOT CAUSE: EXTRA_LANGUAGE unset on the passive RecognizerIntent (defaulted to system de-DE); separately, this device's recognizer never fires the true SpeechRecognizer final callback despite EXTRA_PARTIAL_RESULTS=false.
```

## 14. Test scores

- **Wake-word test (Test A, pre-fix baseline)**: 0/5 confirmed detections — but more precisely, 0/86 sessions across the whole pre-fix capture window ever produced any recognized text at all (not a regex-matching failure; a recognition failure, root-caused to Defect A).
- **Direct STT test (Test B)**: not completed as a clean isolated 5-attempt run (manual-mic sessions were captured opportunistically during conversation-mode use, screen-sleep interruptions prevented a clean dedicated window). Within the available captures: 3 correct transcripts observed pre-Defect-B-fix (all discarded); 0 fresh confirmations obtained post-Defect-B-fix before this report.

## 15. Remaining limitations (honest)

- Defect B's fix is deployed but **not independently re-verified with a fresh live transcript + orchestrator handoff** — this is the single most important next test.
- The native passive wake-word path's actual end-to-end detection (`WAKE_ACCEPTED` → `WAKE_EVENT_RECEIVED_IN_JS` → command capture) has never been observed with hard evidence in this diagnostic run, pre- or post-fix — only inferred from the shared recognizer/language mechanism.
- PCM-level diagnostics (true frame counts, peak amplitude from raw samples) are architecturally unavailable — `onRmsChanged` is the ceiling of what `SpeechRecognizer` exposes.
- This device's recognition service silently ignoring `EXTRA_PARTIAL_RESULTS=false` is itself an OEM/device-specific quirk, not something this app can force-correct at the API level — the partial-fallback consumer-side fix works around it rather than fixing the underlying non-compliant behavior.

## Commits

```
INSTRUMENTATION CHECKPOINT: (not separately committed — see "Files modified" note above)
AUDIO FIX CHECKPOINT: combined with instrumentation, single commit (see below)
```

## Required summary block

```
FIRST BROKEN STAGE: Passive/command recognition never produced a transcript (Defect A: wrong language)
DECISIVE EVIDENCE: STT_ERROR session=wake-37 thread=main component=hotword_loop code=7 name=ERROR_NO_MATCH (86/86 passive sessions, zero exceptions)
FIX APPLIED: EXTRA_LANGUAGE set from a JS-persisted preference (setSttLanguage), read fresh per burst; confirmed active (SR_CONFIG language=ro-RO)
DIRECT STT RESULT: 3 correct transcripts observed (pre partial-fix, all discarded by Defect B); 0/5 fresh post-both-fixes confirmations obtained live
WAKE WORD RESULT: 0/5 — no WAKE_ACCEPTED observed in this diagnostic run, pre- or post-fix
DIAGNOSTIC FLAG DEFAULT: ON
DIAGNOSTIC FLAG CURRENT STATE: ON (toggle exposed in Debug Panel, native-backed SharedPreferences flag)
TRUTH.md UPDATED: YES
```
