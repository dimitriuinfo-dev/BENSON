package expo.modules.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * BENSON — Etapa 10: Accessibility Service
 *
 * Stă peste orice app deschis de Benson (App Launcher, Etapa 6), citește
 * ecranul, poate completa formulare, și trimite un JSON structurat spre
 * BensonAccessibilityModule (care îl emite mai departe către RN / Content Canvas).
 *
 * Reguli respectate:
 * - Nimic nu ajunge în cloud. Snapshot-ul trăiește doar în memorie, pe
 *   durata evenimentului curent.
 * - PAYMENT_BLOCKLIST blochează orice acțiune automată pe noduri legate
 *   de plată — 2FA / plata rămân mereu la user.
 */
class BensonAccessibilityService : AccessibilityService() {

    // Tied to the service's own lifecycle, not the RN Activity's — this is the whole point:
    // ColorOS (and Android background-execution limits generally) throttle a backgrounded app's
    // JS thread almost immediately, but an enabled AccessibilityService keeps running
    // independent of which Activity currently has foreground focus. Every step of
    // placeWhatsAppCall() below runs on this scope, driven by native delay()/rootInActiveWindow
    // polling, never a JS setTimeout.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Generic, asset-backed executor. App-specific flows belong in declarative profiles; this
    // service remains the native runtime that survives JS throttling in the background.
    private val profileAutomationEngine by lazy {
        DeclarativeAutomationEngine(
            service = this,
            returnToBenson = ::returnToBenson,
            isPaymentSensitive = ::isPaymentSensitive,
        )
    }

    // JS-driven, step-by-step command executor (2026-07-17) — the brain (JS) sends an explicit
    // ordered step list, this runs it on serviceScope and stops honestly at the first problem
    // (not_found/ambiguous/blocked/tap_rejected/wrong_package), never guessing. Existing hardcoded
    // flows (placeWhatsAppCall etc.) are NOT replaced yet — this runs in parallel until confirmed
    // to behave the same on a real device.
    private val commandExecutor by lazy {
        BensonCommandExecutor(
            service = this,
            returnToBenson = ::returnToBenson,
            isPaymentSensitive = ::isPaymentSensitive,
        )
    }

    data class WhatsAppCallResult(val success: Boolean, val step: String, val error: String? = null)

    // WA-NATIVE-FINAL contract — one native call runs the whole WhatsApp voice-call sequence.
    data class WhatsAppCallNativeResult(
        val success: Boolean,
        val step: String,      // stage enum: VALIDATE|LAUNCH|PACKAGE|SEARCH_NOT_FOUND|SEARCH_CLICK|
                               // SEARCH_NOT_OPEN|SET_TEXT|TEXT_MISMATCH|CONTACT_NOT_FOUND|CONTACT_CLICK|
                               // CHAT_NOT_OPEN|CHAT_VERIFY|CHAT_WRONG_CONTACT|CALL_NOT_FOUND|CALL_BLOCKED|
                               // CALL_CLICK|CALL_NOT_STARTED|CALL_WRONG_CONTACT|CALL_VERIFIED|EXCEPTION|SERVICE
        val error: String?,
        val contact: String,
        val elapsedMs: Long,
        // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — additive only, both default so every existing call
        // site (constructing this with the original 5-arg shape) still compiles unchanged.
        // Populated ONLY at the exact point a real on-screen header comparison already ran
        // (WA_WRITE_CHAT_VERIFIED / WA_DIRECT_CONVERSATION_VERIFY) — never guessed, never set on
        // an idempotent-reentry shortcut that skipped fresh verification, never on failure/abort.
        val verifiedHeaderText: String? = null,
        val nameMatch: Boolean = false,
    )

    data class AutomationProfileResult(val success: Boolean, val step: String, val error: String? = null)

    suspend fun executeCommand(command: JSONObject): BensonCommandExecutor.CommandResult {
        whatsappAutomationActive = true // suppress Guardian for the duration, same as profiles
        try {
            return commandExecutor.execute(command)
        } finally {
            whatsappAutomationActive = false
        }
    }

    // Lets the Expo module (whose AsyncFunction lambdas are not themselves suspend contexts)
    // launch a suspend call like placeWhatsAppCall() without needing direct access to
    // serviceScope, which stays private to this class.
    fun runOnServiceScope(block: suspend () -> Unit) {
        serviceScope.launch { block() }
    }

    companion object {
        private const val TAG = "BensonA11y"
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        private val PAYMENT_BLOCKLIST = listOf(
            "pay", "plateste", "plătește", "confirm payment", "buy now",
            "cumpara", "cumpără", "checkout", "3d secure", "3-d secure",
            "otp", "cvv", "card number", "numar card", "număr card"
        )

        private val SEARCH_KEYWORDS = listOf("search", "căutare", "cautare", "caută", "cauta", "suche", "suchen")
        private val CALL_KEYWORDS = listOf("voice call", "apel vocal", "apel", "sprachanruf", "anruf", "call")
        private val VIDEO_EXCLUDE_KEYWORDS = listOf("video")
        // WhatsApp UI observed live on this device is German — German keywords listed first
        // since they're the ones actually confirmed to match; EN/RO kept as fallback in case the
        // device language changes.
        private val END_CALL_KEYWORDS = listOf("beenden", "auflegen", "anruf beenden", "end call", "hang up", "închide apelul")
        private val MUTE_KEYWORDS = listOf("stumm", "stummschalten", "mikrofon", "mute", "microfon")
        // Same multi-language idiom as CALL_KEYWORDS — German first since it's the device's
        // confirmed live UI language, EN/RO kept as fallback.
        private val SEND_KEYWORDS = listOf("senden", "send", "trimite", "trimitere")

        @Volatile
        var instance: BensonAccessibilityService? = null
            private set

        // Bumped on every onServiceConnected/onUnbind/onDestroy — lets a caller that cached
        // "service is ready" a moment ago detect it's now talking about a dead generation instead
        // of trusting a stale JS-side boolean. Not persisted; resets to 0 on process restart,
        // which is fine since a fresh process has no stale state to begin with.
        @Volatile
        var connectionEpoch: Int = 0
            private set

        /** Setat de BensonAccessibilityModule.OnCreate, curățat la OnDestroy. */
        var onScreenUpdate: ((String) -> Unit)? = null

        // Fired synchronously on every TYPE_WINDOW_STATE_CHANGED event — works regardless of
        // canRetrieveWindowContent (unlike emitScreenSnapshot/rootInActiveWindow-based reads,
        // which needed that capability re-enabled 2026-07-14 for placeWhatsAppCall). This is the
        // real synchronization point the App Launcher waits on instead of a fixed sleep: it fires the
        // instant Android reports a foreground window change, whether that's BENSON settling
        // into view after bringBensonToForeground() or the target app finally reaching the screen.
        var onForegroundChanged: ((String) -> Unit)? = null

        // Last package seen on a TYPE_WINDOW_STATE_CHANGED event — the closest thing to "what
        // app is in the foreground right now" a non-system app can get without the separate
        // PACKAGE_USAGE_STATS special-access permission. Used by the App Launcher to verify a
        // launch actually brought the target app to front, instead of trusting startActivity()
        // not throwing (Android/OEM background-launch restrictions can silently drop it).
        @Volatile
        var lastForegroundPackage: String? = null
            private set

        // ---------------------------------------------------------------
        // GUARDIAN — resurrection anchor.
        //
        // Insight: an ENABLED AccessibilityService is the most kill-resistant component this app
        // has. When OxygenOS's background killer (OsenseKillAction, confirmed live on-device
        // 2026-07-16 — see FINAL_BUILD_REPORT.md) evicts BENSON's process, the OS itself attempts
        // to rebind this service afterward, which restarts the process to do it — but that fresh
        // process only has THIS service connected: BensonForegroundService (the hotword loop,
        // wake lock, watchdog alarm) and the whole JS/React layer (voice pipeline, TTS) are not
        // automatically restarted by anything. This turns that forced rebind into the trigger that
        // brings the rest of BENSON back, instead of leaving a silent half-alive process.
        //
        // Cross-module note: this file (benson-accessibility) intentionally has no Gradle
        // dependency on benson-foreground-service, matching the existing loose-coupling idiom
        // BensonForegroundService itself already uses to reach benson-overlay (explicit
        // ComponentName intent, not a compiled reference). Liveness is tracked via a shared
        // SharedPreferences heartbeat instead of a direct isRunning check: BensonWatchdogReceiver
        // (in benson-foreground-service, ticking every ~60s via AlarmManager) and
        // BensonForegroundService.onCreate() both touch KEY_LAST_HEARTBEAT whenever the service is
        // confirmed alive. If that heartbeat goes stale, nothing else is running it — safe to
        // resurrect.
        private const val GUARDIAN_PREFS_NAME = "benson_watchdog_prefs"
        private const val KEY_LAST_HEARTBEAT = "last_foreground_heartbeat"
        private const val KEY_USER_STOPPED = "user_stopped"
        private const val KEY_LAST_RESURRECTION_AT = "last_resurrection_attempt_at"
        private const val KEY_RECOVERY_PENDING = "recovery_pending"
        private const val KEY_RECOVERY_EVENTS = "recovery_events"
        private const val HEARTBEAT_STALE_MS = 90_000L
        private const val RESURRECTION_THROTTLE_MS = 60_000L
        // E1-1 / E1-0 (2026-09-07, product-owner-directed): Guardian repornește serviciul foreground
        // (mic + wake word se auto-repară în fundal, tăcut) DAR nu mai aduce Activity-ul BENSON pe
        // ecran singur — nicio aducere în prim-plan fără o rostire recunoscută sau o atingere directă
        // a utilizatorului. "BENSON nu se mai ridică singur." Revert: pune true.
        private const val GUARDIAN_BRING_ACTIVITY_TO_FRONT = false
        private const val FOREGROUND_SERVICE_CLASS = "expo.modules.foregroundservice.BensonForegroundService"
        private const val MAX_RECOVERY_EVENTS = 20

        // Confirmed live (2026-07-17): a WhatsApp automation flow (placeWhatsAppCall / send)
        // deliberately backgrounds BENSON's own Activity for several seconds while it drives
        // WhatsApp's UI — during a long/chaotic session the foreground-service heartbeat can go
        // stale in that same window (e.g. right after a fresh install, before the watchdog alarm
        // has re-registered), and Guardian's bringBensonToForegroundForRecovery() then yanks focus
        // BACK to BENSON mid-flow, so the send-button search runs against BENSON's own screen
        // instead of WhatsApp's. This flag lets an in-progress automation tell Guardian "don't
        // steal focus right now" — the foreground SERVICE restart (which doesn't touch the UI) is
        // still allowed either way, only the Activity-stealing steps are suppressed.
        //
        // Confirmed live (2026-07-17), second occurrence: Guardian also fired ~60s AFTER a
        // successful call had already completed and this flag had already been cleared back to
        // false, snapping BENSON back to the foreground anyway — defeating the whole point of
        // "stay on WhatsApp so the user can see it" the moment the heartbeat next went stale.
        // Every write to this flag also stamps whatsappAutomationLastActiveAt, and Guardian treats
        // "ended less than WHATSAPP_AUTOMATION_COOLDOWN_MS ago" the same as "still active" — the
        // user needs a real window to actually look at WhatsApp, not just protection for the few
        // seconds the automation itself is running.
        @Volatile
        private var whatsappAutomationActiveInternal: Boolean = false

        @Volatile
        private var whatsappAutomationLastActiveAt: Long = 0L
        private const val WHATSAPP_AUTOMATION_COOLDOWN_MS = 120_000L

        var whatsappAutomationActive: Boolean
            get() = whatsappAutomationActiveInternal
            set(value) {
                whatsappAutomationActiveInternal = value
                whatsappAutomationLastActiveAt = System.currentTimeMillis()
            }

        // True while an automation is running OR finished less than WHATSAPP_AUTOMATION_COOLDOWN_MS
        // ago — Guardian treats both the same way (see maybeResurrect below).
        fun isWhatsAppAutomationRecentlyActive(): Boolean {
            if (whatsappAutomationActiveInternal) return true
            return System.currentTimeMillis() - whatsappAutomationLastActiveAt < WHATSAPP_AUTOMATION_COOLDOWN_MS
        }

        // WA-CALL-STAYS-LIVE (2026-09-08, product-owner-directed): the moment runWhatsAppCallNative
        // verifies the WhatsApp call screen is up, it stamps this to now + 180s. BENSON's JS side
        // reads whatsAppCallMicHoldActive() at every mic/wake entry point and, while it's true,
        // does NOT open the mic or restart the wake-word loop — the concurrent mic grab / audio-focus
        // request was ending the fresh WhatsApp call on this device. Source of truth is native (only
        // the native executor actually knows the call started), so EVERY JS trigger path — voice,
        // debug panel, conversation-mode self-heal — is covered by one check. Cleared early by JS the
        // moment BENSON's own Activity is foregrounded again (clearWhatsAppCallMicHold).
        @Volatile
        var whatsappCallMicHoldUntilMs: Long = 0L
        // Held for the WHOLE native call automation (from WA_NATIVE_START, while whatsappAutomationActive
        // is true) AND for 180s after the call is verified live. A run that FAILS clears
        // whatsappAutomationActive in its finally and never stamps the 180s window, so the hold releases
        // at once and BENSON resumes listening — the hold only outlives the automation on success.
        // The call-lifecycle watcher (watchWhatsAppCallLifecycle) sets this to 0 the moment it
        // structurally verifies the call ended, so the 180s is a failsafe, not the primary release.
        fun whatsAppCallMicHoldActive(): Boolean =
            whatsappAutomationActiveInternal || System.currentTimeMillis() < whatsappCallMicHoldUntilMs

        // AUTO-RETURN-AFTER-CALL (2026-09-08, product-owner-directed): the call-lifecycle watcher
        // sets this true right before it fires the explicit Intent back to BENSON's MainActivity.
        // The JS AppState 'active' handler consumes it on the next foreground transition and re-arms
        // WAKE mode only (never conversation mode / general STT), logging WAKE_MODE_RESTORED.
        @Volatile
        var callEndedReturnPending: Boolean = false
        fun consumeCallEndedReturnPending(): Boolean {
            val v = callEndedReturnPending
            callEndedReturnPending = false
            return v
        }

        // ── WA-LIFECYCLE-FIX-1 (2026-09-09) — restart-recoverable WhatsApp call lifecycle ─────────
        // watchWhatsAppCallLifecycle() runs on serviceScope; when OxygenOS kills/revives the
        // AccessibilityService the coroutine dies mid-call and CALL_ENDED is never committed — the
        // mic hold survives to the 180s watchdog and the wake loop stays dead
        // (ROUND_WA_FIX_4_REPORT.md POST_CALL_WAKE=FAIL). Fix: a *persisted* minimal lifecycle state
        // in benson_watchdog_prefs that a fresh service instance reads in onServiceConnected and
        // finalizes. No conversation content is persisted — only state + timestamps + contact name
        // (already logged everywhere). Values: IDLE | CALL_VERIFIED_ACTIVE | CALL_ENDING_PENDING.
        const val WA_CALL_STATE_IDLE = "IDLE"
        const val WA_CALL_STATE_ACTIVE = "CALL_VERIFIED_ACTIVE"
        const val WA_CALL_STATE_ENDING = "CALL_ENDING_PENDING"
        private const val KEY_WA_CALL_STATE = "wa_call_lifecycle_state"
        private const val KEY_WA_CALL_STARTED_AT = "wa_call_started_at"
        private const val KEY_WA_CALL_UPDATED_AT = "wa_call_updated_at"
        private const val KEY_WA_CALL_CONTACT = "wa_call_contact"
        // Monotonic-ish "a verified call just ended" marker. Survives a process kill (prefs). JS
        // polls it in its self-heal loop and clears the JS mic hold + re-arms wake — independent of
        // any AppState 'active' transition.
        private const val KEY_WA_CALL_ENDED_SIGNAL_AT = "wa_call_ended_signal_at"

        private fun waPrefs(ctx: Context) = ctx.getSharedPreferences(GUARDIAN_PREFS_NAME, Context.MODE_PRIVATE)

        fun waCallPersistState(ctx: Context, state: String, contact: String) {
            val now = System.currentTimeMillis()
            val ed = waPrefs(ctx).edit()
            ed.putString(KEY_WA_CALL_STATE, state)
            ed.putString(KEY_WA_CALL_CONTACT, contact)
            ed.putLong(KEY_WA_CALL_UPDATED_AT, now)
            if (state == WA_CALL_STATE_ACTIVE) ed.putLong(KEY_WA_CALL_STARTED_AT, now)
            if (state == WA_CALL_STATE_IDLE) ed.putLong(KEY_WA_CALL_ENDED_SIGNAL_AT, now)
            ed.apply()
            Log.i("BENSON_AUDIO", "WA_CALL_PERSIST state=$state")
        }

        data class WaCallPersisted(val state: String, val startedAt: Long, val updatedAt: Long, val contact: String)
        fun waCallReadState(ctx: Context): WaCallPersisted {
            val p = waPrefs(ctx)
            return WaCallPersisted(
                p.getString(KEY_WA_CALL_STATE, WA_CALL_STATE_IDLE) ?: WA_CALL_STATE_IDLE,
                p.getLong(KEY_WA_CALL_STARTED_AT, 0L),
                p.getLong(KEY_WA_CALL_UPDATED_AT, 0L),
                p.getString(KEY_WA_CALL_CONTACT, "") ?: "",
            )
        }

        // JS bridge — the persisted "call just ended" timestamp; 0 = none. JS keeps its own
        // last-handled value and acts when this is newer.
        fun getWhatsAppCallEndedSignalAt(): Long =
            instance?.let { waPrefs(it).getLong(KEY_WA_CALL_ENDED_SIGNAL_AT, 0L) } ?: 0L

        // ── ROUND_WA_GOVERNANCE_WRITE_1 — persisted idempotency for a WhatsApp message write.
        // A single pending write at a time, keyed by the JS mission id. State machine:
        // NOT_TYPED → TYPED_VERIFIED → WAITING_CONFIRMATION → SEND_ATTEMPTED → SENT_VERIFIED.
        // SEND is pressed at most once per mission id: SEND_ATTEMPTED is written BEFORE the tap,
        // so a crash/interruption after it can only re-VERIFY, never re-press. Nothing of the
        // message body is stored — only its hashCode(), to detect a changed payload.
        const val WA_WRITE_NOT_TYPED = "NOT_TYPED"
        const val WA_WRITE_TYPED_VERIFIED = "TYPED_VERIFIED"
        const val WA_WRITE_WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
        const val WA_WRITE_SEND_ATTEMPTED = "SEND_ATTEMPTED"
        const val WA_WRITE_SENT_VERIFIED = "SENT_VERIFIED"
        private const val KEY_WA_WRITE_MISSION = "wa_write_mission_id"
        private const val KEY_WA_WRITE_STATE = "wa_write_state"
        private const val KEY_WA_WRITE_MSG_HASH = "wa_write_msg_hash"
        private const val KEY_WA_WRITE_UPDATED_AT = "wa_write_updated_at"

        fun waWritePersist(ctx: Context, missionId: String, state: String, msgHash: Int) {
            val ed = waPrefs(ctx).edit()
            ed.putString(KEY_WA_WRITE_MISSION, missionId)
            ed.putString(KEY_WA_WRITE_STATE, state)
            ed.putInt(KEY_WA_WRITE_MSG_HASH, msgHash)
            ed.putLong(KEY_WA_WRITE_UPDATED_AT, System.currentTimeMillis())
            ed.apply()
            Log.i("BENSON_AUDIO", "WA_WRITE_STATE mission=$missionId state=$state")
        }

        data class WaWritePersisted(val missionId: String, val state: String, val msgHash: Int, val updatedAt: Long)
        fun waWriteRead(ctx: Context): WaWritePersisted {
            val p = waPrefs(ctx)
            return WaWritePersisted(
                p.getString(KEY_WA_WRITE_MISSION, "") ?: "",
                p.getString(KEY_WA_WRITE_STATE, WA_WRITE_NOT_TYPED) ?: WA_WRITE_NOT_TYPED,
                p.getInt(KEY_WA_WRITE_MSG_HASH, 0),
                p.getLong(KEY_WA_WRITE_UPDATED_AT, 0L),
            )
        }

        // JS bridge — read the current write state ("<missionId>|<state>" or "|NOT_TYPED").
        fun getWhatsAppWriteState(): String =
            instance?.let { val r = waWriteRead(it); "${r.missionId}|${r.state}" } ?: "|$WA_WRITE_NOT_TYPED"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectionEpoch++
        Log.i(TAG, "Benson accessibility service connected, epoch=$connectionEpoch")
        Log.i("BENSON_AUDIO", "WA_SERVICE_LIFECYCLE event=connected epoch=$connectionEpoch")
        maybeResurrect("onServiceConnected")
        registerAcc1TestReceiver()
        startForegroundStateReassertLoop()
        // WA-LIFECYCLE-FIX-1 — a previous instance may have died mid-call; recover the persisted
        // WhatsApp call lifecycle before anything else can assume the mic hold is stale.
        try { recoverWhatsAppCallLifecycle() } catch (e: Exception) { Log.w(TAG, "recoverWhatsAppCallLifecycle threw: ${e.message}") }
    }

    // ── RUNDA ACC-1 — declanșator de diagnostic, DOAR prin broadcast explicit ─────────────────────
    // `adb shell am broadcast -a com.benson.acc1.RUN [--es calc <package>]` → rulează
    // AccessibilityFoundationTest pe serviceScope. NU e legat de nicio rețetă / feature. Dinamic
    // (fără intrare de manifest). Se dezînregistrează în onDestroy.
    private var acc1Receiver: android.content.BroadcastReceiver? = null
    private fun registerAcc1TestReceiver() {
        if (acc1Receiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.benson.acc1.RUN" -> {
                        val calc = intent.getStringExtra("calc")
                        Log.i("BENSON_AUDIO", "ACC_TEST trigger received calc=${calc ?: "auto"}")
                        serviceScope.launch {
                            try { AccessibilityFoundationTest(this@BensonAccessibilityService).run(calc) }
                            catch (e: Exception) { Log.i("BENSON_AUDIO", "ACC_TEST harness_exception=${e.message}") }
                        }
                    }
                    "com.benson.wafix1.RUN" -> {
                        Log.i("BENSON_AUDIO", "WAFIX1_PROBE trigger received")
                        serviceScope.launch {
                            try { AccessibilityFoundationTest(this@BensonAccessibilityService).runWaFix1Probe() }
                            catch (e: Exception) { Log.i("BENSON_AUDIO", "WAFIX1_PROBE harness_exception=${e.message}") }
                        }
                    }
                    // WA-FIX-1 diagnostic trigger (same pattern as ACC-1): exercises ONLY the new
                    // Chats-state normalisation + search-open helpers, from whatever state WhatsApp
                    // is in now. No typing, no contact match, no call placed.
                    // `adb shell am broadcast -a com.benson.wasearch.RUN`
                    "com.benson.wasearch.RUN" -> {
                        Log.i("BENSON_AUDIO", "WA_SEARCH_SELFTEST trigger received")
                        serviceScope.launch {
                            try {
                                val norm = ensureWhatsAppChatsSearchAvailable()
                                val input = if (norm) openWhatsAppSearch() else null
                                Log.i("BENSON_AUDIO", "WA_SEARCH_SELFTEST result normalize=$norm searchOpen=${input != null}")
                            } catch (e: Exception) {
                                Log.i("BENSON_AUDIO", "WA_SEARCH_SELFTEST harness_exception=${e.message}")
                            }
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.benson.acc1.RUN")
            addAction("com.benson.wafix1.RUN")
            addAction("com.benson.wasearch.RUN")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        acc1Receiver = r
    }

    // Defense-in-depth: onServiceConnected only fires once per (re)bind, but the foreground
    // service could in principle die later without the accessibility binding itself dying (same
    // process, so unlikely, but not impossible — e.g. an explicit stopSelf() bug). This event
    // fires on every foreground-app change system-wide, so maybeResurrect's own throttle (not
    // this call site) is what keeps it cheap.
    private fun maybeResurrect(reason: String) {
        val prefs = getSharedPreferences(GUARDIAN_PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Confirmed live 2026-07-30: the user explicitly stopping BENSON (notification STOP
        // action) looks identical to a real OS/crash kill from the heartbeat's point of view —
        // both just mean "heartbeat went stale." Without this check Guardian revived BENSON
        // shortly after every intentional stop, which is exactly the opposite of what "stop"
        // should do. This flag is cleared the next time BENSON starts normally (see
        // BensonForegroundService.touchGuardianHeartbeat), so a later real crash still recovers.
        if (prefs.getBoolean(KEY_USER_STOPPED, false)) return
        val heartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        if (now - heartbeat < HEARTBEAT_STALE_MS) return // foreground service is alive and recent

        val lastAttempt = prefs.getLong(KEY_LAST_RESURRECTION_AT, 0L)
        if (now - lastAttempt < RESURRECTION_THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST_RESURRECTION_AT, now).apply()

        Log.w(TAG, "Guardian: foreground-service heartbeat stale (age=${now - heartbeat}ms, reason=$reason) — resurrecting")
        recordRecoveryEvent(prefs, now, reason)
        prefs.edit().putBoolean(KEY_RECOVERY_PENDING, true).apply()

        try {
            val svcIntent = Intent().setClassName(packageName, FOREGROUND_SERVICE_CLASS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Guardian: failed to start foreground service: ${e.message}")
        }

        if (isWhatsAppAutomationRecentlyActive()) {
            Log.i(TAG, "Guardian: WhatsApp automation is in progress or ended recently — restarted the foreground service but skipped stealing focus back to BENSON")
            return
        }

        // There is no supported way to run BENSON's actual voice pipeline (STT/TTS, both
        // Activity-bound) without an Activity — a true headless revival was deliberately not
        // attempted (see FINAL_BUILD_REPORT.md). Waking the screen and bringing MainActivity
        // forward reuses the exact mechanism wake-word detection already relies on, so the JS
        // layer boots through its normal, already-proven init path instead of an unverified one.
        //
        // E1-1 (2026-09-07): gated OFF by default — the foreground service (mic + wake word) has
        // been restarted above; that alone keeps BENSON reachable by the wake word without
        // hijacking the screen. The Activity is only surfaced again by a wake word or a touch.
        if (GUARDIAN_BRING_ACTIVITY_TO_FRONT) {
            wakeScreenForRecovery()
            bringBensonToForegroundForRecovery()
        } else {
            Log.i(TAG, "Guardian: foreground service restarted; Activity NOT brought to front (E1-1)")
        }
    }

    private fun recordRecoveryEvent(prefs: SharedPreferences, now: Long, reason: String) {
        val existing = prefs.getString(KEY_RECOVERY_EVENTS, "") ?: ""
        val events = existing.split(",").filter { it.isNotBlank() }.toMutableList()
        events.add("$now:$reason")
        while (events.size > MAX_RECOVERY_EVENTS) events.removeAt(0)
        prefs.edit().putString(KEY_RECOVERY_EVENTS, events.joinToString(",")).apply()
    }

    private fun wakeScreenForRecovery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "BensonAccessibilityService:guardian",
            )
            wl.acquire(3_000L)
        } catch (_: Exception) {}
    }

    private fun bringBensonToForegroundForRecovery() {
        try {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }?.let { startActivity(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Guardian: failed to bring MainActivity to front: ${e.message}")
        }
    }

    // Called by the OS when the service is being disabled/unbound (user toggled it off in
    // Settings, or the system revoked the binding) — NOT called on an OEM process kill, which
    // goes straight to process death with no callback at all (the one AccessibilityService
    // lifecycle gap no app-level code can close; see connectionEpoch/instance for what a caller
    // CAN observe afterward). Clearing instance here means isServiceAlive-style checks go false
    // the instant Android itself decides to unbind, without waiting for a fresh event.
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "Benson accessibility service unbound")
        instance = null
        connectionEpoch++
        return super.onUnbind(intent)
    }

    // RUNDA B — content-change events are cheap by default: subscribing to them is what keeps
    // rootInActiveWindow fresh (see the config xml comment), but WALKING the tree + bridging a
    // snapshot to JS on every system-wide content change is the 2026-07-09 thermal problem. So the
    // snapshot walk on content-change runs ONLY during a WhatsApp automation, throttled.
    private var lastContentSnapshotAt = 0L
    private val CONTENT_SNAPSHOT_MIN_INTERVAL_MS = 250L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                lastForegroundPackage = packageName
                onForegroundChanged?.invoke(packageName)
                maybeResurrect("onAccessibilityEvent")
                emitScreenSnapshot(packageName)
                maybePushImeStateToBubble()
                pushForegroundPackageToBubble(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Just receiving this event keeps the node cache current for waitForNode — that's
                // the whole point. maybeResurrect is internally throttled to 60s so it's ~free.
                maybeResurrect("onAccessibilityEvent")
                if (whatsappAutomationActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastContentSnapshotAt >= CONTENT_SNAPSHOT_MIN_INTERVAL_MS) {
                        lastContentSnapshotAt = now
                        event.packageName?.toString()?.let { emitScreenSnapshot(it) }
                    }
                }
            }
            else -> { /* ignorat — reduce zgomotul */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Benson accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("BENSON_AUDIO", "WA_SERVICE_LIFECYCLE event=destroyed epoch=$connectionEpoch")
        acc1Receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        acc1Receiver = null
        serviceScope.cancel()
        instance = null
        connectionEpoch++
    }

    // ---------------------------------------------------------------
    // SNAPSHOT
    // ---------------------------------------------------------------

    private fun emitScreenSnapshot(packageName: String) {
        val root = rootInActiveWindow ?: return
        try {
            val nodes = JSONArray()
            val counter = intArrayOf(0)
            walk(root, 0, counter, nodes)

            val payload = JSONObject().apply {
                put("packageName", packageName)
                put("timestamp", System.currentTimeMillis())
                put("nodes", nodes)
            }
            onScreenUpdate?.invoke(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
        out: JSONArray
    ) {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return
        counter[0]++

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hasContent = text.isNotBlank() || desc.isNotBlank() || node.isEditable

        if (hasContent) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            out.put(JSONObject().apply {
                put("id", "${node.hashCode()}")
                put("viewId", node.viewIdResourceName ?: JSONObject.NULL)
                put("text", text)
                put("contentDescription", desc)
                put("className", node.className?.toString() ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("checkable", node.isCheckable)
                put("checked", node.isChecked)
                put("bounds", JSONObject().apply {
                    put("left", bounds.left); put("top", bounds.top)
                    put("right", bounds.right); put("bottom", bounds.bottom)
                })
            })
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, counter, out)
            child.recycle()
        }
    }

    // On-demand snapshot (2026-08-29) — reads rootInActiveWindow FRESH right now, so the caller
    // never gets a stale tree. The pushed onScreenUpdate cache (emitScreenSnapshot above) only
    // fires on TYPE_WINDOW_STATE_CHANGED — the only event type this service subscribes to for
    // CPU/thermal reasons (see res/xml/accessibility_service_config.xml). WhatsApp's search
    // results populate via TYPE_WINDOW_CONTENT_CHANGED, which never reaches JS — so anything that
    // must read the CURRENT screen after typing (whatsappTool.ts) has to pull it on demand here.
    // Retries up to 3× @150ms when the tree is momentarily null/empty (normal right after a
    // transition). Runs on serviceScope via runOnServiceScope() — survives JS-thread throttling.
    suspend fun captureSnapshot(): String {
        var retries = 0
        var pkg = ""
        var nodes = JSONArray()
        while (true) {
            // RUNDA B — the tree walk + AccessibilityNodeInfo.getChild() IPC used to run on
            // Dispatchers.Main and could STALL the whole JS bridge (r.txt: "hung ~60s") while
            // WhatsApp was still launching. Now: off the main thread, with a HARD per-attempt cap
            // so getScreenSnapshot() always returns promptly — empty if it had to — never hangs
            // the caller. Retries 3×@150ms while the tree is momentarily empty.
            val attempt = withTimeoutOrNull(1200L) {
                withContext(Dispatchers.Default) {
                    val root = rootInActiveWindow ?: return@withContext null
                    val p = root.packageName?.toString() ?: ""
                    val fresh = JSONArray()
                    try { walk(root, 0, intArrayOf(0), fresh) } catch (_: Exception) {}
                    finally { try { root.recycle() } catch (_: Exception) {} }
                    p to fresh
                }
            }
            if (attempt != null) {
                pkg = attempt.first
                nodes = attempt.second
                if (nodes.length() > 0) break
            }
            if (retries >= 3) break
            retries++
            delay(150)
        }
        val capturedAt = System.currentTimeMillis()
        Log.i("BENSON_AUDIO", "SNAPSHOT pkg=$pkg nodes=${nodes.length()} ageMs=0 retries=$retries")
        return JSONObject().apply {
            put("packageName", pkg)
            put("timestamp", capturedAt)   // back-compat with the pushed-cache shape
            put("capturedAt", capturedAt)
            put("nodeCount", nodes.length())
            put("nodes", nodes)
        }.toString()
    }

    // ---------------------------------------------------------------
    // ACȚIUNI — apelate din BensonAccessibilityModule
    // ---------------------------------------------------------------

    fun performClickById(nodeId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = findByHash(root, nodeId) ?: return false
            if (isPaymentSensitive(target)) {
                Log.w(TAG, "Blocked click on payment-sensitive node")
                return false
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            root.recycle()
        }
    }

    fun performSetTextById(nodeId: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = findByHash(root, nodeId) ?: return false
            if (!target.isEditable) return false
            if (isPaymentSensitive(target)) {
                Log.w(TAG, "Blocked text-fill on payment-sensitive node")
                return false
            }
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value
            )
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            root.recycle()
        }
    }

    fun fillForm(fields: Map<String, String>): Int {
        val root = rootInActiveWindow ?: return 0
        var filled = 0
        try {
            val editableNodes = mutableListOf<AccessibilityNodeInfo>()
            collectEditable(root, editableNodes)

            for (node in editableNodes) {
                val label = (node.hintText?.toString() ?: node.contentDescription?.toString()
                    ?: node.viewIdResourceName ?: "").lowercase()

                val match = fields.entries.firstOrNull { (key, _) ->
                    label.contains(key.lowercase())
                }

                if (match != null && !isPaymentSensitive(node)) {
                    val args = android.os.Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        match.value
                    )
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        filled++
                    }
                }
            }
        } finally {
            root.recycle()
        }
        return filled
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    // Opens the system Recents / app-switcher screen. GLOBAL_ACTION_RECENTS is a plain global
    // action (no canPerformGestures needed — that flag stays false for OEM anti-spyware safety).
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Runs a package-allow-listed declarative profile on the service coroutine. The profile
     * executor re-resolves every target from the live tree and returns an honest step-level
     * failure instead of attempting a coordinate tap.
     */
    suspend fun runAutomationProfile(profileId: String, params: JSONObject): AutomationProfileResult {
        // Guardian currently needs this signal while WhatsApp is foregrounded. Keep the runtime
        // concern here, rather than leaking it into a declarative profile.
        val protectsWhatsApp = profileId.startsWith("whatsapp.")
        if (protectsWhatsApp) whatsappAutomationActive = true
        try {
            val result = profileAutomationEngine.run(profileId, params)
            return AutomationProfileResult(result.success, result.step, result.error)
        } finally {
            if (protectsWhatsApp) whatsappAutomationActive = false
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun findByHash(node: AccessibilityNodeInfo, hash: String): AccessibilityNodeInfo? {
        if ("${node.hashCode()}" == hash) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByHash(child, hash)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun collectEditable(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth >= MAX_DEPTH) return
        if (node.isEditable) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditable(child, out, depth + 1)
        }
    }

    private fun isPaymentSensitive(node: AccessibilityNodeInfo): Boolean {
        val haystack = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName,
            node.hintText?.toString()
        ).joinToString(" ").lowercase()
        return PAYMENT_BLOCKLIST.any { haystack.contains(it) }
    }

    // ---------------------------------------------------------------
    // WHATSAPP CALL FLOW — native state machine (product-owner-authorized 2026-07-14).
    //
    // Runs entirely on serviceScope (native coroutine, not a JS timer) so it survives BENSON
    // being backgrounded the instant WhatsApp opens — the JS-driven version of this same flow
    // was found to silently stall on ColorOS because the RN JS thread gets throttled the moment
    // its Activity loses foreground. Every step re-reads rootInActiveWindow fresh (no stale
    // snapshot) and verifies the expected element is actually present before acting; any missing
    // element aborts immediately and reports exactly which step failed. No coordinate taps —
    // every action is performAction() on a verified AccessibilityNodeInfo, and the existing
    // isPaymentSensitive() blocklist stays in the loop for the final tap.
    // ---------------------------------------------------------------

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase().trim()
    }

    private fun matchesAny(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val label = nodeLabel(node)
        if (label.isEmpty()) return false
        return keywords.any { label.contains(it) }
    }

    // WhatsApp's search bar, its text field, and its media-type filter row all carry viewIds
    // prefixed "search_" (search_input, search_media_filter_image, ...) per live device
    // inspection (2026-07-14) — used to exclude that whole UI from result/chat matching so a
    // leftover "search_input" still showing the typed name doesn't get mistaken for a real result
    // or for the chat having opened.
    private fun isSearchUiNode(node: AccessibilityNodeInfo): Boolean {
        return node.viewIdResourceName?.contains("search") == true
    }

    // Confirmed live (2026-07-14): a contact's avatar image (contentDescription "Bild von X" /
    // "photo of X", viewId contact_photo) also contains the name AND is independently clickable
    // in WhatsApp's search results — tapping it opens the contact's PROFILE screen, not the chat.
    // For "Hannah" (an existing chat) the name text happened to be matched first and correctly
    // routed through findClickableAncestor to the row container; for "Baby" (no prior chat) the
    // avatar matched first instead and got tapped directly, landing on the wrong screen. Excluding
    // avatar-shaped nodes from matching entirely forces this to always find the name text and
    // always walk up to the row container, regardless of which order WhatsApp lays out the row.
    // Confirmed live (2026-07-17): a plain `.contains()` substring match called the WRONG person —
    // searching for "Ana" matched a result row labeled "Adriana Cherciu", since "adriana" contains
    // "ana" as a substring. Whole-word matching (neither the character immediately before nor
    // after the match may be a letter) rejects that case ("i" precedes "ana" inside "adriana")
    // while still matching "Ana Popescu" (bounded by string-start and a space). Calling the wrong
    // person is worse than an honest "no result found", so this is not a loosened-fallback tier —
    // if nothing matches whole-word, the search genuinely failed.
    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val idx = haystack.indexOf(needle)
        if (idx == -1) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetter()
        val afterIdx = idx + needle.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetter()
        return beforeOk && afterOk
    }

    private fun isAvatarNode(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("photo") || viewId.contains("picture") || viewId.contains("avatar")) return true
        return node.className == "android.widget.ImageView"
    }

    // WhatsApp's search screen shows a horizontal "Recent" strip of quick-contact chips ABOVE the
    // real results list — confirmed live 2026-07-17: a contact who also appears there ("hannah")
    // got matched first (tree order puts the strip before the results list), and its clickable
    // ancestor ("recent_container") silently rejected the tap (performAction returned false),
    // unlike a real result row's "contact_row_container" which is reliably clickable — same class
    // of "wrong element matched the name text" bug the avatar exclusion above already covers, just
    // a different WhatsApp UI element. Walk up looking for a "recent"-tagged ancestor rather than
    // checking the matched node itself, since the container tag lives a few levels up, not on the
    // name TextView directly.
    private fun isRecentSuggestionNode(node: AccessibilityNodeInfo, maxHops: Int = 6): Boolean {
        var current = node.parent
        var hops = 0
        while (current != null && hops < maxHops) {
            val viewId = current.viewIdResourceName ?: ""
            if (viewId.contains("recent", ignoreCase = true)) {
                current.recycle()
                return true
            }
            val next = current.parent
            current.recycle()
            current = next
            hops++
        }
        return false
    }

    // The name text in a WhatsApp search result lives in a non-clickable child TextView
    // (conversations_row_contact_name); the actual tap target is an ancestor container
    // (contact_row_container). Walks up from a matched node to the nearest clickable one.
    private fun findClickableAncestor(node: AccessibilityNodeInfo, maxHops: Int = 6): AccessibilityNodeInfo? {
        var current = node.parent
        var hops = 0
        while (current != null && hops < maxHops) {
            if (current.isClickable) return current
            val next = current.parent
            current.recycle()
            current = next
            hops++
        }
        return current
    }

    private fun headerRegionMaxTop(): Int {
        // ~15% of screen height — generous enough for WhatsApp's chat header across device
        // densities without reaching down into the message list itself.
        return (resources.displayMetrics.heightPixels * 0.15).toInt()
    }

    private fun findNodeMatching(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        try {
            return findNodeRecursive(root, predicate, intArrayOf(0))
        } finally {
            root.recycle()
        }
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        counter: IntArray,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return null
        counter[0]++
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate, counter, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    // RUNDA B — the single wait primitive. Polls the LIVE accessibility tree (native delay(), not
    // a JS timer) until `predicate` matches or `timeoutMs` elapses. No fixed sleep is left on the
    // execution path — every step of the call recipe below is gated on one of these instead.
    // `anchor` is a human label for the log line only. Default pollMs=100.
    // Log: WAIT_NODE predicate=<anchor> foundAfterMs=<ms> result=found|timeout
    private suspend fun waitForNode(
        timeoutMs: Long = 3000,
        pollMs: Long = 100,
        requirePackage: String? = null,
        anchor: String = "node",
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requirePackage == null || rootInActiveWindow?.packageName?.toString() == requirePackage) {
                findNodeMatching(predicate)?.let {
                    Log.i("BENSON_AUDIO", "WAIT_NODE predicate=$anchor foundAfterMs=${System.currentTimeMillis() - startedAt} result=found")
                    return it
                }
            }
            delay(pollMs)
        }
        Log.i("BENSON_AUDIO", "WAIT_NODE predicate=$anchor foundAfterMs=${System.currentTimeMillis() - startedAt} result=timeout")
        return null
    }

    // ── RUNDA B — potrivire fonetică de nume, insensibilă la diacritice ───────────────────────────
    private fun normPhon(s: String?): String =
        java.text.Normalizer.normalize(s ?: "", java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "")

    private fun collapseDoubles(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString()
    }

    private fun consSkeleton(s: String): String = s.replace(Regex("[aeiou]"), "")

    // Bounded Levenshtein with early exit — same tolerance the JS row matcher (B-fix2 /
    // lib/appIndex.ts) uses. "hana" ~ "hannah" = 2 edits; the token checks below can't catch that
    // (skeleton "hn" vs "hnnh", collapseDoubles "hana" vs "hanah").
    private fun boundedLevenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    // True if a WhatsApp row label phonetically matches the FULL spoken name. Anchored on whole
    // tokens — never a mid-word substring (the Ana/Adriana lesson: "adriana" must NOT match "ana").
    // Tolerates: diacritics, a doubled/'dropped letter ("Hanna" ~ "Hannah"), vowel slips, and up
    // to 2 edits on a same-first-letter, close-length token ("Hana" ~ "Hannah").
    private fun phoneticNameMatch(rowLabel: String, fullName: String): Boolean {
        val target = normPhon(fullName)
        if (target.length < 2) return false
        val tokens = rowLabel.split(Regex("\\s+")).map { normPhon(it) }.filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        val tCol = collapseDoubles(target)
        val tSkel = consSkeleton(target)
        val editBudget = if (target.length <= 3) 1 else 2
        for (w in tokens) {
            if (w == target) return true
            if (collapseDoubles(w) == tCol) return true
            if (tSkel.length >= 2 && consSkeleton(w) == tSkel) return true
            if (w.length >= 3 && (w.startsWith(target) || target.startsWith(w))) return true
            if (w.isNotEmpty() && w[0] == target[0] && kotlin.math.abs(w.length - target.length) <= 3 &&
                boundedLevenshtein(w, target, editBudget) <= editBudget) return true
        }
        return false
    }

    // Whole-label phonetic equality — used as the FIRST tier so that a contact whose name merely
    // ends in the spoken word (the "…Davids Mama" lesson) never wins over the real "Mama".
    private fun wholeLabelPhoneticEquals(rowLabel: String, fullName: String): Boolean {
        val a = normPhon(rowLabel)
        val b = normPhon(fullName)
        if (b.length < 2) return false
        return a == b || collapseDoubles(a) == collapseDoubles(b)
    }

    private var recipeStepIndex = 0
    private fun recipeStep(name: String, anchor: String, found: Boolean, elapsedMs: Long) {
        Log.i("BENSON_AUDIO", "RECIPE_STEP index=${recipeStepIndex++} name=$name anchor=$anchor found=$found elapsedMs=$elapsedMs")
    }

    private fun launchWhatsApp(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchWhatsApp failed: ${e.message}")
            false
        }
    }

    // Re-added 2026-07-17 (removed 2026-07-17 earlier the same day, per an interim "stay on
    // WhatsApp" instruction — now superseded): product-owner-directed final flow is BENSON PiP +
    // WhatsApp full-screen WHILE the automation runs (JS-side enterPipMode(), called right before
    // this class's launchWhatsApp()), then WhatsApp disappears and BENSON returns to full-screen
    // the moment the action actually completes. Bringing the Activity back via startActivity with
    // REORDER_TO_FRONT is also what restores it OUT of PiP mode, not just switches apps.
    private fun returnToBenson(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "returnToBenson failed: ${e.message}")
            false
        }
    }

    // AUTO-RETURN-AFTER-CALL — the ONLY way BENSON comes back after a WhatsApp call: an explicit
    // Intent to its own launch (MainActivity) component. Never BACK / GLOBAL_ACTION_BACK / HOME /
    // a gesture / an Accessibility click. NEW_TASK|REORDER_TO_FRONT|SINGLE_TOP reuses the single
    // existing Activity instead of stacking a new one.
    private fun returnToBensonForeground(reason: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            startActivity(intent)
            Log.i("BENSON_AUDIO", "WA_CALL_RETURN_INTENT_SENT reason=$reason ok=true")
            true
        } catch (e: Exception) {
            Log.e(TAG, "returnToBensonForeground failed: ${e.message}")
            Log.i("BENSON_AUDIO", "WA_CALL_RETURN_INTENT_SENT reason=$reason ok=false error=${e.message}")
            false
        }
    }

    // READ-ONLY. True while a WhatsApp call screen is on screen — the structural signal the
    // lifecycle watcher debounces on. Same viewId set the CALL_VERIFY step used to confirm the
    // call started, so "gone" here is the exact inverse of "started".
    private fun callScreenPresent(): Boolean = try {
        findNodeMatching { n ->
            val vid = n.viewIdResourceName ?: ""
            vid.contains("voip_") ||
                vid.endsWith("/call_screen") || vid.endsWith("/end_call_button") ||
                vid.endsWith("/audio_route_button") || vid.endsWith("/call_screen_header_view") ||
                vid.endsWith("/call_controls_card")
        } != null
    } catch (e: Exception) {
        false
    }

    // AUTO-RETURN-AFTER-CALL — runs on serviceScope after WA_NATIVE_CALL_VERIFY success=true, so it
    // survives BENSON's Activity being backgrounded. Detection of call end is STRUCTURAL and
    // debounced (call-screen accessibility nodes gone for END_DEBOUNCE_POLLS consecutive polls);
    // the elapsed-time cap is a FAILSAFE only, never the primary mechanism. On a verified end it:
    //   1. releases the mic/audio hold (whatsappCallMicHoldUntilMs = 0),
    //   2. arms callEndedReturnPending + fires the explicit MainActivity Intent,
    //   3. verifies BENSON actually reached the foreground.
    // It performs NO other UI action — no BACK/HOME/gesture/click on WhatsApp.
    private suspend fun watchWhatsAppCallLifecycle(contact: String, verifiedAt: Long) {
        val pollMs = 700L
        val endDebouncePolls = 3           // ~2.1s with no call screen == ended
        val maxWatchMs = 30 * 60 * 1000L   // failsafe cap only
        var heartbeat = 0
        var goneStreak = 0
        var ending = false
        Log.i("BENSON_AUDIO", "WA_CALL_STATE state=CALL_ACTIVE contact=\"$contact\"")
        while (System.currentTimeMillis() - verifiedAt < maxWatchMs) {
            delay(pollMs)
            if (callScreenPresent()) {
                if (ending) Log.i("BENSON_AUDIO", "WA_CALL_STATE state=CALL_ACTIVE detail=call_screen_reappeared")
                goneStreak = 0
                ending = false
                if (++heartbeat % 20 == 0) {
                    Log.i("BENSON_AUDIO", "WA_CALL_STATE state=CALL_ACTIVE tMs=${System.currentTimeMillis() - verifiedAt}")
                }
                continue
            }
            // RUNTIME LAYER v1 — the call-screen nodes are not readable right now. Before counting
            // that as "call ended", check the environment: a transient owner (SystemUI shade /
            // notification / volume panel / IME / accessibility overlay) on top of a still-live
            // WhatsApp window is NOT a call end — hold the streak and keep watching.
            val env = observeEnvironment()
            if (classifyInterruption(env, WA_PKG) == InterruptionClass.TEMPORARY_INTERRUPTION) {
                Log.i("BENSON_AUDIO", "ENV_OBSERVE fg=${env.foregroundPackage ?: "?"} sysOverlay=${env.systemOverlayPresent} ime=${env.imePresent} a11yOverlay=${env.accessibilityOverlayPresent} wins=${env.visiblePackages.size}")
                Log.i("BENSON_AUDIO", "INTERRUPTION_CLASS class=TEMPORARY_INTERRUPTION detail=call_watch")
                continue
            }
            goneStreak++
            if (!ending) {
                ending = true
                Log.i("BENSON_AUDIO", "WA_CALL_END_DETECTED tMs=${System.currentTimeMillis() - verifiedAt} fg=${lastForegroundPackage ?: "?"}")
                Log.i("BENSON_AUDIO", "WA_CALL_STATE state=CALL_ENDING")
                // Persist ENDING before this coroutine (or its whole service) can vanish. CALL_ENDING
                // alone does NOT release the mic — a fresh instance still re-verifies absence.
                try { waCallPersistState(this, WA_CALL_STATE_ENDING, contact) } catch (_: Exception) {}
            }
            if (goneStreak >= endDebouncePolls) break
        }
        val timedOut = System.currentTimeMillis() - verifiedAt >= maxWatchMs
        val endReason = if (timedOut) "failsafe_timeout" else "call_screen_gone"
        Log.i("BENSON_AUDIO", "WA_CALL_END_VERIFIED reason=$endReason tMs=${System.currentTimeMillis() - verifiedAt} fg=${lastForegroundPackage ?: "?"}")
        finalizeWhatsAppCallEnded(if (timedOut) "watchdog" else "watcher")
    }

    // WA-LIFECYCLE-FIX-1 — the ONE authoritative "the WhatsApp call is over" path. Idempotent:
    // guarded on the persisted state so the watcher AND a service-recovery pass can both call it and
    // only the first takes effect (no duplicate hold release / return Intent / wake-restore).
    private val waFinalizeLock = Any()
    private fun finalizeWhatsAppCallEnded(reason: String) {
        synchronized(waFinalizeLock) {
            val prior = try { waCallReadState(this).state } catch (_: Exception) { WA_CALL_STATE_IDLE }
            if (prior == WA_CALL_STATE_IDLE) return  // already finalized — do nothing
            try { waCallPersistState(this, WA_CALL_STATE_IDLE, "") } catch (_: Exception) {}
            whatsappCallMicHoldUntilMs = 0L
            callEndedReturnPending = true
            Log.i("BENSON_AUDIO", "WA_CALL_STATE state=CALL_ENDED reason=$reason")
            Log.i("BENSON_AUDIO", "WA_CALL_AUDIO_HOLD state=released")
            Log.i("BENSON_AUDIO", "WA_CALL_END_SIGNAL state=published")
            Log.i("BENSON_AUDIO", "WA_WAKE_RESTORE_REQUEST reason=call_ended")
        }
        // Outside the lock — network/UI. Auto-return is best-effort and independent of wake restore
        // (which the JS self-heal does off the published signal, foreground or not).
        val sent = try { returnToBensonForeground("call_ended") } catch (_: Exception) { false }
        serviceScope.launch {
            var fgVerified = false
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                if (lastForegroundPackage == packageName) { fgVerified = true; break }
                delay(300)
            }
            Log.i("BENSON_AUDIO", "WA_CALL_RETURN_FOREGROUND_VERIFIED verified=$fgVerified sent=$sent fg=${lastForegroundPackage ?: "?"}")
        }
    }

    // Called from onServiceConnected: a fresh AccessibilityService instance reads the persisted
    // lifecycle and, if a call was active/ending when the previous instance died, re-verifies
    // reality and either resumes the watcher or finalizes CALL_ENDED. The 180s watchdog is NOT the
    // recovery path here.
    private fun recoverWhatsAppCallLifecycle() {
        val st = try { waCallReadState(this) } catch (_: Exception) { return }
        if (st.state == WA_CALL_STATE_IDLE) return
        Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_START persistedState=${st.state} ageMs=${System.currentTimeMillis() - st.updatedAt}")
        serviceScope.launch {
            // Give the freshly-connected service a beat to have a live window tree.
            delay(600)
            // A call cannot realistically outlive this unnoticed — treat as ended.
            if (st.startedAt > 0 && System.currentTimeMillis() - st.startedAt > 30 * 60 * 1000L) {
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_OBSERVE callPresent=false detail=stale")
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=finalize_ended")
                finalizeWhatsAppCallEnded("service_recovery")
                return@launch
            }
            var present = 0
            var absent = 0
            val maxPolls = 8
            for (i in 1..maxPolls) {
                val p = try { callScreenPresent() } catch (_: Exception) { false }
                if (p) { present++; absent = 0 } else { absent++; present = 0 }
                if (present >= 2) {
                    Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_OBSERVE callPresent=true")
                    Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=resume_watch")
                    waCallPersistState(this@BensonAccessibilityService, WA_CALL_STATE_ACTIVE, st.contact)
                    watchWhatsAppCallLifecycle(st.contact, if (st.startedAt > 0) st.startedAt else System.currentTimeMillis())
                    return@launch
                }
                if (absent >= 3) {
                    Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_OBSERVE callPresent=false")
                    Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=finalize_ended")
                    finalizeWhatsAppCallEnded("service_recovery")
                    return@launch
                }
                delay(600)
            }
            // Still uncertain after the budget — one short recheck, then default to finalize (never
            // leave the mic held waiting on the 180s watchdog).
            Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=retry")
            delay(1200)
            val stillPresent = try { callScreenPresent() } catch (_: Exception) { false }
            if (stillPresent) {
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_OBSERVE callPresent=true detail=retry")
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=resume_watch")
                waCallPersistState(this@BensonAccessibilityService, WA_CALL_STATE_ACTIVE, st.contact)
                watchWhatsAppCallLifecycle(st.contact, if (st.startedAt > 0) st.startedAt else System.currentTimeMillis())
            } else {
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_OBSERVE callPresent=false detail=retry")
                Log.i("BENSON_AUDIO", "WA_CALL_RECOVERY_RESULT action=finalize_ended")
                finalizeWhatsAppCallEnded("service_recovery")
            }
        }
    }


    // Dumps every clickable-or-labeled node currently on screen to logcat — called only when a
    // waitForNode() times out, so a failed step is diagnosable from `adb logcat` directly instead
    // of guessing at WhatsApp's real button labels on this device/version/language.
    private fun dumpScreenForDebug(stepTag: String) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "[$stepTag] dump: rootInActiveWindow is null")
            return
        }
        try {
            Log.w(TAG, "[$stepTag] dump: package=${root.packageName}, dumping clickable/labeled nodes:")
            val counter = intArrayOf(0)
            dumpNodeRecursive(root, stepTag, counter)
        } finally {
            root.recycle()
        }
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, stepTag: String, counter: IntArray, depth: Int = 0) {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return
        counter[0]++
        val label = nodeLabel(node)
        if (label.isNotBlank() || node.isEditable || node.isClickable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            Log.w(
                TAG,
                "[$stepTag]   node: label=\"$label\" class=${node.className} clickable=${node.isClickable} " +
                    "editable=${node.isEditable} top=${bounds.top} viewId=${node.viewIdResourceName}",
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeRecursive(child, stepTag, counter, depth + 1)
            child.recycle()
        }
    }

    // autoPressCall: when false (the current default caller behaviour, chosen 2026-07-14 after
    // a full day of live testing showed the final call-button tap — step 6 below — was the least
    // reliable part, at the mercy of ColorOS repeatedly killing/restarting this service mid-flow)
    // the state machine stops right after confirming the right chat is open (step 5) and leaves
    // the actual call button for the user to tap themselves. Steps 1-5 (open WhatsApp, search,
    // type, tap the result, verify the chat) are unchanged either way.
    suspend fun placeWhatsAppCall(contactName: String, autoPressCall: Boolean = true): WhatsAppCallResult {
        whatsappAutomationActive = true
        try {
            return placeWhatsAppCallInner(contactName, autoPressCall)
        } finally {
            whatsappAutomationActive = false
        }
    }

    // ── RUNDA B — rețeta de apel rescrisă ────────────────────────────────────────────────────────
    // Fiecare pas: waitForNode(ancoră) → apasă → waitForNode(confirmarea că ecranul s-a schimbat).
    // Niciun sleep fix pe calea de execuție (singura excepție: delay(2500) DUPĂ ce butonul de apel
    // a fost deja apăsat — WhatsApp are nevoie de timp să-și stabilească sesiunea de apel, nu există
    // niciun nod de așteptat pentru asta; nu e pe drumul spre reușită). Ancoră expirată → oprire cu
    // mesaj care numește pasul, zero apăsări oarbe. Log per pas: RECIPE_STEP ...
    private suspend fun placeWhatsAppCallInner(contactName: String, autoPressCall: Boolean): WhatsAppCallResult {
        recipeStepIndex = 0
        val name = contactName.trim()
        Log.i(TAG, "placeWhatsAppCall: starting for \"$name\"")
        if (name.isEmpty()) return WhatsAppCallResult(false, "validate", "Contact name is empty.")

        val setTextArgs = { value: String -> android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        } }

        // ── Pas 0: WhatsApp în prim-plan ─────────────────────────────────────────────────────────
        if (!launchWhatsApp()) {
            return WhatsAppCallResult(false, "launch", "Could not launch WhatsApp.")
        }
        var t = System.currentTimeMillis()
        val whatsappReady = waitForNode(3000, 100, WHATSAPP_PACKAGE, "whatsapp_window") { true }
        recipeStep("launch", "whatsapp_window", whatsappReady != null, System.currentTimeMillis() - t)
        if (whatsappReady == null) {
            return WhatsAppCallResult(false, "launch", "WhatsApp did not reach the foreground.")
        }

        // ── Pas 1: pe lista de chat-uri (nu blocați într-un chat individual) ──────────────────────
        var searchField: AccessibilityNodeInfo? = null
        var onChatList = false
        for (attempt in 1..5) {
            t = System.currentTimeMillis()
            val searchIcon = waitForNode(1200, 100, WHATSAPP_PACKAGE, "chat_list_search_icon") {
                it.isClickable && matchesAny(it, SEARCH_KEYWORDS)
            }
            if (searchIcon != null) {
                recipeStep("reach_chat_list", "chat_list_search_icon", true, System.currentTimeMillis() - t)
                onChatList = true
                break
            }
            // O căutare rămasă deschisă cu text vechi dintr-o încercare anterioară — reia câmpul.
            val staleSearch = findNodeMatching { it.viewIdResourceName == "com.whatsapp:id/search_input" }
            if (staleSearch != null) {
                recipeStep("reach_chat_list", "stale_search_input", true, System.currentTimeMillis() - t)
                searchField = staleSearch
                break
            }
            val inChat = findNodeMatching { it.viewIdResourceName == "com.whatsapp:id/entry" }
            if (inChat == null) {
                // Nici pe listă, nici într-un chat — încă se încarcă; următoarea buclă re-așteaptă.
                recipeStep("reach_chat_list", "chat_list_search_icon", false, System.currentTimeMillis() - t)
                continue
            }
            // Blocați într-un chat individual — apasă înapoi (butonul din toolbar, altfel back global).
            val toolbarHome = findNodeMatching {
                it.viewIdResourceName == "com.whatsapp:id/whatsapp_toolbar_home" && it.isClickable
            }
            if (toolbarHome != null) toolbarHome.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else performGlobalAction(GLOBAL_ACTION_BACK)
            recipeStep("escape_individual_chat", "whatsapp_toolbar_home", toolbarHome != null, System.currentTimeMillis() - t)
        }
        if (!onChatList && searchField == null) {
            dumpScreenForDebug("reach_chat_list")
            return WhatsAppCallResult(false, "reach_chat_list", "Could not reach WhatsApp's chat list to search — stopped before any blind tap.")
        }

        // ── Pas 2: deschide căutarea ────────────────────────────────────────────────────────────
        if (searchField == null) {
            t = System.currentTimeMillis()
            val searchIcon = waitForNode(3000, 100, WHATSAPP_PACKAGE, "search_icon") {
                it.isClickable && matchesAny(it, SEARCH_KEYWORDS)
            }
            recipeStep("open_search", "search_icon", searchIcon != null, System.currentTimeMillis() - t)
            if (searchIcon == null) {
                dumpScreenForDebug("open_search")
                return WhatsAppCallResult(false, "open_search", "Could not find WhatsApp's search icon.")
            }
            if (!searchIcon.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_search", "Found the search icon but the tap was not accepted.")
            }
            t = System.currentTimeMillis()
            val field = waitForNode(3000, 100, WHATSAPP_PACKAGE, "search_field") { it.isEditable }
            recipeStep("search_field", "editable_field", field != null, System.currentTimeMillis() - t)
            if (field == null) {
                dumpScreenForDebug("search_field")
                return WhatsAppCallResult(false, "search_field", "The search field did not appear after tapping search.")
            }
            searchField = field
        }

        // ── Pas 3: tastează un PREFIX de 3 caractere („Han" → „Hannah") ──────────────────────────
        val prefix = normPhon(name).take(3).ifEmpty { name.take(3) }
        Log.i(TAG, "placeWhatsAppCall: typing 3-char prefix \"$prefix\" for \"$name\"")
        if (!searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(prefix))) {
            return WhatsAppCallResult(false, "type_prefix", "Found the search field but could not type into it.")
        }

        // ── Pas 4: rândul rezultat — potrivire fonetică, insensibilă la diacritice, față de numele
        //          rostit COMPLET. Tier 1: eticheta întreagă a rândului == numele (ca „…Davids Mama"
        //          să nu bată „Mama"). Tier 2: potrivire fonetică pe token. Excluderile dovedite
        //          (search UI / avatar / sugestie „recent") rămân. ────────────────────────────────
        val rowFilter: (AccessibilityNodeInfo) -> Boolean = {
            !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it)
        }
        t = System.currentTimeMillis()
        val exactRow = waitForNode(1800, 100, WHATSAPP_PACKAGE, "result_exact") {
            rowFilter(it) && wholeLabelPhoneticEquals(nodeLabel(it), name)
        }
        val resultLabelNode = exactRow ?: waitForNode(2800, 100, WHATSAPP_PACKAGE, "result_phonetic") {
            rowFilter(it) && phoneticNameMatch(nodeLabel(it), name)
        }
        recipeStep("find_result", if (exactRow != null) "result_exact" else "result_phonetic", resultLabelNode != null, System.currentTimeMillis() - t)
        if (resultLabelNode == null) {
            dumpScreenForDebug("find_result")
            return WhatsAppCallResult(false, "find_result", "No phonetic search result for \"$name\" appeared — stopped before any blind tap.")
        }

        // ── Pas 5: apasă containerul-rând (strămoșul clickable al etichetei) ─────────────────────
        var tapTarget = findClickableAncestor(resultLabelNode) ?: resultLabelNode.takeIf { it.isClickable }
        if (tapTarget == null) {
            dumpScreenForDebug("tap_result")
            return WhatsAppCallResult(false, "tap_result", "Found \"$name\" in the results but no tappable row around it — stopped.")
        }
        var tapped = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var tapAttempt = 1
        while (!tapped && tapAttempt < 3) {
            // Re-rezolvă FRESH (referințele de nod pot deveni stale la ~200ms după update de arbore)
            // — nicio pauză fixă: waitForNode așteaptă un rând clickable proaspăt.
            t = System.currentTimeMillis()
            val fresh = waitForNode(700, 100, WHATSAPP_PACKAGE, "result_retry") {
                rowFilter(it) && (wholeLabelPhoneticEquals(nodeLabel(it), name) || phoneticNameMatch(nodeLabel(it), name))
            }
            val freshTarget = fresh?.let { findClickableAncestor(it) ?: it.takeIf { n -> n.isClickable } }
            recipeStep("tap_result_retry", "result_retry", freshTarget != null, System.currentTimeMillis() - t)
            if (freshTarget == null) { tapAttempt++; continue }
            tapTarget = freshTarget
            tapped = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tapAttempt++
        }
        if (!tapped) {
            return WhatsAppCallResult(false, "tap_result", "Found the result row but the tap was not accepted after $tapAttempt attempts.")
        }

        // ── Pas 6: confirmă că s-a deschis chat-ul individual. Ancoră fiabilă = câmpul de mesaj
        //          (com.whatsapp:id/entry) există DOAR într-un chat individual. ────────────────────
        t = System.currentTimeMillis()
        val chatOpen = waitForNode(3000, 100, WHATSAPP_PACKAGE, "chat_open") {
            it.viewIdResourceName == "com.whatsapp:id/entry"
        }
        recipeStep("verify_chat", "message_entry_field", chatOpen != null, System.currentTimeMillis() - t)
        if (chatOpen == null) {
            dumpScreenForDebug("verify_chat")
            return WhatsAppCallResult(false, "verify_chat", "Could not confirm $name's chat opened (no message field).")
        }

        if (!autoPressCall) {
            Log.i(TAG, "placeWhatsAppCall: autoPressCall=false, stopping after chat_opened")
            return WhatsAppCallResult(true, "chat_opened", null)
        }

        // ── Pas 7: butonul de apel (niciodată video), doar în zona de antet, blocklist de plată. ─
        val maxTop = headerRegionMaxTop()
        t = System.currentTimeMillis()
        val callNode = waitForNode(2500, 100, WHATSAPP_PACKAGE, "call_button") {
            val bounds = Rect()
            it.getBoundsInScreen(bounds)
            it.isClickable && bounds.top <= maxTop && matchesAny(it, CALL_KEYWORDS) && !matchesAny(it, VIDEO_EXCLUDE_KEYWORDS)
        }
        recipeStep("find_call_button", "call_button", callNode != null, System.currentTimeMillis() - t)
        if (callNode == null) {
            dumpScreenForDebug("find_call_button")
            return WhatsAppCallResult(false, "find_call_button", "Could not find the call button in $name's chat header.")
        }
        if (isPaymentSensitive(callNode)) {
            return WhatsAppCallResult(false, "call_button_blocked", "Blocked: call button node matched the payment-sensitive pattern.")
        }
        if (!callNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return WhatsAppCallResult(false, "tap_call_button", "Found the call button but the tap was not accepted.")
        }

        // DUPĂ apăsare (nu pe calea spre reușită): WhatsApp are nevoie de ~2.5s ca să-și
        // stabilească sesiunea de apel înainte ca BENSON să reia prim-planul — altfel focusul smuls
        // prea repede anulează apelul (confirmat live 2026-07-17). Niciun nod de așteptat pentru asta.
        Log.i(TAG, "placeWhatsAppCall: call button tapped, settling 2500ms before returning to BENSON")
        delay(2500)
        returnToBenson()
        return WhatsAppCallResult(true, "done")
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WA-NATIVE-FINAL (2026-09-08) — executorul ACTIV de apel WhatsApp, complet nativ.
    //
    // Un singur apel JS → toată secvența (launch → verify package → search → set_text → verify text
    // → contact match → verify chat header → call button → verify call screen) rulează AICI, pe
    // Dispatchers.Default, cu delay() de coroutine. Zero JS / zero setTimeout între pași → BENSON
    // Activity poate intra în background fără să întrerupă fluxul (serviciul de accesibilitate e
    // independent de Activity). Selectori: viewId întâi, apoi contentDescription, apoi semantic +
    // strămoș clickable. Niciun tap pe coordonate. Verificare reală la fiecare pas; un
    // ACTION_CLICK=true NU e succes. Retries mărginite, timeout-uri mărginite, re-citire FRESH.
    // ══════════════════════════════════════════════════════════════════════════════════════════════
    private val WA_PKG = "com.whatsapp"

    // Overlay-aware foreground check (portat din BensonCommandExecutor.resolveForegroundPackage):
    // root_active → window_scan (getWindows(), trece de bula BENSON) → last_foreground.
    private fun foregroundIsPackage(expected: String): Triple<Boolean, String, Int> {
        val rootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (rootPkg == expected) return Triple(true, "root_active", 0)
        val wins: List<AccessibilityWindowInfo> = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }
        for (w in wins) {
            val wp = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
            if (wp == expected && wp != packageName) return Triple(true, "window_scan", wins.size)
        }
        if ((rootPkg == null || rootPkg == packageName) && lastForegroundPackage == expected)
            return Triple(true, "last_foreground", wins.size)
        return Triple(false, "none", wins.size)
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // BENSON RUNTIME LAYER v1 (ROUND_RUNTIME_LAYER_1, 2026-09-10) — generic environment observation
    // + interruption classification + bounded target reacquire. Extends the existing runtime
    // (BensonAccessibilityService + BensonForegroundService + Guardian/START_STICKY); NO new
    // service. A mission's "is my target app/window still there?" wait routes through
    // awaitTargetReacquired() so a *transient* owner on top (SystemUI shade / notification / volume
    // panel, the IME, an accessibility overlay — classified generically by window TYPE, never by a
    // hardcoded third-party package) does NOT fail the mission; only a *different real app*
    // foreground past a short generic grace does. No app-specific transient exceptions.
    // ══════════════════════════════════════════════════════════════════════════════════════════════
    enum class InterruptionClass { EXPECTED, TEMPORARY_INTERRUPTION, BLOCKING }

    data class AppEnvironmentSnapshot(
        val foregroundPackage: String?,
        val visiblePackages: List<String>,
        val systemOverlayPresent: Boolean,
        val imePresent: Boolean,
        val accessibilityOverlayPresent: Boolean,
        val timestamp: Long,
    )

    // ROUND_BENSON_BUBBLE_IME_1 — the floating bubble (a separate module, no compile dependency
    // either way) needs to know when the software keyboard is up so it can hop out of the way.
    // The accessibility window list is the only cross-app IME signal available. Cheap: one
    // `windows` scan on a window-state change, pushed only on an actual visible↔hidden transition,
    // addressed to the overlay service by component-name string (no class dependency).
    @Volatile private var lastImeVisibleForBubble: Boolean? = null
    private fun maybePushImeStateToBubble() {
        val visible = try {
            (windows ?: emptyList()).any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) { return }
        if (visible == lastImeVisibleForBubble) return
        lastImeVisibleForBubble = visible
        try {
            val i = Intent().apply {
                component = android.content.ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
                action = "expo.modules.overlay.ACTION_IME_VISIBILITY"
                putExtra("ime_visible", visible)
            }
            startService(i)
        } catch (_: Exception) { /* overlay service not startable right now — ignore */ }
    }

    // ROUND_BUBBLE_VISIBILITY_POLICY_1 — native, background-safe self-app suppression: the
    // overlay must never show over BENSON's own Activity. Same idiom as
    // maybePushImeStateToBubble() just above (dedup on actual transition, address the overlay
    // service by component-name string, no Gradle dependency between the two modules) — reuses
    // the SAME TYPE_WINDOW_STATE_CHANGED event stream that already computes lastForegroundPackage
    // for JS's getForegroundPackage()/addForegroundChangeListener, rather than adding a second
    // observation mechanism.
    @Volatile private var lastForegroundIsSelfForBubble: Boolean? = null
    // ROUND_OVERLAY_FOREGROUND_STALENESS_1 (2026-09-18, device-confirmed) — a real 6+ minute
    // device session showed isSelfForeground latched at `true` (from an earlier genuine
    // self-foreground moment) and NEVER updated again despite the user genuinely being on other
    // apps/the launcher the whole time (confirmed: window-title history from dumpsys accessibility
    // showed real launcher transitions in that window) — the overlay stayed permanently suppressed,
    // so the bubble never once appeared for the Calculator wake attempt. The push is a single
    // best-effort startService() call wrapped in a silent catch (no log), so either a missed/
    // undelivered TYPE_WINDOW_STATE_CHANGED event or a transient startService() failure (this
    // session already confirmed background_start_restriction errors are real on this device, in
    // BensonForegroundService's own startup) can go unnoticed indefinitely. Two changes: log the
    // catch instead of silently swallowing it, and periodically RE-ASSERT the last known state
    // (bypassing the dedup) so any missed/failed push self-corrects within a few seconds instead
    // of staying wrong for the rest of the session — same self-healing idiom already used
    // elsewhere in this project (BensonForegroundService's own native heartbeat).
    private fun pushForegroundPackageToBubble(foregroundPackage: String, forceSend: Boolean = false) {
        val isSelf = foregroundPackage == packageName
        if (!forceSend && isSelf == lastForegroundIsSelfForBubble) return
        lastForegroundIsSelfForBubble = isSelf
        try {
            val i = Intent().apply {
                component = android.content.ComponentName(packageName, "expo.modules.overlay.BensonBubbleService")
                action = "expo.modules.overlay.ACTION_FOREGROUND_PACKAGE_CHANGED"
                putExtra("is_self_foreground", isSelf)
            }
            startService(i)
        } catch (e: Exception) {
            Log.w("BENSON_AUDIO", "OVERLAY_PUSH_FAILED isSelf=$isSelf error=\"${e.javaClass.simpleName}: ${e.message}\"")
        }
    }

    private fun startForegroundStateReassertLoop() {
        serviceScope.launch {
            while (true) {
                delay(4000)
                lastForegroundPackage?.let { pushForegroundPackageToBubble(it, forceSend = true) }
            }
        }
    }

    private fun observeEnvironment(): AppEnvironmentSnapshot {
        val now = System.currentTimeMillis()
        val wins: List<AccessibilityWindowInfo> = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }
        val pkgs = ArrayList<String>()
        var ime = false
        var sys = false
        var a11yOverlay = false
        var topAppPkg: String? = null
        var topLayer = Int.MIN_VALUE
        for (w in wins) {
            val wp = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
            if (wp != null) pkgs.add(wp)
            when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> ime = true
                AccessibilityWindowInfo.TYPE_SYSTEM -> sys = true
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> a11yOverlay = true
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val layer = try { w.layer } catch (_: Exception) { 0 }
                    if (layer >= topLayer && wp != null && wp != packageName) { topLayer = layer; topAppPkg = wp }
                }
                else -> {}
            }
        }
        if (pkgs.any { it.contains("systemui", ignoreCase = true) }) sys = true
        val rootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val fg = topAppPkg
            ?: rootPkg?.takeIf { it != packageName && !it.contains("systemui", ignoreCase = true) }
            ?: lastForegroundPackage
        return AppEnvironmentSnapshot(fg, pkgs.distinct(), sys, ime, a11yOverlay, now)
    }

    private fun classifyInterruption(env: AppEnvironmentSnapshot, expectedPackage: String): InterruptionClass {
        if (env.foregroundPackage == expectedPackage) return InterruptionClass.EXPECTED
        val targetStillVisible = env.visiblePackages.contains(expectedPackage)
        val transientOwner =
            env.foregroundPackage == null ||
                env.foregroundPackage == packageName ||
                env.foregroundPackage?.contains("systemui", ignoreCase = true) == true ||
                env.systemOverlayPresent || env.imePresent || env.accessibilityOverlayPresent
        // A generic transient system owner is up. If the target is still a live window underneath,
        // it's clearly temporary; if it's momentarily not in the list either, still treat as
        // temporary and let the grace/budget in awaitTargetReacquired decide.
        if (transientOwner) return InterruptionClass.TEMPORARY_INTERRUPTION
        // A different, real application owns the foreground with no transient owner on top.
        return if (targetStillVisible) InterruptionClass.TEMPORARY_INTERRUPTION else InterruptionClass.BLOCKING
    }

    // Waits for `expectedPackage` to (re)own the environment. EXPECTED → true. TEMPORARY_INTERRUPTION
    // → keep waiting. BLOCKING → fail only after `BLOCKING_GRACE_MS` of continuous BLOCKING. Overall
    // `budgetMs` timeout → fail cleanly. Poll 300ms.
    private suspend fun awaitTargetReacquired(expectedPackage: String, budgetMs: Long): Boolean {
        val start = System.currentTimeMillis()
        val blockingGraceMs = 2500L
        Log.i("BENSON_AUDIO", "TARGET_REACQUIRE_START expected=$expectedPackage budgetMs=$budgetMs")
        var blockingSince = 0L
        var lastClass: InterruptionClass? = null
        while (System.currentTimeMillis() - start < budgetMs) {
            val env = observeEnvironment()
            val cls = classifyInterruption(env, expectedPackage)
            Log.i(
                "BENSON_AUDIO",
                "ENV_OBSERVE fg=${env.foregroundPackage ?: "?"} sysOverlay=${env.systemOverlayPresent} " +
                    "ime=${env.imePresent} a11yOverlay=${env.accessibilityOverlayPresent} wins=${env.visiblePackages.size}",
            )
            if (cls != lastClass) { Log.i("BENSON_AUDIO", "INTERRUPTION_CLASS class=$cls"); lastClass = cls }
            when (cls) {
                InterruptionClass.EXPECTED -> {
                    Log.i("BENSON_AUDIO", "TARGET_REACQUIRE_OK expected=$expectedPackage elapsedMs=${System.currentTimeMillis() - start}")
                    return true
                }
                InterruptionClass.TEMPORARY_INTERRUPTION -> blockingSince = 0L
                InterruptionClass.BLOCKING -> {
                    if (blockingSince == 0L) blockingSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - blockingSince >= blockingGraceMs) {
                        Log.i("BENSON_AUDIO", "TARGET_REACQUIRE_TIMEOUT expected=$expectedPackage reason=blocking fg=${env.foregroundPackage ?: "?"} elapsedMs=${System.currentTimeMillis() - start}")
                        return false
                    }
                }
            }
            delay(300)
        }
        Log.i("BENSON_AUDIO", "TARGET_REACQUIRE_TIMEOUT expected=$expectedPackage reason=budget elapsedMs=${System.currentTimeMillis() - start}")
        return false
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val anc = findClickableAncestor(node) ?: return false
        return anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private suspend fun awaitCondition(timeoutMs: Long, pollMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { if (cond()) return true } catch (_: Exception) {}
            delay(pollMs)
        }
        return false
    }

    // If WhatsApp opened straight into an individual chat, walk back to the chat list so search is
    // reachable. Bounded to 4 tries.
    private suspend fun reachWhatsAppChatList() {
        for (attempt in 1..4) {
            val hasSearch = findNodeMatching { n ->
                val vid = n.viewIdResourceName ?: ""
                vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/menuitem_search") ||
                    (n.isClickable && matchesAny(n, SEARCH_KEYWORDS))
            } != null
            if (hasSearch) return
            val inChat = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/entry") } != null
            if (!inChat) { delay(400); continue } // still loading
            val back = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/whatsapp_toolbar_home") && it.isClickable }
            if (back != null) clickNodeOrAncestor(back) else performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WA-FIX-1 (2026-09-09) — deterministic Chats-state normalisation before the search step.
    //
    // Root cause (ROUND_WA_DIAG_REPORT.md, verdict CAUSE_A_PLUS_COLLAPSED_HEADER): the selector
    // `com.whatsapp:id/search_bar_inner_layout` is unchanged and still valid, but it is the HEADER
    // ROW of the conversations RecyclerView (`android:id/list`). When the chat list is scrolled
    // down that row is recycled out of the accessibility tree, so every id/semantic selector for it
    // returns nothing → SEARCH_NOT_FOUND. Confirmed on device: the node exists (doc #20/169,
    // depth 19) whenever the list is at the top; absent when scrolled.
    //
    // Fix: before searching, put the Chats screen into a state where the header exists — assert
    // WhatsApp foreground, select the Chats tab if another tab is active, then scroll the
    // RecyclerView to the top (bounded, semantic ACTION_SCROLL_BACKWARD; no coordinate taps —
    // canPerformGestures="false"), re-reading the tree after each step until the header appears.
    //
    // Revert: WA_FIX1_NORMALIZE_SEARCH = false → runWhatsAppCallNative uses the pre-2026-09-09
    // reachWhatsAppChatList() + inline selector block only (kept verbatim below).
    // ══════════════════════════════════════════════════════════════════════════════════════════════
    private val WA_FIX1_NORMALIZE_SEARCH = true
    // WA-FIX-3 fast-burst scroll-to-top (ROUND_WA_FIX_2_REPORT.md: ACTION_SCROLL_BACKWARD works but
    // moves ~1 row/call, and one settle+tree-read per row made it ~0.8 s/row — too slow. Fire the
    // action in bursts with only a tiny inter-action delay, read the tree ONCE per burst.)
    private val WA_FIX3_BURST_SIZE = 8
    private val WA_FIX3_INTER_ACTION_DELAY_MS = 55L
    private val WA_FIX3_POST_BURST_SETTLE_MS = 250L
    private val WA_FIX3_MAX_TOTAL_SCROLL_ACTIONS = 120
    private val WA_FIX3_MAX_WALL_CLOCK_MS = 15_000L

    // The search affordance: primary id, its wrapper, and the ACC-1-proven broader variants
    // (all evidenced in the live hierarchy / ROUND_ACC1_REPORT.md). `menuitem_search` kept only
    // as one entry among several — it is absent on this WhatsApp build.
    private fun waSearchHeaderNode(): AccessibilityNodeInfo? = findNodeMatching { n ->
        val vid = n.viewIdResourceName ?: ""
        vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/my_search_bar") ||
            vid.endsWith("/menuitem_search") || vid.contains("search_bar") ||
            (n.contentDescription?.toString()?.lowercase()?.let { it.contains("meta ai") && it.contains("such") } == true)
    }

    // The conversations list — a scrollable RecyclerView carrying the well-known `android:id/list`.
    private fun waConversationsList(): AccessibilityNodeInfo? =
        findNodeMatching { n ->
            (n.viewIdResourceName ?: "") == "android:id/list" && n.isScrollable
        } ?: findNodeMatching { n ->
            n.isScrollable && (n.className?.toString()?.contains("RecyclerView") == true) &&
                (n.viewIdResourceName ?: "").endsWith("/list")
        }

    // Cheap "did the list move" probe — the first visible conversation row's name.
    private fun waFirstRowSignature(): String =
        findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/conversations_row_contact_name") && !it.text.isNullOrBlank() }
            ?.text?.toString()?.trim().orEmpty()

    // Active bottom-nav item renders its label as `*_large_label_view` and carries selected=true;
    // inactive items use `*_small_label_view`. Locale note: WhatsApp UI here is German — the Chats
    // tab label is still literally "Chats". Fallback: presence of the conversations RecyclerView
    // with rows (only the Chats tab has that) counts as "on Chats".
    private fun waChatsTabLabelNode(): AccessibilityNodeInfo? = findNodeMatching { n ->
        val vid = n.viewIdResourceName ?: ""
        vid.contains("navigation_bar_item") && vid.contains("label_view") &&
            (n.text ?: "").toString().trim().lowercase().let { it == "chats" || it == "chat" }
    }

    private fun waOnChatsTab(): Boolean {
        val label = waChatsTabLabelNode()
        if (label != null) {
            if ((label.viewIdResourceName ?: "").endsWith("large_label_view") || label.isSelected) return true
            var p = label.parent; var h = 0
            while (p != null && h < 6) { if (p.isSelected) { p.recycle(); return true }; val nx = p.parent; p.recycle(); p = nx; h++ }
            // a Chats label exists but not marked active → not on Chats
            return false
        }
        // no recognisable Chats label — fall back to structural evidence
        val hasList = findNodeMatching { (it.viewIdResourceName ?: "") == "android:id/list" } != null
        val hasRows = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/contact_row_container") } != null
        return hasList && (hasRows || waSearchHeaderNode() != null)
    }

    private fun waSelectChatsTab(): Boolean {
        val target = waChatsTabLabelNode()
            ?: findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/navigation_bar_item_icon_container") } // leftmost item = Chats in WhatsApp
            ?: return false
        return clickNodeOrAncestor(target)
    }

    private fun waScrollActionIds(node: AccessibilityNodeInfo): String =
        node.actionList.joinToString(",") { it.id.toString() }

    private fun waSupportsScrollToPosition(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id }

    // WA-FIX-3 (2026-09-09) — fast semantic scroll-to-top by BURSTS.
    // Evidence (ROUND_WA_FIX_2_REPORT.md): WhatsApp's `android:id/list` advertises only
    // SCROLL_FORWARD / SCROLL_BACKWARD (no SCROLL_TO_POSITION / SCROLL_UP / pageUp), and
    // `ACTION_SCROLL_BACKWARD` moves ~1 row per call. The old loop's one settle+tree-read per row
    // made it ~0.8 s/row → couldn't clear a deep list inside a call-appropriate budget.
    // Here: fire `ACTION_SCROLL_BACKWARD` in bursts of WA_FIX3_BURST_SIZE with only a tiny
    // inter-action delay and NO tree read between actions; after each burst do one bounded settle
    // and ONE tree read to check the header + a burst-level first-row signature. Success is ONLY
    // "search header present". Semantic AccessibilityNodeInfo actions only — no coordinates,
    // no dispatchGesture. The ACTION_SCROLL_TO_POSITION probe is kept (falls through instantly
    // on this build) as diagnostic evidence.
    private suspend fun waScrollChatsToTop(): Boolean {
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started

        var list = waConversationsList() ?: run {
            Log.i("BENSON_AUDIO", "WA_CHAT_LIST_FOUND state=missing"); return false
        }
        Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_ACTIONS supported=\"${waScrollActionIds(list)}\"")

        if (waSearchHeaderNode() != null) {
            Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER_RECOVERED method=already_present burst=0 totalActions=0 elapsedMs=${elapsed()}")
            return true
        }

        // Kept probe — jump to position 0 if the node ever advertises it (it does not on this build).
        if (waSupportsScrollToPosition(list)) {
            val args = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
            }
            val r = list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, args)
            Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_TO_POSITION attempted=true supported=true result=$r")
            val dl = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < dl) {
                delay(150)
                if (waSearchHeaderNode() != null) {
                    Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER_RECOVERED method=scroll_to_position burst=0 totalActions=1 elapsedMs=${elapsed()}")
                    return true
                }
            }
        } else {
            Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_TO_POSITION attempted=false supported=false result=false")
        }

        // Burst loop
        var totalActions = 0
        var burst = 0
        var unchangedBursts = 0
        var prevSig = waFirstRowSignature()

        while (totalActions < WA_FIX3_MAX_TOTAL_SCROLL_ACTIONS && elapsed() < WA_FIX3_MAX_WALL_CLOCK_MS) {
            if (waSearchHeaderNode() != null) {
                Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER_RECOVERED method=scroll_backward_burst burst=$burst totalActions=$totalActions elapsedMs=${elapsed()}")
                return true
            }
            list = waConversationsList() ?: run {
                // re-acquire once more before declaring the node invalid
                delay(150); waConversationsList()
            } ?: run {
                Log.i("BENSON_AUDIO", "WA_CHAT_LIST_FOUND state=missing")
                Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=scroll_action_refused")
                return false
            }

            burst++
            val burstStart = System.currentTimeMillis()
            val pre = prevSig
            var requested = 0
            var accepted = 0
            var refused = false
            while (requested < WA_FIX3_BURST_SIZE && totalActions < WA_FIX3_MAX_TOTAL_SCROLL_ACTIONS) {
                requested++
                totalActions++
                if (list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) accepted++
                else { refused = true; break }
                delay(WA_FIX3_INTER_ACTION_DELAY_MS)
            }
            delay(WA_FIX3_POST_BURST_SETTLE_MS)

            val headerNow = waSearchHeaderNode() != null
            val post = waFirstRowSignature()
            Log.i(
                "BENSON_AUDIO",
                "WA_CHAT_SCROLL_BURST burst=$burst requested=$requested accepted=$accepted totalActions=$totalActions " +
                    "pre=\"${pre.take(20)}\" post=\"${post.take(20)}\" elapsedMs=${System.currentTimeMillis() - burstStart}",
            )

            if (headerNow) {
                Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER_RECOVERED method=scroll_backward_burst burst=$burst totalActions=$totalActions elapsedMs=${elapsed()}")
                return true
            }

            if (refused && accepted == 0) {
                // Action refused with nothing accepted: re-acquire the list once and retry the burst once.
                delay(400)
                val fresh = waConversationsList()
                var retryAccepted = 0
                if (fresh != null) {
                    var k = 0
                    while (k < WA_FIX3_BURST_SIZE && totalActions < WA_FIX3_MAX_TOTAL_SCROLL_ACTIONS) {
                        k++; totalActions++
                        if (fresh.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) retryAccepted++ else break
                        delay(WA_FIX3_INTER_ACTION_DELAY_MS)
                    }
                    delay(WA_FIX3_POST_BURST_SETTLE_MS)
                }
                if (waSearchHeaderNode() != null) {
                    Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER_RECOVERED method=scroll_backward_burst burst=$burst totalActions=$totalActions elapsedMs=${elapsed()}")
                    return true
                }
                if (retryAccepted == 0 && waFirstRowSignature() == post) {
                    Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_PROGRESS state=stalled")
                    Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=scroll_action_refused")
                    return false
                }
                prevSig = waFirstRowSignature()
                continue
            }

            if (post != pre) {
                unchangedBursts = 0
                prevSig = post
                Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_PROGRESS state=progress")
            } else {
                unchangedBursts++
                if (unchangedBursts >= 2) {
                    Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_PROGRESS state=stalled")
                    Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=scroll_stalled")
                    return false
                }
                Log.i("BENSON_AUDIO", "WA_CHAT_SCROLL_PROGRESS state=stalled_candidate")
                prevSig = post
            }
        }

        Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=scroll_timeout")
        return false
    }

    // Puts the WhatsApp Chats screen into a state where `search_bar_inner_layout` exists in the
    // accessibility tree. Returns false (with a WA_SEARCH_NORMALIZE_FAIL reason) if it cannot.
    private suspend fun ensureWhatsAppChatsSearchAvailable(): Boolean {
        Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_START")

        val (fg, fgSrc, _) = foregroundIsPackage(WA_PKG)
        if (!fg) { Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=not_foreground src=$fgSrc"); return false }

        // Chats tab
        var onChats = waOnChatsTab()
        Log.i("BENSON_AUDIO", "WA_CHATS_TAB state=${if (onChats) "active" else "inactive"}")
        if (!onChats) {
            val sel = waSelectChatsTab()
            Log.i("BENSON_AUDIO", "WA_CHATS_TAB state=select ok=$sel")
            awaitCondition(2500, 150) { waOnChatsTab() && findNodeMatching { (it.viewIdResourceName ?: "") == "android:id/list" } != null }
            onChats = waOnChatsTab()
            if (!onChats) { Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=chats_tab_unreachable"); return false }
        }

        // not stuck inside an individual chat
        if (findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/entry") } != null) {
            val back = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/whatsapp_toolbar_home") && it.isClickable }
            if (back != null) clickNodeOrAncestor(back) else performGlobalAction(GLOBAL_ACTION_BACK)
            awaitCondition(2000, 150) { findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/entry") } == null }
        }

        if (waSearchHeaderNode() != null) {
            Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER state=visible")
            return true
        }
        Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER state=missing")

        val list = waConversationsList()
        if (list == null) {
            Log.i("BENSON_AUDIO", "WA_CHAT_LIST_FOUND state=missing")
            Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=no_recyclerview")
            return false
        }
        Log.i("BENSON_AUDIO", "WA_CHAT_LIST_FOUND state=found vid=${list.viewIdResourceName ?: "-"}")

        // waScrollChatsToTop() logs its own specific WA_SEARCH_NORMALIZE_FAIL reason on failure.
        if (waScrollChatsToTop()) {
            Log.i("BENSON_AUDIO", "WA_SEARCH_HEADER state=visible source=scrolled")
            return true
        }
        return false
    }

    // Finds the search affordance, clicks it, and returns the VERIFIED search-input node once the
    // search state is actually open — or null (with a WA_SEARCH_NORMALIZE_FAIL reason). Selector
    // cascade aligned with the ACC-1 proven matcher (id set + non-clickable desc tier). Bounded
    // re-click retry. No coordinate taps. Shared by runWhatsAppCallNative and the self-test.
    private suspend fun openWhatsAppSearch(): AccessibilityNodeInfo? {
        val searchNode = waitForNode(4000, 150, WA_PKG, "wa_search_affordance") { n ->
            val vid = n.viewIdResourceName ?: ""
            vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/my_search_bar") ||
                vid.endsWith("/menuitem_search") || vid.contains("search_bar")
        } ?: waitForNode(2000, 150, WA_PKG, "wa_search_affordance_sem") { n ->
            matchesAny(n, SEARCH_KEYWORDS) ||
                (n.contentDescription?.toString()?.lowercase()?.let { it.contains("such") || it.contains("meta ai") } == true)
        }
        if (searchNode == null) { Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=affordance_absent_after_normalize"); return null }
        Log.i("BENSON_AUDIO", "WA_SEARCH_CLICK viewId=${searchNode.viewIdResourceName?.substringAfterLast('/') ?: "-"} desc=\"${(searchNode.contentDescription ?: "").toString().take(32)}\"")

        var input: AccessibilityNodeInfo? = null
        for (attempt in 1..2) {
            var clicked = false
            for (a in 1..3) { if (clickNodeOrAncestor(searchNode)) { clicked = true; break }; delay(200) }
            if (!clicked && attempt == 2) { Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=click_rejected"); return null }
            input = waitForNode(3000, 120, WA_PKG, "wa_search_input") { n ->
                val vid = n.viewIdResourceName ?: ""
                vid.endsWith("/search_input") || vid.endsWith("/search_src_text")
            } ?: waitForNode(1200, 120, WA_PKG, "wa_search_input_edit") { it.isEditable && isSearchUiNode(it) }
              ?: waitForNode(800, 120, WA_PKG, "wa_search_input_any") { it.isEditable }
            if (input != null) break
            Log.i("BENSON_AUDIO", "WA_SEARCH_INPUT_READY state=retry attempt=$attempt")
            delay(300)
        }
        if (input == null) { Log.i("BENSON_AUDIO", "WA_SEARCH_NORMALIZE_FAIL reason=search_state_not_open"); return null }
        val focused = try { input.refresh(); input.isFocused } catch (_: Exception) { false }
        Log.i("BENSON_AUDIO", "WA_SEARCH_INPUT_READY state=open viewId=${input.viewIdResourceName?.substringAfterLast('/') ?: "-"} focused=$focused")
        return input
    }

    // WhatsApp conversation CONTACT IDENTITY — the header name TextView, and nothing else.
    // ROUND_WA_HEADER_FIX_1 (2026-09-10): the old version also accepted
    // `conversation_contact_status_holder` and fell back to "the first top-region TextView", so
    // right after a `whatsapp://send` deep link — while `conversation_contact_name` is still
    // binding — it returned the status / typing / "last seen" / message-preview line ("Du…").
    // That is contact-identity poison: the direct-call verifier then compared "Du…" to the
    // resolved name and refused a CORRECT conversation (ROUND_WA_HEADER_DIAG_1). A blank result
    // (caller retries) is always better than a wrong one — so: read ONLY
    // `com.whatsapp:id/conversation_contact_name`, no status node, no generic TextView fallback.
    private fun conversationTitleText(): String? =
        findNodeMatching { n ->
            (n.viewIdResourceName ?: "").endsWith("/conversation_contact_name") && !n.text.isNullOrBlank()
        }?.also { try { it.refresh() } catch (_: Exception) {} }
            ?.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun setTextOn(node: AccessibilityNodeInfo, value: String): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })

    suspend fun runWhatsAppCallNative(contactRaw: String): WhatsAppCallNativeResult =
        withContext(Dispatchers.Default) {
            val contact = contactRaw.trim()
            val t0 = System.currentTimeMillis()
            fun ms() = System.currentTimeMillis() - t0
            fun waLog(m: String) = Log.i("BENSON_AUDIO", m)
            fun ok(step: String, verifiedHeaderText: String? = null, nameMatch: Boolean = false): WhatsAppCallNativeResult {
                waLog("WA_NATIVE_END success=true step=$step elapsedMs=${ms()}")
                return WhatsAppCallNativeResult(true, step, null, contact, ms(), verifiedHeaderText, nameMatch)
            }
            fun fail(step: String, reason: String): WhatsAppCallNativeResult {
                waLog("WA_NATIVE_FAIL stage=$step reason=${reason.replace("\n", " ").take(200)}")
                return WhatsAppCallNativeResult(false, step, reason, contact, ms())
            }

            waLog("WA_NATIVE_START contact=\"$contact\"")
            waLog("WA_CALL_STATE state=CALL_STARTING contact=\"$contact\"")
            if (contact.isEmpty()) return@withContext fail("VALIDATE", "empty contact")

            whatsappAutomationActive = true
            try {
                // ── 1. LAUNCH ──────────────────────────────────────────────────────────────────
                val launched = launchWhatsApp()
                waLog("WA_NATIVE_LAUNCH ok=$launched")
                if (!launched) return@withContext fail("LAUNCH", "getLaunchIntentForPackage/startActivity failed")

                // ── 2. PACKAGE (overlay-aware, real check) ─────────────────────────────────────
                var pkgOk = false; var pkgSrc = "none"; var winN = 0
                run {
                    val deadline = System.currentTimeMillis() + 6000
                    while (System.currentTimeMillis() < deadline) {
                        val (f, s, n) = foregroundIsPackage(WA_PKG)
                        winN = n
                        if (f) { pkgOk = true; pkgSrc = s; break }
                        delay(200)
                    }
                }
                waLog("WA_NATIVE_PACKAGE found=$pkgOk source=$pkgSrc windows=$winN")
                if (!pkgOk) return@withContext fail("PACKAGE", "WhatsApp not foreground (source=$pkgSrc windows=$winN)")

                // ── 3-6. SEARCH — WA-FIX-1: normalise the Chats list so the search header exists,
                // then find + click + verify-open via the shared helpers. `search_bar_inner_layout`
                // is a scroll-away RecyclerView row (ROUND_WA_DIAG_REPORT.md). Downstream steps
                // (SET TEXT / contact match / call button / verify) are unchanged.
                // Revert: WA_FIX1_NORMALIZE_SEARCH = false → the pre-2026-09-09 block (kept below).
                val searchInput: AccessibilityNodeInfo
                if (WA_FIX1_NORMALIZE_SEARCH) {
                    if (!ensureWhatsAppChatsSearchAvailable()) {
                        dumpScreenForDebug("wa_native_search_normalize")
                        return@withContext fail("SEARCH_HEADER_NOT_RECOVERED", "WhatsApp Chats could not be normalised to expose com.whatsapp:id/search_bar_inner_layout")
                    }
                    searchInput = openWhatsAppSearch() ?: run {
                        dumpScreenForDebug("wa_native_search")
                        return@withContext fail("SEARCH_NOT_FOUND", "search affordance not found or search did not open after chat-list normalisation")
                    }
                } else {
                    reachWhatsAppChatList()
                    // ── 3-4. SEARCH — viewId search_bar_inner_layout first, then semantic ──────────
                    val searchNode = waitForNode(4000, 150, WA_PKG, "wa_native_search") { n ->
                        val vid = n.viewIdResourceName ?: ""
                        vid.endsWith("/search_bar_inner_layout") || vid.endsWith("/menuitem_search")
                    } ?: waitForNode(2000, 150, WA_PKG, "wa_native_search_sem") { n ->
                        n.isClickable && (matchesAny(n, SEARCH_KEYWORDS) ||
                            (n.contentDescription?.toString()?.lowercase()?.contains("such") == true))
                    }
                    if (searchNode == null) { dumpScreenForDebug("wa_native_search"); return@withContext fail("SEARCH_NOT_FOUND", "no search_bar_inner_layout / menuitem_search / semantic search node") }
                    waLog("WA_NATIVE_SEARCH_FOUND viewId=${searchNode.viewIdResourceName ?: "-"} desc=\"${(searchNode.contentDescription ?: "").toString().take(40)}\"")
                    // ── 5. SEARCH CLICK ──────────────────────────────────────────────────────────────
                    var searchTap = false
                    for (a in 1..3) { if (clickNodeOrAncestor(searchNode)) { searchTap = true; break }; delay(200) }
                    waLog("WA_NATIVE_SEARCH_CLICK ok=$searchTap")
                    if (!searchTap) return@withContext fail("SEARCH_CLICK", "ACTION_CLICK rejected on search node/ancestor")
                    // ── 6. SEARCH VERIFY — search input appeared ──────────────────────────────────
                    val si = waitForNode(3000, 120, WA_PKG, "wa_native_search_input") { n ->
                        val vid = n.viewIdResourceName ?: ""
                        vid.endsWith("/search_input") || vid.endsWith("/search_src_text")
                    } ?: waitForNode(1500, 120, WA_PKG, "wa_native_search_edit") { it.isEditable && isSearchUiNode(it) }
                      ?: waitForNode(1000, 120, WA_PKG, "wa_native_any_edit") { it.isEditable }
                    waLog("WA_NATIVE_SEARCH_VERIFY ok=${si != null} viewId=${si?.viewIdResourceName ?: "-"}")
                    if (si == null) { dumpScreenForDebug("wa_native_search_open"); return@withContext fail("SEARCH_NOT_OPEN", "search input did not appear after tapping search") }
                    searchInput = si
                }

                // ── 7. SET TEXT — exact contact from the mission ──────────────────────────────
                val setOk = setTextOn(searchInput, contact)
                waLog("WA_NATIVE_SET_TEXT ok=$setOk text=\"$contact\"")
                if (!setOk) return@withContext fail("SET_TEXT", "ACTION_SET_TEXT rejected")

                // ── 8. VERIFY TEXT — input reflects the contact ──────────────────────────────
                val textOk = awaitCondition(2500, 150) {
                    searchInput.refresh()
                    val cur = searchInput.text?.toString()?.trim().orEmpty()
                    cur.equals(contact, true) || normPhon(cur) == normPhon(contact) || cur.contains(contact, true)
                }
                waLog("WA_NATIVE_SET_TEXT_VERIFY ok=$textOk cur=\"${searchInput.text?.toString()?.take(40) ?: ""}\"")
                if (!textOk) return@withContext fail("TEXT_MISMATCH", "search input did not reflect \"$contact\"")

                // ── 9-10. CONTACT MATCH — full name first, 3-char prefix fallback ────────────
                val rowFilter: (AccessibilityNodeInfo) -> Boolean = {
                    !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it)
                }
                var matchTier = ""
                fun rowPredicate(n: AccessibilityNodeInfo): Boolean {
                    if (!rowFilter(n)) return false
                    val lbl = nodeLabel(n)
                    if (lbl.isBlank()) return false
                    return when {
                        normPhon(lbl) == normPhon(contact) -> { matchTier = "exact"; true }
                        wholeLabelPhoneticEquals(lbl, contact) -> { matchTier = "normalized"; true }
                        phoneticNameMatch(lbl, contact) -> { matchTier = "phonetic"; true }
                        else -> false
                    }
                }
                var row = waitForNode(5000, 150, WA_PKG, "wa_native_row") { rowPredicate(it) }
                if (row == null) {
                    val prefix = normPhon(contact).take(3).ifEmpty { contact.take(3) }
                    waLog("WA_NATIVE_SET_TEXT retry=1 text=\"$prefix\"")
                    val si = waitForNode(2000, 150, WA_PKG, "wa_native_input_retry") {
                        (it.viewIdResourceName ?: "").endsWith("/search_input") || (it.isEditable && isSearchUiNode(it))
                    } ?: searchInput
                    setTextOn(si, prefix)
                    delay(500)
                    row = waitForNode(4000, 150, WA_PKG, "wa_native_row_retry") { rowPredicate(it) }
                }
                if (row == null) { dumpScreenForDebug("wa_native_contact"); return@withContext fail("CONTACT_NOT_FOUND", "no result row phonetically matched \"$contact\"") }
                waLog("WA_NATIVE_CONTACT_MATCH tier=$matchTier label=\"${nodeLabel(row).take(40)}\"")

                // ── 10. CONTACT CLICK — re-resolve FRESH before each attempt ─────────────────
                var contactClicked = false
                for (attempt in 1..3) {
                    val fresh = waitForNode(900, 150, WA_PKG, "wa_native_row_fresh") { n ->
                        rowFilter(n) && (wholeLabelPhoneticEquals(nodeLabel(n), contact) ||
                            phoneticNameMatch(nodeLabel(n), contact) || normPhon(nodeLabel(n)) == normPhon(contact))
                    } ?: row
                    if (clickNodeOrAncestor(fresh)) { contactClicked = true; break }
                    delay(250)
                }
                waLog("WA_NATIVE_CONTACT_CLICK ok=$contactClicked")
                if (!contactClicked) return@withContext fail("CONTACT_CLICK", "ACTION_CLICK rejected on result row after 3 attempts")

                // ── 11. CHAT VERIFY — chat open + header IS the requested contact ────────────
                val entry = waitForNode(3500, 150, WA_PKG, "wa_native_entry") { (it.viewIdResourceName ?: "").endsWith("/entry") }
                if (entry == null) { dumpScreenForDebug("wa_native_chat"); return@withContext fail("CHAT_NOT_OPEN", "compose field (id/entry) not present after selecting contact") }
                var header: String? = null
                awaitCondition(2500, 150) { header = conversationTitleText(); !header.isNullOrBlank() }
                val headerMatch = !header.isNullOrBlank() && (
                    normPhon(header!!) == normPhon(contact) ||
                    wholeLabelPhoneticEquals(header!!, contact) ||
                    phoneticNameMatch(header!!, contact))
                waLog("WA_NATIVE_CHAT_VERIFY ok=$headerMatch header=\"${(header ?: "?").take(40)}\"")
                if (header.isNullOrBlank()) return@withContext fail("CHAT_VERIFY", "conversation header not readable")
                if (!headerMatch) return@withContext fail("CHAT_WRONG_CONTACT", "chat header \"$header\" != \"$contact\" — not calling")

                // ── 12. CALL BUTTON — viewId → contentDescription → semantic (voice, not video) ─
                val maxTop = headerRegionMaxTop()
                val callNode = waitForNode(3000, 150, WA_PKG, "wa_native_call_id") { n ->
                    val vid = n.viewIdResourceName ?: ""
                    (vid.endsWith("/menuitem_call") || vid.endsWith("/voip_call")) && !vid.contains("video")
                } ?: waitForNode(1800, 150, WA_PKG, "wa_native_call_desc") { n ->
                    val b = Rect(); n.getBoundsInScreen(b)
                    val d = n.contentDescription?.toString()?.lowercase() ?: ""
                    n.isClickable && b.top <= maxTop && d.isNotEmpty() && d != "video" &&
                        (d.contains("sprachanruf") || d.contains("voice call") || d.contains("apel vocal") || d.contains("anrufen")) &&
                        !d.contains("video")
                } ?: waitForNode(1500, 150, WA_PKG, "wa_native_call_sem") { n ->
                    val b = Rect(); n.getBoundsInScreen(b)
                    n.isClickable && b.top <= maxTop && matchesAny(n, CALL_KEYWORDS) && !matchesAny(n, VIDEO_EXCLUDE_KEYWORDS)
                }
                if (callNode == null) { dumpScreenForDebug("wa_native_call"); return@withContext fail("CALL_NOT_FOUND", "voice-call button not found (viewId/contentDesc/semantic all missed)") }
                if (isPaymentSensitive(callNode)) return@withContext fail("CALL_BLOCKED", "call node matched payment-sensitive pattern")
                waLog("WA_NATIVE_CALL_FOUND viewId=${callNode.viewIdResourceName ?: "-"} desc=\"${(callNode.contentDescription ?: "").toString().take(40)}\"")

                // ── 13. CALL CLICK ─────────────────────────────────────────────────────────────
                var callClicked = false
                for (a in 1..2) { if (clickNodeOrAncestor(callNode)) { callClicked = true; break }; delay(250) }
                waLog("WA_NATIVE_CALL_CLICK ok=$callClicked")
                if (!callClicked) return@withContext fail("CALL_CLICK", "ACTION_CLICK rejected on call button")

                // ── 14. CALL VERIFY — WhatsApp call screen up + correct contact ─────────────
                delay(1500)
                var callScreen = false; var callName: String? = null
                run {
                    val deadline = System.currentTimeMillis() + 7000
                    while (System.currentTimeMillis() < deadline) {
                        val screen = findNodeMatching { n ->
                            val vid = n.viewIdResourceName ?: ""
                            vid.endsWith("/call_screen") || vid.endsWith("/end_call_button") ||
                                vid.endsWith("/audio_route_button") || vid.endsWith("/call_screen_header_view") ||
                                vid.contains("voip_") || vid.endsWith("/call_controls_card")
                        }
                        if (screen != null) {
                            callScreen = true
                            callName = findNodeMatching { n ->
                                val vid = n.viewIdResourceName ?: ""
                                (vid.endsWith("/name") || vid.endsWith("/title") || vid.endsWith("/contact_name") ||
                                    vid.endsWith("/call_screen_contact_name") || vid.endsWith("/subtitle")) &&
                                    !n.text.isNullOrBlank() && (n.text!!.length in 1..40)
                            }?.text?.toString()?.trim()
                            break
                        }
                        delay(250)
                    }
                }
                val nameOk = callName.isNullOrBlank() || normPhon(callName!!) == normPhon(contact) ||
                    wholeLabelPhoneticEquals(callName!!, contact) || phoneticNameMatch(callName!!, contact)
                val verifyOk = callScreen && nameOk
                // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — nameOk is also true when callName is blank
                // (lenient pass-through, not a real read) — only bridge the identity when a real
                // header text was actually read AND phonetically matched.
                val identityVerified = !callName.isNullOrBlank() && nameOk
                waLog("WA_NATIVE_CALL_VERIFY success=$verifyOk screen=$callScreen name=\"${callName ?: "?"}\" nameMatch=$nameOk")
                if (!callScreen) return@withContext fail("CALL_NOT_STARTED", "WhatsApp call screen did not appear after tapping call")
                if (!nameOk) return@withContext fail("CALL_WRONG_CONTACT", "call screen shows \"$callName\" not \"$contact\"")

                // WA-NATIVE post-call rule (2026-09-08, product-owner-directed): the WhatsApp call is
                // now verified LIVE on screen. From this point the executor performs ZERO further UI
                // actions on WhatsApp — no ACTION_CLICK, no performGlobalAction(BACK/HOME), no re-tap,
                // no retry, no UI cleanup. It returns immediately below. BENSON's own Activity stays
                // in the background; the call keeps running until the user or the other party ends it.
                //
                // Two detached tasks run on serviceScope (survive BENSON being backgrounded):
                //  - the mic/audio hold flag, so BENSON's conversation-mode / wake STT loop does NOT
                //    re-acquire the mic + audio focus mid-call (that was dropping the call). The
                //    180s is only a failsafe — the lifecycle watcher clears it the instant it
                //    structurally confirms the call ended.
                //  - watchWhatsAppCallLifecycle(): STRUCTURAL, debounced call-end detection, then
                //    release the hold and fire an explicit Intent back to BENSON's MainActivity
                //    (AUTO-RETURN-AFTER-CALL). No BACK/HOME/gesture/click.
                whatsappCallMicHoldUntilMs = System.currentTimeMillis() + 180_000L
                Log.i("BENSON_AUDIO", "WA_NATIVE_POST_CALL_ACTION action=mic_hold_armed holdMs=180000")
                Log.i("BENSON_AUDIO", "WA_CALL_AUDIO_HOLD state=armed holdMs=180000")
                // WA-LIFECYCLE-FIX-1 — persist BEFORE relying on the serviceScope watcher, so a
                // service death mid-call is recoverable on the next onServiceConnected.
                try { waCallPersistState(this@BensonAccessibilityService, WA_CALL_STATE_ACTIVE, contact) } catch (_: Exception) {}
                val verifiedAt = System.currentTimeMillis()
                val obsContact = contact
                serviceScope.launch { watchWhatsAppCallLifecycle(obsContact, verifiedAt) }

                return@withContext ok("CALL_VERIFIED", verifiedHeaderText = if (identityVerified) callName else null, nameMatch = identityVerified)
            } catch (e: Exception) {
                return@withContext fail("EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                whatsappAutomationActive = false
            }
        }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WA-FIX-4 (2026-09-09) — DIRECT-CONTACT-DEEPLINK call route (ROUND_WA_ROUTE_DIAG_REPORT.md).
    //
    // JS has already resolved the spoken name to a phone number from the local address book. This
    // opens the exact conversation via `whatsapp://send?phone=<digits>` (handler
    // com.whatsapp/.TextAndDirectChatDeepLink, verified on device) — NO Chats list, NO scroll, NO
    // search_bar_inner_layout — then verifies the conversation is the expected contact and reuses
    // the proven call-button + call-screen-verify + post-call mic-hold sequence.
    //
    // Steps 11–14 + the post-call hold below MIRROR runWhatsAppCallNative (device-proven 2026-09-08,
    // RUNs 1/2/6/9). Kept as a parallel copy on purpose — the proven runWhatsAppCallNative is not
    // refactored. Keep the two in sync; do not share-refactor without a 5/5 device pass.
    // ══════════════════════════════════════════════════════════════════════════════════════════════
    suspend fun runWhatsAppOpenConversationCall(phoneRaw: String, expectedNameRaw: String): WhatsAppCallNativeResult =
        withContext(Dispatchers.Default) {
            val phone = phoneRaw.filter { it.isDigit() }
            val contact = expectedNameRaw.trim()
            val t0 = System.currentTimeMillis()
            fun ms() = System.currentTimeMillis() - t0
            fun waLog(m: String) = Log.i("BENSON_AUDIO", m)
            fun mask(p: String) = if (p.length <= 4) "****" else "***" + p.takeLast(4)
            fun ok(step: String, verifiedHeaderText: String? = null, nameMatch: Boolean = false): WhatsAppCallNativeResult {
                waLog("WA_DIRECT_END success=true step=$step elapsedMs=${ms()}")
                return WhatsAppCallNativeResult(true, step, null, contact, ms(), verifiedHeaderText, nameMatch)
            }
            fun fail(step: String, reason: String): WhatsAppCallNativeResult {
                waLog("WA_DIRECT_FAIL stage=$step reason=${reason.replace("\n", " ").take(200)}")
                return WhatsAppCallNativeResult(false, step, reason, contact, ms())
            }

            waLog("WA_DIRECT_START phone=${mask(phone)} name=\"$contact\"")
            waLog("WA_CALL_STATE state=CALL_STARTING contact=\"$contact\"")
            if (phone.length < 6) return@withContext fail("WHATSAPP_DEEPLINK_FAILED", "phone too short after normalisation")

            whatsappAutomationActive = true
            try {
                // ── 1. DEEP LINK — exact conversation, no UI navigation ────────────────────────
                waLog("WA_DIRECT_DEEPLINK_START")
                val launched = try {
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("whatsapp://send?phone=$phone")).apply {
                        setPackage(WA_PKG)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(i); true
                } catch (e: Exception) {
                    waLog("WA_DIRECT_DEEPLINK_EXC ${e.javaClass.simpleName}: ${e.message}"); false
                }
                waLog("WA_DIRECT_DEEPLINK_RESULT launched=$launched")
                if (!launched) return@withContext fail("WHATSAPP_DEEPLINK_FAILED", "startActivity(whatsapp://send) failed")

                // ── 2. WAIT — WhatsApp reacquired as the environment target (RUNTIME LAYER v1:
                // a notification / SystemUI overlay / IME popping right after the deep link is a
                // TEMPORARY_INTERRUPTION, not a launch failure) ───────────────────────────────
                val pkgOk = foregroundIsPackage(WA_PKG).first || awaitTargetReacquired(WA_PKG, 8000L)
                if (!pkgOk) return@withContext fail("WHATSAPP_DEEPLINK_FAILED", "WhatsApp did not reach the foreground after the deep link")
                val entry = waitForNode(9000, 200, WA_PKG, "wa_direct_entry") { (it.viewIdResourceName ?: "").endsWith("/entry") }
                if (entry == null) {
                    dumpScreenForDebug("wa_direct_no_conversation")
                    return@withContext fail("WHATSAPP_CONTACT_VERIFY_FAILED", "no conversation opened (id/entry absent) — number may not be on WhatsApp")
                }

                // ── 3. VERIFY the conversation IS the expected contact ────────────────────────
                // ROUND_WA_HEADER_FIX_1 — `conversation_contact_name` binds a beat after a
                // whatsapp://send deep link. Poll it for up to 6s, re-reading each pass; break the
                // instant the name OR the digit criterion matches. conversationTitleText() now
                // returns ONLY the name node (never status/preview), so a non-blank result here is
                // always a real identity candidate. No match after 6s → DO NOT CALL.
                var h = ""
                var nameMatch = false
                var digitsMatch = false
                var nameAppearedMs = -1L
                val vStart = System.currentTimeMillis()
                run {
                    val deadline = vStart + 6000
                    while (System.currentTimeMillis() < deadline) {
                        val cur = conversationTitleText()?.trim().orEmpty()
                        if (cur.isNotBlank()) {
                            if (nameAppearedMs < 0) nameAppearedMs = System.currentTimeMillis() - vStart
                            h = cur
                            nameMatch = contact.isNotEmpty() && (
                                normPhon(h) == normPhon(contact) ||
                                    wholeLabelPhoneticEquals(h, contact) ||
                                    phoneticNameMatch(h, contact))
                            val hd = h.filter { it.isDigit() }
                            digitsMatch = hd.length >= 6 &&
                                (phone.endsWith(hd.takeLast(9)) || hd.endsWith(phone.takeLast(9)))
                            if (nameMatch || digitsMatch) break
                        }
                        delay(150)
                    }
                }
                val verified = nameMatch || digitsMatch
                waLog("WA_DIRECT_CONVERSATION_VERIFY status=${if (verified) "verified" else "failed"} header=\"${h.take(40)}\" nameAppearedMs=$nameAppearedMs nameMatch=$nameMatch digitsMatch=$digitsMatch elapsedMs=${System.currentTimeMillis() - vStart}")
                if (!verified) {
                    dumpScreenForDebug("wa_direct_verify")
                    return@withContext fail(
                        "WHATSAPP_CONTACT_VERIFY_FAILED",
                        if (h.isBlank()) "conversation_contact_name never rendered within 6s"
                        else "conversation header \"$h\" does not match \"$contact\"",
                    )
                }

                // ── 4. CALL BUTTON — MIRRORS runWhatsAppCallNative step 12 ────────────────────
                waLog("WA_DIRECT_CALL_BUTTON")
                val maxTop = headerRegionMaxTop()
                val callNode = waitForNode(3000, 150, WA_PKG, "wa_direct_call_id") { n ->
                    val vid = n.viewIdResourceName ?: ""
                    (vid.endsWith("/menuitem_call") || vid.endsWith("/voip_call")) && !vid.contains("video")
                } ?: waitForNode(1800, 150, WA_PKG, "wa_direct_call_desc") { n ->
                    val b = Rect(); n.getBoundsInScreen(b)
                    val d = n.contentDescription?.toString()?.lowercase() ?: ""
                    n.isClickable && b.top <= maxTop && d.isNotEmpty() && d != "video" &&
                        (d.contains("sprachanruf") || d.contains("voice call") || d.contains("apel vocal") || d.contains("anrufen")) &&
                        !d.contains("video")
                } ?: waitForNode(1500, 150, WA_PKG, "wa_direct_call_sem") { n ->
                    val b = Rect(); n.getBoundsInScreen(b)
                    n.isClickable && b.top <= maxTop && matchesAny(n, CALL_KEYWORDS) && !matchesAny(n, VIDEO_EXCLUDE_KEYWORDS)
                }
                if (callNode == null) { dumpScreenForDebug("wa_direct_call"); return@withContext fail("CALL_BUTTON_NOT_FOUND", "voice-call button not found in the verified conversation") }
                if (isPaymentSensitive(callNode)) return@withContext fail("CALL_BUTTON_NOT_FOUND", "call node matched payment-sensitive pattern")

                var callClicked = false
                for (a in 1..2) { if (clickNodeOrAncestor(callNode)) { callClicked = true; break }; delay(250) }
                waLog("WA_DIRECT_CALL_CLICK ok=$callClicked")
                if (!callClicked) return@withContext fail("CALL_START_FAILED", "ACTION_CLICK rejected on call button")

                // ── 5. CALL VERIFY — MIRRORS runWhatsAppCallNative step 14 ───────────────────
                delay(1500)
                var callScreen = false; var callName: String? = null
                run {
                    val deadline = System.currentTimeMillis() + 7000
                    while (System.currentTimeMillis() < deadline) {
                        val screen = findNodeMatching { n ->
                            val vid = n.viewIdResourceName ?: ""
                            vid.endsWith("/call_screen") || vid.endsWith("/end_call_button") ||
                                vid.endsWith("/audio_route_button") || vid.endsWith("/call_screen_header_view") ||
                                vid.contains("voip_") || vid.endsWith("/call_controls_card")
                        }
                        if (screen != null) {
                            callScreen = true
                            callName = findNodeMatching { n ->
                                val vid = n.viewIdResourceName ?: ""
                                (vid.endsWith("/name") || vid.endsWith("/title") || vid.endsWith("/contact_name") ||
                                    vid.endsWith("/call_screen_contact_name") || vid.endsWith("/subtitle")) &&
                                    !n.text.isNullOrBlank() && (n.text!!.length in 1..40)
                            }?.text?.toString()?.trim()
                            break
                        }
                        delay(250)
                    }
                }
                val nameOk = callName.isNullOrBlank() || normPhon(callName!!) == normPhon(contact) ||
                    wholeLabelPhoneticEquals(callName!!, contact) || phoneticNameMatch(callName!!, contact)
                val verifyOk = callScreen && nameOk
                waLog("WA_DIRECT_CALL_VERIFY success=$verifyOk screen=$callScreen name=\"${callName ?: "?"}\" nameMatch=$nameOk")
                // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — the earlier chat-header check (h/nameMatch,
                // WA_DIRECT_CONVERSATION_VERIFY, above) is the reliable "real header text was read"
                // signal for this function — the call-screen's own callName can be blank yet still
                // pass nameOk's lenient OR.
                if (!callScreen) return@withContext fail("CALL_VERIFY_FAILED", "WhatsApp call screen did not appear after tapping call")
                if (!nameOk) return@withContext fail("CALL_VERIFY_FAILED", "call screen shows \"$callName\" not \"$contact\"")

                // ── 6. POST-CALL — MIRRORS runWhatsAppCallNative: arm mic-hold + lifecycle watcher ─
                whatsappCallMicHoldUntilMs = System.currentTimeMillis() + 180_000L
                Log.i("BENSON_AUDIO", "WA_NATIVE_POST_CALL_ACTION action=mic_hold_armed holdMs=180000")
                Log.i("BENSON_AUDIO", "WA_CALL_AUDIO_HOLD state=armed holdMs=180000")
                // WA-LIFECYCLE-FIX-1 — persist BEFORE relying on the serviceScope watcher, so a
                // service death mid-call is recoverable on the next onServiceConnected.
                try { waCallPersistState(this@BensonAccessibilityService, WA_CALL_STATE_ACTIVE, contact) } catch (_: Exception) {}
                val verifiedAt = System.currentTimeMillis()
                val obsContact = contact
                serviceScope.launch { watchWhatsAppCallLifecycle(obsContact, verifiedAt) }

                return@withContext ok("CALL_VERIFIED", verifiedHeaderText = if (nameMatch) h else null, nameMatch = nameMatch)
            } catch (e: Exception) {
                return@withContext fail("EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                whatsappAutomationActive = false
            }
        }

    // ── ROUND_WA_GOVERNANCE_WRITE_1 — PHASE A ────────────────────────────────────────────────────
    // RESOLVE_CONTACT (JS) → OPEN_CHAT → VERIFY_CHAT → FIND_MESSAGE_INPUT → TYPE_MESSAGE →
    // VERIFY_TYPED_TEXT → WAIT_CONFIRMATION. STOPS before send. Reuses the verified
    // whatsapp://send?phone= direct-chat route + the WA-HEADER-FIX-1 identity poll — copied inline
    // (not shared) so the proven placeCall path stays byte-identical. Idempotent per missionId:
    // refuses to re-open/retype a mission that already reached SEND.
    suspend fun runWhatsAppOpenConversationType(
        phoneRaw: String, expectedNameRaw: String, messageRaw: String, missionId: String,
    ): WhatsAppCallNativeResult = withContext(Dispatchers.Default) {
        val phone = phoneRaw.filter { it.isDigit() }
        val contact = expectedNameRaw.trim()
        val message = messageRaw
        val want = message.trim()
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        fun waLog(m: String) = Log.i("BENSON_AUDIO", m)
        fun mask(p: String) = if (p.length <= 4) "****" else "***" + p.takeLast(4)
        fun ok(step: String, verifiedHeaderText: String? = null, nameMatch: Boolean = false): WhatsAppCallNativeResult {
            waLog("WA_WRITE_END success=true step=$step elapsedMs=${ms()}")
            return WhatsAppCallNativeResult(true, step, null, contact, ms(), verifiedHeaderText, nameMatch)
        }
        fun fail(step: String, reason: String): WhatsAppCallNativeResult {
            waLog("WA_WRITE_FAIL stage=$step reason=${reason.replace("\n", " ").take(200)}")
            return WhatsAppCallNativeResult(false, step, reason, contact, ms())
        }

        waLog("WA_WRITE_START mission=$missionId phone=${mask(phone)} name=\"$contact\" msgLen=${want.length}")
        if (missionId.isBlank()) return@withContext fail("RESOLVE_CONTACT", "missing mission id")
        if (phone.length < 6) return@withContext fail("RESOLVE_CONTACT", "phone too short after normalisation")
        if (want.isBlank()) return@withContext fail("TYPE_MESSAGE", "empty message")

        // Idempotency across recovery / re-entry — never type twice for one mission.
        val prior = waWriteRead(this@BensonAccessibilityService)
        if (prior.missionId == missionId) {
            when (prior.state) {
                WA_WRITE_SEND_ATTEMPTED, WA_WRITE_SENT_VERIFIED ->
                    return@withContext fail("TYPE_MESSAGE", "mission $missionId already at ${prior.state} — refusing to retype")
                WA_WRITE_TYPED_VERIFIED, WA_WRITE_WAITING_CONFIRMATION ->
                    if (prior.msgHash == want.hashCode()) {
                        waLog("WA_WRITE_TEXT_TYPED ok=true note=idempotent_reentry")
                        waLog("WA_WRITE_TEXT_VERIFIED ok=true note=idempotent_reentry")
                        waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_WAITING_CONFIRMATION, want.hashCode())
                        waLog("WA_WRITE_WAIT_CONFIRMATION mission=$missionId note=idempotent_reentry")
                        return@withContext ok("TYPED_VERIFIED")
                    }
                else -> {}
            }
        }

        whatsappAutomationActive = true
        try {
            // 1. OPEN_CHAT — the verified direct-chat route, explicit com.whatsapp
            waLog("WA_WRITE_DEEPLINK_START")
            val launched = try {
                val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("whatsapp://send?phone=$phone")).apply {
                    setPackage(WA_PKG); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i); true
            } catch (e: Exception) {
                waLog("WA_WRITE_DEEPLINK_EXC ${e.javaClass.simpleName}: ${e.message}"); false
            }
            if (!launched) return@withContext fail("OPEN_CHAT", "startActivity(whatsapp://send) failed")

            val pkgOk = foregroundIsPackage(WA_PKG).first || awaitTargetReacquired(WA_PKG, 8000L)
            if (!pkgOk) return@withContext fail("OPEN_CHAT", "WhatsApp did not reach the foreground after the deep link")
            val entry0 = waitForNode(9000, 200, WA_PKG, "wa_write_entry") { (it.viewIdResourceName ?: "").endsWith("/entry") }
            if (entry0 == null) {
                dumpScreenForDebug("wa_write_no_conversation")
                return@withContext fail("OPEN_CHAT", "no conversation opened (id/entry absent) — number may not be on WhatsApp")
            }

            // 2. VERIFY_CHAT — identity BEFORE typing (mirrors WA-HEADER-FIX-1)
            var h = ""; var nameMatch = false; var digitsMatch = false; var nameAppearedMs = -1L
            val vStart = System.currentTimeMillis()
            run {
                val deadline = vStart + 6000
                while (System.currentTimeMillis() < deadline) {
                    val cur = conversationTitleText()?.trim().orEmpty()
                    if (cur.isNotBlank()) {
                        if (nameAppearedMs < 0) nameAppearedMs = System.currentTimeMillis() - vStart
                        h = cur
                        nameMatch = contact.isNotEmpty() && (
                            normPhon(h) == normPhon(contact) ||
                                wholeLabelPhoneticEquals(h, contact) ||
                                phoneticNameMatch(h, contact))
                        val hd = h.filter { it.isDigit() }
                        digitsMatch = hd.length >= 6 &&
                            (phone.endsWith(hd.takeLast(9)) || hd.endsWith(phone.takeLast(9)))
                        if (nameMatch || digitsMatch) break
                    }
                    delay(150)
                }
            }
            val verified = nameMatch || digitsMatch
            waLog("WA_WRITE_CHAT_VERIFIED status=${if (verified) "verified" else "failed"} header=\"${h.take(40)}\" nameAppearedMs=$nameAppearedMs nameMatch=$nameMatch digitsMatch=$digitsMatch elapsedMs=${System.currentTimeMillis() - vStart}")
            if (!verified) {
                dumpScreenForDebug("wa_write_verify")
                return@withContext fail(
                    "VERIFY_CHAT",
                    if (h.isBlank()) "conversation_contact_name never rendered within 6s"
                    else "conversation header \"$h\" does not match \"$contact\"",
                )
            }

            // 3. FIND_MESSAGE_INPUT — stable view-id only, never coordinates
            val input = waitForNode(4000, 150, WA_PKG, "wa_write_input") { n ->
                (n.viewIdResourceName ?: "").endsWith("/entry") && n.isEditable
            } ?: waitForNode(1500, 150, WA_PKG, "wa_write_input_any") {
                it.isEditable && (it.viewIdResourceName ?: "").endsWith("/entry")
            }
            if (input == null) {
                dumpScreenForDebug("wa_write_no_input")
                return@withContext fail("FIND_MESSAGE_INPUT", "compose field com.whatsapp:id/entry not found")
            }
            waLog("WA_WRITE_INPUT_FOUND viewId=${input.viewIdResourceName}")

            // 4. TYPE_MESSAGE — exact text, one ACTION_SET_TEXT
            val setOk = setTextOn(input, message)
            waLog("WA_WRITE_TEXT_TYPED ok=$setOk")
            if (!setOk) {
                dumpScreenForDebug("wa_write_settext_failed")
                return@withContext fail("TYPE_MESSAGE", "ACTION_SET_TEXT rejected on compose field")
            }

            // 5. VERIFY_TYPED_TEXT — read the field back, exact match
            var typedBack = ""
            run {
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val n = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/entry") && it.isEditable }
                        ?.also { try { it.refresh() } catch (_: Exception) {} }
                    typedBack = n?.text?.toString()?.trim().orEmpty()
                    if (typedBack == want) break
                    delay(150)
                }
            }
            val textOk = typedBack == want
            waLog("WA_WRITE_TEXT_VERIFIED ok=$textOk fieldLen=${typedBack.length} wantLen=${want.length}")
            if (!textOk) {
                dumpScreenForDebug("wa_write_text_mismatch")
                return@withContext fail("VERIFY_TYPED_TEXT", "compose field shows \"${typedBack.take(60)}\" not the requested message")
            }

            // typed & verified — persist, STOP. SEND is a separate, explicitly-confirmed call.
            waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_TYPED_VERIFIED, want.hashCode())
            waLog("WA_WRITE_WAIT_CONFIRMATION mission=$missionId")
            waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_WAITING_CONFIRMATION, want.hashCode())
            return@withContext ok("TYPED_VERIFIED", verifiedHeaderText = if (nameMatch) h else null, nameMatch = nameMatch)
        } catch (e: Exception) {
            return@withContext fail("EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            whatsappAutomationActive = false
        }
    }

    // ── ROUND_WA_GOVERNANCE_WRITE_1 — PHASE B ────────────────────────────────────────────────────
    // Called ONLY after an explicit YES. Presses Send at most once per missionId, then verifies
    // the exact outgoing message appears in the transcript. SEND_ATTEMPTED is persisted before the
    // tap: an interruption after it re-VERIFIES only, never re-presses.
    suspend fun pressWhatsAppSendVerified(missionId: String, messageRaw: String): WhatsAppCallNativeResult =
        withContext(Dispatchers.Default) {
            val want = messageRaw.trim()
            val t0 = System.currentTimeMillis()
            fun ms() = System.currentTimeMillis() - t0
            fun waLog(m: String) = Log.i("BENSON_AUDIO", m)
            fun ok(step: String): WhatsAppCallNativeResult {
                waLog("WA_WRITE_END success=true step=$step elapsedMs=${ms()}")
                return WhatsAppCallNativeResult(true, step, null, "", ms())
            }
            fun fail(step: String, reason: String): WhatsAppCallNativeResult {
                waLog("WA_WRITE_FAIL stage=$step reason=${reason.replace("\n", " ").take(200)}")
                return WhatsAppCallNativeResult(false, step, reason, "", ms())
            }

            val rec = waWriteRead(this@BensonAccessibilityService)
            waLog("WA_WRITE_SEND_ENTER mission=$missionId priorMission=${rec.missionId} priorState=${rec.state}")

            if (missionId.isBlank() || rec.missionId != missionId) {
                return@withContext fail("SEND_ON_YES", "no typed-and-verified message for mission $missionId (have ${rec.missionId}/${rec.state})")
            }
            if (rec.state == WA_WRITE_SENT_VERIFIED) {
                waLog("WA_WRITE_SENT_VERIFIED mission=$missionId note=already_sent")
                return@withContext ok("SENT_VERIFIED")
            }
            if (rec.state == WA_WRITE_SEND_ATTEMPTED) {
                val present = whatsAppOutgoingMessagePresent(want)
                return@withContext if (present) {
                    waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_SENT_VERIFIED, want.hashCode())
                    waLog("WA_WRITE_SENT_VERIFIED mission=$missionId note=verified_on_recovery")
                    ok("SENT_VERIFIED")
                } else {
                    fail("SEND_ON_YES", "send already attempted for mission $missionId but outgoing message not verified — not re-pressing")
                }
            }
            if (rec.state != WA_WRITE_TYPED_VERIFIED && rec.state != WA_WRITE_WAITING_CONFIRMATION) {
                return@withContext fail("SEND_ON_YES", "mission $missionId in state ${rec.state}, expected TYPED_VERIFIED / WAITING_CONFIRMATION")
            }
            if (rec.msgHash != want.hashCode()) {
                return@withContext fail("SEND_ON_YES", "message changed since it was typed & verified")
            }

            whatsappAutomationActive = true
            try {
                val fgOk = foregroundIsPackage(WA_PKG).first || awaitTargetReacquired(WA_PKG, 6000L)
                if (!fgOk) return@withContext fail("SEND_ON_YES", "WhatsApp is not foreground")

                val field = findNodeMatching { (it.viewIdResourceName ?: "").endsWith("/entry") && it.isEditable }
                    ?.also { try { it.refresh() } catch (_: Exception) {} }
                val fieldText = field?.text?.toString()?.trim().orEmpty()
                if (fieldText != want) {
                    dumpScreenForDebug("wa_write_send_field_drift")
                    return@withContext fail("SEND_ON_YES", "compose field no longer holds the verified message (\"${fieldText.take(40)}\")")
                }

                // mark SEND_ATTEMPTED BEFORE the tap — a crash after this can only re-verify
                waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_SEND_ATTEMPTED, want.hashCode())
                waLog("WA_WRITE_SEND_ATTEMPT mission=$missionId")

                val sendNode = waitForNode(3000, 150, WA_PKG, "wa_write_send") { n ->
                    (n.viewIdResourceName ?: "").endsWith("/send") && n.isClickable
                } ?: waitForNode(1200, 150, WA_PKG, "wa_write_send_any") { (it.viewIdResourceName ?: "").endsWith("/send") }
                if (sendNode == null) {
                    dumpScreenForDebug("wa_write_no_send")
                    return@withContext fail("SEND_ON_YES", "send button com.whatsapp:id/send not found")
                }
                if (isPaymentSensitive(sendNode)) return@withContext fail("SEND_ON_YES", "send node matched payment-sensitive pattern")

                val clicked = clickNodeOrAncestor(sendNode)
                waLog("WA_WRITE_SEND_CLICK ok=$clicked")
                if (!clicked) return@withContext fail("SEND_ON_YES", "ACTION_CLICK rejected on send button")

                // VERIFY_OUTGOING_MESSAGE
                var present = false
                run {
                    val deadline = System.currentTimeMillis() + 6000
                    while (System.currentTimeMillis() < deadline) {
                        if (whatsAppOutgoingMessagePresent(want)) { present = true; break }
                        delay(200)
                    }
                }
                waLog("WA_WRITE_SENT_VERIFIED mission=$missionId present=$present")
                if (!present) {
                    dumpScreenForDebug("wa_write_not_in_transcript")
                    return@withContext fail("VERIFY_OUTGOING_MESSAGE", "tapped send but the message did not appear in the conversation")
                }
                waWritePersist(this@BensonAccessibilityService, missionId, WA_WRITE_SENT_VERIFIED, want.hashCode())
                return@withContext ok("SENT_VERIFIED")
            } catch (e: Exception) {
                return@withContext fail("EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                whatsappAutomationActive = false
            }
        }

    // The exact message text present as a NON-editable node in the conversation transcript
    // (i.e. a sent/received bubble, not the compose field).
    private fun whatsAppOutgoingMessagePresent(message: String): Boolean {
        val wantMsg = message.trim()
        if (wantMsg.isEmpty()) return false
        return findNodeMatching { n ->
            val vid = n.viewIdResourceName ?: ""
            !n.isEditable && !vid.endsWith("/entry") &&
                (n.text?.toString()?.trim() == wantMsg)
        } != null
    }

    // Ends an in-progress WhatsApp call. Assumes a call is currently active (started via
    // placeWhatsAppCall or manually) — brings WhatsApp back to the foreground (its call screen is
    // what resumes, same as the user tapping the ongoing-call notification), finds the
    // end-call-shaped button, taps it, then returns to BENSON. Reports honestly if no such
    // button is found rather than assuming there was nothing to end.
    suspend fun endWhatsAppCall(): WhatsAppCallResult {
        whatsappAutomationActive = true
        try {
            Log.i(TAG, "endWhatsAppCall: starting")
            if (!launchWhatsApp()) {
                return WhatsAppCallResult(false, "launch", "Could not bring WhatsApp to the foreground.")
            }
            val endNode = waitForNode(3000, requirePackage = WHATSAPP_PACKAGE) {
                it.isClickable && matchesAny(it, END_CALL_KEYWORDS)
            }
            if (endNode == null) {
                dumpScreenForDebug("find_end_call")
                return WhatsAppCallResult(false, "find_end_call", "Could not find an end-call button — is a call actually active?")
            }
            Log.i(TAG, "endWhatsAppCall: found node label=\"${nodeLabel(endNode)}\", tapping")
            if (!endNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_end_call", "Found the end-call button but the tap was not accepted.")
            }
            Log.i(TAG, "endWhatsAppCall: done, returning to BENSON")
            returnToBenson()
            return WhatsAppCallResult(true, "done")
        } finally {
            whatsappAutomationActive = false
        }
    }

    // Toggles mute on an in-progress WhatsApp call. Same shape as endWhatsAppCall — brings
    // WhatsApp's call screen forward, finds the mute-shaped button, taps it, returns to BENSON.
    // A single tap toggles WhatsApp's own mute state; this does not track or report which state
    // resulted, since the button's own accessibility label doesn't reliably expose that.
    suspend fun muteWhatsAppCall(): WhatsAppCallResult {
        whatsappAutomationActive = true
        try {
            Log.i(TAG, "muteWhatsAppCall: starting")
            if (!launchWhatsApp()) {
                return WhatsAppCallResult(false, "launch", "Could not bring WhatsApp to the foreground.")
            }
            val muteNode = waitForNode(3000, requirePackage = WHATSAPP_PACKAGE) {
                it.isClickable && matchesAny(it, MUTE_KEYWORDS)
            }
            if (muteNode == null) {
                dumpScreenForDebug("find_mute")
                return WhatsAppCallResult(false, "find_mute", "Could not find a mute button — is a call actually active?")
            }
            Log.i(TAG, "muteWhatsAppCall: found node label=\"${nodeLabel(muteNode)}\", tapping")
            if (!muteNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_mute", "Found the mute button but the tap was not accepted.")
            }
            Log.i(TAG, "muteWhatsAppCall: done, returning to BENSON")
            returnToBenson()
            return WhatsAppCallResult(true, "done")
        } finally {
            whatsappAutomationActive = false
        }
    }

    // Taps WhatsApp's own send button — used only after the JS side has already opened a specific
    // chat with the message pre-filled via a wa.me deep link (Linking.openURL, whatsappTool.ts's
    // openConversation), which is the only way to get text into WhatsApp's compose field at all;
    // there's no accessibility "type the message" step here the way placeWhatsAppCall types a
    // contact name into WhatsApp's own search field. This function only finds and taps "send".
    // Product-owner-authorized (2026-07-17): confirming once by voice/tap in BENSON's own
    // "Trimit mesajul?" prompt is enough — the user should never also have to reach into WhatsApp
    // and tap send a second time.
    suspend fun pressWhatsAppSend(): WhatsAppCallResult {
        whatsappAutomationActive = true
        try {
            Log.i(TAG, "pressWhatsAppSend: starting")
            val sendNode = waitForNode(4000, requirePackage = WHATSAPP_PACKAGE) {
                it.isClickable && matchesAny(it, SEND_KEYWORDS)
            }
            if (sendNode == null) {
                dumpScreenForDebug("find_send")
                return WhatsAppCallResult(false, "find_send", "Could not find the send button — is the chat actually open with a message typed?")
            }
            Log.i(TAG, "pressWhatsAppSend: found node label=\"${nodeLabel(sendNode)}\", tapping")
            if (isPaymentSensitive(sendNode)) {
                return WhatsAppCallResult(false, "send_button_blocked", "Blocked: send button node matched the payment-sensitive pattern.")
            }
            if (!sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_send", "Found the send button but the tap was not accepted.")
            }
            // Brief pause so the user actually sees the message land in the chat (the whole point
            // of showing WhatsApp large during this step) before it disappears and BENSON returns.
            delay(1200)
            Log.i(TAG, "pressWhatsAppSend: done, returning to BENSON")
            returnToBenson()
            return WhatsAppCallResult(true, "done")
        } finally {
            whatsappAutomationActive = false
        }
    }

    // Product-owner-directed (2026-07-17): the confirmation flow was reordered so the user sees
    // the actual resolved contact (chat already open, BENSON in PiP) BEFORE answering "Confirmi?",
    // instead of confirming blind. placeWhatsAppCall(..., autoPressCall=false) already stops right
    // after the chat opens without pressing the call button (see "chat_opened" branch above) — this
    // is the second half of that same two-phase flow: assumes that chat is ALREADY open (JS calls
    // this only after the user says "da"), finds and taps the call button, same header-region +
    // payment-blocklist safety as the single-shot path, then the same settle-delay-before-return
    // fix as placeWhatsAppCallInner (confirmed live: returning to BENSON with no delay cancelled
    // the outgoing call before WhatsApp's own call session had started).
    suspend fun pressWhatsAppCallButton(): WhatsAppCallResult {
        whatsappAutomationActive = true
        try {
            Log.i(TAG, "pressWhatsAppCallButton: starting")
            val maxTop = headerRegionMaxTop()
            val callNode = waitForNode(2500, requirePackage = WHATSAPP_PACKAGE) {
                val bounds = Rect()
                it.getBoundsInScreen(bounds)
                it.isClickable && bounds.top <= maxTop && matchesAny(it, CALL_KEYWORDS) && !matchesAny(it, VIDEO_EXCLUDE_KEYWORDS)
            }
            if (callNode == null) {
                dumpScreenForDebug("find_call_button")
                return WhatsAppCallResult(false, "find_call_button", "Could not find the call button — is the chat actually open?")
            }
            Log.i(TAG, "pressWhatsAppCallButton: found call node label=\"${nodeLabel(callNode)}\", tapping")
            if (isPaymentSensitive(callNode)) {
                return WhatsAppCallResult(false, "call_button_blocked", "Blocked: call button node matched the payment-sensitive pattern.")
            }
            if (!callNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_call_button", "Found the call button but the tap was not accepted.")
            }
            Log.i(TAG, "pressWhatsAppCallButton: call button tapped, settling before returning to BENSON")
            delay(2500)
            Log.i(TAG, "pressWhatsAppCallButton: done, returning to BENSON")
            returnToBenson()
            return WhatsAppCallResult(true, "done")
        } finally {
            whatsappAutomationActive = false
        }
    }
}
