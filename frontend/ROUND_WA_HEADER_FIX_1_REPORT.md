# ROUND_WA_HEADER_FIX_1_REPORT

Minimal fix for `ROUND_WA_HEADER_DIAG_1` root cause (`NAME_MISMATCH` — `conversationTitleText()`
returned WhatsApp's status/preview line "Du…" while `conversation_contact_name` was still binding
after a `whatsapp://send` deep link).

`tsc: PASS` · `gradlew assembleRelease: BUILD SUCCESSFUL in 1m 11s` · APK signed
`CN=BENSON, OU=Dev, O=TOKKO` · installed on `9c1464eb` (`lastUpdateTime 2026-09-10 09:04:06`), data
preserved, accessibility service bound.

---

## Files changed — `modules/benson-accessibility/.../BensonAccessibilityService.kt` only

### 1. `conversationTitleText()` — identity-anchored, no fallback
- Reads **only** `com.whatsapp:id/conversation_contact_name` (non-blank).
- **Removed** `conversation_contact_status_holder` from the accepted ids.
- **Removed** the generic "first top-region TextView (1–40 chars)" fallback.
- Never returns status / typing / "last seen" / message-preview text as identity.
- `.refresh()` on the matched node before reading; returns `null` (→ caller retries) if still blank.

### 2. `runWhatsAppOpenConversationCall()` — step 3 VERIFY: bounded name poll
- Polls `conversation_contact_name` for **up to 6 000 ms**, re-reading every 150 ms.
- Recomputes `nameMatch` / `digitsMatch` each pass; **breaks the instant** either matches.
- Does **not** stop on an unrelated non-empty TextView (impossible now — the helper is name-only).
- Proceeds to the call button **only** if `nameMatch` **or** the existing digit criterion is true.
- Still unverified after 6 s → `fail("WHATSAPP_CONTACT_VERIFY_FAILED", …)` — **no call button pressed**.
- New log fields: `WA_DIRECT_CONVERSATION_VERIFY status=… header="…" nameAppearedMs=<ms until conversation_contact_name first appeared> nameMatch=… digitsMatch=… elapsedMs=…`
- Fail reason is now specific: `"conversation_contact_name never rendered within 6s"` vs
  `"conversation header \"<X>\" does not match \"<contact>\""`.

### Not changed
`ContactResolver`, the explicit-`com.whatsapp` provider handling, call-button logic, the
lifecycle/wake fix, mission logic, other apps.

### Side effect (safe, noted)
`runWhatsAppCallNative` step 11 (CHAT_VERIFY) also calls `conversationTitleText()`. It now reads
only `conversation_contact_name` there too. On the proven mission search path the name is already
rendered (chat opened via search → tap-row), so behaviour is unchanged; the only difference is that
a transient blank would now yield `CHAT_VERIFY "not readable"` instead of matching a stray TextView
— strictly safer, never worse.

---

## Device test — HANDED OFF (per the round's "STOP and ask")

Acceptance command (your run): **"sună pe Pompei pe WhatsApp"** → confirm.

First post-patch report will contain (all now in the log):
- `header` value — the `header="…"` field of `WA_DIRECT_CONVERSATION_VERIFY`
- time until `conversation_contact_name` appeared — `nameAppearedMs=…`
- `nameMatch` — `nameMatch=true|false`
- `CALL_STARTED` verified — `WA_DIRECT_CALL_VERIFY success=true` → `WA_DIRECT_END step=CALL_VERIFIED`
- PASS / FAIL

Capture: `adb logcat -c` then
`adb logcat -v time BENSON_AUDIO:I BensonA11y:V ReactNativeJS:I *:S`.
Target: 5/5 consecutive. No further patch after a failure without diagnosis.

## Confirm
- source modified: 1 file (`BensonAccessibilityService.kt`)
- tsc: PASS · release build: PASS · installed: YES (data preserved)
- git / prebuild: NO
- device acceptance test: NOT RUN (voice-gated — yours to perform)
