# ROUND_UNEXPECTED_WHATSAPP_FOREGROUND_DIAG_1_REPORT

No UI driving was performed this round (per instruction) — this is a log/code audit only, using
data already captured earlier in this session plus read-only `adb` queries (`dumpsys package`,
no taps/launches). Diagnostic logging was added for future occurrences; **no execution behavior
was changed**.

---

## VERDICT: B — Android/external, NOT BENSON

**BENSON did not launch WhatsApp.** This is not "insufficient evidence" — it's a direct,
conclusive contradiction: BENSON's own process has a fixed, verifiable Android uid, and the
actual launch was attributed to a completely different uid, not BENSON's.

## THE EVIDENCE

From the real device (`9c1464eb`), read-only queries:

```
$ dumpsys package com.benson.butler | grep uid
uid=10555 gids=[] type=0 prot=signature

$ dumpsys package com.whatsapp | grep uid
uid=10319 gids=[] type=0 prot=signature

$ cmd package list packages -U | grep 10199
package:com.android.launcher uid:10199
```

The `ActivityTaskManager` log lines captured earlier this session, at the exact moment WhatsApp
became foreground:

```
09-12 10:07:50.934 START ... cmp=com.whatsapp/.Main ... mCallingUid=10199 (BAL_ALLOW_VISIBLE_WINDOW ...)
09-12 10:08:01.050 START ... cmp=com.whatsapp/.contact.ui.quickcontact.QuickContactActivity ... mCallingUid=10319
09-12 10:08:04.220 START ... cmp=com.whatsapp/.Main ... mCallingUid=10199
09-12 10:08:04.261 START ... cmp=com.whatsapp/.home.ui.HomeActivity ... mCallingUid=10319
09-12 10:08:05.561 START ... cmp=com.whatsapp/.Conversation ... mCallingUid=10319
```

`mCallingUid=10199` = **`com.android.launcher`** — the system home-screen launcher. `mCallingUid=10319`
= WhatsApp's own uid (its subsequent internal navigation — Main → HomeActivity → Conversation —
is WhatsApp routing itself once already open, completely normal). **Neither line carries
`mCallingUid=10555`** (BENSON). If BENSON's own code had called `launchPackage('com.whatsapp')`
(the only mechanism it has for this — see the audit below), Android would attribute that
`START` line to BENSON's uid, 10555, unconditionally — that is not optional or spoofable from
JS, it's how `ActivityTaskManager` records the real calling process. It didn't.

**Correlating BENSON's own logs for the same window (10:07:47-10:08:06)**: zero
`EXEC_TRACE_*`/`APP_MATCH`/`WA_*` mission-related log lines appear anywhere near this timestamp —
the only BENSON activity in that exact window was the native wake loop scanning ambient audio
(`WAKE_VAD_*`/`WAKE_NO_MATCH`, all unrelated phrases) and one unrelated hallucinated wake trigger
("o eroare, atâta la e de rin") that routed to the brain/chat path (`ORCHESTRATOR_HANDOFF_COMPLETED
handled=false`) — not a WhatsApp action of any kind. This is independent corroboration, not just
absence of evidence: `EXEC_TRACE_*` instrumentation (from `ROUND_EXECUTION_PIPELINE_DIAG_1`) is
wired at every mission/executor choke point precisely because a `com.whatsapp` launch initiated by
BENSON *always* passes through it — and it shows nothing.

**The `WA_SERVICE_LIFECYCLE event=destroyed/connected` lines observed around this time are
`BensonAccessibilityService`'s own bind/rebind lifecycle** (a known, separate, already-documented
instability class in this project), not `com.whatsapp` app lifecycle — a coincidental naming
overlap, not evidence of anything WhatsApp-related.

**What this doesn't prove**: I cannot identify the *specific* human or system trigger behind
`com.android.launcher`'s `START` call — a genuine notification tap, a home-screen widget, or (less
likely, given the timing doesn't line up with any raw tap/swipe I sent in that exact window) a
stray/delayed input event from earlier UI automation in this session. What the evidence does
conclusively establish is which process it was **not**: BENSON.

## CODE AUDIT — every path capable of foregrounding `com.whatsapp`

| Path | File | Mechanism | Reachable how |
|---|---|---|---|
| Generic app-open | `src/executors/appLauncherExecutor.ts` `launchAllowlisted()` | `launchPackage('com.whatsapp')` (PackageManager launch intent) | `OPEN_WHATSAPP` intent via the generic `governAction` dispatch (only when `toGovernedCall` below does NOT intercept it first) |
| Governed tool | `src/core/mission/tools/whatsappTool.ts` `openApp()` (**protected file, read-only this round**) | same underlying `launchPackage('com.whatsapp')` | `missionOrchestrator.ts`'s `toGovernedCall()` routes any `OPEN_APP` task whose name contains "whatsapp" here FIRST — this is the actual live path for "deschide WhatsApp" |
| Deep link | `src/executors/whatsappExecutor.ts` `openUrl()`/`WhatsAppExecutor` | `Linking.openURL('whatsapp://'...)` | Registered in `actionDispatcher.ts`'s `DEFAULT_EXECUTORS`, but its `OPEN_WHATSAPP` handling is shadowed by `AppLauncherExecutor` (listed first); its `OPEN_WHATSAPP_CONTACT`/`MESSAGE_CONTACT` handling is likewise shadowed for mission-originated commands by `toGovernedCall`'s `PREPARE_MESSAGE` interception. Live reachability for a command that bypasses the mission orchestrator entirely (the brain/LLM bridge) was not fully traced this round — flagged as a loose end, not a launch source for *this* incident (would still show BENSON's uid regardless) |
| Call-lifecycle recovery | `app/index.tsx` (`consumeCallEndedReturnPending`, `WA-CALL-STAYS-LIVE`) | `bringActivityToFront()` — targets **BENSON's own** `MainActivity`, never WhatsApp | Cannot foreground WhatsApp by construction |
| Stale mission/disambiguation resume | `missionOrchestrator.ts` `pendingDisambiguation`/`pendingMissionTaskRef` | only fires on the **next real user utterance**, never on a timer | The native mic-ownership watchdogs added this session (45s/65s) release mic ownership back to passive listening — they do **not** call `resumePendingTask`/re-execute any mission. Ruled out by inspection, not just absence in logs |
| Notification/PendingIntent | — | grepped for `PendingIntent`+`whatsapp` across the codebase | No BENSON-authored `PendingIntent` targets WhatsApp anywhere in the tree |

**Every one of these, without exception, funnels through `launchPackage()`/`openUriWithPackage()`/
`openUrl()` in `src/core/action-engine/androidActionExecutor.ts`** — confirmed by reading each
call site. That is the single, complete choke point for a BENSON-originated foreground request,
which is why the new logging (below) was added there rather than scattered across every executor.

## LOGGING ADDED

`APP_FOREGROUND_REQUEST` and (when the target is WhatsApp) `WHATSAPP_FOREGROUND_REQUEST`, both
`source=<caller> targetPackage=<pkg> reason=<mechanism> success=<bool>`, added in **one place**:
`androidActionExecutor.ts`'s `launchPackage()`, `openUriWithPackage()`, and `openUrl()` (backing
`openDeepLink`/`openFallbackUrl`/`dial`). This catches every future BENSON-originated launch,
**including calls made from the protected `whatsappTool.ts`**, without editing that file — it
already funnels through this same choke point.

`source` is a new optional parameter (default `'unknown'`, so untouched call sites — including
every call inside the protected `whatsappTool.ts`/`missionExecutor.ts`, which this round did not
edit — keep compiling and just log `source=unknown` honestly rather than a guessed value).
Updated with a real value at the two non-protected call sites reachable this round:
`AppLauncherExecutor:launchAllowlisted` and `WhatsAppExecutor`.

**Not implemented**: a `missionId` for the governed (`whatsappTool.ts`) path specifically — doing
that properly needs a parameter threaded through the protected `missionExecutor.ts`/`whatsappTool.ts`,
which this round did not touch (per the standing protected-files rule; no explicit permission was
given to edit them this round, and it wasn't necessary to reach a verdict). `WHATSAPP_FOREGROUND_REQUEST`
still fires either way — it just always logs `missionId=none` until that follow-up is done.

## FILES CHANGED

`src/core/action-engine/androidActionExecutor.ts` (new logging), `src/executors/appLauncherExecutor.ts`,
`src/executors/whatsappExecutor.ts` (both: pass a real `source` string). `npx tsc --noEmit` → 0 errors.
No native/Kotlin files touched, no build/install performed this round (JS-only change; not device-tested).

## ANSWER TO THE FOUR OPTIONS

- **A. BENSON explicitly launched WhatsApp** — ruled out. Would require `mCallingUid=10555`.
- **B. Android/notification/user interaction launched WhatsApp** — **best-supported by the
  evidence**: the caller was `com.android.launcher` (uid 10199), a system component, not BENSON.
- **C. Stale mission/recovery launched WhatsApp** — ruled out by code inspection (no timer-driven
  path exists that could call a launch function without a fresh user utterance) and by the
  complete absence of any correlating `EXEC_TRACE_*`/mission log line in BENSON's own log stream
  for that exact window.
- **D. Insufficient evidence** — not applicable to *whether it was BENSON*; only the more specific
  question of *which exact external trigger* (real notification tap vs. something else) remains
  open, and is lower-stakes than the safety question this round was actually about.

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Origin ruled IN/OUT for BENSON | **DONE** — ruled out, hard uid evidence |
| Full code-path audit | **DONE** |
| Required logging added | **DONE** (`androidActionExecutor.ts`, catches every path including the protected tool file) |
| Execution behavior changed | **NOT DONE** — correctly, per instruction |
| Device-verified (rebuilt/reinstalled/re-tested) | **NOT_RUN** this round — no build was performed; this was a log/code audit only, and no further UI driving was permitted |
