package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// BENSON NEXT — "o comanda RO si echivalentul german trebuie sa genereze aceeasi intentie
// canonica, inclusiv VIDEO_CALL catre Hannah, fara schimbarea neautorizata a canalului"
// (19.10.2026). ModelProvider real (un LLM) ramane neinceput aici - acest test dovedeste ca,
// ODATA ce doua fraze diferite (RO/DE) sunt clasificate la ACEEASI actiune/entitati (exact ce un
// model multilingv real ar trebui sa faca), restul lantului LIVE->AKA->EXECUTOR le trateaza
// IDENTIC, fara nicio ramura de cod per limba.
class MultilingualIntentEquivalenceTest {

  private fun videoCallOrchestrator(executor: WhatsAppDryRunExecutionEngine, action: BrainOutput.ActionRequest) =
    BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) = action
      },
      knownActions = setOf("WHATSAPP_VIDEO"),
      actionToAgent = mapOf("WHATSAPP_VIDEO" to "COMMUNICATION"),
      confirmRequiredActions = setOf("WHATSAPP_VIDEO"),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) =
          CapabilityRecord("com.whatsapp", "WHATSAPP_VIDEO", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
      },
      executionEngine = executor,
    )

  @Test fun romanianAndGermanVideoCallToHannahProduceTheSameOutcome() {
    val executor = WhatsAppDryRunExecutionEngine(simulateSuccess = true)

    val actionRo = BrainOutput.ActionRequest("WHATSAPP_VIDEO", listOf(DomainEntity.Contact("Hannah")), SemanticConfidence.HIGH, "ro")
    val actionDe = BrainOutput.ActionRequest("WHATSAPP_VIDEO", listOf(DomainEntity.Contact("Hannah")), SemanticConfidence.HIGH, "de")

    val resultRo = videoCallOrchestrator(executor, actionRo).handleTextInput("video cu Hannah", "m-ro")
    val resultDe = videoCallOrchestrator(executor, actionDe).handleTextInput("Videoanruf mit Hannah", "m-de")

    assertTrue(resultRo is MissionResult.Executed)
    assertTrue(resultDe is MissionResult.Executed)
    // Acelasi tip de rezultat, aceeasi actiune/canal (WHATSAPP_VIDEO) - nu s-a schimbat
    // neautorizat canalul (ex. nu a cazut pe apel vocal normal sau pe SMS).
    assertEquals(
      ((resultRo as MissionResult.Executed).outcome as ExecutionOutcome.Success).verifiedBy,
      ((resultDe as MissionResult.Executed).outcome as ExecutionOutcome.Success).verifiedBy,
    )
  }

  @Test fun channelNeverSilentlyChangesFromVideoToVoiceCall() {
    // Chiar daca cineva ar incerca sa "simplifice" WHATSAPP_VIDEO la WHATSAPP_CALL in routing,
    // acest test l-ar prinde: actiunea ceruta de model trebuie sa ajunga EXACT la executor.
    val executor = object : ExecutionEngine {
      var receivedAction: String? = null
      override fun execute(request: ExecutionRequest): ExecutionOutcome {
        receivedAction = request.action
        return ExecutionOutcome.Success("${EvidenceLabels.SIMULATED_PREFIX}whatsapp_dry_run", "ok")
      }
    }
    val action = BrainOutput.ActionRequest("WHATSAPP_VIDEO", listOf(DomainEntity.Contact("Hannah")), SemanticConfidence.HIGH, "de")
    videoCallOrchestrator(WhatsAppDryRunExecutionEngine(), action) // doar pentru constructie identica, neutilizat
    val live = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) = action
      },
      knownActions = setOf("WHATSAPP_VIDEO"),
      actionToAgent = mapOf("WHATSAPP_VIDEO" to "COMMUNICATION"),
      confirmRequiredActions = setOf("WHATSAPP_VIDEO"),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) =
          CapabilityRecord("com.whatsapp", "WHATSAPP_VIDEO", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
      },
      executionEngine = executor,
    )
    live.handleTextInput("Videoanruf mit Hannah", "m-channel")
    assertEquals("WHATSAPP_VIDEO", executor.receivedAction)
  }
}
