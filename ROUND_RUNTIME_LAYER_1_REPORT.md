# ROUND_RUNTIME_LAYER_1_REPORT

BENSON Runtime Layer v1 — Decision **A** (extend the existing runtime; no new service) +
Decision 2 **YES** (generic transient classification by window TYPE; no third-party app hacks).

`tsc: PASS` · `gradlew assembleRelease: BUILD SUCCESSFUL in 48s` · APK 260,962,612 B signed
`CN=BENSON, OU=Dev, O=TOKKO` · installed on `9c1464eb` (`lastUpdateTime 2026-09-10 10:31:43`),
data preserved, accessibility service bound. Module recompiled:
`BensonAccessibilityService$AppEnvironmentSnapshot.class`, `…$InterruptionClass.class`,
`…$awaitTargetReacquired$1.class`.

---

## Files changed — `modules/benson-accessibility/.../BensonAccessibilityService.kt` ONLY

No new service. Reuses `BensonForegroundService` + Guardian/`START_STICKY`, `foregroundIsPackage()`,
`getWindows()` (`windows`), `lastForegroundPackage`, `awaitCondition()`, the persisted
watchdog/recovery infra — as instructed.

### 1. `AppEnvironmentSnapshot` (new nested data class)
```
foregroundPackage: String?              — top TYPE_APPLICATION window (by layer, excl. BENSON),
                                          else rootInActiveWindow pkg (excl. BENSON/systemui),
                                          else lastForegroundPackage
visiblePackages: List<String>           — pkg of every current accessibility window
systemOverlayPresent: Boolean           — any TYPE_SYSTEM window, or any window pkg containing "systemui"
imePresent: Boolean                     — any TYPE_INPUT_METHOD window
accessibilityOverlayPresent: Boolean    — any TYPE_ACCESSIBILITY_OVERLAY window (e.g. BENSON's bubble)
timestamp: Long
```
`observeEnvironment()` builds it from `windows` + `rootInActiveWindow` + `lastForegroundPackage`.

### 2. `InterruptionClass` (new nested enum): `EXPECTED` · `TEMPORARY_INTERRUPTION` · `BLOCKING`
`classifyInterruption(env, expectedPackage)`:
- `foregroundPackage == expectedPackage` → **EXPECTED**
- a **generic transient owner** on top — `foregroundPackage` null / BENSON itself / contains
  `"systemui"` / `systemOverlayPresent` / `imePresent` / `accessibilityOverlayPresent` →
  **TEMPORARY_INTERRUPTION**
- a different **real app** foreground, target no longer a visible window → **BLOCKING**
- (different real app but target still a live window underneath → TEMPORARY_INTERRUPTION)

No package name is special-cased. SystemUI is matched by window TYPE (`TYPE_SYSTEM`) and by the
generic substring `"systemui"`, not as a hardcoded exception; WhatsApp / YouTube / Drupe / etc. are
**not** referenced anywhere in this layer.

### 3. `awaitTargetReacquired(expectedPackage, budgetMs): Boolean` (new, suspend)
Poll 300 ms until `budgetMs`:
- `EXPECTED` → `TARGET_REACQUIRE_OK` → **true**
- `TEMPORARY_INTERRUPTION` → keep waiting (resets the blocking timer)
- `BLOCKING` → fail only after **`BLOCKING_GRACE_MS = 2500`** of *continuous* BLOCKING →
  `TARGET_REACQUIRE_TIMEOUT reason=blocking`
- overall budget exceeded → `TARGET_REACQUIRE_TIMEOUT reason=budget` → **false**

### 4. Routed existing WhatsApp waits through the layer (no execution-logic rewrite)
| site | before | after |
|---|---|---|
| `runWhatsAppOpenConversationCall` step 2 (post-deep-link foreground wait) | `awaitCondition(7000,200){ foregroundIsPackage(WA_PKG).first }` | `foregroundIsPackage(WA_PKG).first \|\| awaitTargetReacquired(WA_PKG, 8000L)` — a notification / shade / IME right after the deep link is now a `TEMPORARY_INTERRUPTION`, not `WHATSAPP_DEEPLINK_FAILED` |
| `watchWhatsAppCallLifecycle` (call-end detection) | `!callScreenPresent()` → `goneStreak++` immediately | `!callScreenPresent()` → `observeEnvironment()`; if `classifyInterruption(env, WA_PKG) == TEMPORARY_INTERRUPTION` → log `INTERRUPTION_CLASS class=TEMPORARY_INTERRUPTION detail=call_watch` and **`continue`** (do NOT count as call-ended). Only a genuine absence (no transient owner) advances `goneStreak`. |

`runWhatsAppCallNative` (the proven mission search route) — its initial package gate is **left
unchanged** to avoid touching the proven path; it already tolerates a brief blip via its own 6 s
loop. Everything else (contact resolution, provider handling, call-button logic, the
lifecycle/wake fix, mission logic, other apps) untouched.

### 5. Generic logs added
`ENV_OBSERVE fg=… sysOverlay=… ime=… a11yOverlay=… wins=…` ·
`INTERRUPTION_CLASS class=EXPECTED|TEMPORARY_INTERRUPTION|BLOCKING [detail=…]` ·
`TARGET_REACQUIRE_START expected=… budgetMs=…` ·
`TARGET_REACQUIRE_OK expected=… elapsedMs=…` ·
`TARGET_REACQUIRE_TIMEOUT expected=… reason=blocking|budget elapsedMs=…`

---

## No-duplicate-action guarantee

The layer only **gates a wait** — it never re-issues an action. `runWhatsAppOpenConversationCall`
still deep-links exactly once (step 1) and presses the call button exactly once (step 4). The
watcher change only *withholds* the `goneStreak` increment during a transient overlay; when the
overlay clears it resumes the same single watch. `finalizeWhatsAppCallEnded` remains
idempotent (persisted-state guarded, `ROUND_WA_LIFECYCLE_FIX_1`). So a SystemUI overlay mid-call
produces **no** extra deep link, **no** extra call tap, **no** extra `CALL_ENDED`.

---

## TEST MILESTONE — NOT_RUN (device state)

Intended: start a WhatsApp mission → trigger a SystemUI notification/overlay mid-execution → mission
survives, target reacquired, execution continues after verification, no duplicate action.

Could not execute: starting a WhatsApp mission needs the voice/production path (no mic injection
from this harness) or the Debug Panel — and on the attempt the device would not hold a testable
foreground: `com.facebook.katana/LoginActivity` was stuck foreground and repeatedly reclaimed it,
`benson://debug` and `am start -n com.benson.butler/.MainActivity` both landed on the launcher, not
BENSON (BENSON process alive, foreground service `isForeground=true`, accessibility bound — but the
Activity would not foreground). No spoken command possible.

### To run it (you, at the phone)
1. `adb logcat -c`, then `adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`.
2. Start a WhatsApp call mission (voice: "sună pe Baby pe WhatsApp" → "da").
3. **After `WA_DIRECT_CALL_VERIFY success=true`**, pull down the notification shade (or fire a
   notification), leave it ~5 s, close it.
4. **PASS** = during the shade:
   ```
   ENV_OBSERVE fg=com.whatsapp sysOverlay=true …           (or fg=com.android.systemui)
   INTERRUPTION_CLASS class=TEMPORARY_INTERRUPTION detail=call_watch
   ```
   and **NO** `WA_CALL_STATE state=CALL_ENDING` while the shade is up; after closing it,
   `WA_CALL_STATE state=CALL_ACTIVE` continues. Exactly one deep link, one call tap, one eventual
   `CALL_ENDED reason=watcher` on the real hang-up. No `TARGET_REACQUIRE_TIMEOUT reason=blocking`.
5. Also fine to test step-2 reacquire: fire a notification in the ~1 s after "da" (during the
   deep-link) — expect `TARGET_REACQUIRE_START` → `…_OK`, mission proceeds (no
   `WHATSAPP_DEEPLINK_FAILED`).

## Confirm
- source modified: 1 file (`BensonAccessibilityService.kt`); no new service; WhatsApp execution logic not rewritten (only two waits routed through the layer)
- third-party packages hardcoded as transient: **NO** (window TYPE + generic `"systemui"` substring only)
- tsc: PASS · release build: PASS · installed: YES (data preserved)
- git / prebuild: NO
- device milestone test: **NOT_RUN** — device would not hold a testable foreground; hand-off steps above
