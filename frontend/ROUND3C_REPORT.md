# ROUND 3C — Erori curate · căutare tolerantă · creierul 404

Data: 2026-08-28 · fără commit, fără push, fără tag.

**Excepție de scope acordată explicit:** `src/core/mission/missionExecutor.ts` și
`src/core/mission/tools/whatsappTool.ts` (fișierul din lista protejată e trecut ca
`lib/tools/whatsappTool.ts`, dar nu există acolo — calea reală e `src/core/mission/tools/`).
Restul listei protejate — neatins. În afara ei, tot în scope ca de obicei:
`lib/engines/registry.ts`, `lib/engines/llm/openAiCompatibleBrain.ts`.

---

# 1 · Erorile tehnice nu mai ajung la utilizator

Lanțul din captură: nativ → `whatsappTool.placeCall` împacheta `` `Nu am găsit: ${failedLabel}
(${result.detail})` `` → `missionExecutor.buildWaitingUserMessage` împacheta A DOUA oară
`` `Nu am reușit să-l sun pe ${contact} pe WhatsApp (${result.error}).` `` → JSON-ul brut al
Accessibility Guard-ului ajungea rostit.

Acum: mesajul vizibil = **ce nu a reușit + în ce aplicație**. Selectori / JSON / status nativ →
logcat, `EXEC_ERROR detail=…`.

### `src/core/mission/tools/whatsappTool.ts` — schimbări linie cu linie

| Linii (după) | Schimbare |
|---|---|
| 7–9 | **+2 importuri:** `type CommandResult` din `benson-accessibility`; `logAudioDiag` din `benson-foreground-service`; `getLastScreenSnapshot` din `../../../../lib/screenBridge`. |
| ~121 | `attemptAndConfirm`: `error: 'Could not open WhatsApp.'` → `error: 'Nu am putut deschide WhatsApp.'` |
| ~136 | `openApp`: `error: outcome.error ?? 'Could not launch WhatsApp.'` → `error: outcome.error ?? 'Nu am putut deschide WhatsApp.'` |
| 267–273 | **`RecipeStep.kind?`** adăugat: `'contact_result' \| 'message_typed'` — dă forma frazei de eșec pentru pasul respectiv. |
| 356–372 | **nou `cleanRecipeFailure(recipe, result: CommandResult, searchString)`** — loghează `EXEC_ERROR step=… action=… status=… label=… detail=…` (detail trunchiat la 300, spații colapsate) și întoarce `error` = **o singură** propoziție RO: `contact_result` → „Nu am găsit contactul «X» în WhatsApp."; `message_typed` → „Am pregătit mesajul în WhatsApp, dar nu am reușit să apăs trimite — apasă-l tu."; altfel → „Nu am reușit să duc comanda la capăt în WhatsApp." |
| 312–319 (`placeCall`), 452–470 (`openContactByName`), 480–507 (`sendMessageByName`) | ramura de eșec nu mai construiește `` `Nu am găsit: ${failedLabel} (${result.detail})` `` — trece prin `cleanRecipeFailure` / `runTwoPhase`. Vechiul `runRecipe` (care făcea append-ul `(${result.detail})`) a fost **eliminat**. |
| ~457/488/511 | `error: 'No search text was given.'` → `error: 'Nu mi-ai spus pe cine să caut.'` (3 locuri) |
| `endCall`/`muteCall` ~553/566 | `error: result.error ?? \`Stopped at step: ${result.step}\`` — **nemodificat**: `buildWaitingUserMessage` pentru endCall/muteCall folosește text RO propriu și ignoră acest câmp; nu compune text vizibil. Rămâne doar ca detaliu intern. |

### `src/core/mission/missionExecutor.ts` — schimbări linie cu linie

| Linii (după) | Schimbare |
|---|---|
| 18 | **+1 import:** `logAudioDiag` din `benson-foreground-service`. |
| 264–269 `buildFailureMessage` | `` `Nu am putut deschide Waze${error ? ` (${error})` : ''}.` `` → `'Nu am putut deschide Waze.'`; idem WhatsApp. Adăugat: `if (error) logAudioDiag('EXEC_ERROR', \`phase=launch tool=… detail=…\`)` (trunchiat 300). Cazul `ACCESSIBILITY_DISCONNECTED_ERROR` — nemodificat (are propoziția lui dedicată). |
| 291–296 `buildWaitingUserMessage` / `placeCall` | `` `Nu am reușit să-l sun pe ${contact} pe WhatsApp (${result.error ?? 'motiv necunoscut'}).` `` → `return result.error ?? \`Nu am reușit să sun pe ${contact} pe WhatsApp.\`;` — `whatsappTool` întoarce acum o propoziție curată; e surfaceată ca atare, fără paranteze, fără dublă-împachetare. |
| 311–320 `prepareMessage` / `openContact` | ambele ramuri `opened_manual_action_required` întorc acum `result.error ?? <text generic>` — ca întrebarea „pe care?" / „nu am găsit contactul" să nu se piardă (înainte `openContact` ignora complet `result.error`). |

**Toate căile de text vizibil verificate:** singurele două care compuneau text vizibil erau
`buildWaitingUserMessage`+`placeCall` (L288) și `buildFailureMessage` (L267/268). `runRecipe`
(openContact/prepareMessage) NU surfacea `result.error` înainte — acum o face, curat. `sendMessage`
(calea cu număr de telefon), `endCall`, `muteCall` nu compun text vizibil din `error` (verificat în
`buildWaitingUserMessage`). Reason-urile interne engleze din `findBadConfirmationPayload`
(L219/L223) merg în câmpul `reason` al misiunii (debug), niciodată rostite — lăsate.

---

# 2 · Căutarea contactului — tolerantă, nu exactă

Doctrina rămâne: **BENSON nu citește agenda**. Tastează în câmpul de căutare WhatsApp exact ce a
spus utilizatorul, apoi **citește rândurile de rezultat** prin snapshot-ul Accessibility și alege
tolerant. Normalizarea se aplică **doar** la compararea rândurilor citite — niciodată la ce se
tastează.

### Implementare (`whatsappTool.ts`)

- **`phoneticKey(s)`** (L285): NFD → fără diacritice → lowercase → `h` ignorat → doar litere/cifre
  → litere duble reduse la una. „Hannah" → `ana`; „HANA" → `ana` → potrivire.
- **`matchCandidates(query, candidates)`** (L297): cascadă, **oprire la primul pas cu rezultate**:
  `exact` (`===` case-insensitive) → `prefix` (`startsWith`) → `phonetic` (`phoneticKey` egal).
- **`readWhatsAppResultNames()`** (L326): `getLastScreenSnapshot()`; dacă `packageName ===
  com.whatsapp` și snapshot < 6 s vechi, extrage `text`-urile scurte (≤ 40 ch), ne-editabile, care
  nu-s câmpul de căutare și nu-s antete de secțiune („Chats", „Mesaje", „Contacte pe WhatsApp",
  DE/EN/RO). Best-effort — nativul dă listă plată de noduri, fără arbore.
- **`resolveResultPick(query)`** (L349):
  - 0 candidați citiți → `CONTACT_MATCH strategy=native_fallback` → pasul de click cade pe
    `textContains: query, wholeWord: false` (tot mai larg decât vechiul `wholeWord: true`).
  - potrivire unică → auto-pick, click pe **textul exact de pe ecran**.
  - `exact` cu mai multe (același nume) → auto-pick primul.
  - `prefix`/`phonetic` cu mai multe distincte → **NU alege** → întoarce întrebarea
    „Am găsit mai multe: A, B, C. Pe care? Spune-mi numele complet." (max 5).
  - `CONTACT_MATCH strategy=exact|prefix|phonetic|native_fallback|none query="…" matched="…"
    candidates=<n>` la fiecare apel.
- **Flux în două faze** (`runTwoPhase`, L397): part 1 = `buildOpenAndSearchSteps` (deschide
  WhatsApp, buton căutare, `set_text` nume, wait 1000 ms) → `executeCommand` #1. Apoi JS:
  `resolveResultPick`. Apoi part 2 = coada (click rezultat + restul) → `executeCommand` #2.
  `placeCall` / `openContactByName` / `sendMessageByName` folosesc toate `runTwoPhase` cu coada
  lor (`buildCallTailSteps` / `buildOpenChatTail` / `buildOpenChatTail`+mesaj+trimite).
- Pasul de click pe rezultat: `wholeWord` acum **`false`** peste tot (era `true`), plus
  `textContains` = numele exact citit de pe ecran când JS a putut alege unul.

### Limită cunoscută (raportată, nu ascunsă)

Ramura „Am găsit mai multe. Pe care?" **întreabă** dar nu reia misiunea automat: utilizatorul
repetă comanda cu numele complet („sună pe Hannah Müller pe WhatsApp") → data viitoare e `exact`.
Reluarea în starea `WaitingUser` cu param rafinat ar fi atins mașina de stări a misiunii + 
`resolveActiveMissionFromUtterance` — în afara acestei runde.
`readWhatsAppResultNames` e euristic: dacă snapshot-ul nu conține lista de rezultate (nu s-a
actualizat la timp), se cade pe potrivirea nativă `wholeWord:false`.

---

# 3 · Creierul dă 404

`GET https://console.groq.com/docs/models` (fără cheie, doc public): **`llama-3.3-70b-versatile`
este ÎNCĂ model de PRODUCȚIE pe Groq** (280 tps, 131k context), tier Enterprise. Deci 404-ul
**nu** e model scos din serviciu.

Cauze probabile, în ordine: (a) **acces cont** — `llama-3.3-70b-versatile` e listat „Enterprise
tier"; un cont free/dev primește 404 „model does not exist or you do not have access"; (b) **URL**
— un baseUrl stocat cu slash final ar da `…/v1//chat/completions` → 404 identic la aspect.

**Nu pot interoga `GET /openai/v1/models` cu cheia** — cheia e în expo-secure-store pe telefon, nu
în mediul meu. De aceea am adăugat logarea care spune definitiv care e cauza la următoarea rulare:

### `lib/engines/llm/openAiCompatibleBrain.ts`

- **nou `chatCompletionsUrl(baseUrl)`** (L18) — taie slash-urile finale din baseUrl înainte de a
  adăuga `/chat/completions`. Fix defensiv pentru cauza (b).
- `postChatCompletion` (L29–32): `const url = chatCompletionsUrl(config.baseUrl)` + 
  **`logAudioDiag('LLM_ENDPOINT', \`url=${url} model=${config.model}\`)`** — URL-ul rezolvat +
  modelul, niciodată cheia (e doar în antetul `Authorization`).
- ramura `!res.ok` (L76–82): citește `res.text()`, scrub `Bearer …` → `Bearer •••`, trunchiază la
  400, **`logAudioDiag('LLM_ERROR', \`status=${res.status} body=${JSON.stringify(body)}\`)`**.
  Corpul erorii Groq spune exact „model does not exist / no access" vs. altceva.

### `lib/engines/registry.ts`

- `GROQ_BRAIN_DEFAULT_MODEL`: `'llama-3.3-70b-versatile'` → **`'llama-3.1-8b-instant'`** — model de
  producție disponibil pe **orice** tier de cont Groq, deci implicit sigur. Comentariu adăugat cu
  contextul de mai sus și indicația de a trece la `openai/gpt-oss-20b` odată ce logul confirmă
  contul/URL-ul.

**Ce am găsit / ce am ales:** `llama-3.3-70b-versatile` e valid dar Enterprise-tier → am pus
implicit `llama-3.1-8b-instant` (universal). Dacă logul `LLM_ENDPOINT` de la următoarea rulare
arată un URL cu `//` sau alt path, **acela e fixul** și modelul nu contează — spune-mi ce arată
`LLM_ENDPOINT` și `LLM_ERROR` și continui. Comandă de verificat manual, cu cheia din câmpul Groq
din Settings:

```
curl -s https://api.groq.com/openai/v1/models -H "Authorization: Bearer <cheia_groq>" | findstr /i "\"id\""
```

---

# 4 · Fișiere atinse

| Fișier | Stare | Linii (net) |
|---|---|---|
| `src/core/mission/tools/whatsappTool.ts` | modificat (excepție de scope) | **+~200 / −~80** — vezi §1, §2 linie cu linie |
| `src/core/mission/missionExecutor.ts` | modificat (excepție de scope) | **+~18 / −~7** — vezi §1 linie cu linie |
| `lib/engines/llm/openAiCompatibleBrain.ts` | modificat | **+~16** — `chatCompletionsUrl`, `LLM_ENDPOINT`, `LLM_ERROR` |
| `lib/engines/registry.ts` | modificat | **+~7 / −1** — model implicit + comentariu |
| `ROUND3C_REPORT.md` | nou | acest fișier |

Restul listei protejate (`android/**`, `plugins/**`, `modules/**`, `whisper-models/**`,
`porcupine-model/**`, `groqStt.ts`, `localWhisperEngine.ts`, `missionValidator.ts`,
`voiceAgent.ts`, `lib/tools/tools.ts`) — **neatins**.

---

# 5 · Verificare

### `npx tsc --noEmit`
```
(fără output — exit code 0)
```

### `gradlew assembleRelease`
```
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 56s
945 actionable tasks: 67 executed, 878 up-to-date
```

- **Cale APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
- **Dimensiune:** 260.848.020 bytes (248,76 MiB)
- **Construit:** 2026-08-28 17:52:51
- **SHA-256 (calculat de mine pe APK-ul rezultat):** `0eae5edbf74e520ab9897a692727577be6db6a41ddf7383fc76ba00c1ff16226`
- **Semnare:** `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` — `CN=BENSON, O=TOKKO` neschimbat.

---

# 6 · Ce se testează pe dispozitiv

1. **„Sună-o pe Hana pe WhatsApp"** (mistranscriere) → `CONTACT_MATCH strategy=phonetic
   query="Hana" matched="Hannah" candidates=1` → conversația Hannah se deschide, apel vocal.
2. Contact inexistent → rostit: **„Nu am găsit contactul «X» în WhatsApp."** — fără JSON, fără
   `No node matched`. `EXEC_ERROR step=… detail={"textContains":…}` doar în logcat.
3. Nume ambiguu cu mai multe potriviri prefix/fonetic → **„Am găsit mai multe: A, B. Pe care?…"**
4. Creierul: prima conversație → `LLM_ENDPOINT url=https://api.groq.com/openai/v1/chat/completions
   model=llama-3.1-8b-instant`. Dacă tot 404 → `LLM_ERROR status=404 body={…}` spune de ce.
5. Nicio cheie în logcat (`LLM_ENDPOINT` are doar URL-ul; `LLM_ERROR` scrub-uie `Bearer`).

Fără commit, fără push, fără tag.
