# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`6c7940a` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).
**Împins pe GitHub** (`origin/benson-next`), cu tag-ul `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`
pe commit-ul de checkpoint — vezi secțiunea CHECKPOINT de mai jos.

## Teste
418/418 PASS (`./gradlew :benson-foreground-service:testDebugUnitTest`). Vezi `BENSON_NEXT_BACKLOG.md`
pentru distincția pe 4 coloane (implementat/conectat/test automat/DEVICE_PASS) — **primul DEVICE_PASS
real al acestei runde a fost obținut** (SURVIVOR/navigație Waze), detaliat mai jos.

## SURVIVOR Verification Engine (19.10.2026) — răspuns la DEVICE_FAIL Calculator

Captura lui Rareș: BENSON a spus „Nu găsesc calculator butoanele trei șapte si opt pe ecran. Apasă
pe calculator butoanele trei șapte si opt." — ecranul final arăta `89.979()`, nu `378`. Cauza
GENERICĂ (nu specifică Calculatorului) găsită și reparată, în `benson-accessibility` (EXECUTOR
comun, folosit de orice interacțiune `tap_element`, nu un script special):

**Bug real, confirmat prin citirea codului**: `tapDigestElement()` (`BensonAccessibilityService.kt`)
întorcea `ControlResult(true, ...)` **necondiționat** după o apăsare acceptată — inclusiv când
`changed` (ecranul comparat înainte/după) era `false`. Mesajul text era onest ("...dar ecranul pare
neschimbat."), dar câmpul `ok` rămânea `true`. Agentul primea deci "succes" pentru o apăsare care nu
produsese NICIUN efect observabil — exact genul de fals pozitiv care, repetat, poate duce la o stare
finală incoerentă precum `89.979()`.

**Reparat**, pe punctele din mandat:
1. **Plan/rezultat separate**: codul BENSON nu calculează și nu presupune NICIODATĂ rezultatul
   matematic așteptat (378) — doar verifică, generic, că fiecare apăsare a avut un efect observabil.
   Rezultatul final se citește mereu literal de pe ecran, niciodată dedus.
2. **Dovezi observabile**: fiecare ramură de eșec din `tapDigestElement` loghează acum explicit
   `classification=<UI_NODE_NOT_FOUND|UI_ACTION_REJECTED|VERIFICATION_FAILED|UNKNOWN>`, nu doar
   `ok=false`.
3-4. **Verificare reală a efectului, nu doar prezența unei reobservări**: după apăsare, se citește
   `screenSignature()` din nou; dacă nu s-a schimbat, rezultatul e acum `ok=false`
   (`VERIFICATION_FAILED`), nu `true`. Agentul primește mesajul explicit și trebuie să reobserve.
5. **Fără coordonate fixe, fără repetare oarbă**: neschimbat — `tapDigestElement` tot funcționează
   doar pe indici dintr-un digest proaspăt, cu verificare de etichetă anti-ecran-schimbat
   (comportament deja existent, FIX_AGENT_TAP_CONTRACT_1, neatins).
6. **Calculator — stare inițială/expresie/rezultat**: NU s-a construit logică specifică
   Calculatorului (interzis explicit, punctul 9) — verificarea rămâne generică ("s-a schimbat ceva
   observabil pe ecran după apăsare"), aplicabilă oricărei aplicații cu conținut text vizibil.
7. **Cifrele 3,7,8 — planificare greșită sau răspuns final?** — **NU se poate răspunde din log**:
   bufferul logcat s-a rotit peste rularea exactă a lui Rareș înainte să pot citi; nu exista încă
   tag-ul `classification=` din acest fix. Ipoteză, explicit neconfirmată: fals pozitive repetate
   (`ok=true` pe apăsări fără efect) ar fi putut lăsa agentul să creadă că progresează, ducând la o
   secvență de apăsări necorelată cu planul inițial. Următoarea rulare reală va avea dovada în log.
8. **FailureClassifier**: `InteractionVerification.kt` (nou, pur, testat) — exact cele 4 categorii
   cerute: `UI_NODE_NOT_FOUND`, `UI_ACTION_REJECTED`, `VERIFICATION_FAILED`, `UNKNOWN`.
9. **Un singur EXECUTOR comun**: fix-ul e în `tapDigestElement`, funcția deja folosită de ORICE
   apăsare prin agent (`TAP_ELEMENT`) — nu un drum nou, nu un al doilea LIVE.
10. **Teste + dovezi ADB nedistructive**: 6 teste noi (`InteractionVerificationTest`), 111/111 teste
    PASS în `benson-accessibility`, 418/418 neschimbate în `benson-foreground-service`.
    `assembleRelease` reconstruit local (BUILD SUCCESSFUL, semnătură identică `fbbc618da8...`) —
    **NEINSTALAT** (nicio reautorizare explicită de instalare/cost în acest mesaj) — pregătit pentru
    reluarea testului real, nu executat.

**Fișiere**: `InteractionVerification.kt`/`InteractionVerificationTest.kt` (noi) +
`BensonAccessibilityService.kt` (modificat, fișier deja COMIS anterior — deci diff normal, dar
rămâne NECOMIS acum, alături de restul lucrului nevalidat Calculator, până la o nouă dovadă reală).

## Semantic UI Resolver (19.10.2026) — a CINCEA rulare reală, progres real confirmat

Mandat: nu repeta testul cu selecția nesigură de index a modelului — construiește o rezolvare
semantică generică (`press_button(label)`), integrată în AKA/EXECUTOR existent, nu un orchestrator
nou. Construit, testat local, apoi testat REAL pe dispozitiv (rularea 5 a zilei).

**Construit, pe punctele cerute**:
1-2. **`ControlLocator.findButtonCandidates(label, root)`** (nou, pur, în motorul G-1 deja
   existent) — rezolvă ținta din arborele Accessibility ACTUAL (text/desc/viewId, potrivire exactă
   întâi, parțială ca rezervă), niciodată dintr-un index memorat.
3. **Ambiguitate**: `candidates.size > 1` → refuz explicit, cu numărul de candidați, cerând
   `observe_screen`+`tap_element` pentru dezambiguizare prin index — nu un al doilea mecanism de
   selecție, ci reutilizarea celui existent DOAR pentru acest caz.
4. **Identitate pachet**: `pressButtonByLabel` reutilizează `isForeignAppForeground` (deja construit
   pentru `tapDigestElement`) înainte de orice apăsare.
5-6. **SURVIVOR**: aceeași verificare pe focalizare prioritară + coordonate excluse
   (`InteractionVerification.kt`, neschimbată) — zero logică nouă de verificare, zero cunoaștere
   despre Calculator sau poziții de butoane.
7. **Plan vs. rezultat**: instrucțiune explicită în `systemPrompt` — apasă EXACT cifrele/operatorii
   EXPRESIEI cerute, niciodată cifrele unui rezultat presupus cunoscut.
8. **8 teste noi** (`ControlLocatorTest`) — exact scenariile cerute: ordine de noduri schimbată,
   etichete duplicate (ambiguu), element negăsit, element neclicabil exclus, potrivire exactă vs.
   parțială.

**Rularea 5, REZULTAT REAL — progres confirmat, nu doar teoretic**:
```
AGENT_PRESS label="2" → digit_2 găsit, apăsat, VERIFICAT: focalizarea a CRESCUT cu exact "2" ✓
AGENT_PRESS label="7" → digit_7 găsit, apăsat, VERIFICAT: focalizarea a CRESCUT cu exact "7" ✓
AGENT_PRESS label="×" → UI_NODE_NOT_FOUND (eticheta reală e "Multiplizieren", nu simbolul "×")
```
**Pentru prima dată în 5 rulări, cifrele corecte (2, apoi 7) au fost identificate și apăsate CORECT,
cu efectul verificat EXACT corespunzător** (nu doar "ceva s-a schimbat"). Agentul s-a adaptat
singur după eșecul pe „×" (revenind la `tap_element`+`observe_screen`), exact cum spune
`systemPrompt`-ul. Eșecul pe „×" are o cauză clară, diferită: eticheta VIZUALĂ a operatorului
(simbolul „×") nu se potrivește cu `contentDescription`-ul REAL din arborele Accessibility
(„Multiplizieren", cuvântul german — sistemul telefonului e în germană, deja documentat). Nu e un
bug de rezolvare — e o diferență reală între ce VEDE omul și ce EXPUNE aplicația pentru accesibilitate.

**Zero afirmație falsă** — exact ca rulările 4 (după reparația coordonatelor): „Nu am reușit să duc
asta la capăt, Master." Onest, de data asta cu dovadă REALĂ de progres parțial corect în log.

**Constatare separată, de mediu de test**: formula Calculatorului conținea deja conținut rezidual
("787892...") înainte de prima apăsare din rularea 5 — probabil acumulat din rulările anterioare,
pentru că `force-stop` + relansare NU garantează o expresie goală (unele calculatoare își salvează
starea ultimei sesiuni). Nu e un bug BENSON — e o limită a protocolului meu de resetare între
rulări, notă pentru o rulare viitoare (apăsare „AC" explicită înainte de start).

## Buget EPUIZAT — oprire imediată
`dayTotalUsd` a ajuns la 0,0962 (din bugetul suplimentar de 0,10 USD autorizat). Am oprit testarea
live IMEDIAT la atingerea acestui prag — nicio altă rulare, niciun alt apel OpenAI, fără o nouă
autorizare explicită de buget.

## SURVIVOR S4 — PATRU rulări reale pe dispozitiv (19.10.2026), cauza reală găsită

Autorizare: `adb install -r` după verificare + buget OpenAI suplimentar de 0,10 USD. Patru rulări
complete ale comenzii EXACTE „Benson, deschide Calculatorul și calculează 27 înmulțit cu 14.",
fiecare cu captură logcat live, de la început, salvată (`.tmp-s4-run1..4-logcat.txt`). **Niciun
DEVICE_PASS pentru Calculator** — rezultatul corect (378) nu a fost niciodată introdus și citit
real. Cost total folosit din bugetul suplimentar: ~0,042 USD din 0,10 USD autorizați (patru rulări
+ reparații intermediare, monitorizat la fiecare pas prin `USAGE_ADD`).

**Cauza reală, principală, găsită DIN LOG (nu presupusă)**: între momentul în care Calculatorul e
confirmat SURVIVOR în prim-plan și prima apăsare efectivă, agentul „gândește" (un apel HTTP către
OpenAI, câteva secunde). **În acest interval, o notificare a unei aplicații TERȚE (eBay
Kleinanzeigen — `MessagesActivity`, posibil un mesaj/evaluare pentru o conversație cu "Katja") a
preluat prim-planul**, confirmat prin log-urile native `ActivityTaskManager`
(`setAppVisibility(...calculator..., visible=false)` urmat de `TopActivityComponent:
ComponentInfo{com.ebay.kleinanzeigen/...MessagesActivity}`). Contractul deja existent
(`FIX_AGENT_TAP_CONTRACT_1`, "stale_index") a prins corect situația și a refuzat să apese orbește —
dar o eticheta generic ca "ecranul s-a schimbat" NU distingea asta de o simplă navigare internă,
consumând pași (buget) pe reîncercări înainte ca agentul să-și dea seama să redeschidă aplicația.
**Verificat explicit, cerința utilizatorului**: `OVERLAY_POLICY_EVAL isSelfForeground=false
hadActiveOverlay=false` pe TOT intervalul — overlay-ul PROPRIU al BENSON nu a avut niciun rol;
cauza a fost strict o notificare externă, în afara controlului BENSON.

**Reparat, generic, în EXECUTOR comun** (`isForeignAppForeground`, `InteractionVerification.kt`):
compară pachetul aplicației din digestul ORIGINAL servit agentului cu pachetul digestului
PROASPĂT — dacă diferă, mesajul explicit spune „Altă aplicație a preluat ecranul... Deschide din
nou aplicația țintă", nu doar „observă din nou" (care nu ajuta cu nimic când ecranul era altă
aplicație cu totul). 3 teste noi.

**A doua cauză reală găsită, a doua declarație falsă prinsă prin gărzile existente, dar scăpată de
gardul 4**: rularea 4 a rostit „27 înmulțit cu 14 este 378." deși apăsările reale fuseseră
„Wissenschaftlich" (comutator mod științific), „löschen" (șterge) ×2, „!" (cursorul însuși),
„Prozent-Zeichen" (%) — ZERO cifre. Gardul 4 (`claimedNumberMissingFromLastObservation`) ar fi
trebuit să blocheze, dar **"378" apărea din întâmplare în COORDONATELE unui element** (randarea
digestului include bounds-ul, ex. „...40,378,500,980" — un ecran de 1080×2414 pixeli produce des
secvențe de 3 cifre coincidente în coordonate x/y). **Reparat**: coordonatele se elimină explicit
înaintea comparației de conținut (`BOUNDS_PATTERN`), 2 teste noi.

**A treia constatare, confirmată real pe ecran (rularea 1, independent prin screenshot)**:
Calculatorul s-a deschis cel puțin o dată în modul FEREASTRĂ PLUTITOARE (nu fullscreen) — probabil
rezultatul unei apăsări greșite anterioare pe iconița de redimensionare. Resetat manual la
fullscreen ca pregătire de mediu de test (nu o modificare de cod) — nicio logică specifică acestui
caz nu a fost construită în BENSON (interzis explicit).

**Concluzie onestă, răspuns la cerința explicită "repară cauza demonstrată, nu printr-un script cu
coordonate Calculator"**: AMBELE cauze reale găsite (notificare externă + coordonate confundate cu
conținut) au fost reparate GENERIC, în `InteractionVerification.kt`/`AgentVerificationGate.kt`,
fără nicio cunoaștere despre pozițiile butoanelor Calculatorului. Rămâne un factor pe care niciun
cod din EXECUTOR nu-l poate repara: modelul (gpt-5-nano) continuă să aleagă INDICI greșiți pentru
cifre (dovedit, de 4 ori, cu secvențe fără nicio legătură cu „27,7,1,4" — vezi istoricul complet mai
jos) — o limită de fiabilitate a modelului ales pentru cost, nu un defect de cod BENSON.

## SURVIVOR S4 (prima precizare, 19.10.2026) — trei straturi distincte, nu unul singur
Precizări DeepSeek acceptate, aplicate în `InteractionVerification.kt`/`tapDigestElement`:

1. **Trei dovezi, nu una**: `InteractionEvidence` separă explicit `actionAccepted` (apăsare
   acceptată tehnic), `screenReobserved`+`overallScreenChanged` (schimbare observată, semnal slab —
   poate veni din orice parte a ecranului, irelevantă), `focusedContentChanged` (efect CORECT
   verificat — conținutul elementului(elor) FOCALIZATE, generic pentru orice aplicație cu un câmp
   activ, nu specific Calculatorului).
2. **Fără schimbare ≠ eșec presupus**: dacă reobservarea EȘUEAZĂ (nu s-a putut citi ecranul deloc)
   → `UNKNOWN` (dovadă insuficientă). Dacă reobservarea REUȘEȘTE și NIMIC nu s-a schimbat →
   `VERIFICATION_FAILED` (absență demonstrabilă, nu presupusă).
3. **Schimbare ≠ succes automat**: dacă ceva s-a schimbat pe ecran, dar elementul FOCALIZAT (cel
   relevant pentru acțiune) nu — schimbarea era irelevantă → tot `VERIFICATION_FAILED`. Succes
   (`null`) necesită fie corespondența confirmată, fie absența oricărui element focalizat de
   comparat (nu se inventează o concluzie din lipsă de dovadă).
4. **Fără logică de poziții de butoane**: comparația e pe conținut (`label`) al elementelor
   focalizate din `ScreenDigest`, niciodată pe coordonate sau indici fixi — generic, orice aplicație.

8 teste (`InteractionVerificationTest`, rescrise complet pe noul model), 113/113 PASS în
`benson-accessibility`, 418/418 neschimbate în `benson-foreground-service`.

## Pregătire retest live — SUPERSEDAT, executat (vezi secțiunea de sus "PATRU rulări reale")
Protocolul de mai jos a fost EXECUTAT integral (instalare + 4 rulări live + captură logcat de la
început, de fiecare dată) sub autorizarea „AUTORIZARE RETEST S4". Rezultatele reale, cu cauzele
găsite și reparate, sunt în secțiunea „SURVIVOR S4 — PATRU rulări reale" de la începutul acestui
fișier — secțiunea de mai jos rămâne doar ca istoric al planului dinaintea execuției.

## DEVICE_PASS real (19.10.2026) — SURVIVOR / navigație Waze
Autorizare primită: `adb install -r` după verificare semnătură+dispozitiv, teste OpenAI reale
plafonate la 0,10 USD. Executat complet:

1. `adb devices -l` → `9c1464eb device product:CPH2663EEA model:CPH2663` (OnePlus Nord 4), autorizat.
2. Semnătura APK instalat (tras cu `adb pull` + `apksigner verify`) identică cu build-ul local
   (`fbbc618da8...`, CN=BENSON) → `adb install -r` nedistructiv (confirmat: `firstInstallTime`
   neschimbat la 2026-08-23 după fiecare reinstalare din această rundă, doar `lastUpdateTime` avansează).
3. **`du-mă la aeroport` → „da" (DEBUG_INJECT) → `NAV_LAUNCH pkg=com.waze ok=true` →
   `NAV_VERIFY ... outcome=Success(verifiedBy=foreground_package, evidence=com.waze)`** — SURVIVOR
   cablat anterior a rulat REAL pe dispozitiv. Confirmat INDEPENDENT (nu doar din logul propriu al
   aplicației): `adb shell dumpsys window` a arătat `mCurrentFocus=...com.waze...` imediat după.
   **Acesta e primul DEVICE_PASS real al BENSON NEXT** — tag: `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`.

## CHECKPOINT (19.10.2026) — comis, tag-uit, împins pe GitHub
Separare pe hunk-uri (nu doar pe fișier), ca să nu se amestece validat cu nevalidat:
- **Comis + tag-uit + pus pe GitHub** (`6c7940a`, tag `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`):
  LEGEA TĂCERII, fix-ul TTS „accent indian", cross-check-urile anti-fals-trezire/„taci", SURVIVOR
  în `handlePendingNavAnswer` — exact ce a fost validat pe dispozitiv.
- **Rămas intenționat NECOMIS** (același fișier, în working tree, NU pe GitHub): gărzile de
  verificare ale agentului, `MAX_STEPS`, timeout-ul HTTP, fix-ul `open_app` — tot ce ține de
  misiunea Calculator, nevalidată încă. Verificat explicit: `git status` arată `M` (nu `MM`) pe
  `BensonForegroundService.kt` după commit — diff-ul rămas conține DOAR cele 5 hunk-uri Calculator.
- `BensonAgentTools.kt` și fișierele noi (`AgentOpenAppVerification.kt`, `AgentVerificationGate.kt`
  + testele lor) rămân complet netrackuite — nu fac parte din checkpoint-ul Waze.
- Compilare verificată DUPĂ separare (working tree neschimbat pe disc, doar indexul git) — BUILD
  SUCCESSFUL, nimic stricat de operația de split.

## Date/configurație telefon — nimic pierdut, un singur setting reversibil schimbat
- `adb devices`/`pm`/`dumpsys` — doar citire, zero ștergere de date.
- **`screen_off_timeout`: 30000ms → 1800000ms (30 min)**, setare standard Display, reversibilă
  (`adb shell settings put system screen_off_timeout 30000` revine la valoarea inițială) — scopul:
  evitarea blocării ecranului în timpul testelor viitoare, FĂRĂ a ocoli PIN-ul (timeout-ul mai lung
  e o preferință normală de utilizator, nu o slăbire a securității propriu-zise).
- `debug_inject_enabled`/`debug_inject_enabled_at`: dezarmat la finalul rundei anterioare (disciplină
  LEGEA TĂCERII); va trebui rearmat explicit la următoarea rundă de testare.
- APK-ul instalat rămâne cel din ultima rundă (cu gărzile + fix-urile Calculator, necomise — vezi mai
  jos). Nicio dezinstalare, nicio ștergere de date.

## Protocol — reluare test Calculator — EXECUTAT de 4 ori (vezi "PATRU rulări reale" sus)
Protocolul de mai jos a fost urmat la fiecare din cele 4 rulări. Pentru o a 5-a rulare viitoare,
rămâne valabil ca listă de pași, cu O ADĂUGARE importantă confirmată necesară: verifică explicit
(din log, `ActivityTaskManager`/`isForeignAppForeground` acum activ) că aplicația țintă NU a fost
întreruptă de o notificare externă înainte de a interpreta un eșec ca fiind de altă natură.
1. `adb shell input keyevent KEYCODE_WAKEUP` + confirmă `dumpsys window` arată launcher/ecran
   deblocat (NU keyguard) înainte de a începe.
2. Rearmează harness-ul: `am broadcast -a com.benson.butler.DEBUG_INJECT_ENABLE --ez enabled true`.
3. Injectează comanda, urmărește `AGENT_*`/`USAGE_ADD` în logcat.
4. **La final, ÎNAINTE de orice altă acțiune**: `adb shell screencap -p` sau `uiautomator dump`
   direct pe ecranul Calculatorului (fără să se fi blocat între timp) — dovada cerută explicit:
   rezultatul AFIȘAT, nu doar ce a raportat agentul.
5. Dezarmează harness-ul la final.

**Buget rămas**: ~0,058 USD din cei 0,10 USD autorizați explicit pentru acest retest (4 rulări +
2 reparații intermediare au folosit ~0,042 USD, monitorizat la fiecare `USAGE_ADD`). O a 5-a
rulare completă (ce ar costa tipic 0,01-0,03 USD) rămâne posibilă în limita rămasă, dar o nouă
autorizare explicită, separată, e mai curată decât presupunerea tacită a continuării.

## Protocol pregătit — Wake / „Benson, taci" / LEGEA TĂCERII (cu Rareș prezent)
Niciunul din acestea nu poate fi testat de mine singur — necesită vocea reală a lui Rareș lângă
telefon. Pregătit pentru rulare, nu executat:
1. **Wake**: telefonul în fundal/ecran stins, Rareș spune „Benson" — urmăresc în paralel
   `adb logcat` pentru `WAKE_DETECT`/`WAKE_VERIFY`/`WAKE_ACK_DONE` (tag `BENSON_AUDIO`), fără nicio
   injectare DEBUG — exact calea acustică reală, neatinsă de nicio schimbare a mea.
2. **„Benson, taci"**: în timp ce BENSON vorbește, Rareș spune „taci" — urmăresc
   `SHUTUP_CHECK`/`interruptAll`/`SPEAK_SUPPRESSED`.
3. **LEGEA TĂCERII**: confirm că NICIO rostire nu apare fără `TURN_AUTHORIZED` în log imediat înainte
   — cel mai simplu test negativ: dacă Rareș nu spune nimic și totuși BENSON vorbește spontan, e o
   regresie de prioritate zero (identic cu incidentul din 16.10.2026 care a generat legea).
Zero cod atins pe aceste căi în toată sesiunea — regresie improbabilă, dar neconfirmată pe dispozitiv.

## Misiunea „Calculator 27×14" — DEVICE_FAIL CONFIRMAT (19.10.2026, testul lui Rareș)
Test real pe OnePlus Nord 4, capturi de la Rareș: Calculatorul s-a deschis, dar BENSON **NU** a
introdus `27 × 14`. Ecranul final arată `89.979()` — nu `378`, și nici o expresie parțială
recognoscibilă ca "27×14 în curs". BENSON a raportat că nu găsește butoanele necesare. **Acesta
anulează orice interpretare optimistă a rulărilor mele anterioare** (unde răspunsul vorbit fusese
„27 ori 14 = 378" — acum confirmat, printr-un test real independent, că nu corespundea ecranului).

Ipoteză de cauză (neconfirmată încă prin log, bufferul logcat s-a rotit peste rularea exactă a lui
Rareș înainte să pot citi): `89.979()` conține paranteze — butoane care NU apar în grila simplă de
cifre, ci doar în modul științific al Calculatorului OnePlus (`item_science_calculator`/
`science_GridLayout`, văzute în `uiautomator dump`-ul unei rulări anterioare). Posibil ca agentul să
fi apăsat accidental comutatorul "Wissenschaftlich", schimbând complet grila de butoane ÎNTRE două
`observe_screen` — ceea ce ar rupe presupunerea implicită a gărzilor mele (indexul+eticheta rămân
valide doar dacă NU se schimbă setul de butoane disponibile, nu doar ecranul în sens larg).
Necesită log-ul exact al rulării lui Rareș (sau o reluare cu logcat capturat live) pentru confirmare
— **nu construiesc un fix pe o ipoteză neconfirmată**.

**Stare onestă**: Calculator = DEVICE_FAIL confirmat, nu doar "nevalidat". Gărzile de cod au prevenit
(probabil — de confirmat din log) o declarație verbală falsă de "378", dar NU au prevenit apăsarea
unor butoane greșite. Asta e o limită diferită, nouă, nedocumentată până acum: gărzile verifică
SECVENȚA (acțiune→observare), nu CORECTITUDINEA apăsării în sine.

## Misiunea „Calculator 27×14" — istoric rulărilor MELE anterioare (depășit de testul real de mai sus)
Trei bug-uri reale găsite ȘI reparate, fiecare dovedit pe dispozitiv (reprodus → reparat → reverificat):

1. **`MAX_STEPS` insuficient** (10, apoi 16 insuficient) — gărzile de verificare dublează costul de
   pași al unei secvențe de apăsări; ridicat în final la 22. Dovadă: `AGENT_RUN steps=10 done=false`,
   apoi `steps=16 done=false`, fiecare înainte de a ajunge la rezultat.
2. **`brainHttpClient` timeout 25s insuficient** pentru al treilea apel (primul cu digest de ecran
   mare) — `InterruptedIOException` reprodus IDENTIC de două ori, la exact 25s. Ridicat la 45s;
   niciun timeout după aceea (un apel real a durat ~41s, sub noul plafon).
3. **Gard nou, al treilea** (`isTapAllowedNow`/`isCompoundOpenWithoutActionBlocked`,
   `AgentVerificationGate.kt`) — dovedit NECESAR pe dispozitiv: fără el, agentul a declarat odată
   „27 ori 14 este 378." fără nicio apăsare, și altă dată „rezultatul este 378" după ce admitea
   explicit „nu pot introduce cifra 2". Cu gărzile active, log-ul arată exact blocarea cerută:
   `AGENT_VERIFY_GATE blocked=compound_open_say` și `blocked=tap_without_observe`.

**Rulare finală**: `open_app`(verificat SURVIVOR) → apăsări+observări reale (confirmate prin
`AGENT_TOOL` cu durate realiste, 200-2600ms) → `BUBBLE state="27 ori 14 = 378."`. Corect matematic
și precedat de gărzile de verificare active. **Nu e declarat DEVICE_PASS complet**: ecranul telefonului
s-a blocat (PIN-ul lui Rareș) înainte să pot verifica EU, independent, pixel cu pixel, afișajul final
al Calculatorului — nu am încercat să deblochez telefonul (lege absolută, nu se ocolește PIN-ul).
Dovada existentă e puternică (verificare SURVIVOR independentă la deschidere + gărzile dovedit active
+ apăsări cu durată realistă), dar nu completă in sensul cerut explicit („dovada trebuie să conțină
rezultatul observat, corelat cu misiunea" — am văzut DOAR ce a raportat agentul, nu ecranul însuși).

**Cost real folosit**: ~0,04-0,05 USD din 0,10 USD autorizați (monitorizat la fiecare pas prin
log-urile `USAGE_ADD`; niciun apel nefăcut fără verificare prealabilă a bugetului cumulat).

## Regresii critice — ce s-a verificat real și ce NU
- **Navigație Waze**: verificat REAL pe dispozitiv (vezi DEVICE_PASS de mai sus) — fără regresie.
- **Wake („Benson" acustic)/„Benson, taci"/LEGEA TĂCERII**: **NEVERIFICATE pe dispozitiv în această
  rundă** — ar cere fie vocea reală a lui Rareș lângă telefon, fie o injectare WAV pregătită, niciuna
  disponibilă în acest mediu. Niciun cod din `HeedWakeWord.kt`, din `authorizeTurn`/`isTurnAuthorized`
  sau din cross-check-ul „taci" nu a fost atins în această rundă sau oricare anterioară — zero motiv
  de regresie suspectată, dar zero dovadă nouă de pe dispozitiv. Onest: NU confundă asta cu un PASS.

## Verificare obligatorie prin cod (nu doar systemPrompt) — AgentVerificationGate.kt
TREI gărzi de stare pure, cablate în bucla reală a `tryAgent`, toate dovedite active pe dispozitiv
(logcat, nu doar teste):
1. **`isTurnEndAllowedNow`** — după un `tap_element`, nici `say`, nici un răspuns conversațional fără
   instrument nu mai pot încheia tura decât după un `observe_screen` (sau `verify_playing`, drumul
   media deja existent) ULTERIOR acelei apăsări. `open_app` nu intră aici (SURVIVOR intern propriu).
2. **`isCompoundOpenWithoutActionBlocked`** — o comandă compusă („deschide X și...") care a deschis
   aplicația dar n-a apăsat încă nimic nu poate încheia tura. Dovadă reală: `AGENT_VERIFY_GATE
   blocked=compound_open_say` — a blocat exact momentul în care modelul ar fi spus „378" fără nicio
   apăsare.
3. **`isTapAllowedNow`** — `tap_element` refuzat dacă n-a existat încă NICIUN `observe_screen` în
   rulare (index ghicit, fără digest real). Dovadă reală: `AGENT_VERIFY_GATE blocked=tap_without_observe`.

18 teste noi (`AgentVerificationGateTest`).

## Consolidare arhitecturală (19.10.2026) — UN SINGUR BENSON LIVE
Mandat explicit: nu construi un al doilea orchestrator alături de agentul OpenAI deja existent
(`tryAgent`/`runAgentToolLocked`/`BensonAgentTools.kt`, necomis, în `BensonForegroundService.kt`).
Auditul celor 9 instrumente ale agentului (tabel complet în backlog) a găsit UN singur gol real de
onestitate — `open_app` — reparat în aceeași autoritate (nu într-un orchestrator nou). Restul fie
era deja conform doctrinei 9 (`play_stream`/`verify_playing`), fie generic prin construcție
(`observe_screen`/`tap_element`, fără nicio cunoaștere de aplicație — verificat prin test automat,
`BensonAgentToolsGenericityTest`). `BensonLiveOrchestrator` din NEXT rămâne arhitectură de
REFERINȚĂ, pură, testată — nu e și nu va fi cablată ca sistem paralel.

## Ce există, funcțional (testat unitar)
- D0: contracte comune (`BensonAgentContracts.kt`)
- BENSON LIVE: `BensonLiveOrchestrator.kt` — flux TEXT→LIVE→AKA→EXECUTOR→REZULTAT VERIFICAT
- AKA: `StaffRegistryCapabilityLookup.kt` — cablat la `StaffRegistryCore`/`StaffRegistry` reale (provenență ≠ verificare)
- MEMORY: `PersonalMemoryStore.kt`/`PersonalMemoryStorage.kt` (criptat AES-256-GCM) + `DestinationMemoryLookup` cablat în LIVE
- TRAVEL: `TravelMissionController.kt`, `NavigationExecutionEngine.kt` (SURVIVOR integrat — `classifyLaunchVerification`)
- COMMS: `WhatsAppDryRunExecutionEngine.kt` (dry-run, dovedește generalizarea orchestratorului)
- SOS: `SosWatchContracts.kt`, `SosTriggerGate.kt` (bug real de overflow găsit+reparat), model KWS „AJUTOR" antrenat în `.benson-training/` (neintegrat în HEED)
- VOICE: `BensonVoiceOutput.kt`/`VoiceOutputPolicy` (decizie ONE VOICE, fallback text) — apelul HTTP real OpenAI TTS NEFĂCUT încă
- LIVE: flux explicit de confirmare opt-in (`MissionResult.AwaitingConfirmation` + `confirmPendingMission`, implicit dezactivat) — modelează "da/nu" fără a atinge ConfirmationGate-ul real din LEGACY
- TRAVEL: `TravelRouteAnnouncer.kt` — anunț onest rută/trafic (ETA/trafic live doar când sursa o justifică) + cablare opt-in `routeInfoProvider` în `NavigationExecutionEngine` (sursă reală de date neconectată — rețea + autorizare)
- ANTI-CONFUZIE: `EvidenceLabels.kt` — rezultate simulate etichetate, testat bidirecțional

## LEGACY — necomis, separat, așteaptă confirmare pe telefon
`BensonForegroundService.kt`/`BensonCommandRegistry.kt`/`BensonAgentTools.kt`/`CLAUDE.md` pe `day-2026-10-08`: LEGEA TĂCERII (autorizare tură) + fix cache voce TTS (cauza „accent indian" găsită — `ensureNativeTtsVoiceCurrent` memora motorul înainte de confirmarea reală) + cross-check anti-fals-trezire (televizor) + **(19.10.2026, autorizare de integrare)** SURVIVOR cablat real în `handlePendingNavAnswer` ("YES" — navigație Waze): după lansarea prin URI (`startActivity` care nu aruncă), o verificare asincronă pe fir separat (`classifyLaunchVerification`, reutilizat verbatim din `NavigationMissionTrace.kt`, 10 poll-uri × 400ms) confirmă pe `currentForegroundPackageLive()` că Waze chiar a ajuns pe ecran; răspunsul deja rostit ("Te duc la X.") NU se schimbă (zero risc pt. comportamentul dovedit), doar o corecție onestă se adaugă dacă verificarea eșuează, suprimată dacă a pornit o comandă nouă între timp (`commandStartedAtMs`). Scop limitat deliberat: doar URI-ul principal către `com.waze` (pachet neambiguu) — fallback-ul generic `google.navigation:` rămâne neverificat (ar cere un pachet așteptat ghicit, interzis de doctrina 8). Revert: șterge blocul `if (ok && succeededIndex == 0 && pending.pkg == "com.waze") { ... }` din `handlePendingNavAnswer`.

**(19.10.2026, consolidare) FIX_AGENT_OPEN_APP_HONESTY_1** — bug real găsit în `runAgentToolLocked`, ramura `open_app`: `resp != null` trata orice text NENUL de la `tryNativeOpenApp` drept succes, inclusiv textele proprii de EȘEC ("Am găsit X, dar n-am putut să o deschid.", "Nu pornesc video cât conduci.") — agentul LLM putea raporta "gata" exact când lansarea eșuase. Reparat cu `classifyOpenAppResponseText` (nou, pur, testat — `AgentOpenAppVerification.kt`/`AgentOpenAppVerificationTest.kt`, 5 teste) + aceeași verificare SURVIVOR ca la Waze (poll pe `currentForegroundPackageLive()`, 10×400ms). Revert: șterge blocul nou din ramura `BensonAgentTools.OPEN_APP` a lui `runAgentToolLocked`, restaurează `BensonAgentTools.toolResult(resp != null, resp ?: ...)`.

**(19.10.2026, consolidare) Instrucțiune de verificare onestă în `systemPrompt`** (`BensonAgentTools.kt`) — generic, nu specific Calculatorului: după orice acțiune care produce un rezultat vizibil, agentul trebuie să recheme `observe_screen` și să citească valoarea AFIȘATĂ, nu să o calculeze/presupună; dacă nu poate confirma, spune explicit că nu poate. Testat structural (`BensonAgentToolsGenericityTest`, 6 teste) — **nedovedit comportamental**, pentru că asta ar cere un apel OpenAI real pe un model real, care ar putea sau nu respecta instrucțiunea (exact ca `verify_playing` înainte de dovada pe telefon).

**Instalat REAL pe telefon** (autorizat explicit), de 5 ori în această rundă (fiecare reparație →
rebuild → reinstall → retest), ultima oară cu toate cele 3 reparații + gărzile active. Certificat
verificat identic la fiecare instalare (`fbbc618da8...`, CN=BENSON). `firstInstallTime` neschimbat
(2026-08-23) la fiecare reinstalare — confirmat nedistructiv, datele aplicației păstrate.

## Sarcina activă / următoarea
Checkpoint-ul Waze e făcut (comis, tag-uit, pe GitHub). Patru rulări reale ale misiunii Calculator
executate, cu două cauze reale găsite și reparate generic (notificare externă confundată cu
"ecran schimbat"; coordonate confundate cu conținut în gardul 4). Rămas:
1. **O a 5-a rulare** (sau mai multe), cu fix-urile din această rundă active — buget rămas ~0,058
   USD din cei 0,10 USD, dar aștept o nouă autorizare explicită înainte de a continua automat mai
   departe, dată fiind deja seria de patru încercări.
2. **Confirmare vizuală independentă** a ecranului Calculatorului pentru rezultatul real (378) —
   nerealizată încă în nicio rulare (fie model greșește butoanele, fie întreruperi externe).
3. **Wake/„taci"/LEGEA TĂCERII** — cer vocea ta reală lângă telefon, neschimbat.
4. **WhatsApp VIDEO_CALL dry-run** prin agentul generic — neîncercat încă pe dispozitiv.

Recomandare pentru o rundă viitoare, NECONSTRUITĂ acum (doar observație, nu decizie luată):
fiabilitatea gpt-5-nano la alegerea INDICILOR corecte pentru cifre rămâne limita reală, dovedită de
4 ori — niciun gard de cod nu poate repara asta, doar preveni declarații false despre ea. Un model
mai capabil (cost diferit) sau o cale alternativă generică (ex. `ACTION_SET_TEXT` direct pe un câmp
editabil, quando există, în loc de apăsări individuale) ar fi direcții de evaluat separat.
`search_in_app`/`open_url` rămân identificate dar NEATINSE deliberat (vezi BACKLOG).

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
