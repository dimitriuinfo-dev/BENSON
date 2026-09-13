# RUNDA E2 — raport de execuție

**Fără ieșiri din scope lock.** „componenta bulei/overlay" din permisiuni am interpretat-o ca modulul `modules/benson-overlay/**` (serviciul care desenează bula) — nu am atins `modules/benson-accessibility/**` (interzis), `android/**`, `plugins/**`, `src/core/orchestrator/**`, `missionExecutor.ts`, `missionValidator.ts`, `whatsappTool.ts`. Fără `git`, `expo prebuild`, `setx`.

**Un singur tip de schimbare:** ascultare instant la deschidere + feedback vizibil lângă bulă + o singură constantă de timp mărită. Fiecare are constantă de revert.

---

## 1. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 1m 4s** (102 executed / 843 up-to-date; Kotlin recompilat pentru `benson-overlay` + `benson-audio-capture`) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 875 708 B (~248,8 MiB)** |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · schema v2 verificată · SHA-256 `fbbc618d…5184da` |
| Instalare + `lastUpdateTime` | dispozitiv `9c1464eb` (CPH2663) · `adb install -r -d` → **Success** (13s) · `lastUpdateTime=2026-09-07 11:29:16` (era 09:32:48 = E1) · `firstInstallTime` neschimbat → date păstrate |

---

## 2. Modificări, fișier cu fișier

### `modules/benson-overlay/android/.../BensonBubbleService.kt` — E2-2 (~+105 linii)
- Importuri: `Typeface`, `TypedValue`, `LinearLayout`, `TextView`.
- Câmpuri noi: `statusView: LinearLayout?`, `statusStateText/statusTranscriptText: TextView?`.
- `onStartCommand`: acțiune nouă `ACTION_UPDATE_STATUS` cu extras `state` / `transcript` / `visible` → `updateStatus(...)`.
- `updateStatus(state, transcript, visible)`: creează (o dată) o bandă `LinearLayout` verticală — un rând de stare (gold, bold, 12sp) + un rând cu transcrierea (alb, 14sp, `maxLines=2`, `ellipsize=END`, `maxWidth = 78% din lățimea ecranului`), fundal pill închis cu bord gold. Poziție: `Gravity.BOTTOM | CENTER_HORIZONTAL`, `y = (100+56+10)dp` = **exact deasupra bulei**. `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS` — nu prinde atingeri, nu fură focus. La reapel doar actualizează textele (fereastra e `WRAP_CONTENT`, se redimensionează). `visible=false` sau ambele texte goale → `removeStatus()`. `wm.addView` e în `try/catch` (fără permisiune de overlay → no-op tăcut, ca restul modulului).
- `removeStatus()` își ia propriul handle `WindowManager` (în `onDestroy`, `removeBubble()` rulează înainte și anulează `windowManager`).
- `onDestroy`: `removeStatus()` adăugat.
- companion: `ACTION_UPDATE_STATUS`, `EXTRA_STATE`, `EXTRA_TRANSCRIPT`, `EXTRA_VISIBLE`.
- **Banda nu acoperă stânga:** e `WRAP_CONTENT` centrată deasupra unei bule bottom-center, cu lățime plafonată la 78%. Stânga și dreapta ecranului de dedesubt rămân vizibile.

### `modules/benson-overlay/android/.../BensonOverlayModule.kt` — E2-2 (~+13 linii)
- `Function("updateBubbleStatus") { state: String, transcript: String, visible: Boolean -> ... }` — `startService` cu `ACTION_UPDATE_STATUS` + extras.

### `modules/benson-overlay/index.js` + `index.d.ts` — E2-2 (~+7 / +5 linii)
- `export function updateBubbleStatus(state, transcript, visible)` + tipul.

### `modules/benson-audio-capture/android/.../BensonAudioCaptureModule.kt` — E2-3 (net 0, ~5 linii de comentariu)
- `SILENCE_TIMEOUT_MS` **800L → 1600L**, comentariul cu valoarea veche alături: `// was 800L (E2-3 2026-09-07) / 2500L (pre-E1) / 1600L original`.
- Doar această constantă a fost atinsă (conform scope „doar SILENCE_TIMEOUT_MS").

### `lib/agents/voiceAgent.ts` — E2-3 oglindă (~+6 linii)
- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` 800 → **1600**, `..._POSSIBLY_..._MILLIS` 600 → **1200**. Doar pentru motoarele `cloud`/`ondevice` (SpeechRecognizer); motorul implicit `local` folosește constanta nativă de mai sus. Consistență cu E2-3.

### `app/index.tsx` — E2-1 + E2-2 wiring (~+73 linii)
- Import `updateBubbleStatus` din `benson-overlay`.
- Constante: `E2_LISTEN_ON_OPEN = true`, `E2_BUBBLE_BAND = true`; refs `lastUserTranscriptRef`, `bandVisibleRef`; hartă `E2_STATE_LABEL` (`LISTENING→ascult`, `THINKING/CONFIRMING→am înțeles`, `EXECUTING→execut`, `DONE/ERROR→gata`, restul → fără etichetă = banda dispare); helper `pushBubbleBand(state)`.
- `pushBubbleBand`: arată banda **doar** cât BENSON NU e aplicația din prim-plan (`!isForegroundRef.current`), nu e silenced, serviciul e activ. Altfel o ascunde. Nu pokă nativul dacă n-are nimic de ascuns (`bandVisibleRef`).
- **E2-1:**
  - `bubbleTapSub` (atingerea bulei): `noteUserAction()` + `bringToForeground()` + `logAudioDiag('LISTEN_STARTED', 'source=user_open')` + `doStartListening()` **imediat**, necondiționat de conv mode. Fără chime, fără rostire.
  - `AppState → 'active'`, ramura conv mode: în loc de `setTimeout(doStartListening, 500)` → `LISTEN_STARTED source=user_open` + `doStartListening()` **instant** (păstrează `setTimeout`-ul vechi doar pe calea non-conv, neschimbat).
  - `phase === 'chat'` useEffect (prima deschidere): după `startBackgroundService()`, dacă conv mode + ne-silenced → `LISTEN_STARTED source=user_open` + `doStartListening()` instant. (Înainte, ascultarea pornea abia prin self-heal-ul de 2s din E1-2, fiindcă `startLocalWakeLoop` no-op-ează în conv mode.)
- **E2-2 wiring:**
  - `setBensonState(next)` → `pushBubbleBand(next)` la final. „gata" rămâne `STATE_DONE_CLEAR_MS` (4s), apoi auto-IDLE ascunde banda — „rămâne vizibil cât se execută, nu dispare imediat".
  - `handleIncomingText` (sus): `lastUserTranscriptRef.current = msg` (textul final asamblat) + `pushBubbleBand`.
  - `resultSub` (transcript final acceptat): `lastUserTranscriptRef.current = transcript` + `pushBubbleBand` — apare imediat ce s-a auzit, înainte de dispatch.
  - `AppState → fundal`: `pushBubbleBand(bensonStateRef.current)` — banda apare cu starea/transcrierea curentă când BENSON trece în spatele altei aplicații.
  - `AppState → 'active'`: `pushBubbleBand('IDLE')` — banda coboară (transcrierea rămâne în chat).
  - `enterSilentMode`: `bandVisibleRef.current = false` (banda a căzut cu serviciul bulei).

---

## 3. Cum se leagă de E1-0

- **E2-1** pornește **ascultarea** (mic), nu o rostire și nu o aducere în prim-plan din cod. Sursa e strict atingerea directă / deschiderea de către utilizator. `noteUserAction()` marchează momentul (deci un eventual reply ulterior are voie să vorbească). Nu se rostește nimic, nu se cântă chime. Conform notei explicite din E2-1.
- **E2-2** e feedback **scris**, pasiv, lângă bulă — nici rostire, nici UI adus în față. Se actualizează doar ca urmare a unei rostiri a utilizatorului (transcriere) sau a unei tranziții de stare a unei comenzi pe care el a dat-o.
- `SPEAK_SUPPRESSED` din E1-0 rămâne neatins și activ.

---

## 4. Anti-regresie — comportamente dovedite

| Comportament dovedit | Afectat? | De ce nu se strică |
|---|---|---|
| Navigație Waze cu governance | nu | fluxul de misiune neatins; doar `setBensonState` mai cheamă `pushBubbleBand` (fire-and-forget, `try/catch`) |
| Deschidere aplicație după nume | nu | `appLauncherExecutor` / orchestrator neatinse |
| Propunere pentru cerere generică | nu | neatins |
| Conversație liberă cu răspuns rostit | nu | `speak`/`speakText` neatinse |
| Apel WhatsApp cap-coadă | **posibil pozitiv** | E2-3 (1600ms) reduce riscul tăierii „Sună-o pe Hannah pe WhatsApp" la pauza scurtă; `whatsappTool`/accesibilitate neatinse |
| Microfon închis cât vorbește BENSON | nu | `beginTtsBlock`/`micResumeAtRef` neatinse; `doStartListening` respectă în continuare `speakingRef`/tail |
| Index de aplicații 274 | nu | neatins |
| Auto-reparare mic C1+C2, revenire fără atingerea medalionului | **întărit** | self-heal-ul rămâne; E2-1 doar îl precede cu un start instant la deschidere. `LISTEN_HEALED` neschimbat |

**Riscuri reale (de urmărit pe dispozitiv):**
1. **E2-3 (1600ms)** readuce ~0,8s de latență la finalul fiecărei comenzi față de E1 (era 800ms). E compromisul cerut. Revert: `SILENCE_TIMEOUT_MS = 800L`.
2. **Banda** e un al doilea overlay `WindowManager`. Dacă OxygenOS raportează scurgeri de ferestre sau banda rămâne pe ecran după revenire → revert: `E2_BUBBLE_BAND = false`. `removeStatus()` are handle propriu de `WindowManager` și `onDestroy` o curăță.
3. **E2-1** pornește o sesiune STT la fiecare `AppState → active` în conv mode (inclusiv la deblocarea ecranului cu BENSON în față). E intenționat („mereu ascultă"), dar dacă e prea agresiv la baterie → revert: `E2_LISTEN_ON_OPEN = false` (revine grația de 500ms + self-heal-ul de 2s).
4. Banda se arată doar cât BENSON e în fundal. Dacă comanda e dată în interfața BENSON și acțiunea NU lansează altă aplicație (ex. „cât e ceasul"), banda nu apare deloc — corect, transcrierea e în chat.

---

## 5. Build + instalare

- `npx tsc --noEmit` → **0 erori**.
- `gradlew assembleRelease` (cu `ANDROID_HOME` setat inline pentru comandă — fără `setx`) → **BUILD SUCCESSFUL in 1m 4s**. `:app:compileReleaseKotlin` re-executat → codul nativ nou (`BensonBubbleService.updateStatus`, `updateBubbleStatus`, `SILENCE_TIMEOUT_MS`) e în APK.
- APK: `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk` · **260 875 708 B (~248,8 MiB)**.
- `apksigner verify --print-certs`: DN `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · APK Signature Scheme v2 = verified · SHA-256 `fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da` (identic cu E1 — aceeași cheie de release).
- `adb install -r -d` pe `9c1464eb` (CPH2663 / OnePlus Nord 4) → **Success** (13s).
- `dumpsys package com.benson.butler`: `lastUpdateTime=2026-09-07 11:29:16` (era `2026-09-07 09:32:48` = build-ul E1), `firstInstallTime` neschimbat `2026-08-23 10:15:49` → build-ul E2 e pe telefon, cu datele păstrate. versionCode=1, versionName=1.0.0.

---

## 6. Constante de revert

| Constantă | Fișier | Revert |
|---|---|---|
| `E2_LISTEN_ON_OPEN` | `app/index.tsx` | `false` → deschiderea nu mai pornește instant ascultarea (revine grația 500ms + self-heal 2s) |
| `E2_BUBBLE_BAND` | `app/index.tsx` | `false` → banda scrisă nu mai apare niciodată |
| `SILENCE_TIMEOUT_MS` | `BensonAudioCaptureModule.kt` | `800L` (E1-3) sau `2500L` (pre-E1) |
| `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` | `lib/agents/voiceAgent.ts` | `800` / `600` |

Pentru dezactivarea totală a benzii fără rebuild JS: se poate seta `E2_BUBBLE_BAND = false`; codul nativ rămâne inert (nu primește niciodată `ACTION_UPDATE_STATUS`).

---

## 7. Acceptare pe dispozitiv — de rulat de tine

1. **Deschizi BENSON → te ascultă imediat, fără atingere suplimentară.** Log: `LISTEN_STARTED source=user_open` urmat imediat de `STT_REQUESTED trigger=conversation_mode`, fără `LISTEN_HEALED` înainte.
2. **Spui o comandă → textul transcris apare lângă bulă și rămâne cât se execută.** Peste altă aplicație: banda arată „am înțeles" + textul, apoi „execut", apoi „gata" (4s), apoi dispare. Log: `TRANSCRIPT_ACCEPTED` → `STATE to=EXECUTING` → banda actualizată.
3. **„Sună-o pe Hannah pe WhatsApp" rostit firesc → nu te mai taie la mijloc.** Log: `CAPTURE_ENDED reason=vad_silence` cu `durationSec` acoperind toată frază, nu `reason=vad_silence` la ~1s.
4. Verifică și că banda **nu acoperă** conținutul din stânga al aplicației de dedesubt și că **nu prinde atingeri** (poți apăsa prin ea).
