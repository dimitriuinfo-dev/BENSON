package expo.modules.foregroundservice

// CALENDAR AGENT (19.10.2026, prioritate explicita a Masterului, integrat in unicul BENSON LIVE —
// acelasi motor de instrumente folosit deja de press_button/open_app/tap_element, NU un orchestrator
// nou si NU scaffold-ul separat BensonLiveOrchestrator.kt, care ramane neatins). Pura, fara
// dependinta de Context/CalendarContract, testabila JUnit fara dispozitiv — toata matematica de
// interval (conflict, duplicat, interval liber) traieste aici; BensonCalendarExecutor.kt (Android,
// netestabil JUnit) o foloseste ca sa decida, niciodata sa ghiceasca.
//
// Totul in epoch millis (Long) — zero java.time (neindisponibil sub API 26 fara desugaring, care nu
// e activat in acest proiect) si zero parsare de text aici; interpretarea limbajului natural
// (RO/DE/EN/orice) ramane la modelul comun deja folosit pentru restul conversatiei (acelasi principiu
// ca la "Multiplizieren" == "×") — executorul primeste deja ISO local date-time, nu text liber.

data class CalendarEvent(
  val id: Long,
  val title: String,
  val startMillis: Long,
  val endMillis: Long,
)

object CalendarEventMath {
  fun intervalsOverlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    aStart < bEnd && bStart < aEnd

  /** Evenimente existente care s-ar suprapune cu [candidateStart, candidateEnd). `excludeId` e folosit la UPDATE, ca evenimentul sa nu se raporteze conflict cu el insusi. */
  fun findConflicts(
    candidateStart: Long,
    candidateEnd: Long,
    existing: List<CalendarEvent>,
    excludeId: Long? = null,
  ): List<CalendarEvent> =
    existing.filter { it.id != excludeId && intervalsOverlap(candidateStart, candidateEnd, it.startMillis, it.endMillis) }

  private fun normTitle(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase().trim()

  // Protectie impotriva duplicatelor: ACELASI titlu (normalizat) SI ACELASI inceput exact — un
  // eveniment real nu se creeaza de doua ori din aceeasi cerere repetata (ex. agentul reincearca
  // dupa un timeout de retea si crede ca prima incercare n-a mers).
  fun isDuplicate(candidateTitle: String, candidateStart: Long, existing: List<CalendarEvent>): Boolean =
    existing.any { it.startMillis == candidateStart && normTitle(it.title) == normTitle(candidateTitle) }

  /** Ferestre libere de cel putin `durationMillis` in [dayStart, dayEnd), excluzand `busy` (nu trebuie sa fie sortat). */
  fun findFreeSlots(dayStart: Long, dayEnd: Long, durationMillis: Long, busy: List<CalendarEvent>): List<Pair<Long, Long>> {
    if (durationMillis <= 0 || dayEnd <= dayStart) return emptyList()
    val merged = mutableListOf<Pair<Long, Long>>()
    for (b in busy.sortedBy { it.startMillis }) {
      val s = b.startMillis.coerceAtLeast(dayStart)
      val e = b.endMillis.coerceAtMost(dayEnd)
      if (e <= dayStart || s >= dayEnd) continue
      if (merged.isNotEmpty() && s <= merged.last().second) {
        val last = merged.removeAt(merged.size - 1)
        merged.add(last.first to maxOf(last.second, e))
      } else {
        merged.add(s to e)
      }
    }
    val free = mutableListOf<Pair<Long, Long>>()
    var cursor = dayStart
    for ((s, e) in merged) {
      if (s - cursor >= durationMillis) free.add(cursor to s)
      cursor = maxOf(cursor, e)
    }
    if (dayEnd - cursor >= durationMillis) free.add(cursor to dayEnd)
    return free
  }
}
