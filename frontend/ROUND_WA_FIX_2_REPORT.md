# ROUND_WA_FIX_2_REPORT

WA-FIX-2 — replace the weak fixed-8 `ACTION_SCROLL_BACKWARD` normalisation with a semantic
scroll-to-top: try `ACTION_SCROLL_TO_POSITION(0)` first, else an adaptive bounded
`ACTION_SCROLL_BACKWARD` loop with real stop conditions.

Device `9c1464eb` / OnePlus Nord 4 / OxygenOS 15. WhatsApp `2.26.34.81`.

---

## Status

| Item | Result |
|---|---|
| Implementation | DONE — 1 function rewritten, 2 tiny helpers added, constants swapped |
| Source changed outside `BensonAccessibilityService.kt` | NO |
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` (from `frontend/android/`) | PASS — `BUILD SUCCESSFUL in 38s`, APK 260,938,968 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install on `9c1464eb` | PASS — `Success`, data preserved |
| **ACC-WA-SEARCH-2** | **FAIL** — `SCROLL_TO_POSITION_UNSUPPORTED` → fallback `HEADER_NOT_RECOVERED` (`reason=scroll_timeout`) |
| ACC-WA-SEARCH-1 | NOT_RUN (gated on -2) |
| ACC-WA-SEARCH-3 | NOT_RUN (gated on -2) |
| **Real call reached VERIFIED `CALL_STARTED`?** | **NO** — failed at scroll-to-top normalisation; no SET_TEXT, no contact match, no call button, **no call placed** (contact "Baby" not rung) |

Per the round's FAIL protocol: **STOPPED after -2. No automatic patch.**

---

## 1. Files modified

`modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt` only.

## 2. Exact function(s) changed

- **`waScrollChatsToTop()`** — fully rewritten (WA-FIX-2 strategy below).
- **`waScrollActionIds(node)`** — new helper: `node.actionList.joinToString(","){ it.id.toString() }`.
- **`waSupportsScrollToPosition(node)`** — new helper: `node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id }`.
- Constants: removed `WA_FIX1_MAX_SCROLL_ATTEMPTS = 8`; added `WA_FIX2_MAX_BACKWARD_ATTEMPTS = 40`, `WA_FIX2_NORMALIZE_BUDGET_MS = 9000L`.
- `ensureWhatsAppChatsSearchAvailable()` — dropped one now-redundant trailing `WA_SEARCH_NORMALIZE_FAIL` log line (the rewritten `waScrollChatsToTop()` emits its own specific reason). No behavioural change there.

Nothing else touched — search selectors, `openWhatsAppSearch`, contact typing/selection, call-button, `CALL_STARTED` verification, wakeword, STT, mic-hold, `CALL_ENDED`, auto-return, confirmation, `app/index.tsx` all unchanged.

## 3. Supported accessibility scroll actions on `android:id/list` (runtime, this build)

`WA_CHAT_SCROLL_ACTIONS supported="1,4,8,32,64,256,512,4096,8192,131072,16908342"`

| id | action |
|---|---|
| 1 | FOCUS |
| 4 | SELECT |
| 8 | CLEAR_SELECTION |
| 32 | LONG_CLICK |
| 64 | ACCESSIBILITY_FOCUS |
| 256 / 512 | NEXT / PREVIOUS_AT_MOVEMENT_GRANULARITY |
| **4096** | **SCROLL_FORWARD** |
| **8192** | **SCROLL_BACKWARD** |
| 131072 | SET_SELECTION |
| 16908342 | `R.id.accessibilityActionShowOnScreen` |

**Not present:** `ACTION_SCROLL_TO_POSITION` (16908343), `ACTION_SCROLL_UP` (16908344), pageUp (16908358). The only vertical-scroll levers WhatsApp's chat list exposes to accessibility are **SCROLL_FORWARD / SCROLL_BACKWARD**.

## 4. Did `ACTION_SCROLL_TO_POSITION` work?

**No — not supported.** `waSupportsScrollToPosition(list)` returned `false`; log `WA_CHAT_SCROLL_TO_POSITION attempted=false supported=false result=false`. Detection was correct (the action id is genuinely absent from `list.actionList`). It was never attempted, per spec (no invented argument keys, no blind `performAction`).

## 5. Fallback behaviour (as implemented) and why it FAILED

Adaptive `ACTION_SCROLL_BACKWARD` loop, `WA_FIX2_MAX_BACKWARD_ATTEMPTS = 40`, wall-clock budget `WA_FIX2_NORMALIZE_BUDGET_MS = 9000 ms`. Per attempt: `performAction(ACTION_SCROLL_BACKWARD)` → ≤400 ms settle poll for the header → read `waFirstRowSignature()` → log `WA_CHAT_SCROLL_TOP attempt=N first="<sig>" actionResult=<bool>`. Stop conditions: header present (SUCCESS); `!actionResult && signature stable` (TERMINAL TOP → `header_not_recovered`); signature unchanged 3× (`scroll_stalled`); budget exceeded (`scroll_timeout`).

**Device result (09-09 09:52:41→51):**
```
WA_SEARCH_NORMALIZE_START
WA_CHATS_TAB state=active                              ✓
WA_SEARCH_HEADER state=missing                         ✓
WA_CHAT_SCROLL_ACTIONS supported="1,4,8,32,64,256,512,4096,8192,131072,16908342"
WA_CHAT_SCROLL_TO_POSITION attempted=false supported=false result=false
WA_CHAT_SCROLL_TOP attempt=1  first="Dr Tiemann"               actionResult=true
WA_CHAT_SCROLL_TOP attempt=2  first="Rudolf Schwindl"          actionResult=true
WA_CHAT_SCROLL_TOP attempt=3  first="Leni Cùsin"               actionResult=true
WA_CHAT_SCROLL_TOP attempt=4  first="Gabi Ziegler Elektrotech" actionResult=true
WA_CHAT_SCROLL_TOP attempt=5  first="M2 Racing 2026🔥"          actionResult=true
WA_CHAT_SCROLL_PROGRESS state=progress
WA_CHAT_SCROLL_TOP attempt=6  first="Martin Hedel"             actionResult=true
WA_CHAT_SCROLL_TOP attempt=7  first="Robi"                     actionResult=true
WA_CHAT_SCROLL_TOP attempt=8  first="Girtofan Andrei"          actionResult=true
WA_CHAT_SCROLL_TOP attempt=9  first="Kupfer Schenk"            actionResult=true
WA_CHAT_SCROLL_TOP attempt=10 first="Frau Biegel Privat"       actionResult=true
WA_CHAT_SCROLL_PROGRESS state=progress
WA_CHAT_SCROLL_TOP attempt=11 first="Mina"                     actionResult=true
WA_CHAT_SCROLL_TOP attempt=12 first="Curent Paul"              actionResult=true
WA_SEARCH_NORMALIZE_FAIL reason=scroll_timeout
WA_NATIVE_FAIL stage=SEARCH_HEADER_NOT_RECOVERED elapsedMs=10286 contact="Baby"
```

**Root cause:** `ACTION_SCROLL_BACKWARD` on WhatsApp's `android:id/list` advances **exactly ~1 conversation row per call** (the `first=` label steps through the list one contact at a time), and each iteration (action + 400 ms settle + 3 tree reads for header/signature) costs **~800 ms**. So the 9 s budget buys only ~12 rows. The list was scrolled deep (test prep: 10 aggressive swipes ≈ 60+ rows); post-fail `uiautomator dump` shows first visible chat "HAMIMO", `android:id/list` first child still `contact_row_container`, `search-ish: 0` — nowhere near item 0.

The mechanism is *correct* (it scrolls the right way, detects the header correctly, fails cleanly, no blind typing / no call) but **far too slow per step** to traverse an arbitrary scroll depth within any call-appropriate time budget. Raising the budget/attempts to cover 60+ rows would mean ~50 s of scrolling before a call — not acceptable.

## 6. Max attempts / time budget

`WA_FIX2_MAX_BACKWARD_ATTEMPTS = 40`, `WA_FIX2_NORMALIZE_BUDGET_MS = 9000 ms`. On device the budget was hit at attempt 12 (`elapsedMs=10286` end-to-end incl. launch/package/tab).

## 7. ACC-WA-SEARCH results

```
ACC-WA-SEARCH-2: FAIL
  classification: SCROLL_TO_POSITION_UNSUPPORTED  (primary — the reliable action is absent on this WhatsApp build)
                  → fallback outcome: HEADER_NOT_RECOVERED via reason=scroll_timeout
  starting state: WhatsApp Chats tab active, list scrolled ~60+ rows down (10× swipe-up in prep),
                  uiautomator dump pre-test: search-ish nodes = 0, first child of android:id/list =
                  contact_row_container, first row "+40 758 394 489"
  trigger: BENSON Debug Panel (benson://debug) → "suna pe Baby pe WhatsApp" → confirm "da" →
           confirmActiveMission → runWhatsAppCallNative("Baby")  (real production executor)
  step reached: waScrollChatsToTop() adaptive loop, 12 ACTION_SCROLL_BACKWARD attempts,
                each advancing ~1 row, budget (9 s) exceeded → scroll_timeout
  never reached: WA_SEARCH_HEADER_RECOVERED, WA_SEARCH_CLICK, WA_SEARCH_INPUT_READY,
                 WA_NATIVE_SET_TEXT, WA_NATIVE_CONTACT_MATCH, WA_NATIVE_CALL_VERIFY
  post-failure a11y state: WhatsApp still foreground (HomeActivity); android:id/list first visible
                 chat "HAMIMO"; no my_search_bar / search_bar_inner_layout; search-ish = 0
  final result: clean bounded failure, honest error returned, NO call placed

ACC-WA-SEARCH-1: NOT_RUN
ACC-WA-SEARCH-3: NOT_RUN
```

## Real call reached VERIFIED `CALL_STARTED`?

**NO.** The recipe failed during scroll-to-top normalisation, before search / set-text / contact / call button. No WhatsApp call was initiated.

## 8. Remaining limitation

WhatsApp `2.26.34.81`'s `android:id/list` exposes **no** semantic "jump to top" (`ACTION_SCROLL_TO_POSITION`, `ACTION_SCROLL_UP`, pageUp all absent) — only `SCROLL_FORWARD` / `SCROLL_BACKWARD`, and `SCROLL_BACKWARD` moves **one row per call**. With the current one-action-then-settle loop, restoring the header from a deep scroll can't be done inside a call-appropriate time budget.

**Not implemented this round (FAIL → STOP → no auto-patch). Candidate for a follow-up, still coordinate-free:**
fire `ACTION_SCROLL_BACKWARD` in a **tight burst** — e.g. N (≈8–10) consecutive `performAction` calls ~40–60 ms apart with **no tree read between them** — then read the tree once to check the header; repeat the burst up to a larger cap with a wall-clock budget and a "first-row signature unchanged across a whole burst" terminal-top / stall check. The per-row action is unavoidable, but removing the ~400 ms settle + 3 tree reads from every single row raises throughput roughly 8–10× (≈ 80–100 rows within ~9 s). If even a burst loop cannot reliably reach item 0 at acceptable latency, the honest conclusion is that this WhatsApp build cannot be normalised purely semantically and the round's "report rather than add coordinate swipes" clause applies.

## CONFIRM

- source modified outside `BensonAccessibilityService.kt`: **NO**
- git used: **NO**
- prebuild used: **NO**
- coordinate swipes / dispatchGesture added: **NO** (prep-only `adb shell input swipe` is test scaffolding, not app code)
- real WhatsApp call placed: **NO**
- reached VERIFIED `CALL_STARTED` from the scrolled-down state: **NO**
