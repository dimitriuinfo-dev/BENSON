package expo.modules.foregroundservice

// FIX_APP_NAME_ACRONYM_1 (2026-10-04, user-directed, device-proven) — "deschide Magic FM" failed:
// Deepgram transcribed it as "Magica Fam" (STT artifact), and findLaunchablePackage's matching had
// zero fuzzy tolerance (exact/startsWith/contains only) — "magica fam" never scored against "magic
// fm" at any tier (log: NATIVE_ROUTE action=open_app target="magica fam" -> APP_LAUNCH pkg=none
// ok=false reason=not_found). Two fixes, both needed: (1) collapse spelled-out/isolated-letter
// acronyms ("f m" / "ef em") into one contiguous token on BOTH sides before comparing, so "magic f
// m" / "magic ef em" / "magicfm" all normalize the same as "magic fm"; (2) a Levenshtein fallback
// tier (same threshold convention as WaNameMatcher/findWaContacts) for mis-transcriptions that
// aren't a clean acronym split, like "magica fam" itself. Inputs are assumed already run through
// BensonForegroundService.normalizeForMatch (lowercase, no punctuation) — this only adds the
// acronym/fuzzy layer on top. Pure, zero Context dependency, JUnit-tested (AppNameMatcherTest).
object AppNameMatcher {
  // Romanian spelled-letter names for single letters STT may transcribe as a whole word instead of
  // the bare letter ("ef" for "f", "em" for "m"). Only entries actually needed for acronyms like
  // "FM"/"BBC" are listed — extend as real device failures surface new ones.
  private val LETTER_WORDS = mapOf(
    "ef" to "f", "em" to "m", "en" to "n", "be" to "b", "ce" to "c", "de" to "d", "ge" to "g",
    "el" to "l", "er" to "r", "es" to "s", "te" to "t", "ve" to "v",
  )

  fun collapseAcronyms(s: String): String {
    val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = StringBuilder()
    var run = StringBuilder()
    fun flushRun() {
      if (run.isNotEmpty()) {
        if (out.isNotEmpty()) out.append(' ')
        out.append(run)
        run = StringBuilder()
      }
    }
    for (w in words) {
      val letter = if (w.length == 1) w else LETTER_WORDS[w]
      if (letter != null) {
        run.append(letter)
      } else {
        flushRun()
        if (out.isNotEmpty()) out.append(' ')
        out.append(w)
      }
    }
    flushRun()
    return out.toString().trim()
  }

  private fun scoreStructural(target: String, candidate: String): Int = when {
    candidate == target -> 100
    candidate.startsWith(target) -> 80
    candidate.contains(target) -> 60
    target.length >= 3 && target.contains(candidate) -> 50
    else -> -1
  }

  /** Higher is better; -1 means no match at all (neither structural nor fuzzy). */
  fun score(target: String, candidateLabel: String, levenshtein: (String, String) -> Int): Int {
    val t = collapseAcronyms(target)
    val c = collapseAcronyms(candidateLabel)
    val structural = scoreStructural(t, c)
    if (structural >= 0) return structural
    if (t.length < 3) return -1
    val threshold = maxOf(2, t.length / 3)
    val dist = levenshtein(c, t)
    return if (dist <= threshold) threshold - dist else -1
  }
}
