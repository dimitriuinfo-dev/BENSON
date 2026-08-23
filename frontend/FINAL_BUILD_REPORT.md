# BENSON Final Build Report

**Status: DRAFT — a build has now installed and passed a smoke check, and one non-sensitive
Item 0 sub-test ran with real evidence. Everything voice-dependent is still PENDING** (device is
currently locked at the keyguard — see below). See `RESUME.md` for the operational handoff.

## Build ID

- Date: 2026-07-15
- Commit before this session: `f9d99674f48da54025f71479b92da28556a85fc3` (2026-07-02) — the
  working tree already carried ~2 weeks of substantial uncommitted work before this session
  started (most of `src/`, most of `modules/`, most of `components/`); this session's commits
  necessarily include that pre-existing state for any file touched, since git commits whole file
  contents, not hunks, and several of those directories (`src/core/mission`,
  `src/core/action-engine`, `src/core/orchestrator`, `src/core/contacts`, `src/executors`,
  `modules/benson-accessibility`, `modules/benson-app-registry`) were entirely untracked before
  this session — each is committed here for the first time.
- This session's commits (all reviewed via `git status`/`git diff --stat` before staging; nothing
  outside each item's own files was swept in; no blanket `git add -A` was ever used):
  - `76be45d` item-0-accessibility
  - `b1075eb` item-1-contacts
  - `8c954d4` item-2-native-calls
  - `e66c297` item-3-whatsapp-calls
  - `78e11a8` item-4-organic-ui
  - Some files are genuinely shared across items (`whatsappTool.ts`/`missionExecutor.ts` carry
    both Item 0 and Item 3 changes; `app/index.tsx` carries both Item 1 and Item 4 changes) — a
    clean per-item split wasn't possible without risky interactive patch-staging, so each is
    committed once, in the later of the two items, with the commit message explicitly listing
    which parts belong to which item.
- Package: `com.benson.butler`, versionName `1.0.0`, versionCode `1` (device install predates
  this session's changes).
- Device: OnePlus Nord 4 (CPH2663), adb serial `9c1464eb`, OxygenOS 15, system language German.
- Build command: `npx expo run:android` (with `JAVA_HOME=/c/Program Files/Android/Android
  Studio/jbr` and Android SDK platform-tools on `PATH` — neither was set by default in this shell).
- Install command: handled by `expo run:android` itself (`adb install` under the hood).

## Items 0-5 status table

| Item | Status | Verification method | Evidence | Known limitation |
|---|---|---|---|---|
| Build/install smoke check | **PASS** | adb: pid, activity state, logcat | see "Smoke check evidence" below | — |
| 0 — Accessibility resilience | PARTIAL — data-layer confirmed, full flow PENDING | adb `settings put/get secure` toggle (non-sensitive) | see below | full spoken-error+deeplink path needs a WhatsApp-call attempt while disabled, which needs an unlocked device |
| 1 — Real device contacts | CODE DONE, UNVERIFIED on-device | typecheck/lint clean | — | needs unlocked device + Debug Panel |
| 2 — Direct native phone calls | CODE DONE, UNVERIFIED on-device | typecheck/lint clean | — | needs unlocked device + spoken "da" |
| 3 — WhatsApp voice call (auto-tap) | CODE DONE, UNVERIFIED on-device | typecheck/lint clean | — | needs unlocked device + spoken "da"; keyguard status for this exact flow now confirmed relevant (see below) |
| 4 — Living organic design | CODE DONE (organism + re-theme + control-bar/contacts/dashboard glow), UNVERIFIED visually | typecheck/lint clean | — | no frame-stat/latency measurement possible without visual access |
| 5 — Full regression | NOT STARTED | — | — | depends on 1-4 being verified individually first |

**No FAIL is being reported as PASS.** The one PASS above (build/install smoke check) has real
adb evidence attached. Everything else is either genuinely unverified or explicitly PARTIAL.

## Smoke check evidence (real, this session)

- `adb shell pidof com.benson.butler` → `10712` (process alive, no crash).
- `dumpsys package` → `lastUpdateTime=2026-07-15 19:15:10` (confirms the fresh install actually landed).
- `dumpsys activity activities` → `MainActivity` task `visible=true`, `mFocusedApp=...MainActivity`.
- `logcat` grep for `FATAL EXCEPTION|AndroidRuntime|ReactNativeJS.*[Ee]rror|redbox|Unable to resolve module|TypeError|SyntaxError` → **zero matches**.
- `AccessibilityManagerService` log line at install time still lists
  `ComponentInfo{com.benson.butler/expo.modules.accessibility.BensonAccessibilityService}` as enabled — survived the reinstall.

## Item 0 — what was actually tested (non-sensitive, autonomous)

Toggled the OS-level accessibility state directly (the same field `getConnectionState()` reads):
```
adb shell settings put secure accessibility_enabled 0
adb shell settings delete secure enabled_accessibility_services
adb shell settings get secure enabled_accessibility_services   # -> null
adb shell settings get secure accessibility_enabled             # -> 0
```
then restored both to their original values (service was enabled before this test; confirmed
restored). This proves the underlying OS signal `getConnectionState()`'s Kotlin implementation
reads does change exactly as expected. It does **not** yet prove the full JS flow (abort before
click → speak the RO sentence → open Settings) — that requires triggering an actual
accessibility-dependent action (a WhatsApp-call attempt) while disabled, which needs the device
unlocked. **Device is currently locked (keyguard)** — confirmed via a screenshot after waking the
screen; did not attempt to unlock it (would require the user's PIN, which is out of scope for
autonomous testing).

## Regression matrix (Item 5)

Not run. Will be populated once a build is installed and the human is available for the
voice-dependent tests (per the standing safety rule below).

## Files touched (by item)

Authoritative list is each item's own git commit message (`git log --stat 76be45d..78e11a8` or
`git show <hash>` for any one item) — each message enumerates every file with a one-line purpose,
per `BENSON_ENGINEERING_RULES.md`'s "When done" convention. Not duplicated here to avoid drift.

## Permissions added/changed

- `CALL_PHONE` — already present in both `app.json` and the generated
  `android/app/src/main/AndroidManifest.xml` **before this session** (no manifest change needed).
  Item 2 adds the *runtime* request (`PermissionsAndroid.request` in
  `src/executors/phoneCallExecutor.ts`), asked once per app-process lifetime, with an honest
  spoken fallback to the dialer on denial.
- `READ_CONTACTS` / `WRITE_CONTACTS` — already present before this session; Item 1 is the first
  feature to request `READ_CONTACTS` lazily (with a spoken reason) rather than only at first
  Quick-Contacts use.
- No new manifest permissions were added this session.

## Measured numbers

Not yet available — requires an installed build. Will include `adb shell dumpsys gfxinfo
com.benson.butler` frame-time percentiles for each organism state, and a before/after voice-loop
latency comparison (timestamped logcat: wake-word detection to first spoken word).

## Honest limitations (known going in, independent of test results)

- **Accessibility Service lifecycle is OS-owned.** Item 0 adds a real liveness check
  (`getConnectionState`), correct `onUnbind` handling, and an honest notify-only watchdog — but
  there is no public Android API for an app to rebind or re-enable an `AccessibilityService` the
  OS has unbound. That action requires the user to open Settings; BENSON can only detect the
  outage and deep-link there.
- **WhatsApp UI fragility (Item 3).** The call-button tap now runs by default (`autoPressCall:
  true`), reversing an earlier product decision that disabled it after live testing found that
  exact tap unreliable on this device. The existing honest-failure path (leaves the chat open,
  reports the specific failed step) is what carries that risk now, not a guarantee the tap always
  succeeds.
- **Keyguard/screen-off behavior for WhatsApp calling is unverified, and now confirmed relevant**:
  this device does lock (confirmed via screenshot this session, a circular lock-icon keyguard
  screen appeared after waking the display). Whether WhatsApp's own UI is reachable via
  accessibility while the keyguard is active has not been tested — do not claim screen-off/locked
  WhatsApp calling works without testing it specifically once unlocked-vs-locked states can both
  be exercised.
- **Contacts search collision risk (Item 1).** RO "caută-l pe X"/"caută-o pe X" requires the
  clitic+"pe" form specifically to avoid swallowing unrelated "caută X" phrases (place/thing
  search) into a contacts lookup — narrower than a bare "caută" would have been, by design.
- **Item 4 code is complete but visually unverified** — asymmetric dashboard panel radii, organism,
  control-bar glow, and QuickContactsWidget glow are all implemented and typecheck/lint clean, but
  no one has looked at the running app yet to confirm it actually reads as "alive" versus just
  compiling without error.

## Privacy verification

- No cloud writes added; `src/core/contacts/deviceContacts.ts` never persists to AsyncStorage —
  every read goes straight to `expo-contacts` and returns fresh.
- No analytics added.
- Phone numbers: not stored in any new code path. `PhoneCallExecutor` logs
  `sanitized.length > 0` rather than the number itself.
- **Found and fixed a pre-existing leak** (not introduced this session, but in a file already
  touched for Item 0): `src/core/mission/tools/whatsappTool.ts`'s `tryOpen`/`attemptAndConfirm`
  logged the full `wa.me/<digits>?text=...` URL on every WhatsApp message/call-chat open,
  including the raw phone number in cleartext. Added `redactUrlForLog()` (masks any 7+ digit run
  to `••••<last 4>`, mirroring the existing `maskPhone()` convention already used for spoken
  confirmations in this same file) and applied it at both log call sites.
- Grepped `src/` and `lib/` for any other `console.log`/`devLog` call passing a `phoneNumber` or
  `.phoneNumbers` value directly — none found beyond the one fixed above.
- No accessibility-tree content added to persistent logs — `dumpScreenForDebug` (pre-existing,
  unchanged) already only logs to logcat (ephemeral), never to storage.

## Standing safety rule (this session)

The human is the confirmation gate during testing. No real phone call, WhatsApp call, or message
was placed, confirmed, or terminated by AI at any point this session, and none will be. Every test
requiring a spoken command is listed as PENDING below, not simulated via adb.

## PENDING — requires human voice verification

All of these require the device reconnected, a successful build+install, and a spoken command from
the human. None can be completed by AI alone under the standing safety rule.

1. **Item 0** — `TEST READY: kill the accessibility service (Settings > Accessibility > BENSON >
   off), then say "sună-l pe [contact] pe WhatsApp"` — confirm spoken RO error + Settings deep
   link, no click attempted.
2. **Item 1** — `TEST READY: "arată-mi contactele"` then `TEST READY: "cine e [real contact
   name]"` — confirm spoken results match the real address book.
3. **Item 2** — `TEST READY: "sună-o pe [real contact name]"`, answer `"da"` — confirm a direct
   call is placed (no chooser), screen off if possible. Then, separately, with CALL_PHONE revoked
   (`adb shell pm revoke com.benson.butler android.permission.CALL_PHONE`): same command, confirm
   the honest dialer-fallback sentence is spoken instead of a placed call.
4. **Item 3** — `TEST READY: "sună-l pe [real WhatsApp contact] pe WhatsApp"`, answer `"da"` —
   confirm the correct chat opens and a voice (not video) call actually starts. Human ends the
   call.
5. **Item 4** — `TEST READY: one full idle → listening → thinking → speaking cycle via any voice
   command` — visually confirm all four organism states read as distinct and alive; then `adb
   shell dumpsys gfxinfo com.benson.butler` for frame-time evidence.
6. **Item 5** — full regression matrix (21 checks in the original build spec), batched into as few
   voice sessions as practical once 1-5 above are confirmed working individually.
