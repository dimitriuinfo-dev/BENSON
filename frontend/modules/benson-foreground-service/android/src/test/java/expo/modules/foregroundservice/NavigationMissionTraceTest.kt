package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMissionTraceTest {

  @Test fun knownRawReasonsClassifyToClosedEnum() {
    assertEquals(NavigationFailureReason.NO_STAFF, classifyNavigationFailure("navigate_no_staff"))
    assertEquals(NavigationFailureReason.LAUNCH_FAILED, classifyNavigationFailure("launch_failed"))
    assertEquals(NavigationFailureReason.USER_DECLINED, classifyNavigationFailure("nav_confirm_no"))
    assertEquals(NavigationFailureReason.UNRESOLVED, classifyNavigationFailure("nav_confirm_unresolved"))
    assertEquals(NavigationFailureReason.LAUNCH_UNVERIFIED, classifyNavigationFailure("launch_unverified"))
  }

  @Test fun unknownRawReasonClassifiesToNull() {
    assertNull(classifyNavigationFailure("something_new"))
  }

  @Test fun navigationMissionHasValidDependencyChain() {
    val mission = newNavigationMission("nav1", "aeroport")
    assertEquals(MissionState.CREATED, mission.state)
    assertEquals(listOf("navigate_confirm", "navigate_launch", "navigate_verify"), mission.steps.map { it.stepId })
    assertTrue(validateMissionSteps(mission.steps))
    assertTrue(mission.steps.first { it.stepId == "navigate_launch" }.requiresConfirmation)
  }

  @Test fun launchVerificationSucceedsWhenForegroundMatchesExpected() {
    val outcome = classifyLaunchVerification(setOf("com.waze"), "com.waze", pollsAttempted = 1, maxPolls = 5)
    assertTrue(outcome is ExecutionOutcome.Success)
    assertEquals("com.waze", (outcome as ExecutionOutcome.Success).evidence)
  }

  @Test fun launchVerificationStaysUnverifiedWhilePollsRemain() {
    val outcome = classifyLaunchVerification(setOf("com.waze"), null, pollsAttempted = 2, maxPolls = 5)
    assertTrue(outcome is ExecutionOutcome.Unverified)
  }

  @Test fun launchVerificationFailsAfterPollsExhausted() {
    val outcome = classifyLaunchVerification(setOf("com.waze"), null, pollsAttempted = 5, maxPolls = 5)
    assertTrue(outcome is ExecutionOutcome.Failed)
    assertEquals("launch_unverified", (outcome as ExecutionOutcome.Failed).reason)
  }

  @Test fun launchVerificationFailsWhenWrongAppIsForeground() {
    // ex. o alta aplicatie a ajuns in prim-plan intre timp (notificare, apel) — nu Waze.
    val outcome = classifyLaunchVerification(setOf("com.waze"), "com.whatsapp", pollsAttempted = 5, maxPolls = 5)
    assertTrue(outcome is ExecutionOutcome.Failed)
  }
}
