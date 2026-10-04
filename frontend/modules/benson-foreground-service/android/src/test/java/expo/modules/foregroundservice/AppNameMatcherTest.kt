package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameMatcherTest {
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

  @Test fun collapsesBareLetterRun() = assertEquals("magic fm", AppNameMatcher.collapseAcronyms("magic f m"))
  @Test fun collapsesSpelledLetterRun() = assertEquals("magic fm", AppNameMatcher.collapseAcronyms("magic ef em"))
  @Test fun leavesAlreadyGluedAcronymAlone() = assertEquals("magicfm", AppNameMatcher.collapseAcronyms("magicfm"))
  @Test fun leavesPlainWordsAlone() = assertEquals("magic fm", AppNameMatcher.collapseAcronyms("magic fm"))

  @Test fun magicFSpaceMMatchesMagicFm() =
    assertTrue(AppNameMatcher.score("magic f m", "magic fm", ::lev) >= 0)

  @Test fun magicEfEmMatchesMagicFm() =
    assertTrue(AppNameMatcher.score("magic ef em", "magic fm", ::lev) >= 0)

  @Test fun magicfmGluedMatchesMagicFm() =
    assertTrue(AppNameMatcher.score("magicfm", "magic fm", ::lev) >= 0)

  // normalizeForMatch already strips the trailing period before this is ever called — simulated
  // here directly since that step lives in BensonForegroundService, not this pure object.
  @Test fun magicFmPeriodStrippedUpstreamMatchesExactly() =
    assertEquals(100, AppNameMatcher.score("magic fm", "magic fm", ::lev))

  // The real device-failing transcript (2026-10-04): Deepgram heard "Magica Fam" for "Magic FM".
  @Test fun realFailingTranscriptMagicaFam() =
    assertTrue(AppNameMatcher.score("magica fam", "magic fm", ::lev) >= 0)

  @Test fun unrelatedAppScoresLower() {
    val magicFmScore = AppNameMatcher.score("magica fam", "magic fm", ::lev)
    val unrelatedScore = AppNameMatcher.score("magica fam", "whatsapp", ::lev)
    assertTrue("Magic FM ($magicFmScore) must beat an unrelated app ($unrelatedScore)", magicFmScore > unrelatedScore)
  }

  @Test fun tooShortTargetNeverFuzzyMatches() = assertEquals(-1, AppNameMatcher.score("xz", "magic fm", ::lev))
}
