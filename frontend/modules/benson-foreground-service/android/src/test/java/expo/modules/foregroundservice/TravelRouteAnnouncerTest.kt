package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TravelRouteAnnouncerTest {

  private fun route(
    distanceMeters: Int = 1200,
    geometrySource: RouteDataSource = RouteDataSource.STATIC_GEOMETRY_ONLY,
    etaMinutes: Int? = null,
    etaSource: RouteDataSource = RouteDataSource.STATIC_GEOMETRY_ONLY,
    trafficAware: Boolean = false,
  ) = RouteInfo(distanceMeters, geometrySource, etaMinutes, etaSource, trafficAware)

  @Test fun announceRouteIsNullWhenRouteIsNull() {
    assertNull(announceRoute(null))
  }

  @Test fun announceRouteIsNullWhenTrafficClaimedWithoutLiveSource() {
    val dishonest = route(trafficAware = true, etaSource = RouteDataSource.HISTORICAL_ESTIMATE, etaMinutes = 10)
    assertNull(announceRoute(dishonest))
  }

  @Test fun announceRouteUsesMetersBelowOneKilometer() {
    assertEquals("800 m", announceRoute(route(distanceMeters = 800)))
  }

  @Test fun announceRouteUsesKilometersAtOrAboveOneKilometer() {
    assertEquals("1.2 km", announceRoute(route(distanceMeters = 1200)))
  }

  @Test fun announceRouteOmitsEtaWhenMissing() {
    val result = announceRoute(route(etaMinutes = null))
    assertEquals(false, result!!.contains("minute"))
  }

  @Test fun announceRouteIncludesEtaWhenPresent() {
    val result = announceRoute(route(etaMinutes = 7, etaSource = RouteDataSource.HISTORICAL_ESTIMATE))
    assertEquals("1.2 km, aproximativ 7 minute", result)
  }

  @Test fun announceRouteMentionsLiveTrafficOnlyWhenHonest() {
    val honestLive = route(etaMinutes = 5, etaSource = RouteDataSource.LIVE_TRAFFIC_AWARE, trafficAware = true)
    assertEquals("1.2 km, aproximativ 5 minute (cu trafic live)", announceRoute(honestLive))
  }

  @Test fun formatRouteEvidenceIsNullWhenDishonest() {
    val dishonest = route(trafficAware = true, etaSource = RouteDataSource.STATIC_GEOMETRY_ONLY, etaMinutes = 5)
    assertNull(formatRouteEvidence(dishonest))
  }

  @Test fun formatRouteEvidenceIncludesEtaSourceWhenPresent() {
    val honest = route(etaMinutes = 7, etaSource = RouteDataSource.HISTORICAL_ESTIMATE)
    assertEquals(
      "distanceMeters=1200;trafficAware=false;etaMinutes=7;etaSource=HISTORICAL_ESTIMATE",
      formatRouteEvidence(honest),
    )
  }

  @Test fun formatRouteEvidenceOmitsEtaFieldsWhenEtaMissing() {
    assertEquals("distanceMeters=1200;trafficAware=false", formatRouteEvidence(route()))
  }
}
