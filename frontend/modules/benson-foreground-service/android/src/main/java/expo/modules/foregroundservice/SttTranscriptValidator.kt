package expo.modules.foregroundservice

// FIX_STT_NON_LATIN_1 (14.10.2026) — cauza dovedita pe telefon, in testul vocal al Masterului:
//   16:13:53.666  CMD_CAPTURE end reason=vad speechMs=1186   (1,2 s de vorbire reala, peakRms=4091)
//   16:13:54.910  STT_RESULT provider=openai text="当" chars=1
// Masterul a rostit un raspuns scurt ("da"/"nu"); transcrierea a intors un singur ideogram chinezesc.
// In `nativeTranscribe`, verificarea era `if (!t.isNullOrBlank()) return t` — iar "当" NU e gol, deci
// rezultatul halucinat era acceptat si rezerva (Deepgram/Groq) nu era incercata NICIODATA.
// Acelasi mod de eșec a fost vazut si cu zgomot chirilic dintr-un TV (documentat in
// PLAN_REPORT_2026-10-14: "55 caractere (chirilic, zgomot/TV, nu romana)").
//
// Regula: BENSON vorbeste ro/de/en — toate in alfabet latin. Un transcript care nu contine NICIO
// litera latina nu poate fi o comanda sau un raspuns valid; e zgomot halucinat. Nu-l acceptam:
// il tratam ca pe un transcript gol, deci lanțul trece la furnizorul de rezerva.
//
// Deliberat NU respinge: cifrele singure ("2"), punctuatia alaturi de litere, diacriticele
// romanesti/germane. Pur, JUnit-testabil.
object SttTranscriptValidator {

  // Litere latine, inclusiv diacritice ro/de (ă â î ș ț ö ü ä ß) — intervalele Latin-1/Latin Extended.
  private val LATIN_LETTER = Regex("[A-Za-z\\u00C0-\\u024F]")

  // Cifrele sunt raspunsuri valide la o lista numerotata ("2"), deci un transcript doar din cifre
  // rămâne acceptabil.
  private val DIGIT = Regex("\\d")

  /** false => transcript inutilizabil; apelantul trebuie sa incerce furnizorul de rezerva. */
  fun isUsable(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    if (LATIN_LETTER.containsMatchIn(text)) return true
    // Fara litere latine: acceptam doar daca e un numar curat (alegere din lista).
    return DIGIT.containsMatchIn(text) && text.none { it.isLetter() }
  }

  /** Motivul respingerii, pentru linia de log STT_FALLBACK. */
  fun rejectReason(text: String?): String = when {
    text == null -> "provider_error_or_timeout"
    text.isBlank() -> "empty_transcript"
    else -> "non_latin_transcript"
  }
}
