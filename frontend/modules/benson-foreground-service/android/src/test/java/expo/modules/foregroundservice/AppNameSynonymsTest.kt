package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameSynonymsTest {
  @Test fun stripsUlSuffix() = assertEquals("calculator", AppNameSynonyms.stripRomanianArticle("calculatorul"))
  @Test fun stripsUluiSuffixBeforeUl() = assertEquals("calculator", AppNameSynonyms.stripRomanianArticle("calculatorului"))
  @Test fun stripsLeSuffix() = assertEquals("setari", AppNameSynonyms.stripRomanianArticle("setarile"))
  @Test fun leavesUnarticulatedWordAlone() = assertEquals("calculator", AppNameSynonyms.stripRomanianArticle("calculator"))
  @Test fun refusesToStripBelowMinLength() = assertEquals("a", AppNameSynonyms.stripRomanianArticle("a"))

  @Test fun calculatorulExpandsToRechner() =
    assertTrue(AppNameSynonyms.expandTarget("calculatorul").contains("rechner"))

  @Test fun calculatorExpandsToRechner() =
    assertTrue(AppNameSynonyms.expandTarget("calculator").contains("rechner"))

  @Test fun setarileExpandsToEinstellungen() =
    assertTrue(AppNameSynonyms.expandTarget("setarile").contains("einstellungen"))

  @Test fun cameraExpandsToKamera() =
    assertTrue(AppNameSynonyms.expandTarget("camera").contains("kamera"))

  @Test fun ceasExpandsToUhr() =
    assertTrue(AppNameSynonyms.expandTarget("ceas").contains("uhr"))

  @Test fun mesajeExpandsToNachrichten() =
    assertTrue(AppNameSynonyms.expandTarget("mesaje").contains("nachrichten"))

  @Test fun telefonExpandsToPhone() =
    assertTrue(AppNameSynonyms.expandTarget("telefon").contains("phone"))

  // German spoken form passed straight through, matches its own group directly — no stripping needed.
  @Test fun einstellungenStaysAndMatchesGroup() =
    assertTrue(AppNameSynonyms.expandTarget("einstellungen").contains("setari"))

  // An aggressive strip of an unrelated word must never remove the original from consideration —
  // AppNameMatcher scores every variant and takes the best, so a bad extra variant is harmless.
  @Test fun unrelatedWordKeepsOriginalAmongVariants() =
    assertTrue(AppNameSynonyms.expandTarget("whatsapp").contains("whatsapp"))

  // Punctul 4 din specificatie — BENSON rosteste numele in limba turei, nu eticheta de sistem.
  @Test fun canonicalNameRomanianForDefiniteArticleForm() =
    assertEquals("Calculatorul", AppNameSynonyms.canonicalName("calculatorul", "ro"))

  @Test fun canonicalNameGermanWhenTurnIsGerman() =
    assertEquals("Rechner", AppNameSynonyms.canonicalName("calculatorul", "de"))

  @Test fun canonicalNameNullForUnknownApp() =
    assertNull(AppNameSynonyms.canonicalName("whatsapp", "ro"))
}
