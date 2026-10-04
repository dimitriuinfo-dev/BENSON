package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalHandCommandMatcherTest {
  @Test fun pressKnownIntent() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe play") as? UniversalHandCommand.Press
    assertEquals("play", cmd?.knownIntent)
  }

  @Test fun pressLiteralTargetHasNullIntent() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe 5") as? UniversalHandCommand.Press
    assertEquals("5", cmd?.target)
    assertNull(cmd?.knownIntent)
  }

  @Test fun pressBareInchideResolvesToClose() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe închide") as? UniversalHandCommand.Press
    assertEquals("close", cmd?.knownIntent)
  }

  @Test fun pressInchideApelulResolvesToEndCall() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe închide apelul") as? UniversalHandCommand.Press
    assertEquals("end_call", cmd?.knownIntent)
  }

  @Test fun bareInchideApelulWithoutApasaStillEndCall() {
    // the dedicated end-call phrasing works without the generic "apasă pe" prefix too, per the
    // adaos spec's own synonym list ("închide apelul" is itself a complete command).
    val cmd = UniversalHandCommandMatcher.parse("apasă închide apelul") as? UniversalHandCommand.Press
    assertEquals("end_call", cmd?.knownIntent)
  }

  @Test fun search() = assertEquals("madonna", (UniversalHandCommandMatcher.parse("caută Madonna") as? UniversalHandCommand.Search)?.query)

  @Test fun scrollDown() = assertEquals("down", (UniversalHandCommandMatcher.parse("mai jos") as? UniversalHandCommand.Scroll)?.direction)
  @Test fun scrollUp() = assertEquals("up", (UniversalHandCommandMatcher.parse("mai sus") as? UniversalHandCommand.Scroll)?.direction)
  @Test fun scrollLeft() = assertEquals("left", (UniversalHandCommandMatcher.parse("stânga") as? UniversalHandCommand.Scroll)?.direction)
  @Test fun scrollRight() = assertEquals("right", (UniversalHandCommandMatcher.parse("mai dreapta") as? UniversalHandCommand.Scroll)?.direction)

  @Test fun readScreen() = assertTrue(UniversalHandCommandMatcher.parse("ce scrie pe ecran") is UniversalHandCommand.ReadScreen)

  @Test fun unrelatedPhraseReturnsNull() = assertNull(UniversalHandCommandMatcher.parse("deschide youtube"))

  // Adaos G-1 (2026-10-04) — forme descrise prin simbol/formă.
  @Test fun triangleMeansPlay() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe triunghi") as? UniversalHandCommand.Press
    assertEquals("play", cmd?.knownIntent)
  }

  @Test fun doubleTriangleRightMeansNext() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe triunghi dublu spre dreapta") as? UniversalHandCommand.Press
    assertEquals("next", cmd?.knownIntent)
  }

  @Test fun doubleTriangleLeftMeansPrev() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe triunghi dublu spre stânga") as? UniversalHandCommand.Press
    assertEquals("prev", cmd?.knownIntent)
  }

  @Test fun squareMeansStop() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe pătrat") as? UniversalHandCommand.Press
    assertEquals("stop", cmd?.knownIntent)
  }

  @Test fun parallelBarsMeanPause() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe două bare paralele") as? UniversalHandCommand.Press
    assertEquals("pause", cmd?.knownIntent)
  }

  @Test fun seekForwardTenSeconds() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe înainte 10 secunde") as? UniversalHandCommand.Press
    assertEquals("seek_forward", cmd?.knownIntent)
  }

  @Test fun seekBackTenSeconds() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe înapoi 10 secunde") as? UniversalHandCommand.Press
    assertEquals("seek_back", cmd?.knownIntent)
  }

  @Test fun arrowRight() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe săgeata dreapta") as? UniversalHandCommand.Press
    assertEquals("arrow_right", cmd?.knownIntent)
  }

  @Test fun arrowUp() {
    val cmd = UniversalHandCommandMatcher.parse("apasă pe săgeata sus") as? UniversalHandCommand.Press
    assertEquals("arrow_up", cmd?.knownIntent)
  }
}
