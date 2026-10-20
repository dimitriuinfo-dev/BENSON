package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVerificationGateTest {

  @Test fun freshStateAllowsEndingTheTurn() {
    assertTrue(isTurnEndAllowedNow(AgentVerificationGateState()))
  }

  @Test fun tapElementIsClassifiedAsAction() {
    assertEquals(AgentStepKind.ACTION, agentStepKindFor(BensonAgentTools.TAP_ELEMENT))
  }

  // Semantic UI Resolver (19.10.2026) — press_button e tot o actiune ce poate schimba ecranul;
  // aceleasi garduri (verificare dupa actiune, compound-open) trebuie sa se aplice la fel.
  @Test fun pressButtonIsClassifiedAsActionToo() {
    assertEquals(AgentStepKind.ACTION, agentStepKindFor(BensonAgentTools.PRESS_BUTTON))
  }

  @Test fun observeScreenAndVerifyPlayingAreClassifiedAsObserve() {
    assertEquals(AgentStepKind.OBSERVE, agentStepKindFor(BensonAgentTools.OBSERVE_SCREEN))
    assertEquals(AgentStepKind.OBSERVE, agentStepKindFor(BensonAgentTools.VERIFY_PLAYING))
  }

  @Test fun openAppIsNotClassifiedAsActionRequiringThisGate() {
    // open_app isi face deja propria verificare SURVIVOR in tool (FIX_AGENT_OPEN_APP_HONESTY_1) -
    // un "say" imediat dupa o deschidere simpla nu trebuie blocat de acest gard.
    assertEquals(AgentStepKind.OTHER, agentStepKindFor(BensonAgentTools.OPEN_APP))
  }

  @Test fun turnEndIsBlockedRightAfterATapWithNoObserveYet() {
    val afterTap = advanceVerificationGate(AgentVerificationGateState(), 0, AgentStepKind.ACTION)
    assertFalse(isTurnEndAllowedNow(afterTap))
  }

  @Test fun turnEndIsAllowedAfterObserveFollowsTheTap() {
    var state = AgentVerificationGateState()
    state = advanceVerificationGate(state, 0, AgentStepKind.ACTION)
    state = advanceVerificationGate(state, 1, AgentStepKind.OBSERVE)
    assertTrue(isTurnEndAllowedNow(state))
  }

  @Test fun turnEndIsAllowedWhenVerifyPlayingFollowsTheTap() {
    // Drumul media deja existent ("apasa, apoi verify_playing") nu trebuie sa fie blocat de gard.
    var state = AgentVerificationGateState()
    state = advanceVerificationGate(state, 0, AgentStepKind.ACTION)
    state = advanceVerificationGate(state, 1, agentStepKindFor(BensonAgentTools.VERIFY_PLAYING))
    assertTrue(isTurnEndAllowedNow(state))
  }

  @Test fun anotherTapAfterAnObserveReblocksTheGate() {
    var state = AgentVerificationGateState()
    state = advanceVerificationGate(state, 0, AgentStepKind.ACTION)
    state = advanceVerificationGate(state, 1, AgentStepKind.OBSERVE)
    state = advanceVerificationGate(state, 2, AgentStepKind.ACTION) // a doua apasare (ex. "=")
    assertFalse(isTurnEndAllowedNow(state))
  }

  @Test fun observeBeforeAnyActionDoesNotPrematurelyUnblockFutureActions() {
    var state = AgentVerificationGateState()
    state = advanceVerificationGate(state, 0, AgentStepKind.OBSERVE) // observare initiala, fara actiune inca
    state = advanceVerificationGate(state, 1, AgentStepKind.ACTION)
    assertFalse(isTurnEndAllowedNow(state))
  }

  @Test fun otherToolsDoNotAffectGateState() {
    var state = AgentVerificationGateState()
    state = advanceVerificationGate(state, 0, AgentStepKind.ACTION)
    val afterOther = advanceVerificationGate(state, 1, agentStepKindFor(BensonAgentTools.SEARCH_IN_APP))
    assertEquals(state, afterOther)
    assertFalse(isTurnEndAllowedNow(afterOther))
  }

  // --- isCompoundOpenWithoutActionBlocked — FIX_AGENT_COMPOUND_OPEN_NO_ACTION_1, dovada reala:
  // "deschide Calculatorul si calculeaza 27x14" -> open_app reusit -> observe_screen -> "say" cu
  // rezultatul calculat de model, fara niciun tap_element. ---

  @Test fun compoundCommandWithOpenAppButNoTapIsBlocked() {
    assertTrue(isCompoundOpenWithoutActionBlocked(commandIsCompound = true, openAppSucceeded = true, lastActionStep = -1))
  }

  @Test fun compoundCommandIsAllowedOnceATapHappened() {
    assertFalse(isCompoundOpenWithoutActionBlocked(commandIsCompound = true, openAppSucceeded = true, lastActionStep = 2))
  }

  @Test fun simpleOpenOnlyCommandIsNeverBlockedByThisGate() {
    // "deschide Calculatorul" fara "si ..." nu cere nicio apasare — nu trebuie fortata una ghicita.
    assertFalse(isCompoundOpenWithoutActionBlocked(commandIsCompound = false, openAppSucceeded = true, lastActionStep = -1))
  }

  @Test fun compoundCommandThatNeverOpenedAnAppIsNotBlockedByThisGate() {
    // Gardul e despre "a deschis, dar n-a apasat" — daca open_app n-a reusit deloc, alta logica
    // (raspunsul normal al instrumentului) se ocupa de asta, nu acest gard.
    assertFalse(isCompoundOpenWithoutActionBlocked(commandIsCompound = true, openAppSucceeded = false, lastActionStep = -1))
  }

  // --- isTapAllowedNow — FIX_AGENT_TAP_WITHOUT_OBSERVE_1, dovada reala: dupa open_app, modelul a
  // cerut tap_element DIRECT, fara niciun observe_screen anterior in rulare (index ghicit). ---

  @Test fun tapIsBlockedBeforeAnyObserveEverHappened() {
    assertFalse(isTapAllowedNow(AgentVerificationGateState()))
  }

  @Test fun tapIsAllowedAfterAtLeastOneObserve() {
    val state = advanceVerificationGate(AgentVerificationGateState(), 0, AgentStepKind.OBSERVE)
    assertTrue(isTapAllowedNow(state))
  }

  @Test fun tapRemainsAllowedAfterObserveEvenAcrossLaterActions() {
    var state = advanceVerificationGate(AgentVerificationGateState(), 0, AgentStepKind.OBSERVE)
    state = advanceVerificationGate(state, 1, AgentStepKind.ACTION)
    assertTrue(isTapAllowedNow(state)) // a observat macar o data - taps ulterioare raman permise
  }

  @Test fun advanceVerificationGateMarksHasObservedEvenBeforeAnyAction() {
    // Comportament schimbat deliberat fata de lastObserveAfterActionStep (care ramane -1 pana la
    // prima actiune) — hasObservedAtLeastOnce e un flag separat, adevarat din prima observare.
    val state = advanceVerificationGate(AgentVerificationGateState(), 0, AgentStepKind.OBSERVE)
    assertTrue(state.hasObservedAtLeastOnce)
    assertEquals(-1, state.lastObserveAfterActionStep)
  }

  // --- claimedNumberMissingFromLastObservation — FIX_AGENT_CLAIMED_NUMBER_NOT_OBSERVED_1, dovada
  // reala: "Master, rezultatul este 126." rostit cand ultima observare reala arata "8.951". ---

  @Test fun claimedNumberNotInLastObservationIsBlockedForCompoundCommand() {
    assertTrue(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Master, rezultatul este 126.",
        lastObservedScreenText = "[16] button \"8.951\"",
      ),
    )
  }

  @Test fun claimedNumberPresentInLastObservationIsAllowed() {
    assertFalse(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Rezultatul este 378.",
        lastObservedScreenText = "[16] text \"378\"",
      ),
    )
  }

  @Test fun simpleNonCompoundCommandIsNeverBlockedByThisGate() {
    // Conversatie libera ("cati ani a murit Stefan cel Mare?") poate rosti legitim numere care nu
    // vin de pe ecranul curent — gardul se aplica DOAR comenzilor deja marcate compuse.
    assertFalse(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = false,
        finalText = "A murit la 70 de ani.",
        lastObservedScreenText = "[0] text \"altceva\"",
      ),
    )
  }

  @Test fun noObservationYetMeansNothingToContradict() {
    assertFalse(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Rezultatul este 378.",
        lastObservedScreenText = null,
      ),
    )
  }

  // Cauza reala a FIX_CLAIMED_NUMBER_BOUNDS_FALSE_NEGATIVE_1: "378" aparea DOAR in coordonatele
  // unui element ("40,378,500,980"), nu in continutul lui real - gardul trebuie sa tot blocheze.
  @Test fun claimedNumberFoundOnlyInBoundsCoordinatesIsStillBlocked() {
    assertTrue(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Rezultatul este 378.",
        lastObservedScreenText = "[16] button \"%\" 40,378,500,980",
      ),
    )
  }

  @Test fun claimedNumberInRealLabelNotJustBoundsIsAllowed() {
    assertFalse(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Rezultatul este 378.",
        lastObservedScreenText = "[16] text \"378\" 40,900,500,980",
      ),
    )
  }

  @Test fun responseWithNoNumbersIsNeverBlockedByThisGate() {
    assertFalse(
      claimedNumberMissingFromLastObservation(
        commandIsCompound = true,
        finalText = "Nu pot confirma rezultatul.",
        lastObservedScreenText = "[0] text \"ceva\"",
      ),
    )
  }
}
