package expo.modules.foregroundservice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
  private data class CaptureMetadata(
    val sampleRateHz: Int,
    val channels: Int,
    val routedInputType: Int?,
  )

  private val diagnosticExpiryHandler = Handler(Looper.getMainLooper())

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
    private const val DEFAULT_DEEPGRAM_LANGUAGE = "ro"
    private const val KEY_RETAIN_DIAGNOSTIC = "retain_next_diagnostic_capture"
    private const val KEY_DIAGNOSTIC_EXPIRY = "diagnostic_capture_expiry_at"
    private const val DIAGNOSTIC_WAV_NAME = "benson_diagnostic_once.wav"
    private const val DIAGNOSTIC_WAV_TTL_MS = 30L * 60L * 1000L

    // Audio format — identical to BensonAudioCaptureModule.kt / MicroWakeWord.kt.
    private const val SAMPLE_RATE = 16000
    private const val READ_CHUNK_MS = 50L

    // VAD tuning — RMS_THRESHOLD was a literal duplicate of BensonAudioCaptureModule.kt's
    // device-proven value (measured live 2026-08-23); SILENCE_TIMEOUT_MS/MAX_UTTERANCE_MS below
    // were ALSO originally duplicated from there (1600/15000→6000) but this loop's own use case
    // (a short wake phrase + optional same-breath tail, not a full dictated command) never
    // actually needed that long a leash — see ROUND_WAKE_LATENCY_1 below for why they're now
    // tuned separately for this loop specifically.
    private const val RMS_THRESHOLD = 700.0
    // Speech often gets quieter after the wake name (particularly the command tail). Use the
    // adaptive threshold to START a candidate, but a lower hysteresis threshold to keep that same
    // phrase open. Requiring every syllable to stay above the ambient-noise start threshold can
    // split "Benson, open ..." after its loud first word; the 900 ms silence timer then submits a
    // short prefix and STT returns an empty transcript. This only extends an already-started
    // candidate; it cannot start a capture or wake BENSON by itself.
    private const val ACTIVE_SPEECH_MIN_RMS = 350.0
    private const val ACTIVE_SPEECH_NOISE_MARGIN = 1.15
    private const val MIN_SPEECH_MS = 800L
    // ROUND_WAKE_LATENCY_1 (2026-09-23, device-proven) — was 1600. Device evidence: a short
    // "Benson, cât e ceasul?" utterance measured VAD_BEGIN→VAD_END = 5003ms, ending via
    // reason=max_duration (the 6s hard cap), not reason=vad_silence — meaning no 1600ms gap of
    // quiet was ever found inside a ~5s window for a phrase that takes ~2s to say out loud. In a
    // room with intermittent ambient noise (the same TV/radio ROUND_AMBIENT_NOISE_1 targeted),
    // a stray loud moment anywhere in that window keeps resetting the "last loud" clock, and 1600ms
    // is a long gap to ask a noisy room to stay quiet for. 900ms is still comfortably longer than a
    // natural mid-sentence pause (confirmed against this same "Benson, cât e ceasul?" cadence) while
    // roughly halving the worst-case tail wait whenever a reset does happen.
    private const val SILENCE_TIMEOUT_MS = 900L
    private const val EARLY_NO_SPEECH_STOP_MS = 3000L
    // ROUND_WAKE_LATENCY_1 — was 6000. A wake phrase + short same-breath tail (this loop's actual
    // job — see the doc comment above) realistically never needs 6s; lowering the hard cap bounds
    // the worst case directly (the exact 6.8s-total complaint this round answers) without touching
    // SILENCE_TIMEOUT_MS's normal-case behavior. A genuinely long command tail still isn't lost —
    // it's handled by the SEPARATE, far more generous COMMAND_STT phase after wake handoff
    // (30000ms deadline, app/index.tsx), not by this loop.
    private const val MAX_UTTERANCE_MS = 4500L
    // ROUND_WAKE_WORD_DROP_1 (2026-09-23, device-proven) — was 11 (~550ms). Device evidence, TWICE
    // in a row: user said "Benson, cât e ceasul?", VAD_BEGIN caught real voice (rms 4583 / 2389,
    // both well above threshold), Deepgram transcribed the REST of the phrase correctly ("cat este
    // ceasul") but "Benson" itself was completely absent both times — not misheard, just missing
    // from the audio Deepgram received. "Benson" is very likely said at lower volume / with a
    // small pause before the louder command clause, which the trigger threshold catches, not the
    // wake word itself. preRoll already buffers ALL pre_speech chunks regardless of loudness (see
    // the loop below), so a longer buffer directly covers a bigger gap between the wake word and
    // whatever crosses threshold after it. 40 chunks = 2s, enough to preserve the start of a
    // natural one-breath wake + short command even when only the louder final word crosses VAD.
    private const val PRE_ROLL_CHUNKS = 40

    // ROUND_AMBIENT_NOISE_1 (2026-09-23, product-owner-directed, device-proven root cause) — a
    // FIXED absolute RMS_THRESHOLD cannot tell a person talking to BENSON from a steady background
    // source (TV/radio) that happens to run louder than 700 — device log evidence, same room, TV
    // on: WAKE_VAD_BEGIN rms values of 795, 1121, 1499, 1824 — all comfortably above the fixed
    // threshold, none of them the user. This is the standard failure mode fixed-threshold VAD has
    // in a noisy room; the standard fix (used by e.g. WebRTC's VAD, and conceptually how dedicated
    // far-field assistants separate "the room" from "a directed command") is an ADAPTIVE noise
    // floor: track a slow-moving average of the ambient level and require a candidate to clear a
    // MARGIN above *that*, not a fixed number tuned for a quiet room. A steadily-playing TV raises
    // the floor and gets filtered out; a person's voice — a transient, louder-than-ambient event —
    // still clears the margin. RMS_THRESHOLD is kept as an absolute floor underneath the adaptive
    // one (a silent room's floor should never drift low enough that a whisper trips it).
    private const val NOISE_FLOOR_EMA_ALPHA = 0.02
    // ROUND_WAKE_LATENCY_1 (2026-09-23, device-proven) — was 1.6. Device evidence with a TV on in
    // the room: the loop kept re-triggering VAD_BEGIN roughly every 1-8s continuously (floor
    // ~710-750, individual TV bursts 1174-2416 rms) with EMPTY or clearly-TV transcripts, and the
    // user's own genuine "Benson, cât e ceasul?" attempt was never captured at all in that ~87s
    // window — 1.6x over a merely-moderate TV floor still let frequent TV bursts through, and each
    // one occupies the mic for a full VAD+STT round-trip, competing directly with a real attempt.
    // 2.2x requires a real voice to be substantially louder than the room, not just "somewhat
    // louder than average" — the standard tradeoff of a stricter margin (fewer false triggers,
    // at some cost to catching a quiet/distant genuine utterance).
    private const val NOISE_FLOOR_MARGIN = 2.2
    private const val NOISE_FLOOR_MAX = 4000.0
    // ROUND_WAKE_WORD_DROP_1 — see consecutiveNonMatches' own comment (instance field below) for
    // why this exists. Each non-match/empty round adds one step to the margin, capped at 5 steps
    // (worst case ~2.2 + 5*0.3 = 3.7x over the noise floor); a single real match resets to 0.
    private const val NON_MATCH_BACKOFF_STEP = 0.3
    private const val NON_MATCH_BACKOFF_CAP = 5
    private const val MAX_WAKE_RESULT_AGE_MS = 8000L

    fun available(context: Context): Boolean {
      // The active development wake path below uses Deepgram, not the dormant Groq endpoint.
      // Availability must reflect the credential this path will actually read, otherwise the
      // service can report a live wake engine while every recognition attempt has no key.
      val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_WAKE_DEEPGRAM_API_KEY, "") ?: ""
      return key.isNotBlank()
    }

    private fun currentDeepgramLanguage(context: Context): String {
      val configured = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString("stt_language", null)
        ?.substringBefore('-')
        ?.lowercase()
      return if (configured == "de" || configured == "ro") configured else DEFAULT_DEEPGRAM_LANGUAGE
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
  @Volatile private var audioInputActive = false
  private var thread: Thread? = null
  // ROUND_WAKE_WORD_DROP_1 (2026-09-23, device-proven) — a sustained non-speech noise source
  // (radio) kept crossing the adaptive threshold and consuming a full VAD+STT round-trip
  // (1-3s of the loop thread "deaf" — see handleUtterance's own comment: a deliberate,
  // pre-existing trade-off, not something this round introduces) with an empty/no-match result
  // every time. Only handleUtterance (single loop thread, sequential — no concurrent writer)
  // touches this: each non-match/empty result raises it (capped), any real match resets it to 0,
  // so the effective threshold self-escalates specifically while being swamped by noise, and
  // relaxes back down the moment a genuine utterance gets through or the noise stops.
  @Volatile private var consecutiveNonMatches = 0
  @Volatile private var sttExecutor: ThreadPoolExecutor? = null
  private val activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
  private val pendingStt = AtomicInteger(0)
  private val wakeEpoch = AtomicLong(0)
  private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

  fun isRunning(): Boolean = running
  fun isAudioInputActive(): Boolean = running && audioInputActive

  /** Idempotent. False (and no thread started) if no STT credentials are configured yet. */
  fun start(): Boolean {
    if (running) return true
    if (!available(context)) {
      log("WAKE_NATIVE_START", "skipped=true reason=no_credentials")
      return false
    }
    sttExecutor = ThreadPoolExecutor(
      2, 2, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
      { task -> Thread(task, "benson-wake-stt").apply { priority = Thread.NORM_PRIORITY } },
      ThreadPoolExecutor.AbortPolicy(),
    )
    wakeEpoch.incrementAndGet()
    audioInputActive = false
    running = true
    thread = Thread({ loop() }, "benson-cloudwake").also {
      it.priority = Thread.NORM_PRIORITY
      it.start()
    }
    log("WAKE_NATIVE_START", "wakeNameConfigured=true")
    return true
  }

  /** Idempotent. Cancels any in-flight STT request so mic ownership hands off promptly instead of
   *  waiting out the HTTP read timeout — critical for WAKE-NATIVE-4 (mic-hold recovery). */
  fun stop() {
    if (!running && thread == null) return
    running = false
    audioInputActive = false
    wakeEpoch.incrementAndGet()
    activeCalls.forEach { try { it.cancel() } catch (_: Exception) {} }
    activeCalls.clear()
    try { sttExecutor?.shutdownNow() } catch (_: Exception) {}
    sttExecutor = null
    try { thread?.join(1500) } catch (_: Exception) {}
    thread = null
    log("WAKE_NATIVE_STOP", "")
  }

  // Same key/default as BensonAudioCaptureModule.kt's isNoiseSuppressorEnabled() — no Gradle
  // dependency between the two modules (same idiom as the VAD-constant duplication noted in this
  // class's own doc comment above), so mirrored here rather than shared.
  private fun isNoiseSuppressorEnabled(): Boolean {
    return try {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("noise_suppressor_enabled", false)
    } catch (_: Exception) { false }
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

    // Wake-word AEC gap (project memory 2026-09-22, closed here 2026-09-23, user-reported live:
    // "indata ce porneste muzica, Benson nu mai aude nimic"): VOICE_RECOGNITION doesn't get the
    // device's acoustic echo cancellation pipeline for free — same root cause and same fix already
    // proven live 2026-07-30 for BENSON's own TTS self-echo in BensonAudioCaptureModule.kt's
    // capture loop (command listening). That fix was never mirrored onto THIS loop (passive wake
    // listening, which is what's actually running while music plays) — this is that mirror, same
    // effect chain, same audioSessionId-scoped attach/release. NoiseSuppressor stays gated behind
    // the same runtime flag (default off) — it was disabled elsewhere after a suspected STT-
    // garbling regression on this device's OEM audio HAL; AEC alone is the mechanism that actually
    // targets known-own-device-output (echo of BENSON's speaker), which music-drowning-the-mic is a
    // case of, same as the self-TTS-echo case this pattern was already proven against.
    // ROUND_WAKE_AEC_1 — verification rigor requirement: a non-null `aec` after create() proves
    // ATTACHMENT, not that the effect is actually processing this session's audio. Log the
    // platform's own isAvailable() (device/HAL support), the created instance's actual read-back
    // `.enabled` (not the value we asked for — a failed native enable can silently no-op), and
    // hasControl() (another effect instance on the same session could have taken control). All
    // three, not just non-null, are what "AEC really on for this session" means.
    var aec: AcousticEchoCanceler? = null
    var ns: NoiseSuppressor? = null
    val aecAvailable = AcousticEchoCanceler.isAvailable()
    val nsAvailable = NoiseSuppressor.isAvailable()
    try {
      if (aecAvailable) {
        aec = AcousticEchoCanceler.create(record.audioSessionId)
        aec?.enabled = true
      }
      if (isNoiseSuppressorEnabled() && nsAvailable) {
        ns = NoiseSuppressor.create(record.audioSessionId)
        ns?.enabled = true
      }
    } catch (e: Exception) {
      log("WAKE_NATIVE_ERROR", "reason=aec_ns_setup error=\"${e.message}\"")
    }
    val aecEnabledActual = try { aec?.enabled } catch (_: Exception) { null }
    val aecHasControl = try { aec?.hasControl() } catch (_: Exception) { null }
    val nsEnabledActual = try { ns?.enabled } catch (_: Exception) { null }
    log(
      "WAKE_EFFECTS",
      "sessionId=${record.audioSessionId} aecAvailable=$aecAvailable aecCreated=${aec != null} " +
        "aecEnabled=$aecEnabledActual aecHasControl=$aecHasControl nsAvailable=$nsAvailable " +
        "nsCreated=${ns != null} nsEnabled=$nsEnabledActual",
    )

    val chunkSamples = (SAMPLE_RATE * READ_CHUNK_MS / 1000).toInt()
    val readBuffer = ShortArray(chunkSamples)
    var pcm = ByteArrayOutputStream()
    var phase = "pre_speech" // pre_speech -> in_speech -> (handed off, cycle resets)
    var speechStartedAt = 0L
    var lastLoudAt = 0L
    var cycleStartedAt = System.currentTimeMillis()
    val preRoll = ArrayDeque<ByteArray>()
    var lastReaderDuringSttLogAt = 0L
    // ROUND_AMBIENT_NOISE_1 — seeded at RMS_THRESHOLD (a quiet room's expected floor), only ever
    // updated from pre_speech chunks (never from a chunk already inside a captured utterance —
    // that would let a person's own ongoing speech drag the floor up mid-sentence).
    var noiseFloor = RMS_THRESHOLD

    fun resetCycle() {
      phase = "pre_speech"
      pcm = ByteArrayOutputStream()
      preRoll.clear()
      cycleStartedAt = System.currentTimeMillis()
    }

    try {
      record.startRecording()
      val initialCaptureMetadata = captureMetadata(record)
      log("WAKE_AUDIO_ACQUIRE", "source=VOICE_RECOGNITION requestedRateHz=$SAMPLE_RATE " +
        "recordRateHz=${initialCaptureMetadata.sampleRateHz} channels=${initialCaptureMetadata.channels} " +
        "encoding=PCM_16BIT routedInputType=${initialCaptureMetadata.routedInputType ?: -1}")
      // ROUND_DEAF_WINDOW_DIAG_1 (2026-09-23, product-owner-directed) — explicit, unambiguous
      // marker for "the wake loop is listening again", paired with WAKE_LISTEN_WINDOW_CLOSED in
      // the finally block below. The loop's own thread fully stops (and its AudioRecord releases)
      // every time JS takes the mic for command handling after a trigger, then a brand-new thread
      // starts when JS hands it back — this pair brackets exactly that "not listening" span, so a
      // missed utterance can be checked against it directly instead of inferring from unrelated tags.
      log("WAKE_LISTEN_WINDOW_OPENED", "reason=loop_started")
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
            submitUtterance(pcm.toByteArray(), captureMetadata(record))
          }
          resetCycle()
          continue
        }
        if (phase == "in_speech" && (now - lastLoudAt) > SILENCE_TIMEOUT_MS && (now - speechStartedAt) > MIN_SPEECH_MS) {
          log("WAKE_VAD_END", "reason=vad_silence bytes=${pcm.size()}")
          submitUtterance(pcm.toByteArray(), captureMetadata(record))
          resetCycle()
          continue
        }

        val read = record.read(readBuffer, 0, chunkSamples)
        if (read <= 0) continue
        if (!audioInputActive) {
          audioInputActive = true
          log("WAKE_INPUT_ACTIVE", "state=reading")
        }

        var sumSquares = 0.0
        for (i in 0 until read) {
          val s = readBuffer[i].toDouble()
          sumSquares += s * s
        }
        val rms = sqrt(sumSquares / read)
        val bytes = shortsToBytes(readBuffer, read)
        val readerNow = SystemClock.elapsedRealtime()
        if (pendingStt.get() > 0 && readerNow - lastReaderDuringSttLogAt >= 1000L) {
          lastReaderDuringSttLogAt = readerNow
          log("WAKE_AUDIO_READ_DURING_STT", "pendingRequests=${pendingStt.get()} samplesRead=$read rms=${"%.1f".format(rms)}")
        }

        if (phase == "in_speech") {
          pcm.write(bytes)
        } else {
          preRoll.addLast(bytes)
          if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
          // Ambient-level tracking — pre_speech chunks only (see noiseFloor's own comment above).
          noiseFloor = ((1 - NOISE_FLOOR_EMA_ALPHA) * noiseFloor + NOISE_FLOOR_EMA_ALPHA * rms)
            .coerceIn(RMS_THRESHOLD, NOISE_FLOOR_MAX)
        }

        val backoffMargin = NOISE_FLOOR_MARGIN + consecutiveNonMatches * NON_MATCH_BACKOFF_STEP
        val effectiveThreshold = maxOf(RMS_THRESHOLD, noiseFloor * backoffMargin)
        val activeSpeechThreshold = maxOf(ACTIVE_SPEECH_MIN_RMS, noiseFloor * ACTIVE_SPEECH_NOISE_MARGIN)
        // Hysteresis: keep quiet syllables / short pauses in the current utterance without
        // weakening the stricter ambient-resistant threshold that starts a new one.
        val activityThreshold = if (phase == "in_speech") activeSpeechThreshold else effectiveThreshold
        if (rms > activityThreshold) {
          lastLoudAt = now
          if (phase == "pre_speech") {
            phase = "in_speech"
            speechStartedAt = now
            log("WAKE_VAD_BEGIN", "rms=${"%.1f".format(rms)} floor=${"%.1f".format(noiseFloor)} threshold=${"%.1f".format(effectiveThreshold)} backoff=$consecutiveNonMatches")
            for (chunk in preRoll) pcm.write(chunk)
            preRoll.clear()
          }
        }
      }
    } catch (e: Exception) {
      log("WAKE_NATIVE_ERROR", "reason=loop error=\"${e.javaClass.simpleName}: ${e.message}\"")
    } finally {
      try { aec?.release() } catch (_: Exception) {}
      try { ns?.release() } catch (_: Exception) {}
      try { record.stop() } catch (_: Exception) {}
      try { record.release() } catch (_: Exception) {}
      audioInputActive = false
      log("WAKE_INPUT_ACTIVE", "state=stopped")
      log("WAKE_AUDIO_RELEASE", "")
      // ROUND_DEAF_WINDOW_DIAG_1 — see WAKE_LISTEN_WINDOW_OPENED's comment above; this closes the
      // bracket. Everything between this line and the next WAKE_LISTEN_WINDOW_OPENED is time the
      // wake loop is NOT capturing at all, regardless of the reason (command dispatch, TTS, error
      // recovery, etc.) — the reason itself is already visible from the surrounding MIC_OWNER/
      // WAKE_NATIVE_STOP/WAKE_NATIVE_START lines already logged elsewhere.
      log("WAKE_LISTEN_WINDOW_CLOSED", "reason=loop_stopped")
    }
  }

  // Runs synchronously on the loop thread — the STT round trip is a deliberate pause in scanning
  // (same trade-off already accepted by the legacy SpeechRecognizer burst loop), not a new one.
  private fun captureMetadata(record: AudioRecord): CaptureMetadata = CaptureMetadata(
    sampleRateHz = try { record.sampleRate } catch (_: Exception) { SAMPLE_RATE },
    channels = try { record.channelCount } catch (_: Exception) { 1 },
    routedInputType = try { record.routedDevice?.type } catch (_: Exception) { null },
  )

  // Keep the AudioRecord reader draining the microphone while cloud STT runs. Previously each
  // blocking HTTP request made this loop deaf for its full network latency (observed up to 6.7s).
  // At most two candidates are processed concurrently; excess ambient bursts are discarded.
  private fun submitUtterance(pcmBytes: ByteArray, capture: CaptureMetadata) {
    if (pcmBytes.isEmpty() || !running) return
    if (pendingStt.incrementAndGet() > 2) {
      pendingStt.decrementAndGet()
      log("WAKE_STT_CANDIDATE_DROPPED", "reason=recognition_busy")
      return
    }
    val submittedAt = SystemClock.elapsedRealtime()
    val epoch = wakeEpoch.get()
    try {
      val executor = sttExecutor
      if (executor == null) {
        pendingStt.decrementAndGet()
        log("WAKE_STT_CANDIDATE_DROPPED", "reason=engine_stopping")
        return
      }
      executor.execute {
        try {
          if (!running || wakeEpoch.get() != epoch) return@execute
          val ageMs = SystemClock.elapsedRealtime() - submittedAt
          if (ageMs > 3500L) {
            log("WAKE_STT_CANDIDATE_DROPPED", "reason=stale ageMs=$ageMs")
            return@execute
          }
          handleUtterance(pcmBytes, capture, epoch, submittedAt)
        } finally { pendingStt.decrementAndGet() }
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      pendingStt.decrementAndGet()
      log("WAKE_STT_CANDIDATE_DROPPED", "reason=recognition_busy")
    }
  }

  private fun handleUtterance(pcmBytes: ByteArray, capture: CaptureMetadata, epoch: Long, submittedAt: Long) {
    if (pcmBytes.isEmpty() || !running) return
    val wakeName = currentWakeName(context)
    val durationMs = if (capture.sampleRateHz > 0 && capture.channels > 0)
      pcmBytes.size * 1000L / (capture.sampleRateHz * capture.channels * 2L) else -1L
    var peak = 0
    var squareSum = 0.0
    var sampleCount = 0
    var offset = 0
    while (offset + 1 < pcmBytes.size) {
      val sample = ((pcmBytes[offset + 1].toInt() shl 8) or (pcmBytes[offset].toInt() and 0xff)).toShort().toInt()
      peak = maxOf(peak, kotlin.math.abs(sample))
      squareSum += sample.toDouble() * sample.toDouble()
      sampleCount++
      offset += 2
    }
    val rms = if (sampleCount > 0) sqrt(squareSum / sampleCount) else 0.0
    log("WAKE_CAPTURE_FORMAT", "source=VOICE_RECOGNITION inputDeviceType=${capture.routedInputType ?: -1} " +
      "sampleRateHz=${capture.sampleRateHz} channels=${capture.channels} encoding=PCM_S16LE " +
      "container=none pcmBytes=${pcmBytes.size} durationMs=$durationMs samples=$sampleCount " +
      "rms=${"%.1f".format(rms)} peak=$peak")
    log("WAKE_STT_REQUEST", "pcmBytes=${pcmBytes.size} durationMs=$durationMs")
    val retainDiagnostic = claimDiagnosticCapture()
    // DEV_STT_DEEPGRAM_WAKE_1 — dev routing; postToGroq(pcmBytes) is the production call this
    // reverts to (see companion object comment above).
    val transcript = postToDeepgram(pcmBytes, capture, retainDiagnostic)
    val resultAgeMs = SystemClock.elapsedRealtime() - submittedAt
    if (resultAgeMs > MAX_WAKE_RESULT_AGE_MS) {
      log("WAKE_STT_RESULT_EXPIRED", "ageMs=$resultAgeMs limitMs=$MAX_WAKE_RESULT_AGE_MS")
      return
    }
    if (!running || wakeEpoch.get() != epoch) return
    if (retainDiagnostic) {
      log("WAKE_DIAGNOSTIC_EXECUTOR_SUPPRESSED", "audioRetained=true")
      return
    }
    if (transcript == null) {
      log("WAKE_STT_RESULT", "ok=false")
      // A provider/network failure says nothing about whether the captured sound was ambient noise.
      // Do not make the next real owner's utterance harder to capture because STT was unavailable.
      return
    }
    log("WAKE_STT_RESULT", "ok=true chars=${transcript.length}")
    if (transcript.isBlank()) {
      // An empty transcript is also inconclusive: device evidence shows a short owner command can
      // reach VAD yet return HTTP 200 with no words. Raising the adaptive threshold here caused a
      // quiet owner's next attempt to be rejected before STT. Only a non-empty, unrelated
      // transcript is evidence for the ambient-source backoff below.
      log("WAKE_STT_EMPTY", "backoffUnchanged=$consecutiveNonMatches bytes=${pcmBytes.size}")
      return
    }
    val (matched, exact, tail) = matchWake(transcript, wakeName)
    if (!matched) {
      log("WAKE_NO_MATCH", "transcriptChars=${transcript.length}")
      synchronized(this) { consecutiveNonMatches = (consecutiveNonMatches + 1).coerceAtMost(NON_MATCH_BACKOFF_CAP) }
      return
    }
    synchronized(this) {
      if (!running || wakeEpoch.get() != epoch) return
      consecutiveNonMatches = 0
      wakeEpoch.incrementAndGet()
      log(if (exact) "WAKE_MATCH_EXACT" else "WAKE_MATCH_FUZZY", "wakeNameConfigured=true tailChars=${tail.length}")
      log("WAKE_TRIGGER", "source=native_cloud tailChars=${tail.length}")
      onDetected(tail)
    }
  }

  // DEV_STT_DEEPGRAM_WAKE_1 — Deepgram's pre-recorded /listen endpoint takes the raw WAV bytes as
  // the request body directly (no multipart, unlike postToGroq below). Never logs the API key.
  // Returns null on any failure (network, non-2xx, empty body, no key configured) — the caller
  // treats that as "no match this cycle", same as a VAD miss.
  private fun postToDeepgram(pcmBytes: ByteArray, capture: CaptureMetadata, retainDiagnostic: Boolean): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val apiKey = prefs.getString(KEY_WAKE_DEEPGRAM_API_KEY, "") ?: ""
    val language = currentDeepgramLanguage(context)
    log("WAKE_STT_PROVIDER_ATTEMPT", "provider=deepgram model=$DEEPGRAM_MODEL language=$language " +
      "contentType=audio/wav encoding=container_declared sampleRate=container_declared")
    val wav = buildWav(pcmBytes)
    val declaredPcmBytes = if (wav.size >= 44) ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40) else -1
    val payloadMatchesCapture = wav.size == pcmBytes.size + 44 && declaredPcmBytes == pcmBytes.size &&
      wav.copyOfRange(44, wav.size).contentEquals(pcmBytes)
    val wavRate = if (wav.size >= 28) ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24) else -1
    val wavChannels = if (wav.size >= 24) ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(22).toInt() else -1
    val wavBits = if (wav.size >= 36) ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(34).toInt() else -1
    val sentDurationMs = if (wavRate > 0 && wavChannels > 0 && wavBits > 0)
      declaredPcmBytes * 1000L / (wavRate * wavChannels * (wavBits / 8L)) else -1L
    log("WAKE_STT_PAYLOAD", "container=RIFF_WAVE encoding=PCM_S16LE sampleRateHz=$wavRate channels=$wavChannels " +
      "bitsPerSample=$wavBits pcmBytes=${pcmBytes.size} requestBodyBytes=${wav.size} durationMs=$sentDurationMs " +
      "captureFormatMatch=${wavRate == capture.sampleRateHz && wavChannels == capture.channels} " +
      "pcmPayloadMatchesCapture=$payloadMatchesCapture")
    if (retainDiagnostic) retainDiagnosticWav(wav, wavRate, wavChannels, wavBits, declaredPcmBytes)
    if (apiKey.isBlank()) { log("WAKE_STT_PROVIDER_RESULT", "provider=deepgram status=error reason=no_api_key"); return null }
    val body = wav.toRequestBody("audio/wav".toMediaType())
    val url = "$DEEPGRAM_BASE_URL?model=$DEEPGRAM_MODEL&language=$language"
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $apiKey").post(body).build()
    val call = client.newCall(request)
    activeCalls.add(call)
    return try {
      call.execute().use { resp ->
        log("WAKE_STT_PROVIDER_RESULT", "provider=deepgram http_status=${resp.code}")
        if (!resp.isSuccessful) {
          log("WAKE_NATIVE_ERROR", "reason=stt_http code=${resp.code}")
          return null
        }
        val json = resp.body?.string() ?: return null
        val root = JSONObject(json)
        val metadata = root.optJSONObject("metadata")
        val results = root.optJSONObject("results")
        val channels = results?.optJSONArray("channels")
        val alternatives = channels?.optJSONObject(0)?.optJSONArray("alternatives")
        val alt = alternatives?.optJSONObject(0)
        val transcript = (alt?.optString("transcript", "") ?: "").trim()
        val words = alt?.optJSONArray("words")?.length() ?: 0
        val confidence = alt?.optDouble("confidence", -1.0) ?: -1.0
        val serverDuration = metadata?.optDouble("duration", -1.0) ?: -1.0
        // Never log the response body, transcript, authorization header, or audio. Keep only
        // non-content fields needed to separate decode/configuration failures from blank speech.
        log("WAKE_STT_RESPONSE_META", "http=${resp.code} requestId=${metadata?.optString("request_id", "") ?: ""} " +
          "serverDurationSec=${"%.3f".format(serverDuration)} channels=${channels?.length() ?: 0} " +
          "alternatives=${alternatives?.length() ?: 0} confidence=${"%.3f".format(confidence)} " +
          "wordCount=$words transcriptChars=${transcript.length}")
        transcript
      }
    } catch (e: Exception) {
      if (!running) return null
      log("WAKE_NATIVE_ERROR", "reason=deepgram_stt_exception error=\"${e.javaClass.simpleName}: ${e.message}\"")
      null
    } finally {
      activeCalls.remove(call)
    }
  }

  /** Claims consent for one utterance only; stale consent is discarded before any audio is kept. */
  private fun claimDiagnosticCapture(): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_RETAIN_DIAGNOSTIC, false)) return false
    val expiry = prefs.getLong(KEY_DIAGNOSTIC_EXPIRY, 0L)
    val armed = expiry >= System.currentTimeMillis()
    prefs.edit().putBoolean(KEY_RETAIN_DIAGNOSTIC, false).putLong(KEY_DIAGNOSTIC_EXPIRY, 0L).commit()
    return armed
  }

  /** Stores the exact WAV request body, never a parallel microphone recording. */
  private fun retainDiagnosticWav(wav: ByteArray, rate: Int, channels: Int, bits: Int, pcmBytes: Int) {
    try {
      val file = File(context.cacheDir, DIAGNOSTIC_WAV_NAME)
      FileOutputStream(file).use { it.write(wav) }
      diagnosticExpiryHandler.removeCallbacksAndMessages(null)
      diagnosticExpiryHandler.postDelayed({ file.delete() }, DIAGNOSTIC_WAV_TTL_MS)
      val valid = wav.size == pcmBytes + 44 && wav.size >= 44 &&
        String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE" &&
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == pcmBytes
      val durationMs = if (rate > 0 && channels > 0 && bits > 0)
        pcmBytes * 1000L / (rate * channels * (bits / 8L)) else -1L
      log("WAKE_DIAGNOSTIC_RETAINED", "wavValid=$valid exactSttPayload=true bytes=${wav.size} " +
        "sampleRateHz=$rate channels=$channels bits=$bits durationMs=$durationMs ttlMs=$DIAGNOSTIC_WAV_TTL_MS")
    } catch (e: Exception) {
      log("WAKE_DIAGNOSTIC_RETAIN_FAILED", "reason=${e.javaClass.simpleName}")
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
    activeCalls.add(call)
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
      activeCalls.remove(call)
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
