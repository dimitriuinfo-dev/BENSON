package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A (08.10.2026, pivot "Rareș pleacă ACUM cu telefonul") — "cod + teste pe fixture-urile de la
 * pasul 2". AppNameMatcher și findLaunchablePackage (BensonForegroundService) erau deja corecte
 * și dovedite pe dispozitiv (CLAUDE.md, 20.09/04.10) — nimic de rescris. Ce lipsea: o dovadă că
 * "best score wins" rezolvă corect ȚINTA dintre toate cele 664 de aplicații REALE instalate pe
 * telefonul lui Rareș (test/fixtures/device/packages_all.txt, capturat chiar azi, 2 minute înainte
 * să plece telefonul), nu doar dintr-o listă mică, aleasă de mine. Etichetele (nume afișate) nu au
 * fost capturate azi (doar pachetele brute) — gap cunoscut, notat în DAY_REPORT. Pentru pachetele
 * cunoscute public (YouTube, Spotify, WhatsApp, Chrome, calculatorul OnePlus, Play Store) folosesc
 * eticheta reală, de notorietate; pentru restul (zgomotul competitiv, 650+ pachete), o etichetă
 * aproximativă din ultimul segment al pachetului — suficient ca "zgomot" ca să testeze dacă ținta
 * corectă câștigă totuși, nu ca etichetă corectă în sine.
 */
class AppLaunchFixtureTest {
  private fun fixtureDir(): File {
    // CWD at test time varies by how gradlew is invoked (module dir, android/ project root, or
    // repo root) — try all three rather than guess one.
    val candidates = listOf(
      "../../../test/fixtures/device", // CWD = modules/benson-foreground-service/android
      "../test/fixtures/device", // CWD = android/ (prebuild project root)
      "test/fixtures/device", // CWD = repo root
    )
    val dir = candidates.map { File(it) }.firstOrNull { it.isDirectory }
    check(dir != null) { "fixture dir not found from ${File(".").absolutePath}" }
    return dir
  }

  private fun loadPackages(filename: String): List<String> =
    File(fixtureDir(), filename).readLines()
      .mapNotNull { it.removePrefix("package:").trim().takeIf { p -> p.isNotBlank() } }

  private val KNOWN_LABELS = mapOf(
    "com.google.android.youtube" to "youtube",
    "com.spotify.music" to "spotify",
    "com.whatsapp" to "whatsapp",
    "com.android.chrome" to "chrome",
    "com.oneplus.calculator" to "calculator",
    "com.android.settings" to "setari",
    "com.android.vending" to "play store",
    "magicfm.app" to "magic fm",
  )

  // Weak proxy label for the competitive-noise packages — NOT a real display name, just enough
  // text for AppNameMatcher.score to produce a (usually low/no) score against it.
  private fun proxyLabel(pkg: String): String =
    KNOWN_LABELS[pkg] ?: pkg.substringAfterLast('.').lowercase().replace(Regex("[^a-z]"), " ").trim()

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

  // Mirrors findLaunchablePackage's loop exactly (best score wins, first seen on ties) — pure,
  // so it's testable without a PackageManager/Context.
  private fun bestMatch(target: String, packages: List<String>): String? {
    var best: String? = null
    var bestScore = -1
    for (pkg in packages) {
      val score = AppNameMatcher.score(target, proxyLabel(pkg), ::lev)
      if (score > bestScore) { bestScore = score; best = pkg }
    }
    return if (bestScore >= 0) best else null
  }

  @Test fun fixtureHasBensonsOwnPackage() {
    assertTrue(loadPackages("packages_user.txt").contains("com.benson.butler"))
  }

  @Test fun fixtureHasAllExpectedKnownApps() {
    val all = loadPackages("packages_all.txt").toSet()
    for (pkg in KNOWN_LABELS.keys) {
      assertTrue("$pkg missing from the real packages_all.txt captured today", all.contains(pkg))
    }
  }

  @Test fun youtubeWinsAgainstFullRealFieldOf664() {
    val all = loadPackages("packages_all.txt")
    assertEquals("com.google.android.youtube", bestMatch("youtube", all))
  }

  @Test fun spotifyWinsAgainstFullRealField() {
    val all = loadPackages("packages_all.txt")
    assertEquals("com.spotify.music", bestMatch("spotify", all))
  }

  @Test fun whatsappWinsAgainstFullRealField() {
    val all = loadPackages("packages_all.txt")
    assertEquals("com.whatsapp", bestMatch("whatsapp", all))
  }

  @Test fun chromeWinsAgainstFullRealField() {
    val all = loadPackages("packages_all.txt")
    assertEquals("com.android.chrome", bestMatch("chrome", all))
  }

  @Test fun calculatorulWinsAgainstFullRealField_oneplusNotAospId() {
    val all = loadPackages("packages_all.txt")
    // CALC1 (04.10) targets com.oneplus.calculator specifically — AOSP's com.android.calculator2
    // isn't even installed on this device (confirmed absent from packages_all.txt today).
    assertTrue(!all.contains("com.android.calculator2"))
    assertEquals("com.oneplus.calculator", bestMatch("calculatorul", all))
  }

  @Test fun magicFmMistranscriptionStillWinsAgainstFullRealField() {
    // FIX_APP_NAME_ACRONYM_1 (04.10) real device failure, now against the real competitive field.
    val all = loadPackages("packages_all.txt")
    assertEquals("magicfm.app", bestMatch("magica fam", all))
  }
}
