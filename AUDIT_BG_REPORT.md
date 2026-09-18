# AUDIT READ-ONLY — Recipe execution & background behavior

Nimic modificat. Fișier nou: doar acesta.

---

## 1. Executive finding

Recipe step runner-ul de pe **calea activă** a apelului WhatsApp (`sună pe Hana pe WhatsApp`) este **JavaScript / React Native**. Secvențierea pas-cu-pas, deciziile „ce pas urmează" și toate așteptările dintre pași (`waitForNode`) rulează pe firul JS al RN, în funcția `runCallRecipe` din `src/core/mission/tools/whatsappTool.ts:812`. Doar primitivele individuale (`click` / `set_text` / `assert_package` / snapshot) rulează în Kotlin, în `BensonCommandExecutor` pe `serviceScope` (coroutine, `Dispatchers.Main` al procesului). Fiecare pas e un bridge call JS→Kotlin separat; între pași controlul se întoarce **întotdeauna** în JS.

Există și o rețetă complet nativă (`BensonAccessibilityService.placeWhatsAppCallInner`, `BensonAccessibilityService.kt:964`) — dar **NU e pe calea activă**: `USE_LEGACY_CALL_RECIPE = false` (`whatsappTool.ts:805`) → `placeCall` cheamă `runCallRecipe` (JS); comentariul `whatsappTool.ts:208` spune explicit „no native placeWhatsAppCall call". `whatsappTool.ts` nici nu importă `placeWhatsAppCall` din `benson-accessibility` (`whatsappTool.ts:8`).

**Consecință pentru logul dat:** după `RECIPE_STEP index=0 launch_app found=true` (produs de `runCallRecipe`, `whatsappTool.ts:817`), execuția intră imediat în `await waitForNode(...)` (`whatsappTool.ts:819`), care în bucla ei `for(;;)` face `await new Promise((r) => setTimeout(r, pollMs))` (`whatsappTool.ts:675`). WhatsApp tocmai a trecut în prim-plan → Activity-ul BENSON nu mai e `resumed` → `setTimeout`-urile RN nu se mai declanșează (comportament de platformă RN, coroborat de comentariile „dovedit pe dispozitiv" din `app/index.tsx:591-593, 1213-1216, 1279, 1288`) → `waitForNode` nu avansează → `RECIPE_STEP index=1` (`assert_package`) nu se mai emite. Niciun handler de `AppState` nu anulează rețeta — e o **înghețare a firului JS**, nu un abort din cod.

---

## 2. Recipe runner location

| Componentă | Fișier | Runtime |
|---|---|---|
| Bucla peste task-uri a misiunii | `src/core/orchestrator/missionOrchestrator.ts:347` (`for` în `runPlanFrom`) | JS |
| Rețeta de apel (secvența de pași 0–7) | `src/core/mission/tools/whatsappTool.ts:812` (`runCallRecipe`) | JS |
| Primitiva de așteptare între pași | `src/core/mission/tools/whatsappTool.ts:650` (`waitForNode`, `for(;;)` + `setTimeout`) | JS |
| Polling rezultate căutare (pas 4) | `src/core/mission/tools/whatsappTool.ts:854` (`while` + `setTimeout`) | JS |
| Executor de pași individuali | `modules/benson-accessibility/.../BensonCommandExecutor.kt:161` (`execute`, `for` peste `steps[]`) | Kotlin |
| `click` → tap | `BensonCommandExecutor.kt:271` (`doClick`) → `:301` `performAction(ACTION_CLICK)` | Kotlin |
| `assert_package` | `BensonCommandExecutor.kt:348` (`doAssertPackage`, `while` + `delay(200)`) | Kotlin |
| Snapshot ecran | `BensonAccessibilityService.kt:490` (`captureSnapshot`, `while` + `delay(150)`, `withContext(Dispatchers.Default)`) | Kotlin |
| Rețetă nativă alternativă (INACTIVĂ) | `BensonAccessibilityService.kt:964` (`placeWhatsAppCallInner`) | Kotlin |

**`RECIPE_EXECUTOR = HYBRID`** — sequencing JS, primitive Kotlin, o traversare bridge per pas.

---

## 3. Full call chain: `CONFIRM_EXECUTE_START` → `ACTION_CLICK`

| # | Fișier:linie | Funcție / metodă | Runtime | Tip apel |
|---|---|---|---|---|
| 1 | `app/index.tsx:3320` | `handleIncomingText` — `logAudioDiag('CONFIRM_EXECUTE_START', 'type=mission')` | JS | sync |
| 2 | `app/index.tsx:3321` | `setBensonState('EXECUTING', 'resume_task')` → log `STATE from=CONFIRMING to=EXECUTING` | JS | sync |
| 3 | `app/index.tsx:3327` | `resumeResult = await resumePendingTask(pending, await getLiveContacts(), onMissionAck)` (import din `../src/core/orchestrator`, `app/index.tsx:64`) | JS | **await / Promise** |
| 4 | `src/core/orchestrator/missionOrchestrator.ts:605` → `:614` | `resumePendingTask(...)` → `return runPlanFrom(plan, taskIndex, rawText, contacts, true, onAck)` | JS | async, return Promise |
| 5 | `src/core/orchestrator/missionOrchestrator.ts:347` | `runPlanFrom` — `for (let i = startIndex; i < plan.tasks.length; i++)` | JS | **for-loop JS** |
| 6 | `src/core/orchestrator/missionOrchestrator.ts:357` | `const outcome = await executeTask(task, plan, rawText, contacts, confirmed)` | JS | await |
| 7 | `src/core/orchestrator/missionOrchestrator.ts:264` → `:266` | `executeTask` — `const governed = toGovernedCall(task)` → `return runGovernedTask(governed, task, plan, contacts, confirmed)` | JS | async |
| 8 | `src/core/orchestrator/missionOrchestrator.ts:209-210` | `runGovernedTask` — `const request = buildGovernedRequest(...)` ; `const outcome = await executeGoverned(request, { confirmed }, contacts)` (aliasuri `buildActionRequest`/`execute` din `../mission`, `missionOrchestrator.ts:22-24`) | JS | await |
| 9 | `src/core/mission/missionExecutor.ts:70` | `export async function execute(request, options, contacts)` — gate confirmare sărit (`options.confirmed === true`, `:113`) | JS | async |
| 10 | `src/core/mission/missionExecutor.ts:141` | `mission = (await transitionMission('Running'))!` (persistă în `missionStore` / AsyncStorage) | JS | await |
| 11 | `src/core/mission/missionExecutor.ts:143` | `const result = await runTool(enrichedRequest)` | JS | await |
| 12 | `src/core/mission/missionExecutor.ts:169` → `:175` | `runTool` — `if (request.action === 'placeCall') return whatsappTool.placeCall(String(request.params.contactName ?? ''), uiLang)` (`import * as whatsappTool from './tools/whatsappTool'`, `:17`) | JS | async |
| 13 | `src/core/mission/tools/whatsappTool.ts:900` | `export async function placeCall(searchString, uiLang)` — `:904` `await ensureAccessibilityReady()` ; `:910` `setWhatsAppAutomationActive(true)` ; `:912-914` `return USE_LEGACY_CALL_RECIPE ? runTwoPhase(...) : await runCallRecipe(name)` (`USE_LEGACY_CALL_RECIPE = false`, `:805`) | JS | async |
| 14 | `src/core/mission/tools/whatsappTool.ts:815` | `runCallRecipe` **pas 0** — `const launch = await executeCommand({ steps: [{ action: 'launch_app', package: WHATSAPP_PACKAGE }] })` | JS | **bridge call** (Promise) |
| 15 | `src/core/mission/tools/whatsappTool.ts:817` | `logRecipeStep(0, 'lansare WhatsApp', 'launch_app', true, …)` → **`RECIPE_STEP index=0 … launch_app found=true`** | JS | sync |
| 16 | `src/core/mission/tools/whatsappTool.ts:819` | `const fg = await waitForNode([{ viewIdContains: 'com.whatsapp' }, …], 5000, 150)` | JS | await — **conține `setTimeout` la `:675`** |
| 17 | `src/core/mission/tools/whatsappTool.ts:821` | **pas 1** — `const inWa = await executeCommand({ steps: [{ action: 'assert_package', package: WHATSAPP_PACKAGE, timeoutMs: 4000 }] })` ; `:822` `logRecipeStep(1, …)` | JS | bridge call |
| 18 | `src/core/mission/tools/whatsappTool.ts:826` | **pas 2** — `const search = await waitForNode(SEARCH_ANCHORS, 4000, 150)` | JS | await (setTimeout) |
| 19 | `src/core/mission/tools/whatsappTool.ts:830` | `const searchClick = await executeCommand({ steps: [{ action: 'click', match: predicateToClickMatch(search.predicate), requirePackage: WHATSAPP_PACKAGE }] })` — **primul click** | JS | bridge call |
| 20 | `modules/benson-accessibility/index.js:124-126` | `export function executeCommand(command) { return NativeModule.executeCommand(JSON.stringify(command)) }` | JS→bridge | Expo AsyncFunction → Promise |
| 21 | `modules/benson-accessibility/.../BensonAccessibilityModule.kt:234` | `AsyncFunction("executeCommand") { commandJson, promise -> … }` | Kotlin | bridge entry |
| 22 | `BensonAccessibilityModule.kt:235-238` | `val svc = BensonAccessibilityService.instance` ; dacă `null` → `promise.resolve({success=false, status="invalid", detail="Accessibility Service is not running."})` | Kotlin | sync guard (static singleton) |
| 23 | `BensonAccessibilityModule.kt:246-247` | `svc.runOnServiceScope { val result = svc.executeCommand(command); promise.resolve(...) }` | Kotlin | coroutine launch |
| 24 | `BensonAccessibilityService.kt:86-88` | `fun runOnServiceScope(block) { serviceScope.launch { block() } }` ; `serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())` (`:45`) | Kotlin | coroutine, firul principal al procesului |
| 25 | `BensonAccessibilityService.kt:74-81` | `suspend fun executeCommand(command): CommandResult { whatsappAutomationActive = true; try { return commandExecutor.execute(command) } finally { whatsappAutomationActive = false } }` | Kotlin | suspend |
| 26 | `BensonCommandExecutor.kt:161` → `:171` | `suspend fun execute(command)` — `for (i in 0 until steps.length())` (aici: array de **1** element) | Kotlin | **for-loop Kotlin** |
| 27 | `BensonCommandExecutor.kt:181` | `"click" -> doClick(i, step)` | Kotlin | when-dispatch |
| 28 | `BensonCommandExecutor.kt:277` | `doClick` — `val found = waitForCandidates(timeout, requirePackage, match)` (`while` + `delay(POLL_INTERVAL_MS)`) | Kotlin | suspend, coroutine `delay` |
| 29 | `BensonCommandExecutor.kt:291-294` | `if (match.optBoolean("clickableAncestor", false) && !target.isClickable) target = findClickableAncestor(target)` | Kotlin | sync |
| 30 | `BensonCommandExecutor.kt:301` | `return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ok(i, "click") else rejected(...)` | Kotlin | **`AccessibilityNodeInfo.performAction(ACTION_CLICK)`** — IPC sincron către view-ul aplicației țintă |
| 31 | `BensonAccessibilityModule.kt:248-255` | `promise.resolve(mapOf("success" to result.success, …))` | Kotlin→bridge | rezolvă Promise-ul din pasul 19/20 |
| 32 | `src/core/mission/tools/whatsappTool.ts:831` | JS reia: `if (!searchClick.success) return stopNotFound('butonul de căutare')` — apoi pasul 3 etc. | JS | continuă `runCallRecipe` |

Punct de sincronizare între pași: **fiecare `await executeCommand(...)`** (`whatsappTool.ts:815, 821, 830, 846, 871, 891`) și **fiecare iterație `waitForNode`** (`whatsappTool.ts:658-676`, `853-859`) predau controlul înapoi firului JS al RN.

---

## 4. JS / background behavior

### 4a. React Native JS runtime în background

| Întrebare | Răspuns din implementarea BENSON | Dovadă |
|---|---|---|
| Continuă să ruleze? | **Procesul da** (foreground service + wake lock îl țin viu), dar **firul JS al RN este suspendat/„frozen" când Activity-ul nu mai e `resumed`** — pe acest dispozitiv, „dovedit pe dispozitiv". | `app/index.tsx:591-593` „activitatea RN nu mai e resumed → callback-ul de final TTS nu se declanșează … Simptom **dovedit pe dispozitiv**"; `app/index.tsx:1213-1216` „Android suspends the mic/JS timers while backgrounded"; `app/index.tsx:1279` „endSub never fired **while JS timers were suspended**"; `app/index.tsx:1288` „the **(frozen) activity thread**". Comentariu nativ contrar (intenție, nu efect): `BensonAccessibilityService.kt:664-667` „the RN JS thread gets **throttled** the moment its Activity loses foreground". |
| Poate fi throttled? | Da — vezi mai sus. Rundele **C1** (watchdog TTS, decuplare `resume_task`) și **C3** (curățare gate la revenirea în prim-plan) există exact pentru că firul JS îngheață la background. | `app/index.tsx:589-609` (bloc C1), `:611-…` (bloc C3), `ROUND_C1_REPORT.md`. |
| Promise-uri deja pornite? | Un `await executeCommand(...)` — rezolvarea vine prin `promise.resolve` nativ postat pe coada firului JS; **NU e demonstrabil din repo** dacă acea continuare rulează în background sau așteaptă revenirea (coada firului JS e vie, dar dispecerizarea RN a `.then` nu e vizibilă în cod). Un `await new Promise(r => setTimeout(r, ms))` — **nu se rezolvă** cât timp Activity-ul nu e `active` (vezi 4a rândul 2). | `whatsappTool.ts:675, 858` (singurele `setTimeout` de pe calea rețetei). |
| `setTimeout` / polling / loops ale rețetei? | `waitForNode` (`whatsappTool.ts:650`, `for(;;)` + `setTimeout` la `:675`) și bucla pas-4 (`:854`, `while` + `setTimeout` la `:858`) — **înghețate în background**. Bucla `for` din `runPlanFrom` (`missionOrchestrator.ts:347`) și lanțul `await` din `runCallRecipe` avansează doar cât timp firul JS rulează. | idem. |

**Cod care depinde de timer JS / event loop JS:** `runPlanFrom` (`missionOrchestrator.ts:336-393`), tot `runCallRecipe` (`whatsappTool.ts:812-898`), `waitForNode` / `waitForNodeGone` / `cappedSnapshot` (`whatsappTool.ts:629-698`), bucla pas-4 (`:854`), `handleIncomingText` însuși.
**Cod nativ după bridge call (independent de firul JS):** `BensonCommandExecutor.execute` + `doClick`/`doSetText`/`doAssertPackage`/`waitForCandidates` (`BensonCommandExecutor.kt`), `BensonAccessibilityService.captureSnapshot` (`:490`, chiar `withContext(Dispatchers.Default)`), `placeWhatsAppCallInner` (`:964`, dar inactiv). Toate rulează pe `serviceScope` (`Dispatchers.Main` al procesului) — coada Looper-ului firului principal continuă cât timp procesul are CPU (garantat de FGS + `PARTIAL_WAKE_LOCK`, `BensonForegroundService.kt:937`).

### 4b. AppState listeners

Trei `AppState.addEventListener('change', …)` în `app/index.tsx`:

| # | Fișier:linie | Funcție (context) | Ce face la `background` / `active` | Atinge execuția rețetei? |
|---|---|---|---|---|
| 1 | `app/index.tsx:960` | `useEffect` boot | `logAudioDiag('APP_STATE', state=${next})` — doar log | NU |
| 2 | `app/index.tsx:1217` | `appStateSub` (useEffect STT) | `next !== 'active'`: `isForegroundRef=false`, `pushBubbleBand`, `stopSpeaking`/`stopOpenAITTS`/`stopGeminiTTS`, `endTtsBlock('background')` (`:1229-1232`); dacă `convMode && sttEngine!=='local'`: `stopRecognition` + `closeSttSession('background')` + `resumePassiveWake` (`:1253-1258`); apoi `return`. `next === 'active'`: reset TTS (`:1271-1276`), `closeSttSession` (`:1282-1286`), **`C1_RESUME_DECOUPLED`**: dacă `resumeInFlightRef.current` sau `state===EXECUTING/resume_task` → `logAudioDiag('RESUME', source=foreground_return)` + **`setLoading(false); loadingRef.current=false`** + `setTimeout(resumeListeningAfterUnblock, 250)` (`:1294-1298`); curăță gate-uri „stale" > `PENDING_STALE_MS` (`:1300-1308`); repornește listening. | NU anulează/oprește `resumePendingTask`/`runCallRecipe`. Efect secundar: `:1296` scade `loadingRef` cât rețeta poate fi încă `await`-ată → redeschide buclele de re-armare STT (mic contention), **dar nu un abort**. |
| 3 | `app/index.tsx:1414` | `useEffect` mission-hydrate | `if (next !== 'active') return;` — la revenire, dacă `getActiveMission()?.state === 'WaitingUser'` re-anunță mesajul misiunii (cu cooldown). | NU |

Niciun listener nu apelează un abort/cancel pe `resumePendingTask`/`runMission`/`runCallRecipe`, nu setează niciun flag citit de `runCallRecipe`, nu respinge vreo Promise a rețetei.

**`RECIPE_EXECUTION_IS_INTERRUPTED_BY_APPSTATE = NO`** (din codul handler-elor). Întârzierea/pauza reală vine din înghețarea firului JS (4a), nu dintr-un handler.

---

## 5. `BensonForegroundService.kt`

### 5a. Rulează independent de `MainActivity`?

**DA.** `class BensonForegroundService : Service()` (`:45`). Manifest (`modules/benson-foreground-service/android/src/main/AndroidManifest.xml`): `android:foregroundServiceType="microphone"`, `android:exported="false"`. `onStartCommand` returnează **`START_STICKY`** (`:206`). `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` (`:178`). `acquireWakeLock()` → `PowerManager.PARTIAL_WAKE_LOCK` 12h (`:934-940`). Persistență în plus: AlarmManager repetat la 60s → `BensonWatchdogReceiver` (`:907-921`), `WorkManager` periodic 15min → `BensonHealthWorker` (`:83-90`), `BensonBootReceiver` pe `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` (manifest). Rămâne activ când Activity-ul intră în background — asta e chiar rațiunea lui (`:38-43`).

### 5b. Are acces direct la `BensonAccessibilityService`?

**NU.** `BensonForegroundService.kt` **nu importă** `BensonAccessibilityService`, nu are referință la `.instance`, niciun `bindService`, niciun `IBinder` (`onBind` returnează `null`, `:52`). Singurele componente pe care le atinge prin `ComponentName` explicit sunt din modulul **benson-overlay** (`expo.modules.overlay.BensonBubbleService`, `:885, :897`) și el însuși (acțiuni `ACTION_*`). Relaționează cu JS doar prin callback-uri statice (`onWakeWordDetected`, `onStopRequested`, `onListenRequested`, `:1023-1030`) setate de `BensonForegroundServiceModule`.

### 5c. Poate declanșa efectiv acțiuni Accessibility?

**`FOREGROUND_SERVICE_CAN_EXECUTE_ACCESSIBILITY = NO`.** În tot fișierul (1056 linii) nu există `find node`, `performAction`, `AccessibilityNodeInfo`, `rootInActiveWindow`, nici apel către executor/rețetă. Ce face: ține procesul viu (wake lock + FGS + START_STICKY + watchdog-uri), rulează bucla nativă de wake-word (SpeechRecognizer / Porcupine), afișează bula + inelul (overlay), tratează acțiunile din notificare, relează evenimentul „Benson" spre JS. A ține procesul viu (deci indirect și `BensonAccessibilityService` legat) **nu** e același lucru cu execuția pașilor unei misiuni.

---

## 6. `BensonAccessibilityService.kt`

### 6a. Rămâne viu când `MainActivity` intră în background?

**DA — ciclu de viață complet separat de Activity.** Manifest (`modules/benson-accessibility/android/src/main/AndroidManifest.xml`): `<service android:name=".BensonAccessibilityService" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="true">` cu `<intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>` și meta-data `@xml/accessibility_service_config`. Legat de **AccessibilityManagerService** (system_server), nu de Activity. `onServiceConnected()` (`:223`): `instance = this`, `connectionEpoch++`, `registerAcc1TestReceiver()`. `onUnbind` (`:368`), `onDestroy()` (`:408`): `serviceScope.cancel()`, `instance = null`, `connectionEpoch++`. Singleton static `@Volatile var instance` (`:114-116`), `serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())` (`:45`). Nicio referință la `MainActivity`; nu depinde de starea ei. Rămâne conectat cât timp userul îl are activat în Setări **și** OS-ul nu omoară procesul.

**Avertisment cunoscut (din cod, nu presupunere):** ColorOS/OxygenOS îl poate dezactiva silențios („confirmed live, repeatedly, this session" — `app/index.tsx:1447-1449`), iar o `SecurityException` la `startForeground` din FGS poate prăbuși întreg procesul și lua serviciul cu el (`BensonForegroundService.kt:162-175`). Asta e o defecțiune separată de cuplarea-cu-Activity — serviciul nu e cuplat de Activity.

### 6b. Cum primește comenzi?

| Cale | Fișier:linie | Sender | Receiver | Exemplu de acțiune |
|---|---|---|---|---|
| **Expo native module → AsyncFunction → `runOnServiceScope`** (calea de producție) | `BensonAccessibilityModule.kt:234` (`executeCommand`), `:93` (`getScreenSnapshot`), `:85` (`performClick`), `:104` (`performSetText`), `:117`(`goBack`), `:121`(`goHome`), `:125`(`openRecents`), `:141`(`placeWhatsAppCall`), `:156`(`endWhatsAppCall`), `:168`(`muteWhatsAppCall`), `:183`(`pressWhatsAppSend`), `:198`(`pressWhatsAppCallButton`), `:212`(`runAutomationProfile`) | JS: `modules/benson-accessibility/index.js` (`executeCommand` `:124`, `getScreenSnapshot` importat de `lib/screenBridge.ts:3`) apelat din `whatsappTool.ts` / `screenBridge.ts` | `BensonAccessibilityService.instance` (static) → `svc.runOnServiceScope { … }` (`:246`) | `svc.executeCommand(command)` → `BensonCommandExecutor.execute` → `doClick` → `performAction(ACTION_CLICK)` |
| **Funcție sync** (fără scope) | `BensonAccessibilityModule.kt:132` (`getForegroundPackage`) | JS `getForegroundPackage()` (`index.js:59`) | `BensonAccessibilityService.lastForegroundPackage` (static, `:143`) | citire diagnostic |
| **BroadcastReceiver dinamic** (DOAR diagnostic, adăugat în rundele ACC-1 / WA-FIX-1) | `BensonAccessibilityService.kt:237-268` (`registerAcc1TestReceiver`, acțiuni `com.benson.acc1.RUN` / `com.benson.wafix1.RUN`) | `adb shell am broadcast` | `serviceScope.launch { AccessibilityFoundationTest(this).run(...) }` | harness de test, nu calea produsului |
| **Evenimente OS** (nu „comenzi", dar mișcă starea) | `BensonAccessibilityService.kt:378` (`onAccessibilityEvent`) | AccessibilityManagerService | `lastForegroundPackage = packageName`, `emitScreenSnapshot`, `maybeResurrect` | ține cache-ul de noduri proaspăt pentru `waitForNode` |

**NU există:** BroadcastReceiver de manifest pentru comenzi de produs, Binder expus altor componente, event bus, apel din `BensonForegroundService`.

### 6c. Poate executa acțiuni fără ca JS bridge-ul să mai trimită următorul step?

**`YES` — dar numai în interiorul unui singur `executeCommand({steps:[…]})`.** `BensonCommandExecutor.execute` iterează nativ tot array-ul `steps` (`BensonCommandExecutor.kt:171`), inclusiv `{action:"wait", ms}` = `delay(...)` coroutine (`:180`). Deci un array multi-pas ar rula integral nativ, fără JS între pași.
**`NO` — pentru calea activă a apelului.** `runCallRecipe` trimite mereu array-uri de **1** element (`whatsappTool.ts:815, 821, 830, 846, 871, 891`), iar între ele face `waitForNode` în JS. Deci JS trebuie să trimită fiecare pas următor.
(`runTwoPhase`, `whatsappTool.ts:540`, grupează unii pași via `runPlainSteps` → `executeCommand({steps: [...multi...]})` `:508` — dar e `USE_LEGACY_CALL_RECIPE=false` pentru `placeCall`, și tot revine în JS între grupuri.)

---

## 7. Native independent execution paths

Există **trei** mașini de stări complet native pe `serviceScope`, fiecare cu `waitForNode` nativ (`BensonAccessibilityService.kt:804`, „native delay(), not a JS timer", `:799-801`) și `performAction(ACTION_CLICK)`:

| Funcție nativă | Fișier:linie | Loop / state machine | Activă pe calea `placeCall`? |
|---|---|---|---|
| `placeWhatsAppCallInner` | `BensonAccessibilityService.kt:964-1145` | Pași 0–7 secvențial, `recipeStepIndex` (`:869`), `waitForNode` per ancoră, retry-uri | **NU** — `USE_LEGACY_CALL_RECIPE=false` → `runCallRecipe` (JS); `whatsappTool.ts` nici nu importă `placeWhatsAppCall`; comentariu `whatsappTool.ts:208` „no native placeWhatsAppCall call" |
| `endWhatsAppCall` | `BensonAccessibilityService.kt:1152` | launch → `waitForNode(END_CALL_KEYWORDS)` → click → return | DA, dar doar pentru `endCall()` (`whatsappTool.ts:962-973`), nu pentru apelul inițial |
| `muteWhatsAppCall` | `BensonAccessibilityService.kt:1182` | launch → `waitForNode(MUTE_KEYWORDS)` → click → return | idem `muteCall()` |
| `pressWhatsAppCallButton` / `pressWhatsAppSend` | `BensonAccessibilityService.kt:1254` / `:1216` | un singur `waitForNode` + click | NU sunt importate/apelate din `whatsappTool.ts` (import la `:8` nu le include) |
| `DeclarativeAutomationEngine` | `BensonAccessibilityService.kt:49-55` (`profileAutomationEngine`), apelat prin `runAutomationProfile` (`:610`) | interpretor DSL de profil, nativ | NU pe calea apelului (comentariu `whatsappTool.ts:154-158`: „runs through the generic, JS-driven step-list executor … instead of the bundled declarative profile") |

**`NATIVE_RECIPE_LOOP = ABSENT`** pe calea activă a lui `sună pe … pe WhatsApp`. Bucla nativă `BensonCommandExecutor.execute` (`:171`) există dar primește doar array-uri de 1 pas de la `runCallRecipe`.

**Unde se întoarce controlul la JS între doi pași:** după fiecare `await executeCommand(...)` din `runCallRecipe` (`whatsappTool.ts:815 → 819`, `:821 → 826`, `:830 → 833`, `:846 → 852`, `:871 → 879`, `:891 → 894`) și în fiecare tur al buclelor `waitForNode` (`whatsappTool.ts:658, 675`) și pas-4 `while` (`:854, :858`).

---

## 8. Explicație exactă `launch_app` → următorul step (scenariul din log)

Log real: `CONFIRM_EXECUTE_START` → `STATE CONFIRMING → EXECUTING` → `RECIPE_STEP index=0 launch_app found=true` → `APP_STATE state=background`.

### A. Cine ar trebui să execute `RECIPE_STEP index=1`?

`runCallRecipe`, din `src/core/mission/tools/whatsappTool.ts` — firul JS al RN, continuând funcția `async` după pasul 0. Concret: `whatsappTool.ts:819` (`await waitForNode(...)`) → `:821` (`await executeCommand({ steps: [{ action: 'assert_package', … }] })`) → `:822` (`logRecipeStep(1, 'WhatsApp în prim-plan', 'assert_package', inWa.success, fg.elapsedMs)`).

### B. În ce runtime?

Secvențierea / decizia „pasul 1 începe acum": **JavaScript / React Native** (firul JS rulând `runCallRecipe`). Primitiva `assert_package` odată dispecerizată: **Kotlin** — `BensonCommandExecutor.doAssertPackage` (`BensonCommandExecutor.kt:348`), pe `serviceScope` (`Dispatchers.Main` al procesului).

### C. Prin ce funcție?

`whatsappTool.ts:819` `waitForNode` (`:650`) — iar în interior `cappedSnapshot` (`:629`) → `getScreenSnapshot` (`lib/screenBridge.ts:34`) → `NativeModule.getScreenSnapshot` → `BensonAccessibilityModule.kt:93` → `svc.runOnServiceScope { captureSnapshot() }` (`BensonAccessibilityService.kt:490`). Apoi `whatsappTool.ts:821` `executeCommand` (`:8` import) → `index.js:124` → `BensonAccessibilityModule.kt:234` → `BensonAccessibilityService.executeCommand` (`:74`) → `BensonCommandExecutor.execute` (`:161`) → `when(action){ "assert_package" -> doAssertPackage(i, step) }` (`:185`).

### D. Ce condiție trebuie îndeplinită pentru ca `index=1` să înceapă?

1. Promise-ul pasului 0 (`whatsappTool.ts:815`) trebuie să se fi **rezolvat înapoi în JS** — adică `BensonAccessibilityModule.kt:248` `promise.resolve(...)` să fi fost consumat de firul JS, iar `runCallRecipe` să treacă de `:817` în `:819`.
2. **Firul JS / event loop-ul RN trebuie să ruleze**, ca bucla `for(;;)` din `waitForNode` (`whatsappTool.ts:658`) să-și poată rula `await cappedSnapshot()` (`:659`) și `await new Promise(r => setTimeout(r, 150))` (`:675`) până când găsește nodul **sau** expiră la 5000ms (`:670`, verificat cu `Date.now()`).
3. `waitForNode` de la `:819` trebuie să returneze (found sau timeout) → abia atunci `:821` emite `executeCommand({assert_package})`.
4. `BensonAccessibilityService.instance != null` (`BensonAccessibilityModule.kt:235`), altfel `assert_package` se rezolvă imediat ca eșec „Accessibility Service is not running" (`:237`).

### E. Există ceva care poate opri/întârzia tranziția `index=0 → index=1` când Activity devine background?

**DA — `await new Promise((r) => setTimeout(r, pollMs))` din `waitForNode` (`whatsappTool.ts:675`)**, atins imediat după pasul 0 (`:819`), exact în momentul în care `launch_app` a adus WhatsApp în prim-plan și Activity-ul BENSON a devenit non-`resumed`.

- **Din cod, direct:** `runCallRecipe` nu are niciun `await executeCommand` batch pentru pașii 0→1; între ele stă `waitForNode` cu `setTimeout` (`whatsappTool.ts:675`). Bucla pas-4 (`:858`) la fel. Bucla `for` a misiunii (`missionOrchestrator.ts:347`) și lanțul `await` al `runCallRecipe` avansează doar cât timp firul JS rulează.
- **Din cod, ca observație „dovedit pe dispozitiv" (comentarii, nu execuție):** când Activity-ul nu mai e `resumed`, timerele/callback-urile JS ale RN se suspendă pe acest dispozitiv — `app/index.tsx:591-593`, `:1213-1216`, `:1279`, `:1288`; întreaga rundă C1 și `ROUND_C1_REPORT.md` există din cauza asta.
- **Efect:** `await`-ul de la `whatsappTool.ts:675` nu se rezolvă → `runCallRecipe` e „parcată" înainte să emită `executeCommand({assert_package})` de la `:821` → `RECIPE_STEP index=1` nu se loghează niciodată. Se deblochează abia la revenirea BENSON în prim-plan (`AppState → active`): `setTimeout`-urile RN repornesc, `waitForNode` reevaluează față de un `Date.now()` care a avansat în timpul înghețării → returnează `timeout` → rețeta continuă — acum poate cu WhatsApp deja nu mai e în prim-plan.
- Niciun `AppState` handler nu face abort. `app/index.tsx:1296` (`setLoading(false); loadingRef.current=false`) doar redeschide buclele de re-armare STT în timp ce rețeta e încă `await`-ată — contention pe microfon, dar nu oprire a rețetei.

Efectul TTS colateral (context C1): `runPlanFrom` cheamă `onAck` **înainte** de `executeTask` (`missionOrchestrator.ts:353-356`) → în JS `onMissionAck` → `speak("O sun.")` (`app/index.tsx:3128-3130`) → `beginTtsBlock()` ridică `speakingRef`. Când WhatsApp trece în prim-plan, callback-ul de final TTS (JS) nu se declanșează → `speakingRef` rămâne `true` (fixat de watchdog-ul C1). Nu blochează rețeta în sine, dar blochează repornirea microfonului.

---

## 9. Proven facts (din cod)

1. Calea activă a apelului: `handleIncomingText` → `resumePendingTask` → `runPlanFrom` (`for` JS) → `executeTask` → `runGovernedTask` → `executeGoverned`/`execute` → `runTool` → `whatsappTool.placeCall` → **`runCallRecipe` (JS)**. Fișiere/linii în §3.
2. `runCallRecipe` secvențiază pașii în JS și trimite câte **un** pas per `executeCommand` (`whatsappTool.ts:815, 821, 830, 846, 871, 891`).
3. Așteptarea dintre pași = JS `setTimeout` (`whatsappTool.ts:675`, `:858`). Nicio primitivă `wait`/`delay` nativă pe calea `runCallRecipe`.
4. `RECIPE_STEP` din log e emis de JS (`whatsappTool.ts:701`, `anchor="launch_app"`), nu de nativ (`BensonAccessibilityService.kt:871`, care ar folosi `anchor="whatsapp_window"`).
5. Primitivele (`click`, `set_text`, `assert_package`, snapshot) rulează în Kotlin pe `serviceScope` (`Dispatchers.Main` proces), cu bucle `while`/`delay` coroutine (`BensonCommandExecutor.kt:348-368`, `BensonAccessibilityService.kt:804-824`) — supraviețuiesc înghețării firului JS **în interiorul unui singur apel**.
6. `BensonAccessibilityService` are ciclu de viață independent de `MainActivity` (manifest `BIND_ACCESSIBILITY_SERVICE` + `onServiceConnected`/`onDestroy` + singleton static `instance`; `BensonAccessibilityService.kt:114-116, 223-230, 408-415`).
7. `BensonForegroundService` **nu** are nicio referință la `BensonAccessibilityService` și **nu execută** nicio acțiune de accesibilitate (fișier întreg, 1056 linii — `modules/benson-foreground-service/.../BensonForegroundService.kt`). Rol: proces viu + wake-word nativ + overlay-uri + notificare.
8. Rețeta complet nativă `placeWhatsAppCallInner` (`BensonAccessibilityService.kt:964`) **nu e pe calea activă** (`USE_LEGACY_CALL_RECIPE=false`, `whatsappTool.ts:805`; import fără `placeWhatsAppCall`, `:8`; comentariu `:208`).
9. Niciunul din cele 3 `AppState.addEventListener` (`app/index.tsx:960, 1217, 1414`) nu anulează / nu oprește / nu setează un flag verificat de `resumePendingTask`/`runCallRecipe`.
10. Comenzile către `BensonAccessibilityService` vin exclusiv prin `BensonAccessibilityModule` (Expo bridge, JS-originat) + receiverul de diagnostic ACC-1/WA-FIX-1 (`BensonAccessibilityService.kt:237-268`).

---

## 10. Not proven from code

1. **Mecanismul exact al suspendării timerelor JS RN în background** (RN `Timing`/`ReactChoreographer` `onHostPause`, gating pe frame-callback) — e intern React Native, nu apare în acest repo. Repo-ul conține doar *efectul observat*, în comentarii marcate „dovedit pe dispozitiv" (`app/index.tsx:591-593` etc.). `NOT PROVEN FROM CODE` — dovada ar fi un trace `adb logcat` cu timestamp pe `WAIT_NODE` / `RECIPE_STEP` / `APP_STATE`, arătând că polling-ul `waitForNode` se oprește exact la `APP_STATE state=background` și repornește la `state=active`.
2. **Dacă o continuare `await` a unei Promise rezolvate nativ (`promise.resolve` din `executeCommand`) rulează sau nu în background** (independent de `setTimeout`) — nedeterminabil din repo. Simptomul din log (blocaj înainte de `RECIPE_STEP index=1`) e consistent cu înghețarea *doar* a `setTimeout`, fiindcă primul `setTimeout` de după pasul 0 e chiar `waitForNode` la `whatsappTool.ts:819/675`. Testul: injectează un `logAudioDiag` imediat după `await executeCommand` la `:821` și vezi dacă apare cât timp e background.
3. **Dacă `PARTIAL_WAKE_LOCK` (`BensonForegroundService.kt:937`) chiar menține „JS/recognition timers" (cum pretinde comentariul `:42-43`)** — comentariul e o *intenție/afirmație de developer*; comentariile C1/C3 arată efectul contrar observat. Un wake lock parțial împiedică doar somnul CPU/Doze, nu pauza timerelor RN la `onHostPause`. `NOT PROVEN FROM CODE` în sensul „nu se poate arăta din repo că funcționează"; se poate arăta din repo doar că *nu funcționează* (comentariile „dovedit pe dispozitiv").
4. **Dacă `BensonAccessibilityService` chiar rămâne conectat pe acest dispozitiv când Activity-ul e background** — din cod, ciclul lui de viață nu depinde de Activity (§6a). Din realitate, ColorOS îl dezactivează des (comentarii `app/index.tsx:1447-1449`, `BensonForegroundService.kt:162-175`). Care din cele două se întâmplă la un run anume — trace runtime (`dumpsys accessibility` + logcat `onServiceConnected`/`onDestroy` în jurul lui `APP_STATE state=background`).

---

## 11. Final verdict

`RECIPE_EXECUTOR = HYBRID`
> Secvențiere + „next step" + așteptări între pași = JS/RN (`src/core/mission/tools/whatsappTool.ts:812` `runCallRecipe`, `src/core/orchestrator/missionOrchestrator.ts:347` `runPlanFrom`, `whatsappTool.ts:650` `waitForNode`). Primitivele (`click`/`set_text`/`assert_package`/snapshot) = Kotlin pe `serviceScope` (`BensonCommandExecutor.kt`, `BensonAccessibilityService.kt:490`). Un bridge call per pas.

`JS_SURVIVES_BACKGROUND = PARTIAL` (efectul), `NOT_PROVEN_FROM_CODE` (mecanismul)
> Procesul supraviețuiește (FGS + `PARTIAL_WAKE_LOCK`, `BensonForegroundService.kt:178, 937`; `START_STICKY` + watchdog-uri). Firul JS al RN și `setTimeout`-urile lui se suspendă când Activity-ul nu mai e `resumed` — afirmat „dovedit pe dispozitiv" în cod (`app/index.tsx:591-593, 1213-1216, 1279, 1288`), dar mecanismul RN nu e în repo. Coroutinele native de pe `serviceScope` (`Dispatchers.Main` proces) continuă *în interiorul unui singur* `executeCommand`.

`ACCESSIBILITY_INDEPENDENT_OF_ACTIVITY = YES`
> `BensonAccessibilityService` — bound service al OS-ului (`android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, manifest modul), singleton static `instance` (`BensonAccessibilityService.kt:114-116`), `serviceScope` propriu (`:45`), `onServiceConnected`/`onDestroy` (`:223, :408`), zero referință la `MainActivity`. (Caveat operațional, nu de cuplare: ColorOS îl poate omorî — `app/index.tsx:1447-1449`.)

`NATIVE_RECIPE_LOOP = ABSENT` (pe calea activă)
> `runCallRecipe` (JS) conduce pașii; `BensonCommandExecutor.execute` (`BensonCommandExecutor.kt:171`) buclează nativ dar primește doar array-uri de 1 pas. Rețeta nativă `placeWhatsAppCallInner` (`BensonAccessibilityService.kt:964`) există dar e dezactivată (`USE_LEGACY_CALL_RECIPE=false`, `whatsappTool.ts:805`; comentariu `:208`). Controlul revine la JS după fiecare `await executeCommand` din `whatsappTool.ts:815/821/830/846/871/891` și în fiecare tur `waitForNode`.

`NEXT_STEP_AFTER_LAUNCH_IS_TRIGGERED_BY = runCallRecipe (src/core/mission/tools/whatsappTool.ts:819→821), pe firul JS al RN — anume await waitForNode(...) apoi await executeCommand({steps:[{action:'assert_package'}]}) după ce Promise-ul pasului 0 (:815) se rezolvă. Condiție: event loop-ul JS activ (Activity `active`), altfel setTimeout-ul din waitForNode (whatsappTool.ts:675) nu se declanșează.`

`BACKGROUND_CAN_STOP_RECIPE_FROM_CODE = PARTIAL`
> Nu prin cod explicit — niciun `AppState` handler (`app/index.tsx:960/1217/1414`) nu anulează rețeta. Dar rețeta depinde de `setTimeout` JS (`whatsappTool.ts:675, 858`) și de bucla `for` JS a misiunii (`missionOrchestrator.ts:347`); trecerea în background îngheață firul JS (§4a, „dovedit pe dispozitiv") → rețeta se **pauzează** înainte de `RECIPE_STEP index=1` și se reia la revenirea în prim-plan, cu timing învechit. În plus `app/index.tsx:1296` scade `loadingRef` cât rețeta e încă `await`-ată → redeschide re-armarea STT (contention), fără abort.

---

## 12. Ce test runtime ar rezolva punctele „NOT PROVEN"

1. `adb logcat -c` → declanșează fluxul → `adb logcat BENSON_AUDIO:I ReactNativeJS:I BensonCmdExec:I *:S` și verifică timestamp-urile: dacă între `APP_STATE state=background` și `APP_STATE state=active` NU apare niciun `WAIT_NODE` / `RECIPE_STEP` / `SNAPSHOT`, iar imediat după `state=active` apar în rafală — firul JS a fost înghețat (confirmă §4a, §8E).
2. `adb shell dumpsys accessibility | grep -i benson` + logcat `BensonA11y` în jurul lui `state=background` — dacă `onDestroy` / `serviceConnected=false` apare, serviciul a picat (ColorOS), nu doar firul JS (departajează §10.4).
3. Un `logAudioDiag('BRIDGE_RESOLVED', 'step=1')` imediat după `await executeCommand` la `whatsappTool.ts:821` (temporar) — dacă apare cât timp e background, continuările de Promise rezolvate nativ NU sunt înghețate, doar `setTimeout` e (departajează §10.2).
