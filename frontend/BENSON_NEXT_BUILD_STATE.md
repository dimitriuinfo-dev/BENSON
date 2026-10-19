# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`f418976` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).

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

## Misiunea „Calculator 27×14" — DEVICE EXECUTAT, NU DEVICE_PASS complet
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
Rămas de verificat, strict pe dispozitiv (nimic de construit în plus fără asta):
1. **Confirmare vizuală independentă** a ecranului Calculatorului pentru „27×14 = 378" (telefonul
   s-a blocat înainte să pot verifica eu — cere fie testul tău direct, fie o rulare viitoare unde
   ecranul rămâne deblocat până la verificare).
2. **WhatsApp VIDEO_CALL dry-run** prin agentul generic — neîncercat încă pe dispozitiv (genericitatea
   mecanismului e dovedită doar structural, prin test, nu prin execuție reală).
3. **Wake/„taci"/LEGEA TĂCERII** — regresie neverificată pe dispozitiv (vezi secțiunea de mai sus).

`search_in_app`/`open_url` rămân identificate dar NEATINSE deliberat (vezi BACKLOG — risc de
regresie grăbită mai mare decât valoarea imediată, fără dovadă că ar fi nevoie acum).

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
