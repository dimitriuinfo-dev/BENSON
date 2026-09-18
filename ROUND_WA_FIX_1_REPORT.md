# ROUND_WA_FIX_1_REPORT

WA-FIX-1 — normalise WhatsApp Chats state (scroll search header back into the accessibility
tree) before the search step of the native call recipe.
Diagnosis basis: `ROUND_WA_DIAG_REPORT.md` — verdict `CAUSE_A_PLUS_COLLAPSED_HEADER`.

Device `9c1464eb` / CPH2663 / OnePlus Nord 4 / OxygenOS 15. WhatsApp `2.26.34.81`.

---

## Status

| Item | Result |
|---|---|
| Implementation | DONE (1 file) |
| `npx tsc --noEmit` | PASS (exit 0) |
| `gradlew assembleRelease` | PASS — `BUILD SUCCESSFUL in 48s`, APK 260,938,968 B, signer `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` |
| Install on `9c1464eb` | PASS — `adb install -r` `Success`, app data preserved, accessibility service still enabled, foreground service `isForeground=true` |
| **ACC-WA-SEARCH-2** (scrolled-down Chats) | **FAIL — `HEADER_NOT_RECOVERED`** |
| ACC-WA-SEARCH-1 (Chats at top) | NOT_RUN (gated on -2 passing) |
| ACC-WA-SEARCH-3 (non-Chats tab) | NOT_RUN (gated on -2 passing) |
| ACC-WA-SEARCH-4 (failure sim) | NOT_RUN (gated on -2 passing) |
| **Real WhatsApp call reached VERIFIED `CALL_STARTED` from the scrolled-down state?** | **NO** — the recipe failed at the scroll-to-top normalisation step, before search / set-text / contact / call button. **No call was placed** (contact "Baby" was not rung). |

Per the round's FAIL protocol: **STOPPED after -2. No source changed during the test round. No automatic patch of the failure.**

---

## 1. Files modified

| File | Change |
|---|---|
| `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt` | +1 flag block, +8 helpers, rewired the SEARCH section of `runWhatsAppCallNative`, +1 debug broadcast action |

No other file changed. `tsc` run only as a sanity check (no TS touched).

## 2. Exact functions changed / created

Created (all `private`, in `BensonAccessibilityService`, inserted after `reachWhatsAppChatList()`):

- `waSearchHeaderNode(): AccessibilityNodeInfo?` — matches `…/search_bar_inner_layout`, `…/my_search_bar`, `…/menuitem_search`, `contains("search_bar")`, or a `content-desc` with both "meta ai" and "such".
- `waConversationsList(): AccessibilityNodeInfo?` — `android:id/list` scrollable RecyclerView (fallback: any scrollable RecyclerView whose id ends `/list`).
- `waFirstRowSignature(): String` — first visible `conversations_row_contact_name` text (a "did the list move" probe).
- `waChatsTabLabelNode()`, `waOnChatsTab(): Boolean`, `waSelectChatsTab(): Boolean` — Chats bottom-nav detection / selection.
- `waScrollChatsToTop(): Boolean` — bounded `ACTION_SCROLL_BACKWARD` loop.
- `ensureWhatsAppChatsSearchAvailable(): Boolean` — the orchestrator (foreground → Chats tab → not-in-chat → header present? → find list → scroll to top → verify).
- `openWhatsAppSearch(): AccessibilityNodeInfo?` — find affordance → click (2× bounded) → verify search input open → return it.

Changed:
- `runWhatsAppCallNative` SEARCH section (`BensonAccessibilityService.kt:1645–1686`): `if (WA_FIX1_NORMALIZE_SEARCH) { ensureWhatsAppChatsSearchAvailable() ?: fail("SEARCH_HEADER_NOT_RECOVERED"); openWhatsAppSearch() ?: fail("SEARCH_NOT_FOUND") } else { <verbatim pre-2026-09-09 block> }`. Downstream (SET TEXT / contact match / call button / verify) unchanged.
- `registerAcc1TestReceiver()` — added action `com.benson.wasearch.RUN` → runs `ensureWhatsAppChatsSearchAvailable()` + `openWhatsAppSearch()` only, logs `WA_SEARCH_SELFTEST result …`. Not used in this test (production path only, per instruction).

Revert constant: `WA_FIX1_NORMALIZE_SEARCH = false`. Scroll budget: `WA_FIX1_MAX_SCROLL_ATTEMPTS = 8`.

## 3. How Chats-tab detection works

`waOnChatsTab()`: find a bottom-nav label node (`resource-id` contains `navigation_bar_item` and `label_view`, text == "chats"/"chat"). Active tab's label renders as `*_large_label_view` and/or carries `selected=true` (inactive tabs use `*_small_label_view`). If no recognisable Chats label exists (unknown locale), fall back to structural evidence: `android:id/list` present **and** (a `contact_row_container` present **or** the search header present). `waSelectChatsTab()` clicks the Chats label's clickable ancestor; last-resort target is the first `navigation_bar_item_icon_container` (leftmost = Chats in WhatsApp).

**On device: worked.** `WA_CHATS_TAB state=active` — correctly detected the Chats tab, no tab switch needed.

## 4. How scroll-to-top works — and why it FAILED

`waScrollChatsToTop()`: up to `WA_FIX1_MAX_SCROLL_ATTEMPTS` (8) iterations. Each iteration: if `waSearchHeaderNode()` present → `WA_SEARCH_HEADER_RECOVERED`, return true; else find the list, record `waFirstRowSignature()`, call `list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)`, log `WA_CHAT_SCROLL_TOP attempt=N`, then poll ≤800 ms for the header to appear or the first-row signature to change, log `WA_CHAT_SCROLL_RESULT changed=…`. Terminal early-out only if `!scrolled && !changed`.

`ACTION_SCROLL_TO_POSITION(0)` was deliberately **not** used (documented in code): its accessibility-action support is inconsistent across RecyclerView setups. **This choice is now implicated in the failure — see below.**

**On device: FAILED.** `ACTION_SCROLL_BACKWARD` on WhatsApp's `android:id/list` scrolls only a **small increment per call** (~1 row / partial viewport). 8 calls travelled ~4–5 rows — not back to item 0. `WA_CHAT_SCROLL_RESULT changed=true` fired on every attempt (the first-row signature kept changing as the list crept upward), which **masked** the "still not at the top" condition, so the loop ran its full budget and exited via `WA_SEARCH_NORMALIZE_FAIL reason=header_not_recovered`. Post-failure `uiautomator dump` of `android:id/list`: first child is `com.whatsapp:id/contact_row_container` ("XII-B"), then "FIT" — a chat row, **not** `my_search_bar`; search header still absent.

Failure classification: **`HEADER_NOT_RECOVERED`**. Mechanism/tuning issue in the new helper — **not** a selector, tab-detection, list-detection, or header-detection failure (all of those worked). The scroll action is accepted and moves the list; the per-call step is just far too small for the fixed budget of 8, and there is no single-jump-to-top and no true "reached position 0" check.

## 5. Maximum retry / scroll budget

`WA_FIX1_MAX_SCROLL_ATTEMPTS = 8` scroll iterations; `openWhatsAppSearch()` = 2 bounded click attempts (×3 `clickNodeOrAncestor` each). Total normalise time on the failing run: **4789 ms** (`WA_NATIVE_RESULT … elapsedMs=4789`) — the budget, not the settle, is the limiter.

## 6. Exact search selectors used

Primary unchanged: `com.whatsapp:id/search_bar_inner_layout`. Also accepted (all evidenced in the live hierarchy / ACC-1): `…/my_search_bar`, `…/menuitem_search`, `contains("search_bar")`, `content-desc` "meta ai"+"such", and (semantic tier) `SEARCH_KEYWORDS` / `desc contains "such"|"meta ai"` with the `isClickable` requirement dropped (ACC-1 alignment). **Not reached this run** — the flow failed before `openWhatsAppSearch()`.

## 7. How search-open verification works

`openWhatsAppSearch()` after click: `waitForNode` for `…/search_input` or `…/search_src_text` (3 s) → editable `isSearchUiNode` (1.2 s) → any editable (0.8 s). On hit: `refresh()` + read `isFocused`, log `WA_SEARCH_INPUT_READY state=open viewId=… focused=…`, return the node. Miss → one re-click retry → `WA_SEARCH_NORMALIZE_FAIL reason=search_state_not_open`, return null → recipe `fail("SEARCH_NOT_FOUND")`. Contact name is typed only after this returns non-null. **Not reached this run.**

## 8. Logs added

`WA_SEARCH_NORMALIZE_START`, `WA_CHATS_TAB state=active|inactive|select ok=…`, `WA_SEARCH_HEADER state=visible|missing[ source=scrolled]`, `WA_CHAT_LIST_FOUND state=found|missing vid=…`, `WA_CHAT_SCROLL_TOP attempt=N`, `WA_CHAT_SCROLL_RESULT changed=…`, `WA_SEARCH_HEADER_RECOVERED attempt=N`, `WA_SEARCH_CLICK viewId=… desc=…`, `WA_SEARCH_INPUT_READY state=open|retry …`, `WA_SEARCH_NORMALIZE_FAIL reason=…`, `WA_SEARCH_SELFTEST …`. New fail step: `SEARCH_HEADER_NOT_RECOVERED`. No full-tree dumps on the success path (`dumpScreenForDebug` only on hard-fail, unchanged).

## 9. Code / build tests run

- `npx tsc --noEmit` → exit 0.
- `MSYS_NO_PATHCONV=1 ANDROID_HOME=…\Android\Sdk ./gradlew assembleRelease` from `frontend/android/` → `BUILD SUCCESSFUL in 48s`; module recompiled (`…/build/tmp/kotlin-classes/release/…/BensonAccessibilityService$ensureWhatsAppChatsSearchAvailable$1.class` et al., dated 09:31); APK signed `CN=BENSON, O=TOKKO`.
- No compiler errors. No unit tests exist for this native path.

## 10. Real-device tests run

### ACC-WA-SEARCH-2 — FAIL (`HEADER_NOT_RECOVERED`)

**Starting UI state (verified by `uiautomator dump`, 169 nodes):** WhatsApp `HomeActivity`, bottom-nav "Chats" active, chat list scrolled down (prep: 5× swipe-up); `search_bar_inner_layout` / `search_bar` / `my_search_bar` / `menuitem_search` / "Meta AI" — **0 search-ish nodes**; first row `+40 721 671 339` at `bounds=[216,344…]` flush near the top, no header above it; `android:id/list` `scrollable=true`. Then Home (WhatsApp backgrounded in that state).

**Trigger (real production path):** BENSON Debug Panel (`benson://debug`) → typed `suna pe Baby pe WhatsApp` → Send → mission `COMMUNICATION_PROBLEM` / `PREPARE_MESSAGE` / `mode=voice_call` / `parsed_contactName="Baby"` → `CONFIRM_PENDING type=governed` → typed `da` → Send → `confirmActiveMission` → governed executor → `runWhatsAppCallNative("Baby")`. (Same functions the live voice pipeline uses; not the debug self-test.)

**Relevant logs (`BENSON_AUDIO`, 09-09 09:40:50→55):**
```
WA_NATIVE_START contact="Baby"
WA_NATIVE_LAUNCH ok=true
WA_NATIVE_PACKAGE found=true source=root_active windows=0
WA_SEARCH_NORMALIZE_START
WA_CHATS_TAB state=active                       ← Chats tab detected  ✓
WA_SEARCH_HEADER state=missing                  ← absence detected     ✓
WA_CHAT_LIST_FOUND state=found vid=android:id/list   ← RecyclerView found  ✓
WA_CHAT_SCROLL_TOP attempt=1 … WA_CHAT_SCROLL_RESULT changed=true
WA_CHAT_SCROLL_TOP attempt=2 … changed=true
WA_CHAT_SCROLL_TOP attempt=3 … changed=true
WA_CHAT_SCROLL_TOP attempt=4 … changed=true
WA_CHAT_SCROLL_TOP attempt=5 … changed=true
WA_CHAT_SCROLL_TOP attempt=6 … changed=true
WA_CHAT_SCROLL_TOP attempt=7 … changed=true
WA_CHAT_SCROLL_TOP attempt=8 … changed=true
WA_SEARCH_NORMALIZE_FAIL reason=header_not_recovered      ← last new-code log
WA_NATIVE_FAIL stage=SEARCH_HEADER_NOT_RECOVERED reason=WhatsApp Chats could not be normalised to expose com.whatsapp:id/search_bar_inner_layout
WA_NATIVE_RESULT success=false step=SEARCH_HEADER_NOT_RECOVERED elapsedMs=4789 contact="Baby"
JS: error: 'Nu am reușit să duc apelul la capăt în WhatsApp (pas: SEARCH_HEADER_NOT_RECOVERED).'
```

- **Exact last successful WA_\* log:** `WA_CHAT_SCROLL_RESULT changed=true` (attempt 8) @ 09:40:55.129.
- **Exact failure code:** `SEARCH_HEADER_NOT_RECOVERED` (native step); `WA_SEARCH_NORMALIZE_FAIL reason=header_not_recovered`.
- **Exact step reached:** scroll-to-top normalisation (`waScrollChatsToTop`), all 8 attempts consumed. Never reached: `WA_SEARCH_HEADER_RECOVERED`, `WA_SEARCH_CLICK`, `WA_SEARCH_INPUT_READY`, `WA_NATIVE_SET_TEXT`, contact match, call button, `WA_NATIVE_CALL_VERIFY`.
- **Accessibility state around the failure (`uiautomator dump` right after, WhatsApp still foreground, 181 nodes):** `android:id/list` first child = `com.whatsapp:id/contact_row_container` (`desc="Bild von XII-B"`), next visible name `FIT`; **no `my_search_bar` / `search_bar_inner_layout` anywhere.** The list had crept up only ~4–5 rows over 8 `ACTION_SCROLL_BACKWARD` calls and never exposed item 0.
- **Final verified result:** normalisation failed cleanly. Bounded (stopped at 8). No blind typing, no click, no call placed, honest failure returned. The FAIL-behaviour contract held; the recovery mechanism did not.
- **Classification:** `HEADER_NOT_RECOVERED`.

### ACC-WA-SEARCH-1 / -3 / -4 — NOT_RUN
Gated on ACC-WA-SEARCH-2 passing (per the round instruction). Not started.

## 11. PASS / FAIL

```
ACC-WA-SEARCH-2: FAIL   (HEADER_NOT_RECOVERED)
ACC-WA-SEARCH-1: NOT_RUN
ACC-WA-SEARCH-3: NOT_RUN
ACC-WA-SEARCH-4: NOT_RUN
```

## 12. Coordinate fallback remaining

None. `waScrollChatsToTop()` uses only `AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD` on a verified node; `openWhatsAppSearch()` uses only `clickNodeOrAncestor` (ACTION_CLICK). `android:canPerformGestures="false"` unchanged. The prep-only `adb shell input swipe` used to create the test's starting state is test scaffolding, not app code.

## 13. Regressions found

None observed. The change is behind `WA_FIX1_NORMALIZE_SEARCH` (revert = `false` restores the verbatim pre-2026-09-09 block). Only the WhatsApp-call SEARCH section changed; downstream contact-match / call-button / verify, and wakeword / STT / mic-hold / CALL_ENDED / auto-return / confirmation, untouched. Install preserved app data; accessibility permission and foreground service intact after install. Not device-verified for other flows this round (test stopped at -2 per protocol).

## 14. Source lines — final normalised search path

`modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt`:

- `WA_FIX1_NORMALIZE_SEARCH` / `WA_FIX1_MAX_SCROLL_ATTEMPTS` — flags (helper block header).
- `waSearchHeaderNode()`, `waConversationsList()`, `waFirstRowSignature()`, `waChatsTabLabelNode()`, `waOnChatsTab()`, `waSelectChatsTab()`, `waScrollChatsToTop()`, `ensureWhatsAppChatsSearchAvailable()`, `openWhatsAppSearch()` — helper block, immediately after `reachWhatsAppChatList()`.
- `runWhatsAppCallNative` SEARCH section — `:1645–1686` (`if (WA_FIX1_NORMALIZE_SEARCH) { … } else { … }`), then `// ── 7. SET TEXT` at `:1688` (`setTextOn(searchInput, contact)` at `:1689`, unchanged).
- `com.benson.wasearch.RUN` broadcast action — in `registerAcc1TestReceiver()`.

---

## Root cause of the test failure (for the next round — NOT patched here)

`ACTION_SCROLL_BACKWARD` on WhatsApp's `android:id/list` advances by a small increment per call; a fixed budget of 8 is not enough to travel from an arbitrary scroll depth back to item 0, and `WA_CHAT_SCROLL_RESULT changed=true` (first-row-signature delta) does not distinguish "creeping upward" from "at the top". The tab/list/header detection and the bounded-failure behaviour all worked. Candidate fixes for a follow-up round (not applied): use `AccessibilityAction.ACTION_SCROLL_TO_POSITION` (arg 0) as a single jump with `ACTION_SCROLL_BACKWARD` as fallback; and/or raise the budget and add a real terminal condition (first child is `my_search_bar`/header, or `ACTION_SCROLL_BACKWARD` returns false, or first-row signature stops changing across 2 attempts).

## CONFIRM

- source modified during the test round: **NO**
- git used: **NO**
- prebuild used: **NO**
- real device install: **YES** (`adb install -r`, data preserved)
- real WhatsApp call placed: **NO** (flow failed at normalisation, before the call button)
- reached VERIFIED `CALL_STARTED` from the scrolled-down state: **NO**
