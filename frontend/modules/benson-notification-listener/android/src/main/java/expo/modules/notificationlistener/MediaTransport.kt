package expo.modules.notificationlistener

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore

// RUNDA_N3 TASK C [NATIVE_MEDIA_CTL] (2026-10-02) — plain-Context mirror of
// BensonNotificationListenerModule's own mediaSessionManager()/activeControllers()/
// pickController() (same logic, same ComponentName, same "no filter = prefer PLAYING" fallback),
// so BensonForegroundService (not an Expo Module, no appContext.reactContext) can drive the exact
// same MediaSession transport already controlling Spotify, instead of a second implementation.
// The Module's own Functions (getActiveMediaSessions/mediaControl/getPlaybackState) are untouched.
object MediaTransport {
  private fun mediaSessionManager(context: Context): MediaSessionManager? =
    context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

  private fun activeControllers(context: Context, msm: MediaSessionManager): List<MediaController> {
    val component = ComponentName(context, BensonNotificationListenerService::class.java)
    return try { msm.getActiveSessions(component) } catch (_: SecurityException) { emptyList() }
  }

  private fun pickController(controllers: List<MediaController>, packageName: String): MediaController? {
    if (packageName.isNotEmpty()) controllers.firstOrNull { it.packageName == packageName }?.let { return it }
    controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
    return controllers.firstOrNull()
  }

  /** action: "play" | "pause" | "stop" | "next" | "previous" | "toggle" | "seek_forward" |
   * "seek_back". packageName: "" = no filter. */
  fun control(context: Context, packageName: String, action: String): Boolean {
    val msm = mediaSessionManager(context) ?: return false
    return try {
      val target = pickController(activeControllers(context, msm), packageName) ?: return false
      when (action) {
        "play" -> target.transportControls.play()
        "pause" -> target.transportControls.pause()
        "stop" -> target.transportControls.stop()
        "next" -> target.transportControls.skipToNext()
        "previous" -> target.transportControls.skipToPrevious()
        // RUNDA G-1 adaos (2026-10-04, user-directed) — "înainte/înapoi 10 secunde": standard
        // MediaSession actions, app-defined seek amount (not literally parameterizable to "10s"
        // through this API) — close enough per spec's own framing ("MediaSession întâi, apoi
        // butonul de pe ecran"), and the on-screen-button fallback in BensonAccessibilityService
        // covers the exact-amount case when a session doesn't support this.
        "seek_forward" -> target.transportControls.fastForward()
        "seek_back" -> target.transportControls.rewind()
        // ADAOS K-1 (2026-10-03, user-directed) — the headset-button short press must stay
        // "normal play/pause". The raw key event is consumed before we know it's short (see
        // BensonAccessibilityService.onKeyEvent), so this replicates it instead of re-dispatching.
        "toggle" -> if (target.playbackState?.state == PlaybackState.STATE_PLAYING) {
          target.transportControls.pause()
        } else {
          target.transportControls.play()
        }
        else -> return false
      }
      true
    } catch (_: Exception) { false }
  }

  // RUNDA MUSIC-2 (2026-10-04, user-directed) — STRATUL MEDIA, primul: standard MediaSession API
  // (MediaController.TransportControls.playFromSearch), implementată de orice app care vrea să
  // suporte căutare vocală prin Assistant — dacă sesiunea din prim-plan o are, nu mai e nevoie de
  // nicio automatizare de ecran. Fire-and-forget la nivel de OS (nu întoarce succes/eșec) — proba
  // reală e verificarea metadatei MediaSession după, făcută de apelant (BensonForegroundService).
  //
  // FIX_MUSIC2_UNSTRUCTURED_SEARCH_1 (2026-10-04, device-investigated) — primul test a chemat
  // playFromSearch(query, null): per documentația Google pentru "Assistant and media apps", extras
  // null înseamnă explicit "căutare nestructurată" — o cerere mai slabă, pe care multe aplicații o
  // tratează ca ambiguă (sau deloc) când nu redau deja ceva. O căutare STRUCTURATĂ, cu
  // EXTRA_MEDIA_FOCUS = tip piesă + EXTRA_MEDIA_TITLE = interogarea, e calea documentată oficial
  // pentru "redă piesa X" și dă aplicației mult mai multă informație ca să rezolve corect căutarea.
  fun playFromSearch(context: Context, packageName: String, query: String): Boolean {
    val msm = mediaSessionManager(context) ?: return false
    return try {
      val target = pickController(activeControllers(context, msm), packageName) ?: return false
      val extras = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, query)
      }
      target.transportControls.playFromSearch(query, extras)
      true
    } catch (_: Exception) { false }
  }

  // RUNDA MUSIC-1 (2026-10-04) — plain-Context mirror of BensonNotificationListenerModule's own
  // getPlaybackState Function (title/artist added for ROUND_SPOTIFY_SELECT_2 there), so
  // BensonForegroundService can verify a just-selected track's MediaSession metadata the same way
  // the proven JS adapter (mediaSearchExecutor.ts) did, without going through the JS bridge.
  data class PlaybackMeta(val packageName: String?, val title: String?, val artist: String?)

  fun metadata(context: Context, packageName: String): PlaybackMeta? {
    val msm = mediaSessionManager(context) ?: return null
    return try {
      val target = pickController(activeControllers(context, msm), packageName) ?: return null
      val md = target.metadata
      PlaybackMeta(
        target.packageName,
        md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
        md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
      )
    } catch (_: Exception) { null }
  }
}
