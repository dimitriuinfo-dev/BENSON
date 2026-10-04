package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTtsGuardTest {
  @Test fun ignoresWhileSpeaking() =
    assertTrue(SelfTtsGuard.isWithinGuard(nowMs = 50_000, ttsSpeakingSince = 49_500, ttsLastDoneAt = 0))

  @Test fun ignores109msAfterDone() =
    assertTrue(SelfTtsGuard.isWithinGuard(nowMs = 50_109, ttsSpeakingSince = 0, ttsLastDoneAt = 50_000))

  @Test fun ignores999msAfterDone() =
    assertTrue(SelfTtsGuard.isWithinGuard(nowMs = 50_999, ttsSpeakingSince = 0, ttsLastDoneAt = 50_000))

  @Test fun acceptsAt1000msAfterDone() =
    assertFalse(SelfTtsGuard.isWithinGuard(nowMs = 51_000, ttsSpeakingSince = 0, ttsLastDoneAt = 50_000))

  @Test fun acceptsWellAfterDone() =
    assertFalse(SelfTtsGuard.isWithinGuard(nowMs = 55_000, ttsSpeakingSince = 0, ttsLastDoneAt = 50_000))

  @Test fun acceptsWhenNeverSpoken() =
    assertFalse(SelfTtsGuard.isWithinGuard(nowMs = 50_000, ttsSpeakingSince = 0, ttsLastDoneAt = 0))
}
