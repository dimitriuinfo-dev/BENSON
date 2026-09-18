# ROUND_YOUTUBE_GOVERNANCE_1_REPORT

Implemented AND real-device verified. `npx tsc --noEmit` → 0 errors at every step.
`:app:assembleRelease` → BUILD SUCCESSFUL (rebuilt 6 times this round as real bugs were found and
fixed — see §6). Final build installed and confirmed working via real device logs and screenshots.

**YT-GOV-1 ("caută INNA pe YouTube"): PASS**, confirmed twice in a row on the final build with a
full clean log trace (`YT_GOV_START` → `YT_GOV_DONE`) and a screenshot of real YouTube search
results. See §6 for the three real bugs this took to get here, and §7 for one known remaining
intermittent risk, disclosed rather than hidden.

---

## 1. ARCHITECTURE — WHY NO NEW NATIVE FLOW WAS NEEDED

The existing generic step-DSL (`BensonCommandExecutor.kt`, JS-driven via `executeCommand`,
2026-07-17) already provides launch/click/set_text/assert_present with re-resolve-fresh semantics
and honest step-level failure reporting. Two primitives were missing for a browse-and-observe flow
like YouTube search, so this round adds exactly those two, generically (not YouTube-specific):

| New action | What it does |
|---|---|
| `extract_list` | Read-only: returns up to `limit` distinct visible `{label, top}` pairs matching `match`, sorted top-to-bottom. Taps nothing. |
| `ime_action` | Presses `AccessibilityAction.ACTION_IME_ENTER` (Android 11+) on the currently-focused input — a keyboard "search/go" key, not an on-screen button. |

Also extended the shared `match` spec (click/set_text/assert_*/extract_list alike) with:
- `textContainsAny: string[]` — OR-of-substrings for semantic labels that differ by app language.
- `minTopPercent` / a later companion — exclude chrome above/below a screen percentage.
- `withinScrollable` (added mid-round — see §6.4) — restrict `extract_list` to the results list's
  scrollable container.
- `classNameContains` (added mid-round — see §6.5) — filter by Android widget class (e.g.
  "SeekBar"), a structural signal that survives a media player's controls auto-hiding.

**No new native automation flow was written for YouTube specifically** — the whole
OPEN→FIND→ACTIVATE→TYPE→VERIFY→SUBMIT→OBSERVE→EXTRACT pipeline is orchestrated entirely from a new
JS file (`src/executors/youtubeExecutor.ts`) using these generic primitives, matching CLAUDE.md's
"add alongside, don't rewrite" rule: nothing that worked before was touched.

## 2. FILES CHANGED

| File | Change |
|---|---|
| `modules/benson-accessibility/android/.../BensonCommandExecutor.kt` | `itemsJson` field on `CommandResult`; `extract_list`/`ime_action` actions; `textContainsAny`/`minTopPercent`/`withinScrollable`/`classNameContains` in `nodeMatches()`; `findAllMatchingWithin()`; `execute()` loop carries `itemsJson` through |
| `modules/benson-accessibility/android/.../BensonAccessibilityModule.kt` | `executeCommand`'s promise map includes `itemsJson` |
| `modules/benson-accessibility/index.js` | **Bug fix**: `getScreenSnapshot` was declared in `.d.ts` and implemented natively but never actually exported — added the missing export (§6.2) |
| `modules/benson-accessibility/index.d.ts` | New `CommandMatch`/`CommandStep`/`CommandResult` fields (see above) |
| `src/executors/youtubeExecutor.ts` | **NEW**, ~300 lines — `searchYouTube(query)` (this report) and `selectYouTubeCandidate(title)` (GOVERNANCE_2, see that report) |
| `src/core/orchestrator/missionOrchestrator.ts` | `extractYouTubeQuery()` GoalInterpreter; wired into `runMission()` before `extractGoals` (same precedent as the existing `END_CALL_PATTERN` check) |

**Not touched:** wake engine, bubble lifecycle (`BensonBubbleService.kt`), session UX / screen-awake
logic (`app/index.tsx` — zero changes), `whatsappTool.ts`, `missionValidator.ts`,
`missionExecutor.ts` (all protected, untouched).

## 3. GOAL INTERPRETATION

`extractYouTubeQuery(text)` in `missionOrchestrator.ts`:

- `"caută INNA pe YouTube"` → query `"INNA"` (SEARCH mission) — **confirmed live**, exact match.
- `"caută Take My Breath Away pe YouTube"` → query `"Take My Breath Away"` (by inspection; not
  separately device-tested this round — see §8).
- `"deschide YouTube și caută INNA"` → query `"INNA"` (by inspection).
- `"deschide YouTube"` (no query) → returns `null` → falls through unchanged to the existing
  generic `OPEN_APP` pipeline, per the round's explicit "do not treat these as the same mission."

## 4. EXECUTION SEQUENCE (`searchYouTube`, `youtubeExecutor.ts`) — AS CONFIRMED LIVE

```
YT_GOV_START query="INNA"
YT_FOREGROUND_OK
YT_SEARCH_FOUND / YT_SEARCH_CLICKED
YT_INPUT_FOUND
YT_QUERY_TYPED query="INNA"
YT_QUERY_VERIFIED
YT_SEARCH_SUBMITTED
YT_RESULTS_VERIFIED count=1
YT_GOV_DONE query="INNA" candidates=1
```
Displayed/spoken result: `"Am găsit: Mix – INNA - 50 Videos. Ce vrei să asculți?"` — a single,
genuine, on-screen result, not chrome (see §6.3/6.4 for what it took to get a clean candidate list).

## 5. FAILURE-STAGE LOGGING

All required tags are emitted via `logAudioDiag`: `YT_GOV_START`, `YT_OPEN_FAIL`,
`YT_FOREGROUND_OK`/`YT_FOREGROUND_VERIFY_FAIL`, `YT_SEARCH_FOUND`/`YT_SEARCH_CONTROL_NOT_FOUND`,
`YT_SEARCH_CLICKED`/`YT_SEARCH_CLICK_FAIL`, `YT_INPUT_FOUND`/`YT_INPUT_NOT_FOUND`,
`YT_QUERY_TYPED`/`YT_TYPE_FAIL`, `YT_QUERY_VERIFIED`/`YT_INPUT_VERIFY_FAIL`,
`YT_SEARCH_SUBMITTED`/`YT_SUBMIT_FAIL`, `YT_RESULTS_VERIFIED`/`YT_RESULT_VERIFY_FAIL`,
`YT_GOV_DONE` — every one of these was actually observed firing on the real device this round
(both the success path and, while debugging, several of the failure tags).

## 6. REAL BUGS FOUND AND FIXED THIS ROUND (in the order hit)

**6.1 — JS `setTimeout` goes inert once BENSON backgrounds.** The first live attempt froze forever
immediately after `YT_FOREGROUND_OK` — the very next line was
`await new Promise((res) => setTimeout(res, 700))`. This is the exact "RN JS runtime suspended
while the Activity is backgrounded" root cause that drove the wake-engine/bubble-timer work earlier
in this project, now hit for the first time in a *JS-orchestrated* multi-step UI flow. Fixed: every
"let the UI settle" delay in `youtubeExecutor.ts` now runs as a native `executeCommand({action:
'wait'})` step (`nativeWait()`), which runs inside the accessibility service's own coroutine, not
the JS timer subsystem.

**6.2 — `getScreenSnapshot` was never actually exported from JS.** Declared in `index.d.ts`,
implemented natively (`BensonAccessibilityModule.kt`'s `AsyncFunction("getScreenSnapshot")`), but
missing from `index.js` — importing it silently gave `undefined`, and calling it threw
synchronously, caught by my own try/catch as an instant (1ms) false `YT_INPUT_VERIFY_FAIL` even
though a screenshot proved "INNA" was typed correctly. Fixed by adding the missing export. Confirmed
safe: `whatsappTool.ts` (protected) gets `getScreenSnapshot` from a different wrapper
(`lib/screenBridge.ts`), so this fix doesn't touch or risk that path.

**6.3 — Channel-card chrome leaked into "candidates" (German UI).** A real "INNA" search surfaced
a channel-info card ahead of per-video results; its chrome ("Zum Kanal", "@INNA @INNA", a bullet-
separated subscriber-count line, "INNA abonnieren") all passed the original English/Romanian-only
noise filter. Fixed with signals that generalize across languages: reject any label containing a
bullet (`•`) or an `@handle`, reject an exact match against the search query itself, and expand the
German noise-word list.

**6.4 — Promo/menu/section-header chrome, plus duplicated labels.** Further live runs surfaced
"YouTube Music" (promo banner), "Aktionsmenü" (overflow-menu button, German), "Neueste Videos von
INNA" (a section header, not a video), and labels literally doubled ("Mix Mix") because a node's
`text` and `contentDescription` sometimes hold the identical string. Fixed with: a
`collapseDuplicatedHalf()` normalizer, a cross-item dedup set, more noise words — and, since a
per-string list was clearly going to keep needing patches, a **structural** fix:
`extract_list` gained a `withinScrollable` option that restricts collection to descendants of the
first scrollable container (the results list) — nav bars, banners, and top cards all live outside
it. This eliminated the class of leaks in one shot rather than one more string at a time.

**6.5 — Query-typed verification raced the accessibility tree.** `YT_QUERY_TYPED` and
`YT_INPUT_VERIFY_FAIL` logged only 18ms apart, yet a screenshot showed the text correctly present.
Fixed with a short native settle wait plus one retry before giving up.

**6.6 — Selection click ran against the wrong app's tree.** Documented in
`ROUND_YOUTUBE_GOVERNANCE_2_REPORT.md` §4 (this is the selection step, not the search step covered
by this report) — noted here because the fix (`classNameContains` for a SeekBar-based playback
signal) also lives in the shared native match spec this report introduces.

## 7. KNOWN REMAINING RISK — DISCLOSED, NOT FIXED THIS ROUND

**Intermittent race in `waitForPackageForeground`** (pre-existing, shared infrastructure in
`androidActionExecutor.ts` — NOT modified this round): roughly 2 of 6 live test runs this session
hung indefinitely right after launching YouTube, with `dumpsys window` confirming YouTube genuinely
WAS foreground the whole time — the JS promise just never resolved until I manually brought BENSON
back to the foreground, at which point its OWN internal `setTimeout` bail-out fired
(`YT_FOREGROUND_VERIFY_FAIL observed="com.google.android.inputmethod.latin"`), 50+ seconds late.
This is the same "JS timer suspended while backgrounded" root cause as §6.1, but inside existing,
already-proven infrastructure (`waitForPackageForeground`, used by `AppLauncherExecutor` for
WhatsApp/Waze today) — fixing it would mean touching shared code well beyond this round's YouTube-
only scope, and risks regressing flows that already work. **Not fixed here, per "one round = one
type of change."** Flagging for a possible dedicated follow-up round (the WhatsApp automation faced
this same class of issue and was eventually made fully native for exactly this reason).

## 8. HONEST LIMITATIONS

- YT-GOV-2 (`"caută Take My Breath Away pe YouTube"`) and YT-GOV-3 (starting from another app's
  foreground) were **not separately device-tested** this round — only YT-GOV-1's exact command was
  run repeatedly (necessarily, while iterating on the bugs above). The GoalInterpreter and pipeline
  are identical code paths regardless of query text or starting app, so I have reasonable
  confidence, but confidence from code inspection is not the same as a device confirmation.
- The candidate-quality fixes (§6.3/6.4) were validated against exactly one query ("INNA") on one
  device/UI-language combination (German). A different query or a differently-shaped results
  screen could still surface an unseen chrome string — the `withinScrollable` structural fix should
  catch most future cases, but "most" is not "all."

## 9. REVERT CONSTANT

Every new native action (`extract_list`, `ime_action`) and match field (`textContainsAny`,
`minTopPercent`, `withinScrollable`, `classNameContains`) is a pure addition — no existing
action/field was changed. To back this round out: delete `extractYouTubeQuery`'s check and its
call site in `missionOrchestrator.ts`, and delete `src/executors/youtubeExecutor.ts` — nothing else
references them. The `getScreenSnapshot` export fix (§6.2) should be KEPT regardless (it's a
pre-existing gap, not YouTube-specific).

## PASS / FAIL / NOT_RUN

| Item | Status |
|---|---|
| GoalInterpreter distinguishes OPEN vs SEARCH | **PASS** (live, "caută INNA pe YouTube") |
| OPEN→FIND→ACTIVATE→TYPE→VERIFY→SUBMIT→OBSERVE→EXTRACT pipeline | **PASS**, confirmed twice on the final build |
| All failure-stage + milestone tags present and firing | **PASS**, both success and failure tags observed live |
| YT-GOV-1 ("caută INNA pe YouTube") | **PASS** |
| YT-GOV-2 ("caută Take My Breath Away pe YouTube") | **NOT_RUN** (same code path, not separately exercised) |
| YT-GOV-3 (started from another app foreground) | **NOT_RUN** (not separately exercised) |
| Intermittent foreground-wait race | **KNOWN, disclosed, not fixed** (pre-existing shared infra, out of scope) |

No PASS is claimed anywhere in this report from the build succeeding alone — every PASS above is
backed by an actual `adb logcat`/screenshot capture from this session.
