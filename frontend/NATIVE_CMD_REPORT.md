# NATIVE_CMD_1 — captură nativă completă pentru comenzi simple

Scope folosit: `BensonForegroundService.kt` · `modules/benson-audio-capture/**` (refolosire) · acest fișier.
Ieșire din scope, minimă și necesară: `modules/benson-foreground-service/android/build.gradle` (o linie —
dependința Gradle către modulul `benson-audio-capture`, obligatorie ca `CaptureEngine` să fie apelabil
cross-modul; fără ea nimic din runda asta nu compilează). Niciun alt fișier atins.

## 1. CAPTURĂ — refolosire, nu rescriere

`BensonAudioCaptureModule.kt` (singurul fișier din `benson-audio-capture`) avea logica de VAD/pre-roll/WAV
scrisă inline, cuplată de `appContext`/`sendEvent` (Expo Module) — neapelabilă direct dintr-un `Service`
simplu. Am extras-o verbatim (aceleași constante, aceeași logică, nicio valoare schimbată) într-un obiect
Kotlin nou, `CaptureEngine.kt`, în același pachet. `BensonAudioCaptureModule.kt` acum doar deleagă către el
(comportament identic pentru calea JS existentă — zero regresie). `BensonForegroundService.kt` apelează
același `CaptureEngine.capture(...)`.

## 2. STT — Deepgram + fallback Groq, din serviciu

Cheile sunt deja împinse din JS în `benson_watchdog_prefs` (`wake_stt_deepgram_api_key` via
`setWakeDeepgramCredentials`, `wake_stt_api_key`/`wake_stt_base_url`/`wake_stt_model` via
`setNativeWakeCredentials` — ambele deja apelate la deschiderea aplicației, `app/index.tsx`). Kotlin le
citește deja — **nu a fost nevoie de Keystore/EncryptedSharedPreferences**, condiția ta era deja
îndeplinită de infrastructura existentă. Niciodată logate.

## 3. COMENZI SIMPLE NATIVE

`tryNativeOpenApp`: regex `deschide(-mi)? <X>`, potrivire fuzzy pe eticheta aplicațiilor din
`PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)` (fără index hardcodat), lansare prin
`getLaunchIntentForPackage` + `startActivity`. Orice altceva → `onHotwordDetected(...)`, exact calea JS de
azi.

## 4. Schimbare de arhitectură necesară, neanticipată în cerere

**Ack-ul nu mai e instant.** Varianta anterioară (runda „ack nativ") vorbea „Da, Master" imediat, înainte de
orice — 3ms. Dar contractul din `CLAUDE.md` („Benson, <comandă>" dintr-o suflare → fără „Da, Master") impune
ca decizia bară-vs-comandă să se ia DUPĂ ce știm ce s-a spus, iar singurul mod de a ști e să rulăm STT.
Am mutat `speakNativeAckThenCapture()` după rezultatul STT pe bufferul original — ack-ul acum vine la
~1-2s (durata STT), nu la 3ms. E trade-off-ul corect pentru contract, dar diferit de ce am raportat
anterior; semnalez explicit.

**Veriga lipsă găsită și reparată:** `TextToSpeech.speak()` nu avea `UtteranceProgressListener` — nimic nu
știa când se termină „Da, Master". Adăugat; `onDone` → `WAKE_ACK_DONE` → `startFollowUpCapture()`.

## Log

`WAKE_SCORE` · `STT_REQUEST provider=…` · `STT_RESULT provider=… text=… chars=…` · `WAKE_ACK_DONE` ·
`CMD_CAPTURE start|end bytes=… reason=…` · `NATIVE_ROUTE action=open_app|js_handoff target=…` ·
`APP_LAUNCH pkg=… ok=…` · `SESSION open|close reason=…`.

## Constantă de revert

Restaurează `onHeedWakeDetected` la varianta dinaintea acestei runde (git), șterge tot blocul
`NATIVE_CMD_1` din `BensonForegroundService.kt`, linia din `build.gradle`, și `CaptureEngine.kt`
(readu `BensonAudioCaptureModule.kt` la varianta inline).

## Build / test

Vezi raportul din conversație pentru BUILD SUCCESSFUL, hash, certificat, și rezultatul testului pe
dispozitiv (5× „Benson" → „Da, Master" → „deschide YouTube"; 5× „Benson, deschide Spotify" dintr-o
suflare).
