package expo.modules.foregroundservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restarts the listening service after a phone reboot or an app update (MY_PACKAGE_REPLACED —
 * a fresh APK install otherwise sits silent until the user manually reopens it), so BENSON
 * doesn't stay silent in either case. */
class BensonBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    AudioDiag.log(context, "BOOT_RECEIVED", "action=$action")
    val serviceIntent = Intent(context, BensonForegroundService::class.java)
    // PASUL 2 TEST A (07.10.2026) — "fără excepții în buclă" + FGS_START, same pattern as the
    // watchdog/healthworker. Android 14+/15 is documented to refuse microphone-type FGS from
    // BOOT_COMPLETED regardless of exemptions — this log is what proves/disproves that live.
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
      AudioDiag.log(context, "FGS_START", "source=boot allowed=true exemption=voice_interaction_service")
    } catch (e: Exception) {
      AudioDiag.log(context, "FGS_START", "source=boot allowed=false exemption=voice_interaction_service error=${e.javaClass.simpleName}")
    }
  }
}
