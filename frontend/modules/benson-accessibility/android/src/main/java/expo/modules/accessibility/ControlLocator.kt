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
