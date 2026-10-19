package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BensonLiveOrchestratorTest {

  private class FakeModelProvider(private val output: BrainOutput) : ModelProvider {
    override val providerId = "fake"
    override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>): BrainOutput = output
  }

  private class FakeCapabilityLookup(private val record: CapabilityRecord?) : ApplicationCapabilityLookup {
    override fun selectCapability(operation: String): CapabilityRecord? = record
  }

  private class FakeExecutionEngine(private val outcome: ExecutionOutcome) : ExecutionEngine {
    var lastRequest: ExecutionRequest? = null
    override fun execute(request: ExecutionRequest): ExecutionOutcome {
      lastRequest = request
      return outcome
    }
  }

  private val verifiedNavigateCapability =
    CapabilityRecord("com.waze", "NAVIGATE", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)

  // --- Primul flux complet: TEXT -> LIVE -> AKA -> EXECUTOR -> REZULTAT VERIFICAT ---

  @Test fun fullMissionFlowEndToEndProducesVerifiedResult() {
    val actionRequest = BrainOutput.ActionRequest(
      action = "NAVIGATE",
      entities = listOf(DomainEntity.Destination("aeroport")),
      confidence = SemanticConfidence.HIGH,
      detectedLanguage = "ro",
    )
    val executor = FakeExecutionEngine(ExecutionOutcome.Success(verifiedBy = "foreground_package", evidence = "com.waze"))
    val live = BensonLiveOrchestrator(
      modelProvider = FakeModelProvider(actionRequest),
      knownActions = setOf("NAVIGATE"),
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = setOf("NAVIGATE"),
      capabilityLookup = FakeCapabilityLookup(verifiedNavigateCapability),
      executionEngine = executor,
    )

    val result = live.handleTextInput("du-ma la aeroport", missionId = "m1")

    assertTrue(result is MissionResult.Executed)
    result as MissionResult.Executed
    assertEquals("m1", result.missionId)
    assertTrue(result.outcome is ExecutionOutcome.Success)
    assertEquals("com.waze", (result.outcome as ExecutionOutcome.Success).evidence)
    // AKA a ales corect capabilitatea, EXECUTOR a primit cererea corecta (risc CONFIRM_REQUIRED,
    // pentru ca NAVIGATE e in confirmRequiredActions) - traseul complet, nu doar rezultatul final.
    assertEquals("NAVIGATE", executor.lastRequest?.action)
    assertEquals(RiskLevel.CONFIRM_REQUIRED, executor.lastRequest?.risk)
  }

  @Test fun pureConversationReplyNeverTouchesExecutor() {
    val executor = FakeExecutionEngine(ExecutionOutcome.Unverified)
    val live = BensonLiveOrchestrator(
      modelProvider = FakeModelProvider(BrainOutput.Reply("Salut!", "ro")),
      knownActions = setOf("NAVIGATE"),
      actionToAgent = emptyMap(),
      confirmRequiredActions = emptySet(),
      capabilityLookup = FakeCapabilityLookup(null),
      executionEngine = executor,
    )
    val result = live.handleTextInput("salut", "m2")
    assertTrue(result is MissionResult.Replied)
    assertEquals("Salut!", (result as MissionResult.Replied).text)
    assertEquals(null, executor.lastRequest) // niciodata apelat pentru conversatie simpla
  }

  @Test fun unknownActionIsRejectedBeforeReachingAka() {
    val actionRequest = BrainOutput.ActionRequest("INVENTED", listOf(DomainEntity.FreeText("x")), SemanticConfidence.HIGH, "ro")
    val capabilityLookup = FakeCapabilityLookup(verifiedNavigateCapability)
    val live = BensonLiveOrchestrator(
      modelProvider = FakeModelProvider(actionRequest),
      knownActions = setOf("NAVIGATE"), // "INVENTED" nu e cunoscut
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = emptySet(),
      capabilityLookup = capabilityLookup,
      executionEngine = FakeExecutionEngine(ExecutionOutcome.Unverified),
    )
    val result = live.handleTextInput("ceva inventat", "m3")
    assertTrue(result is MissionResult.Rejected)
  }

  @Test fun unverifiedCapabilityBlocksExecution() {
    val actionRequest = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("aeroport")), SemanticConfidence.HIGH, "ro")
    val inferredOnly = CapabilityRecord("com.waze", "NAVIGATE", CapabilityVerificationState.INFERRED, requiresConfirmation = true)
    val executor = FakeExecutionEngine(ExecutionOutcome.Success("x"))
    val live = BensonLiveOrchestrator(
      modelProvider = FakeModelProvider(actionRequest),
      knownActions = setOf("NAVIGATE"),
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = setOf("NAVIGATE"),
      capabilityLookup = FakeCapabilityLookup(inferredOnly), // nu DEVICE_VERIFIED/RUNTIME_VERIFIED
      executionEngine = executor,
    )
    val result = live.handleTextInput("du-ma la aeroport", "m4")
    assertTrue(result is MissionResult.Rejected)
    assertEquals(null, executor.lastRequest) // EXECUTOR nu se atinge fara capabilitate verificata
  }

  @Test fun clarifyRequestNeverReachesExecutor() {
    val executor = FakeExecutionEngine(ExecutionOutcome.Unverified)
    val live = BensonLiveOrchestrator(
      modelProvider = FakeModelProvider(BrainOutput.Clarify("Unde anume?", "ro")),
      knownActions = emptySet(), actionToAgent = emptyMap(), confirmRequiredActions = emptySet(),
      capabilityLookup = FakeCapabilityLookup(null), executionEngine = executor,
    )
    val result = live.handleTextInput("du-ma undeva", "m5")
    assertTrue(result is MissionResult.Clarify)
    assertEquals(null, executor.lastRequest)
  }

  // --- ONE VOICE, OPENAI FIRST: niciodata inlocuire tacita, text cand vocea nu e disponibila ---

  @Test fun voiceServiceAvailableSpeaksWithConfiguredVoice() {
    val decision = VoiceOutputPolicy.decide(VoiceOutputRequest("Am ajuns.", "m1"), voiceServiceAvailable = true)
    assertTrue(decision is VoiceOutputDecision.Speak)
    assertEquals(VoiceOutputPolicy.CONFIGURED_VOICE_ID, (decision as VoiceOutputDecision.Speak).voiceId)
  }

  @Test fun voiceServiceUnavailableFallsBackToTextNeverToAnotherVoice() {
    val decision = VoiceOutputPolicy.decide(VoiceOutputRequest("Am ajuns.", "m1"), voiceServiceAvailable = false)
    assertTrue(decision is VoiceOutputDecision.TextOnly)
    assertEquals("voice_service_unavailable", (decision as VoiceOutputDecision.TextOnly).reason)
  }
}
