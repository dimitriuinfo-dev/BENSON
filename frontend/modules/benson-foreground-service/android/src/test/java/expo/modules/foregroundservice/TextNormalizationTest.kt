package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizationTest {
  @Test fun stripsLeadingSurrogatePairEmoji() = assertEquals("baby", TextNormalization.stripSymbolsAndEmoji("👶baby"))
  @Test fun stripsTrailingHeartSymbol() = assertEquals("baby ", TextNormalization.stripSymbolsAndEmoji("baby ❤"))
  @Test fun leavesPlainTextUntouched() = assertEquals("hannah", TextNormalization.stripSymbolsAndEmoji("hannah"))
}
