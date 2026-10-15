# HARTA BENSON

Document viu, actualizat de SANTINELA (zilnic, 07:00) și de Code la fiecare decizie de ordine a
lui Rareș. Nu e o specificație — e starea curentă, pe scurt, a celor 12 agenți.

## Cei 12 agenți

Nicăieri nu există o listă numerotată „cei 12 agenți" dată explicit — construită aici, transparent,
din lista închisă BENSON 1.0 (10, `CLAUDE.md`) + cele două runde noi active (12). Corectează dacă
nu e gruparea pe care o vrei.

| # | Agent | Stare | Dovada |
|---|---|---|---|
| 1 | Wake din fundal → „Da, Master" | ✅ VERDE | `live_test_2026-10-13d.txt`: 24× `WAKE_VERIFY result=accepted` |
| 2 | Deschide orice aplicație | 🔴 ROȘU | `APP_INDEX count=49` → „deschide calculatorul" → `APP_LAUNCH ok=false reason=not_found`, 21:17:30. Fix scris (`FIX_APPINDEX_ICON_COST_1`), compilat, neinstalat |
| 3 | YouTube — caută/pornește/pauză | ✅ VERDE | G3, `day_2026_10_08_last_run.log`: `YT_SEARCH`→`YT_PLAY_CONFIRM`→`YT_STOP`, toate PASS |
| 4 | Spotify — „pune X" | ⚠️ PARȚIAL | `CLAUDE.md`, RUNDA MUSIC-2: activare căutare intermitentă, selectează colecția nu piesa |
| 5 | WhatsApp — apel/video/mesaj | ✅ VERDE | G3: `WA_CALL_ASK/NO_ACTION`, `WA_VIDEO_ASK/NO_ACTION`, `WA_MESSAGE_ASK/NO_ACTION`, toate PASS |
| 6 | Navigație — „du-mă la X" | ✅ VERDE | `CLAUDE.md`, „Stare cunoscută": Waze cu governance, testat pe dispozitiv, nicio regresie raportată |
| 7 | Căutare internet via Perplexity/ChatGPT | ⚠️ PARȚIAL | implementat ca căutare Google generică (`tryNativeGeneralSearch`), nu Perplexity/ChatGPT cum cerea specul; „deschide google și vezi unde ne aflăm" nu face căutare (regresie de expectativă, nu de cod — raportat de Rareș 14.10) |
| 8 | Înapoi/acasă/curățenie după apel | ✅ VERDE | `CLAUDE.md`, „Stare cunoscută": auto-repair microfon C1+C2, dovedit 31.08 |
| 9 | Confirmare vocală a rezultatului și eșecului | 🔴 ROȘU azi | logica/confirmările native funcționează (G3 PASS), dar **bula nu mai apare** (raport Rareș 14.10) — rezultatul nu e vizibil, deși e rostit |
| 10 | Vocea ChatGPT + conversație + formulări libere | 🔴 ROȘU azi | MINA/brain funcțional (`BRAIN_CALL ok=true`, 21:34:29), dar Rareș raportează 14.10 voce engleză cu accent indian pe toate rostirile — PERF-1 (model test necomis) revertit ca prim pas, cauza completă neconfirmată (lipsă logcat/verificări Setări) |
| 11 | VOCEA ȘI CREIERUL (B.7–B.12, OpenAI TTS unic) | ⏳ NEÎNCEPUT | cercetare făcută (model/voci/preț); cod zero; test comparativ marin/sage/coral blocat (cheie API invalidă în acest mediu) |
| 12 | PERSONALUL (PASUL 1, APP_CAPABILITY_REGISTRY) | ⏳ NEÎNCEPUT | P1.0 inspecție gata (fără cod); P1.1+ în așteptare, cf. ordinii (BULA → VOCEA → PERSONALUL → Z) |

## Ordinea obligatorie (Rareș, arhitect-șef, 14.10.2026)

BULA → VOCEA ȘI CREIERUL (B.7–B.12) → PERSONALUL (PASUL 1) → RUNDA Z (integrarea, Z1–Z6).

## Ultima actualizare

14.10.2026, de Code, din diagnosticul de sesiune + rapoartele din conversație. Nu e încă o rulare
SANTINELA — SANTINELA pornește azi pentru prima dată.

---

## Verificare pe dispozitiv — 14.10.2026, 10:58–11:09 (Code, telefon conectat: CPH2663 `9c1464eb`)

Prima verificare live din această sesiune. Dovezi: `scratchpad/bula_live.log` (logcat propriu),
`aapt2 dump xmltree` pe APK-ul **instalat** vs. cel **compilat**.

### Agent 2 (Deschide orice aplicație) — cauza documentată anterior era GREȘITĂ

`PLAN_REPORT_2026-10-14.md` §A atribuia `APP_INDEX count=49` costului de randare a iconiței
(`FIX_APPINDEX_ICON_COST_1`). **Infirmat**: un cost de randare nu trunchiază o listă.

Cauza reală, demonstrată cu `aapt2` pe cele două APK-uri:

| APK | blocuri `<queries>` | `category.LAUNCHER` în `<queries>` |
|---|---|---|
| **instalat** pe telefon (13.10 21:30) | **2** | **0** |
| compilat din sursa curentă | **3** | **1** |

Manifestul sursă (`android/app/src/main/AndroidManifest.xml:37-47`, `@benson-launcher-queries`,
nemodificat din 29.08) are blocul și chiar prezice simptomul în comentariu: *„Without this,
Android 11+ package visibility caps the result at the auto-visible subset (~49 of 277 on the test
device)"*. APK-ul instalat nu-l are → `queryIntentActivities(MAIN+LAUNCHER)` vede ~49 din 850 de
activități de lansator reale (`cmd package query-activities` = 850) → cad ambele drumuri:

- JS: `APP_INDEX count=49` (`lib/appIndex.ts`, singurul emitent al acestui tag)
- nativ: `findLaunchablePackage("calculatorul")` nu vede `com.oneplus.calculator` →
  `10-14 10:58:48.183 APP_LAUNCH pkg=none ok=false reason=not_found`

Logica de potrivire RO/DE (RUNDA F2) e intactă — 36/36 teste JUnit verzi, inclusiv
`AppLaunchFixtureTest.calculatorulWinsAgainstFullRealField_oneplusNotAospId`, care cere exact
`com.oneplus.calculator` pe câmpul complet de aplicații. Nu e un bug de cod; e un APK vechi pe
telefon.

**Fix: zero cod nou.** APK compilat, semnat `CN=BENSON, O=TOKKO`, conține blocul.
SHA-256 `e0dfd35deb1a0ff60c9105e694a2427066fcccf08690ee6b63cd0fab0983bce7`.
Stare: **🔴 ROȘU încă — `adb install` BLOCAT de politica de permisiuni a sesiunii**, deci
nedovedit pe telefon. Ridicarea indexului la 274+ rămâne o predicție din manifest, nu o dovadă.

### Agent 9 (bula) — NEREPRODUS ca defect

Raportul „bula nu mai apare" nu s-a reprodus. Bula s-a desenat corect în test:
`10:58:46.714 BUBBLE show` → `46.870 OVERLAY_SHOW_ACTIVE` → `46.886 BUBBLE_EXPANDED_SHOW` +
`BUBBLE_TEXT_SHOW state=true transcript=true` → `49.660 BUBBLE hide reason=turn_done`.
Garda `OVERLAY_POLICY_REJECTED reason=no_resumed_activity` a funcționat corect (3×), adică
`RUNDA_BUBBLE_STABLE_VISIBILITY_1` a împiedicat ascunderea greșită. Prin design, bula se ascunde
când aplicația BENSON e ea însăși în prim-plan (`OVERLAY_HIDE_SELF_APP`) — probabil scenariul
raportat. Cere scenariul exact al lui Rareș; nu e de reparat orb.

### Agent 10 (creierul) — regresie NOUĂ de siguranță, demonstrată

```
10:58:37.669 DEBUG_INJECT text="cat este 9 ori 6"
10:58:45.735 BRAIN_CALL ms=8014 type=command action=OPEN_APP ok=true
10:58:45.744 NATIVE_ROUTE action=open_app target="ebay"
```

O întrebare de aritmetică a fost clasificată de MINA (`gpt-5-nano`, INTEGRATION-2) drept
`OPEN_APP` către **eBay**. Nu s-a deschis nimic doar pentru că eBay nu e instalat
(`reason=not_found`) — adică exact din cauza bug-ului de vizibilitate de mai sus.

**Cuplaj periculos de reținut înainte de instalare**: ramura `OPEN_APP` din `executeBrainCommand`
(`BensonForegroundService.kt:3498`) apelează `tryNativeOpenApp("deschide $app")`, care pe o
potrivire bună **deschide direct, fără ConfirmationGate**. Repararea vizibilității ridică câmpul
vizibil de la ~49 la ~850 de aplicații, deci mărește raza de acțiune a unei halucinații a
creierului. Contravine doctrinei 6 (Confirmation Gate înaintea oricărei acțiuni) și 7 (creierul
nu stă pe drumul comenzilor). Propus ca următoarea rundă, cu flag de revert — nu implementat azi
(o rundă = un tip de schimbare).

Secundar, din același log: două apeluri LLM pe tură (`brain_turn_gpt5nano` + `interp_gpt5nano`
shadow, `INTERP_SHADOW old=APP_OPEN new=CHAT match=false`) și `BRAIN_CALL ms=8014` — 8 s,
departe de ținta <700 ms.

### Ultima actualizare

14.10.2026, 11:10, de Code — din verificare live pe dispozitiv, nu din rapoarte.

---

## Agent 12 — PERSONALUL: LEGEA PERSONALULUI BENSON înscrisă + nucleul implementat (14.10.2026)

Stare: **🟡 PARȚIAL** — legea e înscrisă permanent, nucleul e implementat și testat offline,
**nimic nu e încă dovedit pe telefon** (instalarea e blocată, vezi secțiunea precedentă).

### Legea, înscrisă în trei locuri permanente

| Loc | Ce conține |
|---|---|
| `docs/specs/LEGEA_PERSONALULUI_BENSON.md` (nou) | textul normativ permanent, 9 secțiuni |
| `CLAUDE.md`, secțiune proprie după „Doctrina produsului" | legea pe scurt, 7 puncte + revert |
| aici, agent 12 | starea curentă cu dovezi |

Sursele verbatim (`PASUL_1_REGISTRY_2026-10-14.md` 09:24, `PASUL_1_MOTOR_2026-10-14.md` 09:25)
rămân neatinse — legea le citează, nu le rescrie.

### Ce e implementat efectiv

- `StaffRegistryCore.kt` (nou, **pur**, zero Context/org.json): fișa angajatului (M2), trei
  niveluri (P1.2), `demoteAfterUpdate` (M4), `diff`/`reconcile` cu arhivare (M3), restricții
  (bancar → refuz), serializare, garda de plauzibilitate.
- `StaffRegistry.kt` (nou, Context): **enumerarea unică** MAIN+LAUNCHER, persistență locală în
  `benson_staff_registry`, jurnal de mișcări (max 200), log `REGISTRY count= known= verified=` și
  `REGISTRY_EVENT kind=` — fără nume de aplicații.
- `BensonForegroundService.kt` (+~45 linii): flag `APP_REGISTRY = true`, bootstrap pe thread
  separat la `onCreate` (deci automat la prima instalare, după permisiuni), receiver
  `PACKAGE_ADDED/REMOVED/REPLACED`, unregister în `onDestroy`.
- **Un singur inventar**: `findLaunchablePackage` citește acum din registru
  (`launchableNameSource()`); scorarea F2/`AppNameMatcher` e **neschimbată**. Registru gol → cade
  pe enumerarea live de dinainte, deci „deschide X" nu are nicio fereastră de nefuncționare.

`verified` e semănat **exclusiv** din „Comportamente dovedite" (CLAUDE.md): WhatsApp
apel/video/mesaj, YouTube căutare/control, Waze navigație, Calculator. **Spotify rămâne `known`**
— CLAUDE.md îl declară explicit PARȚIAL; există un test care cade dacă cineva îl promovează.

### Ce NU e implementat (următoarea rundă, tip de schimbare diferit)

Uneltele pentru creier (P1.5: `list_available_apps`, `find_app_by_purpose`,
`get_app_capabilities`, `inspect_current_screen`, `perform_ui_action`, `verify_action_result`;
M5: `staff_changes`, `get_app_dossier`) — **nescrise**. Deci testele 1–12 din specuri (întrebările
rostite „Ce aplicații de navigație am?" etc.) **nu pot trece încă**: registrul există, dar creierul
încă nu-l poate interoga. `discovered` nu are producător.

### Teste

| Test | Rezultat |
|---|---|
| `StaffRegistryCoreTest` (nou, 22 teste) | **PASS** 22/22 |
| `AppLaunchFixtureTest` + `AppNameSynonymsTest` + `AppNameMatcherTest` (regresie resolver) | **PASS** 36/36 |
| `REGISTRY count=` pe telefon | **BLOCKED** — instalare blocată |
| Testele 1–12 din specuri | **BLOCKED** — cer uneltele creierului (nescrise) |

---

## FIX_BRAIN_OPEN_APP_GATE_1 — poarta de confirmare pe OPEN_APP de la creier (14.10.2026)

Regresia de siguranță demonstrată mai sus (întrebare de aritmetică → `OPEN_APP ebay`) e **reparată**,
nu doar raportată. Creierul acum **propune**: „Deschid X?" → „da" execută, „Nu" închide sesiunea
fără nicio acțiune, două răspunsuri neclare o abandonează.

- Mecanism **reutilizat 1:1** de la `PendingYoutubePlay`/`handlePendingYoutubePlayAnswer` (dovedit
  G3): `classifyConfirmationNative` → YES/NO/2 încercări. Zero mecanism de confirmare nou.
- Se aplică **exclusiv** ramurii `OPEN_APP` din `executeBrainCommand`. Calea deterministă
  (`tryNativeOpenApp` din parser, când utilizatorul chiar a spus „deschide X") e **neatinsă** —
  comportamentul dovedit 20.09 „deschide Calculatorul" se execută în continuare direct, fără
  întrebare în plus. Doctrina 7 („BENSON execută, nu interpretează") cere exact această separare.
- Ordinea e intenționată: poarta intră în **același** APK cu repararea vizibilității, ca primul
  build instalat să nu aibă niciodată ~850 de aplicații deschizabile de o halucinație negăzuită.

Revert: `BRAIN_OPEN_APP_CONFIRM = false`.

Teste: `StaffRegistryCoreTest` 22/22, regresie resolver 36/36, `tsc` 0, `assembleRelease` verde,
APK semnat `CN=BENSON, O=TOKKO`, SHA-256 `e89dbfd6e22ec31ea714307ed88bc55b80148727d7305e44ffbfd49b43a7df22`.
**Nedovedit pe telefon** — `adb install` rămâne blocat de politica de permisiuni a sesiunii.

---

## VALIDARE PE TELEFON — 14.10.2026, 11:51–12:08 (build instalat, OnePlus Nord 4)

APK final: SHA-256 `436e258b06907d142fedb0b44a1319418fc231cf479992fa23132c1cedf41e6c`.
Dovezi: `scratchpad/val*.log`, `scripts/regression/day_2026_10_08_capture.log`.

| # | Test cerut | Verdict | Dovada |
|---|---|---|---|
| 1 | registrul personalului | ✅ **PASS** | `REGISTRY_BOOTSTRAP staff=281 reason=service_start` → `REGISTRY count=281 known=9 verified=4`, automat la prima pornire |
| 2 | Calculator / YouTube / Amazon Shopping | ✅ **PASS 3/3** | `APP_LAUNCH pkg=com.oneplus.calculator ok=true`, `pkg=com.google.android.youtube ok=true`, `pkg=com.amazon.mShop.android.shopping ok=true` (a ales Shopping, nu Business/Alexa/Prime) |
| 3 | „Cât este 9 ori 6?" → 54 | ✅ **PASS, după reparație** | `BRAIN_CALL ms=5993 type=chat ok=true` → „9 înmulțit cu 6 este 54." |
| 4 | regresia G3 integrală | ⚠️ **7 PASS / 3 defect de fixtură** | YouTube caută/pornește/oprește + „oprește muzica" PASS. Cele 3 WA_*_NO_ACTION: `WA_RESOLVE candidates=0` — contactul „Ingrid" nu există pe telefon, deci nu s-a creat confirmare. `leaked=no` la toate → zero execuție. Reluat cu un contact real: PASS (mai jos) |
| 5 | bula · wake · follow-up 8 s | ✅ **PASS** / wake vocal **BLOCKED** | bula: `BUBBLE show` + `OVERLAY_SHOW_ACTIVE` la fiecare tură, text corect, inclusiv „ASCULT". 8 s: `SESSION open 11:55:20.783` → `close reason=timeout 11:55:28.783` = exact 8,000 s. HEED armat: `NWW_HEALTH micOwner=WAKE engine=heed running=true`, `WAKE_REARM ok=true`. „Benson" rostit real **nu se poate testa de aici** (fără voce) |
| 6 | Waze · WhatsApp · ConfirmationGate | ✅ **PASS, după reparație** | WA: `WA_RESOLVE candidates=1 chosen=H` → „Dorești să sun la HANNAH?" → „nu" → `CONFIRM_RESULT verdict=NO`, `SESSION close reason=wa_confirm_no`, **zero** `WA_INTENT`/`WA_NATIVE_CALL_VERIFY`. Waze: vezi reparația de mai jos |

### Regresii găsite și REPARATE în această sesiune

**1. `FIX_BRAIN_TIMEOUT_1` + `1b` — creierul nu răspundea deloc.**
Cauză din log: `BRAIN_ERROR type=InterruptedIOException` la `ms=10002`/`10005`, adică
`brainHttpClient.callTimeout(10s)`; latențele reale măsurate azi: 8014 / 10002 / 10005 ms. Prima
versiune a fixului a fost **incompletă** — doar `callTimeout(25s)` a lăsat `readTimeout`-ul
implicit OkHttp (10 s) ca plafon real, dovedit imediat pe telefon:
`BRAIN_ERROR type=SocketTimeoutException ms=10006`. Acum sunt setate explicit toate.
Revert: `callTimeout(10, SECONDS)` singur.

**2. `FIX_NATIVE_NAVIGATE_1` — agentul 6 (Navigație) era CĂZUT, nu verde.**
Corecție la harta de mai sus: rândul „Navigație — du-mă la X ✅ VERDE" era **greșit**, bazat pe
dovada din 29.08 de pe calea JS. Cauză demonstrată: nu există niciun executor nativ de navigație;
calea JS a fost închisă de RUNDA J (`JS_VOICE_HANDOFF=false`, 12.10), iar INTEGRATION-2 a cablat
ramura `NAVIGATE` a creierului la `tryGlobalNav("du-mă la X")` — a cărei expresie e
`^(inchide|iesi|acasa|inapoi)$`, deci **returna `false` întotdeauna: cod mort**. Dovadă pe telefon
înainte de fix (12:02:17): „du-mă la aeroport" → zero rută de navigație → brain → „Nu am înțeles".

Reparat cu executor nativ care alege aplicația **după capabilitate din registru**, cu confirmare
înainte de pornire și URI-urile deja dovedite (`waze://?q=…&navigate=yes`, rezervă
`google.navigation:q=…`, copiate din `wazeTool.ts`, neinventate). Dovadă după fix (12:08:03):
```
NATIVE_ROUTE action=navigate_confirm target="aeroport" staff=com.waze level=VERIFIED
BUBBLE "Te duc la aeroport cu Waze. Pornesc?"  →  "nu"  →  CONFIRM_RESULT verdict=NO
SESSION close reason=nav_confirm_no          (zero NAV_LAUNCH — nicio navigație reală pornită)
```
`staff=com.waze level=VERIFIED` = aplicația a fost aleasă **după scop**, nu după nume — prima
bucată funcțională din L-SERVICE-001 punctul 1.2. Revert: `NATIVE_NAVIGATE = false`.

**3. `LAUNCH_FAILED` ≠ `NOT_FOUND`** (L-SERVICE-001 punctul 1.1): aplicație găsită dar nelansabilă
spune acum „Am găsit X, dar n-am putut să o deschid.", nu mai cade pe drumul „nu există" și nu mai
tace. Revert: `if (!ok) return null`.

### Problema deschisă #1 — latența creierului

`BRAIN_CALL` măsurat azi: 8014 / 10002 / 10005 / 5286 / 5993 ms. Chiar reușit, un răspuns la
5–6 s e departe de ținta <700 ms. Cauze identificate, **nereparate**: `gpt-5-nano` cu
`json_schema` strict; plus un **al doilea apel LLM pe fiecare tură** (`interp_gpt5nano`, shadow
experimental) care rulează chiar și pe comenzi deterministe (`deschide calculatorul`,
`suna la Hannah`) și care a dat și el `INTERP_ERROR type=InterruptedIOException`. Propuneri (cer
acord, una e derivată din documentația API): oprirea shadow-ului; `reasoning_effort` minim.

---

## DROIDRUN / MOBILERUN — Faza 1 executată, Faza 2 BLOCATĂ (14.10.2026, 12:10–12:25)

### Ce s-a instalat efectiv

`mobilerun 0.6.22`, **licență MIT**, `Requires-Python >=3.11,<3.14`, 35 dependențe.
Mediu **izolat, în afara repo-ului BENSON**: `C:\Users\lenovo\Desktop\BENSON-LAB\mobilerun\.venv`
(CPython 3.13.15 instalat prin `uv`, fără admin, fără Microsoft Store — pe mașină nu exista Python).
Fără Mobilerun Cloud, fără login, fără abonament.

### Concluzia arhitecturală decisivă

Mobilerun **nu rulează pe telefon**. Arhitectura lui, confirmată din README oficial + suprafața CLI:

- agentul LLM e un **proces Python pe PC**;
- controlul dispozitivului merge prin **ADB** (USB/TCP);
- pe telefon se instalează **`com.mobilerun.portal`** (APK 51 MB) cu **propriul
  AccessibilityService**, pe care `mobilerun setup` îl și activează.

`mobilerun devices` a găsit telefonul: `Found 1 local device(s): 9c1464eb` — deci metoda de
conectare e verificată și funcțională, dar e **ADB de pe acest PC**.

Prin urmare **FAZA 4 nu e atingibilă prin integrarea Mobilerun ca runtime**: ar face permanent
obligatoriu laptopul + ADB, exact ce ordinul interzice („Nu impune laptop, ADB sau un server extern
ca cerință permanentă", „Nu declara integrarea finalizată dacă depinde de calculator"). Se aplică
clauza prevăzută în ordin: **se păstrează ca referință, se reutilizează doar algoritmii**.

### Incident de raportat: `doctor` NU e read-only

Am rulat `mobilerun doctor` așteptând doar diagnostic. Comanda a **descărcat și a împins singură
Portal APK pe telefon** și a încercat să-l instaleze. Instalarea a fost **respinsă de OxygenOS**:
`Failure [INSTALL_FAILED_VERIFICATION_FAILURE]`.

Stare după incident, verificată:

| Verificare | Rezultat |
|---|---|
| APK BENSON | **neatins** (`lastUpdateTime=2026-10-14 12:07:44`, PID 8813 viu) |
| Servicii de accesibilitate active | **doar** `com.benson.butler/...BensonAccessibilityService` |
| `com.mobilerun.portal` | **neinstalat** |
| Reziduu `/data/local/tmp/com.mobilerun.portal-539.apk` (53,6 MB) | **șters**, telefon readus la starea anterioară |
| Regresie BENSON (test după incident, 12:25) | **zero**: `APP_LAUNCH pkg=com.oneplus.calculator ok=true`; `navigate_confirm staff=com.waze level=VERIFIED`; zero `NAV_LAUNCH` |

Lecție de integrare: comenzile Mobilerun mută starea dispozitivului fără să o anunțe în nume.
Orice folosire viitoare se face cu dispozitivul declarat explicit și cu pași reversibili.

### Faza 2 — BLOCKED, cere decizia Masterului (trei porți)

1. **Permisiune Android / securitate**: instalarea Portal cere dezactivarea verificării
   instalărilor pe OnePlus (Play Protect / „Install unknown apps"). E o slăbire de securitate a
   telefonului — nu o fac fără acord explicit.
2. **Conflict de accesibilitate**: `mobilerun setup` activează un AL DOILEA AccessibilityService
   pe același telefon, lângă cel al lui BENSON (care e „degetul" și e protejat prin CLAUDE.md).
   Android permite servicii multiple, dar două automatizări UI pe aceleași ecrane se pot încurca
   reciproc. Ordinul însuși cere verificarea conflictelor înaintea activării.
3. **Cost API**: fiecare `mobilerun run` consumă tokeni la un provider LLM (agent multi-pas, cu
   screenshot-uri → cost pe imagine). Plafonul BENSON e 2 EUR/zi, comun. Cer acord pe provider,
   model și plafon separat înainte de a rula cele 4 teste.

### Ce se poate reutiliza fără Python pe telefon (Faza 3, propunere)

BENSON are deja echivalentele native pentru: Accessibility tree (`BensonAccessibilityService`),
identificarea elementelor (`ControlLocator`/`ControlSynonyms`, G-1), verificarea rezultatului
(doctrina 9). Lipsesc, și exact astea merită împrumutate ca **algoritmi**, rescrise în Kotlin:
**(a)** reprezentarea compactă a ecranului pentru LLM (tree + screenshot adnotat cu indici de
element), **(b)** planificarea multi-pas cu reîncercare, **(c)** bucla observă→acționează→verifică.
Nicio linie de Python pe telefon.

---

## FAZA 3, item 1 — SCREEN OBSERVATION livrat; Amazon descoperă o orbire reală (14.10, 12:30–12:46)

APK: SHA-256 `010051f493563b203c54109dae63ae6e2e00364e8b767a95859209ae0cda90b9`.

### Livrat (cod + teste)

- `ScreenDigest.kt` (nou, **pur**): reprezentare compactă și INDEXATĂ a ecranului — rol, etichetă,
  poziție, stare, acțiuni; antet `UNTRUSTED_DATA`; redactarea câmpurilor de parolă; deduplicare
  ierarhică; plafon 60 de elemente cu trunchiere raportată; verdict de siguranță pe fiecare element
  prin `ButtonSafetyClassifier` **existent** (nu o listă nouă).
- `UiNode` extins **aditiv** (bounds/scrollable/enabled/focused/password, toate cu implicit) —
  `ControlLocatorTest` și `UniversalHandCommandMatcherTest` rămân verzi, deci G-1 neatins.
- `BensonAccessibilityService.observeScreen()` + `currentScreenEpoch()`: citește
  `rootInActiveWindow` PROASPĂT, incrementează epoch → indicii unui digest vechi se invalidează.
- `DEBUG_SCREEN_DIGEST` (debug-only, în receiverul existent): observă și scrie în logcat. Zero
  acțiune, zero apel LLM, **zero cost**.
- Teste: `ScreenDigestTest` **19/19**; întreg modulul **102/102**, 0 eșecuri.
- **Atribuire**: conceptul (tree + indici de element pentru agent) vine din DroidRun/Mobilerun,
  MIT. Aici e reimplementare în Kotlin, scrisă de la zero — zero cod copiat, zero Python/ADB/Portal.

### Dovedit pe telefon: ochii văd

YouTube după căutare, `epoch=4`, 34 de elemente, fără coordonate preprogramate:
`[1] button "queen"` · `[10] text "Queen, Offizieller Künstlerkanal"` · `[14] button "Queen abonnieren."`

### Dovedit pe telefon: TREI defecte reale, raportate ca FAIL

Cerere reală a Masterului: Amazon + „baterii AA"; apoi căutare web + primul link. **Toate au
eșuat.** Trasee din log (12:43–12:45):

**1. Orbire pe aplicații mari — cauza rădăcină.** `snapshotTree` e plafonat la `MAX_NODES = 400`
(constantă G-1 preexistentă), parcurs în adâncime, în ordinea documentului. Pe ecranul principal
Amazon bara de căutare **nu intră deloc în arbore**. Consecințe în lanț, ambele observate:
`CONTROL_FIND intent=search pkg=com.amazon… layer=semantic match=none result=none` (G-1 nu o
găsește) și digest cu **0 elemente `input`** (27 button / 26 text / 5 image / 2 container).
Onest: `FIX_DIGEST_PRIORITY_1`, scris special ca să salveze câmpul de căutare, **nu a avut efect** —
nu poți prioritiza un nod care nu e în arbore. Prioritizarea rămâne corectă (test dedicat verde pe
arbore sintetic), dar e inutilă până se ridică plafonul.

**2. Rutare greșită a căutării web.** „caută în internet robot de curățat ferestrele până la 80
euro" a fost capturat de căutarea în-aplicație (Amazon în prim-plan), nu de căutarea web:
`CONTROL_FIND intent=search pkg=com.amazon…`. `GeneralSearchMatcher` e verificat DUPĂ
`tryContextSearch`, iar excluderile lui nu acoperă „în internet". Deci pasul WEB din L-SERVICE-002
nu e atins niciodată când o aplicație e deschisă.

**3. Eșec silențios.** Când căutarea în-aplicație nu găsește controlul, BENSON **nu spune nimic** —
zero linie `TTS_NATIVE_REQUEST` după cele trei comenzi. Încalcă doctrina 9 („mesajul spune exact ce
n-a mers") și e exact comportamentul de „lansator", nu de majordom, pe care L-SERVICE-001 îl
interzice.

**4. „apasă pe primul rezultat"** → `CONTROL_FIND intent=primul rezultat … result=none`: fraza nu
e în `ControlSynonyms`, deși `ControlLocator.findFirstResult` există și e folosit de YouTube.

### Următoarea rundă (ordinea e impusă de dependențe)

1. Ridicarea plafonului de parcurgere, cu flag + măsurare de latență, și **suita G3 completă** după
   (atinge WhatsApp/YouTube — nu se face fără regresie verde).
2. „caută în internet/pe Google X" → web, înaintea căutării în-aplicație.
3. Eșecul căutării devine mesaj rostit, niciodată tăcere.
4. „primul rezultat" cablat la `findFirstResult` existent.

### FIX_TREE_CAP_1 — orbirea pe aplicații mari, REPARATĂ (14.10, 12:50–12:53)

APK: SHA-256 `faa42f00e0a64512530e6e30084b28be242f8c5db6cf32d6707181b323700da7`.

Cauza rădăcină confirmată: `snapshotTree` era plafonat la `MAX_NODES = 400`, deci pe Amazon bara
de căutare nu intra în arbore. Ridicat la **3000** (flag `TREE_CAP_RAISED`, revert = 400).
Adăugat `ControlLocator.findSearchField` (recunoaștere **structurală**: câmp editabil → viewId de
căutare → cuvinte → unicul câmp editabil din ecran; `findControl` neatins) și `FIX_SEARCH_VOCAB_1`
(„suche" lipsea: matcher-ul face `contains`, iar `"Suche".contains("suchen")` e false).

**Înainte → după, pe aceeași comandă („caută baterii AA" în Amazon):**

| | înainte (12:43) | după (12:50) |
|---|---|---|
| G-1 | `CONTROL_FIND intent=search result=none` | `result=found layer=desc` |
| acțiune | — (eșec silențios) | `CONTROL_ACT search_open_tap ok=true` → `search_set_text ok=true text="baterii aa"` |
| digest | `elements=60 truncated=true`, **0 input** | `elements=43 truncated=false`, `[2] input "baterii aa" editabil,focalizat` |
| ecran | nimic | sugestii reale: `[7] button "batterie aa"`, `[11] button "batterie aaa"` |

**PASS parțial, onest**: bara găsită, apăsată, textul introdus și verificat pe ecran. **Trimiterea
căutării (enter / tap pe sugestie) nu e încă implementată**, deci lista de produse nu e deschisă.

### Regresie G3 după ridicarea plafonului: 9 PASS / 1 intermitent

WhatsApp integral verde cu contact real (apel/video/mesaj → „Nu" → `wa_confirm_no`, zero
`WA_INTENT`). `YT_STOP`, `STOP_MUZICA`, `YT_SEARCH` PASS.

`YT_PLAY_CONFIRM` **intermitent, neatribuibil acestei runde**: sondă de 2 rulări pe același build,
YouTube în prim-plan — run 1 `found=false` (1,69 s), run 2 `found=true
title="Madonna, Offizieller Künstlerkanal"` → „Îl pornesc?" → „nu" → `CONFIRM_RESULT verdict=NO`.
Cauza e fereastra fixă de ~1800 ms din `tryYoutubeSearchConfirm` înainte de prima căutare pe ecran,
documentată deja ca intermitentă în CLAUDE.md (MUSIC-2). Poarta de confirmare funcționează corect.

### Defecte noi observate, nereparate (pentru următoarea rundă)

1. **Creierul expune nume de enum utilizatorului**: „Ce acțiune dorești pe YouTube acum:
   SEARCH_IN_APP (căutare) sau OPEN_APP (deschide aplicația)?" — jargon intern rostit.
2. **Cursă fallback vs. ecran**: „Nu am înțeles" s-a rostit la 12:53:22.490, cu 30 ms ÎNAINTE ca
   `YOUTUBE_SEARCH_CONFIRM found=true` să sosească — două răspunsuri pentru o comandă.
3. Rutarea „caută în internet X" încă e capturată de căutarea în-aplicație (vezi mai sus).

### Op. 1 + Op. 2 (aprobate de Master) — cod livrat, testat offline, **device BLOCKED** (14.10, 13:00)

APK: SHA-256 `bcffe4f211dfce71fd001ad1bed7b1c8cb7694042ff642ef3db71e8624f590a4`.
**Neinstalat**: telefonul s-a deconectat de pe USB chiar înaintea instalării
(`adb: no devices/emulators found`; `adb kill-server`/`start-server` + așteptare 2 min — nu a
revenit). Toate verdictele pe dispozitiv de mai jos sunt **BLOCKED**, nu PASS.

**Op. 1 — `FIX_SEARCH_SUBMIT_VERIFY_1`.** Cauză dovedită pe telefon (12:50:56): ramura `Search` din
`runUniversalHandCommand` se oprea după `ACTION_SET_TEXT` și raporta „Caut X." — **succes declarat
fără nicio dovadă pe ecran**, exact ce interzice doctrina 9. Acum: `ACTION_IME_ENTER` (mecanismul
din MUSIC-2) → dacă nu apar rezultate, apasă sugestia corespunzătoare → verifică un rând real prin
`ControlLocator.findFirstResult` + `SearchResultNoiseFilter` (ambele existente) → `SEARCH_VERIFY
query= found= first=`. Succes **numai** cu rând de rezultat pe ecran; altfel: „Am scris X în
căutare, dar nu s-au afișat rezultate."

**Op. 2 — `ExplicitWebSearchMatcher` + `FirstResultMatcher`** (fișier nou, pur) + rutele
`tryExplicitWebSearch` / `tryFirstResult`, verificate **înaintea** căutării în-aplicație.
`GeneralSearchMatcher` și `ControlSynonyms.TABLE` **neatinse** (cerință explicită): matcherul nou
prinde doar cazul în care Masterul numește internetul/motorul. Context păstrat prin
`lastSearchQuery`, setat **atât** de căutarea web **cât și** de cea în-aplicație, deci „apasă pe
primul rezultat" continuă oricare dintre cele două comenzi. Clicul reutilizează
`findFirstResult` + `clickableTarget` + `clickNodeOrAncestor`; succesul se declară doar dacă
**ecranul s-a schimbat** după click (`FIRST_RESULT_VERIFY screen_changed=`).

Teste offline: **260 teste, 0 eșecuri** pe ambele module (incl. `ExplicitWebSearchMatcherTest` cu
fraza reală a Masterului din 12:45, `FirstResultMatcherTest`, `ControlLocatorTest` 9/9 →
G-1 neatins). `tsc` 0, `assembleRelease` verde, semnat `CN=BENSON, O=TOKKO`.

Reverturi: `EXPLICIT_WEB_SEARCH=false` · `FIX_SEARCH_SUBMIT_VERIFY_1` (revino la linia
`ControlResult(ok, …)`) · `TREE_CAP_RAISED=false` · `FIX_SEARCH_STRUCTURAL_1` ·
`FIX_SEARCH_VOCAB_1`.

De făcut la reconectare: Amazon „baterii AA" până la rezultate afișate · „caută pe internet robot
de curățat ferestrele până la 80 euro" → „apasă pe primul rezultat" · regresia G3.

---

## VALIDARE op. 1 + op. 2 pe telefon — 14.10.2026, 15:01–15:18 (OnePlus Nord 4)

APK final: SHA-256 `35b3a6e1a7555a83fe758514c7d90d44b1a4c2d058adf8ed7ce2e3ed1813be6a`.
Instalat cu `-r` (date păstrate). Dovezi: `scratchpad/t2b.log`, `t3c.log`,
`scripts/regression/day_2026_10_08_last_run.log`.

| Test | Verdict | Dovada |
|---|---|---|
| 1. instalare fără ștergerea datelor | ✅ **PASS** | `lastUpdateTime=2026-10-14 15:01:38`, `Success` |
| 2. Amazon „baterii AA" până la rezultate | ✅ **PASS** | `SEARCH_VERIFY changed=true content=45 ok=true`; pe ecran: „Amazon Basics AA-Alkali-Hochleistungsbatterien", „BEVIGOR AA Lithium Batterien", „VARTA Batterien AA", categoriile Alkaline/Lithium/NiMH |
| 3. căutare web + „apasă pe primul rezultat" | ✅ **PASS** | `SEARCH_VERIFY found=true first="PANCERKA intelligent Window Cleaning Robot 80,00 € Von Amazon.de"` → `FIRST_RESULT clicked=true` → `FIRST_RESULT_VERIFY screen_changed=true` |
| 4. regresia G3 integrală | ✅ **PASS 10/10** | WA apel/video/mesaj → „Nu" → `wa_confirm_no`, zero `WA_INTENT`; `YT_SEARCH`, `YT_PLAY_CONFIRM verdict=YES`, `YT_STOP`, `STOP_MUZICA` |

Contextul se păstrează între comenzi: a doua comandă a pornit cu
`NATIVE_ROUTE action=first_result target="robot de curatat ferestrele pana la 80 euro"`, fără
repetarea cererii.

### Trei iterații, toate cu cauza dovedită pe telefon (G4: max 3)

1. **`FIX_SEARCH_SUBMIT_VERIFY_1`** — ramura `Search` se oprea după `ACTION_SET_TEXT` și raporta
   „Caut X." fără dovadă (doctrina 9). Adăugat submit + verificare.
2. **`FIX_SEARCH_SUBMIT_VERIFY_2`** — prima versiune a picat din două cauze ale mele: interogarea
   română („baterii aa") vs. sugestiile germane („batterie aa") făcea imposibilă potrivirea pe
   subșir, iar `ACTION_IME_ENTER` pleca 22 ms după `SET_TEXT`, pe un câmp nesedimentat. Reparat cu
   sedimentare 700 ms + „prima sugestie de sub câmp" (generic) + verificare pe schimbarea
   semnăturii ecranului, nu pe limba interogării.
3. **`FIX_WEB_FIRST_RESULT_1/2`** — pe Google, `findFirstResult` nu putea potrivi fraza întreagă;
   rezerva generică „primul clickabil lung de sub câmp" a apăsat însă **„Warum sehe ich diese
   Werbung?"** (15:12:47). Cauza: rezerva ocolea `SearchResultNoiseFilter`. Acum trece prin filtru,
   iar filtrul a fost extins cu etichetele observate (dezvăluire reclamă + consimțământ) — exact
   cum cere comentariul propriu al fișierului.

Reverturi: `TREE_CAP_RAISED=false` · `EXPLICIT_WEB_SEARCH=false` · `FIX_SEARCH_STRUCTURAL_1` ·
`FIX_SEARCH_VOCAB_1` · `FIX_SEARCH_SUBMIT_VERIFY_1/2` · `FIX_WEB_FIRST_RESULT_1/2`.
`ControlSynonyms.TABLE` și `GeneralSearchMatcher`: **neatinse**, cum s-a cerut.

## CRITERIU VOICE-ONLY — obligatoriu de acum în toate testele

Legea completă: `CLAUDE.md`, secțiunea „LEGEA VOICE-ONLY".
> *Poate Masterul finaliza sarcina folosind doar vocea, fără să i se ceară să citească sau să apese
> pe ecran?* Dacă nu → **INCOMPLET** sau **BLOCKED**, niciodată PASS.

**Reevaluare onestă a testelor de azi sub acest criteriu nou**: toate au fost injectate prin
DEBUG_INJECT (text tastat), nu rostite, iar rezultatele au fost citite de mine din logcat, nu
rostite Masterului. Deci, strict VOICE-ONLY, testele 2 și 3 sunt **INCOMPLETE**: lanțul
tehnic e dovedit, dar nu există încă dovadă că BENSON *prezintă verbal* rezultatele și acceptă
*selecție rostită*. Rămâne de verificat cu voce reală + de implementat prezentarea verbală a
opțiunilor („trei rezultate" → le enumeră → selecție vocală).

---

## RUNDA VOICE-ONLY — prezentare verbală a rezultatelor (14.10.2026, 15:20–15:56)

APK: SHA-256 `c23f52e9e879dd2824f16d999930b3452c7477c3e94f0d7d818ddb3cb0db91e1`.
Teste offline: **278, 0 eșecuri**. G3: **10/10 PASS**, inclusiv rulat intenționat DUPĂ o listă
lăsată neconfirmată (scenariul care produsese regresia).

### Livrat

- `OrdinalMatcher` (nou, pur): „primul / prima / al doilea / a treia / al patrulea" + cifre +
  cardinalele vechi. `parseWaOrdinal` **deleagă** spre el → WhatsApp și lista de aplicații câștigă
  aceleași forme, fără cod paralel. 8 teste.
- `ResultLabelPresenter` (nou, pur): din eticheta brută a reclamei scoate **numele produsului +
  prețul**, în română („80 de euro", „82 virgulă 39 euro"). Nu inventează niciun preț: dacă nu e pe
  ecran, nu se rostește. 10 teste.
- `topResultLabels` / `clickResultByLabel` (accesibilitate): citește până la 3 rezultate reale și
  apasă pe cel ales, verificând **schimbarea ecranului**. Etichetele nu sunt noduri reținute —
  se re-caută la selecție, deci o listă devenită invalidă eșuează onest (cerința 4).
- `PendingResultChoice` + `speakResultList`: aceeași formă ca dezambiguizarea WhatsApp dovedită.
  Bula rămâne pe ecran până la răspuns (`dismissMs=0`).

### Confirmat de Master, cu vocea lui

„Benson a găsit o listă de roboți și mi-a prezentat." — prezentarea verbală **funcționează real**.

### Patru defecte găsite cu vocea reală (invizibile prin DEBUG_INJECT)

**Lecție de metodă**: `DEBUG_INJECT` ocolește microfonul, deci niciuna din probele mele nu putea
prinde aceste erori. Exact ce spune criteriul VOICE-ONLY.

1. **„nu aude"** — cauza din log: `TTS_SPEAK chars=261` la 15:39:27.121 → `WAKE_ACK_DONE` la
   15:39:47.415 = **20,3 s de vorbire**. Microfonul se deschide abia DUPĂ TTS (protecția anti-ecou
   E0, corectă, neatinsă), deci Masterul a rostit „al treilea" peste vocea lui BENSON.
   Reparat: `MAX_SPOKEN_NAME` 58→34 + formulare tersă („Prima: …") → 199 chars. Lanț verificat la
   15:47:26: `MIC_OWNER → COMMAND_STT`, `BUBBLE "ASCULT"`, `CAPTURE_MIC ACQUIRE`.
2. **„nu există bulă"** — bula se desena corect, dar cu `dismissMs=8000` dispărea după 8 s în timp
   ce BENSON mai citea 12 s. Reparat: `dismissMs=0` până la răspuns.
3. **Duplicate în listă** — trei iterații, fiecare cu dovadă: dedup pe etichetă brută (15:29) →
   pe formă prezentată (15:36) → **pe NUME**, fără preț (15:47), plus „SALE/RABATT/Angebot" în
   prefixele promo.
4. **REGRESIE proprie, prinsă de G3 la 15:51:02** — o listă neconfirmată **înghițea comenzile
   următoare** (`FAIL|WA_CALL_ASK`, `FAIL|YT_SEARCH`): orice rostire era tratată drept răspuns
   ordinal. Exact bug-ul descris de Master („buclă stricată… mă întreabă de mesaj către
   has-a-ne-ne-a-has"). Reparat prin `FIX_RESULT_CHOICE_NO_SWALLOW_1`: confirmarea **expiră** (60 s)
   și consumă rostirea **doar** dacă e ordinal sau anulare („lasă asta", „stop"); altfel comanda
   nouă trece nestingherită. Verificat: G3 10/10 după o listă agățată.

### Nereușit / NEIMPLEMENTAT — cerut de Master, nu livrat

- **Lista prea lungă la rostire**: 199 chars ≈ 17 s. Încă prea mult pentru dialog.
- **Buget nerespectat**: la „până la 80 euro", varianta rostită a doua a fost 82,39 €. BENSON **nu
  filtrează după preț** — e cerința „găsește între primii 5 candidatul ideal", neimplementată.
- **Bula după terminarea sarcinii** + „acasă"/„închide aplicația" → revenire în BENSON: neimplementat.
- **Widget BENSON**: neimplementat.
- **Pronunție/gramatică umană**: vocea curentă e `ro-ro-x-vfv-local` (TTS local Android) — de aici
  sunetul robotic. Specul `docs/specs/B_VOCE_CHATBOT_2026-10-14.md` (OpenAI TTS, voce unică) există
  dar e **NEIMPLEMENTAT**. Asta e cauza reală, nu gramatica textului.

### FIX_NAME_PRONUNCE_1 — „has-a-ne-ne-a-has" reparat (14.10.2026, 16:00)

APK: SHA-256 `fe4bddca8a605ac0334c650831aa035a404d5642b94898de3a21160e3246d8a5`. Teste: **289, 0 eșecuri**.

Cerere directă a Masterului: „mesaj catre has-a-ne-ne-a-has… rectifica, nu mai pot sa aud asta".
Cauza reală: vocea e `ro-ro-x-vfv-local` (TTS local Android, română); pe un nume care nu arată
românesc, motorul renunță la citire și **silabisește literele**. Era deja notată în CLAUDE.md ca
„cunoscut, neconsiderat regresie", cu un mecanism MANUAL per contact (`TTS_NAME_PRONUNCE`).

`NamePronunciation.speakableName` (nou, pur, 11 teste): colapsează consoanele dublate, mapează
grupurile străine (`th→t`, `ph→f`, `ck→c`, `w→v`, `sh→ș`) și taie „h"-ul final **doar după vocală**.
„Hannah" → „Hana", „Walter" → „Valter", „Phillip" → „Filip"; numele românești rămân neatinse.
Cablat în `WaQuestionText.forKind`, deci în toate cele trei întrebări WhatsApp.

**Limita, spusă clar**: numele folosit la potrivirea contactului, la căutare și în intent rămâne
EXACT cel din agendă — `WaNameMatcher` și drumul de apel dovedit nu sunt atinse. Efect secundar
acceptat: bula afișează acum forma rostibilă („Hana"), nu cea din agendă.

G3 după fix: **9/10** — singurul FAIL e `YT_PLAY_CONFIRM`, intermitentul deja documentat
(fereastra fixă de ~1800 ms din `tryYoutubeSearchConfirm`; sondat 1/2 azi pe build nemodificat).
Fix-ul atinge doar textul întrebărilor WhatsApp, care au trecut 6/6.

---

## STT non-latin + VISIBLE WORKING STATE — cod livrat, **device OFFLINE** (14.10.2026, 16:20–16:35)

APK: SHA-256 `369d70c152cb4ca42ca8dedb4f4171e52bbf8d2897cd4626e157dfaf21a3afda`.
**NEINSTALAT** — telefonul s-a deconectat de pe USB (`adb: no devices/emulators found`). Toate
verdictele pe dispozitiv: **BLOCKED**. Teste offline: **298, 0 eșecuri**.

### `FIX_STT_NON_LATIN_1` — blocajul real al testului vocal (op. 2, aprobată de Master)

Cauza, din logul testului vocal al Masterului:
```
16:13:53.666  CMD_CAPTURE end reason=vad speechMs=1186  peakRms=4091 (vorbire reală, curată)
16:13:54.910  STT_RESULT provider=openai text="当" chars=1
```
Masterul a rostit un răspuns scurt; transcrierea a întors un ideogram chinezesc. În
`nativeTranscribe`, poarta era `if (!t.isNullOrBlank()) return t` — iar `"当"` **nu e gol**, deci
halucinația era acceptată ca răspuns și rezerva Deepgram/Groq **nu era încercată niciodată**.
Același mod de eșec fusese deja observat cu zgomot chirilic de TV (`PLAN_REPORT_2026-10-14`).

`SttTranscriptValidator` (nou, pur, 8 teste): un transcript fără **nicio literă latină** nu poate
fi comandă sau răspuns în ro/de/en → se tratează ca gol, deci lanțul trece la rezervă. Poarta e
aplicată **și** pe Deepgram. Acceptă deliberat cifrele singure („2" = alegere din listă) și
diacriticele ro/de. Log nou: `STT_FALLBACK reason=openai_non_latin_transcript`.

### VISIBLE WORKING STATE — punctele 1, 3, 5 implementate

Cauza plângerii „nu există bulă", găsită în cod (`applyWakeForegroundPolicy`, linia 2475):
`locked -> WakeFgDecision.VOICE_ONLY`, iar `updateBubbleNative` returna imediat sub VOICE_ONLY →
bula era suprimată **total** cu keyguard-ul activ. Confirmat în log:
`16:13:51 BUBBLE suppressed reason=voice_only state="ASCULT"`. **Nu era o regresie — era design
dintr-o rundă anterioară** (RUNDA_N3/N4), acum înlocuit prin ordinul Masterului.

- **punctul 1**: `wakeScreen()` (cel dovedit 03.10) se apelează la wake confirmat când e blocat.
- **punctul 2**: zero ocolire a keyguard-ului — `FULL_WAKE_LOCK` aprinde ecranul, nu deblochează.
- **punctul 3**: pe lockscreen, starea merge în **notificarea foreground** (singura suprafață
  permisă), prin `BensonPhrasing.lockscreenState` — doar stare generică („Ascult…", „Lucrez…",
  „Aștept răspunsul dumneavoastră."), **niciodată** transcrierea, rezultatele sau nume de contact.
- **punctul 5**: `SCREEN_LOCKED` și `SCREEN_UNLOCKED` sunt acum cazuri separate.

**CONSTRÂNGERE ANDROID, de reținut permanent**: un `TYPE_APPLICATION_OVERLAY` **nu poate** fi
desenat peste keyguard. Deci bula chiar nu poate apărea cât telefonul e blocat — notificarea e
substitutul conform. Nu e o limitare BENSON și nu se poate „repara" fără a ocoli protecțiile.

### NEIMPLEMENTAT din ordin (spus clar)

Punctele **6** (animarea logoului pe cele 5 stări), **7** (bula persistă cât sarcina e activă),
**8** (bula să nu blocheze atingerea dedesubt — de verificat, nu doar presupus) și **9**
(semantica distinctă „închide"/„acasă"/„stop" + păstrarea contextului). Și `BensonPhrasing`
(frazare de majordom) rămâne scris dar **necablat**.

---

## IDENTITATEA VIZUALĂ BENSON + validare APK — 14.10.2026, 16:58–17:10

APK: SHA-256 `3bba4f3884062ac32d82f05f63e5c9cd80641906f52422a2f1337fab8c67620e`, instalat cu `-r`.

### Validarea APK-ului anterior (prioritatea cerută)

| Pas | Verdict | Dovada |
|---|---|---|
| 1. instalare cu păstrarea datelor | ✅ **PASS** | `REGISTRY count=281 known=9 verified=4` identic după reinstalare |
| 5. regresia G3 | ✅ **PASS 10/10** | toate liniile în `day_2026_10_08_last_run.log`, 16:59 |
| 2. STT scurt + rezervă Deepgram | ⚠️ **BLOCKED** (cere voce) | cheia Deepgram e dovedit funcțională: `WAKE_VERIFY result=accepted text="benson" score=0,981` folosește chiar Deepgram |
| 3. wake → căutare → alegere vocală | ⚠️ **BLOCKED** (cere voce) | — |
| 4. ecran blocat + bulă deblocat | ⚠️ **BLOCKED** (cere voce) | vezi mai jos |

`DEBUG_INJECT` ocolește microfonul, deci pașii 2–4 **nu pot** fi validați fără vocea Masterului.

### Identitatea vizuală — livrat

- **Assetul ORIGINAL, nu redesenat**: `assets/brand/benson-seal.png` copiat bit-cu-bit în
  `modules/benson-overlay/.../res/drawable-nodpi/benson_seal.png` — **același sha256**
  (`caf384d1…`). Confirmat în APK: `resource 0x7f080080 drawable/benson_seal`.
- **`BensonLogoView`** (nou): rotește **doar inelul de arcuri**, păstrând textul „B·E·N·S·O·N /
  AI BUTLER" drept și lizibil. Tehnica: același bitmap desenat în două straturi decupate radial
  (`RING_INNER_RATIO = 0.72`) — inelul rotit, centrul static. Zero pixel desenat de mine, zero
  spinner generic. Viteze pe stare: `WAITING` 12°/s · `LISTENING` 36 · `SPEAKING` 48 · `WORKING` 72
  · `THINKING` 120; puls de opacitate pe `WORKING`/`SPEAKING`. În `IDLE` **nu se cere niciun cadru**
  (punctul 8 — baterie).
- **`BensonLockscreenActivity`** (nou): `setShowWhenLocked(true)` + `setTurnScreenOn(true)`.
  **NU deblochează**: fără `requestDismissKeyguard`, fără atingerea PIN/biometriei. Primește
  **doar** eticheta generică din `BensonPhrasing.lockscreenState` — niciodată transcrierea,
  rezultatele sau nume de contact. Se închide la `ACTION_SCREEN_OFF` sau la finalul sarcinii.
- Declarată în **manifestul modulului** (merge automat), ca să NU atingem
  `android/app/src/main/AndroidManifest.xml` (protejat, negenerabil fără `expo prebuild`).
  Verificat în APK: `showOnLockScreen=true`, `exported=false`, `excludeFromRecents=true`.
- Serviciul o pornește prin `ComponentName` cu nume de clasă ca **string**, exact ca
  `showBubbleNative` — fără dependență nouă între module.

Reverturi: `BENSON_LOCKSCREEN_UI=false` (doar notificare) · `VISIBLE_WORKING_STATE=false`
(comportamentul de dinainte) · `RING_INNER_RATIO=1.0f` (logo complet static).

### Verificat pe dispozitiv

`adb shell am start` al activității a fost **respins de Android** — corect: `exported=false`, deci
**doar BENSON o poate porni**, nu shell-ul. `isKeyguardShowing=true` în tot testul: blocarea nu e
ocolită. **Afișarea efectivă pe lockscreen rămâne BLOCKED** — cere un wake real cu telefonul blocat.

### Neimplementat din ordin

Punctul 4 (bulă transparentă cu logo animat peste aplicații, cu ecranul deblocat) — bula există, dar
**fără logoul animat în ea**. Punctele 6–7 ale legii VISIBLE WORKING STATE (bula persistă cât
sarcina e activă) și 9 (semantica „închide"/„acasă"/„stop") rămân nefăcute.

---

## CICLU DE ÎNCĂRCARE RAPID PE TELEFON (14.10.2026, 17:05–17:20)

Cerere directă a Masterului: ciclul build→instalare era prea lent. Cauza, măsurată:

| | înainte | după |
|---|---|---|
| APK | **370 MB** | **217 MB** (−41%) |
| `assembleRelease` | ~76 s | **31–44 s** |
| `adb install -r` | (nemăsurat exact) | **10 s** |

Cauza: APK-ul conținea `libonnxruntime.so` pentru **4 arhitecturi** (x86 29,6 MB · x86_64 29,9 ·
armeabi-v7a 17,5 · arm64-v8a 24,6) — **~77 MB inutili**, pentru că OnePlus Nord 4 e `arm64-v8a`.
(Separat, `assets/models/ggml-base.bin` = 141 MB, modelul whisper offline; **neatins** — e declarat
în CLAUDE.md ca rezervă și e nereproductibil.)

**Soluția nu modifică niciun fișier**: `./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a`
— mecanismul oficial React Native, documentat chiar în `android/gradle.properties:30`. Reversibil
prin definiție: fără flag, se construiesc toate arhitecturile (pentru livrare).

### Regresie introdusă de mine, găsită și corectată prin experiment

`FAIL|WA_MESSAGE_NO_ACTION` a apărut după runda de identitate vizuală. Prima mea ipoteză —
activitatea de lockscreen fură prim-planul — a fost **greșită**: fix-ul
`FIX_LOCKSCREEN_STALE_DECISION_1` nu a rezolvat testul. A doua ipoteză — plafonul de noduri — a fost
**infirmată prin experiment**: cu `TREE_CAP_RAISED=false` (400, ca înainte) testul cădea identic.

Cauza reală: **testul era greșit, nu produsul.** Spre deosebire de apel/video (care întreabă
imediat), mesajul scrie întâi draftul vizibil în WhatsApp și abia apoi întreabă „Trimit?" —
comportament dovedit și intenționat (`RUNDA WA_VISIBLE_DRAFT`, 03.10). Harness-ul injecta „nu"
la 2,2 s după `WA_WRITE_START`, înainte să existe întrebarea:
```
21.246 WA_WRITE_DEEPLINK_START → 22.226 TARGET_REACQUIRE_OK elapsedMs=918 → 23.430 inject "nu"
```
`FIX_HARNESS_WA_MESSAGE_WAIT_1`: pauză de 4 s înaintea răspunsului, fereastră 5 s. După fix,
`WA_MESSAGE_NO_ACTION` **PASS**. În toate rulările, `leaked=no` — **zero mesaje trimise vreodată**.

`FIX_LOCKSCREEN_STALE_DECISION_1` rămâne, fiind corect independent: `currentWakeFgDecision` se
calculează doar la wake și rămâne agățată; acum blocarea se verifică **live**, deci cu telefonul
deblocat bula nu mai e suprimată de o decizie veche.

G3 final: **9/10**, singurul FAIL fiind `YT_PLAY_CONFIRM` — intermitentul documentat (fereastra
fixă de ~1800 ms din `tryYoutubeSearchConfirm`, sondat 1/2 azi pe build nemodificat).
APK curent: SHA-256 `cdf87bc98e393df7746b2b7c0559dfdec4f2be8da11d9e5faef7d7557878bf4f`.

---

# FAZA 0 + FAZA 1 — BENSON LLM AGENT (15.10.2026, 09:57–10:25)

APK: SHA-256 `15b5a5a7d5ecaa6ee29414391587e28ce0c5df39523d18907cbcfda8aaa3edcf`.
G3 după agent: **9/10** (singurul FAIL = `YT_PLAY_CONFIRM`, intermitentul documentat) → **zero
regresii noi**. Cost total al zilei, incl. 4 rulări de agent: **0,0255 USD**.

## FAZA 0 — auditul, cu dovadă

Linia de bază la comanda-țintă, măsurată înainte de orice cod (09:57:46):
```
NATIVE_ROUTE action=music_play target="radio romania actualitati" pkg=com.opera.browser
MUSIC_PLAY_STEP step=type ok=true → step=extract ok=false → verified=false
```
Trei defecte simultane: **creierul nu a fost consultat deloc** (zero `BRAIN_CALL`), matcher-ul a
tastat numele postului **în browser** doar pentru că era în prim-plan, și BENSON **a tăcut**.

**Cusătura chatbot ↔ Dispatcher**: creierul era la linia 3848 (`tryBrainFallback`), după **14
matchere** care returnează devreme (3800→3824). LLM-ul era ultima soluție — invers față de mandat.

## Decizia de arhitectură (luată pe regula Masterului, nu inventată)

TEST H din mandat: *„Nicio modificare a agentului nu justifică deteriorarea unei funcții deja
validate"* + §1 *„Nu reconstrui BENSON"* → **agentul preia înțelegerea și orchestrarea; comenzile
deterministe dovedite (G3) își păstrează calea rapidă de ~115 ms.** Agentul stă unde stătea creierul
vechi, dar — spre deosebire de el — poate EXECUTA în mai mulți pași, cu verificare.

## Ce funcționează (dovedit pe telefon)

- **Bucla completă de tool calling**: `agent_handoff → open_app → search_in_app → observe_screen →
  tap_element → …`, cu `AGENT_TOOL` și latență per pas.
- **Alege aplicația potrivită**: după `FIX_AGENT_STAFF_BLINDNESS_1`, la „pune Radio România
  Actualități" deschide **myTuner Radio** (`com.appgeneration.itunerfree`), nu Chrome.
- **Caută în aplicația radio**: `SEARCH_VERIFY pkg=com.appgeneration.itunerfree ok=true`.
- **Poarta de siguranță ține**: a refuzat să apese o reclamă — `AGENT_TAP ok=false reason=label_mismatch have="ad icon"`.
- **Verifică efectul**: `AGENT_TAP_VERIFY screen_changed=true` după fiecare apăsare.
- **Nu minte și nu tace**: la eșec rostește „Nu am reușit să duc asta la capăt, Master."
- **Plafon de pași respectat** (10), fără bucle infinite.

## Ce NU funcționează — verdict onest: **INCOMPLETE, nu PASS**

Testul A (radio) **nu se încheie**: agentul navighează în myTuner, apasă corect de 3 ori, dar
**rătăcește și nu ajunge la redare**. `dumpsys media_session`: niciun `state=PLAYING`.
10 pași, **42 s**. Cauza vizibilă în log: apasă elemente cu **etichetă goală** (`label=""` la
index 12 și 14) — reprezentarea ecranului nu-i dă destulă informație în această aplicație, deci
ghicește.

Pe criteriile mandatului (§17): **CONVERSATION_PASS** parțial · **EXECUTION_PASS NU** ·
**VOICE_ONLY_PASS netestat** (comandă injectată, nu rostită).

## Trei fixuri reale, fiecare cu cauza dovedită

1. `FIX_MUSIC_FOREGROUND_CAPABILITY_1` — „pune X" nu mai tastează în orice aplicație din prim-plan;
   cere capabilitatea `CAP_MEDIA_SEARCH` din registrul personalului. Asta a oprit tastarea în Opera.
2. `FIX_AGENT_STAFF_BLINDNESS_1` — agentul primea doar cele 9 aplicații din tabelul de capabilități,
   deci **nu știa că aplicațiile de radio există**. Acum primește și aplicațiile instalate al căror
   nume se potrivește cu cererea.
3. `FIX_AGENT_TAP_CONTRACT_1` — agentul trimitea ROLUL („button", „input") ca etichetă, pentru că
   randarea e `[3] button "Căutare"`. Vina contractului meu: **indexul** e cheia unei liste
   numerotate. Acum index = autoritate, eticheta = control anti-ecran-schimbat.

## Reverturi

`LLM_AGENT=false` (agentul dispare, rămâne `tryBrainFallback` de azi) ·
`MUSIC_REQUIRE_MEDIA_CAPABILITY=false` · `MAX_STEPS` · `FIX_AGENT_TAP_CONTRACT_1`.

## Următorul pas tehnic (nu decizie de produs)

Reprezentarea ecranului trebuie să-i dea agentului ce-i lipsește: elementele fără etichetă sunt
inutile. De adăugat `viewId` și textul copiilor în `ScreenDigest`, ca un rând de rezultat să fie
identificabil. Fără asta, agentul va ghici în orice aplicație cu butoane nedenumite.

## LLM ACTION BRIDGE — progres real, dar **EXECUTION_PASS NEATINS** (15.10.2026, 10:30–10:45)

APK: SHA-256 `7bb3de376dc8df6e191b83b343e996beea501a495ec373318e26a3d0369e10f0`.
G3: **9/10** (FAIL = `YT_PLAY_CONFIRM`, intermitentul documentat) → zero regresii noi.
Cost total al zilei, cu 7 rulări de agent: **0,0417 USD**.

### Ce s-a reparat, fiecare cu cauza dovedită pe telefon

1. `FIX_DIGEST_DERIVED_LABEL_1` — agentul apăsa elemente cu `label=""` și **ghicea**, pentru că
   într-o listă reală rândul clickabil nu are text propriu: textul stă în COPII. Acum eticheta se
   derivă din descendenți (max 3), cu `viewId` scurt ca ultimă rezervă. Efect imediat, vizibil în
   log: etichete reale — „Radio România Actualități live", „See details", „Română".
2. **Punctul 2 din ordin** — refuz explicit la apăsarea unui element fără etichetă:
   `AGENT_TAP ok=false reason=blank_label`.
3. **Punctul 3 din ordin** — indexul e valid **doar pentru observația curentă**: comparăm eticheta
   de la același index între ce a văzut agentul și ce e pe ecran acum. Dovedit că funcționează:
   `reason=stale_index seen="myTuner Radio FM Deutschland" now="icon clickable area"`.
4. **Punctul 5 din ordin** — instrument `verify_playing` pe semnal verificabil
   (`AudioManager.isMusicActive` + titlu MediaSession). Agentul are interdicție în prompt să spună
   că a pornit ceva fără el.
5. `FIX_AGENT_OPEN_URL_1` — instrumentul care lipsea din punte. Agentul își consuma toți pașii prin
   bannere de consimțământ. Acum poate merge direct la sursa oficială, cu **validare de schemă**
   (doar http/https; un `intent://` de la model e refuzat). Dovedit:
   `AGENT_OPEN_URL ok=true host=www.romania-actualitati.ro`.

### Verdict pe testul A (radio): **INCOMPLETE**

Lanțul merge aproape până la capăt — Chrome → rezultat corect apăsat → site oficial deschis →
element „Radio România Actualitați" apăsat — dar **redarea nu pornește**:
`dumpsys media_session`: zero `state=PLAYING`; stiva de audio focus goală. Ultimul tap:
`AGENT_TAP_VERIFY screen_changed=false`. 10 pași, 57 s.

Pe criteriile §17: CONVERSATION_PASS parțial · **EXECUTION_PASS NU** · VOICE_ONLY netestat.

### Defect nou, nereparat: **două rulări de agent în paralel**

`AGENT_RUN steps=1 done=true ms=15028` ȘI `AGENT_RUN steps=10 done=false ms=57596`, în aceeași
sesiune. Două instanțe ale agentului pot apăsa pe același ecran simultan — periculos și probabil
cauza unora dintre rătăciri. De reparat înaintea oricărei alte funcții: un singur agent activ,
cu anulare la comandă nouă.

### Ce blochează EXECUTION_PASS (analiza, nu presupunerea)

Butonul de play al unui player web nu produce întotdeauna `MediaSession`, iar pagina poate cere o
interacțiune suplimentară (autoplay blocat de browser). Două căi, de decis:
(a) fluxul audio direct (`.mp3`/`.aac`) deschis cu un player, nu pagina HTML — pornește imediat și
    produce sesiune media verificabilă;
(b) `MediaTransport.playFromSearch` pe o aplicație de radio deja configurată.

## FIX_INTERRUPT_1 — „Benson stop" / „Benson mute" nu funcționau DELOC (15.10.2026, 10:50)

APK: SHA-256 `5bd7c20a0af6ca8d6abd046af261d5b7078d0054d51fa7f325866fd6201e90b7`. G3: **9/10**
(FAIL = `YT_PLAY_CONFIRM`, intermitentul documentat) → întreruperea nu a furat nicio comandă.

Raport direct al Masterului: *„spun benson stop, benson inchide. Nimic!"* și *„nici Benson mute
nu merge"*. **Confirmat în cod, nu presupus:**

- `"stop"` apărea DOAR ca vorbă de control media (`"stop" -> "pause"`), ca anulare a unei liste de
  rezultate și ca sinonim de „nu" într-o confirmare. Fără nimic în așteptare și fără muzică,
  **nu se potrivea cu nimic**.
- `"închide"` mergea la `GLOBAL_NAV_PATTERN` → ecranul principal — adică închidea *aplicația*, nu
  oprea BENSON.
- `grep -c "agentCancel|cancelAgent|interrupt"` = **0**: agentul LLM rula până la 60 s și **nu
  putea fi oprit de nimic**.
- Era deja cerut explicit (ordinul CONTINUOUS BUTLER, §5 ÎNTRERUPERI) și rămăsese neimplementat.

**Reparat**: `tryInterrupt` se verifică **PRIMUL** în lanț, înaintea stărilor în așteptare, deci
funcționează și în mijlocul unei confirmări sau al unei sarcini a agentului. Face patru lucruri:
anulează agentul în curs (`agentRunId++`, bucla vede că nu mai e a ei și iese curat — fără
`Thread.stop`, fără apăsare lăsată la jumătate), golește TOATE stările în așteptare (un „da" de
mai târziu nu mai autorizează nimic), taie TTS-ul propriu, apoi confirmă scurt „Am oprit." și
re-armează wake-ul.

Vocabular: `stop · stai · oprește-te · lasă asta · anulează · renunță · gata · destul · taci ·
mute · liniște · nu mai vorbi`. **Deliberat FĂRĂ „oprește" simplu** — acela înseamnă pauză la
muzică (G3 `YT_STOP`/`STOP_MUZICA`) și nu are voie furat; tiparul cere rostirea singură, deci
„oprește muzica" nu se potrivește.

Dovadă pe telefon: `NATIVE_ROUTE action=interrupt` → `INTERRUPT agent_cancelled=true
pendings_cleared=true` → `TTS "Am oprit."`, atât pentru „stop" cât și pentru „mute".
Revert: `INTERRUPT_ENABLED = false`.

## FIX_MEDIA_VERB_SYNONYMS_1 — „dă-i drumul" / „mai departe" (15.10.2026, 11:05)

APK: SHA-256 `f9cf98a526d3d4922b8c1c2746c532f7a23e3d1409e8f96506a3f449369952c5`.

Raport al Masterului: *„nu știe să dea drumu mai departe sau să apese play"*. Diagnostic pe telefon
(11:01–11:02), care arată exact granița:

| rostire | înainte |
|---|---|
| „play" | ✅ `media_ctl target="play"` în ~8 ms |
| „următorul" | ✅ `media_ctl target="next"` |
| **„dă-i drumul"** | ❌ `action=agent_handoff` — cădea la LLM |
| **„mai departe"** | ❌ `action=agent_handoff`; agentul a apăsat apoi un articol de știri („USA gegen China") în loc de play |
| „apasă pe play" | ❌ agentul a rătăcit |

Deci calea determinist funcționa, dar **doar pentru cuvintele exacte**; formele rostite firesc
cădeau la agent — lent și nesigur. Conform regulii Masterului („completează-l NUMAI cu sinonime
lipsă demonstrate prin teste"), adăugate în `MEDIA_CTL_PATTERN`: `reia`, `dă-i drumul`,
`dă drumul`, `dă-i drumu` → **play**; `mai departe`, `mergi mai departe`, `sari peste` → **next**.

Verificat după fix, toate pe calea determinist (~5 ms): „dă-i drumul"→play · „mai departe"→next ·
„reia"→play · „sari peste"→next.

G3: **9/10**. O rulare intermediară a dat 8/10 cu `WA_MESSAGE_NO_ACTION`, dar reluarea imediată a
trecut → confirmă că e marginal la timing (durata scrierii draftului WhatsApp vs. fereastra
harness-ului), nu o regresie din această schimbare. `leaked=no` în toate rulările — zero mesaje
trimise vreodată.

**Observație nereparată**: „apasă pe play" merge la agent (nu la `media_ctl`), iar agentul poate
apăsa greșit. Tiparul `tryUniversalHand` pentru „apasă pe X" ar trebui să prindă „play" înaintea
agentului. De verificat într-o rundă dedicată.

## Prioritatea 1 (agent unic) + Prioritatea 2 (radio) — 15.10.2026, 11:15–11:21

APK: SHA-256 `3abd0be66f707adbd075addcf3cdf85a6154c432ac33750f72f695c86232b5b1`.

### Suprapunerea — DOVEDITĂ din loguri, cum s-a cerut

```
10:41:14.786 AGENT_RUN steps=1 done=false ms=25054
10:41:19.147 AGENT_RUN steps=1 done=false ms=25047      → două rulări de ~25 s la 4,3 s distanță:
                                                          s-au suprapus ~21 de secunde
11:01:57.412 AGENT_TOOL step=1 name=observe_screen
11:01:57.412 AGENT_CANCELLED run=5                      → ACEEAȘI milisecundă: agentul anulat
                                                          apucase deja să execute un instrument
```

### Reparat (`FIX_AGENT_SINGLE_RUN_1`)

- **`agentFingerLock`** — acces exclusiv la degetul BENSON: două instrumente nu mai pot rula
  simultan, oricâți agenți ar exista.
- **Verificare de prospețime ÎN INTERIORUL lacătului**, chiar înainte de acțiune: o rulare anulată
  cât aștepta lacătul nu mai apasă nimic (`AGENT_TOOL_SKIPPED reason=cancelled`).
- **Verificare după răspunsul HTTP**, înainte de a executa instrumentele cerute
  (`AGENT_CANCELLED stage=after_http`).

### Test cu 3 comenzi rapide (2 s între ele) — rezultat ONEST: **PARȚIAL**

```
11:20:12.723 agent_handoff
11:20:14.851 agent_handoff
11:20:16.979 agent_handoff
11:20:27.539 AGENT_CANCELLED run=2 stage=after_http      ← anularea funcționează
11:20:39.456 AGENT_RUN steps=1 done=true  ms=22477
11:20:39.877 AGENT_RUN steps=1 done=false ms=25026       ← DOUĂ rulări au ajuns totuși la final
```

**Ce e garantat**: nicio acțiune pe ecran nu se mai execută simultan, și o rulare anulată nu mai
apasă nimic. **Ce NU e garantat încă**: o rulare veche poate ajunge totuși la pasul final (`say` /
mesajul de eșec), deci Masterul poate auzi un răspuns la o comandă abandonată. Cauza: apelul HTTP
al agentului durează ~22 s (gpt-5-nano), iar verificarea de anulare vine abia după ce răspunsul
sosește. De reparat cu anularea efectivă a apelului HTTP (`Call.cancel()`), nu doar cu verificări
între pași.

### Prioritatea 2 — radio: tot **INCOMPLETE**, cu o cauză nouă și clară

Implementat `play_stream` cu **verificare la server** (ordinul: „numai dintr-o sursă reală,
verificată. Nu accepta URL-uri inventate"): BENSON cere antetele adresei și acceptă doar
`Content-Type: audio/*`; dacă e listă de redare (.m3u/.pls) extrage primul flux și verifică din nou.
Un URL inventat dă 404 sau `text/html` și **nu ajunge niciodată la player**. Zero listă de posturi
în cod.

**Dar agentul nu a folosit deloc `play_stream`** (11:18–11:19): a mers tot pe site-ul oficial și a
rătăcit; `media_session` PLAYING = 0. Cauza, onest: i-am interzis explicit să inventeze adrese, iar
**`gpt-5-nano` nu cunoaște fluxul oficial al postului**. Deci instrucțiunea a funcționat — modelul
pur și simplu nu are informația.

Trei căi reale, niciuna încă aleasă:
1. model mai bun pentru pasul de planificare (cost mai mare — §15 cere rutare pe model);
2. BENSON extrage singur fluxul din pagina oficială a postului (fără listă în cod, fără invenție);
3. Masterul dictează fluxul o dată, iar BENSON îl reține ca preferință.

## P0 — ANULAREA AGENTULUI: **PASS** (15.10.2026, 11:30)

APK: SHA-256 `e3185aaa5e31a0dfc7d921c5df7d8171e4ad87bc38f1ec02d0bcf757c9f43ea4`. G3: 9/10.

`Call.cancel()` pe apelul OkHttp în zbor + verificarea identificatorului rulării **înainte de orice
TTS**, nu doar înainte de instrumente. Cele două guri prin care un agent anulat încă vorbea
(răspunsul conversațional și mesajul de eșec) sunt acum închise, cu dublă verificare: o dată pe
firul agentului, o dată pe firul principal chiar înainte de rostire.

**Test cerut — 3 comenzi succesive + STOP în timpul unui apel lent:**
```
11:30:43.020 agent_handoff                    (run 1)
11:30:45.122 agent_handoff                    (run 2 anulează run 1)
11:30:45.154 AGENT_RUN run=1 ms=2134          ← 2,1 s în loc de ~22 s: Call.cancel() funcționează
11:30:45.154 AGENT_SPEECH_SUPPRESSED run=1    ← și NU a vorbit
11:30:47.273 AGENT_RUN run=2 ms=2151  +  AGENT_SPEECH_SUPPRESSED run=2
11:30:50.351 AGENT_RUN run=3 ms=3112  +  AGENT_SPEECH_SUPPRESSED run=3
11:30:50.353 INTERRUPT agent_cancelled=true
11:30:50.373 TTS "Am oprit."                  ← SINGURA rostire din tot testul
```
Toate cele 6 criterii: ✅ anulare HTTP efectivă · ✅ invalidare imediată · ✅ răspunsuri de după
anulare tratate ca invalide · ✅ runId verificat înainte de TTS și de instrumente · ✅ testat cu
3 comenzi + STOP · ✅ agentul anulat nu mai poate nici apăsa, nici vorbi.

## P2 — radio: cauza extragerii, raportată (punctul 6)

Pagina oficială `https://www.romania-actualitati.ro/live/` (http=200, 116 KB) conține:
```html
<audio id="liveplayer" controls controlslist="nofullscreen nodownload noremote" style="display:none;">
```
— un element **gol**: sursa e injectată de JavaScript la rulare. Fluxul **nu există** în HTML-ul
static, nici în `/js/utils.js`. Deci extragerea statică a URL-ului **nu e posibilă**; de aceea nu
pot alimenta `play_stream` cu o adresă verificată din pagină, iar a inventa una e interzis.

Alternativa verificată, în spiritul punctului 6: BENSON deschide pagina OFICIALĂ și apasă chiar
controlul ei de redare (JS-ul paginii pune fluxul). Sursa rămâne oficială, zero URL inventat.
Piesa care lipsește e identificarea sigură a butonului de play pe acea pagină — elementul e
`display:none` până când scriptul îl activează.

## FIX_DIGEST_INVISIBLE_1 + FIX_VERIFY_PLAYING_STRICT_1 (15.10.2026, 11:40–11:50)

APK: SHA-256 `998b674ea2384207376b576d276ed9c4edb81979946bb30f0694f19e4a82c4eb`. G3: 9/10.
Teste `ScreenDigestTest`: 22/22.

### Cel mai important fix al zilei: elementele INVIZIBILE

Pe pagina oficială a postului, deschisă în Chrome, digestul era plin de elemente de meniu cu
margini de **lățime zero** (`1080,379,1080,475` — x1 == x2): un meniu colapsat, invizibil pentru om.
Ele consumau locurile din digest și împingeau conținutul real afară — **de aici o bună parte din
„rătăcirile" agentului**: alegea dintre elemente pe care nimeni nu le vede.

Regula nouă: fără suprafață pe ecran → nu există pentru agent. Efect imediat, vizibil în același
test:

| înainte | după |
|---|---|
| `[4] button "Ştiri" 1080,379,1080,475` (invizibil) | `[6] text "ÎN DIRECT"` |
| `[8] button "Politică" 1080,574,…` (invizibil) | **`[10] button "Ascultă"`** ← butonul de redare |
| …18 elemente de meniu invizibile | `[8] text "PRIETENII DE LA RADIO"` (emisiunea curentă) |

Trei teste proprii au căzut la acest fix — pentru că **fixturile mele** foloseau margini degenerate
(`"0,$it,10,$it"`, înălțime zero). Codul era corect, datele de test erau nerealiste; fixturile au
fost corectate, nu regula.

### FIX_VERIFY_PLAYING_STRICT_1 — fals pozitiv DOVEDIT, reparat

La 11:44:48 `verify_playing` a raportat `playing=true` cu titlul **„VWs neue Strategie…"** — un
articol german dintr-o sesiune Chrome rămasă de la o navigare anterioară, nu radioul cerut. Imediat
după, toate playerele erau `state:idle`. O singură citire a lui `isMusicActive` poate prinde un
sunet de o clipă sau o sesiune veche. Acum se cer **două citiri la ~1,8 s distanță, ambele
adevărate** — un flux radio sună continuu, o reclamă scurtă sau un rest de sesiune nu mai trece
drept succes.

### Radio: încă **INCOMPLETE**, dar din altă cauză decât ieri

Cu digestul curat, agentul ajunge corect la țintă în 3 apăsări:
`tap "Radio România Actualitați"` → `tap "Ascultă Radio România Actualitaţi Live"`.
**Redarea tot nu pornește** (`state:started` = 0 după test), iar agentul consumă cei 10 pași.
Cauza probabilă, neconfirmată: politica de autoplay a browserului cere o interacțiune pe care
apăsarea programatică nu o satisface. Calea sigură rămâne fluxul direct — pe care nici pagina
(JS injectat), nici `gpt-5-nano` nu îl pot furniza.

### Ordin nou înregistrat, NEÎNCEPUT: arhitectura LLM-agnostic (BENSON AI Gateway)

Masterul a cerut explicit: *„Nu începe acest refactor înainte de finalizarea și validarea
remedierilor curente."* Respectat — nimic început.

## P3 — cauza „eșecului repetat" G3, lămurită (15.10.2026, 11:50–12:00)

APK: SHA-256 `a6b320b5cf0cebc615f94b9e69a48185d3bcc5cae8efbf43f8e77e0783624d69` (instalat).

### Descoperirea cea mai importantă: o parte din „regresii" erau CABLUL

Două rulări G3 consecutive au raportat **10 din 10 FAIL**. Produsul era intact — PID viu, zero
crash-uri, wake sănătos, iar un test manual imediat a mers perfect
(`DEBUG_INJECT run="deschide calculatorul"` → `APP_LAUNCH ok=true`).

Cauza, din fișierul de captură: conținea **o singură linie** — `- waiting for device -`.
**Telefonul se deconectase de pe USB.** Nicio injecție nu ajungea, iar suita acuza senin produsul.

Asta pune sub semnul întrebării o parte din eșecurile „intermitente" urmărite azi: suita nu
distingea între „funcția e stricată" și „telefonul lipsește".

**Reparat în harness** (nu în produs): gardă de validitate la pornire — dacă `adb devices` e gol
sau aplicația nu răspunde la `DEBUG_INJECT` (de ex. tocmai repornește după `adb install`), suita
scrie **`BLOCKED`** și iese, în loc să producă FAIL-uri false. Verificat acum, cu telefonul
deconectat: `BLOCKED|DEVICE_ABSENT|suita nu a rulat`.

### `FIX_YT_FIXED_WINDOW_1` — cauza reală a `YT_PLAY_CONFIRM`

După lansarea YouTube prin Intent, codul aștepta o fereastră **FIXĂ** de 1800 ms și căuta apoi o
**singură dată** primul rezultat. Dacă YouTube nu randase încă, rezultatul era null → „N-am găsit
madonna pe YouTube" → nu se crea nicio întrebare → „da"-ul suitei nu avea ce confirma → FAIL.
De aceea părea „intermitent": depindea de viteza de randare.

Înlocuit cu **așteptare pe condiție**: interoghează la 250 ms până apare rezultatul, buget 6 s,
cu log `YT_RESULT_WAIT found= attempts= ms=`. Când randarea e rapidă iese mai devreme decât
înainte; când e lentă, mai așteaptă. Revert: `delay(1800)` + un singur apel.

Aceeași metodă aplicată și în harness pentru WhatsApp: se așteaptă **apariția întrebării de
confirmare** în log, nu un `sleep` fix.

### Stare: **P3 NEVALIDAT** — telefon deconectat

Codul e scris, compilat și instalat; cele două rulări G3 de după sunt **invalide** (telefon absent),
deci nu există încă o rulare 10/10 care să confirme fixul. Nu declar P3 închis.

---

# P3 ÎNCHIS — G3 **10/10, de trei ori la rând** (15.10.2026, 21:45–22:00)

APK: SHA-256 `388ab297b3444407014a81714c1ed90dfd2f9d3c915e823a3eb996ab7702a5a3`.

## Cauza reală a lui `YT_PLAY_CONFIRM` — și o corecție a istoricului

Am avut DOUĂ ipoteze greșite (fereastra fixă de 1800 ms; agentul care interferează). Ambele fixuri
erau corecte în sine, dar niciuna nu era cauza. Cauza s-a văzut abia uitându-mă la **ce e efectiv
pe ecran** după lansarea YouTube:

```
[1] button "guns n roses you could be mine instrumental"   <- căutarea VECHE, încă în casetă
[8] button "Guns N' Roses: You Could Be Mine Instrumental"  <- rezultatele VECHI
```

`ACTION_SEARCH` cu extra `"query"` e **acceptat** de YouTube (`resolveActivity != null`) dar
**ignorat**: aplicația restaurează sesiunea anterioară. Deci „madonna" nu era căutat niciodată.

**Corecție de istoric, onestă**: testul TRECEA mai devreme azi **din motiv greșit** — ultima
căutare din YouTube chiar era „madonna" (din rulările precedente), deci rezultatele vechi se
potriveau întâmplător. Un fals pozitiv, nu o funcție care merge. Tot ce am numit azi
„intermitent" la acest test era, de fapt, dependență de starea reziduală a YouTube.

`FIX_YT_SEARCH_INTENT_1`: adresa de rezultate (`youtube.com/results?search_query=…`, cu
`setPackage` pe YouTube) devine calea principală — ea chiar execută căutarea. `ACTION_SEARCH`
rămâne rezervă. Revert: `YT_SEARCH_VIA_URL = false`.

## Trei ferestre fixe eliminate, toate înlocuite cu așteptare pe CONDIȚIE

| unde | înainte | acum |
|---|---|---|
| cod, după lansarea YouTube | `delay(1800)` + o singură căutare | interogare la 250 ms, buget 6 s, log `YT_RESULT_WAIT` |
| harness, confirmarea WhatsApp | `sleep` fix | așteaptă apariția întrebării în log |
| harness, confirmarea YouTube | `da` la +3,3 s (înaintea întrebării) | așteaptă `YOUTUBE_SEARCH_CONFIRM found=true` |

## Gardă de validitate în harness

Două rulări au raportat **10/10 FAIL** doar pentru că telefonul se deconectase de pe USB
(captura conținea o singură linie: `- waiting for device -`). Suita acuza produsul pentru un cablu.
Acum verifică `adb devices` și răspunsul aplicației la `DEBUG_INJECT`, și scrie **BLOCKED**, nu FAIL.
Asta explică retroactiv o parte din „eșecurile repetate" urmărite azi.

## Rezultat

**G3 10/10 × 3 rulări consecutive**, apoi încă o dată după dezactivarea ecranului negru: 10/10.

## UX: ecranul negru cu logo — RETRAS, la cererea Masterului

`BENSON_LOCKSCREEN_UI = false`. Activitatea `BensonLockscreenActivity` (fundal închis + logo
rotitor) a fost **ideea mea**, nu designul original BENSON; Masterul a respins-o explicit. Pe ecran
blocat rămâne notificarea foreground (suprafață permisă, fără conținut privat); pe ecran deblocat
rămâne BULA peste aplicația activă. Codul activității rămâne în proiect, dezactivat.

## UNIVERSAL_ACTION_STOP + registrul de comenzi (15.10.2026)

**Oprirea universală.** `TACI = STOP = OPREȘTE = MUTE`, rostite singure, trec toate prin
`BensonForegroundService.interruptAll` — un singur mecanism. Oprește: TTS · agentul LLM și cererea
HTTP · buclele degetului BENSON (steag nou în `BensonAccessibilityService`, verificat în
`waitForNode`/`tapDigestElement`/`clickResultByLabel`) · redarea media (același transport
MediaSession folosit de `MEDIA_CTL`) · confirmările pendinte · sesiunea. Silențios.
Nu închide aplicația, nu atinge o convorbire conectată, nu anulează un SOS.
Dezambiguizare pe obiect: „oprește muzica"/„oprește videoclipul" rămân MEDIA_CTL.
Dovezi pe telefon, 15.10: `INTERRUPT_VERIFY media_stopped=true` peste YouTube în redare ·
pregătire apel WhatsApp → `stop` → „da" ulterior nu autorizează nimic · agent multi-pas → zero
pași · G3 extins **14/14** (patru teste noi `INTERRUPT_*`). Barge-in-ul **rostit** cât BENSON
vorbește rămâne neconfirmat de Master.

**Registrul de comenzi.** `BensonCommandRegistry` — vedere peste aceleași `CAP_*` din
`StaffRegistryCore` (legătură la compilare + test JUnit de acoperire), nu un registru paralel.
Per comandă: acțiune, descriere ro/de/en, formulări acceptate, executor real, condiții, stare
(VALIDAT / IMPLEMENTAT / PLANIFICAT / INDISPONIBIL). Creierul îl citește prin instrumentul
`explain_command`; promptul interzice execuția când Masterul doar întreabă cum se cere ceva.
`END_CALL` și `CANCEL_SOS` = **PLANIFICAT** (zero cod `TelecomManager`/`endCall` în proiect) —
BENSON spune explicit că nu știe încă. Contracte: `docs/specs/POLITICA_OPRIRE_APEL_SOS_2026-10-15.md`.
**Stare: NEVALIDAT PE TELEFON** — telefonul s-a deconectat după build; APK `7c747e77`, teste
JUnit verzi, testele vocale și G3 de re-rulat la reconectare.
