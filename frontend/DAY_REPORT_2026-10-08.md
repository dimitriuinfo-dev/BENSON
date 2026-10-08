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

## V — Vocea guvernată de ChatGPT
(în curs)
