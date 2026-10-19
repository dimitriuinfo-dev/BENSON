package expo.modules.foregroundservice

// BENSON NEXT — al treilea domeniu (SHOPPING) prin ACELASI BensonLiveOrchestrator — zero
// orchestrator nou, zero ramificatie speciala in LIVE. DRY-RUN DELIBERAT: nu deschide nicio
// aplicatie reala, nu cauta niciun produs real — demonstreaza doar generalizarea, la fel ca
// WhatsAppDryRunExecutionEngine pentru COMUNICARE. Doctrina 3 (fara plati) ramane absoluta:
// acest executor nu poate produce NICIODATA o actiune de cumparare/plata — doar SEARCH_PRODUCTS.
class ShoppingDryRunExecutionEngine(private val simulateSuccess: Boolean = true) : ExecutionEngine {
  override fun execute(request: ExecutionRequest): ExecutionOutcome {
    if (request.action != StaffRegistryCore.CAP_SEARCH_PRODUCTS) {
      return ExecutionOutcome.Failed("unsupported_action:${request.action}")
    }
    return if (simulateSuccess) {
      ExecutionOutcome.Success(
        verifiedBy = "${EvidenceLabels.SIMULATED_PREFIX}shopping_dry_run",
        evidence = "no_real_search_performed",
      )
    } else {
      ExecutionOutcome.Failed("${EvidenceLabels.SIMULATED_PREFIX}dry_run_simulated_failure")
    }
  }
}
