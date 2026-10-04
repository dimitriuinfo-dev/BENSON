package expo.modules.foregroundservice

// RUNDA S-1 TASK 1 (2026-10-03, user-directed) — pure regex extraction for "cauta/deschide
// aplicatia de <categorie>" ("caută aplicația de parcare"). The installed-app lookup/launch needs
// PackageManager (Context-dependent, stays inline in
// BensonForegroundService.tryNativeAppCategorySearch) — this is just the parsing, JUnit-tested.
object AppCategoryMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  private val PATTERN = Regex("^(?:cauta|deschide)(?:-mi)?\\s+aplicati[ae]\\s+de\\s+(.+?)\\s*$")

  fun extractCategory(rawCommand: String): String? =
    PATTERN.find(normalize(rawCommand))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}
