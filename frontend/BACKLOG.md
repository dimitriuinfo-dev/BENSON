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
