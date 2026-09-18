package expo.modules.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * E3-2 — the floating bubble's inner motion. Three dots on a small circle, drawn natively.
 *
 *  - "listening"  → the three-dot constellation rotates counter-clockwise (View.rotation animator).
 *  - "executing"  → the constellation pulses (scaleX/scaleY breathing).
 *  - "static"     → no animation, dots drawn at rest.
 *
 * The bubble container itself is transparent (see BensonBubbleService.addBubble) — only these dots
 * and a faint ring are visible, so "BENSON is listening" reads at a glance without opening the app.
 * Colours: gold + dark green, matching RingArcView / the in-app seal.
 */
class BubbleDotsView(context: Context) : View(context) {
  private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD4AF37.toInt(); style = Paint.Style.FILL }
  private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F5A45.toInt(); style = Paint.Style.FILL }
  // dot i -> paint: gold, green, gold
  private val dotPaints = arrayOf(gold, green, gold)

  private var animator: ObjectAnimator? = null
  private var mode: String = "static"

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val cx = width / 2f
    val cy = height / 2f
    val ring = minOf(width, height) * 0.30f   // radius the dots sit on
    val dotR = minOf(width, height) * 0.11f    // each dot's radius
    for (i in 0 until 3) {
      val ang = Math.toRadians((i * 120.0) - 90.0) // start at top
      val x = cx + ring * Math.cos(ang).toFloat()
      val y = cy + ring * Math.sin(ang).toFloat()
      canvas.drawCircle(x, y, dotR, dotPaints[i])
    }
  }

  fun applyMotion(next: String) {
    if (next == mode) return
    mode = next
    animator?.cancel()
    animator = null
    rotation = 0f
    scaleX = 1f
    scaleY = 1f
    alpha = 1f
    when (next) {
      "listening" -> {
        // counter-clockwise = 0 -> -360
        animator = ObjectAnimator.ofFloat(this, "rotation", 0f, -360f).apply {
          duration = 2400L
          repeatCount = ValueAnimator.INFINITE
          interpolator = LinearInterpolator()
          start()
        }
      }
      "executing" -> {
        animator = ObjectAnimator.ofPropertyValuesHolder(
          this,
          android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.78f, 1.18f),
          android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.78f, 1.18f),
          android.animation.PropertyValuesHolder.ofFloat("alpha", 0.65f, 1f),
        ).apply {
          duration = 620L
          repeatCount = ValueAnimator.INFINITE
          repeatMode = ValueAnimator.REVERSE
          interpolator = android.view.animation.AccelerateDecelerateInterpolator()
          start()
        }
      }
      // "static" (or anything else) — already reset above, dots drawn at rest.
    }
    invalidate()
  }

  fun stop() {
    animator?.cancel()
    animator = null
  }
}
