package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventMathTest {

  private val hour = 3_600_000L
  private val day = 24 * hour

  @Test fun overlappingIntervalsAreDetected() {
    assertTrue(CalendarEventMath.intervalsOverlap(0, 10, 5, 15))
  }

  @Test fun touchingIntervalsDoNotOverlap() {
    // [0,10) si [10,20) se ating, nu se suprapun — ora 10:00-11:00 urmata de 11:00-12:00 e valid.
    assertFalse(CalendarEventMath.intervalsOverlap(0, 10, 10, 20))
  }

  @Test fun disjointIntervalsDoNotOverlap() {
    assertFalse(CalendarEventMath.intervalsOverlap(0, 10, 20, 30))
  }

  @Test fun findConflictsReturnsOnlyOverlappingEvents() {
    val existing = listOf(
      CalendarEvent(1, "Dentist", 10 * hour, 11 * hour),
      CalendarEvent(2, "Liber", 14 * hour, 15 * hour),
    )
    val conflicts = CalendarEventMath.findConflicts(10 * hour + 30 * 60_000, 12 * hour, existing)
    assertEquals(1, conflicts.size)
    assertEquals(1L, conflicts.single().id)
  }

  @Test fun findConflictsExcludesTheEventBeingUpdated() {
    // UPDATE: evenimentul 1 isi muta ora, nu trebuie sa se raporteze conflict cu EL INSUSI.
    val existing = listOf(CalendarEvent(1, "Dentist", 10 * hour, 11 * hour))
    val conflicts = CalendarEventMath.findConflicts(10 * hour, 11 * hour, existing, excludeId = 1)
    assertTrue(conflicts.isEmpty())
  }

  @Test fun duplicateIsDetectedBySameTitleAndSameStart() {
    val existing = listOf(CalendarEvent(1, "Întâlnire Rareș", 10 * hour, 11 * hour))
    assertTrue(CalendarEventMath.isDuplicate("întâlnire rareș", 10 * hour, existing))
  }

  @Test fun sameTitleDifferentStartIsNotADuplicate() {
    val existing = listOf(CalendarEvent(1, "Întâlnire Rareș", 10 * hour, 11 * hour))
    assertFalse(CalendarEventMath.isDuplicate("Întâlnire Rareș", 14 * hour, existing))
  }

  @Test fun differentTitleSameStartIsNotADuplicate() {
    val existing = listOf(CalendarEvent(1, "Dentist", 10 * hour, 11 * hour))
    assertFalse(CalendarEventMath.isDuplicate("Altceva", 10 * hour, existing))
  }

  @Test fun freeSlotsOnAnEmptyDayIsTheWholeDay() {
    val free = CalendarEventMath.findFreeSlots(0, day, hour, emptyList())
    assertEquals(listOf(0L to day), free)
  }

  @Test fun freeSlotsAroundASingleBusyEvent() {
    val busy = listOf(CalendarEvent(1, "X", 10 * hour, 12 * hour))
    val free = CalendarEventMath.findFreeSlots(8 * hour, 18 * hour, hour, busy)
    assertEquals(listOf(8 * hour to 10 * hour, 12 * hour to 18 * hour), free)
  }

  @Test fun overlappingBusyEventsAreMergedBeforeComputingFreeSlots() {
    val busy = listOf(
      CalendarEvent(1, "A", 9 * hour, 11 * hour),
      CalendarEvent(2, "B", 10 * hour, 13 * hour), // se suprapune cu A
    )
    val free = CalendarEventMath.findFreeSlots(8 * hour, 18 * hour, hour, busy)
    assertEquals(listOf(8 * hour to 9 * hour, 13 * hour to 18 * hour), free)
  }

  @Test fun tooShortGapIsNotReturnedAsAFreeSlot() {
    val busy = listOf(
      CalendarEvent(1, "A", 9 * hour, 10 * hour),
      CalendarEvent(2, "B", hour * 10 + 30 * 60_000, 12 * hour), // gol de doar 30 min intre A si B
    )
    val free = CalendarEventMath.findFreeSlots(9 * hour, 12 * hour, hour, busy)
    assertTrue(free.none { it.second - it.first < hour })
  }

  @Test fun busyEventOutsideTheRequestedWindowIsIgnored() {
    val busy = listOf(CalendarEvent(1, "Noapte", 0, 6 * hour))
    val free = CalendarEventMath.findFreeSlots(8 * hour, 18 * hour, hour, busy)
    assertEquals(listOf(8 * hour to 18 * hour), free)
  }
}
