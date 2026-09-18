# ROUND_NATIVE_WAKE_ALTERNATIVES_1 — AUDIT + FEASIBILITY

Porcupine rejected (per-unit commercial licensing). Goal: fastest production-viable **native /
on-device** wake-word engine for Android, no per-user cost, works while the RN JS runtime is
suspended, keyword "Benson".

**No implementation. The Porcupine scaffolding started earlier this turn was fully reverted** —
the tree is back to the accepted `ROUND_WAKE_STATE_BUG_1` state (native heartbeat + `WAKE_READY`
only); FGS module recompiles clean.

---

## Candidate comparison

| | **microWakeWord** (OHF-Voice) | openWakeWord (dscripka) | Vosk KWS | TFLite "speech commands" | Snowboy | VoxRT |
|---|---|---|---|---|---|---|
| LICENSE | **Apache-2.0** (Open Home Foundation) | **Apache-2.0** | Apache-2.0 | Apache-2.0 | Apache-2.0 (fork); training service dead | unverified |
| COMMERCIAL_USE | **yes, unrestricted** | yes | yes | yes | grey (unmaintained) | unknown |
| PER_USER_COST | **$0** | $0 | $0 | $0 | $0 | likely per-unit (same problem as Porcupine) |
| CUSTOM_WAKE_WORD | **yes** — free, synthetic-data training (microwakeword.com / community thread), no ML expertise, ~1 h | yes — Colab, synthetic (Piper TTS), 75–90 min | keyword *grammar* over a general model (not a trained WWD) | retrain on Speech Commands pipeline + your own recordings (harder) | was yes; service gone | unknown |
| ANDROID_NATIVE | **yes — proven: Home Assistant Companion App ships microWakeWord on-device on Android** | yes (tflite build); no turnkey Android lib — you build the pipeline | yes — `org.vosk:vosk-android` AAR | yes (tflite AAR) | old NDK `.so`, armeabi only, no arm64 builds maintained | unknown |
| BACKGROUND_OPERATION | yes — your own `AudioRecord` in `BensonForegroundService` (native thread, unaffected by RN host pause) | same | same | same | same | unknown |
| MODEL_FORMAT | **single `.tflite`** (streaming, 16 kHz mono, 40 feat / 30 ms) | **3 `.tflite`**: melspec → shared embedding → per-word classifier (700 KB–2 MB total) | Vosk model dir (~40–50 MB small model) | 1 tiny `.tflite` | `.pmdl`/`.umdl` | unknown |
| CPU/BATTERY | **lowest** — designed for ESP32-class MCUs; on a phone it's negligible always-on | small/efficient on a phone; heavier than microWakeWord (openWakeWord docs themselves point to microWakeWord for low power) | heaviest of the four (full ASR decoder running continuously) + 40–50 MB RAM | very low | low | unknown |
| INTEGRATION_COMPLEXITY | **low–medium**: AudioRecord 16 kHz → 40-feature frontend → 1 tflite invoke per 30 ms frame → threshold. LiteRT (`org.tensorflow:tensorflow-lite`) AAR. ~250–400 lines Kotlin. | medium: same but 3 chained models + the melspec frontend | low code (AAR does it) but wrong tool — a general ASR mis-hears an English name "Benson" the same way the device SpeechRecognizer does (the very problem we're escaping) | medium: fixed vocab, "Benson" needs a full retrain you own | high: no maintained arm64 native build | unknown |
| PROJECT_MATURITY | **high & active** — Open Home Foundation, used across ESPHome + HA Voice hardware + HA Android; migrated from `kahrendt/microWakeWord` to `OHF-Voice/micro-wake-word` | high & active — used by HA server, Rhasspy, OpenVoiceOS; `dscripka/openWakeWord` | very high (Alpha Cephei) | Google tutorial-grade, not a product | **dead** (Kitt.ai shut down 2020) | insufficient public evidence — **not audited, cannot recommend** |
| SECURITY/PRIVACY | on-device only; no network; models are plain `.tflite` assets (auditable, no code) | same | same; large model | same | unmaintained C — higher risk | unknown |
| RISKS | must **train + evaluate** a "Benson" model (false-accept / false-reject tuning); LiteRT AAR size (~few MB, arm64+arm32); feature frontend must match the training config exactly | 3-model chain = more to get right; slightly higher CPU; same training/tuning risk | wrong tool for a name wake word; RAM/battery; still name-mishearing class | you own the whole training loop; fixed-vocab model quality | abandoned, no arm64, licensing of personal models murky | **do not adopt on marketing claims** (round's own rule) — no repo/license/security audit possible from public info |

---

## BEST_CANDIDATE

**microWakeWord (`OHF-Voice/micro-wake-word`).**

## WHY
- **Already proven native on Android in a shipping production app** — the Home Assistant Companion
  App runs microWakeWord on-device on Android for wake detection, audio only leaving the device
  *after* the wake word. That single fact removes most of the "will it even work in a
  foreground-service `AudioRecord` loop backgrounded" risk that killed the JS engine.
- **Single `.tflite` model** → the simplest possible integration: one interpreter, one invoke per
  30 ms frame, one threshold. No 3-model chain (openWakeWord), no ASR decoder (Vosk).
- **Apache-2.0, $0 per user, unrestricted commercial use** — meets the business constraint
  exactly. Custom "Benson" model is free to train (synthetic samples, ~1 h, no ML expertise).
- **Lowest CPU/battery** of the viable set — it targets microcontrollers; on a Nord 4 it is
  negligible for an always-on foreground service.
- Runs in **native code we control** (`BensonForegroundService` + `AudioRecord`), entirely
  outside React Native — so it works with the JS runtime suspended, which is the whole point
  (`ROUND_WAKE_STATE_BUG_1`).

openWakeWord is the fallback if the "Benson" model quality is unsatisfactory (its embedding model
sometimes generalises better for uncommon phrases) — same license, same integration shape, one
extra model in the chain.

## COST
- **$0 recurring / $0 per user.** Apache-2.0.
- One-time: (a) train + tune a "Benson" `.tflite` (free, but real effort — collecting negative
  audio, tuning false-accept vs false-reject), (b) ~1–2 focused days of native integration +
  device tuning.
- APK size: +~2–4 MB (LiteRT AAR arm64+arm32) + ~1 MB model asset.

## LICENSE
Apache-2.0 (verify the `LICENSE` file at `github.com/OHF-Voice/micro-wake-word` at pin time;
Open Home Foundation standardises on Apache-2.0). Permits commercial use, redistribution, and
bundling the model + a fine-tuned derivative. No attribution burden beyond the standard NOTICE.
The bundled `.tflite` is data, not code — no runtime network, nothing to phone home.

## FILES_REQUIRED (when approved to build the probe)
| file | purpose |
|---|---|
| `modules/benson-foreground-service/android/build.gradle` | `+ implementation "org.tensorflow:tensorflow-lite:<pin>"` (LiteRT) — the only new dep, Apache-2.0 |
| `modules/benson-foreground-service/.../MicroWakeWord.kt` (new) | `AudioRecord` (16 kHz mono PCM16) → 40-feature frontend (must mirror the training config) → ring buffer → `Interpreter.run()` per frame → sliding-window threshold → `onDetected()` |
| `modules/benson-foreground-service/.../BensonForegroundService.kt` | own the `MicroWakeWord` instance; `micOwner` state machine; auto-arm in `onStartCommand`; `wakePokeTick` self-heals it (recreate on unexpected stop, exactly one instance); the existing `onHotwordDetected("")` → `onWakeWordDetected` event path is reused verbatim |
| `modules/benson-foreground-service/.../BensonForegroundServiceModule.kt` | `Function("wakeSetMicOwner")(owner)`, `Function("isNativeWakeAvailable")` → `{model, available}`; existing `onWakeWordDetected` event unchanged |
| `modules/benson-foreground-service/index.js` / `index.d.ts` | export the two |
| `app/src/main/assets/wakeword/benson.tflite` (new asset) | the trained model — **not committed** if licensing of the trained derivative is unclear; placed on the build machine / via a fetch step like `whisper-models/` |
| `app/index.tsx` | boot: `isNativeWakeAvailable().available` → `wakeEngineRef.current = 'native'` (JS `startLocalWakeLoop` already no-ops for non-`'local'`); call `wakeSetMicOwner('COMMAND_STT'|'TTS'|'CALL'|'WAKE')` at the existing chokepoints (`doStartListening` / `beginTtsBlock` / `afterPromptRearm` / WA-call arm+end); `WAKE_TO_COMMAND_LATENCY` timing |
| **not touched** | WhatsApp routing, parking governance, bubble, emergency, mission logic, contact resolution, `BENSON_STABILIZATION_1` invariants |

No new module, no new service — it lives in the existing `BensonForegroundService`.

## MIC_OWNERSHIP_PLAN
Authoritative single owner in `BensonForegroundService`: `NONE | WAKE | COMMAND_STT | TTS | CALL`,
logged `MIC_OWNER from=X to=Y`.

```
idle:  MIC_OWNER=WAKE   (MicroWakeWord owns AudioRecord)
detect "Benson":
  1. MicroWakeWord.stop()  → release AudioRecord   (native, synchronous)
  2. MIC_OWNER=COMMAND_STT
  3. emit onWakeWordDetected("")  → JS starts existing COMMAND_STT (unchanged)
  4. WAKE_TO_COMMAND_LATENCY ms=<detect→COMMAND_STT_READY>
during BENSON TTS:  JS → wakeSetMicOwner('TTS')   → wake stays stopped (cannot hear BENSON's voice)
command + TTS + AUDIO_TAIL_GUARD_MS done:  JS → wakeSetMicOwner('WAKE')
  → rearm(): no-op unless owner∈{WAKE,NONE} ∧ wake_word_enabled ∧ ¬user_stopped ∧ model present
  → MicroWakeWord.start()  → MIC_OWNER=WAKE   (exactly one instance; idempotent)
WhatsApp/phone call:  arm → wakeSetMicOwner('CALL');  verified CALL_ENDED (existing signal) → wakeSetMicOwner('WAKE')
self-heal (wakePokeTick, 3 s): owner should be WAKE but interpreter/thread dead → dispose + rearm once
```
Invariants preserved from `BENSON_STABILIZATION_1`: `userMicGate` still gates every COMMAND_STT
transcript (BENSON_OUTPUT ≠ USER_INPUT); no wake transcript enters mission parsing (the wake
engine emits only `""`); `afterPromptRearm` re-arm destination changes JS-wake-loop → `wakeSetMicOwner('WAKE')`.

## RISKS
1. **Model quality for "Benson"** — a made-up/uncommon name; false-accepts on similar phonemes,
   or misses. Mitigation: train on real negatives (RO/DE/EN ambient speech from the device),
   tune the threshold + refractory window on-device, keep a JS-wake debug fallback behind a flag
   during migration.
2. **Feature frontend mismatch** — the 40-feature extraction must exactly match the training
   config; a subtle mismatch = silently poor accuracy. Mitigation: use the reference frontend
   from the repo (port it 1:1), unit-test feature vectors against known inputs.
3. **LiteRT AAR** — ~2–4 MB APK growth (arm64 + armeabi-v7a). Acceptable; verify the pinned
   version has no unwanted native deps.
4. **OEM background AudioRecord** — OxygenOS could throttle mic for a backgrounded process
   despite the FGS. The HA Companion precedent suggests it works, but **P2/P7 (other-app / screen-off)
   must be proven on 9c1464eb** — same acceptance gate as before.
5. **Committing a trained derivative** — treat `benson.tflite` like `whisper-models/`
   (on-disk only, not versioned) until the licence question on the *trained* model is settled.
6. **Not chosen: VoxRT** — insufficient public repo/license/security information; the round's own
   rule forbids adopting on marketing claims. Revisit only if a real repo + Apache/MIT-class
   licence + audit is available.

## PROBE_PLAN (only after you approve microWakeWord + the licence check)
1. **Train** a "Benson" microWakeWord `.tflite` (community trainer / Colab), collect device
   negatives, get false-accept ≈ 0 over 30 min ambient + false-reject ≤ 1/10 spoken.
2. **Native probe** in `BensonForegroundService` behind `NATIVE_WAKE = "microwakeword"`:
   `BensonForegroundService → AudioRecord(16k mono) → MicroWakeWord frontend → tflite → detect
   "Benson" → stop wake capture → onWakeWordDetected("") → existing COMMAND_STT → after command/TTS
   → wakeSetMicOwner('WAKE') → rearm`. `isNativeWakeAvailable=false` ⇒ JS fallback unchanged (zero
   regression).
3. Logs: `WAKE_ENGINE engine=MICROWAKEWORD`, `NWW_INIT`, `NWW_ARMED`, `NWW_DETECTED keyword=Benson
   score=<...>`, `NWW_SUSPEND reason=<COMMAND|TTS|CALL>`, `NWW_REARM`, `NWW_ERROR`, `NWW_HEALTH`,
   `MIC_OWNER from= to=`, `WAKE_TO_COMMAND_LATENCY ms=`.
4. Device acceptance = the same P1–P7 grid from `ROUND_NATIVE_WAKE_PORCUPINE_1` (HOME 5/5,
   other-app 5/5, after-command 5/5, after-TTS 5/5, after-WA-call, 10-min idle, screen-off
   documented). No PASS claimed for NOT_RUN. If P2/P7 fail on OxygenOS, that is a device-policy
   finding, reported honestly, not a fake pass.
5. Only after P1–P4 = 5/5 and no duplicate mic ownership → promote to primary, demote JS wake to
   flagged debug fallback, then remove.

---

## Recommendation, one line
**Adopt microWakeWord (Apache-2.0, $0, single tflite, proven on Android by the Home Assistant
Companion App).** Verify the repo LICENSE + trained-model terms, then approve the probe.

Sources:
- [dscripka/openWakeWord](https://github.com/dscripka/openWakeWord) · [openWakeWord releases (tflite melspec/embedding/wakeword + onnx|tflite framework arg)](https://github.com/dscripka/openWakeWord/releases) · [openWakeWord LICENSE](https://github.com/dscripka/openWakeWord/blob/main/LICENSE)
- [OHF-Voice/micro-wake-word](https://github.com/OHF-Voice/micro-wake-word) · [kahrendt/microWakeWord](https://github.com/kahrendt/microWakeWord) · [microwakeword.com (custom training)](https://microwakeword.com/)
- [Home Assistant — about wake words (microWakeWord on Android Companion App, on-device)](https://www.home-assistant.io/voice_control/about_wake_word/) · [Home Assistant — create wake word](https://www.home-assistant.io/voice_control/create_wake_word/)
- [home-assistant/android issue #6472 — microWakeWord on Android](https://github.com/home-assistant/android/issues/6472)
