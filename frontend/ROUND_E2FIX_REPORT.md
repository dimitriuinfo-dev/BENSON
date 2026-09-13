# RUNDA E2-fix — raport de execuție

**IEȘIRE DIN SCOPE LOCK: Fix 1 (self-echo) nu poate fi făcut cu fișierele permise. Filtrul `looksLikeSelfEcho` + constanta `ECHO_WINDOW_MS` trăiesc în `app/index.tsx`, care NU e în lista permisă a acestei runde (`lib/agents/voiceAgent.ts` · `lib/appIndex.ts` · `src/executors/appLauncherExecutor.ts` · `ROUND_E2FIX_REPORT.md`). Fix 2 e livrat integral.**

Motiv: protocolul rundei, punctul 2 — „Dacă o sarcină cere un fișier interzis: oprește-te și raportează, nu improviza." O a doua copie a filtrului într-un fișier permis n-ar rezolva problema: filtrul agresiv din `app/index.tsx` ar rula în continuare, neschimbat.

Fără `git`, `expo prebuild`, `setx`.

---

## 1. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 1m** (89 executed / 856 up-to-date; schimbare doar JS — bundle RN refăcut, fără recompilare Kotlin) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 876 700 B (~248,8 MiB)** |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · schema v2 verificată |
| Instalare | `9c1464eb` (CPH2663) · `adb install -r -d` → **Success** · `lastUpdateTime=2026-09-07 11:51:09` (era 11:29:16 = E2) · `firstInstallTime` neschimbat → date păstrate |

---

## 2. Fix 2 — numele aplicațiilor în index (LIVRAT)

Constatarea din E2: `chosen="X"` (adică `kind=none`) la „FlexParken". Cauza — verificată în `lib/appIndex.ts`: `scoreApp()` are DOAR reguli exacte / substring / `startsWith`, zero toleranță la greșeli. „Flexparkon" vs „FlexParken" diferă printr-o singură vocală (`o`↔`e`):
- `nJoined("flexparken") === qJoined("flexparkon")` → fals
- `nJoined.startsWith(qJoined)` / invers → fals
- `nJoined.includes(qJoined)` / invers → fals
- `partial`: `"flexparken".includes("flexparkon")` / invers → fals

→ scor **0** → `kind: 'none'` → „Nu am nicio aplicație care să semene cu Flexparkon." Numele din PackageManager (`FlexParken`) e corect în index — problema e că un slip de vocală din STT rupe complet potrivirea.

Numele afișat **și** pachetul erau deja salvate amândouă (`IndexedApp = { packageName, appName }`) și `scoreApp` compara deja pe amândouă — ce lipsea era potrivirea **fonetică**.

### `lib/appIndex.ts` (~+55 linii)
- Constantă de revert `PHONETIC_MATCH = true`.
- `consonantSkeleton(s)` — scoate vocalele: `"flexparken" → "flxprkn"`, `"flexparkon" → "flxprkn"` (egale). Prinde exact slip-ul de vocală.
- `boundedLevenshtein(a, b, max)` — distanță de editare cu oprire timpurie (ieftină; nu evaluează perechi evident diferite). Prinde un slip de consoană / literă lipsă.
- În `scoreApp()`, după regulile existente, doar pentru `qJoined.length >= 5` (o interogare de 2-3 litere nu poate potrivi fonetic orice):
  - schelet de consoane egal cu numele afișat → **scor 82**; cu ultimul segment de pachet → **70**.
  - `boundedLevenshtein` ≤ 1 (nume scurte) / ≤ 2 (≥ 9 litere): distanță 1 → **80** (nume) / **68** (pachet); distanță 2 → **66** / **56**.
- `matchApps()` neschimbat: scor 82 → `kind: 'single'` (nu `exact`, care cere ≥ 90) → BENSON **confirmă**: „Am găsit FlexParken. O deschid?" — nu deschide singur o potrivire aproximativă.
- `resolveAppQuery()`: logul `APP_MATCH` acum include `matched=` (numele afișat rezolvat) și `package=`:
  `APP_MATCH intent=open query="Flexparkon" matched="FlexParken" package="…" candidates=1 chosen="FlexParken" asked=true`

### `src/executors/appLauncherExecutor.ts` — neatins
Nu a fost nevoie: `OPEN_APP` / radio / muzică trec toate prin `matchApps()` / `resolveAppQuery()` din `appIndex.ts`, deci potrivirea fonetică + logul îmbunătățit se aplică automat pe toate căile. (Fișierul era permis, dar o modificare acolo ar fi fost redundantă.)

### `lib/agents/voiceAgent.ts` — neatins
Permis, dar Fix 2 nu-l atinge, iar Fix 1 nu poate fi făcut util acolo (vezi §3).

**Fals-pozitive:** riscul e mărginit — potrivirea fonetică dă maxim scor 82 (< 90), deci niciodată auto-deschidere; cel mai rău caz e „Am găsit X. O deschid?" pentru o potrivire ușor greșită (recuperabil, nu o acțiune tăcută greșită). Numele scurte (`Waze`, `Maps`, `Gmail` fără cele 5 litere) sunt exceptate de la potrivirea fonetică.

---

## 3. Fix 1 — self-echo prea agresiv (BLOCAT — necesită `app/index.tsx`)

### Diagnostic (verificat în cod)

Filtrul e în `app/index.tsx`:
- `lastSpokenRef` — populat de `rememberSpoken()`, apelat din `speak()` și `speakText()`.
- `looksLikeSelfEcho(transcript)` — apelat în `resultSub` (linia ~873) și în ramura de partial-fallback din `endSub` (~916). Loghează `TRANSCRIPT_ACCEPTED … REJECTED_self_echo=true`.
- `ECHO_WINDOW_MS = 120000` (**2 minute**).

**De ce „Benson deschide YouTube" e respins:**
1. Runda E1-5 a introdus ACK-ul scurt: la orice `OPEN_APP`, `onMissionAck` cheamă `speak("Deschid.")` → `rememberSpoken("Deschid.")` → `lastSpokenRef` conține `normalized = "deschid"`, valabil **120s**.
2. Utilizatorul spune, câteva secunde mai târziu, „Benson deschide YouTube" → `normalizeForEcho` → `"deschide youtube"`.
3. În `looksLikeSelfEcho`: `norm.includes(last.normalized)` → `"deschide youtube".includes("deschid")` → **TRUE** (prefix substring: „deschide" conține „deschid").
4. → `REJECTED_self_echo=true`, comanda reală e mâncată.

Fereastra de 120s + verificarea `includes` pe substring transformă orice ACK / răspuns scurt al lui BENSON într-un blocaj de 2 minute pentru comenzi care încep cu aceleași cuvinte.

### Ce ar rezolva (o schimbare de ~4 linii, toată în `app/index.tsx`)

```ts
// app/index.tsx
// (1) fereastra scade de la 120000 la 3000 — dacă BENSON n-a rostit nimic în ultimele 3s,
//     filtrul nu se aplică deloc:
const ECHO_WINDOW_MS = 3000; // was 120000 (E2-fix / Fix 1)

// (2) în looksLikeSelfEcho, înainte de return, un log dedicat:
//     logAudioDiag('SELF_ECHO_CHECK', `spoken="${last.normalized}" heard="${norm}" similarity=${sim.toFixed(2)} decision=${hit ? 'reject' : 'accept'}`);
```

`SELF_ECHO_FINGERPRINTS` (fragmentele hardcodate „did not catch") rămân — sunt independente de fereastră și țintesc o buclă reală, confirmată live; nu afectează „deschide YouTube".

### De ce nu s-a făcut într-un fișier permis
- `ECHO_WINDOW_MS` și `looksLikeSelfEcho` sunt locale în componenta din `app/index.tsx`. `voiceAgent.ts` nu are acces la ele și nu are un lever care să le neutralizeze.
- Un filtru nou în `voiceAgent.ts` ar rula **pe lângă** cel existent (ambele trebuie trecute), deci n-ar face comenzile respinse să treacă — filtrul agresiv din `app/index.tsx` rămâne.
- `voiceAgent.ts` vede doar TTS-ul de dispozitiv (prin `speakNow`), nu și OpenAI/Gemini TTS — deci nici măcar o reimplementare completă acolo n-ar fi echivalentă.

### Ce-mi trebuie de la tine
Adaugă `app/index.tsx` la lista permisă pentru o rundă „E2-fix-1" (schimbarea e cele ~4 linii de mai sus, un singur tip de schimbare, cu `ECHO_WINDOW_MS` drept constantă de revert).

---

## 4. Constante de revert

| Constantă | Fișier | Revert |
|---|---|---|
| `PHONETIC_MATCH` | `lib/appIndex.ts` | `false` → exact potrivirea dinainte (fără schelet de consoane, fără Levenshtein) |
| *(Fix 1)* `ECHO_WINDOW_MS` | `app/index.tsx` — **neatins** | `120000` |

---

## 5. Build + instalare

- `npx tsc --noEmit` → **0 erori**.
- `gradlew assembleRelease` (cu `ANDROID_HOME` inline) → **BUILD SUCCESSFUL in 1m**. Schimbare doar în `lib/appIndex.ts` (JS) → doar bundle-ul RN a fost refăcut, fără recompilare Kotlin.
- APK: `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk` · **260 876 700 B (~248,8 MiB)**.
- Certificat: `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · APK Signature Scheme v2 = verified.
- `adb install -r -d` pe `9c1464eb` (CPH2663 / OnePlus Nord 4) → **Success**.
- `dumpsys package com.benson.butler`: `lastUpdateTime=2026-09-07 11:51:09` (era `11:29:16` = E2), `firstInstallTime` neschimbat `2026-08-23 10:15:49` → build-ul E2-fix e pe telefon, cu datele păstrate.

---

## 6. Acceptare pe dispozitiv

| Test | Așteptat | Status |
|---|---|---|
| „Benson deschide FlexParken" | „Am găsit FlexParken. O deschid?" → „da" → se deschide. Log: `APP_MATCH query="…" matched="FlexParken" package="…"` | **de testat** — Fix 2 livrat |
| „Benson deschide YouTube" fără `REJECTED_self_echo` | se deschide direct | **NU e rezolvat de această rundă** — vezi §3, necesită `app/index.tsx` |
