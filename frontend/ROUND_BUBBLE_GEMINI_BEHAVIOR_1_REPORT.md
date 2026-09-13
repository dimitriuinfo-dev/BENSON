# ROUND_BUBBLE_GEMINI_BEHAVIOR_1_REPORT

Implemented. **Superseded in part by `ROUND_BUBBLE_STATE_DESYNC_FIX_1`** (same session, immediately
after real-device evidence showed this round's JS-timer-based auto-dismiss was itself vulnerable
to the exact JS-suspension-while-backgrounded problem documented elsewhere in this project) — the
auto-dismiss **timing mechanism** described here (§5) was replaced by a native-owned one before
ever being device-tested; everything else in this report (drag, sizing, mutual exclusivity) stands
as implemented. See the other report for the full final architecture.

No real-device acceptance testing was performed this round — see `ROUND_BUBBLE_STATE_DESYNC_FIX_1_REPORT.md`
for why, and for the results that do exist.

## 1. Exact files changed

- `modules/benson-overlay/android/.../BensonBubbleService.kt` — sizing, drag, mutual exclusivity, status-position persistence.
- `modules/benson-overlay/android/.../BensonOverlayModule.kt` — `updateBubbleStatus` signature.
- `modules/benson-overlay/index.js`, `index.d.ts` — JS wrapper signature.
- `app/index.tsx` — `pushBubbleBand`/`hideBubbleBandNow` call sites.

## 2. Exact root cause of non-draggable written bubble

`updateStatus()`'s status card `WindowManager.LayoutParams` carried
`FLAG_NOT_TOUCHABLE` — this flag makes Android pass every touch event straight through to
whatever is behind the view; no `OnTouchListener` on that view could ever receive an event no
matter what it did. The idle bubble never had this flag and already had full, correct drag
handling (ACTION_DOWN/MOVE/UP with a movement threshold distinguishing tap from drag) — that
existing pattern was reused verbatim for the status card, just with the flag removed and its own
`OnTouchListener` added.

## 3. State-machine changes

None to the JS state machine itself (`setBensonState`/`pushBubbleBand` in `app/index.tsx`) — per
instruction, it was reused, not rewritten. `pushBubbleBand` now also computes `terminal = label
=== 'GATA'` and a `turnId = Date.now()`, passed through to native (superseded design, see the
other report for why native needed these).

## 4. Size change

Idle bubble: `64dp → 44dp` diameter (`IDLE_BUBBLE_SIZE_DP` constant, `BensonBubbleService.kt`).
Same `BubbleDotsView` (dots), same stroke color, same identity — only the container diameter
changed. Revert: `IDLE_BUBBLE_SIZE_DP = 64`.

## 5. Auto-dismiss timing

**As originally implemented this round**: JS-side `E3_BAND_LINGER_MS` constant lowered
`6000 → 2500` (governing a JS `setTimeout` that called `hideBubbleBandNow()`).

**Superseded before device testing** — see `ROUND_BUBBLE_STATE_DESYNC_FIX_1_REPORT.md`: this
JS timer has the same class of vulnerability as every other JS `setTimeout` in this app (frozen
while backgrounded, per `ROUND_WAKE_STATE_BUG_1`'s own finding) and was replaced with a native
`Handler.postDelayed` inside `BensonBubbleService.kt` itself (`TERMINAL_DISMISS_MS = 2500L`) as
the sole authority.

## 6. Device-test results

**NOT_RUN.** No device interaction (drag, wake-and-watch, timing observation) was performed this
round — implementation and build/install only. See the other report for the reason and for what
verification does exist.

## 7. PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Written bubble draggable (code) | IMPLEMENTED, not device-verified |
| Idle bubble size reduced | IMPLEMENTED, not device-verified |
| Mutual exclusivity (idle vs. expanded) | IMPLEMENTED, not device-verified |
| Auto-dismiss ~2-3s | IMPLEMENTED — via the superseding native mechanism, not this round's JS timer |
| BUBBLE-GEMINI-1 through 6 | **NOT_RUN** |
