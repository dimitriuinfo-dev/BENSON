# ROUND_NATIVE_WAKE_MICROWAKEWORD_1_REPORT

Native on-device wake-word probe. microWakeWord approved, Porcupine rejected. Built, installed,
wired end-to-end, **fails safe** — and cannot detect anything yet because **a `benson.tflite`
model does not exist and cannot be trained in this environment** (no Python / TensorFlow / GPU /
microWakeWord toolchain — verified, not assumed). No wake word was substituted.

**No DEVICE PASS is claimed. All P1–P7 = NOT_RUN** — real-device detection is impossible without
the model.

---

## IMPLEMENTED

### `modules/benson-foreground-service/.../MicroWakeWord.kt` (NEW, ~300 lines)
Fully native, no React Native involvement:
- dedicated `AudioRecord` thread (`MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz mono
  PCM16, `THREAD_PRIORITY_URGENT_AUDIO`), 10 ms hop over a 30 ms sliding window.
- **TFLite `Interpreter`** loads `assets/wakeword/benson.tflite` (mmapped). The pipeline **adapts
  to the model's actual input tensor** (`describeIo()` → `NWW_MODEL_SIGNATURE`): last-dim == 40 →
  run the built-in 40-bin log-mel frontend (radix-2 FFT + mel filterbank, `ln` power); otherwise
  → feed raw audio windows (frontend baked into the graph). Handles `[1,1,40]` streaming and
  `[1,T,40]` windowed models; float or int input.
- sliding-window max over the output prob, `DETECT_THRESHOLD = 0.7f`, `REFRACTORY_MS = 1500`.
- `start()` / `stop()` idempotent; clean `AudioRecord` + `Interpreter` release. `modelPresent()`
  static check.
- **The log-mel frontend is a standard implementation and MUST be validated against the exact
  training config** of whatever `benson.tflite` is produced — threshold, mel params and hop will
  need on-device tuning against the real model.

### `modules/benson-foreground-service/.../BensonForegroundService.kt`
- owns one `MicroWakeWord` instance; `armNativeWake()` / `suspendNativeWake()` / `nativeWakeSetOwner()`.
- **auto-arms in `onStartCommand`** (native, on its own thread → survives Activity background /
  JS suspension — the whole point). Only when `benson.tflite` is bundled; else
  `NATIVE_WAKE_UNAVAILABLE reason=missing_model` and the JS fallback stays primary.
- `onNativeWakeDetected(score)` → `suspendNativeWake("COMMAND")` → `MIC_OWNER=COMMAND_STT` →
  best-effort `bringActivityToFront()` (Alexa-style; OxygenOS may block a bg Activity start — the
  bubble + wake-ring overlays always show) → the existing `onHotwordDetected("")` path (bubble,
  ring, `onWakeWordDetected` JS event, `pendingWakeCommand` fallback) unchanged.
- `wakePokeTick` (the ROUND_WAKE_STATE_BUG_1 3 s heartbeat) now **self-heals** the native engine:
  if it should be armed but the thread/interpreter died → dispose + recreate exactly one
  (`NWW_SELF_HEAL_START/OK/FAIL`). When no model → falls back to poking JS as before.
- `onDestroy` stops + nulls the instance.

### `BensonForegroundServiceModule.kt` + `index.js` / `index.d.ts`
`Function("nativeWakeSetOwner")(owner)`, `Function("isNativeWakeAvailable") → { model, running }`.

### `app/index.tsx`
- boot (`startBackgroundService`): `isNativeWakeAvailable().model` → `wakeEngineRef = 'native'`
  (JS `startLocalWakeLoop` no-ops), `stopWakeScan()`, `nwOwner('WAKE')`. Logs `WAKE_ENGINE
  select=MICROWAKEWORD_NATIVE | JS_WHISPER_FALLBACK`.
- `nwOwner(o)` helper (no-op unless native) wired at every mic boundary:
  `doStartListening` → `COMMAND_STT` (+ `COMMAND_STT_START`, `WAKE_TO_COMMAND_LATENCY ms=`),
  `beginTtsBlock` → `TTS`, `resumePassiveWake` → `WAKE`, WhatsApp voice-call mic-hold arm →
  `CALL`; call-ended self-heal → `resumePassiveWake` → `WAKE`.
- `addWakeWordDetectedListener` stamps `nativeWakeEventAtRef` for the latency metric.

## CONFIGURATION
| item | value |
|---|---|
| model asset | `android/app/src/main/assets/wakeword/benson.tflite` — **not present**, not committed (place like `whisper-models/`) |
| dep added | `org.tensorflow:tensorflow-lite:2.16.1` (LiteRT core, Apache-2.0, mavenCentral) — no select-TF-ops |
| APK size | 262 MB → **276 MB** (+~14 MB, the tflite native libs, arm64 + armeabi-v7a) |
| access key | none needed (microWakeWord is not licensed per-unit) |
| kill switch | existing `wake_word_enabled` pref honoured by `armNativeWake()` |
| fail-safe | model absent → `NATIVE_WAKE_UNAVAILABLE` → JS Whisper wake + the 3 s heartbeat stay primary → **zero behaviour change** |

## MIC_OWNERSHIP
Authoritative single owner in the service: `NONE | WAKE | COMMAND_STT | TTS | CALL`, every flip
logged `MIC_OWNER from=X to=Y reason=…`. Idle = `WAKE` (MicroWakeWord owns `AudioRecord`). On
detect → native `stop()` releases the mic → `COMMAND_STT` → JS command STT. `beginTtsBlock` →
`TTS`. After command + TTS + `AUDIO_TAIL_GUARD_MS` (STABILIZATION_1) → `resumePassiveWake` →
`WAKE` → `armNativeWake()` re-creates exactly one instance (idempotent; guarded on
`isRunning()` + kill switch + owner). WhatsApp/phone call → `CALL`; verified `CALL_ENDED`
(existing signal) → `WAKE`. **No two `AudioRecord` owners, no duplicate interpreters.**

## JS_WAKE_DEMOTION
`wakeEngineRef = 'native'` when the model is present → `startLocalWakeLoop()` returns immediately
(its existing `if (wakeEngineRef.current !== 'local') return` guard). The JS Whisper loop is not
deleted — it remains the automatic fallback when `isNativeWakeAvailable().model === false`
(current state), so nothing regressed while the model is absent.

## STABILIZATION_1 invariants — preserved
`userMicGate` still gates every COMMAND_STT transcript; the native engine emits only `""` (no
ASR) so no wake text can enter `commandParser`; `afterPromptRearm` re-arm destination is now
`nwOwner('WAKE')`; confirmation / supersession / WhatsApp-call lifecycle untouched.

## BUILD
- `npx tsc --noEmit` → **0 errors**.
- `scripts/wa-routing-tests.ts` → **ALL PASS**.
- `:benson-foreground-service:compileReleaseKotlin` (with tflite + `MicroWakeWord.kt`) → SUCCESS.
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 1m 30s**, signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓).

## INSTALL
`adb install -r` → **Success** on `9c1464eb`, data preserved (`lastUpdateTime 2026-09-10 19:10:21`).
Device confirms the probe state:
```
NATIVE_WAKE_UNAVAILABLE reason=missing_model asset=wakeword/benson.tflite
WAKE_ENGINE select=JS_WHISPER_FALLBACK nativeModel=false
WAKE_POKE src=native_heartbeat            (every 3 s — WAKE_STATE_BUG_1 preserved)
WAKE_HEALTH ... WAKE_READY=true reason=ok  (foreground — no regression)
```

## DEVICE TESTS
| test | result |
|---|---|
| **PRIMARY** (backgrounded + JS suspended, say "Benson" → native detect) | **NOT_RUN** — no `benson.tflite`; the native detector has no model to run |
| P1 foreground wake | **NOT_RUN** (native path); JS-fallback foreground wake still works (`WAKE_READY=true`) |
| P2 Home/background wake | **NOT_RUN** (native); JS fallback = FAIL-structural (ROUND_WAKE_STATE_BUG_1) |
| P3 wake while another app foreground | **NOT_RUN** (native); JS fallback = FAIL-structural |
| P4 wake after command | **NOT_RUN** (native); JS fallback works foreground |
| P5 wake after TTS | **NOT_RUN** (native); JS fallback works foreground (STABILIZATION_1 gate confirmed) |
| P6 long idle | **NOT_RUN** |
| P7 screen off | **NOT_RUN** |

Not a single PASS is claimed from build/inspection — as instructed.

## REGRESSION_GATE (device, structural)
- R1 foreground service alive = **PASS**
- R2 accessibility bound = **PASS**
- R3 no stale mic hold = **PASS** (`micHold=false`)
- R4 no stale speaking state = **PASS** (`speaking=false` idle)
- R5 exactly one recognizer owner = **PASS** (native inert → `micOwner=NONE`; JS fallback single-session gate intact; **0 crashes** backgrounded 14 s)
- R6 wake works from background = **FAIL (structural, unchanged)** — the native fix that would flip this needs the model
- R7 TTS does not become user input = **PASS** (STABILIZATION_1 `userMicGate`)
- R8 WhatsApp call route unchanged = **PASS** (routing tests green; no WA code touched)
- R9 confirmation logic unchanged = **PASS**
- R10 mission supersession unchanged = **PASS**

## REMAINING_LIMITATIONS
1. **`benson.tflite` must be produced.** This environment has no Python / TensorFlow / GPU /
   microWakeWord toolchain — training is not feasible here, and no wake word was substituted.
   **Hand-off:**
   - Train "Benson" with the audited microWakeWord toolchain — the community trainer
     (`microwakeword.com`, "taking requests" thread) or the repo's Colab notebook
     (`OHF-Voice/micro-wake-word`, synthetic samples, ~1 h). Collect negatives from ambient
     RO/DE/EN speech on the device for the false-accept set.
   - Drop the output at `android/app/src/main/assets/wakeword/benson.tflite`, rebuild, `adb install -r`.
   - On next service start `onStartCommand` auto-arms it (`NWW_MODEL_SIGNATURE` will print the
     input shape it detected); tune `DETECT_THRESHOLD` / the log-mel params on device against
     `NWW_DETECTED score=` until false-accept ≈ 0 over 30 min ambient and false-reject ≤ 1/10.
   - Then run P1–P7. The PRIMARY proof (backgrounded + JS suspended) is the one that matters —
     the native `AudioRecord` thread already runs independently of the RN host state (proven this
     turn: `WAKE_POKE thread=main` keeps firing while `mqt_v_js` is parked).
2. **Frontend exactness** — the in-code log-mel is standard, not yet matched to a specific
   training config; the probe adapts to raw-audio-input models automatically, which is the
   lower-risk path if the trained model bakes its frontend into the graph (microWakeWord V2 can).
3. **OxygenOS background `AudioRecord`** — must be verified on `9c1464eb` once the model exists
   (P2/P7). The HA Companion App precedent says it works; if OEM policy blocks it, that is a
   device finding, reported honestly, not a failure to hide.
4. **`bringActivityToFront()` on native detect** — best-effort; if OxygenOS blocks the bg Activity
   start, command capture waits for a bubble tap (existing behaviour). Not a blocker for the
   detection proof.

## Confirm
- one type of change: add (native wake engine probe + mic-ownership); JS wake loop demoted, not removed
- fail-safe verified on device: no model ⇒ `NATIVE_WAKE_UNAVAILABLE` ⇒ JS fallback unchanged, 0 crashes
- tsc PASS · routing tests ALL PASS · build PASS (`O=TOKKO`) · installed (data preserved)
- DEVICE detection: **NOT_RUN** (blocked on `benson.tflite`); no PASS claimed
- git / prebuild / setx: NO
