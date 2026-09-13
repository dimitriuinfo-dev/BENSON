# ROUND_BUBBLE_STATE_DESYNC_FIX_1_REPORT

Implements a native-owned overlay lifecycle, replacing the JS-`setTimeout`-based auto-dismiss
`ROUND_BUBBLE_GEMINI_BEHAVIOR_1` had just added (that mechanism was never device-tested before
this round's real-device evidence — a screenshot showing exactly the predicted split-brain
symptom — arrived). **No real-device acceptance testing was performed this round.** Per explicit
instruction I stopped driving the phone via `adb` and stopped asking you to type test commands
(the device had shown YouTube in foreground, i.e., in active use, in the prior round) — TEST 1-5
are honestly `NOT_RUN` below, not claimed from build success.

---

## ROOT CAUSE OF THE REPORTED DESYNC

The screenshot showed `"GATA"` (a DONE/ERROR label) next to stale `"Pensând..."` body text, with
both the idle bubble and the expanded card visible at once. Two independent, compounding causes,
both structural (not something a single string-match fix could close):

1. **The auto-dismiss timer lived in JS** (`ROUND_BUBBLE_GEMINI_BEHAVIOR_1`'s
   `bandHideTimerRef`/`E3_BAND_LINGER_MS` `setTimeout`). Every JS timer in this app has already
   been shown, repeatedly, this session (`ROUND_WAKE_STATE_BUG_1`, and again live during
   `ROUND_WAKE_COMMAND_HANDOFF_FIX_1`'s device test) to go inert the moment React Native is
   backgrounded — Android suspends the JS engine, and a queued `setTimeout` callback simply never
   fires until JS resumes, if it ever does. An overlay whose only "hide yourself" instruction is a
   JS timer can therefore be stranded indefinitely while BENSON operates over another app — which
   is the entire point of the overlay existing in the first place.
2. **No delivery-ordering guarantee between successive `updateStatus()` IPC calls.** Each state
   transition (LISTENING → THINKING → EXECUTING → DONE) fires its own `startService(Intent)` call
   into `BensonBubbleService`; nothing previously stopped an older call's effects from landing
   after a newer one's if they were ever delayed or reordered (background CPU throttling, Binder
   scheduling under load). The observed "GATA header + Pensând body" is consistent with exactly
   this: two different calls' text fields, applied out of the order JS actually sent them in.

Both are now closed by moving ownership of the overlay's show/hide/dismiss decision entirely into
`BensonBubbleService.kt`, native, independent of whether JS is alive.

## ARCHITECTURE — ONE AUTHORITATIVE STATE, NATIVELY OWNED

JS (`app/index.tsx`'s existing, unmodified `setBensonState`/`pushBubbleBand` state machine) is
still the thing that DECIDES what BENSON is doing and calls `updateBubbleStatus(state, transcript,
visible, terminal, turnId)` to request a UI reflecting it — **unchanged responsibility**. What
changed is that native no longer trusts JS to also tell it *when to stop showing that request*:

```
updateStatus(state, transcript, visible, terminal, turnId):
  if turnId < lastAppliedTurnId:          // an older call arriving late
    log UI_STATE_DESYNC, DROP IT           // never let it clobber a newer one
    return
  lastAppliedTurnId = turnId
  log UI_STATE_SYNC

  if !visible or (state blank and transcript blank):
    dismissNow("explicit_hide")            // JS asked to hide now — honored immediately
    return

  hide idle bubble, show/update the card (both fields together, one call = one atomic frame)
  cancel any previous native dismiss timer
  if terminal:  schedule native dismiss in ~2.5s   // DONE/ERROR
  else:         schedule native dismiss in ~65s    // safety net for an abandoned turn

dismissNow(reason):
  clear both text fields FIRST (guarantees no stale frame, ever)
  tear down the card, hide the wake ring
  restore the idle bubble
```

`turnId` is `Date.now()` at the JS call site (`pushBubbleBand`) — a cheap, already-available
monotonic value, not a new subsystem. `terminal = (label === 'GATA')`, computed where the label
already is.

## A. DUPLICATE BUBBLES — FIXED

`updateStatus()` now hides the idle bubble (`View.GONE`) the instant it shows the card, and
`dismissNow()` restores it (`View.VISIBLE`) every time the card is torn down — the same function
that already existed, now with this side effect added, not two independent visibility states that
can drift apart.

## B. STALE BODY TEXT — FIXED

`dismissNow()` clears **both** `statusStateText` and `statusTranscriptText` before removing the
view — `BUBBLE_STATUS_CLEAR_BODY` logs this. Combined with the `turnId` staleness guard, an old
call can no longer partially overwrite a newer one's fields either. Both text fields are always
set together, from the same call, or cleared together, from the same dismiss — never one from an
old call and one from a new one.

## C. AUTO-DISMISS SURVIVES BACKGROUNDING — FIXED

The dismiss timer is a native `Handler(Looper.getMainLooper()).postDelayed(...)` inside the
service process — the same class of mechanism as this session's other native watchdogs
(`BensonForegroundService`'s `wakePokeTick`, the mic-ownership reclaim timer), which have already
been proven live this session to keep firing while `mqt_v_js` shows zero activity for 70+ seconds.
It does not call back into JS at all to execute the dismiss — `dismissNow()` is pure native code
operating on native view references.

## D. CONFIRMING EXCEPTION — PRESERVED

`CONFIRMING`'s label (`'AM ÎNȚELES'`, same as THINKING — unchanged, not this round's concern) is
not `'GATA'`, so `terminal=false` — it gets the long (~65s) safety-net timer, not the short one.
It will not auto-dismiss while genuinely waiting; the 65s value is deliberately placed just past
`missionOrchestrator.ts`'s own `PENDING_DISAMBIGUATION_TIMEOUT_MS` (60000) so a legitimate
confirmation wait is never cut short by this backstop — it only fires if JS itself never resolved
or expired the wait on its own (the exact "JS died mid-turn" scenario this round exists to bound).

## FILES CHANGED

| File | Change |
|---|---|
| `modules/benson-overlay/android/.../BensonBubbleService.kt` | native dismiss-timer ownership, `turnId` staleness guard, idle/status mutual exclusivity, status-card drag, size constant |
| `modules/benson-overlay/android/.../BensonOverlayModule.kt` | `updateBubbleStatus` gains `terminal`, `turnId` params |
| `modules/benson-overlay/index.js`, `index.d.ts` | JS wrapper signature |
| `app/index.tsx` | `pushBubbleBand` computes and passes `terminal`/`turnId`; JS's own dismiss-timer scheduling removed (native is now the sole owner — avoids exactly the two-timers-racing split-brain this round is about) |

## LOGGING

All requested tags added, in `BensonBubbleService.kt` unless noted:
`UI_STATE_NATIVE`, `UI_STATE_SYNC`, `UI_STATE_DESYNC` — every `updateStatus()` call.
`UI_STATE_JS` — `app/index.tsx`'s `pushBubbleBand`, the JS-side request being made.
`BUBBLE_IDLE_HIDE`, `BUBBLE_IDLE_SHOW`, `BUBBLE_STATUS_SHOW`, `BUBBLE_STATUS_HIDE`,
`BUBBLE_STATUS_CLEAR_BODY`, `BUBBLE_AUTO_DISMISS_NATIVE_SCHEDULE`,
`BUBBLE_AUTO_DISMISS_NATIVE_EXECUTE`, `BUBBLE_AUTO_DISMISS_CANCEL`, `BUBBLE_EXPANDED_SHOW`,
`BUBBLE_EXPANDED_HIDE`, `BUBBLE_DRAG_START`/`BUBBLE_DRAG_END` (both surfaces, `surface=idle` or
`surface=status`), `BUBBLE_POSITION_RESTORE`.

## BUILD / INSTALL

```
npx tsc --noEmit               → 0 errors
:app:assembleRelease           → BUILD SUCCESSFUL (1m 16s), benson-overlay:compileReleaseKotlin ran clean
adb install -r                 → Success (9c1464eb)
```

## REAL DEVICE ACCEPTANCE

**TEST 1-5: NOT_RUN.** Per your explicit instruction this round ("do not drive the phone via adb,
do not ask me to type commands") and the prior round's finding that the phone was in active use
(YouTube foreground) — I stopped automated device interaction and did not resume it. The build
above is installed and ready; these five tests all require real interaction (drag gestures, wake
timing, watching a ~2.5s dismiss) that I cannot fabricate or infer from a successful build.

**What IS confirmed**: `npx tsc --noEmit` clean, Kotlin compiles clean across all three
`benson-overlay` files, `adb install -r` succeeded on the target device with data preserved. No
runtime behavior is claimed beyond that.

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Root cause (JS timer + no ordering guarantee) identified | DONE, with citations |
| Native-owned dismiss lifecycle implemented | IMPLEMENTED, build-verified only |
| `turnId` stale-delivery guard implemented | IMPLEMENTED, build-verified only |
| A. Duplicate bubbles | IMPLEMENTED, **NOT_RUN** on device |
| B. Stale body text | IMPLEMENTED, **NOT_RUN** on device |
| C. Auto-dismiss survives backgrounding | IMPLEMENTED (native Handler, same class already proven live elsewhere this session), **NOT_RUN** specifically for this overlay |
| D. Confirming exception | IMPLEMENTED, **NOT_RUN** on device |
| TEST 1 / TEST 2 / TEST 3 / TEST 4 / TEST 5 | **NOT_RUN** — awaiting your test, or your go-ahead to drive it via adb again |

**Next step**: whenever you're not actively using the phone, tell me and I'll either drive the
five tests via `adb` myself, or you can run them directly and tell me what you see (especially
TEST 3/4 — the background auto-dismiss — since that's the one this round was built around).
