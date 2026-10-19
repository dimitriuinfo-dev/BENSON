# BENSON NEXT — backlog (document de referință, Master Build)

Fiecare sarcină: stare (FĂCUT/ÎN LUCRU/URMEAZĂ/BLOCAT), dovada, fișierele atinse. Nu duplica —
dacă o capacitate există deja în LEGACY funcțional, NEXT o reutilizează prin contract, nu o
reinventează (ex. URI-urile Waze, vocabularul WhatsApp, pragurile HEED rămân sursa de adevăr).

## FĂCUT (349/349 teste, zero DEVICE_PASS)
- [x] D0 — contracte comune
- [x] BENSON LIVE — orchestrator unic, flux TEXT→LIVE→AKA→EXECUTOR→VERIFIED
- [x] AKA — cablat la StaffRegistry real, provenență separată de verificare
- [x] MEMORY — store criptat + rezolvare multilingvă (ro/de → aceeași locație)
- [x] TRAVEL — plan misiune + executor navigație cu SURVIVOR
- [x] COMMS — dry-run WhatsApp (dovedește generalizarea, nu e livrabil final)
- [x] SOS (parțial) — contracte EmergencyProtocol, SOS WATCH, trigger gate, model KWS AJUTOR antrenat
- [x] VOICE — decizie ONE VOICE (fără client HTTP real încă)
- [x] Anti-confuzie simulat/real (EvidenceLabels)

## ÎN LUCRU
- [ ] **EXECUTOR Android real #1** — acțiune nepericuloasă (Calculator, deja CAP_CALCULATE dovedit
      în LEGACY), verificare SURVIVOR reală prin `BensonAccessibilityService.currentForegroundPackageLive()`
      existent (NU un AccessibilityService nou). Cod compilat, **netestat pe telefon**.
- [ ] **OpenAI TTS real** — client HTTP (cod), estimare cost (reutilizează `OpenAiUsageMeter`),
      redare audio. **Apelul real NU se execută** fără autorizare explicită de cost (mandat §5).
- [ ] Test RO/DE aceeași intenție canonică pentru VIDEO_CALL (Hannah) — fără schimbare neautorizată de canal.

## URMEAZĂ (ordine propusă, nu fixă — se adaptează la ce se blochează)
- [ ] Pipeline HEED→STT→LIVE NEXT→EXECUTOR→RESPONSE (integrare conceptuală; integrarea REALĂ în
      `BensonForegroundService.kt` rămâne un pas separat, pe dispozitiv, cu LEGEA TĂCERII respectată)
- [ ] AKA — descoperire dinamică de aplicații noi (în limitele permisiunilor Android)
- [ ] SHOPPING — neînceput
- [ ] SOS local — cablare reală KWS „AJUTOR" în HEED (cere acordul explicit pentru fișierul protejat)
- [ ] Interpretare conversațională comună (un singur ModelProvider real, nu simulat)

## BLOCAJE REALE, cunoscute
- Fără `adb`/dispozitiv în acest mediu → zero DEVICE_PASS posibil din sesiune.
- Fără mecanism de rulare neasistată între sesiuni (vezi `BENSON_NEXT_BUILD_STATE.md`).
- OpenAI TTS real = cost nou, cere autorizare explicită înainte de prima execuție reală (nu doar cod).
- Integrarea reală în `BensonForegroundService.kt` (fișier protejat, deja cu LEGEA TĂCERII necomisă)
  rămâne secvențială, un flag/o rundă odată — nu paralel cu alte modificări pe același fișier.
