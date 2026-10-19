# BENSON NEXT — backlog (document de referință, Master Build)

Fiecare sarcină: stare (FĂCUT/ÎN LUCRU/URMEAZĂ/BLOCAT), dovada, fișierele atinse. Nu duplica —
dacă o capacitate există deja în LEGACY funcțional, NEXT o reutilizează prin contract, nu o
reinventează (ex. URI-urile Waze, vocabularul WhatsApp, pragurile HEED rămân sursa de adevăr).

## FĂCUT (376/376 teste, zero DEVICE_PASS)
- [x] D0 — contracte comune
- [x] BENSON LIVE — orchestrator unic, flux TEXT→LIVE→AKA→EXECUTOR→VERIFIED
- [x] AKA — cablat la StaffRegistry real (provenență ≠ verificare) + descoperire dinamică (P1.6 respectat)
- [x] MEMORY — store criptat + rezolvare multilingvă (ro/de → aceeași locație)
- [x] TRAVEL — plan misiune + executor navigație cu SURVIVOR
- [x] COMMS — dry-run WhatsApp (apel/video), test RO/DE identice pe VIDEO_CALL, canal neschimbat
- [x] SHOPPING — al treilea domeniu prin același orchestrator (dry-run, fără plată posibilă)
- [x] SOS (parțial) — contracte EmergencyProtocol, SOS WATCH, trigger gate, model KWS AJUTOR antrenat
- [x] VOICE — decizie ONE VOICE + client HTTP real OpenAI TTS (cod, neinvocat)
- [x] EXECUTOR Android real #1 — Calculator, verificare reală prin serviciul de accesibilitate existent (cod, netestat pe telefon)
- [x] ModelProvider real (OpenAI, cod HTTP + parsare testată; neinvocat cu cheie reală)
- [x] Pipeline conceptual HEED→STT→LIVE→EXECUTOR→RESPONSE, LEGEA TĂCERII respectată la intrare
- [x] Anti-confuzie simulat/real (EvidenceLabels)
- [x] LIVE — flux explicit de confirmare opt-in (`MissionResult.AwaitingConfirmation` +
      `confirmPendingMission`, implicit dezactivat), modelează "da/nu" pentru acțiuni cu
      `requiresConfirmation` fără a atinge ConfirmationGate-ul real din LEGACY

## BLOCAT — cere autorizare explicită, nu se face autonom
- [ ] **SOS local real** (cablarea modelului KWS „AJUTOR" ca a doua inferență în `HeedWakeWord.kt`)
      — fișier protejat explicit, cere acordul proprietarului per rundă, + model încă nevalidat pe
      voce reală/umană (vezi `.benson-training/`, protocolul de validare pregătit, neexecutat).
- [ ] **Invocarea reală a OpenAI TTS/ModelProvider** cu o cheie reală — cost nou neautorizat explicit.
- [ ] **Integrarea reală în `BensonForegroundService.kt`** (wiring efectiv al LIVE NEXT peste calea
      vocală vie) — fișier protejat, deja cu LEGEA TĂCERII necomisă + fix-uri TTS/anti-fals-trezire
      necomise, în așteptarea confirmării tale pe telefon. O singură schimbare odată, nu paralel.
- [ ] **Orice DEVICE_PASS** — acest mediu nu are `adb`/dispozitiv conectat.
- [ ] **Instalare APK, ștergere date, apeluri/mesaje/plăți reale** — în afara autorității BUILD AUTOPILOT.

## URMEAZĂ — ce rămâne, neblocat, dar neînceput (viitoarea sesiune poate continua direct de aici)
- [ ] TRAVEL — rută/trafic real (dincolo de navigația simplă) — neînceput.
- [ ] Extindere SOS Watch către un modul Wear OS real (dependență nouă, decizie separată — menționat deja ca atare).

## Notă despre reluare
Orice sesiune viitoare: citește `BENSON_NEXT_BUILD_STATE.md` întâi. Elementele BLOCAT de mai sus
NU se reîncearcă fără autorizarea specifică cerută — nu sunt "sarcini uitate", sunt limite reale
ale mediului/autorității curente.
