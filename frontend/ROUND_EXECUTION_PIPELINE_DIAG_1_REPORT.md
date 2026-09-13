# ROUND_EXECUTION_PIPELINE_DIAG_1_REPORT

Diagnostic-only round: added the 9 requested `EXEC_TRACE_*` log points across the real pipeline
(instrumentation only, zero behavior change — verified by `npx tsc --noEmit`, 0 errors, and by
inspection: every new line is a `logAudioDiag(...)` call, nothing conditional on it). **The device
disconnected from `adb` partway through this round** (USB, not a code issue), so TEST 2 (WhatsApp)
and a fresh TEST 1 run with the new trace lines were not executed. TEST 1's failure stage is,
however, answered with **real, already-captured device logs** from this session's prior live test
of the identical command — reused here as evidence, not re-derived.

---

## 1. EXACT FAILURE STAGE

**For "deschide calculatorul": `COMMAND_DISPATCH` → `APP_RESOLUTION` sub-stage. The pipeline never
reaches `LAUNCH_ACTION` at all.**

Real device log (captured live this session, `9c1464eb`, command spoken via wake word):
```
MISSION_INPUT text="deschide calculatorul."
STATE from=LISTENING to=THINKING
ORCHESTRATOR_HANDOFF_REQUESTED text="deschide calculatorul."
ACK text="Deschid."
APP_MATCH intent=open query="calculatorul" matched="Rechner" package="com.oneplus.calculator" candidates=1 chosen="Rechner" asked=true
ORCHESTRATOR_HANDOFF_COMPLETED handled=true missionId=mission_...
STATE from=THINKING to=CONFIRMING detail=disambiguation
```
No `launchPackage`/`nativeLaunchApp` call ever happens for this command. `dumpsys activity
activities` immediately after confirmed **no trace of Calculator/Rechner ever launching**. This
directly satisfies the round's own rule — `handled=true` was never proof of anything opening, and
in this trace it demonstrably wasn't.

**Root cause, traced to the exact line**: `appLauncherExecutor.ts`'s `openResultFromMatch()` —
```ts
if (m.kind === 'single') {
  return needsDisambiguationResult(requestId, `Am găsit ${m.app.appName}. O deschid?`, {
    candidates: toCandidates([m.app]),
  });
}
```
"calculatorul" scored as a `'single'` match against `"Rechner"` (this device's German-locale
Calculator app name) — not `'exact'` (`matchApps()`'s exact threshold requires score ≥90 with a
≥6-point gap over the runner-up; string similarity between "calculatorul" and "Rechner" cannot
reach that on literal text alone, whatever mechanism actually found the match). A `'single'` match
always asks a yes/no question and **stops** — this is deliberate, existing behavior, not a
malfunction of the launch mechanism itself.

**Second, compounding bug found by continuing the trace**: even if the user answers "da" to that
question, `missionOrchestrator.ts`'s `matchDisambiguationPick()` (the function that's supposed to
interpret the reply) only recognizes an **ordinal** ("primul", "1", "a doua"...) or the candidate's
**own name** repeated back — never a plain affirmative. A bare "da" matches neither, falls through
("No usable pick — fall through and handle this utterance as a fresh command"), and the pending
disambiguation is silently discarded. **This means: for ANY app that resolves as `'single'` rather
than `'exact'`, saying "yes" to BENSON's own question does not work** — this is very likely the
generic, cross-app "nu deschide nimic" pattern the user is reporting, not something specific to
Calculator's cross-language name.

## 2. RELEVANT FILES/FUNCTIONS

| File | Function | Role in the failure |
|---|---|---|
| `src/executors/appLauncherExecutor.ts` | `openResultFromMatch()` | decides `'single'` → ask, never auto-open |
| `lib/appIndex.ts` | `matchApps()` | produces the `'single'` vs `'exact'` classification (untouched, not tuned per instruction) |
| `src/core/orchestrator/missionOrchestrator.ts` | `matchDisambiguationPick()` | the function that fails to recognize a plain "da" as an answer to a single-candidate proposal |
| `src/core/orchestrator/missionOrchestrator.ts` | `runMission()` (`pendingDisambiguation` handling, lines ~496-519) | where the unanswerable "da" is discarded |
| `src/executors/appLauncherExecutor.ts` | `launchAllowlisted()` | the ACTUAL launch+verify code — never reached for a `'single'` match; separately depends on Accessibility for foreground verification (see §6/§7) |
| `src/core/mission/tools/whatsappTool.ts` (protected — read only) | `openApp()` | the WhatsApp/Waze path: `launchPackage()` + `waitForBackground()` — a DIFFERENT verification signal than Accessibility-based `waitForPackageForeground()`, and no fuzzy-match/confirmation step at all |

## 3. REAL LOGS

Full log block reproduced above (§1) — captured live on `9c1464eb` earlier this session for the
exact command `"deschide calculatorul."`. This predates the `EXEC_TRACE_*` instrumentation added
this round (that instrumentation adds `EXEC_TRACE_TARGET`/`EXEC_TRACE_FAILURE` etc. at the same
points — a re-run would show, in addition to the lines above: `EXEC_TRACE_INPUT`,
`EXEC_TRACE_MISSION`, `EXEC_TRACE_PLAN tasks=["OPEN_APP"]`, `EXEC_TRACE_EXECUTOR
executor=generic:governAction`, `EXEC_TRACE_TARGET name="Rechner" package="com.oneplus.calculator"`,
`EXEC_TRACE_FAILURE stage=APP_RESOLUTION reason=SINGLE_MATCH_NEEDS_CONFIRMATION`). Not re-captured
with the new tags — device disconnected before a re-run (see §8).

## 4. CALCULATOR RESULT

**FAIL — confirmed, with real logs.** Wake detection, command capture, and command dispatch all
worked (per `ROUND_WAKE_COMMAND_HANDOFF_FIX_1`'s already-verified chain). The mission orchestrator
correctly identified an `OPEN_APP` intent and correctly resolved a real installed package
(`com.oneplus.calculator`, display name "Rechner") — but stopped at a confirmation question that
cannot currently be answered "yes" to. **Calculator never received a launch request.**

## 5. WHATSAPP/WAZE RESULT

**NOT_RUN.** The device disconnected from `adb` before this test could be attempted (this round's
own logging additions were only just installed... in fact not even that — see §8, the build was
never reinstalled this round). No fabricated result is given.

**Code-level expectation, not a result**: `toGovernedCall()` routes any `OPEN_APP` whose appName
contains "whatsapp" or "waze" (with `appCapability==='navigation'`) to `runGovernedTask()` →
`missionExecutor.ts` → `whatsappTool.openApp()`/`wazeTool.openApp()` — **bypassing `matchApps()`
and the confirmation-question path entirely.** If this hypothesis is right, "deschide WhatsApp"
should NOT hit the same stopping point Calculator did. This is exactly the "one app works,
another fails" shape the round's own diagnosis classification describes — but it is a hypothesis
from reading the code, not a device-confirmed result, and is reported as such.

## 6. ACCESSIBILITY / RUNTIME STATE

**NOT_RUN this round** — `adb shell dumpsys accessibility` / `settings get secure
enabled_accessibility_services` / `dumpsys activity services` were attempted after the device had
already disconnected; all returned `no devices/emulators found`. No stale/cached answer is
substituted.

**From earlier in this session** (same device, `9c1464eb`, different rounds, offered as context
only — not this round's evidence): `WAKE_HEALTH` lines repeatedly showed `a11y=UNBOUND` at various
points, and at least once `a11y=BOUND`. Accessibility binding on this device is evidently not
constant across the session — which matters directly here, since `appLauncherExecutor.ts`'s
`launchAllowlisted()`'s foreground verification (`waitForPackageForeground`/`getForegroundPackage`,
both backed by `benson-accessibility`) reports `unsupportedResult` (not success, not failure —
"can't verify") whenever Accessibility isn't bound at the moment of a real launch attempt. This is
a SEPARATE, real way a fully-successful `launchPackage()` call could still be reported to the user
as "couldn't confirm it opened" — orthogonal to the confirmation-question bug in §1, and worth
checking independently once the device reconnects.

## 7. MINIMAL JUSTIFIED FIX (not implemented this round)

Two independent, narrow fixes — neither is "tuning the confirmation threshold" (that decision,
`'exact'` vs `'single'` vs `'multiple'`, is untouched):

1. **Make `matchDisambiguationPick()` accept a plain affirmative for a single-candidate proposal.**
   When `pd.candidates.length === 1` and the reply is a bare "da"/"sigur"/"confirm"/"ok" (a small,
   already-precedented pattern — `classifyConfirmation()` elsewhere in this codebase already does
   exactly this kind of yes/no classification for other confirmation gates), treat it as picking
   that one candidate. This is the fix that would make `"Benson, deschide calculatorul"` +
   `"da"` actually open Calculator, and is very likely the fix for most other "nu deschide
   nimic" cases too, since it's a generic gap (any `'single'` match, any app).
2. **Verify Accessibility binding before trusting `launchAllowlisted()`'s foreground-mismatch
   path**, or at minimum make the "unverified" outcome distinguishable from the "confirmation
   never happened" outcome in the diagnostic logs (`EXEC_TRACE_FAILURE` added this round already
   does this — `reason=ACCESSIBILITY_NOT_ENABLED` vs `reason=SINGLE_MATCH_NEEDS_CONFIRMATION` vs
   `reason=FOREGROUND_MISMATCH` are now three distinct, greppable outcomes).

Neither is implemented this round, per instruction ("do not make broad code changes until the
exact failing stage is identified" — and TEST 2 / the Accessibility state are still unconfirmed
live, so "the exact failing stage" for the GENERIC "nu deschide nimic" complaint beyond Calculator
specifically is not yet fully closed out).

## 8. BUILD / INSTALL / TEST STATUS

```
npx tsc --noEmit → 0 errors (all EXEC_TRACE_* additions)
Android build   → NOT RUN this round (device disconnected before rebuild+install)
adb install     → NOT RUN
```

The code changes (7 files: `appLauncherExecutor.ts`, `missionOrchestrator.ts`) are committed to
the working tree, type-checked, but **not yet running on the device** — the currently-installed
build predates this round's `EXEC_TRACE_*` lines. TEST 1 and TEST 2 as literal device tests are
therefore:

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Exact failure stage identified (Calculator) | **DONE** — `APP_RESOLUTION`/confirmation-answer gap, backed by real prior-session logs |
| `EXEC_TRACE_*` instrumentation added | **DONE**, type-checked, not yet device-verified |
| TEST 1 — Calculator (with new trace tags) | **NOT_RUN** (device disconnected; the *stage* is answered from an earlier real trace, but not re-confirmed with the new tags) |
| TEST 2 — WhatsApp/Waze | **NOT_RUN** |
| Accessibility/runtime state check | **NOT_RUN** (device disconnected) |
| Diagnosis classification | Best-supported by current evidence: **planner/orchestrator confirmation-answer routing bug** (§1/§7 item 1), with a **separate, unconfirmed possible environment factor** (Accessibility binding, §6) — not yet distinguishable from "app resolver/provider-specific" until TEST 2 actually runs |

**Next step**: reconnect the device, rebuild (`:app:assembleRelease`), reinstall, then run TEST 1
and TEST 2 for real with the new `EXEC_TRACE_*` tags visible in `adb logcat`, and check
Accessibility binding at that exact moment. I did not fabricate any of those results here.
