# BENSON — WhatsApp Governance Routing Review

Diagnosis only. **No source changed this round** (supersedes the "diagnose then patch" framing of
WA_GOVERNANCE_WRITE_1C — this is architecture review first, per your follow-up).

Acceptance command traced: **`"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"`**

---

## ROOT CAUSE

The message body is discarded **in the deterministic parser**, before any mission or executor
runs. It is a regex-precedence bug, not a WhatsApp / Accessibility / mission-executor bug.

### The real path, step by step

| stage | file | what happens to `"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"` |
|---|---|---|
| STT → normalized text | `app/index.tsx` → orchestrator | text passed through intact (`pe WhatsApp` and `că ajung mai târziu` both present) |
| intent parse | `src/core/action-engine/commandParser.ts` `classify()` | **cascade of `if` checks, first match wins** |
| — 1. `WHATSAPP_MESSAGE_EN_PATTERN` (line 351) | | English only (`write … on whatsapp …`) → no match |
| — 2. `WHATSAPP_CONTACT_PATTERNS` via `firstMatch` (line 364) | line **68**: `/\bscrie(?:[- ]?i\b)?\s+(?:lui\s+)?(.+?)\s+(?:un\s+mesaj\s+)?pe\s+whatsapp\b/i` | **MATCHES** the prefix `"scrie-i lui Hannah pe whatsapp"`. Lazy `(.+?)` → `"Hannah"`. Pattern ends at `\bpe whatsapp\b` — the trailing `că ajung mai târziu` is **ignored, not captured**. Returns `intent: 'OPEN_WHATSAPP_CONTACT', parameters: { contactName: 'Hannah', channel: 'whatsapp' }` — **no `message`.** |
| — 3. `MESSAGE_CONTACT_PATTERN` (line 377): `/\bscrie(?:[- ]?i\b)?\s+(?:lui\s+)?(.+?)\s+(?:un\s+mesaj\s+)?c[ăa]\s+(.+?)…/i` — the pattern that captures **both** contact and message | | **never reached** (step 2 already returned) |
| goal extraction | `src/core/orchestrator/goalExtractor.ts` `toGoal()` | `entities.message = request.parameters.message` → `undefined`. `sourceIntent = 'OPEN_WHATSAPP_CONTACT'`. Goal type `COMMUNICATION`. |
| plan | `src/core/orchestrator/missionPlanner.ts` `buildTaskForGoal()` COMMUNICATION (line 62–68) | `message: goal.entities.message ?? ''` → `''`. Task `PREPARE_MESSAGE`, `input = { contactName:'Hannah', message:'', mode:undefined }`, `requiresConfirmation:true`. |
| governed call | `src/core/orchestrator/missionOrchestrator.ts` `toGovernedCall()` (line 190–193) | `const message = task.input.message.trim()` → `''`. `return message ? {action:'prepareMessage'} : {action:'openContact'}` → **`{ tool:'whatsapp', action:'openContact', params:{ contactName:'Hannah' } }`** |
| mission executor | `src/core/mission/missionExecutor.ts` | `requiredConfirmationFor('whatsapp','openContact')` → `true`. `!options.confirmed` branch → `maybeRunWhatsAppWritePhaseA` returns `skipped` (action is not `prepareMessage`) → `buildConfirmationPrompt` `openContact` branch → **`"Deschid WhatsApp, caut \"Hannah\" și deschid conversația. Confirmi?"`** |

That final string is exactly what you saw on device. **The two-phase WRITE wiring from
WA_GOVERNANCE_WRITE_1B is correct and was never invoked** — it only runs for
`action === 'prepareMessage'`, and the parser produced `OPEN_WHATSAPP_CONTACT` → `openContact`.

### Why the old open/search/confirm "wins"

It doesn't win a fight — **it is the only route offered.** By the time control reaches
`missionExecutor`, the action is already `openContact` and the message string no longer exists
anywhere in the mission. Nothing downstream can recover it. There is no fallback *selection*; the
intent was silently downgraded three layers up.

The specific trigger: `WHATSAPP_CONTACT_PATTERNS[line 68]` was added for the bare phrasing
*"scrie-i lui Hannah pe WhatsApp"* (open the chat, no message). Because it terminates at
`\bpe whatsapp\b` with no clause boundary, it **also matches the prefix** of the message form
*"scrie-i lui Hannah pe WhatsApp **că …**"*, and it is checked **before** the contact+message
pattern.

---

## ARCHITECTURAL PROBLEM

The parser bug is the immediate cause; these are the structural weaknesses that let a one-line
regex silently change what the user asked for:

1. **Two intents model the same user goal, distinguished only by an implicit emptiness check.**
   `MESSAGE_CONTACT` and `OPEN_WHATSAPP_CONTACT` both map to a `COMMUNICATION` goal →
   `PREPARE_MESSAGE` task. "Send a message" vs "open the chat" is encoded nowhere explicit — it is
   `message ? … : …` in `toGovernedCall`. **Losing the message body is indistinguishable from
   "the user only wanted to open the chat".**

2. **Every layer faithfully propagates the downgrade.** `goalExtractor` copies
   `parameters.message` (undefined→undefined); `missionPlanner` does `message ?? ''`;
   `toGovernedCall` does `message ? prepareMessage : openContact`. Each line is locally
   reasonable. The composition converts a message intent into an open intent with **no error, no
   log, no invariant check.**

3. **The parser is a flat first-match cascade** with hand-tuned ordering and overlapping patterns
   for the same verb (`scrie`). "Contact-only" and "contact+message" patterns for one verb family
   are not ordered by specificity and do not exclude each other. Adding a phrasing to one list
   silently shadows another.

4. **`missionExecutor` still contains a fallback** (`maybeRunWhatsAppWritePhaseA` → `skipped` →
   the old `sendMessageByName` / `openContactByName` via `buildConfirmationPrompt` +
   `runTool`). Even once the parser is fixed, a `CONTACT_UNRESOLVED` today drops back to the
   legacy open/search flow — which *looks* like the new feature working. (WA_GOVERNANCE_WRITE_1B
   kept this on purpose for compatibility; per this round it must go.)

5. **No routing observability.** There is no log line that says "this utterance was classified as
   a WhatsApp message with contact=X body=Y and routed to DIRECT_WRITE". The failure was only
   visible from the spoken sentence.

**Is the mission architecture suitable?** The *mission* layer (goal → plan → task → governed
call → `MissionExecutor` two-phase → native idempotent SEND) is sound and does not need
redesigning. The **intent layer** (parser + `MESSAGE_CONTACT`/`OPEN_WHATSAPP_CONTACT` duality +
the `message ? … : …` collapse points) is the structurally wrong part. The fix is to make
"message intent" a first-class, non-lossy contract from the parser down, and to delete the
legacy fallback rather than gate it.

---

## RECOMMENDED FIX

Smallest change that makes message intent un-loseable. Four edits + two deletions. No
phrase-specific rules — everything keys off *verb family* + *presence of a message tail*.

### 1. Parser — message form always beats contact-only form (`commandParser.ts`)

- **Add** one RO message pattern to the message set:
  `/\bscrie(?:[- ]?i\b)?\s+(?:lui\s+)?(.+?)\s+pe\s+whatsapp[\s,:]+(?:c[ăa]\s+)?(.+?)<NEXT_CLAUSE_BOUNDARY>/i`
  (contact = g1, message = g2). Same family as `MESSAGE_CONTACT_PATTERN`, just with an explicit
  `pe whatsapp` between name and body.
- **Check the message patterns before `WHATSAPP_CONTACT_PATTERNS`** — reorder the cascade so any
  `scrie/write/schreib` utterance that has a message tail (`că …` / `: …` / `, …` / `pe whatsapp …`)
  resolves to `MESSAGE_CONTACT` with both fields.
- **Guard the contact-only pattern** (`WHATSAPP_CONTACT_PATTERNS[line 68]`) with a negative
  lookahead so it can only match when there is **no** message tail:
  `/\bscrie…(.+?)\s+pe\s+whatsapp\b(?!\s*[:,]|\s+c[ăa]\s)/i`. It then means strictly "open the
  chat with X, nothing to send".
- Net rule (not phrase-specific): **`scrie`/`write`/`schreib` + any message tail ⇒
  `MESSAGE_CONTACT{contact,message}`. Same verb + no tail ⇒ an explicit open-chat intent.**

### 2. Canonical contract — collapse the duality (`missionOrchestrator.ts`, `missionPlanner.ts`)

- `toGovernedCall` for a `COMMUNICATION` goal:
  - `sourceIntent === 'CALL_CONTACT'` or `mode === 'voice_call'` → `placeCall` (**unchanged** —
    preserves working call functionality).
  - `sourceIntent === 'MESSAGE_CONTACT'` (or any goal carrying a non-empty `message`) →
    **always** `prepareMessage { contactName, message }`. Never `openContact`.
  - `sourceIntent === 'MESSAGE_CONTACT'` with an **empty** body → `prepareMessage` with an empty
    `message`, and `missionExecutor` returns `MESSAGE_BODY_MISSING` → BENSON asks *"Ce să-i scriu
    lui X?"* (mission stays open). **Never silently `openContact`.**
  - `openContact` is produced **only** from a genuine open-verb intent
    (`OPEN_WHATSAPP_CONTACT` with no `message` **and** no `mode`), i.e. "deschide conversația cu X".
- Remove the `message ? prepareMessage : openContact` ternary — replace with the explicit branch
  above.
- Add routing logs (see §"Logs").

### 3. `missionExecutor.ts` — no legacy fallback, loud failures

- `maybeRunWhatsAppWritePhaseA`: `prepareMessageDirect` returning `{ handled:false }` because the
  contact did not resolve locally must become **`kind: 'failed'` with reason `CONTACT_UNRESOLVED`**,
  not `kind: 'skipped'`. BENSON says it could not resolve the contact and hands ambiguity to
  `ContactResolver`; it does **not** run `sendMessageByName`/`openContactByName`.
- Add a `MESSAGE_BODY_MISSING` guard: `prepareMessage` with empty `message` → `Failed`/clarify,
  never a downgrade to open.
- The only remaining `'skipped'` case is the deliberate revert flag `WA_WRITE_DIRECT = false`.

### 4. Delete / deprecate the conflicting legacy message paths

- **Delete** `whatsappTool.sendMessage`, `sendMessageByName`, and the `WHATSAPP_MESSAGE_VIA_ACCESSIBILITY`
  branch of `buildConfirmationPrompt` (the *"Deschid WhatsApp, caut …, trimit mesajul: … Confirmi?"*
  string) and the corresponding `runTool` branch. Nothing may reach WhatsApp's send button except
  `pressWhatsAppSendVerified` (Phase B).
- **Keep** `openContactByName` **only** for the true open-chat intent; it must never be reached
  from a failed message parse.
- `runTool`'s `prepareMessage` branch is dead after §2/§3 (Phase A/B own it) — remove it.

### Canonical action/capability contract for WhatsApp message writing (Q6)

```
capability: whatsapp.prepareMessage
params (both REQUIRED, both non-empty):
  contactName: string   // exact STT token, resolved LOCALLY before any UI
  message:     string    // exact body, never merged with the contact token
lifecycle (idempotent, native-owned state, WA_GOVERNANCE_WRITE_1):
  RESOLVE_CONTACT → OPEN_CHAT(com.whatsapp) → VERIFY_CHAT
                  → FIND_MESSAGE_INPUT → TYPE_MESSAGE → VERIFY_TYPED_TEXT
                  → WAITING_CONFIRMATION  (typing done; nothing sent)
  YES → SEND_ON_YES (press once) → VERIFY_OUTGOING_MESSAGE → SENT_VERIFIED
  NO  → cancel (typed text left unsent)
  UNKNOWN → reprompt, stay WAITING_CONFIRMATION
separate capability: whatsapp.openContact  { contactName }  — open the chat, no send.
                     Reachable ONLY from an explicit open verb. Never a fallback.
```
`OPEN_WHATSAPP_CONTACT` loses its dual role — it becomes "open only". A message utterance emits
exactly one intent: `MESSAGE_CONTACT`.

### Failure representation (Q7) — BENSON never pretends another route ran

| condition | result | BENSON says |
|---|---|---|
| contact not found / ambiguous locally | `CONTACT_UNRESOLVED` → `ContactResolver` disambiguation | "Nu am găsit contactul «X»." / "Care X: …?" — **no** chat opened |
| message intent, empty body | `MESSAGE_BODY_MISSING` | "Ce să-i scriu lui X?" — mission stays open |
| chat identity mismatch | `CHAT_VERIFY_FAILED` (native `WA_WRITE_FAIL stage=VERIFY_CHAT`) | "Am deschis WhatsApp dar nu am putut confirma conversația cu «X». N-am scris nimic." |
| typed text ≠ requested | `TYPED_TEXT_MISMATCH` | "…nu am putut scrie mesajul în câmp. Scrie-l tu." |
| send tapped, bubble not seen | `OUTGOING_NOT_VERIFIED` | "…nu am putut confirma că mesajul a plecat. Verifică în WhatsApp." |
| accessibility disconnected | `ACCESSIBILITY_DISCONNECTED` | the standard re-enable sentence |

Invariant: **a mission created as `prepareMessage` terminates only as `SENT_VERIFIED`, an explicit
failure above, or user-cancelled — never as "opened the chat" and never as another action's
success.**

### Logs (added at the routing boundary)

```
WA_MSG_INTENT_RAW            text="<normalized utterance>"
WA_MSG_INTENT_PARSED         contact="<g1>" message="<g2>"            (parser, MESSAGE_CONTACT)
WA_MSG_ROUTE_SELECTED        route=DIRECT_WRITE | CONTACT_UNRESOLVED | MESSAGE_BODY_MISSING | INVALID
WA_MSG_OLD_FALLBACK_BLOCKED  reason=<why the legacy open/search path was NOT taken>
WA_MSG_PHASE_A_ENTER         mission=<req id> contact="<...>"
```
(`WA_WRITE_*` from WA_GOVERNANCE_WRITE_1 continue from Phase A onward.)

---

## FILES TO CHANGE

| file | change | kind |
|---|---|---|
| `src/core/action-engine/commandParser.ts` | add RO `scrie … pe whatsapp că …` message pattern; reorder message patterns before `WHATSAPP_CONTACT_PATTERNS`; negative-lookahead on `WHATSAPP_CONTACT_PATTERNS[68]` | change |
| `src/core/orchestrator/missionOrchestrator.ts` | `toGovernedCall`: explicit `MESSAGE_CONTACT → prepareMessage` (never `openContact`); `WA_MSG_*` logs | change |
| `src/core/orchestrator/missionPlanner.ts` | `buildTaskForGoal` COMMUNICATION: message-sourced goal keeps its body; empty body ⇒ mark for `MESSAGE_BODY_MISSING`, not `openContact` | change |
| `src/core/mission/missionExecutor.ts` | `CONTACT_UNRESOLVED` no longer `skipped`→fallback; add `MESSAGE_BODY_MISSING`; remove dead `prepareMessage` branch of `runTool`; drop the `WHATSAPP_MESSAGE_VIA_ACCESSIBILITY` prompt branch | change + delete |
| `src/core/mission/tools/whatsappTool.ts` | delete `sendMessage`, `sendMessageByName`; keep `openContactByName` for the open intent only; keep `prepareMessageDirect`/`confirmSendMessageDirect` (WA_GOVERNANCE_WRITE_1) | delete |
| `lib/agents/tools.ts` | `sendWhatsApp`: when `message` empty, return a "ce mesaj?" clarification instead of building `openContact` | change |
| `src/core/action-engine/commandParser.ts` tests (if present) | add cases for the acceptance command + the no-message open form + the call form | test |
| `modules/**` (Accessibility) | **none** — native Phase A/B unchanged this round |

Deprecated by removal (no revert flag kept — per "prefer deleting over layering"):
`whatsappTool.sendMessage`, `whatsappTool.sendMessageByName`, `runTool` `prepareMessage` branch,
`buildConfirmationPrompt` `WHATSAPP_MESSAGE_VIA_ACCESSIBILITY` branch.
Kept as revert: `whatsappTool.WA_WRITE_DIRECT` (false → the whole direct-write path is disabled and
`prepareMessage` reports `INVALID` rather than silently opening a chat).

---

## RISKS

1. **Parser reordering regressions.** Other `scrie`/`trimite`/`write`/`schreib` phrasings, and the
   compound-clause boundary (`… și scrie-i lui …`). Mitigation: the message patterns already use
   `NEXT_CLAUSE_BOUNDARY`; add unit cases for every phrasing in `WHATSAPP_CONTACT_PATTERNS` +
   `MESSAGE_CONTACT_PATTERN` before/after.
2. **Voice-call path.** `WHATSAPP_CALL_PATTERNS` is matched **earlier** (`classify` line 297) and
   is untouched — "sună pe Hannah pe WhatsApp" stays `placeCall`. Must be in the acceptance set
   (regression list: "Apel WhatsApp cap-coadă").
3. **Behavior change for a message utterance we cannot parse a body from.** Today it silently
   opens the chat; after the fix it asks "ce să-i scriu?". This is intended and better, but it is
   a visible change — call it out to the user.
4. **`openContact` callers.** The LLM `sendWhatsApp` tool and any "deschide conversația cu X"
   phrasing still produce `openContact`; that path must keep working (only the *fallback-from-
   message* route is removed).
5. **Deleting `sendMessage`/`sendMessageByName`.** Grep for external callers first (expected: only
   `runTool`). `notepad/actions.ts` builds `tel:`/`wa.me` URLs independently — check it does not
   import these.
6. **`CONTACT_UNRESOLVED` with no local match.** Contacts permission must be granted; if denied,
   the honest message is "am nevoie de acces la contacte", not a silent open.

---

## MIGRATION PLAN

1. **Parser + tests** (`commandParser.ts`). Add the message pattern, reorder, negative-lookahead.
   `npx tsc --noEmit`; run the parser test file if one exists; hand-verify the 3 forms
   (message / open / call) resolve correctly. No build needed yet.
2. **Routing contract** (`missionOrchestrator.ts`, `missionPlanner.ts`, `missionExecutor.ts`).
   `MESSAGE_CONTACT → prepareMessage` always; `CONTACT_UNRESOLVED` / `MESSAGE_BODY_MISSING` as
   real failures; `WA_MSG_*` logs; delete the dead `prepareMessage` `runTool` branch and the
   legacy prompt branch.
3. **Delete legacy tool fns** (`whatsappTool.ts`) once grep confirms no other callers.
4. `npx tsc --noEmit` → `gradlew assembleRelease` (cert `CN=BENSON, O=TOKKO`) → `adb install -r`
   (preserve data).
5. **Real-device acceptance** (below). DEVICE TEST stays **NOT_RUN** until physically executed.
6. One round, one change-type: this is a *routing* change only — no Accessibility edits, no
   fuzzy/phonetic contact work (explicitly out of scope).

---

## REAL-DEVICE ACCEPTANCE TEST  (NOT_RUN until executed physically)

`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`

**A. Primary — `"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"`** (do not answer the prompt):
PASS requires all of —
```
WA_MSG_INTENT_PARSED contact="Hannah" message="ajung mai târziu"
WA_MSG_ROUTE_SELECTED route=DIRECT_WRITE
WA_MSG_PHASE_A_ENTER mission=req_…
WA_WRITE_CHAT_VERIFIED status=verified
WA_WRITE_TEXT_TYPED ok=true
WA_WRITE_TEXT_VERIFIED ok=true
WA_WRITE_STATE mission=req_… state=WAITING_CONFIRMATION
```
— BENSON says **"Am scris în conversația cu Hannah pe WhatsApp: «ajung mai târziu». Îl trimit?"**,
Hannah's chat open with the text in the compose field, **nothing sent**, and BENSON does **NOT**
say *"Deschid WhatsApp, caut … Confirmi?"*.

**B. SEND stage** — reply "da": `WA_WRITE_SEND_ATTEMPT` → `state=SEND_ATTEMPTED` →
`WA_WRITE_SENT_VERIFIED present=true` → `state=SENT_VERIFIED`; a repeated "da" ⇒ no second send.
Reply "nu" instead ⇒ "Am anulat.", nothing sent.

**C. Unresolved contact** — `"scrie-i lui <nume inexistent> pe WhatsApp că test"`:
`WA_MSG_ROUTE_SELECTED route=CONTACT_UNRESOLVED`, BENSON says it could not resolve the contact,
**no chat opened**, `WA_MSG_OLD_FALLBACK_BLOCKED` present.

**D. Regression — call** — `"sună pe Hannah pe WhatsApp"`: still `placeCall`, chat opens, voice-call
button tapped (existing WA_DIRECT_* chain). No `WA_MSG_*`.

**E. Regression — open only** — `"deschide conversația cu Hannah pe WhatsApp"` (no body): resolves
to the open intent, chat opens, nothing typed, no send prompt.

**F. Regression — no message body** — `"scrie-i lui Hannah pe WhatsApp"` (nothing after):
`WA_MSG_ROUTE_SELECTED route=MESSAGE_BODY_MISSING`, BENSON asks "Ce să-i scriu lui Hannah?", no
chat action.

---

## Answers to the 7 questions (index)

1. **Where diverted:** `commandParser.ts` `classify()` — `WHATSAPP_CONTACT_PATTERNS[line 68]`
   matches the prefix of the message form and returns `OPEN_WHATSAPP_CONTACT` (no `message`)
   before `MESSAGE_CONTACT_PATTERN` (line 377) is reached.
2. **Why the old flow wins:** it isn't selected against anything — the intent is downgraded to
   `openContact` three layers before `missionExecutor`, and the message string is gone. The
   `message ? prepareMessage : openContact` collapse in `toGovernedCall` then routes to the
   legacy prompt.
3. **Architecture suitable?** Mission layer: yes. Intent layer (dual intent + implicit
   emptiness = "open", flat first-match parser cascade, executor-level fallback): structurally
   wrong — fix per §RECOMMENDED FIX.
4. **Smallest robust architecture:** one `MESSAGE_CONTACT` intent that always carries
   `{contact, message}`; `prepareMessage` capability with both params required; `openContact`
   reachable only from open verbs; `CONTACT_UNRESOLVED` / `MESSAGE_BODY_MISSING` as hard
   failures; SEND only via native idempotent Phase B; **no fallback path exists**.
5. **Files:** see §FILES TO CHANGE. Reuse: `MissionExecutor` two-phase, `prepareMessageDirect` /
   `confirmSendMessageDirect`, native Phase A/B, `ContactResolver`. Change: `commandParser`,
   `missionOrchestrator`, `missionPlanner`, `missionExecutor`. Delete: `whatsappTool.sendMessage`
   / `sendMessageByName`, `runTool` `prepareMessage` branch, legacy prompt branch.
6. **Canonical contract:** `whatsapp.prepareMessage { contactName:string!, message:string! }` +
   the native lifecycle above; `whatsapp.openContact { contactName }` as a separate, open-only
   capability. (§"Canonical action/capability contract".)
7. **Failures:** typed enum surfaced honestly (`CONTACT_UNRESOLVED`, `MESSAGE_BODY_MISSING`,
   `CHAT_VERIFY_FAILED`, `TYPED_TEXT_MISMATCH`, `OUTGOING_NOT_VERIFIED`,
   `ACCESSIBILITY_DISCONNECTED`), each with its own spoken sentence; a `prepareMessage` mission
   can never terminate as another action's success. (§"Failure representation".)

---

## Confirm
- source modified this round: **NONE** (diagnosis + proposal only, as requested)
- device test: **NOT_RUN**
- proposal touches routing only — no Accessibility changes, no fuzzy/phonetic contact work
- working call path (`placeCall`) preserved; confirmation + idempotency (WA_GOVERNANCE_WRITE_1)
  preserved
- git / prebuild / setx: NO
