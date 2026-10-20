package expo.modules.foregroundservice

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// CALENDAR AGENT (19.10.2026, prioritate explicita, integrat in unicul BENSON LIVE — acelasi
// motor de instrumente folosit deja de open_app/press_button, NU scaffold-ul separat
// BensonLiveOrchestrator.kt, neatins). Foloseste intai API-ul oficial Android
// (android.provider.CalendarContract), niciodata Accessibility — calendarul nu are nevoie de
// apasat butoane, are o interfata de date structurata deja data de platforma.
//
// Toata matematica de decizie (conflict/duplicat/interval liber) e in CalendarEventMath.kt, pura,
// JUnit-testata fara dispozitiv. Acest fisier e strict Android-dependent (ContentResolver) — nu
// poate fi testat JUnit fara Robolectric (neintrodus in acest proiect); validarea reala ramane
// pentru un test pe dispozitiv separat, prin ADB, fara niciun apel OpenAI (operatie locala,
// gratuita).
//
// SIGURANTA CERUTA EXPLICIT DE MASTER (aceasta runda): scrierea (creare/modificare) se face DOAR
// intr-un calendar LOCAL dedicat de test (ACCOUNT_TYPE_LOCAL — nu se sincronizeaza niciodata,
// deci nu trimite NICIODATA invitatii catre nimeni), niciodata in calendarul real al
// utilizatorului, cat timp capabilitatea nu e DEVICE_PASS. Citirea (C1) ramane pe toate
// calendarele, pentru ca e read-only, fara risc. Comutarea pe scriere in calendarul implicit
// real ramane o decizie separata, explicita, dupa validare.
object BensonCalendarExecutor {
  private const val TEST_CALENDAR_NAME = "BENSON Test"
  private const val TEST_ACCOUNT_NAME = "BENSON Test"

  data class CalendarActionResult(val ok: Boolean, val message: String, val eventId: Long? = null)

  // Fara java.time (minSdk 24, fara core library desugaring in acest proiect) — ISO local simplu,
  // fara fus orar in text; interpretarea limbajului natural (RO/DE/EN/orice) ramane la modelul
  // comun deja folosit in restul conversatiei (acelasi principiu ca "Multiplizieren" == "×") —
  // acest executor primeste deja un ISO local date-time, nu text liber.
  fun parseIsoLocal(iso: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)?.time
  } catch (_: Exception) { null }

  private fun weekdayName(millis: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    return arrayOf("", "duminică", "luni", "marți", "miercuri", "joi", "vineri", "sâmbătă")[cal.get(java.util.Calendar.DAY_OF_WEEK)]
  }

  // Data inteleasa se rosteste INTOTDEAUNA cu ziua saptamanii (cerinta explicita EC_CALENDAR), ca
  // Masterul sa prinda o greseala de interpretare inainte de a confirma.
  fun formatHuman(startMillis: Long, endMillis: Long, allDay: Boolean): String {
    return if (allDay) {
      val sdfDay = SimpleDateFormat("d MMMM", Locale("ro"))
      val lastDay = endMillis - 1
      "${weekdayName(startMillis)}, ${sdfDay.format(java.util.Date(startMillis))} până " +
        "${weekdayName(lastDay)}, ${sdfDay.format(java.util.Date(lastDay))}, toată ziua"
    } else {
      val sdf = SimpleDateFormat("d MMMM, HH:mm", Locale("ro"))
      "${weekdayName(startMillis)}, ${sdf.format(java.util.Date(startMillis))}"
    }
  }

  private fun ensureTestCalendarId(resolver: ContentResolver): Long {
    resolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID),
      "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
      arrayOf(TEST_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
      null,
    )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }

    val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
      .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
      .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT_NAME)
      .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
      .build()
    val values = ContentValues().apply {
      put(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT_NAME)
      put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
      put(CalendarContract.Calendars.NAME, TEST_CALENDAR_NAME)
      put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, TEST_CALENDAR_NAME)
      put(CalendarContract.Calendars.CALENDAR_COLOR, -0x2b51c9) // auriu BENSON, decorativ
      put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
      put(CalendarContract.Calendars.OWNER_ACCOUNT, TEST_ACCOUNT_NAME)
      put(CalendarContract.Calendars.VISIBLE, 1)
      put(CalendarContract.Calendars.SYNC_EVENTS, 1)
      put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
    }
    val inserted = resolver.insert(uri, values) ?: error("nu pot crea calendarul de test")
    return ContentUris.parseId(inserted)
  }

  private fun queryEventsInRange(resolver: ContentResolver, calendarId: Long?, rangeStart: Long, rangeEnd: Long): List<CalendarEvent> {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
      .appendPath(rangeStart.toString()).appendPath(rangeEnd.toString()).build()
    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
    )
    val selection = if (calendarId != null) "${CalendarContract.Instances.CALENDAR_ID}=?" else null
    val args = if (calendarId != null) arrayOf(calendarId.toString()) else null
    val out = mutableListOf<CalendarEvent>()
    resolver.query(uri, projection, selection, args, null)?.use { c ->
      while (c.moveToNext()) out.add(CalendarEvent(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3)))
    }
    return out
  }

  // C1 CITIRE (EC_CALENDAR spec) — toate calendarele, read-only, fara risc de a strica ceva real.
  fun readDay(resolver: ContentResolver, dayStartMillis: Long, dayEndMillis: Long): List<CalendarEvent> =
    queryEventsInRange(resolver, null, dayStartMillis, dayEndMillis).sortedBy { it.startMillis }

  fun findFreeSlots(resolver: ContentResolver, dayStartMillis: Long, dayEndMillis: Long, durationMinutes: Int): List<Pair<Long, Long>> =
    CalendarEventMath.findFreeSlots(dayStartMillis, dayEndMillis, durationMinutes * 60_000L, readDay(resolver, dayStartMillis, dayEndMillis))

  // DOCTRINA 6 (Confirmation Gate) — doua faze, acelasi tipar ca draftul WhatsApp deja dovedit pe
  // dispozitiv: `confirmed=false` propune si NU scrie nimic; abia `confirmed=true` (pe un apel
  // ulterior, dupa "da" rostit de Master) scrie efectiv. Conflictul se verifica INAINTE de ambele
  // faze si blocheaza indiferent de `confirmed` — niciodata nu se forteaza o suprapunere.
  fun createEvent(resolver: ContentResolver, title: String, startMillis: Long, endMillis: Long, allDay: Boolean, confirmed: Boolean): CalendarActionResult {
    if (title.isBlank()) return CalendarActionResult(false, "Lipsește titlul evenimentului.")
    if (endMillis <= startMillis) return CalendarActionResult(false, "Ora de sfârșit trebuie să fie după ora de început.")
    val calendarId = ensureTestCalendarId(resolver)
    val nearby = queryEventsInRange(resolver, calendarId, startMillis - 86_400_000L, endMillis + 86_400_000L)
    if (CalendarEventMath.isDuplicate(title, startMillis, nearby)) {
      return CalendarActionResult(false, "Există deja „$title” exact la aceeași oră — nu adaug un duplicat.")
    }
    val conflicts = CalendarEventMath.findConflicts(startMillis, endMillis, nearby)
    if (conflicts.isNotEmpty()) {
      return CalendarActionResult(false, "CONFLICT: se suprapune cu „${conflicts.joinToString(", ") { it.title }}”. Alege altă oră.")
    }
    val whenHuman = formatHuman(startMillis, endMillis, allDay)
    if (!confirmed) return CalendarActionResult(false, "PROPUNERE (neconfirmat): adaug „$title”, $whenHuman. Confirm?")

    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DTSTART, startMillis)
      put(CalendarContract.Events.DTEND, endMillis)
      put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
      put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
      put(CalendarContract.Events.HAS_ALARM, 0)
    }
    val inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
      ?: return CalendarActionResult(false, "Scrierea în calendar a eșuat.")
    val eventId = ContentUris.parseId(inserted)
    val verified = resolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
      arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
      null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) == title && c.getLong(1) == startMillis else false } ?: false
    return if (verified) {
      CalendarActionResult(true, "Am adăugat „$title”, $whenHuman.", eventId)
    } else {
      CalendarActionResult(false, "UNKNOWN: am scris evenimentul, dar nu pot confirma prin recitire că s-a salvat corect.", eventId)
    }
  }

  fun updateEvent(resolver: ContentResolver, eventId: Long, newTitle: String?, newStartMillis: Long?, newEndMillis: Long?, confirmed: Boolean): CalendarActionResult {
    val row = resolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
      arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.ALL_DAY),
      null, null, null,
    )?.use { c -> if (c.moveToFirst()) listOf<Any>(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4)) else null }
      ?: return CalendarActionResult(false, "Nu găsesc evenimentul #$eventId.")

    val title = newTitle ?: row[0] as String
    val start = newStartMillis ?: row[1] as Long
    val end = newEndMillis ?: row[2] as Long
    val calendarId = row[3] as Long
    val allDay = (row[4] as Int) == 1
    if (end <= start) return CalendarActionResult(false, "Ora de sfârșit trebuie să fie după ora de început.")

    val nearby = queryEventsInRange(resolver, calendarId, start - 86_400_000L, end + 86_400_000L)
    val conflicts = CalendarEventMath.findConflicts(start, end, nearby, excludeId = eventId)
    if (conflicts.isNotEmpty()) {
      return CalendarActionResult(false, "CONFLICT: noua oră se suprapune cu „${conflicts.joinToString(", ") { it.title }}”.")
    }
    val whenHuman = formatHuman(start, end, allDay)
    if (!confirmed) return CalendarActionResult(false, "PROPUNERE (neconfirmat): modific „$title” -> $whenHuman. Confirm?")

    val values = ContentValues().apply {
      put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DTSTART, start)
      put(CalendarContract.Events.DTEND, end)
    }
    val rows = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null)
    if (rows <= 0) return CalendarActionResult(false, "Actualizarea a eșuat.")
    val verified = resolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
      arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
      null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) == title && c.getLong(1) == start && c.getLong(2) == end else false } ?: false
    return if (verified) {
      CalendarActionResult(true, "Am modificat „$title”, $whenHuman.", eventId)
    } else {
      CalendarActionResult(false, "UNKNOWN: am trimis modificarea, dar nu pot confirma prin recitire noua valoare.", eventId)
    }
  }
}
