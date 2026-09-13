# ROUND_WA_ROUTE_DIAG_REPORT

Read-only diagnostic — find a deterministic, semantic, NON-SCROLL route from a deeply-scrolled
WhatsApp Chats screen to a started + verified WhatsApp call for a named contact.

Device `9c1464eb` / CPH2663 / OnePlus Nord 4 / OxygenOS 15. WhatsApp `2.26.34.81`.
**No source modified. No gestures enabled. No manifest change. No prebuild. No selectors patched.**

---

## Harness limitation (disclosed)

A read-only adb harness cannot issue `AccessibilityNodeInfo.performAction(ACTION_CLICK)`. `adb
shell input` offers only coordinate taps (forbidden this round) and key events; `KEYCODE_MENU` did
not open WhatsApp's Toolbar overflow (it surfaced an unrelated `mobi.drupe.app` caller-ID overlay).
So the **contents** of the overflow menu and of the "New chat" picker could not be enumerated on
device this round. Everything below that is marked `VERIFIED_BY_DEVICE` comes from `uiautomator
dump` of the live tree and `dumpsys package com.whatsapp` (intent filters); menu/picker internals
are marked accordingly.

---

## B — Top toolbar, Chats deeply scrolled (VERIFIED_BY_DEVICE)

`uiautomator dump`, list scrolled ~60+ rows down (first visible row = a raw phone number, chat
dated April 2025), `search-ish nodes = 0`. Every persistent (non-list) clickable node:

| resource-id | class | content-desc | clickable | bounds |
|---|---|---|---|---|
| `com.whatsapp:id/menuitem_camera` | `ImageButton` | `Kamera` | true | `[816,133][960,277]` |
| `com.whatsapp:id/menuitem_overflow` | `ImageView` | `Weitere Optionen` | true | `[960,133][1080,277]` |
| `com.whatsapp:id/fab` | `ImageButton` | **`Neuer Chat`** | true | `[864,1824][1032,1992]` |
| `com.whatsapp:id/extended_mini_fab` | `LinearLayout` | `Sende eine Nachricht an deinen Assistenten.` (Meta AI) | true | `[882,1644][1008,1764]` |
| bottom-nav items ×4 | `FrameLayout` | `Chats, 1 neue Benachrichtigung` + 3 unlabelled (Updates / Communities / Calls) | true | row `y≈2042–2282` |

`menuitem_overflow` and `fab` were present in **all three** independent deep-scroll dumps taken this
round → they are fixed chrome, **structurally independent of the scroll-away
`search_bar_inner_layout`**. There is **no** Search / Contacts / New-chat node in the toolbar
itself; the only toolbar actions are Camera and the ⋮ overflow.

---

## A — Overflow menu (`menuitem_overflow`)

- Node: `com.whatsapp:id/menuitem_overflow`, `ImageView`, `content-desc="Weitere Optionen"`,
  `clickable=true`, `bounds=[960,133][1080,277]` — **present while deeply scrolled** (VERIFIED_BY_DEVICE).
- **Menu contents: NOT enumerated** (harness limitation above).
- Historical WhatsApp Chats overflow (all recent 2.2x builds): *New group · New broadcast · Linked
  devices · Starred messages · Payments · Settings* — **no "Search" entry** (search has only ever
  been the toolbar magnifier, now the collapsing header bar). EVIDENCE LEVEL: **SPECULATIVE**
  (historical pattern; needs one semantic click to confirm).
- Assessment: low probability of a usable search/contacts route here.

---

## C — Android / WhatsApp intent routes (VERIFIED_BY_DEVICE from `dumpsys package`, + EXISTS_IN_CURRENT_CODE)

| Route | Component / filter | Opens | Needs |
|---|---|---|---|
| `whatsapp://send?phone=<digits>` / `whatsapp-consumer://send?...` / `whatsapp-sheet://send` | `com.whatsapp/.TextAndDirectChatDeepLink` — `VIEW` + `DEEP_LINK_REDIRECT`, schemes `whatsapp` / `whatsapp-consumer` / `whatsapp-sheet`, authority `send`, `BROWSABLE` | the **conversation** with that number (no chooser if the Intent is explicit `setPackage("com.whatsapp")`) | phone number |
| `https://wa.me/<digits>` | same, via web intent | the conversation | phone number; **hits `ResolverActivity` (chooser)** unless made explicit → prefer the `whatsapp://` scheme |
| `SENDTO smsto:<number>` | `com.whatsapp/.Conversation` — `VIEW` + `SENDTO`, schemes `sms` / `smsto` | the conversation | phone number |
| `VIEW content://com.android.contacts/data/<id>` (WhatsApp voip data row) | `com.whatsapp/.accountsync.CallContactLandingActivity` — `VIEW`, scheme `content`, authority `com.android.contacts`, `BROWSABLE` | **starts a WhatsApp voice call directly** | a ContactsProvider data-row URI for the contact's WhatsApp "voice call" mimetype → a deeper ContactsProvider read |
| `PICK` + `Category "com.whatsapp"` | `com.whatsapp/.contact.ui.picker.ContactPicker` | contact picker | the private `com.whatsapp` category → effectively WhatsApp-internal, not a clean external route |
| `com.google.android.voicesearch.SEND_MESSAGE_TO_CONTACTS` | `com.whatsapp/.voicemessaging.productinfra.VoiceMessagingActivity` | Assistant voice-message flow | Assistant contract; message-only, not a call |

**No exported "start a WhatsApp voice call to phone number X" intent exists.** A call is reachable
by intent only via `CallContactLandingActivity` (needs a contacts data-row URI). Every clean
name→number route lands in the **conversation**, from which the call button is tapped via
Accessibility (already implemented + proven — `WA_NATIVE_FINAL_REPORT.md`, `pressWhatsAppCallButton` /
`runWhatsAppCallNative` steps: `WA_NATIVE_CALL_CLICK` → `WA_NATIVE_CALL_VERIFY success=true` on
RUNs 1/2/6/9).

### What BENSON already has (EXISTS_IN_CURRENT_CODE)

| Capability | File / symbol |
|---|---|
| Address-book read (the single reader) | `src/core/contacts/deviceContacts.ts` — `import * as Contacts from 'expo-contacts'`; `Contacts.getContactsAsync({ fields: [PhoneNumbers, Emails] })`; permission helpers. Doc-comment: *"Every caller that needs contacts (WhatsApp tool, Mission Orchestrator's contacts search, CALL_CONTACT resolution) goes through this."* |
| Name → contact (with phone) | `src/core/contacts/contactResolver.ts` — exact / partial / fuzzy (`fuzzyClose`, Levenshtein ≤ ⅓ len: `"hana"→"hannah"`). `contactTypes.ts` `phoneNumbers?: string[]`. `contactResolver.ts:150` `if (needsPhone && no phoneNumbers) → error`. Comment at `:66-68`: *"the raw ContactsProvider query for 'Baby' returned exactly one row"* — **"Baby" is already known to resolve to a single contact.** |
| Phone → WhatsApp deep link | `src/executors/whatsappExecutor.ts` — `sanitizeForWaMe()`, `buildWaMeUrl(digits,msg)` → `https://wa.me/<digits>`; `openDeepLink(url)`. Already has an explicit `request.parameters.mode === 'voice_call'` branch (`:82`) that today just opens the chat and says *"Apelul vocal trebuie pornit manual"* — because when it was written there was no call-button automation. There now is. |
| Phone → `tel:` | `src/executors/phoneCallExecutor.ts` — `sanitizePhoneNumber()` |
| WhatsApp deep-link gatekeeper | `src/core/mission/tools/whatsappTool.ts:2-3` — *"The ONLY place allowed to call Linking for WhatsApp."* |
| `READ_CONTACTS` permission | `app.json:15` + `AndroidManifest.xml:12` (declared); device `dumpsys package com.benson.butler` → `READ_CONTACTS: granted=true` (also `WRITE_CONTACTS: granted=true`) — **VERIFIED_BY_DEVICE, already granted.** |

The name→number→deep-link→(accessibility call) chain is **fully present in the codebase and
unblocked on the device**. It is simply **not wired to the `placeCall` / `voice_call` mission
path**, which per product doctrine (see G) was deliberately kept name-only:
`WA_NATIVE_FINAL_REPORT.md` — *"the `placeCall` route deliberately does not read any contact list —
`resolveContact()` is disconnected for `placeCall`."*

---

## D — Contact-first route (assessment)

`resolveContact("Baby")` → `phoneNumbers[0]` → sanitize → `whatsapp://send?phone=<digits>` explicit
Intent to `com.whatsapp` → lands in **Baby's conversation** (list untouched, no search, no header)
→ verify header == "Baby" (`conversationTitleText()`, exists) → find call button (`menuitem_call` /
`voip_call` / `sprachanruf` semantic — exists, `isPaymentSensitive` guard exists) → `ACTION_CLICK`
→ verify call screen (`call_screen` / `end_call_button` / name match — exists). Every step after the
deep link is **already implemented and device-proven**.

- Works with Chats deeply scrolled: **YES** — it never touches `android:id/list`.
- Phone number required: **YES** (`READ_CONTACTS`, granted).
- Accessibility required: **YES**, but only for the in-conversation call-button tap + verify (not for navigation).
- Gesture permission required: **NO** (`canPerformGestures` stays false).
- Reliability: **HIGH** — deterministic intent, no scrolling, no fuzzy row-matching against a
  search-results list (WhatsApp's own name resolution is replaced by the address-book match, which
  BENSON can verify against the conversation header before calling).

---

## E — New chat / contact picker route (assessment)

- `com.whatsapp:id/fab` `content-desc="Neuer Chat"` — **present in all three deep-scroll dumps**
  (VERIFIED_BY_DEVICE), fixed overlay, independent of the scroll-away header.
- Semantic `ACTION_CLICK` on it opens WhatsApp's New-chat screen (`com.whatsapp/.contact.ui.picker.ContactPicker`
  / `.contact.picker.ContactPicker` — both present in the package). That screen has its **own**
  search field and full contact list — a *different screen*, so **structurally independent** of the
  chat-list search header. EVIDENCE LEVEL for "picker has its own search field / selectable rows":
  **SPECULATIVE this round** (well-established WhatsApp behaviour, but not device-verified here
  because the click couldn't be issued semantically from the harness).
- deeply-scrolled Chats → `fab` (ACTION_CLICK) → New-chat screen → type "Baby" into its search →
  tap the matching row → conversation → call button → verify.
- Works with Chats deeply scrolled: **YES**.
- Phone number required: **NO** — a name string into the picker's own search (same doctrine as today).
- Accessibility required: **YES**. Gesture permission: **NO**.
- Reliability: **MEDIUM–HIGH**, pending device verification of the picker's search field id + row ids.

---

## F — Route ranking

### ROUTE_1 — DIRECT_CONTACT_DEEPLINK  *(recommended)*
- **Path:** `resolveContact(name)` → `phoneNumbers[0]` → `whatsapp://send?phone=<digits>` explicit
  Intent (`setPackage("com.whatsapp")`) → conversation → verify header == name → find call button →
  `ACTION_CLICK` → verify call screen.
- **Evidence:** deep-link filter `TextAndDirectChatDeepLink` VERIFIED_BY_DEVICE; `deviceContacts.ts` /
  `contactResolver.ts` / `whatsappExecutor.ts` EXISTS_IN_CURRENT_CODE; `READ_CONTACTS` granted
  VERIFIED_BY_DEVICE; call-button tap + verify VERIFIED_BY_DEVICE (`WA_NATIVE_FINAL_REPORT.md`).
- **Required UI states:** none in Chats — starts from any state; only the conversation screen.
- **Selectors/IDs:** conversation header (`conversation_contact_name`), call button
  (`menuitem_call` / `voip_call` / desc `sprachanruf`), call screen
  (`call_screen` / `end_call_button` / `audio_route_button`) — all already used.
- **Chats deeply scrolled:** irrelevant — list never touched.
- **Phone number required:** YES.
- **Needs Accessibility:** YES (call button only). **Needs gesture permission:** NO.
- **Reliability:** HIGH.
- **Failure modes:** contact has no / multiple numbers (resolver `needsPhone` + ambiguity already
  handle this); resolved number is not the WhatsApp-registered one → "X is not on WhatsApp" dialog
  (detectable, fail clean); deep link shows a one-tap "message X?" interstitial for a non-saved
  number (won't occur for an address-book contact); wrong-contact safety = verify the conversation
  header before tapping call.

### ROUTE_2 — NEW_CHAT_CONTACT_PICKER  *(fallback if D's contacts read is rejected)*
- **Path:** `com.whatsapp:id/fab` (ACTION_CLICK) → New-chat picker → type name into its own search
  → tap matching row → conversation → verify header → call button → verify.
- **Evidence:** `fab` node VERIFIED_BY_DEVICE (present when deeply scrolled, ×3); picker activity
  present in package; picker's internal search field / row ids **SPECULATIVE** (not device-verified).
- **Required UI states:** New-chat picker screen.
- **Selectors/IDs:** `com.whatsapp:id/fab` (confirmed); picker search input + result row (TBD on device).
- **Chats deeply scrolled:** YES — `fab` is fixed; picker is a separate screen.
- **Phone number required:** NO.
- **Needs Accessibility:** YES. **Needs gesture permission:** NO.
- **Reliability:** MEDIUM–HIGH pending one device check of the picker internals.
- **Failure modes:** picker row-matching is the same fuzzy-name problem the current search flow has
  (Ana/Adriana); picker layout could differ from the chat-list search; an "invite / not on
  WhatsApp" row.

### ROUTE_3 — OVERFLOW_SEARCH  *(unlikely)*
- **Path:** `menuitem_overflow` (ACTION_CLICK) → menu → "Search"/"Suche" item → search state → type → pick.
- **Evidence:** overflow node VERIFIED_BY_DEVICE; a "Search" menu item is **SPECULATIVE and
  historically absent** from WhatsApp's Chats overflow.
- **Chats deeply scrolled:** node yes; usable route probably N/A.
- **Reliability:** LOW. Recommend a single semantic click to confirm/deny, then drop.

### (ROUTE_4 — CALL_CONTACT_LANDING content:// — not ranked)
`CallContactLandingActivity` starts a WhatsApp call directly, but needs a
`content://com.android.contacts/data/<id>` URI for the contact's WhatsApp voip mimetype → a
ContentResolver query BENSON does not do today. Heavier contacts coupling than ROUTE_1 for no gain
(ROUTE_1 already lands the call). EVIDENCE: filter VERIFIED_BY_DEVICE; BENSON's ability to build the
URI = SPECULATIVE.

---

## RECOMMENDATION = DIRECT_CONTACT_DEEPLINK

**`resolve Baby → whatsapp://send?phone=<number> → verify header → press call → verify CALL_STARTED`
is strictly superior to `open WhatsApp → global search → type → pick row`** for satisfying "sună pe
Baby pe WhatsApp":

- deterministic Intent vs. multi-step UI automation across a recycling list;
- eliminates scroll, the scroll-away search header, text entry into WhatsApp's field, search-results
  latency, and fuzzy row-matching against WhatsApp's result list (the Ana/Adriana class of bug);
- the only Accessibility left is the in-conversation call-button tap + verify, which is already
  proven on this device;
- it is architecturally closer to BENSON's stated model — *semantic intent → shortest verifiable
  path* — than imitating a user's finger.

Fallback, if the product decision in G goes against reading contacts: **ROUTE_2
(NEW_CHAT_CONTACT_PICKER)** — keeps the name-only doctrine, still fully non-scroll, needs one device
check of the picker's internal ids before implementation.

`OVERFLOW_SEARCH`: effectively `NO_SEMANTIC_ROUTE` (no Search in that menu).

---

## G — Product-rule note (must be decided before implementation)

CLAUDE.md invariant #2: *"Fără integrări de date. Fără agendă telefonică, fără citirea bazelor
altor aplicații. Numele se introduc ca șir de căutare în aplicația țintă; ea rezolvă."*

ROUTE_1 requires BENSON to read the device address book to turn "Baby" into a number — that is
"agendă telefonică". This is a **doctrine decision, not a technical blocker**:
- the capability is already built and partly in use — `deviceContacts.ts` doc-comment lists
  *"Mission Orchestrator's contacts search"* and *"CALL_CONTACT resolution"* and the WhatsApp
  **message** route as existing callers; `READ_CONTACTS` is declared and granted;
- only the `placeCall` / `voice_call` path was deliberately kept name-only.

If the product owner keeps invariant #2 for calls, implement **ROUTE_2**. If the owner accepts a
contacts read for the call path (as this round's framing suggests), implement **ROUTE_1**.

Only if ROUTE_1 and ROUTE_2 are both rejected/unworkable does enabling `canPerformGestures=true` +
a verified controlled swipe become the path — not before.

---

## H — Stop

No source modified. No gestures enabled. No manifest change. No prebuild. Report only — implementation
to follow once a route is chosen.

## CONFIRM

- source modified: **NO**
- `canPerformGestures` enabled: **NO**
- coordinate taps used: **NO** (only `uiautomator dump`, `dumpsys`, `KEYCODE_MENU`/`KEYCODE_HOME`/`KEYCODE_BACK`, prep-only `input swipe` to create the deep-scroll test state)
- AndroidManifest modified: **NO**
- git / prebuild: **NO**
- WhatsApp call placed: **NO**
