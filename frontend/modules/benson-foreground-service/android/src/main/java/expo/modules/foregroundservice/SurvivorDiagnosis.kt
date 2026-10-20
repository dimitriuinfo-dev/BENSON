package expo.modules.foregroundservice

// SURVIVOR (20.10.2026, etapa 3 — "dupa inchiderea AKA") — NU un executor nou si NU un orchestrator
// nou. Audit facut INTAI, cerut explicit: infrastructura de verificare EXISTA deja si ramane
// neschimbata aici:
//   - InteractionVerification.kt (benson-accessibility) — observarea REALA a efectului unei
//     apasari (elementFound/actionAccepted/screenReobserved/focusedContentChanged), deja
//     etichetata "SURVIVOR/EXECUTOR" in propriul ei header. Acest fisier CONSUMA rezultatul ei
//     (InteractionFailureReason), nu il re-calculeaza.
//   - AgentVerificationGate.kt — gardurile de SECVENTA (actiune->observare obligatorie inainte de
//     "say") raman neschimbate; SURVIVOR adauga DIAGNOSTIC si DECIZIE DE REINCERCARE pe langa ele,
//     nu le inlocuieste.
//   - BensonAgentContracts.kt (D0) — ExecutionOutcome (Success/Failed/Unverified),
//     ExecutionIdempotencyKey/isDuplicateExecution: deja pure, deja testate, NECABLATE pana acum
//     in drumul real. Reutilizate aici, nu redefinite.
// Ce lipsea, cerut explicit in ordin, si e nou in acest fisier:
//   1. Clasificarea ORIGINII unui esec (AKA/LIVE/EXECUTOR/PERMISIUNE/APLICATIE EXTERNA).
//   2. Decizia daca o reincercare e SIGURA (niciodata pentru ceva deja reusit o data, niciodata
//      pentru un efect posibil ireversibil nc confirmat).
// Pur, zero Context — testabil JUnit, consuma tipuri deja existente din ambele module.

enum class SurvivorFaultOrigin { AKA, EXECUTOR, PERMISSION, EXTERNAL_APP, UNKNOWN }

// Originea se decide din semnale DEJA produse de straturile existente — nicio sursa noua de
// date. Ordinea verificarilor conteaza: o permisiune lipsa sau o aplicatie straina explica orice
// alt simptom, deci se verifica INAINTE de a interpreta failure-ul insusi.
fun classifyFaultOrigin(
  failure: expo.modules.accessibility.InteractionFailureReason?,
  accessibilityAvailable: Boolean,
  foreignAppForeground: Boolean,
): SurvivorFaultOrigin = when {
  !accessibilityAvailable -> SurvivorFaultOrigin.PERMISSION
  foreignAppForeground -> SurvivorFaultOrigin.EXTERNAL_APP
  failure == null -> SurvivorFaultOrigin.UNKNOWN
  // Elementul asteptat (dupa o cunostinta AKA anterioara sau o eticheta cautata) nu mai exista pe
  // ecranul curent — fie interfata s-a schimbat, fie cunostinta AKA era deja gresita. In ambele
  // cazuri, raspunderea e a cunostintei, nu a executiei insesi.
  failure == expo.modules.accessibility.InteractionFailureReason.UI_NODE_NOT_FOUND -> SurvivorFaultOrigin.AKA
  // Apasarea insasi (tehnic acceptata sau nu) sau verificarea efectului — raspunderea EXECUTORULUI.
  failure == expo.modules.accessibility.InteractionFailureReason.UI_ACTION_REJECTED -> SurvivorFaultOrigin.EXECUTOR
  failure == expo.modules.accessibility.InteractionFailureReason.VERIFICATION_FAILED -> SurvivorFaultOrigin.EXECUTOR
  failure == expo.modules.accessibility.InteractionFailureReason.UNKNOWN -> SurvivorFaultOrigin.UNKNOWN
  else -> SurvivorFaultOrigin.UNKNOWN
}

// O reincercare AUTOMATA (fara ca LIVE sa fi decis explicit altceva) e SIGURA DOAR cand:
//  - actiunea NU a raportat deja succes o data in aceasta misiune (altfel s-ar duplica un efect —
//    exact ce cere ordinul: "preveni repetarea apelurilor, mesajelor, platilor");
//  - controlul insusi nu cere confirmare si nu e refuzat (ButtonSafetyClassifier ramane garda
//    REALA, neschimbata — asta doar respecta verdictul ei, nu il recalculeaza);
//  - esecul e de tipul EXECUTOR (apasare respinsa tehnic) — reincercarea unei apasari care n-a
//    ajuns sa produca niciun efect e sigura. UI_NODE_NOT_FOUND (origine AKA) NU e sigur de
//    reincercat orbeste — intai trebuie re-identificat controlul (vezi needsReidentification mai
//    jos), nu doar apasat din nou cu aceeasi eticheta/index. VERIFICATION_FAILED inseamna ca S-A
//    intamplat ceva (posibil ireversibil) — nu e sigur de presupus ca o reincercare n-ar dubla
//    acel efect. UNKNOWN (dovada insasi lipseste) nu se ghiceste niciodata ca sigur.
fun isSafeToRetry(
  failure: expo.modules.accessibility.InteractionFailureReason?,
  alreadySucceededThisMission: Boolean,
  safety: expo.modules.accessibility.ButtonSafetyClassifier.Verdict,
): Boolean {
  if (alreadySucceededThisMission) return false
  if (safety != expo.modules.accessibility.ButtonSafetyClassifier.Verdict.FREE) return false
  return failure == expo.modules.accessibility.InteractionFailureReason.UI_ACTION_REJECTED
}

// FIX_SURVIVOR_REIDENTIFY_FOREIGN_APP_1 (20.10.2026, cerinta explicita a Masterului dupa auditul
// rundei anterioare) — CAUZA REALA a unui defect ne-inca-dovedit-pe-dispozitiv dar prins prin
// citirea codului: versiunea veche lua decizia direct pe `failure` brut, deci un UI_NODE_NOT_FOUND
// cauzat de o APLICATIE STRAINA preluand ecranul (o notificare, exact cazul deja dovedit real in
// runda Calculator/S4) ar fi retrogradat GRESIT o cunostinta AKA inca VALABILA — ecranul/aplicatia
// nu erau cele asteptate, deci dovada "controlul nu exista" nu e despre acel control, ci despre
// faptul ca nu ne mai uitam la aplicatia corecta. Reparat: functia consuma acum ORIGINEA deja
// clasificata (care verifica deja `foreignAppForeground` INAINTEA oricarei interpretari a
// `failure`), nu rederiva propria conditie — o singura sursa de adevar, nu doua reguli care ar
// putea diverge.
fun needsReidentification(
  origin: SurvivorFaultOrigin,
  hadPriorVerifiedKnowledge: Boolean,
): Boolean = origin == SurvivorFaultOrigin.AKA && hadPriorVerifiedKnowledge

// FIX_SURVIVOR_UNUSED_IN_RUNTIME_1 (20.10.2026) — `isSafeToRetry` exista, era testat, dar NU era
// chemat de nicaieri in afara propriului test (verificat prin grep, cerinta explicita a
// Masterului: "existenta in fisiere si teste unitare nu reprezinta integrare in runtime"). Aceasta
// functie wrapper e EXACT ce ajunge acum sa fie chemat din `runAgentToolLocked` (BensonForegroundService.kt)
// la fiecare esec de press_button/tap_element — parametrii fixati reflecta cazul concret de acolo:
// evaluam DOAR daca o REINCERCARE OARBA, cu ACEEASI eticheta, ar fi sigura CHIAR ACUM (nu daca a
// reusit deja o data — acela e un gard SEPARAT, cel de duplicare, care foloseste r.ok direct).
// Punctul 2+3 din ordin: UNKNOWN si VERIFICATION_FAILED NU sunt "sigur de reincercat" DOAR pentru
// ca lipseste dovada clara de succes — un rezultat incert poate sa fi produs deja efectul.
fun isAutomaticRetryUnsafe(failure: expo.modules.accessibility.InteractionFailureReason?): Boolean =
  !isSafeToRetry(
    failure,
    alreadySucceededThisMission = false,
    safety = expo.modules.accessibility.ButtonSafetyClassifier.Verdict.FREE,
  )
