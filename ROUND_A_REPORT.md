# RUNDA A — raport

29.08.2026. Lock: `lib/appIndex.ts` · `src/executors/appLauncherExecutor.ts` ·
`lib/agents/appLauncherAgent.ts` · `lib/engines/registry.ts` · `lib/engines/llm/**` · acest raport.
Fără `git`, fără `expo prebuild`, fără `setx`. `android/**`, `plugins/**`, `modules/**` neatinse.

---

## 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | vezi mai jos |

---

## TASK A1 — indexul întoarce zero pentru „youtube"

### Ce arată logul de pe dispozitiv (sesiunea pid 17142)

```
11:53:38  APP_INDEX  count=49
11:54:52  APP_MATCH  intent=open  query="youtube"  candidates=0  chosen=""  asked=false
11:58:47  APP_MATCH  intent=music query="Spotify"  candidates=0  chosen=""  asked=false
```

`APP_INDEX count=49`. Indexul **s-a încărcat** — nu e 0, nu lipsește. `getInstalledApps()` a
răspuns cu 49 de intrări.

### Dar telefonul are 277 de aplicații cu iconiță

```
adb shell cmd package query-activities --brief -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER  →  277
adb shell pm list packages | grep -E "youtube|spotify"  →
    com.google.android.youtube   com.spotify.music   (ambele instalate)
```

`getInstalledApps()` vede **49 din 277**. YouTube și Spotify sunt printre cele 228 pe care nu le vede.

### Cauza — și o corecție a auditului meu anterior

`adb shell dumpsys package com.benson.butler` →

```
queriesIntents = [ VIEW+BROWSABLE+https, SEND ×3, EDIT, INSERT, OPEN_DOCUMENT_TREE,
                   TTS_SERVICE, CustomTabsService, RecognitionService ]
system apps queryable: false
```

**Nu există `<intent> MAIN + LAUNCHER` în `<queries>`.** `queryIntentActivities(MAIN+LAUNCHER)`
întoarce, fără el, doar submulțimea auto-vizibilă (49): pachetul propriu + cele cu care aplicația
a interacționat explicit + câteva de sistem.

**Aici am greșit în auditul precedent.** Am scris „perechea MAIN/LAUNCHER e scutită de
vizibilitatea Android 11+, nu e nevoie de `<queries>`" — bazându-mă pe comentariul (greșit) din
`BensonAppRegistryModule.kt`. **Nu e scutită.** Exact blocul pe care planul tău anterior îl
propunea:

```xml
<queries>
  <intent>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
  </intent>
</queries>
```

…**este** necesar. Fără el, indexul rămâne plafonat la ~49, indiferent ce fac în JS.

### Ce depășește lock-ul Rundei A

Blocul `<queries>` trebuie să ajungă în `AndroidManifest.xml` printr-un **config plugin nou**
(`plugins/withLauncherQueries.js`) ca să supraviețuiască `prebuild`. Asta e `plugins/**` +
`android/**` — **interzise în lock-ul Rundei A.** `getInstalledApps()` nu întoarce 0 (întoarce 49),
deci nu e strict „nativ", dar cauza e la nivel de manifest.

**Îți cer să deschizi lock-ul pentru:** `plugins/withLauncherQueries.js` (nou) + înregistrarea lui
în `app.json`. E o singură intrare `<queries><intent>`, **NU** `QUERY_ALL_PACKAGES`. Restul e deja gata.

### Ce am făcut totuși în lock (`lib/appIndex.ts`)

1. **Garanție „niciodată pe index gol"** (A1.3): `resolveAppQuery` face `await loadAppIndex()`, iar
   dacă lista vine goală forțează **o** reîncărcare sincronă înainte de a răspunde. Warm-up-ul la
   3 s rămâne, dar orice comandă timpurie oricum așteaptă încărcarea (e `await`-uită).
2. **Log cerut**: `APP_INDEX count=… source=warm|lazy|cache elapsedMs=…`.
3. **Diagnostic la miss**: linia `APP_MATCH` primește, când `candidates=0`,
   `indexCount=… rawContains=…` — `rawContains` = câte nume/pachete din index conțin efectiv textul
   căutat. La următorul test, `query="youtube" … indexCount=49 rawContains=0` = confirmă că YouTube
   nu e în lista vizibilă (nu e un bug de scor). Odată pluginul pus, `rawContains` va fi ≥ 1.

Cele trei ipoteze din brief:
- „potrivire înainte de încărcare" — **infirmat** (log: `count=49` era prezent la momentul potrivirii);
- „`getInstalledApps()` aruncă, excepția înghițită" — **infirmat** (`count=49`, fără `error=`);
- „modulul nativ întoarce listă goală" — **infirmat** (întoarce 49, plafonat de vizibilitate).

---

## TASK A2 — creierul

### Ce arată logul

```
LLM_MODELS count=14 list="…openai/gpt-oss-20b,…openai/gpt-oss-120b,…qwen/qwen3.8-27b,…"
LLM_MODELS chosen=openai/gpt-oss-20b
LLM_ENDPOINT model=openai/gpt-oss-20b
BRAIN_INTENT raw="…" kind=speak|clarify|action   ← răspunde
```

- **Descoperirea modelului reușește.** 14 modele, alege unul, apelul merge. **Niciun `LLM_ERROR`**
  în tot logul. Fix-ul de acum două runde (descoperire în locul lui `llama-3.1-8b-instant`, care
  dădea 404) ține.
- **Deja o singură dată per sesiune**: `LLM_MODELS` apare o dată per pornire (per pid), apoi doar
  `LLM_ENDPOINT`-uri. Cache-ul (`discovered` la nivel de modul) funcționează.
- „Nu am putut procesa asta acum" pe care l-ai auzit = `conversationFallbackLine`
  (`lib/agents/fallbackLine.ts`), rostit de `orchestrator.ts` **când nu e cheie Anthropic** și
  calea brain nu produce un răspuns direct. Ambele fișiere sunt **în afara lock-ului Rundei A**.
  Din log, creierul răspunde `kind=clarify` la intrări STT degradate („are venit online", „Bensan,
  duuma laică", „Dezică de Magic FM") — problema reală acolo e **STT-ul**, nu creierul. Câteva
  `clarify` fără întrebare bună pot cădea pe linia generică; asta se leagă în `orchestrator.ts`,
  altă rundă.

### Ce am schimbat în lock

**`lib/engines/registry.ts`:**
- Ordinea de preferință: `openai/gpt-oss-120b` **înaintea** lui `20b` (ambele existau pe cont; 120b
  e creier de majordom mai bun). Adăugate `qwen/qwen3.8-27b`, `qwen/qwen3.6-27b` (din lista reală).
  Scoase din listă id-urile llama-3.x absente ca priorități de top. La următorul build,
  `LLM_MODELS chosen=` ar trebui să fie `openai/gpt-oss-120b`.
- Descoperirea întoarce acum o **cauză**: `key` (HTTP 401/403), `network` (excepție/timeout),
  `model` (a ajuns la Groq dar niciun model de chat utilizabil), `ok`. Logat:
  `LLM_MODELS error=key http=401` / `error=network detail=…` / `error=model count=…`.
- Eșecul e **memorat** (cu tot cu cauză) — o cheie/rețea stricată nu mai declanșează un GET
  `/models` la fiecare rostire, ci o dată.
- Export nou `lastLlmDiscoveryCause()` — hook pentru stratul de conversație (`brainRouter.ts`, în
  afara lock-ului) să formuleze după cauză. Neconsumat încă.

**`lib/engines/llm/openAiCompatibleBrain.ts`:**
- `errorMessage()` nu mai spune „(HTTP 401)" generic. După cod:
  - fără răspuns HTTP → „pare o problemă de conexiune la internet";
  - 401/403 → „Cheia Groq pare invalidă sau fără acces — verific-o în Setări";
  - 404 → „Modelul de conversație nu e disponibil pe acest cont Groq";
  - 429 → „Groq e limitat temporar — încearcă din nou în câteva minute".

---

## Acceptare — stare

| Criteriu | Stare |
|---|---|
| „Deschide YouTube" → se deschide | ❌ **blocat de manifest** — YouTube nu e în cele 49 vizibile. Se rezolvă cu `plugins/withLauncherQueries.js` (cerere de lock mai sus). Diagnosticul `rawContains=0` o va confirma. |
| „Ce e nou azi?" → răspuns real, rostit | ✅ creierul răspunde (`BRAIN_INTENT kind=speak`), fără `LLM_ERROR`. Calitatea urcă cu `gpt-oss-120b`. Intrarea STT degradată e o problemă separată. |

---

## Ce-ți cer, pe scurt

Deschide lock-ul pentru **`plugins/withLauncherQueries.js` (nou) + `app.json`** — o singură intrare
`<queries><intent>MAIN+LAUNCHER</intent></queries>`, fără `QUERY_ALL_PACKAGES`. Cu ea, „deschide
orice aplicație instalată" funcționează (restul e deja construit). Fără ea, plafonul de 49 rămâne.
