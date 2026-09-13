# RUNDA B — raport

29.08.2026. Lock: `modules/benson-accessibility/**` · `lib/screenBridge*` ·
`src/core/mission/tools/whatsappTool.ts` · acest raport. Fără `git`, `prebuild`, `setx`.
Neatinse: `android/**`, `plugins/**`, `modules/benson-audio-capture/**`, `missionValidator.ts`,
`missionExecutor.ts`.

---

## 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | vezi mai jos |

---

## B1 — de ce snapshot-ul vine gol pentru WhatsApp (cauza)

`modules/benson-accessibility/android/src/main/res/xml/accessibility_service_config.xml`:

```
android:accessibilityEventTypes="typeWindowStateChanged"
```

**Doar `typeWindowStateChanged`.** Setul complet (`+typeWindowContentChanged/…`) a fost tăiat
deliberat pe 2026-07-09 fiindcă provoca 75–105% CPU / ~90 °C pe acest telefon.

`emitScreenSnapshot()` (care împinge JSON-ul spre JS prin `onScreenUpdate`) e apelat **numai** din
`onAccessibilityEvent` pe `TYPE_WINDOW_STATE_CHANGED` — adică doar când apare o fereastră/activity
nouă. Când **rezultatele căutării WhatsApp se populează**, asta e `TYPE_WINDOW_CONTENT_CHANGED`
în aceeași activity → **niciun snapshot nu e emis**. `getLastScreenSnapshot()` întoarce
instantaneul de dinainte de căutare (ecranul home WhatsApp), sau unul mai vechi de 6 s, pe care
`readWhatsAppResultNames()` îl aruncă → `[]` → `CONTACT_MATCH candidates=0`.

Rețeta a mers totuși pentru „mama" doar prin fallback-ul larg `textContains:"mama"`, fiindcă
textul „mama" era literal pe ecran. Pentru „Hana" (inexistent ca atare) picase.

### Fixul — snapshot la cerere, NU re-lărgirea tipurilor de eveniment

Re-adăugarea lui `typeWindowContentChanged` ar readuce regresia termică. În loc de asta:

**`getScreenSnapshot()` — citire PROASPĂTĂ, la cerere, a `rootInActiveWindow`:**

- **Nativ** `BensonAccessibilityService.captureSnapshot()` (suspend, rulează pe `serviceScope`):
  citește `rootInActiveWindow` acum, îl parcurge cu `walk()` existent, **reîncearcă până la 3× la
  150 ms** dacă arborele e null sau gol (normal imediat după o tranziție). Întoarce JSON cu
  garanțiile cerute: `packageName`, `timestamp`/`capturedAt`, `nodeCount`, `nodes`.
  Log: **`SNAPSHOT pkg=… nodes=… ageMs=0 retries=…`**.
- **Nativ** `BensonAccessibilityModule` — `AsyncFunction("getScreenSnapshot")` → `runOnServiceScope { captureSnapshot() }`.
- **`index.d.ts`** — `getScreenSnapshot(): Promise<string>` + câmpuri `capturedAt`/`nodeCount`.
- **`lib/screenBridge.ts`** — `export async function getScreenSnapshot(): Promise<BensonScreenSnapshot | null>`
  (parsează JSON-ul nativ). `getLastScreenSnapshot()` rămâne pentru consumatorii pasivi (tools.ts, creierul).

„Niciodată mai vechi decât ultima acțiune" e garantat prin construcție: e o citire *acum*.
„Niciodată al altei aplicații": `waitForNode` verifică `snap.packageName === com.whatsapp`.

---

## B2 — `waitForNode`, în locul somnurilor fixe

`src/core/mission/tools/whatsappTool.ts`:

```ts
waitForNode(predicate | predicate[], timeoutMs = 3000, pollMs = 100)
  → { found, node, predicate, elapsedMs }
waitForNodeGone(predicate, timeoutMs, pollMs) → boolean
```

- Interoghează `getScreenSnapshot()` (proaspăt) la fiecare `pollMs` până se potrivește un predicat
  sau expiră.
- Predicat pe: `viewId`, `viewIdContains`, `text`, `textContains`, `contentDescription`, `className`.
- `text`/`textContains`/`contentDescription` — **insensibil la diacritice și majuscule**
  (`normLoose`), potrivit pe `text + contentDescription` (ca `nodeLabel` nativ).
- Listă de predicate = potrivește ORICARE (resource-id întâi, apoi `contentDescription` de/en/ro).
- Log: **`WAIT_NODE predicate=… foundAfterMs=… result=found|timeout|gone`**.

---

## B3 — rețeta de apel WhatsApp, rescrisă (`runCallRecipe`)

`placeCall` nu mai trece prin `runTwoPhase` — folosește `runCallRecipe(name)`, construit pe B1+B2.
**Niciun `wait ms` fix.** Fiecare pas: `waitForNode` pentru ancoră → acțiune (`executeCommand`
click/set_text — care face find+act proaspăt nativ) → `waitForNode`/`waitForNodeGone` pentru
confirmarea că ecranul s-a schimbat.

| idx | name | ancoră | la timeout |
|---|---|---|---|
| 0 | lansare WhatsApp | `launch_app` | „nu găsesc WhatsApp" |
| 1 | WhatsApp în prim-plan | `assert_package` | „nu găsesc WhatsApp" |
| 2 | butonul de căutare | `menuitem_search` → `viewIdContains:search` → `contentDescription: suchen\|search\|caut` | „nu găsesc butonul de căutare" |
| 3 | câmpul de căutare | `viewIdContains:search_input` | „nu găsesc câmpul de căutare" |
| 4 | rezultatele căutării | `resolveResultPick(name, <snapshot PROASPĂT>)` — buclă 12×150 ms | ask / „nu am găsit contactul" |
| 5 | contactul „X" | `textContains:rowText` (rând ales fuzzy) | „nu am găsit contactul «X»" |
| 6 | conversație deschisă | `search_input` dispărut **sau** `:id/entry` prezent | „nu găsesc conversația" |
| 7 | butonul de apel vocal | `menuitem_call` → `contentDescription: sprachanruf\|voice call\|apel vocal\|apel` | „nu găsesc butonul de apel vocal" |
| 8 | — | — (fără `return_to_benson`) | — |

La ancoră expirată: `stopNotFound(step)` → `opened_manual_action_required` cu
**„Nu găsesc <pasul>, deschid WhatsApp și preiei tu."** — niciodată o apăsare oarbă.
Log per pas: **`RECIPE_STEP index=… name=… anchor=… found=… elapsedMs=…`**.

---

## B4 — `return_to_benson` scos din ramura de apel

`runCallRecipe` **nu** are pas `return_to_benson` — ecranul de apel WhatsApp rămâne în prim-plan.
La final: **`FOREGROUND_AFTER_ACTION pkg=<getForegroundPackage()> expected=com.whatsapp`**.
Ramurile `open`/`send` din `runTwoPhase` își păstrează `return_to_benson` (doctrina: după chat/mesaj
BENSON predă controlul). Branch-ul `tail.kind === 'call'` a fost șters din `runTwoPhase` (mort acum).

---

## Fișiere modificate

| Fișier | Ce |
|---|---|
| `modules/benson-accessibility/.../BensonAccessibilityService.kt` | `captureSnapshot()` suspend + log `SNAPSHOT` |
| `modules/benson-accessibility/.../BensonAccessibilityModule.kt` | `AsyncFunction("getScreenSnapshot")` |
| `modules/benson-accessibility/index.d.ts` | `getScreenSnapshot()` + `capturedAt`/`nodeCount` |
| `lib/screenBridge.ts` | `export async getScreenSnapshot()` |
| `src/core/mission/tools/whatsappTool.ts` | `waitForNode`/`waitForNodeGone`/predicate helpers, `runCallRecipe`, `placeCall`→`runCallRecipe`, ramura `call` ștearsă din `runTwoPhase`, `resolveResultPick`/`readWhatsAppResultNames` acceptă snapshot proaspăt |

---

## Observație — cost

`waitForNode` interoghează `getScreenSnapshot()` la 100–150 ms; fiecare apel parcurge nativ până la
400 de noduri pe thread-ul Main al serviciului. Pentru un apel întreg (~15–20 s) sunt ~100 de
parcurgeri. Similar cu vechiul `executeCommand`/`waitForCandidates` nativ (poll la 250 ms), doar
declanșat din JS. Acceptabil pentru o acțiune inițiată de utilizator; de urmărit dacă apare spike
termic la testare.

---

## Acceptare — de verificat pe dispozitiv

„Sună-o pe <cineva> pe WhatsApp", **de 5 ori la rând, aceeași sesiune**:
- apelul pornește de 5/5;
- niciun `SNAPSHOT nodes=0` care să nu fie rezolvat de cele 3 reîncercări;
- niciun `WAIT_NODE result=timeout` pe drumul reușit;
- `FOREGROUND_AFTER_ACTION pkg=com.whatsapp` — ecranul de apel rămâne în față;
- la eșec: mesaj clar „nu găsesc <pasul>", oprire, fără apăsare oarbă.
