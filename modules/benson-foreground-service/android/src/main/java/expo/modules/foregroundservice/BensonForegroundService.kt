package expo.modules.foregroundservice

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the process alive while BENSON listens with the mic in the background.
 * Required on Android 14+ (API 34) to legally keep recording while the app isn't foregrounded.
 * Also holds a partial wake lock so the CPU (and JS/recognition timers with it) keeps running
 * with the screen off — without it Android suspends the process shortly after screen-off and
 * the whole hands-free loop goes dead until the user manually wakes the phone.
 */
class BensonForegroundService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null
  private var hotwordRecognizer: SpeechRecognizer? = null
  private var hotwordRecognizerIsOnDevice: Boolean? = null
  private var hotwordLoopRunning = false
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  private val screenStateReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      val state = when (intent?.action) {
        Intent.ACTION_SCREEN_ON -> "on"
        Intent.ACTION_SCREEN_OFF -> "off"
        Intent.ACTION_USER_PRESENT -> "unlocked"
        else -> "unknown"
      }
      AudioDiag.log(context, "SCREEN_STATE", "state=$state")
    }
  }

  override fun onCreate() {
    super.onCreate()
    isRunning = true
    instance = this
    AudioDiag.log(this, "SERVICE_CREATE", "")
    try {
      registerReceiver(screenStateReceiver, android.content.IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
      })
    } catch (_: Exception) {}
    touchGuardianHeartbeat()
    // WorkManager persists across process death/reboot once scheduled — a second, independent
    // revival path from the AlarmManager watchdog above, in case OxygenOS throttles one
    // mechanism but not the other. KEEP means re-enqueuing here (every service start, including
    // a Guardian resurrection) is a no-op if already scheduled, not a duplicate.
    try {
      val request = PeriodicWorkRequestBuilder<BensonHealthWorker>(15, TimeUnit.MINUTES).build()
      WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
        "benson_health_check", ExistingPeriodicWorkPolicy.KEEP, request,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to schedule WorkManager health check: ${e.message}")
    }
  }

  // Guardian heartbeat — read by BensonAccessibilityService (separate Gradle module) to tell a
  // genuinely alive foreground service apart from one that needs resurrecting. See that file's
  // companion object for the full rationale; keep the prefs name/key literals in sync.
  private fun touchGuardianHeartbeat() {
    try {
      // Any normal start (JS reopening BENSON, app launch) means the user wants it running again
      // — clear the "user explicitly stopped it" flag so Guardian resumes normal crash-recovery
      // duty. The ACTION_STOP branch below runs AFTER this (same onStartCommand call), so it
      // still wins and sets the flag back to true for a real stop request.
      getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).edit()
        .putLong("last_foreground_heartbeat", System.currentTimeMillis())
        .putBoolean("user_stopped", false)
        .apply()
    } catch (_: Exception) {}
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AudioDiag.log(this, "SERVICE_START_COMMAND", "action=${intent?.action ?: "default"} returning=START_STICKY")
    touchGuardianHeartbeat()
    if (intent?.action == ACTION_REVIVE) {
      // Forced full re-init, triggered from the notification's REVIVE action — restart the
      // hotword loop even if it thinks it's already running (idempotent either way) and bring
      // the Activity forward so a stuck JS layer gets a fresh boot.
      hotwordLoopRunning = false
      mainHandler.post { startHotwordLoop() }
      bringActivityToFront()
      return START_STICKY
    }
    if (intent?.action == ACTION_STOP) {
      isRunning = false
      cancelWatchdog()
      stopHotwordLoop()
      onStopRequested?.invoke()
      releaseWakeLock()
      // Confirmed live 2026-07-30: without this flag, Guardian (BensonAccessibilityService)
      // couldn't tell an intentional user stop apart from a real OS/crash kill — the heartbeat
      // just went stale either way, so it revived BENSON right back a short time after the user
      // explicitly stopped it. This is the one bit that tells Guardian "stand down."
      try {
        getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).edit()
          .putBoolean("user_stopped", true).apply()
      } catch (_: Exception) {}
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_LISTEN) {
      onListenRequested?.invoke()
      return START_STICKY
    }

    // JS is about to run its own STT session (manual conversation mode, or handling a command
    // right after the hotword fired) — pause the native loop so the two don't fight over the mic.
    if (intent?.action == ACTION_PAUSE_HOTWORD) {
      stopHotwordLoop()
      return START_STICKY
    }

    // JS is done (reply spoken, back to idle) — resume passive "Benson" listening.
    if (intent?.action == ACTION_RESUME_HOTWORD) {
      mainHandler.post { startHotwordLoop() }
      return START_STICKY
    }

    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "BENSON"
    val body = intent?.getStringExtra(EXTRA_BODY) ?: "BENSON is listening."
    val notification = buildNotification(title, body)

    // Confirmed live (2026-07-27): Android 14+ (targetSdk 36) throws a SecurityException here —
    // "Starting FGS with type microphone ... requires ... the app must be in the eligible
    // state/exemptions" — whenever this is reached from a context Android doesn't consider a
    // direct user-initiated foreground action (an ADB-launched relaunch, or Guardian/WorkManager
    // resurrecting the service after it died). Left uncaught, this was an UNCAUGHT RuntimeException
    // that killed the ENTIRE process — not just "the mic loop failed to start" but the whole app,
    // JS engine included — which is what actually took the Accessibility Service down with it
    // (logcat: AccessibilityUserState marks it a "crashed service" the instant this process dies),
    // triggering Android's own repeated-crash auto-disable of that service. That crash cascade,
    // not anything OS-security-policy-driven against BENSON specifically, is the real explanation
    // for accessibility repeatedly needing to be re-enabled today. Catching it here can't force the
    // exemption to exist (only a genuine user-initiated foreground context does that — no code
    // workaround bypasses this OS restriction), but it stops one failed mic-loop start from taking
    // the whole app down; bringActivityToFront() is a best-effort recovery attempt only.
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: SecurityException) {
      AudioDiag.logError("SERVICE_FOREGROUND_START_FAILED", "error=\"${e.message}\" reason=background_start_restriction")
      try { bringActivityToFront() } catch (_: Exception) {}
      return START_STICKY
    }
    AudioDiag.log(this, "SERVICE_FOREGROUND_STARTED", "")
    acquireWakeLock()
    scheduleWatchdog()
    mainHandler.post { startHotwordLoop() }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    AudioDiag.log(this, "SERVICE_TASK_REMOVED", "hotwordLoopRunning=$hotwordLoopRunning")
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    AudioDiag.log(this, "SERVICE_DESTROY", "hotwordLoopRunning=$hotwordLoopRunning")
    isRunning = false
    if (instance === this) instance = null
    stopHotwordLoop()
    releaseWakeLock()
    try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
    super.onDestroy()
  }

  // Synchronous (main-thread) pause/resume, invoked directly via the companion `instance`
  // reference instead of round-tripping through startService(Intent) — that Intent dispatch is
  // asynchronous, so JS calling pauseHotword() then immediately starting its own SpeechRecognizer
  // session could race the native loop's actual stopHotwordLoop() call, both briefly holding the
  // mic at once. onDone fires only after the native loop has actually stopped, so the JS side
  // (via a Promise in BensonForegroundServiceModule) can await real confirmation.
  fun pauseHotwordAndNotify(onDone: () -> Unit) {
    Log.i(TAG, "pauseHotwordAndNotify: called, hotwordLoopRunning=$hotwordLoopRunning")
    AudioDiag.log(this, "MIC_HANDOVER", "from=hotword_loop to=stt wakeRecorderStopped=pending")
    mainHandler.post {
      val wasRunning = hotwordLoopRunning
      stopHotwordLoop()
      Log.i(TAG, "pauseHotwordAndNotify: stopHotwordLoop done, hotwordLoopRunning=$hotwordLoopRunning")
      AudioDiag.log(this, "MIC_HANDOVER", "from=hotword_loop to=stt wakeRecorderStopped=true wakeRecorderReleased=true wasRunning=$wasRunning")
      onDone()
    }
  }

  fun resumeHotwordAndNotify(onDone: () -> Unit) {
    Log.i(TAG, "resumeHotwordAndNotify: called, hotwordLoopRunning=$hotwordLoopRunning")
    AudioDiag.log(this, "MIC_HANDOVER", "from=stt to=hotword_loop")
    mainHandler.post {
      startHotwordLoop()
      Log.i(TAG, "resumeHotwordAndNotify: startHotwordLoop done, hotwordLoopRunning=$hotwordLoopRunning")
      onDone()
    }
  }

  // ---------------------------------------------------------------------
  // Wake word — native "Benson" hotword loop. Runs entirely inside this service via the plain
  // Android SpeechRecognizer API (not the JS/Expo bridge), so it keeps working with the screen
  // off or another app (Waze, YouTube, anything) in front — the previous JS-side implementation
  // in app/index.tsx died whenever the Activity wasn't resumed, which was the actual root cause
  // of "Benson only hears me on its own screen." No third-party wake-word SDK (e.g. Picovoice)
  // is used here since that requires the user to create an account and generate a custom
  // keyword model — this is short repeated SpeechRecognizer bursts instead, same trade-off
  // (small battery cost, ~1-2s gaps) already accepted for the old JS-side probe.
  // ---------------------------------------------------------------------

  // Current hotword burst's session id — assigned fresh in runHotwordBurst(), read by the
  // RecognitionListener callbacks below (all fire on the main thread, no synchronization needed).
  private var hotwordSessionId: String = "none"

  private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

  // Passive-session counters — plain (not @Volatile) since every access happens on the main
  // looper via mainHandler.post, same as the rest of this loop's state.
  private var passiveSessionsStarted = 0
  private var passiveSessionsCompleted = 0
  private var passiveErrors = 0
  private var passiveRestartsScheduled = 0
  private var lastCallbackAt = 0L
  private var wakeRegexLoggedOnce = false

  // Circuit breaker (2026-07-27) — confirmed live: a bad run of ERROR_SERVER_DISCONNECTED can
  // free-run restartBurst() thousands of times (consecutiveErrors climbing into the 1000s within
  // seconds), and on-device that coincided with system-wide video/media playback breaking (every
  // app's video stopping after a fraction of a second) until a full phone reboot — consistent
  // with exhausting a shared, limited pool of hardware codec/audio-session resources by
  // create/destroy-churning SpeechRecognizer sessions far faster than any real recognition
  // round-trip could ever need. Unlike passiveErrors (a lifetime total, never reset), this counts
  // only the current unbroken run of errors — reset to 0 on any real onResults callback.
  private var consecutiveErrors = 0
  // Plain (non-companion) nested object — a class may only have one `companion object`, and this
  // file already has one below (TAG, ACTION_* constants); a private object is just as accessible
  // from instance methods here (BackoffTuning.XYZ) without conflicting with it.
  private object BackoffTuning {
    // Restart the recognizer itself (destroy+recreate, not just cancel+reuse) once a short run of
    // errors suggests the existing instance may itself be wedged/corrupted, rather than reusing it
    // indefinitely as runHotwordBurst() otherwise does.
    const val RECREATE_RECOGNIZER_AFTER = 3
    // Stop scheduling restarts entirely past this many unbroken errors — give the system a real
    // rest instead of hammering it. Not a permanent dead end: any of the many existing
    // resumeHotword() JS call sites (every turn/mission completion) calls startHotwordLoop(),
    // which is a no-op only while hotwordLoopRunning is still true — false here lets the very next
    // one restart it fresh.
    const val CIRCUIT_BREAKER_THRESHOLD = 12
    const val BASE_DELAY_MS = 400L
    const val MAX_DELAY_MS = 20_000L
  }

  // RMS gate (product-owner-directed 2026-08-01, battery motive) — a short energy probe before
  // every passive burst, so SpeechRecognizer is only invoked when there's actually something loud
  // enough to plausibly be speech, instead of cycling burst-after-burst on silence/ambient room
  // tone. REVERSIBLE VIA THIS ONE CONSTANT: false restores the exact prior behavior (burst on
  // every cycle, unconditionally, old circuit-breaker counting too — see onError below).
  private object WakeGate {
    const val RMS_GATE_ENABLED = true
    // Same threshold/formula as BensonAudioCaptureModule.kt's RMS_THRESHOLD (sqrt(sum of squares
    // / count)) — duplicated as a literal since the two modules have no Gradle dependency between
    // them (same idiom already used for shared SharedPreferences keys elsewhere in this app).
    const val RMS_THRESHOLD = 350.0
    const val PROBE_MS = 250L // within the requested 200-300ms window
    const val RETRY_DELAY_MS = 400L // matches BackoffTuning.BASE_DELAY_MS's fast-retry cadence
  }

  // Wake-word engine switch (product-owner-directed 2026-08-01) — single revert constant.
  // "porcupine" tries Picovoice Porcupine first (see tryStartPorcupine() below); on ANY failure
  // (missing model asset, missing/blank access key, SDK init exception) it falls back to the
  // untouched SpeechRecognizer path automatically, logging exactly why via WAKE_ENGINE_FALLBACK —
  // the app must never fail to start just because the model/key aren't in place yet.
  // "speechrecognizer" skips Porcupine entirely and restores the exact prior behavior.
  private object WakeEngineConfig {
    const val WAKE_ENGINE = "porcupine" // "porcupine" | "speechrecognizer"
    // Relative to assets/ (Porcupine's own SDK resolves this internally, no manual copy needed —
    // confirmed against the SDK's own docs). User places the trained keyword file here.
    const val PORCUPINE_MODEL_ASSET = "porcupine/benson.ppn"
    // Pasted by the user (Settings, once that UI exists) into benson_watchdog_prefs — native has
    // no direct AsyncStorage access, same cross-module idiom as stt_language/wake_word_enabled.
    const val PORCUPINE_ACCESS_KEY_PREF = "porcupine_access_key"
    const val PORCUPINE_SENSITIVITY = 0.5f // SDK default; not yet exposed as a user setting
  }

  private var porcupineManager: PorcupineManager? = null
  // Tracks which engine is ACTUALLY running right now (Porcupine can silently fall back to
  // SpeechRecognizer at start time) — stopHotwordLoop() reads this to know which one to tear
  // down; WakeEngineConfig.WAKE_ENGINE alone isn't enough once a fallback has happened.
  private var activeWakeEngine: String? = null

  private fun startHotwordLoop() {
    if (hotwordLoopRunning) return
    // Wake-word kill switch (product-owner-directed) — checked here, the single function that
    // actually creates/starts passive listening (either engine), so every existing call site
    // (initial service start, ACTION_REVIVE, ACTION_RESUME_HOTWORD, resumeHotwordAndNotify) respects
    // it with no changes needed at any of those call sites. A real OFF: the mic is never opened
    // for passive listening at all, not just muted/ignored.
    val wakeWordEnabled = try {
      getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).getBoolean("wake_word_enabled", true)
    } catch (_: Exception) { true }
    if (!wakeWordEnabled) {
      AudioDiag.log(this, "WAKE_WORD_DISABLED", "startHotwordLoop skipped by user toggle, mic not opened")
      return
    }

    hotwordLoopRunning = true
    if (WakeEngineConfig.WAKE_ENGINE == "porcupine" && tryStartPorcupine()) {
      activeWakeEngine = "porcupine"
      return
    }
    activeWakeEngine = "speechrecognizer"
    startSpeechRecognizerLoop()
  }

  // Returns true only on a fully successful start (model found, key present, SDK init + start
  // didn't throw). Any failure logs WAKE_ENGINE_FALLBACK with the specific reason and returns
  // false, leaving hotwordLoopRunning as the caller set it — the caller falls back to
  // startSpeechRecognizerLoop() unconditionally, so the app is never left with no wake detection
  // just because Porcupine couldn't start.
  private fun tryStartPorcupine(): Boolean {
    val modelExists = try {
      assets.open(WakeEngineConfig.PORCUPINE_MODEL_ASSET).use { true }
    } catch (e: Exception) { false }
    if (!modelExists) {
      AudioDiag.logError("WAKE_ENGINE_FALLBACK", "reason=missing_model path=${WakeEngineConfig.PORCUPINE_MODEL_ASSET}")
      return false
    }
    val accessKey = try {
      getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
        .getString(WakeEngineConfig.PORCUPINE_ACCESS_KEY_PREF, "") ?: ""
    } catch (_: Exception) { "" }
    if (accessKey.isBlank()) {
      AudioDiag.logError("WAKE_ENGINE_FALLBACK", "reason=missing_key")
      return false
    }
    return try {
      AudioDiag.log(this, "WAKE_ENGINE_CREATE", "engine=porcupine isMainThread=${isMainThread()}")
      porcupineManager = PorcupineManager.Builder()
        .setAccessKey(accessKey)
        .setKeywordPaths(arrayOf(WakeEngineConfig.PORCUPINE_MODEL_ASSET))
        .setSensitivity(WakeEngineConfig.PORCUPINE_SENSITIVITY)
        .build(applicationContext, object : PorcupineManagerCallback {
          override fun invoke(keywordIndex: Int) {
            // Porcupine's callback API exposes only the matched keyword's index — no confidence/
            // probability score exists to log (confirmed against the SDK's own callback
            // interface: `invoke(int keywordIndex)`, nothing else). Logged as n/a rather than a
            // fabricated number.
            AudioDiag.log(this@BensonForegroundService, "WAKE_DETECT", "engine=porcupine keyword=benson confidence=n/a")
            AudioDiag.log(this@BensonForegroundService, "WAKE_MIC", "state=STOP ts=${System.currentTimeMillis()} engine=porcupine")
            AudioDiag.log(this@BensonForegroundService, "MODE_TRANSITION", "from=PASSIVE_WAKE to=COMMAND reason=WAKE_MATCH engine=porcupine")
            // Porcupine only spots the wake word itself — it has no ASR/transcription, so there is
            // never a same-breath commandTail to extract (unlike the SpeechRecognizer path, which
            // can capture "Benson, deschide Waze" as one utterance and forward the tail directly).
            // Empty string routes through onHotwordDetected's existing bare-wake-word branch
            // unchanged — no JS-side change needed for this.
            hotwordLoopRunning = false
            onHotwordDetected("")
          }
        })
      porcupineManager?.start()
      AudioDiag.log(this, "WAKE_MIC", "state=START ts=${System.currentTimeMillis()} engine=porcupine")
      AudioDiag.log(this, "WAKE_ENGINE_INIT", "success=true engine=porcupine")
      true
    } catch (e: PorcupineException) {
      AudioDiag.logError("WAKE_ENGINE_FALLBACK", "reason=init_failed error=\"${e.message}\"")
      porcupineManager = null
      false
    } catch (e: Exception) {
      AudioDiag.logError("WAKE_ENGINE_FALLBACK", "reason=init_failed error=\"${e.javaClass.simpleName}: ${e.message}\"")
      porcupineManager = null
      false
    }
  }

  // Renamed from the original startHotwordLoop() body — untouched logic, only the name and the
  // wake_word_enabled check (now hoisted into the dispatcher above) changed.
  private fun startSpeechRecognizerLoop() {
    // A fresh (re)start deserves a clean slate — otherwise a loop stopped by the circuit breaker
    // (consecutiveErrors already at/above CIRCUIT_BREAKER_THRESHOLD) would re-trip on its very
    // first error after restarting, never actually giving the recognizer a real second chance.
    consecutiveErrors = 0
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      Log.w(TAG, "hotword loop not started: SpeechRecognizer.isRecognitionAvailable() = false")
      AudioDiag.logError("WAKE_ENGINE_INIT", "success=false error=\"SpeechRecognizer.isRecognitionAvailable() returned false\"")
      hotwordLoopRunning = false
      return
    }
    if (!wakeRegexLoggedOnce) {
      wakeRegexLoggedOnce = true
      AudioDiag.log(this, "WAKE_REGEX_CONFIG", "pattern=\"${WAKE_REGEX.pattern}\"")
    }
    AudioDiag.log(this, "WAKE_ENGINE_CREATE", "engine=android.speech.SpeechRecognizer(system_default) isMainThread=${isMainThread()}")
    AudioDiag.log(this, "WAKE_CONFIG", "keywordLiteral=\"benson\" matchMechanism=regex_over_full_transcript modelPath=none modelExists=false note=\"no dedicated wake-word engine/model; SpeechRecognizer output regex-matched, see AUDIO_DIAGNOSIS_REPORT.md\"")
    AudioDiag.log(this, "WAKE_ENGINE_INIT", "success=true engine=speechrecognizer")
    runHotwordBurst()
  }

  // Public entry point for the wake-word kill-switch toggle (called from
  // BensonForegroundServiceModule when the user turns the Settings toggle off) — posts to the
  // main thread since every hotword-loop function assumes that affinity, same discipline as
  // pauseHotwordAndNotify/resumeHotwordAndNotify above.
  fun stopHotwordLoopExternal() {
    mainHandler.post { stopHotwordLoop() }
  }

  // Settings UI status line (product-owner-directed 2026-08-02) — reflects which engine is
  // ACTUALLY running, not just the WakeEngineConfig.WAKE_ENGINE preference: Porcupine can silently
  // fall back to SpeechRecognizer at start time (missing model/key/init failure), so the
  // preference alone would lie to the user about what's really listening. "none" when the
  // hotword loop isn't running at all (e.g. kill switch off, or paused during command capture).
  fun getActiveWakeEngine(): String = activeWakeEngine ?: "none"

  private fun stopHotwordLoop() {
    hotwordLoopRunning = false
    AudioDiag.log(this, "VOICE_MODE", "actual=off component=hotword_loop engine=${activeWakeEngine ?: "none"}")

    if (activeWakeEngine == "porcupine") {
      try {
        porcupineManager?.stop()
        AudioDiag.log(this, "WAKE_MIC", "state=STOP ts=${System.currentTimeMillis()} engine=porcupine")
      } catch (e: Exception) {
        AudioDiag.logError("WAKE_MIC", "state=STOP engine=porcupine success=false error=\"${e.message}\"")
      }
      try {
        porcupineManager?.delete()
        AudioDiag.log(this, "WAKE_MIC", "state=RELEASE ts=${System.currentTimeMillis()} engine=porcupine")
      } catch (e: Exception) {
        AudioDiag.logError("WAKE_MIC", "state=RELEASE engine=porcupine success=false error=\"${e.message}\"")
      }
      porcupineManager = null
      activeWakeEngine = null
      return
    }

    hotwordRecognizer?.let {
      AudioDiag.log(this, "SR_CANCEL_REQUESTED", "session=$hotwordSessionId isMainThread=${isMainThread()}")
      try {
        it.stopListening()
        AudioDiag.log(this, "WAKE_MIC", "state=STOP ts=${System.currentTimeMillis()} session=$hotwordSessionId")
        AudioDiag.log(this, "SR_CANCELLED", "session=$hotwordSessionId")
      } catch (e: Exception) {
        AudioDiag.logError("SR_CANCELLED", "session=$hotwordSessionId success=false error=\"${e.message}\"")
      }
      AudioDiag.log(this, "SR_DESTROY_REQUESTED", "session=$hotwordSessionId")
      try {
        it.destroy()
        AudioDiag.log(this, "WAKE_MIC", "state=RELEASE ts=${System.currentTimeMillis()} session=$hotwordSessionId")
        AudioDiag.log(this, "SR_DESTROYED", "session=$hotwordSessionId")
      } catch (e: Exception) {
        AudioDiag.logError("SR_DESTROYED", "session=$hotwordSessionId success=false error=\"${e.message}\"")
      }
    }
    hotwordRecognizer = null
    activeWakeEngine = null
  }

  // One RecognitionListener instance, reused across every burst — set once, on whichever
  // SpeechRecognizer instance is currently live.
  private val hotwordListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      AudioDiag.log(this@BensonForegroundService, "STT_READY_FOR_SPEECH", "session=$hotwordSessionId component=hotword_loop")
    }
    override fun onBeginningOfSpeech() {
      AudioDiag.log(this@BensonForegroundService, "STT_BEGINNING_OF_SPEECH", "session=$hotwordSessionId component=hotword_loop")
    }
    override fun onRmsChanged(rmsdB: Float) {
      // Substitute for PCM_STATS — see AudioDiag class doc: no raw AudioRecord access exists in
      // this architecture, onRmsChanged is the closest legitimate audio-activity signal.
      AudioDiag.logRateLimited(this@BensonForegroundService, "PCM_STATS_SUBSTITUTE_RMS", "session=$hotwordSessionId component=hotword_loop rms=$rmsdB")
    }
    override fun onEndOfSpeech() {
      AudioDiag.log(this@BensonForegroundService, "STT_END_OF_SPEECH", "session=$hotwordSessionId component=hotword_loop")
    }
    override fun onResults(results: Bundle?) {
      setSystemSoundsMuted(false)
      lastCallbackAt = System.currentTimeMillis()
      passiveSessionsCompleted += 1
      consecutiveErrors = 0
      val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      Log.i(TAG, "hotword burst raw transcript(s): $matches")
      AudioDiag.log(this@BensonForegroundService, "STT_FINAL", "session=$hotwordSessionId component=hotword_loop text=${matches?.joinToString("|")}")
      val normalized = matches?.firstOrNull() ?: ""
      val hit = matches?.firstNotNullOfOrNull { WAKE_REGEX.find(it) }
      AudioDiag.log(this@BensonForegroundService, "WAKE_EVALUATION", "session=$hotwordSessionId source=final normalizedText=\"$normalized\" matched=${hit != null}")
      if (hit != null) {
        // Group 2 is whatever followed the name in the same breath ("Benson, deschide Waze")
        // — if present, skip the extra round-trip and process it immediately instead of
        // starting a second empty listening session.
        val commandTail = hit.groupValues.getOrNull(2)?.trim().orEmpty()
        Log.i(TAG, "wake word matched, commandTail='$commandTail'")
        AudioDiag.log(this@BensonForegroundService, "WAKE_ACCEPTED", "session=$hotwordSessionId commandTail=\"$commandTail\"")
        AudioDiag.log(this@BensonForegroundService, "WAKE_DETECTED_NATIVE", "session=$hotwordSessionId")
        AudioDiag.log(this@BensonForegroundService, "MODE_TRANSITION", "from=PASSIVE_WAKE to=COMMAND reason=WAKE_MATCH session=$hotwordSessionId")
        AudioDiag.log(this@BensonForegroundService, "PASSIVE_SESSION_TERMINATED", "session=$hotwordSessionId reason=result_wake_match")
        onHotwordDetected(commandTail)
      } else {
        Log.i(TAG, "no wake word match, restarting burst")
        AudioDiag.log(this@BensonForegroundService, "PASSIVE_SESSION_TERMINATED", "session=$hotwordSessionId reason=result_no_match")
        restartBurst()
      }
    }
    override fun onError(error: Int) {
      setSystemSoundsMuted(false)
      lastCallbackAt = System.currentTimeMillis()
      passiveErrors += 1
      // Circuit-breaker counting (product-owner-directed 2026-08-01): once the RMS gate is
      // active, a burst only ever starts on genuinely loud audio — so ERROR_NO_MATCH after that
      // is very often real ambient noise (traffic, TV, music) that was never the wake word to
      // begin with, not evidence of a wedged recognizer. Excluded from the consecutive-error
      // count ONLY while the gate is enabled; every other error code (CLIENT/AUDIO/NETWORK/...)
      // still counts normally, since those remain genuine signals of a real problem. With the
      // gate off, this restores the exact old behavior (every error counts) — same single
      // WakeGate.RMS_GATE_ENABLED constant governs both.
      if (error != SpeechRecognizer.ERROR_NO_MATCH || !WakeGate.RMS_GATE_ENABLED) consecutiveErrors += 1
      val decoded = decodeSpeechErrorName(error)
      Log.i(TAG, "hotword burst error code=$error")
      AudioDiag.log(this@BensonForegroundService, "STT_ERROR", "session=$hotwordSessionId component=hotword_loop code=$error name=$decoded")
      AudioDiag.log(this@BensonForegroundService, "PASSIVE_SESSION_TERMINATED", "session=$hotwordSessionId reason=error consecutiveErrors=$consecutiveErrors")
      restartBurst()
    }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }

  private fun decodeSpeechErrorName(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
    else -> "ERROR_UNKNOWN($code)"
  }

  // RMS gate entry point (product-owner-directed 2026-08-01) — every path that used to call
  // runHotwordBurst() directly (startHotwordLoop, restartBurst, the watchdog) still does; this
  // function decides whether that turns into a real SpeechRecognizer burst or a cheap skip.
  // WakeGate.RMS_GATE_ENABLED=false bypasses the probe entirely and goes straight to the old
  // behavior, unchanged.
  private fun runHotwordBurst() {
    if (!hotwordLoopRunning) return
    if (!WakeGate.RMS_GATE_ENABLED) {
      runHotwordBurstActual()
      return
    }
    probeGateRms { rms ->
      mainHandler.post {
        if (!hotwordLoopRunning) return@post // state may have changed while probing
        val decision = if (rms >= WakeGate.RMS_THRESHOLD) "burst" else "skip"
        AudioDiag.log(this, "GATE_RMS", "value=$rms threshold=${WakeGate.RMS_THRESHOLD} decision=$decision")
        if (decision == "skip") {
          mainHandler.postDelayed({ runHotwordBurst() }, WakeGate.RETRY_DELAY_MS)
          return@post
        }
        runHotwordBurstActual()
      }
    }
  }

  // Short energy probe (product-owner-directed 2026-08-01, battery motive) — same RMS
  // formula/threshold already proven in BensonAudioCaptureModule.kt, run on a background thread
  // (blocking the main thread for PROBE_MS would freeze it). The probe's own AudioRecord is
  // always stopped+released in the `finally` block, on this same background thread, BEFORE
  // `onResult` is invoked — guaranteeing it never overlaps with a SpeechRecognizer burst, which
  // only ever starts afterward, on the main thread, once this callback has already returned.
  private fun probeGateRms(onResult: (Double) -> Unit) {
    Thread {
      var rms = 0.0
      var recorder: AudioRecord? = null
      try {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize > 0) {
          recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, channelConfig, audioFormat, minBufferSize * 2,
          )
          if (recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.startRecording()
            val samplesToRead = (sampleRate * WakeGate.PROBE_MS / 1000).toInt()
            val buffer = ShortArray(samplesToRead)
            val read = recorder.read(buffer, 0, samplesToRead)
            if (read > 0) {
              var sumSquares = 0.0
              for (i in 0 until read) {
                val s = buffer[i].toDouble()
                sumSquares += s * s
              }
              rms = kotlin.math.sqrt(sumSquares / read)
            }
          }
        }
      } catch (e: Exception) {
        AudioDiag.logError("GATE_RMS_PROBE_FAILED", "error=\"${e.message}\"")
      } finally {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
      }
      onResult(rms)
    }.start()
  }

  // Reuses a single SpeechRecognizer instance across every burst instead of destroying and
  // recreating it on each retry (~every 1-3s). Confirmed live 2026-07-16: that churn made the
  // on-device speech model (logcat: "ConcurrentSodaManager: Initializing SODA") reinitialize on
  // almost every single burst on this device, frequently not finishing before the next attempt
  // fired — visible as an unbroken NO_MATCH/SERVER_DISCONNECTED cycle (~every 400-500ms) that
  // never gave real speech a fair chance to be heard. create/setRecognitionListener now happens
  // once; only startListening() runs per burst, and destroy() happens once, in stopHotwordLoop().
  private fun runHotwordBurstActual() {
    hotwordSessionId = AudioDiag.nextSessionId("wake")

    // On-device (offline) recognition toggle — see AUDIO_DIAGNOSIS_REPORT.md: cloud recognition
    // on this device hangs/errors inconsistently (ERROR_NO_MATCH/ERROR_CLIENT/ERROR_TOO_MANY_REQUESTS)
    // independent of the language fix below. createOnDeviceSpeechRecognizer() only exists on API 33+
    // and requires the offline model for the language to already be downloaded on-device, or it will
    // fail fast with ERROR_LANGUAGE_NOT_SUPPORTED/ERROR_LANGUAGE_UNAVAILABLE (already logged below).
    val preferOnDevice = try {
      getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).getBoolean("prefer_ondevice_stt", false)
    } catch (_: Exception) { false }
    val useOnDevice = preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // If the desired mode differs from the currently cached instance's mode, destroy and recreate
    // — a stale instance created in the wrong mode would silently keep using it since it's reused
    // across bursts (see the reuse fix note below).
    if (hotwordRecognizer != null && hotwordRecognizerIsOnDevice != useOnDevice) {
      try { hotwordRecognizer?.destroy() } catch (_: Exception) {}
      hotwordRecognizer = null
    }

    val recognizerAlreadyExisted = hotwordRecognizer != null
    val recognizer = hotwordRecognizer ?: run {
      val created = if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    else SpeechRecognizer.createSpeechRecognizer(this)
      created.setRecognitionListener(hotwordListener)
      hotwordRecognizer = created
      hotwordRecognizerIsOnDevice = useOnDevice
      created
    }
    AudioDiag.log(this, "RECORDER_CREATE", "session=$hotwordSessionId component=hotword_loop source=SpeechRecognizer(${if (useOnDevice) "on_device" else "system_default"}) reused=$recognizerAlreadyExisted")

    // Root cause confirmed live 2026-07-16: this intent never set EXTRA_LANGUAGE, so recognition
    // silently used the device's system locale (de-DE on this device) instead of whatever the
    // user actually speaks/selected in Settings — 86 consecutive passive sessions with real
    // speech-detected audio produced zero successful transcriptions as a result. JS persists its
    // selected language via setSttLanguage() (see BensonForegroundServiceModule) whenever it
    // changes; read fresh on every burst rather than cached once, so a language change takes
    // effect on the very next passive cycle without needing a service restart.
    val sttLanguage = try {
      getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).getString("stt_language", null)
    } catch (_: Exception) { null }
    AudioDiag.log(this, "SR_CONFIG", "session=$hotwordSessionId mode=PASSIVE_WAKE language=${sttLanguage ?: "system_default"}")

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      if (sttLanguage != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLanguage)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
      if (useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      // Loosened from 500/800/500ms — those were tuned only for fast "no match, retry" passive
      // cycling and were cutting the recognizer off right after the name itself, before the user
      // finished the rest of the sentence ("Benson [cut here] deschide Waze"). These wider
      // windows tolerate a natural micro-pause between the wake word and the command that
      // follows it in the same breath, at the cost of slightly slower passive-miss cycling.
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
    }

    try {
      // Android's SpeechRecognizer plays an audible start/stop tone (the "xylophone" sound) on
      // STREAM_NOTIFICATION/STREAM_SYSTEM every time startListening() fires — since the hotword
      // loop restarts every ~1-3s while idly listening for "Benson", this was audibly beeping on
      // a loop. Muted for the duration of this one burst, restored in onResults/onError above.
      setSystemSoundsMuted(true)
      recognizer.cancel() // defensive: ensure no stale session is still active before starting a new one
      recognizer.startListening(intent)
      passiveSessionsStarted += 1
      lastCallbackAt = System.currentTimeMillis()
      AudioDiag.log(this, "WAKE_MIC", "state=START ts=${System.currentTimeMillis()} session=$hotwordSessionId")
      AudioDiag.log(this, "RECORDER_STARTED", "session=$hotwordSessionId component=hotword_loop success=true isMainThread=${isMainThread()}")
      AudioDiag.log(this, "STT_START_CALLED", "session=$hotwordSessionId component=hotword_loop trigger=passive_loop")
      AudioDiag.log(this, "PASSIVE_SESSION_STARTED", "session=$hotwordSessionId started=$passiveSessionsStarted completed=$passiveSessionsCompleted errors=$passiveErrors")
      scheduleBurstWatchdog(hotwordSessionId)
    } catch (e: Exception) {
      AudioDiag.logError("RECORDER_STARTED", "session=$hotwordSessionId component=hotword_loop success=false error=\"${e.javaClass.simpleName}: ${e.message}\"")
      setSystemSoundsMuted(false)
      restartBurst()
    }
  }

  // Confirmed live 2026-07-18: a burst that never calls back at all — neither onResults nor
  // onError, observed right as the screen turned off shortly after a fresh app launch — leaves
  // hotwordLoopRunning stuck true forever with nothing left to move it forward, since the loop
  // only ever advances via the RecognitionListener callbacks above. The service-level watchdog
  // (scheduleWatchdog/BensonWatchdogReceiver) only detects the whole SERVICE dying, not one burst
  // silently hanging while the service process is still alive — this is the missing per-burst
  // safety net. If this exact session is still the active one when the timeout fires, no callback
  // ever arrived for it — force-cancel the recognizer and start a fresh burst. Comparing session
  // ids (not just hotwordLoopRunning) means a burst that legitimately completed and was replaced
  // by a new one in the meantime is correctly left alone.
  private fun scheduleBurstWatchdog(sessionId: String) {
    mainHandler.postDelayed({
      if (hotwordLoopRunning && hotwordSessionId == sessionId) {
        AudioDiag.logError("BURST_WATCHDOG_TIMEOUT", "session=$sessionId reason=no_callback_within_${BURST_TIMEOUT_MS}ms")
        hotwordRecognizer?.let { try { it.cancel() } catch (_: Exception) {} }
        setSystemSoundsMuted(false)
        restartBurst()
      }
    }, BURST_TIMEOUT_MS)
  }

  private fun setSystemSoundsMuted(muted: Boolean) {
    try {
      val am = getSystemService(AUDIO_SERVICE) as AudioManager
      val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
      am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
      am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
    } catch (_: Exception) {}
  }

  private fun restartBurst() {
    if (!hotwordLoopRunning) {
      AudioDiag.log(this, "PASSIVE_RESTART_CANCELLED", "reason=loop_not_running session=$hotwordSessionId")
      return
    }
    if (consecutiveErrors >= BackoffTuning.CIRCUIT_BREAKER_THRESHOLD) {
      // Stop hammering the recognizer/system entirely rather than schedule yet another restart —
      // see the consecutiveErrors doc comment. hotwordLoopRunning=false makes this self-healing:
      // the next resumeHotword() call from JS (fires on essentially every turn/mission
      // completion) calls startHotwordLoop(), which is a no-op only while this stays true.
      AudioDiag.logError("PASSIVE_CIRCUIT_BREAKER_TRIPPED", "session=$hotwordSessionId consecutiveErrors=$consecutiveErrors reason=too_many_consecutive_errors")
      hotwordLoopRunning = false
      hotwordRecognizer?.let { try { it.destroy() } catch (_: Exception) {} }
      hotwordRecognizer = null
      return
    }
    if (consecutiveErrors == BackoffTuning.RECREATE_RECOGNIZER_AFTER) {
      // A short run of errors on the SAME reused instance is a signal it may itself be wedged —
      // force a fresh SpeechRecognizer rather than reusing a possibly-corrupted one indefinitely.
      AudioDiag.log(this, "SR_RECREATE_AFTER_ERRORS", "session=$hotwordSessionId consecutiveErrors=$consecutiveErrors")
      hotwordRecognizer?.let { try { it.destroy() } catch (_: Exception) {} }
      hotwordRecognizer = null
    }
    passiveRestartsScheduled += 1
    // Exponential backoff past the first few errors — a genuine transient hiccup still retries at
    // the original fast 400ms cadence, but a real bad run backs off instead of free-running.
    val delayMs = if (consecutiveErrors <= 2) BackoffTuning.BASE_DELAY_MS
      else minOf(BackoffTuning.BASE_DELAY_MS * (1L shl minOf(consecutiveErrors - 2, 8)), BackoffTuning.MAX_DELAY_MS)
    AudioDiag.log(this, "PASSIVE_RESTART_SCHEDULED", "session=$hotwordSessionId delayMs=$delayMs consecutiveErrors=$consecutiveErrors scheduled=$passiveRestartsScheduled")
    mainHandler.postDelayed({
      AudioDiag.log(this, "PASSIVE_RESTART_EXECUTED", "previousSession=$hotwordSessionId")
      runHotwordBurst()
    }, delayMs)
  }

  private fun onHotwordDetected(commandTail: String) {
    hotwordLoopRunning = false // pause self; JS resumes us via ACTION_RESUME_HOTWORD when done
    hotwordRecognizer?.let { try { it.destroy() } catch (_: Exception) {} }
    hotwordRecognizer = null
    // Porcupine equivalent teardown (product-owner-directed 2026-08-01) — without this, a
    // detection on the Porcupine path would leave PorcupineManager still running in the
    // background while control moves to command capture, fighting it for the mic.
    if (activeWakeEngine == "porcupine") {
      try { porcupineManager?.stop() } catch (_: Exception) {}
      try { porcupineManager?.delete() } catch (_: Exception) {}
      porcupineManager = null
      activeWakeEngine = null
    }

    wakeScreen()
    // bringActivityToFront() is only best-effort here (2026-07-18): starting an Activity from a
    // background Service is exactly the pattern ColorOS's own background-activity restrictions
    // (and Android 10+'s BAL rules in general) are designed to block outside a narrow set of
    // exemptions, so it silently no-ops more often than not on this device — confirmed by "Benson
    // always requires me to go find and open the app myself" even though the hotword loop was
    // demonstrably hearing and matching "Benson" the whole time. showBubbleNative() is the
    // reliable fallback: a WindowManager overlay (SYSTEM_ALERT_WINDOW) is not subject to the same
    // background-start restriction, so it shows up regardless of whether the Activity launch
    // above actually lands — giving the user visible proof BENSON woke up without forcing its
    // full UI open over whatever they were doing.
    bringActivityToFront()
    showBubbleNative()
    showWakeRingNative()
    val hasJsListener = onWakeWordDetected != null
    AudioDiag.log(this, "WAKE_EVENT_EMITTED_TO_JS", "hasListenerRegistered=$hasJsListener commandTail=\"$commandTail\"")
    if (hasJsListener) {
      onWakeWordDetected?.invoke(commandTail)
      return
    }
    // Confirmed live 2026-07-17: if the JS/Expo bridge isn't alive at this exact instant (OnePlus
    // killed the process, or this module hasn't finished OnCreate yet), the callback above was a
    // silent no-op — hotwordLoopRunning was already set false above, so the mic loop stayed
    // paused forever with nothing left to ever resume it (ACTION_RESUME_HOTWORD only ever comes
    // from the JS side that never received this event). Storing the command lets the module's
    // OnCreate flush it the moment JS actually comes back, and resuming the loop here directly
    // (not waiting on a JS round-trip that has no listener to receive it) means the mic doesn't
    // stay dead in the meantime either. Deliberately scoped to ONLY the no-listener case — when a
    // listener DOES exist, JS is alive and responsible for its own resume via
    // pauseHotwordAndNotify/resumeHotwordAndNotify; force-resuming unconditionally there risked
    // racing a legitimate in-progress STT session still actively using the mic.
    pendingWakeCommand = commandTail
    AudioDiag.log(this, "WAKE_COMMAND_QUEUED", "commandTail=\"$commandTail\" reason=no_js_listener")
    startHotwordLoop()
  }

  private fun wakeScreen() {
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    @Suppress("DEPRECATION")
    val wl = pm.newWakeLock(
      PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
      "BensonForegroundService:wakeword",
    )
    wl.acquire(3_000L)
  }

  private fun bringActivityToFront() {
    packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }?.let { startActivity(it) }
  }

  // Calls into the benson-overlay module's service directly via an explicit component intent —
  // avoids needing a Gradle dependency between the two native modules just for this one call.
  private fun showWakeRingNative() {
    val intent = Intent().apply {
      component = ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
      action = "expo.modules.overlay.ACTION_SHOW_WAKE_RING"
    }
    try { startService(intent) } catch (_: Exception) {}
  }

  // No action set -> BensonBubbleService's onStartCommand default branch (addBubble() if not
  // already shown) — the same persistent draggable bubble already used during WhatsApp/Waze
  // handoffs, requires SYSTEM_ALERT_WINDOW ("Display over other apps") to actually render; a
  // silent no-op otherwise, same as every other overlay call in this codebase.
  private fun showBubbleNative() {
    val intent = Intent().apply {
      component = ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
    }
    try { startService(intent) } catch (_: Exception) {}
  }

  // Watchdog: a repeating alarm (delivered by the OS regardless of this process's state) that
  // pings BensonWatchdogReceiver every ~60s. If this service died in the meantime (OEM kill
  // managers like ColorOS's own background-process killer can still kill a foreground service
  // despite battery-optimization exemption), the receiver restarts it. The alarm itself is
  // reliable because AlarmManager lives in the system_server process, not ours.
  private fun scheduleWatchdog() {
    val am = getSystemService(ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(
      this, 0,
      Intent(this, BensonWatchdogReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
    )
    am.setRepeating(
      AlarmManager.ELAPSED_REALTIME_WAKEUP,
      SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
      WATCHDOG_INTERVAL_MS,
      pi,
    )
  }

  private fun cancelWatchdog() {
    val am = getSystemService(ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(
      this, 0,
      Intent(this, BensonWatchdogReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
    )
    am.cancel(pi)
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BensonForegroundService:listening").apply {
      setReferenceCounted(false)
      acquire(12 * 60 * 60 * 1000L) // 12h safety cap — released explicitly on stop/destroy well before this
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  private fun buildNotification(title: String, body: String): Notification {
    createChannelIfNeeded()

    val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

    val stopIntent = Intent(this, BensonForegroundService::class.java).apply { action = ACTION_STOP }
    val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

    val listenIntent = Intent(this, BensonForegroundService::class.java).apply { action = ACTION_LISTEN }
    val listenPendingIntent = PendingIntent.getService(this, 1, listenIntent, pendingIntentFlags)

    // Manual escape hatch alongside the automatic Guardian resurrection — lets the user force a
    // full re-init directly from the notification if they notice BENSON is stuck, without
    // needing to know it should be enabled/disabled in Settings first.
    val reviveIntent = Intent(this, BensonForegroundService::class.java).apply { action = ACTION_REVIVE }
    val revivePendingIntent = PendingIntent.getService(this, 2, reviveIntent, pendingIntentFlags)

    val contentPendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    }?.let {
      PendingIntent.getActivity(this, 0, it, pendingIntentFlags)
    }

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(body)
      .setSmallIcon(applicationInfo.icon)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .addAction(0, "LISTEN", listenPendingIntent)
      .addAction(0, "REVIVE", revivePendingIntent)
      .addAction(0, "STOP", stopPendingIntent)

    contentPendingIntent?.let { builder.setContentIntent(it) }
    return builder.build()
  }

  private fun createChannelIfNeeded() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(CHANNEL_ID, "BENSON Listening", NotificationManager.IMPORTANCE_LOW).apply {
      description = "Persistent notification while BENSON is listening in the background."
      setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val TAG = "BensonHotword"
    const val CHANNEL_ID = "benson_listening_channel"
    const val NOTIFICATION_ID = 4271
    const val ACTION_STOP = "expo.modules.foregroundservice.ACTION_STOP"
    const val ACTION_REVIVE = "expo.modules.foregroundservice.ACTION_REVIVE"
    const val ACTION_LISTEN = "expo.modules.foregroundservice.ACTION_LISTEN"
    const val ACTION_PAUSE_HOTWORD = "expo.modules.foregroundservice.ACTION_PAUSE_HOTWORD"
    const val ACTION_RESUME_HOTWORD = "expo.modules.foregroundservice.ACTION_RESUME_HOTWORD"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val WATCHDOG_INTERVAL_MS = 60_000L
    // Comfortably above EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS (1800ms) plus real-world
    // recognizer-service latency — long enough that it never fires on a burst that's genuinely
    // still listening, short enough that a hung burst recovers in well under the time it'd take a
    // user to notice and manually reopen the app.
    const val BURST_TIMEOUT_MS = 8_000L

    // Set by the module while it's alive; invoked when a notification action fires.
    var onStopRequested: (() -> Unit)? = null
    var onListenRequested: (() -> Unit)? = null

    // Invoked when the native hotword loop hears "Benson" — argument is whatever followed the
    // name in the same utterance ("Benson, deschide Waze" -> "deschide Waze"), empty if the name
    // was said alone. JS should process a non-empty tail immediately, otherwise start real
    // command capture.
    var onWakeWordDetected: ((String) -> Unit)? = null

    // Set only when a hotword fired with no JS listener registered (see onHotwordDetected) —
    // BensonForegroundServiceModule's OnCreate flushes and clears this the moment a listener
    // becomes available again, so the wake word isn't lost outright just because JS wasn't ready
    // at the exact instant it was detected.
    var pendingWakeCommand: String? = null

    // STT mishearings of "Benson" across RO/DE/EN pronunciation — mirrors the variant list
    // used by the (foreground-only) JS wake-word gate this replaces, since the fuzzy matching
    // itself is still useful even though the listening loop that uses it moved here.
    private val WAKE_REGEX = Regex(
      "\\b(benson|bensen|bensson|benzon|bentson|bennson|bänson|bensn|benzine|benzin|penson|penzon|benz[ăa]|ben\\s+son)\\b[,.!?]?\\s*(.*)",
      RegexOption.IGNORE_CASE,
    )

    // Checked by BensonWatchdogReceiver — true whenever an instance of this service is alive.
    @Volatile
    var isRunning: Boolean = false

    // Live reference to the running service, used by BensonForegroundServiceModule for the
    // synchronous pause/resume calls above — null whenever no instance is alive.
    @Volatile
    var instance: BensonForegroundService? = null
  }
}
