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

    Events("onStopRequested", "onListenRequested", "onWakeWordDetected", "onWakePoke", "onSttWatchdogTimeout", "onTtsWatchdogTimeout", "onMicResumeWatchdogTimeout", "onCloudFetchTimeout", "onConfirmationResult")

    OnCreate {
      // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — delivered as an event (survives backgrounding),
      // unlike the JS setTimeout it replaces as the JS-side STT session gate's release mechanism.
      BensonForegroundService.onSttWatchdogTimeout = { sessionId ->
        sendEvent("onSttWatchdogTimeout", mapOf("sessionId" to sessionId))
      }
      // ROUND_TTS_WATCHDOG_NATIVE_1 — same idea, for the TTS mic-ownership hard timer.
      BensonForegroundService.onTtsWatchdogTimeout = {
        sendEvent("onTtsWatchdogTimeout", mapOf())
      }
      // MIC_RESUME_WATCHDOG_NATIVE_1 — same idea, for doStartListening()'s post-TTS tail-wait retry.
      BensonForegroundService.onMicResumeWatchdogTimeout = {
        sendEvent("onMicResumeWatchdogTimeout", mapOf())
      }
      // CLOUD_FETCH_WATCHDOG_NATIVE_1 — same idea, for fetchWithTimeout.ts's abort trigger.
      BensonForegroundService.onCloudFetchTimeout = { requestId ->
        sendEvent("onCloudFetchTimeout", mapOf("requestId" to requestId))
      }
      // URGENT_CONFIRMATION_NATIVE_1 — native one-shot YES/NO/UNKNOWN confirmation capture result.
      BensonForegroundService.onConfirmationResult = { confirmationId, verdict, transcript ->
        sendEvent("onConfirmationResult", mapOf("confirmationId" to confirmationId, "verdict" to verdict, "transcript" to transcript))
      }
      // Durable delivery: a result that finished while JS had no listener registered (suspended)
      // must not be silently dropped — flush it the moment this module is alive again.
      BensonForegroundService.pendingConfirmationResult?.let { (confirmationId, verdict, transcript) ->
        BensonForegroundService.pendingConfirmationResult = null
        sendEvent("onConfirmationResult", mapOf("confirmationId" to confirmationId, "verdict" to verdict, "transcript" to transcript))
      }
      BensonForegroundService.onStopRequested = {
        sendEvent("onStopRequested")
      }
      BensonForegroundService.onListenRequested = {
        sendEvent("onListenRequested")
      }
      // ROUND_WAKE_STATE_BUG_1 — native heartbeat. Delivered as an EVENT (executes while
      // backgrounded, unlike JS setTimeout); JS re-arms its wake loop from the handler.
      BensonForegroundService.onWakePoke = {
        sendEvent("onWakePoke")
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
      BensonForegroundService.onWakePoke = null
      BensonForegroundService.onSttWatchdogTimeout = null
      BensonForegroundService.onTtsWatchdogTimeout = null
      BensonForegroundService.onMicResumeWatchdogTimeout = null
      BensonForegroundService.onCloudFetchTimeout = null
      BensonForegroundService.onConfirmationResult = null
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

    // WAKE HEALTH DIAGNOSIS — re-issue the ongoing notification with an honest body when the wake
    // recognizer's real state changes. Plain startService (the service is already running, so this
    // is allowed from the background); handled by ACTION_UPDATE_NOTIFICATION which only calls
    // NotificationManager.notify() — no startForeground(), no side effects.
    Function("updateNotification") { title: String, body: String ->
      val context = appContext.reactContext ?: return@Function
      val intent = Intent(context, BensonForegroundService::class.java).apply {
        action = BensonForegroundService.ACTION_UPDATE_NOTIFICATION
        putExtra(BensonForegroundService.EXTRA_TITLE, title)
        putExtra(BensonForegroundService.EXTRA_BODY, body)
      }
      try { context.startService(intent) } catch (_: Exception) {}
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
    // ── ROUND_NATIVE_WAKE_MICROWAKEWORD_1 ────────────────────────────────────────────────────────
    // JS drives every non-WAKE mic-ownership transition of the native wake engine.
    // owner ∈ {COMMAND_STT, TTS, CALL} suspends it; {WAKE, NONE} re-arms it. Idempotent.
    Function("nativeWakeSetOwner") { owner: String ->
      BensonForegroundService.instance?.nativeWakeSetOwner(owner)
    }

    // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — background-safe replacement for the JS setTimeout that
    // used to release app/index.tsx's STT session gate. timeoutMs mirrors STT_SESSION_MAX_MS there.
    Function("armSttSessionWatchdog") { sessionId: String, timeoutMs: Double ->
      BensonForegroundService.instance?.armSttSessionWatchdog(sessionId, timeoutMs.toLong())
    }
    Function("cancelSttSessionWatchdog") { sessionId: String ->
      BensonForegroundService.instance?.cancelSttSessionWatchdog(sessionId)
    }

    // ROUND_TTS_WATCHDOG_NATIVE_1 — background-safe replacement for the JS setTimeout hard timer
    // that used to be the only bound on app/index.tsx's TTS mic-ownership block. timeoutMs mirrors
    // TTS_MAX_BLOCK_MS there (passed in from JS, not duplicated here).
    Function("armTtsWatchdog") { timeoutMs: Double ->
      BensonForegroundService.instance?.armTtsWatchdog(timeoutMs.toLong())
    }
    Function("cancelTtsWatchdog") {
      BensonForegroundService.instance?.cancelTtsWatchdog()
    }

    // MIC_RESUME_WATCHDOG_NATIVE_1 — background-safe replacement for doStartListening()'s post-TTS
    // tail-wait JS setTimeout retry (TTS_TAIL_MS, passed in from JS, not duplicated here).
    Function("armMicResumeWatchdog") { timeoutMs: Double ->
      BensonForegroundService.instance?.armMicResumeWatchdog(timeoutMs.toLong())
    }
    Function("cancelMicResumeWatchdog") {
      BensonForegroundService.instance?.cancelMicResumeWatchdog()
    }

    // CLOUD_FETCH_WATCHDOG_NATIVE_1 — background-safe replacement for fetchWithTimeout.ts's JS
    // setTimeout-driven AbortController trigger. ID-keyed: requestId lets multiple concurrent
    // cloud calls (STT + brain, etc.) each own an independent timer with no collision.
    Function("armCloudFetchWatchdog") { requestId: String, timeoutMs: Double ->
      BensonForegroundService.instance?.armCloudFetchWatchdog(requestId, timeoutMs.toLong())
    }
    Function("cancelCloudFetchWatchdog") { requestId: String ->
      BensonForegroundService.instance?.cancelCloudFetchWatchdog(requestId)
    }

    // URGENT_CONFIRMATION_NATIVE_1 — native one-shot YES/NO/UNKNOWN capture (AudioRecord+VAD+cloud
    // STT), survives BENSON being backgrounded (e.g. WhatsApp foreground) and JS timers suspended.
    Function("startConfirmationListening") { confirmationId: String, timeoutMs: Double ->
      BensonForegroundService.instance?.startConfirmationListening(confirmationId, timeoutMs.toLong())
    }
    Function("cancelConfirmationListening") { confirmationId: String ->
      BensonForegroundService.instance?.cancelConfirmationListening(confirmationId)
    }

    // ROUND_WAKE_NATIVE_TO_JS_ACK_1 — atomic read+clear of a durable pending wake command. Called
    // both from the live onWakeWordDetected listener (fast path) and from a heartbeat-poll fallback
    // (app/index.tsx's wakePokeSub) so a wake that arrived while JS was suspended is still
    // delivered the next time JS actually runs — the one consumption point either path shares.
    Function("takePendingWakeCommand") {
      BensonForegroundService.instance?.takePendingWakeCommand()
    }
    // { model, cloud, running } — model=false ⇒ benson.tflite not bundled; cloud=false ⇒ no STT
    // credentials pushed yet (setNativeWakeCredentials). JS treats "model || cloud" as "some
    // native engine can own passive wake" (ROUND_WAKE_NATIVE_GENERIC_1) — nothing pretends a
    // native engine is active when neither is true; the JS Whisper fallback stays primary then.
    Function("isNativeWakeAvailable") {
      val context = appContext.reactContext
      val model = context != null && MicroWakeWord.modelPresent(context)
      val cloud = context != null && NativeCloudWake.available(context)
      mapOf(
        "model" to model,
        "cloud" to cloud,
        "running" to (BensonForegroundService.instance?.isNativeWakeRunning() == true),
      )
    }

    // ── ROUND_WAKE_NATIVE_GENERIC_1 ────────────────────────────────────────────────────────────
    // ONE authoritative wake-name config, native-persisted (survives JS suspension and service
    // recreation) so the native cloud wake loop reads the same name Settings writes. Default
    // "Benson". Same SharedPreferences-push idiom as setSttLanguage/setWakeWordEnabled — native
    // has no direct AsyncStorage access.
    Function("setWakeName") { name: String ->
      val context = appContext.reactContext ?: return@Function
      val trimmed = name.trim()
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString(NativeCloudWake.KEY_WAKE_NAME, if (trimmed.isNotBlank()) trimmed else NativeCloudWake.DEFAULT_WAKE_NAME)
        .apply()
    }

    Function("getWakeName") {
      val context = appContext.reactContext ?: return@Function NativeCloudWake.DEFAULT_WAKE_NAME
      NativeCloudWake.currentWakeName(context)
    }

    // Pushes the active STT provider's credentials down so the native cloud wake loop
    // (NativeCloudWake.kt) can transcribe an utterance without JS being alive. JS
    // (settingsStore.ts + expo-secure-store) remains the sole place the real secret is authored —
    // this is a runtime push, the same class of mechanism already established for
    // setPorcupineAccessKey (a secret pushed into this same SharedPreferences file, not a second
    // place the secret is written from). Never logged.
    Function("setNativeWakeCredentials") { apiKey: String, baseUrl: String, model: String ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString(NativeCloudWake.KEY_API_KEY, apiKey)
        .putString(NativeCloudWake.KEY_BASE_URL, baseUrl)
        .putString(NativeCloudWake.KEY_MODEL, model)
        .apply()
    }

    Function("isNativeCloudWakeConfigured") {
      val context = appContext.reactContext ?: return@Function false
      NativeCloudWake.available(context)
    }

    // DEV_STT_DEEPGRAM_1 (2026-09-16) — SEPARATE credential push for the native confirmation
    // listener (NativeConfirmationListener.kt), own SharedPreferences key
    // (KEY_CONFIRM_DEEPGRAM_API_KEY), never NativeCloudWake's KEY_API_KEY above — a Deepgram key
    // here must never redirect the passive wake loop, which stays on Groq. Same
    // runtime-push-only idiom as setNativeWakeCredentials. Never logged.
    Function("setConfirmationSttCredentials") { apiKey: String ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString(NativeConfirmationListener.KEY_CONFIRM_DEEPGRAM_API_KEY, apiKey)
        .apply()
    }

    // DEV_STT_DEEPGRAM_WAKE_1 (2026-09-17) — a THIRD independent credential push, own
    // SharedPreferences key (KEY_WAKE_DEEPGRAM_API_KEY), for the native wake loop
    // (NativeCloudWake.kt) — never shares a slot with the confirmation listener's key above or the
    // Groq wake key from setNativeWakeCredentials.
    Function("setWakeDeepgramCredentials") { apiKey: String ->
      val context = appContext.reactContext ?: return@Function
      context.getSharedPreferences("benson_watchdog_prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString(NativeCloudWake.KEY_WAKE_DEEPGRAM_API_KEY, apiKey)
        .apply()
    }

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
