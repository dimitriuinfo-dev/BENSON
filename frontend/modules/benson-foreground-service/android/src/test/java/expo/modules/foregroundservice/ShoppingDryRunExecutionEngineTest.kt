package expo.modules.foregroundservice

import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingDryRunExecutionEngineTest {

  private fun request(action: String = StaffRegistryCore.CAP_SEARCH_PRODUCTS) =
    ExecutionRequest("SHOPPING", action, emptyMap(), RiskLevel.LOW)

  @Test fun dryRunSucceedsWithoutARealSearch() {
    val outcome = ShoppingDryRunExecutionEngine(simulateSuccess = true).execute(request())
    assertTrue(outcome is ExecutionOutcome.Success)
    assertTrue(EvidenceLabels.isSimulatedEvidence((outcome as ExecutionOutcome.Success).verifiedBy))
  }

  @Test fun unsupportedActionFails() {
    assertTrue(ShoppingDryRunExecutionEngine().execute(request("NAVIGATE")) is ExecutionOutcome.Failed)
  }

  @Test fun neverProducesAPurchaseOrPaymentAction() {
    // Doctrina 3 (fara plati): singura actiune suportata e cautarea, niciodata cumpararea.
    assertTrue(ShoppingDryRunExecutionEngine().execute(request("PURCHASE")) is ExecutionOutcome.Failed)
    assertTrue(ShoppingDryRunExecutionEngine().execute(request("PAY")) is ExecutionOutcome.Failed)
  }

  @Test fun wiredThroughLiveOrchestratorAsAThirdIndependentDomain() {
    val live = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) =
          BrainOutput.ActionRequest(StaffRegistryCore.CAP_SEARCH_PRODUCTS, listOf(DomainEntity.MediaQuery("baterii aa")), SemanticConfidence.HIGH, "ro")
      },
      knownActions = setOf(StaffRegistryCore.CAP_SEARCH_PRODUCTS),
      actionToAgent = mapOf(StaffRegistryCore.CAP_SEARCH_PRODUCTS to "SHOPPING"),
      confirmRequiredActions = emptySet(),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) =
          CapabilityRecord("com.amazon.mShop.android.shopping", operation, CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = false)
      },
      executionEngine = ShoppingDryRunExecutionEngine(),
    )
    val result = live.handleTextInput("caută baterii aa", "m-shop-1")
    assertTrue(result is MissionResult.Executed)
    assertTrue((result as MissionResult.Executed).outcome is ExecutionOutcome.Success)
  }
}
