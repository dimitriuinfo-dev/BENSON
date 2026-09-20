// NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19, device-proven) — the Headless JS task
// BensonWakeHeadlessTaskService (native) starts ONLY when the live wake-event/heartbeat-poll path
// in app/index.tsx has NOT consumed a pending wake command within 5s (BensonForegroundService's
// existing WakeHandoffWatchdog). Calls the SAME atomic takePendingWakeCommand() every other
// consumption path already uses, so a command is never processed twice regardless of which path
// claims it first. Runs runMission() directly — the SAME deterministic executor the live
// app/index.tsx path uses, no second parser, no second dispatcher — so a missed "Benson, deschide
// calculatorul" still opens the app instead of vanishing silently.
//
// Known limitation of this round (documented, not silently glossed over): this headless recovery
// path executes the mission but does not yet speak a TTS ack or update the overlay bubble the way
// the live app/index.tsx path does — those live in app/index.tsx's stateful conversation loop,
// out of this round's scope ("do not open BENSON's UI"). The user gets the actual action (app
// opens) instead of nothing; a spoken/visual ack is a natural follow-up round.
import { AppRegistry } from 'react-native';
import { takePendingWakeCommand, takePendingHeadlessTestCommand, logAudioDiag } from 'benson-foreground-service';
import { runMission } from '../../src/core/orchestrator/missionOrchestrator';

async function wakeCommandTask(): Promise<void> {
  // HEADLESS_WIRING_TEST_ISOLATION_1 (2026-09-20) — checked FIRST, and ONLY here (the live
  // onWakeWordDetected/heartbeat-fallback paths in app/index.tsx never call this), so an injected
  // debug-only wiring-test command is structurally impossible for the live path to race. Always
  // null in a release build (nothing ever writes that field there) — zero effect on real commands.
  const testCmd = takePendingHeadlessTestCommand();
  const cmd = testCmd ?? takePendingWakeCommand();
  if (cmd === null) {
    logAudioDiag('WAKE_HEADLESS_NOOP', 'reason=already_consumed');
    return;
  }
  logAudioDiag('WAKE_HEADLESS_CONSUME', `commandTail=${JSON.stringify(cmd)} source=${testCmd !== null ? 'headless_test' : 'production'}`);
  try {
    const result = await runMission(cmd, { source: 'voice' });
    logAudioDiag('WAKE_HEADLESS_RESULT', `handled=${result.handled} missionId=${result.plan?.id ?? 'none'}`);
  } catch (e) {
    logAudioDiag('WAKE_HEADLESS_ERROR', `error=${String(e)}`);
  }
}

AppRegistry.registerHeadlessTask('BensonWakeCommand', () => wakeCommandTask);
