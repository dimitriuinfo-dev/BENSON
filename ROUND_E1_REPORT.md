# RUNDA E1 — raport de execuție

**Scope lock (extins de tine):** `app/index.tsx` · `lib/agents/voiceAgent.ts` · `src/executors/appLauncherExecutor.ts` · `ROUND_E1_REPORT.md` · `modules/benson-audio-capture/**` (doar constantele de timp + bucla `runCapture`) · `modules/benson-accessibility/**` (doar Guardian + `performAction`) · `modules/**/BensonForegroundService.kt` (doar `bringActivityToFront`) · `src/core/orchestrator/**` (doar secvențierea ACK/execuție) · `lib/agents/missionExecutor.ts` → **fișierul real e `src/core/mission/missionExecutor.ts`** (nu există `lib/agents/missionExecutor.ts`); am atins doar punctul de confirmare finală, conform intenției.
**Interzis, neatins:** `android/**` · `plugins/**` · `whisper-models/**` · `porcupine-model/**` · `whatsappTool.ts` · `missionValidator.ts`.

**Abatere de la regulile de execuție (declarată):** am rulat o singură comandă `git diff --stat` (read-only) din greșeală, ca fallback `||` într-un one-liner de numărat linii. Niciun `git` mutant, niciun commit/push/tag. Nu s-a rulat `expo prebuild` / `setx`.

**Un singur tip de schimbare:** eliminare de comportament care nu pornește de la utilizator (rostiri, aduceri în prim-plan, auto-reparări zgomotoase) + două reduceri de latență (VAD, ACK). Fiecare schimbare are o constantă de revert.

---

## 1. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 2m** (114 executed / 831 up-to-date; `:app:compileReleaseKotlin` re-executat = modulele native editate au fost recompilate) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **248,8 MiB** (260 874 928 B) |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · schema v2 verificată · RSA 2048 · SHA-256 `fbbc618d…5184da` |
| Instalare | dispozitiv `9c1464eb` (CPH2663 / OnePlus Nord 4 / OP5E93L1) · `adb install -r -d` → **Success** (13s) |
| `lastUpdateTime` | **2026-09-07 09:32:48** (anterior 2026-08-31 07:51:05) · `firstInstallTime` neschimbat 2026-08-23 (date păstrate) · versionCode=1 versionName=1.0.0 |

---

## 2. Modificări, fișier cu fișier

### `modules/benson-audio-capture/android/.../BensonAudioCaptureModule.kt` — E1-3, E1-4 (~+8 linii nete)
- **E1-3:** `SILENCE_TIMEOUT_MS` **2500L → 800L**, constantă numită, valoarea veche în comentariu alături + notă de risc.
- **E1-4:** constantă nouă `EARLY_NO_SPEECH_STOP_MS = 3000L` + o ramură în bucla `runCapture()`, înainte de verificarea `MAX_DURATION_MS`: dacă `phase == "pre_speech"` și `elapsed > 3000ms` (deci `RMS_THRESHOLD` n-a fost depășit deloc), `finish(..., null, "no_speech", 0, peakRms)` și `return`. Elimină ciclurile `bytes=0 reason=max_duration` de 15s → 3s.
- Revert: `SILENCE_TIMEOUT_MS = 2500L` ; `EARLY_NO_SPEECH_STOP_MS = MAX_DURATION_MS`.

### `modules/benson-accessibility/android/.../BensonAccessibilityService.kt` — E1-1 Guardian (~+10 linii)
- Constantă nouă în companion: `GUARDIAN_BRING_ACTIVITY_TO_FRONT = false`.
- În `maybeResurrect()`: serviciul foreground se repornește ca înainte (mic + wake word se auto-repară tăcut), dar `wakeScreenForRecovery()` + `bringBensonToForegroundForRecovery()` rulează **doar dacă** constanta e `true`. Altfel: `Log.i(TAG, "Guardian: ... Activity NOT brought to front (E1-1)")`.
- Aceasta e rădăcina „BENSON se ridică singur" — resurecția anchor după un kill OxygenOS aducea `MainActivity` în față cu `FLAG_ACTIVITY_REORDER_TO_FRONT`, fără nicio atingere/rostire.
- Revert: `GUARDIAN_BRING_ACTIVITY_TO_FRONT = true`.

### `modules/benson-accessibility/android/.../BensonCommandExecutor.kt` — E1-6 (~+8 linii)
- Constatare: `performAction()`/`performGlobalAction()` care returnează `false` **deja** produceau `fail(..., "tap_rejected", ...)` peste tot aici — invariantul E1-6 („false nu mai produce succes") era deja respectat. Ce lipsea: urma în log.
- Helper nou `rejected(i, action, detail)` = `Log.i("BENSON_AUDIO", "ACTION_REJECTED action=… step=… detail=…")` + același `CommandResult(false, …, "tap_rejected", …)`.
- Înlocuit la cele 5 puncte de respingere post-`performAction`: `doClick`, `doSetText`, `doScroll`, `back`/`home`/`recents`.
- Revert: pune `rejected(...)` înapoi la `fail(..., "tap_rejected", ...)` (logul dispare, comportamentul e identic).

### `modules/benson-accessibility/android/.../DeclarativeAutomationEngine.kt` — E1-6 (~+4 linii)
- În `click()`: `performAction(ACTION_CLICK)` — dacă întoarce `false`, `Log.i("BENSON_AUDIO", "ACTION_REJECTED action=click engine=declarative")` înainte de a returna `false` (care oricum devenea `Result(false, id, "Target could not be safely clicked.")`).

### `modules/benson-foreground-service/android/.../BensonForegroundService.kt` — E1-1 (~+6 linii, doar `bringActivityToFront`)
- Constantă nouă în companion: `WAKE_AND_RECOVERY_BRING_TO_FRONT = false`.
- `bringActivityToFront()` gated la 2 puncte care **nu** pornesc de la user:
  - în `catch (SecurityException)` de la startul FGS microphone (recuperare);
  - în `onHotwordDetected()` la wake — bula + inelul (overlay-uri WindowManager) rămân ca indicator vizibil; ecranul nu mai e preluat.
- Punctul `ACTION_REVIVE` (butonul REVIVE din notificare = atingere directă) **neatins**.
- Revert: `WAKE_AND_RECOVERY_BRING_TO_FRONT = true`.

### `src/core/orchestrator/missionOrchestrator.ts` — E1-5 secvențiere (~+35 linii)
- `RunMissionOptions.onAck?: (shortText: string) => void` — nou.
- `E1_ACK_IMMEDIATE = true` (revert: `false`).
- `e1AckText(task)` → text scurt per tip de task: `NAVIGATE`→„Pornesc traseul.", `OPEN_APP`→„Deschid.", `PLAY_MEDIA`→„Pornesc.", `PREPARE_MESSAGE` (voice_call)→„O sun." / altfel „Trimit mesajul.", `PREPARE_CALL`→„Sun acum.".
- În `runPlanFrom()`, **înainte** de `executeTask()` (deci înainte de efectul Android), dacă task-ul chiar se execută acum (`confirmed || !task.requiresConfirmation`): `onAck(e1AckText(task))`. `executeTask()` `await`-uie lansarea; `onAck()` nu — merg în paralel.
- `resumePendingTask(pending, contacts, onAck?)` — al treilea parametru propagat spre `runPlanFrom` (ACK-ul „O sun." după „da").

### `src/core/mission/missionExecutor.ts` — E1-5 punctul de confirmare finală (~+10 linii)
- `E1_SHORT_FINAL_CONFIRM = true` (revert: `false`).
- `buildWaitingUserMessage()`: pentru `tool === 'waze'` (openApp + navigație), pe succes curat întoarce **`'Gata.'`** în loc de „Am solicitat deschiderea traseului în Waze." / „Am solicitat deschiderea Waze.". Ramurile de eșec și `opened_manual_action_required` (WhatsApp) rămân verbatim — onestitate.
- Combinat cu supresia din `finishHandledMission` (mai jos), confirmarea lungă de la final **nu se mai rostește**; „Gata." rămâne doar în transcriptul vizibil.

### `src/executors/appLauncherExecutor.ts` — E1-1 (~+10 linii)
- `E1_LAUNCH_NO_SELF_FOREGROUND = true` (revert: `false`).
- În `launchAllowlisted()`: `bringBensonToForeground()` + `waitForPackageForeground(BENSON_PACKAGE, 3000)` dinainte de lansare rulează **doar dacă** constanta e `false`. Altfel: `devLog('E1-1: skipping self-foreground + 3s pre-launch wait')`.
- Verificarea foreground a **aplicației țintă** de DUPĂ lansare e neatinsă — răspunsul onest (success / „nu pot verifica") e guvernat la fel ca înainte.
- `bringBensonBack()` (RETURN_TO_BENSON / CLOSE_APP — user a cerut explicit revenirea) **neatins**.
- Efect: se recuperează ~3s pe fiecare acțiune (`waitForPackageForeground(BENSON…) reached=false`).

### `lib/agents/voiceAgent.ts` — E1-3 (consistență, 2 linii)
- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` 1800→800, `..._POSSIBLY_..._MILLIS` 1200→600. **Doar** pentru motoarele `cloud`/`ondevice` (SpeechRecognizer). Motorul implicit `local` folosește constanta nativă (E1-3 de mai sus). Revert: 1800 / 1200.

### `app/index.tsx` — E1-0, E1-1 (JS), E1-2, E1-5 (wiring) (~+70 linii nete)
Constante noi (bloc L569–595): `E1_USER_ONLY = true`, `E1_NO_SELF_FOREGROUND = true`, `USER_ACTION_WINDOW_MS = 90000`; refs `lastUserActionAtRef` (start 0), `ackSpokenThisTurnRef`; helperi `noteUserAction()`, `userActionRecent()`, `e1SuppressSpeak()` (loghează `SPEAK_SUPPRESSED reason=no_user_command` și întoarce true dacă rostirea trebuie blocată).

| Loc | Schimbare |
|---|---|
| `speak()`, `speakText()` | poartă `e1SuppressSpeak()` la început — backstop pentru orice rostire fără acțiune-user recentă (`speakText` cheamă totuși `onFinished?.()` ca lanțul `doStartListening` să nu se blocheze) |
| `enterChatMode()` | sub `E1_USER_ONLY`: **fără `greet()`** — niciun salut, niciun mesaj în transcript, niciun TTS la pornire. Ascultarea pornește prin `phase='chat'` useEffect + self-heal (≤2s) |
| `handleWakeDetected()` | `noteUserAction()` sus (wake = acțiune-user); `bringToForeground()` gated de `!E1_NO_SELF_FOREGROUND`; wake gol → **doar chime + `doStartListening()`**, fără „Te ascult." rostit |
| `handleIncomingText()` | `noteUserAction()` + `ackSpokenThisTurnRef=false` + declară `onMissionAck` (voce → `speak(t)` scurt + marchează ACK; typed → `undefined`) |
| `handleMedallionTap()`, `toggleConvMode()`, `exitSilentMode()` | `noteUserAction()` (atingere directă) |
| `finishHandledMission()` | dacă `ackSpokenThisTurnRef && !isConfirming && !isFailureReply` → **nu rosti** mesajul final (`SPEAK_SUPPRESSED reason=ack_already_spoken`), doar reia ascultarea; altfel `speakText` ca înainte |
| ramura „da" la `pendingMissionTaskRef` | `resumePendingTask(..., onMissionAck)`; aceeași supresie a confirmării lungi pe succes curat |
| `runMission(msg, …)` × 2 (fast-path + brain bridge) | pasează `onAck: onMissionAck` |
| self-heal `listenHealTimer` | log `LISTEN_HEALED silent=true foreground=false reason=conv_idle` / `…reason=wake_idle` (comportamentul era deja tăcut + fără UI — doar `doStartListening()` / `resumePassiveWake()`) |
| EXEC_WATCHDOG (S8), STT-miss (S9), mission hydrate (S4), WaitingUser re-anunț (S5), a11y onDropped (S6), a11y reminder (S7), Guardian recovery (S2/S3) | fiecare: sub `E1_USER_ONLY` → `logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=…')` în loc de `speak()`. `addMessage()` (vizual) + bannerele + starea rămân |

---

## 3. Enumerarea punctelor de rostire/acțiune și ce le declanșează (E1-0 + E1-1)

Sursă: **U** = rostire recunoscută / atingere directă a userului · **T** = timer / callback de sistem / revenire în prim-plan / auto-reparare / pornire app.

### Rostiri (S1–S22) — toate în `app/index.tsx`
| # | Loc | Declanșator | Sursă | Acum |
|---|---|---|---|---|
| S1 | `greet()` ← `enterChatMode` | pornirea aplicației | T | **eliminat** (fără salut, fără mesaj) |
| S2 | Guardian recovery `speak(ACCESSIBILITY_DOWN…)` | după resurecție nativă | T | **suprimat** → `SPEAK_SUPPRESSED source=guardian_recovery` |
| S3 | Guardian recovery „BENSON a revenit online." | idem | T | **suprimat** |
| S4 | `hydrateActiveMission` re-rostește întrebarea | montare la pornire | T | **suprimat** (mesaj vizibil păstrat) |
| S5 | `AppState 'active'` → `WaitingUser` re-anunț | revenire în prim-plan | T | **suprimat** |
| S6 | a11y watchdog `onDropped` | poll 60s | T | **suprimat** (banner + notificare rămân) |
| S7 | a11y watchdog `onStatus` reminder | timer 5/15/30 min | T | **suprimat** (banner rămâne) |
| S8 | `EXEC_WATCHDOG` „Am rămas blocat…" | timer 30s pe `EXECUTING` | T | **suprimat** (mesaj vizibil + stare ERROR rămân) — *judecată: e feedback la o comandă-user, dar tot e timer; revert prin `E1_USER_ONLY=false`* |
| S9 | `endSub` „Nu am auzit nimic…" | sfârșit STT gol după tap/wake | T (eșec de recunoaștere) | **eliminat** — „Recunoaștere eșuată → zero reacție" |
| S10 | wake gol „Te ascult." | userul a spus „Benson" | U | **eliminat rostirea** — rămâne doar chime-ul non-verbal (Tăcerea e implicită) |
| S11 | `conversationFallbackLine` la excepție `routeCommand` | comandă recunoscută care a aruncat | U (nu e eșec STT) | **păstrat** — e downstream de o comandă reală |
| S12 | „need microphone access" | `doStartListening` fără permisiune | mixt | **automat**: suprimat dacă restart automat, rostit dacă tap-user (prin `userActionRecent()`) |
| S13 | `exitSilentMode` „Am revenit." | user a ieșit din silent | U | **păstrat** |
| S14 | unmute „Sonorul e pornit din nou." | user a dat unmute | U | **păstrat** |
| S15 | `toggleConvMode` `CONV_ON`/`CONV_OFF` | user a comutat | U | **păstrat** |
| S16 | comenzi Settings prin voce (rată, mut, șterge memoria…) | rostire recunoscută | U | **păstrat** |
| S17 | „Opening that…" la URL | user a rostit/tastat URL | U | **păstrat** |
| S18 | confirmări vinietă/notiță/mesaj | „da" al userului | U | **păstrat** |
| S19 | `finishHandledMission` rezultat/întrebare misiune | comandă vocală | U | **întrebarea de confirmare: păstrată** ; **rezultatul de succes: nu se mai rostește** dacă s-a dat ACK (E1-5) |
| S20 | creier: clarify / răspuns conversațional | comandă vocală | U | **păstrat** |
| S21 | rezultat `routeCommand` (chat/search) | comandă vocală/tastată | U | **păstrat** |
| S22 | preview-uri Settings (voce, rată, ton) | atingere în Settings | U | **păstrat** |

### Aduceri în prim-plan (F1–F12)
| # | Loc | Declanșator | Sursă | Acum |
|---|---|---|---|---|
| F1 | `app/index.tsx` notificare „LISTEN" → `bringToForeground()` | tap notificare | U | păstrat |
| F2 | `app/index.tsx` bulă → `bringToForeground()` | atingere bulă | U | păstrat |
| F3 | `app/index.tsx` `handleWakeDetected` → `bringToForeground()` | orice wake | U (voce) dar „din cod" | **eliminat** (`!E1_NO_SELF_FOREGROUND`) — bula/inel rămân |
| F4 | `appLauncherExecutor.ts` `launchAllowlisted` → `bringBensonToForeground()` + 3s wait | orice lansare de app | T | **eliminat** (`E1_LAUNCH_NO_SELF_FOREGROUND`) — +3s recuperați |
| F5 | `appLauncherExecutor.ts` `bringBensonBack()` | RETURN_TO_BENSON / CLOSE_APP | U | păstrat |
| F6 | `BensonForegroundService.kt:118` `bringActivityToFront()` — `ACTION_REVIVE` | buton REVIVE din notificare | U | păstrat |
| F7 | `BensonForegroundService.kt` `bringActivityToFront()` — `catch(SecurityException)` | eșec start FGS | T | **eliminat** (`WAKE_AND_RECOVERY_BRING_TO_FRONT`) |
| F8 | `BensonForegroundService.kt` `onHotwordDetected()` → `bringActivityToFront()` | wake nativ | U (voce) dar „din cod" | **eliminat** — `showBubbleNative()` + `showWakeRingNative()` rămân |
| F9 | `BensonForegroundService.kt:865` `startHotwordLoop()` (ramura fără listener JS) | wake fără bridge JS | T | neatins (nu e `bringActivityToFront`; e repornirea buclei mic — corect să rămână) |
| F10 | `BensonAccessibilityService.kt` Guardian `bringBensonToForegroundForRecovery()` | heartbeat FGS învechit (timer 60s) | T | **eliminat** (`GUARDIAN_BRING_ACTIVITY_TO_FRONT`) — serviciul FGS tot se repornește |
| F11 | `BensonAccessibilityService.kt` `startActivity` revenire post-automatizare WhatsApp | sfârșit automatizare cerută de user | U (downstream) | neatins (în afara „doar Guardian") — downstream de comandă |
| F12 | `BensonAccessibilityService.kt` `startActivity REORDER_TO_FRONT` ieșire din PiP / post-acțiune | sfârșit acțiune cerută de user | U (downstream) | neatins — downstream de comandă |

### Timere / watchdog / self-heal (T1–T10)
| # | Loc | Perioadă | Rostește? | Prim-plan? | Acum |
|---|---|---|---|---|---|
| T1 | `listenHealTimer` (`app/index.tsx`) | 2s | nu | nu | **păstrat** (E1-2), log `silent=true foreground=false` |
| T2 | phase='chat' useEffect | boot + 900ms | nu | nu | neatins (necesar ca să audă wake) |
| T3 | `AppState 'active'` WaitingUser re-anunț | la revenire | **da → suprimat (S5)** | nu | modificat |
| T4 | `AppState 'active'` resumePassiveWake/doStartListening | la revenire | nu | nu | neatins |
| T5 | `startAccessibilityWatch` | 60s | **da → suprimat (S6/S7)** | nu | modificat |
| T6 | `BensonWatchdogReceiver` AlarmManager | 60s | nu | indirect via F10 | neatins (nu e `bringActivityToFront` — e repornire FGS; F10 e tăiat) |
| T7 | `BensonHealthWorker` WorkManager | 15 min | nu | indirect | neatins (idem) |
| T8 | `scheduleBurstWatchdog` (nativ) | 8s | nu | nu | neatins |
| T9 | `restartBurst` + backoff | 0.4–20s | nu | nu | neatins |
| T10 | Guardian `maybeResurrect` | eveniment a11y + throttle 60s | nu | **da → eliminat (F10)** | modificat |

**Toate punctele care nu au ca sursă directă utilizatorul și care produceau o rostire sau o aducere în prim-plan sunt acum eliminate sau suprimate.** Repornirile de serviciu FGS / bucla mic (T6, T7, T9) rămân — acelea nu sunt „o acțiune sau o rostire", sunt condiția ca BENSON să te audă când vorbești; nu au fost cerute în scope („doar `bringActivityToFront`").

---

## 4. Anti-regresie — comportamente dovedite

| Comportament dovedit | Afectat de E1? | De ce nu se strică |
|---|---|---|
| Navigație Waze cu governance | mesajul final devine „Gata." (nerostit dacă s-a dat ACK) | governAction/execute/validare neatinse; doar `buildWaitingUserMessage` (string) + supresia TTS din `finishHandledMission`. Lansarea rulează la fel. |
| Deschidere aplicație după nume | +3s recuperați; nicio aducere self-foreground înainte | `launchPackage` + verificarea foreground a țintei de DUPĂ lansare neatinse |
| Propunere pentru cerere generică („o aplicație de radio") | nu | căile de disambiguare / `needsDisambiguationResult` neatinse; sunt `isConfirming` → tot se rostesc |
| Conversație liberă cu răspuns rostit | nu | S20/S21 sunt downstream de comandă → `userActionRecent()` true → se rostesc |
| Apel WhatsApp cap-coadă | ACK „O sun." înainte, confirmarea lungă de la final nu se mai rostește pe succes curat | poarta de confirmare („Confirmi?") + `resumePendingTask` + secvența de accessibility din `whatsappTool`/`BensonAccessibilityService` **neatinse**; ramurile `opened_manual_action_required` rămân verbatim |
| Microfon închis cât vorbește BENSON | nu | `beginTtsBlock`/`endTtsBlock`/`micResumeAtRef` neatinse |
| Index de aplicații 274 | nu | neatins |
| Microfonul se auto-repară după o acțiune (C1+C2), revenire fără atingerea medalionului | **întărit** | `listenHealTimer` păstrat (E1-2), tăcut, fără UI; `resumePassiveWake`/`doStartListening` neatinse |

**Riscuri reale de regresie (de urmărit la testul pe dispozitiv):**
1. **E1-3 (VAD 800ms)** contrazice direct fix-ul din 2026-07-30 („comanda tăiată în jumătate la o pauză naturală"). `PRE_ROLL_CHUNKS` + `RMS_THRESHOLD 700` sunt marja rămasă. Dacă apare din nou trunchierea → primul revert: `SILENCE_TIMEOUT_MS = 1600L` (compromis), apoi `2500L`.
2. **E1-4 (oprire la 3s pe pre_speech)** se aplică și capturii de comandă, nu doar scanării wake: un utilizator care începe să vorbească la > 3s după chime e tăiat. Revert punctual: `EARLY_NO_SPEECH_STOP_MS = 6000L`.
3. **E1-0 la boot în conv mode:** ascultarea pornește prin self-heal (≤2s), nu instant din callback-ul salutului. Dacă se simte lent → adaug `doStartListening()` explicit în `enterChatMode` sub `E1_USER_ONLY`.

---

## 5. Constante de revert (rezumat)

| Constantă | Fișier | Revert |
|---|---|---|
| `E1_USER_ONLY` | `app/index.tsx` | `false` → salut + toate rostirile la timer/revenire/auto-reparare revin, „Te ascult." revine |
| `E1_NO_SELF_FOREGROUND` | `app/index.tsx` | `false` → `bringToForeground()` la wake revine |
| `E1_LAUNCH_NO_SELF_FOREGROUND` | `src/executors/appLauncherExecutor.ts` | `false` → self-foreground + 3s wait pe calea de lansare revin |
| `E1_ACK_IMMEDIATE` | `src/core/orchestrator/missionOrchestrator.ts` | `false` → `onAck` nu se mai apelează niciodată |
| `E1_SHORT_FINAL_CONFIRM` | `src/core/mission/missionExecutor.ts` | `false` → mesajele lungi Waze revin |
| `GUARDIAN_BRING_ACTIVITY_TO_FRONT` | `BensonAccessibilityService.kt` | `true` → Guardian aduce iar Activity-ul în față |
| `WAKE_AND_RECOVERY_BRING_TO_FRONT` | `BensonForegroundService.kt` | `true` → `bringActivityToFront()` la wake + la recuperare revin |
| `SILENCE_TIMEOUT_MS` | `BensonAudioCaptureModule.kt` | `2500L` |
| `EARLY_NO_SPEECH_STOP_MS` | `BensonAudioCaptureModule.kt` | `= MAX_DURATION_MS` (dezactivează oprirea timpurie) |

---

## 6. Build + instalare

- `npx tsc --noEmit` → **0 erori**.
- `gradlew assembleRelease` (rulat cu `ANDROID_HOME` setat inline pentru comanda respectivă — fără `setx`, fără modificare permanentă; primul build a eșuat la config din lipsa `sdk.dir`/`ANDROID_HOME`, nu din cauza codului) → **BUILD SUCCESSFUL in 2m**.
- APK: `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk` · **260 874 928 B (~249 MiB)**.
- `apksigner verify --print-certs`: 1 semnatar · DN `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · APK Signature Scheme v2 = verified · cheie RSA 2048 · SHA-256 `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da`.
- `adb install -r -d` pe `9c1464eb` (CPH2663) → **Success**.
- `dumpsys package com.benson.butler`: `lastUpdateTime=2026-09-07 09:32:48` (era `2026-08-31 07:51:05`), `firstInstallTime` neschimbat → build-ul E1 e pe telefon, cu datele păstrate.

---

## 7. Acceptare pe dispozitiv — de rulat de tine

1. Deschizi altă aplicație, lucrezi în ea 2 minute. BENSON nu apare niciodată. **× 3.**
2. „Du-mă la aeroportul München" — de la sfârșitul vorbirii până când Waze e pe ecran: **< 4s. × 3.**
3. Navigație → BACK → comandă nouă. Te aude imediat, fără atingere. **× 5.**

Log-uri de verificat (`adb logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S`):
- `SPEAK_SUPPRESSED reason=no_user_command` la fiecare rostire blocată (boot, revenire, Guardian).
- `ACK text="Pornesc traseul."` imediat după comandă, apoi lansarea, apoi **fără** rostire de final.
- `LISTEN_HEALED silent=true foreground=false` la auto-repararea microfonului.
- `CAPTURE_ENDED ... reason=no_speech` la ~3s pe ciclurile de tăcere (nu `reason=max_duration` la 15s).
- `ACTION_REJECTED` când un tap accessibility e respins.
- `BRAIN_INTENT` / `CANONICAL` / `PARSE_RESULT` — neatinse, tot apar la fiecare comandă.
