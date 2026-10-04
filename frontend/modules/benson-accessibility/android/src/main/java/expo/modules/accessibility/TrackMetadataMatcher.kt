package expo.modules.accessibility

// RUNDA MUSIC-1 (2026-10-04, user-directed) — port 1:1 al logicii DOVEDITE din
// src/executors/mediaSearchExecutor.ts (checkMetadataMatch/tokens/stripDiacriticsLocal), confirmată
// live acolo pentru Spotify (ROUND_SPOTIFY_SELECT_2). O playlist/album pornește de fapt prima
// piesă, al cărei titlu/artist nu e identic cu numele căutat ("Best of INNA" -> "Body and the Sun"
// / "INNA") — de-aia potrivirea e pe TOKENI comuni (>=3 litere, fără diacritice), nu pe șir exact.
// Pur, zero Context, JUnit-tested (TrackMetadataMatcherTest).
object TrackMetadataMatcher {
  private fun stripDiacritics(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase()

  private fun tokens(s: String): List<String> =
    stripDiacritics(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }

  /** True if the MediaSession's title/artist shares a real token with the selected title or the original query. */
  fun matches(sessionTitle: String?, sessionArtist: String?, selectedTitle: String, query: String?): Boolean {
    val haystack = tokens("${sessionTitle ?: ""} ${sessionArtist ?: ""}")
    if (haystack.isEmpty()) return false
    val needles = tokens(selectedTitle) + tokens(query ?: "")
    return needles.any { haystack.contains(it) }
  }
}
