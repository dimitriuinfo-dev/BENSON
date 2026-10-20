package expo.modules.foregroundservice

// BENSON NEXT (19.10.2026, "verificare obligatorie PRIN COD, nu doar systemPrompt") — gard de
// stare, pur, testabil, care impune doctrina 9 in cod: dupa o apasare (tap_element) care poate
// produce un rezultat citibil (un calcul, un scor, un total), agentul NU poate incheia tura — nici
// prin instrumentul «say», nici printr-un raspuns conversational fara instrument — decat dupa ce a
// mai chemat o data «observe_screen» (sau, pe drumul deja existent media/radio, «verify_playing»,
// care e deja propriul verificator onest, dovedit). Fara acest gard, o instructiune de prompt era
// singura bariera — niciodata garantata, pentru ca modelul putea "calcula singur" un rezultat si
// sa-l rosteasca fara sa fi citit vreodata ecranul.
//
// Scop ingust, deliberat: `open_app` NU e tratat ca actiune care cere verificare aici — tool-ul
// insusi face deja verificare SURVIVOR reala (FIX_AGENT_OPEN_APP_HONESTY_1) inainte sa raporteze
// succes, deci un "say" imediat dupa o deschidere simpla ramane neschimbat (fara frictiune noua pe
// drumul deja functional). Doar `tap_element` (care NU stie ce valoare a produs apasarea) declanseaza
// gardul.

enum class AgentStepKind { ACTION, OBSERVE, OTHER }

fun agentStepKindFor(toolName: String?): AgentStepKind = when (toolName) {
  // press_button (19.10.2026, Semantic UI Resolver) e si el o actiune ce poate schimba ecranul —
  // aceleasi garduri de verificare se aplica, indiferent daca apasarea vine prin index sau semantic.
  BensonAgentTools.TAP_ELEMENT, BensonAgentTools.PRESS_BUTTON -> AgentStepKind.ACTION
  BensonAgentTools.OBSERVE_SCREEN, BensonAgentTools.VERIFY_PLAYING -> AgentStepKind.OBSERVE
  else -> AgentStepKind.OTHER
}

data class AgentVerificationGateState(
  val lastActionStep: Int = -1,
  val lastObserveAfterActionStep: Int = -1,
  val hasObservedAtLeastOnce: Boolean = false,
)

fun advanceVerificationGate(state: AgentVerificationGateState, stepIndex: Int, kind: AgentStepKind): AgentVerificationGateState =
  when (kind) {
    AgentStepKind.ACTION -> state.copy(lastActionStep = stepIndex)
    AgentStepKind.OBSERVE -> state.copy(
      lastObserveAfterActionStep = if (state.lastActionStep >= 0) stepIndex else state.lastObserveAfterActionStep,
      hasObservedAtLeastOnce = true,
    )
    AgentStepKind.OTHER -> state
  }

// FIX_AGENT_TAP_WITHOUT_OBSERVE_1 (19.10.2026, DOVADA PE TELEFON) — CAUZA REALA a unei a treia
// anomalii: dupa open_app, modelul a cerut tap_element DIRECT, fara sa fi chemat observe_screen
// vreodata in aceasta rulare — un index ghicit (niciun digest real care sa-l justifice). Contractul
// tapDigestElement l-a respins corect si rapid (ms=1), dar NIMIC nu oprea INCERCAREA insasi — exact
// "apasari ghicite", interzis explicit. Gard al treilea, ortogonal: tap_element nu se executa
// niciodata inainte de PRIMUL observe_screen al rularii.
fun isTapAllowedNow(state: AgentVerificationGateState): Boolean = state.hasObservedAtLeastOnce

const val TAP_WITHOUT_OBSERVE_REJECTION_MESSAGE =
  "Nu ai observat încă ecranul în această sarcină. Cheamă «observe_screen» înainte să încerci " +
    "să apeși ceva — un index fără o observare reală e o apăsare ghicită."

/** Poate tura sa se incheie ACUM (prin "say" sau printr-un raspuns fara instrument)? */
fun isTurnEndAllowedNow(state: AgentVerificationGateState): Boolean =
  state.lastActionStep < 0 || state.lastObserveAfterActionStep > state.lastActionStep

const val VERIFICATION_GATE_REJECTION_MESSAGE =
  "Ai efectuat o apăsare care poate schimba ce se vede pe ecran, dar nu ai verificat rezultatul. " +
    "Cheamă din nou «observe_screen» și citește ce arată ecranul înainte să răspunzi."

// FIX_AGENT_COMPOUND_OPEN_NO_ACTION_1 (19.10.2026, DOVADA PE TELEFON) — gard al DOILEA, ortogonal:
// pentru comanda reala "deschide Calculatorul si calculeaza 27x14", agentul a deschis aplicatia
// (open_app, verificat SURVIVOR), a chemat observe_screen o data, apoi a raspuns direct "27 ori 14
// este 378" FARA sa apese vreun buton — exact calculul facut de LLM, niciodata verificat pe ecran
// (bubble: BUBBLE state="27 ori 14 este 378." dupa doar step=1 open_app + step=2 observe_screen).
// Gardul de mai sus (isTurnEndAllowedNow) nu prindea asta: lastActionStep ramanea -1 (niciun
// tap_element nu avusese loc), deci starea initiala "nimic de verificat inca" il lasa sa treaca.
//
// Scop ingust, deliberat: regula se aplica NUMAI cand comanda originala era deja marcata drept
// COMPUSA de parserul determinist (COMPOUND_COMMAND_PATTERN — "si"/"apoi"/"dupa aia", acelasi
// tipar care a si trimis comanda spre agent in loc de tryNativeOpenApp) SI open_app a reusit. O
// comanda simpla "deschide Calculatorul" (fara "si ...") NU e compusa — nu se cere nicio apasare,
// gardul nu se activeaza, nu forteaza apasari ghicite pe un ecran unde nu era nevoie de nimic.
fun isCompoundOpenWithoutActionBlocked(
  commandIsCompound: Boolean,
  openAppSucceeded: Boolean,
  lastActionStep: Int,
): Boolean = commandIsCompound && openAppSucceeded && lastActionStep < 0

const val COMPOUND_OPEN_REJECTION_MESSAGE =
  "Ai deschis aplicația, dar cererea cerea mai mult de atât. Apasă ce trebuie în interfață " +
    "înainte să răspunzi — nu calcula sau presupune singur rezultatul."

// FIX_AGENT_CLAIMED_NUMBER_NOT_OBSERVED_1 (19.10.2026, S4, DOVADA PE TELEFON) — gard al PATRULEA,
// ortogonal de celelalte trei: pentru comanda reala "deschide Calculatorul si calculeaza
// 27 inmultit cu 14", gardurile 1-3 au functionat corect (apasare -> observare, inainte de orice
// declaratie) — dar agentul a rostit totusi "Master, rezultatul este 126.", desi ultima observare
// REALA a ecranului (capturata in log, dovada noua din instrumentatia S4) arata "8.951", fara nicio
// legatura cu "126". Gardurile 1-3 verifica SECVENTA (actiune->observare), niciodata daca VALOAREA
// rostita chiar a fost VAZUTA. Acesta verifica exact asta — generic, nu specific Calculatorului:
// orice numar rostit ca rezultat final trebuie sa apara, ca text, in ultima observare reala a
// ecranului (orice aplicatie cu continut text vizibil, nu doar calculatoare).
//
// Scop ingust, deliberat, ca si gardul 2: se aplica NUMAI pentru o comanda deja marcata COMPUSA —
// o conversatie libera ("cati ani a murit Stefan cel Mare?") poate rosti legitim numere care nu vin
// de pe ecranul curent. Limitare onesta, documentata: digestul observat poate fi trunchiat la
// afisarea catre model (`toolResult` cap=900) — un numar real, dar aflat dupa trunchiere, ar putea
// fi semnalat gresit ca "nevazut". Nu exista aici nicio presupunere despre POZITIA unui buton.
//
// FIX_CLAIMED_NUMBER_BOUNDS_FALSE_NEGATIVE_1 (19.10.2026, S4, a patra rulare reala, DOVADA PE
// TELEFON) — CAUZA REALA a unei a doua declaratii false care a trecut de acest gard ("27 inmultit
// cu 14 este 378.", desi apasarile reale au fost "Wissenschaftlich"/"löschen"/"!"/"Prozent-Zeichen"
// — nicio cifra): `ScreenDigest.render()` adauga la finalul fiecarui rand coordonatele elementului
// ("... 40,900,500,980"), iar cautarea naiva `contains("378")` putea gasi acele cifre INTAMPLATOR
// IN COORDONATE (un ecran de 1080x2414 pixeli produce des subsiruri de 3 cifre ca "378" in pozitii
// x/y), nu in continutul real al unui element. Coordonatele se elimina explicit inainte de cautare
// — nicio presupunere despre POZITIA unui buton, doar excluderea datelor de pozitionare din
// comparatia de CONTINUT.
private val BOUNDS_PATTERN = Regex("""\d+,\d+,\d+,\d+""")

fun claimedNumberMissingFromLastObservation(
  commandIsCompound: Boolean,
  finalText: String,
  lastObservedScreenText: String?,
): Boolean {
  if (!commandIsCompound) return false
  if (lastObservedScreenText == null) return false // fara o observare de comparat, nu se contrazice nimic
  val claimedNumbers = Regex("\\d+(?:[.,]\\d+)?").findAll(finalText).map { it.value }.toList()
  if (claimedNumbers.isEmpty()) return false // niciun numar rostit — nimic de verificat pe acest gard
  val contentOnly = lastObservedScreenText.replace(BOUNDS_PATTERN, "")
  return claimedNumbers.none { contentOnly.contains(it) }
}

const val CLAIMED_NUMBER_NOT_OBSERVED_REJECTION_MESSAGE =
  "Numărul pe care vrei să-l spui nu apare în ultima observare reală a ecranului. Cheamă din nou " +
    "«observe_screen» și citește valoarea AFIȘATĂ, literal — nu calcula sau presupune singur " +
    "rezultatul."
