# ROUND_USER_DEFINED_WAKE_1 — USER-DEFINED WAKE WORD ARCHITECTURE AUDIT

Product requirement: no fixed wake word — every user names BENSON, and that name becomes the
wake word, enrolled from a handful of the user's own recordings. **No implementation this round.**

Verification method: `AndreiBulzan/heed-wakeword` was **cloned and read at the source level**
(`LICENSE`, `NOTICE`, `requirements.txt`, `heed/*.py`, `docs/*.md`, the RN example's
`build.gradle`/`package.json`) — not a marketing-page summary. Other candidates are evaluated from
public documentation; none were adopted on claims alone.

---

## Special focus: Heed — verified, source-level

| question | answer | evidence |
|---|---|---|
| repository real? | yes, active, single maintainer, 2026 copyright | cloned `github.com/AndreiBulzan/heed-wakeword`, proper `pyproject.toml`/`CHANGELOG.md`/`CONTRIBUTING.md`/`SECURITY.md` |
| license | **Apache-2.0**, full text present, `NOTICE` present | `LICENSE` (standard ASF template) + `NOTICE` (2026, Andrei Bulzan) |
| Android/mobile runtime real? | yes | `docs/mobile.md`: measured on-device timing "15–20 ms preprocessing + 1–15 ms inference per 100 ms audio", NNAPI/Core ML delegate support, "Android works from any OS … no Mac needed"; `examples/inference_react_native/android/{build.gradle,app/build.gradle,settings.gradle}` present |
| 8–30 user recordings real? | **yes, literal, documented** | `docs/studio.md`: *"Record positives. Say the phrase several times straight from the browser… 8 to 30 takes."* Plus a **negative-mining** step (phonetically-similar near-misses) and an **ambient-noise capture** step — both real, both improve false-accept control beyond bare positives. |
| GPU required for training? | **no** — CPU-only works, GPU auto-detected and used opportunistically | `heed/trainer.py: _resolve_device()` — `"auto"` picks CUDA if present else CPU, clean fallback; `requirements.txt` has no CUDA-only dependency; default `epochs=35` on a **~10 K-parameter** model (see below) — CPU-feasible in low minutes, not hours |
| TFLite/ONNX export real? | **yes**, both, plus INT8 | `heed/export.py`: `torch.onnx.export` → `wake.onnx` (float32) + optional `wake.int8.onnx` (dynamic quantization, "~25% the size, typically <0.01 accuracy delta"); `docs/colab.md`: *"Both export `wake.onnx`, `wake.int8.onnx`, and `wake.json` (plus `wake.tflite` if …)"* |
| on-device or backend training? | **backend/local-machine training, on-device inference.** Training runs the CPU/GPU Torch pipeline (studio Flask app, CLI, or Colab) — not inside the Android app. Inference (loading the exported model + running it) is fully on-device. | `heed/trainer.py` is plain Torch — no Android training runtime exists or is claimed anywhere in the docs |

**Bonus finding not asked for, materially relevant:** `wake.json` is a **preprocessing contract**
(phrase, threshold, mel parameters, filter/CMN steps, trigger logic, energy-gate config) shipped
next to the model. This is exactly the "frontend must exactly match training" risk that plagued
the `microWakeWord` probe (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1`) — Heed removes it by making the
contract *data*, not something to reverse-engineer. `heed/gate.py` also ships a working two-stage
`EnergyGate` (RMS threshold + voice-band spectral fraction) as a cheap pre-filter before the model
even runs — lower false-accepts *and* lower battery cost, already built and documented, not
something BENSON would have to invent (the ad-hoc log-mel frontend from the microWakeWord probe
becomes unnecessary here). `heed/model.py`: `TinyWakeWordNet`, **~10 K params, <50 KB float32,
~12 KB INT8** — smaller than either Porcupine's or microWakeWord's typical footprint.

**One residual verification item** (not a blocker, flagged honestly): the bundled RN example wires
inference through `onnxruntime-react-native` (a JS-bridge dependency, confirmed via
`patches/onnxruntime-react-native+1.24.3.patch` and `package.json`) — i.e. their *demo app*
routes inference through RN/JS, which would NOT survive JS suspension. **BENSON would not reuse
that demo path** — it would load `wake.onnx`/`wake.tflite` directly in Kotlin inside
`BensonForegroundService` via `onnxruntime-android` (or LiteRT for the `.tflite` export), exactly
the pattern already proven for the microWakeWord probe. This is a normal, low-risk integration
task *because* `wake.json` fully specifies the preprocessing — it is not a research problem.

---

## Candidate comparison

| | **Heed** | on-device few-shot KWS (generic academic/QbE) | openWakeWord / microWakeWord (per-user) | Vosk KWS grammar | Sensory/Picovoice-class commercial |
|---|---|---|---|---|---|
| LICENSE | **Apache-2.0** (verified) | varies by repo, mostly research code, licensing often unclear/None | Apache-2.0 | Apache-2.0 | proprietary, per-unit fee |
| COMMERCIAL_USE | **yes** | usually unclear — research code, no NOTICE/LICENSE discipline | yes | yes | rejected (Porcupine precedent) |
| ANDROID_RUNTIME | **yes**, ONNX+TFLite export, measured on-device timing | rarely packaged for mobile — mostly Python research repos | yes (tflite), but no per-user training UI shipped | yes (AAR) | yes |
| CUSTOM_ARBITRARY_PHRASE | **yes — designed for it**, any phrase, any language TTS can speak or the user can say | yes in principle (that's the research goal), but no productized enrollment UX | design goal is a *curated* wake word list; automated per-user pipeline exists (`hawake-wakeword`-style) but is TTS-synthesis-based, not literal user recordings | grammar-constrained ASR, not a trained wake profile — same name-mishearing risk BENSON is escaping | yes, but cloud/backend training in their product |
| NUMBER_OF_USER_SAMPLES | **8–30**, documented, real | claims range 1–20 in papers; no shipped tool enforces/validates this | not designed around user-recorded samples; needs either large synthetic TTS corpus OR would need custom work to accept few real recordings | N/A (no per-phrase training) | vendor-controlled |
| TRAINING_LOCATION | **local CPU (or GPU) — studio app / CLI / Colab**, not on-phone | typically a research notebook; not packaged for a phone or a server | Colab/local Python (openWakeWord) or ESPHome tooling (microWakeWord); GPU recommended for openWakeWord's synthetic pipeline | N/A | cloud (Sensory) |
| TRAINING_TIME | **low minutes on CPU** (10 K-param model, 35 epochs, logged `training took Xs`) | unknown / not benchmarked for shipping | openWakeWord: ~75–90 min (synthetic data generation dominates); microWakeWord: similar order | N/A | minutes (cloud) |
| MODEL_SIZE | **~10 K params, <50 KB f32 / ~12 KB int8** | varies, often larger (embedding-based) | 700 KB–2 MB (openWakeWord 3-model chain); microWakeWord: comparable to Heed, single model | tens of MB (full ASR) | proprietary, small |
| INFERENCE_COST | very low (measured: 1–15 ms / 100 ms audio on a phone) | unknown, unbenchmarked on mobile | low (both), microWakeWord ≈ Heed-class | high (full decoder, always on) | low |
| BACKGROUND_FEASIBILITY | yes — same native-`AudioRecord`-in-foreground-service pattern already proven this project (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1`) | unproven for this use case | yes (already probed this project) | yes but heavy | yes |
| FALSE_POSITIVE_CONTROL | **built-in**: negative-mining UI + ambient capture + energy/voice-band gate + eval/ROC tooling (`eval.py`) | usually left to the integrator | manual negative curation; no shipped per-user negative-mining UX | grammar constrains vocabulary, not acoustic confusability | vendor-tuned |
| MODEL_REPLACEMENT | **yes, by design** — "five word slots with live word switching" in the mobile demo; swapping `wake.onnx`+`wake.json` is a file replace | N/A | swap the `.tflite` asset — same mechanism BENSON's probe already implements | swap the grammar | vendor API call |
| MATURITY | active (2026), single maintainer, small but complete and disciplined (tests/, CONTRIBUTING.md, SECURITY.md, CHANGELOG.md) | fragmented, mostly unmaintained research code | high (HA ecosystem) | very high (Alpha Cephei) | high (commercial) |
| RISKS | single-maintainer bus factor; INT8 accuracy delta small but non-zero; BENSON must write its own native Kotlin loader (RN demo's ORT-via-JS path isn't reusable as-is) | not production-viable without months of hardening | wrong shape for *literal user recordings*; TTS-only per-user pipelines still need a phrase-to-audio step and per-language TTS voice coverage | wrong tool (ASR, not WWD) — reintroduces the name-mishearing problem this project is escaping from | licensing (rejected) |

---

## BEST_CANDIDATE

**Heed (`AndreiBulzan/heed-wakeword`), Apache-2.0.**

## WHY
- It is the only audited candidate whose enrollment flow **is literally** "user says the phrase
  8–30 times" — the exact UX the product now requires — with a shipped, documented tool (the
  studio) that also mines negatives and captures ambient noise, not just positives.
- **No GPU, no cloud, no per-user fee** — training is a small Torch job (~10 K-param model, CPU
  fallback verified in source) that finishes in low minutes; inference is on-device ONNX/TFLite.
- **Model replacement is a first-class feature** ("live word switching"), which maps directly onto
  "user changes the assistant's name later."
- The `wake.json` preprocessing contract removes the single biggest integration risk this project
  hit with the microWakeWord probe (frontend-must-match-training uncertainty).
- Apache-2.0, single-file NOTICE, no viral licensing — safe for BENSON's commercial use.

## ENROLLMENT_FLOW (target architecture, mapped onto Heed's real tooling)
```
Settings → "Assistant name" → user types "Marius"
  → BENSON prompts: say "Marius" 8–15 times (Heed's studio flow, ported to a BENSON-native
    recording screen — no browser needed; same WAV capture Heed's CLI accepts)
  → capture ~10–20 s of ambient room noise (one-time, silent prompt)
  → negatives: reuse a small BUNDLED negative pool (common words/phonetically-similar names,
    ships with BENSON) — no need to make the user record negatives; Heed's studio negative-mining
    UI becomes an OFFLINE step done once when building the app, not a per-user step
  → USER_ENROLLMENT payload (positives + ambient) sent to TRAINING_LOCATION (see below)
  → heed CLI trains (`heed train`, CPU, ~10 K-param model, low minutes) → `wake.onnx` +
    `wake.int8.onnx` + `wake.json`
  → files copied to the device (or produced directly on a companion service and downloaded once)
  → NativeWakeEngine.load(wake.onnx, wake.json) inside BensonForegroundService
  → BensonForegroundService always listening, native, same MIC_OWNER state machine already built
```
Changing the name: stop the current native engine → re-run the same enrollment flow for the new
phrase → atomically replace `wake.onnx`/`wake.json` in the app's private storage → re-arm. No
reinstall, no app restart (matches Heed's own "live word switching" capability).

## TRAINING_REQUIREMENTS
- **Where**: Heed's training code is plain PyTorch — it does not run inside an Android app. Two
  honest options:
  1. **On-device-adjacent, but not in-app**: ship a minimal native (C++/NDK) forward-and-backward
     pass for this tiny 10 K-param CNN and train literally on the phone. Not what Heed ships;
     would be new engineering, feasible only because the model is this small — a real but
     non-trivial R&D task, out of scope for a probe.
  2. **Local server / companion process** (recommended): run the existing `heed` CLI/Torch
     pipeline on a small backend the user's own recordings go to over a LAN/local call (a
     lightweight self-hosted service, or a one-time local process on a paired PC) — audio and the
     resulting model never leave the user's control, satisfying "no continuous cloud audio" and
     "privacy-preserving" without literal on-phone gradient descent. This is the realistic
     `TRAINING_LOCATION = LOCAL SERVER`, not cloud, not literally on-device.
- **Time**: low minutes on CPU for a fresh enrollment (default 35 epochs on ~10 K params).
- **Samples**: 8–30 positive phrase recordings (Heed's own documented range) + one ambient clip;
  negatives reused from a bundled pool, not per-user.
- **GPU**: optional, not required (auto-detect + CPU fallback verified in `trainer.py`).

## ANDROID_INTEGRATION
- Reuse the exact native pattern already built and probed this project
  (`ROUND_NATIVE_WAKE_MICROWAKEWORD_1`'s `MicroWakeWord.kt` inside `BensonForegroundService`):
  dedicated `AudioRecord` thread, own inference call, `MIC_OWNER` state machine, `wakePokeTick`
  self-heal, `onHotwordDetected("")` hand-off to the existing command pipeline. **Not** Heed's own
  RN/`onnxruntime-react-native` demo path (JS-bridge, would not survive JS suspension).
- Model runtime: `onnxruntime-android` (loads `wake.onnx`/`wake.int8.onnx` directly) **or** LiteRT
  if `wake.tflite` is exported — either is a drop-in swap for the `Interpreter`/`OrtSession` call
  in a `HeedWakeWord.kt` analogous to `MicroWakeWord.kt`.
- Preprocessing: implement exactly what `wake.json` specifies (mel params, CMN, gate thresholds) —
  a direct port, not a guess, because the contract is explicit data.
- Storage: `wake.onnx` + `wake.json` live in the app's private files dir (not `assets/`, since
  they're generated per-user at runtime, not bundled at build time) — swapped atomically on
  re-enrollment.

## COST_PER_USER
**$0.** Apache-2.0, on-device inference, no API calls, no telemetry. The only recurring cost is
whatever compute BENSON's own enrollment/training step uses (self-hosted CPU minutes) — no
third-party per-user or per-request fee of any kind.

## LICENSE
Apache-2.0, verified at the source (`LICENSE` + `NOTICE` files, standard ASF template, no
additional restrictions found in `pyproject.toml`/`README.md`). Permits commercial use,
modification, and redistribution of both the training code and exported models.

## RISKS
1. **Single-maintainer project** — bus-factor risk; mitigate by vendoring the specific commit BENSON
   integrates against (Apache-2.0 permits this) rather than tracking `main`.
2. **BENSON must build its own native Kotlin loader** — not a research risk (the contract is fully
   specified via `wake.json`), but real integration work, same shape as the already-completed
   `MicroWakeWord.kt` probe.
3. **INT8 quantization delta** — small ("<0.01" per the docs) but non-zero; use float32 `wake.onnx`
   first, revisit INT8 once real users' false-accept/false-reject data exists.
4. **Training location decision** — "on-device" in the strict literal sense (gradient descent
   running on the phone's CPU) is not what Heed ships; a local-server/companion-process training
   step is the realistic, still-privacy-preserving architecture. This must be an explicit product
   decision before implementation (self-hosted service vs. a future on-phone training R&D effort).
5. **Enrollment quality varies by user** — background noise, mic placement, accent; Heed's own
   ambient-capture + energy gate mitigate this, but real-device tuning (threshold, epochs) will be
   needed per the same discipline as the microWakeWord probe (no PASS from inspection alone).
6. **Multi-user households** — the round's examples (User A/B/C) imply per-installation naming,
   not simultaneous multi-name recognition on one device; if simultaneous is later required, the
   "five word slots" capability already supports it without new architecture.

## PROBE_PLAN (after approval — not started)
1. Vendor the `heed` Python package (pinned commit) as a build-time/enrollment-time dependency of
   a small self-hosted training service (or a documented local-machine CLI step) — **not** shipped
   inside the Android app.
2. Native probe: `HeedWakeWord.kt` (mirrors `MicroWakeWord.kt`) — `AudioRecord` → the exact
   `wake.json`-specified frontend → `onnxruntime-android` `OrtSession.run()` → `EnergyGate`
   pre-filter ported 1:1 from `heed/gate.py` → threshold/refractory → `onHotwordDetected("")`.
3. Enrollment UI: a BENSON screen that records 10–15 takes of the typed name + one ambient clip,
   uploads to the training service, polls for `wake.onnx`/`wake.json`, saves to private storage,
   calls `NativeWakeEngine.load()`.
4. Logs: `WAKE_ENGINE engine=HEED`, `HEED_ENROLL_START/PROGRESS/DONE`, `HEED_INIT`, `HEED_ARMED`,
   `HEED_DETECTED keyword=<name> score=`, `HEED_SUSPEND reason=`, `HEED_REARM`, `HEED_ERROR`,
   `HEED_MODEL_REPLACED`, plus the existing `MIC_OWNER from= to=`.
5. Device acceptance: same P1–P7 grid as `ROUND_NATIVE_WAKE_PORCUPINE_1` /
   `ROUND_NATIVE_WAKE_MICROWAKEWORD_1`, run with a real enrolled name (e.g. "Marius"), plus a
   re-enrollment test (change name, confirm the old phrase no longer wakes and the new one does,
   with no duplicate `AudioRecord`/session instance). No PASS claimed without real-device
   detection, exactly as the prior two probes were reported.

---

## Confirm
- Heed verified at the **source level** (cloned, LICENSE/NOTICE/requirements.txt/trainer.py/
  export.py/gate.py/model.py/docs read directly) — not adopted on marketing claims.
- No implementation this round, as instructed.
- Nothing in `BensonForegroundService`/`app/index.tsx` changed — this is a document only.
