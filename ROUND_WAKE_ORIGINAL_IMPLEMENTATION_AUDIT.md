# ROUND_WAKE_ORIGINAL_IMPLEMENTATION_AUDIT

Audit only. No code touched, no build, no install, no device test performed this round.
Every claim below is cited to a file/line or an existing report already in the repo.

---

## ORIGINAL_WAKE_ENGINE

There are **two** wake engines in the codebase, not one — and the one that actually works on
this device today is **not** the one most of the git history was originally built around.

1. **Legacy native engine — Android `SpeechRecognizer`, regex over its transcript.**
   `BensonForegroundService.kt` runs short repeated `SpeechRecognizer` bursts
   (`startHotwordLoop` → `startSpeechRecognizerLoop` → `runHotwordBurst`,
   `BensonForegroundService.kt:497-592`) and matches the result against a hardcoded
   `WAKE_REGEX` (`BensonForegroundService.kt:1193-1196`). This is the **original** wake
   mechanism the project was built with (see git history below) but it is **documented broken
   on this specific device**: `b0252a0` states plainly that this path "folosește ACELAȘI
   SpeechRecognizer de sistem care a picat" (uses the same system SpeechRecognizer that already
   failed), and `app/index.tsx:554` calls it "the old Android SpeechRecognizer hotword loop
   (kept as a fallback but broken on this device)".

2. **Current working engine — JS-driven local Whisper VAD scan ("Option A").**
   `startWakeScan()` in `frontend/lib/agents/voiceAgent.ts:261-285` reuses the same
   `benson-audio-capture` VAD+AudioRecord pipeline used for real command capture
   (`BensonAudioCaptureModule.kt`, energy-based VAD, no ML — see `EXACT_FILES_AND_FUNCTIONS`),
   transcribes the captured clip through the STT chain (Groq → Gemini → OpenAI → local
   `whisper.cpp`, `voiceAgent.ts:85-115`), and hands the resulting text to
   `detectWakeWord()` in `app/index.tsx:2736-2743`, which does a **substring/fuzzy match**, not a
   dedicated wake-word engine. This is what `app/index.tsx:553-557` calls "Option A" and defaults
   to (`wakeEngineRef = useRef<'local'|'native'>('local')`).

3. **A third, unfinished native TFLite ("microWakeWord") engine exists in code
   (`MicroWakeWord.kt`) but has never run on a device** — see `CAN_EXISTING_ENGINE_BE_MOVED_NATIVE`
   below. It is not "original" — it was added in the most recent round
   (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1`).

**Neither Porcupine nor a trained hotword model was ever the working engine.** Porcupine is
explicitly abandoned (`frontend/CLAUDE.md`: "Porcupine e abandonat — nivelul gratuit a fost
refuzat") and its `.ppn` files present in the tree are only Picovoice's stock demo keywords
(alexa/jarvis/computer/etc., in `android/…/build/intermediates/.../raw/*.ppn`) pulled in as
build artifacts of the Picovoice SDK dependency — not a custom "Benson" model, and only in the
`build/` output directory, i.e. generated, not authored.

## EXACT_FILES_AND_FUNCTIONS

**JS engine ("local", the one that has device-confirmed foreground hits):**
| File | Function/constant | Role |
|---|---|---|
| `frontend/lib/agents/voiceAgent.ts:261` | `startWakeScan(lang, onResult, onIdle)` | opens native VAD capture, transcribes result |
| `frontend/lib/agents/voiceAgent.ts:287` | `stopWakeScan()` | tears down the shared capture-end subscription |
| `frontend/lib/agents/voiceAgent.ts:85` | `transcribeAudio()` | Groq → Gemini → OpenAI → local Whisper fallback chain |
| `frontend/app/index.tsx:2912` | `startLocalWakeLoop()` | the JS re-arm loop: guards, calls `startWakeScan`, self-restarts every 150ms on a miss |
| `frontend/app/index.tsx:2736` | `detectWakeWord(text)` | the actual "is this Benson" test |
| `frontend/app/index.tsx:2730` | `WAKE_VARIANTS` | hardcoded literal name spellings |
| `frontend/app/index.tsx:2735` | `WAKE_UP_VARIANTS` | hardcoded "wake up" alt-phrase |
| `frontend/app/index.tsx:2744` | `lev(a,b)` | Levenshtein distance ≤1 fuzzy fallback |
| `frontend/app/index.tsx:2763` | `stripWakeWord(text)` | extracts same-breath command tail after the name |
| `frontend/app/index.tsx:2774` | `handleWakeDetected(commandTail)` | single shared post-wake handler (both engines call it) |
| `frontend/modules/benson-audio-capture/.../BensonAudioCaptureModule.kt:51-57` | `RMS_THRESHOLD`, `MIN_SPEECH_MS`, `SILENCE_TIMEOUT_MS` | the energy VAD gating what audio gets transcribed at all |

**Legacy native engine (original, now the disabled fallback):**
| File | Function/constant | Role |
|---|---|---|
| `frontend/modules/benson-foreground-service/.../BensonForegroundService.kt:1193-1196` | `WAKE_REGEX` | hardcoded Kotlin `Regex`, `IGNORE_CASE`, matches `benson`/misspellings + captures the command tail in group 2 |
| same file:497 | `startHotwordLoop()` | engine-switch entry (`porcupine` vs `speechrecognizer`, `:474-479`) |
| same file:574 | `startSpeechRecognizerLoop()` | creates `SpeechRecognizer`, logs `WAKE_REGEX_CONFIG pattern="…"` and `WAKE_CONFIG keywordLiteral="benson" matchMechanism=regex_over_full_transcript` (line 587-590) |
| same file:656 | `hotwordListener.onResults` | runs `WAKE_REGEX.find(it)` against the transcript (line 680) |
| same file:464-472 | `WakeGate` (`RMS_THRESHOLD=350.0`) | a cheap RMS energy probe gates whether a `SpeechRecognizer` burst is even started (battery motive) |

## HOW_"BENSON"_WAS_DEFINED

**Hardcoded in three separate places, all literal strings/patterns, none read from Settings or
user configuration:**

1. Native regex: `BensonForegroundService.kt:1193-1196` —
   `Regex("\\b(benson|bensen|bensson|benzon|bentson|bennson|bänson|bensn|benzine|benzin|penson|penzon|benz[ăa]|ben\\s+son)\\b[,.!?]?\\s*(.*)", RegexOption.IGNORE_CASE)`.
2. JS substring list: `app/index.tsx:2730` —
   `const WAKE_VARIANTS = ['benson','bensen','benzon','bension','pension','penson','benton','bensons']`.
3. JS fuzzy fallback: `app/index.tsx:2742` — any token of length ≥5 within Levenshtein distance 1
   of the literal string `'benson'`.
4. JS command-tail extraction: `app/index.tsx:2764` — `search(/bens|benz|bent|pens/)`, a fourth
   independent hardcoded pattern used only to find where to cut the string, not to decide a match.

There is no settings field, constant-from-config, or remote value anywhere in the codebase that
feeds these — confirmed by grep across `*.ts*` for `assistantName`/`wakeName`/`customWakeWord`
(no hits). The name is baked into source on both the native and JS sides independently.

## CAN_WAKE_NAME_BE_DYNAMIC

**Not currently.** It is compiled/interpreted literal text in four places (three regex/list
literals above, one on the Kotlin side, three on the JS side). Nothing reads it from
`AsyncStorage`, `settingsStore.ts`, or any native persisted pref. Making it dynamic would require:
adding a settings field, threading it into `detectWakeWord`/`stripWakeWord`/`WAKE_VARIANTS`
(straightforward, JS-only), **and** into `WAKE_REGEX` on the Kotlin side (needs a native
settings bridge — `BensonForegroundServiceModule.kt` already has the pattern for other
prefs like `setSttLanguage`/`setWakeWordEnabled`, so this is mechanical, not architectural).

## WOULD_"MARIUS"_WORK_WITHOUT_RETRAINING

**Yes, trivially, for the two engines that actually exist today** — neither is a trained
acoustic hotword model:

- The **legacy native engine** is a plain `SpeechRecognizer` (general Romanian/English ASR) +
  regex string match. Swapping the regex literal from `benson|bensen|...` to a `marius|mariuș|...`
  variant list is a one-line text edit — same mechanism, zero retraining, because there is no
  model to retrain; it was never a hotword model, it was general speech recognition with a
  string filter (`BensonForegroundService.kt:590`: `"no dedicated wake-word engine/model;
  SpeechRecognizer output regex-matched"`, its own log line says so).
- The **current JS/Whisper engine** is the same idea one layer up: general Whisper/Groq/Gemini
  ASR transcribes whatever was said, then `WAKE_VARIANTS`/Levenshtein does a text match. Renaming
  is again a literal-string edit in `app/index.tsx`, no retraining, because Whisper is a
  general-purpose transcriber, not a name-specific model.
- The **only engine where this would NOT be true** is the unfinished native TFLite engine
  (`MicroWakeWord.kt`) — that one requires a `benson.tflite` model trained specifically on the
  word "Benson" (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:132-138`). Renaming there means
  retraining a new `.tflite` from scratch. This engine has never run on the device (no model
  file exists — confirmed: no `.tflite` anywhere under `frontend/` or `android/` in this
  workspace), so it does not affect what "worked before."

**Bottom line: for every wake mechanism that has ever produced a real detection on this device,
"Marius" is a config/string change, not a retraining problem** — because none of them are
acoustic hotword models; they are general ASR + text matching.

## WHY_OLD_WAKE_WORKED

"Old" here means the JS local-Whisper loop, foreground — this is the only wake path with
device log evidence of actually firing. Per `ROUND_WAKE_STATE_BUG_1_REPORT.md` (device captures
on `9c1464eb`):

- While BENSON's Activity is in the foreground (or within ~1s of leaving it), the RN JS runtime
  (`mqt_v_js` thread) is alive and executing.
- `startLocalWakeLoop()` (`app/index.tsx:2912`) runs continuously: it opens a VAD-gated
  `AudioRecord` capture (`benson-audio-capture`), and on every miss re-arms itself via
  `setTimeout(startLocalWakeLoop, 150)` (`app/index.tsx:2949`).
- Each cycle transcribes through the STT chain and `detectWakeWord()` checks the text.
- Device evidence: `WAKE_HEALTH … recognizer=LISTENING WAKE_READY=true reason=ok` reliably
  within ~2s of returning to foreground (capture C3, `ROUND_WAKE_STATE_BUG_1_REPORT.md:22`), and
  `ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:113` explicitly states "JS fallback works
  foreground (STABILIZATION_1 gate confirmed)" for the wake-after-TTS case.

So: **wake recognition itself is real and does fire, but only while the JS runtime is running**
— i.e., while BENSON's own screen/Activity is foregrounded, or in the brief window right after
backgrounding before RN suspends JS.

The legacy native `SpeechRecognizer` path, by contrast, has **no confirmed device pass anywhere
in the repo's reports** — `b0252a0`'s commit message describes it as already failed at the OEM
level ("cloud a picat 2/2 la nivel de serviciu OEM"), and `ROUND_WAKE_STATE_BUG_1_REPORT.md:143`
calls it "documented broken on this device (`ERROR_NO_MATCH`)."

## WHY_BACKGROUND_WAKE_FAILED

Root cause, proven from real device logs, not inferred (`ROUND_WAKE_STATE_BUG_1_REPORT.md:8-25`):

> **React Native fully suspends the JS runtime (`mqt_v_js` thread) whenever BENSON's Activity is
> backgrounded — screen ON or OFF — despite the running foreground service and the partial
> wakelock.**

Evidence table from that report:
- Backgrounded 32s (screen ON): native `WAKE_POKE` heartbeat fires every 3s (process alive), but
  **0 `mqt_v_js` lines**, **0 `WAKE_SCAN_START`** — the JS wake loop never runs a single cycle.
- Backgrounded + screen OFF (`KEYCODE_SLEEP`) 22s: identical — **0 `mqt_v_js` lines**, proving
  the partial wakelock does **not** keep the JS engine alive.
- Return to foreground: JS resumes within ~2s, `WAKE_READY=true` instantly.

Because `startLocalWakeLoop`'s self-re-arm is a **JS `setTimeout`**, and the self-heal interval is
a **JS `setInterval`**, and even the native `onWakePoke` heartbeat's *handler* is JS
(`app/index.tsx:1282-1292`) — every single re-arm mechanism for the only working wake engine
lives inside the exact runtime that Android/RN parks on background. The native side (foreground
service, accessibility service, `WAKE_POKE` heartbeat) stays alive and ticking; it just has
nothing to poke, because the JS event loop that would receive the poke is not executing.

## FIRST_BROKEN_STAGE

Per the report's own formal finding (`ROUND_WAKE_STATE_BUG_1_REPORT.md:45-55`):

```
firstBrokenStage = WAKE_LOOP_REARM
reason = js_runtime_suspended_while_backgrounded
  (RN onHostPause parks mqt_v_js; setTimeout / setInterval / DeviceEventEmitter
   callbacks all inert; the sole working wake engine (JS local-Whisper) lives there)
```

All seven other candidate causes considered (stale mic hold, stale speaking flag, recognizer
duplication, OxygenOS process kill, TTS→wake handoff, notification mismatch) were checked
against the same device logs and ruled out (`ROUND_WAKE_STATE_BUG_1_REPORT.md:57-64`). The
native process itself is never frozen — only RN's JS execution is paused by `onHostPause`.

## JS_DEPENDENCIES

The current working wake path depends on, in order:
1. `expo-speech-recognition` — only for the (broken/fallback) `'native'`-engine branch, not the
   active `'local'` path.
2. `benson-audio-capture` (custom native module, exposed to JS) — VAD/AudioRecord capture.
3. `benson-foreground-service` (custom native module) — `logAudioDiag`, `pauseHotword`/
   `resumeHotword`, `isNativeWakeAvailable`, `nativeWakeSetOwner`, the `onWakePoke` event.
4. `whisper.rn` (`localWhisperEngine.ts`) — on-device fallback transcription (`ggml-base.bin`,
   downloaded at runtime, not bundled — `localWhisperEngine.ts:9-14`).
5. Network STT providers as first-tried tiers: Groq (`groqStt`), Gemini (`geminiSTT`), OpenAI
   (`openaiSTT`) — `voiceAgent.ts:85-115`.
6. React state/refs in `app/index.tsx` (`wakeEngineRef`, `wakeScanningRef`, `wakeTriggeredRef`,
   `convModeRef`, etc.) — all of which are JS-runtime-resident and therefore frozen exactly when
   background wake needs them most.

**Every one of these (1-6) requires the RN JS runtime to be executing.** This is the structural
ceiling described above.

## NATIVE_DEPENDENCIES

- `BensonForegroundService.kt` — foreground service, keeps the *process* (not the JS runtime)
  alive; owns the legacy `SpeechRecognizer` hotword loop, the `WAKE_POKE` heartbeat
  (`mainHandler.postDelayed`, runs on a native `Handler`, independent of RN host state —
  `ROUND_WAKE_STATE_BUG_1_REPORT.md:71`), and (unused today) `MicroWakeWord`.
- `BensonAccessibilityService.kt` — required bound service for the app-control side of BENSON;
  not itself part of wake detection, but its `BOUND` state is one of the `WAKE_READY` gates
  (`app/index.tsx:2866`).
- `BensonAudioCaptureModule.kt` — the actual `AudioRecord` + energy-VAD capture used by both
  command capture and the JS wake scan. Runs on a native thread, but is *driven* (started/stopped)
  from JS calls (`startCapture()`/`stopCapture()`), which is why it stops working once JS is
  parked — nothing is left to call `startCapture()` again.
- `MicroWakeWord.kt` — fully native `AudioRecord` + TFLite inference thread, **the one piece of
  native code in this repo that would keep running with JS suspended** — but it has no model
  file and has never detected anything on a device (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:8-9`,
  `:108`: "**NOT_RUN** — no `benson.tflite`; the native detector has no model to run").

## CAN_EXISTING_ENGINE_BE_MOVED_NATIVE

**Not as-is — the "engine" (Whisper/cloud ASR + string matching) can be ported natively, but the
specific working implementation cannot simply be relocated; it has to be rebuilt as a
lighter-weight detector, because whisper.cpp/cloud STT continuously running as a background loop
is not what made this work — VAD-gated bursts were.**

What already exists, unused, and *is* structurally capable of surviving backgrounding:
- `MicroWakeWord.kt` proves the pattern works mechanically: a dedicated native `AudioRecord`
  thread inside `BensonForegroundService`, independent of the RN host state
  (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:144-145`: "the native `AudioRecord` thread
  already runs independently of the RN host state (proven this turn: `WAKE_POKE thread=main`
  keeps firing while `mqt_v_js` is parked)").
- It fails safe today only because `benson.tflite` doesn't exist — the code path, mic-ownership
  handoff (`nativeWakeSetOwner`), and JS demotion logic (`wakeEngineRef='native'` when a model is
  present, `app/index.tsx:3237-3243`) are already wired and build-verified
  (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:88-96`: `tsc` clean, Kotlin compiles, release
  build signed, installed on `9c1464eb`).

Two honest options going forward (not a recommendation to act — audit only):
1. **Train a `benson.tflite`** (or `marius.tflite`) and drop it into
   `android/app/src/main/assets/wakeword/benson.tflite` — the native pipeline already adapts to
   the model's tensor shape at load time (`MicroWakeWord.kt:119-131`). This is the path the repo's
   own audit already recommends (`ROUND_NATIVE_WAKE_ALTERNATIVES_1_AUDIT.md:32-49`,
   microWakeWord selected over openWakeWord/Vosk/Porcupine).
2. **Port the *same* substring-match logic natively**, i.e. run a native `AudioRecord` VAD loop
   (reusing `BensonAudioCaptureModule.kt`'s own RMS/VAD constants) that, on each detected speech
   burst, calls a cloud/on-device STT from **native code** instead of JS, then regex-matches the
   result — essentially rebuilding `BensonForegroundService.kt`'s legacy `SpeechRecognizer` loop,
   but fixing why it was "broken" (name mis-hearing plus the OEM-level `ERROR_NO_MATCH` issue)
   rather than moving today's JS/Whisper implementation verbatim. This preserves the exact
   matching semantics (`WAKE_VARIANTS`/regex) without requiring any model training.

Either way, **"the same previously working wake recognition logic" cannot be relocated
unchanged** — it depends on JS-only libraries (`whisper.rn`, `expo-speech-recognition`, the STT
provider SDKs) that don't run outside the RN JS runtime. Its *matching logic* (literal +
Levenshtein string match) can be ported natively in an afternoon; its *transcription* cannot,
without either a native ASR call or a trained hotword model.

## SMALLEST_ROBUST_PATH

(Descriptive, not a proposal to implement this round — audit only.)

The repo's own prior rounds already converged on this without prompting from this audit:
1. Keep the JS local-Whisper wake loop exactly as-is for foreground use — it is the only
   proven-working path today and required zero changes to that logic
   (`ROUND_WAKE_STATE_BUG_1_REPORT.md:141`: "Foreground + return-to-BENSON wake: reliable (C3)").
2. Background/screen-off/after-app-launch wake is **structurally impossible** to fix inside the
   JS engine — this was formally concluded, not assumed
   (`ROUND_WAKE_STATE_BUG_1_REPORT.md:133-139`, citing the round's own escalation clause: "do not
   continue to Porcupine until this existing wake path is either proven stable or proven
   structurally incapable... **This satisfies the round's escalation clause verbatim.**").
3. The native TFLite engine (`MicroWakeWord.kt`) is the chosen next step, already scaffolded and
   build-verified, blocked only on a trained model file
   (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:131-145`).

## RISKS

- **Do not conflate "wake recognition is unreliable" with "wake recognition is unreachable in
  background."** They are separate, proven-separately failures (see next section). Fixing one
  does not fix the other.
- The `.ppn` files found under `android/…/build/` are Picovoice **stock demo keywords**
  (alexa, jarvis, computer, ok_google, etc.) pulled in transitively by the Picovoice SDK
  dependency, not evidence of a working custom "Benson" Porcupine model. Do not mistake their
  presence for a functioning Porcupine integration — Porcupine is abandoned per
  `frontend/CLAUDE.md`.
- Any future round that touches `modules/**` (including `benson-foreground-service`,
  `benson-audio-capture`) is explicitly gated by `frontend/CLAUDE.md`'s protected-files list —
  "nu se ating fără permisiune explicită, per rundă."
- Whisper/cloud STT transcription accuracy for the literal word "Benson" is itself an open,
  separate, previously-documented problem (`localWhisperEngine.ts:24-29`: `ggml-base` "small"
  mis-hears clear Romanian speech; that's why `WAKE_VARIANTS`/Levenshtein-fuzzy matching exists at
  all) — independent of the background-survival problem. A name change to "Marius" does not
  fix or worsen this; it inherits the same class of mis-hearing risk regardless of which name is
  chosen, and would need its own set of near-spelling variants tuned by ear on-device, same as
  "Benson" was.

## RECOMMENDATION

No implementation is proposed this round, per the task's explicit instruction. Two facts should
drive whatever decision comes next, both device-proven and already on record in this repo:

1. **Wake *quality* (does it hear "Benson" correctly) and wake *background survival* (does the
   detector even run when backgrounded) are two independent, separately-diagnosed problems.**
   Confusing them risks re-doing work that's already done (the JS matching logic is fine in the
   foreground) or declaring victory on something that was never touched (background survival is
   unfixable in JS by construction, not by a bug that can be patched).
2. **Nothing here requires retraining to answer "would Marius work"** — every currently-existing,
   ever-fired wake detector in this codebase is general-purpose ASR + a hardcoded string/regex
   match, not a trained acoustic keyword model. The one component that *would* need training
   (`MicroWakeWord.kt`) has never produced a single detection on a device and is not part of
   "the previously working" behavior the task is asking about.
