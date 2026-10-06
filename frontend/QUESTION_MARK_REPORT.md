# BENSON ÎNTREABĂ CA O ÎNTREBARE — 06.10.2026

Branch `wip_2026_10_06_question_mark`, din tag `settings-2026-10-06`.

## Inventar — toate textele rostite ca întrebare

| Text | Fișier:linie | „?" prezent |
|---|---|---|
| „Am găsit X. Îl pornesc?" (YouTube) | `BensonForegroundService.kt:1150`, `:1568` | ✅ |
| „Nu am înțeles numărul. Care?" / „...Pe care?" | `BensonForegroundService.kt:1286`, `:2427` | ✅ |
| „Apăs pe «X»?" (control generic) | `BensonForegroundService.kt:1477` | ✅ |
| „Da sau nu?" | `BensonForegroundService.kt:1518` | ✅ |
| „Am găsit mai mulți: X. Pe care?" (WhatsApp) | `BensonForegroundService.kt:2253`, `:2555` | ✅ |
| „Mesaj, apel sau video?" | `BensonForegroundService.kt:2282`, `:2495` | ✅ |
| „Ce să-i scriu?" | `BensonForegroundService.kt:2290` | ✅ |
| „X nu are video pe WhatsApp. O sun normal?" | `BensonForegroundService.kt:2303`, `:2470` | ✅ |
| „O sun pe X pe WhatsApp?" | `BensonForegroundService.kt:2440` | ✅ |
| „Îi scriu lui X: «…». Trimit?" | `BensonForegroundService.kt:2442` | ✅ |
| „Pe cine să sun, {adresare}?" (RO/DE/EN) | `app/index.tsx:246-248` | ✅ |
| „Who should I add, {adresare}?" | `app/index.tsx:4190` | ✅ |
| „Sun la 112?" | `app/index.tsx:4387` | ✅ |
| „Te referi la X?" / „...X sau Y?" | `app/index.tsx:5378-5379` | ✅ |
| „N-am putut duce asta la capăt... Poți spune comanda altfel?" | `app/index.tsx:5418` | ✅ |
| „Doriți să o pregătesc acum?" (vinietă) | `app/index.tsx:3403` | ✅ |
| „Am găsit X. O deschid?" (deschidere aplicație) | `lib/agents/appLauncherAgent.ts:100,117`, `src/executors/appLauncherExecutor.ts:117,184` | ✅ |

**Rezultat onest: nicio corecție de text necesară.** Toate întrebările rostite din cod se termină
deja cu „?" — niciun caz de text lipsă găsit. „Doar textul se corectează" nu s-a aplicat nicăieri,
fiindcă nu era nimic de corectat.

## Verificarea lanțului până la TTS

`BensonForegroundService.speakNativeFallbackThenCapture` (sursa reală a aproape tuturor
întrebărilor de mai sus — WhatsApp/YouTube/control generic): `t.speak(text, ...)` primește `text`
NESCHIMBAT, fără nicio sanitizare/trunchiere de punctuație pe acest drum. Adăugat log nou chiar
înainte de apel: `TTS_NATIVE_REQUEST text="…"` (textul complet, cu punctuația) — singurul loc unde
textul complet ajunge în log, fiindcă aici trăiesc EXCLUSIV întrebările de confirmare generate din
cod, niciodată conținut citit de pe ecran sau mesaje WhatsApp.

**NU am extins** `lib/agents/voiceAgent.ts`'s `speakNow`/`TTS_NATIVE_REQUEST` (varianta JS) cu
textul complet — rămâne doar `chars=`/`language=`, cum era. Motiv: `speakNow` e o funcție comună,
folosită potențial și pentru citit conținut WhatsApp (date UNTRUSTED) — politica explicită din cod
„never write message text to logs" contează acolo mai mult decât confortul de debug al acestei
runde. Inventarul de mai sus arată oricum că sursa reală a întrebărilor e aproape toată nativă.

## Persona, SYSTEM

`lib/agents/claudeAgent.ts`, `buildSystemPrompt` — rând nou, comun tuturor celor 3 personaje
(butler/friend/professional), nu doar unuia: „When you're not sure what the user wants, ask one
short question — don't assume, and don't refuse." (funcția e deja partajată cu `openaiAgent.ts`,
deci se aplică identic la Claude/Gemini/ChatGPT din conversația liberă).

## Limita cunoscută a vocii — relevantă pentru runda VOCEA

Găsit un comentariu existent, `BensonForegroundService.kt:364-371`
(`FIX_TTS_INTONATION_REVERT`, 2026-10-03): o rundă anterioară A ÎNCERCAT deja intonație de
întrebare (manipulare de pitch) și a fost REVERTITĂ — „suna groaznic... ton ciudat/nenatural",
mai rău decât vocea simplă. Concluzia de atunci, scrisă în cod: „intonația reală de întrebare are
nevoie de un alt motor/voce cu control real de prosodie (SSML), nu un truc la nivel de text pe
API-ul standard speak()/setPitch()". **Dacă „?" e prezent (confirmat mai sus, peste tot) și tot
sună plat, asta e cauza — nu text lipsă — și se rezolvă doar la runda VOCEA (ElevenLabs), nu aici.**

## TESTE

- `npx tsc --noEmit`: curat.
- `gradlew assembleRelease`: BUILD SUCCESSFUL (1m 34s, a recompilat Kotlin). APK:
  `android/app/build/outputs/apk/release/app-release.apk`, 387.334.775 bytes,
  sha256 `d231a83646b0d73489e0b2ae4853d8ac853d5fb3ffbb3ad0007e1afd2cbbb1a7`. Instalat pe dispozitiv.

## ACCEPTARE (Rareș)

5 confirmări rostite (apel/video/mesaj WhatsApp, deschidere aplicație ambiguă, YouTube) — verifică
în logcat (`TTS_NATIVE_REQUEST`) că fiecare text se termină cu „?"; confirmă după ureche dacă sună
ca întrebare sau plat (în al doilea caz, notat deja mai sus — e limita vocii, nu text).
