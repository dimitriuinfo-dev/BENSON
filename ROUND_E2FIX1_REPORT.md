# RUNDA E2-fix-1 — raport de execuție

**Fără ieșiri din scope lock.** Un singur fișier de cod atins: `app/index.tsx`. Fără `git`, `expo prebuild`, `setx`.

**Un singur tip de schimbare:** o constantă.

---

## 1. Modificarea

### `app/index.tsx` — 1 linie de cod + comentariu

`ECHO_WINDOW_MS` **120000 → 3000**, cu valoarea veche în comentariu alături:

```ts
const ECHO_WINDOW_MS = 3000; // was 120000 (E2-fix-1) — earlier 60000 / 12000 / 6000
```

Plus 4 linii de comentariu deasupra care explică de ce (bloc `// E2-fix-1 (2026-09-07 …)`).

Constanta e folosită în două locuri, ambele acum guvernate de fereastra de 3s:
- `rememberSpoken()` — retează din `lastSpokenRef` intrările mai vechi de `ECHO_WINDOW_MS`.
- `looksLikeSelfEcho()` — ignoră orice intrare rostită cu mai mult de `ECHO_WINDOW_MS` în urmă.

Rezultat: dacă BENSON n-a rostit nimic în ultimele 3 secunde, filtrul self-echo nu se mai aplică deloc. ACK-ul scurt din E1-5 („Deschid.") nu mai blochează 2 minute o comandă reală care începe cu aceleași cuvinte.

`SELF_ECHO_FINGERPRINTS` (fragmentele „did not catch") rămân neatinse — sunt independente de fereastră și țintesc o buclă reală confirmată live; nu ating „deschide YouTube".

---

## 2. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 49s** (67 executed / 878 up-to-date; doar JS) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 876 696 B (~248,8 MiB)** |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · schema v2 verificată |
| Instalare | `9c1464eb` (CPH2663) · `adb install -r -d` → **Success** · `lastUpdateTime=2026-09-07 11:58:06` (era 11:51:09 = E2-fix) · `firstInstallTime` neschimbat → date păstrate |

---

## 3. Build + instalare

- `npx tsc --noEmit` → **0 erori**.
- `gradlew assembleRelease` (cu `ANDROID_HOME` inline) → **BUILD SUCCESSFUL in 49s**. Schimbare doar JS — bundle RN refăcut, fără Kotlin.
- APK: `C:\Users\lenovo\Desktop\BENSON-Android\frontend\android\app\build\outputs\apk\release\app-release.apk` · **260 876 696 B**.
- Certificat: `CN=BENSON, OU=Dev, O=TOKKO, … C=RO` · APK Signature Scheme v2 = verified.
- `adb install -r -d` pe `9c1464eb` (CPH2663) → **Success**.
- `dumpsys package com.benson.butler`: `lastUpdateTime=2026-09-07 11:58:06` (era `11:51:09` = E2-fix), `firstInstallTime` neschimbat `2026-08-23 10:15:49` → build-ul E2-fix-1 e pe telefon, date păstrate.

---

## 4. Constantă de revert

| Constantă | Fișier | Revert |
|---|---|---|
| `ECHO_WINDOW_MS` | `app/index.tsx` | `120000` |

---

## 5. Anti-regresie

Comportamentul pe care fereastra largă îl proteja: bucla acustică „BENSON își aude propriul TTS". Riscul reintroducerii:
- Whisper local poate întârzia transcrierea zeci de secunde după ce BENSON a terminat de vorbit; un ecou care aterizează după 3s nu mai e prins de fereastră.
- **Însă** din E1: microfonul e închis dur cât vorbește BENSON + `TTS_TAIL_MS` (`beginTtsBlock`/`micResumeAtRef`), iar `SELF_ECHO_FINGERPRINTS` prinde fraza de rezervă „did not catch" independent de timp. Deci apărarea principală contra buclei nu e fereastra asta, ci închiderea microfonului — fereastra era o plasă secundară care ajunsese să facă mai mult rău decât bine.
- Dacă bucla de ecou reapare pe dispozitiv (BENSON redeschide o aplicație singur, la câteva zeci de secunde după ce a rostit ceva) → revert: `ECHO_WINDOW_MS = 60000` (compromis) sau `120000`.

---

## 6. Acceptare pe dispozitiv

„Benson deschide YouTube" imediat după orice ACK al lui BENSON („Deschid." / „Pornesc traseul." / „O sun.") → se deschide, **zero `REJECTED_self_echo`** în `TRANSCRIPT_ACCEPTED`.
