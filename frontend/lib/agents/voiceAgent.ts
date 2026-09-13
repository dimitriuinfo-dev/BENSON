import * as Speech from 'expo-speech';
import * as FileSystem from 'expo-file-system/legacy';
import { ExpoSpeechRecognitionModule } from 'expo-speech-recognition';
import { startCapture, stopCapture, addCaptureEndListener, addVolumeChangeListener } from 'benson-audio-capture';
import { logAudioDiag } from 'benson-foreground-service';
import { transcribeLocally } from './localWhisperEngine';
import { transcribeWithOpenAI } from './openaiSTT';
import { transcribeWithGemini } from './geminiSTT';
import { transcribeWithGroq } from '../engines/stt/groqStt';
import { getEngineConfig } from '../engines/settingsStore';

// Set by app/index.tsx whenever the user's OpenAI/Gemini keys change (same ref-sync pattern
// already used for every other cross-module setting in this app). Tried in order, since the GO
// round (2026-08-27): Groq's whisper-large-v3-turbo first (see the transcribeAudio() comment
// below — a real large Whisper model, free tier, over the network instead of on this SoC), then
// Gemini (genuinely free tier, confirmed live 2026-08-24 to understand the same Romanian commands
// correctly — see geminiSTT.ts), then OpenAI (works but blocked on unconfigured billing as of
// this session), then local Whisper as the always-available last resort. Each failure falls
// through silently to the next so a bad/missing key or no network never leaves BENSON deaf.
let geminiSttKey: string | null = null;
let openaiSttKey: string | null = null;

// Byte size of the WAV that produced the most recent local-capture transcript, measured at
// onCaptureEnd. -1 means "not a local capture" (Android SpeechRecognizer streams, no file) or
// "not measured yet". app/index.tsx reads this to refuse a voice confirmation that was
// transcribed from empty/near-empty audio — a Whisper hallucination is not consent (see
// CONFIRM_REJECTED / MIN_CONFIRM_BYTES there).
let lastUtteranceBytes = -1;
export function getLastUtteranceBytes(): number {
  return lastUtteranceBytes;
}

async function measureAndLogCaptureFile(filePath: string, captureEndAt: number): Promise<number> {
  // The native module hands back a bare `/data/...` path; expo-file-system's getInfoAsync only
  // stats `file://` URIs (a bare path silently returns { exists: false }). Same prefix the Groq
  // upload already uses — one canonical URI for reading and sending.
  const uri = filePath.startsWith('file://') ? filePath : `file://${filePath}`;
  const read = async () => {
    const info = await FileSystem.getInfoAsync(uri).catch(() => null);
    return {
      exists: !!(info && info.exists),
      bytes: info && info.exists && typeof info.size === 'number' ? info.size : 0,
    };
  };
  let r = await read();
  // Native writes the WAV synchronously before firing onCaptureEnd, so this should already be the
  // real size; one short retry only in case the filesystem hasn't surfaced it yet.
  if (r.bytes === 0) { await new Promise((res) => setTimeout(res, 120)); r = await read(); }
  logAudioDiag(
    'AUDIO_FILE',
    `component=voiceAgent path=${JSON.stringify(uri)} existsAtRead=${r.exists} bytes=${r.bytes} tSinceFinishMs=${Date.now() - captureEndAt}`,
  );
  return r.bytes;
}
export function setGeminiKeyForStt(key: string | null) {
  geminiSttKey = key && key.trim() ? key.trim() : null;
}
export function setOpenAIKeyForStt(key: string | null) {
  openaiSttKey = key && key.trim() ? key.trim() : null;
}

// Rate-limit circuit breaker (product-owner-confirmed live 2026-08-25): confirmed the free-tier
// Gemini quota can stay exhausted for 11+ minutes straight (not a quick per-minute reset), and
// every single command in that window was still trying Gemini, waiting for its 429, THEN trying
// OpenAI (also blocked on unconfigured billing, another 429), and ONLY THEN falling to local
// Whisper — adding 5-10+ seconds of guaranteed-to-fail network round trips to every command
// before the user ever heard anything. Once a provider 429s, skip it entirely for a cooldown
// window instead of re-proving the same failure every single time — cuts straight to the fast
// local fallback. 5 minutes: long enough to stop hammering a still-exhausted quota, short enough
// to pick the cloud engine back up automatically once it recovers, with no user action needed.
const RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000;
let geminiRateLimitedUntil = 0;
let openaiRateLimitedUntil = 0;
let groqRateLimitedUntil = 0;
function isRateLimitError(e: unknown): boolean {
  return String(e).includes('429');
}

// GO round (2026-08-27), Task 2: Groq's whisper-large-v3-turbo becomes the default/first-tried
// STT tier for command capture — reads its config fresh from settingsStore.ts on every call
// (rather than a push-from-Settings in-memory setter like geminiSttKey/openaiSttKey below) so this
// works without app/index.tsx needing to know Groq exists at all; app/settings.tsx just writes to
// the same store this reads. Any failure logs STT_FALLBACK and falls through to the existing
// gemini -> openai -> local chain, which always reaches `local` in the end.
async function transcribeAudio(filePath: string, lang: string, captureEndAt?: number): Promise<string> {
  const now = Date.now();
  if (now >= groqRateLimitedUntil) {
    const groqConfig = await getEngineConfig('stt', 'groq').catch(() => null);
    if (groqConfig) {
      try {
        return await transcribeWithGroq(filePath, lang, groqConfig, captureEndAt);
      } catch (e) {
        if (isRateLimitError(e)) groqRateLimitedUntil = Date.now() + RATE_LIMIT_COOLDOWN_MS;
        logAudioDiag('STT_FALLBACK', `reason="${String(e)}"`);
      }
    }
  }
  if (geminiSttKey && now >= geminiRateLimitedUntil) {
    try {
      return await transcribeWithGemini(filePath, geminiSttKey, lang);
    } catch (e) {
      if (isRateLimitError(e)) geminiRateLimitedUntil = Date.now() + RATE_LIMIT_COOLDOWN_MS;
      logAudioDiag('STT_SESSION', `engine=local event=gemini_transcribe_fallback error="${String(e)}"`);
    }
  }
  if (openaiSttKey && now >= openaiRateLimitedUntil) {
    try {
      return await transcribeWithOpenAI(filePath, openaiSttKey, lang);
    } catch (e) {
      if (isRateLimitError(e)) openaiRateLimitedUntil = Date.now() + RATE_LIMIT_COOLDOWN_MS;
      logAudioDiag('STT_SESSION', `engine=local event=openai_transcribe_fallback error="${String(e)}"`);
    }
  }
  return transcribeLocally(filePath, lang, captureEndAt);
}

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
const speechStartListeners = new Set<EndCb>();
const speechEndListeners = new Set<EndCb>();

// ── Runda C3 (2026-09-08) — o rostire = o singură sesiune STT (geamănul din app/index.tsx) ─────
// app/index.tsx's doStartListening() gate is the primary fix, but there are 15+ call sites into
// this module and runLocalCapture() has no re-entrancy guard of its own — a direct double
// startRecognition() would still call the native startCapture() twice → two AudioRecord owners →
// the peakRms=32 / no_speech symptom. `recognitionInFlight` makes a second concurrent session
// structurally impossible here too. Raised in startRecognition(), dropped on every end path
// (emitEnd covers local + cloud) and eagerly in stopRecognition(). Revert: set to false (and the
// matching C3_SINGLE_SESSION_GATE in app/index.tsx).
const C3_SINGLE_SESSION_GATE = true;
let recognitionInFlight = false;

// C3-fix — the "session never closes" bug: a native onCaptureEnd that never arrives (its JS
// listener was torn down by a shared-subscription swap, or the recognizer hung) means emitEnd()
// never fires, so app/index.tsx's endSub never runs and its session gate wedges BENSON deaf.
// `endEmitted` is reset at the start of every session; stopRecognition() schedules a fallback
// emitEnd() ~400ms later if the real one still hasn't landed. Guarantees the hands-free loop
// (and the gate release) resume regardless of native behaviour.
let endEmitted = false;
let endFallbackTimer: ReturnType<typeof setTimeout> | null = null;

function emitResult(t: string, isFinal: boolean) { resultListeners.forEach((cb) => cb(t, isFinal)); }
function emitError(e: string) { errorListeners.forEach((cb) => cb(e)); }
function emitEnd() {
  if (endFallbackTimer) { clearTimeout(endFallbackTimer); endFallbackTimer = null; }
  endEmitted = true;
  recognitionInFlight = false;
  endListeners.forEach((cb) => cb());
}
function emitVolume(level: number) { volumeListeners.forEach((cb) => cb(level)); }
function emitSpeechStart() { speechStartListeners.forEach((cb) => cb()); }
function emitSpeechEnd() { speechEndListeners.forEach((cb) => cb()); }

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
// ROUND_MIC_CAPTURE_DIAGNOSTICS_1 — real native VAD boundaries (previously received but unused).
// Only wired for cloud/on-device sessions; local Whisper capture has no such event (disclosed as
// n/a in MIC_CAPTURE_DIAG rather than guessed).
ExpoSpeechRecognitionModule.addListener('speechstart', () => {
  logAudioDiag('STT_SESSION', `engine=${activeEngine} event=speechstart`);
  emitSpeechStart();
});
ExpoSpeechRecognitionModule.addListener('speechend', () => {
  logAudioDiag('STT_SESSION', `engine=${activeEngine} event=speechend`);
  emitSpeechEnd();
});
// Real-time mic level for the listening indicator (replaces a Math.random() animation) — value is
// -2..10 per the library; normalized to 0-1 here so the UI doesn't need to know which engine (or
// which native scale) produced it.
ExpoSpeechRecognitionModule.addListener('volumechange', (ev: any) => {
  emitVolume(Math.max(0, Math.min(1, (ev.value ?? -2) / 10)));
});
addVolumeChangeListener((level: number) => emitVolume(Math.max(0, Math.min(1, level))));

let activeEngine: SttEngine = 'cloud';

// Single shared onCaptureEnd subscription for BOTH command capture (runLocalCapture) and passive
// wake scanning (startWakeScan) — product-owner-directed 2026-08-23 fix for a confirmed live bug:
// each used to keep its OWN separate subscription variable (localCaptureEndSub / wakeScanEndSub).
// expo-modules-core's EventEmitter allows any number of simultaneous listeners on the same native
// event, so if timing ever let both be registered at once (confirmed live: the wake-scan loop's own
// 150ms self-restart racing against conversation-mode's reactive doStartListening() call), a SINGLE
// native onCaptureEnd fired BOTH callbacks — each independently calling transcribeLocally() on the
// same file. Logcat showed this on every cycle: two TRANSCRIBE_START lines back to back, one of the
// two always failing with local-transcribe-error, and downstream, commands processing many seconds
// late / getting spoken back multiple times as the two paths fought over the same result. Routing
// both through one shared variable makes it structurally impossible for two listeners to coexist —
// whichever call is most recent always tears down the other's subscription first.
let sharedCaptureEndSub: { remove: () => void } | null = null;
let wakeScanActive = false;

function runLocalCapture(lang: string) {
  wakeScanActive = false; // any in-flight wake-scan result must be dropped, not delivered late
  sharedCaptureEndSub?.remove();
  sharedCaptureEndSub = addCaptureEndListener(async (filePath, reason) => {
    const captureEndAt = Date.now();
    sharedCaptureEndSub?.remove();
    sharedCaptureEndSub = null;
    if (!filePath) {
      lastUtteranceBytes = 0;
      emitError(reason === 'no_speech' ? 'no-speech' : reason);
      emitEnd();
      return;
    }
    try {
      lastUtteranceBytes = await measureAndLogCaptureFile(filePath, captureEndAt);
      const text = await transcribeAudio(filePath, lang, captureEndAt);
      if (text) emitResult(text, true);
      else emitError('no-speech');
    } catch (e) {
      emitError('local-transcribe-error');
    }
    emitEnd();
  });
  startCapture().catch(() => {
    sharedCaptureEndSub?.remove();
    sharedCaptureEndSub = null;
    emitError('audio-capture');
    emitEnd();
  });
}

// ── Passive wake-word scan (FREE, on-device, zero new deps) ────────────────────────────────────
// Reuses the SAME local capture pipeline (benson-audio-capture VAD + Whisper) that already works
// for command capture on this device, instead of Android's broken SpeechRecognizer hotword loop.
// One VAD-gated capture cycle: it only records/transcribes when real speech is detected (not
// silence), transcribes it, and reports the text back. The caller (app/index.tsx) decides whether
// the text contains "Benson" and loops. Single AudioRecord owner → no mic contention, since a scan
// and a command capture never run at the same time (see sharedCaptureEndSub above).
export function startWakeScan(lang: string, onResult: (text: string) => void, onIdle: () => void) {
  wakeScanActive = true;
  sharedCaptureEndSub?.remove();
  sharedCaptureEndSub = addCaptureEndListener(async (filePath, reason) => {
    const captureEndAt = Date.now();
    sharedCaptureEndSub?.remove();
    sharedCaptureEndSub = null;
    if (!wakeScanActive) return; // stopped mid-flight — drop the result
    wakeScanActive = false;
    if (!filePath) { lastUtteranceBytes = 0; onIdle(); return; }
    try {
      lastUtteranceBytes = await measureAndLogCaptureFile(filePath, captureEndAt);
      const text = await transcribeAudio(filePath, lang, captureEndAt);
      onResult(text || '');
    } catch {
      onResult('');
    }
  });
  startCapture().catch(() => {
    sharedCaptureEndSub?.remove();
    sharedCaptureEndSub = null;
    wakeScanActive = false;
    onIdle();
  });
}

export function stopWakeScan() {
  wakeScanActive = false;
  sharedCaptureEndSub?.remove();
  sharedCaptureEndSub = null;
  stopCapture().catch(() => {});
}

export function startRecognition(lang: string, engine: SttEngine = 'cloud') {
  // C3 — a session is already live; do not open a second recorder on the same mic.
  if (C3_SINGLE_SESSION_GATE && recognitionInFlight) {
    logAudioDiag('STT_SESSION', `engine=${engine} event=start_rejected reason=already_in_flight`);
    return;
  }
  recognitionInFlight = true;
  endEmitted = false; // C3-fix — new session; a fresh end must be observed for this one
  if (endFallbackTimer) { clearTimeout(endFallbackTimer); endFallbackTimer = null; }
  activeEngine = engine;
  if (engine === 'local') {
    runLocalCapture(lang);
    return;
  }
  // SpeechRecognizer streams — no WAV file to size, so a confirmation captured this way is never
  // byte-gated (the empty-audio hallucination this guards against is specific to the local
  // capture -> Whisper/Groq route).
  lastUtteranceBytes = -1;
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
      // E2-3 (2026-09-07): fereastra de tăcere după vorbire → 1600ms (E1-3 o pusese la 800,
      // tăia comenzile cu pauză scurtă). Consistent cu SILENCE_TIMEOUT_MS din benson-audio-capture.
      // Notă: acesta e DOAR pentru motoarele 'cloud'/'ondevice' (SpeechRecognizer); motorul
      // implicit 'local' folosește constanta nativă. Revert: 800 / 600 (E1-3) sau 1800 / 1200 (pre-E1).
      EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS: 1600,
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
  recognitionInFlight = false; // C3 — release the gate immediately; the async end event still fires
  // C3-fix — guarantee an end within ~400ms even if the native onCaptureEnd never lands for this
  // session (shared-subscription swap orphaned its listener, or the recognizer hung). The real
  // emitEnd() clears this timer; a duplicate emitEnd() is harmless (endListeners are idempotent —
  // app/index.tsx's closeSttSession() no-ops after the first).
  if (endFallbackTimer) clearTimeout(endFallbackTimer);
  endFallbackTimer = setTimeout(() => {
    endFallbackTimer = null;
    if (!endEmitted) {
      logAudioDiag('STT_SESSION', `engine=${activeEngine} event=end_fallback reason=no_native_end`);
      emitEnd();
    }
  }, 400);
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

// Real native VAD boundary events (ROUND_MIC_CAPTURE_DIAGNOSTICS_1). Cloud/on-device only.
export function addSpeechStartListener(cb: () => void) {
  speechStartListeners.add(cb);
  return { remove: () => speechStartListeners.delete(cb) };
}
export function addSpeechEndListener(cb: () => void) {
  speechEndListeners.add(cb);
  return { remove: () => speechEndListeners.delete(cb) };
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
