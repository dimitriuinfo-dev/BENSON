# RUNDA D — raport

31.08.2026. Lock: `src/core/orchestrator/missionOrchestrator.ts` (DOAR runda asta) ·
`app/index.tsx` · acest raport.
Fără `git`, fără `expo prebuild`, fără `setx`. Neatinse: `android/**`, `plugins/**`, `modules/**`,
`whisper-models/**`, `porcupine-model/**`, `whatsappTool.ts`, `missionValidator.ts`,
`missionExecutor.ts`.

---

## 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 47s** (exit 0) |

**APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
- Dimensiune: **260 875 196 bytes** (≈ 248.8 MiB)
- Construit: 2026-08-31 08:28:14
- Certificat: **`CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`** · schema v2 · SHA-256 `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da`

**Instalarea pe dispozitiv nu s-a putut face — `adb: no devices/emulators found`** (telefonul
deconectat de la USB în momentul buildului). APK-ul e gata la calea de mai sus; instalează-l cu
`adb install -r "<cale>"` când reconectezi.

---

## 1. Fișier cu fișier

### `src/core/orchestrator/missionOrchestrator.ts` — ~107 linii nete

| Zonă | Linii | Ce |
|---|---|---|
| `MissionRunResult` + `type DisambiguationCandidate` | +9 | câmp nou `disambiguation?: { candidates }` — semnal către UI că răspunsul e o propunere „pe care?" |
| `pendingDisambiguation` state + `stripDiac` + `matchDisambiguationPick` | +41 | stare reală de așteptare pentru o propunere; potrivire ordinală („primul", „a doua", „3") sau pe nume (substring / suprapunere de token-uri), insensibil la diacritice; auto-expiră la 60s |
| `runMission` — consumă `pendingDisambiguation` PRIMUL | +24 | dacă e o propunere în așteptare, rostirea următoare e potrivită cu candidații și se lansează direct (`OPEN_APP` cu numele ales), **nu** re-parsată ca o comandă nouă. „Nu/las-o" o anulează. Fără potrivire → cade prin la dispatch normal. |
| `executeTask` — prinde `needs_disambiguation` | +18 | `governAction` întoarce `needs_disambiguation` (radio/muzică/app) → task-ul intră `WAITING`, lista de candidați e trecută în sus, nu mai e marcat `FAILED` |
| `executeTask` semnătură return | ~1 | `+ disambiguation?: DisambiguationCandidate[]` |
| `runPlanFrom` — armează `pendingDisambiguation` | +7 | la o propunere, setează starea + `resetActiveMission()` + întoarce `{ disambiguation }` |
| cooldown `goalSignature` | ~10 | **include acum textul rostit**: `...|txt:<normalizedText[:80]>`. „deschide un radio" ≠ „vreau un radio românesc". Treapta 1 (microfon închis în timpul TTS) e apărarea reală contra ecoului acum, deci semnătura poate fi mai strictă. |
| cooldown declanșat | ~2 | `return { handled: false, message: '' }` (era `handled: true` + `message: ''` = **tăcere**). `handled: false` trimite la creier → BENSON spune ceva. |

### `app/index.tsx` — ~31 linii nete

| Zonă | Linii | Ce |
|---|---|---|
| `setBensonState` — suprimă no-op | ~2 | `if (prev === next && next !== 'EXECUTING') return;` — elimină zgomotul `STATE from=DONE to=DONE` |
| `AppState` ramura `next !== 'active'` | +7 | **necondiționat** la trecerea în fundal: `stopSpeaking()` + `stopOpenAITTS()` + `stopGeminiTTS()` + `endTtsBlock()`. Nicio rostire nu supraviețuiește unei tranziții în fundal → gata cu „am deschis Waze" rostit după YouTube. |
| `finishHandledMission` — gardă mesaj gol | +13 | un răspuns de misiune fără mesaj → `ERROR` + rostește „N-am înțeles, spune din nou." (ro/de/en). Niciodată tăcere. |
| `finishHandledMission` — propunere → `CONFIRMING` | +9 | `mr.disambiguation` → `STATE=CONFIRMING detail=disambiguation`, **fără auto-clear** (nu mai dispare de pe ecran după 4s cât timp alegi); `expectsReply` include acum cazul propunerii |

---

## 2. Cerințele rundei

| # | Cerință | Stare |
|---|---|---|
| 1 | Dezambiguarea setează stare reală; rostirea următoare e răspuns, nu comandă nouă | ✅ `pendingDisambiguation` + rutare în `runMission` |
| 2 | Semnătura de cooldown include parametrii; „radio" ≠ „radio românesc" | ✅ `|txt:<normalizedText>` în `goalSignature` |
| 3 | Cooldown declanșat nu produce niciodată `message=''` | ✅ `handled: false` → creier; plus gardă în `finishHandledMission` |
| 4 | La `APP_STATE=background`: oprire TTS + golire coadă, necondiționat | ✅ în ramura `next !== 'active'` |
| 5 | Propunerea → `CONFIRMING`, fără auto-clear | ✅ `mr.disambiguation` → `CONFIRMING`, `stateClearTimerRef` nu se armează pentru `CONFIRMING` |

---

## 3. Comportamente dovedite — ce poate fi afectat (regula 1)

| Comportament | Afectat? | De ce |
|---|---|---|
| Navigație Waze | Nu | Semnătura de cooldown mai strictă doar **reduce** fals-pozitivele; navigația nu trecea prin dezambiguare. Cooldown-ul de self-echo real e acum redundant (Treapta 1 închide ecoul la microfon). |
| Deschidere aplicație după nume | Nu | `OPEN_APP` cu nume exact → `resolveAppQuery` → `exact` → lansare, neschimbat. Dezambiguarea intervine doar când erau deja mai mulți candidați. |
| Propunere pentru cerere generică | **Îmbunătățit** | Acum e o poartă reală: răspunzi „primul" / „Magic FM" și se deschide, în loc să cadă tăcut. |
| Apel WhatsApp cap-coadă | Nu | `whatsappTool.ts` / `missionExecutor.ts` neatinse. Ramura `needs_disambiguation` din `executeTask` e după calea guvernată (`runGovernedTask`), nu o atinge. |
| Microfon auto-reparare (Treapta 1) | Nu | `setBensonState` no-op-suppress nu atinge `speakingRef`/buclele. TTS-kill la background e în plus față de handler-ul de foreground-return, nu-l înlocuiește. |
| Microfon închis cât vorbește BENSON | Nu | `endTtsBlock()` la background e apelul existent; doar se cheamă și pe calea asta. |
| Mașina de stări C8 | **Îmbunătățit** | no-op-ul `DONE→DONE` dispare din log; propunerile devin `CONFIRMING` corect; mesaj gol → `ERROR` vizibil. |
| Index de aplicații 274 | Nu | neatins. |

Riscul realist: `matchDisambiguationPick` ar putea potrivi greșit un răspuns ambiguu la un
candidat nedorit (ex. „nu prima, a doua" — conține „prima"). Ordinalele sunt verificate în ordine
și „a doua"/„al doilea" au prioritate la index 1; un `NO_PATTERN` la început anulează. Logul
`ReactNativeJS: 'disambiguation resolved ->', <nume>` arată exact ce a ales.

---

## 4. Ieșiri din scope

Niciuna. Ambele fișiere modificate sunt în lock. `missionOrchestrator.ts` a fost deblocat explicit
pentru această rundă. Nimic din lista interzisă nu a fost atins.

---

## 5. Test pe dispozitiv (când reconectezi)

```
adb install -r "C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk"
adb logcat -c
```
1. „deschide un radio" → propunere. Ecranul **NU** se golește după 4s (`STATE ... to=CONFIRMING detail=disambiguation`, fără `to=IDLE detail=autoclear`).
2. „primul" sau „Magic FM" → se deschide (`disambiguation resolved -> Magic FM`, `STATE→DONE`).
3. „deschide un radio", apoi „vreau un radio românesc" → **nu mai e tăcere**: fie `disambiguation resolved`, fie o nouă propunere (semnături diferite, cooldown nu se declanșează).
4. „deschide YouTube" → bulă → revii → BENSON **nu** mai rostește un răspuns vechi (Waze).
5. Niciun `STATE from=DONE to=DONE` în log.
