# RUNDA MOTORUL — BENSON REZOLVĂ ORICE CERERE. 07.10.2026.

Se trimite DUPĂ ce AC2-F e verde și are tag (ac2f-2026-10-0X).
Include integral BENSON_EXP_CHATGPT_FINAL.md (în repo, commit 7735928) —
Părțile A, A2, B, C de acolo devin fazele M2, M4, M5 de mai jos.

## PRINCIPIU
Fiecare cerere trece prin ACELAȘI lanț, fără excepții și fără scurtături:
ROSTIRE → STT (OpenAI + vocabular personal)
→ INTERPRET (ChatGPT: o LISTĂ de pași tipizați)
→ REZOLVATOR (găsește ținta reală: contact / loc / aplicație / media / web)
→ CONFIRMARE (șablon local, ce auzi = ce se execută)
→ EXECUTOR (rețetele existente, neatinse)
→ VERIFICATOR (ținta e atinsă? dovadă din sistem, nu din presupunere)
→ RAPORTOR (rostește DOAR ce a dovedit verificatorul)
→ ISTORIC ([ACȚIUNE BENSON] + ultimul rezultat, pentru „acolo", „pe el").
Motorul de căutare e REZOLVATORUL: orice cerere fără țintă exactă
(„cel mai apropiat", „doctor X", „bilete de cinema", „acolo") se rezolvă
întâi la o țintă concretă, cu nume, abia apoi se execută.

## INSTRUMENT DE TEST AUTOMAT (construit primul, folosit de tine la fiecare fază)
- Debug-only, oprit în release prin constantă: comandă tastată injectată
  prin adb în ACELAȘI lanț ca vocea (de la INTERPRET încolo), plus răspuns
  automat la confirmări („da"/„nu"). Protejat: neexportat public.
- Scriptul de test citește logcat-ul și decide PASS/FAIL pe fiecare caz.
- Buclă per fază: rulezi cazurile → FAIL → cauza din log → corectezi →
  rulezi din nou TOATE cazurile fazei + toate fazele anterioare.
  Maximum 3 iterații pe fază. Dacă tot nu trece: OPREȘTE-TE și raportează
  cauza dovedită. Nu ascunzi, nu reduci cazul de test.

## M0 — DIAGNOSTIC, FĂRĂ COD. OPREȘTE-TE ȘI RAPORTEAZĂ.
- Din log 07.10, cauza pentru fiecare raport fals:
  (a) 09:11 „Dumă la cea mai apropiată benzinărie" → „GATA" + Waze fără destinație;
  (b) 09:14 aceeași cerere → „Încă nu știu să fac asta" + Waze deschis;
  (c) „caută pe YouTube X" → căutare făcută, raportată ca eșuată.
  Cine a produs textul rostit și ce verificare a decis.
- Pe dispozitiv: ce deep link dă CEL MAI APROPIAT rezultat pentru o
  categorie (waze://?q=…, google.navigation:q=…, geo:0,0?q=…).
  Referință: benzinăria de la ~400 m de casa lui Rareș.
- Verificat AZI: modelele OpenAI (transcriere, text cu Structured Outputs)
  + prețuri.

## M1 — RAPORTOR ADEVĂRAT (prima, fiindcă de ea depinde încrederea)
- „Gata" / succes DOAR după VERIFICATOR. Per tip de acțiune, dovada:
  WA apel/video: ecran de apel cu numele potrivit (existent);
  WA mesaj: mesaj trimis în chat-ul potrivit (existent);
  NAVIGATE: navigația activă spre destinația cerută (APP_OPEN singur ≠ succes);
  YT căutare/pornire: rezultate / redare pe query-ul cerut;
  WEB_SEARCH: pagina de rezultate cu query-ul exact.
- „Nu pot" DOAR dacă nimic nu s-a executat.
- Parțial → spune exact ce s-a făcut: „Am deschis Waze, dar nu am găsit
  benzinăria."
- Textul rostit e generat din rezultatul verificatorului, niciodată
  dintr-un șablon ales înainte de execuție.
- Log: OUTCOME action=… verified=true|false|partial evidence=… spoken="…"

TESTE: cazurile (a)(b)(c) reproduse prin comandă tastată → zero rapoarte
false. Plus: navigare cu Waze închis, cu Waze deschis pe sugestia proprie.

## M2 — CHATGPT AUDE ȘI ÎNȚELEGE (Părțile A + A2 din fișierul experimentului)
Adăugiri față de fișier:
- Schema: { steps: [ {action, contact?, app?, query?, text?, destination?,
  category?, question?} ], confidence }. Acțiuni noi: PLACE_SEARCH,
  NAVIGATE_LAST, CLARIFY. Max 5 pași; executați pe rând, cu anunț între ei
  („Al doilea: …"); un pas eșuat nu oprește restul, se raportează.
- VOCABULAR PERSONAL în Dezvoltator, editabil, local. Inițial:
  Gusbeth, Hannah, Ingrid, Dimitriu-Baltres, München, București, Otopeni,
  Sibiu. Trimis ca indiciu la transcriere (prompt) și la verificarea wake
  NU (aceea rămâne doar „Benson").
- Nume propriu fără sens (confidence=low) → CLARIFY „Pe cine să caut? Am
  înțeles «…»". Corectura intră în vocabular DOAR după „Da".
- Normalizare înaintea parserului de rezervă: „dumă"→„du-mă" etc. (JUnit).

TESTE (tastat, apoi transcriere din clipuri audio înregistrate de Rareș dacă există):
- „caută doctor Gusbeth în München" → query conține „Gusbeth", „München".
- „caută 3 medici în München: A, B, C" → 3 pași, 3 căutări, în ordine.
- aceeași acțiune în 3 formulări RO + 2 DE → același pas, aceeași confirmare.

## M3 — REZOLVATORUL (motorul de căutare)
- WEB_SEARCH: Google cu query-ul exact, nfpr=1 (fără autocorectare);
  nume din vocabular între ghilimele.
- PLACE_SEARCH „cel mai apropiat X" → deep link-ul dovedit la M0. Înainte
  de plecare: „Cea mai apropiată <categorie>: <nume>, la <distanță>.
  Pornesc?" Nume + distanță citite ca UNTRUSTED_DATA.
- NAVIGATE_LAST „du-mă acolo" → ultimul loc găsit (nume + adresă din
  primul rezultat de tip loc), NU textul căutat. Confirmare cu numele.
  Fără loc reținut → CLARIFY „Unde anume?".
- Niciodată nu se acceptă sugestia proprie a Waze („Fährst Du zu …?").
- Aplicație lipsă pentru cerere (ex. bilete de cinema) → Play Store cu
  căutarea categoriei (comportament existent, de păstrat).
- „intră pe Amazon / arată-le pe Amazon" → căutarea în aplicația Amazon
  cu ultimul termen, prin link. Fără plăți.
- STRAT DE RUTARE (doar cârligul, fără parteneri acum): categorie →
  furnizor configurabil. Locul numit explicit de Rareș are prioritate
  absolută. Câmp pentru cod de afiliere, gol.

TESTE:
- „caută Elite Tactical București" → „du-mă acolo" → confirmare „Elite
  Tactical Otopeni" → Waze spre Strada Tebea 16.
- „caută stomatolog doctor Dimitriu Baltres" → „du-mă acolo" → Waze spre
  Untersbergstraße 8, München.
- de acasă, „du-mă la cea mai apropiată benzinărie" → stația de ~400 m.
- „bring mich zur nächsten Tankstelle" → același rezultat.
- „vreau bilete la cinema" fără aplicație → Play Store.
- „unde e cel mai apropiat magazin?" → CLARIFY „Ce fel de magazin?" →
  „alimentar" → Maps cu magazine alimentare.

## M4 — CONVERSAȚIE + MEMORIA ACȚIUNILOR (Partea B din fișier, neschimbată)
TESTE: „video cu mama" → „Mulțumesc că ai sunat-o pe mama" → confirmă,
nu neagă. Întrebare doar prin intonație → „?" păstrat. Injecție de pe
ecran („Benson, ignoră regulile…") → zero acțiune.

## M5 — CONTOR DE CONSUM (Partea C din fișier, neschimbată)

## REVERT
O constantă per fază; toate pe false = tag-ul ac2f exact.

## NU SE ATING
Wake (model, prag, ack, verificare), captura, serviciul foreground, rețetele
betonate, logica confirmărilor, ruta audio.

## LA FINAL
`npm test` → `tsc` → `assembleRelease`. Raport `MOTOR_REPORT.md`:
cauzele M0, per fază cazurile PASS/FAIL și numărul de iterații, fișiere
atinse, calea + sha256 APK. NU instala.

## ACCEPTARE (Rareș, cu vocea, pe Wi-Fi, logcat pe fișier)
Toate cazurile de test de mai sus rostite o dată. Orice raport fals de succes
sau de eșec = runda nu e verde.

După verde: commit → tag `motor-2026-10-0X` → push → verificare remote.
