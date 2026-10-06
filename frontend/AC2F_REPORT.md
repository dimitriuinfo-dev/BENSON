# RUNDA AC2-F — PASUL 0, 07.10.2026

Branch `wip_2026_10_06_ac2f`, din `21a6c75` (ultimul commit al rundei question, cel din care s-a
construit APK-ul `e15cd401…`). Runda question se închide separat, cu tag `question-2026-10-06b`,
după testul lui Rareș — nu s-a atins aici.

## TEST DE REGRESIE — diff față de 2fa9fb2 pe tot lanțul wake+verificare+chei+audio

| Fișier | Diff |
|---|---|
| `HeedWakeWord.kt` | **0** |
| `WakeVerifyMatcher.kt` | **0** |
| `NativeConfirmationListener.kt` | **0** (înainte de diagnosticul de mai jos) |
| `NativeCloudWake.kt` | **0** |
| `CaptureEngine.kt` | **0** |
| `lib/engines/settingsStore.ts` (citirea cheilor) | **0** |
| `lib/agents/audioMode.ts` | **0** |
| `BensonForegroundService.kt` | 2 diferențe izolate, ambele deja testate, din runda question — vezi mai jos |

Cele 2 diferențe din `BensonForegroundService.kt`:
1. `TTS_NATIVE_REQUEST text="…"` — o linie de log, în funcția generică de vorbit (folosită și la
   confirmări), zero schimbare de logică.
2. `waQuestionFor` → deleagă la `WaQuestionText.forKind` — refactor pur de text (confirmările
   WhatsApp), aceeași ieșire logică, acoperit de cele 91 teste JUnit ale modulului.

**Niciuna din cele 2 nu atinge `onHeedWakeDetected`, `isWakeVerified`, `wakeVerifyTranscribe`,
pragul HEED, sau fereastra de captură.**

**Concluzie: „Benson nu aude" NU e o regresie introdusă în această sesiune.** E aceeași cauză deja
documentată în `BENSON_AC2F_DOVADA.md` — prezentă încă de la baza stabilă `2fa9fb2`, doar observată
azi live.

## APK STABIL PENTRU TEST A/B

Reconstruit din `2fa9fb2` — **identic byte-cu-byte cu originalul**:
sha256 `5b1924e6e2a907e2fd948a391d9b4806df940ff5fd498cc371415749bfa8c26d` (confirmă build
reproductibil). Salvat: `C:\Users\lenovo\BENSON-stable\BENSON_5b1924e6_stable_baseline.apk`.

## PORCUPINE — mort, confirmat

`startHotwordLoop()` (apelată inclusiv din `ACTION_REVIVE`) verifică `nativeWakeAvailable()`
PRIMUL — dacă adevărat (cazul azi: model HEED prezent, credențiale native configurate), deleagă
la `armNativeWake("legacy_loop_superseded")` și se oprește, fără să mai ajungă vreodată la
`tryStartPorcupine()`. Codul Porcupine rămâne în sursă, dar e cod mort în practică pe acest build.

## CAUZA text="" — ipoteză din cod, de confirmat cu clipuri reale

`WakeVerifyMatcher.isWakeVerified(transcript, wakeName)`: `if (transcript == null) return false` —
**zero verdict bazat pe scor existent oriunde în cod**, pentru cazul offline. Comentariul din
apropiere („Accepted only via the score>=0.90 offline fallback") e **vechi**, rămas de la
`FIX_WAKE_OFFLINE_CAR_ACCEPT_1`, deja REVERTIT (BACKLOG.md) — nu descrie codul actual.

Pentru cazul online-dar-gol (ca cel prins live aseară: `result=rejected text="" connReused=true`,
nu timeout): `PRE_ROLL_SAMPLES = RATE * 1500 / 1000` — **preroll deja 1,5 s**, peste cerința de
≥1,0 s din Pasul 1. La nivel de cod, un preroll insuficient NU explică evident cazul ăsta — cauza
reală (clipul chiar nu conține cuvântul? zgomot care maschează vorbirea? altceva?) cere ascultarea
clipului real, exact scopul diagnosticului de mai jos.

## DIAGNOSTIC INSTALAT, AȘTEAPTĂ TESTUL LUI RAREȘ

Build debug (semnat temporar cu certificatul de release ca să nu șteargă datele aplicației la
instalare peste build-ul release existent — `android/app/build.gradle` revenit la normal imediat
după), instalat pe dispozitiv: sha256 `499c01ff0f789a34629c9ed0728406249f18c6acc9bc472116a1fbcb1c889890`.

**Salvează necondiționat** (inclusiv cazurile „offline", pe care `retainWakeClip` existent le sare):
- Clipul de wake trimis la verificare — `diagSaveAc2fWakeClip` în `BensonForegroundService.kt`,
  apelată din `onHeedWakeDetected` imediat după `WAKE_VERIFY`. Log: `AC2F_DIAG_CLIP kind=wake
  path=… durMs=… peakRms=… score=… result=… triggeredAtMs=…`.
- Clipul de confirmare — `diagSaveAc2fConfirmClip` în `NativeConfirmationListener.kt`, apelată din
  `finishWithCapture` înainte de apelul Deepgram. Log: `AC2F_DIAG_CLIP kind=confirm path=…
  durMs=… peakRms=… speechOnsetMs=… reason=…`.

Ambele scrise în `getExternalFilesDir(null)/ac2f_diag/` (pullable prin `adb pull`, fără root).
Ambele active DOAR pe build debuggable — refuzate necondiționat pe orice build release semnat
pentru livrare, indiferent dacă acest cod ajunge sau nu în el.

**Am nevoie de Rareș**: 5 × „Benson" real (oricare rezultat — accepted/rejected/offline, toate se
salvează acum) + cel puțin o confirmare „Da"/"Nu" reală (apel/mesaj WhatsApp). După, pull:
```
adb pull /storage/emulated/0/Android/data/com.benson.butler/files/ac2f_diag ac2f_diag_clips
```
și logcat-ul complet din aceeași fereastră, pentru corelare `AC2F_DIAG_CLIP` ↔ `WAKE_SCORE`/
`WAKE_VERIFY`/`CONFIRM_LISTEN_VAD_END`.

## TESTE

- `:benson-foreground-service:testReleaseUnitTest`: toate verzi (91 teste, nicio regresie).
- `npx tsc --noEmit`: curat (nicio schimbare JS de data asta).
- `gradlew assembleDebug`: BUILD SUCCESSFUL.

## STOP — conform instrucțiunii

Niciun cod din Pasul 1-5. Aștept clipurile + confirmarea ta pentru a continua.
