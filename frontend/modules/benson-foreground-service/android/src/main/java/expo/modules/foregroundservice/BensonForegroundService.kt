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
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
// NATIVE_CMD_1 — OkHttp/org.json already on this module's classpath (see NativeCloudWake.kt,
// same module/package, same Deepgram+Groq pattern reused here).
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
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

  // ROUND_WAKE_STATE_BUG_1 — root cause: React Native suspends JS setTimeout/setInterval when the
  // app is backgrounded (proven on device: 0 WAKE_HEALTH ticks in 30 s after APP_STATE=background).
  // The JS local-Whisper wake loop re-arms via setTimeout(startLocalWakeLoop, 150) — frozen while
  // backgrounded — so after the one in-flight capture completes there is no next scan. THIS is
  // "sometimes works (caught the in-flight capture) / sometimes nothing (loop was idle)".
  // Fix: a NATIVE Handler heartbeat (runs regardless of RN host state) that emits an onWakePoke
  // EVENT to JS every WAKE_POKE_INTERVAL_MS. Native→JS events ARE delivered while backgrounded
  // (unlike timers). The JS handler re-arms the existing wake loop. No new engine, no new
  // service, no architecture change — a heartbeat inside this existing foreground service.
  // ── ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native wake engine + single mic owner ─────────────────
  // NONE | WAKE | COMMAND_STT | TTS | CALL. JS drives every non-WAKE transition (nativeWakeSetOwner);
  // native owns WAKE<->COMMAND_STT on keyword detect. When benson.tflite is absent the whole thing
  // is inert (micOwner stays NONE, JS wake fallback + the heartbeat below stay primary).
  private var microWakeWord: MicroWakeWord? = null
  // Heed is the locally trained, configurable-name candidate. It owns the same single WAKE mic
  // slot as MWW; it is never paired with ambient cloud transcription.
  private var heedWakeWord: HeedWakeWord? = null
  // ROUND_WAKE_NATIVE_GENERIC_1 — Option C: native VAD-gated capture + cloud STT + text/fuzzy
  // match, reusing this exact mic-ownership state machine (micOwner/armNativeWake/
  // suspendNativeWake/nativeWakeSetOwner below), unchanged in shape from MICROWAKEWORD_1.
  // MicroWakeWord keeps PRIORITY when its trained model exists (future-proof; inert today — no
  // benson.tflite is bundled). NativeCloudWake is what actually runs on this build.
  private var nativeCloudWake: NativeCloudWake? = null
  @Volatile private var micOwner: String = "NONE"
  // W-1 TASK A/B/C [WAKE_DURING_PLAYBACK / WAKE_DUCKING / WAKE_AEC] — barge-in support. aecAvailable
  // is a device-capability check only (AcousticEchoCanceler.isAvailable() takes no session id); it
  // is NOT attached to Heed's AudioRecord session here — that needs a public accessor on
  // HeedWakeWord.kt, out of this round's scope (BensonForegroundService.kt only). Revert: delete
  // this block, requestDuckFocus/releaseDuckFocus/openSession/closeSession/startHeedEngineOnly
  // below, and their three call sites (nativeWakeSetOwner's "TTS" case, onHeedWakeDetected,
  // armNativeWake's owner guard).
  private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
  private var duckFocusRequest: AudioFocusRequest? = null
  private var sessionOpenUntil: Long = 0L
  private var sessionEpoch = 0
  private object SessionWindow { const val FOLLOW_UP_WINDOW_MS = 8_000L }
  private val aecAvailable: Boolean by lazy {
    try { android.media.audiofx.AcousticEchoCanceler.isAvailable() } catch (_: Throwable) { false }
  }

  // RUNDA_N3 TASK D [WAKE_AEC] — getter added to HeedWakeWord.kt (onCaptureStarted callback) so
  // this can attach to the CURRENT live AudioRecord session, not a guessed/stale one. A new
  // session (new call to heedWakeWord.start()) releases the previous canceler first. Threshold
  // (RMS_THRESHOLD etc.) is untouched — measurement only, per scope.
  private var heedAec: android.media.audiofx.AcousticEchoCanceler? = null
  private var heedAecSessionId: Int = -1

  // RUNDA_N5 TASK 2 (2026-10-02, user-approved) — "fără el nu putem deosebi o buclă moartă de un
  // wake ratat": device-verified this session that the loop was NOT dead across an 11-minute
  // no-detection window (NWW_HEALTH/heed running=true, unbroken, every ~3s) — the earlier
  // description of such windows as "wake ratat" was correct, not a hung loop; this heartbeat makes
  // that distinction visible going forward without needing a manual log audit each time.
  // maxScore30s is the most recent HEED_DETECTED score if one landed in the last 30s, else "none" —
  // it can only reflect scores that already crossed threshold (HeedWakeWord doesn't expose
  // sub-threshold inference scores without further change, out of this round's scope).
  @Volatile private var lastHeedDetectScore: Float = -1f
  @Volatile private var lastHeedDetectAt: Long = 0L
  private val WAKE_ALIVE_INTERVAL_MS = 30_000L

  private fun scheduleWakeAliveCheck() {
    mainHandler.postDelayed({
      val running = heedWakeWord?.isRunning() == true
      val scoreWindow = if (System.currentTimeMillis() - lastHeedDetectAt <= WAKE_ALIVE_INTERVAL_MS)
        "%.3f".format(lastHeedDetectScore) else "none"
      AudioDiag.log(this, "WAKE_ALIVE", "running=$running maxScore30s=$scoreWindow aecAttached=${heedAec != null} source=VOICE_RECOGNITION")
      if (!running && micOwner == "WAKE") {
        rearmWakeNative("wake_alive_check")
        AudioDiag.log(this, "WAKE_HEALED", "reason=loop_not_running")
      }
      scheduleWakeAliveCheck()
    }, WAKE_ALIVE_INTERVAL_MS)
  }

  private fun attachHeedAec(sessionId: Int) {
    if (heedAecSessionId == sessionId && heedAec != null) return
    try { heedAec?.release() } catch (_: Exception) {}
    heedAec = null
    heedAecSessionId = sessionId
    if (!aecAvailable) {
      AudioDiag.log(this, "WAKE_AEC", "sessionId=$sessionId attached=false reason=not_available")
      return
    }
    try {
      heedAec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
      AudioDiag.log(this, "WAKE_AEC", "sessionId=$sessionId attached=${heedAec != null}")
    } catch (e: Exception) {
      AudioDiag.logError("WAKE_AEC_ERROR", "sessionId=$sessionId type=${e.javaClass.simpleName}")
    }
  }

  private fun requestDuckFocus(mayDuck: Boolean) {
    if (duckFocusRequest != null) return
    // RUNDA CAR-1 — matches whatever usage the TTS itself will actually speak on (see
    // currentTtsAudioAttributes), so the focus negotiation and the real audio agree.
    val attrs = currentTtsAudioAttributes(isCarModeActive())
    val gain = if (mayDuck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    val req = AudioFocusRequest.Builder(gain).setAudioAttributes(attrs).setOnAudioFocusChangeListener {}.build()
    val result = try { audioManager.requestAudioFocus(req) } catch (e: Exception) {
      AudioDiag.logError("DUCK_ERROR", "type=${e.javaClass.simpleName}"); return
    }
    duckFocusRequest = req
    AudioDiag.log(this, "DUCK", "on holder=wake mode=${if (mayDuck) "duck" else "stop"} result=$result")
  }

  private fun releaseDuckFocus() {
    val req = duckFocusRequest ?: return
    try { audioManager.abandonAudioFocusRequest(req) } catch (_: Exception) {}
    duckFocusRequest = null
    AudioDiag.log(this, "DUCK", "off holder=wake")
  }

  private fun openSession(reason: String, mayDuck: Boolean) {
    sessionOpenUntil = System.currentTimeMillis() + SessionWindow.FOLLOW_UP_WINDOW_MS
    sessionEpoch += 1
    val epoch = sessionEpoch
    AudioDiag.log(this, "SESSION", "open reason=$reason")
    requestDuckFocus(mayDuck)
    mainHandler.postDelayed({ if (sessionEpoch == epoch) closeSession("timeout") }, SessionWindow.FOLLOW_UP_WINDOW_MS)
  }

  private fun closeSession(reason: String) {
    if (sessionOpenUntil == 0L) return
    sessionOpenUntil = 0L
    sessionEpoch += 1
    AudioDiag.log(this, "SESSION", "close reason=$reason")
    releaseDuckFocus()
    try { expo.modules.car.BensonCarScreen.instance?.setIdle() } catch (_: Exception) {} // RUNDA CAR-3a
  }

  // Starts ONLY the Heed engine thread, without touching micOwner. armNativeWake() always forces
  // micOwner to "WAKE" on a successful (re)arm, which is correct for every other caller but wrong
  // here: TTS playback doesn't hold the mic hardware at all, so Heed can listen concurrently while
  // micOwner stays "TTS" for every other state machine (TTS watchdog, bubble, owner-release logic)
  // to keep working unmodified.
  private fun startHeedEngineOnly(why: String): Boolean {
    if (!HeedWakeWord.modelPresent(this)) return false
    if (heedWakeWord?.isRunning() == true) return true
    if (heedWakeWord == null) {
      heedWakeWord = HeedWakeWord(
        applicationContext,
        onDetected = { phrase, score, wavPath, triggeredAtMs -> onHeedWakeDetected(phrase, score, wavPath, triggeredAtMs) },
        log = { stage, fields -> AudioDiag.log(this, stage, fields) },
        onCaptureStarted = { sessionId -> attachHeedAec(sessionId) },
      )
    }
    val ok = heedWakeWord?.start() == true
    AudioDiag.log(this, if (ok) "HEED_REARM" else "HEED_ERROR", if (ok) "why=$why" else "reason=start_failed why=$why")
    return ok
  }

  // FIX_NATIVE_WAKE_ACK_1 (2026-10-02, user-directed) — "Da, Master" must not depend on the JS
  // engine being alive. Device-proven this session: 15 WAKE_DETECT, only 4 WAKE_ACK, and every one
  // of those 4 landed within seconds of OVERLAY_SELF_ACTIVITY event=resumed (the user manually
  // foregrounding the app) — every wake while backgrounded got zero ack, ever. Speaking here, in
  // onHeedWakeDetected, BEFORE onHotwordDetected's JS emit, removes that dependency entirely: this
  // runs on the service's own thread regardless of whether any JS listener is registered. ToneGenerator
  // is the fallback for the narrow window right after process start before TextToSpeech.onInit has
  // fired. Revert: delete this block, initNativeAck()'s call site in onCreate, speakNativeAck()'s
  // call site in onHeedWakeDetected, and the shutdown call in onDestroy.
  private var nativeTts: android.speech.tts.TextToSpeech? = null
  @Volatile private var nativeTtsReady = false
  // FIX_WAKE_SELF_ECHO_GUARD_1 (2026-10-03) — see SelfTtsGuard. 0L = "never"/"not speaking".
  @Volatile private var ttsSpeakingSince = 0L
  @Volatile private var ttsLastDoneAt = 0L

  private fun initNativeAck() {
    nativeTts = android.speech.tts.TextToSpeech(applicationContext) { status ->
      nativeTtsReady = status == android.speech.tts.TextToSpeech.SUCCESS
      if (nativeTtsReady) {
        try { nativeTts?.language = java.util.Locale("ro", "RO") } catch (_: Exception) {}
        AudioDiag.log(this, "NATIVE_TTS_INIT", "status=success")
      } else {
        AudioDiag.logError("NATIVE_TTS_INIT", "status=failed code=$status")
      }
    }
    // NATIVE_CMD_1 — the missing link diagnosed 2026-10-02: speak() had no onDone callback, so
    // nothing ever opened a second capture for the command that follows the ack. utteranceId is
    // prefixed "wake_" (covers both the "Da, Master" ack and the RUNDA_N3 "Încă nu știu să fac
    // asta." fallback below — this nativeTts instance has no other caller) so onDone always
    // continues straight into another capture.
    nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        ttsSpeakingSince = System.currentTimeMillis()
      }
      override fun onDone(utteranceId: String?) {
        ttsSpeakingSince = 0L
        ttsLastDoneAt = System.currentTimeMillis()
        if (utteranceId?.startsWith("wake_") != true) return
        AudioDiag.log(this@BensonForegroundService, "WAKE_ACK_DONE", "utteranceId=$utteranceId")
        mainHandler.post { startFollowUpCapture() }
      }
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        ttsSpeakingSince = 0L
        ttsLastDoneAt = System.currentTimeMillis()
        if (utteranceId?.startsWith("wake_") != true) return
        AudioDiag.logError("WAKE_ACK_ERROR", "utteranceId=$utteranceId reason=tts_error")
        mainHandler.post { startFollowUpCapture() }
      }
    })
  }

  // NATIVE_CMD_1 (2026-10-02, user-approved) — speaks the ack and, once it is actually done
  // (WAKE_ACK_DONE above), opens a fresh command capture. Only reached when STT on the original
  // wake buffer already came back empty (see handleNativeCommandFlow) — "Benson, <command>" in one
  // breath never reaches this function at all, so it never gets a spoken ack. Revert: restore the
  // old unconditional speakNativeAck(now) call at the top of onHeedWakeDetected and delete this
  // function + the UtteranceProgressListener above.
  // RUNDA CAR-1 (2026-10-04, user-directed, device-proven bug) — "vorbește prin speakerul
  // propriu și nu ascultă comenzi" cu Android Auto conectat: nativeTts nu avea niciodată
  // setAudioAttributes() apelat, deci rula pe ieșirea implicită a motorului TTS, care nu e
  // garantat rutată spre boxele mașinii de Android Auto (spre deosebire de
  // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, calea pe care Waze o folosește și care AJUNGE garantat
  // acolo). "Nu ascultă comenzi" era cel mai probabil un efect secundar: dacă "Da, Master" nu se
  // auzea din boxe, Rareș nu știa să continue, sesiunea expira pe "no_followup_command" — nu un
  // bug de microfon separat (microfonul telefonului e neschimbat, WAKE_ALIVE maxScore30s rămâne
  // diagnosticul de verificat la următorul test, nu s-a schimbat nimic acolo).
  @Volatile private var lastCarModeLogged: Boolean? = null

  // FIX_CAR1_CONNECTION_DETECT_1 (2026-10-04, user-directed) — UiModeManager's UI_MODE_TYPE_CAR
  // stayed false on-device while Android Auto was genuinely projecting (gearhead processes alive,
  // CarAppService registered) — that flag tracks the phone's OWN car-mode UI, not a projection
  // session. androidx.car.app.connection.CarConnection.getType() is the library's own, purpose-
  // built signal for "is this app actually connected to a car head unit right now". Observed once
  // (forever, no lifecycle to tie to in a Service) into a cached field — same pattern as
  // lastForegroundPackage elsewhere in this file. Revert: restore the UiModeManager check above.
  @Volatile private var carConnectionType: Int = androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED

  private fun initCarConnectionObserver() {
    try {
      androidx.car.app.connection.CarConnection(this).type.observeForever { type ->
        carConnectionType = type ?: androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        AudioDiag.log(this, "CAR_CONNECTION", "type=$carConnectionType")
        logCarModeIfChanged()
      }
    } catch (e: Exception) {
      AudioDiag.logError("CAR_CONNECTION_INIT_FAILED", "error=\"${e.message}\"")
    }
  }

  private fun isCarModeActive(): Boolean =
    carConnectionType == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION

  private fun logCarModeIfChanged() {
    val active = isCarModeActive()
    if (lastCarModeLogged == active) return
    lastCarModeLogged = active
    AudioDiag.log(this, "CAR_MODE", "${if (active) "on" else "off"} source=car_connection")
  }

  private fun currentTtsAudioAttributes(carMode: Boolean): AudioAttributes = AudioAttributes.Builder()
    .setUsage(if (carMode) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

  // Applied right before every speak() — car mode can toggle mid-session (cable plugged/unplugged).
  private fun applyTtsRouting(t: android.speech.tts.TextToSpeech) {
    logCarModeIfChanged()
    val carMode = lastCarModeLogged == true
    try { t.setAudioAttributes(currentTtsAudioAttributes(carMode)) } catch (_: Exception) {}
    val outDevice = try {
      if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice?.type?.toString() ?: "unknown" else "unknown"
    } catch (_: Exception) { "unknown" }
    AudioDiag.log(this, "TTS_ROUTE", "usage=${if (carMode) "nav_guidance" else "assistant"} carMode=$carMode outDevice=$outDevice")
  }

  private fun speakNativeAckThenCapture() {
    val t = nativeTts
    if (t != null && nativeTtsReady) {
      applyTtsRouting(t)
      val utteranceId = "wake_ack_${System.currentTimeMillis()}"
      val result = t.speak("Da, Master.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
      AudioDiag.log(this, "WAKE_ACK", "engine=native result=$result")
    } else {
      try {
        val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
        mainHandler.postDelayed({ try { tone.release() } catch (_: Exception) {} }, 300)
      } catch (_: Exception) {}
      AudioDiag.log(this, "WAKE_ACK", "fallback=tone")
      // No UtteranceProgressListener for a tone — fire the capture after a fixed short delay.
      mainHandler.postDelayed({ startFollowUpCapture() }, 400)
    }
  }

  // RUNDA_N3 (2026-10-02, user-approved) — "nicio comandă nu mai dispare în tăcere": a command
  // native routing didn't recognize, captured while BENSON's own screen isn't visible (JS likely
  // suspended — device-proven this session: three consecutive commands with zero log activity
  // past STT, not just a routing miss), gets this instead of a silent JS handoff into the void.
  // Session stays open — same onDone -> startFollowUpCapture() path as the ack.
  // RUNDA SPEAK-1 (2026-10-04, user-directed) — "toate textele rostite trec printr-o singură
  // funcție speak(text)": orice confirmare/rezultat nou (YouTube, și tot ce vine după) trece prin
  // AICI, nu direct prin TextToSpeech. Când vine RT-1a, doar vocea din spatele acestei funcții se
  // schimbă (Realtime, cu fallback pe acest TTS) — nimic din apelurile existente nu se mută.
  private fun speak(text: String) = speakNativeFallbackThenCapture(text)

  private fun speakNativeFallbackThenCapture(text: String) {
    val t = nativeTts
    if (t != null && nativeTtsReady) {
      // FIX_TTS_INTONATION_REVERT (2026-10-03, device-proven, user-directed) — both
      // TTS_INTONATION_1 (uniform pitch bump, "nu se intelege ce vrea") and TTS_INTONATION_2
      // (split last word at a higher pitch, "suna groaznic... ton ciudat/nenatural") made this
      // worse, not better. Per CLAUDE.md "pasul înapoi e obligatoriu": reverted to plain speech,
      // no pitch manipulation — the proven baseline every successful WA-1 test ran on before
      // either experiment. Real question intonation needs a different TTS engine/voice with actual
      // prosody control (SSML), not a text-level trick on the stock speak()/setPitch() API — a
      // separate, bigger round if still wanted.
      applyTtsRouting(t)
      val utteranceId = "wake_say_${System.currentTimeMillis()}"
      // ADĂUGARE — BENSON ÎNTREABĂ CA O ÎNTREBARE (06.10.2026, product-owner-directed): textul
      // complet, cu punctuația, ca să se poată verifica pe log că „?" ajunge intact la motorul de
      // voce — nu e trunchiat/sanitizat nicăieri pe acest drum (t.speak primește `text` neschimbat).
      AudioDiag.log(this, "TTS_NATIVE_REQUEST", "text=\"$text\"")
      val result = t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
      AudioDiag.log(this, "WAKE_FALLBACK", "engine=native result=$result")
    } else {
      AudioDiag.log(this, "WAKE_FALLBACK", "fallback=tone")
      mainHandler.postDelayed({ startFollowUpCapture() }, 400)
    }
  }

  // Self-importance only (no UsageStatsManager/Accessibility reach — out of this round's scope,
  // see tryNativeYoutube's scope note for the same boundary on foreground-app detection). This
  // answers "is BENSON's own screen visible", which is exactly what JS being asleep correlates
  // with — not "what app is in front", which point 2 of the N-3 request also asked for and which
  // this function cannot answer within scope.
  private fun isBensonForeground(): Boolean = try {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val myPid = android.os.Process.myPid()
    val me = am.runningAppProcesses?.find { it.pid == myPid }
    me != null && me.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  } catch (_: Exception) { false }

  @Volatile private var callAudioBlocked = false
  private var callStatePollRunning = false
  private val callPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == "wa_call_lifecycle_state") mainHandler.post { refreshCallAudioState("whatsapp_signal") }
  }
  private val audioModeChangedListener = AudioManager.OnModeChangedListener { mode ->
    mainHandler.post { refreshCallAudioState("audio_mode_changed_$mode") }
  }
  // ROUND_WAKE_NATIVE_GENERIC_1 — last-resort reclaim. Confirmed live on 9c1464eb this round: a
  // native wake trigger hands the mic to JS (micOwner=COMMAND_STT) exactly as designed, but if the
  // command that followed wasn't a recognized action, JS's own reply/conversation path can run
  // into the SAME JS-suspension-on-background limitation this whole round exists to route around
  // (ROUND_WAKE_STATE_BUG_1) — JS goes silent mid-turn and never calls back to release ownership,
  // permanently stranding micOwner at COMMAND_STT (observed: 44+ s and climbing, zero further
  // BENSON_AUDIO activity). armNativeWake() correctly refuses to re-arm while micOwner is
  // COMMAND_STT/TTS (by design — JS legitimately owns the mic then), so nothing else in this file
  // was ever going to notice or recover. This timer is the backstop: if the SAME owner transition
  // is still current after OWNER_RECLAIM_TIMEOUT_MS, force it back to NONE and re-arm. Deliberately
  // longer than every existing JS-side bound for these two states (STT_SESSION_MAX_MS=30000,
  // TTS_MAX_BLOCK_MS=15000 in app/index.tsx) so it never preempts a legitimate in-flight command or
  // reply — it only fires once JS itself has gone silent. CALL is deliberately excluded: a
  // WhatsApp call is legitimately long-lived (JS's own WA_CALL_MIC_HOLD_MS=180000) and already has
  // its own dedicated native call-lifecycle-ended signal — a short generic timeout would wrongly
  // reclaim the mic mid-call. Revert: remove the `next == "COMMAND_STT" || next == "TTS"` block.
  private var micOwnerEpoch = 0
  private object OwnerWatchdog { const val TIMEOUT_MS = 45_000L }

  private fun setMicOwner(next: String, reason: String) {
    if (micOwner == next) return
    AudioDiag.log(this, "MIC_OWNER", "from=$micOwner to=$next reason=$reason")
    micOwner = next
    micOwnerEpoch += 1
    if (next == "COMMAND_STT" || next == "TTS" || next == "CONFIRMATION_STT") {
      val epoch = micOwnerEpoch
      val owner = next
      mainHandler.postDelayed({
        if (micOwner == owner && micOwnerEpoch == epoch) {
          AudioDiag.logError("WAKE_NATIVE_RECOVER", "reason=owner_timeout owner=$owner timeoutMs=${OwnerWatchdog.TIMEOUT_MS}")
          setMicOwner("NONE", "owner_timeout")
          armNativeWake("owner_timeout_recover")
        }
      }, OwnerWatchdog.TIMEOUT_MS)
    }
  }

  // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — the JS-side STT session gate (sttSessionActiveRef in
  // app/index.tsx) was released by a plain JS setTimeout, which goes inert while BENSON is
  // backgrounded (same root cause class as OwnerWatchdog above) — proven live: a session left open
  // during the WhatsApp write flow (which backgrounds BENSON) never closed, permanently rejecting
  // every later STT attempt with STT_REJECTED reason=session_active. This mirrors setMicOwner's
  // epoch-guarded Handler timer exactly, but tracks the JS session id instead of mic ownership, and
  // notifies JS via event (delivered while backgrounded, unlike a JS timer) instead of acting alone.
  private var sttWatchdogSessionId: String? = null
  private var sttWatchdogEpoch = 0

  fun armSttSessionWatchdog(sessionId: String, timeoutMs: Long) {
    sttWatchdogSessionId = sessionId
    sttWatchdogEpoch += 1
    val epoch = sttWatchdogEpoch
    AudioDiag.log(this, "STT_WATCHDOG_ARMED", "sessionId=$sessionId deadline=$timeoutMs")
    mainHandler.postDelayed({
      if (sttWatchdogSessionId == sessionId && sttWatchdogEpoch == epoch) {
        AudioDiag.logError("STT_WATCHDOG_TIMEOUT", "sessionId=$sessionId")
        sttWatchdogSessionId = null
        onSttWatchdogTimeout?.invoke(sessionId)
      } else {
        AudioDiag.log(this, "STT_WATCHDOG_STALE_IGNORED", "timedOutSessionId=$sessionId currentSessionId=${sttWatchdogSessionId ?: "none"}")
      }
    }, timeoutMs)
  }

  fun cancelSttSessionWatchdog(sessionId: String) {
    if (sttWatchdogSessionId == sessionId) {
      sttWatchdogSessionId = null
      sttWatchdogEpoch += 1
    }
  }

  // ROUND_TTS_WATCHDOG_NATIVE_1 — mirrors armSttSessionWatchdog above exactly, for TTS instead of
  // STT. Confirmed live 2026-09-15: a TTS bind failure (Android TextToSpeech's ServiceConnection
  // never completed — every call logging "not bound to TTS engine") left micOwner=TTS stranded for
  // the full 45s OwnerWatchdog window instead of app/index.tsx's own TTS_MAX_BLOCK_MS=15000, because
  // BOTH of that file's JS-side watchdogs (the beginTtsBlock() hard timer AND speakOnDevice()'s own
  // per-call timer) are plain setTimeout and go inert while BENSON is backgrounded (same root cause
  // class as ROUND_STT_SESSION_WATCHDOG_NATIVE_1) — the target app (Spotify) was foreground the
  // whole time. This is a native, epoch-guarded backstop using the SAME already-chosen
  // TTS_MAX_BLOCK_MS bound (passed in from JS, not duplicated here) instead of the much longer
  // generic OwnerWatchdog, which stays untouched as the final, JS-independent fallback.
  private var ttsWatchdogEpoch = 0
  private var ttsWatchdogArmed = false

  fun armTtsWatchdog(timeoutMs: Long) {
    ttsWatchdogEpoch += 1
    val epoch = ttsWatchdogEpoch
    ttsWatchdogArmed = true
    AudioDiag.log(this, "TTS_WATCHDOG_ARM", "timeoutMs=$timeoutMs")
    mainHandler.postDelayed({
      if (ttsWatchdogArmed && ttsWatchdogEpoch == epoch) {
        AudioDiag.logError("TTS_WATCHDOG_FIRE", "timeoutMs=$timeoutMs")
        ttsWatchdogArmed = false
        // Release ownership immediately, natively — do not wait for the JS event round-trip (which
        // still fires right after, so JS can resume the correct follow-up listener per its own
        // pendingDisambigReplyRef-aware endTtsBlock() logic instead of a generic wake re-arm here).
        if (micOwner == "TTS") setMicOwner("NONE", "tts_watchdog")
        onTtsWatchdogTimeout?.invoke()
      }
    }, timeoutMs)
  }

  fun cancelTtsWatchdog() {
    if (ttsWatchdogArmed) {
      AudioDiag.log(this, "TTS_WATCHDOG_CANCEL", "")
      ttsWatchdogArmed = false
      ttsWatchdogEpoch += 1
    }
  }

  // MIC_RESUME_WATCHDOG_NATIVE_1 (2026-09-17) — mirrors armTtsWatchdog above exactly, same root
  // cause class, different call site: app/index.tsx's doStartListening() has a short (~500ms)
  // TTS_TAIL_MS grace wait after speech ends (anti-self-echo — don't open the mic on the acoustic
  // tail of BENSON's own voice) that used to defer its retry via a plain JS
  // `setTimeout(() => doStartListening(), ttsTailLeft)`. Device-confirmed 2026-09-17: that JS timer
  // can go inert while BENSON is backgrounded exactly like the ones ROUND_TTS_WATCHDOG_NATIVE_1
  // already fixed — the retry never ran, mic ownership stayed stranded at TTS for the full 45s
  // generic OwnerWatchdog instead of ~500ms. This native, epoch-guarded timer replaces that one
  // JS setTimeout; unlike armTtsWatchdog it does NOT touch micOwner itself (the tail wait isn't an
  // ownership-stuck condition, just a timing gate before doStartListening() proceeds) — firing only
  // tells JS "the tail has elapsed, retry now."
  private var micResumeWatchdogEpoch = 0
  private var micResumeWatchdogArmed = false

  fun armMicResumeWatchdog(timeoutMs: Long) {
    micResumeWatchdogEpoch += 1
    val epoch = micResumeWatchdogEpoch
    micResumeWatchdogArmed = true
    AudioDiag.log(this, "MIC_RESUME_WATCHDOG_ARM", "timeoutMs=$timeoutMs")
    mainHandler.postDelayed({
      if (micResumeWatchdogArmed && micResumeWatchdogEpoch == epoch) {
        AudioDiag.log(this, "MIC_RESUME_WATCHDOG_FIRE", "timeoutMs=$timeoutMs")
        micResumeWatchdogArmed = false
        onMicResumeWatchdogTimeout?.invoke()
      }
    }, timeoutMs)
  }

  fun cancelMicResumeWatchdog() {
    if (micResumeWatchdogArmed) {
      AudioDiag.log(this, "MIC_RESUME_WATCHDOG_CANCEL", "")
      micResumeWatchdogArmed = false
      micResumeWatchdogEpoch += 1
    }
  }

  // CLOUD_FETCH_WATCHDOG_NATIVE_1 (2026-09-17) — same root cause class, fourth instance found in
  // one session: lib/agents/fetchWithTimeout.ts (the ONE shared timeout wrapper around every
  // cloud call — Groq/Deepgram STT, the AI brain chat completion) used a plain JS
  // `setTimeout(() => controller.abort(), timeoutMs)`. Device-confirmed 2026-09-17: while
  // backgrounded, that timer can go inert, so `await fetch(...)` never settles at all — not even
  // as an error. app/index.tsx's handleIncomingText() already wraps its whole body in
  // try/finally specifically to guarantee loadingRef resets on ANY exception, but a finally block
  // can only run once its try block's execution resumes — an await that never resolves or rejects
  // never resumes, so that finally never fires either, and loadingRef stays stuck true forever
  // (silently dropping every later command via handleIncomingText's own
  // `if (!msg || loadingRef.current) return;` guard). Unlike armTtsWatchdog/armMicResumeWatchdog
  // (one call at a time), cloud fetches can genuinely overlap (STT + a brain call, etc.), so this
  // is ID-keyed rather than a single epoch — each request gets its own independent timer, and a
  // late/stale fire for an already-finished or already-cancelled id is a no-op.
  private val cloudFetchWatchdogs = mutableMapOf<String, Int>()
  private var cloudFetchWatchdogEpochCounter = 0

  fun armCloudFetchWatchdog(requestId: String, timeoutMs: Long) {
    cloudFetchWatchdogEpochCounter += 1
    val epoch = cloudFetchWatchdogEpochCounter
    cloudFetchWatchdogs[requestId] = epoch
    AudioDiag.log(this, "CLOUD_FETCH_TIMEOUT_ARM", "requestId=$requestId timeoutMs=$timeoutMs")
    mainHandler.postDelayed({
      if (cloudFetchWatchdogs[requestId] == epoch) {
        cloudFetchWatchdogs.remove(requestId)
        AudioDiag.log(this, "CLOUD_FETCH_TIMEOUT_FIRE", "requestId=$requestId")
        onCloudFetchTimeout?.invoke(requestId)
      }
    }, timeoutMs)
  }

  fun cancelCloudFetchWatchdog(requestId: String) {
    if (cloudFetchWatchdogs.remove(requestId) != null) {
      AudioDiag.log(this, "CLOUD_FETCH_TIMEOUT_CANCEL", "requestId=$requestId")
    }
  }

  // URGENT_CONFIRMATION_NATIVE_1 — one-shot YES/NO/UNKNOWN confirmation capture, entirely native
  // (AudioRecord+VAD+cloud-STT, same recipe as NativeCloudWake — see NativeConfirmationListener).
  // Proven live that a JS-owned mic loop cannot reliably re-arm while BENSON is backgrounded
  // (WhatsApp/etc. foreground): this replaces the LISTENING WINDOW itself with a native one that
  // does not depend on any JS timer or JS being resumed at all.
  private var confirmationListener: NativeConfirmationListener? = null

  fun startConfirmationListening(confirmationId: String, timeoutMs: Long) {
    if (callAudioBlocked || isCallAudioActive()) {
      AudioDiag.log(this, "CONFIRM_LISTEN_SUPPRESSED", "reason=call_active")
      return
    }
    confirmationListener?.stop()
    setMicOwner("CONFIRMATION_STT", "confirmation_start")
    AudioDiag.log(this, "CONFIRM_LISTEN_START", "confirmationId=$confirmationId timeoutMs=$timeoutMs")
    val listener = NativeConfirmationListener(
      applicationContext,
      onResult = { verdict, transcript ->
        mainHandler.post {
          AudioDiag.log(this, "CONFIRM_LISTEN_RESULT", "confirmationId=$confirmationId verdict=$verdict text=\"$transcript\"")
          if (micOwner == "CONFIRMATION_STT") setMicOwner("NONE", "confirmation_done")
          armNativeWake("confirmation_done")
          val cb = onConfirmationResult
          if (cb != null) cb(confirmationId, verdict, transcript)
          else pendingConfirmationResult = Triple(confirmationId, verdict, transcript)
        }
      },
      log = { stage, fields -> AudioDiag.log(this, stage, fields) },
    )
    confirmationListener = listener
    listener.start(timeoutMs)
  }

  fun cancelConfirmationListening(confirmationId: String) {
    confirmationListener?.stop()
    confirmationListener = null
    if (micOwner == "CONFIRMATION_STT") {
      AudioDiag.log(this, "CONFIRM_LISTEN_CANCELLED", "confirmationId=$confirmationId")
      setMicOwner("NONE", "confirmation_cancelled")
      armNativeWake("confirmation_cancelled")
    }
  }

  // Ambient wake must be local. NativeCloudWake transcribes every VAD candidate and can upload
  // unrelated nearby speech before the configured wake word is known; it is not a wake detector.
  private fun nativeWakeAvailable(): Boolean =
    HeedWakeWord.modelPresent(this) || MicroWakeWord.modelPresent(this)

  // Build + start exactly one native wake engine. No-op unless nothing else owns the mic and the
  // model/credentials + kill switch allow it. Idempotent (both engines self-guard on `running`).
  private fun armNativeWake(why: String) {
    if (callAudioBlocked || isCallAudioActive()) {
      AudioDiag.log(this, "WAKE_AUDIO_BLOCKED", "why=$why reason=call_active")
      return
    }
    if (!nativeWakeAvailable()) {
      if (nativeCloudWake?.isRunning() == true) {
        AudioDiag.log(this, "WAKE_CLOUD_FALLBACK_STOP", "reason=local_model_missing ambient_upload_disabled=true")
        try { nativeCloudWake?.stop() } catch (_: Exception) {}
        nativeCloudWake = null
      }
      AudioDiag.log(this, "NATIVE_WAKE_UNAVAILABLE", "reason=missing_benson_model cloud_fallback_disabled=true")
      return
    }
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("user_stopped", false)) return
    if (!prefs.getBoolean("wake_word_enabled", true)) return
    // W-1 TASK A — "TTS" deliberately excluded: playback doesn't hold the mic, so Heed may run
    // concurrently (see startHeedEngineOnly). Revert: add "|| micOwner == \"TTS\"" back.
    if (micOwner == "COMMAND_STT" || micOwner == "CALL" || micOwner == "CONFIRMATION_STT") {
      AudioDiag.log(this, "WAKE_AUDIO_BLOCKED", "why=$why owner=$micOwner")
      return
    }

    if (HeedWakeWord.modelPresent(this)) {
      if (heedWakeWord?.isRunning() == true) { setMicOwner("WAKE", "already_armed"); return }
      if (heedWakeWord == null) {
        heedWakeWord = HeedWakeWord(
          applicationContext,
          onDetected = { phrase, score, wavPath, triggeredAtMs -> onHeedWakeDetected(phrase, score, wavPath, triggeredAtMs) },
          log = { stage, fields -> AudioDiag.log(this, stage, fields) },
          onCaptureStarted = { sessionId -> attachHeedAec(sessionId) },
        )
      }
      AudioDiag.log(this, "WAKE_ENGINE", "engine=HEED why=$why")
      if (heedWakeWord?.start() == true) {
        setMicOwner("WAKE", "heed_armed")
        AudioDiag.log(this, "HEED_REARM", "why=$why")
      } else {
        AudioDiag.logError("HEED_ERROR", "reason=start_failed why=$why")
      }
      return
    }

    if (MicroWakeWord.modelPresent(this)) {
      if (microWakeWord?.isRunning() == true) { setMicOwner("WAKE", "already_armed"); return }
      if (microWakeWord == null) {
        microWakeWord = MicroWakeWord(
          applicationContext,
          onDetected = { score, wavPath -> onNativeWakeDetected(score, wavPath) },
          log = { stage, fields -> AudioDiag.log(this, stage, fields) },
        )
      }
      AudioDiag.log(this, "WAKE_ENGINE", "engine=MICROWAKEWORD why=$why")
      AudioDiag.log(this, "NWW_INIT", "asset=${MicroWakeWord.MODEL_ASSET}")
      if (microWakeWord?.start() == true) {
        setMicOwner("WAKE", "native_armed")
        AudioDiag.log(this, "NWW_REARM", "why=$why")
      } else {
        AudioDiag.logError("NWW_ERROR", "reason=start_failed why=$why")
      }
      return
    }

    // No cloud-STT wake fallback. Until the actual Benson model is bundled, passive wake remains
    // unavailable; manual command capture remains a separate explicit action.
  }

  private fun suspendNativeWake(reason: String) {
    if (heedWakeWord?.isRunning() == true) {
      AudioDiag.log(this, "HEED_SUSPEND_REQUEST", "reason=$reason")
      try { heedWakeWord?.stop() } catch (_: Exception) {}
    }
    if (microWakeWord?.isRunning() == true) {
      AudioDiag.log(this, "NWW_SUSPEND", "reason=$reason")
      try { microWakeWord?.stop() } catch (_: Exception) {}
    }
    if (nativeCloudWake?.isRunning() == true) {
      try { nativeCloudWake?.stop() } catch (_: Exception) {}
    }
  }

  // Called from the module. owner ∈ {COMMAND_STT, TTS, CALL} → suspend; {WAKE, PORCUPINE, NONE} → re-arm.
  fun nativeWakeSetOwner(owner: String) {
    mainHandler.post {
      when (owner) {
        // W-1 TASK A [WAKE_DURING_PLAYBACK] — no suspend: Heed must keep listening through
        // BENSON's own TTS so "Benson" said mid-playback can barge in. Revert: fold "TTS" back
        // into the branch below (suspendNativeWake(owner); no startHeedEngineOnly call).
        "TTS" -> {
          AudioDiag.log(this, "WAKE_AUDIO_BLOCKED", "reason=js_request owner=$owner note=heed_kept_running")
          setMicOwner(owner, "js_request")
          startHeedEngineOnly("tts_barge_in_listen")
        }
        "COMMAND_STT", "CALL" -> {
          AudioDiag.log(this, "WAKE_AUDIO_BLOCKED", "reason=js_request owner=$owner")
          suspendNativeWake(owner)
          setMicOwner(owner, "js_request")
        }
        else -> { setMicOwner("NONE", "js_release"); armNativeWake("js_release") }
      }
    }
  }

  fun isNativeWakeRunning(): Boolean =
    heedWakeWord?.isRunning() == true || microWakeWord?.isRunning() == true || nativeCloudWake?.isRunning() == true

  fun isNativeWakeAudioActive(): Boolean =
    heedWakeWord?.isAudioInputActive() == true || microWakeWord?.isRunning() == true || nativeCloudWake?.isAudioInputActive() == true

  private fun onHeedWakeDetected(phrase: String, score: Float, wavPath: String, triggeredAtMs: Long) {
    mainHandler.post {
      if (isCallAudioBlockedNow()) {
        AudioDiag.log(this, "WAKE_RESULT_DROPPED", "reason=call_audio_active engine=heed")
        return@post
      }
      // FIX_WAKE_SELF_ECHO_GUARD_2 (2026-10-03, device-proven) — checked against triggeredAtMs
      // (the acoustic trigger instant, HeedWakeWord's phase=armed), not System.currentTimeMillis()
      // here: this mainHandler.post only runs once HeedWakeWord's OWN capture loop finishes
      // (phase=complete), which can lag the trigger by up to ~2s of trailing-silence collection.
      // FIX_WAKE_SELF_ECHO_GUARD_1's original check used "now" at THIS late point, so a stray
      // detection 106ms after the ack's TTS finished still slipped through once the lag pushed
      // "now" past the 1000ms guard window — device-proven: WAKE_VERIFY text="master" ok=false at
      // 11:44:11, same signature as the first incident. Checked before anything else: no
      // WAKE_SCORE log, no verify call, no rearm-refusal noise.
      if (SelfTtsGuard.isWithinGuard(triggeredAtMs, ttsSpeakingSince, ttsLastDoneAt)) {
        AudioDiag.log(this, "WAKE_IGNORED", "reason=self_tts score=${"%.3f".format(score)}")
        rearmWakeNative("self_tts_guard")
        return@post
      }
      // W-1 TASK C — measurement only: aecAvailable is a device-capability check, not proof AEC is
      // actually attached to this capture (it isn't, this round — see scope note above).
      // RUNDA_N5 — `score` is now the real trigger score (HeedWakeWord.kt fix), not the stale
      // last-chunk value this line used to log.
      lastHeedDetectScore = score
      lastHeedDetectAt = System.currentTimeMillis()
      AudioDiag.log(this, "WAKE_SCORE", "engine=heed score=${"%.3f".format(score)} aecAvailable=$aecAvailable aecAttached=${heedAec != null}")
      // RUNDA_WAKE_VERIFY (2026-10-03, user-approved) — device-proven today: 8/8 spoken "Da,
      // Master" ALL had STT_RESULT chars=0 (empty Deepgram transcript) from a normal ~2.7s clip —
      // HEED accepting ambient noise/conversation, not an actual "Benson". NOTHING audible or
      // visible happens until the clip is verified to actually contain the wake word. Revert:
      // delete this verify block and restore the direct suspendNativeWake/setMicOwner/openSession/
      // applyWakeForegroundPolicy/handleNativeCommandFlow sequence that used to run immediately.
      val acceptedAtMs = System.currentTimeMillis()
      Thread({
        val (verifyTranscript, connReused) = wakeVerifyTranscribe(wavPath)
        val verified = isWakeVerified(verifyTranscript, score)
        val resultLabel = if (verifyTranscript == null) "offline" else if (verified) "accepted" else "rejected"
        AudioDiag.log(
          this, "WAKE_VERIFY",
          "result=$resultLabel text=\"${verifyTranscript ?: ""}\" score=${"%.3f".format(score)} latencyMs=${System.currentTimeMillis() - acceptedAtMs} connReused=$connReused",
        )
        // FIX_WAKE_DATASET_1 (2026-10-03, user-directed) — "offline" is skipped: a timeout tells
        // us nothing about whether the clip was really "Benson", so it's never labeled either way.
        when (resultLabel) {
          "accepted" -> retainWakeClip(wavPath, "positive")
          "rejected" -> retainWakeClip(wavPath, "negative")
        }
        mainHandler.post {
          if (!verified) {
            rearmWakeNative("wake_verify_rejected")
            return@post
          }
          val bargeIn = micOwner == "TTS"
          if (bargeIn) AudioDiag.log(this, "WAKE_DURING_PLAYBACK", "action=barge_in owner=$micOwner")
          AudioDiag.log(this, "WAKE_DETECT", "engine=heed keyword=${phrase.replace(' ', '_')} score=${"%.3f".format(score)}")
          suspendNativeWake("COMMAND")
          setMicOwner("COMMAND_STT", "wake_detected")
          openSession(if (bargeIn) "barge_in" else "wake_detected", mayDuck = !bargeIn)
          applyWakeForegroundPolicy()
          AudioDiag.log(this, "WAKE_COMMAND_AUDIO_READY", "engine=heed path=${java.io.File(wavPath).name}")
          if (verifyTranscript != null) {
            // Verify's own transcript already covers this clip — no second Deepgram call.
            handleNativeCommandFlow(verifyTranscript, isFollowUp = false, originalPhrase = phrase, originalWavPath = wavPath)
          } else {
            // Accepted only via the score>=0.90 offline fallback — verify never got a transcript,
            // so we still don't know the command text. One normal (uncapped) transcribe call here.
            Thread({
              val transcript = nativeTranscribe(wavPath)
              mainHandler.post {
                handleNativeCommandFlow(transcript ?: "", isFollowUp = false, originalPhrase = phrase, originalWavPath = wavPath)
              }
            }, "benson-native-cmd-stt").start()
          }
        }
      }, "benson-wake-verify").start()
    }
  }

  // ADAOS K-1 (2026-10-03, user-directed) — headset-button long press (≥600ms). Mirrors
  // onHeedWakeDetected's ACCEPTED branch (suspend wake, open session, ack+listen) but skips
  // wake-verify entirely: a physical button press needs no acoustic/STT confirmation, it IS the
  // confirmation. No transcript to route, so this always goes through speakNativeAckThenCapture
  // (same as "Benson" alone) rather than handleNativeCommandFlow directly.
  private fun triggerWakeFromHeadsetButton() {
    if (isCallAudioBlockedNow()) {
      AudioDiag.log(this, "WAKE_RESULT_DROPPED", "reason=call_audio_active engine=headset_button")
      return
    }
    AudioDiag.log(this, "WAKE_DETECT", "engine=headset_button")
    suspendNativeWake("COMMAND")
    setMicOwner("COMMAND_STT", "headset_long_press")
    openSession("headset_long_press", mayDuck = true)
    applyWakeForegroundPolicy()
    speakNativeAckThenCapture()
  }

  // FIX_WAKE_VERIFY_LATENCY_1 (2026-10-03, user-directed, device-proven) — 1200ms was too tight:
  // 6/10 of today's real "Benson" utterances timed out at 1200-1215ms (Deepgram's own round trip
  // on this network), not a matcher/keyterm problem — all 4 that got a transcript back said
  // "benson" cleanly. 2000ms covers every observed latency (max seen: 1215ms) with margin.
  // Single reused client (unchanged — this was always one instance) + connectionPool so a kept-
  // alive socket from a recent call (including the warm-up ping) can be reused, skipping a fresh
  // TCP+TLS handshake. Revert: callTimeout back to 1200.
  private val wakeVerifyHttpClient = OkHttpClient.Builder()
    .callTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
    .connectionPool(okhttp3.ConnectionPool(1, 5, java.util.concurrent.TimeUnit.MINUTES))
    .build()

  // FIX_WAKE_VERIFY_LATENCY_1 — tracks whether THIS call reused a pooled connection (connectStart
  // firing means a fresh TCP+TLS handshake was needed) for the WAKE_VERIFY connReused= log field.
  private class ConnReuseTracker : okhttp3.EventListener() {
    @Volatile var newConnection = false
    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
      newConnection = true
    }
  }

  // FIX_WAKE_VERIFY_LATENCY_1 — periodic lightweight request (service start + every ~4 min) to
  // keep a TLS connection to Deepgram's host warm in the pool above, so a real verify call during
  // a wake doesn't pay for the handshake. HEAD is expected to 404/405 — irrelevant, the handshake
  // and pooled connection are the only things that matter. Revert: remove this function and its
  // two call sites (onCreate scheduling, onDestroy cleanup).
  // FIX_WAKE_VERIFY_LATENCY_2 (2026-10-03, user-directed, device-proven) — 4 min was too long:
  // today's device test showed connReused=true only for calls within ~90s of each other; every
  // gap of 20-80s past that lost the pooled connection (Deepgram's server-side idle timeout is
  // shorter than our re-ping interval, or shorter than our client pool's 5min). 30s keeps the
  // round trip gap short enough that a real wake almost always lands inside the kept-alive window.
  private val WAKE_VERIFY_WARM_INTERVAL_MS = 30_000L
  private val wakeVerifyWarmRunnable = object : Runnable {
    override fun run() {
      Thread({
        try {
          val req = Request.Builder().url("https://api.deepgram.com/v1/listen").head().build()
          wakeVerifyHttpClient.newCall(req).execute().close()
          AudioDiag.log(this@BensonForegroundService, "WAKE_VERIFY_WARM", "ok=true")
        } catch (e: Exception) {
          AudioDiag.log(this@BensonForegroundService, "WAKE_VERIFY_WARM", "ok=false type=${e.javaClass.simpleName}")
        }
      }, "benson-wake-verify-warm").start()
      if (isRunning) mainHandler.postDelayed(this, WAKE_VERIFY_WARM_INTERVAL_MS)
    }
  }

  // FIX_WAKE_VERIFY_LATENCY_1 — REVERTED (2026-10-03, device-proven regression): trimming to a
  // 1.0s window centered on HeedWakeWord.PRE_ROLL_SAMPLES produced 11/11 empty transcripts on the
  // next device test (previously 4/10 clean "benson" transcripts with the full buffer) — the
  // window missed the actual word, likely because detection fires some distance after the word
  // ends (preprocessing/consecutive-frames lag), not exactly at PRE_ROLL_SAMPLES. Per CLAUDE.md
  // "pasul înapoi e obligatoriu": reverted to the full untrimmed buffer rather than guess a new
  // offset without proof. The 3 other latency changes (timeout, connReused, warm-up) are unaffected
  // and stay.
  // FIX_WAKE_DATASET_1 (2026-10-03, user-directed) — preparation for a future local verifier, NOT
  // the verifier itself: every wake-verify WAV, auto-labeled by today's own verdict (accepted →
  // positive, rejected-with-empty-or-irrelevant-transcript → negative). Local only — filesDir, this
  // app's own private storage, never uploaded. Capped at 300 clips, oldest evicted first by file
  // mtime. "offline" is never saved (see call site above). Revert: delete this function, its call
  // site, and the wake_dataset folder.
  private val WAKE_DATASET_MAX_CLIPS = 300

  private fun retainWakeClip(wavPath: String, label: String) {
    try {
      val src = File(wavPath)
      if (!src.isFile) return
      val dir = File(filesDir, "wake_dataset").apply { if (!exists()) mkdirs() }
      val dest = File(dir, "wake_${System.currentTimeMillis()}_$label.wav")
      src.copyTo(dest, overwrite = true)
      val clips = dir.listFiles { f -> f.isFile && f.name.startsWith("wake_") }
        ?.sortedBy { it.lastModified() } ?: return
      if (clips.size > WAKE_DATASET_MAX_CLIPS) {
        clips.take(clips.size - WAKE_DATASET_MAX_CLIPS).forEach { it.delete() }
      }
      AudioDiag.log(this, "WAKE_DATASET", "label=$label count=${minOf(clips.size, WAKE_DATASET_MAX_CLIPS)}")
    } catch (e: Exception) {
      AudioDiag.logError("WAKE_DATASET_ERROR", "type=${e.javaClass.simpleName}")
    }
  }

  private fun wakeVerifyTranscribe(wavPath: String): Pair<String?, Boolean> {
    val bytes = try { File(wavPath).readBytes() } catch (_: Exception) { null } ?: return null to false
    if (bytes.isEmpty()) return null to false
    val dgKey = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      .getString("wake_stt_deepgram_api_key", "") ?: ""
    if (dgKey.isBlank()) return null to false
    // FIX_WAKE_VERIFY_KEYTERM_1 (2026-10-03, user-directed) — Deepgram Keyterm Prompting
    // (confirmed via developers.deepgram.com/docs/keyterm: param name `keyterm`, Nova-3,
    // multilingual-compatible) biases the model toward writing the wake name correctly instead of
    // guessing "bandswon" on an isolated single word. Fixes the cause (STT output), not the
    // symptom (a looser matcher). Only on this wake-verify call, not postToDeepgramCmd's
    // full-command STT. Reads NativeCloudWake.currentWakeName — the one authoritative source
    // (Settings), not a hardcoded "Benson" — so a renamed wake word gets boosted too.
    // Revert: drop "&keyterm=..." from this URL.
    val keyterm = java.net.URLEncoder.encode(NativeCloudWake.currentWakeName(this), "UTF-8")
    val url = "https://api.deepgram.com/v1/listen?model=nova-3&language=${cmdLanguage()}&keyterm=$keyterm"
    val tracker = ConnReuseTracker()
    val client = wakeVerifyHttpClient.newBuilder().eventListener(tracker).build()
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $dgKey")
      .post(bytes.toRequestBody("audio/wav".toMediaType())).build()
    return try {
      client.newCall(request).execute().use { resp ->
        val connReused = !tracker.newConnection
        if (!resp.isSuccessful) return null to connReused
        val json = resp.body?.string() ?: return null to connReused
        val alt = JSONObject(json).optJSONObject("results")?.optJSONArray("channels")
          ?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
        (alt?.optString("transcript", "") ?: "").trim() to connReused
      }
    } catch (_: Exception) { null to !tracker.newConnection }
  }

  // FIX_WAKE_VERIFY_FAILSAFE_1 (2026-10-03, device-proven) — the original heedScore>=0.90
  // fallback is unsafe on this device: today's false triggers (confirmed empty STT, see
  // WAKE_SCORE/STT_RESULT chars=0 pairs in log) scored 0.795-0.984, overlapping real detections
  // entirely. An offline/timeout verify now REJECTS — silence over a false "Da, Master", per
  // CLAUDE.md doctrine "Tăcerea e implicită". Revert: restore "heedScore >= 0.90f".
  //
  // FIX_WAKE_VERIFY_FUZZY_1 (2026-10-03, device-proven) — exact-word whitelist rejected a REAL
  // "Benson" (score 0.956): Deepgram transcribed an isolated single word, with no sentence
  // context, as "bandswon" (distance 3 from "benson"). Known-bad transcripts (ambient-noise false
  // triggers) were always empty (distance 6). Levenshtein against each word gives margin for STT
  // noise on isolated words without reopening the false-accept door. Revert: restore the
  // WAKE_VERIFY_WORDS.any { norm.contains(it) } check.
  // Delegates to WakeVerifyMatcher (plain Kotlin, zero Context dependency) so the logic has a
  // JUnit test (WakeVerifyMatcherTest) independent of this Service. heedScore is unused — kept in
  // the signature only to avoid touching the call site above. wakeName comes from
  // NativeCloudWake.currentWakeName — same source keyterm uses, same source HeedWakeWord already
  // trusts for model selection — not hardcoded "Benson".
  private fun isWakeVerified(transcript: String?, heedScore: Float): Boolean =
    WakeVerifyMatcher.isWakeVerified(transcript, NativeCloudWake.currentWakeName(this))

  // ── NATIVE_CMD_1 (2026-10-02, user-approved) — native capture → STT → simple-command routing ──
  // Full chain now lives here, per CLAUDE.md's wake contract: wake, ack, session, command capture,
  // STT, and simple-command execution are native; JS only ever sees what this native chain decides
  // it can't handle itself. Revert: restore the pre-round onHeedWakeDetected (git history) and
  // delete everything in this section plus its call sites.
  private val cmdHttpClient = OkHttpClient()
  private val WAKE_VARIANTS_NATIVE = setOf("benson", "bensen", "benzon", "bension", "bensons")
  private val OPEN_APP_PATTERN = Regex("^\\s*deschide(?:-mi)?\\s+(.+?)\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE)

  // FIX_WA_NORMALIZE_BEFORE_MATCH_1 (2026-10-03, user-directed, device-proven) — smart_format
  // (added this round for WA_COMPOSE) now adds punctuation Deepgram never used to emit — a trailing
  // "," or "." landing inside a non-greedy capture group could silently corrupt a name/instruction.
  // Punctuation stripping added here (not a separate function): every existing caller already
  // wants case/diacritic-insensitive comparison, and stripping .,!?;"'«» only ever helps that,
  // never hurts it (confirmation classifier's \b-bounded regexes were already punctuation-safe).
  // ":" deliberately NOT stripped (2026-10-03, device-proven regression) — "scrie-i lui Hannah:
  // text" relies on the literal colon as the name/text separator (WA_MESSAGE_PATTERNS pattern A);
  // stripping it merged name and text with no anchor left ("hannah text", no "ca"/":" token),
  // which pattern D's bare-name fallback then swallowed whole as a corrupted contact name.
  private fun normalizeForMatch(s: String): String =
    TextNormalization.stripSymbolsAndEmoji(
      java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[.,!?;\"'«»]"), ""),
    ).lowercase().trim()

  // Mirrors app/index.tsx's stripWakeWord() exactly (same WAKE_VARIANTS, same position-anchored
  // first-word match) — only used on the ORIGINAL wake buffer, which still has "Benson" in it. The
  // follow-up capture's transcript never does, so handleNativeCommandFlow never calls this for it.
  private fun stripWakeWordNative(text: String): String {
    val raw = text.trim()
    if (raw.isEmpty()) return ""
    val rawWords = raw.split(Regex("\\s+"))
    val normWords = normalizeForMatch(raw).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (normWords.isEmpty()) return ""
    val firstTwo = normWords.take(2).joinToString(" ")
    val dropCount = when {
      firstTwo == "hey benson" || firstTwo == "hei benson" -> 2
      WAKE_VARIANTS_NATIVE.contains(normWords[0]) -> 1
      else -> 0
    }
    if (dropCount == 0) return ""
    return rawWords.drop(dropCount).joinToString(" ").trim()
  }

  private fun cmdLanguage(): String {
    val configured = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      .getString("stt_language", null)?.substringBefore('-')?.lowercase()
    return if (configured == "de" || configured == "ro") configured else "ro"
  }

  // Deepgram first (same credential NativeCloudWake already reads — pushed down from JS at app
  // open / key save, see setWakeDeepgramCredentials in app/index.tsx), Groq fallback (same
  // credential setNativeWakeCredentials already pushes). Never logs a key. Returns null only if
  // BOTH fail/are unconfigured — caller treats that exactly like an empty transcript.
  private fun nativeTranscribe(wavPath: String): String? {
    val bytes = try { File(wavPath).readBytes() } catch (_: Exception) { null } ?: return null
    if (bytes.isEmpty()) return null
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val dgKey = prefs.getString("wake_stt_deepgram_api_key", "") ?: ""
    if (dgKey.isNotBlank()) {
      val t = postToDeepgramCmd(bytes, dgKey)
      if (t != null) return t
    }
    val groqKey = prefs.getString("wake_stt_api_key", "") ?: ""
    if (groqKey.isBlank()) return null
    return postToGroqCmd(bytes, groqKey, prefs)
  }

  private fun postToDeepgramCmd(wav: ByteArray, apiKey: String): String? {
    AudioDiag.log(this, "STT_REQUEST", "provider=deepgram bytes=${wav.size}")
    // ADAOS WA-3 (2026-10-03, user-directed) — exact param names confirmed via
    // developers.deepgram.com/docs/smart-format: "no need to also set punctuate=true" when
    // smart_format=true (it already enables punctuation) — adding both would be redundant, so
    // only smart_format is sent. This is what feeds WA_COMPOSE's brain step and, on brain timeout,
    // the spoken fallback text directly — needs real punctuation either way.
    val url = "https://api.deepgram.com/v1/listen?model=nova-3&language=${cmdLanguage()}&smart_format=true"
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $apiKey")
      .post(wav.toRequestBody("audio/wav".toMediaType())).build()
    return try {
      cmdHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) { AudioDiag.logError("STT_RESULT", "provider=deepgram http=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        val alt = JSONObject(json).optJSONObject("results")?.optJSONArray("channels")
          ?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
        val transcript = (alt?.optString("transcript", "") ?: "").trim()
        AudioDiag.log(this, "STT_RESULT", "provider=deepgram text=\"$transcript\" chars=${transcript.length}")
        transcript
      }
    } catch (e: Exception) {
      AudioDiag.logError("STT_ERROR", "provider=deepgram type=${e.javaClass.simpleName}")
      null
    }
  }

  private fun postToGroqCmd(wav: ByteArray, apiKey: String, prefs: android.content.SharedPreferences): String? {
    AudioDiag.log(this, "STT_REQUEST", "provider=groq bytes=${wav.size}")
    val baseUrl = (prefs.getString("wake_stt_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.groq.com/openai/v1").trimEnd('/')
    val model = prefs.getString("wake_stt_model", "")?.takeIf { it.isNotBlank() } ?: "whisper-large-v3-turbo"
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
      .addFormDataPart("model", model)
      .addFormDataPart("language", cmdLanguage())
      .addFormDataPart("response_format", "json")
      .build()
    val request = Request.Builder().url("$baseUrl/audio/transcriptions")
      .addHeader("Authorization", "Bearer $apiKey").post(body).build()
    return try {
      cmdHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) { AudioDiag.logError("STT_RESULT", "provider=groq http=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        val text = JSONObject(json).optString("text", "").trim()
        AudioDiag.log(this, "STT_RESULT", "provider=groq text=\"$text\" chars=${text.length}")
        text
      }
    } catch (e: Exception) {
      AudioDiag.logError("STT_ERROR", "provider=groq type=${e.javaClass.simpleName}")
      null
    }
  }

  // "deschide <app>" only (per approved scope) — fuzzy label match against PackageManager's own
  // launchable-activity list, no hardcoded app list. Returns the spoken/shown confirmation text
  // ("Deschid WhatsApp.") only on an actual launched Intent, null otherwise.
  // Device-proven 2026-10-02: "deschide whatsapp și dă-i un mesaj lui Hannah" matched via
  // findLaunchablePackage's contains() fallback (the phrase CONTAINS "whatsapp"), opened WhatsApp,
  // and silently dropped the message half — no error, no handoff, the user never knew the second
  // half was ignored. A compound command ("X și Y") is never just "deschide X"; refusing it here
  // sends the WHOLE sentence to JS's existing mission pipeline instead, which already knows how to
  // open-and-message in one mission.
  private val COMPOUND_COMMAND_PATTERN = Regex("\\b(si|apoi|dupa\\s+aia|dupa\\s+care)\\b")

  // FIX_YT_OPEN_SEARCH_1 (2026-10-03, device-proven regression) — "deschide youtube și caută X"
  // worked yesterday, broke today: COMPOUND_COMMAND_PATTERN (added for the WhatsApp
  // message-swallowing fix) now also declines this single-intent phrase, and JS (asleep in
  // background) never picks it up either. Checked BEFORE tryNativeOpenApp so the WA-protecting
  // guard is untouched — this is a narrow exemption, not a guard rewrite, reusing the existing
  // launchYoutubeSearch (real open+search, same as tryNativeYoutube). Revert: delete this pattern,
  // this function, and its call site in handleNativeCommandFlow.
  private val YT_OPEN_AND_SEARCH_PATTERN = Regex(
    "^\\s*deschide(?:-mi)?\\s+you\\s*tube\\s+(?:si|apoi)\\s+(?:cauta|cautam|cauta-mi)\\s+(.+?)\\s*[.!?]*\\s*$",
    RegexOption.IGNORE_CASE,
  )

  // RUNDA SPEAK-1 (2026-10-04, user-directed, device-proven on highway) — "deschide YouTube și
  // caută X" used to just fire the search Intent and show silent bubble text ("Caut X pe
  // YouTube.") with no speech, no confirmation, no verification — device-proven today: "a gasit
  // dar nu spune nimic". Converted to the same speak+confirm+verify shape as tryYoutubeSearchConfirm,
  // Boolean return (self-handling), so BOTH phrasings ("deschide YouTube și caută X" and bare
  // "caută X" with YouTube already foreground) go through one path. Results land via Intent
  // (ACTION_SEARCH, already populated) — locateFirstResultOnScreen extracts directly, no
  // search-box activation (that would re-tap the search icon and blank the Intent's own results).
  private fun tryNativeYoutubeOpenSearch(command: String): Boolean {
    val normalized = normalizeForMatch(command)
    val m = YT_OPEN_AND_SEARCH_PATTERN.find(normalized) ?: return false
    val query = expo.modules.accessibility.AppMentionStripper.strip(m.groupValues[1].trim())
    if (query.isBlank()) return false
    if (isCarModeActive()) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=yt_open_and_search_refused reason=car_mode target=\"$query\"")
      speak("Nu pornesc video cât conduci.")
      return true
    }
    AudioDiag.log(this, "NATIVE_ROUTE", "action=yt_open_and_search target=\"$query\"")
    if (!launchYoutubeSearch(query)) return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) { speak("Serviciul de accesibilitate nu e disponibil."); return true }
    svc.runOnServiceScope {
      kotlinx.coroutines.delay(1800)
      val title = svc.locateFirstResultOnScreen(query)
      mainHandler.post {
        if (title == null) {
          AudioDiag.log(this, "YOUTUBE_SEARCH_CONFIRM", "query=\"$query\" found=false")
          speak("N-am găsit $query pe YouTube.")
        } else {
          AudioDiag.log(this, "YOUTUBE_SEARCH_CONFIRM", "query=\"$query\" found=true title=\"$title\"")
          pendingYoutubePlay = PendingYoutubePlay(query)
          val question = "Am găsit $title. Îl pornesc?"
          updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
          speak(question)
        }
      }
    }
    return true
  }

  private fun tryNativeOpenApp(command: String): String? {
    val m = OPEN_APP_PATTERN.find(command) ?: return null
    val target = normalizeForMatch(m.groupValues[1])
    if (target.isBlank()) return null
    if (COMPOUND_COMMAND_PATTERN.containsMatchIn(target)) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=open_app_declined reason=compound_command target=\"$target\"")
      return null
    }
    // RUNDA CAR-1 — "deschide youtube" în modul mașină e tot acces la video; refuzat. Nu afectează
    // deschiderea YouTube din afara mașinii (testul înghețat din CLAUDE.md rulează fără Android Auto).
    if (target == "youtube" && isCarModeActive()) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=open_app_refused reason=car_mode target=\"$target\"")
      return "Nu pornesc video cât conduci."
    }
    AudioDiag.log(this, "NATIVE_ROUTE", "action=open_app target=\"$target\"")
    val pkg = findLaunchablePackage(target)
    if (pkg == null) {
      AudioDiag.log(this, "APP_LAUNCH", "pkg=none ok=false reason=not_found")
      return null
    }
    val ok = launchPackageNative(pkg)
    AudioDiag.log(this, "APP_LAUNCH", "pkg=$pkg ok=$ok")
    if (!ok) return null
    val label = try {
      packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { target }
    return "Deschid $label."
  }

  // RUNDA S-1 TASK 1 (2026-10-03, user-directed) — "caută/deschide aplicația de <categorie>"
  // ("parcare", "taxi"...): a small category->token dictionary; unknown categories fall back to
  // matching the raw word itself (so "aplicația de spotify" still resolves without an entry).
  // One installed match -> opens directly (no confirm, same convention as tryNativeOpenApp's exact
  // match). Multiple -> spoken numbered list (PendingAppSearch, same ordinal-answer doctrine as
  // WA's PendingWaDisambiguation/FIX_WA_DISAMBIGUATION_LOOP_1 2-strike give-up). Zero -> Play
  // Store via market://search, never silence. All via Intent, no Accessibility.
  private val APP_CATEGORY_DICTIONARY = mapOf(
    "parcare" to listOf("park", "parcare", "easypark", "paybyphone", "parknow", "parkopedia"),
    "taxi" to listOf("taxi", "uber", "bolt", "clever"),
    "banca" to listOf("banca", "bank", "revolut"),
    "vreme" to listOf("vreme", "weather", "accuweather"),
  )

  private fun findAppsByCategory(category: String): List<Pair<String, String>> {
    val tokens = APP_CATEGORY_DICTIONARY[category] ?: listOf(category)
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { return emptyList() }
    val seen = linkedSetOf<String>()
    val out = mutableListOf<Pair<String, String>>()
    for (ri in apps) {
      val pkg = ri.activityInfo.packageName
      if (!seen.add(pkg)) continue
      val normLabel = try { normalizeForMatch(ri.loadLabel(pm).toString()) } catch (_: Exception) { continue }
      if (tokens.any { normLabel.contains(it) || pkg.contains(it) }) {
        val realLabel = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
        out.add(realLabel to pkg)
      }
    }
    return out
  }

  private data class PendingAppSearch(val candidates: List<Pair<String, String>>, var noCount: Int = 0)
  @Volatile private var pendingAppSearch: PendingAppSearch? = null

  private fun finishAppLaunch(label: String, pkg: String, commandForBubble: String) {
    val ok = launchPackageNative(pkg)
    AudioDiag.log(this, "APP_LAUNCH", "pkg=$pkg ok=$ok")
    val resp = if (ok) "Deschid $label." else "N-am putut deschide $label."
    openSession("native_cmd_executed", mayDuck = true)
    setMicOwner("NONE", "native_cmd_executed")
    updateBubbleNative(resp, commandForBubble, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
    hideWakeRingNative()
    rearmWakeNative("app_launch_done")
  }

  private fun tryNativeAppCategorySearch(command: String): Boolean {
    val category = AppCategoryMatcher.extractCategory(command) ?: return false
    AudioDiag.log(this, "NATIVE_ROUTE", "action=app_category_search target=\"$category\"")
    val found = findAppsByCategory(category)
    when {
      found.size == 1 -> finishAppLaunch(found[0].first, found[0].second, command)
      found.size > 1 -> {
        val top = found.take(WA_ORDINAL_WORDS.size - 1)
        pendingAppSearch = PendingAppSearch(top)
        val spoken = top.mapIndexed { i, pair -> "${WA_ORDINAL_WORDS[i + 1]}, ${pair.first}" }.joinToString(". ")
        val question = "Am găsit mai multe: $spoken. Care?"
        updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
        speakNativeFallbackThenCapture(question)
      }
      else -> {
        val encoded = try { java.net.URLEncoder.encode(category, "UTF-8") } catch (_: Exception) { category }
        val ok = try {
          startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$encoded"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          )
          true
        } catch (e: Exception) {
          AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=app_category_search type=${e.javaClass.simpleName}")
          false
        }
        AudioDiag.log(this, "APP_LAUNCH", "pkg=play_store ok=$ok")
        val resp = if (ok) "N-am găsit instalată; am căutat în Magazin." else "Nu am găsit $category."
        openSession("native_cmd_executed", mayDuck = true)
        setMicOwner("NONE", "native_cmd_executed")
        updateBubbleNative(resp, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
        hideWakeRingNative()
        rearmWakeNative("app_launch_done")
      }
    }
    return true
  }

  private fun handlePendingAppSearch(rawAnswer: String) {
    val pending = pendingAppSearch ?: return
    val n = parseWaOrdinal(rawAnswer)
    val chosen = n?.let { pending.candidates.getOrNull(it - 1) }
    if (chosen == null) {
      pending.noCount += 1
      if (pending.noCount >= 2) {
        pendingAppSearch = null
        AudioDiag.log(this, "SESSION", "close reason=app_search_unresolved")
        closeSession("app_search_unresolved")
        setMicOwner("NONE", "app_search_unresolved")
        rearmWakeNative("app_search_unresolved")
      } else {
        speakNativeFallbackThenCapture("Nu am înțeles numărul. Care?")
      }
      return
    }
    pendingAppSearch = null
    finishAppLaunch(chosen.first, chosen.second, rawAnswer)
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════════
  // RUNDA G-1 (2026-10-03, user-directed) — "mâna universală". The actual node search/click/scroll/
  // read lives in BensonAccessibilityService.runUniversalHandCommand (Context-dependent); this is
  // just the voice-command wiring + confirmation gate, same shape as PendingAppSearch above.
  // ══════════════════════════════════════════════════════════════════════════════════════════════
  private data class PendingControlAction(val cmd: expo.modules.accessibility.UniversalHandCommand, var noCount: Int = 0)
  @Volatile private var pendingControlAction: PendingControlAction? = null

  // RUNDA SPEAK-1 (2026-10-04, user-directed) — YouTube's own confirm gate: "Am găsit X. Îl
  // pornesc?" before clicking anything, same shape as PendingControlAction above.
  private data class PendingYoutubePlay(val query: String, var noCount: Int = 0)
  @Volatile private var pendingYoutubePlay: PendingYoutubePlay? = null

  private fun finishControlResult(result: expo.modules.accessibility.BensonAccessibilityService.ControlResult, commandForBubble: String) {
    val resp = result.message.ifBlank { if (result.ok) "Am făcut." else "Nu am putut." }
    openSession("native_cmd_executed", mayDuck = true)
    setMicOwner("NONE", "native_cmd_executed")
    updateBubbleNative(resp, commandForBubble, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
    hideWakeRingNative()
    rearmWakeNative("control_done")
  }

  // Play/pauză/stop/următorul/înapoi: MediaSession (existing tryNativeMediaControl) ÎNTÂI, abia
  // apoi butonul de pe ecran — per spec. cmd.target is the bare control word ("pauza", "play",
  // "urmatorul"...), which is exactly what MEDIA_CTL_PATTERN already expects, so this reuses
  // tryNativeMediaControl verbatim instead of re-implementing media control for this phrasing too.
  // RUNDA C-1 (2026-10-04, user-directed) — "închide"/"ieși"/"acasă"/"înapoi", oricând, clasa LIBER.
  private val GLOBAL_NAV_PATTERN = Regex("^(inchide|iesi|acasa|inapoi)$")

  private fun tryGlobalNav(command: String): Boolean {
    val n = normalizeForMatch(command)
    val m = GLOBAL_NAV_PATTERN.find(n) ?: return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) { speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil."); return true }
    val action = if (m.groupValues[1] == "inapoi") "back" else "home"
    val ok = svc.performGlobalNav(action)
    AudioDiag.log(this, "NATIVE_ROUTE", "action=global_nav target=\"$action\" ok=$ok")
    openSession("native_cmd_executed", mayDuck = true)
    setMicOwner("NONE", "native_cmd_executed")
    updateBubbleNative(if (ok) "" else "Nu am putut.", command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
    hideWakeRingNative()
    rearmWakeNative("global_nav_done")
    return true
  }

  // FIX_G1_MEDIA_WRONG_APP_1 (2026-10-04, device-proven) — "apasă pe play" cu YouTube pe ecran a
  // pornit Spotify în schimb: MediaTransport.control(ctx, "", "play") n-are niciun concept de
  // "aplicația din prim-plan" — alege orice sesiune STATE_PLAYING, altfel prima găsită, ceea ce a
  // fost sesiunea Spotify rămasă din testul anterior. Fix: când există o aplicație reală în prim-
  // plan (nu launcher/BENSON însuși), ținta MediaSession e FILTRATĂ pe pachetul ei întâi; fără
  // filtru doar dacă nu există o asemenea aplicație — exact comportamentul vechi, dovedit, pentru
  // "pauză" rostit simplu cu BENSON în fundal.
  private val MEDIA_ACTION_FOR_INTENT = mapOf(
    "play" to "play", "pause" to "pause", "stop" to "stop",
    "next" to "next", "prev" to "previous",
    "seek_forward" to "seek_forward", "seek_back" to "seek_back",
  )

  // RUNDA MUSIC-1 (2026-10-04, user-directed) — "pune X"/"cântă X"/"caută X" în aplicații de muzică
  // = caută + selectează primul rezultat de tip piesă + play, confirmat DOAR prin metadata
  // MediaSession (titlu/artist), niciodată "gata" fără verificare. YouTube (video): doar "pune"/
  // "cântă" pornesc selectarea; "caută" rămâne neatins (doar rezultate, drumul vechi, dovedit).
  // YouTube Music omis deliberat — zero ancore dovedite pe dispozitiv pentru el.
  private fun tryMusicPlay(command: String): Boolean {
    val parsed = expo.modules.accessibility.MusicPlayCommandMatcher.parse(command) ?: return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance ?: return false
    val fg = svc.currentForegroundPackageLive() ?: return false
    if (fg == packageName || fg == launcherPackage()) return false
    // RUNDA MUSIC-2 (2026-10-04, user-directed) — "soluția generică, nativă, nu pe aplicații":
    // "pune"/"cântă" se aplică la ORICE aplicație reală din prim-plan, fără listă de pachete.
    // Singura excepție rămasă, deliberată: "caută X" pe YouTube (video) rămâne doar rezultate —
    // comportamentul vechi, dovedit, neatins; pentru restul, "caută" în Spotify specific înseamnă
    // tot play (singura aplicație pentru care asta a fost cerut explicit și testat).
    val eligible = when (parsed.verb) {
      expo.modules.accessibility.MusicPlayVerb.PUNE, expo.modules.accessibility.MusicPlayVerb.CANTA -> true
      expo.modules.accessibility.MusicPlayVerb.CAUTA -> fg == "com.spotify.music"
    }
    if (!eligible) return false

    val query = parsed.query
    AudioDiag.log(this, "NATIVE_ROUTE", "action=music_play target=\"$query\" pkg=$fg")
    svc.runOnServiceScope {
      // FIX_MUSIC2_STALE_METADATA_1 (2026-10-04, device-proven) — a query containing the artist's
      // own name (e.g. "pune like a prayer madonna" while a DIFFERENT Madonna track was already
      // playing) matched the OLD, unchanged MediaSession metadata on the very first poll (title
      // never actually updated) — a false positive. Require the title to also have CHANGED from
      // before this attempt, not just "matches", so a stale read is never confused with success.
      val titleBefore = expo.modules.notificationlistener.MediaTransport.metadata(applicationContext, fg)?.title
      fun metaMatches(): expo.modules.notificationlistener.MediaTransport.PlaybackMeta? {
        val meta = expo.modules.notificationlistener.MediaTransport.metadata(applicationContext, fg)
        if (meta == null) return null
        if (meta.title == titleBefore) return null
        return if (expo.modules.accessibility.TrackMetadataMatcher.matches(meta.title, meta.artist, query, query)) meta else null
      }

      // STRATUL 1 — MediaSession.playFromSearch, orice aplicație cu sesiune activă.
      val mediaAttempted = expo.modules.notificationlistener.MediaTransport.playFromSearch(applicationContext, fg, query)
      AudioDiag.log(this, "MUSIC_PLAY_STEP", "step=media_session attempted=$mediaAttempted pkg=$fg")
      var meta: expo.modules.notificationlistener.MediaTransport.PlaybackMeta? = null
      var layer = "media_session"
      // PROBA_PLAYFROMSEARCH_1 (2026-10-04, user-directed) — fereastră lărgită de la 2.5s la 6s:
      // fiecare altă întârziere de MediaSession măsurată azi pe acest telefon (stratul 2) a avut
      // nevoie de secunde reale, nu de sute de ms — 2.5s nu i-a dat niciodată lui playFromSearch o
      // șansă cinstită înainte să trecem la ecran. Raportul (NATIVE_ROUTE/MUSIC_PLAY_STEP/MUSIC_PLAY
      // cu layer=media_session) spune adevărul: dacă și cu 6s stratul 1 nu verifică, el chiar nu
      // pornește piesa pe acest Spotify — nu o presupunere, o măsurătoare.
      if (mediaAttempted) {
        val deadline = System.currentTimeMillis() + 6000
        while (meta == null && System.currentTimeMillis() < deadline) { meta = metaMatches(); if (meta == null) kotlinx.coroutines.delay(250) }
      }

      // STRATUL 2 — ecran, generic, doar dacă stratul 1 n-a fost confirmat.
      if (meta == null) {
        layer = "screen"
        // ADEVĂR ÎN AMBELE SENSURI (2026-10-04, user-directed) — verificarea reală rulează
        // NECONDIȚIONAT, indiferent ce a raportat genericSearchAndPlay (un pas intermediar poate
        // eșua — "no_input_found", "play_control ok=false" — iar piesa corectă tot pornește, de
        // exemplu dintr-o reîncercare sau o stare reziduală a aplicației). Rezultatul final e
        // DOAR starea reală MediaSession, niciodată boolean-ul intermediar.
        svc.genericSearchAndPlay(query)
        // FIX_MUSIC2_VERIFY_WINDOW_1 (2026-10-04, device-proven, lărgit a treia oară) — 5s, 8s,
        // 12s, TOATE au expirat chiar înainte ca Spotify să-și actualizeze metadata — confirmat de
        // trei ori direct cu `dumpsys media_session`, piesa REDÂND corect (titlu exact cerut) la
        // câteva sute de ms-secunde după ce fereastra noastră renunțase deja (încărcarea/
        // buffering-ul lui Spotify variază real, nu e un bug de-al nostru de rezolvat prin cod).
        // 18s — nu o slăbire a verificării (potrivire + schimbare de metadata rămân obligatorii),
        // doar timp real de încărcare, cu marjă peste cel mai lent caz observat (~13s).
        val deadline = System.currentTimeMillis() + 18000
        while (meta == null && System.currentTimeMillis() < deadline) { meta = metaMatches(); if (meta == null) kotlinx.coroutines.delay(300) }
      }

      val verified = meta != null
      AudioDiag.log(this, "MUSIC_PLAY", "query=\"$query\" selected=\"$query\" layer=$layer verified=$verified title=\"${meta?.title ?: ""}\" artist=\"${meta?.artist ?: ""}\"")
      mainHandler.post {
        val resp = if (verified) "Cântă $query." else "Am căutat $query, dar n-am reușit să pornesc piesa."
        finishControlResult(expo.modules.accessibility.BensonAccessibilityService.ControlResult(verified, resp), command)
      }
    }
    return true
  }

  private fun tryUniversalHand(command: String): Boolean {
    val cmd = expo.modules.accessibility.UniversalHandCommandMatcher.parse(command) ?: return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (cmd is expo.modules.accessibility.UniversalHandCommand.Press) {
      val mediaAction = MEDIA_ACTION_FOR_INTENT[cmd.knownIntent]
      if (mediaAction != null) {
        val liveFg = svc?.currentForegroundPackageLive()
        val targetPkg = if (liveFg != null && liveFg != packageName && liveFg != launcherPackage()) liveFg else ""
        val ok = expo.modules.notificationlistener.MediaTransport.control(applicationContext, targetPkg, mediaAction) ||
          (targetPkg.isNotEmpty() && expo.modules.notificationlistener.MediaTransport.control(applicationContext, "", mediaAction))
        if (ok) {
          openSession("native_cmd_executed", mayDuck = true)
          setMicOwner("NONE", "native_cmd_executed")
          val resp = when (mediaAction) {
            "play" -> "Redau."; "pause" -> "Pauză."; "stop" -> "Opresc."
            "next" -> "Trec mai departe."; "previous" -> "Mă întorc."
            "seek_forward" -> "Sar înainte."; else -> "Sar înapoi."
          }
          updateBubbleNative(resp, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
          hideWakeRingNative()
          rearmWakeNative("media_ctl_done")
          return true
        }
      }
    }
    if (cmd is expo.modules.accessibility.UniversalHandCommand.Search) {
      // No real foreground app (launcher/BENSON itself) — decline so the existing, betonat
      // tryNativeGeneralSearch (Google) keeps handling "caută X" with BENSON backgrounded.
      // FIX_G1_STALE_FOREGROUND_1 — live read (svc.currentForegroundPackageLive()), not the cached
      // currentForegroundPackage()/lastForegroundPackage, which lagged right after an app just opened.
      val fg = svc?.currentForegroundPackageLive()
      if (fg == null || fg == packageName || fg == launcherPackage()) return false
    }
    if (svc == null) {
      speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil.")
      return true
    }
    svc.runOnServiceScope {
      val result = svc.runUniversalHandCommand(cmd)
      mainHandler.post {
        if (result.needsConfirmation) {
          pendingControlAction = PendingControlAction(cmd)
          val question = "Apăs pe «${result.confirmLabel}»?"
          updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
          speakNativeFallbackThenCapture(question)
        } else {
          finishControlResult(result, command)
        }
      }
    }
    return true
  }

  private fun handlePendingControlAnswer(rawAnswer: String) {
    val pending = pendingControlAction ?: return
    val verdict = classifyConfirmationNative(rawAnswer)
    AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
    when (verdict) {
      "YES" -> {
        pendingControlAction = null
        val svc = expo.modules.accessibility.BensonAccessibilityService.instance
        if (svc == null) { speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil."); return }
        svc.runOnServiceScope {
          val result = svc.runUniversalHandCommand(pending.cmd, skipConfirmGate = true)
          mainHandler.post { finishControlResult(result, rawAnswer) }
        }
      }
      "NO" -> {
        pendingControlAction = null
        AudioDiag.log(this, "SESSION", "close reason=control_confirm_no")
        closeSession("control_confirm_no")
        setMicOwner("NONE", "control_confirm_no")
        rearmWakeNative("control_confirm_no")
      }
      else -> {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingControlAction = null
          AudioDiag.log(this, "SESSION", "close reason=control_confirm_unresolved")
          closeSession("control_confirm_unresolved")
          setMicOwner("NONE", "control_confirm_unresolved")
          rearmWakeNative("control_confirm_unresolved")
        } else {
          speakNativeFallbackThenCapture("Da sau nu?")
        }
      }
    }
  }

  // RUNDA SPEAK-1 (2026-10-04, user-directed, device-proven on highway) — "caută X"/"pune X" cu
  // YouTube în prim-plan: ANUNȚĂ ce a găsit și ÎNTREABĂ înainte să apese ceva, spre diferență de
  // MUSIC-2 (Spotify), care pornește direct. Verbul CAUTA rămâne neeligibil pe YouTube în
  // tryMusicPlay (fg == "com.spotify.music" only), deci nu există conflict de rutare pentru "caută
  // X" — verificat în cod, nu presupus.
  private val MENTIONS_YOUTUBE_PATTERN = Regex("\\byou\\s*tube\\b", RegexOption.IGNORE_CASE)

  private fun tryYoutubeSearchConfirm(command: String): Boolean {
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance ?: return false
    val normalized = normalizeForMatch(command)
    val m = CONTEXT_SEARCH_PATTERN.find(normalized) ?: return false
    val rawQuery = m.groupValues[1].trim()
    val fg = svc.currentForegroundPackageLive()
    val youtubeForeground = fg == "com.google.android.youtube"
    // FIX_YT_CONFIRM_FOREGROUND_RACE_1 (2026-10-04, device-proven) — "caută în YouTube X" said as
    // a FOLLOW-UP (after a bare "Benson" ack) landed while BENSON's own listening overlay briefly
    // had foreground focus (SESSION_ACTIVE_END reason=self_app_foreground) — live foreground
    // wasn't YouTube yet even though the words plainly say YouTube. The command's own wording is
    // authoritative when it names the app explicitly; live foreground stays authoritative only
    // when the command doesn't name an app at all (bare "caută X").
    if (!youtubeForeground && !MENTIONS_YOUTUBE_PATTERN.containsMatchIn(rawQuery)) return false
    val query = expo.modules.accessibility.AppMentionStripper.strip(rawQuery)
    if (query.isBlank()) return false
    if (isCarModeActive()) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=youtube_search_confirm_refused reason=car_mode target=\"$query\"")
      speak("Nu pornesc video cât conduci.")
      return true
    }
    AudioDiag.log(this, "NATIVE_ROUTE", "action=youtube_search_confirm target=\"$query\" youtubeForeground=$youtubeForeground")
    if (!youtubeForeground && !launchYoutubeSearch(query)) return false
    svc.runOnServiceScope {
      // Already-foreground: real search box, activate+type (genericSearchLocateFirst). Just
      // launched via Intent: results already populated, no box to activate (locateFirstResultOnScreen).
      val title = if (youtubeForeground) svc.genericSearchLocateFirst(query) else {
        kotlinx.coroutines.delay(1800)
        svc.locateFirstResultOnScreen(query)
      }
      mainHandler.post {
        if (title == null) {
          AudioDiag.log(this, "YOUTUBE_SEARCH_CONFIRM", "query=\"$query\" found=false")
          speak("N-am găsit $query pe YouTube.")
        } else {
          AudioDiag.log(this, "YOUTUBE_SEARCH_CONFIRM", "query=\"$query\" found=true title=\"$title\"")
          pendingYoutubePlay = PendingYoutubePlay(query)
          val question = "Am găsit $title. Îl pornesc?"
          updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
          speak(question)
        }
      }
    }
    return true
  }

  private fun handlePendingYoutubePlayAnswer(rawAnswer: String) {
    val pending = pendingYoutubePlay ?: return
    val verdict = classifyConfirmationNative(rawAnswer)
    AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
    when (verdict) {
      "YES" -> {
        pendingYoutubePlay = null
        val svc = expo.modules.accessibility.BensonAccessibilityService.instance
        if (svc == null) { speak("Serviciul de accesibilitate nu e disponibil."); return }
        val query = pending.query
        svc.runOnServiceScope {
          val titleBefore = expo.modules.notificationlistener.MediaTransport.metadata(applicationContext, "com.google.android.youtube")?.title
          val clicked = svc.genericSearchAndPlay(query)
          AudioDiag.log(this, "YOUTUBE_PLAY_STEP", "clicked=$clicked query=\"$query\"")
          var meta: expo.modules.notificationlistener.MediaTransport.PlaybackMeta? = null
          // Same real-world buffering latency MUSIC-2 measured on Spotify today (widened 5s→18s
          // across three device-proven rounds) — same margin here, same reason, not re-guessed.
          val deadline = System.currentTimeMillis() + 15000
          while (meta == null && System.currentTimeMillis() < deadline) {
            val m = expo.modules.notificationlistener.MediaTransport.metadata(applicationContext, "com.google.android.youtube")
            if (m != null && m.title != titleBefore &&
              expo.modules.accessibility.TrackMetadataMatcher.matches(m.title, m.artist, query, query)
            ) meta = m
            if (meta == null) kotlinx.coroutines.delay(300)
          }
          val verified = meta != null
          AudioDiag.log(this, "YOUTUBE_PLAY", "query=\"$query\" verified=$verified title=\"${meta?.title ?: ""}\"")
          mainHandler.post {
            setMicOwner("NONE", "youtube_play_done")
            val resp = if (verified) "Pornit." else "N-am reușit să pornesc piesa."
            updateBubbleNative(resp, rawAnswer, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
            speak(resp)
          }
        }
      }
      "NO" -> {
        pendingYoutubePlay = null
        AudioDiag.log(this, "SESSION", "close reason=youtube_confirm_no")
        closeSession("youtube_confirm_no")
        setMicOwner("NONE", "youtube_confirm_no")
        rearmWakeNative("youtube_confirm_no")
      }
      else -> {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingYoutubePlay = null
          AudioDiag.log(this, "SESSION", "close reason=youtube_confirm_unresolved")
          closeSession("youtube_confirm_unresolved")
          setMicOwner("NONE", "youtube_confirm_unresolved")
          rearmWakeNative("youtube_confirm_unresolved")
        } else {
          speak("Da sau nu?")
        }
      }
    }
  }

  // FIX_APP_NAME_ACRONYM_1 (2026-10-04, device-proven) — scoring delegated to AppNameMatcher (pure,
  // JUnit-tested), which adds acronym-collapsing + a Levenshtein fallback on top of the original
  // exact/startsWith/contains tiers. Acceptance widened from ">= 50" to ">= 0" since a fuzzy-only
  // match now legitimately scores below the old structural floor but above -1 ("no match at all").
  private fun findLaunchablePackage(target: String): String? {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { return null }
    var best: String? = null
    var bestScore = -1
    for (ri in apps) {
      val label = try { normalizeForMatch(ri.loadLabel(pm).toString()) } catch (_: Exception) { continue }
      val pkg = ri.activityInfo.packageName
      val score = AppNameMatcher.score(target, label, ::levenshtein)
      if (score > bestScore) { bestScore = score; best = pkg }
    }
    return if (bestScore >= 0) best else null
  }

  private fun launchPackageNative(pkg: String): Boolean = try {
    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
  } catch (e: Exception) {
    AudioDiag.logError("APP_LAUNCH_ERROR", "pkg=$pkg type=${e.javaClass.simpleName}")
    false
  }

  // RUNDA_N2 TASK 1 — the missing link diagnosed 2026-10-02: wake re-arm after a native-handled
  // turn used to depend on startFollowUpCapture() (a VAD capture, not HEED) or on JS eventually
  // calling nativeWakeSetOwner('NONE'). Neither fires reliably while another app is foreground and
  // BENSON's JS is suspended. This wraps armNativeWake() with the requested log and is now called
  // explicitly at the end of every terminal outcome below — zero dependency on the bubble, JS, or
  // Activity. Revert: delete this function and call armNativeWake() directly at each site instead.
  private fun rearmWakeNative(reason: String) {
    armNativeWake(reason)
    val ok = heedWakeWord?.isRunning() == true || microWakeWord?.isRunning() == true
    AudioDiag.log(this, "WAKE_REARM", "reason=$reason ok=$ok")
  }

  // RUNDA_N4 — single source of truth for the bubble's auto-dismiss, replacing the earlier ad-hoc
  // 10_000L literals ("Code"'s own later spec settled on 8s, tied to last interaction).
  private val BUBBLE_LINGER_MS = 8_000L

  // RUNDA_N3 TASK A [WAKE_FOREGROUND_POLICY] — decided once per wake, before any bubble/activity
  // call. The foreground package now comes from BensonAccessibilityService's own
  // lastForegroundPackage (companion object, updated on every TYPE_WINDOW_STATE_CHANGED event —
  // "the closest thing to 'what app is in the foreground right now' a non-system app can get
  // without PACKAGE_USAGE_STATS", per that file's own comment). Launcher identified by resolving
  // ACTION_MAIN/CATEGORY_HOME, not a hardcoded package name. If the accessibility service isn't
  // running (instance == null), falls back to today's behavior — bubble only, never an Activity.
  private enum class WakeFgDecision { ACTIVITY, BUBBLE, VOICE_ONLY }
  @Volatile private var currentWakeFgDecision = WakeFgDecision.BUBBLE

  private fun currentForegroundPackage(): String? =
    try { expo.modules.accessibility.BensonAccessibilityService.lastForegroundPackage } catch (_: Exception) { null }

  private fun isAccessibilityAvailable(): Boolean =
    try { expo.modules.accessibility.BensonAccessibilityService.instance != null } catch (_: Exception) { false }

  private fun launcherPackage(): String? = try {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
  } catch (_: Exception) { null }

  private fun applyWakeForegroundPolicy() {
    val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
    val locked = km?.isKeyguardLocked == true
    val a11yUp = isAccessibilityAvailable()
    val fgPkg = if (a11yUp) currentForegroundPackage() else null
    val decision = when {
      locked -> WakeFgDecision.VOICE_ONLY
      !a11yUp -> WakeFgDecision.BUBBLE // accessibility down — today's behavior, per spec
      fgPkg == packageName || (fgPkg != null && fgPkg == launcherPackage()) -> WakeFgDecision.ACTIVITY
      else -> WakeFgDecision.BUBBLE
    }
    currentWakeFgDecision = decision
    val pkgLabel = when { locked -> "locked"; !a11yUp -> "a11y_unavailable"; else -> fgPkg ?: "unknown" }
    AudioDiag.log(this, "WAKE_FG", "pkg=$pkgLabel decision=${decision.name.lowercase()}")
    when (decision) {
      WakeFgDecision.ACTIVITY -> { try { bringActivityToFront() } catch (_: Exception) {}; showBubbleNative(); showWakeRingNative() }
      WakeFgDecision.BUBBLE -> { showBubbleNative(); showWakeRingNative() }
      WakeFgDecision.VOICE_ONLY -> {}
    }
  }

  // RUNDA_N2 TASK 2 — reuses the SAME native Intent protocol showBubbleNative()/showWakeRingNative()
  // already send to expo.modules.overlay.BensonBubbleService (unchanged, not touched this round);
  // this just sends more of the same ACTION_UPDATE_STATUS intents from more call sites, so the
  // bubble no longer depends on JS ever running updateBubbleStatus().
  // RUNDA_N3/N4 — "bula = dialog": state carries BENSON's actual spoken response (e.g. "Deschid
  // WhatsApp."), not a generic "gata"; transcript carries the user's last utterance. Suppressed
  // entirely under VOICE_ONLY (screen locked — "bula nu se poate afișa peste ecranul de blocare").
  private fun updateBubbleNative(state: String, transcript: String, terminal: Boolean, dismissDelayMs: Long) {
    if (currentWakeFgDecision == WakeFgDecision.VOICE_ONLY) {
      AudioDiag.log(this, "BUBBLE", "suppressed reason=voice_only state=\"$state\"")
      return
    }
    val intent = Intent().apply {
      component = ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
      action = "expo.modules.overlay.ACTION_UPDATE_STATUS"
      putExtra("state", state)
      putExtra("transcript", transcript)
      putExtra("visible", true)
      putExtra("terminal", terminal)
      putExtra("turn_id", System.currentTimeMillis())
      putExtra("dismiss_delay_ms", dismissDelayMs)
    }
    try { startService(intent) } catch (_: Exception) {}
    AudioDiag.log(this, "BUBBLE", "show state=\"$state\" terminal=$terminal dismissMs=$dismissDelayMs")
    // RUNDA CAR-3a — same funnel every tryNative*/handlePending* result already goes through; no
    // new call sites needed. No-op (null instance) when Android Auto isn't showing Benson's screen.
    try {
      expo.modules.car.BensonCarScreen.instance?.updateState(
        expo.modules.car.CarStateMapper.fromBubbleUpdate(state, terminal),
        newLastUser = transcript.takeIf { it.isNotBlank() },
        newLastBenson = state.takeIf { it.isNotBlank() },
      )
    } catch (_: Exception) {}
  }

  private fun hideWakeRingNative() {
    val intent = Intent().apply {
      component = ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
      action = "expo.modules.overlay.ACTION_HIDE_WAKE_RING"
    }
    try { startService(intent) } catch (_: Exception) {}
    AudioDiag.log(this, "BUBBLE", "hide reason=turn_done")
  }

  // RUNDA_N3 TASK C [NATIVE_MEDIA_CTL] — reuses MediaTransport.control (modules/benson-notification-
  // listener, extracted verbatim from BensonNotificationListenerModule's own mediaSessionManager/
  // activeControllers/pickController — same ComponentName, same "no package = prefer PLAYING"
  // fallback already driving Spotify from JS). packageName="" so it picks whatever is actually
  // playing, same as the JS caller's own default usage.
  // FIX_MEDIA_CTL_TRAILING_OBJECT_1 (2026-10-04, device-reported: "opreste muzica"/"stop muzica"
  // nu opreau nimic) — ancora "$" cerea cuvântul-cheie SINGUR; "muzica"/"muzică"/"music" rămas
  // după el rupea match-ul complet, deci comanda cădea silențios înainte să ajungă la
  // MediaTransport.control. Obiectul e acum opțional, nu obligatoriu.
  private val MEDIA_CTL_PATTERN = Regex(
    "^\\s*(pauza|pauză|opreste|oprește|stop|continua|continuă|porneste|pornește|play|" +
      "urmatorul|următorul|urmatoarea|următoarea|next|inapoi|înapoi|precedentul|precedenta|precedentă|previous)" +
      "\\s*(muzica|muzică|music)?\\s*[.!?]*\\s*$",
    RegexOption.IGNORE_CASE,
  )

  private fun tryNativeMediaControl(command: String): String? {
    val m = MEDIA_CTL_PATTERN.find(normalizeForMatch(command)) ?: return null
    val word = m.groupValues[1]
    val action = when (word) {
      "pauza", "pauză", "opreste", "oprește", "stop" -> "pause"
      "continua", "continuă", "porneste", "pornește", "play" -> "play"
      "urmatorul", "următorul", "urmatoarea", "următoarea", "next" -> "next"
      else -> "previous"
    }
    AudioDiag.log(this, "NATIVE_ROUTE", "action=media_ctl target=\"$action\"")
    val ok = expo.modules.notificationlistener.MediaTransport.control(applicationContext, "", action)
    AudioDiag.log(this, "APP_LAUNCH", "pkg=media_session ok=$ok")
    if (!ok) return null
    return when (action) {
      "pause" -> "Pauză."
      "play" -> "Continui."
      "next" -> "Trec mai departe."
      else -> "Mă întorc."
    }
  }

  // RUNDA_N3 TASK B [CONTEXT_SEARCH] — "caută/pune X" without naming the app, routed by whichever
  // app BensonAccessibilityService currently reports as foreground. Only YouTube/Spotify, per
  // scope. Checked AFTER the explicit "pe YouTube" pattern below, so an explicit mention still
  // wins regardless of what's actually in front.
  private val CONTEXT_SEARCH_PATTERN = Regex(
    "^\\s*(?:cauta|cautam|cauta-mi|pune)(?:-mi)?\\s+(.+?)\\s*[.!?]*\\s*$",
    RegexOption.IGNORE_CASE,
  )

  private fun tryContextSearch(command: String): String? {
    // FIX_G1_STALE_FOREGROUND_1 (2026-10-04, device-proven) — same bug as tryUniversalHand's
    // Search gate: currentForegroundPackage()/lastForegroundPackage (updated only on
    // TYPE_WINDOW_STATE_CHANGED) stayed stuck on "youtube" after switching to Spotify — "cauta
    // madonna" kept routing to YouTube's context search. Live read instead.
    val fgPkg = expo.modules.accessibility.BensonAccessibilityService.instance?.currentForegroundPackageLive() ?: return null
    val m = CONTEXT_SEARCH_PATTERN.find(normalizeForMatch(command)) ?: return null
    // FIX_SEARCH_TARGET_APP_MENTION_1 (2026-10-04, device-proven) — "caută în youtube george
    // michael" kept "în youtube" IN the query, so the YouTube search box searched for the wrong
    // text. Shared stripper, same as MusicPlayCommandMatcher's trailing-form case.
    val query = expo.modules.accessibility.AppMentionStripper.strip(m.groupValues[1].trim())
    if (query.isBlank()) return null
    return when (fgPkg) {
      "com.google.android.youtube" -> {
        if (isCarModeActive()) {
          AudioDiag.log(this, "NATIVE_ROUTE", "action=context_search_refused reason=car_mode target=\"$query\" app=youtube")
          return "Nu pornesc video cât conduci."
        }
        AudioDiag.log(this, "NATIVE_ROUTE", "action=context_search target=\"$query\" app=youtube")
        if (launchYoutubeSearch(query)) "Caut $query pe YouTube." else null
      }
      "com.spotify.music" -> {
        AudioDiag.log(this, "NATIVE_ROUTE", "action=context_search target=\"$query\" app=spotify")
        if (launchSpotifySearch(query)) "Caut $query pe Spotify." else null
      }
      else -> null
    }
  }

  // RUNDA S-1 TASK 2 (2026-10-03, user-directed) — "caută X" with no app/location named: Google
  // web search via Intent, last resort in the native chain (checked after YouTube/Spotify/context
  // search, so an explicit mention of those still wins). GeneralSearchMatcher already refuses nav
  // ("pe hartă"/"unde e"/"du-mă la") and YouTube/Spotify phrasing — that existing path stays
  // untouched.
  private fun tryNativeGeneralSearch(command: String): String? {
    val query = GeneralSearchMatcher.extractQuery(command) ?: return null
    AudioDiag.log(this, "NATIVE_ROUTE", "action=web_search target=\"$query\"")
    val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return null }
    val ok = try {
      startActivity(
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$encoded"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      true
    } catch (e: Exception) {
      AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=web_search type=${e.javaClass.simpleName}")
      false
    }
    AudioDiag.log(this, "APP_LAUNCH", "pkg=browser ok=$ok")
    return if (ok) "Caut $query." else null
  }

  private fun launchYoutubeSearch(query: String): Boolean {
    val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return false }
    return try {
      val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
        setPackage("com.google.android.youtube")
        putExtra("query", query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val ok = if (packageManager.resolveActivity(searchIntent, 0) != null) {
        startActivity(searchIntent); true
      } else {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$encoded")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
      }
      AudioDiag.log(this, "APP_LAUNCH", "pkg=com.google.android.youtube ok=$ok")
      ok
    } catch (e: Exception) {
      AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=yt_search type=${e.javaClass.simpleName}")
      false
    }
  }

  private fun launchSpotifySearch(query: String): Boolean {
    val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return false }
    return try {
      val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:search:$encoded")).apply {
        setPackage("com.spotify.music")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val ok = if (packageManager.resolveActivity(intent, 0) != null) {
        startActivity(intent); true
      } else {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/search/$encoded")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
      }
      AudioDiag.log(this, "APP_LAUNCH", "pkg=com.spotify.music ok=$ok")
      ok
    } catch (e: Exception) {
      AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=spotify_search type=${e.javaClass.simpleName}")
      false
    }
  }

  private val YT_SEARCH_PATTERN = Regex(
    "^\\s*(?:cauta|cautam|cauta-mi|pune)(?:-mi)?\\s+(.+?)\\s+pe\\s+you\\s*tube\\s*[.!?]*\\s*$",
    RegexOption.IGNORE_CASE,
  )

  private fun tryNativeYoutube(command: String): String? {
    val normalized = normalizeForMatch(command)
    val m = YT_SEARCH_PATTERN.find(normalized) ?: return null
    val query = m.groupValues[1].trim()
    if (query.isBlank()) return null
    // RUNDA CAR-1 — vezi tryNativeYoutubeOpenSearch; aceeași refuzare în modul mașină.
    if (isCarModeActive()) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=yt_search_refused reason=car_mode target=\"$query\"")
      return "Nu pornesc video cât conduci."
    }
    AudioDiag.log(this, "NATIVE_ROUTE", "action=yt_search target=\"$query\"")
    val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return null }
    val ok = try {
      val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
        setPackage("com.google.android.youtube")
        putExtra("query", query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      if (packageManager.resolveActivity(searchIntent, 0) != null) {
        startActivity(searchIntent); true
      } else {
        val viewIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(viewIntent); true
      }
    } catch (e: Exception) {
      AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=yt_search type=${e.javaClass.simpleName}")
      false
    }
    AudioDiag.log(this, "APP_LAUNCH", "pkg=com.google.android.youtube ok=$ok")
    return if (ok) "Caut $query pe YouTube." else null
  }

  // ── RUNDA WA-1 (2026-10-02, user-approved) — native WhatsApp call/video/message ──────────────
  // Doctrină (Rareș, 02.10.2026): citire locală read-only din ContactsContract, doar rândurile
  // WhatsApp ale contactului numit; nimic copiat/stocat/trimis dincolo de execuția imediată. Log:
  // doar inițiala și dataId (contactId), niciodată numele complet sau numărul de telefon.
  // Execuția REFOLOSEȘTE funcțiile suspend deja dovedite pe BensonAccessibilityService
  // (runWhatsAppCallNative / runWhatsAppOpenConversationType / pressWhatsAppSendVerified — fiecare
  // cu propriul DEVICE_PASS din runde anterioare) în loc de un nou drum ContactsContract-Intent
  // pentru apel/video — "nu înlocui ce funcționează". Nou aici e doar: parserul, rezolvarea
  // contactului prin ContactsContract, și gate-ul de confirmare (clasificatorul YES/NO/UNKNOWN,
  // portat din app/index.tsx's classifyConfirmation — CONFIRM_YES_RE/CONFIRM_NO_RE, NO prioritar).
  //
  // Scope redus, semnalat explicit: "caută (contactul) X" (deschide + întreabă mesaj/apel/video)
  // NU e implementat — nu era în lista de teste a rundei, iar nicio funcție existentă nu doar
  // "deschide, fără apel/mesaj"; necesită design nou, nu refolosire. Disambiguarea e simplificată
  // (cel mai bun scor automat; doar la egalitate exactă pe primul loc cere o repetare a numelui,
  // nu o listă numerotată "unu, doi" — nicio frază existentă de genul ăsta nu a fost găsită în cod).
  private data class WaContact(val contactId: Long, val displayName: String, val phone: String?, val score: Int = 0, val lookupKey: String? = null)
  private data class PendingWaAction(
    val kind: String, // "call" | "video" | "message" | "search_followup"
    val contact: WaContact,
    var message: String?,
    var noCount: Int = 0,
    // ADAOS WA_VISIBLE_DRAFT — set once startWaDraftAndAsk has actually typed the draft, so the
    // YES branch knows which WhatsApp mission to send instead of starting a fresh one.
    var missionId: String? = null,
    // ADAOS CONTACT_ALIAS_LEARN — set only when this contact was resolved via a learned alias
    // (not a fresh fuzzy pick). "Nu, altă X" deletes the alias and re-shows aliasCandidates
    // instead of just going silent — both null when resolution came from the normal fuzzy path.
    var aliasSpokenForm: String? = null,
    var aliasCandidates: List<WaContact>? = null,
  )
  @Volatile private var pendingWaAction: PendingWaAction? = null

  // ADAOS WA-1 (2026-10-03, user-directed) — ambiguity ("unu... doi...", picked by number), checked
  // BEFORE pendingWaAction in handleNativeCommandFlow (mutually exclusive — only one is ever set).
  // FIX_WA_DISAMBIGUATION_LOOP_1 (2026-10-03, device-proven, user-reported live — "sună ca o
  // mașinărie stricată") — handlePendingWaDisambiguation had no give-up: an unresolved answer
  // (including silence, since an empty follow-up transcript routes here too while this is set)
  // re-asked "Pe care?" forever, with no termination, ever. noCount mirrors the exact 2-strikes
  // pattern handlePendingWaAnswer already uses for YES/NO/UNKNOWN — same doctrine, same escape.
  private data class PendingWaDisambiguation(
    val kind: String, val candidates: List<WaContact>, val messageText: String?, var noCount: Int = 0,
    val spokenNameRaw: String = "", // ADAOS CONTACT_ALIAS_LEARN — the ORIGINAL spoken name, not the "cinci" answer that resolved it
  )
  @Volatile private var pendingWaDisambiguation: PendingWaDisambiguation? = null
  private val WA_NUMBER_WORDS = mapOf("unu" to 1, "una" to 1, "doi" to 2, "doua" to 2, "trei" to 3, "patru" to 4, "cinci" to 5)
  private val WA_ORDINAL_WORDS = arrayOf("", "unu", "doi", "trei", "patru", "cinci")

  private fun parseWaOrdinal(text: String): Int? {
    val norm = normalizeForMatch(text)
    Regex("\\d+").find(norm)?.let { return it.value.toIntOrNull() }
    for ((word, n) in WA_NUMBER_WORDS) if (norm.split(Regex("\\s+")).contains(word)) return n
    return null
  }

  private val WA_MIMETYPES = arrayOf(
    "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
    "vnd.android.cursor.item/vnd.com.whatsapp.video.call",
    "vnd.android.cursor.item/vnd.com.whatsapp.profile",
  )
  // FIX_WA_MESSAGE_PARSER_1 (2026-10-03, device-proven regression) — "in whatsapp" (not just "pe
  // whatsapp") is a real spelling Deepgram produces ("în" ASCII-folded); every WA pattern now
  // accepts either preposition in its trailing cleanup, not just "pe".
  // FIX_WA_NORMALIZE_BEFORE_MATCH_1 (2026-10-03, user-directed) — matched against normalizeForMatch
  // output now (see tryNativeWhatsApp), so no "[îi]" alternation needed — "în" already folds to "in".
  private val WA_SUFFIX = "(?:\\s+(?:pe|in)\\s+whats\\s*app)?"
  // FIX_WA_CALL_PREPOSITION_1 / ADAOS WA-4 — call AND video classification now lives in
  // WaCallVideoMatcher (pure, JUnit-tested: WaCallVideoMatcherTest), not inline here. Revert:
  // restore "sun[ăa]-?[ol]?\s+(?:pe|la)\s+(.+?)$WA_SUFFIX\s*[.!?]*$" (call) and
  // "video\s*(?:call)?\s+cu\s+(.+?)$WA_SUFFIX\s*[.!?]*$" (video) as private vals here instead.
  // FIX_WA_MESSAGE_PARSER_1 — device-proven regression: the old single pattern mixed "mesaj" into
  // the SAME separator alternation as "că" ("c[ăa]|ca|mesaj"). For "scrie-i lui hana un mesaj pe
  // whatsapp" (mesaj AFTER the name, no text — a completely normal phrasing), that forced the
  // non-greedy name group to swallow "un" just to make the literal "mesaj" line up, producing the
  // corrupted name "hana un" — confirmed in log (WA_RESOLVE candidates=14 ambiguous=true on a
  // single real contact). Split into four patterns, name-corruption now structurally impossible
  // since "mesaj" is matched as its own anchored token, never folded into the separator class:
  //   A. "scrie-i/trimite-i (lui) X că/ca TEXT"      — explicit text, no "mesaj" word
  //   B. "scrie/trimite (-i) (un) mesaj (lui) X (că/: TEXT)" — mesaj BEFORE the name
  //   C. "scrie-i/trimite-i (lui) X (un) mesaj (că/: TEXT)"  — mesaj AFTER the name (today's bug)
  //   D. "scrie-i/trimite-i (lui) X"                  — bare name, no "mesaj"/"că" word at all
  // Tried in this order (most specific/text-bearing first); text group (2) absent/blank in B/C/D
  // means tryNativeWhatsApp asks "Ce să-i scriu?" instead of silently failing or guessing.
  // ADAOS WA-3 (2026-10-03, user-directed) — "întreabă-o pe X dacă/ce…" is handled SEPARATELY, by
  // WaMessageExtractor (pure, JUnit-tested) — checked before this list in tryNativeWhatsApp, not
  // folded in here, since it captures an INSTRUCTION (handed to composeWaMessage/the brain), not
  // literal text. "spune-i (lui) X că…" reuses pattern A's că/ca structure here (one more verb
  // alternative, same shape as scrie-i/trimite-i, zero new logic needed for that one).
  // FIX_WA_NORMALIZE_BEFORE_MATCH_1 — no more (?i)/c[ăa] alternations: matched against
  // normalizeForMatch output (lowercase, diacritic- and punctuation-free) in tryNativeWhatsApp.
  // FIX_WA_DICTATION_1 (2026-10-03, user-directed) — "mesajul = exact ce dictez, cuvânt cu cuvânt"
  // — WA_COMPOSE's brain is now disabled for messages entirely (see WA_COMPOSE_ENABLED below); the
  // captured text group IS the message, verbatim, "că"/":" stripped and nothing else touched.
  // "(?:\s+ca|:)" (not "\s+(?:ca|:)") — a literal colon needs NO preceding whitespace ("Hannah:
  // text" has none), "ca" as a separate word always does; a single shared "\s+" before both would
  // wrongly require a space before the colon too.
  private val WA_MESSAGE_PATTERNS = listOf(
    Regex("(?:scrie-?i|trimite-?i|spune-?i)\\s+(?:lui\\s+)?(.+?)(?:\\s+ca|:)\\s+(.+?)$WA_SUFFIX\\s*$"),
    Regex("(?:scrie|trimite)(?:-?i)?\\s+(?:un\\s+)?mesaj\\s+(?:lui\\s+)?(.+?)(?:(?:\\s+ca|:)\\s+(.+?))?$WA_SUFFIX\\s*$"),
    Regex("(?:scrie-?i|trimite-?i|scrie|trimite)\\s+(?:lui\\s+)?(.+?)\\s+(?:un\\s+)?mesaj(?:(?:\\s+ca|:)\\s+(.+?))?$WA_SUFFIX\\s*$"),
    Regex("(?:scrie-?i|trimite-?i)\\s+(?:lui\\s+)?(.+?)$WA_SUFFIX\\s*$"),
  )
  // ADAOS WA-1 — "caută (contactul) X (pe WhatsApp)": requires either "contactul" or an explicit
  // "pe whatsapp" so a bare "caută X" still falls through to tryContextSearch (YouTube/Spotify) —
  // tryNativeWhatsApp runs BEFORE that chain in handleNativeCommandFlow, so this must not be loose.
  private val WA_SEARCH_PATTERNS = listOf(
    Regex("cauta?(?:-mi)?\\s+contactul\\s+(.+?)(?:\\s+pe\\s+whats\\s*app)?\\s*$"),
    Regex("cauta?(?:-mi)?\\s+(.+?)\\s+pe\\s+whats\\s*app\\s*$"),
  )
  // Ported verbatim from app/index.tsx's CONFIRM_NO_RE/CONFIRM_YES_RE/classifyConfirmation — NO is
  // checked first (priority), matched against normalizeForMatch() output (already lowercase,
  // diacritics stripped), so only the ASCII-folded forms need listing here.
  private val CONFIRM_NO_RE = Regex("\\b(nu|no|nein|anuleaza|renunta|opreste|stop|cancel|abbrechen|negativ)\\b")
  private val CONFIRM_YES_RE = Regex("\\b(da|dap|yes|yeah|yep|yup|sure|ok|okay|sigur|desigur|bineinteles|corect|exact|perfect|pregat\\w*|confirm\\w*)\\b")

  private fun classifyConfirmationNative(text: String): String {
    val norm = normalizeForMatch(text)
    if (CONFIRM_NO_RE.containsMatchIn(norm)) return "NO"
    if (CONFIRM_YES_RE.containsMatchIn(norm)) return "YES"
    return "UNKNOWN"
  }

  private fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
      dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
      else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
    }
    return dp[a.length][b.length]
  }

  private fun fetchWaPhone(contactId: Long): String? = try {
    contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
  } catch (_: Exception) { null }

  // ADAOS CONTACT_ALIAS_LEARN (2026-10-03) — LOOKUP_KEY, not contactId: stable across the contact
  // being re-synced/re-merged by the Contacts provider, contactId is not.
  private fun fetchWaLookupKey(contactId: Long): String? = try {
    contentResolver.query(
      ContactsContract.Contacts.CONTENT_URI,
      arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
      "${ContactsContract.Contacts._ID} = ?",
      arrayOf(contactId.toString()),
      null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
  } catch (_: Exception) { null }

  // ADAOS CONTACT_ALIAS_LEARN + TTS_NAME_PRONOUNCE (2026-10-03, user-directed) — same
  // "benson_watchdog_prefs" SharedPreferences every other local setting already uses in this file
  // (wake_name, debug_inject_enabled, etc.), NOT EncryptedSharedPreferences/Keystore: no such
  // dependency exists anywhere in this project today, and an earlier round (NATIVE_CMD_REPORT.md)
  // explicitly found it unnecessary for comparably sensitive local data. Flagged in the round
  // report as a deviation from "criptat" needing your confirmation, not silently decided either way.
  // Never logs the spoken form or the learned name — only "action=save|delete", matching the
  // existing WA_RESOLVE convention of logging initials/dataId, never full names.
  private fun waAliasPrefs() = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
  private fun saveWaAlias(spokenRaw: String, lookupKey: String) {
    val key = WaAliasMatcher.aliasKey(normalizeForMatch(spokenRaw)) ?: return
    if (lookupKey.isBlank()) return
    waAliasPrefs().edit().putString("wa_alias_$key", lookupKey).apply()
    AudioDiag.log(this, "CONTACT_ALIAS", "action=save")
  }
  private fun lookupWaAlias(spokenRaw: String): String? {
    val key = WaAliasMatcher.aliasKey(normalizeForMatch(spokenRaw)) ?: return null
    return waAliasPrefs().getString("wa_alias_$key", null)
  }
  private fun deleteWaAlias(spokenRaw: String) {
    val key = WaAliasMatcher.aliasKey(normalizeForMatch(spokenRaw)) ?: return
    waAliasPrefs().edit().remove("wa_alias_$key").apply()
    AudioDiag.log(this, "CONTACT_ALIAS", "action=delete")
  }
  private fun fetchWaContactByLookupKey(lookupKey: String): WaContact? = try {
    val uri = android.net.Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
    contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
      ?.use { c -> if (c.moveToFirst()) WaContact(c.getLong(0), c.getString(1) ?: "", fetchWaPhone(c.getLong(0)), 0, lookupKey) else null }
  } catch (_: Exception) { null }

  private fun saveWaPronunciation(lookupKey: String?, phonetic: String) {
    if (lookupKey.isNullOrBlank() || phonetic.isBlank()) return
    waAliasPrefs().edit().putString("wa_pronounce_$lookupKey", phonetic).apply()
    AudioDiag.log(this, "TTS_NAME_PRONOUNCE", "action=save")
  }
  // The name to SPEAK (phonetic override if one was taught, else the real name). Never used for
  // the bubble/log text — those always show chosen.displayName, per "numele scris rămâne neschimbat".
  private fun spokenWaName(contact: WaContact): String {
    val key = contact.lookupKey ?: return contact.displayName
    return waAliasPrefs().getString("wa_pronounce_$key", null)?.takeIf { it.isNotBlank() } ?: contact.displayName
  }

  // Diacritic-insensitive fuzzy match ("Hana" -> "Hannah") against every local contact that has
  // AT LEAST ONE WhatsApp-connected Data row (voip.call / video.call / profile) — read-only,
  // nothing persisted. Sorted best-score first; caller takes [0] unless there's an exact tie.
  private fun findWaContacts(nameRaw: String): List<WaContact> {
    val target = normalizeForMatch(nameRaw)
    if (target.isBlank()) return emptyList()
    val candidates = LinkedHashMap<Long, String>()
    try {
      val placeholders = WA_MIMETYPES.joinToString(",") { "?" }
      contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME),
        "${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
        WA_MIMETYPES,
        null,
      )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
        val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME)
        while (c.moveToNext()) {
          val name = c.getString(nameIdx) ?: continue
          candidates.putIfAbsent(c.getLong(idIdx), name)
        }
      }
    } catch (e: Exception) {
      AudioDiag.logError("WA_RESOLVE_ERROR", "type=${e.javaClass.simpleName}")
      return emptyList()
    }
    val threshold = maxOf(2, target.length / 3)
    val scored = candidates.mapNotNull { (id, name) ->
      val norm = normalizeForMatch(name)
      // FIX_WA_DECLENSION_1 (2026-10-03, device-proven) — was plain inline scoring against `target`
      // only; "hanei" (Romanian dative) landed outside the threshold against "hannah" while an
      // unrelated contact fell inside it by chance (WA_RESOLVE chosen=A, not Hannah). WaNameMatcher
      // also scores against declension-stripped variants ("hanei" -> "han", a clean prefix of
      // "hannah") and takes the best. Revert: inline the three-case `when` + plain levenshtein back.
      val score = WaNameMatcher.bestScore(target, norm, ::levenshtein)
      if (score <= threshold) Pair(id, name) to score else null
    }.sortedBy { it.second }
    return scored.map { (idName, score) -> WaContact(idName.first, idName.second, fetchWaPhone(idName.first), score, fetchWaLookupKey(idName.first)) }
  }

  // ADAOS WA-4 (2026-10-03, user-directed) — specifically the video.call row, not just "is this a
  // WhatsApp contact at all" (findWaContacts already answered that via WA_MIMETYPES broadly).
  private fun hasWaVideoCapability(contactId: Long): Boolean = try {
    contentResolver.query(
      ContactsContract.Data.CONTENT_URI,
      arrayOf(ContactsContract.Data._ID),
      "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
      arrayOf(contactId.toString(), "vnd.android.cursor.item/vnd.com.whatsapp.video.call"),
      null,
    )?.use { it.moveToFirst() } ?: false
  } catch (_: Exception) { false }

  private fun openWhatsAppChatOnly(phoneRaw: String): Boolean = try {
    val phone = phoneRaw.filter { it.isDigit() }
    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("whatsapp://send?phone=$phone"))
      .apply { setPackage("com.whatsapp"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(i); true
  } catch (_: Exception) { false }

  // Parses a WA-specific intent out of `command`; if matched, resolves the contact (alias first,
  // then the numbered-disambiguation question, then a fresh fuzzy pick) and SPEAKS the next
  // question — never executes directly. Returns false so the caller falls through to the generic
  // chain when no WA intent is recognized.
  private fun tryNativeWhatsApp(command: String): Boolean {
    // ADAOS WA-4 — video checked before call INSIDE WaCallVideoMatcher.classify() itself, so a
    // call-shaped phrase that also mentions "video" never corrupts into "call" with "video"
    // swallowed into the name. WaCallVideoMatcher/WaMessageExtractor normalize internally (pure,
    // reusable); WA_MESSAGE_PATTERNS/WA_SEARCH_PATTERNS (still inline here) need it done explicitly.
    val normCommand = normalizeForMatch(command)
    val callOrVideo = WaCallVideoMatcher.classify(command)
    // ADAOS WA-3 — "întreabă-o pe X dacă/ce…" checked before the literal-text message patterns:
    // captures an INSTRUCTION (fed to composeWaMessage), not text to send verbatim.
    val askM = if (callOrVideo == null) WaMessageExtractor.extractAskInstruction(command) else null
    val msgM = if (callOrVideo == null && askM == null) WA_MESSAGE_PATTERNS.firstNotNullOfOrNull { it.find(normCommand) } else null
    val searchM = if (callOrVideo == null && askM == null && msgM == null) WA_SEARCH_PATTERNS.firstNotNullOfOrNull { it.find(normCommand) } else null
    val kind: String; val nameRaw: String; var messageText: String?
    when {
      callOrVideo != null -> { kind = callOrVideo.first; nameRaw = callOrVideo.second; messageText = null }
      askM != null -> { kind = "message"; nameRaw = askM.recipient; messageText = askM.instruction }
      msgM != null -> {
        kind = "message"; nameRaw = msgM.groupValues[1].trim()
        messageText = msgM.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
      }
      searchM != null -> { kind = "search_followup"; nameRaw = searchM.groupValues[1].trim(); messageText = null }
      else -> return false
    }
    AudioDiag.log(this, "WA_PARSE", "kind=$kind")
    val candidates = findWaContacts(nameRaw)
    // ADAOS CONTACT_ALIAS_LEARN (2026-10-03, user-directed) — a learned spoken form resolves
    // directly, skipping the numbered list entirely. Still asks the normal confirm question right
    // after (never skips confirmation) — only the LIST is skipped. Re-verified against the live
    // contact (not blindly trusted) so a deleted/changed contact doesn't silently misfire; the
    // fuzzy `candidates` list is computed anyway and stashed so "nu, altă X" can still show it.
    val aliasContact = lookupWaAlias(nameRaw)?.let { fetchWaContactByLookupKey(it) }
    if (aliasContact != null) {
      AudioDiag.log(this, "WA_RESOLVE", "candidates=${candidates.size} via_alias=true")
      proceedWithWaContact(kind, aliasContact, messageText, command, aliasSpokenForm = nameRaw, aliasCandidates = candidates)
      return true
    }
    if (candidates.isEmpty()) {
      AudioDiag.log(this, "WA_RESOLVE", "candidates=0")
      speakNativeFallbackThenCapture("Nu găsesc $nameRaw în WhatsApp.")
      return true
    }
    // ADAOS WA-1 — ambiguity: a true tie on the top score gets a spoken numbered list instead of
    // an automatic pick. Not every multi-candidate result is ambiguous — only an exact tie is.
    if (candidates.size > 1 && candidates[0].score == candidates[1].score) {
      val top = candidates.take(WA_ORDINAL_WORDS.size - 1)
      AudioDiag.log(this, "WA_RESOLVE", "candidates=${candidates.size} ambiguous=true")
      pendingWaDisambiguation = PendingWaDisambiguation(kind, top, messageText, spokenNameRaw = nameRaw)
      val spoken = top.mapIndexed { i, c -> "${WA_ORDINAL_WORDS[i + 1]}, ${c.displayName}" }.joinToString(". ")
      val question = "Am găsit mai mulți: $spoken. Pe care?"
      updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(question)
      return true
    }
    val chosen = candidates[0]
    AudioDiag.log(this, "WA_RESOLVE", "candidates=${candidates.size} chosen=${chosen.displayName.take(1)} dataId=${chosen.contactId}")
    proceedWithWaContact(kind, chosen, messageText, command)
    return true
  }

  // Shared by the direct-resolve path above and handlePendingWaDisambiguation below — the "what do
  // we ask next" decision is identical either way once a single contact is settled. For "message"
  // with text already known, this now OPENS AND TYPES the draft first (WA_VISIBLE_DRAFT) instead
  // of asking blind — see startWaDraftAndAsk.
  private fun proceedWithWaContact(
    kind: String, chosen: WaContact, messageText: String?, commandForBubble: String,
    aliasSpokenForm: String? = null, aliasCandidates: List<WaContact>? = null,
  ) {
    if (kind != "search_followup" && chosen.phone.isNullOrBlank()) {
      speakNativeFallbackThenCapture("Nu am un număr de telefon pentru ${chosen.displayName}.")
      return
    }
    if (kind == "search_followup") {
      if (chosen.phone.isNullOrBlank() || !openWhatsAppChatOnly(chosen.phone)) {
        speakNativeFallbackThenCapture("Nu am un număr de telefon pentru ${chosen.displayName}.")
        return
      }
      pendingWaAction = PendingWaAction(kind, chosen, null, aliasSpokenForm = aliasSpokenForm, aliasCandidates = aliasCandidates)
      val question = "Mesaj, apel sau video?"
      updateBubbleNative(question, commandForBubble, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(question)
      return
    }
    if (kind == "message" && messageText.isNullOrBlank()) {
      pendingWaAction = PendingWaAction(kind, chosen, null, aliasSpokenForm = aliasSpokenForm, aliasCandidates = aliasCandidates)
      updateBubbleNative("Ce să-i scriu?", commandForBubble, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture("Ce să-i scriu?")
      return
    }
    if (kind == "message") {
      startWaDraftAndAsk(chosen, messageText!!, commandForBubble, aliasSpokenForm, aliasCandidates)
      return
    }
    // ADAOS WA-4 (2026-10-03, user-directed) — the contact's WhatsApp rows are already known to
    // include AT LEAST one of {voip.call, video.call, profile} (that's how findWaContacts found
    // them at all) — this checks specifically for video.call before asking a video question that
    // could never be fulfilled.
    if (kind == "video" && !hasWaVideoCapability(chosen.contactId)) {
      pendingWaAction = PendingWaAction("video_fallback_ask", chosen, null, aliasSpokenForm = aliasSpokenForm, aliasCandidates = aliasCandidates)
      val question = "${chosen.displayName} nu are video pe WhatsApp. O sun normal?"
      updateBubbleNative(question, commandForBubble, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(question)
      return
    }
    pendingWaAction = PendingWaAction(kind, chosen, messageText, aliasSpokenForm = aliasSpokenForm, aliasCandidates = aliasCandidates)
    val question = waQuestionFor(kind, spokenWaName(chosen), messageText)
    updateBubbleNative(waQuestionFor(kind, chosen.displayName, messageText), commandForBubble, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
    speakNativeFallbackThenCapture(question)
  }

  // ADAOS WA_VISIBLE_DRAFT (2026-10-03, user-directed, device-proven gap) — opens WhatsApp VISIBLY
  // and types the draft BEFORE asking "Trimit?", so the contact header and the typed text are both
  // on screen while BENSON asks — not confirmed blind from voice alone (today's send happened with
  // WhatsApp foreground for ~2s total, entirely AFTER "da" was already given). Does NOT click send
  // — pressWhatsAppSendVerified in executeWaAction still does that, still gated on "da".
  private fun startWaDraftAndAsk(
    chosen: WaContact, messageText: String, commandForBubble: String,
    aliasSpokenForm: String?, aliasCandidates: List<WaContact>?,
  ) {
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) {
      speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil.")
      return
    }
    val missionId = "wa1_${System.currentTimeMillis()}"
    // FIX_WA_SCREEN_LOCKED_1 (2026-10-03, device-proven) — see executeWaAction's identical fix for
    // the exact same device-proven bug (screen stayed off the whole attempt, WhatsApp never became
    // visible — "telefonul nu se lumineaza, nu apare nimic"). Draft needs a lit screen too: that's
    // the entire point of WA_VISIBLE_DRAFT.
    wakeScreen()
    svc.runOnServiceScope {
      // ADAOS WA-3 / WA_COMPOSE (2026-10-03, user-directed) — turns an INSTRUCTION ("întreabă-o
      // dacă vine diseară") into the actual message to send ("Vii diseară?"), via the brain. Brain
      // unavailable/timeout (3s, see composeHttpClient) → the raw Deepgram text is used as-is,
      // never a blocked draft.
      val composeStart = System.currentTimeMillis()
      val composed = composeWaMessage(messageText)
      val finalText = WaMessageExtractor.selectFinalMessage(composed, messageText)
      AudioDiag.log(
        this@BensonForegroundService, "WA_COMPOSE",
        "in=\"$messageText\" out=\"$finalText\" source=${if (composed != null) "brain" else "stt"} latencyMs=${System.currentTimeMillis() - composeStart}",
      )
      val typed = svc.runWhatsAppOpenConversationType(chosen.phone ?: "", chosen.displayName, finalText, missionId)
      AudioDiag.log(this@BensonForegroundService, "WA_DRAFT", "result=${if (typed.success) "ok" else "fail:${typed.step}"}")
      mainHandler.post {
        if (!typed.success) {
          val msg = if (typed.step == "FIND_MESSAGE_INPUT") "Am deschis conversația, preiei tu." else "Nu am reușit: ${typed.step}."
          speakNativeFallbackThenCapture(msg)
          return@post
        }
        pendingWaAction = PendingWaAction(
          "message", chosen, finalText, missionId = missionId,
          aliasSpokenForm = aliasSpokenForm, aliasCandidates = aliasCandidates,
        )
        val spokenQuestion = waQuestionFor("message", spokenWaName(chosen), finalText)
        val shownQuestion = waQuestionFor("message", chosen.displayName, finalText)
        updateBubbleNative(shownQuestion, commandForBubble, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
        speakNativeFallbackThenCapture(spokenQuestion)
      }
    }
  }

  // ADAOS WA-3 / WA_COMPOSE (2026-10-03, user-directed) — system prompt is a constant in code, per
  // explicit instruction. USER_VOICE only goes in as the user message; nothing from the screen.
  // Output is draft text ONLY — it can never trigger an action (the result is passed straight to
  // runWhatsAppOpenConversationType's TYPE step above, never re-parsed as a command).
  private val WA_COMPOSE_SYSTEM_PROMPT = "Transformă instrucțiunea în mesajul exact către destinatar, în română, persoana a II-a, cu diacritice și punctuație corectă. «Întreabă-l/o dacă/ce…» → întrebare directă cu «?». «Spune-i că…» → afirmație directă. Nu adăuga nimic, nu schimba sensul, fără emoji, fără salut dacă nu a fost cerut. Răspunde DOAR cu textul mesajului."
  private val composeHttpClient = OkHttpClient.Builder().callTimeout(3000, TimeUnit.MILLISECONDS).build()

  // FIX_WA_DICTATION_1 (2026-10-03, user-directed) — "Fără creier la mesaje. Regula: mesajul =
  // exact ce dictez, cuvânt cu cuvânt, cu punctuația de la Deepgram." Disabled, not deleted — the
  // call site (startWaDraftAndAsk) and WA_COMPOSE logging stay as-is, this just always returns
  // null now, so WaMessageExtractor.selectFinalMessage's existing fallback (composed ?: raw) takes
  // the raw STT text every time. Revert: flip back to true.
  private val WA_COMPOSE_ENABLED = false

  private fun composeWaMessage(instruction: String): String? {
    if (!WA_COMPOSE_ENABLED) return null
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("brain_api_key", "") ?: ""
    if (apiKey.isBlank()) return null
    val baseUrl = (prefs.getString("brain_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").trimEnd('/')
    val model = prefs.getString("brain_model", "")?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
    val body = JSONObject().apply {
      put("model", model)
      put(
        "messages",
        org.json.JSONArray().apply {
          put(JSONObject().apply { put("role", "system"); put("content", WA_COMPOSE_SYSTEM_PROMPT) })
          put(JSONObject().apply { put("role", "user"); put("content", instruction) })
        },
      )
      put("temperature", 0.3)
      put("max_tokens", 200)
    }
    val request = Request.Builder().url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    return try {
      composeHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val json = resp.body?.string() ?: return null
        JSONObject(json).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
          ?.optString("content", "")?.trim()?.takeIf { it.isNotBlank() }
      }
    } catch (_: Exception) { null }
  }

  private fun handlePendingWaDisambiguation(rawAnswer: String) {
    val pending = pendingWaDisambiguation ?: return
    val n = parseWaOrdinal(rawAnswer)
    val chosen = n?.let { pending.candidates.getOrNull(it - 1) }
    if (chosen == null) {
      AudioDiag.log(this, "WA_RESOLVE", "disambiguation=unresolved raw_ordinal=${n ?: -1}")
      pending.noCount += 1
      if (pending.noCount >= 2) {
        pendingWaDisambiguation = null
        AudioDiag.log(this, "SESSION", "close reason=wa_disambiguation_unresolved")
        closeSession("wa_disambiguation_unresolved")
        setMicOwner("NONE", "wa_disambiguation_unresolved")
        rearmWakeNative("wa_disambiguation_unresolved")
      } else {
        speakNativeFallbackThenCapture("Nu am înțeles numărul. Pe care?")
      }
      return
    }
    pendingWaDisambiguation = null
    AudioDiag.log(this, "WA_RESOLVE", "disambiguation=resolved chosen=${chosen.displayName.take(1)} dataId=${chosen.contactId}")
    // ADAOS CONTACT_ALIAS_LEARN — learn the alias right when the user actually makes a choice from
    // ambiguity; never learned on a direct unambiguous resolve (nothing was chosen there).
    chosen.lookupKey?.let { saveWaAlias(pending.spokenNameRaw, it) }
    proceedWithWaContact(pending.kind, chosen, pending.messageText, rawAnswer, aliasSpokenForm = pending.spokenNameRaw, aliasCandidates = pending.candidates)
  }

  // Textul mutat în WaQuestionText.kt (pur, JUnit-testat — WaQuestionTextTest), același pattern ca
  // WaCallVideoMatcher. Delegare simplă, zero schimbare la cele 5 locuri care o apelează.
  private fun waQuestionFor(kind: String, name: String, message: String?): String =
    WaQuestionText.forKind(kind, name, message)

  private fun handlePendingWaAnswer(rawAnswer: String) {
    val pending = pendingWaAction ?: return
    // ADAOS WA-4 (2026-10-03, user-directed) — "X nu are video pe WhatsApp. O sun normal?" is a
    // plain yes/no, but YES means "execute a VOICE call now", not "ask the normal video confirm
    // question again" (the user already answered the only question that matters once here).
    if (pending.kind == "video_fallback_ask") {
      val verdict = classifyConfirmationNative(rawAnswer)
      AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
      when (verdict) {
        "YES" -> { pendingWaAction = null; executeWaAction(PendingWaAction("call", pending.contact, null)) }
        "NO" -> {
          pendingWaAction = null
          AudioDiag.log(this, "SESSION", "close reason=wa_confirm_no")
          closeSession("wa_confirm_no")
          setMicOwner("NONE", "wa_confirm_no")
          rearmWakeNative("wa_confirm_no")
        }
        else -> {
          pending.noCount += 1
          if (pending.noCount >= 2) {
            pendingWaAction = null
            closeSession("wa_confirm_unknown_twice")
            setMicOwner("NONE", "wa_confirm_unknown")
            rearmWakeNative("wa_confirm_unknown")
          } else {
            speakNativeFallbackThenCapture("${pending.contact.displayName} nu are video pe WhatsApp. O sun normal?")
          }
        }
      }
      return
    }
    // ADAOS WA-1 — two states that are NOT a yes/no confirmation: "Mesaj, apel sau video?" after a
    // search, and "Ce să-i scriu?" when a message had no text yet. Both fall through to
    // proceedWithWaContact, which asks the REAL yes/no question next.
    if (pending.kind == "search_followup") {
      val norm = normalizeForMatch(rawAnswer)
      val resolvedKind = when {
        norm.contains("mesaj") -> "message"
        norm.contains("video") -> "video"
        norm.contains("apel") || norm.contains("sun") -> "call"
        else -> null
      }
      if (resolvedKind == null) {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingWaAction = null
          closeSession("wa_search_followup_unknown")
          setMicOwner("NONE", "wa_search_followup_unknown")
          rearmWakeNative("wa_search_followup_unknown")
        } else {
          speakNativeFallbackThenCapture("Mesaj, apel sau video?")
        }
        return
      }
      pendingWaAction = null
      proceedWithWaContact(resolvedKind, pending.contact, null, rawAnswer)
      return
    }
    if (pending.kind == "message" && pending.message == null) {
      val text = rawAnswer.trim()
      if (text.isBlank()) {
        pendingWaAction = null
        AudioDiag.log(this, "SESSION", "close reason=wa_message_empty")
        closeSession("wa_message_empty")
        setMicOwner("NONE", "wa_message_empty")
        rearmWakeNative("wa_message_empty")
        return
      }
      pendingWaAction = null
      // ADAOS WA_VISIBLE_DRAFT — opens+types NOW (was: just ask, open+type only after "da").
      startWaDraftAndAsk(pending.contact, text, rawAnswer, pending.aliasSpokenForm, pending.aliasCandidates)
      return
    }
    // ADAOS TTS_NAME_PRONUNCE (2026-10-03, user-directed) — "Se pronunță X" after BENSON has just
    // said a name: saves a TTS-only override (spokenWaName), re-asks the SAME question so the
    // correction is audible immediately. The WRITTEN name (bubble, logs) never changes.
    WaAliasMatcher.parsePronounce(rawAnswer)?.let { phonetic ->
      saveWaPronunciation(pending.contact.lookupKey, phonetic)
      val question = waQuestionFor(pending.kind, phonetic, pending.message)
      speakNativeFallbackThenCapture(question)
      return
    }
    // ADAOS WA_VISIBLE_DRAFT — "schimbă în <text nou>" while the draft is already on screen:
    // re-types (replaces, ACTION_SET_TEXT) and re-asks. Only meaningful for "message" — call/video
    // never had a "mesaj" field to begin with, so WA_CHANGE_TEXT_PATTERN simply won't fire there
    // in practice (no such phrasing applies), but gating on kind keeps the intent explicit.
    if (pending.kind == "message" && pending.message != null) {
      WaAliasMatcher.parseChangeText(rawAnswer)?.let { newText ->
        pendingWaAction = null
        startWaDraftAndAsk(pending.contact, newText, rawAnswer, pending.aliasSpokenForm, pending.aliasCandidates)
        return
      }
    }
    val verdict = classifyConfirmationNative(rawAnswer)
    AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
    when (verdict) {
      "YES" -> { pendingWaAction = null; executeWaAction(pending) }
      "NO" -> {
        pendingWaAction = null
        // ADAOS CONTACT_ALIAS_LEARN — "nu, altă X": delete the alias (it pointed at the wrong
        // person) and re-show the ORIGINAL numbered list instead of just going silent. Only
        // meaningful when this resolution actually came from an alias — a fresh fuzzy pick has
        // nothing to delete, falls through to the normal silent-IDLE cancel.
        val norm = normalizeForMatch(rawAnswer)
        if (pending.aliasSpokenForm != null && WaAliasMatcher.mentionsAlternateContact(norm)) {
          deleteWaAlias(pending.aliasSpokenForm!!)
          val list = pending.aliasCandidates
          if (!list.isNullOrEmpty()) {
            pendingWaDisambiguation = PendingWaDisambiguation(pending.kind, list, pending.message, spokenNameRaw = pending.aliasSpokenForm!!)
            val spoken = list.mapIndexed { i, c -> "${WA_ORDINAL_WORDS[i + 1]}, ${c.displayName}" }.joinToString(". ")
            val question = "Am găsit mai mulți: $spoken. Pe care?"
            updateBubbleNative(question, rawAnswer, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
            speakNativeFallbackThenCapture(question)
            return
          }
        }
        if (pending.kind == "message") {
          val svc = expo.modules.accessibility.BensonAccessibilityService.instance
          svc?.runOnServiceScope { svc.clearWhatsAppComposeField() }
        }
        AudioDiag.log(this, "SESSION", "close reason=wa_confirm_no")
        closeSession("wa_confirm_no")
        setMicOwner("NONE", "wa_confirm_no")
        rearmWakeNative("wa_confirm_no")
      }
      else -> {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingWaAction = null
          closeSession("wa_confirm_unknown_twice")
          setMicOwner("NONE", "wa_confirm_unknown")
          rearmWakeNative("wa_confirm_unknown")
        } else {
          speakNativeFallbackThenCapture(waQuestionFor(pending.kind, spokenWaName(pending.contact), pending.message))
        }
      }
    }
  }

  private fun executeWaAction(pending: PendingWaAction) {
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) {
      speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil.")
      return
    }
    // FIX_WA_SCREEN_LOCKED_1 (2026-10-03, device-proven) — "sun-o pe mama" confirmed with "da"
    // (CONFIRM_RESULT YES, WA_NATIVE_START fired) but nothing ever appeared: SCREEN_STATE stayed
    // "off" through the whole attempt (device had gone to sleep between the question and the
    // answer), so launchWhatsApp()'s plain startActivity() never got a chance to become visible —
    // WA_NATIVE_PACKAGE found=false, call never connected. Reuses the existing wakeScreen()
    // (FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP, proven on the legacy onHotwordDetected path) instead
    // of inventing a second wake mechanism. Harmless when the screen is already on.
    wakeScreen()
    svc.runOnServiceScope {
      val result = when (pending.kind) {
        "call" -> svc.runWhatsAppCallNative(pending.contact.displayName, "voice_call")
        "video" -> svc.runWhatsAppCallNative(pending.contact.displayName, "video_call")
        else -> {
          // ADAOS WA_VISIBLE_DRAFT (2026-10-03, user-directed) — the draft was already typed by
          // startWaDraftAndAsk (missionId set then); this sends whatever is ACTUALLY in the field
          // NOW, not the cached pending.message — "dacă ating ecranul și corectez manual, BENSON
          // nu suprascrie; trimite trimite ce e în câmp". Re-types (updates the persisted hash
          // pressWhatsAppSendVerified's own drift-check requires) only when they differ; never
          // changes pressWhatsAppSendVerified itself, which stays the proven, unmodified function.
          val missionId = pending.missionId ?: "wa1_${System.currentTimeMillis()}"
          val liveText = (svc.readWhatsAppComposeFieldText()?.trim()).takeUnless { it.isNullOrBlank() } ?: (pending.message ?: "")
          if (pending.missionId == null || liveText != pending.message) {
            AudioDiag.log(this@BensonForegroundService, "WA_DRAFT", "manual_edit_detected=${pending.missionId != null}")
            val typed = svc.runWhatsAppOpenConversationType(pending.contact.phone ?: "", pending.contact.displayName, liveText, missionId)
            if (!typed.success) typed else svc.pressWhatsAppSendVerified(missionId, liveText, pending.contact.displayName)
          } else {
            svc.pressWhatsAppSendVerified(missionId, liveText, pending.contact.displayName)
          }
        }
      }
      AudioDiag.log(this@BensonForegroundService, "WA_INTENT", "kind=${pending.kind} result=${if (result.success) "ok" else "fail:${result.step}"}")
      mainHandler.post {
        val response = if (result.success) when (pending.kind) {
          "call" -> "Sun pe ${pending.contact.displayName}."
          "video" -> "Pornesc video cu ${pending.contact.displayName}."
          else -> "Trimis."
          // ADAOS WA-1 (2026-10-03) — "ancoră negăsită" per spec section 4: the chat DID open and
          // verify correctly (steps 1-2 passed), only the compose field itself wasn't found. Never
          // presses anything in that case (the function already returns before any ACTION_CLICK).
        } else if (result.step == "FIND_MESSAGE_INPUT") "Am deschis conversația, preiei tu." else "Nu am reușit: ${result.step}."
        openSession("wa_action_done", mayDuck = true)
        setMicOwner("NONE", "wa_action_done")
        updateBubbleNative(response, "", terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
        hideWakeRingNative()
        rearmWakeNative("wa_action_done")
      }
    }
  }

  // The single post-STT decision point, reached both from the original wake buffer (isFollowUp=
  // false, still has "Benson" in it — strip before matching) and from startFollowUpCapture's own
  // capture (isFollowUp=true, already just the command). Every terminal branch below calls
  // rearmWakeNative() and updates the bubble natively — "cu sau fără bulă" per your instruction,
  // the rearm itself never depends on the bubble call succeeding.
  private fun isWakeNameAlone(command: String): Boolean {
    val norm = normalizeForMatch(command)
    if (norm.isBlank() || norm.split(Regex("\\s+")).size != 1) return false
    return WakeVerifyMatcher.isWakeVerified(command, NativeCloudWake.currentWakeName(this))
  }

  private fun handleNativeCommandFlow(rawTranscript: String, isFollowUp: Boolean, originalPhrase: String, originalWavPath: String?) {
    val command = if (isFollowUp) rawTranscript.trim() else stripWakeWordNative(rawTranscript)
    if (pendingWaDisambiguation != null) {
      handlePendingWaDisambiguation(command)
      return
    }
    if (pendingWaAction != null) {
      handlePendingWaAnswer(command)
      return
    }
    if (pendingAppSearch != null) {
      handlePendingAppSearch(command)
      return
    }
    if (pendingControlAction != null) {
      handlePendingControlAnswer(command)
      return
    }
    if (pendingYoutubePlay != null) {
      handlePendingYoutubePlayAnswer(command)
      return
    }
    // FIX_WAKE_NAME_ALONE_1 (2026-10-03, user-directed, device-proven) — attempt #3 of today's 10x
    // test: follow-up capture caught "benson" (chars=6, the user re-addressing BENSON mid-session,
    // or an echo of it), which isn't blank, so it fell through to the parser chain and none of
    // tryNativeOpenApp/tryNativeYoutube/etc matched → "Încă nu știu să fac asta.". The wake name
    // alone, as the WHOLE follow-up utterance, means "are you listening?" — never the parser.
    // Reuses WakeVerifyMatcher (same fuzzy/keyterm-aware matching as the wake gate itself) instead
    // of a second hand-rolled comparison. Only in isFollowUp — the original wake buffer's "Benson"
    // alone already strips to "" via stripWakeWordNative above and hits the isBlank() branch below.
    if (isFollowUp && isWakeNameAlone(command)) {
      AudioDiag.log(this, "SESSION", "reconfirm reason=wake_name_alone")
      speakNativeAckThenCapture()
      return
    }
    if (command.isBlank()) {
      if (!isFollowUp) {
        // Not terminal — continues into the follow-up capture, which owns the mic next. Rearming
        // HEED here would fight that capture for the AudioRecord.
        speakNativeAckThenCapture()
      } else {
        AudioDiag.log(this, "SESSION", "close reason=no_followup_command")
        closeSession("no_followup_command")
        setMicOwner("NONE", "native_cmd_followup_empty")
        hideWakeRingNative()
        rearmWakeNative("session_timeout")
      }
      return
    }
    if (tryGlobalNav(command)) return
    if (tryNativeWhatsApp(command)) return // asked a confirmation; continues via the follow-up capture above
    if (tryNativeAppCategorySearch(command)) return // same shape — may ask a confirmation (ambiguous) or finish directly
    // RUNDA MUSIC-1 — checked BEFORE tryContextSearch so Spotify's "caută X" is superseded by
    // search+select+play+verify (per spec); declines internally (returns false) for every
    // combination it shouldn't touch (video YouTube's "caută X" stays search-only, unchanged).
    if (tryMusicPlay(command)) return
    if (tryNativeYoutubeOpenSearch(command)) return
    if (tryYoutubeSearchConfirm(command)) return
    var response = tryNativeOpenApp(command) ?: tryNativeYoutube(command) ?: tryNativeMediaControl(command) ?: tryContextSearch(command)
    // RUNDA G-1 — "mâna universală", checked only after all the existing faster/specific handlers
    // above declined, and before the generic web-search fallback below (so YouTube/Spotify keep
    // using their proven Intent-based context search, and "caută X" backgrounded/no-app still
    // reaches Google unchanged).
    if (response == null && tryUniversalHand(command)) return
    if (response == null) response = tryNativeGeneralSearch(command)
    if (response != null) {
      openSession("native_cmd_executed", mayDuck = true)
      setMicOwner("NONE", "native_cmd_executed")
      updateBubbleNative(response, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
      hideWakeRingNative()
      rearmWakeNative("app_launch_done")
      return
    }
    // RUNDA_N3 — a backgrounded handoff to JS is a dead path (device-proven this session: three
    // consecutive unrecognized commands with zero log activity past STT — JS never ran at all, not
    // even enough to log a miss). Only hand off when BENSON's own screen is actually visible
    // (JS demonstrably alive); otherwise speak+show the fallback natively and keep listening.
    if (isBensonForeground()) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=js_handoff")
      // FIX_SINGLE_TRANSCRIPTION_1 (2026-10-04, device-proven on highway) — passing
      // (originalPhrase, originalWavPath) here made JS re-transcribe the SAME clip from scratch
      // via its own Deepgram call; that second, independent transcription disagreed with
      // WAKE_VERIFY's ("benson punem adona pe spotify", score 0.943) and came back as an empty
      // command, losing a command WAKE_VERIFY had already captured correctly. `command` (verify's
      // own text, wake word already stripped above) is the one transcription on this path now —
      // same as the isFollowUp branch already did. Revert: restore the if/else on isFollowUp.
      onHotwordDetected(command, null)
      rearmWakeNative("js_handoff")
    } else {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=native_fallback reason=benson_backgrounded")
      val fallback = "Încă nu știu să fac asta."
      updateBubbleNative(fallback, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(fallback)
    }
  }

  // Reuses CaptureEngine (modules/benson-audio-capture, same VAD/pre-roll/WAV as the JS-driven
  // path — no new capture mechanism). Runs on its own thread; CaptureEngine.capture() blocks that
  // thread until done, so the subsequent nativeTranscribe() call is safe there too. Only the final
  // routing decision is posted back to mainHandler.
  // RUNDA_N5 (2026-10-02, user-approved) — CMD_START_TIMEOUT_MS: a person needs real time to start
  // talking after hearing "Da, Master" (the old shared default, 1500ms, was tuned for JS's
  // already-mid-conversation capture, not "just heard an ack, now begin"). CMD_MAX_MS/silence-800ms
  // match the spec exactly. Same values for the inline-decision follow-up (there is none — inline
  // vs ack is still decided by the ~700ms-equivalent empty-transcript check in
  // handleNativeCommandFlow) and for the post-response FOLLOW_UP_WINDOW_MS re-listen (same call
  // site, same rule — this function IS that re-listen).
  private val CMD_START_TIMEOUT_MS = 5_000L
  private val CMD_MAX_MS = 10_000L
  private val CMD_VAD_SILENCE_MS = 800L

  private fun startFollowUpCapture() {
    setMicOwner("COMMAND_STT", "followup_capture_start")
    updateBubbleNative("ASCULT", "", terminal = false, dismissDelayMs = 0L)
    AudioDiag.log(this, "CMD_CAPTURE", "start")
    Thread({
      expo.modules.audiocapture.CaptureEngine.capture(
        applicationContext,
        onVolume = {},
        onEnd = { filePath, reason, waitedMs, speechMs ->
          val bytes = filePath?.let { try { File(it).length() } catch (_: Exception) { 0L } } ?: 0L
          val logReason = when (reason) { "vad_silence" -> "vad"; "max_duration" -> "max"; else -> "timeout" }
          AudioDiag.log(this, "CMD_CAPTURE", "end bytes=$bytes reason=$logReason waitedMs=$waitedMs speechMs=$speechMs")
          val transcript = if (filePath != null) nativeTranscribe(filePath) else null
          if (filePath != null) try { File(filePath).delete() } catch (_: Exception) {}
          mainHandler.post {
            handleNativeCommandFlow(transcript ?: "", isFollowUp = true, originalPhrase = "", originalWavPath = null)
          }
        },
        isStopRequested = { false },
        preSpeechTimeoutMs = CMD_START_TIMEOUT_MS,
        silenceTimeoutMs = CMD_VAD_SILENCE_MS,
        maxDurationMs = CMD_MAX_MS,
      )
    }, "benson-native-cmd-capture").start()
  }

  // Native keyword detect (runs on the MicroWakeWord thread). Release the wake mic, flip owner,
  // then hand to the existing wake-event path (bubble + ring + JS event + pendingWakeCommand).
  private fun onNativeWakeDetected(score: Float, wavPath: String) {
    mainHandler.post {
      if (isCallAudioBlockedNow()) { AudioDiag.log(this, "WAKE_RESULT_DROPPED", "reason=call_audio_active engine=microwakeword"); return@post }
      AudioDiag.log(this, "WAKE_DETECT", "engine=microwakeword keyword=${MicroWakeWord.WAKE_PHRASE.replace(' ', '_')} score=${"%.3f".format(score)}")
      suspendNativeWake("COMMAND")
      setMicOwner("COMMAND_STT", "wake_detected")
      // Alexa-style: try to surface BENSON so command capture can start even from another app.
      // Best-effort — OxygenOS may block a bg Activity start; the bubble/ring below always show.
      AudioDiag.log(this, "WAKE_COMMAND_AUDIO_READY", "path=${java.io.File(wavPath).name}")
      onHotwordDetected(MicroWakeWord.WAKE_PHRASE, wavPath)
    }
  }

  // ROUND_WAKE_NATIVE_GENERIC_1 — native cloud wake detect (runs on NativeCloudWake's own
  // thread, posted here to the main thread). Reuses the EXACT SAME hand-off path as every other
  // wake source (onHotwordDetected: wakeScreen, E1-1-gated bringActivityToFront, bubble + ring,
  // onWakeWordDetected JS event / pendingWakeCommand fallback) — no redesign of the command stack.
  private fun onNativeCloudWakeDetected(commandTail: String) {
    mainHandler.post {
      if (isCallAudioBlockedNow()) { AudioDiag.log(this, "WAKE_RESULT_DROPPED", "reason=call_audio_active engine=native_cloud"); return@post }
      AudioDiag.log(this, "WAKE_DETECT", "engine=native_cloud keywordConfigured=true commandTailChars=${commandTail.length}")
      suspendNativeWake("COMMAND")
      setMicOwner("COMMAND_STT", "wake_detected")
      AudioDiag.log(this, "WAKE_COMMAND_HANDOFF", "source=native_cloud")
      try {
        onHotwordDetected(commandTail)
        AudioDiag.log(this, "WAKE_COMMAND_HANDOFF_OK", "source=native_cloud")
      } catch (e: Exception) {
        AudioDiag.logError("WAKE_COMMAND_HANDOFF_FAIL", "source=native_cloud error=\"${e.message}\"")
      }
    }
  }

  private var wakePokeRunning = false
  private val wakePokeTick = object : Runnable {
    override fun run() {
      try {
        refreshCallAudioState("heartbeat")
        val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
        val userStopped = prefs.getBoolean("user_stopped", false)
        val wakeEnabled = prefs.getBoolean("wake_word_enabled", true)
        if (!callAudioBlocked && !isCallAudioActive() && !userStopped && wakeEnabled) {
          if (nativeWakeAvailable()) {
            // Native engine health self-heal (ROUND_NATIVE_WAKE_MICROWAKEWORD_1, generalized in
            // ROUND_WAKE_NATIVE_GENERIC_1 to cover whichever engine armNativeWake() actually
            // chose). If it SHOULD be armed (nothing else owns the mic) but the thread died,
            // recreate it — this is the mechanism that lets wake recover after service recreation
            // without requiring JS to be alive to notice.
            val shouldBeArmed = micOwner == "WAKE" || micOwner == "NONE"
            val usingHeed = HeedWakeWord.modelPresent(this@BensonForegroundService)
            val usingMicroWakeWord = !usingHeed && MicroWakeWord.modelPresent(this@BensonForegroundService)
            val engineName = if (usingHeed) "heed" else if (usingMicroWakeWord) "microwakeword" else "unavailable"
            val runningNow = if (usingHeed) heedWakeWord?.isRunning() == true else if (usingMicroWakeWord) microWakeWord?.isRunning() == true else false
            AudioDiag.log(this@BensonForegroundService, "NWW_HEALTH",
              "micOwner=$micOwner engine=$engineName running=$runningNow")
            if (shouldBeArmed && !runningNow) {
              AudioDiag.log(this@BensonForegroundService, "WAKE_NATIVE_RECOVER", "micOwner=$micOwner engine=$engineName")
              if (usingHeed) {
                try { heedWakeWord?.stop() } catch (_: Exception) {}
                heedWakeWord = null
              } else if (usingMicroWakeWord) {
                try { microWakeWord?.stop() } catch (_: Exception) {}
                microWakeWord = null
              } else {
                try { nativeCloudWake?.stop() } catch (_: Exception) {}
                nativeCloudWake = null
              }
              armNativeWake("self_heal")
              val recovered = if (usingHeed) heedWakeWord?.isRunning() == true else if (usingMicroWakeWord) microWakeWord?.isRunning() == true else false
              AudioDiag.log(this@BensonForegroundService,
                if (recovered) "NWW_SELF_HEAL_OK" else "NWW_SELF_HEAL_FAIL", "engine=$engineName")
            }
          }
          // URGENT_REPAIR_AND_ADVANCE_1 — previously only invoked in the "no native model" branch
          // above (ROUND_WAKE_STATE_BUG_1's original JS-Whisper-wake-loop-only purpose). Proven
          // live: JS's own doStartListening() retry timers (tts_tail / post-action-mute) also go
          // inert while backgrounded, stranding a conv-mode confirmation wait with no mic open
          // (e.g. WhatsApp "Îl trimit?" — "da" never heard). JS now also self-heals conv-mode
          // listening on this same heartbeat; existing consumers (native-cloud wake self-heal
          // above, the JS-Whisper-wake-loop handler which self-guards on wakeEngineRef==='local')
          // are unaffected — this only ADDS a delivery, on every tick, not just the no-model one.
          AudioDiag.log(this@BensonForegroundService, "WAKE_POKE", "src=native_heartbeat")
          onWakePoke?.invoke()
        }
      } catch (_: Exception) {}
      if (wakePokeRunning) mainHandler.postDelayed(this, WAKE_POKE_INTERVAL_MS)
    }
  }
  private fun startWakePokeLoop() {
    if (wakePokeRunning) return
    wakePokeRunning = true
    mainHandler.postDelayed(wakePokeTick, WAKE_POKE_INTERVAL_MS)
    AudioDiag.log(this, "WAKE_POKE_LOOP", "state=started intervalMs=$WAKE_POKE_INTERVAL_MS")
  }
  private fun stopWakePokeLoop() {
    wakePokeRunning = false
    mainHandler.removeCallbacks(wakePokeTick)
  }
  private val mainHandler = Handler(Looper.getMainLooper())

  private fun isCallAudioActive(): Boolean {
    val mode = try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode } catch (_: Exception) { AudioManager.MODE_NORMAL }
    val waState = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      .getString("wa_call_lifecycle_state", "IDLE")
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
      waState == "CALL_VERIFIED_ACTIVE" || waState == "CALL_ENDING_PENDING"
  }
  fun isCallAudioBlockedNow(): Boolean = callAudioBlocked || isCallAudioActive()

  private fun refreshCallAudioState(reason: String) {
    val active = isCallAudioActive()
    if (active == callAudioBlocked) return
    callAudioBlocked = active
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    if (active) {
      AudioDiag.log(this, "CALL_AUDIO_GUARD", "state=active reason=$reason audioMode=${try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode } catch (_: Exception) { -1 }}")
      prefs.edit().putBoolean("call_audio_active", true).apply()
      pendingWakeCommand.getAndSet(null)
      pendingWakeAudioFile.getAndSet(null)
      pendingWakeCommandEpoch += 1
      suspendNativeWake("call_active")
      try { confirmationListener?.stop() } catch (_: Exception) {}
      confirmationListener = null
      setMicOwner("CALL", "call_audio_active")
      startCallMicProbe() // RUNDA CAR-1/PROBA RMS — doar măsurare, blocajul de mai sus rămâne activ
    } else {
      AudioDiag.log(this, "CALL_AUDIO_GUARD", "state=ended reason=$reason")
      prefs.edit().putBoolean("call_audio_active", false).apply()
      if (micOwner == "CALL") setMicOwner("NONE", "call_audio_ended")
      stopCallMicProbe()
      armNativeWake("call_audio_ended")
    }
    onCallAudioStateChanged?.invoke(active)
  }

  private val callStatePoll = object : Runnable {
    override fun run() {
      refreshCallAudioState("audio_poll")
      if (callStatePollRunning) mainHandler.postDelayed(this, 500L)
    }
  }

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

  // RUNDA TEST-1 (2026-10-03) — see onCreate/onDestroy comments. `adb shell am broadcast` reaches
  // this because it's registered with RECEIVER_EXPORTED (API 33+); it still does nothing without
  // the enable flag, which only this receiver and the harness ever touch.
  private val debugInjectReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      when (intent?.action) {
        "com.benson.butler.DEBUG_INJECT_ENABLE" -> {
          val enabled = intent.getBooleanExtra("enabled", false)
          prefs.edit().putBoolean("debug_inject_enabled", enabled).apply()
          AudioDiag.log(context, "DEBUG_INJECT", "action=set_enabled value=$enabled")
        }
        "com.benson.butler.DEBUG_INJECT" -> {
          if (!prefs.getBoolean("debug_inject_enabled", false)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val text = intent.getStringExtra("text")
          if (text.isNullOrBlank()) return
          AudioDiag.log(context, "DEBUG_INJECT", "action=run text=\"$text\"")
          mainHandler.post {
            handleNativeCommandFlow(text, isFollowUp = true, originalPhrase = "DEBUG_INJECT", originalWavPath = null)
          }
        }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    isRunning = true
    instance = this
    AudioDiag.log(this, "SERVICE_CREATE", "")
    initNativeAck()
    initCarConnectionObserver()
    scheduleWakeAliveCheck()
    mainHandler.post(wakeVerifyWarmRunnable) // FIX_WAKE_VERIFY_LATENCY_1
    // ADAOS K-1 (2026-10-03, user-directed) — see BensonAccessibilityService.onHeadsetButton and
    // onKeyEvent. true = long press (≥600ms) → wake, same as a real "Benson" but skipping
    // wake-verify entirely: the physical press IS the confirmation, there's no audio to verify.
    // false = short press → replicate normal play/pause (the raw key event was already consumed).
    expo.modules.accessibility.BensonAccessibilityService.onHeadsetButton = { longPress ->
      mainHandler.post {
        if (longPress) {
          triggerWakeFromHeadsetButton()
        } else {
          AudioDiag.log(this, "NATIVE_ROUTE", "action=headset_short_press")
          expo.modules.notificationlistener.MediaTransport.control(applicationContext, "", "toggle")
        }
      }
    }
    getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(callPrefsListener)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.addOnModeChangedListener(java.util.concurrent.Executor { task -> mainHandler.post(task) }, audioModeChangedListener)
      } catch (e: Exception) { AudioDiag.logError("CALL_AUDIO_LISTENER", "reason=register_failed type=${e.javaClass.simpleName}") }
    }
    callAudioBlocked = false
    refreshCallAudioState("service_start")
    callStatePollRunning = true
    mainHandler.post(callStatePoll)
    try {
      registerReceiver(screenStateReceiver, android.content.IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
      })
    } catch (_: Exception) {}
    // RUNDA TEST-1 (2026-10-03) — debug-only injection into the NATIVE command pipeline
    // (handleNativeCommandFlow), for scripts/regression/run.sh to exercise tryNativeYoutubeOpenSearch/
    // tryNativeOpenApp/tryNativeYoutube without needing real audio. Implicit OFF: DEBUG_INJECT does
    // nothing unless DEBUG_INJECT_ENABLE was sent first (SharedPreferences flag, default false,
    // never flipped by production code). Revert: remove this registerReceiver call, the receiver
    // field below, and the unregisterReceiver call in onDestroy.
    try {
      val filter = android.content.IntentFilter().apply {
        addAction("com.benson.butler.DEBUG_INJECT")
        addAction("com.benson.butler.DEBUG_INJECT_ENABLE")
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(debugInjectReceiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        registerReceiver(debugInjectReceiver, filter)
      }
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

    if (intent?.action == ACTION_TEST_SIMULATE_HANDOFF_MISS) {
      handleTestSimulateHandoffMiss(intent)
      return START_STICKY
    }

    if (intent?.action == ACTION_TEST_SIMULATE_WAKE_ACCEPT) {
      handleTestSimulateWakeAccept()
      return START_STICKY
    }

    // WAKE HEALTH DIAGNOSIS — JS pushes the honest notification text (the ongoing notification
    // must not keep claiming "listening" when the recognizer is actually stopped / mic-held).
    // Only re-issues the SAME notification id via NotificationManager — no startForeground(), so
    // no Android 14+ FGS-restart SecurityException, no wakelock/watchdog churn.
    if (intent?.action == ACTION_UPDATE_NOTIFICATION) {
      if (isRunning) {
        val t = intent.getStringExtra(EXTRA_TITLE) ?: "BENSON"
        val b = intent.getStringExtra(EXTRA_BODY) ?: "BENSON is listening."
        try {
          (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(t, b))
        } catch (_: Exception) {}
      }
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
      // E1-1: nu mai aducem Activity-ul în față ca „recuperare" — asta pornea din cod, nu de la user.
      if (WAKE_AND_RECOVERY_BRING_TO_FRONT) { try { bringActivityToFront() } catch (_: Exception) {} }
      return START_STICKY
    }
    AudioDiag.log(this, "SERVICE_FOREGROUND_STARTED", "")
    acquireWakeLock()
    scheduleWatchdog()
    startWakePokeLoop() // ROUND_WAKE_STATE_BUG_1 — native heartbeat re-arms the JS wake loop while backgrounded
    // Local keyword detector auto-arms in this foreground service, independently of Activity/JS
    // lifecycle. The command audio buffer is handed to the existing transcription/executor path.
    if (nativeWakeAvailable()) {
      mainHandler.post { armNativeWake("service_start") }
    } else {
      AudioDiag.logError("NATIVE_WAKE_UNAVAILABLE", "reason=no_compatible_local_model cloud_fallback_disabled=true")
    }
    // Removed unconditional startHotwordLoop() here (2026-08-24, confirmed live root cause):
    // this fired on EVERY service (re)start regardless of which engine JS actually wants —
    // JS's own boot sequence (app/index.tsx's phase-'chat' useEffect) already calls
    // resumePassiveWake() right after starting this service specifically to choose the engine
    // (defaults to 'local', the working Whisper-based one on this device; native SpeechRecognizer
    // is confirmed broken here — see AUDIO_DIAGNOSIS_REPORT.md). The two raced: if this post()
    // landed before JS's pauseHotword() call, native won and kept running uncontested — confirmed
    // live via a fresh capture: GATE_RMS/WAKE_MIC cycling every 1-5s for over a minute straight,
    // every single burst ending in STT_ERROR ERROR_NO_MATCH (the long-documented device-level
    // SpeechRecognizer failure), with local Whisper never getting a turn. That rapid repeated
    // mic-burst cycle is also the most likely explanation for the separately-reported "listens for
    // a fraction of a second" / other apps' audio briefly cutting out symptom — each burst opens
    // and immediately closes the mic. ACTION_REVIVE and ACTION_RESUME_HOTWORD below still call
    // startHotwordLoop() explicitly and are untouched — those are deliberate, JS/user-triggered
    // requests for the native engine specifically, not an unconditional default.
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    AudioDiag.log(this, "SERVICE_TASK_REMOVED", "hotwordLoopRunning=$hotwordLoopRunning")
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    AudioDiag.log(this, "SERVICE_DESTROY", "hotwordLoopRunning=$hotwordLoopRunning")
    isRunning = false
    releaseDuckFocus() // W-1 — don't leak held audio focus across a service restart
    try { nativeTts?.stop(); nativeTts?.shutdown() } catch (_: Exception) {} // FIX_NATIVE_WAKE_ACK_1
    try { heedAec?.release() } catch (_: Exception) {} // RUNDA_N3 TASK D
    try { unregisterReceiver(debugInjectReceiver) } catch (_: Exception) {} // RUNDA TEST-1
    mainHandler.removeCallbacks(wakeVerifyWarmRunnable) // FIX_WAKE_VERIFY_LATENCY_1
    expo.modules.accessibility.BensonAccessibilityService.onHeadsetButton = null // ADAOS K-1
    if (instance === this) instance = null
    stopWakePokeLoop()
    callStatePollRunning = false
    mainHandler.removeCallbacks(callStatePoll)
    try { getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(callPrefsListener) } catch (_: Exception) {}
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).removeOnModeChangedListener(audioModeChangedListener) } catch (_: Exception) {}
    }
    try { microWakeWord?.stop() } catch (_: Exception) {}
    microWakeWord = null
    try { heedWakeWord?.stop() } catch (_: Exception) {}
    heedWakeWord = null
    try { nativeCloudWake?.stop() } catch (_: Exception) {}
    nativeCloudWake = null
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
    // ROUND_BENSON_STABILIZATION_CLEANUP_1 — proven duplicate-engine gap: every call site
    // (ACTION_REVIVE, ACTION_RESUME_HOTWORD, resumeHotwordAndNotify) called this unconditionally,
    // with no check for whether NativeCloudWake should be the sole wake authority instead. On a
    // build with native wake credentials configured (this one), that would run the weaker legacy
    // SpeechRecognizer-only loop (no cloud STT, no wake-name fuzzy matching) IN PARALLEL with
    // NativeCloudWake, competing for the mic. Defer to the native engine when it's available —
    // on a build with no native model/credentials this is unchanged (nativeWakeAvailable()=false).
    if (nativeWakeAvailable()) {
      AudioDiag.log(this, "HOTWORD_LOOP_SUPERSEDED", "reason=native_wake_available")
      armNativeWake("legacy_loop_superseded")
      return
    }
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
            AudioDiag.log(this@BensonForegroundService, "WAKE_DETECT", "engine=porcupine keyword=benson confidence=n/a")
            AudioDiag.log(this@BensonForegroundService, "WAKE_MIC", "state=STOP ts=${System.currentTimeMillis()} engine=porcupine")
            AudioDiag.log(this@BensonForegroundService, "MODE_TRANSITION", "from=PASSIVE_WAKE to=COMMAND reason=WAKE_MATCH engine=porcupine")
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
      AudioDiag.log(this@BensonForegroundService, "STT_FINAL", "session=$hotwordSessionId component=hotword_loop resultCount=${matches?.size ?: 0} chars=${matches?.firstOrNull()?.length ?: 0}")
      val normalized = matches?.firstOrNull() ?: ""
      val hit = matches?.firstNotNullOfOrNull { WAKE_REGEX.find(it) }
      AudioDiag.log(this@BensonForegroundService, "WAKE_EVALUATION", "session=$hotwordSessionId source=final normalizedText=\"$normalized\" matched=${hit != null}")
      if (hit != null) {
        // Group 2 is whatever followed the name in the same breath ("Benson, deschide Waze")
        // — if present, skip the extra round-trip and process it immediately instead of
        // starting a second empty listening session.
        val commandTail = hit.groupValues.getOrNull(2)?.trim().orEmpty()
        Log.i(TAG, "wake word matched, commandTailChars=${commandTail.length}")
        AudioDiag.log(this@BensonForegroundService, "WAKE_ACCEPTED", "session=$hotwordSessionId commandTailChars=${commandTail.length}")
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
  // PROBA RMS ÎN APEL (2026-10-04, user-directed) — "doar măsurare, nicio schimbare de
  // comportament. Blocajul rămâne activ." Reutilizează probeGateRms (deja existent, proven) o
  // dată pe secundă cât durează apelul WhatsApp, logând WA_CALL_MIC_PROBE rms=… . Nu modifică
  // armNativeWake/isCallAudioActive — wake-ul rămâne blocat exact ca azi. Revert: scoate cele două
  // apeluri startCallMicProbe()/stopCallMicProbe() din refreshCallAudioState.
  @Volatile private var callMicProbeActive = false

  private fun startCallMicProbe() {
    if (callMicProbeActive) return
    callMicProbeActive = true
    callMicProbeTick()
  }

  private fun stopCallMicProbe() {
    callMicProbeActive = false
  }

  private fun callMicProbeTick() {
    if (!callMicProbeActive) return
    probeGateRms { rms ->
      if (callMicProbeActive) AudioDiag.log(this, "WA_CALL_MIC_PROBE", "rms=$rms")
    }
    mainHandler.postDelayed({ callMicProbeTick() }, 1000)
  }

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

  private fun onHotwordDetected(commandTail: String, audioFilePath: String? = null) {
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
    // E1-1 (2026-09-07): bringActivityToFront() on wake is gated OFF. It was only ever best-effort
    // here — starting an Activity from a background Service is exactly the pattern ColorOS's
    // background-activity restrictions block, so it silently no-op'd most of the time on this
    // device anyway. The bubble + wake ring below (WindowManager overlays, not subject to the
    // background-start restriction) stay as the visible "BENSON heard you" cue. The user surfaces
    // the full UI by tapping the bubble. Revert: WAKE_AND_RECOVERY_BRING_TO_FRONT = true.
    if (WAKE_AND_RECOVERY_BRING_TO_FRONT) bringActivityToFront()
    showBubbleNative()
    showWakeRingNative()

    // ROUND_WAKE_NATIVE_TO_JS_ACK_1 — ALWAYS stored now, not just when no listener is registered.
    // Proven live: hasListenerRegistered=true and sendEvent() still never reached JS's actual
    // callback (screen was off; JS thread presumably Doze-suspended at the exact delivery
    // instant) — the previous design had zero recovery for that case, only for "module never
    // initialized at all." takePendingWakeCommand() (atomic read+clear, below) is the ONE
    // consumption point for both the live-event path and the heartbeat-poll fallback in
    // app/index.tsx's wakePokeSub — the same command can never be processed twice.
    pendingWakeCommand.set(commandTail)
    pendingWakeAudioFile.set(audioFilePath)
    pendingWakeCommandEpoch += 1
    armWakeHandoffWatchdog()

    val hasJsListener = onWakeWordDetected != null
    AudioDiag.log(this, "WAKE_EVENT_EMITTED_TO_JS", "hasListenerRegistered=$hasJsListener commandTailChars=${commandTail.length}")
    if (hasJsListener) {
      onWakeWordDetected?.invoke(commandTail, audioFilePath)
      return
    }
    // Confirmed live 2026-07-17: if the JS/Expo bridge isn't alive at this exact instant (OnePlus
    // killed the process, or this module hasn't finished OnCreate yet), the callback above was a
    // silent no-op — hotwordLoopRunning was already set false above, so the mic loop stayed
    // paused forever with nothing left to ever resume it (ACTION_RESUME_HOTWORD only ever comes
    // from the JS side that never received this event). pendingWakeCommand (above) lets the
    // module's OnCreate flush it the moment JS actually comes back, and resuming the loop here
    // directly (not waiting on a JS round-trip that has no listener to receive it) means the mic
    // doesn't stay dead in the meantime either.
    // Keep COMMAND_STT ownership while this durable command waits. The existing handoff watchdog
    // will start the headless consumer and re-arm wake after 5s. Re-arming here used to clear
    // COMMAND_STT, disabling that watchdog; subsequent detections could then overwrite the pending
    // command before JS had consumed it (observed on-device: two wakes, zero executor handoff).
    AudioDiag.log(this, "WAKE_COMMAND_QUEUED", "commandTailChars=${commandTail.length} reason=no_js_listener watchdog=armed")
  }

  // ROUND_WAKE_NATIVE_TO_JS_ACK_1 — much shorter than OwnerWatchdog's general 45s (that one covers
  // legitimate long-running JS STT round-trips too, which this must not cut short). Only fires if
  // NOTHING — neither the live event path nor the heartbeat-poll fallback — has taken the pending
  // command within TIMEOUT_MS, i.e. JS never got a chance to react at all. Safe to release the mic
  // early: the durable pendingWakeCommand survives the release and is still delivered the moment
  // JS actually calls takePendingWakeCommand(), whenever that ends up being.
  private object WakeHandoffWatchdog { const val TIMEOUT_MS = 5_000L }
  @Volatile private var pendingWakeCommandEpoch = 0

  private fun armWakeHandoffWatchdog() {
    val epoch = pendingWakeCommandEpoch
    mainHandler.postDelayed({
      if (pendingWakeCommandEpoch == epoch && micOwner == "COMMAND_STT") {
        AudioDiag.logError("WAKE_HANDOFF_RECOVER", "reason=not_consumed timeoutMs=${WakeHandoffWatchdog.TIMEOUT_MS}")
        setMicOwner("NONE", "wake_handoff_recover")
        armNativeWake("wake_handoff_recover")
        // NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19, device-proven) — the two lines above don't
        // clear pendingWakeCommand (only takePendingWakeCommand() does), so the command is still
        // durably held. Start Headless JS so it still gets processed even though the live JS
        // thread never woke up in time to consume it — the task calls the SAME
        // takePendingWakeCommand() every other path uses, so it can never double-process.
        try {
          startService(Intent(this, BensonWakeHeadlessTaskService::class.java))
          AudioDiag.log(this, "WAKE_HEADLESS_START", "reason=not_consumed")
        } catch (e: Exception) {
          AudioDiag.logError("WAKE_HEADLESS_START_FAILED", "error=\"${e.javaClass.simpleName}: ${e.message}\"")
        }
      }
    }, WakeHandoffWatchdog.TIMEOUT_MS)
  }

  // HEADLESS_WIRING_TEST_1 (2026-09-19) — adb-only, debug-build-only. Sets the SAME durable
  // pendingWakeCommand and arms the SAME WakeHandoffWatchdog a real "not consumed in time" wake
  // event would, WITHOUT touching the wake-word engine, STT, or the mic pipeline — it does not
  // call armNativeWake()/startHotwordLoop() or acquire any audio. This isolates one question only:
  // does the pending->watchdog->BensonWakeHeadlessTaskService->takePendingWakeCommand->runMission
  // wiring work, independent of whether the JS thread is actually suspended. It does NOT prove
  // recovery from a genuinely inert React runtime — that still requires a real device freeze (see
  // NATIVE_JS_RELIABLE_HANDOFF_FIX_1's own device-test notes). Result label: HEADLESS_WIRING_PASS,
  // never BACKGROUND_RECOVERY_PASS — the two are not the same claim.
  //
  // Refused and logged (never a silent no-op) unless the running APK is itself debuggable
  // (android:debuggable, read from the live ApplicationInfo — not a Gradle BuildConfig field,
  // which this module does not currently generate) — this can never fire against the signed
  // release build regardless of whether this code ships in it.
  private fun handleTestSimulateHandoffMiss(intent: Intent) {
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) {
      AudioDiag.logError("HEADLESS_WIRING_TEST_REFUSED", "reason=not_debuggable")
      return
    }
    val commandTail = intent.getStringExtra("commandTail") ?: "deschide Netflix"
    AudioDiag.log(this, "HEADLESS_WIRING_TEST_SIMULATE", "commandTail=\"$commandTail\"")
    setMicOwner("COMMAND_STT", "headless_wiring_test")
    // HEADLESS_WIRING_TEST_ISOLATION_1 — writes to the TEST-ONLY field (pendingWakeCommand,
    // above, is never touched here), so the live path structurally cannot see or consume this.
    // armWakeHandoffWatchdog() itself is generic (only checks micOwner/epoch, never reads either
    // command field directly) — reusing it unmodified still arms the same real 5s timer and, on
    // expiry, starts the same BensonWakeHeadlessTaskService.
    pendingHeadlessTestCommand.set(commandTail)
    pendingWakeCommandEpoch += 1
    armWakeHandoffWatchdog()
  }

  // FIX_NATIVE_WAKE_ACK_1 — NOT debuggable-gated, unlike handleTestSimulateHandoffMiss above: the
  // debug and release buildTypes sign with different keystores here (app/build.gradle, out of
  // scope to touch), so a debug-variant APK can't be adb-installed over the release install
  // without uninstalling first (wiping local app data) — unacceptable just to self-test. This
  // action only replays the exact same onHeedWakeDetected() path a real "Benson" already triggers
  // (speaks the ack, opens a session) with fixed benign parameters; it executes no command and
  // bypasses no confirmation gate. Calls the real onHeedWakeDetected() path with a synthetic
  // accept (no model/mic involved), so WAKE_ACK's latency and zero-JS-dependency can be verified
  // by adb alone: background the app first (input keyevent KEYCODE_HOME), fire this action, then
  // read WAKE_ACK from logcat. Revert: delete this function, its ACTION_TEST_SIMULATE_WAKE_ACCEPT
  // constant, and the onStartCommand branch that calls it.
  private fun handleTestSimulateWakeAccept() {
    AudioDiag.log(this, "WAKE_ACCEPT_TEST_SIMULATE", "")
    onHeedWakeDetected("Benson", 0.9f, "", System.currentTimeMillis())
  }

  // Atomic read+clear — the single consumption point for BOTH the live onWakeWordDetected event
  // path and the heartbeat-poll fallback, so the same wake is never handled twice. Returns null if
  // nothing is pending (already consumed, or none fired). commandTail="" is a valid result (bare
  // "Benson"), distinct from null.
  fun takePendingWakeCommand(): String? {
    // DATA_RACE_FIX_1 — getAndSet(null) is one indivisible operation: whichever caller (live JS
    // event path or Headless recovery task) happens to call this first gets the non-null value
    // and every other caller — no matter how close in time — gets null. Only log when a value was
    // actually taken, never on the (now expected, harmless) null case.
    val cmd = pendingWakeCommand.getAndSet(null) ?: return null
    pendingWakeCommandEpoch += 1
    AudioDiag.log(this, "WAKE_PENDING_TAKEN", "commandTailChars=${cmd.length}")
    return cmd
  }

  // HEADLESS_WIRING_TEST_ISOLATION_1 — the ONLY consumer of pendingHeadlessTestCommand. Called
  // exclusively from the Headless JS task (wakeCommandTask.ts), never from the live event/
  // heartbeat-fallback paths — see that field's own doc for why this makes the wiring test
  // exercise exactly the Headless path with zero possibility of the live path racing it ahead.
  // getAndSet(null) clears the marker atomically the instant it's taken, same discipline as
  // takePendingWakeCommand() above.
  fun takePendingHeadlessTestCommand(): String? {
    val cmd = pendingHeadlessTestCommand.getAndSet(null) ?: return null
    AudioDiag.log(this, "WAKE_PENDING_TAKEN", "commandTailChars=${cmd.length} consumer=headless")
    return cmd
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

  // Wake handoff must reveal the active overlay immediately, before the JS event/state update.
  // The overlay service's no-action start is intentionally a no-op while BENSON sleeps, so send
  // an explicit, non-private LISTENING state here. JS later replaces it with the transcript and
  // processing/result state. The existing meter is driven by real capture RMS from JS.
  private fun showBubbleNative() {
    val intent = Intent().apply {
      component = ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
      action = "expo.modules.overlay.ACTION_UPDATE_STATUS"
      putExtra("state", "ASCULT")
      putExtra("transcript", "")
      putExtra("visible", true)
      putExtra("terminal", false)
      putExtra("turn_id", System.currentTimeMillis())
      putExtra("dismiss_delay_ms", 0L)
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
    const val ACTION_UPDATE_NOTIFICATION = "expo.modules.foregroundservice.ACTION_UPDATE_NOTIFICATION"
    // HEADLESS_WIRING_TEST_1 (2026-09-19) — debug-build-only, adb-triggered. Tests the wiring
    // (pendingWakeCommand -> WakeHandoffWatchdog -> BensonWakeHeadlessTaskService ->
    // takePendingWakeCommand -> runMission) in isolation, without touching the wake-word engine,
    // STT, or the mic pipeline at all. Refused and logged on a non-debuggable build — see
    // handleTestSimulateHandoffMiss(). Never invoked by any production code path.
    const val ACTION_TEST_SIMULATE_HANDOFF_MISS = "expo.modules.foregroundservice.TEST_SIMULATE_HANDOFF_MISS"
    // FIX_NATIVE_WAKE_ACK_1 (2026-10-02) — debug-build-only, adb-triggered. Calls
    // onHeedWakeDetected() directly with a synthetic accept, so speakNativeAck()/WAKE_ACK can be
    // verified in isolation (latency, zero JS dependency) without needing a real spoken "Benson" or
    // a trained model loaded. Same FLAG_DEBUGGABLE guard as ACTION_TEST_SIMULATE_HANDOFF_MISS.
    // Never invoked by any production code path.
    const val ACTION_TEST_SIMULATE_WAKE_ACCEPT = "expo.modules.foregroundservice.TEST_SIMULATE_WAKE_ACCEPT"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val WATCHDOG_INTERVAL_MS = 60_000L
    // ROUND_WAKE_STATE_BUG_1 — native heartbeat that re-arms the (setTimeout-frozen while
    // backgrounded) JS wake loop. 3 s: fast enough that a wake attempt after a background/idle
    // transition finds the loop re-armed within one poke; cheap (one prefs read + one event).
    const val WAKE_POKE_INTERVAL_MS = 3_000L
    // Comfortably above EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS (1800ms) plus real-world
    // recognizer-service latency — long enough that it never fires on a burst that's genuinely
    // still listening, short enough that a hung burst recovers in well under the time it'd take a
    // user to notice and manually reopen the app.
    const val BURST_TIMEOUT_MS = 8_000L

    // E1-1 / E1-0 (2026-09-07, product-owner-directed): BENSON nu se mai aduce singur în prim-plan.
    // La wake ("Benson") rămân bula + inelul (indicatori vizibili), dar Activity-ul nu mai fură
    // ecranul; la recuperarea după SecurityException-ul de start FGS, la fel. Butonul REVIVE din
    // notificare (atingere directă a utilizatorului) NU e afectat. Revert: pune true.
    const val WAKE_AND_RECOVERY_BRING_TO_FRONT = false

    // Set by the module while it's alive; invoked when a notification action fires.
    var onStopRequested: (() -> Unit)? = null
    var onListenRequested: (() -> Unit)? = null
    // ROUND_WAKE_STATE_BUG_1 — native heartbeat → module sendEvent("onWakePoke") → JS re-arm.
    var onWakePoke: (() -> Unit)? = null
    var onCallAudioStateChanged: ((Boolean) -> Unit)? = null

    // Invoked when the native hotword loop hears "Benson" — argument is whatever followed the
    // name in the same utterance ("Benson, deschide Waze" -> "deschide Waze"), empty if the name
    // was said alone. JS should process a non-empty tail immediately, otherwise start real
    // command capture.
    var onWakeWordDetected: ((String, String?) -> Unit)? = null

    // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — fired when armSttSessionWatchdog's native timer
    // expires for the still-current session id. Argument is that session's id.
    var onSttWatchdogTimeout: ((String) -> Unit)? = null

    // ROUND_TTS_WATCHDOG_NATIVE_1 — fired when armTtsWatchdog's native timer expires while still
    // armed. No argument — TTS has no session id, only one block can be active at a time.
    var onTtsWatchdogTimeout: (() -> Unit)? = null

    // MIC_RESUME_WATCHDOG_NATIVE_1 — fired when armMicResumeWatchdog's native timer expires while
    // still armed. No argument — same one-at-a-time reasoning as onTtsWatchdogTimeout.
    var onMicResumeWatchdogTimeout: (() -> Unit)? = null

    // CLOUD_FETCH_WATCHDOG_NATIVE_1 — fired when armCloudFetchWatchdog's native timer expires for
    // the still-current requestId. Argument is that request's id (ID-keyed — see companion note).
    var onCloudFetchTimeout: ((String) -> Unit)? = null

    // URGENT_CONFIRMATION_NATIVE_1 — (confirmationId, verdict, transcript). Durable: if no JS
    // listener is registered when the native capture finishes (JS suspended), the result is held
    // in pendingConfirmationResult and flushed the moment the module's OnCreate runs again —
    // same pattern as pendingWakeCommand below, so a YES/NO can never be silently dropped.
    var onConfirmationResult: ((String, String, String) -> Unit)? = null
    @Volatile var pendingConfirmationResult: Triple<String, String, String>? = null

    // Set only when a hotword fired with no JS listener registered (see onHotwordDetected) —
    // BensonForegroundServiceModule's OnCreate flushes and clears this the moment a listener
    // becomes available again, so the wake word isn't lost outright just because JS wasn't ready
    // at the exact instant it was detected.
    //
    // DATA_RACE_FIX_1 (2026-09-20, code-confirmed) — was a plain `var` with no @Volatile and no
    // atomicity: written on the Android main thread (onHotwordDetected /
    // handleTestSimulateHandoffMiss), read on the JS thread (takePendingWakeCommand(), called
    // from both the live onWakeWordDetected path and the Headless JS recovery task). Device log
    // proved the visibility half of this — WAKE_PENDING_TAKEN never fired even though the write
    // demonstrably happened — but a plain @Volatile alone only fixes visibility, not exclusivity:
    // two near-simultaneous readers (live JS and Headless) could each observe the same non-null
    // value before either cleared it, executing the same command twice. AtomicReference +
    // getAndSet(null) makes read-and-clear a single indivisible operation, so exactly one caller
    // ever gets a given command. Deliberately NOT solving consume-before-ACK here (see
    // takePendingWakeCommand()'s own comment) — that is a separate, larger change (persist the
    // command until the mission is actually accepted, not just until it's read).
    val pendingWakeCommand = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val pendingWakeAudioFile = java.util.concurrent.atomic.AtomicReference<String?>(null)

    // HEADLESS_WIRING_TEST_ISOLATION_1 (2026-09-20) — a SEPARATE field, deliberately never
    // touched by the live onWakeWordDetected path or the JS heartbeat-poll fallback (both call
    // ONLY takePendingWakeCommand() / pendingWakeCommand above). handleTestSimulateHandoffMiss()
    // writes here instead of pendingWakeCommand, so an injected wiring-test command is
    // structurally impossible for the live path to race — only the Headless recovery task
    // (wakeCommandTask.ts, via takePendingHeadlessTestCommand() below) ever reads this field.
    // Zero effect on real wake commands: nothing in the production wake path ever writes or reads
    // this field. Written ONLY from a call already refused unless the running APK is debuggable
    // (see handleTestSimulateHandoffMiss) — impossible to populate in a release build.
    val pendingHeadlessTestCommand = java.util.concurrent.atomic.AtomicReference<String?>(null)

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
