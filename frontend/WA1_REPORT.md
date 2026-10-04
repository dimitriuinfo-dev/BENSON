# RUNDA WA-1 — WhatsApp nativ (mesaj, apel, video)

Scope: `BensonForegroundService.kt` (parser, rezolvare contact, confirmare, orchestrare) ·
`modules/benson-accessibility/**` (doar citire — zero schimbare, vezi mai jos) ·
`modules/benson-audio-capture/**` (deja refolosit din rundele anterioare, neatins acum) · acest raport.

## Precondiție

`READ_CONTACTS` era deja în manifest — verificat înainte de orice schimbare.

## Decizie de arhitectură: reuse integral pentru execuție

Cercetarea (read-only) a găsit pe `BensonAccessibilityService` trei funcții `suspend` deja
dovedite pe dispozitiv, din runde anterioare (DEVICE_PASS în `git log`: WA2 TASK2, apel WhatsApp):

- `runWhatsAppCallNative(contact: String, mode: "voice_call"|"video_call")` — caută contactul
  direct în UI-ul WhatsApp, apelează, verifică (`nameMatch`, `verifiedHeaderText`).
- `runWhatsAppOpenConversationType(phone, expectedName, message, missionId)` — deschide chat-ul
  prin `whatsapp://send?phone=`, scrie mesajul, verifică textul. **Nu trimite.**
- `pressWhatsAppSendVerified(missionId, message, expectedChat)` — apasă trimite, verifică.

Conform regulii „nu înlocui ce funcționează", runda asta NU reimplementă apelul/mesajul prin
intent-uri `ContactsContract` + MIME (cum descria schița inițială) — orchestrez direct aceste trei
funcții, deja proven. Singurul cod nou e: parserul, rezolvarea contactului (pentru nume corect +
număr de telefon), și poarta de confirmare. `BensonAccessibilityService.kt` în sine **nu a fost
modificat** — doar apelat, prin dependința Gradle `implementation project(":benson-accessibility")`
deja adăugată într-o rundă anterioară (N-3, pentru `lastForegroundPackage`).

## 1. Parser

Trei regex-uri, diacritic-tolerante (`[ăa]`, `[șs]` unde era nevoie): `sună-o/sună-l pe X (pe
WhatsApp)` → CALL, `video (call) cu X` → VIDEO, `scrie-i/trimite-i (lui) X că/mesaj TEXT` → MESSAGE.
Orice altceva → `false`, cade în lanțul existent (deschide aplicație, YouTube, media, JS).

**Neimplementat, semnalat explicit:** „caută (contactul) X" (deschide conversația + întreabă
„mesaj, apel sau video?") — nu era în lista de teste a rundei și nicio funcție existentă doar
deschide fără apel/scriere; ar cere design nou, nu refolosire.

## 2. Rezolvare contact

`ContactsContract.Data`, filtrat pe cele trei MIME-uri WhatsApp (`voip.call`, `video.call`,
`profile`), deduplicat pe `CONTACT_ID`. Potrivire: exact → prefix → conține → Levenshtein (prag
generos, tolerează și formele de caz românești, „Hannei" vs „Hannah"). Telefonul vine separat, din
`ContactsContract.CommonDataKinds.Phone`. Zero candidați → „Nu găsesc X în WhatsApp." (vorbit).

**Disambiguare simplificată:** iau automat cel mai bun scor; nu există un pas „unu, doi, alege" —
nicio frază de genul ăsta n-a fost găsită deja în cod, iar construirea uneia noi de la zero a fost
lăsată deoparte pentru a ține scope-ul rezonabil. La o potrivire ambiguă reală, alegerea automată a
celui mai apropiat scor e comportamentul curent.

## 3. Confirmare

`classifyConfirmationNative` — portat din `app/index.tsx` (`CONFIRM_NO_RE`/`CONFIRM_YES_RE`/
`classifyConfirmation`), NU verificat primul. **Corecție de onestitate:** cercetarea nu a găsit
niciun fișier de test cu „31 de cazuri" pentru acest clasificator (menționat în cerere) — am portat
clasificatorul JS real (sursa de adevăr confirmată în cod), nu am inventat numărul de teste.

NU → IDLE imediat. UNKNOWN de două ori la rând → IDLE. Întrebarea se repetă o singură dată la primul
UNKNOWN.

## 4. Execuție

Pe `svc.runOnServiceScope { ... }` (exact idiomul „pe serviceScope" din doctrina rundei). Rezultatul
determină răspunsul vorbit + afișat în bulă („Sun pe X." / „Pornesc video cu X." / „Trimis." / „Nu
am reușit: <step>.").

## Log

`WA_PARSE kind=…` · `WA_RESOLVE candidates=… chosen=<inițială> dataId=…` (niciodată numele complet
sau telefonul) · `CONFIRM_RESULT verdict=…` · `WA_INTENT kind=… result=…`. Pașii interni de scriere/
trimitere/verificare folosesc deja logging-ul propriu al funcțiilor reutilizate (`WA_WRITE_*`,
`WA_NATIVE_*`) — nu l-am duplicat.

## Verificare

`tsc --noEmit` (fără schimbări JS) · `gradlew assembleRelease` · instalare, certificat, hash — vezi
raportul din conversație. Clasificatorul nu are suită separată de teste automate în această rundă
(timpul s-a dus pe orchestrare); verificarea e prin testul pe dispozitiv cerut.

## Constantă de revert

Șterge blocul „RUNDA WA-1" din `BensonForegroundService.kt` (de la comentariul cu acest nume până
la `executeWaAction`), și cele două linii de apel din `handleNativeCommandFlow`
(`if (pendingWaAction != null) {...}` și `if (tryNativeWhatsApp(command)) return`).
