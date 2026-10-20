package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// RUNDA VOICE-ONLY — alegerea rostita dintr-o lista citita cu voce.
class OrdinalMatcherTest {

  // Formele NOI, cerute explicit de Master.
  @Test fun ordinalFormsMasculine() {
    assertEquals(1, OrdinalMatcher.parse("primul"))
    assertEquals(2, OrdinalMatcher.parse("al doilea"))
    assertEquals(3, OrdinalMatcher.parse("al treilea"))
    assertEquals(4, OrdinalMatcher.parse("al patrulea"))
  }

  @Test fun ordinalFormsFeminine() {
    assertEquals(1, OrdinalMatcher.parse("prima"))
    assertEquals(2, OrdinalMatcher.parse("a doua"))
    assertEquals(3, OrdinalMatcher.parse("a treia"))
  }

  @Test fun ordinalsInsideAFullSentence() {
    assertEquals(2, OrdinalMatcher.parse("Dă-mi a doua variantă, te rog."))
    assertEquals(3, OrdinalMatcher.parse("vreau al treilea rezultat"))
    assertEquals(1, OrdinalMatcher.parse("deschide prima"))
  }

  @Test fun worksWithoutDiacriticsAsSttProducesThem() {
    assertEquals(2, OrdinalMatcher.parse("a doua"))
    assertEquals(3, OrdinalMatcher.parse("al treilea"))
    assertEquals(1, OrdinalMatcher.parse("intai"))
  }

  // REGRESIE: formele vechi, pe care WhatsApp si lista de aplicatii se bazeaza deja.
  @Test fun digitsStillWin() {
    assertEquals(2, OrdinalMatcher.parse("2"))
    assertEquals(3, OrdinalMatcher.parse("numărul 3"))
  }

  @Test fun cardinalsStillWork() {
    assertEquals(1, OrdinalMatcher.parse("unu"))
    assertEquals(2, OrdinalMatcher.parse("doi"))
    assertEquals(3, OrdinalMatcher.parse("trei"))
    assertEquals(1, OrdinalMatcher.parse("una"))
  }

  @Test fun noNumberMeansNull() {
    assertNull(OrdinalMatcher.parse("nu am înțeles"))
    assertNull(OrdinalMatcher.parse(""))
    assertNull(OrdinalMatcher.parse("da"))
  }

  // "a doua" trebuie sa dea 2 si prin ramura ordinala si prin cardinalul "doua" — acelasi rezultat.
  @Test fun doubleFormIsStable() {
    assertEquals(2, OrdinalMatcher.parse("doua"))
    assertEquals(2, OrdinalMatcher.parse("două"))
  }
}
