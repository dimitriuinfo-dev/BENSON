# ROUND_BUBBLE_VISIBILITY_POLICY_1_REPORT

Implemented: the overlay is no longer persistent. It now exists **only** while BENSON is actively
working outside its own app, and disappears completely (not to an idle bubble) when that ends.
Build succeeded and is installed on the device; **real-device acceptance tests were not run this
round** (see §Device testing below for why, and what happened instead).

---

## POLICY IMPLEMENTED

```
foregroundPackage == BENSON        → overlayVisible = false   (nothing renders, ever)
foregroundPackage != BENSON, IDLE  → overlayVisible = false   (no idle bubble — this is the
                                                                 explicit correction from last round)
foregroundPackage != BENSON,
  state ∈ {LISTENING,THINKING,
           EXECUTING,CONFIRMING,
           RESULT}                 → overlayVisible = true    (dots + status card, together)
state ∈ {DONE,ERROR,CANCELLED,
         TIMEOUT}                  → short result visibility (~2.5s, unchanged from last round's
                                       native timer), then overlayVisible = false — completely,
                                       not back to an idle bubble
```

## WHAT CHANGED FROM THE PREVIOUS ROUND

`ROUND_BUBBLE_STATE_DESYNC_FIX_1`'s `dismissNow()` used to **restore** the small dots bubble to
`View.VISIBLE` after tearing down the status card — that was the "always-present idle bubble"
model, now explicitly forbidden. `dismissNow()` now calls `removeBubble()` (full teardown, same
function used by `onDestroy()`) instead of restoring visibility. The dots indicator and the status
card are now created together (`updateStatus()`'s show path) and torn down together
(`dismissNow()`) — one logical overlay unit that either fully exists or doesn't, never a
lingering idle remnant.

## SELF-APP SUPPRESSION — NATIVE, NOT JS

Per instruction ("use the existing foreground-package observation already in the project"): the
project already tracks the foreground package natively for JS's
`getForegroundPackage()`/`addForegroundChangeListener` — `BensonAccessibilityService.kt`'s
`onAccessibilityEvent()` updates `lastForegroundPackage` on every `TYPE_WINDOW_STATE_CHANGED`
event, and already pushes a native cross-module signal to `BensonBubbleService` on exactly this
event stream (`maybePushImeStateToBubble()`, the IME-visibility broadcast). I added a second,
identically-shaped broadcast on the **same** event, reusing the same idiom (component-name
`Intent`, no new Gradle dependency between the `benson-accessibility` and `benson-overlay`
modules):

```
BensonAccessibilityService.onAccessibilityEvent (TYPE_WINDOW_STATE_CHANGED, unchanged trigger)
  → pushForegroundPackageToBubble(packageName)   [NEW — dedup'd on actual self/other transition]
    → Intent(ACTION_FOREGROUND_PACKAGE_CHANGED, is_self_foreground) → BensonBubbleService

BensonBubbleService.applyForegroundState(selfForeground)  [NEW]
  → isSelfForeground = selfForeground
  → if now self-foreground: force dismissNow("self_app_foreground") + hideWakeRing()
    immediately, regardless of any pending timer or what JS thinks the state is
```

This is entirely native-to-native — no JS round trip, no JS timer. `updateStatus()` additionally
re-checks `isSelfForeground` itself (belt-and-suspenders against a request that races the
broadcast) and `showWakeRing()` now refuses to show at all while self-foreground ("use the in-app
listening UI only").

## WAKE BEHAVIOR

Unchanged detection/matching (per instruction, not touched). What changed: `showWakeRing()` (the
transient "I heard you" cue shown natively the instant a wake fires) now no-ops if BENSON's own
Activity happens to already be foreground at that moment, instead of always showing.

## CONFIRMATION

Unchanged from last round's mechanism: `CONFIRMING`'s label isn't `'GATA'`, so it's non-terminal
→ gets the long (~65s) safety-net timer, not the short one — stays visible until resolved,
cancelled, or that backstop fires. This round only changes what happens to the *idle bubble*
piece of the teardown when it eventually does dismiss (nothing, now — full removal).

## DRAGGING

Unchanged — the status card keeps its own drag handling and persisted position from
`ROUND_BUBBLE_GEMINI_BEHAVIOR_1`. Still draggable exactly while visible; never persists as a
draggable idle element since it no longer exists between sessions.

## FILES CHANGED

| File | Change |
|---|---|
| `modules/benson-accessibility/android/.../BensonAccessibilityService.kt` | `pushForegroundPackageToBubble()` — new native broadcast on the existing `TYPE_WINDOW_STATE_CHANGED` stream, called alongside the existing IME broadcast |
| `modules/benson-overlay/android/.../BensonBubbleService.kt` | `applyForegroundState()`, `isSelfForeground` gate in `updateStatus()`/`showWakeRing()`, `onStartCommand`'s default branch no longer auto-creates a bubble, `dismissNow()` fully tears down instead of restoring |

No JS changes were needed — the policy is entirely native, matching "must not depend only on JS
timers."

## LOGGING

`OVERLAY_POLICY_EVAL` (every foreground-package transition), `OVERLAY_SHOW_ACTIVE` (overlay
created for an active state), `OVERLAY_HIDE_SELF_APP`, `OVERLAY_HIDE_DONE` (terminal dismiss),
`OVERLAY_HIDE_TIMEOUT` (non-terminal safety-net dismiss), `OVERLAY_HIDE_IDLE` (explicit hide /
no-longer-showable request) — all in `BensonBubbleService.kt`, plus the full set from the prior
two rounds (`BUBBLE_STATUS_SHOW/HIDE`, `BUBBLE_DRAG_*`, `UI_STATE_*`, etc.), unchanged.

## BUILD / INSTALL

```
:app:assembleRelease → BUILD SUCCESSFUL (1m 1s) — benson-accessibility:compileReleaseKotlin and
                        app:compileReleaseKotlin both ran clean
adb install -r        → Success (9c1464eb)
```
No `npx tsc` run this round — no JS/TypeScript files were changed.

## DEVICE TESTING

**NOT_RUN.** While preparing to test the previous round live, the device showed a genuine,
unprompted WhatsApp conversation in the foreground; you confirmed you were not using WhatsApp
yourself, meaning something opened it without real user intent (checked: it was **not** BENSON —
the `ActivityTaskManager` log lines around that launch carry WhatsApp's own `uid`, with no
correlating BENSON log line; most likely an incoming-message notification or chat-head). You then
interrupted with this round's instructions before that could be investigated further, and this
round's implementation took the rest of the turn. TEST 1-5 from this round's acceptance list are
therefore honestly `NOT_RUN` — the build is installed and ready.

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| No overlay while BENSON foreground (Case 1) | IMPLEMENTED, native, event-driven — **NOT_RUN** on device |
| No persistent idle bubble (Case 2) | IMPLEMENTED (idle bubble creation removed from boot path) — **NOT_RUN** |
| Transient overlay during active states only (Case 3) | IMPLEMENTED — **NOT_RUN** |
| Full disappearance on DONE/ERROR/CANCEL/TIMEOUT, no idle restore (Case 4) | IMPLEMENTED — **NOT_RUN** |
| Self-app suppression is native/background-safe | IMPLEMENTED (Accessibility-event-driven, no JS involved) — **NOT_RUN** |
| Wake overlay suppressed when BENSON already foreground | IMPLEMENTED — **NOT_RUN** |
| Confirmation exception preserved | UNCHANGED from prior round, reasoning re-verified by inspection — **NOT_RUN** |
| Dragging preserved while visible | UNCHANGED from prior round — **NOT_RUN** |
| TEST 1 / 2 / 3 / 4 / 5 | **NOT_RUN** |

**Next step**: tell me when the phone is genuinely free (and confirm nothing else is mid-use) and
I'll run the five tests, or you can run them and describe what you see.
