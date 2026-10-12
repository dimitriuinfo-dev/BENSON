package expo.modules.foregroundservice

// RUNDA F2 (12.10.2026, user-directed, device-proven) — "deschide calculatorul" -> not_found.
// Cauza: sistemul telefonului e in germana (CLAUDE.md: "Sistem: germana"), deci eticheta aplicatiei
// instalate vine din Android in germana ("Rechner", nu "Calculator") — AppNameMatcher.score
// functiona deja corect, dar targetul rostit in romana nu avea nicio suprapunere textuala/fonetica
// cu eticheta germana instalata. Doua mecanisme independente, ambele doar GENEREAZA variante de
// incercat — scorul final tot il decide AppNameMatcher (maxOf peste toate variantele), deci o
// varianta gresita (ex. "camer" dupa o taiere agresiva) nu poate strica un match corect deja gasit
// pe alta varianta. Pur, zero Context dependency, JUnit-tested (AppNameSynonymsTest).
object AppNameSynonyms {
  // Grup sinonim: numele canonic de ROSTIT inapoi (RO/DE, cf. PLANUL_FINAL punctul 4 — "BENSON
  // rosteste numele in limba turei, nu eticheta de sistem"), plus toate formele rostite (RO/DE/EN,
  // deja normalizate: lowercase, fara diacritice) care trebuie sa gaseasca eticheta instalata.
  private data class Group(val ro: String, val de: String, val variants: List<String>)

  // Sufixele de articol hotarat romanesc cerute explicit, cele mai lungi primele (ordinea conteaza:
  // "ului" trebuie incercat inaintea lui "ul", altfel "calculatorului" s-ar taia gresit).
  private val RO_ARTICLE_SUFFIXES = listOf("ului", "lui", "le", "ul", "a")

  fun stripRomanianArticle(target: String): String {
    for (suffix in RO_ARTICLE_SUFFIXES) {
      if (target.endsWith(suffix) && target.length - suffix.length >= 3) {
        return target.removeSuffix(suffix)
      }
    }
    return target
  }

  // Exact tabelul din specificatie (PLANUL_FINAL, RUNDA F2 punctul 2).
  private val GROUPS: List<Group> = listOf(
    Group("Calculatorul", "Rechner", listOf("calculator", "calculatorul", "rechner", "taschenrechner")),
    Group("Setările", "Einstellungen", listOf("setari", "setarile", "einstellungen", "settings")),
    Group("Camera", "Kamera", listOf("camera", "kamera")),
    Group("Galeria", "Galerie", listOf("galerie", "fotos", "photos", "gallery")),
    Group("Calendarul", "Kalender", listOf("calendar", "kalender")),
    Group("Ceasul", "Uhr", listOf("ceas", "uhr", "clock")),
    Group("Mesajele", "Nachrichten", listOf("mesaje", "nachrichten", "messages")),
    Group("Telefonul", "Telefon", listOf("telefon", "phone")),
  )

  private fun matchingGroup(rawTarget: String): Group? {
    val forms = listOf(rawTarget, stripRomanianArticle(rawTarget)).distinct()
    return GROUPS.firstOrNull { g -> forms.any { g.variants.contains(it) } }
  }

  // Toate formele de incercat pentru un target rostit: originalul, varianta fara articol (daca
  // difera), si — pentru fiecare dintre ele care e membru al unui grup — restul grupului. Deduplicat.
  fun expandTarget(rawTarget: String): List<String> {
    val base = mutableListOf(rawTarget)
    val stripped = stripRomanianArticle(rawTarget)
    if (stripped != rawTarget) base.add(stripped)
    val expanded = base.toMutableList()
    matchingGroup(rawTarget)?.let { expanded.addAll(it.variants) }
    return expanded.distinct()
  }

  // Numele canonic de rostit, in limba turei ("ro"/"de"; orice altceva cade pe "ro"). Null daca
  // targetul nu apartine niciunui grup cunoscut — apelantul foloseste atunci eticheta de sistem,
  // ca inainte de F2.
  fun canonicalName(rawTarget: String, lang: String): String? {
    val group = matchingGroup(rawTarget) ?: return null
    return if (lang == "de") group.de else group.ro
  }
}
