package expo.modules.foregroundservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * URGENT_CONFIRMATION_NATIVE_1 — one-shot YES/NO/UNKNOWN reply capture, entirely native (survives
 * JS suspension/backgrounding). Same AudioRecord+VAD+cloud-STT recipe as NativeCloudWake (literal
 * duplicate of its tuning constants and credential source — same no-Gradle-dependency idiom that
 * file already uses relative to BensonAudioCaptureModule.kt), but captures exactly ONE utterance
 * then stops, and classifies YES/NO instead of matching a wake name. `verdict` here is diagnostic
 * only — app/index.tsx re-runs the transcript through its own proven classifyConfirmation() before
 * acting, so this file owns capture reliability, not confirmation business logic.
 */
class NativeConfirmationListener(
  private val context: Context,
  private val onResult: (verdict: String, transcript: String) -> Unit, // YES|NO|UNKNOWN|TIMEOUT
  private val log: (stage: String, fields: String) -> Unit,
) {
  companion object {
    private const val SAMPLE_RATE = 16000
    private const val READ_CHUNK_MS = 50L
    private const val RMS_THRESHOLD = 700.0
    private const val MIN_SPEECH_MS = 500L
    private const val SILENCE_TIMEOUT_MS = 1200L
    private const val MAX_UTTERANCE_MS = 6000L
    private const val PRE_ROLL_CHUNKS = 11
    // ROUND_CONFIRM_SELF_ECHO_1 — self-echo fix (2026-09-15: BENSON's own spoken disambiguation
    // question was captured by this exact listener and mis-heard as the user's reply, which then
    // false-positive-matched and silently opened an unrelated app). Reuses the SAME value already
    // proven as app/index.tsx's TTS_TAIL_MS — a fixed settle window before capture starts, so a
    // speaker-playback tail can't be recorded regardless of AEC effectiveness below.
    private const val POST_TTS_SETTLE_MS = 500L

    // DEV_STT_DEEPGRAM_1 (2026-09-16) — Groq's request quota is exhausted (see memory
    // project_groq_quota_blocker); Deepgram Nova-3 is the DEVELOPMENT-only confirmation/
    // disambiguation STT provider while that's true. postToGroq() below stays fully in place,
    // unused, for the production revert (switch finishWithCapture()'s call back to it). Own
    // SharedPreferences key, deliberately NOT NativeCloudWake.KEY_API_KEY — this must never
    // repoint the passive wake loop, which stays on Groq (see BensonForegroundServiceModule.kt's
    // setConfirmationSttCredentials).
    const val KEY_CONFIRM_DEEPGRAM_API_KEY = "confirm_stt_deepgram_api_key"
    private const val DEEPGRAM_BASE_URL = "https://api.deepgram.com/v1/listen"
    private const val DEEPGRAM_MODEL = "nova-3"
    private const val DEEPGRAM_LANGUAGE = "ro"

    private val YES_WORDS = setOf("da", "sigur", "trimite", "confirm", "confirma", "exact", "asa", "trimitel")
    private val NO_WORDS = setOf("nu", "anuleaza", "renunta", "stop", "opreste")

    fun normalize(s: String): String {
      val lower = s.lowercase()
      val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
      return nfd.replace(Regex("[\\u0300-\\u036f]"), "")
    }

    // Diagnostic-only classification (see class doc) — a NO word anywhere wins over a bare YES
    // word elsewhere in the same reply (e.g. "nu, trimite-l mai tarziu" must not read as YES).
    fun classify(transcriptRaw: String): String {
      val norm = normalize(transcriptRaw)
      val words = norm.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
      if (words.any { it in NO_WORDS }) return "NO"
      if (words.any { it in YES_WORDS }) return "YES"
      return "UNKNOWN"
    }
  }

  @Volatile private var running = false
  @Volatile private var currentCall: Call? = null
  private var thread: Thread? = null
  private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
    .build()

  fun isRunning(): Boolean = running

  fun start(overallTimeoutMs: Long) {
    if (running) return
    running = true
    thread = Thread({ loop(overallTimeoutMs) }, "benson-confirmlisten").also {
      it.priority = Thread.NORM_PRIORITY
      it.start()
    }
  }

  /** Cancels any in-flight STT request so mic ownership hands off promptly. */
  fun stop() {
    if (!running && thread == null) return
    running = false
    try { currentCall?.cancel() } catch (_: Exception) {}
    try { thread?.join(1500) } catch (_: Exception) {}
    thread = null
  }

  private fun loop(overallTimeoutMs: Long) {
    var delivered = false
    fun deliver(verdict: String, transcript: String) {
      if (delivered) return
      delivered = true
      onResult(verdict, transcript)
    }

    // ROUND_CONFIRM_SELF_ECHO_1 — deliberate settle window BEFORE the mic even opens: TTS_COMPLETE
    // → mic ownership already released to CONFIRMATION_STT by the caller → this short sleep →
    // AudioRecord created/started. Not a JS setTimeout (those go inert while backgrounded) — a
    // plain thread sleep on this listener's own dedicated thread, so it survives backgrounding.
    try { Thread.sleep(POST_TTS_SETTLE_MS) } catch (_: InterruptedException) {}
    if (!running) { deliver("TIMEOUT", ""); return }

    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
    if (minBuf <= 0) { log("CONFIRM_NATIVE_ERROR", "reason=min_buffer_size"); running = false; deliver("TIMEOUT", ""); return }
    val record = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, channelConfig, audioFormat, minBuf * 2)
    } catch (e: Exception) {
      log("CONFIRM_NATIVE_ERROR", "reason=audiorecord_ctor error=\"${e.message}\""); running = false; deliver("TIMEOUT", ""); return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      log("CONFIRM_NATIVE_ERROR", "reason=audiorecord_uninitialized")
      try { record.release() } catch (_: Exception) {}
      running = false; deliver("TIMEOUT", ""); return
    }

    // ROUND_CONFIRM_SELF_ECHO_1 — same AEC fix already proven in BensonAudioCaptureModule.kt for
    // the identical bug class (confirmed live 2026-07-30 there); this listener never inherited it.
    // AEC availability is device-dependent — this is a best-effort layer, not the only guard (see
    // the settle sleep above and app/index.tsx's looksLikeSelfEcho() content-based rejection).
    var aec: AcousticEchoCanceler? = null
    try {
      val available = AcousticEchoCanceler.isAvailable()
      log("CONFIRM_AEC_AVAILABLE", "available=$available sessionId=${record.audioSessionId}")
      if (available) {
        val created = AcousticEchoCanceler.create(record.audioSessionId)
        if (created != null) {
          aec = created
          log("CONFIRM_AEC_CREATE", "created=true sessionId=${record.audioSessionId}")
          aec.enabled = true
          log("CONFIRM_AEC_ENABLED", "enabled=${aec.enabled} sessionId=${record.audioSessionId}")
        } else {
          log("CONFIRM_AEC_UNAVAILABLE", "created=false sessionId=${record.audioSessionId}")
        }
      } else {
        log("CONFIRM_AEC_UNAVAILABLE", "available=false sessionId=${record.audioSessionId}")
      }
    } catch (e: Exception) {
      log("CONFIRM_AEC_ERROR", "error=\"${e.message}\" sessionId=${record.audioSessionId}")
    }

    val chunkSamples = (SAMPLE_RATE * READ_CHUNK_MS / 1000).toInt()
    val readBuffer = ShortArray(chunkSamples)
    var pcm = ByteArrayOutputStream()
    var phase = "pre_speech"
    var speechStartedAt = 0L
    var lastLoudAt = 0L
    val startedAt = System.currentTimeMillis()
    val preRoll = ArrayDeque<ByteArray>()

    fun finishWithCapture(reason: String) {
      log("CONFIRM_LISTEN_VAD_END", "reason=$reason bytes=${pcm.size()}")
      val bytes = pcm.toByteArray()
      // DEV_STT_DEEPGRAM_1 — dev routing; postToGroq(bytes) is the production call this reverts
      // to (see companion object comment above).
      val transcript = postToDeepgram(bytes) ?: ""
      val verdict = if (transcript.isBlank()) "UNKNOWN" else classify(transcript)
      log("CONFIRM_LISTEN_STT_RESULT", "ok=${transcript.isNotBlank()} text=\"$transcript\" verdict=$verdict")
      deliver(verdict, transcript)
    }

    try {
      record.startRecording()
      log("CONFIRM_LISTEN_AUDIO_ACQUIRE", "source=VOICE_RECOGNITION rate=$SAMPLE_RATE")
      while (running && !delivered) {
        val now = System.currentTimeMillis()
        if (now - startedAt > overallTimeoutMs) {
          log("CONFIRM_LISTEN_TIMEOUT", "elapsedMs=${now - startedAt}")
          deliver("TIMEOUT", "")
          break
        }
        if (phase == "in_speech" && (now - lastLoudAt) > SILENCE_TIMEOUT_MS && (now - speechStartedAt) > MIN_SPEECH_MS) {
          finishWithCapture("vad_silence"); break
        }
        if (phase == "in_speech" && (now - speechStartedAt) > MAX_UTTERANCE_MS) {
          finishWithCapture("max_duration"); break
        }

        val read = record.read(readBuffer, 0, chunkSamples)
        if (read <= 0) continue
        var sumSquares = 0.0
        for (i in 0 until read) { val s = readBuffer[i].toDouble(); sumSquares += s * s }
        val rms = sqrt(sumSquares / read)
        val bytes = shortsToBytes(readBuffer, read)
        if (phase == "in_speech") {
          pcm.write(bytes)
        } else {
          preRoll.addLast(bytes)
          if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
        }
        if (rms > RMS_THRESHOLD) {
          lastLoudAt = now
          if (phase == "pre_speech") {
            phase = "in_speech"; speechStartedAt = now
            log("CONFIRM_LISTEN_VAD_BEGIN", "rms=${"%.1f".format(rms)}")
            for (chunk in preRoll) pcm.write(chunk)
            preRoll.clear()
          }
        }
      }
      if (!delivered) deliver("TIMEOUT", "")
    } catch (e: Exception) {
      log("CONFIRM_NATIVE_ERROR", "reason=loop error=\"${e.javaClass.simpleName}: ${e.message}\"")
      deliver("TIMEOUT", "")
    } finally {
      running = false
      try { aec?.release() } catch (_: Exception) {}
      if (aec != null) log("CONFIRM_AEC_RELEASE", "")
      try { record.stop() } catch (_: Exception) {}
      try { record.release() } catch (_: Exception) {}
      log("CONFIRM_LISTEN_AUDIO_RELEASE", "")
      log("CONFIRM_LISTEN_END", "")
    }
  }

  private fun postToGroq(pcmBytes: ByteArray): String? {
    if (pcmBytes.isEmpty()) return null
    val prefs = context.getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString(NativeCloudWake.KEY_API_KEY, "") ?: ""
    if (apiKey.isBlank()) return null
    val baseUrl = (prefs.getString(NativeCloudWake.KEY_BASE_URL, "")?.takeIf { it.isNotBlank() } ?: NativeCloudWake.DEFAULT_BASE_URL).trimEnd('/')
    val model = prefs.getString(NativeCloudWake.KEY_MODEL, "")?.takeIf { it.isNotBlank() } ?: NativeCloudWake.DEFAULT_MODEL
    val sttLang = (prefs.getString("stt_language", null) ?: "ro-RO").split("-").firstOrNull()?.lowercase() ?: "ro"
    val wav = buildWav(pcmBytes)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
      .addFormDataPart("model", model)
      .addFormDataPart("language", sttLang)
      .addFormDataPart("response_format", "json")
      .build()
    val request = Request.Builder().url("$baseUrl/audio/transcriptions").addHeader("Authorization", "Bearer $apiKey").post(body).build()
    val call = client.newCall(request)
    currentCall = call
    return try {
      call.execute().use { resp ->
        if (!resp.isSuccessful) { log("CONFIRM_NATIVE_ERROR", "reason=stt_http code=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        JSONObject(json).optString("text", "").trim()
      }
    } catch (e: Exception) {
      if (running) log("CONFIRM_NATIVE_ERROR", "reason=stt_exception error=\"${e.message}\"")
      null
    } finally { currentCall = null }
  }

  // DEV_STT_DEEPGRAM_1 — Deepgram's pre-recorded /listen endpoint takes the raw WAV bytes as the
  // request body directly (no multipart, unlike Groq's postToGroq above).
  private fun postToDeepgram(pcmBytes: ByteArray): String? {
    log("CONFIRM_STT_PROVIDER_ATTEMPT", "provider=deepgram")
    if (pcmBytes.isEmpty()) { log("CONFIRM_STT_RESULT", "provider=deepgram status=error reason=empty_audio"); return null }
    val prefs = context.getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString(KEY_CONFIRM_DEEPGRAM_API_KEY, "") ?: ""
    if (apiKey.isBlank()) { log("CONFIRM_STT_RESULT", "provider=deepgram status=error reason=no_api_key"); return null }
    val wav = buildWav(pcmBytes)
    val body = wav.toRequestBody("audio/wav".toMediaType())
    val url = "$DEEPGRAM_BASE_URL?model=$DEEPGRAM_MODEL&language=$DEEPGRAM_LANGUAGE"
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $apiKey").post(body).build()
    val call = client.newCall(request)
    currentCall = call
    return try {
      call.execute().use { resp ->
        log("HTTP_STATUS", "provider=deepgram code=${resp.code}")
        if (!resp.isSuccessful) {
          log("CONFIRM_STT_RESULT", "provider=deepgram status=error http_status=${resp.code}")
          return null
        }
        val json = resp.body?.string() ?: return null
        val alt = JSONObject(json).optJSONObject("results")
          ?.optJSONArray("channels")?.optJSONObject(0)
          ?.optJSONArray("alternatives")?.optJSONObject(0)
        val transcript = (alt?.optString("transcript", "") ?: "").trim()
        val confidence = if (alt != null && alt.has("confidence")) alt.optDouble("confidence") else null
        log("CONFIRM_STT_RAW_TRANSCRIPT", "text=\"$transcript\"")
        log("CONFIRM_STT_CONFIDENCE", "value=${confidence ?: "n/a"}")
        log("CONFIRM_STT_RESULT", "provider=deepgram status=ok http_status=${resp.code}")
        transcript
      }
    } catch (e: Exception) {
      if (running) log("CONFIRM_NATIVE_ERROR", "reason=deepgram_stt_exception error=\"${e.message}\"")
      log("CONFIRM_STT_RESULT", "provider=deepgram status=exception")
      null
    } finally { currentCall = null }
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

  private fun buildWav(pcmData: ByteArray): ByteArray {
    val byteRate = SAMPLE_RATE * 2
    val header = ByteArray(44)
    fun writeStr(offset: Int, s: String) { s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() } }
    fun writeInt(offset: Int, v: Int) {
      header[offset] = (v and 0xFF).toByte(); header[offset + 1] = ((v shr 8) and 0xFF).toByte()
      header[offset + 2] = ((v shr 16) and 0xFF).toByte(); header[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun writeShort(offset: Int, v: Int) { header[offset] = (v and 0xFF).toByte(); header[offset + 1] = ((v shr 8) and 0xFF).toByte() }
    writeStr(0, "RIFF"); writeInt(4, 36 + pcmData.size); writeStr(8, "WAVE")
    writeStr(12, "fmt "); writeInt(16, 16); writeShort(20, 1); writeShort(22, 1)
    writeInt(24, SAMPLE_RATE); writeInt(28, byteRate); writeShort(32, 2); writeShort(34, 16)
    writeStr(36, "data"); writeInt(40, pcmData.size)
    return header + pcmData
  }
}
