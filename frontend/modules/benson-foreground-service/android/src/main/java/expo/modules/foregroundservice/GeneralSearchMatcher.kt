package expo.modules.foregroundservice

// RUNDA S-1 TASK 2 (2026-10-03, user-directed) — pure regex extraction for the generic "cauta X"
// web-search fallback (no app, no location named). Explicitly refuses phrases that belong to the
// existing, untouched nav/Maps path ("pe harta", "unde e", "du-ma la") or that name YouTube/
// Spotify — those are matched by earlier, more specific patterns in BensonForegroundService and
// must never reach here. The actual Intent/startActivity call stays inline in
// BensonForegroundService.tryNativeGeneralSearch (Context-dependent).
object GeneralSearchMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  private val PATTERN = Regex("^(?:cauta|cautam|gaseste)(?:-mi)?\\s+(.+?)\\s*$")
  private val EXCLUDE = Regex("\\b(harta|waze|unde\\s+e|du-?ma|you\\s*tube|spotify)\\b")

  fun extractQuery(rawCommand: String): String? {
    val norm = normalize(rawCommand)
    if (EXCLUDE.containsMatchIn(norm)) return null
    return PATTERN.find(norm)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
  }
}
