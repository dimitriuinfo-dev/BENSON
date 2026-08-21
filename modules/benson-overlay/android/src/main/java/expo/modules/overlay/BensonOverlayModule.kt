package expo.modules.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BensonOverlayModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonOverlay")

    Events("onBubbleTapped")

    OnCreate {
      BensonBubbleService.onBubbleTapped = {
        sendEvent("onBubbleTapped")
      }
    }

    OnDestroy {
      BensonBubbleService.onBubbleTapped = null
    }

    Function("hasOverlayPermission") {
      val context = appContext.reactContext ?: return@Function false
      Settings.canDrawOverlays(context)
    }

    // "Draw over other apps" is a special permission Android will not grant via the normal
    // runtime prompt — this opens the dedicated system settings screen for it.
    Function("requestOverlayPermission") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
      ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      try { context.startActivity(intent) } catch (_: Exception) {}
    }

    Function("showBubble") {
      val context = appContext.reactContext ?: return@Function Unit
      context.startService(Intent(context, BensonBubbleService::class.java))
    }

    Function("hideBubble") {
      val context = appContext.reactContext ?: return@Function Unit
      context.stopService(Intent(context, BensonBubbleService::class.java))
    }

    // Wake-word reveal — call while BensonForegroundService (mic-type) is already running,
    // otherwise Android may refuse to start this background service at all.
    Function("showWakeRing") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(context, BensonBubbleService::class.java).apply {
        action = BensonBubbleService.ACTION_SHOW_WAKE_RING
      }
      context.startService(intent)
    }

    Function("hideWakeRing") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(context, BensonBubbleService::class.java).apply {
        action = BensonBubbleService.ACTION_HIDE_WAKE_RING
      }
      context.startService(intent)
    }
  }
}
