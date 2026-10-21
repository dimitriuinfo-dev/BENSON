package expo.modules.foregroundservice

import expo.modules.accessibility.PoiSearchVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelPoiRecoveryTest {

  @Test fun canTryNewRouteUpToThreeDistinctRoutes() {
    assertTrue(canTryNewRoute(RouteAttemptBudget(distinctRoutesTried = 0)))
    assertTrue(canTryNewRoute(RouteAttemptBudget(distinctRoutesTried = 2)))
    assertFalse(canTryNewRoute(RouteAttemptBudget(distinctRoutesTried = 3)))
  }

  @Test fun canTryNewRouteStopsAtTotalAttemptBudgetEvenWithRoutesLeft() {
    assertFalse(canTryNewRoute(RouteAttemptBudget(distinctRoutesTried = 1, totalAttempts = 6)))
  }

  @Test fun canRetrySameRouteRequiresAnObservableReason() {
    assertFalse(canRetrySameRoute(RouteAttemptBudget(), observableReasonToRetry = false))
    assertTrue(canRetrySameRoute(RouteAttemptBudget(), observableReasonToRetry = true))
  }

  @Test fun canRetrySameRouteAllowsOnlyOneRetry() {
    assertTrue(canRetrySameRoute(RouteAttemptBudget(retriesOnCurrentRoute = 0), observableReasonToRetry = true))
    assertFalse(canRetrySameRoute(RouteAttemptBudget(retriesOnCurrentRoute = 1), observableReasonToRetry = true))
  }

  @Test fun onlySearchFailedAbandonsARoutePermanently() {
    assertTrue(shouldAbandonRoutePermanently(PoiSearchVerdict.SEARCH_FAILED))
    assertFalse(shouldAbandonRoutePermanently(PoiSearchVerdict.SEARCH_UNVERIFIED))
    assertFalse(shouldAbandonRoutePermanently(PoiSearchVerdict.SEARCH_SUCCESS_EMPTY))
    assertFalse(shouldAbandonRoutePermanently(PoiSearchVerdict.SEARCH_SUCCESS_RESULTS))
  }

  @Test fun budgetAdvancesCorrectlyAcrossNewRoutesAndRetries() {
    var budget = RouteAttemptBudget()
    budget = nextBudgetAfterNewRoute(budget)
    assertEquals(RouteAttemptBudget(distinctRoutesTried = 1, retriesOnCurrentRoute = 0, totalAttempts = 1), budget)
    budget = nextBudgetAfterRetry(budget)
    assertEquals(RouteAttemptBudget(distinctRoutesTried = 1, retriesOnCurrentRoute = 1, totalAttempts = 2), budget)
    budget = nextBudgetAfterNewRoute(budget)
    assertEquals(RouteAttemptBudget(distinctRoutesTried = 2, retriesOnCurrentRoute = 0, totalAttempts = 3), budget)
  }
}
