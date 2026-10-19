package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryResolveIntegrationTest {

  private val cabinet = SavedPlace(
    canonicalName = "cabinet medical",
    aliases = mapOf("ro" to "cabinet", "de" to "Praxis", "en" to "my office"),
    latitude = 46.77, longitude = 23.59, confirmedByUser = true,
  )

  private fun fakeMemory(place: SavedPlace?) = object : DestinationMemoryLookup {
    override fun resolve(requesterAgent: String, query: String): SavedPlace? = place
  }

  private fun orchestratorFor(memory: DestinationMemoryLookup?, action: BrainOutput.ActionRequest) =
    BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) = action
      },
      knownActions = setOf("NAVIGATE"),
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = setOf("NAVIGATE"),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) =
          CapabilityRecord("com.waze", "NAVIGATE", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
      },
      executionEngine = NavigationExecutionEngine(
        expectedPackagesFor = { setOf("com.waze") },
        observeForegroundPackage = { "com.waze" },
      ),
      memoryLookup = memory,
    )

  @Test fun knownAliasResolvesAndMissionExecutes() {
    val action = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "ro")
    val result = orchestratorFor(fakeMemory(cabinet), action).handleTextInput("du-ma la cabinet", "m1")
    assertTrue(result is MissionResult.Executed)
    assertTrue((result as MissionResult.Executed).outcome is ExecutionOutcome.Success)
  }

  @Test fun germanAliasResolvesToSameSavedPlaceAsRomanian() {
    val actionRo = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "ro")
    val actionDe = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("Praxis")), SemanticConfidence.HIGH, "de")
    val resultRo = orchestratorFor(fakeMemory(cabinet), actionRo).handleTextInput("du-ma la cabinet", "m1")
    val resultDe = orchestratorFor(fakeMemory(cabinet), actionDe).handleTextInput("fahr mich zur Praxis", "m2")
    // Ambele ajung la EXECUTOR cu acelasi rezultat - "aceeasi destinatie, indiferent de limba".
    assertTrue(resultRo is MissionResult.Executed && resultDe is MissionResult.Executed)
  }

  @Test fun unknownDestinationAsksForClarificationNeverInventsAnAddress() {
    val action = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("un loc necunoscut")), SemanticConfidence.HIGH, "ro")
    val result = orchestratorFor(fakeMemory(null), action).handleTextInput("du-ma undeva", "m3")
    assertTrue(result is MissionResult.Clarify)
    assertEquals("destination_unknown:un loc necunoscut", (result as MissionResult.Clarify).question)
  }

  @Test fun withoutMemoryWiredBehaviorIsUnchanged() {
    // memoryLookup = null (implicit) -> trece direct, ca inainte de aceasta runda.
    val action = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("aeroport")), SemanticConfidence.HIGH, "ro")
    val result = orchestratorFor(null, action).handleTextInput("du-ma la aeroport", "m4")
    assertTrue(result is MissionResult.Executed)
  }
}
