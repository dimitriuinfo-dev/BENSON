package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosWatchContractsTest {

  private fun trigger(id: String = "t1", deviceId: String = "watch-001") =
    SosTrigger(id, SosTriggerSource.WATCH_BUTTON, deviceId, emittedAtMs = 1000L, heldDurationMs = 1500L)

  @Test fun unauthorizedDeviceIsRejected() {
    val authority = SosWatchTriggerAuthority(authorizedDeviceIds = setOf("watch-001"))
    assertFalse(authority.accept(trigger(deviceId = "unknown-watch")))
  }

  @Test fun authorizedDeviceFirstTriggerIsAccepted() {
    val authority = SosWatchTriggerAuthority(authorizedDeviceIds = setOf("watch-001"))
    assertTrue(authority.accept(trigger()))
  }

  @Test fun duplicateTriggerIdIsRejectedEvenFromAuthorizedDevice() {
    val authority = SosWatchTriggerAuthority(authorizedDeviceIds = setOf("watch-001"))
    assertTrue(authority.accept(trigger(id = "t1")))
    assertFalse(authority.accept(trigger(id = "t1"))) // acelasi triggerId, re-livrat de retea
  }

  @Test fun sameDeviceDifferentTriggerIdIsAcceptedAgain() {
    val authority = SosWatchTriggerAuthority(authorizedDeviceIds = setOf("watch-001"))
    assertTrue(authority.accept(trigger(id = "t1")))
    assertTrue(authority.accept(trigger(id = "t2")))
  }

  @Test fun deliveredStatusConfirmsHaptic() {
    val result = buildDeliveryResult("t1", SosDeliveryStatus.DELIVERED)
    assertTrue(result.hapticConfirmed)
  }

  @Test fun unconfirmedAndUnavailableNeverConfirmHaptic() {
    assertFalse(buildDeliveryResult("t1", SosDeliveryStatus.UNCONFIRMED).hapticConfirmed)
    assertFalse(buildDeliveryResult("t1", SosDeliveryStatus.PHONE_UNAVAILABLE).hapticConfirmed)
  }

  // --- Cele trei evenimente distincte (19.10.2026) ---

  @Test fun deliveredMapsToSosAlertDeliveredStage() {
    assertEquals(SosWatchEventStage.SOS_ALERT_DELIVERED, buildDeliveryResult("t1", SosDeliveryStatus.DELIVERED).stage)
  }

  @Test fun unconfirmedAndUnavailableBothMapToSosAlertUnknownNeverToDelivered() {
    assertEquals(SosWatchEventStage.SOS_ALERT_UNKNOWN, buildDeliveryResult("t1", SosDeliveryStatus.UNCONFIRMED).stage)
    assertEquals(SosWatchEventStage.SOS_ALERT_UNKNOWN, buildDeliveryResult("t1", SosDeliveryStatus.PHONE_UNAVAILABLE).stage)
  }

  @Test fun watchEventReceivedIsNeverProducedByDeliveryLogic() {
    // WATCH_EVENT_RECEIVED e emis la primirea evenimentului de la ceas (SosWatchTriggerAuthority),
    // NU de buildDeliveryResult - cele doua trepte nu se confunda niciodata.
    for (status in SosDeliveryStatus.entries) {
      assertFalse(buildDeliveryResult("t1", status).stage == SosWatchEventStage.WATCH_EVENT_RECEIVED)
    }
  }
}
