package expo.modules.foregroundservice

// ADAOS WA_VISIBLE_DRAFT / CONTACT_ALIAS_LEARN / TTS_NAME_PRONOUNCE (2026-10-03, user-directed) —
// pure parsing, zero Context dependency, so it has a JUnit test (WaAliasMatcherTest) independent
// of BensonForegroundService. The actual alias/pronunciation STORE is SharedPreferences-backed
// (Context-dependent, lives in BensonForegroundService) — this object only owns the "does this
// utterance mean X" question, plus the store-agnostic key-normalization both sides share.
object WaAliasMatcher {
  private val CHANGE_TEXT_PATTERN = Regex("(?i)schimb[ăa]-?(?:l|o)?\\s+(?:in|în)\\s+(.+?)\\s*[.!?]*$")
  private val PRONOUNCE_PATTERN = Regex("(?i)se\\s+pronun[țt][ăa]\\s+(.+?)\\s*[.!?]*$")
  private val ALT_CONTACT_WORDS = setOf("alta", "altul", "altcineva", "altii", "alte", "altcineva")

  fun parseChangeText(input: String): String? =
    CHANGE_TEXT_PATTERN.find(input)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

  fun parsePronounce(input: String): String? =
    PRONOUNCE_PATTERN.find(input)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

  // Called with an ALREADY-normalized (lowercase, diacritic-stripped) string — same
  // normalizeForMatch() every other WA matcher uses, so "altă" and "alta" are indistinguishable
  // here by design.
  fun mentionsAlternateContact(normalizedInput: String): Boolean =
    normalizedInput.split(Regex("\\s+")).any { it in ALT_CONTACT_WORDS }

  // Store-agnostic: production keys SharedPreferences by this; tests key a plain HashMap by this.
  // Returns null for a blank spoken form — never save/look up an alias for nothing.
  fun aliasKey(normalizedSpokenForm: String): String? = normalizedSpokenForm.takeIf { it.isNotBlank() }
}
