package expo.modules.foregroundservice

// FIX_WA_DECLENSION_1 (2026-10-03, device-proven) — "scriei hanei un mesaj" (Romanian dative,
// grammatically correct "to Hannah") resolved to an UNRELATED contact (WA_RESOLVE candidates=1
// chosen=A — no relation to Hannah): full-string Levenshtein against "hanei" put the real contact
// ("Hannah") outside the match threshold, while a short unrelated name fell inside it by chance.
// The project's own original WA-1 spec explicitly required "Hannei" -> Hannah. Stripping common
// Romanian dative/genitive suffixes ("ei", "ii") and re-scoring against the stripped form too
// fixes this: "hanei" stripped to "han" is a clean prefix of "hannah" (score 1, startsWith),
// whereas the unstripped "hanei" vs "hannah" was already past the edit-distance threshold. Pure
// logic, zero Context dependency, JUnit-tested (WaNameMatcherTest) independent of ContactsContract.
object WaNameMatcher {
  private val DECLENSION_SUFFIXES = listOf("ei", "ii")

  fun targetVariants(normalizedTarget: String): List<String> {
    val variants = mutableListOf(normalizedTarget)
    for (suffix in DECLENSION_SUFFIXES) {
      if (normalizedTarget.endsWith(suffix) && normalizedTarget.length - suffix.length >= 3) {
        variants.add(normalizedTarget.removeSuffix(suffix))
      }
    }
    return variants.distinct()
  }

  private fun scoreOne(target: String, candidateNorm: String, levenshtein: (String, String) -> Int): Int = when {
    candidateNorm == target -> 0
    candidateNorm.startsWith(target) || target.startsWith(candidateNorm) -> 1
    candidateNorm.contains(target) || target.contains(candidateNorm) -> 2
    else -> levenshtein(candidateNorm, target)
  }

  // Best (lowest) score across the original target and its declension-stripped variants.
  fun bestScore(normalizedTarget: String, candidateNorm: String, levenshtein: (String, String) -> Int): Int =
    targetVariants(normalizedTarget).minOf { scoreOne(it, candidateNorm, levenshtein) }
}
