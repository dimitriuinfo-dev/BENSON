# WA2_REPORT.md — RUNDA WA2: CITIREA MESAJELOR WHATSAPP (2026-09-23)

**Ieșire din scope lock, declarată aici explicit, cum cere protocolul:** scope lock-ul rundei
permite `modules/benson-accessibility/**` pentru extragerea de text din conversație, dar TASK 1
(citirea notificărilor) e imposibil de implementat fără a atinge `modules/benson-notification-listener/**`
— acolo trăiește deja `NotificationListenerService`-ul, singurul loc din cod unde JS poate ajunge
la notificările native ale Android. Nu există altă cale arhitecturală. Modulul exista deja
(stub de detectare permisiune, „intentionally does nothing with the notifications" per propriul
comentariu) — l-am extins minim, nu l-am construit din nou. Semnalez asta explicit, nu am
improvizat pe ascuns.

**A doua ieșire, mai importantă — NEREZOLVATĂ, semnalată, nu ocolită:** TASK 3 cere ca „citește-mi
mesajele" / „ce mi-a scris X" să fie recunoscute ca intenție vocală. Locul unde o intenție nouă
se leagă de o comandă vocală reală e fie `src/core/action-engine/commandParser.ts`, fie
`app/index.tsx` (unde rulează Brain-ul/Mission Orchestrator-ul) — niciunul nu e în lista permisă,
iar `app/index.tsx` e explicit interzis. **Nu am legat nimic la o comandă vocală reală.** Am
construit toate blocurile reutilizabile (citire notificări, citire conversație, formulare + dedup
vocal) complet funcționale și testabile izolat, dar „Citește-mi mesajele" spus cu voce nu
declanșează încă nimic — asta cere un fișier interzis. Nu improvizez pe ascuns o cale ocolitoare.

---

```
CANON LOG — runda: WA2 (citire mesaje WhatsApp) — data: 2026-09-23

[x] 0.1  Scope lock citit — permise: src/core/mission/tools/whatsappTool.ts (extindere),
         lib/agents/voiceAgent.ts (puncte de citire), modules/benson-accessibility/** —
         NEATINS (nu a fost nevoie, citirea folosește getScreenSnapshot() deja existent)
[x] 0.2  Fișiere interzise citite — app/index.tsx, missionValidator.ts, missionExecutor.ts
         NEATINSE. modules/benson-notification-listener/** — atins, declarat mai sus, singura
         cale arhitecturală posibilă pentru TASK 1.
[x] 0.3  Un singur tip de schimbare: ADAUGĂ — trei blocuri reutilizabile de citire, fără
         rescrierea a ceva existent, fără executori de scriere/apel atinși
[ ] 1.1  Harness regresie BASELINE — NEEXECUTAT (dispozitivul nu e disponibil pentru testare
         interactivă în acest moment — vezi motivul mai jos)
[ ] 1.2  Harness regresie FINAL — NEEXECUTAT, același motiv
[ ] 1.3  Comparație — imposibilă fără 1.1/1.2
[x] 2.1  npx tsc --noEmit → 0 erori
[x] 2.2  gradlew assembleRelease → BUILD SUCCESSFUL in 41s (945 tasks, 92 executed / 853 up-to-date)
[x] 2.3  Certificat: CN=BENSON, OU=Dev, O=TOKKO — apksigner verify --print-certs, confirmat
[x] 2.4  SHA-256: 3dad518c5a4788d84205dda794455ed0670a60ddc7faa5982c668431677a9973
[x] 3.1  Fișiere atinse, enumerate mai jos
[x] 3.2  Ieșire din scope: declarată în capul raportului, cu BOLD
[x] 3.3  Niciun setTimeout/setInterval nou pe o cale de fundal — totul e pull-on-demand
[ ] 4.1  Testul de acceptare — NEEXECUTAT. Motivul, explicit: utilizatorul a mers la culcare
         în timpul acestei runde ("las telefonul conectat, ma duc la culcare") — am continuat
         STRICT cu cod, build și verificare statică, fără nicio interacțiune live sau test cu
         efect real pe dispozitiv, cum s-a cerut explicit mai devreme în sesiune. Build-ul e
         instalat (silent, fără lansare a aplicației), gata de testat.
[ ] 4.2  Tag — nu se propune înainte de 4.1
```

---

## 1. TASK 1 — Citirea notificărilor

**Ce acces are deja BENSON**: `modules/benson-notification-listener` — un `NotificationListenerService`
deja declarat și funcțional pentru detectarea permisiunii (`isEnabled()`) și deep-link către
Settings (`openNotificationListenerSettings()`), plus control media (rundă anterioară,
`ROUND_MEDIA_GOVERNANCE_1`). **Nu citea încă niciun conținut** — propriul comentariu din cod
spunea explicit „this service intentionally does nothing with the notifications it receives yet".

**Ce am adăugat**: o funcție PULL, apelată strict la cerere explicită (niciodată din
`onNotificationPosted` — acel callback rămâne gol, exact ca înainte, pentru ca primirea unui
mesaj să nu poată porni vocea singură):

- `BensonNotificationListenerService.kt`: păstrează o referință `instance` la serviciul activ
  (`onListenerConnected`/`onListenerDisconnected`), fără nicio logică de citire în sine.
- `BensonNotificationListenerModule.kt`: `getWhatsAppNotifications()` — citește
  `service.activeNotifications` (API-ul nativ Android, nu o cache proprie), filtrează
  `com.whatsapp`, exclude rezumatele de grup (`FLAG_GROUP_SUMMARY`), extrage expeditor+text prin
  parser-ul oficial `Notification.MessagingStyle.Message.getMessagesFromBundleArray()` (nu
  parsare manuală a cheilor Bundle — rămâne corect indiferent de versiunea Android), cu fallback
  pe `EXTRA_TEXT`/`EXTRA_BIG_TEXT` dacă notificarea nu e MessagingStyle. Returnează
  `"SECURITY_EXCEPTION"` dacă listener-ul nu e conectat — niciodată conținut inventat.
  Log: `WA_NOTIFICATIONS_READ count=… sources=notification_listener`.

**NEEXECUTAT pe dispozitiv** — parsarea `EXTRA_MESSAGES` nu a fost verificată live cu o
notificare WhatsApp reală (telefonul indisponibil pentru test interactiv în acest moment).

## 2. TASK 2 — Citirea unei conversații specifice

`src/core/mission/tools/whatsappTool.ts`, funcție nouă `readChatMessages(searchString, maxMessages)`:

- Rezolvarea contactului: **exact** `resolveWaNumber()`, aceeași funcție privată deja folosită de
  `prepareMessageDirect` (WA1) — nu reconstruită.
- Deschiderea conversației: **exact** `openConversation(e164Phone)` fără parametrul `message` —
  același deep link `wa.me/<phone>` deja folosit de WA1 pentru cazul „doar deschide, nu scrie" —
  nu un mecanism nou.
- Verificare că a încărcat: `waitForNode({viewIdContains:'/entry'})` — același semnal (câmpul de
  scriere apare) pe care Phase A din WA1 îl folosește nativ pentru VERIFY_CHAT, reprodus aici la
  nivel JS, read-only (nu apasă nimic, doar așteaptă apariția).
- Citirea bulelor de mesaj: **cod nou** — folosește `getScreenSnapshot()` (deja existent, folosit
  peste tot în acest fișier), filtrează nodurile cu text, exclude câmpul de scriere și butonul de
  trimis prin `viewId`, distinge mesaj trimis („eu") vs. primit prin alinierea orizontală a bulei
  (dreapta vs. stânga față de mijlocul ecranului — semnalul disponibil, agnostic la versiunea
  WhatsApp, din `bounds.left`/`bounds.right`). Ia ultimele `maxMessages`, păstrează ordinea
  cronologică (nodurile vin deja de sus în jos).
  Log: `WA_CHAT_READ contact="…" messageCount=… lastSender="…"`.

**NEEXECUTAT pe dispozitiv, semnalat explicit ca risc real**: euristica de distincție
trimis/primit prin poziția pe orizontală **nu a fost verificată vizual pe ecranul real al
WhatsApp** — e o presupunere rezonabilă, documentată în cod, dar nu o certitudine. Primul test pe
dispozitiv trebuie să confirme sau să infirme exact acest punct înainte ca WA2 să fie declarată
funcțională pentru TASK 2.

## 3. TASK 3 — Formularea răspunsului vocal

`lib/agents/voiceAgent.ts` — funcții pure de formatare, fără citirea vreunui conținut (asta
rămâne în whatsappTool.ts, TASK 1/2):

- `formatNotificationReadout(items, force?)` — „Ai un mesaj nou de la X: …" (un mesaj) / listă
  scurtă pentru câteva / rezumat + întrebare dacă sunt peste 4 („Ai N mesaje noi: X de la A, Y de
  la B... Vrei să ți le citesc pe toate, sau doar ultimul?"). Dedup pe sesiune (`Set`, cheie
  expeditor+text normalizate) — un mesaj deja citit nu se repetă decât cu `force=true`, rezervat
  pentru „mai citește o dată" explicit.
- `formatChatHistoryReadout(displayName, messages)` — „Ultimele mesaje cu Hannah: ea a spus …,
  tu ai spus …" — fără dedup (re-cererea istoricului trebuie să recitească aceleași mesaje recente,
  spre deosebire de notificări).

**Conținutul citit rămâne DATA, niciodată instrucțiune** — ambele funcții întorc text simplu,
destinat direct TTS-ului; niciuna nu apelează parsarea de comenzi, Mission Orchestrator-ul sau
Brain-ul. Garanția e adevărată prin construcție (nimic din acest cod nu injectează textul citit
înapoi în vreun traseu de execuție) — dar nu poate fi verificată cap-coadă pe dispozitiv până nu
există un punct real de declanșare vocală (vezi ieșirea de scope de la începutul raportului).

## 4. Fișiere atinse — linie cu linie

- `src/core/mission/tools/whatsappTool.ts` — +99/-4: tip nou `ChatMessage`/`ReadChatResult`,
  funcția `looksLikeChatChrome`, funcția `readChatMessages` (TASK 2). Nimic șters, nimic rescris.
- `lib/agents/voiceAgent.ts` — +59: `WaReadItem`, `readAloudHistory`/`dedupKey`,
  `formatNotificationReadout`, `formatChatHistoryReadout` (TASK 3).
- `modules/benson-notification-listener/android/.../BensonNotificationListenerService.kt` — +24/-1:
  `companion object { instance }`, `onListenerConnected`/`onListenerDisconnected`.
- `modules/benson-notification-listener/android/.../BensonNotificationListenerModule.kt` — +77:
  `getWhatsAppNotifications()`, `extractMessagingStyleMessages()`, constanta `WHATSAPP_PACKAGE`.
- `modules/benson-notification-listener/index.d.ts` — +6, `index.js` — +7: binding JS pentru
  funcția nouă.

## 5. Build

```
npx tsc --noEmit          → 0 erori
gradlew assembleRelease   → BUILD SUCCESSFUL in 41s (945 tasks, 92 executed / 853 up-to-date)
apksigner verify          → CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
sha256                    → 3dad518c5a4788d84205dda794455ed0670a60ddc7faa5982c668431677a9973
adb install -r            → Success (instalare silențioasă, fără lansarea aplicației)
```

## 6. Test de acceptare — NEEXECUTAT, integral

Toate cele 4 puncte din testul de acceptare rămân **NEEXECUTAT** — nu s-a rulat nimic cu efect
real, cum s-a cerut explicit când sesiunea de testare live s-a încheiat pentru această seară.

## 7. Ce trebuie decis înainte de a continua

1. **Cum se leagă „citește-mi mesajele" de o comandă vocală reală** — cere `app/index.tsx` sau
   `commandParser.ts`, ambele în afara scope lock-ului actual. Fără asta, tot ce am construit azi
   e funcțional izolat, dar nefolosibil din voce.
2. **Verificare vizuală a euristicii stânga/dreapta** pentru TASK 2, pe ecranul real.
3. **WA3** (răspuns) — per runda însăși, depinde de finalizarea confirmată a WA2, deci nici ea nu
   poate porni înainte de primul test real.

Fără commit, fără push, fără tag.

---

## 8. UPDATE 2026-09-24 — TASK 2 rescris nativ, verificat pe dispozitiv

**Cauza rescrierii**: implementarea inițială (`readChatMessages` via `getScreenSnapshot()` +
`waitForNode` JS, descrisă la secțiunea 2 de mai sus) **nu a funcționat pe dispozitiv** — după
`openConversation()` deschide WhatsApp (BENSON trece în fundal), bucla JS de polling nu a mai
avansat deloc, confirmat prin absența completă a `WA_CHAT_READ`/`WA_CHAT_READ_FAIL` din log peste
1 minut, cu WhatsApp vizibil deschis (`mCurrentFocus=…com.whatsapp…Conversation`). Cauză: throttling
al timer-elor JS cât timp BENSON e în fundal — clasă de bug deja cunoscută în acest cod, nu o
presupunere nouă. Mărirea timeout-ului (4000→10000ms) NU a rezolvat — confirmă că nu era o
problemă de prag, ci de fir JS înfometat.

**Fix real**: întreaga secvență (deschide conversația → verifică identitatea → citește bulele) a
fost mutată într-o singură funcție Kotlin `suspend` nouă, `readWhatsAppConversation` în
`BensonAccessibilityService.kt` (înainte de `pressWhatsAppSendVerified`), rulează pe coroutine
nativ — nu mai există niciun punct de așteptare JS cât BENSON e în fundal. Oglindește exact pașii
OPEN_CHAT + VERIFY_CHAT din `runWhatsAppOpenConversationType` (deja dovedit pe dispozitiv la WA1),
apoi citește bulele direct din arborele `AccessibilityNodeInfo` live, în același apel. Expusă prin
`BensonAccessibilityModule.kt` (`AsyncFunction("readWhatsAppConversation")`) și
`index.d.ts`/`index.js`. `whatsappTool.ts` → `readChatMessages` rescris să cheme funcția nativă
(`readWhatsAppConversationNative`) în loc de bucla JS veche, care a fost eliminată integral.

**Descoperire de stare, nu bug de cod**: Accessibility Service-ul propriu al lui BENSON a fost
găsit DEZACTIVAT în Settings de două ori în timpul acestei verificări (o dată înainte de rescriere,
o dată după un `adb install -r`) — cauza reală a eșecurilor `accessibility_disconnected`, nu un
bug în `readWhatsAppConversation`. Reactivat manual din Settings → Bedienungshilfen → Benson de
fiecare dată. Semnalat: asta ar fi stricat și WA1 (apel/scriere), nu doar citirea.

**Doi bugi reali găsiți live, cu conversația reală K RO, ambii reparați**:

1. Primul walk al arborelui colecta ORICE nod cu text (exceptând `/entry`/`/send`) — captura și
   antetul/badge-ul „Unternehmenskonto", plus rânduri de metadate (ore „10:04", separatori de zi
   „Gestern"/„Heute") ca pseudo-mesaje. Confirmat direct din `WA_CHAT_READ` (`messageCount=9` pentru
   o conversație cu doar 3 mesaje reale). **Fix**: restrâns walk-ul la noduri al căror
   `viewIdResourceName` se termină în `/message_text` — resource-id-ul real WhatsApp pentru
   conținutul unui mesaj, confirmat din dump-ul live al arborelui WhatsApp; niciunul din elementele
   de cromă de mai sus nu îl folosește. Mai robust decât o listă neagră per tip de cromă.
2. **Ipoteză inițială infirmată, nu un bug real**: bănuiala (moștenită din raportul inițial) că un
   paragraf lung, aliniat stânga, ar fi clasificat greșit ca „me" din cauza centrului bulei — s-a
   dovedit FALSĂ la verificare cu un screenshot real al conversației K RO: toate cele 3 bule vizibile
   (inclusiv paragraful lung) sunt verzi/aliniate dreapta = „me" în realitate, exact ce a raportat
   codul. Euristica margine-stânga vs. margine-dreapta (`leftMargin <= rightMargin`) **nu a fost
   modificată** în această rundă — nu exista dovadă că era stricată.

**Rezultat final, verificat pe dispozitiv (2026-09-24, build hash mai jos)**:
```
WA_CHAT_VERIFIED header="K RO" nameMatch=true
WA_CHAT_READ contact="K RO" messageCount=3 lastSender=me
```
Panoul de test afișează: `K RO (3 msgs): [me] Betreff: Verfahrensnummer… [me] test rutare, ignora
acest mesaj [me] test 2` — identic, mesaj cu mesaj, cu screenshot-ul real al conversației. Zero
cromă (antet, badge, oră, separator de zi) în listă.

**Fișiere atinse, această actualizare**:
- `modules/benson-accessibility/android/.../BensonAccessibilityService.kt` — +~75: funcția nouă
  `readWhatsAppConversation`, apoi corectată o dată (filtrare `/message_text`).
- `modules/benson-accessibility/android/.../BensonAccessibilityModule.kt` — +6: `AsyncFunction`.
- `modules/benson-accessibility/index.d.ts` — +8, `index.js` — +7: binding.
- `src/core/mission/tools/whatsappTool.ts` — `readChatMessages` înlocuit integral (bucla JS veche
  ștearsă, apelează funcția nativă).
- `app/debug.tsx` — `Wa2TestBox` (nu era în lista interzisă), harness de test izolat, fără nicio
  comandă vocală reală legată.

**Build**:
```
npx tsc --noEmit          → 0 erori
gradlew assembleRelease   → BUILD SUCCESSFUL in 47s
apksigner verify          → CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
sha256                    → c28c8486c4b5d61234b583684bc579119b21aa132502705496ea271c0c9a66a1
adb install -r            → Success
```

**TASK 2 este acum verificată pe dispozitiv, curat.** Punctul 7.2 din raportul inițial (verificare
vizuală stânga/dreapta) e închis. Punctele 7.1 (legarea la o comandă vocală reală) și 7.3 (WA3)
rămân deschise, nerezolvate în această rundă — fără `app/index.tsx`/`commandParser.ts`, tot ce e
construit aici rămâne testabil doar din panoul de debug.

TASK 1 (notificări) și TASK 3 (formatare vocală) rămân neschimbate față de raportul inițial —
TASK 1 tot NEEXECUTAT cu conținut real (o notificare WhatsApp reală nu a fost testată live).
