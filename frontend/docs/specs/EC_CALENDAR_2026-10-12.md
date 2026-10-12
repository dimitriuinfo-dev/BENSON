Salvat verbatim, 2026-10-12. Două mesaje, în ordine primită. Nu există în
PLANUL_FINAL_2026-10-12.md — rundă nouă, poziția în ordine neclarificată
încă (al doilea mesaj spune "Test (după runda R)" pentru EC2, dar runda EC
de bază nu are o poziție explicită dată). Nicio reformulare.

---

## Mesaj 1 — PRECIZARE EC — CALENDARUL SE REDUCE LA DOUĂ FUNCȚII (înlocuiește C1–C4)

PRECIZARE EC — CALENDARUL SE REDUCE LA DOUĂ FUNCȚII (înlocuiește C1–C4):
C1 CITIRE: „am ceva pe <dată>?" / „am termin pe <dată>?" / „ce am pe
   <dată>?" / „hab ich am <Datum> einen Termin?" → prin CalendarContract:
   ora + titlul fiecărui eveniment din acea zi; niciunul → „Pe <dată> nu
   ai nimic în calendar."
C2 ADĂUGARE: „pune un termin pe <dată> la <oră> <titlu>" / „trage-mi
   <titlu> pe <dată> la <oră>" / „trag mir am <Datum> um <Uhrzeit> <Titel>
   ein" → „Adaug «titlu», <dată>, ora <oră>. Confirm?" → doar la „da"
   inserare prin CalendarContract în calendarul implicit, 1 oră →
   verificare prin recitire → „Am adăugat." Lipsește ora sau data → o
   singură întrebare. Lipsește titlul → titlul „Termin".
DATE ACCEPTATE: „pe 15 noiembrie", „pe 15.11", „pe data de 15" (luna
curentă; dacă a trecut → luna următoare), „mâine", „poimâine", „vineri"
(următoarea vineri), „am 15.", „übermorgen", „nächsten Freitag". Data
înțeleasă se rostește ÎNTOTDEAUNA înapoi cu ziua săptămânii („vineri,
15 noiembrie"), ca Rareș să prindă o greșeală înainte de „da".
Fără mutare, fără ștergere în runda asta.
TESTE: „am ceva pe <o zi cu eveniment>?" → corect; „am ceva pe <o zi
liberă>?" → „nu ai nimic"; „pune un termin pe 20 noiembrie la 10 test
Benson" → confirmare cu ziua săptămânii → „da" → apare în calendar;
„pune un termin vineri" → „La ce oră?" → „la 3" → confirmare → „nu" →
nimic adăugat.

---

## Mesaj 2 — PRECIZARE EC 2 — INTERVALE ȘI CONTEXT

PRECIZARE EC 2 — INTERVALE ȘI CONTEXT:
C2b INTERVAL DE ZILE: „trece din 10 până în 20 august <titlu>" /
   „vom 10. bis 20. August <Titel>" → eveniment PE TOATĂ ZIUA, de la prima
   la ultima zi INCLUSIV (CalendarContract: ALL_DAY=1, DTEND = ziua de după
   ultima zi, UTC). Confirmare: „Adaug «vacanța la mare», de luni,
   10 august, până joi, 20 august, toată ziua. Confirm?" → doar la „da".
   Anul: cel mai apropiat viitor.
C2c CONTEXT: „trece-o", „pune-o în calendar" imediat după un răspuns care
   a conținut un interval de date → folosește acel interval; titlul se cere
   dacă lipsește („Cum o numesc?").
PROFIL FAMILIE (Dezvoltator, câmpuri editabile, local): pentru fiecare
membru: nume, școală/oraș, land sau țară (ex. Hannah — <completează
Rareș>). Folosit DOAR ca să precizeze întrebările de fapt („vacanța lui
Hannah" → vacanța școlară din landul/țara ei). Necompletat → BENSON
întreabă o dată „În ce land sau țară e școala lui Hannah?" și propune
salvarea; se salvează doar la „da".
Test (după runda R): „când are Hannah vacanța de vară?" → interval cu
sursa numită → „trece din 10 până în 20 august vacanța la mare" →
confirmare cu zilele săptămânii → „da" → eveniment pe toată ziua, 11 zile,
vizibil în calendar.
