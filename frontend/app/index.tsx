import { useEffect, useState, useRef, useMemo } from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, TextInput,
  ScrollView, Modal, Switch, Animated, Linking, AppState,
} from 'react-native';
import Constants from 'expo-constants';
import { router } from 'expo-router';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import * as Haptics from 'expo-haptics';
import * as Notifications from 'expo-notifications';
import * as Contacts from 'expo-contacts';
import * as Location from 'expo-location';
import * as Clipboard from 'expo-clipboard';
import * as SplashScreen from 'expo-splash-screen';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  startListeningService, stopListeningService, addStopRequestedListener,
  addListenRequestedListener, addWakeWordDetectedListener, bringToForeground,
  isIgnoringBatteryOptimizations, requestIgnoreBatteryOptimizations,
  pauseHotword, resumeHotword, setSystemSoundsMuted, consumeRecoveryFlag, logAudioDiag,
  setSttLanguage, setWakeWordEnabled,
  setPorcupineAccessKey, getPorcupineStatus, getActiveWakeEngine,
} from 'benson-foreground-service';
import {
  hasOverlayPermission, requestOverlayPermission, showBubble, hideBubble,
  addBubbleTappedListener, hideWakeRing,
} from 'benson-overlay';
import { isServiceEnabled as isAccessibilityEnabled, openAccessibilitySettings, openRecents } from 'benson-accessibility';
import { startAccessibilityWatch, ACCESSIBILITY_ALERT_NOTIFICATION_TAG } from '../lib/accessibilityWatchdog';
import { ensureAccessibilityReady, ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO } from '../src/core/safety';
import type { Character, AnthropicMsg, FamilyMember } from '../lib/agents/types';
import type { ContentCard } from '../lib/agents/contentTypes';
import { routeCommand, type ModelProvider } from '../lib/agents/orchestrator';
import {
  requestMicPermission, checkMicPermission, startRecognition, stopRecognition,
  addResultListener, addErrorListener, addEndListener, addVolumeListener,
  startWakeScan, stopWakeScan,
  speakNow, stopSpeaking, getAvailableVoices,
  isOnDeviceLocaleInstalled, triggerOfflineModelDownload,
  type Voice, type SttEngine,
} from '../lib/agents/voiceAgent';
import { preloadLocalWhisper, subscribeWhisperStatus, type WhisperStatus } from '../lib/agents/localWhisperEngine';
import { preloadWakeChime, playWakeChime, setWakeChimeVolume, unloadWakeChime } from '../lib/agents/wakeChime';
import { setNormalAudioMode, releaseAudioFocusMode } from '../lib/agents/audioMode';
import { speakWithOpenAI, stopOpenAITTS } from '../lib/agents/openaiTTS';
import { buildVoiceInstructions, currentTimeOfDay } from '../lib/agents/voiceInstructions';
import { startCarAutoDetection, type CarAutoDetectHandle } from '../lib/carAutoDetect';
import { startScreenBridge } from '../lib/screenBridge';
import { runMission, resumePendingTask } from '../src/core/orchestrator';
import type { MissionPlan } from '../src/core/orchestrator';
import type { TrustedContact } from '../src/core/contacts';
import { loadDeviceContacts as loadRealDeviceContacts, getContactsPermissionState, requestContactsPermission } from '../src/core/contacts';
import {
  hydrateActiveMission, getActiveMission, confirmActiveMission, cancelActiveMission,
  resolveActiveMissionFromUtterance,
} from '../src/core/mission';
import { getBondedDevices, type BluetoothDeviceInfo } from 'benson-car-bluetooth';
import { addPipModeListener } from 'benson-app-registry';
import {
  startContextWatch, BORDER_CROSSINGS,
  type ContextWatchHandle, type RoadType, type BorderCrossing,
} from '../lib/contextEngine';
import { BensonMainScreen } from '../components/BensonMainScreen';
import { AppPermissionsModal } from '../components/onboarding/AppPermissionsModal';
import { SetupWizard } from '../components/onboarding/SetupWizard';
import { SimpleSlider } from '../components/settings/SimpleSlider';
import { isOnboardingDone as isAppPermOnboardingDone } from '../lib/appPermissions';
import { isSetupWizardDone } from '../lib/setupWizard';
import {
  loadQuickContacts, saveQuickContacts, MAX_QUICK_CONTACTS, type QuickContact,
} from '../lib/quickContacts';
import { APP_REGISTRY } from '../lib/appRegistry';
import { loadApprovedAppIds, saveApprovedAppIds } from '../lib/appLauncherMemory';
import type { ParsedNote } from '../lib/agents/noteRouterAgent';
import { createCalendarEvent, saveFamilyNote, sendMessageToPerson } from '../lib/notepad/actions';
import { toggleTodoItem, clearCompletedTodoItems } from '../lib/notepad/todoList';
import { loadFeedbackItems, type FeedbackItem } from '../lib/notepad/feedbackList';
import { getConsent, setConsent, tryFlush, recordEvent, type Consent } from '../lib/analytics/eventLog';
import { classifyCommand } from '../lib/analytics/classify';
import { GOLD, NAVY, PANEL, MUTED, RED, GREEN } from '../lib/theme';

// Fire-and-forget haptic tap for buttons/interactions — swallow errors since some devices/
// emulators have no vibration motor and Haptics rejects rather than no-op-ing there.
function tap() {
  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(() => {});
}

// Keeps the screen on for the duration of an active BENSON session (listening/processing/
// waiting for confirmation/speaking) — the native hotword loop only wakes the screen briefly on
// wake-word detection (a FULL_WAKE_LOCK pulse), it doesn't hold it on through the rest of the
// interaction, so the phone was going back to sleep mid-command. Tagged (not a bare boolean) so
// activate/deactivate calls from different call sites don't stomp each other; bumped (not just
// activated once) on every new activity so a long confirmation wait doesn't expire early, and
// released automatically ~45s after the last activity rather than held forever.
const KEEP_AWAKE_TAG = 'benson-session';
const KEEP_AWAKE_IDLE_MS = 45000;
let keepAwakeIdleTimer: ReturnType<typeof setTimeout> | null = null;

function bumpSessionKeepAwake() {
  activateKeepAwakeAsync(KEEP_AWAKE_TAG).catch(() => {});
  if (keepAwakeIdleTimer) clearTimeout(keepAwakeIdleTimer);
  keepAwakeIdleTimer = setTimeout(() => {
    deactivateKeepAwake(KEEP_AWAKE_TAG);
    keepAwakeIdleTimer = null;
  }, KEEP_AWAKE_IDLE_MS);
}

// confirm[ăa]?/confirmat missing here was a real bug (confirmed live 2026-07-17): BENSON's own
// confirmation question literally says "Confirmi?", so saying the word back — "confirm" — is the
// single most natural affirmative reply a user would give, yet it never matched, leaving every
// pending mission stuck waiting forever.
const YES_PATTERN = /\b(da|yes|sigur|sure|ok|okay|pregate|pregăte[sș]te|confirm[aă]?t?)\b/i;
const VIGNETTE_KEY = 'benson_vignette_expiry_v1';

// Latency fix (user-reported 2026-07-17, "dialogul trebuie sa devina mai rapid ... peste tot"):
// getLiveContacts() was awaited unconditionally on EVERY utterance before runMission even ran —
// a full expo-contacts address-book read for a plain "cât e ceasul"/chit-chat turn that has
// nothing to do with a contact. Deliberately broad (better to over-fetch on an ambiguous phrase
// than to break a real "sună"/"scrie"/"WhatsApp" command by being too strict) — same trigger-word
// vocabulary as commandParser.ts's CALL_CONTACT_PATTERNS/WHATSAPP_CONTACT_PATTERNS/
// MESSAGE_CONTACT_PATTERN, plus generic nouns (contact/telefon/mesaj) as extra safety margin.
const MAY_NEED_CONTACTS_PATTERN =
  /\b(sun[ăa]?|apel|apeleaz[ăa]|call|ruf|whatsapp|scrie|trimite|mesaj|telefon|contact|contacte|vorbesc|d[ăa]-?i)\b/i;

// A URL the user said/typed directly — open it immediately, no confirmation, no AI round-trip.
const URL_PATTERN = /\b(?:https?:\/\/|www\.)\S+/i;

// Item 1: production contact resolution now reads the real device address book — see
// getLiveContacts() below. The old TEST_CONTACTS hardcoded stand-in survives only inside
// app/debug.tsx's own explicitly-labeled Debug Panel harness, not here.

type TtsProvider = 'device' | 'openai';

// ── Types ────────────────────────────────────────────────────────────────────
type AddressMode = 'master' | 'name';

// ── Stage 4: Memory keys ──────────────────────────────────────────────────────
const HISTORY_KEY = 'benson_history_v2';
const FACTS_KEY   = 'benson_facts_v2';
const MAX_HISTORY = 40; // max messages kept (pairs)
const MAX_FACTS   = 20;

// Patterns for "remember that …"
const REMEMBER_PATTERN =
  /\b(?:remember that|retine ca|reține că|merke dir|souviens-toi que)\b\s+(.+)/i;

// ── Family Engine ──────────────────────────────────────────────────────────────
const FAMILY_KEY = 'benson_family_v1';
const DEFAULT_FAMILY: FamilyMember[] = [
  { id: 'f1', name: 'Rareș',  relation: '', notes: '' },
  { id: 'f2', name: 'Ingrid', relation: '', notes: '' },
  { id: 'f3', name: 'Hannah', relation: '', notes: '' },
];

// ── Locale strings ────────────────────────────────────────────────────────────
const LANGUAGES = [
  { code: 'en-GB', label: 'EN' },
  { code: 'ro-RO', label: 'RO' },
  { code: 'de-DE', label: 'DE' },
  { code: 'fr-FR', label: 'FR' },
];

const GREETINGS: Record<string, string> = {
  'en-GB': 'BENSON online, {name}. What can I do for you?',
  'ro-RO': 'BENSON online, {name}. Ce fac pentru tine?',
  'de-DE': 'BENSON online, {name}. Was kann ich für dich tun?',
  'fr-FR': 'BENSON en ligne, {name}. Que puis-je faire pour vous?',
};
const CONV_ON: Record<string, string> = {
  'en-GB': 'Conversation mode on. Listening.',
  'ro-RO': 'Mod conversație activat. Ascult.',
  'de-DE': 'Gesprächsmodus aktiv. Ich höre zu.',
  'fr-FR': 'Mode conversation activé. Je vous écoute.',
};
const CONV_OFF: Record<string, string> = {
  'en-GB': 'Conversation mode off.',
  'ro-RO': 'Modul conversație dezactivat.',
  'de-DE': 'Gesprächsmodus beendet.',
  'fr-FR': 'Mode conversation désactivé.',
};

// Spoken cue after a bare "Benson" (no command in the same breath) — replaces a silent/haptic-only
// transition into command-capture mode with an audible signal the user is actually being heard.
const WAKE_LISTENING_PROMPT: Record<string, string> = {
  'en-GB': "I'm listening.",
  'ro-RO': 'Te ascult.',
  'de-DE': 'Ich höre.',
  'fr-FR': "Je vous écoute.",
};

// ── Voice-only Settings control (2026-07-14) ────────────────────────────────
// Voice-first accessibility priority: 100% of BENSON's functionality must be operable without
// ever touching the screen. These recognize Settings actions directly in handleIncomingText,
// the same position/pattern as tryStoreFact below, bypassing the touch-only Settings modal
// entirely for the actions a blind user needs most often.
const VOICE_FASTER_PATTERN = /\bvorbe[șs]te\s+mai\s+repede\b|\bspeak\s+faster\b|\bsprich\s+schneller\b/i;
const VOICE_SLOWER_PATTERN = /\bvorbe[șs]te\s+mai\s+(?:încet|incet)\b|\bspeak\s+slower\b|\bsprich\s+langsamer\b/i;
// Voice command → open the Android Recents / app-switcher screen. Anchored on the app-switcher
// meaning to avoid false hits: "aplicații recente/deschise", bare "recente", "multitasking",
// "comutator (de) aplicații", plus EN/DE fallbacks.
const SHOW_RECENTS_PATTERN = /\baplica[țt]ii(?:le)?\s+recente\b|\baplica[țt]ii(?:le)?\s+deschise\b|\b(?:arat[ăa]|deschide|comut[ăa](?:\s+la)?|mergi\s+la|vreau)\s+recente\b|\bmultitasking\b|\bcomutator(?:ul)?\s+(?:de\s+)?aplica[țt]ii\b|\brecent\s+apps\b|\bapp\s+switcher\b|\bletzte\s+apps\b/i;
// Voice command → enter Silent / Fully-Off mode. Deliberately only ENTER (exit needs the on-screen
// tap / notification, since the mic is off once silenced). "taci", "liniște", "mod silențios",
// "oprește-te complet", "gura", "fă liniște" + EN/DE fallbacks.
const SILENCE_ON_PATTERN = /\btaci\b|\blini[șs]te\b|\bf[ăa]\s+lini[șs]te\b|\bmod\s+silen[țt]ios\b|\bfii\s+silen[țt]ios\b|\bopre[șs]te-te\s+complet\b|\bgura\b|\bbe\s+quiet\b|\bshut\s+up\b|\bstop\s+listening\b|\bsei\s+still\b/i;
// Mute-only (keep listening, no sound) toggle by voice. ON must be checked before SILENCE_ON so
// "taci dar ascultă" isn't swallowed by the bare "taci" full-off rule.
const MUTE_ON_PATTERN = /\bmod\s+mut\b|\bf[ăa]r[ăa]\s+sunet\b|\bopre[șs]te\s+sunetul\b|\bnu\s+mai\s+vorbi\b|\btaci\s+dar\s+ascult[ăa]\b|\bmute\b/i;
const MUTE_OFF_PATTERN = /\bporne[șs]te\s+sunetul\b|\bcu\s+sunet\b|\bvorbe[șs]te\s+din\s+nou\b|\bunmute\b/i;
const DELETE_MEMORY_PATTERN = /\b[șs]terge\s+memoria\b|\bdelete\s+(?:my\s+)?memory\b|\bwipe\s+memory\b|\bl[öo]sche\s+(?:den\s+)?speicher\b/i;
const ADD_FAMILY_PATTERN =
  /\badaug[ăa]\s+(?:un\s+membru\s+(?:al\s+familiei\s+)?)?(?:numit\s+)?(.+)$/i.source +
  '|' +
  /\badd\s+(?:a\s+)?family\s+member\s+(?:named\s+)?(.+)$/i.source;
const ADD_FAMILY_PATTERN_RE = new RegExp(ADD_FAMILY_PATTERN, 'i');
const PASTE_KEY_PATTERN =
  /\binserea[zș][ăa]\s+cheia(?:\s+(anthropic|tavily|openai))?\b/i.source +
  '|' +
  /\bpaste\s+(?:the\s+)?(?:api\s+)?key(?:\s+(anthropic|tavily|openai))?\b/i.source;
const PASTE_KEY_PATTERN_RE = new RegExp(PASTE_KEY_PATTERN, 'i');

// ── Component ─────────────────────────────────────────────────────────────────
export default function BensonApp() {
  // UI state
  const [phase, setPhase]             = useState<'boot'|'name'|'key'|'consent'|'chat'>('boot');
  // Safety-timeout flag (product-owner-directed 2026-08-01) — set if init() hasn't finished
  // within INIT_TIMEOUT_MS. Rendered as a visible warning on the 'boot' screen instead of leaving
  // "Initialising systems..." up forever with no indication anything is wrong.
  const [initTimedOut, setInitTimedOut] = useState(false);
  const [masterName, setMasterName]   = useState('');
  const [apiKey, setApiKey]           = useState('');
  const [tavilyKey, setTavilyKey]     = useState('');
  const [openaiKey, setOpenaiKey]     = useState('');
  const [inputName, setInputName]     = useState('');
  const [inputKey, setInputKey]       = useState('');
  const [activeCard, setActiveCard]   = useState<ContentCard | null>(null);
  const [lastReply, setLastReply]     = useState('');
  const [loading, setLoading]         = useState(false);
  const [listening, setListening]     = useState(false);
  const [micVolume, setMicVolume]     = useState(0);
  // Item 4 — mirrors speakingRef (already the real TTS-in-flight signal used for logic
  // gating elsewhere in this file) as render-visible state, so the organism can react to actual
  // speech start/stop instead of a separate, parallel state machine.
  const [speaking, setSpeaking]       = useState(false);
  const [convMode, setConvMode]       = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [appPermOpen, setAppPermOpen] = useState(false);
  const [setupWizardOpen, setSetupWizardOpen] = useState(false);
  const [showQuickContacts, setShowQuickContacts] = useState(false);
  const [serviceStatus, setServiceStatus] = useState({
    mic: false, accessibility: false, gps: false, ai: false,
  });
  // In-app banner state for the accessibility service being off — kept in sync by the watchdog's
  // onStatus callback (fires on every poll + on every foreground return). When true, a persistent,
  // tappable banner is shown over the main screen with a one-tap route into system Settings, so a
  // silently auto-disabled service is impossible to miss (spoken alert + notification alone can be
  // dismissed/unheard).
  const [accessibilityDown, setAccessibilityDown] = useState(false);
  // First-launch voice-model download status (ggml-base is fetched once at runtime, not bundled).
  const [whisperStatus, setWhisperStatus] = useState<WhisperStatus>({ state: 'idle' });
  // "Silent / Fully Off" kill switch — when true, BENSON does not listen (no wake word, no active
  // capture) and makes NO sound (TTS, chimes, confirmations all suppressed). Persisted, and it
  // never self-reactivates: only an explicit user action (the on-screen toggle, the notification
  // STOP action, or the "taci" voice command to enter) changes it. Critical for meetings/public.
  const [silenced, setSilenced] = useState(false);
  const silencedRef = useRef(false);
  silencedRef.current = silenced;
  // "Doar Mut" (mute-only) — BENSON keeps LISTENING (wake word + commands still work) but makes NO
  // sound: no voice replies, no confirmation chime. Distinct from silenced (which also stops
  // listening). Persisted; toggled from the on-screen MUT pill or by voice ("mut" / "fără sunet").
  const [muted, setMuted] = useState(false);
  const mutedRef = useRef(false);
  mutedRef.current = muted;
  // Gentle-reminder bookkeeping — while the service stays off, re-speak a short cue periodically
  // (not every poll, that would nag), only when BENSON is idle in the foreground.
  const a11yLastReminderRef = useRef(0);
  // Configurable reminder interval (ms) — read inside the watchdog's onStatus closure (which is set
  // up once, so it must read a ref, not the state) and settable from Settings. Default 5 min.
  const a11yReminderMsRef = useRef(5 * 60 * 1000);
  // "Screen in screen" (2026-07-17): real PiP shrinks this SAME Activity, so BensonMainScreen
  // needs to know to switch to the logo-only layout — there's no separate native PiP screen.
  const [isInPip, setIsInPip] = useState(false);
  useEffect(() => {
    const sub = addPipModeListener(({ isInPip: v }) => setIsInPip(v));
    return () => sub.remove();
  }, []);

  // Voice settings
  const [lang, setLang]               = useState('ro-RO');
  const [voiceRate, setVoiceRate]     = useState(1.1);
  const [voicePitch, setVoicePitch]   = useState(0.85);
  const [voiceEnabled, setVoiceEnabled] = useState(true);
  const [voiceId, setVoiceId]         = useState('');
  const [voices, setVoices]           = useState<Voice[]>([]);
  const [ttsProvider, setTtsProvider] = useState<TtsProvider>('device');
  const [modelProvider, setModelProvider] = useState<ModelProvider>('claude');
  // Wake-confirmation "ding" level: 0 = off, up to 1.0 = full. User-configurable in Settings.
  const [wakeVolume, setWakeVolume]   = useState(1.0);
  // How often (minutes) the gentle spoken accessibility reminder repeats while the service is off.
  const [reminderMins, setReminderMins] = useState(5);
  // Cloud STT is confirmed unreliable on this device independent of language (see
  // AUDIO_DIAGNOSIS_REPORT.md/project_stt_broken memory). 'ondevice' (Android's built-in offline
  // recognition) was tried and also hangs/contends for the mic with the passive loop; 'local' is
  // BENSON's own AudioRecord+VAD+Whisper pipeline (benson-audio-capture + localWhisperEngine),
  // fully on-device, no Google/OpenAI dependency for listening at all. Default is 'local':
  // cloud recognition is proven unreliable on THIS device (OnePlus Nord 4 / OxygenOS — two
  // consecutive ERROR_NO_MATCH with zero partial results, live 2026-08, see
  // AUDIO_DIAGNOSIS_REPORT.md), while the on-device Whisper engine transcribed successfully on the
  // same device the same session. The cloud recognition service is an OEM black box we cannot make
  // reliable from JS, so we default off it.
  const [sttEngine, setSttEngineState] = useState<SttEngine>('local');

  // Wake-word kill switch (product-owner-directed) — default ON. When off, the native passive
  // hotword loop never opens the mic at all (gated in BensonForegroundService.kt's own
  // startHotwordLoop, not here); interaction becomes push-to-talk only via the existing manual
  // mic control. This JS-side flag exists only to drive the Settings Switch and restore its
  // visual state at boot — the actual enforcement is entirely native.
  const [wakeWordEnabled, setWakeWordEnabledState] = useState(true);

  // Picovoice Porcupine — Settings UI (product-owner-directed 2026-08-02). The AccessKey field is
  // write-only (no native getter reads it back — same idiom as the API key fields above), so it
  // starts blank every time Settings opens even after a successful save; porcupineStatus is what
  // actually confirms the save took effect. Refreshed whenever the Settings modal opens (see the
  // effect below) since both the model asset and the active engine can change without JS's
  // involvement (e.g. a rebuild, or a runtime fallback the native side decided on its own).
  const [porcupineKeyInput, setPorcupineKeyInput] = useState('');
  const [porcupineStatus, setPorcupineStatus] = useState({ hasKey: false, hasModel: false });
  const [activeWakeEngine, setActiveWakeEngineState] = useState('none');

  async function refreshPorcupineStatus() {
    try { setPorcupineStatus(await getPorcupineStatus()); } catch {}
    try { setActiveWakeEngineState(await getActiveWakeEngine()); } catch {}
  }

  useEffect(() => {
    if (settingsOpen) refreshPorcupineStatus();
  }, [settingsOpen]);

  async function savePorcupineKey() {
    tap();
    try { setPorcupineAccessKey(porcupineKeyInput.trim()); } catch {}
    setPorcupineKeyInput('');
    await refreshPorcupineStatus();
  }

  // Stage 2
  const [character, setCharacter]       = useState<Character>('butler');
  const [addressMode, setAddressMode]   = useState<AddressMode>('master');

  // Stage 4
  const [facts, setFacts]               = useState<string[]>([]);

  // Family Engine
  const [family, setFamily]             = useState<FamilyMember[]>([]);

  // Background Mode
  const [backgroundMode, setBackgroundMode] = useState(false);

  // Car Mode
  const [carMode, setCarMode] = useState(false);
  const [autoCarMode, setAutoCarMode] = useState(false);
  const [carDeviceAddress, setCarDeviceAddress] = useState('');
  const [carDeviceName, setCarDeviceName] = useState('');
  const [bondedDevices, setBondedDevices] = useState<BluetoothDeviceInfo[]>([]);

  // Quick Contacts
  const [quickContacts, setQuickContacts] = useState<QuickContact[]>([]);
  const [deviceContacts, setDeviceContacts] = useState<Contacts.ExistingContact[]>([]);

  // Governed Apps
  const [approvedAppIds, setApprovedAppIds] = useState<string[]>([]);

  // Smart Voice Notepad
  const [feedbackItems, setFeedbackItems] = useState<FeedbackItem[]>([]);

  // Developer Analytics Agent
  const [analyticsConsent, setAnalyticsConsent] = useState<Consent | null>(null);

  // Context Engine
  const [vignetteExpiry, setVignetteExpiry] = useState<Record<string, string>>({});

  // Animation
  const pulseAnim  = useRef(new Animated.Value(1)).current;

  // ── Refs for stale-closure safety ──────────────────────────────────────────
  const langRef         = useRef('ro-RO');
  // Reply (TTS) language, deliberately separate from langRef (STT recognition language). The
  // voice-only "change language" command (2026-07-14) sets ONLY this — changing STT language via
  // voice risks locking a voice-only user out entirely, since Android's recognizer only listens
  // in one locale at a time: if they then speak in whatever language they were using before,
  // it goes unrecognized and they have no way to say the words to switch back. Touch-based
  // changeLang() (Settings picker) still syncs both, since a sighted user can see/fix it there.
  const replyLangRef    = useRef('ro-RO');
  const voiceEnabledRef = useRef(true);
  const voiceRateRef    = useRef(1.1);
  const voicePitchRef   = useRef(0.85);
  const voiceIdRef      = useRef('');
  const apiKeyRef       = useRef('');
  const tavilyKeyRef    = useRef('');
  const openaiKeyRef    = useRef('');
  const ttsProviderRef  = useRef<TtsProvider>('device');
  const modelProviderRef = useRef<ModelProvider>('claude');
  const sttEngineRef = useRef<SttEngine>('local');
  const masterNameRef   = useRef('');
  const characterRef    = useRef<Character>('butler');
  const addressModeRef  = useRef<AddressMode>('master');
  const factsRef        = useRef<string[]>([]);
  const familyRef       = useRef<FamilyMember[]>([]);
  const convModeRef     = useRef(false);
  const listeningRef    = useRef(false);
  const loadingRef      = useRef(false);
  const speakingRef     = useRef(false);
  const backgroundModeRef = useRef(false);
  const serviceActiveRef  = useRef(false);
  // True while BENSON's own Activity is the foreground/active app. Conv mode's JS-driven
  // SpeechRecognizer session (doStartListening/startRecognition) is only reliable while this is
  // true — confirmed live 2026-07-18: once backgrounded (screen off, or another app in front), a
  // JS STT session can silently stop calling back at all (same class of hang as the native
  // hotword loop's burst timeout, but with nothing to detect/recover it on the JS side), leaving
  // convModeRef "on" forever with nothing actually listening. Used to (a) hand the mic back to
  // the native, foreground-service-backed hotword loop the moment the app leaves the foreground,
  // and (b) stop the JS 'end' handler from trying to re-arm its own session while backgrounded.
  const isForegroundRef   = useRef(true);
  // Wake word: passive "Benson" listening now runs natively (BensonForegroundService,
  // independent of Activity/screen state) — set true while a native-triggered command capture
  // is in flight, so the completion callback knows to resumeHotword() instead of doStartListening
  // again for continuous conv mode.
  const wakeTriggeredRef  = useRef(false);
  // FREE local-Whisper wake engine (Option A): 'local' runs a VAD-gated Whisper passive loop that
  // reuses the command capture pipeline; 'native' is the old Android SpeechRecognizer hotword loop
  // (kept as a fallback but broken on this device). Default 'local'. wakeScanningRef guards against
  // starting two overlapping scans.
  const wakeEngineRef     = useRef<'local' | 'native'>('local');
  const wakeScanningRef   = useRef(false);
  const jsSttSessionIdRef = useRef('none'); // BENSON_AUDIO session id for the current JS STT session
  const lastPartialTranscriptRef = useRef<{ sessionId: string; text: string } | null>(null);
  // For turning SILENT listen failures into feedback: what started the current STT session
  // ('manual_tap' | 'conversation_mode' | 'wake_word') and whether it produced any transcript.
  // A manual medallion tap or a wake-word command that captures NOTHING must not just die quietly
  // (the "I pressed the medallion, heard a beep, then nothing" bug) — endSub uses these to say so.
  const sttTriggerRef = useRef<'manual_tap' | 'conversation_mode' | 'wake_word'>('manual_tap');
  const sessionGotResultRef = useRef(false);
  // Safety net for a hung STT session (cloud recognizer is documented to sometimes hang with no
  // result/error/end — AUDIO_DIAGNOSIS_REPORT.md). If a session never ends on its own, this forces
  // it closed so the mic is never left stuck "listening" forever with no reaction.
  const listenWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Fragment-assembly buffer (product-owner-directed 2026-07-31, root cause confirmed live the
  // same day): every STT session — final result (resultSub) or the partial-fallback (endSub) —
  // ends at the same ~1.2-1.8s silence timeout tuned for command capture (see startRecognition's
  // androidIntentOptions in lib/agents/voiceAgent.ts). A natural mid-sentence pause ends session N
  // early, and its half of the sentence used to reach handleIncomingText immediately as its own,
  // unrelated command. See scheduleAssembledDispatch below for how this is used.
  const pendingAssemblyRef = useRef<{ text: string; timer: ReturnType<typeof setTimeout> | null }>({ text: '', timer: null });
  // Self-echo guard: confirmed live 2026-07-16 — BENSON's own TTS ("Am solicitat deschiderea
  // Waze.") was picked back up by the mic once conv-mode re-armed listening, transcribed as if it
  // were a fresh user command, and re-triggered the same mission — a self-sustaining loop. Not a
  // one-off; the real fix is acoustic (echo cancellation / not listening while the speaker plays),
  // which is out of reach here, so this is a content-based mitigation: anything BENSON says is
  // remembered for a few seconds, and an incoming transcript that closely echoes it is treated as
  // mic feedback, not a real command.
  const lastSpokenRef = useRef<{ normalized: string; at: number } | null>(null);
  // Throttle for the WaitingUser re-announce-on-foreground handler — see its own comment.
  const lastWaitingUserAnnounceRef = useRef<{ text: string; at: number } | null>(null);
  const WAITING_USER_ANNOUNCE_COOLDOWN_MS = 60000;
  const carModeRef        = useRef(false);
  const autoCarModeRef    = useRef(false);
  const carDeviceAddressRef = useRef('');
  const autoDetectHandleRef = useRef<CarAutoDetectHandle | null>(null);
  const contextWatchRef     = useRef<ContextWatchHandle | null>(null);
  const roadTypeRef         = useRef<RoadType | null>(null);
  const vignetteExpiryRef   = useRef<Record<string, string>>({});
  const pendingVignetteRef  = useRef<BorderCrossing | null>(null);
  const pendingNoteActionRef = useRef<ParsedNote | null>(null);
  const pendingMissionTaskRef = useRef<{ plan: MissionPlan; taskIndex: number } | null>(null);
  // Stage 4 – Anthropic conversation history
  const historyRef      = useRef<AnthropicMsg[]>([]);

  // Keep refs current on every render
  langRef.current        = lang;
  voiceEnabledRef.current= voiceEnabled;
  voiceRateRef.current   = voiceRate;
  voicePitchRef.current  = voicePitch;
  voiceIdRef.current     = voiceId;
  apiKeyRef.current      = apiKey;
  tavilyKeyRef.current   = tavilyKey;
  openaiKeyRef.current   = openaiKey;
  ttsProviderRef.current = ttsProvider;
  modelProviderRef.current = modelProvider;
  sttEngineRef.current = sttEngine;
  masterNameRef.current  = masterName;
  characterRef.current   = character;
  addressModeRef.current = addressMode;
  factsRef.current       = facts;
  familyRef.current      = family;

  // BENSON_AUDIO — dedicated APP_STATE trace, separate from the several convMode-gated
  // AppState listeners elsewhere in this file, so foreground/background transitions are visible
  // in the audio-chain log even when conv mode is off (correlating passive-session death with
  // backgrounding, per the audio diagnosis addendum).
  useEffect(() => {
    logAudioDiag('APP_STATE', `state=${AppState.currentState}`);
    const sub = AppState.addEventListener('change', (next) => logAudioDiag('APP_STATE', `state=${next}`));
    return () => sub.remove();
  }, []);

  // Reflect the local voice-model download/load status into a banner (first launch downloads the
  // ~141MB Whisper model once; a fresh install shows progress instead of appearing frozen).
  useEffect(() => subscribeWhisperStatus(setWhisperStatus), []);

  // ── Boot: init + STT listeners ────────────────────────────────────────────
  // Splash-screen gating + safety timeout (product-owner-directed 2026-08-01) — the audit that
  // started this session found NO SplashScreen.hideAsync() call anywhere in the app at all;
  // _layout.tsx's preventAutoHideAsync() means the native splash would then stay up forever
  // regardless of what init() does. hideSplashOnce() runs on every path here (success, throw, AND
  // the 10s timeout) so the splash can never get stuck — the 'boot' screen below already renders
  // real content ("Initialising systems..."), so once the splash lifts, there's something visible
  // underneath either way, never a blank view.
  const INIT_TIMEOUT_MS = 10000;
  useEffect(() => {
    let splashHidden = false;
    const hideSplashOnce = () => {
      if (splashHidden) return;
      splashHidden = true;
      SplashScreen.hideAsync().catch(() => {});
    };
    const timeoutId = setTimeout(() => {
      hideSplashOnce();
      setInitTimedOut(true);
    }, INIT_TIMEOUT_MS);

    init()
      .catch((e) => { console.error('[init] threw', e); })
      .finally(() => {
        clearTimeout(timeoutId);
        hideSplashOnce();
      });
    startScreenBridge();

    // Real mic level for the listening indicator (see BensonMainScreen's ListeningWaveform) —
    // user-requested 2026-07-30: it was a decorative Math.random() animation, not tied to actual
    // audio at all.
    const volumeSub = addVolumeListener((level) => setMicVolume(level));

    // result: transcript arrived. Explicitly stop recognition here (not just flip the
    // `listening` flag) so the native recognizer releases the mic/AudioRecord before TTS
    // tries to play — audio-focus contention audit finding: starting playback while the
    // recognizer hasn't fully released the mic can cause it to fail silently on some OEM
    // audio stacks (observed risk on this ColorOS device).
    const resultSub = addResultListener((transcript, isFinal) => {
      const sid = jsSttSessionIdRef.current;
      logAudioDiag(isFinal ? 'STT_FINAL' : 'STT_PARTIAL', `session=${sid} component=js_stt text="${transcript}"`);
      if (!isFinal) {
        // Defensive fallback for a real device/recognizer-service quirk confirmed live
        // 2026-07-16: EXTRA_PARTIAL_RESULTS=false was requested (interimResults:false), but this
        // device's recognition service fires onPartialResults with a complete, correct transcript
        // anyway and then ends the session (error/timeout) without ever calling the true final
        // callback. Without capturing it here, a perfectly good transcript like "Sun-o pe Baby pe
        // WhatsApp" was silently discarded every single time. Kept keyed to this exact session id
        // so a stale partial from a previous, already-handled session can never leak forward.
        if (transcript && transcript.trim()) lastPartialTranscriptRef.current = { sessionId: sid, text: transcript };
        return;
      }
      lastPartialTranscriptRef.current = null;
      try { stopRecognition(); } catch {}
      setListening(false);
      listeningRef.current = false;
      if (transcript) {
        if (looksLikeSelfEcho(transcript)) {
          logAudioDiag('TRANSCRIPT_ACCEPTED', `session=${sid} text="${transcript}" language=${langRef.current} REJECTED_self_echo=true`);
        } else {
          logAudioDiag('TRANSCRIPT_ACCEPTED', `session=${sid} text="${transcript}" language=${langRef.current}`);
          sessionGotResultRef.current = true;
          scheduleAssembledDispatch(transcript);
        }
      }
      // If empty transcript, 'end' will restart the loop
    });

    // error: don't restart here — 'end' always fires after and handles it
    const errorSub = addErrorListener((error) => {
      logAudioDiag('STT_ERROR', `session=${jsSttSessionIdRef.current} component=js_stt code=-1 name=${error}`);
      if (error === 'aborted') return;
      setListening(false);
      listeningRef.current = false;
    });

    // ── KEY FIX: 'end' drives the hands-free loop ──────────────────────────
    // 'end' fires after every recognition session (result, error, or stop).
    // If convMode is on and nothing is processing/speaking, restart.
    const endSub = addEndListener(() => {
      const sid = jsSttSessionIdRef.current;
      if (listenWatchdogRef.current) { clearTimeout(listenWatchdogRef.current); listenWatchdogRef.current = null; }
      logAudioDiag('STT_STOPPED', `session=${sid} component=js_stt convMode=${convModeRef.current} wakeTriggered=${wakeTriggeredRef.current}`);
      try { setSystemSoundsMuted(false); } catch {}
      setListening(false);
      listeningRef.current = false;

      // See resultSub's partial-fallback comment above — this session ended with a captured
      // partial transcript that never got a true final callback. Process it now instead of
      // silently dropping it; handleIncomingText's own reply flow re-arms listening afterward, so
      // this returns before the normal immediate-restart path below.
      const fallback = lastPartialTranscriptRef.current;
      if (fallback && fallback.sessionId === sid && fallback.text.trim()) {
        lastPartialTranscriptRef.current = null;
        const fallbackText = fallback.text.trim();
        if (looksLikeSelfEcho(fallbackText)) {
          logAudioDiag('TRANSCRIPT_ACCEPTED', `session=${sid} text="${fallbackText}" language=${langRef.current} source=partial_fallback REJECTED_self_echo=true`);
        } else {
          logAudioDiag('TRANSCRIPT_ACCEPTED', `session=${sid} text="${fallbackText}" language=${langRef.current} source=partial_fallback`);
          sessionGotResultRef.current = true;
          scheduleAssembledDispatch(fallbackText);
          return;
        }
      }

      // BENSON is expecting a specific reply right now (a "Confirmi?"/"Pentru cine?" question is
      // outstanding) — keep JS's own SpeechRecognizer session self-restarting so the user doesn't
      // have to say "Benson" again just to answer a question BENSON itself just asked.
      const expectingReply = !!(
        pendingMissionTaskRef.current || pendingNoteActionRef.current || pendingVignetteRef.current ||
        getActiveMission()?.state === 'WaitingConfirmation' || getActiveMission()?.state === 'WaitingUser'
      );

      if (expectingReply && convModeRef.current && isForegroundRef.current && !loadingRef.current && !speakingRef.current) {
        setTimeout(() => {
          // Re-check inside timeout — state may have changed
          if (convModeRef.current && isForegroundRef.current && !loadingRef.current && !speakingRef.current && !listeningRef.current) {
            doStartListening();
          }
        }, 700);
        return;
      }
      // Nothing was captured this cycle and nothing is pending a reply — rather than blindly
      // restarting JS's own SpeechRecognizer session again (confirmed live 2026-07-18: doing this
      // unconditionally on every empty/no-speech ending created a ~6s self-restart loop that fought
      // the native hotword loop for the mic every single cycle, degrading actual audio capture
      // enough that neither engine reliably transcribed real speech during an entire multi-minute
      // test — not just wasted battery, an actual capture-quality problem), hand the mic back to
      // the native, foreground-service-backed hotword loop instead. It already knows how to wake
      // JS back up the moment it hears "Benson" (wakeWordSub below), and a real conversational
      // reply resumes JS directly via resultSub's handleIncomingText -> speakText flow — neither of
      // those paths ever reaches this block, since loadingRef/speakingRef guards it out above.
      if (!loadingRef.current && !speakingRef.current) {
        // Explicit user attempt (medallion tap or wake-word command) that captured NOTHING: give a
        // short spoken cue instead of dying silently. This is the "pressed the medallion, heard a
        // beep/click, then no reaction at all" bug — a miss must be acknowledged. Not fired for
        // ambient conversation auto-loop cycles (those end quietly by design).
        const missedExplicit = !sessionGotResultRef.current &&
          (sttTriggerRef.current === 'manual_tap' || wakeTriggeredRef.current);
        if (wakeTriggeredRef.current) {
          wakeTriggeredRef.current = false;
          try { hideWakeRing(); } catch {}
          logAudioDiag('MODE_TRANSITION', 'from=COMMAND to=PASSIVE_WAKE reason=command_empty_or_timeout');
        }
        if (missedExplicit) {
          logAudioDiag('STT_MISS_FEEDBACK', `session=${sid} trigger=${sttTriggerRef.current} engine=${sttEngineRef.current}`);
          speak(`Nu am auzit nimic, ${getAddress()}. Mai încearcă o dată.`);
        }
        try { resumePassiveWake(); } catch {}
      }
    });

    // notification "STOP" action — user explicitly wants BENSON to stop listening entirely,
    // including passive wake word (the native side already tears down the service, and its
    // hotword loop with it).
    const stopReqSub = addStopRequestedListener(() => {
      // Notification STOP = the same hard "silent / fully off" the on-screen toggle does, so both
      // routes behave identically and it stays off until explicitly turned back on.
      enterSilentMode();
    });

    // notification "LISTEN" action — doubles as the "turn BENSON back on" half of the notification
    // toggle: if it's in silent/off mode, this exits it (mirrors tapping the on-screen red banner);
    // otherwise it's the usual manual activation fallback.
    const listenReqSub = addListenRequestedListener(() => {
      if (silencedRef.current) { exitSilentMode(); return; }
      bringToForeground();
      if (convModeRef.current) doStartListening();
    });

    // Floating bubble tap — same activation as the notification's LISTEN action.
    const bubbleTapSub = addBubbleTappedListener(() => {
      bringToForeground();
      if (convModeRef.current) doStartListening();
    });

    // The native "Benson" hotword loop (inside BensonForegroundService) heard the word — it
    // already paused itself, woke the screen, brought the app to front, and shown the wake-ring
    // overlay. Pause is a no-op safety net (already paused). If the user said the command in the
    // same breath ("Benson, deschide Waze"), native hands back the tail — process it immediately
    // instead of starting a second, empty listening session; otherwise capture the command now.
    const wakeWordSub = addWakeWordDetectedListener((commandTail) => {
      logAudioDiag('WAKE_EVENT_RECEIVED_IN_JS', `commandTail="${commandTail}" source=native`);
      handleWakeDetected(commandTail || '');
    });

    // Resume hands-free listening when the user returns to BENSON after the app was
    // backgrounded — e.g. openApp/callContact/sendWhatsApp switched to another app and the
    // user came back. Without background mode's foreground service, Android suspends the
    // mic/JS timers while backgrounded, and nothing else restarts the loop on return —
    // conversation mode would otherwise sit "on" but dead until the user manually toggles it.
    const appStateSub = AppState.addEventListener('change', (next) => {
      isForegroundRef.current = next === 'active';

      if (next !== 'active') {
        // Leaving the foreground (screen off, Home pressed, another app opened manually) — JS's
        // own conv-mode SpeechRecognizer session is not a safe mic owner here (see isForegroundRef
        // doc above: confirmed live 2026-07-18 it can silently die with no recovery), so hand the
        // mic back to the native, foreground-service-backed hotword loop instead. Without this,
        // BENSON went completely deaf until the user manually reopened the app — the exact
        // "always have to search for and open the app" complaint, since conv mode being on by
        // default meant JS grabbed the mic away from the native loop on every launch and never
        // gave it back on its own.
        if (convModeRef.current) {
          try { stopRecognition(); } catch {}
          setListening(false); listeningRef.current = false;
          try { resumePassiveWake(); } catch {}
        }
        return;
      }

      // Whenever BENSON's own Activity comes back to the foreground (returnToBenson() after a
      // WhatsApp automation, or the user just switching back manually), the bubble shown during
      // the handoff has no reason to keep floating on top of BENSON's own full-screen UI —
      // unconditional and harmless to call even if it was never shown.
      hideBubble();
      if (!convModeRef.current) return;
      // A wake-word-triggered session (native bringActivityToFront() also fires this same
      // 'active' transition) already owns its own doStartListening() call via wakeWordSub —
      // skip here so the two don't race for the mic (confirmed live 2026-07-14: this was firing
      // its own doStartListening() ~500ms after the wake-word flow's, overlapping/killing both).
      if (loadingRef.current || speakingRef.current || listeningRef.current || wakeTriggeredRef.current) return;
      setTimeout(() => {
        if (convModeRef.current && !loadingRef.current && !speakingRef.current && !listeningRef.current && !wakeTriggeredRef.current) {
          doStartListening();
        }
      }, 500);
    });

    return () => {
      resultSub.remove(); errorSub.remove(); endSub.remove(); volumeSub.remove();
      stopReqSub.remove(); listenReqSub.remove(); bubbleTapSub.remove();
      wakeWordSub.remove(); appStateSub.remove();
    };
  }, []);

  // ── Stage 6: load device TTS voices once ──────────────────────────────────
  useEffect(() => {
    getAvailableVoices().then(setVoices).catch(() => {});
  }, []);

  // Mission Governance (Waze/WhatsApp, Phase 1) — cold-start restore + resume status on return.
  // src/core/mission's store is AsyncStorage-backed, so a mission left WaitingConfirmation or
  // WaitingUser survives an app kill; this surfaces it instead of silently forgetting it.
  useEffect(() => {
    hydrateActiveMission().then((mission) => {
      if (mission && (mission.state === 'WaitingConfirmation' || mission.state === 'WaitingUser')) {
        addMessage('benson', mission.userMessage);
        speak(mission.userMessage);
        lastWaitingUserAnnounceRef.current = { text: mission.userMessage, at: Date.now() };
      }
    });
    const sub = AppState.addEventListener('change', (next) => {
      if (next !== 'active') return;
      const mission = getActiveMission();
      if (mission?.state === 'WaitingUser') {
        // Confirmed live 2026-07-16: this handler had no throttle at all. A mission genuinely
        // stuck in WaitingUser (never resolved, e.g. by the exact self-echo loop fixed elsewhere
        // this session) combined with the app rapidly losing/regaining foreground focus — up to
        // 27 times in a few minutes, likely from a separate Waze focus-stealing issue — meant this
        // re-spoke the identical stuck message dozens of times, completely bypassing the
        // STT/orchestrator echo guards (this path never touches STT at all). Same message within
        // this cooldown is treated as a duplicate announcement, not a new one.
        const last = lastWaitingUserAnnounceRef.current;
        const isDuplicate = last && last.text === mission.userMessage && Date.now() - last.at < WAITING_USER_ANNOUNCE_COOLDOWN_MS;
        if (isDuplicate) return;
        addMessage('benson', mission.userMessage);
        speak(mission.userMessage);
        lastWaitingUserAnnounceRef.current = { text: mission.userMessage, at: Date.now() };
      }
    });
    return () => sub.remove();
  }, []);

  // Guided system-permissions setup wizard: fires once, the first time the user reaches the
  // main chat screen (fresh installs and existing ones alike). Runs before the App Permissions
  // picker below — AppPermissionsModal's own visibility is gated off while this is open (see
  // render site) so the two full-screen onboarding flows never stack.
  useEffect(() => {
    if (phase !== 'chat') return;
    isSetupWizardDone().then(done => { if (!done) setSetupWizardOpen(true); });
  }, [phase]);

  // Proactive accessibility-drop detector — Android/OxygenOS can silently disable this service
  // (confirmed live, repeatedly, this session) with no push signal a normal app can rely on;
  // polling every 60s is the only reliable option. Speaks immediately AND leaves a tappable
  // notification, rather than the user only finding out once a command that needed it fails.
  useEffect(() => {
    if (phase !== 'chat') return;
    const watch = startAccessibilityWatch({
      onDropped: () => {
        a11yLastReminderRef.current = Date.now(); // the drop message counts as the first reminder
        speak('Serviciul de accesibilitate tocmai s-a dezactivat. Nu mai pot citi ecranul sau apăsa butoane până nu-l reactivezi din Setări.');
      },
      onStatus: (connected) => {
        setAccessibilityDown(!connected);
        if (connected) { a11yLastReminderRef.current = 0; return; }
        // Still off — periodic gentle reminder (interval user-configurable in Settings), but only
        // when BENSON is idle & in the foreground so it never talks over a conversation or from
        // the background. The banner (visual) stays up the whole time regardless.
        const now = Date.now();
        const idle = isForegroundRef.current && !listeningRef.current && !loadingRef.current && !speakingRef.current;
        if (idle && now - a11yLastReminderRef.current >= a11yReminderMsRef.current) {
          a11yLastReminderRef.current = now;
          speak(`Reamintire blândă, ${getAddress()}: serviciul de accesibilitate e încă oprit. Când ai un moment, reactivează-l din Setări ca să pot ajuta din nou complet.`);
        }
      },
    });
    const sub = Notifications.addNotificationResponseReceivedListener((response) => {
      if (response.notification.request.content.data?.tag === ACCESSIBILITY_ALERT_NOTIFICATION_TAG) {
        openAccessibilitySettings();
      }
    });
    return () => { watch.stop(); sub.remove(); };
  }, [phase]);

  // BENSON 4 — App Permissions onboarding: fires once, the first time the user reaches the
  // main chat screen after this feature shipped (fresh installs and existing ones alike),
  // independent of the name/key/consent setup wizard above.
  useEffect(() => {
    if (phase !== 'chat') return;
    isAppPermOnboardingDone().then(done => { if (!done) setAppPermOpen(true); });
  }, [phase]);

  // Wake word — passive "Benson" listening runs natively inside BensonForegroundService
  // (independent of this Activity/screen state), and starts itself as soon as the service
  // starts. Just need the service running as soon as the main screen is reached — requires mic
  // permission, since Android refuses background mic access to an app with no active
  // foreground service.
  useEffect(() => {
    if (phase !== 'chat') return;
    (async () => {
      if (silencedRef.current) return; // fully off — don't start the listening service
      const granted = await checkMicPermission();
      if (!granted) return;
      if (!serviceActiveRef.current) await startBackgroundService();
    })();
  }, [phase]);

  // Guardian — consumeRecoveryFlag() is true exactly once, only right after the native
  // resurrection anchor in BensonAccessibilityService brought MainActivity forward following an
  // OxygenOS process kill; a normal app open always reads false and this block does nothing.
  // Scoped to the recovery case specifically (not every boot) so a fresh install / accessibility
  // simply never having been turned on yet doesn't get treated as a "death" and interrupted with
  // an error + an auto-opened Settings screen — that path already exists, lazily, right before
  // the one feature (WhatsApp calling) that actually needs it.
  useEffect(() => {
    if (phase !== 'chat') return;
    (async () => {
      let wasRecovery = false;
      try { wasRecovery = await consumeRecoveryFlag(); } catch {}
      if (!wasRecovery) return;

      const guard = await ensureAccessibilityReady().catch(() => null);
      if (guard && guard.state !== 'enabled_connected') {
        speak(ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO);
      } else {
        speak('BENSON a revenit online.');
      }
    })();
  }, [phase]);

  const voiceGroups = useMemo(() => {
    const groups: Record<string, Voice[]> = {};
    for (const v of voices) (groups[v.language] ??= []).push(v);
    return Object.entries(groups).sort(([a], [b]) => a.localeCompare(b));
  }, [voices]);

  // ── Pulse animation while the B seal is actively listening ────────────────
  useEffect(() => {
    if (!listening) return;
    const anim = Animated.loop(Animated.sequence([
      Animated.timing(pulseAnim, { toValue: 1.13, duration: 500, useNativeDriver: true }),
      Animated.timing(pulseAnim, { toValue: 1,    duration: 500, useNativeDriver: true }),
    ]));
    anim.start();
    return () => { anim.stop(); pulseAnim.setValue(1); };
  }, [listening]);

  // ── init ─────────────────────────────────────────────────────────────────
  async function init() {
    // GPS was previously only requested lazily, the first time navigation/weather/Car Mode
    // needed it — fire it at app start instead, so the permission dialog isn't a surprise
    // mid-command. Not awaited: this shouldn't delay the rest of setup.
    Location.requestForegroundPermissionsAsync().catch(() => {});
    // Warm the wake-confirmation chime so the first "Benson" gets an instant sound, no decode lag.
    preloadWakeChime();
    // Set a SAFE, non-exclusive audio mode up front (DuckOthers) so BENSON never grabs permanent
    // exclusive audio focus and starves other apps' sound. Critical bug fix — see audioMode.ts.
    setNormalAudioMode();
    // Restore the silent/off kill switch FIRST and awaited — enterChatMode() below must see it so
    // it skips the greeting/listening when the user left BENSON muted.
    const silencedStored = (await AsyncStorage.getItem('bensonSilenced')) === 'true';
    if (silencedStored) { setSilenced(true); silencedRef.current = true; }
    // Restore mute-only state (listens, no sound).
    const mutedStored = (await AsyncStorage.getItem('bensonMuted')) === 'true';
    if (mutedStored) { setMuted(true); mutedRef.current = true; }
    // Restore the user's wake-chime volume (0 = off). Read separately from the big Promise.all below
    // to avoid touching that long destructuring; applied to the audio module immediately.
    AsyncStorage.getItem('bensonWakeChimeVolume').then((wv) => {
      if (wv == null) return;
      const n = parseFloat(wv);
      if (!isNaN(n)) { setWakeVolume(n); setWakeChimeVolume(n); }
    }).catch(() => {});
    // Restore the accessibility-reminder interval (minutes → ms), applied to the watchdog's ref.
    AsyncStorage.getItem('bensonA11yReminderMins').then((rm) => {
      if (rm == null) return;
      const m = parseInt(rm, 10);
      if (m === 5 || m === 15 || m === 30) { setReminderMins(m); a11yReminderMsRef.current = m * 60 * 1000; }
    }).catch(() => {});

    const [name, key, sl, sr, sp, ve, sc, sa, hist, fcts, bg, tk, vid, ok, tp, cm, acm, cda, cdn, vig, rl, mp, se, ww] = await Promise.all([
      AsyncStorage.getItem('masterName'),
      AsyncStorage.getItem('anthropicKey'),
      AsyncStorage.getItem('bensonLang'),
      AsyncStorage.getItem('voiceRate'),
      AsyncStorage.getItem('voicePitch'),
      AsyncStorage.getItem('voiceEnabled'),
      AsyncStorage.getItem('bensonCharacter'),
      AsyncStorage.getItem('bensonAddress'),
      AsyncStorage.getItem(HISTORY_KEY),
      AsyncStorage.getItem(FACTS_KEY),
      AsyncStorage.getItem('bensonBackgroundMode'),
      AsyncStorage.getItem('tavilyKey'),
      AsyncStorage.getItem('bensonVoiceId'),
      AsyncStorage.getItem('openaiKey'),
      AsyncStorage.getItem('bensonTtsProvider'),
      AsyncStorage.getItem('bensonCarMode'),
      AsyncStorage.getItem('bensonAutoCarMode'),
      AsyncStorage.getItem('bensonCarDeviceAddress'),
      AsyncStorage.getItem('bensonCarDeviceName'),
      AsyncStorage.getItem(VIGNETTE_KEY),
      AsyncStorage.getItem('bensonReplyLang'),
      AsyncStorage.getItem('bensonModelProvider'),
      AsyncStorage.getItem('bensonSttEngine'),
      AsyncStorage.getItem('bensonWakeWordEnabled'),
    ]);

    if (sl) { setLang(sl); langRef.current = sl; try { setSttLanguage(sl); } catch {} }
    replyLangRef.current = rl || sl || 'ro-RO';
    if (sr) { const r = parseFloat(sr); setVoiceRate(r); voiceRateRef.current = r; }
    if (sp) { const p = parseFloat(sp); setVoicePitch(p); voicePitchRef.current = p; }
    if (ve) { const v = ve === 'true'; setVoiceEnabled(v); voiceEnabledRef.current = v; }
    if (sc) { setCharacter(sc as Character); characterRef.current = sc as Character; }
    if (sa) { setAddressMode(sa as AddressMode); addressModeRef.current = sa as AddressMode; }
    if (bg) { const b = bg === 'true'; setBackgroundMode(b); backgroundModeRef.current = b; }
    if (tk) { setTavilyKey(tk); tavilyKeyRef.current = tk; }
    if (vid) { setVoiceId(vid); voiceIdRef.current = vid; }
    if (ok) { setOpenaiKey(ok); openaiKeyRef.current = ok; }
    if (tp === 'openai' || tp === 'device') { setTtsProvider(tp); ttsProviderRef.current = tp; }
    if (mp === 'openai' || mp === 'claude') { setModelProvider(mp); modelProviderRef.current = mp; }
    // STT engine restore + one-time cloud→local migration. Cloud is proven unreliable on this
    // device (see AUDIO_DIAGNOSIS_REPORT.md / the sttEngine useState comment). Existing installs
    // that still have the old 'cloud' value are moved to 'local' ONCE (no manual Settings change
    // needed); after that, an explicit re-pick of any engine in Settings is preserved.
    const sttMigrated = (await AsyncStorage.getItem('bensonSttDefaultLocalMigrated_v1')) === 'true';
    if (se === 'ondevice' || se === 'local') {
      setSttEngineState(se); sttEngineRef.current = se;
      if (se === 'local') preloadLocalWhisper();
    } else if (se === 'cloud' && !sttMigrated) {
      setSttEngineState('local'); sttEngineRef.current = 'local';
      AsyncStorage.multiSet([['bensonSttEngine', 'local'], ['bensonSttDefaultLocalMigrated_v1', 'true']]).catch(() => {});
      preloadLocalWhisper();
    } else if (se === 'cloud') {
      setSttEngineState('cloud'); sttEngineRef.current = 'cloud';
    } else {
      // Nothing stored yet — default is already 'local'; warm the Whisper model ahead of first use.
      preloadLocalWhisper();
    }
    // Wake-word kill switch — restore the visual toggle state and push it to native explicitly
    // (even when it's the same as native's own default) so JS and native never disagree about
    // what the user last chose.
    const wakeWordOn = ww !== 'false';
    setWakeWordEnabledState(wakeWordOn);
    try { setWakeWordEnabled(wakeWordOn); } catch {}
    if (cm) { const c = cm === 'true'; setCarMode(c); carModeRef.current = c; }
    if (cda) { setCarDeviceAddress(cda); carDeviceAddressRef.current = cda; }
    if (cdn) { setCarDeviceName(cdn); }
    if (acm === 'true') {
      setAutoCarMode(true); autoCarModeRef.current = true;
      startDetection();
    }
    if (vig) {
      try {
        const parsed: Record<string, string> = JSON.parse(vig);
        setVignetteExpiry(parsed);
        vignetteExpiryRef.current = parsed;
      } catch {}
    }
    if (carModeRef.current) startContextTracking();

    // Stage 4 – restore history
    if (hist) {
      try {
        const parsed: AnthropicMsg[] = JSON.parse(hist);
        historyRef.current = parsed;
        const lastAssistant = [...parsed].reverse().find(m => m.role === 'assistant');
        if (lastAssistant) setLastReply(lastAssistant.content);
      } catch {}
    }
    if (fcts) {
      try {
        const parsed: string[] = JSON.parse(fcts);
        setFacts(parsed);
        factsRef.current = parsed;
      } catch {}
    }

    // Family Engine – load or seed defaults
    const fam = await AsyncStorage.getItem(FAMILY_KEY);
    if (fam) {
      try {
        const parsed: FamilyMember[] = JSON.parse(fam);
        setFamily(parsed);
        familyRef.current = parsed;
      } catch {}
    } else {
      setFamily(DEFAULT_FAMILY);
      familyRef.current = DEFAULT_FAMILY;
      await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(DEFAULT_FAMILY));
    }

    setQuickContacts(await loadQuickContacts());
    setApprovedAppIds(await loadApprovedAppIds());
    setFeedbackItems(await loadFeedbackItems());

    const consent = await getConsent();
    setAnalyticsConsent(consent);
    tryFlush().catch(() => {});

    if (name) {
      setMasterName(name); masterNameRef.current = name;
      if (key) { setApiKey(key); apiKeyRef.current = key; }
      // Anthropic key is required again (direct calls, no Supabase relay). If it's missing (e.g. an
      // install from the proxy era that never stored one), collect it before entering chat.
      if (!key) {
        setPhase('key');
      } else if (consent === null) {
        setPhase('consent');
      } else {
        setPhase('chat');
        setTimeout(() => enterChatMode(name, sl || 'en-GB', ve !== 'false',
          sr ? parseFloat(sr) : 1.1, sp ? parseFloat(sp) : 0.85), 600);
      }
    } else {
      setTimeout(() => setPhase('name'), 2500);
    }
  }

  // ── Stage 4: history persistence ─────────────────────────────────────────
  async function appendHistory(userMsg: string, assistantMsg: string) {
    const updated: AnthropicMsg[] = [
      ...historyRef.current,
      { role: 'user' as const,      content: userMsg },
      { role: 'assistant' as const, content: assistantMsg },
    ].slice(-MAX_HISTORY);
    historyRef.current = updated;
    await AsyncStorage.setItem(HISTORY_KEY, JSON.stringify(updated));
  }

  async function appendFact(fact: string) {
    const updated = [...factsRef.current, fact].slice(-MAX_FACTS);
    setFacts(updated);
    factsRef.current = updated;
    await AsyncStorage.setItem(FACTS_KEY, JSON.stringify(updated));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  function getAddress(): string {
    return addressModeRef.current === 'master' ? 'Master' : (masterNameRef.current || 'Master');
  }

  // Benson's replies drive the discreet caption bar; user input isn't displayed (no chat log).
  function addMessage(role: string, text: string) {
    if (role === 'benson') setLastReply(text);
  }

  // Lowercase, strips diacritics and non-alphanumeric characters — good enough to compare a
  // spoken sentence against a transcribed one despite STT's own accent/punctuation noise.
  function normalizeForEcho(text: string): string {
    return text
      .normalize('NFD').replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function rememberSpoken(text: string) {
    lastSpokenRef.current = { normalized: normalizeForEcho(text), at: Date.now() };
  }

  // True if `transcript` looks like BENSON hearing its own recent TTS rather than a real command
  // — either string containing the other (STT often catches only part of a long sentence), within
  // a window after BENSON finished speaking.
  // Widened TWICE from an original 6000ms/substring-only version after watching a real live loop
  // 2026-07-16 leak through repeatedly: BENSON said "Am solicitat deschiderea Waze.", the mic
  // heard "am solicitat deschiderea oazei" (STT mis-hears "Waze" as "oazei") — a strict substring
  // check never matched since the strings differ at the end (fixed with word-overlap), and even
  // after that fix the loop's own real per-cycle timing (mic re-arm + STT session length) varied
  // 12-40+ seconds in practice — periodically exceeding first a 6s then a 12s window and firing a
  // genuine new mission each time it did. 60s comfortably exceeds every observed cycle gap; a
  // legitimate repeat of the exact same command a full minute later is rare enough to accept the
  // small risk of suppressing it, against a loop that otherwise re-opens Waze indefinitely.
  const ECHO_WINDOW_MS = 60000;
  function looksLikeSelfEcho(transcript: string): boolean {
    const last = lastSpokenRef.current;
    if (!last) return false;
    if (Date.now() - last.at > ECHO_WINDOW_MS) return false;
    const norm = normalizeForEcho(transcript);
    if (norm.length < 4) return false;
    if (last.normalized.includes(norm) || norm.includes(last.normalized)) return true;

    const spokenWords = new Set(last.normalized.split(' ').filter((w) => w.length > 1));
    const heardWords = norm.split(' ').filter((w) => w.length > 1);
    if (spokenWords.size === 0 || heardWords.length === 0) return false;
    const overlap = heardWords.filter((w) => spokenWords.has(w)).length;
    return overlap / heardWords.length >= 0.6;
  }

  // Fragment-assembly window: 400-600ms was the specified range; 500ms chosen as the midpoint.
  // Not tuned against a live measurement (that would need the same kind of live capture the
  // 800/1800/1200ms silence values above were tuned against, deliberately not touched here) —
  // picked because it's short enough that a sentence spoken in one breath (a single session,
  // nothing to assemble) only ever pays this once before its otherwise-unchanged reply timing,
  // while still giving a genuine mid-sentence continuation a real window to land in. Does NOT
  // change when or whether a following STT session starts — that's mic-handoff/wake-word timing
  // (endSub's resumeHotword()/doStartListening() branches, out of scope here); this only reacts to
  // whatever the existing logic already restarts within the window. See the report for where that
  // leaves a gap (the endSub 'expectingReply' restart is delayed 700ms — longer than this window).
  const FRAGMENT_ASSEMBLY_WINDOW_MS = 500;

  // Buffers a transcript chunk instead of dispatching it to handleIncomingText immediately. If
  // another chunk arrives (from a new STT session) before the window elapses, it's appended with
  // a space and the window resets; only once the window elapses with nothing further does the
  // accumulated text — one sentence, not a lone fragment — reach the parser. Called from the same
  // two places that used to call handleIncomingText(transcript) directly: resultSub's final-result
  // branch and endSub's partial-fallback branch (both below). Deliberately NOT wired into
  // wakeWordSub's same-breath commandTail handoff further down — see the report for why.
  function scheduleAssembledDispatch(chunk: string) {
    const trimmedChunk = chunk.trim();
    if (!trimmedChunk) return;
    const pending = pendingAssemblyRef.current;
    pending.text = pending.text ? `${pending.text} ${trimmedChunk}` : trimmedChunk;
    if (pending.timer) clearTimeout(pending.timer);
    pending.timer = setTimeout(() => {
      const assembled = pendingAssemblyRef.current.text;
      pendingAssemblyRef.current = { text: '', timer: null };
      if (assembled) handleIncomingText(assembled);
    }, FRAGMENT_ASSEMBLY_WINDOW_MS);
  }

  function speakOnDevice(text: string, onFinished?: () => void) {
    // Watchdog: confirmed live 2026-07-30 — this device's system TTS engine can silently never
    // fire onDone/onError/onStopped at all (same category of unreliability already proven for its
    // SpeechRecognizer). Without a fallback, speakingRef stays stuck true forever, and
    // doStartListening()'s guard (`|| speakingRef.current`) then silently no-ops on every future
    // call — BENSON just goes deaf with zero log trace, since the guard returns before the first
    // log line. ~110ms/word (slow-ish spoken pace) + 4s buffer, so real long replies aren't cut
    // short; only a genuinely stuck callback trips this.
    let settled = false;
    const wordCount = text.trim().split(/\s+/).filter(Boolean).length;
    const watchdogMs = Math.max(6000, wordCount * 110 + 4000);
    const settle = () => {
      if (settled) return;
      settled = true;
      if (watchdogTimer) clearTimeout(watchdogTimer);
      speakingRef.current = false; setSpeaking(false); onFinished?.();
    };
    const watchdogTimer = setTimeout(() => {
      logAudioDiag('TTS_WATCHDOG_TIMEOUT', `wordCount=${wordCount} timeoutMs=${watchdogMs}`);
      settle();
    }, watchdogMs);
    speakNow(text, {
      language: replyLangRef.current,
      pitch:    voicePitchRef.current,
      rate:     voiceRateRef.current,
      voice:    voiceIdRef.current || undefined,
      onDone:    () => settle(),
      onError:   () => settle(),
      onStopped: () => { if (!settled) { settled = true; clearTimeout(watchdogTimer); speakingRef.current = false; setSpeaking(false); } },
    });
  }

  // Voice delivery instructions for gpt-4o-mini-tts — sent on every OpenAI TTS call.
  // Pass an override (e.g. a detected family-member speaker) for a specific reply;
  // otherwise falls back to ambient context (driving urgency, time of day).
  function currentVoiceInstructions(override?: { speaker?: string; urgency?: 'low' | 'normal' | 'high' }) {
    return buildVoiceInstructions({
      urgency:   override?.urgency ?? (roadTypeRef.current === 'highway' ? 'high' : 'normal'),
      speaker:   override?.speaker,
      timeOfDay: currentTimeOfDay(),
    });
  }

  // TTS with onFinished callback — drives conv loop. Uses OpenAI TTS when selected,
  // falling back to the on-device voice automatically if it fails (no key/network).
  function speakText(text: string, onFinished?: () => void, instructions?: string) {
    if (silencedRef.current || mutedRef.current) { onFinished?.(); return; }
    if (!voiceEnabledRef.current) { onFinished?.(); return; }
    rememberSpoken(text);
    bumpSessionKeepAwake();
    stopSpeaking();
    speakingRef.current = true; setSpeaking(true);
    if (ttsProviderRef.current === 'openai' && openaiKeyRef.current) {
      speakWithOpenAI(
        text, openaiKeyRef.current, 'onyx',
        () => { speakingRef.current = false; setSpeaking(false); onFinished?.(); },
        instructions ?? currentVoiceInstructions(),
      ).catch(() => speakOnDevice(text, onFinished));
    } else {
      speakOnDevice(text, onFinished);
    }
  }

  // Fire-and-forget TTS (used outside conv loop)
  function speak(text: string, l = replyLangRef.current, enabled = voiceEnabledRef.current,
    rate = voiceRateRef.current, pitch = voicePitchRef.current) {
    if (silencedRef.current || mutedRef.current) return;
    if (!enabled) return;
    rememberSpoken(text);
    bumpSessionKeepAwake();
    if (ttsProviderRef.current === 'openai' && openaiKeyRef.current) {
      speakWithOpenAI(text, openaiKeyRef.current, 'onyx', undefined, currentVoiceInstructions())
        .catch(() => speakNow(text, { language: l, pitch, rate, voice: voiceIdRef.current || undefined }));
    } else {
      speakNow(text, { language: l, pitch, rate, voice: voiceIdRef.current || undefined });
    }
  }

  // Plays streamed reply sentences back-to-back as they arrive, instead of waiting
  // for the whole reply — the first sentence starts speaking while Claude is still
  // generating the rest. `onAllDone` fires once every queued sentence has finished
  // playing AND the caller has signalled no more are coming (finish()).
  function createSentenceSpeaker(onAllDone: () => void, instructions?: string) {
    const queue: string[] = [];
    let playing = false;
    let finished = false;

    function tryPlayNext() {
      if (playing) return;
      if (queue.length === 0) {
        if (finished) onAllDone();
        return;
      }
      playing = true;
      const sentence = queue.shift()!;
      speakText(sentence, () => { playing = false; tryPlayNext(); }, instructions);
    }

    return {
      push(sentence: string) { queue.push(sentence); tryPlayNext(); },
      finish() { finished = true; tryPlayNext(); },
    };
  }

  // Best-effort "who is this reply about" signal for buildVoiceInstructions — there's
  // no real speaker-diarization input, so this just checks whether a known family
  // member's name appears in what the user said.
  function detectSpeaker(msg: string, members: FamilyMember[]): string | undefined {
    const lower = msg.toLowerCase();
    return members.find(f => f.name && new RegExp(`\\b${f.name.toLowerCase()}\\b`).test(lower))?.name;
  }

  // ── Stage 6: voice selection ───────────────────────────────────────────────
  async function selectVoice(id: string) {
    setVoiceId(id); voiceIdRef.current = id;
    await AsyncStorage.setItem('bensonVoiceId', id);
  }

  function previewVoice(id: string, lang: string) {
    stopSpeaking();
    speakNow(`This is how I sound, ${getAddress()}.`, {
      language: lang, voice: id, rate: voiceRateRef.current, pitch: voicePitchRef.current,
    });
  }

  function greet(name: string, l: string, enabled: boolean, rate: number, pitch: number) {
    const addr = addressModeRef.current === 'master' ? 'Master' : name;
    const msg  = (GREETINGS[l] || GREETINGS['en-GB']).replace('{name}', addr);
    addMessage('benson', msg);
    if (!enabled) { speak(msg, l, enabled, rate, pitch); return; }
    speakText(msg, () => { if (convModeRef.current) doStartListening(); });
  }

  // Conversation mode is on by default — no button needed. Called whenever the
  // app enters the main 'chat' screen, so it's always listening after the greeting.
  function enterChatMode(name: string, l: string, enabled: boolean, rate: number, pitch: number) {
    // If the user left BENSON in silent/off mode, respect it on boot: no greeting, no listening.
    if (silencedRef.current) { convModeRef.current = false; setConvMode(false); return; }
    convModeRef.current = true;
    setConvMode(true);
    if (backgroundModeRef.current) startBackgroundService();
    greet(name, l, enabled, rate, pitch);
  }

  // ── Setup screens actions ─────────────────────────────────────────────────
  async function saveName() {
    if (!inputName.trim()) return;
    await AsyncStorage.setItem('masterName', inputName.trim());
    setMasterName(inputName.trim()); masterNameRef.current = inputName.trim();
    // BENSON now calls Anthropic directly with the user's OWN key (Supabase relay removed), so the
    // key is required again — collect it if we don't have one stored yet.
    if (!apiKeyRef.current) { setPhase('key'); return; }
    if (analyticsConsent === null) {
      setPhase('consent');
    } else {
      setPhase('chat');
      enterChatMode(inputName.trim(), lang, voiceEnabled, voiceRate, voicePitch);
    }
  }

  async function saveKey() {
    if (!inputKey.trim()) return;
    await AsyncStorage.setItem('anthropicKey', inputKey.trim());
    setApiKey(inputKey.trim()); apiKeyRef.current = inputKey.trim();
    if (analyticsConsent === null) {
      setPhase('consent');
    } else {
      setPhase('chat');
      enterChatMode(masterName || inputName, lang, voiceEnabled, voiceRate, voicePitch);
    }
  }

  async function decideConsent(value: Consent) {
    await setConsent(value);
    setAnalyticsConsent(value);
    setPhase('chat');
    enterChatMode(masterName || inputName, lang, voiceEnabled, voiceRate, voicePitch);
  }

  // Settings → "Ce trimite Benson" toggle — same consent, no onboarding-phase side effects.
  async function updateAnalyticsConsent(value: Consent) {
    await setConsent(value);
    setAnalyticsConsent(value);
  }

  // ── Settings / Service status — live, non-prompting checks for the ⚙️ panel ──
  async function checkServiceStatus() {
    const [mic, gps] = await Promise.all([
      checkMicPermission().catch(() => false),
      Location.getForegroundPermissionsAsync().then(p => p.granted).catch(() => false),
    ]);
    let accessibility = false;
    try { accessibility = await isAccessibilityEnabled(); } catch {}
    // BENSON calls Anthropic/OpenAI directly with the user's own key now (Supabase relay removed),
    // so "AI ready" means an Anthropic key is actually stored.
    setServiceStatus({ mic, accessibility, gps, ai: !!apiKeyRef.current });
  }

  function openSettings() {
    setSettingsOpen(true);
    loadFeedbackItems().then(setFeedbackItems);
    checkServiceStatus();
  }

  // ── Settings actions ──────────────────────────────────────────────────────
  async function changeLang(l: string) {
    setLang(l); langRef.current = l;
    try { setSttLanguage(l); } catch {}
    // Touch path syncs both STT and TTS language — a sighted user picking this in Settings can
    // see and immediately correct it if it's wrong, unlike the voice-only command (below).
    replyLangRef.current = l;
    await AsyncStorage.setItem('bensonLang', l);
    await AsyncStorage.setItem('bensonReplyLang', l);
  }

  // Wake-confirmation chime volume (0 = off). Applied live, persisted, and previewed (unless off).
  async function changeWakeVolume(v: number) {
    setWakeVolume(v);
    setWakeChimeVolume(v);
    await AsyncStorage.setItem('bensonWakeChimeVolume', v.toString());
    if (v > 0 && !silencedRef.current) playWakeChime();
  }

  // How often the gentle spoken accessibility reminder repeats (5/15/30 min). Applied live via ref.
  async function changeReminderMins(m: number) {
    setReminderMins(m);
    a11yReminderMsRef.current = m * 60 * 1000;
    await AsyncStorage.setItem('bensonA11yReminderMins', m.toString());
  }

  async function updateRate(r: number) {
    setVoiceRate(r); voiceRateRef.current = r;
    await AsyncStorage.setItem('voiceRate', r.toString());
    speakNow('This is my voice speed now.', { language: replyLangRef.current, rate: r, pitch: voicePitchRef.current, voice: voiceIdRef.current || undefined });
  }

  async function updatePitch(p: number) {
    setVoicePitch(p); voicePitchRef.current = p;
    await AsyncStorage.setItem('voicePitch', p.toString());
    speakNow('This is my voice tone now.', { language: replyLangRef.current, rate: voiceRateRef.current, pitch: p, voice: voiceIdRef.current || undefined });
  }

  async function toggleVoice(v: boolean) {
    setVoiceEnabled(v); voiceEnabledRef.current = v;
    await AsyncStorage.setItem('voiceEnabled', v.toString());
  }

  // Wake-word kill switch — the actual mic-never-opens enforcement lives entirely in native
  // (BensonForegroundService.kt's startHotwordLoop), gated on the same SharedPreferences key this
  // pushes to. This just persists the choice and syncs the toggle's visual state.
  async function toggleWakeWord(v: boolean) {
    setWakeWordEnabledState(v);
    await AsyncStorage.setItem('bensonWakeWordEnabled', v.toString());
    try { setWakeWordEnabled(v); } catch {}
  }

  async function changeCharacter(c: Character) {
    setCharacter(c); characterRef.current = c;
    await AsyncStorage.setItem('bensonCharacter', c);
  }

  async function changeAddressMode(a: AddressMode) {
    setAddressMode(a); addressModeRef.current = a;
    await AsyncStorage.setItem('bensonAddress', a);
  }

  async function saveApiKeys() {
    const ak = apiKey.trim();
    const tk = tavilyKey.trim();
    const ok = openaiKey.trim();
    apiKeyRef.current = ak;
    tavilyKeyRef.current = tk;
    openaiKeyRef.current = ok;
    await AsyncStorage.multiSet([
      ['anthropicKey', ak], ['tavilyKey', tk], ['openaiKey', ok],
    ]);
    addMessage('benson', `API keys updated, ${getAddress()}.`);
  }

  async function toggleTtsProvider(p: TtsProvider) {
    setTtsProvider(p); ttsProviderRef.current = p;
    await AsyncStorage.setItem('bensonTtsProvider', p);
  }

  async function toggleModelProvider(p: ModelProvider) {
    setModelProvider(p); modelProviderRef.current = p;
    await AsyncStorage.setItem('bensonModelProvider', p);
  }

  async function changeSttEngine(engine: SttEngine) {
    setSttEngineState(engine); sttEngineRef.current = engine;
    await AsyncStorage.setItem('bensonSttEngine', engine);
    if (engine === 'ondevice') {
      const installed = await isOnDeviceLocaleInstalled(langRef.current);
      if (!installed) {
        addMessage('benson', `Modelul offline pentru ${langRef.current} nu e descărcat încă pe telefon — deschid descărcarea.`);
        try { await triggerOfflineModelDownload(langRef.current); } catch {}
      }
    } else if (engine === 'local') {
      preloadLocalWhisper();
    }
  }

  function previewOpenAIVoice() {
    if (!openaiKeyRef.current) {
      addMessage('benson', `I need an OpenAI API key to use that voice, ${getAddress()}.`);
      return;
    }
    stopOpenAITTS().catch(() => {});
    speakWithOpenAI(`This is how I sound, ${getAddress()}.`, openaiKeyRef.current, 'onyx', undefined, currentVoiceInstructions())
      .catch(() => addMessage('benson', `I could not reach the OpenAI voice service, ${getAddress()}.`));
  }

  async function toggleBackgroundMode(v: boolean) {
    setBackgroundMode(v); backgroundModeRef.current = v;
    await AsyncStorage.setItem('bensonBackgroundMode', v.toString());
    // Wake word ("Benson") needs the service running always now — this legacy toggle no longer
    // stops it; the notification's STOP action is the only way to actually turn it off.
  }

  // ── Family Engine ──────────────────────────────────────────────────────────
  function updateFamilyField(id: string, field: 'name' | 'relation' | 'notes', value: string) {
    setFamily(prev => prev.map(f => (f.id === id ? { ...f, [field]: value } : f)));
  }

  async function saveFamily() {
    familyRef.current = family;
    await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(family));
    addMessage('benson', `Family profiles updated, ${getAddress()}.`);
  }

  async function addFamilyMember() {
    const updated = [...family, { id: Date.now().toString(), name: '', relation: '', notes: '' }];
    setFamily(updated); familyRef.current = updated;
    await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(updated));
  }

  async function removeFamilyMember(id: string) {
    const updated = family.filter(f => f.id !== id);
    setFamily(updated); familyRef.current = updated;
    await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(updated));
  }

  // ── Car Mode auto-detection ───────────────────────────────────────────────
  async function startDetection() {
    autoDetectHandleRef.current?.stop();
    autoDetectHandleRef.current = await startCarAutoDetection({
      carDeviceAddress: carDeviceAddressRef.current,
      onAutoOn:  () => { if (!carModeRef.current) toggleCarMode(true); },
      onAutoOff: () => { if (carModeRef.current) toggleCarMode(false); },
    });
  }

  async function toggleAutoCarMode(v: boolean) {
    setAutoCarMode(v); autoCarModeRef.current = v;
    await AsyncStorage.setItem('bensonAutoCarMode', v.toString());
    if (v) {
      await startDetection();
    } else {
      autoDetectHandleRef.current?.stop();
      autoDetectHandleRef.current = null;
    }
  }

  async function loadBondedDevices() {
    try {
      const devices = await getBondedDevices();
      setBondedDevices(devices);
    } catch {}
  }

  async function selectCarDevice(device: BluetoothDeviceInfo) {
    setCarDeviceAddress(device.address); carDeviceAddressRef.current = device.address;
    setCarDeviceName(device.name);
    await AsyncStorage.multiSet([
      ['bensonCarDeviceAddress', device.address],
      ['bensonCarDeviceName', device.name],
    ]);
    if (autoCarModeRef.current) await startDetection();
  }

  // ── Quick Contacts ─────────────────────────────────────────────────────────
  async function loadDeviceContacts() {
    const { status } = await Contacts.requestPermissionsAsync();
    if (status !== 'granted') return;
    const { data } = await Contacts.getContactsAsync({
      fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Emails],
    });
    setDeviceContacts(data.filter(c => c.name));
  }

  async function toggleQuickContact(contact: Contacts.ExistingContact) {
    const exists = quickContacts.some(q => q.id === contact.id);
    let updated: QuickContact[];
    if (exists) {
      updated = quickContacts.filter(q => q.id !== contact.id);
    } else {
      if (quickContacts.length >= MAX_QUICK_CONTACTS) return;
      updated = [...quickContacts, {
        id: contact.id,
        name: contact.name ?? 'Unknown',
        phone: contact.phoneNumbers?.[0]?.number,
        email: contact.emails?.[0]?.email,
      }];
    }
    setQuickContacts(updated);
    await saveQuickContacts(updated);
  }

  // ── Governed Apps ──────────────────────────────────────────────────────────
  async function toggleAppApproval(id: string) {
    const updated = approvedAppIds.includes(id)
      ? approvedAppIds.filter(a => a !== id)
      : [...approvedAppIds, id];
    setApprovedAppIds(updated);
    await saveApprovedAppIds(updated);
  }

  // ── Smart Voice Notepad — to-do card interactivity ─────────────────────────
  async function handleToggleTodo(id: string) {
    const items = await toggleTodoItem(id);
    setActiveCard({ kind: 'todo', items });
  }

  async function handleClearCompletedTodo() {
    const items = await clearCompletedTodoItems();
    setActiveCard({ kind: 'todo', items });
  }

  // ── Context Engine ────────────────────────────────────────────────────────
  function speakOrShow(reply: string) {
    addMessage('benson', reply);
    if (convModeRef.current) {
      speakText(reply, () => { if (convModeRef.current) doStartListening(); });
    } else {
      speak(reply);
    }
  }

  function handleRoadTypeChange(type: RoadType) {
    roadTypeRef.current = type;
  }

  function handleBorderAlert(border: BorderCrossing, distanceKm: number) {
    const expiryStr = vignetteExpiryRef.current[border.countryCode];
    const valid = !!expiryStr && new Date(expiryStr).getTime() > Date.now();
    const km = Math.round(distanceKm);
    if (valid) {
      speakOrShow(`${getAddress()}, intrăm în ${border.country} în aproximativ ${km} km. Vinieta este valabilă.`);
    } else {
      pendingVignetteRef.current = border;
      speakOrShow(
        `${getAddress()}, intrăm în ${border.country} în aproximativ ${km} km. ` +
        `Nu am găsit vinietă valabilă. Doriți să o pregătesc acum?`
      );
    }
  }

  function handleBreakReminder() {
    speakOrShow(`${getAddress()}, conduceți de peste 2 ore. Ar fi bine să faceți o pauză.`);
  }

  function startContextTracking() {
    if (contextWatchRef.current) return;
    startContextWatch({
      onRoadTypeChange: handleRoadTypeChange,
      onBorderAlert:    handleBorderAlert,
      onBreakReminder:  handleBreakReminder,
    }).then(handle => { contextWatchRef.current = handle; });
  }

  function stopContextTracking() {
    contextWatchRef.current?.stop();
    contextWatchRef.current = null;
    roadTypeRef.current = null;
  }

  async function saveVignetteExpiry(countryCode: string, date: string) {
    const updated = { ...vignetteExpiry, [countryCode]: date };
    setVignetteExpiry(updated); vignetteExpiryRef.current = updated;
    await AsyncStorage.setItem(VIGNETTE_KEY, JSON.stringify(updated));
  }

  // ── Car Mode ──────────────────────────────────────────────────────────────
  async function toggleCarMode(v: boolean) {
    setCarMode(v); carModeRef.current = v;
    await AsyncStorage.setItem('bensonCarMode', v.toString());
    if (v) {
      if (!backgroundModeRef.current) await toggleBackgroundMode(true);
      if (!convModeRef.current) toggleConvMode();
      startContextTracking();
    } else {
      stopContextTracking();
    }
  }

  async function wipeMemory() {
    await AsyncStorage.multiRemove([HISTORY_KEY, FACTS_KEY]);
    historyRef.current = [];
    setFacts([]); factsRef.current = [];
    addMessage('benson', `Memory wiped, ${getAddress()}.`);
  }

  // ── STT control ───────────────────────────────────────────────────────────
  // Reverted (2026-07-09): tried leaving the system STT-start tone audible after the wake word
  // as a real "mic is ready" cue (playStartTone param). Live testing showed it made things worse,
  // not better -- logcat showed onMicrophoneDeactivated firing ~10ms after onStartListening,
  // immediately around when the tone plays, then NO_SPEECH_DETECTED. The tone competes with the
  // recognizer for the audio path on this device instead of cleanly preceding it -- that's almost
  // certainly why it was muted here in the first place. Reverted to always-muted; the STT-miss
  // problem after wake word remains open (see openaiTTS.ts / handleIncomingText history for what
  // was already tried and ruled out).
  // ── Local Whisper wake word (Option A — free, on-device, no new deps) ─────────
  // detectWakeWord: does the transcript contain "Benson"? Whisper commonly mis-hears the name, so
  // we accept a small set of near-spellings plus a fuzzy check on each token.
  const WAKE_VARIANTS = ['benson', 'bensen', 'benzon', 'bension', 'pension', 'penson', 'benton', 'bensons'];
  function detectWakeWord(text: string): boolean {
    const norm = (text || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    if (!norm.trim()) return false;
    if (WAKE_VARIANTS.some((w) => norm.includes(w))) return true;
    // fuzzy: any single token within edit-distance 1 of "benson"
    return norm.split(/[^a-z]+/).some((tok) => tok.length >= 5 && lev(tok, 'benson') <= 1);
  }
  function lev(a: string, b: string): number {
    const m = a.length, n = b.length;
    if (!m) return n; if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, i) => i);
    for (let i = 1; i <= m; i++) {
      const cur = [i];
      for (let j = 1; j <= n; j++) cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = cur;
    }
    return prev[n];
  }
  // stripWakeWord: return whatever the user said AFTER "Benson" as the command tail (so
  // "Benson, deschide Waze" runs "deschide Waze" in one breath); empty if only the wake word.
  function stripWakeWord(text: string): string {
    const idx = text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').search(/bens|benz|bent|pens/);
    if (idx < 0) return '';
    const after = text.slice(idx).replace(/^[^\s]+[\s,.:;!?-]*/, '').trim(); // drop the wake token itself
    return after;
  }

  // Single reusable wake handler — invoked by BOTH the native hotword listener AND the local
  // Whisper scan loop, so there is exactly ONE wake-handling path (no duplication).
  async function handleWakeDetected(commandTail: string) {
    if (silencedRef.current) return; // fully off — ignore wake events entirely
    // Immediate non-verbal "I heard you" — plays the instant "Benson" is recognized, before the
    // mic is handed off. Suppressed in mute-only mode (still listens, just makes no sound).
    if (!mutedRef.current) playWakeChime();
    stopWakeScan();
    wakeScanningRef.current = false;
    wakeTriggeredRef.current = true;
    bumpSessionKeepAwake();
    try { await pauseHotword(); } catch {}
    const tail = (commandTail || '').trim();
    if (tail) {
      logAudioDiag('WAKE_HANDOFF_TO_STT', 'mode=same_breath_command_tail');
      tap();
      handleIncomingText(tail);
    } else {
      logAudioDiag('WAKE_HANDOFF_TO_STT', 'mode=bare_wake_word_prompt_then_listen');
      const msg = WAKE_LISTENING_PROMPT[replyLangRef.current] || WAKE_LISTENING_PROMPT['en-GB'];
      speakText(msg, () => doStartListening());
    }
  }

  // The free passive wake loop: one VAD-gated Whisper capture; if it heard "Benson" hand off to the
  // command flow, otherwise scan again. Self-restarting. Guarded so it never overlaps a command
  // capture, TTS, or an in-flight session.
  function startLocalWakeLoop() {
    if (silencedRef.current) return;
    if (wakeEngineRef.current !== 'local') return;
    if (!serviceActiveRef.current) return;
    if (wakeScanningRef.current) return;
    if (listeningRef.current || loadingRef.current || speakingRef.current || wakeTriggeredRef.current) return;
    wakeScanningRef.current = true;
    logAudioDiag('WAKE_SCAN_START', `engine=local lang=${langRef.current}`);
    startWakeScan(
      langRef.current,
      (text) => {
        wakeScanningRef.current = false;
        if (detectWakeWord(text)) {
          logAudioDiag('WAKE_SCAN_HIT', `text="${text}"`);
          handleWakeDetected(stripWakeWord(text));
        } else {
          setTimeout(startLocalWakeLoop, 150); // not the wake word — keep listening
        }
      },
      () => { wakeScanningRef.current = false; setTimeout(startLocalWakeLoop, 150); },
    );
  }

  // Hand the mic back to passive listening after a command/return. Chooses the engine: local
  // Whisper loop (free, works on this device) or the native hotword loop (fallback).
  function resumePassiveWake() {
    if (silencedRef.current) return;
    if (wakeEngineRef.current === 'local') {
      try { pauseHotword(); } catch {} // make sure the native SpeechRecognizer loop is off
      startLocalWakeLoop();
    } else {
      try { resumeHotword(); } catch {}
    }
  }

  async function doStartListening() {
    if (silencedRef.current) return;
    if (listeningRef.current || loadingRef.current || speakingRef.current) return;
    try { stopWakeScan(); } catch {}
    wakeScanningRef.current = false;
    bumpSessionKeepAwake();
    const trigger = wakeTriggeredRef.current ? 'wake_word' : convModeRef.current ? 'conversation_mode' : 'manual_tap';
    const sessionId = `js-${Date.now()}`;
    jsSttSessionIdRef.current = sessionId;
    sttTriggerRef.current = trigger;
    sessionGotResultRef.current = false;
    logAudioDiag('STT_REQUESTED', `session=${sessionId} trigger=${trigger} component=js_stt`);
    try {
      // Unconditional, regardless of caller — previously only toggleConvMode() and the wake-word
      // handler paused the native hotword loop before starting this JS session, which meant any
      // OTHER path into doStartListening() (there are 15+ call sites) could start a JS
      // SpeechRecognizer session while the native loop was still cycling its own, both fighting
      // over the mic. Confirmed live 2026-07-16: interleaved ro-RO (JS)/de-DE (native, no
      // EXTRA_LANGUAGE set) sessions, one of which had real speech (onStartOfSpeech) cut off
      // mid-utterance by the other starting. pauseHotword() is idempotent (a no-op if the native
      // loop is already paused), so calling it here unconditionally is safe.
      //
      // Run alongside requestMicPermission() (Promise.all), not sequentially before it — confirmed
      // live 2026-07-17: awaiting these one after another added a real ~200-400ms mic-handover gap
      // on every single conversation-mode turn (pause hotword -> THEN check permission -> THEN
      // create+start the recognizer), during which a user who starts talking the instant BENSON
      // stops has the first word(s) of their command land in dead air with nothing recording yet —
      // caught live as "sună-o pe mama pe WhatsApp" transcribing as just "pe mama pe WhatsApp".
      // The two calls are independent (one pauses the OTHER recognizer, the other checks THIS
      // session's own permission) — running them concurrently costs whichever is slower, not both.
      const [, granted] = await Promise.all([
        pauseHotword().catch(() => {}),
        requestMicPermission(),
      ]);
      logAudioDiag('STT_PRECHECK', `session=${sessionId} permission=${granted} component=js_stt`);
      if (!granted) {
        const noMicMsg = `I need microphone access to hear you, ${getAddress()}.`;
        addMessage('benson', noMicMsg);
        // Always spoken, not gated behind conv-mode/settings — a blind user has no other way to
        // learn why voice input stopped working, and reading text isn't an option for them.
        speak(noMicMsg);
        setConvMode(false); convModeRef.current = false;
        return;
      }
      setListening(true); listeningRef.current = true;
      try { setSystemSoundsMuted(true); } catch {}
      logAudioDiag('RECORDER_CREATE', `session=${sessionId} component=js_stt engine=${sttEngineRef.current} lang=${langRef.current}`);
      startRecognition(langRef.current, sttEngineRef.current);
      logAudioDiag('STT_START_CALLED', `session=${sessionId} component=js_stt`);
      // Watchdog: cloud/ondevice SpeechRecognizer can hang with no end event; local capture has a
      // 60s pre-speech native timeout. Force-close if the session is still the current one and
      // still listening after the ceiling, so it can never leave the mic stuck. stopRecognition()
      // triggers the normal 'end' path, which fires the miss feedback + hands the mic back.
      if (listenWatchdogRef.current) clearTimeout(listenWatchdogRef.current);
      listenWatchdogRef.current = setTimeout(() => {
        if (listeningRef.current && jsSttSessionIdRef.current === sessionId) {
          logAudioDiag('STT_WATCHDOG', `session=${sessionId} forced_stop engine=${sttEngineRef.current}`);
          try { stopRecognition(); } catch {}
        }
      }, sttEngineRef.current === 'local' ? 65000 : 12000);
    } catch (e) {
      logAudioDiag('STT_ERROR', `session=${sessionId} component=js_stt code=-1 name=JS_EXCEPTION message="${String(e)}"`);
      try { setSystemSoundsMuted(false); } catch {}
      setListening(false); listeningRef.current = false;
      if (convModeRef.current) setTimeout(() => doStartListening(), 2000);
    }
  }

  function doStopListening() {
    try { stopRecognition(); } catch {}
    try { setSystemSoundsMuted(false); } catch {}
    setListening(false); listeningRef.current = false;
  }

  // ── Silent / Fully-Off kill switch ────────────────────────────────────────
  // Hard stop: no listening (wake word or active), no sound (TTS/chimes/confirmations). Persisted,
  // and it never self-reactivates — the guards in speak/speakText/doStartListening/startLocalWakeLoop/
  // resumePassiveWake/handleWakeDetected all bail out while silencedRef is true, so none of the
  // auto-resume paths (endSub, AppState 'active', wake events) can turn anything back on.
  async function enterSilentMode() {
    setSilenced(true); silencedRef.current = true;
    await AsyncStorage.setItem('bensonSilenced', 'true');
    convModeRef.current = false; setConvMode(false);
    // stop everything that listens
    try { stopWakeScan(); } catch {}
    wakeScanningRef.current = false;
    wakeTriggeredRef.current = false;
    try { stopRecognition(); } catch {}
    try { await pauseHotword(); } catch {}
    setListening(false); listeningRef.current = false;
    // stop everything that makes sound
    try { stopSpeaking(); } catch {}
    try { stopOpenAITTS(); } catch {}
    speakingRef.current = false; setSpeaking(false);
    try { setSystemSoundsMuted(false); } catch {}
    try { hideWakeRing(); } catch {}
    // Hard-release system audio focus so other apps' sound (video/radio) recovers immediately,
    // WITHOUT a phone restart — unload the chime + drop expo-av's background audio session.
    try { await unloadWakeChime(); } catch {}
    try { await releaseAudioFocusMode(); } catch {}
    // TRUE full stop: tear down the native foreground service (kills the persistent notification,
    // the wake-lock and any residual mic activity → no more clicks/pops) and hide the floating
    // bubble. exitSilentMode() restarts the service, so this is fully reversible with one tap.
    try { stopListeningService(); } catch {}
    serviceActiveRef.current = false;
    try { hideBubble(); } catch {}
    logAudioDiag('SILENT_MODE', 'state=on');
  }

  async function exitSilentMode() {
    setSilenced(false); silencedRef.current = false;
    await AsyncStorage.setItem('bensonSilenced', 'false');
    logAudioDiag('SILENT_MODE', 'state=off');
    try { await setNormalAudioMode(); } catch {} // restore normal (ducking) playback mode
    preloadWakeChime();
    if (!serviceActiveRef.current) await startBackgroundService();
    try { resumePassiveWake(); } catch {} // back to hands-free wake-word listening
    speak(`Am revenit, ${getAddress()}.`); // sound is allowed again — confirm the return
  }

  function toggleSilence() {
    if (silencedRef.current) exitSilentMode();
    else enterSilentMode();
  }

  // "Doar Mut" — toggle sound off/on WITHOUT stopping listening. Wake word + commands keep working;
  // BENSON just won't speak or chime. Cuts any in-progress speech immediately when turning on.
  async function toggleMute() {
    const next = !mutedRef.current;
    setMuted(next); mutedRef.current = next;
    await AsyncStorage.setItem('bensonMuted', next.toString());
    if (next) {
      try { stopSpeaking(); } catch {}
      try { stopOpenAITTS(); } catch {}
      speakingRef.current = false; setSpeaking(false);
      try { await releaseAudioFocusMode(); } catch {} // no sound while muted → let other apps play freely
      logAudioDiag('MUTE_MODE', 'state=on');
    } else {
      try { await setNormalAudioMode(); } catch {}
      logAudioDiag('MUTE_MODE', 'state=off');
      speak(`Sonorul e pornit din nou, ${getAddress()}.`);
    }
  }

  // ── Background Mode: foreground service control ──────────────────────────
  async function startBackgroundService() {
    try {
      const settings = await Notifications.getPermissionsAsync();
      if (settings.status !== 'granted') await Notifications.requestPermissionsAsync();
    } catch {}
    try {
      startListeningService('BENSON', 'BENSON is listening — tap LISTEN to talk, or open the app.');
      serviceActiveRef.current = true;
    } catch {}
    try {
      if (!(await isIgnoringBatteryOptimizations())) requestIgnoreBatteryOptimizations();
    } catch {}
    // Floating bubble — a visible, always-there way back into BENSON while another app has
    // focus. Only shows if the user has already granted "draw over other apps"; never prompts
    // mid-conversation (that's a jarring system settings redirect) — see openSettings' App
    // Permissions flow / Settings panel for where the user grants it ahead of time.
    try {
      if (await hasOverlayPermission()) showBubble();
    } catch {}
  }

  // ── Conversation Mode toggle ──────────────────────────────────────────────
  async function toggleConvMode() {
    const next = !convModeRef.current;
    // Update ref FIRST so 'end' handler sees correct state immediately
    convModeRef.current = next;
    setConvMode(next);

    if (next) {
      // The native hotword loop may currently hold the recognizer — pause it first and AWAIT
      // confirmation it actually stopped (pauseHotword() now resolves only once the native loop
      // has released the mic) before doStartListening() below starts JS's own recognizer. Without
      // the await, the two could briefly race for the mic and both fail (confirmed live 2026-07-14:
      // repeated NO_SPEECH_DETECTED loops traced to exactly this race).
      console.log('[ConvMode] pauseHotword: calling');
      try { await pauseHotword(); console.log('[ConvMode] pauseHotword: resolved'); } catch (e) { console.log('[ConvMode] pauseHotword: threw', e); }
      try { hideWakeRing(); } catch {}
      const msg = CONV_ON[replyLangRef.current] || CONV_ON['en-GB'];
      addMessage('benson', msg);
      // Background service (persistent notification + wake lock) accompanies conversation
      // mode too — it's likely already running for wake word, this is just a safety net.
      if (!serviceActiveRef.current) startBackgroundService();
      // Start listening after greeting TTS finishes
      speakText(msg, () => { if (convModeRef.current) doStartListening(); });
    } else {
      doStopListening();
      stopSpeaking(); stopOpenAITTS().catch(() => {}); speakingRef.current = false; setSpeaking(false);
      // Background service stays on — wake word ("Benson") still needs it. Only the STOP
      // notification action actually tears it down.
      const msg = CONV_OFF[replyLangRef.current] || CONV_OFF['en-GB'];
      addMessage('benson', msg);
      speak(msg);
      try { resumePassiveWake(); } catch {}
    }
  }

  // Medallion tap handler — confirmed live 2026-07-30: conversation mode is ON by default
  // (enterChatMode sets it on boot), so a plain toggleConvMode() on tap actually turned it OFF on
  // the very first tap, and back on on the next — the user just wanted "listen now", not a
  // toggle. If already in conv mode, start a listening session directly instead of flipping it
  // off; only fall back to toggleConvMode() when conv mode is genuinely off.
  async function handleMedallionTap() {
    if (silencedRef.current) return; // fully off — the red banner is the way back on
    if (convModeRef.current) {
      if (!listeningRef.current && !loadingRef.current && !speakingRef.current) {
        doStartListening();
      }
      return;
    }
    await toggleConvMode();
  }

  // ── Stage 4: Fact extraction ──────────────────────────────────────────────
  // Voice-only Settings control — every action here always speaks its result (never text-only),
  // since the whole point is 100% voice operability with zero screen interaction required.
  async function trySettingsVoiceCommand(text: string): Promise<boolean> {
    // Language change is deliberately touch-only (Settings picker, changeLang) for now — a
    // voice-triggered version caused a real lockout live (2026-07-14: STT and reply language
    // were coupled, so changing language by voice could leave the user unable to say the words
    // to switch back). Revisit as its own, separately-tested piece of work, not bundled here.

    if (VOICE_FASTER_PATTERN.test(text) || VOICE_SLOWER_PATTERN.test(text)) {
      const delta = VOICE_FASTER_PATTERN.test(text) ? 0.15 : -0.15;
      const next = Math.max(0.5, Math.min(2.0, voiceRateRef.current + delta));
      await updateRate(next); // updateRate already speaks its own confirmation
      return true;
    }

    // "taci" / "mod silențios" — full silent/off. No spoken confirmation (silence is the point);
    // enterSilentMode stops all listening + sound and it stays off until an explicit tap to return.
    if (SILENCE_ON_PATTERN.test(text) && !MUTE_ON_PATTERN.test(text)) {
      await enterSilentMode();
      return true;
    }

    // Mute-only toggle (keep listening, no sound). Checked here so it works by voice in both
    // directions — unlike full silent, the mic stays on so "pornește sunetul" can turn it back.
    if (MUTE_OFF_PATTERN.test(text)) {
      if (mutedRef.current) await toggleMute(); // toggleMute speaks the confirmation
      return true;
    }
    if (MUTE_ON_PATTERN.test(text)) {
      if (!mutedRef.current) await toggleMute();
      return true;
    }

    // "arată aplicațiile recente" — open the system Recents/app-switcher. Needs the accessibility
    // service connected (GLOBAL_ACTION_RECENTS is issued from it); honest reply if it's off.
    if (SHOW_RECENTS_PATTERN.test(text)) {
      const enabled = await isAccessibilityEnabled().catch(() => false);
      if (!enabled) {
        const reply = `Am nevoie de Serviciul de Accesibilitate activ ca să deschid aplicațiile recente, ${getAddress()}.`;
        addMessage('benson', reply); speak(reply);
        return true;
      }
      const ok = await openRecents().catch(() => false);
      const reply = ok
        ? 'Am deschis aplicațiile recente.'
        : `Nu am reușit să deschid aplicațiile recente, ${getAddress()}.`;
      addMessage('benson', reply); speak(reply);
      return true;
    }

    if (DELETE_MEMORY_PATTERN.test(text)) {
      await wipeMemory();
      const reply = `Memory wiped, ${getAddress()}.`;
      speak(reply);
      return true;
    }

    const familyMatch = text.match(ADD_FAMILY_PATTERN_RE);
    if (familyMatch) {
      const name = (familyMatch[1] || familyMatch[2] || '').trim();
      if (!name) {
        const reply = `Who should I add, ${getAddress()}?`;
        addMessage('benson', reply); speak(reply);
        return true;
      }
      const updated = [...family, { id: Date.now().toString(), name, relation: '', notes: '' }];
      setFamily(updated); familyRef.current = updated;
      await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(updated));
      const reply = `Added ${name} to your family profiles, ${getAddress()}.`;
      addMessage('benson', reply); speak(reply);
      return true;
    }

    const keyMatch = text.match(PASTE_KEY_PATTERN_RE);
    if (keyMatch) {
      const which = (keyMatch[1] || keyMatch[2] || 'anthropic').toLowerCase();
      const clipped = (await Clipboard.getStringAsync()).trim();
      if (!clipped) {
        const reply = `Your clipboard is empty, ${getAddress()}.`;
        addMessage('benson', reply); speak(reply);
        return true;
      }
      if (which === 'tavily') {
        setTavilyKey(clipped); tavilyKeyRef.current = clipped;
        await AsyncStorage.setItem('tavilyKey', clipped);
      } else if (which === 'openai') {
        setOpenaiKey(clipped); openaiKeyRef.current = clipped;
        await AsyncStorage.setItem('openaiKey', clipped);
      } else {
        setApiKey(clipped); apiKeyRef.current = clipped;
        await AsyncStorage.setItem('anthropicKey', clipped);
      }
      const label = which === 'anthropic' ? 'Anthropic' : which === 'tavily' ? 'Tavily' : 'OpenAI';
      const reply = `${label} key updated from clipboard, ${getAddress()}.`;
      addMessage('benson', reply); speak(reply);
      return true;
    }

    return false;
  }

  async function tryStoreFact(text: string): Promise<boolean> {
    const m = text.match(REMEMBER_PATTERN);
    if (!m) return false;
    const fact = m[1].trim();
    await appendFact(fact);
    const reply = `Noted, ${getAddress()}. I will remember that.`;
    addMessage('benson', reply);
    if (convModeRef.current) {
      speakText(reply, () => { if (convModeRef.current) doStartListening(); });
    } else {
      speak(reply);
    }
    return true;
  }

  // Item 1 — live device contacts, requested lazily (spoken reason) on first actual need rather
  // than at app boot, so a user who never uses a contacts feature is never interrupted by the
  // permission dialog. Denial is handled honestly: an empty list, never a fabricated contact —
  // every caller downstream (Mission Orchestrator's contacts search/call/message tasks) already
  // speaks "no access" rather than silently failing when given [].
  const contactsPermissionAskedRef = useRef(false);
  async function getLiveContacts(): Promise<TrustedContact[]> {
    const state = await getContactsPermissionState();
    if (state === 'undetermined' && !contactsPermissionAskedRef.current) {
      contactsPermissionAskedRef.current = true;
      speak('Am nevoie de acces la contacte ca să te pot ajuta cu apeluri, WhatsApp și mesaje.');
      await requestContactsPermission();
    }
    return loadRealDeviceContacts();
  }

  // ── Central message handler — routes through the Benson Core Orchestrator ──
  async function handleIncomingText(msg: string) {
    if (!msg || loadingRef.current) return;
    bumpSessionKeepAwake();
    addMessage('user', msg);
    setLoading(true); loadingRef.current = true;

    // Safety net: every branch below already resets loading state on its own successful path,
    // but if anything throws unexpectedly partway through (a native module rejecting, an
    // unhandled exception deep in the Action Engine/Mission Orchestrator chain), none of those
    // inline resets would ever run — loadingRef.current would stay stuck true forever, and
    // BENSON would silently stop responding to every command after that point (confirmed live:
    // this exact symptom recurred after an unexpected failure mid-command). The outer finally
    // guarantees the reset fires no matter which path — or no path — actually completes.
    try {

    // A URL — open it immediately, no confirmation, no AI round-trip.
    const urlMatch = msg.match(URL_PATTERN);
    if (urlMatch) {
      const url = urlMatch[0].startsWith('http') ? urlMatch[0] : `https://${urlMatch[0]}`;
      await Linking.openURL(url).catch(() => {});
      setLoading(false); loadingRef.current = false;
      speakOrShow(`Opening that, ${getAddress()}.`);
      return;
    }

    // Context Engine – pending vignette confirmation takes priority
    if (pendingVignetteRef.current) {
      const border = pendingVignetteRef.current;
      pendingVignetteRef.current = null;
      if (YES_PATTERN.test(msg)) {
        await Linking.openURL(border.vignetteUrl).catch(() => {});
        setLoading(false); loadingRef.current = false;
        speakOrShow(`Am deschis pagina pentru vinieta ${border.country}, ${getAddress()}.`);
        return;
      }
      // Anything else — drop the pending confirmation and handle the message normally.
    }

    // Smart Voice Notepad – pending calendar/message confirmation takes priority
    if (pendingNoteActionRef.current) {
      const note = pendingNoteActionRef.current;
      pendingNoteActionRef.current = null;
      if (YES_PATTERN.test(msg)) {
        setLoading(false); loadingRef.current = false;
        if (note.actions.includes('create_calendar')) {
          const reply = await createCalendarEvent(note, getAddress());
          speakOrShow(reply);
          setActiveCard({ kind: 'note', noteType: 'calendar', summary: note.content });
        } else if (note.actions.includes('send_message') && note.person) {
          const quickPhone = quickContacts.find(
            q => q.name.toLowerCase() === note.person!.toLowerCase()
          )?.phone;
          const reply = await sendMessageToPerson(note.person, note.content, getAddress(), quickPhone);
          speakOrShow(reply);
          setActiveCard({ kind: 'note', noteType: 'message', summary: `${note.person}: ${note.content}` });
        }
        return;
      }
      // Anything else — drop the pending confirmation and handle the message normally.
    }

    // Mission Orchestrator — pending mission task awaiting confirmation takes priority, same
    // yes/no pattern as pendingNoteActionRef/pendingVignetteRef above.
    if (pendingMissionTaskRef.current) {
      const pending = pendingMissionTaskRef.current;
      pendingMissionTaskRef.current = null;
      if (YES_PATTERN.test(msg)) {
        const resumeResult = await resumePendingTask(pending, await getLiveContacts());
        console.log('[MissionOrchestrator] resumed mission=', pending.plan.id, 'status=', resumeResult.plan?.status, 'message=', resumeResult.message);
        addMessage('benson', resumeResult.message);
        setLoading(false); loadingRef.current = false;
        if (resumeResult.pendingTask) pendingMissionTaskRef.current = resumeResult.pendingTask;
        // Same fire-and-forget speak() gap as the initial confirmation question above — a
        // multi-step mission's next confirmation ("da" to step 1, then a second "Confirmi?" for
        // step 2) would otherwise go silent the exact same way.
        const expectsReply2 = !!resumeResult.pendingTask;
        speakText(resumeResult.message, () => {
          if (expectsReply2) { doStartListening(); return; }
          if (convModeRef.current) { doStartListening(); return; }
          if (wakeTriggeredRef.current) {
            wakeTriggeredRef.current = false;
            try { hideWakeRing(); } catch {}
            try { resumePassiveWake(); } catch {}
          }
        });
        return;
      }
      // Anything else — drop the pending mission task and handle the message normally.
    }

    // Mission Governance (Waze/WhatsApp, Phase 1) — pending confirmation / awaiting user
    // resolution takes priority, same position/pattern as the pending-* checks above. This also
    // catches confirmations from the Claude tool-use path (lib/agents/tools.ts), which has no
    // pendingMissionTaskRef of its own — the AsyncStorage-backed store is what lets that path be
    // confirmed on a later turn regardless of which one asked the question.
    const governedMission = getActiveMission();
    if (governedMission?.state === 'WaitingConfirmation') {
      if (YES_PATTERN.test(msg)) {
        const outcome = await confirmActiveMission(await getLiveContacts());
        setLoading(false); loadingRef.current = false;
        if (outcome) { addMessage('benson', outcome.message); speak(outcome.message); }
        return;
      }
      await cancelActiveMission();
      // Anything else — drop the pending confirmation and handle the message normally.
    } else if (governedMission?.state === 'WaitingUser') {
      const resolved = await resolveActiveMissionFromUtterance(msg);
      if (resolved) {
        setLoading(false); loadingRef.current = false;
        addMessage('benson', resolved.message);
        speak(resolved.message);
        return;
      }
      // No resolving word — fall through, ordinary conversation continues unaffected.
    }

    // Voice-only Settings control — checked before fact storage/mission orchestrator so these
    // Settings actions are always reachable by voice, regardless of what else might match.
    if (await trySettingsVoiceCommand(msg)) {
      setLoading(false); loadingRef.current = false;
      return;
    }

    // Stage 4 – fact storage (kept local, not a routed agent)
    if (await tryStoreFact(msg)) {
      setLoading(false); loadingRef.current = false;
      return;
    }

    // Mission Orchestrator (BENSON 21) — the new upper brain: normalize -> Goal Extractor ->
    // Problem Solver -> Mission Planner -> execute tasks through the unchanged App Governance
    // Engine (governAction). Anything it doesn't recognize (handled=false) falls through
    // unchanged to the existing routeCommand/Claude path below, same invariant as BENSON 20.
    logAudioDiag('ORCHESTRATOR_HANDOFF_REQUESTED', `text="${msg}"`);
    const contactsForMission = MAY_NEED_CONTACTS_PATTERN.test(msg) ? await getLiveContacts() : [];
    const missionResult = await runMission(msg, { source: 'voice', contacts: contactsForMission });
    logAudioDiag('ORCHESTRATOR_HANDOFF_COMPLETED', `handled=${missionResult.handled} missionId=${missionResult.plan?.id ?? 'none'}`);
    if (missionResult.handled) {
      console.log('[MissionOrchestrator] mission=', missionResult.plan?.id, 'status=', missionResult.plan?.status, 'message=', missionResult.message);
      addMessage('benson', missionResult.message);
      setLoading(false); loadingRef.current = false;
      if (missionResult.pendingTask) pendingMissionTaskRef.current = missionResult.pendingTask;
      // A response expects a direct spoken reply next either when a task is formally pending
      // confirmation ("Confirmi?"), or when no plan was ever created at all — that only happens
      // on the Mission Orchestrator's own clarification branches ("Pentru cine?",
      // problem.suggestedNextStep), never on a completed/failed mission run (which always carries
      // a plan). Executed-action results (WhatsApp end/mute call, a resolved bridge repair) do
      // carry outcomes of their own and fall back to the same conv-mode/wake-triggered rule as a
      // normal informational reply.
      const expectsReply = !!missionResult.pendingTask || !missionResult.plan;
      // speak() is explicitly fire-and-forget (no onFinished hook) -- using it here meant a
      // spoken "Confirmi?"/"Pentru cine?" never resumed listening for the answer, so BENSON
      // looked dead right after asking its own question. speakText() mirrors the
      // routeCommand/Claude path's resume logic below.
      speakText(missionResult.message, () => {
        if (expectsReply) { doStartListening(); return; }
        if (convModeRef.current) { doStartListening(); return; }
        if (wakeTriggeredRef.current) {
          wakeTriggeredRef.current = false;
          try { hideWakeRing(); } catch {}
          try { resumePassiveWake(); } catch {}
        }
      });
      return;
    }

    const drivingContext = !carModeRef.current ? '' :
      roadTypeRef.current === 'highway'
        ? 'The user is driving on a highway right now — reply in a single short sentence, no distractions.'
        : roadTypeRef.current === 'national'
        ? 'The user is driving on a national road right now — keep the reply short and clear.'
        : 'The user is driving right now — keep the reply concise.';

    const startedAt = Date.now();
    const instructions = currentVoiceInstructions({
      urgency: carModeRef.current && roadTypeRef.current === 'highway' ? 'high' : undefined,
      speaker: detectSpeaker(msg, familyRef.current),
    });
    const sentenceSpeaker = (convModeRef.current || wakeTriggeredRef.current)
      ? createSentenceSpeaker(() => {
          if (convModeRef.current) { doStartListening(); return; }
          if (wakeTriggeredRef.current) {
            wakeTriggeredRef.current = false;
            try { hideWakeRing(); } catch {}
            try { resumePassiveWake(); } catch {}
          }
        }, instructions)
      : null;
    try {
      const result = await routeCommand(msg, {
        address:   getAddress(),
        apiKey:    apiKeyRef.current,
        openaiKey: openaiKeyRef.current,
        tavilyKey: tavilyKeyRef.current,
        modelProvider: modelProviderRef.current,
        character: characterRef.current,
        lang:      replyLangRef.current,
        facts:     factsRef.current,
        history:   historyRef.current,
        family:    familyRef.current,
        drivingContext,
        onSentence: sentenceSpeaker ? (s) => sentenceSpeaker.push(s) : undefined,
        onStartCarMode: () => toggleCarMode(true),
        onRememberFact: (fact) => appendFact(fact),
      }, (progress) => addMessage('benson', progress));

      recordEvent({
        command_type: classifyCommand(result),
        agent_used: result.agent,
        success: true,
        language: langRef.current,
        app_opened: result.appId ?? (result.card?.kind === 'media' ? result.card.provider : undefined),
        session_duration_seconds: Math.round((Date.now() - startedAt) / 1000),
      }).catch(() => {});

      addMessage('benson', result.reply);
      setActiveCard(result.card ?? null);
      if (result.pendingNote) {
        pendingNoteActionRef.current = result.pendingNote;
        // "message" notes save to the family profile immediately — only sending waits for confirmation.
        if (result.pendingNote.type === 'message' && result.pendingNote.person) {
          const updatedFamily = saveFamilyNote(family, result.pendingNote.person, result.pendingNote.content);
          if (updatedFamily !== family) {
            setFamily(updatedFamily); familyRef.current = updatedFamily;
            await AsyncStorage.setItem(FAMILY_KEY, JSON.stringify(updatedFamily));
          }
        }
      }
      if (result.agent === 'claude' || result.agent === 'search') {
        await appendHistory(msg, result.reply);
      }
      setLoading(false); loadingRef.current = false;

      if (sentenceSpeaker) {
        // Non-'claude' agents don't stream — their reply arrives whole, so queue it now.
        // The 'claude' agent already pushed its sentences via onSentence as they streamed in.
        if (result.agent !== 'claude') sentenceSpeaker.push(result.reply);
        sentenceSpeaker.finish();
      } else {
        speak(result.reply);
      }
    } catch {
      recordEvent({
        command_type: 'unknown',
        agent_used: 'unknown',
        success: false,
        language: langRef.current,
        error_type: 'exception',
        session_duration_seconds: Math.round((Date.now() - startedAt) / 1000),
      }).catch(() => {});
      const err = `Connection issue, ${getAddress()}. Please check your network.`;
      addMessage('benson', err);
      setLoading(false); loadingRef.current = false;
      // Always spoken now (previously only when conv mode was already on) — a blind user typing
      // or otherwise not in conv mode still needs to hear that something failed, not just see it.
      speakText(err, () => { if (convModeRef.current) setTimeout(() => doStartListening(), 2000); });
    }
    } catch (err) {
      // Nothing above caught this — genuinely unexpected. Reset happens in finally regardless;
      // this just avoids a silent, unlogged crash of the whole handler.
      console.log('[handleIncomingText] uncaught error', err);
    } finally {
      setLoading(false);
      loadingRef.current = false;
    }
  }

  // ── Render: boot / setup screens ─────────────────────────────────────────
  if (phase === 'boot') return (
    <View style={s.center}>
      <View style={s.seal}><Text style={s.sealB}>B</Text></View>
      <Text style={s.bootText}>BENSON</Text>
      <Text style={s.bootSub}>Initialising systems...</Text>
      {initTimedOut && (
        <Text style={[s.bootSub, { color: '#D9534F', marginTop: 12 }]}>
          Pornirea durează mai mult decât ar trebui — ceva din inițializare s-a blocat.
        </Text>
      )}
    </View>
  );

  if (phase === 'name') return (
    <View style={s.center}>
      <View style={s.seal}><Text style={s.sealB}>B</Text></View>
      <Text style={s.question}>How shall I address you?</Text>
      <TextInput style={s.input} placeholder="Your name" placeholderTextColor={MUTED}
        value={inputName} onChangeText={setInputName} autoFocus />
      <TouchableOpacity style={s.btn} onPress={saveName} accessibilityLabel="Confirm name" accessibilityRole="button">
        <Text style={s.btnText}>CONFIRM</Text>
      </TouchableOpacity>
    </View>
  );

  if (phase === 'key') return (
    <View style={s.center}>
      <View style={s.seal}><Text style={s.sealB}>B</Text></View>
      <Text style={s.question}>Bine ai venit, {masterName}.</Text>
      <Text style={s.bootSub}>Introdu cheia ta Anthropic API. Rămâne doar pe telefonul tău.</Text>
      <TextInput style={s.input} placeholder="sk-ant-..." placeholderTextColor={MUTED}
        value={inputKey} onChangeText={setInputKey} secureTextEntry autoFocus />
      <TouchableOpacity style={s.btn} onPress={saveKey} accessibilityLabel="Activează Benson" accessibilityRole="button">
        <Text style={s.btnText}>ACTIVEAZĂ BENSON</Text>
      </TouchableOpacity>
    </View>
  );

  if (phase === 'consent') return (
    <View style={s.center}>
      <View style={s.seal}><Text style={s.sealB}>B</Text></View>
      <Text style={s.question}>
        Benson învață din utilizare. Trimite anonim statistici de utilizare pentru a deveni mai bun.
        Niciun cuvânt rostit nu este înregistrat. Nicio informație personală nu părăsește telefonul.
      </Text>
      <TouchableOpacity style={s.btn} onPress={() => decideConsent('accepted')}
        accessibilityLabel="Accept analytics consent" accessibilityRole="button">
        <Text style={s.btnText}>ACCEPT</Text>
      </TouchableOpacity>
      <TouchableOpacity style={[s.btn, { backgroundColor: 'transparent', borderWidth: 1, borderColor: MUTED, marginTop: 10 }]}
        onPress={() => decideConsent('declined')}
        accessibilityLabel="Decline analytics consent" accessibilityRole="button">
        <Text style={[s.btnText, { color: MUTED }]}>REFUZ</Text>
      </TouchableOpacity>
    </View>
  );

  // ── Render: Car Mode — big-button, hands-free screen ──────────────────────
  if (carMode) {
    const carStatusText = listening ? 'LISTENING...' : loading ? 'THINKING...' : convMode ? 'CONVERSATION ON' : 'TAP TO START';
    return (
      <View style={s.carContainer}>
        <TouchableOpacity style={s.carExit} hitSlop={12} onPress={() => { tap(); toggleCarMode(false); }}
          accessibilityLabel="Exit car mode" accessibilityRole="button">
          <Text style={s.carExitTxt}>✕ EXIT CAR MODE</Text>
        </TouchableOpacity>
        <View style={s.carCenter}>
          <Animated.View style={{ transform: [{ scale: convMode ? pulseAnim : 1 }] }}>
            <TouchableOpacity style={[s.carBigBtn, convMode && s.carBigBtnActive]}
              onPress={() => { tap(); toggleConvMode(); }} activeOpacity={0.8}
              accessibilityLabel="Conversation mode" accessibilityRole="button"
              accessibilityState={{ selected: convMode }}>
              <Text style={s.carBigIcon}>{listening ? '🎙' : loading ? '···' : convMode ? '◉' : '◎'}</Text>
            </TouchableOpacity>
          </Animated.View>
          <Text style={s.carStatus}>{carStatusText}</Text>
          {lastReply !== '' && <Text style={s.carLastReply} numberOfLines={4}>{lastReply}</Text>}
        </View>
      </View>
    );
  }

  // ── Render: chat screen ────────────────────────────────────────────────────
  return (
    <View style={s.container}>
      <BensonMainScreen
        listening={listening}
        micVolume={micVolume}
        loading={loading}
        speaking={speaking}
        convMode={convMode}
        carMode={carMode}
        showQuickContacts={showQuickContacts}
        activeCard={activeCard}
        lastReply={lastReply}
        quickContacts={quickContacts}
        isInPip={isInPip}
        silenced={silenced}
        muted={muted}
        onToggleConvMode={() => { tap(); handleMedallionTap(); }}
        onOpenSettings={() => { tap(); openSettings(); }}
        onToggleQuickContacts={() => { tap(); setShowQuickContacts(v => !v); }}
        onToggleCarMode={() => { tap(); toggleCarMode(!carMode); }}
        onToggleTodo={handleToggleTodo}
        onClearCompletedTodo={handleClearCompletedTodo}
        onQuickContactsChange={setQuickContacts}
        onSubmitText={handleIncomingText}
        onToggleSilence={toggleSilence}
        onToggleMute={toggleMute}
      />

      {/* ── Accessibility-down banner ── persistent, tappable, over the main screen. Shown only on
          the chat screen and never while the onboarding/setup modals (which already handle this
          permission) are open, so it doesn't double-nag. One tap opens the system Settings screen
          where the user re-enables the service. */}
      {accessibilityDown && phase === 'chat' && !setupWizardOpen && !appPermOpen && (
        <View style={s.a11yBanner}>
          <View style={{ flex: 1 }}>
            <Text style={s.a11yBannerTitle}>Serviciul de Accesibilitate este oprit</Text>
            <Text style={s.a11yBannerBody}>
              Nu pot citi ecranul sau apăsa butoane în alte aplicații până nu îl reactivezi.
            </Text>
          </View>
          <TouchableOpacity
            style={s.a11yBannerBtn}
            onPress={() => { tap(); openAccessibilitySettings(); }}
            accessibilityLabel="Deschide setările de accesibilitate" accessibilityRole="button">
            <Text style={s.a11yBannerBtnText}>DESCHIDE SETĂRILE</Text>
          </TouchableOpacity>
        </View>
      )}
      {/* ── Voice-model download banner ── the ggml Whisper model is fetched once at first launch
          (it is not bundled in the APK). Show clear progress so a fresh install never looks frozen,
          and offer a retry if the download fails (e.g. no Wi-Fi). */}
      {phase === 'chat' && !setupWizardOpen && !appPermOpen && whisperStatus.state === 'downloading' && (
        <View style={s.dlBanner}>
          <View style={{ flex: 1 }}>
            <Text style={s.dlBannerTitle}>Se descarcă modelul de voce…</Text>
            <Text style={s.dlBannerBody}>
              O singură dată, ca Benson să te audă offline. {Math.round(whisperStatus.progress * 100)}%
            </Text>
          </View>
        </View>
      )}
      {phase === 'chat' && !setupWizardOpen && !appPermOpen && whisperStatus.state === 'error' && (
        <View style={s.dlBanner}>
          <View style={{ flex: 1 }}>
            <Text style={s.dlBannerTitle}>Descărcarea modelului de voce a eșuat</Text>
            <Text style={s.dlBannerBody}>Conectează-te la internet și reîncearcă.</Text>
          </View>
          <TouchableOpacity
            style={s.dlBannerBtn}
            onPress={() => { tap(); preloadLocalWhisper(); }}
            accessibilityLabel="Reîncearcă descărcarea modelului" accessibilityRole="button">
            <Text style={s.dlBannerBtnText}>REÎNCEARCĂ</Text>
          </TouchableOpacity>
        </View>
      )}
      {/* ── Settings Modal ── */}
      <Modal visible={settingsOpen} animationType="slide" transparent>
        <View style={s.modalBg}>
          <View style={s.modalBox}>
            <View style={s.modalHeader}>
              <Text style={s.modalTitle}>SETTINGS</Text>
              <TouchableOpacity hitSlop={12} onPress={() => { tap(); setSettingsOpen(false); }}
                accessibilityLabel="Close settings" accessibilityRole="button">
                <Text style={s.closeX}>✕</Text>
              </TouchableOpacity>
            </View>
            <ScrollView showsVerticalScrollIndicator={false}>

              {/* Service status — live, non-invasive checks */}
              <Text style={s.label}>SERVICE STATUS</Text>
              <View style={s.statusRow}>
                <Text style={s.statusLabel}>Microphone</Text>
                <Text style={s.statusDot}>{serviceStatus.mic ? '✅' : '❌'}</Text>
              </View>
              <View style={s.statusRow}>
                <Text style={s.statusLabel}>Accessibility Service</Text>
                <Text style={s.statusDot}>{serviceStatus.accessibility ? '✅' : '❌'}</Text>
              </View>
              <View style={s.statusRow}>
                <Text style={s.statusLabel}>GPS</Text>
                <Text style={s.statusDot}>{serviceStatus.gps ? '✅' : '❌'}</Text>
              </View>
              <View style={s.statusRow}>
                <Text style={s.statusLabel}>AI Connection</Text>
                <Text style={s.statusDot}>{serviceStatus.ai ? '✅' : '❌'}</Text>
              </View>
              <View style={[s.row, { marginTop: 10 }]}>
                <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, flex: 1 }]}
                  onPress={() => { tap(); checkServiceStatus(); }}
                  accessibilityLabel="Test all services" accessibilityRole="button">
                  <Text style={[s.dangerTxt, { color: GOLD }]}>Testează tot</Text>
                </TouchableOpacity>
                <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, flex: 1 }]}
                  onPress={() => { tap(); router.push('/debug'); }}
                  accessibilityLabel="Open debug panel" accessibilityRole="button">
                  <Text style={[s.dangerTxt, { color: GOLD }]}>Debug Panel</Text>
                </TouchableOpacity>
                {!serviceStatus.accessibility && (
                  <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, flex: 1 }]}
                    onPress={() => { tap(); openAccessibilitySettings(); }}
                    accessibilityLabel="Enable accessibility service" accessibilityRole="button">
                    <Text style={[s.dangerTxt, { color: GOLD }]}>Activează Accessibility</Text>
                  </TouchableOpacity>
                )}
              </View>

              {/* Setup / Einrichtung — re-run the guided system-permissions wizard anytime */}
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 10 }]}
                onPress={() => { tap(); setSetupWizardOpen(true); }}
                accessibilityLabel="Setup / Einrichtung" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>Setup / Einrichtung</Text>
              </TouchableOpacity>

              {/* App Permissions — BENSON 4: which installed apps BENSON may open/operate */}
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 10 }]}
                onPress={() => { tap(); setAppPermOpen(true); }}
                accessibilityLabel="App permissions" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>App Permissions</Text>
              </TouchableOpacity>

              {/* Floating bubble — "draw over other apps" can't be silently granted; this opens
                  the system settings screen once. The bubble itself only shows automatically
                  once granted, whenever conversation mode is on. */}
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 10 }]}
                onPress={() => { tap(); requestOverlayPermission(); }}
                accessibilityLabel="Enable floating bubble" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>Enable Floating Bubble</Text>
              </TouchableOpacity>

              {/* API Keys — BENSON calls Anthropic/OpenAI directly with the user's own key (kept
                  on-device only). The Supabase relay was removed for deploy compatibility. */}
              <Text style={s.label}>API KEYS</Text>
              <TextInput style={s.input} placeholder="Cheie Anthropic (sk-ant-...) — creierul lui Benson" placeholderTextColor={MUTED}
                value={apiKey} onChangeText={setApiKey} secureTextEntry />
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder="Tavily key (tvly-...) — for web search" placeholderTextColor={MUTED}
                value={tavilyKey} onChangeText={setTavilyKey} secureTextEntry />
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder="OpenAI key (sk-...) — for OpenAI chat model + voice" placeholderTextColor={MUTED}
                value={openaiKey} onChangeText={setOpenaiKey} secureTextEntry />
              <TouchableOpacity style={[s.btn, { marginTop: 10 }]} onPress={() => { tap(); saveApiKeys(); }}
                accessibilityLabel="Save API keys" accessibilityRole="button">
                <Text style={s.btnText}>SAVE KEYS</Text>
              </TouchableOpacity>

              {/* Chat model */}
              <Text style={s.label}>CHAT MODEL</Text>
              <View style={s.row}>
                <TouchableOpacity onPress={() => toggleModelProvider('claude')}
                  style={[s.chip, modelProvider === 'claude' && s.chipActive]}
                  accessibilityLabel="Claude chat model" accessibilityRole="button"
                  accessibilityState={{ selected: modelProvider === 'claude' }}>
                  <Text style={[s.chipTxt, modelProvider === 'claude' && s.chipTxtActive]}>Claude</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => toggleModelProvider('openai')}
                  style={[s.chip, modelProvider === 'openai' && s.chipActive]}
                  accessibilityLabel="ChatGPT chat model" accessibilityRole="button"
                  accessibilityState={{ selected: modelProvider === 'openai' }}>
                  <Text style={[s.chipTxt, modelProvider === 'openai' && s.chipTxtActive]}>ChatGPT (gpt-4o)</Text>
                </TouchableOpacity>
              </View>

              {/* Voice engine */}
              <Text style={s.label}>VOICE ENGINE</Text>
              <View style={s.row}>
                <TouchableOpacity onPress={() => toggleTtsProvider('device')}
                  style={[s.chip, ttsProvider === 'device' && s.chipActive]}
                  accessibilityLabel="Device voice engine, free" accessibilityRole="button"
                  accessibilityState={{ selected: ttsProvider === 'device' }}>
                  <Text style={[s.chipTxt, ttsProvider === 'device' && s.chipTxtActive]}>Device (free)</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => toggleTtsProvider('openai')}
                  style={[s.chip, ttsProvider === 'openai' && s.chipActive]}
                  accessibilityLabel="OpenAI voice engine, onyx" accessibilityRole="button"
                  accessibilityState={{ selected: ttsProvider === 'openai' }}>
                  <Text style={[s.chipTxt, ttsProvider === 'openai' && s.chipTxtActive]}>OpenAI (onyx)</Text>
                </TouchableOpacity>
                <TouchableOpacity style={s.previewBtn} onPress={previewOpenAIVoice}
                  accessibilityLabel="Preview OpenAI voice" accessibilityRole="button">
                  <Text style={s.previewTxt}>▶</Text>
                </TouchableOpacity>
              </View>
              <Text style={s.factLine}>
                OpenAI voice costs per use and needs internet; Benson falls back to the device voice automatically if it's unavailable.
              </Text>

              {/* Wake-confirmation chime — the short "ding" Benson plays when it hears "Benson".
                  "Oprit" turns it off entirely; the others set how loud it is (and preview it). */}
              <Text style={s.label}>SUNET LA TREZIRE</Text>
              <View style={s.row}>
                {([['Oprit', 0], ['Încet', 0.3], ['Mediu', 0.6], ['Tare', 1.0]] as [string, number][]).map(([label, v]) => {
                  const active = Math.abs(wakeVolume - v) < 0.05;
                  return (
                    <TouchableOpacity key={label} onPress={() => { tap(); changeWakeVolume(v); }}
                      style={[s.chip, active && s.chipActive]}
                      accessibilityLabel={`Sunet la trezire ${label}`} accessibilityRole="button"
                      accessibilityState={{ selected: active }}>
                      <Text style={[s.chipTxt, active && s.chipTxtActive]}>{label}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
              <Text style={s.factLine}>
                Sunetul scurt care confirmă că te-am auzit când spui „Benson”. Alege „Oprit” ca să nu se mai audă.
              </Text>

              {/* Accessibility reminder interval — how often Benson gently re-speaks the reminder
                  while the Accessibility service stays off. */}
              <Text style={s.label}>REAMINTIRE ACCESIBILITATE</Text>
              <View style={s.row}>
                {([['5 min', 5], ['15 min', 15], ['30 min', 30]] as [string, number][]).map(([label, m]) => {
                  const active = reminderMins === m;
                  return (
                    <TouchableOpacity key={label} onPress={() => { tap(); changeReminderMins(m); }}
                      style={[s.chip, active && s.chipActive]}
                      accessibilityLabel={`Reamintire la fiecare ${label}`} accessibilityRole="button"
                      accessibilityState={{ selected: active }}>
                      <Text style={[s.chipTxt, active && s.chipTxtActive]}>{label}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
              <Text style={s.factLine}>
                Cât de des îți reamintesc vocal, blând, dacă Serviciul de Accesibilitate rămâne oprit.
              </Text>

              {/* STT engine — cloud recognition on this device has been unreliable (hangs/errors);
                  on-device (Android's own offline mode) has the same issue plus mic contention;
                  Local is BENSON's own AudioRecord+VAD+Whisper pipeline, fully on-device. */}
              <Text style={s.label}>ASCULTARE (STT)</Text>
              <View style={s.row}>
                <TouchableOpacity onPress={() => changeSttEngine('cloud')}
                  style={[s.chip, sttEngine === 'cloud' && s.chipActive]}
                  accessibilityLabel="Recunoaștere vocală în cloud" accessibilityRole="button"
                  accessibilityState={{ selected: sttEngine === 'cloud' }}>
                  <Text style={[s.chipTxt, sttEngine === 'cloud' && s.chipTxtActive]}>Cloud</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => changeSttEngine('ondevice')}
                  style={[s.chip, sttEngine === 'ondevice' && s.chipActive]}
                  accessibilityLabel="Recunoaștere vocală Android pe device, offline" accessibilityRole="button"
                  accessibilityState={{ selected: sttEngine === 'ondevice' }}>
                  <Text style={[s.chipTxt, sttEngine === 'ondevice' && s.chipTxtActive]}>Offline Android</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => changeSttEngine('local')}
                  style={[s.chip, sttEngine === 'local' && s.chipActive]}
                  accessibilityLabel="Sistemul propriu Benson, Whisper local" accessibilityRole="button"
                  accessibilityState={{ selected: sttEngine === 'local' }}>
                  <Text style={[s.chipTxt, sttEngine === 'local' && s.chipTxtActive]}>Benson (local)</Text>
                </TouchableOpacity>
              </View>
              <Text style={s.factLine}>
                Benson (local): sistem propriu, integral pe telefon — vocea ta nu iese niciodată din device pentru ascultare. Offline Android necesită modelul limbii descărcat din Setările telefonului. Cloud e varianta clasică Google.
              </Text>

              {/* Language */}
              <Text style={s.label}>LANGUAGE</Text>
              <View style={s.row}>
                {LANGUAGES.map(l => (
                  <TouchableOpacity key={l.code} onPress={() => changeLang(l.code)}
                    style={[s.chip, lang === l.code && s.chipActive]}
                    accessibilityLabel={`Language: ${l.label}`} accessibilityRole="button"
                    accessibilityState={{ selected: lang === l.code }}>
                    <Text style={[s.chipTxt, lang === l.code && s.chipTxtActive]}>{l.label}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              {/* Voice on/off */}
              <Text style={s.label}>VOICE</Text>
              <Switch value={voiceEnabled} onValueChange={toggleVoice}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Voice enabled" accessibilityRole="switch" />

              {/* Background Mode */}
              <Text style={s.label}>BACKGROUND LISTENING</Text>
              <Switch value={backgroundMode} onValueChange={toggleBackgroundMode}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Background listening" accessibilityRole="switch" />
              <Text style={s.factLine}>
                Keeps Benson listening via a persistent notification when Conversation Mode is on, even if the app is minimised.
              </Text>

              {/* Wake word kill switch */}
              <Text style={s.label}>WAKE WORD</Text>
              <Switch value={wakeWordEnabled} onValueChange={toggleWakeWord}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Wake word active" accessibilityRole="switch" />
              <Text style={s.factLine}>
                When off, Benson never listens for "Benson" in the background — the mic stays closed until you tap to talk.
              </Text>

              {/* Picovoice Porcupine — AccessKey field + live status (product-owner-directed
                  2026-08-02). Blank field is normal even when a key is already saved — see
                  savePorcupineKey's comment; the status lines below are the source of truth. */}
              <Text style={s.label}>PICOVOICE ACCESS KEY</Text>
              <TextInput style={s.input} placeholder="Picovoice AccessKey" placeholderTextColor={MUTED}
                value={porcupineKeyInput} onChangeText={setPorcupineKeyInput} secureTextEntry
                accessibilityLabel="Picovoice AccessKey" />
              <TouchableOpacity style={[s.btn, { marginTop: 10 }]} onPress={savePorcupineKey}
                accessibilityLabel="Save Picovoice AccessKey" accessibilityRole="button">
                <Text style={s.btnText}>SAVE KEY</Text>
              </TouchableOpacity>
              <Text style={s.factLine}>
                AccessKey: {porcupineStatus.hasKey ? '✓ saved' : '✗ missing'} · Model file (benson.ppn): {porcupineStatus.hasModel ? '✓ present' : '✗ missing'}
                {porcupineStatus.hasKey && porcupineStatus.hasModel ? ' — Porcupine ready.' : ' — falls back to the classic engine until both are present.'}
              </Text>
              <Text style={s.factLine}>
                Wake-word engine running right now: {activeWakeEngine === 'porcupine' ? 'Porcupine' : activeWakeEngine === 'speechrecognizer' ? 'Classic (SpeechRecognizer)' : 'none (wake word off)'}
              </Text>

              {/* Car Mode */}
              <Text style={s.label}>CAR MODE</Text>
              <Switch value={carMode} onValueChange={toggleCarMode}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Car mode" accessibilityRole="switch" />
              <Text style={s.factLine}>
                Big-button, hands-free screen for driving. Turns on Conversation Mode and Background Listening automatically.
              </Text>

              {/* Auto Car Mode detection */}
              <Text style={s.label}>AUTO CAR MODE</Text>
              <Switch value={autoCarMode} onValueChange={toggleAutoCarMode}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Auto car mode detection" accessibilityRole="switch" />
              <Text style={s.factLine}>
                No button, no voice command — Car Mode turns on by itself when your car's Bluetooth connects,
                or when speed stays above 25 km/h for 30s. Turns off when Bluetooth disconnects or speed
                stays below 5 km/h for 60s.
              </Text>
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 10 }]} onPress={loadBondedDevices}
                accessibilityLabel="Load paired Bluetooth devices" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>Load paired Bluetooth devices</Text>
              </TouchableOpacity>
              {carDeviceName !== '' && (
                <Text style={s.factLine}>Current car device: {carDeviceName}</Text>
              )}
              <View style={[s.row, { marginTop: 8 }]}>
                {bondedDevices.map(d => (
                  <TouchableOpacity key={d.address} onPress={() => selectCarDevice(d)}
                    style={[s.chip, carDeviceAddress === d.address && s.chipActive]}
                    accessibilityLabel={`Car device: ${d.name}`} accessibilityRole="button"
                    accessibilityState={{ selected: carDeviceAddress === d.address }}>
                    <Text style={[s.chipTxt, carDeviceAddress === d.address && s.chipTxtActive]}>{d.name}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              {/* Quick Contacts */}
              <Text style={s.label}>QUICK CONTACTS  ({quickContacts.length}/{MAX_QUICK_CONTACTS})</Text>
              <Text style={s.factLine}>
                Pick up to {MAX_QUICK_CONTACTS} contacts to show as floating bubbles on the main screen —
                tap one, then drag toward call, WhatsApp, SMS, or email to reach them instantly.
              </Text>
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 10 }]} onPress={loadDeviceContacts}
                accessibilityLabel="Load contacts" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>Load contacts</Text>
              </TouchableOpacity>
              <View style={[s.row, { marginTop: 8 }]}>
                {deviceContacts.map(c => {
                  const active = quickContacts.some(q => q.id === c.id);
                  const atMax = !active && quickContacts.length >= MAX_QUICK_CONTACTS;
                  return (
                    <TouchableOpacity key={c.id} onPress={() => toggleQuickContact(c)} disabled={atMax}
                      style={[s.chip, active && s.chipActive, atMax && { opacity: 0.4 }]}
                      accessibilityLabel={`Quick contact: ${c.name}`} accessibilityRole="button"
                      accessibilityState={{ selected: active, disabled: atMax }}>
                      <Text style={[s.chipTxt, active && s.chipTxtActive]}>{c.name}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>

              {/* Governed Apps */}
              <Text style={s.label}>GOVERNED APPS  ({approvedAppIds.length}/{APP_REGISTRY.length})</Text>
              <Text style={s.factLine}>
                Benson only opens apps you've approved here. It never embeds them or touches payment —
                it just opens the app, you take it from there.
              </Text>
              <View style={[s.row, { marginTop: 8 }]}>
                {APP_REGISTRY.map(a => {
                  const active = approvedAppIds.includes(a.id);
                  return (
                    <TouchableOpacity key={a.id} onPress={() => toggleAppApproval(a.id)}
                      style={[s.chip, active && s.chipActive]}
                      accessibilityLabel={`Governed app: ${a.name}`} accessibilityRole="button"
                      accessibilityState={{ selected: active }}>
                      <Text style={[s.chipTxt, active && s.chipTxtActive]}>{a.name}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>

              {/* Vignettes — Context Engine */}
              <Text style={s.label}>VIGNETTES (border alerts, Car Mode)</Text>
              {BORDER_CROSSINGS.map(b => (
                <View key={b.countryCode} style={s.familyCard}>
                  <Text style={s.chipTxt}>{b.country}</Text>
                  <View style={{ flexDirection: 'row', gap: 8, marginTop: 6 }}>
                    <TextInput style={[s.input, { flex: 1, marginTop: 0 }]}
                      placeholder="Expiry date (YYYY-MM-DD)" placeholderTextColor={MUTED}
                      value={vignetteExpiry[b.countryCode] ?? ''}
                      onChangeText={(t) => saveVignetteExpiry(b.countryCode, t)} />
                    <TouchableOpacity style={s.previewBtn} onPress={() => Linking.openURL(b.vignetteUrl)}
                      accessibilityLabel={`Open vignette purchase page for ${b.country}`} accessibilityRole="link">
                      <Text style={s.previewTxt}>↗</Text>
                    </TouchableOpacity>
                  </View>
                </View>
              ))}

              {/* Speed */}
              <Text style={s.label}>VOICE SPEED  ({voiceRate.toFixed(2)}x)</Text>
              <SimpleSlider
                min={0.8} max={1.5} value={voiceRate}
                onChange={(v) => { setVoiceRate(v); voiceRateRef.current = v; }}
                onSlideEnd={updateRate}
              />

              {/* Tone */}
              <Text style={s.label}>VOICE TONE</Text>
              <View style={s.row}>
                {([
                  { label: 'Deep', pitch: 0.6  },
                  { label: 'Low',  pitch: 0.85 },
                  { label: 'Mid',  pitch: 1.0  },
                  { label: 'High', pitch: 1.2  },
                ] as { label: string; pitch: number }[]).map(v => (
                  <TouchableOpacity key={v.pitch} onPress={() => updatePitch(v.pitch)}
                    style={[s.chip, voicePitch === v.pitch && s.chipActive]}
                    accessibilityLabel={`Voice tone: ${v.label}`} accessibilityRole="button"
                    accessibilityState={{ selected: voicePitch === v.pitch }}>
                    <Text style={[s.chipTxt, voicePitch === v.pitch && s.chipTxtActive]}>{v.label}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              {/* Stage 6 — Specific voice selection */}
              <Text style={s.label}>VOICE SELECTION  ({voices.length} on device)</Text>
              {voiceGroups.map(([langCode, vs]) => (
                <View key={langCode} style={{ marginBottom: 14 }}>
                  <Text style={s.groupLabel}>{langCode}</Text>
                  {vs.map(v => (
                    <View key={v.identifier} style={s.voiceRow}>
                      <TouchableOpacity
                        style={[s.chip, { flex: 1 }, voiceId === v.identifier && s.chipActive]}
                        onPress={() => selectVoice(v.identifier)}
                        accessibilityLabel={`Voice: ${v.name}`} accessibilityRole="button"
                        accessibilityState={{ selected: voiceId === v.identifier }}>
                        <Text numberOfLines={1}
                          style={[s.chipTxt, voiceId === v.identifier && s.chipTxtActive]}>
                          {v.name}
                        </Text>
                      </TouchableOpacity>
                      <TouchableOpacity style={s.previewBtn} onPress={() => previewVoice(v.identifier, v.language)}
                        accessibilityLabel={`Preview voice: ${v.name}`} accessibilityRole="button">
                        <Text style={s.previewTxt}>▶</Text>
                      </TouchableOpacity>
                    </View>
                  ))}
                </View>
              ))}
              {voiceId !== '' && (
                <TouchableOpacity style={s.dangerBtn} onPress={() => selectVoice('')}
                  accessibilityLabel="Reset to default voice" accessibilityRole="button">
                  <Text style={s.dangerTxt}>Reset to default voice</Text>
                </TouchableOpacity>
              )}

              {/* Stage 2 — Character */}
              <Text style={s.label}>CHARACTER</Text>
              <View style={s.row}>
                {(['butler', 'friend', 'professional'] as Character[]).map(c => (
                  <TouchableOpacity key={c} onPress={() => changeCharacter(c)}
                    style={[s.chip, character === c && s.chipActive]}
                    accessibilityLabel={`Character: ${c}`} accessibilityRole="button"
                    accessibilityState={{ selected: character === c }}>
                    <Text style={[s.chipTxt, character === c && s.chipTxtActive]}>
                      {c[0].toUpperCase() + c.slice(1)}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>

              {/* Stage 2 — Address mode */}
              <Text style={s.label}>BENSON CALLS YOU</Text>
              <View style={s.row}>
                <TouchableOpacity onPress={() => changeAddressMode('master')}
                  style={[s.chip, addressMode === 'master' && s.chipActive]}
                  accessibilityLabel="Address mode: Master" accessibilityRole="button"
                  accessibilityState={{ selected: addressMode === 'master' }}>
                  <Text style={[s.chipTxt, addressMode === 'master' && s.chipTxtActive]}>Master</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => changeAddressMode('name')}
                  style={[s.chip, addressMode === 'name' && s.chipActive]}
                  accessibilityLabel={`Address mode: ${masterName || 'your name'}`} accessibilityRole="button"
                  accessibilityState={{ selected: addressMode === 'name' }}>
                  <Text style={[s.chipTxt, addressMode === 'name' && s.chipTxtActive]}>
                    {masterName || 'Your name'}
                  </Text>
                </TouchableOpacity>
              </View>

              {/* Family Engine */}
              <Text style={s.label}>FAMILY</Text>
              {family.map(f => (
                <View key={f.id} style={s.familyCard}>
                  <View style={{ flexDirection: 'row', gap: 8 }}>
                    <TextInput style={[s.input, { flex: 1, marginTop: 0 }]} placeholder="Name" placeholderTextColor={MUTED}
                      value={f.name} onChangeText={(t) => updateFamilyField(f.id, 'name', t)} />
                    <TouchableOpacity style={s.previewBtn} onPress={() => removeFamilyMember(f.id)}
                      accessibilityLabel={`Remove family member ${f.name || ''}`} accessibilityRole="button">
                      <Text style={[s.previewTxt, { color: RED }]}>✕</Text>
                    </TouchableOpacity>
                  </View>
                  <TextInput style={[s.input, { marginTop: 8 }]} placeholder="Relation (e.g. son, wife, daughter)" placeholderTextColor={MUTED}
                    value={f.relation} onChangeText={(t) => updateFamilyField(f.id, 'relation', t)} />
                  <TextInput style={[s.input, { marginTop: 8 }]} placeholder="Notes (likes, birthday, etc.)" placeholderTextColor={MUTED}
                    value={f.notes} onChangeText={(t) => updateFamilyField(f.id, 'notes', t)} />
                </View>
              ))}
              <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, marginTop: 4 }]} onPress={addFamilyMember}
                accessibilityLabel="Add family member" accessibilityRole="button">
                <Text style={[s.dangerTxt, { color: GOLD }]}>+ Add family member</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[s.btn, { marginTop: 10 }]} onPress={saveFamily}
                accessibilityLabel="Save family" accessibilityRole="button">
                <Text style={s.btnText}>SAVE FAMILY</Text>
              </TouchableOpacity>

              {/* Stage 4 — Memory / facts */}
              <Text style={s.label}>MEMORY  ({facts.length} facts)</Text>
              {facts.map((f, i) => (
                <Text key={i} style={s.factLine}>• {f}</Text>
              ))}
              <View style={[s.row, { marginTop: 10 }]}>
                <TouchableOpacity style={s.dangerBtn} onPress={wipeMemory}
                  accessibilityLabel="Wipe memory" accessibilityRole="button">
                  <Text style={s.dangerTxt}>Wipe memory</Text>
                </TouchableOpacity>
              </View>

              {/* Smart Voice Notepad — app feedback, for the developer */}
              <Text style={s.label}>FEEDBACK  ({feedbackItems.length} saved)</Text>
              <Text style={s.factLine}>
                Say "Benson, I'd like you to add X to the app" — it's saved here, visible only to you.
              </Text>
              {feedbackItems.map(f => (
                <Text key={f.id} style={s.factLine}>• {f.text}</Text>
              ))}

              {/* Developer Analytics Agent */}
              <Text style={s.label}>CE TRIMITE BENSON</Text>
              <Switch
                value={analyticsConsent === 'accepted'}
                onValueChange={(v) => updateAnalyticsConsent(v ? 'accepted' : 'declined')}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Send anonymous usage analytics" accessibilityRole="switch"
              />
              <Text style={s.factLine}>
                Când e activat, Benson trimite anonim: tipul comenzii, agentul folosit, succes/eșec,
                limba, aplicația deschisă, durata procesării și tipul erorii — niciodată cuvintele
                rostite sau date personale. Se încarcă o dată pe zi, doar prin WiFi.
              </Text>

              <Text style={s.versionTag}>
                {Constants.expoConfig?.extra?.buildLabel ?? 'BENSON'}
              </Text>

            </ScrollView>
          </View>
        </View>
      </Modal>

      <SetupWizard visible={setupWizardOpen} onClose={() => setSetupWizardOpen(false)} />
      <AppPermissionsModal visible={appPermOpen && !setupWizardOpen} onClose={() => setAppPermOpen(false)} />
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────
const s = StyleSheet.create({
  container:     { flex: 1, backgroundColor: NAVY },
  center:        { flex: 1, backgroundColor: NAVY, alignItems: 'center', justifyContent: 'center', padding: 32 },

  a11yBanner:    { position: 'absolute', top: 0, left: 0, right: 0, flexDirection: 'row', alignItems: 'center',
                   backgroundColor: RED, paddingTop: 44, paddingBottom: 12, paddingHorizontal: 14, gap: 12 },
  a11yBannerTitle: { color: '#fff', fontWeight: '800', fontSize: 13, letterSpacing: 0.5, marginBottom: 2 },
  a11yBannerBody:  { color: 'rgba(255,255,255,0.92)', fontSize: 12, lineHeight: 16 },
  a11yBannerBtn:   { backgroundColor: '#fff', paddingVertical: 8, paddingHorizontal: 12, borderRadius: 6 },
  a11yBannerBtnText: { color: RED, fontWeight: '800', fontSize: 11, letterSpacing: 0.5 },

  dlBanner:      { position: 'absolute', top: 0, left: 0, right: 0, flexDirection: 'row', alignItems: 'center',
                   backgroundColor: '#2E3742', paddingTop: 44, paddingBottom: 12, paddingHorizontal: 14, gap: 12 },
  dlBannerTitle: { color: GOLD, fontWeight: '800', fontSize: 13, letterSpacing: 0.5, marginBottom: 2 },
  dlBannerBody:  { color: 'rgba(255,255,255,0.92)', fontSize: 12, lineHeight: 16 },
  dlBannerBtn:   { backgroundColor: GOLD, paddingVertical: 8, paddingHorizontal: 12, borderRadius: 6 },
  dlBannerBtnText: { color: '#2E3742', fontWeight: '800', fontSize: 11, letterSpacing: 0.5 },

  seal:          { width: 100, height: 100, borderRadius: 50, backgroundColor: PANEL, borderWidth: 2, borderColor: GOLD, alignItems: 'center', justifyContent: 'center', marginBottom: 24 },
  sealB:         { fontSize: 48, color: GOLD, fontWeight: '300' },


  bootText:      { fontSize: 32, color: GOLD, letterSpacing: 12, marginBottom: 8 },
  bootSub:       { fontSize: 13, color: MUTED, letterSpacing: 2, textAlign: 'center', marginTop: 8 },
  question:      { fontSize: 22, color: '#E8E8E8', marginBottom: 8, textAlign: 'center' },
  input:         { width: '100%', borderWidth: 1, borderColor: GOLD, color: '#E8E8E8', padding: 14, fontSize: 15, marginTop: 16, backgroundColor: PANEL },
  btn:           { backgroundColor: GOLD, width: '100%', padding: 14, alignItems: 'center', marginTop: 12 },
  btnText:       { color: NAVY, fontWeight: '700', letterSpacing: 2, fontSize: 13 },

  statusRow:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#1A1A2E' },
  statusLabel:   { color: '#E8E8E8', fontSize: 13 },
  statusDot:     { fontSize: 14 },

  modalBg:       { flex: 1, backgroundColor: 'rgba(0,0,0,0.75)', justifyContent: 'flex-end' },
  modalBox:      { backgroundColor: NAVY, borderTopWidth: 2, borderTopColor: GOLD, padding: 24, maxHeight: '85%' },
  modalHeader:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  modalTitle:    { fontSize: 18, color: GOLD, letterSpacing: 4 },
  closeX:        { fontSize: 20, color: MUTED },

  label:         { fontSize: 11, color: GOLD, letterSpacing: 2, marginTop: 20, marginBottom: 10 },
  row:           { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  chip:          { paddingVertical: 8, paddingHorizontal: 14, borderWidth: 1, borderColor: MUTED },
  chipActive:    { borderColor: GOLD, backgroundColor: 'rgba(201,168,76,0.15)' },
  chipTxt:       { fontSize: 12, color: MUTED },
  chipTxtActive: { color: GOLD },

  dangerBtn:     { borderWidth: 1, borderColor: RED, padding: 12, alignItems: 'center' },
  dangerTxt:     { color: RED, fontSize: 13 },

  familyCard:    { borderWidth: 1, borderColor: '#1A1A2E', padding: 10, marginBottom: 10, backgroundColor: PANEL },

  groupLabel:    { fontSize: 11, color: MUTED, letterSpacing: 1, marginBottom: 6, fontWeight: '700' },
  voiceRow:      { flexDirection: 'row', gap: 8, marginBottom: 6 },
  previewBtn:    { width: 40, borderWidth: 1, borderColor: MUTED, alignItems: 'center', justifyContent: 'center' },
  previewTxt:    { color: GOLD, fontSize: 14 },

  factLine:      { color: MUTED, fontSize: 12, marginBottom: 3, paddingLeft: 4 },
  versionTag:    { color: MUTED, fontSize: 10, textAlign: 'center', marginTop: 24, marginBottom: 8, opacity: 0.5 },

  carContainer:  { flex: 1, backgroundColor: NAVY },
  carExit:       { alignSelf: 'center', marginTop: 56, marginBottom: 12, borderWidth: 1, borderColor: MUTED, paddingVertical: 10, paddingHorizontal: 20 },
  carExitTxt:    { color: MUTED, fontSize: 13, letterSpacing: 2 },
  carCenter:     { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  carBigBtn:     { width: 220, height: 220, borderRadius: 110, borderWidth: 3, borderColor: MUTED, backgroundColor: PANEL, alignItems: 'center', justifyContent: 'center' },
  carBigBtnActive: { borderColor: GOLD, backgroundColor: 'rgba(201,168,76,0.12)' },
  carBigIcon:    { fontSize: 84 },
  carStatus:     { color: GOLD, fontSize: 22, letterSpacing: 3, marginTop: 32, fontWeight: '700' },
  carLastReply:  { color: '#E8E8E8', fontSize: 16, textAlign: 'center', marginTop: 24, lineHeight: 24, paddingHorizontal: 12 },
});
