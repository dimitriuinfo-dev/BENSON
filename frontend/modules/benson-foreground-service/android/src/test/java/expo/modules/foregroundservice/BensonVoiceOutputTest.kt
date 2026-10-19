package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BensonVoiceOutputTest {

  private fun client(available: Boolean) = object : OpenAiVoiceOutputClient {
    override fun isAvailable() = available
  }

  @Test fun textForRepliedIsTheReplyItself() {
    assertEquals("Salut!", BensonVoiceOutput.textFor(MissionResult.Replied("Salut!")))
  }

  @Test fun textForClarifyIsTheQuestion() {
    assertEquals("Unde anume?", BensonVoiceOutput.textFor(MissionResult.Clarify("Unde anume?")))
  }

  @Test fun textForExecutedSuccessIsPositive() {
    val outcome = ExecutionOutcome.Success("foreground_package", "com.waze")
    assertEquals("Gata.", BensonVoiceOutput.textFor(MissionResult.Executed("m1", outcome)))
  }

  @Test fun textForExecutedFailureIsHonestNotPositive() {
    val outcome = ExecutionOutcome.Failed("launch_unverified")
    assertEquals("Nu am reușit.", BensonVoiceOutput.textFor(MissionResult.Executed("m1", outcome)))
  }

  @Test fun textForExecutedUnverifiedNeverClaimsSuccess() {
    assertEquals("Nu pot confirma rezultatul.", BensonVoiceOutput.textFor(MissionResult.Executed("m1", ExecutionOutcome.Unverified)))
  }

  @Test fun speaksWithConfiguredVoiceWhenClientAvailable() {
    val decision = BensonVoiceOutput.decide(MissionResult.Replied("Salut!"), "m1", client(true))
    assertTrue(decision is VoiceOutputDecision.Speak)
  }

  @Test fun fallsBackToTextWhenClientUnavailableNeverSubstitutesAnotherVoice() {
    val decision = BensonVoiceOutput.decide(MissionResult.Replied("Salut!"), "m1", client(false))
    assertTrue(decision is VoiceOutputDecision.TextOnly)
    assertEquals("voice_service_unavailable", (decision as VoiceOutputDecision.TextOnly).reason)
  }
}
