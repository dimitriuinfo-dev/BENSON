package expo.modules.appregistry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ROUND_WA_NATIVE_CALL_PROBE_1 — feasibility probe ONLY.
 *
 * Question: does this device / WhatsApp installation expose a native ContactsContract WhatsApp
 * voice-call target that bypasses all chat / header / call-button UI automation?
 *
 * The probe:
 *   1. reads ContactsContract.Data for one resolved local contact, looking for the row with
 *      MIMETYPE "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
 *   2. if present, takes that row's Data._ID (NEVER persisted anywhere);
 *   3. builds Intent(ACTION_VIEW).setDataAndType(content://com.android.contacts/data/<id>, MIME)
 *      .setPackage("com.whatsapp"), and reports whether it resolves;
 *   4. only fires it when the caller explicitly asks (doLaunch) — a launch places a REAL call.
 *
 * It touches nothing in the existing WhatsApp call route (a different module entirely), uses no
 * Accessibility, no chat-header verification, no wa.me deep link, and only ever normal WhatsApp.
 * Capability is probed dynamically every call; the MIME row is never assumed to exist.
 */
object WhatsAppNativeCallProbe {
  private const val T = "BENSON_AUDIO"
  const val MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
  const val WA_PKG = "com.whatsapp"

  data class ProbeResult(
    val contactFound: Boolean,
    val displayName: String?,
    val mimeFound: Boolean,
    val dataId: Long?,           // returned for the report only — NOT cached/persisted
    val intentResolved: Boolean,
    val intentLaunched: Boolean,
    val resolverActivity: String?,
    val fail: String?,
  )

  fun run(context: Context, contactNameRaw: String, doLaunch: Boolean): ProbeResult {
    val contactName = contactNameRaw.trim()
    Log.i(T, "WA_NATIVE_CALL_PROBE_START contact=\"${contactName.take(24)}\" launch=$doLaunch")

    if (ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS,
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(T, "WA_NATIVE_CALL_PROBE_FAIL reason=no_read_contacts_permission")
      return ProbeResult(false, null, false, null, false, false, null, "no_read_contacts_permission")
    }

    // ── 1. one resolved local contact + its voip.call Data row ────────────────────────────────
    var dataId: Long? = null
    var displayName: String? = null
    try {
      val projection = arrayOf(
        ContactsContract.Data._ID,
        ContactsContract.Data.DISPLAY_NAME,
        ContactsContract.Data.MIMETYPE,
      )
      val selection = StringBuilder("${ContactsContract.Data.MIMETYPE} = ?")
      val args = mutableListOf(MIME)
      if (contactName.isNotEmpty()) {
        selection.append(" AND ${ContactsContract.Data.DISPLAY_NAME} LIKE ?")
        args.add("%$contactName%")
      }
      context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        projection,
        selection.toString(),
        args.toTypedArray(),
        "${ContactsContract.Data.DISPLAY_NAME} ASC",
      )?.use { c ->
        if (c.moveToFirst()) {
          dataId = c.getLong(c.getColumnIndexOrThrow(ContactsContract.Data._ID))
          displayName = c.getString(c.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME))
        }
      }
    } catch (e: Exception) {
      Log.i(T, "WA_NATIVE_CALL_PROBE_FAIL reason=data_query_exception:${e.javaClass.simpleName}")
      return ProbeResult(false, null, false, null, false, false, null, "data_query_exception")
    }

    // Independent "is there any such contact at all" check for the CONTACT_FOUND log line.
    var contactFound = dataId != null
    if (!contactFound && contactName.isNotEmpty()) {
      try {
        context.contentResolver.query(
          ContactsContract.Contacts.CONTENT_URI,
          arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
          "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
          arrayOf("%$contactName%"),
          null,
        )?.use { c ->
          if (c.moveToFirst()) {
            contactFound = true
            if (displayName == null) displayName = c.getString(1)
          }
        }
      } catch (_: Exception) { /* leave contactFound false */ }
    }

    if (contactFound) {
      Log.i(T, "WA_NATIVE_CALL_CONTACT_FOUND name=\"${displayName ?: "?"}\"")
    } else {
      Log.i(T, "WA_NATIVE_CALL_PROBE_FAIL reason=contact_not_found")
      return ProbeResult(false, displayName, false, null, false, false, null, "contact_not_found")
    }

    if (dataId == null) {
      Log.i(T, "WA_NATIVE_CALL_MIME_NOT_FOUND")
      return ProbeResult(true, displayName, false, null, false, false, null, "mime_row_absent")
    }
    Log.i(T, "WA_NATIVE_CALL_MIME_FOUND dataId=$dataId")

    // ── 2. build the typed intent, check resolution against normal WhatsApp ───────────────────
    val uri = Uri.parse("content://com.android.contacts/data/$dataId")
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, MIME)
      setPackage(WA_PKG)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val matches = try {
      context.packageManager.queryIntentActivities(intent, 0)
    } catch (_: Exception) { emptyList() }
    val resolved = matches.isNotEmpty()
    val resolverName = matches.firstOrNull()?.activityInfo?.let { "${it.packageName}/${it.name}" }
    Log.i(T, "WA_NATIVE_CALL_INTENT_RESOLVED value=$resolved activity=${resolverName ?: "-"}")

    // ── 3. launch ONLY on explicit request (a launch places a real call) ─────────────────────
    var launched = false
    if (resolved && doLaunch) {
      try {
        context.startActivity(intent)
        launched = true
        Log.i(T, "WA_NATIVE_CALL_INTENT_LAUNCHED")
      } catch (e: Exception) {
        Log.i(T, "WA_NATIVE_CALL_PROBE_FAIL reason=start_activity_exception:${e.javaClass.simpleName}")
        return ProbeResult(true, displayName, true, dataId, true, false, resolverName, "start_activity_exception")
      }
    }

    return ProbeResult(
      contactFound = true,
      displayName = displayName,
      mimeFound = true,
      dataId = dataId,
      intentResolved = resolved,
      intentLaunched = launched,
      resolverActivity = resolverName,
      fail = if (!resolved) "intent_unresolved" else null,
    )
  }
}
