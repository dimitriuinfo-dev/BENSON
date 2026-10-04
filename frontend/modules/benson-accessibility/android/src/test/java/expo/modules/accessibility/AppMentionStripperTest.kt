package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMentionStripperTest {
  @Test fun stripsTrailingPeSpotify() = assertEquals("madonna", AppMentionStripper.strip("madonna pe spotify"))
  @Test fun stripsTrailingInYoutube() = assertEquals("george michael", AppMentionStripper.strip("george michael in youtube"))
  @Test fun stripsTrailingPeYoutubeMusic() = assertEquals("x", AppMentionStripper.strip("x pe youtube music"))
  @Test fun stripsLeadingInYoutube() = assertEquals("george michael", AppMentionStripper.strip("in youtube george michael"))
  @Test fun stripsLeadingPeSpotify() = assertEquals("madonna", AppMentionStripper.strip("pe spotify madonna"))
  @Test fun leavesPlainQueryUnchanged() = assertEquals("george michael", AppMentionStripper.strip("george michael"))
  @Test fun doesNotOverStripYoutubeMusicToYoutube() = assertEquals("x", AppMentionStripper.strip("x pe youtube music"))
  @Test fun caseInsensitive() = assertEquals("madonna", AppMentionStripper.strip("madonna PE Spotify"))
  @Test fun trimsExtraWhitespace() = assertEquals("madonna", AppMentionStripper.strip("  madonna   pe   spotify  "))
}
