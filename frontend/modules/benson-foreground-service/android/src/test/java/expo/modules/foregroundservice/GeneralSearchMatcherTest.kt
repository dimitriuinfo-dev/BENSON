package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneralSearchMatcherTest {
  @Test fun plainSearch() = assertEquals("farmacie deschisa", GeneralSearchMatcher.extractQuery("caută farmacie deschisă"))
  @Test fun diacriticsAndPunctuation() = assertEquals("madonna", GeneralSearchMatcher.extractQuery("Caută Madonna."))
  @Test fun excludesMapPhrasing() = assertNull(GeneralSearchMatcher.extractQuery("caută o farmacie pe hartă"))
  @Test fun excludesYoutube() = assertNull(GeneralSearchMatcher.extractQuery("cauta madonna pe youtube"))
  @Test fun excludesSpotify() = assertNull(GeneralSearchMatcher.extractQuery("cauta madonna pe spotify"))
}
