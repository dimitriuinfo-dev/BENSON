package expo.modules.audiocapture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "BensonAudioCapture"
// NATIVE_CMD_1 (2026-10-02) — the VAD/pre-roll/focus constants and logic that used to live here
// moved verbatim into CaptureEngine.kt (same package, same tuning, same device-measured
// rationale — see that file) so BensonForegroundService can drive the identical proven capture.
// This class is now a thin Expo-Module wrapper around it.
private const val PREFS_NAME = "benson_watchdog_prefs"
private const val DIAGNOSTIC_WAV_NAME = "benson_diagnostic_once.wav"
private const val DIAGNOSTIC_WAV_TTL_MS = 30L * 60L * 1000L

class BensonAudioCaptureModule : Module() {
  private var captureThread: Thread? = null
  private val stopRequested = AtomicBoolean(false)
  private val diagnosticExpiryHandler = Handler(Looper.getMainLooper())
  private var diagnosticExpiryRunnable: Runnable? = null

  override fun definition() = ModuleDefinition {
    Name("BensonAudioCapture")

    OnCreate {
      // A retained diagnostic never survives a fresh app process.
      appContext.reactContext?.let { context ->
        File(context.cacheDir, DIAGNOSTIC_WAV_NAME).delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
          .putBoolean("retain_next_diagnostic_capture", false)
          .putLong("diagnostic_capture_expiry_at", 0L).apply()
      }
    }

    Events("onCaptureEnd", "onVolumeChanged")

    AsyncFunction("startCapture") { promise: expo.modules.kotlin.Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject("NO_CONTEXT", "No react context available", null)
        return@AsyncFunction
      }
      if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("call_audio_active", false)) {
        Log.i("BENSON_AUDIO", "CAPTURE_REJECTED reason=call_audio_active")
        promise.resolve(null)
        return@AsyncFunction
      }
      if (captureThread?.isAlive == true) {
        promise.resolve(null)
        return@AsyncFunction
      }
      stopRequested.set(false)
      // NATIVE_CMD_1 — delegates to the shared CaptureEngine (modules/benson-audio-capture's own
      // package); diagnostic retention stays here since it's a debug-UI concern specific to this
      // JS-driven caller, not something BensonForegroundService's post-ack capture needs.
      captureThread = thread(start = true, name = "BensonAudioCapture") {
        CaptureEngine.capture(
          context,
          onVolume = { level -> sendEvent("onVolumeChanged", mapOf("level" to level)) },
          onEnd = { filePath, reason, _, _ -> sendEnd(retainDiagnosticIfArmed(context, filePath), reason) },
          isStopRequested = { stopRequested.get() },
        )
      }
      promise.resolve(null)
    }

    AsyncFunction("stopCapture") { promise: expo.modules.kotlin.Promise ->
      stopRequested.set(true)
      promise.resolve(null)
    }

    // One-shot diagnostic retention. This is OFF by default and only armed by the explicit
    // diagnostic UI after the user consents to temporary local storage of one utterance.
    AsyncFunction("armNextDiagnosticCapture") { promise: expo.modules.kotlin.Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject("NO_CONTEXT", "No react context available", null)
        return@AsyncFunction
      }
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("retain_next_diagnostic_capture", true)
        .putLong("diagnostic_capture_expiry_at", System.currentTimeMillis() + 60_000L)
        .apply()
      promise.resolve(true)
    }

    AsyncFunction("deleteDiagnosticCapture") { promise: expo.modules.kotlin.Promise ->
      val context = appContext.reactContext
      val file = context?.let { File(it.cacheDir, DIAGNOSTIC_WAV_NAME) }
      context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
        ?.putBoolean("retain_next_diagnostic_capture", false)
        ?.putLong("diagnostic_capture_expiry_at", 0L)?.apply()
      diagnosticExpiryRunnable?.let { diagnosticExpiryHandler.removeCallbacks(it) }
      diagnosticExpiryRunnable = null
      promise.resolve(file?.delete() ?: false)
    }
  }

  private fun sendEnd(filePath: String?, reason: String) {
    Log.i(TAG, "Capture ended: reason=$reason path=$filePath")
    sendEvent("onCaptureEnd", mapOf("filePath" to (filePath ?: ""), "reason" to reason))
  }

  // NATIVE_CMD_1 — the diagnostic-retention behavior CaptureEngine's writeWav used to do inline;
  // kept here (not in the shared engine) since it's specific to this JS-driven debug-UI caller.
  // Renames CaptureEngine's generic output file to the fixed diagnostic name only when the
  // one-shot "retain_next_diagnostic_capture" flag was armed; otherwise returns filePath unchanged.
  private fun retainDiagnosticIfArmed(context: Context, filePath: String?): String? {
    if (filePath == null) return null
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val diagnostic = prefs.getBoolean("retain_next_diagnostic_capture", false)
    if (!diagnostic) return filePath
    prefs.edit().putBoolean("retain_next_diagnostic_capture", false).commit()
    return try {
      val source = File(filePath)
      val dest = File(context.cacheDir, DIAGNOSTIC_WAV_NAME)
      source.copyTo(dest, overwrite = true)
      source.delete()
      diagnosticExpiryRunnable?.let { diagnosticExpiryHandler.removeCallbacks(it) }
      diagnosticExpiryRunnable = Runnable { dest.delete(); diagnosticExpiryRunnable = null }
      diagnosticExpiryHandler.postDelayed(diagnosticExpiryRunnable!!, DIAGNOSTIC_WAV_TTL_MS)
      val data = dest.readBytes()
      val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val pcmBytes = if (data.size >= 44) header.getInt(40) else -1
      val rate = if (data.size >= 28) header.getInt(24) else -1
      val channels = if (data.size >= 24) header.getShort(22).toInt() else -1
      val bits = if (data.size >= 36) header.getShort(34).toInt() else -1
      val durationMs = if (pcmBytes >= 0 && rate > 0 && channels > 0 && bits > 0)
        pcmBytes * 1000L / (rate * channels * (bits / 8L)) else -1L
      val valid = data.size >= 44 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WAVE" &&
        String(data, 12, 4) == "fmt " && String(data, 36, 4) == "data" && header.getShort(20).toInt() == 1 &&
        pcmBytes + 44 == data.size && header.getInt(4) + 8 == data.size
      Log.i("BENSON_AUDIO", "AUDIO_DIAGNOSTIC_RETAINED wavValid=$valid bytes=${data.size} pcmBytes=$pcmBytes " +
        "sampleRateHz=$rate channels=$channels bits=$bits durationMs=$durationMs exactUploadedWav=true ttlMs=$DIAGNOSTIC_WAV_TTL_MS")
      dest.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "retainDiagnosticIfArmed failed", e)
      filePath
    }
  }
}
