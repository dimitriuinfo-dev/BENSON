package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationExecutionEngineTest {

  private fun request(action: String = "NAVIGATE") = ExecutionRequest("TRAVEL", action, emptyMap(), RiskLevel.CONFIRM_REQUIRED)

  @Test fun successWhenForegroundMatchesOnFirstPoll() {
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.waze" },
    )
    val outcome = engine.execute(request())
    assertTrue(outcome is ExecutionOutcome.Success)
    assertEquals("com.waze", (outcome as ExecutionOutcome.Success).evidence)
  }

  @Test fun successAfterAFewPollsOnceForegroundCatchesUp() {
    var call = 0
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { call += 1; if (call < 3) null else "com.waze" },
    )
    val outcome = engine.execute(request())
    assertTrue(outcome is ExecutionOutcome.Success)
  }

  @Test fun failsAfterPollsExhaustedWithoutMatch() {
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { null },
      maxPolls = 3,
    )
    val outcome = engine.execute(request())
    assertTrue(outcome is ExecutionOutcome.Failed)
    assertEquals("launch_unverified", (outcome as ExecutionOutcome.Failed).reason)
  }

  @Test fun failsWhenWrongAppIsInForeground() {
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.whatsapp" },
      maxPolls = 1,
    )
    val outcome = engine.execute(request())
    assertTrue(outcome is ExecutionOutcome.Failed)
  }

  @Test fun unsupportedActionFailsImmediately() {
    val engine = NavigationExecutionEngine(expectedPackagesFor = { setOf("com.waze") }, observeForegroundPackage = { "com.waze" })
    val outcome = engine.execute(request(action = "WHATSAPP_CALL"))
    assertTrue(outcome is ExecutionOutcome.Failed)
  }

  // --- routeInfoProvider opt-in (implicit null = fara schimbare fata de testele de mai sus) ---

  @Test fun successAttachesHonestRouteEvidenceWhenProviderGiven() {
    val honestRoute = RouteInfo(1200, RouteDataSource.STATIC_GEOMETRY_ONLY, 7, RouteDataSource.HISTORICAL_ESTIMATE, false)
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.waze" },
      routeInfoProvider = { honestRoute },
    )
    val outcome = engine.execute(request()) as ExecutionOutcome.Success
    assertEquals("com.waze;distanceMeters=1200;trafficAware=false;etaMinutes=7;etaSource=HISTORICAL_ESTIMATE", outcome.evidence)
  }

  @Test fun successDoesNotAttachDishonestRouteEvidence() {
    val dishonestRoute = RouteInfo(1200, RouteDataSource.STATIC_GEOMETRY_ONLY, 7, RouteDataSource.STATIC_GEOMETRY_ONLY, true)
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.waze" },
      routeInfoProvider = { dishonestRoute },
    )
    val outcome = engine.execute(request()) as ExecutionOutcome.Success
    assertEquals("com.waze", outcome.evidence)
  }

  @Test fun failureNeverGetsRouteEvidenceAttached() {
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { null },
      maxPolls = 1,
      routeInfoProvider = { RouteInfo(1200, RouteDataSource.STATIC_GEOMETRY_ONLY, 7, RouteDataSource.HISTORICAL_ESTIMATE, false) },
    )
    val outcome = engine.execute(request())
    assertTrue(outcome is ExecutionOutcome.Failed)
  }

  // --- Integrat cu BensonLiveOrchestrator (nu izolat) ---

  @Test fun wiredIntoLiveOrchestratorProducesVerifiedNavigation() {
    val actionRequest = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("aeroport")), SemanticConfidence.HIGH, "ro")
    val capability = CapabilityRecord("com.waze", "NAVIGATE", CapabilityVerificationState.DEVICE_VERIFIED, requiresConfirmation = true)
    val engine = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.waze" },
    )
    val live = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) = actionRequest
      },
      knownActions = setOf("NAVIGATE"),
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = setOf("NAVIGATE"),
      capabilityLookup = object : ApplicationCapabilityLookup {
        override fun selectCapability(operation: String) = capability
      },
      executionEngine = engine,
    )
    val result = live.handleTextInput("du-ma la aeroport", "m-nav-1")
    assertTrue(result is MissionResult.Executed)
    assertTrue((result as MissionResult.Executed).outcome is ExecutionOutcome.Success)
  }
}
