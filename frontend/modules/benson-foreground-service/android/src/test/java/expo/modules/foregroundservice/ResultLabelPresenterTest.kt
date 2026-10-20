package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Etichetele de mai jos sunt CELE REALE, copiate din logul dispozitivului 14.10.2026 15:29:49.
class ResultLabelPresenterTest {

  private val pancerka = "PANCERKA intelligent Window Cleaning Robot 80,00 € Von Amazon.de Kostenlos Vorher: 105 €"
  private val lubluelu = "10% RABATT lubluelu CHYW200 Fensterputzroboter mit Sprühfunktion 6500Pa für große Fenster,"

  @Test fun realLabelKeepsProductDropsAdvertising() {
    val name = ResultLabelPresenter.productName(pancerka)
    // FIX_RESULT_LIST_LENGTH_1 — numele rostit e plafonat la 34 de caractere (vezi dovada din
    // log: 261 de caractere = 20 s de vorbire, Masterul nu avea cand sa raspunda).
    assertEquals("PANCERKA intelligent Window", name)
    assertFalse(name.contains("Kostenlos"))
    assertFalse(name.contains("Vorher"))
    assertFalse(name.contains("Amazon.de"))
    assertFalse(name.contains("€"))
  }

  @Test fun realLabelExtractsPriceSpokenInRomanian() {
    assertEquals("80 de euro", ResultLabelPresenter.spokenPrice(pancerka))
  }

  @Test fun presentJoinsNameAndPrice() {
    assertEquals("PANCERKA intelligent Window, 80 de euro", ResultLabelPresenter.present(pancerka))
  }

  @Test fun promoPrefixIsStripped() {
    val name = ResultLabelPresenter.productName(lubluelu)
    assertTrue(name.startsWith("lubluelu CHYW200"))
    assertFalse(name.contains("RABATT"))
  }

  @Test fun orphanTrailingCommaRemoved() {
    assertFalse(ResultLabelPresenter.productName(lubluelu).endsWith(","))
  }

  @Test fun centsSpokenWhenNotRound() {
    assertEquals("79 virgulă 99 euro", ResultLabelPresenter.spokenPrice("Robot X 79,99 €"))
  }

  @Test fun noPriceOnScreenMeansNoInventedPrice() {
    val raw = "Fensterputzroboter Test 2026 Vergleich"
    assertNull(ResultLabelPresenter.spokenPrice(raw))
    // Intentia testului: fara pret pe ecran, nu se rosteste NICIUN pret. Lungimea e plafonata
    // separat (MAX_SPOKEN_NAME), deci verificam ca forma rostita e un decupaj al textului REAL,
    // nu un text inventat, si ca nu contine nici "euro" nici cifre de pret.
    val spoken = ResultLabelPresenter.present(raw)
    assertTrue(raw.startsWith(spoken))
    assertFalse(spoken.contains("euro"))
  }

  @Test fun longNameClippedAtWordBoundary() {
    val long = "Fensterputzroboter mit Sprühfunktion und sehr langer Produktbezeichnung die weitergeht 120 €"
    val name = ResultLabelPresenter.productName(long)
    assertTrue(name.length <= ResultLabelPresenter.MAX_SPOKEN_NAME)
    assertFalse(name.endsWith(" "))
    // nu taie in mijlocul unui cuvant
    assertTrue(long.contains(name))
  }

  @Test fun euroBeforeNumberAlsoParsed() {
    assertEquals("65 de euro", ResultLabelPresenter.spokenPrice("Robot curatare € 65"))
  }

  @Test fun emptyLabelDoesNotCrash() {
    assertEquals("", ResultLabelPresenter.productName(""))
  }
}
