package expo.modules.foregroundservice

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Guardian secondary revival layer — a Quick Settings tile the user adds once from the shade's
 * edit panel (no app-side API can add it automatically; this is standard Android). Tapping it
 * revives BENSON from the shade directly, without needing to find and open the app first, which
 * matters exactly when BENSON is dead and there's no notification/wake-word to rely on yet.
 */
class BensonQsTileService : TileService() {
  override fun onStartListening() {
    super.onStartListening()
    qsTile?.apply {
      label = "BENSON"
      state = if (BensonForegroundService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      icon = Icon.createWithResource(this@BensonQsTileService, applicationInfo.icon)
      updateTile()
    }
  }

  override fun onClick() {
    super.onClick()
    val svcIntent = Intent(this, BensonForegroundService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)

    val activityIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    } ?: return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pi = PendingIntent.getActivity(
        this, 0, activityIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
      startActivityAndCollapse(pi)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(activityIntent)
    }
  }
}
