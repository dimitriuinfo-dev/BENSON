# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`4636f3c` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).

## Teste
349/349 PASS la ultimul commit (`./gradlew :benson-foreground-service:testDebugUnitTest`). **Niciun DEVICE_PASS** — tot ce există e cod compilat + unit-testat cu fakes/dry-run, zero validare pe telefon (acest mediu nu are `adb`/dispozitiv conectat).

## Ce există, funcțional (testat unitar)
- D0: contracte comune (`BensonAgentContracts.kt`)
- BENSON LIVE: `BensonLiveOrchestrator.kt` — flux TEXT→LIVE→AKA→EXECUTOR→REZULTAT VERIFICAT
- AKA: `StaffRegistryCapabilityLookup.kt` — cablat la `StaffRegistryCore`/`StaffRegistry` reale (provenență ≠ verificare)
- MEMORY: `PersonalMemoryStore.kt`/`PersonalMemoryStorage.kt` (criptat AES-256-GCM) + `DestinationMemoryLookup` cablat în LIVE
- TRAVEL: `TravelMissionController.kt`, `NavigationExecutionEngine.kt` (SURVIVOR integrat — `classifyLaunchVerification`)
- COMMS: `WhatsAppDryRunExecutionEngine.kt` (dry-run, dovedește generalizarea orchestratorului)
- SOS: `SosWatchContracts.kt`, `SosTriggerGate.kt` (bug real de overflow găsit+reparat), model KWS „AJUTOR" antrenat în `.benson-training/` (neintegrat în HEED)
- VOICE: `BensonVoiceOutput.kt`/`VoiceOutputPolicy` (decizie ONE VOICE, fallback text) — apelul HTTP real OpenAI TTS NEFĂCUT încă
- ANTI-CONFUZIE: `EvidenceLabels.kt` — rezultate simulate etichetate, testat bidirecțional

## LEGACY — necomis, separat, așteaptă confirmare pe telefon
`BensonForegroundService.kt`/`BensonCommandRegistry.kt`/`CLAUDE.md` pe `day-2026-10-08`: LEGEA TĂCERII (autorizare tură) + fix cache voce TTS (cauza „accent indian" găsită — `ensureNativeTtsVoiceCurrent` memora motorul înainte de confirmarea reală) + cross-check anti-fals-trezire (televizor). **Nu comite, nu instala peste aplicația reală fără confirmarea ta.**

## Sarcina activă / următoarea
Vezi `BENSON_NEXT_BACKLOG.md` pentru lista completă. Următorul pas la redeschiderea acestui fișier: primul EXECUTOR Android real (acțiune nepericuloasă, verificare SURVIVOR reală) — vezi backlog, secțiunea „ÎN LUCRU".

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
