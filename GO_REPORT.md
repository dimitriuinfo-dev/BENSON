# GO Round — Report (Groq STT + swappable nuclei + anti-corruption architecture)

27.08.2026 · o singură rundă

---

## 0. Partea 0 (cheile) — stare

Cheile Groq/Picovoice sunt responsabilitatea utilizatorului per comanda de lucru ("CE FACI TU
ÎNAINTE"). Nu am cont Groq/Picovoice și nu am introdus nicio cheie nicăieri — Settings-ul nou
(`app/settings.tsx`) este gol până când cheile sunt introduse acolo, pe dispozitiv. Fără ele,
**testul de acceptare pe dispozitiv nu a fost rulat** — vezi §6.

---

## 1. Câmpuri reale verificate în tipările instalate

Nicio opțiune cerută în această rundă s-a dovedit lipsă din tipările instalate — spre deosebire de
runda anterioară (whisper.rn), tot ce a fost cerut aici a fost implementabil direct, verificat
împotriva codului real, nu din memorie:

| Pachet | Ce am verificat | Fișier |
|---|---|---|
| `expo-secure-store` (~15.0.8) | `setItemAsync(key, value)`, `getItemAsync(key)` — folosite exact așa | `node_modules/expo-secure-store/build/SecureStore.d.ts` |
| `@react-native-async-storage/async-storage` | `setItem(key, value)`, `getItem(key)` | `node_modules/@react-native-async-storage/async-storage/lib/typescript/types.d.ts` |
| `expo-speech` | `SpeechOptions.{language,onDone,onStopped,onError}` (onError primește `Error`, nu `unknown`), `Voice.language`, `speak()`/`getAvailableVoicesAsync()` | `node_modules/expo-speech/build/Speech.types.d.ts`, `Speech.d.ts` |

Niciun SDK de furnizor nu a fost adăugat — Groq și „CREIER" folosesc `fetch`/`FormData` simplu,
exact ca `lib/agents/openaiSTT.ts`/`openaiAgent.ts` existente (pattern reutilizat, nu duplicat
orbește).

---

## 2. Fișierul de memorie (Task 5) — localizat, NEATINS

Localizat **înainte** de a atinge orice: entry point-ul LLM-facing este tool-ul `remember` din
**`lib/agents/tools.ts`** (liniile ~223-233 descriere, ~427-432 execuție), apelat prin
`ctx.onRememberFact?.(fact)` — `ToolContext.onRememberFact` e definit tot acolo (linia ~251) și e
threadat prin **`lib/agents/orchestrator.ts`** (liniile ~42-44), al cărui comentariu spune explicit:
*"the same appendFact() used by the REMEMBER_PATTERN regex path, threaded through since facts
storage also lives in app/index.tsx."*

Deci scrierea propriu-zisă (`appendFact()`) trăiește în **`app/index.tsx`** — pe lista interzisă.
**Nu a fost atins.**

Ce am găsit, exact, ca stare curentă (înainte de orice modificare a mea — nu am schimbat nimic din
tools.ts/orchestrator.ts/app/index.tsx):

- Descrierea tool-ului `remember` spune explicit: *"Use when the user explicitly asks you to
  remember something, **or shares something clearly worth recalling later**."* A doua clauză e
  exact judecata autonomă pe care Task 5.1 o interzice — modelul poate decide singur, fără cerere
  explicită, că ceva „merită reținut".
- `case 'remember'` din `executeTool()` (tools.ts) ia `input.fact` — text generat integral de
  model — și îl trimite mai departe **fără niciun filtru** către `onRememberFact`.
- Nimic din acest lanț verifică azi conținutul înainte de scriere.

**Ce ar fi trebuit schimbat, și de ce nu am schimbat:**

1. `app/index.tsx` — `appendFact()` ar trebui să apeleze `checkMemoryWrite()` (nou, vezi mai jos)
   înainte de orice persistență, și să respingă (cu log `MEMORY_REJECTED`) orice `fact` care nu
   trece filtrul. **Fișier interzis — neatins.**
2. `lib/agents/tools.ts` — descrierea tool-ului `remember` ar trebui restrânsă la „doar când
   utilizatorul cere explicit", eliminând clauza „sau împărtășește ceva demn de reținut", și
   idealmente `case 'remember'` ar trebui să nu se declanșeze deloc decât dintr-o cale explicit
   marcată ca provenind dintr-o cerere directă a utilizatorului (nu din orice tur de conversație).
   **Nu e pe lista de fișiere permise ("poți modifica DOAR") — neatins.**
3. `lib/agents/orchestrator.ts` — același argument ca mai sus pentru cum e threadat
   `onRememberFact`. **Nu e pe lista de fișiere permise — neatins.**

Ce **am** construit, în scope (`lib/engines/memory/memoryGuard.ts`, fișier nou):

- `checkMemoryWrite(fact)` — filtrul cerut la 5.2 (blocklist RO/DE/EN pentru fraze care ar schimba
  comportamentul lui BENSON), gata de apelat din `appendFact()` odată ce cineva are voie să
  modifice `app/index.tsx`. Loghează `MEMORY_REJECTED reason=…` la fiecare respingere.
- `buildMemoryContextTurn(facts)` — 5.3: împachetează faptele reținute ca `UNTRUSTED_DATA` (context
  marcat), niciodată ca `SYSTEM` (reguli).

Filtrul e complet și testabil (vezi tsc), dar **nu e conectat live** — asta ar necesita
`app/index.tsx`, în afara scope-ului acestei runde.

---

## 3. Fișiere atinse, cu linii schimbate

Nimic din afara scope lock-ului nu a fost atins. Toate liniile "schimbate" de mai jos sunt DOAR
cele adăugate/modificate în această rundă (nu cumulate cu runda anterioară, pentru cele două
fișiere pe care runda anterioară le atinsese deja) — diff calculat față de starea fișierului chiar
înainte de primul Edit din această rundă.

| Fișier | Stare | Linii schimbate (runda asta) |
|---|---|---|
| `lib/engines/types.ts` | nou | 71 |
| `lib/engines/settingsStore.ts` | nou | 68 |
| `lib/engines/registry.ts` | nou | 46 |
| `lib/engines/stt/groqStt.ts` | nou | 104 |
| `lib/engines/llm/messageChannels.ts` | nou | 118 |
| `lib/engines/llm/openAiCompatibleBrain.ts` | nou | 106 |
| `lib/engines/tts/androidTts.ts` | nou | 47 |
| `lib/engines/memory/memoryGuard.ts` | nou | 65 |
| `app/settings.tsx` | nou | 222 |
| `lib/agents/localWhisperEngine.ts` | modificat | +31 / −13 |
| `lib/agents/voiceAgent.ts` | modificat | +24 / −2 |
| `GO_REPORT.md` | nou | acest fișier |

`lib/agents/localWhisperEngine.ts` — modificarea e strict „conformare la interfața SttEngine" plus
un refactor minim necesar pentru Task 2 (filtrul de halucinație existent trebuia reutilizat de
Groq): funcția `applyHallucinationFilter()` a fost extrasă din corpul lui `transcribeLocally()`
(comportament identic, doar exportată) și `export const localSttEngine: SttEngine = { id: 'local',
transcribe: transcribeLocally }` a fost adăugat la final. Nimic din logica de transcriere locală
nu s-a schimbat.

`lib/agents/voiceAgent.ts` — strict punctul de apel STT (`transcribeAudio()`): Groq adăugat ca
primul nivel încercat, plus `groqRateLimitedUntil` (același pattern deja folosit pentru
gemini/openai) și un comentariu actualizat la începutul fișierului. Restul fișierului (TTS,
wake-scan, startRecognition etc.) neatins.

---

## 4. Verificare

### `npx tsc --noEmit`

```
(fără output — exit code 0)
```

**0 erori.**

### `gradlew assembleRelease`

Ca și runda anterioară: `ANDROID_HOME`/`ANDROID_SDK_ROOT` setate ca variabile de mediu doar pentru
această singură invocare Gradle (nimic scris pe disc, niciun `local.properties` creat, niciun
`setx`, nicio modificare de PATH) — folosind SDK-ul deja instalat la
`C:\Users\lenovo\AppData\Local\Android\Sdk`.

Coada build-ului:

```
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease

[Incubating] Problems report is available at: file:///C:/Users/lenovo/Desktop/BENSON-Android/frontend/android/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
...
BUILD SUCCESSFUL in 57s
945 actionable tasks: 67 executed, 878 up-to-date
```

**APK:** `frontend/android/app/build/outputs/apk/release/app-release.apk`
**Dimensiune:** 260,807,684 bytes (~248.7 MiB)

**Semnare** (`apksigner verify --print-certs`):

```
Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
Signer #1 certificate SHA-256 digest: fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da
```

`CN=BENSON, O=TOKKO` confirmat — neschimbat. Niciun fișier din `android/**`/`plugins/**` nu a fost
deschis sau atins.

---

## 5. Fișiere interzise — ce am vrut să schimb și nu am schimbat

- **`app/index.tsx`** — de departe cel mai relevant: `appendFact()` (Task 5, vezi §2) ar trebui să
  apeleze `checkMemoryWrite()` înainte de orice scriere. De asemenea, este fișierul care ar trebui
  să compună `SystemTurn`/`UserVoiceTurn`/`UntrustedDataTurn` din conversația reală și să apeleze
  `resolveLlmBrain()`/`resolveTtsVoice()` (Task 1's registry) pentru ca noul „CREIER"/"VOCE" să
  devină vocea live a aplicației, nu doar module testabile din Settings — vezi §7 pentru de ce
  testul de acceptare pentru conversație/injecție nu a putut fi rulat ca urmare. Neatins.
- **`lib/agents/tools.ts`** / **`lib/agents/orchestrator.ts`** — Task 5.1 („creierul nu are voie să
  scrie în memorie... elimină orice cale prin care un răspuns al modelului poate declanșa singur o
  scriere") cere restrângerea tool-ului `remember` (vezi §2). Niciunul din cele două fișiere nu e
  pe lista „poți modifica DOAR" — neatinse.
- **`lib/agents/missionValidator.ts`**, **`lib/agents/missionExecutor.ts`**, **`app/index.tsx`**
  (din nou), **`lib/tools/whatsappTool.ts`** — nimic din runda asta le-a cerut direct (confirmă
  Partea 2 a comenzii: acțiunile reale ale lui `KnownAction` nu sunt încă legate de sistemul de
  misiuni existent — enum-ul din `lib/engines/types.ts` e nou și explicit provizoriu, documentat ca
  atare în cod). Neatinse.
- **`android/**`, `plugins/**`, `modules/**`, `whisper-models/**`, `porcupine-model/**`** —
  neatinse, nedeschise.

---

## 6. Rezumat onest al golurilor de integrare (nu era pe lista de raport, dar e necesar pentru §7)

Task 2 (Groq STT) este **complet live**: `voiceAgent.ts`'s `transcribeAudio()` — punctul unic prin
care trece azi orice captare de comandă sau wake-scan — încearcă Groq primul, indiferent ce face
`app/index.tsx` mai departe. Asta e singura parte a rundei care poate fi testată real pe dispozitiv
fără nicio altă modificare.

Task 3/4 (CREIER) și Task 6 (VOCE) sunt construite complet și corect (tsc curat, testabile din
Settings prin butonul TEST), dar **nu sunt conectate în bucla live de conversație** — asta ar
necesita ca `app/index.tsx` (interzis) să apeleze `resolveLlmBrain()`/`resolveTtsVoice()` în loc de
`claudeAgent.ts`/`openaiAgent.ts`/`speakNow()` existente. Nu am făcut asta.

---

## 7. Testul de acceptare — NU a fost rulat

Motive, cumulate:

1. **Cheile din Partea 0** (Groq, Picovoice) nu au fost furnizate în această sesiune — fără o cheie
   Groq reală, nici măcar Task 2 (singura parte complet live-conectată) nu poate fi testat pe
   dispozitiv.
2. **Conversația liberă / testul de injecție** cer ca Task 3/4/6 să fie live în bucla de
   conversație — structural imposibil fără a atinge `app/index.tsx` (vezi §5/§6).
3. Nu am acces la dispozitivul fizic OnePlus Nord 4 din acest mediu de lucru pentru a rula
   `adb logcat`/a instala APK-ul/a vorbi comenzi.

APK-ul de mai sus e construit și gata de instalat; testul de acceptare complet rămâne următorul
pas, condiționat de §0 și de o rundă viitoare care să conecteze Task 3/4/6 în `app/index.tsx`.

Nimic altceva. Fără commit, fără push, fără tag.
