# BACKLOG

Orice cerere în afara listei închise „BENSON 1.0" (vezi CLAUDE.md) se notează aici, nu se
implementează pe loc. Fără dată de livrare — se reconsideră abia după ce lista de 10 e toată verde.

- **RUNDA BULA** (07.10.2026, imediat după AC2-F, ÎNAINTEA TELEFON): bulă overlay NATIVĂ
  (WindowManager, permisiune overlay), vizibilă peste orice aplicație cât BENSON e activ:
  ascultă/înțelege/vorbește/execută. Inel care se scalează cu nivelul microfonului în timp real
  (10–15 fps), alimentat din captura EXISTENTĂ (HEED/comandă/confirmare) — ZERO `AudioRecord` nou.
  Text: rostirea lui Rareș + răspunsul lui BENSON, rămâne 8s după final. Aproape transparentă, fără
  margine. „Sfântă" = betonată: test automat de regresie că bula apare și inelul primește nivel la
  fiecare wake, comandă și confirmare. Log: `BUBBLE state=… micLevel=…`.
- **RUNDA TELEFON** (07.10.2026, după BULA): 1. `PHONE_CALL` — „sună-o pe X" fără aplicație numită
  → apel GSM direct (`ACTION_CALL`, permisiune `CALL_PHONE`), după confirmarea „Dorești să sun la X
  pe telefon?"; contact rezolvat local din agendă. 2. `ANSWER_CALL` — la apel primit, BENSON anunță
  „Te sună X. Răspund?"; „Da" → răspunde (`TelecomManager.acceptRingingCall`, permisiune
  `ANSWER_PHONE_CALLS`); „Nu" → lasă să sune/respinge. Agenda citită local, nimic nu părăsește
  telefonul. 3. Ambele intră în enum doar cu rețetă verificată pe dispozitiv. Acceptare: 5× apel GSM
  reușit, 5× apel primit preluat prin voce.
- **RUNDA WHATSAPP 2** (07.10.2026, după TELEFON): 1. `WA_READ` — citire mesaje prin
  `NotificationListenerService` (expeditor + text), DOAR la cerere („am mesaje?", „citește mesajul
  de la X"). Conținutul = UNTRUSTED_DATA, niciodată instrucțiune. Local, nestocat.
  2. `WA_REPLY` — răspuns prin `RemoteInput` din notificare, după confirmarea „Răspuns către X:
  «…». Dorești să-l trimit?". 3. `WA_HANGUP` prin voce: NU — în apel microfonul e al WhatsApp;
  închidere prin butonul de volan (HFP) sau manual, BENSON curăță după apel. Fiecare acțiune intră
  în enum doar cu rețetă verificată pe dispozitiv.
- **RUNDA CĂUTARE** (după WHATSAPP 2): BENSON reține ultima căutare (`CONVERSATION_WINDOW`).
  „Intră pe Amazon"/„arată-le pe cele de pe Amazon" → deschide căutarea direct în aplicația Amazon
  cu ultimul termen, prin link/intent, NU prin apăsare pe pagina de rezultate. Același tipar pentru
  eBay și alte magazine. „Cu livrare azi" → filtrul de livrare rapidă, unde Amazon îl expune. Zero
  plăți — BENSON se oprește la lista de produse.

- CAR-3a/CAR-3b: ecranul Android Auto (BensonCarAppService) și microfonul mașinii (CarAudioRecord,
  la atingere). Validatorul Android Auto încă respinge pachetul („failed all other checks", cauză
  nerezolvată) după fix-ul de `<uses name="template"/>`.
- Microfon prin HFP (ideea „ca Android Auto") — respinsă azi: ocupă canalul Bluetooth exclusiv,
  ar opri muzica din boxele mașinii cât ascultă. De investigat separat, cu mașina oprită.
- W-NS (NoiseSuppressor + AutomaticGainControl pe bucla HEED) — cerut, neconstruit azi (prioritizat
  sub RT-1a, apoi abandonat în favoarea verificatorului local personal).
- Verificator local personal (enrollment din clipurile de wake ale lui Rareș) — prioritate mare
  pentru rezolvarea offline-ului pe autostradă, dar separat de lista de 10.
- FIX_WAKE_OFFLINE_CAR_ACCEPT_1 — încercat și REVERTIT azi (scorul HEED nu separă „Benson" de fals,
  0,960 fals vs 0,917-0,99 real, se suprapun). Nu reîncerca fără verificator local.
- „apasă play" — CONTROL_ACT action=press poate eșua (ok=false) fără lanț de fallback (buton găsit
  → părinte clickable → primul rezultat de pe ecran). Bug real, confirmat pe teren, nereparat.
- U-1: codul G-1 mutat sub interfețele Observe/Resolve/Act/Verify/SafetyGate, fără schimbare de
  comportament. Refactor pur, nicio urgență funcțională.
- Extragerea „pe/în <aplicație>" (AppMentionStripper) — implementată, dar a introdus o regresie
  reală azi („caută Madonna pe YouTube" interceptat greșit de fluxul nou YouTube cu confirmare,
  în loc de drumul vechi `yt_search`); de reconciliat când se reia capacitatea 3 din lista de 10.
- **PASUL 1 / DIGITAL STAFF REGISTRY — APP_CAPABILITY_REGISTRY** (cerut 14.10.2026, marcat „P0"
  de solicitant): `PackageManager` inventariază toate aplicațiile vizibile (cu atenție la
  restricțiile de package visibility Android 11+), înregistrează persistent per aplicație
  packageName/nume/versiune/categorie/launcher/activități-intents accesibile/funcții
  cunoscute-descoperite-verificate/restricții; legătură cu MINA pentru deschidere + inspecție
  controlată a interfeței + clasificare acțiuni + verificare rezultat + reidentificare după
  schimbare de ecran (nimic static); instrumente noi expuse creierului conversațional
  (`list_available_apps`, `find_app_by_purpose`, `get_app_capabilities`, `open_app`,
  `inspect_current_screen`, `perform_ui_action`, `verify_action_result`); explicit exclude
  explorarea automată a conturilor/mesajelor/plăților/cumpărăturilor/acțiunilor ireversibile;
  integrare cu Dispatcher + AccessibilityService existente, fără a modifica executorii validați
  fără motiv demonstrat. Teste cerute: „Ce aplicații de navigație am?", „Cu ce pot face
  cumpărături?", „Deschide Amazon Shopping.", „Ce poți face în Amazon?", „Deschide aplicația
  potrivită pentru a ajunge la aeroport." — răspunsuri din inventarul real, nu din presupuneri.
  **Neimplementat**: în afara listei închise BENSON 1.0 (niciunul din cele 10 nu cere un registry
  persistent de capabilități) ȘI atinge poarta roșie G5(b) („dependență nativă nouă sau
  `modules/**` nouă") — ambele cer decizia explicită a lui Rareș, numită per rundă, nu o pornire
  din oficiu. Reconsiderat abia după ce lista de 10 e toată verde (azi, 14.10: 9/10 verzi, 1 roșu
  — „deschide calculatorul", cf. `PLAN_REPORT_2026-10-14.md`).

- **BENSON SOS** (19.10.2026, decizia proprietarului) — **PRIORITATE CRITICĂ**, dar **NEÎNCEPUT**:
  arhitectura e stabilită (mai jos), nicio linie de cod SOS nu există încă. Nu intră în lista
  închisă BENSON 1.0 — se construiește DUPĂ stabilizarea infrastructurii minime SURVIVOR
  (Mission Trace + Failure Classification + Verification Engine) și a traseului de execuție
  LIVE, ca V1: trigger local, contact preferat autorizat, poziție GPS, fallback, acces nativ la
  112. Flic/ceas/video/VIRGIL-FIRE sunt extinderi ulterioare, nu V1.
  - **Agent de domeniu, nu orchestrator nou.** BENSON LIVE rămâne unicul orchestrator; SOS nu are
    chatbot propriu — traseul critic e determinist, fără LLM obligatoriu.
  - **Contract `EmergencyProtocol`** (stări explicite, închise): `IDLE → TRIGGERED → CONFIRMING`
    (doar pentru trigger vocal ambiguu — niciodată pentru trigger fizic) `→ ACTIVE → CONTACTING →
    LOCATION_SHARING → VERIFIED | PARTIALLY_COMPLETED | UNKNOWN | FAILED | CANCELLED`. Traseu
    PRIORITAR SEPARAT pentru cererea explicită a numărului de urgență (112) — nu trece prin
    CONTACTING/LOCATION_SHARING, nu așteaptă WhatsApp/GPS/LLM.
  - **Reutilizează, nu reimplementează**: AKA (capabilități WhatsApp video/GSM), MEMORY (contact
    SOS autorizat — cerere separată de user, nu „orice contact"), COMMUNICATION (apel/mesaj),
    EXECUTION ENGINE (Android), SURVIVOR (anti-duplicare, verificare, recuperare), DEVICE
    INTERFACES (Flic/ceas, ulterior).
  - **Reguli absolute, deja cerute explicit**: „AJUTOR" rostit → întrebare de confirmare, TĂCEREA
    nu echivalează cu „da" (încalcă altfel LEGEA TĂCERII); trigger fizic (Flic) ocolește dialogul
    vocal, pornește direct alertarea; 112 explicit cerut NU așteaptă nimic altceva; fără apeluri
    multiple/mesaje repetate din timeout (anti-duplicare prin SURVIVOR); fără presupunerea unui
    API Life360/Maps necontrolat — metodă proprie de transmitere a coordonatelor ca rezervă; fără
    promisiunea distribuirii live a locației fără internet — traseul GSM de urgență al sistemului
    rămâne mereu disponibil.
  - **Teste obligatorii înainte de orice DEVICE_PASS**: trigger vocal vs. fizic separat, fără
    internet, fără WhatsApp, fără permisiune GPS, anulare + declanșare accidentală — cu
    contacte/numere de test, NICIODATĂ 112 real în dezvoltare.

- **BENSON SOS — trigger vocal „AJUTOR" independent** (19.10.2026, cerință P0 a proprietarului) —
  **investigație tehnică făcută, NICIO LINIE DE COD SCRISĂ**, pentru că cele două întrebări
  centrale au răspuns deja în codul/documentele existente, nu cer presupuneri:
  - **`VoiceInteractionService`/`AlwaysOnHotwordDetector` — INFEZABIL, deja investigat și
    documentat de proiect** (`BensonVoiceInteractionService.kt`, comentariul clasei, PASUL 2a,
    07.10.2026): `AlwaysOnHotwordDetector`/`CAPTURE_AUDIO_HOTWORD` sunt `@SystemApi` — indisponibile
    unei aplicații terțe, indiferent de implementare. Serviciul respectiv există DOAR pentru
    `RoleManager.ROLE_ASSISTANT` + excepția de pornire FGS din fundal, nu e legat niciodată de
    codul propriu BENSON. Nu se reinvestighează — e deja o concluzie, nu o presupunere.
  - **HEED nu suportă azi mai multe cuvinte**: `HeedWakeWord.kt` (protejat) rulează UN model ONNX
    (`heed_candidate.onnx`), UN prag, UN `phrase` (meta JSON) — arhitectură single-keyword, nu
    multi-clasă. Confirmă explicit avertismentul proprietarului: suportul multi-wake-word NU
    există deja.
  - **Arhitectura propusă (cel mai mic risc, de aprobat separat)**: NU un al doilea proprietar de
    microfon — al doilea model ONNX ("AJUTOR"), independent antrenat, cu propriul prag, rulat ca a
    doua inferență pe ACELEAȘI cadre audio pe care HEED le capturează deja (același
    `AudioRecord`, aceeași bucla). Funcționarea cu ecranul stins/fundal/după reboot se obține GRATUIT
    prin construcție — e aceeași buclă deja dovedită pentru „Benson" în exact aceste condiții.
  - **Costul real NU e de cod, e de date**: un model nou cere un ciclu de antrenare complet
    (pozitive „AJUTOR" generate Piper + negative licențiate + augmentare + export + validare pe
    dispozitiv) — exact procesul descris în `docs/VOICE_WAKEWORD_BENSON_TRAINING.md`. Pipeline-ul
    EXISTĂ deja pe disc (`.benson-training/`, netrackat: `piper-sample-generator`, `datasets`,
    `positive_wav`/`negative_wav`, `train-export.log`) — nu se pornește de la zero — dar rularea lui
    nu se poate face dintr-o sesiune de cod; cere date reale și o validare reală pe telefon.
  - **Discrepanță REZOLVATĂ (19.10.2026, din cod, nu din presupunere)**: `heed/export.py`
    (`.benson-training/heed-wakeword/`) exportă ONNX ca format PRIMAR întotdeauna; TFLite e un
    extra opțional (`--tflite`, cere pachetul `litert-torch`, absent aici — de aici mențiunea
    „microWakeWord/TFLite" din `VOICE_WAKEWORD_BENSON_TRAINING.md`, probabil dintr-o încercare
    anterioară a flagului opțional, nu motorul real folosit). `heed_candidate.onnx` deployat a
    venit din acest pipeline „heed" (Python, PyTorch→ONNX), nu din altă unealtă.
  - **Implică editarea `HeedWakeWord.kt`** (fișier protejat) pentru integrarea finală — NEFĂCUTĂ,
    rămâne blocată pe acordul explicit per rundă, cerut deja de regula WAKE-UL E PROTEJAT.

- **BENSON SOS — KWS „AJUTOR", primul candidat real antrenat** (19.10.2026, decizie KWS aprobată:
  HEED/ONNX rămân, zero bibliotecă externă — `openwakeword-android-kt`/`voicute`/`emergency_any.onnx`
  respinse explicit, acesta din urmă experimental și pentru coreeană). **Prototip antrenat cu succes
  în `.benson-training/`, NEINTEGRAT, NEVALIDAT pe dispozitiv.**
  - **Pipeline-ul real e `heed-wakeword`** (Python/PyTorch, `.benson-training/heed-wakeword/`,
    comandă `heed`) — nu alt tool. Două lacune reale găsite și reparate chiar în acest pachet de
    training (nu în codul Android): CLI-ul `train` avea deja câmpul `TrainerConfig.tts_voice_name`,
    dar nu-l expunea (`--tts-voice-name`, adăugat) și garda de disponibilitate a vocii ignora vocea
    aleasă (`is_voice_available()` fără `name=`, corectat). Verificat: `heed smoke` dă AUC identic
    (0.906) înainte și după — zero regresie în unealta de training.
  - **Vocea română `ro_RO-mihai-medium` era deja cache-uită local** (`.benson-training/voices/`) —
    copiată (nu mutată) în `~/.heed/voices/`, verificată byte-identic cu sursa. **Clarificare**:
    vocea Piper e DOAR pentru generarea sintetică a datelor de antrenare „AJUTOR" — nu are nicio
    legătură cu vocea cu care BENSON răspunde (TTS nativ Android, separat).
  - **Prima antrenare reală**: `heed init ajutor --phrase "AJUTOR"` + 24 pozitive TTS directe +
    100 pozitive augmentate (voci Piper diferite, vorbitori ținuți deoparte pentru test) + 40
    negative reutilizate din `.benson-training/negative_wav` (zero corpus nou construit) + 28 seturi
    de distractori auto-generați. Model `TinyWakeWordNet` „small", **10 513 parametri (44 KB)**,
    antrenat în **124,7 secunde**. Prag calibrat 0.65 (TPR=1.00/FPR=0.00 pe setul de validare
    sintetic — optimist, NU echivalent cu o validare reală pe voce umană/zgomot ambiental, exact
    avertismentul din `VOICE_WAKEWORD_BENSON_TRAINING.md`).
  - **Export verificat**: `ajutor/export/wake.onnx` (44,1 KB, eroare max față de modelul PyTorch
    original = 0) + `wake.json`. **Compatibilitate confirmată din date, nu presupusă**: `wake.json`
    are EXACT aceleași chei ca `heed_candidate.json` deployat (preprocessing/trigger/energy_gate/
    `heed_export_version`) — același pipeline de caracteristici (n_mels/n_fft/hop_length identice),
    deci zero adaptor necesar pentru a fi citit de același cod care citește azi modelul „Benson".
    Varianta int8 s-a produs, dar verificarea ei a picat în acest mediu (operator `ConvInteger`
    neimplementat în versiunea de `onnxruntime` de pe acest disc — limitare de mediu, nu dovadă că
    modelul e stricat; neutilizată, nerecomandată fără re-verificare).
  - **Arhitectura de integrare (definită, NEIMPLEMENTATĂ)**: un singur proprietar de microfon —
    HEED calculează caracteristicile mel O SINGURĂ DATĂ per cadru (identice între cele două
    modele), le trimite la ambele sesiuni ONNX; fiecare scor trece prin propria poartă de
    declanșare. Poarta de declanșare (consecutive_frames + refractory_seconds — identică ca formă
    la ambele modele) e deja scrisă și testată IZOLAT, pur Kotlin, fără ONNX:
    `SosTriggerGate.kt`/`SosTriggerGateTest.kt`, zero atingere a `HeedWakeWord.kt`.
  - **Cost/latență**: doar ESTIMAT, nu măsurat pe dispozitiv — 10,5K parametri e un model minuscul
    (comparabil ca ordin de mărime cu ce rulează deja HEED la fiecare 100ms); de măsurat real
    înainte de orice integrare.
  - **Multilingv (RO/DE/EN/ES/ZH/HI)**: „AJUTOR" (română) e primul, folosit ca șablon reproductibil
    — fiecare limbă următoare cere propria frază + propria voce Piper/Kokoro + propriul ciclu
    `heed init/train/export`, NU un singur model presupus universal.
  - **Declanșare independentă vs. conversațională**: „AJUTOR" spus SINGUR (fără „Benson") trece
    exclusiv prin acest detector local; o urgență menționată ÎN CONVERSAȚIE („Benson, am nevoie de
    ajutor") rămâne pe calea existentă, prin inteligența conversațională multilingvă a BENSON LIVE —
    nu sunt același traseu.
  - **Blocaje reale**: fără măsurători CPU/RAM/baterie/latență pe dispozitiv (necesită telefonul);
    fără date reale de voce umană (doar sintetic Piper); `HeedWakeWord.kt`/
    `BensonForegroundService.kt` NEATINSE — integrarea cere acord explicit + checkpoint Git +
    validare pe telefon, per decizia KWS.

- **TEST DE PARITATE PREPROCESARE — rezultat real, 19.10.2026** (`HeedStreamingPreprocessorParityTest.kt`,
  5/5 teste, nicio modificare la `HeedWakeWord.kt`/`BensonForegroundService.kt`):
  - **Defect real, confirmat NUMERIC (nu doar structural)**: `HeedStreamingPreprocessor.kt` nu
    aplică `peak_normalize` — `heed/infer.py` (referința Python, `WakeWordDetector.step()`) aplică
    `peak_normalize(-3dBFS)` pe fereastra curentă la FIECARE pas, înainte de log-mel. Măsurat pe
    același clip: `max|Kotlin - Python(fără normalize)| = 3.4e-5` (practic identic, doar zgomot
    float32) vs. `max|Kotlin - Python(CU normalize, referința reală)| = 0.6858` — diferența e
    ÎNTREAGĂ și EXCLUSIV din lipsa acestui pas, nimic altceva.
  - **De ce nu s-a rupt „Benson" până acum (analiză, nu presupunere)**: CMN (scăderea mediei pe
    timp, per bandă mel) anulează matematic un gain constant aplicat întregii ferestre — ESTE
    exact ce arată testul pentru majoritatea cazurilor. Diferența de 0,69 vine din pragul
    `LOG_FLOOR=1e-9`: la energie joasă, varianta neamplificată atinge pragul și varianta
    amplificată (peak-normalizată) nu — acolo anularea matematică nu mai e exactă. Afectează
    probabil marginile/porțiunile liniștite ale clipului, nu vorbirea clară.
  - **NEREPARAT, deliberat**: fișierul alimentează deja drumul viu, dovedit pentru „Benson" —
    nu se schimbă algoritmul fără decizia explicită a proprietarului, chiar dacă fișierul nu e pe
    lista nominal protejată.
  - **Dependențe noi, doar `testImplementation`, zero impact APK/runtime**: `mockito-core:5.11.0`
    (necesar ca să instanțiezi clasa reală fără Android real) + `org.json:json:20231013` (org.json e
    stub sub rulătorul de teste implicit — HeedStreamingPreprocessor.kt îl folosește direct).

- **PROTOCOL VALIDARE VOCE REALĂ — pregătit, NEEXECUTAT** (cere persoane reale, consimțământ,
  telefon — nimic din ce urmează s-a făcut):
  1. **Consimțământ explicit, scris**, per persoană: ce se înregistrează („AJUTOR", de mai multe
     ori), cum se folosește (doar antrenare/testare model local, niciodată transmis extern), cât se
     păstrează, dreptul de ștergere.
  2. **Minim 2 persoane distincte** pentru primul test real — o voce masculină, una feminină
     (lipsesc complet azi, confirmat: `ro_RO-mihai-medium` e un singur vorbitor, bărbat).
  3. Per persoană: 10 clipuri „AJUTOR" (ton normal) + 5 șoptit/grăbit + 5 de la distanțe diferite
     (0,5 m / 2 m / 3 m, cameră obișnuită) — **separate explicit în antrenare vs. testare**: clipurile
     unei persoane nu apar niciodată în ambele seturi.
  4. **Negative reale, aceleași persoane**: 10 propoziții normale (nu „ajutor"), + televizor/radio
     în fundal 2 min, + conversație normală 2 min.
  5. Rulare: `heed eval <nume-proiect> --positive-dir <test> --negative-dir <test>` — raportează
     TPR/FPR reale pe voce umană, nu sintetic. **Rezultat < acest test = model NEVALIDAT**, indiferent
     de scorurile sintetice de azi.
