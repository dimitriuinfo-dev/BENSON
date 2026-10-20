package expo.modules.accessibility

// RUNDA G-1 (2026-10-03) — generic semantic locator over an abstract UI tree (UiNode). Priority:
// contentDescription -> text -> viewId -> hint -> className (spec order). Each candidate word is
// matched as a normalized substring (diacritic/case-insensitive), not exact-equals, so "cauta"
// matches a desc of "Căutare în chat". Pure, zero Context dependency, JUnit-tested
// (ControlLocatorTest).
object ControlLocator {
  private fun norm(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

  private fun matchesAny(field: String, words: List<String>): Boolean {
    if (field.isBlank()) return false
    val f = norm(field)
    return words.any { it.isNotBlank() && f.contains(norm(it)) }
  }

  private fun flatten(root: UiNode): List<UiNode> {
    val out = mutableListOf<UiNode>()
    fun walk(n: UiNode) { out.add(n); n.children.forEach { walk(it) } }
    walk(root)
    return out
  }

  /** First node (tree order) whose desc/text/viewId/hint/className matches any of `words`. */
  fun findControl(words: List<String>, root: UiNode): UiNode? {
    if (words.isEmpty()) return null
    val nodes = flatten(root)
    nodes.firstOrNull { matchesAny(it.desc, words) }?.let { return it }
    nodes.firstOrNull { matchesAny(it.text, words) }?.let { return it }
    nodes.firstOrNull { matchesAny(it.viewId, words) }?.let { return it }
    nodes.firstOrNull { matchesAny(it.hint, words) }?.let { return it }
    nodes.firstOrNull { matchesAny(it.className, words) }?.let { return it }
    return null
  }

  // FIX_SEARCH_STRUCTURAL_1 (14.10.2026, cerinta explicita a Masterului) — "in toate aplicatiile
  // spatiul destinat cautarii este o bara alba lanca care este o lupa desenata sau search".
  // Corect, si de aceea potrivirea NUMAI pe cuvinte nu ajunge: bara e recunoscuta dupa FORMA
  // (un camp editabil), nu doar dupa eticheta. Ordinea e de la cel mai sigur la cel mai slab, ca
  // sa nu nimerim un alt formular:
  //   1. camp EDITABIL cu eticheta/viewId de căutare (cel mai sigur)
  //   2. nod cu viewId de căutare (bara neinteractiva pe care trebuie intai apasat)
  //   3. potrivirea clasica pe cuvinte (comportamentul de pana acum, neschimbat)
  //   4. SINGURUL camp editabil din ecran — o bara alba singura ESTE bara de căutare; daca sunt
  //      mai multe campuri, refuzam (ar fi ghicit, nu identificat)
  // `findControl` rămâne neatins, deci nimic din G-1 nu se schimba. Revert: sterge aceasta functie
  // si apelul ei din BensonAccessibilityService.
  fun findSearchField(root: UiNode, searchWords: List<String>): UiNode? {
    val nodes = flatten(root)
    fun searchish(n: UiNode): Boolean =
      matchesAny(n.viewId, searchWords) || matchesAny(n.hint, searchWords) ||
        matchesAny(n.desc, searchWords) || matchesAny(n.text, searchWords)

    nodes.firstOrNull { it.editable && searchish(it) }?.let { return it }
    nodes.firstOrNull { matchesAny(it.viewId, searchWords) }?.let { return it }
    findControl(searchWords, root)?.let { return it }
    val editables = nodes.filter { it.editable }
    return if (editables.size == 1) editables[0] else null
  }

  // RUNDA MUSIC-2 (2026-10-04, user-directed) — "click pe PRIMUL rând din lista de rezultate al
  // cărui text conține X (potrivire aproximativă)", generic, pentru orice aplicație — nu doar
  // Spotify. Exclude câmpurile editabile (altfel s-ar potrivi pe propriul câmp de căutare, care
  // conține X ca text curent) și etichetele marcate drept zgomot de `isNoise` (apelantul dă
  // SearchResultNoiseFilter.isNoise — ControlLocator rămâne agnostic de acel filtru specific).
  fun findFirstResult(query: String, root: UiNode, isNoise: (label: String, query: String) -> Boolean): UiNode? {
    val q = norm(query)
    if (q.isBlank()) return null
    return flatten(root).firstOrNull { node ->
      if (node.editable) return@firstOrNull false
      val label = node.text.ifBlank { node.desc }.trim()
      if (label.isBlank()) return@firstOrNull false
      if (isNoise(label, query)) return@firstOrNull false
      norm(label).contains(q)
    }
  }

  // SEMANTIC UI RESOLVER (19.10.2026, raspuns la DEVICE_FAIL Calculator repetat — selectie de
  // INDEX nesigura a modelului). Generic, orice aplicatie — NU index memorat dintr-un digest vechi,
  // NU pozitii/coordonate hardcodate. Reutilizeaza norm()/flatten() de mai sus, acelasi motor G-1.
  //
  // Returneaza TOTI candidatii gasiti la cel mai SIGUR nivel de potrivire care are macar unul — nu
  // doar primul (ca findControl) — ca apelantul sa poata detecta AMBIGUITATEA explicit (>1
  // candidat) in loc sa aleaga orbeste. 0 candidati = butonul nu exista acum pe ecran
  // (UI_NODE_NOT_FOUND); 1 = continua; >1 = AMBIGUU, nu se ghiceste.
  //
  // Nivel 1 (cel mai sigur): potrivire EXACTA (dupa normalizare) pe text SAU contentDescription —
  // cazul tipic pentru cifre/operatori cu eticheta scurta si clara ("7", "×", "=").
  // Nivel 2 (rezerva, doar daca nivelul 1 nu gaseste nimic): potrivire PARTIALA pe
  // desc/text/viewId/hint, ca la findControl — pentru butoane cu eticheta descriptiva mai lunga
  // (ex. "Sterge", cand modelul cere "sterge" sau "clear").
  fun findButtonCandidates(label: String, root: UiNode): List<UiNode> {
    val target = norm(label)
    if (target.isBlank()) return emptyList()
    val clickable = flatten(root).filter { it.clickable }
    val exact = clickable.filter { norm(it.text) == target || norm(it.desc) == target }
    if (exact.isNotEmpty()) return exact
    val byDesc = clickable.filter { matchesAny(it.desc, listOf(label)) }
    if (byDesc.isNotEmpty()) return byDesc
    val byText = clickable.filter { matchesAny(it.text, listOf(label)) }
    if (byText.isNotEmpty()) return byText
    val byViewId = clickable.filter { matchesAny(it.viewId, listOf(label)) }
    if (byViewId.isNotEmpty()) return byViewId
    return clickable.filter { matchesAny(it.hint, listOf(label)) }
  }

  // FIX_PRESS_BUTTON_LABEL_MISMATCH_1 (19.10.2026, cerinta explicita a Masterului) — cauza reala
  // gasita in rularea 5: press_button(label="×") a esuat cu UI_NODE_NOT_FOUND pentru ca eticheta
  // REALA a butonului, din contentDescription, era cuvantul german "Multiplizieren" — simbolul
  // vizual si eticheta de Accessibility nu coincid, pe orice limba/aplicatie, nu doar Calculator.
  //
  // Solutia e GENERICA si nu adauga nicio lista rigida per limba in cod: cand 0 candidati se
  // gasesc, apelantul poate oferi agentului lista REALA a etichetelor clickabile vazute acum pe
  // ecran (resursa Accessibility deja citita, nimic nou). Inteligenta multilingva ramane acolo
  // unde exista deja — la modelul comun (acelasi folosit pentru tot restul conversatiei) — care
  // poate recunoaste ca "Multiplizieren" inseamna "×" si poate relua press_button cu eticheta
  // EXACTA gasita, fara ca BENSON sa codifice vreodata traducerea.
  fun visibleClickableLabels(root: UiNode, limit: Int = 20): List<String> {
    val out = LinkedHashSet<String>()
    for (n in flatten(root)) {
      if (!n.clickable) continue
      val label = n.text.ifBlank { n.desc }.trim()
      if (label.isBlank()) continue
      out.add(label)
      if (out.size >= limit) break
    }
    return out.toList()
  }

  /** Nearest clickable node at or above `node` within `root` (node itself, or first clickable ancestor). */
  fun clickableTarget(node: UiNode, root: UiNode): UiNode? {
    if (node.clickable) return node
    fun findParentChain(n: UiNode, target: UiNode, chain: MutableList<UiNode>): Boolean {
      if (n === target) return true
      for (c in n.children) {
        chain.add(n)
        if (findParentChain(c, target, chain)) return true
        chain.removeAt(chain.size - 1)
      }
      return false
    }
    val chain = mutableListOf<UiNode>()
    if (!findParentChain(root, node, chain)) return null
    return chain.lastOrNull { it.clickable }
  }
}
