# ROUND_WAKE_NATIVE_GENERIC_FEASIBILITY_1_REPORT

Feasibility audit only. No production file changed, no build run, no install, no device test.
Builds directly on `ROUND_WAKE_ORIGINAL_IMPLEMENTATION_AUDIT.md` — facts already established
there are cited, not re-derived.

---

## 1. CURRENT WORKING WAKE PIPELINE

Stage-by-stage trace of the one path with device-confirmed hits
(`startLocalWakeLoop`/`detectWakeWord`, foreground only):

| Stage | Code | JS/Native | FG/BG constraint | Dependency | Network | Latency (device-observed) | Battery |
|---|---|---|---|---|---|---|---|
| **AUDIO SOURCE** | `BensonAudioCaptureModule.kt` — `AudioRecord(VOICE_RECOGNITION, 16kHz mono PCM16)` | **Native** (Kotlin) | Runs fine in background *if started*; the problem is nothing re-starts it once JS is parked | none beyond Android `AudioRecord` | no | mic opens in <50ms typically | low while a single burst; the mic stays open for the full utterance |
| **VAD** | same file, `RMS_THRESHOLD=700.0`, `MIN_SPEECH_MS=800`, `SILENCE_TIMEOUT_MS=1600` (lines 51-57) | **Native** | same | none | no | adds up to 1.6s tail-silence wait per utterance | negligible extra (energy calc only) |
| **AUDIO BUFFER** | same module writes a WAV file (`writeWavHeader`, ~line 439-459) to app storage | **Native** | same | filesystem | no | disk write, <50ms for short clips | negligible |
| **capture→JS handoff** | `addCaptureEndListener` (JS) fires with the file path — `voiceAgent.ts:264-278` | **bridge (native→JS event)** | **requires the JS runtime to be executing to receive the event at all** — this is the first JS dependency in the chain | Expo Modules `EventEmitter` | no | — | — |
| **TRANSCRIPTION PROVIDER** | `transcribeAudio()` — `voiceAgent.ts:85-115`: Groq (`whisper-large-v3-turbo`) → Gemini → OpenAI → local `whisper.cpp` (`localWhisperEngine.ts`) | **JS-orchestrated**; Groq/Gemini/OpenAI calls are plain JS `fetch()`; local Whisper is JS calling into `whisper.rn`'s JSI bridge | **all four tiers require the JS runtime** — cloud tiers because the HTTP call is JS `fetch`, local tier because `whisper.rn` is JSI-only (see §3) | network (cloud tiers) or `ggml-base.bin` (local tier, downloaded at first run, `localWhisperEngine.ts:37-42`) | yes (3 of 4 tiers) | cloud: ~1-3s typical; local Whisper on this SoC: ~15s foreground, up to 137s backgrounded (`localWhisperEngine.ts:18-19`, device-measured) | cloud tiers: radio + short CPU burst; local tier: heavy CPU burst, 4 threads pinned to the 2.61GHz cluster (`localWhisperEngine.ts:51`) |
| **WAKE TEXT NORMALIZATION** | `detectWakeWord()`, `app/index.tsx:2737`: lowercase + NFD diacritic strip | **JS** | requires JS | none | no | <1ms | negligible |
| **FUZZY MATCH** | `WAKE_VARIANTS.some(includes)` then Levenshtein ≤1 fallback (`app/index.tsx:2739-2743`) | **JS** | requires JS | none | no | <1ms | negligible |
| **WAKE EVENT** | `handleWakeDetected()` (`app/index.tsx:2774`) — chime, `stopWakeScan()`, `bumpSessionKeepAwake()`, hands off to command capture or same-breath command tail | **JS** | requires JS | none | no | — | — |
| **COMMAND LISTENING** | `doStartListening()` → back into `startRecognition`/`runLocalCapture` (`voiceAgent.ts:294`) | **JS** | requires JS | same as above | as above | — | — |

**Everything from "capture→JS handoff" onward is JS-resident.** Only the first three stages
(audio source, VAD, buffer write) are already native today.

## 2. EXACT BACKGROUND FAILURE BOUNDARY

Confirmed by device logs in `ROUND_WAKE_STATE_BUG_1_REPORT.md` (already cited in the prior
audit, restated here as the load-bearing fact for this round): the boundary is not audio, not
VAD, not the foreground service, not the accessibility service — it is precisely the
**capture→JS handoff** row in the table above. `BensonAudioCaptureModule.kt`'s `AudioRecord`
loop is native and *could* keep running backgrounded, but nothing calls `startCapture()` again
once a cycle ends, because the caller of `startCapture()` (`startLocalWakeLoop` in
`app/index.tsx`) is JS, and JS (`mqt_v_js`) is suspended by `onHostPause` — proven by 0 JS log
lines over 32s/22s of backgrounding while the *native* `WAKE_POKE` heartbeat kept firing every
3s in the same window (`ROUND_WAKE_STATE_BUG_1_REPORT.md:20-21`).

**Conclusion for this round:** the fix is not "make VAD survive background" (it already can) —
it is "move the loop that *drives* VAD + the transcription call + the text match out of the JS
runtime entirely," i.e. everything from the capture→JS handoff row downward in the table.

## 3. EXISTING NATIVE COMPONENTS WE CAN REUSE

Investigated in this round (grep across `modules/**` gradle files, `node_modules/whisper.rn`,
and the resolved dependency list of `benson-foreground-service`'s release build):

| Component | Status | Reusable for this? |
|---|---|---|
| `BensonAudioCaptureModule.kt` (native `AudioRecord` + VAD + WAV writer) | proven, already native, already used for real command capture | **Yes** — its VAD constants/logic can be called directly from a loop living inside `BensonForegroundService` instead of being driven from JS |
| `BensonForegroundService.kt`'s `WakeGate` RMS probe (`WakeGate.RMS_THRESHOLD=350.0`, `probeGateRms()`, lines 464-472, 771-802) | proven, already native, already gates the legacy `SpeechRecognizer` burst on cheap energy | **Yes** — same idiom, already battery-motivated, already in the exact class that would host a new loop |
| `BensonForegroundService.kt`'s `WAKE_POKE` native heartbeat (`mainHandler.postDelayed`, survives RN host pause) | proven, native, already ticks in background | **Yes** — as the arm/self-heal timer for a native loop, same role it plays today for the (currently useless) JS re-arm |
| **OkHttp 4.9.2** | **already on the classpath** — confirmed by inspecting `benson-foreground-service`'s resolved release dependencies (`okhttp3:okhttp:4.9.2`, pulled in transitively via React Native's own networking stack) | **Yes, at zero new-dependency cost** — a native Kotlin HTTP POST to Groq/Gemini's OpenAI-compatible STT endpoint needs no new library |
| `org.json` (Android built-in) | always available | **Yes** — parsing a Groq/Gemini JSON transcript response needs nothing new |
| `ai.picovoice:porcupine-android:3.0.1` | **already a gradle dependency** (`build.gradle:51`), unused, no custom model — Porcupine is product-abandoned per `frontend/CLAUDE.md` | **No** — out of scope per this round's instructions and per standing product decision |
| `org.tensorflow:tensorflow-lite:2.16.1` + `MicroWakeWord.kt` | already wired, build-verified, **no model file**, never detected anything on-device (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT.md:108`) | **No, explicitly excluded this round** per the task's instruction; also fails the "arbitrary wake name without retraining" requirement (§4/§7) |
| `whisper.rn` native library (`node_modules/whisper.rn/android`) | its Java layer (`RNWhisper.java`) only `System.loadLibrary()`s the native lib and installs **JSI bindings** (`jni.cpp`) — there is **no plain Java/Kotlin-callable transcribe method**; the only way JS gets a result is through the JSI object it installs into the JS runtime | **No, not directly reusable** — see risk in §5. Duplicating whisper.cpp as an independent native integration is possible in principle but is new work, not reuse |
| Vosk (`org.vosk:vosk-android`) | **not currently a dependency anywhere in this repo** | conditionally reusable as a *new* component — evaluated as Option B below |

## 4. OPTIONS CONSIDERED

### Option A — Reproduce the current VAD + capture + transcription + text/fuzzy match entirely inside `BensonForegroundService` (native Kotlin), reusing existing native pieces
- Native loop (reusing `BensonAudioCaptureModule`'s VAD logic + `WakeGate`'s RMS pre-gate)
  captures an utterance the same way it does today.
- Transcription is done **from Kotlin**, not JS: `OkHttp` POST (multipart WAV) to Groq's
  Whisper-compatible endpoint (mirrors what `groqStt.ts` already does over `fetch`), or Gemini's
  REST endpoint. The active API key/base URL/model are pushed down from JS to native once,
  the same way `setPorcupineAccessKey`/`setSttLanguage` already push secrets/config down today
  (`benson-foreground-service/index.js:131-149`) — no new key-storage mechanism needed, and the
  key never has to be read out of `expo-secure-store`'s encrypted file by native code directly.
- Text match: port `WAKE_VARIANTS`/Levenshtein (already proven logic) to Kotlin — mechanical,
  same idiom already used for the legacy `WAKE_REGEX`.
- On match: call the **existing** `onHotwordDetected(commandTail)` path unchanged.
- On-device (offline) fallback tier is the one piece that does **not** port cleanly (see §5) —
  documented as a known gap, not silently dropped.

| | |
|---|---|
| arbitrary wake name supported? | **YES** — same text-match mechanism as today, just fed a user-supplied string |
| requires retraining per wake name? | **NO** |
| works with JS suspended? | **YES** — the entire loop, from mic to match, runs in Kotlin |
| offline possible? | **PARTIAL** — cloud tiers need network; on-device fallback tier requires new native work (§5), not free |
| battery | one extra native thread, mic open only during a VAD-gated burst (same duty cycle as today); +1 HTTP round-trip per utterance instead of a JS `fetch` — no meaningful battery delta vs. today's JS version, since the same radio/CPU work happens either way |
| changes required | new Kotlin transcription client in `benson-foreground-service`; a small native config-push API (key/base URL/model) from JS; ported text-match constants; a native loop driver in `BensonForegroundService` that reuses `BensonAudioCaptureModule`'s VAD |
| risks | duplicated logic between JS and native STT clients (two places to keep in sync if the Groq/Gemini API changes); native HTTP error handling (timeouts, 429 backoff) must be re-implemented in Kotlin, mirroring `voiceAgent.ts`'s existing rate-limit cooldown logic |

### Option B — Native continuous/local ASR with keyword-constrained recognition (Vosk)
- `org.vosk:vosk-android` AAR, small model (~40-50MB), fully offline, runs a continuous
  recognizer constrained by a JSON grammar (a literal list of accepted words/phrases) —
  arbitrary words can be added to the grammar at runtime with **no acoustic retraining**, because
  the grammar only constrains a general acoustic model's decoding, it doesn't change the model.

| | |
|---|---|
| arbitrary wake name supported? | **YES** — add the new word/spelling variants to the JSON grammar at runtime |
| requires retraining per wake name? | **NO** |
| works with JS suspended? | **YES** — 100% native, no JS involvement at all once armed |
| offline possible? | **YES, fully** — no network needed at any tier |
| battery | **worse than A** — this is a continuous general-purpose ASR decoder running at all times (not VAD-gated bursts), plus ~40-50MB resident model in RAM the whole time the service is alive (flagged as the heaviest of the four candidates already compared in `ROUND_NATIVE_WAKE_ALTERNATIVES_1_AUDIT.md:23-24`) |
| changes required | new gradle dependency + ~40-50MB model asset (git-ignored, downloaded at runtime like `whisper-models/` already is); new native recognizer lifecycle in `BensonForegroundService`; grammar-generation logic for arbitrary names (still needs a variant list, same mis-hearing-mitigation problem as today) |
| risks | same class of name-mis-hearing as `SpeechRecognizer`/Whisper — Vosk is still general ASR, not a dedicated hotword detector (`ROUND_NATIVE_WAKE_ALTERNATIVES_1_AUDIT.md:25`: "wrong tool for a name wake word... same problem we're escaping"); continuous decoding is a real, ongoing battery cost the current VAD-gated approach avoids entirely; APK size (+model) |

### Option C — Hybrid: native VAD-gated burst (reusing existing native VAD) + native cloud STT call, falling back to on-device Whisper only in foreground
This is Option A stated precisely as "reuse, don't rebuild": the native loop uses the **already
native and proven** `BensonAudioCaptureModule` VAD + `WakeGate` RMS pre-gate exactly as they
exist today (zero changes to that code), adds a thin native HTTP client for the cloud STT tiers
(reusing the already-present OkHttp dependency, zero new libraries), and explicitly **defers**
the on-device Whisper tier to the JS/foreground path only (documented limitation, not a silent
regression — background wake would depend on network being available, same as most of today's
command-capture STT already does since Groq is tried first).

| | |
|---|---|
| arbitrary wake name supported? | **YES** |
| requires retraining per wake name? | **NO** |
| works with JS suspended? | **YES**, when network is available; falls back to "wake inactive" (same honest `WAKE_HEALTH`/notification pattern already built, `app/index.tsx:2853-2861`) when it isn't |
| offline possible? | **NO** for the background case specifically (this is the one tradeoff vs. Option B) |
| battery | **best of the three** — VAD-gated bursts only, no continuous decoding, no resident 40-50MB model |
| changes required | smallest of the three: no new dependency, no new model/asset, reuse of two already-native components, one new native HTTP client class, one ported text-match table |
| risks | background wake becomes network-dependent (acceptable given the product already relies on Groq/Gemini/OpenAI as its primary STT tiers even in foreground); still inherits general-ASR mis-hearing risk for whatever name is chosen (not a new risk — it's today's risk, unchanged) |

## 5. OPTIONS REJECTED AND WHY

- **Native Android `SpeechRecognizer` as the continuous background engine** — explicitly excluded
  per this round's instructions and per prior evidence: documented broken on this device
  (`ERROR_NO_MATCH`, `b0252a0` commit message; `ROUND_WAKE_STATE_BUG_1_REPORT.md:143`). Not
  reconsidered.
- **MicroWakeWord/TFLite as the near-term solution** — excluded per this round's instructions.
  Independent of that instruction, it also structurally fails requirement §7/product goal: a
  `.tflite` hotword model is trained for one specific word; supporting "Benson", "Marius",
  "Alfred", "George" on demand without an APK rebuild would require either shipping N
  pre-trained models (does not cover an arbitrary future name) or a per-name on-device/cloud
  training pipeline (not "without training a new acoustic model" — directly violates the stated
  product requirement). Kept out of scope, not revisited.
- **Reusing `whisper.rn`'s compiled native library directly from Kotlin (bypassing JS/JSI)** —
  investigated this round and rejected as impractical for now: `RNWhisper.java` only
  `System.loadLibrary()`s the native lib and installs **JSI** bindings (`jni.cpp`); there is no
  plain Java/Kotlin-callable `transcribe()` entry point in the Android sources. Making this work
  natively would mean either (a) reverse-engineering/duplicating whisper.cpp's own JNI/C API as a
  second, independent integration (real new engineering, not reuse), or (b) forking `whisper.rn`.
  Neither is "smallest viable" — this is why Option A/C defer the on-device fallback tier to
  foreground/JS rather than porting it.
- **A brand-new Android Service instead of extending `BensonForegroundService`** — rejected.
  The task specifically asked whether the existing service/AudioRecord machinery can be reused:
  yes, and there is no technical reason to add a second foreground service. `BensonForegroundService`
  already owns exactly the lifecycle (`onStartCommand`, `onDestroy`, the `WAKE_POKE` heartbeat,
  mic-ownership arbitration via `nativeWakeSetOwner`) that any of Options A/B/C would need; adding
  a second service would only duplicate that lifecycle and create a second point of mic
  contention with the existing `MIC_OWNER` state machine (`WAKE|COMMAND_STT|TTS|CALL|NONE`).

## 6. RECOMMENDED MINIMAL ARCHITECTURE

**Option C** (native VAD-gated burst, reusing `BensonAudioCaptureModule`'s proven VAD +
`BensonForegroundService`'s `WakeGate`/heartbeat, native OkHttp call to the cloud STT tier,
ported text/fuzzy match, on-device Whisper fallback deferred to foreground-only) is the smallest
change that satisfies every stated survival requirement (backgrounded Activity, another app
foreground, screen off, JS suspended, accessibility-service restart — orthogonal, already
handled — and WhatsApp call lifecycle — already has its own native-aware mic-hold logic to
extend, not replace) without introducing a new dependency, a new model asset, or continuous
always-on decoding.

Rejected in favor of C: Option A is functionally identical to C except it also promises an
on-device fallback tier that, per §5, is not cleanly buildable without new whisper.cpp
integration work — C states that limitation honestly instead of half-building it. Option B
(Vosk) is the only fully-offline candidate but fails the battery/complexity bar for a
continuous background decoder and does not remove the mis-hearing risk that C already accepts
as a known, pre-existing tradeoff.

## 7. WHETHER "MARIUS" CAN BE CONFIGURED WITHOUT RETRAINING

**Yes, under every option evaluated (A, B, C).** None of them are acoustic hotword models tied
to "Benson" specifically — they are general transcription (cloud STT or Vosk's grammar-constrained
general ASR) plus a text/fuzzy match or grammar list. Making the name user-configurable is a
matter of:
1. A Settings field (new UI, JS-side, out of scope for this feasibility round) that stores the
   chosen name string.
2. Pushing that string down to native (same idiom as `setSttLanguage`/`setPorcupineAccessKey`)
   so the native loop's match table (Option A/C) or grammar (Option B) is built from it — plus a
   small variant-generation step (near-spellings, same tolerance idea as today's
   `WAKE_VARIANTS`/Levenshtein-1) so "Marius" gets the same mis-hearing tolerance "Benson" has
   today. This variant table would need the same kind of on-device listening/tuning `WAKE_VARIANTS`
   originally got, per name — expected effort, not a blocker.

Only `MicroWakeWord`/TFLite would need retraining per name, and it is excluded from this round's
recommendation for exactly that reason (§5).

## 8. BATTERY / NETWORK / LATENCY CONSEQUENCES

For the recommended Option C, relative to **today's foreground-only** behavior (not relative to
"no wake word at all"):
- **Battery:** essentially unchanged in kind — the mic still opens only on a VAD-gated burst, the
  same `WakeGate` RMS pre-gate that already exists to avoid needless bursts (`BensonForegroundService.kt:464-472`)
  is reused as-is. The only new always-on cost is the native loop's control logic (timers,
  thread), which is negligible next to an open microphone/HTTP call. Materially better than
  Option B's continuous decoder, and no worse than what already runs today whenever BENSON is
  foregrounded.
- **Network:** background wake becomes network-dependent (cloud STT tier only) — a **new**
  constraint that doesn't exist for foreground wake today (which also tries local Whisper on
  cloud failure). Offline background wake is not covered by C; it is covered by B at a real
  battery/RAM cost, or by a future native Whisper integration not attempted this round.
- **Latency:** a native HTTP call replaces a JS `fetch()` call to the same endpoint — expected to
  be latency-neutral or slightly better (no JS/bridge marshaling overhead), same ~1-3s cloud STT
  round-trip observed today.

## 9. FILES THAT WOULD NEED MODIFICATION

(For the record only — no changes made this round; these are `modules/**`/native paths, which
`frontend/CLAUDE.md` requires explicit per-round permission to touch.)

| File | Nature of change (Option C) |
|---|---|
| `modules/benson-foreground-service/android/.../BensonForegroundService.kt` | new native wake-loop driver (reusing `WakeGate`, adding the VAD-gated burst→transcribe→match cycle); config-push entry points for wake name + STT provider key/base URL/model |
| `modules/benson-audio-capture/android/.../BensonAudioCaptureModule.kt` | expose its VAD/capture logic as a plain Kotlin function callable from `BensonForegroundService`, not only as an Expo-module JS-facing API (or: duplicate the ~30 lines of VAD constants — smaller footprint, more duplication; a design decision for the implementation round, not this one) |
| `modules/benson-foreground-service/android/.../BensonForegroundServiceModule.kt` | new bridge functions: push wake-name + STT credentials from JS to native (mirrors `setPorcupineAccessKey`/`setSttLanguage`) |
| `modules/benson-foreground-service/index.js` / `index.d.ts` | JS wrappers for the new push functions |
| `frontend/app/index.tsx` | push the active wake name + STT config to native at boot / on Settings change; `WAKE_ENGINE`/`isNativeWakeAvailable` selection logic gains a third state (native-cloud loop) alongside `local`/`native-tflite`; **no change** to `handleWakeDetected`/`detectWakeWord` themselves — they stay as the foreground/JS-available path exactly as today |
| new file, e.g. `modules/benson-foreground-service/android/.../NativeWakeHttp.kt` | the OkHttp client + JSON parse for the cloud STT call, and the ported wake-name text/fuzzy match |
| (Settings UI, not audited this round) | new field for the user-chosen wake name — outside `modules/**`, not covered by this feasibility pass |

## 10. SINGLE PROPOSED IMPLEMENTATION ROUND

Not authorized to name a start date or begin work — this is the audit's closing scope statement,
for the next round's scope-lock, per `frontend/CLAUDE.md` protocol ("Un singur tip de schimbare
per rundă"):

> **ROUND_WAKE_NATIVE_GENERIC_1**: add a native (Kotlin) VAD-gated wake loop inside
> `BensonForegroundService`, reusing `BensonAudioCaptureModule`'s existing VAD/AudioRecord logic
> and `WakeGate`'s RMS pre-gate unchanged; add a native OkHttp-based cloud-STT call (Groq tier
> only, to start — the same first tier `voiceAgent.ts` already tries) and a ported
> text/fuzzy wake-match; wire it to fire the existing `onHotwordDetected(commandTail)` path
> unchanged. JS's `startLocalWakeLoop`/`detectWakeWord` remain the foreground path and the
> fallback when the native loop is disabled/unavailable — **addition, not replacement**, per the
> regression-prevention rule already standing in `frontend/CLAUDE.md` §2. Wake-name
> configurability (Settings UI) and the on-device offline fallback tier are explicitly **out of
> scope** for that first round and would each be their own subsequent round.

No implementation performed. No build performed. No PASS claimed anywhere in this report.
