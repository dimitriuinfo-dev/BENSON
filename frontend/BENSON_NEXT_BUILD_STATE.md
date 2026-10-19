# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`6a033ab` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).

## Teste
389/389 PASS la ultimul commit (`./gradlew :benson-foreground-service:testDebugUnitTest`). **Niciun DEVICE_PASS** — tot ce există e cod compilat + unit-testat cu fakes/dry-run, zero validare pe telefon (acest mediu nu are `adb`/dispozitiv conectat).

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
`BensonForegroundService.kt`/`BensonCommandRegistry.kt`/`CLAUDE.md` pe `day-2026-10-08`: LEGEA TĂCERII (autorizare tură) + fix cache voce TTS (cauza „accent indian" găsită — `ensureNativeTtsVoiceCurrent` memora motorul înainte de confirmarea reală) + cross-check anti-fals-trezire (televizor) + **(19.10.2026, autorizare de integrare)** SURVIVOR cablat real în `handlePendingNavAnswer` ("YES" — navigație Waze): după lansarea prin URI (`startActivity` care nu aruncă), o verificare asincronă pe fir separat (`classifyLaunchVerification`, reutilizat verbatim din `NavigationMissionTrace.kt`, 10 poll-uri × 400ms) confirmă pe `currentForegroundPackageLive()` că Waze chiar a ajuns pe ecran; răspunsul deja rostit ("Te duc la X.") NU se schimbă (zero risc pt. comportamentul dovedit), doar o corecție onestă se adaugă dacă verificarea eșuează, suprimată dacă a pornit o comandă nouă între timp (`commandStartedAtMs`). Scop limitat deliberat: doar URI-ul principal către `com.waze` (pachet neambiguu) — fallback-ul generic `google.navigation:` rămâne neverificat (ar cere un pachet așteptat ghicit, interzis de doctrina 8). Revert: șterge blocul `if (ok && succeededIndex == 0 && pending.pkg == "com.waze") { ... }` din `handlePendingNavAnswer`. **Nu comite, nu instala peste aplicația reală fără confirmarea ta.**

**Build pregătit pentru acceptare**: `assembleRelease` → BUILD SUCCESSFUL, certificat verificat prin `apksigner verify --print-certs` = `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da` (CN=BENSON, OU=Dev, O=TOKKO — identic cu baseline-ul cunoscut). APK la `frontend/android/app/build/outputs/apk/release/app-release.apk`. **Neinstalat, niciun DEVICE_PASS declarat** — așteaptă testul tău pe telefon.

**De ce NU e cablat `BensonLiveOrchestrator`/`tryAgent` ca sisteme separate**: codul deja necomis conține un agent LLM cu tool-use complet funcțional (`tryAgent`/`runAgentTool`/`BensonAgentTools.kt`, gpt-5-nano, buclă mărginită, anulare pe `agentRunId`, niciodată succes fals) — exact rolul pe care `BensonLiveOrchestrator` l-ar fi jucat. Cablarea lui NEXT alături ar fi însemnat „sisteme paralele", interzis explicit. Integrarea reală a constat în a lega SURVIVOR (singura piesă nouă, genuin lipsă) de drumul existent, nu în a înlocui sau duplica agentul deja viu.

## Sarcina activă / următoarea
**Toate sarcinile izolate, sigure, neblocate din Master Build sunt FĂCUTE** (vezi `BENSON_NEXT_BACKLOG.md`, secțiunea FĂCUT). Secțiunea URMEAZĂ din backlog e acum **goală** — fiecare item rămas cere explicit una din: (a) dispozitiv real, (b) cheie API + autorizare de cost, (c) atingerea unui fișier protejat (`BensonForegroundService.kt`/`HeedWakeWord.kt`), (d) un modul/dependență nativă nouă (ex. SOS Watch Wear OS). Toate patru sunt enumerate, cu motiv, în secțiunea BLOCAT din backlog — nu sunt sarcini uitate, sunt limitele reale ale autorității curente de construcție autonomă. O sesiune viitoare cu una din aceste autorizări poate continua direct de acolo.

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
