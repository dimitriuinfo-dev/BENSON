package expo.modules.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Minimal floating bubble — a plain circle, no theming, meant as a functional placeholder
 * until the app's own design system covers it. Tap activates the mic (fires onBubbleTapped);
 * drag moves it anywhere on screen. Deliberately has zero dependency on any in-app UI
 * component (BensonSeal/BensonMainScreen/IdleCard) — this lives entirely outside the RN view
 * tree, drawn directly via WindowManager like any system overlay (as Android's own
 * accessibility/chat-head bubbles do).
 */
class BensonBubbleService : Service() {
  private var windowManager: WindowManager? = null
  private var bubbleView: View? = null
  private var params: WindowManager.LayoutParams? = null

  private var wakeRingView: FrameLayout? = null
  private var wakeOuterRing: RingArcView? = null
  private var wakeInnerRing: RingArcView? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SHOW_WAKE_RING -> showWakeRing()
      ACTION_HIDE_WAKE_RING -> hideWakeRing()
      else -> if (bubbleView == null) addBubble()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    removeBubble()
    hideWakeRing()
    super.onDestroy()
  }

  private fun addBubble() {
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    val size = (56 * resources.displayMetrics.density).toInt()
    val view = View(this).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#1E2B38"))
        setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#D4AF37"))
      }
    }

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // Bottom, not top (product-owner-directed 2026-07-17): "Benson stays a bubble at the bottom"
    // while another app (WhatsApp, Waze, anything BENSON operates) is in the foreground — this is
    // the primary way the user sees BENSON is still present/reachable during a handoff, replacing
    // reliance on real Android PiP (multi-window), which ColorOS's own flexible-window layer kept
    // reinterpreting unpredictably. A plain WindowManager overlay has no such OS-level ambiguity.
    val lp = WindowManager.LayoutParams(
      size, size, overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
      x = 0
      y = (100 * resources.displayMetrics.density).toInt()
    }

    var downX = 0f; var downY = 0f
    var startX = 0; var startY = 0
    var moved = false

    view.setOnTouchListener { v, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX; downY = event.rawY
          startX = lp.x; startY = lp.y
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downX).toInt()
          val dy = (event.rawY - downY).toInt()
          if (abs(dx) > 12 || abs(dy) > 12) moved = true
          lp.x = startX + dx
          // y is now a bottom-edge offset (Gravity.BOTTOM), not a top-edge offset — dragging the
          // finger DOWN (dy positive) must DECREASE the offset (bubble moves closer to the bottom
          // edge), the opposite sign from the old top-gravity behavior.
          lp.y = startY - dy
          windowManager?.updateViewLayout(v, lp)
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) onBubbleTapped?.invoke()
          true
        }
        else -> false
      }
    }

    params = lp
    bubbleView = view
    windowManager?.addView(view, lp)
  }

  private fun removeBubble() {
    bubbleView?.let { windowManager?.removeView(it) }
    bubbleView = null
    windowManager = null
  }

  // Wake-word reveal — the seal appears centered, semi-transparent, over whatever app is in
  // front, only while "Benson" was just heard and the real command is being captured. Drawn
  // natively (two counter-rotating RingArcViews) rather than hosting a second React Native
  // surface — far simpler and avoids a second bridge/root-view lifecycle to manage.
  private fun showWakeRing() {
    if (wakeRingView != null) return
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    windowManager = windowManager ?: wm

    val density = resources.displayMetrics.density
    val outerSize = (180 * density).toInt()
    val innerSize = (110 * density).toInt()

    val container = FrameLayout(this).apply { alpha = 0.88f }
    val outer = RingArcView(this, 6f, thin = false).apply {
      layoutParams = FrameLayout.LayoutParams(outerSize, outerSize, Gravity.CENTER)
    }
    val inner = RingArcView(this, 1.5f, thin = true).apply {
      layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
    }
    container.addView(outer)
    container.addView(inner)

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    val lp = WindowManager.LayoutParams(
      outerSize, outerSize, overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }

    wm.addView(container, lp)
    wakeRingView = container
    wakeOuterRing = outer
    wakeInnerRing = inner
    outer.startSpin(3500, clockwise = true)
    inner.startSpin(3500, clockwise = false)
  }

  private fun hideWakeRing() {
    wakeOuterRing?.stopSpin()
    wakeInnerRing?.stopSpin()
    wakeRingView?.let { windowManager?.removeView(it) }
    wakeRingView = null
    wakeOuterRing = null
    wakeInnerRing = null
  }

  companion object {
    var onBubbleTapped: (() -> Unit)? = null
    const val ACTION_SHOW_WAKE_RING = "expo.modules.overlay.ACTION_SHOW_WAKE_RING"
    const val ACTION_HIDE_WAKE_RING = "expo.modules.overlay.ACTION_HIDE_WAKE_RING"
  }
}
