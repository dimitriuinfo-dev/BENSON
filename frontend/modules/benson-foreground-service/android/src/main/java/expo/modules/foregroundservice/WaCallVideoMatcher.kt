package expo.modules.foregroundservice

// FIX_WA_NORMALIZE_BEFORE_MATCH_1 (2026-10-03, user-directed, device-proven) — normalizes
// internally (diacritics stripped via NFD, lowercase, punctuation removed) before matching, so
// every call site can pass the raw transcript as-is — no more manual [ăa]/[îi] alternations or
// (?i) scattered through every pattern, and smart_format's new punctuation (commas, periods) can
// never land inside a non-greedy capture group. Pure, zero Context dependency, JUnit-tested
// (WaCallVideoMatcherTest).
object WaCallVideoMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  private const val SUFFIX = "(?:\\s+(?:pe|in)\\s+whats\\s*app)?"

  // "sun" clitic may be attached with a hyphen OR a bare space (STT doesn't reliably insert the
  // hyphen), or absent entirely ("suna pe X" / "suna la X").
  private const val SUN_PREFIX = "sun[a]?(?:[\\s-]?[ol])?"

  // Checked in this order (first match wins) so a call-shaped phrase that also mentions "video"
  // classifies as video, not as a call with "video" corrupted into the name ("Hannah video").
  private val VIDEO_PATTERNS = listOf(
    Regex("(?:fa\\s+un\\s+)?video\\s*(?:call)?\\s+cu\\s+(.+?)$SUFFIX\\s*$"), // "video cu X" / "videocall cu X" / "fa un video cu X"
    Regex("apel\\s+video\\s+cu\\s+(.+?)$SUFFIX\\s*$"), // "apel video cu X"
    Regex("$SUN_PREFIX\\s+pe\\s+(.+?)\\s+(?:pe\\s+)?video$SUFFIX\\s*$"), // "sun-o pe X video" / "sun-o pe X pe video"
  )
  private val CALL_PATTERN = Regex("$SUN_PREFIX\\s+(?:pe|la)\\s+(.+?)$SUFFIX\\s*$")

  /** Returns ("video"|"call", nameRaw) or null if neither matches. nameRaw comes back normalized. */
  fun classify(rawCommand: String): Pair<String, String>? {
    val command = normalize(rawCommand)
    VIDEO_PATTERNS.firstNotNullOfOrNull { it.find(command) }?.let { return "video" to it.groupValues[1].trim() }
    CALL_PATTERN.find(command)?.let { return "call" to it.groupValues[1].trim() }
    return null
  }
}
