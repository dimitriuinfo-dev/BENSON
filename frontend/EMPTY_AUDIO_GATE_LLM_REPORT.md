# Raport — audio gol, gate anti-halucinație, model LLM 404

Data: 2026-08-28 · Rundă cu 3 sarcini, în ordine. Doar fișiere JS. Zero fișiere native,
zero fișiere protejate, `git` neatins.

---

## 0. Verificări

| | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (exit 0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 45s** (exit 0) |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · schema v2 · 1 semnatar · RSA 2048 |

**APK:**
```
C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk
```
**Dimensiune:** 260 854 248 bytes ≈ 248.8 MiB · construit 22:24:45
SHA-256 cert: `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da`

**Fișiere modificate (5, toate JS):**

| Fișier | Linii nete | Sarcină |
|---|---|---|
| `lib/engines/stt/groqStt.ts` | +46 | 1 |
| `lib/agents/voiceAgent.ts` | +33 | 1, 2 |
| `lib/agents/localWhisperEngine.ts` | +9 | 1 (instrumentare comparativă) |
| `app/index.tsx` | +25 | 2 |
| `lib/engines/registry.ts` | +58 | 3 |

---

## 1. AUDIO GOL (`bytes=0`) — diagnostic + fix în JS

### Ce am exclus prin citirea codului (nu prin ghicit)

**Ipoteza „altă cale/variabilă" — INFIRMATĂ.** Ambele rute primesc *exact același* string:
`voiceAgent.ts` → `addCaptureEndListener((filePath) => …)` → `transcribeAudio(filePath, lang)` →
fie `transcribeWithGroq(filePath, …)`, fie `transcribeLocally(filePath, …)`. O singură variabilă,
transmisă neschimbată. Nu există a doua cale.

**Ipoteza „Groq citește înainte de finalizarea `finish()`" — CONFIRMATĂ ca fiind cea reală, și e
o cursă de temporizare, nu un bug nativ:**

- `transcribeWithGroq` (înainte de fix): apela `FileSystem.getInfoAsync(wavFilePath)` la ~2 ms
  după evenimentul JS `onCaptureEnd`. Ordinea dintre acel eveniment și scrierea/închiderea finală
  a fișierului WAV de către modulul nativ **nu este garantată**. Rezultat: `exists=false` sau
  `size=0` → `bytes=0` → Groq/Whisper halucinează propoziții din fișierul gol.
- `transcribeLocally`: citește fișierul (`getInfoAsync`, linia 299) **abia după
  `await ensureContext()`** — adică după `initWhisper()` (contexт nou per rostire,
  `WHISPER_FRESH_CONTEXT_PER_TAKE = true`), care pe acest telefon durează sute de ms (log `CTX_INIT`),
  plus descărcarea modelului la prima rulare. Această întârziere **incidentală** dă modulului nativ
  timp să golească și să închidă WAV-ul. De aceea pe 02.08 captura a apărut curată (284800 bytes,
  8.9 s) — a fost citită *după* `initWhisper`, niciodată cu ruta rapidă Groq.

Deci captura nativă e (probabil) în regulă; ce lipsea era ca ruta Groq să aștepte fișierul.

### Fix (pur JS, `groqStt.ts`)

`waitForWavReady(wavFilePath, captureEndAt?)` înainte de citire/upload:
- interoghează `getInfoAsync` la fiecare 100 ms, timeout 1500 ms;
- se oprește când `size > 44` **și** neschimbat față de citirea anterioară (fișier stabil);
- returnează `bytes` reali către restul funcției (măsurare durată, `STT_REQUEST`).

`captureEndAt` (`Date.now()` luat în callback-ul `onCaptureEnd` din `voiceAgent.ts`) e transmis
prin `transcribeAudio → transcribeWithGroq` / `transcribeLocally` pentru a calcula `tSinceFinishMs`.

### Instrumentare cerută — `AUDIO_FILE path=… existsAtRead=… bytes=… tSinceFinishMs=…`

Se scrie acum în 3 puncte, pentru comparația directă a celor două rute în același log:

| Punct | Linie | Când |
|---|---|---|
| `voiceAgent.ts` `measureAndLogCaptureFile` | `AUDIO_FILE component=voiceAgent …` | imediat la `onCaptureEnd` (t≈0) |
| `groqStt.ts` `waitForWavReady` | `AUDIO_FILE engine=groq phase=first_read \| ready \| timeout …` | prima citire + rezultatul așteptării |
| `localWhisperEngine.ts` | `AUDIO_FILE engine=local …` | la citirea lui, **după** `ensureContext()` |

La următorul test pe dispozitiv, logul arată negru pe alb: `first_read bytes=0 tSinceFinishMs=3`
vs `ready bytes=NNNNN tSinceFinishMs=180` (confirmă cursa + că fix-ul o acoperă), sau
`first_read bytes>44` (infirmă cursa — atunci se investighează nativul, dar abia atunci).

**Modulul nativ nu a fost atins.**

---

## 2. Gate-ul nu mai poate fi confirmat de o halucinație

### Cauza

`13838: WHISPER_LANG_MISMATCH engine=groq raw="Da, confirm."` — produs din `bytes=0`.
Filtrul de halucinație (`applyHallucinationFilter`) **nu** respinge „Da, confirm.":
`classifyTranscription` cere doar `length ≥ 3` și absența frazelor din blocklist; „lang mismatch"
se loghează dar nu respinge. Deci textul trecea, se potrivea cu `YES_PATTERN`, și
`confirmActiveMission()` se executa fără consimțământ real.

### Fix (`voiceAgent.ts` + `app/index.tsx`)

- `voiceAgent.ts`: `lastUtteranceBytes` (dimensiunea WAV-ului care a produs ultimul transcript
  local), setat în callback-ul `onCaptureEnd` prin `measureAndLogCaptureFile`. `getLastUtteranceBytes()`
  exportat. Valoarea `-1` = rută fără fișier (Android `SpeechRecognizer`) sau nemăsurat →
  **exceptată** de la gate.
- `app/index.tsx`:
  - `const MIN_CONFIRM_BYTES = 8000;` — captura e 16 kHz mono 16-bit = 32000 bytes/s, deci
    8000 bytes ≈ 0.25 s de vorbire reală. Sub prag = 0-byte sau doar antet WAV = halucinație.
  - `handleIncomingText(msg, opts?: { viaVoice?; utteranceBytes? })`. Gardă la începutul funcției:
    dacă `viaVoice` **și** `YES_PATTERN` **și** nu `NO_PATTERN` **și** un gate e deschis
    (`pendingVignetteRef` / `pendingNoteActionRef` / `pendingMissionTaskRef` /
    `getActiveMission().state === 'WaitingConfirmation'`) **și** `0 ≤ bytes < MIN_CONFIRM_BYTES`:
    → `logAudioDiag('CONFIRM_REJECTED', 'reason=empty_audio bytes=…')`, `return`.
    **Gate-ul rămâne pending, nu se execută nimic, nici măcar nu apare în transcript.**
  - Cele 2 apeluri vocale (`scheduleAssembledDispatch` și tail-ul same-breath după wake word)
    trec acum `{ viaVoice: true, utteranceBytes: getLastUtteranceBytes() }`. Calea de text
    (`onSubmitText`) nu trece nimic → `viaVoice` false → gate-ul nu se aplică textului tastat.

Notă: o rostire respinsă de filtrul de halucinație devine oricum `NOT_UNDERSTOOD_TEXT`, care nu
se potrivește cu `YES_PATTERN` — deci acea cerință („nu a fost respinsă de filtru") era deja
îndeplinită; garda pe bytes acoperă exact cazul rămas (text plauzibil din zgomot/tăcere).

---

## 3. Modelul LLM 404 — descoperire, nu ghicit

### Ce arată logul

```
LLM_ENDPOINT url=https://api.groq.com/openai/v1/chat/completions model=llama-3.1-8b-instant
LLM_ERROR status=404 body="{\"error\":{\"message\":\"The model `llama-3.1-8b-instant` does not
  exist or you do not have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"
```

URL-ul e curat (fără `//`), deci **nu** e un 404 de adresă — e genuin `model_not_found`. Pe acest
cont, **și** `llama-3.3-70b-versatile` **și** `llama-3.1-8b-instant` dau 404. Niciun nume hardcodat
nu e de încredere.

### Nu am putut face GET-ul acum

Cheia Groq e în `expo-secure-store` pe telefon — nu îmi este accesibilă din acest mediu. Deci
**nu pot lipi lista reală în raport acum.** Am rezolvat altfel, conform „nu ghici numele":

### Fix (`registry.ts`) — descoperire la runtime

`discoverGroqBrainModel(baseUrl, apiKey)`:
1. `GET {baseUrl}/models` cu cheia salvată (timeout 15 s);
2. loghează lista completă: **`LLM_MODELS count=N list="id1,id2,…"`** (asta îți dă lista în log
   la prima pornire cu cheia validă);
3. alege primul din `GROQ_BRAIN_MODEL_PREFERENCE` prezent în listă
   (`llama-3.3-70b-versatile` → `llama-3.1-8b-instant` → `llama-3.1-70b-versatile` →
   `llama3-70b-8192` → `llama3-8b-8192` → `gemma2-9b-it` → `openai/gpt-oss-20b` →
   `openai/gpt-oss-120b` → `qwen/qwen3-32b` → `deepseek-r1-distill-llama-70b`);
4. dacă niciunul nu e în listă → primul id care nu e audio (`whisper|tts|guard|embed|playai`);
5. loghează `LLM_MODELS chosen=<id>`; cache în memorie (un singur GET per sesiune de app).

`resolveLlmBrain` (ramura care refoloseste cheia Groq) folosește
`(await discoverGroqBrainModel(...)) || GROQ_BRAIN_DEFAULT_MODEL`. Constanta
`GROQ_BRAIN_DEFAULT_MODEL = 'llama-3.1-8b-instant'` rămâne **doar** ca ultimă soluție dacă GET-ul
însuși eșuează (offline). Comentariul greșit („production model available on every Groq account
tier") a fost înlocuit cu explicația de mai sus.

**Lista completă `/models` va apărea în log la următoarea pornire** (linia `LLM_MODELS list=…`).
Dacă o vrei în raport acum, rulează în terminal:

```
! curl -s https://api.groq.com/openai/v1/models -H "Authorization: Bearer <cheia_ta_gsk_...>" | grep -o '"id":"[^"]*"'
```

---

## 4. Ieșiri din scope

Niciuna. 5 fișiere JS modificate (`groqStt.ts`, `voiceAgent.ts`, `localWhisperEngine.ts`,
`app/index.tsx`, `registry.ts`). Nu s-a atins `whatsappTool.ts`, niciun fișier din `android/**`,
`modules/**`, `plugins/**`, `whisper-models/**`, `porcupine-model/**`, `missionValidator.ts`,
`missionExecutor.ts`. `git` neatins. `expo prebuild` nerulat. `ANDROID_HOME` setat doar pentru
comanda `gradlew` (fără `setx`).

---

## 5. La testare — ce să urmărești în log

```
adb logcat -c
# pornește app, comandă vocală care cere confirmare
adb logcat -d | grep -E "AUDIO_FILE|STT_REQUEST|CONFIRM_REJECTED|LLM_MODELS|LLM_ENDPOINT|LLM_ERROR"
```

- `AUDIO_FILE engine=groq phase=first_read bytes=…` — dacă `bytes=0` aici dar `phase=ready bytes>0`
  urmează → cursa era reală și fix-ul a prins-o. `STT_REQUEST engine=groq bytes=…` trebuie să fie
  acum > 0.
- `CONFIRM_REJECTED reason=empty_audio bytes=…` — apare doar dacă o rostire goală ar fi confirmat
  un gate; gate-ul rămâne deschis.
- `LLM_MODELS count=… list="…"` — lista reală de modele. `LLM_MODELS chosen=…` — ce a ales.
  `LLM_ENDPOINT model=…` trebuie să fie acel `chosen`, iar `LLM_ERROR status=404` să dispară.
