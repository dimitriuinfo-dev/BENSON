# BENSON — handover, 2026-09-20

## Branch / commit / build

- Branch: `wip_2026_09_18_snapshot`.
- Last commit before this checkpoint: `cadcc65` (`DEVICE_PASS_BUBBLE_STABLE_VISIBILITY_AND_SMALL_ARCS`).
- This session's checkpoint commit/tag: see the report the assistant printed when it ran — do not re-derive it from memory, `git log`/`git tag` are authoritative.
- Installed/tested build: `versionName=1.0.0`, `lastUpdateTime=2026-09-20 16:51:03`, APK SHA-256 `4dbf4a215e9c5f1bffc854a10e8dc82e6f8bd550d0685ff7ef386aa73ed980fa`. Verified: no tracked source file has an mtime after the APK build — the checkpointed source matches this exact APK.

## What works (verified on-device today, see `docs/device-tests/2026-09-20-results.md` for full evidence)

- Wake word "Benson" detected and delivered live even after OxygenOS kills BENSON's Activity while backgrounded — was completely broken before today's fix, now ~9–15ms wake-to-command-listening latency.
- "Benson, deschide Calculatorul" from the background, untouched, including a real two-round disambiguation confirmation.
- "Benson, sun-o pe mama pe WhatsApp" from the background — confirmed voice, WhatsApp call verified genuinely active (not just clicked), ~21s end to end.

## What's still broken or unverified — do not assume fixed

- Continuous dialog (greeting → time → location) is NOT validated as a chain; only an isolated greeting has succeeded.
- Wake-word mic sensitivity at normal (50–70cm) distance is unconfirmed — one 50cm attempt failed outright (VAD barely crossed threshold, empty transcript). Every other test today was spoken from ~15cm.
- Confirmation-listen has a measured ~580ms dead zone between "listening state starts" and "microphone actually recording" (`CONFIRM_LISTEN_START` → `CONFIRM_LISTEN_AUDIO_ACQUIRE`). Diagnosed, not fixed.
- Mic release after an `ERROR` reply: code fix is in the 16:51:03 build (`postMissionWindowEligible()` now treats `ERROR` like `DONE`), but no live retest has actually reproduced an ERROR reply to confirm it works.
- A real, reproduced bug: a stale mission result (an old async foreground-check from a Calculator-open command issued ~6 minutes earlier) resumed and displayed itself as current the instant an unrelated WhatsApp call ended, showing a misleading "still trying to open Rechner" message against the wrong foreground app. Root cause understood (delayed mission result rendered without a staleness check against the current turn/mission), fix not yet written.
- Ending a WhatsApp call via "Benson, închide apelul" is user-reported as working from the phone but has no confirming log evidence in anything captured today — the call-end evidence found was a passive watcher (`via=self_heal`), not a matched voice command dispatch.
- Generic on-screen-element selection (e.g., Spotify "Best of INNA", a title visible in search results) does not exist in the codebase — this is the still-unbuilt "BENSON HAND GENERIC" feature, not a bug.
- Netflix flow (open → search "Yellowstone" → results read back → "1883" opened) and playback itself: reported by the user from the phone; not present in any log this session captured, so not independently verified here.

## Next test, in order (per explicit user instruction)

1. Fix and reverify the stale-mission-result display bug (need a turn/mission-identity check before rendering a delayed result).
2. Verify wake recovery after a WhatsApp call ends, without manually reopening BENSON.
3. Validate the greeting → time → location dialog chain (Series B).
4. Then, and only then, start the generic visible-result selection work (Spotify "Best of INNA" / BENSON HAND GENERIC).

Confirmation-listen latency, ERROR-recovery regression test, and mic distance sensitivity remain open but were explicitly deprioritized behind the above by the user.

## Uncommitted work / scope notes

At checkpoint time there were no further edits queued beyond what's in this commit — the working tree matched the tested APK exactly. If you find modified-but-uncommitted files when resuming, treat them as *new* since-checkpoint work, not part of this evidence.
