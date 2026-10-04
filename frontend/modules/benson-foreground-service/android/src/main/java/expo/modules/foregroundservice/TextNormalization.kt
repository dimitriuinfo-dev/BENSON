package expo.modules.foregroundservice

// FIX_WA_EMOJI_STRIP_1 (2026-10-03, user-directed) — contact display names can carry emoji/
// symbols ("Baby ❤️"); stripped here so matching never depends on an emoji's position lining up
// with startsWith/contains luck. Called from BensonForegroundService.normalizeForMatch, which both
// the spoken target AND the ContactsContract display name pass through — one fix, both sides.
// Pure, zero Context dependency, JUnit-tested (TextNormalizationTest).
object TextNormalization {
  private val SYMBOLS_AND_EMOJI = Regex("[\\p{So}\\p{Sk}\\p{Cf}]+")

  fun stripSymbolsAndEmoji(s: String): String = s.replace(SYMBOLS_AND_EMOJI, "")
}
