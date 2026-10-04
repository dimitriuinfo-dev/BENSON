# RUNDA W-1 — Barge-in

Scope: `BensonForegroundService.kt` exclusiv. Nicio schimbare în `HeedWakeWord.kt`, JS, sau alt modul.

## AUDIT

- **AudioSource bucla HEED:** `MediaRecorder.AudioSource.VOICE_RECOGNITION` (`HeedWakeWord.kt:163`), neschimbat.
- **`AcousticEchoCanceler.isAvailable()` pe acest device:** cod adăugat (`aecAvailable`, lazy, static — nu cere o sesiune anume); valoarea reală apare în logul `WAKE_SCORE` la următoarea comandă vocală.
- **Se oprea bucla wake la TTS propriu, înainte de această rundă?** Da — `nativeWakeSetOwner("TTS")` → `suspendNativeWake(owner)` → `heedWakeWord?.stop()`. Asta a fost schimbat (TASK A).

## TASK A — WAKE_DURING_PLAYBACK

- `nativeWakeSetOwner`: cazul `"TTS"` nu mai cheamă `suspendNativeWake` — cheamă `startHeedEngineOnly()`, o funcție nouă care pornește DOAR firul motorului HEED, fără să atingă `micOwner` (spre deosebire de `armNativeWake()`, care mereu forțează `micOwner="WAKE"` — ar fi stricat watchdog-ul de TTS).
- `armNativeWake()`: garda de blocare nu mai include `"TTS"` (rămân blocate doar `COMMAND_STT`/`CALL`/`CONFIRMATION_STT`, care chiar folosesc microfonul exclusiv).
- `onHeedWakeDetected`: dacă `micOwner == "TTS"` în momentul detecției → `bargeIn = true` → log `WAKE_DURING_PLAYBACK action=barge_in` → cere focus audio fără ducking (`AUDIOFOCUS_GAIN_TRANSIENT`, nu `_MAY_DUCK`), care forțează motorul TTS Android să cedeze/oprească, fără nicio coordonare JS.
- **Revert:** mută `"TTS"` înapoi în ramura care suspendă (lângă `COMMAND_STT`/`CALL`), scoate apelul `startHeedEngineOnly`, și readaugă `"TTS"` în garda lui `armNativeWake`.

## TASK B — DUCKING

- `requestDuckFocus(mayDuck)` / `releaseDuckFocus()`: `AudioFocusRequest` cu `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`; `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` la wake normal, `AUDIOFOCUS_GAIN_TRANSIENT` (fără duck) la barge-in.
- `openSession(reason, mayDuck)` / `closeSession(reason)`: sesiune proprie acestei runde (NU e arhitectura mare de sesiune-fără-wake-word discutată separat) — ține focus-ul cerut timp de `FOLLOW_UP_WINDOW_MS = 8000`, apoi eliberează automat (`closeSession("timeout")`) dacă nu se renews printr-un wake nou.
- Log: `DUCK on|off holder=wake mode=duck|stop result=…`, `SESSION open|close reason=…`.
- Eliberat și la `onDestroy()` (nu rămâne focus agățat dacă serviciul repornește).
- **Revert:** șterge blocul de câmpuri/funcții (`audioManager`, `duckFocusRequest`, `sessionOpenUntil`, `sessionEpoch`, `SessionWindow`, `requestDuckFocus`, `releaseDuckFocus`, `openSession`, `closeSession`) și apelul din `onHeedWakeDetected`/`onDestroy`.

## TASK C — AEC: parțial, STOP pe atașare

- Implementat: `aecAvailable` (capabilitate device, `AcousticEchoCanceler.isAvailable()`), logat în `WAKE_SCORE` la fiecare detecție (`aecAvailable=true|false aecAttached=false`).
- **NEIMPLEMENTAT, intenționat:** atașarea reală (`AcousticEchoCanceler.create(audioSessionId)`) pe sesiunea `AudioRecord` a buclei HEED. Acel `AudioRecord` e `private` în `HeedWakeWord.kt`, fișier în afara scope-ului permis această rundă ("Permis: BensonForegroundService.kt" exclusiv). Pentru a continua, `HeedWakeWord.kt` ar avea nevoie de un singur getter public (`audioRecord?.audioSessionId`) — cer permisiune explicită pe acel fișier într-o rundă viitoare, nu o adaug acum.
- Deci testul A/B de volum (Task C's "WAKE_SCORE cu AEC on vs off") nu poate produce încă o comparație reală — doar `aecAvailable` (constant per test, nu comutabil on/off fără atașare).

## Build

(vezi output-ul comenzilor de mai jos)
