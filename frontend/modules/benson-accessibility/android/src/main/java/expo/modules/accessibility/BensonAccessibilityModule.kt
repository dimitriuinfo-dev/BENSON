package expo.modules.accessibility

import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BensonAccessibilityModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonAccessibility")
    Events("onScreenUpdate", "onForegroundChanged")

    OnCreate {
      BensonAccessibilityService.onScreenUpdate = { json ->
        sendEvent("onScreenUpdate", mapOf("json" to json))
      }
      BensonAccessibilityService.onForegroundChanged = { packageName ->
        sendEvent("onForegroundChanged", mapOf("packageName" to packageName))
      }
    }

    OnDestroy {
      BensonAccessibilityService.onScreenUpdate = null
      BensonAccessibilityService.onForegroundChanged = null
    }

    AsyncFunction("isServiceEnabled") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: ""
      val expected = "${context.packageName}/${BensonAccessibilityService::class.java.name}"

      val splitter = TextUtils.SimpleStringSplitter(':')
      splitter.setString(enabledServices)
      var found = false
      while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) {
          found = true
          break
        }
      }
      found
    }

    // Single authoritative connection-state check, replacing the settings-only isServiceEnabled
    // as the pre-flight gate for any accessibility-dependent action. Distinguishes what
    // isServiceEnabled alone cannot: a service the user enabled in Settings but whose process
    // OxygenOS has since killed (enabled_disconnected) from one genuinely still bound
    // (enabled_connected) from one the user never turned on at all (disabled). "disconnected" is
    // the case a caller must treat as unusable exactly like "disabled" — the difference only
    // matters for diagnostics/logging, never for deciding whether to proceed.
    AsyncFunction("getConnectionState") {
      val context = appContext.reactContext ?: return@AsyncFunction "disabled"
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: ""
      val expected = "${context.packageName}/${BensonAccessibilityService::class.java.name}"
      val splitter = TextUtils.SimpleStringSplitter(':')
      splitter.setString(enabledServices)
      var enabledInSettings = false
      while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) {
          enabledInSettings = true
          break
        }
      }
      if (!enabledInSettings) return@AsyncFunction "disabled"
      if (BensonAccessibilityService.instance != null) "enabled_connected" else "enabled_disconnected"
    }

    AsyncFunction("openAccessibilitySettings") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
      true
    }

    AsyncFunction("performClick") { nodeId: String ->
      BensonAccessibilityService.instance?.performClickById(nodeId) ?: false
    }

    // On-demand FRESH screen snapshot as a JSON string: { packageName, timestamp, capturedAt,
    // nodeCount, nodes[] }. Reads rootInActiveWindow now (retries 3×@150ms if empty) instead of
    // returning the last pushed onScreenUpdate — the pushed cache is stale for content that
    // changes without a window-state change (WhatsApp search results). See captureSnapshot().
    AsyncFunction("getScreenSnapshot") { promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve("{\"packageName\":\"\",\"timestamp\":0,\"capturedAt\":0,\"nodeCount\":0,\"nodes\":[]}")
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        promise.resolve(svc.captureSnapshot())
      }
    }

    AsyncFunction("performSetText") { nodeId: String, value: String ->
      BensonAccessibilityService.instance?.performSetTextById(nodeId, value) ?: false
    }

    // fieldsJson: {"email": "x@y.com", "nume": "Rareș"}
    AsyncFunction("fillForm") { fieldsJson: String ->
      val svc = BensonAccessibilityService.instance ?: return@AsyncFunction 0
      val obj = JSONObject(fieldsJson)
      val map = mutableMapOf<String, String>()
      obj.keys().forEach { key -> map[key] = obj.getString(key) }
      svc.fillForm(map)
    }

    AsyncFunction("goBack") {
      BensonAccessibilityService.instance?.goBack() ?: false
    }

    AsyncFunction("goHome") {
      BensonAccessibilityService.instance?.goHome() ?: false
    }

    AsyncFunction("openRecents") {
      BensonAccessibilityService.instance?.openRecents() ?: false
    }

    // Waze-launch diagnostic (and future launch verification generally): the last package the
    // accessibility service saw come to the foreground. Null if the service isn't running/
    // enabled — callers must treat that as "can't verify", never as a pass or a fail.
    Function("getForegroundPackage") {
      BensonAccessibilityService.instance?.let { BensonAccessibilityService.lastForegroundPackage }
    }

    // Native WhatsApp call flow (open -> search -> type name -> tap result -> verify chat ->
    // find+tap call button), run entirely inside the accessibility service's own coroutine scope
    // so it isn't affected by the RN JS thread being throttled once BENSON backgrounds. Resolves
    // with a structured { success, step, error } result instead of throwing, so JS always gets
    // an honest, specific reason rather than a generic rejection.
    AsyncFunction("placeWhatsAppCall") { contactName: String, autoPressCall: Boolean, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      // This AsyncFunction lambda is not itself a suspend context, so the suspend
      // placeWhatsAppCall() must be launched from inside a coroutine — runOnServiceScope wraps
      // that so this module doesn't need direct access to the service's private CoroutineScope.
      svc.runOnServiceScope {
        val result = svc.placeWhatsAppCall(contactName, autoPressCall)
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    // WA-NATIVE-FINAL — THE active WhatsApp voice-call executor. One call from JS; Kotlin runs the
    // whole sequence on its own coroutine (Dispatchers.Default), independent of the RN Activity
    // being foregrounded. Resolves { success, step, error, contact, elapsedMs }.
    AsyncFunction("runWhatsAppCallNative") { contact: String, promise: Promise ->
      // WA-CONTACT-TRACE — the exact string that crossed the JS→native bridge. This is what the
      // executor will type into WhatsApp's own search; there is no rename/fallback past this point.
      android.util.Log.i("BENSON_AUDIO", "WA_CONTACT_INPUT stage=native_bridge native=\"$contact\"")
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf(
          "success" to false, "step" to "SERVICE",
          "error" to "Accessibility Service is not running.",
          "contact" to contact, "elapsedMs" to 0,
        ))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val r = svc.runWhatsAppCallNative(contact)
        promise.resolve(mapOf(
          "success" to r.success, "step" to r.step, "error" to r.error,
          "contact" to r.contact, "elapsedMs" to r.elapsedMs,
          "verifiedHeaderText" to r.verifiedHeaderText, "nameMatch" to r.nameMatch,
        ))
      }
    }

    // WA-FIX-4 — DIRECT-CONTACT-DEEPLINK call route. JS resolved the spoken name to a phone number
    // from the local address book; native opens the exact conversation via whatsapp://send?phone=,
    // verifies it, and reuses the proven call-button + verify sequence. No Chats list, no search.
    AsyncFunction("runWhatsAppOpenConversationCall") { phone: String, expectedName: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf(
          "success" to false, "step" to "SERVICE",
          "error" to "Accessibility Service is not running.",
          "contact" to expectedName, "elapsedMs" to 0,
        ))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val r = svc.runWhatsAppOpenConversationCall(phone, expectedName)
        promise.resolve(mapOf(
          "success" to r.success, "step" to r.step, "error" to r.error,
          "contact" to r.contact, "elapsedMs" to r.elapsedMs,
          "verifiedHeaderText" to r.verifiedHeaderText, "nameMatch" to r.nameMatch,
        ))
      }
    }

    // ── ROUND_WA_GOVERNANCE_WRITE_1 ─────────────────────────────────────────────────────────────
    // PHASE A: open the exact conversation (whatsapp://send?phone=, explicit com.whatsapp), verify
    // identity, find the compose field, type the exact message, verify the typed text. STOPS before
    // send. `missionId` keys the persisted idempotency state. Resolves { success, step, error,
    // contact, elapsedMs } — step "TYPED_VERIFIED" on success; step names the failed stage.
    AsyncFunction("runWhatsAppOpenConversationType") { phone: String, expectedName: String, message: String, missionId: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "SERVICE", "error" to "Accessibility Service is not running.", "contact" to expectedName, "elapsedMs" to 0))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val r = svc.runWhatsAppOpenConversationType(phone, expectedName, message, missionId)
        promise.resolve(mapOf(
          "success" to r.success, "step" to r.step, "error" to r.error, "contact" to r.contact, "elapsedMs" to r.elapsedMs,
          "verifiedHeaderText" to r.verifiedHeaderText, "nameMatch" to r.nameMatch,
        ))
      }
    }

    // PHASE B: called ONLY after an explicit YES. Presses Send at most once for `missionId`, then
    // verifies the exact outgoing message appears in the conversation. Idempotent: a mission already
    // at SEND_ATTEMPTED/SENT_VERIFIED re-verifies, never re-presses.
    AsyncFunction("pressWhatsAppSendVerified") { missionId: String, message: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "SERVICE", "error" to "Accessibility Service is not running.", "contact" to "", "elapsedMs" to 0))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val r = svc.pressWhatsAppSendVerified(missionId, message)
        promise.resolve(mapOf("success" to r.success, "step" to r.step, "error" to r.error, "contact" to r.contact, "elapsedMs" to r.elapsedMs))
      }
    }

    // "<missionId>|<state>" of the current pending WhatsApp write, or "|NOT_TYPED".
    Function("getWhatsAppWriteState") {
      BensonAccessibilityService.getWhatsAppWriteState()
    }

    AsyncFunction("endWhatsAppCall") { promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.endWhatsAppCall()
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    AsyncFunction("muteWhatsAppCall") { promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.muteWhatsAppCall()
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    // Taps WhatsApp's own send button — call only after opening a chat with the message already
    // pre-filled via a wa.me deep link (whatsappTool.ts's openConversation). Same
    // structured-result, non-JS-timer execution model as the call flow above.
    AsyncFunction("pressWhatsAppSend") { promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.pressWhatsAppSend()
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    // Second half of the two-phase call flow (2026-07-17): call only after placeWhatsAppCall(...,
    // autoPressCall=false) has already opened the contact's chat and the user has confirmed by
    // voice having SEEN it (BENSON in PiP alongside it) — this just finds and taps the call button.
    AsyncFunction("pressWhatsAppCallButton") { promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.pressWhatsAppCallButton()
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    // Generic declarative automation entry point. profileId is allow-listed by the native
    // executor; params are data for a profile assertion, never executable code.
    AsyncFunction("runAutomationProfile") { profileId: String, paramsJson: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "step" to "service", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      val params = try {
        JSONObject(paramsJson)
      } catch (_: Exception) {
        promise.resolve(mapOf("success" to false, "step" to "params", "error" to "Automation parameters are invalid."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.runAutomationProfile(profileId, params)
        promise.resolve(mapOf("success" to result.success, "step" to result.step, "error" to result.error))
      }
    }

    // JS-driven step-list executor (2026-07-17) — the JS "brain" sends an explicit ordered list
    // of steps (launch_app/click/set_text/wait/back/home/assert_*/return_to_benson); the native
    // BensonCommandExecutor runs them and stops honestly at the first problem. commandJson is the
    // step list, not executable code — see BensonCommandExecutor.kt's doc comment for the format.
    AsyncFunction("executeCommand") { commandJson: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "stepIndex" to -1, "action" to "none", "status" to "invalid", "detail" to "Accessibility Service is not running.", "itemsJson" to "[]"))
        return@AsyncFunction
      }
      val command = try {
        JSONObject(commandJson)
      } catch (_: Exception) {
        promise.resolve(mapOf("success" to false, "stepIndex" to -1, "action" to "none", "status" to "invalid", "detail" to "Command JSON is invalid.", "itemsJson" to "[]"))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val result = svc.executeCommand(command)
        promise.resolve(mapOf(
          "success" to result.success,
          "stepIndex" to result.stepIndex,
          "action" to result.action,
          "status" to result.status,
          "detail" to result.detail,
          // ROUND_YOUTUBE_GOVERNANCE_1 — generic extract_list payload; "[]" for every other action.
          "itemsJson" to result.itemsJson,
        ))
      }
    }

    // CALC1 (2026-09-22) — symbolsJson is a JSON array of already-parsed button symbols (e.g.
    // ["√","9","="]) built by JS (lib/tools/toolRegistry.ts's text->symbol translation).
    // CalculatorRecipe presses each one natively (waitForNode + ACTION_CLICK, reused, never
    // duplicated) and reads the real result display back — mirrors executeCommand's own shape.
    AsyncFunction("runCalculatorRecipe") { symbolsJson: String, promise: Promise ->
      val svc = BensonAccessibilityService.instance
      if (svc == null) {
        promise.resolve(mapOf("success" to false, "resultText" to null, "changed" to false, "failedStep" to "SERVICE", "error" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      val symbols = try {
        val arr = JSONArray(symbolsJson)
        (0 until arr.length()).map { arr.getString(it) }
      } catch (_: Exception) {
        promise.resolve(mapOf("success" to false, "resultText" to null, "changed" to false, "failedStep" to "PARSE", "error" to "symbolsJson is invalid."))
        return@AsyncFunction
      }
      svc.runOnServiceScope {
        val outcome = CalculatorRecipe(svc).run(symbols)
        promise.resolve(mapOf(
          "success" to outcome.success,
          "resultText" to outcome.resultText,
          "changed" to outcome.changed,
          "failedStep" to outcome.failedStep,
          "error" to outcome.error,
        ))
      }
    }

    // Lets JS mark a WhatsApp automation as in-progress BEFORE the native step starts (e.g.
    // whatsappTool.ts's sendMessage calls this, then Linking.openURL's wa.me deep link, then
    // pressWhatsAppSend) — closes the gap where Guardian could steal focus back to BENSON during
    // the JS-side Linking.openURL call itself, before the native send-button step (which sets the
    // same flag internally) even starts. Always clear this from a finally block on the JS side.
    Function("setWhatsAppAutomationActive") { active: Boolean ->
      BensonAccessibilityService.whatsappAutomationActive = active
    }

    // WA-CALL-STAYS-LIVE — true while a WhatsApp call placed by runWhatsAppCallNative is still
    // fresh (verified < 180s ago and not yet cleared). While true, BENSON's JS mic/wake entry
    // points must not open the mic — the concurrent capture was ending the call. Synchronous,
    // cheap (one volatile compare), safe to poll from every listen chokepoint.
    Function("whatsAppCallMicHoldActive") {
      BensonAccessibilityService.whatsAppCallMicHoldActive()
    }

    // Lift the hold early — called when BENSON's own Activity is foregrounded again, i.e. the user
    // is back with BENSON and done with (or has put on speaker) the call.
    Function("clearWhatsAppCallMicHold") {
      BensonAccessibilityService.whatsappCallMicHoldUntilMs = 0L
    }

    // AUTO-RETURN-AFTER-CALL — true (once) when the call-lifecycle watcher has verified a call
    // ended and fired the return Intent. The JS AppState 'active' handler reads it on the next
    // foreground transition and re-arms WAKE mode only (no conversation mode / general STT).
    Function("consumeCallEndedReturnPending") {
      BensonAccessibilityService.consumeCallEndedReturnPending()
    }

    // WA-LIFECYCLE-FIX-1 — the persisted "a verified WhatsApp call just ended" timestamp (0 = none).
    // Survives a process kill. The JS self-heal loop keeps its own last-handled value and, when this
    // is newer, clears the JS mic hold + re-arms wake — independent of any AppState 'active' event.
    Function("getWhatsAppCallEndedSignalAt") {
      BensonAccessibilityService.getWhatsAppCallEndedSignalAt().toDouble()
    }
  }
}
