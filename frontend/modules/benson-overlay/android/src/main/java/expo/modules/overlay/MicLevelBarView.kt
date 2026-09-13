package expo.modules.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/**
 * URGENT_REPAIR_AND_ADVANCE_1 — cheapest correct real-mic indicator: center-origin symmetric
 * bars at the bottom of the active status card, driven by real RMS (setLevel), hidden otherwise.
 * No animation loop of its own — every frame is a direct function of the last real level pushed
 * from JS (addVolumeListener's real RMS, already fixed from a fake Math.random() in an earlier
 * round), so a silent/backgrounded mic draws nothing rather than a fake idle wiggle.
 */
class MicLevelBarView(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD4AF37.toInt() }
  private var level: Float = 0f
  private val bars = 7
  private val taper = floatArrayOf(1f, 0.85f, 0.65f, 0.45f) // index = distance from center bar

  fun setLevel(v: Float) {
    level = v.coerceIn(0f, 1f)
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (width == 0 || height == 0) return
    val gap = width * 0.02f
    val barW = (width - gap * (bars - 1)) / bars
    val centerIdx = bars / 2
    val minH = height * 0.12f
    for (i in 0 until bars) {
      val d = abs(i - centerIdx).coerceAtMost(taper.size - 1)
      val h = minH + (height - minH) * level * taper[d]
      val left = i * (barW + gap)
      val top = height - h
      canvas.drawRoundRect(left, top, left + barW, height.toFloat(), barW / 2f, barW / 2f, paint)
    }
  }
}
