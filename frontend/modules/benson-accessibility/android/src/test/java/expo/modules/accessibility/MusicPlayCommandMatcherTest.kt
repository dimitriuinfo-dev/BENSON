package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlayCommandMatcherTest {
  @Test fun pune() = assertEquals(MusicPlayCommand(MusicPlayVerb.PUNE, "madonna"), MusicPlayCommandMatcher.parse("pune Madonna"))
  @Test fun canta() = assertEquals(MusicPlayCommand(MusicPlayVerb.CANTA, "justify my love"), MusicPlayCommandMatcher.parse("Cântă Justify My Love."))
  @Test fun cauta() = assertEquals(MusicPlayCommand(MusicPlayVerb.CAUTA, "inna"), MusicPlayCommandMatcher.parse("caută inna"))
  @Test fun unrelatedReturnsNull() = assertNull(MusicPlayCommandMatcher.parse("deschide spotify"))
  @Test fun blankQueryReturnsNull() = assertNull(MusicPlayCommandMatcher.parse("pune"))
}
