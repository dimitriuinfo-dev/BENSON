package expo.modules.foregroundservice

// Op. 2 (14.10.2026, aprobata de Master) — "Benson, caută pe internet X".
// Cauza dovedita pe telefon (12:45:32): cu Amazon in prim-plan, "caută în internet robot de
// curatat ferestrele pana la 80 euro" a fost capturat de căutarea IN-APLICATIE
// (`CONTROL_FIND intent=search pkg=com.amazon…`), deci pasul WEB din L-SERVICE-002 nu era atins
// niciodata cat o aplicatie e deschisa.
//
// Matcher SEPARAT, nu o modificare a lui GeneralSearchMatcher: acela e fallback-ul generic
// ("cauta X" fara nicio aplicatie numita, verificat LA FINALUL lanțului). Asta prinde doar cazul
// in care utilizatorul NUMESTE explicit internetul/motorul, si de aceea are voie sa fie verificat
// INAINTEA căutării in-aplicatie: cand Masterul spune "pe internet", nimic altceva n-are drept.
//
// Pur, JUnit-testabil. Intent-ul/startActivity rămâne in BensonForegroundService (Context).
object ExplicitWebSearchMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  // "cauta/caut/gaseste [-mi] pe|in internet|google|web|net <ce>"  +  "google <ce>"
  private val PATTERN = Regex(
    "^(?:(?:cauta|cautam|caut|gaseste|caută)(?:-mi)?\\s+(?:pe|in|pt|prin)\\s+" +
      "(?:internet|internetul|google|web|net|browser)\\s+(.+?)" +
      "|googleste\\s+(.+?)" +
      "|(?:cauta|gaseste)(?:-mi)?\\s+(.+?)\\s+(?:pe|in)\\s+(?:internet|internetul|google|web))\\s*$"
  )

  // Navigatia rămâne a navigatiei, chiar daca fraza spune "internet".
  private val EXCLUDE = Regex("\\b(du-?ma|navigheaza|harta)\\b")

  fun extractQuery(rawCommand: String): String? {
    val norm = normalize(rawCommand)
    if (EXCLUDE.containsMatchIn(norm)) return null
    val m = PATTERN.find(norm) ?: return null
    return m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }
  }
}

// Op. 2, partea a doua — "apasă pe primul rezultat / primul link".
// Dovada pe telefon (12:44:25): `CONTROL_FIND intent=primul rezultat … result=none` — fraza nu era
// cunoscuta, desi ControlLocator.findFirstResult EXISTA si e folosit de YouTube/Spotify. Aici doar
// recunoastem fraza; clicul il face motorul generic existent, cu interogarea pastrata din comanda
// precedenta (contextul cerut explicit de Master).
object FirstResultMatcher {
  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), "")
      .lowercase().trim()

  private val PATTERN = Regex(
    "^(?:apasa|apasă|deschide|intra|selecteaza|alege|da)\\s+(?:pe\\s+|in\\s+)?" +
      "(?:primul|prima|primu)\\s+(?:rezultat|link|linc|articol|produs|varianta)\\s*$"
  )

  fun matches(rawCommand: String): Boolean = PATTERN.containsMatchIn(normalize(rawCommand))
}
