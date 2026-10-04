package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeVerifyMatcherTest {
  @Test fun acceptsBenson() = assertTrue(WakeVerifyMatcher.isWakeVerified("benson"))
  // FIX_WAKE_VERIFY_KEYTERM_1 (2026-10-03) — was acceptsBandswonSttNoise (distance 3, accepted).
  // keyterm=Benson fixes the STT output itself; the matcher no longer papers over the miss.
  @Test fun rejectsBandswon() = assertFalse(WakeVerifyMatcher.isWakeVerified("bandswon"))
  @Test fun rejectsPension() = assertFalse(WakeVerifyMatcher.isWakeVerified("pension"))
  @Test fun rejectsPensie() = assertFalse(WakeVerifyMatcher.isWakeVerified("pensie"))
  @Test fun rejectsPerson() = assertFalse(WakeVerifyMatcher.isWakeVerified("person"))
  @Test fun rejectsBenzin() = assertFalse(WakeVerifyMatcher.isWakeVerified("benzin"))
  @Test fun rejectsEmpty() = assertFalse(WakeVerifyMatcher.isWakeVerified(""))
  @Test fun rejectsNull() = assertFalse(WakeVerifyMatcher.isWakeVerified(null))

  // Configured wake name is NOT hardcoded "Benson" — comparison target follows Settings.
  @Test fun acceptsConfiguredNameToma() = assertTrue(WakeVerifyMatcher.isWakeVerified("toma", "Toma"))
  @Test fun rejectsBensonWhenConfiguredNameIsToma() = assertFalse(WakeVerifyMatcher.isWakeVerified("benson", "Toma"))
}
