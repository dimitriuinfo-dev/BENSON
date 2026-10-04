package expo.modules.accessibility

// RUNDA G-1 (2026-10-03, user-directed) — synonym table for findControl's semantic matching,
// RO/DE/EN (system is German, BENSON speaks Romanian — labels can appear in either, or English).
// Extensible constant, one list per intent. "end_call" added per user's explicit adaos (2026-10-03):
// FREE class, no confirmation.
object ControlSynonyms {
  val TABLE: Map<String, List<String>> = mapOf(
    // Adaos G-1 (2026-10-04, user-directed) — forme descrise prin formă (simbolul de pe buton),
    // nu doar cuvântul: "triunghi (spre dreapta)" = play, "pătrat" = stop, "două bare paralele" =
    // pauză. Acestea sunt cuvinte ROSTITE (deci intră în listă, nu doar potrivire pe ecran).
    "play" to listOf(
      // "wiedergeben" (verb) adăugat 2026-10-04, device-proven necesar separat de "wiedergabe"
      // (substantiv) — Spotify folosește "Wiedergeben" ca etichetă de buton, nu "Wiedergabe".
      "play", "redare", "porneste", "pornește", "wiedergabe", "wiedergeben", "abspielen",
      "triunghi", "triunghi spre dreapta", "triunghiul spre dreapta", "triunghiul din dreapta",
    ),
    "pause" to listOf(
      "pauza", "pauză", "pause", "pausieren",
      "doua bare paralele", "două bare paralele", "bare paralele",
    ),
    "stop" to listOf("stop", "patrat", "pătrat"),
    "live" to listOf("live", "in direct", "în direct"),
    "next" to listOf(
      "urmatorul", "următorul", "weiter", "next", "inainte", "înainte",
      "triunghi dublu spre dreapta", "triunghiul dublu spre dreapta",
    ),
    "prev" to listOf(
      "inapoi", "înapoi", "anterior", "precedentul", "zurück",
      "triunghi dublu spre stanga", "triunghi dublu spre stânga", "triunghiul dublu spre stanga",
    ),
    // "înainte/înapoi 10 secunde" — seek, nu schimbare de piesă. Distinct de next/prev.
    "seek_forward" to listOf("inainte 10 secunde", "înainte 10 secunde", "sari inainte", "sari înainte"),
    "seek_back" to listOf("inapoi 10 secunde", "înapoi 10 secunde", "sari inapoi", "sari înapoi"),
    "search" to listOf("cauta", "caută", "cautare", "căutare", "search", "suchen", "lupa", "lupă", "magnifier"),
    "close" to listOf("inchide", "închide", "schließen"),
    "back" to listOf("inapoi", "înapoi", "navigate up"),
    "menu" to listOf("meniu", "mai multe optiuni", "mai multe opțiuni", "more options"),
    // Adaos G-1 — "săgeata stânga/dreapta/sus/jos": caută întâi un nod cu descriere de săgeată;
    // fără potrivire, BensonAccessibilityService recurge la scroll în direcția cerută (vezi acolo).
    "arrow_left" to listOf("sageata stanga", "săgeata stânga", "pfeil links", "arrow left"),
    "arrow_right" to listOf("sageata dreapta", "săgeata dreapta", "pfeil rechts", "arrow right"),
    "arrow_up" to listOf("sageata sus", "săgeata sus", "pfeil nach oben", "arrow up"),
    "arrow_down" to listOf("sageata jos", "săgeata jos", "pfeil nach unten", "arrow down"),
    // Intent resolution for commands happens by dedicated phrase (UniversalHandCommandMatcher),
    // never by reverse word lookup — "apasă pe închide" (bare) still means "close", only "închide
    // apelul"/"termină apelul"/explicit call-ending phrasing resolves to end_call. Safe to share
    // "inchide"/"închide" with the close list below since lookup is always by intent key, not by
    // searching which list contains a word.
    "end_call" to listOf(
      "inchide", "închide", "inchide apelul", "închide apelul", "termina", "termină",
      "end call", "hang up", "auflegen", "beenden",
    ),
  )

  fun wordsFor(intent: String): List<String> = TABLE[intent].orEmpty()
}
