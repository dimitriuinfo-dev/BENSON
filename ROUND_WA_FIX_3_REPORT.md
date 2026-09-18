# ROUND_WA_FIX_3_REPORT

WA-FIX-3 — fast semantic scroll-to-top by **bursts** of `ACTION_SCROLL_BACKWARD` (one tree read
per burst instead of per row).

Device `9c1464eb` / OnePlus Nord 4 / OxygenOS 15. WhatsApp `2.26.34.81`.

---

## Status

| Item | Result |
|---|---|
| Files changed | `BensonAccessibilityService.kt` only |
| Function changed | `waScrollChatsToTop()` (rewritten); helpers `waScrollActionIds` / `waSupportsScrollToPosition` kept |
| Scope | only `waScrollChatsToTop()` + its constants + burst logging. Nothing else touched. |
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` | **PASS** — `BUILD SUCCESSFUL in 35s`, APK 260,938,968 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install `9c1464eb` | PASS — `Success`, data preserved |
| **ACC-WA-SEARCH-2** | **FAIL — `BURST_SCROLL_TIMEOUT`** |
| ACC-WA-SEARCH-1 | NOT_RUN (gated on -2) |
| ACC-WA-SEARCH-3 | NOT_RUN (gated on -2) |
| **VERIFIED `CALL_STARTED` reached?** | **NO** — failed in `waScrollChatsToTop()`; no SET_TEXT / contact match / call button. **No call placed** (contact "Baby" not rung). |

Per rule 12: **STOPPED after -2. No auto-patch.**

---

## Constants used (WA-FIX-3)

| Constant | Value |
|---|---|
| `WA_FIX3_BURST_SIZE` | 8 |
| `WA_FIX3_INTER_ACTION_DELAY_MS` | 55 |
| `WA_FIX3_POST_BURST_SETTLE_MS` | 250 |
| `WA_FIX3_MAX_TOTAL_SCROLL_ACTIONS` | 120 |
| `WA_FIX3_MAX_WALL_CLOCK_MS` | 15_000 |

## Function changed

`waScrollChatsToTop()` — new body:
1. log `WA_CHAT_SCROLL_ACTIONS supported="…"`; if header already present → success.
2. `ACTION_SCROLL_TO_POSITION` probe (kept as diagnostic; falls through instantly — unsupported on this build).
3. **burst loop** while `totalActions < 120` and `elapsed < 15 s`:
   - reacquire `android:id/list` (once, with a single retry if null);
   - fire up to 8 `ACTION_SCROLL_BACKWARD` with only a 55 ms inter-action delay and **no tree read between actions**; stop the inner burst early if an action returns `false`;
   - one 250 ms settle, then **one** tree read → check header + first-row signature;
   - `WA_CHAT_SCROLL_BURST burst=… requested=… accepted=… totalActions=… pre="…" post="…" elapsedMs=…`;
   - header present → `WA_SEARCH_HEADER_RECOVERED method=scroll_backward_burst …`, success;
   - `post != pre` → `state=progress`; `post == pre` once → `state=stalled_candidate`; twice → `state=stalled` + `WA_SEARCH_NORMALIZE_FAIL reason=scroll_stalled`;
   - all-refused burst → reacquire list + one retry burst; still refused + unchanged → `reason=scroll_action_refused`.
4. budgets exhausted → `WA_SEARCH_NORMALIZE_FAIL reason=scroll_timeout`.

## Device run — ACC-WA-SEARCH-2

**Starting state (uiautomator dump):** WhatsApp Chats tab active, list scrolled deep (14× swipe-up in prep), `search-ish nodes = 0`, first visible chat `‎Dr.Dimitriu-Baltres, Lau und Mirela`. Backgrounded (Home).

**Trigger (real production path):** BENSON Debug Panel → `suna pe Baby pe WhatsApp` → mission `COMMUNICATION_PROBLEM` / `PREPARE_MESSAGE` / `voice_call` / contact "Baby" → confirm `da` → `confirmActiveMission` → `runWhatsAppCallNative("Baby")`.

**Trace (`BENSON_AUDIO`, 10:03:55 → 10:04:11):**
```
WA_SEARCH_NORMALIZE_START
WA_CHATS_TAB state=active                       ✓
WA_SEARCH_HEADER state=missing                  ✓
WA_CHAT_SCROLL_ACTIONS supported="1,4,8,32,64,256,512,4096,8192,131072,16908342"
WA_CHAT_SCROLL_TO_POSITION attempted=false supported=false result=false
WA_CHAT_SCROLL_BURST burst=1  requested=8 accepted=8 totalActions=8   pre="George"              post="Cata Foliator"        elapsedMs=824
WA_CHAT_SCROLL_BURST burst=2  requested=8 accepted=8 totalActions=16  pre="Cata Foliator"       post="+49 1520 9122341"     elapsedMs=1010
WA_CHAT_SCROLL_BURST burst=3  requested=8 accepted=8 totalActions=24  pre="+49 1520 9122341"    post="Uwe Funk"             elapsedMs=956
WA_CHAT_SCROLL_BURST burst=4  requested=8 accepted=8 totalActions=32  pre="Uwe Funk"            post="Marion Aulenbacher"   elapsedMs=879
WA_CHAT_SCROLL_BURST burst=5  requested=8 accepted=8 totalActions=40  pre="Marion Aulenbacher"  post="Angi"                 elapsedMs=972
WA_CHAT_SCROLL_BURST burst=6  requested=8 accepted=8 totalActions=48  pre="Angi"                post="Andreas Kasper Physi" elapsedMs=1067
WA_CHAT_SCROLL_BURST burst=7  requested=8 accepted=8 totalActions=56  pre="Andreas Kasper Physi" post="ListB Strobl"        elapsedMs=995
WA_CHAT_SCROLL_BURST burst=8  requested=8 accepted=8 totalActions=64  pre="ListB Strobl"        post="CRETU Andrei (ENGIE " elapsedMs=958
WA_CHAT_SCROLL_BURST burst=9  requested=8 accepted=8 totalActions=72  pre="CRETU Andrei (ENGIE " post="+40 758 394 489"     elapsedMs=1038
WA_CHAT_SCROLL_BURST burst=10 requested=8 accepted=8 totalActions=80  pre="+40 758 394 489"     post="Software Group"       elapsedMs=882
WA_CHAT_SCROLL_BURST burst=11 requested=8 accepted=8 totalActions=88  pre="Software Group"      post="Alex Lazu Mural"      elapsedMs=850
WA_CHAT_SCROLL_BURST burst=12 requested=8 accepted=8 totalActions=96  pre="Alex Lazu Mural"     post="Evaluator Moldovan"   elapsedMs=1008
WA_CHAT_SCROLL_BURST burst=13 requested=8 accepted=8 totalActions=104 pre="Evaluator Moldovan"  post="Oana Dl Ovidiu"       elapsedMs=959
WA_CHAT_SCROLL_BURST burst=14 requested=8 accepted=8 totalActions=112 pre="Oana Dl Ovidiu"      post="Elektro Herr Steigen" elapsedMs=1003
WA_SEARCH_NORMALIZE_FAIL reason=scroll_timeout
WA_NATIVE_FAIL stage=SEARCH_HEADER_NOT_RECOVERED elapsedMs=16228 contact="Baby"
```

Post-failure dump: `android:id/list` first child `contact_row_container`, first visible chat `Kunz`, `search-ish = 0`.

### Observed numbers

| Metric | Value |
|---|---|
| bursts executed | 14 |
| total `ACTION_SCROLL_BACKWARD` requested / accepted | 112 / 112 |
| every burst | `state=progress` (never stalled — the list did keep moving) |
| rows actually traversed | **~14** (`George` → … → `Elektro Herr Steigen`) |
| per-burst wall time | ~0.9–1.1 s |
| normalisation latency to failure | 15 s budget hit (`elapsedMs=16228` end-to-end) |
| header recovered | **NO** |

## Why it FAILED — `ACTION_SCROLL_BACKWARD` coalesces

Every burst had `accepted=8` yet the first-row signature advanced by **~1 contact per burst** — 8 accepted scroll actions produced **1 row of movement**. Firing `performAction(ACTION_SCROLL_BACKWARD)` 8× in ~440 ms does **not** scroll 8 rows: WhatsApp's `RecyclerView` (and/or the accessibility pipeline) **coalesces rapid repeated scroll events into ~1 scroll step per settle**. `accepted=true` means "event queued", not "one discrete row scrolled".

Net effect: the burst rewrite removed the wasted per-row tree reads but **throughput is still ~1 row/second** — identical to WA-FIX-2's per-row loop. 14 s of scrolling bought 14 rows; the list was 60+ rows deep.

## Combined conclusion across WA-FIX-1/2/3

`android:id/list` on WhatsApp `2.26.34.81` exposes to accessibility **only** `SCROLL_FORWARD (4096)` and `SCROLL_BACKWARD (8192)` (+ `showOnScreen`). No `ACTION_SCROLL_TO_POSITION`, no `ACTION_SCROLL_UP`, no page action. `SCROLL_BACKWARD` moves **~1 row per honoured event** and **coalesces under bursts**, so semantic scroll throughput is hard-capped at roughly **1 row/second**.

**A realistically deep chat list (60–100+ rows) cannot be scrolled to the top within a call-appropriate latency using semantic AccessibilityNodeInfo actions.** This is the "report explicitly rather than add coordinate swipes" outcome the round anticipated.

## Options (NOT implemented — for the next decision)

1. **Coordinate swipe / `dispatchGesture`.** The only thing proven to move the list fast (test-prep swipes cleared ~60 rows in ~5 s). The app cannot do this today: `android:canPerformGestures="false"` in `accessibility_service_config.xml` (a protected file, and a deliberate OEM-anti-spyware posture). Enabling it is a product/architecture decision, out of this round's scope and explicitly forbidden here.
2. **Stop depending on the collapsing header.** Open WhatsApp search another way — e.g. the `com.whatsapp:id/menuitem_overflow` (⋮) menu may carry a "Search" item on this build (its contents were never dumped), or a WhatsApp search intent/deep-link. Different mechanism, outside "changes ONLY `waScrollChatsToTop()`".
3. **Scope the guarantee.** Normalise only when the list is shallowly scrolled — succeed within a bounded budget (≈12 s ⇒ ≈12–15 rows), otherwise fail cleanly with a clear message. Makes the common case (user scrolled a little) work; the rare deep-scroll case fails honestly. Viable as a future scoped round.

## ACC-WA-SEARCH results

```
ACC-WA-SEARCH-2: FAIL  (BURST_SCROLL_TIMEOUT — ACTION_SCROLL_BACKWARD coalesces; ~1 row/s; 14 rows in 15 s; header never recovered; no call placed)
ACC-WA-SEARCH-1: NOT_RUN
ACC-WA-SEARCH-3: NOT_RUN
```

Exact last successful stage: `WA_CHAT_SCROLL_BURST burst=14` (`state=progress`) — the list was still moving when the wall-clock budget expired.

## Remaining limitation

Semantic-only scroll-to-top of WhatsApp's chat list is throughput-limited to ~1 row/s (BACKWARD coalescing) with no jump-to-position action available; deep scroll states are not recoverable at call latency without either a coordinate gesture capability or a non-scroll route to search.

## CONFIRM

- source changed outside `BensonAccessibilityService.kt`: **NO**
- change limited to `waScrollChatsToTop()` + its constants/logging: **YES**
- coordinate gestures / `dispatchGesture` / screen coordinates added: **NO**
- git / prebuild: **NO**
- real WhatsApp call placed: **NO**
- reached VERIFIED `CALL_STARTED`: **NO**
