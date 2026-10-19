package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Flux explicit de confirmare (opt-in, requireExplicitConfirmationStep=true) — modeleaza
// "Te duc la X. Pornesc?" -> da/nu la nivel LIVE. Implicit FALSE peste tot in rest (zero
// schimbare pentru testele/comportamentul deja existent).
class MissionConfirmationFlowTest {

  private fun orchestrator(executor: ExecutionEngine) = BensonLiveOrchestrator(
    modelProvider = object : ModelProvider {
      override val providerId = "fake"
      override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) =
        BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("aeroport")), SemanticConfidence.HIGH, "ro")
    },
    knownActions = setOf("NAVIGATE"),
    actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
    confirmRequiredActions = setOf("NAVIGATE"),
    capabilityLookup = object : ApplicationCapabilityLookup {
      override fun selectCapability(operation: String) =
        CapabilityRecord("com.waze", "NAVIGATE", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
    },
    executionEngine = executor,
    requireExplicitConfirmationStep = true,
  )

  @Test fun confirmRequiredActionWaitsInsteadOfExecutingImmediately() {
    val executor = object : ExecutionEngine {
      var called = false
      override fun execute(request: ExecutionRequest): ExecutionOutcome { called = true; return ExecutionOutcome.Success("x") }
    }
    val result = orchestrator(executor).handleTextInput("du-ma la aeroport", "m1")
    assertTrue(result is MissionResult.AwaitingConfirmation)
    assertEquals(false, executor.called) // executia NU a pornit inainte de confirmare
  }

  @Test fun yesExecutesThePendingMission() {
    val executor = object : ExecutionEngine {
      override fun execute(request: ExecutionRequest) = ExecutionOutcome.Success("foreground_package", "com.waze")
    }
    val live = orchestrator(executor)
    val awaiting = live.handleTextInput("du-ma la aeroport", "m2") as MissionResult.AwaitingConfirmation
    val result = live.confirmPendingMission(awaiting.missionId, confirmed = true)
    assertTrue(result is MissionResult.Executed)
  }

  @Test fun noNeverExecutesAndIsNotSimulatedAsSuccess() {
    val executor = object : ExecutionEngine {
      var called = false
      override fun execute(request: ExecutionRequest): ExecutionOutcome { called = true; return ExecutionOutcome.Success("x") }
    }
    val live = orchestrator(executor)
    val awaiting = live.handleTextInput("du-ma la aeroport", "m3") as MissionResult.AwaitingConfirmation
    val result = live.confirmPendingMission(awaiting.missionId, confirmed = false)
    assertTrue(result is MissionResult.Rejected)
    assertEquals(false, executor.called)
  }

  @Test fun confirmingAnUnknownMissionIdIsRejectedNotCrashed() {
    val live = orchestrator(object : ExecutionEngine { override fun execute(request: ExecutionRequest) = ExecutionOutcome.Unverified })
    val result = live.confirmPendingMission("never-existed", confirmed = true)
    assertTrue(result is MissionResult.Rejected)
  }

  @Test fun sameMissionCannotBeConfirmedTwice() {
    val executor = object : ExecutionEngine { override fun execute(request: ExecutionRequest) = ExecutionOutcome.Success("x") }
    val live = orchestrator(executor)
    val awaiting = live.handleTextInput("du-ma la aeroport", "m4") as MissionResult.AwaitingConfirmation
    live.confirmPendingMission(awaiting.missionId, confirmed = true)
    val second = live.confirmPendingMission(awaiting.missionId, confirmed = true)
    assertTrue(second is MissionResult.Rejected) // deja consumata, nu se re-executa
  }
}
