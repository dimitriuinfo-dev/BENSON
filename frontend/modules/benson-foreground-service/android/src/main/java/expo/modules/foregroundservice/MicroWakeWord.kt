package expo.modules.foregroundservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native on-device wake-word probe.
 *
 * Fully native: a dedicated AudioRecord thread inside BensonForegroundService feeds 16 kHz mono
 * PCM16 into a microWakeWord-style TFLite model. React Native is NOT involved in detection — the
 * whole reason for this round (JS runtime suspends when the Activity backgrounds).
 *
 * Model asset: assets/wakeword/benson.tflite  (NOT committed — generated with the audited
 * microWakeWord toolchain, placed like whisper-models/). Absent → available()=false, caller keeps
 * the JS wake fallback. Nothing pretends the native engine is running.
 *
 * The model's input tensor is inspected at load and the pipeline adapts:
 *   - float/int window of raw samples  → frontend is inside the graph, feed raw audio
 *   - [.., 40] feature frames          → we run a 40-bin log-mel frontend (30 ms window, 10 ms hop)
 * The log-mel frontend here is a STANDARD implementation and MUST be validated against the exact
 * training config of whatever benson.tflite is produced (see NWW_MODEL_SIGNATURE log + the probe
 * hand-off). Threshold + refractory are tunable constants.
 */
class MicroWakeWord(
  private val context: Context,
  private val onDetected: (score: Float) -> Unit,
  private val log: (stage: String, fields: String) -> Unit,
) {
  companion object {
    const val MODEL_ASSET = "wakeword/benson.tflite"
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 30
    private const val HOP_MS = 10
    private const val MEL_BINS = 40
    private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000   // 480
    private const val HOP_SAMPLES = SAMPLE_RATE * HOP_MS / 1000       // 160
    // Detection threshold on the model's probability output, and a refractory window so one
    // utterance fires once. Tune on device against the real model.
    private const val DETECT_THRESHOLD = 0.7f
    private const val REFRACTORY_MS = 1_500L
    // How many recent feature frames to hold for a non-streaming (windowed) model.
    private const val FEATURE_WINDOW = 100

    fun modelPresent(context: Context): Boolean = try {
      context.assets.openFd(MODEL_ASSET).use { true }
    } catch (_: Exception) {
      try { context.assets.open(MODEL_ASSET).use { true } } catch (_: Exception) { false }
    }
  }

  @Volatile private var running = false
  private var thread: Thread? = null
  private var interpreter: Interpreter? = null
  private var lastDetectAt = 0L

  // model I/O described at load
  private var inWantsRawAudio = false
  private var inShape: IntArray = IntArray(0)
  private var inIsFloat = true
  private var outShape: IntArray = IntArray(0)

  fun available(): Boolean = modelPresent(context)

  /** Start the AudioRecord + inference loop. Idempotent. */
  fun start(): Boolean {
    if (running) return true
    if (!available()) { log("NWW_UNAVAILABLE", "reason=missing_model asset=$MODEL_ASSET"); return false }
    return try {
      interpreter = Interpreter(loadModel(), Interpreter.Options().apply { numThreads = 1 })
      describeIo()
      running = true
      thread = Thread({ loop() }, "benson-mww").also { it.priority = Thread.NORM_PRIORITY; it.start() }
      log("NWW_ARMED", "asset=$MODEL_ASSET rawAudioInput=$inWantsRawAudio inShape=${inShape.joinToString("x")}")
      true
    } catch (e: Exception) {
      log("NWW_ERROR", "reason=init_failed error=\"${e.javaClass.simpleName}: ${e.message}\"")
      safeCloseInterpreter()
      running = false
      false
    }
  }

  /** Stop the loop, release AudioRecord + interpreter. Idempotent. Blocks briefly for a clean stop. */
  fun stop() {
    if (!running && thread == null && interpreter == null) return
    running = false
    try { thread?.join(400) } catch (_: Exception) {}
    thread = null
    safeCloseInterpreter()
    log("NWW_SUSPEND", "released=true")
  }

  fun isRunning(): Boolean = running

  private fun safeCloseInterpreter() {
    try { interpreter?.close() } catch (_: Exception) {}
    interpreter = null
  }

  private fun loadModel(): MappedByteBuffer {
    val afd = context.assets.openFd(MODEL_ASSET)
    FileInputStream(afd.fileDescriptor).use { fis ->
      return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }
  }

  private fun describeIo() {
    val itp = interpreter ?: return
    val i0 = itp.getInputTensor(0)
    inShape = i0.shape()
    inIsFloat = i0.dataType().name.contains("FLOAT", ignoreCase = true)
    // Heuristic: an input whose last dim == MEL_BINS is a feature-frame model; anything else with
    // a large last dim is raw audio (frontend baked into the graph).
    val lastDim = if (inShape.isNotEmpty()) inShape[inShape.size - 1] else 0
    inWantsRawAudio = lastDim != MEL_BINS
    outShape = itp.getOutputTensor(0).shape()
    log("NWW_MODEL_SIGNATURE",
      "in=${inShape.joinToString("x")} inType=${if (inIsFloat) "float" else "int"} " +
        "out=${outShape.joinToString("x")} interpretedAs=${if (inWantsRawAudio) "raw_audio" else "logmel_${MEL_BINS}"}")
  }

  // ── audio + inference loop ───────────────────────────────────────────────────────────────────
  private fun loop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val bufBytes = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
    val record = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
    } catch (e: Exception) {
      log("NWW_ERROR", "reason=audiorecord_ctor error=\"${e.message}\""); running = false; return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      log("NWW_ERROR", "reason=audiorecord_uninitialized"); try { record.release() } catch (_: Exception) {}; running = false; return
    }

    val hann = FloatArray(FRAME_SAMPLES) { 0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * it / (FRAME_SAMPLES - 1)).toFloat() }
    val melFb = buildMelFilterbank(FRAME_SAMPLES, SAMPLE_RATE, MEL_BINS)
    val featureRing = ArrayDeque<FloatArray>()
    val pcm = ShortArray(HOP_SAMPLES)
    val window = ShortArray(FRAME_SAMPLES)
    var haveSamples = 0

    try {
      record.startRecording()
      log("NWW_MIC", "state=START src=VOICE_RECOGNITION rate=$SAMPLE_RATE")
      while (running) {
        val n = record.read(pcm, 0, HOP_SAMPLES)
        if (n <= 0) { Thread.sleep(5); continue }
        // slide the window by HOP_SAMPLES
        System.arraycopy(window, HOP_SAMPLES, window, 0, FRAME_SAMPLES - HOP_SAMPLES)
        System.arraycopy(pcm, 0, window, FRAME_SAMPLES - HOP_SAMPLES, n)
        haveSamples = min(FRAME_SAMPLES, haveSamples + n)
        if (haveSamples < FRAME_SAMPLES) continue

        val score: Float = if (inWantsRawAudio) {
          inferRaw(window)
        } else {
          val feat = logMel(window, hann, melFb)
          featureRing.addLast(feat)
          while (featureRing.size > FEATURE_WINDOW) featureRing.removeFirst()
          inferFeatures(featureRing)
        }
        if (score >= DETECT_THRESHOLD) {
          val now = System.currentTimeMillis()
          if (now - lastDetectAt >= REFRACTORY_MS) {
            lastDetectAt = now
            log("NWW_DETECTED", "keyword=Benson score=${"%.3f".format(score)}")
            try { onDetected(score) } catch (_: Exception) {}
          }
        }
      }
    } catch (e: Exception) {
      log("NWW_ERROR", "reason=loop error=\"${e.javaClass.simpleName}: ${e.message}\"")
    } finally {
      try { record.stop() } catch (_: Exception) {}
      try { record.release() } catch (_: Exception) {}
      log("NWW_MIC", "state=STOP")
    }
  }

  private fun inferRaw(window: ShortArray): Float {
    val itp = interpreter ?: return 0f
    return try {
      val need = if (inShape.isNotEmpty()) inShape.reduce { a, b -> a * b } else FRAME_SAMPLES
      val take = min(need, window.size)
      val out = allocOutput()
      if (inIsFloat) {
        val inBuf = ByteBuffer.allocateDirect(need * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until need) inBuf.putFloat(if (i < take) window[i] / 32768f else 0f)
        inBuf.rewind(); itp.run(inBuf, out)
      } else {
        val inBuf = ByteBuffer.allocateDirect(need * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until need) inBuf.putShort(if (i < take) window[i] else 0)
        inBuf.rewind(); itp.run(inBuf, out)
      }
      readScore(out)
    } catch (e: Exception) { log("NWW_ERROR", "reason=infer_raw error=\"${e.message}\""); 0f }
  }

  private fun inferFeatures(ring: ArrayDeque<FloatArray>): Float {
    val itp = interpreter ?: return 0f
    return try {
      // Streaming model: [1,1,40]  |  windowed model: [1,T,40]
      val t = if (inShape.size >= 2) inShape[inShape.size - 2] else 1
      val frames = if (t <= 1) listOf(ring.lastOrNull() ?: FloatArray(MEL_BINS))
      else {
        val list = ArrayList<FloatArray>(t)
        val start = maxOf(0, ring.size - t)
        for (i in 0 until t) list.add(ring.elementAtOrNull(start + i) ?: FloatArray(MEL_BINS))
        list
      }
      val inBuf = ByteBuffer.allocateDirect(frames.size * MEL_BINS * 4).order(ByteOrder.nativeOrder())
      for (f in frames) for (v in f) inBuf.putFloat(v)
      inBuf.rewind()
      val out = allocOutput()
      itp.run(inBuf, out)
      readScore(out)
    } catch (e: Exception) { log("NWW_ERROR", "reason=infer_feat error=\"${e.message}\""); 0f }
  }

  private fun allocOutput(): ByteBuffer {
    val n = if (outShape.isNotEmpty()) outShape.reduce { a, b -> a * b } else 1
    return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
  }
  private fun readScore(out: ByteBuffer): Float {
    out.rewind()
    var maxv = Float.NEGATIVE_INFINITY
    while (out.remaining() >= 4) maxv = maxOf(maxv, out.float)
    return if (maxv.isFinite()) maxv else 0f
  }

  // ── standard 40-bin log-mel — MUST be validated against the real model's training config ─────
  private fun logMel(window: ShortArray, hann: FloatArray, melFb: Array<FloatArray>): FloatArray {
    val nfft = nextPow2(FRAME_SAMPLES)
    val re = FloatArray(nfft)
    val im = FloatArray(nfft)
    for (i in 0 until FRAME_SAMPLES) re[i] = (window[i] / 32768f) * hann[i]
    fft(re, im)
    val half = nfft / 2 + 1
    val power = FloatArray(half) { re[it] * re[it] + im[it] * im[it] }
    val out = FloatArray(MEL_BINS)
    for (m in 0 until MEL_BINS) {
      var s = 0f
      val fb = melFb[m]
      for (k in 0 until half) s += fb[k] * power[k]
      out[m] = ln((s + 1e-6f))
    }
    return out
  }
  private fun nextPow2(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }
  private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
      j = j or bit
      if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
    }
    var len = 2
    while (len <= n) {
      val ang = -2.0 * Math.PI / len
      val wr = kotlin.math.cos(ang).toFloat(); val wi = kotlin.math.sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var cr = 1f; var ci = 0f
        for (k in 0 until len / 2) {
          val ur = re[i + k]; val ui = im[i + k]
          val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
          val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
          re[i + k] = ur + vr; im[i + k] = ui + vi
          re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
          val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
        }
        i += len
      }
      len = len shl 1
    }
  }
  private fun buildMelFilterbank(frameSamples: Int, sampleRate: Int, bins: Int): Array<FloatArray> {
    val nfft = nextPow2(frameSamples)
    val half = nfft / 2 + 1
    val fMin = 0.0; val fMax = sampleRate / 2.0
    fun hz2mel(f: Double) = 2595.0 * kotlin.math.log10(1.0 + f / 700.0)
    fun mel2hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
    val mMin = hz2mel(fMin); val mMax = hz2mel(fMax)
    val pts = DoubleArray(bins + 2) { mel2hz(mMin + (mMax - mMin) * it / (bins + 1)) }
    val binHz = DoubleArray(half) { it * sampleRate.toDouble() / nfft }
    return Array(bins) { m ->
      FloatArray(half) { k ->
        val hz = binHz[k]
        val lo = pts[m]; val ce = pts[m + 1]; val hi = pts[m + 2]
        when {
          hz in lo..ce && ce > lo -> ((hz - lo) / (ce - lo)).toFloat()
          hz in ce..hi && hi > ce -> ((hi - hz) / (hi - ce)).toFloat()
          else -> 0f
        }
      }
    }
  }
}
