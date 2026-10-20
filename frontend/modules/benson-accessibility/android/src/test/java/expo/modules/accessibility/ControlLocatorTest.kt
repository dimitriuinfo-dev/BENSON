package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLocatorTest {
  // Mirrors the real WhatsApp dump captured on-device (2026-10-03): the search affordance has no
  // text, only a contentDescription ("Meta AI fragen oder suchen").
  private val whatsappToolbar = UiNode(
    children = listOf(
      UiNode(text = "zurück", viewId = "com.whatsapp:id/whatsapp_toolbar_home", clickable = true),
      UiNode(desc = "Meta AI fragen oder suchen", viewId = "com.whatsapp:id/search_bar_inner_layout", clickable = true),
      UiNode(text = "hannah", viewId = "com.whatsapp:id/conversation_contact_name"),
    ),
  )

  @Test fun findsByContentDescriptionFirst() {
    val found = ControlLocator.findControl(ControlSynonyms.wordsFor("search"), whatsappToolbar)
    assertEquals("com.whatsapp:id/search_bar_inner_layout", found?.viewId)
  }

  @Test fun returnsNullWhenNothingMatches() =
    assertNull(ControlLocator.findControl(ControlSynonyms.wordsFor("play"), whatsappToolbar))

  @Test fun clickableTargetReturnsSelfWhenClickable() {
    val node = whatsappToolbar.children[1]
    assertSame(node, ControlLocator.clickableTarget(node, whatsappToolbar))
  }

  @Test fun clickableTargetWalksUpToClickableAncestor() {
    val leaf = UiNode(text = "play")
    val clickableParent = UiNode(clickable = true, children = listOf(leaf))
    val root = UiNode(children = listOf(clickableParent))
    assertSame(clickableParent, ControlLocator.clickableTarget(leaf, root))
  }

  @Test fun clickableTargetReturnsNullWhenNoneClickable() {
    val leaf = UiNode(text = "play")
    val root = UiNode(children = listOf(UiNode(children = listOf(leaf))))
    assertNull(ControlLocator.clickableTarget(leaf, root))
  }

  @Test fun matchesByTextWhenNoDescription() {
    val root = UiNode(children = listOf(UiNode(text = "Pauză", clickable = true)))
    assertEquals("Pauză", ControlLocator.findControl(ControlSynonyms.wordsFor("pause"), root)?.text)
  }

  // RUNDA MUSIC-2 — generic result-row finder, no app-specific anchors.
  private fun noOpNoise(label: String, query: String) = false

  @Test fun findFirstResultSkipsTheSearchFieldItself() {
    val root = UiNode(
      children = listOf(
        UiNode(text = "justify my love", editable = true, clickable = true),
        UiNode(text = "Justify My Love - Madonna", clickable = true),
      ),
    )
    assertEquals("Justify My Love - Madonna", ControlLocator.findFirstResult("justify my love", root, ::noOpNoise)?.text)
  }

  @Test fun findFirstResultSkipsNoiseViaCallback() {
    val root = UiNode(
      children = listOf(
        UiNode(text = "Mehr Optionen für den Song Justify My Love", clickable = true),
        UiNode(text = "Justify My Love - Madonna", clickable = true),
      ),
    )
    val isNoise = { label: String, _: String -> label.contains("Mehr Optionen") }
    assertEquals("Justify My Love - Madonna", ControlLocator.findFirstResult("justify my love", root, isNoise)?.text)
  }

  @Test fun findFirstResultNullWhenNothingMatches() {
    val root = UiNode(children = listOf(UiNode(text = "Unrelated", clickable = true)))
    assertNull(ControlLocator.findFirstResult("justify my love", root, ::noOpNoise))
  }

  // --- findButtonCandidates — rezolvor semantic generic (19.10.2026, raspuns la DEVICE_FAIL
  // Calculator repetat: selectie de INDEX nesigura a modelului). Testeaza exact scenariile cerute
  // (punctul 8): ordine schimbata, etichete duplicate, index vechi devenit invalid (irelevant aici
  // — functia e pura, fara stare intre apeluri), buton negasit. ---

  private val calculatorKeypad = UiNode(
    children = listOf(
      UiNode(text = "AC", viewId = "com.oneplus.calculator:id/clr", clickable = true),
      UiNode(text = "7", viewId = "com.oneplus.calculator:id/digit_7", clickable = true),
      UiNode(text = "8", viewId = "com.oneplus.calculator:id/digit_8", clickable = true),
      UiNode(text = "9", viewId = "com.oneplus.calculator:id/digit_9", clickable = true),
      UiNode(desc = "Multiplizieren", viewId = "com.oneplus.calculator:id/op_mul", clickable = true),
      UiNode(text = "2", viewId = "com.oneplus.calculator:id/digit_2", clickable = true),
    ),
  )

  @Test fun findsExactTextMatchUniquely() {
    val candidates = ControlLocator.findButtonCandidates("7", calculatorKeypad)
    assertEquals(1, candidates.size)
    assertEquals("com.oneplus.calculator:id/digit_7", candidates.single().viewId)
  }

  @Test fun findsExactDescriptionMatchUniquely() {
    val candidates = ControlLocator.findButtonCandidates("Multiplizieren", calculatorKeypad)
    assertEquals(1, candidates.size)
    assertEquals("com.oneplus.calculator:id/op_mul", candidates.single().viewId)
  }

  @Test fun nodeOrderDoesNotAffectResolution() {
    // Acelasi arbore, ordine inversa a copiilor — rezultatul trebuie sa fie identic (functie
    // PURA, fara index memorat, fara pozitie dependenta de ordine).
    val reordered = UiNode(children = calculatorKeypad.children.reversed())
    val candidates = ControlLocator.findButtonCandidates("8", reordered)
    assertEquals(1, candidates.size)
    assertEquals("com.oneplus.calculator:id/digit_8", candidates.single().viewId)
  }

  @Test fun twoElementsWithSameLabelAreReportedAsAmbiguous() {
    val root = UiNode(
      children = listOf(
        UiNode(text = "7", viewId = "a/digit_7_main", clickable = true),
        UiNode(text = "7", viewId = "a/digit_7_duplicate_overlay", clickable = true),
      ),
    )
    val candidates = ControlLocator.findButtonCandidates("7", root)
    assertEquals(2, candidates.size) // ambiguu — apelantul NU trebuie sa aleaga orbeste intre ele
  }

  @Test fun buttonNotPresentReturnsEmptyNotAGuess() {
    assertTrue(ControlLocator.findButtonCandidates("99", calculatorKeypad).isEmpty())
  }

  @Test fun nonClickableElementsAreNeverCandidates() {
    // Un TextView cu acelasi text, dar neclicabil - nu e un buton, nu trebuie oferit ca tinta.
    val root = UiNode(children = listOf(UiNode(text = "7", clickable = false)))
    assertTrue(ControlLocator.findButtonCandidates("7", root).isEmpty())
  }

  @Test fun fallsBackToPartialMatchOnlyWhenNoExactMatchExists() {
    val root = UiNode(children = listOf(UiNode(desc = "Șterge ultimul caracter", clickable = true)))
    val candidates = ControlLocator.findButtonCandidates("sterge", root)
    assertEquals(1, candidates.size)
  }

  @Test fun blankLabelNeverMatchesAnything() {
    assertTrue(ControlLocator.findButtonCandidates("", calculatorKeypad).isEmpty())
  }

  // --- visibleClickableLabels — FIX_PRESS_BUTTON_LABEL_MISMATCH_1 (19.10.2026). Cauza reala
  // dovedita in rularea 5: press_button(label="×") nu gasea nimic pentru ca eticheta reala a
  // butonului e cuvantul german "Multiplizieren" (desc), nu simbolul vizual. Functia nu traduce
  // nimic — doar expune, generic, ce e CU ADEVARAT clickabil acum, ca agentul (inteligenta
  // multilingva comuna, deja existenta) sa poata recunoaste singur echivalenta. ---

  @Test fun visibleClickableLabelsExposesTheRealLabelEvenWhenTheSymbolSearchFails() {
    assertTrue(ControlLocator.findButtonCandidates("×", calculatorKeypad).isEmpty())
    val visible = ControlLocator.visibleClickableLabels(calculatorKeypad)
    assertTrue(visible.contains("Multiplizieren"))
  }

  @Test fun visibleClickableLabelsExcludesNonClickableAndBlankLabels() {
    val root = UiNode(
      children = listOf(
        UiNode(text = "AC", clickable = true),
        UiNode(text = "ignorat", clickable = false),
        UiNode(text = "", desc = "", clickable = true),
      ),
    )
    assertEquals(listOf("AC"), ControlLocator.visibleClickableLabels(root))
  }

  @Test fun visibleClickableLabelsDeduplicatesAndRespectsLimit() {
    val root = UiNode(
      children = listOf(
        UiNode(text = "A", clickable = true),
        UiNode(text = "A", clickable = true),
        UiNode(text = "B", clickable = true),
        UiNode(text = "C", clickable = true),
      ),
    )
    assertEquals(listOf("A", "B"), ControlLocator.visibleClickableLabels(root, limit = 2))
  }
}
