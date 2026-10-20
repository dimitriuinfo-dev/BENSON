package expo.modules.accessibility

// SURVIVOR/EXECUTOR — verificare semantică generică a unei interacțiuni (apăsare), NU specifică
// Calculatorului sau oricărei alte aplicații. Răspunde unei cauze reale, DEVICE_FAIL (19.10.2026,
// misiunea "Benson, deschide Calculatorul și calculează 27 × 14" — ecranul final arăta `89.979()`,
// nu `378`): `tapDigestElement` raporta `ControlResult(true, ...)` necondiționat după o apăsare
// acceptată, chiar și când ecranul NU se schimba deloc după.
//
// S4 (19.10.2026, precizări DeepSeek acceptate) — TREI straturi distincte, nu unul singur:
//   1. apăsare ACCEPTATĂ    — `performAction`/`click` a reușit tehnic (nu spune nimic despre efect)
//   2. schimbare OBSERVATĂ  — ceva, oriunde pe ecran, diferă față de înainte (semnal slab, poate
//                             veni dintr-un element irelevant — un ceas, o animație)
//   3. efect CORECT VERIFICAT — schimbarea corespunde țintei apăsate, nu doar "ceva s-a schimbat"
// Cele trei nu se confundă. Un `ok=true` cere satisfacerea tuturor trei.
//
// Pur, zero Context — testabil JUnit, reutilizabil de orice interacțiune (tap/scroll/etc.), nu doar
// de Calculator. Nu cunoaște nimic despre cifre, operatori, matematică sau poziții de butoane — doar
// despre existența elementului, acceptarea acțiunii și dacă elementul FOCALIZAT (generic, orice
// aplicație cu un câmp activ) s-a schimbat vizibil.

enum class InteractionFailureReason { UI_NODE_NOT_FOUND, UI_ACTION_REJECTED, VERIFICATION_FAILED, UNKNOWN }

/**
 * Dovezile disponibile după o interacțiune, separate pe cele trei straturi (vezi header).
 *
 * @param elementFound elementul țintă a fost identificat pe ecranul curent
 * @param actionAccepted apăsarea/acțiunea a fost acceptată tehnic (nu a eșuat la nivel de API)
 * @param screenReobserved s-a putut reobserva ecranul după acțiune (null-safe — altfel UNKNOWN)
 * @param overallScreenChanged DOAR relevant dacă [screenReobserved]: ceva, oriunde, diferă
 * @param focusedContentChanged corespondența cu ținta: s-a schimbat conținutul elementului(elor)
 *   FOCALIZATE? `null` = niciun element focalizat de comparat (nu putem nici confirma, nici
 *   contrazice corespondența — nu se inventează o concluzie din lipsă de dovadă)
 */
data class InteractionEvidence(
  val elementFound: Boolean,
  val actionAccepted: Boolean,
  val screenReobserved: Boolean,
  val overallScreenChanged: Boolean,
  val focusedContentChanged: Boolean?,
)

/**
 * null = succes real: elementul a fost găsit, acțiunea acceptată, ecranul reobservat, și efectul
 * a fost confirmat — fie prin elementul FOCALIZAT (semnal tare, prioritar), fie, când nu există
 * niciun element focalizat de comparat, prin schimbarea generală a ecranului (semnal slab, de rezervă).
 *
 * Punctul 2 (S4): absența de schimbare, cu o reobservare REUȘITĂ, e dovadă demonstrabilă de eșec
 * (VERIFICATION_FAILED) — nu UNKNOWN. UNKNOWN e rezervat strict cazului în care dovada însăși
 * lipsește (n-am putut reobserva deloc).
 * Punctul 3 (S4): o schimbare oarecare NU e automat succes — dacă exista un element focalizat de
 * verificat și ACELA nu s-a schimbat (schimbarea a venit din altă parte, irelevantă), tot
 * VERIFICATION_FAILED.
 *
 * FIX_FOCUSED_PRIORITY_1 (19.10.2026, S4, a doua rulare reala, DOVADA PE TELEFON) — CAUZA REALA:
 * ordinea veche verifica `overallScreenChanged` INAINTEA lui `focusedContentChanged`, deci o apasare
 * REUSITA (camp focalizat schimbat de la "" la "1" — vazut in log, before_focused="" after_focused=
 * "1") era totusi raportata VERIFICATION_FAILED doar pentru ca semnatura intregului ecran (semnalul
 * SLAB) nu surprinsese schimbarea. Asta putea induce agentul in eroare, raportandu-i esec pe o
 * apasare corecta. Cand exista o comparatie de focalizare (semnal TARE), ea decide — semnalul slab
 * (overall) ramane doar rezerva, pentru cazul fara niciun element focalizat de comparat.
 */
// S4 RETEST (19.10.2026, DOVADA PE TELEFON) — distinge, generic (orice aplicatie), doua cauze
// diferite ascunse pana acum sub acelasi "stale_index": ecranul s-a schimbat IN ACEEASI aplicatie
// (navigare interna) vs. O ALTA aplicatie a preluat prim-planul cu totul (de regula o notificare
// externa). Dovedit real: in timp ce agentul "gandea" intre doua apeluri, o notificare a unei
// aplicatii terte a adus o alta activitate in prim-plan, inlocuind aplicatia tinta — indicii
// pareau doar "stale", desi cauza reala era cu totul alta aplicatie pe ecran.
fun isForeignAppForeground(seenPackage: String, currentPackage: String): Boolean =
  seenPackage.isNotBlank() && currentPackage.isNotBlank() && seenPackage != currentPackage

fun classifyInteractionOutcome(evidence: InteractionEvidence): InteractionFailureReason? = when {
  !evidence.elementFound -> InteractionFailureReason.UI_NODE_NOT_FOUND
  !evidence.actionAccepted -> InteractionFailureReason.UI_ACTION_REJECTED
  !evidence.screenReobserved -> InteractionFailureReason.UNKNOWN
  evidence.focusedContentChanged != null ->
    if (evidence.focusedContentChanged) null else InteractionFailureReason.VERIFICATION_FAILED
  !evidence.overallScreenChanged -> InteractionFailureReason.VERIFICATION_FAILED
  else -> null
}
