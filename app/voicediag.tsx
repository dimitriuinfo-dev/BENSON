// BENSON — Isolated Voice Diagnostic Harness.
//
// PURPOSE (read this before editing): give REAL, on-device proof of exactly where the
// microphone -> speech-recognition -> transcript -> orchestrator chain breaks, WITHOUT depending
// on adb logcat. Every one of the four checkpoints the product owner requires is shown live on
// screen, with timestamps:
//
//   1. RECORD_AUDIO permission — checked programmatically (not assumed).
//   2. Recognition session actually STARTS — proven by the native `start` event.
//   3. The engine returns SOMETHING — any transcript (even wrong), `nomatch`, or the exact error
//      code/name. Mic ENERGY (volumechange RMS) is also shown: if it moves, the microphone
//      hardware + permission + session are all genuinely working.
//   4. The transcript reaches the ORCHESTRATOR — optional live routing through the SAME runMission
//      the working typed Debug Panel uses, with the reply shown on screen.
//
// This screen deliberately talks DIRECTLY to `expo-speech-recognition` and imports NOTHING from
// voiceAgent.ts, the wake-word loop, or the foreground service. That isolation is the whole point:
// if a transcript appears HERE but not in the live app, the bug is in BENSON's own wiring; if
// nothing appears even here, the bug is the recognizer/permission/device itself.

import { useCallback, useEffect, useRef, useState } from 'react';
import { View, Text, ScrollView, TouchableOpacity, StyleSheet, Platform } from 'react-native';
import { useFocusEffect, useRouter } from 'expo-router';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import { ExpoSpeechRecognitionModule } from 'expo-speech-recognition';
import { runMission } from '../src/core/orchestrator';

const KEEP_AWAKE_TAG = 'benson-voicediag';

// The three languages BENSON is used in — lets the tester rule the language/locale in or out on
// the spot (Defect A in AUDIO_DIAGNOSIS_REPORT.md was a wrong-locale bug).
const LANGS = ['ro-RO', 'en-US', 'de-DE'] as const;
type Lang = (typeof LANGS)[number];

// Every native event this library can emit (per ExpoSpeechRecognitionModule.types.ts). We attach
// to ALL of them so nothing can fail silently — the absence of an expected event is itself the clue.
const EVENT_NAMES = [
  'start', 'audiostart', 'soundstart', 'speechstart',
  'result', 'nomatch',
  'speechend', 'soundend', 'audioend',
  'error', 'end', 'languagedetection',
] as const;

type LogLine = { t: number; tag: string; detail: string };

function fmtMs(ms: number) {
  return `+${(ms / 1000).toFixed(2)}s`;
}

export default function VoiceDiagScreen() {
  const router = useRouter();

  // Device recognizer capabilities — the first thing to check on OxygenOS/ColorOS, where the
  // default recognition service is often NOT Google's and may be missing entirely.
  const [caps, setCaps] = useState<string>('(tap "Check device" to load)');

  // Permission (checkpoint 1)
  const [perm, setPerm] = useState<string>('unknown');
  const [permGranted, setPermGranted] = useState<boolean | null>(null);

  // Checkpoints
  const [started, setStarted] = useState(false);        // 2: session started (native `start` event)
  const [engineReturned, setEngineReturned] = useState(false); // 3: result/nomatch/error after speech
  const [transcript, setTranscript] = useState('');     // 3/4: captured transcript
  const [peakRms, setPeakRms] = useState<number>(-999); // mic-energy proof
  const [lastError, setLastError] = useState('');
  const [orchestratorReply, setOrchestratorReply] = useState('');

  const [lang, setLang] = useState<Lang>('ro-RO');
  const [onDevice, setOnDevice] = useState(false);      // requiresOnDeviceRecognition toggle
  const [autoRoute, setAutoRoute] = useState(true);     // send final transcript to orchestrator
  const [listening, setListening] = useState(false);

  const [log, setLog] = useState<LogLine[]>([]);
  const startTsRef = useRef<number>(0);
  const routedRef = useRef(false);

  const append = useCallback((tag: string, detail: string) => {
    const t = startTsRef.current ? Date.now() - startTsRef.current : 0;
    setLog((prev) => [...prev, { t, tag, detail }]);
  }, []);

  // Keep the screen awake — a sleeping screen is exactly what stopped prior live tests from ever
  // capturing a full attempt (see AUDIO_DIAGNOSIS_REPORT.md §8).
  useFocusEffect(
    useCallback(() => {
      activateKeepAwakeAsync(KEEP_AWAKE_TAG).catch(() => {});
      return () => deactivateKeepAwake(KEEP_AWAKE_TAG);
    }, []),
  );

  // Attach to every native event ONCE. Handlers use functional setState so there are no stale
  // closures over React state.
  useEffect(() => {
    const subs = EVENT_NAMES.map((name) =>
      ExpoSpeechRecognitionModule.addListener(name as any, (ev: any) => {
        if (name === 'result') {
          const text = ev?.results?.[0]?.transcript ?? '';
          const isFinal = ev?.isFinal !== false;
          append('result', `isFinal=${isFinal} text="${text}"`);
          setEngineReturned(true);
          if (text) {
            setTranscript(text);
            if (isFinal && autoRoute && !routedRef.current) {
              routedRef.current = true;
              routeToOrchestrator(text);
            }
          }
          return;
        }
        if (name === 'error') {
          append('error', `code=${ev?.code ?? '?'} error="${ev?.error ?? ''}" message="${ev?.message ?? ''}"`);
          setEngineReturned(true);
          setLastError(`${ev?.error ?? 'error'} (code ${ev?.code ?? '?'})`);
          return;
        }
        if (name === 'nomatch') {
          append('nomatch', 'engine returned no match (mic worked, but no words recognized)');
          setEngineReturned(true);
          return;
        }
        if (name === 'start') {
          append('start', 'session started');
          setStarted(true);
          return;
        }
        if (name === 'end') {
          append('end', 'session ended');
          setListening(false);
          return;
        }
        if (name === 'languagedetection') {
          append('languagedetection', `lang=${ev?.detectedLanguage ?? '?'} conf=${ev?.confidence ?? '?'}`);
          return;
        }
        // audiostart / speechstart / speechend / etc — lightweight markers
        append(name, ev ? JSON.stringify(ev).slice(0, 80) : '');
      }),
    );

    // volumechange is high-frequency — handle separately, only track the peak so the log stays readable.
    const volSub = ExpoSpeechRecognitionModule.addListener('volumechange' as any, (ev: any) => {
      const v = typeof ev?.value === 'number' ? ev.value : -999;
      setPeakRms((prev) => (v > prev ? v : prev));
    });

    return () => {
      subs.forEach((s) => s.remove());
      volSub.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRoute]);

  async function routeToOrchestrator(text: string) {
    append('orchestrator', `runMission("${text}") …`);
    try {
      const result = await runMission(text, { source: 'voice', contacts: [] });
      append('orchestrator', `handled=${result.handled} message="${result.message ?? ''}"`);
      setOrchestratorReply(result.handled ? (result.message ?? '(no message)') : '(not handled — would fall through to Claude)');
    } catch (e) {
      append('orchestrator', `EXCEPTION ${String(e)}`);
      setOrchestratorReply(`EXCEPTION: ${String(e)}`);
    }
  }

  async function checkDevice() {
    if (Platform.OS !== 'android') {
      setCaps(`Platform=${Platform.OS} — this harness targets Android device builds.`);
      return;
    }
    try {
      const available = ExpoSpeechRecognitionModule.isRecognitionAvailable();
      let services: string[] = [];
      let def = '(n/a)';
      let assistant = '(n/a)';
      let onDeviceSupported = false;
      try { services = ExpoSpeechRecognitionModule.getSpeechRecognitionServices(); } catch (e) { services = [`err: ${String(e)}`]; }
      try { def = ExpoSpeechRecognitionModule.getDefaultRecognitionService()?.packageName ?? '(none)'; } catch (e) { def = `err: ${String(e)}`; }
      try { assistant = ExpoSpeechRecognitionModule.getAssistantService()?.packageName ?? '(none)'; } catch (e) { assistant = `err: ${String(e)}`; }
      try { onDeviceSupported = ExpoSpeechRecognitionModule.supportsOnDeviceRecognition(); } catch {}
      setCaps(
        `isRecognitionAvailable: ${available}\n` +
        `default service: ${def}\n` +
        `assistant service: ${assistant}\n` +
        `on-device supported: ${onDeviceSupported}\n` +
        `installed services (${services.length}):\n  ${services.join('\n  ') || '(none)'}`,
      );
    } catch (e) {
      setCaps(`ERROR reading capabilities: ${String(e)}`);
    }
  }

  async function checkPermission() {
    try {
      const p = await ExpoSpeechRecognitionModule.getPermissionsAsync();
      setPerm(`status=${p.status} granted=${p.granted} canAskAgain=${p.canAskAgain}`);
      setPermGranted(p.granted);
      return p.granted;
    } catch (e) {
      setPerm(`ERROR: ${String(e)}`);
      setPermGranted(false);
      return false;
    }
  }

  async function requestPermission() {
    try {
      const p = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
      setPerm(`status=${p.status} granted=${p.granted} canAskAgain=${p.canAskAgain}`);
      setPermGranted(p.granted);
    } catch (e) {
      setPerm(`ERROR: ${String(e)}`);
      setPermGranted(false);
    }
  }

  async function startTest() {
    // Reset everything so each run is a clean, unambiguous attempt.
    setLog([]);
    setStarted(false);
    setEngineReturned(false);
    setTranscript('');
    setPeakRms(-999);
    setLastError('');
    setOrchestratorReply('');
    routedRef.current = false;
    startTsRef.current = Date.now();

    const granted = await checkPermission();
    append('permission', `granted=${granted}`);
    if (!granted) {
      append('permission', 'NOT granted — requesting…');
      await requestPermission();
      const regrant = await checkPermission();
      if (!regrant) {
        append('permission', 'STILL NOT granted — chain stops at step 1. Grant mic in Android Settings.');
        return;
      }
    }

    setListening(true);
    append('start-call', `ExpoSpeechRecognitionModule.start lang=${lang} onDevice=${onDevice}`);
    try {
      ExpoSpeechRecognitionModule.start({
        lang,
        interimResults: true,        // TRUE here on purpose: partial results are extra proof the mic is capturing
        continuous: false,
        maxAlternatives: 1,
        requiresOnDeviceRecognition: onDevice,
        volumeChangeEventOptions: { enabled: true, intervalMillis: 100 },
        androidIntentOptions: {
          EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS: 800,
          EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS: 1800,
          EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS: 1200,
        },
      });
      append('start-call', 'start() returned without throwing (events will follow)');
    } catch (e) {
      append('start-call', `start() THREW synchronously: ${String(e)}`);
      setListening(false);
      setLastError(`start() threw: ${String(e)}`);
    }
  }

  function stopTest() {
    try { ExpoSpeechRecognitionModule.stop(); append('stop-call', 'stop() called'); } catch (e) { append('stop-call', `stop() threw: ${String(e)}`); }
  }

  const micEnergyText = peakRms <= -999 ? '(no volume events yet)' : `peak RMS ${peakRms.toFixed(1)} (range -2..10)`;

  return (
    <View style={s.root}>
      <View style={s.topbar}>
        <TouchableOpacity onPress={() => router.back()} testID="voicediag-back" accessibilityRole="button">
          <Text style={s.link}>‹ Back</Text>
        </TouchableOpacity>
        <Text style={s.title}>VOICE DIAGNOSTIC</Text>
        <View style={{ width: 48 }} />
      </View>

      <ScrollView contentContainerStyle={{ paddingBottom: 40 }}>
        {/* Checkpoints */}
        <View style={s.card}>
          <Text style={s.cardTitle}>4-STEP PROOF</Text>
          <Checkpoint n={1} label="Microphone permission granted" ok={permGranted === true} bad={permGranted === false} />
          <Checkpoint n={2} label="Recognition session started" ok={started} />
          <Checkpoint n={3} label="Engine returned something (text / nomatch / error)" ok={engineReturned} />
          <Checkpoint n={4} label="Non-empty transcript captured" ok={!!transcript} />
          <Text style={s.energy}>Mic energy: {micEnergyText}</Text>
          {!!lastError && <Text style={s.errText}>Last error: {lastError}</Text>}
        </View>

        {/* Transcript + orchestrator */}
        <View style={s.card}>
          <Text style={s.cardTitle}>TRANSCRIPT</Text>
          <Text testID="voicediag-transcript" style={s.transcript}>{transcript || '—'}</Text>
          <Text style={[s.cardTitle, { marginTop: 10 }]}>ORCHESTRATOR REPLY</Text>
          <Text testID="voicediag-orchestrator-reply" style={s.reply}>{orchestratorReply || '—'}</Text>
        </View>

        {/* Controls */}
        <View style={s.card}>
          <Text style={s.cardTitle}>SETTINGS</Text>
          <Text style={s.rowLabel}>Language</Text>
          <View style={s.chipRow}>
            {LANGS.map((l) => (
              <TouchableOpacity key={l} onPress={() => setLang(l)} testID={`voicediag-lang-${l}`}
                style={[s.chip, lang === l && s.chipOn]} accessibilityRole="button">
                <Text style={[s.chipText, lang === l && s.chipTextOn]}>{l}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <Toggle label="On-device engine (requiresOnDeviceRecognition)" value={onDevice} onToggle={() => setOnDevice((v) => !v)} testID="voicediag-toggle-ondevice" />
          <Toggle label="Auto-route final transcript to orchestrator" value={autoRoute} onToggle={() => setAutoRoute((v) => !v)} testID="voicediag-toggle-autoroute" />
        </View>

        {/* Actions */}
        <View style={s.card}>
          {!listening ? (
            <TouchableOpacity onPress={startTest} style={[s.btn, s.btnPrimary]} testID="voicediag-start" accessibilityRole="button">
              <Text style={s.btnText}>START LISTENING TEST</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity onPress={stopTest} style={[s.btn, s.btnStop]} testID="voicediag-stop" accessibilityRole="button">
              <Text style={s.btnText}>STOP</Text>
            </TouchableOpacity>
          )}
          <View style={s.btnRow}>
            <TouchableOpacity onPress={checkDevice} style={[s.btn, s.btnSecondary, { flex: 1 }]} testID="voicediag-check-device" accessibilityRole="button">
              <Text style={s.btnTextSecondary}>Check device</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={requestPermission} style={[s.btn, s.btnSecondary, { flex: 1 }]} testID="voicediag-request-perm" accessibilityRole="button">
              <Text style={s.btnTextSecondary}>Request mic</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Device capabilities */}
        <View style={s.card}>
          <Text style={s.cardTitle}>DEVICE RECOGNIZER</Text>
          <Text style={s.mono}>{caps}</Text>
          <Text style={s.mono}>permission: {perm}</Text>
        </View>

        {/* Live event trace */}
        <View style={s.card}>
          <Text style={s.cardTitle}>LIVE EVENT TRACE ({log.length})</Text>
          {log.length === 0 ? (
            <Text style={s.dim}>No events yet — tap START and speak a command.</Text>
          ) : (
            log.map((l, i) => (
              <Text key={i} style={s.logLine}>
                <Text style={s.logT}>{fmtMs(l.t)} </Text>
                <Text style={s.logTag}>{l.tag}</Text>
                <Text style={s.logDetail}>  {l.detail}</Text>
              </Text>
            ))
          )}
        </View>
      </ScrollView>
    </View>
  );
}

function Checkpoint({ n, label, ok, bad }: { n: number; label: string; ok: boolean; bad?: boolean }) {
  const mark = ok ? '✓' : bad ? '✕' : '·';
  const color = ok ? '#4CC38A' : bad ? '#E5484D' : '#6b7683';
  return (
    <View style={s.cpRow}>
      <Text style={[s.cpMark, { color }]}>{mark}</Text>
      <Text style={s.cpLabel}>{n}. {label}</Text>
    </View>
  );
}

function Toggle({ label, value, onToggle, testID }: { label: string; value: boolean; onToggle: () => void; testID: string }) {
  return (
    <TouchableOpacity onPress={onToggle} style={s.toggleRow} testID={testID} accessibilityRole="switch" accessibilityState={{ checked: value }}>
      <View style={[s.toggleBox, value && s.toggleBoxOn]}>{value && <Text style={s.toggleCheck}>✓</Text>}</View>
      <Text style={s.toggleLabel}>{label}</Text>
    </TouchableOpacity>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0D1B2A', paddingTop: 44 },
  topbar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingBottom: 8 },
  title: { color: '#C9A24B', fontSize: 16, fontWeight: '800', letterSpacing: 1 },
  link: { color: '#4da6ff', fontSize: 16 },
  card: { backgroundColor: '#152A3E', marginHorizontal: 12, marginTop: 10, borderRadius: 12, padding: 14 },
  cardTitle: { color: '#8Fb8de', fontSize: 12, fontWeight: '800', letterSpacing: 1, marginBottom: 8 },
  cpRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4 },
  cpMark: { width: 22, fontSize: 18, fontWeight: '900' },
  cpLabel: { color: '#E9E4D8', fontSize: 14, flex: 1 },
  energy: { color: '#C9A24B', fontSize: 13, marginTop: 8 },
  errText: { color: '#E5484D', fontSize: 13, marginTop: 6 },
  transcript: { color: '#fff', fontSize: 18, fontWeight: '700' },
  reply: { color: '#4CC38A', fontSize: 15 },
  rowLabel: { color: '#9AA3AC', fontSize: 12, marginBottom: 6 },
  chipRow: { flexDirection: 'row', gap: 8, marginBottom: 6 },
  chip: { paddingVertical: 8, paddingHorizontal: 14, borderRadius: 20, borderWidth: 1, borderColor: '#2E4A66' },
  chipOn: { backgroundColor: '#C9A24B', borderColor: '#C9A24B' },
  chipText: { color: '#9AA3AC', fontWeight: '700' },
  chipTextOn: { color: '#0D1B2A' },
  toggleRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  toggleBox: { width: 22, height: 22, borderRadius: 5, borderWidth: 2, borderColor: '#2E4A66', marginRight: 10, alignItems: 'center', justifyContent: 'center' },
  toggleBoxOn: { backgroundColor: '#C9A24B', borderColor: '#C9A24B' },
  toggleCheck: { color: '#0D1B2A', fontWeight: '900', fontSize: 14 },
  toggleLabel: { color: '#E9E4D8', fontSize: 14, flex: 1 },
  btn: { paddingVertical: 14, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  btnPrimary: { backgroundColor: '#C9A24B' },
  btnStop: { backgroundColor: '#E5484D' },
  btnSecondary: { backgroundColor: '#1E3A54', borderWidth: 1, borderColor: '#2E4A66' },
  btnText: { color: '#0D1B2A', fontWeight: '800', fontSize: 15 },
  btnTextSecondary: { color: '#8Fb8de', fontWeight: '700' },
  btnRow: { flexDirection: 'row', gap: 10, marginTop: 10 },
  mono: { color: '#B7C4D0', fontSize: 12, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', marginBottom: 2 },
  dim: { color: '#6b7683', fontSize: 13 },
  logLine: { fontSize: 12, marginBottom: 3 },
  logT: { color: '#6b7683', fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },
  logTag: { color: '#C9A24B', fontWeight: '800' },
  logDetail: { color: '#B7C4D0' },
});
