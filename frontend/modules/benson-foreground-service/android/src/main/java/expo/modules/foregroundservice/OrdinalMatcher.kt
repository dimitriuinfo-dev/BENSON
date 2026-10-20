package expo.modules.foregroundservice

// RUNDA VOICE-ONLY (14.10.2026, punctul 3 aprobat de Master) — "Reutilizeaza mecanismul existent
// de dezambiguizare numerotata pentru «primul», «al doilea», «al treilea» si echivalentele lor".
//
// Mecanismul exista deja (PendingWaDisambiguation / PendingAppSearch + parseWaOrdinal), dar
// vocabularul lui accepta DOAR cifre si numerale cardinale ("unu", "doi", "trei"). Formele
// ORDINALE, care sunt exact cele pe care le rosteste un om cand alege dintr-o lista citita cu
// voce, lipseau. Aici e o singura implementare, pura si testabila; `parseWaOrdinal` deleaga spre
// ea, deci WhatsApp si lista de aplicatii castiga aceleasi forme, fara cod paralel.
// Revert: parseWaOrdinal revine la corpul lui vechi (cifre + WA_NUMBER_WORDS).
object OrdinalMatcher {
  private val CARDINALS = mapOf(
    "unu" to 1, "una" to 1, "unul" to 1,
    "doi" to 2, "doua" to 2, "două" to 2,
    "trei" to 3, "patru" to 4, "cinci" to 5,
  )

  // Forme ordinale romanesti, masculin si feminin, cu si fara articol, inclusiv variantele
  // pe care le produce STT-ul fara diacritice.
  private val ORDINALS = mapOf(
    "primul" to 1, "prima" to 1, "primu" to 1, "intai" to 1, "intaiul" to 1, "întâi" to 1,
    "doilea" to 2, "doua" to 2, "secund" to 2, "secunda" to 2,
    "treilea" to 3, "treia" to 3,
    "patrulea" to 4, "patra" to 4,
    "cincilea" to 5, "cincea" to 5,
  )

  private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,!?;:\"'«»]"), " ")
      .lowercase().trim()

  /** Numarul ales (1-based) sau null. Cifrele au prioritate, apoi ordinalele, apoi cardinalele. */
  fun parse(text: String): Int? {
    val norm = normalize(text)
    Regex("\\d+").find(norm)?.let { return it.value.toIntOrNull() }
    val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
    // Ordinalele intai: "a doua" trebuie sa dea 2 prin "doua", iar "al doilea" prin "doilea".
    for (t in tokens) ORDINALS[t]?.let { return it }
    for (t in tokens) CARDINALS[t]?.let { return it }
    return null
  }
}
