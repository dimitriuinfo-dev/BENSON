package expo.modules.foregroundservice

import java.io.Closeable
import java.nio.ByteBuffer

/** microWakeWord v2 streaming inference wrapper adapted from Home Assistant Android. */
internal class MicroWakeWordInference(
  modelBuffer: ByteBuffer,
  featureStepSizeMs: Int,
  probabilityCutoff: Float,
  slidingWindowSize: Int,
) : Closeable {
  private var nativeHandle: Long

  init {
    require(modelBuffer.isDirect) { "microWakeWord model buffer must be direct" }
    require(featureStepSizeMs > 0 && slidingWindowSize > 0)
    require(probabilityCutoff in 0f..1f)
    loadNativeLibrary()
    nativeHandle = nativeCreate(modelBuffer, 16_000, featureStepSizeMs, probabilityCutoff, slidingWindowSize)
    check(nativeHandle != 0L) { "microWakeWord model is invalid or unsupported" }
  }

  fun processAudio(samples: ShortArray): Boolean {
    check(nativeHandle != 0L) { "microWakeWord detector is closed" }
    return nativeProcessAudio(nativeHandle, samples)
  }

  fun reset() {
    check(nativeHandle != 0L) { "microWakeWord detector is closed" }
    nativeReset(nativeHandle)
  }

  override fun close() {
    if (nativeHandle != 0L) {
      nativeDestroy(nativeHandle)
      nativeHandle = 0L
    }
  }

  private external fun nativeCreate(
    modelBuffer: ByteBuffer,
    sampleRate: Int,
    featureStepSizeMs: Int,
    probabilityCutoff: Float,
    slidingWindowSize: Int,
  ): Long

  private external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean
  private external fun nativeReset(handle: Long)
  private external fun nativeDestroy(handle: Long)

  private companion object {
    @Volatile private var loaded = false
    @Synchronized private fun loadNativeLibrary() {
      if (!loaded) {
        System.loadLibrary("microwakeword")
        loaded = true
      }
    }
  }
}
