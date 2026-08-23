package expo.modules.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Executes a small, allow-listed UI-automation DSL from APK assets.
 *
 * Profiles are data, not downloaded code: only IDs in allowedProfiles can run and every step
 * is interpreted here. A locator is re-resolved immediately before an action, so a node from a
 * previous screen update is never tapped.
 */
internal class DeclarativeAutomationEngine(
  private val service: AccessibilityService,
  private val returnToBenson: () -> Boolean,
  private val isPaymentSensitive: (AccessibilityNodeInfo) -> Boolean,
) {
  data class Result(val success: Boolean, val step: String, val error: String? = null)

  private val allowedProfiles = mapOf(
    "whatsapp.send-message.v1" to "profiles/whatsapp.send-message.v1.json",
  )

  private val semanticLexicon = mapOf(
    "send" to listOf("send", "senden", "trimite", "trimitere"),
    "search" to listOf("search", "suche", "suchen", "cautare", "căutare"),
    "voice_call" to listOf("voice call", "sprachanruf", "apel vocal", "anruf", "call"),
  )

  suspend fun run(profileId: String, params: JSONObject): Result {
    val assetPath = allowedProfiles[profileId]
      ?: return Result(false, "policy", "Automation profile is not allow-listed.")

    val profile = try {
      service.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
    } catch (_: Exception) {
      return Result(false, "profile", "Automation profile could not be loaded.")
    }

    val expectedPackage = profile.optString("packageName")
    if (expectedPackage.isBlank()) return Result(false, "profile", "Profile has no target package.")
    if (!waitForPackage(expectedPackage, profile.optLong("entryTimeoutMs", 4_000L))) {
      return Result(false, "foreground_package", "Target app did not reach the foreground.")
    }

    val rememberedLocators = mutableMapOf<String, JSONObject>()
    val steps = profile.optJSONArray("steps") ?: return Result(false, "profile", "Profile has no steps.")
    for (index in 0 until steps.length()) {
      val step = steps.optJSONObject(index) ?: return Result(false, "profile", "Invalid profile step.")
      val id = step.optString("id", "step_$index")
      when (step.optString("kind")) {
        "waitFor" -> {
          val locator = step.optJSONObject("locator") ?: return Result(false, id, "Missing locator.")
          if (!waitForLocator(locator, expectedPackage, step.optLong("timeoutMs", 3_000L))) {
            return Result(false, id, "Expected UI element was not found.")
          }
          rememberedLocators[id] = locator
        }
        "click" -> {
          val target = step.optString("target")
          val locator = rememberedLocators[target] ?: step.optJSONObject("locator")
            ?: return Result(false, id, "Unknown click target.")
          if (!click(locator, expectedPackage)) {
            return Result(false, id, "Target could not be safely clicked.")
          }
        }
        "assert" -> {
          val condition = step.optJSONObject("condition") ?: return Result(false, id, "Missing assertion.")
          if (!waitForAssertion(condition, params, expectedPackage, step.optLong("timeoutMs", 2_500L))) {
            return Result(false, id, "Expected UI state was not observed.")
          }
        }
        "delay" -> delay(step.optLong("ms", 0L).coerceIn(0L, 5_000L))
        "returnToBenson" -> if (!returnToBenson()) return Result(false, id, "Could not return to BENSON.")
        else -> return Result(false, id, "Profile uses a disallowed step.")
      }
    }
    return Result(true, "done")
  }

  private suspend fun waitForPackage(expectedPackage: String, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (activePackage() == expectedPackage) return true
      delay(150)
    }
    return false
  }

  private fun activePackage(): String? {
    val root = service.rootInActiveWindow ?: return null
    return try {
      root.packageName?.toString()
    } finally {
      root.recycle()
    }
  }

  private suspend fun waitForLocator(locator: JSONObject, expectedPackage: String, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val match = findMatching(locator, expectedPackage)
      if (match != null) {
        match.recycle()
        return true
      }
      delay(200)
    }
    return false
  }

  private fun click(locator: JSONObject, expectedPackage: String): Boolean {
    val root = service.rootInActiveWindow ?: return false
    try {
      if (root.packageName?.toString() != expectedPackage) return false
      val node = findMatchingInTree(root, locator) ?: return false
      try {
        if (!node.isClickable || !node.isEnabled || isPaymentSensitive(node)) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      } finally {
        node.recycle()
      }
    } finally {
      root.recycle()
    }
  }

  private suspend fun waitForAssertion(
    condition: JSONObject,
    params: JSONObject,
    expectedPackage: String,
    timeoutMs: Long,
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (assertionHolds(condition, params, expectedPackage)) return true
      delay(200)
    }
    return false
  }

  private fun assertionHolds(condition: JSONObject, params: JSONObject, expectedPackage: String): Boolean {
    if (condition.optString("kind") != "draftDoesNotContainParam") return false
    val value = params.optString(condition.optString("param"))
    if (value.isBlank()) return false
    val root = service.rootInActiveWindow ?: return false
    try {
      if (root.packageName?.toString() != expectedPackage) return false
      return !treeAny(root) { node ->
        node.isEditable && nodeLabel(node).contains(value.lowercase())
      }
    } finally {
      root.recycle()
    }
  }

  private fun findMatching(locator: JSONObject, expectedPackage: String): AccessibilityNodeInfo? {
    val root = service.rootInActiveWindow ?: return null
    try {
      if (root.packageName?.toString() != expectedPackage) return null
      return findMatchingInTree(root, locator)
    } finally {
      root.recycle()
    }
  }

  private fun findMatchingInTree(root: AccessibilityNodeInfo, locator: JSONObject): AccessibilityNodeInfo? {
    return findRecursive(root, locator, intArrayOf(0))
  }

  private fun findRecursive(node: AccessibilityNodeInfo, locator: JSONObject, count: IntArray, depth: Int = 0): AccessibilityNodeInfo? {
    if (count[0]++ >= 500 || depth >= 40) return null
    if (matches(node, locator)) return AccessibilityNodeInfo.obtain(node)
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = findRecursive(child, locator, count, depth + 1)
      child.recycle()
      if (found != null) return found
    }
    return null
  }

  private fun matches(node: AccessibilityNodeInfo, locator: JSONObject): Boolean {
    val viewId = locator.optString("viewId")
    if (viewId.isNotBlank() && node.viewIdResourceName != viewId) return false
    when (locator.optString("role")) {
      "button" -> if (!node.isClickable) return false
      "editableText" -> if (!node.isEditable) return false
      "" -> Unit
      else -> return false
    }
    val semantic = locator.optString("semantic")
    if (semantic.isNotBlank()) {
      val keywords = semanticLexicon[semantic] ?: return false
      val label = nodeLabel(node)
      if (keywords.none { label.contains(it) }) return false
    }
    if (locator.optBoolean("requiresEditableOnScreen", false) && !treeHasEditable()) return false
    return node.isVisibleToUser && node.isEnabled
  }

  private fun treeHasEditable(): Boolean {
    val root = service.rootInActiveWindow ?: return false
    try {
      return treeAny(root) { it.isEditable && it.isVisibleToUser && it.isEnabled }
    } finally {
      root.recycle()
    }
  }

  // predicate must be the LAST parameter (not depth) so the trailing-lambda call sites above
  // (treeAny(root) { ... }) actually bind the lambda to predicate — with depth last, Kotlin
  // attaches the trailing lambda to depth instead, which is a compile error, not a warning.
  private fun treeAny(node: AccessibilityNodeInfo, depth: Int = 0, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
    if (depth >= 40) return false
    if (predicate(node)) return true
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = treeAny(child, depth + 1, predicate)
      child.recycle()
      if (found) return true
    }
    return false
  }

  private fun nodeLabel(node: AccessibilityNodeInfo): String =
    "${node.text ?: ""} ${node.contentDescription ?: ""} ${node.hintText ?: ""}".lowercase().trim()
}
