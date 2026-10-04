package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
