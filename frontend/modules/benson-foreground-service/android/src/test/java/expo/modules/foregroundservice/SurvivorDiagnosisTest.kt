package expo.modules.foregroundservice

import expo.modules.accessibility.ButtonSafetyClassifier
import expo.modules.accessibility.InteractionFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// SURVIVOR (20.10.2026, etapa 3) — teste pure pentru clasificarea originii unui esec, decizia de
// reincercare sigura si semnalul de reidentificare catre AKA. Nicio cunostinta despre vreo
// aplicatie anume — opereaza strict pe tipurile deja existente (InteractionFailureReason,
// ButtonSafetyClassifier.Verdict).
class SurvivorDiagnosisTest {

  // --- classifyFaultOrigin ---

  @Test fun missingAccessibilityIsAlwaysAPermissionIssueRegardlessOfFailure() {
    assertEquals(
      SurvivorFaultOrigin.PERMISSION,
      classifyFaultOrigin(InteractionFailureReason.UI_NODE_NOT_FOUND, accessibilityAvailable = false, foreignAppForeground = false),
    )
  }

  @Test fun foreignAppForegroundTakesPriorityOverTheFailureReasonItself() {
    assertEquals(
      SurvivorFaultOrigin.EXTERNAL_APP,
      classifyFaultOrigin(InteractionFailureReason.VERIFICATION_FAILED, accessibilityAvailable = true, foreignAppForeground = true),
    )
  }

  @Test fun elementNotFoundIsAttributedToAkaNotToTheExecutor() {
    // Cunostinta despre UNDE e controlul vine de la AKA - daca nu exista, raspunderea e acolo.
    assertEquals(
      SurvivorFaultOrigin.AKA,
      classifyFaultOrigin(InteractionFailureReason.UI_NODE_NOT_FOUND, accessibilityAvailable = true, foreignAppForeground = false),
    )
  }

  @Test fun rejectedActionAndFailedVerificationAreAttributedToTheExecutor() {
    assertEquals(SurvivorFaultOrigin.EXECUTOR, classifyFaultOrigin(InteractionFailureReason.UI_ACTION_REJECTED, true, false))
    assertEquals(SurvivorFaultOrigin.EXECUTOR, classifyFaultOrigin(InteractionFailureReason.VERIFICATION_FAILED, true, false))
  }

  @Test fun noFailureAtAllClassifiesAsUnknownNotAsAnyParticularOrigin() {
    assertEquals(SurvivorFaultOrigin.UNKNOWN, classifyFaultOrigin(null, true, false))
  }

  // --- isSafeToRetry ---

  @Test fun neverRetryAnActionThatAlreadySucceededOnceRegardlessOfAnythingElse() {
    assertFalse(isSafeToRetry(InteractionFailureReason.UI_ACTION_REJECTED, alreadySucceededThisMission = true, safety = ButtonSafetyClassifier.Verdict.FREE))
  }

  @Test fun neverRetryAControlThatRequiresConfirmationOrIsRefused() {
    assertFalse(isSafeToRetry(InteractionFailureReason.UI_ACTION_REJECTED, false, ButtonSafetyClassifier.Verdict.CONFIRM))
    assertFalse(isSafeToRetry(InteractionFailureReason.UI_ACTION_REJECTED, false, ButtonSafetyClassifier.Verdict.REFUSE))
  }

  @Test fun rejectedActionOnAFreeControlIsSafeToRetryBecauseNothingHappenedYet() {
    assertTrue(isSafeToRetry(InteractionFailureReason.UI_ACTION_REJECTED, false, ButtonSafetyClassifier.Verdict.FREE))
  }

  @Test fun elementNotFoundIsNeverSafeToBlindlyRetryItNeedsReidentificationFirst() {
    assertFalse(isSafeToRetry(InteractionFailureReason.UI_NODE_NOT_FOUND, false, ButtonSafetyClassifier.Verdict.FREE))
  }

  @Test fun verificationFailedIsNeverSafeToRetryBlindlySomethingMayHaveAlreadyHappened() {
    assertFalse(isSafeToRetry(InteractionFailureReason.VERIFICATION_FAILED, false, ButtonSafetyClassifier.Verdict.FREE))
  }

  @Test fun unknownEvidenceIsNeverGuessedAsSafe() {
    assertFalse(isSafeToRetry(InteractionFailureReason.UNKNOWN, false, ButtonSafetyClassifier.Verdict.FREE))
  }

  // --- needsReidentification — FIX_SURVIVOR_REIDENTIFY_FOREIGN_APP_1: consuma ORIGINEA deja
  // clasificata (nu failure-ul brut), ca sa respecte prioritatea aplicatie-straina/permisiune
  // fara sa dubleze acea logica. ---

  @Test fun akaOriginWithPriorVerifiedKnowledgeSignalsReidentification() {
    assertTrue(needsReidentification(SurvivorFaultOrigin.AKA, hadPriorVerifiedKnowledge = true))
  }

  @Test fun akaOriginWithoutPriorVerifiedKnowledgeIsJustAnOrdinaryDiscoveryMiss() {
    // Nimic de reidentificat - nu exista nicio dovada veche care sa fi devenit falsa.
    assertFalse(needsReidentification(SurvivorFaultOrigin.AKA, hadPriorVerifiedKnowledge = false))
  }

  @Test fun otherOriginsNeverTriggerReidentificationEvenWithPriorKnowledge() {
    assertFalse(needsReidentification(SurvivorFaultOrigin.EXECUTOR, hadPriorVerifiedKnowledge = true))
    assertFalse(needsReidentification(SurvivorFaultOrigin.PERMISSION, hadPriorVerifiedKnowledge = true))
    assertFalse(needsReidentification(SurvivorFaultOrigin.UNKNOWN, hadPriorVerifiedKnowledge = true))
  }

  // FIX_SURVIVOR_REIDENTIFY_FOREIGN_APP_1 — cerinta explicita a Masterului, testata direct: un
  // UI_NODE_NOT_FOUND cauzat de o aplicatie STRAINA in prim-plan NU are voie sa retrogradeze o
  // cunostinta AKA VERIFIED — proba ca `classifyFaultOrigin` (acum chemat in motor, nu doar
  // testat izolat) schimba efectiv decizia finala, nu doar exista in paralel cu ea.
  @Test fun foreignAppForegroundNeverTriggersReidentificationEvenWithUiNodeNotFoundAndPriorKnowledge() {
    val origin = classifyFaultOrigin(InteractionFailureReason.UI_NODE_NOT_FOUND, accessibilityAvailable = true, foreignAppForeground = true)
    assertEquals(SurvivorFaultOrigin.EXTERNAL_APP, origin)
    assertFalse(needsReidentification(origin, hadPriorVerifiedKnowledge = true))
  }

  @Test fun genuineUiNodeNotFoundOnTheRightAppStillTriggersReidentification() {
    // Control pozitiv: fara aplicatie straina, acelasi esec TREBUIE sa declanseze reidentificarea.
    val origin = classifyFaultOrigin(InteractionFailureReason.UI_NODE_NOT_FOUND, accessibilityAvailable = true, foreignAppForeground = false)
    assertEquals(SurvivorFaultOrigin.AKA, origin)
    assertTrue(needsReidentification(origin, hadPriorVerifiedKnowledge = true))
  }

  // --- isAutomaticRetryUnsafe — FIX_SURVIVOR_UNUSED_IN_RUNTIME_1: wrapper-ul EFECTIV chemat din
  // runAgentToolLocked la fiecare esec de press_button/tap_element (nu doar testat izolat). ---

  @Test fun rejectedActionIsSafeToAutomaticallyRetryNothingHappenedYet() {
    assertFalse(isAutomaticRetryUnsafe(InteractionFailureReason.UI_ACTION_REJECTED))
  }

  @Test fun uncertainOrPossiblyAppliedOutcomesAreNeverSafeToAutomaticallyRetry() {
    // Punctele 2+3 ale ordinului: un rezultat INCERT poate sa fi produs deja efectul.
    assertTrue(isAutomaticRetryUnsafe(InteractionFailureReason.VERIFICATION_FAILED))
    assertTrue(isAutomaticRetryUnsafe(InteractionFailureReason.UNKNOWN))
    assertTrue(isAutomaticRetryUnsafe(InteractionFailureReason.UI_NODE_NOT_FOUND))
    assertTrue(isAutomaticRetryUnsafe(null))
  }
}
