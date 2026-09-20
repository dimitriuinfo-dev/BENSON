package expo.modules.foregroundservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * ROUND_WAKE_NATIVE_GENERIC_1 — Option C from ROUND_WAKE_NATIVE_GENERIC_FEASIBILITY_1_REPORT.md.
 *
 * Fully native wake loop: a dedicated AudioRecord + energy-VAD thread inside
 * BensonForegroundService (same idiom as MicroWakeWord.kt), gated bursts uploaded to a cloud STT
 * endpoint via OkHttp (already on this module's classpath — see build.gradle), matched against a
 * single configurable wake name. React Native is NOT involved in detection — the whole reason for
 * this round is that the JS runtime suspends when the Activity backgrounds
 * (ROUND_WAKE_STATE_BUG_1_REPORT.md).
 *
 * VAD constants are a deliberate literal duplicate of BensonAudioCaptureModule.kt's proven,
 * device-tuned values — the two modules have no Gradle dependency between them, same idiom
 * already used by BensonForegroundService.kt's own WakeGate object for the same reason.
 *
 * Wake name and STT credentials are never hardcoded here: both are read from
 * `benson_watchdog_prefs`, pushed down from JS (setWakeName / setNativeWakeCredentials in
 * BensonForegroundServiceModule.kt) — the same SharedPreferences-push idiom already established
 * for setSttLanguage/setPorcupineAccessKey. This is the ONE authoritative wake-name source for
 * this native path (see KEY_WAKE_NAME / currentWakeName below).
 */
class NativeCloudWake(
  private val context: Context,
  private val onDetected: (commandTail: String) -> Unit,
  private val log: (stage: String, fields: String) -> Unit,
) {
  companion object {
    private const val PREFS_NAME = "benson_watchdog_prefs"
    const val KEY_API_KEY = "wake_stt_api_key"
    const val KEY_BASE_URL = "wake_stt_base_url"
    const val KEY_MODEL = "wake_stt_model"
    const val KEY_WAKE_NAME = "wake_name"
    const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    const val DEFAULT_WAKE_NAME = "Benson"

    // DEV_STT_DEEPGRAM_WAKE_1 (2026-09-17) — Groq's request quota is exhausted AGAIN
    // (device-confirmed: WAKE_NATIVE_ERROR reason=stt_http code=429 on the large majority of wake
    // calls this session, the ONE remaining STT path still on Groq after main-command and
    // confirmation already moved to Deepgram for the same reason). Own SharedPreferences key,
    // deliberately separate from both KEY_API_KEY above (Groq, stays in place for revert) and
    // NativeConfirmationListener's confirmation-only Deepgram key — three independent consumers,
    // never sharing a credential slot, so changing one can never silently affect another.
    const val KEY_WAKE_DEEPGRAM_API_KEY = "wake_stt_deepgram_api_key"
    private const val DEEPGRAM_BASE_URL = "https://api.deepgram.com/v1/listen"
    private const val DEEPGRAM_MODEL = "nova-3"
    private const val DEEPGRAM_LANGUAGE = "ro"

    // Audio format — identical to BensonAudioCaptureModule.kt / MicroWakeWord.kt.
    private const val SAMPLE_RATE = 16000
    private const val READ_CHUNK_MS = 50L

    // VAD tuning — literal duplicate of BensonAudioCaptureModule.kt's device-proven values
    // (RMS_THRESHOLD=700.0 measured live 2026-08-23; SILENCE_TIMEOUT_MS=1600 from E2-3
    // 2026-09-07). See that file's own header comments for the measurement history — not
    // re-derived here, only reused.
    private const val RMS_THRESHOLD = 700.0
    private const val MIN_SPEECH_MS = 800L
    private const val SILENCE_TIMEOUT_MS = 1600L
    private const val EARLY_NO_SPEECH_STOP_MS = 3000L
    // Smaller than BensonAudioCaptureModule's MAX_DURATION_MS=15000 — a wake phrase plus an
    // optional same-breath command tail is much shorter than a full command capture, and holding
    // the mic open for 15s on every passive cycle would needlessly delay re-scanning. Revert:
    // raise to 15000 to match full command-capture behavior exactly.
    private const val MAX_UTTERANCE_MS = 6000L
    private const val PRE_ROLL_CHUNKS = 11

    fun available(context: Context): Boolean {
      val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""
      return key.isNotBlank()
    }

    fun currentWakeName(context: Context): String {
      val n = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_WAKE_NAME, DEFAULT_WAKE_NAME) ?: DEFAULT_WAKE_NAME
      return if (n.isBlank()) DEFAULT_WAKE_NAME else n
    }

    // Exposed for a plain unit-style sanity check from the module layer if ever needed — the real
    // authority for "does this transcript contain the wake word" at runtime is the instance method
    // below, called from the loop thread.
    fun normalize(s: String): String {
      val lower = s.lowercase()
      val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
      return nfd.replace(Regex("[\\u0300-\\u036f]"), "")
    }

    fun levenshtein(a: String, b: String): Int {
      val m = a.length
      val n = b.length
      if (m == 0) return n
      if (n == 0) return m
      var prev = IntArray(n + 1) { it }
      for (i in 1..m) {
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
          cur[j] = minOf(
            cur[j - 1] + 1,
            prev[j] + 1,
            prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1),
          )
        }
        prev = cur
      }
      return prev[n]
    }

    // BENSON_GROUNDED_CONVERSATION_1 (2026-09-20, product-owner-directed) — a closed, exact-match
    // set only. "Benson, bună dimineața" already worked before this round (the greeting is AFTER
    // the wake word — ordinary commandTail, untouched). This adds the ONLY other explicitly
    // authorized order: "Bună dimineața, Benson" (greeting BEFORE the wake word). Deliberately NOT
    // "pass everything before Benson as the command" — that was explicitly forbidden (a user
    // muttering unrelated words right before saying "Benson" must never be replayed as a command).
    // Normalized (diacritics stripped, lowercased) so "Bună dimineața"/"Buna dimineata" both match.
    private val GREETING_PHRASES_NORMALIZED = setOf(
      "buna dimineata", "buna ziua", "buna seara", "salut", "buna", "servus", "salutare",
      "guten morgen", "guten tag", "guten abend", "hallo",
      "good morning", "good afternoon", "good evening", "hello", "hi",
    )

    // Conservative port of app/index.tsx's detectWakeWord()/stripWakeWord(): normalize (lowercase
    // + strip diacritics), then an exact substring/whole-word check, then a Levenshtein<=1 fuzzy
    // fallback on individual tokens — generalized to whatever `wakeName` is configured (that
    // string is the ONE authoritative source; there is no second hardcoded name list here).
    // Returns (matched, isExactMatch, commandTail).
    //
    // BENSON_GROUNDED_CONVERSATION_1 — commandTail is now resolved via resolveTail() below instead
    // of a bare `words.drop(i + 1)...`, so a recognized greeting spoken BEFORE the wake word (and
    // nothing meaningful after it) still reaches the dispatcher as if it were said after — every
    // other case (bare "Benson", "Benson <command>", unrecognized text before the name) is
    // byte-for-byte the same tail this function already returned before this round.
    fun matchWake(transcriptRaw: String, wakeName: String): Triple<Boolean, Boolean, String> {
      val words = transcriptRaw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      val normName = normalize(wakeName)
      if (words.isEmpty() || normName.isBlank()) return Triple(false, false, "")

      fun resolveTail(index: Int): String {
        val after = words.drop(index + 1).joinToString(" ").trim()
        if (after.isNotEmpty()) return after
        val before = words.take(index).joinToString(" ") { normalize(it.trim(',', '.', '!', '?', ':', ';', '-')) }.trim()
        return if (before.isNotEmpty() && GREETING_PHRASES_NORMALIZED.contains(before)) {
          words.take(index).joinToString(" ").trim(',', '.', '!', '?', ':', ';', '-', ' ')
        } else ""
      }
      fun cleanWord(w: String) = w.trim(',', '.', '!', '?', ':', ';', '-')

      for (i in words.indices) {
        val normW = normalize(cleanWord(words[i]))
        if (normW.isEmpty()) continue
        if (normW == normName || normW.contains(normName) || normName.contains(normW)) {
          return Triple(true, true, resolveTail(i))
        }
      }
      // Fuzzy fallback — same tolerance rule as the JS gate. 2026-09-18, device-confirmed: real
      // Deepgram transcripts of "Benson" spoken by a Romanian speaker came back as "bensăm" and
      // "benzan" (edit distance 2 from "benson") and were both rejected by a distance-1 tolerance,
      // producing WAKE_NO_MATCH on a genuine wake attempt — the user said the word, it transcribed
      // close but not within 1 edit. Only names of 5+ normalized characters get any tolerance at
      // all (a distance-1 fuzzy match on a 3-4 letter name would match almost anything); 6+ chars
      // get distance-2, since a false match risk on a 6-letter name at 2 edits is still low.
      val maxDist = when {
        normName.length >= 6 -> 2
        normName.length >= 5 -> 1
        else -> 0
      }
      for (i in words.indices) {
        val normW = normalize(cleanWord(words[i]))
        if (normW.length < 3) continue
        if (levenshtein(normW, normName) <= maxDist) {
          return Triple(true, false, resolveTail(i))
        }
      }
      return Triple(false, false, "")
    }
  }

  @Volatile private var running = false
  private var thread: Thread? = null
  @Volatile private var currentCall: Call? = null
  private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  fun isRunning(): Boolean = running

  /** Idempotent. False (and no thread started) if no STT credentials are configured yet. */
  fun start(): Boolean {
    if (running) return true
    if (!available(context)) {
      log("WAKE_NATIVE_START", "skipped=true reason=no_credentials")
      return false
    }
    running = true
    thread = Thread({ loop() }, "benson-cloudwake").also {
      it.priority = Thread.NORM_PRIORITY
      it.start()
    }
    log("WAKE_NATIVE_START", "wakeName=\"${currentWakeName(context)}\"")
    return true
  }

  /** Idempotent. Cancels any in-flight STT request so mic ownership hands off promptly instead of
   *  waiting out the HTTP read timeout — critical for WAKE-NATIVE-4 (mic-hold recovery). */
  fun stop() {
    if (!running && thread == null) return
    running = false
    try { currentCall?.cancel() } catch (_: Exception) {}
    try { thread?.join(1500) } catch (_: Exception) {}
    thread = null
    log("WAKE_NATIVE_STOP", "")
  }

  private fun loop() {
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
    if (minBuf <= 0) {
      log("WAKE_NATIVE_ERROR", "reason=min_buffer_size value=$minBuf")
      running = false
      return
    }
    val record = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, channelConfig, audioFormat, minBuf * 2)
    } catch (e: Exception) {
      log("WAKE_NATIVE_ERROR", "reason=audiorecord_ctor error=\"${e.message}\"")
      running = false
      return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      log("WAKE_NATIVE_ERROR", "reason=audiorecord_uninitialized")
      try { record.release() } catch (_: Exception) {}
      running = false
      return
    }

    val chunkSamples = (SAMPLE_RATE * READ_CHUNK_MS / 1000).toInt()
    val readBuffer = ShortArray(chunkSamples)
    var pcm = ByteArrayOutputStream()
    var phase = "pre_speech" // pre_speech -> in_speech -> (handed off, cycle resets)
    var speechStartedAt = 0L
    var lastLoudAt = 0L
    var cycleStartedAt = System.currentTimeMillis()
    val preRoll = ArrayDeque<ByteArray>()

    fun resetCycle() {
      phase = "pre_speech"
      pcm = ByteArrayOutputStream()
      preRoll.clear()
      cycleStartedAt = System.currentTimeMillis()
    }

    try {
      record.startRecording()
      log("WAKE_AUDIO_ACQUIRE", "source=VOICE_RECOGNITION rate=$SAMPLE_RATE")
      while (running) {
        val now = System.currentTimeMillis()
        val elapsed = now - cycleStartedAt

        if (phase == "pre_speech" && elapsed > EARLY_NO_SPEECH_STOP_MS) {
          resetCycle()
          continue
        }
        if (elapsed > MAX_UTTERANCE_MS) {
          if (phase == "in_speech" && pcm.size() > 0) {
            log("WAKE_VAD_END", "reason=max_duration bytes=${pcm.size()}")
            handleUtterance(pcm.toByteArray())
          }
          resetCycle()
          continue
        }
        if (phase == "in_speech" && (now - lastLoudAt) > SILENCE_TIMEOUT_MS && (now - speechStartedAt) > MIN_SPEECH_MS) {
          log("WAKE_VAD_END", "reason=vad_silence bytes=${pcm.size()}")
          handleUtterance(pcm.toByteArray())
          resetCycle()
          continue
        }

        val read = record.read(readBuffer, 0, chunkSamples)
        if (read <= 0) continue

        var sumSquares = 0.0
        for (i in 0 until read) {
          val s = readBuffer[i].toDouble()
          sumSquares += s * s
        }
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
            phase = "in_speech"
            speechStartedAt = now
            log("WAKE_VAD_BEGIN", "rms=${"%.1f".format(rms)}")
            for (chunk in preRoll) pcm.write(chunk)
            preRoll.clear()
          }
        }
      }
    } catch (e: Exception) {
      log("WAKE_NATIVE_ERROR", "reason=loop error=\"${e.javaClass.simpleName}: ${e.message}\"")
    } finally {
      try { record.stop() } catch (_: Exception) {}
      try { record.release() } catch (_: Exception) {}
      log("WAKE_AUDIO_RELEASE", "")
    }
  }

  // Runs synchronously on the loop thread — the STT round trip is a deliberate pause in scanning
  // (same trade-off already accepted by the legacy SpeechRecognizer burst loop), not a new one.
  private fun handleUtterance(pcmBytes: ByteArray) {
    if (pcmBytes.isEmpty() || !running) return
    val wakeName = currentWakeName(context)
    log("WAKE_STT_REQUEST", "bytes=${pcmBytes.size}")
    // DEV_STT_DEEPGRAM_WAKE_1 — dev routing; postToGroq(pcmBytes) is the production call this
    // reverts to (see companion object comment above).
    val transcript = postToDeepgram(pcmBytes)
    if (!running) return // stopped while the network call was in flight — never fire a stale trigger
    if (transcript == null) {
      log("WAKE_STT_RESULT", "ok=false")
      return
    }
    log("WAKE_STT_RESULT", "ok=true chars=${transcript.length} text=\"$transcript\"")
    val (matched, exact, tail) = matchWake(transcript, wakeName)
    if (!matched) {
      log("WAKE_NO_MATCH", "text=\"$transcript\"")
      return
    }
    log(if (exact) "WAKE_MATCH_EXACT" else "WAKE_MATCH_FUZZY", "wakeName=\"$wakeName\" tail=\"$tail\"")
    log("WAKE_TRIGGER", "source=native_cloud tail=\"$tail\"")
    onDetected(tail)
  }

  // DEV_STT_DEEPGRAM_WAKE_1 — Deepgram's pre-recorded /listen endpoint takes the raw WAV bytes as
  // the request body directly (no multipart, unlike postToGroq below). Never logs the API key.
  // Returns null on any failure (network, non-2xx, empty body, no key configured) — the caller
  // treats that as "no match this cycle", same as a VAD miss.
  private fun postToDeepgram(pcmBytes: ByteArray): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val apiKey = prefs.getString(KEY_WAKE_DEEPGRAM_API_KEY, "") ?: ""
    log("WAKE_STT_PROVIDER_ATTEMPT", "provider=deepgram")
    if (apiKey.isBlank()) { log("WAKE_STT_PROVIDER_RESULT", "provider=deepgram status=error reason=no_api_key"); return null }
    val wav = buildWav(pcmBytes)
    val body = wav.toRequestBody("audio/wav".toMediaType())
    val url = "$DEEPGRAM_BASE_URL?model=$DEEPGRAM_MODEL&language=$DEEPGRAM_LANGUAGE"
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $apiKey").post(body).build()
    val call = client.newCall(request)
    currentCall = call
    return try {
      call.execute().use { resp ->
        log("WAKE_STT_PROVIDER_RESULT", "provider=deepgram http_status=${resp.code}")
        if (!resp.isSuccessful) {
          log("WAKE_NATIVE_ERROR", "reason=stt_http code=${resp.code}")
          return null
        }
        val json = resp.body?.string() ?: return null
        val alt = JSONObject(json).optJSONObject("results")
          ?.optJSONArray("channels")?.optJSONObject(0)
          ?.optJSONArray("alternatives")?.optJSONObject(0)
        (alt?.optString("transcript", "") ?: "").trim()
      }
    } catch (e: Exception) {
      if (!running) return null
      log("WAKE_NATIVE_ERROR", "reason=deepgram_stt_exception error=\"${e.javaClass.simpleName}: ${e.message}\"")
      null
    } finally {
      currentCall = null
    }
  }

  // Never logs the API key. Returns null on any failure (network, non-2xx, empty body) — the
  // caller treats that as "no match this cycle", same as a VAD miss.
  private fun postToGroq(pcmBytes: ByteArray): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
    if (apiKey.isBlank()) return null
    val baseUrl = (prefs.getString(KEY_BASE_URL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL).trimEnd('/')
    val model = prefs.getString(KEY_MODEL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    val sttLang = (prefs.getString("stt_language", null) ?: "ro-RO").split("-").firstOrNull()?.lowercase() ?: "ro"

    val wav = buildWav(pcmBytes)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
      .addFormDataPart("model", model)
      .addFormDataPart("language", sttLang)
      .addFormDataPart("response_format", "json")
      .build()
    val request = Request.Builder()
      .url("$baseUrl/audio/transcriptions")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body)
      .build()
    val call = client.newCall(request)
    currentCall = call
    return try {
      call.execute().use { resp ->
        if (!resp.isSuccessful) {
          log("WAKE_NATIVE_ERROR", "reason=stt_http code=${resp.code}")
          return null
        }
        val json = resp.body?.string() ?: return null
        JSONObject(json).optString("text", "").trim()
      }
    } catch (e: Exception) {
      // A deliberate cancel() from stop() also lands here (IOException) — not logged as an error,
      // since it is expected mic-ownership behavior, not a fault.
      if (!running) return null
      log("WAKE_NATIVE_ERROR", "reason=stt_exception error=\"${e.javaClass.simpleName}: ${e.message}\"")
      null
    } finally {
      currentCall = null
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

  // Same WAV header layout as BensonAudioCaptureModule.kt's buildWavHeader — duplicated (no
  // Gradle dependency between the two modules), built directly into memory since this loop
  // uploads the clip rather than reading it back from disk.
  private fun buildWav(pcmData: ByteArray): ByteArray {
    val byteRate = SAMPLE_RATE * 2
    val header = ByteArray(44)
    fun writeStr(offset: Int, s: String) { s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() } }
    fun writeInt(offset: Int, v: Int) {
      header[offset] = (v and 0xFF).toByte()
      header[offset + 1] = ((v shr 8) and 0xFF).toByte()
      header[offset + 2] = ((v shr 16) and 0xFF).toByte()
      header[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun writeShort(offset: Int, v: Int) {
      header[offset] = (v and 0xFF).toByte()
      header[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }
    writeStr(0, "RIFF"); writeInt(4, 36 + pcmData.size); writeStr(8, "WAVE")
    writeStr(12, "fmt "); writeInt(16, 16); writeShort(20, 1); writeShort(22, 1)
    writeInt(24, SAMPLE_RATE); writeInt(28, byteRate); writeShort(32, 2); writeShort(34, 16)
    writeStr(36, "data"); writeInt(40, pcmData.size)
    return header + pcmData
  }
}
