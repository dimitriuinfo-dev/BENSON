package expo.modules.accessibility

// RUNDA MUSIC-1 (2026-10-04) — port al filtrului de zgomot DOVEDIT live în
// src/executors/mediaSearchExecutor.ts (CHROME_NOISE/EXACT_LABEL_NOISE/looksLikeLowercaseEcho/
// collapseDuplicatedHalf, ROUND_SPOTIFY_SELECT_2). Subset reprezentativ, nu lista exhaustivă — se
// extinde dacă un test real pe dispozitiv arată o etichetă de interfață care scapă prin filtru.
// Pur, zero Context, JUnit-tested (SearchResultNoiseFilterTest).
object SearchResultNoiseFilter {
  private val CHROME_NOISE = listOf(
    "home", "shorts", "subscriptions", "library", "notifications", "search", "cast", "account",
    "more videos", "more options", "options", "filters", "filter", "settings", "profile",
    "acasă", "abonamente", "bibliotecă", "notificări", "notificari", "cont", "distribuie", "filtre", "mai multe",
    "vorschlag", "hinzufügen", "add suggestion", "sugestie",
    "hinzugefügt", "bibliothek", "added to your library", "adăugat în bibliotecă",
    // CONFIRMED LIVE (2026-10-04) — a result row's own overflow/"..." button contentDescription
    // ("Mehr Optionen für den Song „Justify My Love"") contains the song title as a substring, the
    // exact same collision class the proven TS code solved for the Add/Save button
    // ("hinzugefügt") — same fix, new observed German phrasing for this button specifically.
    "mehr optionen", "weitere optionen", "more options for", "opțiuni pentru",
  )

  private val EXACT_LABEL_NOISE = setOf(
    "playlist", "verifiziert", "verified", "künstler*in", "künstlerin", "künstler", "artist",
    "album", "single", "podcast", "episode", "folge", "song",
  )

  private fun stripDiacritics(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

  private fun looksLikeLowercaseEcho(label: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isBlank()) return false
    val low = label.trim()
    if (low != low.lowercase()) return false
    return low.lowercase().startsWith(q)
  }

  // Faithful port of the TS regex /^(.+?)\s+\1$/i — java.util.regex supports backreferences
  // directly, no need to reimplement the split-and-compare by hand.
  private val DUPLICATED_HALF = Regex("^(.+?)\\s+\\1$", RegexOption.IGNORE_CASE)

  fun collapseDuplicatedHalf(label: String): String {
    val trimmed = label.trim()
    return DUPLICATED_HALF.find(trimmed)?.groupValues?.get(1) ?: trimmed
  }

  fun isNoise(label: String, query: String): Boolean {
    val low = label.trim().lowercase()
    if (low.isBlank()) return true
    if (EXACT_LABEL_NOISE.contains(low)) return true
    if (low == query.trim().lowercase()) return true
    if (low.contains('•') || low.contains('@')) return true
    if (CHROME_NOISE.any { low.contains(it) }) return true
    if (looksLikeLowercaseEcho(label, query)) return true
    return false
  }

  /** First non-noise, de-duplicated candidate label in on-screen order, or null. */
  fun firstCandidate(labels: List<String>, query: String): String? {
    val seen = HashSet<String>()
    for (raw in labels) {
      val label = collapseDuplicatedHalf(raw)
      if (label.length < 2 || label.length > 90) continue
      if (isNoise(label, query)) continue
      val key = label.lowercase()
      if (!seen.add(key)) continue
      return label
    }
    return null
  }
}
