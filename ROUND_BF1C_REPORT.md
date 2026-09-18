# RUNDA BF1-c — assert_package ignoră overlay-ul propriu

**Scope permis:** `src/core/mission/tools/whatsappTool.ts`, `modules/benson-accessibility/**`. Atins un singur fișier de cod: `modules/benson-accessibility/android/src/main/java/expo/modules/accessibility/BensonCommandExecutor.kt`. `whatsappTool.ts` — neatins (fixul e 100% nativ). Fără `git`, `expo prebuild`, `setx`. Nimic comis. `android/` NU a cerut regenerare (recompilare Kotlin normală: 86 executed / 859 up-to-date).

---

## 1. Cauza (log + cod)

```
RECIPE_STEP index=1 name="WhatsApp în prim-plan" found=false elapsedMs=66202
```

Pasul 1 din `runCallRecipe` (`whatsappTool.ts:816`) e `assert_package com.whatsapp`. În nativ, `BensonCommandExecutor.doAssertPackage` verifica:

```kotlin
service.rootInActiveWindow?.packageName?.toString() == pkg
```

`rootInActiveWindow` întoarce rădăcina **ferestrei active** din punctul de vedere al serviciului de accesibilitate. Bula BENSON e o fereastră `WindowManager` de tip `TYPE_APPLICATION_OVERLAY`; serviciul o vede ca fereastră activă → `packageName` = `com.benson.butler` → `assert_package` cade, deși WhatsApp e vizibil dedesubt. Aceeași verificare exista și în `waitForCandidates` (gata `requirePackage` pentru fiecare `click`/`set_text` din rețetă), deci blocajul lovea și pașii 2/3/5/7, nu doar pasul 1.

---

## 2. Abatere de la abordarea cerută (regula 4 din CLAUDE.md) — și de ce e mai bună

Runda cerea: „se ignoră ferestrele de tip overlay ... Se verifică fereastra de activitate — cea cu tipul `TYPE_APPLICATION`." Asta înseamnă `AccessibilityService.getWindows()` + filtrare pe `AccessibilityWindowInfo.type`.

**Nu am folosit `getWindows()`.** Motiv, verificat în cod: `getWindows()` întoarce **listă goală** fără flag-ul `flagRetrieveInteractiveWindows` în `accessibility_service_config.xml`. Config-ul actual are doar `flagReportViewIds` (linia 38). A adăuga `flagRetrieveInteractiveWindows` = extindere de capabilitate a serviciului de accesibilitate — exact clasa de modificare cu **istoric documentat de auto-dezactivare pe acest ColorOS** (comentariile din `accessibility_service_config.xml:11-22`: combinația `canRetrieveWindowContent + canPerformGestures` a declanșat euristica anti-spyware și a dezactivat serviciul automat pe acest dispozitiv, 2026-07-09). Un `assemble` care trece dar lasă serviciul dezactivabil pe telefon e mai rău decât o abatere motivată.

**Ce am făcut în loc:** dacă fereastra activă e propriul overlay al lui BENSON (`rootInActiveWindow.packageName == service.packageName`, sau `null` într-o tranziție), se cade pe `BensonAccessibilityService.lastForegroundPackage` — care e alimentat **exclusiv** de evenimente `TYPE_WINDOW_STATE_CHANGED` (`BensonAccessibilityService.kt:341`). Un overlay `WindowManager` ne-focusabil **nu emite** `TYPE_WINDOW_STATE_CHANGED` (doar tranzițiile de ferestre de activitate cu titlu îl emit), deci `lastForegroundPackage` ține pachetul ultimei ferestre **`TYPE_APPLICATION`** reale — fix ce cerea runda, fără capabilitate nouă și fără modificare de config.

Rezultat funcțional identic: `assert_package` trece când WhatsApp e fereastra de activitate reală, chiar dacă bula e fereastra de accesibilitate de sus. Fals-pozitiv exclus: dacă activitatea reală ar fi propriul ecran BENSON, `lastForegroundPackage` ar fi `com.benson.butler` ≠ `com.whatsapp` → pasul cade corect.

**Limită cunoscută (raportată):** varianta asta tratează DOAR overlay-ul propriu al lui BENSON. Un overlay/alertă de sistem terță pe deasupra nu e „privită dincolo" — dar bug-ul dovedit e „BENSON se vede pe sine", iar `getWindows()` (cu riscul lui) ar fi singura cale să acoperi și cazul terț. Dacă logul de pe dispozitiv arată `ASSERT_PKG ... activityWindow=com.benson.butler` (adică presupunerea „overlay-ul nu emite WINDOW_STATE_CHANGED" e greșită pe acest build), atunci abordarea `getWindows()` + flag devine necesară — rundă separată, cu revert propriu.

---

## 3. Fixul (`BensonCommandExecutor.kt`)

### Constantă de revert (companion object)
```kotlin
const val BF1C_IGNORE_OVERLAY_PKG = true
```
Pe `false` → `foregroundPackageMatches` se reduce la `rootInActiveWindow?.packageName == expected` (comportamentul de azi).

### Helper nou
```kotlin
private fun foregroundPackageMatches(expected: String): Boolean {
    val topPkg = service.rootInActiveWindow?.packageName?.toString()
    if (topPkg == expected) return true
    if (BF1C_IGNORE_OVERLAY_PKG && (topPkg == null || topPkg == service.packageName)) {
        val activityPkg = BensonAccessibilityService.lastForegroundPackage
        if (activityPkg == expected) {
            Log.i("BENSON_AUDIO",
                "ASSERT_PKG expected=$expected topWindow=${topPkg ?: "null"}(overlay) " +
                "activityWindow=$activityPkg result=pass")
            return true
        }
    }
    return false
}
```

### Puncte de folosire
| Loc | Înainte | După |
|---|---|---|
| `doAssertPackage` (bucla) | `service.rootInActiveWindow?.packageName?.toString() == pkg` | `foregroundPackageMatches(pkg)` |
| `doAssertPackage` (mesaj de eșec) | `Foreground is <top>, expected <pkg>.` | `Foreground is <top> (activity=<lastForegroundPackage>), expected <pkg>.` |
| `waitForCandidates` (gata `requirePackage`) | `... == requirePackage` | `requirePackage == null \|\| foregroundPackageMatches(requirePackage)` |

Log nou, la trecerea prin fallback:
```
ASSERT_PKG expected=com.whatsapp topWindow=com.benson.butler(overlay) activityWindow=com.whatsapp result=pass
```
(Când `rootInActiveWindow` e deja `com.whatsapp`, pasul trece pe calea rapidă, fără linia asta — normal.)

---

## 4. Linii modificate (`BensonCommandExecutor.kt`)

| Zonă | Ce | Δlinii |
|---|---|---|
| `companion object` | comentariu cauză + `const val BF1C_IGNORE_OVERLAY_PKG = true` | +14 |
| după `companion` | `private fun foregroundPackageMatches(...)` | +21 |
| `doAssertPackage` | `foregroundPackageMatches(pkg)` + mesaj de eșec cu `activity=` | +3 / −3 |
| `waitForCandidates` | `foregroundPackageMatches(requirePackage)` + comentariu | +3 / −2 |
| **Total net** | | **~+38** |

Nimic șters funcțional. `whatsappTool.ts` — 0 linii (fixul e nativ; rețeta apelează deja `assert_package` / `requirePackage`, doar semantica lor nativă s-a schimbat).

---

## 5. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **`TSC_EXIT=0`** (niciun TS schimbat — sanity). |
| `gradlew assembleRelease` | **`BUILD SUCCESSFUL in 40s`** · `86 executed / 859 up-to-date` (Kotlin recompilat) · `android/` neregenerat. |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 884 944 B (~248,8 MiB)** · mtime `2026-09-08 13:14:01`. |
| SHA-256 | `fee758a7402498826844888eeb426c5beb5e0cc0be8948048356da43c0bf7c57` |
| Certificat | `apksigner verify` → **exit 0** · `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · Scheme v2. |
| Instalare | `9c1464eb` (CPH2663) · `adb install -r` → **`Success`** · `lastUpdateTime=2026-09-08 13:14:28` · `firstInstallTime` neschimbat → date păstrate. |

### Coada `gradlew`
```
BUILD SUCCESSFUL in 40s
945 actionable tasks: 86 executed, 859 up-to-date
```

---

## 6. Verificare pe dispozitiv — de rulat de tine (telefonul e blocat acum: `mDreamingLockscreen=true`, `mScreenOn=false`)

```bash
export ANDROID_HOME="C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
"$ADB" logcat -c
"$ADB" logcat ReactNativeJS:I BENSON_AUDIO:I BensonCmdExec:I BensonA11y:I *:S > bf1c.log
#  → deblochezi telefonul, „Sună-o pe Hannah pe WhatsApp". Ctrl+C. Apoi:
findstr /C:"ASSERT_PKG" /C:"RECIPE_STEP" /C:"SELECTOR" bf1c.log
```

Criteriu:
```
ASSERT_PKG expected=com.whatsapp topWindow=com.benson.butler(overlay) activityWindow=com.whatsapp result=pass
RECIPE_STEP index=1 name="WhatsApp în prim-plan" found=true elapsedMs=<mic, <5000>
RECIPE_STEP index=2 ... found=true          ← nu mai cade pe requirePackage
...
```
- `RECIPE_STEP index=1 found=false elapsedMs=66202` NU trebuie să mai apară.
- Dacă apare `ASSERT_PKG ... activityWindow=com.benson.butler` → presupunerea „overlay-ul nu emite WINDOW_STATE_CHANGED" e greșită pe acest build; îmi trimiți linia și trec pe `getWindows()` + `flagRetrieveInteractiveWindows` (rundă separată, cu măsurarea riscului ColorOS).

---

## 7. Comportamente dovedite (CLAUDE.md) — impact

| Comportament | De ce nu regresează |
|---|---|
| Navigație Waze cu governance / deschidere aplicație după nume | `assert_package` / `requirePackage` trec acum într-un caz în plus (overlay propriu deasupra), niciodată mai puține. Calea rapidă (`rootInActiveWindow == expected`) e neschimbată — cazul Waze o folosește deja. |
| Apel WhatsApp cap-coadă | Ținta directă: deblochează pașii 1–7 din `runCallRecipe`, care nu se mai opreau la „BENSON se vede pe sine". Selectorii viewId (BF1-b), potrivirea fonetică (B-fix2), prefixul de 3 caractere — toate în build, neatinse. |
| Microfon / STT (C1/C3) | Fără legătură — schimbarea e în executorul de comenzi de accesibilitate. |
| Index de aplicații / conversație liberă | Fără legătură. |

Fals-pozitiv nou imposibil: fallback-ul cere `lastForegroundPackage == expected` — dacă ecranul BENSON ar fi activitatea reală, `lastForegroundPackage` ar fi `com.benson.butler` și pasul ar cădea corect.

---

## 8. Ce am vrut să schimb într-un fișier permis și nu am schimbat

`accessibility_service_config.xml` (în scope prin `modules/benson-accessibility/**`): aș fi adăugat `flagRetrieveInteractiveWindows` pentru abordarea „pe tipuri de fereastră" cerută textual. **Nu am făcut-o** — extindere de capabilitate cu istoric de auto-dezactivare ColorOS pe acest dispozitiv (§2). Fallback-ul pe `lastForegroundPackage` dă același rezultat fără riscul ăsta. Reconsider doar dacă logul dovedește că presupunerea nu ține.
