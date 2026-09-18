# AUDIT READ-ONLY — Confirmation / Orchestration Flow

**Nimic modificat.** Doar inspecție cod + corelație cu logul. Fișier nou: doar acesta.

---

## 1. Executive finding

După `STT_RESULT text="Confirme."`, BENSON **nu execută** misiunea pending pentru că textul recunoscut **nu se potrivește cu `YES_PATTERN`**. Regex-ul de acceptare (`app/index.tsx:127`) este:

```js
const YES_PATTERN = /\b(da|yes|sigur|sure|ok|okay|pregate|pregăte[sș]te|confirm[aă]?t?)\b/i;
```

`confirm[aă]?t?\b` cere o graniță de cuvânt **imediat după** `confirm` + `a/ă?` + `t?`. În „Confirme" urmează litera „e" → **nu există `\b`** între „m" și „e" → **`YES_PATTERN.test("Confirme.") === false`**. La fel eșuează: `"Confirmi"` (exact cuvântul din întrebarea „Confirmi?"), `"Confirmați"`, `"confirmarea"`, `"confirmez"`. Acceptă doar: `confirm`, `confirma`, `confirmă`, `confirmat`.

Consecință în lanț:

1. `addMessage('user', "Confirme.")` → `setBensonState('THINKING')` **necondiționat** (`app/index.tsx:1757`) → logul `STATE from=CONFIRMING to=THINKING`.
2. Ramura `pendingMissionTaskRef` își **consumă** referința (`ref.current = null`, `app/index.tsx:3173`) **înainte** de a testa `YES_PATTERN` → `YES_PATTERN` eșuează → misiunea pending se pierde din acest ref.
3. Ramura Mission Governance (`getActiveMission().state === 'WaitingConfirmation'`, `app/index.tsx:3245`): `YES_PATTERN` eșuează; `"Confirme."` are 1 cuvânt și nu e `NO_PATTERN` → cade pe `else` (`app/index.tsx:3270-3272`): `setLoading(false); return;` — **fără nicio tranziție de stare, fără execuție, misiunea rămâne `WaitingConfirmation`**.
4. `endSub` (`app/index.tsx:1076-1092`) vede `expectingReply = getActiveMission().state === 'WaitingConfirmation' === true` → re-armează ascultarea: `setTimeout(700) → doStartListening()` → `STT_REQUESTED trigger=conversation_mode`.
5. `no_speech` (sau alt „Confirme." înghițit identic) → `endSub` din nou → pasul 4 → **buclă infinită, fără re-prompt, fără execuție**.
6. BENSON `MainActivity` rămâne `topActivity` pentru că `confirmActiveMission()` (care lansează WhatsApp) nu e apelat niciodată — funcția a returnat la pasul 3, înainte de linia 3249.

Nu e race. Nu e execuție ne-await-uită. Este **matcher de confirmare care ratează** + **gate-uri care își consumă starea înainte de validare** + o **stare lăsată în THINKING**.

---

## 2. Actual call graph (ce se întâmplă azi cu „Confirme.")

```
addResultListener cb  (app/index.tsx:933 "resultSub")
  isFinal → logAudioDiag('STT_FINAL')                                    [~935]
  !looksLikeSelfEcho → logAudioDiag('TRANSCRIPT_ACCEPTED')               [~961]
  scheduleAssembledDispatch(transcript)                                  [967]
    └─ pendingAssemblyRef + setTimeout(FRAGMENT_ASSEMBLY_WINDOW_MS)      [~1800]
         └─ handleIncomingText("Confirme.", {viaVoice:true, utteranceBytes})   [~1807]  (NU e await-uit — vezi §7)

handleIncomingText(msg, opts)                                            [app/index.tsx:3072]
  ├─ [3097] if (opts.viaVoice && YES_PATTERN.test(msg) && !NO_PATTERN.test(msg))
  │         → YES_PATTERN.test("Confirme.") === FALSE → BLOC SĂRIT (nu se verifică MIN_CONFIRM_BYTES)
  ├─ [3111] addMessage('user', msg)
  │         └─ [1757] if (role==='user') setBensonState('THINKING'); return;   ► LOG: STATE from=CONFIRMING to=THINKING
  ├─ [3112] setLoading(true); loadingRef.current = true;
  ├─ [3121] try {
  ├─ [3134] pendingVignetteRef.current? … (null → skip)   [dar ar face ref=null @3136 înainte de YES check]
  ├─ [3147] pendingNoteActionRef.current? … (null → skip) [idem @3149]
  ├─ [3171] if (pendingMissionTaskRef.current) {
  │           [3172] const pending = pendingMissionTaskRef.current;
  │           [3173] pendingMissionTaskRef.current = null;     ► PENDING CONSUMAT ÎNAINTE DE VALIDARE
  │           [3174] if (YES_PATTERN.test("Confirme.")) { …resume… }   → FALSE
  │           [3237] // "Anything else — drop the pending mission task and handle the message normally."
  │         }
  ├─ [3245] const governedMission = getActiveMission();
  │         if (governedMission?.state === 'WaitingConfirmation') {
  │           [3247] if (YES_PATTERN.test("Confirme.")) { setBensonState('EXECUTING'); await confirmActiveMission(); … }  → FALSE
  │           [3266] wordCount = 1
  │           [3267] if (NO_PATTERN.test(msg) || wordCount >= 3) { await cancelActiveMission(); /* fall through */ }  → FALSE
  │           [3270] else { setLoading(false); loadingRef.current = false; return; }   ► RETURN AICI
  │         }
  └─ (restul funcției — brain/orchestrator — NU se atinge pe varianta "governed")

endSub  (app/index.tsx:984 "addEndListener")   — la sfârșitul fiecărei sesiuni STT
  [1079] expectingReply = !!( pendingMissionTaskRef.current || pendingNoteActionRef.current
           || pendingVignetteRef.current
           || getActiveMission()?.state === 'WaitingConfirmation'      ► ÎNCĂ true
           || getActiveMission()?.state === 'WaitingUser' )
  [1084] if (expectingReply && convModeRef.current && isForegroundRef.current && !loadingRef.current && !speakingRef.current)
  [1085]   setTimeout(700, () => { if (…!listeningRef.current) doStartListening(); })

doStartListening()  (app/index.tsx:2641)
  [2682] const trigger = wakeTriggeredRef.current ? 'wake_word' : convModeRef.current ? 'conversation_mode' : 'manual_tap'
  [2714] logAudioDiag('STT_REQUESTED', `session=… trigger=conversation_mode component=js_stt`)   ► LOG
  → startRecognition(…) → (user tace) → captureEnd reason=no_speech → errorSub 'no-speech' → endSub → BUCLĂ
```

Sursă secundară identică de re-armare (poate produce aceeași linie de log): **self-heal loop** `setInterval(LISTEN_SELF_HEAL_MS)` (`app/index.tsx:1314-1332`): `if (convModeRef.current && !listeningRef.current && !wakeTriggeredRef.current && !loadingRef.current && !speakingRef.current) → logAudioDiag('LISTEN_HEALED') ; doStartListening()`.

### Varianta „non-governed" (misiune multi-step fără backing în `src/core/mission`)
Dacă la [3245] nu există governed mission `WaitingConfirmation`, funcția **nu** returnează la 3272; continuă:
```
[3287] trySettingsVoiceCommand("Confirme.") → false
[3293] tryStoreFact("Confirme.")            → false
[3302] CALL_PATTERN.test                    → false
[3319] logAudioDiag('ORCHESTRATOR_HANDOFF_REQUESTED', text="Confirme.")
[3380] missionResult = await runMission("Confirme.", …)  → handled=false
[3393] brainOut = await routeThroughBrain({utterance:"Confirme.", …})  → kind:'speak' (conversație)
[3454] addMessage('benson', brainOut.text)  → [1763-1764] state THINKING → setBensonState('DONE'|'ERROR','reply')
[3457] speakText(brainOut.text, () => { if (convModeRef.current) { doStartListening(); return; } })  ► STT_REQUESTED trigger=conversation_mode
```
Aici `pendingMissionTaskRef` a fost deja `null` (consumat la 3173) și **nu există** governed mission care să-l recupereze → **misiunea e pierdută definitiv**.

**Discriminator în log:** dacă apar `ORCHESTRATOR_HANDOFF_REQUESTED` + `BRAIN_INTENT` + `ROUTE decision=conversation` + `STATE from=THINKING to=DONE` ⇒ varianta non-governed (misiune pierdută). Dacă NU apar și starea rămâne blocată pe `THINKING` ⇒ varianta governed (deadlock, recuperabil doar cu un „da"/„confirm" exact).

---

## 3. Expected call graph (ce ar trebui să se întâmple)

```
CONFIRMING  (gate armat, pending action stocat o singură dată)
  └─ utterance sosește
       └─ classifier(utterance) → { verdict: yes | no | other }
            ├─ yes  → state = EXECUTING
            │          result = await executePending()          (await complet, un singur executor)
            │          state = VERIFYING → (verificarea rețetei) → DONE / ERROR
            │          state = LISTENING           (auto-listen reia DOAR de aici)
            ├─ no   → cancelPending(); state = LISTENING
            └─ other→ pending action RĂMÂNE; re-prompt scurt ("Spune «da» sau «nu».");
                       state = CONFIRMING; ascultare re-armată pentru încă un tur de dialog
```

În timpul `EXECUTING` și `VERIFYING`, orice cale de auto-listen (`endSub` re-arm, self-heal interval, `speakText` onFinished) **trebuie blocată**, exceptând un flag explicit „mai e nevoie de un tur de dialog" (confirmare pas 2 dintr-o misiune multi-step).

---

## 4. Source files involved

| Fișier | Rol | Linii cheie |
|---|---|---|
| `app/index.tsx` | matcher de confirmare, `handleIncomingText`, state machine, buclele de re-armare | `127` (YES_PATTERN), `131` (NO_PATTERN), `1757` (addMessage→THINKING), `1076-1092` (endSub re-arm), `1314-1332` (self-heal), `2641` (doStartListening), `2682/2714` (trigger + STT_REQUESTED), `3072` (handleIncomingText), `3097-3109` (empty-audio guard), `3134-3167` (vignette/note gates), `3171-3238` (pendingMissionTaskRef gate), `3245-3283` (Mission Governance gate), `3315-3386` (orchestrator fast-path), `3388-3472` (brain path) |
| `src/core/orchestrator/missionOrchestrator.ts` | produce `pendingTask` când un task cere confirmare | `63` (`pendingTask?` în tip), `199-234` (`runGovernedTask` → `WaitingConfirmation` → `waiting:true`), `336-371` (`runPlanFrom` → `return { …, pendingTask: { plan, taskIndex:i } }` @369), `605` (`resumePendingTask`) |
| `src/core/orchestrator/index.ts` | re-export `runMission`, `resumePendingTask` | import la `app/index.tsx:64` |
| `src/core/mission/` (barrel) | `getActiveMission`, `confirmActiveMission`, `cancelActiveMission`, `resolveActiveMissionFromUtterance` — starea `WaitingConfirmation` guvernată, backing AsyncStorage | import la `app/index.tsx:69-71`; folosit la `1081`, `3101`, `3245`, `3249`, `3268`, `3275` |
| `src/core/mission/tools/whatsappTool.ts` | `runCallRecipe` — pasul care ar rula DUPĂ confirmare | neatins de acest defect (nu se ajunge la el) |

---

## 5. Timeline mapped to logs

| # | Log event | File | Function | Line | Condiție | Next call |
|---|---|---|---|---|---|---|
| 1 | `STT_RESULT` / `STT_FINAL text="Confirme."` | `app/index.tsx` | `resultSub` (`addResultListener` cb) | ~935 | `isFinal === true` | `scheduleAssembledDispatch(transcript)` @967 |
| 2 | `TRANSCRIPT_ACCEPTED` | `app/index.tsx` | `resultSub` | ~961 | `!looksLikeSelfEcho(transcript)` | `scheduleAssembledDispatch` → timer |
| 2b | (asamblare fragmente) | `app/index.tsx` | `scheduleAssembledDispatch` | ~1795-1808 | `FRAGMENT_ASSEMBLY_WINDOW_MS` expirat | `handleIncomingText("Confirme.", {viaVoice:true})` **(fire-and-forget)** |
| 3 | *(nimic — gard sărit)* | `app/index.tsx` | `handleIncomingText` | 3097 | `YES_PATTERN.test("Confirme.") === false` | continuă |
| 4 | `STATE from=CONFIRMING to=THINKING` | `app/index.tsx` | `addMessage('user')` → `setBensonState` | 3111 → 1757 | `role === 'user'` (necondiționat) | `setLoading(true)` @3112 |
| 5 | *(pending consumat)* | `app/index.tsx` | `handleIncomingText` | 3172-3173 | `pendingMissionTaskRef.current` truthy | `ref.current = null`; `YES_PATTERN` false @3174 → fall-through @3237 |
| 6 | *(governed: return devreme)* | `app/index.tsx` | `handleIncomingText` | 3245-3272 | `getActiveMission().state==='WaitingConfirmation'`; `YES` false; `NO` false; `wordCount(1) < 3` → `else` | `setLoading(false); return;` — stare rămâne `THINKING`, misiune rămâne `WaitingConfirmation` |
| 7 | `STT_STOPPED` apoi `STT_REQUESTED trigger=conversation_mode` | `app/index.tsx` | `endSub` re-arm | 1079-1090 | `expectingReply` (governed still `WaitingConfirmation`) && `convModeRef` && `isForegroundRef` && `!loadingRef` && `!speakingRef` | `setTimeout(700) → doStartListening()` |
| 7' | *(alternativ, aceeași linie)* | `app/index.tsx` | `listenHealTimer` (`setInterval`) | 1314-1323 | `convModeRef && !listeningRef && !wakeTriggeredRef && !loadingRef && !speakingRef` | `logAudioDiag('LISTEN_HEALED')`; `doStartListening()` |
| 8 | `STT_REQUESTED trigger=conversation_mode` | `app/index.tsx` | `doStartListening` | 2682, 2714 | `!wakeTriggeredRef.current && convModeRef.current` → `trigger='conversation_mode'` | `startRecognition(...)` |
| 9 | `no_speech` (sau alt „Confirme.") | native → `voiceAgent` → `errorSub`/`endSub` | — | ~974/984 | user tace | `endSub` → pasul 7 (**buclă**) |
| 10 | BENSON `MainActivity` rămâne top/focused | `src/core/mission` | `confirmActiveMission` | — | **nu e apelat** (return la pasul 6 înainte de linia 3249) | WhatsApp `launch_app` nu rulează niciodată |

---

## 6. Root cause

**Primar — `CONFIRMATION_HANDLER_BUG`:**
`YES_PATTERN` (`app/index.tsx:127`) nu recunoaște forma flexionară transcrisă de STT. `\b(...|confirm[aă]?t?)\b` acceptă `confirm|confirma|confirmă|confirmat`, dar **respinge** `confirme`, `confirmi`, `confirmați`, `confirmarea`, `confirmez` — inclusiv exact cuvântul din propriul prompt „Confirmi?". Toate cele 5 gate-uri de confirmare (`3097`, `3137`, `3150`, `3174`, `3247`) folosesc acest singur `YES_PATTERN.test(msg)`. Fără potrivire → nicio ramură de execuție nu se ia.

**Secundar — `PENDING_MISSION_LOST`:**
`pendingVignetteRef` (`3136`), `pendingNoteActionRef` (`3149`), `pendingMissionTaskRef` (`3173`) sunt setate la `null` **înainte** de `if (YES_PATTERN.test(msg))`. Un răspuns care nu se potrivește (near-miss, zgomot, comandă nouă) **distruge** acțiunea pending. Ramura Mission Governance (`3245`) e mai sigură — nu nulează nimic, anulează doar explicit (`NO_PATTERN` sau ≥3 cuvinte) — deci o misiune WhatsApp/Waze guvernată supraviețuiește și e recuperabilă cu un „da" ulterior exact; dar `pendingNoteActionRef`/`pendingVignetteRef` (fără backing guvernat) se pierd **definitiv**.

**Minor — `STATE_MACHINE_BUG`:**
`addMessage('user', …)` face `setBensonState('THINKING')` necondiționat (`1757`), deci starea părăsește `CONFIRMING` înainte ca enunțul să fie clasificat. Pe varianta governed, `handleIncomingText` returnează la `3272` fără nicio tranziție terminală → **starea rămâne blocată pe `THINKING`** cât timp gate-ul e deschis. `addMessage` (o funcție de UI/caption) nu ar trebui să conducă state machine-ul.

**De ce se vede ca „repornește listening":** buclele de re-armare (`endSub` @1076, `listenHealTimer` @1314) sunt **corecte** ca intenție — cât timp `getActiveMission().state === 'WaitingConfirmation'` vrei să asculți răspunsul. Combinate cu primar+secundar+minor, produc `STT_REQUESTED trigger=conversation_mode` la nesfârșit, fără progres și fără re-prompt.

---

## 7. Race conditions

**Nu există race care să cauzeze simptomul din log.** Re-ascultarea e **deterministă**: gate-ul e încă deschis (`WaitingConfirmation`) sau conversation mode e idle. Execuția nu pornește niciodată, deci nu există „mission async în zbor" peste care bucla să calce.

Analiză a punctelor cerute:

- **`handleIncomingText` NU e await-uit de apelant** — `scheduleAssembledDispatch` îl cheamă dintr-un `setTimeout` fără `await` (`app/index.tsx:~1807`). Promisiunea lui e fire-and-forget. **Mitigare existentă:** `loadingRef.current = true` e setat **sincron** la `3112`, înainte de orice `await`, iar ambele bucle de re-armare verifică `!loadingRef.current` (`1084`, `1315`). Deci cât timp lanțul await-uit din `handleIncomingText` rulează, bucla e ținută pe loc de `loadingRef`. Este un guard **incidental** (flag „handleIncomingText rulează"), nu unul dedicat de execuție.
- **`resumePendingTask` / `runMission` / `confirmActiveMission` — toate `await`-uite** (`3181`, `3380`, `3423`, `3249`), iar în interior `executeTask` / `executeGoverned` sunt `await`-uite (`missionOrchestrator.ts:357`, `210`). Nicio promisiune de execuție pierdută.
- **`onMissionAck` = `(t) => { …; speak(t); }`** (`3088-3090`) — fire-and-forget prin design (E1-5): ACK-ul TTS rulează în paralel cu efectul nativ. `speak()` ridică `speakingRef` → blochează buclele. Dacă lansarea nativă trece în fundal înainte de callback-ul TTS, `speakingRef` putea rămâne blocat — dar asta e teritoriul C1 (watchdog TTS), nu defectul de aici.
- **Race POTENȚIAL (nu în acest log, dar latent):** dacă vreo cale viitoare pornește execuția **în afara** lanțului await-uit al lui `handleIncomingText` (fire-and-forget, fără `loadingRef`), atunci `endSub`/self-heal ar putea porni STT în timpul unei acțiuni externe — pentru că **nu există** guard pe `bensonStateRef.current === 'EXECUTING'`. Vezi §10.

Verdict race: `start mission async → conversation loop observes THINKING/idle → pornește STT` — **NU se întâmplă acum** (mission nu pornește). Ar deveni posibil doar dacă s-ar introduce execuție ne-await-uită.

---

## 8. Scope of impact

**GENERIC. Nu e o problemă WhatsApp.** Un singur `YES_PATTERN` guvernează fiecare gate de confirmare:

| Flux | Gate | Fișier:linie | Efect la near-miss („Confirme."/„Confirmi"/zgomot) |
|---|---|---|---|
| Apel WhatsApp (guvernat) | `getActiveMission WaitingConfirmation` | `3245-3272` | deadlock: return la 3272, stare `THINKING`, misiune pending, buclă STT; recuperabil cu „da"/„confirm" exact |
| Navigație Waze (guvernată) | idem | `3245-3272` | idem |
| Resume task misiune multi-step | `pendingMissionTaskRef` | `3171-3238` | ref consumat @3173; dacă nu e guvernat → **pierdut definitiv**, „Confirme." merge la brain ca conversație |
| Notepad → eveniment calendar | `pendingNoteActionRef` | `3147-3167` | ref consumat @3149 → **pierdut definitiv** |
| Notepad → trimite mesaj | `pendingNoteActionRef` | `3147-3167` | idem |
| Vinietă (Context Engine) | `pendingVignetteRef` | `3134-3144` | ref consumat @3136 → **pierdut definitiv** |
| Brain „clarify" (întrebare de dezambiguizare) | `CONFIRMING` + următorul enunț | `3442-3450` | răspunsul afirmativ nerecunoscut → conversație în loc de acțiune |

Afectate: **toate acțiunile cu confirmare**, **toate misiunile multi-step**, **conversation mode în general** (bucla de re-armare devine vizibilă). Limba: orice — regex-ul e la fel de strict în RO/EN/DE (`confirm`, `bestätige`, `confirmă`, `sigur că da`, `bineînțeles`, `perfect`, `corect`, `hai`, `merge` — niciunul din astea nu se potrivește, cu excepția celor listate literal).

➡ **Defectul este generic. WhatsApp nu mai trebuie tratat ca problemă izolată.**

---

## 9. Minimal safe fix (NU implementat aici)

Cea mai mică suprafață care repară cazul raportat, fără regresii:

**Fix 1 (obligatoriu, ~1 linie) — `app/index.tsx:127`:** lărgește tulpina afirmativă „confirm" ca prefix, nu ca cuvânt exact:
```
… |confirm(?:[aăiețs]|at[ăi]?|area|ăm)?\b …
```
sau, mai simplu și robust, scoate `\b`-ul de final de pe această alternativă: `…|confirm[a-zăâ]*)\b` → devine `…|confirm[a-zăâ]*)`. Acoperă `confirm`, `confirmi`, `confirme`, `confirmă`, `confirmați`, `confirmarea`, `confirmez`.
**Risc:** ușor mai permisiv. Neutralizat de: (a) `NO_PATTERN` verificat înaintea acceptării pe fiecare gate; (b) garda `MIN_CONFIRM_BYTES` / `CONFIRM_REJECTED` (`3103-3106`) pentru audio gol; (c) precedența „refuz explicit sau ≥3 cuvinte anulează" din ramura guvernată.

**Fix 2 (recomandat, aceeași rundă, 3 mici mutări) — nu consuma pending înainte de validare:** în `3136`, `3149`, `3173`, mută `ref.current = null` **după** decizie — nulează doar pe accept real sau pe anulare explicită, exact ca ramura guvernată (`3266-3273`) care lasă misiunea pending pe un răspuns scurt ambiguu. Elimină `PENDING_MISSION_LOST` pentru note/vignetă/multi-step.

**Fix 3 (opțional, 1 linie) — `app/index.tsx:1757`:** nu muta starea în `THINKING` din `addMessage('user')` când un gate de confirmare e deschis (`pendingMissionTaskRef || pendingNoteActionRef || pendingVignetteRef || getActiveMission()?.state==='WaitingConfirmation'`) — lasă `handleIncomingText` să decidă tranziția. Elimină starea „blocată pe THINKING".

Constantă/rollback: fiecare fix e izolabil; niciunul nu atinge STT, rețete, Accessibility, executor.

---

## 10. Long-term architecture

**O singură mașină de stări, un singur executor, un singur classifier, un singur store de pending.**

```
LISTENING → UNDERSTANDING → CONFIRMING → EXECUTING → VERIFYING → LISTENING
```

1. **`bensonStateRef` = sursă unică de adevăr.** `addMessage`/UI nu scriu stare. Tranziția `CONFIRMING` e deținută de codul gate-ului; `EXECUTING`/`VERIFYING` de codul executorului.

2. **`isBusy()` = `state ∈ {UNDERSTANDING, EXECUTING, VERIFYING}`.** Fiecare punct de intrare în ascultare — `doStartListening`, re-armarea din `endSub`, `listenHealTimer`, callback-ul `onFinished` din `speakText` — începe cu:
   ```
   if (isBusy() && !dialogTurnExpected) return;   // EXECUTION_GUARD explicit
   ```
   `dialogTurnExpected` = un flag setat doar când o misiune multi-step chiar cere următorul „da". În rest, auto-listen e **blocat** în `EXECUTING` și `VERIFYING`.

3. **Un classifier afirmativ/negativ ca modul** (nu regex inline): normalizează (lowercase, fără diacritice/punctuație), potrivește tokeni pe un lexicon multilingv curat de accept/deny + toleranță fuzzy mică, întoarce `{ verdict: 'yes'|'no'|'other', confidence }`. Folosit de toate gate-urile. Un singur loc de întreținut, testabil.

4. **Un singur store de pending action** — store-ul guvernat din `src/core/mission` are deja forma corectă (backing AsyncStorage, ciclu de viață explicit: armare → confirm/cancel, niciodată nulat „din reflex"). Retrage refs-urile React paralele (`pendingMissionTaskRef`, `pendingNoteActionRef`, `pendingVignetteRef`) sau fă-le oglinzi strict read-only derivate din store. Pending-ul se consumă **numai** de executor, după ce a pornit.

5. **După `await execute()` → `VERIFYING`** (pasul de verificare al rețetei, ex. `ACC_VERIFY` / `assert` din recipe) **→ `LISTENING`.** Bucla de conversație reia **doar** din `LISTENING`.

6. **`EXECUTION_GUARD` real** înlocuiește dependența incidentală de `loadingRef`. `resumeInFlightRef` (deja existent, C1) e citit azi doar de handler-ul `AppState` — extinde-l (sau introdu `bensonStateRef==='EXECUTING'|'VERIFYING'`) ca gardă în toate căile de auto-listen.

---

## 11. Final verdict

### Categorii

**`CONFIRMATION_HANDLER_BUG` — PRIMAR**
- Evidence: `app/index.tsx:127` `YES_PATTERN = /\b(…|confirm[aă]?t?)\b/i`; folosit la `3097`, `3137`, `3150`, `3174`, `3247`. Comentariul `123-126` confirmă că „confirm" a fost adăugat ca fix anterior, dar tot nu prinde „Confirmi" (cuvântul din propriul prompt).
- File / function: `app/index.tsx` / `handleIncomingText` + constanta modul `YES_PATTERN`.
- Failure mechanism: `\b` după `confirm[aă]?t?` eșuează când cuvântul continuă cu literă (`confirm**e**`, `confirm**i**`, `confirm**ați**`) → `YES_PATTERN.test("Confirme.") === false` → toate ramurile de confirmare cad fără execuție.

**`PENDING_MISSION_LOST` — SECUNDAR (amplificator)**
- Evidence: `app/index.tsx:3136` `pendingVignetteRef.current = null;`, `3149` `pendingNoteActionRef.current = null;`, `3173` `pendingMissionTaskRef.current = null;` — toate **înainte** de `if (YES_PATTERN.test(msg))`.
- File / function: `app/index.tsx` / `handleIncomingText`, ramurile pending-*.
- Failure mechanism: consume-before-validate. Orice răspuns care nu se potrivește distruge referința pending. Fără backing guvernat (note, vinietă, unele multi-step) → pierdere definitivă; enunțul „Confirme." e apoi tratat de brain ca simplă conversație.

**`STATE_MACHINE_BUG` — MINOR**
- Evidence: `app/index.tsx:1757` `if (role === 'user') { setBensonState('THINKING'); return; }` (necondiționat); `3270-3272` `else { setLoading(false); loadingRef.current = false; return; }` fără tranziție terminală.
- File / function: `app/index.tsx` / `addMessage` + `handleIncomingText` ramura governed.
- Failure mechanism: starea părăsește `CONFIRMING` → `THINKING` înainte de clasificarea enunțului; pe return-ul devreme rămâne blocată pe `THINKING`, gate-ul rămânând deschis.

**`AUTO_LISTEN_RACE` — ABSENT**
- Evidence: `endSub` (`1084`) și `listenHealTimer` (`1315`) verifică ambele `!loadingRef.current`; `loadingRef` e setat sincron la `3112` înainte de orice `await`. Re-ascultarea din log apare **după** ce `handleIncomingText` a returnat (pasul 6), nu în paralel cu o execuție.
- Mechanism: nu e race — e comportament determinist al buclei de re-armare cât timp `getActiveMission().state === 'WaitingConfirmation'`. Execuția nu pornește niciodată.

**`EXECUTION_NOT_AWAITED` — ABSENT**
- Evidence: `await resumePendingTask` (`3181`), `await runMission` (`3380`, `3423`), `await confirmActiveMission` (`3249`); intern `await executeTask` / `await executeGoverned` (`missionOrchestrator.ts:357`, `210`). `handleIncomingText` însuși e chemat fire-and-forget din `scheduleAssembledDispatch`, dar acoperit de `loadingRef` sincron.

**`OVERLAY_FOCUS_SIDE_EFFECT` — NU e cauză**
- „BENSON rămâne topActivity" este **consecință**: `confirmActiveMission()` (care lansează WhatsApp) nu e apelat, deci nimic nu aduce WhatsApp în față. Nu contribuie la defect.

### `EXECUTION_GUARD`

**`EXECUTION_GUARD = ABSENT`** (dedicat). Nu există `executionInProgress` / `missionRunning` / `isExecuting` / `bensonStateRef==='EXECUTING'` verificat în `doStartListening` (`2641`), în `endSub` re-arm (`1076`) sau în `listenHealTimer` (`1314`). Singura acoperire e **incidentală**: `loadingRef.current`, ținut cât rulează lanțul await-uit din `handleIncomingText`. `resumeInFlightRef` (C1) există dar e citit doar de handler-ul `AppState`.

### Severity

**GENERIC / CRITIC.** Afectează: toate acțiunile cu confirmare · toate acțiunile multi-step · conversation mode în general. Nu doar WhatsApp.

---

`ORCHESTRATION_ROOT_CAUSE = YES_PATTERN (/\b…confirm[aă]?t?\b/i, app/index.tsx:127) nu se potrivește cu transcriptul "Confirme." (nici cu "Confirmi", cuvântul propriului prompt), deci fiecare ramură de confirmare din handleIncomingText cade fără execuție; pending-ul e în plus consumat înainte de verificare (3136/3149/3173) iar ramura guvernată returnează devreme (3270-3272) lăsând starea pe THINKING și misiunea WaitingConfirmation, astfel încât buclele endSub/self-heal re-armă STT conversation_mode la nesfârșit și lansarea WhatsApp nu rulează niciodată.`

`AFFECTS_WHATSAPP_ONLY = FALSE`

`AUTO_LISTEN_RACE = FALSE`

`PENDING_MISSION_SAFE = FALSE`

`EXECUTION_AWAITED = TRUE`
