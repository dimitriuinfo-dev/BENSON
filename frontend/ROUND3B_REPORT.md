# ROUND 3B — Textul de rezervă englezesc, tot (confirmat pe dispozitiv)

Data: 2026-08-28 · fără commit, fără push, fără tag.

**Problema:** Runda 2 / TASK 3 a înlocuit doar textele de rezervă din fișierele aflate atunci în
scope lock. Cele din `claudeAgent.ts` / `openaiAgent.ts` / `geminiAgent.ts` /
`openAiCompatibleBrain.ts` au rămas — și **exact ele se rosteau când creierul eșua**, fiindcă
`routeThroughBrain` întoarce `null` la eșec și lanțul cade pe agenții claude/openai/gemini, ai
căror `|| "I did not quite catch that"` ajungea la TTS.

---

## 1. Toate textele de rezervă găsite (înainte) — fișier și linie

| # | Fișier:linie | Text (înainte) | Când se rostea |
|---|---|---|---|
| 1 | `lib/agents/claudeAgent.ts:136` | `` `I did not quite catch that, ${params.address}.` `` | `askClaude` non-streaming, răspuns gol |
| 2 | `lib/agents/claudeAgent.ts:185` | idem | `askClaude` streaming, text gol |
| 3 | `lib/agents/claudeAgent.ts:235` | idem | `askClaudeWithTools`, bloc text gol |
| 4 | `lib/agents/claudeAgent.ts:270` | `` `I'm having trouble completing that, ${params.address}.` `` | `askClaudeWithTools`, bucla de tool-uri epuizată |
| 5 | `lib/agents/openaiAgent.ts:71` | `` `I did not quite catch that, ${params.address}.` `` | `askOpenAI` non-streaming |
| 6 | `lib/agents/openaiAgent.ts:120` | idem | `askOpenAI` streaming |
| 7 | `lib/agents/openaiAgent.ts:170` | idem | `askOpenAIWithTools` |
| 8 | `lib/agents/openaiAgent.ts:201` | `` `I'm having trouble completing that, ${params.address}.` `` | `askOpenAIWithTools`, bucla epuizată |
| 9 | `lib/agents/geminiAgent.ts:119` | `` `I did not quite catch that, ${params.address}.` `` | `askGeminiWithTools` |
| 10 | `lib/agents/geminiAgent.ts:151` | `` `I'm having trouble completing that, ${params.address}.` `` | `askGeminiWithTools`, bucla epuizată |
| 11 | `lib/engines/llm/openAiCompatibleBrain.ts:83` (acum :84) | `content \|\| (lang.startsWith('ro') ? 'Nu am înțeles clar comanda — poți s-o spui din nou?' : "I didn't quite catch that.")` | creierul întoarce răspuns non-JSON **și** gol |

Toate 11 → **`conversationFallbackLine(lang, address?)`**.

### Găsit dar NEatins (nu e text de rezervă pe eșecul creierului)

| Fișier:linie | Text | De ce nu |
|---|---|---|
| `src/core/safety/confirmationGate.ts:126` | `"I'm not sure what you'd like me to do."` | Se rostește în fluxul **Confirmation Gate** când `intent === 'UNKNOWN'` (nimic de executat), NU pe eșecul creierului. `evaluateConfirmation(request, _context?)` nu primește `lang`/`address` — localizarea corectă cere `lang` filetat prin `evaluateConfirmation` și apelanții lui din `src/core/mission/*` (`missionExecutor.ts` — **interzis**). Rundă separată. Același fișier mai are `userFacingMessage` englezești pe blocul de plăți și prompturile „Shall I proceed?" — aceeași situație, aceeași rundă separată. |
| `lib/engines/llm/openAiCompatibleBrain.ts` `errorMessage()` | „Nu am putut contacta creierul BENSON acum (…)" / „Could not reach BENSON's brain right now (…)" | Din Build B e folosit **doar** în `throw new Error(errorMessage(...))` — devine mesajul unui `Error` prins de `routeThroughBrain`, apare în logcat, **niciodată la TTS**. Lăsat ca detaliu de log. |

---

## 2. Sursa unică

`lib/agents/fallbackLine.ts` (**nou, 18 linii, zero dependențe**):

```ts
export function conversationFallbackLine(lang: string, address?: string): string {
  const l = (lang || '').toLowerCase();
  const who = address ? `, ${address}` : '';
  if (l.startsWith('ro')) return `Nu am putut procesa asta acum${who}. Verifică conexiunea sau cheia din setări.`;
  if (l.startsWith('de')) return `Ich konnte das gerade nicht verarbeiten${who}. Prüfe die Verbindung oder den Schlüssel in den Einstellungen.`;
  return `I couldn't process that right now${who}. Check the connection or the key in settings.`;
}
```

Modul-frunză fără importuri **pe intenție**: `orchestrator.ts` importă cei trei agenți **și**
linia; fiecare agent importă și el linia — un modul fără dependențe ține graful aciclic.
Definiția a fost **mutată** din `orchestrator.ts` (unde era din Build A) în `fallbackLine.ts`;
`orchestrator.ts` o re-exportă, deci `app/index.tsx` (`import { conversationFallbackLine } from
'../lib/agents/orchestrator'`) merge neschimbat.

---

## 3. Verificarea prin grep (cerută) — după modificări

```
########## grep -rn "did not quite catch"  lib app src ##########
lib/agents/geminiAgent.ts:106:    // … "I did not quite catch that" text;                (COMENTARIU)
lib/agents/openaiAgent.ts:66:     // … "I did not quite catch that" text on EVERY failure  (COMENTARIU)
lib/agents/openaiAgent.ts:161:    // … "I did not quite catch that" instead of a           (COMENTARIU)
lib/agents/orchestrator.ts:293:   // … hardcoded English "I did not quite catch that"      (COMENTARIU)
app/index.tsx:545 / 550 / 1306 / 1314 / 2713:  (COMENTARII — circuit breaker + self-echo fingerprint)

########## grep -rn "I did not"  lib app src ##########
(aceleași linii — toate COMENTARII)

########## grep -rn "Master\."  lib app src ##########
app/index.tsx:537 / 548 / 1310:  (COMENTARII — exemple de capturi STT ale vechiului ecou)

########## grep -rn "having trouble completing|didn't quite catch|quite catch that"  lib app src ##########
(aceleași linii — toate COMENTARII)

########## grep -rn "not sure what you'd like me to do\."  lib app src ##########
src/core/safety/confirmationGate.ts:126  ← singurul string VIU rămas; vezi §1 (nu e fallback pe creier)
```

**Niciun string viu de tip „text de rezervă pe eșecul creierului" nu mai e în engleză.** Restul
potrivirilor sunt comentarii sau exemple de capturi STT.

Notă: `app/index.tsx:1319` `const SELF_ECHO_FINGERPRINTS = ['quite catch', 'did not catch', 'not
catch that']` — heuristică ce recunoștea ECOUL vechiului text englezesc. Noua linie („Nu am putut
procesa asta acum…") nu e în listă; dacă vreodată intră în buclă de auto-ecou, calea rapidă prin
amprentă n-o prinde (dar verificarea prin suprapunere de cuvinte din același `looksLikeSelfEcho`
încă poate). Nemodificat — `app/index.tsx` n-a fost cerut aici și e o schimbare de comportament cu
risc propriu.

---

## 4. Fișiere atinse

| Fișier | Stare | Linii |
|---|---|---|
| `lib/agents/fallbackLine.ts` | **nou** | 18 |
| `lib/agents/orchestrator.ts` | modificat | **−12 / +5** (definiția `conversationFallbackLine` mutată → import + re-export) |
| `lib/agents/claudeAgent.ts` | modificat | **+1 / −0** import; **4** string-uri înlocuite |
| `lib/agents/openaiAgent.ts` | modificat | **+1** import; **4** string-uri înlocuite |
| `lib/agents/geminiAgent.ts` | modificat | **+1** import; **2** string-uri înlocuite |
| `lib/engines/llm/openAiCompatibleBrain.ts` | modificat | **+1** import; **1** expresie înlocuită |
| `ROUND3B_REPORT.md` | **nou** | acest fișier |

`lib/engines/tts/androidTts.ts` — rescris în tura anterioară (fix accent), **neconstruit până
acum**; intră și el în acest APK. Vezi discuția separată despre faptul că `androidTts.ts` nu are
încă apelant viu.

---

## 5. Verificare

### `npx tsc --noEmit`
```
(fără output — exit code 0)
```

### `gradlew assembleRelease`
```
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 57s
945 actionable tasks: 67 executed, 878 up-to-date
```

- **Cale APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
- **Dimensiune:** 260.844.128 bytes (248,76 MiB)
- **Construit:** 2026-08-28 17:35:20
- **SHA-256 (calculat de mine pe fișierul rezultat):** `6a780be320b3ed3a8cf8942352b7e8fbc5ac8b9054db395f6c6efa9739ce1fc8`
- **Semnare:** `Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` — `CN=BENSON, O=TOKKO` neschimbat.

---

## 6. Observație din captura de pe dispozitiv (17:33, build anterior)

Mesajul rostit/afișat: *„Nu am reușit să-l sun pe HANA pe WhatsApp (Nu am găsit: primul rezultat
pentru «HANA» (No node matched {"textContains":"HANA","wholeWord":tru…"*

Două lucruri, **niciunul nu e text de rezervă englezesc** și **niciunul nu e în scope aici**:

1. **Scurgere de eroare tehnică internă în mesajul utilizatorului.** `No node matched
   {"textContains":…}` e eroarea brută a Accessibility Guard-ului, împachetată de
   `src/core/mission/missionExecutor.ts` / `src/core/mission/tools/whatsappTool.ts` — **ambele pe
   lista interzisă**. Trebuie curățat mesajul acolo (mesaj scurt în română, fără JSON intern),
   rundă separată cu acele fișiere în scope.
2. **„HANA" = „Hannah" mistranscris** de Groq, iar rezolvarea de contacte face `wholeWord:true`
   fără fuzzy match, deci nu găsește „Hannah". Tot în `whatsappTool.ts` / contact resolver.

Puntea Build B a funcționat corect: `BRAIN_INTENT kind=action action=call_contact` →
`CANONICAL text="sună pe HANA pe WhatsApp"` → executorul determinist a rulat și a eșuat curat la
rezolvarea contactului — doar că mesajul lui de eșec e urât. Sanity check-ul a lăsat „HANA" să
treacă fiindcă arată a nume (are litere, un cuvânt, fără verb) — corect, „HANA" chiar poate fi un
nume.

Fără commit, fără push, fără tag.
