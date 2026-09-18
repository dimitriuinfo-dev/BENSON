# RUNDA E3 (UI: bandă, bulă, sunet) — raport de execuție

**Fără ieșiri din scope lock.** Atinse: `modules/benson-overlay/**`, `app/index.tsx`, `ROUND_E3_REPORT.md`. `assets/sounds/**` — **nu a fost creat**: sunetul E3-3 e generat procedural în cod nativ (vezi §3.3), nu e fișier. Fără `git`, `expo prebuild`, `setx`. Certificat `CN=BENSON, O=TOKKO` neschimbat.

**Un singur tip de schimbare:** feedback vizual + auditiv la ascultare — bandă, bulă, sunet, toate în același modul.

---

## 1. Verificare

| Pas | Rezultat |
|---|---|
| `npx tsc --noEmit` | **0 erori** (EXIT=0) |
| `gradlew assembleRelease` | **BUILD SUCCESSFUL in 1m 35s** (90 executed / 855 up-to-date; `:app:compileReleaseKotlin` re-executat — modulele native noi/editate) |
| APK | `frontend/android/app/build/outputs/apk/release/app-release.apk` — **260 877 608 B (~248,8 MiB)** |
| Certificat | `CN=BENSON, OU=Dev, O=TOKKO, L=Unknown, ST=Unknown, C=RO` · APK Signature Scheme v2 = verified |
| Instalare | `9c1464eb` (CPH2663 / OnePlus Nord 4) · `adb install -r -d` → **Success** · `lastUpdateTime=2026-09-07 12:38:08` (era 11:58:06 = E2-fix-1) · `firstInstallTime` neschimbat → date păstrate |

---

## 2. Modificări, fișier cu fișier

### `modules/benson-overlay/android/.../BubbleDotsView.kt` — **NOU (~92 linii)** — E3-2
`View` custom care desenează **trei puncte** pe un cerc mic (120° între ele), colorate `gold / verde‑închis / gold`. `applyMotion(mode)`:
- `"listening"` → `ObjectAnimator` pe `rotation` 0 → **−360°** (invers acelor de ceas), 2400ms, `INFINITE`, liniar.
- `"executing"` → `ObjectAnimator` pe `scaleX/scaleY` 0.78 ↔ 1.18 + `alpha` 0.65 ↔ 1, 620ms, `INFINITE REVERSE` → **pulsează**.
- `"static"` → animatorul se anulează, `rotation=0`, `scale=1` → puncte în repaus.

### `modules/benson-overlay/android/.../WakeSound.kt` — **NOU (~103 linii)** — E3-3
Sunetul „microfon deschis", **sintetizat procedural** (fără asset): glisare de frecvență **ascendentă 430 → 1180 Hz** (glide exponențial, perceptual „urcare") + un al doilea oscilator la cvintă (×1.5) mai încet pentru corp „synth" + un pic de armonica a 2‑a; atac rapid de 8ms, coadă exponențială; durată **380ms** (< 500ms). `AudioTrack` `MODE_STATIC`, `USAGE_ASSISTANCE_SONIFICATION`.
- **Nu se redă dacă `AudioManager.ringerMode != RINGER_MODE_NORMAL`** (silent / vibrație).
- Amplitudinea = `getStreamVolume(STREAM_MUSIC) / max`, plafonată la 0.72 → se adaptează la volumul sistemului.
- De-dup 250ms; rulează pe thread propriu; `AudioTrack` eliberat la marker.

### `modules/benson-overlay/android/.../BensonBubbleService.kt` — E3-1 + E3-2 (~+55 net)
- **E3-1 (banda):** `stateTv` **18sp bold**, gold mai deschis `#F2C94C`; `transcriptTv` **20sp bold** alb; ambele cu `setShadowLayer(5dp, 0, 2dp, #CC000000)` — **umbră ÎN PLUS de fundal**, contrast pe orice fundal. `maxWidth = 85% din ecran` − padding. Fundal pill `#F51E2B38` (~96% opac, era ~94%), bord gold `#99D4AF37` 1.5dp, colț 20dp, padding 16/12dp. Fără `alpha` pe container (era 0.97 — acum plin).
- **E3-2 (bula):** bula devine `FrameLayout` **transparent** (fill `Color.TRANSPARENT`, doar un inel gold slab `#59D4AF37` 1.5dp ca țintă de atingere), mărită la **64dp**, cu un `BubbleDotsView` `MATCH_PARENT` înăuntru. Logica de drag/tap rămâne identică, pe container. `ACTION_BUBBLE_MOTION` + `EXTRA_MOTION` → `bubbleDots?.applyMotion(...)`; starea e reținută (`bubbleMotion`) ca o bulă re-creată să pornească în starea corectă. `removeBubble()` oprește animatorul.

### `modules/benson-overlay/android/.../BensonOverlayModule.kt` — (~+18)
- `Function("setBubbleMotion") { motion: String -> startService(ACTION_BUBBLE_MOTION) }`
- `Function("playWakeSound") { WakeSound.play(context) }`

### `modules/benson-overlay/index.js` (~+10) + `index.d.ts` (~+7)
- `export function setBubbleMotion(motion)` · `export function playWakeSound()` + tipuri.

### `app/index.tsx` — E3-1 + E3-2 + E3-3 wiring (~+55)
- **E3-1:** `E2_STATE_LABEL` → **MAJUSCULE**: `ASCULT` / `AM ÎNȚELES` / `EXECUT` / `GATA`. `E3_BAND_LINGER_MS = 6000` + rescriere `pushBubbleBand`: la `GATA` se armă un timer de 6s; tranziția automată la `IDLE` (după `STATE_DONE_CLEAR_MS`=4s) **nu** ascunde banda cât timerul rulează → banda stă **≥5s după „GATA"**. `hideBubbleBandNow()` / `clearBandHideTimer()` + curățare în cleanup-ul useEffect-ului și în `enterSilentMode`. Revenirea în prim-plan anulează lingerul și ascunde banda.
- **E3-2:** `pushBubbleMotion(state)` — `LISTENING → 'listening'`, `EXECUTING → 'executing'`, restul `'static'`; apelat din `setBensonState` lângă `pushBubbleBand`. Gated pe `serviceActiveRef.current`.
- **E3-3:** `WAKE_SOUND_ENABLED = true` + `playListenStartSound()` (respectă `mutedRef` / `silencedRef`; nativul respectă silent-mode). Apelat la cele **3 puncte `LISTEN_STARTED source=user_open`** (atingere bulă, `AppState → active` conv mode, useEffect `phase='chat'`) și în `handleWakeDetected` (`source=wake_word`, log adăugat; `WAKE_SOUND_ENABLED=false` → revine la `playWakeChime()`). NU se redă la self-heal / re-armare conv mode.
- Import `setBubbleMotion`, `playWakeSound` din `benson-overlay`.

---

## 3. Detaliu pe sub-task

### 3.1 E3-1 — banda
Font ≥18sp bold ✓ (18 stare / 20 transcriere). Lățime 85% ✓. Două rânduri: stare gold `#F2C94C`, transcriere alb ✓. Contrast pe orice fundal ✓ — **umbră puternică + fundal `#F5` (~96% opac)**. Rămâne ≥5s după „GATA" ✓ (`E3_BAND_LINGER_MS = 6000`).

### 3.2 E3-2 — bula
Transparentă (fill clar, inel gold slab) ✓. Trei puncte care se rotesc **invers acelor de ceas** cât BENSON `LISTENING` ✓. Statice când nu ascultă ✓. Pulsează la `EXECUTING` ✓. Culori gold + verde‑închis (`#D4AF37` / `#1F5A45`), consistente cu `RingArcView` și sigiliul din aplicație ✓.

### 3.3 E3-3 — sunetul: **PROCEDURAL, fără fișier**
Nu există `assets/sounds/benson_wake.ogg`. Sunetul e sintetizat la runtime în `WakeSound.kt` (`AudioTrack` + PCM generat: glide 430→1180 Hz + cvintă + armonica a 2-a, atac 8ms, coadă exponențială, 380ms). Motiv: zero lag de decodare (spre deosebire de un OGG prin `expo-av` — vezi comentariul „warm the chime" din E1), zero dependență nouă, tot codul stă în `modules/benson-overlay/**`. Se redă la fiecare `LISTEN_STARTED` inițiat de utilizator; tace pe silent; volum după sistem.

---

## 4. Constante de revert

| Constantă | Fișier | Revert |
|---|---|---|
| `WAKE_SOUND_ENABLED` | `app/index.tsx` | `false` → fără sunet nou; wake word revine la `playWakeChime()`, atingerea/deschiderea nu mai sună |
| `E3_BAND_LINGER_MS` | `app/index.tsx` | `0` → banda dispare odată cu tranziția la IDLE (ca în E2) |
| `E2_BUBBLE_BAND` | `app/index.tsx` | `false` → banda nu mai apare deloc |
| *(bula transparentă + puncte)* | `BensonBubbleService.addBubble` / `BubbleDotsView` | pune `setColor(Color.parseColor("#1E2B38"))` + stroke 2dp `#D4AF37` înapoi în `addBubble` și scoate `addView(dots…)` |

---

## 5. Acceptare pe dispozitiv — de rulat de tine

1. **Deschizi BENSON → sunet SF → trei puncte se rotesc în bulă transparentă.** (Sunetul: doar dacă telefonul NU e pe silent și volumul media > 0.)
2. **Spui o comandă → banda apare lângă bulă, text mare, lizibil** — „AM ÎNȚELES" gold + transcrierea albă, 18–20sp bold, umbră; rămâne „GATA" ≥5s după ce s-a terminat.
3. **Telefonul pe silent → sunetul nu se redă** (banda + punctele funcționează normal).
4. Punctele: se rotesc invers‑ceas cât ascultă; pulsează cât execută; stau în rest.
