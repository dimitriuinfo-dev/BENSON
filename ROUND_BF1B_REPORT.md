# RUNDA BF1-b — selectorii WhatsApp pe viewId

**Scope respectat:** un singur fișier de cod — `src/core/mission/tools/whatsappTool.ts`. Fără `git`, `expo prebuild`, `setx`. Nimic comis.

**Stare:** cascada BF1-b era deja implementată (rundă anterioară). Această re-emitere a adăugat un singur termen sigur în lista de apel (`anrufen`) și a rulat verificarea. **Verificarea viewId-urilor pe snapshot live NU s-a putut face — dispozitivul `9c1464eb` e deconectat** (a căzut după instalarea C3-fix, 09:26). Detalii §3.

---

## 1. Cauza (log 17:01:10)

```
RECIPE_STEP index=4 label="butonul de căutare" strategy=none matched="" status=not_found
EXEC_ERROR phase=search "No node matched {textContains:"căutare", clickable:true} within 4000ms"
```

Selectorul de căutare încerca un singur text (`căutare`, română). WhatsApp e în germană → butonul se numește altfel → `not_found`.

---

## 2. Fixul

Toți selectorii pe text din rețeta de apel activă (`runCallRecipe`, calea din B-fix2) sunt **cascadă strictă**: `viewId` → `contentDescription` (listă DE→RO→EN) → `text`/`textContains` (listă). `waitForNode` iterează predicatele în ordine, **prima potrivire câștigă**.

Constantă de revert: **`BF1B_SELECTORS_VIEWID = true`** (`whatsappTool.ts:715`). Pe `false` → listele `*_LEGACY`, păstrate în fișier.

### Pasul 2 — butonul de căutare · `SEARCH_ANCHORS_BF1B` (whatsappTool.ts:734)

| # | selector | tier | viewId încercat | există în arbore pe acest build? |
|---|---|---|---|---|
| 1 | `{ viewId: 'com.whatsapp:id/menuitem_search' }` | viewId | `com.whatsapp:id/menuitem_search` | **NEVERIFICAT pe snapshot live** (dispozitiv offline). Atestat de istoricul de inspecție al codului: `WA_ID.searchMenu`, `SEARCH_STRATEGIES` (whatsappTool.ts:450, 471). |
| 2 | `{ viewIdContains: 'menuitem_search' }` | viewId | idem, toleranță la prefix | idem |
| 3–6 | `{ contentDescription: 'suchen'\|'cauta'\|'cautare'\|'search' }` | contentDesc | — | fallback DE/RO/EN |
| 7–10 | `{ textContains: 'suchen'\|'cauta'\|'cautare'\|'search' }` | text | — | ultimul resort |

Notă: matcher-ul nativ face `lowercase` pe `text + contentDescription`; formele fără diacritice (`cauta`, `cautare`) sunt intenționate. Un build RO real ar cere `caută`/`căutare` cu diacritice — dar cazul dovedit e build-ul **german**, unde `suchen` + `search` acoperă.

### Pasul 3 — câmpul de căutare · `SEARCH_FIELD_ANCHORS_BF1B` (whatsappTool.ts:740)

| # | selector | viewId încercat | există? |
|---|---|---|---|
| 1 | `{ viewIdContains: 'search_input' }` → `com.whatsapp:id/search_input` | `search_input` | **NEVERIFICAT pe snapshot live.** „Deja dovedit" pentru `ACTION_SET_TEXT` în `SESSION_REPORT.md`; folosit în `WA_ID.searchInput` + `set_text` nativ. |
| 2 | `{ viewIdContains: 'search_src_text' }` | `search_src_text` | **NEatestat nicăieri în acest cod.** id legacy (AppCompat vechi). Rămâne fallback de rang 2; `set_text` (whatsappTool.ts:837) folosește id-ul care a câștigat efectiv (`field.predicate.viewIdContains`). |

Rândul de contact (`com.whatsapp:id/contact_row_container`) — **NU a fost cablat ca selector**, intenționat: toate rândurile îl au, deci nu ajută la a alege CARE rând. Alegerea rândului rămâne pe potrivirea fonetică din B-fix2 (`pickBestPhonetic`, NEATINSĂ), iar clickul urcă la container prin `clickableAncestor:true` (whatsappTool.ts:864, `rowFlags`).

### Pasul 7 — butonul de apel vocal · `VOICECALL_ANCHORS_BF1B` (whatsappTool.ts:744)

| # | selector | tier | viewId încercat | există? |
|---|---|---|---|---|
| 1 | `{ viewId: 'com.whatsapp:id/menuitem_call' }` | viewId | `com.whatsapp:id/menuitem_call` | **NEVERIFICAT pe snapshot live.** Atestat: `WA_ID.voiceCallMenu`, `VOICECALL_STRATEGIES` (whatsappTool.ts:454, 476). **Specific apelului VOCAL** (nu video). |
| 2 | `{ viewIdContains: 'menuitem_call' }` | viewId | idem | idem |
| 3 | `{ viewIdContains: 'voip_call' }` | viewId | `voip_call` | **NEatestat pe acest build.** Fallback de rang inferior. |
| 4–8 | `{ contentDescription: 'sprachanruf'\|'apel vocal'\|'voice call'\|'apelare'\|'suna ' }` | contentDesc | — | forme SPECIFICE apelului vocal |
| 9 | `{ contentDescription: 'anrufen' }` | contentDesc | — | **adăugat în re-emitere.** DE „a suna". Sigur: „Videoanruf" se termină în „…anruf", NU conține „anrufen". |
| 10–13 | `{ textContains: 'sprachanruf'\|'apel vocal'\|'voice call'\|'anrufen' }` | text | — | ultimul resort |

**Abatere de la lista cerută (raportată, per regula 4 din CLAUDE.md):** lista din spec e `apel: ["Anrufen","Sprachanruf","Apel","Apelare","Sună","Voice call","Call"]`. Am adăugat `"Anrufen"`, dar `"Apel"` și `"Call"` simple rămân **OMISE**. Motiv verificat în cod: `predicateToClickMatch()` (whatsappTool.ts:760–768) transformă ORICE `contentDescription`/`text` în `{ textContains: needle }` — **substring**, nu egalitate. „apel" prinde „**Apel** video"; „call" prinde „Video **call**". Într-un build RO/EN fără viewId, cascada ar putea apăsa butonul de apel **VIDEO**. `menuitem_call` (viewId, specific vocal) + `sprachanruf`/`apel vocal`/`voice call`/`anrufen` acoperă cazul fără ambiguitate. „Apelul greșit e mai rău decât un eșec onest."

### Log per selector rezolvat (whatsappTool.ts:776 `logSelector`)

```
SELECTOR step=2 label="butonul de căutare"     won=viewId|contentDesc|text value="<ce a găsit>"
SELECTOR step=3 label="câmpul de căutare"      won=viewId|contentDesc|text value="<...>"
SELECTOR step=7 label="butonul de apel vocal"  won=viewId|contentDesc|text value="<...>"
```
`won` = `viewId` dacă predicatul câștigător avea `viewId`/`viewIdContains`; `contentDesc` dacă avea `contentDescription`; altfel `text`. `value` = viewId-ul real al nodului (pentru viewId) sau `text`/`contentDescription` (altfel).

### Alte modificări (deja în fișier din runda anterioară, neatinse acum)
- `predicateToClickMatch()` — `clickableAncestor: true` pe TOATE ramurile, inclusiv `viewId` (un nod găsit după id poate să nu fie el însuși clickable).
- `waitForNodeGone()` — acceptă `NodePredicate | NodePredicate[]` (pasul 6: „câmpul de căutare a dispărut").
- Pașii 0–3 (lansare, prim-plan, prefix de 3 caractere) — NEATINși. Potrivirea fonetică B-fix2 — NEATINSĂ.

---

## 3. Verificarea viewId-urilor „pe snapshot-ul real" — BLOCAT

**Dispozitivul `9c1464eb` e deconectat acum** (`adb devices` → gol; a căzut după instalarea C3-fix la 09:26). Fără el nu pot rula un `uiautomator dump` al ecranelor WhatsApp de căutare / conversație ca să confirm că `menuitem_search`, `search_input`, `menuitem_call` există literal în arborele acestui build WhatsApp. **Nu am inventat id-uri** — cele trei cablate ca tier 1 vin din istoricul de inspecție live al ACESTUI cod (citate mai sus); `search_src_text` și `voip_call` sunt marcate explicit NEatestate și stau doar ca fallback de rang inferior, cu textul ca plasă.

**Când reconectezi telefonul**, rulează (sau cere-mi mie s-o fac):
```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" shell monkey -p com.whatsapp -c android.intent.category.LAUNCHER 1   # WhatsApp în prim-plan
"$ADB" shell uiautomator dump /sdcard/wa_main.xml && "$ADB" pull /sdcard/wa_main.xml
#   → grep 'resource-id="com.whatsapp' wa_main.xml   → confirmă menuitem_search
#   apasă lupa pe telefon, apoi:
"$ADB" shell uiautomator dump /sdcard/wa_search.xml && "$ADB" pull /sdcard/wa_search.xml
#   → confirmă search_input / search_src_text
#   deschide o conversație, apoi:
"$ADB" shell uiautomator dump /sdcard/wa_chat.xml && "$ADB" pull /sdcard/wa_chat.xml
#   → confirmă menuitem_call / voip_call
```
**Verificarea reală rămâne logul pe care mi-l trimiți:** liniile `SELECTOR step=… won=…`. Dacă vreuna arată `won=contentDesc` sau `won=text`, id-ul de deasupra lipsește pe acest build și îl corectez cu valoarea din `value=`.

---

## 4. Linii modificate în această re-emitere (`src/core/mission/tools/whatsappTool.ts`)

| Zonă | Ce | Δlinii |
|---|---|---|
| `VOICECALL_ANCHORS_BF1B` (L744–758) | + `{ contentDescription: 'anrufen' }`, + `{ textContains: 'anrufen' }`; comentariul rescris (coliziunea „apel"/„call" cu apelul video, explicit) | +6 / −2 |
| **Total net** | | **~+4** |

Restul cascadei BF1-b (`BF1B_SELECTORS_VIEWID`, `SEARCH_ANCHORS_BF1B`, `SEARCH_FIELD_ANCHORS_BF1B`, `SEARCH_ANCHORS/SEARCH_INPUT_ANCHOR/VOICECALL_ANCHORS` prin ternar, `selectorKind`/`logSelector`, apelurile `logSelector(2|3|7,…)` din `runCallRecipe`, `*_LEGACY`) era deja în fișier din runda anterioară — neatins acum.

---

## 5. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** — 0 erori. |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 1m 43s`** · `67 executed / 878 up-to-date` · `android/` neregenerat. |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 884 944 B (~248,8 MiB)** · mtime `2026-09-08 12:49:25`. |
| SHA-256 (certutil) | `e6d333502eb50c46315e6fc349d17a8d5f6a8c4358f495188ac50164b289e542` |
| Certificat | `apksigner verify` → **exit 0** · `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · Scheme v2. |
| Instalare | **NEEFECTUATĂ** — `9c1464eb` deconectat. De rulat de tine: `adb install -r "…/app-release.apk"`. |

> APK-ul conține și C1 / C3 / C3-fix (rundele anterioare, whatsappTool BF1-b + `anrufen`).

---

## 6. Ce urmăresc în log (după reconectare + `adb install -r` + 5× „Sună-o pe Hannah pe WhatsApp")

```
SELECTOR step=2 won=viewId value="com.whatsapp:id/menuitem_search"
RECIPE_STEP index=2 ... found=true
SELECTOR step=3 won=viewId value="com.whatsapp:id/search_input"
CONTACT_MATCH query="…" candidates=N matched="HANNAH" score=<≥260>
RECIPE_STEP index=5 ... found=true
SELECTOR step=7 won=viewId value="com.whatsapp:id/menuitem_call"
RECIPE_STEP index=7 ... found=true
```
→ apelul VOCAL pornește. 5/5, aceeași sesiune. Orice `SELECTOR` cu `won=contentDesc`/`won=text` = id-ul de deasupra lipsește pe acest build WhatsApp; îl corectez în runda următoare cu `value=`.
