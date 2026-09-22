package expo.modules.accessibility

import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

// CALC1 (2026-09-22) — executes an ALREADY-PARSED ordered list of calculator button symbols and
// reads the real result display back. Text->operation parsing (Task 1: Romanian number words,
// operation keywords) lives in JS (lib/tools/toolRegistry.ts) — this file only presses buttons
// and reads text, reusing the service's own waitForNode/ACTION_CLICK (widened to `internal` on
// BensonAccessibilityService, never duplicated — see that function's own comment).
//
// Real button/display IDs discovered 2026-09-22 via uiautomator dump on the installed OnePlus
// "Rechner" (com.oneplus.calculator) — NOT assumed, NOT standard-Android IDs:
//   digits: digit_0..digit_9, dec_point (",")
//   ops: op_add(+) op_sub(-) op_mul(*) op_div(/) op_pct(%)
//   op_sqrt(√) — SCIENTIFIC MODE ONLY (toggle: item_science_calculator), and PREFIX order:
//     tapping the digit THEN √ produces an unparseable formula ("Ausdrucksfehler" on "="); the
//     working order is √ THEN the digit — confirmed empirically, step by step, before writing
//     this file. toolRegistry.ts must emit symbols in that order.
//   control: clr (AC) del (backspace) eq (=)
//   display: formula (live expression, informational only) / result (TextView — live-computed
//     value while typing, and the finalized answer after "=" — this is what CALC_RESULT reads)
internal class CalculatorRecipe(private val service: BensonAccessibilityService) {

    data class Outcome(
        val success: Boolean,
        val resultText: String?,
        val changed: Boolean,
        val failedStep: String?,
        val error: String?,
    )

    companion object {
        private const val PKG = "com.oneplus.calculator"
        private const val NODE_WAIT_TIMEOUT_MS = 2000L
        private const val TAG = "BENSON_AUDIO"

        private val SYMBOL_IDS = mapOf(
            "0" to "digit_0", "1" to "digit_1", "2" to "digit_2", "3" to "digit_3",
            "4" to "digit_4", "5" to "digit_5", "6" to "digit_6", "7" to "digit_7",
            "8" to "digit_8", "9" to "digit_9", "," to "dec_point",
            "+" to "op_add", "-" to "op_sub", "*" to "op_mul", "/" to "op_div",
            "%" to "op_pct", "√" to "op_sqrt", "=" to "eq", "AC" to "clr", "DEL" to "del",
        )
        private val SCIENTIFIC_ONLY = setOf("√")
    }

    // Pas 1-4 din Task 2: pentru fiecare simbol, waitForNode -> ACTION_CLICK, cu log per pas.
    // Oprire imediata la primul buton negasit — niciodata apasare la nimereala.
    suspend fun run(symbols: List<String>): Outcome {
        if (symbols.isEmpty()) return Outcome(false, null, false, "PARSE", "Empty symbol list.")
        for (s in symbols) {
            if (s !in SYMBOL_IDS) {
                Log.i(TAG, "CALC_UNSUPPORTED text=${quote(symbols.joinToString(" "))} symbol=${quote(s)}")
                return Outcome(false, null, false, "PARSE", "Unsupported symbol: $s")
            }
        }

        if (!ensureCalculatorForeground()) {
            return Outcome(false, null, false, "OPEN_APP", "Calculator did not reach the foreground.")
        }

        // "Fiecare calcul incepe dintr-o expresie curata" — clear first, always, before every run.
        val acPress = pressById("clr", "AC_start")
        if (!acPress.success) {
            return Outcome(false, null, false, "AC", "Could not clear the expression before starting (${acPress.reason}).")
        }
        delay(150)

        if (symbols.any { it in SCIENTIFIC_ONLY } && !ensureScientificMode()) {
            return Outcome(false, null, false, "SCIENTIFIC_MODE", "Could not switch to scientific mode.")
        }

        val before = readDisplay("result")

        for ((index, symbol) in symbols.withIndex()) {
            val viewId = SYMBOL_IDS.getValue(symbol)
            val startedAt = System.currentTimeMillis()
            val press = pressById(viewId, symbol)
            val elapsed = System.currentTimeMillis() - startedAt
            Log.i(TAG, "CALC_STEP index=$index symbol=${quote(symbol)} found=${press.reason != "not_found"} success=${press.success} reason=${press.reason} elapsedMs=$elapsed")
            if (!press.success) {
                val detail = if (press.reason == "not_found") "Button not found within ${NODE_WAIT_TIMEOUT_MS}ms." else "Button was found but the tap was rejected."
                return Outcome(false, null, false, "step_$index:$symbol", detail)
            }
        }

        delay(200) // let the final render settle before reading
        val after = readDisplay("result")
        val changed = before != after
        Log.i(TAG, "CALC_RESULT text=${quote(after)} changed=$changed")

        if (after.isNullOrBlank() || after.contains("fehler", ignoreCase = true) || after.contains("error", ignoreCase = true)) {
            return Outcome(false, after, changed, "READ_RESULT", "Result is empty or an expression error.")
        }
        return Outcome(true, after, changed, null, null)
    }

    private fun quote(s: String?): String = "\"${(s ?: "").replace("\"", "\\\"")}\""

    private suspend fun ensureCalculatorForeground(): Boolean {
        val root = service.rootInActiveWindow
        val already = root?.packageName?.toString() == PKG
        root?.recycle()
        if (already) return true

        val intent = service.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.i(TAG, "CALC_STEP_FAILED symbol=- step=OPEN_APP reason=${quote(e.message)}")
            return false
        }

        val deadline = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < deadline) {
            val r = service.rootInActiveWindow
            val pkg = r?.packageName?.toString()
            r?.recycle()
            if (pkg == PKG) return true
            delay(150)
        }
        return false
    }

    // BUG FOUND ON SECOND DEVICE RUN (2026-09-22): op_sqrt matched resource-id even while the
    // BASIC keypad was showing (scientific panel not visible) — clickable=true, enabled=false,
    // confirmed by screenshot: the node exists in the layout (likely an off-screen/collapsed
    // scientific pane) but isn't the one currently on screen. The "already" probe below must
    // also require isVisibleToUser, or it wrongly concludes scientific mode is already active
    // and never presses the toggle.
    //
    // If op_sqrt is genuinely VISIBLE, scientific mode is already on. Otherwise tap the toggle
    // (item_science_calculator) and wait for a visible op_sqrt before proceeding.
    private suspend fun ensureScientificMode(): Boolean {
        val already = service.waitForNode(timeoutMs = 300, requirePackage = PKG, anchor = "op_sqrt_probe") {
            it.viewIdResourceName == "$PKG:id/op_sqrt" && it.isVisibleToUser
        }
        if (already != null) {
            already.recycle()
            return true
        }
        if (!pressById("item_science_calculator", "scientific_toggle").success) return false
        val node = service.waitForNode(timeoutMs = NODE_WAIT_TIMEOUT_MS, requirePackage = PKG, anchor = "op_sqrt_after_toggle") {
            it.viewIdResourceName == "$PKG:id/op_sqrt" && it.isVisibleToUser
        }
        node?.recycle()
        return node != null
    }

    // BUG FOUND ON FIRST DEVICE RUN (2026-09-22): op_sqrt was FOUND instantly (waitForNode
    // foundAfterMs=3) but ACTION_CLICK was REJECTED — the old code conflated "click rejected"
    // with "button not found", reporting a misleading "Button not found within 2000ms" to the
    // user when the real problem was a rejected click. Now distinguished explicitly, and given
    // one short retry (a freshly-toggled scientific-mode button can need a moment to settle)
    // before genuinely giving up — never a blind re-tap at different coordinates, same node.
    private suspend fun pressById(shortId: String, anchor: String): PressResult {
        val node = service.waitForNode(
            timeoutMs = NODE_WAIT_TIMEOUT_MS,
            requirePackage = PKG,
            anchor = anchor,
        ) { it.viewIdResourceName == "$PKG:id/$shortId" && it.isVisibleToUser } ?: return PressResult(false, "not_found")
        try {
            Log.i(TAG, "CALC_NODE_STATE symbol=$anchor clickable=${node.isClickable} enabled=${node.isEnabled}")
            var accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!accepted) {
                Log.i(TAG, "ACTION_REJECTED action=click engine=calculator_recipe symbol=$anchor attempt=1")
                delay(150)
                accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!accepted) Log.i(TAG, "ACTION_REJECTED action=click engine=calculator_recipe symbol=$anchor attempt=2")
            }
            return PressResult(accepted, if (accepted) "ok" else "click_rejected")
        } finally {
            node.recycle()
        }
    }

    private data class PressResult(val success: Boolean, val reason: String)

    // Re-read via a fresh waitForNode probe rather than reusing/refreshing a held node reference
    // (WA-DIAG lesson: a stale node can report an old value after the tree updates).
    private suspend fun readDisplay(shortId: String): String? {
        val node = service.waitForNode(timeoutMs = 800, requirePackage = PKG, anchor = "read_$shortId") {
            it.viewIdResourceName == "$PKG:id/$shortId"
        } ?: return null
        try {
            return node.text?.toString()
        } finally {
            node.recycle()
        }
    }
}
