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
