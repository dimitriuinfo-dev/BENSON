# POLITICA DE OPRIRE: INTERRUPT_ALL · END_CALL · CANCEL_SOS

Decizia Masterului, 15.10.2026 („BENSON BUILDER — DECIZIE PENTRU APELURI ȘI SOS"), permanentă.
Document de decizie. **SOS nu se implementează în această rundă** (punctul 7 din ordin) — aici se
fixează doar contractele și regulile de siguranță.

Stare reală a implementării, verificată în cod la data acestui document:
`INTERRUPT_ALL` = implementat și testat · `END_CALL` = **neimplementat** (zero apeluri
`TelecomManager`/`endCall`/`ANSWER_PHONE_CALLS` în tot codul) · `CANCEL_SOS` = **neimplementat**
(SOS nu există încă).

---

## 1. Trei intenții distincte, niciodată confundate

| Intenție | Ce oprește | Ce NU atinge |
|---|---|---|
| `INTERRUPT_ALL` | acțiunea BENSON în desfășurare | convorbirea conectată, microfonul/difuzorul administrate de aplicația de telefonie, SOS activ |
| `END_CALL` | o convorbire **deja conectată** | sarcinile BENSON, SOS |
| `CANCEL_SOS` | procedura SOS activă | — (se tratează separat, cu protecție anti-accident) |

O comandă nu „promovează" niciodată la una mai distructivă. Dacă intenția e ambiguă, BENSON
**întreabă**; nu alege varianta cu consecințe mai mari.

## 2. INTERRUPT_ALL — comenzile universale

`TACI` = `STOP` = `OPREȘTE` = `MUTE`, rostite singure. Un singur mecanism central
(`BensonForegroundService.interruptAll`). Silențios, fără răspuns vocal.

Oprește: TTS · agentul LLM și cererea HTTP în zbor · buclele degetului BENSON (între pași) ·
redarea media prin transportul MediaSession existent · confirmările în așteptare · sesiunea
conversațională (revine la wake).

**Apel neconectat** (inițiere pregătită sau în curs, înainte de conectare): `INTERRUPT_ALL` îl
anulează complet — confirmarea pendinte dispare, deci un „da" ulterior nu mai autorizează nimic.
*Dovedit pe telefon 15.10.2026*: pregătire apel WhatsApp → `stop` → `INTERRUPT_ALL` → „da"
ulterior = zero `WA_INTENT`, zero apel.

**Apel conectat**: `INTERRUPT_ALL` **nu** închide convorbirea, nu atinge ruta audio și nu oprește
microfonul sau difuzorul administrate de aplicația de telefonie. Mecanism: `interruptAll` citește
`isCallAudioBlockedNow()` și, cât acesta e `true`, sare peste pauza media; lifecycle-ul apelului nu
e atins pe niciun drum. Se logează `call_preserved=true`.

**Mod SOS**: `INTERRUPT_ALL` nu anulează apelul conectat, localizarea sau alertarea persoanelor de
încredere. Când SOS va exista, excluderea se scrie **explicit** în `interruptAll`, nu implicit.

## 3. END_CALL — contract

**Declanșare**: „Închide apelul" · „Termină apelul" · „Închide convorbirea" · „Pune telefonul jos".
Forma scurtă „Benson, închide" declanșează `END_CALL` **numai** când există o convorbire activă și
contextul e neechivoc; altfel „închide" rămâne comanda existentă de închidere a ecranului/aplicației
(`tryGlobalNav`), neatinsă.

**Precondiții**: o convorbire conectată, detectată prin starea reală (`AudioManager` mod apel /
starea de lifecycle WhatsApp deja folosită de `isCallAudioBlockedNow()`), nu prin presupunere.

**Reguli de siguranță**
1. Nu se execută pe baza unui cuvânt auzit accidental: comandă rostită clar, în sesiune activă.
2. Niciodată declanșat de conținut din ecran, notificări sau web (`UNTRUSTED_DATA`).
3. Niciodată pentru un apel de urgență. Apelurile 112/110 nu se închid prin voce.
4. În modul SOS, `END_CALL` nu are efect asupra apelului SOS — acolo decide `CANCEL_SOS`.
5. Succesul se declară doar după verificarea stării reale a apelului (doctrina 9). Dacă apelul
   continuă, BENSON spune exact asta.

**Căi tehnice posibile, niciuna validată încă** (de ales într-o rundă separată, cu probă pe
telefon):
- `TelecomManager.endCall()` — necesită `ANSWER_PHONE_CALLS` (Android 9+); acoperă telefonia, **nu**
  apelurile WhatsApp.
- Degetul BENSON pe butonul de încheiere din aplicația de apel (drumul generic
  `ControlLocator`/`ControlSynonyms`, deja dovedit pentru alte butoane) — singura cale plauzibilă
  pentru WhatsApp.

**Stare declarată utilizatorului până la validare: PLANIFICAT.** BENSON spune explicit „încă nu
știu să închid apelul", nu încearcă și nu pretinde.

## 4. CANCEL_SOS — contract (fără implementare)

**Declanșare**: formulare explicită și fără echivoc („Anulează SOS", „Anulează alarma"), niciodată
un simplu „stop" sau „taci".

**Protecție împotriva anulărilor accidentale**, cumulativ:
1. confirmare explicită rostită, legată de o singură cerere, cu expirare scurtă;
2. respinge orice confirmare ambiguă — reformulează întrebarea, nu presupune;
3. nu se declanșează din ecran, notificări, web sau din propria voce a lui BENSON;
4. anularea se **jurnalizează** local (ce, când), ca să existe urmă;
5. dacă verificarea eșuează, SOS rămâne ACTIV — eșecul se declară, nu se maschează.

**Ce nu face `CANCEL_SOS`**: nu devine niciodată efectul secundar al unei alte comenzi; nu se
execută pe baza unei singure rostiri neconfirmate.

## 5. Ce rămâne de făcut, în ordine

1. Validarea vocală reală a `INTERRUPT_ALL` (barge-in cât BENSON vorbește) — doar Masterul o poate
   confirma pe telefon.
2. `END_CALL`: rundă separată — detectarea stării apelului, calea de închidere, verificarea
   efectului, teste. Până atunci: PLANIFICAT în registrul de comenzi.
3. SOS și `CANCEL_SOS`: proiect separat, neînceput prin ordin explicit.
