package expo.modules.foregroundservice

// SURVIVOR V0 — Mission Trace + Failure Classification + Verification Engine, primul flux: Waze.
// Pur, IZOLAT — nu e cablat inca in BensonForegroundService.kt. Reutilizeaza Mission/MissionStep/
// ExecutionOutcome din D0 — nu e un orchestrator nou, doar inregistrarea + clasificarea +
// verificarea drumului deja existent (tryNativeNavigate -> navigate_confirm ->
// handlePendingNavAnswer -> NAV_LAUNCH). Fluxul real de executie Waze NU se schimba prin acest
// fisier; cablarea (flag + apel din handlePendingNavAnswer) ramane blocata pe decizia privind
// LEGEA TACERII necomisa din BensonForegroundService.kt (vezi raportul anterior).

// Clasificare inchisa, derivata DIRECT din reason-urile deja logate in cod azi
// (navigate_no_staff / launch_failed / nav_confirm_no / nav_confirm_unresolved) + una noua:
// LAUNCH_UNVERIFIED — gaura recunoscuta chiar in comentariul existent de la handlePendingNavAnswer
// ("verificarea reala... ramane de facut cu AccessibilityService").
enum class NavigationFailureReason { NO_STAFF, LAUNCH_FAILED, USER_DECLINED, UNRESOLVED, LAUNCH_UNVERIFIED }

fun classifyNavigationFailure(rawReason: String): NavigationFailureReason? = when (rawReason) {
  "navigate_no_staff" -> NavigationFailureReason.NO_STAFF
  "launch_failed" -> NavigationFailureReason.LAUNCH_FAILED
  "nav_confirm_no" -> NavigationFailureReason.USER_DECLINED
  "nav_confirm_unresolved" -> NavigationFailureReason.UNRESOLVED
  "launch_unverified" -> NavigationFailureReason.LAUNCH_UNVERIFIED
  else -> null
}

// Mission Trace: cele trei puncte ale drumului EXISTENT, ca pasi inregistrati — confirmarea
// (deja exista), lansarea (deja exista) si verificarea (NOUA, inca neexecutata de nimic azi).
fun navigationMissionSteps(destination: String): List<MissionStep> = listOf(
  MissionStep("navigate_confirm", "TRAVEL", "navigate_confirm", listOf(DomainEntity.Destination(destination))),
  MissionStep("navigate_launch", "EXECUTION", "navigate_launch", emptyList(), dependsOn = listOf("navigate_confirm"), requiresConfirmation = true),
  MissionStep("navigate_verify", "EXECUTION", "navigate_verify", emptyList(), dependsOn = listOf("navigate_launch")),
)

fun newNavigationMission(missionId: String, destination: String): Mission =
  Mission(missionId, navigationMissionSteps(destination), MissionState.CREATED)

// Verification Engine — determinist, pur. Apelantul (la integrare) citeste prim-planul prin
// BensonAccessibilityService.currentForegroundPackageLive() (Context-dependent, NEATINS aici) si
// preda rezultatul aici; poll-ul repetat (nu un sleep fix) e acelasi tipar deja dovedit la
// YT_RESULT_WAIT (14.10) — aceasta functie decide doar verdictul dintr-o citire data, nu face ea
// insasi I/O sau asteptare.
fun classifyLaunchVerification(
  expectedPackages: Set<String>,
  observedForegroundPackage: String?,
  pollsAttempted: Int,
  maxPolls: Int,
): ExecutionOutcome = when {
  observedForegroundPackage != null && observedForegroundPackage in expectedPackages ->
    ExecutionOutcome.Success(verifiedBy = "foreground_package", evidence = observedForegroundPackage)
  pollsAttempted < maxPolls -> ExecutionOutcome.Unverified
  else -> ExecutionOutcome.Failed("launch_unverified")
}
