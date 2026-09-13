# RUNDA A — `<queries>` MAIN+LAUNCHER (fix pentru plafonul de 49)

29.08.2026. Lock: `plugins/withLauncherQueries.js` (nou) · `app.json` (o intrare) ·
`android/app/src/main/AndroidManifest.xml`. Fără `QUERY_ALL_PACKAGES`. Fără `git`, fără `prebuild`, fără `setx`.

---

## 0. Verificări

| | |
|---|---|
| `npx tsc --noEmit` | **0 erori** |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 1m 1s** (exit 0) |

**APK:** `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk`
260 862 956 bytes ≈ 248.8 MiB · 12:29 · cert `CN=BENSON, OU=Dev, O=TOKKO, …, C=RO` · v2 · SHA-256 `fbbc618d…5184da`

---

## 1. Blocul, aplicat în DOUĂ locuri (cum ai cerut)

**a) Direct în manifest** — `android/app/src/main/AndroidManifest.xml`, al treilea bloc `<queries>`,
ca buildul de azi (fără prebuild) să-l aibă:

```xml
<queries>
  <intent>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.LAUNCHER"/>
  </intent>
</queries>
```

**b) Config plugin** — `plugins/withLauncherQueries.js` (nou), înregistrat în `app.json` după
`withProguardRules.js`. Folosește `withAndroidManifest` din `@expo/config-plugins`, inserează exact
același bloc, **idempotent** (verifică dacă există deja MAIN+LAUNCHER înainte de a-l adăuga). Asigură
că un `expo prebuild` viitor îl regenerează identic — deci editarea directă nu se pierde.

`node -e "require('./plugins/withLauncherQueries.js')"` → încarcă OK. `app.json` → JSON valid.

Manifestul mergeat de gradle (`app/build/intermediates/merged_manifest/release/…`) conține blocul
(liniile 99–105), cu comentariul `@benson-launcher-queries`.

---

## 2. Verificare obligatorie după instalare — AMBELE trec

APK instalat pe `9c1464eb` (`Success`).

**a) `dumpsys package com.benson.butler | grep queriesIntents`:**

```
queriesIntents=[ …, Intent { act=android.speech.RecognitionService },
                 Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] } ]
```

✅ Conține `MAIN` + `LAUNCHER`. (Înainte: nu-l avea.)

**b) `APP_INDEX count=` după cold start (`am force-stop` + relansare):**

```
08-29 12:31:55  APP_INDEX thread=mqt_v_js count=274 source=warm elapsedMs=9556
```

✅ **A sărit de la 49 la 274.** (Telefonul are 277 activități launcher; 274 = după `distinctBy`
pe packageName + excluderea BENSON + câteva fără etichetă.)

---

## 3. Observație — costul enumerării

`elapsedMs=9556` la warm-up: `getInstalledApps()` nativ re-encodează base64 iconițele a 274 de
aplicații ~9,5 s. Se întâmplă o dată, în fundal, la 3 s după pornire; după aceea totul e din cache
(`source=cache elapsedMs=0`). O variantă nativă „listă fără iconițe" ar tăia asta la <1 s, dar e în
`modules/` — în afara lock-ului. Pentru demo nu deranjează (warm-up-ul e terminat până apuci să dai
prima comandă), dar merită pus pe listă.

---

## 4. Acceptare

| Criteriu | Stare |
|---|---|
| `queriesIntents` conține MAIN+LAUNCHER | ✅ verificat pe dispozitiv |
| `APP_INDEX count=` sare 49 → ~277 | ✅ **274** verificat pe dispozitiv |
| „Deschide YouTube" → se deschide | ▶️ acum posibil — YouTube e în cele 274; de confirmat vocal de tine |
| tsc 0 erori · assembleRelease · APK semnat | ✅ |

Restul Rundei A (indexul, fuzzy, propuneri, modelul `gpt-oss-120b`) era deja în buildul precedent
și e inclus și aici.
