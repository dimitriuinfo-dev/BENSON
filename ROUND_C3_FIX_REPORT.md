# C3-fix — sesiunea STT nu se închidea niciodată

**Scope respectat:** doar `app/index.tsx` și `lib/agents/voiceAgent.ts`. Fără `git`, `prebuild`, `setx`. Nimic comis. `android/` neregenerat (67 executed / 878 up-to-date).

---

## 1. Cauza reală

`c1.log 09:15:47` — sesiunea `js-…747825` pornește, captura se termină `09:15:50 reason=stopped`, dar `STT_SESSION_CLOSED` nu apare. `sttSessionActiveRef` rămâne ridicat → fiecare încercare ulterioară e respinsă (`STT_REJECTED reason=session_active`) la fiecare 2 s, minute în șir. BENSON complet surd.

**De ce nu s-a închis:** garda C3 (din runda C3) verifica `sttSessionActiveRef` **după** `stopWakeScan()` din capul lui `doStartListening()`. Când o captură `local` era deja în zbor și un al doilea `doStartListening()` intra:

1. rulează `stopWakeScan()` (linia ~2617) — care face `sharedCaptureEndSub?.remove()` pe **variabila partajată** (prin design, `voiceAgent.ts` L183–194) — adică demontează listener-ul de sfârșit al capturii `local` **active**;
2. abia apoi lovește garda C3 și e respins.

Rezultat: event-ul nativ `onCaptureEnd` al sesiunii active ajunge în gol → `emitEnd()` nu se declanșează → `endSub` nu rulează → `sttSessionActiveRef` nu se coboară niciodată. Și fiecare respingere ulterioară repetă `stopWakeScan()` (acum no-op), deci nimic nu se auto-repară.

---

## 2. Fixul — apărare în adâncime, 4 straturi

### Strat 1 — garda C3 mutată în capul funcției (`app/index.tsx`)
`doStartListening()`: verificarea `if (C3_SINGLE_SESSION_GATE && sttSessionActiveRef.current) { STT_REJECTED; return; }` e acum **prima** instrucțiune după gărzile `silencedRef` / `listeningRef||loadingRef`, **înainte** de `speakingRef`, de deferralele TTS/mute și de `stopWakeScan()`. Un apel respins nu mai atinge absolut nimic. `rejectedSession` din log se calculează inline (`js-${Date.now()}`), fără a mai atinge `jsSttSessionIdRef`.

### Strat 2 — `closeSttSession(reason)`, un singur punct, pe TOATE căile (`app/index.tsx`)
Helper idempotent nou (înainte de `doStartListening`):
```ts
function closeSttSession(reason: 'result'|'error'|'stopped'|'no_speech'|'timeout'|'background') {
  if (sttSessionWatchdogRef.current) { clearTimeout(...); sttSessionWatchdogRef.current = null; }
  const sid = sttSessionActiveRef.current;
  if (!sid) return;                       // idempotent — al doilea apel pt aceeași sesiune = no-op
  sttSessionActiveRef.current = null;
  if (C3_SESSION_CLEANUP) logAudioDiag('STT_SESSION_CLOSED', `session=${sid} reason=${reason}`);
}
```
Apelat din **fiecare** cale de sfârșit — verificat una câte una:

| reason | Unde | Linie |
|---|---|---|
| `result` | `resultSub` (după `stopRecognition()`), `endSub` (dacă `sessionGotResultRef`) | 1000, 1043 |
| `error` | `errorSub` (kind necunoscut), ramura `!granted`, `catch(e)` din `doStartListening` | 1024, 2757, 2781 |
| `stopped` | `errorSub` (`aborted` / `stopped`), `endSub` (fără rezultat), `doStopListening()`, `enterSilentMode()` | 1020, 1024, 1043, 2790, 2807 |
| `no_speech` | `errorSub` (`error === 'no-speech'`) | 1024 |
| `timeout` | watchdog-ul de sesiune de 30 s, `listenWatchdogRef` (65 s local / 12 s cloud) | 2698, 2774 |
| `background` | AppState `next!=='active'` (ramura non-`local`), AppState `'active'` (plasa de revenire în prim-plan) | 1225, 1254 |

Nicio cale nu mai depinde exclusiv de `endSub`. Dacă `emitEnd` nu se declanșează, `resultSub`/`errorSub`/watchdog-ul închid oricum.

### Strat 3 — watchdog de sesiune `STT_SESSION_MAX_MS = 30000` (`app/index.tsx`)
Armat în `doStartListening` odată cu ridicarea gărzii (`sttSessionWatchdogRef`), dezarmat în `closeSttSession`. La declanșare, dacă sesiunea e tot activă:
```
STT_SESSION_WATCHDOG session=js-… fired=true elapsedMs=30000
STT_SESSION_CLOSED   session=js-… reason=timeout
```
apoi `stopRecognition()` + repornire ascultare **după 600 ms** (peste fereastra de fallback de la Stratul 4, ca un `emitEnd` întârziat al sesiunii moarte să nu închidă sesiunea nouă). Revert: `STT_SESSION_MAX_MS = Number.POSITIVE_INFINITY`.

### Strat 4 — `emitEnd()` garantat în ≤400 ms după orice `stopRecognition()` (`lib/agents/voiceAgent.ts`)
`let endEmitted` (resetat la `false` în `startRecognition`, setat `true` în `emitEnd`) + `endFallbackTimer`. `stopRecognition()` programează:
```ts
endFallbackTimer = setTimeout(() => {
  if (!endEmitted) { logAudioDiag('STT_SESSION', `engine=… event=end_fallback reason=no_native_end`); emitEnd(); }
}, 400);
```
`emitEnd()` real anulează timerul; `startRecognition()` îl anulează la pornirea unei sesiuni noi. Astfel bucla hands-free (`endSub` → repornire) **și** garda se eliberează chiar dacă `onCaptureEnd` nativ nu mai livrează nimic pentru sesiunea aceea. Un `emitEnd` dublu e inofensiv — `closeSttSession` e idempotent, iar repornirea dublă e prinsă de garda C3.

---

## 3. Fișier cu fișier

### `app/index.tsx` — ~85 linii nete (față de starea post-C3)

| Zonă | Ce | Δlinii |
|---|---|---|
| L604–623 — bloc comentariu + `STT_SESSION_MAX_MS` + `sttSessionWatchdogRef` | constante noi + diagnostic cauză | +20 |
| L633–639 — `closeSttSession()` | helper idempotent nou | +9 |
| L644–650 — capul `doStartListening` | garda C3 mutată aici (era la ~L2658) | +7 / −0 |
| L680–704 — `doStartListening` | vechea gardă ștearsă; armare watchdog de sesiune | +18 / −11 |
| L1000 — `resultSub` | `closeSttSession('result')` | +1 |
| L1018–1027 — `errorSub` | rescris pe `closeSttSession` cu mapare `aborted/no-speech/stopped/error` | +6 / −4 |
| L1037–1043 — `endSub` | inline clear → `closeSttSession(result\|stopped)` | +2 / −6 |
| L1218–1225 — AppState bg non-`local` | inline → `closeSttSession('background')` | +1 / −4 |
| L1247–1256 — AppState `'active'` plasă | inline → `closeSttSession('background')` | +1 / −4 |
| L1342 — cleanup `useEffect` | `clearTimeout(sttSessionWatchdogRef)` | +1 |
| L2743–2757 — ramura `!granted` | inline → `closeSttSession('error')` | +1 / −4 |
| L2769–2774 — `listenWatchdogRef` | + `closeSttSession('timeout')` | +1 |
| L2776–2781 — `catch(e)` | inline → `closeSttSession('error')` | +1 / −4 |
| L2784–2790 — `doStopListening` | + `closeSttSession('stopped')` | +1 |
| L2800–2807 — `enterSilentMode` | + `closeSttSession('stopped')` | +1 |

### `lib/agents/voiceAgent.ts` — ~22 linii nete (față de starea post-C3)

| Zonă | Ce | Δlinii |
|---|---|---|
| L151–177 — bloc C3 | + `endEmitted`, `endFallbackTimer` + comentariu; `emitEnd()` devine funcție cu corp (anulează timerul, setează `endEmitted`) | +14 / −1 |
| L300–305 — `startRecognition` | `endEmitted = false` + anulare `endFallbackTimer` la pornire | +2 |
| L366–385 — `stopRecognition` | programează fallback-ul `emitEnd()` la 400 ms dacă `!endEmitted` | +9 |

`C3_SINGLE_SESSION_GATE` din C3 (guard `recognitionInFlight` în `startRecognition`) — **neatins**, rămâne activ.

---

## 4. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** — 0 erori. |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 46s`** · `67 executed / 878 up-to-date` · `android/` neregenerat. |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 884 936 B (~248,8 MiB)** · mtime `2026-09-08 09:26:16`. |
| SHA-256 | `ff125f9a083f5fdae1edaf013bc667a81185d78f611a90985636591cdd6c8aff` |
| Semnătură | `apksigner verify` → **exit 0** · `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`. |
| Instalare | `9c1464eb` (CPH2663) · `adb install -r` → **`Success`** · `lastUpdateTime=2026-09-08 09:26:44` · `firstInstallTime=2026-08-23 10:15:49` neschimbat. |

### Coada `gradlew`
```
BUILD SUCCESSFUL in 46s
945 actionable tasks: 67 executed, 878 up-to-date
```

---

## 5. Comportamente dovedite (CLAUDE.md)

| Comportament | De ce nu regresează |
|---|---|
| Conversație liberă cu răspuns rostit | Fiecare sesiune se închide acum pe ≥1 cale sigură; următoarea rostire pornește curat. Repornirea (endSub `setTimeout`) e neschimbată. |
| Microfonul se auto-repară după o acțiune care lansează altă app (C1+C2) | Garda mutată sus + `closeSttSession` pe calea `background` (bg **și** revenire în prim-plan) + watchdog de 30 s: garda nu mai poate supraviețui unei pauze de fundal. Plasa din AppState `'active'` rulează înaintea repornirilor C1/C2. |
| Apel WhatsApp / navigație Waze | C3-fix nu atinge parsarea/execuția — doar ciclul de viață al sesiunii STT. |
| Microfon închis cât vorbește BENSON | `beginTtsBlock`/`endTtsBlock` neatinse. |

Comportament nou vizibil: o sesiune de ascultare inactivă >30 s (tăcere prelungită în conv mode) e reciclată — `STT_SESSION_WATCHDOG fired=true` + `STT_SESSION_CLOSED reason=timeout` + sesiune nouă după 600 ms. Nu e surzenie, e o sesiune proaspătă. Revert: `STT_SESSION_MAX_MS = Number.POSITIVE_INFINITY`.

---

## 6. Ce am vrut să schimb într-un fișier interzis și nu am schimbat

**Nimic.** `modules/benson-audio-capture/**` (unde `sharedCaptureEndSub` e demontat de `stopWakeScan`, cauza rădăcină nativă) e interzis — dar stratul JS (garda mutată sus + `closeSttSession` universal + watchdog + `emitEnd` garantat) face defectul imposibil fără a-l atinge.

---

## 7. Acceptare pe dispozitiv — de rulat de tine

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" logcat -c
"$ADB" logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S > c1.log
#  → cinci întrebări simple de conversație la rând, aceeași sesiune. Ctrl+C. Apoi:
findstr /C:"STT_SESSION_CLOSED" /C:"CAPTURE_ENDED" /C:"STT_REJECTED" /C:"STT_REQUESTED" c1.log
```

Criteriu:
- **după fiecare `CAPTURE_ENDED` / sfârșit de sesiune → o linie `STT_SESSION_CLOSED session=… reason=…`** (result / stopped / no_speech / timeout — oricare, dar prezentă);
- **zero `STT_REJECTED` în rafale lungi** — cel mult câte unul izolat pe rostire (race real), niciodată la fiecare 2 s minute în șir;
- un `STT_REQUESTED` pe rostire, BENSON aude și răspunde la toate cinci.

Dacă vezi vreun sfârșit de captură **fără** `STT_SESSION_CLOSED` în ≤1 s după el, trimite-mi fereastra de log din jur — defectul persistă și revin cu `STT_SESSION_MAX_MS` mai mic + diagnostic pe calea lipsă.
