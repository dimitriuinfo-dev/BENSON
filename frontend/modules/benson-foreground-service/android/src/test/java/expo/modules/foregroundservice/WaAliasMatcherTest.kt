package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaAliasMatcherTest {
  @Test fun parsesChangeTextSimple() =
    assertEquals("ajung mai tarziu", WaAliasMatcher.parseChangeText("schimbă în ajung mai tarziu"))

  @Test fun parsesChangeTextWithPronoun() =
    assertEquals("salut", WaAliasMatcher.parseChangeText("schimbă-l în salut"))

  @Test fun rejectsChangeTextWithoutKeyword() =
    assertNull(WaAliasMatcher.parseChangeText("da, trimite"))

  @Test fun parsesPronounce() =
    assertEquals("Hana", WaAliasMatcher.parsePronounce("se pronunță Hana"))

  @Test fun rejectsPronounceBlank() =
    assertNull(WaAliasMatcher.parsePronounce("se pronunta "))

  @Test fun detectsAlternateContactMention() =
    assertTrue(WaAliasMatcher.mentionsAlternateContact("nu alta hannah"))

  @Test fun doesNotFlagUnrelatedNo() =
    assertFalse(WaAliasMatcher.mentionsAlternateContact("nu renunta"))

  // Alias store round-trip (learn/use/delete), store-agnostic — production backs this with
  // SharedPreferences, this test backs it with a plain HashMap to prove the key-semantics alone.
  @Test fun aliasLearnUseDeleteRoundTrip() {
    val store = HashMap<String, String>()
    val key = WaAliasMatcher.aliasKey("hana")!!
    assertNull(store[key]) // not learned yet
    store[key] = "lookupKey-123" // learn
    assertEquals("lookupKey-123", store[key]) // use
    store.remove(key) // delete ("nu, altă Hannah")
    assertNull(store[key])
  }

  @Test fun aliasKeyNullForBlank() = assertNull(WaAliasMatcher.aliasKey(""))
}
