package expo.modules.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetadataMatcherTest {
  @Test fun exactTitleMatches() =
    assertTrue(TrackMetadataMatcher.matches("Justify My Love", "Madonna", "Justify My Love", "justify my love"))

  // The device-proven case: a playlist's displayed title differs from the track that actually
  // starts playing — the query/artist token is what ties them together, not the exact title.
  @Test fun playlistFirstTrackMatchesViaArtistToken() =
    assertTrue(TrackMetadataMatcher.matches("Body and the Sun", "Inna", "Best of INNA", "inna"))

  @Test fun unrelatedSongDoesNotMatch() =
    assertFalse(TrackMetadataMatcher.matches("Bohemian Rhapsody", "Queen", "Justify My Love", "justify my love"))

  @Test fun diacriticsIgnored() =
    assertTrue(TrackMetadataMatcher.matches("Dragostea Din Tei", "O-Zone", "dragostea din tei", "dragostea din tei"))

  @Test fun emptySessionMetadataNeverMatches() =
    assertFalse(TrackMetadataMatcher.matches(null, null, "Justify My Love", "justify my love"))

  @Test fun shortTokensIgnored() =
    // "my"/"la" etc. (< 3 chars) must not cause a false positive on their own.
    assertFalse(TrackMetadataMatcher.matches("La La Land", "Soundtrack", "My Way", "my"))

  // ADEVĂR ÎN AMBELE SENSURI (2026-10-04, user-directed) — BensonForegroundService.tryMusicPlay
  // no longer gates this check behind genericSearchAndPlay's own boolean: a failed intermediate
  // step ("no_input_found", "play_control ok=false") must not suppress a real, matching result.
  // This is the ONLY function the final verdict depends on — it takes no "did the click succeed"
  // input at all, so a track that genuinely matches is reported true regardless of what any
  // intermediate screen-automation step returned.
  @Test fun matchesRegardlessOfHowPlaybackStarted() =
    assertTrue(TrackMetadataMatcher.matches("Justify My Love - The Beast Within Mix", "Madonna", "justify my love", "justify my love"))
}
