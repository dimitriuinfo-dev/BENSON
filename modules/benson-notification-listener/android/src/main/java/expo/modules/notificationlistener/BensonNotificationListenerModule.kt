package expo.modules.notificationlistener

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

class BensonNotificationListenerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonNotificationListener")

    // No runtime-permission callback exists for this — the caller must re-check after the user
    // returns from the settings screen, same pattern as benson-accessibility's isServiceEnabled.
    Function("isEnabled") {
      val context = appContext.reactContext ?: return@Function false
      val flat = ComponentName(context, BensonNotificationListenerService::class.java).flattenToString()
      val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
      enabledListeners?.split(":")?.any { it == flat } == true
    }

    // "Notification access" is a special permission Android will not grant via the normal
    // runtime prompt — this opens the dedicated system settings screen for it.
    Function("openNotificationListenerSettings") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // ── ROUND_MEDIA_GOVERNANCE_1 ──────────────────────────────────────────────────────────────
    // Priority-1 media control per the round spec: Android's own MediaSession framework, not
    // Accessibility button-pressing. MediaSessionManager.getActiveSessions() requires exactly the
    // permission this module already exists for and is already onboarded for (a live
    // NotificationListenerService component) — no new permission, no new onboarding step. Every
    // call here is synchronous (plain OS binder calls, same as getForegroundPackage() elsewhere)
    // and wrapped so a SecurityException (listener not actually enabled right now) is reported
    // honestly, never crashes the caller.
    Function("getActiveMediaSessions") {
      mediaSessionManager()?.let { msm ->
        try {
          val out = JSONArray()
          for (c in activeControllers(msm)) {
            val state = c.playbackState
            out.put(JSONObject().apply {
              put("packageName", c.packageName)
              put("state", state?.state ?: PlaybackState.STATE_NONE)
              put("actions", state?.actions ?: 0L)
            })
          }
          out.toString()
        } catch (_: SecurityException) {
          "SECURITY_EXCEPTION"
        } catch (_: Exception) {
          "[]"
        }
      } ?: "[]"
    }

    // action: "play" | "pause" | "stop" | "next" | "previous". packageName: "" = no filter (picks
    // the first actively-PLAYING session, else the first active session at all — same fallback
    // idiom as getForegroundPackage-style "best effort, never guess a coordinate").
    Function("mediaControl") { packageName: String, action: String ->
      val msm = mediaSessionManager() ?: return@Function false
      try {
        val target = pickController(activeControllers(msm), packageName) ?: return@Function false
        val tc = target.transportControls
        when (action) {
          "play" -> tc.play()
          "pause" -> tc.pause()
          "stop" -> tc.stop()
          "next" -> tc.skipToNext()
          "previous" -> tc.skipToPrevious()
          else -> return@Function false
        }
        true
      } catch (_: Exception) {
        false
      }
    }

    // Returns {"packageName": string|null, "state": int, "title": string|null, "artist": string|null}.
    // state: -1 = no active session found, -2 = SecurityException (listener not enabled),
    // otherwise a PlaybackState.STATE_* constant (0=NONE, 1=STOPPED, 2=PAUSED, 3=PLAYING, ...).
    // ROUND_SPOTIFY_SELECT_2 — title/artist added (purely additive; packageName/state unchanged)
    // so a caller can verify not just THAT something is playing but that the metadata matches
    // what was actually selected, per that round's explicit acceptance requirement.
    Function("getPlaybackState") { packageName: String ->
      val msm = mediaSessionManager()
        ?: return@Function JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
      try {
        val target = pickController(activeControllers(msm), packageName)
          ?: return@Function JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
        val md = target.metadata
        JSONObject().apply {
          put("packageName", target.packageName)
          put("state", target.playbackState?.state ?: PlaybackState.STATE_NONE)
          put("title", md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: JSONObject.NULL)
          put("artist", md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: JSONObject.NULL)
        }.toString()
      } catch (_: SecurityException) {
        JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -2) }.toString()
      } catch (_: Exception) {
        JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
      }
    }
  }

  private fun mediaSessionManager(): MediaSessionManager? {
    val context = appContext.reactContext ?: return null
    return context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
  }

  private fun activeControllers(msm: MediaSessionManager): List<MediaController> {
    val context = appContext.reactContext ?: return emptyList()
    val component = ComponentName(context, BensonNotificationListenerService::class.java)
    return msm.getActiveSessions(component)
  }

  // packageName empty -> prefer whichever session is actually PLAYING right now, else the first
  // active session (most-recently-active first, per Android's own ordering) — this is the "no
  // filter needed" path a caller that doesn't know the exact package (e.g. a radio app opened via
  // the generic app-launcher) can still rely on.
  private fun pickController(controllers: List<MediaController>, packageName: String): MediaController? {
    if (packageName.isNotEmpty()) {
      controllers.firstOrNull { it.packageName == packageName }?.let { return it }
    }
    controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
    return controllers.firstOrNull()
  }
}
