package expo.modules.foregroundservice

// BENSON NEXT — AKA, descoperire DINAMICA (19.10.2026). Legea Personalului, P1.6: "nicio
// explorare automata" — functioneaza STRICT pe o lista deja enumerata (parametru, nu interogata
// aici) si DOAR ca raspuns la o cerere explicita a proprietarului (un cuvant de scop in comanda).
// Reutilizeaza AppCategoryMatcher.extractCategory (deja scris/testat, RUNDA S-1) — zero al doilea
// dicionar de categorii.
//
// IMPORTANT: un DiscoveredApp NU e o capabilitate. Nu intra in CapabilityRecord/
// selectBestCapability si NU poate deveni niciodata executabil direct — e doar material pentru
// o intrebare de dezambiguizare ("Am găsit X. Îl deschid?"), exact ca "discovered" din lege.
internal data class DiscoveredApp(val packageName: String, val displayName: String, val matchedByCategory: String)

internal object AkaDiscovery {
  fun discoverByPurpose(members: List<StaffMember>, rawCommand: String): List<DiscoveredApp> {
    val category = AppCategoryMatcher.extractCategory(rawCommand) ?: return emptyList()
    return members
      .filter { !it.archived }
      .filter { it.category.equals(category, ignoreCase = true) || it.displayName.contains(category, ignoreCase = true) }
      .map { DiscoveredApp(it.packageName, it.displayName, matchedByCategory = category) }
  }
}
