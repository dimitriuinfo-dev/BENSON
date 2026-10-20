package expo.modules.foregroundservice

// RUNDA VOICE-ONLY, corectie ceruta de Master (14.10.2026):
// "Prefer sa-mi arate Benson lista cu robotii, nu sa citeasca meniul lor."
//
// Dovada pe telefon (15:29:49) — BENSON a rostit eticheta BRUTA a rezultatului web:
//   "Prima este PANCERKA intelligent Window Cleaning Robot 80,00 € Von Amazon.de Kostenlos
//    Vorher: 105 €. A doua este … A treia este 10% RABATT lubluelu CHYW200 Fensterputzroboter
//    mit Sprühfunktion 6500Pa für große Fenster,"
// Adica reclama intreaga, inclusiv "Kostenlos", "Vorher", "Von Amazon.de" si o virgula orfana.
//
// Aici transformam eticheta de pe ecran in PREZENTAREA produsului: nume + pret. Pur, JUnit-testabil.
// NU inventeaza nimic: pretul se extrage doar daca apare pe ecran; numele e un decupaj al textului
// real, niciodata un text generat. Revert: speakResultList foloseste eticheta bruta.
object ResultLabelPresenter {
  // Zgomot comercial observat pe paginile reale de rezultate (RO/DE/EN).
  private val PROMO_PREFIX = Regex(
    // "SALE" adaugat din dovada de pe telefon 15:39:27: "SALE Fensterreiniger…" si
    // "Fensterreiniger…" erau acelasi produs, dar prefixul le facea sa treaca drept doua variante.
    "^(?:\\d{1,2}\\s*%\\s*(?:rabatt|reducere|off)|gesponsert|sponsored|anzeige|reclama|sale|aktion|deal|angebot)\\s+",
    RegexOption.IGNORE_CASE,
  )
  private val TAIL_NOISE = Regex(
    "\\b(?:von\\s+amazon\\.?de|von\\s+amazon|kostenlos|gratis|vorher\\s*:?.*|" +
      "inkl\\.?\\s*mwst\\.?|versandkostenfrei|livrare\\s+gratuita|in\\s+stoc)\\b.*",
    RegexOption.IGNORE_CASE,
  )
  // Primul pret de pe eticheta: "80,00 €", "80 €", "€ 80", "80.00 EUR".
  private val PRICE = Regex("(\\d{1,5})(?:[.,](\\d{2}))?\\s*(?:€|eur\\b)|(?:€|eur)\\s*(\\d{1,5})(?:[.,](\\d{2}))?", RegexOption.IGNORE_CASE)

  // FIX_RESULT_LIST_LENGTH_1 (14.10.2026) — dovedit pe telefon cu vocea Masterului: cu 58 de
  // caractere pe nume, lista de 3 a devenit 261 de caractere, iar TTS-ul a vorbit 20,3 secunde
  // (`TTS_SPEAK chars=261` 15:39:27.121 → `WAKE_ACK_DONE` 15:39:47.415). Microfonul se deschide
  // abia DUPA ce BENSON termina (protectia anti-ecou E0, corecta si neatinsa), asa ca Masterul a
  // rostit "al treilea" peste vocea lui BENSON si nu a fost auzit niciodata.
  // Un nume de produs rostit trebuie sa fie scurt: marca + model, nu specificatia completa.
  // Revert: 58.
  const val MAX_SPOKEN_NAME = 34

  /** Pretul rostit in romana, din eticheta reala. null daca nu apare niciun pret pe ecran. */
  fun spokenPrice(rawLabel: String): String? {
    val m = PRICE.find(rawLabel) ?: return null
    val whole = m.groupValues[1].ifBlank { m.groupValues[3] }
    val cents = m.groupValues[2].ifBlank { m.groupValues[4] }
    if (whole.isBlank()) return null
    return if (cents.isBlank() || cents == "00") "$whole de euro" else "$whole virgulă $cents euro"
  }

  /** Numele produsului, curatat de promo/zgomot comercial/pret si scurtat la granita de cuvant. */
  fun productName(rawLabel: String): String {
    var s = rawLabel.trim()
    s = PROMO_PREFIX.replace(s, "")
    s = TAIL_NOISE.replace(s, "")
    s = PRICE.replace(s, " ")
    s = s.replace(Regex("\\s{2,}"), " ").trim().trim(',', ';', '-', '·', '|', '.')
    if (s.length <= MAX_SPOKEN_NAME) return dropDanglingWord(s)
    val cut = s.take(MAX_SPOKEN_NAME)
    val lastSpace = cut.lastIndexOf(' ')
    return dropDanglingWord((if (lastSpace > 20) cut.take(lastSpace) else cut).trim().trim(',', ';', '-'))
  }

  // Dovedit pe telefon (15:36:10): decupajul la granita de cuvant a lasat "…Fensterputzroboter
  // mit" — o prepozitie orfana, care suna rupt cand e rostita. Se taie ultimul cuvant daca e o
  // prepozitie/conjunctie scurta RO/DE/EN.
  private val DANGLING = setOf(
    "mit", "und", "oder", "fur", "für", "von", "zum", "zur", "im", "in", "am",
    "cu", "de", "la", "si", "și", "pentru", "din", "pe",
    "with", "and", "or", "for", "the", "a",
  )

  fun dropDanglingWord(s: String): String {
    val parts = s.trim().split(' ')
    if (parts.size < 2) return s.trim()
    return if (DANGLING.contains(parts.last().lowercase())) parts.dropLast(1).joinToString(" ").trim()
    else s.trim()
  }

  /** Forma rostita: "<nume>, <pret>" sau doar "<nume>" cand pretul nu e pe ecran. */
  fun present(rawLabel: String): String {
    val name = productName(rawLabel)
    val price = spokenPrice(rawLabel)
    if (name.isBlank()) return rawLabel.take(MAX_SPOKEN_NAME)
    return if (price != null) "$name, $price" else name
  }
}
