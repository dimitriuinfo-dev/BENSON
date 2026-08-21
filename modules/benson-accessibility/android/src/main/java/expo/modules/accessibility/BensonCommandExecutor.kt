package expo.modules.accessibility

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
    }

    data class CommandResult(
        val success: Boolean,
        val stepIndex: Int,
        val action: String,
        val status: String,          // completed | not_found | ambiguous | blocked | tap_rejected | wrong_package | invalid | timeout
        val detail: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("success", success)
            put("stepIndex", stepIndex)
            put("action", action)
            put("status", status)
            put("detail", detail ?: JSONObject.NULL)
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

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i)
                ?: return CommandResult(false, i, "none", "invalid", "Step $i is not an object.")
            val action = step.optString("action", "")
            Log.i(TAG, "step $i: $action")

            val result: CommandResult = when (action) {
                "launch_app" -> doLaunchApp(i, step)
                "wait" -> { delay(step.optLong("ms", 500L).coerceIn(50L, 10_000L)); ok(i, action) }
                "click" -> doClick(i, step)
                "set_text" -> doSetText(i, step)
                "assert_present" -> doAssertPresent(i, step)
                "assert_gone" -> doAssertGone(i, step)
                "assert_package" -> doAssertPackage(i, step)
                "back" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) ok(i, action)
                          else fail(i, action, "tap_rejected", "Global BACK was not accepted.")
                "home" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) ok(i, action)
                          else fail(i, action, "tap_rejected", "Global HOME was not accepted.")
                "recents" -> if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) ok(i, action)
                          else fail(i, action, "tap_rejected", "Global RECENTS was not accepted.")
                "scroll" -> doScroll(i, step)
                "return_to_benson" -> { returnToBenson(); ok(i, action) }
                else -> fail(i, action, "invalid", "Unknown action \"$action\".")
            }

            if (!result.success) {
                Log.w(TAG, "step $i ($action) failed: ${result.status} — ${result.detail}")
                return result
            }
        }
        return CommandResult(true, steps.length() - 1, "done", "completed")
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
        else fail(i, "scroll", "tap_rejected", "Scrollable node found but scroll was not accepted (already at the edge?).")
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
        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ok(i, "click")
        else fail(i, "click", "tap_rejected", "Node found but the tap was not accepted.")
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
        else fail(i, "set_text", "tap_rejected", "Editable node found but SET_TEXT was not accepted.")
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

    private suspend fun doAssertPackage(i: Int, step: JSONObject): CommandResult {
        val pkg = step.optString("package", "")
        if (pkg.isEmpty()) return fail(i, "assert_package", "invalid", "Missing \"package\".")
        val timeout = step.optLong("timeoutMs", DEFAULT_STEP_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (service.rootInActiveWindow?.packageName?.toString() == pkg) return ok(i, "assert_package")
            delay(POLL_INTERVAL_MS)
        }
        return fail(i, "assert_package", "wrong_package",
            "Foreground is ${service.rootInActiveWindow?.packageName}, expected $pkg.")
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
            val fgOk = requirePackage == null ||
                service.rootInActiveWindow?.packageName?.toString() == requirePackage
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

        match.optString("textContains", "").takeIf { it.isNotEmpty() }?.let { needle ->
            val label = nodeLabel(node)
            if (label.isEmpty()) return false
            val n = needle.lowercase()
            val okText = if (match.optBoolean("wholeWord", false)) containsWholeWord(label, n)
                         else label.contains(n)
            if (!okText) return false
        }

        match.optInt("maxTopPercent", 0).takeIf { it > 0 }?.let { pct ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val maxTop = (service.resources.displayMetrics.heightPixels * pct / 100.0).toInt()
            if (bounds.top > maxTop) return false
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
}
