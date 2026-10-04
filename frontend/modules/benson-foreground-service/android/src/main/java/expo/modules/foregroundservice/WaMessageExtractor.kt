package expo.modules.foregroundservice

// ADAOS WA-3 / WA_COMPOSE (2026-10-03, user-directed) — "întreabă-o pe Hannah dacă vine diseară"
// captures an INSTRUCTION (handed whole to composeWaMessage/the brain, which turns it into the
// actual message "Vii diseară?"), not literal text like the scrie-i/trimite-i patterns do.
//
// FIX_WA_NORMALIZE_BEFORE_MATCH_1 (2026-10-03, device-proven regression) — the real failing
// transcript was "benson întreabă pe hana când vine de seară": no "-o"/"-l" clitic at all (bare
// "întreabă pe"), and Deepgram substituted "când" for "dacă" — neither was accepted before. Fixed
// by (a) normalizing internally (diacritics/case/punctuation) so every call site can pass the raw
// transcript as-is, (b) making the clitic fully optional and accepting either a hyphen or a bare
// space before it ("întreab-o" / "întreabă o" / "întreabă pe" all now match), and (c) widening the
// connector-word set to the actual closed class of Romanian interrogatives ("dacă" is one of
// several STT can plausibly substitute one for another within — "ce", "când", "unde", "cum",
// "cine" — not just the one word that happened to be said correctly).
// Pure, zero Context dependency, JUnit-tested (WaMessageExtractorTest).
object WaMessageExtractor {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  private const val SUFFIX = "(?:\\s+(?:pe|in)\\s+whats\\s*app)?"
  private val ASK_INSTRUCTION_PATTERN = Regex(
    "intreab[a]?(?:[\\s-]?[ol])?\\s+pe\\s+(.+?)\\s+((?:daca|ce|cand|unde|cum|cine)\\s+.+?)$SUFFIX\\s*$",
  )

  /** recipient and instruction both come back normalized (lowercase, diacritic/punctuation-free). */
  data class Extraction(val recipient: String, val instruction: String)

  fun extractAskInstruction(rawCommand: String): Extraction? {
    val command = normalize(rawCommand)
    return ASK_INSTRUCTION_PATTERN.find(command)?.let { Extraction(it.groupValues[1].trim(), it.groupValues[2].trim()) }
  }

  // WA_COMPOSE's brain-unavailable (timeout/no key) fallback: the raw (smart_format-punctuated)
  // Deepgram text, verbatim — a draft must never be blocked waiting on the brain.
  fun selectFinalMessage(composed: String?, rawSttText: String): String = composed ?: rawSttText
}
