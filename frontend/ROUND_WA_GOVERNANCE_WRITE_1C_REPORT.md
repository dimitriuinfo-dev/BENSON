# ROUND_WA_GOVERNANCE_WRITE_1C — WhatsApp governance routing fix

Routing only. **No Accessibility / native code changed** (`modules/**` untouched).

---

## ROOT CAUSE

The message body was discarded in the **deterministic parser**, three layers before
`missionExecutor`. `commandParser.ts` `classify()` is a first-match cascade; for
`"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"`:

- `WHATSAPP_CONTACT_PATTERNS` entry `/\bscrie(?:[- ]?i\b)?\s+(?:lui\s+)?(.+?)\s+(?:un\s+mesaj\s+)?pe\s+whatsapp\b/i`
  matched the **prefix** `"scrie-i lui Hannah pe whatsapp"`, captured `contactName="Hannah"`, and
  its match ended at `pe whatsapp` — the `că ajung mai târziu` tail was **ignored, not captured**.
  It returned `intent: 'OPEN_WHATSAPP_CONTACT'` with **no `message`**, and it was checked
  **before** `MESSAGE_CONTACT_PATTERN` (the pattern that captures both contact and body), which
  was therefore never reached.
- `goalExtractor` → `entities.message = undefined` → `missionPlanner` → `message: '' ` →
  `toGovernedCall` → `message ? prepareMessage : openContact` → **`openContact`** →
  `buildConfirmationPrompt` → **"Deschid WhatsApp, caut «Hannah» și deschid conversația.
  Confirmi?"** — the observed string. The two-phase WRITE wiring (WA_GOVERNANCE_WRITE_1B) only
  runs for `action === 'prepareMessage'` and was never invoked.

Structural cause: `MESSAGE_CONTACT` and `OPEN_WHATSAPP_CONTACT` modelled the same goal,
distinguished only by "is `message` non-empty", so a lost body was indistinguishable from
"user only wanted to open the chat", and every layer propagated the downgrade silently.

---

## FILES CHANGED

### `src/core/action-engine/commandParser.ts` — message intent always wins
- **New** `WHATSAPP_MESSAGE_RO_PATTERN` — `scrie(-i)? (lui)? X pe WhatsApp (că|:|,) Y` → contact
  = g1, message = g2.
- **Renamed & split** the old `WHATSAPP_CONTACT_PATTERNS`:
  - `WHATSAPP_MESSAGE_NOBODY_PATTERNS` — `scrie … pe whatsapp` / `trimite whatsapp lui X` /
    `schreib X auf whatsapp` with **no body** → emit `MESSAGE_CONTACT` with `message: ''`
    (router turns that into `MESSAGE_BODY_MISSING`). **Never `OPEN_WHATSAPP_CONTACT`.**
  - `WHATSAPP_OPEN_CHAT_PATTERNS` — **new**, explicit open verbs only:
    `deschide/arată-mi/open/show/öffne (conversația|discuția|chat|the chat) (cu|with) X [pe/auf/on whatsapp]`
    + `whatsapp with X` → `OPEN_WHATSAPP_CONTACT`.
- **`classify()` dispatch reordered** — message-family (`WHATSAPP_MESSAGE_EN_PATTERN` →
  `WHATSAPP_MESSAGE_RO_PATTERN` → `MESSAGE_CONTACT_PATTERN` → `WHATSAPP_MESSAGE_NOBODY_PATTERNS`)
  is now checked **before** the open-chat patterns. `MESSAGE_CONTACT_PATTERN`'s contact capture
  strips a stray trailing `pe whatsapp` and now requires a non-empty body.
- `WHATSAPP_CALL_PATTERNS` (voice-call, `classify` line ~305) — **untouched**.

### `src/core/orchestrator/missionPlanner.ts` — carry the intent through
- `buildTaskForGoal` COMMUNICATION: the `PREPARE_MESSAGE` task input now also carries
  `intent: goal.sourceIntent` and `rawText: goal.normalizedText`, so `toGovernedCall` can tell an
  explicit open-chat intent from a message intent with an empty body.

### `src/core/orchestrator/missionOrchestrator.ts` — canonical routing + logs
- `toGovernedCall` `PREPARE_MESSAGE`:
  - `mode === 'voice_call'` → `placeCall` (**unchanged**).
  - `sourceIntent === 'OPEN_WHATSAPP_CONTACT'` → `openContact` (**only** here).
  - **everything else → `prepareMessage { contactName, message }`, always** — empty body carried
    through; `message ? prepareMessage : openContact` ternary **removed**.
- Logs: `WA_MSG_INTENT_RAW`, `WA_MSG_INTENT_PARSED contact=… message=…`,
  `WA_MSG_ROUTE_SELECTED route=DIRECT_WRITE|MESSAGE_BODY_MISSING|OPEN_CHAT`.

### `src/core/mission/missionExecutor.ts` — hard invariants, no fallback
- `maybeRunWhatsAppWritePhaseA`: for a `prepareMessage` it now returns **only `typed` or `failed`**
  (never `skipped`):
  - empty body → `failed` reason `MESSAGE_BODY_MISSING`, says *"Ce să-i scriu lui X?"* +
    `WA_MSG_OLD_FALLBACK_BLOCKED`.
  - `WA_WRITE_DIRECT === false` → `failed` reason `WA_WRITE_DISABLED` (loud, not a silent open).
  - contact unresolved locally → `failed` reason `CONTACT_UNRESOLVED` /`CONTACTS_PERMISSION`,
    says *"Nu am găsit contactul «X» în agendă. N-am trimis nimic."* + `WA_MSG_OLD_FALLBACK_BLOCKED`.
    **No fallback to `sendMessageByName` / `openContactByName`.** ContactResolver's own ambiguity
    question is surfaced as-is.
  - `typed` → `WA_MSG_ROUTE_SELECTED route=DIRECT_WRITE`.
  - `WA_MSG_PHASE_A_ENTER mission=<id> contact=… msgLen=…` at entry; `WA_MSG_ROUTE_FAIL reason=…`
    on every failure.
- `runTool`: `prepareMessage` branch removed (Phase A/B own it — if it ever reaches `runTool`,
  `WA_WRITE_DIRECT` was off → `launch_failed`, never a silent open). `openContact` →
  `openContactByName` only.
- `findBadConfirmationPayload`: an **empty** `prepareMessage` body is no longer a "bad payload"
  (→ handled as `MESSAGE_BODY_MISSING` with a specific prompt); only a non-empty torn-STT body is
  rejected.
- `buildConfirmationPrompt`: the legacy `prepareMessage` branch (*"Deschid WhatsApp, caut …,
  trimit mesajul: … Confirmi?"*) **removed** (dead — Phase A builds its own prompt).

### `src/core/mission/tools/whatsappTool.ts` — legacy message paths deleted
- **`sendMessage` deleted** (wa.me?text= prefill + blind send tap).
- **`sendMessageByName` deleted** (search-recipe + type + send).
- Kept: `openContactByName` (open-chat only), `openConversation` (used by `lib/notepad` — out of
  scope), `prepareMessageDirect` / `confirmSendMessageDirect` (WA_GOVERNANCE_WRITE_1).
- Revert: `WA_WRITE_DIRECT = false` → `prepareMessage` fails as `WA_WRITE_DISABLED` (never a
  silent open).

### `lib/agents/tools.ts` — LLM path
- `sendWhatsApp`: empty `message` → asks *"Ce să-i scriu lui X?"*; empty `person` → *"Pentru cine
  e mesajul?"*. Never builds `openContact` from a missing body.

### `scripts/wa-routing-tests.ts` — new (standalone; no jest in this repo)

---

## TESTS

`scripts/wa-routing-tests.ts` imports the pure parser directly and mirrors the production routing
(`toGovernedCall` + the empty-body guard). Compiled with `npx tsc … --module commonjs` and run
with `node`.

```
A "scrie-i lui Hannah pe WhatsApp că ajung mai târziu"
  → MESSAGE_CONTACT · contact="Hannah" · message="ajung mai târziu" · route=prepareMessage   PASS
B "deschide conversația cu Hannah pe WhatsApp"
  → OPEN_WHATSAPP_CONTACT · contact="Hannah" · route=openContact                             PASS
C "scrie-i lui Hannah pe WhatsApp"
  → MESSAGE_CONTACT · message="" · route=MESSAGE_BODY_MISSING (NOT openContact)              PASS
D "scrie-i lui Hannah pe WhatsApp că ajung în 10 minute, adu pâine și lapte"
  → MESSAGE_CONTACT · body keeps "ajung în 10 minute, adu pâine…" · route=prepareMessage    PASS
D2 "scrie-i lui Hannah pe WhatsApp: ne vedem la 8"  → prepareMessage, body="ne vedem la 8"  PASS
D3 "scrie-i lui Baby că vin acum"  → prepareMessage, contact="Baby" (no "pe whatsapp")      PASS
E "sună pe Hannah pe WhatsApp"  → route=placeCall (unchanged)                               PASS
F 4 message forms (RO/EN, comma, colon)  → route never openContact, intent never
  OPEN_WHATSAPP_CONTACT                                                                     PASS

=== ALL PASS ===  (node exit 0)
```

Note on D: `NEXT_CLAUSE_BOUNDARY` (a pre-existing rule) stops the body at the ` și ` conjunction,
so `"… și lapte"` is treated as a possible second clause and drops from the body. This is
orthogonal to this round (it affected the old channel-less path identically) and was explicitly
out of scope — the fix here still recovers the message and routes to `prepareMessage`; before, the
entire body was lost and it routed to `openContact`.

---

## BUILD

- `npx tsc --noEmit` → **0 errors** (EXIT=0).
- routing tests → **ALL PASS** (node exit 0).
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 50s** (no native change — JS bundle repackaged).
  APK signed `CN=BENSON, OU=Dev, O=TOKKO` (O=TOKKO ✓).

## INSTALLED

- `adb install -r` → **Success** on `9c1464eb`, data preserved (`firstInstallTime` unchanged,
  `lastUpdateTime 2026-09-10 12:17:08`), accessibility service still bound.

## DEVICE TEST

**NOT_RUN** — voice-gated; no mic injection from this harness. Not marked PASS.

### Acceptance (you, at the phone)
`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`

**A. `"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"`** — do not answer the prompt. PASS =
```
WA_MSG_INTENT_RAW text="…"
WA_MSG_INTENT_PARSED contact="Hannah" message="ajung mai târziu"
WA_MSG_ROUTE_SELECTED route=DIRECT_WRITE
WA_MSG_PHASE_A_ENTER mission=req_…
WA_WRITE_CHAT_VERIFIED status=verified
WA_WRITE_TEXT_TYPED ok=true
WA_WRITE_TEXT_VERIFIED ok=true
WA_WRITE_STATE mission=req_… state=WAITING_CONFIRMATION
```
— BENSON says **"Am scris în conversația cu Hannah pe WhatsApp: «ajung mai târziu». Îl trimit?"**,
text in Hannah's compose field, **nothing sent**, and it does **NOT** say
*"Deschid WhatsApp, caut … Confirmi?"*.

**B.** `"deschide conversația cu Hannah pe WhatsApp"` → opens the chat, no send prompt.
**C.** `"scrie-i lui Hannah pe WhatsApp"` → `WA_MSG_ROUTE_SELECTED route=MESSAGE_BODY_MISSING`,
BENSON asks *"Ce să-i scriu lui Hannah?"*, no chat action.
**D.** unknown contact → `WA_MSG_ROUTE_FAIL reason=CONTACT_UNRESOLVED`, *"Nu am găsit contactul …"*,
`WA_MSG_OLD_FALLBACK_BLOCKED`, no chat opened.
**E.** `"sună pe Hannah pe WhatsApp"` → unchanged call flow (`WA_DIRECT_*`), no `WA_MSG_*`.
**F.** After A passes, reply "da" → `WA_WRITE_SEND_ATTEMPT` → `state=SEND_ATTEMPTED` →
`WA_WRITE_SENT_VERIFIED present=true`; repeated "da" → no second send.

---

## Preserved (verified)
- **Call path** (`placeCall` / `WHATSAPP_CALL_PATTERNS` / `tryDirectContactCall`) — untouched;
  test E green.
- **Two-phase WRITE** (WA_GOVERNANCE_WRITE_1 native Phase A/B, idempotency, mission supersession,
  explicit `com.whatsapp`) — untouched; only the *entry* to it was fixed.
- **Confirmation safety** — same two hook points; SEND still gated behind explicit YES.
- **`openContact`** — still works for the LLM `sendWhatsApp` (with a body now) and explicit open
  verbs; only the *fallback-from-messaging* route was removed.

## Confirm
- one type of change: routing (parser + orchestrator + executor wiring) + deletion of superseded
  legacy fns; no native/Accessibility changes
- revert: `WA_WRITE_DIRECT = false` (`whatsappTool.ts`) — `prepareMessage` then fails loud as
  `WA_WRITE_DISABLED`, never a silent open
- tsc: PASS · routing tests: ALL PASS · release build: PASS (`O=TOKKO`) · installed: YES (data
  preserved) · device test: **NOT_RUN**
- git / prebuild / setx: NO
