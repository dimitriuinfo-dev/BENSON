# B-fix2 — potrivire fonetică pe rezultatele din ecran

**Notă de scope:** `lib/tools/whatsappTool.ts` din permisiuni NU există — fișierul real e `src/core/mission/tools/whatsappTool.ts` (același pe care îl viza runda). Editat doar el + acest raport. `missionValidator.ts` și `missionExecutor.ts` (în același director `src/core/mission/`) — **neatinse**. `lib/appIndex.ts` — **neatins** (nu e în scope): `consonantSkeleton` + `boundedLevenshtein` de acolo NU sunt exportate, așa că le-am copiat verbatim în `whatsappTool.ts`. Fără `git`, `expo prebuild`, `setx`.

---

## 1. Cauza, din log

```
CONTACT_MATCH strategy=native_fallback query="HANA" matched="" candidates=0
No node matched {"textContains":"HANA"} within 3000ms.
```

Două defecte în lanț:

1. **`candidates=0`** — `readWhatsAppResultNames()` citea numele contactului DOAR din `node.text`. Pe acest build WhatsApp, numele rândului de rezultat trăiește pe `contentDescription`-ul containerului („Hannah, 2 unread messages"), nu pe un nod `text` — deci lista de candidați venea goală și `resolveResultPick` cădea pe `strategy=native_fallback`.
2. **`textContains:"HANA"` exact** — cu lista goală, `rowText = query` = „HANA", iar `executeCommand` face potrivire literală substring: „HANA" nu e în „HANNAH", deci `No node matched`. Vechea cascadă `matchCandidates` (exact → prefix → `phoneticKey`) nici nu apuca să ruleze fără candidați.

---

## 2. Fixul (`src/core/mission/tools/whatsappTool.ts`, ~+95 linii)

### 2a. Citirea rândurilor — `readWhatsAppResultNames()`
Acum culege numele din **`text` ȘI din `contentDescription`** (primul segment, tăiat la `, · | • \n`), cu aceleași filtre (ne-editabil, nu e search UI, nu e antet de secțiune, ≤ 40 caractere). Asta rezolvă `candidates=0`.

### 2b. Potrivire fonetică — algoritmul din `lib/appIndex.ts`
Copiate verbatim: `consonantSkeleton(s)` = `s.replace(/[aeiou]/g,'')` și `boundedLevenshtein(a,b,max)` (Levenshtein cu oprire timpurie). Plus:
- `normNameLoose(s)` — fără diacritice, minuscule, doar `[a-z0-9]`.
- `skelKey(s)` = `consonantSkeleton(normNameLoose(s) fără 'h')` — „h" e consoană slabă în RO/DE/EN, așa că „HANA" → skeleton **„n"**, „HANNAH" → **„nn"** → `boundedLevenshtein("n","nn",4) = 1`.
- `phoneticContactScore(query, cand)` — `1000` la egalitate exactă; altfel un amestec: `+450` dacă scheletele sunt egale, `+max(0, 380 − dSkel·150)`, `+max(0, 200 − dFull·45)`, `+260` bonus dacă rândul începe cu interogarea (prenume → „Prenume Nume"). Pentru „HANA"/„HANNAH": `dSkel=1`, `dFull=2` → **scor 340**.
- `pickBestPhonetic(query, candidates)` — scorează **toate** rândurile, întoarce scorul maxim; `>` strict → **la egalitate câștigă primul rând**. Prag de acceptare `CONTACT_SCORE_FLOOR = 260` (sub prag → nicio potrivire, oprire onestă). „HANA" vs un „MARIA" izolat → scor ~145 → respins.

### 2c. `resolveResultPick()` rescris
Nu mai cheamă `matchCandidates` / nu mai întreabă „pe care?" — regula e „scorul cel mai mare câștigă". Log nou, exact în formatul cerut:
```
CONTACT_MATCH query="HANA" candidates=3 matched="HANNAH" score=340
```

### 2d. Pasul 4/5 din `runCallRecipe`
- Fereastra de poll lărgită: `while (Date.now()-t4 < 8000)`, cap per-snapshot `cappedSnapshot(900)` (numele se populează prin `TYPE_WINDOW_CONTENT_CHANGED`, poate întârzia o clipă).
- Pasul 5 apasă pe **numele real de pe ecran** (`pick.clickText` din potrivirea fonetică, ex. „HANNAH"), nu pe interogarea brută — deci `textContains` literal din `executeCommand` chiar prinde rândul.
- Dacă după 8s niciun rând nu trece pragul → `stopNotFound("contactul \"…\" în rezultatele căutării")` — mesaj care numește pasul, **zero apăsare oarbă**.

Funcțiile vechi `matchCandidates` / `buildChoiceQuestion` / `phoneticKey` rămân în fișier (cod mort acum), neșterse ca să nu lărgesc diff-ul — nu mai sunt pe nicio cale activă.

---

## 3. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 1m 10s** (67 executed / 878 up-to-date; schimbare doar JS) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 881 104 B (~248,8 MiB)** |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · APK Signature Scheme v2 = verified |
| Instalare | `9c1464eb` (CPH2663 / OnePlus Nord 4) · `adb install -r -d` → **Success** · `lastUpdateTime=2026-09-07 17:18:06` · `firstInstallTime` neschimbat → date păstrate |

> Acest APK conține și schimbările din Runda B (nu au fost încă raportate separat — `ROUND_B_REPORT.md` nu e în scope-ul rundei curente): `accessibility_service_config.xml` abonat la `typeWindowContentChanged`; `captureSnapshot()` mutat off-`Dispatchers.Main` cu plafon dur de 1200ms; `USE_LEGACY_CALL_RECIPE = false` (calea activă e acum `runCallRecipe`, cu `waitForNode` + `RECIPE_STEP` + `stopNotFound`, fără `wait ms` fix pe calea de execuție); `waitForNode` nativ cu semnătura B2 + log `WAIT_NODE`; prefix de 3 caractere la căutare; `cappedSnapshot()` (race snapshot vs plafon). B-fix2 se sprijină pe ele.

---

## 4. Constante de revert

| Constantă | Fișier | Revert |
|---|---|---|
| `CONTACT_SCORE_FLOOR` | `whatsappTool.ts` | mai mare = mai strict (mai puține potriviri fonetice); `9999` = practic doar potrivire exactă |
| `USE_LEGACY_CALL_RECIPE` | `whatsappTool.ts` | `true` → revine rețeta veche `runTwoPhase({kind:'call'})` cu `wait ms` fix (B-fix2 n-o mai atinge) |

Pentru a reveni la vechea potrivire fără a atinge rețeta: în `resolveResultPick`, înlocuiește `pickBestPhonetic(query, candidates)` cu `matchCandidates(query, candidates)` (încă în fișier).

---

## 5. Acceptare pe dispozitiv — de rulat de tine

„Sună-o pe Hannah pe WhatsApp" → în log:
```
CONTACT_MATCH query="…" candidates=N matched="HANNAH" score=<≥260>
RECIPE_STEP index=5 ... found=true
```
→ conversația cu HANNAH se deschide → butonul de apel vocal → **apelul pornește**. De trei ori la rând, aceeași sesiune.

Dacă apare `candidates=0` chiar și acum, cauza e că snapshot-ul nativ tot nu conține rândurile — asta ține de `modules/benson-accessibility/**`, interzis în această rundă; raportează logul `SNAPSHOT nodes=…` corespunzător.
