package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastPathCalculatorTest {

  @Test fun parsesSimpleAdditionCommand() {
    assertEquals("9+9=", parseArithmeticExpression("calculează 9+9"))
  }

  @Test fun parsesCatEVariant() {
    assertEquals("27*14=", parseArithmeticExpression("cât e 27 ori 14"))
  }

  @Test fun parsesCatFaceVariant() {
    assertEquals("5+3=", parseArithmeticExpression("cât face 5 plus 3"))
  }

  @Test fun acceptsExpressionAlreadyEndingInEquals() {
    assertEquals("9+9=", parseArithmeticExpression("calculează 9+9="))
  }

  @Test fun rejectsUnrelatedCommands() {
    assertNull(parseArithmeticExpression("deschide calculatorul"))
    assertNull(parseArithmeticExpression("caută lămpi pe Spotify"))
  }

  @Test fun rejectsExpressionsWithUnknownCharactersLikeParenthesesOrPercent() {
    assertNull(parseArithmeticExpression("calculează (9+9)%"))
  }

  @Test fun rejectsTrailingOperatorWithNoRightOperand() {
    assertNull(parseArithmeticExpression("calculează 9+"))
  }

  @Test fun rejectsExpressionWithNoDigitsAtAll() {
    assertNull(parseArithmeticExpression("calculează +="))
  }

  @Test fun buttonStepsStartWithClearThenEachCharacterInOrder() {
    val steps = arithmeticExpressionToButtonSteps("9+9=")!!
    assertEquals(
      listOf("löschen", "9", "Hinzufügen", "9", "Ist gleich"),
      steps.map { it.label },
    )
  }

  @Test fun extractsTheRealDisplayedNumberFromAnObserveScreenDigest() {
    // Format real al digest-ului (dovada device 21.10.2026, dupa "9+9="): `[5] text "18"`.
    val digest = "[0] button \"Wissenschaftlich\" 1,1,1,1\n[5] text \"18\" 0,562,1080,839\n"
    assertEquals("18", extractCalculatorDisplayValue(digest))
  }

  @Test fun returnsNullWhenNothingNumericIsOnScreen() {
    val digest = "[0] button \"Wissenschaftlich\" 1,1,1,1\n"
    assertNull(extractCalculatorDisplayValue(digest))
  }

  @Test fun buttonStepsMapAllFourOperatorsToRealOnScreenLabels() {
    // Etichetele exacte vazute pe ecranul Calculator-ului OnePlus, 21.10.2026 (dovada device).
    assertEquals(listOf("löschen", "Subtrahieren", "Ist gleich"), arithmeticExpressionToButtonSteps("-=")!!.map { it.label })
    assertEquals(listOf("löschen", "Multiplizieren", "Ist gleich"), arithmeticExpressionToButtonSteps("*=")!!.map { it.label })
    assertEquals(listOf("löschen", "Teilen", "Ist gleich"), arithmeticExpressionToButtonSteps("/=")!!.map { it.label })
  }
}
