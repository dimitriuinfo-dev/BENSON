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
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

private const val TAG = "BensonAudioCapture"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

// Simple energy-based VAD (no ML, no wake-word model) — same tuning philosophy already proven
// for the SpeechRecognizer-based capture (MIN_LENGTH/COMPLETE_SILENCE in BensonForegroundService.kt
// and voiceAgent.ts), reapplied here since this replaces that engine, not the timing rationale.
// Confirmed live 2026-07-30 (WAV analysis of a real failed capture): a full sentence ("Benson,
// vreau s-o suni pe mama pe whatsapp") was truncated to ~1.3s of loud audio + the recording ended
// right at the old 1600ms silence timeout — the speaker's natural mid-sentence volume dip was
// mistaken for "done talking," cutting the command in half before Whisper ever saw the rest of
// it. Loosened both knobs: more silence tolerance before ending, and a lower bar for what counts
// as "still talking" so a quieter second half of a sentence doesn't get missed.
// Tuned from real on-device peakRms measurements (2026-08-02/2026-08-23, BENSON_AUDIO logs), not
// guessed. 150.0 was a deliberate temporary test-only value (see prior comment history) that was
// never reverted — confirmed live 2026-08-23 as the actual cause of "wake word doesn't work":
// every wake-scan cycle hit reason=max_duration (never vad_silence) because normal room ambience
// kept re-triggering "still loud" at such a low bar, so genuine silence after "Benson" never had a
// chance to register and end the capture. Raising it to 1500.0 fixed that, but overshot: confirmed
// live minutes later (same day) as the cause of "Benson nu aude" — three consecutive conversation-
// mode captures in a row hit reason=max_duration with bytes=0 (phase NEVER left pre_speech, so
// nothing was ever written to pcm) at peakRms 1445.9 / 172.8 / 209.4. 1445.9 is almost certainly
// real speech sitting just under the 1500 gate — the user was talking and BENSON silently dropped
// 15 seconds of it, three times in a row, with the speech never even entering the recording. Real
// ambient in that same room at that same moment was 172–209, well separated from 1445.9 — so 700.0
// sits with ~3x headroom above the actual observed noise floor and ~2x margin below the actual
// observed (quiet) speech peak, both measured in the same live session, not guessed. Loud speech
// measured a different day (6584–13478) still clears this with enormous margin.
private const val RMS_THRESHOLD = 700.0 // was 1500.0 (overshot, silently dropped quiet speech) / 150.0 (undershot) / originally 350.0
private const val MIN_SPEECH_MS = 800L
// E2-3 (2026-09-07, product-owner-directed): fereastra de tăcere după vorbire urcată de la 800ms
// la 1600ms — 800ms tăia comenzile firești cu o pauză scurtă mid-propoziție („Sună-o pe Hannah
// pe WhatsApp"). 1600ms e compromisul: destul cât să tolereze pauza naturală, sub cele 2500ms
// de dinainte de E1. Revert: 800L (E1-3) sau 2500L (pre-E1).
private const val SILENCE_TIMEOUT_MS = 1600L // was 800L (E2-3 2026-09-07) / 2500L (pre-E1) / 1600L original
// User-requested 2026-07-30: with the old 6s pre-speech timeout, "LISTENING" cycled off/on every
// few seconds while waiting for the user to start talking, feeling interrupted instead of
// continuously listening. In conversation mode the app immediately starts a fresh capture the
// instant one ends anyway, so this is really "how long a single visible LISTENING session lasts
// before restarting" — stretched way out so it reads as always-on rather than blinking.
private const val PRE_SPEECH_TIMEOUT_MS = 60_000L
private const val MAX_DURATION_MS = 15000L
// E1-4 (2026-09-07, product-owner-directed): oprire timpurie pe tăcere. Dacă în primele
// EARLY_NO_SPEECH_STOP_MS de captură pragul RMS nu a fost depășit deloc (faza încă "pre_speech"),
// oprește captura — nu mai ține microfonul deschis până la MAX_DURATION_MS pe o captură fără
// vorbire (log de până acum: șapte capturi consecutive bytes=0 reason=max_duration = 105s).
// Revert: EARLY_NO_SPEECH_STOP_MS = MAX_DURATION_MS (dezactivează efectiv oprirea timpurie).
private const val EARLY_NO_SPEECH_STOP_MS = 3000L
private const val READ_CHUNK_MS = 50L

// Pre-roll buffer (product-owner-directed, root cause confirmed in code review 2026-07-31):
// pcm.write() only ever ran while phase=="in_speech", so every chunk before the FIRST one to
// cross RMS_THRESHOLD was silently discarded, never attenuated — a quiet speech onset (a soft
// first syllable ramping up in volume) was never captured at all. "Sună-o pe Hannah pe WhatsApp"
// starting with a quiet "Su" reproduces exactly the observed mangling pattern ("...nă-o pe Hannah"
// reaching Whisper). Keeps the last PRE_ROLL_CHUNKS chunks (10 x 50ms = 500ms) buffered during
// pre_speech and prepends them once real speech is detected, instead of lowering RMS_THRESHOLD
// globally (which would also make mid-utterance silence detection less reliable).
// 11, not 10: the triggering (loud) chunk itself occupies one of the buffered slots (it's pushed
// to the buffer before the RMS check in the same loop iteration decides to flip phase), so 11
// total = 10 chunks of real pre-trigger context (500ms) + the trigger chunk itself.
private const val PRE_ROLL_CHUNKS = 11

// Mangling-hypothesis test (product-owner-directed, temporary, isolated single-variable test):
// NoiseSuppressor is a candidate cause of the observed STT garbling (spectral distortion on some
// OEM audio HAL implementations) — off for this test. AEC stays on/untouched regardless, so only
// one variable changes at a time. If this reintroduces the self-echo bug NS was originally added
// for (confirmed live 2026-07-30 — BENSON's own TTS mis-heard as a user command), app/index.tsx's
// own content-based looksLikeSelfEcho() guard (independent of audio-level echo cancellation)
// remains the safety net while NS is off.
//
// Moved from a compile-time constant to a runtime SharedPreferences flag (product-owner-directed
// 2026-08-01) — same key namespace/pattern as wake_word_enabled/audio_diagnostics_enabled
// elsewhere in this app, so it can be flipped without a rebuild. Default false preserves today's
// value exactly (see isNoiseSuppressorEnabled() below).
private const val PREFS_NAME = "benson_watchdog_prefs"

class BensonAudioCaptureModule : Module() {
  private var captureThread: Thread? = null
  private val stopRequested = AtomicBoolean(false)

  // Audio focus (2026-08-28) — while BENSON is capturing, ask the system to duck every other
  // app's playback (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) so e.g. Waze's turn-by-turn voice drops
  // in volume instead of being fed straight back into the mic. Released the instant capture ends
  // (finish()), so the other app comes back to full volume with no lingering effect.
  private var audioManager: AudioManager? = null
  private var focusRequest: AudioFocusRequest? = null // API 26+ only; null on 24-25 (legacy path)

  private fun isNoiseSuppressorEnabled(context: Context): Boolean {
    return try {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("noise_suppressor_enabled", false)
    } catch (_: Exception) { false }
  }

  @Suppress("DEPRECATION")
  private fun requestAudioFocus(context: Context) {
    try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      audioManager = am
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=requested")
      val result: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(attrs)
          .setWillPauseWhenDucked(false)
          .setOnAudioFocusChangeListener { }
          .build()
        focusRequest = req
        am.requestAudioFocus(req)
      } else {
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      }
      Log.i(
        "BENSON_AUDIO",
        "AUDIO_FOCUS state=${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "denied"}",
      )
    } catch (e: Exception) {
      Log.e(TAG, "requestAudioFocus failed", e)
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=denied")
    }
  }

  @Suppress("DEPRECATION")
  private fun abandonAudioFocus() {
    val am = audioManager ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
      } else {
        am.abandonAudioFocus(null)
      }
      Log.i("BENSON_AUDIO", "AUDIO_FOCUS state=released")
    } catch (e: Exception) {
      Log.e(TAG, "abandonAudioFocus failed", e)
    } finally {
      focusRequest = null
      audioManager = null
    }
  }

  override fun definition() = ModuleDefinition {
    Name("BensonAudioCapture")

    Events("onCaptureEnd", "onVolumeChanged")

    AsyncFunction("startCapture") { promise: expo.modules.kotlin.Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject("NO_CONTEXT", "No react context available", null)
        return@AsyncFunction
      }
      if (captureThread?.isAlive == true) {
        promise.resolve(null)
        return@AsyncFunction
      }
      stopRequested.set(false)
      captureThread = thread(start = true, name = "BensonAudioCapture") {
        runCapture(context)
      }
      promise.resolve(null)
    }

    AsyncFunction("stopCapture") { promise: expo.modules.kotlin.Promise ->
      stopRequested.set(true)
      promise.resolve(null)
    }
  }

  private fun runCapture(context: Context) {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (minBufferSize <= 0) {
      Log.e(TAG, "getMinBufferSize failed: $minBufferSize")
      sendEnd(null, "error")
      return
    }
    val bufferSize = minBufferSize * 2
    val chunkSamples = (SAMPLE_RATE * READ_CHUNK_MS / 1000).toInt()
    val readBuffer = ShortArray(chunkSamples)

    val recorder = try {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize,
      )
    } catch (e: Exception) {
      Log.e(TAG, "AudioRecord construction failed", e)
      sendEnd(null, "error")
      return
    }

    // Mic-contention instrumentation (product-owner-directed) — logs the raw AudioRecord state
    // right after construction, under BENSON_AUDIO so it can be read alongside the wake-loop's
    // own WAKE_MIC lines to check whether this capture's AudioRecord initialized cleanly or was
    // starved/degraded by the passive loop's SpeechRecognizer still holding the mic.
    Log.i("BENSON_AUDIO", "CAPTURE_MIC_STATE state=${recorder.state} ts=${System.currentTimeMillis()}")
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord not initialized, state=${recorder.state}")
      recorder.release()
      sendEnd(null, "error")
      return
    }

    // Self-echo fix (confirmed live 2026-07-30: a captured transcript was BENSON's own TTS
    // greeting, "Benson online, Master, ce fac pentru tine", mis-heard as a user command) —
    // VOICE_RECOGNITION doesn't get the device's acoustic echo cancellation pipeline that
    // VOICE_COMMUNICATION-based capture (e.g. Android's own SpeechRecognizer, calls) gets for
    // free. Attach AEC/NS explicitly to this session instead of switching audio source (which
    // would also change gain/routing behavior) — targeted fix, same source.
    var aec: AcousticEchoCanceler? = null
    var ns: NoiseSuppressor? = null
    try {
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
      }
      if (isNoiseSuppressorEnabled(context) && NoiseSuppressor.isAvailable()) {
        ns = NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
      }
    } catch (e: Exception) {
      Log.e(TAG, "AEC/NS setup failed (continuing without it)", e)
    }
    Log.i(
      "BENSON_AUDIO",
      "EFFECTS source=voice_recognition sessionId=${recorder.audioSessionId} aec=${if (aec != null) "on" else "off"} ns=${if (ns != null) "on" else "off"}",
    )

    val pcm = java.io.ByteArrayOutputStream()
    var phase = "pre_speech" // pre_speech -> in_speech -> done
    var speechStartedAt = 0L
    var lastLoudAt = 0L
    // Diagnostic only (product-owner-directed 2026-08-02) — the highest RMS actually seen this
    // capture, logged even when RMS_THRESHOLD is never crossed at all, so a "bytes=0" session
    // yields a real number to tune the gate against instead of guessing at a new threshold blind.
    var peakRms = 0.0
    var lastVolumeEmitAt = 0L
    val captureStartedAt = System.currentTimeMillis()
    // Pre-roll ring buffer — see PRE_ROLL_CHUNKS doc comment above.
    val preRollBuffer = ArrayDeque<ByteArray>()

    // Duck other apps' audio for the duration of the capture (released in finish()).
    requestAudioFocus(context)

    try {
      recorder.startRecording()
      Log.i("BENSON_AUDIO", "CAPTURE_MIC state=ACQUIRE ts=${System.currentTimeMillis()}")
      Log.i(TAG, "Capture started")

      while (!stopRequested.get()) {
        val now = System.currentTimeMillis()
        val elapsed = now - captureStartedAt

        if (phase == "pre_speech" && elapsed > PRE_SPEECH_TIMEOUT_MS) {
          Log.i(TAG, "Pre-speech timeout, no speech detected")
          finish(recorder, aec, ns, null, "no_speech", 0, peakRms)
          return
        }
        // E1-4 — RMS_THRESHOLD never crossed within the first EARLY_NO_SPEECH_STOP_MS: end now
        // instead of holding the mic open for the full MAX_DURATION_MS on silence.
        if (phase == "pre_speech" && elapsed > EARLY_NO_SPEECH_STOP_MS) {
          Log.i(TAG, "Early no-speech stop at ${elapsed}ms (RMS never crossed threshold, peakRms=$peakRms)")
          finish(recorder, aec, ns, null, "no_speech", 0, peakRms)
          return
        }
        if (elapsed > MAX_DURATION_MS) {
          Log.i(TAG, "Max duration reached")
          finish(recorder, aec, ns, writeWav(context, pcm.toByteArray()), "max_duration", pcm.size(), peakRms)
          return
        }
        if (phase == "in_speech" && (now - lastLoudAt) > SILENCE_TIMEOUT_MS && (now - speechStartedAt) > MIN_SPEECH_MS) {
          Log.i(TAG, "Silence after speech, finalizing")
          finish(recorder, aec, ns, writeWav(context, pcm.toByteArray()), "vad_silence", pcm.size(), peakRms)
          return
        }

        val read = recorder.read(readBuffer, 0, chunkSamples)
        if (read <= 0) continue

        var sumSquares = 0.0
        for (i in 0 until read) {
          val s = readBuffer[i].toDouble()
          sumSquares += s * s
        }
        val rms = sqrt(sumSquares / read)
        if (rms > peakRms) peakRms = rms

        // Real mic level for the UI's listening indicator — user-requested 2026-07-30: the old
        // waveform was Math.random()-animated, not tied to actual audio at all. Throttled to
        // ~10/sec (plenty for a smooth visual, no point flooding the JS bridge every 50ms chunk).
        // Normalized against 6000 — the RMS range real loud speech landed in during today's WAV
        // analysis (see AUDIO_DIAGNOSIS_REPORT.md-adjacent testing) — not a calibrated absolute
        // scale, just enough to make the bars swing meaningfully instead of pinned at 0 or 1.
        if (now - lastVolumeEmitAt >= 100L) {
          lastVolumeEmitAt = now
          val normalized = (rms / 6000.0).coerceIn(0.0, 1.0)
          sendEvent("onVolumeChanged", mapOf("level" to normalized))
        }

        val bytes = shortsToBytes(readBuffer, read)
        if (phase == "in_speech") {
          pcm.write(bytes)
        } else {
          // pre_speech — not written to pcm yet, only buffered, so a quiet onset ramping up in
          // volume isn't lost outright (see PRE_ROLL_CHUNKS doc comment above).
          preRollBuffer.addLast(bytes)
          if (preRollBuffer.size > PRE_ROLL_CHUNKS) preRollBuffer.removeFirst()
        }

        if (rms > RMS_THRESHOLD) {
          lastLoudAt = now
          if (phase == "pre_speech") {
            phase = "in_speech"
            speechStartedAt = now
            // Flush the buffered pre-roll in chronological order — `bytes` (this triggering
            // chunk) was just pushed above, so it's already the last element; no separate write.
            var prependedBytes = 0
            for (chunk in preRollBuffer) {
              pcm.write(chunk)
              prependedBytes += chunk.size
            }
            preRollBuffer.clear()
            val prependedMs = (prependedBytes / 2.0 / SAMPLE_RATE * 1000).toInt() // 2 bytes/sample
            Log.i("BENSON_AUDIO", "PREROLL prepended_bytes=$prependedBytes prepended_ms=$prependedMs")
          }
        }
      }

      // Explicit stopCapture() call.
      if (phase == "in_speech" && pcm.size() > 0) {
        finish(recorder, aec, ns, writeWav(context, pcm.toByteArray()), "stopped", pcm.size(), peakRms)
      } else {
        finish(recorder, aec, ns, null, "stopped", 0, peakRms)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Capture loop error", e)
      finish(recorder, aec, ns, null, "error", 0, peakRms)
    }
  }

  private fun finish(recorder: AudioRecord, aec: AcousticEchoCanceler?, ns: NoiseSuppressor?, filePath: String?, reason: String, pcmBytes: Int, peakRms: Double) {
    abandonAudioFocus()
    try { aec?.release() } catch (_: Exception) {}
    try { ns?.release() } catch (_: Exception) {}
    try { recorder.stop() } catch (_: Exception) {}
    try { recorder.release() } catch (_: Exception) {}
    Log.i("BENSON_AUDIO", "CAPTURE_MIC state=RELEASE ts=${System.currentTimeMillis()}")
    // Timing instrumentation (product-owner-directed, prerequisite for any threading/model-size
    // decision) — real duration of the captured audio, paired with TRANSCRIBE_START/END in
    // localWhisperEngine.ts to compute the actual Capture-ended -> rawText gap.
    val durationSec = pcmBytes / 2.0 / SAMPLE_RATE // 2 bytes/sample
    Log.i("BENSON_AUDIO", "CAPTURE_ENDED ts=${System.currentTimeMillis()} bytes=$pcmBytes durationSec=$durationSec reason=$reason peakRms=$peakRms threshold=$RMS_THRESHOLD")
    sendEvent("onVolumeChanged", mapOf("level" to 0.0))
    sendEnd(filePath, reason)
  }

  private fun sendEnd(filePath: String?, reason: String) {
    Log.i(TAG, "Capture ended: reason=$reason path=$filePath")
    sendEvent("onCaptureEnd", mapOf("filePath" to (filePath ?: ""), "reason" to reason))
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
    val path = try {
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
    if (path != null) maybeDumpForDiagnostics(context, pcmData)
    return path
  }

  // Diagnostic-only copy of the exact same PCM this capture already produces for whisper —
  // written to app-external storage (adb-pullable without root, unlike cacheDir above) so a
  // human can actually listen to what the recognizer heard. Gated on the SAME toggle already
  // exposed in the Debug Panel (AudioDiagnosticsPanel, app/debug.tsx) for BENSON_AUDIO logging —
  // no new flag, no new UI. Reads the shared prefs directly (no Gradle dependency on
  // benson-foreground-service, where AudioDiag.kt's flag actually lives) — same cross-module
  // idiom already used elsewhere in this app (see BensonAccessibilityService.kt's Guardian
  // heartbeat, which reads benson-foreground-service's own prefs the same way). Never touches
  // the cacheDir file transcribeLocally() actually uses — purely additive, on failure just logs
  // and does not affect capture/transcription.
  private fun maybeDumpForDiagnostics(context: Context, pcmData: ByteArray) {
    try {
      val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean("audio_diagnostics_enabled", true)
      if (!enabled) return
      val dir = context.getExternalFilesDir(null) ?: return
      val dumpFile = File(dir, "benson_audio_dump_${System.currentTimeMillis()}.wav")
      FileOutputStream(dumpFile).use { out ->
        out.write(buildWavHeader(pcmData.size))
        out.write(pcmData)
      }
      Log.i("BENSON_AUDIO", "AUDIO_DUMP_WRITTEN path=${dumpFile.absolutePath}")
    } catch (e: Exception) {
      Log.e("BENSON_AUDIO", "AUDIO_DUMP_FAILED error=${e.message}")
    }
  }

  private fun buildWavHeader(dataSize: Int): ByteArray {
    val byteRate = SAMPLE_RATE * 2 // mono * 16-bit
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
    writeStr(0, "RIFF")
    writeInt(4, 36 + dataSize)
    writeStr(8, "WAVE")
    writeStr(12, "fmt ")
    writeInt(16, 16) // fmt chunk size
    writeShort(20, 1) // PCM
    writeShort(22, 1) // mono
    writeInt(24, SAMPLE_RATE)
    writeInt(28, byteRate)
    writeShort(32, 2) // block align
    writeShort(34, 16) // bits per sample
    writeStr(36, "data")
    writeInt(40, dataSize)
    return header
  }
}
