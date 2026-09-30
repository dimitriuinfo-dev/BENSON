package expo.modules.foregroundservice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Kotlin port of Heed's pinned streaming_preprocessor.js, using its generated coefficients. */
internal class HeedStreamingPreprocessor(context: Context) {
  companion object {
    private const val N_FFT = 512
    private const val HOP = 160
    private const val WINDOW_SAMPLES = 16_000
    private const val N_FRAMES = 101
    private const val N_MELS = 40
    private const val N_BINS = 257
    private const val LOG_FLOOR = 1e-9
  }

  private val coeffs = context.assets.open("wakeword/heed_filter_coeffs.json").bufferedReader().use {
    JSONObject(it.readText())
  }
  private val hann = coeffs.getJSONArray("HANN_WINDOW").toDoubleArray()
  private val mel = Array(N_MELS) { m ->
    val row = coeffs.getJSONArray("MEL_FB_SPARSE").getJSONArray(m)
    Array(row.length()) { j ->
      val pair = row.getJSONArray(j)
      pair.getInt(0) to pair.getDouble(1)
    }
  }
  private val cascade = Cascade(coeffs.getJSONObject("FILTERS"))
  private val filtered = FloatArray(WINDOW_SAMPLES)
  private val melRing = FloatArray(N_MELS * N_FRAMES)
  private val re = DoubleArray(N_FFT)
  private val im = DoubleArray(N_FFT)
  private val spectrum = DoubleArray(N_BINS)
  private val window = DoubleArray(N_FFT).also { w ->
    val offset = (N_FFT - 400) / 2
    for (i in 0 until 400) w[offset + i] = hann[i]
  }
  private val reversed = IntArray(N_FFT).also { arr ->
    for (i in 0 until N_FFT) {
      var x = i
      var y = 0
      repeat(9) { y = (y shl 1) or (x and 1); x = x shr 1 }
      arr[i] = y
    }
  }
  private val output = FloatArray(N_MELS * N_FRAMES)
  private var pendingSamples = 0
  private var initialized = false

  /** Always ingest the chunk. Return the current [40,101] tensor only when the voice gate passes. */
  fun ingest(samples: ShortArray): FloatArray? {
    val tmp = DoubleArray(samples.size)
    for (i in samples.indices) tmp[i] = samples[i] / 32768.0
    cascade.process(tmp)
    if (tmp.size >= WINDOW_SAMPLES) {
      val start = tmp.size - WINDOW_SAMPLES
      for (i in 0 until WINDOW_SAMPLES) filtered[i] = tmp[start + i].toFloat()
      pendingSamples = WINDOW_SAMPLES
    } else {
      filtered.copyInto(filtered, 0, tmp.size, WINDOW_SAMPLES)
      for (i in tmp.indices) filtered[WINDOW_SAMPLES - tmp.size + i] = tmp[i].toFloat()
      pendingSamples = min(WINDOW_SAMPLES, pendingSamples + tmp.size)
    }
    if (!passesEnergyGate()) return null
    computeMel()
    return output
  }

  fun reset() {
    cascade.reset()
    filtered.fill(0f)
    melRing.fill(0f)
    output.fill(0f)
    pendingSamples = 0
    initialized = false
  }

  private fun passesEnergyGate(): Boolean {
    var sum = 0.0
    var lowPass = 0.0
    var bandSum = 0.0
    for (v in filtered) {
      val x = v.toDouble()
      sum += x * x
      lowPass += 0.936 * (x - lowPass)
      bandSum += lowPass * lowPass
    }
    val rms = sqrt(sum / filtered.size)
    if (rms < 1e-9 || 20.0 * log10(rms) < -55.0) return false
    return bandSum / (sum + 1e-12) >= 0.15
  }

  private fun computeMel() {
    val advance = min(N_FRAMES, pendingSamples / HOP)
    pendingSamples -= advance * HOP
    if (!initialized || advance >= N_FRAMES - 3) {
      for (i in 0 until N_FRAMES) computeFrame(i)
      initialized = true
    } else if (advance > 0) {
      for (m in 0 until N_MELS) {
        val base = m * N_FRAMES
        melRing.copyInto(melRing, base, base + advance, base + N_FRAMES)
      }
      computeFrame(0)
      computeFrame(1)
      var start = N_FRAMES - advance - 2
      if (start < 2) start = 2
      for (i in start until N_FRAMES) computeFrame(i)
    }
    for (m in 0 until N_MELS) {
      val base = m * N_FRAMES
      var sum = 0.0
      for (i in 0 until N_FRAMES) sum += melRing[base + i]
      val mean = sum / N_FRAMES
      for (i in 0 until N_FRAMES) output[base + i] = (melRing[base + i] - mean).toFloat()
    }
  }

  private fun computeFrame(frame: Int) {
    val lo = frame * HOP - N_FFT / 2
    for (n in 0 until N_FFT) {
      var index = lo + n
      if (index < 0) index = -index
      else if (index >= WINDOW_SAMPLES) index = 2 * (WINDOW_SAMPLES - 1) - index
      index = index.coerceIn(0, WINDOW_SAMPLES - 1)
      re[n] = filtered[index] * window[n]
      im[n] = 0.0
    }
    fft()
    for (k in 0 until N_BINS) spectrum[k] = re[k] * re[k] + im[k] * im[k]
    for (m in 0 until N_MELS) {
      var acc = 0.0
      for ((bin, weight) in mel[m]) acc += weight * spectrum[bin]
      melRing[m * N_FRAMES + frame] = ln(max(acc, LOG_FLOOR)).toFloat()
    }
  }

  private fun fft() {
    for (i in 0 until N_FFT) {
      val j = reversed[i]
      if (j > i) {
        val tr = re[i]; re[i] = re[j]; re[j] = tr
        val ti = im[i]; im[i] = im[j]; im[j] = ti
      }
    }
    var size = 2
    while (size <= N_FFT) {
      val half = size / 2
      val step = -2.0 * Math.PI / size
      for (base in 0 until N_FFT step size) {
        for (j in 0 until half) {
          val angle = step * j
          val wr = kotlin.math.cos(angle)
          val wi = kotlin.math.sin(angle)
          val even = base + j
          val odd = even + half
          val tr = wr * re[odd] - wi * im[odd]
          val ti = wr * im[odd] + wi * re[odd]
          re[odd] = re[even] - tr
          im[odd] = im[even] - ti
          re[even] += tr
          im[even] += ti
        }
      }
      size *= 2
    }
  }

  private fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

  private class Cascade(filters: JSONObject) {
    private val stages = arrayOf("hpf_100", "notch_50", "notch_60").map { name ->
      val f = filters.getJSONObject(name)
      val sections = f.getJSONArray("sections")
      val zi = f.getJSONArray("zi")
      Array(sections.length()) { index ->
        val s = sections.getJSONObject(index)
        Section(
          s.getDouble("b0"), s.getDouble("b1"), s.getDouble("b2"),
          s.getDouble("a1"), s.getDouble("a2"),
          zi.getJSONArray(index).getDouble(0), zi.getJSONArray(index).getDouble(1),
        )
      }
    }

    fun reset() { for (stage in stages) for (section in stage) section.reset() }

    fun process(input: DoubleArray) {
      if (input.isEmpty()) return
      for (sectionList in stages) {
        for (section in sectionList) {
          section.initialize(input[0])
          section.process(input)
        }
      }
    }
  }

  private class Section(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
    private val zi1: Double, private val zi2: Double,
  ) {
    private var s1 = 0.0
    private var s2 = 0.0
    private var inited = false
    fun reset() { s1 = 0.0; s2 = 0.0; inited = false }
    fun initialize(first: Double) {
      if (!inited) { s1 = zi1 * first; s2 = zi2 * first; inited = true }
    }
    fun process(x: DoubleArray) {
      var a = s1; var b = s2
      for (i in x.indices) {
        val y = b0 * x[i] + a
        a = b1 * x[i] + b - a1 * y
        b = b2 * x[i] - a2 * y
        x[i] = y
      }
      s1 = a; s2 = b
    }
  }
}
