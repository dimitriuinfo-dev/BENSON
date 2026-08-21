import * as Speech from 'expo-speech';
import { ExpoSpeechRecognitionModule } from 'expo-speech-recognition';
import { startCapture, stopCapture, addCaptureEndListener, addVolumeChangeListener } from 'benson-audio-capture';
import { logAudioDiag } from 'benson-foreground-service';
import { transcribeLocally } from './localWhisperEngine';

export type { Voice } from 'expo-speech';
export type SttEngine = 'cloud' | 'ondevice' | 'local';

// Voice Agent — thin wrapper around the STT/TTS native modules.
// Timing/state (when to (re)start listening, when to speak) stays owned by the caller,
// since it's tied to the app's hands-free conversation loop.

export async function requestMicPermission(): Promise<boolean> {
  const perm = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
  return perm.granted;
}

// Non-prompting status check — for the Settings/Service panel's live indicators.
export async function checkMicPermission(): Promise<boolean> {
  const perm = await ExpoSpeechRecognitionModule.getPermissionsAsync();
  return perm.granted;
}

// Shared result/error/end event bus — the caller (app/index.tsx) listens to this ONE set of
// events regardless of which engine actually produced them (Android's SpeechRecognizer via
// expo-speech-recognition, or BENSON's own local capture+Whisper pipeline below). This keeps the
// large, carefully-tuned conversation-loop logic in app/index.tsx unchanged when adding the
// 'local' engine — only startRecognition/stopRecognition need to know which engine is active.
type ResultCb = (transcript: string, isFinal: boolean) => void;
type ErrorCb = (error: string) => void;
type EndCb = () => void;

type VolumeCb = (level: number) => void;

const resultListeners = new Set<ResultCb>();
const errorListeners = new Set<ErrorCb>();
const endListeners = new Set<EndCb>();
const volumeListeners = new Set<VolumeCb>();

function emitResult(t: string, isFinal: boolean) { resultListeners.forEach((cb) => cb(t, isFinal)); }
function emitError(e: string) { errorListeners.forEach((cb) => cb(e)); }
function emitEnd() { endListeners.forEach((cb) => cb()); }
function emitVolume(level: number) { volumeListeners.forEach((cb) => cb(level)); }

// Relay native expo-speech-recognition events into the same bus — set up once, module-level.
ExpoSpeechRecognitionModule.addListener('result', (ev: any) => {
  emitResult(ev.results?.[0]?.transcript ?? '', ev.isFinal !== false);
});
// Mic-contention instrumentation (product-owner-directed) — cloud/ondevice session lifecycle,
// under the same BENSON_AUDIO tag as the native hotword-loop/local-whisper stages, so all three
// engines' timing can be read from one log to check whether sessions overlap in time. `code` is
// the underlying native SpeechRecognizer error constant when the library exposes it (undefined
// otherwise) — logging behavior only, no behavior change.
ExpoSpeechRecognitionModule.addListener('error', (ev: any) => {
  logAudioDiag('STT_SESSION', `engine=${activeEngine} event=error code=${ev.code ?? -1} error=${ev.error}`);
  emitError(ev.error);
});
ExpoSpeechRecognitionModule.addListener('end', () => {
  logAudioDiag('STT_SESSION', `engine=${activeEngine} event=end`);
  emitEnd();
});
// Real-time mic level for the listening indicator (replaces a Math.random() animation) — value is
// -2..10 per the library; normalized to 0-1 here so the UI doesn't need to know which engine (or
// which native scale) produced it.
ExpoSpeechRecognitionModule.addListener('volumechange', (ev: any) => {
  emitVolume(Math.max(0, Math.min(1, (ev.value ?? -2) / 10)));
});
addVolumeChangeListener((level: number) => emitVolume(Math.max(0, Math.min(1, level))));

let activeEngine: SttEngine = 'cloud';
let localCaptureEndSub: { remove: () => void } | null = null;

function runLocalCapture(lang: string) {
  localCaptureEndSub?.remove();
  localCaptureEndSub = addCaptureEndListener(async (filePath, reason) => {
    localCaptureEndSub?.remove();
    localCaptureEndSub = null;
    if (!filePath) {
      emitError(reason === 'no_speech' ? 'no-speech' : reason);
      emitEnd();
      return;
    }
    try {
      const text = await transcribeLocally(filePath, lang);
      if (text) emitResult(text, true);
      else emitError('no-speech');
    } catch (e) {
      emitError('local-transcribe-error');
    }
    emitEnd();
  });
  startCapture().catch(() => {
    localCaptureEndSub?.remove();
    localCaptureEndSub = null;
    emitError('audio-capture');
    emitEnd();
  });
}

export function startRecognition(lang: string, engine: SttEngine = 'cloud') {
  activeEngine = engine;
  if (engine === 'local') {
    runLocalCapture(lang);
    return;
  }
  logAudioDiag('STT_SESSION', `engine=${engine} event=start lang=${lang}`);
  ExpoSpeechRecognitionModule.start({
    lang,
    interimResults: false,
    continuous:      false,
    maxAlternatives: 1,
    // Cloud recognition on this device has been confirmed unreliable independent of language
    // (hangs, ERROR_NO_MATCH/ERROR_CLIENT/ERROR_TOO_MANY_REQUESTS with no consistent pattern —
    // see AUDIO_DIAGNOSIS_REPORT.md). requiresOnDeviceRecognition runs the whole recognition
    // on-device (no network round-trip) — requires the offline model for `lang` to already be
    // downloaded (see triggerOfflineModelDownload/isOnDeviceLocaleInstalled below), or this will
    // fail fast with a 'language-not-supported'/'audio-capture' error event instead.
    requiresOnDeviceRecognition: engine === 'ondevice',
    // Same silence-tuning already proven for the native hotword burst (BensonForegroundService.kt)
    // — this session (manual mic / conversation mode / real command capture after wake word) had
    // none at all, relying on the OS default endpointer, which on this device cuts sessions off
    // after just the first word or two. Confirmed live 2026-07-16: a full "Benson, sună-o pe
    // Hannah pe WhatsApp" utterance reached the Mission Orchestrator as bare "penson" (the wake
    // word alone, mis-heard) with nothing after it — the session ended mid-sentence before the
    // rest was ever spoken.
    androidIntentOptions: {
      EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS: 800,
      EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS: 1800,
      EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS: 1200,
    },
    volumeChangeEventOptions: { enabled: true, intervalMillis: 100 },
  });
}

// Checks whether the on-device (offline) model for `lang` is already downloaded — required before
// requiresOnDeviceRecognition:true can succeed. Android System Intelligence ("com.google.android.as")
// is the service that actually owns on-device models; installedLocales is empty for other packages.
export async function isOnDeviceLocaleInstalled(lang: string): Promise<boolean> {
  try {
    const { installedLocales } = await ExpoSpeechRecognitionModule.getSupportedLocales({
      androidRecognitionServicePackage: 'com.google.android.as',
    });
    return installedLocales.includes(lang);
  } catch {
    return false;
  }
}

// Opens the system's offline-model download flow for `lang` (Android 13+ only). On Android 13
// this opens a dialog; on 14+ it downloads directly and resolves once done.
export async function triggerOfflineModelDownload(lang: string): Promise<string> {
  const result = await ExpoSpeechRecognitionModule.androidTriggerOfflineModelDownload({ locale: lang });
  return result.status;
}

export function stopRecognition() {
  if (activeEngine === 'local') {
    stopCapture().catch(() => {});
    return;
  }
  ExpoSpeechRecognitionModule.stop();
}

export function addResultListener(cb: (transcript: string, isFinal: boolean) => void) {
  resultListeners.add(cb);
  return { remove: () => resultListeners.delete(cb) };
}

export function addErrorListener(cb: (error: string) => void) {
  errorListeners.add(cb);
  return { remove: () => errorListeners.delete(cb) };
}

export function addEndListener(cb: () => void) {
  endListeners.add(cb);
  return { remove: () => endListeners.delete(cb) };
}

// Real mic level (0-1), ~10x/sec while listening, from whichever engine is active — for a
// listening indicator that reflects actual audio instead of a decorative random animation.
export function addVolumeListener(cb: (level: number) => void) {
  volumeListeners.add(cb);
  return { remove: () => volumeListeners.delete(cb) };
}

export function speakNow(text: string, options: Speech.SpeechOptions) {
  Speech.speak(text, options);
}

export function stopSpeaking() {
  Speech.stop();
}

export function getAvailableVoices() {
  return Speech.getAvailableVoicesAsync();
}
