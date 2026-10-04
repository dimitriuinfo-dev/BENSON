package expo.modules.accessibility

// FIX_SEARCH_TARGET_APP_MENTION_1 (2026-10-04, device-proven on highway) — one shared stripper for
// every place a search/play target gets extracted, instead of each parser inventing its own rule.
// Two real bugs, same cause: "pune madonna pe spotify" (MusicPlayCommandMatcher) kept "pe spotify"
// in the query, so nothing matched on screen; "caută în youtube george michael" (tryContextSearch)
// kept "în youtube" IN FRONT of the query, same effect. Mentions can land on either side of the
// core query, so both are stripped here, in that order (longer app names checked before shorter
// ones that are their own prefix — "youtube music" before "youtube").
object AppMentionStripper {
  private val APP_NAMES = listOf("youtube music", "youtube", "spotify")
  private const val PREP = "(?:pe|in|în)"

  fun strip(query: String): String {
    var q = query.trim()
    for (app in APP_NAMES) {
      val trailing = Regex("^(.*?)\\s+$PREP\\s+${Regex.escape(app)}\\s*$", RegexOption.IGNORE_CASE)
      val m = trailing.find(q)
      if (m != null) { q = m.groupValues[1].trim(); break }
    }
    for (app in APP_NAMES) {
      val leading = Regex("^$PREP\\s+${Regex.escape(app)}\\s+(.+)$", RegexOption.IGNORE_CASE)
      val m = leading.find(q)
      if (m != null) { q = m.groupValues[1].trim(); break }
    }
    return q
  }
}
