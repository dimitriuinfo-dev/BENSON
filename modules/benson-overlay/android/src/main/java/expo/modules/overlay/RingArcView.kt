package expo.modules.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A single ring drawn as alternating gold/green arcs (2 of each, on opposite quadrants),
 * continuously rotated via a plain View.rotation animator. Two of these nested at different
 * sizes, spinning opposite directions, reproduce the in-app seal's "counter-rotating rings"
 * look as a system overlay — drawn natively (no second React Native surface needed).
 */
class RingArcView(context: Context, private val strokeDp: Float, thin: Boolean) : View(context) {
  private val paintGold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFFD4AF37.toInt()
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val paintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF1F5A45.toInt()
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
  }
  private val thinRing = thin
  private var animator: ValueAnimator? = null

  init {
    val stroke = strokeDp * resources.displayMetrics.density
    paintGold.strokeWidth = stroke
    paintGreen.strokeWidth = stroke
    if (thin) paintGold.alpha = 140
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val inset = paintGold.strokeWidth
    val rect = RectF(inset, inset, width - inset, height - inset)
    if (thinRing) {
      canvas.drawArc(rect, 0f, 360f, false, paintGold)
      return
    }
    // 4 quarter-arcs, alternating gold/green, with small gaps — same spirit as the in-app seal.
    val arcLen = 70f
    canvas.drawArc(rect, 20f, arcLen, false, paintGold)
    canvas.drawArc(rect, 110f, arcLen, false, paintGreen)
    canvas.drawArc(rect, 200f, arcLen, false, paintGold)
    canvas.drawArc(rect, 290f, arcLen, false, paintGreen)
  }

  fun startSpin(durationMs: Long, clockwise: Boolean) {
    stopSpin()
    rotation = 0f
    val target = if (clockwise) 360f else -360f
    animator = ObjectAnimator.ofFloat(this, "rotation", 0f, target).apply {
      duration = durationMs
      repeatCount = ValueAnimator.INFINITE
      interpolator = android.view.animation.LinearInterpolator()
      start()
    }
  }

  fun stopSpin() {
    animator?.cancel()
    animator = null
  }
}
