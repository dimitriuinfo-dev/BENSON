package expo.modules.foregroundservice

import android.content.Intent
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig

/**
 * NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19, device-proven) — a live wake-word event depends on
 * the JS thread already being alive and pumping messages at the exact instant it fires
 * (onWakeWordDetected / the heartbeat-poll fallback in app/index.tsx). Device-confirmed twice on
 * 2026-09-19, with battery optimization, Battery Saver and the OxygenOS performance mode all
 * already ruled out: the JS thread went completely silent (zero mqt_v_js log lines, zero
 * WAKE_POKE_JS_RECEIVED) for 14+ minutes while backgrounded, so BOTH the live event and the
 * heartbeat-poll fallback went unconsumed — WAKE_HANDOFF_RECOVER fired at 5s and the command was
 * lost with zero trace, while the native wake/STT pipeline worked perfectly the whole time.
 *
 * This is React Native's own HeadlessJsTaskService (RN 0.81, verified compatible with the New
 * Architecture/Bridgeless mode this app already runs — it branches on
 * ReactNativeNewArchitectureFeatureFlags.enableBridgelessArchitecture() internally) — the
 * documented mechanism for exactly this class of problem. Started ONLY from
 * BensonForegroundService's EXISTING WakeHandoffWatchdog timeout (the same 5s point that already
 * detects non-consumption) — no new detection path, no new timer, no polling.
 *
 * The task takes no custom params: it calls the EXISTING takePendingWakeCommand() (atomic
 * read+clear — already the single dedup point shared by the live-event and heartbeat-poll paths,
 * see BensonForegroundService.takePendingWakeCommand()), so the same command can never be
 * processed twice no matter which of the three paths ends up claiming it first.
 */
class BensonWakeHeadlessTaskService : HeadlessJsTaskService() {
  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig {
    // FOREGROUND_CRASH_FIX_1 (2026-09-20, device-proven) — was `isAllowedInForeground = false`
    // (the 4th constructor arg). Device log: WAKE_HEADLESS_START fired while BENSON's own
    // Activity was in the foreground -> HeadlessJsTaskContext threw an uncaught
    // IllegalStateException ("Tried to start task BensonWakeCommand while in foreground, but
    // this is not allowed") -> FATAL EXCEPTION -> the entire process died, taking
    // pendingWakeCommand/pendingHeadlessTestCommand (in-memory) down with it. The whole point of
    // this recovery path is to run when JS failed to respond in time — that can happen whether
    // BENSON is foreground or backgrounded — so this task must be allowed to run in either state.
    return HeadlessJsTaskConfig(
      "BensonWakeCommand",
      Arguments.createMap(),
      20_000L,
      true,
    )
  }
}
