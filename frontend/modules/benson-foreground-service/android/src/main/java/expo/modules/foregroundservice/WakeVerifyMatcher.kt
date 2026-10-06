package expo.modules.foregroundservice

// Extracted from BensonForegroundService.onHeedWakeDetected's isWakeVerified (RUNDA_WAKE_VERIFY,
// FIX_WAKE_VERIFY_FAILSAFE_1, FIX_WAKE_VERIFY_FUZZY_1, FIX_WAKE_VERIFY_KEYTERM_1 — 2026-10-03) —
// zero Android Context dependency, so it can be covered by a plain JUnit test
// (WakeVerifyMatcherTest) instead of an instrumented/device test. BensonForegroundService
// delegates here, passing NativeCloudWake.currentWakeName(context) — the one authoritative wake-
// name source already used by HeedWakeWord.modelPresent — so a non-default wake name (Settings)
// is matched correctly instead of hardcoded "Benson".
object WakeVerifyMatcher {
  // Hand-tuned Deepgram-confusion variants for the DEFAULT wake name only. Applied only when the
  // configured name actually normalizes to "benson" — a different configured name (e.g. "Toma")
  // must not inherit Benson's own STT quirks.
  // RUNDA AC2-F, Pasul 1b (07.10.2026, product-owner-directed) — "penson"/"venson" adăugate
  // explicit: consoanele inițiale p/v sunt confuzii acustice reale cu b pe un cuvânt izolat, fără
  // context de propoziție, dar NU treceau de verificarea `word[0] == firstLetter` de mai jos
  // (firstLetter fiind 'b') — respinse înainte să ajungă la Levenshtein, indiferent de distanță.
  // Whitelist explicit, nu relaxare a regulii generale de literă inițială (care rămâne garda
  // principală împotriva alarmelor false pe alte cuvinte). Revert: scoate "penson"/"venson".
  private val BENSON_KNOWN_VARIANTS = setOf("benson", "bensan", "benzon", "bensen", "bension", "penson", "venson")

  // FIX_WAKE_VERIFY_KEYTERM_1 (2026-10-03, user-directed) — distance<=3 (no other constraint)
  // accepted "pension"/"person"/"benzin" as well as "bandswon": too loose. Tightened to distance
  // <=2, same first letter as the target name, and an explicit exclusion list for known
  // close-but-wrong words that land within that distance anyway (e.g. "benzin" is exactly
  // distance 2 from "benson"). The Deepgram `keyterm` request param (wakeVerifyTranscribe) now
  // does the real work of making STT write the configured name correctly — this matcher only
  // guards the exact-whitelist miss case, deliberately NOT "bandswon" anymore (that was papering
  // over the STT error, not fixing it).
  private val EXCLUDE_WORDS = setOf("benzin", "person", "pension", "pensie", "bonus", "besser", "bitte", "banca")

  fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

  fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
      dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
      else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
    }
    return dp[a.length][b.length]
  }

  // RUNDA AC2-F, Pasul 1c (07.10.2026, product-owner-directed) — un transcript gol ONLINE (cazul
  // #14 din testul Pasul 1b: scor 0,957, conexiune ok, dar text="") înseamnă exact același lucru
  // ca offline-ul: verificarea nu a spus nimic concret, nici da nici nu. Tratate identic — decide
  // scorul local. Un transcript NEGOL dar care nu se potrivește cu numele de trezire (vorbire
  // reală, altceva decât „Benson") NU intră aici — rămâne respins necondiționat mai jos, filtrul
  // de alarme false neatins.
  fun needsScoreFallback(transcript: String?): Boolean = transcript == null || transcript.isBlank()

  fun isWakeVerified(transcript: String?, wakeName: String = "Benson"): Boolean {
    if (transcript == null || transcript.isBlank()) return false
    val norm = normalize(transcript)
    val target = normalize(wakeName)
    if (target.isBlank()) return false
    if (target == "benson" && BENSON_KNOWN_VARIANTS.any { norm.contains(it) }) return true
    val firstLetter = target[0]
    return norm.split(Regex("\\s+")).any { word ->
      word.length >= 4 && word[0] == firstLetter && word !in EXCLUDE_WORDS &&
        levenshtein(word, target) <= 2
    }
  }
}
