# ROUND_WA_LIFECYCLE_FIX_1_REPORT

WA-LIFECYCLE-FIX-1 — restart-recoverable WhatsApp call end + wake restore.
Fixes `POST_CALL_WAKE = FAIL` (`ROUND_WA_FIX_4_REPORT.md`): a WhatsApp call that ends while
OxygenOS kills/revives `BensonAccessibilityService` leaves BENSON deaf until the 180 s watchdog.

Device `9c1464eb` / OnePlus Nord 4 / OxygenOS 15.

---

## Status

| Item | Result |
|---|---|
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` | PASS — `BUILD SUCCESSFUL in 1m 8s`, APK 260,946,228 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install `9c1464eb` (`adb install -r`) | PASS — `Success`, data preserved, accessibility service still bound |
| Module recompiled | `BensonAccessibilityService$finalizeWhatsAppCallEnded$2.class`, `…$recoverWhatsAppCallLifecycle$1.class` present |
| **WA-LIFE-3** (no-call service restart) | **PASS (device)** — service destroyed+recreated, persisted `IDLE` → recovery no-ops, zero mic/lifecycle/auto-return side effects |
| **WA-LIFE-1** (call → end → natural kill → "Benson") | **NOT_RUN** — device wedged on lockscreen/notification-shade; cannot place a call; no voice input for the "say Benson" step |
| **WA-LIFE-2** (call → end → forced service recreation) | **NOT_RUN** — same |
| **WA-LIFE-4** (duplicate finalize) | **NOT_RUN** — same (idempotency is code-verified) |
| **WA-LIFE-5** (post-call "Benson" + "deschide calculatorul") | **NOT_RUN** — same (needs voice) |
| watchdog used | N/A (WA-LIFE-1/2/4/5 not run) |
| real call end → wake runtime restored time | N/A (not measured on device) |

The device sat locked on a wedged `NotificationShade` / `mDreamingLockscreen` (no PIN —
`Password quality: {0=0}` — but synthetic `adb` input could not dismiss it, and `am crash
com.android.systemui` is blocked). WA-LIFE-1/2/4/5 need an active WhatsApp call **and** a spoken
"Benson"; neither is reachable from this harness right now. The recovery machinery, its wiring into
`onServiceConnected`, and its correct no-op on `IDLE` are verified on device (WA-LIFE-3) and by
static trace; the call-active branches are code-verified with the exact expected log chain below.

---

## Root cause (confirmed, reproduced — `ROUND_WA_FIX_4_REPORT.md` POST_CALL_WAKE)

`watchWhatsAppCallLifecycle(contact, verifiedAt)` — the ONLY thing that committed `CALL_ENDED`,
released `whatsappCallMicHoldUntilMs`, set `callEndedReturnPending`, and fired the return Intent —
ran as a coroutine on the instance-owned `serviceScope` (`Dispatchers.Main + SupervisorJob`).
`onDestroy()` calls `serviceScope.cancel()`. When OxygenOS killed/revived the AccessibilityService
right after the call (`SERVICE_START_COMMAND … returning=START_STICKY` ×2 in the failing trace),
that coroutine vanished after logging `WA_CALL_STATE state=CALL_ENDING` and never reached
`CALL_ENDED`. Nothing on the fresh instance knew a call had been verified. The mic hold survived to
its 180 s failsafe; the wake loop never re-armed; BENSON showed "listening" but was deaf.

Secondary weakness: the JS `whatsappCallMicHoldUntilRef` cleared **only** in the `AppState 'active'`
handler — which never fired because BENSON stayed backgrounded — and the JS self-heal wake branch
is gated on `isForegroundRef.current`.

---

## Old lifecycle architecture

```
runWhatsAppCallNative / runWhatsAppOpenConversationCall
  WA_*_CALL_VERIFY success=true
    → whatsappCallMicHoldUntilMs = now + 180_000   (companion @Volatile)
    → serviceScope.launch { watchWhatsAppCallLifecycle(contact, verifiedAt) }   ← single point of failure
watchWhatsAppCallLifecycle:
  poll callScreenPresent() every 700ms
  gone for 3 polls → WA_CALL_STATE state=CALL_ENDED
    → whatsappCallMicHoldUntilMs = 0
    → callEndedReturnPending = true
    → returnToBensonForeground("call_ended")
JS AppState 'active' handler:
  consumeCallEndedReturnPending() → whatsappCallMicHoldUntilRef = 0; clearWhatsAppCallMicHold(); WAKE_MODE_RESTORED
```
If the service dies between `CALL_ENDING` and the debounce break → none of the above happens →
only the 180 s watchdog releases the hold, and the wake loop is never told.

---

## New persisted lifecycle architecture

Minimal state persisted in **`SharedPreferences("benson_watchdog_prefs")`** (the same store Guardian
already uses) — no conversation content, only:

| key | meaning |
|---|---|
| `wa_call_lifecycle_state` | `IDLE` \| `CALL_VERIFIED_ACTIVE` \| `CALL_ENDING_PENDING` |
| `wa_call_started_at` | ms — set when `CALL_VERIFIED_ACTIVE` persisted |
| `wa_call_updated_at` | ms — last transition |
| `wa_call_contact` | contact name (already logged everywhere) — for the return signal |
| `wa_call_ended_signal_at` | ms — bumped whenever `IDLE` is (re)persisted; the native→JS "call ended" marker, survives a process kill |

```
CALL_VERIFY success=true
  → arm mic hold (unchanged)
  → waCallPersistState(CALL_VERIFIED_ACTIVE, contact)     WA_CALL_PERSIST state=CALL_VERIFIED_ACTIVE
  → serviceScope.launch { watchWhatsAppCallLifecycle(...) }

watchWhatsAppCallLifecycle:
  first "call screen gone" poll   → WA_CALL_STATE state=CALL_ENDING
                                  → waCallPersistState(CALL_ENDING_PENDING, contact)   WA_CALL_PERSIST state=CALL_ENDING_PENDING
  debounce break OR failsafe cap  → WA_CALL_END_VERIFIED reason=…
                                  → finalizeWhatsAppCallEnded("watcher" | "watchdog")

onServiceConnected (every (re)bind):
  WA_SERVICE_LIFECYCLE event=connected epoch=N
  recoverWhatsAppCallLifecycle():
    read persisted state
    IDLE                         → return, silent (WA-LIFE-3)
    CALL_VERIFIED_ACTIVE|ENDING  → WA_CALL_RECOVERY_START persistedState=…
       serviceScope.launch:
         delay 600ms
         age > 30 min            → WA_CALL_RECOVERY_RESULT action=finalize_ended → finalizeWhatsAppCallEnded("service_recovery")
         poll callScreenPresent() up to 8×600ms:
           present ≥ 2            → WA_CALL_RECOVERY_OBSERVE callPresent=true  → action=resume_watch → re-persist ACTIVE + watchWhatsAppCallLifecycle(...)
           absent  ≥ 3            → WA_CALL_RECOVERY_OBSERVE callPresent=false → action=finalize_ended → finalizeWhatsAppCallEnded("service_recovery")
         still uncertain          → WA_CALL_RECOVERY_RESULT action=retry → 1.2s → one recheck → resume_watch | finalize_ended
```

The 180 s watchdog is **kept** (`maxWatchMs = 30 min` failsafe in the watcher still exists and
`finalizeWhatsAppCallEnded("watchdog")` is its terminal call; the JS `WA_CALL_MIC_HOLD_MS = 180_000`
is untouched) but is now only the last resort — the normal paths are `watcher` and
`service_recovery`, both of which fire within seconds.

### `finalizeWhatsAppCallEnded(reason)` — the ONE authoritative end path

Instance method, **idempotent** — guarded on the persisted state:
```
synchronized(waFinalizeLock):
  if persisted state == IDLE  → return          ← second call after IDLE does nothing
  waCallPersistState(IDLE, "")                    (also bumps wa_call_ended_signal_at)
  whatsappCallMicHoldUntilMs = 0
  callEndedReturnPending = true
  WA_CALL_STATE state=CALL_ENDED reason=<watcher|watchdog|service_recovery>
  WA_CALL_AUDIO_HOLD state=released
  WA_CALL_END_SIGNAL state=published
  WA_WAKE_RESTORE_REQUEST reason=call_ended
outside the lock:
  returnToBensonForeground("call_ended")          (best-effort, independent of wake restore)
  serviceScope.launch { poll lastForegroundPackage 4s → WA_CALL_RETURN_FOREGROUND_VERIFIED }
```
Called from: the watcher (end of poll loop), `recoverWhatsAppCallLifecycle` (call absent). Both can
call it; only the first past the `IDLE` guard has effect → **no duplicate hold release / return
Intent / signal** (WA-LIFE-4, code-verified).

### Native → JS end signal

- `callEndedReturnPending` (companion `@Volatile`) — unchanged fast in-process path; still consumed
  by the AppState 'active' handler.
- **`getWhatsAppCallEndedSignalAt(): number`** — new bridge `Function`, returns
  `wa_call_ended_signal_at` from prefs (ms; 0 = none). Survives a process kill.

### JS mic-hold clearing + wake re-arm — no dependence on AppState 'active'

`app/index.tsx` self-heal interval (`LISTEN_SELF_HEAL_MS = 2000`) — a new block runs **first,
before every existing guard** (`silenced`/`loading`/`speaking`/`convMode`/`isForeground`):
```
sig    = getWhatsAppCallEndedSignalAt()          (persisted)
pending = consumeCallEndedReturnPending()        (in-process)
if pending  OR  (sig > 0 && sig !== lastCallEndedSignalRef.current):
   lastCallEndedSignalRef = sig || now
   whatsappCallMicHoldUntilRef.current = 0
   clearWhatsAppCallMicHold()
   WA_CALL_ENDED_NATIVE via=self_heal sigAt=… wasHeld=…
   WAKE_MODE_RESTORED reason=call_ended_native
   if !silenced && !convMode: resumePassiveWake()
   WAKE_RUNTIME serviceAlive=… micHold=… wakeLoop=… detectorActive=…
```
This fires **while BENSON is still backgrounded** — the `isForegroundRef` gate on the *old* wake
self-heal branch (line ~1430) is bypassed for this recovery. `resumePassiveWake()` →
`startLocalWakeLoop()` → (hold now 0, `waCallMicHoldActiveSafe()` now false) → `WAKE_SCAN_START`.
No duplicate loops: `startLocalWakeLoop()`/`resumePassiveWake()` self-guard on
`wakeScanningRef`/`listeningRef`/`wakeTriggeredRef`.

### Auto-return

Unchanged mechanism — `returnToBensonForeground("call_ended")` (explicit MainActivity Intent, no
BACK/HOME/gesture). Now called from `finalizeWhatsAppCallEnded` and **decoupled from wake
restoration**: even if Android blocks/delays the foreground launch, the JS self-heal has already
cleared the hold and re-armed wake off the published signal.

### Diagnostics added

`WA_SERVICE_LIFECYCLE event=connected|destroyed epoch=N` · `WA_CALL_PERSIST state=…` ·
`WA_CALL_RECOVERY_START persistedState=… ageMs=…` · `WA_CALL_RECOVERY_OBSERVE callPresent=true|false[ detail=…]` ·
`WA_CALL_RECOVERY_RESULT action=resume_watch|finalize_ended|retry` ·
`WA_CALL_STATE state=CALL_ENDED reason=watcher|watchdog|service_recovery` ·
`WA_CALL_END_SIGNAL state=published` · `WA_WAKE_RESTORE_REQUEST reason=call_ended` ·
`WA_CALL_ENDED_NATIVE via=self_heal` · `WAKE_MODE_RESTORED reason=call_ended_native` · `WAKE_RUNTIME …`.

---

## Files changed

| File | Protected? | Change · why necessary |
|---|---|---|
| `modules/benson-accessibility/android/.../BensonAccessibilityService.kt` | yes | Persisted lifecycle state (companion: keys + `waCallPersistState` / `waCallReadState` / `getWhatsAppCallEndedSignalAt`); `finalizeWhatsAppCallEnded()` (idempotent); `recoverWhatsAppCallLifecycle()`; `onServiceConnected` → recovery + `WA_SERVICE_LIFECYCLE event=connected`; `onDestroy` → `event=destroyed`; `watchWhatsAppCallLifecycle` persists `CALL_ENDING_PENDING` and now ends by calling `finalizeWhatsAppCallEnded`; both `CALL_VERIFY` sites persist `CALL_VERIFIED_ACTIVE`. **Necessary:** the bug is native — the end path must not depend on one `serviceScope` instance; only a fresh instance can re-verify reality. |
| `modules/benson-accessibility/android/.../BensonAccessibilityModule.kt` | yes | `+ Function("getWhatsAppCallEndedSignalAt")`. **Necessary:** JS needs a process-kill-durable "call ended" signal it can poll without an AppState transition. |
| `modules/benson-accessibility/index.js` / `index.d.ts` | yes | `+ export getWhatsAppCallEndedSignalAt()`. **Necessary:** bridge surface for the above. |
| `app/index.tsx` | (approved this round) | self-heal loop consumes the native end signal unconditionally → clears JS hold + `resumePassiveWake()`; `lastCallEndedSignalRef`; import. **Necessary:** the JS-side hold + wake re-arm must not wait for `AppState 'active'` (never fires while backgrounded). |

Not touched: WhatsApp contact resolution, direct-contact deep-link route, call-button selectors,
confirmation gate, mission supersession, YouTube, Waze, Drupe, UI, TTS, `benson-audio-capture`
(the wake audio path re-arms via the existing `resumePassiveWake`/`startLocalWakeLoop`, no capture
change needed). The 180 s watchdog constant is unchanged.

---

## Device evidence

### WA-LIFE-3 — PASS
Forced AccessibilityService recreation (toggled `enabled_accessibility_services`) with **no**
WhatsApp call ever active this boot:
```
WA_SERVICE_LIFECYCLE event=destroyed epoch=2
WA_SERVICE_LIFECYCLE event=connected epoch=4
```
No `WA_CALL_RECOVERY_START`, no `WA_CALL_PERSIST`, no `WA_CALL_STATE`, no
`WA_CALL_AUDIO_HOLD`, no `WA_CALL_RETURN_FOREGROUND_VERIFIED`. `recoverWhatsAppCallLifecycle()` ran,
read persisted `IDLE`, and returned silently. **A no-call service restart does nothing** — no fake
`CALL_ENDED`, no mic manipulation, no auto-return, wake unaffected. ✔

### WA-LIFE-1 / -2 / -4 / -5 — NOT_RUN (device locked; no voice)
Exact expected log chain (for the user to run — physically unlock, then `benson://debug` →
`suna pe Baby pe WhatsApp` → `da`):
```
WA_DIRECT_CALL_VERIFY success=true
WA_CALL_PERSIST state=CALL_VERIFIED_ACTIVE
… call ends …
WA_CALL_STATE state=CALL_ENDING
WA_CALL_PERSIST state=CALL_ENDING_PENDING
── if the service is killed/revived here (WA-LIFE-2: toggle accessibility; WA-LIFE-1: let OxygenOS do it) ──
WA_SERVICE_LIFECYCLE event=destroyed epoch=N
WA_SERVICE_LIFECYCLE event=connected  epoch=N+2
WA_CALL_RECOVERY_START persistedState=CALL_ENDING_PENDING ageMs=<small>
WA_CALL_RECOVERY_OBSERVE callPresent=false
WA_CALL_RECOVERY_RESULT action=finalize_ended
WA_CALL_STATE state=CALL_ENDED reason=service_recovery
WA_CALL_AUDIO_HOLD state=released
WA_CALL_END_SIGNAL state=published
WA_WAKE_RESTORE_REQUEST reason=call_ended
── JS self-heal, within ~2s, no AppState 'active' required ──
WA_CALL_ENDED_NATIVE via=self_heal sigAt=<ts> wasHeld=true
WAKE_MODE_RESTORED reason=call_ended_native
WAKE_RUNTIME serviceAlive=true micHold=false wakeLoop=false detectorActive=true
WAKE_SCAN_START engine=local …
── then say "Benson" ──
WAKE_SCAN_HIT / WAKE_DETECTED
```
PASS = the above with **no `WA_CALL_STATE state=CALL_ENDED reason=watchdog`** and no ~180 s gap
between call end and `WAKE_SCAN_START`.

---

## Definition-of-done checklist

| Requirement | Status |
|---|---|
| lifecycle survives service recreation | **implemented** — persisted state + `onServiceConnected` recovery; WA-LIFE-3 shows the recovery hook runs |
| `CALL_ENDED` not solely coroutine-owned | **implemented** — `finalizeWhatsAppCallEnded` reachable from watcher AND fresh-instance recovery |
| native mic hold released on verified end | **implemented** (`finalizeWhatsAppCallEnded`) — device-unverified for the call case |
| JS mic hold released without AppState 'active' | **implemented** (self-heal consumes `getWhatsAppCallEndedSignalAt` / `consumeCallEndedReturnPending`) — device-unverified for the call case |
| wake runtime restored (`WAKE_SCAN_START`) | **implemented** (`resumePassiveWake` from self-heal) — device-unverified for the call case |
| idempotent finalize | **implemented** (`IDLE`-guarded + `synchronized`) |
| watchdog = emergency only | **implemented** — `finalizeWhatsAppCallEnded("watchdog")` distinct `reason`; normal paths are `watcher` / `service_recovery` |
| `"Benson"` detected again + next command runs | **NOT verified** — needs voice on an unlocked device |

## Confirm

- source modified: **YES** — 5 files (§Files changed); protected files changed under this round's explicit lifecycle-fix authorization; each justified above
- `npx tsc --noEmit`: **PASS** · release build: **PASS** · APK signed `CN=BENSON, O=TOKKO` · installed (data preserved)
- git / prebuild: **NO**
- WhatsApp call placed during this round: **NO** (device locked)
- watchdog relied on: **N/A** (call-path tests not run)

---
---

# ROUND_WA_LIFECYCLE_ACCEPTANCE_1 — attempt log

**No source / build / patch changes.** Testing the already-installed WA-LIFECYCLE-FIX-1 APK
(`lastUpdateTime 2026-09-09 12:00:43`).

- STEP 1 (clean logs): **DONE** — `adb logcat -c`; persistent capture armed to
  `scratchpad/wa_life_accept.log` (tags `BENSON_AUDIO:I BensonA11y:W ReactNativeJS:I *:S`).
- Device state at attempt: unlocked (`deviceLocked=0`), BENSON foreground service `isForeground=true`,
  accessibility service bound.

- STEP 2 / 5 / 6: **CANNOT BE EXECUTED FROM THIS HARNESS.** The round mandates the real voice
  path — spoken wake word `"Benson"`, then `"sună pe Baby pe WhatsApp"`, then `"da"`, then (post
  call) `"Benson"` and `"deschide calculatorul"` — and explicitly forbids the Debug Panel and any
  manual BENSON interaction. There is no `adb`/tool path to inject microphone audio, so none of the
  spoken steps can be produced here.

**WA-LIFE-1 = NOT_RUN · WA-LIFE-5 = NOT_RUN** (acceptance round) — reason: voice-only test, no mic
injection available. The fix itself is implemented, compiled, installed, and its recovery hook is
device-verified for the no-call case (WA-LIFE-3 PASS, above).

## To run the acceptance test (user, at the phone)

The background logcat capture is already running. Perform:

1. From BENSON's screen (or with the wake word): say **"Benson"**, then **"sună pe Baby pe WhatsApp"**, then **"da"**.
2. Wait for BENSON to say it's calling / `WA_DIRECT_CALL_VERIFY success=true`.
3. End the WhatsApp call normally (red end button). Do **not** touch BENSON.
4. Wait ≤ 10 s, then — without touching the phone — say **"Benson"**, then **"deschide calculatorul"**.

Then this session can pull `wa_life_accept.log` and fill in the exact chain / PASS-FAIL /
classification per §STEP 4 and §FAIL of the round spec. Expected chain (PASS): as documented in the
"Device evidence → WA-LIFE-1 / -2 / -4 / -5" block above, ending in `WAKE_SCAN_START` →
`WAKE_SCAN_HIT`/`WAKE_DETECTED` → Calculator opens, with **no `reason=watchdog`** and no ~180 s gap.
