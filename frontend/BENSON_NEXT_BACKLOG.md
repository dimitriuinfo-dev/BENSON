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

## DEVICE_PASS real obținut: SURVIVOR / navigație Waze (19.10.2026)

Testat pe OnePlus Nord 4 conectat, cu autorizare explicită de instalare + cost API (plafon 0,10 USD,
~0,04-0,05 USD folosiți). „Du-mă la aeroport" → „da" → `NAV_LAUNCH pkg=com.waze ok=true` →
`NAV_VERIFY outcome=Success(verifiedBy=foreground_package, evidence=com.waze)` → confirmat
INDEPENDENT prin `dumpsys window` (`mCurrentFocus=...com.waze...`). Tag:
`DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`. Detalii complete în `BENSON_NEXT_BUILD_STATE.md`.

## Misiunea de acceptare: „Benson, deschide Calculatorul și calculează 27 × 14" — EXECUTAT REAL, nu PASS complet

| Pas din mandat | Cod implementat | Conectat în aplicație | Test automat | DEVICE_PASS |
|---|---|---|---|---|
| USER → OPENAI AGENT | da (preexistent) | da (preexistent) | n/a | da — apel real executat |
| LIVE MISSION (clasificare + răspuns într-un apel) | da (preexistent, `tryAgent`) | da | n/a | da — rulat real, mai multe runde |
| AKA / identificare aplicație fără comandă preprogramată | da (`tryNativeOpenApp`, `findLaunchablePackage`, sinonime) | da | parțial | da — `open_app` a găsit Calculatorul de fiecare dată |
| UI DISCOVERY (`observe_screen`) | da (preexistent) | da | n/a | da — apelat real, de mai multe ori per rulare |
| ACTION PLAN + ACCESSIBILITY EXECUTOR (`tap_element`) | da (preexistent) | da | n/a | da — apăsări reale cu durată realistă (200-2600ms) |
| SURVIVOR verification (deschidere confirmată pe ecran) | da (`classifyLaunchVerification` cablat în `open_app`) | da | da (`AgentOpenAppVerificationTest`) | **da** — `AGENT_OPEN_APP_VERIFY outcome=Success` la fiecare rulare |
| Gard în cod (nu doar prompt): fără apăsare → fără declarație de rezultat | da, nou (3 gărzi, `AgentVerificationGate.kt`) | da, nou | da (18 teste) | **da** — `AGENT_VERIFY_GATE blocked=compound_open_say`/`blocked=tap_without_observe` observate REAL, blocând exact două declarații premature |
| Citire rezultat REAL din Calculator, corelată cu ecranul | da — instrucțiune `systemPrompt` + gărzi de cod | da | da (structural) | **NU — telefonul s-a blocat (PIN) înainte să pot verifica EU, independent, afișajul final; nu am încercat să deblochez** |
| UNKNOWN când rezultatul nu poate fi confirmat | da (aceeași instrucțiune) | da | da (text prezent) | neexersat — modelul a ajuns de fiecare dată la un răspuns, niciodată la UNKNOWN explicit |

**De ce nu e declarată „misiunea completă"**: tot ce ține de mecanism (agent, AKA, UI discovery,
executor, SURVIVOR, gărzile de cod) a rulat REAL pe dispozitiv, repetat, cu dovadă în log —
inclusiv dovada că gărzile chiar au blocat de două ori o declarație prematură/falsă înainte de
rularea finală reușită. Ce lipsește e **verificarea mea independentă, vizuală, a ecranului
Calculatorului** pentru rezultatul final „378" — telefonul s-a blocat (securizat cu PIN) chiar după
finalul rulării, și respect absolut regula de a nu încerca deblocarea lui. Trei bug-uri reale găsite
ȘI reparate pe parcurs (plafon de pași, timeout HTTP, gard lipsă) — detalii complete, cu dovezi
logcat, în `BENSON_NEXT_BUILD_STATE.md`.

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
- [x] SURVIVOR → navigație Waze reală (`handlePendingNavAnswer`, 19.10.2026) — **DEVICE_PASS real**
      (vezi secțiunea dedicată mai sus).
- [x] SURVIVOR + onestitate → instrumentul `open_app` al agentului real (`FIX_AGENT_OPEN_APP_HONESTY_1`,
      19.10.2026) — bug real reparat, **reverificat funcțional pe dispozitiv** (`AGENT_OPEN_APP_VERIFY
      outcome=Success` la fiecare rulare a misiunii Calculator).
- [x] Instrucțiune de verificare onestă a rezultatului vizibil, generică (nu per-aplicație), în
      `systemPrompt` — insuficientă SINGURĂ (dovedit pe dispozitiv: modelul a declarat totuși un
      rezultat nereal de două ori), completată de gărzile de cod de mai jos.
- [x] TREI gărzi de verificare în cod (`AgentVerificationGate.kt`) — toate **dovedite active pe
      dispozitiv**: au blocat real două declarații premature/false înainte ca misiunea Calculator
      să se încheie corect.
- [x] `MAX_STEPS` 10→22 și timeout HTTP 25s→45s — **bug-uri reale, reproduse pe dispozitiv, reparate
      și reverificate** (vezi `BENSON_NEXT_BUILD_STATE.md`).

## BLOCAT — cere autorizare explicită sau un dispozitiv, nu se face autonom
- [ ] **Verificare vizuală independentă a ecranului Calculatorului** pentru „378" — telefonul s-a
      blocat (PIN) înainte să pot verifica eu; nu se ocolește blocarea. Cere fie testul direct al
      lui Rareș, fie o rulare viitoare unde ecranul rămâne monitorizabil până la verificare.
- [ ] **DEVICE_PASS pentru generalizarea WhatsApp VIDEO_CALL dry-run** — genericitatea mecanismului
      dovedită structural (test automat), nicio execuție reală încă încercată pe dispozitiv.
- [ ] **Wake acustic / „Benson, taci" / LEGEA TĂCERII — regresie neverificată** această rundă — cere
      fie vocea reală a lui Rareș lângă telefon, fie o injectare WAV pregătită, niciuna disponibilă
      aici. Zero cod atins pe aceste căi, deci zero motiv de regresie suspectată, dar zero dovadă nouă.
- [ ] **SOS local real** (modelul KWS „AJUTOR" în `HeedWakeWord.kt`) — fișier protejat, model
      nevalidat pe voce reală.
- [ ] **Invocarea reală a OpenAI TTS** cu o cheie reală — cost nou neautorizat explicit.
- [ ] **Sursă reală de rută/trafic live** (Waze/Directions API conectat) — rețea live + autorizare.
- [ ] **Extindere SOS Watch către un modul Wear OS real** — modul/dependență nativă nouă, red gate.
- [ ] **Auditul `search_in_app`/`open_url`** (modulul `benson-accessibility`, respectiv o reparație
      de onestitate pentru `open_url` fără pachet-țintă unic) — neînceput, nu urgent, risc mai mare
      de regresie grăbită decât valoare imediată; candidat pentru o rundă dedicată, separată.

## URMEAZĂ — neblocat, dar neînceput
**Niciun item cu adevărat independent de dispozitiv/cost nu a mai rămas identificat** după auditul
celor 9 instrumente și testele reale de pe telefon. `search_in_app`/`open_url` (de mai sus) sunt
candidați, dar marcați BLOCAT intenționat — nu lipsă de autoritate, ci o decizie deliberată de a nu
grăbi o reparație pe un drum fără pachet-țintă clar de verificat, fără dovadă că ar fi nevoie.

## Notă despre reluare
Orice sesiune viitoare: citește `BENSON_NEXT_BUILD_STATE.md` întâi. Nu declara nicio capacitate de
mai sus drept „integrare completă" până nu are DEVICE_PASS pe coloana a patra.
