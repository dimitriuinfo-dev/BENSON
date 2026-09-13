package expo.modules.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * RUNDA ACC-1 — dovada atomică de interacțiune într-o altă aplicație.
 *
 * NU face parte din nicio rețetă / feature BENSON. E un harness de diagnostic, declanșat DOAR
 * printr-un broadcast explicit (`com.benson.acc1.RUN`, vezi BensonAccessibilityService), care:
 *   1. deschide Calculator prin mecanismul existent (executeCommand → launch_app),
 *   2. așteaptă fereastra activă a Calculatorului,
 *   3. citește `rootInActiveWindow` și traversează arborele Accessibility,
 *   4. găsește butonul „7",
 *   5. execută `performAction(ACTION_CLICK)`,
 *   6. re-citește arborele și demonstrează că „7" a apărut în display.
 * Fallback: `dispatchGesture()` pe centrul boundsInScreen (doar dacă ACTION_CLICK n-a avut efect).
 *
 * TEST 2 (WhatsApp) rulează NUMAI dacă TEST 1 e PASS: deschide WhatsApp, apasă un element sigur și
 * reversibil (lupa de căutare), verifică schimbarea UI, apoi revine cu BACK. Zero mesaje, zero apel.
 *
 * Toate liniile sub tag-ul `BENSON_AUDIO`, formatele exacte cerute de rundă.
 */
class AccessibilityFoundationTest(private val service: BensonAccessibilityService) {

    companion object {
        private const val T = "BENSON_AUDIO"
        private const val MAX_NODES = 1200
        private const val MAX_DEPTH = 60
        private val CALC_CANDIDATES = listOf(
            "com.oneplus.calculator",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.digitalchemy.calculator.freedecimal",
        )
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }

    private data class NodeHit(
        val node: AccessibilityNodeInfo,
        val viewId: String?,
        val text: String,
        val desc: String,
        val className: String,
        val clickable: Boolean,
        val enabled: Boolean,
        val bounds: Rect,
    )

    // WA-FIX-1 — standalone probe: launch WhatsApp, run assert_package through the executor with
    // BENSON's overlay present, dump the window list. Independent of the calculator test.
    suspend fun runWaFix1Probe() {
        Log.i(T, "WAFIX1_PROBE begin ts=${System.currentTimeMillis()}")
        try {
            val launch = service.executeCommand(
                JSONObject().put("steps", org.json.JSONArray().put(
                    JSONObject().put("action", "launch_app").put("package", WHATSAPP_PACKAGE)
                ))
            )
            Log.i(T, "WAFIX1_PROBE launch success=${launch.success} status=${launch.status}")
            if (!launch.success) return
            val apStart = System.currentTimeMillis()
            val ap = service.executeCommand(
                JSONObject().put("steps", org.json.JSONArray().put(
                    JSONObject().put("action", "assert_package").put("package", WHATSAPP_PACKAGE).put("timeoutMs", 4000)
                ))
            )
            Log.i(T, "WAFIX1_PROBE assert_package success=${ap.success} status=${ap.status} elapsedMs=${System.currentTimeMillis() - apStart}")
            if (ap.success) Log.i(T, "WHATSAPP_ASSERT_PACKAGE = PASS")
            else Log.i(T, "WHATSAPP_ASSERT_PACKAGE = FAIL (see ASSERT_WINDOW lines above)")
        } catch (e: Exception) {
            Log.i(T, "WAFIX1_PROBE exception=${e.message}")
            Log.i(T, "WHATSAPP_ASSERT_PACKAGE = FAIL")
        }
        Log.i(T, "WAFIX1_PROBE end ts=${System.currentTimeMillis()}")
    }

    suspend fun run(requestedCalcPkg: String?) {
        Log.i(T, "ACC_TEST begin ts=${System.currentTimeMillis()}")
        val calcPass = try {
            runCalculatorTest(requestedCalcPkg)
        } catch (e: Exception) {
            Log.i(T, "ACC_TEST error phase=calculator message=${e.message}")
            false
        }
        Log.i(T, "ACC_TEST calculator result=${if (calcPass) "PASS" else "FAIL"}")

        if (!calcPass) {
            Log.i(T, "ACCESSIBILITY_FOUNDATION = FAIL")
            Log.i(T, "ACC_TEST stop reason=calculator_not_pass (Test 2 WhatsApp skipped by design)")
            return
        }
        Log.i(T, "ACCESSIBILITY_FOUNDATION = PASS")

        try {
            runWhatsAppTest()
        } catch (e: Exception) {
            Log.i(T, "ACC_TEST error phase=whatsapp message=${e.message}")
        }
        Log.i(T, "ACC_TEST end ts=${System.currentTimeMillis()}")
    }

    // ── TEST 1 — Calculator ──────────────────────────────────────────────────────────────────────
    private suspend fun runCalculatorTest(requestedCalcPkg: String?): Boolean {
        // Pas 1 — Launch (prin mecanismul existent: BensonCommandExecutor.doLaunchApp)
        val pkg = resolveCalculatorPackage(requestedCalcPkg)
        if (pkg == null) {
            Log.i(T, "ACC_TEST launch package=<none> — no calculator package installed among $CALC_CANDIDATES")
            return false
        }
        Log.i(T, "ACC_TEST launch package=$pkg")
        val launch = service.executeCommand(
            JSONObject().put("steps", org.json.JSONArray().put(
                JSONObject().put("action", "launch_app").put("package", pkg)
            ))
        )
        if (!launch.success) {
            Log.i(T, "ACC_TEST launch failed status=${launch.status} detail=${launch.detail}")
            return false
        }

        // Pas 2 — Detect active window
        val win = awaitActiveWindow(pkg, 8000)
        if (win == null) {
            val seen = service.rootInActiveWindow?.packageName?.toString()
            Log.i(T, "ACC_WINDOW pkg=<not-detected> expected=$pkg lastSeen=${seen ?: "null"} — STOP")
            Log.i(T, "ACC_TEST stop reason=calculator_window_not_active seen=${seen ?: "null"}")
            return false
        }
        Log.i(T, "ACC_WINDOW pkg=$pkg class=${win.className} type=${win.windowType}")

        // Pas 3 — Accessibility snapshot
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.i(T, "ACC_SNAPSHOT pkg=$pkg nodes=0 — rootInActiveWindow == null — STOP")
            return false
        }
        val all = ArrayList<NodeHit>()
        val counter = intArrayOf(0)
        collect(root, 0, counter, all)
        Log.i(T, "ACC_SNAPSHOT pkg=$pkg nodes=${counter[0]} relevant=${all.size}")
        // Loghează elementele relevante (clickable sau cu text/desc), până la 40 de linii.
        all.asSequence()
            .filter { it.clickable || it.text.isNotBlank() || it.desc.isNotBlank() }
            .take(40)
            .forEach {
                Log.i(
                    T,
                    "ACC_NODE viewId=${it.viewId ?: "-"} text=${q(it.text)} desc=${q(it.desc)} " +
                        "class=${it.className} clickable=${it.clickable} enabled=${it.enabled} " +
                        "bounds=${rectStr(it.bounds)}",
                )
            }

        // Reset the calculator so the baseline is clean (a previous run may have left "7").
        resetCalculator(all)
        val fresh = ArrayList<NodeHit>()
        service.rootInActiveWindow?.let { collect(it, 0, intArrayOf(0), fresh) }
        val work = if (fresh.size >= 20) fresh else all

        // Pas 4 — Find digit 7 (butonul, NU câmpul de display)
        val target = findDigit7(work)
        if (target == null) {
            Log.i(T, "ACC_TARGET found=false")
            return false
        }
        Log.i(
            T,
            "ACC_TARGET found=true viewId=${target.viewId ?: "-"} text=${q(target.text)} " +
                "desc=${q(target.desc)} class=${target.className} clickable=${target.clickable} " +
                "enabled=${target.enabled} bounds=${rectStr(target.bounds)}",
        )

        // Baseline display value BEFORE the click, so "observed" is a real delta not a pre-existing 7.
        val before = readDisplayFresh()
        Log.i(T, "ACC_BASELINE display=${q(before)}")

        // Pas 5 — Click (ACTION_CLICK; urcă la primul strămoș clickable dacă nodul găsit nu e clickable)
        val clickNode = if (target.node.isClickable) target.node else clickableAncestor(target.node) ?: target.node
        val clickResult = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(T, "ACC_CLICK action=ACTION_CLICK result=$clickResult")

        // Pas 6 — Verify effect
        delay(500)
        var observed = readDisplayFresh()
        var success = displayShows7(observed, before)
        Log.i(T, "ACC_VERIFY expected=7 observed=${q(observed)} success=$success")
        if (success) return true

        // ── FALLBACK DIAGNOSTIC — un singur dispatchGesture pe centrul boundsInScreen ─────────────
        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        val gestureOk = dispatchTap(cx.toFloat(), cy.toFloat())
        Log.i(T, "ACC_GESTURE x=$cx y=$cy result=$gestureOk")
        delay(500)
        observed = readDisplayFresh()
        success = displayShows7(observed, before)
        Log.i(T, "ACC_VERIFY_GESTURE expected=7 observed=${q(observed)} success=$success")
        return success
    }

    // ── TEST 2 — WhatsApp (element sigur + reversibil: lupa de căutare, revenire cu BACK) ─────────
    private suspend fun runWhatsAppTest() {
        Log.i(T, "WA_ACC begin")
        val launch = service.executeCommand(
            JSONObject().put("steps", org.json.JSONArray().put(
                JSONObject().put("action", "launch_app").put("package", WHATSAPP_PACKAGE)
            ))
        )
        if (!launch.success) { Log.i(T, "WA_ACC launch failed status=${launch.status}"); return }

        // WA-FIX-1 probe — exercise the EXACT recipe step (assert_package) through the executor,
        // with BENSON's overlay bubble present, and log how long it takes.
        val apStart = System.currentTimeMillis()
        val ap = service.executeCommand(
            JSONObject().put("steps", org.json.JSONArray().put(
                JSONObject().put("action", "assert_package").put("package", WHATSAPP_PACKAGE).put("timeoutMs", 4000)
            ))
        )
        Log.i(T, "WA_ACC_ASSERT_PACKAGE success=${ap.success} status=${ap.status} elapsedMs=${System.currentTimeMillis() - apStart}")

        val win = awaitActiveWindow(WHATSAPP_PACKAGE, 8000)
        if (win == null) {
            Log.i(T, "WA_ACC_WINDOW pkg=<not-detected> expected=$WHATSAPP_PACKAGE — STOP")
            return
        }
        Log.i(T, "WA_ACC_WINDOW pkg=$WHATSAPP_PACKAGE class=${win.className} type=${win.windowType}")

        // Let the chat list settle (fresh launch can snapshot mid-transition with few nodes).
        var all = ArrayList<NodeHit>()
        var tries = 0
        while (tries < 10) {
            all = ArrayList()
            service.rootInActiveWindow?.let { collect(it, 0, intArrayOf(0), all) }
            if (all.size >= 20) break
            delay(300)
            tries++
        }
        Log.i(T, "WA_ACC_SNAPSHOT nodes=${all.size}")

        // Element sigur + reversibil: bara/butonul de căutare. Deschide UI-ul de căutare; BACK îl
        // închide. Zero mesaj, zero apel. viewId întâi (independent de limbă), apoi contentDescription.
        val searchBtn = all.firstOrNull { h ->
            h.viewId?.let { v ->
                v.endsWith("/search_bar_inner_layout") || v.endsWith("/search_bar") ||
                v.endsWith("/menuitem_search") || v.contains("search_bar")
            } == true
        } ?: all.firstOrNull { h ->
            h.desc.lowercase().let { it.contains("suchen") || it.contains("such") || it.contains("search") }
        }
        if (searchBtn == null) { Log.i(T, "WA_ACC_TARGET found=false (search bar/button)"); return }
        Log.i(
            T,
            "WA_ACC_TARGET found=true viewId=${searchBtn.viewId ?: "-"} desc=${q(searchBtn.desc)} " +
                "class=${searchBtn.className} clickable=${searchBtn.clickable} bounds=${rectStr(searchBtn.bounds)}",
        )

        val editBefore = all.count { it.editable() || it.className.contains("EditText") }
        val node = if (searchBtn.node.isClickable) searchBtn.node else clickableAncestor(searchBtn.node) ?: searchBtn.node
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(T, "WA_ACC_CLICK result=$clicked")

        // Verify a real UI change: the search EditText appeared (or an EditText that wasn't there
        // before). Poll — same stale-cache reason as the calculator display.
        var searchFieldAppeared = false
        var afterNodes = 0
        var why = "none"
        var poll = 0
        while (poll < 12) {
            val after = ArrayList<NodeHit>()
            service.rootInActiveWindow?.let { collect(it, 0, intArrayOf(0), after) }
            afterNodes = after.size
            val editNow = after.count { it.editable() || it.className.contains("EditText") }
            val hasSearchInput = after.any {
                it.viewId?.let { v -> v.contains("search_input") || v.contains("search_src_text") } == true
            }
            val hasCancelDesc = after.any { it.desc.lowercase().let { d -> d.contains("zurück") || d.contains("navigate up") || d.contains("suche verlassen") } }
            if (hasSearchInput) { searchFieldAppeared = true; why = "search_input_present"; break }
            if (editNow > editBefore) { searchFieldAppeared = true; why = "edittext_appeared($editBefore->$editNow)"; break }
            if (hasCancelDesc && afterNodes in 1..15) { searchFieldAppeared = true; why = "search_mode_back_affordance"; break }
            delay(300)
            poll++
        }
        Log.i(T, "WA_ACC_VERIFY success=$searchFieldAppeared why=$why observedNodes=$afterNodes editBefore=$editBefore")

        // Reversibil: închide UI-ul de căutare, revino la lista de chat-uri.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.i(T, "WA_ACC restore=back_pressed")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun resolveCalculatorPackage(requested: String?): String? {
        val pm = service.packageManager
        val ordered = (listOfNotNull(requested) + CALC_CANDIDATES).distinct()
        for (p in ordered) {
            try {
                if (pm.getLaunchIntentForPackage(p) != null) return p
            } catch (_: Exception) {}
        }
        return null
    }

    private data class WinInfo(val className: String, val windowType: String)

    private suspend fun awaitActiveWindow(pkg: String, timeoutMs: Long): WinInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            val rootPkg = root?.packageName?.toString()
            val lastFg = BensonAccessibilityService.lastForegroundPackage
            if (rootPkg == pkg || lastFg == pkg) {
                val cls = root?.className?.toString() ?: "?"
                val wt = windowTypeName(root)
                return WinInfo(cls, wt)
            }
            delay(200)
        }
        return null
    }

    private fun windowTypeName(root: AccessibilityNodeInfo?): String = try {
        when (root?.window?.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
            null -> "null"
            else -> "type_${root.window?.type}"
        }
    } catch (e: Exception) { "unavailable(${e.message})" }

    private fun collect(node: AccessibilityNodeInfo, depth: Int, counter: IntArray, out: MutableList<NodeHit>) {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return
        counter[0]++
        val b = Rect(); node.getBoundsInScreen(b)
        out.add(
            NodeHit(
                node = node,
                viewId = node.viewIdResourceName,
                text = node.text?.toString().orEmpty(),
                desc = node.contentDescription?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                bounds = b,
            )
        )
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, depth + 1, counter, out)
        }
    }

    private fun isDisplayField(h: NodeHit): Boolean {
        val v = h.viewId?.lowercase() ?: return false
        return v.contains("formula") || v.contains("result") || v.contains("expression") || v.contains("display")
    }

    private fun findDigit7(all: List<NodeHit>): NodeHit? {
        // Butonul „7", NU câmpul de display (care poate conține deja un „7"). Prioritate:
        //   1) viewId de tip digit  2) text exact „7" pe un Button  3) contentDescription „7"
        val candidates = all.filterNot { isDisplayField(it) }
        candidates.firstOrNull {
            val v = it.viewId?.lowercase() ?: return@firstOrNull false
            v.endsWith("digit_7") || v.endsWith("digit7") || v.endsWith("btn_7") || v.endsWith("key_7") || v.endsWith("/7")
        }?.let { return it }
        candidates.firstOrNull { it.text.trim() == "7" && it.className.contains("Button") }?.let { return it }
        candidates.firstOrNull { it.text.trim() == "7" }?.let { return it }
        candidates.firstOrNull { it.desc.trim() == "7" }?.let { return it }
        return candidates.firstOrNull { it.clickable && Regex("(^|\\D)7(\\D|$)").containsMatchIn(it.desc) }
    }

    // Reset the calculator (tap the clear key) so the baseline display is empty before the click.
    private suspend fun resetCalculator(all: List<NodeHit>) {
        val clr = all.firstOrNull {
            val v = it.viewId?.lowercase() ?: return@firstOrNull false
            v.endsWith("/clr") || v.contains("clear") || v.endsWith("/del")
        } ?: all.firstOrNull { it.desc.lowercase().let { d -> d.contains("löschen") || d.contains("clear") || d.contains("șterge") || d.contains("sterge") } }
        if (clr != null) {
            val n = if (clr.node.isClickable) clr.node else clickableAncestor(clr.node) ?: clr.node
            repeat(3) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(120) }
            delay(300)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur = node.parent
        var guard = 0
        while (cur != null && guard < 12) {
            if (cur.isClickable) return cur
            cur = cur.parent
            guard++
        }
        return null
    }

    // Display value: preferă un viewId de rezultat/expresie; altfel primul EditText/TextView ne-clickable mare.
    private fun readDisplay(all: List<NodeHit>): String {
        val byId = all.firstOrNull {
            val v = it.viewId?.lowercase() ?: return@firstOrNull false
            v.contains("result") || v.contains("formula") || v.contains("expression") ||
                v.contains("display") || v.contains("input") || v.contains("edit")
        }
        if (byId != null && byId.text.isNotBlank()) return byId.text.trim()
        val bigText = all.filter { !it.clickable && it.text.isNotBlank() &&
            (it.className.contains("EditText") || it.className.contains("TextView")) }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
        return bigText?.text?.trim().orEmpty()
    }

    // The service subscribes only to typeWindowStateChanged|typeWindowContentChanged (config xml,
    // CPU/thermal reasons), so a calculator formula update — driven by TYPE_VIEW_TEXT_CHANGED —
    // is NOT reflected in rootInActiveWindow's cached subtree within the verify window. Fix on the
    // read side, no new subscription: pull rootInActiveWindow FRESH and call
    // AccessibilityNodeInfo.refresh() on the display node (on-demand IPC to the app) before
    // reading its text. Poll a few times because the repaint can lag the click by a beat.
    private suspend fun readDisplayFresh(): String {
        repeat(8) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val all = ArrayList<NodeHit>()
                collect(root, 0, intArrayOf(0), all)
                // Find the display node, refresh() it (on-demand IPC — bypasses the stale cache),
                // then read its CURRENT text directly.
                val display = all.firstOrNull {
                    val v = it.viewId?.lowercase() ?: return@firstOrNull false
                    v.endsWith("/formula") || v.endsWith("/result") || v.contains("expression") || v.contains("display")
                }
                if (display != null) {
                    display.node.refresh()
                    val direct = display.node.text?.toString()?.trim().orEmpty()
                    if (direct.isNotBlank()) return direct
                }
                val walked = readDisplay(all)
                if (walked.isNotBlank()) return walked
            }
            delay(300)
        }
        return ""
    }

    private fun displayShows7(observed: String, before: String): Boolean {
        if (observed.isBlank()) return false
        if (!observed.contains("7")) return false
        // Un delta real: fie displayul era gol/"0"/diferit înainte, fie acum are un "7" în plus.
        if (before.isBlank() || before == "0") return true
        if (observed != before && observed.contains("7")) return true
        val add = observed.count { it == '7' } - before.count { it == '7' }
        return add >= 1
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gd = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            var done = false
            var ok = false
            val posted = service.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; done = true }
                override fun onCancelled(g: GestureDescription?) { ok = false; done = true }
            }, null)
            if (!posted) return false
            var waited = 0
            while (!done && waited < 1000) { delay(50); waited += 50 }
            ok
        } catch (e: Exception) {
            Log.i(T, "ACC_GESTURE dispatch_exception=${e.message} (canPerformGestures likely false)")
            false
        }
    }

    private fun NodeHit.editable(): Boolean = node.isEditable
    private fun q(s: String): String = "\"" + s.replace("\"", "'").take(60) + "\""
    private fun rectStr(r: Rect): String = "[${r.left},${r.top}][${r.right},${r.bottom}]"
}
