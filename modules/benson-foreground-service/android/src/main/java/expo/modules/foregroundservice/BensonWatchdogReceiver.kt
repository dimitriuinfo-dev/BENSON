package expo.modules.foregroundservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationCompat

/**
 * Fires every ~60s (scheduled by BensonForegroundService itself while it's alive) via
 * AlarmManager, which lives in system_server and keeps firing even if our whole process was
 * killed. If the service isn't running, restart it — this is the safety net for OEM background
 * killers (ColorOS's OsenseKillAction and similar) that can kill a foreground service despite
 * the app being exempted from stock Android battery optimization.
 */
class BensonWatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (BensonForegroundService.isRunning) {
      // Guardian heartbeat — read by BensonAccessibilityService (a different Gradle module, no
      // compile-time dependency between them) to tell a genuinely alive foreground service apart
      // from one that needs resurrecting after OxygenOS kills the whole process. Only written when
      // isRunning is confirmed true here, not when a restart was just requested below (that's a
      // request, not a confirmation).
      context.getSharedPreferences(GUARDIAN_PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).apply()
    } else {
      val serviceIntent = Intent(context, BensonForegroundService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
    }
    checkAccessibilityServiceAndNotify(context)
  }

  // Honest limitation (Item 0): there is no public API for an app to rebind or re-enable an
  // AccessibilityService the OS has unbound — that toggle lives entirely in Settings and requires
  // explicit user action, unlike BensonForegroundService above, which this app owns the lifecycle
  // of and can legitimately restart itself. This function only detects the outage and raises a
  // notification pointing at Settings; it cannot and does not attempt to fix it. Throttled to at
  // most once per NOTIFY_THROTTLE_MS so a stuck-off service doesn't repeat a notification every
  // single 60s watchdog tick.
  private fun checkAccessibilityServiceAndNotify(context: Context) {
    val expected = "${context.packageName}/expo.modules.accessibility.BensonAccessibilityService"
    val enabledServices = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    var enabled = false
    while (splitter.hasNext()) {
      if (splitter.next().equals(expected, ignoreCase = true)) {
        enabled = true
        break
      }
    }
    if (enabled) return

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val lastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED, 0L)
    val now = System.currentTimeMillis()
    if (now - lastNotifiedAt < NOTIFY_THROTTLE_MS) return
    prefs.edit().putLong(KEY_LAST_NOTIFIED, now).apply()

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      val channel = NotificationChannel(CHANNEL_ID, "BENSON Accessibility", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Alerts when BENSON's Accessibility Service has been turned off."
      }
      manager.createNotificationChannel(channel)
    }

    val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
    val pendingIntent = PendingIntent.getActivity(context, 0, settingsIntent, flags)

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle("BENSON")
      .setContentText("Serviciul de accesibilitate s-a oprit — atinge pentru a-l reactiva")
      .setSmallIcon(context.applicationInfo.icon)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()
    manager.notify(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val CHANNEL_ID = "benson_accessibility_watchdog_channel"
    private const val NOTIFICATION_ID = 4272
    private const val PREFS_NAME = "benson_watchdog_prefs"
    private const val KEY_LAST_NOTIFIED = "accessibility_last_notified_at"
    private const val NOTIFY_THROTTLE_MS = 30 * 60 * 1000L // 30 min

    // Shared with BensonAccessibilityService's Guardian resurrection logic — same prefs file,
    // literal key names duplicated there rather than shared via a Gradle dependency (see that
    // file's companion object for why). Keep both in sync if either changes.
    private const val GUARDIAN_PREFS_NAME = "benson_watchdog_prefs"
    private const val KEY_LAST_HEARTBEAT = "last_foreground_heartbeat"
  }
}
