package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class CallCleanupDecisionTest {
  @Test fun restoresOtherAppWhenUntouched() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = true, interactedSinceEnd = false,
      prevPackage = "com.spotify.music", launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.RESTORE, action)
  }

  @Test fun goesHomeWhenPrevWasLauncher() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = true, interactedSinceEnd = false,
      prevPackage = "com.oplus.launcher", launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.HOME, action)
  }

  @Test fun goesHomeWhenPrevUnknown() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = true, interactedSinceEnd = false,
      prevPackage = null, launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.HOME, action)
  }

  @Test fun goesHomeWhenPrevWasBensonItself() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = true, interactedSinceEnd = false,
      prevPackage = "com.benson.butler", launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.HOME, action)
  }

  @Test fun skipsWhenUserTouchedScreen() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = true, interactedSinceEnd = true,
      prevPackage = "com.spotify.music", launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.SKIP, action)
  }

  @Test fun skipsWhenWhatsAppAlreadyLeftForeground() {
    val action = CallCleanupDecision.decide(
      whatsappStillForeground = false, interactedSinceEnd = false,
      prevPackage = "com.spotify.music", launcherPackage = "com.oplus.launcher", ownPackage = "com.benson.butler",
    )
    assertEquals(CallCleanupDecision.Action.SKIP, action)
  }
}
