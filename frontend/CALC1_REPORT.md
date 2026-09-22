# CALC1_REPORT.md — Executorul de Calculator

## 1. ID-urile reale (Task 0, `com.oneplus.calculator`, verificate cu `uiautomator dump`, nu presupuse)

| Element | `resource-id` (`com.oneplus.calculator:id/...`) | Notă |
|---|---|---|
| Expresie (live) | `formula` | TextView, `text` citibil direct |
| Rezultat | `result` | TextView, `text` citibil direct — și valoarea finală după „=" |
| Cifre | `digit_0`…`digit_9` | `text` = cifra însăși |
| Virgulă | `dec_point` | |
| Adunare / scădere / înmulțire / împărțire | `op_add` / `op_sub` / `op_mul` / `op_div` | |
| Procent | `op_pct` | **existent, dar nefolosit** — vezi §2 |
| Șterge tot | `clr` | content-desc="löschen" (locale german pe acest dispozitiv) |
| Șterge ultimul | `del` | |
| Egal | `eq` | |
| Comutator științific | `item_science_calculator` | content-desc="Wissenschaftlich" |
| Rădăcină pătrată | `op_sqrt` | **doar în modul științific** |

**Descoperire critică, verificată pas cu pas (`uiautomator dump` după fiecare atingere):** `op_sqrt` e **prefix**, nu postfix. `9 → √ → =` produce „Ausdrucksfehler" (eroare de expresie). Secvența corectă e `√ → 9`, care calculează live „3" fără să mai fie nevoie de „=". Exemplul din specificația rundei (`["9", "√"]`) era greșit pentru acest calculator real — corectat înainte de a scrie orice cod.

**Legătura cu ACC-1:** confirmată — toate elementele de mai sus au `text`/`content-desc` citibile direct din arborele de accesibilitate, la fel ca testul ACC-1 (tasta 7). ACC-1 a dovedit o singură tastă și `ACTION_CLICK` generic; nu extind acea afirmație peste secvențe multi-pas, modul științific sau evaluarea expresiei — acelea sunt verificate separat, în această rundă, prin testele empirice de mai sus.

## 2. Parserul (Task 1, `lib/tools/toolRegistry.ts`)

**Acoperă:** adunare, scădere, înmulțire, împărțire, rădăcină pătrată. Numere 0-999 din cuvinte românești (`wordsToNumber` — nu exista niciun utilitar de acest fel în proiect, verificat înainte de a scrie unul; implementare minimă, așa cum a permis explicit runda).

**Rămâne nesuportat, explicit, fără presupuneri:** procentul (`op_pct` există nativ în `CalculatorRecipe.kt`, dar nicio expresie nu e recunoscută de parser către el — sintaxa lui reală pe acest calculator n-a fost verificată în această rundă) și orice altă operație (sinus, cosinus, tangentă, logaritm etc.) — acestea sunt recunoscute ca „e clar despre calculator" (`looksLikeCalculatorRequest`, gate mai larg) dar returnează explicit „operația asta nu e încă suportată", fără nicio apăsare.

## 3. Fișiere atinse, linii schimbate

**Fișiere noi (permise explicit în scope lock):**
- `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/CalculatorRecipe.kt` — 195 linii (172 inițial + 23 din cele două reparații de la §4bis)
- `lib/tools/toolRegistry.ts` — 143 linii
- `CALC1_REPORT.md` — acest fișier

**Fișiere în afara listei permise inițial, atinse DOAR cu aprobarea ta explicită, consemnate înainte de a le atinge (vezi §5):**
- `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt` — 6 linii (+5/-1): **o singură** schimbare de vizibilitate (`private` → `internal` pe `waitForNode`), fără nicio schimbare de comportament, plus comentariul care o explică.
- `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityModule.kt` — 30 linii, pur aditiv: un bloc nou `AsyncFunction("runCalculatorRecipe")`, oglindind exact tiparul lui `executeCommand`. Nicio linie existentă atinsă.
- `modules/benson-accessibility/index.d.ts` — 12 linii, aditiv: tipul `CalculatorRecipeOutcome` + semnătura `runCalculatorRecipe`.
- `modules/benson-accessibility/index.js` — 6 linii, aditiv: wrapper-ul JS care serializează simbolurile (`JSON.stringify`), oglindind exact tiparul lui `executeCommand`.
- `src/core/orchestrator/missionOrchestrator.ts` — 16 linii, aditiv: import + un bloc nou de recunoaștere, în aceeași formă și poziție cu `extractGenericMediaSearch`/`extractYouTubeQuery` (verificat înaintea `extractGoals`). Nicio linie existentă atinsă.

**Total cod nou net:** ~384 linii — peste ghidul general de 200 de linii din CLAUDE.md. Menționez asta explicit, nu ascund: patru task-uri (parser, execuție nativă, citire rezultat, rutare) plus trei extinderi reale de scop, fiecare aprobată separat, au cerut mai mult decât o singură schimbare mică.

## 4. Verificare

```
npx tsc --noEmit          → 0 erori
gradlew assembleRelease   → BUILD SUCCESSFUL
```

**Hash SHA-256 FINAL, instalat și testat pe dispozitiv (complet, 64 caractere):**
```
c8bb3525533f106a2a8cee13ce52b76e5085db0a818bf56f1889abb925445515
```
(hash-ul intermediar `352232bc...`, aprobat inițial pentru instalare, a fost înlocuit de două reparații găsite chiar în timpul testului de acceptare — vezi mai jos; fiecare rebuild+reinstall a fost verificat separat prin comparație de hash înainte de a continua testul.)

**Certificat:**
```
CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
SHA-256: fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da
```
Identic cu certificatul tuturor build-urilor anterioare din această sesiune (verificat prin comparație directă).

## 4bis. Două bug-uri reale, găsite ȘI reparate în timpul testului de acceptare pe dispozitiv

Testul de acceptare nu a trecut din prima încercare — exact genul de lucru pe care rularea pe dispozitiv trebuia să-l scoată la iveală, nu simularea:

1. **Cod confunda „buton negăsit" cu „click respins".** Prima încercare: `op_sqrt` era găsit instant de `waitForNode` (`foundAfterMs=3`), dar `node.performAction(ACTION_CLICK)` era respins de sistem — codul vechi raporta greșit „Button not found within 2000ms". Reparat: `pressById` distinge acum explicit `not_found` de `click_rejected`, cu o reîncercare scurtă (150ms) înainte de a ceda, și loghează `CALC_NODE_STATE symbol=... clickable=... enabled=...` pentru diagnostic.
2. **Cauza reală a respingerii:** `op_sqrt` exista în arborele de accesibilitate chiar și cât tastatura de BAZĂ era afișată (nu cea științifică) — `clickable=true enabled=false`, un nod dintr-un panou ascuns/colapsat. Verificarea „modul științific e deja activ" (`ensureScientificMode`) potrivea doar `resource-id`, nu și `isVisibleToUser` — concluziona greșit că modul științific era activ și nu mai apăsa comutatorul. Reparat: toate verificările de buton (`pressById`, ambele probe din `ensureScientificMode`) cer acum explicit `isVisibleToUser`.

Ambele confirmate cu captură reală de log (`CALC_NODE_STATE`, `CALC_STEP ... reason=click_rejected` înainte de reparație, `reason=ok` după), nu presupuse. Câte un build + instalare + retest per reparație, conform regulii „un bug per rundă".

## 5. Fișiere din afara listei permise — numite ÎNAINTE de a fi atinse, fiecare cu aprobarea ta explicită

Trei goluri reale de scop, descoperite pe rând în timpul investigației Task 0/arhitectură, fiecare raportat înainte de a scrie codul corespunzător:

1. `waitForNode` (în `BensonAccessibilityService.kt`) e `private` — nu era chemabilă literal dintr-un fișier nou. Aprobat: schimbare de vizibilitate, o linie, fără schimbare de comportament.
2. Niciun fișier existent nu expune `CalculatorRecipe` către JS. Aprobat: bloc nou, pur aditiv, în `BensonAccessibilityModule.kt`.
3. `lib/tools/toolRegistry.ts` (presupus de scope lock) nu exista, iar recunoașterea „e o cerere de calculator" trebuie să se întâmple acolo unde se scrie efectiv `decision=command` — `src/core/orchestrator/missionOrchestrator.ts`. Aprobat: bloc nou, pur aditiv, aceeași formă cu recunoașterile deja existente pentru YouTube/media.

**Niciun alt fișier din lista interzisă** (`android/**`, `plugins/**`, `whisper-models/**`, `porcupine-model/**`, `whatsappTool.ts`, `missionValidator.ts`, `missionExecutor.ts`, `app/index.tsx`, `benson-audio-capture/**`) **nu a fost atins sau avut nevoie de a fi atins.**

---

# CANON LOG — runda: CALC1 (executorul de Calculator) — data: 2026-09-22

```
[x] 0.1  Scope lock citit și înțeles — permise: modules/benson-accessibility/**/CalculatorRecipe.kt
         (nou), lib/tools/toolRegistry.ts (doar înregistrare/parser, dacă există), CALC1_REPORT.md
[x] 0.2  Fișiere interzise citite — niciunul atins; trei fișiere ÎN AFARA scope lock-ului inițial
         (nu pe lista interzisă) au fost numite explicit ÎNAINTE de a fi atinse, fiecare cu
         aprobarea ta — vezi §5 din raport
[x] 0.3  Un singur tip de schimbare declarat: implementarea executorului de Calculator (parser +
         execuție nativă + citire rezultat + rutare) — o singură funcționalitate nouă, capăt la capăt
[~] 1.1  Harness de regresie RULAT înainte de schimbare — H1 există, dar NU e 6/6 stabil (vezi
         H1_REPORT.md); nu l-am rulat ca poartă pentru această rundă, per starea lui onestă
[~] 1.2  Harness RULAT după schimbare — NEEXECUTAT în această rundă — testul de acceptare de mai
         jos rămâne manual, pe dispozitiv, condiționat de aprobarea ta de instalare
[ ] 1.3  Compară BASELINE cu FINALA — NEAPLICABIL, harness-ul nu e poartă automată încă (canon
         Secțiunea 1, stare „NEEXISTENT INCA"/parțial, vezi H1_REPORT.md)
[x] 2.1  npx tsc --noEmit → 0 erori — coadă lipită mai sus
[x] 2.2  gradlew assembleRelease → BUILD SUCCESSFUL — coadă lipită mai sus
[x] 2.3  Certificat verificat explicit: CN=BENSON, O=TOKKO — comandă și rezultat, lipite mai sus
[x] 2.4  SHA-256 complet, calculat aici, 64 caractere — lipit mai sus
[x] 3.1  Fiecare fișier atins enumerat, cu linii schimbate — §3
[x] 3.2  Ieșire din scope lock: DA, de trei ori, fiecare aprobată explicit înainte de a atinge
         fișierul — vezi §5 (BOLD, prima observație relevantă)
[x] 3.3  setTimeout/setInterval noi: NICIUNUL — toate așteptările din CalculatorRecipe.kt folosesc
         `delay()` pe coroutine nativă (kotlinx.coroutines), pe modelul deja dovedit al lui
         waitForNode; nimic pe fir JS
[x] 4.1  Testul de acceptare — RULAT pe dispozitiv, build final c8bb3525..., 9/9 (3 scenarii ×
         3 încercări), toate PASS. Rezultatul rostit s-a potrivit cu afișajul real de fiecare
         dată (confirmat vizual prin captură de ecran pentru „√9 = 3"); zero
         CALC_STEP_FAILED pe drumul reușit; operația nesuportată (sinus) a produs zero apăsări
         de fiecare din cele 3 ori — vezi tabelul de mai jos
[x] 4.2  Tag propus: DEVICE_PASS_CALC1_CALCULATOR_EXECUTOR_2026-09-22 — tu confirmi, eu nu
         tag-uiesc singur
```

### Rezultatele testului de acceptare (9/9 PASS)

| Scenariu | Încercare 1 | Încercare 2 | Încercare 3 |
|---|---|---|---|
| „…scoate rădăcina din 9" | PASS — afișaj „√9"→„3", rostit „Rădăcina din 9 este 3." | PASS — identic | PASS — identic |
| „Cât fac cinci plus trei?" | PASS — afișaj „8", rostit „5 plus 3 este 8." | PASS — identic | PASS — identic |
| „Calculează sinusul lui 30" | PASS — zero apăsări, rostit „Operația asta nu e încă suportată de Calculator." | PASS — identic | PASS — identic |

Procentul rămâne **explicit NEIMPLEMENTAT** — confirmat, nicio schimbare față de §2.

**Regulă tare respectată:** runda se declară încheiată abia acum, cu dovada reală de pe dispozitiv, nu la compilare. Codul a eșuat de două ori la prima rulare reală — ambele cauze găsite, reparate, reconfirmate cu build nou de fiecare dată, niciodată presupuse.
