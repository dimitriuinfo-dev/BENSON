# BENSON — CANONUL DE BUILD
**Acest document guverneaza fiecare runda, fara exceptie. Nu e o lista de sugestii — e o
poarta. O runda fara jurnalul de conformare de la finalul acestui document e o runda nula,
indiferent cat de bun e codul din ea.**

Motivul pentru care exista: in doua luni de runde, fiecare regula scrisa in CLAUDE.md a fost
respectata la inceput si uitata pe masura ce sesiunea s-a umplut cu cod si loguri. O regula
citita o data la inceputul sesiunii nu supravietuieste unei sesiuni lungi. O poarta care
blocheaza raportul final, da.

---

## 0. CE TREBUIE SA FACI LA INCEPUTUL FIECAREI RUNDE, INAINTE DE ORICE COD

Copiaza acest tabel gol in fisierul de raport al rundei si completeaza-l pe masura ce
inaintezi. La final, tabelul completat E raportul. Un raport fara acest tabel e incomplet.

```
CANON LOG — runda: <nume rundă> — data: <data>

[ ] 0.1  Scope lock citit si inteles — fisierele permise: <listeaza-le aici, copiate din prompt>
[ ] 0.2  Fisiere interzise citite — daca vreo sarcina cere unul: STOP, scris in raport, nu atins
[ ] 0.3  Un singur tip de schimbare declarat AICI, inainte de a scrie cod: <declara-l>
[ ] 1.1  Harness de regresie RULAT inainte de orice schimbare (stare BASELINE) — coada lipita mai jos
[ ] 1.2  Harness de regresie RULAT dupa schimbare (stare FINALA) — coada lipita mai jos
[ ] 1.3  Compara BASELINE cu FINALA linie cu linie — orice test care trecea si acum pica: STOP,
         nu continua runda, scrie regresia in capul raportului, cu BOLD
[ ] 2.1  npx tsc --noEmit → 0 erori — coada lipita mai jos
[ ] 2.2  gradlew assembleRelease → BUILD SUCCESSFUL — coada lipita mai jos
[ ] 2.3  Certificatul verificat explicit: CN=BENSON, O=TOKKO — comanda si rezultatul, lipite mai jos
[ ] 2.4  SHA-256 al APK-ului calculat AICI, in raport, nu doar promis — hash-ul complet, 64 caractere
[ ] 3.1  Fiecare fisier atins enumerat, cu numarul de linii schimbate
[ ] 3.2  Orice iesire din scope lock, DACA a existat, in prima linie a raportului, cu BOLD
[ ] 3.3  Orice setTimeout/setInterval nou pe o cale care trebuie sa supravietuiasca fundalului:
         enumerat explicit AICI, cu motivul pentru care NU e o problema, sau eliminat
[ ] 4.1  Testul de acceptare descris in prompt — rulat pe dispozitiv, nu doar in cod — rezultat
         lipit mai jos, cu numarul de reusite din numarul de incercari cerute
[ ] 4.2  Daca testul de acceptare a trecut: tag git PROPUS aici, cu numele exact
         (tu confirmi instalarea si testul, eu nu tag-uiesc singur, dar il scriu ca sa nu-l uitam)
```

**Regula tare: daca orice casuta de mai sus ramane nebifata la finalul raportului, raportul
se rescrie inainte de a fi trimis lui Rareș. Un raport cu casuta nebifata nu e un raport
incomplet care se accepta "de data asta" — e o runda care nu s-a terminat.**

---

## 1. HARNESS-UL DE REGRESIE — ce este, si de ce e obligatoriu, nu optional

Pana la construirea lui (Runda H1, mai jos), acest punct din canon se marcheaza
`[ ] 1.x  NEEXISTENT INCA — vezi Runda H1` si se explica in raport de ce nu poate fi bifat.
Dupa ce H1 e livrat si confirmat pe dispozitiv, punctul devine obligatoriu, fara exceptie,
in orice runda ulterioara care atinge cod de executie, brain, sau STT/TTS.

Harness-ul verifica, printr-o singura comanda, cele drumuri deja dovedite pe dispozitiv:

1. Navigatie Waze cu destinatie corecta
2. Deschidere aplicatie prin nume (YouTube sau echivalent, cu link direct)
3. Control Spotify: cauta → reda → verifica → pauza
4. Conversatie cu memorie de tura (doua replici, a doua se refera la prima)
5. Ambiguitate → clarificare → alegere → executie (ex. "deschide radio" → corectura → deschis)
6. Deschidere Calculator (cel mai ieftin test, si primul care trebuie sa treaca mereu)

Fiecare verificare e o comanda `adb shell input text` sau echivalent vocal, urmata de o
asertiune pe o linie de log specifica (ex. `EXEC_TRACE_FOREGROUND_VERIFY confirmedByEvent=true`).
Rezultatul harness-ului e o lista de PASS/FAIL, nu o naratiune.

**Regula de aur:** daca harness-ul arata un FAIL pe ceva ce era PASS acum trei zile, runda se
opreste acolo. Nu se continua cu functia noua peste o regresie nereparata. Regresia se repara
sau se raporteaza ca decizie constienta a ta, niciodata trecuta sub tacere.

---

## 2. RUNDA H1 — CONSTRUIREA HARNESS-ULUI (prima runda dupa acest canon)

### Scope lock

```
Permis:   scripts/regression/**              (director nou)
          H1_REPORT.md                       (fisier nou, radacina)
Interzis: orice fisier de executie, brain, STT/TTS existent — harness-ul CITESTE loguri,
          nu modifica logica aplicatiei. Daca ai nevoie de o linie de log noua ca sa poti
          verifica un pas, adaug-o STRICT ca linie de log, nimic altceva, si numeste fisierul
          si linia in raport.
```

### Task unic

`scripts/regression/run.sh` (sau `.ps1`, dupa mediul lui Rareș — intreaba daca nu e clar din
`ways-of-working.md`):

1. Curata logcat.
2. Pentru fiecare din cele 6 drumuri de mai sus: trimite intrarea (adb shell input text sau
   echivalent), asteapta un timeout rezonabil, citeste logcat, verifica prezenta liniei de
   log care dovedeste succesul (exact liniile deja folosite in testele manuale anterioare —
   `EXEC_TRACE_FOREGROUND_VERIFY`, `WHATSAPP_ASSERT_PACKAGE = PASS` etc. — cauta-le in codul
   existent, nu le inventa).
3. Iesire: tabel PASS/FAIL, un rand per drum, salvat in `scripts/regression/last_run.log`.
4. Cod de iesire diferit de zero daca orice rand e FAIL — asta permite integrarea lui ca
   poarta automata mai tarziu.

### Verificare si acceptare

Rulezi harness-ul o data manual, pe build-ul curent stabil (90c56b8f sau ultimul confirmat).
Toate cele 6 randuri PASS. Daca vreunul FAIL pe build-ul stabil, harness-ul insusi e gresit —
se repara harness-ul, nu aplicatia.

### Raport

`H1_REPORT.md`: liniile de log exacte folosite pentru fiecare din cele 6 verificari, cu
fisierul unde apar in cod; rezultatul rularii pe build-ul stabil; timpul total de rulare.

---

## 3. CE INSEAMNA ASTA PENTRU TINE, DE ACUM INAINTE

Nu tu tii minte canonul. Canonul e in acest fisier, si tu ii ceri lui Code, la inceputul
FIECAREI runde, o singura propozitie:

> **"Aplici BENSON_CANON.md la aceasta runda. Trimite-mi jurnalul completat."**

Daca raportul care revine nu are tabelul de la Sectiunea 0 completat integral, cu hash-uri,
cozi si rezultate reale lipite — nu e un raport valid, indiferent ce spune restul lui. Il
respingi si ceri completarea, exact cum ai cerut recalcularea hash-ului trunchiat.

Asta e diferenta fata de "spune-i sa faca cele 4 puncte": nu ii ceri sa-si aminteasca o
regula, ii ceri un artefact verificabil pe care tu il poti respinge daca lipseste o casuta.
