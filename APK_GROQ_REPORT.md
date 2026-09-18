# Raport — APK release pentru testarea cheii Groq + transcriere

Data: 2026-08-28 · Rundă cu un singur obiectiv: APK release instalabil în care cheia
Groq se introduce din Settings și se poate testa.

---

## 0. Rezultat (pe scurt)

| Verificare | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (exit 0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 23s** (exit 0, 945 taskuri, 63 executate) |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` — schema v2, 1 semnatar, RSA 2048 |
| Modificări de cod | **ZERO** — tot ce cerea runda era deja implementat din rundele anterioare |

**Calea completă a APK-ului:**

```
C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk
```

**Dimensiune:** 260 850 620 bytes ≈ 248.8 MiB (260.85 MB)
`applicationId=com.benson.butler` · `versionName=1.0.0` · `versionCode=1` · `minSdk=24`

**Certificat (apksigner verify --print-certs):**

```
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO
Signer #1 certificate SHA-256 digest: fbbc618da8ecd574a05676e260953ef1628d3b8d639ceb0fbc0d1ee9ec5184da
Signer #1 certificate SHA-1  digest: 338ee60492a9bbd2ec3b73bbddfac1fdcac82987
Signer #1 key algorithm: RSA · key size: 2048
```

Semnat cu keystore-ul de release (`C:\Users\lenovo\BENSON_KEYSTORE_BACKUP\benson-release.keystore`,
alias `benson-release`), injectat de `plugins/withReleaseSigning.js` în `android/app/build.gradle`.
`CN=BENSON, O=TOKKO` — conform regulii din `frontend/CLAUDE.md`.

---

## 1. Ce era deja implementat (neatins, doar verificat)

Fluxul Groq STT + Settings a fost livrat integral în „Round 2 / GO round". Verificat fișier cu fișier:

| Fișier | Rol | Stare |
|---|---|---|
| `lib/engines/types.ts` | tipuri `SttEngine`, `EngineConfig` | complet |
| `lib/engines/settingsStore.ts` | `saveEngineConfig` / `getEngineConfig` — cheia Groq în `expo-secure-store` (prefix `benson.engine.apiKey.stt.groq`), `maskApiKey` | complet |
| `lib/engines/registry.ts` | `resolveSttEngine()` → Groq implicit, `local` rezervă; fallback pe `local` dacă nu e cheie | complet |
| `lib/engines/stt/groqStt.ts` | `transcribeWithGroq` (POST `/audio/transcriptions`, `whisper-large-v3-turbo`) **și** `testGroqConnection` (apel real GET `/openai/v1/models` cu `Authorization: Bearer`) | complet |
| `app/index.tsx` | modalul Settings deschis de pictograma rotiță; câmp „Groq API key"; buton SAVE KEYS; buton TEST; afișare OK / eroare | complet |
| `components/BensonMainScreen.tsx` | `SettingsGear` (Ionicons `settings-outline`) → `onOpenSettings` → `openSettings()` → `setSettingsOpen(true)` | complet |

Nimic nu a fost rescris (instrucțiunea „nu rescrie ce există" + „varianta cu cele mai puține modificări").

---

## 2. Ecranul de setări — verificare punct cu punct

Deschidere: rotița de deasupra medalionului (`app/index.tsx:2943` → `openSettings` → `app/index.tsx:1605`).

- **Câmp „Groq API key"** — `app/index.tsx:3101-3102`. Placeholder
  `Groq API key (gsk_...) — transcriere`; când există deja o cheie salvată, placeholder-ul arată
  și hint-ul mascat `· salvat: ••••XXXX` (`savedGroqMasked`, reîncărcat la fiecare deschidere a
  modalului — `app/index.tsx:373-384`). `secureTextEntry`, `autoCapitalize="none"`, `autoCorrect={false}`.

- **Salvare prin SAVE KEYS** — `app/index.tsx:3103-3106` → `saveApiKeys()` (`app/index.tsx:1673-1697`).
  Dacă câmpul Groq e ne-gol: `saveEngineConfig('stt', 'groq', { apiKey: grq })` → `expo-secure-store`
  (hardware-backed, nu AsyncStorage, nu fișier, nu log — regula 3 din CLAUDE.md). Câmpul se golește
  după salvare; hint-ul mascat se actualizează. Câmp gol la salvare = nu se șterge cheia existentă.

- **Buton TEST** — `app/index.tsx:3125-3129` → `runGroqTest()` (`app/index.tsx:392-409`):
  1. citește configul salvat (`getEngineConfig('stt','groq')`),
  2. compune `cfg` = { baseUrl: salvat sau `https://api.groq.com/openai/v1`, model: `whisper-large-v3-turbo`,
     apiKey: **ce e tastat în câmp acum** sau, dacă e gol, cheia salvată },
  3. dacă nu există nicio cheie → afișează `EROARE: nicio cheie Groq salvată` (fără apel de rețea),
  4. altfel apelează `testGroqConnection(cfg)` — **apel HTTP real la Groq**: `GET https://api.groq.com/openai/v1/models`
     cu antet `Authorization: Bearer <cheie>`, timeout 20 s (`lib/engines/stt/groqStt.ts:93-104`).

- **Afișare rezultat** — `app/index.tsx:3131-3133`:
  - succes (`res.ok`) → text verde **`OK`**,
  - HTTP ne-2xx → roșu **`EROARE: HTTP 401`** / `HTTP 429` / etc. (statusul exact returnat de Groq),
  - eroare de rețea / timeout / excepție → roșu **`EROARE: <mesajul exact>`** (mesajul `Error`).

### Alegere: TEST lovește `/models`, nu `/audio/transcriptions` — și de ce

Ecranul Settings nu are niciun buffer audio disponibil, deci nu se poate rula o transcriere reală
de acolo. `testGroqConnection` face în schimb un GET autentificat pe `/models` — un dus-întors real
la serverele Groq care validează exact ce contează pentru butonul TEST: cheia e acceptată
(`200` vs `401`/`403`), contul nu e limitat (`429`), și există conectivitate. Aceasta este proiectarea
deja existentă din runda anterioară (comentariu la `lib/engines/stt/groqStt.ts:90-92`); a înlocui-o
cu o transcriere sintetică ar fi însemnat cod nou și un fișier audio de test în bundle — exclus de
regula „cele mai puține modificări". Transcrierea propriu-zisă se testează în aplicație, prin voce,
cu cheia salvată (ruta `voiceAgent.ts` → `resolveSttEngine()` → `createGroqSttEngine`).

---

## 3. `npx tsc --noEmit`

```
EXIT: 0
```

Zero erori, zero warning-uri. Niciun fișier sursă modificat în această rundă.

---

## 4. `gradlew assembleRelease`

Comandă: `./gradlew assembleRelease --console=plain` din `frontend/android/`.

### Prima încercare — EȘEC (config de mediu, nu cod)

```
FAILURE: ... SDK location not found. Define a valid SDK location with an ANDROID_HOME
environment variable or by setting the sdk.dir path in ... android/local.properties
```

`frontend/android/local.properties` nu există în acest checkout și `ANDROID_HOME` /
`ANDROID_SDK_ROOT` nu erau setate în shell.

### Alegere: variabilă de mediu doar pentru comandă, NU fișier nou în `android/`

Două opțiuni:
- **(A)** crea `frontend/android/local.properties` cu `sdk.dir=...` — dar `android/**` e pe lista
  „nu atinge" din CLAUDE.md, iar `local.properties` e sub acel arbore.
- **(B)** exporta `ANDROID_HOME` doar pentru invocarea `gradlew`, fără `setx`, fără persistență.

Am ales **(B)** — zero fișiere atinse, zero modificări permanente de mediu (respectă „Fără `setx`.
Fără modificări permanente de PATH."). SDK-ul a fost găsit la calea standard
`C:\Users\lenovo\AppData\Local\Android\Sdk`.

```
export ANDROID_HOME="C:/Users/lenovo/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew assembleRelease --console=plain
```

> **Notă pentru rundele viitoare:** dacă vrei ca `gradlew` să meargă fără prefix, adaugă manual
> `frontend/android/local.properties` cu `sdk.dir=C:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk`
> (fișier ne-versionat, generat în mod normal de Android Studio). Nu l-am creat eu — e sub `android/`.

### A doua încercare — SUCCES

```
BUILD SUCCESSFUL in 23s
945 actionable tasks: 63 executed, 882 up-to-date
GRADLE_EXIT: 0
```

Bibliotecile native (`buildCMakeRelWithDebInfo` pe arm64-v8a / armeabi-v7a / x86 / x86_64) erau deja
compilate și cache-uite din build-urile anterioare în arborele `android/` ne-versionat, de aici
timpul mic. APK-ul a fost re-împachetat și re-semnat de la zero (`app-release.apk`, mtime 18:07).

---

## 5. Ieșiri din scope

Niciuna. Nu s-a atins niciun fișier din lista interzisă (`android/**`, `plugins/**`, `modules/**`,
`whisper-models/**`, `porcupine-model/**`, `whatsappTool.ts`, `missionValidator.ts`,
`missionExecutor.ts`). Nu s-a rulat `git`. Nu s-a rulat `expo prebuild`. Nu s-a folosit `setx`.
Zero fișiere sursă modificate — singura acțiune a fost setarea temporară a `ANDROID_HOME` pentru
o singură comandă gradle.

---

## 6. Ce ai de făcut la testare

1. Instalează APK-ul de la calea de la pct. 0.
2. Deschide app-ul → apasă rotița de deasupra medalionului → secțiunea **API KEYS**.
3. Lipește cheia Groq (`gsk_...`) în câmpul „Groq API key" → **SAVE KEYS**
   (mesaj de confirmare „API keys updated"; câmpul se golește, hint-ul devine `· salvat: ••••XXXX`).
4. În secțiunea **STT**, verifică că e selectat „Groq (implicit)".
5. Apasă **TEST** → ar trebui să apară **OK** verde. Dacă apare `EROARE: HTTP 401` → cheie greșită;
   `HTTP 429` → limită de rată/cont; `EROARE: <text>` → problemă de rețea.
6. Pentru transcrierea propriu-zisă: comandă vocală după wake-word; ruta STT folosește automat Groq
   cu cheia salvată.
