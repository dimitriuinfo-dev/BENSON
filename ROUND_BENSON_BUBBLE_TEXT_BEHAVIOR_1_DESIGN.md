# ROUND_BENSON_BUBBLE_TEXT_BEHAVIOR_1 — DESIGN

Anchored, transient text companion for the round BENSON bubble. Reuses `BensonBubbleService` +
the draggable round bubble + the IME infra from `ROUND_BENSON_BUBBLE_IME_1`. No overlay
architecture redesign.

**Not implemented this pass** — sequencing per your own STABILITY CONTRACT ("stabilize wake +
voice state machine first"): the test device has been offline for several rounds,
`BENSON_STABILIZATION_1` is built-but-not-installed and S1–S7 are NOT_RUN, and
`NATIVE_WAKE_PORCUPINE_1` is awaiting your architecture decision. This is the ready-to-build
design; it lands right after wake is verified on device.

---

## FILES_CHANGED (plan)
| file | change |
|---|---|
| `modules/benson-overlay/.../BensonBubbleService.kt` | text bubble (`statusView`) becomes **anchored** to the round bubble instead of a fixed-gravity offset: `layoutTextBubble()` computes side (ABOVE/BELOW/LEFT/RIGHT) + x/y from the round bubble's current `lp` and `systemBarInsets()`; called on drag-move, drag-end, IME show/hide, target-avoidance, and text update. New `ACTION_TEXT_STATE` (state + text + optional target bounds). Text-lifecycle timer (`textHideHandler`). ~180 lines, additive; the round-bubble touch/drag/clamp/IME code from IME_1 is reused, not rewritten. |
| `modules/benson-overlay/.../BensonOverlayModule.kt` | `Function("setBubbleText") { state, text, targetLeft, targetTop, targetRight, targetBottom }` → `ACTION_TEXT_STATE` intent. Keeps existing `updateBubbleStatus` as a thin wrapper (back-compat). |
| `modules/benson-overlay/index.js` / `index.d.ts` | export `setBubbleText(state, text, targetBounds?)`. |
| `app/index.tsx` | replace the ~6 `updateBubbleStatus(label, transcript, visible)` call sites with `setBubbleText(state, text, targetBounds?)` where `state ∈ VISIBLE_ACTIVE\|VISIBLE_WAITING_USER\|VISIBLE_ERROR\|HIDDEN`; pass the resolved Accessibility node bounds of a to-be-tapped control (from `getScreenSnapshot()`, already available) when BENSON is about to drive a tap. Cancel-pending-hide handled natively. |
| **NOT touched** | round-bubble drag/persist/clamp, IME detection (`ACTION_IME_VISIBILITY`), wake/audio state machine, WhatsApp, missions. |

---

## STATE_MODEL

**Text panel** (native, in `BensonBubbleService`):
```
HIDDEN ──setBubbleText(VISIBLE_*)──► VISIBLE_ACTIVE ─┐
                                     VISIBLE_WAITING_USER │  (no auto-hide)
                                     VISIBLE_ERROR        │  (no auto-hide until resolved/timeout)
        ┌────────────────────────────────────────────────┘
        │ final response / terminal mission state
        ▼
  HIDE_PENDING ──(READABLE_DELAY_MS 3500, cancellable)──► HIDDEN
        ▲                                                    │
        └────────── new setBubbleText(...) cancels timer ────┘   (D: same bubble updates, never stacks)
```
- `VISIBLE_ACTIVE` — BENSON response / current action status. Auto-hides after
  `READABLE_DELAY_MS` once no newer text arrives.
- `VISIBLE_WAITING_USER` — "Îl trimit?" etc. **Never auto-hides.** Cleared by the next
  `setBubbleText` (YES/NO/timeout/cancel all produce one).
- `VISIBLE_ERROR` — needs user attention. No auto-hide; cleared by resolution or an explicit
  `ERROR_TIMEOUT_MS` (e.g. 20 s) → HIDE_PENDING.
- `HIDE_PENDING` — `textHideHandler.postDelayed(hide, delay)`; any new `setBubbleText` calls
  `removeCallbacks` first (rule D).
- `HIDDEN` — panel removed via `windowManager.removeView`; **round bubble untouched** (rule 7).

**Anchor side**: `AnchorSide { ABOVE, BELOW, LEFT, RIGHT }`, recomputed by `layoutTextBubble()`.

**Position mode** (shared with IME_1): `MANUAL` / `TEMPORARY_IME` / `TEMPORARY_ACTION_AVOIDANCE`
— the last two never persist the round bubble's `benson_bubble_prefs` position.

---

## IME_BEHAVIOR (reuses ROUND_BENSON_BUBBLE_IME_1)
- `ACTION_IME_VISIBILITY true` (pushed by `BensonAccessibilityService` on a `TYPE_INPUT_METHOD`
  transition) → the existing `applyImeVisibility(true)` moves the **round bubble** to
  `safeTopYValue()` if it's low; then `layoutTextBubble()` re-anchors the text panel to the
  round bubble's new position. The group (round + text) stays attached and above the keyboard.
  `BUBBLE_TEXT_MOVE_AVOID_IME`. Saved `MANUAL` round-bubble position is **not** overwritten.
- `ACTION_IME_VISIBILITY false` → `applyImeVisibility(false)` restores the round bubble to the
  persisted `MANUAL` position; `layoutTextBubble()` re-anchors. `BUBBLE_TEXT_RESTORE_POSITION`.
- Clamp: `layoutTextBubble()` always calls the IME_1 `clampToSafeBounds()` logic for the text
  panel's own rect too — never covers status bar / nav area, never off-screen (`systemBarInsets()`,
  `WindowInsets`, no fixed coordinates).

---

## TARGET_AVOIDANCE (`TEMPORARY_ACTION_AVOIDANCE`)
- JS passes the **Accessibility node bounds** of the control BENSON is about to tap (it already
  reads `getScreenSnapshot()` → `nodes[].bounds` during every WhatsApp/Waze flow) via
  `setBubbleText(state, text, targetBounds)`.
- `layoutTextBubble()` after computing the preferred side, tests `Rect.intersects(textPanelRect,
  targetRect)`. On overlap it walks the side order `[preferred, opposite, LEFT, RIGHT]` and picks
  the first non-overlapping, on-screen placement; if none, it shifts the panel along that side
  away from the target's centre until clear or clamped. `BUBBLE_TEXT_MOVE_AVOID_TARGET
  side=<...> target=<l,t,r,b>`.
- No target bounds passed (or bounds empty) → normal anchored placement. When the action
  completes/cancels, JS calls `setBubbleText` without `targetBounds` → returns to normal.
- **No invented coordinates** — if the snapshot has no bounds for the target, avoidance is
  skipped (logged `BUBBLE_TEXT_MOVE_AVOID_TARGET skipped=no_bounds`), the panel just uses the
  safe anchored spot.

---

## TEXT_LIFECYCLE
| trigger | state | auto-hide |
|---|---|---|
| BENSON response (final, spoken/shown) | `VISIBLE_ACTIVE` | yes, `READABLE_DELAY_MS` (3.5 s) after the last update |
| clarification / confirmation prompt ("Îl trimit?") | `VISIBLE_WAITING_USER` | **no** — until YES / NO / timeout / cancel (each fires a new `setBubbleText`) |
| error requiring input | `VISIBLE_ERROR` | no — until resolved, or `ERROR_TIMEOUT_MS` |
| new interaction while HIDE_PENDING | cancel timer, update **same** panel | — |
| mission terminal (SUCCESS/CANCELLED/FAILED/SUPERSEDED/TIMEOUT) | brief `VISIBLE_ACTIVE` with the final line **or** immediate `HIDDEN` if no message | 3.5 s then HIDDEN |
| app relaunch / service restart | panel starts `HIDDEN`; a stale `ACTION_TEXT_STATE` is ignored if older than `STALE_TEXT_MS` (60 s) — **no yesterday's text** (rule E / BT-7) |

`BENSON_STABILIZATION_1` tie-in: the text shown is `lastUserTranscriptRef` / BENSON's reply —
both already gated so BENSON output can't be re-consumed; the panel is display-only and never
feeds input.

---

## ANIMATION
- move: `ValueAnimator` on `lp.x/lp.y`, 200 ms, `DecelerateInterpolator`, `updateViewLayout` per
  frame (same pattern the wake ring uses).
- show: alpha 0→1 + scale 0.9→1, 180 ms. hide: reverse, then `removeView`.
- All ≤ 250 ms, non-blocking (`ViewPropertyAnimator` / `ValueAnimator`, no `Thread.sleep`).

---

## LOGS (all tag `BENSON_AUDIO`)
`BUBBLE_TEXT_SHOW state=<...>` · `BUBBLE_TEXT_UPDATE` · `BUBBLE_TEXT_ANCHOR side=<ABOVE|BELOW|LEFT|RIGHT>` ·
`BUBBLE_TEXT_MOVE_AVOID_IME` · `BUBBLE_TEXT_MOVE_AVOID_TARGET side=<...> target=<...>` ·
`BUBBLE_TEXT_WAITING_USER` · `BUBBLE_TEXT_HIDE_SCHEDULED delayMs=<...>` · `BUBBLE_TEXT_HIDE reason=<auto|new|terminal|stale>` ·
`BUBBLE_TEXT_RESTORE_POSITION`

---

## BUILD / DEVICE_TEST
- **BUILD: not run** (not implemented this pass).
- **DEVICE_TEST BT-1…BT-7: NOT_RUN.** Each maps 1:1 to a log/behaviour above; runnable once built
  and the device is back.

## Why held
Per your STABILITY CONTRACT and `BENSON_STABILIZATION_1`: wake + voice state machine must be
proven on device (S1–S7) before overlay polish. Also `NATIVE_WAKE_PORCUPINE_1` needs your
architecture sign-off + an AccessKey. This design is complete and additive; say go (with the
device reconnected) and it builds in one pass.
