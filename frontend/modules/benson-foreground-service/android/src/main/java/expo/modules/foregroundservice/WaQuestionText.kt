package expo.modules.foregroundservice

// ADĂUGARE — ÎNTREBĂRILE LUI BENSON ÎNCEP CU CUVÂNT INTEROGATIV (06.10.2026, product-owner-directed).
// În română, o întrebare da/nu se deosebește de o afirmație DOAR prin intonație — "O sun pe X?" și
// "O sun pe X." sunt identice ca text; dacă vocea nu urcă tonul, suna ca anunț, nu întrebare.
// "Dorești X?" e întrebare din cuvinte, indiferent cum o pronunță motorul TTS. Bonus: "apel cu X"
// evită acordul de gen ("o sun"/"îl sun") care depindea de genul contactului.
// Extras din BensonForegroundService.waQuestionFor (același pattern ca WaCallVideoMatcher) ca să
// fie JUnit-testabil — pur, zero dependență de Context. Logica de confirmare (da/nu, fereastra de
// captură) nu e atinsă aici, doar textul întrebării.
// CORECȚIE (06.10.2026, product-owner-directed): WA_MESSAGE rostește din nou conținutul mesajului
// — în mașină Rareș nu vede ecranul, deci WA_VISIBLE_DRAFT (neatins) nu ajută acolo; conținutul
// trebuie AUZIT înainte de „Da". Revenire parțială față de runda anterioară, care scosese conținutul.
// COMPLETARE (06.10.2026): WA_CALL schimbat la „Dorești să sun la X?" — „apel WhatsApp cu X" era
// corect gramatical, dar „Dorești să sun la X" evită explicit orice formă de acord pe verb legată
// de numele/genul contactului (nu mai există deloc -l/-o/-i de ales). WA_VIDEO aliniat la același
// loc pentru „pe WhatsApp" (la final, ca la CALL), neschimbat altfel.
object WaQuestionText {
  fun forKind(kind: String, name: String, message: String?): String = when (kind) {
    "call" -> "Dorești să sun la $name pe WhatsApp?"
    "video" -> "Dorești apel video cu $name pe WhatsApp?"
    else -> "Mesaj către $name: «$message». Dorești să-l trimit?"
  }
}
