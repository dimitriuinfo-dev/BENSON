package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTriggerGateTest {

  private fun gate() = SosTriggerGate(SosTriggerConfig(threshold = 0.65f, consecutiveFrames = 2, refractoryMs = 700L))

  @Test fun singleFrameAboveThresholdDoesNotTriggerAlone() {
    val g = gate()
    assertFalse(g.onScore(0.9f, nowMs = 0L))
  }

  @Test fun twoConsecutiveFramesAboveThresholdTrigger() {
    val g = gate()
    assertFalse(g.onScore(0.9f, nowMs = 0L))
    assertTrue(g.onScore(0.9f, nowMs = 100L))
  }

  @Test fun belowThresholdResetsTheCount() {
    val g = gate()
    assertFalse(g.onScore(0.9f, nowMs = 0L))
    assertFalse(g.onScore(0.1f, nowMs = 100L)) // sub prag -> resetare
    assertFalse(g.onScore(0.9f, nowMs = 200L)) // trebuie sa o ia de la capat
    assertTrue(g.onScore(0.9f, nowMs = 300L))
  }

  @Test fun refractoryWindowSuppressesRetriggerRightAfter() {
    val g = gate()
    g.onScore(0.9f, nowMs = 0L)
    assertTrue(g.onScore(0.9f, nowMs = 100L)) // primul declansaj
    assertFalse(g.onScore(0.9f, nowMs = 150L)) // delta=50ms, in plina refractie
    assertFalse(g.onScore(0.9f, nowMs = 700L)) // delta=600ms, tot in refractie (<700ms)
  }

  @Test fun triggersAgainAfterRefractoryWindowElapses() {
    val g = gate()
    g.onScore(0.9f, nowMs = 0L)
    assertTrue(g.onScore(0.9f, nowMs = 100L)) // declansaj, lastTriggerAtMs=100
    assertFalse(g.onScore(0.9f, nowMs = 799L)) // delta=699ms < 700ms -> inca in refractie
    assertFalse(g.onScore(0.9f, nowMs = 800L)) // delta=700ms, nu mai e in refractie; primul cadru acumulat (1/2)
    assertTrue(g.onScore(0.9f, nowMs = 900L)) // al doilea cadru consecutiv -> declansare noua
  }

  @Test fun resetClearsPendingCountAndRefractoryWindow() {
    val g = gate()
    g.onScore(0.9f, nowMs = 0L)
    g.reset()
    assertFalse(g.onScore(0.9f, nowMs = 1L)) // fara reset ar fi declansat (al doilea cadru la rand)
    assertTrue(g.onScore(0.9f, nowMs = 101L))
  }

  @Test fun exactThresholdValueDoesNotCountAsAbove() {
    // ">" strict, nu ">=" - consistent cu conventia "raw prob > threshold" din wake.json.
    val g = gate()
    assertFalse(g.onScore(0.65f, nowMs = 0L))
    assertFalse(g.onScore(0.65f, nowMs = 100L))
  }
}
