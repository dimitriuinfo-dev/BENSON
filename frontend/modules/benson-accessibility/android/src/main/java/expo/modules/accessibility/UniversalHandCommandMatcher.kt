package expo.modules.accessibility

// RUNDA G-1 (2026-10-03, user-directed) — parses the universal-hand command phrases ("apasă pe X",
// "caută X", "mai jos/sus/stânga/dreapta", "ce scrie pe ecran") into a typed command. Pure, zero
// Context dependency, JUnit-tested (UniversalHandCommandMatcherTest). The actual node search/click/
// scroll/read stays in BensonAccessibilityService (Context-dependent).
sealed class UniversalHandCommand {
  // knownIntent is non-null when `target` resolved to one of ControlSynonyms' known controls
  // (play/pause/.../end_call) — the caller should prefer ControlSynonyms.wordsFor(knownIntent) over
  // treating `target` as a literal text/description search.
  data class Press(val target: String, val knownIntent: String?) : UniversalHandCommand()
  data class Search(val query: String) : UniversalHandCommand()
  data class Scroll(val direction: String) : UniversalHandCommand() // "down" | "up" | "left" | "right"
  object ReadScreen : UniversalHandCommand()
}

object UniversalHandCommandMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;\"'«»]"), "")
      .lowercase().trim()

  private val READ_SCREEN = Regex("^ce scrie pe ecran$")
  private val SCROLL_DOWN = Regex("^mai jos$")
  private val SCROLL_UP = Regex("^mai sus$")
  private val SCROLL_LEFT = Regex("^(?:mai\\s+)?stanga$")
  private val SCROLL_RIGHT = Regex("^(?:mai\\s+)?dreapta$")
  private val SEARCH_PATTERN = Regex("^cauta(?:-mi)?\\s+(.+?)$")
  private val PRESS_PATTERN = Regex("^apasa\\s+(?:pe\\s+)?(.+?)$")

  // Reverse map word -> intent, built from every ControlSynonyms intent EXCEPT end_call — end_call
  // needs explicit "apel"/"call"-context resolution (see resolveKnownIntent), not a naive reverse
  // lookup, since it deliberately shares words ("inchide") with "close".
  private val KNOWN_WORDS: Map<String, String> = buildMap {
    for ((intent, words) in ControlSynonyms.TABLE) {
      if (intent == "end_call") continue
      for (w in words) put(normalize(w), intent)
    }
  }

  private fun resolveKnownIntent(target: String): String? {
    if (target.contains("apel") || target == "end call" || target == "hang up" ||
      target == "auflegen" || target == "beenden"
    ) {
      if (ControlSynonyms.wordsFor("end_call").any { normalize(it) == target || target.contains(normalize(it)) }) {
        return "end_call"
      }
    }
    return KNOWN_WORDS[target]
  }

  fun parse(rawCommand: String): UniversalHandCommand? {
    val n = normalize(rawCommand)
    if (n.isBlank()) return null
    if (READ_SCREEN.matches(n)) return UniversalHandCommand.ReadScreen
    if (SCROLL_DOWN.matches(n)) return UniversalHandCommand.Scroll("down")
    if (SCROLL_UP.matches(n)) return UniversalHandCommand.Scroll("up")
    if (SCROLL_LEFT.matches(n)) return UniversalHandCommand.Scroll("left")
    if (SCROLL_RIGHT.matches(n)) return UniversalHandCommand.Scroll("right")
    SEARCH_PATTERN.find(n)?.let {
      val q = it.groupValues[1].trim()
      if (q.isNotEmpty()) return UniversalHandCommand.Search(q)
    }
    PRESS_PATTERN.find(n)?.let {
      val target = it.groupValues[1].trim()
      if (target.isNotEmpty()) return UniversalHandCommand.Press(target, resolveKnownIntent(target))
    }
    return null
  }
}
