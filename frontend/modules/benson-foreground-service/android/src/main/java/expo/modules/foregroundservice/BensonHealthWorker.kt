package expo.modules.foregroundservice

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Guardian secondary revival layer — WorkManager's job scheduling lives in a system-managed
 * service that survives this app's process death and reboots (once WorkManager's own DB is
 * restored, which the platform handles automatically), independent of both the AlarmManager
 * watchdog (BensonWatchdogReceiver) and the AccessibilityService resurrection anchor
 * (BensonAccessibilityService). 15 minutes is the OS-enforced minimum interval for periodic
 * work; this is deliberately redundant with the other two, not a replacement for either — OEM
 * background restrictions are known to throttle different Android subsystems differently, so
 * multiple independent paths are more resilient than one "correct" one.
 */
class BensonHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    if (!BensonForegroundService.isRunning) {
      val intent = Intent(applicationContext, BensonForegroundService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        applicationContext.startForegroundService(intent)
      } else {
        applicationContext.startService(intent)
      }
    }
    return Result.success()
  }
}
