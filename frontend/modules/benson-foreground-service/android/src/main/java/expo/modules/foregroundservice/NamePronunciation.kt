package expo.modules.foregroundservice

// FIX_NAME_PRONUNCE_1 (14.10.2026, cerut explicit de Master: "mesaj catre has-a-ne-ne-a-has…
// rectifica, nu mai pot sa aud asta").
//
// Cauza reala: vocea curenta e `ro-ro-x-vfv-local` (TTS local Android, romana). Pe un nume care nu
// arata romanesc ("Hannah"), motorul renunta la citire si SILABISESTE literele: "has-a-ne-ne-a-has".
// Problema era deja notata in CLAUDE.md ca "cunoscut, neconsiderat regresie", cu un mecanism manual
// per contact (TTS_NAME_PRONUNCE). Asta o rezolva automat.
//
// REGULA ABSOLUTA: forma de aici se foloseste DOAR pentru rostire (TTS). Niciodata pentru
// potrivirea contactului, pentru căutare sau pentru vreun payload — acolo numele rămâne EXACT cum
// e in agenda (altfel s-ar strica WaNameMatcher si apelurile dovedite).
// Pur, JUnit-testabil. Revert: speakableName() returneaza argumentul neschimbat.
object NamePronunciation {
  // Grupuri de litere care nu exista in ortografia romaneasca si care deruteaza motorul TTS.
  // Ordinea conteaza: "th" inainte de "h"-ul final.
  private val CLUSTERS = listOf(
    "sch" to "ș", "sh" to "ș", "tsch" to "ci",
    "th" to "t", "ph" to "f", "ck" to "c", "kh" to "h",
    "qu" to "cu", "x" to "cs",
    "ee" to "i", "oo" to "u", "ou" to "u", "ea" to "i",
  )

  private val DOUBLES = listOf("nn", "ll", "tt", "mm", "ss", "ff", "pp", "rr", "dd", "gg", "bb", "cc", "zz")

  /**
   * Numele intr-o forma pe care TTS-ul romanesc o citeste ca pe un cuvant, nu literă cu literă.
   * Ex.: "Hannah" -> "Hana", "Matthew" -> "Matiu", "Phillip" -> "Filip".
   */
  fun speakableName(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return trimmed
    // Mai multe cuvinte (prenume + nume): fiecare separat, ca sa nu se amestece grupurile.
    if (trimmed.contains(' ')) {
      return trimmed.split(' ').filter { it.isNotBlank() }.joinToString(" ") { speakableName(it) }
    }
    var s = trimmed.lowercase()

    for ((from, to) in CLUSTERS) s = s.replace(from, to)
    for (d in DOUBLES) s = s.replace(d, d.substring(0, 1))
    // "h" final dupa vocala e mut in pronuntia romaneasca a numelor ("Hannah" -> "Hana").
    if (s.length > 2 && s.endsWith("h") && "aeiou".contains(s[s.length - 2])) s = s.dropLast(1)
    // "w" se citeste "v"; "y" intre consoane se citeste "i".
    s = s.replace("w", "v")
    s = s.replace(Regex("y"), "i")
    if (s.isBlank()) return trimmed
    // CAUZA RADACINA, dovedita de Master prin NUMARUL de litere auzite (14.10.2026):
    //   "has-a-ne-ne-a-has" = 6 litere = H-A-N-N-A-H   (inainte de orice fix)
    //   "has-a-ne-a"        = 4 litere = H-A-N-A        (dupa ce am scurtat la "HANA")
    // Deci TTS-ul nu avea problema cu ortografia: numele vine din headerul WhatsApp in MAJUSCULE
    // ("HANNAH"), iar motoarele TTS citesc un cuvant integral majuscul ca ACRONIM, litera cu
    // litera. Prima mea versiune pastra majusculele ("HANA") si de aceea continua sa silabiseasca.
    // Regula: forma rostita NU e niciodata integral majuscula. Title Case, mereu.
    // Revert: `if (trimmed == trimmed.uppercase()) s.uppercase() else …` (adica bug-ul la loc).
    return s.replaceFirstChar { it.uppercase() }
  }
}
