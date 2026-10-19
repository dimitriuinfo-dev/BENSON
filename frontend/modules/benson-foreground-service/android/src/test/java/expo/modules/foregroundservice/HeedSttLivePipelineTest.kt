package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeedSttLivePipelineTest {

  private fun orchestratorReplying(text: String) = BensonLiveOrchestrator(
    modelProvider = object : ModelProvider {
      override val providerId = "fake"
      override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) = BrainOutput.Reply(text, "ro")
    },
    knownActions = emptySet(), actionToAgent = emptyMap(), confirmRequiredActions = emptySet(),
    capabilityLookup = object : ApplicationCapabilityLookup { override fun selectCapability(operation: String) = null },
    executionEngine = object : ExecutionEngine { override fun execute(request: ExecutionRequest) = ExecutionOutcome.Unverified },
  )

  private fun voiceClient(available: Boolean) = object : OpenAiVoiceOutputClient { override fun isAvailable() = available }

  // --- LEGEA TACERII: respectata la intrare, nu doar la iesire ---

  @Test fun unauthorizedTurnNeverReachesLiveAtAll() {
    var liveWasCalled = false
    val orchestrator = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>): BrainOutput {
          liveWasCalled = true
          return BrainOutput.Reply("nu ar trebui sa ajunga aici", "ro")
        }
      },
      knownActions = emptySet(), actionToAgent = emptyMap(), confirmRequiredActions = emptySet(),
      capabilityLookup = object : ApplicationCapabilityLookup { override fun selectCapability(operation: String) = null },
      executionEngine = object : ExecutionEngine { override fun execute(request: ExecutionRequest) = ExecutionOutcome.Unverified },
    )
    val pipeline = HeedSttLivePipeline(orchestrator, voiceClient(true))

    val result = pipeline.handleTranscript("ce faci acolo", turnOrigin = "ambient_tv", isTurnAuthorized = false, missionId = "m1")

    assertTrue(result is PipelineResult.Suppressed)
    assertEquals(false, liveWasCalled) // dovada ca TV-ul/vorbirea ambientala nu ajunge la LLM
  }

  @Test fun authorizedWakeTurnReachesLiveAndProducesVoiceDecision() {
    val pipeline = HeedSttLivePipeline(orchestratorReplying("Salut!"), voiceClient(true))
    val result = pipeline.handleTranscript("buna benson", turnOrigin = "wake", isTurnAuthorized = true, missionId = "m2")
    assertTrue(result is PipelineResult.Processed)
    result as PipelineResult.Processed
    assertTrue(result.missionResult is MissionResult.Replied)
    assertTrue(result.voiceDecision is VoiceOutputDecision.Speak)
  }

  @Test fun authorizedTurnWithVoiceUnavailableStillProcessesButFallsBackToText() {
    val pipeline = HeedSttLivePipeline(orchestratorReplying("Salut!"), voiceClient(false))
    val result = pipeline.handleTranscript("buna benson", turnOrigin = "follow_up", isTurnAuthorized = true, missionId = "m3")
    assertTrue(result is PipelineResult.Processed)
    assertTrue((result as PipelineResult.Processed).voiceDecision is VoiceOutputDecision.TextOnly)
  }

  @Test fun suppressedReasonNamesTheUnauthorizedOrigin() {
    val pipeline = HeedSttLivePipeline(orchestratorReplying("x"), voiceClient(true))
    val result = pipeline.handleTranscript("orice", turnOrigin = "ambient_tv", isTurnAuthorized = false, missionId = "m4")
    assertEquals("no_wake_authorization:ambient_tv", (result as PipelineResult.Suppressed).reason)
  }
}
