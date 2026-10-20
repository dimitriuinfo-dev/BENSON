# BENSON NEXT — stare persistentă de construcție

**Citește acest fișier ÎNTÂI la începutul oricărei sesiuni noi pe `benson-next`. Nu relua auditul general.**

## Ultimul commit cunoscut-bun
`6c7940a` pe ramura `benson-next` (pornită din `day-2026-10-08`, care rămâne la `01f6cca`, neatinsă).
**Împins pe GitHub** (`origin/benson-next`), cu tag-ul `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`
pe commit-ul de checkpoint — vezi secțiunea CHECKPOINT de mai jos.

## Teste
418/418 PASS (`./gradlew :benson-foreground-service:testDebugUnitTest`). Vezi `BENSON_NEXT_BACKLOG.md`
pentru distincția pe 4 coloane (implementat/conectat/test automat/DEVICE_PASS) — **primul DEVICE_PASS
real al acestei runde a fost obținut** (SURVIVOR/navigație Waze), detaliat mai jos.

## SURVIVOR Verification Engine (19.10.2026) — răspuns la DEVICE_FAIL Calculator

Captura lui Rareș: BENSON a spus „Nu găsesc calculator butoanele trei șapte si opt pe ecran. Apasă
pe calculator butoanele trei șapte si opt." — ecranul final arăta `89.979()`, nu `378`. Cauza
GENERICĂ (nu specifică Calculatorului) găsită și reparată, în `benson-accessibility` (EXECUTOR
comun, folosit de orice interacțiune `tap_element`, nu un script special):

**Bug real, confirmat prin citirea codului**: `tapDigestElement()` (`BensonAccessibilityService.kt`)
întorcea `ControlResult(true, ...)` **necondiționat** după o apăsare acceptată — inclusiv când
`changed` (ecranul comparat înainte/după) era `false`. Mesajul text era onest ("...dar ecranul pare
neschimbat."), dar câmpul `ok` rămânea `true`. Agentul primea deci "succes" pentru o apăsare care nu
produsese NICIUN efect observabil — exact genul de fals pozitiv care, repetat, poate duce la o stare
finală incoerentă precum `89.979()`.

**Reparat**, pe punctele din mandat:
1. **Plan/rezultat separate**: codul BENSON nu calculează și nu presupune NICIODATĂ rezultatul
   matematic așteptat (378) — doar verifică, generic, că fiecare apăsare a avut un efect observabil.
   Rezultatul final se citește mereu literal de pe ecran, niciodată dedus.
2. **Dovezi observabile**: fiecare ramură de eșec din `tapDigestElement` loghează acum explicit
   `classification=<UI_NODE_NOT_FOUND|UI_ACTION_REJECTED|VERIFICATION_FAILED|UNKNOWN>`, nu doar
   `ok=false`.
3-4. **Verificare reală a efectului, nu doar prezența unei reobservări**: după apăsare, se citește
   `screenSignature()` din nou; dacă nu s-a schimbat, rezultatul e acum `ok=false`
   (`VERIFICATION_FAILED`), nu `true`. Agentul primește mesajul explicit și trebuie să reobserve.
5. **Fără coordonate fixe, fără repetare oarbă**: neschimbat — `tapDigestElement` tot funcționează
   doar pe indici dintr-un digest proaspăt, cu verificare de etichetă anti-ecran-schimbat
   (comportament deja existent, FIX_AGENT_TAP_CONTRACT_1, neatins).
6. **Calculator — stare inițială/expresie/rezultat**: NU s-a construit logică specifică
   Calculatorului (interzis explicit, punctul 9) — verificarea rămâne generică ("s-a schimbat ceva
   observabil pe ecran după apăsare"), aplicabilă oricărei aplicații cu conținut text vizibil.
7. **Cifrele 3,7,8 — planificare greșită sau răspuns final?** — **NU se poate răspunde din log**:
   bufferul logcat s-a rotit peste rularea exactă a lui Rareș înainte să pot citi; nu exista încă
   tag-ul `classification=` din acest fix. Ipoteză, explicit neconfirmată: fals pozitive repetate
   (`ok=true` pe apăsări fără efect) ar fi putut lăsa agentul să creadă că progresează, ducând la o
   secvență de apăsări necorelată cu planul inițial. Următoarea rulare reală va avea dovada în log.
8. **FailureClassifier**: `InteractionVerification.kt` (nou, pur, testat) — exact cele 4 categorii
   cerute: `UI_NODE_NOT_FOUND`, `UI_ACTION_REJECTED`, `VERIFICATION_FAILED`, `UNKNOWN`.
9. **Un singur EXECUTOR comun**: fix-ul e în `tapDigestElement`, funcția deja folosită de ORICE
   apăsare prin agent (`TAP_ELEMENT`) — nu un drum nou, nu un al doilea LIVE.
10. **Teste + dovezi ADB nedistructive**: 6 teste noi (`InteractionVerificationTest`), 111/111 teste
    PASS în `benson-accessibility`, 418/418 neschimbate în `benson-foreground-service`.
    `assembleRelease` reconstruit local (BUILD SUCCESSFUL, semnătură identică `fbbc618da8...`) —
    **NEINSTALAT** (nicio reautorizare explicită de instalare/cost în acest mesaj) — pregătit pentru
    reluarea testului real, nu executat.

**Fișiere**: `InteractionVerification.kt`/`InteractionVerificationTest.kt` (noi) +
`BensonAccessibilityService.kt` (modificat, fișier deja COMIS anterior — deci diff normal, dar
rămâne NECOMIS acum, alături de restul lucrului nevalidat Calculator, până la o nouă dovadă reală).

## STAGE 3 — SURVIVOR, ÎNCHIDERE ȘI VALIDARE (20.10.2026) — DEVICE_PASS pe mecanismele centrale

Răspuns la 6 corecții cerute explicit, ÎNAINTE de orice test pe telefon — fiecare a prins ceva real:

**1. „classifyFaultOrigin/isSafeToRetry nu erau folosite in motor" — confirmat, corectat.**
Verificat prin grep: ZERO apeluri in afara propriilor teste. Acum cablate real:
`classifyFaultOrigin` decide ce se întâmplă cu o cunoștință AKA la eșec; `isSafeToRetry` (prin
wrapper-ul `isAutomaticRetryUnsafe`) populează un câmp NOU, `retry_unsafe`, în JSON-ul pe care
`runAgentToolLocked` îl întoarce — citit MECANIC de bucla agentului, nu doar oferit ca indiciu
modelului.

**2+3. Rezultat incert ≠ sigur de reîncercat.** `isAutomaticRetryUnsafe` întoarce `true` pentru
VERIFICATION_FAILED, UNKNOWN și UI_NODE_NOT_FOUND — DOAR UI_ACTION_REJECTED (nimic nu s-a
întâmplat încă) rămâne sigur. Gard nou în bucla agentului: o acțiune marcată `retry_unsafe` e
adăugată la `executedActions` EXACT ca un succes — o repetare identică e refuzată, indiferent
dacă prima încercare a „reușit" sau doar „nu s-a putut exclude efectul".

**4. „Nu retrograda pentru o observație expirată" — bug real găsit prin auditul cerut, înainte
de orice test.** Versiunea anterioară decidea retrogradarea direct din `failure`, ignorând daca
aplicația/ecranul erau cele așteptate. Reparat: `needsReidentification` consumă acum ORIGINEA deja
clasificată (`classifyFaultOrigin`, care verifică `foreignAppForeground` ÎNAINTEA oricărei
interpretări) — o aplicație străină în prim-plan produce EXTERNAL_APP, niciodată AKA, deci nicio
cunoștință VERIFIED nu se retrogradeaza pentru o întrerupere externă.

**5. „Distinge progres raportat de reluare efectivă" — corectat onest.** Comentariile care
sugerau „recuperare" au fost corectate explicit: `missionProgress` e STRICT raportare — Masterul
află ce s-a realizat, dar misiunea NU se reia singură; continuarea cere o comandă nouă.

**6. Testat automat, inclusiv eșecuri simulate și rezultate incerte** — 634/634 (16 teste noi:
origine, reîncercare, reidentificare, inclusiv scenariul exact „aplicație străină + cunoștință
VERIFICATĂ" și „retry_unsafe pentru VERIFICATION_FAILED/UNKNOWN/NOT_FOUND").

**SESIUNE DE VALIDARE PE DISPOZITIV REAL (OnePlus Nord 4), o singură instalare planificată +
UNA suplimentară, justificată de un bug real găsit chiar prin acest test** (nu pentru ajustări
minore — pentru o reparație funcțională dovedită necesară pe loc):

Instrument nou, gratuit: `DEBUG_SURVIVOR_PRESS` — cheamă `runAgentToolLocked` DIRECT (aceeași
funcție privată pe care bucla plătită a agentului o folosește), deci traversează EFECTIV logica
nouă, nu doar executorul de nivel jos. Zero apel OpenAI.

- **TEST A (aplicație străină nu retrogradează)**: Calculator → Setări (fără observare explicită
  a Setărilor, ca `lastServedDigest` să rămână Calculator) → `press_button("5")` →
  `foreign_app_foreground` detectat corect, `retry_unsafe:true`, **NICIUN** `REGISTRY_EVENT
  kind=needs_reverify` — `ui_control:5` a rămas VERIFIED. **DEVICE_PASS.**
- **TEST B (reidentificare genuină)** — PRIMA rulare a eșuat: niciun `needs_reverify` nici aici,
  deși contextul era corect (același pachet, control genuin absent pe ecranul Convertor). Cauza
  reală, găsită imediat: ramura `UI_NODE_NOT_FOUND` din `pressButtonByLabel` nu seta niciodată
  `packageName` pe `ControlResult` — garda `packageName.isBlank()` ieșea tăcut, de fiecare dată,
  indiferent de origine. Reparat (`FIX_SURVIVOR_MISSING_PACKAGENAME_1`, + aceleași trei ramuri
  similare reparate pentru consecvență), reinstalat, RETESTAT: `REGISTRY_EVENT
  kind=needs_reverify cap=ui_control:5` a apărut corect, `ui_control:5` → REVERIFY, `ui_control:9`
  și `ui_control:umrechner` (alte controale) rămase NEATINSE. **DEVICE_PASS.**

**Onest, ce rămâne neverificat pe dispozitiv**: blocarea efectivă, de către BUCLA agentului, a
unei a DOUA cereri identice după un `retry_unsafe` — semnalul însuși e dovedit corect (JSON-ul
real conține `retry_unsafe:true` la momentul potrivit), dar exercitarea buclei complete cere o
rulare reală a agentului (apel OpenAI), neautorizată această rundă. Mecanismul de bază
(`ExecutionIdempotencyKey`/`isDuplicateExecution`) era deja testat dinainte (D0).

**Checkpoint**: comis după validare, tag DEVICE_PASS pe capacitățile confirmate mai sus.

## STAGE 3 — SURVIVOR (20.10.2026) — construit peste infrastructura existentă, checkpoint NU DEVICE_PASS

**Audit întâi, cerut explicit** — ce exista deja, nu s-a dublat nimic:
- `InteractionVerification.kt` — deja auto-etichetat „SURVIVOR/EXECUTOR" în propriul header;
  observarea efectului real (`InteractionEvidence`/`classifyInteractionOutcome`) rămâne NESCHIMBATĂ.
- `AgentVerificationGate.kt` — gărzile de SECVENȚĂ (acțiune→observare obligatorie) rămân neatinse.
- `BensonAgentContracts.kt` (D0) — `ExecutionOutcome`, `ExecutionIdempotencyKey`/
  `isDuplicateExecution`: deja pure, deja testate, dar NECABLATE până acum în drumul real —
  SURVIVOR le cablează, nu le redefinește.
- `NavigationMissionTrace.kt` — deja numit „SURVIVOR V0" în header, izolat (Waze); confirmă
  tiparul deja acceptat (Mission/MissionStep/ExecutionOutcome), nu contrazis de ce s-a construit.

**Construit, nou, exact cele 8 cerințe**:
1. **Observă rezultatul real** — deja existent (`InteractionEvidence`), reutilizat, nu dublat.
2. **Detectează acțiuni eșuate/blocate** — deja existent, reutilizat.
3. **Identifică originea** (AKA/EXECUTOR/PERMISIUNE/APLICAȚIE EXTERNĂ) — NOU:
   `SurvivorDiagnosis.classifyFaultOrigin`, pur, din semnalele deja produse (fără sursă nouă de date).
4. **Reîncearcă doar operații sigure** — NOU: `isSafeToRetry` — niciodată pentru o acțiune deja
   reușită o dată (previne duplicarea), niciodată pentru un control CONFIRM/REFUSE, niciodată
   pentru UI_NODE_NOT_FOUND (cere reidentificare întâi) sau VERIFICATION_FAILED (ceva s-a întâmplat
   deja, posibil ireversibil) sau UNKNOWN (nicio dovadă de ghicit).
5. **Cere AKA reidentificare la schimbarea interfeței** — NOU: `needsReidentification` +
   `StaffRegistryCore.downgradeToReverify`/`StaffRegistry.markNeedsReverification` — cablat în
   `press_button`/`tap_element`: un eșec UI_NODE_NOT_FOUND pe un control deja VERIFIED îl
   retrogradează la REVERIFY, din dovadă reală, nu bănuială.
6. **Recuperează misiuni întrerupte** — NOU, scopat corect: `missionProgress` (traseu STRICT al
   rulării curente, NU `ActionHistory`-ul global, partajat între comenzi diferite — amestecarea
   lor ar fi afirmat fals că o misiune nouă „a realizat" ceva dintr-o conversație anterioară).
   Mesajul de eșec onest la epuizarea pașilor spune acum CE s-a reușit, nu doar „n-am reușit".
7. **Previne repetarea apelurilor/mesajelor/plăților** — NOU: gard al patrulea în bucla agentului,
   reutilizând `ExecutionIdempotencyKey`/`isDuplicateExecution` (D0) — o acțiune identică
   (instrument + parametri identici) deja reușită o dată în aceeași rulare e refuzată a doua oară.
8. **Raportează corect succes/eșec/incertitudine** — deja existent (UNKNOWN distinct de
   VERIFICATION_FAILED), neschimbat.

**SURVIVOR nu conduce misiuni, nu înlocuiește LIVE** — confirmat prin construcție: niciuna din
funcțiile de mai sus decide CE misiune se face, doar CE se întâmplă cu un pas deja eșuat.

**Teste**: `SurvivorDiagnosisTest.kt` (13 teste noi, pure), `downgradeToReverify` (3 teste noi).
628/628 teste totale (benson-accessibility + benson-foreground-service), zero regresii.
`gradlew :app:assembleRelease` BUILD SUCCESSFUL.

**NU declarat DEVICE_PASS** (cerut explicit: nu pe baza unui build sau a testelor unitare) — nimic
din SURVIVOR etapa 3 a fost exercitat pe dispozitiv real încă. Verificare pe dispozitiv rămâne
pasul următor, separat.

## STAGE 2 — LIVE folosește AKA, un singur orchestrator (20.10.2026, confirmat prin citire de cod)

Confirmat, fără nicio modificare de cod (verificare, nu construcție nouă):
- Un singur punct de dispatch al instrumentelor: `BensonForegroundService.runAgentToolLocked`
  (o singură definiție, un singur apel, din `tryAgent`). `aka_query_app`, `aka_explore_function`,
  `observe_screen` (cu atașarea automată a cunoștinței AKA), `press_button`/`tap_element` — TOATE
  trec prin acest unic punct.
- `BensonLiveOrchestrator` (scaffold-ul separat, 5-nivele, D0) rămâne NECONECTAT — zero
  instanțiere în afara propriului fișier. Nu există un al doilea orchestrator activ.
- `systemPrompt` instruiește explicit modelul să cheme `aka_query_app` ÎNAINTE de o misiune
  într-o aplicație nouă/necunoscută, ca să aleagă metoda de operare pe baza a ce e deja cunoscut,
  nu să ghicească — exact „LIVE utilizează informațiile guvernate de AKA pentru alegerea metodei
  de operare". O singură inteligență conversațională (`gpt-5-nano`, neschimbat) pentru tot
  sistemul — nu s-a introdus alta.

## ÎNCHIDERE AKA (20.10.2026) — DEVICE_PASS per capabilitate, dovezi reale, cu limite precise

Teste reale pe OnePlus Nord 4 (telefon conectat, Accessibility reactivat), fără niciun apel
OpenAI. Două defecte noi găsite și reparate EXACT prin acest test (nu presupuse). Zero setare a
telefonului și zero dată personală modificată — garantat MECANIC (toate presele refuzate sau
reușite doar pe control de navigare pur), nu doar prin observație.

**1. Descoperirea funcțiilor accesibile — DEVICE_PASS.**
Ceas (`com.oneplus.deskclock`, aplicație fără profil predefinit): 5 funcții corecte găsite
(„Weltzeituhr", „Stoppuhr", „Timer", meniul de opțiuni, statusul alarmelor), rândurile de alarme
corect excluse. Setări (`com.android.settings`): inițial **0 candidați** (defect real — vezi mai
jos), după fix: **11-13 funcții reale** găsite corect (WLAN, Bluetooth, Mod avion, Rețea mobilă,
Confidențialitate, Baterie etc.).

**2. Identificarea controalelor și traseelor de navigare — DEVICE_PASS.**
„Weltzeituhr" apăsat → tranziție de ecran REALĂ, verificată prin semnătură structurală diferită
(`93f99fd1`→`78cf16d6`, nu doar „ceva s-a schimbat"), muchie de navigare înregistrată.

**3. Explorarea sigură, fără modificare de setări/date personale — DEVICE_PASS, garantat mecanic.**
Pe Setări, FIECARE încercare de explorare a fost refuzată automat (etichetă negăsită în
observarea proaspătă sau ambiguă la apăsarea reală) — **zero apăsare a ajuns efectiv la vreun
control din Setări**. Singura apăsare reușită a fost „Weltzeituhr" din Ceas — navigare pură,
fără nicio modificare de stare. Nicio schimbare de ecran nu a fost considerată, prin ea însăși,
dovadă de operare corectă — fiecare succes a fost confirmat prin semnătura structurală ÎNAINTE/
DUPĂ, nu prin simpla observație „altceva se vede acum".

**4. Memorarea funcțiilor și traseelor verificate — DEVICE_PASS** (aceeași dovadă ca punctul 2,
persistată: `1 funcție confirmată, 3 descoperite neconfirmate, 1 traseu confirmat`).

**5. Recuperarea cunoștințelor după repornirea procesului — DEVICE_PASS.**
`force-stop` real (PID 26671 → proces nou 29750, auto-revigorare) → cunoștința identică,
regăsită corect din stocarea de pe disc.

**6. Furnizarea/utilizarea de către LIVE — PARȚIAL, distincție explicită cerută:**
**Demonstrat local**: exact funcția de PRODUCȚIE (`formatAppKnowledgeSummary`/
`attachKnownUiControls`, aceeași pe care `aka_query_app`/`observe_screen` o folosesc în
`runAgentToolLocked`) a fost exercitată direct pe date REALE, persistate pe disc, prin harness-ul
de debug — nu o simulare separată, codul de producție însuși. **NEDEMONSTRAT**: un LLM real,
plătit, într-o conversație reală, citind acest text și schimbându-și comportamentul pe baza lui.
Necesită autorizare de buget explicită, neexecutată această rundă.

**Defecte noi găsite și reparate prin acest test (al doilea și al treilea din serie)**:
- `FIX_AKA_FULLSCREEN_SCROLLABLE_1`: Setările au TOT ecranul într-un singur container derulabil →
  regula strictă excludea toate rândurile de meniu, raportând fals „zero funcții". Reparat generic:
  dacă regula strictă ar lăsa zero candidați deși există controale clickabile, se revine la
  semnalul simplu (clickabil + etichetat) — niciodată „zero funcții" cât timp există butoane.
- `FIX_AKA_CAP_LABEL_COMMA_1`: o etichetă reală conținând o virgulă („Weitere Optionen,Neue
  Benachrichtigungen", direct din contentDescription Android) rupea cheia de capabilitate la
  serializare (aceeași clasă de bug ca `FIX_AKA_CAP_KEY_COLON_1`, alt delimitator). Reparat la
  normalizarea comună a etichetei (afectează toate cele trei familii de chei deodată).

**Limită reală, precis diagnosticată (nu „ceva nu merge", ci EXACT ce și de ce)**: ecranul
principal al Setărilor OnePlus are conținut care se actualizează continuu (procent baterie, stare
rețea) și/sau poziție de derulare care diferă între lansări — două observări succesive, la
câteva sute de ms distanță, pot vedea seturi diferite de candidați vizibili. Mecanismul de
explorare (compară DOUĂ observări independente) e fiabil pe ecrane statice (Calculator, Ceas,
dovedit de mai multe ori) dar NU încă pe ecrane cu conținut live-actualizat sau pe pagini lungi
unde ținta e în afara zonei vizibile curente — AKA nu derulează încă pentru a căuta o funcție.
Aceasta e o limită de SCOP, nu un bug corectat — o extensie viitoare (derulare ca parte a
explorării) ar trebui sa o adreseze, nu un patch grăbit acum.

**Checkpoint**: cele doua fixuri de mai sus, comise după acest test real (nu înainte).

## AKA DISCOVER/MAP (20.10.2026) — corecție arhitecturală: descoperire reală, nu memorare de etichete

Mandat: AKA nu e memorarea apăsărilor — e cunoașterea autonomă a aplicațiilor. Ciclu complet:
DISCOVER → UNDERSTAND → MAP → STORE → SERVE LIVE → EXECUTE → VERIFY → LEARN → UPDATE.

**Implementat, commit `dd1ffda` (checkpoint, nu DEVICE_PASS)**:
- `AkaKnowledge.kt` (nou, pur) — regulă structurală generică: un control clickabil, cu etichetă,
  aflat în AFARA unei liste derulabile = funcție candidată a ecranului; un rând dintr-o listă
  derulabilă = conținut, nu funcție. `ScreenSignature` = amprentă STRUCTURALĂ a unui ecran (doar
  funcțiile candidate + câmpurile editabile, normalizat), stabilă peste conținut dinamic (ora unui
  ceas, istoricul unui calculator), diferită între ecrane structural diferite. Testat pe 3
  aplicații SINTETICE (NotiFast/SkyNow/ConfigPanel) — deliberat fără nicio legătură cu
  Calculator/Ceas/Calendar/WhatsApp, dovada cerută explicit de generalizare.
- `BensonAccessibilityService.akaDiscoverScreen()` (observare pură, fără efecte externe) și
  `akaExploreByPressing()` (explorare ACTIVĂ dar STRICT sigură — refuză mecanic orice control
  clasificat cu risc de `ButtonSafetyClassifier` sau orice câmp editabil; operarea reală a unei
  funcții cu risc rămâne pe `press_button`/`tap_element`, sub Confirmation Gate-ul existent,
  neschimbat). Observă înainte și după, ca tranziția de ecran să fie VERIFICATĂ, nu presupusă.
- `StaffRegistryCore`: familii noi `aka_fn:<semnătură_ecran>:<etichetă>` și
  `aka_nav:<sursă>:<etichetă>:<destinație>`, în ACELAȘI map `caps` (niciun registru nou) — separă
  explicit KNOWN (descoperit, neconfirmat) de VERIFIED (confirmat prin apăsare reală) — un singur
  buton funcțional NU promovează întreaga aplicație. `formatAppKnowledgeSummary(member)` — LIVE
  poate întreba ce se cunoaște despre o aplicație ÎNTREAGĂ (nu doar ecranul curent), cu gap-urile
  spuse onest ("nu a fost explorată").
- Tool-uri noi, în ACELAȘI `runAgentToolLocked` (nu un orchestrator nou): `aka_explore_function`
  (mapare sigură de trasee) și `aka_query_app` (interogare pură, fără deschidere). `observe_screen`
  inventariază AUTOMAT funcțiile candidate ale ecranului curent — fără listă închisă de aplicații.
- Guvernanță: `demoteAfterUpdate` (deja generic) retrogradează `aka_fn`/`aka_nav` la update de
  aplicație, exact ca orice altă capabilitate; `markDiscovered` nu retrogradează NICIODATĂ o
  dovadă mai bună deja existentă.

**Ce poate face efectiv AKA acum**: pe ecranul curent al ORICĂREI aplicații (cunoscute sau nu),
descoperă automat funcțiile candidate (fără cod specific per aplicație), poate explora SIGUR
pentru a mapa unde duc, persistă dovada specifică per control/ecran, și poate răspunde la
„ce cunoști despre aplicația X" înainte de a o deschide.

**Ce rămâne limitat, onest**: (a) „metodele de operare" reprezentate explicit în cunoștința AKA
sunt doar Accessibility (apăsare/explorare) — Calendar demonstrează deja o metodă alternativă
(Content Provider, CalendarContract) ca EXECUTOR separat, dar nu e încă modelată ca o „metodă"
alternativă în structura de cunoștințe AKA însăși; (b) eticheta unei funcții candidate ar putea,
teoretic, într-un layout neobișnuit (ex. un grid de contacte favorite, neclickabil-în-listă),
conține date personale — nu există încă un filtru explicit pentru acest caz, dincolo de
redactarea deja existentă a câmpurilor de parolă; (c) consultarea reală de către un LLM plătit,
într-o conversație, rămâne nedovedită pe dispozitiv (fără buget, fără telefon conectat la finalul
rundei).

**Teste**: 604/604 (benson-accessibility + benson-foreground-service), inclusiv 9 noi pe fixturi
sintetice (generalizare) + teste explicite de persistență/invalidare/recuperare pentru cheile cu
mai multe ':' (exact tiparul care a cauzat FIX_AKA_CAP_KEY_COLON_1 — verificat, nu presupus).
`gradlew :app:assembleRelease` BUILD SUCCESSFUL. Zero apel OpenAI, zero reinstalare, zero
modificare de date personale/apeluri/mesaje/plăți. Telefonul s-a deconectat spre finalul rundei
(`adb devices` fără rezultat) — dezvoltarea a continuat conform mandatului, fără a depinde de el.

## AKA P0 — CONTINUARE 2 (20.10.2026): checkpoint comis, contract complet testat, test pregătit

Răspuns la cele 6 cerințe ale rundei următoare raportului 5137b43.

**1. Checkpoint de dezvoltare** — comis și împins: `a2364d9` ("CHECKPOINT DE DEZVOLTARE (nu
DEVICE_PASS)"), 33 fișiere, tot ce era necomis pe acest fir (press_button/G-1, cele 4 gărzi
SURVIVOR, LEGEA PERSONALULUI/StaffRegistry, Calendar Agent, AKA V1) — compilează curat
(`assembleRelease` verde) și trece 581/581 teste. Explicit NU etichetat DEVICE_PASS; `frontend/CLAUDE.md`
și `BensonCommandRegistry.kt` (necomise dinainte, nelegate de acest fir) au rămas neatinse.

**2. Accessibility — BLOCAJ REAL, cere acțiunea Masterului.** Verificat non-distructiv
(`settings get secure accessibility_enabled` → `0`; `dumpsys accessibility` → `Bound services: {}`).
Nu am reinstalat nimic ca să repar asta. Cauza cea mai probabilă: Android dezactivează automat un
serviciu de Accesibilitate al unei aplicații sideload-uite la fiecare actualizare a APK-ului — exact
ce am făcut de 3 ori rândul trecut. **Ce trebuie activat manual, exact**:
  1. Setări → Accesibilitate (Accessibility) → Servicii descărcate / instalate → **BENSON**.
  2. Activează comutatorul → confirmă în dialogul de avertizare.
  3. **Dacă butonul e gri**, mai întâi: Setări → Aplicații → BENSON → (⋮, trei puncte, dreapta sus)
     → „Permite setări restricționate" → apoi pasul 1-2 din nou.
Până la reactivare, `press_button`/`tap_element`/`observe_screen` nu pot fi testate pe dispozitiv
(confirmat: ultima tentativă de `DEBUG_SCREEN_DIGEST` a răspuns `ok=false reason=no_accessibility_or_root`).

**3. Contractul complet LIVE↔AKA, verificat local** — `StaffRegistryCore.attachKnownUiControls(screenText, caps)`
(nou, pur) extrage EXACT compunerea pe care `runAgentToolLocked` o aplică (nu doar formatarea
izolată de runda trecută): digest-ul original + linia AKA, DOAR când există ceva VERIFIED. 4 teste
noi: adaugă linia corect; **text identic, zero schimbare, când nu există istoric** (zero regresie
pe Calculator/WhatsApp/Waze, deja dovedite); ignoră `REVERIFY` (nu afirmă „deja verificat" pentru
ceva ce așteaptă reconfirmare); nu listează capabilități NUMITE (CAP_CALL) ca și cum ar fi
controale. `runAgentToolLocked`/`DEBUG_AKA_DUMP` apelează acum ACEEAȘI funcție (nu o duplicau).

**4. Control ≠ înțelegerea funcției, confirmat explicit** — 2 teste noi: verificarea unui
`ui_control:*` nu promovează NICIODATĂ, implicit, o capabilitate NUMITĂ (`CAP_CALCULATE` etc.);
`attachKnownUiControls` nu listează niciodată o capabilitate numită ca și cum ar fi un control.
Cele două niveluri rămân separate, în ACELAȘI registru, fără promovare încrucișată automată — nu
a fost nevoie de cod nou pentru asta, doar de dovada explicită că separarea e reală, nu presupusă.

**5. Testul real pregătit, NEEXECUTAT — autorizare de buget necesară**:
  - **Comandă**: „Benson, deschide Calculatorul și apasă 5" (scurtă, o singură acțiune, deja
    cunoscută agentului — niciun calcul nou, niciun risc).
  - **Pași expectați**: `open_app` → `observe_screen` (ar trebui să conțină acum, dacă
    Accessibility e activă și cunoștința e încă persistată, linia „Cunoștințe AKA anterioare...
    5, 9.") → `press_button("5")` → `say`.
  - **Ce dovedește**: capturat din logcat-ul REAL al cererii HTTP către model (sau din
    `AGENT_TOOL`/conținutul trimis), că textul `observe_screen` chiar conținea linia AKA — proba
    că LIVE a PRIMIT cunoștința (nu doar că funcția pură o produce izolat, cum e dovedit acum).
  - **Cost estimat**: sub 0,02 USD (4 pași, payload mic, `gpt-5-nano`). **Plafon strict**: 0,03 USD,
    o singură rulare, fără reîncercare automată.
  - **NU se execută fără autorizare explicită de buget.** Precondiție: Accessibility reactivat
    manual (punctul 2) înainte de această rulare.

**6.** Niciun alt agent (Calendar/Waze/Shopping) nu a fost pornit sau dezvoltat în continuare.

## AKA V1 — CONTINUARE, CICLU COMPLET VALIDAT PE DISPOZITIV REAL (20.10.2026)

Mandat: 8 cerinte punctuale pe raportul af27cf7. Niciun apel OpenAI; testat cu harness-ul
DEBUG_AKA_PRESS/DEBUG_AKA_DUMP (nou, gratuit, executa EXACT codul de productie
`pressButtonByLabel`+`recordUiControlVerifiedLocked`, fara agentul platit).

**Corectii, punct cu punct**:
1. **Atribuire** — `ControlResult` are acum `packageName` (citit DIN digest-ul de ÎNAINTE de
   apasare, in `tapDigestElement`/`pressButtonByLabel`), nu o re-interogare a prim-planului de
   DUPA verificare. `recordUiControlVerifiedLocked` foloseste STRICT aceasta valoare.
2. **Dovada specifica, nu un flag pe toata aplicatia** — `CAP_UI_CONTROL_PREFIX` +
   `uiControlCapabilityId(label)` produc o cheie PER CONTROL ("ui_control:5" != "ui_control:9"),
   in ACELASI map `caps` al StaffRegistry (niciun registru nou).
3. **Verificare relevanta, nu o schimbare oarecare** — `ControlResult.strongEffectEvidence` e
   adevarat STRICT cand semnalul de FOCALIZARE a confirmat efectul; recordarea AKA se face DOAR
   atunci, chiar daca `ok=true` (raspunsul catre Master ramane neschimbat — standardul pentru
   cunostinta persistata e mai inalt).

**CAUZA REALA GASITA PE DISPOZITIV, nu presupusa** (exact disciplina cerintei 4): prima rulare
reala a aratat `REGISTRY_EVENT kind=verified cap=ui_control:5` (scrierea reusea), dar imediat
dupa un `StaffRegistry.invalidate()` + reload, `capsOf` intorcea GOL. Cauza: `deserialize` impartea
fiecare intrare pe `':'` presupunand exact 2 bucati — cheile VECHI ("call", "navigate") nu aveau
niciodata ':' in ele, dar "ui_control:5:VERIFIED" are TREI bucati. Testul round-trip existent nu
prindea asta (folosea doar chei simple). **Fara acest test real pe dispozitiv, bug-ul ar fi rămas
ascuns — exact motivul pentru care cerinta 4 a fost pusă explicit.** Fix: impartire pe ULTIMUL
':' (`FIX_AKA_CAP_KEY_COLON_1`), test nou de regresie (`serializeDeserializeRoundTripSurvivesACapabilityKeyContainingAColon`).

**Ciclul complet, dovedit REAL, cu logcat**:
- **Aplicatii descoperite**: `com.oneplus.deskclock` (Ceas OnePlus — FARA capabilitati
  predefinite in `StaffRegistryCore.KNOWN`, exact cerinta punctului 6) si `com.oneplus.calculator`
  (deja cunoscut, reutilizat pentru partea de persistenta — tot fara risc: cifre, zero date
  personale/apeluri/mesaje/plati).
- **Funcții/controale identificate**: ceas — tab-uri de navigare "Weltzeituhr"/"Stoppuhr"/"Timer"
  (gasite generic, din arborele Accessibility, fara niciun profil predefinit); calculator — butoane
  cifra "5"/"9" (viewId `digit_5`/`digit_9`, rezolvate semantic, nu pe index).
- **Efecte verificate**: apasarea „Stoppuhr" → `focused_changed=null` (tab de navigare, fara camp
  de text) → `classification=none` dar `strong=false` → **corect NEinregistrat** (dovada ca
  gardul de la punctul 3 functioneaza, nu doar pe hartie). Apasarea „5" si „9" → `focused_changed=true`
  (`before_focused="" after_focused="...95"`/`"...959"`) → `strong=true` → inregistrat.
- **Cunoștințe persistate**: `ui_control:5=VERIFIED`, `ui_control:9=VERIFIED` pentru
  `com.oneplus.calculator`, confirmate prin `DEBUG_AKA_DUMP` DIRECT din `StaffRegistry.capsOf`
  (citire DE PE DISC, `invalidate()` fortat inainte).
- **Persistenta peste restart REAL**: `am force-stop com.benson.butler` (proces 23806 -> mort) →
  procesul s-a auto-revigorat (mecanism deja existent, neschimbat) ca proces NOU (26069, stare
  statica goala) → `DEBUG_AKA_DUMP` imediat dupa arata ACELEASI doua chei VERIFIED — persistenta
  reala, pe disc, peste un restart de proces adevarat, nu simulat.
- **Cunoștințe reutilizate de LIVE**: `OBSERVE_SCREEN` (in `runAgentToolLocked`) ataseaza acum
  `StaffRegistryCore.formatKnownUiControls(StaffRegistry.capsOf(...))` la raspunsul observarii —
  ACEEASI functie, testata direct pe datele reale persistate (`DEBUG_AKA_LIVE_VIEW`), a produs:
  `"Cunoștințe AKA anterioare, deja verificate pentru acest ecran: 5, 9."` **Onest**: dovedit că
  funcția de formatare produce text corect din date reale — NU dovedit că un agent LLM, într-o
  conversație reală plătită, a citit și folosit efectiv acest text; asta cere o rulare autorizată
  cu buget, neexecutată această rundă.
- **Teste executate pe dispozitiv**: toate prin `DEBUG_AKA_PRESS`/`DEBUG_AKA_DUMP`/`DEBUG_SCREEN_DIGEST`
  (zero cost OpenAI) — instalare non-distructivă confirmată de 3 ori (`firstInstallTime=2026-08-23`
  neschimbat), semnătură verificată identică (`fbbc618d...`) de fiecare dată. Niciun apel, mesaj,
  plată sau modificare de date personale — doar navigare în Ceas și cifre în Calculator.

**Stare la finalul rundei**: Accessibility Service-ul BENSON a devenit neconectat după a treia
reinstalare (probabil protecția Android pentru „restricted settings" la actualizarea unui APK
sideload-uit, deja documentată în `SetupWizard.tsx`) — nu am încercat să o reactivez eu (ar fi
necesitat o acțiune manuală în Setări, care rămâne a Masterului). Un rest inofensiv în istoricul
Calculatorului ("787892778÷989÷959") a rămas neșters din acest motiv — numere, fără conținut
personal, fără risc.

**Ce NU e încă dovedit** (onest, per cerința finală a mandatului): consultarea cunoștințelor AKA
de către un LLM REAL, într-o conversație plătită — doar plumbing-ul (scriere→citire→formatare)
e dovedit pe date reale. **AKA V1 NU e declarat finalizat.**

Toate testele: 576/576 (benson-accessibility + benson-foreground-service), zero regresii. `tsc`
nu a fost nevoie (zero fișiere TS/TSX touched această rundă). Cod rămâne necomis.

## AKA V1 (20.10.2026) — prioritate absolută, wiring minim, cod construit, NU încă DEVICE_PASS

Mandat: AKA (Application Knowledge Agent) e primul agent operațional; nu se mai dezvoltă nimic
altceva (Calendar inclus) până nu e integrat și validat pe dispozitiv. Un singur orchestrator
(`runAgentToolLocked`), nu scaffold-ul `BensonLiveOrchestrator`.

**Analiza care a decis ce se construiește**: `ApplicationCapabilityRegistry.kt` (modelul AKA cu 5
niveluri, D0, pur) și `StaffRegistry.markVerified` (puntea reală, Context-dependentă, deja scrisă
și PERSISTATĂ pe disc din runda LEGEA PERSONALULUI, 14.10.2026) existau amândouă — dar
`markVerified` nu era chemat de NICIUNDE în codul live (verificat prin grep). Cunoștința AKA
exista doar ca 4 aplicații SEMĂNATE manual în `SEEDED_VERIFIED` (Waze/WhatsApp/YouTube/Calculator);
nimic din ce face agentul la runtime o alimenta sau o extindea. Acesta era gap-ul real, nu o
lipsă de cod — exact genul de lucru pe care mandatul îl cere ("folosește codul existent").

**Construit, minim, generic**:
- `StaffRegistryCore.CAP_UI_CONTROL` (nou, const generic) — "BENSON a observat ȘI a operat
  efectiv interfața acestei aplicații prin Accessibility, cu efectul verificat". Spre deosebire de
  `CAP_CALCULATE`/`CAP_MESSAGE`/etc (operații NUMITE, hardcodate per aplicație), aceasta se
  promovează pentru ORICE pachet — inclusiv unul complet necunoscut tabelului `KNOWN` — exact
  cerința de generalizare (punctul 9 al criteriului de finalizare). Risc `NONE` (siguranța reală
  rămâne la `ButtonSafetyClassifier`, la momentul fiecărei apăsări, neschimbat).
- `BensonForegroundService.recordUiControlVerifiedLocked(svc)` (nou) — apelat DOAR când
  `press_button`/`tap_element` întorc `ok=true` (deja verificat SURVIVOR prin
  `classifyInteractionOutcome` — doctrina 9, nimic nou aici), pentru pachetul curent din prim-plan
  (`svc.currentForegroundPackageLive()`). Cablat direct în `runAgentToolLocked` — confirmă punctul
  5 al verificărilor obligatorii: integrarea folosește EFECTIV acel motor, nu scaffold-ul separat.
- Invalidarea e deja generică și deja vie (`demoteAfterUpdate`, aplicat la fiecare
  `PACKAGE_REPLACED` prin `StaffRegistry.bootstrapAndReconcile`, existent dinainte) — `CAP_UI_CONTROL`
  trece prin ACEEAȘI regulă, fără cod special (test nou: `uiControlCapabilityIsAlsoDemotedAfterAnAppUpdateLikeAnyOther`).
- `ControlLocator.visibleClickableLabels` (runda precedentă) e DEJA integrat în rezolvarea
  semantică (`pressButtonByLabel`) — cerința explicită a punctului 3 era deja satisfăcută, confirmat
  aici, nu redus la un nou fișier paralel.
- Descoperire fără efecte externe (punctul 4): `observe_screen` e deja strict read-only, fără nicio
  acțiune — structural neschimbat, confirmat prin citire de cod, nu redus/reconstruit.

**Deliberat NEFĂCUT, ca să nu creeze un al doilea inventar**: nu am conectat modelul de 5 niveluri
din `ApplicationCapabilityRegistry.kt` (încă neconectat la `StaffRegistryCore.initialCaps`) — ar
fi însemnat DOUĂ sisteme de cunoștințe paralele, exact interzis. Puntea reală, deja vie pe telefon
(`StaffRegistry`, 3 niveluri `KNOWN/REVERIFY/VERIFIED`), e singura extinsă.

**Calendar — conservat, neatins**: `BensonCalendarExecutor.kt`/`CalendarEventMath.kt` NU au fost
modificate această rundă. Sunt deja, prin construcție, adaptoare EXECUTOR accesibile prin același
`runAgentToolLocked` (CALENDAR_READ_DAY/CALENDAR_CREATE_EVENT/etc. sunt deja tool-uri dispatch-uite
prin același motor unic) — contractul "AKA → LIVE → EXECUTOR" cerut e deja respectat structural,
fără nicio schimbare necesară. Dezvoltarea de funcționalități noi de calendar rămâne în pauză până
la finalizarea AKA V1, conform mandatului.

**BUILD_PASS vs UNIT_TEST_PASS vs DEVICE_PASS (punctul 6)**:
- **BUILD_PASS**: `gradlew :app:assembleRelease` → BUILD SUCCESSFUL (manifest, toate modulele).
- **UNIT_TEST_PASS**: 571/571 (benson-accessibility + benson-foreground-service), zero regresii,
  inclusiv cele 3 teste noi pentru `CAP_UI_CONTROL` (risc NONE, promovare pe aplicație necunoscută,
  retrogradare după update — toate pure, JUnit, fără dispozitiv).
- **DEVICE_PASS**: NU. Nicio rulare reală — wiring-ul (`recordUiControlVerifiedLocked`) nu a fost
  încă exercitat pe telefon; prima dovadă va fi un `REGISTRY_EVENT kind=verified cap=ui_control`
  real în logcat, pe o aplicație NEsemănată în `SEEDED_VERIFIED`, după o apăsare reușită.

**Verificările obligatorii (punctul 5), stare**: cod necomis protejat (nimic șters, vezi
`git status` — toate fișierele din rundele anterioare rămân `M`/`??`); un singur orchestrator
confirmat prin locul wiring-ului; `press_button` fără regresii (571/571); niciun test nu modifică
date personale, nu inițiază apeluri, nu trimite mesaje (toate pure, pe structuri în memorie);
calendarul NU a fost testat această rundă (nicio verificare de izolare/sincronizare încă
necesară); zero apel OpenAI, buget rămâne închis.

## FIX_PRESS_BUTTON_LABEL_MISMATCH_1 (19.10.2026) — generic, fara liste per limba

Mandat: rezolva generic diferenta dintre SEMNIFICATIA unei actiuni si DENUMIREA reala a
controlului ("×" vs "Multiplizieren"), reutilizand resursele Accessibility si inteligenta
multilingva COMUNA, fara liste rigide per limba.

**Solutia**: `ControlLocator.visibleClickableLabels(root, limit=20)` (nou, pur) — cand
`findButtonCandidates` nu gaseste nimic, `pressButtonByLabel` calculeaza lista REALA a etichetelor
clickabile vazute acum pe ecran si o include in mesajul de eroare catre agent. Niciun cuvant
tradus in Kotlin — doar expunerea datelor deja citite din Accessibility. Inteligenta multilingva
ramane la modelul COMUN (acelasi folosit pentru restul conversatiei), caruia systemPrompt-ul ii
spune explicit: daca «press_button» raspunde NU_GASIT si lista contine un echivalent semantic
(alt cuvant, alta limba, alt simbol), sa cheme din nou «press_button» cu eticheta EXACTA de acolo,
inainte sa treaca la `observe_screen`+`tap_element`.

**Testat LOCAL, fara niciun apel OpenAI** (cerinta explicita a rundei): 3 teste noi in
`ControlLocatorTest` — printre ele exact scenariul real dovedit in rularea 5
(`visibleClickableLabelsExposesTheRealLabelEvenWhenTheSymbolSearchFails`, folosind fixtura reala cu
`desc="Multiplizieren"`). 20/20 in `ControlLocatorTest`, 555/555 in cele doua module atinse
(`benson-accessibility` + `benson-foreground-service`), zero regresii. Necesita o rulare REALA pe
dispozitiv (cu buget reautorizat) ca sa confirme ca modelul chiar foloseste lista — cod construit
si testat local, nu inca DEVICE_PASS pentru acest fix specific.

## CALENDAR AGENT (19.10.2026) — prioritatea noua, cod construit, NU inca DEVICE_PASS

Integrat in ACELASI motor de instrumente deja dovedit pe dispozitiv (`BensonAgentTools` +
`runAgentToolLocked` din `BensonForegroundService.kt` — cel care ruleaza deja `press_button`/
`open_app`), NU in scaffold-ul separat `BensonLiveOrchestrator.kt`/`BensonLiveContracts.kt`
(acela ramane explicit neconectat la productie, per propriul sau comentariu: "implementarile
reale... se cableaza separat... pana la inlocuire demonstrata" — a construi Calendar acolo ar fi
insemnat cod care nu ruleaza niciodata pe telefon, contrazicand "testat pe dispozitiv").

**Construit**:
- `CalendarEventMath.kt` (nou, pur, zero Android/Context) — `intervalsOverlap`, `findConflicts`
  (exclude evenimentul insusi la UPDATE), `isDuplicate` (titlu normalizat + ora de inceput identica),
  `findFreeSlots` (fuzioneaza intervalele ocupate care se suprapun, intoarce golurile ≥ durata
  ceruta). 13 teste noi (`CalendarEventMathTest`), toate verzi.
- `BensonCalendarExecutor.kt` (nou, Android-dependent, `CalendarContract`) — citire (`readDay`,
  toate calendarele, read-only), cautare interval liber (`findFreeSlots`), creare/modificare
  eveniment cu protocol in DOUA FAZE (acelasi tipar ca draftul WhatsApp deja dovedit: intai
  `confirmed=false` → PROPUNERE cu ziua saptamanii rostita, fara nicio scriere; abia
  `confirmed=true`, dupa "da" explicit al Masterului intr-o comanda ulterioara → scrie efectiv),
  conflict/duplicat verificate INAINTE de ambele faze (un conflict se refuza mereu, indiferent de
  `confirmed`), verificare prin RECITIRE dupa fiecare scriere (doctrina 9 — nu doar "insert nu a
  aruncat").
- **Siguranta ceruta explicit**: scrierea (creare/modificare) se face DOAR intr-un calendar LOCAL
  de test ("BENSON Test", `ACCOUNT_TYPE_LOCAL`, creat automat la prima utilizare) — un calendar
  local nu se sincronizeaza niciodata, deci nu trimite NICIODATA invitatii. Programul real al
  Masterului nu e atins cat timp capabilitatea nu e DEVICE_PASS. Citirea ramane pe toate
  calendarele (read-only, fara risc).
- **Multilingv, fara liste rigide**: interpretarea datei/orei ("mâine", "vineri viitoare",
  "übermorgen", "15 noiembrie") ramane la modelul COMUN deja folosit pentru restul conversatiei —
  executorul primeste deja ISO local (`AAAA-LL-ZZTHH:mm:ss`), niciodata text liber de parsat in
  Kotlin. Acelasi principiu ca la fixul "×"/"Multiplizieren" de mai sus.
- Permisiuni: `READ_CALENDAR`+`WRITE_CALENDAR` adaugate in manifestul modulului
  (`benson-foreground-service/android/src/main/AndroidManifest.xml`) si in `SetupWizard.tsx` (pas
  nou "calendar", necritic, acelasi tipar ca restul permisiunilor).

**Verificat local**: `npx tsc --noEmit` curat, `CalendarEventMathTest` 13/13, 439/439 in tot
modulul `benson-foreground-service`, `gradlew :app:assembleRelease` BUILD SUCCESSFUL — manifestul
RELEASE fuzionat confirmat ca include `READ_CALENDAR`/`WRITE_CALENDAR`. Zero apel OpenAI (buget
inchis, conform cerintei).

**NU inca DEVICE_PASS**: nicio rulare reala pe telefon a agentului cu instrumentele de calendar —
nici citire, nici creare de eveniment fictiv in calendarul de test. Pasul urmator, cand bugetul va
fi reautorizat (sau printr-un smoke-test ADB direct, fara OpenAI, pentru partea CalendarContract
singura): instalare non-distructiva, citire unei zile reale (read-only, fara risc), apoi creare a
UNUI eveniment fictiv in calendarul de test + verificare vizuala ca NU a aparut in calendarul real
si ca nicio invitatie nu a plecat.

## Semantic UI Resolver (19.10.2026) — a CINCEA rulare reală, progres real confirmat

Mandat: nu repeta testul cu selecția nesigură de index a modelului — construiește o rezolvare
semantică generică (`press_button(label)`), integrată în AKA/EXECUTOR existent, nu un orchestrator
nou. Construit, testat local, apoi testat REAL pe dispozitiv (rularea 5 a zilei).

**Construit, pe punctele cerute**:
1-2. **`ControlLocator.findButtonCandidates(label, root)`** (nou, pur, în motorul G-1 deja
   existent) — rezolvă ținta din arborele Accessibility ACTUAL (text/desc/viewId, potrivire exactă
   întâi, parțială ca rezervă), niciodată dintr-un index memorat.
3. **Ambiguitate**: `candidates.size > 1` → refuz explicit, cu numărul de candidați, cerând
   `observe_screen`+`tap_element` pentru dezambiguizare prin index — nu un al doilea mecanism de
   selecție, ci reutilizarea celui existent DOAR pentru acest caz.
4. **Identitate pachet**: `pressButtonByLabel` reutilizează `isForeignAppForeground` (deja construit
   pentru `tapDigestElement`) înainte de orice apăsare.
5-6. **SURVIVOR**: aceeași verificare pe focalizare prioritară + coordonate excluse
   (`InteractionVerification.kt`, neschimbată) — zero logică nouă de verificare, zero cunoaștere
   despre Calculator sau poziții de butoane.
7. **Plan vs. rezultat**: instrucțiune explicită în `systemPrompt` — apasă EXACT cifrele/operatorii
   EXPRESIEI cerute, niciodată cifrele unui rezultat presupus cunoscut.
8. **8 teste noi** (`ControlLocatorTest`) — exact scenariile cerute: ordine de noduri schimbată,
   etichete duplicate (ambiguu), element negăsit, element neclicabil exclus, potrivire exactă vs.
   parțială.

**Rularea 5, REZULTAT REAL — progres confirmat, nu doar teoretic**:
```
AGENT_PRESS label="2" → digit_2 găsit, apăsat, VERIFICAT: focalizarea a CRESCUT cu exact "2" ✓
AGENT_PRESS label="7" → digit_7 găsit, apăsat, VERIFICAT: focalizarea a CRESCUT cu exact "7" ✓
AGENT_PRESS label="×" → UI_NODE_NOT_FOUND (eticheta reală e "Multiplizieren", nu simbolul "×")
```
**Pentru prima dată în 5 rulări, cifrele corecte (2, apoi 7) au fost identificate și apăsate CORECT,
cu efectul verificat EXACT corespunzător** (nu doar "ceva s-a schimbat"). Agentul s-a adaptat
singur după eșecul pe „×" (revenind la `tap_element`+`observe_screen`), exact cum spune
`systemPrompt`-ul. Eșecul pe „×" are o cauză clară, diferită: eticheta VIZUALĂ a operatorului
(simbolul „×") nu se potrivește cu `contentDescription`-ul REAL din arborele Accessibility
(„Multiplizieren", cuvântul german — sistemul telefonului e în germană, deja documentat). Nu e un
bug de rezolvare — e o diferență reală între ce VEDE omul și ce EXPUNE aplicația pentru accesibilitate.

**Zero afirmație falsă** — exact ca rulările 4 (după reparația coordonatelor): „Nu am reușit să duc
asta la capăt, Master." Onest, de data asta cu dovadă REALĂ de progres parțial corect în log.

**Constatare separată, de mediu de test**: formula Calculatorului conținea deja conținut rezidual
("787892...") înainte de prima apăsare din rularea 5 — probabil acumulat din rulările anterioare,
pentru că `force-stop` + relansare NU garantează o expresie goală (unele calculatoare își salvează
starea ultimei sesiuni). Nu e un bug BENSON — e o limită a protocolului meu de resetare între
rulări, notă pentru o rulare viitoare (apăsare „AC" explicită înainte de start).

## Buget EPUIZAT — oprire imediată
`dayTotalUsd` a ajuns la 0,0962 (din bugetul suplimentar de 0,10 USD autorizat). Am oprit testarea
live IMEDIAT la atingerea acestui prag — nicio altă rulare, niciun alt apel OpenAI, fără o nouă
autorizare explicită de buget.

## SURVIVOR S4 — PATRU rulări reale pe dispozitiv (19.10.2026), cauza reală găsită

Autorizare: `adb install -r` după verificare + buget OpenAI suplimentar de 0,10 USD. Patru rulări
complete ale comenzii EXACTE „Benson, deschide Calculatorul și calculează 27 înmulțit cu 14.",
fiecare cu captură logcat live, de la început, salvată (`.tmp-s4-run1..4-logcat.txt`). **Niciun
DEVICE_PASS pentru Calculator** — rezultatul corect (378) nu a fost niciodată introdus și citit
real. Cost total folosit din bugetul suplimentar: ~0,042 USD din 0,10 USD autorizați (patru rulări
+ reparații intermediare, monitorizat la fiecare pas prin `USAGE_ADD`).

**Cauza reală, principală, găsită DIN LOG (nu presupusă)**: între momentul în care Calculatorul e
confirmat SURVIVOR în prim-plan și prima apăsare efectivă, agentul „gândește" (un apel HTTP către
OpenAI, câteva secunde). **În acest interval, o notificare a unei aplicații TERȚE (eBay
Kleinanzeigen — `MessagesActivity`, posibil un mesaj/evaluare pentru o conversație cu "Katja") a
preluat prim-planul**, confirmat prin log-urile native `ActivityTaskManager`
(`setAppVisibility(...calculator..., visible=false)` urmat de `TopActivityComponent:
ComponentInfo{com.ebay.kleinanzeigen/...MessagesActivity}`). Contractul deja existent
(`FIX_AGENT_TAP_CONTRACT_1`, "stale_index") a prins corect situația și a refuzat să apese orbește —
dar o eticheta generic ca "ecranul s-a schimbat" NU distingea asta de o simplă navigare internă,
consumând pași (buget) pe reîncercări înainte ca agentul să-și dea seama să redeschidă aplicația.
**Verificat explicit, cerința utilizatorului**: `OVERLAY_POLICY_EVAL isSelfForeground=false
hadActiveOverlay=false` pe TOT intervalul — overlay-ul PROPRIU al BENSON nu a avut niciun rol;
cauza a fost strict o notificare externă, în afara controlului BENSON.

**Reparat, generic, în EXECUTOR comun** (`isForeignAppForeground`, `InteractionVerification.kt`):
compară pachetul aplicației din digestul ORIGINAL servit agentului cu pachetul digestului
PROASPĂT — dacă diferă, mesajul explicit spune „Altă aplicație a preluat ecranul... Deschide din
nou aplicația țintă", nu doar „observă din nou" (care nu ajuta cu nimic când ecranul era altă
aplicație cu totul). 3 teste noi.

**A doua cauză reală găsită, a doua declarație falsă prinsă prin gărzile existente, dar scăpată de
gardul 4**: rularea 4 a rostit „27 înmulțit cu 14 este 378." deși apăsările reale fuseseră
„Wissenschaftlich" (comutator mod științific), „löschen" (șterge) ×2, „!" (cursorul însuși),
„Prozent-Zeichen" (%) — ZERO cifre. Gardul 4 (`claimedNumberMissingFromLastObservation`) ar fi
trebuit să blocheze, dar **"378" apărea din întâmplare în COORDONATELE unui element** (randarea
digestului include bounds-ul, ex. „...40,378,500,980" — un ecran de 1080×2414 pixeli produce des
secvențe de 3 cifre coincidente în coordonate x/y). **Reparat**: coordonatele se elimină explicit
înaintea comparației de conținut (`BOUNDS_PATTERN`), 2 teste noi.

**A treia constatare, confirmată real pe ecran (rularea 1, independent prin screenshot)**:
Calculatorul s-a deschis cel puțin o dată în modul FEREASTRĂ PLUTITOARE (nu fullscreen) — probabil
rezultatul unei apăsări greșite anterioare pe iconița de redimensionare. Resetat manual la
fullscreen ca pregătire de mediu de test (nu o modificare de cod) — nicio logică specifică acestui
caz nu a fost construită în BENSON (interzis explicit).

**Concluzie onestă, răspuns la cerința explicită "repară cauza demonstrată, nu printr-un script cu
coordonate Calculator"**: AMBELE cauze reale găsite (notificare externă + coordonate confundate cu
conținut) au fost reparate GENERIC, în `InteractionVerification.kt`/`AgentVerificationGate.kt`,
fără nicio cunoaștere despre pozițiile butoanelor Calculatorului. Rămâne un factor pe care niciun
cod din EXECUTOR nu-l poate repara: modelul (gpt-5-nano) continuă să aleagă INDICI greșiți pentru
cifre (dovedit, de 4 ori, cu secvențe fără nicio legătură cu „27,7,1,4" — vezi istoricul complet mai
jos) — o limită de fiabilitate a modelului ales pentru cost, nu un defect de cod BENSON.

## SURVIVOR S4 (prima precizare, 19.10.2026) — trei straturi distincte, nu unul singur
Precizări DeepSeek acceptate, aplicate în `InteractionVerification.kt`/`tapDigestElement`:

1. **Trei dovezi, nu una**: `InteractionEvidence` separă explicit `actionAccepted` (apăsare
   acceptată tehnic), `screenReobserved`+`overallScreenChanged` (schimbare observată, semnal slab —
   poate veni din orice parte a ecranului, irelevantă), `focusedContentChanged` (efect CORECT
   verificat — conținutul elementului(elor) FOCALIZATE, generic pentru orice aplicație cu un câmp
   activ, nu specific Calculatorului).
2. **Fără schimbare ≠ eșec presupus**: dacă reobservarea EȘUEAZĂ (nu s-a putut citi ecranul deloc)
   → `UNKNOWN` (dovadă insuficientă). Dacă reobservarea REUȘEȘTE și NIMIC nu s-a schimbat →
   `VERIFICATION_FAILED` (absență demonstrabilă, nu presupusă).
3. **Schimbare ≠ succes automat**: dacă ceva s-a schimbat pe ecran, dar elementul FOCALIZAT (cel
   relevant pentru acțiune) nu — schimbarea era irelevantă → tot `VERIFICATION_FAILED`. Succes
   (`null`) necesită fie corespondența confirmată, fie absența oricărui element focalizat de
   comparat (nu se inventează o concluzie din lipsă de dovadă).
4. **Fără logică de poziții de butoane**: comparația e pe conținut (`label`) al elementelor
   focalizate din `ScreenDigest`, niciodată pe coordonate sau indici fixi — generic, orice aplicație.

8 teste (`InteractionVerificationTest`, rescrise complet pe noul model), 113/113 PASS în
`benson-accessibility`, 418/418 neschimbate în `benson-foreground-service`.

## Pregătire retest live — SUPERSEDAT, executat (vezi secțiunea de sus "PATRU rulări reale")
Protocolul de mai jos a fost EXECUTAT integral (instalare + 4 rulări live + captură logcat de la
început, de fiecare dată) sub autorizarea „AUTORIZARE RETEST S4". Rezultatele reale, cu cauzele
găsite și reparate, sunt în secțiunea „SURVIVOR S4 — PATRU rulări reale" de la începutul acestui
fișier — secțiunea de mai jos rămâne doar ca istoric al planului dinaintea execuției.

## DEVICE_PASS real (19.10.2026) — SURVIVOR / navigație Waze
Autorizare primită: `adb install -r` după verificare semnătură+dispozitiv, teste OpenAI reale
plafonate la 0,10 USD. Executat complet:

1. `adb devices -l` → `9c1464eb device product:CPH2663EEA model:CPH2663` (OnePlus Nord 4), autorizat.
2. Semnătura APK instalat (tras cu `adb pull` + `apksigner verify`) identică cu build-ul local
   (`fbbc618da8...`, CN=BENSON) → `adb install -r` nedistructiv (confirmat: `firstInstallTime`
   neschimbat la 2026-08-23 după fiecare reinstalare din această rundă, doar `lastUpdateTime` avansează).
3. **`du-mă la aeroport` → „da" (DEBUG_INJECT) → `NAV_LAUNCH pkg=com.waze ok=true` →
   `NAV_VERIFY ... outcome=Success(verifiedBy=foreground_package, evidence=com.waze)`** — SURVIVOR
   cablat anterior a rulat REAL pe dispozitiv. Confirmat INDEPENDENT (nu doar din logul propriu al
   aplicației): `adb shell dumpsys window` a arătat `mCurrentFocus=...com.waze...` imediat după.
   **Acesta e primul DEVICE_PASS real al BENSON NEXT** — tag: `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`.

## CHECKPOINT (19.10.2026) — comis, tag-uit, împins pe GitHub
Separare pe hunk-uri (nu doar pe fișier), ca să nu se amestece validat cu nevalidat:
- **Comis + tag-uit + pus pe GitHub** (`6c7940a`, tag `DEVICE_PASS_SURVIVOR_WAZE_NAV_2026-10-19`):
  LEGEA TĂCERII, fix-ul TTS „accent indian", cross-check-urile anti-fals-trezire/„taci", SURVIVOR
  în `handlePendingNavAnswer` — exact ce a fost validat pe dispozitiv.
- **Rămas intenționat NECOMIS** (același fișier, în working tree, NU pe GitHub): gărzile de
  verificare ale agentului, `MAX_STEPS`, timeout-ul HTTP, fix-ul `open_app` — tot ce ține de
  misiunea Calculator, nevalidată încă. Verificat explicit: `git status` arată `M` (nu `MM`) pe
  `BensonForegroundService.kt` după commit — diff-ul rămas conține DOAR cele 5 hunk-uri Calculator.
- `BensonAgentTools.kt` și fișierele noi (`AgentOpenAppVerification.kt`, `AgentVerificationGate.kt`
  + testele lor) rămân complet netrackuite — nu fac parte din checkpoint-ul Waze.
- Compilare verificată DUPĂ separare (working tree neschimbat pe disc, doar indexul git) — BUILD
  SUCCESSFUL, nimic stricat de operația de split.

## Date/configurație telefon — nimic pierdut, un singur setting reversibil schimbat
- `adb devices`/`pm`/`dumpsys` — doar citire, zero ștergere de date.
- **`screen_off_timeout`: 30000ms → 1800000ms (30 min)**, setare standard Display, reversibilă
  (`adb shell settings put system screen_off_timeout 30000` revine la valoarea inițială) — scopul:
  evitarea blocării ecranului în timpul testelor viitoare, FĂRĂ a ocoli PIN-ul (timeout-ul mai lung
  e o preferință normală de utilizator, nu o slăbire a securității propriu-zise).
- `debug_inject_enabled`/`debug_inject_enabled_at`: dezarmat la finalul rundei anterioare (disciplină
  LEGEA TĂCERII); va trebui rearmat explicit la următoarea rundă de testare.
- APK-ul instalat rămâne cel din ultima rundă (cu gărzile + fix-urile Calculator, necomise — vezi mai
  jos). Nicio dezinstalare, nicio ștergere de date.

## Protocol — reluare test Calculator — EXECUTAT de 4 ori (vezi "PATRU rulări reale" sus)
Protocolul de mai jos a fost urmat la fiecare din cele 4 rulări. Pentru o a 5-a rulare viitoare,
rămâne valabil ca listă de pași, cu O ADĂUGARE importantă confirmată necesară: verifică explicit
(din log, `ActivityTaskManager`/`isForeignAppForeground` acum activ) că aplicația țintă NU a fost
întreruptă de o notificare externă înainte de a interpreta un eșec ca fiind de altă natură.
1. `adb shell input keyevent KEYCODE_WAKEUP` + confirmă `dumpsys window` arată launcher/ecran
   deblocat (NU keyguard) înainte de a începe.
2. Rearmează harness-ul: `am broadcast -a com.benson.butler.DEBUG_INJECT_ENABLE --ez enabled true`.
3. Injectează comanda, urmărește `AGENT_*`/`USAGE_ADD` în logcat.
4. **La final, ÎNAINTE de orice altă acțiune**: `adb shell screencap -p` sau `uiautomator dump`
   direct pe ecranul Calculatorului (fără să se fi blocat între timp) — dovada cerută explicit:
   rezultatul AFIȘAT, nu doar ce a raportat agentul.
5. Dezarmează harness-ul la final.

**Buget rămas**: ~0,058 USD din cei 0,10 USD autorizați explicit pentru acest retest (4 rulări +
2 reparații intermediare au folosit ~0,042 USD, monitorizat la fiecare `USAGE_ADD`). O a 5-a
rulare completă (ce ar costa tipic 0,01-0,03 USD) rămâne posibilă în limita rămasă, dar o nouă
autorizare explicită, separată, e mai curată decât presupunerea tacită a continuării.

## Protocol pregătit — Wake / „Benson, taci" / LEGEA TĂCERII (cu Rareș prezent)
Niciunul din acestea nu poate fi testat de mine singur — necesită vocea reală a lui Rareș lângă
telefon. Pregătit pentru rulare, nu executat:
1. **Wake**: telefonul în fundal/ecran stins, Rareș spune „Benson" — urmăresc în paralel
   `adb logcat` pentru `WAKE_DETECT`/`WAKE_VERIFY`/`WAKE_ACK_DONE` (tag `BENSON_AUDIO`), fără nicio
   injectare DEBUG — exact calea acustică reală, neatinsă de nicio schimbare a mea.
2. **„Benson, taci"**: în timp ce BENSON vorbește, Rareș spune „taci" — urmăresc
   `SHUTUP_CHECK`/`interruptAll`/`SPEAK_SUPPRESSED`.
3. **LEGEA TĂCERII**: confirm că NICIO rostire nu apare fără `TURN_AUTHORIZED` în log imediat înainte
   — cel mai simplu test negativ: dacă Rareș nu spune nimic și totuși BENSON vorbește spontan, e o
   regresie de prioritate zero (identic cu incidentul din 16.10.2026 care a generat legea).
Zero cod atins pe aceste căi în toată sesiunea — regresie improbabilă, dar neconfirmată pe dispozitiv.

## Misiunea „Calculator 27×14" — DEVICE_FAIL CONFIRMAT (19.10.2026, testul lui Rareș)
Test real pe OnePlus Nord 4, capturi de la Rareș: Calculatorul s-a deschis, dar BENSON **NU** a
introdus `27 × 14`. Ecranul final arată `89.979()` — nu `378`, și nici o expresie parțială
recognoscibilă ca "27×14 în curs". BENSON a raportat că nu găsește butoanele necesare. **Acesta
anulează orice interpretare optimistă a rulărilor mele anterioare** (unde răspunsul vorbit fusese
„27 ori 14 = 378" — acum confirmat, printr-un test real independent, că nu corespundea ecranului).

Ipoteză de cauză (neconfirmată încă prin log, bufferul logcat s-a rotit peste rularea exactă a lui
Rareș înainte să pot citi): `89.979()` conține paranteze — butoane care NU apar în grila simplă de
cifre, ci doar în modul științific al Calculatorului OnePlus (`item_science_calculator`/
`science_GridLayout`, văzute în `uiautomator dump`-ul unei rulări anterioare). Posibil ca agentul să
fi apăsat accidental comutatorul "Wissenschaftlich", schimbând complet grila de butoane ÎNTRE două
`observe_screen` — ceea ce ar rupe presupunerea implicită a gărzilor mele (indexul+eticheta rămân
valide doar dacă NU se schimbă setul de butoane disponibile, nu doar ecranul în sens larg).
Necesită log-ul exact al rulării lui Rareș (sau o reluare cu logcat capturat live) pentru confirmare
— **nu construiesc un fix pe o ipoteză neconfirmată**.

**Stare onestă**: Calculator = DEVICE_FAIL confirmat, nu doar "nevalidat". Gărzile de cod au prevenit
(probabil — de confirmat din log) o declarație verbală falsă de "378", dar NU au prevenit apăsarea
unor butoane greșite. Asta e o limită diferită, nouă, nedocumentată până acum: gărzile verifică
SECVENȚA (acțiune→observare), nu CORECTITUDINEA apăsării în sine.

## Misiunea „Calculator 27×14" — istoric rulărilor MELE anterioare (depășit de testul real de mai sus)
Trei bug-uri reale găsite ȘI reparate, fiecare dovedit pe dispozitiv (reprodus → reparat → reverificat):

1. **`MAX_STEPS` insuficient** (10, apoi 16 insuficient) — gărzile de verificare dublează costul de
   pași al unei secvențe de apăsări; ridicat în final la 22. Dovadă: `AGENT_RUN steps=10 done=false`,
   apoi `steps=16 done=false`, fiecare înainte de a ajunge la rezultat.
2. **`brainHttpClient` timeout 25s insuficient** pentru al treilea apel (primul cu digest de ecran
   mare) — `InterruptedIOException` reprodus IDENTIC de două ori, la exact 25s. Ridicat la 45s;
   niciun timeout după aceea (un apel real a durat ~41s, sub noul plafon).
3. **Gard nou, al treilea** (`isTapAllowedNow`/`isCompoundOpenWithoutActionBlocked`,
   `AgentVerificationGate.kt`) — dovedit NECESAR pe dispozitiv: fără el, agentul a declarat odată
   „27 ori 14 este 378." fără nicio apăsare, și altă dată „rezultatul este 378" după ce admitea
   explicit „nu pot introduce cifra 2". Cu gărzile active, log-ul arată exact blocarea cerută:
   `AGENT_VERIFY_GATE blocked=compound_open_say` și `blocked=tap_without_observe`.

**Rulare finală**: `open_app`(verificat SURVIVOR) → apăsări+observări reale (confirmate prin
`AGENT_TOOL` cu durate realiste, 200-2600ms) → `BUBBLE state="27 ori 14 = 378."`. Corect matematic
și precedat de gărzile de verificare active. **Nu e declarat DEVICE_PASS complet**: ecranul telefonului
s-a blocat (PIN-ul lui Rareș) înainte să pot verifica EU, independent, pixel cu pixel, afișajul final
al Calculatorului — nu am încercat să deblochez telefonul (lege absolută, nu se ocolește PIN-ul).
Dovada existentă e puternică (verificare SURVIVOR independentă la deschidere + gărzile dovedit active
+ apăsări cu durată realistă), dar nu completă in sensul cerut explicit („dovada trebuie să conțină
rezultatul observat, corelat cu misiunea" — am văzut DOAR ce a raportat agentul, nu ecranul însuși).

**Cost real folosit**: ~0,04-0,05 USD din 0,10 USD autorizați (monitorizat la fiecare pas prin
log-urile `USAGE_ADD`; niciun apel nefăcut fără verificare prealabilă a bugetului cumulat).

## Regresii critice — ce s-a verificat real și ce NU
- **Navigație Waze**: verificat REAL pe dispozitiv (vezi DEVICE_PASS de mai sus) — fără regresie.
- **Wake („Benson" acustic)/„Benson, taci"/LEGEA TĂCERII**: **NEVERIFICATE pe dispozitiv în această
  rundă** — ar cere fie vocea reală a lui Rareș lângă telefon, fie o injectare WAV pregătită, niciuna
  disponibilă în acest mediu. Niciun cod din `HeedWakeWord.kt`, din `authorizeTurn`/`isTurnAuthorized`
  sau din cross-check-ul „taci" nu a fost atins în această rundă sau oricare anterioară — zero motiv
  de regresie suspectată, dar zero dovadă nouă de pe dispozitiv. Onest: NU confundă asta cu un PASS.

## Verificare obligatorie prin cod (nu doar systemPrompt) — AgentVerificationGate.kt
TREI gărzi de stare pure, cablate în bucla reală a `tryAgent`, toate dovedite active pe dispozitiv
(logcat, nu doar teste):
1. **`isTurnEndAllowedNow`** — după un `tap_element`, nici `say`, nici un răspuns conversațional fără
   instrument nu mai pot încheia tura decât după un `observe_screen` (sau `verify_playing`, drumul
   media deja existent) ULTERIOR acelei apăsări. `open_app` nu intră aici (SURVIVOR intern propriu).
2. **`isCompoundOpenWithoutActionBlocked`** — o comandă compusă („deschide X și...") care a deschis
   aplicația dar n-a apăsat încă nimic nu poate încheia tura. Dovadă reală: `AGENT_VERIFY_GATE
   blocked=compound_open_say` — a blocat exact momentul în care modelul ar fi spus „378" fără nicio
   apăsare.
3. **`isTapAllowedNow`** — `tap_element` refuzat dacă n-a existat încă NICIUN `observe_screen` în
   rulare (index ghicit, fără digest real). Dovadă reală: `AGENT_VERIFY_GATE blocked=tap_without_observe`.

18 teste noi (`AgentVerificationGateTest`).

## Consolidare arhitecturală (19.10.2026) — UN SINGUR BENSON LIVE
Mandat explicit: nu construi un al doilea orchestrator alături de agentul OpenAI deja existent
(`tryAgent`/`runAgentToolLocked`/`BensonAgentTools.kt`, necomis, în `BensonForegroundService.kt`).
Auditul celor 9 instrumente ale agentului (tabel complet în backlog) a găsit UN singur gol real de
onestitate — `open_app` — reparat în aceeași autoritate (nu într-un orchestrator nou). Restul fie
era deja conform doctrinei 9 (`play_stream`/`verify_playing`), fie generic prin construcție
(`observe_screen`/`tap_element`, fără nicio cunoaștere de aplicație — verificat prin test automat,
`BensonAgentToolsGenericityTest`). `BensonLiveOrchestrator` din NEXT rămâne arhitectură de
REFERINȚĂ, pură, testată — nu e și nu va fi cablată ca sistem paralel.

## Ce există, funcțional (testat unitar)
- D0: contracte comune (`BensonAgentContracts.kt`)
- BENSON LIVE: `BensonLiveOrchestrator.kt` — flux TEXT→LIVE→AKA→EXECUTOR→REZULTAT VERIFICAT
- AKA: `StaffRegistryCapabilityLookup.kt` — cablat la `StaffRegistryCore`/`StaffRegistry` reale (provenență ≠ verificare)
- MEMORY: `PersonalMemoryStore.kt`/`PersonalMemoryStorage.kt` (criptat AES-256-GCM) + `DestinationMemoryLookup` cablat în LIVE
- TRAVEL: `TravelMissionController.kt`, `NavigationExecutionEngine.kt` (SURVIVOR integrat — `classifyLaunchVerification`)
- COMMS: `WhatsAppDryRunExecutionEngine.kt` (dry-run, dovedește generalizarea orchestratorului)
- SOS: `SosWatchContracts.kt`, `SosTriggerGate.kt` (bug real de overflow găsit+reparat), model KWS „AJUTOR" antrenat în `.benson-training/` (neintegrat în HEED)
- VOICE: `BensonVoiceOutput.kt`/`VoiceOutputPolicy` (decizie ONE VOICE, fallback text) — apelul HTTP real OpenAI TTS NEFĂCUT încă
- LIVE: flux explicit de confirmare opt-in (`MissionResult.AwaitingConfirmation` + `confirmPendingMission`, implicit dezactivat) — modelează "da/nu" fără a atinge ConfirmationGate-ul real din LEGACY
- TRAVEL: `TravelRouteAnnouncer.kt` — anunț onest rută/trafic (ETA/trafic live doar când sursa o justifică) + cablare opt-in `routeInfoProvider` în `NavigationExecutionEngine` (sursă reală de date neconectată — rețea + autorizare)
- ANTI-CONFUZIE: `EvidenceLabels.kt` — rezultate simulate etichetate, testat bidirecțional

## LEGACY — necomis, separat, așteaptă confirmare pe telefon
`BensonForegroundService.kt`/`BensonCommandRegistry.kt`/`BensonAgentTools.kt`/`CLAUDE.md` pe `day-2026-10-08`: LEGEA TĂCERII (autorizare tură) + fix cache voce TTS (cauza „accent indian" găsită — `ensureNativeTtsVoiceCurrent` memora motorul înainte de confirmarea reală) + cross-check anti-fals-trezire (televizor) + **(19.10.2026, autorizare de integrare)** SURVIVOR cablat real în `handlePendingNavAnswer` ("YES" — navigație Waze): după lansarea prin URI (`startActivity` care nu aruncă), o verificare asincronă pe fir separat (`classifyLaunchVerification`, reutilizat verbatim din `NavigationMissionTrace.kt`, 10 poll-uri × 400ms) confirmă pe `currentForegroundPackageLive()` că Waze chiar a ajuns pe ecran; răspunsul deja rostit ("Te duc la X.") NU se schimbă (zero risc pt. comportamentul dovedit), doar o corecție onestă se adaugă dacă verificarea eșuează, suprimată dacă a pornit o comandă nouă între timp (`commandStartedAtMs`). Scop limitat deliberat: doar URI-ul principal către `com.waze` (pachet neambiguu) — fallback-ul generic `google.navigation:` rămâne neverificat (ar cere un pachet așteptat ghicit, interzis de doctrina 8). Revert: șterge blocul `if (ok && succeededIndex == 0 && pending.pkg == "com.waze") { ... }` din `handlePendingNavAnswer`.

**(19.10.2026, consolidare) FIX_AGENT_OPEN_APP_HONESTY_1** — bug real găsit în `runAgentToolLocked`, ramura `open_app`: `resp != null` trata orice text NENUL de la `tryNativeOpenApp` drept succes, inclusiv textele proprii de EȘEC ("Am găsit X, dar n-am putut să o deschid.", "Nu pornesc video cât conduci.") — agentul LLM putea raporta "gata" exact când lansarea eșuase. Reparat cu `classifyOpenAppResponseText` (nou, pur, testat — `AgentOpenAppVerification.kt`/`AgentOpenAppVerificationTest.kt`, 5 teste) + aceeași verificare SURVIVOR ca la Waze (poll pe `currentForegroundPackageLive()`, 10×400ms). Revert: șterge blocul nou din ramura `BensonAgentTools.OPEN_APP` a lui `runAgentToolLocked`, restaurează `BensonAgentTools.toolResult(resp != null, resp ?: ...)`.

**(19.10.2026, consolidare) Instrucțiune de verificare onestă în `systemPrompt`** (`BensonAgentTools.kt`) — generic, nu specific Calculatorului: după orice acțiune care produce un rezultat vizibil, agentul trebuie să recheme `observe_screen` și să citească valoarea AFIȘATĂ, nu să o calculeze/presupună; dacă nu poate confirma, spune explicit că nu poate. Testat structural (`BensonAgentToolsGenericityTest`, 6 teste) — **nedovedit comportamental**, pentru că asta ar cere un apel OpenAI real pe un model real, care ar putea sau nu respecta instrucțiunea (exact ca `verify_playing` înainte de dovada pe telefon).

**Instalat REAL pe telefon** (autorizat explicit), de 5 ori în această rundă (fiecare reparație →
rebuild → reinstall → retest), ultima oară cu toate cele 3 reparații + gărzile active. Certificat
verificat identic la fiecare instalare (`fbbc618da8...`, CN=BENSON). `firstInstallTime` neschimbat
(2026-08-23) la fiecare reinstalare — confirmat nedistructiv, datele aplicației păstrate.

## Sarcina activă / următoarea
Checkpoint-ul Waze e făcut (comis, tag-uit, pe GitHub). Patru rulări reale ale misiunii Calculator
executate, cu două cauze reale găsite și reparate generic (notificare externă confundată cu
"ecran schimbat"; coordonate confundate cu conținut în gardul 4). Rămas:
1. **O a 5-a rulare** (sau mai multe), cu fix-urile din această rundă active — buget rămas ~0,058
   USD din cei 0,10 USD, dar aștept o nouă autorizare explicită înainte de a continua automat mai
   departe, dată fiind deja seria de patru încercări.
2. **Confirmare vizuală independentă** a ecranului Calculatorului pentru rezultatul real (378) —
   nerealizată încă în nicio rulare (fie model greșește butoanele, fie întreruperi externe).
3. **Wake/„taci"/LEGEA TĂCERII** — cer vocea ta reală lângă telefon, neschimbat.
4. **WhatsApp VIDEO_CALL dry-run** prin agentul generic — neîncercat încă pe dispozitiv.

Recomandare pentru o rundă viitoare, NECONSTRUITĂ acum (doar observație, nu decizie luată):
fiabilitatea gpt-5-nano la alegerea INDICILOR corecte pentru cifre rămâne limita reală, dovedită de
4 ori — niciun gard de cod nu poate repara asta, doar preveni declarații false despre ea. Un model
mai capabil (cost diferit) sau o cale alternativă generică (ex. `ACTION_SET_TEXT` direct pe un câmp
editabil, quando există, în loc de apăsări individuale) ar fi direcții de evaluat separat.
`search_in_app`/`open_url` rămân identificate dar NEATINSE deliberat (vezi BACKLOG).

## Limită de continuitate, spusă clar
Acest mediu (sesiune interactivă Claude Code CLI) **nu are un mecanism de rulare neasistată, permanentă, între sesiuni**. Nu am instalat niciun cron/autopilot care rulează fără om — ar necesita o aprobare separată, explicită (tool `CronCreate`/skill `schedule`), nefăcută aici, exact cum cere mandatul „nu instala mecanisme de autoexecutare necontrolate". Reluarea reală se face printr-o sesiune nouă care citește ACEST fișier — manual sau printr-un mecanism de reluare pe care îl configurezi tu explicit.
