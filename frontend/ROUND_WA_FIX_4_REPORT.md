# ROUND_WA_FIX_4_REPORT

WA-FIX-4 — DIRECT-CONTACT-DEEPLINK as the PRIMARY route for a named WhatsApp call.
Product decision: BENSON may read the local address book to resolve a spoken name → number for
WhatsApp `placeCall`. `ROUND_WA_ROUTE_DIAG_REPORT.md` → `RECOMMENDATION = DIRECT_CONTACT_DEEPLINK`.

Device `9c1464eb` / OnePlus Nord 4 / OxygenOS 15. WhatsApp `2.26.34.81`.

---

## Status

| Item | Result |
|---|---|
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` | PASS — `BUILD SUCCESSFUL in 1m 13s`, APK 260,941,912 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install `9c1464eb` (`adb install -r`) | PASS — `Success`, data preserved |
| **ACC-WA-DIRECT-1** (deep-scrolled Chats) | **PASS — verified `CALL_STARTED` in 5.58 s, zero scroll, zero search** |
| **ACC-WA-DIRECT-2** (WhatsApp cold) | **PASS — verified `CALL_STARTED` in 6.40 s, zero scroll, zero search** (completion-test round, phone unlocked) |
| ACC-WA-DIRECT-3 (other tab) | **NOT_RUN** — round interrupted (see COMPLETION TESTS §) |
| ACC-WA-DIRECT-4 (ambiguous) | **NOT_RUN** — safety branch verified by code path only |
| ACC-WA-DIRECT-5 (unknown) | **NOT_RUN** — round interrupted |
| **POST_CALL_WAKE** | **FAIL** — after the -2 call ended, `watchWhatsAppCallLifecycle` reached `CALL_ENDING` but **never `CALL_ENDED`**; no `WA_CALL_AUDIO_HOLD state=released`, no `WAKE_MODE_RESTORED`, no `WAKE_SCAN_START`; BENSON stayed backgrounded, JS frozen, mic-hold left armed. Original "deaf after a call" bug reproduced. |
| **VERIFIED `CALL_STARTED` reached?** | **YES** — `WA_DIRECT_CALL_VERIFY success=true screen=true name="Baby" nameMatch=true` → `WA_DIRECT_END success=true step=CALL_VERIFIED`, from a deeply-scrolled Chats starting state, with **no** `WA_SEARCH_*` / `WA_CHAT_SCROLL_*` in the trace. |

The **critical** test (ACC-WA-DIRECT-1) passed. -2..-5 are blocked only by the device keyguard (a
lab-access issue, not a code issue); the build is installed and ready.

---

## 1. Files changed

| File | Change |
|---|---|
| `src/core/mission/tools/whatsappTool.ts` | + import `runWhatsAppOpenConversationCall`, `getContactsPermissionState`. New: `WA_DIRECT_CONTACT_DEEPLINK` flag, `sanitizeWaPhone()`, `DirectAttempt` type, `tryDirectContactCall()`. `placeCall()` now calls `tryDirectContactCall(name)` first and returns its result when `handled`; otherwise logs `WA_DIRECT_FALLBACK reason=falling_back_to_ui_search` and drops through to the unchanged UI-search route. |
| `modules/benson-accessibility/android/.../BensonAccessibilityService.kt` | New `suspend fun runWhatsAppOpenConversationCall(phoneRaw, expectedNameRaw): WhatsAppCallNativeResult`. `runWhatsAppCallNative` **untouched** (byte-identical). |
| `modules/benson-accessibility/android/.../BensonAccessibilityModule.kt` | New `AsyncFunction("runWhatsAppOpenConversationCall") { phone, expectedName, promise -> }` (same shape as `runWhatsAppCallNative`). |
| `modules/benson-accessibility/index.js` / `index.d.ts` | Export `runWhatsAppOpenConversationCall(phone, expectedName)`. |

Not touched: wakeword, STT, mic-hold, `CALL_ENDED`, auto-return, confirmation, Waze/YouTube/Spotify,
UI, TTS, gesture permissions, `canPerformGestures`, and the entire WA-FIX-1/2/3 UI-search / scroll
code (kept as the fallback). Revert: `WA_DIRECT_CONTACT_DEEPLINK = false` → `tryDirectContactCall`
short-circuits and `placeCall` behaves exactly as before this round.

## 2. Old call route

`placeCall(name)` → `runWhatsAppCallNative(name)` (native): launch WhatsApp → normalise Chats list →
scroll to top → open search header → type name → WhatsApp's own fuzzy row match → tap row → verify
chat header → call button → verify call screen. The scroll-to-top step was proven non-viable for a
deep list (`ROUND_WA_FIX_3_REPORT.md`).

## 3. New primary call route

```
placeCall(name)
  → tryDirectContactCall(name):
      getContactsPermissionState()  ──not 'granted'──▶ WA_DIRECT_FALLBACK → UI-search route
      loadDeviceContacts()  (expo-contacts, on-device)
      resolveContact({ rawName: name, contacts, preferredChannel: 'whatsapp' })
        status 'ambiguous'      ▶ return clarification question, NO call
        status 'not_found'      ▶ WA_DIRECT_FALLBACK reason=contact_not_found → UI-search route
        status 'missing_phone'  ▶ WA_DIRECT_FALLBACK reason=contact_phone_unavailable → UI-search route
        status 'resolved':
          numbers = phoneNumbers.map(sanitizeWaPhone).filter(len>=6)
            0 numbers            ▶ WA_DIRECT_FALLBACK reason=contact_phone_unavailable → UI-search route
            >1 distinct numbers  ▶ return "pe care număr?" clarification, NO call
          phone = numbers[0]
          runWhatsAppOpenConversationCall(phone, contact.displayName)  (native, on service coroutine):
            startActivity(VIEW "whatsapp://send?phone=<phone>", setPackage("com.whatsapp"), NEW_TASK)
            await WhatsApp foreground + a conversation (id/entry)
            verify conversation header ≈ displayName (phonetic) OR trailing-digits match
              fail → WHATSAPP_CONTACT_VERIFY_FAILED  (no call, dumpScreenForDebug)
            call button  (viewId menuitem_call/voip_call → contentDesc sprachanruf/voice call/… → semantic)
              + isPaymentSensitive guard
            ACTION_CLICK → verify call screen (call_screen/end_call_button/… + name match)
              → arm 180 s mic-hold + watchWhatsAppCallLifecycle()   [same as runWhatsAppCallNative]
            → CALL_VERIFIED
  → if tryDirectContactCall returned { handled:false }: existing runWhatsAppCallNative(name) UI-search route
```

`runWhatsAppOpenConversationCall` steps 3–6 (verify chat → call button → verify call screen → arm
mic-hold) are a **parallel copy** of `runWhatsAppCallNative` steps 11–14, kept in sync by comment —
the proven `runWhatsAppCallNative` was not refactored.

## 4. Contact resolver used

`src/core/contacts` — **existing, unchanged**: `loadDeviceContacts()` (`deviceContacts.ts`,
`expo-contacts`, live read, never persisted) + `resolveContact()` (`contactResolver.ts`, tiered
exact → alias → partial → fuzzy/Levenshtein, `dedupeContacts`, `preferredChannel: 'whatsapp'` ⇒
`needsPhone`). No second resolver was created.

## 5. Phone normalization used

`sanitizeWaPhone(raw)` in `whatsappTool.ts`: `raw.replace(/\D/g,'')` (drops `+`, spaces, dashes,
parens — same as `whatsappExecutor.sanitizeForWaMe`), then a leading `00` international prefix is
collapsed to bare country-code digits. Numbers shorter than 6 digits are rejected. On device,
"Baby" resolved to a `+49…8957` number → `49…8957` → deep link succeeded.

## 6. Deep link used

`whatsapp://send?phone=<digits>` as an **explicit** `Intent(ACTION_VIEW).setPackage("com.whatsapp")`
+ `FLAG_ACTIVITY_NEW_TASK`, issued natively via `startActivity` on the accessibility-service
coroutine (background-proof, like `launchWhatsApp()`). Handler:
`com.whatsapp/.TextAndDirectChatDeepLink` (verified in `dumpsys package`, `ROUND_WA_ROUTE_DIAG_REPORT.md`).
No chooser (explicit package). No new/undocumented URI invented.

## 7. Conversation verification mechanism

After the deep link: wait for WhatsApp foreground **and** a conversation compose field
(`…/entry`); then read `conversationTitleText()` (existing helper — `conversation_contact_name` /
top-region TextView). Verified when the header phonetically matches the resolved `displayName`
(`normPhon` / `wholeLabelPhoneticEquals` / `phoneticNameMatch`) **or** the header's trailing 9
digits match the dialled number. Otherwise → `WHATSAPP_CONTACT_VERIFY_FAILED`, `dumpScreenForDebug`,
**no call button is pressed**. Device: `WA_DIRECT_CONVERSATION_VERIFY status=verified header="Baby"
nameMatch=true`.

## 8. Fallback behavior

- Contacts permission not granted / `not_found` / `missing_phone` / no usable number → logged
  `WA_DIRECT_FALLBACK reason=…` → control drops to the existing `runWhatsAppCallNative` UI-search
  route (unchanged).
- `ambiguous` contact, or one contact with >1 distinct numbers → a clarification string is returned,
  **no call, no fallback** (BENSON asks; the user re-issues).
- Deep link fired but a later step failed (`WHATSAPP_DEEPLINK_FAILED` / `WHATSAPP_CONTACT_VERIFY_FAILED`
  / `CALL_*`) → the specific failure is **reported** (with the cause already in the native
  `WA_DIRECT_FAIL stage=…` log); it does **not** silently fall back to UI search (rule 8).

## 9. Privacy behavior

- Address book read **on-device only** (`expo-contacts` via `deviceContacts.ts`); nothing is sent
  to any cloud service, and no contacts enter any LLM prompt on this path.
- Only the **single resolved number** for the current call enters mission state; it is passed once
  to the native bridge and not persisted.
- Logs carry: requested `name`, resolver `status` + match `count`, and a **4-digit tail**
  (`WA_DIRECT_NUMBER_READY … tail=8957`) / masked phone (`WA_DIRECT_START phone=***8957`). No full
  numbers, no address-book dumps.
- `deviceContacts.ts` never caches or persists; every call re-reads the OS address book fresh.

## 10. ACC-WA-DIRECT-1 — device evidence (PASS)

**Starting state (uiautomator dump):** WhatsApp Chats tab, list scrolled ~60 rows down (12× swipe
in prep), `search-ish nodes = 0`, first visible chat "Alex USA". WhatsApp then backgrounded (Home);
BENSON Debug Panel foreground.

**Trigger (real production path):** Debug Panel → `suna pe Baby pe WhatsApp` → mission
`PREPARE_MESSAGE` / `voice_call` → `CONFIRM_PENDING type=governed` → `da` → `confirmActiveMission` →
`whatsappTool.placeCall("Baby")` → `tryDirectContactCall`.

**Log (`BENSON_AUDIO`, 09-09 10:50:29 → 10:50:35):**
```
WA_DIRECT_RESOLVE_START name="Baby"
WA_DIRECT_RESOLVE_RESULT status=resolved count=1
WA_DIRECT_NUMBER_READY name="Baby" tail=8957
WA_DIRECT_START phone=***8957 name="Baby"
WA_CALL_STATE state=CALL_STARTING contact="Baby"
WA_DIRECT_DEEPLINK_START
WA_DIRECT_DEEPLINK_RESULT launched=true
WA_DIRECT_CONVERSATION_VERIFY status=verified header="Baby" nameMatch=true digitsMatch=false
WA_DIRECT_CALL_BUTTON
WA_DIRECT_CALL_CLICK ok=true
WA_DIRECT_CALL_VERIFY success=true screen=true name="Baby" nameMatch=true
WA_DIRECT_END success=true step=CALL_VERIFIED elapsedMs=5580
WA_CALL_STATE state=CALL_ACTIVE contact="Baby"
WA_NATIVE_RESULT route=direct success=true step=CALL_VERIFIED elapsedMs=5581
WA_CALL_STATE state=CALL_ACTIVE tMs=14157 … tMs=28323
WA_CALL_STATE state=CALL_ENDING → CALL_ENDED         (ended by the tester, KEYCODE_ENDCALL)
```

- **No `WA_SEARCH_*`, no `WA_CHAT_SCROLL_*`, no `WA_NATIVE_START`** — the Chats list was never
  touched; the deeply-scrolled state was irrelevant.
- Resolved locally, unique match, deep link, conversation verified as "Baby", call button, **call
  screen verified showing "Baby"** — `CALL_VERIFIED` end-to-end in **5.58 s**.
- Post-call mic-hold armed + `watchWhatsAppCallLifecycle` running (`CALL_ACTIVE` heartbeats), and it
  correctly detected the manual end (`CALL_ENDING` → `CALL_ENDED`). No lingering telecom call
  afterwards.
- **Meets the CRITICAL PASS CONDITION (§15) in full.**

## 11. ACC-WA-DIRECT-2 / -3 / -4 / -5 — NOT_RUN

Blocked: ~2 min after the -1 call ended (while preparing -2) the device screen timed out and
auto-locked. `dumpsys trust` → `deviceLocked=1`, `isKeyguardShowing=true`; `wm dismiss-keyguard`,
statusbar collapse, wake + unlock swipes via adb all failed to dismiss the keyguard. This is a
lab-access limitation, not a code issue. The APK is installed; these tests run after the phone is
unlocked:
- **-2** (WhatsApp force-stopped): expected identical — the deep link is a fresh `startActivity` and
  does not depend on WhatsApp's prior state.
- **-3** (WhatsApp on Updates/Communities/Calls tab): expected identical — `FLAG_ACTIVITY_NEW_TASK`
  routes straight to the conversation regardless of the current WhatsApp screen.
- **-4** (ambiguous name): code path returns the `ambiguous` clarification string, **no call** — see §8.
- **-5** (unknown name): `resolveContact` → `not_found` → `WA_DIRECT_FALLBACK reason=contact_not_found`
  → existing UI-search route (which then fails cleanly for a truly unknown name) — **no random call**.

## 12. Regressions

- `runWhatsAppCallNative` and all WA-FIX-1/2/3 code: **byte-identical**, still present as the
  fallback. A named call with contacts permission now takes the direct route first; the UI-search
  route runs only on `{ handled:false }`.
- `placeCall`'s pre-existing behaviour (accessibility-ready guard, `WA_CONTACT_INPUT` trace,
  `USE_NATIVE_CALL_RECIPE` branch) unchanged downstream of the new gate.
- No other mission tool, executor, or subsystem touched. `READ_CONTACTS` was already
  manifest-declared and granted (no manifest change this round).
- Not device-verified for other flows this round (device locked after -1).

## 13. Remaining limitations

- **National-format numbers.** `sanitizeWaPhone` cannot repair a number stored without a country
  code (bare leading `0`); the deep link would then land wrong or on "not on WhatsApp" — caught by
  the conversation verify (`WHATSAPP_CONTACT_VERIFY_FAILED`), not silently called. Baby's number is
  stored international, so it worked.
- **WhatsApp profile name ≠ address-book name.** Verify matches the device `displayName` against the
  header; if WhatsApp shows a different profile name and the digits don't match either, it fails
  safe (no call) rather than calling.
- **-2..-5 unverified on device** (keyguard) — pending an unlock.
- The direct route needs contacts permission; if revoked it falls back to the (deep-scroll-fragile)
  UI-search route.

## CONFIRM

- source changed: `whatsappTool.ts` + 4 files in `benson-accessibility` (listed §1); `runWhatsAppCallNative` untouched
- gesture permissions / `canPerformGestures`: **unchanged (false)**
- coordinate taps in app code: **none** (deep link is a semantic Intent; prep-only `adb input` is test scaffolding)
- git / prebuild: **NO**
- real WhatsApp call placed: **YES — one, to "Baby", for ACC-WA-DIRECT-1; verified `CALL_STARTED`; ended by the tester (~30 s)**
- VERIFIED `CALL_STARTED` reached from the deeply-scrolled state: **YES**

---
---

# COMPLETION TESTS (ROUND_WA_FIX_4_COMPLETION_TESTS) — phone unlocked, no source changes

`source modified during completion tests: NO` · git: NO · prebuild: NO · rebuild: NO (same APK, `lastUpdateTime 10:49:22`).

Note: `am force-stop com.benson.butler` (used once while resetting between attempts) killed the
accessibility-service binding and OxygenOS did not auto-rebind it — one attempt failed with
`REPLY: Serviciul de accesibilitate s-a oprit`. Recovered by toggling
`settings put secure enabled_accessibility_services`. Subsequent attempts used `am start` only.

## ACC-WA-DIRECT-2 — COLD WHATSAPP — PASS

Starting state: `am force-stop com.whatsapp` (`pidof` empty — WA cold); BENSON Debug Panel foreground.
Command `suna pe Baby pe WhatsApp` (typed, IME `KEYCODE_ENTER` submit via `onSubmitEditing`), then `da`.

```
WA_DIRECT_RESOLVE_START name="Baby"
WA_DIRECT_RESOLVE_RESULT status=resolved count=1
WA_DIRECT_NUMBER_READY name="Baby" tail=8957
WA_DIRECT_START phone=***8957 name="Baby"
WA_DIRECT_DEEPLINK_START
WA_DIRECT_DEEPLINK_RESULT launched=true
WA_DIRECT_CONVERSATION_VERIFY status=verified header="Baby" nameMatch=true digitsMatch=false
WA_DIRECT_CALL_BUTTON
WA_DIRECT_CALL_CLICK ok=true
WA_DIRECT_CALL_VERIFY success=true screen=true name="Baby" nameMatch=true
WA_DIRECT_END success=true step=CALL_VERIFIED elapsedMs=6402
WA_CALL_STATE state=CALL_ACTIVE contact="Baby"
WA_NATIVE_RESULT route=direct success=true step=CALL_VERIFIED elapsedMs=6402
```

- No `WA_SEARCH_*`, no `WA_CHAT_SCROLL_*`, no `WA_NATIVE_START`. Deep link from a fully-cold WhatsApp
  → exact conversation, verified, call, **`CALL_STARTED` verified** in 6.40 s.
- Confirms the direct route is independent of WhatsApp's prior state (cold-start = ACC-WA-DIRECT-2 intent).

## POST_CALL_WAKE — FAIL (original bug reproduced)

After ACC-WA-DIRECT-2 the call ran ~37 s then the far end / `mobi.drupe.app` took over; the native
watcher logged:
```
11:12:47.432  WA_CALL_END_DETECTED tMs=37175 fg=mobi.drupe.app
11:12:47.432  WA_CALL_STATE state=CALL_ENDING
        ── then NOTHING from BENSON for the next 3+ minutes ──
11:14:08      SERVICE_START_COMMAND action=default returning=START_STICKY   (foreground service killed + revived)
11:14:08      SERVICE_FOREGROUND_STARTED
11:15:40      SERVICE_START_COMMAND … START_STICKY   (again)
```

- **`WA_CALL_STATE state=CALL_ENDED` was never logged.** `watchWhatsAppCallLifecycle` stopped between
  `CALL_ENDING` (goneStreak=1) and the `goneStreak >= 3` break — the `serviceScope` coroutine did
  not complete. Consistent with the foreground service being killed right after the call
  (`SERVICE_START_COMMAND … START_STICKY` twice) — process churn from the drupe overlay / OxygenOS.
- Therefore: `whatsappCallMicHoldUntilMs` was **not** cleared (only its 180 s failsafe would, ~11:15:10),
  `callEndedReturnPending` was **not** set, the AUTO-RETURN Intent was **not** fired.
- BENSON's Activity stayed backgrounded behind `com.whatsapp/.Conversation`; the RN JS thread was
  frozen (zero `BENSON_AUDIO`/`ReactNativeJS` output for 3+ min); the wake loop never resumed.
- `MIC_BLOCKED reason=whatsapp_call_live` / `WAKE_MODE_RESTORED reason=call_ended` / `WAKE_SCAN_START`
  — none observed. Wake could not be tested by voice (no way to speak to the device from the
  harness); the log evidence alone shows the wake loop was **not re-armed**.

**Exact last successful lifecycle stage:** `WA_CALL_STATE state=CALL_ENDING` (11:12:47). Everything
after it (`CALL_ENDED`, hold release, AUTO-RETURN, wake re-arm) did not happen.

`POST_CALL_WAKE = FAIL.` Not patched. This is the lifecycle bug the diagnosis anticipated
(`ROUND_WA_DIAG_REPORT.md`): `watchWhatsAppCallLifecycle` on `serviceScope` is fragile; when the
service is killed after a call it never clears the hold or brings BENSON back, and the 180 s
failsafe is the only (slow) release.

## ACC-WA-DIRECT-3 / -4 / -5 — NOT_RUN
Round interrupted by (a) the POST_CALL_WAKE failure and (b) a follow-up diagnostic round
(`ROUND_MISSION_DIAG` — a stale WhatsApp-Baby confirmation intercepting a later YouTube command,
almost certainly a downstream consequence of this same post-call stall leaving mission/confirmation
state un-cleared). -4 (ambiguous → clarify, no call) and -5 (unknown → `not_found` → controlled
fallback, no random call) remain code-verified only.

## Completion summary
- unintended calls: **0** (2 calls placed, both the intended "Baby" test; both `CALL_STARTED` verified; both ended)
- global WhatsApp search used by DIRECT tests: **NO**
- Chats scrolling used by DIRECT tests: **NO**
- `CALL_STARTED` verified for -1 and -2: **YES**
- `CALL_ENDED` cleanly observed: **-1 YES; -2 NO** (`CALL_ENDING` only — see POST_CALL_WAKE)
- source modified during completion tests: **NO**
