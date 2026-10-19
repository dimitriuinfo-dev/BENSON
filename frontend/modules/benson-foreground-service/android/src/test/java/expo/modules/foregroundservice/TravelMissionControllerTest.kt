package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelMissionControllerTest {

  private val cabinet = SavedPlace(
    canonicalName = "cabinet medical",
    aliases = mapOf("ro" to "cabinet", "de" to "Praxis"),
    latitude = 46.77, longitude = 23.59, confirmedByUser = true,
  )

  private fun capability(state: CapabilityVerificationState) =
    CapabilityRecord("com.waze", StaffRegistryCore.CAP_NAVIGATE, state, requiresConfirmation = true)

  @Test fun neverInventsAddressWhenDestinationUnresolved() {
    val mission = planNavigationMission("m1", "cabinet", resolvedPlace = null, navigationCapability = null)
    assertEquals(MissionState.CREATED, mission.state)
    assertEquals(1, mission.steps.size)
    assertEquals("resolve_destination", mission.steps[0].stepId)
    assertTrue(validateMissionSteps(mission.steps))
  }

  @Test fun stopsBeforeExecutionWhenNoCapabilityAvailable() {
    val mission = planNavigationMission("m2", "cabinet", resolvedPlace = cabinet, navigationCapability = null)
    assertEquals(MissionState.CREATED, mission.state)
    assertEquals(listOf("resolve_destination", "select_capability"), mission.steps.map { it.stepId })
  }

  @Test fun stopsBeforeExecutionWhenCapabilityNotYetVerified() {
    // O capabilitate INFERRED exista, dar nu e sigura de declarat operationala — nu trebuie sa
    // ajunga la executie doar pentru ca "exista ceva" (corectia 4).
    val mission = planNavigationMission("m3", "cabinet", resolvedPlace = cabinet, navigationCapability = capability(CapabilityVerificationState.INFERRED))
    assertEquals(MissionState.CREATED, mission.state)
    assertFalse(mission.steps.any { it.stepId == "execute_navigation" })
  }

  @Test fun fullMissionWhenDestinationAndCapabilityAreVerified() {
    val mission = planNavigationMission("m4", "cabinet", resolvedPlace = cabinet, navigationCapability = capability(CapabilityVerificationState.DEVICE_VERIFIED))
    assertEquals(MissionState.EXECUTING, mission.state)
    assertEquals(listOf("resolve_destination", "select_capability", "execute_navigation"), mission.steps.map { it.stepId })
    val execStep = mission.steps.last()
    assertTrue(execStep.requiresConfirmation)
    assertEquals(listOf("select_capability"), execStep.dependsOn)
    assertTrue(validateMissionSteps(mission.steps))
  }

  @Test fun honestRouteOrNullDropsDishonestTrafficClaim() {
    val dishonest = RouteInfo(5000, RouteDataSource.STATIC_GEOMETRY_ONLY, 12, RouteDataSource.HISTORICAL_ESTIMATE, trafficAware = true)
    assertNull(honestRouteOrNull(dishonest))
  }

  @Test fun honestRouteOrNullKeepsGenuineLiveTrafficRoute() {
    val honest = RouteInfo(5000, RouteDataSource.STATIC_GEOMETRY_ONLY, 14, RouteDataSource.LIVE_TRAFFIC_AWARE, trafficAware = true)
    assertEquals(honest, honestRouteOrNull(honest))
  }

  @Test fun honestRouteOrNullPassesThroughNull() {
    assertNull(honestRouteOrNull(null))
  }

  @Test fun applyStepOutcomeCompletesMissionWhenAllStepsSucceed() {
    val mission = planNavigationMission("m5", "cabinet", cabinet, capability(CapabilityVerificationState.DEVICE_VERIFIED))
    var current = mission
    for (step in mission.steps) {
      current = applyStepOutcome(current, step.stepId, ExecutionOutcome.Success("screen_changed"))
    }
    assertEquals(MissionState.COMPLETED, current.state)
  }

  @Test fun applyStepOutcomeFailsMissionOnAnyStepFailure() {
    val mission = planNavigationMission("m6", "cabinet", cabinet, capability(CapabilityVerificationState.DEVICE_VERIFIED))
    val afterFailure = applyStepOutcome(mission, "select_capability", ExecutionOutcome.Failed("not_found"))
    assertEquals(MissionState.FAILED, afterFailure.state)
  }

  @Test fun applyStepOutcomeIsNoOpAfterCancellation() {
    val mission = planNavigationMission("m7", "cabinet", cabinet, capability(CapabilityVerificationState.DEVICE_VERIFIED))
    val cancelled = cancelMission(mission)
    val afterLateSuccess = applyStepOutcome(cancelled, "execute_navigation", ExecutionOutcome.Success("nav_launch"))
    assertEquals(MissionState.CANCELLED, afterLateSuccess.state)
  }
}
