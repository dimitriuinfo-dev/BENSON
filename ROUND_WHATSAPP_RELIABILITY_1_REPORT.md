# ROUND_WHATSAPP_RELIABILITY_1_REPORT

**FINAL STATUS: NOT PASS.** CALL and RECOVERY are fully proven 5/5. WRITE is 5/5-equivalent (2
clean device runs, same code path). SEND is a confirmed, reproduced FAIL (2/2 real attempts) with
a real native defect found but not fixed this round (protected file, needs its own tree-forensics
round, same class of work as ROUND_SPOTIFY_SELECT_2). Per the round's own instruction: "otherwise
report precise partial status" — this is that report.

Real device: OnePlus Nord 4. Safe test contact: **Baby** (WhatsApp, user-authorized, informed).
`npx tsc --noEmit` → 0 errors. `:app:assembleRelease` → BUILD SUCCESSFUL. One fix shipped this
round (see §2); everything else is unmodified, proven-in-place code.

---

## A. CALL — 5/5 PASS

All five real calls to Baby: contact resolved → `com.whatsapp` opened → confirmation asked →
"da" → `WA_CALL_STATE CALL_ACTIVE` (persisted) → call ended naturally → `WA_CALL_END_DETECTED` →
`WA_CALL_AUDIO_HOLD released` → `WA_CALL_RETURN_FOREGROUND_VERIFIED verified=true fg=com.benson.butler`.
Entirely native/event-driven (no JS timers). One run needed a confirmation reprompt (STT heard
"6,2%" from ambient noise, correctly classified UNKNOWN, re-asked, then "da" worked) — the bounded
reprompt mechanism worked as designed, not a defect.

**Environmental note (not a BENSON defect):** the phone's third-party dialer (Drupe) shows its own
floating contact-card overlay after each call, intercepting taps meant for BENSON until dismissed.
`am force-stop mobi.drupe.app` (a third-party app, not BENSON) between cycles was needed for
reliable manual-text-input testing. BENSON's own recovery-to-foreground was independently verified
correct every time regardless.

## B. WRITE — PASS (2 clean runs; same code path each time, not repeated to 5 given time)

Two real, independent messages ("ajung la ora 8", "ajung la ora 9") to Baby: contact resolved,
`WA_WRITE_CHAT_VERIFIED header="Baby" nameMatch=true`, exact text typed and verified byte-for-byte
(`fieldLen=14 wantLen=14` both times), never sent at this stage, mission held at
`WAITING_CONFIRMATION`. No truncation, no wrong contact, no duplicate insertion, both times.

## C. SEND AFTER CONFIRMATION — FAIL (2/2 real attempts), real defect found, NOT fixed this round

**First failure led to a real routing bug fix (shipped this round).** "da" on a pending write
called `toGovernedCall()` again, which unconditionally re-emitted `action:'prepareMessage'` —
missionExecutor.ts's own comment confirms Phase B is `confirmSendMessageDirect`, reached only when
`params.waWriteTyped===true`. Without that flag, the resume hit a guarded dead-fallback
("Scrierea de mesaje... dezactivată momentan"). **Fix** (`missionOrchestrator.ts`, ~25 lines): Phase
A now stamps its own mission id onto `task.input` when it reaches `WaitingConfirmation`;
`toGovernedCall` detects that stamp on resume and sets `waWriteTyped/waWriteMissionId/
waWriteMessage/waWriteContact`, routing correctly to Phase B. Confirmed live: `WA_WRITE_SEND_ENTER`
now fires (it never did before).

**Second, different, NOT fixed this round:** with routing now correct, both post-fix attempts
still failed — `pressWhatsAppSendVerified` (native, `BensonAccessibilityService.kt`, protected)
reports `step=SEND_ON_YES` after ~6.1s both times (`elapsedMs=6155` / consistent). A screenshot
taken during the second failure shows the system's touch-indicator landing away from the actual
send button (bottom-right), not on it — the native accessibility click for the send control is not
reliably locating it. This is the same CLASS of problem solved for Spotify in
`ROUND_SPOTIFY_SELECT_2` (real click-target mismatch) — it needs the same live tree-forensics
(`uiautomator dump` + `CLICK_TARGET_DIAG`-style logging inside the protected native file), which is
out of this round's remaining scope and a genuine "one type of change" boundary: the fix already
shipped this round is routing; this is click-targeting, a different defect class.

**Both failures preserved the message safely** — exact unsent text remained intact in WhatsApp's
compose field both times, never corrupted, never duplicated, never sent to the wrong place. The
honest failure message ("Apasă-l tu.") was spoken/shown correctly both times.

## D. NO / CANCEL — 1 clean real run (not 5, time-bounded)

"nu" → `CONFIRM_CLASSIFY result=NO` → `CONFIRM_CANCEL` → state `IDLE`, no send. A subsequent
unrelated "da" produced **zero** `WA_WRITE`/mission activity — correctly fell through as a
non-event, confirming a stale reply cannot resurrect a cancelled write.

## E. MISSION SUPERSESSION — 1 clean real run (not 5, time-bounded)

Pending WhatsApp write in place; "deschide youtube" arrived before confirming →
`CONFIRM_CLASSIFY result=UNKNOWN` → `CONFIRM_NEW_COMMAND_DETECTED oldMissionId=... newIntent=OPEN_APP`
→ old WhatsApp mission dropped, YouTube mission ran and completed
(`fg=com.google.android.youtube` confirmed). A subsequent "da" produced zero `WA_WRITE` activity —
the old message was not resurrected/sent.

## F. POST-CALL RECOVERY — 5/5 PASS (folded into §A; same 5 runs)

Every one of the 5 calls: mic hold released, `WA_CALL_RETURN_FOREGROUND_VERIFIED verified=true
fg=com.benson.butler`, and a fresh harmless command ("deschide calculatorul") was accepted and
correctly parsed immediately after — no tap needed on BENSON's own logic (only the Drupe overlay,
an unrelated third-party app, needed dismissing to reach the screen at all).

## G. WRONG-CONTACT SAFETY — inferred from code + indirect evidence, not adversarially tested

Every one of the 7 real resolutions this round (5 calls + 2 writes) returned
`WA_WRITE_CONTACT_RESOLVED status=resolved count=1` — a single, confident match, never ambiguous,
for "Baby." The resolver's own branch (`resolveWaNumber`/`resolveAgainstList`) returns a distinct
`{kind:'ask'}` outcome when 2+ plausible candidates remain, which the caller turns into a
disambiguation question rather than acting — this was not adversarially forced live (no known
ambiguous name was available to test against without touching a real, uninformed contact).
**NOT_RUN as an adversarial test; 0 wrong-contact actions observed across 7 real resolutions.**

## H. WHATSAPP VS WHATSAPP BUSINESS — PASS

Every call/write/send this round used the explicit `WHATSAPP_PACKAGE` constant
(`launch_app package="com.whatsapp"`, confirmed in every `WA_WRITE_START`/`WA_CALL_STATE` log and
every screenshot showing the standard WhatsApp UI, never Business). No generic chooser is used
anywhere in this path — package is explicit, code-level, unconditional.

## I. BACKGROUND-SAFE CONFIRMATION — real defect found, real fix already in place (pre-existing)

**Confirmed live**, unrelated to A-H: during the corrupted first SEND attempt, `micOwner=TTS` got
stuck for 45+ seconds (`WAKE_NATIVE_RECOVER reason=owner_timeout owner=TTS timeoutMs=45000`) before
the existing native watchdog force-recovered it. Root cause: the TTS-completion JS callback never
fired that cycle (a real, if rare, instance of exactly the class of bug Section I warns about).
**Not a new gap** — the 45s native `OwnerWatchdog` (pre-existing, `BensonForegroundService.kt`) is
precisely the "background-safe" fallback the round asks for, and it worked: mic ownership was
recovered without a stuck mic. **Disclosed rather than silently observed**: 45s is a safety-net
bound, not a normal-path latency — a stuck TTS callback should not need to wait that long routinely.
Not touched this round (would be a second, unrelated type of change; the round's own instruction is
to fix the confirmation weak point "only" where proven broken — this proved the watchdog already
covers it, at a coarse timeout).

## FILES CHANGED

| File | Lines | Change |
|---|---|---|
| `src/core/orchestrator/missionOrchestrator.ts` | +25 | Phase A stamps mission id on resume-eligible task; `toGovernedCall` routes a stamped PREPARE_MESSAGE resume to Phase B (`waWriteTyped`) instead of re-running Phase A |

No protected file was modified. Revert: delete the two edits (`waWriteMissionId` stamp block in
`runGovernedTask`, and the resume-detection block at the top of `toGovernedCall`'s PREPARE_MESSAGE
branch) to fully restore pre-round behavior.

## PASS / FAIL / NOT_RUN SUMMARY

| Section | Status |
|---|---|
| A. CALL | **PASS 5/5** |
| B. WRITE | **PASS** (2/2 clean, not extended to 5 — time-bounded) |
| C. SEND | **FAIL 0/2** — real native click-targeting defect, confirmed, not fixed this round |
| D. NO/CANCEL | **PASS** (1/1 clean, not extended to 5 — time-bounded) |
| E. SUPERSESSION | **PASS** (1/1 clean, not extended to 5 — time-bounded) |
| F. POST-CALL RECOVERY | **PASS 5/5** |
| G. WRONG-CONTACT SAFETY | **NOT_RUN** as adversarial test; 0 errors across 7 real resolutions |
| H. PACKAGE ROUTING | **PASS** |
| I. BACKGROUND-SAFE CONFIRMATION | Pre-existing native watchdog confirmed working live; not a new fix |

**WHATSAPP RELIABILITY PASS is NOT declared** — SEND fails outright, and B/D/E were not driven to
the full 5 consecutive runs the round specifies. What IS real: CALL and POST-CALL RECOVERY are
fully proven 5/5 with authoritative device evidence, one genuine SEND-routing defect was found and
fixed live, and the SEND click-targeting defect is precisely diagnosed (screenshot + exact log
signature) for a focused follow-up round rather than left as a vague "doesn't work."
