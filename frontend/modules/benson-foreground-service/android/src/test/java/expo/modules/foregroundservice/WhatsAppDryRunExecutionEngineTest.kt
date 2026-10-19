package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppDryRunExecutionEngineTest {

  private fun request(action: String = "WHATSAPP_CALL") = ExecutionRequest("COMMUNICATION", action, emptyMap(), RiskLevel.CONFIRM_REQUIRED)

  @Test fun dryRunSucceedsWithoutPlacingARealCall() {
    val outcome = WhatsAppDryRunExecutionEngine(simulateSuccess = true).execute(request())
    assertTrue(outcome is ExecutionOutcome.Success)
    assertEquals("no_real_call_placed", (outcome as ExecutionOutcome.Success).evidence)
  }

  @Test fun dryRunEvidenceIsAlwaysMarkedSimulated() {
    val outcome = WhatsAppDryRunExecutionEngine(simulateSuccess = true).execute(request()) as ExecutionOutcome.Success
    assertTrue(EvidenceLabels.isSimulatedEvidence(outcome.verifiedBy))
  }

  @Test fun dryRunFailureIsAlsoMarkedSimulated() {
    val outcome = WhatsAppDryRunExecutionEngine(simulateSuccess = false).execute(request()) as ExecutionOutcome.Failed
    assertTrue(EvidenceLabels.isSimulatedEvidence(outcome.reason))
  }

  @Test fun supportsAllThreeWhatsAppActions() {
    for (action in listOf("WHATSAPP_CALL", "WHATSAPP_VIDEO", "WHATSAPP_MESSAGE")) {
      assertTrue(WhatsAppDryRunExecutionEngine().execute(request(action)) is ExecutionOutcome.Success)
    }
  }

  @Test fun unsupportedActionFails() {
    assertTrue(WhatsAppDryRunExecutionEngine().execute(request("NAVIGATE")) is ExecutionOutcome.Failed)
  }

  // --- Anti-confuzie simulat/real: rezultatele celor doua executoare NU se pot amesteca ---

  @Test fun realNavigationEvidenceIsNeverMarkedSimulated() {
    val realOutcome = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.waze" },
    ).execute(ExecutionRequest("TRAVEL", "NAVIGATE", emptyMap(), RiskLevel.CONFIRM_REQUIRED)) as ExecutionOutcome.Success
    assertFalse(EvidenceLabels.isSimulatedEvidence(realOutcome.verifiedBy))
  }

  @Test fun fullOrchestratorFlowThroughWhatsAppDryRunIsLabeledSimulated() {
    val live = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) =
          BrainOutput.ActionRequest("WHATSAPP_CALL", listOf(DomainEntity.Contact("Hannah")), SemanticConfidence.HIGH, "ro")
      },
      knownActions = setOf("WHATSAPP_CALL"),
      actionToAgent = mapOf("WHATSAPP_CALL" to "COMMUNICATION"),
      confirmRequiredActions = setOf("WHATSAPP_CALL"),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) =
          CapabilityRecord("com.whatsapp", "WHATSAPP_CALL", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
      },
      executionEngine = WhatsAppDryRunExecutionEngine(),
    )
    val result = live.handleTextInput("suna-o pe Hannah pe WhatsApp", "m-wa-1")
    assertTrue(result is MissionResult.Executed)
    val outcome = (result as MissionResult.Executed).outcome as ExecutionOutcome.Success
    assertTrue(EvidenceLabels.isSimulatedEvidence(outcome.verifiedBy))
  }
}
