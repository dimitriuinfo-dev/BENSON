package expo.modules.foregroundservice

// FAST SYMBIOSIS (21.10.2026, "ADDENDUM FINAL V2") — gate PUR, testabil, pentru decizia "poate
// aceasta intentie sa evite complet apelul OpenAI, pentru ca AKA are deja o dovada
// DEVICE_VERIFIED/RUNTIME_VERIFIED, neambigua, pentru EXACT pasii necesari?" Nu e un orchestrator
// nou — e o functie de decizie, consultata de un matcher nativ, exact ca AgentVerificationGate.kt/
// SurvivorDiagnosis.kt. Conditiile #1 (intentie neambigua) si #2 (parametri compleți) se verifica
// la PARSARE (FastPathCalculator.kt), inainte de a ajunge aici; #4 (versiune/ruta valide), #5
// (fara conflict de misiune) si #6 (confirmari respectate) raman responsabilitatea apelantului
// (StaffMember real, agentFingerLock, ConfirmationGate existent); #7 (efect verificabil) e
// garantat de SURVIVOR la executie, nu aici. Aceasta functie verifica STRICT conditia #3:
// "exista o SINGURA ruta eligibila VERIFIED pentru TOTI pasii necesari?"
enum class FastPathVerdict { ELIGIBLE, INCOMPLETE, NOT_VERIFIED }

// Regula explicita de ambiguitate din mandat: o singura capabilitate care NU e exact VERIFIED
// (fie REVERIFY, fie KNOWN, fie absenta) face toata ruta ambigua/incompleta — nu doar "mai putin
// sigura". Nu exista un prag de scor (ordinul interzice explicit un prag artificial de tip 0.92).
internal fun evaluateFastPathEligibility(requiredCapLevels: List<CapLevel?>): FastPathVerdict = when {
  requiredCapLevels.isEmpty() -> FastPathVerdict.INCOMPLETE
  requiredCapLevels.any { it == null } -> FastPathVerdict.NOT_VERIFIED
  requiredCapLevels.all { it == CapLevel.VERIFIED } -> FastPathVerdict.ELIGIBLE
  else -> FastPathVerdict.NOT_VERIFIED
}

// Circuit breaker (sectiunea 4 a mandatului): "doua esecuri CONSECUTIVE confirmate de SURVIVOR
// pentru aceeasi ruta si context" dezactiveaza ruta temporar. Pur — apelantul tine contorul
// (per-ruta, in memorie, reset la succes); aceasta functie decide doar pragul.
fun shouldDisableFastPathRoute(consecutiveConfirmedFailures: Int): Boolean =
  consecutiveConfirmedFailures >= 2

// Sectiunea 6 a mandatului — un rezultat care ajunge DUPA ce misiunea a fost deja anulata nu se
// aplica automat unei misiuni noi/altui context: se raporteaza explicit, separat.
data class FastPathStepResult(
  val label: String,
  val ok: Boolean,
  val cancelledBeforeSend: Boolean = false,
  val lateAfterCancel: Boolean = false,
)

// Un pas ale carui rezultate ajung dupa ce s-a cerut anularea, dar care a fost DEJA trimis catre
// EXECUTOR (ireversibil — un tap deja transmis), trebuie marcat LATE_AFTER_CANCEL, nu ignorat si
// nu aplicat ca succes normal al misiunii curente.
fun isLateAfterCancel(alreadySentToExecutor: Boolean, cancelRequestedBeforeResultArrived: Boolean): Boolean =
  alreadySentToExecutor && cancelRequestedBeforeResultArrived
