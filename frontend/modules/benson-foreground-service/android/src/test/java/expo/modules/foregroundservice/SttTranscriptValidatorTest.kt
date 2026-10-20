package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttTranscriptValidatorTest {

  // CAZUL REAL din testul vocal al Masterului, 14.10.2026 16:13:54.910.
  @Test fun chineseIdeogramFromHallucinationIsRejected() {
    assertFalse(SttTranscriptValidator.isUsable("当"))
    assertEquals("non_latin_transcript", SttTranscriptValidator.rejectReason("当"))
  }

  // Al doilea mod de eșec observat: zgomot de TV transcris in chirilic.
  @Test fun cyrillicNoiseIsRejected() {
    assertFalse(SttTranscriptValidator.isUsable("Привет как дела сегодня вечером"))
  }

  @Test fun emptyAndNullAreRejectedWithTheirOwnReason() {
    assertFalse(SttTranscriptValidator.isUsable(""))
    assertFalse(SttTranscriptValidator.isUsable("   "))
    assertFalse(SttTranscriptValidator.isUsable(null))
    assertEquals("empty_transcript", SttTranscriptValidator.rejectReason(""))
    assertEquals("provider_error_or_timeout", SttTranscriptValidator.rejectReason(null))
  }

  // REGRESIE: tot ce functiona trebuie sa rămână acceptat.
  @Test fun romanianAnswersAreUsable() {
    assertTrue(SttTranscriptValidator.isUsable("da"))
    assertTrue(SttTranscriptValidator.isUsable("nu"))
    assertTrue(SttTranscriptValidator.isUsable("a doua"))
    assertTrue(SttTranscriptValidator.isUsable("Suna la Hana pe WhatsApp."))
  }

  @Test fun romanianAndGermanDiacriticsAreUsable() {
    assertTrue(SttTranscriptValidator.isUsable("mulțumesc"))
    assertTrue(SttTranscriptValidator.isUsable("Măriuța"))
    assertTrue(SttTranscriptValidator.isUsable("Müller"))
    assertTrue(SttTranscriptValidator.isUsable("straße"))
  }

  // O alegere numerica dintr-o lista citita trebuie sa treaca, desi nu are litere.
  @Test fun bareDigitChoiceIsUsable() {
    assertTrue(SttTranscriptValidator.isUsable("2"))
    assertTrue(SttTranscriptValidator.isUsable("3."))
  }

  @Test fun punctuationOnlyIsRejected() {
    assertFalse(SttTranscriptValidator.isUsable("..."))
    assertFalse(SttTranscriptValidator.isUsable("?!"))
  }

  // Un transcript mixt (litere latine + alt alfabet) rămâne acceptat: are continut real.
  @Test fun mixedScriptWithLatinContentIsUsable() {
    assertTrue(SttTranscriptValidator.isUsable("pune 当 pe youtube"))
  }
}
