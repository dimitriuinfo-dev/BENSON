# ROUND_WAKE_COMMAND_HANDOFF_FIX_1_REPORT

Fixes post-wake extraction/handoff semantics only. The native wake engine
(`NativeCloudWake.kt`), VAD, and cloud STT call are byte-for-byte untouched this round — confirmed
by the diff (§1). Built and installed on the real device (`9c1464eb`); the two live acceptance
tests (WAKE-HANDOFF-1/2) require a spoken utterance, which I cannot produce from this environment
— both are honestly marked `NOT_RUN`, not PASS.

---

## ROOT CAUSE (identified before writing any fix)

`handleWakeDetected(commandTail)` in `app/index.tsx` is the single shared post-wake handler
every engine funnels through (its own doc comment says so). It treats **any non-empty
`commandTail` as a real command** and dispatches it straight to `handleIncomingText()`.

The JS-local wake engine (`startLocalWakeLoop`) never has this problem because it extracts its
own tail via `stripWakeWord()`, which already filters `WAKE_NOOP_SUFFIXES = ['wake up',
'trezește-te', 'trezeste-te', 'trezire']` before ever calling `handleWakeDetected()`.

The **native** engines (both the legacy regex hotword loop and this project's new
`NativeCloudWake`) extract their own `commandTail` independently, inside native code, with **no
knowledge of `WAKE_NOOP_SUFFIXES`** — `NativeCloudWake.matchWake()` (unchanged this round) simply
returns every word after the matched wake token as the tail. So "Benson wake up" arrived at
`handleWakeDetected` with `commandTail="wake up"`, which the handler dispatched as if it were the
user's actual command — exactly reproducing the device evidence ("AM ÎNȚELES: wake up", no
action). This is a real device-confirmed root cause, not a hypothesis.

**Fix location: `handleWakeDetected()` only** — the shared post-wake handoff layer, not any
engine. This satisfies "only fix post-wake extraction/handoff semantics" precisely.

## FIX

New helper, `isWakeControlPhrase(text)`, added right next to `WAKE_NOOP_SUFFIXES` and reused by
**both** `stripWakeWord()` (refactored to call it, behavior unchanged) and `handleWakeDetected()`
(new check). **No second phrase table was created** — both call sites read the same
`WAKE_NOOP_SUFFIXES` array.

```ts
function isWakeControlPhrase(text: string): boolean {
  const norm = text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[,.:;!?-]+$/, '').trim();
  return WAKE_NOOP_SUFFIXES.includes(norm);
}
```

`handleWakeDetected()` now does:
```ts
const rawTail = (commandTail || '').trim();
const isControlOnly = !!rawTail && isWakeControlPhrase(rawTail);
if (isControlOnly) logAudioDiag('WAKE_CONTROL_CONSUMED', `phrase="${rawTail}"`);
const tail = isControlOnly ? '' : rawTail;
if (tail) { /* same-breath command dispatch, unchanged */ }
else { /* bare-wake-word listening-armed path, unchanged — now also reached for "wake up" */ }
```

### Case-by-case (traced against the actual `NativeCloudWake.matchWake()` code, unchanged)

| Case | Native extraction (unchanged) | `handleWakeDetected` (fixed) | Result |
|---|---|---|---|
| 1. "Benson" | `commandTail=""` | `rawTail=""` → not control-only → `tail=""` | listening armed, waits for next utterance (unchanged — was already correct) |
| 2. "Benson wake up" | `commandTail="wake up"` | `rawTail="wake up"` → `isWakeControlPhrase` **true** → `WAKE_CONTROL_CONSUMED` → `tail=""` | listening armed, waits for next utterance — **this is the fix** |
| 3. "Benson, deschide calculatorul" | `commandTail="deschide calculatorul"` | not a control phrase → `tail="deschide calculatorul"` | dispatched same-breath to `handleIncomingText` (unchanged — was already correct) |
| 4. "Benson wake up" then "deschide calculatorul" | first utterance as in Case 2 (listening armed); second utterance captured by the normal STT session `doStartListening()` already opens | `resultSub`'s existing final-transcript path (unchanged) dispatches the captured text | reaches `handleIncomingText("deschide calculatorul", ...)` exactly as any normal command capture does |

Case 4 required no additional fix beyond Case 2 — once listening is correctly armed instead of a
nonsense command being dispatched, the existing (unmodified) command-capture pipeline already
does the rest, exactly as it does for a manual medallion tap today.

## FILES CHANGED

Only `frontend/app/index.tsx`. Nothing under `modules/**` — the native wake engine, VAD, and
cloud STT call are unmodified (verified: no changes to `NativeCloudWake.kt`,
`BensonForegroundService.kt`, or any other native file this round).

## DIAGNOSTIC LOGS

| Tag | Status | Where |
|---|---|---|
| `WAKE_TRIGGER` | already existed (added last round, native side) | `NativeCloudWake.kt` — verified present, unchanged |
| `WAKE_CONTROL_CONSUMED` | **new** | `handleWakeDetected()`, when `rawTail` matches a `WAKE_NOOP_SUFFIXES` entry |
| `WAKE_COMMAND_ARMED` | **new** | `handleWakeDetected()`, in the no-same-breath-command branch (`reason=bare_wake_word` or `reason=control_phrase_consumed`) |
| `WAKE_COMMAND_CAPTURED` | **new** | the shared STT `resultSub` final-transcript handler, gated on `wakeTriggeredRef.current` — the Case 4 follow-up utterance |
| `WAKE_COMMAND_DISPATCH` | **new** | top of `handleIncomingText()`, gated on a `wakeOriginated` flag captured once at entry — covers both Case 3 (same-breath) and Case 4 (captured follow-up) uniformly, one injection point |
| `WAKE_COMMAND_RESULT` | **new** | next to the existing `ORCHESTRATOR_HANDOFF_COMPLETED` log, same `wakeOriginated` flag |

**Scoping note on `WAKE_COMMAND_RESULT`**: `handleIncomingText()` has several earlier return
branches unrelated to the mission/orchestrator action path (URL-open, vignette confirmation, note
confirmation, the emergency gate). `WAKE_COMMAND_RESULT` is emitted only at the
`ORCHESTRATOR_HANDOFF_COMPLETED` site — the actual action-dispatch path "deschide calculatorul"
goes through. It was **not** added to every one of those other branches, per instruction not to
touch neighboring systems blindly; a wake-triggered utterance that happens to land in one of those
other branches will not currently emit a `WAKE_COMMAND_RESULT` line. Flagged honestly, not fixed
this round (out of scope — those branches aren't part of the failure being diagnosed).

## BUILD

```
npx tsc --noEmit     → 0 errors
:app:assembleRelease → BUILD SUCCESSFUL (1m 2s)
adb install -r       → Success (9c1464eb, data preserved)
```

Post-install sanity launch: no crash, no new errors in `BENSON_AUDIO`/`AndroidRuntime` logcat.

## REAL DEVICE TEST

| Test | Classification |
|---|---|
| WAKE-HANDOFF-1 ("Benson wake up" → "deschide calculatorul") | **NOT_RUN** |
| WAKE-HANDOFF-2 ("Benson, deschide calculatorul" in one utterance) | **NOT_RUN** |

**Why NOT_RUN, not PASS**: I have no microphone or speaker access from this environment — I
cannot produce a spoken utterance, and (per the standing rule in this project and this round's
own instruction) did not fabricate a result. The build is installed, launches cleanly, and the
fix's logic is traced line-by-line against the actual (unmodified) native extraction code above —
but that is code-level verification, not a device PASS.

**One live-testing complication observed, unrelated to this fix**: on this launch, "conversation
mode" was already ON from a prior session (persisted state, not something this round touched),
which keeps JS's own command-capture STT session continuously retrying in the foreground —
`COMMAND_STT` owns the mic, so the native wake engine doesn't get a chance to arm while the app is
foregrounded in this state. This is pre-existing app state, not a regression from this round; it
would need to be turned off (Settings, or backgrounding the app so the native engine takes over
per the previous round's fixes) before a clean foreground wake test.

**To finish**: say "Benson wake up" then "deschide calculatorul" (WAKE-HANDOFF-1), and separately
"Benson, deschide calculatorul" in one breath (WAKE-HANDOFF-2), while I watch `adb logcat` for
`WAKE_TRIGGER → WAKE_CONTROL_CONSUMED?/none → WAKE_COMMAND_ARMED?/WAKE_COMMAND_DISPATCH →
WAKE_COMMAND_CAPTURED?/none → WAKE_COMMAND_RESULT` and confirm Calculator's foreground state via
`adb shell dumpsys window | grep mCurrentFocus`.

## EXACT FAILURE STAGE

Not applicable — no test was run this round, so none failed. The failure stage identified from
the device evidence you supplied (before this fix) was **COMMAND_DISPATCH**: wake detection,
arming, and the native→JS handoff all worked correctly; the extracted tail ("wake up") was
incorrectly treated as a real command at the dispatch decision point inside the shared post-wake
handler, one level before it ever reached the command interpreter. Fixed at that exact point
(§ FIX above) — not COMMAND_ARM (arming itself was never broken), not COMMAND_CAPTURE (nothing
was ever captured for "wake up" — it was dispatched immediately, same-breath), not EXECUTION (the
orchestrator correctly processed "wake up" as an unrecognized non-command; it was never the
orchestrator's job to know "wake up" isn't a command — that's the post-wake layer's job, and is
what this round fixed).
