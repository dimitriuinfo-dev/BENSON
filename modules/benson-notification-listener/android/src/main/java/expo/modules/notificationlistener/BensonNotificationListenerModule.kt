package expo.modules.notificationlistener

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BensonNotificationListenerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonNotificationListener")

    // No runtime-permission callback exists for this — the caller must re-check after the user
    // returns from the settings screen, same pattern as benson-accessibility's isServiceEnabled.
    Function("isEnabled") {
      val context = appContext.reactContext ?: return@Function false
      val flat = ComponentName(context, BensonNotificationListenerService::class.java).flattenToString()
      val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
      enabledListeners?.split(":")?.any { it == flat } == true
    }

    // "Notification access" is a special permission Android will not grant via the normal
    // runtime prompt — this opens the dedicated system settings screen for it.
    Function("openNotificationListenerSettings") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      try { context.startActivity(intent) } catch (_: Exception) {}
    }
  }
}
