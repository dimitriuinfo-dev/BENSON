# ROUND_WA_GOVERNANCE_WRITE_1B_REPORT

Mission wiring for the two-phase WhatsApp write (primitives from WA_GOVERNANCE_WRITE_1).
Doctrine-6 approval applied: **type into a VERIFIED compose field before confirmation; only SEND
stays behind explicit YES.**

---

## IMPLEMENTED

### `src/core/mission/missionExecutor.ts` — the only file changed this round

**PHASE A — at mission create, inside `if (requiresConfirmation && !options.confirmed)`**, after
the bad-payload guard, before the confirmation prompt:

- New `maybeRunWhatsAppWritePhaseA(request)` → `{ kind: 'skipped' | 'typed' | 'failed' }`:
  - runs **only** for `tool==='whatsapp' && action==='prepareMessage'` with a non-empty
    `message`, and only when `WA_WRITE_DIRECT && WHATSAPP_MESSAGE_VIA_ACCESSIBILITY`;
  - calls `whatsappTool.prepareMessageDirect(searchString, message, request.id)` — RESOLVE_CONTACT
    → OPEN_CHAT → VERIFY_CHAT → FIND_MESSAGE_INPUT → TYPE_MESSAGE → VERIFY_TYPED_TEXT, then STOPS;
  - `'typed'` → text is staged & verified in the compose field → ask for send-confirmation;
  - `'skipped'` → direct write unavailable (flag off / no contacts permission / not locally
    resolvable) → **existing confirm-then-`sendMessageByName` flow kept unchanged**;
  - `'failed'` → resolve/open/verify/type stopped, or an ambiguity question → mission `Failed`
    with the exact reason; **no send-confirmation is asked** (nothing was staged).
- On `'typed'`, the send parameters are carried through the confirmation round-trip on the
  **persisted** request:
  `params += { waWriteTyped:true, waWriteMissionId: request.id, waWriteMessage, waWriteContact }`
  and the mission goes to `WaitingConfirmation` with the prompt
  `Am scris în conversația cu <X> pe WhatsApp: "<msg>". Îl trimit?`.
- `request.id` (set once by `buildActionRequest`, preserved verbatim into the persisted mission
  request and reused by `confirmActiveMission`) is the **stable idempotency key** for both phases.

**PHASE B — on YES**, in the confirmed path, replacing the unconditional `runTool(...)`:

```ts
if (enrichedRequest.params.waWriteTyped === true) {
  result = await whatsappTool.confirmSendMessageDirect(missionId, message, contact); // SEND once + VERIFY_OUTGOING
} else {
  result = await runTool(enrichedRequest);                                           // unchanged for every other action
}
```

`confirmSendMessageDirect` → native `pressWhatsAppSendVerified(missionId, message)`:
- refuses unless the persisted state for `missionId` is `TYPED_VERIFIED`/`WAITING_CONFIRMATION`
  and `msgHash` matches → **never SEND unless identity + typed text were verified earlier**;
- persists `SEND_ATTEMPTED` **before** the tap → a recovery/re-entry after it re-VERIFIES
  (`whatsAppOutgoingMessagePresent`), **never re-presses**;
- state already `SENT_VERIFIED` → no-op success. **SEND is pressed at most once per mission.**

**Success** → existing `buildWaitingUserMessage` path: `WaitingUser`, "I-am trimis mesajul lui X
pe WhatsApp." **Phase-B failure** → its clean Romanian reason is surfaced as-is (unchanged
branch). `launch_failed` (accessibility down) → existing `Failed` + `buildFailureMessage`.

### `modules/benson-accessibility/.../BensonAccessibilityService.kt` — 1 small idempotency add
`runWhatsAppOpenConversationType` Phase-A entry guard extended: for the **same** `missionId`
already at `TYPED_VERIFIED`/`WAITING_CONFIRMATION` with the **same** `msgHash` →
`WA_WRITE_TEXT_TYPED ok=true note=idempotent_reentry` / `WA_WRITE_TEXT_VERIFIED ok=true
note=idempotent_reentry`, re-persist `WAITING_CONFIRMATION`, return `TYPED_VERIFIED` **without
re-typing**. (`SEND_ATTEMPTED`/`SENT_VERIFIED` still hard-refuse a retype.) →
**"do not type twice after recovery/re-entry"**.

### `src/core/mission/tools/whatsappTool.ts` — `prepareMessageDirect` now also returns `displayName`
(the locally-resolved contact name) on every `handled:true` branch, so the confirmation prompt and
Phase B use the real name, not the raw search string. No behaviour change otherwise.

### NO / UNKNOWN / supersession — verified, no code needed
| path | mechanism | result |
|---|---|---|
| **NO** | `app/index.tsx` gate → `cancelActiveMission()` → `Cancelled` + clear | Phase B never called (only reachable via `confirmActiveMission`) → **not sent**. Typed text sits unsent in the compose field (Doctrine-6 approved). |
| **UNKNOWN** | `app/index.tsx` → `confirmReprompt`, mission stays `WaitingConfirmation` | existing confirmation orchestration, untouched |
| **supersession** | `supersedeActiveMission()` → `Superseded` + clear; a superseded mission can never be confirmed (`confirmActiveMission` requires `WaitingConfirmation`) | old write can't SEND; a new write command → new mission → Phase A with a new `request.id` → native overwrites the single write record. Preserved. |

Stale native `WAITING_CONFIRMATION` record after NO/supersede is **inert**: `missionId` is a
`req_<ts>_<n>` id that is never reused, and `pressWhatsAppSendVerified` is only reachable through a
`WaitingConfirmation` mission carrying `waWriteTyped`.

### Confirmation architecture — not redesigned
Same two hook points the `placeCall` confirmation already uses (`execute()`'s
`!options.confirmed` branch, and `confirmActiveMission → execute({confirmed:true})`). No new
states, no change to `transitionMission` / the `app/index.tsx` gate / reprompt logic. No
phrase-specific logic — everything keys off `tool`/`action`/`params`, never the contact name.

---

## BUILD

- `npx tsc --noEmit` → **0 errors**.
- `:benson-accessibility:compileReleaseKotlin` → **BUILD SUCCESSFUL** (only pre-existing
  `recycle()` deprecation warnings).
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 1m 2s**. APK signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓).

## INSTALLED

- `adb` reconnected (device had dropped off in WA_GOVERNANCE_WRITE_1) → `9c1464eb device`.
- `adb install -r` → **Success**, data preserved (`firstInstallTime` unchanged,
  `lastUpdateTime 2026-09-10 11:43:00`), accessibility service still enabled.

## DEVICE TEST

**"scrie-i lui Hannah pe WhatsApp că ajung mai târziu" — NOT_RUN** (voice-gated; no mic injection
from this harness). Not marked PASS.

### Hand-off — STOP BEFORE SEND (first test)
`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`, then say
**"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"**. Do **not** answer the confirmation yet.

PASS requires the full chain and nothing sent:
```
WA_WRITE_START mission=req_… name="Hannah" msgLen=…
WA_WRITE_CONTACT_RESOLVED status=resolved count=1
WA_WRITE_NUMBER_READY name="Hannah" tail=<4>
WA_WRITE_DEEPLINK_START
WA_WRITE_CHAT_VERIFIED status=verified header="Hannah" nameMatch=true
WA_WRITE_INPUT_FOUND viewId=com.whatsapp:id/entry
WA_WRITE_TEXT_TYPED ok=true
WA_WRITE_TEXT_VERIFIED ok=true fieldLen=… wantLen=…
WA_WRITE_STATE mission=req_… state=TYPED_VERIFIED
WA_WRITE_WAIT_CONFIRMATION mission=req_…
WA_WRITE_STATE mission=req_… state=WAITING_CONFIRMATION
WA_WRITE_PHASE_A routed=typed contact="Hannah"
```
BENSON speaks **"Am scris în conversația cu Hannah pe WhatsApp: «ajung mai târziu». Îl trimit?"**,
Hannah's chat is open with **"ajung mai târziu" in the compose field**, and there is **no
`WA_WRITE_SEND_ATTEMPT`, no `state=SEND_ATTEMPTED`, message NOT sent**.

### Then the SEND stage
Reply **"da"** → expect `WA_WRITE_SEND_ATTEMPT mission=req_…` → `WA_WRITE_STATE … state=SEND_ATTEMPTED`
→ `WA_WRITE_SEND_CLICK ok=true` → `WA_WRITE_SENT_VERIFIED mission=req_… present=true` →
`WA_WRITE_STATE … state=SENT_VERIFIED`, "ajung mai târziu" appears in the transcript, "I-am trimis
mesajul lui Hannah pe WhatsApp." A repeated "da" / retry produces **no second send** (state
`SENT_VERIFIED` → no-op).
Reply **"nu"** instead → "Am anulat.", nothing sent, text left in the field.

---

## Proven-behaviour impact (regression rules §1)
| behaviour | affected? | why |
|---|---|---|
| Apel WhatsApp cap-coadă / `placeCall` | **no** | `runTool` unchanged for `placeCall`; Phase A/B gate only `prepareMessage` with a message body |
| `prepareMessage` when contact not locally resolvable | **no** | Phase A → `'skipped'` → existing confirm-then-`sendMessageByName` flow, unchanged |
| `openContact` (no message) | **no** | `maybeRunWhatsAppWritePhaseA` returns `'skipped'` (empty message) |
| Confirmation Gate / reprompt / supersession | **no** | same hook points as `placeCall`; no new states; NO/UNKNOWN/supersede paths verified above |
| mic auto-repair / wake / auto-return | **no** | untouched |

## Confirm
- files changed: `missionExecutor.ts` (wiring), `BensonAccessibilityService.kt` (idempotent
  re-entry guard, ~10 lines), `whatsappTool.ts` (`displayName` on the attempt result)
- one type of change: **add** (wire existing primitives) — no rewrite, no removal
- revert: `WA_WRITE_DIRECT = false` (`whatsappTool.ts`) → Phase A/B both `'skipped'`, old flow restored
- type twice: prevented (native re-entry guard) · send twice: prevented (`SEND_ATTEMPTED` pre-tap → re-verify only)
- never SEND without prior identity + typed-text verification: enforced in `pressWhatsAppSendVerified` state guard
- tsc: PASS · release build: PASS (`O=TOKKO`) · installed: YES (data preserved) · device test: **NOT_RUN** (voice-gated; hand-off above)
- git / prebuild / setx: NO
