# ROUND_BENSON_BUBBLE_IME_1 — draggable, IME-aware BENSON bubble

Smallest change in the existing overlay (`BensonBubbleService`), no overlay-architecture redesign.

---

## IMPLEMENTED

### `modules/benson-overlay/.../BensonBubbleService.kt`

**1. Manual drag — persist + clamp**
- `ACTION_DOWN`: records the start; on the first real move (>12px) logs `BUBBLE_DRAG_START x= y=`.
- `ACTION_MOVE`: `lp.x/lp.y` updated then **`clampToSafeBounds(lp)`** every frame — the bubble
  can no longer be dragged off-screen, under the status bar, or into the navigation area.
- `ACTION_UP` (moved): **persists** `lp.x/lp.y` to `SharedPreferences("benson_bubble_prefs")`
  → `BUBBLE_POSITION_SAVED x= y= imeActive=`. This is the **MANUAL_POSITION**.
- `addBubble()`: restores the saved MANUAL_POSITION (falls back to the historic 100 dp default),
  then clamps it (screen may have rotated / value may be stale). Tap behaviour unchanged
  (`onBubbleTapped` fires only when `!moved`).

**2. IME-aware auto reposition — `applyImeVisibility(true)`**
- `BUBBLE_IME_VISIBLE currentY=`; if the bubble is below the safe-top line (i.e. it could be
  behind the keyboard), it hops to `safeTopYValue()` (as high as the safe bounds allow) →
  `BUBBLE_MOVE_TO_SAFE_TOP y=`. If the user already parked it up top, it is left alone.
- **The saved MANUAL_POSITION is not touched** — this is the transient **TEMPORARY_IME_POSITION**,
  never persisted.

**3. Restore — `applyImeVisibility(false)`**
- `BUBBLE_IME_HIDDEN`; `lp.x/lp.y` set back to the persisted MANUAL_POSITION (or the 100 dp
  default), clamped → `BUBBLE_POSITION_RESTORED x= y=`.
- A deliberate drag **while the keyboard is up** still updates the MANUAL_POSITION (explicit
  override); the restore then targets that new position.

**4. Safe-bounds math — no fixed device coordinates**
- `systemBarInsets()` — `WindowManager.currentWindowMetrics.windowInsets.getInsets(systemBars())`
  on API 30+, a 24 dp/48 dp density fallback below.
- `clampToSafeBounds`: `x` (offset from horizontal centre) kept within `±((screenW − size)/2 −
  6dp)`; `y` (bottom-edge offset, `Gravity.BOTTOM|CENTER_HORIZONTAL`) kept within
  `[navInset + 6dp , screenH − statusInset − size − 6dp]`. Never covers the status bar, never
  the nav area, always fully on-screen.

**Behaviour state**: `imeActive: Boolean` (MANUAL_POSITION when false / TEMPORARY_IME_POSITION
when true); MANUAL_POSITION lives in `benson_bubble_prefs` (`bubble_x`, `bubble_y`,
`bubble_has_pos`).

### `modules/benson-accessibility/.../BensonAccessibilityService.kt` (IME signal source)
The bubble runs in a service with no cross-app window visibility; the accessibility window list
is the only reliable IME signal (per the round's "use `AppEnvironmentSnapshot.imePresent`").
- New `maybePushImeStateToBubble()` — on each `TYPE_WINDOW_STATE_CHANGED`, one cheap
  `windows.any { it.type == TYPE_INPUT_METHOD }` check; on an actual **visible↔hidden transition**
  it `startService`s the overlay via a **`ComponentName` string**
  (`expo.modules.overlay.BensonBubbleService` / `ACTION_IME_VISIBILITY` / `ime_visible`) — **no
  compile dependency added between the two modules, no dependency cycle.**
- No other behaviour touched.

No JS bridge / `index.js` / `index.d.ts` change — this is entirely native bubble behaviour.

### Logs (tag `BENSON_AUDIO`)
`BUBBLE_DRAG_START` · `BUBBLE_POSITION_SAVED` · `BUBBLE_IME_VISIBLE` · `BUBBLE_MOVE_TO_SAFE_TOP` ·
`BUBBLE_IME_HIDDEN` · `BUBBLE_POSITION_RESTORED`

---

## BUILD

- `:benson-overlay:compileReleaseKotlin` + `:benson-accessibility:compileReleaseKotlin` →
  **BUILD SUCCESSFUL** (only pre-existing deprecation warnings).
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 39s**, signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓).
- `adb install -r` → **Success** on `9c1464eb`, data preserved (`lastUpdateTime 2026-09-10
  12:40:44`, `firstInstallTime` unchanged).
- `npx tsc --noEmit` → 0 errors (no TS change; run for completeness).

## DEVICE TEST — NOT_RUN

Requires a physical drag + a real keyboard, at the phone. Not marked PASS.

`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I *:S`, bubble visible over another app:

- **A.** Drag the bubble into the lower half → `BUBBLE_DRAG_START` then `BUBBLE_POSITION_SAVED
  x= y=`. Hide/show BENSON — the bubble comes back at that spot (persisted).
- **B.** Open any text field so the software keyboard appears → **PASS** =
  `BUBBLE_IME_VISIBLE` → `BUBBLE_MOVE_TO_SAFE_TOP y=<near top>` and the bubble is visibly above
  the keyboard, not covering the status bar.
- **C.** Dismiss the keyboard → **PASS** = `BUBBLE_IME_HIDDEN` → `BUBBLE_POSITION_RESTORED x= y=`
  with the same x/y saved in A; the bubble is back where the user left it.
- Bounds: drag toward each screen edge / the status bar / the nav bar — the bubble stops at a
  safe margin, never clipped.

(If the accessibility service is disabled, drag+persist+clamp still work; only the automatic
IME hop is unavailable — it needs the window signal.)

---

## Confirm
- overlay architecture: not redesigned — same `WindowManager` overlay, same `TYPE_APPLICATION_OVERLAY`
  window, same touch model; added persistence + clamp + one `ACTION_IME_VISIBILITY` handler
- files: `BensonBubbleService.kt` (bubble behaviour), `BensonAccessibilityService.kt` (+1 call,
  +1 helper for the IME signal, by ComponentName string — no module dep)
- one type of change: add (drag persistence, clamping, IME reposition)
- no fixed device coordinates; status bar / nav area never covered; user position never
  permanently reset by the auto-move
- tsc: PASS · release build: PASS (`O=TOKKO`) · installed: YES (data preserved) · device test:
  **NOT_RUN**
- git / prebuild / setx: NO
