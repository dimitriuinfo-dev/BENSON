# BENSON Device Recovery Test — 2026-09-14

Run strictly in this order. **STOP at the first FAIL — do not continue upward.** Only real-device behavior counts as PASS; BUILD SUCCESSFUL/tests/code review do not.

Build/install once at the start: `gradlew :app:assembleRelease` → `adb install -r` → `adb shell monkey -p com.benson.butler -c android.intent.category.LAUNCHER 1` → `adb logcat -c` → start a background capture (`adb logcat -v time ReactNativeJS:I BENSON_AUDIO:I *:S`).

| # | COMMAND | EXPECTED RESULT | LOG MARKERS | PASS CRITERIA | FAIL STOP CONDITION |
|---|---|---|---|---|---|
| 1 | (none — passive) | Process alive, no crash, service bound | `adb shell ps`, `dumpsys activity services` show `BensonForegroundService` `isForeground=true`; no `FATAL`/`AndroidRuntime` | All present | Any crash/missing service → stop, do not proceed |
| 2 | (none — passive) | Mic ownership sane at idle | `NWW_HEALTH micOwner=WAKE` at rest, no stuck `COMMAND_STT`/`TTS`/`CONFIRMATION_STT` | `micOwner` returns to `WAKE` between commands | Stuck non-WAKE owner for >45s with no watchdog recovery → stop |
| 3 | "deschide calculatorul" | BENSON asks "Am găsit Rechner. O deschid?" | `CONFIRM_LISTEN_ARM ... source=disambiguation` (NOT the old generic doStartListening path) | Confirmation listener armed via the new source tag | If `CONFIRM_LISTEN_ARM` never appears (still generic path) → stop, Layer 2 fix didn't engage |
| 4 | "da" (reply to #3) | Calculator opens | `CONFIRM_LISTEN_STT_RESULT text="da"` (clean, no self-echo garbage), then app-open success, `micOwner` returns to WAKE | Calculator physically visible on screen | Garbled transcript, or WAKE_NO_MATCH discarding the reply, or Calculator doesn't open → stop, report exact log |
| 5 | Repeat #3+#4 two more times, no restart | Calculator opens each time | Same markers as #3/#4, 3 total clean cycles | 3/3 | Any of the 3 fails → stop |
| 6 | "deschide calculatorul" → "nu" | Calculator does NOT open; mission closes cleanly | `CONFIRM_LISTEN_STT_RESULT text="nu"` or similar NO-pattern; disambiguation cleared, no stale state | Calculator stays closed, BENSON returns to idle/listening | Calculator opens anyway, or BENSON hangs → stop |
| 7 | Immediately after #6, new unrelated command (e.g. "cât e ceasul") | BENSON hears and answers it | Normal `MISSION_INPUT`/`BRAIN_INTENT` flow | Heard and answered without restart | Not heard → stop (Layer 2 regression) |
| 8 | "sun-o pe Baby pe WhatsApp" (contact resolution only, let it get to the confirmation) | Resolves Baby unambiguously (assuming Baby is an unambiguous real contact) | `CONTACT_AMBIGUOUS_CANDIDATES` should NOT fire for Baby, or if it does, candidates are real and plausible | Confirmation prompt names Baby correctly | Wrong/unrelated candidates → stop, this is Layer 4 |
| 9 | "sun-o pe Hannah pe WhatsApp" (contact resolution only) | Resolves Hannah unambiguously or asks a real, plausible disambiguation | Same as #8 for Hannah | Confirmation prompt correct or real ambiguity only | Wrong/unrelated candidates ("Peter Pane...") → stop |
| 10 | Confirm #8 ("da") | WhatsApp opens, header verified Baby, voice call placed | `WA_DIRECT_RESOLVE_RESULT`/native call log, header match, call screen live | Real call placed to Baby | `status=ambiguous` for a real unique contact, or call not placed → stop, note whether this is Layer 4 (resolver) or Layer 5 (call) |
| 11 | Confirm #9 ("da") | Same as #10 for Hannah | Same | Real call placed to Hannah | Same as #10 |
| 12 | "scrie-i lui Baby că ajung la opt" | Opens Baby, verifies header, types exact text, asks "Îl trimit?" — **do not confirm yet** | `WA_MSG_ROUTE_SELECTED route=DIRECT_WRITE`, Phase A success | Text visible in WhatsApp compose field, matches exactly | Wrong contact, wrong text, or "Nu am putut deschide WhatsApp" → stop (Layer 6) |
| 13 | "vreau să trimit un mesaj lui Hannah" → "Ce mesaj?" → "ajung la opt" | Same mission continues; Hannah opens, header verified, typed exactly, "Îl trimit?" | `WA_REPLY_CONTEXT_AFTER state=RESUMING`, then Phase A success (NOT the old "Scrierea de mesaje... dezactivată" failure) | Reply consumed as message body, not routed to contact resolver/new command | Old disabled-message error reappears, or reply misrouted → stop (Layer 7 regression) |
| 14 | From #12 or #13's pending "Îl trimit?", say "nu" | Message NOT sent; mission closes cleanly | No `SEND_CLICK`; state returns to idle | Confirmed not sent | Sent anyway → stop immediately (send-safety regression) |
| 15 | Repeat a fresh one-shot WRITE to Baby, then "da" | Sends exactly once | `SEND_CLICK ok=true` → `SENT_VERIFIED`, exactly one send log per mission | 1/1 correct send, message appears once in the chat | No send, or a duplicate send → stop |
| 16 | Repeat step 15 four more times (5 total) | Same as 15 each time | Same | 5/5 | Any failure → stop, report which attempt |
| 17 | "scrie-i lui Baby că ajung la opt" → then "nu Baby, Hannah" | Same mission continues; Hannah opens fresh, header verified, SAME text retyped, "Îl trimit?" asked again | `WA_CONTACT_CORRECTION missionId=... newContact="Hannah"`, fresh Phase A success for Hannah | Correct contact swap, message preserved, no new mission created | Cancels instead of correcting, sends to Baby, or crashes → stop (this is the brand-new, untested Layer 9 — expect it may need a real fix here) |
| 18 | Repeat #17 in reverse ("scrie-i lui Hannah că ..." → "nu Hannah, Baby") | Same, opposite direction | Same | Correct | Same as #17 |
| 19 | End an active WhatsApp call (from #10/#11), background/foreground BENSON | Mic recovers, normal wake listening resumes without restart | `micOwner` returns to WAKE, no stuck CALL hold past its window | Recovered | Stuck mic hold → stop |
| 20 | Screen off, wait, say "Benson" | Wake fires and BENSON becomes reachable (lowest priority — known open gap, may still fail) | `WAKE_STT_RESULT`/`WAKE_MATCH_EXACT` on native side; JS-side actual consumption | Best case: full wake-to-JS handoff works. Otherwise: confirm exactly where it breaks (native fired but JS never received, per the paused investigation) | This layer was explicitly NOT touched tonight — a fail here is expected/known, do not treat as a new regression, just record it |

## After any FAIL
1. Stop testing upward immediately.
2. Report: which numbered test, exact log lines, exact observed vs. expected.
3. Do not attempt a same-session fix-and-retest without confirming with the user first — per the No-Regression Law, diagnose before touching code.
4. Once fixed and re-verified at that layer, re-run every lower-numbered test before resuming upward.

## Promotion rule
A layer is promoted from IMPLEMENTED_ONLY to DEVICE_PASS only when its corresponding test(s) above pass on the real device. Update `BENSON_RECOVERY_MATRIX_2026-09-13.md`'s STATUS column accordingly as each layer clears.
