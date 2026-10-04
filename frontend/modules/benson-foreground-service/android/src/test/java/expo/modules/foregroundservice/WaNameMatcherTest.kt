package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaNameMatcherTest {
  private fun lev(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
      dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
      else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
    }
    return dp[a.length][b.length]
  }

  // Device-proven bug: "hanei" (dative "to Hannah") scored worse than an unrelated contact against
  // "hannah" using plain Levenshtein alone. The stripped variant ("han") must win with a low score.
  @Test fun hanneiResolvesWellAgainstHannah() {
    val score = WaNameMatcher.bestScore("hanei", "hannah", ::lev)
    assertTrue("expected a strong (low) score, got $score", score <= 1)
  }

  @Test fun unrelatedNameScoresWorseThanHannah() {
    val hanneiScore = WaNameMatcher.bestScore("hanei", "hannah", ::lev)
    val unrelatedScore = WaNameMatcher.bestScore("hanei", "andrei", ::lev)
    assertTrue("Hannah ($hanneiScore) must beat an unrelated contact ($unrelatedScore)", hanneiScore < unrelatedScore)
  }

  @Test fun exactMatchStillScoresZero() = assertEquals(0, WaNameMatcher.bestScore("hannah", "hannah", ::lev))

  @Test fun noSuffixNoVariantsAdded() = assertEquals(listOf("mihai"), WaNameMatcher.targetVariants("mihai"))

  @Test fun suffixStrippedOnlyWhenRemainderLongEnough() {
    // "ii" stripped from "ii" itself would leave "" (too short) — must not produce a blank variant.
    assertEquals(listOf("ii"), WaNameMatcher.targetVariants("ii"))
  }

  @Test fun stripsEiSuffix() = assertTrue(WaNameMatcher.targetVariants("mariei").contains("mari"))

  // FIX_WA_EMOJI_STRIP_1 (2026-10-03, device-proven) — "baby" (spoken) vs a ContactsContract
  // display name like "Baby ❤️" (emoji trailing the name) must still score an exact match once
  // both sides go through TextNormalization.stripSymbolsAndEmoji, same as BensonForegroundService's
  // normalizeForMatch does for both the target and the candidate.
  @Test fun emojiInContactNameDoesNotBreakExactMatch() {
    val candidateNorm = TextNormalization.stripSymbolsAndEmoji("baby ❤").trim()
    assertEquals(0, WaNameMatcher.bestScore("baby", candidateNorm, ::lev))
  }
}
