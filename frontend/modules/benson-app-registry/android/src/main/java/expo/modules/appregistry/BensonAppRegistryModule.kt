package expo.modules.appregistry

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream

/**
 * BENSON 4 — App Registry
 *
 * Enumerates apps with a launcher icon (the same set Android's own home-screen app
 * drawer shows) via ACTION_MAIN/CATEGORY_LAUNCHER — this intent pair is exempt from
 * Android 11+ package-visibility restrictions, so no <queries> manifest entry is needed.
 * Feeds the onboarding screen (components/onboarding/AppPermissionsModal.tsx) where the
 * user picks which apps BENSON may open/operate — nothing is governed without explicit
 * per-app consent.
 */
class BensonAppRegistryModule : Module() {
  companion object {
    /** Set by OnCreate below, called by MainActivity.onPictureInPictureModeChanged. */
    var onPipModeChanged: ((Boolean) -> Unit)? = null
  }

  override fun definition() = ModuleDefinition {
    Name("BensonAppRegistry")
    Events("onPipModeChanged")

    OnCreate {
      onPipModeChanged = { isInPip ->
        sendEvent("onPipModeChanged", mapOf("isInPip" to isInPip))
      }
    }

    OnDestroy {
      onPipModeChanged = null
    }

    AsyncFunction("getInstalledApps") {
      val context = appContext.reactContext ?: return@AsyncFunction emptyList<Map<String, Any?>>()
      val pm = context.packageManager
      val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val resolved = pm.queryIntentActivities(launcherIntent, 0)

      resolved
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ri ->
          mapOf(
            "packageName" to ri.activityInfo.packageName,
            "appName" to ri.loadLabel(pm).toString(),
            "icon" to iconToBase64(ri.loadIcon(pm)),
          )
        }
        .sortedBy { (it["appName"] as String).lowercase() }
    }

    // Reliable arbitrary-app launch by package name. `Linking.openURL('android-app://<pkg>')`
    // only works for apps that happen to declare an intent filter matching that exact URI —
    // most third-party apps (ChatGPT, Claude, etc.) don't, so it silently no-ops. This is the
    // same mechanism Android's own home-screen launcher uses, so it works for anything installed.
    Function("launchApp") { packageName: String ->
      // Deterministic fix (2026-07-09) for launches that started the target app's process
      // without ever bringing it to the visible screen ("opened in the background"), confirmed
      // live and previously only worked around with a fixed delay before this call (a timing
      // guess, not a guarantee). Starting the Activity from appContext.currentActivity — the
      // actual resumed Activity instance — rather than appContext.reactContext (an Application-
      // level context) means the call itself comes from something Android's own background-
      // activity-launch rules already recognize as "caller has a visible window", the documented
      // exemption, instead of gambling on a delay letting an earlier transition settle first.
      val activity = appContext.currentActivity
      val context = activity ?: appContext.reactContext ?: return@Function false
      // findLauncherActivity(), not context.packageManager.getLaunchIntentForPackage(packageName)
      // directly — confirmed live 2026-07-17: getLaunchIntentForPackage is subject to Android 11+
      // package-visibility filtering and silently returned null for YouTube (genuinely installed,
      // opened by the user minutes earlier) because this app has no <queries> manifest entry
      // naming that specific package. The ACTION_MAIN/CATEGORY_LAUNCHER enumeration
      // getInstalledApps() already uses IS exempt from that filtering — building the launch
      // Intent explicitly from that same enumeration's result means any app the user can see on
      // their own home screen launches correctly, without adding a <queries> entry per app.
      val resolved = findLauncherActivity(context, packageName) ?: return@Function false
      val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
      }
      if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
      true
    }

    // Explicit pre-flight check for the Action Engine's allowlist executor — same underlying
    // check launchApp does internally, exposed separately so callers can log/report "not
    // installed" distinctly from an actual launch failure.
    Function("isPackageInstalled") { packageName: String ->
      val context = appContext.reactContext ?: return@Function false
      findLauncherActivity(context, packageName) != null
    }

    // Item 2 — direct native phone call. Deliberately NOT Linking.openURL('tel:...'): that issues
    // an implicit ACTION_VIEW, which Android resolves the same way as ACTION_DIAL — if more than
    // one installed app registers a tel: handler (a second dialer, a call-recorder, etc.) the user
    // sees an "Open with" chooser, and even with a single handler it only opens the dialer
    // pre-filled, requiring one more manual tap. ACTION_CALL is different: it requires the
    // CALL_PHONE permission from the CALLING app (checked below) and is handled directly by the
    // device's phone stack with no chooser, no extra tap — an actual placed call. Never call this
    // without CALL_PHONE already granted; the JS caller (phoneCallExecutor.ts) is responsible for
    // requesting that permission first and falling back to the dialer (dial()) on denial.
    Function("hasCallPhonePermission") {
      val context = appContext.reactContext ?: return@Function false
      ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    }

    Function("placeDirectCall") { phoneNumber: String ->
      val context = appContext.reactContext ?: return@Function false
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        return@Function false
      }
      try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
      } catch (e: Exception) {
        false
      }
    }

    // Confirmed live (2026-07-17): a plain Linking.openURL('https://wa.me/...') (implicit
    // ACTION_VIEW) let Android pick WHICHEVER installed app is the current default handler for
    // that link — on a phone with both WhatsApp and WhatsApp Business installed, that turned out
    // to be Business, silently sending the message from the wrong account. setPackage() forces
    // the intent to a specific app, the same no-chooser-ambiguity idiom placeDirectCall already
    // uses for ACTION_CALL — here targeting regular WhatsApp (com.whatsapp) specifically,
    // regardless of which app the OS would otherwise have defaulted to.
    Function("openUriWithPackage") { uri: String, packageName: String ->
      val context = appContext.reactContext ?: return@Function false
      try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        intent.setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
      } catch (e: Exception) {
        false
      }
    }

    // ── ROUND_EMERGENCY_CORE_1 — explicit emergency route, deliberately separate from the
    // app-governance / WhatsApp path. VOICE → classify → (JS) confirmation policy → route. ──────

    // Single source of truth for "is this utterance an explicit emergency intent". Closed set,
    // synchronous, no side effects — the JS confirmation policy calls this before every other gate.
    // Returns "NONE" | "EXPLICIT_112" | "GENERIC_HELP".
    Function("classifyEmergencyIntent") { text: String ->
      EmergencyIntentRouter.classify(text).name
    }

    // One-shot emergency context snapshot (timestamp / battery / network / whether a last-known
    // location fix already exists). Logged natively; raw coordinates NEVER cross the bridge and are
    // never uploaded; this read never blocks the 112 route.
    Function("getEmergencyContext") {
      val context = appContext.reactContext ?: return@Function null
      val s = EmergencyIntentRouter.snapshotContext(context)
      mapOf(
        "timestamp" to s.timestamp,
        "batteryPct" to s.batteryPct,
        "network" to s.network,
        "locationAvailable" to s.locationAvailable,
      )
    }

    // Route 112 to the native Android telecom stack: a real placed call (ACTION_CALL) when
    // CALL_PHONE is granted, otherwise the system dialer pre-filled with 112 (ACTION_DIAL) — one
    // tap, no chooser. Never WhatsApp, never a third-party calling app, never Accessibility typing.
    // Resolves { success, mode: DIRECT_CALL|SYSTEM_DIALER|FAILED, reason }.
    AsyncFunction("routeEmergencyCall") { promise: Promise ->
      val context = appContext.currentActivity ?: appContext.reactContext
      if (context == null) {
        promise.resolve(mapOf("success" to false, "mode" to "FAILED", "reason" to "no context"))
        return@AsyncFunction
      }
      val r = EmergencyIntentRouter.route(context)
      promise.resolve(mapOf("success" to r.success, "mode" to r.mode, "reason" to r.reason))
    }

    // ── ROUND_WA_NATIVE_CALL_PROBE_1 — feasibility probe ONLY. Does NOT touch the existing
    // WhatsApp call route; no Accessibility, no chat-header verify, no wa.me. `doLaunch=true`
    // fires the typed contacts intent, which places a REAL call — default false. Resolves
    // { contactFound, displayName, mimeFound, dataId, intentResolved, intentLaunched,
    //   resolverActivity, fail }. dataId is returned for the report only, never persisted. ──────
    AsyncFunction("probeWhatsAppNativeCall") { contactName: String, doLaunch: Boolean, promise: Promise ->
      val context = appContext.currentActivity ?: appContext.reactContext
      if (context == null) {
        promise.resolve(mapOf("fail" to "no_context"))
        return@AsyncFunction
      }
      val r = WhatsAppNativeCallProbe.run(context, contactName, doLaunch)
      promise.resolve(
        mapOf(
          "contactFound" to r.contactFound,
          "displayName" to r.displayName,
          "mimeFound" to r.mimeFound,
          "dataId" to r.dataId,
          "intentResolved" to r.intentResolved,
          "intentLaunched" to r.intentLaunched,
          "resolverActivity" to r.resolverActivity,
          "fail" to r.fail,
        ),
      )
    }

    // Real Android Picture-in-Picture (product-owner-directed 2026-07-17, "screen in screen"):
    // BENSON's own Activity shrinks into a small, user-movable window while the next app that
    // takes focus (WhatsApp, launched right after this call) fills the rest of the screen. This
    // is the opposite of a custom-drawn overlay bubble — it's the real OS-level PiP an app can
    // only ever trigger on ITSELF, never on another app (there is no API to force WhatsApp into a
    // small window from outside it — Android's security model doesn't allow that). Call this
    // immediately before launching WhatsApp so the shrink happens right as focus is about to move.
    Function("enterPipMode") {
      val activity = appContext.currentActivity ?: return@Function false
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@Function false
      try {
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
      } catch (e: Exception) {
        false
      }
    }
  }

  // Single source of truth for "does this package have a launcher activity, and what is it" —
  // built from the SAME exempt ACTION_MAIN/CATEGORY_LAUNCHER enumeration getInstalledApps() uses,
  // never PackageManager.getLaunchIntentForPackage() directly (that call IS subject to Android
  // 11+ package-visibility filtering and returns null for any package not covered by a <queries>
  // manifest entry, confirmed live for YouTube 2026-07-17).
  private fun findLauncherActivity(context: Context, packageName: String): ResolveInfo? {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(launcherIntent, 0)
      .find { it.activityInfo.packageName == packageName }
  }

  private fun iconToBase64(drawable: Drawable): String {
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
      val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
      val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bmp)
      drawable.setBounds(0, 0, canvas.width, canvas.height)
      drawable.draw(canvas)
      bmp
    }
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
    return "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
  }
}
