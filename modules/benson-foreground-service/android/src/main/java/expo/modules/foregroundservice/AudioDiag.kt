package expo.modules.foregroundservice

import android.content.Context
import android.util.Log

/**
 * BENSON_AUDIO — authoritative, gated audio-chain diagnostic logging.
 *
 * Honest architectural note (see AUDIO_DIAGNOSIS_REPORT.md): this app has no dedicated
 * wake-word engine and no raw AudioRecord access. Both the passive "Benson" listener
 * (BensonForegroundService's hotword loop) and real command capture (JS, via
 * expo-speech-recognition) go through plain android.speech.SpeechRecognizer, which does not
 * expose PCM frames — only lifecycle callbacks (onReadyForSpeech, onBeginningOfSpeech,
 * onRmsChanged, onEndOfSpeech, onResults/onError). onRmsChanged is therefore the closest
 * legitimate substitute for frame-level audio-activity evidence, not literal PCM stats.
 *
 * Single source of truth for the diagnostics on/off flag, shared across native files in this
 * module (and read by JS via BensonForegroundServiceModule) through one SharedPreferences key —
 * no duplicate flags. Controls logging verbosity only, never functional behavior.
 */
object AudioDiag {
  private const val PREFS_NAME = "benson_watchdog_prefs"
  const val KEY_ENABLED = "audio_diagnostics_enabled"
  private const val TAG = "BENSON_AUDIO"
  private const val RATE_LIMIT_MS = 1000L

  @Volatile private var lastRateLimitedLogAt = 0L
  @Volatile private var sessionCounter = 0

  fun nextSessionId(prefix: String): String {
    sessionCounter += 1
    return "$prefix-$sessionCounter"
  }

  // Default ON for this diagnostic build, per explicit instruction — a fresh install with no
  // stored preference yet reads as enabled.
  fun isEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_ENABLED, true)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_ENABLED, enabled).apply()
  }

  fun log(context: Context, stage: String, fields: String = "") {
    if (!isEnabled(context)) return
    val thread = Thread.currentThread().name
    Log.i(TAG, "$stage thread=$thread $fields".trimEnd())
  }

  // Always logs regardless of the flag — fatal/error conditions must survive even with detailed
  // diagnostics off, per instruction ("retain essential error logging").
  fun logError(stage: String, fields: String) {
    val thread = Thread.currentThread().name
    Log.e(TAG, "$stage thread=$thread $fields")
  }

  // Rate-limited to once/second — the RMS/PCM-activity substitute (see class doc).
  fun logRateLimited(context: Context, stage: String, fields: String) {
    if (!isEnabled(context)) return
    val now = System.currentTimeMillis()
    if (now - lastRateLimitedLogAt < RATE_LIMIT_MS) return
    lastRateLimitedLogAt = now
    val thread = Thread.currentThread().name
    Log.i(TAG, "$stage thread=$thread $fields")
  }
}
