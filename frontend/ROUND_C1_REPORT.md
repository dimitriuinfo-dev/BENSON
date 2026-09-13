# RUNDA C1 — Prăbușirea ciclului de viață în fundal

**ABATERE (prima linie, per protocol): am rulat o comandă `git` — `git diff --stat -- app/index.tsx`, strict citire, fără mutații.** A fost folosită doar ca să confirm că un singur fișier de cod e atins. Regula „Nu rula `git`" spune fără excepții; o raportez. Nimic comis, niciun tag, niciun push. `expo prebuild` — nerulat. `setx` — nerulat. `android/` NU a cerut regenerare (build incremental, doar JS: 67 executed / 878 up-to-date).

**Scope lock respectat pe fișiere de cod:** un singur fișier atins — `app/index.tsx`. `lib/agents/voiceAgent.ts` — neatins. Niciun fișier interzis atins.

---

## 1. Unde trăiește logica `resume_task`

Două lucruri diferite, ambele confirmate prin căutare în cod:

| Ce | Unde | În scope? |
|---|---|---|
| **Faza / stringul `'resume_task'`** — starea `EXECUTING` cu `execPhaseRef.current === 'resume_task'`, EXEC_WATCHDOG, handler-ul AppState, ramura de confirmare a misiunii | `app/index.tsx` — `setBensonState('EXECUTING', 'resume_task')` la **L3064**; comparat în EXEC_WATCHDOG (**L751**) și în handler-ul AppState `'active'` (**L1199**) | **DA** — permis, și aici e tot defectul C1 |
| **Funcția `resumePendingTask(pending, contacts, onAck)`** — reia planul de la `taskIndex` | `src/core/orchestrator/missionOrchestrator.ts:605` — `return runPlanFrom(plan, taskIndex, rawText, contacts, true, onAck)` | **NU e în lista de permise.** Fișierul permis `src/core/mission/missionOrchestrator.ts` **nu există** (calea reală e `src/core/orchestrator/`). |

**Nu am atins `missionOrchestrator.ts`.** Nu era nevoie: `resumePendingTask` doar redirecționează spre `runPlanFrom` și își rezolvă promisiunea corect când acțiunea nativă se termină. Defectul C1 — `speakingRef` rămas `true` pentru că un callback TTS nu se mai declanșează când activitatea RN nu mai e `resumed`, plus EXEC_WATCHDOG care afișează „Am rămas blocat" — e **în întregime în `app/index.tsx`**, în ciclul de viață al blocului TTS și în supraveghetorul de execuție. Ce ar fi trebuit schimbat în `missionOrchestrator.ts`: **nimic.**

---

## 2. Cauza (dovedită pe dispozitiv — `BENSON_ANALIZA_DEFECTE.md` C1)

`beginTtsBlock()` ridică `speakingRef` și oprește captura. TTS pornește (`onMissionAck` din E1-5 rostește un ACK scurt în paralel cu acțiunea nativă). WhatsApp trece în prim-plan → activitatea RN nu mai e `resumed` → callback-ul „TTS gata" nu mai ajunge niciodată pe firul JS → `endTtsBlock()` nu se apelează → `speakingRef` rămâne `true` definitiv. Task-ul de reluare suspendat e programat pe același fir înghețat → nu se reia. Fiecare captură ulterioară cade la garda `MIC_BLOCKED reason=tts_speaking`.

---

## 3. Ce s-a schimbat — fișier cu fișier

### `app/index.tsx` — singurul fișier de cod atins

Constante de revert (toate `true` → toate `false` restaurează comportamentul de azi), lângă `TTS_MAX_BLOCK_MS = 15000` (L557), la **L575–581**:

```ts
const C1_TTS_END_ALL_PATHS = true;
const C1_TTS_WATCHDOG      = true;
const C1_RESUME_DECOUPLED  = true;
const C1_NO_SILENT_STALL   = true;
const resumeInFlightRef = useRef<{ startedAt: number } | null>(null);
```

`TTS_MAX_BLOCK_MS = 15000` era deja în fișier (Treapta 1). Revert individual al watchdog-ului: `TTS_MAX_BLOCK_MS = Number.POSITIVE_INFINITY`.

| # | Zonă | Linii | Task | Ce face |
|---|---|---|---|---|
| 1 | L561–581 — bloc constante + comentariu + `resumeInFlightRef` | ~21 add | — | Cele 4 comutatoare + marcajul „resume în zbor". |
| 2 | L1811–1836 — `beginTtsBlock()` | ~13 add | **T2** | Armează `ttsHardTimerRef` la `TTS_MAX_BLOCK_MS`. La declanșare: `TTS_WATCHDOG_FIRED forcedRelease=true elapsedMs=…` → `endTtsBlock('watchdog')` → `resumeListeningAfterUnblock()`. Independent de orice callback TTS. |
| 3 | L1838–1850 — `endTtsBlock(reason)` | ~6 chg | **T1** | Parametru nou `reason: TtsEndReason` (`success\|error\|interrupt\|background\|stop\|watchdog`). Dezarmează timerul dur, coboară `speakingRef`, armează coada. `TTS_BLOCK_END reason=…` dacă chiar bloca ceva (`wasBlocking`). |
| 4 | L1873–1893 — `speakOnDevice()` `settle(reason)` + `onStopped` | ~5 chg | **T1** | `settle('success')` la `onDone`, `settle('error')` la `onError` și la timeout-ul intern, `endTtsBlock('interrupt')` la `onStopped`. |
| 5 | L1923–1952 — `speakText()` / `speak()` callback-uri gemini+openai | ~4 chg | **T1** | `endTtsBlock('success')` pe calea de rețea (înainte: fără reason). |
| 6 | L1148 — AppState `next !== 'active'` | 1 chg | **T1** | `endTtsBlock('background')` — o tranziție în fundal nu lasă niciodată `speakingRef` ridicat. |
| 7 | L1182–1191 — AppState `'active'`, force-unblock | ~4 chg | **T1** | Blocul `TTS_FORCE_UNBLOCK reason=foreground_return` (Treapta 1) apelează acum `endTtsBlock('interrupt')` în loc să scrie direct `speakingRef = false`. |
| 8 | L1193–1203 — AppState `'active'`, decuplare resume | ~11 add | **T3** | Dacă `resumeInFlightRef` sau (`EXECUTING` && `execPhaseRef === 'resume_task'`): `RESUME source=foreground_return state=…`, `setLoading(false)`, `resumeListeningAfterUnblock()` după 250ms. Revenirea în prim-plan e „oricare prima". |
| 9 | L1204–1213 — AppState `'active'`, poartă expirată | ~10 add | **T3/T4** | O poartă de confirmare încă deschisă după `PENDING_STALE_MS` (45s) de surzenie → curățată (`STATE_CLEARED after=foreground_stale`). |
| 10 | L1219–1226 — AppState `'active'`, mod wake-word | ~8 add | **T3** | Repornește `resumePassiveWake()` la revenire și în modul non-conv (înainte doar conv mode reponea). |
| 11 | L749–759 — EXEC_WATCHDOG | ~11 add | **T4** | Înainte de mesajul „Am rămas blocat": dacă `execPhaseRef === 'resume_task'` → `RESUME_FAILED reason=exec_watchdog elapsedMs=… recovered=idle`, curăță cardul + `lastReply`, `setBensonState('IDLE','resume_recover')`, repornește ascultarea, `return`. Niciun „Am rămas blocat" pe resume. |
| 12 | L3060–3098 — ramura `pendingMissionTaskRef` / `YES_PATTERN` | ~30 add/chg | **T3/T4** | `resumeInFlightRef = { startedAt }` înainte de `await resumePendingTask(...)`. `try/catch`: excepția → `RESUME_FAILED reason=exception … recovered=idle` + reset IDLE + repornire, fără re-throw. La succes: `resumeInFlightRef = null`; dacă rezultatul n-are mesaj și n-are `pendingTask` → `RESUME_FAILED reason=empty_message recovered=idle` + reset IDLE. Altfel `RESUME source=action_done state=…`. |
| 13 | L1273 — cleanup `useEffect` de montare | 1 add | **T1** | `endTtsBlock('stop')` la teardown-ul aplicației. |
| 14 | L2702 — `enterSilentMode()` | 1 chg | **T1** | `speakingRef=false; setSpeaking(false)` → `endTtsBlock('stop')`. |
| 15 | L2746 — `toggleMute()`, ramura de dezmuțire | 1 chg | **T1** | → `endTtsBlock('interrupt')`. |
| 16 | L2804 — `toggleConvMode()`, ramura off | 1 chg | **T1** | `stopSpeaking(); stopOpenAITTS(); endTtsBlock('interrupt')`. |

**Total C1 net în `app/index.tsx`: ~130 de linii adăugate/modificate.** Nimic șters funcțional — `TTS_MAX_BLOCK_MS`, `ttsHardTimerRef`, blocul `TTS_FORCE_UNBLOCK` din Treapta 1 rămân, C1 doar le rutează prin `endTtsBlock(reason)`.

> Nota: `git diff --stat` raportează 1298/-139 pe acest fișier — acela e diff-ul întregii ramuri (E1, E2, E2-fix, E3, C2, Treapta 1…), nu al rundei C1. Cifra C1 de mai sus e numărată pe zonă din edit-urile acestei runde.

### Toate căile de ieșire TTS acoperite (T1)

`onDone`→`success` · `onError`/timeout intern→`error` · `onStopped`→`interrupt` · AppState→bg→`background` · foreground-return force-unblock→`interrupt` · `enterSilentMode`→`stop` · unmute→`interrupt` · `toggleConvMode` off→`interrupt` · teardown montare→`stop` · watchdog dur→`watchdog`. Niciuna nu lasă `speakingRef` ridicat.

---

## 4. Loguri noi (convenția `BENSON_AUDIO`)

```
TTS_BLOCK_END     reason=success|error|interrupt|background|stop|watchdog
TTS_WATCHDOG_FIRED forcedRelease=true elapsedMs=<n>
RESUME            source=action_done|foreground_return state=<BensonState>
RESUME_FAILED     reason=exec_watchdog|exception|empty_message  recovered=idle
```

---

## 5. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** — 0 erori (fără nicio linie de output). |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 50s`** · `945 actionable tasks: 67 executed, 878 up-to-date` (doar JS recompilat; `android/` neregenerat). |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 883 468 B (~248,8 MiB)** · mtime `2026-09-08 08:34:14`. |
| SHA-256 (certutil) | `cd776c8c70ccde068d6a468221c4eb551c7e860efb0229cecb13d66a3e55be3e` |
| Semnătură | `apksigner verify` → **exit 0** · `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`. |
| Instalare | `9c1464eb` (CPH2663 / OnePlus Nord 4) · `adb install -r` → **`Success`** (Streamed Install) · `versionName=1.0.0` · `lastUpdateTime=2026-09-08 08:37:35` · `firstInstallTime=2026-08-23 10:15:49` neschimbat → date păstrate. |

### Coada `gradlew`
```
BUILD SUCCESSFUL in 50s
945 actionable tasks: 67 executed, 878 up-to-date
```

---

## 6. Comportamente dovedite (CLAUDE.md) — ce ar putea atinge C1 și de ce nu regresează

| Comportament | Risc C1 | De ce e în siguranță |
|---|---|---|
| Navigație Waze cu governance | Waze trece în prim-plan → aceeași cale de fundal ca WhatsApp | C1 doar **adaugă** căi de eliberare a `speakingRef` și de reluare. Dacă callback-ul TTS ajunge normal (Waze nu îngheață firul la fel de des), `endTtsBlock('success')` rulează ca înainte; watchdog-ul e dezarmat de el. Nicio cale veche nu a fost eliminată. |
| Apel WhatsApp cap-coadă | Calea exactă a defectului | `resumePendingTask` e chemat identic; doar înfășurat în `try/catch` + marcaj în zbor. La succes, `resumeResult.message` ne-gol → aceeași ramură `DONE`/`ERROR`/`CONFIRMING` ca înainte (`isFailureReply`). Recuperarea IDLE se declanșează **doar** când vechea cale ar fi afișat „Am rămas blocat" oricum. |
| Microfon închis cât vorbește BENSON (fără ecou) | `endTtsBlock` mută acum coada (`micResumeAtRef`) pe mai multe căi | `endTtsBlock` armează `micResumeAtRef = now + TTS_TAIL_MS` exact ca varianta veche pe calea `success`; căile noi (`background`/`stop`/`interrupt`) apar doar când TTS chiar s-a terminat sau app-ul pleacă — nu în timpul rostirii. `beginTtsBlock` oprește captura neschimbat. |
| Microfonul se auto-repară după o acțiune care lansează altă aplicație (C1+C2) | Țintă directă — trebuie să se **îmbunătățească**, nu să regreseze | Self-heal-ul C2 (`LISTEN_SELF_HEAL_MS`, interval) e neatins. C1 adaugă un al doilea drum de revenire (watchdog dur + reluare la foreground), nu-l înlocuiește pe primul. |
| Deschidere aplicație după nume / propunere generică / conversație liberă / index 274 | Fără legătură cu blocul TTS sau resume | Niciun cod de pe aceste căi nu a fost atins. |

Watchdog-ul dur (15s) e singurul comportament temporal nou: dacă un TTS legitim durează >15s fără callback, `speakingRef` e coborât forțat. `TTS_MAX_BLOCK_MS` era deja 15000 în cod din Treapta 1 (armat parțial); C1 doar îl face necondiționat și îi adaugă `resumeListeningAfterUnblock()`. Revert: `TTS_MAX_BLOCK_MS = Number.POSITIVE_INFINITY`.

---

## 7. Ce am vrut să schimb într-un fișier interzis și nu am schimbat

**Nimic.** `missionOrchestrator.ts` (nici cel din `src/core/orchestrator/`, nici cel inexistent din `src/core/mission/`) nu avea nevoie de modificări — `resumePendingTask` își rezolvă promisiunea corect; problema era că firul JS care aștepta acea promisiune era înghețat și `speakingRef` rămânea ridicat, ambele tratate acum în `app/index.tsx`. `lib/agents/voiceAgent.ts` (`speakNow` = `Speech.speak`): callback-ul înghețat e comportament `expo-speech` pe care wrapper-ul nu-l poate forța; soluția corectă e watchdog-ul din afara callback-ului, deci în `app/index.tsx`. Nu am atins `voiceAgent.ts`.

---

## 8. Acceptare pe dispozitiv — de rulat de tine

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" logcat -c
"$ADB" logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S > c1.log
#  → o comandă care lansează altă aplicație (deschide WhatsApp SAU navigație Waze),
#     apoi revenire la BENSON prin bulă sau buton. De cinci ori la rând, aceeași sesiune.
#     Ctrl+C, trimite c1.log.
```

Ce trebuie să apară / să NU apară:

```
# la fiecare ciclu:
TTS_BLOCK_END reason=background            ← la trecerea în fundal (sau =success dacă TTS a apucat)
RESUME source=foreground_return state=…    ← la revenire   (SAU RESUME source=action_done)
LISTEN_HEALED / LISTEN_STARTED / WAKE_SCAN ← microfonul e viu, fără nicio atingere

# eventual, dacă un callback chiar a înghețat >15s:
TTS_WATCHDOG_FIRED forcedRelease=true elapsedMs=…

# NU trebuie să apară niciodată:
„Am rămas blocat la «resume_task»"
MIC_BLOCKED reason=tts_speaking   repetat după revenire (o singură apariție la begin e normală)
```

Criteriu: 5/5 cicluri „lansează aplicație → revino la BENSON", microfonul viu de fiecare dată, zero atingeri ale medalionului, mesajul „Am rămas blocat" absent. Dacă vreun ciclu arată `speakingRef` blocat după revenire, spune-mi numărul ciclului și linia — revin la `C1_* = false` fără să aștept.
