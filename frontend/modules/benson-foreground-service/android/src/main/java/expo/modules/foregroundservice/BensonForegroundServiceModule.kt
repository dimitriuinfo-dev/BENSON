package expo.modules.foregroundservice

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise

class BensonForegroundServiceModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonForegroundService")

    Events("onStopRequested", "onListenRequested", "onWakeWordDetected")

    OnCreate {
      BensonForegroundService.onStopRequested = {
        sendEvent("onStopRequested")
      }
      BensonForegroundService.onListenRequested = {
        sendEvent("onListenRequested")
      }
      BensonForegroundService.onWakeWordDetected = { commandTail ->
        sendEvent("onWakeWordDetected", mapOf("commandTail" to commandTail))
      }
      // Flush a wake command that arrived while no JS listener existed (the process had just been
      // killed and restarted, or this module hadn't finished OnCreate yet) — otherwise it's lost
      // forever, since the native loop has no other way to redeliver it once the moment passes.
      BensonForegroundService.pendingWakeCommand?.let { commandTail ->
        BensonForegroundService.pendingWakeCommand = null
        sendEvent("onWakeWordDetected", mapOf("commandTail" to commandTail))
      }
    }

    OnDestroy {
      BensonForegroundService.onStopRequested = null
      BensonForegroundService.onListenRequested = null
      BensonForegroundService.onWakeWordDetected = null
    }

    Function("startService") { title: String, body: String ->
      val context = appContext.reactContext
      if (context != null) {
        val intent = Intent(context, BensonForegroundService::class.java).apply {
          putExtra(BensonForegroundService.EXTRA_TITLE, title)
          putExtra(BensonForegroundService.EXTRA_BODY, body)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      }
    }

    Function("stopService") {
      val context = appContext.reactContext
      if (context != null) {
        val intent = Intent(context, BensonForegroundService::class.java).apply {
          action = BensonForegroundService.ACTION_STOP
        }
        context.startService(intent)
      }
    }

    // Brings BENSON's MainActivity to the front — used by the "Benson, come back" voice
    // command/tool and by the notification tap, so returning from another app doesn't require
    // the user to hunt for BENSON in recent apps.
    Function("bringToForeground") {
      val context = appContext.reactContext ?: return@Function Unit
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
      }?.let { context.startActivity(it) }
    }

    // One-time system dialog asking the user to exempt BENSON from battery optimization —
    // without this, Android (especially aggressive OEM battery managers) can still kill the
    // foreground service + wake lock combo after extended screen-off periods.
    Function("isIgnoringBatteryOptimizations") {
      val context = appContext.reactContext ?: return@Function true
      val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Function("requestIgnoreBatteryOptimizations") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // Pause/resume the native "Benson" hotword loop — JS awaits pause right before starting its
    // own real command-capture STT session (manual conv mode, or right after wake word fires),
    // so the two never fight over the microphone, and resume once it's back to idle. AsyncFunction
    // + a real Promise (resolved only after the native loop has actually stopped/started on the
    // main thread) instead of a fire-and-forget startService(Intent) — that Intent dispatch is
    // asynchronous, so JS could previously start its own recognizer before the native one had
    // actually released the mic, causing both to briefly fight over it.
    AsyncFunction("pauseHotword") { promise: Promise ->
      val service = BensonForegroundService.instance
      android.util.Log.i("BensonHotword", "JS called pauseHotword(), instance=${if (service == null) "NULL" else "present"}")
      if (service == null) { promise.resolve(null); return@AsyncFunction }
      service.pauseHotwordAndNotify { promise.resolve(null) }
    }

    AsyncFunction("resumeHotword") { promise: Promise ->
      val service = BensonForegroundService.instance
      android.util.Log.i("BensonHotword", "JS called resumeHotword(), instance=${if (service == null) "NULL" else "present"}")
      if (service == null) { promise.resolve(null); return@AsyncFunction }
      service.resumeHotwordAndNotify { promise.resolve(null) }
    }

    // Guardian — returns true exactly once per resurrection: cleared immediately after reading,
    // so a normal app open never reports a recovery that didn't happen. Called once on JS mount
    // (app/index.tsx) so BENSON can speak an honest "I'm back" line after a silent OxygenOS kill
    // + Guardian-triggered restart, instead of the user only ever noticing via the notification.
    AsyncFunction("consumeRecoveryFlag") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) { promise.resolve(false); return@AsyncFunction }
      val prefs = context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE)
      val pending = prefs.getBoolean("recovery_pending", false)
      if (pending) prefs.edit().putBoolean("recovery_pending", false).apply()
      promise.resolve(pending)
    }

    // Diagnostic only (Debug Panel / measuring OxygenOS kill frequency for FINAL_BUILD_REPORT.md)
    // — "<timestampMs>:<reason>" pairs, most recent last, capped at 20 by the writer.
    Function("getRecoveryEventsLog") {
      val context = appContext.reactContext ?: return@Function ""
      val prefs = context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE)
      prefs.getString("recovery_events", "") ?: ""
    }

    // BENSON_AUDIO diagnostics toggle — single flag, read by both native (AudioDiag) and JS
    // audio-chain logging. Debug Panel-controlled; default ON for this diagnostic build.
    Function("isAudioDiagnosticsEnabled") {
      val context = appContext.reactContext ?: return@Function true
      AudioDiag.isEnabled(context)
    }

    Function("setAudioDiagnosticsEnabled") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function
      AudioDiag.setEnabled(context, enabled)
    }

    // JS-side audio-chain stages (STT session lifecycle, transcript handoff, orchestrator result)
    // route through this bridge so they land under the SAME "BENSON_AUDIO" logcat tag as the
    // native hotword-loop stages — one authoritative trace, not two separate JS/native logs a
    // reader has to manually interleave by timestamp.
    Function("logAudioDiag") { stage: String, fields: String ->
      val context = appContext.reactContext ?: return@Function
      AudioDiag.log(context, stage, fields)
    }

    // Root cause confirmed live 2026-07-16: the native passive hotword loop's RecognizerIntent
    // never set EXTRA_LANGUAGE, so it silently used the device's system locale (de-DE on this
    // OnePlus Nord 4) regardless of which language the user actually speaks/has selected in
    // Settings. 86 consecutive passive sessions with real speech-detected audio activity
    // (onBeginningOfSpeech firing, non-flat RMS) produced zero successful transcriptions, while
    // the JS-side path — which does explicitly pass the user's selected language — succeeded
    // repeatedly the same night. JS calls this on every language change/restore so the native
    // loop (which has no direct AsyncStorage access) can read the same preference.
    Function("setSttLanguage") { lang: String ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString("stt_language", lang).apply()
    }

    // On-device (offline) speech recognition toggle for the native passive hotword loop.
    // Cloud recognition on this device has been confirmed unreliable independent of the language
    // fix above (hangs, ERROR_NO_MATCH/ERROR_CLIENT/ERROR_TOO_MANY_REQUESTS with no consistent
    // pattern — see AUDIO_DIAGNOSIS_REPORT.md). android.speech.SpeechRecognizer.
    // createOnDeviceSpeechRecognizer() (API 33+) runs entirely on-device, no network round-trip —
    // read fresh per burst by runHotwordBurst() so toggling it takes effect on the next cycle.
    Function("setPreferOnDeviceStt") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putBoolean("prefer_ondevice_stt", enabled).apply()
    }

    Function("isOnDeviceSttSupported") {
      android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    }

    // Wake-word kill switch (product-owner-directed) — a real OFF, not a mute: gated inside
    // startHotwordLoop() itself (BensonForegroundService.kt), so every existing call site
    // (initial start, ACTION_REVIVE, ACTION_RESUME_HOTWORD, resumeHotwordAndNotify) respects it
    // with zero changes. If a passive session happens to be running when this turns off, stop it
    // immediately rather than waiting for its natural end. Mirrors setSttLanguage's
    // SharedPreferences pattern (native has no direct AsyncStorage access).
    Function("setWakeWordEnabled") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putBoolean("wake_word_enabled", enabled).apply()
      if (!enabled) {
        BensonForegroundService.instance?.stopHotwordLoopExternal()
      }
    }

    Function("isWakeWordEnabled") {
      val context = appContext.reactContext ?: return@Function true
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE)
        .getBoolean("wake_word_enabled", true)
    }

    // Picovoice Porcupine AccessKey (product-owner-directed 2026-08-01) — pasted by the user once
    // they've made a free Picovoice Console account (see SESSION_REPORT.md for the exact steps).
    // No UI wired to this yet this session — exists so the value CAN be set (e.g. from a debug
    // script or a future Settings field) without needing another native round-trip added later.
    // Same SharedPreferences pattern as setSttLanguage/setWakeWordEnabled above.
    Function("setPorcupineAccessKey") { key: String ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString("porcupine_access_key", key.trim()).apply()
    }

    Function("isPorcupineConfigured") {
      val context = appContext.reactContext ?: return@Function false
      val key = context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE)
        .getString("porcupine_access_key", "") ?: ""
      val modelExists = try {
        context.assets.open("porcupine/benson.ppn").use { true }
      } catch (_: Exception) { false }
      key.isNotBlank() && modelExists
    }

    // Settings UI status indicator (product-owner-directed 2026-08-02) — split view of the same
    // two checks isPorcupineConfigured() combines into one bool, so the Settings screen can tell
    // the user WHICH of the two is missing (key vs. model file) instead of a single opaque "not
    // configured".
    Function("getPorcupineStatus") {
      val context = appContext.reactContext ?: return@Function mapOf("hasKey" to false, "hasModel" to false)
      val key = context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE)
        .getString("porcupine_access_key", "") ?: ""
      val modelExists = try {
        context.assets.open("porcupine/benson.ppn").use { true }
      } catch (_: Exception) { false }
      mapOf("hasKey" to key.isNotBlank(), "hasModel" to modelExists)
    }

    // Which wake-word engine is ACTUALLY running right now — see
    // BensonForegroundService.getActiveWakeEngine() doc comment for why this must be read from
    // live state, not from the WAKE_ENGINE constant (Porcupine can silently fall back at start).
    Function("getActiveWakeEngine") {
      BensonForegroundService.instance?.getActiveWakeEngine() ?: "none"
    }

    // Suppresses the system STT start/stop tone (the "xylophone" beep) around JS's own
    // real-command-capture recognition session — same fix as the native hotword loop's, exposed
    // here since that session runs through expo-speech-recognition, not this module.
    Function("setSystemSoundsMuted") { muted: Boolean ->
      val context = appContext.reactContext ?: return@Function Unit
      try {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
      } catch (_: Exception) {}
    }
  }
}
