# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`f418976` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).

## Teste
410/410 PASS (`./gradlew :benson-foreground-service:testDebugUnitTest`). **Niciun DEVICE_PASS** — vezi `BENSON_NEXT_BACKLOG.md` pentru distincția pe 4 coloane (implementat/conectat/test automat/DEVICE_PASS).

## Telefon conectat (19.10.2026) — verificare, nu instalare
`adb devices -l`: `9c1464eb device product:CPH2663EEA model:CPH2663` (OnePlus Nord 4), autorizat.
Aplicația instalată: `com.benson.butler` v1.0.0 (versionCode 1), ultima actualizare 2026-10-16.
APK-ul instalat tras de pe telefon (`adb pull`) și verificat cu `apksigner`: semnătură **identică**
cu build-ul local (`fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da`, CN=BENSON) —
o actualizare `adb install -r` ar fi deci **nedistructivă** (același certificat → Android păstrează
datele aplicației). **Pregătit, NEINSTALAT** — aștept aprobarea explicită de instalare.

## Verificare obligatorie prin cod (nu doar systemPrompt) — AgentVerificationGate.kt
Gard de stare pur, cablat în bucla reală a `tryAgent`: după un `tap_element`, nici `say`, nici un
răspuns conversațional fără instrument nu mai pot încheia tura decât după un `observe_screen` (sau
`verify_playing`, pe drumul media deja existent) ULTERIOR acelei apăsări — tool-ul `say` e respins
tehnic (nu doar descurajat prin prompt), forțând o nouă observare. `open_app` nu intră la acest gard
(are deja propria verificare SURVIVOR internă). 10 teste noi (`AgentVerificationGateTest`).

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

**Nu comite, nu instala peste aplicația reală fără confirmarea ta.**

**Build pregătit pentru acceptare**: `assembleRelease` → BUILD SUCCESSFUL, certificat verificat prin `apksigner verify --print-certs` = `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da` (CN=BENSON, OU=Dev, O=TOKKO — identic cu baseline-ul cunoscut). APK la `frontend/android/app/build/outputs/apk/release/app-release.apk`, reconstruit după consolidarea de mai sus. **Neinstalat, niciun DEVICE_PASS declarat** — așteaptă testul tău pe telefon.

## Sarcina activă / următoarea
Auditul celor 9 instrumente ale agentului e complet (tabel în backlog). Singurul gol real reparat
(`open_app`); `search_in_app`/`open_url` rămân identificate dar NEATINSE deliberat (vezi BLOCAT —
risc de regresie grăbită mai mare decât valoarea imediată, fără dovadă că ar fi nevoie acum). Tot
ce rămâne pentru misiunea de acceptare completă („Calculator 27×14", WhatsApp VIDEO_CALL dry-run)
cere strict **telefon + apel OpenAI real** — nimic mai e de construit fără asta, doar de testat. O
sesiune viitoare cu dispozitiv/cost autorizat continuă direct cu testarea, nu cu cod nou.

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
