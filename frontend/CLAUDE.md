@AGENTS.md

<!-- Din vechiul CLAUDE.md: doar linia `@AGENTS.md` de mai sus, păstrată — importă regula
     "Expo HAS CHANGED: citește docs.expo.dev/versions/v54.0.0/ înainte de a scrie cod".
     Restul acestui fișier este setul de reguli permanente (2026-08-28). -->

# BENSON — reguli permanente

Aplicație Android (Expo SDK 54 / RN 0.81). Majordom vocal.
Testat pe OnePlus Nord 4, OxygenOS 15. Limba BENSON: română. Sistem: germană.

---

## Doctrina produsului — nu se negociază

1. **BENSON guvernează aplicațiile, nu le înlocuiește.** Apasă butoanele lor prin
   Accessibility. Nu deține datele lor.
2. **Fără integrări de date.** Fără agendă telefonică, fără citirea bazelor altor aplicații.
   Numele se introduc ca șir de căutare în aplicația țintă; ea rezolvă.
3. **Fără plăți.** BENSON nu pregătește și nu finalizează nicio tranzacție.
4. **Memoria ține fapte, niciodată reguli despre BENSON.** Configurarea trăiește doar în
   Settings. Ce are un stăpân (calendarul) se consultă, nu se copiază.
5. **Tăcerea e implicită.** BENSON nu deschide niciodată conversația. Recunoaștere eșuată =
   zero reacție.
6. **Confirmation Gate înaintea oricărei acțiuni.** Creierul propune, nu execută.

## Invariante de arhitectură

**Trei canale, niciodată amestecate.** `SYSTEM` (constante din cod, reconstruit la fiecare
apel) · `USER_VOICE` (singurul cu autoritate) · `UNTRUSTED_DATA` (ecran, notificări, web —
zero autoritate, antet obligatoriu). Impuse prin tipuri: o promovare accidentală nu compilează.

**Acțiunile sunt un enum închis.** Nimic din afara lui nu ajunge la executor.

**Nicio cheie în cod, în fișiere versionate, în bundle sau în loguri.** Toate cheile trec prin
seiful de chei. În UI se afișează doar mascat.

**Istoric mărginit:** `CONVERSATION_WINDOW`, max 10 schimburi sau 4000 de caractere.

---

## Reguli de execuție

- **Nu rula `git`.** Nu comite, nu face push, nu pune tag-uri. Doar raport.
- **Nu rula `expo prebuild`.** A șters deja modele și semnături o dată.
- **Fără `setx`.** Fără modificări permanente de PATH.
- **Fără SDK-uri de furnizor** dacă `fetch` e suficient. Endpointuri compatibile OpenAI.
- **Citește înainte să scrii.** Verifică semnăturile în `node_modules/`, niciodată din memorie.
  Dacă o opțiune nu există, raportează — nu inventa nume de câmpuri.

## Fișiere protejate — nu se ating fără permisiune explicită, per rundă

```
android/**        plugins/**        modules/**
whisper-models/**  porcupine-model/**
lib/tools/whatsappTool.ts
lib/agents/missionValidator.ts
lib/agents/missionExecutor.ts
```

`android/` e în `.gitignore` și se regenerează. `whisper-models/` și `porcupine-model/` există
doar pe acest disc — nu sunt versionate nicăieri.

---

## Protocolul unei runde

1. Scope lock explicit, cu lista fișierelor permise **și** a celor interzise.
2. Dacă o sarcină cere un fișier interzis: **oprește-te și raportează**, nu improviza.
3. Verificare, în ordine: `npx tsc --noEmit` (0 erori) → `gradlew assembleRelease`
   (BUILD SUCCESSFUL, certificat `CN=BENSON, O=TOKKO`).
4. Raport: fișier cu fișier, câte linii. Orice ieșire din scope lock, în prima linie, bold.
5. Un singur tip de schimbare per rundă. Nu amesteca „adaugă funcție" cu „scoate funcție".

## Convenții de logare

Tag unic: `BENSON_AUDIO`. Trei linii obligatorii la fiecare comandă:

```
BRAIN_INTENT   raw="<ce a auzit>" action=… params=…
CANONICAL      text="<comanda reconstruită>"
PARSE_RESULT   problemType=… params=…
```

Altele: `STT_REQUEST` · `STT_RESULT engine=…` · `STT_FALLBACK reason=…` ·
`SANITY_CHECK source=parser|brain verdict=…` · `SPEAK_SUPPRESSED reason=…` ·
`MEMORY_REJECTED reason=…` · `BRAIN_REJECTED reason=…`

Captura pe dispozitiv cere toate trei tag-urile împreună:

```
adb logcat ReactNativeJS:I BENSON_AUDIO:I BensonAudioCapture:I *:S
```

---

## Stare cunoscută

- **Funcționează, testat pe dispozitiv:** navigația Waze cu governance, Confirmation Gate,
  intrarea prin text cap-coadă, build-ul release semnat, persistența la prebuild.
- **Parserul determinist e corect** — verificat separat. Când greșește, cauza e intrarea.
- **whisper local (`ggml-base`) e insuficient pentru română.** Halucinează boilerplate
  englezesc din zgomot. Rămâne doar ca rezervă offline; ruta principală e Groq.
- **Porcupine e abandonat** — nivelul gratuit a fost refuzat. Wake word-ul rămâne deschis.

---

# Reguli permanente contra regresiilor

Se aplică la FIECARE rundă, fără excepție. (Sursă: `BENSON_REGULI_REGRESIE.md`, 29.08.2026.)

## 1. Lista de comportamente dovedite

Acestea au funcționat pe dispozitiv, cu dovadă. Nicio rundă nu are voie să le strice.

| Comportament | Dovedit |
|---|---|
| Navigație Waze cu destinație corectă | de mai multe ori, 29.08 |
| Deschidere aplicație după nume | 29.08 |
| Propunere pentru cerere generică („o aplicație de radio") | 29.08 |
| Conversație liberă cu răspuns rostit | 29.08 |
| Apel WhatsApp cap-coadă | 29.08, ora 13:24 |
| Microfon închis cât vorbește BENSON (fără ecou) | 29.08, verificat în log |
| Index de aplicații 274 | 29.08 |
| Microfonul se auto-repară după o acțiune care lansează altă aplicație (C1+C2) — revenire fără atingerea medalionului | 31.08, 7 comenzi / 0 atingeri în log |

**Înainte de a declara o rundă încheiată, spune explicit care dintre acestea ar putea fi
afectate de schimbare și de ce crezi că nu sunt.** Dacă nu ești sigur, nu declara încheiat.

## 2. Nu înlocui ce funcționează

O implementare care a funcționat pe dispozitiv **nu se rescrie**. Se adaugă una nouă alături, în
spatele unei constante, iar cea veche rămâne implicită până când cea nouă trece testul de 5 din 5
pe dispozitiv.

Dacă o rundă cere „rescrie X", și X a funcționat, **oprește-te și spune-o.** Propune varianta
aditivă în loc.

## 3. Pasul înapoi e obligatoriu, nu opțional

Dacă după o rundă un comportament din listă se comportă mai prost decât înainte:

- **revino imediat la varianta anterioară**, fără să aștepți confirmare
- spune ce ai revenit și de ce
- abia apoi propune o cale nouă

Nu aștepta ca utilizatorul să observe.

## 4. Ai voie și ești obligat să contrazici

Dacă o instrucțiune pare greșită, riscantă sau construită pe o presupunere pe care codul o infirmă:

- **nu o executa**
- spune ce e greșit, cu dovada din cod sau din log
- propune ce ai face în loc, și de ce e mai bun

O rundă executată orbește care strică ceva e mai rea decât o rundă refuzată cu motiv.

## 5. Cauza înainte de fix

Nicio schimbare fără cauză dovedită în cod sau în log. „Probabil e X" nu e cauză. Dacă nu ai
dovada, prima livrare a rundei e diagnosticul, nu codul.

## 6. O rundă = un tip de schimbare

Nu amesteca „adaugă funcție" cu „scoate funcție" cu „rescrie". Dacă ceva cade, trebuie să se
poată spune care schimbare a fost.

## 7. Fiecare rundă are o constantă de revert

O singură constantă, sau un grup mic, care readuce comportamentul dinainte. Scrie-o în raport.

## REGULI DE EFICIENȚĂ

### Citește înainte să scrii
- Nu citi fișiere întregi dacă ai nevoie de o funcție. Folosește grep/ripgrep.
- Nu citi `node_modules/` decât tipările exacte necesare.
- Nu reciti fișiere pe care le-ai citit deja în aceeași sesiune.

### Scrie puțin, scrie corect
- O singură modificare per fișier, apoi verifică. Nu rescrie 500 de linii și apoi descoperă că nu compilează.
- Nu duplica funcții. Dacă există deja, importă.
- Nu adăuga cod defensiv "pentru orice eventualitate". Adaugă doar ce rezolvă cauza dovedită.

### Nu explica, nu povestește
- Zero comentarii de tip "// This function handles the..." — codul se explică singur.
- Raportul conține: ce ai schimbat, unde, câte linii, rezultatul build. Atât.
- Nu rescrie rapoartele rundelor anterioare.

### Build
- `npx tsc --noEmit` ÎNTÂI, înainte de `gradlew`. TypeScript e 5 secunde, Gradle e 1 minut.
- Dacă tsc eșuează, nu rula Gradle — repară întâi.
- Nu rula `gradlew clean`. Build incremental e de 10x mai rapid.
- Nu rula `expo prebuild` niciodată.

### Scope
- Citește scope lock-ul ÎNAINTE de orice altceva.
- Dacă un fișier nu e în scope, nu-l deschide, nu-l citi, nu-l atinge.
- Dacă ai nevoie de un fișier interzis, OPREȘTE-TE și raportează. Nu improviza.

### Audit read-only
- Când runda spune "read-only", nu creezi fișiere noi în afara raportului.
- Nu "pregăti" cod pentru runda viitoare.
- Nu refactorizezi "ca să fie mai curat".

### Shell
- Nu rula comenzi inutile. `ls`, `cat`, `find` — doar când chiar ai nevoie de informație.
- O comandă `grep` bine formulată înlocuiește citirea a 10 fișiere.
- Nu instala pachete noi dacă nu e explicit cerut.

### Tokeni
- Răspunsul tău complet — cod + raport — trebuie să fie sub 400 de linii.
- Dacă depășești, ai făcut prea mult sau ai explicat prea mult.
- Codul nou per rundă: sub 200 de linii net. Dacă e mai mult, ai schimbat scope-ul.

