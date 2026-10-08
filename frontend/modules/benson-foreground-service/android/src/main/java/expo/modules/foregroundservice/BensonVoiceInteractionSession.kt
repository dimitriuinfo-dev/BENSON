package expo.modules.foregroundservice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/** PASUL 2a (07.10.2026) — deliberately empty: brings BENSON forward and closes immediately, no
 *  assist UI of its own. Only reachable via the system assist gesture. */
class BensonVoiceInteractionSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }?.let { ctx.startActivity(it) }
    hide()
  }
}
