package expo.modules.accessibility

import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
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
        promise.resolve(mapOf("success" to false, "stepIndex" to -1, "action" to "none", "status" to "invalid", "detail" to "Accessibility Service is not running."))
        return@AsyncFunction
      }
      val command = try {
        JSONObject(commandJson)
      } catch (_: Exception) {
        promise.resolve(mapOf("success" to false, "stepIndex" to -1, "action" to "none", "status" to "invalid", "detail" to "Command JSON is invalid."))
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
  }
}
