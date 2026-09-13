# ROUND_NATIVE_WAKE_PORCUPINE_1 — ARCHITECTURE + PROBE (design first)

Per the round: **deliver FILES TO CHANGE / MIC OWNERSHIP PLAN / INTEGRATION RISK / ROLLBACK PLAN
first, implement only after the architecture is coherent.** No code shipped this pass. No WhatsApp
changes.

Held back deliberately because: (a) the round asks for the design first; (b) the STABILITY
CONTRACT forbids untested broad rewrites, and the test device has been offline for several rounds
(`STABILIZATION_1` is built but not yet installed / S1–S7 not run); (c) flipping the passive wake
engine is exactly the kind of change that needs a device to prove.

---

## What already exists (do NOT rebuild)

`BensonForegroundService.kt` already has, working and revert-guarded:
- `ai.picovoice:porcupine-android:3.0.1` (gradle dep present).
- `WakeEngineConfig` — `WAKE_ENGINE = "porcupine"`, `PORCUPINE_MODEL_ASSET = "porcupine/benson.ppn"`,
  `PORCUPINE_ACCESS_KEY_PREF = "porcupine_access_key"`, `PORCUPINE_SENSITIVITY = 0.5f`.
- `tryStartPorcupine()` — checks the model asset, checks the key pref, builds `PorcupineManager`,
  `.start()`, callback → `onHotwordDetected("")`; on **any** failure logs `WAKE_ENGINE_FALLBACK
  reason=missing_model|missing_key|init_failed` and returns false → SpeechRecognizer fallback.
- `startHotwordLoop()` / `stopHotwordLoop()` (tears down `porcupineManager`) / `getActiveWakeEngine()`.
- Module: `setPorcupineAccessKey`, `isPorcupineConfigured`, `getPorcupineStatus`.
- `wake_word_enabled` kill switch honoured inside `startHotwordLoop()`.

**Gap 1** — `startHotwordLoop()` is **not auto-started** in `onStartCommand` (removed 2026-08-24
because it raced the JS local-Whisper wake loop for the mic). It only runs on `ACTION_REVIVE` /
`ACTION_RESUME_HOTWORD`.
**Gap 2** — no `benson_android.ppn` asset, no AccessKey on device → every start falls back to the
device-broken SpeechRecognizer.
**Gap 3** — the JS local-Whisper loop (`startLocalWakeLoop` in `app/index.tsx`) is the de-facto
passive wake engine and dies with the RN JS runtime.

Target = close Gap 1 (native auto-arm), keep Gap 2 as a **probe** (`WAKE_PORCUPINE_UNAVAILABLE`
+ existing fallback), and demote the JS wake loop to fallback-only once Porcupine is proven.

---

## FILES TO CHANGE

| file | change | size |
|---|---|---|
| `modules/benson-foreground-service/.../BensonForegroundService.kt` | `WakeEngineConfig`: accept `porcupine/benson_android.ppn` **and** `benson.ppn`; accept `picovoice_access_key` **or** `porcupine_access_key`. New native wake lifecycle: `WAKE_ARMED`→`WAKE_DETECTED`→`WAKE_SUSPENDED_FOR_COMMAND`→…→`WAKE_REARMING`, and `MIC_OWNER owner=<PORCUPINE\|COMMAND_STT\|TTS\|CALL\|NONE>` emitted at each transition. `porcupineArm()` / `porcupineSuspend(reason)` / `porcupineRearm()` wrappers around the existing manager. **Conditional auto-arm** in `onStartCommand` default path: `if (porcupineAvailable() && wakeWordEnabled && !inCommandCapture && !micHoldActive) porcupineArm()`. A `porcupineSuspendedReasons: Set<String>` so it re-arms only when ALL of {COMMAND, TTS, CALL, MIC_HOLD} clear. Bail to `WAKE_PORCUPINE_UNAVAILABLE` + leave existing wake untouched when model/key absent. | ~120 lines, additive; existing `tryStartPorcupine`/`stopHotwordLoop` reused |
| `modules/benson-foreground-service/.../BensonForegroundServiceModule.kt` | `Function("setPicovoiceAccessKey")` (alias of `setPorcupineAccessKey`), `Function("isPorcupineWakeAvailable")` → `{ model:Bool, key:Bool, available:Bool }`, `Function("porcupineSuspend")(reason)` / `Function("porcupineRearm")()` for JS to drive the handoff, `Function("notifyCommandCaptureState")(active:Bool)`. | ~40 lines |
| `modules/benson-foreground-service/index.js` / `index.d.ts` | export the 4 new functions. | ~20 lines |
| `app/index.tsx` | Wake pipeline: on `PORCUPINE_DETECTED` (native event → new `addWakeWordDetectedListener` payload distinguishes engine) run the existing command path (`handleWakeDetected("")`). **Before** any command capture: `porcupineSuspend('COMMAND')`. **Before** TTS (`beginTtsBlock`): `porcupineSuspend('TTS')`. **After** command + TTS + tail (`afterPromptRearm` / self-heal idle): `porcupineRearm()`. On WhatsApp/phone call arm: `porcupineSuspend('CALL')`; on verified `CALL_ENDED` (existing `getWhatsAppCallEndedSignalAt` self-heal path): `porcupineRearm()`. When `isPorcupineWakeAvailable().available` → set `wakeEngineRef.current = 'native'` so `startLocalWakeLoop()` no-ops (its existing `if (wakeEngineRef.current !== 'local') return` guard) — **the JS loop becomes fallback-only, not removed.** | ~60 lines, mostly wiring at existing chokepoints |
| `plugins/` / `app.json` | asset bundling note only — `assets/porcupine/benson_android.ppn` placed by the user; no plugin change needed (Porcupine SDK resolves from `assets/` and the existing `benson.ppn` path already proves the bundling works). | 0 (doc) |
| **NOT touched** | any WhatsApp file, `missionExecutor`, `commandParser`, contact resolver, the STABILIZATION_1 `userMicGate`/`afterPromptRearm`, `BensonAccessibilityService`. | — |

No new module, no new service, no new gradle dep (Porcupine already present).

---

## MIC OWNERSHIP PLAN

Single owner at all times, tracked by `micOwner: enum { PORCUPINE, COMMAND_STT, TTS, CALL, NONE }`
in `BensonForegroundService` (authoritative) and mirrored to JS via `MIC_OWNER` logs + one query
function.

```
                 ┌─────────────── PORCUPINE ◄──────────────────┐
                 │ (porcupineManager.start(); MIC_OWNER=PORCUPINE)│
                 │                                              │
   PORCUPINE_DETECTED                                    porcupineRearm()
   → porcupineSuspend('COMMAND')                    (only when suspendReasons == ∅)
   → porcupineManager.stop()                              ▲
   → MIC_OWNER=COMMAND_STT                                │
                 ▼                                        │
            COMMAND_STT ── beginTtsBlock() ──► TTS ── endTtsBlock()+TAIL ──► NONE ──┘
            (existing JS      MIC_OWNER=TTS       (AUDIO_TAIL_GUARD_MS,
             command STT)                          then re-arm)
```

Rules enforced:
1. **`porcupineManager.stop()` is called (not just ignored)** before COMMAND_STT opens the mic —
   Porcupine fully releases `AudioRecord`. It is `.start()`-ed again only in `porcupineRearm()`.
2. **`porcupineRearm()` is idempotent and gated**: it no-ops unless `micOwner == NONE` AND
   `suspendReasons` is empty AND `wake_word_enabled` AND `!micHoldActive`. So COMMAND finishing
   while TTS still runs does not prematurely re-arm.
3. **Never two `.start()`** — `porcupineArm()` checks `porcupineManager == null || !running`; the
   existing `hotwordLoopRunning` guard is reused.
4. **Call conflict (req 6)**: when the WhatsApp/phone call mic-hold arms (`whatsappCallMicHoldUntilMs`,
   already persisted), `porcupineSuspend('CALL')`. The existing WA-LIFECYCLE `CALL_ENDED` signal
   (`getWhatsAppCallEndedSignalAt`, consumed in the JS self-heal) additionally calls
   `porcupineRearm()`. If JS is dead, a native watchdog tick (5 s) reads the persisted
   `wa_call_lifecycle_state`; on `IDLE` with `suspendReasons=={CALL}` it clears CALL and re-arms.
5. **TTS (req 2, STABILIZATION_1 invariant)**: `beginTtsBlock()` → `porcupineSuspend('TTS')`.
   Porcupine cannot fire during BENSON's own speech because its mic is released. The
   `userMicGate` in JS remains the second line of defence for the command recognizer.
6. `MIC_OWNER owner=NONE` during the `AUDIO_TAIL_GUARD_MS` window between `endTtsBlock` and
   re-arm — nothing captures, matching the STABILIZATION_1 tail guard.

Command parsing: Porcupine's callback forwards **only** `""` (bare wake) — it has no ASR. It can
never submit text to `commandParser`. Requirement "Porcupine is WAKE ONLY" holds by construction.

---

## INTEGRATION RISK

| risk | likelihood | mitigation |
|---|---|---|
| Porcupine + JS whisper both open `AudioRecord` (the 2026-08-24 race that caused this to be disabled) | **high if mis-wired** | `wakeEngineRef.current='native'` makes `startLocalWakeLoop()` no-op via its existing guard; `porcupineArm()` only from the native dispatcher, never from JS; single `micOwner`. |
| `porcupineManager.stop()`/`start()` churn drops the first ~200 ms after re-arm (SDK warm-up) | medium | re-arm during idle only; `PORCUPINE_REARM` logged; acceptance allows "wait 30 s then say Benson". |
| AccessKey rate-limits / expires (Picovoice free tier was previously refused) | medium | `PorcupineException` already caught → `WAKE_ENGINE_FALLBACK` → SpeechRecognizer; add `PORCUPINE_ERROR reason=activation` and keep the JS fallback armed when Porcupine errors repeatedly. |
| Auto-arm in `onStartCommand` re-introduces the FGS-restart `SecurityException` path (Android 14) | low | arm from a `mainHandler.post` AFTER `startForeground` succeeded, same as `ACTION_RESUME_HOTWORD` already does; never from a background `startService`. |
| Call mic-hold ↔ Porcupine deadlock (suspend for CALL, CALL_ENDED signal missed) | low–med | native 5 s watchdog reads persisted `wa_call_lifecycle_state`; bounded, idempotent. |
| Battery — Porcupine holds the mic continuously | low | it's designed for always-on; the FGS already holds a `FOREGROUND_SERVICE_TYPE_MICROPHONE` + wakelock. Net mic time similar to the JS VAD loop. |
| Removing JS as primary wake breaks `wakeEngineRef` assumptions elsewhere | medium | keep `wakeEngineRef` values `'local'|'native'` (already the type); audit the ~6 `wakeEngineRef.current === 'local'` sites (self-heal, resumePassiveWake) — they already branch to `resumeHotword()` for `'native'`. |

---

## ROLLBACK PLAN

Three independent levers, smallest first:

1. **`WakeEngineConfig.WAKE_ENGINE = "speechrecognizer"`** (one constant) — Porcupine never
   attempted; exact pre-round native behaviour.
2. **`PORCUPINE_AUTO_ARM = false`** (new constant) — Porcupine stays available via
   `ACTION_RESUME_HOTWORD` but is not auto-armed by the service; JS local-Whisper loop resumes as
   primary (`wakeEngineRef` left `'local'`).
3. **Absent key/model** — `WAKE_PORCUPINE_UNAVAILABLE` is logged and the existing wake system runs
   unchanged. This is the **probe default**: nothing regresses until an AccessKey + `benson_android.ppn`
   are actually placed on the device.

JS side: `wakeEngineRef.current` forced back to `'local'` if `isPorcupineWakeAvailable().available
=== false` — a single check at boot, no ripple.

No data migration, no persisted-state change (reuses `benson_watchdog_prefs` keys). Uninstalling
the round = reverting these files; mission/memory untouched.

---

## PROBE (safe to ship now, on request) — no behaviour change

Only these, all additive and inert without a key/model:
- `WakeEngineConfig` accepts `benson_android.ppn` + `picovoice_access_key` (aliases).
- `tryStartPorcupine()` logs `WAKE_PORCUPINE_UNAVAILABLE reason=missing_model|missing_key`
  alongside the existing `WAKE_ENGINE_FALLBACK`.
- New logs mapped onto the existing Porcupine path: `WAKE_ENGINE engine=PORCUPINE`,
  `PORCUPINE_INIT`, `PORCUPINE_ARMED`, `PORCUPINE_DETECTED`, `PORCUPINE_SUSPEND reason=…`,
  `PORCUPINE_REARM`, `PORCUPINE_ERROR reason=…`, `MIC_OWNER owner=…`.
- Module: `setPicovoiceAccessKey`, `isPorcupineWakeAvailable`.
- **No** auto-arm flip, **no** JS-loop demotion — those wait for the device + your go-ahead on
  this architecture.

---

## Honest scope note (req 9)
- background / screen off / **app UI closed but process alive**: target supported by Porcupine
  (native, in the FGS, mic held continuously).
- Android **explicit Force Stop** (Settings → Force stop, or a swipe that the OEM treats as a
  kill): hard lifecycle boundary — `START_STICKY` + `RECEIVE_BOOT_COMPLETED` + the Guardian
  watchdog are best-effort revival, **not** a guarantee, and **not** an acceptance requirement.

## Decision needed from you
1. Approve this architecture (or amend the mic-ownership / rollback levers).
2. Provide a Picovoice AccessKey + a `benson_android.ppn` (or `benson_android` trained keyword) —
   without both, this stays a logging probe.
3. Confirm the JS local-Whisper wake loop may be **demoted to fallback** (not deleted) once
   Porcupine passes 5/5 on device.
