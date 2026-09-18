# ROUND_EMERGENCY_CORE_1_REPORT

Smallest reliable emergency subsystem. VOICE → EmergencyIntentClassifier → confirmation policy →
native emergency route → observe outcome. Deliberately **not** on the WhatsApp / app-governance
path and **not** WhatsApp governance work.

---

## IMPLEMENTED

### New — `modules/benson-app-registry/android/.../EmergencyIntentRouter.kt` (~150 lines)
Pure Kotlin `object`, no state. Three responsibilities:

1. **`classify(text): EmergencyIntent`** — `NONE | EXPLICIT_112 | GENERIC_HELP`. Closed set, input
   lower-cased, optional leading `benson[,\s]+`.
   - `EXPLICIT_112`: `sună la 112` / `sună 112` / `sunați la 112` / `cheamă (o) ambulanța|salvarea|
     poliția|pompierii` / `cheamă la 112` / `apelează (la) 112`. Matched as a whole token
     (`\b`-anchored) anywhere in the utterance.
   - `GENERIC_HELP`: the **entire** utterance is `ajutor` / `urgență` / `help` (± leading `o `,
     trailing `!?.…`). Full-string match (`.matches()`) — a longer phrase like "ajutor cu
     calendarul" does **not** match and still reaches the normal HELP parser.
2. **`route(context): RouteResult`** — `mode = DIRECT_CALL | SYSTEM_DIALER | FAILED`.
   `EMERGENCY_DIAL_START` → snapshot (non-blocking, best-effort) → check `CALL_PHONE`
   (`EMERGENCY_DIRECT_CALL_ALLOWED value=…`) → if granted: `Intent.ACTION_CALL` `tel:112`
   (`FLAG_ACTIVITY_NEW_TASK`) → `EMERGENCY_SYSTEM_DIALER_OPENED mode=direct_call`. On any exception,
   or if `CALL_PHONE` not granted: `Intent.ACTION_DIAL` `tel:112` (pre-filled, one manual tap) →
   `EMERGENCY_SYSTEM_DIALER_OPENED mode=dial_prefilled`. Total failure → `EMERGENCY_ROUTE_FAIL
   reason=…`. **No `wa.me`, no `setPackage("com.whatsapp*")`, no Accessibility, no third-party
   dialer** — plain `tel:` to the platform telecom stack.
3. **`snapshotContext(context): EmergencyContext`** — `timestamp`, `batteryPct`
   (`BATTERY_PROPERTY_CAPACITY`, `-1` if unknown), `network` (`wifi|cellular|other|none|unknown`
   from `ConnectivityManager.getNetworkCapabilities(activeNetwork)`), `locationAvailable` (true iff
   `ACCESS_FINE`/`COARSE_LOCATION` already held **and** a `getLastKnownLocation` fix exists across
   active providers). Logs `EMERGENCY_LOCATION_AVAILABLE value=… source=fine|coarse|none` and
   `EMERGENCY_CONTEXT ts=… batteryPct=… network=… locationAvailable=…`. **Never requests a
   permission, never starts location updates, never returns coordinates across the JS bridge, never
   uploads.** Called once per route; does not block or gate the dial.

### `modules/benson-app-registry/android/.../BensonAppRegistryModule.kt` (+1 import, +~38 lines)
- `import expo.modules.kotlin.Promise`
- `Function("classifyEmergencyIntent") { text -> EmergencyIntentRouter.classify(text).name }`
- `Function("getEmergencyContext")` → `{ timestamp, batteryPct, network, locationAvailable }` (no
  coordinates)
- `AsyncFunction("routeEmergencyCall")` → `EmergencyIntentRouter.route(currentActivity ?:
  reactContext)` → `{ success, mode, reason }`

### `modules/benson-app-registry/index.js` (+18) · `index.d.ts` (+26)
`classifyEmergencyIntent(text)` (try/catch → `'NONE'`), `getEmergencyContext()` (try/catch →
`null`), `routeEmergencyCall()`. Types: `EmergencyIntentKind`, `EmergencyContextSnapshot`,
`EmergencyRouteResult`.

### `app/index.tsx` (+1 import, +~105 lines, 1 gate edit)
- `import { classifyEmergencyIntent, getEmergencyContext, routeEmergencyCall } from 'benson-app-registry';`
- `const EMERGENCY_GATE_ENABLED = true;` — **the revert switch** (rule 7): `false` disables the
  whole gate and restores `ajutor` → HELP parser.
- `pendingEmergencyConfirmRef` (bool) + `emergencyRepromptRef` (number) — a generic "ajutor" /
  "urgență" awaiting the spoken "Sun la 112?" answer.
- **`routeEmergencyNow()`** — reads/logs the context (`EMERGENCY_CONTEXT_JS`), calls
  `routeEmergencyCall()`, logs `EMERGENCY_ROUTE_RESULT success=… mode=… reason=…`, speaks the
  honest outcome (`"Sun la 112."` for DIRECT_CALL / `"Am deschis telefonul cu 112 pregătit. Apasă
  pe apel."` for the dialer / a failure line otherwise).
- **`handleEmergencyTurn(msg): 'handled' | 'pass'`**:
  - **pending generic-confirm branch** — an explicit "sună la 112" said now escalates straight
    through (`EMERGENCY_CONFIRMED via=explicit_escalation`); otherwise `classifyConfirmation`:
    YES → `EMERGENCY_CONFIRMED via=voice_yes` → route; NO → `EMERGENCY_ROUTE_FAIL
    reason=user_cancelled` + "Am anulat."; UNKNOWN → one reprompt (`EMERGENCY_CONFIRM_REQUIRED
    reason=reprompt`, "Sun la 112? Spune da sau nu."), then clean cancel
    (`reason=reprompt_exhausted`).
  - **fresh branch** — `EXPLICIT_112` → `EMERGENCY_INTENT_DETECTED kind=explicit` +
    `EMERGENCY_CONFIRMED auto=true kind=explicit` → route immediately (no gate, per spec).
    `GENERIC_HELP` → `EMERGENCY_INTENT_DETECTED kind=generic` + `EMERGENCY_CONFIRM_REQUIRED
    prompt=sun_la_112`, arm the pending ref, speak **"Sun la 112?"**. `NONE` → `'pass'`.
- **Call site** — first statement inside the `handleIncomingText` `try` block, **before** the URL
  match and every pending-vignette / note / mission / `WaitingConfirmation` gate, and before the
  parser / brain / app-governance. `'handled'` → reset loading and `return`.
- **Empty-audio guard** — `pendingEmergencyConfirmRef.current` added to `gateOpen`, so a
  hallucinated "da" from near-silent audio while "Sun la 112?" is pending is dropped
  (`CONFIRM_REJECTED reason=empty_audio`), same protection every other gate already has.

### Confirmation policy (as specified)
| utterance | policy |
|---|---|
| `sună la 112`, `cheamă ambulanța/poliția/pompierii` | **route immediately**, no verbal confirm |
| `ajutor`, `urgență` (whole utterance) | verbal **"Sun la 112?"** → YES routes · NO cancels · UNKNOWN reprompts once then cancels |

### Logs
JS: `EMERGENCY_INTENT_DETECTED`, `EMERGENCY_CONFIRM_REQUIRED`, `EMERGENCY_CONFIRMED`,
`EMERGENCY_CONFIRM_CLASSIFY`, `EMERGENCY_CONTEXT_JS`, `EMERGENCY_ROUTE_RESULT`,
`EMERGENCY_ROUTE_FAIL`.
Native: `EMERGENCY_DIAL_START`, `EMERGENCY_DIRECT_CALL_ALLOWED`, `EMERGENCY_LOCATION_AVAILABLE`,
`EMERGENCY_CONTEXT`, `EMERGENCY_SYSTEM_DIALER_OPENED`, `EMERGENCY_ROUTE_FAIL`.
All under tag `BENSON_AUDIO`.

### "keep BENSON foreground/wake runtime alive"
Nothing changed here — the existing `BensonForegroundService` (`START_STICKY`) + wake loop + the
`WA-LIFECYCLE` self-heal keep running when the dialer takes the foreground. The emergency route
adds no teardown of BENSON's own runtime.

---

## BUILD

- `npx tsc --noEmit` → **0 errors**.
- `:benson-app-registry:compileReleaseKotlin` → **BUILD SUCCESSFUL** (`EmergencyIntentRouter.class`
  + `$EmergencyIntent`, `$RouteResult`, `$EmergencyContext` emitted).
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 45s**. APK `260,965,608 B`, signed
  `CN=BENSON, OU=Dev, O=TOKKO` (O=TOKKO ✓).
- `adb install -r` → **Success** on `9c1464eb`, data preserved (`firstInstallTime` unchanged,
  `lastUpdateTime 2026-09-10 11:16:20`), accessibility service still bound.

Inert device pre-checks (no call placed, no dialer opened):
- `com.benson.butler` permission state: `CALL_PHONE granted=true`, `ACCESS_FINE_LOCATION
  granted=true`, `ACCESS_COARSE_LOCATION granted=true` → the router will take the **DIRECT_CALL**
  (`ACTION_CALL`) path, and the context snapshot will attempt last-known location.
- `cmd package resolve-activity -a android.intent.action.DIAL -d tel:112` → resolves directly to
  `com.google.android.dialer` (`isDefault=true`), no `ResolverActivity` — the `SYSTEM_DIALER`
  fallback is chooser-free on this device.

---

## DEVICE TEST

**E-1 "Benson, sună la 112" — NOT_RUN.**
**E-2 "Benson, ajutor" — NOT_RUN.**

Both are voice-gated (no mic injection from this harness), and E-1 by design reaches a real 112
route — the round says stop before connecting to emergency services, so it must be run by hand,
carefully, on the device. **Not marked PASS.**

### To run E-1 (you, at the phone — be ready to cancel before it connects)
1. `adb logcat -c` then `adb logcat -v time BENSON_AUDIO:I ReactNativeJS:I *:S`.
2. Say **"Benson, sună la 112"**.
3. **PASS** = this chain, and BENSON never used WhatsApp / a chooser / Accessibility typing:
   ```
   EMERGENCY_INTENT_DETECTED kind=explicit text="…112"
   EMERGENCY_CONFIRMED auto=true kind=explicit
   EMERGENCY_DIAL_START number=112
   EMERGENCY_LOCATION_AVAILABLE value=… source=fine
   EMERGENCY_CONTEXT ts=… batteryPct=… network=… locationAvailable=…
   EMERGENCY_DIRECT_CALL_ALLOWED value=true
   EMERGENCY_SYSTEM_DIALER_OPENED mode=direct_call number=112
   EMERGENCY_ROUTE_RESULT success=true mode=DIRECT_CALL reason=-
   ```
   The native phone call screen for 112 appears. **End it immediately — do not let it connect.**
   (If `CALL_PHONE` were denied you would instead see `EMERGENCY_DIRECT_CALL_ALLOWED value=false`
   → `…mode=dial_prefilled` and the dialer pre-filled with 112, requiring a manual tap.)

### To run E-2 (you, at the phone)
1. Same capture.
2. Say **"Benson, ajutor"**. Expect:
   ```
   EMERGENCY_INTENT_DETECTED kind=generic
   EMERGENCY_CONFIRM_REQUIRED prompt=sun_la_112
   ```
   BENSON speaks **"Sun la 112?"**.
3. Say **"nu"** → `EMERGENCY_ROUTE_FAIL reason=user_cancelled`, "Am anulat.", nothing dialed =
   **PASS**. (Say "da" to also exercise the route — then stop before it connects. Say something
   unrelated to exercise the single reprompt.)

---

## Proven-behaviour impact (regression rules §1)

| proven behaviour | affected? | why |
|---|---|---|
| Apel WhatsApp cap-coadă | **no** | `classifyEmergencyIntent("sună pe Baby pe WhatsApp")` → `NONE` (`sună pe` ≠ `sună la 112` / `sună 112`; not a `cheamă …` service). Gate returns `'pass'`, flow unchanged. |
| Deschidere aplicație după nume / Navigație Waze / conversație liberă | **no** | none match the closed emergency set; `'pass'`. |
| Microfon auto-repair după acțiune, revenire fără medalion | **no** | no change to mic-hold / wake / auto-return; foreground service untouched. |
| Confirmation Gate (mission) | **no** | emergency gate runs first and only consumes the turn when it is itself an emergency intent or the reply to its own pending "Sun la 112?"; otherwise it does not touch `pendingMissionTaskRef` / governed missions. |
| **`ajutor` / `urgență` as a bare utterance** | **YES — intended** | previously → HELP (capabilities answer). Now → emergency generic ("Sun la 112?", NO cancels). This is the round's explicit spec. Longer phrases ("cum mă ajuți", "ajutor cu …") still go to HELP (full-string match). Revert: `EMERGENCY_GATE_ENABLED = false`. |

Not a real emergency at any point during this round — no call placed, no dialer opened; only inert
`resolve-activity` / `dumpsys` reads.

## Confirm
- source modified: 5 files (1 new Kotlin, 1 Kotlin module, 2 JS bridge, `app/index.tsx`); no new
  Expo module; no WhatsApp-governance code touched
- one type of change: **add** (new emergency capability) — nothing removed/rewritten
- revert constant: `EMERGENCY_GATE_ENABLED` (`app/index.tsx`)
- tsc: PASS · release build: PASS (`O=TOKKO`) · installed: YES (data preserved)
- git / prebuild / setx: NO
- device acceptance (E-1, E-2): **NOT_RUN** — voice-gated + real-112 safety; hand-off above
