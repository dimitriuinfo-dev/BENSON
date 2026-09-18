package expo.modules.accessibility

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * BENSON — Command Executor ("mâna care apasă").
 *
 * Filozofie (product-owner-directed 2026-07-17): Benson NU decide nimic singur.
 * Creierul (userul / JS-ul) trimite o listă explicită de pași; acest executor îi rulează
 * în ordine, pe serviceScope (nativ, supraviețuiește throttling-ului JS pe ColorOS),
 * și se OPREȘTE la prima problemă — nu ghicește, nu improvizează.
 *
 * Reguli hard, moștenite din serviciu:
 *  - isPaymentSensitive() blochează orice click / set_text pe noduri de plată. Mereu.
 *  - Ambiguitate la click pe text (2+ candidați diferiți) = STOP + raport "ambiguous".
 *    (Lecția Ana/Adriana: mai bine un eșec onest decât acțiunea greșită.)
 *  - Fiecare pas raportează exact ce s-a întâmplat: completed / not_found / ambiguous /
 *    blocked / tap_rejected / wrong_package.
 *
 * Format comandă (JSON venit din JS prin BensonAccessibilityModule):
 * {
 *   "steps": [
 *     { "action": "launch_app",  "package": "com.whatsapp" },
 *     { "action": "wait",        "ms": 600 },
 *     { "action": "click",       "match": { "textContains": "search", "clickable": true }, "timeoutMs": 4000 },
 *     { "action": "set_text",    "match": { "editable": true }, "text": "Ana", "timeoutMs": 3000 },
 *     { "action": "click",       "match": { "textContains": "Ana", "wholeWord": true, "excludeSearchUi": true, "clickableAncestor": true } },
 *     { "action": "assert_gone", "match": { "viewIdContains": "search_input" }, "timeoutMs": 3000 },
 *     { "action": "back" },
 *     { "action": "return_to_benson" }
 *   ]
 * }
 *
 * Match spec (toate câmpurile opționale, se combină cu ȘI logic):
 *   viewId          — potrivire exactă pe viewIdResourceName
 *   viewIdContains  — substring pe viewIdResourceName
 *   textContains    — substring (sau whole-word dacă wholeWord=true) pe text+contentDescription
 *   wholeWord       — aplică potrivirea whole-word pe textContains (default false)
 *   clickable       — cere isClickable
 *   editable        — cere isEditable
 *   excludeSearchUi — exclude nodurile cu viewId care conține "search"
 *   excludeAvatars  — exclude ImageView / viewId cu photo|picture|avatar (default true la click pe text)
 *   clickableAncestor — dacă nodul potrivit nu e clickable, urcă la primul strămoș clickable
 *   maxTopPercent   — nodul trebuie să fie în primii N% ai ecranului (ex. 15 = doar header)
 */
class BensonCommandExecutor(
    private val service: BensonAccessibilityService,
    private val returnToBenson: () -> Boolean,
    private val isPaymentSensitive: (AccessibilityNodeInfo) -> Boolean,
) {
    companion object {
        private const val TAG = "BensonCmdExec"
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40
        private const val DEFAULT_STEP_TIMEOUT_MS = 4000L
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_STEPS = 30

        // ── BF1-c (2026-09-08) — assert_package must ignore BENSON's own overlay ──────────────────
        // Dovedit în log: RECIPE_STEP index=1 "WhatsApp în prim-plan" found=false elapsedMs=66202,
        // deși WhatsApp era vizibil. service.rootInActiveWindow?.packageName raporta
        // com.benson.butler — bula (fereastră WindowManager TYPE_APPLICATION_OVERLAY) e văzută de
        // serviciu ca fereastra activă. Revert: pune pe false → doar rootInActiveWindow.
        const val BF1C_IGNORE_OVERLAY_PKG = true

        // ── WA-FIX-1 (2026-09-08) — assert_package rezolvat prin AccessibilityService.getWindows() ─
        // BF1-c s-a bazat pe lastForegroundPackage; logul a arătat că nu e de ajuns (bula emite
        // totuși TYPE_WINDOW_STATE_CHANGED în anumite cazuri → lastForegroundPackage=com.benson.butler).
        // Fix strict: enumeră ferestrele; dacă ORICARE fereastră validă are root.packageName ==
        // expected, target-ul e disponibil — indiferent ce layer are overlay-ul BENSON.
        // Revert: pune pe false → doar root_active + lastForegroundPackage (comportamentul BF1-c).
        const val WAFIX1_WINDOW_SCAN = true
        private const val BENSON_PKG = "com.benson.butler"
    }

    private data class PkgResolve(val found: Boolean, val source: String) // root_active | window_scan | last_foreground | none

    private fun windowTypeName(t: Int): String = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        else -> "type_$t"
    }

    // Strict resolution of whether `expected` is the target app's foreground window.
    //  1. root_active   — rootInActiveWindow.packageName already IS the target (fast path)
    //  2. window_scan   — any window in getWindows() has root.packageName == target (BENSON's own
    //                     overlay window is skipped: it never *is* the target, and its layer being
    //                     on top must not invalidate the target underneath)
    //  3. last_foreground — BF1-c fallback: overlay on top, last real activity transition == target
    private fun resolveForegroundPackage(expected: String, logWindows: Boolean): PkgResolve {
        val rootActive = service.rootInActiveWindow?.packageName?.toString()
        if (rootActive == expected) return PkgResolve(true, "root_active")

        if (WAFIX1_WINDOW_SCAN) {
            val windows: List<AccessibilityWindowInfo> =
                try { service.windows ?: emptyList() } catch (e: Exception) { emptyList() }
            if (logWindows) {
                Log.i("BENSON_AUDIO", "ASSERT_PACKAGE expected=$expected rootActive=${rootActive ?: "null"} windows=${windows.size}")
            }
            var hit = false
            windows.forEachIndexed { idx, w ->
                val wPkg = try { w.root?.packageName?.toString() } catch (e: Exception) { null }
                if (logWindows) {
                    Log.i(
                        "BENSON_AUDIO",
                        "ASSERT_WINDOW index=$idx pkg=${wPkg ?: "null"} active=${w.isActive} " +
                            "focused=${w.isFocused} type=${windowTypeName(w.type)} layer=${w.layer}",
                    )
                }
                if (wPkg == expected && wPkg != BENSON_PKG) hit = true
            }
            if (hit) {
                if (rootActive == BENSON_PKG) {
                    Log.i("BENSON_AUDIO", "ASSERT_OVERLAY_BYPASS overlay=$BENSON_PKG target=$expected success=true")
                }
                return PkgResolve(true, "window_scan")
            }
        }

        if (BF1C_IGNORE_OVERLAY_PKG && (rootActive == null || rootActive == service.packageName)) {
            if (BensonAccessibilityService.lastForegroundPackage == expected) {
                return PkgResolve(true, "last_foreground")
            }
        }
        return PkgResolve(false, "none")
    }

    private fun foregroundPackageMatches(expected: String): Boolean =
        resolveForegroundPackage(expected, logWindows = false).found

    data class CommandResult(
        val success: Boolean,
        val stepIndex: Int,
        val action: String,
        val status: String,          // completed | not_found | ambiguous | blocked | tap_rejected | wrong_package | invalid | timeout
        val detail: String? = null,
        // ROUND_YOUTUBE_GOVERNANCE_1 — generic payload for "extract_list": JSON array of
        // {"label": string, "top": number}, sorted top-to-bottom, deduped. "[]" for every other
        // action. Not YouTube-specific — any caller that needs "what's visible on screen right now"
        // can use extract_list.
        val itemsJson: String = "[]",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("success", success)
            put("stepIndex", stepIndex)
            put("action", action)
            put("status", status)
            put("detail", detail ?: JSONObject.NULL)
            put("itemsJson", itemsJson)
        }
    }

    /** Punctul de intrare. Rulează pe serviceScope (apelantul e responsabil de asta). */
    suspend fun execute(command: JSONObject): CommandResult {
        val steps = command.optJSONArray("steps")
            ?: return CommandResult(false, -1, "none", "invalid", "Missing \"steps\" array.")
        if (steps.length() == 0) {
            return CommandResult(false, -1, "none", "invalid", "Empty steps array.")
        }
        if (steps.length() > MAX_STEPS) {
            return CommandResult(false, -1, "none", "invalid", "Too many steps (${steps.length()} > $MAX_STEPS).")
        }

        // ROUND_YOUTUBE_GOVERNANCE_1 — carries the last non-empty extract_list payload through to
        // the final "done" result, since a successful multi-step run otherwise returns a synthetic
        // CommandResult that would silently drop whatever extract_list found.
        var lastItemsJson = "[]"

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i)
                ?: return CommandResult(false, i, "none", "invalid", "Step $i is not an object.")
            val action = step.optString("action", "")
            Log.i(TAG, "step $i: $action")

            val result: CommandResult = when (action) {
                "launch_app" -> doLaunchApp(i, step)
                "open_app_settings" -> doOpenAppSettings(i, step)
                "wait" -> { delay(step.optLong("ms", 500L).coerceIn(50L, 10_000L)); ok(i, action) }
                "click" -> doClick(i, step)
                "set_text" -> doSetText(i, step)
                "assert_present" -> doAssertPresent(i, step)
                "assert_gone" -> doAssertGone(i, step)
                "assert_package" -> doAssertPackage(i, step)
                "back" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) ok(i, action)
                          else rejected(i, action, "Global BACK was not accepted.")
                "home" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) ok(i, action)
                          else rejected(i, action, "Global HOME was not accepted.")
                "recents" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) ok(i, action)
                          else rejected(i, action, "Global RECENTS was not accepted.")
                "scroll" -> doScroll(i, step)
                "return_to_benson" -> { returnToBenson(); ok(i, action) }
                // ROUND_YOUTUBE_GOVERNANCE_1 — generic, reusable additions (not YouTube-specific):
                // extract_list reads a small set of currently-visible text/description labels
                // instead of tapping anything; ime_action presses the keyboard's IME action
                // (typically "search"/"go") on the currently focused input, for apps whose submit
                // control is the keyboard itself rather than an on-screen button.
                "extract_list" -> doExtractList(i, step)
                "ime_action" -> doImeAction(i, step)
                // ROUND_SPOTIFY_GOVERNANCE_1 — generic, reusable: types into whatever node
                // currently holds Android's own input focus, instead of searching the tree for a
                // node matching {editable:true}. A Compose-based search field (confirmed live:
                // Spotify) can be genuinely focused and IME-targeted (dumpsys input_method's
                // mServedView, and a real blinking cursor + keyboard) while still not reporting
                // isEditable/exposing readable text through the classic AccessibilityNodeInfo tree
                // the same way a View-system EditText does. Focus tracking is toolkit-agnostic —
                // this works for any app's search field, not just Spotify's.
                "set_text_on_focus" -> doSetTextOnFocus(i, step)
                else -> fail(i, action, "invalid", "Unknown action \"$action\".")
            }

            if (result.itemsJson != "[]") lastItemsJson = result.itemsJson

            if (!result.success) {
                Log.w(TAG, "step $i ($action) failed: ${result.status} — ${result.detail}")
                return result
            }
        }
        return CommandResult(true, steps.length() - 1, "done", "completed", itemsJson = lastItemsJson)
    }

    // ------------------------------------------------------------------
    // Acțiuni
    // ------------------------------------------------------------------

    // Scroll the current screen so content that isn't visible yet can be reached (Faza 2 — the
    // "operator" can only tap what's on screen otherwise). Finds the first scrollable container
    // from the active window root and asks it to scroll. direction: "forward"/"down" (default) or
    // "backward"/"up". A rejected action usually just means we're already at the edge of the list.
    private fun doScroll(i: Int, step: JSONObject): CommandResult {
        val direction = step.optString("direction", "forward").lowercase()
        val scrollAction = if (direction == "backward" || direction == "up")
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val root = service.rootInActiveWindow
            ?: return fail(i, "scroll", "not_found", "No active window to scroll.")
        val scrollable = findScrollable(root, IntArray(1), 0)
            ?: return fail(i, "scroll", "not_found", "No scrollable node on screen.")
        return if (scrollable.performAction(scrollAction)) ok(i, "scroll")
        else rejected(i, "scroll", "Scrollable node found but scroll was not accepted (already at the edge?).")
    }

    private fun findScrollable(node: AccessibilityNodeInfo, counter: IntArray, depth: Int): AccessibilityNodeInfo? {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return null
        counter[0]++
        if (node.isScrollable) return node
        for (c in 0 until node.childCount) {
            val child = node.getChild(c) ?: continue
            val hit = findScrollable(child, counter, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    // ROUND_MEDIA_GOVERNANCE_1 — extract_list-only variant: collects EVERY scrollable node in the
    // tree and returns the one with the largest on-screen area, a better proxy for "the actual
    // content feed" than "the first one found" (see doExtractList's comment for the live evidence).
    private fun findLargestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        val counter = IntArray(1)
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (counter[0]++ >= MAX_NODES || depth >= MAX_DEPTH) return
            if (node.isScrollable) {
                val b = Rect(); node.getBoundsInScreen(b)
                val area = b.width().toLong() * b.height().toLong()
                if (area > bestArea) { bestArea = area; best = node }
            }
            for (c in 0 until node.childCount) {
                val child = node.getChild(c) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    private fun doLaunchApp(i: Int, step: JSONObject): CommandResult {
        val pkg = step.optString("package", "")
        if (pkg.isEmpty()) return fail(i, "launch_app", "invalid", "Missing \"package\".")
        return try {
            val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                ?: return fail(i, "launch_app", "not_found", "App $pkg is not installed.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            ok(i, "launch_app")
        } catch (e: Exception) {
            fail(i, "launch_app", "tap_rejected", "Launch failed: ${e.message}")
        }
    }

    // Opens the system "App info" (Settings → Apps → <pkg>) screen for a package. This is the only
    // reliable, gesture-free way BENSON can actually close another app: from here the JS step list
    // taps the OEM's "Force stop"/"Forțează oprirea" button and its confirmation. No force-stop API
    // exists for a normal app, so we drive the user-facing Settings UI via Accessibility instead.
    private fun doOpenAppSettings(i: Int, step: JSONObject): CommandResult {
        val pkg = step.optString("package", "")
        if (pkg.isEmpty()) return fail(i, "open_app_settings", "invalid", "Missing \"package\".")
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            ok(i, "open_app_settings")
        } catch (e: Exception) {
            fail(i, "open_app_settings", "tap_rejected", "Opening app settings failed: ${e.message}")
        }
    }

    private suspend fun doClick(i: Int, step: JSONObject): CommandResult {
        val match = step.optJSONObject("match")
            ?: return fail(i, "click", "invalid", "Missing \"match\".")
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS)
        val requirePackage = step.optString("requirePackage", "").ifEmpty { null }

        val found = waitForCandidates(timeout, requirePackage, match)
            ?: return fail(i, "click", "not_found", "No node matched ${match} within ${timeout}ms.")

        // Ambiguitate: dacă match-ul e pe TEXT și există 2+ candidați cu etichete diferite,
        // ne oprim. Un viewId exact e prin definiție țintit, deci acolo luăm primul.
        val byText = match.has("textContains") && !match.has("viewId")
        if (byText && found.distinctLabels.size > 1) {
            return fail(
                i, "click", "ambiguous",
                "Multiple different candidates matched: ${found.distinctLabels.take(4).joinToString(" | ")}. Refusing to guess."
            )
        }

        var target = found.first
        if (match.optBoolean("clickableAncestor", false) && !target.isClickable) {
            target = findClickableAncestor(target)
                ?: return fail(i, "click", "not_found", "Matched node has no clickable ancestor.")
        }
        if (!target.isClickable) {
            return fail(i, "click", "not_found", "Matched node is not clickable (add clickableAncestor:true?).")
        }
        if (isPaymentSensitive(target)) {
            return fail(i, "click", "blocked", "Blocked: node matched the payment-sensitive pattern.")
        }
        // ROUND_SPOTIFY_GOVERNANCE_1 — diagnostic only: which exact node ended up being tapped,
        // to distinguish "clicked the wrong node" from "clicked the right node but nothing
        // happened" when a click reports success yet no visible/IME state change follows.
        val tb = Rect(); target.getBoundsInScreen(tb)
        Log.i("BENSON_AUDIO", "CLICK_TARGET_DIAG class=${target.className} bounds=$tb clickable=${target.isClickable} focusable=${target.isFocusable}")
        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ok(i, "click")
        else rejected(i, "click", "Node found but the tap was not accepted.")
    }

    private suspend fun doSetText(i: Int, step: JSONObject): CommandResult {
        val match = step.optJSONObject("match")
            ?: return fail(i, "set_text", "invalid", "Missing \"match\".")
        val text = step.optString("text", "")
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS)
        val requirePackage = step.optString("requirePackage", "").ifEmpty { null }

        val found = waitForCandidates(timeout, requirePackage, match)
            ?: return fail(i, "set_text", "not_found", "No node matched within ${timeout}ms.")
        val target = found.first
        if (!target.isEditable) {
            return fail(i, "set_text", "not_found", "Matched node is not editable.")
        }
        if (isPaymentSensitive(target)) {
            return fail(i, "set_text", "blocked", "Blocked: editable node matched the payment-sensitive pattern.")
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ok(i, "set_text")
        else rejected(i, "set_text", "Editable node found but SET_TEXT was not accepted.")
    }

    // ROUND_SPOTIFY_GOVERNANCE_1 — types into whatever node Android currently reports as the
    // input-focused one (AccessibilityNodeInfo.FOCUS_INPUT), instead of searching the tree by
    // {editable:true}. Confirmed live: a real device input field can be genuinely IME-focused
    // (dumpsys input_method's mServedView, a real blinking cursor, a real keyboard) while its own
    // AccessibilityNodeInfo doesn't report isEditable the way a classic EditText does — a Compose
    // text field is the confirmed live example, but this is a toolkit-agnostic fallback, not a
    // Spotify-specific one.
    private fun doSetTextOnFocus(i: Int, step: JSONObject): CommandResult {
        val text = step.optString("text", "")
        val root = service.rootInActiveWindow
            ?: return fail(i, "set_text_on_focus", "not_found", "No active window.")
        Log.i("BENSON_AUDIO", "SET_TEXT_ON_FOCUS_DIAG rootPkg=${root.packageName} rootClass=${root.className}")
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
        if (focused == null) {
            Log.i("BENSON_AUDIO", "SET_TEXT_ON_FOCUS_DIAG result=no_focused_node")
            return fail(i, "set_text_on_focus", "not_found", "No focused input field.")
        }
        Log.i(
            "BENSON_AUDIO",
            "SET_TEXT_ON_FOCUS_DIAG focusedClass=${focused.className} editable=${focused.isEditable} " +
                "focusedPkg=${focused.packageName} bounds=${Rect().also { focused.getBoundsInScreen(it) }}",
        )
        // CONFIRMED LIVE (2026-09-12) — the input-focused node itself can be a Compose "merged
        // semantics" wrapper: it genuinely IS the IME's served view (confirmed via dumpsys
        // input_method), but isEditable()==false and it rejects ACTION_SET_TEXT directly. The real
        // editable leaf is a descendant Compose folds into that merged node. Falling back to a
        // bounded search of the focused node's OWN subtree (never the whole screen — this is not
        // "search the tree by criteria," it's "look inside the thing focus already pointed us at")
        // keeps this generic rather than Spotify-specific: any merged-semantics text field benefits.
        val editableTarget = if (focused.isEditable) focused else findEditableDescendant(focused, IntArray(1), 0)
        if (editableTarget == null) {
            Log.i("BENSON_AUDIO", "SET_TEXT_ON_FOCUS_DIAG result=no_editable_descendant")
            return fail(i, "set_text_on_focus", "not_found", "Focused node has no editable descendant.")
        }
        if (editableTarget !== focused) {
            Log.i(
                "BENSON_AUDIO",
                "SET_TEXT_ON_FOCUS_DIAG editableDescendantClass=${editableTarget.className} " +
                    "bounds=${Rect().also { editableTarget.getBoundsInScreen(it) }}",
            )
        }
        return try {
            if (isPaymentSensitive(editableTarget)) {
                return fail(i, "set_text_on_focus", "blocked", "Blocked: focused field matched the payment-sensitive pattern.")
            }
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val accepted = editableTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i("BENSON_AUDIO", "SET_TEXT_ON_FOCUS_DIAG action_set_text_accepted=$accepted")
            if (accepted) ok(i, "set_text_on_focus")
            else rejected(i, "set_text_on_focus", "Focused input field found but SET_TEXT was not accepted.")
        } finally {
            if (editableTarget !== focused) editableTarget.recycle()
            focused.recycle()
        }
    }

    // ROUND_SPOTIFY_GOVERNANCE_1 — bounded DFS for the first isEditable descendant within a
    // merged-semantics focused node's own subtree (never the whole screen).
    private fun findEditableDescendant(node: AccessibilityNodeInfo, counter: IntArray, depth: Int): AccessibilityNodeInfo? {
        if (counter[0]++ >= MAX_NODES || depth >= MAX_DEPTH) return null
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (c in 0 until node.childCount) {
            val child = node.getChild(c) ?: continue
            val hit = findEditableDescendant(child, counter, depth + 1)
            child.recycle()
            if (hit != null) return hit
        }
        return null
    }

    private suspend fun doAssertPresent(i: Int, step: JSONObject): CommandResult {
        val match = step.optJSONObject("match")
            ?: return fail(i, "assert_present", "invalid", "Missing \"match\".")
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS)
        val found = waitForCandidates(timeout, null, match)
        return if (found != null) ok(i, "assert_present")
        else fail(i, "assert_present", "timeout", "Expected node did not appear within ${timeout}ms.")
    }

    private suspend fun doAssertGone(i: Int, step: JSONObject): CommandResult {
        val match = step.optJSONObject("match")
            ?: return fail(i, "assert_gone", "invalid", "Missing \"match\".")
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (findAllMatching(match).isEmpty()) return ok(i, "assert_gone")
            delay(POLL_INTERVAL_MS)
        }
        return fail(i, "assert_gone", "timeout", "Node still present after ${timeout}ms.")
    }

    // ROUND_YOUTUBE_GOVERNANCE_1 — reads up to `limit` distinct visible labels matching `match`,
    // sorted top-to-bottom, as {"label","top"} JSON objects. Read-only: taps nothing, invents
    // nothing — every label comes from a real node's text+contentDescription right now. Generic
    // (any caller needing "what's visible on screen" can use this, not just YouTube).
    private fun doExtractList(i: Int, step: JSONObject): CommandResult {
        val match = step.optJSONObject("match")
            ?: return fail(i, "extract_list", "invalid", "Missing \"match\".")
        val limit = step.optInt("limit", 5).coerceIn(1, 20)
        // CONFIRMED LIVE (2026-09-12) — real per-video result titles all live inside the results
        // list's scrollable container; every observed leak (nav-bar "Create" button, a "new
        // content available" refresh banner, the top channel card, promo banners) sat OUTSIDE it.
        // Restricting collection to that subtree is a structural fix instead of an ever-growing
        // per-string chrome list — generic, not YouTube-specific: any caller extracting "the
        // visible list" benefits the same way.
        //
        // CONFIRMED LIVE (2026-09-12), second occurrence — the FIRST scrollable node found
        // (depth-first from root) is not always the results feed: one real run picked up an outer
        // wrapper spanning the whole page (bottom nav bar included), letting "Erstellen"/"Neue
        // Inhalte verfügbar" leak straight back in. The LARGEST scrollable node by on-screen area
        // is a much better proxy for "the actual content feed" than "the first one encountered" —
        // a small chip row or an incidental outer wrapper is rarely the biggest scrollable region
        // on screen; the results list almost always is. Scoped to extract_list only — the
        // pre-existing `scroll` action keeps using findScrollable()'s original "first" behavior,
        // unchanged, since it was already proven correct for that use.
        val all = if (match.optBoolean("withinScrollable", false)) {
            val root = service.rootInActiveWindow
            val scrollable = root?.let { findLargestScrollable(it) }
            if (scrollable != null) findAllMatchingWithin(scrollable, match) else findAllMatching(match)
        } else {
            findAllMatching(match)
        }
        val sorted = all.sortedBy { node ->
            val b = Rect(); node.getBoundsInScreen(b); b.top
        }
        val seen = LinkedHashSet<String>()
        val out = JSONArray()
        for (node in sorted) {
            val raw = "${node.text ?: ""} ${node.contentDescription ?: ""}".trim()
            if (raw.isBlank()) continue
            val key = raw.lowercase()
            if (!seen.add(key)) continue
            val b = Rect(); node.getBoundsInScreen(b)
            out.put(JSONObject().apply { put("label", raw); put("top", b.top) })
            if (out.length() >= limit) break
        }
        return CommandResult(true, i, "extract_list", "completed", null, out.toString())
    }

    // ROUND_YOUTUBE_GOVERNANCE_1 — presses the currently-focused input field's IME action
    // (ACTION_IME_ENTER, e.g. a keyboard "search"/"go" key) instead of tapping an on-screen
    // button. Generic: works for any editable field the OS currently has input-focused.
    private fun doImeAction(i: Int, step: JSONObject): CommandResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return fail(i, "ime_action", "not_found", "ACTION_IME_ENTER requires Android 11+.")
        }
        val root = service.rootInActiveWindow
            ?: return fail(i, "ime_action", "not_found", "No active window.")
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        } ?: return fail(i, "ime_action", "not_found", "No focused input field.")
        return try {
            // AccessibilityAction.ACTION_IME_ENTER (added API 30) is only exposed as an
            // AccessibilityAction object, unlike the legacy int constants (ACTION_CLICK etc.) —
            // performAction() takes its .id.
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) ok(i, "ime_action")
            else rejected(i, "ime_action", "IME enter action was not accepted.")
        } finally {
            focused.recycle()
        }
    }

    private suspend fun doAssertPackage(i: Int, step: JSONObject): CommandResult {
        val pkg = step.optString("package", "")
        if (pkg.isEmpty()) return fail(i, "assert_package", "invalid", "Missing \"package\".")
        // WA-FIX-1 — short poll, a few seconds max. No more 39–66s waits: after launch_app the
        // target window shows up in getWindows() within a beat.
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS).coerceIn(1000L, 6000L)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            // Log the window enumeration on the first attempt and ~once/sec after — informative,
            // not spammy (a working assert resolves in 1–3 attempts).
            val logWindows = (attempt == 0 || attempt % 5 == 0)
            val r = resolveForegroundPackage(pkg, logWindows)
            if (r.found) {
                Log.i("BENSON_AUDIO",
                    "ASSERT_PACKAGE_RESULT expected=$pkg found=true source=${r.source} elapsedMs=${System.currentTimeMillis() - started}")
                return ok(i, "assert_package")
            }
            attempt++
            delay(200L)
        }
        Log.i("BENSON_AUDIO",
            "ASSERT_PACKAGE_RESULT expected=$pkg found=false source=none elapsedMs=${System.currentTimeMillis() - started}")
        return fail(i, "assert_package", "wrong_package",
            "Foreground is ${service.rootInActiveWindow?.packageName} " +
                "(activity=${BensonAccessibilityService.lastForegroundPackage}), expected $pkg.")
    }

    // ------------------------------------------------------------------
    // Potrivire noduri
    // ------------------------------------------------------------------

    private class Candidates(val first: AccessibilityNodeInfo, val distinctLabels: Set<String>)

    private suspend fun waitForCandidates(
        timeoutMs: Long,
        requirePackage: String?,
        match: JSONObject,
    ): Candidates? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // BF1-c — same overlay-aware check as assert_package: a requirePackage gate must not
            // fail just because BENSON's bubble is the topmost accessibility window.
            val fgOk = requirePackage == null || foregroundPackageMatches(requirePackage)
            if (fgOk) {
                val all = findAllMatching(match)
                if (all.isNotEmpty()) {
                    val labels = all.map { nodeLabel(it) }.filter { it.isNotEmpty() }.toSet()
                    return Candidates(all.first(), labels)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun findAllMatching(match: JSONObject): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        collect(root, match, out, IntArray(1), 0)
        return out
    }

    // ROUND_YOUTUBE_GOVERNANCE_1/2 — same as findAllMatching but scoped to a given subtree
    // (e.g. a results list's scrollable container) instead of the whole active window.
    private fun findAllMatchingWithin(start: AccessibilityNodeInfo, match: JSONObject): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        collect(start, match, out, IntArray(1), 0)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        match: JSONObject,
        out: MutableList<AccessibilityNodeInfo>,
        counter: IntArray,
        depth: Int,
    ) {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return
        counter[0]++
        if (nodeMatches(node, match)) out.add(node)
        for (c in 0 until node.childCount) {
            val child = node.getChild(c) ?: continue
            collect(child, match, out, counter, depth + 1)
        }
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, match: JSONObject): Boolean {
        val viewId = node.viewIdResourceName ?: ""

        match.optString("viewId", "").takeIf { it.isNotEmpty() }?.let {
            if (viewId != it) return false
        }
        match.optString("viewIdContains", "").takeIf { it.isNotEmpty() }?.let {
            if (!viewId.contains(it)) return false
        }
        if (match.optBoolean("excludeSearchUi", false) && viewId.contains("search")) return false
        if (match.optBoolean("excludeAvatars", match.has("textContains"))) {
            if (viewId.contains("photo") || viewId.contains("picture") || viewId.contains("avatar")) return false
            if (node.className == "android.widget.ImageView") return false
        }
        if (match.optBoolean("clickable", false) && !node.isClickable) return false
        if (match.optBoolean("editable", false) && !node.isEditable) return false
        // ROUND_YOUTUBE_GOVERNANCE_2 — generic Android widget-class filter (e.g. "SeekBar" for a
        // media player's scrub bar). A structural signal that survives a media player's controls
        // auto-hiding their text/contentDescription labels a few seconds into playback, unlike a
        // "pause" button label match.
        match.optString("classNameContains", "").takeIf { it.isNotEmpty() }?.let { needle ->
            if (!(node.className?.toString() ?: "").contains(needle)) return false
        }

        match.optString("textContains", "").takeIf { it.isNotEmpty() }?.let { needle ->
            val label = nodeLabel(node)
            if (label.isEmpty()) return false
            val n = needle.lowercase()
            val okText = if (match.optBoolean("wholeWord", false)) containsWholeWord(label, n)
                         else label.contains(n)
            if (!okText) return false
        }

        // ROUND_YOUTUBE_GOVERNANCE_1 — OR-of-substrings, for semantic labels that differ by app
        // language (e.g. a "search" icon whose contentDescription is "Search"/"Căutare"/"Suchen"
        // depending on locale) without needing a separate hardcoded lookup per feature.
        match.optJSONArray("textContainsAny")?.let { needles ->
            if (needles.length() > 0) {
                val label = nodeLabel(node)
                if (label.isEmpty()) return false
                var matchedAny = false
                for (k in 0 until needles.length()) {
                    val n = needles.optString(k, "").lowercase()
                    if (n.isNotEmpty() && label.contains(n)) { matchedAny = true; break }
                }
                if (!matchedAny) return false
            }
        }

        match.optInt("maxTopPercent", 0).takeIf { it > 0 }?.let { pct ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val maxTop = (service.resources.displayMetrics.heightPixels * pct / 100.0).toInt()
            if (bounds.top > maxTop) return false
        }
        // ROUND_YOUTUBE_GOVERNANCE_1 — inverse of maxTopPercent: excludes chrome ABOVE this
        // percentage of the screen (e.g. a search bar / top nav row) from a results extraction.
        match.optInt("minTopPercent", -1).takeIf { it >= 0 }?.let { pct ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val minTop = (service.resources.displayMetrics.heightPixels * pct / 100.0).toInt()
            if (bounds.top < minTop) return false
        }
        return true
    }

    // Whole-word: lecția Ana/Adriana — caracterul dinainte și de după potrivire nu pot fi litere.
    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val idx = haystack.indexOf(needle)
        if (idx == -1) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetter()
        val afterIdx = idx + needle.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetter()
        return beforeOk && afterOk
    }

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

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase().trim()
    }

    private fun ok(i: Int, action: String) = CommandResult(true, i, action, "completed")
    private fun fail(i: Int, action: String, status: String, detail: String) =
        CommandResult(false, i, action, status, detail)

    // E1-6 (2026-09-07, product-owner-directed): un performAction()/performGlobalAction() care
    // returnează false NU produce niciodată succes (deja așa era aici) — în plus, fiecare astfel de
    // respingere lasă o urmă explicită în log. Tag BENSON_AUDIO ca restul lanțului audio.
    private fun rejected(i: Int, action: String, detail: String): CommandResult {
        Log.i("BENSON_AUDIO", "ACTION_REJECTED action=$action step=$i detail=\"$detail\"")
        return CommandResult(false, i, action, "tap_rejected", detail)
    }
}
