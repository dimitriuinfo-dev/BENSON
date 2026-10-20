package expo.modules.foregroundservice

// FIX_HUMAN_PHRASING_1 (14.10.2026, cerut de Master: "vreau sa emulezi pronuntia si gramatica lui
// Benson, sa fie umana").
//
// Ce pot schimba si ce NU:
//  - NU ating motorul TTS (`ro-ro-x-vfv-local`), rata, tonul sau configuratia vocii: e interdictie
//    explicita a Masterului din ordinele anterioare ("nu modifica wake-ul si TTS-ul validate").
//    Vocea cu adevarat umana cere runda OpenAI TTS (docs/specs/B_VOCE_CHATBOT_2026-10-14.md),
//    neimplementata, si alegerea vocii de catre Master dupa mostre.
//  - POT schimba TEXTUL: un majordom nu vorbeste telegrafic ("Prima: X. A doua: Y."). Frazarea,
//    pauzele si acordul gramatical schimba masiv cum SUNA acelasi motor TTS.
//
// Reguli de frazare, toate cu motiv acustic sau gramatical:
//  1. Niciun cuvant integral MAJUSCUL in textul rostit — TTS-ul il citeste ca acronim, litera cu
//     litera (cauza dovedita a "has-a-ne-ne-a-has"; vezi NamePronunciation).
//  2. Virgula inaintea apozitiei si punct la final: motorul face pauza pe punctuatie, deci
//     punctuatia E prozodie.
//  3. Registru consecvent "dumneavoastra" + apelativul "Master", ca un majordom.
//  4. Fara simboluri brute in text (€, %, &): se rostesc prost; se scriu in litere.
// Pur, JUnit-testabil. Revert: apelantii folosesc iar literalele de dinainte.
object BensonPhrasing {

  private val SYMBOL_WORDS = listOf(
    "€" to " euro", "%" to " la sută", "&" to " și", "+" to " plus", "@" to " la ",
  )

  /** Curata un text inainte de rostire: simboluri in litere, fara MAJUSCULE integrale, spatii. */
  fun forSpeech(raw: String): String {
    var s = raw
    for ((sym, word) in SYMBOL_WORDS) s = s.replace(sym, word)
    // Cuvintele integral majuscule (si mai lungi de o litera) devin Title Case, ca sa nu fie
    // silabisite. Acronimele reale de 2-3 litere le lasam (TV, PDF) — acolo silabisirea e corecta.
    s = s.split(' ').joinToString(" ") { w ->
      val letters = w.filter { it.isLetter() }
      if (letters.length > 3 && letters == letters.uppercase()) {
        w.lowercase().replaceFirstChar { it.uppercase() }
      } else w
    }
    return s.replace(Regex("\\s{2,}"), " ").trim()
  }

  /** Introducerea listei de rezultate, in registru de majordom. */
  fun resultListIntro(n: Int): String {
    val count = when (n) {
      1 -> "o singură variantă"
      2 -> "două variante"
      3 -> "trei variante"
      4 -> "patru variante"
      else -> "$n variante"
    }
    return "Master, am găsit $count."
  }

  /** O linie de opțiune: "Prima ar fi X." / "A doua, Y." — cu pauze naturale. */
  fun resultListItem(index: Int, presented: String): String {
    val ord = when (index) {
      1 -> "Prima ar fi"
      2 -> "A doua,"
      3 -> "A treia,"
      4 -> "A patra,"
      else -> "A $index-a,"
    }
    return "$ord $presented."
  }

  fun resultListQuestion(): String = "Pe care o preferați?"

  fun askOrdinalAgain(n: Int): String = when {
    n <= 2 -> "Vă rog, prima sau a doua?"
    n == 3 -> "Vă rog să-mi spuneți: prima, a doua sau a treia?"
    else -> "Vă rog să-mi spuneți numărul variantei."
  }

  fun searchUnreadable(query: String): String =
    "Master, am deschis căutarea pentru $query, dar nu reușesc să citesc rezultatele din pagină. " +
      "Dacă doriți, încerc cu alte cuvinte."

  fun listAbandoned(): String = "Am lăsat lista deoparte, Master. Spuneți-mi când doriți altceva."

  fun choiceGone(): String =
    "Varianta aceea nu mai este pe ecran, Master. Doriți să caut din nou?"

  // VISIBLE WORKING STATE, punctul 3 (14.10.2026) — pe ecranul BLOCAT, Android nu permite unui
  // overlay de aplicatie sa fie desenat peste keyguard; singura suprafata permisa e notificarea.
  // Dar o notificare de pe lockscreen e vizibila oricui ridica telefonul, deci NU are voie sa
  // conțină conținut privat: nici transcrierea Masterului, nici rezultatele, nici nume de contact.
  // Mapam starea reala la o eticheta GENERICA. Dupa deblocare, bula completa preia tot conținutul.
  // Revert: VISIBLE_WORKING_STATE = false (suprimare totala, comportamentul de dinainte).
  fun lockscreenState(rawState: String, terminal: Boolean): String {
    val s = rawState.lowercase()
    return when {
      s.startsWith("ascult") -> "Ascult…"
      s.contains("nu am înțeles") || s.contains("nu am inteles") -> "Nu am înțeles."
      s.endsWith("?") -> "Aștept răspunsul dumneavoastră."
      terminal -> "Gata."
      else -> "Lucrez…"
    }
  }
}
