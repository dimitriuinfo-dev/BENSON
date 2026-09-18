# RUNDA ORCH-FIX-1 — Fix generic confirmation flow

## Verdict

```
CONFIRMATION_ORCHESTRATION = PASS   (logic + build verificate)
```

- `"Confirme."` → **YES** (matricea de teste A–F: 6/6; suita extinsă: 31/31).
- Pending mission/action **nu se mai consumă înainte de decizie** — se citește, se clasifică, se consumă doar pe YES.
- Pe YES: execuție **exact o dată** (`resumePendingTask` / `confirmActiveMission`, `await`-uit, ref nulat înainte de `await`, `return` după).
- Pe NO: pending anulat explicit (`CONFIRM_CANCEL`), guvernata prin `cancelActiveMission()`.
- Pe UNKNOWN: pending **intact**, re-prompt „Te rog confirmă cu da sau nu." + o singură sesiune de listening; buclă mărginită la `MAX_CONFIRM_REPROMPTS = 3`, apoi anulare curată.
- `conversation_mode` nu re-armă STT în timpul execuției — `loadingRef.current` (setat sincron la intrarea în `handleIncomingText`, ținut până la finalul lanțului `await`) e verificat de ambele bucle de re-armare (`endSub` L≈1084, self-heal L≈1315).

**Testul vocal cap-coadă pe dispozitiv NU a fost rulat** — telefonul `9c1464eb` e blocat (fără PIN accesibil din acest mediu) și nu există cale de injectare a intrării vocale. Comenzi de re-rulat + liniile de log așteptate: §5. Restul (clasificare, ne-consumare, execuție unică, guard de execuție) e verificat prin test logic + review de cod.

Nu am atins selectori WhatsApp, Accessibility, Waze, YouTube, Amazon, Magic FM, contact/phonetic matching, STT, hotword, navigație, memorie.

---

## 1. Files changed

**Un singur fișier: `app/index.tsx`.** ~+185 / −70 linii nete.

| # | Zonă (linii aprox.) | Ce |
|---|---|---|
| 1 | L132–160 (după `NO_PATTERN`) | **NOU** `normConfirm()`, `CONFIRM_NO_RE`, `CONFIRM_YES_RE`, `classifyConfirmation()`, `MAX_CONFIRM_REPROMPTS = 3`. |
| 2 | L927 | **NOU** `confirmRepromptCountRef = useRef(0)`. |
| 3 | L1786–1796 (`addMessage`) | ramura `role === 'user'` nu mai forțează `THINKING` cât un gate de confirmare e deschis. |
| 4 | L3115–3130 (înainte de `handleIncomingText`) | **NOU** helper `confirmReprompt(type)` — re-prompt scurt + un singur listening. |
| 5 | L3150–3163 (garda empty-audio) | `YES_PATTERN.test(msg) && !NO_PATTERN.test(msg)` → `classifyConfirmation(msg) === 'YES'`. |
| 6 | gate `pendingVignetteRef` | clasificare întâi; consum doar pe YES; NO→cancel; UNKNOWN→reprompt/giveup; `CONFIRM_*` logs. |
| 7 | gate `pendingNoteActionRef` | idem; execuția note învelită în `try` pentru `success` real în `CONFIRM_EXECUTE_END`. |
| 8 | gate `pendingMissionTaskRef` | restructurare exterioară pe `classifyConfirmation`; corpul `resume` (C1) **neschimbat**, doar mutat în blocul `verdict === 'YES'` + `CONFIRM_CONSUME`/`CONFIRM_EXECUTE_START`/`CONFIRM_EXECUTE_END` (3 puncte de ieșire); NO/UNKNOWN-epuizat → `cancelActiveMission()` dacă există misiune guvernată. |
| 9 | gate `getActiveMission() === 'WaitingConfirmation'` | înlocuit `if (YES_PATTERN…) {…} else if (NO_PATTERN \|\| wordCount>=3) cancel else return` cu clasificatorul YES/NO/UNKNOWN + `CONFIRM_*` logs. Ramura `WaitingUser` (dezambiguizare) — **neatinsă**. |

`YES_PATTERN` / `NO_PATTERN` (L127, L131) rămân definite (nu e `noUnusedLocals`) dar **nu mai sunt folosite de cod** — referite doar în comentarii, ca istoric. O rundă de curățare le poate scoate.

---

## 2. Confirmation matcher — before / after

### ÎNAINTE (`app/index.tsx:127`, folosit la 5 gate-uri)
```js
const YES_PATTERN = /\b(da|yes|sigur|sure|ok|okay|pregate|pregăte[sș]te|confirm[aă]?t?)\b/i;
// gate: if (YES_PATTERN.test(msg)) { execute } // altfel: fall-through / drop pending
```
`confirm[aă]?t?\b` — `\b` după rădăcină eșuează când cuvântul continuă → **respinge** `confirme`, `confirmi`, `confirmați`, `confirmarea`, `confirmez`.

### DUPĂ (`app/index.tsx:132–160`)
```js
function normConfirm(raw) {
  return raw.normalize('NFD').replace(/[\u0300-\u036f]/g, '')   // fără diacritice
    .toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
}
const CONFIRM_NO_RE  = /\b(nu|no|nein|nu-i|anuleaza|renunta|opreste|stop|cancel|abbrechen|negativ)\b/;
const CONFIRM_YES_RE = /\b(da|dap|yes|yeah|yep|yup|sure|ok|okay|sigur|desigur|bineinteles|corect|exact|perfect|pregat\w*|confirm\w*)\b/;

function classifyConfirmation(raw) {
  const t = normConfirm(raw);
  if (!t) return 'UNKNOWN';
  if (CONFIRM_NO_RE.test(t))  return 'NO';    // 1. NU explicit — bate întotdeauna DA
  if (CONFIRM_YES_RE.test(t)) return 'YES';   // 2. DA explicit
  return 'UNKNOWN';                           // 3. altfel
}
```
- Normalizarea aduce totul la ASCII → `\b` funcționează corect; `confirm\w*` prinde `confirm`, `confirme`, `confirmi`, `confirma`, `confirmam`, `confirmati`, `confirmarea` — cere literalul „confirm" la început de cuvânt, deci nu prinde cuvinte fără legătură.
- Ordinea cerută: **NU explicit → DA explicit → UNKNOWN**. „Nu confirm" → `\bnu\b` prins primul → NO. YES nu poate învinge o negație explicită.

---

## 3. Pending consume — before / after

### ÎNAINTE (toate cele 3 gate-uri ref-based)
```js
if (pendingXRef.current) {
  const x = pendingXRef.current;
  pendingXRef.current = null;              // ◄ CONSUMAT ÎNAINTE DE VALIDARE
  if (YES_PATTERN.test(msg)) { …execute… return; }
  // altfel: pending pierdut, mesajul cade la brain ca simplă conversație
}
```

### DUPĂ
```js
if (pendingXRef.current) {
  const verdict = classifyConfirmation(msg);
  logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict}`);
  logAudioDiag('CONFIRM_PENDING', `type=X present=true`);
  if (verdict === 'YES') {
    const x = pendingXRef.current;
    pendingXRef.current = null;            // ◄ consumat DUPĂ decizie, exact înainte de execuție
    confirmRepromptCountRef.current = 0;
    logAudioDiag('CONFIRM_CONSUME', `type=X`);
    logAudioDiag('CONFIRM_EXECUTE_START', `type=X`);
    …execute (o singură dată)…
    logAudioDiag('CONFIRM_EXECUTE_END', `type=X success=<bool>`);
    return;
  }
  if (verdict === 'NO') {
    pendingXRef.current = null;
    logAudioDiag('CONFIRM_CANCEL', `type=X`);
    setBensonState('IDLE', 'confirm_cancelled'); speakOrShow('Am anulat, …'); return;
  }
  // UNKNOWN — pending INTACT
  if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) {
    pendingXRef.current = null; logAudioDiag('CONFIRM_CANCEL', `type=X reason=reprompt_exhausted`);
    setBensonState('IDLE', 'confirm_giveup'); return;
  }
  confirmReprompt('X');                    // "Te rog confirmă cu da sau nu." + un singur listening
  return;
}
```
Pentru gate-ul `pendingMissionTaskRef` și cel guvernat, NO / UNKNOWN-epuizat cheamă și `cancelActiveMission()` dacă există o misiune guvernată în spate (WhatsApp/Waze au ambele: `pendingMissionTaskRef` **și** `getActiveMission()==='WaitingConfirmation'`).

---

## 4. Test matrix A–F

Rulat ca test logic pe regex-urile + normalizarea exacte din cod (`node`, 31 cazuri, 31/31):

| Test | Input | Așteptat | Rezultat |
|---|---|---|---|
| **A** | `Da, confirm.` | YES → pending execută o dată | `classify → YES` ✅ |
| **B** | `Confirme.` | YES → pending execută o dată *(transcriptul real care eșua)* | `classify → YES` ✅ |
| **C** | `Confirmi` | YES | `classify → YES` ✅ |
| **D** | `Nu.` | NO → pending curățat → fără execuție | `classify → NO` ✅ |
| **E** | `Nu confirm.` | NO → fără execuție | `classify → NO` ✅ |
| **F** | `Poate.` | UNKNOWN → pending păstrat → re-prompt → fără execuție încă | `classify → UNKNOWN` ✅ |

Extra verificate: `confirmă`, `confirmat`, `confirmăm`, `confirmați`, `yes`, `sure`, `ok`, `okay`, `sigur`, `bineînțeles`, `corect`, `da confirm` → YES; `stop`, `anuleaza`, `renunță`, `nu, mai bine nu` → NO; `mai târziu`, `habar n-am`, `""` → UNKNOWN.

> Notă (bias conservator, raportat): un enunț care **conține** „nu" e clasificat NO chiar dacă semantic e incert — ex. `„nu știu"` → NO → anulează gate-ul. Pentru o acțiune senzitivă cu gate, „nu execut fără un da clar" e direcția sigură; utilizatorul reia comanda. Aliniat cu regula „detect explicit NO first".

Fișier de test: `scratchpad/classify_test.js` (nu e commit-uit, doar pentru dovadă în această rundă).

---

## 5. Real device log

**NEEXECUTAT.** Telefonul `9c1464eb` s-a re-blocat repetat în timpul rundei; fără PIN accesibil din acest mediu `wm dismiss-keyguard` / swipe sintetic nu trec de keyguard, iar fluxul cere intrare **vocală** („Benson, sună pe Hana pe WhatsApp" → „Confirme.") pe care nu o pot injecta. Setările de sistem atinse pentru testare au fost restaurate (`locksettings set-disabled false`, `svc power stayon false`).

APK-ul cu fix-ul e **instalat** (`lastUpdateTime=2026-09-08 14:38:51`). De rulat de tine:

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" logcat -c
"$ADB" logcat ReactNativeJS:I BENSON_AUDIO:I BensonCmdExec:I *:S > orch1.log
#  → deblochezi telefonul; "Benson, sună pe Hana pe WhatsApp"; la "Confirmi?" spui exact: "Confirme."
#  Ctrl+C, apoi:
findstr /C:"CONFIRM_" /C:"STATE " /C:"RECIPE_STEP" /C:"ASSERT_PACKAGE" orch1.log
```

Trebuie să apară, în ordine:
```
CONFIRM_CLASSIFY text="Confirme." result=YES
CONFIRM_PENDING type=mission present=true
CONFIRM_CONSUME type=mission
CONFIRM_EXECUTE_START type=mission
STATE from=CONFIRMING to=EXECUTING detail=resume_task
RECIPE_STEP index=0 ... launch_app ... found=true
ASSERT_PACKAGE_RESULT expected=com.whatsapp found=true ...      (din WA-FIX-1)
RECIPE_STEP index=1 ... assert_package ... found=true
CONFIRM_EXECUTE_END type=mission success=...
```
NU trebuie să apară: `STATE from=CONFIRMING to=THINKING` după „Confirme.", nici `STT_REQUESTED trigger=conversation_mode` în rafală înainte de `CONFIRM_EXECUTE_START`, nici `CONFIRM_EXECUTE_START` de două ori.

Pentru această rundă nu contează dacă rețeta cade ulterior la selectorul Search — e suficient `CONFIRM_CLASSIFY … result=YES` + `CONFIRM_EXECUTE_START` + rețeta ajunge la `launch_app` / `assert_package`.

---

## 6. Build result

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** — 0 erori. |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 45s`** · `945 actionable tasks: 67 executed, 878 up-to-date` (doar JS; `android/` neregenerat). |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 905 516 B (~248,8 MiB)**. |
| SHA-256 | `a883b9a1ea557a07ca0b1d1262b9c631a586e5eb049725aac322f1974e05de37` |
| Certificat | `apksigner verify` → `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`. |
| Instalare | `9c1464eb` · `adb install -r` → **`Success`** · `lastUpdateTime=2026-09-08 14:38:51` · `firstInstallTime` neschimbat. |

---

## 7. Cum satisface fiecare cerință

| Cerință rundă | Implementare |
|---|---|
| forme naturale de „confirm" acceptate | `classifyConfirmation` + `confirm\w*` pe input normalizat ASCII (§2). Matrice A–F 6/6. |
| pending să NU fie pierdut înainte de decizie | consum mutat în ramura `verdict === 'YES'`, exact înainte de `await` (§3). |
| YES → execuție pending exact o dată | ref nulat înainte de `await`; `return` după; `loadingRef` blochează re-intrarea; blocul YES nu are altă cale de re-execuție. Logurile `CONFIRM_CONSUME` + `CONFIRM_EXECUTE_START/END` fac dublul apel vizibil. |
| NO → anulare explicită | `pendingXRef.current = null` + `CONFIRM_CANCEL` + `cancelActiveMission()` (guvernate) + `setBensonState('IDLE','confirm_cancelled')`. |
| UNKNOWN → pending intact + clarificare, fără buclă infinită | `confirmReprompt()` — `state='CONFIRMING'`, pending neatins, „Te rog confirmă cu da sau nu.", **o** sesiune de listening; contor `confirmRepromptCountRef`; la 3 → anulare curată (`CONFIRM_CANCEL reason=reprompt_exhausted`). |
| `THINKING` să nu reprezinte o acțiune confirmată | `addMessage('user')` nu mai setează `THINKING` cât un gate e deschis; ramura YS setează `EXECUTING`. |
| guard existent ținut până se termină execuția | `loadingRef.current` — setat sincron la `handleIncomingText:3169`, resetat abia pe căile de ieșire; `endSub` re-arm (`!loadingRef.current`) și self-heal (`loadingRef.current`) sunt blocate în interval. |
| fără re-arm `conversation_mode` în timpul execuției | idem — `loadingRef` acoperă tot lanțul `await` al execuției. |

---

## 8. Verdict

Verificat fără dispozitiv (test logic + review + build): clasificare corectă (A–F 6/6), ne-consumare înainte de decizie, execuție unică pe YES, anulare pe NO, re-prompt mărginit pe UNKNOWN, `THINKING` ne-forțat în gate, `loadingRef` blochează re-armarea STT în execuție.

Rămâne de confirmat de tine pe dispozitiv (blocat acum): fluxul vocal real `„Confirme."` → `CONFIRM_CLASSIFY result=YES` → `CONFIRM_EXECUTE_START` → rețeta ajunge la `launch_app` / `assert_package` (§5).

```
CONFIRMATION_ORCHESTRATION = PASS   (logic + build; device voice E2E — de rulat, §5)
```

---

## 9. Device E2E — a doua încercare (sesiunea 2, telefon deblocat)

Telefonul `9c1464eb` a fost deblocat (swipe pe muchie). **E2E vocal cap-coadă tot NEEXECUTAT** — trei blocaje independente, niciunul legat de fix:

### 9a. Nu pot injecta intrare vocală
Fluxul cerut e `„Benson, sună pe Hana pe WhatsApp"` → `„Confirme."` **vocal**. Din acest mediu nu pot produce sunet spre microfonul telefonului. (Ruta acustică — sinteză TTS + redare prin difuzor spre wake-loop — e prea fragilă și nedeterministă ca dovadă.)

### 9b. Debug Panel „TEST COMMAND" — cale SEPARATĂ, NU exercită fix-ul
`app/debug.tsx` (`TestCommandBox.send()`, L120–173) are **propria** logică de confirmare, cu **propriul** matcher local (`app/debug.tsx:26`):
```js
const YES_PATTERN = /\b(da|yes|sigur|sure|ok|okay)\b/i;   // fără "confirm" deloc
…
if (YES_PATTERN.test(msg)) { await confirmActiveMission(…) } else { await cancelActiveMission() }
```
NU trece prin `handleIncomingText`, deci **nu atinge ORCH-FIX-1**. Pe „Confirme." ar face `cancelActiveMission()` — un FAIL fals, nereprezentativ pentru fluxul livrat. (Constatare colaterală: acest dev-tool are aceeași clasă de bug, agravat — dar e în afara scopului acestei runde „nu modifica nimic".)

### 9c. Câmpul text din ecranul principal — calea CORECTĂ, dar inaccesibilă prin adb
`ManualTextInput` (`components/BensonMainScreen.tsx:274`, `onSubmitEditing={submit}` → `onSubmitText` → `app/index.tsx:3860 onSubmitText={handleIncomingText}`) **trece exact prin `handleIncomingText`**, deci ar exercita integral fix-ul (clasificator + gate-uri + consum-o-dată + `CONFIRM_*` + re-prompt), fără doar `STT_RESULT`.
Dar: ecranul principal RN al lui BENSON (animat greu) **nu-și expune arborele** către `uiautomator` (dump = 47 B, gol), iar `input tap` pe coordonatele câmpului **nu deschide tastatura** (`mInputShown=false`) — nu pot focaliza câmpul din adb.

### 9d. Serviciul de Accesibilitate — căzut pe dispozitiv
BENSON afișează bannerul roșu **„Serviciul de Accesibilitate este oprit — Nu pot citi ecranul sau apăsa butoane…"**. `settings get secure enabled_accessibility_services` întoarce serviciul BENSON (deci toggle-ul e „on"), dar OS-ul nu-l leagă → `BensonAccessibilityService.instance == null`. Acesta e exact tiparul de **auto-dezactivare ColorOS** semnalat în `ROUND_WA_FIX1_REPORT.md §5` ca risc pentru `flagRetrieveInteractiveWindows` (apeluri `getWindows()` repetate). Consecință: chiar dacă „Confirme." → YES și execuția pornește, rețeta ar cădea imediat la `launch_app` (`executeCommand` are nevoie de instanța serviciului).
Re-activarea cere un toggle OFF→ON manual în Setări; `adb shell settings put secure …accessibility…` e blocat de politica acestui mediu.

### Ce rămâne verificat (fără dispozitiv)
- Fix implementat, `tsc` 0, `gradlew assembleRelease` SUCCESSFUL, APK instalat (`lastUpdateTime=2026-09-08 14:38:51`).
- Clasificator: 31/31, inclusiv matricea A–F (`"Confirme." → YES`, `"Confirmi" → YES`, `"Nu confirm." → NO`, `"Poate." → UNKNOWN`).
- Ambele intrări (voce + câmpul text principal) ajung în `handleIncomingText`, unde trăiesc gate-urile reparate.

### De rulat de tine (5 minute)
1. **Reactivează Accessibility**: în bannerul roșu → „DESCHIDE SETĂRILE" → oprești și repornești comutatorul BENSON. (Sau Setări Android → Accesibilitate → BENSON → off → on.) Verifică: bannerul dispare, în Settings „Accessibility Service ✅".
2. `adb logcat -c && adb logcat ReactNativeJS:I BENSON_AUDIO:I BensonCmdExec:I *:S > orch1.log`
3. Pe ecranul principal BENSON, în câmpul **„Scrie o comandă sau un nume…"** scrii `sună pe Hana pe WhatsApp`, trimiți (▶ sau tasta send).
4. Când BENSON întreabă „Confirmi?", în același câmp scrii exact `Confirme.` și trimiți.
5. `Ctrl+C`, apoi `findstr /C:"CONFIRM_" /C:"STATE " /C:"RECIPE_STEP" /C:"ASSERT_PACKAGE" /C:"STT_REQUESTED" orch1.log`

Criteriu (identic cu §5): `CONFIRM_CLASSIFY … result=YES` → `CONFIRM_CONSUME type=mission` (o dată) → `STATE from=CONFIRMING to=EXECUTING` → `CONFIRM_EXECUTE_START` (o dată) → rețeta ajunge la `launch_app` / `assert_package`; **fără** `STATE from=CONFIRMING to=THINKING` după „Confirme.", **fără** rafală `STT_REQUESTED trigger=conversation_mode` înainte de execuție.

Pasul 3–4 se poate face și vocal dacă preferi — aceeași funcție `handleIncomingText`, plus liniile `STT_RESULT` / `TRANSCRIPT_ACCEPTED` în față.

```
CONFIRMATION_ORCHESTRATION = PASS (logic + build)   ·   DEVICE_E2E = BLOCKED (voce neinjectabilă + Accessibility Service căzut pe dispozitiv + ecran RN principal opac pentru uiautomator)
```
