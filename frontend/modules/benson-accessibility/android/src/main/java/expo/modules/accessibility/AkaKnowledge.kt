package expo.modules.accessibility

// AKA — Application Knowledge Agent, NUCLEUL PUR al descoperirii (20.10.2026, corectie
// arhitecturala explicita: "AKA nu este memorarea apasarilor, e cunoasterea autonoma a
// aplicatiilor"). Zero Context, zero AccessibilityNodeInfo, zero cunostinta despre vreo
// aplicatie anume — opereaza STRICT pe ScreenElement (deja produs de ScreenDigestBuilder pentru
// ORICE aplicatie). Testat cu fixturi SINTETICE, nu cu Calculator/Ceas/Calendar/WhatsApp — proba
// explicita a generalizarii (punctul 7 al ordinului).
//
// Principiul structural, generic pentru orice interfata Android: o "FUNCTIE CANDIDATA" a unui
// ecran e un control CLICKABIL, cu eticheta, care NU e continut dintr-o lista derulabila. Un
// rand dintr-o lista (istoric, alarme, rezultate de cautare) e CONTINUT, nu o functie a
// aplicatiei; un buton/tab/meniu din afara ariei derulabile e o functie reala, indiferent de
// aplicatie. Regula foloseste DOAR `scrollable`+`bounds`, deja existente pe ScreenElement — zero
// eticheta sau viewId hardcodat.

object AkaKnowledge {
  private fun parseBounds(b: String): IntArray? {
    if (b.isBlank()) return null
    val p = b.split(',')
    if (p.size != 4) return null
    val l = p[0].toIntOrNull() ?: return null
    val t = p[1].toIntOrNull() ?: return null
    val r = p[2].toIntOrNull() ?: return null
    val bb = p[3].toIntOrNull() ?: return null
    return intArrayOf(l, t, r, bb)
  }

  private fun contains(outer: String, inner: String): Boolean {
    val o = parseBounds(outer) ?: return false
    val i = parseBounds(inner) ?: return false
    return o[0] <= i[0] && o[1] <= i[1] && o[2] >= i[2] && o[3] >= i[3]
  }

  // GUVERNANTA (20.10.2026, cerinta explicita: "nu confunda informatiile despre utilizarea
  // aplicatiilor cu datele personale continute in ele") — o eticheta care arata ca un email sau
  // un numar de telefon nu e o FUNCTIE a aplicatiei (meniu/tab/buton), e CONTINUT personal aflat
  // intamplator intr-un loc neobisnuit (ex. un grid de contacte favorite, neclickabil-in-lista).
  // Generic, fara nicio cunoastere despre vreo aplicatie anume.
  private val EMAIL_PATTERN = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")
  private val PHONE_PATTERN = Regex("""^[+(]?[\d][\d\s().-]{6,}\d$""")

  private fun looksLikePersonalData(label: String): Boolean {
    val trimmed = label.trim()
    if (EMAIL_PATTERN.containsMatchIn(trimmed)) return true
    if (PHONE_PATTERN.matches(trimmed)) return true
    return false
  }

  /** Un control clickabil, cu eticheta, care NU e continut geometric intr-un container derulabil,
   * si a carui eticheta nu arata ca date personale (email/telefon). */
  fun isCandidateFunction(element: ScreenElement, allElements: List<ScreenElement>): Boolean {
    if (!element.clickable || element.label.isBlank()) return false
    if (looksLikePersonalData(element.label)) return false
    val scrollables = allElements.filter { it.scrollable && it !== element }
    return scrollables.none { contains(it.bounds, element.bounds) }
  }

  /** Toate functiile candidate ale ecranului curent, in ordinea observata — generic, orice aplicatie. */
  fun discoverCandidateFunctions(elements: List<ScreenElement>): List<ScreenElement> =
    elements.filter { isCandidateFunction(it, elements) }

  private fun normalizeLabel(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

  // O amprenta STRUCTURALA a ecranului — nu a continutului lui dinamic. Doar functiile candidate
  // + campurile editabile (structura stabila a unui meniu/ecran), niciodata continutul unei liste
  // derulabile (care creste/se schimba intre observari ale ACELUIASI ecran — ora unui ceas,
  // istoricul unui calculator, rezultatele unei cautari). Doua observari ale ACELUIASI ecran
  // produc ACEEASI amprenta; un ecran structural DIFERIT produce una diferita.
  object ScreenSignature {
    fun compute(packageName: String, elements: List<ScreenElement>): String {
      val stable = (discoverCandidateFunctions(elements) + elements.filter { it.editable })
        .distinct()
        .map { "${it.role.name}:${normalizeLabel(it.label)}" }
        .distinct()
        .sorted()
      val raw = packageName + '|' + stable.joinToString("|")
      return "$packageName:${hash(raw)}"
    }

    // Hash polinomial simplu, deterministic — nu criptografic, nu e nevoie: doar o amprenta
    // stabila pentru egalitate/indexare, nu securitate.
    private fun hash(s: String): String {
      var h = 0L
      for (c in s) h = (h * 31 + c.code) and 0xffffffffL
      return h.toString(16)
    }
  }
}
