package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {
  @Test fun emptyByDefault() {
    assertEquals(0, ConversationHistory().snapshot().size)
  }

  @Test fun keepsOrderOfAddedTurns() {
    val h = ConversationHistory()
    h.add("user", "salut")
    h.add("assistant", "salut si tie")
    val snap = h.snapshot()
    assertEquals(listOf("user", "assistant"), snap.map { it.role })
    assertEquals(listOf("salut", "salut si tie"), snap.map { it.text })
  }

  @Test fun capsAtMaxTurns() {
    val h = ConversationHistory(maxTurns = 4, maxChars = 100_000)
    for (i in 1..10) h.add("user", "mesaj $i")
    assertEquals(4, h.snapshot().size)
    // oldest dropped first — the 4 remaining are the most recent
    assertEquals(listOf("mesaj 7", "mesaj 8", "mesaj 9", "mesaj 10"), h.snapshot().map { it.text })
  }

  @Test fun capsAtMaxChars() {
    val h = ConversationHistory(maxTurns = 100, maxChars = 20)
    h.add("user", "0123456789") // 10 chars
    h.add("user", "0123456789") // 20 chars total, still within cap
    h.add("user", "0123456789") // 30 chars total -> must drop oldest until <= 20
    assertTrue("total chars must stay within cap", h.snapshot().sumOf { it.text.length } <= 20)
  }

  @Test fun clearEmptiesHistory() {
    val h = ConversationHistory()
    h.add("user", "x")
    h.clear()
    assertEquals(0, h.snapshot().size)
  }
}
