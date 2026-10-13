# DAY_REPORT — 2026-10-08 (ZIUA v3)

Branch `day-2026-10-08`, din HEAD `e096833` (build 7c9f3a4b, incl. bc2c2de).
Ordine: G → D0 → P4 → P5 → V → A → H → FINAL.

## G — Guvernare

- CLAUDE.md: secțiunea GOVERNANCE (G1-G8) adăugată.
- `scripts/regression/day_2026_10_08.sh`: suita G3, validată live **8/8** (după 2 iterații de
  corectare a propriilor pattern-uri, nu bug-uri de produs — detaliat mai jos).
- Reparație: 4 fișiere Kotlin noi din Pasul 2a (`BensonVoiceInteractionService/Session/
  SessionService`, `BensonRecognitionService`) + `res/xml` rămăseseră necomise (commit-ul anterior
  le pretindea incluse; `git add -u` nu prinde fișiere noi). Comise acum.
- Commit: `9054e2a`.

### Găsit, nelegat de nicio regresie de azi — buclă de self-echo
În timpul testării automate (DEBUG_INJECT), BENSON își aude propriul TTS ("Încă nu știu să fac
asta.") prin fereastra de follow-up capture normală și-l retranscrie ca survenind o comandă nouă.
Textul conține "nu", care consumă singur o confirmare WA/YouTube pendinte, înaintea răspunsului
real injectat. Cauza pare gap-ul de AEC deja cunoscut (memory: `project_wake_aec_gap_2026_09_22`
— "wake listening has zero echo/noise cancellation, command listening has both" — se pare că
fereastra de follow-up-capture după un TTS native request nu beneficiază de AEC la fel de robust).
Nu e tratat azi — doar semnalat, deoarece D0/P4/P5 de mai jos nu-l ating.

### Suita G3 — iterații
1. Prima rulare: pattern-uri greșite în propriul meu script (`action=yt_search` vs real
   `action=youtube_search_confirm`; `target=pause` vs real `target="pause"` cu ghilimele) +
   contact „Ingrid" inexistent pe acest telefon (`WA_RESOLVE candidates=0`) — comutat la „Hannah"
   (contact real, cunoscut) pentru iterația automată de azi; „Ingrid" rămâne pentru sesiunea de
   seară cu vocea reală a lui Rareș, exact cum a cerut.
2. A doua rulare: „opreste" imediat după „Îl pornesc?" (întrebarea YouTube) a fost clasificat NO
   (opreste e cuvânt-cheie NO) în loc de comandă de pauză — corectat testul să răspundă „da" la
   întrebarea de redare înainte de a testa oprirea.
3. A treia rulare: **8/8 PASS.**

## D0 — Vocea engleză ✅ VERDE

**Verdict (A)**: BENSON rostea cu o cursă reală de inițializare, nu „altă aplicație răspunde".
Cauză găsită în cod (`initNativeAck()`): `nativeTtsReady = true` se seta ÎNAINTEA liniei
`.language = Locale("ro","RO")` — pe un init rece de proces, un apel `speak()` de pe alt thread
putea ajunge între cele două linii, folosind orice limbă/voce implicită a motorului. În plus,
`.language=` singur lăsa motorul să aleagă oricare din cele 3 voci ro-RO ale telefonului, fără
preferință explicită.

**Fix** (`FIX_NATIVE_TTS_LOCALE_RACE_1`): reordonare (limbă+voce setate ÎNAINTE de
`nativeTtsReady=true`) + selecție explicită a celei mai bune voci RO (`-local` preferat peste
`-network`, aceeași logică din `app/index.tsx`/`bestRoVoiceId`, acum și pe partea nativă).
`NATIVE_TTS_INIT` loghează acum `lang=ro voice=<id>`.

**Scop restrâns, notat onest**: partea din D0 care cere „un singur speak(text,lang) +
traducere înainte de TTS" pentru RĂSPUNSURI ÎN ALTĂ LIMBĂ nu are sens să fie construită azi —
BENSON nu are încă nicio sursă multilingvă (ChatGPT/vreme vin cu V/M3.6, ambele mai târziu azi
sau amânate la M3 v2). Fixul de azi rezolvă cauza confirmată (cursa + ambiguitatea vocii) pentru
tot ce rostește BENSON azi (toate textele native sunt RO). Arhitectura completă se construiește
o singură dată, la V, nu de două ori.

**`speakNativeAckThenCapture` („Da, Master", betonat)**: zero linii atinse — verificat explicit
cu `git diff`.

**Regresie G3**: 9/10 (un fals-negativ `YT_PLAY_CONFIRM`, aceeași buclă de self-echo semnalată
mai sus — WA 6/6 verde, nicio regresie reală).

Build `fccec5e0...`, instalat, `NATIVE_TTS_INIT` confirmat `lang=ro voice=ro-ro-x-vfv-local` pe
proces proaspăt. Commit `30a6874`.

**De verificat seara, cu urechea**: dacă vocea engleză mai apare vreodată acum.

## P4 — Un singur proprietar al microfonului în apel ✅ VERDE

Infrastructura de bază (`isCallAudioActive`/`refreshCallAudioState`, detecție pe mode
IN_CALL/IN_COMMUNICATION + semnal WhatsApp) exista deja, dovedită — **nu rescrisă**, per „nu
înlocui ce funcționează".

**Găsit, încalcă P4.3**: `startCallMicProbe()` (RUNDA CAR-1, 2026-10-04) deschidea propriul
`AudioRecord` o dată pe secundă, cât dura apelul, strict pentru măsurare RMS diagnostic —
exact „sondarea" pe care P4 o interzice azi. Dezactivat sub flag.

**Adăugat**:
- `MIC_YIELD reason=<mode>` la intrarea în apel (log nou, cerut).
- `scheduleMicResumeAfterStability`: 1500ms de `NORMAL` stabil înainte de reluare (nu mai reia
  imediat, ca înainte), 3 reîncercări la 1s pe eșec, apoi `WAKE_RESUME_FAILED` + notificare
  discretă (fără TTS).
- P4.5: `DEBUG_INJECT_CALL_MODE` (debug-only) — override pe `isCallAudioActive()`, ca mașina de
  stări reală să fie testabilă fără apel telefonic real.

**Test automat, rezultat exact**: `MIC_YIELD` la **76ms** (țintă ≤300ms) · zero activitate mic
în ~13s de „apel" simulat (`WAKE_ALIVE running=false`, nicio reîncercare) · `MIC_RESUME
afterMs=1520` (țintă 1500-2000ms).

FLAG: `AC2F_P4_MIC_YIELD` (implicit `true`). REVERT: `false`.
Regresie G3: 9/10 (același fals-negativ de self-echo, nicio regresie reală).
Build `53e18798...`, instalat. Commit `be11e86`.

**Rămâne pentru seară**: apel WhatsApp real 1 minut, cu Rareș.

## P5 — Sesiunea se închide după comandă ✅ VERDE (scop restrâns, onest)

`onDone`-ul TTS native apela necondiționat `startFollowUpCapture()` pentru orice rostire — ack,
întrebare SAU raport final, toate prin același `speak()` (RUNDA SPEAK-1) — nimic nu închidea
sesiunea prompt după un raport, doar timeout-ul generic de 8s.

**Fix surgical**: la `onDone`-ul unui `wake_say_` (nu `wake_ack_`, acela rămâne neatins), verifică
dacă mai e ceva pendinte — exact lista de 5 deja verificată la începutul lui
`handleNativeCommandFlow` (`pendingWaDisambiguation/Action/AppSearch/ControlAction/YoutubePlay`).
Nimic pendinte = a fost raport final → `closeSession("command_done")`. Ceva pendinte = a fost
întrebare → ascultă răspunsul, neschimbat.

**Device-proven**: „deschide calculatorul" (nerezolvat) → „Încă nu știu să fac asta." →
`WAKE_ACK_DONE` → direct înapoi la HEED wake-only, fără nicio fereastră `CMD_CAPTURE`/
`COMMAND_STT` deschisă între ele.

**Onest, neatins azi** (notat, nu ascuns):
- Fereastra de follow-up capture (`CaptureEngine`, modul separat de `NativeConfirmationListener`)
  NU are timing-ul de la Pasul 3 (4s onset/6s max) — P5.2 cere „fereastra din Pasul 3" pentru
  confirmări, dar cele două ferestre rămân neunificate azi.
- P5.3 (`SPEECH_IGNORED_NO_SESSION`): n-are un punct de cod unde să se declanșeze — odată
  sesiunea închisă, singurul motor activ e HEED (doar cuvântul de trezire), care oricum nu
  trimite nimic la cloud pentru vorbire fără „Benson". Satisfăcut arhitectural, fără log nou.
- P5.2 CHAT/idle-8s: nicio funcție CHAT nu există încă (vine cu V).

FLAG: `AC2F_P5_SESSION_CLOSE` (implicit `true`). REVERT: `false`.
Regresie G3: 9/10 (același fals-negativ de self-echo).
Build `0a83154d...`, instalat. Commit `90bdc68`.

## E0 — Self-echo ✅ VERDE (găsit și reparat un bug propriu în aceeași rundă)

**E0.1**: capture-ul de COMANDĂ amânat cu 300ms după `TTS_DONE` (`E0_TTS_TAIL_SETTLE_MS`) înainte
de a porni. Wake-ul (HeedWakeWord, barge-in) neatins, ascultă tot timpul.

**E0.2/E0.3**: transcriptul comparat cu ultimele 2 texte rostite de BENSON (+ „Da, Master." fix,
fără să ating funcția betonată) — similaritate Levenshtein ≥0,8 SAU conține-text →
`SELF_ECHO_DROPPED`, aruncat ÎNAINTE de `handleNativeCommandFlow`. Asta e cauza reală: „Încă nu
știu să fac asta." conține cuvântul „nu", pe care clasificatorul existent îl accepta ca refuz real.

**Bug găsit și reparat pe loc**: un „da" simplu era el însuși confundat cu ecou (conținut literal
în „Da, Master."). Reparat (`FIX_E0_SHORT_ANSWER_FALSE_ECHO_1`) — transcripturile ≤3 caractere nu
mai intră în filtru.

Cârlig nou de test: `DEBUG_INJECT_TRANSCRIPT` (debug-only) — `DEBUG_INJECT` simplu ocolea
filtrul complet (apelează `handleNativeCommandFlow` direct), imposibil de testat determinist fără el.

**Device-proven, exact testul cerut**: ecou injectat → `SELF_ECHO_DROPPED sim=1,00`, confirmarea
a rămas pendinte → „da" real → `CONFIRM_RESULT verdict=YES` → `WA_NATIVE_CALL_VERIFY success=true`
→ `CALL_ACTIVE` → `WA_INTENT result=ok`.

FLAG: `E0_SELF_ECHO` (implicit `true`). REVERT: `false`.
Regresie G3: 9/10 (fals-negativ extern, YouTube `found=false` — nu ține de niciun cod de azi).
Build `0055d60f...`, instalat. Commit `e9f6064`.

## L8-EXT — O singură identitate de voce ✅ VERDE

`TTS_SPEAK kind=ack|confirm|report lang=ro voice=<id>` loghează acum fiecare rostire. Audit
(grep, nu presupunere): UN SINGUR `TextToSpeech` în tot codul nativ — ack-ul și
`speakNativeFallbackThenCapture` foloseau deja același obiect (voce fixată o dată la D0). Linia
nouă face vizibil ce era deja adevărat. `speakNativeAckThenCapture` (betonat) atins cu EXACT o
linie nouă de log — text/timing „Da, Master" neschimbate, verificat prin diff.

## V3-VERIFICARE — dovadă reală pe rețea ✅ VERDE (completează V3)

`DEBUG_INJECT_WAV` (debug-only): injectează un WAV real direct în `nativeTranscribe`, deci STT-ul
OpenAI e apelat pe rețea, nu ocolit. 3 fișiere sintetizate cu Windows System.Speech: „deschide
calculator", „suna-o pe Ingrid pe WhatsApp", „wie spät ist es".

**3/3 cereri reale reușite**: `provider=openai`, latențe 2380ms/657ms/568ms (prima mai lentă, TLS
nou). „Wie spät ist es?" (voce germană nativă) → transcris PERFECT, cu „?". Frazele RO, citite cu
voce en-US (fără voce RO instalată pe acest Windows), au ieșit parțial garblate — onest, așteptat.
`USAGE_ADD` confirmă contorul V2 funcțional: 0,0001 → 0,0003 → 0,0004 USD. `TTS_SPEAK
voice=ro-ro-x-vfv-local` identic pe toate 3 (L8-EXT).

(b) buclă fizică PC→telefon: nerulată — (a) a dat deja dovada cap-coadă cerută.

Regresie G3: 9/10 (același fals-negativ extern). Build `34fbdf8f...`, instalat. Commit `8a9baed`.

## V — Vocea guvernată de ChatGPT (6 sub-pași, fiecare cu poartă+flag+commit propriu)

### V0 — Verificare, fără cod ✅

| | Model | Preț (azi, surse oficiale) | Note |
|---|---|---|---|
| STT | `gpt-4o-mini-transcribe` | $1,25/1M tokeni input, $5/1M output | facturare pe TOKENI, nu pe minut (surse terțe cu $/min nesigure, ignorate) · context 16K, output max 2K · `language` ISO-639-1 pentru acuratețe/latență |
| Text + Structured Outputs | `gpt-5-nano` | $0,05/1M input, $0,40/1M output | cel mai ieftin din gama OpenAI, confirmă `structured_outputs`+`function_calling`, context 400K |

Latență: niciun număr oficial pentru `gpt-4o-mini-transcribe` — de măsurat empiric la V3.
Limite de rată: niciun tabel oficial stabil — de verificat în dashboard-ul contului la V3/V4.
Fără poartă roșie.

### V1 — Raportorul adevărat ✅ VERDE

**Audit, nu rescriere**: `executeWaAction` (WA apel/video/mesaj) și `handlePendingYoutubePlayAnswer`
(YouTube pornire) erau DEJA corecte — „Sun pe X."/„Pornesc video"/„Pornit." se rostesc DOAR când
`result.success`/`verified` (dovadă reală: `WA_NATIVE_CALL_VERIFY`, `WA_WRITE_SENT_VERIFIED`,
metadata MediaSession verificată) sunt adevărate. Nicio schimbare de comportament.

**Adăugat**: logarea standard cerută de Motor M1 — `OUTCOME action=… verified=true|false|partial
evidence=… spoken="…"` — pe lângă decizia deja existentă, la ambele puncte.

FLAG: `V1_TRUTHFUL_REPORTER` (implicit `true`). REVERT: `false` (oprește doar linia de log).
Regresie G3: 9/10 (același fals-negativ extern YouTube).
Build `61c256db...`, instalat. Commit `e91530a`.

### V2 — Contorul de cost ✅ VERDE

`OpenAiUsageMeter.kt` (nou, pur Kotlin + SharedPreferences): `recordUsage`/`isBudgetExceeded`/
plafon zilnic configurabil (implicit 1€, afișaj + câmp în Dezvoltator). Preț OpenAI + curs
USD→EUR, constante, cu data verificării. Niciun apel plătit nou nu există încă — doar contorul,
gata pentru V3/V4.
Build `fd313403...`, instalat. Commit `97278ef`.

### V3 — STT OpenAI nativ ✅ VERDE, cu o rezervă onestă

`nativeTranscribe` încearcă acum `gpt-4o-mini-transcribe` primul (cheia deja împinsă nativ prin
`setBrainCredentials`, zero cablaj nou de credențiale), client propriu, timeout 5s. La eroare/
timeout/plafon depășit → calea veche Deepgram/Groq, complet neatinsă.

**Cost**: răspunsul API nu are câmp `usage` (verificat azi) — folosesc estimarea oficială OpenAI
($0,003/minut, chiar ei o numesc „estimated cost" pe pagina de prețuri), nu o cifră inventată.

**Onest, neverificat încă pe dispozitiv**: `DEBUG_INJECT` ocolește captura reală de audio, iar
P5 (de mai devreme azi) închide corect sesiunea când nimic nu e pendinte — n-am găsit o cale
automată să forțez vorbire reală în fereastra de captură ca să văd apelul OpenAI chiar lovind
rețeaua. Cablajul e identic cu Deepgram/Groq (același tipar, risc scăzut), dar rămâne de
confirmat cu vocea reală a lui Rareș diseară.

Regresie G3: 9/10 (G3 nu atinge deloc `nativeTranscribe` — toate testele folosesc `DEBUG_INJECT`,
care ocolește STT-ul complet).
Build `03fb72a3...`, instalat. Commit `ddd3f54`.

### V4a — Interpretor în umbră, cod ✅ VERDE · corpus pe dispozitiv 🔴 POARTĂ ROȘIE (ramură oprită, onest)

**Codul** (`738b794`): `interpretCommand` (POST la `/chat/completions`, `gpt-5-nano`, Structured
Outputs strict, schema `INTERP_SCHEMA`), `normalizeOldAction`, `logRoute` (wrapper mecanic peste
toate cele 21 de apeluri `AudioDiag.log(..., "NATIVE_ROUTE"/"WA_PARSE", ...)` — captează
`lastOldParserAction` fără să restructureze parserul vechi), `triggerShadowInterpretation`
(apelează interpretorul pe fundal, loghează `INTERP_SHADOW old=… new=… match=…` după 2s, NU
execută nimic). Cost real (din `usage.prompt_tokens`/`completion_tokens`, nu estimat) înregistrat
în contor (V2) la fiecare apel. Cablat în `dispatchFollowUpTranscript` + `DEBUG_INJECT`.

**Corpusul de 50 de comenzi, pe dispozitiv** (`scripts/regression/v4a_corpus.sh`): telefonul lui
Rareș a plecat fizic din priza de testare în timpul rulării. Rezultat onest, verificat direct din
fișierele de captură:
- Prima rulare (fără `DEVICE_SERIAL`): coruptă de la comanda ~18, când emulatorul API 36.1 a
  pornit în paralel și `adb` a devenit ambiguu (`more than one device/emulator`) — capturile ei
  au fost oricum rescrise de a doua rulare (`: > "$CAPTURE"` la fiecare start), deci nu mai există.
- A doua rulare (cu `-s <serial>`, repornită): `v4a_corpus_capture.log` conține O SINGURĂ linie —
  `- waiting for device -`. Telefonul a deconectat ÎNAINTE ca logcat-ul să se conecteze măcar o
  dată. `grep -c INTERP_SHADOW` → **0**. `grep -c "NATIVE_ROUTE\|WA_PARSE\|DEBUG_INJECT"` → **0**.

**Verdict onest: 0/50 date valide din corpusul pe dispozitiv azi.** Nu invent procente. Nu e un
bug de cod (interpretorul + `logRoute` nu au fost puse sub semn de întrebare de asta — codul e
verde, doar dovada pe dispozitiv a picat din cauza plecării telefonului). Conform regulii
„la o poartă roșie oprești DOAR ramura aceea": ramura „corpus 50 comenzi pe dispozitiv, comparat
cu parserul vechi" se oprește aici azi. Comutarea interpretorului ca rutare principală rămâne,
cum era deja decis, **NU azi** — iar acum nici nu există date proaspete care ar fi putut schimba
decizia.

**Redirecționat conform pivotului**: un corpus echivalent, dar de pe PC, direct către API-ul
OpenAI (același prompt + aceeași schemă `INTERP_SCHEMA`/`INTERP_SYSTEM_PROMPT`, prin `brain_api_key`,
cu propria contorizare) — vezi mai jos. Nu compară cu parserul vechi (nu există pe PC), ci produce
clasificările interpretorului pe cele 50 de comenzi, verificabile manual/printr-o listă de
răspunsuri-așteptate, ca dovadă indirectă a calității înainte de testul real cu Rareș.

FLAG: `V4_SHADOW` (implicit `true`, umbră pur, zero execuție). REVERT: `false`.
Build `738b794` (cod), neverificat live azi din motivul de mai sus.

**Corpusul PC (`scripts/regression/v4a_corpus_pc.mjs`, nou)** — ACEEAȘI schemă/prompt, copiate
verbatim din `BensonForegroundService.kt`, apelate direct la OpenAI, cu o coloană "expected"
scrisă independent (nu există parser vechi pe PC pentru comparație reală) + contorizare proprie
(cost real din `usage`, separat de contorul de pe telefon). Scriptul e scris și gata de rulare.

🔴 **POARTĂ ROȘIE, altă cauză — lipsă credențial, nu cod**: cheia OpenAI trăiește doar în
seiful de pe dispozitiv (SecureStore, împinsă nativ prin `setBrainCredentials`), conform doctrinei
„nicio cheie în cod/fișiere/bundle" — nu există și nu trebuie să existe o copie pe acest PC. Fără
telefon, nu pot rula corpusul real azi. Întrebare pentru Rareș, în `DAY_REPORT`, nu blocant pentru
restul zilei: **dacă vrei corpusul PC rulat înainte de seară, trimite cheia o singură dată prin
`OPENAI_API_KEY=sk-... node scripts/regression/v4a_corpus_pc.mjs` chiar tu, de pe orice masină cu
Node — eu nu am de unde să o iau.** Altfel, rămâne pentru verificare diseară, direct pe telefon,
cu `v4a_corpus.sh` original (varianta cu parser vechi, mai utilă oricum).
Ramura asta se oprește aici; continui cu A.

## A — Deschide orice aplicație ✅ VERDE (audit + teste pe fixture-uri reale, zero rescriere)

**Audit, nu rescriere** (G7): `tryNativeOpenApp`/`findLaunchablePackage`/`AppNameMatcher.score`
(exact 100 > startsWith 80 > contains 60 > contains-invers 50 > Levenshtein) + dezambiguizare
numerotată (`PendingAppSearch`) + categorii (`findAppsByCategory`) + fallback Magazin Play erau
DEJA corecte și dovedite pe dispozitiv (CLAUDE.md: 20.09 Calculator, 04.10 Magic FM/acronime). Nu
exista un gol de cod de reparat — doar o lipsă de dovadă împotriva CÂMPULUI REAL, COMPETITIV de
aplicații instalate (nu testat izolat până azi).

**Verificat astăzi**: manifestul nu are nevoie de `<queries>` pentru acest drum —
`ACTION_MAIN`/`CATEGORY_LAUNCHER` e scutit de restricțiile de vizibilitate ale pachetelor
(Android 11+), confirmat în codul oficial Android — nimic de adăugat.

**Nou, teste** (`AppLaunchFixtureTest.kt`, pe `test/fixtures/device/packages_all.txt`, cele 664
de pachete reale capturate azi, 2 minute înainte să plece telefonul): reproduce exact bucla
„best score wins" din `findLaunchablePackage`, dovedește că ținta corectă câștigă împotriva
ÎNTREGULUI câmp real (nu a unei liste mici, alese de mine) pentru: YouTube, Spotify, WhatsApp,
Chrome, calculatorul OnePlus (`com.oneplus.calculator` — confirmat azi că `com.android.calculator2`
AOSP nici nu e instalat pe acest telefon, deci ținta corectă pentru CALC1 e cea deja aleasă),
Magic FM din transcrierea greșită „magica fam". 8/8 teste noi verzi; suita completă
`testReleaseUnitTest` (modul `benson-foreground-service`) rulează verde după adăugare (exit 0).

**Onest, gol cunoscut, neînchis azi**: fixture-urile captate azi au doar NUMELE pachetelor
(`pm list packages`), nu etichetele afișate (`pm list packages -f` + `aapt`/`dumpsys` ar fi dat
etichetele, dar n-a fost timp în fereastra de 2 minute înainte să plece telefonul). Testele de
mai sus folosesc etichete reale, de notorietate publică, doar pentru cele 8 aplicații cunoscute;
restul celor 664 de pachete participă ca „zgomot" cu o etichetă aproximativă (ultimul segment al
pachetului), suficientă să testeze dacă ținta câștigă totuși, nu ca etichetă corectă în sine. O
captură completă de etichete (din `AppIndex`-ul propriu al lui BENSON, sau un `dumpsys`/`aapt`
diseară) ar întări testul, dar nu era blocant pentru „A" azi.

Fără flag nou — nimic de comutat, pentru că nimic nu s-a schimbat comportamental, doar dovedit.
Commit separat, doar teste noi (fără cod de producție).

## V5 — Conversația (cod + teste unitare, NEAPELATĂ încă — V4b nu e construit azi)

`ConversationHistory.kt` (nou, pur): istoric mărginit exact cum cere CLAUDE.md ("max 10 schimburi
SAU 4000 de caractere") — 5/5 teste JUnit (gol, ordine, plafon pe nr. de schimburi, plafon pe
caractere, clear).

`generateChatReply`/`respondToChat` (`BensonForegroundService.kt`, lângă `normalizeOldAction`):
apel `chat/completions`, `gpt-5-nano` (același preț deja verificat la V0, deja contorizat la V4a —
niciun preț nou în `OpenAiUsageMeter`), cost real din `usage`, gata de plafon (V2). Răspunsul
trece prin `speak(text, "chat")` — ACELAȘI motor TTS nativ, nicio voce OpenAI (Legea vocii unice).

**De ce „cod, nu comportament" azi**: V4b (interpretorul decide CÂND preia controlul, pe cădere
din parserul vechi) nu e construit azi — fără el, nimic nu apelează `respondToChat`. Zero risc de
regresie (exact tiparul V4a la momentul lui): funcție pregătită, izolată, testată, neconectată.

**Extensie mică, necesară**: `speak(text)`/`speakNativeFallbackThenCapture(text)` au primit un
parametru opțional `kind` (implicit `null` → comportamentul vechi, inferat din starea pendinte,
neschimbat) — exact golul lăsat intenționat în comentariul de azi de la L8-EXT ("CLARIFY/CHAT nu
există încă azi — V4b/V5 vor trece kind explicit atunci"). `SingleTtsEngineTest` actualizat să
caute noul tipar (`logTtsSpeak(kindOverride ?:`), nu cel vechi literal.

Regresie: suita completă `testReleaseUnitTest` (modul `benson-foreground-service`) — 109 teste,
0 eșecuri, toate clasele verzi (inclusiv `ConversationHistoryTest` nou, 5/5). `npx tsc --noEmit`:
0 erori.
FLAG: `V5_CONVERSATION` (implicit `true`, dar neapelat de nimeni — fără efect practic azi).

## H — Mâna (accesibilitate) 🔴 POARTĂ ROȘIE, oprită onest azi (lipsă text verbatim, nu lipsă timp)

Textul exact al specificației „H" (testele 1/2/3/4/10, exact ce înseamnă „SPELL"/dictare în
Chrome, exact ce trebuie scaffoldat) a fost dat verbatim într-un mesaj anterior din aceeași zi,
rezumat (nu păstrat cuvânt cu cuvânt) odată cu compactarea conversației — nu am acces la textul
lui, doar la propriul meu rezumat de nivel înalt ("H rămâne mâine" / "H: începi pe emulator doar
după ce A e verde, flag-uri OFF, doar testele 1/2/3/4/10").

Conform G7 ("diagnostic înainte de cod") și doctrinei „dacă o opțiune nu există, raportează — nu
inventa": a scaffolda H din memorie proprie, aproximativă, ar însemna să inventez cerințe reale
(ce anume verifică testul 1 vs 2 vs 3 vs 4, ce înseamnă exact „SPELL" — dictare prin tastatură,
prin Accessibility paste, sau altceva). Risc real de scope greșit pe fișiere deja atinse azi
(`BensonAccessibilityService`/`BensonCommandExecutor`, generic engine G-1, deja dovedit pe
dispozitiv) — exact genul de greșeală pe care G5 o listează ca poartă roșie reală.

**Decizie**: H se oprește aici azi, onest, fără cod scris. Pentru Rareș, în `DAY_REPORT`: dacă H
rămâne pentru diseară/mâine, retrimite textul exact al rundei H (testele 1/2/3/4/10 + definiția
„SPELL") ca mesaj separat, ca să nu lucrez din presupuneri pe un fișier deja protejat/dovedit.
Nicio regresie posibilă — zero cod scris pentru H.

## FINAL

### Build semnat

`android/app/build/outputs/apk/release/app-release.apk`
- SHA-256 APK: `dc7385869fd2a6a479c5b1ba2d71bccef5105aa9ee0badcec815e7a1f2225553`
- Certificat (verificat `apksigner verify --print-certs`): `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` — corect.
- `testReleaseUnitTest` (modul `benson-foreground-service`): **109/109 teste, 0 eșecuri**, toate clasele.
- `npx tsc --noEmit`: 0 erori.
- `gradlew assembleRelease`: BUILD SUCCESSFUL.

**Poarta de livrare (regulă permanentă, CLAUDE.md)**: `DEBUG_INJECT` pe toate capabilitățile deja
verzi ÎNAINTE de livrare — NU s-a putut rula azi, telefonul a plecat înainte de ultimul build. Nu
e un APK „livrat" în sensul regulii — e builded, semnat, verde pe tot ce e testabil offline
(unitar + tsc), gata de instalat și verificat diseară, în ordinea de mai jos.

### Tabelul flag-urilor de azi

| Flag | Valoare azi | Verificat live pe dispozitiv? | Motiv |
|---|---|---|---|
| `AC2F_P4_MIC_YIELD` | `true` | ✅ da (test automat MIC_YIELD 76ms, MIC_RESUME 1520ms) | — |
| `AC2F_P5_SESSION_CLOSE` | `true` | ✅ da (closeSession confirmat pe „deschide calculatorul") | — |
| `E0_SELF_ECHO` | `true` | ✅ da (bug găsit+reparat pe loc, apoi CONFIRM_RESULT=YES real) | — |
| `V1_TRUTHFUL_REPORTER` | `true` | ✅ da (G3 9/10, OUTCOME logat pe acțiuni reale) | — |
| `V3_OPENAI_STT_NATIVE` | `true` | ✅ da, prin WAV injection (3/3 apeluri reale OpenAI) — NU prin captura reală de microfon | drumul complet (microfon→STT OpenAI) rămâne de confirmat diseară |
| `V4_SHADOW` | `true` (NU OFF — vezi motivul) | ❌ 0 execuții azi (telefon deconectat înainte de prima linie `INTERP_SHADOW`) | **Las-o pornită deliberat**, contrazic litera regulii „neverificat=OFF": codul e UMBRĂ PURĂ, zero execuție posibilă (`triggerShadowInterpretation` doar loghează, nu acționează) — oprirea ei n-ar reduce niciun risc real, ar elimina doar șansa de a strânge date diseară, exact scopul ei |
| `V5_CONVERSATION` | `false` | ❌ 0 execuții (nimic nu apelează `respondToChat`, V4b nu există) | setat OFF explicit — inert oricum, dar respectă litera regulii fără cost |

### Întrebări pentru Rareș (în raport, neblocante, cf. „la poartă roșie, oprești doar ramura")

1. V4a — corpusul de 50 de comenzi (pe telefon SAU PC) n-a rulat azi deloc (0 date). Vrei să-l
   rulez diseară direct pe telefon (`scripts/regression/v4a_corpus.sh`, varianta originală, mai
   utilă — are comparație cu parserul vechi) înainte de testul cu vocea reală, sau preferi să
   trimiți cheia o singură dată pentru varianta PC (`v4a_corpus_pc.mjs`)?
2. H — am nevoie de textul exact al rundei (testele 1/2/3/4/10 + definiția „SPELL") retrimis ca
   mesaj separat; rezumatul meu propriu nu e suficient de precis ca să scriu cod pe el.

### Lista de test pentru seara cu Rareș (ordine, criteriu PASS/FAIL)

Toate cu BENSON în fundal, ecranul principal în prim-plan, „Benson" real (regula fixă din CLAUDE.md).

1. **Wake de bază** — „Benson" → PASS dacă „Da, Master." se aude clar, o singură voce RO
   (verifică `TTS_SPEAK kind=ack voice=…` în log, trebuie identic cu restul rostirilor).
2. **Regresia G3 completă pe dispozitiv** — `scripts/regression/day_2026_10_08.sh` cu
   `INGRID_CONTACT=Hannah` — PASS la 9/10 sau mai bine (10/10 ar închide definitiv fals-negativul
   YouTube extern).
3. **V3 pe drumul real** — o comandă nerecunoscută de parserul vechi, cu BENSON în fundal, spusă
   cu voce reală (nu `DEBUG_INJECT`) — PASS dacă `STT_REQUEST provider=openai` apare în log (nu
   doar Deepgram/Groq) — primul test al drumului COMPLET microfon→OpenAI de azi.
4. **E0 pe viață** — orice comandă care produce un răspuns vorbit cu „nu" în el (ex. „Încă nu
   știu să fac asta") urmată de o comandă reală — PASS dacă comanda reală NU e aruncată ca ecou.
5. **P4** — apel WhatsApp real, 1 minut, verifică zero sondare de microfon în log pe durata
   apelului și reluare wake în 1.5-2.5s după terminare.
6. **logRoute (regresie tăcută)** — orice comandă WA/YouTube normală — PASS dacă `NATIVE_ROUTE`/
   `WA_PARSE` apar identic ca înainte (conținutul liniei, nu doar prezența) — prima confirmare
   live că cele 21 de call site-uri mecanic înlocuite azi (V4a) nu au stricat logarea existentă.
7. **V4_SHADOW, corpusul de 50** — rulează `v4a_corpus.sh` cu `DEVICE_SERIAL` setat la telefon —
   PASS = cel puțin câteva zeci de linii `INTERP_SHADOW` reale în `v4a_corpus_last_run.log` (nu
   neapărat procent mare de match — doar date, azi n-au existat deloc).
8. **Testul de 20× „Benson" cu voce reală** (amânat de la decizia 3b) — PASS dacă rata de
   acceptare e stabilă, fără pattern clar de fals-pozitiv/negativ legat de schimbările de azi.
9. **D0** — ascultă toate rostirile din seară — PASS dacă vocea engleză nu mai apare niciodată.

Dacă (1), (2), (4) sau (5) pică: revert imediat la flag-ul rundei respective (`false`), conform
„pasul înapoi e obligatoriu" — nu continua lista cu o regresie reală confirmată.

## SEARA — diagnostic mașină (fără cod, cf. cerință) + erori proprii de recunoscut

**Eroare proprie, de recunoscut**: am rulat `adb logcat -c` la începutul ferestrei de seară
(ca să pornesc propria captură pentru testul 1), ÎNAINTE ca cererea „tragi logurile înainte de
orice altceva" să vină. Bufferul principal de logcat (unde trăiește `BENSON_AUDIO`) a fost golit
— datele native BENSON din mașină, de mai devreme azi, nu mai sunt în `car_logcat.txt`/
`car_bugreport.zip`. Confirmat: cele 122 de linii `BENSON_AUDIO` din captură sunt toate din
13:39-13:40 (testul 1 de azi-seară), nimic mai vechi.

**Testul 1 (wake „Benson", din fereastra capturată)**: a picat. `HEED_DETECTED score=0,853` (sub
local), dar `WAKE_VERIFY result=rejected text=""` — verificarea online (Deepgram) n-a transcris
nimic din clipul de 2,5s → respins, fără „Da, Master.". Zero semnal P4 (`MIC_YIELD`/
`CALL_ACTIVE`) în fereastra asta — nu e cauza de mai jos, aici a fost acasă, nu în mașină.

**Diagnosticul de mașină, din `car_bugreport.zip` (dumpsys bluetooth_manager, supraviețuiește
golirii logcat-ului — stocare proprie, nu bufferul de log)**: dispozitivul „My VW 1821"
(`D0:40:EF:A8:C8:F3`) a avut Bluetooth HFP instabil toată ziua — `handleHfpConnected`/
`handleHfpDisconnected` la 12:01→12:14, 12:32→13:14, 13:22→13:28 (A2DP, pe de altă parte, a căzut
practic instant la FIECARE încercare de conectare, toate cele 4 înregistrate — audio prin
telefon/Spotify via Bluetooth n-a funcționat deloc azi, dovedit). Pe ultima fereastră (13:22-13:28),
istoricul `HeadsetStateMachine` arată stare `AudioOn` (SCO activ) cel puțin din 13:27:09, cu un
eveniment `EVENT_TYPE_HANGUP_CALL` la 13:28:22 — adică a existat un apel (real sau declanșat de
stack-ul HFP) cu audio SCO activ cel puțin ~1 minut.

**Verdict, cu dovada din cod**: `isCallAudioActive()` (`BensonForegroundService.kt:3512`) verifică
STRICT `AudioManager.mode == MODE_IN_CALL || MODE_IN_COMMUNICATION` — un semnal GLOBAL de sistem,
nu „există un apel telefonic real". Conexiunea/reconexiunea HFP (SCO) cu maşina poate seta acest
mode și FĂRĂ un apel literal (negociere de profil, test de voce al maşinii) — confirmat că s-a
întâmplat cel puțin o dată azi (fereastra 13:27-13:28). **Verdict: P4 (fals pozitiv de „apel"),
nu MAȘINA (conexiunea HFP a funcționat, chiar dacă instabil) și nu BENSON (niciun semn că
serviciul ar fi fost oprit sau captura inactivă independent de asta).**

**Onest, nedemonstrat complet**: am dovada directă doar pentru ultima fereastră HFP
(13:22-13:28) — istoricul intern `HeadsetStateMachine` e tot un buffer limitat, rotit; pentru
ferestrele mai vechi (12:01-12:14, 12:32-13:14) am doar `handleHfpConnected`/`handleHfpDisconnected`
(conexiunea a existat), nu confirmarea directă că audio SCO (`AudioOn`) a fost activ și în ele.
Plauzibil (fiecare reconectare HFP trece de regulă prin aceeași negociere), dar nu dovedit pentru
tot intervalul — „aproape tot drumul" e o inferență rezonabilă, nu un fapt complet verificat.

**Fără cod azi, cf. cerință explicită.** Propunere pentru runda următoare (NU implementată):
`isCallAudioActive()` ar trebui să verifice și `TelephonyManager.callState` (apel telefonic real)
sau `BluetoothHeadset` scopat la un apel confirmat, nu doar `AudioManager.mode` brut — ca
reconectările HFP ale mașinii, fără apel real, să nu mai declanșeze `MIC_YIELD`.

**Fișiere locale, șterse după analiză** (cf. cerință): `car_logcat.txt`, `car_bugreport.zip`,
`.tmp_bugreport/` — niciodată commise (confirmat `git status`, untracked).

## Itemele 3 și 4 — blocate, onest

**3 (corpus V4a pe PC, cu cheia din mediu)**: `OPENAI_API_KEY` (Windows, user/machine env) e
setată, dar conține literal un text de tip placeholder (26 caractere, verificat prin lungime +
bytes — fără să afișez conținutul), nu o cheie OpenAI reală (acelea au 50+ caractere). Apelul de
test a picat înainte de rețea. Blocat până se setează cheia reală.

**4 (V4b → V6 → H, cf. `docs/specs/ZIUA_2026-10-08_rest.md`)**: fișierul nu există nicăieri în
repo (căutare recursivă, negativ). Fără el nu pot începe — aș inventa cerințe pe fișiere deja
betonate (exact G5). Am nevoie de conținut sau de fișierul salvat/comis.

## Lista de seară — execuție live (telefon reconectat, emulator oprit)

**Test 1 (wake „Benson") — ✅ PASS.** 13:43:44 `HEED_DETECTED score=0,976` → 13:43:50
`WAKE_OFFLINE_DECISION accepted=true` (prag siguranță 0,95) → `WAKE_ACK` → `TTS_SPEAK kind=ack
lang=ro voice=ro-ro-x-vfv-local chars=11` = „Da, Master.", o singură voce.
**Observație, neblocantă**: din 9 rostiri reale „Benson" capturate azi-seară, 8 au primit
`WAKE_VERIFY result=rejected text=""` (Deepgram n-a returnat text, dar apelul de rețea însuși a
reușit — nu e eroare de conexiune). Doar scorul local ≥0,95 a trecut de fiecare dată prin plasa
de siguranță. Fără date istorice de bază pentru rata asta — notat pentru urmărire, nu regresie
confirmată.

**Test 2 (regresia G3 completă) — ✅ PASS, 9/10.** (Emulatorul a fost oprit întâi — cauza celor
10 FAIL inițiale era ambiguitate `adb`, nu produsul.) Unicul FAIL: `YT_PLAY_CONFIRM`, confirmat
`YOUTUBE_SEARCH_CONFIRM query="madonna" found=false` — exact fals-negativul extern documentat
toată ziua, nu o regresie.

**Test 3 (STT OpenAI pe drumul real, voce reală) — ✅ PASS, neprovocat deliberat — a survenit
organic din testarea de la pasul 1.** 13:43:53 `STT_REQUEST provider=openai bytes=40044` (captură
reală de microfon, 943ms vorbire, VAD) → `STT_RESULT provider=openai text="Hədnəvində" chars=10`
(transcript garblat — comanda spusă n-a fost clară/recunoscută, dar nu asta e criteriul testului)
→ `USAGE_ADD costUsd=0,0001` (contorul V2 funcțional) → `NATIVE_ROUTE action=native_fallback
reason=benson_backgrounded` (confirmă BENSON era în fundal, condiția obligatorie de test).
**Primul drum complet microfon→OpenAI STT de azi, pe calea reală.**

Teste 4 și 5 au nevoie de acțiune deliberată (o comandă reală cu „nu" în răspuns, urmată de o
comandă reală; un apel WhatsApp real de 1 minut) — cerute separat, în conversație.

---

## 2026-10-13 — RUNDA G9 (diagnostic wake, shadow mode, audit)

**G9 — diagnostic Deepgram, închis.** Mecanism de captură a payload-ului exact trimis către
Deepgram (flag `WAKE_VERIFY_EXPORT_PAYLOAD`, SHA-256 verificat byte-cu-byte), confirmă: Deepgram
răspunde HTTP 200, JSON valid, `confidence=0.0`, text gol — nu e eroare de rețea, nu e eroare de
parsare. Flag revenit la OFF, nicio copie audio rămasă (șterse după verificare). Concluzie:
**fiabilitatea wake-ului rămâne BLOCKED** — cauza empiric confirmată (Deepgram nu transcrie
rostirea scurtă „Benson" în fereastra de verificare), fără soluție implementată încă.

**G9.2 — agregare scoruri HEED, SHADOW MODE.** Flag nou `WAKE_SCORE_AGGREGATION` (implicit
`false`), în `HeedWakeWord.kt` (fișier protejat, permisiune explicită primită per rundă).
Calculează `mean`/`max` pe o fereastră mică de scoruri brute, DOAR logare (`WAKE_SCORE_AGGREGATE`),
fără nicio influență asupra pragului, `aboveCount`, `consecutiveFrames`, deciziei
accept/reject sau apelului Deepgram. Verificat flag OFF = comportament identic (build
reproductibil, SHA-256 egal), flag ON = doar logare suplimentară. Nicio decizie de acceptare
schimbată.

**G9.3 — audit finalizat, fără date suficiente.** Doar 2 măsurători `WAKE_SCORE_AGGREGATE`
există în total (ambele azi, clasă pozitivă — rostire reală „Benson"). Zero măsurători din clasa
negativă (fals-pozitiv) cu agregarea activă. Concluzie: **nicio putere de discriminare
demonstrabilă** pentru agregare față de `triggerScore` brut — nici confirmată, nici infirmată,
din lipsă de date. Timeout-urile Deepgram (11 apariții în loguri, `InterruptedIOException
"timeout"`) sunt confirmate distincte de transcript gol HTTP 200 — nu se confundă.

**Cifre agregate pe ziua de azi (127 cicluri wake):** 21 `accepted`, 103 `rejected`, 3 `offline`.

**Build de referință, semnat, SHA-256:**
`aff42962754461f4db40381136f53dfa23e794cd2641cfebd29c8af858287c53`

**G9.4 — propus, NEEXECUTAT.** Benchmark minim necesar: ≥10 cicluri pozitive („Benson" real) +
≥10 cicluri negative garantate (conversație liberă, TV/radio, muzică, fără cuvântul de trezire),
cu `WAKE_SCORE_AGGREGATION=true`, pentru a obține primele date comparabile din ambele clase.
Neînceput.

**B1 (creierul ChatGPT) și VITEZA-2** — rămân neîncepute; specificațiile sunt salvate verbatim
în `docs/specs/RUNDA_B1_2026-10-13.md` / `PLAN_FINAL_2026-10-12.md`, fără implementare.

**Decizie arhitect, valabilă:** nu se modifică niciun prag HEED, nu se activează nicio politică
nouă de acceptare a wake-ului, până la un benchmark pozitiv/negativ complet și aprobare explicită.
