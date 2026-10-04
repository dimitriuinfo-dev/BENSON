package expo.modules.audiocapture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// NATIVE_CMD_1 (2026-10-02) — extracted verbatim from BensonAudioCaptureModule's runCapture/
// finish/shortsToBytes/writeWav/buildWavHeader (same constants, same tuning, same VAD/pre-roll/
// WAV logic — see that file's history for the device-measured rationale behind every threshold
// below, unchanged here). Decoupled from Module/appContext/sendEvent so a plain Android component
// (BensonForegroundService, which is not an Expo Module and has no ModuleRegistry access) can
// drive the exact same proven capture, not a reimplementation. BensonAudioCaptureModule.kt now
// delegates to this object; its own observable behavior (events, log lines) is unchanged.
// Revert: inline this back into BensonAudioCaptureModule.kt and delete this file.
object CaptureEngine {
  private const val TAG = "BensonAudioCapture"
  private const val SAMPLE_RATE = 16000
  private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
  private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

  private const val RMS_THRESHOLD = 700.0
  private const val MIN_SPEECH_MS = 800L
  private const val SILENCE_TIMEOUT_MS = 1600L
  private const val PRE_SPEECH_TIMEOUT_MS = 60_000L
  private const val MAX_DURATION_MS = 15000L
  private const val EARLY_NO_SPEECH_STOP_MS = 1500L
  private const val READ_CHUNK_MS = 50L
  private const val PRE_ROLL_CHUNKS = 11

  const val PREFS_NAME = "benson_watchdog_prefs"
  const val DIAGNOSTIC_WAV_NAME = "benson_diagnostic_once.wav"

  private fun isNoiseSuppressorEnabled(context: Context): Boolean = try {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("noise_suppressor_enabled", false)
  } catch (_: Exception) { false }

  @Suppress("DEPRECATION")
  private fun requestAudioFocus(context: Context): AudioFocusRequest? {
    return try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=requested")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(attrs)
          .setWillPauseWhenDucked(false)
          .setOnAudioFocusChangeListener { }
          .build()
        val result = am.requestAudioFocus(req)
        Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "denied"}")
        req
      } else {
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=granted")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "requestAudioFocus failed", e)
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=denied")
      null
    }
  }

  @Suppress("DEPRECATION")
  private fun abandonAudioFocus(context: Context, focusRequest: AudioFocusRequest?) {
    try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
      } else {
        am.abandonAudioFocus(null)
      }
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=released")
    } catch (e: Exception) {
      Log.e(TAG, "abandonAudioFocus failed", e)
    }
  }

  /**
   * Runs one VAD-gated capture to completion on the CALLING thread (caller is responsible for
   * running this off the main thread). Same phases/thresholds as the original: pre_speech ->
   * in_speech -> done. onVolume is throttled to ~10/sec; onEnd fires exactly once, with a WAV file
   * path (or null if no speech was captured), a reason (vad_silence|max_duration|no_speech|
   * stopped|error|call_audio_active), how long it waited before speech started (or total elapsed
   * if it never did), and how long the captured speech itself lasted.
   *
   * RUNDA_N5 (2026-10-02) — preSpeechTimeoutMs/silenceTimeoutMs/maxDurationMs added as optional
   * parameters, defaulting to the EXACT prior hardcoded constants (EARLY_NO_SPEECH_STOP_MS/
   * SILENCE_TIMEOUT_MS/MAX_DURATION_MS) — the existing JS-driven caller (BensonAudioCaptureModule)
   * passes nothing and is byte-for-byte unaffected. BensonForegroundService's post-ack follow-up
   * capture passes its own, longer values (a person needs real time to start talking after hearing
   * "Da, Master", unlike JS's conversation-mode capture which is already mid-exchange).
   */
  fun capture(
    context: Context,
    onVolume: (Double) -> Unit,
    onEnd: (filePath: String?, reason: String, waitedMs: Long, speechMs: Long) -> Unit,
    isStopRequested: () -> Boolean,
    preSpeechTimeoutMs: Long = EARLY_NO_SPEECH_STOP_MS,
    silenceTimeoutMs: Long = SILENCE_TIMEOUT_MS,
    maxDurationMs: Long = MAX_DURATION_MS,
  ) {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (minBufferSize <= 0) {
      Log.e(TAG, "getMinBufferSize failed: $minBufferSize")
      onEnd(null, "error", 0L, 0L)
      return
    }
    val bufferSize = minBufferSize * 2
    val chunkSamples = (SAMPLE_RATE * READ_CHUNK_MS / 1000).toInt()
    val readBuffer = ShortArray(chunkSamples)

    val recorder = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
    } catch (e: Exception) {
      Log.e(TAG, "AudioRecord construction failed", e)
      onEnd(null, "error", 0L, 0L)
      return
    }
    Log.i("BENSON_AUDIO", "CAPTURE_MIC_STATE state=${recorder.state} ts=${System.currentTimeMillis()}")
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord not initialized, state=${recorder.state}")
      recorder.release()
      onEnd(null, "error", 0L, 0L)
      return
    }

    var aec: AcousticEchoCanceler? = null
    var ns: NoiseSuppressor? = null
    try {
      if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
      if (isNoiseSuppressorEnabled(context) && NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
    } catch (e: Exception) {
      Log.e(TAG, "AEC/NS setup failed (continuing without it)", e)
    }
    Log.i("BENSON_AUDIO", "EFFECTS source=voice_recognition sessionId=${recorder.audioSessionId} aec=${if (aec != null) "on" else "off"} ns=${if (ns != null) "on" else "off"}")

    val pcm = java.io.ByteArrayOutputStream()
    var phase = "pre_speech"
    var speechStartedAt = 0L
    var lastLoudAt = 0L
    var peakRms = 0.0
    var lastVolumeEmitAt = 0L
    val captureStartedAt = System.currentTimeMillis()
    val preRollBuffer = ArrayDeque<ByteArray>()
    val focusRequest = requestAudioFocus(context)

    fun finish(filePath: String?, reason: String, pcmBytes: Int) {
      abandonAudioFocus(context, focusRequest)
      try { aec?.release() } catch (_: Exception) {}
      try { ns?.release() } catch (_: Exception) {}
      try { recorder.stop() } catch (_: Exception) {}
      try { recorder.release() } catch (_: Exception) {}
      Log.i("BENSON_AUDIO", "CAPTURE_MIC state=RELEASE ts=${System.currentTimeMillis()}")
      val durationSec = pcmBytes / 2.0 / SAMPLE_RATE
      Log.i("BENSON_AUDIO", "CAPTURE_ENDED ts=${System.currentTimeMillis()} bytes=$pcmBytes durationSec=$durationSec reason=$reason peakRms=$peakRms threshold=$RMS_THRESHOLD")
      onVolume(0.0)
      val finishedAt = System.currentTimeMillis()
      val waitedMs = if (speechStartedAt > 0) speechStartedAt - captureStartedAt else finishedAt - captureStartedAt
      val speechMs = if (speechStartedAt > 0) finishedAt - speechStartedAt else 0L
      onEnd(filePath, reason, waitedMs, speechMs)
    }

    try {
      recorder.startRecording()
      Log.i("BENSON_AUDIO", "CAPTURE_MIC state=ACQUIRE ts=${System.currentTimeMillis()}")
      Log.i(TAG, "Capture started")

      while (!isStopRequested()) {
        if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("call_audio_active", false)) {
          Log.i("BENSON_AUDIO", "CAPTURE_CANCELLED reason=call_audio_active")
          finish(null, "call_audio_active", 0)
          return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - captureStartedAt

        if (phase == "pre_speech" && elapsed > PRE_SPEECH_TIMEOUT_MS) { finish(null, "no_speech", 0); return }
        if (phase == "pre_speech" && elapsed > preSpeechTimeoutMs) { finish(null, "no_speech", 0); return }
        if (elapsed > maxDurationMs) { finish(writeWav(context, pcm.toByteArray()), "max_duration", pcm.size()); return }
        if (phase == "in_speech" && (now - lastLoudAt) > silenceTimeoutMs && (now - speechStartedAt) > MIN_SPEECH_MS) {
          finish(writeWav(context, pcm.toByteArray()), "vad_silence", pcm.size()); return
        }

        val read = recorder.read(readBuffer, 0, chunkSamples)
        if (read <= 0) continue

        var sumSquares = 0.0
        for (i in 0 until read) { val s = readBuffer[i].toDouble(); sumSquares += s * s }
        val rms = sqrt(sumSquares / read)
        if (rms > peakRms) peakRms = rms

        if (now - lastVolumeEmitAt >= 100L) {
          lastVolumeEmitAt = now
          onVolume((rms / 6000.0).coerceIn(0.0, 1.0))
        }

        val bytes = shortsToBytes(readBuffer, read)
        if (phase == "in_speech") {
          pcm.write(bytes)
        } else {
          preRollBuffer.addLast(bytes)
          if (preRollBuffer.size > PRE_ROLL_CHUNKS) preRollBuffer.removeFirst()
        }

        if (rms > RMS_THRESHOLD) {
          lastLoudAt = now
          if (phase == "pre_speech") {
            phase = "in_speech"
            speechStartedAt = now
            var prependedBytes = 0
            for (chunk in preRollBuffer) { pcm.write(chunk); prependedBytes += chunk.size }
            preRollBuffer.clear()
            val prependedMs = (prependedBytes / 2.0 / SAMPLE_RATE * 1000).toInt()
            Log.i("BENSON_AUDIO", "PREROLL prepended_bytes=$prependedBytes prepended_ms=$prependedMs")
          }
        }
      }

      if (phase == "in_speech" && pcm.size() > 0) finish(writeWav(context, pcm.toByteArray()), "stopped", pcm.size())
      else finish(null, "stopped", 0)
    } catch (e: Exception) {
      Log.e(TAG, "Capture loop error", e)
      finish(null, "error", 0)
    }
  }

  private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
    val bytes = ByteArray(count * 2)
    for (i in 0 until count) {
      val s = shorts[i].toInt()
      bytes[i * 2] = (s and 0xFF).toByte()
      bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return bytes
  }

  private fun writeWav(context: Context, pcmData: ByteArray): String? {
    if (pcmData.isEmpty()) return null
    return try {
      val file = File(context.cacheDir, "benson_capture_${System.currentTimeMillis()}.wav")
      FileOutputStream(file).use { out ->
        out.write(buildWavHeader(pcmData.size))
        out.write(pcmData)
      }
      file.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "writeWav failed", e)
      null
    }
  }

  private fun buildWavHeader(dataSize: Int): ByteArray {
    val byteRate = SAMPLE_RATE * 2
    val header = ByteArray(44)
    fun writeStr(offset: Int, s: String) { s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() } }
    fun writeInt(offset: Int, v: Int) {
      header[offset] = (v and 0xFF).toByte(); header[offset + 1] = ((v shr 8) and 0xFF).toByte()
      header[offset + 2] = ((v shr 16) and 0xFF).toByte(); header[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun writeShort(offset: Int, v: Int) {
      header[offset] = (v and 0xFF).toByte(); header[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }
    writeStr(0, "RIFF"); writeInt(4, 36 + dataSize); writeStr(8, "WAVE")
    writeStr(12, "fmt "); writeInt(16, 16); writeShort(20, 1); writeShort(22, 1)
    writeInt(24, SAMPLE_RATE); writeInt(28, byteRate); writeShort(32, 2); writeShort(34, 16)
    writeStr(36, "data"); writeInt(40, dataSize)
    return header
  }
}
