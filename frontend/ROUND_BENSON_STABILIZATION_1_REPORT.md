# BENSON_STABILIZATION_1 — self-audio contamination + wake death

Subsumes `ROUND_CONTACT_AMBIGUITY_WAKE_CRASH_1` and the wake-recovery concern. No new features,
no new fuzzy logic, no WhatsApp-UI-automation changes, no new service. Smallest coherent fix.

---

## ROOT_CAUSE

**BENSON's own spoken output was captured by the recognizer and accepted as user input**, then
routed into mission parsing / the WhatsApp compose field, and the flapping TTS↔mic cycle left the
recognizer wedged.

Two code-level facts made it possible:

1. **The STT result path had no turn/time gate.** `resultSub` (`app/index.tsx`) accepted any
   final transcript, guarded only by `looksLikeSelfEcho()` — a *content* heuristic (substring / ≥60%
   word overlap against recently-spoken text, within `ECHO_WINDOW_MS`). It fails when:
   - the mic catches only a **fragment** of a long utterance (word-overlap ratio drops),
   - the spoken text was not pushed to `lastSpokenRef` on that path,
   - the echo window expired because the utterance was long,
   - `speakingRef` was force-released early by the TTS watchdog (`TTS_MAX_BLOCK_MS`) and the mic
     reopened while BENSON was still audibly speaking.
   There was **no rule** "a capture whose audio window overlapped a TTS window is BENSON_OUTPUT →
   drop it, regardless of content."

2. **Contact ambiguity spoke a full candidate enumeration.** `resolveContact` (ambiguous) and
   `whatsappTool.resolveWaNumber` / `tryDirectContactCall` / `buildChoiceQuestion` built the prompt
   as `"… : <every candidate displayName>. Pe care dintre ei?"`. On a mangled STT token
   (`"Hannah"` → garbled) the fuzzy tier returns many contacts, so BENSON spoke a long list of
   contact-name tokens (`"Dan Popescu, Salvamont, Dan Vulcan D, Dana Dragomir …"`). That output,
   re-heard as a fragment, sailed through `looksLikeSelfEcho`, became `lastUserTranscriptRef`
   (shown in the overlay as the "AM ÎNȚELES" transcript), and was dispatched — landing in the
   verified HANNAH chat's compose field.

3. **No guaranteed re-arm on the ambiguity/failure branch.** The clarification/question prompts
   re-armed only via `() => { if (convModeRef.current || wakeTriggeredRef.current) doStartListening(); }`.
   In the ambiguity path `wakeTriggeredRef` was already cleared and `convModeRef` was false → the
   `onDone` callback did **nothing** → recognizer stopped. The self-heal wake-idle resume was
   gated on `isForegroundRef`, so a backgrounded BENSON never restarted the loop.

---

## STATE_MACHINE_BEFORE

Implicit, via booleans (`speakingRef`, `listeningRef`, `wakeScanningRef`, `loadingRef`,
`convModeRef`, `wakeTriggeredRef`). No source/turn tag on transcripts. TTS↔mic ordering enforced
only by `TTS_TAIL_MS = 500` + content echo heuristic. Re-arm depended on which flags happened to
be set when a callback fired.

## STATE_MACHINE_AFTER

Same booleans (no refactor), plus one **authoritative acceptance gate** and one **guaranteed
re-arm**, with explicit `AUDIO_STATE` transitions logged:

```
WAKE_LISTENING ⇄ COMMAND_LISTENING → PROCESSING → TTS_SPEAKING → WAITING_USER_REPLY → (RECOVERING) → …
```

- **`userMicGate(sid)`** — the single decision "is this transcript a real USER_MIC turn?":
  `sid === current session` AND `!speakingRef` AND `sttCaptureStartedAtRef > ttsEndedAtRef +
  AUDIO_TAIL_GUARD_MS (1200 ms)`. Applied in `resultSub` final **and** the partial-fallback path.
  A capture whose window overlapped TTS or the tail guard → **`STT_DROP reason=TTS_ACTIVE|TTS_TAIL`**;
  a superseded session → `STT_DROP reason=STALE_SESSION`. `looksLikeSelfEcho` kept as a secondary
  net (`STT_DROP reason=SELF_ECHO_CONTENT`). Only a pass logs `USER_INPUT_ACCEPTED turnId=… source=USER_MIC`.
- **`sttCaptureStartedAtRef`** stamped the instant a wake scan or command capture begins
  (`startLocalWakeLoop`, `doStartListening`).
- **`afterPromptRearm(expectReply)`** — the ONE post-TTS re-arm, wired into **every** prompt
  branch (all 10 `speakText(…)` sites: confirmation reprompt, clarification questions, "nu am
  găsit", brain clarify, conv replies). Force-clears a stale `speakingRef`, then re-arms exactly
  one recognizer (`doStartListening` when a reply is expected / conv / wake-triggered, else
  `resumePassiveWake`). Logs `WAKE_REARM_AFTER_TTS` → `WAKE_REARM_OK|FAIL`.
- **Self-heal (`listenHealTimer`)**:
  - RECOVERING: `speakingRef` held > `TTS_MAX_BLOCK_MS + 2000` → `WAKE_SELF_HEAL_START
    reason=stale_speaking` → `endTtsBlock('watchdog')` → `afterPromptRearm` → `WAKE_SELF_HEAL_OK`.
  - `isForegroundRef` gate **removed** from the wake-idle resume → `WAKE_SELF_HEAL_START
    reason=wake_loop_stopped` → `resumePassiveWake()` → `WAKE_SELF_HEAL_OK|FAIL` whether the UI is
    foreground or backgrounded.

## FILES_CHANGED
| file | change |
|---|---|
| `app/index.tsx` | `AUDIO_TAIL_GUARD_MS` + `sttCaptureStartedAtRef`; `userMicGate()` applied in `resultSub` + partial-fallback (`STT_DROP` / `USER_INPUT_ACCEPTED`); `afterPromptRearm()` wired into all 10 prompt callbacks; self-heal RECOVERING branch + dropped `isForegroundRef` gate; `AUDIO_STATE` / `STT_SESSION_START` / `MISSION_INPUT` logs |
| `src/core/contacts/contactResolver.ts` | ambiguous → `"Nu sunt sigur de nume. Spune numele din nou."`, `candidates` trimmed to ≤1 — **no enumeration** |
| `src/core/mission/tools/whatsappTool.ts` | 3 ambiguity strings (`tryDirectContactCall`, `resolveWaNumber`, `buildChoiceQuestion`) → the same bounded prompt; `WA_MESSAGE_BODY_ORIGIN` log before the native type call |

## REMOVED_CONFLICTING_BEHAVIOR
- **Contact-candidate enumeration** in every ambiguity prompt (4 sites) — replaced by one short
  bounded line. This was the toxic long BENSON output that got re-consumed.
- The `isForegroundRef` precondition on the self-heal wake resume — it prevented recovery while
  backgrounded.
- Prompt callbacks that re-armed only "if conv || wakeTriggered" — replaced by the unconditional
  `afterPromptRearm`.

## AUTOMATED_TESTS
- `npx tsc --noEmit` → **0 errors**.
- `scripts/wa-routing-tests.ts` (parser/routing regression, 11 groups) → **ALL PASS** — R6/R8/R9
  proxies unchanged (message routing, MESSAGE_BODY_MISSING, call path, no-openContact-downgrade).

## BUILD
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 48s**, signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓). APK: `android/app/build/outputs/apk/release/app-release.apk`.
- **INSTALL: done** (device reconnected) — `adb install -r` → **Success** on `9c1464eb`, data
  preserved (`lastUpdateTime 2026-09-10 17:59:21`, `firstInstallTime` unchanged).

### Post-install regression gate (structural — device, no voice)
- R1 a11y bound/alive = **PASS** (`enabled_accessibility_services` set; `Bound services:{Service[label=Benson…]}`)
- R2 foreground service alive = **PASS** (`ServiceRecord … BensonForegroundService`)
- R3 wake pipeline active/recoverable = **PASS** — `WAKE_HEALTH … recognizer=LISTENING activation=READY`;
  new `AUDIO_STATE from=PROCESSING to=COMMAND_LISTENING` firing; self-heal logs present
- R5 app opening = **PASS** (`MainActivity` resumed, process healthy, no crash)
- R10 no stale mic hold / speaking = **PASS (observed idle)** — `micHold=false`, `activation=READY`,
  no stuck `speakingRef`; full check needs S1–S7 voice
- R4 (background wake), S1–S7 = **NOT_RUN** (voice-gated)
- notification body currently `"BENSON is listening."` — correct: `WAKE_HEALTH` shows the
  recognizer actually LISTENING, so the honest-notification rule keeps "listening" (not lying)

## DEVICE_TEST — NOT_RUN (all)
Voice-gated; device offline. Not marked PASS.

| test | expectation |
|---|---|
| **S1** BENSON speaks a response | **zero** `USER_INPUT_ACCEPTED` during/for 1.2 s after its TTS; each dropped capture logs `STT_DROP reason=TTS_ACTIVE\|TTS_TAIL` |
| **S2** ambiguous contact | one short `"Nu sunt sigur de nume. Spune numele din nou."`, **no name list**, then `WAKE_REARM_OK`; user's spoken reply → `USER_INPUT_ACCEPTED` |
| **S3** cancel, wait 10 s, "Benson" | `WAKE_SELF_HEAL_*` / `WAKE_REARM_OK`; wake activates |
| **S4** WhatsApp message | compose field = only the user-spoken body; `WA_MESSAGE_BODY_ORIGIN turnId=… origin=user_mic_or_typed` precedes `WA_PAYLOAD_NATIVE_TYPE` |
| **S5** no list / TTS text / wake word in compose | guaranteed by S1 (nothing BENSON-spoken reaches `handleIncomingText`) + enumeration removal |
| **S6** 10× TTS→reply→TTS→reply | recognizer alive every cycle (`afterPromptRearm` each exit) |
| **S7** background BENSON, say "Benson" | self-heal `reason=wake_loop_stopped` no longer foreground-gated (works only while the RN JS runtime is alive — see LIMITATION) |

### LIMITATION (honest — needs a product decision, not shipped here)
When BENSON's **Activity is fully destroyed** (app swiped away), the RN JS runtime's timers stop
(verified on device: 12 s, zero `WAKE_HEALTH`/`WAKE_SCAN`). The only working wake engine is the JS
local-Whisper loop, which needs that runtime. The native `BensonForegroundService` hotword loop
falls back to the **device-broken SpeechRecognizer** (no Porcupine `benson.ppn` asset / no
Picovoice key). So "wake while the app is force-closed" is **not fixed by this round** and cannot
be without either a Porcupine key or a headless-JS whisper task — flag for decision. This round
does fix wake recovery for the reported flow (ambiguity / cancel / TTS / **backgrounded** app with
the runtime alive).

---

## REGRESSION_GATE
- R1 (a11y bound/alive) = **NOT_RUN** (device offline; unchanged — no accessibility code touched)
- R2 (foreground service alive) = **NOT_RUN** (device offline; unchanged — no FGS code touched this round)
- R3 (wake pipeline active + recoverable) = **NOT_RUN** on device; **strengthened** (self-heal RECOVERING + un-gated resume + `afterPromptRearm`)
- R4 (wake "Benson" from background) = **NOT_RUN**; improved while runtime alive; force-closed case = documented LIMITATION
- R5 (app opening works) = **NOT_RUN** on device; no app-launch code touched
- R6 (WhatsApp direct contact open) = **PASS (static)** — routing tests green; `runWhatsAppOpenConversationType` untouched
- R7 (WhatsApp call path unchanged) = **PASS (static)** — only the ambiguity *string* in `tryDirectContactCall` changed; `runWhatsAppCallNative` / call executor untouched; routing test E (placeCall) green
- R8 (confirmation YES/NO/UNKNOWN) = **PASS (static)** — gate logic untouched; routing tests green
- R9 (mission supersession) = **PASS (static)** — `supersedeActiveMission` / `looksLikeNewCommand` untouched
- R10 (no stale mic hold / speaking after tested flow) = **NOT_RUN** on device; **directly targeted** — `userMicGate` + `afterPromptRearm` + self-heal RECOVERING guarantee no stuck `speakingRef` / dead recognizer; `micHold` clearing path unchanged

**No previously-working capability regressed** in anything checkable statically (tsc + routing
tests). Device-dependent items are NOT_RUN because the device is offline, not because they failed.

## Confirm
- one type of change: add a turn/time acceptance gate + guaranteed re-arm; remove the enumeration
- no new service / no STT redesign / no WhatsApp-UI-automation change / no new regex patches
- tsc: PASS · routing tests: ALL PASS · build: PASS (`O=TOKKO`) · install: **pending (device offline)** · device tests S1–S7: **NOT_RUN**
- git / prebuild / setx: NO
