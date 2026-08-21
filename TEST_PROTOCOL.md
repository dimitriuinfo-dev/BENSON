# BENSON — Protocol de testare

Scop: un set fix de teste, cu criterii clare PASS/FAIL, ca să avansăm metodic în loc de haotic.
Fiecare test are: ce se face, ce marker `BENSON_AUDIO` dovedește succesul, ce înseamnă eșec real
vs. eroare de operare.

## Capcane operaționale (citește înainte de orice sesiune de testare)

Descoperite pe pielea noastră azi — ignorarea lor a produs ore de confuzie falsă:

1. **`adb screencap` fură focus-ul.** OnePlus arată un popup de previzualizare după orice captură
   de ecran (chiar și prin adb), care ia focusul de la BENSON. Nu trage o captură imediat după ce
   lansezi aplicația dacă vrei să verifici dacă a rămas în prim-plan — verifică întâi cu
   `dumpsys activity activities | grep ResumedActivity`, și dacă chiar trebuie o captură vizuală,
   așteaptă-te să fie nevoie de un al doilea `am start` după aceea.
2. **`am force-stop` NU simulează o omorâre reală de OxygenOS.** Un force-stop "curat" face Android
   să dezactiveze automat accesibilitatea aplicației (ca măsură de securitate) — o omorâre reală
   prin oom-killer (`OsenseKillAction`) NU face asta. Pentru testarea Guardian-ului, folosește
   `adb shell am kill <pid>`, nu force-stop. Pentru un simplu reload de cod, force-stop e ok, dar
   **restaurează accesibilitatea imediat după**:
   ```
   adb shell settings put secure enabled_accessibility_services com.benson.butler/expo.modules.accessibility.BensonAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```
3. **Tunelul `adb reverse tcp:8081 tcp:8081` poate cădea** după restart-uri de adb server /
   reconectări de device — fără el, telefonul nu poate încărca bundle-ul JS de la Metro și rămâne
   blocat pe splash screen alb la infinit (nu e crash, nu apare nimic în logcat ca eroare fatală).
   Verifică `adb reverse --list` dacă ecranul rămâne alb mai mult de câteva secunde.
4. **Un singur device conectat o dată.** Dacă apare un emulator alături de telefonul real,
   `expo run:android` poate întreba interactiv ce țintă alege și eșuează silențios în mod
   non-interactiv. Verifică `adb devices -l` înainte de orice build.
5. **Fișierele de captură logcat pot muri silențios** pe sesiuni lungi (mai ales după
   `adb kill-server`/`start-server`). Dacă un fișier de captură nu mai crește de peste un minut în
   timpul unui test activ, ignoră-l și pornește unul nou — nu trage concluzii din absența liniilor.
6. **O singură comandă per test, cu pauză înainte și după.** Comenzi date rapid una după alta se
   amestecă într-o singură captură STT confuză.

## Precondiții (verifică înainte de ORICE test)

```bash
adb devices -l                                          # exact un device, nu un emulator alături
adb -s <serial> shell dumpsys power | grep mWakefulness  # Awake, nu Dozing
adb -s <serial> reverse --list                           # tcp:8081 tcp:8081 prezent
curl -s http://localhost:8081/status                      # packager-status:running
adb -s <serial> shell settings get secure enabled_accessibility_services  # com.benson.butler/...
adb -s <serial> shell dumpsys activity activities | grep ResumedActivity  # com.benson.butler/.MainActivity
adb -s <serial> logcat -c && adb -s <serial> logcat -v time -s BENSON_AUDIO:V > captura.log &
```

Dacă oricare eșuează, repară-l ÎNAINTE de a cere un test vocal.

## Testele

### T1 — Comandă directă simplă (fără confirmare)
**Pași**: apasă microfonul manual, spune o comandă simplă fără acțiune reală ("cât e ceasul",
"unde mă aflu").
**PASS**: `TRANSCRIPT_ACCEPTED` cu text corect → `ORCHESTRATOR_HANDOFF_COMPLETED` → răspuns vorbit
auzit.
**FAIL real**: text greșit/gol în `TRANSCRIPT_ACCEPTED`, sau zero răspuns vorbit după handoff.

### T2 — Cuvântul de trezire ("Benson")
**Pași**: cu ecranul treaz, spune "Benson" de 5 ori, pauză ~3s între ele.
**PASS**: `WAKE_ACCEPTED`/`WAKE_DETECTED_NATIVE` de fiecare dată.
**FAIL real**: `STT_ERROR ... ERROR_NO_MATCH` constant fără nicio detecție din 5 încercări reale.

### T3 — Comandă cu confirmare (da/nu)
**Pași**: comandă care declanșează "Confirmi?" (ex. un apel WhatsApp), apoi răspunde "da".
**PASS**: prima comandă → `ORCHESTRATOR_HANDOFF_COMPLETED` cu `pendingTask`, TTS "Confirmi?",
apoi ASCULTĂ DIN NOU automat (fără să mai apeși microfonul), "da" → misiunea se termină.
**FAIL real**: nu reia ascultarea după întrebare (rămâne mut).

### T4 — Fără buclă de ecou
**Pași**: după orice comandă care produce un răspuns vorbit lung, așteaptă 30-60s fără să mai spui
nimic.
**PASS**: zero `ORCHESTRATOR_HANDOFF_REQUESTED` noi în acest interval (sau, dacă apare un
`TRANSCRIPT_ACCEPTED`, are `REJECTED_self_echo=true`).
**FAIL real**: aceeași misiune se re-execută fără input nou de la tine.

### T5 — Deschidere aplicație (Waze/WhatsApp/YouTube)
**Pași**: "Benson, deschide Waze" (sau altă comandă).
**PASS**: `ORCHESTRATOR_HANDOFF_COMPLETED handled=true` ȘI aplicația rămâne vizibil deschisă
(verifică `dumpsys activity activities | grep ResumedActivity` arată pachetul țintă, nu
`com.benson.butler`, la >3 secunde după comandă).
**FAIL real**: se deschide și se închide singură în <2s, sau rămâne pe ecranul BENSON.

### T6 — Închidere aplicație
**Pași**: cu Waze (sau altă aplicație) deschisă, "Benson, închide Waze".
**PASS**: `TRANSCRIPT_ACCEPTED` cu text corect → BENSON revine în prim-plan.
**FAIL real**: comanda nu e recunoscută (text greșit în transcript) sau nu se întâmplă nimic.

### T7 — Guardian: recuperare după omorâre reală
**Pași**: `adb shell am kill <pid>` (NU force-stop) cu ecranul stins.
**PASS**: procesul reapare autonom (`pidof` din nou nenul) în maxim ~60-90s, TTS "BENSON a
revenit online" auzit.
**FAIL real**: procesul rămâne mort peste 2 minute fără intervenție manuală.

## Raportare

Pentru fiecare test: `T<n>: PASS` sau `T<n>: FAIL — <motiv scurt + linia decisivă din log>`.
Nu se raportează "funcționează" fără cel puțin un marker `BENSON_AUDIO` citat ca dovadă.
