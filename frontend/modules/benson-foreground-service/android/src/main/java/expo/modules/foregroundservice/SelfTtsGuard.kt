package expo.modules.foregroundservice

// FIX_WAKE_SELF_ECHO_GUARD_1 (2026-10-03, user-directed) — device-proven cause of the
// WAKE_REARM ok=false incident at 11:19:39: HEED got re-armed (via a "js_release" mic-owner
// transition, triggered by the overlay bubble's own brief foreground flicker) WHILE the native TTS
// ack ("Da, Master.") was still playing. HEED's mic picked up the tail of its own speech —
// HEED_DETECTED score=0.948, Deepgram verify transcript="master". Zero Context dependency, so this
// has a JUnit test (SelfTtsGuardTest) independent of the Service.
object SelfTtsGuard {
  const val GUARD_MS = 1_000L

  fun isWithinGuard(nowMs: Long, ttsSpeakingSince: Long, ttsLastDoneAt: Long, guardMs: Long = GUARD_MS): Boolean {
    if (ttsSpeakingSince != 0L) return true
    if (ttsLastDoneAt == 0L) return false
    return (nowMs - ttsLastDoneAt) < guardMs
  }
}
