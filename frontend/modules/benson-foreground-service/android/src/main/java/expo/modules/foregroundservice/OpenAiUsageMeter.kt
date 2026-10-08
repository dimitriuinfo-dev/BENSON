package expo.modules.foregroundservice

import android.content.Context

/**
 * V2 (08.10.2026, Motor M5) — daily cost meter for OpenAI calls, built BEFORE V3/V4 (the first new
 * paid calls this round adds) so nothing can spend money unmetered, even for a single request.
 * Pure Kotlin + SharedPreferences, no network, no UI — V3/V4 call recordUsage() after every real
 * API response and isBudgetExceeded() before every new one.
 *
 * Prices verified 08.10.2026 (V0, developers.openai.com/api/docs/pricing + model pages) — per 1M
 * tokens, USD. USD->EUR rate verified same day (~0.89, market rate, fluctuates — not exact).
 */
object OpenAiUsageMeter {
  private const val PREFS_NAME = "benson_watchdog_prefs"
  private const val KEY_DAY = "openai_usage_day"
  private const val KEY_TOTAL_USD = "openai_usage_total_usd"
  private const val KEY_DAILY_CAP_EUR = "openai_daily_cap_eur"
  private const val DEFAULT_DAILY_CAP_EUR = 1.0f

  // Verified 08.10.2026 — see V0 report (DAY_REPORT_2026-10-08.md). Re-verify before relying on
  // these for a real budget decision far in the future; OpenAI pricing changes without notice.
  const val GPT4O_MINI_TRANSCRIBE_INPUT_PER_1M_USD = 1.25
  const val GPT4O_MINI_TRANSCRIBE_OUTPUT_PER_1M_USD = 5.0
  const val GPT5_NANO_INPUT_PER_1M_USD = 0.05
  const val GPT5_NANO_OUTPUT_PER_1M_USD = 0.40
  const val USD_TO_EUR = 0.89

  private fun today(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

  private fun ensureFreshDay(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getString(KEY_DAY, null) != today()) {
      prefs.edit().putString(KEY_DAY, today()).putFloat(KEY_TOTAL_USD, 0f).apply()
    }
  }

  /** costUsd computed by the caller from the exact token counts in the real API response —
   *  never estimated here. kind is a free-form label for the log (e.g. "stt", "chat"). */
  fun recordUsage(context: Context, kind: String, costUsd: Double) {
    ensureFreshDay(context)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val newTotal = prefs.getFloat(KEY_TOTAL_USD, 0f) + costUsd.toFloat()
    prefs.edit().putFloat(KEY_TOTAL_USD, newTotal).apply()
    AudioDiag.log(context, "USAGE_ADD", "kind=$kind costUsd=${"%.4f".format(costUsd)} dayTotalUsd=${"%.4f".format(newTotal)}")
  }

  fun dailyTotalUsd(context: Context): Double {
    ensureFreshDay(context)
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_TOTAL_USD, 0f).toDouble()
  }

  fun dailyCapEur(context: Context): Double =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getFloat(KEY_DAILY_CAP_EUR, DEFAULT_DAILY_CAP_EUR).toDouble()

  fun setDailyCapEur(context: Context, eur: Double) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putFloat(KEY_DAILY_CAP_EUR, eur.toFloat()).apply()
  }

  /** V3/V4 check this BEFORE any new paid call: true = fall back to the old path + speak the
   *  budget-exceeded phrase, never call OpenAI. */
  fun isBudgetExceeded(context: Context): Boolean =
    (dailyTotalUsd(context) * USD_TO_EUR) >= dailyCapEur(context)
}
