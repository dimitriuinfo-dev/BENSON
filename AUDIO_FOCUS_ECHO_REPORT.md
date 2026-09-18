# Raport — audio focus + anulare de ecou (+ audit „vede toate aplicațiile")

Data: 2026-08-28.

---

## PARTEA A — Runda audio (implementată)

### 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 56s** (exit 0) |

**APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
260 854 488 bytes ≈ 248.8 MiB · construit 22:46:57 · cert `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` · schema v2 · RSA 2048

**Fișiere modificate (2):**
- `modules/benson-audio-capture/android/.../BensonAudioCaptureModule.kt` — audio focus + linia `EFFECTS` extinsă (task 1, 2)
- `app/index.tsx` — `POST_ACTION_MUTE_MS` + logica de pauză (task 4, DOAR asta)

### 1. Audio focus — implementat

- La `recorder.startRecording()`: `requestAudioFocus(context)` →
  `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
  - API ≥ 26: `AudioFocusRequest.Builder` cu `AudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)`,
    `setWillPauseWhenDucked(false)` → celelalte aplicații (Waze) își **coboară** volumul, nu se opresc.
  - API 24–25: `requestAudioFocus(null, STREAM_MUSIC, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` (legacy).
- În `finish()` (toate căile de ieșire — vad_silence / max_duration / stopped / error / no_speech):
  `abandonAudioFocus()` → celelalte aplicații revin la volum plin imediat.
- Log: **`AUDIO_FOCUS state=requested`** → **`granted`** sau **`denied`** → **`released`**.

### 2. Anulare de ecou — starea reală (nimic schimbat, doar linia de log extinsă)

| | |
|---|---|
| Sursă audio | `MediaRecorder.AudioSource.VOICE_RECOGNITION` |
| sessionId | `recorder.audioSessionId` — AEC și NS create pe **exact această** sesiune → țintă corectă |
| `AcousticEchoCanceler.isAvailable()` | verificat (linia 167); pe acest telefon → **true** (log live `aec=on`) |
| AEC enabled | **true** |
| NS enabled | **false** — poartă dublă: flag `noise_suppressor_enabled` (**default false**) + `isAvailable()`. Oprit prin flag, nu din indisponibilitate. Deliberat (test „mangling"). |

**Concluzie:** AEC este deja atașat corect, pe sesiunea corectă, activ. Nu e un bug de cablare.
Ecoul rezidual (motivul pentru `looksLikeSelfEcho()` din JS) vine din AEC software pe calea
`VOICE_RECOGNITION` care nu anulează complet propriul TTS / vocea altei aplicații pe acest HAL.
**Audio focus-ul (task 1) ar trebui să acopere cazul Waze** prin ducking. Dacă după test ecoul
persistă, se încearcă separat, în spatele unei constante de revert (neatins acum).

Linia nouă: **`EFFECTS source=voice_recognition sessionId=<id> aec=on|off ns=on|off`**.

### 3. Mod de comunicare — NEATINS (confirmat de tine)

Modulul **nu apelează `AudioManager.setMode()`** — nicio referință. Modul rămâne `MODE_NORMAL`.
Referința hardware AEC (pentru far-end / playback-ul altei aplicații) se activează în general doar
în `MODE_IN_COMMUNICATION` + `VOICE_COMMUNICATION` — schimbă rutarea/gain-ul global, deci **nu se
atinge**. Audio focus-ul rezolvă cazul Waze fără acest risc.

### 4. Plasă temporară post-acțiune — implementat

- `app/index.tsx`: `const POST_ACTION_MUTE_MS = 3000;` + `postActionMuteUntilRef`.
- Setat după execuția reală a unei acțiuni în altă aplicație:
  - după `confirmActiveMission(...)` (calea guvernată Waze/WhatsApp),
  - după `resumePendingTask(...)` (Mission Orchestrator).
- `doStartListening()`: dacă `postActionMuteUntilRef - Date.now() > 0` → `logAudioDiag('POST_ACTION_MUTE', 'deferMs=…')`, `setTimeout(doStartListening, rămas)`, `return`. Se reintră singur după fereastră.
- O constantă + un ref + o gardă — ușor de scos când ducking-ul se dovedește suficient.

---

## PARTEA B — Audit „BENSON vede toate aplicațiile" (fără modificări, cerut de tine)

### 1. Enumerarea nativă întoarce toate aplicațiile?

**DA.** `benson-app-registry` → `BensonAppRegistryModule.getInstalledApps()`:
`queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER, 0)` → `distinctBy(packageName)` →
exclude BENSON → `{packageName, appName, icon(base64 PNG)}` → sortat după nume.
Perechea `MAIN`/`LAUNCHER` **este scutită** de filtrarea Android 11+ — **nu e nevoie de `<queries>`**.

**Deci pasul 1 din cererea ta e INFIRMAT:** cauza NU e vizibilitatea pachetelor. Enumerarea completă
există și funcționează. `launchApp` / `isPackageInstalled` native folosesc aceeași enumerare exemptă.

**Lipsește:** niciun log `APP_INDEX count=…` nicăieri — nu se vede câte aplicații întoarce.

### 2. Lista ajunge în JS? Cine o consumă?

`getInstalledApps()` e apelat din:
- `components/onboarding/AppPermissionsModal.tsx` — ecranul de permisiuni per-aplicație.
- `src/executors/appLauncherExecutor.ts` — **DOAR** pentru: rezolvare radio (`resolveRadioTarget`),
  hint muzică (`findInstalledAppByNameHint`), și `CLOSE_APP` (`resolveTargetPackage`).

**NU e folosit pentru intenția generică `OPEN_APP`.** Cele două căi de „deschide X":
- `src/executors/appLauncherExecutor.ts` `OPEN_APP` (linia 361): `findAppRegistryEntry(appName)` →
  potrivire **doar** cu un registru curat, hardcodat (`src/core/action-engine/appRegistry`). Miss →
  **`"Nu găsesc aplicația ${appName} pe telefon."`** — fără să întrebe măcar dispozitivul.
- `lib/agents/appLauncherAgent.ts` `launchApp()` (folosit de orchestrator + Claude tools):
  `findAppByName()` → `APP_REGISTRY` curat (`lib/appRegistry`); apoi `findAllowedDynamicApp()` →
  **doar** submulțimea deja aprobată de user (`getAllowedApps()`), niciodată lista completă
  `getInstalledApps()`. Miss → deschide Play Store („I don't have X set up yet").

### 3. Potrivire fuzzy pe etichetă ȘI packageName, insensibilă la diacritice?

**NU.** Toți matcherii sunt `String.toLowerCase().includes()` simplu:
- `appLauncherAgent.findAppByName`: substring bidirecțional pe **numele din registrul curat** — fără packageName, fără pliere de diacritice, fără fuzzy la nivel de token („you tube" ≠ „youtube").
- `appLauncherExecutor.resolveTargetPackage`: `=== q || .includes(q) || q.includes(appName)` — pe `appName`, **fără packageName**, fără diacritice.
- `findInstalledAppByNameHint` / radio / muzică: `.includes(hint)` pe `appName`.
- Nicăieri: `normalize('NFD')` + strip diacritice, potrivire pe `packageName`, sau distanță de editare.

### 4. De unde vine exact „nu găsesc aplicația"?

`src/executors/appLauncherExecutor.ts`:
- linia 141 — `launchAllowlisted`, când `isPackageInstalled(packageName)` e false;
- linia 362 — `OPEN_APP`, când numele nu e în registrul **curat** `findAppRegistryEntry`;
- linia 266 — `CLOSE_APP`.
Plus `lib/agents/appLauncherAgent.ts` linia 274 — varianta EN „I don't have X set up yet" (Play Store).

**Cauza reală:** ambele căi `OPEN_APP` rezolvă numele rostit contra unui **registru curat mic**, nu
contra `getInstalledApps()`. Orice aplicație instalată care nu e în lista curată → „nu găsesc".
Plus potrivire substring simplă, fără fuzzy/diacritice/packageName.

### Ce ar cere fix-ul (când dai lock-ul)

- **NU** un plugin `<queries>` nou (pasul 2) — inutil, `MAIN`/`LAUNCHER` funcționează deja.
- Conectează `OPEN_APP` (în `appLauncherExecutor.ts` **și** `appLauncherAgent.ts`) la
  `getInstalledApps()` ca fallback când registrul curat ratează.
- Matching insensibil la diacritice + fuzzy pe token, peste **`appName` ȘI `packageName`**.
- „Cel mai apropiat rezultat + întrebare" în loc de „nu găsesc".
- Log `APP_INDEX count=…` la pornire.
- Opțional: index în memorie + `BroadcastReceiver` pentru instalare/dezinstalare (acum `getInstalledApps()`
  re-interoghează și **re-encodează base64 iconițele tuturor aplicațiilor la fiecare apel** — greu).
- Fișiere: `src/executors/appLauncherExecutor.ts`, `lib/agents/appLauncherAgent.ts`, eventual un nou
  `lib/appIndex.ts`, opțional `benson-app-registry` nativ (log count / listă fără iconițe / eveniment pkg).
  **`plugins/**` nu e necesar.**
