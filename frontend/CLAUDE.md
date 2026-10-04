@AGENTS.md

<!-- Din vechiul CLAUDE.md: doar linia `@AGENTS.md` de mai sus, păstrată — importă regula
     "Expo HAS CHANGED: citește docs.expo.dev/versions/v54.0.0/ înainte de a scrie cod".
     Restul acestui fișier este setul de reguli permanente (2026-08-28). -->

# BENSON — reguli permanente

Aplicație Android (Expo SDK 54 / RN 0.81). Majordom vocal.
Testat pe OnePlus Nord 4, OxygenOS 15. Limba BENSON: română. Sistem: germană.

## BENSON 1.0 — lista închisă (04.10.2026, decizia Rareș, permanentă)

Nimic din afara acestei liste de 10 nu se lucrează până nu e toată verde. Orice cerere în afara
listei: notată în `BACKLOG.md`, niciodată implementată pe loc.

1. Wake din fundal → „Da, Master"
2. Deschide orice aplicație
3. YouTube — caută/pornește/pauză/play
4. Spotify — „pune X"
5. WhatsApp — apel/video/mesaj
6. Navigație — „du-mă la X"
7. Căutare pe internet via Perplexity/ChatGPT
8. Înapoi/acasă/curățenie după apel
9. Confirmare vocală a rezultatului și a eșecului
10. Vocea ChatGPT + conversație + înțelegerea formulărilor libere

„Terminat" = toate cele 10 trec 5/5 într-o sesiune, în două zile diferite.

**O rundă pe zi, din listă, în ordine. Fără build-uri în mașină** — build/instalare/test doar cu
mașina oprită sau acasă.

**Poarta de livrare**: înainte de orice APK dat lui Rareș, rulez prin DEBUG_INJECT toate
capabilitățile deja verzi din listă. Una căzută = APK nelivrat, repar întâi.

**Git — excepție scoped de la regula de mai jos „Nu rula git"**: am voie, și sunt OBLIGAT, să fac
`git add -u && git commit -m ...` după fiecare rundă verde din lista de mai sus. Nicio altă comandă
git (fără push, fără reset, fără checkout, fără tag-uri).

---

## Doctrina produsului — nu se negociază

1. **BENSON guvernează aplicațiile, nu le înlocuiește.** Apasă butoanele lor prin
   Accessibility. Nu deține datele lor.
2. **Fără integrări de date.** Fără agendă telefonică, fără citirea bazelor altor aplicații.
   Numele se introduc ca șir de căutare în aplicația țintă; ea rezolvă.
3. **Fără plăți.** BENSON nu pregătește și nu finalizează nicio tranzacție.
4. **Memoria ține fapte, niciodată reguli despre BENSON.** Configurarea trăiește doar în
   Settings. Ce are un stăpân (calendarul) se consultă, nu se copiază.
5. **Tăcerea e implicită.** BENSON nu deschide niciodată conversația. Recunoaștere eșuată =
   zero reacție.
6. **Confirmation Gate înaintea oricărei acțiuni.** Creierul propune, nu execută.
7. **BENSON execută, nu interpretează.** Comenzile și mesajele merg pe drumul determinist: ce
   spune Rareș, aia se face, exact. Creierul (LLM) nu reformulează, nu ghicește intenții și nu stă
   pe drumul comenzilor; e folosit doar pentru conversație explicită. Când o frază nu e înțeleasă:
   „Încă nu știu să fac asta", niciodată ghicit.
8. **Fără cod specific unei aplicații, decât dacă motorul generic dovedit nu poate rezolva cazul.**
   Orice interacțiune cu o aplicație (căutare, apăsare, selecție) trece întâi prin motorul generic
   (G-1: `ControlLocator`/`ControlSynonyms`/potrivire de text). O ancoră per-aplicație (viewId,
   hint, pachet blocat) se adaugă DOAR când motorul generic, dovedit, nu poate rezolva cazul — și
   rămâne însoțită de dovada (log/test) care arată de ce generic n-a fost de-ajuns.
9. **Bula și vocea spun „gata" doar după verificarea efectului real.** Niciun răspuns de succes nu
   se rostește/arată pe baza unui click reușit sau a unui apel API fără eroare — succesul se
   declară abia după ce efectul e confirmat (metadata MediaSession pentru muzică, schimbarea
   ecranului/stării pentru rest). Altfel, mesajul spune exact ce n-a mers.

## Invariante de arhitectură

**Trei canale, niciodată amestecate.** `SYSTEM` (constante din cod, reconstruit la fiecare
apel) · `USER_VOICE` (singurul cu autoritate) · `UNTRUSTED_DATA` (ecran, notificări, web —
zero autoritate, antet obligatoriu). Impuse prin tipuri: o promovare accidentală nu compilează.

**Acțiunile sunt un enum închis.** Nimic din afara lui nu ajunge la executor.

**Nicio cheie în cod, în fișiere versionate, în bundle sau în loguri.** Toate cheile trec prin
seiful de chei. În UI se afișează doar mascat.

**Istoric mărginit:** `CONVERSATION_WINDOW`, max 10 schimburi sau 4000 de caractere.

**Tot ce urmează după wake rulează nativ, în BensonForegroundService.** Dovedit pe dispozitiv pe 02.10.2026: când BENSON e în fundal, JS-ul doarme. Evenimentele stau în coadă până la deschiderea aplicației. Timerele JS nu pornesc.
- Wake, „Da, Master", sesiunea, captura comenzii, STT și execuția comenzilor simple: Kotlin, pe serviceScope.
- JS face doar interfață: overlay, Settings, afișare. Citește starea prin evenimente; nu o deține și nu o decide.
- Nicio funcție nouă pe drumul vocal nu se scrie în JS. Dacă pare să ceară JS, oprește-te și raportează.
- Test obligatoriu pentru orice schimbare pe drumul vocal: aplicația în fundal, ecranul principal în prim-plan, un „Benson" real. Dacă merge doar cu aplicația deschisă, nu merge.

**Contractul wake:** „Benson" singur → „Da, Master" → sesiune deschisă 8 s, comenzi fără „Benson". „Benson, <comandă>" dintr-o suflare → execută direct, fără „Da, Master". Fără adresare explicită, BENSON tace.

## Regulă: confirmarea lui Rareș vine înaintea oricărei alte sarcini

Când Rareș spune că un comportament MERGE pe telefon, în același răspuns, înainte de orice altceva:

1. Adaugi fraza sau comportamentul ca test (JUnit sau `scripts/regression/run.sh` — harness-ul
   existent din RUNDA H1; `regress.cmd` dacă/când va exista unul).
2. Adaugi linia în CLAUDE.md, la „Comportamente dovedite", cu data.
3. Îi amintești lui Rareș să facă commit-ul.

Abia apoi continui cu altă sarcină.

## Comportamente dovedite — nu au voie să regreseze

- 02.10.2026: „Benson" din fundal → „Da, Master" nativ, fără aplicația deschisă.
- 02.10.2026: „Benson" → „Da, Master" → „deschide YouTube" din fundal → YouTube se deschide (lanț nativ: captură → STT → APP_LAUNCH).
- 03.10.2026: „Benson, scrie-i lui Hannah" (fără text) → dezambiguare numerotată rostită → „Ce să-i scriu?" → text captat → „Îi scriu lui Hannah: «…». Trimit?" → „da" → mesaj scris ȘI trimis real pe WhatsApp, verificat (`WA_WRITE_SENT_VERIFIED present=true`, `WA_INTENT result=ok`). Lanț nativ complet: parser (`BensonForegroundService.tryNativeWhatsApp`) → `ContactsContract` → `BensonAccessibilityService.runWhatsAppOpenConversationType`/`pressWhatsAppSendVerified`. Dovedit pe log, build `a943084ae18b902705b9e87204e29552f90196dbb4fda79e461bf5889b8f8a58`.
- 03.10.2026: RUNDA WA_VISIBLE_DRAFT — draftul se scrie în WhatsApp (vizibil, contact + text pe ecran) ÎNAINTE de „Trimit?", nu după „da". „Benson, scrie-i lui Hana un mesaj" (alias deja învățat, fără listă) → `WA_RESOLVE via_alias=true` → WhatsApp deschis, header verificat „HANNAH" → text captat → draft scris → „Trimit?" (acum cu ecranul vizibil) → „da" → `WA_WRITE_SENT_VERIFIED present=true`, `WA_INTENT result=ok`. Dovedit pe log complet, build `28f6da85c6dfb04b40cb222ae4f4cc284aa2f3faa4777e5179afe9147c143cdd`.
- 03.10.2026: CONTACT_ALIAS_LEARN — la dezambiguare numerotată („Am găsit mai mulți... Pe care?"), alegerea prin număr salvează local forma rostită → `lookupKey` (`CONTACT_ALIAS action=save`). Data următoare, aceeași formă rostită sare direct peste listă (`via_alias=true`), confirmarea „Trimit?" rămâne obligatorie. Dovedit pe log, aceeași sesiune, același build.
- 03.10.2026: FIX_WA_DECLENSION_1 — forme declinate („Hanei", dativ) rezolvă corect la contactul de bază („Hannah") prin potrivire pe variantă cu sufix românesc de dativ/genitiv decupat, nu doar pe cuvântul întreg. Dovedit prin JUnit (`WaNameMatcherTest`); cazul concret de regresie („chosen=A", contact greșit) nu a mai fost reprodus după fix.
  Cunoscut, neconsiderat regresie: pronunția TTS a numelor nefamiliare (ex. „Hannah" silabisit litere) nu e corectă implicit — necesită „Se pronunță X" (TTS_NAME_PRONUNCE) o dată per contact.
- 03.10.2026: APEL WHATSAPP cu telefonul blocat/stins + „sună LA X" — „Benson, sună la mama pe WhatsApp" din fundal, telefon blocat → ecranul se trezește (`wakeScreen()`, reutilizat de pe drumul vechi de wake) → WhatsApp ajunge real în prim-plan (`WA_NATIVE_PACKAGE found=true`, anterior `found=false` pe acest bug) → contact verificat, apel apăsat, verificat activ (`WA_NATIVE_CALL_VERIFY success=true screen=true nameMatch=true`, `WA_CALL_STATE state=CALL_ACTIVE`). Fixuri: `FIX_WA_SCREEN_LOCKED_1` (ecran) + `FIX_WA_CALL_PREPOSITION_1` („la" pe lângă „pe" în `WA_CALL_PATTERN`) — fără ele, comanda fie nu pornea vizibil, fie scăpa complet spre JS adormit. Dovedit pe log complet, build `7f4ef2bafdb20178e51da5222552b8748582f82ea0294c2847b9e6007cc66a3c`.

- 03.10.2026: RUNDA WA-4 (VIDEOCALL WHATSAPP) — „Benson, video cu Hannah" pornește un apel video real, verificat (confirmat pe telefon de Rareș). Refolosit 1:1 drumul de apel betonat (parser → resolver + alias → confirmare → intent), zero cod paralel; singura diferență e mimetype-ul `video.call`. `WaCallVideoMatcher` (nou, pur) clasifică video ÎNAINTE de apel, ca o frază cu „video" să nu corupă numele unui apel normal; „sună-o pe X" fără „video" rămâne apel vocal. Contact fără rând video → „X nu are video pe WhatsApp. O sun normal?" → „da" execută direct apelul vocal. Build `6f6d57b43f7259e680deeaaed634f97a8ba5ab4a51bee9337f0ffe2e2b12be42`.
- 03.10.2026: RUNDA S-1 TASK 1 (CĂUTARE APLICAȚII DUPĂ CATEGORIE) — „Benson, caută aplicația de parcare" din fundal → `NATIVE_ROUTE action=app_category_search target="parcare"` (dicționar mic de categorie) → mai multe instalate → listă numerotată rostită → „2" → `APP_LAUNCH pkg=de.swm.parken.handyparken ok=true`. Verificat pe același build, în aceeași sesiune, că „Benson, video cu Hannah" (WA-4) nu a regresat (`WA_NATIVE_CALL_VERIFY success=true nameMatch=true`, `WA_CALL_STATE state=CALL_ACTIVE`). Parsing pur, JUnit (`AppCategoryMatcherTest`); lookup-ul de aplicații instalate rămâne în `BensonForegroundService` (Context-dependent, netestabil JUnit). Build `7430659a16ac8225d84c2d323384b6e8dae520a8333e926203c45bc4ecfcd8bf`.
- 03.10.2026: RUNDA S-1 TASK 2 (CĂUTARE GENERALĂ prin Google) — „Benson, caută farmacie deschisă" / „caută cea mai apropiată pizzerie" din fundal → `NATIVE_ROUTE action=web_search target="..."` → browser deschis pe Google, rezultat confirmat de Rareș („perfect"). Prima încercare a fost lentă/incompletă din cauza conexiunii, nu a parserului — reîncercarea a confirmat. `GeneralSearchMatcher` (pur, JUnit) exclude explicit „pe hartă/unde e/du-mă la/YouTube/Spotify" — calea de navigație rămâne neatinsă. Build `7430659a16ac8225d84c2d323384b6e8dae520a8333e926203c45bc4ecfcd8bf`.
- 03.10.2026: regresie verificată — „Benson, caută Madonna pe YouTube" din fundal, pe același build cu Task 1 + căutare generală adăugate, rutează identic ca înainte (`NATIVE_ROUTE action=yt_search target="madonna"` → `APP_LAUNCH pkg=com.google.android.youtube ok=true`); tiparul explicit „pe YouTube" e verificat în lanț înaintea căutării generale, nu e interceptat de ea. Build `7430659a16ac8225d84c2d323384b6e8dae520a8333e926203c45bc4ecfcd8bf`.
- 03.10.2026: FIX_WA_STALE_CHAT_SCREEN_1 — „Benson, video cu baby" nu făcea nimic (`WA_CHATS_TAB state=inactive` → `state=select ok=false` → `WA_NATIVE_FAIL stage=SEARCH_HEADER_NOT_RECOVERED`), pentru că WhatsApp rămăsese pe ecranul de conversație cu Hannah (de la apelul video precedent, fără bară de navigare), iar pasul de „ieși din conversație" exista deja în `ensureWhatsAppChatsSearchAvailable()` dar era AMPLASAT DUPĂ verificarea tab-ului Chats, care pica și ieșea prima. Mutat înainte — zero logică nouă, doar ordine. Reconfirmat pe log, build nou: `WA_CHATS_TAB state=active` → `WA_NATIVE_CALL_VERIFY success=true name="Baby" nameMatch=true` → `CALL_ACTIVE`.
- 03.10.2026: FIX_WA_EMOJI_STRIP_1 — nume de contact cu emoji/simboluri (ex. „Baby ❤️") se normalizează la potrivire pe ambele părți (`TextNormalization.stripSymbolsAndEmoji`, nou, pur, cablat în `normalizeForMatch`). Dovedit prin JUnit (`TextNormalizationTest`, `WaNameMatcherTest.emojiInContactNameDoesNotBreakExactMatch`); nu era o regresie observată pe dispozitiv, dar risca să depindă de poziția emoji-ului față de nume (startsWith/contains "din noroc"). Build `75efe92582079a2a8a7d9891faf4375b87435586ab191281df8dd6c7255b79fb`.

- 04.10.2026: RUNDA G-1 extins (simboluri: triunghi/pătrat/bare paralele, seek 10s, săgeți) + două bug-uri reale găsite și reparate prin testare DEBUG_INJECT pe dispozitiv (nu ghicite): FIX_G1_STALE_FOREGROUND_1 — `lastForegroundPackage` (actualizat doar la TYPE_WINDOW_STATE_CHANGED) rămânea învechit după o schimbare recentă de aplicație, făcând `tryContextSearch` ȘI `tryUniversalHand` să caute în aplicația GREȘITĂ („cauta madonna" imediat după „deschide spotify" căuta tot în YouTube); înlocuit cu `BensonAccessibilityService.currentForegroundPackageLive()` (citire directă din `rootInActiveWindow`, fără cache) la ambele puncte. FIX_G1_MEDIA_WRONG_APP_1 — „apasă pe play" pornea Spotify în loc de YouTube (MediaTransport alegea orice sesiune activă, nu pe cea a aplicației din prim-plan); acum filtrează întâi pe pachetul din prim-plan, cu fallback pe comportamentul vechi (fără filtru) când nu există o aplicație reală în prim-plan — testul vechi „pauză" cu BENSON în fundal rămâne neschimbat. Secvență completă confirmată pe dispozitiv, cu bula de dialog la fiecare pas: YouTube („caută madonna" → „caută vogue" → „apasă play" → „apasă pauză") și Spotify (același lanț, „caută justify my love"), toate corecte după fix. Build `f3a3b7f4e767d6a978c24a0291555216356f9c9d185e948443e7f14d5c017e9b`.
- 04.10.2026: RUNDA CAR-3a — `BensonCarAppService`/`Session`/`Screen` (modul nou `benson-car`, androidx.car.app 1.7.0 stabil, categorie IOT, `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` pentru uz personal). Compilează curat, teste unitare verzi (`CarStateMapperTest`). Nu e încă testat în mașină — proba CAR-2 și testul pe ecranul mașinii rămân de făcut.
- 04.10.2026: RUNDA MUSIC-2 — „pune X" în Spotify, pe doi pași: Stratul 1 (`MediaTransport.playFromSearch`, generic, orice aplicație cu MediaSession) → Stratul 2 (ecran, generic, `ControlLocator`/`ControlSynonyms` din G-1, fără liste per-aplicație) cu selectarea primului rezultat de tip text + un clic secundar de „play" (RO/DE/EN) dacă rezultatul era o colecție (album/remix/radio), nu o piesă directă. Verificare STRICTĂ: metadata MediaSession trebuie să SE SCHIMBE față de înainte ȘI să se potrivească cu interogarea (FIX_MUSIC2_STALE_METADATA_1 — fără asta, o interogare care conține numele artistului deja activ dădea fals pozitiv instant). Confirmat pe dispozitiv cel puțin o dată curat, cap-coadă: „pune justify my love" → `verified=true title="Justify My Love - Q-Sound Mix Version" artist="Madonna, Goh Hotoda, Shep Pettibone"`, piesă auzită, confirmat de Rareș.
  **Onest, neterminat**: pasul de activare a căutării Spotify (tap pe placeholder → câmp editabil real) e intermitent — a eșuat de mai multe ori chiar și cu timeout mărit la 3.5s (`no_input_found`), cauza exactă nedovedită încă. Selecția de candidat preferă adesea un album/EP/radio ("Justify My Love (Remixes)", "Vogue Radio") în locul piesei simple — nicio distincție de TIP rezultat încă. **Nu e un comportament garantat, e un prototip funcțional parțial dovedit** — nu trata ca betonat în sensul „5 din 5"; următoarea rundă pe asta ar trebui să atace fie fiabilitatea activării, fie distincția „e un cântec, nu o colecție".

- 04.10.2026: FIX_MEDIA_CTL_TRAILING_OBJECT_1 — „oprește muzica"/„stop muzica" (verb + obiect) cădea silențios: `MEDIA_CTL_PATTERN` cerea verbul SINGUR (`^...$`), fără loc pentru „muzica" după el. Obiectul e acum opțional. Confirmat pe dispozitiv, pe ambele aplicații: YouTube și Spotify, `NATIVE_ROUTE action=media_ctl target="pause"` → `ok=true`.
- 04.10.2026: YouTube — secvență completă verificată prin stare reală (nu doar succes API): căutare „madonna" → scroll ×2 (`CONTROL_ACT action=scroll_down ok=true`) → play (`dumpsys media_session state=PLAYING`) → stop după 20s (`state=PAUSED`) → play după 5s (`state=PLAYING`) → stop final (`state=PAUSED`). Build `702cf6151562a53900a579194824e46642fc28585541a0b63e5097c0d0547f68`.
- 04.10.2026: RUNDA MUSIC-2 continuare — trei fixuri reale găsite și reparate pe Spotify, niciunul încă suficient pentru poarta 5/5: FIX_MUSIC2_ACTIVATE_RETRY_1 (reîncercare unică a click-ului pe lupă dacă `waitForNode` expiră — a eliminat `no_input_found` repetat azi), FIX_MUSIC2_PLAY_CONTROL_TARGET_1 (dintre toate controalele „play" găsite pe ecran, alege cel mai MARE — mini-player-ul persistent al Spotify, `play_pause_button` 120×120px, nu mai e confundat cu butonul paginii; elimina pornirea unei piese complet neînrudite), FIX_MUSIC2_PLAY_CONTROL_SETTLE_1 (400ms după primul match, înainte de a colecta toate candidatele — primul cadru poate avea doar mini-player-ul vizibil). `playFromSearch` (Stratul 1) testat izolat cu fereastră lărgită la 6s: NU pornește nimic pe acest Spotify — rămâne calea de ecran ca principală, nu fallback.
  **Onest, neterminat**: după fixuri, artistul e mereu corect (Madonna), dar piesa pornită e alta din colecție („Vogue", nu „Justify My Love" cerut), iar poziția de redare CREȘTE continuu între încercări — butonul mare al paginii de colecție nu pornește colecția de la zero, reia o sesiune globală deja activă. Următorul pas: intrarea în colecție și selecția explicită a rândului cu titlul exact al piesei, nu butonul de Play al colecției.
- 04.10.2026: TESTUL DE TEREN ÎN MAȘINĂ (autostradă) — FIX_SINGLE_TRANSCRIPTION_1 (js_handoff folosea calea audio originală, nu textul deja cunoscut din WAKE_VERIFY — JS re-transcria același clip și scotea alt rezultat; eliminat, ambele căi folosesc acum `command`) și FIX_CAR1_CONNECTION_DETECT_1 (`androidx.car.app.connection.CarConnection`, nu `UiModeManager` — acesta rămânea `false` cât Android Auto proiecta real) confirmate pe dispozitiv, pe autostradă: „Benson, pune Madonna pe Spotify" execută direct, fără fereastră nouă; „Da, Master" auzit prin boxele mașinii. CAR-3a: iconița nu apărea în Android Auto din `automotive_app_desc.xml` fără `<uses name="template"/>` — fixat (`FIX_CAR3A_TEMPLATE_USES_1`), dar încă o a doua respingere („failed all other checks") nerezolvată.
  **Offline în mașină: scorul HEED nu separă „Benson" de fals (falsele ating 0,960, cele reale 0,917–0,99 — se suprapun), iar fără rețea nici comanda nu poate fi transcrisă (Deepgram e singura cale utilizabilă pe română). Offline = tăcere.** Încercat și REVERTIT complet azi (FIX_WAKE_OFFLINE_CAR_ACCEPT_1, prag 0,93, doar în CAR_MODE) — dovadă din chiar acest test: trei scoruri false de 0,960/0,960/0,921 din conversație/radio normal, respinse corect doar de verificarea online; un prag bazat pe scor singur le-ar fi acceptat offline. Nu reîncerca varianta „prag + scor" fără un verificator care cunoaște vocea lui Rareș.

Regulă: orice rundă care atinge drumul vocal re-rulează aceste două teste la final, cu BENSON în fundal. Dacă unul cade, runda e respinsă, oricât de bine merge restul.

---

## Reguli de execuție

- **Nu rula `git`**, cu o excepție scoped (vezi „BENSON 1.0 — lista închisă" de la începutul fișierului, 04.10.2026): `git add -u && git commit -m ...` e OBLIGATORIU după fiecare rundă verde din lista de 10. Nicio altă comandă git — fără push, fără reset, fără checkout, fără tag-uri.
- **Nu rula `expo prebuild`.** A șters deja modele și semnături o dată.
- **Fără `setx`.** Fără modificări permanente de PATH.
- **Fără SDK-uri de furnizor** dacă `fetch` e suficient. Endpointuri compatibile OpenAI.
- **Citește înainte să scrii.** Verifică semnăturile în `node_modules/`, niciodată din memorie.
  Dacă o opțiune nu există, raportează — nu inventa nume de câmpuri.
- **După orice schimbare a exporturilor JS ale unui modul local din `modules/` (index.js/index.d.ts),
  rulează `npm install` înainte de `tsc`.** Pe Windows, `npm` COPIE dependențele `file:` în
  `node_modules/`, nu le leagă simbolic — o schimbare în `modules/<nume>/index.d.ts` nu ajunge
  singură la `node_modules/<nume>/index.d.ts`. Dovedit 03.10.2026: `tsc` raporta „has no exported
  member" pentru `setBrainCredentials`, deja exportat în sursă, din cauza copiei vechi.
  Automatizat: `npm run sync-modules` (`scripts/sync-local-modules.cjs`) recopiază rapid doar
  `index.js`/`index.d.ts`/`package.json` pentru fiecare dependență `file:` — rulează și singur, ca
  `postinstall`, la fiecare `npm install`.

## Fișiere protejate — nu se ating fără permisiune explicită, per rundă

```
android/**        plugins/**        modules/**
whisper-models/**  porcupine-model/**
lib/tools/whatsappTool.ts
lib/agents/missionValidator.ts
lib/agents/missionExecutor.ts
modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice/HeedWakeWord.kt
```

`android/` e în `.gitignore` și se regenerează. `whisper-models/` și `porcupine-model/` există
doar pe acest disc — nu sunt versionate nicăieri.

### WAKE-UL E PROTEJAT (04.10.2026, decizia Rareș, permanent)

**Modelul**: `modules/benson-foreground-service/android/src/main/assets/wakeword/heed_candidate.onnx`
— sha256 `57e18401d6c146907ee669b17b82c79fed1c2d94c593ff949f6f36fe591b4779`. Intră în git (deja
tracked). Un test la build verifică acest hash; dacă diferă, build-ul pică.

**Parametrii** (schimbarea lor cere aprobarea explicită a lui Rareș, numită în prompt-ul rundei):
- Pragul HEED: `0.789` (`heed_candidate.json`, câmpul `threshold`; `consecutive_frames=2`,
  `refractory_seconds=0.7`, `energy_gate.rms_threshold_dbfs=-55.0`).
- Fereastra anti-ecou (`SelfTtsGuard.GUARD_MS`): `1000` ms — cât TTS-ul propriu vorbește, plus 1s
  după, HEED ignoră o detecție (altfel se aude pe sine zicând „Da, Master" și se redeclanșează).
- Ack-ul nativ: textul exact `"Da, Master."`, rostit din `BensonForegroundService.speakNativeAckThenCapture`.

**Comportamentul**: „Benson" din fundal → „Da, Master" nativ, fără aplicația deschisă — deja în
„Comportamente dovedite" (02.10.2026). Devine test obligatoriu la finalul ORICĂREI runde, nu doar
al celor de pe drumul vocal — dacă pică, runda e respinsă, oricât de bine merge restul.

`HeedWakeWord.kt` și blocul de wake/ack din `BensonForegroundService.kt`
(`onHeedWakeDetected`/`speakNativeAckThenCapture`/`SelfTtsGuard`-ul lor): fișiere protejate — se
ating doar cu permisiune explicită, numită în prompt, per rundă.

---

## Protocolul unei runde

1. Scope lock explicit, cu lista fișierelor permise **și** a celor interzise.
2. Dacă o sarcină cere un fișier interzis: **oprește-te și raportează**, nu improviza.
3. Verificare, în ordine: `npx tsc --noEmit` (0 erori) → `gradlew assembleRelease`
   (BUILD SUCCESSFUL, certificat `CN=BENSON, O=TOKKO`).
4. Raport: fișier cu fișier, câte linii. Orice ieșire din scope lock, în prima linie, bold.
5. Un singur tip de schimbare per rundă. Nu amesteca „adaugă funcție" cu „scoate funcție".

## Convenții de logare

Tag unic: `BENSON_AUDIO`. Trei linii obligatorii la fiecare comandă:

```
BRAIN_INTENT   raw="<ce a auzit>" action=… params=…
CANONICAL      text="<comanda reconstruită>"
PARSE_RESULT   problemType=… params=…
```

Altele: `STT_REQUEST` · `STT_RESULT engine=…` · `STT_FALLBACK reason=…` ·
`SANITY_CHECK source=parser|brain verdict=…` · `SPEAK_SUPPRESSED reason=…` ·
`MEMORY_REJECTED reason=…` · `BRAIN_REJECTED reason=…`

Captura pe dispozitiv cere toate trei tag-urile împreună:

```
adb logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S
```

---

## Stare cunoscută

- **Funcționează, testat pe dispozitiv:** navigația Waze cu governance, Confirmation Gate,
  intrarea prin text cap-coadă, build-ul release semnat, persistența la prebuild.
- **Parserul determinist e corect** — verificat separat. Când greșește, cauza e intrarea.
- **whisper local (`ggml-base`) e insuficient pentru română.** Halucinează boilerplate
  englezesc din zgomot. Rămâne doar ca rezervă offline; ruta principală e Groq.
- **Porcupine e abandonat** — nivelul gratuit a fost refuzat. Wake word-ul rămâne deschis.

---

# Reguli permanente contra regresiilor

Se aplică la FIECARE rundă, fără excepție. (Sursă: `BENSON_REGULI_REGRESIE.md`, 29.08.2026.)

## 1. Lista de comportamente dovedite

Acestea au funcționat pe dispozitiv, cu dovadă. Nicio rundă nu are voie să le strice.

| Comportament | Dovedit |
|---|---|
| Navigație Waze cu destinație corectă | de mai multe ori, 29.08 |
| Deschidere aplicație după nume | 29.08 |
| Propunere pentru cerere generică („o aplicație de radio") | 29.08 |
| Conversație liberă cu răspuns rostit | 29.08 |
| Apel WhatsApp cap-coadă | 29.08, ora 13:24 |
| Microfon închis cât vorbește BENSON (fără ecou) | 29.08, verificat în log |
| Index de aplicații 274 | 29.08 |
| Microfonul se auto-repară după o acțiune care lansează altă aplicație (C1+C2) — revenire fără atingerea medalionului | 31.08, 7 comenzi / 0 atingeri în log |
| „Benson, deschide X" (nume ambiguu/inexact ca aplicație) → un singur candidat instalat → BENSON propune ("Am găsit X. O deschid?") → confirmare vocală "da" → deschidere verificată pe foreground | 19.09, log complet WAKE_DETECT→APP_MATCH→CONFIRM_LISTEN_RESULT=YES→EXEC_TRACE_FOREGROUND_VERIFY, calea live (JS activ) |
| Wake „Benson" în fundal/ecran stins, inclusiv cu altă aplicație în prim-plan și BENSON omorât de OS — livrare live, ~9–15ms latență | 20.09, build 16:35:55, detalii în `docs/device-tests/2026-09-20-results.md` |
| „Benson, deschide Calculatorul" din fundal, fără atingerea lui BENSON, inclusiv dezambiguizare pe două runde | 20.09, build 16:35:55 și 16:51:03, detalii în `docs/device-tests/2026-09-20-results.md` |
| „Benson, sun-o pe mama pe WhatsApp" din fundal → confirmare vocală → apel verificat activ (`CALL_ACTIVE`, nameMatch=true) | 20.09, build 16:51:03, ~21s total, detalii în `docs/device-tests/2026-09-20-results.md`. Închiderea apelului prin voce e raportată de utilizator, NEconfirmată încă în log. |

**Înainte de a declara o rundă încheiată, spune explicit care dintre acestea ar putea fi
afectate de schimbare și de ce crezi că nu sunt.** Dacă nu ești sigur, nu declara încheiat.

## 2. Nu înlocui ce funcționează

O implementare care a funcționat pe dispozitiv **nu se rescrie**. Se adaugă una nouă alături, în
spatele unei constante, iar cea veche rămâne implicită până când cea nouă trece testul de 5 din 5
pe dispozitiv.

Dacă o rundă cere „rescrie X", și X a funcționat, **oprește-te și spune-o.** Propune varianta
aditivă în loc.

## 3. Pasul înapoi e obligatoriu, nu opțional

Dacă după o rundă un comportament din listă se comportă mai prost decât înainte:

- **revino imediat la varianta anterioară**, fără să aștepți confirmare
- spune ce ai revenit și de ce
- abia apoi propune o cale nouă

Nu aștepta ca utilizatorul să observe.

## 4. Ai voie și ești obligat să contrazici

Dacă o instrucțiune pare greșită, riscantă sau construită pe o presupunere pe care codul o infirmă:

- **nu o executa**
- spune ce e greșit, cu dovada din cod sau din log
- propune ce ai face în loc, și de ce e mai bun

O rundă executată orbește care strică ceva e mai rea decât o rundă refuzată cu motiv.

## 5. Cauza înainte de fix

Nicio schimbare fără cauză dovedită în cod sau în log. „Probabil e X" nu e cauză. Dacă nu ai
dovada, prima livrare a rundei e diagnosticul, nu codul.

## 6. O rundă = un tip de schimbare

Nu amesteca „adaugă funcție" cu „scoate funcție" cu „rescrie". Dacă ceva cade, trebuie să se
poată spune care schimbare a fost.

## 7. Fiecare rundă are o constantă de revert

O singură constantă, sau un grup mic, care readuce comportamentul dinainte. Scrie-o în raport.

## REGULI DE EFICIENȚĂ

### Citește înainte să scrii
- Nu citi fișiere întregi dacă ai nevoie de o funcție. Folosește grep/ripgrep.
- Nu citi `node_modules/` decât tipările exacte necesare.
- Nu reciti fișiere pe care le-ai citit deja în aceeași sesiune.

### Scrie puțin, scrie corect
- O singură modificare per fișier, apoi verifică. Nu rescrie 500 de linii și apoi descoperă că nu compilează.
- Nu duplica funcții. Dacă există deja, importă.
- Nu adăuga cod defensiv "pentru orice eventualitate". Adaugă doar ce rezolvă cauza dovedită.

### Nu explica, nu povestește
- Zero comentarii de tip "// This function handles the..." — codul se explică singur.
- Raportul conține: ce ai schimbat, unde, câte linii, rezultatul build. Atât.
- Nu rescrie rapoartele rundelor anterioare.

### Build
- `npx tsc --noEmit` ÎNTÂI, înainte de `gradlew`. TypeScript e 5 secunde, Gradle e 1 minut.
- Dacă tsc eșuează, nu rula Gradle — repară întâi.
- Nu rula `gradlew clean`. Build incremental e de 10x mai rapid.
- Nu rula `expo prebuild` niciodată.

### Scope
- Citește scope lock-ul ÎNAINTE de orice altceva.
- Dacă un fișier nu e în scope, nu-l deschide, nu-l citi, nu-l atinge.
- Dacă ai nevoie de un fișier interzis, OPREȘTE-TE și raportează. Nu improviza.

### Audit read-only
- Când runda spune "read-only", nu creezi fișiere noi în afara raportului.
- Nu "pregăti" cod pentru runda viitoare.
- Nu refactorizezi "ca să fie mai curat".

### Shell
- Nu rula comenzi inutile. `ls`, `cat`, `find` — doar când chiar ai nevoie de informație.
- O comandă `grep` bine formulată înlocuiește citirea a 10 fișiere.
- Nu instala pachete noi dacă nu e explicit cerut.

### Tokeni
- Răspunsul tău complet — cod + raport — trebuie să fie sub 400 de linii.
- Dacă depășești, ai făcut prea mult sau ai explicat prea mult.
- Codul nou per rundă: sub 200 de linii net. Dacă e mai mult, ai schimbat scope-ul.

