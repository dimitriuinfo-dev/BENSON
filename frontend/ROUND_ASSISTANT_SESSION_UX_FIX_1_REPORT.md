# ROUND_ASSISTANT_SESSION_UX_FIX_1_REPORT

Implemented. Build succeeded and is installed on the device. **No device testing was performed
this round** — per your explicit "STOP ALL ADB UI DRIVING" instruction (still in force from the
WhatsApp-origin investigation), I did not run any of the five acceptance tests. Building and
`adb install` are not UI interaction (no taps/launches/navigation) so they were done to get the
fix onto the device; nothing beyond that.

---

## 1. FILES CHANGED

| File | Change |
|---|---|
| `modules/benson-overlay/android/.../BensonBubbleService.kt` | `dismissDelayMs` param on `updateStatus`; `FLAG_KEEP_SCREEN_ON` on the status-card window; `SESSION_ACTIVE_*`/`SCREEN_AWAKE_*`/`RESULT_*` logging |
| `modules/benson-overlay/android/.../BensonOverlayModule.kt` | `updateBubbleStatus` gains `dismissDelayMs` param |
| `modules/benson-overlay/index.js`, `index.d.ts` | JS wrapper signature |
| `app/index.tsx` | `pushBubbleBand` no longer ever claims `terminal=true`; new `scheduleResultDismiss()`; hooked into `endTtsBlock()` and the two `ack_already_spoken` skip branches; `CONFIRM_WAIT_START/END` in `setBensonState()` |

`npx tsc --noEmit` → 0 errors. `:app:assembleRelease` → BUILD SUCCESSFUL. `adb install -r` →
Success.

## 2. ROOT CAUSE OF THE PREVIOUS PREMATURE DISMISS

`pushBubbleBand()` (called synchronously from `setBensonState('DONE', ...)`) sent
`terminal=true` **the instant the state became DONE** — which happens **before** `speakText()`
even starts for the final reply. Native's fixed `TERMINAL_DISMISS_MS` (2.5s, from the prior
round) then started counting down immediately, with no awareness of whether BENSON was about to
speak a long sentence. For HandyParken's result (a longer reply than "Deschid."), the 2.5s window
elapsed mid-speech — the overlay vanished while BENSON was still talking. This was not a bug in
the *duration* (extending it to another fixed number would just move the same problem to a
different reply length) — it was a bug in *when the countdown started*.

## 3. NEW RESULT-VISIBILITY RULE

`terminal=true` is now sent **exactly once**, at the moment the result is actually done being
communicated — never at the moment the state label first becomes "GATA":

- **If TTS plays for the result**: `endTtsBlock()` (already the universal hook every TTS
  completion path in this app funnels through — success/error/interrupt/background/stop/watchdog)
  checks whether the state is currently `DONE`/`ERROR` when TTS ends; if so, that's the trigger —
  `scheduleResultDismiss(RESULT_DWELL_AFTER_TTS_MS)` (3000ms).
- **If no additional TTS plays** (the `ackSpokenThisTurnRef.current` skip branches — the short ACK
  already covered it, "confirmarea lungă de la final dispare"): the skip branch itself now calls
  `scheduleResultDismiss(RESULT_DWELL_NO_TTS_MS)` (4500ms) immediately, since nothing else marks
  "the user could start reading this now". Both occurrences of this pattern in the codebase
  (`finishHandledMission()` and the mission-resume completion flow) were updated.
- **LISTENING/THINKING/EXECUTING/CONFIRMING/SPEAKING**: unchanged — `pushBubbleBand` always sends
  `terminal=false` for these (as it always did for the first four), so native's long
  (~65s) safety-net timer applies, never the short dwell. `CONFIRMING`'s wait is now additionally
  bracketed by `CONFIRM_WAIT_START`/`CONFIRM_WAIT_END` logs.
- **New speech cancels a pending dismiss**: unchanged mechanism from the prior round — any new
  `updateStatus()` call (e.g. a fresh LISTENING) cancels native's pending timer and re-arms the
  long safety-net instead. Untouched, still correct.

## 4. TTS/RESULT COORDINATION

The dwell is anchored to `endTtsBlock()`, which is the single point every TTS path (Android TTS,
OpenAI/Gemini network TTS, interrupted, watchdog-forced) already reports through — so "do not
dismiss while TTS is still playing" falls out of the existing architecture rather than a new
timer racing the real audio. If TTS is cut short (interrupt/background/watchdog), `endTtsBlock`
still fires and still starts the (short) dwell — a genuinely abandoned/interrupted turn does not
hold the overlay open forever waiting for a TTS completion that will never arrive cleanly.

## 5. KEEP-SCREEN-AWAKE IMPLEMENTATION

Two distinct mechanisms for the two distinct cases, per instruction (no permanent WakeLock,
proper Android APIs):

- **BENSON's own Activity foreground**: unchanged — the pre-existing `bumpSessionKeepAwake()` /
  `expo-keep-awake` mechanism (`activateKeepAwakeAsync`, 45s idle decay, already called from 5
  call sites covering listen/wake/incoming-text) already handles this correctly; it was not
  touched, since the reported bug (HandyParken open, i.e. BENSON backgrounded) is structurally a
  case this mechanism cannot address at all — it operates on BENSON's own window, which isn't the
  one on screen at that point.
- **Another app foreground, BENSON interacting via the overlay**: `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`
  added to the status card's own `LayoutParams` (`BensonBubbleService.updateStatus()`). This is
  Android's own per-window contract ("keep the device screen on for as long as I'm visible") —
  it needs no separate `PowerManager.WakeLock` object at all, because the window's own visible
  lifetime **is** the bound. No new acquire/release bookkeeping was added; the existing
  create/teardown pair (`addBubble()`+card creation / `dismissNow()` → `removeStatus()`+`removeBubble()`)
  already IS the acquire/release, automatically, on every one of `dismissNow()`'s existing call
  paths (terminal dwell complete, self-app-foreground, explicit hide, safety-net timeout).

## 6. EXACT ACQUIRE/RELEASE OWNERSHIP

| Event | Owner | Mechanism |
|---|---|---|
| Acquire | `BensonBubbleService.updateStatus()`, card creation (`wasNew` branch) | `FLAG_KEEP_SCREEN_ON` set on the card's `WindowManager.LayoutParams` at construction |
| Release | `BensonBubbleService.dismissNow()` → `removeStatus()` | Window removed from `WindowManager` — flag's effect ends immediately, same instant on every terminal path |

No code anywhere else acquires or releases this — one owner, one window, matching "session state
must be authoritative... reuse the current state machine plus the native overlay lifecycle
already introduced" (the overlay's own existence *is* `sessionActive` for the over-another-app
case, exactly as `ROUND_BUBBLE_VISIBILITY_POLICY_1` already defined it).

## 7. LOGGING ADDED

`SESSION_ACTIVE_START`/`SESSION_ACTIVE_END`, `SCREEN_AWAKE_ACQUIRE`/`SCREEN_AWAKE_RELEASE` (native,
`BensonBubbleService.kt`, tied to card creation/teardown); `RESULT_SHOW`, `RESULT_TTS_DONE`,
`RESULT_READ_DWELL_START` (JS, `app/index.tsx`); `RESULT_DISMISS_SCHEDULE`/`RESULT_DISMISS_CANCEL`/`RESULT_DISMISS_EXECUTE`
(native, tied to the existing `scheduleDismiss`/`cancelDismissTimer`/dismiss-execute points);
`CONFIRM_WAIT_START`/`CONFIRM_WAIT_END` (JS, `setBensonState()`). `RESULT_TTS_ACTIVE` was not
added as a separate tag — the existing `AUDIO_STATE from=LISTENING to=TTS_SPEAKING` /
`MIC_BLOCKED reason=tts_speaking` lines already mark exactly that transition; adding a second tag
for the identical event seemed like duplication rather than new signal, flagged here rather than
silently added.

## 8. REAL-DEVICE ACCEPTANCE

**All five (UX-SESSION-1 through 5): NOT_RUN.** Per your explicit instruction to stop all `adb`
UI driving (taps, typing, force-stop, navigation, launching app UI) after the repeated unexplained
WhatsApp-foreground incidents, none of the five tests — all of which require exactly that kind of
interaction (speaking the HandyParken command, waiting through a long conversation, watching the
screen not turn off, triggering and answering a confirmation) — were attempted. The build is
installed and ready.

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Session-aware dismiss (not tied to state-entry, tied to TTS-done/no-TTS) | IMPLEMENTED, build-verified only |
| Result readable dwell (3s after TTS / 4.5s text-only) | IMPLEMENTED — **NOT_RUN** on device |
| Overlay never dismisses mid-TTS | IMPLEMENTED (anchored to `endTtsBlock`) — **NOT_RUN** |
| New speech cancels pending dismiss | UNCHANGED from prior round (already correct) — **NOT_RUN** |
| CONFIRMING stays until resolved/cancelled/timeout | UNCHANGED, now also logged — **NOT_RUN** |
| Screen stays awake during an over-another-app session | IMPLEMENTED (`FLAG_KEEP_SCREEN_ON`, bounded to the overlay window) — **NOT_RUN** |
| Screen-awake released on every terminal path | IMPLEMENTED (automatic — window teardown) — **NOT_RUN** |
| No permanent WakeLock introduced | Confirmed by inspection — no `PowerManager.WakeLock` was added anywhere this round |
| UX-SESSION-1 / 2 / 3 / 4 / 5 | **NOT_RUN** |

No PASS is claimed anywhere in this report from the build succeeding.
