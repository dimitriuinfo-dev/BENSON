# Raport — BENSON vede toate aplicațiile instalate

Data: 2026-08-28. Include în același build modificările de audio focus din runda precedentă.

---

## 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | vezi mai jos |

**Fișiere modificate (lock respectat):**

| Fișier | Stare | Note |
|---|---|---|
| `lib/appIndex.ts` | **nou** | sursa primară: lista reală de aplicații + potrivire fuzzy |
| `src/executors/appLauncherExecutor.ts` | modificat | OPEN_APP / CLOSE_APP / radio / muzică prin index |
| `lib/agents/appLauncherAgent.ts` | modificat | ⚠️ vezi nota de path mai jos |
| `APP_INDEX_REPORT.md` | nou | acest fișier |

> **⚠️ Corecție de path în lock:** ai scris `src/agents/appLauncherAgent.ts`. Acel fișier nu
> există — fișierul din auditul precedent (și singurul „App Launcher Agent") este
> **`lib/agents/appLauncherAgent.ts`**. Am modificat acest fișier, presupunând că e o eroare de
> tastare. Dacă nu era, spune și îl revert.

Neatins: `plugins/**`, `modules/**`, `android/**`, manifest, restul listei protejate. `git` neatins.

---

## 1. `getInstalledApps()` ca sursă primară; registrul curat = doar alias-uri

### `lib/appIndex.ts` (nou)

- `loadAppIndex(force?)` — apelează `benson-app-registry.getInstalledApps()`
  (`queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` — scutit de vizibilitatea Android 11+),
  normalizează la `{packageName, appName}`, **cache în memorie**. Loghează **`APP_INDEX count=N`**.
- `refreshAppIndex()` — golește cache-ul (de apelat la instalare/dezinstalare de pachete; hook-ul
  în sine ar fi în `app/index.tsx`, în afara lock-ului — vezi §6).
- Warm-up la pornire: `setTimeout(() => loadAppIndex(), 3000)` la încărcarea modulului (modulul e
  tras prin lanțul de import al App Launcher-ului, încărcat la boot). Așa `APP_INDEX count=` apare
  în log fără să aștepți prima comandă „deschide X".

### `src/executors/appLauncherExecutor.ts`

- **OPEN_APP** (intenția deterministă): ordine nouă —
  1. telefon / SMS (neschimbat);
  2. `isRadioRequest` → `resolveAndLaunchRadio` (vezi §4);
  3. `isMusicRequest` → `resolveAndLaunchMusic` (vezi §4);
  4. **alias curat**: `findAppRegistryEntry(appName)` — dacă are `packageName` **și** e instalat
     → deschide direct (păstrează „harta"→Maps, „oaza"→Waze);
  5. **index**: `resolveAppQuery(appName)` → `openResultFromMatch(...)`.
  Registrul curat nu mai e listă închisă — un miss trece la index, nu la „nu găsesc".
- **CLOSE_APP**: `resolveCloseTarget` = alias curat (exact) → altfel `resolveAppQuery`. Rezultatul
  e un `AppMatch`, deci `closeApp` **propune** în loc să spună că nu găsește.
- `findInstalledAppByNameHint` (prima potrivire) → `findInstalledAppsByNameHint` (toate, prin index).
- `getInstalledApps` nu mai e importat direct aici — totul trece prin `loadAppIndex()` (cache).

### `lib/agents/appLauncherAgent.ts` (calea prin orchestrator + Claude tools)

- Ramura `OPEN_PATTERN`:
  1. **alias curat** `findAppByName` — doar dacă e aprobat → calea cu scheme/deep-link (neschimbată);
  2. **categorie** `matchCategory` → `proposeForCategory` (propune, nu alege — §4);
  3. **index** `resolveOpenViaIndex(phrase)` — sursa primară.
- Eliminat: `findAllowedDynamicApp` (subsumat de index), `suggestForCategory` (înlocuit de
  `proposeForCategory`), și **redirecția tăcută către Play Store** cu „I don't have X set up yet".

---

## 2. Potrivire — insensibilă la majuscule ȘI diacritice, pe appName ȘI packageName, pe token-uri

`lib/appIndex.ts`:

- `normalizeName(s)` = `NFD` → strip diacritice → lowercase → orice non-alfanumeric devine spațiu.
  „YouTube", „you tube", „Youtube", „YOU-TUBE" → toate `["you","tube"]` / joined `"youtube"`.
- `scoreApp()` compară query-ul (tokenizat) cu:
  - **numele** (token-set egal, joined egal → „you tube" == „YouTube"; prefix; substring; toate
    token-urile query prezente ca token-uri întregi în nume; substring parțial per token);
  - **packageName** (`com.google.android.youtube` → token-uri `["com","google","android","youtube"]`;
    ultimul segment = „youtube" → potrivire exactă sau substring).
- `matchApps()` → `{kind}`:
  - `exact` (scor ≥ 90, ecart ≥ 6 față de #2) → deschide direct;
  - `single` (scor ≥ 50, ecart ≥ 14) → propune „O deschid?";
  - `multiple` (shortlist ≤ 3) → „Pe care?";
  - `none`.

---

## 3. Nicio aplicație negăsită fără propunere — „nu găsesc aplicația" eliminat complet

| Situație | Răspuns |
|---|---|
| un candidat apropiat | „Am găsit **Magic FM**. O deschid?" (`needsDisambiguationResult` / reply) |
| mai mulți | „Am găsit mai multe: **A, B, C**. Pe care s-o deschid?" (max 3) |
| zero | „Nu am nicio aplicație instalată care să semene cu **<X>**." |

Șiruri șterse / înlocuite:
- `appLauncherExecutor.ts`: `Nu găsesc aplicația ${name} pe telefon.` (×2) și
  `Nu găsesc ${label} pe telefon ca să o închid.` → formulările de mai sus.
  `${name} nu este instalată pe telefon.` rămâne doar ca gardă internă în `launchAllowlisted`
  (când un packageName rezolvat dintr-un alias nu mai e instalat).
- `appLauncherAgent.ts`: `I don't have ${phrase} set up yet … Searching the Play Store instead.`
  → șters complet (fără redirecție Play Store).

Grep `"nu găsesc aplicați"` / `"I don't have"` / `"not set up yet"` în ambele fișiere → **0**.

---

## 4. Categorie vs. nume

- **Nume concret** (`exact` din index, sau stație radio numită, sau „Spotify") → **deschide direct**.
- **Categorie** („radio", „muzică", frază de categorie) → **propune, nu alege singur**:
  - radio: `resolveRadioTarget` întoarce `exact` (nume concret → deschide) vs `found` (un singur
    candidat de categorie → „O deschid?") vs `ambiguous` (listă) vs `none`;
  - muzică: `resolveAndLaunchMusic` — la fel, prin `resolveAppQuery` pentru nume concret și
    `categoryResult` pentru cuvântul gol „muzică".
- **Preferința ținută minte:** în `appLauncherAgent.ts`, `proposeForCategory` verifică
  `loadLastUsedByCategory()` — dacă există deja o alegere pentru categoria respectivă, o
  **deschide direct** (o tratează ca fapt stabilit) și reînnoiește `recordAppUsed`. Fără preferință
  memorată → propune.
  **Limitare (din cauza lock-ului):** pe calea deterministă din `appLauncherExecutor.ts`,
  răspunsul „da" la o propunere se rezolvă în `missionOrchestrator`/`missionExecutor` (fișiere
  protejate), deci scrierea preferinței „la confirmarea alegerii" nu se poate face acolo în
  această rundă. Pe calea Claude-tools, Claude poate relua propunerea singur.

---

## 5. Loguri

- **`APP_INDEX count=N`** — o dată, la warm-up (~3s după pornire) sau la prima folosire.
  `APP_INDEX count=0 error=…` dacă `getInstalledApps()` eșuează.
- **`APP_MATCH intent=<open|close|radio|music|category:X> query="…" candidates=N chosen="A|B|C" asked=true|false`**
  — la fiecare rezolvare de nume/categorie.

---

## 6. Ce a rămas în afara lock-ului (pentru o rundă viitoare, dacă vrei)

- **Reîmprospătare la instalare/dezinstalare de pachete:** `refreshAppIndex()` există, dar
  `BroadcastReceiver`-ul `ACTION_PACKAGE_ADDED/REMOVED` (nativ, `modules/`) sau un hook la
  `AppState`/focus (`app/index.tsx`) sunt în afara lock-ului. Acum cache-ul se golește doar la
  repornirea procesului.
- **Cost `getInstalledApps()`:** modulul nativ re-encodează base64 iconițele **tuturor**
  aplicațiilor la fiecare apel. Cache-ul din `appIndex.ts` reduce asta la ~1/sesiune, dar o
  variantă nativă „listă fără iconițe" ar fi mai ieftină (`modules/`, în afara lock-ului).
- **Gate de aprobare la „deschide X":** calea prin index deschide orice aplicație instalată, fără
  să treacă prin allowlist-ul App Permissions (consecventă cu `appLauncherExecutor.ts`, care nu a
  verificat niciodată aprobarea la open). Dacă vrei ca „deschide X" să respecte allowlist-ul,
  spune — e o decizie de guvernare, nu am presupus-o.
