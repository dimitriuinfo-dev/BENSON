# Real-Device Evidence — Typed BENSON Input Execution

- **Date/time recorded:** 2026-09-15 17:11 (local)
- **Branch:** benson_recovery_2026_09_13
- **HEAD at time of recording:** `7b52cb4bb3c734a65dd3f9dbb678438e35dc7dbc` — "DECISION_GROQ_PRIMARY_1_IMPLEMENTED"
- **Device model:** OnePlus Nord 4 (ColorOS)
- **Package under test:** `com.benson.butler`
- **Input methods under test:** typed text entered directly into BENSON (primary baseline), plus one specific spoken-command observation (Yellowstone) recorded separately below

## Context

This document freezes manually-observed real-device evidence, before any further source changes. It does not modify or evaluate code — it is a record of what a human directly observed on the physical device. Typed input is the primary control baseline exercised here; one additional spoken-command observation is recorded separately and must not be generalized beyond what was literally observed.

The working tree at the time of this recording contains an **unrelated, uncommitted, untested** change to `frontend/lib/engines/stt/groqStt.ts` (adds diagnostic logging around STT provider attempts/results/validation). That change is not reflected in, and was not exercised by, the evidence below, and is **not** included in the evidence commit — see "Working tree note" at the end of this document.

## Observed capabilities

### Typed input — general

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `TYPED_INPUT_TO_EXECUTION` | Typed BENSON commands drove real app actions on-device across multiple target apps, with no additional physical taps by the user after issuing the typed command. | **PASS** |

### Prime Video

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `PRIME_VIDEO_OPEN` | Prime Video opened from typed BENSON input. | **PASS** |
| `PRIME_VIDEO_PROFILE_SELECTION_BY_TYPED_COMMAND` | User explicitly typed a command to open one specific account/profile, and BENSON entered that profile directly. | **PASS** |
| `PRIME_VIDEO_BEYOND_PROFILE` | The flow did not progress further than the selected account/profile. | **NOT_VERIFIED / CURRENTLY NOT WORKING** |

### Netflix

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `NETFLIX_OPEN` | Netflix opened from typed BENSON input. | **PASS** |
| `NETFLIX_SEARCH_BY_TYPED_COMMAND` | BENSON entered Netflix and performed a search within the already-open user profile. | **PASS** |
| `NETFLIX_PROFILE_SELECTION` | Not verified — do not claim this as PASS. | **NOT_VERIFIED** |

### Yellowstone

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `YELLOWSTONE_TYPED_OPEN_NAVIGATION` | Typed path currently reaches the account/profile screen but does not progress further. | **PARTIAL / CURRENTLY BLOCKED AT PROFILE SCREEN** |
| `YELLOWSTONE_VOICE_OPEN` | A spoken/vocal command for "Yellowstone" successfully caused BENSON to open/navigate to it, up to the same point typed input reaches. Which STT backend handled this was not recorded. | **PASS** |

### Spotify / YouTube

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `SPOTIFY_TYPED_EXECUTION` | Spotify opened and a requested content/action succeeded, but the specific content or action requested was not recorded with enough detail to reproduce exactly. | **PARTIAL** |
| `YOUTUBE_TYPED_EXECUTION` | YouTube opened and a requested content/action succeeded, but the specific content or action requested was not recorded with enough detail to reproduce exactly. | **PARTIAL** |

### Voice / STT

| Capability | Observation (as manually verified) | Classification |
|---|---|---|
| `VOICE_INPUT` | One real-device spoken command (`YELLOWSTONE_VOICE_OPEN`, above) succeeded. This is one specific, isolated success, not a demonstration of a stable or general voice pipeline. Voice must **not** be classified as globally failing, but must also not be generalized to "working." | **PARTIAL / INTERMITTENT** |
| `VOICE_STT` (pipeline-level, generalized) | Not claimed. Do not infer general STT correctness from one success. | **NOT CLAIMED** |
| `VOICE_PIPELINE` (end-to-end, generalized) | Not claimed, for the same reason. | **NOT CLAIMED** |
| `STT_GROQ` | Groq STT request quota exhausted (429s) as of 2026-09-15 per prior verified test round (see project memory `project_groq_quota_blocker.md`); resume protocol agreed but not yet re-enabled. | **BLOCKED_BY_QUOTA** |

## Notes

- No capability above is marked PASS unless the observed action was specific and directly reproducible. In-app actions described only generically ("navigation/action succeeded", "requested content/actions succeeded") are marked PARTIAL rather than PASS, since the exact steps are not specific enough to reproduce from this record alone.
- Revised diagnosis as of this update: voice input is **not** dead — one spoken command (`YELLOWSTONE_VOICE_OPEN`) succeeded on-device — but the voice path is intermittent/unreliable rather than stable. This is a meaningfully different starting point than "voice does not work at all," and should inform reconstruction priorities.
- Several flows (Prime Video, Netflix, Yellowstone) currently stop at profile/account-selection boundaries; only Prime Video's profile selection and Netflix's in-profile search are confirmed to go past app-open.
- `STT_GROQ = BLOCKED_BY_QUOTA` is an independent, previously-verified fact about the Groq provider's quota state and is not evidence about the device's voice-capture path in general.

## Working tree note (not part of this evidence)

At recording time, `frontend/lib/engines/stt/groqStt.ts` has an unrelated uncommitted modification (adds `STT_PROVIDER_ATTEMPT` / `STT_PROVIDER_RESULT` / `STT_RAW_TRANSCRIPT` / `STT_VALIDATION` / `MISSION_INPUT_ALLOWED` diagnostic logging, and splits confidence assessment out to also track `avg_logprob`/`compression_ratio`). This change:
- was not exercised or verified by the typed-input evidence above,
- has not gone through a device test cycle,
- is left uncommitted and is intentionally **excluded** from the evidence commit.

Several other untracked paths also exist in the working tree (`android/`, `modules/`, `node_modules/`, `.expo/`, build artifacts, `whisper-models/`, `release-signing.json`, `docs/upstream/`) and are likewise excluded from the evidence commit, which stages this documentation file only.
