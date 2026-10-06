package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ADĂUGARE — ÎNTREBĂRILE LUI BENSON ÎNCEP CU CUVÂNT INTEROGATIV (06.10.2026). Golden tests: fiecare
// șablon nou, plus o verificare generică "începe cu cuvânt interogativ" pentru toate 3.
class WaQuestionTextTest {
  @Test fun call() = assertEquals("Dorești apel WhatsApp cu Hannah?", WaQuestionText.forKind("call", "Hannah", null))
  @Test fun video() = assertEquals("Dorești apel video WhatsApp cu Mama?", WaQuestionText.forKind("video", "Mama", null))
  @Test fun message() = assertEquals("Dorești să trimit mesajul către Ingrid?", WaQuestionText.forKind("message", "Ingrid", "Ajung în 10 minute"))

  // Mesajul propriu-zis nu mai apare rostit (draftul vizibil pe ecran, WA_VISIBLE_DRAFT, acoperă
  // confirmarea conținutului) — verificare explicită să nu reapară accidental.
  @Test fun messageDoesNotSpeakBackContent() =
    assertTrue(!WaQuestionText.forKind("message", "Ingrid", "text secret").contains("text secret"))

  @Test fun allThreeStartWithInterrogativeWord() {
    for (kind in listOf("call", "video", "message")) {
      val q = WaQuestionText.forKind(kind, "X", "y")
      assertTrue("kind=$kind text=$q", q.startsWith("Dorești"))
      assertTrue("kind=$kind text=$q", q.endsWith("?"))
    }
  }
}
