package expo.modules.accessibility

// RUNDA C-1 (2026-10-04, user-directed) — "unde eram" înainte de o acțiune WhatsApp (apel/video/
// mesaj) decide ce face BENSON după: readuce aplicația anterioară, apasă Acasă, sau nu face nimic
// dacă utilizatorul a interacționat cu ecranul între timp. Pur, zero Context, JUnit-tested
// (CallCleanupDecisionTest). Execuția reală (launch intent REORDER_TO_FRONT / GLOBAL_ACTION_HOME)
// rămâne în BensonAccessibilityService.
object CallCleanupDecision {
  enum class Action { RESTORE, HOME, SKIP }

  fun decide(
    whatsappStillForeground: Boolean,
    interactedSinceEnd: Boolean,
    prevPackage: String?,
    launcherPackage: String?,
    ownPackage: String,
  ): Action = when {
    !whatsappStillForeground -> Action.SKIP
    interactedSinceEnd -> Action.SKIP
    prevPackage == null || prevPackage == launcherPackage || prevPackage == ownPackage -> Action.HOME
    else -> Action.RESTORE
  }
}
