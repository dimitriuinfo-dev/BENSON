package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// D0 — teste pe contractele pure (fara dispozitiv, fara Context), JUnit4, acelasi stil cu
// StaffRegistryCoreTest.kt.
class BensonAgentContractsTest {

  // --- 1. BrainOutput -> CanonicalIntent: validarea e a Core-ului, nu a modelului ---

  @Test fun unknownActionFailsValidation() {
    val out = BrainOutput.ActionRequest("INVENTED_ACTION", listOf(DomainEntity.FreeText("x")), SemanticConfidence.HIGH, "ro")
    assertNull(validateCanonicalIntent(out, knownActions = setOf("NAVIGATE")))
  }

  @Test fun knownActionWithLowConfidenceAndNoEntitiesFailsValidation() {
    val out = BrainOutput.ActionRequest("NAVIGATE", emptyList(), SemanticConfidence.LOW, "ro")
    assertNull(validateCanonicalIntent(out, knownActions = setOf("NAVIGATE")))
  }

  @Test fun knownActionWithEntityValidates() {
    val out = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.MEDIUM, "ro")
    val intent = validateCanonicalIntent(out, knownActions = setOf("NAVIGATE"))
    assertNotNull(intent)
    assertEquals("NAVIGATE", intent!!.action)
    assertEquals("ro", intent.detectedLanguage)
  }

  // --- AgentRoutingDecision: deterministic, produs de Core, nu de model ---

  @Test fun routingPicksAgentAndConfirmationFromClosedTables() {
    val intent = CanonicalIntent("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "de")
    val decision = routeCanonicalIntent(
      intent,
      actionToAgent = mapOf("NAVIGATE" to "TRAVEL"),
      confirmRequiredActions = setOf("NAVIGATE"),
    )
    assertNotNull(decision)
    assertEquals("TRAVEL", decision!!.targetAgent)
    assertTrue(decision.requiresConfirmation)
  }

  @Test fun routingReturnsNullForUnmappedAgent() {
    val intent = CanonicalIntent("UNMAPPED", emptyList(), SemanticConfidence.HIGH, "en")
    assertNull(routeCanonicalIntent(intent, actionToAgent = emptyMap(), confirmRequiredActions = emptySet()))
  }

  @Test fun sameIntentRoutesIdenticallyRegardlessOfLanguage() {
    val intentRo = CanonicalIntent("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "ro")
    val intentDe = CanonicalIntent("NAVIGATE", listOf(DomainEntity.Destination("Praxis")), SemanticConfidence.HIGH, "de")
    val table = mapOf("NAVIGATE" to "TRAVEL")
    assertEquals(
      routeCanonicalIntent(intentRo, table, emptySet())?.targetAgent,
      routeCanonicalIntent(intentDe, table, emptySet())?.targetAgent,
    )
  }

  // --- 2. Mission / MissionStep: dependinte, cicluri, gata-de-rulare, anulare ---

  @Test fun missionWithDuplicateStepIdsIsInvalid() {
    val steps = listOf(
      MissionStep("s1", "TRAVEL", "A", emptyList()),
      MissionStep("s1", "MEMORY", "B", emptyList()),
    )
    assertFalse(validateMissionSteps(steps))
  }

  @Test fun missionWithDanglingDependencyIsInvalid() {
    val steps = listOf(MissionStep("s1", "TRAVEL", "A", emptyList(), dependsOn = listOf("ghost")))
    assertFalse(validateMissionSteps(steps))
  }

  @Test fun missionWithSelfDependencyIsInvalid() {
    val steps = listOf(MissionStep("s1", "TRAVEL", "A", emptyList(), dependsOn = listOf("s1")))
    assertFalse(validateMissionSteps(steps))
  }

  @Test fun missionWithDependencyCycleIsInvalid() {
    val steps = listOf(
      MissionStep("s1", "TRAVEL", "A", emptyList(), dependsOn = listOf("s2")),
      MissionStep("s2", "MEMORY", "B", emptyList(), dependsOn = listOf("s1")),
    )
    assertFalse(validateMissionSteps(steps))
  }

  @Test fun missionWithValidLinearDependencyIsValid() {
    val steps = listOf(
      MissionStep("s1", "MEMORY", "resolve", emptyList()),
      MissionStep("s2", "TRAVEL", "navigate", emptyList(), dependsOn = listOf("s1")),
    )
    assertTrue(validateMissionSteps(steps))
  }

  @Test fun stepIsNotReadyUntilDependencySucceeds() {
    val step = MissionStep("s2", "TRAVEL", "navigate", emptyList(), dependsOn = listOf("s1"))
    assertFalse(isStepReady(step, completed = emptyMap()))
    assertFalse(isStepReady(step, completed = mapOf("s1" to ExecutionOutcome.Failed("x"))))
    assertTrue(isStepReady(step, completed = mapOf("s1" to ExecutionOutcome.Success("screen_changed"))))
  }

  @Test fun cancelMissionSetsCancelledAndState() {
    val mission = Mission("m1", emptyList(), MissionState.EXECUTING)
    val cancelled = cancelMission(mission)
    assertTrue(cancelled.cancelled)
    assertEquals(MissionState.CANCELLED, cancelled.state)
  }

  // --- 6. Memorie: autorizare verificabila + citire scopata ---

  @Test fun memoryWriteRejectedWithoutAuthorizedTurnOrigin() {
    val proof = TurnAuthorizationProof(turnOrigin = "debug_inject", authorizedAtMs = 1000L)
    assertNull(MemoryWriteAuthorization.fromTurnProof(proof, nowMs = 1000L, maxAgeMs = 90_000L))
  }

  @Test fun memoryWriteRejectedWhenAuthorizationExpired() {
    val proof = TurnAuthorizationProof(turnOrigin = "wake", authorizedAtMs = 0L)
    assertNull(MemoryWriteAuthorization.fromTurnProof(proof, nowMs = 100_000L, maxAgeMs = 90_000L))
  }

  @Test fun memoryWriteAcceptedForFreshWakeOrigin() {
    val proof = TurnAuthorizationProof(turnOrigin = "wake", authorizedAtMs = 1000L)
    val auth = MemoryWriteAuthorization.fromTurnProof(proof, nowMs = 2000L, maxAgeMs = 90_000L)
    assertNotNull(auth)
  }

  @Test fun memoryReadScopedPerAgent() {
    assertTrue(isMemoryReadAllowed("TRAVEL", MemoryDomain.PLACES))
    assertFalse(isMemoryReadAllowed("TRAVEL", MemoryDomain.GENERAL_FACTS))
    assertFalse(isMemoryReadAllowed("AKA", MemoryDomain.PLACES))
    assertTrue(isMemoryReadAllowed("MEMORY", MemoryDomain.GENERAL_FACTS))
  }

  // --- 7. Migrare: tipul nu permite stergerea implicita a sursei ---

  @Test fun migrationPlanDefaultsToNotDeletingSource() {
    val plan = MemoryMigrationPlan("js_async_storage_facts_v2", "native_personal_memory_v1", MigrationStrategy.READ_ONLY_BRIDGE)
    assertFalse(plan.deletesSource)
  }

  // --- 9. Ruta: garda impotriva "trafficAware" fals ---

  @Test fun osrmOnlyRouteCannotClaimTrafficAware() {
    val route = RouteInfo(
      distanceMeters = 5000,
      geometrySource = RouteDataSource.STATIC_GEOMETRY_ONLY,
      etaMinutes = 12,
      etaSource = RouteDataSource.HISTORICAL_ESTIMATE,
      trafficAware = true, // ar fi o minciuna
    )
    assertFalse(route.isHonestAboutTraffic())
  }

  @Test fun liveTrafficSourceMayClaimTrafficAware() {
    val route = RouteInfo(
      distanceMeters = 5000,
      geometrySource = RouteDataSource.STATIC_GEOMETRY_ONLY,
      etaMinutes = 14,
      etaSource = RouteDataSource.LIVE_TRAFFIC_AWARE,
      trafficAware = true,
    )
    assertTrue(route.isHonestAboutTraffic())
  }

  @Test fun nonTrafficClaimIsAlwaysHonest() {
    val route = RouteInfo(5000, RouteDataSource.STATIC_GEOMETRY_ONLY, null, RouteDataSource.STATIC_GEOMETRY_ONLY, trafficAware = false)
    assertTrue(route.isHonestAboutTraffic())
  }

  // --- ETAPA 3: clarificare explicita, distincta de respingere (defect gasit la verificare) ---

  @Test fun unknownActionIsRejectedNotClarified() {
    val out = BrainOutput.ActionRequest("INVENTED", emptyList(), SemanticConfidence.HIGH, "ro")
    val outcome = classifyIntentValidation(out, knownActions = setOf("NAVIGATE"))
    assertTrue(outcome is IntentValidationOutcome.Rejected)
  }

  @Test fun lowConfidenceKnownActionNeedsClarificationNotRejection() {
    val out = BrainOutput.ActionRequest("NAVIGATE", emptyList(), SemanticConfidence.LOW, "ro")
    val outcome = classifyIntentValidation(out, knownActions = setOf("NAVIGATE"))
    assertTrue(outcome is IntentValidationOutcome.NeedsClarification)
  }

  @Test fun sufficientKnownActionValidates() {
    val out = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "ro")
    val outcome = classifyIntentValidation(out, knownActions = setOf("NAVIGATE"))
    assertTrue(outcome is IntentValidationOutcome.Validated)
    assertEquals("NAVIGATE", (outcome as IntentValidationOutcome.Validated).intent.action)
  }

  // --- ETAPA 3: protectie la executii duplicate ---

  @Test fun actionRequiringEntityNeedsClarificationWhenMissingEvenAtHighConfidence() {
    val out = BrainOutput.ActionRequest("NAVIGATE", emptyList(), SemanticConfidence.HIGH, "ro")
    val outcome = classifyIntentValidation(
      out, knownActions = setOf("NAVIGATE"),
      requiredEntityTypes = mapOf("NAVIGATE" to DomainEntity.Destination::class.java),
    )
    assertTrue(outcome is IntentValidationOutcome.NeedsClarification)
  }

  @Test fun actionRequiringEntityValidatesWhenPresent() {
    val out = BrainOutput.ActionRequest("NAVIGATE", listOf(DomainEntity.Destination("cabinet")), SemanticConfidence.HIGH, "ro")
    val outcome = classifyIntentValidation(
      out, knownActions = setOf("NAVIGATE"),
      requiredEntityTypes = mapOf("NAVIGATE" to DomainEntity.Destination::class.java),
    )
    assertTrue(outcome is IntentValidationOutcome.Validated)
  }

  @Test fun actionWithoutDeclaredRequirementIsUnaffected() {
    // Comportamentul vechi (fara requiredEntityTypes) ramane neschimbat pentru apelantii existenti.
    val out = BrainOutput.ActionRequest("NAVIGATE", emptyList(), SemanticConfidence.HIGH, "ro")
    val outcome = classifyIntentValidation(out, knownActions = setOf("NAVIGATE"))
    assertTrue(outcome is IntentValidationOutcome.Validated)
  }

  @Test fun isDuplicateExecutionDetectsRepeatedStep() {
    val executed = setOf(ExecutionIdempotencyKey("m1", "execute_navigation"))
    assertTrue(isDuplicateExecution(executed, "m1", "execute_navigation"))
    assertFalse(isDuplicateExecution(executed, "m1", "resolve_destination"))
    assertFalse(isDuplicateExecution(executed, "m2", "execute_navigation"))
  }
}
