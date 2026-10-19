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

## Protocol pregătit — reluare test Calculator (NU executat, așteaptă autorizare)
1. `adb shell input keyevent KEYCODE_WAKEUP` + confirmă `dumpsys window` arată launcher/ecran
   deblocat (NU keyguard) înainte de a începe — cu timeout-ul de 30 min, nu ar trebui să se mai
   blocheze singur în mijlocul testului.
2. Rearmează harness-ul: `am broadcast -a com.benson.butler.DEBUG_INJECT_ENABLE --ez enabled true`.
3. Injectează comanda, urmărește `AGENT_*`/`USAGE_ADD` în logcat ca până acum.
4. **La final, ÎNAINTE de orice altă acțiune**: `adb shell screencap -p` sau `uiautomator dump`
   direct pe ecranul Calculatorului (fără să se fi blocat între timp) — dovada cerută explicit:
   rezultatul AFIȘAT, nu doar ce a raportat agentul.
5. Dezarmează harness-ul la final (disciplină neschimbată).
Cere autorizare separată de cost API înainte de rulare (bugetul de 0,10 USD anterior a fost deja
folosit parțial — ~0,04-0,05 USD rămân, dar o nouă autorizare explicită e mai curată decât reluarea
uneia vechi).

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
Checkpoint-ul Waze e făcut (comis, tag-uit, pe GitHub). Rămas, strict cu prezența/autorizarea ta
(nimic de construit în plus fără asta — vezi protocoalele pregătite mai sus):
1. **Confirmare vizuală independentă** a ecranului Calculatorului pentru „27×14 = 378".
2. **Wake/„taci"/LEGEA TĂCERII** — cer vocea ta reală lângă telefon.
3. **WhatsApp VIDEO_CALL dry-run** prin agentul generic — neîncercat încă pe dispozitiv.

Fără autorizare nouă de cost API, nu mai fac alte apeluri OpenAI. Fără arhitectură nouă pentru
aceste teste (cerut explicit). `search_in_app`/`open_url` rămân identificate dar NEATINSE deliberat
(vezi BACKLOG — risc de regresie grăbită mai mare decât valoarea imediată).

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
