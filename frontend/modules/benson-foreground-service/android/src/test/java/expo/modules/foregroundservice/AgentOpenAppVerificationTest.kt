package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOpenAppVerificationTest {

  @Test fun nullResponseIsNotSuccess() {
    assertFalse(classifyOpenAppResponseText(null))
  }

  @Test fun successResponseIsRecognized() {
    assertTrue(classifyOpenAppResponseText("Deschid Calculatorul."))
  }

  @Test fun launchFailedTextResponseIsNotSuccess() {
    // Exact textul pe care tryNativeOpenApp il intoarce la esec de lansare — inainte de fix,
    // `resp != null` il trata gresit ca succes.
    assertFalse(classifyOpenAppResponseText("Am găsit Calculatorul, dar n-am putut să o deschid."))
  }

  @Test fun carModeRefusalTextResponseIsNotSuccess() {
    assertFalse(classifyOpenAppResponseText("Nu pornesc video cât conduci."))
  }

  @Test fun blankResponseIsNotSuccess() {
    assertFalse(classifyOpenAppResponseText(""))
  }
}
