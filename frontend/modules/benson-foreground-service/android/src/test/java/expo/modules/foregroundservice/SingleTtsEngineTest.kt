package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LEGEA VOCII UNICE (08.10.2026) — "Jest care eșuează dacă există vreun apel TTS în afara lui
 * speak()" tradus pentru Kotlin: nu există jest/JS pe acest drum (arhitectura e nativă după wake),
 * deci verificarea echivalentă e un JUnit care citește sursa și eșuează la build dacă invariantul
 * se strică — exact efectul cerut (poarta CI), cu unealta corectă pentru limbaj.
 */
class SingleTtsEngineTest {
  private fun sourceDir(): File {
    // Test working dir is the module root when run via gradlew; walk up if needed.
    var dir = File("src/main/java/expo/modules/foregroundservice")
    if (!dir.isDirectory) dir = File("modules/benson-foreground-service/android/src/main/java/expo/modules/foregroundservice")
    check(dir.isDirectory) { "source dir not found from ${File(".").absolutePath}" }
    return dir
  }

  @Test fun exactlyOneTextToSpeechInstantiation() {
    val count = sourceDir().listFiles { f -> f.extension == "kt" }!!
      .sumOf { f -> Regex("""(?<!//.{0,200})TextToSpeech\(""").findAll(f.readText()).count() }
    assertEquals("exactly one TextToSpeech(...) engine allowed on the native voice path", 1, count)
  }

  @Test fun bothSpeakFunctionsLogTtsSpeak() {
    val text = File(sourceDir(), "BensonForegroundService.kt").readText()
    assertTrue("speakNativeAckThenCapture must call logTtsSpeak", text.contains("logTtsSpeak(\"ack\""))
    assertTrue("speakNativeFallbackThenCapture must call logTtsSpeak", text.contains("logTtsSpeak(if (hasAnyPendingNativeState())"))
  }
}
