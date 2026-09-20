// NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19) — package.json's "main" used to point straight at
// "expo-router/entry". AppRegistry.registerHeadlessTask() must run at JS-bundle-load time,
// independent of whether the app's UI ever mounts — this file preserves expo-router's own entry
// (unchanged) and additionally registers the headless wake-command task next to it.
import 'expo-router/entry';
import './lib/headless/wakeCommandTask';
import { addWakeWordDetectedListener, takePendingWakeCommand, logAudioDiag } from 'benson-foreground-service';
import { getLiveWakeHandler } from './lib/headless/liveWakeHandler';
import { runMission } from './src/core/orchestrator/missionOrchestrator';

// PERSISTENT_WAKE_CONSUMER_1 (2026-09-20, device-proven) — registered once at JS-engine load,
// independent of whether app/index.tsx's screen component is currently mounted. Device log
// evidence: two real "Benson" wake attempts while backgrounded both showed
// WAKE_EVENT_EMITTED_TO_JS hasListenerRegistered=true natively, yet WAKE_EVENT_RECEIVED_IN_JS
// (logged as literally the first line of the OLD screen-scoped listener) never fired — root cause
// traced to ActivityTaskManager relaunching BENSON's Activity from a killed state (OxygenOS
// background-activity policy, unrelated to Doze/battery — this device's deviceidle whitelist,
// wake lock, and app-standby bucket were all already correctly exempted). The old listener lived
// in app/index.tsx's useEffect, torn down whenever the Activity/screen unmounts. This one survives
// as long as the JS engine does, cutting wake-to-dispatch latency from the previous ~5s
// (WakeHandoffWatchdog + Headless recovery) down to near-instant for both outcomes below.
addWakeWordDetectedListener((commandTail) => {
  logAudioDiag('WAKE_EVENT_RECEIVED_IN_JS', `commandTail="${commandTail}" source=native_persistent`);
  try { takePendingWakeCommand(); } catch {}
  const live = getLiveWakeHandler();
  if (live) { live(commandTail); return; }
  // Screen not currently mounted — same one-shot executor the Headless recovery path already
  // used, now reached without waiting for the 5s watchdog. Known, honestly-reported limitation:
  // a bare "Benson" (empty commandTail) still can't open a live follow-up listening turn from
  // here — doStartListening() needs the screen's own refs, which don't exist without a mounted
  // component. That case surfaces below as handled=false, not silently dropped.
  logAudioDiag('WAKE_PERSISTENT_NO_LIVE_SCREEN', `commandTail="${commandTail}"`);
  runMission(commandTail, { source: 'voice' })
    .then((result) => logAudioDiag('WAKE_HEADLESS_RESULT', `handled=${result.handled} missionId=${result.plan?.id ?? 'none'} source=persistent_fallback`))
    .catch((e) => logAudioDiag('WAKE_HEADLESS_ERROR', `error=${String(e)} source=persistent_fallback`));
});
