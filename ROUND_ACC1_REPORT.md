# RUNDA ACC-1 — Dovada atomică de interacțiune într-o altă aplicație

## Verdict

```
ACCESSIBILITY_FOUNDATION = PASS
```

BENSON deschide o aplicație externă, îi citește UI-ul prin Android Accessibility, execută un click real pe un element și **verifică efectul în arbore**. Demonstrat pe dispozitiv (`9c1464eb` / CPH2663 / OnePlus Nord 4, OxygenOS 15), reproductibil 3/3 rulări. Nu s-a atins nicio rețetă, WhatsApp logic, potrivire fonetică sau navigație.

---

## 1. Fișiere modificate

| Fișier | Ce | Linii |
|---|---|---|
| `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/AccessibilityFoundationTest.kt` | **NOU** — harness de diagnostic ACC-1 (TEST 1 Calculator + TEST 2 WhatsApp). Nu e legat de nicio rețetă/feature. | +290 |
| `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonAccessibilityService.kt` | `registerAcc1TestReceiver()` — receiver dinamic (fără intrare de manifest) pentru broadcast-ul `com.benson.acc1.RUN`, apelat din `onServiceConnected`; dezînregistrare în `onDestroy`. | +32 |

Declanșare: `adb shell am broadcast -a com.benson.acc1.RUN` (opțional `--es calc <package>`). Zero cod JS, zero atingere a fișierelor interzise (`app/index.tsx`, `lib/agents/**`, `src/core/mission/tools/**`, `android/**`, `plugins/**` — toate neatinse).

> APK-ul conține și rundele anterioare necomise (C1/C3/C3-fix/BF1-b/BF1-c) — nimic din ele modificat aici.

---

## 2. Rezultat build

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** (niciun TS schimbat — sanity). |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 34s`** · `945 actionable tasks: 86 executed, 859 up-to-date` (Kotlin recompilat) · `android/` neregenerat. |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 901 328 B (~248,8 MiB)** · mtime `2026-09-08 13:33`. |
| SHA-256 | `5f4fb241eb44237146e5e53e69be3b3aedcf63cf21c4f7dc22ac5ebf2376982c` |
| Certificat | `apksigner verify` → **exit 0** · `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO`. |
| Instalare | `9c1464eb` · `adb install -r` → **`Success`** · `lastUpdateTime=2026-09-08 13:33:31`. |

---

## 3. Logurile exacte (rulare curată, 13:33:45 — reprodusă identic la 13:34:27 și 13:35:01)

### TEST 1 — Calculator (`com.oneplus.calculator`, v16.4.2, UI germană)

```
ACC_TEST launch package=com.oneplus.calculator
ACC_WINDOW  pkg=com.oneplus.calculator class=android.widget.FrameLayout type=null
ACC_SNAPSHOT pkg=com.oneplus.calculator nodes=53 relevant=53
  ACC_NODE viewId=com.oneplus.calculator:id/clr      text=""  desc="löschen"     class=android.widget.Button clickable=true enabled=true bounds=[42,986][264,1208]
  ACC_NODE viewId=com.oneplus.calculator:id/digit_7  text="7" desc=""            class=android.widget.Button   clickable=true enabled=true bounds=[42,1244][264,1466]
  ACC_NODE viewId=com.oneplus.calculator:id/digit_0  text="0" desc=""            class=android.widget.Button   clickable=true enabled=true bounds=[300,2018][522,2240]
  ACC_NODE viewId=com.oneplus.calculator:id/op_add   text=""  desc="Hinzufügen"  class=android.widget.Button   clickable=true enabled=true bounds=[816,1760][1038,1982]
  ACC_NODE viewId=com.oneplus.calculator:id/eq       text=""  desc="Ist gleich"  class=android.widget.Button   clickable=true enabled=true bounds=[816,2018][1038,2240]
  ACC_NODE viewId=com.oneplus.calculator:id/formula  text=""  desc=""            class=android.widget.TextView clickable=true enabled=true bounds=[48,379][1008,682]
  … (40 linii ACC_NODE, tot arborele relevant — 53 noduri totale)
ACC_TARGET found=true viewId=com.oneplus.calculator:id/digit_7 text="7" desc="" class=android.widget.Button clickable=true enabled=true bounds=[42,1244][264,1466]
ACC_BASELINE display=""
ACC_CLICK   action=ACTION_CLICK result=true
ACC_VERIFY  expected=7 observed="7" success=true
ACC_TEST calculator result=PASS
ACCESSIBILITY_FOUNDATION = PASS
```

- `ACC_WINDOW type=null`: `AccessibilityNodeInfo.getWindow()` întoarce null fără flag-ul `flagRetrieveInteractiveWindows` (neactivat — vezi BF1-c). Fereastra E detectată corect (`pkg=com.oneplus.calculator` prin `rootInActiveWindow.packageName` + `lastForegroundPackage`); doar tipul `AccessibilityWindowInfo` nu e citibil. Nu blochează testul.
- `ACC_BASELINE display=""`: harness-ul apasă întâi `clr` (reset), deci display-ul e gol înainte de click. `observed="7"` e efect REAL al click-ului, nu o valoare preexistentă.
- Câmpul verificat: `com.oneplus.calculator:id/formula` → text `"7"` după `ACTION_CLICK` pe `digit_7`. Confirmat și independent cu `uiautomator dump` extern (`formula text="7"`).
- **`ACTION_CLICK` a fost suficient — fallback-ul `dispatchGesture` NU s-a executat** (și nici nu putea: `canPerformGestures=false`).

### TEST 2 — WhatsApp (`com.whatsapp`, v2.26.34.81, UI germană) — rulează doar fiindcă TEST 1 = PASS

```
WA_ACC begin
WA_ACC_WINDOW   pkg=com.whatsapp class=android.widget.FrameLayout type=null
WA_ACC_SNAPSHOT nodes=104
WA_ACC_TARGET   found=true viewId=com.whatsapp:id/search_bar_inner_layout desc="Meta AI fragen oder suchen" class=androidx.appcompat.widget.LinearLayoutCompat clickable=true bounds=[36,301][1044,445]
WA_ACC_CLICK    result=true
WA_ACC_VERIFY   success=true why=search_input_present observedNodes=7 editBefore=0
WA_ACC restore=back_pressed
```

- Element ales: bara de căutare (`search_bar_inner_layout`) — sigur și **reversibil**: deschide modul de căutare; `GLOBAL_ACTION_BACK` îl închide. Zero mesaj, zero apel, zero acțiune destructivă.
- Verificare: după click apare un nod cu viewId ce conține `search_input` (`why=search_input_present`) — UI-ul WhatsApp s-a schimbat efectiv. Apoi BACK readuce lista de chat-uri.

### Fără ACC_GESTURE / ACC_VERIFY_GESTURE în rularea finală
Nu au fost necesare — `ACTION_CLICK` a produs efect verificat la ambele teste. (Pentru referință: într-o iterație anterioară `dispatchGesture` a returnat `false` / `dispatch_exception` fiindcă `canPerformGestures=false` — deci fallback-ul pe gest NU e disponibil pe configul actual; nu a fost nevoie de el.)

---

## 4. Diagnostic — de ce prima încercare a dat FAIL și cum s-a rezolvat (fără a extinde capabilitățile)

Prima rulare: `ACC_CLICK result=true` dar `ACC_VERIFY observed="" success=false`, deși `uiautomator` extern arăta `formula text="7"` la 6 s după click. **Click-ul funcționa; citirea de verificare a lui BENSON era stale.**

Cauza: serviciul e abonat DOAR la `typeWindowStateChanged|typeWindowContentChanged` (config XML, motive CPU/termice). Actualizarea câmpului `formula` (un `TextView`) vine prin `TYPE_VIEW_TEXT_CHANGED` — neascultat — deci subarborele din `rootInActiveWindow` rămâne stale în fereastra de verificare.

Fix, pe partea de CITIRE, fără abonament nou și fără `canPerformGestures`/`flagRetrieveInteractiveWindows`:
`readDisplayFresh()` re-ia `rootInActiveWindow`, găsește nodul de display și cheamă **`AccessibilityNodeInfo.refresh()`** pe el (IPC la aplicație, la cerere) înainte de a-i citi textul; poll scurt (8×300 ms) pentru repaint-ul întârziat. După fix: `observed="7" success=true`, stabil 3/3.

> Relevant și pentru rețeta WhatsApp: dacă un pas citește ecranul imediat după o acțiune și nu vede schimbarea, `node.refresh()` înainte de citire e soluția — nu un abonament de evenimente mai larg.

---

## 5. PASS / FAIL — criteriile rundei

| Criteriu PASS FOUNDATION | Stare |
|---|---|
| `ACC_SNAPSHOT nodes>0` | ✅ `nodes=53` |
| `ACC_TARGET found=true` | ✅ `viewId=com.oneplus.calculator:id/digit_7` |
| click sau gesture produce efect verificat (`ACC_VERIFY... success=true`) | ✅ `ACTION_CLICK` → `ACC_VERIFY observed="7" success=true` |
| `rootInActiveWindow != null` | ✅ |
| Calculator detectat ca fereastră activă | ✅ `ACC_WINDOW pkg=com.oneplus.calculator` |
| butonul `7` există în arbore | ✅ `com.oneplus.calculator:id/digit_7` |
| serviciul primește evenimentele necesare | ✅ (cu `refresh()` pe citire — vezi §4) |

**`ACCESSIBILITY_FOUNDATION = PASS`.** TEST 2 (WhatsApp) — de asemenea PASS: click pe `search_bar_inner_layout`, UI schimbat verificat, revenire cu BACK.

Nu se continuă cu alte modificări BENSON după acest verdict, conform rundei.

---

## 6. Cum re-rulezi tu

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" shell am force-stop com.oneplus.calculator   # baseline curat
"$ADB" logcat -c
"$ADB" shell am broadcast -a com.benson.acc1.RUN
"$ADB" logcat -d BENSON_AUDIO:I '*:S' | findstr /C:"ACC_" /C:"WA_ACC" /C:"ACCESSIBILITY_FOUNDATION"
```
Aștepți: `ACC_VERIFY ... success=true` și `ACCESSIBILITY_FOUNDATION = PASS`.
