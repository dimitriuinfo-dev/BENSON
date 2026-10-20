package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// SURVIVOR/EXECUTOR — teste pentru clasificatorul generic de eșec al unei interacțiuni, pe cele
// TREI straturi distincte (S4, 19.10.2026): apăsare acceptată / schimbare observată / efect corect
// verificat, cu elementul FOCALIZAT ca semnal PRIORITAR (tare) și schimbarea generală a ecranului
// ca semnal de REZERVĂ (slab), folosit doar când nu există niciun element focalizat de comparat.
// Cauza reală a reordonării: DEVICE_FAIL misiunea Calculator, a doua rulare — o apăsare reală,
// corectă (focused: "" -> "1") a fost raportată totuși VERIFICATION_FAILED pentru că semnalul slab
// (overall) nu surprinsese schimbarea, iar vechea ordine îl verifica PRIMUL.
class InteractionVerificationTest {

  private fun evidence(
    elementFound: Boolean = true,
    actionAccepted: Boolean = true,
    screenReobserved: Boolean = true,
    overallScreenChanged: Boolean = true,
    focusedContentChanged: Boolean? = null,
  ) = InteractionEvidence(elementFound, actionAccepted, screenReobserved, overallScreenChanged, focusedContentChanged)

  @Test fun elementNotFoundIsClassifiedCorrectly() {
    assertEquals(InteractionFailureReason.UI_NODE_NOT_FOUND, classifyInteractionOutcome(evidence(elementFound = false)))
  }

  @Test fun elementNotFoundTakesPriorityOverOtherFields() {
    assertEquals(
      InteractionFailureReason.UI_NODE_NOT_FOUND,
      classifyInteractionOutcome(evidence(elementFound = false, actionAccepted = true, overallScreenChanged = true, focusedContentChanged = true)),
    )
  }

  @Test fun actionRejectedWhenElementFoundButClickFails() {
    assertEquals(InteractionFailureReason.UI_ACTION_REJECTED, classifyInteractionOutcome(evidence(actionAccepted = false)))
  }

  @Test fun unknownWhenScreenCannotBeReobservedAfterAction() {
    assertEquals(InteractionFailureReason.UNKNOWN, classifyInteractionOutcome(evidence(screenReobserved = false)))
  }

  // --- Fara element focalizat de comparat (focusedContentChanged = null) - semnalul slab decide ---

  @Test fun fallsBackToOverallChangeWhenNoFocusedElementToCompare() {
    assertEquals(
      InteractionFailureReason.VERIFICATION_FAILED,
      classifyInteractionOutcome(evidence(overallScreenChanged = false, focusedContentChanged = null)),
    )
  }

  @Test fun successOnOverallChangeWhenNoFocusedElementToCompare() {
    assertNull(classifyInteractionOutcome(evidence(overallScreenChanged = true, focusedContentChanged = null)))
  }

  // --- Element focalizat disponibil - semnalul TARE decide, INDIFERENT de semnalul slab ---

  // Cazul real, dovedit pe dispozitiv: focused "" -> "1" (schimbare reala, corecta), dar
  // overallScreenChanged=false (semnalul slab a ratat-o). Trebuie sa fie SUCCES, nu esec.
  @Test fun focusedChangeMeansSuccessEvenWhenOverallSignalMissedIt() {
    assertNull(classifyInteractionOutcome(evidence(overallScreenChanged = false, focusedContentChanged = true)))
  }

  // Si inversul: overall a "vazut" o schimbare (poate irelevanta, alta parte a ecranului), dar
  // elementul focalizat NU s-a schimbat - tot esec, semnalul tare decide.
  @Test fun focusedUnchangedMeansFailureEvenWhenOverallSignalSawSomeChange() {
    assertEquals(
      InteractionFailureReason.VERIFICATION_FAILED,
      classifyInteractionOutcome(evidence(overallScreenChanged = true, focusedContentChanged = false)),
    )
  }

  @Test fun successWhenBothSignalsAgreeSomethingChanged() {
    assertNull(classifyInteractionOutcome(evidence(overallScreenChanged = true, focusedContentChanged = true)))
  }

  @Test fun failureWhenBothSignalsAgreeNothingChanged() {
    assertEquals(
      InteractionFailureReason.VERIFICATION_FAILED,
      classifyInteractionOutcome(evidence(overallScreenChanged = false, focusedContentChanged = false)),
    )
  }

  // --- isForeignAppForeground — S4 retest, dovada reala: o notificare eBay Kleinanzeigen a adus
  // MessagesActivity in prim-plan in timp ce agentul astepta raspunsul OpenAI intre doi pasi. ---

  @Test fun sameAppIsNotForeign() {
    assertEquals(false, isForeignAppForeground("com.oneplus.calculator", "com.oneplus.calculator"))
  }

  @Test fun differentAppIsForeign() {
    assertEquals(true, isForeignAppForeground("com.oneplus.calculator", "com.ebay.kleinanzeigen"))
  }

  @Test fun blankPackagesAreNeverClassifiedAsForeign() {
    // Fara un nume de pachet valid de ambele parti, nu se poate afirma nimic - nu se inventeaza.
    assertEquals(false, isForeignAppForeground("", "com.ebay.kleinanzeigen"))
    assertEquals(false, isForeignAppForeground("com.oneplus.calculator", ""))
  }
}
