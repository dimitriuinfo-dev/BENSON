# ROUND_WA_DIAG_REPORT

Read-only diagnosis of `SEARCH_NOT_FOUND` on the WhatsApp voice-call path.
Device `9c1464eb` / CPH2663 / OnePlus Nord 4 / OxygenOS 15. No source changed (see last section).

---

## Result

```
CAUSE_A_DIFFERENT_PATH
```

The active call recipe does **not** run the Accessibility path proven in ACC-1. It uses a
**narrower, more shallowly-capped, first-match** node search than the ACC-1 harness, with **no
step that scrolls the chat list to the top or guarantees the Chats tab**, and no full-tree
collect. The ACC-1 premise in the task ("`getWindows()` + `node.refresh()` + `search_bar_inner_layout`")
is only partly right: **ACC-1 uses neither `getWindows()` nor `node.refresh()`** for the WhatsApp
search step — those belong to the *calculator* test. What ACC-1 does that the call recipe does not:
collect the **whole** tree (caps 1200/60) into a list, settle it, then match on a **broader**
selector set including non-clickable, `contains("search_bar")`, and `/search_bar`.

Secondary finding from the captured hierarchy (§ "Evidence from UI hierarchy"): in the exact
failing state the search node is **not among the ~92 interactive nodes that were traversed**, and
the tree simultaneously contains Chats + Status/Updates + Communities content (multiple retained
ViewPager pages). So a pure selector-string widening may not be sufficient — the node was either
scrolled out of the collapsing header or sits beyond the call path's `MAX_NODES=400` cut-off.
Confirming which needs the one `uiautomator dump` that this round did **not** spend (WhatsApp was
not foregrounded — device was on `com.opera.browser`; launching it is outside the allowed scope).

`INCONCLUSIVE` on exactly one point: whether `com.whatsapp:id/search_bar_inner_layout` exists
*anywhere* in the full, uncapped tree right now. The filtered native dump cannot prove that
negative.

---

## Evidence from code

### Active call path (what emitted `SEARCH_NOT_FOUND`)

| Hop | File : line | Note |
|---|---|---|
| mission tool | `src/core/mission/tools/whatsappTool.ts:941` | `const r = await runWhatsAppCallNative(name)` — `USE_NATIVE_CALL_RECIPE = true` branch |
| bridge | `modules/benson-accessibility/index.js:93` → `BensonAccessibilityModule.kt:159` | `AsyncFunction("runWhatsAppCallNative")` on `runOnServiceScope` |
| executor | `BensonAccessibilityService.kt:1396` | `suspend fun runWhatsAppCallNative(...)` — `withContext(Dispatchers.Default)` |
| package gate | `BensonAccessibilityService.kt:1427` → `foregroundIsPackage()` `:1329-1340` | uses `windows` (`getWindows()`) at `:1332` — **log shows `windows=0`**, fell through to `root_active` |
| reach list | `BensonAccessibilityService.kt:1436` → `reachWhatsAppChatList()` `:1359-1373` | 4 tries; returns on a search-ish node **or** `com.whatsapp:id/entry`. **No scroll-to-top. No bottom-nav / tab handling.** No log lines. |
| **SEARCH find** | `BensonAccessibilityService.kt:1439-1446` | tier 1 `waitForNode(4000,150){ vid.endsWith("/search_bar_inner_layout") \|\| vid.endsWith("/menuitem_search") }` → tier 2 `waitForNode(2000,150){ n.isClickable && (matchesAny(n, SEARCH_KEYWORDS) \|\| desc.contains("such")) }` → `null` ⇒ `dumpScreenForDebug("wa_native_search")` + `fail("SEARCH_NOT_FOUND", ...)` |
| node scan primitive | `waitForNode` `:848-868` → `findNodeMatching` `:816-823` → `findNodeRecursive` `:825-841` | **first predicate match, DFS**, aborts at `MAX_NODES = 400` / `MAX_DEPTH = 40` (`:105-106`). **No `node.refresh()`.** |
| `SEARCH_KEYWORDS` | `BensonAccessibilityService.kt:115` | `["search","căutare","cautare","caută","cauta","suche","suchen"]` |
| `matchesAny` | `:722-726` | matches on `text + contentDescription` lowercased; needs a keyword substring |

### ACC-1 known-good path (WhatsApp Test 2)

| Step | File : line | Note |
|---|---|---|
| entry | `AccessibilityFoundationTest.kt:210` `runWhatsAppTest()` | triggered only by `adb shell am broadcast -a com.benson.acc1.RUN`; not on any feature path |
| window wait | `:229` → `awaitActiveWindow()` `:313-327` | uses `service.rootInActiveWindow` + `BensonAccessibilityService.lastForegroundPackage`. **Does NOT call `getWindows()`.** `root.window?.type` at `:330` is for a log label only. |
| settle | `:237-245` | re-`collect()` the whole tree up to **10×300 ms** until `all.size >= 20` |
| tree snapshot | `collect()` `:341-361` | full tree into `List<NodeHit>`, caps `MAX_NODES = 1200` / `MAX_DEPTH = 60` (`:35-36`) |
| **SEARCH find** | `:250-257` | `all.firstOrNull { vid.endsWith("/search_bar_inner_layout") \|\| vid.endsWith("/search_bar") \|\| vid.endsWith("/menuitem_search") \|\| vid.contains("search_bar") }` → `all.firstOrNull { desc.contains("suchen") \|\| desc.contains("such") \|\| desc.contains("search") }` — **second tier does NOT require `isClickable`** |
| click | `:266-267` | `if (node.isClickable) node else clickableAncestor(node)` then `ACTION_CLICK` |
| verify | `:276-290` | re-`collect()` fresh each poll (12×300 ms); looks for `search_input` / `search_src_text`, or EditText count up, or a back-affordance desc. **No `node.refresh()`.** |
| `node.refresh()` in ACC-1 | `:439` only | inside `readDisplayFresh()` — the **calculator** display fix, never used by the WhatsApp test |

### Point-by-point (task's Cause-A checklist)

1. **current call path** — `whatsappTool.placeCall` → `runWhatsAppCallNative` (`whatsappTool.ts:941`) → `BensonAccessibilityService.runWhatsAppCallNative` (`:1396`); search at `:1439-1446`.
2. **ACC-1 working path** — `AccessibilityFoundationTest.runWhatsAppTest` (`:210-296`); search at `:250-257`.
3. **exact divergence** —

   | Aspect | ACC-1 (`AccessibilityFoundationTest.kt`) | Call recipe (`BensonAccessibilityService.kt`) |
   |---|---|---|
   | tree caps | `MAX_NODES=1200`, `MAX_DEPTH=60` (`:35-36`) | `MAX_NODES=400`, `MAX_DEPTH=40` (`:105-106`) |
   | scan strategy | full `collect()` → list → `firstOrNull` (`:341-361`, `:250`) | `findNodeRecursive` first-match DFS, abort at cap (`:825-841`) |
   | settle before search | ≤10×300 ms until ≥20 nodes (`:237-245`) | `reachWhatsAppChatList()` 4 tries, no min-node gate, no scroll, no tab switch (`:1359-1373`) |
   | search viewIds | `/search_bar_inner_layout`, `/search_bar`, `/menuitem_search`, `contains("search_bar")` (`:252-253`) | `/search_bar_inner_layout`, `/menuitem_search` only (`:1441`) |
   | search desc tier | any node, `desc contains suchen\|such\|search` (`:255-257`) | **`isClickable` required** + `matchesAny(SEARCH_KEYWORDS)` or `desc contains "such"` (`:1442-1444`) |
   | `getWindows()` | not used (`:313-327`) | used for package gate only (`:1332`); `windows=0` in log; not used for the node search |
   | `node.refresh()` | not used for WhatsApp (`:439` = calculator only) | not used |
   | re-read each poll | re-`collect()` full fresh tree (`:277-278`) | `waitForNode` re-`findNodeMatching` fresh root (`:857-864`) |
   | thread | `serviceScope` coroutine | `withContext(Dispatchers.Default)` (`:1397`) |

4. **file + line numbers** — as tabled above.
5. **interactive windows available on both paths** — **No, on neither.** The service config carries no `flagRetrieveInteractiveWindows` (`res/xml/accessibility_service_config.xml`; ACC-1 report §3 note: "`getWindow()` returns null without `flagRetrieveInteractiveWindows`"). Today's call-path log: `WA_NATIVE_PACKAGE found=true source=root_active windows=0` — `getWindows()` returned empty. ACC-1 does not call `getWindows()` at all. The task's premise that the ACC-1 path relies on interactive windows is **not correct**.
6. **`node.refresh()` on both paths** — **No, on neither** (for the search step). In ACC-1 `refresh()` exists only in `readDisplayFresh()` (`:439`), the calculator display fix; the WhatsApp Test 2 does a fresh `collect()` instead. The call recipe's search does not call `refresh()` either. The task's premise that ACC-1's WhatsApp search uses `refresh()` is **not correct**.
7. **exact selector per path** —
   * ACC-1: viewId `endsWith("/search_bar_inner_layout") || endsWith("/search_bar") || endsWith("/menuitem_search") || contains("search_bar")`; then `contentDescription` contains `suchen` / `such` / `search` on **any** node.
   * Call recipe: viewId `endsWith("/search_bar_inner_layout") || endsWith("/menuitem_search")`; then `isClickable && ( text+desc contains one of ["search","căutare","cautare","caută","cauta","suche","suchen"] || desc contains "such" )`.

---

## Evidence from log

Device buffer, tag `BENSON_AUDIO` + `BensonA11y`, **today 09-09 08:49** — the failing run, contact "Baby":

```
09-09 08:48:52.929 ReactNativeJS  WA_CONTACT_INPUT stage=parser transcript="Benson, sună la Baby pe WhatsApp." parsed_contactName="Baby" mode="voice_call"
09-09 08:49:05.288 BENSON_AUDIO   WA_CONTACT_INPUT stage=placeCall received="Baby" native="Baby"
09-09 08:49:05.289 BENSON_AUDIO   WA_CONTACT_INPUT stage=native_bridge native="Baby"
09-09 08:49:05.291 BENSON_AUDIO   WA_NATIVE_START contact="Baby"
09-09 08:49:05.291 BENSON_AUDIO   WA_CALL_STATE state=CALL_STARTING contact="Baby"
09-09 08:49:05.307 BENSON_AUDIO   WA_NATIVE_LAUNCH ok=true
09-09 08:49:05.609 BENSON_AUDIO   WA_NATIVE_PACKAGE found=true source=root_active windows=0
   (~2.3 s gap = reachWhatsAppChatList() 4 tries, no log lines of its own)
09-09 08:49:12.024 BENSON_AUDIO   WAIT_NODE predicate=wa_native_search      foundAfterMs=4133 result=timeout
09-09 08:49:14.024 BENSON_AUDIO   WAIT_NODE predicate=wa_native_search_sem  foundAfterMs=2000 result=timeout
09-09 08:49:14.030 BensonA11y     [wa_native_search] dump: package=com.whatsapp, dumping clickable/labeled nodes:  (full dump below)
09-09 08:49:14.041 BENSON_AUDIO   WA_NATIVE_FAIL stage=SEARCH_NOT_FOUND reason=no search_bar_inner_layout / menuitem_search / semantic search node
09-09 08:49:14.043 BENSON_AUDIO   WA_NATIVE_RESULT success=false step=SEARCH_NOT_FOUND elapsedMs=8750 contact="Baby"
09-09 08:49:14.070 ReactNativeJS  [MissionOrchestrator] resumed mission=... status=COMPLETED message="Nu am reușit să duc apelul la capăt în WhatsApp (pas: SEARCH_NOT_FOUND)."
```

* **native vs JS** — **native.** `runWhatsAppCallNative` on the service coroutine; `WA_NATIVE_*` tags; the JS `runCallRecipe` / `runTwoPhase` step executors are gated out (`USE_NATIVE_CALL_RECIPE = true`, `whatsappTool.ts`).
* **exact recipe name** — `runWhatsAppCallNative` (aka "WA-NATIVE-FINAL", `BensonAccessibilityService.kt:1396`).
* **exact selector attempted** — tier 1 `wa_native_search`: `vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/menuitem_search")` (timeout 4133 ms). tier 2 `wa_native_search_sem`: `isClickable && (SEARCH_KEYWORDS match || desc contains "such")` (timeout 2000 ms).
* **exact reason `SEARCH_NOT_FOUND` was emitted** — `BensonAccessibilityService.kt:1446`: both `waitForNode` calls returned `null` (nothing in the traversed tree satisfied either predicate within 4 s + 2 s), so `searchNode == null` → `fail("SEARCH_NOT_FOUND", "no search_bar_inner_layout / menuitem_search / semantic search node")`.

### Cross-reference: same path/selector *worked* on 2026-09-08

* `ROUND_ACC1_REPORT.md` §3, ACC-1 Test 2 (build 2026-09-08 13:33), same device, **same WhatsApp `2.26.34.81`**:
  ```
  WA_ACC_TARGET found=true viewId=com.whatsapp:id/search_bar_inner_layout
                desc="Meta AI fragen oder suchen" class=androidx.appcompat.widget.LinearLayoutCompat
                clickable=true bounds=[36,301][1044,445]
  WA_ACC_VERIFY  success=true why=search_input_present
  ```
* `WA_NATIVE_FINAL_REPORT.md` §1 (2026-09-08): `runWhatsAppCallNative` reached `CALL_VERIFIED` on RUNs 1,2,6,7,8,9,10,12, "Search — viewId `com.whatsapp:id/search_bar_inner_layout` (tier 1) … proven on device", selectors "unchanged".

So on 2026-09-08 both paths found `search_bar_inner_layout`; on 2026-09-09 the call path does not — **with no WhatsApp update and no selector-code change in between** (see version evidence below).

---

## Evidence from UI hierarchy

**Source: `dumpScreenForDebug("wa_native_search")` fired natively at 09-09 08:49:14.030-.040**, `package=com.whatsapp`, from the exact `SEARCH_NOT_FOUND` state. (No `adb shell uiautomator dump` was run — see last section. This native dump is filtered to nodes that are clickable, editable, or have a non-blank label, and is subject to the same `MAX_NODES=400` / `MAX_DEPTH=40` caps as the failing search itself.)

### Top app bar / search area — every relevant id present

| viewId | class | text / content-desc | clickable | bounds top |
|---|---|---|---|---|
| `com.whatsapp:id/menuitem_camera` | `android.widget.ImageButton` | desc `"kamera"` | true | 133 |
| `com.whatsapp:id/menuitem_overflow` | `android.widget.ImageView` | desc `"weitere optionen"` | true | 133 |
| *(none)* | `android.widget.LinearLayout` | `"whatsapp"` (title) | false | 121 |
| `android:id/list` | `androidx.recyclerview.widget.RecyclerView` | desc `"wische nach unten, um weitere aktionen einzublenden."` | false | 121 |

* **`com.whatsapp:id/search_bar_inner_layout` — does NOT appear.**
* **`com.whatsapp:id/search_bar` / `menuitem_search` / `search_input` / `search_src_text` — do NOT appear.**
* **No node anywhere in the dump has `text` or `content-desc` containing `search` / `such` / `suchen` / a magnifier.** (`grep` over the full dump: zero hits.)
* No `"Meta AI fragen oder suchen"` node (the 2026-09-08 search-bar label) anywhere.
* The chat-list `RecyclerView` sits at `top=121` — directly under the status bar, **with no header/search bar above it** — and carries the a11y hint "swipe down to reveal more actions". On 2026-09-08 the search bar occupied `top=301-445`, i.e. content began lower.

### The tree also contains non-Chats content (multiple retained pages)

Logged in the same dump, overlapping coordinate ranges (two+ coordinate origins ⇒ retained ViewPager2 pages):

* Chat rows (`contact_row_container` / `conversations_row_contact_name`): `benson`, `baby`, `whatsapp`, `xii-b`, `fit`, `doc2op eu`, `doct2op`, `la plajă`, `hannah`, `auf die guten alten zeiten`
* Status/Updates: `header_textview "status"`, `"mein status…"`, `"neue meldungen"`, `contact_name` = `alina bucur`, `horst kauntz body`, `mario atu`, `karla suditu nou`, `michael löhnert` ("… ungelesene statusmeldungen")
* `header_textview "kanäle"` (Channels)
* Communities empty-state: `empty_community_row_title "bleib mit einer community in kontakt"`, `empty_community_row_button "community erstellen"`, `empty_community_row_see_example_communities_text`
* FABs: `com.whatsapp:id/fab "neuer chat"`, `com.whatsapp:id/extended_mini_fab "sende eine nachricht an deinen assistenten."`

`"neuer chat"` FAB ⇒ the Chats tab is (most likely) the active tab. So this is **not** "wrong tab shows no search bar" — it is the Chats tab, on the same app version, **without the collapsing search header materialised**, and with a large multi-page tree that the call path's 400-node cap may not fully cover.

### WhatsApp / BENSON versions (read-only `dumpsys package`)

```
com.whatsapp     versionName=2.26.34.81   versionCode=263408100   lastUpdateTime=2026-09-07 09:11:50
com.benson.butler versionName=1.0.0        lastUpdateTime=2026-09-08 20:27:04
```

* WhatsApp `2.26.34.81` is **identical** to the version in `ROUND_ACC1_REPORT.md` / `WA_NATIVE_FINAL_REPORT.md` (both "WhatsApp 2.26.34.81, UI German"). `lastUpdateTime = 2026-09-07` predates the last known-good run (2026-09-08). **No WhatsApp update happened.** ⇒ this is **not** a viewId rename by an app upgrade; "CAUSE_B as literally defined (selector no longer present because WhatsApp updated)" is **ruled out**.

### What the one `uiautomator dump` would still add

Whether `com.whatsapp:id/search_bar_inner_layout` exists **anywhere** in the *uncapped, unfiltered* tree in this state. The native dump is capped at 400 nodes and filtered; it can show the node is not among the traversed interactive nodes, but not that it is truly absent from the full tree. `uiautomator dump` (system-level, no cap) settles that. It was not taken because WhatsApp was not foregrounded at diagnosis time (`mCurrentFocus=com.opera.browser`) and launching an app is outside this round's allowed scope.

---

## Exact failing path

`voice → commandParser → mission PREPARE_MESSAGE{mode:voice_call} → missionOrchestrator.resumePendingTask → missionExecutor → whatsappTool.placeCall(name)` **→ `whatsappTool.ts:941` `runWhatsAppCallNative(name)`** → bridge `BensonAccessibilityModule.kt:159` → **`BensonAccessibilityService.kt:1396` `runWhatsAppCallNative`** → `launchWhatsApp()` `:1418` → package gate `foregroundIsPackage` `:1427` (`windows=0`, `source=root_active`) → `reachWhatsAppChatList()` `:1436` (no-op: no search node, not in a chat) → **SEARCH `:1439-1446`**: `waitForNode(/search_bar_inner_layout|/menuitem_search)` timeout 4133 ms → `waitForNode(isClickable & search-kw)` timeout 2000 ms → `dumpScreenForDebug` → `fail("SEARCH_NOT_FOUND")` `:1446` → `WA_NATIVE_RESULT success=false step=SEARCH_NOT_FOUND` → JS mission message "Nu am reușit să duc apelul la capăt în WhatsApp (pas: SEARCH_NOT_FOUND)."

---

## ACC-1 known-good path

`adb broadcast com.benson.acc1.RUN → AccessibilityFoundationTest.run() :85 → (Calculator Test 1 PASS) → runWhatsAppTest() :210` → `executeCommand(launch_app com.whatsapp) :212` → `executeCommand(assert_package) :222` (result logged, **not gated on**) → `awaitActiveWindow(com.whatsapp, 8000) :229` (via `rootInActiveWindow` + `lastForegroundPackage`, **no `getWindows()`**) → settle loop `:237-245` (≤10×300 ms until ≥20 nodes) → `collect()` full tree `:341` (caps 1200/60) → **search find `:250-257`** (`/search_bar_inner_layout | /search_bar | /menuitem_search | contains("search_bar")`, then desc `such*` on any node) → `clickableAncestor` + `ACTION_CLICK` `:266-267` → verify by fresh `collect()` for `search_input` `:276-290` → `GLOBAL_ACTION_BACK` `:294`. Proven 2026-09-08: `WA_ACC_TARGET found=true viewId=com.whatsapp:id/search_bar_inner_layout … WA_ACC_VERIFY success=true`.

---

## Divergence

1. **Traversal breadth/caps.** Call path aborts node scan at `MAX_NODES=400` / `MAX_DEPTH=40` (`BensonAccessibilityService.kt:105-106`), first-match DFS. ACC-1 collects the **whole** tree at `1200` / `60` (`AccessibilityFoundationTest.kt:35-36`) then scans the list. Today's failing tree holds Chats **plus** Status **plus** Communities pages — large enough that the 400-node cut-off is a plausible reason the call path never reaches a node ACC-1 would.
2. **Selector set narrower.** Call path: `/search_bar_inner_layout`, `/menuitem_search`. ACC-1 also accepts `/search_bar` and any `contains("search_bar")` (`:252-253`).
3. **Semantic tier gated on `isClickable`.** Call path tier 2 requires `n.isClickable` (`:1442`). ACC-1's desc tier matches non-clickable nodes too (`:255-257`) and lets `clickableAncestor` handle the tap.
4. **No state normalisation before search.** `reachWhatsAppChatList()` (`:1359-1373`) only escapes an open individual chat (BACK / `whatsapp_toolbar_home`). It never scrolls the `RecyclerView` to the top (which would re-materialise the collapsing "Meta AI fragen oder suchen" header) and has no concept of the bottom-nav tabs. ACC-1 doesn't scroll either, but its ≥20-node settle + full collect + retry-on-verify loop tolerate a mid-transition snapshot better.
5. **Task premise correction.** ACC-1's WhatsApp search uses **neither `getWindows()` nor `node.refresh()`**. `getWindows()` on the call path is used only for the package gate and returned empty (`windows=0`). `node.refresh()` in ACC-1 is the *calculator* display fix (`:439`), not part of any WhatsApp step.

**Not a divergence / ruled out:** WhatsApp version (identical `2.26.34.81`, no update since the last good run); the call recipe's own selector strings (unchanged vs the 2026-09-08 proven build per `WA_NATIVE_FINAL_REPORT.md`).

---

## No changes performed

- **source modified: NO** — net zero. Earlier in this same turn (before the round was redefined as read-only) two edits were made to `modules/benson-accessibility/.../BensonAccessibilityService.kt` and then **fully reverted**; the file was re-read and is byte-identical to its pre-turn state (`grep` for every inserted identifier returns nothing). No other source file was touched.
- **git used: NO**
- **prebuild used: NO**
- **uiautomator dumps: 0** — `adb` was used only for read-only inspection: `adb devices`, `adb shell dumpsys window` (focus), `adb logcat -d` (existing buffer), `adb shell dumpsys package` (versions). WhatsApp was not foregrounded (device on `com.opera.browser`), so the one permitted `adb shell uiautomator dump` was **not** spent — it remains available for the follow-up (open WhatsApp on the Chats screen, then one dump).

---

## Recommended next step (not performed)

Open WhatsApp to the Chats screen in the failing state and take the one `adb shell uiautomator dump`.
Two outcomes:

* `search_bar_inner_layout` **present** in the full tree ⇒ pure **CAUSE_A**: fix by raising the call
  path's node/depth caps and/or switching its search find to the ACC-1 full-collect strategy, and
  adding a "scroll chat list to top" step in `reachWhatsAppChatList()`.
* `search_bar_inner_layout` **absent** even uncapped ⇒ the collapsing header is not materialised in
  this state; fix is a state step (scroll-to-top / pull-down) before the search find, then the
  existing selector works — plus report the actual node that *does* open search in that state.

---
---

# ROUND_WA_DIAG — CLOSE (the one reserved `uiautomator dump`)

`adb shell uiautomator dump` — run **once**, 2026-09-09, WhatsApp foreground
(`mCurrentFocus = com.whatsapp/com.whatsapp.home.ui.HomeActivity`), user confirmed on the Chats
screen. Pulled `/sdcard/window_dump.xml` (64 278 bytes) and parsed in full. **Total `<node>`: 169.
Max tree depth: 22.**

## Answers

### 1. resource-ids present?

| id | present | evidence |
|---|---|---|
| `com.whatsapp:id/search_bar_inner_layout` | **YES** | doc-node **#20**, depth **19**, `class="androidx.appcompat.widget.LinearLayoutCompat"`, `clickable="true"`, `enabled="true"`, `focusable="true"`, `bounds="[36,301][1044,445]"` |
| `com.whatsapp:id/search_bar` | **NO** | closest is `com.whatsapp:id/my_search_bar` (#19, `FrameLayout`, `clickable="false"`, `bounds="[0,301][1080,445]"`) — the wrapper around `search_bar_inner_layout` |
| `com.whatsapp:id/menuitem_search` | **NO** | no node with this id anywhere in the 169 |

Also present: `com.whatsapp:id/search_icon` (#21, `ImageView`, `[48,313][168,433]`), `com.whatsapp:id/search_text` (#22, `TextView`, `[168,336][1044,409]`).

### 2. search text / content-desc?

* `search_bar_inner_layout` → **`content-desc="Meta AI fragen oder suchen"`**
* `search_text` (`TextView`) → **`text="Meta AI fragen oder suchen"`**
* No standalone `Search` / `Suche` / `Suchen` / `Căutare` node.
* `search_icon` carries the magnifier glyph but has empty `text`/`content-desc` (id only).
* The search affordance = the **"Meta AI fragen oder suchen"** pill (`search_bar_inner_layout`). Byte-identical id, class, bounds and desc to the 2026-09-08 known-good run in `ROUND_ACC1_REPORT.md` (`viewId=com.whatsapp:id/search_bar_inner_layout desc="Meta AI fragen oder suchen" … clickable=true bounds=[36,301][1044,445]`).

### 3. `search_bar_inner_layout` detail

| field | value |
|---|---|
| bounds | `[36,301][1044,445]` |
| class | `androidx.appcompat.widget.LinearLayoutCompat` |
| clickable | `true` |
| enabled | `true` |
| focusable / focused | `true` / `false` |
| visible / displayed | attribute not emitted by this uiautomator build (`n/a`); node is laid out on-screen (bounds inside `[0,0][1080,2414]`) with rendered children (`search_icon`, `search_text` with real text) |
| hierarchy position | doc-node **#20 of 169**, depth **19**. Parent chain: `com.whatsapp:id/pager` (ViewPager, #13, scrollable) → `conversation_container` (#16) → **`android:id/list`** (`RecyclerView`, #17, `scrollable="true"`) → `FrameLayout` (#18) → `com.whatsapp:id/my_search_bar` (#19) → **`search_bar_inner_layout` (#20)**. It is the **first / header row of the conversations `RecyclerView`**. |
| beyond native 400-node / depth-40 cap? | **No.** #20 ≪ 400 and depth 19 < 40. When present it is one of the earliest interactive nodes; first-match DFS reaches it almost immediately. |

### 4. header collapsed/scrolled?

`search_bar_inner_layout` **does** exist now, so **the current screen is NOT collapsed** — the search
bar sits at its normal `top=301`, and the first chat row (`contact_row_container` #25, name `BENSON`
#32) is pushed down to `top≈493–529`.

Contrast the **failing-call native dump** (`ROUND_WA_DIAG_REPORT` above, `dumpScreenForDebug` at
09-09 08:49:14): the first `contact_row_container` / `conversations_row_contact_name "benson"` was
flush at **`top=121`** — directly under the status bar, **with no search bar above it** — and
`android:id/list` carried the "swipe down to reveal more actions" a11y hint. i.e. at call time the
conversations `RecyclerView` had been **scrolled down past its header row**, so the header
(`my_search_bar` / `search_bar_inner_layout`) was recycled out of the view hierarchy and therefore
absent from the accessibility tree. `android:id/list` is `scrollable="true"` (confirmed in this
dump), so a scrolled position is the normal, expected state after any prior use.

### 5. A / B / C / D

**B. SEARCH_HEADER_COLLAPSED.**

* **Not A** (`SEARCH_EXISTS_BEYOND_NATIVE_CAP`): when present, the node is doc-#20 / depth 19 —
  nowhere near the 400 / 40 caps. In the 08:49 native dump the deep, late-tree toolbar nodes
  (`menuitem_camera`, `menuitem_overflow`, `fab`) were still logged, so that traversal did not
  exhaust its budget before finishing — a present header would have been found.
* **B** confirmed: `search_bar_inner_layout` is a **scroll-away `RecyclerView` header row**; at the
  failing call the list was scrolled down and the header was not laid out / not in the tree, so every
  id-based and semantic selector for it returned nothing.
* Not C, not D.

### 6. Correlation with the confirmed call path

`runWhatsAppCallNative` SEARCH step (`BensonAccessibilityService.kt:1439-1446`): tier 1
`waitForNode(4000){ vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/menuitem_search") }`,
tier 2 `waitForNode(2000){ n.isClickable && (matchesAny(n, SEARCH_KEYWORDS) || desc.contains("such")) }`,
resolved by first-match DFS capped at `MAX_NODES=400` / `MAX_DEPTH=40`.

In the scrolled state none of `search_bar_inner_layout`, `menuitem_search`, or any clickable node
with search text/desc is in the tree → **both `waitForNode` calls run to timeout** (exactly the
09-09 08:49 log: `WAIT_NODE predicate=wa_native_search … result=timeout` then
`WAIT_NODE predicate=wa_native_search_sem … result=timeout`) → `searchNode == null` →
`fail("SEARCH_NOT_FOUND")`.

The **400 / depth-40 cap** and the **`isClickable`-gated desc tier** are **not** what broke this run
(the target node was *absent*, not merely *unreached*), and `menuitem_search` is a dead selector on
this build (never present). They remain latent risks: (a) the caps, for the retained multi-page tree
seen in the 08:49 dump (Chats + Status + Communities + Channels simultaneously); (b) the narrow
selector set, since `my_search_bar` and the `Meta AI fragen oder suchen` desc are not in the tier
list. When the header *is* present, tier 1 matches `search_bar_inner_layout` by id (it is
`clickable="true"`) and the recipe works — as it did on 2026-09-08.

### 7. What actually fixes it

| candidate | verdict |
|---|---|
| **Unify with the ACC-1 search implementation** (full `collect()`, broader viewId set, non-clickable desc tier) | **Insufficient alone.** ACC-1 also only *matches nodes that exist*; a header scrolled out of the `RecyclerView` is in no snapshot. ACC-1 would report `WA_ACC_TARGET found=false` in the identical state. |
| **Normalize the Chats screen before the search find** (scroll `android:id/list` to position 0 — `ACTION_SCROLL_BACKWARD` / scroll-to-top — until `search_bar_inner_layout` materialises; optionally first assert the `Chats` bottom-nav item is `selected="true"`) | **Primary fix, likely sufficient.** Re-materialises the header row; the existing `search_bar_inner_layout` selector (correct, current, unchanged since the proven run) then matches. |
| **Increase / align tree collection** (400→1200, 40→60, or full-collect) | **Hardening only.** Not implicated in this failure; worth doing for retained multi-page states. |
| **Combination** | scroll-to-top normalization **+** cap/breadth alignment as hardening. |

---

## VERDICT:

**CAUSE_A_PLUS_COLLAPSED_HEADER**

The active recipe (`runWhatsAppCallNative`) does diverge from the ACC-1 known-good approach —
narrower selector set (`search_bar_inner_layout` + dead `menuitem_search` only), first-match DFS
under a `400` / `40` cap instead of ACC-1's `1200` / `60` full `collect()`, `isClickable`-gated
semantic tier, and no full-tree settle — **and, decisively, it performs no state normalisation
before searching.** The proximate cause of this specific `SEARCH_NOT_FOUND` is a **collapsed /
scrolled chat list**: `com.whatsapp:id/search_bar_inner_layout` is the header row of the
conversations `RecyclerView`, and at call time the list was scrolled down so that row was recycled
out of the accessibility tree entirely. The same scrolled state would also defeat the ACC-1
selectors, so "unify with ACC-1" is not on its own a fix.

## EVIDENCE:

* **code path** — `whatsappTool.ts:941` `runWhatsAppCallNative` → `BensonAccessibilityService.kt:1396`; SEARCH at `:1439-1446` (tier 1 `search_bar_inner_layout | menuitem_search`; tier 2 `isClickable && SEARCH_KEYWORDS/"such"`); scan via `findNodeRecursive` first-match DFS, `MAX_NODES=400` / `MAX_DEPTH=40` (`:105-106`); `reachWhatsAppChatList()` (`:1359-1373`) escapes an open chat only — **no scroll-to-top, no tab check**. ACC-1 counterpart: `AccessibilityFoundationTest.kt:210-296`, search `:250-257`, caps `1200/60` (`:35-36`), full `collect()` + ≥20-node settle; uses neither `getWindows()` nor `node.refresh()` for search.
* **log evidence** — 09-09 08:49 (contact "Baby"): `WA_NATIVE_PACKAGE found=true source=root_active windows=0` → `WAIT_NODE predicate=wa_native_search … result=timeout` (4133 ms) → `WAIT_NODE predicate=wa_native_search_sem … result=timeout` (2000 ms) → `WA_NATIVE_FAIL stage=SEARCH_NOT_FOUND reason=no search_bar_inner_layout / menuitem_search / semantic search node`. Same-run native `dumpScreenForDebug`: package `com.whatsapp`, first chat row flush at `top=121`, **no search-bar node, no search text/desc anywhere**, list hint "swipe down to reveal more actions", tree also holding Status + Communities + Channels rows. WhatsApp `2.26.34.81` (`lastUpdateTime 2026-09-07`) — identical to the 2026-09-08 known-good run; no update, no selector-code change.
* **full UI hierarchy evidence** — the one `uiautomator dump` (WhatsApp Chats foreground, 169 nodes): `com.whatsapp:id/search_bar_inner_layout` **present**, doc-#20, depth 19, `clickable=true enabled=true`, `content-desc="Meta AI fragen oder suchen"`, `bounds=[36,301][1044,445]`, class `LinearLayoutCompat`; it is the header child of `android:id/list` (`RecyclerView`, `scrollable="true"`) via `my_search_bar` (#19). `com.whatsapp:id/search_bar` and `com.whatsapp:id/menuitem_search` **absent**. Bottom nav `Chats` tab `selected="true"`. Node is far inside the 400 / 40 caps ⇒ **not** "beyond cap"; its absence at call time ⇒ **scrolled-out header**.

## RECOMMENDED FIX SHAPE:

Before the SEARCH step in `runWhatsAppCallNative`, normalise the chat-list state: confirm the `Chats` bottom-nav item is `selected`, then scroll `android:id/list` to the top (repeated `ACTION_SCROLL_BACKWARD`, or scroll-to-position-0, bounded, until `com.whatsapp:id/search_bar_inner_layout` appears or scrolling stops), and only then run the existing selector — which is correct and unchanged (`search_bar_inner_layout`, "Meta AI fragen oder suchen"). Fold this into `reachWhatsAppChatList()` so both the native recipe and any future caller benefit. As non-blocking hardening for retained multi-page trees, raise this path's traversal caps toward ACC-1's (`400→1200`, `40→60`) and widen the tier list to include `my_search_bar` and a non-clickable `content-desc="…suchen"` match. No selector removal, no coordinate fallback, no change to contact-match / call-button / verify logic.

## CONFIRM:

* source modified: **NO**
* git used: **NO**
* prebuild used: **NO**
* uiautomator dumps this round: **exactly 1**
