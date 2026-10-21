package expo.modules.accessibility

// COMMUNICATION FAST ROUTE (21.10.2026, mandat "anti-duplicare — stari explicite") — verdict PUR,
// derivat din campurile DEJA existente ale WhatsAppCallNativeResult (success/step/nameMatch),
// fara nicio modificare a acelui data class sau a runWhatsAppCallNative/
// runWhatsAppOpenConversationCall (ambele raman neatinse). Patru stari, nu doua (succes/esec) —
// regula anti-duplicare cere sa NU se confunde "incert" cu "esuat confirmat".
enum class WaCallVerdict { NOT_STARTED, CALL_VERIFIED, CALL_UNVERIFIED, CALL_FAILED_VERIFIED }

// Toti pasii existenti (ambele rute) care dovedesc ca butonul de apel NU a fost inca atins —
// niciun efect extern posibil, fallback controlat permis in siguranta.
private val PRE_CALL_BUTTON_STEPS = setOf(
  "VALIDATE", "LAUNCH", "PACKAGE", "SEARCH_NOT_FOUND", "SEARCH_CLICK", "SEARCH_NOT_OPEN", "SET_TEXT",
  "TEXT_MISMATCH", "CONTACT_NOT_FOUND", "CONTACT_CLICK", "CHAT_NOT_OPEN", "CHAT_VERIFY", "CHAT_WRONG_CONTACT",
  "CALL_NOT_FOUND", "CALL_BLOCKED",
  "WHATSAPP_DEEPLINK_FAILED", "WHATSAPP_CONTACT_VERIFY_FAILED", "CALL_BUTTON_NOT_FOUND",
)

fun classifyWaCallOutcome(success: Boolean, step: String, nameMatch: Boolean): WaCallVerdict = when {
  step in PRE_CALL_BUTTON_STEPS -> WaCallVerdict.NOT_STARTED
  // ACTION_CLICK pe butonul de apel a fost REFUZAT de sistem — dovada explicita ca apelul nu a
  // pornit, nu doar o presupunere din absenta ecranului de apel.
  step == "CALL_NOT_STARTED" -> WaCallVerdict.CALL_FAILED_VERIFIED
  success && step == "CALL_VERIFIED" && nameMatch -> WaCallVerdict.CALL_VERIFIED
  // Tot restul (CALL_CLICK fara ecran confirmat, CALL_WRONG_CONTACT — un apel A PORNIT dar spre
  // alt destinatar, CALL_VERIFIED fara nameMatch, EXCEPTION, SERVICE) e INCERT, nu un esec
  // confirmat: un apel ar putea fi deja activ. Fallback automat interzis.
  else -> WaCallVerdict.CALL_UNVERIFIED
}

// Doar NOT_STARTED si CALL_FAILED_VERIFIED autorizeaza o reincercare/fallback controlat — niciun
// efect extern demonstrat. CALL_UNVERIFIED cere explicit interventia Masterului (regula anti-
// duplicare), CALL_VERIFIED e deja un succes, nu se repeta.
fun waCallSafeToRetry(verdict: WaCallVerdict): Boolean =
  verdict == WaCallVerdict.NOT_STARTED || verdict == WaCallVerdict.CALL_FAILED_VERIFIED
