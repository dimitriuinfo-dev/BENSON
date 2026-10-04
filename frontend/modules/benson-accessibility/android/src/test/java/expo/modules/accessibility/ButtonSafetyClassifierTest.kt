package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonSafetyClassifierTest {
  @Test fun freeForPlay() =
    assertEquals(ButtonSafetyClassifier.Verdict.FREE, ButtonSafetyClassifier.classify("Play", "", ""))

  @Test fun confirmForSend() =
    assertEquals(ButtonSafetyClassifier.Verdict.CONFIRM, ButtonSafetyClassifier.classify("Trimite", "", ""))

  @Test fun confirmForDeleteEnglish() =
    assertEquals(ButtonSafetyClassifier.Verdict.CONFIRM, ButtonSafetyClassifier.classify("Delete", "", ""))

  // The exact case the user named: a "Cumpără" button must refuse even if BENSON is told to press it.
  @Test fun refuseForCumparaEvenWhenToldDirectly() =
    assertEquals(ButtonSafetyClassifier.Verdict.REFUSE, ButtonSafetyClassifier.classify("Cumpără", "", ""))

  @Test fun refuseForGermanBezahlen() =
    assertEquals(ButtonSafetyClassifier.Verdict.REFUSE, ButtonSafetyClassifier.classify("", "Jetzt bezahlen", ""))

  @Test fun refuseWinsOverConfirmOnSameLabel() =
    // "trimite" (CONFIRM) + "comanda" (REFUSE) on the same label — REFUSE must win.
    assertEquals(ButtonSafetyClassifier.Verdict.REFUSE, ButtonSafetyClassifier.classify("Trimite comanda", "", ""))

  @Test fun refuseForAnyButtonInBlockedPackage() =
    assertEquals(ButtonSafetyClassifier.Verdict.REFUSE, ButtonSafetyClassifier.classify("OK", "", "com.paypal.android.p2pmobile"))

  @Test fun freeForNeutralLabelInOrdinaryApp() =
    assertEquals(ButtonSafetyClassifier.Verdict.FREE, ButtonSafetyClassifier.classify("OK", "", "com.whatsapp"))
}
