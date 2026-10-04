package expo.modules.car

// RUNDA CAR-3a (2026-10-04, user-directed) — maps BensonForegroundService's EXISTING bubble
// update signal (state text + terminal flag — the same two values every tryNative*/handlePending*
// function in that file already produces for the overlay bubble) onto the six car-screen states
// the user asked for, without touching every one of those call sites individually.
//
// LISTENING: the exact "ASCULT" text shown while CMD_CAPTURE/CONFIRMATION_STT is live.
// THINKING: a non-terminal update that ISN'T "ASCULT" — BENSON is asking something / mid-flow
//   (a disambiguation list, a WA confirm question, the message-draft prompt...).
// DONE / ERROR: a terminal update, split by whether the response text reads as a failure
//   ("Nu ...", "Încă nu știu...", "Nu găsesc...", "Nu am...").
// EXECUTING: NOT separately distinguishable from THINKING with today's existing signals — there is
//   no distinct "a multi-step action is now running" bubble call anywhere in the codebase (the WA
//   call/message automation jumps straight from the confirm question to the terminal result). Left
//   as a real enum value for the screen/template code to render identically to THINKING for now;
//   a true EXECUTING signal would need a new call site in BensonForegroundService, out of scope for
//   this skeleton round.
// IDLE: not derived from a bubble update at all — set explicitly when a session closes with
//   nothing shown (see BensonCarScreen.setIdle(), called from closeSession).
enum class CarScreenState { IDLE, LISTENING, THINKING, EXECUTING, DONE, ERROR }

object CarStateMapper {
  private val ERROR_PREFIXES = listOf("nu ", "incă nu știu", "încă nu știu", "nu găsesc", "nu am", "n-am")

  fun fromBubbleUpdate(stateText: String, terminal: Boolean): CarScreenState {
    if (!terminal) return if (stateText == "ASCULT") CarScreenState.LISTENING else CarScreenState.THINKING
    val lower = stateText.trim().lowercase()
    return if (ERROR_PREFIXES.any { lower.startsWith(it) }) CarScreenState.ERROR else CarScreenState.DONE
  }
}
