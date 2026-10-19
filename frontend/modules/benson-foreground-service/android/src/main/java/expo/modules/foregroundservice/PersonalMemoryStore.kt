package expo.modules.foregroundservice

// WORKSTREAM B — Personal Memory, faza izolata (D0 aprobat, 19.10.2026).
// Pur, in-memory — persistenta reala (SharedPreferences, criptare) e pasul de integrare, DUPA
// aprobare si verificare pe dispozitiv (corectia 13). Nu migreaza, nu citeste si nu sterge
// FACTS_KEY (JS, benson_facts_v2, existent) — vezi MemoryMigrationPlan (D0), neexecutat aici.
// Autorizarea scrierii e verificata deja la construirea MemoryWriteAuthorization (D0); aici se
// verifica doar ca nu a expirat intre autorizare si scriere efectiva.

class PersonalMemoryStore {
  private val places = mutableMapOf<String, SavedPlace>() // canonicalName normalizat -> place

  fun write(request: MemoryWriteRequest, nowMs: Long, maxAgeMs: Long = 90_000L): SavedPlace? {
    if (nowMs - request.authorization.proof.authorizedAtMs > maxAgeMs) return null
    val place = request.place ?: return null
    places[normalize(place.canonicalName)] = place
    return place
  }

  // Scopat pe domeniu (corectia 6) — un agent fara permisiune pe PLACES nu primeste nimic, chiar
  // daca locatia exista.
  fun resolve(requesterAgent: String, query: String): SavedPlace? {
    if (!isMemoryReadAllowed(requesterAgent, MemoryDomain.PLACES)) return null
    val q = normalize(query)
    places[q]?.let { return it }
    return places.values.firstOrNull { place -> place.aliases.values.any { normalize(it) == q } }
  }

  fun allPlaces(requesterAgent: String): List<SavedPlace> =
    if (isMemoryReadAllowed(requesterAgent, MemoryDomain.PLACES)) places.values.toList() else emptyList()

  // Corectie — scriere pe un nume canonic deja existent e CORECTIA lui, nu o a doua intrare
  // (acelasi comportament ca write(), explicit numit si testat separat).
  fun correct(request: MemoryWriteRequest, nowMs: Long, maxAgeMs: Long = 90_000L): SavedPlace? =
    write(request, nowMs, maxAgeMs)

  fun delete(authorization: MemoryWriteAuthorization, canonicalName: String, nowMs: Long, maxAgeMs: Long = 90_000L): Boolean {
    if (nowMs - authorization.proof.authorizedAtMs > maxAgeMs) return false
    return places.remove(normalize(canonicalName)) != null
  }

  // ───────── Punte catre persistenta (PersonalMemoryStorage.kt, Context-dependent) ─────────
  // Pur, fara Context — acelasi tipar ca StaffRegistryCore/StaffRegistry: nucleul nu stie nimic
  // despre SharedPreferences, doar ofera/accepta o lista simpla.

  fun snapshot(): List<SavedPlace> = places.values.toList()

  // Corupere/migrare in siguranta (cerinta explicita): o lista goala sau partiala primita de la
  // stratul de persistenta e acceptata ca atare — nu se blocheaza, nu se sterge nimic existent in
  // memorie care nu e in lista noua (hidratare aditiva, nu inlocuire completa).
  fun loadFrom(savedPlaces: List<SavedPlace>) {
    for (place in savedPlaces) places[normalize(place.canonicalName)] = place
  }
}

private fun normalize(s: String): String = s.trim().lowercase()
