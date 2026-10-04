package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaMessageExtractorTest {
  // POARTA (WA-3): "întreab-o pe Hannah dacă vine diseară" → destinatar Hannah, instrucțiune de
  // tip întrebare (conține "daca"). Output is normalized (lowercase) — see file-level comment.
  @Test fun extractsAskInstruction() {
    val result = WaMessageExtractor.extractAskInstruction("întreab-o pe Hannah dacă vine diseară")
    assertEquals("hannah", result?.recipient)
    assertEquals("daca vine diseara", result?.instruction)
    assertTrue(result?.instruction?.contains("daca") == true)
  }

  @Test fun extractsAskInstructionWithCe() {
    val result = WaMessageExtractor.extractAskInstruction("întreabă-l pe Mihai ce face diseară")
    assertEquals("mihai", result?.recipient)
    assertEquals("ce face diseara", result?.instruction)
  }

  // FIX_WA_NORMALIZE_BEFORE_MATCH_1 — exact failing transcript from today's device test
  // (2026-10-03): "benson întreabă pe hana când vine de seară", after stripWakeWordNative removes
  // "benson". Zero clitic ("întreabă" bare, no "-o"/"-l"), and Deepgram said "când" not "dacă" —
  // both were previously unhandled (CLAUDE.md "Comportamente dovedite" regression otherwise).
  @Test fun realFailingTranscriptNoClitic() {
    val result = WaMessageExtractor.extractAskInstruction("întreabă pe hana când vine de seară")
    assertEquals("hana", result?.recipient)
    assertEquals("cand vine de seara", result?.instruction)
  }

  @Test fun bareSpaceClitic() {
    val result = WaMessageExtractor.extractAskInstruction("întreabă o pe Hannah dacă vine")
    assertEquals("hannah", result?.recipient)
  }

  @Test fun widensToOtherInterrogatives() {
    assertEquals("unde este", WaMessageExtractor.extractAskInstruction("întreabă-l pe Mihai unde este")?.instruction)
    assertEquals("cum se simte", WaMessageExtractor.extractAskInstruction("întreabă-o pe Ana cum se simte")?.instruction)
  }

  @Test fun rejectsNonAskPhrasing() = assertNull(WaMessageExtractor.extractAskInstruction("scrie-i lui Hannah că vin"))

  // POARTA: fallback — creier indisponibil → text STT.
  @Test fun fallsBackToRawSttWhenBrainUnavailable() =
    assertEquals("vin mai tarziu", WaMessageExtractor.selectFinalMessage(null, "vin mai tarziu"))

  @Test fun usesComposedWhenBrainAvailable() =
    assertEquals("Vii diseară?", WaMessageExtractor.selectFinalMessage("Vii diseară?", "dacă vine diseară"))
}
