# ROUND 3D — Selectoare WhatsApp · watchdog TTS · parțial vs. final

Data: 2026-08-28 · fără commit, fără push, fără tag.

Fișiere atinse: `src/core/mission/tools/whatsappTool.ts` (excepție de scope, continuă din 3C),
`app/index.tsx`. Restul listei protejate — neatins.

---

# 1 · Selectoarele WhatsApp — resource-id întâi, etichetă doar ca rezervă

Din logcat: `No node matched {"textContains":"suchen","clickable":true} within 4000ms` — pasul de
căutare al rețetei `placeCall`. Butonul de căutare e o **pictogramă fără nod `text`** — potrivirea
pe cuvânt („suchen"/„search"/„căutare") eșuează pe orice WhatsApp non-german și, pentru o
pictogramă pură, chiar și în germană.

## Fiecare selector bazat pe text găsit în rețete (înainte)

| # | Fișier:linie (înainte) | Selector | Control | Limbă-dependent? |
|---|---|---|---|---|
| 1 | `whatsappTool.ts` `buildOpenAndSearchSteps` | `{ textContains: labels.search, clickable: true }` | buton căutare | **DA** (`suchen`/`search`/`căutare`) |
| 2 | `whatsappTool.ts` `contactResultStep` | `{ textContains: clickText, wholeWord:false, … }` | rândul contactului | NU — e numele persoanei (Task 2 din 3C) |
| 3 | `whatsappTool.ts` `buildCallTailSteps` | `{ textContains: labels.voiceCall, clickable:true, maxTopPercent:15 }` | buton apel vocal | **DA** (`sprachanruf`/`voice call`/`apel vocal`) |
| 4 | `whatsappTool.ts` `sendMessageByName` (tail inline) | `{ textContains: labels.send, clickable:true }` | buton trimite | **DA** (`senden`/`send`/`trimite`) |
| 5 | `whatsappTool.ts` `sendMessage` (calea legacy cu nr. telefon) | `{ textContains: 'senden', clickable:true }` — **literal german hardcodat** | buton trimite | **DA** |
| — | `set_text` câmp căutare / câmp mesaj | `{ viewId: 'com.whatsapp:id/search_input' }` / `{ viewId: 'com.whatsapp:id/entry' }` | — | deja viewId ✓ |
| — | `assert_gone` | `{ viewIdContains: 'search_input' }` | — | deja viewId ✓ |

## După

Rețeta declarativă (un singur `executeCommand` cu N pași) → **execuție pas-cu-pas**. Fiecare
control limbă-dependent (1, 3, 4, 5) trece printr-o **cascadă de strategii**, prima care prinde
câștigă:

```
SEARCH_STRATEGIES    = [ viewId com.whatsapp:id/menuitem_search,
                         viewIdContains menuitem_search,
                         text de:suchen, text en:search, text ro:căutare ]
VOICECALL_STRATEGIES = [ viewId com.whatsapp:id/menuitem_call,
                         viewIdContains menuitem_call,
                         text de:sprachanruf, en:voice call, ro:apel vocal  (maxTopPercent:15) ]
SEND_STRATEGIES      = [ viewId com.whatsapp:id/send,
                         viewIdContains :id/send,
                         text de:senden, en:send, ro:trimite ]
```

- `clickResilient(strategies, label, index)` — încearcă fiecare strategie ca `executeCommand`
  separat; pe `not_found`/`timeout`/`ambiguous`/`invalid` trece la următoarea, pe orice altă
  eroare (`blocked`/`tap_rejected`/`wrong_package`) se oprește. Loghează
  **`RECIPE_STEP index=… label=… strategy=viewId|text|none matched=…`** la pasul care prinde (sau la
  ultimul ratat, cu `status=`).
- `runPlainSteps(steps, baseIndex)` — grupurile ne-limbă-dependente (launch, wait, assert,
  set_text pe viewId) rulează tot ca un `executeCommand`, dar cu **`RECIPE_STEP` per pas** care a
  rulat, iar pasul căzut e marcat `matched=FAILED:<status>`.
- **Toți pașii tuturor rețetelor** (call / open / send) trec acum prin `runPlainSteps` +
  `clickResilient` — verificat pas cu pas, nu doar căutarea. Numele de `viewId` sunt cele istorice
  WhatsApp — **NEverificate pe acest build exact**; lista de etichete de/en/ro e plasa de
  siguranță, iar `RECIPE_STEP strategy=…` spune care a prins.
- Calea legacy `sendMessage` (nr. telefon, folosită doar dacă `WHATSAPP_MESSAGE_VIA_ACCESSIBILITY
  = false`): literalul `'senden'` → `{ viewId: WA_ID.send }`; eroarea tehnică → `EXEC_ERROR
  phase=send_legacy`, mesaj utilizator „Am pregătit mesajul… apasă-l tu."

### `whatsappTool.ts` — schimbări structurale

| Zonă | Schimbare |
|---|---|
| import | `+ CommandMatch` din `benson-accessibility` |
| ~181 | `sendMessage` legacy: `textContains:'senden'` → `viewId: WA_ID.send`; + `EXEC_ERROR phase=send_legacy`; mesaj RO curat |
| `RecipeStep` | simplificat la `{ label; step }` (câmpul `kind` mutat în logica `failAt(phase)`) |
| **nou** ~355–420 | `SelStrategy`, `WA_ID`, `labelStrategies()`, `SEARCH/VOICECALL/SEND_STRATEGIES`, `TRY_NEXT_STATUSES`, `clickResilient()`, `runPlainSteps()`, `failAt()` |
| `buildOpenAndSearchSteps` / `contactResultStep` / `buildCallTailSteps` / `buildOpenChatTail` / `cleanRecipeFailure` / `runRecipe` / vechiul `runTwoPhase` | **eliminate** — înlocuite de noul `runTwoPhase(name, uiLang, tail: {kind:'call'\|'open'\|'send'})` care orchestrează pas cu pas |
| `placeCall` / `openContactByName` / `sendMessageByName` | corpul → `return runTwoPhase(name, uiLang, { kind })` |
| `whatsappUiLabels()` | rămâne definit dar nefolosit (etichetele se iau acum direct din `WHATSAPP_UI_LABELS` în `labelStrategies`) — lăsat, inofensiv |

`RECIPE_STEP` acoperă acum ~15 pași per apel (index 0..14).

---

# 2 · Watchdog TTS

Din logcat: `TTS_WATCHDOG_TIMEOUT wordCount=12 timeoutMs=6000` — întrebarea de confirmare
(„Deschid WhatsApp, caut «X», aleg primul rezultat și apăs apelul vocal. Confirmi?" ≈ 12 cuvinte)
nu apuca să fie rostită: watchdog-ul se declanșa la 6 s, `settle()` elibera `speakingRef`,
microfonul se redeschidea în mijlocul propoziției și BENSON își auzea propria întrebare.

### `app/index.tsx` `speakOnDevice` (~1379)

```
- const watchdogMs = Math.max(6000, wordCount * 110 + 4000);
+ const watchdogMs = Math.max(8000, wordCount * 400 + 5000);
```

- ~400 ms/cuvânt (realist pentru TTS-ul de sistem al acestui telefon, cu start-up motor + pauze
  de frază), + 5 s buffer.
- **Minim 8 s** — watchdog-ul e plasă de siguranță de ultimă instanță pentru un callback blocat,
  nu un timeout real, deci e deliberat generos.
- 12 cuvinte → 9 800 ms; 3 cuvinte → 8 000 ms (podeaua); 40 cuvinte → 21 000 ms.
- Microfonul rămâne închis cât `speakingRef.current` e `true` (`doStartListening`'s guard,
  neschimbat) — watchdog-ul nu-l mai eliberează prematur.

---

# 3 · Parțialul nu mai e tratat ca final

Din logcat: `TRANSCRIPT_ACCEPTED source=partial_fallback` — rezultatul parțial e dispecerizat pe
`end` imediat, ca și cum ar fi final.

### `app/index.tsx` — schimbări

| Zonă | Schimbare |
|---|---|
| ~511 | **nou ref** `partialFallbackTimerRef` |
| `resultSub` (isFinal) ~677 | dacă un timer de grație e activ când sosește un final real → `clearTimeout` + `STT_PARTIAL_FALLBACK outcome=superseded_by_final` |
| `endSub` partial-fallback ~715–740 | nu mai dispecerizează sincron. Self-echo → respins imediat (`STT_PARTIAL_FALLBACK outcome=rejected_self_echo`), cade prin la restart-ul normal. Altfel → **`setTimeout(PARTIAL_FALLBACK_GRACE_MS = 900)`** + `STT_PARTIAL_FALLBACK outcome=grace_started` + `return`. |
| callback-ul de grație | dacă `jsSttSessionIdRef.current !== sid` sau `loadingRef.current` → `outcome=dropped_stale`, nu face nimic. Altfel → `STT_PARTIAL_FALLBACK outcome=accepted_no_final` + `TRANSCRIPT_ACCEPTED … source=partial_fallback grace_ms=900` + `scheduleAssembledDispatch`. |
| `doStartListening` (~2082) | o sesiune nouă anulează timerul de grație pendinte |
| cleanup effect (~900) | `clearTimeout(partialFallbackTimerRef.current)` la unmount |

Comportament: se **așteaptă finalul 900 ms**; dacă vine, parțialul e abandonat
(`superseded_by_final`); dacă nu, parțialul e acceptat și **logat distinct**
(`STT_PARTIAL_FALLBACK outcome=accepted_no_final`, plus `grace_ms=900` pe linia
`TRANSCRIPT_ACCEPTED`).

---

# 4 · Verificare

### `npx tsc --noEmit`
```
(fără output — exit code 0)
```

### `gradlew assembleRelease`
```
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 53s
945 actionable tasks: 67 executed, 878 up-to-date
```

- **Cale APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
- **Dimensiune:** 260.850.620 bytes (248,77 MiB)
- **Construit:** 2026-08-28 18:07:41
- **SHA-256 (calculat de mine pe APK-ul rezultat):** `66d8b00fcf635eb40c89f97c31bbf9c97fb890aadb59761299f703b9a4b8cde0`
- **Semnare:** `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` — `CN=BENSON, O=TOKKO` neschimbat.

---

# 5 · Ce se testează pe dispozitiv

1. **„Sună-o pe Hannah pe WhatsApp"** cu WhatsApp în ORICE limbă → logcat:
   `RECIPE_STEP index=4 label="butonul de căutare" strategy=viewId matched="viewId:menuitem_search"`
   (sau `strategy=text matched="text:de:suchen"` dacă id-ul nu prinde). Rețeta merge până la apel.
   Dacă un `viewId` e greșit, `RECIPE_STEP` arată exact care strategie a salvat pasul.
2. Întrebarea de confirmare (~12 cuvinte) se rostește **întreagă**; niciun `TTS_WATCHDOG_TIMEOUT`
   la 6 s; microfonul nu se redeschide până termină.
3. STT: dacă vine doar un parțial → `STT_PARTIAL_FALLBACK outcome=grace_started` apoi, după 900 ms,
   `outcome=accepted_no_final`. Dacă un final real vine în fereastră → `outcome=superseded_by_final`
   și se folosește finalul.

Fără commit, fără push, fără tag.
