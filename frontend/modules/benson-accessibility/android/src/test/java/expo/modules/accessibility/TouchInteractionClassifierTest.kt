package expo.modules.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInteractionClassifierTest {
  // "actualizare de conținut fără atingere → curățenie executată": content_changed is not a
  // tracked type at all, so it never counts as a user interaction — cleanup proceeds.
  @Test fun contentChangedNeverCountsAsInteraction() =
    assertFalse(TouchInteractionClassifier.isUserInteraction("content_changed", 10_000L, 0L))

  // "click de la utilizator → skip": a real click, well outside BENSON's own 1s action window.
  @Test fun realUserClickCountsAsInteraction() =
    assertTrue(TouchInteractionClassifier.isUserInteraction("click", 10_000L, lastSelfActionAtMs = 0L))

  @Test fun scrollCountsAsInteraction() =
    assertTrue(TouchInteractionClassifier.isUserInteraction("scroll", 10_000L, lastSelfActionAtMs = 0L))

  @Test fun textChangedCountsAsInteraction() =
    assertTrue(TouchInteractionClassifier.isUserInteraction("text_changed", 10_000L, lastSelfActionAtMs = 0L))

  // A click arriving within 1s of BENSON's own last action (its own send-button tap, say) is
  // excluded — it's BENSON's hand, not Rareș's.
  @Test fun clickWithinSelfActionWindowIsExcluded() =
    assertFalse(TouchInteractionClassifier.isUserInteraction("click", 1_500L, lastSelfActionAtMs = 1_000L))

  @Test fun clickExactlyAtWindowBoundaryCounts() =
    assertTrue(TouchInteractionClassifier.isUserInteraction("click", 2_000L, lastSelfActionAtMs = 1_000L))
}
