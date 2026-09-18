# ROUND_WA_HEADER_DIAG_1_REPORT

Read-only. Diagnose ONLY the conversation verification that produced:
> "Am deschis WhatsApp dar nu am putut confirma că e conversația cu «Pompei». Nu am sunat."

`source: NO` · `build: NO` · `patch: NO`

That sentence is the JS mapping of native step **`WHATSAPP_CONTACT_VERIFY_FAILED`** from
`runWhatsAppOpenConversationCall` (WA-FIX-4), `whatsappTool.ts tryDirectContactCall`:
```
r.step === 'WHATSAPP_CONTACT_VERIFY_FAILED'
  → `Am deschis WhatsApp dar nu am putut confirma că e conversația cu „${contact.displayName}". Nu am sunat.`
```
So: contact **resolved** (a `displayName` "Pompei" and a phone existed → the deep link fired), the
chat opened (`id/entry` found), but the header check failed. Verification stops before the call
button — no call was attempted. Correct fail-safe behaviour; wrong verdict.

**No device logcat for the "Pompei" run survived** (buffer rotated; no capture was running) and it
can't be reproduced from here (voice-gated + would place/attempt a real call). The answers below are
from code + the WhatsApp conversation currently on screen; the two items that need the run's own log
line are marked UNCONFIRMED.

---

## ROOT CAUSE

**`NAME_MISMATCH`**

`conversationTitleText()` (`BensonAccessibilityService.kt`) is **not anchored to the contact-name
node**. It compares whatever string it returns against `"Pompei"`, and that string was almost
certainly **WhatsApp's status / message-preview / "last seen" line, not the contact name**:

```kotlin
private fun conversationTitleText(): String? {
    val byId = findNodeMatching { n ->
        val vid = n.viewIdResourceName ?: ""
        (vid.endsWith("/conversation_contact_name") ||
         vid.endsWith("/conversation_contact_status_holder")) &&   // ◄ (1) status container accepted as a name source
            !n.text.isNullOrBlank()
    }?.also { it.refresh() }?.text?.toString()?.trim()
    if (!byId.isNullOrBlank()) return byId
    val maxTop = headerRegionMaxTop()                              //   ≈ 15% of 2414 ≈ 362 px
    return findNodeMatching { n ->
        val b = Rect(); n.getBoundsInScreen(b)
        b.top in 0..maxTop && (n.className?.toString()?.contains("TextView") == true) &&
            !n.text.isNullOrBlank() && (n.text!!.length in 1..40) && !isSearchUiNode(n)   // ◄ (2) "first top TextView" — grabs the STATUS line
    }?.text?.toString()?.trim()
}
```

Live header of a WhatsApp conversation on this exact build (`com.whatsapp` 2.26.34.81):

| node | text | bounds |
|---|---|---|
| `com.whatsapp:id/conversation_contact_name` | **`BENSON`** (the real title) | `[300,139][523,216]` |
| `com.whatsapp:id/conversation_contact_status_holder` (LinearLayout) | `""` (empty — text is in the child) | `[300,216][744,265]` |
| `com.whatsapp:id/conversation_contact_status` (TextView) | **`Du`** ( = "Du: <preview>" / "Du bist…" — truncated ) | `[300,216][345,265]` |

Failure mechanism for "Pompei":
1. `whatsapp://send?phone=<digits>` (explicit `setPackage("com.whatsapp")`) opens the chat **shell**
   (toolbar + status area) a beat before WhatsApp binds/renders `conversation_contact_name`.
2. In that gap the primary lookup (`conversation_contact_name` non-blank) → **blank**;
   `conversation_contact_status_holder` → skipped (its own `.text` is `""`).
3. Fallback fires: `conversation_contact_status` ("Du" / "Du: foto" / "zuletzt online heute um …" /
   "tippt …") sits at `top=216` (< `maxTop`), is a `TextView`, 1–40 chars, not search-UI → it is
   the fallback's pick. `awaitCondition(3000,150)` sees a non-blank result → **stops, `h = "Du…"`**,
   never re-tries once the name renders.
4. `nameMatch`: `normPhon("Du…") == "pompei"` → false · `wholeLabelPhoneticEquals` → false ·
   `phoneticNameMatch` → false  ⇒ **false**.
5. `digitsMatch`: `headerDigits = "Du".filter{isDigit}` = `""` (or from a time string "14:32" →
   "1432", `length < 6`) ⇒ guard `headerDigits.length >= 6` fails ⇒ **false** (no real digit
   comparison happened).
6. `verified = false` → `WA_DIRECT_CONVERSATION_VERIFY status=failed header="Du…" nameMatch=false digitsMatch=false`
   → `fail("WHATSAPP_CONTACT_VERIFY_FAILED", "conversation header \"Du…\" does not match \"Pompei\"")`.

The conversation was most likely the **correct** one (Pompei's chat) — the verifier just read the
wrong field. Not `WRONG_CONVERSATION`, not `HEADER_NOT_FOUND` (a header *was* read — the status
line), not `WHATSAPP_UI_CHANGED` (ids unchanged), not `CONTACT_RESOLUTION_FAILED`.

---

## The 8 items

| # | item | value | source |
|---|---|---|---|
| 1 | ContactResolver result for Pompei | **RESOLVED** — device contact `display_name=Pompei`, exactly **1** row, **1** phone number, no ambiguity. (The failure is at `WHATSAPP_CONTACT_VERIFY_FAILED`, i.e. *after* resolve + `WA_DIRECT_NUMBER_READY` + the deep link — so resolution succeeded.) | `content query content://com.android.contacts/data/phones` |
| 2 | phone tail | **`…5969`** (Romanian `+4…`) | same query, tail only |
| 3 | package actually foreground | **`com.whatsapp`** — the deep link uses `setPackage("com.whatsapp")` explicitly, and verification only runs *after* `id/entry` was found, so `com.whatsapp` was foreground with a conversation open. (Not `com.whatsapp.w4b` — that's a separate issue, `ROUND_WA_PROVIDER_DIAG_1`.) Current on-screen: `com.whatsapp/.Conversation` (a different chat — "BENSON" — now). | code + `dumpsys window` |
| 4 | WhatsApp header/text actually visible | **UNCONFIRMED for the Pompei run** — the `[wa_direct_verify] dump` and `WA_DIRECT_CONVERSATION_VERIFY header="…"` line did not survive in logcat. On this build the header region contains `conversation_contact_name` (the name) **and** `conversation_contact_status` ("Du…"). By the mechanism above, `conversationTitleText()` returned the **status line**. | code + live UI dump of a WA chat |
| 5 | expectedName passed to native verifier | **`"Pompei"`** — `runWhatsAppOpenConversationCall(phone, contact.displayName)` with `contact.displayName == "Pompei"` (from `tryDirectContactCall`). | `whatsappTool.ts` |
| 6 | exact comparison that failed | `nameMatch = normPhon(h)=="pompei" \|\| wholeLabelPhoneticEquals(h,"Pompei") \|\| phoneticNameMatch(h,"Pompei")` → **false** (because `h` ≠ a "Pompei"-like string — it was the status/preview line). `digitsMatch` also **false**. `verified = nameMatch \|\| digitsMatch = false`. | `BensonAccessibilityService.kt` verify block |
| 7 | phone-digit verification attempted? | **Attempted but inert.** `digitsMatch` needs `headerDigits.length >= 6`; with `h="Du…"` (0 digits) or a short time string the guard failed, so no real `phone.endsWith(headerDigits.takeLast(9))` comparison ran. Digit-verify only helps when the header itself shows a phone number, which it did not here. | code |
| 8 | exact reason verification returned false | `WHATSAPP_CONTACT_VERIFY_FAILED`. Branch: **most likely** `!verified` → `"conversation header \"<status text>\" does not match \"Pompei\""` (a header WAS read, just the wrong one). The alternative branch `h.isBlank()` → `"conversation header not readable"` is less likely because the fallback almost always finds *some* top-region TextView. **Which branch: UNCONFIRMED** (needs the run's `WA_DIRECT_FAIL stage=WHATSAPP_CONTACT_VERIFY_FAILED reason="…"` line). | code |

---

## To confirm on device (for the fix round)

Capture `BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`, then run the direct call for a contact
whose WhatsApp header is slow to render. Read:
```
WA_DIRECT_RESOLVE_RESULT status=resolved count=1
WA_DIRECT_NUMBER_READY name="Pompei" tail=5969
WA_DIRECT_START phone=***5969 name="Pompei"
WA_DIRECT_DEEPLINK_RESULT launched=true
WA_DIRECT_CONVERSATION_VERIFY status=failed header="<X>" nameMatch=<b> digitsMatch=<b>
WA_DIRECT_FAIL stage=WHATSAPP_CONTACT_VERIFY_FAILED reason="conversation header \"<X>\" does not match \"Pompei\""
[wa_direct_verify] dump: … conversation_contact_name text="<?>" … conversation_contact_status text="<?>"
```
`<X>` is the string `conversationTitleText()` returned. If `<X>` is a status/preview/"last seen"
line (or blank while `conversation_contact_name` in the dump shows "Pompei") → confirms this root
cause.

## Fix shape (not implemented)

`conversationTitleText()` must be **name-anchored**: read only `…/conversation_contact_name` (drop
`conversation_contact_status_holder`); poll it (with `.refresh()`) for the full `awaitCondition`
window instead of returning on the first non-blank fallback; the "first top-region TextView"
fallback must **exclude** `…/conversation_contact_status*` and any node inside the status holder
(and ideally be dropped entirely — a wrong name is worse than "not readable" + retry). Independently,
give the deep-link header verify a longer / retried settle so the name has time to bind.

## Confirm
- source modified: NO · build: NO · patch: NO · git/prebuild: NO
- real call placed/attempted during diagnosis: NO (only `content query` [tail masked], `dumpsys`, one `uiautomator dump` of an already-open chat)
