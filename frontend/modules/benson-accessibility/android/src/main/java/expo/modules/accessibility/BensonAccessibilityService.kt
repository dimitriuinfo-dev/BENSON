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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectionEpoch++
        Log.i(TAG, "Benson accessibility service connected, epoch=$connectionEpoch")
        maybeResurrect("onServiceConnected")
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
        wakeScreenForRecovery()
        bringBensonToForegroundForRecovery()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    lastForegroundPackage = packageName
                    onForegroundChanged?.invoke(packageName)
                }
                maybeResurrect("onAccessibilityEvent")
                emitScreenSnapshot(packageName)
            }
            else -> { /* ignorat — reduce zgomotul */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Benson accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
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

    // Polls the live accessibility tree (native delay(), not a JS timer) until `predicate` finds
    // a match or timeoutMs elapses. This is the one thing that made the JS version of this flow
    // unreliable — this loop lives entirely inside the service process and keeps running
    // regardless of whether BENSON's own Activity currently has foreground focus.
    private suspend fun waitForNode(
        timeoutMs: Long,
        intervalMs: Long = 250,
        requirePackage: String? = null,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requirePackage == null || rootInActiveWindow?.packageName?.toString() == requirePackage) {
                findNodeMatching(predicate)?.let { return it }
            }
            delay(intervalMs)
        }
        return null
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

    private suspend fun placeWhatsAppCallInner(contactName: String, autoPressCall: Boolean): WhatsAppCallResult {
        val name = contactName.trim()
        Log.i(TAG, "placeWhatsAppCall: starting for \"$name\"")
        if (name.isEmpty()) return WhatsAppCallResult(false, "validate", "Contact name is empty.")

        if (!launchWhatsApp()) {
            Log.e(TAG, "placeWhatsAppCall: launchWhatsApp failed")
            return WhatsAppCallResult(false, "launch", "Could not launch WhatsApp.")
        }
        Log.i(TAG, "placeWhatsAppCall: launched WhatsApp, waiting for search icon")

        // Confirmed live (2026-07-17): the recovery loop below was firing its FIRST check
        // immediately after launchWhatsApp(), before WhatsApp's activity had actually rendered —
        // rootInActiveWindow was still transiently stale/empty, so insideIndividualChat read false
        // on attempt 1 and the loop broke out instantly, never getting a chance to detect (let
        // alone fix) a real stuck individual-chat screen that only became visible a moment later.
        // A short settle delay before the first check fixes that race.
        delay(600)

        // Confirmed live (2026-07-14): if WhatsApp is already running, getLaunchIntentForPackage
        // resumes whatever screen it was last on (e.g. a previous contact's individual chat) —
        // it does not reset to the main chat list, where the global search icon lives. An
        // individual chat has no such icon, so step 2 below would time out for the wrong reason.
        // Detect that case via the message-entry field's viewId and press back to return to the
        // main list before searching.
        //
        // Confirmed live (2026-07-17): performGlobalAction(GLOBAL_ACTION_BACK) alone was NOT
        // reliably escaping a stuck individual chat on this device — 3 attempts at 500ms each
        // left the flow still inside the same chat every time. Tapping the toolbar's own visible
        // back/home button (viewId "com.whatsapp:id/whatsapp_toolbar_home", confirmed present on
        // an individual chat screen) is a more direct, targeted action than the global back
        // gesture — tried first each attempt, falling back to the global action if that specific
        // button isn't present. Attempts raised 3 -> 5 and delay 500ms -> 700ms for extra headroom.
        for (attempt in 1..5) {
            val onChatList = findNodeMatching { it.isClickable && matchesAny(it, SEARCH_KEYWORDS) } != null
            if (onChatList) break
            val insideIndividualChat = findNodeMatching { it.viewIdResourceName == "com.whatsapp:id/entry" } != null
            if (!insideIndividualChat) break
            val toolbarHome = findNodeMatching { it.viewIdResourceName == "com.whatsapp:id/whatsapp_toolbar_home" }
            if (toolbarHome != null && toolbarHome.isClickable) {
                Log.i(TAG, "placeWhatsAppCall: landed inside an individual chat (attempt $attempt), tapping the toolbar back button")
                toolbarHome.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                Log.i(TAG, "placeWhatsAppCall: landed inside an individual chat (attempt $attempt), no toolbar back button found — using global back")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            delay(700)
        }

        // Confirmed live (2026-07-17): a PREVIOUS placeWhatsAppCall attempt that failed to find a
        // result (e.g. a mis-captured name, now fixed upstream) can leave WhatsApp sitting on the
        // search screen with the OLD query still typed in — there's no separate search ICON to tap
        // while search is already open, so trying to press back and re-open it via step 2 below
        // was found to not reliably return to the plain chat list (GLOBAL_ACTION_BACK's effect on
        // WhatsApp's search overlay is not consistent enough to depend on) — the earlier fix
        // attempt for this exact bug still failed live. Simpler and more robust: if the search
        // field is ALREADY open, reuse it directly — clear the stale text and type the new name —
        // instead of trying to navigate away and back into a fresh one.
        val alreadyOpenSearchField = findNodeMatching { it.viewIdResourceName == "com.whatsapp:id/search_input" }
        val searchField: AccessibilityNodeInfo
        if (alreadyOpenSearchField != null) {
            Log.i(TAG, "placeWhatsAppCall: search already open with stale text, reusing the field directly")
            searchField = alreadyOpenSearchField
        } else {
            // Step 2 — read the screen, find and tap the search icon.
            val searchNode = waitForNode(4000, requirePackage = WHATSAPP_PACKAGE) {
                it.isClickable && matchesAny(it, SEARCH_KEYWORDS)
            }
            if (searchNode == null) {
                dumpScreenForDebug("find_search")
                return WhatsAppCallResult(false, "find_search", "Could not find the search icon in WhatsApp.")
            }
            Log.i(TAG, "placeWhatsAppCall: found search node label=\"${nodeLabel(searchNode)}\", tapping")
            if (!searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return WhatsAppCallResult(false, "tap_search", "Found the search icon but could not tap it.")
            }

            // Step 3 — read the screen, find the now-visible search field.
            val freshSearchField = waitForNode(3000, requirePackage = WHATSAPP_PACKAGE) { it.isEditable }
            if (freshSearchField == null) {
                dumpScreenForDebug("find_search_field")
                return WhatsAppCallResult(false, "find_search_field", "The search field did not appear.")
            }
            searchField = freshSearchField
        }
        Log.i(TAG, "placeWhatsAppCall: typing \"$name\" into the search field")
        val setTextArgs = android.os.Bundle()
        setTextArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        if (!searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            return WhatsAppCallResult(false, "type_name", "Found the search field but could not type into it.")
        }

        // Step 4 — read the screen, find the first matching result, tap it.
        //
        // Confirmed live (2026-07-14) that the naive "first clickable node whose own label
        // contains the name" matched the SEARCH FIELD ITSELF an instant after typing (its own
        // text is now "Hannah", and it's clickable) — long before real results render, and the
        // flow proceeded as if a result had been tapped when nothing had actually happened.
        // Two fixes: (1) exclude editable nodes and anything under a search_* viewId (WhatsApp's
        // whole search-bar/filter-row UI) from matching at all; (2) the actual name text lives in
        // a non-clickable child (conversations_row_contact_name) — walk up to the nearest
        // clickable ancestor (contact_row_container) instead of requiring the match itself to be
        // clickable, since tapping a peripheral clickable child (e.g. the avatar) may not open
        // the chat the same way the row itself does.
        // Prefer an EXACT label match over a whole-word substring match — confirmed live
        // 2026-07-17: searching "Mama" surfaced a completely different contact whose display name
        // merely ENDS in the word "mama" ("Fr. Batljan Slobodanka-Davids Mama") as a valid
        // whole-word match, and this called them instead of the real "Mama" contact. Whole-word
        // matching correctly stops "Ana" from matching mid-word inside "Adriana" (the earlier
        // Ana/Adriana lesson), but it does NOT stop an entirely different, longer name that
        // happens to literally end in the same word — only an exact match guarantees that's really
        // the contact meant. Falls back to whole-word only when no exact match appears in time,
        // same honest-search behavior as before.
        val exactLabelNode = waitForNode(1500, requirePackage = WHATSAPP_PACKAGE) {
            !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it) &&
                nodeLabel(it) == name.lowercase()
        }
        val resultLabelNode = exactLabelNode ?: waitForNode(3000, requirePackage = WHATSAPP_PACKAGE) {
            !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it) &&
                containsWholeWord(nodeLabel(it), name.lowercase())
        }
        if (resultLabelNode == null) {
            dumpScreenForDebug("find_result")
            return WhatsAppCallResult(false, "find_result", "No search result for \"$name\" appeared.")
        }
        // Always prefer the row-container ancestor over the matched node itself now that avatars
        // (the one case where the match was directly clickable) are excluded — the name text is
        // reliably non-clickable, so this should now always walk up to the real row.
        val resultNode = findClickableAncestor(resultLabelNode) ?: resultLabelNode.takeIf { it.isClickable }
        if (resultNode == null) {
            dumpScreenForDebug("find_result")
            return WhatsAppCallResult(false, "find_result", "Found \"$name\" in results but no tappable row around it.")
        }
        Log.i(TAG, "placeWhatsAppCall: found result node label=\"${nodeLabel(resultLabelNode)}\", tapping ancestor viewId=${resultNode.viewIdResourceName}")
        // Confirmed live 2026-07-17: this exact tap on this exact node type ("mama"/"baby",
        // ancestor recent_container) succeeded on some attempts and failed with an
        // outright-rejected ACTION_CLICK on others, same day, same code path — the signature of a
        // transient "not yet settled/interactive" window (e.g. still mid-animation into place)
        // rather than a wrong target, since a genuinely wrong/non-clickable node fails every time,
        // not intermittently. A single retry on the SAME cached node object was tried first and
        // was not enough (confirmed live: it failed again on retry too) — AccessibilityNodeInfo
        // references can themselves go stale within a couple hundred ms of the tree updating, so
        // retrying is only meaningful against a FRESH node, not the same possibly-stale reference.
        // Re-resolves the label + clickable ancestor from scratch on each retry instead.
        var tapTarget = resultNode
        var tapped = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var tapAttempt = 1
        while (!tapped && tapAttempt < 3) {
            delay(300L * tapAttempt)
            val freshLabel = findNodeMatching {
                !it.isEditable && !isSearchUiNode(it) && !isAvatarNode(it) && !isRecentSuggestionNode(it) &&
                    (nodeLabel(it) == name.lowercase() || containsWholeWord(nodeLabel(it), name.lowercase()))
            }
            val freshTarget = freshLabel?.let { findClickableAncestor(it) ?: it.takeIf { n -> n.isClickable } }
            if (freshTarget == null) {
                tapAttempt++
                continue
            }
            tapTarget = freshTarget
            tapped = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tapAttempt++
        }
        if (!tapped) {
            return WhatsAppCallResult(false, "tap_result", "Found a search result but could not tap it.")
        }
        if (tapAttempt > 1) {
            Log.i(TAG, "placeWhatsAppCall: tap_result succeeded on retry attempt=$tapAttempt")
        }

        // Step 5 — wait for the chat to load. Matching "name text visible somewhere" alone
        // false-positived here too (the search screen's own result row still shows the name) —
        // require the search UI (search_input) to have actually disappeared as well, confirming
        // we've genuinely navigated to a different screen, not just that the name is on-screen.
        var chatConfirmed = false
        run {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val stillOnSearch = findNodeMatching { isSearchUiNode(it) } != null
                val nameVisible = findNodeMatching { !isSearchUiNode(it) && containsWholeWord(nodeLabel(it), name.lowercase()) } != null
                if (!stillOnSearch && nameVisible) {
                    chatConfirmed = true
                    break
                }
                delay(250)
            }
        }
        if (!chatConfirmed) {
            dumpScreenForDebug("verify_chat")
            return WhatsAppCallResult(false, "verify_chat", "Could not confirm $name's chat opened.")
        }
        Log.i(TAG, "placeWhatsAppCall: chat confirmed open, looking for call button")

        if (!autoPressCall) {
            Log.i(TAG, "placeWhatsAppCall: autoPressCall=false, stopping after chat_opened")
            return WhatsAppCallResult(true, "chat_opened", null)
        }

        // Step 6 — find and tap the call-shaped button (never video), header region only, with
        // the existing payment-safety blocklist still enforced on the final tap.
        val maxTop = headerRegionMaxTop()
        val callNode = waitForNode(2500, requirePackage = WHATSAPP_PACKAGE) {
            val bounds = Rect()
            it.getBoundsInScreen(bounds)
            it.isClickable && bounds.top <= maxTop && matchesAny(it, CALL_KEYWORDS) && !matchesAny(it, VIDEO_EXCLUDE_KEYWORDS)
        }
        if (callNode == null) {
            dumpScreenForDebug("find_call_button")
            return WhatsAppCallResult(false, "find_call_button", "Could not find the call button on screen.")
        }
        Log.i(TAG, "placeWhatsAppCall: found call node label=\"${nodeLabel(callNode)}\", tapping")

        if (isPaymentSensitive(callNode)) {
            return WhatsAppCallResult(false, "call_button_blocked", "Blocked: call button node matched the payment-sensitive pattern.")
        }
        if (!callNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return WhatsAppCallResult(false, "tap_call_button", "Found the call button but the tap was not accepted.")
        }

        // Product-owner-directed (2026-07-17, final flow): WhatsApp was full-screen (BENSON in
        // PiP, entered from JS right before launchWhatsApp() above) WHILE this automation ran, so
        // the user could watch it happen — now that it's actually done, WhatsApp disappears and
        // BENSON returns to full-screen. Confirmed live (2026-07-17): calling returnToBenson()
        // with NO delay right after the tap ended the call itself — WhatsApp's outgoing call
        // hadn't yet established its own foreground call session at that instant, so forcibly
        // pulling focus away that fast cancelled the dial instead of just backgrounding a
        // stable call. A short settle delay lets WhatsApp's call session actually start before
        // BENSON reclaims the foreground; the call keeps running in the background afterwards
        // exactly as it would if the user switched apps themselves.
        Log.i(TAG, "placeWhatsAppCall: call button tapped, settling before returning to BENSON")
        delay(2500)
        Log.i(TAG, "placeWhatsAppCall: done, returning to BENSON")
        returnToBenson()
        return WhatsAppCallResult(true, "done")
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
