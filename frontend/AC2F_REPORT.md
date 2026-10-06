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

---

## ANALIZA CLIPURILOR — 07.10.2026 (fără cod, doar analiză)

**Onest, înainte de concluzie**: din 5 × „Benson" + 1 confirmare testate, am recuperat date pentru
**4 din cele 5 încercări de wake și 0 din confirmare**. Logcat-ul e un buffer circular (dovedit
deja de mai multe ori în sesiunea asta) — a 5-a încercare și confirmarea s-au întâmplat probabil
înaintea ferestrei vizibile, iar liniile lor au fost deja împinse afară de spam-ul `WAKE_MIC_LEVEL`
(o linie/secundă) până am apucat să fac captura. **Fișierele WAV în sine confirmă același lucru —
exact 4 clipuri de wake pe disc, zero clipuri de confirmare** — nu e doar un gol de log, confirmarea
chiar nu a produs un fișier. Două explicații posibile, nedistinse încă: (a) s-a întâmplat înainte de
fereastra capturată și fișierul a fost... nu, fișierele nu se evică din storage ca logcat-ul, deci
dacă `finishWithCapture` ar fi rulat vreodată azi, fișierul ar exista. **Concluzie clară pe acest
punct: `NativeConfirmationListener.finishWithCapture` nu a rulat deloc azi** — fie confirmarea
testată a mers pe alt drum decât acest listener, fie ceva a oprit fluxul înainte de acel punct.
Nu ghicesc care — rămâne întrebare deschisă, posibil de clarificat cu ce comandă exact ai confirmat.

### Cele 4 clipuri de wake

| Clip | Scor | Rezultat | Durată | peakRms | Transcript |
|---|---|---|---|---|---|
| `…0,966_accepted` | 0,966 | accepted | 8000ms (**a atins plafonul max de 8s**) | 1461 | „benson" |
| `…0,950_accepted` | 0,950 | accepted | 3800ms | 1272 | „benson" |
| `…0,986_offline` | 0,986 | **offline** | 3900ms | 1337 | — (timeout 2013ms, connReused=false) |
| `…0,955_accepted` | 0,955 | accepted | 7200ms | 1193 | „benson" |

**3 din 4 au reușit curat** — transcris corect „benson", niciun semn de clipare/preroll insuficient:
durată și peakRms arată clipuri normale, nimic trunchiat sau anormal de scurt. Primul clip a atins
plafonul de 8 secunde (`MAX_COMMAND_SAMPLES`) fără 1,2s de tăcere — zgomot de fundal continuu
probabil, dar transcrierea tot a ieșit corectă.

**Singurul eșec din acest lot e „offline"** — exact Dovada 2, reconfirmată: scor local bun (0,986,
peste orice prag discutat), rețeaua a picat la 2013ms, tratat ca respingere.

### Ce NU confirmă acest lot

Capturarea anterioară (turul trecut, live) arătase și un caz „rejected text="" connReused=true" —
online, conexiune reușită, dar transcriere goală (nu timeout). **Acest lot de 4 nu reproduce acel
caz** — fie condițiile de rețea au fost mai bune de data asta (posibil Wi-Fi vs. date mobile),
fie a fost o coincidență de moment. Ipoteza „preroll insuficient" rămâne **neconfirmată ȘI
nerespinsă** din acest lot — pur și simplu n-a apărut cazul relevant pentru ea.

### Cauza, pe scurt

Din ce am putut analiza azi: **cauza dominantă, reproductibilă, e exact Dovada 2** (offline/timeout
tratat ca respingere necondiționat, fără fallback pe scor) — nu un clip corupt sau prost tăiat.
Cazul „online dar gol" rămâne o întrebare separată, văzută o dată, neconfirmată aici.

## STOP — conform instrucțiunii

Niciun cod din Pasul 1-5. Aștept confirmarea ta pentru a continua.

---

## TESTUL PASUL 1 — 14 încercări capturate pe fișier (nu buffer), 07.10.2026

Mai multe de 10 — presupun reîncercări după tăceri. Toate din `ac2f_pasul1_10x_test.log`.

| # | Ora | Scor | Rezultat | clipMs | latencyMs | connReused | „Da, Master"? |
|---|---|---|---|---|---|---|---|
| 1 | 19:02:31 | 0,849 | rejected (online, text gol) | 2500 | 1936 | false | **NU** |
| 2 | 19:02:41 | 0,832 | rejected (online, text gol) | 2500 | 1482 | true | **NU** |
| 3 | 19:05:05 | 0,889 | offline → scor<0,95 → respins | 2500 | 2008 | false | **NU** |
| 4 | 19:06:07 | 0,853 | rejected (online, text gol) | 2500 | 1386 | false | **NU** |
| 5 | 19:07:12 | 0,916 | offline → scor<0,95 → respins | 2500 | 2006 | true | **NU** |
| 6 | 19:07:30 | 0,919 | **accepted**, text="benson" | 2500 | 1141 | false | **DA** |
| 7 | 19:07:44 | 0,821 | offline → scor<0,95 → respins | 2500 | 2008 | false | **NU** |
| 8 | 19:07:57 | 0,957 | **accepted**, text="benson" | 2500 | 1708 | false | **DA** |
| 9 | 19:08:09 | 0,903 | **accepted**, text="benson" | 2500 | 891 | true | **DA** |
| 10 | 19:08:24 | 0,910 | offline → scor<0,95 → respins | 2500 | 2011 | false | **NU** |
| 11 | 19:08:38 | 0,933 | **accepted**, text="benson" | 2500 | 1273 | true | **DA** |
| 12 | 19:08:51 | 0,918 | offline → scor<0,95 → respins | 2500 | 2011 | false | **NU** |
| 13 | 19:09:05 | 0,904 | **accepted**, text="benson" | 2500 | 1333 | false | **DA** |
| 14 | 19:09:26 | 0,974 | **accepted**, text="benson" | 2500 | 1561 | false | **DA** |

**6/14 „Da, Master" (43%).** `clipMs=2500` consistent peste tot — trimmingul la fereastră fixă
funcționează corect în toate cele 14 cazuri.

### Diferența reală dintre reușite și ratări

**Nu e offline vs. online cum părea ieri — e scorul HEED însuși.** Toate cele 6 reușite au scor
≥0,903. Toate cele 8 ratări (atât „offline" cât și „rejected text gol" online) au scor ≤0,919, cu
**7 din 8 sub 0,92**. Există suprapunere (0,903 a reușit, 0,916 nu) — nu separă curat — dar
tendința e clară: **sub ~0,90, verificarea aproape sigur nu găsește cuvântul, indiferent dacă
rețeaua merge sau nu.**

**Pragul de 0,95 pentru acceptarea offline NU a prins niciun caz azi** — toate cele 5 „offline" au
scoruri 0,821-0,918, toate sub 0,95. Fix-ul de la Pasul 1(1) a avut, concret, **efect zero în acest
test**: a rămas o plasă de siguranță teoretică, nu una care a prins ceva azi.

**Nou față de Pasul 0**: acum am și 3 cazuri „rejected text gol" ONLINE (nu offline) — conexiunea a
mers, Deepgram a răspuns, dar n-a auzit nimic. Scorurile lor (0,849/0,832/0,853) sunt în aceeași
bandă joasă ca „offline"-urile — susține aceeași concluzie: cauza comună probabilă e claritatea
reală a rostirii/zgomotul de fond la momentul acelor încercări, nu un bug separat de rețea sau
verificare. Nu e ceva ce codul poate repara prin prag sau fereastră — e ce a ajuns în microfon.

---

## TESTUL PASUL 1b — 15 încercări, 07.10.2026

| # | Scor | Rezultat | Transcript | „Da, Master"? |
|---|---|---|---|---|
| 1 | 0,872 | accepted | „benson" | **DA** |
| 2 | 0,930 | accepted | „benson" | **DA** |
| 3 | 0,960 | accepted | „benson" | **DA** |
| 4 | 0,963 | offline → scor≥0,95 → **acceptat** | — | **DA** |
| 5 | 0,981 | accepted | „benson" | **DA** |
| 6 | 0,949 | accepted | „benson" | **DA** |
| 7 | 0,936 | accepted | „benson" | **DA** |
| 8 | 0,877 | accepted | „benson" | **DA** |
| 9 | 0,833 | accepted | „benson" | **DA** |
| 10 | 0,810 | rejected (online, text gol) | — | NU |
| 11 | 0,950 | accepted | „benson" | **DA** |
| 12 | 0,918 | offline → scor<0,95 → respins | — | NU |
| 13 | 0,938 | accepted | „benson" | **DA** |
| 14 | 0,957 | rejected (online, text gol) | — | NU |
| 15 | 0,941 | accepted | „benson" | **DA** |

**12/15 (80%) — ținta ≥12/14 atinsă.** Salt mare față de 6/14 (43%) la Pasul 1.

- **Încercarea #4** e prima confirmare reală că pragul offline de 0,95 chiar prinde un caz: scor
  0,963, rețea picată, acceptat doar prin scorul local — fără Pasul 1(1) ar fi fost tăcere.
- Toate cele 12 „accepted" au transcris literal „benson" — varianta penson/venson nu a fost
  necesară azi (nimic nu arată că era folosită), dar rămâne acolo pentru data viitoare.
- **Rămân 3 ratări**: #10 (0,810, cel mai slab scor din tot lotul — consistent cu tendința găsită la
  Pasul 1, scor mic → verificare mai puțin sigură), #12 (0,918, sub pragul offline), și **#14, cel
  mai surprinzător** — scor 0,957 (printre cele mai mari), online, conexiune ok, dar transcript gol.
  Un scor mare nu garantează o transcriere reușită — zgomot/hiccup STT trecător, nu ceva ce scorul
  local poate prezice.

## STOP — conform instrucțiunii. Aștept decizia ta pentru Pasul 2.
