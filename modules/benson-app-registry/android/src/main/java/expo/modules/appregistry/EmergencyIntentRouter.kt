package expo.modules.appregistry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ROUND_EMERGENCY_CORE_1 — the smallest reliable BENSON emergency route.
 *
 * VOICE → [classify] → confirmation policy (JS side) → [route] → observe outcome.
 *
 * Deliberately NOT part of the WhatsApp / app-governance path:
 *  - explicit "sună la 112" always goes straight to the native Android telecom stack (tel:112),
 *    never WhatsApp, never a third-party calling app, never Accessibility typing;
 *  - a real placed call (ACTION_CALL) only when CALL_PHONE is already granted, otherwise the
 *    system dialer pre-filled with 112 (ACTION_DIAL) — one tap, no chooser ambiguity;
 *  - the EmergencyContext snapshot (timestamp / last-known location / battery / network) is
 *    read once, logged locally, and NEVER blocks the 112 route and NEVER uploaded.
 */
object EmergencyIntentRouter {
  private const val T = "BENSON_AUDIO"
  const val EMERGENCY_NUMBER = "112"

  enum class EmergencyIntent { NONE, EXPLICIT_112, GENERIC_HELP }

  // Closed set. The whole utterance (minus an optional leading "benson," and trailing
  // punctuation) must BE one of these — a stray "ajutor" inside a longer sentence is left to
  // the normal HELP parser, only the bare spoken exclamation escalates here.
  private val GENERIC = Regex(
    """^(?:benson[\s,]+)?(?:o\s+)?(?:ajutor|urgen[țt][ăa]|help)\s*[!.?…]*$""",
  )
  private val EXPLICIT = Regex(
    """(?:^|\b)(?:benson[\s,]+)?(?:""" +
      """sun[ăaâ]\s+la\s+112|sun[ăaâ]\s+112|suna[țt]i\s+la\s+112|""" +
      """cheam[ăa]\s+(?:o\s+)?(?:ambulan[țt][ae]|salvarea|salvare|poli[țt]ia|poli[țt]ie|pompierii|pompieri)|""" +
      """cheam[ăa]\s+la\s+112|apeleaz[ăa]\s+(?:la\s+)?112""" +
      """)(?:$|\b)""",
  )

  fun classify(textRaw: String?): EmergencyIntent {
    val t = (textRaw ?: "").trim().lowercase()
    if (t.isEmpty()) return EmergencyIntent.NONE
    if (EXPLICIT.containsMatchIn(t)) return EmergencyIntent.EXPLICIT_112
    if (GENERIC.matches(t)) return EmergencyIntent.GENERIC_HELP
    return EmergencyIntent.NONE
  }

  data class RouteResult(val success: Boolean, val mode: String, val reason: String?)
  // mode: DIRECT_CALL | SYSTEM_DIALER | FAILED

  /**
   * Route to the native emergency dialer. Reads (and logs) the context snapshot first — that read
   * is cheap and never blocks; it does NOT gate the dial. Prefers a real placed call, falls back
   * to the pre-filled system dialer, and is honest in the result about which happened.
   */
  fun route(context: Context): RouteResult {
    Log.i(T, "EMERGENCY_DIAL_START number=$EMERGENCY_NUMBER")
    // Non-blocking: snapshot is logged, never awaited, never sent anywhere.
    try { snapshotContext(context) } catch (_: Exception) {}

    val canDirect = ContextCompat.checkSelfPermission(
      context, Manifest.permission.CALL_PHONE,
    ) == PackageManager.PERMISSION_GRANTED
    Log.i(T, "EMERGENCY_DIRECT_CALL_ALLOWED value=$canDirect")

    if (canDirect) {
      try {
        context.startActivity(
          Intent(Intent.ACTION_CALL, Uri.parse("tel:$EMERGENCY_NUMBER"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(T, "EMERGENCY_SYSTEM_DIALER_OPENED mode=direct_call number=$EMERGENCY_NUMBER")
        return RouteResult(true, "DIRECT_CALL", null)
      } catch (e: Exception) {
        Log.i(T, "EMERGENCY_ROUTE_FAIL reason=direct_call_exception:${e.javaClass.simpleName}")
        // fall through to the pre-filled dialer
      }
    }

    return try {
      context.startActivity(
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$EMERGENCY_NUMBER"))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      Log.i(T, "EMERGENCY_SYSTEM_DIALER_OPENED mode=dial_prefilled number=$EMERGENCY_NUMBER")
      RouteResult(true, "SYSTEM_DIALER", null)
    } catch (e: Exception) {
      Log.i(T, "EMERGENCY_ROUTE_FAIL reason=dial_exception:${e.javaClass.simpleName}")
      RouteResult(false, "FAILED", "${e.javaClass.simpleName}: ${e.message}")
    }
  }

  data class EmergencyContext(
    val timestamp: Long,
    val batteryPct: Int,       // -1 if unavailable
    val network: String,       // wifi | cellular | other | none | unknown
    val locationAvailable: Boolean,
  )

  /**
   * One-shot, best-effort. Last-known location ONLY if the permission is already held; never
   * requests it, never starts location updates, never returns coordinates across the bridge —
   * only whether a fix exists. Battery + network are read synchronously.
   */
  fun snapshotContext(context: Context): EmergencyContext {
    val ts = System.currentTimeMillis()

    val battery = try {
      (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (_: Exception) { -1 }

    val network = try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
      when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
      }
    } catch (_: Exception) { "unknown" }

    val fineOk = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarseOk = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var hasFix = false
    if (fineOk || coarseOk) {
      try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providers = lm?.getProviders(true).orEmpty()
        for (p in providers) {
          val loc = try { lm?.getLastKnownLocation(p) } catch (_: SecurityException) { null }
          if (loc != null) { hasFix = true; break }
        }
      } catch (_: Exception) {}
    }
    val locSource = if (fineOk) "fine" else if (coarseOk) "coarse" else "none"
    Log.i(T, "EMERGENCY_LOCATION_AVAILABLE value=$hasFix source=$locSource")
    Log.i(T, "EMERGENCY_CONTEXT ts=$ts batteryPct=$battery network=$network locationAvailable=$hasFix")

    return EmergencyContext(ts, battery, network, hasFix)
  }
}
