package expo.modules.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class BensonOverlayModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonOverlay")

    Events("onBubbleTapped", "onBubbleCameraTapped")

    OnCreate {
      BensonBubbleService.onBubbleTapped = {
        sendEvent("onBubbleTapped")
      }
      // Photo/video capture button on the status card (2026-09-18).
      BensonBubbleService.onBubbleCameraTapped = {
        sendEvent("onBubbleCameraTapped")
      }
    }

    OnDestroy {
      BensonBubbleService.onBubbleTapped = null
      BensonBubbleService.onBubbleCameraTapped = null
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

    // E2-2 — push the written status band next to the bubble. `state` is a short word
    // ("ascult"/"am înțeles"/"execut"/"gata"), `transcript` is the user's last utterance.
    // visible=false (or both strings blank) removes the band.
    // ROUND_BUBBLE_STATE_DESYNC_FIX_1 — `terminal` (this is a DONE/ERROR result — arms the short
    // ~2.5s native auto-dismiss instead of the long stale-safety-net) and `turnId` (Date.now() at
    // the JS call site — lets native drop an out-of-order/stale delivery) are now required so
    // native can own the dismiss lifecycle itself instead of trusting a JS timer to hide it later.
    // ROUND_ASSISTANT_SESSION_UX_FIX_1 — dismissDelayMs: the session-aware readable dwell JS
    // computed for THIS terminal call (0 = let native use its own default). Ignored when
    // terminal=false.
    Function("updateBubbleStatus") { state: String, transcript: String, visible: Boolean, terminal: Boolean, turnId: Double, dismissDelayMs: Double ->
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(context, BensonBubbleService::class.java).apply {
        action = BensonBubbleService.ACTION_UPDATE_STATUS
        putExtra(BensonBubbleService.EXTRA_STATE, state)
        putExtra(BensonBubbleService.EXTRA_TRANSCRIPT, transcript)
        putExtra(BensonBubbleService.EXTRA_VISIBLE, visible)
        putExtra(BensonBubbleService.EXTRA_TERMINAL, terminal)
        putExtra(BensonBubbleService.EXTRA_TURN_ID, turnId.toLong())
        putExtra(BensonBubbleService.EXTRA_DISMISS_DELAY_MS, dismissDelayMs.toLong())
      }
      context.startService(intent)
    }

    // URGENT_REPAIR_AND_ADVANCE_1 — real-RMS mic bars on the status card. Direct call (not an
    // Intent) since this fires ~10x/sec while listening; silent no-op if the service/card isn't up.
    Function("setMicLevel") { level: Double, active: Boolean ->
      BensonBubbleService.instance?.setMicLevel(level.toFloat(), active)
    }

    // E3-2 — drive the bubble's inner dots: "listening" (rotate counter-clockwise), "executing"
    // (pulse), "static" (at rest). The bubble itself stays transparent.
    Function("setBubbleMotion") { motion: String ->
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(context, BensonBubbleService::class.java).apply {
        action = BensonBubbleService.ACTION_BUBBLE_MOTION
        putExtra(BensonBubbleService.EXTRA_MOTION, motion)
      }
      context.startService(intent)
    }

    // E3-3 — the "microphone is open" cue: a short procedurally-synthesised ascending SF tone.
    // Muted automatically when the ringer is on silent/vibrate; amplitude tracks media volume.
    Function("playWakeSound") {
      val context = appContext.reactContext ?: return@Function Unit
      WakeSound.play(context)
    }
  }
}
