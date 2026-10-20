package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitWebSearchMatcherTest {

  // Fraza reala a Masterului, 14.10.2026 — cea care a fost capturata greșit de Amazon.
  @Test fun realMasterPhraseIsRecognized() {
    assertEquals(
      "robot de curatat ferestrele pana la 80 euro",
      ExplicitWebSearchMatcher.extractQuery("caută în internet robot de curățat ferestrele până la 80 euro"),
    )
  }

  @Test fun acceptsPeInternetAndGoogleAndWeb() {
    assertEquals("bilete la film", ExplicitWebSearchMatcher.extractQuery("caută pe internet bilete la film"))
    assertEquals("vremea maine", ExplicitWebSearchMatcher.extractQuery("caută pe google vremea maine"))
    assertEquals("preturi incarcator", ExplicitWebSearchMatcher.extractQuery("caută pe web preturi incarcator"))
  }

  @Test fun acceptsTrailingForm() {
    assertEquals("robot aspirator", ExplicitWebSearchMatcher.extractQuery("caută robot aspirator pe internet"))
  }

  @Test fun acceptsGoogleste() {
    assertEquals("cinema timisoara", ExplicitWebSearchMatcher.extractQuery("googlește cinema timisoara"))
  }

  // Esential: NU fura comenzile existente, deja dovedite.
  @Test fun plainCautaStaysWithTheExistingGenericFallback() {
    assertNull(ExplicitWebSearchMatcher.extractQuery("caută farmacie deschisă"))
    assertNull(ExplicitWebSearchMatcher.extractQuery("caută baterii AA"))
  }

  @Test fun youtubeAndSpotifyPhrasingUntouched() {
    assertNull(ExplicitWebSearchMatcher.extractQuery("caută madonna pe youtube"))
    assertNull(ExplicitWebSearchMatcher.extractQuery("pune justify my love"))
  }

  @Test fun navigationIsNeverStolen() {
    assertNull(ExplicitWebSearchMatcher.extractQuery("du-mă la aeroport"))
    assertNull(ExplicitWebSearchMatcher.extractQuery("navighează la gară pe internet"))
  }

  @Test fun emptyQueryRejected() {
    assertNull(ExplicitWebSearchMatcher.extractQuery("caută pe internet"))
  }
}

class FirstResultMatcherTest {
  @Test fun recognizesMasterPhrasing() {
    assertTrue(FirstResultMatcher.matches("apasă pe primul rezultat"))
    assertTrue(FirstResultMatcher.matches("apasa primul link"))
    assertTrue(FirstResultMatcher.matches("deschide primul rezultat"))
    assertTrue(FirstResultMatcher.matches("alege prima varianta"))
    assertTrue(FirstResultMatcher.matches("deschide primul produs"))
  }

  @Test fun doesNotStealOtherPresses() {
    assertFalse(FirstResultMatcher.matches("apasă pe play"))
    assertFalse(FirstResultMatcher.matches("apasă pe pauză"))
    assertFalse(FirstResultMatcher.matches("deschide youtube"))
    assertFalse(FirstResultMatcher.matches("da"))
    assertFalse(FirstResultMatcher.matches("apasă pe al doilea rezultat"))
  }
}
