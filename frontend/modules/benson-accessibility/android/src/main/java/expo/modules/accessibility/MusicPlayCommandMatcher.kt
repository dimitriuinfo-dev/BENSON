package expo.modules.accessibility

// RUNDA MUSIC-1 (2026-10-04, user-directed) — "pune X" / "cântă X" / "caută X" (în aplicații de
// muzică) → caută + selectează primul rezultat de tip piesă + play, verificat prin MediaSession.
// Doar extragerea verbului + interogării e pură; decizia DE APLICARE a verbului "cauta" (muzică:
// da; YouTube video: nu, rămâne doar căutare) se ia în BensonForegroundService.tryMusicPlay, unde
// se știe ce aplicație e în prim-plan.
enum class MusicPlayVerb { PUNE, CANTA, CAUTA }

data class MusicPlayCommand(val verb: MusicPlayVerb, val query: String)

object MusicPlayCommandMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;\"'«»]"), "")
      .lowercase().trim()

  private val PATTERN = Regex("^(pune|canta|cauta)(?:-mi)?\\s+(.+?)$")

  fun parse(rawCommand: String): MusicPlayCommand? {
    val m = PATTERN.find(normalize(rawCommand)) ?: return null
    // FIX_SEARCH_TARGET_APP_MENTION_1 (2026-10-04, device-proven) — "pune madonna pe spotify" kept
    // "pe spotify" in the query, so the on-screen search found nothing. Shared stripper, same as
    // tryContextSearch's leading-form case.
    val query = AppMentionStripper.strip(m.groupValues[2].trim())
    if (query.isBlank()) return null
    val verb = when (m.groupValues[1]) { "pune" -> MusicPlayVerb.PUNE; "canta" -> MusicPlayVerb.CANTA; else -> MusicPlayVerb.CAUTA }
    return MusicPlayCommand(verb, query)
  }
}
