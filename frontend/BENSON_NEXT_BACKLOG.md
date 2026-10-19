# BENSON NEXT — backlog (document de referință, Master Build)

## Patru stări distincte, niciodată confundate

De la 19.10.2026 (mandat „BENSON NEXT — CONSOLIDARE ARHITECTURALĂ"), fiecare capacitate se
urmărește pe PATRU coloane separate, pentru că sunt lucruri diferite:

1. **COD IMPLEMENTAT** — există ca fișier sursă, compilează. Nu spune nimic despre dacă rulează în
   aplicația reală sau dacă a fost vreodată executat.
2. **CONECTAT ÎN APLICAȚIA ACTIVĂ** — codul e parte din drumul real pe care BENSON îl execută pe
   telefon (fie comis, fie în diff-ul necomis al `BensonForegroundService.kt`), nu doar într-un
   fișier izolat. Nu spune nimic despre dacă a fost testat.
3. **TEST AUTOMAT TRECUT** — există un test JUnit care trece. Rulează în acest mediu (fără
   dispozitiv), deci dovedește doar logica pură, nu comportamentul pe telefon.
4. **DEVICE_PASS** — verificat REAL, pe telefon, de proprietar. Singura coloană care autorizează
   declararea unei capacități drept „gata".

**Nicio capacitate nu e „integrare completă" până nu are DEVICE_PASS.** O coloană bifată nu
promovează automat pe următoarea.

## Arhitectura: UN SINGUR BENSON LIVE — nu două

`BensonForegroundService.kt` conține deja, necomis, un agent OpenAI cu tool-use complet
(`tryAgent`/`runAgentToolLocked`/`BensonAgentTools.kt`, gpt-5-nano, buclă mărginită pe
`agentRunId`, niciodată succes fals pe o rulare anulată) — **asta ESTE BENSON LIVE real**, nu un
concept separat. `BensonLiveOrchestrator`/`ModelProvider`/`CanonicalIntent` din NEXT (fișiere
izolate, 400 teste) rămân o arhitectură de REFERINȚĂ, pură, testată — valoarea lor se **migrează**
spre autoritatea reală (tryAgent), nu se cablează alături ca un al doilea orchestrator. Asta s-a
întâmplat deja o dată (SURVIVOR → navigație Waze) și o a doua oară (SURVIVOR + clasificare onestă
→ instrumentul `open_app` al agentului) — vezi tabelul de mai jos.

## Auditul celor 9 instrumente ale agentului, față de garanțiile LIVE NEXT

| Instrument | Garanție LIVE NEXT relevantă | Stare găsită | Acțiune |
|---|---|---|---|
| `open_app` | SURVIVOR (verificare onestă a efectului, doctrina 9) | **BUG REAL**: `resp != null` trata textele de EȘEC ale `tryNativeOpenApp` drept succes | **Reparat** (`FIX_AGENT_OPEN_APP_HONESTY_1` + poll `classifyLaunchVerification`, 19.10.2026) |
| `search_in_app` | EXECUTOR (rezultat verificat) | Deja onest — `runUniversalHandCommand` întoarce `ok/message` din propria verificare (modul `benson-accessibility`, neauditat în profunzime această rundă) | Neschimbat |
| `observe_screen` | AKA/UI DISCOVERY | Deja generic — citire pură, fără ramificații per-aplicație | Neschimbat |
| `tap_element` | EXECUTOR (plan → acțiune) | Deja generic — index+etichetă, fără cunoaștere de aplicație; `FIX_AGENT_TAP_CONTRACT_1` deja verifică eticheta contra unui ecran schimbat | Neschimbat |
| `open_url` | SURVIVOR | Naiv — `startActivity` fără aruncare = succes, fără verificare reală a paginii | **Identificat, neatins** (fără un pachet-țintă unic de verificat, risc mai mare de fals pozitiv la o reparație grăbită — rămâne pentru o rundă dedicată) |
| `play_stream` | SURVIVOR | Deja exemplar — verifică `Content-Type` audio la server ÎNAINTE de a porni ceva | Neschimbat |
| `verify_playing` | SURVIVOR | Deja exemplar — dublă citire la 1,8s distanță, anti-fals-pozitiv deja demonstrat | Neschimbat |
| `explain_command` | AKA (expunerea registrului) | Deja făcut (commit anterior `b78faae`) | Neschimbat |
| `say` | — | Terminal, fără acțiune pe telefon | Neschimbat |

**Concluzie onestă**: singurul gol real de onestitate găsit în cele 9 instrumente a fost
`open_app`. Restul fie era deja conform doctrinei 9 (`play_stream`/`verify_playing`), fie e generic
prin construcție (`observe_screen`/`tap_element`) și nu are nevoie de AKA/SURVIVOR în forma lor
NEXT, fie ține de un alt modul (`search_in_app`, delegat la `benson-accessibility`, neauditat
acum).

## Misiunea de acceptare: „Benson, deschide Calculatorul și calculează 27 × 14"

| Pas din mandat | Cod implementat | Conectat în aplicație | Test automat | DEVICE_PASS |
|---|---|---|---|---|
| USER → OPENAI AGENT | da (preexistent) | da (preexistent) | n/a (necesită cheie reală) | NU |
| LIVE MISSION (clasificare + răspuns într-un apel) | da (preexistent, `tryAgent`) | da | n/a | NU |
| AKA / identificare aplicație fără comandă preprogramată | da (`tryNativeOpenApp`, `findLaunchablePackage`, sinonime) | da | parțial (matching testat, nu modelul) | NU |
| UI DISCOVERY (`observe_screen`) | da (preexistent) | da | n/a (Context-dependent) | NU |
| ACTION PLAN + ACCESSIBILITY EXECUTOR (`tap_element`) | da (preexistent) | da | n/a (Context-dependent) | NU |
| SURVIVOR verification (deschidere confirmată pe ecran) | **da, nou** (`classifyLaunchVerification` cablat în `open_app`) | **da, nou** | da (`AgentOpenAppVerificationTest`, 5 teste) | NU |
| Citire rezultat REAL din Calculator (nu calcul mental al LLM-ului) | **da, nou** — instrucțiune explicită în `systemPrompt` | **da, nou** | da (`BensonAgentToolsGenericityTest`, verifică prezența instrucțiunii) | **NU — asta e exact partea netestabilă fără telefon real + apel OpenAI real** |
| UNKNOWN când rezultatul nu poate fi confirmat | da, nou (aceeași instrucțiune) | da | da (text prezent) | NU |

**De ce nu e declarată „misiunea completă"**: pașii 1-6 rulează deja, astăzi, prin mecanismul
existent (nimic nou de construit pentru ei — mandatul a cerut verificare, nu reinventare). Pasul
critic nou (citirea onestă a rezultatului) e doar o INSTRUCȚIUNE de prompt, nu o garanție impusă în
cod — exact ca `verify_playing` înainte să fie dovedit pe telefon. **Fără un apel OpenAI real pe un
telefon real, nu există nicio dovadă că gpt-5-nano chiar respectă instrucțiunea** în loc să
calculeze mental 27×14=378 și să-l rostească fără să fi citit ecranul. Acesta e motivul explicit
pentru care raportul NU declară integrarea completă.

## WhatsApp VIDEO_CALL — generalizare, dry-run

| Verificare | Rezultat |
|---|---|
| `observe_screen`/`tap_element` conțin cod specific WhatsApp? | **Nu** (`BensonAgentToolsGenericityTest.toolsJsonContainsNoCalculatorOrWhatsappSpecificBranching`, test automat trecut) |
| O frază germană de apel video ("Mach einen Videoanruf mit Hannah auf WhatsApp") e înghițită accidental de matcher-ul determinist românesc (`WaCallVideoMatcher`), blocând calea generică? | **Nu** — verificat prin test automat (`waCallVideoMatcherDoesNotSwallowGermanPhrasing`) |
| Control pozitiv: aceeași intenție în română tot ajunge pe calea deterministă existentă? | Da, test automat (`waCallVideoMatcherStillRecognizesTheEquivalentRomanianPhrasing`) |
| Agentul generic a descoperit și apăsat REAL un buton de video-call WhatsApp, în dry-run, pe un ecran real? | **NU** — cere dispozitiv + apel OpenAI real; neexecutat, neinventat |

Concluzie onestă: generalizarea mecanismului e dovedită structural (nimic specific WhatsApp în
instrumente, nimic blochează fraza germană să ajungă la agent) — dar **nicio execuție reală, nici
măcar dry-run, nu a avut loc**, pentru că asta cere un apel OpenAI real pe un dispozitiv real,
niciunul disponibil aici.

## FĂCUT — cod implementat + conectat + test automat trecut (zero DEVICE_PASS)
- [x] D0 — contracte comune (`BensonAgentContracts.kt`)
- [x] BENSON LIVE (arhitectură de referință, neconectată ca sistem separat) — `BensonLiveOrchestrator.kt`
- [x] AKA — `StaffRegistryCapabilityLookup.kt` cablat la `StaffRegistryCore`/`StaffRegistry` reale
- [x] MEMORY — store criptat AES-256-GCM + rezolvare multilingvă (ro/de → aceeași locație)
- [x] TRAVEL — plan misiune + `NavigationExecutionEngine.kt` + anunț onest rută/trafic
- [x] COMMS — dry-run WhatsApp prin orchestratorul de referință (RO/DE identice pe VIDEO_CALL)
- [x] SHOPPING — al treilea domeniu, dry-run, prin același orchestrator de referință
- [x] SOS (parțial) — contracte + trigger gate + model KWS antrenat (neintegrat în HEED)
- [x] VOICE — decizie ONE VOICE + client HTTP OpenAI TTS (cod, neinvocat)
- [x] ModelProvider real de referință (cod HTTP + parsare testată; neinvocat cu cheie reală)
- [x] Anti-confuzie simulat/real (`EvidenceLabels.kt`)
- [x] LIVE — flux explicit de confirmare opt-in (arhitectură de referință)

## FĂCUT ȘI CONECTAT ÎN APLICAȚIA ACTIVĂ (necomis, parte din diff-ul protejat al `BensonForegroundService.kt`)
- [x] SURVIVOR → navigație Waze reală (`handlePendingNavAnswer`, 19.10.2026) — corecție asincronă,
      onestă, doar pe URI-ul principal `com.waze`; răspunsul deja rostit neschimbat.
- [x] SURVIVOR + onestitate → instrumentul `open_app` al agentului real (`FIX_AGENT_OPEN_APP_HONESTY_1`,
      19.10.2026) — bug real reparat (succes fals pe text de eșec) + poll de verificare pe ecran.
- [x] Instrucțiune de verificare onestă a rezultatului vizibil, generică (nu per-aplicație), în
      `systemPrompt` — acoperă cerința „nu declara succes doar fiindcă LLM-ul poate calcula singur".

## BLOCAT — cere autorizare explicită sau un dispozitiv, nu se face autonom
- [ ] **DEVICE_PASS pentru misiunea de acceptare completă** („deschide Calculatorul și calculează
      27 × 14", citire reală a ecranului) — cere telefon conectat + un apel OpenAI real (cheie +
      cost). Nimic de construit în plus fără asta; următorul pas e strict testarea, nu codul.
- [ ] **DEVICE_PASS pentru generalizarea WhatsApp VIDEO_CALL dry-run** — idem, cere dispozitiv + apel real.
- [ ] **SOS local real** (modelul KWS „AJUTOR" în `HeedWakeWord.kt`) — fișier protejat, model
      nevalidat pe voce reală.
- [ ] **Invocarea reală a OpenAI TTS** cu o cheie reală — cost nou neautorizat explicit.
- [ ] **Orice DEVICE_PASS** — acest mediu nu are `adb`/dispozitiv conectat.
- [ ] **Instalare APK, ștergere date, apeluri/mesaje/plăți reale** — în afara autorității curente.
- [ ] **Sursă reală de rută/trafic live** (Waze/Directions API conectat) — rețea live + autorizare.
- [ ] **Extindere SOS Watch către un modul Wear OS real** — modul/dependență nativă nouă, red gate.
- [ ] **Auditul `search_in_app`/`open_url`** (modulul `benson-accessibility`, respectiv o reparație
      de onestitate pentru `open_url` fără pachet-țintă unic) — neînceput, nu urgent, risc mai mare
      de regresie grăbită decât valoare imediată; candidat pentru o rundă dedicată, separată.

## URMEAZĂ — neblocat, dar neînceput
**Niciun item cu adevărat independent de dispozitiv/cost nu a mai rămas identificat** după auditul
celor 9 instrumente. `search_in_app`/`open_url` (de mai sus) sunt candidați, dar marcați BLOCAT
intenționat — nu lipsă de autoritate, ci o decizie deliberată de a nu grăbi o reparație pe un drum
fără pachet-țintă clar de verificat, fără dovadă că ar fi nevoie.

## Notă despre reluare
Orice sesiune viitoare: citește `BENSON_NEXT_BUILD_STATE.md` întâi. Nu declara nicio capacitate de
mai sus drept „integrare completă" până nu are DEVICE_PASS pe coloana a patra.
