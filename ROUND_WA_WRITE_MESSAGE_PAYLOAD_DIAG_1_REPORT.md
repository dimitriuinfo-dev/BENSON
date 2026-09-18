# ROUND_WA_WRITE_MESSAGE_PAYLOAD_DIAG_1  (+ ROUND_CONTACT_DIAG_1)

Same bug from two angles: contact resolution was **correct** (header verified `HANNAH`); the
**message body** typed into the compose field was `"Benzim"` — a wake-word-family token, not
`"ajung mai târziu"`.

Not modified: ContactResolver, WhatsApp chat opening, Accessibility input discovery, send logic.

---

## PAYLOAD TRACE (fields kept separate at every stage)

`contactName` and `message` are **already** distinct typed fields the whole way down — this is
**not** a plumbing swap (recipient text is never assigned to `message` in any stage):

```
RAW_STT                       msg = "<transcript>"                         (app/index.tsx)
  ↓  transcriptNormalizer      strips a LEADING wake word ONLY
  ↓  cleanDiscourse            filler only
parser (commandParser.classify)
  MESSAGE_CONTACT              parameters.contactName = g1   parameters.message = g2
  ↓  goalExtractor.toGoal      entities.contact  = params.contactName   entities.message = params.message   (verbatim copy)
  ↓  missionPlanner            task.input.contactName / task.input.message
  ↓  toGovernedCall            params.contactName / params.message
  ↓  missionExecutor (Phase A) request.params.contactName / request.params.message
  ↓  prepareMessageDirect      name = contactName          msg = message.trim()
  ↓  runWhatsAppOpenConversationType(phone, displayName, msg, missionId)   ← arg #3 = the typed text
```

## ROOT CAUSE — FIRST corrupt stage = STT (message segment)

`"Benzim"` is a **wake-word-family token**: `transcriptNormalizer.LEADING_WAKE_WORD_PATTERN`
already lists `benson | benzine | benzin | benz[ăa] | benzon | …` as known STT mishears of the
wake word "Benson". `"Benzim"` / `"benzină"` is the same mishear.

- The recipient was heard correctly enough to resolve to `HANNAH` (device contact `HANNAH`, exact
  tier — `CONTACT_RESOLVE_SELECTED method=EXACT`, confirmed by the verified header).
- The **message segment** (`"…că ajung mai târziu"`) was **not** recognized as the user's words —
  the ro-RO recognizer had no anchor for that phrase (endpointer clip / low confidence) and what
  survived after `"că"` was a **trailing wake-word bleed** (`"Benson"` heard as `"Benzim"`).
- `transcriptNormalizer` strips the wake word **only at position 0**. A trailing/embedded
  `"Benzim"` passed straight through into the parser's `\bscrie … pe whatsapp c[ăa]\s+(.+?)`
  body capture (`g2 = "Benzim"`), and **every downstream stage copied it verbatim** — none of them
  is the corrupt stage, they are all faithful.

Proof the parser is faithful for a clean transcript: `scripts/wa-routing-tests.ts` cases A / D /
D2 feed the exact acceptance string and get `message = "ajung mai târziu"` (and the multi-word /
punctuation variants). The corruption requires a corrupted input.

Why `"Benzim"` reached WhatsApp's compose field rather than being rejected: before this round the
message body was only checked for *emptiness* (and only via the generic bad-payload guard). A
non-empty garbage token — including a wake word or the recipient's own name — was typed as-is.

---

## SMALLEST FIX  (parser only — none of the forbidden files touched)

`src/core/action-engine/commandParser.ts`:

1. **`WAKE_WORD_TOKEN` + `sanitizeMessageBody(raw)`** — removes every wake-word-family token from
   a captured message body (mirrors `transcriptNormalizer`'s leading-wake variant list),
   collapses whitespace, trims stray punctuation. A trailing/embedded `"Benson"`/`"Benzim"`/
   `"benzină"` bleed is stripped.
2. **`bodyEqualsContact(body, contact)`** — if the sanitized body folds (case/diacritic) to the
   **same string as the recipient token**, the body is set to `""`. The recipient name is never
   sent as the message.
3. The three contact+body patterns (`WHATSAPP_MESSAGE_EN/RO_PATTERN`, `MESSAGE_CONTACT_PATTERN`)
   now go through one `messageContact(contactRaw, bodyRaw, confidence)` helper that applies (1)+(2)
   and **always returns `MESSAGE_CONTACT`** — even when the body empties. An emptied body →
   `message: ''` → router → **`MESSAGE_BODY_MISSING`** → *"Ce să-i scriu lui X?"*. It never falls
   through to open-chat, never types the wake word / echo, never opens WhatsApp on an uncertain
   payload.

Hard invariant now holds: `messageBody` null/empty/wake-word/echo-of-contact ⇒ **STOP with
`MESSAGE_BODY_MISSING`**; the contact name, an STT partial, the wake word, the last transcript and
UI text are never substituted for the body.

### Diagnostic logs added (both rounds' sets)
- `WA_PAYLOAD_RAW_STT text="…" viaVoice=…`               (`app/index.tsx`)
- `WA_PAYLOAD_PARSED contact="…" message="…" intent=…`   (`missionOrchestrator`, per COMMUNICATION goal)
- `WA_PAYLOAD_GOAL contact="…" message="…"`               (same — proves PARSED == GOAL)
- `WA_PAYLOAD_ACTION contact="…" message="…"`             (`toGovernedCall`)
- `WA_PAYLOAD_MISSION contact="…" message="…"`            (`missionExecutor` Phase A entry)
- `WA_PAYLOAD_NATIVE_TYPE text="…" contact="…"`           (`prepareMessageDirect`, exact JS→native arg)
- `CONTACT_RESOLVE_QUERY value="…" normalized="…"`        (`resolveWaNumber`)
- `CONTACT_RESOLVE_CANDIDATES values=[…]`
- `CONTACT_RESOLVE_SELECTED value="…" status=… method=EXACT|NORMALIZED|NONE`

The first stage whose `message`/`text` field differs from the user's body pinpoints the corruption
on the next real run; with this fix a wake-word/echo body is neutralised at the parser and the
chain shows `WA_MSG_ROUTE_SELECTED route=MESSAGE_BODY_MISSING` instead of `WA_PAYLOAD_NATIVE_TYPE
text="Benzim"`.

---

## TESTS

`scripts/wa-routing-tests.ts` — standalone (no jest in repo), compiled + run with `node`.

```
A  "scrie-i lui Hannah pe WhatsApp că ajung mai târziu"  → contact=Hannah, message="ajung mai târziu", route=prepareMessage   PASS
B  "deschide conversația cu Hannah pe WhatsApp"           → openContact                                                        PASS
C  "scrie-i lui Hannah pe WhatsApp" (no body)             → MESSAGE_BODY_MISSING (not openContact)                             PASS
D/D2/D3  multi-word / colon / channel-less bodies         → prepareMessage, body intact                                        PASS
E  "sună pe Hannah pe WhatsApp"                           → placeCall (unchanged)                                              PASS
F  4 message forms                                        → never openContact, intent never OPEN_WHATSAPP_CONTACT             PASS
G  "scrie-i lui Hannah pe WhatsApp că Benzim"             → contact=Hannah, message="" , route=MESSAGE_BODY_MISSING,
                                                             message != contact                                               PASS  ← the reported bug
G2 "…că ajung mai târziu Benson"                          → message="ajung mai târziu" (wake token stripped), prepareMessage   PASS
G3 "…că Hannah" (body == recipient)                       → message="" , MESSAGE_BODY_MISSING                                  PASS

=== ALL PASS ===  (node exit 0)
```

---

## BUILD / INSTALLED

- `npx tsc --noEmit` → **0 errors**.
- routing tests → **ALL PASS**.
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 44s**, signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓).
- `adb install -r` → **Success** on `9c1464eb`, data preserved (`lastUpdateTime 2026-09-10
  12:34:13`, `firstInstallTime` unchanged), accessibility service bound.

## DEVICE TEST

**NOT_RUN** — voice-gated; no mic injection.

Acceptance (you, at the phone): `"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"` →
```
WA_PAYLOAD_RAW_STT text="<what STT actually produced>"
WA_PAYLOAD_PARSED  contact="Hannah" message="ajung mai târziu"
WA_PAYLOAD_GOAL / _ACTION / _MISSION  contact="Hannah" message="ajung mai târziu"
CONTACT_RESOLVE_SELECTED value="HANNAH" status=resolved method=EXACT
WA_PAYLOAD_NATIVE_TYPE text="ajung mai târziu" contact="HANNAH"
```
compose field shows exactly **"ajung mai târziu"**, header **HANNAH**, nothing sent.
If STT again mis-hears the body → the chain stops at `WA_MSG_ROUTE_SELECTED
route=MESSAGE_BODY_MISSING` and BENSON asks *"Ce să-i scriu lui Hannah?"* — it does **not** type
`"Benzim"` and does **not** open the chat on an uncertain body.

---

## Confirm
- forbidden files untouched: ContactResolver, WhatsApp chat opening, Accessibility input
  discovery, send logic — **no changes**
- fix scope: `commandParser.ts` (message-body sanitizer + hard `MESSAGE_BODY_MISSING`) +
  diagnostic logs in `app/index.tsx`, `missionOrchestrator.ts`, `missionExecutor.ts`,
  `whatsappTool.ts`
- one type of change: add (sanitizer + logs); the sanitizer only ever *shrinks* a body, never
  substitutes one
- tsc: PASS · tests: ALL PASS · build: PASS (`O=TOKKO`) · installed: YES (data preserved) ·
  device test: **NOT_RUN**
- git / prebuild / setx: NO
