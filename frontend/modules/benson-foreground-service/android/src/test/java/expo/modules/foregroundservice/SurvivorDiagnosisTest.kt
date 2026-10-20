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

  // --- needsReidentification ---

  @Test fun elementNotFoundWithPriorVerifiedKnowledgeSignalsReidentification() {
    assertTrue(needsReidentification(InteractionFailureReason.UI_NODE_NOT_FOUND, hadPriorVerifiedKnowledge = true))
  }

  @Test fun elementNotFoundWithoutPriorVerifiedKnowledgeIsJustAnOrdinaryDiscoveryMiss() {
    // Nimic de reidentificat - nu exista nicio dovada veche care sa fi devenit falsa.
    assertFalse(needsReidentification(InteractionFailureReason.UI_NODE_NOT_FOUND, hadPriorVerifiedKnowledge = false))
  }

  @Test fun otherFailureReasonsNeverTriggerReidentificationEvenWithPriorKnowledge() {
    assertFalse(needsReidentification(InteractionFailureReason.VERIFICATION_FAILED, hadPriorVerifiedKnowledge = true))
    assertFalse(needsReidentification(InteractionFailureReason.UI_ACTION_REJECTED, hadPriorVerifiedKnowledge = true))
    assertFalse(needsReidentification(null, hadPriorVerifiedKnowledge = true))
  }
}
