# H1_REPORT.md — Regression harness (BENSON_CANON.md, Secțiunea 2)

## Liniile de log folosite (găsite în cod, nu inventate)

| Drum | Comandă trimisă | Linie de log / fișier |
|---|---|---|
| 6. Calculator | „deschide calculatorul" → „da" | `EXEC_TRACE_FAILURE ... SINGLE_MATCH_NEEDS_CONFIRMATION` / `CONFIRM_LISTEN_ARM` (missionOrchestrator.ts), apoi `EXEC_TRACE_FOREGROUND_VERIFY ... confirmedByEvent=true` (`src/executors/appLauncherExecutor.ts:283`) |
| 1. Waze | „du-ma la Sibiu" | `'[WazeTool]', 'accepted', 'waze_app', '<url cu destinația>'` — `console.log`, tag `ReactNativeJS`, NU `logAudioDiag` (`src/core/mission/tools/wazeTool.ts`, apelat din `missionExecutor` guvernat) |
| 2. Deschidere aplicație (YouTube) | „deschide youtube" | `EXEC_TRACE_FOREGROUND_VERIFY expected="com.google.android.youtube" ... confirmedByEvent=true` (`src/executors/appLauncherExecutor.ts:283`) |
| 3. Spotify | „cauta workout mix radio pe spotify" → „a doua" → „pauza" | `MEDIA_SEARCH_DONE` (`src/executors/mediaSearchExecutor.ts:329`), `MEDIA_SELECT_DONE` (`:451`), `MEDIA_ACT action=pause ... success=true` (`src/executors/mediaGovernor.ts:91`) |
| 4. Conversație cu memorie de tură | „Ma numesc Rares." → „Cum ma numesc?" | `BRAIN_INTENT ... kind=speak` + `TTS_BLOCK_END ... reason=success` (`app/index.tsx`) |
| 5. Ambiguitate → corectură | „deschide radio" → „Magic FM" | `UI_STATE_JS ... state=CONFIRMING` apoi `EXEC_TRACE_FOREGROUND_VERIFY ... confirmedByEvent=true` |

## Buguri găsite și reparate în harness (nu în aplicație)

1. **Fără revenire la prim-plan între teste.** Waze/Calculator/YouTube/Spotify preiau prim-planul; fiecare `dispatch()` trebuie să readucă explicit BENSON (`am start -n com.benson.butler/.MainActivity`) înainte de următoarea intrare. Lipsea la prima rulare.
2. **Calculator cere confirmare pe acest dispozitiv.** Calculatorul instalat e „Rechner" (OnePlus/locale german) — o potrivire fuzzy, deci `SINGLE_MATCH_NEEDS_CONFIRMATION`, nu lansare directă. Test în doi pași: comanda, apoi „da".
3. **Waze nu trece prin calea presupusă inițial.** Nu `androidActionExecutor`/`APP_FOREGROUND_REQUEST`, ci unealta guvernată `wazeTool.ts` (`EXEC_TRACE_EXECUTOR executor=governed:waze`), logată prin `console.log` sub tag-ul `ReactNativeJS`, nu `BENSON_AUDIO`.
4. **Evicție din ring buffer-ul logcat.** `adb logcat -c`/`-d` repetat e vulnerabil când mai multe aplicații (fiecare instalată/lansată de teste) scriu în același buffer partajat, cu dimensiune fixă — liniile noastre pot fi împinse afară înainte să le citim. Fix: o captură persistentă unică, într-un fișier (`last_run_capture.log`), fără ring buffer; fiecare test citește doar liniile adăugate de la propriul offset.
5. **Regex fără `.*` după numele tag-ului.** `logAudioDiag` scrie mereu `TAG thread=mqt_v_js ...` — un pattern literal `TAG cuvânt=valoare` (fără `.*` între ele) nu se potrivește niciodată. Afecta 3a, 3b, 3c, 4b — reparat.
6. **Titlul exact dovedit („Workout Mix Radio") e ambiguu față de rezultatele curente.** Toate cele 5 candidate returnate azi de Spotify conțin cuvântul „workout" — potrivirea pe token (`matchDisambiguationPick`, `missionOrchestrator.ts:721-722`) alege primul candidat cu un token comun, nu neapărat titlul dorit. Înlocuit cu selectorul ordinal „a doua" (verificat înaintea potrivirii pe substr/token, linia 711-716), țintind poziția confirmată stabilă a titlului dorit în cele 4 rulări din această rundă.

## Stare finală, onestă — NU 6/6 stabil încă

Pe parcursul a **4 rulări complete** ale scriptului corectat, pe build-ul instalat (`f615a8f0...`):

- **Stabile, PASS de fiecare dată după reparații:** 6 (Calculator), 1 (Waze), 2 (YouTube), 4 (conversație cu memorie).
- **3 (Spotify) și 5 (ambiguitate→corectură) rămân intermitente**, chiar și după reparațiile de mai sus:
  - 3b (selecția „a doua"): a trecut o dată curat (cu titlul greșit ales din cauza bug-ului #6, înainte de reparație), apoi a eșuat din nou după reparație — cauza exactă a rulării de după fix nu e încă izolată; nu am mai reușit să reproduc manual condiția exactă din interiorul scriptului.
  - 5b („Magic FM" ca răspuns la dezambiguizare): a trecut o dată, apoi a eșuat de două ori la rând, inclusiv cu un decalaj scurt (8s) între clarificare și corectură — de fiecare dată "Magic FM" a fost interpretat greșit de BRAIN ca `action=media_play`, nu ca răspuns la dezambiguizare. Nu am găsit cauza exactă (nu pare a fi doar vechime/timeout, dat fiind că a eșuat și la decalaj scurt) — necesită investigație separată, nu o presupunere.

**Conform regulii canonului, nu declar harness-ul gata de poartă automată.** Rămâne un instrument util, reparat semnificativ, dar Secțiunea 1 a canonului nu poate fi bifată `[x]` — vezi jurnalul de mai jos.

## Timp total de rulare
~165-175 secunde per rulare completă (10-11 sub-teste).

---

# CANON LOG — runda: H1 (harness de regresie) — data: 2026-09-22

```
[x] 0.1  Scope lock citit și înțeles — fișierele permise: scripts/regression/** (director nou), H1_REPORT.md (nou, rădăcină)
[x] 0.2  Fișiere interzise citite — niciuna din sarcina H1 nu a cerut un fișier interzis
[x] 0.3  Un singur tip de schimbare declarat: construirea harness-ului de regresie (citește loguri, nu modifică logica aplicației)
[~] 1.1  Harness RULAT înainte de orice schimbare (stare BASELINE) — N/A, harness-ul NU EXISTA înainte de această rundă (primul build al lui)
[x] 1.2  Harness RULAT după construire (stare FINALĂ) — 4 rulări complete, coadă lipită mai sus și în scripts/regression/last_run.log
[ ] 1.3  Compară BASELINE cu FINALA — NEAPLICABIL, nu există BASELINE (harness nou); NU e 6/6 PASS pe toate cele 4 rulări — vezi „Stare finală" mai sus, cu BOLD
[x] 2.1  npx tsc --noEmit — N/A pentru acest fișier (bash, nu TypeScript); nu s-a atins niciun fișier .ts/.tsx în această rundă
[x] 2.2  gradlew assembleRelease — N/A, nicio schimbare de cod aplicație, niciun build nou necesar
[x] 2.3  Certificat — N/A, niciun APK nou construit
[x] 2.4  SHA-256 — N/A, niciun APK nou construit; buildul testat rămâne f615a8f0e9ce83eea3391954219cd2895e0ae7ae59be13f743761e018a157af9 (neschimbat)
[x] 3.1  Fișiere atinse: scripts/regression/run.sh (nou, ~215 linii), H1_REPORT.md (nou, acest fișier)
[x] 3.2  Ieșire din scope lock: NICIUNA
[x] 3.3  setTimeout/setInterval noi: NICIUNUL — harness-ul e un script bash extern, nu atinge codul aplicației; toate „așteptările" sunt `sleep` în script, nu cod care rulează pe telefon
[~] 4.1  Testul de acceptare (6/6 PASS pe build-ul stabil) — 4/6 drumuri stabile (Calculator, Waze, YouTube, Conversație); 2/6 (Spotify, Ambiguitate) intermitente, cauză neizolată complet — vezi mai sus
[ ] 4.2  Tag propus — NEPROPUS încă; canonul cere 6/6 înainte de tag, condiție neîndeplinită
```

**Regulă tare respectată:** raportul de mai sus e onest despre căsuțele nebifate — nu declar runda H1 încheiată. Harness-ul e funcțional și mult mai fiabil decât la prima rulare (4/6 solide), dar 2/6 rămân o problemă reală, nerezolvată, documentată explicit, nu ascunsă.
