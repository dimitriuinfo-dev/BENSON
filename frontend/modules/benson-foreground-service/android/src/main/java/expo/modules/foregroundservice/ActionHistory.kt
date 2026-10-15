package expo.modules.foregroundservice

// FIX_HISTORY_SEPARATION_1 (15.10.2026) — CAUZA DOVEDITA a plangerii Masterului: un raspuns despre
// automobile electrice amesteca streaming muzical, Google Maps, Waze si Amazon.
//
// De ce se intampla: notele despre ACTIUNI erau scrise in `conversationHistory` ca replici de
// ASISTENT, deci modelul le citea ca si cum BENSON i-ar fi spus Masterului acele lucruri:
//   [Am ieșit din ecranul curent]  ·  [Am mers la ecranul principal]  ·  [Am deschis $app]
//   [Am propus deschiderea $label — aștept confirmare]  ·  [Caut $query pe YouTube]
// Iar `conversationHistory.clear()` nu era apelat NICIODATA (verificat: 0 ocurente in tot codul),
// deci fereastra de 10 schimburi continea testele de radio/Waze/Amazon de dinainte. Pur si simplu
// conversatia Masterului si jurnalul de actiuni erau acelasi lucru.
//
// Separarea: dialogul REAL (ce a spus Masterul, ce a raspuns BENSON cu voce) rămâne in
// ConversationHistory si ajunge la model. Ce a FACUT BENSON pe telefon intra aici si NU se trimite
// ca replica de conversatie. Agentul isi vede oricum rezultatele instrumentelor in propria rulare.
//
// Deliberat NU golim nimic automat la schimbarea de subiect (interdictie explicita a Masterului),
// si NU stergem memoria utilizatorului.
// Revert: `remember()` scrie din nou in conversationHistory.
class ActionHistory(private val maxEntries: Int = 20) {
  data class Entry(val atMs: Long, val note: String)

  private val entries = ArrayDeque<Entry>()

  fun add(note: String) {
    entries.addLast(Entry(System.currentTimeMillis(), note))
    while (entries.size > maxEntries) entries.removeFirst()
  }

  fun snapshot(): List<Entry> = entries.toList()

  /** Ultimele actiuni, pentru diagnostic si pentru un eventual instrument `staff_changes`. */
  fun recent(n: Int = 5): List<String> = entries.takeLast(n).map { it.note }

  fun clear() = entries.clear()
}
