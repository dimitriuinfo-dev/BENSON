# RESUME — BENSON Final Build (Items 0-5)

Last updated: 2026-07-15, ~21:00. Session status: **git history cleaned up (see below); live voice
testing started, found BENSON completely unresponsive to the wake word ("silent the whole time" —
user's words); traced two real, fixed infrastructure bugs; the original silence bug is NOT yet
explained or fixed. Device disconnected from adb (USB) before this could be re-tested — user had to
step away. Everything below is accurate as of the last tool-verified state.**

## What actually happened this session, in order

1. **Git cleanup (done, verified).** `git status` at session start showed ~1,137 lines of
   uncommitted changes on top of `78e11a8` (the prior session's last commit) plus a pile of
   untracked files. Traced this to: those specific files (`BensonForegroundService.kt`,
   `claudeAgent.ts`, `theme.ts`, etc.) were last committed in `33ca460`, well before the prior
   items-0-4 session's base commit — they'd been sitting uncommitted since roughly BENSON 5
   through BENSON 36 in `CHANGELOG.md` (~2 weeks of already-shipped, already-tested work, never
   git-committed). Committed it as `3342a38`, excluding `modules/*/android/build` (Gradle output —
   added to `.gitignore`) and 6 ad-hoc debug screenshots at repo root (left untracked, not source).
   **Tree is clean now except those 6 screenshots.**

2. **Live test attempt: Item 0 (Accessibility resilience).** Confirmed device unlocked, Metro
   started, BENSON brought to foreground, no crashes in logcat. Disabled Accessibility Service via
   `adb shell settings put/delete secure ...` (same non-sensitive method as the prior session).
   Asked the user to say "sună-l pe [contact] pe WhatsApp" to trigger the test.
   **Result: BENSON never spoke, never reacted — "silent the whole time" per the user.** Live
   logcat capture (2700+ lines spanning the whole attempt) showed **zero** lines from either the
   `ReactNativeJS` or `BensonHotword` tags — the wake-word pipeline did not engage at all, not even
   an error. The user also reported the seal's ambient ring rotation wasn't animating (only the
   TTS "speaking" waveform moved), and `dumpsys cpuinfo` showed the process at 40% CPU with 9m21s
   of accumulated CPU time despite being "idle" — both suspicious but not yet explained either.

3. **First hypothesis (WRONG — corrected below).** Found `android/` (the generated native project,
   gitignored) had a `LastWriteTime` of **2026-07-09**, six days stale, and a `find android -iname
   "*overlay*"` turned up nothing related to `benson-overlay`. Concluded the generated native
   project predated several native modules and theorized the installed APK was missing the
   wake-word/overlay code entirely. **This was wrong**: `find android -iname "*overlay*"` only
   searched the top-level generated `android/` folder, not `modules/benson-overlay/android/` where
   that module's own source and build output actually live — Expo's autolinking references
   modules by their own Gradle subproject path, it doesn't copy them into `android/`. A later,
   correct test (unzip each APK's `classes*.dex`, `grep` the decompressed bytes — **not** the raw
   compressed `.apk`, which is why an even-earlier same-style check falsely showed 0 for
   everything) proved `BensonHotword`/`BensonBubbleService`/`BensonWatchdogReceiver` were present
   **identically** in both the old installed APK and a fresh rebuild. The native code was never
   missing. Flagging this explicitly so the next session doesn't re-trust the "stale android/
   folder" theory — it's ruled out.

4. **Real (separate, confirmed) bug found and fixed: `android/local.properties` was empty.**
   Unrelated to the silence bug, but a genuine break: after running `expo prebuild --clean` (done
   while chasing hypothesis #3, before it was ruled out), the regenerated `local.properties` had no
   `sdk.dir`, which fails any Gradle build outright ("SDK location not found"). Fixed by writing
   `sdk.dir=C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk` to
   `android/local.properties` (this file is gitignored/machine-local by design, nothing to commit).
   A full clean rebuild then succeeded (`BUILD SUCCESSFUL in 1m 57s`,
   `android/app/build/outputs/apk/debug/app-debug.apk`, confirmed via the corrected dex-string
   check to contain the same wake-word/overlay code as the previously-installed build). **This APK
   was never installed** — the device dropped off `adb` (USB) at the exact moment `expo
   run:android` tried to install it, and did not reconnect before the user had to leave.

## The real open bug — BENSON does not respond to "Benson" at all

Not explained yet. What's confirmed:
- Not a missing-code problem (ruled out above — the wake-word/hotword code is compiled in, in both
  the currently-installed APK and the freshly rebuilt one).
- Not a crash (pid stayed the same throughout, zero `FATAL EXCEPTION`/`AndroidRuntime` in logcat).
- Not a placed/confirmed call — verified directly with the user: no active/ringing WhatsApp call at
  any point.
- The foreground service notification's `posttimeElapsedMs` implied roughly 22+ hours of
  continuous uptime for that service instance — much longer than this session, and seemingly
  longer than the app's own `lastUpdateTime` (2026-07-15 19:15:10) would suggest. Not yet
  reconciled. **Leading hypothesis for next session**: the long-lived process/service never
  received a fresh `onStartCommand` (which is what actually calls `startHotwordLoop()`) because it
  was already running from before any of this session's testing started — a full `am force-stop
  com.benson.butler` followed by a cold `am start` (not just bringing the existing task to front)
  would rule this in or out, and is the single highest-value next diagnostic step, before touching
  any more code.
- Also unexplained: rings not animating (ambient rotation), 40% idle CPU. Could be the same root
  cause as the silence (e.g. some effect never firing) or could be unrelated — don't assume either
  way without checking.

## Next steps when the user is back

1. Reconnect the device (`adb devices -l` — it was gone at session end, likely just USB/cable).
2. `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` — installs the already-built,
   already-verified APK (no rebuild needed unless more source changes happen first).
3. **`adb shell am force-stop com.benson.butler`**, then a cold `adb shell am start -n
   com.benson.butler/.MainActivity` — this is the untested diagnostic step above. Watch `adb logcat
   -s BensonHotword:V ReactNativeJS:V` live from the moment of that cold start.
4. If the hotword loop starts logging bursts (`hotword burst raw transcript(s): ...` every 1-3s),
   the long-lived-process theory was right — the silence bug is a process-lifecycle issue, not a
   code bug, and the standing PENDING voice tests can resume immediately.
5. If it's still silent even from a cold start, this needs real code-level tracing next
   (`SpeechRecognizer.isRecognitionAvailable()`, mic permission at runtime, whether
   `startBackgroundService()` in `app/index.tsx` is even being reached — add temporary logging if
   needed) — **not** more guessing from outside.
6. Once wake-word response is confirmed working, resume the original PENDING list from
   `FINAL_BUILD_REPORT.md` (contacts search, native call + denial fallback, WhatsApp call, organism
   states, full regression) exactly as before — none of that changed this session.

## Standing safety rule (unchanged all session)

Never place, confirm, or terminate a real phone/WhatsApp call, and never answer BENSON's
confirmation gate via adb — the human is the confirmation gate. Never attempt to unlock the
device's keyguard. Verified this session: no call was placed or active at any point.
