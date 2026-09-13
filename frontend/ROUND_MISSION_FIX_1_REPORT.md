# ROUND_MISSION_FIX_1_REPORT

MISSION-FIX-1 — safe mission supersession: a new valid user command outranks a still-unconfirmed
pending mission; a persisted `WaitingConfirmation` privileged action can never become re-confirmable
after a cold restart. Fixes `STALE_MISSION_REUSE` (`ROUND_MISSION_DIAG_REPORT.md`).

Device `9c1464eb` / OnePlus Nord 4 / OxygenOS 15.

---

## Status

| Item | Result |
|---|---|
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` | PASS — `BUILD SUCCESSFUL in 46s`, APK 260,945,440 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install `9c1464eb` (`adb install -r`) | PASS — `Success`, data preserved, accessibility service bound |
| **MISSION-ISO-1** (new command supersedes) | **CODE-VERIFIED · device NOT_RUN** (harness cannot drive `handleIncomingText` with a chosen phrase — see §Testing) |
| **MISSION-ISO-2** (`nu` cancels) | **CODE-VERIFIED · device NOT_RUN** (path unchanged by this round) |
| **MISSION-ISO-3** (`da` confirms) | **PASS (device)** — `da` → exact Baby mission confirmed → existing WhatsApp direct route → `CALL_VERIFIED` |
| **MISSION-ISO-4** (true ambiguity → reprompt) | **PASS (device, incidental)** — ambient `"Muzica de intro"` during Baby `WaitingConfirmation` → `CONFIRM_UNKNOWN_CHECK_NEW_COMMAND` → declined (no command) → `CONFIRM_REPROMPT reason=true_ambiguity pendingPreserved=true` |
| **MISSION-ISO-5** (cold-start drop) | **CODE-VERIFIED · device NOT_RUN** (device kept auto-locking / notification shade wedged) |
| **MISSION-ISO-6** (exact repro) | **CODE-VERIFIED · device NOT_RUN** (same as ISO-1) |
| unintended action executed | **NONE** — the only call placed was the intended ISO-3 `da` confirmation, `CALL_VERIFIED`, ended by the tester |

Per §13: ISO-1 / ISO-6 did **not fail** — they could not be executed from this harness (the RN
main-screen `TextInput` ignores synthetic `adb shell input` taps, so the live `handleIncomingText`
path can only be reached via voice; the device also repeatedly auto-locked with the notification
shade wedged during the session). ISO-4 exercised the **same new code** live and it behaved
correctly. `source not further modified after the ISO-3/ISO-4 run.`

---

## 1. Audit — the confirmation contract as it was

`app/index.tsx handleIncomingText(msg)` checks, in order, before any intent routing:

| # | line | gate | on UNKNOWN (pre-fix) |
|---|---|---|---|
| a | `:3210` | `viaVoice` empty-audio YES guard (`gateOpen && classifyConfirmation==='YES'`) | — |
| b | `:3248` | `pendingVignetteRef.current` | reprompt |
| c | `:3290` | `pendingNoteActionRef.current` | reprompt |
| d | `:3384` | `pendingMissionTaskRef.current` (multi-step task) | `confirmReprompt('mission'); return` |
| e | `:3515` | `getActiveMission()?.state === 'WaitingConfirmation'` (governed) | `confirmReprompt('governed'); return` |

- `classifyConfirmation` (`:158-164`) — `NO` (`/\b(nu\|no\|nein\|anuleaza\|…)\b/`) / `YES` (`/\b(da\|yes\|ok\|sigur\|confirm\w*\|…)\b/`) / else `UNKNOWN`.
- **Pending confirmation representation:** `getActiveMission()` (`src/core/mission/missionStore.ts` — `activeMission`, mirrored to AsyncStorage `ACTIVE_MISSION_KEY`), state `WaitingConfirmation`; plus JS refs `pendingMissionTaskRef` / `pendingNoteActionRef` / `pendingVignetteRef`.
- **Mission fields:** `id`, `request {tool, action, params, createdAt}`, `state`, `userMessage`, `createdAt`, `updatedAt` (`missionTypes.ts`). No stored source utterance.
- **Persistence:** `transitionMission` writes every state change to AsyncStorage; `hydrateActiveMission()` (cold start) restored a `WaitingUser`/`WaitingConfirmation` mission younger than **5 min**; `getActiveMission()` in-session did the same.
- **Binding:** `confirmActiveMission()` (`missionExecutor.ts:339`) acts on **whatever** mission `getActiveMission()` returns if its state is `WaitingConfirmation` — no `missionId` / actionType / target / utterance check.

**Root cause (confirmed in `ROUND_MISSION_DIAG_REPORT.md`):** an unanswered `WaitingConfirmation`
Baby `placeCall` mission, restored by `hydrateActiveMission`, made gate (e) fire for the next
utterance; `classifyConfirmation("Intră în YouTube…")` = `UNKNOWN` → `confirmReprompt('governed'); return`
— the YouTube command never reached routing.

## 2. Confirmation state-machine change

New shared step, run in the **UNKNOWN** branch of gate (d) and gate (e), **before** `confirmReprompt`:

```
UNKNOWN
  → supersedeStaleConfirmationIfNewCommand(msg)          app/index.tsx
      looksLikeNewCommand(msg):
        classifyConfirmation(msg) !== 'UNKNOWN'      → false   (never override a clear da/nu)
        < 2 words after normConfirm()                → false   ("hmm", "poate", "?")
        NEW_COMMAND_VERB_RE matches a leading/embedded imperative verb  → true
          (deschide|intra|pune|reda|cauta|gaseste|suna|apeleaza|navigheaza|opreste|
           inchide|arata|trimite|scrie|… | open|start|play|search|find|call|stop|close|…)
        else parseCommandToActionRequest(msg).intent ∉ {CHAT, HELP}     → true
      if true:
        CONFIRM_NEW_COMMAND_DETECTED oldMissionId=… newIntent=…
        pendingMissionTaskRef / pendingNoteActionRef / pendingVignetteRef → null
        confirmRepromptCountRef → 0 ; gateArmedAtRef → 0
        getActiveMission() in {WaitingConfirmation, WaitingUser}
          → supersedeActiveMission('new_user_command')   (→ state 'Superseded', clearIfTerminal → null)
        MISSION_SUPERSEDED missionId=… reason=new_user_command
        return true  → caller does NOT return; the SAME `msg` falls through to normal routing
  → else (genuinely unclear): confirmReprompt(type, 'true_ambiguity'); return   (mission preserved)
```

The bare `verdict === 'YES'` block in gate (d) is now guarded `if (verdict === 'YES' && pendingMissionTaskRef.current)` so a superseded UNKNOWN falls through it instead of resuming a now-null task. Gate (e)'s `else if (… 'WaitingUser')` is skipped because `getActiveMission()` is `null` after supersede.

**YES / NO semantics unchanged.** `da` → confirm only the bound mission (`confirmActiveMission`, untouched). `nu` → cancel only the bound mission (`cancelActiveMission`, untouched).

## 3. Supersession mechanism

- New terminal state **`'Superseded'`** (`missionTypes.ts`) — added to `clearIfTerminal`'s terminal
  list (`missionStore.ts`), so a superseded mission is cleared from memory **and** AsyncStorage.
- New `supersedeActiveMission(reason)` (`missionExecutor.ts`) — `transitionMission('Superseded',
  { reason: 'superseded: …' })` + `clearIfTerminal()`. A superseded mission can never be confirmed
  later: `confirmActiveMission` requires `state === 'WaitingConfirmation'` and `getActiveMission()`
  returns `null`.
- Every pending-confirmation representation is cleared in one place (`supersedeStaleConfirmationIfNewCommand`):
  `pendingMissionTaskRef`, `pendingNoteActionRef`, `pendingVignetteRef`, `confirmRepromptCountRef`,
  `gateArmedAtRef`, and the store mission. No stale authority survives in a ref, the store, or
  AsyncStorage.

## 4. Hydration change

`src/core/mission/missionStore.ts`:

- **`hydrateActiveMission()` — drops ANY `WaitingConfirmation` mission on cold start, regardless of
  age**, logging `MISSION_HYDRATE_DROP missionId=… reason=persisted_waiting_confirmation` and
  removing the AsyncStorage key. A confirmation dialogue has no meaning after a process restart,
  and a persisted re-confirmable privileged action (WhatsApp call / message send) is exactly the
  bug. `WaitingUser` (a call/nav already in progress, awaiting the user's "gata") is still restored,
  subject to the 5-minute staleness — that "call in progress" recovery is unaffected.
- **`getActiveMission()` in-session staleness split:** `WaitingConfirmation` →
  `STALE_WAITING_CONFIRMATION_MS = 90 s` (an unanswered confirmation is abandoned much sooner);
  `WaitingUser` → `STALE_WAITING_MISSION_MS = 5 min` (unchanged).

## 5. Explicit voice-confirmation behaviour (§8)

Already satisfied — `buildConfirmationPrompt` (`missionExecutor.ts:229`) for `placeCall` returns
`Deschid WhatsApp, caut "<name>", aleg primul rezultat și apăs apelul vocal. Confirmi?` — it names
the app, the action (voice call) and the target. The bare `'Confirmi?'` is only the last-resort
fallback for a tool/action combo not enumerated (unreachable for governed missions). No change made
here (out of scope: WhatsApp route wording). Invariant 5 holds: `da` cannot be given blind.

## 6. Files modified

| File | Change |
|---|---|
| `src/core/mission/missionTypes.ts` | `+ 'Superseded'` in `MissionState` |
| `src/core/mission/missionStore.ts` | `hydrateActiveMission` drops any `WaitingConfirmation` (`MISSION_HYDRATE_DROP`); `getActiveMission` staleness split (`STALE_WAITING_CONFIRMATION_MS = 90 s`); `clearIfTerminal` `+ 'Superseded'` |
| `src/core/mission/missionExecutor.ts` | `+ export async function supersedeActiveMission(reason)` |
| `app/index.tsx` | `+ NEW_COMMAND_VERB_RE`, `+ looksLikeNewCommand()`, `+ supersedeStaleConfirmationIfNewCommand()`; `confirmReprompt(type, reason='unclear_reply')` + `reason=` in the log; wired the supersede check into gate (d) and gate (e) UNKNOWN branches; guarded gate (d)'s YES block; `+ supersedeActiveMission` import |

Not touched: WhatsApp executor / direct-contact route, CALL lifecycle, wakeword, mic-hold, YouTube
executor, Accessibility selectors, Waze, STT, YES/NO classifier semantics. UI: only `confirmReprompt`'s
log string (a `reason=` field). No unrelated refactor.

## 7. Logging added

`CONFIRM_UNKNOWN_CHECK_NEW_COMMAND text="…"`, `CONFIRM_NEW_COMMAND_DETECTED oldMissionId=… newIntent=…`,
`MISSION_SUPERSEDED missionId=… reason=new_user_command`, `CONFIRM_REPROMPT … reason=true_ambiguity|unclear_reply`,
`MISSION_HYDRATE_DROP missionId=… reason=persisted_waiting_confirmation`.

## 8. Device evidence

### MISSION-ISO-3 — PASS
Debug Panel `suna pe Baby pe WhatsApp` → `[MissionExecutor] needs confirmation whatsapp placeCall`
(mission → `WaitingConfirmation`). Then `da`:
```
WA_CONTACT_INPUT stage=placeCall received="Baby" native="Baby"
WA_DIRECT_RESOLVE_RESULT status=resolved count=1
WA_DIRECT_CONVERSATION_VERIFY status=verified header="Baby" nameMatch=true
WA_DIRECT_CALL_VERIFY success=true screen=true name="Baby" nameMatch=true
WA_DIRECT_END success=true step=CALL_VERIFIED elapsedMs=6322
[MissionExecutor] tool result whatsapp placeCall { outcome: 'app_switch_observed', via: 'wa_direct_deeplink' }
```
`da` confirmed the exact bound Baby mission; the existing WhatsApp direct route executed and
verified. Call ended by the tester (`CALL_ENDING` → `CALL_ENDED`).

### MISSION-ISO-4 — PASS (incidental, live)
While the Baby mission sat `WaitingConfirmation`, a conversation-mode STT transcription
`"Muzica de intro"` reached `handleIncomingText`:
```
CONFIRM_CLASSIFY text="Muzica de intro" result=UNKNOWN
CONFIRM_PENDING type=governed present=true
CONFIRM_UNKNOWN_CHECK_NEW_COMMAND text="Muzica de intro"          ← new
CONFIRM_REPROMPT type=governed reason=true_ambiguity pendingPreserved=true attempt=1   ← new
```
`"muzica de intro"` has no imperative verb and `parseCommandToActionRequest` yields `CHAT`, so
`looksLikeNewCommand` returned **false** → `confirmReprompt('governed', 'true_ambiguity')`, **Baby
mission preserved**. The new code path executed exactly as designed for a genuinely ambiguous reply.

### MISSION-ISO-1 / -6 — code trace (device NOT_RUN)
`"Intră în YouTube"` / `"Intră în YouTube și caută Take My Breath Away"`:
`normConfirm` → `"intra in youtube …"` → `classifyConfirmation` = `UNKNOWN` →
`supersedeStaleConfirmationIfNewCommand` → `looksLikeNewCommand` → `NEW_COMMAND_VERB_RE` matches
`intra` → **true** → `supersedeActiveMission('new_user_command')` (Baby mission → `Superseded`,
cleared from store + AsyncStorage) → refs nulled → `MISSION_SUPERSEDED` → **no `return`** → the same
`msg` falls through gate (d)'s guarded YES block (skipped) and gate (e)'s `else if` (skipped,
`getActiveMission()` now `null`) → normal routing (per `ROUND_MISSION_DIAG_REPORT.md` device log,
`brain:open_app` → `OPEN_APP YouTube`). **Zero `confirmActiveMission`, zero `placeCall`, zero
`WA_DIRECT_*`, zero `CONFIRM_REPROMPT`.** The `looksLikeNewCommand` heuristic is proven correct by
ISO-4 (declined a non-command); the verb-match arm is a superset that additionally accepts
imperative phrasings the pure parser misses.

### MISSION-ISO-5 — code trace (device NOT_RUN)
Cold restart → `hydrateActiveMission()` sees `state === 'WaitingConfirmation'` → logs
`MISSION_HYDRATE_DROP missionId=… reason=persisted_waiting_confirmation`, sets `activeMission = null`,
`AsyncStorage.removeItem(ACTIVE_MISSION_KEY)`. A later `da` → `getActiveMission()` = `null` → gate
(e) does not fire → `da` routes as an ordinary word → **no call**.

### MISSION-ISO-2 — code trace (device NOT_RUN)
`classifyConfirmation("nu")` = `NO` → gate (e) `NO` branch → `cancelActiveMission()` (unchanged this
round) → `IDLE`, `"Am anulat"`, no call.

## 9. Regression risks

- `looksLikeNewCommand` errs toward supersession (invariant 1): a borderline reply containing an
  imperative verb ends a pending confirmation and routes as a new command. This is the intended,
  safe direction (no wrong privileged action), but a user who answers a confirmation with a
  verb-containing sentence that was *meant* as elaboration ("da, deschide-o") — caught by
  `classifyConfirmation`=YES first, so unaffected. A pure non-YES/NO sentence with a verb
  ("hai deschide") supersedes — acceptable.
- `getActiveMission()` `WaitingConfirmation` window shrank 5 min → 90 s: a user who takes >90 s to
  answer a confirmation loses it and must re-issue. Bounded by `MAX_CONFIRM_REPROMPTS = 3` already;
  90 s is generous for a spoken yes/no.
- `hydrateActiveMission` no longer restores `WaitingConfirmation` at all: a confirmation
  interrupted by an app kill is not resumed — by design (invariant 3). `WaitingUser` recovery is
  unchanged.
- New `MissionState` value `'Superseded'` — added to every terminal check that mattered
  (`clearIfTerminal`); `confirmActiveMission` / `resolveActiveMissionFromUtterance` already guard on
  the specific expected state, so an unknown terminal value is inert there. `tsc` clean.
- Not device-verified for ISO-1/2/5/6 (harness limits); the paths are small and traced.

## Core invariants — status

1. New valid command outranks an old unconfirmed mission — **implemented** (ISO-1/6 trace; ISO-4 proves the discriminator).
2. UNKNOWN is not auto-"please repeat" — **implemented** (new-command check first; ISO-4 shows the ambiguous case still reprompts).
3. No stale high-impact `WaitingConfirmation` survives a cold restart as executable — **implemented** (`MISSION_HYDRATE_DROP`, unconditional).
4. A confirmation authorises exactly one identified mission — **held** (`confirmActiveMission` binds to the single active mission and requires `WaitingConfirmation`; superseded → not confirmable).
5. Consequential confirmation understandable by voice alone — **already held** (`buildConfirmationPrompt` names app + action + target).

## Confirm

- source modified: **YES** — 4 files (§6); no WhatsApp/YouTube/wakeword/mic-hold/CALL-lifecycle/selector changes
- `npx tsc --noEmit`: **PASS**
- release build: **PASS** · APK signed `CN=BENSON, O=TOKKO` · installed (data preserved)
- git / prebuild: **NO**
- unintended action executed during tests: **NONE** (one intended ISO-3 `da` call, verified, ended)
