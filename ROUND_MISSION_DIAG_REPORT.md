# ROUND_MISSION_DIAG_REPORT

Read-only diagnosis: why a spoken "Intră în YouTube și caută Take My Breath Away" produced a
WhatsApp **Baby** call-confirmation flow instead of a YouTube action, with Android Settings visible.

`source modified: NO` · `prebuild: NO` · `git: NO`

---

## VERDICT

**STALE_MISSION_REUSE**

A WhatsApp `placeCall` **governed mission for "Baby"** was left in state `WaitingConfirmation` in the
AsyncStorage-backed mission store (an earlier un-confirmed call attempt). It survived app/service
restarts via `hydrateActiveMission()` (which only discards a waiting mission once it is **older than
5 minutes**). While it was still live, `handleIncomingText` routed the next utterance to the
**governed-confirmation gate FIRST**: `getActiveMission()?.state === 'WaitingConfirmation'` was true,
the utterance did not classify as "da"/"nu" → `UNKNOWN` → `confirmReprompt('governed')` +
`return`. The new command never reached intent classification, mission planning, or any YouTube
route — BENSON simply re-asked the pending Baby confirmation.

Not `CLASSIFICATION_ERROR` (the utterance was never classified as an intent). Not
`INTENTIONAL_BY_CURRENT_LOGIC` in the "YouTube→Baby" sense — it is the confirmation gate's
designed precedence over-reaching: it treats *any* non-YES/NO reply as an unclear answer to the
pending question, not as a possible brand-new command.

## Exact user utterance seen by STT

The exact YouTube→Baby event is **not in this session's captured logcat** (the buffer had been
cleared; the incident predates or falls between captures). But the **same interception mechanism is
captured live** in `wafix2/d2.log` at 11:10–11:11, with a different unrelated utterance:

```
09-09 11:10:06  [MissionOrchestrator] rawText='suna pe Baby pe WhatsApp'
                mission planned mission_1788945006456_2  tasks=[PREPARE_MESSAGE]
                WA_CONTACT_INPUT stage=parser parsed_contactName="Baby" mode="voice_call"
                → mission → WaitingConfirmation  (never received "da")

09-09 11:10:16  CONFIRM_CLASSIFY  thread=mqt_v_js text="Muzica" result=UNKNOWN
09-09 11:10:16  CONFIRM_PENDING   thread=mqt_v_js type=governed present=true
09-09 11:10:16  CONFIRM_REPROMPT  thread=mqt_v_js type=governed pendingPreserved=true attempt=1
09-09 11:10:16  STATE            from=LISTENING to=CONFIRMING detail=confirm_reprompt
```

`text="Muzica"` is an unrelated user utterance (looking for the song) — it was **eaten by the
stale Baby confirmation** exactly as "Intră în YouTube…" was in the reported incident.

Later, once the stale mission had aged out / exhausted its re-prompts, the YouTube phrasing **did**
route (11:11:43, same log):
```
rawText='Benson intră în YouTube și caută Take My Breath Away, aia lui Berlin.'
  normalizedText='intră în youtube și caută Take My Breath Away, aia lui Berlin.'
  problemType=UNKNOWN inferredGoals=0
STATE from=THINKING to=EXECUTING detail=brain:open_app
rawText='deschide YouTube'  problemType=DEVICE_CONTROL_PROBLEM
  mission planned mission_1788945104772_4  tasks=[OPEN_APP]
```
i.e. with no stale mission in the way, the utterance goes to the brain → `open_app` → `OPEN_APP
YouTube` (it opens the app; the "și caută …" part is dropped — see YOUTUBE ROUTING).

- raw STT text (reported): "Intră în YouTube și caută Take My Breath Away" — analogue captured: `"Muzica"` and `"…intră în youtube și caută Take My Breath Away…"`
- normalized: `"intră în youtube și caută take my breath away…"`
- classified intent (at the gate): **none** — `classifyConfirmation` returned `UNKNOWN`; the mission planner / brain never saw it while the stale mission was active.
- entities / target app / target action: **not extracted** — routing was short-circuited.
- Did STT mishear "Baby"/"WhatsApp"? **No.** The captured YouTube utterance transcribed cleanly as YouTube. The Baby name came from the *earlier* `mission_1788945006456_2`, not from this utterance.

## Intent produced

For the intercepted utterance: **none**. The governed-confirmation gate consumed it as an
`UNKNOWN` confirmation reply and re-prompted.

## Mission selected

`mission_1788945006456_2` — `PREPARE_MESSAGE` / `mode="voice_call"` / `contactName="Baby"`, state
`WaitingConfirmation`. It was **reused**, not newly created, for the YouTube utterance.

## Why Baby was selected

Because it was the single mission returned by `getActiveMission()` and it was in
`WaitingConfirmation`. The confirmation gate does not bind to an actionType/target/utterance — it
acts on **whatever mission is currently pending confirmation**. "Baby" was carried in that
pre-existing mission's `request.params.contactName`; the new utterance contributed nothing.

## Why WhatsApp executor ran

For the reported incident the WhatsApp executor **did not actually run** — the flow stopped at
`confirmReprompt` (re-ask the question). It *would* run only if the user then said "da": the gate's
`YES` branch calls `confirmActiveMission()` → `execute(mission.request, {confirmed:true})` →
`runTool` → `whatsappTool.placeCall(request.params.contactName)` = `placeCall("Baby")`. So a
reflexive "da" to the unexpected re-prompt is what would place the Baby call.

## Confirmation state

- The pending mission lived in `src/core/mission/missionStore.ts` (`activeMission`, mirrored to
  `AsyncStorage` key `ACTIVE_MISSION_KEY`), state `WaitingConfirmation`.
- `handleIncomingText` (`app/index.tsx`) checks, **in order**, before any intent routing:
  1. `:3213` `gateOpen && classifyConfirmation(msg)==='YES'` (fast-path YES for pending mission task / governed)
  2. `:3249` vignette-confirm gate
  3. `:3290` note-confirm gate
  4. `:3342` `if (pendingMissionTaskRef.current)` — mission-task confirm gate
  5. **`:3468` `const governedMission = getActiveMission(); if (governedMission?.state === 'WaitingConfirmation') { … }`** ← the one that fired
- At `:3470-3505`: `classifyConfirmation(msg)` → `YES` (execute) / `NO` (cancel) / `UNKNOWN` →
  `if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) cancelActiveMission()` else
  `confirmReprompt('governed'); return;`.
- `classifyConfirmation` (`:158-164`): `NO` if `/\b(nu|no|nein|anuleaza|renunta|opreste|stop|cancel|…)\b/`; `YES` if `/\b(da|dap|yes|ok|sigur|exact|perfect|confirm\w*|…)\b/`; else `UNKNOWN`. "intra in youtube si cauta take my breath away" matches neither → `UNKNOWN`.
- `MAX_CONFIRM_REPROMPTS = 3` (`:166`). After 3 `UNKNOWN` utterances the governed mission is
  auto-cancelled (`:3496-3502`, `CONFIRM_CANCEL type=governed reason=reprompt_exhausted`).

## Why Settings remained visible

- `confirmReprompt` (`app/index.tsx:3170-3177`): logs `CONFIRM_REPROMPT`, bumps
  `confirmRepromptCountRef`, `setBensonState('CONFIRMING','confirm_reprompt')`, and speaks the
  re-prompt. It does **not** call `bringToForeground()` / `bringBensonToForeground()`. BENSON does
  not self-foreground on a confirmation re-prompt (E1 "no self-foreground" behaviour). The
  re-prompt is voice + floating-band only.
- The user was in **Android Settings** because, earlier in this test session, `am force-stop
  com.benson.butler` (used while resetting between WhatsApp tests) killed the accessibility-service
  binding; BENSON replied `"Serviciul de accesibilitate s-a oprit — reactivează-l în setări."`
  (`ACCESSIBILITY_DISCONNECTED_ERROR`) and the user opened Settings to re-enable it. Commands
  spoken from that Settings screen were answered voice-only, with the stale Baby mission still
  pending, so the visible screen stayed on Settings while BENSON re-asked about Baby.

## Exact code path

```
STT / typed text
  → app/index.tsx handleIncomingText(msg)                         app/index.tsx:3188
  → governed-confirmation gate                                    app/index.tsx:3468
      const governedMission = getActiveMission()                  src/core/mission/missionStore.ts:49
        (mission restored earlier by hydrateActiveMission)        src/core/mission/missionStore.ts:23-41
        (mission put into WaitingConfirmation by an earlier
         un-confirmed placeCall)                                  src/core/mission/missionExecutor.ts:135
      if (governedMission.state === 'WaitingConfirmation')        app/index.tsx:3469
      const verdict = classifyConfirmation(msg)  → UNKNOWN        app/index.tsx:158 / :3470
      confirmReprompt('governed'); return                        app/index.tsx:3504 → :3170
  ─ utterance never reaches: trySettingsVoiceCommand (:3519),
    fact storage (:3524), mission orchestrator / brain, or any
    YouTube route.
```

## Root cause

The governed-confirmation gate (`app/index.tsx:3468`) has **absolute precedence** over intent
routing and treats **every** non-YES/NO utterance as an unclear answer to the pending question
(`UNKNOWN → re-prompt`), rather than recognising it as a possible new, unrelated command. Combined
with a mission store that (a) leaves a `placeCall` mission in `WaitingConfirmation` whenever the
confirmation is never answered and (b) restores such a mission across restarts for up to 5 minutes
(`hydrateActiveMission`, `STALE_WAITING_MISSION_MS`), a leftover Baby confirmation silently
"captures" the user's next command. In this session the leftover mission originated from a
mis-clicked debug-panel `suna pe Baby` test (`mission_1788945006456_2`, 11:10:06) that never
received its "da"; the accessibility-service force-stop that put the user into Settings was also a
test-harness side effect.

## Is this a bug?

**YES.** Two compounding defects:
1. `handleIncomingText`'s confirmation gate does not distinguish "reply to my pending question"
   from "a brand-new command" — any non-YES/NO is `UNKNOWN` and re-prompts, swallowing the new
   command (and, on a reflexive "da", could execute the wrong action).
2. A `placeCall` mission is parked in `WaitingConfirmation` on every un-answered confirmation and
   kept restorable for 5 minutes; there is no binding of the pending confirmation to the utterance
   that will answer it, and no "this looks like a different command → drop the stale confirmation"
   check.

(Third, separate, latent gap: "intră în YouTube **și caută** X" has no in-app-search route —
`commandParser.ts` `OPEN_APP_PATTERN:249` matches "intră în YouTube" and the brain resolves it to
`open_app` (log 11:11:44 `detail=brain:open_app` → `deschide YouTube` → `OPEN_APP`); the "caută X"
clause is dropped. Not the cause of this incident, but it means even a correctly-routed YouTube
command would only open the app.)

## Recommended fix shape

Before the governed-confirmation gate treats an utterance as `UNKNOWN → re-prompt`, run the normal
intent classifier on it: if it parses as a concrete, unrelated command (a different tool/app/action
than the pending mission), **drop or defer the stale confirmation**, log why (e.g.
`CONFIRM_SUPERSEDED by=<intent>`), and route the new command — reserving the `UNKNOWN → re-prompt`
path for genuinely ambiguous replies ("hmm", "poftim?"). Independently, tighten the mission store so
a `placeCall` `WaitingConfirmation` is not restored across a cold start at all (only `WaitingUser`
"call in progress" missions need to survive), and shorten/eliminate the 5-minute restore window for
confirmations. No change to YES/NO semantics, to the Confirmation Gate's core requirement, or to the
WhatsApp/YouTube routes themselves.

---

## Confirm

- source modified: **NO**
- prebuild: **NO**
- git: **NO**
- device changes: read-only inspection only (`adb logcat -d`, `dumpsys`, `uiautomator dump`); no source, no settings written for this diagnosis
