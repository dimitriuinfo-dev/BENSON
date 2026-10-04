package expo.modules.accessibility

// RUNDA G-1 (2026-10-03, user-directed) — three confirmation classes for the universal "apasă pe
// X" hand. Word lists are exact per spec (RO/EN/DE), checked against both the node's text and its
// contentDescription, normalized. REFUSE wins over CONFIRM wins over FREE when a label matches
// more than one list (doctrina 3: fără plăți, niciodată negociabil). BLOCKED_PACKAGES are real,
// device-verified package names (`pm list packages`, 2026-10-03) — never guessed. Distinct from
// the existing, narrower isPaymentSensitive()/PAYMENT_BLOCKLIST (guards only the WA call-button
// tap) — not merged with it, per "nu înlocui ce funcționează".
object ButtonSafetyClassifier {
  enum class Verdict { FREE, CONFIRM, REFUSE }

  private val REFUSE_WORDS = listOf(
    "plateste", "plătește", "cumpara", "cumpără", "comanda", "comandă",
    "finalizeaza comanda", "finalizează comanda", "adauga card", "adaugă card",
    "pay", "buy", "order", "checkout", "kaufen", "bezahlen", "bestellen", "jetzt kaufen",
  )
  private val CONFIRM_WORDS = listOf(
    "trimite", "sterge", "șterge", "posteaza", "postează", "distribuie", "accepta", "acceptă",
    "instaleaza", "instalează", "dezinstaleaza", "dezinstalează", "apeleaza", "apelează", "aboneaza", "abonează",
    "send", "delete", "post", "share", "accept", "install", "senden", "löschen", "teilen", "annehmen",
  )

  // Device-verified (`pm list packages | grep -iE "paypal|revolut|wallet|bank|..."`, 2026-10-03):
  // Revolut, PayPal, Google Wallet, Commerzbank (+ photoTAN), Bank Norwegian, ebankit/BT. No click
  // at all in these, regardless of button — extend by adding the real package here, never a guess.
  private val BLOCKED_PACKAGES = listOf(
    "com.revolut.revolut",
    "com.paypal.android.p2pmobile",
    "com.google.android.apps.walletnfcrel",
    "de.commerzbanking.mobil",
    "com.commerzbank.phototan",
    "com.banknorwegian",
    "com.ebankit.com.bt",
  )

  private fun norm(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

  private fun matchesAny(field: String, words: List<String>): Boolean {
    if (field.isBlank()) return false
    val f = norm(field)
    return words.any { f.contains(norm(it)) }
  }

  fun isBlockedPackage(pkg: String): Boolean = BLOCKED_PACKAGES.any { pkg.equals(it, ignoreCase = true) }

  fun classify(text: String, description: String, foregroundPackage: String = ""): Verdict {
    if (isBlockedPackage(foregroundPackage)) return Verdict.REFUSE
    if (matchesAny(text, REFUSE_WORDS) || matchesAny(description, REFUSE_WORDS)) return Verdict.REFUSE
    if (matchesAny(text, CONFIRM_WORDS) || matchesAny(description, CONFIRM_WORDS)) return Verdict.CONFIRM
    return Verdict.FREE
  }
}
