package expo.modules.foregroundservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.min

/**
 * Native microWakeWord v2 runner, adapted from Home Assistant Android at commit
 * b3db6a862c78263b72c8759209d0f24da7185407. The former Kotlin prototype guessed tensor types
 * and used an unverified generic log-mel frontend; it could not execute real microWakeWord v2
 * streaming models. Inference now uses the upstream TFLite-Micro/microfrontend path and requires
 * both a valid v2 manifest and its real model asset. A missing model is unavailable, never a pass.
 *
 * Source attribution and licenses are recorded in frontend/THIRD_PARTY_NOTICES.md.
 */
internal class MicroWakeWord(
  private val context: Context,
  private val onDetected: (score: Float, wavPath: String) -> Unit,
  private val log: (stage: String, fields: String) -> Unit,
) {
  companion object {
    private val modelProfile: String get() = BuildConfig.WAKE_MODEL_PROFILE
    val MODEL_ASSET: String get() = if (modelProfile == "diagnostic_hey_jarvis") "wakeword/diagnostic_hey_jarvis.tflite" else "wakeword/benson.tflite"
    val MODEL_CONFIG_ASSET: String get() = if (modelProfile == "diagnostic_hey_jarvis") "wakeword/diagnostic_hey_jarvis.json" else "wakeword/benson.json"
    val WAKE_PHRASE: String get() = if (modelProfile == "diagnostic_hey_jarvis") "Hey Jarvis" else "Benson"
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_MS = 30
    private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
    private const val REFRACTORY_MS = 1_500L

    fun modelPresent(context: Context): Boolean = try {
      val manifest = context.assets.open(MODEL_CONFIG_ASSET).bufferedReader().use {
        JSONObject(it.readText())
      }
      require(manifest.optInt("version", 0) == 2 && manifest.optString("type") == "micro")
      val micro = manifest.getJSONObject("micro")
      require(micro.getInt("feature_step_size") > 0)
      require(micro.getDouble("probability_cutoff") in 0.0..1.0)
      require(micro.getInt("sliding_window_size") > 0)
      context.assets.open(MODEL_ASSET).use { it.read() >= 0 }
    } catch (_: Exception) { false }
  }

  @Volatile private var running = false
  private var thread: Thread? = null
  private var detector: MicroWakeWordInference? = null
  private var lastDetectAt = 0L

  fun available(): Boolean = modelPresent(context)

  @Synchronized fun start(): Boolean {
    if (running) return true
    if (thread?.isAlive == true) {
      log("NWW_START_BLOCKED", "reason=previous_capture_still_stopping")
      return false
    }
    if (!available()) {
      log("NWW_UNAVAILABLE", "reason=missing_or_invalid_v2_model model=$MODEL_ASSET manifest=$MODEL_CONFIG_ASSET")
      return false
    }
    return try {
      val config = context.assets.open(MODEL_CONFIG_ASSET).bufferedReader().use {
        JSONObject(it.readText()).getJSONObject("micro")
      }
      detector = MicroWakeWordInference(
        modelBuffer = loadModel(),
        featureStepSizeMs = config.getInt("feature_step_size"),
        probabilityCutoff = config.getDouble("probability_cutoff").toFloat(),
        slidingWindowSize = config.getInt("sliding_window_size"),
      )
      running = true
      thread = Thread({ captureLoop() }, "benson-micro-wake-word").also {
        it.priority = Process.THREAD_PRIORITY_URGENT_AUDIO
        it.start()
      }
      log("NWW_ARMED", "model=$MODEL_ASSET manifest=$MODEL_CONFIG_ASSET version=2 rate=$SAMPLE_RATE")
      true
    } catch (e: Exception) {
      closeDetector()
      running = false
      log("NWW_ERROR", "reason=init_failed type=${e.javaClass.simpleName} message=${e.message}")
      false
    }
  }

  @Synchronized fun stop() {
    if (!running && thread == null && detector == null) return
    running = false
    val worker = thread
    try { worker?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    if (worker?.isAlive == true) {
      log("NWW_STOP_DEFERRED", "reason=audio_thread_did_not_exit_within_1000ms")
      return
    }
    thread = null
    closeDetector()
    log("NWW_SUSPEND", "released=true")
  }

  fun isRunning(): Boolean = running

  private fun closeDetector() {
    try { detector?.close() } catch (_: Exception) {}
    detector = null
  }

  private fun loadModel(): MappedByteBuffer {
    val descriptor = context.assets.openFd(MODEL_ASSET)
    FileInputStream(descriptor.fileDescriptor).use { stream ->
      return stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
    }
  }

  private fun captureLoop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val minBytes = AudioRecord.getMinBufferSize(
      SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBytes <= 0) {
      log("NWW_ERROR", "reason=invalid_min_buffer_size bytes=$minBytes")
      running = false
      closeDetector()
      return
    }
    val record = try {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBytes * 2, CHUNK_SAMPLES * 4),
      )
    } catch (e: Exception) {
      log("NWW_ERROR", "reason=audiorecord_ctor type=${e.javaClass.simpleName}")
      running = false
      closeDetector()
      return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      log("NWW_ERROR", "reason=audiorecord_uninitialized")
      try { record.release() } catch (_: Exception) {}
      running = false
      closeDetector()
      return
    }

    val samples = ShortArray(CHUNK_SAMPLES)
    val preRoll = ArrayDeque<ShortArray>()
    val preRollSamples = SAMPLE_RATE * 1500 / 1000
    val capture = ArrayList<ShortArray>()
    var detected = false
    var speechSeenAfterWake = false
    var trailingSilenceSamples = 0
    var capturedSamples = 0
    val maxCommandSamples = SAMPLE_RATE * 8
    try {
      record.startRecording()
      log("NWW_MIC", "state=START src=VOICE_RECOGNITION rate=$SAMPLE_RATE")
      while (running) {
        val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        if (count <= 0) {
          if (count < 0) log("NWW_ERROR", "reason=audiorecord_read code=$count")
          continue
        }
        val audioChunk = if (count == samples.size) samples.copyOf() else samples.copyOf(min(count, samples.size))
        if (!detected) {
          preRoll.addLast(audioChunk)
          while (preRoll.sumOf { it.size } > preRollSamples) preRoll.removeFirst()
        }
        val wakeHit = !detected && detector?.processAudio(audioChunk) == true
        if (wakeHit) {
          val now = System.currentTimeMillis()
          if (now - lastDetectAt >= REFRACTORY_MS) {
            lastDetectAt = now
            detected = true
            preRoll.forEach { capture.add(it) }
            capturedSamples = capture.sumOf { it.size }
            log("NWW_DETECTED", "keyword=${WAKE_PHRASE.replace(' ', '_')} engine=microWakeWord_v2 profile=$modelProfile")
            log("NWW_COMMAND_BUFFER", "phase=armed prerollSamples=$capturedSamples")
          }
        } else if (detected) {
          capture.add(audioChunk)
          capturedSamples += audioChunk.size
          val rms = sqrt(audioChunk.sumOf { it.toDouble() * it.toDouble() } / maxOf(1, audioChunk.size))
          if (rms >= 700.0) { speechSeenAfterWake = true; trailingSilenceSamples = 0 }
          else trailingSilenceSamples += audioChunk.size
          if (capturedSamples >= maxCommandSamples || trailingSilenceSamples >= SAMPLE_RATE * 1200 / 1000) {
            val path = writeWav(capture)
            log("NWW_COMMAND_BUFFER", "phase=complete samples=$capturedSamples speech=$speechSeenAfterWake path=${File(path).name}")
            try { onDetected(1f, path) } catch (e: Exception) {
              log("NWW_ERROR", "reason=detected_callback type=${e.javaClass.simpleName}")
            }
            running = false
          }
        }
      }
    } catch (e: Exception) {
      log("NWW_ERROR", "reason=capture_loop type=${e.javaClass.simpleName} message=${e.message}")
    } finally {
      try { record.stop() } catch (_: Exception) {}
      try { record.release() } catch (_: Exception) {}
      closeDetector()
      log("NWW_MIC", "state=STOP")
      running = false
    }
  }

  private fun writeWav(chunks: List<ShortArray>): String {
    val pcmBytes = chunks.sumOf { it.size } * 2
    val file = File(context.cacheDir, "wake_command_${System.currentTimeMillis()}.wav")
    FileOutputStream(file).use { out ->
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + pcmBytes)
      header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16); header.putShort(1)
      header.putShort(1); header.putInt(SAMPLE_RATE); header.putInt(SAMPLE_RATE * 2)
      header.putShort(2); header.putShort(16); header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(pcmBytes)
      out.write(header.array())
      val bytes = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
      chunks.forEach { chunk -> chunk.forEach { bytes.putShort(it) } }
      out.write(bytes.array())
    }
    return file.absolutePath
  }
}
