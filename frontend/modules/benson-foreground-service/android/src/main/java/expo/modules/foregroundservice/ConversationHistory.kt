package expo.modules.foregroundservice

/**
 * V5 (08.10.2026, Motor) — "Istoric mărginit: CONVERSATION_WINDOW, max 10 schimburi sau 4000 de
 * caractere" (CLAUDE.md, Invariante de arhitectură). Native, pur Kotlin, zero Context — ținut ca
 * instanță de câmp pe BensonForegroundService, nu persistat (se pierde la restart serviciu,
 * intenționat: fără memorie de reguli/conversații între sesiuni, doar "fapte", conform Doctrinei
 * produsului punctul 4 — iar o conversație liberă nu e un fapt).
 */
class ConversationHistory(
  private val maxTurns: Int = 10,
  private val maxChars: Int = 4000,
) {
  data class Turn(val role: String, val text: String)

  private val turns = ArrayDeque<Turn>()

  fun add(role: String, text: String) {
    turns.addLast(Turn(role, text))
    while (turns.size > maxTurns) turns.removeFirst()
    while (totalChars() > maxChars && turns.size > 1) turns.removeFirst()
  }

  private fun totalChars(): Int = turns.sumOf { it.text.length }

  fun snapshot(): List<Turn> = turns.toList()

  fun clear() = turns.clear()
}
