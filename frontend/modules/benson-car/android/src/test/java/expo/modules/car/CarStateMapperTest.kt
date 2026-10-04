package expo.modules.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarStateMapperTest {
  @Test fun ascultMeansListening() =
    assertEquals(CarScreenState.LISTENING, CarStateMapper.fromBubbleUpdate("ASCULT", terminal = false))

  @Test fun nonTerminalQuestionMeansThinking() =
    assertEquals(CarScreenState.THINKING, CarStateMapper.fromBubbleUpdate("Video cu Hannah?", terminal = false))

  @Test fun terminalSuccessMeansDone() =
    assertEquals(CarScreenState.DONE, CarStateMapper.fromBubbleUpdate("Deschid WhatsApp.", terminal = true))

  @Test fun terminalFailureMeansError() =
    assertEquals(CarScreenState.ERROR, CarStateMapper.fromBubbleUpdate("Nu am putut apăsa.", terminal = true))

  @Test fun unknownCommandMeansError() =
    assertEquals(CarScreenState.ERROR, CarStateMapper.fromBubbleUpdate("Încă nu știu să fac asta.", terminal = true))

  @Test fun notFoundMeansError() =
    assertEquals(CarScreenState.ERROR, CarStateMapper.fromBubbleUpdate("Nu găsesc Magic FM.", terminal = true))
}
