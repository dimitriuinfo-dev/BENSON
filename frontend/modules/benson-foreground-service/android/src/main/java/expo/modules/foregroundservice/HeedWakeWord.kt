package expo.modules.foregroundservice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.sqrt

/** Native foreground-service runner for Heed's ONNX model and pinned 16 kHz preprocessor. */
internal class HeedWakeWord(
  private val context: Context,
  // FIX_WAKE_SELF_ECHO_GUARD_2 (2026-10-03, device-proven) — triggeredAtMs is the acoustic trigger
  // instant (phase=armed), not whenever this callback finally fires (phase=complete, which lags by
  // however long trailing-silence collection takes — up to ~2s). A self-TTS guard checked against
  // "now" at callback time, instead of this timestamp, missed a stray detection that fired 106ms
  // after the ack's own TTS finished (guard window 1000ms) because the callback itself didn't run
  // until ~1.9s later — by then "now" was already outside the window.
  private val onDetected: (phrase: String, score: Float, wavPath: String, triggeredAtMs: Long) -> Unit,
  private val log: (stage: String, fields: String) -> Unit,
  // RUNDA_N3 TASK D [WAKE_AEC] — fired once the AudioRecord is actually recording (right after
  // recorder.startRecording(), same point HEED_MIC state=START already logs), so the caller can
  // attach an AcousticEchoCanceler to the correct, currently-live session. Default no-op so every
  // existing call site compiles unchanged.
  private val onCaptureStarted: (sessionId: Int) -> Unit = {},
) {
  companion object {
    private const val RATE = 16_000
    private const val CHUNK_SAMPLES = 1_600 // 100 ms; Heed reference streaming interval
    // FIX_WAKE_VERIFY_LATENCY_1 (2026-10-03) — non-private: BensonForegroundService.wakeVerifyTranscribe
    // trims its upload to a window around this exact offset (the trigger instant inside the WAV),
    // instead of duplicating the 1500ms constant.
    const val PRE_ROLL_SAMPLES = RATE * 1_500 / 1_000
    private const val MAX_COMMAND_SAMPLES = RATE * 8
    private const val TAIL_SILENCE_SAMPLES = RATE * 1_200 / 1_000
    private const val MODEL_ASSET = "wakeword/heed_candidate.onnx"
    private const val META_ASSET = "wakeword/heed_candidate.json"

    private fun files(context: Context): Pair<File, File> {
      val customDir = File(context.filesDir, "wakeword")
      val customModel = File(customDir, "benson.onnx")
      val customMeta = File(customDir, "benson.json")
      if (customModel.isFile && customMeta.isFile) return customModel to customMeta
      val cacheModel = File(context.cacheDir, "heed_candidate.onnx")
      if (!cacheModel.isFile || cacheModel.length() == 0L) {
        context.assets.open(MODEL_ASSET).use { input ->
          FileOutputStream(cacheModel).use { output -> input.copyTo(output) }
        }
      }
      val cacheMeta = File(context.cacheDir, "heed_candidate.json")
      if (!cacheMeta.isFile || cacheMeta.length() == 0L) {
        context.assets.open(META_ASSET).use { input ->
          FileOutputStream(cacheMeta).use { output -> input.copyTo(output) }
        }
      }
      return cacheModel to cacheMeta
    }

    fun modelPresent(context: Context): Boolean = try {
      val (model, manifest) = files(context)
      val meta = JSONObject(manifest.readText())
      val currentName = NativeCloudWake.currentWakeName(context).trim()
      model.isFile && model.length() > 0L &&
        meta.optString("phrase").equals(currentName, ignoreCase = true) &&
        meta.optInt("sample_rate") == RATE && meta.optInt("n_mels") == 40 &&
        meta.optInt("n_fft") == 512 && meta.optInt("hop_length") == 160 &&
        meta.optDouble("threshold", -1.0) in 0.0..1.0
    } catch (_: Exception) { false }

    fun phrase(context: Context): String = try {
      val (_, manifest) = files(context)
      JSONObject(manifest.readText()).optString("phrase", "Benson")
    } catch (_: Exception) { "Benson" }
  }

  @Volatile private var running = false
  @Volatile private var audioRecord: AudioRecord? = null
  private var worker: Thread? = null
  private var session: OrtSession? = null
  private var threshold = 0.5f
  private var consecutiveFrames = 2
  private var refractoryMs = 700L
  private var lastTriggerAt = 0L
  private lateinit var preprocessor: HeedStreamingPreprocessor

  fun isRunning(): Boolean = running

  fun isAudioInputActive(): Boolean =
    running && audioRecord?.let { it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING } == true

  @Synchronized fun start(): Boolean {
    if (running) return true
    if (worker?.isAlive == true || !modelPresent(context)) return false
    return try {
      val (modelFile, manifestFile) = files(context)
      val meta = JSONObject(manifestFile.readText())
      threshold = meta.getDouble("threshold").toFloat()
      consecutiveFrames = meta.optJSONObject("trigger")?.optInt("consecutive_frames", 2) ?: 2
      refractoryMs = ((meta.optJSONObject("trigger")?.optDouble("refractory_seconds", 0.7) ?: 0.7) * 1_000).toLong()
      val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
      }
      session = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
      options.close()
      preprocessor = HeedStreamingPreprocessor(context)
      preprocessor.reset()
      lastTriggerAt = 0L
      running = true
      worker = Thread({ captureLoop(meta.optString("phrase", "Benson")) }, "benson-heed-wake").also {
        it.start()
      }
      log("HEED_ARMED", "phrase=${meta.optString("phrase").replace(' ', '_')} threshold=${"%.3f".format(threshold)} modelBytes=${modelFile.length()}")
      true
    } catch (e: Exception) {
      closeSession()
      running = false
      log("HEED_ERROR", "reason=init_failed type=${e.javaClass.simpleName} message=${e.message}")
      false
    }
  }

  @Synchronized fun stop() {
    if (!running && worker == null && session == null) return
    running = false
    try { audioRecord?.stop() } catch (_: Exception) {}
    val thread = worker
    try { thread?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    if (thread?.isAlive == true) {
      log("HEED_STOP_DEFERRED", "reason=audio_thread_stopping")
      return
    }
    worker = null
    closeSession()
    log("HEED_SUSPEND", "released=true")
  }

  private fun closeSession() {
    try { session?.close() } catch (_: Exception) {}
    session = null
  }

  private fun infer(features: FloatArray): Float {
    val env = OrtEnvironment.getEnvironment()
    val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 40, 101))
    try {
      val result = session!!.run(Collections.singletonMap("mel", input))
      try {
        val value = result[0].value
        val logit = when (value) {
          is FloatArray -> value[0]
          is Array<*> -> (value[0] as Number).toFloat()
          else -> error("unexpected Heed output ${value?.javaClass?.name}")
        }
        return (1.0 / (1.0 + kotlin.math.exp(-logit.toDouble()))).toFloat()
      } finally { result.close() }
    } finally { input.close() }
  }

  private fun captureLoop(phrase: String) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBytes <= 0) { fail("invalid_min_buffer", "bytes=$minBytes"); return }
    val recorder = try {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBytes * 2, CHUNK_SAMPLES * 4),
      )
    } catch (e: Exception) { fail("audiorecord_ctor", "type=${e.javaClass.simpleName}"); return }
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      try { recorder.release() } catch (_: Exception) {}
      fail("audiorecord_uninitialized", ""); return
    }
    audioRecord = recorder
    val chunk = ShortArray(CHUNK_SAMPLES)
    val preRoll = ArrayDeque<ShortArray>()
    val capture = ArrayList<ShortArray>()
    var preRollCount = 0
    var capturedCount = 0
    var aboveCount = 0
    var detected = false
    var trailingSilence = 0
    // PROBA RMS ÎN MAȘINĂ (2026-10-04, user-directed) — doar măsurare, nicio schimbare de
    // comportament pe wake: nivelul brut al canalului de ascultare, logat o dată pe secundă, ca
    // să corelăm "Benson" nedetectat cu zgomotul real din mașină. Nu alimentează threshold/score.
    var lastLevelLogAt = 0L
    // RUNDA_N3/N5 — the trigger score, frozen at the instant of detection. `score` below keeps
    // getting reassigned every chunk for the REST of the capture (while `detected` stays true), so
    // using it at onDetected() time returned whatever the LAST chunk inferred, not what actually
    // triggered — device-confirmed this session (WAKE_SCORE logged 0.09-0.62 while the real
    // HEED_DETECTED line, seconds earlier, showed 0.83-0.97 for the same wake). This is the fix.
    var triggerScore = 0f
    var triggerAtMs = 0L
    try {
      recorder.startRecording()
      log("HEED_MIC", "state=START src=VOICE_RECOGNITION rate=$RATE chunkMs=100")
      try { onCaptureStarted(recorder.audioSessionId) } catch (_: Exception) {}
      while (running) {
        val read = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
        if (read <= 0) {
          if (read < 0) log("HEED_ERROR", "reason=audiorecord_read code=$read")
          continue
        }
        val audio = if (read == chunk.size) chunk.copyOf() else chunk.copyOfRange(0, read)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLevelLogAt >= 1000) {
          lastLevelLogAt = nowMs
          var sumSquares = 0.0
          for (s in audio) sumSquares += s.toDouble() * s.toDouble()
          val rms = kotlin.math.sqrt(sumSquares / audio.size)
          log("WAKE_MIC_LEVEL", "rms=${"%.0f".format(rms)}")
        }
        if (!detected) {
          preRoll.addLast(audio)
          preRollCount += audio.size
          while (preRollCount > PRE_ROLL_SAMPLES && preRoll.isNotEmpty()) preRollCount -= preRoll.removeFirst().size
        }
        val features = preprocessor.ingest(audio)
        var score: Float? = null
        if (features != null) {
          score = infer(features)
          if (score > threshold) aboveCount++ else aboveCount = 0
        } else aboveCount = 0
        if (!detected && score != null && aboveCount >= consecutiveFrames) {
          val now = System.currentTimeMillis()
          if (now - lastTriggerAt >= refractoryMs) {
            lastTriggerAt = now
            detected = true
            triggerScore = score
            triggerAtMs = now
            preRoll.forEach { capture.add(it) }
            capturedCount = capture.sumOf { it.size }
            log("HEED_DETECTED", "phrase=${phrase.replace(' ', '_')} score=${"%.3f".format(score)}")
            log("HEED_COMMAND_BUFFER", "phase=armed prerollSamples=$capturedCount")
          }
          aboveCount = 0
        } else if (detected) {
          capture.add(audio)
          capturedCount += audio.size
          val rms = sqrt(audio.sumOf { it.toDouble() * it.toDouble() } / maxOf(1, audio.size))
          if (rms >= 700.0) trailingSilence = 0 else trailingSilence += audio.size
          if (capturedCount >= MAX_COMMAND_SAMPLES || trailingSilence >= TAIL_SILENCE_SAMPLES) {
            val wav = writeWav(capture)
            log("HEED_COMMAND_BUFFER", "phase=complete samples=$capturedCount path=${File(wav).name}")
            running = false
            try { onDetected(phrase, triggerScore, wav, triggerAtMs) }
            catch (e: Exception) { log("HEED_ERROR", "reason=detected_callback type=${e.javaClass.simpleName}") }
            break
          }
        }
      }
    } catch (e: Exception) {
      log("HEED_ERROR", "reason=capture_loop type=${e.javaClass.simpleName} message=${e.message}")
    } finally {
      try { recorder.stop() } catch (_: Exception) {}
      try { recorder.release() } catch (_: Exception) {}
      if (audioRecord === recorder) audioRecord = null
      closeSession()
      running = false
      log("HEED_MIC", "state=STOP")
    }
  }

  private fun fail(reason: String, extra: String) {
    log("HEED_ERROR", "reason=$reason $extra")
    running = false
    closeSession()
  }

  private fun writeWav(chunks: List<ShortArray>): String {
    val pcmBytes = chunks.sumOf { it.size } * 2
    val file = File(context.cacheDir, "wake_command_${System.currentTimeMillis()}.wav")
    FileOutputStream(file).use { out ->
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + pcmBytes)
      header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16); header.putShort(1)
      header.putShort(1); header.putInt(RATE); header.putInt(RATE * 2)
      header.putShort(2); header.putShort(16); header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(pcmBytes)
      out.write(header.array())
      val bytes = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
      chunks.forEach { part -> part.forEach { bytes.putShort(it) } }
      out.write(bytes.array())
    }
    return file.absolutePath
  }
}
