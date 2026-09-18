# ROUND_YOUTUBE_GOVERNANCE_2_REPORT

Implemented in the same pass as ROUND_YOUTUBE_GOVERNANCE_1 (shared build/typecheck history — see
that report's §6 for the full bug list, most of which affects both rounds since they share one
pipeline). **Real-device verified end to end**, including a genuinely confirmed video PLAYING
after selection — not just a click.

---

## 1. WHAT THIS ROUND ADDS ON TOP OF GOVERNANCE_1

GOVERNANCE_1's `searchYouTube()` ends by extracting real on-screen candidates (never hardcoded).
This round adds the conversational half: present those candidates, resolve the user's spoken pick
against them, tap the matching real result, and verify playback.

```
SEARCH(query) → OBSERVE_RESULTS → EXTRACT_CANDIDATES   [ GOVERNANCE_1 ]
→ ASK_USER → RESOLVE_SELECTION → SELECT_RESULT → VERIFY_PLAYBACK   [ this round ]
```

## 2. FILES CHANGED

| File | Change |
|---|---|
| `src/executors/youtubeExecutor.ts` | `selectYouTubeCandidate(title)` — reassert YouTube foreground, click the exact previously-extracted label, verify a playback signal |
| `src/core/orchestrator/missionOrchestrator.ts` | `pendingYouTubeSelection` state (separate from the existing app-open `pendingDisambiguation`), `resolveYouTubeSelection()`, wired at the top of `runMission()` |
| `modules/benson-accessibility/android/.../BensonCommandExecutor.kt` | `classNameContains` match field (structural playback-signal fallback — see §4) |

No changes to `app/index.tsx`, the bubble lifecycle, or session UX — the existing
`MissionRunResult.disambiguation` signal (Round D) already renders CONFIRMING / keeps the overlay
visible / listens for the reply, and this round's YouTube candidates ride that exact same,
untouched path.

## 3. WHY A SEPARATE PENDING STATE, NOT THE EXISTING `pendingDisambiguation`

`pendingDisambiguation` (Round D, app-open disambiguation) resolves a pick by **always building an
`OPEN_APP` request** — hardcoded to app-opening semantics. Selecting a YouTube video means tapping
a specific on-screen row and verifying playback, a different action entirely. Rather than branching
that existing, working code path, this round adds `pendingYouTubeSelection` as its own independent
pending state (same 60s-timeout shape, checked first in `runMission()`), zero lines changed in the
existing `pendingDisambiguation` block. Both states reuse the same `MissionRunResult.disambiguation`
rendering signal.

## 4. SELECTION + VERIFICATION — CONFIRMED LIVE, WITH ONE REAL FIX ALONG THE WAY

**First attempt: FAIL, but for an explainable, non-representative reason.** Submitting the reply
("prima") via BENSON's manual-text-input debug field necessarily brings BENSON's own Activity to
the foreground (required to type into it) — which backgrounds YouTube. `selectYouTubeCandidate`
then tried to click against BENSON's OWN accessibility tree, not YouTube's, and correctly reported
`not_found`. This is a test-methodology artifact (a real spoken command never needs BENSON's screen
visible), but it exposed a real, worth-fixing gap: nothing reasserted YouTube's foreground before
clicking. **Fixed**: `selectYouTubeCandidate` now checks (`assert_package`) that YouTube is still
foreground first, and if not, re-launches it (resuming its existing task — the search results
screen, not a reset to home) before clicking.

**Second attempt, after the fix: click succeeded, but playback verification FAILED — again for an
explainable reason, this time also fixed.** The click genuinely opened and started playing a real
video (confirmed by a screenshot: a playing video frame, progress bar, and the "Mix – INNA" queue
below it), but `assert_present {textContainsAny:['pause',...]}` reported no match. Root cause:
YouTube's on-screen player controls (including the pause button's accessible label) auto-hide a few
seconds into playback — a real, generic characteristic of video players, not specific to this
device. **Fixed**: added `classNameContains` to the native match spec (a structural Android
widget-class filter, e.g. matching `"SeekBar"`) as a fallback signal that doesn't depend on a label
that fades away — a video's scrub bar is a structural element, not a text label.

**Third attempt, after both fixes: PASS.**
```
YT_GOV_START stage=select title="Mix – INNA - 50 Videos"
YT_GOV_DONE title="Mix – INNA - 50 Videos" verified=true
```
Spoken/displayed result: `"Redau „Mix – INNA - 50 Videos"."` — a screenshot taken immediately after
shows a different video from the Mix genuinely playing (thumbnail pause-icon visible), confirming
this wasn't a false positive.

## 5. SELECTION RESOLUTION (`resolveYouTubeSelection`, `missionOrchestrator.ts`)

- **Ordinal match ("prima")**: reuses the EXISTING `matchDisambiguationPick()` (Round D's ordinal +
  substring + token-overlap matcher, already proven for app disambiguation), mapping
  `YtCandidate{title}` → `{name: title}` at the call site. No new matching logic was written.
  **Confirmed live**: "prima" correctly resolved to the single extracted candidate.
- **"nu, altul"** (`YT_DECLINE_PATTERN`, explicitly excludes "da"): re-arms
  `pendingYouTubeSelection` with the same candidate set and re-asks. Mission stays alive. **Not
  device-tested this round** (see §7) — the live tests exercised the single-candidate path.
- **No match**: re-asks with the same candidate list rather than silently failing. Not separately
  device-tested.

## 6. ARCHITECTURAL RULE COMPLIANCE

- **No hardcoded titles anywhere** — confirmed live: the only candidate ever presented was
  "Mix – INNA - 50 Videos", read directly off the live screen each time, never invented.
- **Only the minimal candidate set reaches conversation** — capped at 5, only `{title}` surfaced.
- **Deliberate scope reduction, disclosed and unchanged from GOVERNANCE_1's report**: every search
  always asks which one, even with a single strong candidate (confirmed live: the one-candidate
  case still asked "Ce vrei să asculți?" rather than auto-selecting).

## 7. HONEST LIMITATIONS

- **YT-GOV-CHOICE-3 (ordinal "a doua" against multiple candidates)**: only exercised with a single
  candidate this round ("prima" → the only item) — the matcher code path is identical for "a doua"
  against a longer list (proven elsewhere for app disambiguation), but a genuine multi-candidate
  live run was not captured this session.
- **YT-GOV-CHOICE-4 ("nu, altul")**: implemented, not device-tested this round.
- The playback-verification fallback (`classNameContains: 'SeekBar'`) was validated against exactly
  one real playback event. It is a generic, class-based check (not YouTube-specific), but "worked
  once" is not the same bar as the repeated confirmation GOVERNANCE_1's search leg got.

## 8. REVERT CONSTANT

`pendingYouTubeSelection` and its two call sites in `missionOrchestrator.ts`, plus
`selectYouTubeCandidate` in `youtubeExecutor.ts`, are the only runtime additions this round makes;
removing them fully reverts GOVERNANCE_2 while leaving GOVERNANCE_1's search-only flow intact. The
`classNameContains` match field is a pure addition to the shared native DSL — safe to leave in
place regardless.

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| Candidates presented conversationally (never hardcoded) | **PASS**, confirmed live |
| Separate pending-selection state (doesn't touch app disambiguation) | **PASS**, code-inspected + exercised live without regressing the existing path |
| Ordinal resolution reuses existing matcher | **PASS** ("prima" resolved correctly, live) |
| Foreground reasserted before selection click | **PASS** — this exact fix is why the second live attempt's click succeeded |
| Playback verified via a real, durable signal before reporting success | **PASS** — SeekBar-class fallback confirmed live after the label-based check alone proved unreliable |
| YT-GOV-CHOICE-1 (search → offered titles) | **PASS** |
| YT-GOV-CHOICE-2 (select by title/ordinal → playback verified) | **PASS** |
| YT-GOV-CHOICE-3 (ordinal against 2+ candidates specifically) | **NOT_RUN** (only a 1-candidate case was live-tested) |
| YT-GOV-CHOICE-4 ("nu, altul") | **NOT_RUN** |

No PASS is claimed anywhere in this report from the build succeeding or from a bare click alone —
every PASS above is backed by an actual `adb logcat`/screenshot capture from this session, and each
one required tracking down and fixing a real defect first (see §4).
