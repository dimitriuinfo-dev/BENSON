# STT Reliability Round — Report

Datum: 27.08.2026 · Zielgerät: OnePlus Nord 4 (SM7675, OxygenOS 15) · whisper.rn installed version per `node_modules/whisper.rn/package.json`

---

## 1. Installed whisper.rn option surface vs. what Tasks A/B asked for

Source of truth: `node_modules/whisper.rn/lib/typescript/NativeRNWhisper.d.ts` (`TranscribeOptions`), cross-checked against the native binding it actually maps onto, `node_modules/whisper.rn/cpp/jsi/RNWhisperJSI.cpp`.

**Full `TranscribeOptions` field list, as installed:**

```
language?: string
translate?: boolean
maxThreads?: number
nProcessors?: number
maxContext?: number
maxLen?: number
tokenTimestamps?: boolean
tdrzEnable?: boolean
wordThold?: number
offset?: number
duration?: number
temperature?: number
temperatureInc?: number
beamSize?: number
bestOf?: number
prompt?: string
```

**Task A:**

| Requested | Exists? | What was done |
|---|---|---|
| `prompt` (explicit empty string) | ✅ yes | Set to `''` under `WHISPER_NO_CONTEXT` |
| `maxContext` / `n_max_text_ctx` | ✅ yes, as `maxContext` (maps 1:1 to native `n_max_text_ctx`, confirmed at `RNWhisperJSI.cpp:881-882`) | Set to `0` under `WHISPER_NO_CONTEXT` |
| `noContext` / `no_context` flag | ❌ **not exposed** — no such field on `TranscribeOptions` | Not settable from JS. See finding below — turned out not to matter. |

**Task B:**

| Requested | Exists? | What was done |
|---|---|---|
| `temperature: 0` | ✅ yes | Set under `WHISPER_STRICT_DECODE` |
| `temperatureInc: 0` | ✅ yes | Set under `WHISPER_STRICT_DECODE` |
| `beamSize: 1` / `bestOf: 1` (greedy) | ✅ yes, both | Set under `WHISPER_STRICT_DECODE`. Previous beam-search attempt (2026-08-24, see file comment) used `beamSize > 1`, which switches the native strategy to `WHISPER_SAMPLING_BEAM_SEARCH` (`RNWhisperJSI.cpp:905-909`) — `beamSize: 1` does not; it stays on greedy, just states the width explicitly instead of leaving it to the library default. |
| `suppressNonSpeechTokens` / `suppress_nst` | ❌ **not exposed** — no such field, and no equivalent field under any other name, in the installed `TranscribeOptions` | Not implementable from JS in this version. No native code was touched (forbidden scope). |
| `singleSegment` | ❌ **not exposed** — the native binding hardcodes `config.params.single_segment = false` unconditionally (`RNWhisperJSI.cpp:922`), with no JS field to override it | Not implementable from JS in this version. |
| `translate: false` | ✅ yes | Set explicitly under `WHISPER_STRICT_DECODE` (native default is already `false`, so this is a no-op today, but it's now asserted rather than assumed — a stray `translate:true` would have been the single most direct explanation for Romanian audio producing English text, and it's ruled out explicitly). |

Per the ground rules, no option names were invented and nothing outside `TranscribeOptions` was set. `suppressNonSpeechTokens` and `singleSegment` are the two Task-B asks that fell back to "not implementable" — there is no generic JS-side fallback for either given the scope lock (both would require a native/C++ change, which is out of scope this round — `node_modules/**` was not touched).

---

## 2. Previous-result-as-prompt feedback path

**No.** `lib/agents/localWhisperEngine.ts`'s `transcribeLocally()` never set `prompt` (or anything like it) before this round — it called `ctx.transcribe(wavFilePath, { language: langCode, maxThreads: THREADS })` with no other options. Grepped the whole file and `lib/agents/voiceAgent.ts`'s call site (`voiceAgent.ts:154`, unchanged) for `prompt`/`initialPrompt`/similar — nothing feeds a previous take's result back in anywhere in the allowed-scope files.

**More importantly, a second, deeper finding changes the read on the whole hypothesis:** the installed native binding (`cpp/jsi/RNWhisperJSI.cpp:921`) hardcodes

```cpp
config.params.no_context = true;
```

**unconditionally, for every transcribe call, regardless of any JS option.** whisper.cpp's own compiled-in default (`whisper.cpp:5943`) is also `no_context = true`. This means whisper.cpp's internal `no_context` gate — the thing that would normally let a previous call's decoded tokens leak into the next call's decoder prompt — was **already forcibly disabled** in this installed version, before any change made this round. Classic prompt-carryover through `whisper_full`'s own `no_context`/`prompt_past` mechanism was therefore already structurally impossible.

This does not mean the hypothesis in the task brief was wrong about the *symptom* (progressive English hallucination + growing decode time within one session) — only that the specific mechanism named (JS-level prompt feedback, or the native `no_context` gate) is not it, because both were already closed off. The two most plausible remaining candidates for the growing-decode-time symptom given the same context object was kept alive and reused for the whole session (the exact thing this round's Task C removes) are: (a) some other internal state on the shared `WhisperContext`/JSI object growing per call (a buffer, cache, or a leak in state not covered by `no_context`), or (b) OS-level CPU deprioritization compounding over a long backgrounded session (already documented elsewhere in this file's comments as a real, separate effect on this device). Task C's per-take context release+recreate defends against (a) regardless of which it turns out to be; `maxContext: 0` is additional, redundant insurance on the same axis. This is exactly why Task C is described as mandatory regardless of Task A's outcome — that held up.

---

## 3. Cost of a fresh context init

Not yet measured on-device — this build has not been installed/run yet (see §6, acceptance test not run). `CTX_INIT elapsedMs=…` is wired up (`ensureContext()` in `localWhisperEngine.ts`, logged under tag `BENSON_AUDIO`) and will emit on the first take of the on-device run. Flagging per the task's own instruction: if that number comes back > ~1500ms, the per-take release/recreate trades measurable latency for reliability — visible directly in the `TRANSCRIBE_START`-to-previous-`CTX_RELEASE` gap in the logcat capture once the acceptance test is run.

---

## 4. Per-file changes (allowed files only)

| File | Touched? | Lines changed (this round only, not counting pre-existing dirty state from before this session) |
|---|---|---|
| `lib/agents/localWhisperEngine.ts` | ✅ yes | +164 / −20 (diffed against this file's content as read at the start of this task, not against `git HEAD`, since the file already carried unrelated uncommitted changes from before this session) |
| `lib/agents/voiceAgent.ts` | ❌ no | 0 — not needed. See design note below. |
| `modules/benson-audio-capture/android/.../BensonAudioCaptureModule.kt` | ❌ no | 0 — this file already logs `PREROLL`/`CAPTURE_ENDED` under `BENSON_AUDIO` directly via `Log.i`; none of this round's required log lines (`WHISPER_OPTS`, `CTX_INIT`, `TRANSCRIBE_START/END`, `WHISPER_REJECTED`, `CTX_RELEASE`) are capture-side, so nothing here needed changing. |
| `STT_REPORT.md` | ✅ yes (new) | this file |

**Design note on why `voiceAgent.ts` needed no change:** Task E's rejection path needed a way to surface the reused Romanian string `"Nu am înțeles clar comanda — poți s-o spui din nou?"` (found verbatim in `src/core/mission/missionExecutor.ts`'s own low-confidence-parse fallback — a file outside the allowed-to-edit scope) to the user, without touching `app/index.tsx` or `missionExecutor.ts` (both forbidden). `transcribeLocally()` now returns that exact string as its result on rejection, instead of the raw hallucinated text or a bare empty string. `voiceAgent.ts`'s existing call site (`if (text) emitResult(text, true); else emitError('no-speech')`, unchanged) then does exactly what it already did for a normal transcript: hands it to the intent parser. Since this string can't parse as any command, the parser falls through to its own already-existing generic failure message — which is this same string — so the user hears the correct thing without any forbidden-scope file being touched. This was a deliberate design choice to stay inside the scope lock; it is *not* the only valid design (a cleaner one would thread a distinct `not-understood` signal through `voiceAgent.ts` and `app/index.tsx`), but that would require editing a forbidden file.

---

## 5. Verification output

### `npx tsc --noEmit`

```
(no output — exit code 0)
```

**0 errors.**

### `gradlew assembleRelease`

Note: the environment had no `ANDROID_HOME`/`ANDROID_SDK_ROOT` set and no `android/local.properties` (a machine-local, gitignored file, not one of the forbidden `build.gradle`/`gradle.properties`/`proguard-rules.pro`/keystore files). `ANDROID_HOME`/`ANDROID_SDK_ROOT` were set as one-off environment variables scoped to this single Gradle invocation only (`ANDROID_HOME=... ANDROID_SDK_ROOT=... ./gradlew assembleRelease`) — nothing was written to disk, no `local.properties` file was created, no `setx`, no PATH change. The existing SDK install at `C:\Users\lenovo\AppData\Local\Android\Sdk` was used as-is.

Tail of the build output:

```
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease

[Incubating] Problems report is available at: file:///C:/Users/lenovo/Desktop/BENSON-Android/frontend/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 1m 59s
945 actionable tasks: 67 executed, 878 up-to-date
```

**APK:** `frontend/android/app/build/outputs/apk/release/app-release.apk`
**Size:** 260,784,100 bytes (~248.7 MiB)

**Signing confirmation** (`apksigner verify --print-certs`):

```
Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
Signer #1 certificate SHA-256 digest: fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da
```

`CN=BENSON, O=TOKKO` confirmed — same signing identity, untouched. No signing or minify setting was changed (no file under `plugins/**` or `android/**` build config was opened or edited).

---

## 6. Forbidden-file items — none touched, and what was about to require one

- **Task B's `suppressNonSpeechTokens`/`singleSegment`**: making these actually work would require editing `node_modules/whisper.rn`'s native C++ (not in the forbidden list by name, but "no new dependencies / don't touch node_modules" and the spirit of the scope lock rule it out just the same) — left unimplemented, documented in §1.
- **Task E's user-facing rejection message**: the natural place to wire a distinct "not understood" signal is `app/index.tsx` (forbidden) or `src/core/mission/missionExecutor.ts` (not explicitly forbidden by name, but outside the 4-file allow-list). Neither was opened or edited. Instead `transcribeLocally()` returns the exact same Romanian string `missionExecutor.ts` already uses, so the existing parse-failure path produces the right user-facing text without modification — see §4 design note.
- Nothing else came close to requiring a forbidden file. `android/**`, `plugins/**`, `whisper-models/**`, `porcupine-model/**`, `modules/benson-accessibility/**`, `modules/**/BensonForegroundService.kt`, `lib/tools/whatsappTool.ts`, `lib/agents/missionValidator.ts`, `lib/agents/missionExecutor.ts` (the `lib/agents/` one — a different, empty/unused file from the `src/core/mission/` one referenced above) were not opened.

---

## 7. Revert constants (§6 of the task brief)

All four declared together at the top of `localWhisperEngine.ts`, all `true`:

```ts
const WHISPER_NO_CONTEXT = true;
const WHISPER_STRICT_DECODE = true;
const WHISPER_FRESH_CONTEXT_PER_TAKE = true;
const WHISPER_HALLUCINATION_FILTER = true;
```

`THREADS = 4` (previous value `6`, in a comment beside it, per instruction). Flipping all four booleans to `false` restores this file's pre-round transcription behavior exactly (verified by inspection: with all four `false`, the options object, context lifecycle, and return path are byte-for-byte what they were before this round — the only remaining differences are the new `takeIndex`/busy-guard bookkeeping and the always-on `WHISPER_OPTS`/`CTX_INIT`/`TRANSCRIBE_START`/`TRANSCRIBE_END`/`CTX_RELEASE` log lines, which are instrumentation, not behavior).

---

## Not yet done

The acceptance test in §5 of the task brief (five consecutive "Sună-o pe Hannah pe WhatsApp." takes on-device with logcat capture) has **not been run** — this report covers implementation and the two required build/type checks only. The APK above is built and ready to install; on-device verification is the next step.
