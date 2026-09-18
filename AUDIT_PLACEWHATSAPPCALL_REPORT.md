# AUDIT READ-ONLY — `BensonAccessibilityService.placeWhatsAppCallInner`

Nimic modificat. Fișier nou: doar acesta.

---

## 1. Executive finding

`placeWhatsAppCallInner` (`modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt:964-1145`) este o **mașină de stări complet nativă (Kotlin)** care rulează cap-coadă pe `serviceScope` (`CoroutineScope(Dispatchers.Main + SupervisorJob())`, `:45`), cu polling `while` + `delay()` de coroutine — **zero JS între pași, zero `setTimeout`**. Face exact secvența cerută: launch → wait window → (ajunge pe lista de chat-uri) → open search → set text → choose contact (fonetic) → verify chat → click call → verify → return. Întoarce un rezultat structurat unic: `WhatsAppCallResult(success: Boolean, step: String, error: String?)` (`:70`).

**Este cod mort acum:** funcția publică `placeWhatsAppCall(...)` (`:949`) → `placeWhatsAppCallInner` e expusă prin `BensonAccessibilityModule.kt:141` (`AsyncFunction("placeWhatsAppCall")`) și `modules/benson-accessibility/index.js:85`, dar **niciun fișier `.ts`/`.tsx` din aplicație nu o apelează** (grep: doar comentarii în `whatsappTool.ts:157, 208` o menționează; import-ul din `whatsappTool.ts:8` NU include `placeWhatsAppCall`). Calea activă a apelului e `runCallRecipe` (JS), via `USE_LEGACY_CALL_RECIPE = false` (`whatsappTool.ts:805`).

**Verdict de recuperabilitate:** DA, poate fi reactivată. Rulează deja complet nativ și supraviețuiește background-ului. Îi lipsesc **4 lucruri** pe care calea JS le-a câștigat de atunci (BF1-b viewId-first, BF1-c/WA-FIX-1 overlay-bypass, plimbarea arborelui off-`Dispatchers.Main` din Runda B, plus cablarea). Detalii §5–§6.

---

## 2. Ce face, pas cu pas (`placeWhatsAppCallInner`, `BensonAccessibilityService.kt:964`)

Prefixe: toate `waitForNode(...)` = `BensonAccessibilityService.kt:804` — `while (now < deadline) { if (requirePackage==null || rootInActiveWindow?.packageName==requirePackage) findNodeMatching(predicate)?.let{return it}; delay(pollMs) }`. `findNodeMatching` (`:772`) → `findNodeRecursive` (`:781`) DFS pe `rootInActiveWindow`, `MAX_NODES=400`, `MAX_DEPTH=40`. `recipeStep(name, anchor, found, elapsedMs)` (`:870`) → `Log.i("BENSON_AUDIO", "RECIPE_STEP index=${recipeStepIndex++} name=$name anchor=$anchor found=$found elapsedMs=$elapsedMs")`.

| Pas | Linii | Ce face | Ancoră / selector | Eșec → `WhatsAppCallResult` |
|---|---|---|---|---|
| **0 · launch** | `:974-983` | `launchWhatsApp()` (`:874` — `packageManager.getLaunchIntentForPackage("com.whatsapp")` + `FLAG_ACTIVITY_NEW_TASK` + `startActivity`). Apoi `waitForNode(3000, 100, WHATSAPP_PACKAGE, "whatsapp_window") { true }` | pachet == `com.whatsapp` pe `rootInActiveWindow` (predicat `{true}`) | `(false, "launch", "WhatsApp did not reach the foreground.")` |
| **1 · reach chat list** | `:985-1022` | Buclă `for (attempt in 1..5)`: (a) `waitForNode(1200, ...) { it.isClickable && matchesAny(it, SEARCH_KEYWORDS) }` → `onChatList=true`; (b) `findNodeMatching { viewIdResourceName == "com.whatsapp:id/search_input" }` → refolosește câmpul (căutare rămasă deschisă); (c) `findNodeMatching { viewIdResourceName == "com.whatsapp:id/entry" }` == null → încă se încarcă, `continue`; (d) blocat într-un chat → tap `com.whatsapp:id/whatsapp_toolbar_home` (dacă clickable) altfel `performGlobalAction(GLOBAL_ACTION_BACK)` | `SEARCH_KEYWORDS` (text/contentDesc), viewId-uri `search_input` / `entry` / `whatsapp_toolbar_home` | `dumpScreenForDebug` + `(false, "reach_chat_list", ...)` |
| **2 · open search** | `:1024-1046` | dacă `searchField==null`: `waitForNode(3000, ...) { it.isClickable && matchesAny(it, SEARCH_KEYWORDS) }` → `performAction(ACTION_CLICK)` → `waitForNode(3000, ...) { it.isEditable }` → `searchField` | `SEARCH_KEYWORDS` pentru icon; `isEditable` (primul nod editabil) pentru câmp | `(false, "open_search")` / `(false, "tap_search")` / `(false, "search_field")` |
| **3 · type prefix** | `:1048-1053` | `val prefix = normPhon(name).take(3).ifEmpty { name.take(3) }` (prefix fonetic de 3 caractere, „Han"). `searchField.performAction(ACTION_SET_TEXT, {CHARSEQUENCE: prefix})` | — | `(false, "type_prefix")` |
| **4 · find result** | `:1055-1073` | Tier 1: `waitForNode(1800, ...) { rowFilter(it) && wholeLabelPhoneticEquals(nodeLabel(it), name) }`. Tier 2: `waitForNode(2800, ...) { rowFilter(it) && phoneticNameMatch(nodeLabel(it), name) }`. `rowFilter = { !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it) }` | fonetic pe `nodeLabel` = `"${text} ${contentDescription}".lowercase().trim()` (`:675`), vs numele COMPLET; `normPhon` = NFD + strip `\p{Mn}` + lowercase + `[^a-z0-9]` scos; `collapseDoubles` (Hanna~Hannah); `consSkeleton` (scoate vocale); tier-1 = egalitate întreagă (ca „…Davids Mama" să nu bată „Mama") | `dumpScreenForDebug` + `(false, "find_result", ...)` |
| **5 · tap row** | `:1075-1099` | `tapTarget = findClickableAncestor(resultLabelNode) ?: resultLabelNode.takeIf{it.isClickable}` (`findClickableAncestor` `:753` — urcă max 6 nivele la primul clickable). `performAction(ACTION_CLICK)`. Retry ×3 cu re-rezolvare FRESH: `waitForNode(700, ..., "result_retry") { rowFilter && (wholeLabel|| phonetic) }` | strămoș clickable al etichetei (containerul rândului) | `(false, "tap_result", "...after N attempts")` |
| **6 · verify chat** | `:1101-1111` | `waitForNode(3000, ...) { it.viewIdResourceName == "com.whatsapp:id/entry" }` | **viewId exact** `com.whatsapp:id/entry` (câmpul de compunere — există DOAR într-un chat individual) | `dumpScreenForDebug` + `(false, "verify_chat")` |
| **6b · two-phase stop** | `:1113-1116` | dacă `autoPressCall == false` → `return WhatsAppCallResult(true, "chat_opened", null)` | — | — |
| **7 · click call** | `:1118-1136` | `val maxTop = headerRegionMaxTop()` (`:766` — 15% din înălțimea ecranului). `waitForNode(2500, ...) { getBoundsInScreen(b); it.isClickable && b.top <= maxTop && matchesAny(it, CALL_KEYWORDS) && !matchesAny(it, VIDEO_EXCLUDE_KEYWORDS) }`. `if (isPaymentSensitive(callNode)) → blocat`. `performAction(ACTION_CLICK)` | `CALL_KEYWORDS` (text/contentDesc), DOAR în banda de antet (top ≤ 15%), exclus dacă eticheta conține „video" | `dumpScreenForDebug` + `(false, "find_call_button")` / `(false, "call_button_blocked")` / `(false, "tap_call_button")` |
| **8 · settle + return** | `:1138-1144` | `delay(2500)` (WhatsApp are nevoie de timp să stabilească sesiunea — confirmat live 2026-07-17: revenirea prea rapidă anulează apelul). `returnToBenson()` (`:892` — `startActivity` + `FLAG_ACTIVITY_REORDER_TO_FRONT`). `return WhatsAppCallResult(true, "done")` | — | — |

Wrapper public `placeWhatsAppCall(contactName, autoPressCall = true)` (`:949`): `whatsappAutomationActive = true` pe toată durata (suprimă Guardian), `try { placeWhatsAppCallInner(...) } finally { whatsappAutomationActive = false }`.

### Selectorii / keyword-listele folosite (companion object, `BensonAccessibilityService.kt:94-112`)
```kotlin
WHATSAPP_PACKAGE       = "com.whatsapp"
SEARCH_KEYWORDS        = ["search","căutare","cautare","caută","cauta","suche","suchen"]
CALL_KEYWORDS          = ["voice call","apel vocal","apel","sprachanruf","anruf","call"]
VIDEO_EXCLUDE_KEYWORDS = ["video"]
PAYMENT_BLOCKLIST      = ["pay","plateste","plătește","confirm payment","buy now","cumpara",
                          "cumpără","checkout","3d secure","3-d secure","otp","cvv",
                          "card number","numar card","număr card"]
```
viewId-uri hardcodate în flow: `com.whatsapp:id/search_input` (`:999`), `com.whatsapp:id/entry` (`:1005, :1105`), `com.whatsapp:id/whatsapp_toolbar_home` (`:1013`).

---

## 3. Rulează complet nativ, fără JS între pași?

**DA.** Singura implicare JS:
1. apelul de start: `NativeModule.placeWhatsAppCall(contactName, autoPressCall)` (`index.js:86`) → `BensonAccessibilityModule.kt:141` `AsyncFunction("placeWhatsAppCall")` → `svc.runOnServiceScope { val result = svc.placeWhatsAppCall(...); promise.resolve(...) }` (`:150-153`).
2. rezultatul final: `promise.resolve(mapOf("success" to …, "step" to …, "error" to …))` (`:152`).

Între ele, TOT rulează în Kotlin pe `serviceScope`:
- `launchWhatsApp()` / `returnToBenson()` — `startActivity` sincron.
- `waitForNode(...)` (`:804`) — `while` + `delay(pollMs)` **coroutine Kotlin**, nu `setTimeout` JS. Comentariul serviciului (`:39-44`): „Every step of placeWhatsAppCall() below runs on this scope, driven by native delay()/rootInActiveWindow polling, **never a JS setTimeout**."
- `findNodeMatching` / `findNodeRecursive` — DFS sincron pe `rootInActiveWindow`.
- `performAction(ACTION_CLICK / ACTION_SET_TEXT)` — IPC sincron către view-ul aplicației țintă.
- `delay(2500)` la pasul 8 — coroutine.
- fonetică (`normPhon`, `phoneticNameMatch`, `wholeLabelPhoneticEquals`, `collapseDoubles`, `consSkeleton`) — pure Kotlin.

`serviceScope` = `Dispatchers.Main` al procesului (firul principal / Looper), **NU firul JS al RN**. Cât timp procesul are CPU (garantat de foreground service + `PARTIAL_WAKE_LOCK`), acest flow avansează independent de starea Activity-ului BENSON. Asta e exact proprietatea care lipsește căii JS (`runCallRecipe`, vezi `AUDIT_BG_REPORT.md`).

**`NATIVE_SEQUENCING_IS_COMPLETE = YES` · `RUNS_WITHOUT_JS_BETWEEN_STEPS = YES` · `CURRENTLY_REACHABLE_FROM_APP = NO`**

---

## 4. Comparație pas-cu-pas: `placeWhatsAppCallInner` (nativ, inactiv) vs `runCallRecipe` (JS, activ)

| Etapă | `runCallRecipe` — `whatsappTool.ts` (JS) | `placeWhatsAppCallInner` — `BensonAccessibilityService.kt` (Kotlin) | Cine e mai bun / diferență |
|---|---|---|---|
| **launch WhatsApp** | `executeCommand({launch_app})` → `BensonCommandExecutor.doLaunchApp` (`getLaunchIntentForPackage` + `startActivity`) (`whatsappTool.ts:815`) | `launchWhatsApp()` (`:874`) — mecanism identic | egale |
| **wait window** | `waitForNode([{viewIdContains:'com.whatsapp'},{className:'FrameLayout'}], 5000)` (JS, `setTimeout`) + `executeCommand({assert_package})` (`whatsappTool.ts:819-823`) — `assert_package` folosește **BF1-c/WA-FIX-1**: `getWindows()` scan + `lastForegroundPackage` fallback, trece de bula BENSON | `waitForNode(3000, ..., WHATSAPP_PACKAGE) { true }` (`:979`) — verifică DOAR `rootInActiveWindow?.packageName == "com.whatsapp"` | **JS mai robust aici** — dacă bula BENSON (overlay) e fereastra activă, `rootInActiveWindow.packageName == com.benson.butler` → pasul 0 nativ **eșuează** cu `(false, "launch")`. Native n-are overlay-bypass. |
| **reach chat list** | (nu are — `runCallRecipe` presupune că `launch_app` aduce lista) | Buclă dedicată `:985-1022`: detectează „blocat într-un chat", apasă înapoi, retry ×5; refolosește o căutare rămasă deschisă | **Native mai robust aici** — tratează cazul „WhatsApp s-a deschis direct într-un chat" pe care JS îl ignoră |
| **open search (buton)** | `waitForNode(SEARCH_ANCHORS)` — **BF1-b cascadă**: `viewId:menuitem_search` → `viewIdContains:menuitem_search` → `contentDescription`[suchen,cauta,cautare,search] → `textContains`[…] (`whatsappTool.ts:826, 734-739`) | `waitForNode { it.isClickable && matchesAny(it, SEARCH_KEYWORDS) }` — `matchesAny` = `nodeLabel.contains(keyword)`, DE-first, **fără viewId** (`:1027-1029`) | **JS mai robust** — BF1-b (log 17:01:10) a fost fix exact pentru „un singur text, WhatsApp e în germană → not_found". Native e la nivelul de dinainte de BF1-b (text-only). `menuitem_search` există și e cunoscut (`SEARCH_STRATEGIES`), dar nu e folosit aici. |
| **set text** | `executeCommand({set_text, match:{viewIdContains: <id câștigător> || 'search_input'}})` (`whatsappTool.ts:842-846`) | `searchField.performAction(ACTION_SET_TEXT, {CHARSEQUENCE: prefix})` pe „primul nod editabil" (`:1039, :1051`) | JS puțin mai precis (țintește `search_input`); ambele folosesc prefix fonetic de 3 caractere |
| **choose contact** | `resolveResultPick` → `pickBestPhonetic` (`consonantSkeleton` + `boundedLevenshtein` peste rânduri, B-fix2; citește nume din `text` ȘI `contentDescription`) (`whatsappTool.ts:851-864`) | `wholeLabelPhoneticEquals` (tier 1) → `phoneticNameMatch` (tier 2) peste `nodeLabel` (text+contentDesc); excluderi confirmate-live: `isSearchUiNode` / `isAvatarNode` / `isRecentSuggestionNode` (`:1059-1068`) | **~egale** — aceeași familie de potrivire fonetică (comentariul B-fix2 spune chiar „algoritmul din `lib/appIndex.ts`"). Native are în plus tier-1 egalitate-întreagă + 3 excluderi dovedite live; JS are `boundedLevenshtein` (toleranță la vowel-slip). |
| **tap row** | `executeCommand({click, textContains: rowText, clickableAncestor:true})` (`whatsappTool.ts:871`) | `findClickableAncestor(node)` + `performAction(ACTION_CLICK)`, retry ×3 cu re-rezolvare FRESH (`:1076-1096`) | **Native mai robust** — retry cu re-citire fresh (nodurile devin stale la ~200ms după update de arbore) |
| **verify chat opened** | `waitForNodeGone(SEARCH_INPUT_ANCHOR)` + `waitForNode({viewIdContains:':id/entry'})` (`whatsappTool.ts:880-882`) | `waitForNode { viewIdResourceName == "com.whatsapp:id/entry" }` (`:1104`) | ~egale (ambele pe `entry`) |
| **click call** | `waitForNode(VOICECALL_ANCHORS)` — **BF1-b cascadă**: `viewId:menuitem_call` → `viewIdContains` → `voip_call` → `contentDescription`[sprachanruf,apel vocal,voice call,apelare,suna,anrufen] → `textContains`. „Apel"/„Call" simple OMISE intenționat (coliziune video) (`whatsappTool.ts:887, 744-758`) | `waitForNode { b.top <= maxTop(15%) && matchesAny(it, CALL_KEYWORDS) && !matchesAny(it, ["video"]) }` — `CALL_KEYWORDS` INCLUDE bare „apel"/„anruf"/„call"; anti-coliziune prin excludere „video" + banda de antet (`:1121-1124`) | **Strategii diferite, ~egale.** JS: allowlist strict (viewId + termeni specifici). Native: blocklist („video") + constrângere geometrică (top ≤ 15%). Native n-are viewId-tier. Risc rezidual native: un buton video etichetat fără cuvântul „video" ar putea prinde „anruf"/„call". |
| **return to BENSON** | Pasul 8: **fără** `return_to_benson` — ecranul de apel WhatsApp rămâne în prim-plan (Runda B4) (`whatsappTool.ts:894`) | `delay(2500)` + `returnToBenson()` (`startActivity` REORDER_TO_FRONT) (`:1142-1143`) | **Decizie de produs divergentă** — trebuie aliniată. `returnToBenson` dintr-un serviciu de fundal = restricția ColorOS de background-activity-start (poate fi silent no-op). |
| **rezultat** | `ToolCallResult { outcome: 'app_switch_observed' \| 'opened_manual_action_required', error?, via? }` — `stopNotFound(step)` numește pasul în text RO | `WhatsAppCallResult(success, step, error?)` — `step` e enum de stadiu (`launch`/`reach_chat_list`/`open_search`/`search_field`/`type_prefix`/`find_result`/`tap_result`/`verify_chat`/`chat_opened`/`find_call_button`/`tap_call_button`/`done`) | **Native = exact contractul „SUCCESS/FAILED + exact stage" cerut** |
| **logging** | `RECIPE_STEP index=N name=… anchor=… found=…` + `SELECTOR step=N won=viewId\|contentDesc\|text value=…` (BF1-b) + `WAIT_NODE` + `EXEC_ERROR` | `RECIPE_STEP index=N name=… anchor=… found=… elapsedMs=…` + `WAIT_NODE predicate=<anchor> result=found\|timeout` + `dumpScreenForDebug` la timeout | format `RECIPE_STEP` compatibil; native n-are linia `SELECTOR won=…` |
| **plimbarea arborelui** | snapshot nativ `captureSnapshot()` (`BensonAccessibilityService.kt:490`) — **`withContext(Dispatchers.Default)` + `withTimeoutOrNull(1200)`** (off-main, plafonat — fix Runda B pentru „hung ~60s") | `findNodeMatching`/`findNodeRecursive` (`:772-797`) rulează pe **`Dispatchers.Main`** (contextul `serviceScope`), `MAX_NODES=400` | **Native are riscul pe care Runda B l-a reparat pe calea JS** — plimbarea arborelui pe firul principal se poate bloca pe `getChild()` IPC cât timp WhatsApp se lansează |

---

## 5. Ce e vechi / fragil în `placeWhatsAppCallInner`

| # | Problemă | Linii | Gravitate |
|---|---|---|---|
| 1 | **Fără cascadă viewId-first** pentru butonul de căutare (pas 2) și butonul de apel (pas 7). Potrivire pur text/contentDescription prin `matchesAny` (substring). BF1-b (log 17:01:10) a fost fixul exact pentru asta pe calea JS. `menuitem_search` / `menuitem_call` sunt cunoscute și neatinse aici. | `:990, :1027, :1121` + `CALL_KEYWORDS`/`SEARCH_KEYWORDS` `:102-103` | **Mare** — cea mai importantă lecție BF1-b lipsește |
| 2 | **Fără overlay/foreground-bypass (BF1-c / WA-FIX-1).** Pasul 0/1 se bazează pe `rootInActiveWindow?.packageName == "com.whatsapp"`. Cu bula BENSON deasupra, `rootInActiveWindow` raportează `com.benson.butler` → pasul 0 eșuează. `BensonCommandExecutor.resolveForegroundPackage` (`getWindows()` + `lastForegroundPackage`) rezolvă asta, dar nu e refolosit aici. | `:979, :814` | **Mare** |
| 3 | **Plimbarea arborelui pe `Dispatchers.Main`.** `findNodeRecursive` (`:781`) walk sincron pe firul principal, `MAX_NODES=400`. Runda B a mutat exact acest walk off-main (`captureSnapshot`, `Dispatchers.Default` + `withTimeoutOrNull`) fiindcă `getChild()` IPC bloca ~60s la lansarea WhatsApp. | `:772-797, :804-824` | **Medie-Mare** — risc de blocare a firului principal |
| 4 | viewId hardcodat `com.whatsapp:id/whatsapp_toolbar_home` (butonul „înapoi din chat individual"), neverificat pe WhatsApp 2.26.34.81. | `:1013` | Mică (are fallback `GLOBAL_ACTION_BACK`) |
| 5 | `nodeLabel` (`:675`) face `lowercase()` dar **NU scoate diacriticele** pentru `matchesAny`. Nodurile RO cu diacritice (`Caută`) ar putea rata; DE (dispozitivul confirmat) acoperă. | `:675, :678-682` | Mică |
| 6 | `set_text` țintește „primul nod editabil" (`:1039`), nu `search_input` explicit. | `:1039, :1051` | Mică |
| 7 | `CALL_KEYWORDS` include bare „apel"/„anruf"/„call" — coliziune cu apelul VIDEO, mitigată doar prin excluderea „video" + banda de antet. Dacă butonul video e etichetat fără cuvântul „video" → risc de apel greșit. | `:103, :1124` | Mică-Medie |
| 8 | `returnToBenson()` la pasul 8 dintr-un serviciu de fundal = pattern blocat de restricția ColorOS de background-activity-start (silent no-op posibil). Și divergență de produs cu Runda B4 (JS lasă ecranul de apel WhatsApp în prim-plan). | `:1143, :892` | Mică (cosmetic) |
| 9 | `recipeStepIndex` e câmp mutabil de instanță (`:869`), resetat la `:965` — sigur doar sub presupunerea unui singur apel în zbor (garantată de `whatsappAutomationActive`). | `:869, :965` | Mică |
| 10 | Fără `AccessibilityNodeInfo.refresh()` explicit pe re-citire (constatarea ACC-1: `refresh()` era necesar ca să vezi efectul unei acțiuni). `waitForNode` re-ia `rootInActiveWindow` proaspăt la fiecare poll (mai bine decât un nod cache-uit), iar Runda B a adăugat `typeWindowContentChanged` în config exact ca `rootInActiveWindow` să rămână proaspăt pentru rezultatele de căutare WhatsApp — deci probabil OK, dar de verificat. | `:813-819` | Mică (de verificat) |

---

## 6. Ce trebuie schimbat ca să devină executorul activ de apel WhatsApp

**Fără a rescrie de la zero** — sunt porturi punctuale din ce a învățat calea JS între timp:

1. **Cablare (obligatoriu).** În `src/core/mission/tools/whatsappTool.ts`: adaugă `placeWhatsAppCall` la import-ul din `'benson-accessibility'` (`:8`); în `placeCall` (`:900`), pe ramura non-legacy, cheamă `NativeModule.placeWhatsAppCall(name, true)` în loc de `runCallRecipe(name)`; mapează `WhatsAppCallResult {success, step, error}` → `ToolCallResult` (`success → app_switch_observed`; `!success → opened_manual_action_required` cu `error`/`step`). Alternativ, tot flow-ul de decizie mută-l în `missionExecutor.runTool` (`missionExecutor.ts:169-175`). Păstrează `USE_LEGACY_CALL_RECIPE` ca și constantă de revert, plus una nouă (`USE_NATIVE_CALL_RECIPE`).
2. **Portează cascada viewId-first BF1-b** în predicatele native de la pasul 2 (căutare) și pasul 7 (apel): întâi `viewIdResourceName == "com.whatsapp:id/menuitem_search"` / `"…menuitem_call"` (și `viewIdContains`), apoi lista contentDescription, apoi lista text — prima potrivire câștigă. Pentru câmpul de căutare (pas 2/3): `viewIdContains "search_input"` (fallback `"search_src_text"`) în locul lui „primul editabil".
3. **Portează overlay-bypass BF1-c / WA-FIX-1** în detecția de prim-plan a pașilor 0–1: în loc de `rootInActiveWindow?.packageName == WHATSAPP_PACKAGE`, folosește logica `resolveForegroundPackage` din `BensonCommandExecutor.kt:103` (scan `getWindows()` + fallback `lastForegroundPackage`). E deja în același modul — se poate extrage într-un helper comun.
4. **Mută plimbarea arborelui off-`Dispatchers.Main`** — fie `findNodeMatching`/`findNodeRecursive` în `withContext(Dispatchers.Default)` cu `withTimeoutOrNull`, fie refolosește direct `captureSnapshot()` (deja off-main + plafonat) și potrivește predicatele pe JSON-ul lui.
5. **Aliniază comportamentul de return** — decide: rămâi pe ecranul de apel WhatsApp (Runda B4, ca `runCallRecipe`) SAU `returnToBenson()` după settle. Dacă rămâi, scoate `delay(2500)+returnToBenson()` de la pasul 8.
6. **PiP** — `runCallRecipe` face `enterPipBeforeWhatsApp()` (JS) înainte. Fie flow-ul nativ intră singur în PiP, fie apelantul JS face `enterPipMode()` înainte de `NativeModule.placeWhatsAppCall`.
7. **Verifică viewId-urile pe WhatsApp 2.26.34.81** (`menuitem_search`, `menuitem_call`, `search_input`, `entry`, `whatsapp_toolbar_home`) cu `uiautomator dump` pe dispozitiv — aceeași verificare cerută și pentru BF1-b/BF1-c.
8. **Logging** — adaugă linia `SELECTOR step=N won=viewId|contentDesc|text value=…` (formatul BF1-b) pe fiecare selector rezolvat, ca diagnosticul să fie identic cu ce se așteaptă deja utilizatorul.
9. **Păstrează neatins ce merge deja:** secvențierea nativă pe `serviceScope`, `WhatsAppCallResult(success, step, error)`, re-citirea FRESH per pas, verify-before-act, `isPaymentSensitive` în buclă, excluderile avatar/recent/search-UI, tier-1 whole-label, prefixul fonetic de 3 caractere, `delay(2500)` de settle după butonul de apel, retry ×3 pe tap-ul rândului, bucla „reach chat list".

---

## 7. Verdict

`RECIPE_RUNS_FULLY_NATIVE = YES` — `placeWhatsAppCallInner` secvențiază toți pașii (launch→wait→search→type→choose→click→verify→return) în Kotlin pe `serviceScope`, cu `while`+`delay()` coroutine, zero JS și zero `setTimeout` între pași.

`SURVIVES_ACTIVITY_BACKGROUND = YES` (structural) — pe `Dispatchers.Main` al procesului, independent de firul JS al RN; comentariul `:39-44` afirmă exact asta. Caveat: dacă ColorOS omoară procesul/serviciul, cade oricum (separat de cuplarea-cu-Activity).

`CURRENTLY_ACTIVE = NO` — cod mort; niciun apelant JS. Calea activă e `runCallRecipe` (JS), `USE_LEGACY_CALL_RECIPE=false`.

`CAN_BE_RECOVERED = YES` — cu ~4 porturi punctuale (cascadă viewId BF1-b, overlay-bypass BF1-c/WA-FIX-1, walk off-`Dispatchers.Main` Runda B, cablarea în `whatsappTool.ts`/`missionExecutor.ts`) + verificarea viewId-urilor pe dispozitiv. Nu necesită rescriere.

`FASTEST_PATH_TO_WORKING_WHATSAPP = da` — reactivarea + modernizarea acestei căi native e mai rapidă și mai robustă decât cârpirea `waitForNode()`/`setTimeout()` din `runCallRecipe`, fiindcă elimină cauza structurală (secvențierea în JS care îngheață la background) în loc s-o mascheze.

`NATIVE_KNOWS_HOW_TO`: launch WhatsApp ✅ · wait window ✅ (fără overlay-bypass) · reach chat list ✅ (mai bine ca JS) · open search ✅ (fără viewId-tier) · set text ✅ (pe „primul editabil") · choose contact fonetic ✅ (~egal cu JS) · tap row ✅ (retry fresh, mai bine ca JS) · verify chat ✅ (viewId `entry`) · click call ✅ (fără viewId-tier, anti-video prin blocklist+geometrie) · verify + structured result ✅ (`WhatsAppCallResult(success, step, error)` = exact contractul cerut).
