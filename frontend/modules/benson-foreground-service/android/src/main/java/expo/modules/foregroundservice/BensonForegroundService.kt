package expo.modules.foregroundservice

import android.app.ActivityManager
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

  // PASUL 2 TEST B (07.10.2026) — passive monitoring for OxygenOS-style kills that no adb command
  // can trigger on demand (am kill no-ops on an FGS-hosting process; am force-stop enters stopped
  // state, which defeats the point). Set once in logProcessStartAndCountKill(), read once by the
  // first scheduleWakeAliveCheck() tick after this process start.
  @Volatile private var pendingRecoveryCredit = false
  @Volatile private var recoveryCounted = false

  // PASUL 2 TEST B — getHistoricalProcessExitReasons (API 30+) is the only source for WHY the
  // previous process instance ended; there is no broadcast/callback for it. A non-empty result at
  // onCreate means a prior process for this app existed and ended — not necessarily a "kill" in
  // the hostile sense (REASON_USER_REQUESTED from force-stop would also show up here), so the
  // counter is honestly "process restarts observed", not strictly "hostile kills". reason is the
  // raw android.app.ApplicationExitInfo.REASON_* int — deliberately not remapped to a name here,
  // to avoid guessing at a mapping I haven't verified against today's SDK.
  private fun logProcessStartAndCountKill() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      AudioDiag.log(this, "PROCESS_START", "reason=unavailable_below_api30")
      return
    }
    try {
      val am = getSystemService(ActivityManager::class.java)
      val exits = am?.getHistoricalProcessExitReasons(packageName, 0, 1)
      if (exits.isNullOrEmpty()) {
        AudioDiag.log(this, "PROCESS_START", "reason=none")
        return
      }
      val info = exits[0]
      AudioDiag.log(this, "PROCESS_START", "reason=${info.reason} description=\"${info.description}\"")
      pendingRecoveryCredit = true
      val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      val count = prefs.getInt("process_kill_count", 0) + 1
      prefs.edit().putInt("process_kill_count", count).apply()
    } catch (e: Exception) {
      AudioDiag.log(this, "PROCESS_START", "reason=query_failed error=${e.javaClass.simpleName}")
    }
  }

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
      // PASUL 2 TEST B — credits one recovery per process start, on the first tick that finds the
      // wake loop actually running again after a counted prior exit.
      if (running && pendingRecoveryCredit && !recoveryCounted) {
        recoveryCounted = true
        val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("process_recovery_count", 0) + 1
        prefs.edit().putInt("process_recovery_count", count).apply()
        AudioDiag.log(this, "PROCESS_RECOVERY", "count=$count")
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

  // E0 (08.10.2026, ZIUA v3) — flag + revert (G2). false = comportamentul dinainte de azi (capture
  // reia imediat după TTS_DONE, fără filtru de text). Revert: E0_SELF_ECHO = false.
  private val E0_SELF_ECHO = true
  private val E0_TTS_TAIL_SETTLE_MS = 300L
  private val E0_SIMILARITY_THRESHOLD = 0.8
  // Last 2 texts BENSON spoke via speakNativeFallbackThenCapture (reports/questions) — "Da,
  // Master." from the protected ack is a fixed, known constant, included in the comparison
  // without needing to touch that function to record it dynamically.
  private val recentSpokenTexts = ArrayDeque<String>(2)
  // synchronized: written on the main thread (speakNativeFallbackThenCapture), read on the
  // background "benson-native-cmd-capture" thread (isSelfEchoText, via startFollowUpCapture's
  // onEnd) — ArrayDeque itself isn't thread-safe.
  private fun recordSpokenTextForEcho(text: String) = synchronized(recentSpokenTexts) {
    if (recentSpokenTexts.size >= 2) recentSpokenTexts.removeFirst()
    recentSpokenTexts.addLast(text)
  }
  // Returns (isEcho, similarity). Normalizes both sides with the existing normalizeForMatch
  // (diacritics/case/punctuation already stripped there). "Conține textul rostit" = substring
  // either direction; similarity = 1 - normalized Levenshtein distance (WakeVerifyMatcher, already
  // pure/JUnit-tested, reused rather than a second edit-distance implementation).
  private fun isSelfEchoText(transcript: String): Pair<Boolean, Double> {
    val t = normalizeForMatch(transcript)
    if (t.isBlank()) return false to 0.0
    // FIX_E0_SHORT_ANSWER_FALSE_ECHO_1 (08.10.2026, device-proven) — a bare "da" was being flagged
    // as self-echo at sim=1.00 because it's a literal substring of the hardcoded "Da, Master."
    // candidate ("da master" contains "da"). Any short transcript risks this same coincidental
    // containment against a long candidate — classifyConfirmationNative's own word-boundary
    // YES_WORDS/NO_WORDS matching already handles short real answers correctly; this filter exists
    // for longer echoed SENTENCES, not 2-3 letter words. Below this length, never call it echo.
    if (t.length <= 3) return false to 0.0
    val snapshot = synchronized(recentSpokenTexts) { recentSpokenTexts.toList() }
    val candidates = (snapshot + "Da, Master.").map { normalizeForMatch(it) }.filter { it.isNotBlank() }
    var best = 0.0
    for (c in candidates) {
      if (t.contains(c) || c.contains(t)) return true to 1.0
      val dist = WakeVerifyMatcher.levenshtein(t, c)
      val sim = 1.0 - dist.toDouble() / maxOf(t.length, c.length).coerceAtLeast(1)
      if (sim > best) best = sim
    }
    return (best >= E0_SIMILARITY_THRESHOLD) to best
  }

  // P5 (08.10.2026, ZIUA v3) — flag + revert (G2). false = comportamentul dinainte de azi
  // (sesiunea rămâne deschisă până la timeout-ul de 8s după orice rostire, inclusiv rapoarte
  // finale). Revert: AC2F_P5_SESSION_CLOSE = false.
  private val AC2F_P5_SESSION_CLOSE = true

  // J1 (12.10.2026, PLANUL_FINAL, RUNDA J) — "un singur creier pe drumul vocal": JS nu mai preia
  // NICIO comandă nerecunoscută de drumul nativ, indiferent dacă BENSON e în prim-plan. Revert:
  // JS_VOICE_HANDOFF = true => comportamentul vechi exact (isBensonForeground() ? js_handoff :
  // native fallback).
  private val JS_VOICE_HANDOFF = false

  // LEGEA PERSONALULUI BENSON (14.10.2026, decizie permanenta a proprietarului; spec normativ
  // docs/specs/LEGEA_PERSONALULUI_BENSON.md, derivat din PASUL_1_REGISTRY + PASUL_1_MOTOR).
  // M1: registrul e PARTE DIN MOTOR, porneste la fiecare start de serviciu, mereu activ, si
  // devine singurul inventar de aplicatii (findLaunchablePackage citeste din el).
  // APP_REGISTRY = false e DOAR revertul de urgenta: enumerare live la fiecare apel, adica exact
  // comportamentul de dinainte de 14.10.2026. Nimic altceva nu depinde de flag.
  private val APP_REGISTRY = true

  // FIX_BRAIN_OPEN_APP_GATE_1 (14.10.2026) — vezi PendingAppOpen pentru cauza dovedita din log.
  // true = o deschidere de aplicatie PROPUSA DE CREIER trece prin confirmare ("Deschid X?").
  // false = revertul exact la comportamentul de dinainte (creierul lanseaza direct).
  // Calea determinista a parserului nu e afectata in niciun caz.
  private val BRAIN_OPEN_APP_CONFIRM = true

  // V1 (08.10.2026, Motor M1) — flag + revert (G2). Pure logging addition: the actual "zero Gata
  // fără dovadă" decisions (result.success / verified) already existed and were already correct in
  // both call sites this touches (WA actions, YouTube play) — this only adds the standardized
  // OUTCOME log line Motor's spec asks for, on top of that existing decision. false = no OUTCOME
  // line, otherwise unchanged behavior either way.
  private val V1_TRUTHFUL_REPORTER = true

  // Exact aceeași listă de 5 verificată la începutul handleNativeCommandFlow — o întrebare
  // (confirmare/disambiguare) setează unul dintre ele ÎNAINTE de a fi rostită și îl golește abia
  // după răspuns, deci "niciunul setat" la finalul unui TTS înseamnă sigur raport final, nu
  // întrebare încă nerezolvată.
  // FIX_LIVE_CONVERSATION_CONTINUES_1 (20.10.2026, mandat "BENSON OPENAI VOICE CHATBOT") — CAUZA
  // DOVEDITĂ a plângerii "nu pot conversa natural": finishBrainCommandSuccess (apelat de calea
  // conversaţională fără tool ŞI de instrumentul `say`) închidea sesiunea necondiţionat după
  // FIECARE replică a agentului ("not_opened reason=final_answer_benson_stays_silent"), chiar şi
  // când agentul tocmai PUSESE o întrebare de clarificare — Masterul trebuia să repete "Benson"
  // ca să răspundă la propria întrebare a lui BENSON. `agentAwaitingReply` e exact ca un
  // `pendingXxx` determinist (acelaşi tipar): setat ÎNAINTE de a rosti o întrebare/invitaţie la
  // continuare, citit aici ca să ţină sesiunea deschisă, golit la următorul răspuns al agentului.
  @Volatile private var agentAwaitingReply = false

  // FIX_LIVE_PENDING_PROPOSAL_1 (20.10.2026, ordin "DEVICE_FAIL: promite căutarea, dar nu o
  // execută") — cand agentul propune o cautare concreta intr-o alta aplicatie (ex.
  // incompatibilitate semantica: "Spotify nu e potrivit, pot cauta pe Amazon Shopping?"), pachetul
  // si interogarea se retin STRUCTURAT aici — exact acelasi tipar ca pendingNav/pendingWaAction —
  // ca urmatorul raspuns afirmativ sa execute DIRECT (open_app + search_in_app confirmed=true),
  // fara alt apel OpenAI de reconstructie a intentiei. appPkg vine DEJA validat prin AKA
  // (findLaunchablePackage, acelasi resolver ca open_app) la momentul STOCARII, nu doar la executie.
  private data class PendingAgentProposal(
    val appPkg: String,
    val appLabel: String,
    val query: String,
    val createdAtMs: Long,
    val originalCommand: String,
  )
  // Siguranta #1 din ordin: o propunere veche, neexecutata, nu ramane valabila la nesfarsit.
  private val PENDING_AGENT_PROPOSAL_TTL_MS = 90_000L
  @Volatile private var pendingAgentProposal: PendingAgentProposal? = null

  // Siguranta #3 din ordin: "nu doar o lista rigida de cuvinte romanesti... pastreaza BENSON
  // multilingv" — distinct, DELIBERAT, de classifyConfirmationNative/CONFIRM_YES_RE/CONFIRM_NO_RE
  // (subsistem deja validat, NEATINS, folosit de pendingNav/pendingWaAction etc.). "si"/"sim" sunt
  // excluse deliberat din lista de DA — ar coincide, dupa eliminarea diacriticelor, cu "și" (RO,
  // conjunctie foarte frecventa), producand acceptari false pe fraze nelegate de propunere.
  private val AGENT_PROPOSAL_YES_RE = Regex(
    "\\b(da|sigur|exact|asa|perfect|cauta|ok|okay|yes|yeah|yep|sure|ja|genau|oui)\\b"
  )
  private val AGENT_PROPOSAL_NO_RE = Regex(
    "\\b(nu|anuleaza|renunta|stop|opreste|no|nein|non|nicht)\\b"
  )

  private fun classifyAgentProposalAcceptance(text: String): String {
    val norm = normalizeForMatch(text)
    if (AGENT_PROPOSAL_NO_RE.containsMatchIn(norm)) return "NO"
    if (AGENT_PROPOSAL_YES_RE.containsMatchIn(norm)) return "YES"
    return "UNKNOWN"
  }

  // Siguranta #2 din ordin: consuma propunerea necondiTionat (DA, NU sau necunoscut deopotriva) —
  // orice alt verdict decat DA lasa comanda sa treaca mai departe NESCHIMBATA prin restul
  // lantului (o comanda noua/un refuz/ambiguitate invalideaza simplu propunerea, nu o executa).
  private fun handlePendingAgentProposalAnswer(command: String): Boolean {
    val pending = pendingAgentProposal ?: return false
    pendingAgentProposal = null
    val ageMs = System.currentTimeMillis() - pending.createdAtMs
    if (ageMs > PENDING_AGENT_PROPOSAL_TTL_MS) {
      AudioDiag.log(this, "AGENT_PROPOSAL", "verdict=expired ageMs=$ageMs")
      return false
    }
    val verdict = classifyAgentProposalAcceptance(command)
    AudioDiag.log(this, "AGENT_PROPOSAL", "verdict=$verdict pkg=${pending.appPkg} ageMs=$ageMs")
    if (verdict != "YES") return false
    executeAgentProposal(pending)
    return true
  }

  // Executie DIRECTA, autorizata STRICT pentru cautari (ordin, siguranta #5): reutilizeaza EXACT
  // aceleasi doua instrumente deja validate ale agentului (open_app, search_in_app confirmed=true),
  // niciun cod de executie nou — doar apelate direct, fara bucla LLM. SURVIVOR ramane acelasi
  // (search_in_app isi pastreaza propria verificare de rezultat, neschimbata).
  private fun executeAgentProposal(pending: PendingAgentProposal) {
    Thread({
      val t0 = System.currentTimeMillis()
      AudioDiag.log(this, "AGENT_PROPOSAL", "action=execute_start pkg=${pending.appPkg} query=\"${pending.query}\"")
      val openArgs = JSONObject().apply { put("name", pending.appLabel) }
      val (openResult, _) = synchronized(agentFingerLock) {
        runAgentToolLocked(BensonAgentTools.OPEN_APP, openArgs, pending.originalCommand)
      }
      val openOk = runCatching { JSONObject(openResult).optBoolean("ok", false) }.getOrDefault(false)
      AudioDiag.log(this, "AGENT_PROPOSAL", "step=open_app ok=$openOk ms=${System.currentTimeMillis() - t0}")
      val finalMsg: String
      if (!openOk) {
        finalMsg = runCatching { JSONObject(openResult).optString("detail") }.getOrDefault("")
          .ifBlank { "Nu am reușit să deschid ${pending.appLabel}." }
      } else {
        val t1 = System.currentTimeMillis()
        val searchArgs = JSONObject().apply { put("query", pending.query); put("confirmed", true) }
        val (searchResult, _) = synchronized(agentFingerLock) {
          runAgentToolLocked(BensonAgentTools.SEARCH_IN_APP, searchArgs, pending.originalCommand)
        }
        val searchOk = runCatching { JSONObject(searchResult).optBoolean("ok", false) }.getOrDefault(false)
        val detail = runCatching { JSONObject(searchResult).optString("detail") }.getOrDefault("")
        AudioDiag.log(this, "AGENT_PROPOSAL", "step=search_in_app ok=$searchOk ms=${System.currentTimeMillis() - t1} totalMs=${System.currentTimeMillis() - t0}")
        finalMsg = if (searchOk) {
          "Am căutat „${pending.query}” în ${pending.appLabel}.${if (detail.isNotBlank()) " $detail" else ""}"
        } else {
          detail.ifBlank { "Nu am reușit căutarea „${pending.query}” în ${pending.appLabel}." }
        }
      }
      conversationHistory.add("assistant", finalMsg)
      mainHandler.post {
        agentAwaitingReply = false
        updateBubbleNative(finalMsg, pending.originalCommand, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
        finishBrainCommandSuccess(finalMsg, pending.originalCommand, "agent_proposal_done")
      }
    }, "benson-agent-proposal").start()
  }

  // FIX_LIVE_SEMANTIC_PRECHECK_1 (20.10.2026, "verifică mecanismul de execuție, nu doar
  // systemPrompt") — fapt dinamic, calculat per comandă din acelaşi inventar unic (StaffRegistry,
  // LEGEA PERSONALULUI, zero a doua enumerare), injectat ÎNAINTE de prima decizie a modelului —
  // nu doar o instrucţiune statică. Dacă Masterul numeşte explicit o aplicaţie cu domeniu CUNOSCUT,
  // modelul vede faptul chiar din primul apel şi poate răspunde direct cu «say» (propunere), fără
  // să mai cheme «open_app» deloc — reduce un caz de incompatibilitate de la 3 apeluri OpenAI
  // secvenţiale la 1 singur (dovadă: testul Spotify/lămpi, ~30s → ~10s aşteptat).
  private fun explicitlyNamedAppCompatibilityHint(command: String): String? = try {
    val norm = normalizeForMatch(command)
    val named = StaffRegistry.load(this)
      .filter { !it.archived && it.caps.isNotEmpty() }
      .firstOrNull { m ->
        val label = normalizeForMatch(m.displayName)
        label.length >= 3 && norm.contains(label)
      }
    val hint = named?.let { StaffRegistryCore.namedCapabilityDomainHints(it.caps) }
    if (named == null || hint == null) null else
      "Masterul a numit explicit aplicația „${named.displayName}”. $hint Dacă cererea nu se " +
        "potrivește cu acest domeniu, NU deschide această aplicație — răspunde direct cu «say», " +
        "explică incompatibilitatea și propune o alternativă (proposed_app/proposed_query)."
  } catch (_: Exception) { null }

  private fun hasAnyPendingNativeState(): Boolean =
    pendingWaDisambiguation != null || pendingWaAction != null || pendingAppSearch != null ||
      pendingControlAction != null || pendingYoutubePlay != null || pendingAppOpen != null ||
      pendingNav != null || pendingResultChoice != null || agentAwaitingReply

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
  // URGENT (12.10.2026, user-reported) — Rareș a schimbat motorul TTS de sistem pe Google; vocea
  // ro-RO aleasă o singură dată la initNativeAck() a rămas legată de motorul VECHI —
  // TextToSpeech() leagă instanța de motorul activ DOAR la construcție, nu urmărește schimbările
  // ulterioare din Setări — fără nicio voce ro-RO pe noul motor, engine-ul a căzut pe vocea lui
  // implicită (en-IN). Fix: verificare + re-selecție înaintea FIECĂREI rostiri, în applyTtsRouting
  // (singurul loc deja comun ambelor căi de speak, cf. L8-EXT), nu doar o dată la pornirea
  // serviciului — detectat prin t.defaultEngine, ieftin, fără apel de rețea.
  //
  // FIX_TTS_VOICE_CACHE_1 (19.10.2026, P0 — "accent indian" dupa comenzi tastate) — CAUZA REALA,
  // dovedita in cod: fix-ul de mai sus (12.10) memora motorul INAINTE de a sti daca selectia
  // vocii a reusit (`lastBoundTtsEngine = engine` necondiTionat), apoi sarea peste orice
  // reselectie cat motorul ramanea neschimbat. Daca PRIMA rostire a procesului avea loc inainte ca
  // motorul sa-si fi incarcat lista completa de voci (cursa reala, documentata pentru
  // TextToSpeech), `selectBestTtsVoice` intorcea null, `t.voice` nu se seta explicit, iar engine-ul
  // cadea pe vocea lui implicita (en-IN) — si ramanea asa tot restul procesului, pentru ca
  // `lastBoundTtsEngine` deja se schimbase. Tiparea imediat dupa pornire (DEBUG_INJECT) are o
  // fereastra mai scurta pana la prima rostire decat o comanda vocala reala (care asteapta
  // wake+STT), deci lovea cursa mai des.
  // Logica noua traieste in TtsVoiceSelector.kt (pur, testat JUnit) — aici doar starea si
  // orchestrarea reala a apelurilor Android. `engine` nu mai inseamna singur "configurat cu
  // succes": starea CONFIRMED se obtine DOAR dupa citirea rezultatelor reale ale setLanguage()/
  // setVoice() SI confirmarea ca t.voice chiar e vocea ceruta — niciodata doar din atribuire.
  private var ttsVoiceBinding: TtsVoiceBinding = TtsVoiceBinding(null, TtsVoiceState.NOT_INITIALIZED)
  private var ttsVoiceRetryCount = 0
  private var ttsVoiceLastAttemptAtMs = 0L
  // FIX_WAKE_SELF_ECHO_GUARD_1 (2026-10-03) — see SelfTtsGuard. 0L = "never"/"not speaking".
  // UNIVERSAL_ACTION_STOP, al doilea defect dovedit pe telefon (15.10.2026): un executor NATIV
  // deja pornit (yt_open_and_search) a terminat DUPA stop si a rostit propriul raport —
  //   NATIVE_ROUTE action=interrupt_all -> INTERRUPT_ALL -> TTS "N-am gasit ... pe YouTube."
  // Executorii nativi nu ruleaza prin agentRunId, deci cancelAgentNow() nu-i atinge. In loc sa
  // pun un token in fiecare executor (si sa-i rescriu, interzis), pun garda in SINGURUL punct de
  // rostire: cat e deschisa fereastra, nicio replica a sarcinii anulate nu se aude.
  // Fereastra se ridica imediat ce Masterul da o comanda nouă (handleNativeCommandFlow).
  // LEGEA TACERII (16.10.2026, lege noua a Masterului): „Este interzis sa porneasca singur BENSON,
  // fara wakeword."
  // Cauza dovedita a incidentului de azi: NU exista niciun drum de auto-pornire in cod (intrarile
  // in handleNativeCommandFlow sunt doar: wake verificat x3, follow-up in sesiune deschisa,
  // DEBUG_INJECT) — a vorbit pentru ca EU injectam comenzi de test pe telefonul Masterului.
  // Legea devine structurala, nu accidentala: nicio rostire fara un declansator AUTORIZAT,
  // inregistrat explicit. Daca o cale viitoare incearca sa vorbeasca fara autorizare, tace si se
  // vede in log (SPEAK_SUPPRESSED reason=no_wake_authorization).
  // Fereastra acopera o tura intreaga (agent + follow-up); dupa ea, orice rostire tardiva tace.
  // Revert: TURN_AUTH_REQUIRED = false.
  private val TURN_AUTH_REQUIRED = true
  private val TURN_AUTH_WINDOW_MS = 90_000L
  @Volatile private var turnOrigin = ""
  @Volatile private var turnAuthorizedAtMs = 0L

  // Harness-ul de test e armat doar daca a fost pornit EXPLICIT si nu a trecut fereastra.
  private fun isDebugInjectArmed(prefs: android.content.SharedPreferences): Boolean {
    if (!prefs.getBoolean("debug_inject_enabled", false)) return false
    val at = prefs.getLong("debug_inject_enabled_at", 0L)
    if (at == 0L) return true
    val fresh = System.currentTimeMillis() - at < DEBUG_INJECT_TTL_MS
    if (!fresh) AudioDiag.log(this, "DEBUG_INJECT", "action=rejected reason=expired")
    return fresh
  }
  private val DEBUG_INJECT_TTL_MS = 30 * 60 * 1000L

  private fun authorizeTurn(origin: String) {
    turnOrigin = origin
    turnAuthorizedAtMs = System.currentTimeMillis()
    AudioDiag.log(this, "TURN_AUTHORIZED", "origin=$origin")
  }

  private fun isTurnAuthorized(): Boolean {
    if (!TURN_AUTH_REQUIRED) return true
    if (turnOrigin.isEmpty()) return false
    return System.currentTimeMillis() - turnAuthorizedAtMs < TURN_AUTH_WINDOW_MS
  }

  @Volatile private var lastStopAtMs = 0L
  @Volatile private var commandStartedAtMs = 0L
  @Volatile private var ttsSpeakingSince = 0L
  @Volatile private var ttsLastDoneAt = 0L

  // D0 (08.10.2026, Cauza: voce engleză) — FIX_NATIVE_TTS_LOCALE_RACE_1, device-proven by reading
  // the code, not guessed: nativeTtsReady was flipped true BEFORE .language was set (two separate
  // statements, same synchronous callback, but a reader on another thread could observe
  // nativeTtsReady=true and call speak() in the gap). On a cold TextToSpeech init, the very FIRST
  // utterance of a fresh process could speak with whatever locale/voice the engine defaults to —
  // plausibly English if the system default differs from ro-RO — before our own assignment ran.
  // Also selects an explicit Voice (not just .language), preferring a "-local" identifier over
  // "-network", mirroring app/index.tsx's own bestRoVoiceId logic — a bare .language= otherwise
  // leaves the engine to pick among this device's 3 ro-RO voices on its own.
  // Revert: swap the two lines back (nativeTtsReady = ... before the language/voice block) and
  // drop the explicit voice selection.
  private fun initNativeAck() {
    nativeTts = android.speech.tts.TextToSpeech(applicationContext) { status ->
      if (status == android.speech.tts.TextToSpeech.SUCCESS) {
        val t = nativeTts
        // Selecția reală trăiește acum în ensureNativeTtsVoiceCurrent (rulează oricum la prima
        // rostire, cf. applyTtsRouting) — aici doar o forțăm o dată, imediat, ca NATIVE_TTS_INIT
        // să raporteze deja vocea corectă, nu "unset".
        if (t != null) ensureNativeTtsVoiceCurrent(t)
        nativeTtsReady = true
        AudioDiag.log(this, "NATIVE_TTS_INIT", "status=success lang=ro voice=${t?.voice?.name ?: "unset"}")
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
        continueSessionAfterUtterance(utteranceId)
      }
      // FIX_TTS_STOP_STATE_1 (15.10.2026) - BUG DOVEDIT DIN CONTRACTUL API: TextToSpeech.stop() NU
      // cheama onDone, ci onStop (implementare implicita goala). Deci dupa orice intrerupere
      // (tryInterrupt apela deja nativeTts.stop()) ttsSpeakingSince ramanea != 0 PERMANENT, iar
      // SelfTtsGuard.isWithinGuard intoarce true necondiTionat in acel caz, deci fiecare "Benson"
      // de dupa era aruncat cu WAKE_IGNORED reason=self_tts. Fara acest override, "taci" ar fi
      // omorat wake-ul pana la urmatoarea rostire dusa pana la capat. Revert: sterge override-ul.
      override fun onStop(utteranceId: String?, interrupted: Boolean) {
        ttsSpeakingSince = 0L
        ttsLastDoneAt = System.currentTimeMillis()
        AudioDiag.log(this@BensonForegroundService, "TTS_STOPPED", "utteranceId=$utteranceId interrupted=$interrupted")
      }
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        ttsSpeakingSince = 0L
        ttsLastDoneAt = System.currentTimeMillis()
        if (utteranceId?.startsWith("wake_") != true) return
        AudioDiag.logError("WAKE_ACK_ERROR", "utteranceId=$utteranceId reason=tts_error")
        mainHandler.postDelayed({ startFollowUpCapture() }, E0_TTS_TAIL_SETTLE_MS)
      }
    })
  }

  // P5.1 (08.10.2026, ZIUA v3) — "wake_ack_" (speakNativeAckThenCapture, betonat, NEATINS) always
  // opens the first-command window, unchanged. "wake_say_" covers BOTH confirmation questions AND
  // final result reports (RUNDA SPEAK-1's single speak() point) — distinguished here not by the
  // text, but by whether a pending-answer state is still set: a question sets one of these fields
  // right before speaking it (see handleNativeCommandFlow's own check order) and only clears it
  // once answered, so "nothing pending" at this exact instant means the utterance that just
  // finished WAS the final report, not a question.
  // FIX_TTS_VOICE_CACHE_1 (19.10.2026) — extras identic din onDone (zero schimbare de comportament
  // pentru calea existenta) ca sa poata fi reutilizat si cand vocea e sarita deliberat (text-only,
  // vezi speakNativeFallbackThenCapture) — o singura cale de continuare a sesiunii, nu doua.
  private fun continueSessionAfterUtterance(utteranceId: String) {
    if (AC2F_P5_SESSION_CLOSE && utteranceId.startsWith("wake_say_") && !hasAnyPendingNativeState()) {
      // F1 (12.10.2026, arhitect-aprobat) — closeSession() singur nu atinge niciodată micOwner/
      // HEED; până acum doar OwnerWatchdog (45s) recupera după un raport final fără întrebare.
      // Același drum, idempotent, folosit deja la sfârșitul capturii de follow-up goale
      // (line ~3136, reason=no_followup_command) — reutilizat aici verbatim, nu un drum nou.
      mainHandler.post {
        closeSession("command_done")
        setMicOwner("NONE", "native_report_done")
        hideWakeRingNative()
        rearmWakeNative("command_done")
      }
    } else {
      // E0.1 (08.10.2026, self-echo) — capture stays closed from TTS_START to TTS_DONE+300ms, not
      // opened the instant onDone fires. HeedWakeWord/wake keeps listening the whole time
      // (barge-in, untouched) — this delay only affects the separate COMMAND capture.
      mainHandler.postDelayed({ startFollowUpCapture() }, E0_TTS_TAIL_SETTLE_MS)
    }
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

  // FIX_TTS_VOICE_CACHE_1 (19.10.2026) — orchestrarea reala (apeluri Android), decizia e in
  // TtsVoiceSelector (pur, testat JUnit). O selectie CONFIRMATA nu se reincearca la fiecare
  // rostire; una esuata/neincarcata se reincearca, plafonat, fara bucla infinita (cerintele 1+4+8).
  // Nu se mai atribuie niciodata `t.language`/`t.voice` orbeste cand nu exista o voce aleasa —
  // asta era exact mecanismul care lasa motorul sa cada pe vocea lui implicita (en-IN).
  private fun ensureNativeTtsVoiceCurrent(t: android.speech.tts.TextToSpeech) {
    val engine = try { t.defaultEngine } catch (_: Exception) { null }
    if (engine != ttsVoiceBinding.engine) {
      val (resetBinding, resetRetries) = TtsVoiceSelector.resetForNewEngine(engine)
      ttsVoiceBinding = resetBinding
      ttsVoiceRetryCount = resetRetries
    }
    val nowMs = System.currentTimeMillis()
    if (!TtsVoiceSelector.shouldAttemptSelection(ttsVoiceBinding, ttsVoiceRetryCount, nowMs, ttsVoiceLastAttemptAtMs)) return
    ttsVoiceLastAttemptAtMs = nowMs

    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val androidVoices = try { t.voices } catch (_: Exception) { null }
    val candidates = androidVoices?.map { TtsVoiceCandidate(it.name, it.locale.language, it.locale.country) }
    if (androidVoices != null) {
      val hasRo = candidates?.any { it.language == "ro" } == true
      prefs.edit().putBoolean("tts_no_ro_voice", !hasRo).apply()
      if (!hasRo) AudioDiag.log(this, "TTS_NO_RO_VOICE", "engine=$engine")
    }

    val chosenCandidate = candidates?.let { TtsVoiceSelector.pickBest(it) }
    val chosenVoice = chosenCandidate?.let { cand -> androidVoices?.firstOrNull { it.name == cand.name } }

    // Cerinta 3: se citesc REZULTATELE reale, nu se presupune succes din simpla atribuire.
    var langResult = android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
    var voiceResult = android.speech.tts.TextToSpeech.ERROR
    if (chosenVoice != null) {
      langResult = try { t.setLanguage(chosenVoice.locale) } catch (_: Exception) { android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED }
      voiceResult = try { t.setVoice(chosenVoice) } catch (_: Exception) { android.speech.tts.TextToSpeech.ERROR }
    }
    val langOk = langResult == android.speech.tts.TextToSpeech.LANG_AVAILABLE ||
      langResult == android.speech.tts.TextToSpeech.LANG_COUNTRY_AVAILABLE ||
      langResult == android.speech.tts.TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    val voiceOk = voiceResult == android.speech.tts.TextToSpeech.SUCCESS
    // Cerinta 3: confirmare REALA — ce a ramas efectiv legat (getVoice()) chiar e ce s-a cerut.
    val actuallyBound = try { t.voice?.name } catch (_: Exception) { null }

    val newState = TtsVoiceSelector.classifyAttempt(candidates, chosenCandidate, langOk, voiceOk, actuallyBound)
    ttsVoiceBinding = TtsVoiceBinding(engine, newState, chosenCandidate?.name, chosenCandidate?.language)
    ttsVoiceRetryCount = if (newState == TtsVoiceState.CONFIRMED) 0 else ttsVoiceRetryCount + 1

    AudioDiag.log(
      this, "TTS_VOICE_SELECT",
      "engine=$engine state=$newState requested=${chosenCandidate?.name ?: "none"} " +
        "locale=${chosenCandidate?.language ?: "none"} langResult=$langResult voiceResult=$voiceResult " +
        "actuallyBound=${actuallyBound ?: "none"} attempt=$ttsVoiceRetryCount",
    )
  }

  // Applied right before every speak() — car mode can toggle mid-session (cable plugged/unplugged).
  private fun applyTtsRouting(t: android.speech.tts.TextToSpeech) {
    ensureNativeTtsVoiceCurrent(t)
    logCarModeIfChanged()
    val carMode = lastCarModeLogged == true
    try { t.setAudioAttributes(currentTtsAudioAttributes(carMode)) } catch (_: Exception) {}
    val outDevice = try {
      if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice?.type?.toString() ?: "unknown" else "unknown"
    } catch (_: Exception) { "unknown" }
    AudioDiag.log(this, "TTS_ROUTE", "usage=${if (carMode) "nav_guidance" else "assistant"} carMode=$carMode outDevice=$outDevice")
  }

  // LEGE L8-EXT (08.10.2026) — o singură identitate de voce pentru toate ieșirile. Verificat, nu
  // presupus: nativeTts e UNICUL TextToSpeech din tot codul nativ (audit grep, 08.10.2026) — ack-ul
  // și speakNativeFallbackThenCapture folosesc deja ACELAȘI obiect, deci aceeași voce aleasă o
  // dată la initNativeAck (D0). Linia asta doar face vizibil pe log ce era deja adevărat.
  private fun logTtsSpeak(kind: String, text: String) {
    val voiceId = nativeTts?.voice?.name ?: "unset"
    AudioDiag.log(this, "TTS_SPEAK", "kind=$kind lang=ro voice=$voiceId chars=${text.length}")
  }

  // Ack-ul wake e prima rostire a unei ture legitime; autorizarea se pune aici, nu in HeedWakeWord
  // (fisier protejat) si fara sa schimbam nimic din logica sau parametrii ack-ului.
  private fun speakNativeAckThenCapture() {
    val t = nativeTts
    if (t != null && nativeTtsReady) {
      applyTtsRouting(t)
      val utteranceId = "wake_ack_${System.currentTimeMillis()}"
      val result = t.speak("Da, Master.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
      AudioDiag.log(this, "WAKE_ACK", "engine=native result=$result")
      logTtsSpeak("ack", "Da, Master.")
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
  private fun speak(text: String, kind: String? = null) = speakNativeFallbackThenCapture(text, kind)

  private fun speakNativeFallbackThenCapture(text: String, kindOverride: String? = null) {
    // Comanda curenta a pornit INAINTE de ultimul stop => rostirea aparTine unei sarcini moarte.
    // O comanda noua muta commandStartedAtMs dupa lastStopAtMs, deci vorbeste normal. Dovedit
    // necesar pe telefon: fereastra pe ceas (2,5 s) lasa sa scape rapoarte la 8 s dupa stop.
    if (lastStopAtMs > commandStartedAtMs) {
      AudioDiag.log(this, "SPEAK_SUPPRESSED", "reason=interrupt_all chars=${text.length}")
      return
    }
    // LEGEA TACERII — fara declansator autorizat, BENSON tace. Fara excepTii.
    if (!isTurnAuthorized()) {
      AudioDiag.log(
        this, "SPEAK_SUPPRESSED",
        "reason=no_wake_authorization origin=\"$turnOrigin\" chars=${text.length}",
      )
      return
    }
    if (E0_SELF_ECHO) recordSpokenTextForEcho(text)
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
      // FIX_TTS_VOICE_CACHE_1 (19.10.2026, P0 — "accent indian", cerintele 5+6+7) — nu se vorbeste
      // CU VOCE decat pe o selectie CONFIRMATA pentru acest motor (altfel inseamna sa lasam
      // motorul sa aleaga singur, exact mecanismul care a produs en-IN) SI doar daca originea turei
      // respecta preferinta de redare audio (cerinta 7 — tastat fara activare explicita = text).
      // Niciodata nu schimba pe-ascuns identitatea vocii: daca nu e sigur, raspunsul ramane text
      // (bula, deja afisata de apelant inainte de speak()) + raportare explicita in log.
      val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      val debugInjectAudioEnabled = prefs.getBoolean("debug_inject_audio_enabled", false)
      val voiceConfirmed = TtsVoiceSelector.isSafeToSpeakAudio(ttsVoiceBinding)
      val originAllowsAudio = TtsVoiceSelector.shouldSpeakAudioForOrigin(turnOrigin, debugInjectAudioEnabled)
      if (voiceConfirmed && originAllowsAudio) {
        // ADĂUGARE — BENSON ÎNTREABĂ CA O ÎNTREBARE (06.10.2026, product-owner-directed): textul
        // complet, cu punctuația, ca să se poată verifica pe log că „?" ajunge intact la motorul de
        // voce — nu e trunchiat/sanitizat nicăieri pe acest drum (t.speak primește `text` neschimbat).
        AudioDiag.log(this, "TTS_NATIVE_REQUEST", "text=\"$text\"")
        val result = t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        AudioDiag.log(this, "WAKE_FALLBACK", "engine=native result=$result")
        // LEGE L8-EXT — kind explicit (V5: "chat"/"clarify") câștigă; altfel inferat din starea
        // pendinte la momentul vorbirii (același tipar ca P5): ceva pendinte ÎNCĂ = confirm; nimic
        // pendinte = raport final.
        logTtsSpeak(kindOverride ?: (if (hasAnyPendingNativeState()) "confirm" else "report"), text)
      } else {
        val reason = if (!voiceConfirmed) "voice_not_confirmed:${ttsVoiceBinding.state}" else "typed_audio_not_enabled"
        AudioDiag.log(this, "TTS_TEXT_ONLY", "reason=$reason engine=${ttsVoiceBinding.engine ?: "unset"}")
        continueSessionAfterUtterance(utteranceId)
      }
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

  // J4 (12.10.2026, PLANUL_FINAL, RUNDA J) — plasă separată, pe proprietar, mai scurtă decât cei
  // 45s de mai sus (neatinși): dacă proprietarul ≠ WAKE/NONE și NU există o sesiune activă
  // (sessionOpenUntil == 0L — nici o captură/TTS legitim în curs nu o ține deschisă), 10s sunt
  // suficiente ca să fie sigur o stare blocată, nu un răspuns lung normal. Flag + revert propriu
  // (independent de JS_VOICE_HANDOFF): J4_SAFETY_RELEASE = false => se păstrează doar paza de 45s.
  private val J4_SAFETY_RELEASE = true
  private val J4_SAFETY_RELEASE_MS = 10_000L

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
      if (J4_SAFETY_RELEASE) {
        mainHandler.postDelayed({
          if (micOwner == owner && micOwnerEpoch == epoch && sessionOpenUntil == 0L) {
            AudioDiag.logError("SAFETY_RELEASE", "owner=$owner reason=no_active_session_${J4_SAFETY_RELEASE_MS}ms")
            setMicOwner("NONE", "safety_release")
            armNativeWake("safety_release")
          }
        }, J4_SAFETY_RELEASE_MS)
      }
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
  // ZIUA 2026-10-08, G3 (debug-only) — last confirmationId started, so DEBUG_INJECT_CONFIRM can
  // target it without the caller having to pass it back in. Not read by any production path.
  @Volatile private var lastConfirmationId: String? = null

  // Shared by the real NativeConfirmationListener callback AND the debug-injected path below —
  // the one place that actually delivers a confirmation verdict, so a debug run exercises the
  // exact same downstream code as a real microphone reply.
  private fun deliverConfirmationResult(confirmationId: String, verdict: String, transcript: String) {
    AudioDiag.log(this, "CONFIRM_LISTEN_RESULT", "confirmationId=$confirmationId verdict=$verdict text=\"$transcript\"")
    if (micOwner == "CONFIRMATION_STT") setMicOwner("NONE", "confirmation_done")
    armNativeWake("confirmation_done")
    val cb = onConfirmationResult
    if (cb != null) cb(confirmationId, verdict, transcript)
    else pendingConfirmationResult = Triple(confirmationId, verdict, transcript)
  }

  fun startConfirmationListening(confirmationId: String, timeoutMs: Long) {
    if (callAudioBlocked || isCallAudioActive()) {
      AudioDiag.log(this, "CONFIRM_LISTEN_SUPPRESSED", "reason=call_active")
      return
    }
    confirmationListener?.stop()
    lastConfirmationId = confirmationId
    setMicOwner("CONFIRMATION_STT", "confirmation_start")
    AudioDiag.log(this, "CONFIRM_LISTEN_START", "confirmationId=$confirmationId timeoutMs=$timeoutMs")
    val listener = NativeConfirmationListener(
      applicationContext,
      onResult = { verdict, transcript -> mainHandler.post { deliverConfirmationResult(confirmationId, verdict, transcript) } },
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
          // J3 (12.10.2026) — JS nu mai poate cere microfonul pe drumul vocal cât JS_VOICE_HANDOFF
          // e OFF: singurul apelant real al acestei ramuri e lib/headless/presentWakeResult.ts, chiar
          // calea js_handoff pe care J1 o dezactivează — refuzul e defense-in-depth, nu doar oprirea
          // sursei. Revert: JS_VOICE_HANDOFF = true readuce și acest refuz la comportamentul vechi.
          if (!JS_VOICE_HANDOFF) {
            AudioDiag.log(this, "JS_MIC_REQUEST_DENIED", "owner=$owner reason=js_voice_handoff_off")
          } else {
            AudioDiag.log(this, "WAKE_AUDIO_BLOCKED", "reason=js_request owner=$owner note=heed_kept_running")
            setMicOwner(owner, "js_request")
            startHeedEngineOnly("tts_barge_in_listen")
          }
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

  // WAKE_FULL_STT (08.10.2026, Rareș-directed, lacăt explicit pe onHeedWakeDetected — doar sursa
  // textului de comandă). Regula: transcriptul WAKE_VERIFY (clipMs=2500, trunchiat pe comenzi
  // "dintr-o suflare" mai lungi — bug confirmat device, "benson sună" dintr-un "sună-o pe Baby pe
  // whatsapp" real) servește EXCLUSIV deciziei accept/reject, niciodată ca text de comandă. Textul
  // vine ÎNTOTDEAUNA din STT complet (nativeTranscribe, V3, OpenAI-first) pe captura întreagă
  // (HEED_COMMAND_BUFFER/wake_command_*.wav — verificat: VAD pe 1,2s tăcere, plafon 8s, NU o limită
  // fixă care taie vorbirea reală). Revert: WAKE_FULL_STT=false = comportamentul vechi exact.
  private val WAKE_FULL_STT = true

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
        // FIX_SHUTUP_WHILE_SPEAKING_1 (15.10.2026) - CAUZA DOVEDITA a reclamatiei "taci nu merge":
        // vocabularul de intrerupere EXISTA deja (INTERRUPT_PATTERN contine "taci"), dar cat timp
        // BENSON vorbeste, ACEASTA linie arunca orice detectie wake (WAKE_IGNORED reason=self_tts),
        // deci "Benson, taci!" nu ajungea NICIODATA nici la STT, nici la tryInterrupt. Singura cale
        // de oprire era sa astepti sa termine de vorbit.
        //
        // Nu atingem pragul HEED, nici SelfTtsGuard.GUARD_MS, nici ack-ul (parametri protejati):
        // drumul normal de wake ramane neschimbat - detectia e tot IGNORATA pentru deschiderea unei
        // sesiuni. Alaturi, un singur drum strict pentru tacere: clipul deja capturat de HEED se
        // transcrie cu verificatorul EXISTENT (wakeVerifyTranscribe) si, DOAR daca textul real
        // contine un cuvant de tacere si NU e ecoul vocii proprii (isSelfEchoText, existent),
        // taiem vocea. Nicio decizie pe scor - altfel BENSON s-ar taia singur in mijlocul
        // propriului raspuns (exact motivul pentru care garda exista).
        // Revert: SHUTUP_WHILE_SPEAKING = false.
        if (SHUTUP_WHILE_SPEAKING && ttsSpeakingSince != 0L && wavPath.isNotEmpty()) {
          Thread({
            val (txt0, _, clipMs) = wakeVerifyTranscribe(wavPath)
            // FIX_SHUTUP_CROSSCHECK_1 (19.10.2026, P0 — raport real: „Benson, taci" nu opreste
            // vorbirea). CAUZA DOVEDITA IN COD: `hit` depindea EXCLUSIV de `wakeVerifyTranscribe`
            // (un singur apel rapid la cloud); daca intoarce null/gol (acelasi esec de retea/
            // timeout deja documentat in alta parte a acestui fisier pentru verificarea de trezire),
            // SHUTUP_PATTERN nu avea pe ce sa se potriveasca si opritul tacea complet, fara niciun
            // semnal in afara liniei de log SHUTUP_CHECK. O singura incercare suplimentara cu
            // nativeTranscribe (acelasi lant existent de 3 furnizori) inainte de a renunta.
            val txt = if (txt0.isNullOrBlank()) nativeTranscribe(wavPath) else txt0
            val norm = normalizeForMatch(txt ?: "")
            val isEcho = if (txt.isNullOrBlank()) false else isSelfEchoText(txt).first
            val hit = !isEcho && SHUTUP_PATTERN.containsMatchIn(norm) &&
              !MEDIA_OBJECT_PATTERN.containsMatchIn(norm)
            AudioDiag.log(this, "SHUTUP_CHECK", "text=\"${txt ?: ""}\" echo=$isEcho hit=$hit clipMs=$clipMs crosschecked=${txt0.isNullOrBlank()}")
            if (hit) mainHandler.post { interruptAll("barge_in") }
          }, "benson-shutup").start()
        }
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
        val (verifyTranscript, connReused, clipMs) = wakeVerifyTranscribe(wavPath)
        // FIX_WAKE_FALSE_ACCEPT_CROSSCHECK_1 (19.10.2026, P0 — raport real: BENSON asculta
        // televizorul si comenta spontan). CAUZA DOVEDITA IN COD, nu presupusa: cand verify-ul
        // rapid de mai sus nu intoarce text, isWakeVerified() (mai jos in fisier) accepta DOAR pe
        // scorul acustic HEED (OFFLINE_ACCEPT_SCORE_THRESHOLD=0.95) — dar comentariul
        // FIX_WAKE_VERIFY_FAILSAFE_1, scris chiar langa acel prag, documenteaza deja alarme false
        // masurate la 0.795-0.984, ADICA PESTE pragul ales. Un zgomot de fundal (televizor) care
        // depaseste 0.95 trecea garda fara nicio confirmare reala de text.
        // Fix: inainte de a avea incredere oarba in scor, se mai incearca O DATA, cu nativeTranscribe
        // (acelasi lant de 3 furnizori deja folosit ca rezerva mai jos in handleNativeCommandFlow —
        // nu e cod nou, doar mutat mai devreme, ca sa decida ACCEPTAREA, nu doar textul comenzii).
        // Nu schimba pragul HEED (0.789, protejat), nu schimba OFFLINE_ACCEPT_SCORE_THRESHOLD (decizia
        // explicita a proprietarului ramane, pentru cazul real fara nicio conexiune), nu adauga un
        // proprietar nou de microfon. Revert: sterge blocul de mai jos, foloseste verifyTranscript direct.
        val crossCheckedTranscript = if (WakeVerifyMatcher.needsScoreFallback(verifyTranscript)) {
          val t = nativeTranscribe(wavPath)
          AudioDiag.log(this, "WAKE_CROSSCHECK", "attempted=true text=\"${t ?: ""}\"")
          t
        } else null
        val effectiveTranscript = crossCheckedTranscript ?: verifyTranscript
        val verified = isWakeVerified(effectiveTranscript, score)
        val resultLabel = if (effectiveTranscript == null) "offline" else if (verified) "accepted" else "rejected"
        // Punctul 6 (19.10.2026, P0) — traseul COMPLET al ambelor transcrieri ramane in log, nu
        // doar rezultatul combinat: daca televizorul tot declanseaza, verify_text (incercarea
        // rapida) si crosscheck_text (a doua incercare) trebuie sa poata fi citite separat.
        AudioDiag.log(
          this, "WAKE_VERIFY",
          "result=$resultLabel verify_text=\"${verifyTranscript ?: ""}\" crosscheck_text=\"${crossCheckedTranscript ?: ""}\" effective_text=\"${effectiveTranscript ?: ""}\" score=${"%.3f".format(score)} clipMs=$clipMs latencyMs=${System.currentTimeMillis() - acceptedAtMs} connReused=$connReused crosschecked=${crossCheckedTranscript != null}",
        )
        // FIX_WAKE_DATASET_1 (2026-10-03, user-directed) — "offline" is skipped: a timeout tells
        // us nothing about whether the clip was really "Benson", so it's never labeled either way.
        when (resultLabel) {
          "accepted" -> retainWakeClip(wavPath, "positive")
          "rejected" -> retainWakeClip(wavPath, "negative")
        }
        // RUNDA AC2-F — PASUL 0 (07.10.2026, DEBUG ONLY, diagnostic, product-owner-directed):
        // salvează necondiționat (inclusiv "offline", pe care retainWakeClip de mai sus îl sare)
        // fiecare clip trimis la verificare, cu durata/peakRms/scor, pentru analiza celor 5
        // "Benson" reale. Revert: șterge acest apel — restul fluxului nu se schimbă.
        diagSaveAc2fWakeClip(wavPath, score, triggeredAtMs, resultLabel)
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
          if (crossCheckedTranscript != null) {
            // FIX_WAKE_FALSE_ACCEPT_CROSSCHECK_1 — cross-check-ul de mai sus a rulat deja
            // nativeTranscribe o data pentru decizia de acceptare; daca a reusit, il refolosim ca
            // text de comanda in loc sa platim/asteptam un al doilea apel identic la cloud.
            AudioDiag.log(this, "CMD_SOURCE", "reused_crosscheck text=\"$crossCheckedTranscript\"")
            authorizeTurn("wake")
            handleNativeCommandFlow(crossCheckedTranscript, isFollowUp = false, originalPhrase = phrase, originalWavPath = wavPath)
          } else if (WAKE_FULL_STT) {
            val sttStartMs = System.currentTimeMillis()
            Thread({
              val transcript = nativeTranscribe(wavPath)
              val sttMs = System.currentTimeMillis() - sttStartMs
              val durMs = (java.io.File(wavPath).length() - 44).coerceAtLeast(0) * 1000 / (16_000 * 2)
              AudioDiag.log(this, "CMD_SOURCE", "full_capture durMs=$durMs sttMs=$sttMs text=\"${transcript ?: ""}\"")
              mainHandler.post {
                authorizeTurn("wake")
                handleNativeCommandFlow(transcript ?: "", isFollowUp = false, originalPhrase = phrase, originalWavPath = wavPath)
              }
            }, "benson-native-cmd-stt").start()
          } else if (verifyTranscript != null) {
            // Revert (WAKE_FULL_STT=false) — comportamentul vechi: verify's own transcript, posibil
            // trunchiat la clipMs=2500, folosit direct ca text de comandă.
            authorizeTurn("wake")
            handleNativeCommandFlow(verifyTranscript, isFollowUp = false, originalPhrase = phrase, originalWavPath = wavPath)
          } else {
            // Accepted only via the score>=0.90 offline fallback — verify never got a transcript,
            // so we still don't know the command text. One normal (uncapped) transcribe call here.
            Thread({
              val transcript = nativeTranscribe(wavPath)
              mainHandler.post {
                authorizeTurn("wake")
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
    // LEGEA TACERII — apasarea fizica a butonului de pe casca E un declansator explicit al
    // Masterului (nu o auto-pornire), deci se autorizeaza aici. Era singura cale legitima de
    // rostire care NU trecea prin handleNativeCommandFlow; fara linia asta, legea ar fi amutit
    // ack-ul de la buton.
    authorizeTurn("headset_button")
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
  // PASUL 3b (07.10.2026) — warm-up's own timeout stays on the base client (unchanged, 2000ms);
  // the verify call gets its own, separate, longer timeout below (WAKE_VERIFY_CALL_TIMEOUT_MS).
  // Revert: WAKE_VERIFY_WARM_CALL_TIMEOUT_MS = 2000L (no change needed, already that value).
  private val WAKE_VERIFY_WARM_CALL_TIMEOUT_MS = 2000L
  private val wakeVerifyHttpClient = OkHttpClient.Builder()
    .callTimeout(WAKE_VERIFY_WARM_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    .connectionPool(okhttp3.ConnectionPool(1, 5, java.util.concurrent.TimeUnit.MINUTES))
    .build()

  // PASUL 3b point 4 (07.10.2026, Cauza 3b) — separate, longer timeout for the real verify call
  // only; the warm-up ping above keeps its original 2000ms. Revert: WAKE_VERIFY_CALL_TIMEOUT_MS = 2000L.
  private val WAKE_VERIFY_CALL_TIMEOUT_MS = 3000L

  // PASUL 3b point 1/2 (07.10.2026) — connection AGE tracking: OkHttp's EventListener never
  // exposes "how long has this pooled connection been idle", so this map does it ourselves —
  // stamped in connectionReleased (call finished using it), read in connectionAcquired (next call
  // picks it back up). Keyed by the okhttp3.Connection instance itself (stable identity per TCP
  // connection), shared across warm-up and verify calls since both go through wakeVerifyHttpClient
  // and therefore the SAME ConnectionPool — this directly answers "is the shared pool actually the
  // cause, or is the server closing the socket under our 30s warm-up interval".
  private val wakeVerifyConnLastUsedAt = java.util.concurrent.ConcurrentHashMap<okhttp3.Connection, Long>()

  // PASUL 3b point 1 (07.10.2026, Cauza 3b) — full OkHttp waterfall timing, self-logging on
  // callEnd/callFailed so a timeout (which never reaches callEnd) still gets a WAKE_VERIFY_TIMING
  // line via callFailed. Used for BOTH the warm-up ping and the real verify call (callType
  // distinguishes them in the log). connReused/newConnection kept from the prior ConnReuseTracker
  // this replaces — same field, same meaning, now alongside the rest of the waterfall.
  private inner class WakeVerifyTimingListener(private val callType: String) : okhttp3.EventListener() {
    @Volatile var newConnection = false
    private var callStartNs = 0L
    private var dnsStartNs = 0L
    private var dnsMs = -1L
    private var connectStartNs = 0L
    private var connectMs = -1L
    private var secureStartNs = 0L
    private var tlsMs = -1L
    private var requestSentNs = 0L
    private var uploadStartNs = 0L
    private var uploadMs = -1L
    private var ttfbMs = -1L
    private var connAgeMs = -1L

    override fun callStart(call: okhttp3.Call) { callStartNs = System.nanoTime() }
    override fun dnsStart(call: okhttp3.Call, domainName: String) { dnsStartNs = System.nanoTime() }
    override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
      if (dnsStartNs > 0) dnsMs = (System.nanoTime() - dnsStartNs) / 1_000_000
    }
    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
      newConnection = true
      connectStartNs = System.nanoTime()
    }
    override fun secureConnectStart(call: okhttp3.Call) {
      secureStartNs = System.nanoTime()
      // Pure TCP handshake portion — everything from connectStart up to the TLS handshake beginning.
      if (connectStartNs > 0) connectMs = (secureStartNs - connectStartNs) / 1_000_000
    }
    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
      if (secureStartNs > 0) tlsMs = (System.nanoTime() - secureStartNs) / 1_000_000
    }
    override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) {
      if (!newConnection) connAgeMs = (System.currentTimeMillis() - (wakeVerifyConnLastUsedAt[connection] ?: return))
    }
    override fun connectionReleased(call: okhttp3.Call, connection: okhttp3.Connection) {
      wakeVerifyConnLastUsedAt[connection] = System.currentTimeMillis()
    }
    override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) {
      if (uploadStartNs == 0L) uploadStartNs = System.nanoTime()
      if (request.body == null) requestSentNs = System.nanoTime() // no body (warm-up HEAD) — request is fully sent now
    }
    override fun requestBodyStart(call: okhttp3.Call) { if (uploadStartNs == 0L) uploadStartNs = System.nanoTime() }
    override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) {
      requestSentNs = System.nanoTime()
      if (uploadStartNs > 0) uploadMs = (requestSentNs - uploadStartNs) / 1_000_000
    }
    override fun responseHeadersStart(call: okhttp3.Call) {
      if (requestSentNs > 0) ttfbMs = (System.nanoTime() - requestSentNs) / 1_000_000
    }
    override fun callEnd(call: okhttp3.Call) { emit() }
    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { emit() }

    private fun emit() {
      val totalMs = if (callStartNs > 0) (System.nanoTime() - callStartNs) / 1_000_000 else -1L
      AudioDiag.log(this@BensonForegroundService, "WAKE_VERIFY_TIMING",
        "callType=$callType dnsMs=$dnsMs connectMs=$connectMs tlsMs=$tlsMs uploadMs=$uploadMs ttfbMs=$ttfbMs totalMs=$totalMs connReused=${!newConnection} connAgeMs=$connAgeMs")
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
          val client = wakeVerifyHttpClient.newBuilder().eventListener(WakeVerifyTimingListener("warmup")).build()
          client.newCall(req).execute().close()
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

  // RUNDA AC2-F — PASUL 0 (07.10.2026, DEBUG ONLY). Refused on a signed release build regardless
  // of whether this code ships in it — same guard idiom as handleTestSimulateHandoffMiss above
  // (android:debuggable from the live ApplicationInfo; this module generates no BuildConfig field).
  // Reads the WAV's own PCM (44-byte header, 16-bit mono LE, RATE=16000 — HeedWakeWord.writeWav's
  // exact format) for duration/peakRms, no re-decoding library needed.
  private fun diagSaveAc2fWakeClip(wavPath: String, score: Float, triggeredAtMs: Long, resultLabel: String) {
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return
    try {
      val bytes = File(wavPath).readBytes()
      if (bytes.size <= 44) {
        AudioDiag.log(this, "AC2F_DIAG_CLIP", "kind=wake result=$resultLabel error=empty_or_header_only bytes=${bytes.size}")
        return
      }
      var sumSquares = 0.0
      var peak = 0
      var i = 44
      while (i + 1 < bytes.size) {
        val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
        sumSquares += sample.toDouble() * sample.toDouble()
        if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
        i += 2
      }
      val sampleCount = (bytes.size - 44) / 2
      val durMs = sampleCount * 1000 / 16000
      val rms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0
      val dir = File(getExternalFilesDir(null), "ac2f_diag").apply { if (!exists()) mkdirs() }
      val dest = File(dir, "wake_${triggeredAtMs}_score${"%.3f".format(score)}_$resultLabel.wav")
      File(wavPath).copyTo(dest, overwrite = true)
      AudioDiag.log(
        this, "AC2F_DIAG_CLIP",
        "kind=wake path=${dest.name} durMs=$durMs peakRms=${"%.0f".format(rms)} score=${"%.3f".format(score)} result=$resultLabel triggeredAtMs=$triggeredAtMs",
      )
    } catch (e: Exception) {
      AudioDiag.logError("AC2F_DIAG_CLIP_ERROR", "kind=wake type=${e.javaClass.simpleName}")
    }
  }

  // RUNDA AC2-F, Pasul 1 (07.10.2026) — fereastră fixă în jurul cuvântului: preroll-ul
  // (HeedWakeWord.PRE_ROLL_SAMPLES, 1,5s) e mereu scris PRIMUL în WAV, deci oricât de lung ar fi
  // restul clipului (până la 8s), primele (1,5+1,0)=2,5s conțin exact cuvântul — nu mai trimitem
  // restul la Deepgram. Revert: WAKE_VERIFY_POST_TRIGGER_MS = 6_500 (readuce fereastra la ~8s).
  private val WAKE_VERIFY_POST_TRIGGER_MS = 1_000
  private val WAKE_VERIFY_WINDOW_BYTES = (HeedWakeWord.PRE_ROLL_SAMPLES + 16_000 * WAKE_VERIFY_POST_TRIGGER_MS / 1_000) * 2

  // WAKE_VERIFY_EMPTY_DIAG (13.10.2026, arhitect-aprobat) — diagnostic PUR pe cazul text="" online
  // (9/10 azi, deja cunoscut din 07.10 — vezi FIX de mai jos). Implicit OFF: zero schimbare de
  // comportament cat flagul e false. NU loga audio, transcripturi nevide, chei sau antete.
  // Revert: WAKE_VERIFY_EMPTY_DIAG = false.
  private val WAKE_VERIFY_EMPTY_DIAG = false

  // WAKE_VERIFY_EXPORT_PAYLOAD (13.10.2026, arhitect-aprobat) — o SINGURA captura controlata a
  // payload-ului EXACT trimis la Deepgram (nu wake_command_*.wav complet — acelasi `bytes` care
  // merge la .toRequestBody() mai jos, verbatim, byte cu byte). Implicit OFF. Latch propriu
  // (diagPayloadExported) — o singura exportare cat procesul traieste, cat timp flagul ramane true;
  // NU captura continua. Scrie DOAR in getExternalFilesDir/wake_diag (spatiu extern al aplicatiei,
  // accesibil prin adb fara root — verificat 13.10 pe ac2f_diag, acelasi mecanism). Loghează doar
  // metadate (nume fisier, bytes, hash SHA-256 — MessageDigest din JDK, zero dependinta noua), NU
  // continutul audio. Revert: WAKE_VERIFY_EXPORT_PAYLOAD = false (export-ul existent, daca a avut
  // loc, ramane pe disc pana e sters manual — vezi raportul rundei pentru stergere).
  private val WAKE_VERIFY_EXPORT_PAYLOAD = false
  @Volatile private var diagPayloadExported = false

  private fun exportWakeVerifyPayloadOnce(bytes: ByteArray) {
    if (!WAKE_VERIFY_EXPORT_PAYLOAD || diagPayloadExported) return
    diagPayloadExported = true
    try {
      val dir = File(getExternalFilesDir(null), "wake_diag").apply { if (!exists()) mkdirs() }
      val dest = File(dir, "payload_${System.currentTimeMillis()}.wav")
      dest.writeBytes(bytes)
      val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
      AudioDiag.log(this, "WAKE_VERIFY_PAYLOAD_EXPORTED", "path=${dest.name} bytes=${bytes.size} sha256=$sha256")
    } catch (e: Exception) {
      AudioDiag.logError("WAKE_VERIFY_PAYLOAD_EXPORT_ERROR", "type=${e.javaClass.simpleName}")
    }
  }

  private fun trimToVerifyWindow(bytes: ByteArray): ByteArray {
    if (bytes.size <= 44) return bytes
    val pcm = bytes.copyOfRange(44, bytes.size)
    val trimmedPcm = if (pcm.size > WAKE_VERIFY_WINDOW_BYTES) pcm.copyOfRange(0, WAKE_VERIFY_WINDOW_BYTES) else pcm
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + trimmedPcm.size)
    header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); header.putInt(16); header.putShort(1)
    header.putShort(1); header.putInt(16_000); header.putInt(16_000 * 2)
    header.putShort(2); header.putShort(16); header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(trimmedPcm.size)
    return header.array() + trimmedPcm
  }

  private fun wakeVerifyTranscribe(wavPath: String): Triple<String?, Boolean, Int> {
    val fullBytes = try { File(wavPath).readBytes() } catch (_: Exception) { null } ?: return Triple(null, false, 0)
    if (fullBytes.isEmpty()) return Triple(null, false, 0)
    val bytes = trimToVerifyWindow(fullBytes)
    val clipMs = (bytes.size - 44) * 1000 / (16_000 * 2)
    exportWakeVerifyPayloadOnce(bytes)
    val dgKey = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      .getString("wake_stt_deepgram_api_key", "") ?: ""
    if (dgKey.isBlank()) return Triple(null, false, clipMs)
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
    val timing = WakeVerifyTimingListener("verify")
    // PASUL 3b point 4 — this client's OWN callTimeout (3000ms), separate from the warm-up ping's
    // 2000ms on wakeVerifyHttpClient itself; same ConnectionPool either way (newBuilder() copies
    // the reference, doesn't clone it), so pooled-connection reuse is unaffected by this override.
    val client = wakeVerifyHttpClient.newBuilder()
      .eventListener(timing)
      .callTimeout(WAKE_VERIFY_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
      .build()
    val request = Request.Builder().url(url).addHeader("Authorization", "Token $dgKey")
      .post(bytes.toRequestBody("audio/wav".toMediaType())).build()
    return try {
      client.newCall(request).execute().use { resp ->
        val connReused = !timing.newConnection
        if (!resp.isSuccessful) {
          AudioDiag.log(this, "WAKE_VERIFY_ERROR", "reason=http_error code=${resp.code} connReused=$connReused")
          return Triple(null, connReused, clipMs)
        }
        val json = resp.body?.string() ?: return Triple(null, connReused, clipMs)
        val root = JSONObject(json)
        val channels = root.optJSONObject("results")?.optJSONArray("channels")
        val alternatives = channels?.optJSONObject(0)?.optJSONArray("alternatives")
        val alt = alternatives?.optJSONObject(0)
        val transcript = (alt?.optString("transcript", "") ?: "").trim()
        // WAKE_VERIFY_EMPTY_DIAG (13.10.2026, arhitect-aprobat, diagnostic only) — flag propriu,
        // implicit OFF; NU schimbă transcript/Triple-ul returnat, doar observă acelasi JSON deja
        // parsat mai sus. Zero audio, zero transcripturi nevide, zero chei/antete — doar forma
        // raspunsului (status, model, existenta campurilor, nr. alternative).
        if (WAKE_VERIFY_EMPTY_DIAG && transcript.isEmpty()) {
          val hasResults = root.has("results")
          val channelCount = channels?.length() ?: -1
          val altCount = alternatives?.length() ?: -1
          val hasConfidence = alt?.has("confidence") == true
          val confidence = if (hasConfidence) alt?.optDouble("confidence") else null
          val modelInfoName = root.optJSONObject("metadata")?.optJSONArray("models")?.optString(0, "")
          AudioDiag.log(
            this,
            "WAKE_VERIFY_EMPTY_DIAG",
            "http=${resp.code} model=\"${modelInfoName ?: ""}\" hasResults=$hasResults channels=$channelCount alternatives=$altCount hasConfidence=$hasConfidence confidence=${confidence ?: "none"} bodyLen=${json.length}",
          )
        }
        Triple(transcript, connReused, clipMs)
      }
    } catch (e: Exception) {
      // PASUL 3 (07.10.2026) — was catch (_: Exception), swallowing the exception type entirely.
      // Made it impossible to answer "DNS/timeout/TLS/socket closed?" for any past "offline" wake
      // result — this is the fix for that, going forward only (no way to recover past occurrences).
      AudioDiag.log(this, "WAKE_VERIFY_ERROR", "reason=exception type=${e.javaClass.simpleName} message=\"${e.message}\" connReused=${!timing.newConnection}")
      Triple(null, !timing.newConnection, clipMs)
    }
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
  // RUNDA AC2-F, Pasul 1 (07.10.2026, product-owner-directed, aprobat cu precizări) — offline e
  // acum decis de scorul local, nu mai e respingere necondiționată. ATENȚIE, dovadă deja în cod,
  // nu ignorată: FIX_WAKE_VERIFY_FAILSAFE_1 de mai sus (2026-10-03) a RENUNȚAT exact la acest
  // fallback fiindcă alarmele false observate atunci scoreau 0,795-0,984 — PESTE pragul 0,95 ales
  // acum. Implementat exact cum a cerut Rareș, cu riscul semnalat explicit în raport, nu ascuns.
  // Revert: OFFLINE_ACCEPT_SCORE_THRESHOLD = 2f (peste orice scor posibil => offline respinge mereu).
  private val OFFLINE_ACCEPT_SCORE_THRESHOLD = 0.95f

  // RUNDA AC2-F, Pasul 1c (07.10.2026, product-owner-directed) — text="" ONLINE (conexiune ok,
  // Deepgram a răspuns, dar n-a transcris nimic — cazul #14 din Pasul 1b, scor 0,957) tratat acum
  // identic cu offline: decide scorul local. Un transcript real care nu se potrivește cu numele de
  // trezire rămâne respins necondiționat — filtrul de alarme false neatins.
  private fun isWakeVerified(transcript: String?, heedScore: Float): Boolean {
    if (WakeVerifyMatcher.needsScoreFallback(transcript)) {
      val accepted = heedScore >= OFFLINE_ACCEPT_SCORE_THRESHOLD
      val reason = if (transcript == null) "offline" else "empty_online"
      AudioDiag.log(this, "WAKE_OFFLINE_DECISION", "reason=$reason score=${"%.3f".format(heedScore)} threshold=$OFFLINE_ACCEPT_SCORE_THRESHOLD accepted=$accepted")
      return accepted
    }
    return WakeVerifyMatcher.isWakeVerified(transcript, NativeCloudWake.currentWakeName(this))
  }

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

  // V4a (08.10.2026) — a SINGLE, mechanical forwarding point for every NATIVE_ROUTE/WA_PARSE log
  // call (replace_all substitution, not per-site logic edits — behaviorally identical, same stage/
  // fields reach AudioDiag.log exactly as before). Lets the shadow interpreter's INTERP_SHADOW line
  // report what the OLD parser actually decided for the SAME transcript, without touching any of
  // the ~18 individual routing branches themselves.
  @Volatile private var lastOldParserAction: String = "none"
  private fun logRoute(stage: String, fields: String) {
    val action = Regex("action=\"?([^ \"]+)\"?").find(fields)?.groupValues?.get(1)
    if (action != null) lastOldParserAction = action else if (stage == "WA_PARSE") lastOldParserAction = "wa_" + fields.removePrefix("kind=")
    AudioDiag.log(this, stage, fields)
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
  // V3 (08.10.2026) — flag + revert (G2). false = comportamentul dinainte de azi (Deepgram/Groq
  // direct, fără nicio încercare OpenAI). Revert: V3_OPENAI_STT_NATIVE = false.
  private val V3_OPENAI_STT_NATIVE = true

  private fun nativeTranscribe(wavPath: String): String? {
    val bytes = try { File(wavPath).readBytes() } catch (_: Exception) { null } ?: return null
    if (bytes.isEmpty()) return null
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    // V3 — OpenAI încercat PRIMUL, calea Deepgram/Groq de mai jos rămâne rezervă neatinsă, folosită
    // doar la eroare/timeout OpenAI sau când contorul (V2) arată plafonul zilnic depășit.
    if (V3_OPENAI_STT_NATIVE) {
      if (OpenAiUsageMeter.isBudgetExceeded(this)) {
        AudioDiag.log(this, "STT_FALLBACK", "reason=openai_budget_exceeded")
      } else {
        val openAiKey = prefs.getString("brain_api_key", "") ?: ""
        if (openAiKey.isNotBlank()) {
          val t = postToOpenAiTranscribe(bytes, openAiKey, prefs)
          // FIX_STT_OPENAI_EMPTY_FALLTHROUGH_1 (12.10.2026) — t=="" (succes HTTP, transcript gol)
          // scurtcircuita rezerva Deepgram/Groq identic cu t=null (eroare/timeout); `isNullOrBlank`
          // le separă: doar un transcript real oprește căutarea rezervei.
          // FIX_STT_NON_LATIN_1 (14.10.2026) — `isNullOrBlank` NU era de ajuns: un transcript
          // halucinat non-latin ("当", dovedit 16:13:54 in testul vocal) trecea drept valid si
          // rezerva nu mai era incercata. Vezi SttTranscriptValidator pentru cauza completa.
          if (SttTranscriptValidator.isUsable(t)) return t
          AudioDiag.log(this, "STT_FALLBACK", "reason=openai_${SttTranscriptValidator.rejectReason(t)}")
        }
      }
    }
    val dgKey = prefs.getString("wake_stt_deepgram_api_key", "") ?: ""
    if (dgKey.isNotBlank()) {
      val t = postToDeepgramCmd(bytes, dgKey)
      // FIX_STT_NON_LATIN_1 — aceeasi poarta pe rezerva: gunoiul trece mai departe la Groq, nu
      // devine comanda. Inainte: `if (t != null) return t`.
      if (SttTranscriptValidator.isUsable(t)) return t
      AudioDiag.log(this, "STT_FALLBACK", "reason=deepgram_${SttTranscriptValidator.rejectReason(t)}")
    }
    val groqKey = prefs.getString("wake_stt_api_key", "") ?: ""
    if (groqKey.isBlank()) return null
    return postToGroqCmd(bytes, groqKey, prefs)
  }

  // V3 (08.10.2026) — own short client (5s callTimeout: audio commands here are at most CMD_MAX_MS
  // worth of speech, short enough that a slow OpenAI response should fail fast into the Deepgram/
  // Groq reserve rather than stall the whole command). Cost logged as an ESTIMATE from clip
  // duration (OpenAiUsageMeter.GPT4O_MINI_TRANSCRIBE_ESTIMATED_USD_PER_MINUTE, OpenAI's own
  // published per-minute figure) — the response has no `usage` field to compute an exact cost from
  // (verified 08.10.2026), and V2's own rule is never to invent a number, so this is sourced, not
  // guessed.
  private val openAiSttHttpClient = OkHttpClient.Builder()
    .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()

  private fun postToOpenAiTranscribe(wav: ByteArray, apiKey: String, prefs: android.content.SharedPreferences): String? {
    AudioDiag.log(this, "STT_REQUEST", "provider=openai bytes=${wav.size}")
    val baseUrl = (prefs.getString("brain_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").trimEnd('/')
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
      .addFormDataPart("model", "gpt-4o-mini-transcribe")
      .addFormDataPart("language", cmdLanguage())
      .addFormDataPart("response_format", "json")
      .build()
    val request = Request.Builder().url("$baseUrl/audio/transcriptions")
      .addHeader("Authorization", "Bearer $apiKey").post(body).build()
    return try {
      openAiSttHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) { AudioDiag.logError("STT_RESULT", "provider=openai http=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        val transcript = JSONObject(json).optString("text", "").trim()
        AudioDiag.log(this, "STT_RESULT", "provider=openai text=\"$transcript\" chars=${transcript.length}")
        val clipMs = (wav.size - 44).coerceAtLeast(0) * 1000.0 / (16_000 * 2)
        if (transcript.isEmpty()) {
          // Diagnostic (12.10.2026): de ce OpenAI întoarce HTTP 200 cu text gol — audio, format
          // sau model. Nu e un fix, doar datele cerute pentru runda următoare.
          AudioDiag.log(this, "STT_OPENAI_EMPTY", "durMs=${"%.0f".format(clipMs)} bytes=${wav.size} sampleRate=16000 model=gpt-4o-mini-transcribe http=${resp.code}")
        }
        val estimatedCostUsd = (clipMs / 60_000.0) * OpenAiUsageMeter.GPT4O_MINI_TRANSCRIBE_ESTIMATED_USD_PER_MINUTE
        OpenAiUsageMeter.recordUsage(this, "stt_openai_estimated", estimatedCostUsd)
        transcript
      }
    } catch (e: Exception) {
      AudioDiag.logError("STT_ERROR", "provider=openai type=${e.javaClass.simpleName}")
      null
    }
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
      logRoute("NATIVE_ROUTE", "action=yt_open_and_search_refused reason=car_mode target=\"$query\"")
      speak("Nu pornesc video cât conduci.")
      return true
    }
    logRoute("NATIVE_ROUTE", "action=yt_open_and_search target=\"$query\"")
    if (!launchYoutubeSearch(query)) return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) { speak("Serviciul de accesibilitate nu e disponibil."); return true }
    svc.runOnServiceScope {
      val title = awaitFirstResult(svc, query)
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
      logRoute("NATIVE_ROUTE", "action=open_app_declined reason=compound_command target=\"$target\"")
      return null
    }
    // RUNDA CAR-1 — "deschide youtube" în modul mașină e tot acces la video; refuzat. Nu afectează
    // deschiderea YouTube din afara mașinii (testul înghețat din CLAUDE.md rulează fără Android Auto).
    if (target == "youtube" && isCarModeActive()) {
      logRoute("NATIVE_ROUTE", "action=open_app_refused reason=car_mode target=\"$target\"")
      return "Nu pornesc video cât conduci."
    }
    logRoute("NATIVE_ROUTE", "action=open_app target=\"$target\"")
    val pkg = findLaunchablePackage(target)
    if (pkg == null) {
      AudioDiag.log(this, "APP_LAUNCH", "pkg=none ok=false reason=not_found")
      return null
    }
    val ok = launchPackageNative(pkg)
    AudioDiag.log(this, "APP_LAUNCH", "pkg=$pkg ok=$ok")
    // LEGEA REZOLVARII PRIN PERSONAL, punctul 1 (14.10.2026) — "Daca aplicatia este vizibila dar
    // lansarea eseueaza, raporteaza LAUNCH_FAILED, nu NOT_FOUND". Inainte: `if (!ok) return null`,
    // adica aplicatie GASITA dar nelansata cadea pe acelasi drum ca una inexistenta si, la finalul
    // lanțului, tacea. Acum spune exact ce n-a mers (doctrina 9). Revert: `if (!ok) return null`.
    if (!ok) {
      AudioDiag.log(this, "APP_LAUNCH", "pkg=$pkg ok=false reason=launch_failed")
      val failLabel = AppNameSynonyms.canonicalName(target, cmdLanguage()) ?: target
      return "Am găsit $failLabel, dar n-am putut să o deschid."
    }
    // F2 punctul 4 — numele rostit e in limba turei (ex. "Calculatorul"), nu eticheta de sistem
    // ("Rechner", germana) decat daca targetul rostit nu apartine niciunui grup cunoscut.
    val label = AppNameSynonyms.canonicalName(target, cmdLanguage()) ?: try {
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
    logRoute("NATIVE_ROUTE", "action=app_category_search target=\"$category\"")
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

  // FIX_BRAIN_OPEN_APP_GATE_1 (14.10.2026) — poarta de confirmare pentru o deschidere de
  // aplicatie PROPUSA DE CREIER, nu rostita de utilizator. Cauza dovedita, din log-ul live de azi:
  //   10:58:37.669 DEBUG_INJECT text="cat este 9 ori 6"
  //   10:58:45.735 BRAIN_CALL ms=8014 type=command action=OPEN_APP ok=true
  //   10:58:45.744 NATIVE_ROUTE action=open_app target="ebay"
  // O intrebare de aritmetica a devenit "deschide eBay". Nu s-a deschis nimic DOAR pentru ca eBay
  // nu era instalat; cu registrul personalului (vizibilitate reparata, ~850 de aplicatii in loc de
  // 49) o asemenea halucinatie ar nimeri o aplicatie reala. Doctrina 6 cere ConfirmationGate
  // inaintea oricarei actiuni, doctrina 7 spune ca creierul propune, nu executa.
  // Se aplica EXCLUSIV pe ramura OPEN_APP din executeBrainCommand. Calea determinista
  // (tryNativeOpenApp apelat de parser, cand utilizatorul chiar a spus "deschide X") ramane
  // NEATINSA — comportamentul dovedit 20.09 "deschide Calculatorul" se executa in continuare
  // direct, fara intrebare in plus.
  private data class PendingAppOpen(val label: String, val pkg: String, var noCount: Int = 0)
  @Volatile private var pendingAppOpen: PendingAppOpen? = null

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

  // FIX_INTERRUPT_1 (15.10.2026) — RAPORT DIRECT AL MASTERULUI: «spun benson stop, benson inchide.
  // Nimic!». Verificat in cod si confirmat: "stop" NU exista ca intrerupere. Aparea doar ca vorba
  // de control media ("stop" -> "pause", linia 2658), ca anulare a unei liste de rezultate si ca
  // sinonim de "nu" intr-o confirmare — deci, fara nimic in asteptare si fara muzica, nu facea
  // NIMIC. Iar "inchide" ducea la ecranul principal, nu oprea BENSON.
  // Mai grav: `grep -c agentCancel|cancelAgent|interrupt` = 0 — agentul LLM ruleaza pana la 60 s
  // si NU putea fi oprit de nimic. Era deja cerut explicit (ordinul CONTINUOUS BUTLER, §5
  // INTRERUPERI: "Stop / Stai / Lasa asta / Reia") si nu fusese implementat.
  //
  // Se verifica PRIMUL in lant, inaintea starilor in asteptare, ca sa functioneze si in mijlocul
  // unei confirmari sau al unei sarcini a agentului.
  // Deliberat NU contine "opreste"/"oprește" simplu: acelea inseamna pauza la muzica (G3
  // YT_STOP/STOP_MUZICA) si nu au voie sa fie furate. Tiparul cere rostirea SINGURA, deci
  // "oprește muzica" nu se potriveste aici.
  // Revert: INTERRUPT_ENABLED = false.
  private val INTERRUPT_ENABLED = true
  // INTERRUPT_ALL (15.10.2026, ordin direct al Masterului) — TACI / STOP / OPRESTE / MUTE sunt
  // ECHIVALENTE si trec toate prin aceeasi functie centrala, `interruptAll`.
  // Dezambiguizarea e pe OBIECT, nu pe verb, si e data de ancorele ^...$: comanda rostita SINGURA
  // = intrerupere universala; aceeasi comanda cu obiect ("opreste muzica", "opreste videoclipul")
  // nu se potriveste aici si ajunge la MEDIA_CTL_PATTERN, neatins.
  private val INTERRUPT_PATTERN = Regex(
    "^(?:stop|stai|stai asa|stai așa|opreste|oprește|opreste-te|oprește-te|" +
      "lasa asta|lasă asta|lasa|lasă|anuleaza|anulează|renunta|renunță|gata|destul|ajunge|" +
      "taci|mute|liniste|liniște|nu mai vorbi|taci din gura|taci din gură)$"
  )

  // FIX_SHUTUP_WHILE_SPEAKING_1 - cuvinte care cer TACERE, nu anularea unei sarcini. Pentru ele
  // BENSON nu mai raspunde "Am oprit." cu voce (era contradictoriu: i se cerea sa taca si vorbea),
  // doar taie vocea si arata in bula. Nu e ancorat ^...$: fraza vine lipita de wake word
  // ("benson taci"), deci se caută si in interiorul transcriptului de verificare.
  private val SHUTUP_WHILE_SPEAKING = true
  private val SHUTUP_PATTERN = Regex("""(?:\btaci\b|\bstop\b|\bopreste\b|\boprește\b|\bmute\b|\bgata\b|\bdestul\b|\bajunge\b|\bliniste\b|nu mai vorbi|lasa asta)""")
  // Garda de context cerută explicit de Master: „opreste muzica" / „opreste videoclipul" sunt
  // comenzi MEDIA, nu intrerupere universala. In barge-in nu avem parser complet (lucram pe
  // transcriptul clipului de wake), deci un obiect media anuleaza interpretarea de tacere.
  private val MEDIA_OBJECT_PATTERN = Regex(
    """(?:muzic|melodi|piesa|cantec|cântec|videoclip|video|film|clip|redarea|serial|podcast|radio)"""
  )

  // INTERRUPT_ALL — SINGURUL drum de intrerupere. Cele patru comenzi, barge-in-ul si orice apel
  // viitor ajung aici; nu exista a doua implementare.
  // Ce NU atinge, deliberat (punctul 9 din ordin): un apel deja conectat (lifecycle-ul WA/telefonie
  // si audio-ul apelului nu sunt atinse nicaieri mai jos) si redarea media a altei aplicatii —
  // „taci" taie vocea lui BENSON, nu filmul Masterului. Pentru media exista MEDIA_CTL.
  // SOS: cand va exista, se exclude aici, explicit, nu implicit.
  private fun interruptAll(reason: String) {
    val callActive = isCallAudioBlockedNow()
    lastStopAtMs = System.currentTimeMillis()
    // 1. Creierul si instrumentele: rularea devine invalida si apelul HTTP in zbor e taiat.
    cancelAgentNow()
    pendingResultChoice = null
    pendingAppOpen = null
    pendingNav = null
    pendingYoutubePlay = null
    pendingAppSearch = null
    pendingWaAction = null
    pendingWaDisambiguation = null
    pendingControlAction = null
    agentAwaitingReply = false
    pendingAgentProposal = null
    try { nativeTts?.stop() } catch (_: Exception) {}
    ttsSpeakingSince = 0L
    ttsLastDoneAt = System.currentTimeMillis()
    // 2. Degetul BENSON: buclele de pasi (cautare, submit, redactare, navigare automata) se
    //    opresc INTRE pasi, prin steagul din serviciul de accesibilitate. Nu inchidem aplicatia
    //    (punctul 6 din ordin): oprim operatia, nu YouTube.
    val uiAborted = try {
      expo.modules.accessibility.BensonAccessibilityService.requestAbort(); true
    } catch (_: Throwable) { false }
    // 3. Media: oprim REDAREA (muzica/radio/video) prin acelasi transport MediaSession folosit
    //    de MEDIA_CTL — executor existent, nu unul nou. Apel conectat: nu atingem nimic.
    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val wasPlaying = try { am.isMusicActive } catch (_: Exception) { false }
    val pauseSent = if (wasPlaying && !callActive) {
      try {
        expo.modules.notificationlistener.MediaTransport.control(applicationContext, "", "pause")
      } catch (_: Exception) { false }
    } else false
    AudioDiag.log(
      this, "INTERRUPT_ALL",
      "reason=$reason silent=true tts_stopped=true agent_cancelled=true pendings_cleared=true " +
        "ui_aborted=$uiAborted media_was_playing=$wasPlaying pause_sent=$pauseSent " +
        "call_preserved=$callActive",
    )
    updateBubbleNative("Am oprit.", reason, terminal = true, dismissDelayMs = 1500L, force = true)
    closeSession(reason)
    setMicOwner("NONE", reason)
    hideWakeRingNative()
    rearmWakeNative(reason)
    // 4. Verificare reala, nu declarativa (punctul 7): daca redarea continua, bula spune exact
    //    asta. Nu raportam un stop reusit peste o operatie care merge inainte.
    if (wasPlaying && !callActive) {
      mainHandler.postDelayed({
        val still = try { am.isMusicActive } catch (_: Exception) { false }
        AudioDiag.log(this, "INTERRUPT_VERIFY", "media_stopped=${!still} still_playing=$still")
        if (still) {
          updateBubbleNative(
            "Am oprit ce puteam. Redarea continuă — aplicația nu o cedează.",
            reason, terminal = true, dismissDelayMs = 2500L, force = true,
          )
        }
      }, 900L)
    }
  }

  @Volatile private var agentRunId = 0

  private fun tryInterrupt(command: String): Boolean {
    if (!INTERRUPT_ENABLED) return false
    val normCmd = normalizeForMatch(command)
    if (!INTERRUPT_PATTERN.matches(normCmd)) return false
    logRoute("NATIVE_ROUTE", "action=interrupt_all")
    // Punctul 7 din ordin: intreruperea e SILENTIOASA. Nu mai exista ramura care rostea
    // "Am oprit." — era contradictorie (i se cere sa tacă si vorbea) si era al doilea drum de
    // intrerupere, pe lângă `interruptAll`. Acum e unul singur.
    interruptAll(normCmd)
    return true
  }

  // FIX_NATIVE_NAVIGATE_1 (14.10.2026) — REGRESIE DOVEDITA pe telefon, pe un comportament din
  // lista inchisa BENSON 1.0 (#6 "Navigatie — du-ma la X", dovedit 29.08).
  // Cauza, din cod + log: nu exista NICIUN executor nativ de navigatie. Drumul dovedit pe 29.08
  // era in JS (src/core/mission/tools/wazeTool.ts), iar RUNDA J (12.10) a inchis predarea catre JS
  // (JS_VOICE_HANDOFF=false). Peste asta, INTEGRATION-2 a cablat ramura NAVIGATE a creierului la
  // `tryGlobalNav("du-mă la $target")` — dar GLOBAL_NAV_PATTERN de mai sus e ^(inchide|iesi|acasa|
  // inapoi)$, deci apelul returna FALSE intotdeauna. Dovada pe telefon, 14.10 12:02:17:
  // "du-mă la aeroport" -> zero NATIVE_ROUTE de navigatie -> brain -> fallback "Nu am înțeles".
  // Reparatie: executor nativ, cu alegerea aplicatiei DUPA SCOP din registrul personalului
  // (CAP_NAVIGATE), confirmare obligatorie inainte de pornire (doctrina 6, "Waze cu governance"),
  // si URI-urile deja dovedite pe dispozitiv, copiate din wazeTool.ts/appLauncherAgent.ts —
  // neinventate. Revert: NATIVE_NAVIGATE = false (ramura NAVIGATE redevine no-op, ca azi).
  private val NATIVE_NAVIGATE = true
  private val NAV_DEST_PATTERN = Regex(
    "^(?:du\\s*-?\\s*ma|duma|condu\\s*-?\\s*ma|ia\\s*-?\\s*ma|navigheaza|mergi)\\s+" +
      "(?:la|spre|catre|pana la)\\s+(.{2,})$"
  )

  private data class PendingNav(val dest: String, val pkg: String, val label: String, var noCount: Int = 0)
  @Volatile private var pendingNav: PendingNav? = null

  private fun tryNativeNavigate(command: String): Boolean {
    if (!NATIVE_NAVIGATE) return false
    val n = normalizeForMatch(command)
    val m = NAV_DEST_PATTERN.find(n) ?: return false
    val dest = m.groupValues[1].trim()
    if (dest.isBlank()) return false
    // Alegerea executantului vine din registru, dupa capabilitate, nu dintr-o lista hardcodata.
    val candidates = try { StaffRegistry.staffForCapability(this, StaffRegistryCore.CAP_NAVIGATE) } catch (_: Exception) { emptyList() }
    val chosen = candidates.firstOrNull()
    if (chosen == null) {
      // L-SERVICE-002: aici intra pasul WEB, apoi recomandarea Play Store. NEIMPLEMENTAT inca —
      // spune onest ce lipseste, nu tace si nu pretinde.
      logRoute("NATIVE_ROUTE", "action=navigate_no_staff target=\"$dest\"")
      val msg = "Nu am o aplicație de navigație pe telefon."
      updateBubbleNative(msg, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(msg)
      return true
    }
    val (member, level) = chosen
    logRoute("NATIVE_ROUTE", "action=navigate_confirm target=\"$dest\" staff=${member.packageName} level=${level.name}")
    pendingNav = PendingNav(dest, member.packageName, member.displayName)
    val question = "Te duc la $dest cu ${member.displayName}. Pornesc?"
    updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
    speak(question, "confirm")
    return true
  }

  private fun handlePendingNavAnswer(rawAnswer: String) {
    val pending = pendingNav ?: return
    val verdict = classifyConfirmationNative(rawAnswer)
    AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
    when (verdict) {
      "YES" -> {
        pendingNav = null
        val enc = try { java.net.URLEncoder.encode(pending.dest, "UTF-8") } catch (_: Exception) { pending.dest }
        // Aceleasi URI-uri dovedite pe dispozitiv (wazeTool.ts): Waze intai, google.navigation ca
        // rezerva.
        // TRAVEL AGENT (20.10.2026) — EXTRAS in navigationLaunchUris (NavigationMissionTrace.kt),
        // reutilizat acum si de instrumentul agent travel_navigate; literalul de aici a fost
        // inlocuit cu apelul functiei pure, fara nicio schimbare de comportament (aceleasi URI-uri,
        // aceeasi ordine — vezi NavigationLaunchUrisTest.kt pentru proba de regresie).
        val uris = navigationLaunchUris(pending.pkg, enc)
        var ok = false
        var succeededIndex = -1
        for ((idx, u) in uris.withIndex()) {
          ok = try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
          } catch (_: Exception) { false }
          if (ok) { succeededIndex = idx; AudioDiag.log(this, "NAV_LAUNCH", "pkg=${pending.pkg} ok=true"); break }
        }
        if (!ok) AudioDiag.log(this, "NAV_LAUNCH", "pkg=${pending.pkg} ok=false reason=launch_failed")
        val resp = if (ok) "Te duc la ${pending.dest}." else "N-am putut porni navigația spre ${pending.dest}."
        finishBrainCommandSuccess(resp, "du-mă la ${pending.dest}", "nav_done")

        // SURVIVOR (BENSON NEXT, 19.10.2026) — inchide gaura numita chiar in comentariul original
        // de mai sus ("verificarea reala... ramane de facut cu AccessibilityService"): NU schimba
        // raspunsul deja rostit (zero risc pentru comportamentul dovedit, "Navigație Waze cu
        // destinație corectă"), doar adauga o corectie asincrona, onesta, cand lansarea a "reusit"
        // (fara exceptie) dar aplicatia nu a ajuns de fapt pe ecran. Scop ingust, deliberat: doar
        // cazul neambiguu — URI-ul PRINCIPAL catre Waze, acelasi pachet verificat — pentru ca
        // fallback-ul generic (google.navigation:) poate rezolva la orice aplicatie instalata,
        // iar un pachet asteptat ghicit ar incalca doctrina 8 (fara cod specific unei aplicatii
        // fara dovada ca motorul generic nu ajunge). Reutilizeaza clasificatorul SURVIVOR deja
        // scris si testat (NavigationMissionTrace.kt) — nicio logica noua de verificare.
        if (ok && succeededIndex == 0 && pending.pkg == "com.waze") {
          val startedAtSnapshot = commandStartedAtMs
          val destForLog = pending.dest
          Thread({
            var attempt = 0
            var outcome: ExecutionOutcome = ExecutionOutcome.Unverified
            while (outcome is ExecutionOutcome.Unverified) {
              val observed = expo.modules.accessibility.BensonAccessibilityService.instance?.currentForegroundPackageLive()
              outcome = classifyLaunchVerification(setOf(pending.pkg), observed, attempt, 10)
              if (outcome is ExecutionOutcome.Unverified) Thread.sleep(400)
              attempt++
            }
            AudioDiag.log(this, "NAV_VERIFY", "pkg=${pending.pkg} dest=\"$destForLog\" outcome=$outcome")
            if (outcome is ExecutionOutcome.Failed && commandStartedAtMs == startedAtSnapshot) {
              mainHandler.post {
                // A doua verificare, chiar inainte de rostire (acelasi tipar ca AGENT_SPEECH_SUPPRESSED):
                // o comanda noua intre timp face corectia tardiva si nerelevanta, nu doar stramba.
                if (commandStartedAtMs == startedAtSnapshot) {
                  val correction = "Nu pot confirma că am ajuns la $destForLog cu Waze."
                  updateBubbleNative(correction, "du-mă la $destForLog", terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
                  speak(correction, "chat")
                } else {
                  AudioDiag.log(this, "NAV_VERIFY", "speech_suppressed reason=newer_command")
                }
              }
            }
          }, "benson-nav-verify").start()
        }
      }
      "NO" -> {
        pendingNav = null
        AudioDiag.log(this, "SESSION", "close reason=nav_confirm_no")
        closeSession("nav_confirm_no")
        setMicOwner("NONE", "nav_confirm_no")
        rearmWakeNative("nav_confirm_no")
      }
      else -> {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingNav = null
          AudioDiag.log(this, "SESSION", "close reason=nav_confirm_unresolved")
          closeSession("nav_confirm_unresolved")
          setMicOwner("NONE", "nav_confirm_unresolved")
          rearmWakeNative("nav_confirm_unresolved")
        } else speak("Da sau nu?")
      }
    }
  }

  private fun tryGlobalNav(command: String): Boolean {
    val n = normalizeForMatch(command)
    val m = GLOBAL_NAV_PATTERN.find(n) ?: return false
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) { speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil."); return true }
    val action = if (m.groupValues[1] == "inapoi") "back" else "home"
    val ok = svc.performGlobalNav(action)
    logRoute("NATIVE_ROUTE", "action=global_nav target=\"$action\" ok=$ok")
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
    // FIX_MUSIC_FOREGROUND_CAPABILITY_1 (15.10.2026) — CAUZA DOVEDITA pe telefon, 09:57:46:
    //   "pune Radio România Actualități" -> action=music_play pkg=com.opera.browser
    //   -> step=type ok=true -> step=extract ok=false -> verified=false, si BENSON a TACUT.
    // Regula "orice aplicatie reala din prim-plan" (MUSIC-2) e prea larga: a tastat numele unui
    // post de radio in BROWSER, doar pentru ca browserul era deschis. O cerere de redare are sens
    // doar intr-o aplicatie care chiar poate reda. Verificam capabilitatea in REGISTRUL
    // PERSONALULUI (CAP_MEDIA_SEARCH), nu printr-o lista de pachete hardcodata (doctrina 8).
    // Cand aplicatia din fata nu stie sa redea, declinam -> cererea ajunge la agentul LLM, care
    // alege instrumentul potrivit. Revert: MUSIC_REQUIRE_MEDIA_CAPABILITY = false.
    if (MUSIC_REQUIRE_MEDIA_CAPABILITY) {
      val canPlayHere = try {
        StaffRegistry.capsOf(this, fg).containsKey(StaffRegistryCore.CAP_MEDIA_SEARCH)
      } catch (_: Exception) { false }
      if (!canPlayHere) {
        logRoute("NATIVE_ROUTE", "action=music_play_declined reason=fg_not_media pkg=$fg")
        return false
      }
    }

    val query = parsed.query
    logRoute("NATIVE_ROUTE", "action=music_play target=\"$query\" pkg=$fg")
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
      // Op. 2 — contextul se pastreaza si dupa o căutare IN-APLICATIE (Amazon), nu doar web, ca
      // "apasă pe primul rezultat" sa mearga in continuarea oricareia dintre cele doua comenzi.
      lastSearchQuery = cmd.query
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

  // P3 / FIX_YT_FIXED_WINDOW_1 (15.10.2026) — CAUZA REALA a esecului repetat `YT_PLAY_CONFIRM`,
  // identificata, nu presupusa: dupa lansarea YouTube prin Intent, codul astepta o fereastra FIXA
  // de 1800 ms si apoi cauta o SINGURA data primul rezultat. Daca YouTube nu randase inca (pornire
  // la rece, retea lenta), `locateFirstResultOnScreen` intorcea null -> "N-am gasit madonna pe
  // YouTube" -> nu se mai crea nicio intrebare in asteptare -> "da"-ul din suita nu avea ce
  // confirma -> FAIL. De aceea testul cadea "intermitent": depindea de cat de repede randa YouTube.
  // Reparatie: asteptare pe CONDITIE, nu pe ceas — interogam pana apare rezultatul, cu buget
  // maxim. Cand randarea e rapida iese imediat (mai rapid decat inainte), cand e lenta mai asteapta.
  // Revert: `kotlinx.coroutines.delay(1800); svc.locateFirstResultOnScreen(query)`.
  private suspend fun awaitFirstResult(
    svc: expo.modules.accessibility.BensonAccessibilityService,
    query: String,
    budgetMs: Long = 6000,
    stepMs: Long = 250,
  ): String? {
    val started = System.currentTimeMillis()
    var attempts = 0
    while (System.currentTimeMillis() - started < budgetMs) {
      attempts++
      val t = try { svc.locateFirstResultOnScreen(query) } catch (_: Exception) { null }
      if (t != null) {
        AudioDiag.log(this, "YT_RESULT_WAIT", "found=true attempts=$attempts ms=${System.currentTimeMillis() - started}")
        return t
      }
      kotlinx.coroutines.delay(stepMs)
    }
    AudioDiag.log(this, "YT_RESULT_WAIT", "found=false attempts=$attempts ms=${System.currentTimeMillis() - started}")
    return null
  }

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
      logRoute("NATIVE_ROUTE", "action=youtube_search_confirm_refused reason=car_mode target=\"$query\"")
      speak("Nu pornesc video cât conduci.")
      return true
    }
    logRoute("NATIVE_ROUTE", "action=youtube_search_confirm target=\"$query\" youtubeForeground=$youtubeForeground")
    if (!youtubeForeground && !launchYoutubeSearch(query)) return false
    svc.runOnServiceScope {
      // Already-foreground: real search box, activate+type (genericSearchLocateFirst). Just
      // launched via Intent: results already populated, no box to activate (locateFirstResultOnScreen).
      val title = if (youtubeForeground) svc.genericSearchLocateFirst(query)
      else awaitFirstResult(svc, query)
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
            // V1 (08.10.2026, M1) — standardized OUTCOME, same decision as above, already correct.
            if (V1_TRUTHFUL_REPORTER) AudioDiag.log(this, "OUTCOME", "action=youtube_play verified=$verified evidence=\"${meta?.title ?: "none"}\" spoken=\"$resp\"")
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

  // FIX_BRAIN_OPEN_APP_GATE_1 — raspunsul la "Deschid X?" propus de creier. Aceeasi forma ca
  // handlePendingYoutubePlayAnswer (YES / NO / 2 incercari), zero mecanism nou.
  private fun handlePendingAppOpenAnswer(rawAnswer: String) {
    val pending = pendingAppOpen ?: return
    val verdict = classifyConfirmationNative(rawAnswer)
    AudioDiag.log(this, "CONFIRM_RESULT", "verdict=$verdict")
    when (verdict) {
      "YES" -> {
        pendingAppOpen = null
        finishAppLaunch(pending.label, pending.pkg, "deschide ${pending.label}")
      }
      "NO" -> {
        pendingAppOpen = null
        AudioDiag.log(this, "SESSION", "close reason=brain_open_app_no")
        closeSession("brain_open_app_no")
        setMicOwner("NONE", "brain_open_app_no")
        rearmWakeNative("brain_open_app_no")
      }
      else -> {
        pending.noCount += 1
        if (pending.noCount >= 2) {
          pendingAppOpen = null
          AudioDiag.log(this, "SESSION", "close reason=brain_open_app_unresolved")
          closeSession("brain_open_app_unresolved")
          setMicOwner("NONE", "brain_open_app_unresolved")
          rearmWakeNative("brain_open_app_unresolved")
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
  // LEGEA PERSONALULUI (M1) — sursa de nume vine din registru (un singur inventar). Scorarea de
  // mai jos e NESCHIMBATA fata de F2/FIX_APP_NAME_ACRONYM_1: aceleasi variante de target, acelasi
  // AppNameMatcher, acelasi prag. Daca registrul e gol (flag OFF, prima pornire, sau enumerare
  // amanata de garda de vizibilitate), cadem pe enumerarea live de dinainte — niciodata pe nimic.
  private fun launchableNameSource(): List<Pair<String, String>> {
    if (APP_REGISTRY) {
      val snapshot = try { StaffRegistry.launchableSnapshot(this) } catch (_: Exception) { emptyList() }
      if (snapshot.isNotEmpty()) return snapshot
    }
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { return emptyList() }
    return apps.mapNotNull { ri ->
      val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
      val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { return@mapNotNull null }
      label to pkg
    }
  }

  private fun findLaunchablePackage(target: String): String? {
    val apps = launchableNameSource()
    if (apps.isEmpty()) return null
    // F2 (12.10.2026) — targetul rostit (RO) vs. eticheta instalata (DE, sistem german) nu se
    // suprapun textual; AppNameSynonyms genereaza variante (articol taiat + sinonime RO/DE/EN),
    // scorul final ramane maxOf peste toate — o varianta proasta nu poate strica un match bun.
    val targetVariants = AppNameSynonyms.expandTarget(target)
    var best: String? = null
    var bestScore = -1
    for ((rawLabel, pkg) in apps) {
      val label = try { normalizeForMatch(rawLabel) } catch (_: Exception) { continue }
      val score = targetVariants.maxOf { AppNameMatcher.score(it, label, ::levenshtein) }
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
  // VISIBLE WORKING STATE (14.10.2026, ordin permanent) — un singur flag de revert pentru toata
  // regula: trezirea ecranului la wake + starea pe lockscreen prin notificare in loc de tăcere.
  // false = exact comportamentul de dinainte (ecran neatins, bula suprimata cand e blocat).
  private val VISIBLE_WORKING_STATE = true

  // IDENTITATEA VIZUALA BENSON (14.10.2026) — logoul original animat pe ecranul blocat.
  // Revert independent de VISIBLE_WORKING_STATE: false => rămâne doar notificarea, fara activitate.
  // RESPINS DE MASTER (15.10.2026): „Masterul respinge ecranul negru cu logoul intermediar.
  // BENSON are deja o interfață proprie... ACEASTA trebuie utilizată."
  // Activitatea `BensonLockscreenActivity` (fundal #0D0F0E + logo rotitor) a fost ideea MEA de
  // ieri, nu designul original BENSON — deci iese din fluxul normal. Cu `false`, pe ecran blocat
  // rămâne doar notificarea foreground (suprafață permisă, fără conținut privat), iar pe ecran
  // deblocat rămâne BULA peste aplicația activă, exact cum cere ordinul.
  // Activitatea rămâne în cod, dezactivată, ca să nu pierdem munca dacă Masterul o vrea altundeva.
  private val BENSON_LOCKSCREEN_UI = false

  // Punctul 6 din VISIBLE WORKING STATE — starea logoului trebuie sa reflecte activitatea REALA,
  // nu sa fie decorativa. O deducem din starea deja calculata a turei, fara o masina de stare noua.
  private fun logoStateFor(state: String, terminal: Boolean): String {
    val s = state.lowercase()
    return when {
      s.startsWith("ascult") -> "LISTENING"
      terminal -> "SPEAKING"
      s.endsWith("?") -> "WAITING"
      s.contains("caut") || s.contains("deschid") -> "WORKING"
      else -> "THINKING"
    }
  }

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
      // VISIBLE WORKING STATE, punctul 1 — „Reutilizează wakeScreen() existent pentru trezirea
      // ecranului după wake confirmat." Aceeasi functie dovedita pe 03.10 (FIX_WA_SCREEN_LOCKED_1,
      // folosita de apelul WhatsApp cu telefonul blocat), nu una noua. NU deblocheaza nimic:
      // FULL_WAKE_LOCK aprinde ecranul, keyguard-ul rămâne exact cum e (punctul 2).
      WakeFgDecision.VOICE_ONLY -> { if (VISIBLE_WORKING_STATE) try { wakeScreen() } catch (_: Exception) {} }
    }
  }

  // RUNDA_N2 TASK 2 — reuses the SAME native Intent protocol showBubbleNative()/showWakeRingNative()
  // already send to expo.modules.overlay.BensonBubbleService (unchanged, not touched this round);
  // this just sends more of the same ACTION_UPDATE_STATUS intents from more call sites, so the
  // bubble no longer depends on JS ever running updateBubbleStatus().
  // RUNDA_N3/N4 — "bula = dialog": state carries BENSON's actual spoken response (e.g. "Deschid
  // WhatsApp."), not a generic "gata"; transcript carries the user's last utterance. Suppressed
  // entirely under VOICE_ONLY (screen locked — "bula nu se poate afișa peste ecranul de blocare").
  private fun updateBubbleNative(
    state: String,
    transcript: String,
    terminal: Boolean,
    dismissDelayMs: Long,
    force: Boolean = false,
  ) {
    if (!force && lastStopAtMs > commandStartedAtMs) {
      AudioDiag.log(this, "BUBBLE_SUPPRESSED", "reason=interrupt_all")
      return
    }
    // VISIBLE WORKING STATE, punctele 3 si 5 (14.10.2026, ordin permanent al Masterului):
    // „Elimină suprimarea generală a bulei în VOICE_ONLY. Separă explicit SCREEN_LOCKED și
    // SCREEN_UNLOCKED." Cauza plangerii „nu există bulă", dovedita in logul testului vocal
    // (16:13:51 `BUBBLE suppressed reason=voice_only state="ASCULT"`): cu keyguard-ul activ,
    // decizia era VOICE_ONLY si bula era suprimata COMPLET, deci nimic nu se vedea.
    //
    // CONSTRANGERE ANDROID, nu alegere BENSON: un TYPE_APPLICATION_OVERLAY nu poate fi desenat
    // peste keyguard. Nu ocolim PIN-ul/biometria (punctul 2). Suprafata permisa pe lockscreen e
    // NOTIFICAREA foreground, pe care o avem deja — o folosim pentru STARE, fara conținut privat
    // (vezi BensonPhrasing.lockscreenState). Dupa deblocare, bula completa preia tot.
    // Revert: VISIBLE_WORKING_STATE = false => return imediat, exact ca inainte.
    // FIX_LOCKSCREEN_STALE_DECISION_1 — ecranul blocat se verifica LIVE (vezi mai jos de ce).
    // Daca decizia veche spune VOICE_ONLY dar telefonul e ACUM deblocat, nu mai suprimam nimic:
    // cadem pe drumul normal al bulei, exact ca inainte.
    val lockedNow = try {
      (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
    } catch (_: Exception) { false }
    if (currentWakeFgDecision == WakeFgDecision.VOICE_ONLY && lockedNow) {
      if (!VISIBLE_WORKING_STATE) {
        AudioDiag.log(this, "BUBBLE", "suppressed reason=voice_only state=\"$state\"")
        return
      }
      val safe = BensonPhrasing.lockscreenState(state, terminal)
      try {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
          .notify(NOTIFICATION_ID, buildNotification("BENSON", safe))
      } catch (_: Exception) {}
      // IDENTITATEA VIZUALA, punctul 5 — logoul animat pe lockscreen, prin activitatea
      // showWhenLocked. Primeste DOAR eticheta generica `safe`, niciodata conținutul.
      // La final de sarcina (terminal) o inchidem, ca sa nu rămână peste lockscreen (punctul 8).
      // Acelasi protocol ca pentru bula: ComponentName cu numele clasei ca STRING. Modulul
      // foreground-service NU depinde de modulul overlay la compilare (deliberat, asa e deja
      // cablat showBubbleNative) — altfel ar fi o dependenta noua intre module.
      // FIX_LOCKSCREEN_STALE_DECISION_1 (14.10.2026) — REGRESIE introdusa de mine, prinsa de G3
      // (2 rulari din 2, `FAIL|WA_MESSAGE_NO_ACTION`). `currentWakeFgDecision` se calculeaza DOAR
      // la wake si rămâne agatata: dupa ce ecranul fusese stins intr-un test anterior, decizia a
      // rămas VOICE_ONLY, iar in operare NORMALA (telefon deblocat) pornirea activitatii de
      // lockscreen FURA prim-planul. Dovada din log, 17:11:31.231:
      //   ENV_OBSERVE fg=com.benson.butler ...  -> TARGET_REACQUIRE_OK expected=com.whatsapp elapsedMs=925
      // adica WhatsApp a ajuns in fata cu ~1 s intarziere, iar intrebarea "Trimit?" n-a mai apucat
      // sa fie pusa inainte de raspunsul injectat.
      // Regula: ecranul blocat se verifica LIVE, in momentul afisarii, nu dintr-o decizie veche.
      val lockedNow = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
      } catch (_: Exception) { false }
      if (BENSON_LOCKSCREEN_UI && lockedNow) {
        try {
          if (terminal) {
            sendBroadcast(Intent("expo.modules.overlay.LOCKSCREEN_CLOSE"))
          } else {
            startActivity(
              Intent().apply {
                component = ComponentName(packageName, "expo.modules.overlay.BensonLockscreenActivity")
                putExtra("state", safe)
                putExtra("logoState", logoStateFor(state, terminal))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
              },
            )
          }
        } catch (_: Exception) {}
      }
      AudioDiag.log(this, "BUBBLE", "locked_notification state=\"$safe\"")
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
  // FIX_MEDIA_VERB_SYNONYMS_1 (15.10.2026) — sinonime LIPSA, demonstrate prin test pe telefon
  // (11:01–11:02), conform regulii Masterului "completează-l numai cu sinonime lipsă demonstrate
  // prin teste". Dovada: "play" si "următorul" mergeau instant pe calea determinista
  // (`media_ctl target=play` / `target=next`, ~8 ms), dar formele ROSTITE firesc cadeau la agentul
  // LLM — lent si nesigur:
  //   "dă-i drumul"  -> NATIVE_ROUTE action=agent_handoff
  //   "mai departe"  -> NATIVE_ROUTE action=agent_handoff  (agentul a apasat apoi un articol de
  //                     stiri, "USA gegen China", in loc de play)
  // Adaugate: da-i drumul / da drumul / reia / mergi mai departe / mai departe / sari peste.
  // "mai departe" si "sari peste" inseamna URMATORUL, nu play — mapate corespunzator mai jos.
  private val MEDIA_CTL_PATTERN = Regex(
    "^\\s*(pauza|pauză|opreste|oprește|stop|continua|continuă|porneste|pornește|play|reia|" +
      "da-i drumul|dă-i drumul|da drumul|dă drumul|da-i drumu|dă-i drumu|" +
      "urmatorul|următorul|urmatoarea|următoarea|next|mai departe|mergi mai departe|sari peste|" +
      "inapoi|înapoi|precedentul|precedenta|precedentă|previous)" +
      // INTERRUPT_ALL — Masterul a cerut explicit ca „opreste videoclipul" sa opreasca
      // videoclipul, nu conversatia. Pana azi obiectul acceptat era doar „muzica", deci fraza
      // cadea in afara ambelor drumuri.
      "\\s*(muzica|muzică|music|melodia|melodie|piesa|cantecul|cântecul|videoclipul|videoclip|" +
      "video|filmul|film|clipul|clip|redarea|serialul|podcastul|radio|radioul|youtube)?\\s*[.!?]*\\s*$",
    RegexOption.IGNORE_CASE,
  )

  private fun tryNativeMediaControl(command: String): String? {
    val m = MEDIA_CTL_PATTERN.find(normalizeForMatch(command)) ?: return null
    val word = m.groupValues[1]
    val action = when (word) {
      "pauza", "pauză", "opreste", "oprește", "stop" -> "pause"
      // FIX_MEDIA_VERB_SYNONYMS_1 — formele rostite firesc pentru "reia redarea".
      "continua", "continuă", "porneste", "pornește", "play", "reia",
      "da-i drumul", "dă-i drumul", "da drumul", "dă drumul", "da-i drumu", "dă-i drumu" -> "play"
      // "mai departe"/"sari peste" = piesa urmatoare, nu play.
      "urmatorul", "următorul", "urmatoarea", "următoarea", "next",
      "mai departe", "mergi mai departe", "sari peste" -> "next"
      else -> "previous"
    }
    logRoute("NATIVE_ROUTE", "action=media_ctl target=\"$action\"")
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
          logRoute("NATIVE_ROUTE", "action=context_search_refused reason=car_mode target=\"$query\" app=youtube")
          return "Nu pornesc video cât conduci."
        }
        logRoute("NATIVE_ROUTE", "action=context_search target=\"$query\" app=youtube")
        if (launchYoutubeSearch(query)) "Caut $query pe YouTube." else null
      }
      "com.spotify.music" -> {
        logRoute("NATIVE_ROUTE", "action=context_search target=\"$query\" app=spotify")
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
  // Op. 2 (14.10.2026) — contextul cerut explicit de Master: ultima interogare căutată, ca
  // "apasă pe primul rezultat" sa stie DUPA CE sa caute randul, fara sa repeti cererea.
  @Volatile private var lastSearchQuery: String = ""

  // Op. 2 — "caută pe internet X": web INAINTEA căutării in-aplicatie (vezi
  // ExplicitWebSearchMatcher pentru cauza dovedita). Lanseaza, apoi VERIFICA rezultatele pe ecran
  // prin motorul generic existent; fara dovada, spune ce n-a mers (doctrina 9).
  // Revert: EXPLICIT_WEB_SEARCH = false.
  private val EXPLICIT_WEB_SEARCH = true

  // RUNDA VOICE-ONLY (14.10.2026, punctele 1-2-4-6) — BENSON CITESTE rezultatele si accepta o
  // alegere ROSTITA. Aceeasi forma ca PendingWaDisambiguation/PendingAppSearch (lista numerotata,
  // raspuns prin ordinal, 2 incercari si renunta) — zero mecanism nou de conversatie sau de voce.
  // Etichetele sunt inghetate la momentul citirii; clickResultByLabel le re-cauta pe ecranul de
  // atunci, deci o lista devenita invalida eseueaza onest in loc sa apese altceva (punctul 4).
  private data class PendingResultChoice(
    val query: String,
    val labels: List<String>,
    val epoch: Long,
    val createdAt: Long = System.currentTimeMillis(), // FIX_RESULT_CHOICE_NO_SWALLOW_1 — expiră
    var noCount: Int = 0,
  )
  @Volatile private var pendingResultChoice: PendingResultChoice? = null

  private val VOICE_RESULT_LIST = true // revert: false => doar primul rezultat, ca inainte

  private fun ordinalWordRo(n: Int): String = when (n) {
    1 -> "Prima"; 2 -> "A doua"; 3 -> "A treia"; 4 -> "A patra"; else -> "A $n-a"
  }

  private fun countWordRo(n: Int): String = when (n) {
    2 -> "două variante"; 3 -> "trei variante"; 4 -> "patru variante"; else -> "$n variante"
  }

  // Construieste exact fraza ceruta de Master: "Master, am găsit trei variante. Prima este … A
  // doua este … A treia este … Pe care o preferați?"
  private fun speakResultList(query: String, labels: List<String>) {
    // Corectie ceruta de Master: se prezinta PRODUSUL (nume + pret), nu eticheta bruta a reclamei.
    // FIX_RESULT_LIST_LENGTH_1 — formulare TERSA. Cu "Prima este … A doua este …" lista ajungea la
    // 261 de caractere / 20 s de vorbire, iar Masterul nu avea cand sa raspunda (vezi
    // ResultLabelPresenter.MAX_SPOKEN_NAME pentru dovada din log).
    val sb = StringBuilder("Master, am găsit ${countWordRo(labels.size)}. ")
    labels.forEachIndexed { i, l ->
      sb.append(ordinalWordRo(i + 1)).append(": ").append(ResultLabelPresenter.present(l)).append(". ")
    }
    sb.append("Pe care o preferați?")
    val text = sb.toString()
    AudioDiag.log(this, "RESULT_LIST", "query=\"$query\" n=${labels.size} chars=${text.length}")
    // dismissDelayMs = 0 => bula RAMANE pe ecran pana la raspuns, exact ca starea "ASCULT".
    // Cu 8000 ms, bula dispărea după 8 s în timp ce BENSON mai citea încă 12 s (raportat de Master:
    // „nu exista bula"). Revert: BUBBLE_LINGER_MS.
    updateBubbleNative(text, query, terminal = false, dismissDelayMs = 0L)
    speak(text, "confirm")
  }

  // FIX_RESULT_CHOICE_NO_SWALLOW_1 (14.10.2026) — REGRESIE DOVEDITA, a mea, prinsa de G3 la
  // 15:51:02: o lista de rezultate rămasă neconfirmată a INGHITIT comenzile urmatoare, complet
  // nelegate ("suna la Hannah pe whatsapp" a fost tratat ca raspuns de tip ordinal) →
  // `FAIL|WA_CALL_ASK`, `FAIL|YT_SEARCH`, `FAIL|WA_MESSAGE_NO_ACTION`.
  // Doua reguli, exact cele cerute de Master: confirmarea e legata de O SINGURA actiune si EXPIRA;
  // iar o comanda noua nu se pierde niciodata intr-o intrebare veche ("Lasă asta", "Stop").
  // Returneaza true DOAR daca a consumat efectiv rostirea; false => comanda isi continua drumul
  // normal prin lantul nativ. Revert: `handlePendingResultChoice` fara valoare de retur + apelul
  // necondiționat din dispatcher.
  private val RESULT_CHOICE_TTL_MS = 60_000L
  private val RESULT_CHOICE_CANCEL = Regex(
    "^(?:lasa|lasă|lasa asta|lasă asta|stop|stai|anuleaza|anulează|renunta|renunță|nimic|niciuna|niciunul)\\b"
  )

  private fun handlePendingResultChoice(rawAnswer: String): Boolean {
    val pending = pendingResultChoice ?: return false
    if (System.currentTimeMillis() - pending.createdAt > RESULT_CHOICE_TTL_MS) {
      AudioDiag.log(this, "RESULT_PICK", "expired age_ms=${System.currentTimeMillis() - pending.createdAt}")
      pendingResultChoice = null
      return false
    }
    val n = parseWaOrdinal(rawAnswer)
    val chosen = n?.let { pending.labels.getOrNull(it - 1) }
    if (chosen == null) {
      if (RESULT_CHOICE_CANCEL.containsMatchIn(normalizeForMatch(rawAnswer))) {
        pendingResultChoice = null
        AudioDiag.log(this, "RESULT_PICK", "cancelled_by_user")
        finishBrainCommandSuccess("Am lăsat lista. Spuneți-mi când doriți altceva.", rawAnswer, "result_pick_cancel")
        return true
      }
      // Nu e un numar si nu e o anulare: daca sunt mai mult de doua cuvinte, e aproape sigur o
      // COMANDA NOUA, nu un raspuns ratat. O lasam sa treaca, in loc sa o pierdem.
      if (normalizeForMatch(rawAnswer).split(Regex("\\s+")).count { it.isNotBlank() } > 2) {
        AudioDiag.log(this, "RESULT_PICK", "released_to_command_chain")
        pendingResultChoice = null
        return false
      }
      AudioDiag.log(this, "RESULT_PICK", "unresolved raw_ordinal=${n ?: -1}")
      pending.noCount += 1
      if (pending.noCount >= 2) {
        pendingResultChoice = null
        val msg = "Am renunțat la listă. Spune-mi din nou ce să caut."
        updateBubbleNative(msg, rawAnswer, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
        speakNativeFallbackThenCapture(msg)
      } else {
        speak("Nu am înțeles numărul. Prima, a doua sau a treia?", "confirm")
      }
      return true
    }
    pendingResultChoice = null
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) { speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil."); return true }
    svc.runOnServiceScope {
      val result = svc.clickResultByLabel(chosen)
      mainHandler.post {
        if (result.ok) finishBrainCommandSuccess(result.message, rawAnswer, "result_pick_done")
        else {
          updateBubbleNative(result.message, rawAnswer, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
          speakNativeFallbackThenCapture(result.message)
        }
      }
    }
    return true
  }

  private fun tryExplicitWebSearch(command: String): Boolean {
    if (!EXPLICIT_WEB_SEARCH) return false
    val query = ExplicitWebSearchMatcher.extractQuery(command) ?: return false
    logRoute("NATIVE_ROUTE", "action=web_search_explicit target=\"$query\"")
    val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return false }
    val launched = try {
      startActivity(
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$encoded"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      true
    } catch (e: Exception) {
      AudioDiag.logError("NATIVE_ROUTE_ERROR", "action=web_search_explicit type=${e.javaClass.simpleName}")
      false
    }
    AudioDiag.log(this, "APP_LAUNCH", "pkg=browser ok=$launched")
    if (!launched) {
      val msg = "N-am putut deschide browserul."
      updateBubbleNative(msg, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(msg)
      return true
    }
    lastSearchQuery = query
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) {
      finishBrainCommandSuccess("Am căutat $query pe internet.", command, "web_search_done")
      return true
    }
    svc.runOnServiceScope {
      kotlinx.coroutines.delay(2600)
      // VOICE-ONLY — citim pana la 3 variante, nu doar prima.
      // FIX_RESULT_LIST_DEDUP_2 — dovedit pe telefon (15:36:10): doua rezultate cu etichete BRUTE
      // diferite ("10% RABATT lubluelu CHYW201…" vs "lubluelu CHYW201…") devin acelasi produs DUPA
      // curatare, deci Masterul auzea "a doua" si "a treia" identice. Deduplicarea trebuie sa se
      // faca pe forma PREZENTATA, nu pe eticheta bruta. Cerem mai multe candidate si pastram 3
      // distincte. Revert: svc.topResultLabels(query, 3) direct.
      val labels = if (VOICE_RESULT_LIST) {
        val seen = linkedSetOf<String>()
        val kept = mutableListOf<String>()
        for (raw in svc.topResultLabels(query, 8)) {
          // FIX_RESULT_LIST_DEDUP_3 — dovedit 15:47:09: deduplicarea pe forma COMPLETA (nume +
          // pret) lasa doua variante identice sa treaca, pentru ca una avea pretul atasat si
          // cealalta nu ("Fensterreiniger Fensterputzer, 82 virgulă 39 euro" vs
          // "Fensterreiniger Fensterputzer"). Acelasi produs = acelasi NUME; pretul nu il face alt
          // produs. Revert: cheia revine la ResultLabelPresenter.present(raw).
          val spoken = ResultLabelPresenter.productName(raw)
          if (spoken.isBlank() || !seen.add(spoken.lowercase())) continue
          kept.add(raw)
          if (kept.size >= 3) break
        }
        kept
      } else emptyList()
      val first = labels.firstOrNull() ?: svc.firstResultLabel(query)
      AudioDiag.log(this, "SEARCH_VERIFY", "query=\"$query\" pkg=browser found=${first != null} n=${labels.size} first=\"${first ?: ""}\"")
      mainHandler.post {
        when {
          labels.size >= 2 -> {
            pendingResultChoice = PendingResultChoice(query, labels, svc.currentScreenEpoch())
            speakResultList(query, labels)
          }
          first != null -> finishBrainCommandSuccess(
            "Am căutat $query. Am găsit o singură variantă: $first. O deschid?", command, "web_search_done",
          ).also { pendingResultChoice = PendingResultChoice(query, listOf(first), svc.currentScreenEpoch()) }
          // Punctul 6 — si cand NU gaseste, raspunde verbal si propune o alternativa.
          else -> finishBrainCommandSuccess(
            "Am deschis căutarea pentru $query, dar nu pot citi rezultatele de pe pagină. " +
              "Pot încerca altă formulare, dacă îmi spuneți una.",
            command, "web_search_done",
          )
        }
      }
    }
    return true
  }

  // Op. 2 partea a doua — "apasă pe primul rezultat", cu interogarea pastrata din comanda
  // precedenta. Clicul il face ControlLocator.findFirstResult EXISTENT, nu un mecanism nou.
  private fun tryFirstResult(command: String): Boolean {
    if (!FirstResultMatcher.matches(command)) return false
    val query = lastSearchQuery
    if (query.isBlank()) {
      logRoute("NATIVE_ROUTE", "action=first_result_declined reason=no_context")
      val msg = "Nu știu la ce rezultat te referi. Ce să caut mai întâi?"
      updateBubbleNative(msg, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(msg)
      return true
    }
    logRoute("NATIVE_ROUTE", "action=first_result target=\"$query\"")
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    if (svc == null) {
      speakNativeFallbackThenCapture("Serviciul de accesibilitate nu e disponibil.")
      return true
    }
    svc.runOnServiceScope {
      val result = svc.clickFirstResult(query)
      mainHandler.post {
        if (result.ok) finishBrainCommandSuccess(result.message, command, "first_result_done")
        else {
          updateBubbleNative(result.message, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
          speakNativeFallbackThenCapture(result.message)
        }
      }
    }
    return true
  }

  private fun tryNativeGeneralSearch(command: String): String? {
    val query = GeneralSearchMatcher.extractQuery(command) ?: return null
    logRoute("NATIVE_ROUTE", "action=web_search target=\"$query\"")
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
      // FIX_YT_SEARCH_INTENT_1 (15.10.2026) — CAUZA REALĂ a eșecului DETERMINIST `YT_PLAY_CONFIRM`,
      // găsită uitându-mă la ce e efectiv pe ecran după lansare:
      //   [1] button "guns n roses you could be mine instrumental"   <- căutarea VECHE, în casetă
      //   [8] button "Guns N' Roses: You Could Be Mine Instrumental"  <- rezultatele VECHI
      // `ACTION_SEARCH` cu extra "query" e ACCEPTAT de YouTube (resolveActivity != null), dar
      // aplicația îl IGNORĂ și restaurează sesiunea anterioară. Deci "madonna" nu era căutat
      // niciodată, iar `locateFirstResultOnScreen` nu avea ce găsi.
      // Mai grav: testul TRECEA mai devreme azi din motiv greșit — ultima căutare din YouTube
      // chiar era "madonna" (din rulările precedente), deci rezultatele vechi se potriveau
      // întâmplător. Un fals pozitiv, nu o funcție care merge.
      // Reparație: adresa de rezultate, care CHIAR execută căutarea, e acum calea principală;
      // `ACTION_SEARCH` rămâne rezervă. Revert: YT_SEARCH_VIA_URL = false.
      val ok = if (YT_SEARCH_VIA_URL) {
        startActivity(
          Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            .setPackage("com.google.android.youtube")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
      } else if (packageManager.resolveActivity(searchIntent, 0) != null) {
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
      logRoute("NATIVE_ROUTE", "action=yt_search_refused reason=car_mode target=\"$query\"")
      return "Nu pornesc video cât conduci."
    }
    logRoute("NATIVE_ROUTE", "action=yt_search target=\"$query\"")
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

  // RUNDA VOICE-ONLY (14.10.2026) — deleaga spre OrdinalMatcher (pur, JUnit-testat). Cifrele si
  // cardinalele se comporta exact ca inainte; se ADAUGA formele ordinale ("primul", "al doilea",
  // "a treia"), de care are nevoie alegerea dintr-o lista citita cu voce. Un singur parser in tot
  // codul, folosit si de WhatsApp si de lista de aplicatii. Revert: corpul vechi de mai jos.
  //   val norm = normalizeForMatch(text)
  //   Regex("\\d+").find(norm)?.let { return it.value.toIntOrNull() }
  //   for ((word, n) in WA_NUMBER_WORDS) if (norm.split(Regex("\\s+")).contains(word)) return n
  //   return null
  private fun parseWaOrdinal(text: String): Int? = OrdinalMatcher.parse(text)

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
    logRoute("WA_PARSE", "kind=$kind")
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
        // V1 (08.10.2026, M1 raportorul adevărat) — standardizează logarea OUTCOME cerută de Motor,
        // PESTE decizia deja existentă (result.success), nu o înlocuiește: WA era deja corect,
        // "Sun pe X."/"Trimis." se rostesc DOAR când result.success e adevărat, verificat mai sus.
        if (V1_TRUTHFUL_REPORTER) {
          val verifiedTag = if (result.success) "true" else if (result.step == "FIND_MESSAGE_INPUT") "partial" else "false"
          AudioDiag.log(this, "OUTCOME", "action=wa_${pending.kind} verified=$verifiedTag evidence=${result.step} spoken=\"$response\"")
        }
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
    // FIX_INTERRUPT_1 — PRIMUL in lant: "stop" trebuie sa functioneze si in mijlocul unei
    // confirmari sau al unei sarcini a agentului, nu doar cand BENSON sta degeaba.
    // FIX_AGENT_YIELDS_TO_COMMAND_1 (15.10.2026) — CAUZA REALĂ a eșecului DETERMINIST
    // `YT_PLAY_CONFIRM` (3 rulări din 3), găsită în log:
    //   21:34:44.737 youtube_search_confirm target="madonna"
    //   21:34:46.596 TTS "Nu am reușit să duc asta la capăt, Master."  ← AGENTUL, din comanda
    //                 anterioară, încă rula: vorbea peste test și umbla pe ecran
    //   21:34:50.761 YT_RESULT_WAIT found=false attempts=21 ms=6002    ← rezultatul chiar lipsea
    // `cancelAgentNow()` se chema DOAR când pornea un agent nou. O comandă DETERMINISTĂ
    // („caută madonna pe youtube") nu anula nimic, deci agentul vechi continua în paralel cu
    // executorii nativi — exact „doi agenți pe același ecran", doar că unul era motorul vechi.
    // Regula cerută de Master: „La STOP sau COMANDĂ NOUĂ, anulează rularea veche."
    // Orice rostire nouă a Masterului invalidează agentul în curs, înainte de orice rutare.
    // Revert: mută `cancelAgentNow()` înapoi doar în `tryAgent`.
    if (agentCall != null || agentRunId > 0) cancelAgentNow()
    if (tryInterrupt(command)) return
    // UNIVERSAL_ACTION_STOP - comanda noua ridica fereastra de oprire a degetului.
    commandStartedAtMs = System.currentTimeMillis()
    try { expo.modules.accessibility.BensonAccessibilityService.clearAbort() } catch (_: Throwable) {}
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
    if (pendingAppOpen != null) { // FIX_BRAIN_OPEN_APP_GATE_1
      handlePendingAppOpenAnswer(command)
      return
    }
    if (pendingNav != null) { // FIX_NATIVE_NAVIGATE_1
      handlePendingNavAnswer(command)
      return
    }
    // FIX_LIVE_PENDING_PROPOSAL_1 — un "da"/"caută" la o propunere a agentului execută direct
    // (vezi handlePendingAgentProposalAnswer); orice alt verdict consumă propunerea și lasă
    // comanda să treacă mai departe, neschimbată, prin restul lanțului.
    if (pendingAgentProposal != null && handlePendingAgentProposalAnswer(command)) return
    // RUNDA VOICE-ONLY — alegere rostită din lista citită. FIX_RESULT_CHOICE_NO_SWALLOW_1:
    // consumă rostirea DOAR dacă e un ordinal sau o anulare; altfel comanda nouă trece mai departe.
    if (pendingResultChoice != null && handlePendingResultChoice(command)) return
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
    // FIX_NATIVE_NAVIGATE_1 — "du-mă la X": alege aplicatia de navigatie din registru dupa
    // capabilitate, cere confirmare, apoi porneste. Inaintea WhatsApp-ului nu conteaza (tiparele
    // nu se suprapun), dar sta aici ca sa fie langa celelalte rute care pun o intrebare.
    if (tryNativeNavigate(command)) return
    // Op. 2 — cand Masterul NUMESTE internetul, nimic altceva n-are drept la comanda. Sta INAINTEA
    // căutării in-aplicatie (cauza dovedita: Amazon o captura). "primul rezultat" tot aici, ca sa
    // nu fie inghitit de "apasă pe X" generic (dovada: CONTROL_FIND intent=primul rezultat none).
    if (tryExplicitWebSearch(command)) return
    if (tryFirstResult(command)) return
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
    if (JS_VOICE_HANDOFF && isBensonForeground()) {
      logRoute("NATIVE_ROUTE", "action=js_handoff")
      // FIX_SINGLE_TRANSCRIPTION_1 (2026-10-04, device-proven on highway) — passing
      // (originalPhrase, originalWavPath) here made JS re-transcribe the SAME clip from scratch
      // via its own Deepgram call; that second, independent transcription disagreed with
      // WAKE_VERIFY's ("benson punem adona pe spotify", score 0.943) and came back as an empty
      // command, losing a command WAKE_VERIFY had already captured correctly. `command` (verify's
      // own text, wake word already stripped above) is the one transcription on this path now —
      // same as the isFollowUp branch already did. Revert: restore the if/else on isFollowUp.
      onHotwordDetected(command, null)
      rearmWakeNative("js_handoff")
    // FAZA 1 — agentul preia inaintea creierului vechi. Daca e OFF sau indisponibil (buget, cheie),
    // cade exact pe tryBrainFallback de pana acum, deci comportamentul vechi rămâne intact.
    } else if (tryAgent(command)) {
      AudioDiag.log(this, "NATIVE_ROUTE", "action=agent_handoff")
    } else if (tryBrainFallback(command)) {
      // INTEGRATION-1 (13.10.2026, B1 live) — interpretorul a preluat async; bula/vocea sunt
      // actualizate din callback-ul lui. Nu mai rula fallback-ul static mai jos.
      return
    } else {
      // J2 (12.10.2026) — un singur drum, indiferent de prim-plan, cât JS_VOICE_HANDOFF e OFF.
      // Textul exact cerut de RUNDA J, nu vechiul "Încă nu știu să fac asta." (păstrat pe celelalte
      // căi — dezambiguizare/numere — neatinse).
      val reason = if (JS_VOICE_HANDOFF) "benson_backgrounded" else "js_voice_handoff_off"
      logRoute("NATIVE_ROUTE", "action=native_fallback reason=$reason")
      val fallback = if (JS_VOICE_HANDOFF) "Încă nu știu să fac asta." else "Nu am înțeles. Repetă, te rog."
      updateBubbleNative(fallback, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
      speakNativeFallbackThenCapture(fallback)
    }
  }

  // INTEGRATION-2 (13.10.2026, arhitect-aprobat) — MINA, un singur apel LLM (nu doua secventiale
  // ca INTEGRATION-1): clasifica SI raspunde in acelasi JSON, intr-un singur round-trip. Ruleaza
  // DOAR dupa ce toti executorii deterministi de mai sus au esuat — zero risc pentru comenzile
  // canonice. Modelul nu atinge AccessibilityService: pentru "command", reconstruieste o fraza
  // canonica si o retrimite prin ACEIASI executori native (tryNativeWhatsApp/tryNativeOpenApp/
  // tryNativeYoutube*/tryNativeMediaControl/tryGlobalNav) sau apeleaza direct goBack()/goHome()
  // (deja existente in BensonAccessibilityService, folosite si in alta parte a fisierului) —
  // niciun executor nou. ConfirmationGate neatins: tryNativeWhatsApp isi pastreaza propria
  // confirmare (pendingWaAction), raspunsul YES/NO al utilizatorului e interceptat normal la
  // inceputul handleNativeCommandFlow, inainte sa ajunga vreodata aici.
  private val conversationHistory = ConversationHistory()
  // FIX_HISTORY_SEPARATION_1 — jurnal de actiuni, SEPARAT de dialog; nu pleaca spre model.
  private val actionHistory = ActionHistory()
  // FIX_BRAIN_TIMEOUT_1 (14.10.2026) — cauza dovedita pe telefon, nu presupusa. Cele trei
  // BRAIN_CALL de azi: ms=8014 ok=true, ms=10002 ok=false, ms=10005 ok=false, ambele esecuri cu
  // BRAIN_ERROR type=InterruptedIOException, adica exact acest callTimeout. gpt-5-nano cu
  // json_schema strict livreaza in 8-10+ s, deci un plafon de 10 s garanteaza eroarea: "cat este
  // 9 ori 6" nu primea niciun raspuns. 25 s = capabilitatea functioneaza.
  // ATENTIE, onest: asta repara FAIL-ul, NU latenta. 8-25 s pentru un raspuns vocal e inacceptabil
  // fata de tinta <700 ms; cauza latentei (model + reasoning + schema stricta + al doilea apel LLM
  // "interp shadow" pe acelasi drum de retea) e raportata separat, nu ascunsa aici.
  // Revert: callTimeout(10, SECONDS).
  // FIX_BRAIN_TIMEOUT_1b (14.10.2026) — prima versiune a fixului a fost INCOMPLETA, dovedit pe
  // telefon imediat: cu doar callTimeout(25s) a aparut `BRAIN_ERROR type=SocketTimeoutException`
  // la `ms=10006`, adica readTimeout-ul IMPLICIT al OkHttp (10 s) ramasese plafonul real. Se seteaza
  // explicit toate trei. Revert: doar .callTimeout(10, SECONDS).
  // FIX_AGENT_TIMEOUT_1 (19.10.2026, DOVADA PE TELEFON, test real Calculator) — CAUZA DOVEDITA,
  // reprodusa IDENTIC de doua ori: al treilea apel HTTP al unei rulari (primul care include un
  // rezultat de tool mare, raspunsul observe_screen) a picat cu InterruptedIOException la exact
  // 25s de ambele dati (AGENT_RUN steps=3, la 18:00:13 si din nou la 18:05:43 pe acelasi build).
  // 25s era suficient pentru raspunsuri scurte, dar nu si pentru un prompt crescut cu un digest de
  // ecran real. Ridicat la 45s — doar o margine mai mare, niciun comportament nou; o cerere care
  // oricum reusea in <25s nu e afectata.
  private val brainHttpClient = OkHttpClient.Builder()
    .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

  private val BRAIN_TURN_SCHEMA = """
    {"type":"object","properties":{
      "type":{"type":"string","enum":["command","chat","clarify"]},
      "action":{"type":["string","null"],"enum":["OPEN_APP","SEARCH_IN_APP","NAVIGATE","BACK","HOME","WHATSAPP_CALL","WHATSAPP_VIDEO","WHATSAPP_MESSAGE","MEDIA_CONTROL",null]},
      "app":{"type":["string","null"]},"query":{"type":["string","null"]},
      "contact":{"type":["string","null"]},"message":{"type":["string","null"]},
      "media_action":{"type":["string","null"],"enum":["play","pause","stop","next","previous",null]},
      "target":{"type":["string","null"]},"reply":{"type":["string","null"]},"question":{"type":["string","null"]}},
     "required":["type","action","app","query","contact","message","media_action","target","reply","question"],
     "additionalProperties":false}
  """.trimIndent()

  private fun brainTurnSystemPrompt(foregroundApp: String): String =
    "Ești MINA, vocea și interpretoarea asistentului BENSON. Primești o rostire a utilizatorului " +
      "(română, posibil germană/engleză) și istoricul conversației curente. Răspunzi STRICT cu " +
      "schema JSON dată, într-un singur pas — nu clasifici separat de răspuns. Aplicația activă " +
      "acum pe ecran: \"$foregroundApp\". type=\"command\": utilizatorul cere o acțiune din lista " +
      "permisă — completează action și câmpurile relevante (OPEN_APP: app; SEARCH_IN_APP: query, " +
      "presupune YouTube dacă nu se specifică altă aplicație media; NAVIGATE: target=destinație; " +
      "BACK=închide/ieși din ecranul curent; HOME=ecran principal; WHATSAPP_CALL/WHATSAPP_VIDEO: " +
      "contact; WHATSAPP_MESSAGE: contact+message EXACT, fără reformulare; MEDIA_CONTROL: " +
      "media_action). O corecție rostită ('nu, ...') e o comandă nouă, cu valorile actualizate, " +
      "nu o conversație. type=\"chat\": conversație liberă — scrie răspunsul scurt (1-2 fraze, " +
      "română naturală) în reply. type=\"clarify\": cererea e ambiguă — scrie o întrebare scurtă " +
      "în question, nu ghici. Câmpurile nefolosite = null."

  // Un singur apel HTTP: clasificare + răspuns, conform cerinței explicite.
  private fun brainTurn(transcript: String, foregroundApp: String): JSONObject? {
    if (OpenAiUsageMeter.isBudgetExceeded(this)) { AudioDiag.log(this, "BRAIN_SKIPPED", "reason=budget_exceeded"); return null }
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("brain_api_key", "") ?: ""
    if (apiKey.isBlank()) return null
    val baseUrl = (prefs.getString("brain_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").trimEnd('/')
    val messages = org.json.JSONArray().apply {
      put(JSONObject().apply { put("role", "system"); put("content", brainTurnSystemPrompt(foregroundApp)) })
      for (turn in conversationHistory.snapshot()) {
        put(JSONObject().apply { put("role", turn.role); put("content", turn.text) })
      }
      put(JSONObject().apply { put("role", "user"); put("content", transcript) })
    }
    val body = JSONObject().apply {
      put("model", "gpt-5-nano")
      put("messages", messages)
      put(
        "response_format",
        JSONObject().apply {
          put("type", "json_schema")
          put(
            "json_schema",
            JSONObject().apply { put("name", "mina_turn"); put("strict", true); put("schema", JSONObject(BRAIN_TURN_SCHEMA)) },
          )
        },
      )
    }
    val request = Request.Builder().url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    return try {
      brainHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) { AudioDiag.logError("BRAIN_ERROR", "http=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        val root = JSONObject(json)
        val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        val usage = root.optJSONObject("usage")
        if (usage != null) {
          val inTok = usage.optInt("prompt_tokens", 0)
          val outTok = usage.optInt("completion_tokens", 0)
          val cost = (inTok / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_INPUT_PER_1M_USD +
            (outTok / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_OUTPUT_PER_1M_USD
          OpenAiUsageMeter.recordUsage(this, "brain_turn_gpt5nano", cost)
        }
        if (content.isNullOrBlank()) return null
        JSONObject(content)
      }
    } catch (e: Exception) {
      AudioDiag.logError("BRAIN_ERROR", "type=${e.javaClass.simpleName}")
      null
    }
  }

  private fun foregroundAppLabel(): String {
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    val pkg = svc?.currentForegroundPackageLive()
    return if (pkg.isNullOrBlank() || pkg == packageName || pkg == launcherPackage()) "ecran principal" else pkg
  }

  // ───────────────────────── FAZA 1 — BENSON LLM AGENT ─────────────────────────
  // Mandat §2: agentul e motorul conversatiei SI al orchestrarii, integrat in BENSON, nu un al
  // doilea asistent. Decizie de arhitectura (15.10.2026), luata pe baza regulii Masterului insusi
  // din TEST H ("Nicio modificare a agentului nu justifica deteriorarea unei functii deja
  // validate") si §1 ("Nu reconstrui BENSON"): agentul preia INTELEGEREA si ORCHESTRAREA, iar
  // comenzile deterministe DOVEDITE (G3) isi pastreaza calea rapida de ~115 ms. Agentul sta exact
  // unde statea creierul vechi — la capatul lantului — dar, spre deosebire de el, poate EXECUTA
  // prin instrumente, in mai multi pasi, cu verificare.
  // Revert complet: LLM_AGENT = false => tryBrainFallback vechi, neatins, exact ca azi.
  private val LLM_AGENT = true
  private val MUSIC_REQUIRE_MEDIA_CAPABILITY = true
  private val YT_SEARCH_VIA_URL = true // FIX_YT_SEARCH_INTENT_1

  // FIX_AGENT_STAFF_BLINDNESS_1 (15.10.2026) — CAUZA DOVEDITA pe telefon, 10:12:30: la "pune Radio
  // România Actualități" agentul a deschis CHROME, desi telefonul are patru aplicatii de radio
  // (Simple Radio, Radio Garden, AccuRadio, Magic FM). Motivul: ii dadeam doar aplicatiile cu
  // capabilitati in tabelul din cod (9 pachete) — deci agentul NU STIA ca aplicatiile de radio
  // exista. Exact esecul pe care LEGEA PERSONALULUI il interzice: "BENSON trebuie sa stie pe cine
  // trimite".
  // Acum: pe langa cele cu capabilitati cunoscute, ii dam si aplicatiile instalate al caror nume
  // se potriveste cu CE A CERUT Masterul. Registrul are ~281 de aplicatii — nu incap intr-un
  // prompt si nici nu ajuta; selectia pe cuvintele cererii e relevanta si ieftina.
  // Revert: revino la filtrul `it.caps.isNotEmpty()` singur.
  private fun agentInstalledAppsSummary(command: String): String = try {
    val all = StaffRegistry.load(this).filter { !it.archived }
    val tokens = normalizeForMatch(command).split(Regex("\\s+"))
      .filter { it.length >= 4 }
    val byPurpose = all.filter { m ->
      val n = normalizeForMatch(m.displayName)
      tokens.any { t -> n.contains(t) || t.contains(n) }
    }
    val known = all.filter { it.caps.isNotEmpty() }
    (byPurpose + known).distinctBy { it.packageName }
      .joinToString(", ") { it.displayName }.take(400)
  } catch (_: Exception) { "" }

  // Executa UN instrument. Aici e singura granita prin care agentul atinge Androidul; fiecare
  // ramura cheama cod EXISTENT, dovedit. Returneaza (jsonResult, terminat).
  // FIX_AGENT_SINGLE_RUN_1 (15.10.2026) — SUPRAPUNERE DOVEDITA din loguri, nu banuita:
  //   10:41:14.786 AGENT_RUN steps=1 ms=25054   si   10:41:19.147 AGENT_RUN steps=1 ms=25047
  // doua rulari de ~25 s terminate la 4,3 s distanta => s-au suprapus ~21 s, doi agenti apasand
  // pe acelasi ecran. Si mai rau, la 11:01:57.412 apar in ACEEASI milisecunda:
  //   AGENT_TOOL step=1 name=observe_screen   SI   AGENT_CANCELLED run=5
  // adica agentul anulat APUCASE sa execute un instrument — exact "actiunea intarziata dupa
  // anulare" pe care Masterul o interzice.
  //
  // Doua garantii, nu una:
  //  1. `agentFingerLock` — acces EXCLUSIV la degetul BENSON: doua instrumente nu pot rula simultan.
  //  2. verificarea de prospetime CHIAR INAINTE de actiune, in interiorul lacatului — o rulare
  //     anulata cat astepta lacatul nu mai apasa nimic.
  // Revert: scoate parametrul runId si blocul synchronized.
  private val agentFingerLock = Any()

  // P0 (15.10.2026) — "anularea efectiva a cererii HTTP prin Call.cancel()". Fara ea, o rulare
  // anulata ramanea blocata ~22 s in apelul OkHttp si abia apoi vedea ca nu mai e a ei — timp in
  // care putea ajunge la pasul final si VORBI. Pastram apelul in curs ca sa-l putem taia imediat.
  @Volatile private var agentCall: okhttp3.Call? = null

  private fun cancelAgentNow() {
    agentRunId++
    try { agentCall?.cancel() } catch (_: Exception) {}
    agentCall = null
  }

  // Verifica la SERVER ca adresa chiar serveste audio. Intoarce (urlFinal, mimeType) sau null.
  // Daca adresa e o lista de redare (.m3u/.pls), extragem primul flux din ea si verificam din nou,
  // o singura data (fara recursie infinita). Asa, un URL inventat de model pica aici: da 404, sau
  // text/html, si nu ajunge la player. Foloseste clientul OkHttp existent.
  private fun verifyAudioStream(url: String, depth: Int = 0): Pair<String, String>? {
    if (depth > 1) return null
    return try {
      val req = Request.Builder().url(url).header("User-Agent", "BENSON/1.0").get().build()
      brainHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val ct = (resp.header("Content-Type") ?: "").lowercase().substringBefore(';').trim()
        when {
          ct.startsWith("audio/") && !ct.contains("mpegurl") && !ct.contains("scpls") ->
            url to ct
          // Lista de redare: luam prima linie care e un http(s) si o verificam la randul ei.
          ct.contains("mpegurl") || ct.contains("scpls") || ct == "application/pls+xml" -> {
            val body = resp.body?.source()?.let { s -> s.request(8192); s.buffer.snapshot().utf8() } ?: return null
            val first = body.lineSequence()
              .map { it.substringAfter("File1=", it).trim() }
              .firstOrNull { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            verifyAudioStream(first, depth + 1)
          }
          else -> null
        }
      }
    } catch (_: Exception) { null }
  }

  private fun runAgentTool(name: String, args: JSONObject, originalCommand: String, runId: Int): Pair<String, Boolean> =
    synchronized(agentFingerLock) {
      if (runId != agentRunId) {
        AudioDiag.log(this, "AGENT_TOOL_SKIPPED", "run=$runId name=$name reason=cancelled")
        return@synchronized BensonAgentTools.toolResult(false, "anulat") to true
      }
      runAgentToolLocked(name, args, originalCommand)
    }

  // AKA V1 (20.10.2026), CORECTAT (20.10.2026, dupa raportul af27cf7, 3 cerinte explicite) — unica
  // punte dintre EXECUTOR/SURVIVOR (press_button/tap_element, deja verificate prin
  // InteractionVerification.classifyInteractionOutcome) și cunoștințele PERSISTATE ale AKA
  // (StaffRegistry.markVerified, deja existent, deja scris pe disc — pana acum, niciodata apelat).
  //
  // Punctul 1 (atribuire corecta): `packageName` vine DIN ControlResult, citit de EXECUTOR ÎNAINTE
  // de apasare — niciodata o re-interogare a prim-planului de ACUM (care ar putea fi alta
  // aplicatie, ajunsa in prim-plan intre apasare si acest apel).
  // Punctul 2 (dovada specifica, nu un flag pe toata aplicatia): cheia e
  // StaffRegistryCore.uiControlCapabilityId(label) — "ui_control:7" e o dovada diferita de
  // "ui_control:cauta", in ACELASI map caps (niciun registru nou).
  // Punctul 3 (verificare relevanta, nu o schimbare oarecare): se persisteaza STRICT cand
  // strongEvidence e adevarat (semnalul de focalizare, nu doar "ceva s-a schimbat pe ecran
  // undeva") — mai conservator decat raspunsul dat Masterului, deliberat.
  // SURVIVOR (20.10.2026, etapa 3) — "solicita AKA reidentificarea functiilor cand interfata s-a
  // modificat": cand EXECUTORUL tocmai a esuat sa gaseasca un control pentru care exista deja o
  // dovada VERIFIED, aceea tocmai s-a aratat GRESITA, pe loc, prin incercare reala — se
  // retrogradeaza la REVERIFY (StaffRegistryCore.downgradeToReverify), nu ramane sa afirme fals
  // catre o misiune viitoare ca acel control "sigur exista". Reutilizeaza SurvivorDiagnosis.kt
  // (pur, testat) pentru decizie — aici doar citirea/scrierea Context-dependenta.
  // FIX_SURVIVOR_REIDENTIFY_FOREIGN_APP_1 (20.10.2026) — foloseste `classifyFaultOrigin` (acum
  // efectiv chemat in motor, nu doar testat izolat) ca SINGURA sursa a deciziei "a cui e vina" —
  // o aplicatie straina in prim-plan produce origine EXTERNAL_APP, NICIODATA AKA, indiferent de
  // `failureReason` brut, deci o cunostinta VERIFIED valida nu se retrogradeaza pentru o
  // observare pur si simplu INTRERUPTA de o notificare. Doar PRESS_BUTTON apeleaza aceasta functie
  // (nu TAP_ELEMENT) — un index "stale" e o staleness AȘTEPTATĂ intre o observare si o apasare, nu
  // dovada ca eticheta insasi nu mai exista nicaieri; `press_button` cauta in TOT arborele curent,
  // deci UI_NODE_NOT_FOUND de acolo e dovada mult mai tare.
  private fun recordSurvivorReidentificationLocked(
    packageName: String,
    label: String,
    failure: expo.modules.accessibility.InteractionFailureReason?,
    foreignAppDetected: Boolean,
  ) {
    if (packageName.isBlank() || label.isBlank()) return
    try {
      val capId = StaffRegistryCore.uiControlCapabilityId(label)
      val hadVerified = StaffRegistry.capsOf(applicationContext, packageName)[capId] == CapLevel.VERIFIED
      val origin = classifyFaultOrigin(failure, accessibilityAvailable = true, foreignAppForeground = foreignAppDetected)
      if (!needsReidentification(origin, hadVerified)) return
      StaffRegistry.markNeedsReverification(applicationContext, packageName, capId)
    } catch (_: Exception) {
    }
  }

  private fun recordUiControlVerifiedLocked(packageName: String, label: String, strongEvidence: Boolean) {
    if (!strongEvidence || packageName.isBlank() || label.isBlank()) return
    try {
      val capId = StaffRegistryCore.uiControlCapabilityId(label)
      StaffRegistry.markVerified(applicationContext, packageName, capId)
      AudioDiag.log(this, "AKA_RECORD", "pkg=$packageName cap=$capId")
    } catch (_: Exception) {
    }
  }

  private fun runAgentToolLocked(name: String, args: JSONObject, originalCommand: String): Pair<String, Boolean> {
    val svc = expo.modules.accessibility.BensonAccessibilityService.instance
    return when (name) {
      BensonAgentTools.OPEN_APP -> {
        val app = args.optString("name").trim()
        if (app.isBlank()) return BensonAgentTools.toolResult(false, "lipsește numele aplicației") to false
        val resp = tryNativeOpenApp("deschide $app")
        // FIX_AGENT_OPEN_APP_HONESTY_1 (19.10.2026, BENSON NEXT consolidare) — vezi
        // AgentOpenAppVerification.kt: `resp != null` trata gresit textele de ESEC ale
        // tryNativeOpenApp ("Am gasit X, dar n-am putut...", "Nu pornesc video...") ca succes.
        if (!classifyOpenAppResponseText(resp)) {
          return BensonAgentTools.toolResult(false, resp ?: "nu am găsit aplicația $app") to false
        }
        // SURVIVOR (acelasi principiu ca la navigatia Waze, 19.10.2026) — nu ne oprim la
        // "startActivity nu a aruncat" (ce intoarce deja tryNativeOpenApp), verificam REAL ca
        // aplicatia a ajuns in prim-plan inainte sa raportam succes agentului. Thread-ul agentului
        // tolereaza deja asteptari similare (verify_playing/open_url/play_stream, mai sus) — acelasi
        // tipar, nu unul nou. Reutilizeaza classifyLaunchVerification (NavigationMissionTrace.kt).
        val pkg = findLaunchablePackage(normalizeForMatch(app))
        val outcome = if (pkg != null && svc != null) {
          var attempt = 0
          var result: ExecutionOutcome = ExecutionOutcome.Unverified
          while (result is ExecutionOutcome.Unverified) {
            val observed = svc.currentForegroundPackageLive()
            result = classifyLaunchVerification(setOf(pkg), observed, attempt, 10)
            if (result is ExecutionOutcome.Unverified) Thread.sleep(400)
            attempt++
          }
          result
        } else null
        AudioDiag.log(this, "AGENT_OPEN_APP_VERIFY", "pkg=${pkg ?: "unknown"} outcome=${outcome ?: "skipped_no_accessibility"}")
        when (outcome) {
          is ExecutionOutcome.Failed -> BensonAgentTools.toolResult(false, "am pornit $app, dar nu a ajuns pe ecran") to false
          // Success, sau imposibil de verificat (accesibilitate/pachet necunoscut) — raspunsul deja
          // onest al tryNativeOpenApp ramane cel raportat, exact comportamentul dinainte de fix.
          else -> {
            // LIVE+AKA — VERIFICAREA COMPATIBILITĂȚII SEMANTICE (20.10.2026): daca aplicatia
            // tocmai deschisa are un domeniu NUMIT cunoscut, se ataseaza aici — cel mai devreme
            // punct posibil, inainte ca modelul sa apuce sa ceara o cautare incompatibila.
            val domainHint = if (pkg != null) {
              try { StaffRegistryCore.namedCapabilityDomainHints(StaffRegistry.capsOf(applicationContext, pkg)) } catch (_: Exception) { null }
            } else null
            val detail = resp ?: "am deschis aplicația"
            BensonAgentTools.toolResult(true, if (domainHint != null) "$detail $domainHint" else detail) to false
          }
        }
      }
      // LIVE+AKA — VERIFICAREA COMPATIBILITĂȚII SEMANTICE (20.10.2026). In doua faze, ca restul
      // instrumentelor cu efect (Calendar/Travel/Mobility): confirmed=false NU cauta nimic — doar
      // intoarce domeniul CUNOSCUT al aplicatiei curente (AKA, StaffRegistryCore.namedCapabilityDomainHints,
      // pur, nicio regula specifica unui cuvant), ca LIVE sa decida SEMANTIC daca cererea se
      // potriveste, INAINTE ca EXECUTORUL sa caute efectiv orbeste. Cand nu exista niciun domeniu
      // NUMIT cunoscut (aplicatie necunoscuta sau fara capacitate declarata), nu e nimic de
      // verificat — cautarea trece direct, exact ca inainte (zero regresie pe fluxurile deja
      // dovedite care NU folosesc acest instrument de agent, ci calea deterministica separata).
      BensonAgentTools.SEARCH_IN_APP -> {
        val q = args.optString("query").trim()
        if (q.isBlank()) return BensonAgentTools.toolResult(false, "lipsește textul căutat") to false
        if (svc == null) return BensonAgentTools.toolResult(false, "accesibilitatea nu e disponibilă") to false
        val confirmed = args.optBoolean("confirmed", false)
        if (!confirmed) {
          val fg = svc.currentForegroundPackageLive()
          val caps = if (fg.isNullOrBlank()) emptyMap() else try { StaffRegistry.capsOf(applicationContext, fg) } catch (_: Exception) { emptyMap() }
          val domainHint = StaffRegistryCore.namedCapabilityDomainHints(caps)
          if (domainHint != null) {
            return BensonAgentTools.toolResult(
              false,
              "$domainHint Dacă „$q” NU se potrivește cu acest domeniu, nu căuta aici — explică-i Masterului incompatibilitatea și propune o aplicație potrivită (fără să o deschizi fără acordul lui). Dacă se potrivește, cheamă din nou «search_in_app» cu confirmed=true.",
            ) to false
          }
          // Niciun domeniu NUMIT cunoscut — nimic de verificat, cauta direct (comportament vechi).
        }
        val r = kotlinx.coroutines.runBlocking {
          svc.runUniversalHandCommand(
            expo.modules.accessibility.UniversalHandCommand.Search(q), skipConfirmGate = true,
          )
        }
        BensonAgentTools.toolResult(r.ok, r.message) to false
      }
      BensonAgentTools.OBSERVE_SCREEN -> {
        val d = svc?.observeScreen()
        if (d == null) BensonAgentTools.toolResult(false, "nu pot citi ecranul") to false
        else {
          // AKA — INVENTARIERE AUTOMATA (20.10.2026, punctul 1: "fara lista inchisa de aplicatii
          // acceptate") — ORICE observe_screen descopera functiile candidate ale ecranului curent
          // (AkaKnowledge.discoverCandidateFunctions, pur, benson-accessibility — acelasi digest
          // deja citit, fara o a doua parcurgere a arborelui Accessibility) si le inregistreaza ca
          // KNOWN daca sunt noi. Nu retrogradeaza niciodata o dovada VERIFIED existenta.
          val signature = expo.modules.accessibility.AkaKnowledge.ScreenSignature.compute(d.packageName, d.elements)
          val candidates = expo.modules.accessibility.AkaKnowledge.discoverCandidateFunctions(d.elements)
          for (c in candidates) {
            try {
              StaffRegistry.markDiscovered(applicationContext, d.packageName, StaffRegistryCore.akaFunctionCapabilityId(signature, c.label))
            } catch (_: Exception) {}
          }
          // AKA punctul 5 ("LIVE consulta efectiv cunostintele AKA") — observe_screen e singurul
          // punct unde agentul primeste DEJA cunostinte persistate din rulari anterioare (nu doar
          // ce vede acum), citite din ACELASI StaffRegistry, nicio sursa noua. Compunerea textuala
          // insasi e `StaffRegistryCore.attachKnownUiControls` (pura, testata JUnit separat —
          // "contractul complet", 20.10.2026) — aici doar citirea Context-dependenta a datelor,
          // DUPA inventarierea de mai sus, ca descoperirile noi sa apara imediat.
          val caps = try { StaffRegistry.capsOf(applicationContext, d.packageName) } catch (_: Exception) { emptyMap() }
          val detail = StaffRegistryCore.attachKnownUiControls(d.render(), caps, signature)
          BensonAgentTools.toolResult(true, detail) to false
        }
      }
      BensonAgentTools.TAP_ELEMENT -> {
        val label = args.optString("label").trim()
        if (label.isBlank()) return BensonAgentTools.toolResult(false, "lipsește eticheta") to false
        if (svc == null) return BensonAgentTools.toolResult(false, "accesibilitatea nu e disponibilă") to false
        // FIX_AGENT_TAP_CONTRACT_1 — INDEXUL e autoritatea (lista e numerotata); eticheta e doar
        // control impotriva unui ecran schimbat intre observare si apasare.
        val idx = args.optInt("index", -1)
        if (idx < 0) return BensonAgentTools.toolResult(false, "lipsește indexul elementului") to false
        val r = kotlinx.coroutines.runBlocking { svc.tapDigestElement(idx, label) }
        if (r.ok) recordUiControlVerifiedLocked(r.packageName, label, r.strongEffectEvidence)
        // Punctul 4 (ordinul 20.10.2026): NU se cheama recordSurvivorReidentificationLocked aici —
        // un index "stale" e staleness AȘTEPTATĂ (ecranul s-a schimbat intre observare si apasare),
        // nu dovada ca eticheta insasi a disparut — vezi comentariul de la functia respectiva.
        BensonAgentTools.toolResult(r.ok, r.message, retryUnsafe = !r.ok && isAutomaticRetryUnsafe(r.failureReason)) to false
      }
      // SEMANTIC UI RESOLVER (19.10.2026, raspuns la DEVICE_FAIL Calculator repetat de 4 ori) —
      // nicio logica noua aici: doar cablarea apelului catre pressButtonByLabel (benson-accessibility,
      // ControlLocator.findButtonCandidates — acelasi motor G-1, acelasi clasificator SURVIVOR ca
      // tapDigestElement). Executie reala, nu un drum paralel.
      BensonAgentTools.PRESS_BUTTON -> {
        val label = args.optString("label").trim()
        if (label.isBlank()) return BensonAgentTools.toolResult(false, "lipsește eticheta butonului") to false
        if (svc == null) return BensonAgentTools.toolResult(false, "accesibilitatea nu e disponibilă") to false
        val r = kotlinx.coroutines.runBlocking { svc.pressButtonByLabel(label) }
        if (r.ok) recordUiControlVerifiedLocked(r.packageName, label, r.strongEffectEvidence)
        else recordSurvivorReidentificationLocked(r.packageName, label, r.failureReason, r.foreignAppDetected)
        BensonAgentTools.toolResult(r.ok, r.message, retryUnsafe = !r.ok && isAutomaticRetryUnsafe(r.failureReason)) to false
      }
      // CALENDAR AGENT (19.10.2026) — API oficial Android (CalendarContract), nu Accessibility;
      // toata decizia (conflict/duplicat/interval liber) traieste in CalendarEventMath.kt, pura.
      // Clasificate implicit AgentStepKind.OTHER (agentStepKindFor) — fiecare isi face verificarea
      // SURVIVOR prin recitire chiar in BensonCalendarExecutor, exact ca open_app; gardul de
      // "apasare neverificata" e specific Accessibility (observe_screen/tap_element), irelevant aici.
      BensonAgentTools.CALENDAR_READ_DAY -> {
        val startMs = BensonCalendarExecutor.parseIsoLocal(args.optString("start_iso"))
        val endMs = BensonCalendarExecutor.parseIsoLocal(args.optString("end_iso"))
        if (startMs == null || endMs == null) return BensonAgentTools.toolResult(false, "dată/oră nevalidă") to false
        val events = BensonCalendarExecutor.readDay(contentResolver, startMs, endMs)
        val detail = if (events.isEmpty()) "Nu ai nimic în calendar în acest interval." else
          events.joinToString("; ") { ev ->
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            "${sdf.format(java.util.Date(ev.startMillis))} ${ev.title}"
          }
        BensonAgentTools.toolResult(true, detail) to false
      }
      BensonAgentTools.CALENDAR_FIND_FREE_SLOT -> {
        val startMs = BensonCalendarExecutor.parseIsoLocal(args.optString("start_iso"))
        val endMs = BensonCalendarExecutor.parseIsoLocal(args.optString("end_iso"))
        val durationMin = args.optInt("duration_minutes", -1)
        if (startMs == null || endMs == null || durationMin <= 0) return BensonAgentTools.toolResult(false, "parametri nevalizi") to false
        val slots = BensonCalendarExecutor.findFreeSlots(contentResolver, startMs, endMs, durationMin)
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val detail = if (slots.isEmpty()) "Niciun interval liber de $durationMin minute în acest interval." else
          slots.joinToString("; ") { (s, e) -> "${sdf.format(java.util.Date(s))}–${sdf.format(java.util.Date(e))}" }
        BensonAgentTools.toolResult(true, detail) to false
      }
      BensonAgentTools.CALENDAR_CREATE_EVENT -> {
        val title = args.optString("title").trim()
        val startMs = BensonCalendarExecutor.parseIsoLocal(args.optString("start_iso"))
        val endMs = BensonCalendarExecutor.parseIsoLocal(args.optString("end_iso"))
        val allDay = args.optBoolean("all_day", false)
        val confirmed = args.optBoolean("confirmed", false)
        if (title.isBlank() || startMs == null || endMs == null) return BensonAgentTools.toolResult(false, "lipsesc detalii despre eveniment") to false
        val r = BensonCalendarExecutor.createEvent(contentResolver, title, startMs, endMs, allDay, confirmed)
        BensonAgentTools.toolResult(r.ok, r.message) to false
      }
      BensonAgentTools.CALENDAR_UPDATE_EVENT -> {
        val eventId = args.optLong("event_id", -1)
        if (eventId < 0) return BensonAgentTools.toolResult(false, "lipsește event_id") to false
        val newTitle = args.optString("new_title").trim().takeIf { it.isNotBlank() }
        val newStart = args.optString("new_start_iso").trim().takeIf { it.isNotBlank() }?.let { BensonCalendarExecutor.parseIsoLocal(it) }
        val newEnd = args.optString("new_end_iso").trim().takeIf { it.isNotBlank() }?.let { BensonCalendarExecutor.parseIsoLocal(it) }
        val confirmed = args.optBoolean("confirmed", false)
        val r = BensonCalendarExecutor.updateEvent(contentResolver, eventId, newTitle, newStart, newEnd, confirmed)
        BensonAgentTools.toolResult(r.ok, r.message) to false
      }
      // AKA — DISCOVER/MAP (20.10.2026) — explorare ACTIVA dar SIGURA: akaExploreByPressing
      // refuza mecanic orice control cu risc sau camp editabil (BensonAccessibilityService.kt);
      // aici doar inregistram tranzitia VERIFICATA (functie confirmata + muchie de navigatie),
      // exact ca recordUiControlVerifiedLocked — nicio logica de decizie noua aici.
      BensonAgentTools.AKA_EXPLORE_FUNCTION -> {
        val label = args.optString("label").trim()
        if (label.isBlank()) return BensonAgentTools.toolResult(false, "lipsește eticheta funcției") to false
        if (svc == null) return BensonAgentTools.toolResult(false, "accesibilitatea nu e disponibilă") to false
        val r = kotlinx.coroutines.runBlocking { svc.akaExploreByPressing(label) }
        if (r.ok && r.transitionVerified) {
          val pressed = r.pressedLabel
          val toSig = r.toSignature
          if (pressed != null && toSig != null) {
            try {
              StaffRegistry.markVerified(applicationContext, r.packageName, StaffRegistryCore.akaFunctionCapabilityId(r.screenSignature, pressed))
              StaffRegistry.markVerified(applicationContext, r.packageName, StaffRegistryCore.akaNavigationCapabilityId(r.screenSignature, pressed, toSig))
              AudioDiag.log(this, "AKA_RECORD", "pkg=${r.packageName} nav=${r.screenSignature}->$toSig")
            } catch (_: Exception) {}
          }
        }
        BensonAgentTools.toolResult(r.ok, r.message) to false
      }
      // AKA — "LIVE solicită informații despre o aplicație, identifică lipsurile" (20.10.2026) —
      // interogare PURA a cunostintelor DEJA stranse, acelasi resolver de nume ca open_app
      // (findLaunchablePackage), zero deschidere, zero apasare.
      BensonAgentTools.AKA_QUERY_APP -> {
        val name = args.optString("name").trim()
        if (name.isBlank()) return BensonAgentTools.toolResult(false, "lipsește numele aplicației") to false
        val pkg = findLaunchablePackage(normalizeForMatch(name))
          ?: return BensonAgentTools.toolResult(false, "nu găsesc o aplicație numită „$name”") to false
        val member = try { StaffRegistry.dossier(applicationContext, pkg) } catch (_: Exception) { null }
          ?: return BensonAgentTools.toolResult(false, "aplicația „$name” nu e încă în registru") to false
        BensonAgentTools.toolResult(true, StaffRegistryCore.formatAppKnowledgeSummary(member)) to false
      }
      // TRAVEL AGENT (20.10.2026) — DOAR pentru misiuni COMPUSE; comanda simpla de navigatie
      // ramane pe calea deterministica existenta, neatinsa. Reutilizeaza EXACT acelasi
      // capability-lookup (AKA) si acelasi mecanism de lansare+verificare SURVIVOR.
      BensonAgentTools.TRAVEL_NAVIGATE -> {
        val destination = args.optString("destination").trim()
        if (destination.isBlank()) return BensonAgentTools.toolResult(false, "lipsește destinația") to false
        val confirmed = args.optBoolean("confirmed", false)
        val candidates = try { StaffRegistry.staffForCapability(this, StaffRegistryCore.CAP_NAVIGATE) } catch (_: Exception) { emptyList() }
        val chosen = candidates.firstOrNull()
          ?: return BensonAgentTools.toolResult(false, "nu am o aplicație de navigație pe telefon") to false
        val (member, _) = chosen
        if (!confirmed) {
          return BensonAgentTools.toolResult(false, "PROPUNERE (neconfirmat): te duc la „$destination” cu ${member.displayName}. Confirm?") to false
        }
        val enc = try { java.net.URLEncoder.encode(destination, "UTF-8") } catch (_: Exception) { destination }
        val uris = navigationLaunchUris(member.packageName, enc)
        var launched = false
        for (u in uris) {
          launched = try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
          } catch (_: Exception) { false }
          if (launched) break
        }
        if (!launched) return BensonAgentTools.toolResult(false, "n-am putut porni navigația spre „$destination”") to false
        val svcNav = expo.modules.accessibility.BensonAccessibilityService.instance
        var attemptNav = 0
        var outcomeNav: ExecutionOutcome = ExecutionOutcome.Unverified
        while (outcomeNav is ExecutionOutcome.Unverified) {
          val observed = svcNav?.currentForegroundPackageLive()
          outcomeNav = classifyLaunchVerification(setOf(member.packageName), observed, attemptNav, 10)
          if (outcomeNav is ExecutionOutcome.Unverified) Thread.sleep(400)
          attemptNav++
        }
        AudioDiag.log(this, "TRAVEL_NAVIGATE_VERIFY", "pkg=${member.packageName} outcome=$outcomeNav")
        when (outcomeNav) {
          is ExecutionOutcome.Success -> BensonAgentTools.toolResult(true, "Te duc la „$destination” cu ${member.displayName}.") to false
          else -> BensonAgentTools.toolResult(false, "Am pornit ${member.displayName}, dar nu pot confirma că a ajuns pe ecran.") to false
        }
      }
      // MOBILITY — modul SUBORDONAT TRAVEL (cerinta explicita). Reutilizeaza EXACT acelasi motor
      // determinist deja dovedit (findAppsByCategory, RUNDA S-1 TASK 1) — nicio lista noua.
      BensonAgentTools.MOBILITY_FIND_APP -> {
        val category = args.optString("category").trim()
        if (category.isBlank()) return BensonAgentTools.toolResult(false, "lipsește categoria") to false
        val confirmed = args.optBoolean("confirmed", false)
        val chosenPkg = args.optString("chosen_pkg").trim()
        val found = try { findAppsByCategory(category) } catch (_: Exception) { emptyList() }
        if (!confirmed) {
          if (found.isEmpty()) return BensonAgentTools.toolResult(false, "nu am găsit nicio aplicație instalată de „$category”") to false
          val listed = found.joinToString("; ") { "${it.first} (${it.second})" }
          return BensonAgentTools.toolResult(false, "Aplicații găsite pentru „$category”: $listed. Spune-i Masterului lista; cheamă din nou cu chosen_pkg EXACT din ea.") to false
        }
        if (chosenPkg.isBlank() || found.none { it.second == chosenPkg }) {
          return BensonAgentTools.toolResult(false, "„$chosenPkg” nu e în lista găsită — nu deschid un pachet neverificat") to false
        }
        val ok = try { launchPackageNative(chosenPkg) } catch (_: Exception) { false }
        if (!ok) return BensonAgentTools.toolResult(false, "n-am putut deschide aplicația") to false
        val svcMob = expo.modules.accessibility.BensonAccessibilityService.instance
        var attemptMob = 0
        var outcomeMob: ExecutionOutcome = ExecutionOutcome.Unverified
        while (outcomeMob is ExecutionOutcome.Unverified) {
          val observed = svcMob?.currentForegroundPackageLive()
          outcomeMob = classifyLaunchVerification(setOf(chosenPkg), observed, attemptMob, 10)
          if (outcomeMob is ExecutionOutcome.Unverified) Thread.sleep(400)
          attemptMob++
        }
        val chosenName = found.first { it.second == chosenPkg }.first
        AudioDiag.log(this, "MOBILITY_FIND_APP_VERIFY", "pkg=$chosenPkg outcome=$outcomeMob")
        when (outcomeMob) {
          is ExecutionOutcome.Success -> BensonAgentTools.toolResult(true, "Am deschis $chosenName.") to false
          else -> BensonAgentTools.toolResult(false, "Am pornit $chosenName, dar nu pot confirma că a ajuns pe ecran.") to false
        }
      }
      BensonAgentTools.OPEN_URL -> {
        val url = args.optString("url").trim()
        // Validare in cod (§4 din mandat): doar http/https, nimic altceva. Un `intent://` sau un
        // `file://` venit de la model nu ajunge niciodata la startActivity.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
          AudioDiag.log(this, "AGENT_REJECT", "tool=open_url reason=bad_scheme")
          return BensonAgentTools.toolResult(false, "adresă nepermisă; doar http sau https") to false
        }
        val ok = try {
          startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
          )
          true
        } catch (_: Exception) { false }
        AudioDiag.log(this, "AGENT_OPEN_URL", "ok=$ok host=${runCatching { android.net.Uri.parse(url).host }.getOrNull()}")
        if (ok) Thread.sleep(3500) // lasam pagina/fluxul sa porneasca inainte de verificare
        BensonAgentTools.toolResult(ok, if (ok) "am deschis adresa" else "nu am putut deschide adresa") to false
      }
      BensonAgentTools.PLAY_STREAM -> {
        val url = args.optString("url").trim()
        val station = args.optString("station").trim().ifBlank { "postul" }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
          return BensonAgentTools.toolResult(false, "adresă nepermisă; doar http sau https") to false
        }
        // Ordinul Masterului: "numai dintr-o sursa REALA, VERIFICATA. Nu accepta URL-uri inventate".
        // Verificam la server CE serveste adresa, inainte sa pornim ceva. Un URL inventat da 404
        // sau text/html si e refuzat aici, nu ajunge niciodata la player.
        val verified = verifyAudioStream(url)
        if (verified == null) {
          AudioDiag.log(this, "STREAM_VERIFY", "ok=false host=${runCatching { android.net.Uri.parse(url).host }.getOrNull()}")
          return BensonAgentTools.toolResult(false, "adresa nu servește audio; nu o pornesc") to false
        }
        AudioDiag.log(this, "STREAM_VERIFY", "ok=true type=${verified.second} host=${runCatching { android.net.Uri.parse(verified.first).host }.getOrNull()}")
        val ok = try {
          startActivity(
            Intent(Intent.ACTION_VIEW).apply {
              setDataAndType(android.net.Uri.parse(verified.first), verified.second)
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
          )
          true
        } catch (_: Exception) { false }
        AudioDiag.log(this, "STREAM_PLAY", "ok=$ok station=\"$station\"")
        if (ok) Thread.sleep(4000)
        BensonAgentTools.toolResult(ok, if (ok) "am pornit fluxul pentru $station" else "nu am găsit un player pentru flux") to false
      }
      BensonAgentTools.VERIFY_PLAYING -> {
        // Semnal verificabil, nu o presupunere: isMusicActive spune daca iese efectiv sunet.
        // FIX_VERIFY_PLAYING_STRICT_1 (15.10.2026) — FALS POZITIV DOVEDIT pe telefon (11:44:48):
        // `verify_playing` a raportat `playing=true` cu titlul "VWs neue Strategie…" — un articol
        // GERMAN dintr-o sesiune Chrome rămasă de la o navigare anterioară, nu radioul cerut. La
        // verificarea imediat urmatoare, toate playerele erau `state:idle`. O singura citire a lui
        // isMusicActive poate prinde un sunet de o clipa sau o sesiune veche.
        // Un flux radio sună CONTINUU: cerem doua citiri la ~1,8 s distanta, ambele adevarate.
        // Asa, o reclama scurta sau un rest de sesiune nu mai trece drept "radioul merge".
        val am = try { getSystemService(Context.AUDIO_SERVICE) as AudioManager } catch (_: Exception) { null }
        val first = am?.isMusicActive == true
        if (first) Thread.sleep(1800)
        val playing = first && am?.isMusicActive == true
        val fg = expo.modules.accessibility.BensonAccessibilityService.instance?.currentForegroundPackageLive().orEmpty()
        val meta = try {
          expo.modules.notificationlistener.MediaTransport.metadata(applicationContext, fg)
        } catch (_: Exception) { null }
        val detail = if (playing) {
          "se aude audio" + (meta?.title?.takeIf { it.isNotBlank() }?.let { ": \"$it\"" } ?: "")
        } else "NU se aude niciun audio"
        AudioDiag.log(this, "AGENT_VERIFY_PLAYING", "playing=$playing pkg=$fg title=\"${meta?.title.orEmpty().take(40)}\"")
        BensonAgentTools.toolResult(playing, detail) to false
      }
      // BENSON ISI CUNOASTE COMENZILE — citire din registrul real (canal SYSTEM: constante din
      // cod, nu ecran si nu web). Nu executa nimic si nu incheie tura: creierul primeste datele
      // si apoi raspunde cu «say», ca la orice conversatie.
      BensonAgentTools.EXPLAIN_COMMAND -> {
        val q = args.optString("query").trim()
        val text = BensonCommandRegistry.explain(q)
        AudioDiag.log(this, "EXPLAIN_COMMAND", "query=\"$q\" chars=${text.length}")
        BensonAgentTools.toolResult(true, text, cap = 3500) to false
      }
      BensonAgentTools.SAY -> {
        val text = args.optString("text").trim().ifBlank { "Gata." }
        val expectsReply = args.optBoolean("expects_reply", false)
        conversationHistory.add("user", originalCommand); conversationHistory.add("assistant", text)
        // FIX_LIVE_PENDING_PROPOSAL_1 — stocheaza propunerea DOAR daca pachetul propus e un
        // rezultat REAL al aceluiasi resolver folosit de open_app (findLaunchablePackage, AKA/
        // StaffRegistry) — un nume pe care modelul l-a inventat sau care nu e instalat nu produce
        // nicio propunere activa (siguranta #4, "valideaza aplicatia prin AKA").
        val proposedApp = args.optString("proposed_app").trim()
        val proposedQuery = args.optString("proposed_query").trim()
        var proposalStored = false
        if (proposedApp.isNotBlank() && proposedQuery.isNotBlank()) {
          val pkg = findLaunchablePackage(normalizeForMatch(proposedApp))
          if (pkg != null) {
            pendingAgentProposal = PendingAgentProposal(pkg, proposedApp, proposedQuery, System.currentTimeMillis(), originalCommand)
            proposalStored = true
            AudioDiag.log(this, "AGENT_PROPOSAL", "action=stored pkg=$pkg app=\"$proposedApp\" query=\"$proposedQuery\"")
          } else {
            AudioDiag.log(this, "AGENT_PROPOSAL", "action=rejected reason=app_not_resolved app=\"$proposedApp\"")
          }
        }
        // Siguranta #6 (ordin) — o propunere activa TREBUIE sa tina sesiunea deschisa, garantat de
        // cod, nu doar de judecata modelului asupra lui expects_reply.
        if (expectsReply || proposalStored) {
          agentAwaitingReply = true
          continueAgentConversation(text, originalCommand)
        } else {
          agentAwaitingReply = false
          finishBrainCommandSuccess(text, originalCommand, "agent_done")
        }
        BensonAgentTools.toolResult(true, "spus") to true
      }
      else -> BensonAgentTools.toolResult(false, "instrument necunoscut") to true
    }
  }

  // FIX_LIVE_CONVERSATION_DEFAULT_OPEN_1 (20.10.2026, defect gasit prin test vocal real pe
  // telefon) — "Pot căuta lămpi într-un magazin ca Amazon Shopping sau în browser, dacă
  // dorești." (o ofertă, nu o întrebare cu "?") a închis sesiunea pe calea text-simplu (modelul
  // n-a folosit instrumentul "say"), fiindcă euristica veche cerea strict semnul "?". Corectat:
  // implicit sesiunea RĂMÂNE deschisă (acelaşi principiu ca la "say" — "dacă nu eşti sigur că ai
  // terminat, presupune că NU ai terminat"), închisă doar la o formulă de încheiere explicită,
  // generică — nicio aplicaţie/domeniu numit (doctrina "fără reguli rigide" ramane neatinsa,
  // acolo era despre potrivirea aplicaţie-intenţie, nu despre marcaje de încheiere a dialogului;
  // acelaşi tipar ca UNIVERSAL_ACTION_STOP, deja o listă explicită de cuvinte de control).
  private val CONVERSATION_CLOSING_PATTERN = Regex(
    """\b(la revedere|cu drag|numai bine|o zi buna|o seara buna|noapte buna)\b"""
  )

  private fun tryAgent(command: String): Boolean {
    if (!LLM_AGENT) return false
    if (JS_VOICE_HANDOFF) return false
    if (OpenAiUsageMeter.isBudgetExceeded(this)) { AudioDiag.log(this, "AGENT_SKIPPED", "reason=budget"); return false }
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("brain_api_key", "") ?: ""
    if (apiKey.isBlank()) return false
    val baseUrl = (prefs.getString("brain_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").trimEnd('/')

    Thread({
      val startMs = System.currentTimeMillis()
      val messages = org.json.JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", BensonAgentTools.systemPrompt(foregroundAppLabel(), agentInstalledAppsSummary(command)))
        })
        // FIX_LIVE_SEMANTIC_PRECHECK_1 — fapt dinamic, calculat per comandă (vezi funcția), nu
        // parte din promptul static — ajunge la model ÎNAINTE de prima lui decizie, ca o aplicație
        // numită explicit și incompatibilă să nu mai fie deschisă deloc (1 apel OpenAI, nu 3).
        explicitlyNamedAppCompatibilityHint(command)?.let { hint ->
          put(JSONObject().apply { put("role", "system"); put("content", hint) })
        }
        for (turn in conversationHistory.snapshot()) {
          put(JSONObject().apply { put("role", turn.role); put("content", turn.text) })
        }
        put(JSONObject().apply { put("role", "user"); put("content", command) })
        // FIX_ANSWER_LENGTH_3 — directiva de lungime, ULTIMUL mesaj, cu plafon numeric. Nu taie
        // nimic (interdictie explicita a Masterului); doar ii spune modelului cat sa scrie.
        put(JSONObject().apply { put("role", "system"); put("content", BensonAgentTools.lengthDirective(command)) })
      }
      // FIX_INTERRUPT_1 — fiecare rulare isi ia un numar; "stop" incrementeaza agentRunId, deci
      // bucla vede ca nu mai e a ei si se opreste la pasul urmator. Fara Thread.stop (interzis),
      // fara a lasa o apasare la jumatate.
      // O comanda noua invalideaza complet rularea veche, inclusiv apelul ei HTTP.
      cancelAgentNow()
      val myRun = agentRunId
      var done = false
      var steps = 0
      // GARD DE VERIFICARE IN COD (19.10.2026) — vezi AgentVerificationGate.kt. callIndex creste o
      // data per apel de instrument REAL procesat (nu per iteratie a buclei), ca sa ordoneze corect
      // mai multe instrumente cerute in acelasi raspuns al modelului.
      var gateState = AgentVerificationGateState()
      var callIndex = 0
      // FIX_AGENT_COMPOUND_OPEN_NO_ACTION_1 (19.10.2026, DOVADA PE TELEFON) — vezi
      // AgentVerificationGate.kt: o comanda compusa ("deschide X si ...") care deschide aplicatia
      // dar nu apasa nimic inauntru nu poate incheia tura — altfel modelul "calculeaza singur" un
      // rezultat (dovedit: "27 ori 14 este 378" rostit fara niciun tap_element).
      val commandIsCompound = COMPOUND_COMMAND_PATTERN.containsMatchIn(normalizeForMatch(command))
      var openAppSucceeded = false
      // FIX_AGENT_CLAIMED_NUMBER_NOT_OBSERVED_1 (19.10.2026, S4, DOVADA PE TELEFON) — continutul
      // ULTIMEI observari reale (observe_screen), ca sa putem verifica ca un numar rostit ca
      // rezultat final chiar a fost VAZUT, nu doar calculat. Vezi AgentVerificationGate.kt.
      var lastObservedScreenText: String? = null
      // SURVIVOR (20.10.2026, etapa 3) — "preveni repetarea apelurilor/mesajelor/platilor":
      // reutilizeaza ExecutionIdempotencyKey/isDuplicateExecution (D0, BensonAgentContracts.kt,
      // deja pur/testat) ca sa refuze o actiune IDENTICA (acelasi instrument, aceiasi parametri)
      // cerinduta a doua oara in ACEEASI rulare, dupa ce a raportat DEJA succes o data — modelul
      // poate crede gresit ca prima incercare a esuat (ex. un timeout de retea) si sa ceara din
      // nou exact acelasi apel, ceea ce ar duplica un efect real.
      val executedActions = mutableSetOf<ExecutionIdempotencyKey>()
      // FIX_SURVIVOR_PROGRESS_NOT_RECOVERY_1 (20.10.2026, corectie explicita a Masterului) —
      // ATENTIE la ce NU face asta: e strict RAPORTARE de progres, NU recuperare efectiva a
      // misiunii. Nimic de aici re-ia automat pasii ramasi — rularea tot se opreste, iar
      // continuarea cere o comanda NOUA, explicita, a Masterului. `missionProgress` e un traseu
      // SCURT, STRICT al ACESTEI rulari (nu istoricul global ActionHistory, partajat intre comenzi
      // diferite — amestecarea lor ar insemna sa afirmam fals ca am "realizat" ceva dintr-o
      // misiune anterioara, fara legatura). Cand rularea se opreste la MAX_STEPS fara sa termine,
      // mesajul onest de esec spune CE s-a reusit pana atunci — ca Masterul sa poata CONTINUA el,
      // cu o comanda noua, informat — nu ca sistemul ar relua ceva singur.
      val missionProgress = mutableListOf<String>()
      try {
        while (!done && steps < BensonAgentTools.MAX_STEPS) {
          if (myRun != agentRunId) { AudioDiag.log(this, "AGENT_CANCELLED", "run=$myRun"); return@Thread }
          steps++
          val body = JSONObject().apply {
            put("model", "gpt-5-nano")
            put("messages", messages)
            put("tools", org.json.JSONArray(BensonAgentTools.toolsJson()))
          }
          val req = Request.Builder().url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
          val call = brainHttpClient.newCall(req)
          agentCall = call
          val msg = call.execute().use { resp ->
            if (!resp.isSuccessful) { AudioDiag.logError("AGENT_ERROR", "http=${resp.code}"); return@use null }
            val root = JSONObject(resp.body?.string() ?: return@use null)
            root.optJSONObject("usage")?.let { u ->
              val cost = (u.optInt("prompt_tokens") / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_INPUT_PER_1M_USD +
                (u.optInt("completion_tokens") / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_OUTPUT_PER_1M_USD
              OpenAiUsageMeter.recordUsage(this, "agent_gpt5nano", cost)
            }
            root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
          } ?: break
          messages.put(msg)
          if (myRun != agentRunId) { AudioDiag.log(this, "AGENT_CANCELLED", "run=$myRun stage=after_http"); return@Thread }
          val calls = msg.optJSONArray("tool_calls")
          if (calls == null || calls.length() == 0) {
            // Fara instrument: raspuns pur conversational. Il rostim si incheiem.
            val text = msg.optString("content").trim()
            // P0 punctul 4: identificatorul rulării se verifică ÎNAINTE de orice TTS, nu doar
            // înainte de instrumente. Un răspuns sosit după anulare e rezultat INVALID (punctul 3).
            if (text.isNotBlank() && myRun == agentRunId) {
              // GARD DE VERIFICARE IN COD — un raspuns conversational nu poate incheia tura daca
              // ultima apasare (tap_element) n-a fost inca urmata de o observare reala a ecranului,
              // SAU daca o comanda compusa a deschis aplicatia dar n-a apasat inca nimic inauntru.
              if (!isTurnEndAllowedNow(gateState)) {
                AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=conversational_end step=$callIndex")
                messages.put(JSONObject().apply { put("role", "user"); put("content", VERIFICATION_GATE_REJECTION_MESSAGE) })
                continue
              }
              if (isCompoundOpenWithoutActionBlocked(commandIsCompound, openAppSucceeded, gateState.lastActionStep)) {
                AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=compound_open_conversational_end step=$callIndex")
                messages.put(JSONObject().apply { put("role", "user"); put("content", COMPOUND_OPEN_REJECTION_MESSAGE) })
                continue
              }
              if (claimedNumberMissingFromLastObservation(commandIsCompound, text, lastObservedScreenText)) {
                AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=claimed_number_conversational_end step=$callIndex")
                messages.put(JSONObject().apply { put("role", "user"); put("content", CLAIMED_NUMBER_NOT_OBSERVED_REJECTION_MESSAGE) })
                continue
              }
              conversationHistory.add("user", command); conversationHistory.add("assistant", text)
              // FIX_LIVE_CONVERSATION_CONTINUES_1 — modelul e instruit (systemPrompt) să folosească
              // instrumentul `say` cu `expects_reply` de fiecare dată; acest ramus text-simplu e
              // doar plasa de siguranță pentru răspunsul rar fără niciun tool_call. Vezi
              // FIX_LIVE_CONVERSATION_DEFAULT_OPEN_1: implicit ramane deschisa, inchisa doar la o
              // formula explicita de incheiere.
              val expectsReply = !CONVERSATION_CLOSING_PATTERN.containsMatchIn(normalizeForMatch(text))
              mainHandler.post {
                if (myRun == agentRunId) {
                  if (expectsReply) {
                    agentAwaitingReply = true
                    continueAgentConversation(text, command)
                  } else {
                    agentAwaitingReply = false
                    finishBrainCommandSuccess(text, command, "agent_chat")
                  }
                }
              }
              done = true
            }
            break
          }
          for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function")
            val fname = fn?.optString("name")
            val fargs = runCatching { JSONObject(fn?.optString("arguments") ?: "{}") }.getOrDefault(JSONObject())
            if (!BensonAgentTools.isKnownTool(fname)) {
              AudioDiag.log(this, "AGENT_REJECT", "tool=${fname ?: "none"}")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, "instrument neautorizat"))
              })
              continue
            }
            // GARD DE VERIFICARE IN COD (19.10.2026, "nu doar prin systemPrompt") — "say" nu poate
            // incheia tura dupa o apasare neverificata; respinge instrumentul (fara sa-l execute,
            // fara sa vorbeasca) si forteaza o noua observare. Reutilizeaza AgentVerificationGate.kt.
            if (fname == BensonAgentTools.SAY && !isTurnEndAllowedNow(gateState)) {
              AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=say step=$callIndex")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, VERIFICATION_GATE_REJECTION_MESSAGE))
              })
              callIndex++
              continue
            }
            if (fname == BensonAgentTools.SAY && isCompoundOpenWithoutActionBlocked(commandIsCompound, openAppSucceeded, gateState.lastActionStep)) {
              AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=compound_open_say step=$callIndex")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, COMPOUND_OPEN_REJECTION_MESSAGE))
              })
              callIndex++
              continue
            }
            if (fname == BensonAgentTools.SAY &&
              claimedNumberMissingFromLastObservation(commandIsCompound, fargs.optString("text"), lastObservedScreenText)
            ) {
              AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=claimed_number_say step=$callIndex")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, CLAIMED_NUMBER_NOT_OBSERVED_REJECTION_MESSAGE))
              })
              callIndex++
              continue
            }
            // GARD AL TREILEA (19.10.2026, dovada pe telefon) — tap_element niciodata inaintea
            // primului observe_screen al rularii: un index fara digest real e o apasare ghicita.
            if (fname == BensonAgentTools.TAP_ELEMENT && !isTapAllowedNow(gateState)) {
              AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=tap_without_observe step=$callIndex")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, TAP_WITHOUT_OBSERVE_REJECTION_MESSAGE))
              })
              callIndex++
              continue
            }
            // SURVIVOR — GARD AL PATRULEA: o actiune (ACTION, nu observare) IDENTICA (acelasi
            // instrument + aceiasi parametri) care a reusit DEJA o data in aceasta rulare nu se
            // repeta. Doar actiunile cu efect potential extern conteaza — observarea nu are nevoie
            // de aceasta garda (poate fi chemata oricat de des, e read-only).
            val idempotencyKey = if (agentStepKindFor(fname) == AgentStepKind.ACTION) {
              ExecutionIdempotencyKey(myRun.toString(), "$fname:${fargs}")
            } else null
            if (idempotencyKey != null && isDuplicateExecution(executedActions, idempotencyKey.missionId, idempotencyKey.stepId)) {
              AudioDiag.log(this, "AGENT_VERIFY_GATE", "blocked=duplicate_action step=$callIndex name=$fname")
              messages.put(JSONObject().apply {
                put("role", "tool"); put("tool_call_id", call.optString("id"))
                put("content", BensonAgentTools.toolResult(false, "Ai mai cerut exact această acțiune o dată în această sarcină, iar efectul ei nu e sigur de exclus — nu o repet, ca să nu dublez un efect real. Dacă ai nevoie de altceva, spune-mi clar ce lipsește sau ce s-a schimbat."))
              })
              callIndex++
              continue
            }
            val t0 = System.currentTimeMillis()
            val (result, finished) = runAgentTool(fname!!, fargs, command, myRun)
            AudioDiag.log(this, "AGENT_TOOL", "step=$steps name=$fname ms=${System.currentTimeMillis() - t0}")
            messages.put(JSONObject().apply {
              put("role", "tool"); put("tool_call_id", call.optString("id")); put("content", result)
            })
            val toolOk = runCatching { JSONObject(result).optBoolean("ok", false) }.getOrDefault(false)
            if (fname == BensonAgentTools.OPEN_APP) {
              openAppSucceeded = openAppSucceeded || toolOk
            }
            if (fname == BensonAgentTools.OBSERVE_SCREEN) {
              lastObservedScreenText = runCatching { JSONObject(result).optString("detail").takeIf { it.isNotBlank() } }.getOrNull()
            }
            // SURVIVOR, punctele 2+3 (ordinul 20.10.2026) — "nu permite repetarea automata...
            // doar pentru ca lipseste confirmarea succesului": un rezultat INCERT (retry_unsafe,
            // citit mecanic din JSON, nu doar oferit ca indiciu modelului) se blocheaza de
            // repetare EXACT ca un succes — poate sa fi produs deja efectul.
            val retryUnsafe = runCatching { JSONObject(result).optBoolean("retry_unsafe", false) }.getOrDefault(false)
            if (idempotencyKey != null && (toolOk || retryUnsafe)) {
              executedActions.add(idempotencyKey)
              if (toolOk) {
                // Doar eticheta/numele (generic, orice instrument) — nu textul complet al
                // raspunsului, ca sa nu dubleze o afirmatie deja verificata separat.
                val subject = fargs.optString("label").ifBlank { fargs.optString("name") }.ifBlank { fargs.optString("title") }
                missionProgress.add(if (subject.isBlank()) fname else "$fname($subject)")
              }
            }
            gateState = advanceVerificationGate(gateState, callIndex, agentStepKindFor(fname))
            callIndex++
            if (finished) done = true
          }
        }
      } catch (e: Exception) {
        AudioDiag.logError("AGENT_ERROR", "type=${e.javaClass.simpleName}")
      }
      AudioDiag.log(this, "AGENT_RUN", "steps=$steps done=$done ms=${System.currentTimeMillis() - startMs}")
      // P0 punctele 2+3+6: o rulare ANULATĂ nu mai are voie nici să apese, nici să VORBEASCĂ.
      // Asta era gura prin care un agent abandonat încă rostea un mesaj (dovedit 11:20:39).
      // Dubla verificare: o dată aici, o dată pe firul principal, chiar înainte de TTS.
      if (!done && myRun == agentRunId) {
        // Mandat §4 + doctrina 9: niciodata tacere, niciodata succes nedovedit.
        mainHandler.post {
          if (myRun != agentRunId) {
            AudioDiag.log(this, "AGENT_SPEECH_SUPPRESSED", "run=$myRun reason=cancelled")
            return@post
          }
          // SURVIVOR (20.10.2026, etapa 3) — RAPORTARE de progres, NU recuperare automata (vezi
          // comentariul de la `missionProgress`): un esec onest nu trebuie sa fie si un esec
          // OARB — daca s-a realizat ceva verificat inainte de blocaj, Masterul afla CE, dar
          // misiunea NU se reia singura — Masterul decide explicit urmatorul pas.
          val msg = if (missionProgress.isEmpty()) {
            "Nu am reușit să duc asta la capăt, Master. Spuneți-mi altfel, vă rog."
          } else {
            "Nu am reușit să duc asta la capăt, Master. Am apucat să fac: ${missionProgress.joinToString(", ")}. Spuneți-mi cum continuăm."
          }
          // Raport final de eșec — niciodată o întrebare: închide orice "așteptare de răspuns"
          // rămasă dintr-o tură conversațională anterioară a aceleiași rulări.
          agentAwaitingReply = false
          updateBubbleNative(msg, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
          speakNativeFallbackThenCapture(msg)
        }
      } else if (!done) {
        AudioDiag.log(this, "AGENT_SPEECH_SUPPRESSED", "run=$myRun reason=cancelled")
      }
    }, "benson-agent").start()
    return true
  }

  private fun tryBrainFallback(command: String): Boolean {
    if (JS_VOICE_HANDOFF) return false
    if (OpenAiUsageMeter.isBudgetExceeded(this)) return false
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    if ((prefs.getString("brain_api_key", "") ?: "").isBlank()) return false
    val foregroundApp = foregroundAppLabel()
    val startMs = System.currentTimeMillis()
    Thread({
      val result = brainTurn(command, foregroundApp)
      val elapsedMs = System.currentTimeMillis() - startMs
      val type = result?.optString("type") ?: "none"
      val action = result?.optString("action")?.takeIf { it.isNotBlank() && it != "null" }
      AudioDiag.log(this, "BRAIN_CALL", "ms=$elapsedMs type=$type action=${action ?: "none"} ok=${result != null}")
      mainHandler.post { dispatchBrainTurn(command, result) }
    }, "benson-brain-turn").start()
    return true
  }

  private fun dispatchBrainTurn(command: String, result: JSONObject?) {
    when (result?.optString("type")) {
      "chat" -> {
        val reply = result.optString("reply").trim().takeIf { it.isNotBlank() } ?: "Nu pot vorbi acum."
        conversationHistory.add("user", command); conversationHistory.add("assistant", reply)
        updateBubbleNative(reply, command, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
        speak(reply, "chat")
      }
      "clarify" -> {
        val question = result.optString("question").trim().takeIf { it.isNotBlank() } ?: "Poți spune mai clar ce vrei?"
        conversationHistory.add("user", command); conversationHistory.add("assistant", question)
        updateBubbleNative(question, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
        speak(question, "clarify")
      }
      "command" -> {
        val action = result?.optString("action")
        if (!executeBrainCommand(action, result!!, command)) {
          AudioDiag.log(this, "BRAIN_REJECT", "action=${action ?: "none"}")
          logRoute("NATIVE_ROUTE", "action=native_fallback reason=brain_unresolved")
          val fallback = "Nu am înțeles. Repetă, te rog."
          conversationHistory.add("user", command); conversationHistory.add("assistant", fallback)
          updateBubbleNative(fallback, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
          speakNativeFallbackThenCapture(fallback)
        }
      }
      else -> {
        logRoute("NATIVE_ROUTE", "action=native_fallback reason=brain_unresolved")
        val fallback = "Nu am înțeles. Repetă, te rog."
        updateBubbleNative(fallback, command, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
        speakNativeFallbackThenCapture(fallback)
      }
    }
  }

  // FIX_AGENT_SPEAKS_1 (15.10.2026) — CAUZA DOVEDITA a plangerii „eu vreau un chat bot cu care sa
  // vorbesc, nu text". In testul de conversatie de 5 replici: agentul chema `say` de fiecare data,
  // raspunsul aparea in bula, dar in log **nu exista NICIO linie TTS_SPEAK** — BENSON scria, nu
  // vorbea. Motivul: functia asta actualiza DOAR bula si nu chema niciodata `speak()`.
  // Comenzile deterministe pareau sa vorbeasca pentru ca ele trec prin alt drum
  // (`response != null` din handleNativeCommandFlow), care roste separat.
  // Acelasi defect facea mute si confirmarea de navigatie si alegerea din lista de rezultate.
  // Reutilizam `speak()` existent — zero modificare in TTS, doar il chemam de unde lipsea.
  // Revert: scoate linia `speak(...)`.
  // FIX_NO_OPEN_SESSION_AFTER_ANSWER_1 (15.10.2026) — cerinta Masterului: „o sesiune activă nu
  // trebuie să rămână deschisă la nesfârșit după ce BENSON ți-a răspuns. Altfel, televizorul poate
  // fi interpretat ca următoarea ta replică." + „Îl chemi, discuți, execută, apoi TACE. Nu se bagă
  // niciodată singur în discuție." (doctrina 5: tăcerea e implicită).
  // Codul deschidea o sesiune de 8 s DUPA fiecare raspuns, deci BENSON rămânea „pe receptie"
  // nechemat. Acum sesiunea se deschide DOAR cand BENSON a pus o INTREBARE si chiar asteapta
  // raspuns (`hasAnyPendingNativeState()`); dupa un raspuns final se intoarce direct la wake.
  // Revert: `openSession("native_cmd_executed", mayDuck = true)` neconditionat.
  private fun finishBrainCommandSuccess(resp: String, originalCommand: String, rearmReason: String) {
    if (hasAnyPendingNativeState()) {
      openSession("native_cmd_executed", mayDuck = true)
    } else {
      AudioDiag.log(this, "SESSION", "not_opened reason=final_answer_benson_stays_silent")
    }
    setMicOwner("NONE", "native_cmd_executed")
    updateBubbleNative(resp, originalCommand, terminal = true, dismissDelayMs = BUBBLE_LINGER_MS)
    if (resp.isNotBlank()) speak(resp, "chat")
    hideWakeRingNative()
    rearmWakeNative(rearmReason)
  }

  // FIX_LIVE_CONVERSATION_CONTINUES_1 (20.10.2026) — perechea lui finishBrainCommandSuccess pentru
  // cazul în care agentul NU a terminat: tocmai a pus o întrebare sau a invitat la continuare, deci
  // sesiunea rămâne deschisă (NU se eliberează micOwner, NU se ascunde inelul, NU se rearmează
  // wake-ul aici) — exact simetric cu orice pendingXxx determinist. `agentAwaitingReply` (setat de
  // apelant ÎNAINTE de asta) face ca `continueSessionAfterUtterance`, chemat din onDone după ce
  // rostirea asta chiar se termină, să deschidă singur o nouă captură (startFollowUpCapture), fără
  // alt "Benson". Vezi hasAnyPendingNativeState.
  private fun continueAgentConversation(resp: String, originalCommand: String) {
    openSession("agent_awaits_reply", mayDuck = true)
    updateBubbleNative(resp, originalCommand, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
    if (resp.isNotBlank()) speak(resp, "chat")
  }

  // Fiecare ramură reutilizează exact funcția nativă deja existentă (vezi comentariul de mai sus);
  // zero logică de execuție nouă aici, doar reconstrucție de frază + rutare spre ea.
  private fun executeBrainCommand(action: String?, result: JSONObject, originalCommand: String): Boolean {
    // FIX_HISTORY_SEPARATION_1 — notele de ACȚIUNE nu mai intră în conversație. Înainte scriau
    // `[Am deschis X]` ca replică de ASISTENT, iar modelul le citea ca spuse Masterului; combinat
    // cu istoricul care nu se golea niciodată, de aici venea amestecul Waze/Maps/radio într-un
    // răspuns despre automobile. Vezi ActionHistory.kt pentru dovada completă.
    fun remember(note: String) { actionHistory.add(note) }
    return when (action) {
      "BACK" -> {
        val ok = expo.modules.accessibility.BensonAccessibilityService.instance?.goBack() ?: false
        if (ok) { remember("[Am ieșit din ecranul curent]"); finishBrainCommandSuccess("Am ieșit.", originalCommand, "brain_back_done") }
        ok
      }
      "HOME" -> {
        val ok = expo.modules.accessibility.BensonAccessibilityService.instance?.goHome() ?: false
        if (ok) { remember("[Am mers la ecranul principal]"); finishBrainCommandSuccess("Gata.", originalCommand, "brain_home_done") }
        ok
      }
      "OPEN_APP" -> {
        val app = result.optString("app").takeIf { it.isNotBlank() } ?: return false
        if (!BRAIN_OPEN_APP_CONFIRM) {
          val resp = tryNativeOpenApp("deschide $app") ?: return false
          remember("[Am deschis $app]")
          finishBrainCommandSuccess(resp, originalCommand, "app_launch_done")
          return true
        }
        // FIX_BRAIN_OPEN_APP_GATE_1 — creierul PROPUNE. Rezolvam pachetul, dar nu lansam nimic
        // pana la "da". Nerezolvat => false, adica exact fallback-ul de pana acum.
        val pkg = findLaunchablePackage(normalizeForMatch(app)) ?: run {
          AudioDiag.log(this, "APP_LAUNCH", "pkg=none ok=false reason=not_found origin=brain")
          return false
        }
        val label = AppNameSynonyms.canonicalName(normalizeForMatch(app), cmdLanguage()) ?: try {
          packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { app }
        logRoute("NATIVE_ROUTE", "action=brain_open_app_confirm target=\"$label\"")
        pendingAppOpen = PendingAppOpen(label, pkg)
        val question = "Deschid $label?"
        remember("[Am propus deschiderea $label — aștept confirmare]")
        updateBubbleNative(question, originalCommand, terminal = false, dismissDelayMs = BUBBLE_LINGER_MS)
        speak(question)
        true
      }
      "SEARCH_IN_APP" -> {
        val query = result.optString("query").takeIf { it.isNotBlank() } ?: return false
        val phrase = "caută $query pe youtube"
        if (tryNativeYoutubeOpenSearch(phrase)) { remember("[Caut $query pe YouTube]"); return true }
        if (tryYoutubeSearchConfirm(phrase)) { remember("[Caut $query pe YouTube]"); return true }
        val resp = tryNativeYoutube(phrase) ?: return false
        remember("[Caut $query pe YouTube]")
        finishBrainCommandSuccess(resp, originalCommand, "app_launch_done")
        true
      }
      "NAVIGATE" -> {
        val target = result.optString("target").takeIf { it.isNotBlank() } ?: return false
        // FIX_NATIVE_NAVIGATE_1 — era `tryGlobalNav(...)`, care nu putea sa potriveasca niciodata
        // "du-mă la X" (tiparul ei e ^(inchide|iesi|acasa|inapoi)$), deci ramura asta era cod mort.
        if (!tryNativeNavigate("du-ma la $target")) return false
        remember("[Navigare spre $target — aștept confirmare]")
        true
      }
      "WHATSAPP_CALL" -> {
        val contact = result.optString("contact").takeIf { it.isNotBlank() } ?: return false
        val ok = tryNativeWhatsApp("sună la $contact pe whatsapp")
        if (ok) remember("[Apel WhatsApp către $contact — confirmare în curs]")
        ok
      }
      "WHATSAPP_VIDEO" -> {
        val contact = result.optString("contact").takeIf { it.isNotBlank() } ?: return false
        val ok = tryNativeWhatsApp("video cu $contact")
        if (ok) remember("[Video WhatsApp către $contact — confirmare în curs]")
        ok
      }
      "WHATSAPP_MESSAGE" -> {
        val contact = result.optString("contact").takeIf { it.isNotBlank() } ?: return false
        val message = result.optString("message").takeIf { it.isNotBlank() } ?: return false
        val ok = tryNativeWhatsApp("scrie-i lui $contact că $message")
        if (ok) remember("[Mesaj WhatsApp către $contact — confirmare în curs]")
        ok
      }
      "MEDIA_CONTROL" -> {
        val mediaAction = result.optString("media_action").takeIf { it.isNotBlank() } ?: return false
        val phrase = when (mediaAction) {
          "play" -> "pune play"
          "pause", "stop" -> "oprește muzica"
          "next" -> "piesa următoare"
          "previous" -> "piesa anterioară"
          else -> return false
        }
        val resp = tryNativeMediaControl(phrase) ?: return false
        remember("[Control media: $mediaAction]")
        finishBrainCommandSuccess(resp, originalCommand, "media_ctl_done")
        true
      }
      else -> false
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

  // V4a (08.10.2026) — flag + revert (G2). Shadow-only: runs on EVERY transcript, logs, executes
  // nothing. Revert: V4_SHADOW = false.
  // OPRIT 15.10.2026, pe cifre, la întrebarea Masterului „plătesc resurse aiurea?".
  // Interpretorul-umbră face un apel LLM SUPLIMENTAR la FIECARE comandă — inclusiv la fiecare
  // comandă injectată de suita de regresie — doar ca să compare vechiul parser cu o clasificare
  // nouă. Măsurat azi, într-o singură rulare G3: **9 apeluri, 0,0023 USD**, iar acordul cu
  // parserul vechi era **3 din 10**. Peste ~20 de rulări înseamnă ~0,046 USD, adică ~27% din
  // cheltuiala zilei, pe un experiment care nu produce nimic în producție.
  // În plus adăuga latență și concura pe aceeași rețea cu creierul (văzut: `INTERP_ERROR
  // type=InterruptedIOException` în timp ce agentul aștepta răspuns).
  // Revert: V4_SHADOW = true.
  private val V4_SHADOW = false
  // INTEGRATION-1 (13.10.2026) — 6s → 10s: demonstrat azi (test live, 3 apeluri reale) — 2 din 3
  // au picat exact la 6003ms (InterruptedIOException), al treilea a reusit la 5263ms, aproape de
  // limita. Marja mica, justificata de date reale, nu o presupunere. Revert: 6 secunde la loc.
  private val interpHttpClient = OkHttpClient.Builder()
    .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

  private val INTERP_SCHEMA = """
    {"type":"object","properties":{
      "action":{"type":"string","enum":["WA_CALL","WA_VIDEO","WA_MESSAGE","APP_OPEN","YT_SEARCH","MEDIA_STOP","CLARIFY","CHAT","UNKNOWN"]},
      "contact":{"type":["string","null"]},"message":{"type":["string","null"]},"app":{"type":["string","null"]},
      "query":{"type":["string","null"]},"question":{"type":["string","null"]}},
     "required":["action","contact","message","app","query","question"],"additionalProperties":false}
  """.trimIndent()

  private val INTERP_SYSTEM_PROMPT = "Ești interpretorul BENSON. Clasifici o comandă rostită " +
    "(română/germană/engleză) într-un singur obiect JSON, exact schema dată. action: WA_CALL/" +
    "WA_VIDEO (contact=numele persoanei), WA_MESSAGE (contact+message), APP_OPEN (app=numele " +
    "aplicației), YT_SEARCH (query=ce se caută), MEDIA_STOP (fără alte câmpuri), CLARIFY " +
    "(question=o întrebare scurtă, dacă nu e clar ce vrea), CHAT (conversație liberă, întrebare " +
    "sau afirmație fără acțiune de executat), UNKNOWN (nimic din astea/comandă fără sens). " +
    "Câmpurile nefolosite = null."

  // V4 — real usage from the chat/completions response (unlike V3's STT estimate: this endpoint
  // DOES return a `usage` field with real token counts).
  private fun interpretCommand(transcript: String): JSONObject? {
    if (transcript.isBlank()) return null
    if (OpenAiUsageMeter.isBudgetExceeded(this)) { AudioDiag.log(this, "INTERP_SKIPPED", "reason=budget_exceeded"); return null }
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("brain_api_key", "") ?: ""
    if (apiKey.isBlank()) return null
    val baseUrl = (prefs.getString("brain_base_url", "")?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").trimEnd('/')
    val body = JSONObject().apply {
      put("model", "gpt-5-nano")
      put(
        "messages",
        org.json.JSONArray().apply {
          put(JSONObject().apply { put("role", "system"); put("content", INTERP_SYSTEM_PROMPT) })
          put(JSONObject().apply { put("role", "user"); put("content", transcript) })
        },
      )
      put(
        "response_format",
        JSONObject().apply {
          put("type", "json_schema")
          put(
            "json_schema",
            JSONObject().apply {
              put("name", "benson_intent"); put("strict", true); put("schema", JSONObject(INTERP_SCHEMA))
            },
          )
        },
      )
    }
    val request = Request.Builder().url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    return try {
      interpHttpClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) { AudioDiag.logError("INTERP_ERROR", "http=${resp.code}"); return null }
        val json = resp.body?.string() ?: return null
        val root = JSONObject(json)
        val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        val usage = root.optJSONObject("usage")
        if (usage != null) {
          val inTok = usage.optInt("prompt_tokens", 0)
          val outTok = usage.optInt("completion_tokens", 0)
          val cost = (inTok / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_INPUT_PER_1M_USD +
            (outTok / 1_000_000.0) * OpenAiUsageMeter.GPT5_NANO_OUTPUT_PER_1M_USD
          OpenAiUsageMeter.recordUsage(this, "interp_gpt5nano", cost)
        }
        if (content.isNullOrBlank()) return null
        JSONObject(content)
      }
    } catch (e: Exception) {
      AudioDiag.logError("INTERP_ERROR", "type=${e.javaClass.simpleName}")
      null
    }
  }

  // V4a — maps the old parser's many internal action names onto the interpreter's enum, for a
  // meaningful match= comparison (not a raw string compare — the vocabularies differ on purpose,
  // old names are internal routing detail, new ones are the public enum from the spec).
  private fun normalizeOldAction(old: String): String = when (old) {
    "open_app" -> "APP_OPEN"
    "yt_search", "yt_open_and_search", "youtube_search_confirm", "context_search" -> "YT_SEARCH"
    "media_ctl", "music_play" -> "MEDIA_STOP"
    "wa_call" -> "WA_CALL"
    "wa_video" -> "WA_VIDEO"
    "wa_message" -> "WA_MESSAGE"
    "native_fallback", "js_handoff", "none" -> "UNKNOWN"
    else -> old.uppercase()
  }

  // INTEGRATION-2 (13.10.2026) — V5/CHAT_SYSTEM_PROMPT/generateChatReply/respondToChat
  // (doua apeluri secventiale: clasificare separata de raspuns) inlocuite de brainTurn/
  // tryBrainFallback/dispatchBrainTurn de mai sus (un singur apel). conversationHistory
  // mutat acolo. Nimic de pastrat aici — comportamentul vechi nu era oricum apelat live
  // pana la INTEGRATION-1 (azi).

  // E0.2/E0.3 (08.10.2026, self-echo) — dropped BEFORE handleNativeCommandFlow, so a pending
  // confirmation's own classifyConfirmationNative never even sees this transcript. This is the
  // actual fix for the original bug: BENSON's own fallback text contains "nu" as a bare word,
  // which the existing NO_WORDS classifier would otherwise accept as a real decline. Not dropped
  // silently — SELF_ECHO_DROPPED logs the similarity, and capture re-arms so a genuine pending
  // confirmation/question keeps its real chance to be answered. Shared by the real capture path
  // (startFollowUpCapture's onEnd) and DEBUG_INJECT_TRANSCRIPT (debug-only, P4.5-style hook —
  // DEBUG_INJECT itself calls handleNativeCommandFlow directly, bypassing this check entirely,
  // which is exactly why a separate hook exists for testing E0 deterministically).
  private fun dispatchFollowUpTranscript(transcript: String?) {
    val echo = if (E0_SELF_ECHO && !transcript.isNullOrBlank()) isSelfEchoText(transcript) else (false to 0.0)
    if (echo.first) {
      AudioDiag.log(this, "SELF_ECHO_DROPPED", "sim=${"%.2f".format(echo.second)} text=\"$transcript\"")
      mainHandler.post { startFollowUpCapture() }
    } else {
      mainHandler.post {
        authorizeTurn("follow_up")
        handleNativeCommandFlow(transcript ?: "", isFollowUp = true, originalPhrase = "", originalWavPath = null)
      }
      if (V4_SHADOW && !transcript.isNullOrBlank()) triggerShadowInterpretation(transcript)
    }
  }

  // V4a (08.10.2026) — shadow only: the old parser has already routed/executed by the time this is
  // called (caller posts that to mainHandler first), unmodified. Runs independently, in parallel,
  // and NEVER touches pendingWaAction or any executor. The 2s delay gives the old parser's own
  // logRoute() call (sync for simple actions, async for WA via runOnServiceScope) time to land
  // before comparing old= here.
  private fun triggerShadowInterpretation(transcript: String) {
    val epochAtDispatch = lastOldParserAction
    Thread({
      val interp = interpretCommand(transcript)
      mainHandler.postDelayed({
        val newAction = interp?.optString("action") ?: "call_failed"
        val oldNorm = normalizeOldAction(if (lastOldParserAction != epochAtDispatch) lastOldParserAction else "none")
        val match = oldNorm == newAction
        AudioDiag.log(this, "INTERP_SHADOW", "old=$oldNorm new=$newAction match=$match text=\"$transcript\"")
      }, 2000)
    }, "benson-interp-shadow").start()
  }

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
          dispatchFollowUpTranscript(transcript)
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

  // P4 (08.10.2026, ZIUA v3) — debug-only override so the regression harness can exercise this
  // state machine without a real telephony/VoIP call (P4.5). Never set by production code.
  // null = use the real AudioManager.mode query below.
  @Volatile private var debugForcedCallActive: Boolean? = null

  private fun isCallAudioActive(): Boolean {
    debugForcedCallActive?.let { return it }
    val mode = try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode } catch (_: Exception) { AudioManager.MODE_NORMAL }
    val waState = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      .getString("wa_call_lifecycle_state", "IDLE")
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
      waState == "CALL_VERIFIED_ACTIVE" || waState == "CALL_ENDING_PENDING"
  }
  fun isCallAudioBlockedNow(): Boolean = callAudioBlocked || isCallAudioActive()

  // P4 — flag + revert (G2). false = comportamentul dinainte de azi (startCallMicProbe activ,
  // reluare wake imediată, fără stabilitate de 1500ms). Revert: AC2F_P4_MIC_YIELD = false.
  private val AC2F_P4_MIC_YIELD = true
  private val MIC_RESUME_STABLE_MS = 1_500L
  private val MIC_RESUME_RETRY_MS = 1_000L
  private val MIC_RESUME_MAX_RETRIES = 3
  @Volatile private var micResumeCheckEpoch = 0

  private fun refreshCallAudioState(reason: String) {
    val active = isCallAudioActive()
    if (active == callAudioBlocked) return
    callAudioBlocked = active
    micResumeCheckEpoch += 1 // invalidates any in-flight resume-stability/retry chain below
    val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
    if (active) {
      AudioDiag.log(this, "CALL_AUDIO_GUARD", "state=active reason=$reason audioMode=${try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode } catch (_: Exception) { -1 }}")
      prefs.edit().putBoolean("call_audio_active", true).apply()
      pendingWakeCommand.getAndSet(null)
      pendingWakeAudioFile.getAndSet(null)
      pendingWakeCommandEpoch += 1
      suspendNativeWake("call_active")
      AudioDiag.log(this, "MIC_YIELD", "reason=$reason")
      try { confirmationListener?.stop() } catch (_: Exception) {}
      confirmationListener = null
      setMicOwner("CALL", "call_audio_active")
      // P4.3 — "zero AudioRecord.startRecording, zero sondare" cât durează apelul: mic-probe-ul de
      // măsurare RMS (RUNDA CAR-1, 2026-10-04) deschidea propriul AudioRecord o dată pe secundă pe
      // toată durata apelului, strict diagnostic — intră direct în contradicție cu cerința de azi.
      // Dezactivat sub flag; codul rămâne, pentru revert.
      if (!AC2F_P4_MIC_YIELD) startCallMicProbe()
    } else {
      AudioDiag.log(this, "CALL_AUDIO_GUARD", "state=ended reason=$reason")
      prefs.edit().putBoolean("call_audio_active", false).apply()
      if (micOwner == "CALL") setMicOwner("NONE", "call_audio_ended")
      stopCallMicProbe()
      if (AC2F_P4_MIC_YIELD) {
        scheduleMicResumeAfterStability(System.currentTimeMillis(), micResumeCheckEpoch)
      } else {
        armNativeWake("call_audio_ended")
      }
    }
    onCallAudioStateChanged?.invoke(active)
  }

  // P4.4 — mode NORMAL stabil ≥1500ms înainte de reluare (evită flapping-ul in-call/post-call);
  // P4.4 — eșec → 3 reîncercări la 1s, apoi WAKE_RESUME_FAILED + notificare discretă, fără TTS.
  private fun scheduleMicResumeAfterStability(endedAt: Long, epoch: Int, attempt: Int = 0) {
    mainHandler.postDelayed({
      if (epoch != micResumeCheckEpoch) return@postDelayed // a new state transition superseded this chain
      if (isCallAudioActive()) return@postDelayed // flapped back into a call — refreshCallAudioState will re-drive this
      armNativeWake("call_audio_ended")
      val resumed = heedWakeWord?.isRunning() == true || microWakeWord?.isRunning() == true
      if (resumed) {
        AudioDiag.log(this, "MIC_RESUME", "afterMs=${System.currentTimeMillis() - endedAt} attempt=$attempt")
      } else if (attempt < MIC_RESUME_MAX_RETRIES) {
        mainHandler.postDelayed({
          if (epoch != micResumeCheckEpoch) return@postDelayed
          scheduleMicResumeAfterStability(endedAt, epoch, attempt + 1)
        }, MIC_RESUME_RETRY_MS)
      } else {
        AudioDiag.log(this, "WAKE_RESUME_FAILED", "attempts=$attempt")
        notifyWakeResumeFailedDiscreetly()
      }
    }, if (attempt == 0) MIC_RESUME_STABLE_MS else 0L)
  }

  // Discrete = notification only, never TTS (P4.4's own wording) — the mic is presumably still in
  // a bad state, so speaking over it would be exactly the kind of unreliable native speech this
  // whole round is trying to avoid depending on.
  private fun notifyWakeResumeFailedDiscreetly() {
    try {
      val manager = getSystemService(NotificationManager::class.java)
      if (manager.getNotificationChannel(CHANNEL_ID) == null) return
      val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("BENSON")
        .setContentText("Ascultarea nu a repornit după apel — atinge pentru a relua.")
        .setSmallIcon(applicationInfo.icon)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(
          PendingIntent.getService(
            this, 3, Intent(this, BensonForegroundService::class.java).apply { action = ACTION_REVIVE },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
          ),
        )
        .build()
      manager.notify(4273, notification)
    } catch (_: Exception) {}
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
  // LEGEA PERSONALULUI, M3 — miscari de personal. Zero anunt rostit: doar registrul se actualizeaza.
  private val staffChangeReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (!APP_REGISTRY) return
      val ctx = context ?: return
      val action = intent?.action ?: return
      StaffRegistry.invalidate()
      AudioDiag.log(ctx, "REGISTRY_EVENT", "kind=pkg_broadcast action=${action.substringAfterLast('.')}")
      // Enumerarea a ~850 de pachete nu se face pe thread-ul de broadcast.
      Thread({
        try { StaffRegistry.bootstrapAndReconcile(ctx, "pkg_event") } catch (_: Exception) {}
      }, "benson-staff-registry").start()
    }
  }

  private val debugInjectReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      val prefs = getSharedPreferences("benson_watchdog_prefs", Context.MODE_PRIVATE)
      when (intent?.action) {
        "com.benson.butler.DEBUG_INJECT_ENABLE" -> {
          val enabled = intent.getBooleanExtra("enabled", false)
          // LEGEA TACERII, partea a doua: steagul de test era PERSISTENT, deci un harness uitat
          // pornit putea face BENSON sa execute si sa vorbeasca zile mai tarziu. Acum are
          // marca de timp si expira; suita de regresie il reporneste oricum la fiecare rulare.
          prefs.edit()
            .putBoolean("debug_inject_enabled", enabled)
            .putLong("debug_inject_enabled_at", if (enabled) System.currentTimeMillis() else 0L)
            .apply()
          AudioDiag.log(context, "DEBUG_INJECT", "action=set_enabled value=$enabled")
        }
        "com.benson.butler.DEBUG_INJECT" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val text = intent.getStringExtra("text")
          if (text.isNullOrBlank()) return
          AudioDiag.log(context, "DEBUG_INJECT", "action=run text=\"$text\"")
          mainHandler.post {
            authorizeTurn("debug_inject")
            handleNativeCommandFlow(text, isFollowUp = true, originalPhrase = "DEBUG_INJECT", originalWavPath = null)
          }
          if (V4_SHADOW) triggerShadowInterpretation(text)
        }
        // V3-VERIFICARE (08.10.2026) — debug-only, injects a REAL WAV file (synthesized speech,
        // pushed to the device beforehand) directly into nativeTranscribe — the actual STT call
        // (OpenAI first, Deepgram/Groq reserve) hits the real network, unlike DEBUG_INJECT_TRANSCRIPT
        // which skips STT entirely. Then dispatches through the same echo-check/command path a real
        // capture would use, so a recognizable command (e.g. "deschide calculator") can actually execute.
        // FAZA 3 / SCREEN OBSERVATION (debug-only, ca tot restul acestui receiver: nu face nimic
        // fara DEBUG_INJECT_ENABLE). Observa ecranul activ si scrie digest-ul indexat in logcat —
        // ZERO acțiune pe ecran, zero apel LLM, zero cost. Doar proba ca "ochii" vad corect.
        // Revert: sterge aceasta ramura + addAction-ul DEBUG_SCREEN_DIGEST.
        "com.benson.butler.DEBUG_SCREEN_DIGEST" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          Thread({
            val svc = expo.modules.accessibility.BensonAccessibilityService.instance
            val digest = svc?.observeScreen()
            if (digest == null) {
              AudioDiag.log(context, "SCREEN_DIGEST", "ok=false reason=no_accessibility_or_root")
            } else {
              AudioDiag.log(
                context, "SCREEN_DIGEST",
                "ok=true app=${digest.packageName} epoch=${digest.epoch} elements=${digest.elements.size} truncated=${digest.truncated}"
              )
              for (line in digest.render().split('\n')) {
                if (line.isNotBlank()) AudioDiag.log(context, "SCREEN_EL", line)
              }
            }
          }, "benson-screen-digest").start()
        }
        // AKA V1 (20.10.2026, "testeaza fara API platit") — exercita EXACT drumul de productie
        // (pressButtonByLabel + recordUiControlVerifiedLocked, acelasi cod pe care runAgentToolLocked
        // il apeleaza pentru PRESS_BUTTON), fara niciun apel OpenAI — zero cost, pentru validarea
        // locala a ciclului descoperire->operare->verificare->inregistrare, inclusiv dupa un
        // restart real al serviciului (persistenta pe disc, StaffRegistry). Revert: sterge aceasta
        // ramura + addAction-ul DEBUG_AKA_PRESS.
        "com.benson.butler.DEBUG_AKA_PRESS" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val label = intent.getStringExtra("label")
          if (label.isNullOrBlank()) return
          val svc = expo.modules.accessibility.BensonAccessibilityService.instance
          if (svc == null) {
            AudioDiag.log(context, "AKA_DEBUG_PRESS", "ok=false reason=no_accessibility")
            return
          }
          Thread({
            val r = kotlinx.coroutines.runBlocking { svc.pressButtonByLabel(label) }
            AudioDiag.log(
              context, "AKA_DEBUG_PRESS",
              "ok=${r.ok} pkg=${r.packageName} strong=${r.strongEffectEvidence} msg=\"${r.message.take(80)}\"",
            )
            if (r.ok) recordUiControlVerifiedLocked(r.packageName, label, r.strongEffectEvidence)
          }, "benson-aka-debug-press").start()
        }
        // Dump pur al cunostintelor PERSISTATE pentru un pachet — proba directa de persistenta
        // (descoperire -> operare -> verificare -> inregistrare -> restart -> regasire), citita
        // DIN STORAGE, fara sa treaca prin observe_screen/agent. Revert: sterge + DEBUG_AKA_DUMP.
        "com.benson.butler.DEBUG_AKA_DUMP" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val pkg = intent.getStringExtra("package")
          if (pkg.isNullOrBlank()) return
          StaffRegistry.invalidate() // forteaza citirea DE PE DISC, nu din cache-ul din memorie
          val caps = try { StaffRegistry.capsOf(context, pkg) } catch (_: Exception) { emptyMap() }
          val uiControls = caps.filter { StaffRegistryCore.isUiControlCapability(it.key) }
          AudioDiag.log(context, "AKA_DEBUG_DUMP", "pkg=$pkg ui_controls=${uiControls.entries.joinToString(",") { "${it.key}=${it.value}" }}")
          // Punctul 5 ("LIVE consulta efectiv AKA") — EXACT acelasi contract pur
          // (StaffRegistryCore.attachKnownUiControls) pe care OBSERVE_SCREEN il aplica in
          // runAgentToolLocked, exercitat aici pe date REALE, deja persistate pe disc — proba ca
          // textul pe care l-ar primi modelul contine cunostinta, fara o rulare platita a agentului.
          val liveWouldSee = StaffRegistryCore.attachKnownUiControls("(digest ecran omis in acest dump)", caps)
          AudioDiag.log(context, "AKA_DEBUG_LIVE_VIEW", "pkg=$pkg text=\"$liveWouldSee\"")
          // Rezumatul AKA pe toata aplicatia (aka_fn/aka_nav incluse) — EXACT ce ar primi LIVE de la
          // "aka_query_app", citit DIN STORAGE, dovada directa a ciclului DISCOVER->MAP->STORE.
          val member = try { StaffRegistry.dossier(context, pkg) } catch (_: Exception) { null }
          if (member != null) {
            AudioDiag.log(context, "AKA_DEBUG_APP_SUMMARY", "pkg=$pkg text=\"${StaffRegistryCore.formatAppKnowledgeSummary(member)}\"")
          }
        }
        // SURVIVOR etapa 3 (20.10.2026) — exercita EXACT drumul de productie, nu doar executorul
        // de nivel jos: cheama `runAgentToolLocked` direct (aceeasi functie PRIVATA pe care
        // bucla platita a agentului o cheama), deci traverseaza SI logica noua (clasificarea
        // originii, decizia de reincercare, campul retry_unsafe, retrogradarea AKA) — fara niciun
        // apel OpenAI (JSON-ul brut al cererii trece direct, fara retea). Revert: sterge aceasta
        // ramura + addAction DEBUG_SURVIVOR_PRESS.
        "com.benson.butler.DEBUG_SURVIVOR_PRESS" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val label = intent.getStringExtra("label")
          if (label.isNullOrBlank()) return
          Thread({
            val args = JSONObject().put("label", label)
            val (result, _) = runAgentToolLocked(BensonAgentTools.PRESS_BUTTON, args, "debug_survivor")
            AudioDiag.log(context, "SURVIVOR_DEBUG_PRESS", "label=\"${label.take(40)}\" result=$result")
          }, "benson-survivor-debug-press").start()
        }
        // AKA DISCOVER/MAP (20.10.2026) — exercita EXACT drumul de productie al inventarierii
        // automate (OBSERVE_SCREEN din runAgentToolLocked), fara niciun apel OpenAI: observa
        // ecranul, descopera functiile candidate, le inregistreaza ca KNOWN daca sunt noi. Revert:
        // sterge aceasta ramura + addAction DEBUG_AKA_DISCOVER.
        "com.benson.butler.DEBUG_AKA_DISCOVER" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val svc = expo.modules.accessibility.BensonAccessibilityService.instance
          if (svc == null) {
            AudioDiag.log(context, "AKA_DEBUG_DISCOVER", "ok=false reason=no_accessibility")
            return
          }
          Thread({
            val r = svc.akaDiscoverScreen()
            if (r.ok) {
              for (c in r.candidates) {
                try {
                  StaffRegistry.markDiscovered(context, r.packageName, StaffRegistryCore.akaFunctionCapabilityId(r.screenSignature, c.label))
                } catch (_: Exception) {}
              }
            }
            AudioDiag.log(
              context, "AKA_DEBUG_DISCOVER",
              "ok=${r.ok} pkg=${r.packageName} sig=${r.screenSignature} candidates=${r.candidates.size} " +
                "labels=\"${r.candidates.joinToString(",") { it.label }.take(200)}\"",
            )
          }, "benson-aka-debug-discover").start()
        }
        // AKA DISCOVER/MAP (20.10.2026) — exercita EXACT drumul de productie al explorarii active
        // (AKA_EXPLORE_FUNCTION), fara niciun apel OpenAI — apasa DOAR controale clasificate FREE,
        // niciodata campuri editabile sau cu risc (vezi akaExploreByPressing). Revert: sterge +
        // addAction DEBUG_AKA_EXPLORE.
        "com.benson.butler.DEBUG_AKA_EXPLORE" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val label = intent.getStringExtra("label")
          if (label.isNullOrBlank()) return
          val svc = expo.modules.accessibility.BensonAccessibilityService.instance
          if (svc == null) {
            AudioDiag.log(context, "AKA_DEBUG_EXPLORE", "ok=false reason=no_accessibility")
            return
          }
          Thread({
            val r = kotlinx.coroutines.runBlocking { svc.akaExploreByPressing(label) }
            if (r.ok && r.transitionVerified) {
              val pressed = r.pressedLabel
              val toSig = r.toSignature
              if (pressed != null && toSig != null) {
                try {
                  StaffRegistry.markVerified(context, r.packageName, StaffRegistryCore.akaFunctionCapabilityId(r.screenSignature, pressed))
                  StaffRegistry.markVerified(context, r.packageName, StaffRegistryCore.akaNavigationCapabilityId(r.screenSignature, pressed, toSig))
                } catch (_: Exception) {}
              }
            }
            AudioDiag.log(
              context, "AKA_DEBUG_EXPLORE",
              "ok=${r.ok} pkg=${r.packageName} from=${r.screenSignature} to=${r.toSignature} " +
                "verified=${r.transitionVerified} msg=\"${r.message.take(100)}\"",
            )
          }, "benson-aka-debug-explore").start()
        }
        "com.benson.butler.DEBUG_INJECT_WAV" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val path = intent.getStringExtra("path")
          if (path.isNullOrBlank() || !File(path).isFile) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=wav_rejected reason=file_missing path=$path")
            return
          }
          AudioDiag.log(context, "DEBUG_INJECT", "action=wav path=$path")
          Thread({
            val startedAt = System.currentTimeMillis()
            val transcript = nativeTranscribe(path)
            AudioDiag.log(context, "DEBUG_INJECT", "action=wav_done latencyMs=${System.currentTimeMillis() - startedAt} text=\"${transcript ?: ""}\"")
            dispatchFollowUpTranscript(transcript)
          }, "benson-debug-wav").start()
        }
        // E0 (08.10.2026) — debug-only, feeds text through dispatchFollowUpTranscript (the SAME
        // echo-check + dispatch real capture results go through), unlike plain DEBUG_INJECT which
        // calls handleNativeCommandFlow directly and bypasses the self-echo filter entirely. Only
        // way to test E0 deterministically without real acoustic feedback in the room.
        "com.benson.butler.DEBUG_INJECT_TRANSCRIPT" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val text = intent.getStringExtra("text")
          if (text.isNullOrBlank()) return
          AudioDiag.log(context, "DEBUG_INJECT", "action=transcript text=\"$text\"")
          dispatchFollowUpTranscript(text)
        }
        // P4.5 (08.10.2026) — debug-only, forces isCallAudioActive()'s return value so the real
        // state machine (refreshCallAudioState/suspendNativeWake/scheduleMicResumeAfterStability)
        // can be exercised without a real telephony/VoIP call. Never reachable in release (same
        // debug_inject_enabled gate as every other DEBUG_INJECT_* action). "clear" extra removes
        // the override, falling back to the real AudioManager.mode query.
        "com.benson.butler.DEBUG_INJECT_CALL_MODE" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val clear = intent.getBooleanExtra("clear", false)
          val active = intent.getBooleanExtra("active", false)
          debugForcedCallActive = if (clear) null else active
          AudioDiag.log(context, "DEBUG_INJECT", "action=call_mode forced=${debugForcedCallActive?.toString() ?: "cleared"}")
          mainHandler.post { refreshCallAudioState("debug_forced") }
        }
        // ZIUA 2026-10-08, G3 — simulates a confirmation reply without real microphone audio, so
        // the regression suite can exercise "Nu" → zero acțiune on a timed, flaky mic-free path.
        // Takes the literal words (e.g. "nu"), not a verdict label: app/index.tsx's
        // confirmResultSub re-classifies the TRANSCRIPT itself via classifyConfirmation(), not the
        // native `verdict` field (that only gates the self-echo guard) — passing a bare verdict
        // here would be silently ignored downstream. NativeConfirmationListener.classify() is the
        // same classifier a real capture uses, so verdict/transcript stay consistent with a real one.
        "com.benson.butler.DEBUG_INJECT_CONFIRM" -> {
          if (!isDebugInjectArmed(prefs)) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=rejected reason=disabled")
            return
          }
          val text = intent.getStringExtra("text")
          val id = lastConfirmationId
          if (text.isNullOrBlank() || id == null) {
            AudioDiag.log(context, "DEBUG_INJECT", "action=confirm_rejected reason=no_pending_confirmation")
            return
          }
          val verdict = NativeConfirmationListener.classify(text)
          AudioDiag.log(context, "DEBUG_INJECT", "action=confirm verdict=$verdict text=\"$text\" confirmationId=$id")
          mainHandler.post {
            confirmationListener?.stop()
            confirmationListener = null
            deliverConfirmationResult(id, verdict, text)
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
    // PASUL 2a (07.10.2026) — correlates with FGS_START in BensonWatchdogReceiver/BensonHealthWorker:
    // was ROLE_ASSISTANT actually held at the moment this service (re)started.
    val roleHeld = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      try { getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) ?: false } catch (_: Exception) { false }
    } else false
    AudioDiag.log(this, "ASSISTANT_ROLE", "held=$roleHeld")
    logProcessStartAndCountKill()
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
          logRoute("NATIVE_ROUTE", "action=headset_short_press")
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
    // LEGEA PERSONALULUI BENSON, M1/M3 — registrul porneste cu motorul, la fiecare start de
    // serviciu (deci si automat la prima instalare pe orice telefon, imediat ce serviciul
    // porneste dupa acordarea permisiunilor din onboarding). Reconcilierea completa de la start
    // prinde si ce s-a schimbat cat BENSON a fost oprit. Pe thread separat: enumerarea a ~850 de
    // pachete nu are ce sa caute pe main la onCreate.
    if (APP_REGISTRY) {
      try {
        registerReceiver(staffChangeReceiver, android.content.IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addDataScheme("package")
        })
      } catch (_: Exception) {}
      Thread({
        try { StaffRegistry.bootstrapAndReconcile(applicationContext, "service_start") } catch (_: Exception) {}
      }, "benson-staff-registry-boot").start()
    }
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
        addAction("com.benson.butler.DEBUG_INJECT_CONFIRM") // ZIUA 2026-10-08, G3
        addAction("com.benson.butler.DEBUG_INJECT_CALL_MODE") // ZIUA 2026-10-08, P4.5
        addAction("com.benson.butler.DEBUG_INJECT_TRANSCRIPT") // ZIUA 2026-10-08, E0
        addAction("com.benson.butler.DEBUG_INJECT_WAV") // ZIUA 2026-10-08, V3-VERIFICARE
        addAction("com.benson.butler.DEBUG_SCREEN_DIGEST") // FAZA 3 / SCREEN OBSERVATION
        addAction("com.benson.butler.DEBUG_AKA_PRESS") // AKA V1, 20.10.2026
        addAction("com.benson.butler.DEBUG_AKA_DUMP") // AKA V1, 20.10.2026
        addAction("com.benson.butler.DEBUG_SURVIVOR_PRESS") // SURVIVOR etapa 3, 20.10.2026
        addAction("com.benson.butler.DEBUG_AKA_DISCOVER") // AKA DISCOVER/MAP, 20.10.2026
        addAction("com.benson.butler.DEBUG_AKA_EXPLORE") // AKA DISCOVER/MAP, 20.10.2026
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
    try { unregisterReceiver(staffChangeReceiver) } catch (_: Exception) {} // LEGEA PERSONALULUI, M3
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
