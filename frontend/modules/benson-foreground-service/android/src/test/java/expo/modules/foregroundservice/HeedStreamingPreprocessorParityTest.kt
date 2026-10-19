package expo.modules.foregroundservice

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.FileInputStream

// PARITY TEST (Priority 1, 19.10.2026) — compares the REAL HeedStreamingPreprocessor.kt (not a
// reimplementation) against heed-wakeword's Python reference, on the SAME raw PCM, with explicit
// error values. Does NOT modify HeedWakeWord.kt or BensonForegroundService.kt. Mockito mocks only
// Context/AssetManager, just enough to load the real asset file from disk -- test-only.
//
// Fixtures (src/test/resources/parity/), generated from `.benson-training/heed-wakeword/ajutor/
// positive/ajutor_tts_000.wav`, reproducible via the script in the round report:
//   test_pcm.txt      - 16000 raw int16 samples (one per line), the LAST 16000 samples of that wav
//   py_with_norm.json - Python's own streaming reference (heed/infer.py's WakeWordDetector.step()):
//                       StreamingHighpass -> peak_normalize(-3dBFS) -> log_mel(apply_cmn=true)
//   py_without_norm.json - identical, MINUS peak_normalize (what HeedStreamingPreprocessor.kt
//                          structurally does: cascade filter -> straight to log-mel/CMN, no
//                          normalization step)
// Both flattened mel-major-then-frame ((40,101) numpy .flatten(), C-order) -- same layout as
// Kotlin's `output[m * N_FRAMES + frame]`.
class HeedStreamingPreprocessorParityTest {

  private fun resourceLines(name: String): List<String> =
    javaClass.classLoader!!.getResourceAsStream("parity/$name")!!
      .bufferedReader().readLines()

  private fun resourceJsonFloats(name: String): FloatArray {
    val text = javaClass.classLoader!!.getResourceAsStream("parity/$name")!!.bufferedReader().readText()
    val arr = JSONArray(text)
    return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
  }

  // Finds the module's own main/assets dir from the test working directory, without hardcoding an
  // absolute machine path -- Gradle runs unit tests with the module dir as the working directory.
  private fun realAssetFile(relativePath: String): File {
    val candidate = File("src/main/assets/$relativePath")
    require(candidate.isFile) { "expected asset at ${candidate.absolutePath}" }
    return candidate
  }

  private fun mockContextServingRealAssets(): Context {
    val assetManager = mock(AssetManager::class.java)
    `when`(assetManager.open("wakeword/heed_filter_coeffs.json")).thenAnswer {
      FileInputStream(realAssetFile("wakeword/heed_filter_coeffs.json"))
    }
    val context = mock(Context::class.java)
    `when`(context.assets).thenReturn(assetManager)
    return context
  }

  @Test fun kotlinOutputMatchesPythonPathWithoutPeakNormalize() {
    val pcm = resourceLines("test_pcm.txt").map { it.trim().toShort() }.toShortArray()
    assertEquals(16_000, pcm.size)

    val preprocessor = HeedStreamingPreprocessor(mockContextServingRealAssets())
    val kotlinOut = preprocessor.ingest(pcm)
    assertTrue("energy gate unexpectedly rejected the fixture clip", kotlinOut != null)

    val pyWithoutNorm = resourceJsonFloats("py_without_norm.json")
    assertEquals(pyWithoutNorm.size, kotlinOut!!.size)

    var maxDiff = 0f
    for (i in kotlinOut.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(kotlinOut[i] - pyWithoutNorm[i]))
    println("PARITY max|Kotlin - Python(no peak_normalize)| = $maxDiff")
    // Same operations on both sides (highpass+notch cascade -> log-mel -> CMN, no normalize);
    // residual is FFT/float32-vs-float64 implementation noise, not an algorithmic difference.
    assertTrue("expected near-equality (same algorithm both sides), got maxDiff=$maxDiff", maxDiff < 0.05f)
  }

  @Test fun kotlinOutputDiffersFromPythonsOwnStreamingReferenceByTheMissingPeakNormalizeAmount() {
    val pcm = resourceLines("test_pcm.txt").map { it.trim().toShort() }.toShortArray()
    val preprocessor = HeedStreamingPreprocessor(mockContextServingRealAssets())
    val kotlinOut = preprocessor.ingest(pcm)!!

    val pyWithNorm = resourceJsonFloats("py_with_norm.json")
    var maxDiff = 0f
    for (i in kotlinOut.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(kotlinOut[i] - pyWithNorm[i]))
    println("PARITY max|Kotlin - Python(WITH peak_normalize, heed/infer.py's own streaming path)| = $maxDiff")
    // CONFIRMED FINDING, not a crash/assert-fail: HeedStreamingPreprocessor.kt has no
    // peak_normalize step; heed/infer.py's WakeWordDetector.step() (the Python reference the
    // Kotlin code is supposed to mirror) calls peak_normalize(self.buffer) before log_mel on
    // every window. This test documents the real, measured gap (same clip, same measurement
    // that found 0.6857 max-diff between Python's own with/without-normalize runs) rather than
    // asserting it away. A future round can decide whether to add the missing step to
    // HeedStreamingPreprocessor.kt (NOT done here -- it already feeds the live, proven HEED path).
    assertTrue(
      "expected a real, bounded gap matching the missing peak_normalize step, got maxDiff=$maxDiff",
      maxDiff in 0.01f..2.0f,
    )
  }

  // --- Limite ale ferestrei audio (Kotlin-only, fara Python) ---

  @Test fun shortFirstChunkDoesNotCrashAndAccumulates() {
    val preprocessor = HeedStreamingPreprocessor(mockContextServingRealAssets())
    val shortChunk = ShortArray(1_600) { 0 } // 100ms of silence, below WINDOW_SAMPLES
    val result = preprocessor.ingest(shortChunk)
    // silence -> energy gate should reject it (null), not crash
    assertTrue(result == null)
  }

  @Test fun incrementalChunksEventuallyFillTheWindowWithoutCrashing() {
    val pcm = resourceLines("test_pcm.txt").map { it.trim().toShort() }.toShortArray()
    val preprocessor = HeedStreamingPreprocessor(mockContextServingRealAssets())
    var lastResult: FloatArray? = null
    var offset = 0
    val chunkSize = 1_600
    while (offset < pcm.size) {
      val end = minOf(offset + chunkSize, pcm.size)
      lastResult = preprocessor.ingest(pcm.copyOfRange(offset, end))
      offset = end
    }
    assertTrue("expected non-null output once the full window is filled", lastResult != null)
    assertEquals(40 * 101, lastResult!!.size)
  }

  @Test fun resetClearsStateBetweenClips() {
    val pcm = resourceLines("test_pcm.txt").map { it.trim().toShort() }.toShortArray()
    val preprocessor = HeedStreamingPreprocessor(mockContextServingRealAssets())
    preprocessor.ingest(pcm)
    preprocessor.reset()
    val afterReset = preprocessor.ingest(ShortArray(1_600) { 0 })
    assertTrue(afterReset == null) // silence right after reset -> gate rejects, same as a fresh instance
  }
}
