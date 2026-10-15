package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BensonCommandRegistryTest {

  // Punctul 1 din ordin: un singur registru. Fiecare capabilitate de aplicatie din
  // StaffRegistryCore trebuie sa aiba o comanda prin care Masterul o poate cere; altfel exista o
  // capabilitate despre care BENSON nu stie sa vorbeasca.
  @Test
  fun everyAppCapabilityHasACommand() {
    val caps = listOf(
      StaffRegistryCore.CAP_NAVIGATE,
      StaffRegistryCore.CAP_MEDIA_SEARCH,
      StaffRegistryCore.CAP_MEDIA_CONTROL,
      StaffRegistryCore.CAP_MESSAGE,
      StaffRegistryCore.CAP_CALL,
      StaffRegistryCore.CAP_VIDEO_CALL,
      StaffRegistryCore.CAP_CALCULATE,
      StaffRegistryCore.CAP_SEARCH_PRODUCTS,
      StaffRegistryCore.CAP_WEB_SEARCH,
    )
    val covered = BensonCommandRegistry.ALL.mapNotNull { it.capId }.toSet()
    val missing = caps.filterNot { covered.contains(it) }
    assertTrue("capabilitati fara comanda: $missing", missing.isEmpty())
  }

  @Test
  fun everyEntryIsFullyDescribed() {
    for (c in BensonCommandRegistry.ALL) {
      assertTrue("${c.action}: lipsesc formularile", c.phrasings.isNotEmpty())
      assertTrue("${c.action}: descriere ro", c.descRo.length > 10)
      assertTrue("${c.action}: descriere de", c.descDe.length > 5)
      assertTrue("${c.action}: descriere en", c.descEn.length > 5)
      assertTrue("${c.action}: executor", c.executor.isNotBlank())
    }
  }

  // Punctul 9: nimic neimplementat nu se declara disponibil.
  @Test
  fun unimplementedStaysPlanned() {
    val endCall = BensonCommandRegistry.ALL.first { it.action == "END_CALL" }
    assertEquals(CmdState.PLANIFICAT, endCall.state)
    val cancelSos = BensonCommandRegistry.ALL.first { it.action == "CANCEL_SOS" }
    assertEquals(CmdState.PLANIFICAT, cancelSos.state)
  }

  @Test
  fun stopCommandsAreOneSingleAction() {
    val stop = BensonCommandRegistry.ALL.first { it.action == "UNIVERSAL_ACTION_STOP" }
    assertEquals(listOf("taci", "stop", "oprește", "mute"), stop.phrasings)
    assertEquals("interruptAll", stop.executor)
  }

  @Test
  fun findMatchesTheMasterQuestion() {
    val hits = BensonCommandRegistry.find("cum îți spun să închizi apelul")
    assertTrue(hits.any { it.action == "END_CALL" })
    val stopHits = BensonCommandRegistry.find("ce comandă folosesc ca să te oprești")
    assertTrue(stopHits.any { it.action == "UNIVERSAL_ACTION_STOP" })
  }

  @Test
  fun emptyQueryReturnsWholeRegistry() {
    assertEquals(BensonCommandRegistry.ALL.size, BensonCommandRegistry.find("").size)
  }

  @Test
  fun explainMarksPlannedExplicitly() {
    val text = BensonCommandRegistry.explain("apel")
    assertTrue(text.contains("PLANIFICAT"))
    assertFalse(text.contains("UNTRUSTED"))
  }
}
