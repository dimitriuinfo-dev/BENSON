package expo.modules.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

/**
 * RUNDA_UI_SINGLE_THINKING_INDICATOR_1 (2026-09-19) — the small bubble's interior visual,
 * replacing the old three-dot BubbleDotsView with the SAME counter-rotating arc design the
 * (now-disabled) large center wake ring used, scaled to fit the small bubble. Exposes the exact
 * same applyMotion(String)/stop() surface BubbleDotsView had, so BensonBubbleService's existing
 * call sites (updateStatus/onStartCommand/removeBubble) need no logic changes — only the type
 * swap in addBubble().
 */
class SmallArcBubbleView(context: Context) : FrameLayout(context) {
  private val outer = RingArcView(context, 3f, thin = false)
  private val inner = RingArcView(context, 1f, thin = true)
  private var pulseAnimator: ObjectAnimator? = null
  private var mode: String = "static"

  init {
    val innerSize = (26 * resources.displayMetrics.density).toInt()
    addView(outer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(inner, LayoutParams(innerSize, innerSize, Gravity.CENTER))
  }

  fun applyMotion(next: String) {
    if (next == mode) return
    mode = next
    outer.stopSpin()
    inner.stopSpin()
    pulseAnimator?.cancel()
    pulseAnimator = null
    scaleX = 1f
    scaleY = 1f
    alpha = 1f
    when (next) {
      "listening" -> {
        outer.startSpin(2400, clockwise = true)
        inner.startSpin(2400, clockwise = false)
      }
      "executing" -> {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
          this,
          PropertyValuesHolder.ofFloat("scaleX", 0.85f, 1.1f),
          PropertyValuesHolder.ofFloat("scaleY", 0.85f, 1.1f),
          PropertyValuesHolder.ofFloat("alpha", 0.7f, 1f),
        ).apply {
          duration = 620L
          repeatCount = ValueAnimator.INFINITE
          repeatMode = ValueAnimator.REVERSE
          interpolator = AccelerateDecelerateInterpolator()
          start()
        }
      }
      // "static" (or anything else) — already reset above, arcs drawn at rest.
    }
  }

  fun stop() {
    outer.stopSpin()
    inner.stopSpin()
    pulseAnimator?.cancel()
    pulseAnimator = null
  }
}
