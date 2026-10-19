package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Testeaza DOAR parseContent() — nu are nevoie de retea/cheie reala. converse() (apelul HTTP
// real) NU e invocat in aceasta sesiune (fara autorizare de cost), deci ramane netestat aici.
class OpenAiModelProviderTest {

  private val provider = OpenAiModelProvider(apiKey = "unused-in-this-test")

  @Test fun parsesChatReply() {
    val output = provider.parseContent("""{"type":"chat","reply":"Salut!","language":"ro"}""", emptySet())
    assertTrue(output is BrainOutput.Reply)
    assertEquals("Salut!", (output as BrainOutput.Reply).text)
  }

  @Test fun parsesClarifyQuestion() {
    val output = provider.parseContent("""{"type":"clarify","question":"Unde anume?","language":"ro"}""", emptySet())
    assertTrue(output is BrainOutput.Clarify)
  }

  @Test fun parsesKnownCommandAsActionRequest() {
    val output = provider.parseContent("""{"type":"command","action":"NAVIGATE","language":"ro"}""", setOf("NAVIGATE"))
    assertTrue(output is BrainOutput.ActionRequest)
    assertEquals("NAVIGATE", (output as BrainOutput.ActionRequest).action)
  }

  @Test fun unknownActionBecomesFailureNeverABlindActionRequest() {
    val output = provider.parseContent("""{"type":"command","action":"INVENTED","language":"ro"}""", setOf("NAVIGATE"))
    assertTrue(output is BrainOutput.Failure)
  }

  @Test fun malformedJsonFallsBackToPlainReplyNeverCrashes() {
    val output = provider.parseContent("nu e json deloc", emptySet())
    assertTrue(output is BrainOutput.Reply)
  }

  @Test fun providerIdNamesTheConfiguredModel() {
    assertEquals("openai:gpt-5-nano", provider.providerId)
  }
}
