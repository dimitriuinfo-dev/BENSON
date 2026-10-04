# RUNDA N-3 — cele trei ziduri

Scope folosit: `BensonForegroundService.kt` · `HeedWakeWord.kt` · `modules/benson-accessibility/**`
(doar citire `lastForegroundPackage`/`instance`, neschimbat) · `modules/benson-notification-listener/**`
(fișier nou, `MediaTransport.kt`, extras din `BensonNotificationListenerModule.kt`, acel fișier
neschimbat) · acest raport. Plus o linie în `build.gradle` (dependințe cross-modul, mecanice,
identic cu precedentul pentru `benson-audio-capture`).

## A. WAKE_FOREGROUND_POLICY

`currentForegroundPackage()` citește `BensonAccessibilityService.lastForegroundPackage` (companion
object, deja actualizat de serviciul de accesibilitate la fiecare `TYPE_WINDOW_STATE_CHANGED`,
exact mecanismul „deja dovedit" cerut). Launcher identificat prin rezolvarea
`Intent.ACTION_MAIN`/`CATEGORY_HOME`, nu un nume de pachet fix. Dacă serviciul de accesibilitate nu
rulează (`instance == null`) → comportamentul de azi (doar bulă). Ecran blocat → doar voce.

## B. CONTEXT_SEARCH

„caută/pune X" fără numele aplicației, verificat DUPĂ pattern-ul explicit „pe YouTube" (ca o mențiune
explicită să câștige mereu). Dacă `currentForegroundPackage()` e YouTube sau Spotify → caută acolo.
Spotify folosește `spotify:search:<query>`, fallback `https://open.spotify.com/search/<query>`.

## C. NATIVE_MEDIA_CTL

`MediaTransport.kt` (fișier nou) extrage verbatim `mediaSessionManager`/`activeControllers`/
`pickController` din `BensonNotificationListenerModule.kt` — același `ComponentName`, același
fallback „fără pachet = preferă ce chiar cântă". Modulul JS existent (`getActiveMediaSessions`,
`mediaControl`, `getPlaybackState`) rămâne complet neatins, cu propriile copii private neschimbate.

## D. WAKE_AEC

`HeedWakeWord.kt`: parametru nou `onCaptureStarted: (sessionId: Int) -> Unit`, apelat imediat după
`recorder.startRecording()` (exact punctul unde se loghează azi `HEED_MIC state=START`), cu
`audioSessionId`-ul real, curent. `BensonForegroundService.kt` atașează `AcousticEchoCanceler` pe
acea sesiune exactă (nu una ghicită), eliberează canceler-ul vechi la fiecare sesiune nouă. Pragul
RMS neschimbat. `WAKE_SCORE` loghează acum `aecAttached` real, nu hardcodat `false`.

## Build

Vezi raportul din conversație pentru BUILD SUCCESSFUL, hash, certificat, și rezultatele testelor.
