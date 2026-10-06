package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeVerifyMatcherTest {
  @Test fun acceptsBenson() = assertTrue(WakeVerifyMatcher.isWakeVerified("benson"))
  @Test fun acceptsBensen() = assertTrue(WakeVerifyMatcher.isWakeVerified("bensen"))
  @Test fun acceptsBenzon() = assertTrue(WakeVerifyMatcher.isWakeVerified("benzon"))
  // RUNDA AC2-F, Pasul 1b (07.10.2026) — "penson"/"venson" (confuzie acustică reală p/v-b pe un
  // cuvânt izolat) nu treceau de verificarea de literă inițială înainte de a ajunge la Levenshtein
  // — whitelist explicit, nu o relaxare a regulii generale (care rămâne garda pentru alte cuvinte).
  @Test fun acceptsPenson() = assertTrue(WakeVerifyMatcher.isWakeVerified("penson"))
  @Test fun acceptsVenson() = assertTrue(WakeVerifyMatcher.isWakeVerified("venson"))
  // Regresie: "pension"/"person" (cuvinte reale, distincte de "penson") rămân respinse mai jos —
  // whitelist-ul de mai sus e pe cuvântul exact "penson", nu pe orice cuvânt care începe cu p.
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

  // RUNDA AC2-F, Pasul 1c (07.10.2026) — cazul #14 din testul Pasul 1b: scor 0,957, conexiune ok
  // (nu offline), dar text="" — tratat acum identic cu offline-ul: scorul local decide, nu
  // respingere automată. Un transcript real nepotrivit (alarmă falsă) NU intră aici.
  @Test fun needsScoreFallbackForNullTranscript() = assertTrue(WakeVerifyMatcher.needsScoreFallback(null))
  @Test fun needsScoreFallbackForEmptyOnlineTranscript() = assertTrue(WakeVerifyMatcher.needsScoreFallback(""))
  @Test fun needsScoreFallbackForBlankOnlineTranscript() = assertTrue(WakeVerifyMatcher.needsScoreFallback("   "))
  @Test fun doesNotNeedScoreFallbackForRealSpeech() = assertFalse(WakeVerifyMatcher.needsScoreFallback("cum faci azi"))
  @Test fun doesNotNeedScoreFallbackForBenson() = assertFalse(WakeVerifyMatcher.needsScoreFallback("benson"))
}
