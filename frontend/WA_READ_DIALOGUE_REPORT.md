# WA_READ_DIALOGUE — 2026-09-28

Scope autorizat: conectarea citirii existente la voce, răspuns contextual, finalizarea
mesajelor și verificarea apelurilor. Fără schimbarea interfeței aprobate; fără trimiteri
sau apeluri reale fără confirmarea utilizatorului. Plățile rămân manuale.

Baseline: HEAD 1f3ab3a, 30 fișiere urmărite modificate anterior, nimic staged.
Patch și copii ale fișierelor modificate în această rundă: scratchpad/wa_read_baseline_2026_09_28/.
Nu se suprascriu modificările anterioare, nu se folosesc reset/checkout/stash/clean.

CANON LOG
- [x] Scope: rutare/orchestrator, formatare citire, teste locale și acest raport.
- [x] Tip: integrarea citirii WhatsApp în dialogul existent, cu răspuns guvernat.
- [x] Documentația Expo SDK 54 consultată înaintea codului.
- [x] TypeScript BASELINE: 0 erori.
- [ ] Harness telefon BASELINE/FINAL: nu se rulează automat în paralel cu utilizatorul.
      H1 nu este 6/6 stabil; verificarea acestei runde începe cu teste locale fără efect extern.
- [ ] Teste locale finale / TypeScript.
- [ ] Build release / certificat / SHA-256.
- [ ] Instalare cu păstrarea datelor.
- [ ] DEVICE_PASS vocal: încă nedemonstrat. Nu se atribuie din build sau teste simulate.
- [ ] După DEVICE_PASS: commit → tag → push → verificare remote.

Executorul nativ de citire și executori de apel/trimitere: păstrați.
Revert integrare: WA_READ_DIALOGUE_ENABLED în missionOrchestrator.ts.
Niciun timer JS nou: expirarea contextului se verifică la următoarea cerere.

## Probe instalată, 2026-09-28

- APK release instalat păstrând datele (`Success`); captură cu aplicația reală WhatsApp și chatul
  Mona în prim-plan.
- Comanda scrisă a produs `WA_CHAT_VERIFIED header="Mona" nameMatch=true`,
  `WA_CHAT_READ messageCount=8`, apoi handoff `handled=true` și state `DONE`.
- Logul UI conține cele opt bule extrase. Fără scriere sau apel.
- TTS a ajuns în `TTS_SPEAKING`/`TTS_WATCHDOG_ARM timeoutMs=15000`, dar niciuna dintre capturile
  salvate nu conține `TTS_BLOCK_END`, `RESULT_TTS_DONE`, `TTS_WATCHDOG_TIMEOUT` sau
  `TTS_WATCHDOG_FIRED`. Nu se declară că utilizatorul a auzit rostirea. Transcriptul gol din
  sesiunea următoare este un rezultat STT separat.
- Următoarea probă necesară: o singură comandă vocală „Benson, citește-mi conversația cu Mona pe
  WhatsApp”, urmată doar de confirmarea utilizatorului dacă a auzit mesajele.

## Proba vocală ulterioară, 2026-09-29 — NEPASS la STT

- Utilizatorul a rostit comanda de două ori; captura asociată are 3.6s / 115200 bytes. Măsurarea
  locală pe ferestre scurte confirmă energie în două grupuri. Fișierul WAV temporar a fost analizat
  fără redare/transcriere și apoi eliminat din workspace.
- Providerul curent `deepgram` a returnat HTTP 200, transcript gol, `MISSION_INPUT_ALLOWED=false`;
  aplicația a logat `STT_ERROR no-speech` și `command_empty_or_timeout`. Nici `USER_INPUT_ACCEPTED`,
  nici `WAKE_COMMAND_DISPATCH`, nici `WA_READ_DIALOGUE` nu apar în traseul probei. Așadar comanda
  vocală nu a ajuns la funcția WhatsApp.
- Nu există dovezi de acces invalid la microfon, de defect VAD sau de defect al executorului de
  citire în această probă. Calea STT configurată explicit în cod este `USE_DEEPGRAM_DEV_STT=true`;
  Deepgram a dat un rezultat gol, nu o eroare HTTP. Nu se schimbă VAD și nu se introduce alt
  furnizor drept fallback implicit. Următoarea modificare trebuie să aleagă/configureze un primar
  STT explicit, compatibil cu bugetul și cheile deja salvate; căutare online, baterie de probe
  vocale repetate sau reexecutarea automată a comenzii nu sunt permise.
- Proba vocală rămâne NEPASS. Fără DEVICE_PASS, commit/tag/push-ul funcției de citire nu pornește.
