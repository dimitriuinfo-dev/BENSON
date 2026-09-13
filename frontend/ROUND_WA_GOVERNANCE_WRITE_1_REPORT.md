# ROUND_WA_GOVERNANCE_WRITE_1_REPORT

First real WhatsApp message-write capability, as a **two-phase** flow around the confirmation gate:

```
prepareMessageDirect()      RESOLVE_CONTACT → OPEN_CHAT → VERIFY_CHAT → FIND_MESSAGE_INPUT →
                            TYPE_MESSAGE → VERIFY_TYPED_TEXT → (STOP, WAITING_CONFIRMATION)
── explicit YES ──
confirmSendMessageDirect()  SEND_ON_YES (press Send once) → VERIFY_OUTGOING_MESSAGE
```

No phrase-specific logic. Reuses the existing local `ContactResolver` and the verified
`whatsapp://send?phone=` direct-chat route. Explicit `com.whatsapp`.

**Scope note (first line, bold): the mission-layer wiring that routes the `prepareMessage`
action through these two phases is NOT included — it requires modifying
`src/core/mission/missionExecutor.ts` (protected, not granted this round) and reorders the
`prepareMessage` execute/confirm model. Everything else is implemented, built, and behind a revert
flag. See "NOT DONE" below.**

---

## IMPLEMENTED

### `modules/benson-accessibility/android/.../BensonAccessibilityService.kt`

**Idempotency state (companion, persisted in `benson_watchdog_prefs`)**
`NOT_TYPED → TYPED_VERIFIED → WAITING_CONFIRMATION → SEND_ATTEMPTED → SENT_VERIFIED`.
One pending write at a time, keyed by the JS `missionId`. Stores `missionId`, `state`,
`msgHash` (`message.trim().hashCode()` — no message body persisted), `updatedAt`.
`waWritePersist()` / `waWriteRead()` / `getWhatsAppWriteState()` (→ `"<missionId>|<state>"`).
Every transition logs `WA_WRITE_STATE mission=<id> state=<state>`.

**`runWhatsAppOpenConversationType(phone, expectedName, message, missionId)` — PHASE A**
(`WhatsAppCallNativeResult`, step `TYPED_VERIFIED` on success)
- idempotency: refuses if this `missionId` is already `SEND_ATTEMPTED`/`SENT_VERIFIED`.
- **OPEN_CHAT** — `Intent(ACTION_VIEW, whatsapp://send?phone=<digits>).setPackage("com.whatsapp")`
  `.FLAG_ACTIVITY_NEW_TASK` → `foregroundIsPackage(WA_PKG) || awaitTargetReacquired(WA_PKG, 8000)`
  (RUNTIME-LAYER-1) → wait for `com.whatsapp:id/entry`.
- **VERIFY_CHAT** — the WA-HEADER-FIX-1 poll, copied inline (not shared — the proven `placeCall`
  path stays byte-identical): up to 6 s polling `conversation_contact_name`, break on
  `nameMatch` (`normPhon` / `wholeLabelPhoneticEquals` / `phoneticNameMatch`) **or** `digitsMatch`
  (≥6 header digits vs the resolved number). No match → `WA_WRITE_FAIL stage=VERIFY_CHAT`,
  **nothing typed**.
- **FIND_MESSAGE_INPUT** — `com.whatsapp:id/entry` + `isEditable`, view-id only, **no coordinates**.
- **TYPE_MESSAGE** — one `ACTION_SET_TEXT` with the exact `message`.
- **VERIFY_TYPED_TEXT** — re-read the `entry` field (`.refresh()`), exact-string compare to
  `message.trim()`, up to 2.5 s. Mismatch → `WA_WRITE_FAIL stage=VERIFY_TYPED_TEXT`, no send.
- On success: persist `TYPED_VERIFIED` then `WAITING_CONFIRMATION`, return. **Send is never
  reached here.**

**`pressWhatsAppSendVerified(missionId, message)` — PHASE B** (step `SENT_VERIFIED` on success)
- guards, in order: wrong/absent `missionId` → fail; state `SENT_VERIFIED` → return ok (no-op);
  state `SEND_ATTEMPTED` → **re-verify only, never re-press** (`whatsAppOutgoingMessagePresent`
  → `SENT_VERIFIED`, else fail); state not `TYPED_VERIFIED`/`WAITING_CONFIRMATION` → fail;
  `msgHash` changed → fail.
- must still be foreground `com.whatsapp` with the exact verified text in `entry` (drift → fail,
  no tap).
- **persist `SEND_ATTEMPTED` BEFORE the tap** — a crash/interruption after this can only
  re-verify, never re-press → requirement 11.
- find `com.whatsapp:id/send` (clickable) → payment-sensitive guard → `clickNodeOrAncestor`
  **once**.
- **VERIFY_OUTGOING_MESSAGE** — poll up to 6 s for a non-editable, non-`/entry` node whose text
  equals the message (the sent bubble). Present → persist `SENT_VERIFIED`. Absent → `WA_WRITE_FAIL
  stage=VERIFY_OUTGOING_MESSAGE` (send tapped but unconfirmed — honest, not "sent").

**`whatsAppOutgoingMessagePresent(message)`** — private helper for the transcript check.

### `modules/benson-accessibility/.../BensonAccessibilityModule.kt` (+~34 lines)
`AsyncFunction("runWhatsAppOpenConversationType")`, `AsyncFunction("pressWhatsAppSendVerified")`,
`Function("getWhatsAppWriteState")`.

### `modules/benson-accessibility/index.js` (+19) · `index.d.ts` (+11)
`runWhatsAppOpenConversationType`, `pressWhatsAppSendVerified`, `getWhatsAppWriteState`.

### `src/core/mission/tools/whatsappTool.ts` (+~120 lines, protected — WhatsApp write path)
- `export const WA_WRITE_DIRECT = true` — **revert flag** (false → this path inert).
- `resolveWaNumber(name)` — reuses `resolveAgainstList` (local `ContactResolver`,
  `preferredChannel:'whatsapp'`) exactly as `tryDirectContactCall` does; `ambiguous` /
  `multiple_numbers` → clarifying question; `not_found` / `missing_phone` / no-permission →
  `{ ok:false, reason }`. Logs `WA_WRITE_CONTACT_RESOLVED status=… count=…`, and
  `WA_WRITE_NUMBER_READY name=… tail=<4>` (tail only).
- `prepareMessageDirect(searchString, message, missionId)` — PHASE A wrapper →
  `runWhatsAppOpenConversationType`. Success → `{ handled:true, result:{ outcome:'app_switch_observed',
  via:'wa_write_typed_verified' } }`. Failure → `mapWriteFailure(step, name)` (one clean Romanian
  sentence per stage).
- `confirmSendMessageDirect(missionId, message, displayName)` — PHASE B wrapper →
  `pressWhatsAppSendVerified`. Success → `via:'wa_write_sent_verified'`.
- Logs: `WA_WRITE_START`, `WA_WRITE_CONTACT_RESOLVED`, `WA_WRITE_NUMBER_READY`, `WA_WRITE_RESULT
  phase=A|B`, `WA_WRITE_SEND_ATTEMPT`, `WA_WRITE_FAIL stage=…` (JS side) plus every native
  `WA_WRITE_*` line.

### Requirement → implementation
| # | requirement | where |
|---|---|---|
| 1 | reuse local ContactResolver | `resolveWaNumber` → `resolveAgainstList` (unchanged) |
| 2 | explicit `com.whatsapp` | `setPackage("com.whatsapp")` on the deep link; `com.whatsapp:id/*` selectors |
| 3 | reuse verified direct-chat route | same `whatsapp://send?phone=` + `awaitTargetReacquired` + `entry` wait as `runWhatsAppOpenConversationCall` |
| 4 | verify identity BEFORE typing | VERIFY_CHAT stage runs before FIND_MESSAGE_INPUT; failure returns with nothing typed |
| 5 | input by stable semantic/view-id, no coordinates | `com.whatsapp:id/entry` + `isEditable`; zero `getBoundsInScreen` use |
| 6 | insert exact text | one `ACTION_SET_TEXT` with `message` verbatim |
| 7 | verify exact text present | VERIFY_TYPED_TEXT: re-read `entry`, `== message.trim()` |
| 8 | no send before explicit YES | Phase A never reaches send; Phase B is a separate call |
| 9 | after YES press Send once | Phase B: `SEND_ATTEMPTED` persisted pre-tap, single `clickNodeOrAncestor` |
| 10 | verify outgoing message in conversation | VERIFY_OUTGOING_MESSAGE: transcript node text `==` message |
| 11 | recovery never duplicates typing/sending | idempotency guards on `missionId`+state; `SEND_ATTEMPTED` pre-tap → re-call re-verifies only |
| 12 | every failure = exact stage + reason | `WA_WRITE_FAIL stage=<STAGE> reason=<why>`; `WhatsAppCallNativeResult.step`/`.error` |

---

## BUILD

- `npx tsc --noEmit` → **0 errors**.
- `:benson-accessibility:compileReleaseKotlin` → **BUILD SUCCESSFUL** (only pre-existing
  `recycle()` deprecation warnings).
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 1m 1s**. APK signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓). Path: `android/app/build/outputs/apk/release/app-release.apk`.
- **INSTALL: not performed** — device `9c1464eb` dropped off adb mid-round (it was connected
  earlier this session for the EMERGENCY_CORE_1 / WA_NATIVE_CALL_PROBE_1 installs; `adb devices`
  now lists nothing, `kill-server`/`wait-for-device`/re-poll did not bring it back — USB
  unplugged or phone powered down). The APK is built and ready; `adb install -r <path>` when the
  device is reconnected.

---

## DEVICE TEST

**Acceptance 1 ("scrie-i lui Hannah pe WhatsApp că ajung mai târziu", STOP BEFORE SEND) —
NOT_RUN.** It is voice-gated, needs the device, **and** needs the mission wiring below to route
through the new path. Not marked PASS.

### Hand-off — once wired + installed
`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`, then say
**"scrie-i lui Hannah pe WhatsApp că ajung mai târziu"** and confirm the prompt is *pending*.

PASS (first test) requires ALL of:
```
WA_WRITE_START mission=<id> …
WA_WRITE_CONTACT_RESOLVED status=resolved count=1
WA_WRITE_NUMBER_READY name="Hannah" tail=<4>
WA_WRITE_CHAT_VERIFIED status=verified header="Hannah" nameMatch=true
WA_WRITE_INPUT_FOUND viewId=com.whatsapp:id/entry
WA_WRITE_TEXT_TYPED ok=true
WA_WRITE_TEXT_VERIFIED ok=true fieldLen=… wantLen=…
WA_WRITE_WAIT_CONFIRMATION mission=<id>
WA_WRITE_STATE mission=<id> state=WAITING_CONFIRMATION
```
and, on the phone: Hannah's chat open, **"ajung mai târziu" visible in the compose field**,
**no `WA_WRITE_SEND_ATTEMPT`, no `WA_WRITE_STATE … state=SEND_ATTEMPTED`, message NOT sent**.
`getWhatsAppWriteState()` returns `<id>|WAITING_CONFIRMATION`.

Only after that passes, test the SEND stage: reply "da" → expect `WA_WRITE_SEND_ATTEMPT` →
`WA_WRITE_STATE … state=SEND_ATTEMPTED` → `WA_WRITE_SENT_VERIFIED mission=<id> present=true` →
`WA_WRITE_STATE … state=SENT_VERIFIED`, message in the transcript, and a second "da" / retry
produces **no second send** (state already `SENT_VERIFIED` → no-op).

---

## NOT DONE — needs a separate round + explicit permission

**Mission-layer wiring.** Today `src/core/mission/missionExecutor.ts` (protected) dispatches the
`prepareMessage` action straight to `whatsappTool.sendMessageByName(...)`, which opens + searches +
types + **sends** in one shot after a single blind confirmation (the user never sees the text
first). Routing it as *create → `prepareMessageDirect` (type + verify) → confirmation gate (user
now sees the staged text) → `confirmSendMessageDirect` (send + verify)* requires:

1. **`src/core/mission/missionExecutor.ts`** — split `prepareMessage`: run Phase A at mission
   build (before `WaitingConfirmation`), run Phase B in `confirmActiveMission` on YES. Pass a
   stable `missionId` (the mission's own id) to both. Adjust `buildConfirmationPrompt` for
   `prepareMessage` to "mesajul e scris în conversația cu «X»: «…». Îl trimit?" and
   `buildWaitingUserMessage` for the Phase-B outcomes. **This file is protected and not in this
   round's scope lock** — per CLAUDE.md §1/§2/§4 I stopped rather than improvise it.
2. **Doctrine confirmation (Doctrine 6).** The round's flow types into WhatsApp's compose field
   *before* the confirmation (nothing leaves the device until SEND). Please confirm explicitly
   that "type pre-confirm, only SEND is gated" is acceptable — then the wiring round can proceed.

Until then the new path is dormant behind `WA_WRITE_DIRECT` and nothing calls it.

---

## Proven-behaviour impact (regression rules §1)
| behaviour | affected? | why |
|---|---|---|
| Apel WhatsApp cap-coadă / `placeCall` | **no** | `runWhatsAppOpenConversationCall` byte-identical; new functions are separate entry points; VERIFY_CHAT logic copied, not shared |
| existing `sendMessage` / `sendMessageByName` | **no** | untouched; still the only path any caller uses |
| mic auto-repair / wake / auto-return | **no** | no change to mic-hold, wake, lifecycle, `serviceScope` |
| Confirmation Gate | **no** | no mission-layer change this round |

## Confirm
- one type of change: **add** (new two-phase write primitives, flag-gated) — nothing removed/rewritten
- files: `BensonAccessibilityService.kt`, `BensonAccessibilityModule.kt`, `index.js`, `index.d.ts`,
  `whatsappTool.ts` (5) — all WhatsApp-write; **`missionExecutor.ts` NOT touched**
- revert constant: `WA_WRITE_DIRECT` (`whatsappTool.ts`)
- SEND pressed at most once per mission: `SEND_ATTEMPTED` persisted before the tap; re-call re-verifies only
- tsc: PASS · release build: PASS (`O=TOKKO`) · install: **not done (device offline)** · device test: **NOT_RUN**
- git / prebuild / setx: NO
