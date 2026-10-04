// NATIVE_JS_RELIABLE_HANDOFF_FIX_1 (2026-09-19) — package.json's "main" used to point straight at
// "expo-router/entry". AppRegistry.registerHeadlessTask() must run at JS-bundle-load time,
// independent of whether the app's UI ever mounts — this file preserves expo-router's own entry
// (unchanged) and additionally registers the headless wake-command task next to it.
import 'expo-router/entry';
import './lib/headless/wakeCommandTask';
import { addWakeWordDetectedListener, takePendingWakeCommand, takePendingWakeAudioFile, logAudioDiag, nativeWakeSetOwner, isCallAudioBlocked, startConfirmationListening, addConfirmationResultListener } from 'benson-foreground-service';
import { getLiveWakeHandler } from './lib/headless/liveWakeHandler';
import { runMission } from './src/core/orchestrator/missionOrchestrator';
import { resumePendingTask } from './src/core/orchestrator/missionOrchestrator';
import { presentWakeResult } from './lib/headless/presentWakeResult';
import { routeThroughBrain, buildCanonicalCommand } from './lib/engines/brainRouter';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { stopSpeaking } from './lib/agents/voiceAgent';
import { setSystemSoundsMuted } from 'benson-foreground-service';

let pendingHeadlessMessageBody: { pending: NonNullable<Awaited<ReturnType<typeof runMission>>['pendingTask']>; confirmationId: string; lang: string } | null = null;
let activeReplyLang = 'ro-RO';
async function getHeadlessLanguages(): Promise<{ stt: string; reply: string }> {
  const [configuredStt, configuredReply] = await Promise.all([
    AsyncStorage.getItem('bensonLang').catch(() => null),
    AsyncStorage.getItem('bensonReplyLang').catch(() => null),
  ]);
  const stt = configuredStt || configuredReply || 'ro-RO';
  const reply = configuredReply || configuredStt || 'ro-RO';
  activeReplyLang = reply;
  return { stt, reply };
}
addConfirmationResultListener((confirmationId, _verdict, transcript) => {
  const waiting = pendingHeadlessMessageBody;
  if (!waiting || waiting.confirmationId !== confirmationId) return;
  pendingHeadlessMessageBody = null;
  if (!transcript.trim() || isCallAudioBlocked()) {
    logAudioDiag('WA_REPLY_BODY_NOT_RESUMED', `reason=${!transcript.trim() ? 'empty' : 'call_active'}`);
    return;
  }
  logAudioDiag('WA_REPLY_BODY_CAPTURED', `chars=${transcript.trim().length}`);
  resumePendingTask(waiting.pending, [], undefined, transcript.trim())
    .then(async (result) => {
      logAudioDiag('WA_REPLY_BODY_PREPARED', `waiting=${!!result.pendingTask} handled=${result.handled}`);
      await presentWakeResult({ text: result.message, transcript, lang: waiting.lang, waiting: !!result.pendingTask });
      // Deliberately stop at the send confirmation. No message is sent by this dictation turn.
    })
    .catch((error) => logAudioDiag('WA_REPLY_BODY_ERROR', `name=${error?.name ?? 'Error'}`));
});

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
addWakeWordDetectedListener((commandTail, audioFilePath) => {
  logAudioDiag('WAKE_EVENT_RECEIVED_IN_JS', `commandTail="${commandTail}" source=native_persistent`);
  try { takePendingWakeCommand(); takePendingWakeAudioFile(); } catch {}
  const live = getLiveWakeHandler();
  if (live) { live(commandTail, audioFilePath); return; }
  try {
    if (isCallAudioBlocked()) {
      logAudioDiag('WAKE_EVENT_DROPPED', 'reason=call_audio_active source=persistent_fallback');
      nativeWakeSetOwner('CALL');
      return;
    }
  } catch {}
  if (audioFilePath) {
    nativeWakeSetOwner('COMMAND_STT');
    Promise.all([import('./lib/agents/voiceAgent'), getHeadlessLanguages()]).then(([voice, languages]) =>
      voice.transcribeCapturedWakeAudio(audioFilePath, languages.stt).then((transcript) => {
        // FIX_PERSISTENT_WAKE_STRIP_1 (2026-10-02) — the old literal regex only stripped an exact
        // "Benson"/"hey jarvis" prefix. Deepgram mis-hearing the bare wake word itself (e.g.
        // "bentan", "benzan") then survived as leftover "command" text and was dispatched straight
        // to Brain, which always timed out for it (device-proven, three separate incidents, each
        // ~10010ms) — the misleading "nu am ajuns la creierul BENSON" message. Position-anchored
        // fuzzy match, same variant list as app/index.tsx's stripWakeWord(). Revert: restore the
        // literal regex above this line.
        const rawWords = (transcript || '').trim().split(/\s+/);
        const normWords = (transcript || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/^[^a-z]+/, '').trim().split(/\s+/);
        const wakeVariants = ['benson', 'bensen', 'benzon', 'bension', 'bensons'];
        const firstTwo = normWords.slice(0, 2).join(' ');
        const dropCount = firstTwo === 'hey jarvis' ? 2 : wakeVariants.includes(normWords[0]) ? 1 : 0;
        const command = dropCount ? rawWords.slice(dropCount).join(' ').trim() : '';
        if (!command) {
          // FIX_NATIVE_WAKE_ACK_1 (2026-10-02) — "Da, Master" is now spoken natively in
          // BensonForegroundService.kt, before this JS listener even runs. Speaking it again here
          // (as the prior FIX_PERSISTENT_WAKE_STRIP_1 round did) would double it. Revert: restore
          // the voice.speakNow(ackMsg, ...) call that used to sit here.
          logAudioDiag('WAKE_AUDIO_NO_COMMAND', 'source=persistent_fallback');
          return presentWakeResult({ text: '', transcript: '', lang: languages.reply });
        }
        logAudioDiag('WAKE_AUDIO_DISPATCH', `chars=${command.length} source=persistent_fallback`);
        const folded = command.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
        if (/^(?:please\s+)?(?:mute|mod mut|nu mai vorbi|opreste sunetul|taci dar asculta)[.! ]*$/i.test(folded)) {
          AsyncStorage.setItem('bensonMuted', 'true').catch(() => {});
          try { setSystemSoundsMuted(true); stopSpeaking(); } catch {}
          logAudioDiag('MUTE_MODE', 'state=on source=persistent_wake');
          return presentWakeResult({ text: '', transcript: command, lang: languages.reply });
        }
        if (/^(?:please\s+)?(?:unmute|porneste sunetul|cu sunet|vorbeste din nou)[.! ]*$/i.test(folded)) {
          AsyncStorage.setItem('bensonMuted', 'false').catch(() => {});
          try { setSystemSoundsMuted(false); } catch {}
          logAudioDiag('MUTE_MODE', 'state=off source=persistent_wake');
          return presentWakeResult({ text: 'Sound is back on.', transcript: command, lang: languages.reply });
        }
        return runMission(command, { source: 'voice' }).then(async (result) => {
          logAudioDiag('WAKE_AUDIO_MISSION_RESULT', `handled=${result.handled} waiting=${!!result.pendingTask || !!result.awaitingReadChoice} messageChars=${result.message.length}`);
          if (!result.handled && !result.pendingTask && !result.awaitingReadChoice) {
            // The mounted-screen dispatcher already sends deterministic misses to the single
            // configured Brain. The persistent headless path must do the same or wake commands
            // that need semantic interpretation stop here with an empty result.
            logAudioDiag('HEADLESS_BRAIN_ROUTE', `chars=${command.length}`);
            const readJson = async (key, fallback) => {
              try { const raw = await AsyncStorage.getItem(key); return raw ? JSON.parse(raw) : fallback; }
              catch { return fallback; }
            };
            const [history, facts, replyLang] = await Promise.all([
              readJson('benson_history_v2', []),
              readJson('benson_facts_v2', []),
              AsyncStorage.getItem('bensonReplyLang').catch(() => null),
            ]);
            const brainOut = await routeThroughBrain({
              utterance: command,
              lang: replyLang || 'ro-RO',
              history: Array.isArray(history) ? history.slice(-12) : [],
              facts: Array.isArray(facts) ? facts.slice(-20) : [],
            });
            if (brainOut.kind === 'speak' || brainOut.kind === 'clarify') {
              const text = brainOut.kind === 'speak' ? brainOut.text : brainOut.question;
              logAudioDiag('HEADLESS_BRAIN_RESULT', `kind=${brainOut.kind} chars=${text.length}`);
              return presentWakeResult({ text, transcript: command, lang: replyLang || 'ro-RO', waiting: brainOut.kind === 'clarify' });
            }
            const canonical = buildCanonicalCommand(brainOut.action, brainOut.params, replyLang || 'ro-RO');
            if (!canonical) {
              logAudioDiag('HEADLESS_BRAIN_UNMAPPED', `action=${brainOut.action}`);
              return presentWakeResult({ text: 'Nu pot executa această cerere prin executorul disponibil.', transcript: command, lang: replyLang || 'ro-RO' });
            }
            logAudioDiag('HEADLESS_BRAIN_ACTION', `action=${brainOut.action} canonicalChars=${canonical.length}`);
            const bridged = await runMission(canonical, { source: 'voice' });
            logAudioDiag('HEADLESS_BRAIN_MISSION', `handled=${bridged.handled} waiting=${!!bridged.pendingTask || !!bridged.awaitingReadChoice}`);
            result = bridged;
          }
          return presentWakeResult({
            text: result.message,
            transcript: command,
              lang: languages.reply,
            waiting: !!result.pendingTask || !!result.awaitingReadChoice,
          }).then(() => {
            const task = result.pendingTask?.plan.tasks[result.pendingTask.taskIndex];
            if (result.pendingTask && task?.type === 'PREPARE_MESSAGE' && task.input.waAwaitingMessageBody) {
              const confirmationId = `wa-body-${Date.now()}`;
              pendingHeadlessMessageBody = { pending: result.pendingTask, confirmationId, lang: languages.reply };
              logAudioDiag('WA_REPLY_BODY_LISTEN_ARM', `confirmationId=${confirmationId} timeoutMs=20000`);
              startConfirmationListening(confirmationId, 20000);
            }
          });
        });
      })
    ).catch((e) => {
      logAudioDiag('WAKE_AUDIO_ERROR', `error=${String(e)} source=persistent_fallback`);
      return presentWakeResult({ text: '', transcript: '', lang: activeReplyLang });
    });
    return;
  }
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
