# RUNDA C3 — O rostire = o singură sesiune STT

**Scope lock respectat.** Atinse doar `app/index.tsx` și `lib/agents/voiceAgent.ts`. Niciun fișier interzis. Fără `git`, `expo prebuild`, `setx`. Nimic comis. `android/` NU a cerut regenerare (build incremental: 67 executed / 878 up-to-date; Metro și-a reconstruit doar cache-ul gol → 1m 37s).

---

## 1. Cauza dublului `STT_REQUESTED` (numită)

**Race de tip check-then-act pe un flag de gardă setat asincron.**

`doStartListening()` are o gardă de reintrare în capul funcției:

```ts
if (listeningRef.current || loadingRef.current) return;
```

Dar `listeningRef.current = true` se setează abia la **~L2691**, adică **după**:

```ts
const [, currentlyGranted] = await Promise.all([ pauseHotword().catch(()=>{}), checkMicPermission() ]);
const granted = currentlyGranted || await requestMicPermission();
```

`checkMicPermission()` face un dus-întors pe bridge-ul nativ (`PermissionsAndroid`) — ~1–5 ms de suspendare reală. Orice **al doilea** apel `doStartListening()` care aterizează în fereastra acelui `await` trece de garda de sus (`listeningRef` încă `false`), ajunge la propriul `startRecognition()` / `RECORDER_CREATE`, și acum două recordere native trăiesc pe același microfon. Exact `session=js-…273` + `session=js-…275` la 2 ms din `c1.log 08:46:38`.

**De ce pleacă două apeluri pe aceeași rostire** — NU e „un handler apelat de două ori", sunt **două mecanisme independente de repornire** care se declanșează pentru că primul apel n-a apucat încă să ridice `listeningRef`:

| Sursă | Unde | Când |
|---|---|---|
| callback-ul de final `speakText(reply, onFinished)` | `() => { if (convModeRef.current) doStartListening(); }` — ~L2011, 2340, 2801, 2941, 3199, 3226, … | la fiecare tură de conversație, când BENSON termină de vorbit |
| self-heal-ul C2 | `setInterval(…, LISTEN_SELF_HEAL_MS=2000)` — ~L1272, `LISTEN_HEALED reason=conv_idle` | orice tick de 2 s la care `!listeningRef.current` în conv mode |
| repornirea din `endSub` | `setTimeout(() => doStartListening(), 400)` / `700` — ~L1035 / L1076 | după o sesiune care n-a prins nimic / aștepta un răspuns |
| C1 `resumeListeningAfterUnblock()` | handler-ul AppState `'active'` + watchdog-ul TTS | la revenirea în prim-plan / la eliberarea forțată a TTS |

Când callback-ul TTS-done și un tick de self-heal (sau `setTimeout(…,400)` din `endSub`) cad în același tick de event-loop, cele două apeluri `doStartListening()` sunt la microsecunde distanță → ambele trec garda → două `STT_REQUESTED trigger=conversation_mode` → două `RECORDER_CREATE`.

**Fixul:** un flag de gardă **sincron** (`sttSessionActiveRef`), ridicat înainte de orice `await`, verificat la intrare. Al doilea apel e respins cu `STT_REJECTED` înainte să atingă `jsSttSessionIdRef` sau `startRecognition`.

---

## 2. Ce s-a schimbat — fișier cu fișier

### `app/index.tsx` — ~74 linii adăugate, 0 șterse

| # | Zonă (linii aprox. după edit) | Linii | Task | Ce face |
|---|---|---|---|---|
| 1 | L583–602 — bloc constante + ref | +24 | — | `C3_SINGLE_SESSION_GATE`, `C3_SESSION_CLEANUP` (ambele `true`), `sttSessionActiveRef = useRef<string\|null>(null)` + comentariul de cauză. |
| 2 | L2655–2665 — `doStartListening()`, imediat după `const sessionId` | +13 | **T1** | `if (C3_SINGLE_SESSION_GATE && sttSessionActiveRef.current) { logAudioDiag('STT_REJECTED', 'reason=session_active activeSession=… rejectedSession=…'); return; }` — **înainte** de `jsSttSessionIdRef.current = sessionId` (deci un apel respins nu mai clobберează id-ul sesiunii active). Apoi `if (C3_SINGLE_SESSION_GATE \|\| C3_SESSION_CLEANUP) sttSessionActiveRef.current = sessionId;` — sincron, înaintea oricărui `await`. |
| 3 | L2716–2719 — ramura `if (!granted)` | +4 | **T3** | Microfon refuzat → `STT_SESSION_CLOSED reason=error` + `sttSessionActiveRef.current = null`. Nicio sesiune fantomă peste un mic negarantat. |
| 4 | L2742–2745 — `catch (e)` din `doStartListening` | +4 | **T3** | `startRecognition` a aruncat → `STT_SESSION_CLOSED reason=error` + `null`, ca retry-ul de 2 s (`if (convModeRef.current) setTimeout(…)`) să poată porni. |
| 5 | L1003–1008 — `errorSub` (`addErrorListener`) | +6 | **T3** | Eroare reală (non-`aborted`) → `STT_SESSION_CLOSED reason=error` + `null`. Acoperă o eroare fără `end`. |
| 6 | L1020–1027 — `endSub` (`addEndListener`) | +8 | **T3** | Orice sfârșit de sesiune → `STT_SESSION_CLOSED reason=${sessionGotResultRef.current ? 'result' : 'timeout'}` + `null`. Următoarea rostire pornește curat. |
| 7 | L1206–1215 — AppState `next !== 'active'`, ramura non-`local` | +5 | **T3** | Când recognizer-ul e demontat la trecerea în fundal (motor cloud/ondevice) → `STT_SESSION_CLOSED reason=background` + `null`. |
| 8 | L1234–1243 — AppState `'active'`, după force-unblock-ul C1 | +10 | **T3** | Plasă defensivă: o gardă rămasă setată peste o pauză de fundal (motor `local` — `endSub` nu se declanșează cât timerele JS sunt suspendate) ar bloca **toate** repornirile de sub ea (C1 `resumeListeningAfterUnblock`, self-heal, timerele din `endSub`) → BENSON revenea surd. Revenirea în prim-plan = semnal fără echivoc „sesiunile s-au terminat": `STT_SESSION_CLOSED reason=background` + `null` + `stopRecognition()`. |

Regula de ciclu de viață a ref-ului: **setat** dacă `(C3_SINGLE_SESSION_GATE || C3_SESSION_CLEANUP)`; **coborât necondiționat** pe fiecare cale de sfârșit (nulificarea unui ref deja `null` e inofensivă) — deci `C3_SINGLE_SESSION_GATE=true, C3_SESSION_CLEANUP=false` nu poate lăsa o sesiune fantomă. Log-ul `STT_SESSION_CLOSED` e gardat de `C3_SESSION_CLEANUP`; `STT_REJECTED` + respingerea de `C3_SINGLE_SESSION_GATE`.

### `lib/agents/voiceAgent.ts` — ~17 linii adăugate, 1 modificată

Apărare în adâncime pentru cele 15+ căi de intrare în modul și pentru că `runLocalCapture()` nu are nicio gardă de reintrare proprie — un `startRecognition()` dublu direct ar chema `startCapture()` nativ de două ori → două AudioRecord → simptomul `peakRms=32`.

| # | Zonă | Linii | Ce face |
|---|---|---|---|
| 1 | L153–164 — după `emitVolume` | +10 / ~1 mod | `const C3_SINGLE_SESSION_GATE = true;` · `let recognitionInFlight = false;` · `emitEnd()` devine `{ recognitionInFlight = false; endListeners.forEach(…); }` — un singur punct de eliberare care acoperă **și** `local` (via `runLocalCapture` → `emitEnd()`) **și** `cloud/ondevice` (via listener-ul nativ `'end'`). |
| 2 | L282–287 — capul lui `startRecognition()` | +6 | `if (C3_SINGLE_SESSION_GATE && recognitionInFlight) { logAudioDiag('STT_SESSION', 'engine=… event=start_rejected reason=already_in_flight'); return; }` apoi `recognitionInFlight = true;` — înaintea ramurii `engine === 'local'`. |
| 3 | L351 — capul lui `stopRecognition()` | +1 | `recognitionInFlight = false;` eager (event-ul `end` async se declanșează oricum după). |

Toate căile de sfârșit ale capturii `local` trec prin `emitEnd()` (succes, `no-speech`, `local-transcribe-error`, `audio-capture` din `startCapture().catch`) → flag-ul nu se blochează. Pentru `cloud/ondevice`, watchdog-ul de 12 s din `app/index.tsx` cheamă `stopRecognition()` → eliberare eager.

**`C3_SINGLE_SESSION_GATE` există în AMBELE fișiere** (geamăn, documentat în cod). Pentru revert complet: pune-l pe `false` în ambele, plus `C3_SESSION_CLEANUP=false` în `app/index.tsx`.

---

## 3. Cum acoperă cele trei task-uri

- **TASK 1 (poartă de sesiune unică):** `sttSessionActiveRef` sincron în `doStartListening` + `recognitionInFlight` sincron în `startRecognition`. Al doilea apel → `STT_REJECTED reason=session_active activeSession=… rejectedSession=…` (JS) / `STT_SESSION event=start_rejected reason=already_in_flight` (voiceAgent), fără a porni în paralel.
- **TASK 2 (un recorder, nu două):** apelul respins la TASK 1 se întoarce **înainte** de `logAudioDiag('RECORDER_CREATE', …)` (L2689) și înainte de `startRecognition`. Nicio cale nu ajunge la un al doilea `RECORDER_CREATE` cât primul e viu.
- **TASK 3 (curățare la închidere):** `STT_SESSION_CLOSED session=… reason=result|error|timeout|background` pe toate căile de sfârșit (rezultat, eroare, timeout/endpointer, fundal, mic refuzat, excepție la start) + `sttSessionActiveRef = null` + plasă defensivă la revenirea în prim-plan. Fără sesiune fantomă care să blocheze următoarea rostire.

---

## 4. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** — 0 erori (fără nicio linie de output). |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 1m 37s`** · `945 actionable tasks: 67 executed, 878 up-to-date` (doar JS; `android/` neregenerat). |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 884 336 B (~248,8 MiB)** · mtime `2026-09-08 09:09:14`. |
| SHA-256 (certutil) | `a9ec9334f04f6a00d159001fea4fea3e80640be1aa5386f4809cc3fcf8f01a14` |
| Semnătură | `apksigner verify` → **exit 0** · `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`. |
| Instalare | `9c1464eb` (CPH2663 / OnePlus Nord 4) · `adb install -r` → **`Success`** (Streamed Install) · `versionName=1.0.0` · `lastUpdateTime=2026-09-08 09:09:45` · `firstInstallTime=2026-08-23 10:15:49` neschimbat → date păstrate. |

### Coada `gradlew`
```
BUILD SUCCESSFUL in 1m 37s
945 actionable tasks: 67 executed, 878 up-to-date
```

---

## 5. Comportamente dovedite (CLAUDE.md) — impact C3

| Comportament | Risc C3 | De ce nu regresează |
|---|---|---|
| Conversație liberă cu răspuns rostit | Poarta ar putea respinge o repornire legitimă | Poarta se ridică doar la un apel **committed** (după toate gărzile de deferral: `speakingRef`, coada TTS, mute post-acțiune) și se coboară pe **fiecare** cale de sfârșit + la revenirea în prim-plan. Un apel care iese devreme (ex. `speakingRef`) nu o ridică → apelul valid următor trece. Fără deadlock. |
| Apel WhatsApp cap-coadă / navigație Waze | STT-ul alimentează parserul | C3 nu atinge parsarea, execuția sau rețetele — doar concurența pe captură. Un STT curat (o sesiune, `peakRms` normal) **îmbunătățește** intrarea, nu o degradează. |
| Microfon închis cât vorbește BENSON (fără ecou) | `beginTtsBlock`/`endTtsBlock` neatinse | Neatinse. C3 nu schimbă când pornește/se oprește captura față de TTS. |
| Microfonul se auto-repară după o acțiune care lansează altă aplicație (C1+C2) | Plasa defensivă din AppState `'active'` | Adăugată **înaintea** repornirilor C1/C2, tocmai ca să nu le blocheze: dacă garda a supraviețuit unei pauze de fundal, e coborâtă aici → `resumeListeningAfterUnblock()` și self-heal-ul pornesc normal. Testat structural: nicio cale de repornire nu rămâne în urma unei gărzi setate. |
| Index de aplicații 274 / deschidere după nume / propunere generică | Fără legătură | Niciun cod pe aceste căi atins. |

Singurul comportament nou: un al doilea `doStartListening()`/`startRecognition()` concurent e respins în loc să pornească. Revert: `C3_SINGLE_SESSION_GATE=false` (ambele fișiere) + `C3_SESSION_CLEANUP=false`.

---

## 6. Ce am vrut să schimb într-un fișier interzis și nu am schimbat

**Nimic.** Fixul e complet în cele două fișiere permise. `modules/benson-audio-capture/**` (unde trăiește `startCapture`/AudioRecord nativ) **nu** a fost atins — nu era nevoie: dacă niciun al doilea `startRecognition` nu ajunge la `startCapture`, nu există al doilea AudioRecord. O gardă de reintrare și în stratul nativ Kotlin ar fi apărare în plus, dar `modules/**` e interzis și stratul JS o face structural imposibilă oricum.

---

## 7. Acceptare pe dispozitiv — de rulat de tine

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" logcat -c
"$ADB" logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S > c1.log
#  → cinci întrebări simple de conversație la rând, aceeași sesiune
#     („Cât e ceasul", „Cum te cheamă", orice). Ctrl+C. Apoi:
findstr /C:"STT_REQUESTED" /C:"STT_REJECTED" /C:"peakRms" c1.log
```

Criteriu — la fiecare din cele cinci:
```
STT_REQUESTED session=js-<n> trigger=conversation_mode   ← EXACT UNUL pe rostire
STT_REJECTED  reason=session_active activeSession=js-<n> rejectedSession=js-<n+…>   ← unde înainte era dublură (poate lipsi dacă race-ul nu apare la acea rostire)
STT_SESSION_CLOSED session=js-<n> reason=result           ← sesiunea s-a închis curat
peakRms=<peste prag>                                       ← niciun peakRms=32
```
Nu trebuie să apară: două `STT_REQUESTED` pe aceeași rostire · două `RECORDER_CREATE` back-to-back · `peakRms=32` · `reason=no_speech` pe o rostire clară.

BENSON aude și răspunde la toate cinci, nu 1 din 5. Dacă vreo rostire arată tot dublură, trimite-mi cele trei linii pentru acea rostire — revin la `C3_* = false` fără să aștept.
