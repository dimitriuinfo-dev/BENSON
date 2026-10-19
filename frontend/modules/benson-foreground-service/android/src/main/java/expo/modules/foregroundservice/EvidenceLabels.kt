package expo.modules.foregroundservice

// BENSON NEXT — convenție obligatorie: niciun rezultat SIMULAT (dry-run, fake de test) nu poate fi
// confundat cu o dovadă reală de execuție. Orice ExecutionOutcome produs de un executor care NU
// atinge Android real trebuie sa-si eticheteze `verifiedBy`/`reason` cu acest prefix — verificat
// prin teste (NavigationExecutionEngineTest, WhatsAppDryRunExecutionEngineTest), nu doar prin
// conventie nescrisa.
object EvidenceLabels {
  const val SIMULATED_PREFIX = "simulated:"
  fun isSimulatedEvidence(label: String): Boolean = label.startsWith(SIMULATED_PREFIX)
}
