# BENSON — Ghid test diagnostic vocal (pe device, fără adb)

Scop: să vezi cu ochii tăi, pe OnePlus, exact unde se rupe lanțul
microfon → recunoaștere → transcript → orchestrator. Fără presupuneri.

## Cum ajungi la ecran
1. Build dev/EAS normal (ecranul e cod JS pur, nu cere modul nativ nou).
2. Deschide **Debug Panel** (locul unde testezi comenzile scrise).
3. Apasă butonul auriu **🎤 OPEN VOICE DIAGNOSTIC**.

## Ce faci
1. Apasă **Check device** — arată dacă telefonul are un serviciu de recunoaștere
   (`isRecognitionAvailable`, serviciul default, dacă suportă on-device). Pe OxygenOS,
   dacă `isRecognitionAvailable = false` sau serviciul default nu e Google → aici e cauza.
2. Alege limba (implicit `ro-RO`). Poți încerca și `en-US`/`de-DE`.
3. Apasă **START LISTENING TEST** și rostește o comandă (ex: „deschide Waze").
4. Citește cei **4 pași** de sus și **LIVE EVENT TRACE**.

## Cum citești rezultatul (cei 4 pași)
- **1 Permisiune** ✓ = RECORD_AUDIO acordat (verificat programatic, nu presupus).
- **2 Sesiune pornită** ✓ = a venit evenimentul nativ `start`. Dacă rămâne ✕ → sesiunea nu pornește
  (serviciu de recunoaștere lipsă/blocat).
- **3 Motorul a întors ceva** ✓ = a venit `result`, `nomatch` sau `error`.
  - „Mic energy: peak RMS ..." care se mișcă = **microfonul chiar captează sunet** (hardware + permisiune + sesiune OK).
  - `nomatch` = mic OK, dar motorul n-a înțeles cuvinte (limbă greșită? zgomot?).
  - `error code=... ` = vezi codul exact (ex: `network`, `service-not-allowed`, `no-speech`).
- **4 Transcript** ✓ = a venit text (chiar greșit = dovadă că merge).
  Dacă „Auto-route" e pornit, transcriptul e trimis la **același** orchestrator ca la comenzile scrise,
  iar răspunsul apare la **ORCHESTRATOR REPLY**.

## Ce înseamnă rezultatul (unde reparăm)
- **Merge aici (apare transcript), dar NU merge în aplicația live** →
  bug-ul e în wiring-ul BENSON (pornirea ascultării e „ascunsă" după callback-ul de TTS
  `speakText(...)` din `app/index.tsx`, sau un guard `listeningRef/loadingRef/speakingRef`
  rămas blocat pe `true`). Reparăm exact acolo.
- **NU merge nici aici (pas 2 sau 3 pică)** → problema e recognizer/permisiune/device
  (serviciu de recunoaștere absent pe OxygenOS, model de limbă nedescărcat, sau permisiune revocată).
  Reparăm exact la pasul care pică (ex: comutăm pe engine on-device + descărcare model, sau alt serviciu).

## Ce-mi trimiți înapoi
Un **screenshot** cu:
- cei 4 pași (bifele),
- „Mic energy",
- ultimele linii din „LIVE EVENT TRACE" (mai ales orice `error code=...`),
- rezultatul de la **Check device**.

Cu poza aia, reparăm fix la locul care pică — nu în altă parte.
