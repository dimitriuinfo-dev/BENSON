package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ADĂUGARE — ÎNTREBĂRILE LUI BENSON ÎNCEP CU CUVÂNT INTEROGATIV (06.10.2026). Golden tests: fiecare
// șablon nou, plus o verificare generică "începe cu cuvânt interogativ" pentru toate 3.
class WaQuestionTextTest {
  @Test fun call() = assertEquals("Dorești apel WhatsApp cu Hannah?", WaQuestionText.forKind("call", "Hannah", null))
  @Test fun video() = assertEquals("Dorești apel video WhatsApp cu Mama?", WaQuestionText.forKind("video", "Mama", null))
  @Test fun message() = assertEquals("Mesaj către Ingrid: «Ajung în 10 minute». Dorești să-l trimit?", WaQuestionText.forKind("message", "Ingrid", "Ajung în 10 minute"))

  // CORECȚIE (06.10.2026): în mașină Rareș nu vede ecranul — conținutul mesajului TREBUIE auzit
  // înainte de "Da sau nu", WA_VISIBLE_DRAFT (ecran) nu ajunge acolo. Invers față de runda
  // anterioară: verificare explicită că textul rostit CONȚINE mesajul, nu îl mai ascunde.
  @Test fun messageSpeaksBackContent() =
    assertTrue(WaQuestionText.forKind("message", "Ingrid", "text important").contains("text important"))

  // call/video: întreaga propoziție e întrebarea, începe cu cuvântul interogativ. message: aceeași
  // regulă se aplică doar propoziției finale (prima propoziție e conținutul de confirmat, nu o
  // întrebare) — ambele cazuri verificate separat, dar toate 3 se termină cu "?" un cuvânt real.
  @Test fun callAndVideoStartWithInterrogativeWord() {
    for (kind in listOf("call", "video")) {
      val q = WaQuestionText.forKind(kind, "X", null)
      assertTrue("kind=$kind text=$q", q.startsWith("Dorești"))
      assertTrue("kind=$kind text=$q", q.endsWith("?"))
    }
  }

  @Test fun messageEndsWithInterrogativeQuestion() {
    val q = WaQuestionText.forKind("message", "X", "y")
    assertTrue(q, q.endsWith("Dorești să-l trimit?"))
  }
}
