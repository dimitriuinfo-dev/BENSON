package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathGateTest {

  @Test fun eligibleOnlyWhenEveryRequiredCapabilityIsExactlyVerified() {
    assertEquals(
      FastPathVerdict.ELIGIBLE,
      evaluateFastPathEligibility(listOf(CapLevel.VERIFIED, CapLevel.VERIFIED)),
    )
  }

  @Test fun oneReverifyAmongVerifiedMakesTheWholeRouteNotVerified() {
    // regula explicita din mandat: o singura capabilitate neverificata face toata ruta ambigua/
    // incompleta — nu doar "mai putin sigura".
    assertEquals(
      FastPathVerdict.NOT_VERIFIED,
      evaluateFastPathEligibility(listOf(CapLevel.VERIFIED, CapLevel.REVERIFY)),
    )
  }

  @Test fun oneKnownAmongVerifiedMakesTheWholeRouteNotVerified() {
    assertEquals(
      FastPathVerdict.NOT_VERIFIED,
      evaluateFastPathEligibility(listOf(CapLevel.VERIFIED, CapLevel.KNOWN)),
    )
  }

  @Test fun missingCapabilityMakesTheRouteNotVerified() {
    assertEquals(
      FastPathVerdict.NOT_VERIFIED,
      evaluateFastPathEligibility(listOf(CapLevel.VERIFIED, null)),
    )
  }

  @Test fun emptyRequirementListIsIncompleteNotEligible() {
    assertEquals(FastPathVerdict.INCOMPLETE, evaluateFastPathEligibility(emptyList()))
  }

  @Test fun circuitBreakerOpensOnlyAtTwoConsecutiveFailures() {
    assertFalse(shouldDisableFastPathRoute(0))
    assertFalse(shouldDisableFastPathRoute(1))
    assertTrue(shouldDisableFastPathRoute(2))
    assertTrue(shouldDisableFastPathRoute(3))
  }

  @Test fun lateAfterCancelOnlyWhenAlreadySentAndCancelPrecedesTheResult() {
    assertTrue(isLateAfterCancel(alreadySentToExecutor = true, cancelRequestedBeforeResultArrived = true))
    assertFalse(isLateAfterCancel(alreadySentToExecutor = false, cancelRequestedBeforeResultArrived = true))
    assertFalse(isLateAfterCancel(alreadySentToExecutor = true, cancelRequestedBeforeResultArrived = false))
  }
}
