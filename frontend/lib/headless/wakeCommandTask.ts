// NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19) / NO_JS_LISTENER_HEADLESS_FIX_1 (2026-09-21) —
// BensonWakeHeadlessTaskService (native) now starts in BOTH cases the live JS path can miss a
// wake command: (a) a live listener is registered but its callback never actually fires within
// 5s (BensonForegroundService's WakeHandoffWatchdog), and (b) NO listener was ever registered at
// all — e.g. the native foreground service/wake loop resumed after an OS-triggered restart but
// the JS engine itself never initialized for that process (device-confirmed 2026-09-21: zero
// mqt_v_js log lines for the entire process lifetime, despite the native wake loop correctly
// detecting and transcribing speech). Calls the SAME atomic takePendingWakeCommand() every other
// consumption path uses, so a command is never processed twice regardless of which path claims it
// first or how many times the native side (redundantly, harmlessly) starts this service.
//
// CONVERSATIONAL_HEADLESS_RECOVERY_1 (2026-09-21) — extends the previous "silent runMission() only"
// recovery: when the mission needs a yes/no reply (a same-breath "Benson, deschide Calculatorul"
// resolving to a single fuzzy app match), this now actually SPEAKS the question (TTS is a plain
// native-module call, not tied to any mounted screen) and LISTENS for the reply via the same
// native one-shot confirmation capture the live path uses (also not screen-bound), then feeds the
// reply back into the SAME runMission() — the identical resolution path a live "da" takes, no
// second parser/orchestrator. Bounded to one confirmation round to keep a headless task's limited
// time budget (BensonWakeHeadlessTaskService's HeadlessJsTaskConfig, 20s) safe.
//
// Known, honestly-reported limitation of this round: a BARE "Benson" (empty commandTail) still
// cannot open a live follow-up listening turn from here — there is no exposed native function for
// capturing a NEW, open-ended command outside the wake-word STT/confirmation-STT paths (both of
// which require known content shape: a wake name, or a short yes/no). That case still surfaces
// honestly as handled=false rather than silently doing nothing further.
import { AppRegistry } from 'react-native';
import {
  takePendingWakeCommand, takePendingHeadlessTestCommand, logAudioDiag,
  startConfirmationListening, addConfirmationResultListener,
} from 'benson-foreground-service';
import { runMission, type MissionRunResult } from '../../src/core/orchestrator/missionOrchestrator';
import { speakNow } from '../agents/voiceAgent';

const HEADLESS_CONFIRM_TIMEOUT_MS = 8000; // same window the live disambiguation confirm uses
const HEADLESS_MAX_CONFIRM_ROUNDS = 1; // bounded — see file header

function speakAndWait(text: string): Promise<void> {
  return new Promise((resolve) => {
    try {
      speakNow(text, { language: 'ro-RO', onDone: () => resolve(), onStopped: () => resolve(), onError: () => resolve() });
    } catch {
      resolve();
    }
  });
}

function waitForConfirmation(confirmationId: string, timeoutMs: number): Promise<{ verdict: string; text: string }> {
  return new Promise((resolve) => {
    let settled = false;
    const sub = addConfirmationResultListener((id, verdict, text) => {
      if (id !== confirmationId || settled) return;
      settled = true;
      sub.remove();
      resolve({ verdict, text });
    });
    try {
      startConfirmationListening(confirmationId, timeoutMs);
    } catch {
      settled = true;
      sub.remove();
      resolve({ verdict: 'TIMEOUT', text: '' });
    }
  });
}

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
  if (!cmd.trim()) {
    // Documented limitation above — nothing further this round, reported honestly.
    logAudioDiag('WAKE_HEADLESS_RESULT', 'handled=false missionId=none reason=bare_wake_no_capture');
    return;
  }
  try {
    let result: MissionRunResult = await runMission(cmd, { source: 'voice' });
    logAudioDiag('WAKE_HEADLESS_RESULT', `handled=${result.handled} missionId=${result.plan?.id ?? 'none'}`);
    let round = 0;
    while (result.handled && result.disambiguation && round < HEADLESS_MAX_CONFIRM_ROUNDS) {
      round += 1;
      if (result.message) await speakAndWait(result.message);
      const confirmationId = `headless-confirm-${Date.now()}`;
      logAudioDiag('WAKE_HEADLESS_CONFIRM_ARM', `confirmationId=${confirmationId} round=${round}`);
      const { verdict, text } = await waitForConfirmation(confirmationId, HEADLESS_CONFIRM_TIMEOUT_MS);
      logAudioDiag('WAKE_HEADLESS_CONFIRM_RESULT', `confirmationId=${confirmationId} verdict=${verdict} text=${JSON.stringify(text)}`);
      if (verdict === 'TIMEOUT' || !text.trim()) {
        logAudioDiag('WAKE_HEADLESS_RESULT', 'handled=true missionId=none reason=confirmation_not_answered');
        return;
      }
      // Same resolution path a live "da" takes: runMission() checks its own pendingDisambiguation
      // first, before anything else — no second parser, no second orchestrator.
      result = await runMission(text, { source: 'voice' });
      logAudioDiag('WAKE_HEADLESS_RESULT', `handled=${result.handled} missionId=${result.plan?.id ?? 'none'} round=${round}`);
    }
    if (result.handled && result.message) await speakAndWait(result.message);
  } catch (e) {
    logAudioDiag('WAKE_HEADLESS_ERROR', `error=${String(e)}`);
  }
}

AppRegistry.registerHeadlessTask('BensonWakeCommand', () => wakeCommandTask);
