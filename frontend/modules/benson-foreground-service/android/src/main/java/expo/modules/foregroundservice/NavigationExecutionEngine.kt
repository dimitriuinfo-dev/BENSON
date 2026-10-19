package expo.modules.foregroundservice

// BENSON NEXT — leaga SURVIVOR (NavigationMissionTrace.kt, deja construit, testat) de EXECUTOR
// (interfata ExecutionEngine din BensonLiveContracts.kt). Primul EXECUTOR real al BENSON NEXT:
// construieste misiunea de navigatie, cere observatia prim-planului (functie injectata — ramane
// afara, legata de AccessibilityService intr-o runda viitoare, NU aici) si clasifica rezultatul
// prin Verification Engine-ul deja scris. Nu reimplementeaza nimic din SURVIVOR, doar il consuma.

class NavigationExecutionEngine(
  private val expectedPackagesFor: (String) -> Set<String>, // actiune -> pachete acceptate (din AKA)
  private val observeForegroundPackage: (pollAttempt: Int) -> String?, // I/O reala, injectata
  private val maxPolls: Int = 5,
) : ExecutionEngine {

  override fun execute(request: ExecutionRequest): ExecutionOutcome {
    if (request.action != "NAVIGATE") {
      return ExecutionOutcome.Failed("unsupported_action:${request.action}")
    }
    val expected = expectedPackagesFor(request.action)
    if (expected.isEmpty()) return ExecutionOutcome.Failed("no_expected_package")

    var attempt = 0
    while (true) {
      val observed = observeForegroundPackage(attempt)
      val outcome = classifyLaunchVerification(expected, observed, attempt, maxPolls)
      if (outcome !is ExecutionOutcome.Unverified) return outcome
      attempt += 1
    }
  }
}
