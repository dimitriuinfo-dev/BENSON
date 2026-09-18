# BENSON WAKE HEALTH DIAGNOSIS

The notification is a fixed string, set once at service start (`buildNotification(title, body)`,
`EXTRA_BODY` default `"BENSON is listening."`) and **never updated to reflect the recognizer's
real state** — so it is not evidence of wake health. This round adds the real signal
(`WAKE_HEALTH`) and makes the notification tell the truth.

Wake architecture **not redesigned** — diagnosis + smallest fix only.

---

## REAL RUNTIME STATE (read now, on device `9c1464eb`)

| # | question | now | source |
|---|---|---|---|
| 1 | `BensonForegroundService` alive? | **ALIVE** | `dumpsys activity services` — `ServiceRecord … BensonForegroundService` present, `FOREGROUND_SERVICE` notif flag |
| 2 | `BensonAccessibilityService` bound? | **BOUND** | `dumpsys accessibility` — `Bound services:{Service[label=Benson…]}`, `Enabled services:{…BensonAccessibilityService}` |
| 3 | wake recognizer instance exists? | **JS local-Whisper loop is the active engine** (`wakeEngineRef='local'`); the native `SpeechRecognizer` hotword loop is **not auto-started** on this device (removed 2026-08-24, `BensonForegroundService.kt:191` — device-level SpeechRecognizer failure) | code |
| 4 | recognizer currently LISTENING? | **needs the live `WAKE_HEALTH` line** — `wakeScanningRef` (JS) is the flag; no wake-scan logs survived in the buffer | code + `WAKE_HEALTH` |
| 5 | microphone hold active? | **needs `WAKE_HEALTH`** — `waCallMicHoldActiveSafe()` / `whatsappCallMicHoldUntilRef` (a WhatsApp-call 180 s hold) | native + `WAKE_HEALTH.micHold` |
| 6 | whatsapp/native call mic hold **stale**? | **the prime suspect** — a prior WhatsApp call arms an 180 s native `whatsappCallMicHoldUntilMs`; the WA-LIFECYCLE rounds fixed the finalize path, but any gap leaves `MIC_BLOCKED reason=whatsapp_call_live` blocking every wake scan silently | `WAKE_HEALTH.reason=whatsapp_call_mic_hold` |
| 7 | SpeechRecognizer callbacks receiving audio? | for the JS loop: the mic-volume/RMS callback (`addVolumeListener`) is now timestamped → `WAKE_HEALTH.audio=RECEIVING\|NONE` | new |
| 8 | partial/final STT results arriving? | `WAKE_SCAN_HIT` / `WAKE_SCAN_MISS` / `WAKE_SCAN_IDLE` per cycle; `WAKE_HEALTH.lastTranscript` = last text seen | code + `WAKE_HEALTH` |
| 9 | wake-word classifier receiving those results? | `detectWakeWord(text)` inside the `startWakeScan` callback — `WAKE_HEALTH.wakeMatch` recomputes it against the last transcript | code + `WAKE_HEALTH` |
| 10 | activation transition firing? | `WAKE_SCAN_HIT` → `handleWakeDetected` → `wakeTriggeredRef=true` → `doStartListening`. `WAKE_HEALTH.activation=READY\|BLOCKED` + `reason` name the first thing stopping it | code + `WAKE_HEALTH` |

**Both services are alive and accessibility is bound**, so the first broken stage is **downstream**
of (1)/(2). The `WAKE_HEALTH.reason` field pins it exactly on the next "Benson didn't work"
moment. From the code, the three reachable culprits, in likelihood order:

1. **`whatsapp_call_mic_hold`** — a stale 180 s mic hold from a prior WhatsApp call (`MIC_BLOCKED
   reason=whatsapp_call_live` at every wake-scan entry, `startLocalWakeLoop` lines guard 2).
2. **`wake_loop_stopped`** — the JS local wake loop's self-restart chain
   (`setTimeout(startLocalWakeLoop, …)`) broke and the self-heal did not resume it. The self-heal
   resume at `app/index.tsx` is gated on `isForegroundRef.current` — **while BENSON is
   backgrounded it will not restart a dead local wake loop** (only `LISTEN_HEALED reason=wake_idle`
   when foregrounded). Native SpeechRecognizer isn't started to cover the gap.
3. **`tts_speaking` stuck** — `speakingRef` never cleared after a backgrounded network-TTS `onDone`
   never fired (the documented C1 background-collapse class); every listen/wake restart no-ops at
   `|| speakingRef.current`.

---

## ADDED — consolidated health log (JS, in the self-heal loop, every 2 s, deduped / ≤30 s)

```
WAKE_HEALTH
  service=ALIVE|DEAD
  a11y=BOUND|UNBOUND|UNKNOWN
  recognizer=LISTENING|STOPPED|ERROR
  micHold=true|false
  audio=RECEIVING|NONE
  lastTranscript="<last STT text, 60 chars>"
  wakeMatch=true|false
  activation=READY|BLOCKED
  reason=<ok | service_dead | silenced_by_user | wake_word_disabled | whatsapp_call_mic_hold |
          tts_speaking | tts_tail | conv_mode_idle | conv_mode_listening |
          engine_native_state_unknown | processing_command | wake_loop_stopped | no_audio_frames>
```

- `computeWakeHealth()` derives everything from live refs: `serviceActiveRef`,
  `a11yBoundRef` (refreshed from `getConnectionState()`), `wakeEngineRef`, `wakeScanningRef`,
  `listeningRef`, `wakeTriggeredRef`, `speakingRef`, `loadingRef`, `silencedRef`, `convModeRef`,
  `micResumeAtRef`, `whatsappCallMicHoldUntilRef` / `waCallMicHoldActiveSafe()`,
  `wakeWordEnabledRef` (from `isWakeWordEnabled()`), `lastAudioAtRef` (new — stamped on every
  mic-volume callback), `lastUserTranscriptRef`, `detectWakeWord()`.
- `reason` is the **first broken stage** — the ladder mirrors `startLocalWakeLoop`'s own guards.
- Emitted from `listenHealTimer` **before** its early-returns, so health is reported even while
  speaking / loading.

---

## FIX — truthful notification (smallest change)

`"BENSON is listening."` is now shown **only** when `recognizer=LISTENING` **and**
`audio=RECEIVING` **and** `reason ∈ {ok, conv_mode_listening}` (brief transient states —
tts_speaking / tts_tail / processing — keep it, so the notification doesn't flap). Otherwise the
body becomes honest:

| condition | notification body |
|---|---|
| listening + receiving audio | `BENSON is listening.` |
| `reason=whatsapp_call_mic_hold` | **`BENSON microphone blocked`** |
| `reason=silenced_by_user` | `BENSON silenced — tap LISTEN` |
| `reason=wake_word_disabled` | `BENSON wake word off` |
| `wake_loop_stopped` / `no_audio_frames` / anything else sustained | **`BENSON wake inactive`** |

- New native `ACTION_UPDATE_NOTIFICATION` in `BensonForegroundService.onStartCommand` →
  `NotificationManager.notify(NOTIFICATION_ID=4271, buildNotification(t, b))` only.
  **No `startForeground()`** → no Android-14+ FGS-restart `SecurityException`, no wakelock /
  watchdog churn, no hotword-loop restart.
- New `updateNotification(title, body)` in the module + `index.js` / `index.d.ts`.
- JS (`emitWakeHealth()` in the self-heal loop) calls it only when the body actually changes
  (`notifBodyRef`), and logs `WAKE_NOTIF_UPDATED body=… reason=…`.

Files: `app/index.tsx` (health compute + emit + notif drive; `lastAudioAtRef` on the volume
listener; 2 imports), `BensonForegroundService.kt` (+1 action branch, +1 const),
`BensonForegroundServiceModule.kt` (+1 `updateNotification`), `benson-foreground-service`
`index.js` / `index.d.ts` (+1 export). **Wake pipeline itself untouched.**

---

## BUILD / INSTALLED

- `npx tsc --noEmit` → **0 errors**.
- `:benson-foreground-service:compileReleaseKotlin` → **BUILD SUCCESSFUL**.
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 1m 5s**, signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓).
- `adb install -r` → **Success** on `9c1464eb`, data preserved (`lastUpdateTime 2026-09-10
  12:50:33`, `firstInstallTime` unchanged), accessibility bound.

## DEVICE TEST — NOT_RUN

At the phone: `adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I *:S`, then reproduce
"say Benson, nothing happens". Read the `WAKE_HEALTH` line — `reason=` **is the first broken
stage**. The pull-down notification should now read `BENSON wake inactive` /
`BENSON microphone blocked` (not `BENSON is listening.`) whenever `activation=BLOCKED`, and go back
to `BENSON is listening.` within ~2 s of the wake loop actually resuming (`WAKE_SCAN_START` +
`audio=RECEIVING`).

Quick check for the mic-hold suspect: after a WhatsApp call ends, watch whether `WAKE_HEALTH
reason=whatsapp_call_mic_hold` persists past a few seconds — if it does, the WA-LIFECYCLE finalize
didn't clear the hold and that is the root cause; the notification will correctly say
`BENSON microphone blocked`.

---

## Confirm
- wake architecture: not redesigned — `WAKE_HEALTH` is read-only computed state; the only
  behavioural change is the notification text now reflects it
- one type of change: add (health log + honest-notification path)
- notification update path has no `startForeground` / no service restart / no hotword restart
- tsc: PASS · release build: PASS (`O=TOKKO`) · installed: YES (data preserved) · device test:
  **NOT_RUN**
- git / prebuild / setx: NO
