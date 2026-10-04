package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultNoiseFilterTest {
  // A real result title is almost never byte-identical to the typed query (capitalization,
  // artist suffix, etc.) — ported as-is from the proven TS rule, which specifically treats an
  // EXACT query match as the transient autocomplete-echo row, not a real result.
  @Test fun realTitleIsNotNoise() = assertTrue(!SearchResultNoiseFilter.isNoise("Justify My Love - Madonna", "justify my love"))
  @Test fun exactQueryEchoIsNoise() = assertTrue(SearchResultNoiseFilter.isNoise("justify my love", "justify my love"))
  @Test fun exactLabelPlaylistIsNoise() = assertTrue(SearchResultNoiseFilter.isNoise("Playlist", "madonna"))
  @Test fun chromeWordIsNoise() = assertTrue(SearchResultNoiseFilter.isNoise("Search", "madonna"))
  @Test fun lowercaseEchoIsNoise() = assertTrue(SearchResultNoiseFilter.isNoise("inna hot", "inna"))
  @Test fun realTitleWithUppercaseIsNotEcho() = assertTrue(!SearchResultNoiseFilter.isNoise("INNA Radio", "inna"))

  @Test fun collapsesDuplicatedHalf() = assertEquals("Best of INNA", SearchResultNoiseFilter.collapseDuplicatedHalf("Best of INNA Best of INNA"))
  @Test fun leavesPlainLabelAlone() = assertEquals("Justify My Love", SearchResultNoiseFilter.collapseDuplicatedHalf("Justify My Love"))

  @Test fun firstCandidateSkipsNoiseAndPicksRealTitle() {
    val labels = listOf("Search", "Playlist", "Best of Madonna", "Justify My Love")
    assertEquals("Best of Madonna", SearchResultNoiseFilter.firstCandidate(labels, "madonna"))
  }

  // Device-proven (2026-10-04): Spotify's overflow button contentDescription contains the song
  // title as a substring ("Mehr Optionen für den Song „Justify My Love""), same collision class
  // as the Add/Save button the proven TS code already guards against.
  @Test fun overflowButtonDescriptionIsNoise() =
    assertTrue(SearchResultNoiseFilter.isNoise("Mehr Optionen für den Song „Justify My Love\"", "justify my love"))

  @Test fun firstCandidateSkipsOverflowButton() {
    val labels = listOf("Mehr Optionen für den Song „Justify My Love\"", "Justify My Love - Madonna")
    assertEquals("Justify My Love - Madonna", SearchResultNoiseFilter.firstCandidate(labels, "justify my love"))
  }

  @Test fun firstCandidateNullWhenAllNoise() =
    assertNull(SearchResultNoiseFilter.firstCandidate(listOf("Search", "Playlist"), "madonna"))
}
