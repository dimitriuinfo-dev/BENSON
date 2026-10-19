package expo.modules.foregroundservice

// BENSON NEXT — al doilea EXECUTOR, pe un domeniu diferit (COMUNICARE, nu TRAVEL), prin ACELASI
// BensonLiveOrchestrator (un singur LIVE Mission Owner — zero sistem paralel). DRY-RUN DELIBERAT:
// nu atinge WhatsApp real, nu trimite niciun apel/mesaj — demonstreaza doar ca pipeline-ul
// LIVE -> AKA -> EXECUTOR e generic, nu specific navigatiei. Orice rezultat produs aici e etichetat
// EXPLICIT ca simulat (EvidenceLabels.SIMULATED_PREFIX) — nu poate fi confundat cu o dovada reala.
class WhatsAppDryRunExecutionEngine(private val simulateSuccess: Boolean = true) : ExecutionEngine {
  override fun execute(request: ExecutionRequest): ExecutionOutcome {
    if (request.action !in setOf("WHATSAPP_CALL", "WHATSAPP_VIDEO", "WHATSAPP_MESSAGE")) {
      return ExecutionOutcome.Failed("unsupported_action:${request.action}")
    }
    return if (simulateSuccess) {
      ExecutionOutcome.Success(
        verifiedBy = "${EvidenceLabels.SIMULATED_PREFIX}whatsapp_dry_run",
        evidence = "no_real_call_placed",
      )
    } else {
      ExecutionOutcome.Failed("${EvidenceLabels.SIMULATED_PREFIX}dry_run_simulated_failure")
    }
  }
}
