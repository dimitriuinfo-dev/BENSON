# ROUND_SPOTIFY_GOVERNANCE_1_REPORT

Implemented AND real-device verified for 5 of 6 acceptance tests. The generic media-governance
architecture was **not** modified — no changes to `mediaGovernor.ts`'s MediaSession control,
`youtubeExecutor.ts`, the wake engine, overlay/session UX, or `returnToBensonFromMedia()`. Every
fix this round is either a genuinely generic primitive addition (usable by any provider) or a
small, disclosed, provider-specific adapter entry — exactly the scope the round asked for.

`npx tsc --noEmit` → 0 errors at every step. `:app:assembleRelease` → BUILD SUCCESSFUL (rebuilt 6
times this round chasing real, precisely-diagnosed bugs on the actual device).

---

## 1. THE INVESTIGATION (not fixed with fixed coordinates — as required)

Starting state: "Spotify reaches the search screen, but its search field is a tap-through
placeholder." Rather than guess, I inspected the real Accessibility/UI tree through the actual
transition (`uiautomator dump`, `dumpsys input_method`, `dumpsys media_session`, and BENSON's own
diagnostic logging added this round):

1. **Confirmed the tap-through behavior is real**: `dumpsys input_method`'s `mInputShown` and
   `mServedView` fields, plus a screenshot showing a genuine blinking cursor and keyboard, proved a
   second tap on the placeholder activates a real, IME-focused input elsewhere in the tree.
2. **Found the real editable node exists** (`android.widget.EditText`, `clickable=true`,
   `focusable=true`) one level above the placeholder TextView — but only in a POST-focus tree dump;
   before focus, `uiautomator dump` failed to serialize that region of the (Compose-based) screen
   at all (a real, separate tooling limitation, not a BENSON bug — the same class of dump
   unreliability already documented for BENSON's own animated screens, now also seen on a
   third-party Compose app).
3. **Confirmed a real touch tap DOES activate the field** at its current on-screen position — but
   a tap at the position from the POST-focus dump did NOT (the search bar's layout shifts once
   focused/scrolled) — ruling out "Compose doesn't respond to touch" and confirming coordinates are
   genuinely unstable, which is exactly why this implementation never uses them.
4. **Added `CLICK_TARGET_DIAG`/`SET_TEXT_ON_FOCUS_DIAG` native logging** (kept in the codebase —
   low-noise, generically useful for diagnosing any future click/focus mismatch) to see precisely
   what BENSON's own Accessibility-driven click actually landed on, without more screenshot
   round-trips. This is what found the real root cause (§2).

## 2. ROOT CAUSE AND FIX — ACTIVATE_SEARCH_INPUT

**Root cause**: `root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` correctly finds the genuinely
IME-served node (confirmed: it matches `dumpsys input_method`'s `mServedView`) — but that node is a
Compose **merged-semantics wrapper** spanning the whole search bar. It reports `isEditable=false`
and rejects `ACTION_SET_TEXT` directly; the real editable leaf is a descendant Compose folds into
it for accessibility purposes.

**Fix, generic, not Spotify-specific**: added a new native action `set_text_on_focus` that types
into whatever node Android's own input-focus tracking currently points at — the correct entry point
for a field that doesn't reliably expose `{editable:true}` in the tree, regardless of which app or
toolkit renders it. When that focused node isn't itself editable, it does a **bounded search of
that node's own subtree** (never the whole screen) for the first `isEditable` descendant and types
there instead. This is "look inside the thing focus already pointed us at," not "search the tree by
criteria" — confirmed live: `action_set_text_accepted=true`, and a screenshot immediately after
showed "INNA" correctly typed.

The two-tap "activation" step itself (`searchActivationHints` on Spotify's `MediaProvider` entry)
is the **provider-specific adapter detail** the round explicitly allows — data (a locator hint
list, same category as the already-accepted `searchIconHints`), not a phrase/intent script.
Providers whose field is directly editable (YouTube) simply omit it.

## 3. TWO MORE REAL BUGS FOUND AND FIXED

**No explicit submit needed/exists**: both `ime_action` and the icon-click submit fallback failed
for Spotify — yet a screenshot at that exact moment already showed real "INNA" results (Spotify
shows live/incremental results as you type). Fixed by making submit best-effort in the generic path
only (`mediaSearchExecutor.ts`) rather than a hard failure — `youtubeExecutor.ts`, which does need
and successfully use an explicit submit, was not touched.

**Candidate noise, twice more, then genuinely clean**: the live-typeahead **autocomplete
suggestion** rows ("Vorschlag „inna hot" hinzufügen" = "Add suggestion 'inna hot'") and then
per-result **category/metadata labels** ("Playlist", "Verifiziert", "Künstler*in") both leaked into
candidates before being filtered — the first via new noise words plus a generic
"all-lowercase-echo-of-the-query" heuristic (autocomplete echoes the literal typed text; real
titles use normal capitalization), the second via an **exact-match-only** noise set (unlike the
substring-based `CHROME_NOISE` list, words like "Single"/"Album" are also plausible real title
substrings, so these are only rejected when the ENTIRE candidate is nothing but the label).
Confirmed live, final state: a single, genuine candidate — `"Best of INNA"` — extracted from the
real screen, nothing invented, nothing left over.

## 4. REAL-DEVICE EVIDENCE — TRANSPORT CONTROL (SPOTIFY-4/5/6)

Since SPOTIFY-3's own selection didn't yet land on real INNA playback (see §5), transport control
was verified against whatever Spotify session was actually active — proving the SAME generic
mechanism ROUND_MEDIA_GOVERNANCE_1 proved for YouTube also works, unmodified, for a second,
independent provider (the core claim this round exists to support):

| Step | Result | Authoritative confirmation |
|---|---|---|
| "pauza" | PASS | `dumpsys media_session` (Spotify session) → `state=PAUSED(2)` |
| "continua" | PASS | `dumpsys media_session` → `state=PLAYING(3)` |
| "opreste si revino la benson" | PASS | pause action succeeded cleanly, single attempt, no needless cascade (the polarity-bug fix from ROUND_MEDIA_GOVERNANCE_1 held); `dumpsys media_session` → `state=PAUSED(2)`; `dumpsys window` → `mCurrentFocus=...com.benson.butler/.MainActivity` |

Spotify's session `actions` bitmask also does not advertise `ACTION_STOP` (same as YouTube) —
`stopMedia()`'s PAUSE-fallback path was exercised correctly and reported the honest
"Am pus pauză (aplicația nu are o oprire completă)." message, not a false "oprit."

## 5. SPOTIFY-3 — FAIL, PRECISELY DIAGNOSED

Selecting "Best of INNA" reported `MEDIA_SELECT_DONE` (the click was accepted), but a screenshot
taken immediately after showed a **green checkmark badge appear next to the result row** — Spotify's
"added to your library" confirmation — with no navigation into the playlist and no change to the
active MediaSession (it still showed the pre-existing track, unchanged). The click, using
`{textContains: title, clickableAncestor: true}`, is landing on the row's implicit
"add/quick-action" affordance rather than an "open" affordance — a second, distinct Spotify-specific
tap-target detail beyond the one solved in §2, not yet solved this round.

**Not fixed this round** — disclosed rather than papered over. A plausible next step (not
attempted): the result row likely has a separate clickable region (e.g. the thumbnail image) whose
`ACTION_CLICK` opens/plays rather than adds; distinguishing it needs another close tree inspection
of a real result row's exact child structure, the same investigative method used successfully in
§2.

## 6. WHAT WAS NOT ATTEMPTED (per this round's explicit scope)

Gesture-based tapping (`AccessibilityService.dispatchGesture()`) was considered as a way to
bypass `ACTION_CLICK` ambiguity entirely, then **rejected**: it requires the `canPerformGestures`
capability, which this project has deliberately avoided project-wide because OxygenOS's
anti-spyware system disables the **entire** Accessibility Service for apps that request it — a
regression risk to every existing feature, not just Spotify, and not worth taking for one
provider's tap target.

## 7. FILES CHANGED

| File | Change |
|---|---|
| `modules/benson-accessibility/android/.../BensonCommandExecutor.kt` | `set_text_on_focus` action + `findEditableDescendant()`; `CLICK_TARGET_DIAG`/`SET_TEXT_ON_FOCUS_DIAG` diagnostic logging (kept — generically useful, low-noise) |
| `modules/benson-accessibility/index.d.ts` | `set_text_on_focus` added to `CommandStep` |
| `src/executors/mediaSearchExecutor.ts` | `searchActivationHints` on `MediaProvider` + the activation step; `set_text_on_focus` used when a provider declares it; submit made best-effort; autocomplete-echo + exact-label noise filters |

**Not touched:** `mediaGovernor.ts`, `youtubeExecutor.ts`, wake engine, overlay/session UX,
`missionOrchestrator.ts` (this round's fixes are all inside the two executor files above).

## 8. REVERT CONSTANT

`searchActivationHints` on Spotify's `MediaProvider` entry is the only per-provider behavior
change; removing it (and the `usesFocusInput` branch it gates in `searchMedia`) reverts Spotify to
its pre-round state. `set_text_on_focus` and the two noise-filter refinements are pure, safe
additions with no other callers to break.

## PASS / FAIL / NOT_RUN / IMPLEMENTED_ONLY

| Item | Status |
|---|---|
| SPOTIFY-1 (search opens, query typed+verified, results appear) | **PASS** |
| SPOTIFY-2 (real result extracted from observed UI) | **PASS** — "Best of INNA," confirmed clean after 3 rounds of noise-filter refinement |
| SPOTIFY-3 (select → playback verified) | **FAIL** — click lands on "add to library," not "open/play"; precisely diagnosed, not yet fixed |
| SPOTIFY-4 ("pauză" verified paused) | **PASS** — authoritative `dumpsys media_session` confirmation |
| SPOTIFY-5 ("continuă" verified playing) | **PASS** — authoritative confirmation |
| SPOTIFY-6 ("oprește și revino la Benson") | **PASS** — authoritative confirmation, both media state and BENSON foreground |

No PASS is claimed anywhere in this report from the build succeeding or from a native call
returning `true` alone — every PASS above is backed by an actual screenshot and/or an OS-level
`dumpsys media_session`/`dumpsys window` read.
