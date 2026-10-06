# RUNDA SETĂRI — CURĂȚENIE, 06.10.2026

## PASUL 0

Branch: `wip_2026_10_05_ac2`. Start: build-ul dinainte de AC2 (`5b1924e6e2a907e2fd948a391d9b4806df940ff5fd498cc371415749bfa8c26d`),
confirmat prin semnătura funcției `onHeedWakeDetected` (4 parametri, fără `continuedSpeech`) și `tsc` curat. HEAD-ul
branch-ului rămâne `3eaa27e` (AC3, doar comis, inactiv) — working tree-ul celor 4 fișiere de wake/microfon e restaurat
la starea sigură, neschimbată în această rundă.

**CORECTAT** (instrucțiune primită după primul raport): „nu se ating wake/microfon" înseamnă COMPORTAMENTUL, nu
locul butonului în UI. Mutarea în ecranul Dezvoltator e permisă și s-a făcut — vezi PASUL 2 mai jos. Nimic din
`HeedWakeWord.kt` sau din blocul de wake/ack din `BensonForegroundService.kt` (fișiere protejate) nu a fost atins;
confirmat prin `git diff 2fa9fb2 -- <cele 3 fișiere .kt>` = 0 linii.

## PASUL 1 — INVENTAR (read-only)

| Setare | Unde se citește | Efect | Verdict |
|---|---|---|---|
| anthropicKey/tavilyKey/openaiKey/geminiKey | `app/index.tsx` SAVE KEYS → `AsyncStorage.multiSet` (NU seif) | chei reale pentru chat/search | **vie**, dar **chei NU prin seif** — bug de securitate deja raportat, NU reparat aici → **mutată, logica neschimbată** |
| groqKey / deepgramKey | `settingsStore.saveEngineConfig` (seif real) | alimentează STT-ul native de wake + transcriere comandă | vie → **mutată (doar UI), logica/seifu neschimbate** |
| NUME DE TREZIRE (wakeName) | `changeWakeName` → push nativ | numele de trezire al motorului HEED | vie → **mutată (doar UI); `changeWakeName` neschimbată** |
| STT (sttNucleusId groq/local) | `changeSttNucleus` → `setSelectedEngineId('stt',…)` | motorul STT al seifului | vie → **mutată (doar UI)** |
| ASCULTARE/STT (sttEngine cloud/ondevice/local) | `startRecognition(lang, engine)` | motor STT pentru calea JS manuală (tap-to-talk) | vie, **separată** de rândul de mai sus (doi selectori STT distincți, confirmați amândoi live) → **mutată (doar UI)** |
| SUNET LA TREZIRE (wakeVolume) | `changeWakeVolume` → `setWakeChimeVolume` | volumul chime-ului de trezire | vie → **mutată (doar UI)** |
| WAKE WORD (wakeWordEnabled) | `toggleWakeWord` → `setWakeWordEnabled` (nativ, confirmat în `BensonForegroundService.kt:3368`) | kill-switch real al motorului nativ de trezire | vie → **mutată (doar UI); `toggleWakeWord`/push nativ neschimbate** |
| BACKGROUND LISTENING (backgroundMode) | `toggleBackgroundMode` → `startBackgroundService()` | pornește serviciul de fundal | vie → **mutată (doar UI)** |
| CAR MODE (carMode) | `toggleCarMode` → apelează `toggleBackgroundMode(true)` | ecranul mare + pornește background listening | vie → **mutată (doar UI); `toggleCarMode` neschimbată** |
| PICOVOICE ACCESS KEY | `getPorcupineStatus`/`setPorcupineAccessKey` (funcții native reale) | Porcupine, declarat „abandonat" în CLAUDE.md | **NU ștearsă — grep case-insensitive găsește consumator real**: `PorcupineManager` e instanțiat în `tryStartPorcupine()` (`BensonForegroundService.kt`), apelat din `startHotwordLoop()`, la rândul ei chemată din `ACTION_REVIVE`/`ACTION_RESUME_HOTWORD`/`resumeHotwordAndNotify` — căi reale, nu moarte. Abandonat ca decizie de produs (nivelul gratuit refuzat), dar NU zero-consumator în cod. → **mutată, NU ștearsă** |
| LANGUAGE (lang) | `changeLang` | limba BENSON | vie — **ecran principal** |
| VOICE (voiceEnabled) | `toggleVoice` | pornește/oprește vocea | vie — **ecran principal** |
| VOICE ENGINE (ttsProvider) | `toggleTtsProvider` + preview | motorul vocii (device/OpenAI/Gemini) | vie — **ecran principal** (parte din „voce") |
| VOICE SPEED (voiceRate) | `updateRate` | viteza vocii | vie — **ecran principal** |
| VOICE TONE (voicePitch) | `updatePitch` | tonul vocii | vie — **ecran principal** |
| VOICE SELECTION (voiceId) | `selectVoice` | vocea TTS exactă | vie — **ecran principal** |
| BENSON CALLS YOU (addressMode/masterName) | `changeAddressMode` | cum se adresează BENSON | vie — **ecran principal** |
| CHAT MODEL (modelProvider) | `toggleModelProvider` | LLM-ul de conversație | vie, fără legătură cu wake/mic → **mutată** |
| CHARACTER (character) | `changeCharacter`, folosit în `claudeAgent.ts`/`geminiAgent.ts`/`openaiAgent.ts` | persona LLM-ului de conversație | vie → **mutată** |
| REAMINTIRE ACCESIBILITATE (reminderMins) | `changeReminderMins` | interval reamintire vocală dacă Accessibility e oprit | vie, fără legătură cu mic → **mutată** |
| SERVICE STATUS + Testează tot + Debug Panel + Activează Accessibility | `checkServiceStatus`, diverse | panou de diagnostic | viu (doar citire) → **mutat** |
| Setup / Einrichtung | `setSetupWizardOpen(true)` | reporneşte wizardul de permisiuni | viu → **mutat** |
| App Permissions | `setAppPermOpen(true)` | ecranul BENSON 4 de permisiuni per-aplicație | viu → **mutat** |
| Enable Floating Bubble | `requestOverlayPermission` | permisiune overlay | viu → **mutat** |
| QUICK CONTACTS | `loadDeviceContacts`/`toggleQuickContact`/`saveQuickContacts` | bule rapide pe ecranul principal | viu → **mutat** |
| GOVERNED APPS (approvedAppIds) | `toggleAppApproval` → `saveApprovedAppIds` (`lib/appLauncherMemory.ts`), citit real în `lib/agents/appLauncherAgent.ts:227` | restricționează ce aplicații apar la căutarea pe categorie | **vie — aproape clasificată greșit „moartă"** la un grep insensibil la majuscule; verificată explicit înainte de a șterge → **mutată, NU ștearsă** |
| VIGNETTES | `saveVignetteExpiry` | reamintiri roviniete, Car Mode | viu → **mutat** |
| FAMILY | `updateFamilyField`/`saveFamily` | personalizare LLM conversație | viu → **mutat** |
| MEMORY (facts + wipe) | `wipeMemory` | fapte reținute | viu → **mutat** |
| FEEDBACK | doar afișare, comentariul din cod: „for the developer" | notepad vocal pentru dezvoltator | viu, explicit pentru dezvoltator → **mutat** |
| CE TRIMITE BENSON (analytics) | `updateAnalyticsConsent` | consimțământ telemetrie | viu → **mutat** |
| **AUTO CAR MODE** (switch) | `toggleAutoCarMode(v)` — **ignoră `v`, scrie mereu `'false'`**; switch-ul e `disabled`, `value={false}` hardcodat | **niciunul — nu poate fi niciodată pornit, handler-ul forțează mereu oprit** | **MOARTĂ, confirmat — ȘTEARSĂ** |
| Load paired Bluetooth devices / bondedDevices / carDeviceAddress / carDeviceName | `loadBondedDevices`/`selectCarDevice`, persistate, dar niciun consumator nativ sau JS în afara propriei afișări (verificat: zero referințe `.kt`) | **niciunul — scrie o stare pe care nimic n-o citește, de când Auto Car Mode a fost dezactivat la produs** | **MOARTĂ, confirmat — ȘTEARSĂ** |
| Închide complet (toggleSilence) | `toggleSilence` | kill-switch mic + sunet | vie, critică → **mutată (doar UI); `toggleSilence` neschimbată** |

## PASUL 2 — CURĂȚENIE

### Șters (cod mort confirmat, nu doar UI)
- Switch-ul **AUTO CAR MODE** + factLine + handler `toggleAutoCarMode` (ignora parametrul, forța mereu `false`).
- **Load paired Bluetooth devices** + lista `bondedDevices` + `carDeviceName`/`carDeviceAddress` (stare, ref, restaurare
  din `AsyncStorage`, funcțiile `loadBondedDevices`/`selectCarDevice`), import-ul `getBondedDevices`/`BluetoothDeviceInfo`
  din `benson-car-bluetooth` (rămas fără niciun alt consumator).
- Cele 3 chei `bensonAutoCarMode`/`bensonCarDeviceAddress`/`bensonCarDeviceName` scoase din `Promise.all`-ul de
  încărcare la pornire (destructurarea poziţională a fost actualizată cu grijă, fără să deplaseze celelalte 23 de
  variabile).

### Mutat pe ecranul „Dezvoltator" (ascuns, buton nou „Dezvoltator" la finalul Settings) — REVIZUIT
Runda a avut două treceri. Prima trecere mutase doar ce era clar neutru față de wake/mic (CHAT MODEL, REAMINTIRE
ACCESIBILITATE, CHARACTER, QUICK CONTACTS, GOVERNED APPS, VIGNETTES, FAMILY, MEMORY, FEEDBACK, CE TRIMITE BENSON,
SERVICE STATUS, Setup/Einrichtung, App Permissions, Enable Floating Bubble) și lăsase pe loc tot ce ținea de
wake/microfon. **Corecția primită**: „nu se ating" = comportamentul, nu locul UI. A doua trecere a mutat și restul,
NUMAI relocare JSX — zero linie de logică schimbată, aceiași handler-i, aceleași apeluri native:

Închide complet (`toggleSilence`), API KEYS (toate 6 câmpuri + SAVE KEYS + TEST LLM, `saveApiKeys`/`runLlmConnectionTest`
neschimbate), NUME DE TREZIRE (`changeWakeName`), STT groq/local (`changeSttNucleus`), SUNET LA TREZIRE
(`changeWakeVolume`), ASCULTARE-STT cloud/ondevice/local (`changeSttEngine`), BACKGROUND LISTENING
(`toggleBackgroundMode`), WAKE WORD (`toggleWakeWord`), PICOVOICE ACCESS KEY (`savePorcupineKey`/`refreshPorcupineStatus`
— NU ștearsă, vezi tabelul), CAR MODE (`toggleCarMode`).

Verificare că nimic din comportament s-a mișcat: `git diff 2fa9fb2 -- HeedWakeWord.kt BensonForegroundService.kt
CaptureEngine.kt` = **0 linii** — niciunul dintre cele 3 fișiere Kotlin protejate nu a fost deschis în această rundă.

### Ecranul principal, rezultat final
Doar 4 grupuri: **LANGUAGE**, **VOICE** (on/off + motor + viteză + ton + selecție voce), **BENSON CALLS YOU**
(adresare), plus butonul ascuns „Dezvoltator". Verificat prin listarea tuturor `s.label` dintre `visible={settingsOpen}`
și `</Modal>` — exact aceste 4, nimic altceva.

### Fișiere, linii
- `app/index.tsx`: 6378 linii. Modificare netă a rundei (comparativ cu baza sigură `2fa9fb2`, confirmat prin
  `git diff 2fa9fb2 -- app/index.tsx`): 565 linii schimbate — toate relocare 1:1 (cut/paste de JSX, zero logică nouă
  în afara: 1 stare nouă `devOpen`, 1 buton „Dezvoltator", 1 `<Modal>` nou cu header/close) + ștergerea confirmată
  moartă a Auto Car Mode.

## TESTE

- `npx tsc --noEmit`: curat, 0 erori (rulat de două ori — după ștergerea Auto Car Mode și după relocare).
- `npm test`: **lipsă script „test" în package.json** — raportat onest, neinventat, aceeași lacună cunoscută din rundele
  anterioare.
- `gradlew assembleRelease`: **BUILD SUCCESSFUL** de două ori (prima trecere 1m34s, a doua — după relocarea completă —
  57s). `validateSigningRelease` a trecut amândouă dățile (configurația existentă, CN=BENSON O=TOKKO, aplicată automat).
  APK final instalat pe dispozitiv: sha256 `8cd56b43f42d150d04b4639a65d87cf375704e0ada832075573ff4ec752315c6`.

## ACCEPTARE PE TELEFON (Rareș) — de confirmat, ACUM

- [ ] Settings arată **exact** cele 4 grupuri: Language, Voice (+motor+viteză+ton+selecție), Benson calls you — nimic
      altceva pe ecranul principal.
- [ ] Butonul „Dezvoltator" deschide ecranul ascuns și TOATE secțiunile mutate funcționează identic ca înainte:
      Închide complet, API Keys + Save Keys + Test LLM, Nume de trezire, STT, Sunet la trezire, Ascultare-STT,
      Background Listening, Wake Word, Picovoice, Car Mode, Service Status, Setup Wizard, App Permissions,
      Floating Bubble, Chat Model, Reamintire Accesibilitate, Character, Quick Contacts, Governed Apps, Vignettes,
      Family, Memory, Feedback, analytics.
- [ ] „Benson" → „Da, Master" merge ca înainte (fundal, ecran stins).
- [ ] WhatsApp apel + YouTube caută/oprește merg.
- [ ] Nicio cheie pierdută (comenzile cu cloud funcționează: Groq STT, Anthropic/OpenAI/Gemini chat, Tavily search).

## GIT — PLAN PROPUS, NEEXECUTAT (de aprobat de Rareș)

**Nu s-a făcut niciun commit.** Corect semnalat: `wip_2026_10_05_ac2` are HEAD la `3eaa27e` (AC3, cod de wake
neinstalat, nefuncțional — loop-ul „Da, Master" reperat și revertit manual în working tree). Un commit al curățeniei
pe acest branch ar sta PESTE acel commit, îngropând restaurarea manuală într-o istorie confuză.

**Plan**:
1. Branch nou `wip_2026_10_06_settings`, creat din `2fa9fb2` (același commit din care a pornit și
   `wip_2026_10_06_ac2_diag` — stabil, wake protejat, FĂRĂ AC2/AC3). Verificat acum: `git diff 2fa9fb2 -- HeedWakeWord.kt
   BensonForegroundService.kt CaptureEngine.kt` = 0 linii, deci working tree-ul actual e deja „2fa9fb2 + doar
   modificările acestei runde" pentru toate cele 4 fișiere relevante (confirmat și pentru `app/index.tsx`: diff
   565 linii, toate din Settings).
2. Pe branch-ul nou: `git add app/index.tsx SETTINGS_REPORT.md` → un singur commit, mesaj care numește runda.
3. **Nu se adaugă** nimic din `wip_2026_10_05_ac2` (AC2/AC3) la acest commit — rămân exact unde sunt, neatinse.
4. Graf după:
   ```
   2fa9fb2 ──┬── f9eda5a (AC2) ── 3eaa27e (AC3)         [wip_2026_10_05_ac2, neschimbat]
             ├── (diag, throwaway)                       [wip_2026_10_06_ac2_diag, neschimbat]
             └── <commit nou settings>                   [wip_2026_10_06_settings, NOU]
   ```
   Trei ramuri independente din același părinte stabil, niciuna peste alta.
5. Fără tag, fără push — doar branch + commit local, până la o decizie separată despre merge/tag/push.

Nu execut nimic din pasul de mai sus până nu aprobi planul.

---

# CORECȚIE RUNDA SETTINGS — 06.10.2026 (runda anterioară NU era aprobată)

## PASUL 1 — DIAGNOSTIC

**Întrebarea 1 — cum se ajunge la Dezvoltator?** Fără `__DEV__`, fără `process.env`, fără gest
ascuns. `app/index.tsx`, buton necondiționat:
```
onPress={() => { tap(); setDevOpen(true); }}
accessibilityLabel="Developer settings"
```
(linia exactă s-a mutat de câteva ori în timpul rundei; grep `Dezvoltator` îl găsește instant).
Cod-ul era corect de la runda precedentă. **Defectul nu era o poartă ascunsă — era un loc prost.**

**Cauza reală, confirmată pe captură de ecran** (nu ghicită): `VOICE SELECTION` rămăsese pe ecranul
principal cu lista NEFILTRATĂ (473 voci, toate limbile telefonului), sortată alfabetic — prima
literă e „ar" (arabă). Ecranul „BENSON CALLS YOU" și butonul „Dezvoltator" veneau DUPĂ acea listă.
Pe telefon, asta înseamnă sute de rânduri de derulat înainte de a ajunge la orice altceva — practic
negăsibile. Nu a fost niciun crash, nicio cheie pierdută, nicio poartă — doar o listă uriașă pusă
în calea a tot restul. Exact problema pe care runda precedentă (întreruptă) voia să o rezolve.

**Inventar complet, 2fa9fb2 → starea curentă:**

| Element (la 2fa9fb2) | Acum |
|---|---|
| LANGUAGE | **Ecran principal** |
| BENSON CALLS YOU (adresare) | **Ecran principal** |
| API KEYS (anthropic/tavily/openai/gemini/groq/deepgram) + SAVE KEYS + TEST LLM | Dezvoltator |
| NUME DE TREZIRE | Dezvoltator |
| STT (groq/local) | Dezvoltator |
| ASCULTARE-STT (cloud/ondevice/local) | Dezvoltator |
| SUNET LA TREZIRE | Dezvoltator |
| WAKE WORD | Dezvoltator |
| BACKGROUND LISTENING | Dezvoltator |
| PICOVOICE ACCESS KEY | Dezvoltator (NU șters — consumator real confirmat în `BensonForegroundService.kt`) |
| CAR MODE | Dezvoltator |
| Închide complet | Dezvoltator |
| Enable Floating Bubble | Dezvoltator |
| SERVICE STATUS + Testează tot + Debug Panel + Activează Accessibility | Dezvoltator |
| Setup/Einrichtung | Dezvoltator |
| App Permissions | Dezvoltator |
| CHAT MODEL | Dezvoltator |
| REAMINTIRE ACCESIBILITATE | Dezvoltator |
| CHARACTER | Dezvoltator |
| QUICK CONTACTS | Dezvoltator |
| GOVERNED APPS | Dezvoltator (confirmat viu — vezi runda precedentă) |
| VIGNETTES | Dezvoltator |
| FAMILY | Dezvoltator |
| MEMORY | Dezvoltator |
| FEEDBACK | Dezvoltator |
| CE TRIMITE BENSON (analytics) | Dezvoltator |
| VOICE ENGINE (ttsProvider) | Dezvoltator (mutat ACUM — la runda precedentă rămăsese pe principal) |
| VOICE (on/off) | Dezvoltator (mutat ACUM) |
| VOICE SPEED | Dezvoltator (mutat ACUM) |
| **VOICE TONE** | **ȘTERS definitiv** — niciun înlocuitor, nicăieri |
| **VOICE SELECTION** (473, toate limbile) | **ȘTERS** — înlocuit cu o listă nouă, filtrată ro/de/en, pe Dezvoltator |
| AUTO CAR MODE + Bluetooth pairing | Șters (runda precedentă — confirmat mort, zero consumator) |

**Vocea fixă a lui BENSON** (nou, cerut explicit): `bestRoVoiceId`, calculat din lista reală de voci
a telefonului — filtrează pe `language` începând cu „ro", preferă un `identifier` ce conține
„-local" față de unul cu „-network"; dacă nu există nicio voce -local, ia prima RO găsită. Cablat ca
fallback în `speakOnDevice` (singurul punct real de ieșire TTS pe calea „device"): `voice:
voiceIdRef.current || bestRoVoiceIdRef.current || undefined` — o alegere manuală din Dezvoltator
(pentru teste) are tot timpul prioritate. Loghează o singură dată, la calcul: `BENSON_VOICE_FIXED
identifier=… name=… quality=… isLocal=… roCandidates=…`.

**Confirmarea seifului de chei** — cod adăugat (`KEY_PRESENT`, per furnizor, niciodată cheia
întreagă) în `init()`, imediat după încărcare, pentru toate 6: anthropic/openai/gemini/tavily
(plain) + groq/deepgram (seif). Pe dispozitiv am prins O singură linie din cele 6 pe logcat
(`provider=deepgram present=true last4=••••0bed`) — celelalte 5 nu au apărut în capturile mele,
deși codul le loghează identic (același tipar ca VOICE_LIST din runda AC-diag, care are aceeași
problemă de vizibilitate pe acest telefon — bănuiala mea, neconfirmată, e o fereastră îngustă la
pornirea bridge-ului JS→nativ care înghite liniile foarte apropiate unele de altele). **Am verificat
altfel, direct pe captură**: în ecranul Dezvoltator, primul câmp (anthropic) arată puncte (valoare
încărcată, nu gol), iar Groq/Deepgram arată „salvat: ••••…" în placeholder — toate trei confirmate
nevide pe ecran, nu doar în log. Cer lui Rareș să verifice logcat-ul pe o pornire normală a
aplicației (nu scriptată prin adb), nu doar pe capturile mele.

## PASUL 2 — VERIFICAT PE DISPOZITIV (captură reală, nu doar cod)

Build nou instalat, deschis efectiv pe telefon (nu doar citit din cod):
- **Ecranul principal**: EXACT Language (EN/RO/DE) · Benson calls you (Master/Kalimero) · buton
  „Dezvoltator" — vizibil fără nicio derulare.
- **Ecranul Dezvoltator**: se deschide, arată cheile mascate (puncte / „salvat: …"), Nume de
  trezire = „Benson", STT, Sunet la trezire = „Tare" — toate intacte.
- **Lista de voci filtrată**: derulând, apar doar grupuri en-*/ro-*/de-* (ex. en-GB, en-IN, en-NG)
  — zero arabă, zero alte limbi. Filtrul `/^(ro|de|en)/i` funcționează.

## TESTE

- `npx tsc --noEmit`: curat, 0 erori.
- `npm test`: lipsă script „test" (aceeași lacună cunoscută, neinventată).
- `gradlew assembleRelease`: **BUILD SUCCESSFUL** (2m 8s). APK:
  `android/app/build/outputs/apk/release/app-release.apk`, 387.333.539 bytes (~369 MB),
  sha256 `8a18b305dd9dc9c3e0bc4dc39c2e6db907105ec62f32f77560be38ac123737f1`. Instalat pe dispozitiv.

## Fișiere, linii

- `app/index.tsx`: relocare JSX (VOICE ENGINE/VOICE/VOICE SPEED → Dezvoltator), ștergere VOICE TONE
  (17 linii) + VOICE SELECTION nefiltrată (33 linii), adăugare VOICE SELECTION filtrată ro/de/en
  (31 linii, nou), adăugare `devVoiceGroups`/`bestRoVoiceId`/`bestRoVoiceIdRef` (19 linii, nou),
  1 linie schimbată în `speakOnDevice` (fallback voce), 6 linii noi `KEY_PRESENT` logging.
  Niciun alt fișier atins. Wake/microfon (`HeedWakeWord.kt`, `BensonForegroundService.kt` partea de
  wake/ack, `CaptureEngine.kt`): zero diferență față de `2fa9fb2`, verificat din nou.

## ACCEPTARE — rămâne pe Rareș, pe APK-ul de mai sus

Nu fac commit. Aștept confirmarea pe dispozitiv (captură + logcat KEY_PRESENT pe pornire normală +
o comandă betonată) înainte de orice `git add`/`commit`/`tag`.

---

# PASUL 0 (exp/chatgpt-direct) — partea care închide runda Settings, 06.10.2026

Confirmat independent pe telefonul lui Rareș (mesajul lui): KEY_PRESENT 4/6 (anthropic, openai,
gemini, tavily), groq/deepgram lipsă din log deși UI arată „salvat" — exact ce bănuiam, acum
confirmat REPRODUCTIBIL, nu doar pe capturile mele instabile.

**Cauza, de data asta cu explicație, nu doar reparație**: `VOICE_LIST` (runda anterioară) are exact
același simptom — un `logAudioDiag` apelat devreme, într-un `useEffect`/`.then()` de la pornirea
rece a aplicației, nu ajunge fiabil în logcat pe acest telefon. Nu e specific SecureStore-ului:
hint-ul mascat din Settings (ACELAȘI `getEngineConfig`) se încarcă perfect câteva secunde mai
târziu, când userul deschide ecranul. Deci nu e o eroare de citire — e o fereastră de pornire în
care bridge-ul JS→nativ pierde liniile de log (nu și efectele reale: `setNativeWakeCredentials`
etc. funcționează, dovadă fiind toate „Comportamente dovedite" din CLAUDE.md).

**Fix, izolat, fără să ating push-ul de credențiale**: un `useEffect` nou, separat, cu
`setTimeout(…, 3000)`, care RE-citește (doar pentru log) `getEngineConfig('stt','groq'/'deepgram')`
la 3 secunde după montare — suficient timp după fereastra de pornire. Codul original (care face
push-ul real de credențiale către nativ) rămâne complet neatins.

**Etichete vizibile + câmpuri goale** (cerut explicit): fiecare din cele 6 câmpuri are acum o
etichetă permanentă deasupra (`Anthropic`/`OpenAI`/`Gemini`/`Tavily`/`Groq`/`Deepgram`), cu hint-ul
„· salvat: ••••last4" chiar în etichetă, calculat din cheia reală deja încărcată. Câmpul de
introducere însuși e acum un DRAFT separat (`apiKeyInput`/`openaiKeyInput`/`geminiKeyInput`/
`tavilyKeyInput`, gol la pornire) — cheia reală nu mai ajunge niciodată în `value`-ul unui
`TextInput`. `saveApiKeys()` rescris: scrie doar câmpurile ne-goale (exact convenția deja folosită
la Groq/Deepgram) — un SAVE KEYS apăsat din greșeală cu câmpuri goale nu mai șterge nimic.

**Model chat înlocuit**: `gpt-4o` → `gpt-5-nano` (`lib/agents/openaiAgent.ts`,
`GPT4O_MODEL`→`GPT5_NANO_MODEL`), eticheta din Dezvoltator actualizată. **Risc semnalat, nu
verificat pe dispozitiv**: nano e un model mult mai slab — tool-calling-ul (`askOpenAIWithTools`,
folosit și de misiuni) ar putea avea de suferit. Revert: `'gpt-4o'` la loc.

## Pasul 0(a)(b)(c) — cercetare, înainte de cod

- **(a) Germană azi**: NU. Grep complet pe `WA_MESSAGE_PATTERNS`/`WA_SEARCH_PATTERNS`/
  `WaCallVideoMatcher`/`MEDIA_CTL_PATTERN`/`YT_SEARCH_PATTERN` — zero verbe germane (doar
  „nein"/„abbrechen" la DA/NU de confirmare). → PARTEA A2 e necesară.
- **(b) Transcriere cea mai ieftină**: `gpt-4o-mini-transcribe`, $0.003/minut. Răspunsul are `usage`
  (input/output/total_tokens) dar NU are `language` — doar `gpt-4o-transcribe` (mai scump) are
  `languages` (array). `lang=` trebuie logat „n/a", niciodată ghicit.
- **(c) Text cel mai ieftin**: `gpt-5-nano`, $0.05/1M input · $0.40/1M output.

## TESTE

- `npx tsc --noEmit`: curat.
- `gradlew assembleRelease`: BUILD SUCCESSFUL (1m 1s). APK:
  `android/app/build/outputs/apk/release/app-release.apk`, 387.334.579 bytes,
  sha256 `536833af727ae4c75b7395d0945351187c1a45d572ef4ea9845ad30c87501db5`.
  **Neinstalat de mine** — per instrucțiune explicită, Rareș verifică hash-ul și instalează.

## Furculița — tot blocată

Tag-ul `settings-2026-10-06` tot nu există — runda Settings n-a primit încă un „merge"/„aprobat"
explicit (am primit doar rapoarte de bug-uri găsite, utile, dar nu o aprobare). Cu fix-ul de mai
sus, cred că se închide chestiunea KEY_PRESENT din lista de acceptare — dacă pe telefon arată bine,
spune-mi explicit „merge" și fac commit → tag `settings-2026-10-06` pe branch-ul propus, apoi
pornesc imediat `exp/chatgpt-direct` din el.
