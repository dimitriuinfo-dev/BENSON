package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Test

// FIX_WA_NORMALIZE_BEFORE_MATCH_1 — classify() normalizes internally now, so captured names come
// back lowercase/diacritic-free regardless of the input's original casing/accents. Expected values
// below reflect that (a deliberate trade-off — the brain and contact-fuzzy-matching both handle
// normalized Romanian fine; see the file-level comment on WaCallVideoMatcher).
class WaCallVideoMatcherTest {
  @Test fun videoCu() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("video cu Hannah"))
  @Test fun videocallCu() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("videocall cu Hannah"))
  @Test fun faUnVideoCu() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("fă un video cu Hannah"))
  @Test fun sunOPeVideo() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("sun-o pe Hannah video"))
  @Test fun apelVideoCu() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("apel video cu Hannah"))
  @Test fun sunOPePeVideo() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("sun-o pe Hannah pe video"))
  @Test fun sunSpaceOVideo() = assertEquals("video" to "hannah", WaCallVideoMatcher.classify("sun o pe Hannah video")) // bare-space clitic, no hyphen

  // Regression guard: a plain call must stay a call — this is exactly what the new video
  // patterns must not break.
  @Test fun plainCallStaysCall() = assertEquals("call" to "hannah", WaCallVideoMatcher.classify("sună-o pe Hannah"))

  // Real device transcripts from today, unchanged behavior (broadened sun-prefix, not a rewrite).
  @Test fun realTranscriptSunao() = assertEquals("call" to "baby", WaCallVideoMatcher.classify("sunăo pe baby pe whatsapp"))
  @Test fun realTranscriptSunaLa() = assertEquals("call" to "mama", WaCallVideoMatcher.classify("sună la mama pe whatsapp"))

  // smart_format punctuation must never corrupt the captured name.
  @Test fun trailingPunctuationStripped() = assertEquals("call" to "hannah", WaCallVideoMatcher.classify("Sună-o pe Hannah."))

  @Test fun noMatchForUnrelatedText() = assertEquals(null, WaCallVideoMatcher.classify("deschide youtube"))
}
