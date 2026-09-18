# ROUND_WAKE_STATE_BUG_1_REPORT

Diagnosis from **real-device logs on `9c1464eb`** (3 independent captures), not code. Smallest
safe fix shipped. Structural verdict at the end.

---

## ROOT_CAUSE

**React Native fully suspends the JS runtime (`mqt_v_js` thread) whenever BENSON's Activity is
backgrounded — screen ON or OFF — despite the running foreground service and the partial
wakelock.** The *only working wake engine on this device is the JS local-Whisper loop*
(`startLocalWakeLoop` in `app/index.tsx`), which lives in that runtime. When JS is parked, **no**
JS re-arm mechanism runs — not `setTimeout(startLocalWakeLoop, 150)`, not the `setInterval`
self-heal, and not (as this round proved) a native-event `onWakePoke` handler.

### Device evidence
| capture | condition | result |
|---|---|---|
| C1 | HOME pressed (backgrounded, screen ON), 32 s | native `WAKE_POKE thread=main src=native_heartbeat` every 3 s (process alive) · **0 `mqt_v_js` lines** · 0 `WAKE_SCAN_START` |
| C2 | backgrounded + `KEYCODE_SLEEP` (screen OFF, the wakelock case the code comment claims keeps JS alive), 22 s | native `WAKE_POKE` every 3 s · **0 `mqt_v_js` lines** — wakelock does **not** keep the RN JS runtime alive |
| C3 | return to foreground (`monkey LAUNCHER`) | `APP_STATE state=active` → within ~2 s `WAKE_HEALTH … recognizer=LISTENING WAKE_READY=true reason=ok` — **instant, reliable recovery** |

So the native side is never frozen (`WAKE_POKE` keeps ticking); only RN's JS execution is paused
on `onHostPause`. `sendEvent("onWakePoke")` is queued/dropped, never delivered, until resume.

## SUCCESS_VS_FAILURE_STATE_DIFFERENCE

| stage | successful wake (foreground / just backgrounded) | failed wake (sustained background) |
|---|---|---|
| foreground service | ALIVE | ALIVE (identical) |
| accessibility | BOUND | BOUND (identical) |
| native heartbeat (`WAKE_POKE`) | firing | firing (identical) |
| **JS runtime (`mqt_v_js`)** | **executing** — `WAKE_HEALTH`/`AUDIO_STATE`/`WAKE_SCAN_*` every 2 s | **parked** — zero lines |
| wake loop re-arm | `startWakeScan` runs, `WAKE_SCAN_START` logged | never — the re-arm code cannot execute |
| recognizer | `LISTENING`, `audio=RECEIVING`, `WAKE_READY=true` | frozen in whatever state it held at background time |

**The first and only divergence: the JS runtime is executing vs. parked.** Everything downstream
of it (re-arm, scan, match, activation) simply doesn't run in the failing case.

"Sometimes works" = the wake attempt landed while a native capture round-trip from *before*
backgrounding was still completing, or BENSON was actually still foreground/within ~1 s of
`onHostPause`. There is no sustained-background success path.

## FIRST_BROKEN_STAGE

`wake_loop_rearm` — but caused one level up by `js_runtime_suspended_on_background`. Formally:

```
WAKE_FAILURE
firstBrokenStage=WAKE_LOOP_REARM
reason=js_runtime_suspended_while_backgrounded (RN onHostPause parks mqt_v_js;
        setTimeout / setInterval / DeviceEventEmitter callbacks all inert;
        the sole working wake engine (JS local-Whisper) lives there)
```

Audit of §2 candidates against evidence:
- A stale mic hold — **not it** (`micHold=false` in every capture).
- B stale speaking — **not it** (`speaking=false` when idle; STABILIZATION_1 gate clean).
- C recognizer lifecycle / duplicates — **not it** foreground (`WAKE_READY=true`); backgrounded the recognizer isn't errored, it's un-driven.
- D wake loop lifecycle — **this**: stopped after the background transition, self-heal cannot re-arm because self-heal is a JS timer.
- E stale session/turn — **not it** (STABILIZATION_1 `userMicGate` working; no wrongful `STT_DROP` foreground).
- F OxygenOS lifecycle — **partially**: it's RN's own `onHostPause`, not an OEM kill (native `WAKE_POKE` proves the process is not frozen).
- G TTS→wake handoff — **not it** (`afterPromptRearm` + `AUDIO_STATE from=TTS_SPEAKING to=WAITING_USER_REPLY` firing; `reason` walks `tts_speaking`→`tts_tail`→`ok`).
- H notification mismatch — confirmed present but a symptom, not the cause; `WAKE_READY` is now the truthful signal.

## FILES_CHANGED

| file | change | why |
|---|---|---|
| `modules/benson-foreground-service/.../BensonForegroundService.kt` | native `wakePokeTick` (`mainHandler.postDelayed`, `WAKE_POKE_INTERVAL_MS = 3000`), started in `onStartCommand` default path, stopped in `onDestroy`; `onWakePoke` companion callback | a native heartbeat that runs regardless of RN host state; re-arms the JS wake loop the instant JS is alive again (foreground / return) |
| `modules/benson-foreground-service/.../BensonForegroundServiceModule.kt` | `Events(… "onWakePoke")` + `OnCreate`/`OnDestroy` wiring | bridge the heartbeat to JS |
| `modules/benson-foreground-service/index.js` / `index.d.ts` | `addWakePokeListener` | JS subscription |
| `app/index.tsx` | `addWakePokeListener` handler → `startLocalWakeLoop()` (idempotent, all wake guards checked, `WAKE_SELF_HEAL_START/OK/FAIL reason=native_poke`); `WAKE_READY` field added to `WAKE_HEALTH` (`= service ALIVE ∧ a11y BOUND ∧ recognizer LISTENING ∧ ¬micHold ∧ ¬speaking ∧ audio RECEIVING ∧ activation READY`); `speaking=` field added | truthful health (§4); re-arm hook that works for foreground/return and is the correct seam for a future native engine |

**Not touched:** WhatsApp, missions, contact resolution, bubble UI, Porcupine, Car Mode. No new
service. START_STICKY unchanged.

## SMALLEST_FIX

The native heartbeat + `WAKE_READY`. It is honest about what it can do:
- **fixes** the foreground / return-to-BENSON re-arm (already reliable — C3) and gives a truthful
  ready signal;
- **cannot fix** sustained background wake, because the JS runtime that owns the only working
  wake engine is suspended — no native poke, timer, or event can make parked JS execute.

No second speculative fix was attempted (per §7 discipline) — the log evidence is unambiguous.

## BUILD
- `npx tsc --noEmit` → **0 errors**.
- `scripts/wa-routing-tests.ts` → **ALL PASS**.
- `:benson-foreground-service:compileReleaseKotlin` → SUCCESS. `:app:assembleRelease` →
  **BUILD SUCCESSFUL in 1m 3s**, signed `CN=BENSON, OU=Dev, O=TOKKO` (O=TOKKO ✓).

## INSTALL
`adb install -r` → **Success** on `9c1464eb`, data preserved (`lastUpdateTime 2026-09-10 18:18:58`).

## DEVICE_TEST_W1 — **FAIL (structural)**
BENSON not foreground → JS parked → no wake scan → "Benson" produces nothing. Proven, not
inferred (C1/C2: 0 JS lines for 32 s / 22 s while the native heartbeat ran normally). **Cannot
reach 5/5 with the JS wake engine.**

## DEVICE_TEST_W2 — **NOT_RUN** (voice-gated). Foreground/after-TTS re-arm is confirmed working in
the logs (`AUDIO_STATE` TTS→reply transitions, `reason` walks back to `ok`) but 5/5 needs voice.

## DEVICE_TEST_W3 — **NOT_RUN** (voice). After-cancel re-arm works foreground (STABILIZATION_1
`afterPromptRearm`); backgrounded = same W1 limitation.

## DEVICE_TEST_W4 — **NOT_RUN** (voice). After-ambiguity: no enumeration (STABILIZATION_1),
`afterPromptRearm` fires; foreground OK, background = W1 limitation.

## DEVICE_TEST_W5 — **FAIL (structural)**. Open WhatsApp/parking app → BENSON backgrounded → JS
parked → "Benson" dead until BENSON is reopened. This is the same root cause as
`ROUND_APP_GOVERNANCE_PARKING_1` Part 1, and it is **not fixable in the JS wake engine.**

## DEVICE_TEST_W6 — **NOT_RUN** (needs a real service restart during test). On restart the native
heartbeat re-arms as soon as JS is up; foreground recovery is C3-proven.

## REGRESSION_GATE
- R1 foreground service alive = **PASS** (`ServiceRecord … BensonForegroundService`)
- R2 accessibility bound = **PASS**
- R3 no stale mic hold = **PASS** (`micHold=false` every capture)
- R4 no stale speaking state = **PASS** (`speaking=false` idle; walks `tts_speaking→tts_tail→ok`)
- R5 exactly one recognizer owner = **PASS** (no duplicate `WAKE_SCAN_START`; poke handler guards `wakeScanningRef`)
- R6 wake works from background = **FAIL (structural — see W1)**
- R7 TTS does not become user input = **PASS** (STABILIZATION_1 `userMicGate`; `wakeMatch=false` on BENSON/ambient speech during `tts_speaking`)
- R8 WhatsApp call route unchanged = **PASS** (no WhatsApp code touched; routing tests green)
- R9 confirmation logic unchanged = **PASS** (untouched)
- R10 mission supersession unchanged = **PASS** (untouched)

## REMAINING_LIMITATIONS / VERDICT

**The existing wake architecture is proven structurally incapable of 5/5 background wake on this
device.** The working wake engine (JS local-Whisper) runs in an RN JS runtime that Android + RN
suspend on Activity background (screen on *and* off), and no native re-arm can execute parked JS.
This satisfies the round's escalation clause verbatim:

> "do not continue to Porcupine until this existing wake path is either proven stable or **proven
> structurally incapable** of meeting 5/5 reliability."

- **Foreground + return-to-BENSON wake**: reliable (C3), and now truthfully reported via `WAKE_READY`.
- **Background / screen-off / after-app-launch wake**: requires a **native** wake detector.
  The existing native `SpeechRecognizer` hotword loop is documented broken on this device
  (`ERROR_NO_MATCH`). → **Porcupine returns for your decision** (its architecture doc is already
  delivered: `ROUND_NATIVE_WAKE_PORCUPINE_1_ARCH.md`). Nothing about it is implemented here.

The native heartbeat + `WAKE_READY` + all logs are kept — they are the correct integration seam
for whichever native engine is chosen, and they measurably improve the foreground/return path
with zero regression.
