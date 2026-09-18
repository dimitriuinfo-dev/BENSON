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
  addListenRequestedListener, addWakeWordDetectedListener, addWakePokeListener, bringToForeground,
  isIgnoringBatteryOptimizations, requestIgnoreBatteryOptimizations,
  pauseHotword, resumeHotword, setSystemSoundsMuted, consumeRecoveryFlag, logAudioDiag,
  setSttLanguage, setWakeWordEnabled, isWakeWordEnabled, updateNotification,
  setHibernationEnabled, isHibernationEnabled,
  setPorcupineAccessKey, getPorcupineStatus, getActiveWakeEngine,
  nativeWakeSetOwner, isNativeWakeAvailable, setWakeName, setNativeWakeCredentials,
  setConfirmationSttCredentials,
  armSttSessionWatchdog, cancelSttSessionWatchdog, addSttWatchdogTimeoutListener,
  armTtsWatchdog, cancelTtsWatchdog, addTtsWatchdogTimeoutListener,
  startConfirmationListening, cancelConfirmationListening, addConfirmationResultListener,
  takePendingWakeCommand,
} from 'benson-foreground-service';
import {
  hasOverlayPermission, requestOverlayPermission, showBubble, hideBubble,
  addBubbleTappedListener, hideWakeRing, updateBubbleStatus, setBubbleMotion, playWakeSound,
  setMicLevel,
} from 'benson-overlay';
import { isServiceEnabled as isAccessibilityEnabled, getConnectionState as getA11yConnectionState, openAccessibilitySettings, openRecents, whatsAppCallMicHoldActive, clearWhatsAppCallMicHold, consumeCallEndedReturnPending, getWhatsAppCallEndedSignalAt } from 'benson-accessibility';

// WA-CALL-STAYS-LIVE — true while a WhatsApp voice call BENSON just placed is still live. Source of
// truth is native (the executor that verified the call screen). Wrapped so a missing/old native
// module can never break the listen loop.
function waCallMicHoldActiveSafe(): boolean {
  try { return whatsAppCallMicHoldActive(); } catch { return false; }
}
import { classifyEmergencyIntent, getEmergencyContext, routeEmergencyCall } from 'benson-app-registry';
import { startAccessibilityWatch, ACCESSIBILITY_ALERT_NOTIFICATION_TAG } from '../lib/accessibilityWatchdog';
import { ensureAccessibilityReady, ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO } from '../src/core/safety';
import type { Character, AnthropicMsg, FamilyMember } from '../lib/agents/types';
import type { ContentCard } from '../lib/agents/contentTypes';
import { routeCommand, conversationFallbackLine, type ModelProvider } from '../lib/agents/orchestrator';
import { CALL_PATTERN } from '../lib/agents/contactsAgent';
import { parseCommandToActionRequest } from '../src/core/action-engine';
// Round 2 — swappable engine layer wiring.
// Build A: memory guard + the STT/Groq-key Settings section.
// Build B: the brain as the conversation + intent route (routeThroughBrain), the canonical-command
// bridge to the existing deterministic executor, and the shared contact-param sanity checkpoint.
import { checkMemoryWrite } from '../lib/engines/memory/memoryGuard';
import { routeThroughBrain, buildCanonicalCommand, contactParamOf } from '../lib/engines/brainRouter';
import type { KnownAction } from '../lib/engines/types';
import { resolvePerson, type ResolutionCandidate, type ResolvedPerson } from '../src/core/contacts/contextResolver';
import { getVerifiedIdentities } from '../src/core/contacts/verifiedIdentityStore';
import { sanityCheckContactParam } from '../lib/engines/actionSanity';
import {
  getSelectedEngineId, setSelectedEngineId, getEngineConfig, saveEngineConfig, maskApiKey,
} from '../lib/engines/settingsStore';
import { testGroqConnection, GROQ_STT_DEFAULT_BASE_URL, GROQ_STT_DEFAULT_MODEL } from '../lib/engines/stt/groqStt';
import {
  requestMicPermission, checkMicPermission, startRecognition, stopRecognition,
  addResultListener, addErrorListener, addEndListener, addVolumeListener,
  addSpeechStartListener, addSpeechEndListener,
  startWakeScan, stopWakeScan,
  speakNow, stopSpeaking, getAvailableVoices,
  isOnDeviceLocaleInstalled, triggerOfflineModelDownload,
  setOpenAIKeyForStt, setGeminiKeyForStt, getLastUtteranceBytes,
  type Voice, type SttEngine,
} from '../lib/agents/voiceAgent';
import { preloadLocalWhisper, subscribeWhisperStatus, type WhisperStatus } from '../lib/agents/localWhisperEngine';
import { preloadWakeChime, playWakeChime, setWakeChimeVolume, unloadWakeChime } from '../lib/agents/wakeChime';
import { setNormalAudioMode, releaseAudioFocusMode } from '../lib/agents/audioMode';
import { speakWithOpenAI, stopOpenAITTS } from '../lib/agents/openaiTTS';
import { speakWithGemini, stopGeminiTTS } from '../lib/agents/geminiTTS';
import { buildVoiceInstructions, currentTimeOfDay } from '../lib/agents/voiceInstructions';
import { startCarAutoDetection, type CarAutoDetectHandle } from '../lib/carAutoDetect';
import { startScreenBridge, getLastScreenSnapshot } from '../lib/screenBridge';
import { runMission, resumePendingTask } from '../src/core/orchestrator';
import type { MissionPlan } from '../src/core/orchestrator';
import type { TrustedContact } from '../src/core/contacts';
import { loadDeviceContacts as loadRealDeviceContacts, getContactsPermissionState, requestContactsPermission } from '../src/core/contacts';
import {
  hydrateActiveMission, getActiveMission, confirmActiveMission, cancelActiveMission,
  supersedeActiveMission, resolveActiveMissionFromUtterance,
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
// Explicit refusal — used alongside YES_PATTERN so a pending confirmation (WhatsApp call, Waze,
// etc.) is only ever dropped on a DELIBERATE reply, not silently on noise (see cancelActiveMission
// call site below for why this matters).
const NO_PATTERN = /\b(nu|no|anuleaz[ăa]|renun[țt][ăa]|stop|cancel)\b/i;

// ── RUNDA ORCH-FIX-1 (2026-09-08) — clasificator robust de confirmare ────────────────────────
// Audit read-only: YES_PATTERN (`confirm[aă]?t?\b`) rata "Confirme.", "Confirmi", "Confirmați" —
// chiar cuvântul din propriul prompt "Confirmi?". Aici: normalizează (fără diacritice, minuscule,
// doar litere/cifre/spații), apoi verifică ÎN ORDINE STRICTĂ:
//   1) NU explicit   2) DA explicit (inclusiv rădăcina `confirm\w*` pe cuvânt întreg)   3) UNKNOWN
// O negație explicită ("Nu confirm") câștigă întotdeauna înaintea lui DA. `confirm\w*` cere
// literalul "confirm" la început de cuvânt — nu prinde cuvinte fără legătură.
function normConfirm(raw: string): string {
  return raw
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
const CONFIRM_NO_RE = /\b(nu|no|nein|nu-i|anuleaza|renunta|opreste|stop|cancel|abbrechen|negativ)\b/;
const CONFIRM_YES_RE = /\b(da|dap|yes|yeah|yep|yup|sure|ok|okay|sigur|desigur|bineinteles|corect|exact|perfect|pregat\w*|confirm\w*)\b/;
type ConfirmVerdict = 'YES' | 'NO' | 'UNKNOWN';
function classifyConfirmation(raw: string): ConfirmVerdict {
  const t = normConfirm(raw);
  if (!t) return 'UNKNOWN';
  if (CONFIRM_NO_RE.test(t)) return 'NO';   // 1. NU explicit — bate întotdeauna DA
  if (CONFIRM_YES_RE.test(t)) return 'YES'; // 2. DA explicit
  return 'UNKNOWN';                         // 3. altfel
}
// Câte re-prompt-uri pe UNKNOWN acceptăm înainte să renunțăm curat (fără buclă infinită).
const MAX_CONFIRM_REPROMPTS = 3;

// ROUND_EMERGENCY_CORE_1 — revert switch. Set to false to fully disable the emergency gate in
// handleIncomingText (classifier + "Sun la 112?" flow + native 112 route) and restore the prior
// behaviour where "ajutor" falls through to the HELP parser.
const EMERGENCY_GATE_ENABLED = true;

// Task 2 (2026-08-28): a Confirmation Gate must not be satisfied by a hallucination. Confirmed
// live — the local capture handed Groq/Whisper a 0-byte WAV and it returned raw="Da, confirm.",
// which matched YES_PATTERN and executed a pending action nobody consented to. A deliberate "da"
// is ≥ ~0.25s of speech; capture is 16 kHz mono 16-bit = 32000 bytes/s, so 8000 bytes ≈ 0.25s.
// Below this, a voice affirmative into an open gate is dropped (CONFIRM_REJECTED) and the gate
// stays pending. Bytes of -1 (Android SpeechRecognizer, no file) or a typed reply are exempt.
const MIN_CONFIRM_BYTES = 8000;
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

type TtsProvider = 'device' | 'openai' | 'gemini';

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

// Build B — utterances that ask BENSON to read/act on what's on screen. Only when this matches
// does the current on-screen snapshot get attached to the brain turn (as UNTRUSTED_DATA) — so
// screen contents aren't shipped to the brain on every unrelated sentence.
const SCREEN_READ_PATTERN =
  /\b(?:cite[sșş]te|cite[sșş]ti|ce\s+(?:scrie|zice|e)\s+(?:pe\s+)?ecran|read|lies|vezi)\b.{0,20}\b(?:ecran(?:ul)?|screen|bildschirm|display)\b/i;

// Build B — the one "who should I call?" clarify line, in the active language. Spoken when the
// shared contact-param sanity check rejects a name on either route (parser or brain).
function askWhoToCall(lang: string, address: string): string {
  const l = (lang || '').toLowerCase();
  if (l.startsWith('ro')) return `Pe cine să sun, ${address}?`;
  if (l.startsWith('de')) return `Wen soll ich anrufen, ${address}?`;
  return `Who should I call, ${address}?`;
}

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
];

const GREETINGS: Record<string, string> = {
  'en-GB': 'BENSON online, {name}. What can I do for you?',
  'ro-RO': 'BENSON online, {name}. Ce fac pentru tine?',
  'de-DE': 'BENSON online, {name}. Was kann ich für dich tun?',
};
const CONV_ON: Record<string, string> = {
  'en-GB': 'Conversation mode on. Listening.',
  'ro-RO': 'Mod conversație activat. Ascult.',
  'de-DE': 'Gesprächsmodus aktiv. Ich höre zu.',
};
const CONV_OFF: Record<string, string> = {
  'en-GB': 'Conversation mode off.',
  'ro-RO': 'Modul conversație dezactivat.',
  'de-DE': 'Gesprächsmodus beendet.',
};

// Spoken cue after a bare "Benson" (no command in the same breath) — replaces a silent/haptic-only
// transition into command-capture mode with an audible signal the user is actually being heard.
const WAKE_LISTENING_PROMPT: Record<string, string> = {
  'en-GB': "I'm listening.",
  'ro-RO': 'Te ascult.',
  'de-DE': 'Ich höre.',
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
  const [geminiKey, setGeminiKey]     = useState('');
  // Round 2 — Groq key + STT nucleus selector, both surfaced in the one Settings modal.
  const [groqKey, setGroqKey]         = useState('');
  const [savedGroqMasked, setSavedGroqMasked] = useState('(none)');
  // DEV_STT_DEEPGRAM_1 (2026-09-16) — development-only main/confirmation STT key while Groq's
  // quota is exhausted (see memory project_groq_quota_blocker). Same secure-store/masked-field
  // pattern as the Groq key above.
  const [deepgramKey, setDeepgramKey] = useState('');
  const [savedDeepgramMasked, setSavedDeepgramMasked] = useState('(none)');
  const [sttNucleusId, setSttNucleusId] = useState<'groq' | 'local'>('groq');
  const [groqTestStatus, setGroqTestStatus] = useState('');
  const [groqTesting, setGroqTesting] = useState(false);
  // ROUND_WAKE_NATIVE_GENERIC_1 — the ONE authoritative wake-name config. Native-persisted
  // (survives JS suspension / service recreation) so the native cloud wake loop reads exactly
  // what this field writes. Default "Benson"; the JS foreground detectWakeWord()/WAKE_VARIANTS
  // path below is untouched this round (proven-working code, not rewritten — see report).
  const [wakeName, setWakeNameState] = useState('Benson');
  const wakeNameRef = useRef('Benson');
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

  // Battery-fix hibernation kill switch (product-owner-directed 2026-09-18) — default OFF until
  // proven on device. Same restore/push idiom as wakeWordEnabled above; enforcement is entirely
  // native (BensonForegroundService.kt), this only drives the Settings Switch.
  const [hibernationEnabled, setHibernationEnabledState] = useState(false);

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
    if (settingsOpen) {
      (async () => {
        try {
          const cfg = await getEngineConfig('stt', 'groq');
          setSavedGroqMasked(cfg?.apiKey ? maskApiKey(cfg.apiKey) : '(none)');
          const sel = await getSelectedEngineId('stt', 'groq');
          setSttNucleusId(sel === 'local' ? 'local' : 'groq');
          const dgCfg = await getEngineConfig('stt', 'deepgram');
          setSavedDeepgramMasked(dgCfg?.apiKey ? maskApiKey(dgCfg.apiKey) : '(none)');
        } catch {}
        setGroqKey(''); setGroqTestStatus(''); setDeepgramKey('');
      })();
    }
  }, [settingsOpen]);

  async function changeSttNucleus(id: 'groq' | 'local') {
    setSttNucleusId(id);
    setGroqTestStatus('');
    try { await setSelectedEngineId('stt', id); } catch {}
  }

  async function runGroqTest() {
    setGroqTesting(true);
    setGroqTestStatus('');
    try {
      const saved = await getEngineConfig('stt', 'groq');
      const cfg = {
        baseUrl: saved?.baseUrl || GROQ_STT_DEFAULT_BASE_URL,
        model: saved?.model || GROQ_STT_DEFAULT_MODEL,
        apiKey: groqKey.trim() || saved?.apiKey || '',
      };
      if (!cfg.apiKey) { setGroqTestStatus('EROARE: nicio cheie Groq salvată'); return; }
      const r = await testGroqConnection(cfg);
      setGroqTestStatus(r.ok ? 'OK' : `EROARE: ${r.error}`);
    } catch (e) {
      setGroqTestStatus(`EROARE: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setGroqTesting(false);
    }
  }

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
  const geminiKeyRef    = useRef('');
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
  // 2026-08-28 — a partial result is NOT treated as final immediately on session end. When 'end'
  // fires with only a partial captured, we wait PARTIAL_FALLBACK_GRACE_MS for a real final; only
  // if none arrives is the partial dispatched, logged distinctly (STT_PARTIAL_FALLBACK).
  const partialFallbackTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
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
  // Post-action mute net (2026-08-28, temporary): right after BENSON acts in another app (e.g. a
  // governed Waze/WhatsApp mission), that app's own voice (Waze turn-by-turn) or BENSON's spoken
  // outcome can bleed straight back into the next capture. Until the native audio-focus ducking is
  // proven sufficient on device, doStartListening() defers by whatever is left of this window.
  // One constant, one ref — easy to pull out once ducking is confirmed enough.
  const POST_ACTION_MUTE_MS = 3000;
  const postActionMuteUntilRef = useRef(0);
  // WA-CALL-STAYS-LIVE (2026-09-08, product-owner-directed): once BENSON has placed a WhatsApp
  // voice call and the native executor confirmed the call screen is up, BENSON must go fully quiet
  // and NOT reopen the mic / restart the wake-word loop — that concurrent mic grab is what was
  // tearing the fresh WhatsApp call down on this device. While Date.now() < this value, every
  // listen/wake entry point (doStartListening, resumePassiveWake, resumeListeningAfterUnblock)
  // bails with MIC_BLOCKED reason=whatsapp_call_live, leaving the mic to WhatsApp. It is cleared
  // early the moment BENSON's own Activity is foregrounded again (AppState 'active') — by then the
  // user is back in BENSON and the call is on speaker / ended. Revert: set WA_CALL_MIC_HOLD_MS = 0.
  const WA_CALL_MIC_HOLD_MS = 180_000;
  const whatsappCallMicHoldUntilRef = useRef(0);
  // WA-LIFECYCLE-FIX-1 — last native "verified WhatsApp call ended" signal timestamp this JS side
  // has already acted on. The self-heal loop clears the JS hold + re-arms wake when the persisted
  // native signal is newer than this, so recovery works even with no AppState 'active' transition
  // (BENSON still backgrounded after a post-call OxygenOS service kill/revive).
  const lastCallEndedSignalRef = useRef(0);
  // Hard mic close during TTS (2026-08-29): the mic is fully shut the whole time BENSON is speaking
  // and stays shut for TTS_TAIL_MS after the last word, so BENSON can never transcribe its own
  // voice — no reliance on acoustic echo cancellation. beginTtsBlock() stops any in-flight capture
  // and raises speakingRef; endTtsBlock() drops it and arms the tail window. doStartListening() and
  // startLocalWakeLoop() honour both (MIC_BLOCKED / MIC_RESUMED).
  const TTS_TAIL_MS = 500;
  const micResumeAtRef = useRef(0); // Date.now() before which no capture may start
  const ttsEndedAtRef = useRef(0);  // when the last TTS utterance finished (for MIC_RESUMED afterMs)
  // ── BENSON_STABILIZATION_1 — self-audio turn gate. INVARIANT: BENSON_OUTPUT can NEVER become
  // USER_INPUT. A transcript is only accepted if its capture started AFTER TTS fully ended + a
  // bounded audio-tail guard, BENSON is not speaking now, and the session id is current. ───────
  const AUDIO_TAIL_GUARD_MS = 1200;
  const sttCaptureStartedAtRef = useRef(0);  // Date.now() when the CURRENT mic capture (wake or command) began
  // ROUND_MIC_CAPTURE_DIAGNOSTICS_1 — truthful timing/amplitude telemetry for one capture turn.
  // t0 = sttCaptureStartedAtRef.current. All *At fields are absolute Date.now(); -1 = never observed
  // this turn (never fabricated as 0 or guessed). Reset at STT_START_CALLED, read+cleared at the
  // first terminal event (result/error/end) for this session.
  const micCaptureDiagRef = useRef({
    active: false, sessionId: '', recorderCreateAt: -1, sttStartCalledAt: -1,
    speechStartAt: -1, speechEndAt: -1, firstRmsAboveThresholdAt: -1,
    peakRms: 0, rmsSum: 0, rmsCount: 0,
  });
  const MIC_RMS_THRESHOLD = 0.15; // matches the visible-motion floor used elsewhere for "audio present"

  // URGENT_CONFIRMATION_NATIVE_1 — non-null while a native one-shot confirmation capture is armed
  // (armed right when TTS finishes speaking a mission-gate question, e.g. "Îl trimit?"). The JS
  // mic loop (doStartListening) must not compete with it — proven live that BENSON backgrounded
  // into WhatsApp left the JS-owned retry timers inert, so "da" was never heard at all.
  const pendingConfirmationIdRef = useRef<string | null>(null);
  const CONFIRMATION_LISTEN_TIMEOUT_MS = 8000;
  // ROUND_GENERIC_CONFIRMATION_FIX_1 — one-shot signal that the message finishHandledMission()
  // just queued for TTS is a disambiguation question ("Am găsit Rechner. O deschid?"), so
  // endTtsBlock() arms the SAME native confirmation listener as the WhatsApp mission-gate case
  // instead of leaving the reply to the generic doStartListening() path (device-proven unreliable:
  // local-Whisper self-echo contamination, or the reply lost to the wake engine). Set fresh on
  // every finishHandledMission() call (true or false) and consumed/cleared by endTtsBlock() the
  // instant it's read, so it can never leak into an unrelated later TTS-end event.
  const pendingDisambigReplyRef = useRef<boolean>(false);
  function logMicCaptureDiag(reason: string, transcript: string) {
    const d = micCaptureDiagRef.current;
    if (!d.active) return;
    d.active = false;
    const t0 = sttCaptureStartedAtRef.current;
    const rel = (at: number) => (at < 0 ? 'n/a' : `${at - t0}`);
    const avgRms = d.rmsCount > 0 ? (d.rmsSum / d.rmsCount).toFixed(3) : 'n/a';
    logAudioDiag('MIC_CAPTURE_DIAG', `session=${d.sessionId} reason=${reason} ` +
      `recorderCreateMs=${rel(d.recorderCreateAt)} sttStartMs=${rel(d.sttStartCalledAt)} ` +
      `speechStartMs=${rel(d.speechStartAt)} firstRmsMs=${rel(d.firstRmsAboveThresholdAt)} ` +
      `speechEndMs=${rel(d.speechEndAt)} resultMs=${Date.now() - t0} ` +
      `peakRms=${d.peakRms.toFixed(3)} avgRms=${avgRms} rmsSamples=${d.rmsCount} ` +
      `transcript="${transcript.slice(0, 60)}"`);
  }
  // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — when benson.tflite is bundled the native TFLite engine in
  // BensonForegroundService owns passive wake (survives JS suspension). JS then only drives the
  // mic-ownership handoff at command/TTS/call boundaries and never runs its own wake loop.
  const nativeWakeRef = useRef(false);
  const nativeWakeEventAtRef = useRef(0);    // Date.now() a native wake event reached JS (for WAKE_TO_COMMAND_LATENCY)
  const nwOwner = (o: 'WAKE' | 'COMMAND_STT' | 'TTS' | 'CALL' | 'NONE') => {
    if (!nativeWakeRef.current) return;
    try { nativeWakeSetOwner(o); } catch {}
  };
  // ── WAKE HEALTH DIAGNOSIS — real runtime state, don't trust the notification ─────────────────
  const lastAudioAtRef = useRef(0);          // last mic RMS/volume callback = audio frames arriving
  const a11yBoundRef = useRef<'BOUND' | 'UNBOUND' | 'UNKNOWN'>('UNKNOWN');
  const wakeWordEnabledRef = useRef(true);   // native passive-listen kill switch
  const wakeHealthLineRef = useRef('');      // last emitted WAKE_HEALTH line (dedupe)
  const wakeHealthAtRef = useRef(0);
  const notifBodyRef = useRef('');           // last body pushed to the foreground-service notification
  // ── Treapta 1 / C1 — background lifecycle collapse ────────────────────────────────────────────
  // Confirmed live (crash.txt 2026-08-29): after an action launches another app, BENSON's Activity
  // is no longer resumed, so a network-TTS onDone callback never fires, endTtsBlock() never runs,
  // speakingRef stays true forever, and every listen restart no-ops at the `|| speakingRef.current`
  // guard — BENSON goes permanently deaf until the user taps the logo. Two backstops:
  //   1. a hard timer in beginTtsBlock() force-drops speakingRef after TTS_MAX_BLOCK_MS no matter
  //      what happened to the TTS callbacks;
  //   2. returning to the foreground unconditionally resets the speaking state (see AppState 'active').
  // Revert: TTS_MAX_BLOCK_MS = Number.POSITIVE_INFINITY disables the hard timer.
  const TTS_MAX_BLOCK_MS = 15000;
  const ttsHardTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const ttsBlockStartedAtRef = useRef(0); // Date.now() when the current TTS block was raised

  // ── Runda C1 (2026-09-08) — prăbușirea ciclului de viață în fundal ───────────────────────────
  // Simptom dovedit pe dispozitiv: „Am rămas blocat la «resume_task»" după ce BENSON lansează
  // WhatsApp. beginTtsBlock() ridică speakingRef, WhatsApp trece în prim-plan, activitatea RN nu
  // mai e resumed → callback-ul de final TTS nu se declanșează → endTtsBlock() nu se apelează
  // → speakingRef rămâne true definitiv → fiecare captură cade la MIC_BLOCKED reason=tts_speaking.
  //   C1_TTS_END_ALL_PATHS: endTtsBlock(reason) pe TOATE ieșirile (succes/eroare/întrerupere/
  //     fundal/stop), fiecare cu TTS_BLOCK_END reason=…. Nicio cale nu lasă speakingRef ridicat.
  //   C1_TTS_WATCHDOG: timer dur armat la beginTtsBlock — coboară forțat speakingRef după
  //     TTS_MAX_BLOCK_MS indiferent de callback-uri. Log TTS_WATCHDOG_FIRED forcedRelease=true.
  //   C1_RESUME_DECOUPLED: resume_task se încheie la terminarea acțiunii SAU la revenirea în
  //     prim-plan, oricare prima; la revenire — reset speakingRef + repornire ascultare. Log RESUME.
  //   C1_NO_SILENT_STALL: un resume_task nereluabil NU mai afișează „Am rămas blocat" — reset IDLE
  //     curat + repornire ascultare + RESUME_FAILED reason=… recovered=idle.
  // Toate pe false → comportamentul de azi.
  const C1_TTS_END_ALL_PATHS = true;
  const C1_TTS_WATCHDOG = true;
  const C1_RESUME_DECOUPLED = true;
  const C1_NO_SILENT_STALL = true;
  // Marcat cât un resume_task e „în zbor" (între setBensonState('EXECUTING','resume_task') și
  // rezultatul lui) — citit de handler-ul AppState 'active' ca să reia curat la revenire.
  const resumeInFlightRef = useRef<{ startedAt: number } | null>(null);

  // ── Runda C3 (2026-09-08) — o rostire = o singură sesiune STT ────────────────────────────────
  // Dovedit pe dispozitiv (c1.log 08:46:38): două STT_REQUESTED trigger=conversation_mode la 2ms
  // distanță pe aceeași rostire → două RECORDER_CREATE → două recordere se bat pe microfon → unul
  // întoarce peakRms=32 / no_speech. Cauza: garda din capul lui doStartListening()
  // (`if (listeningRef.current || loadingRef.current) return;`) verifică un flag care se ridică abia
  // DUPĂ `await Promise.all([pauseHotword(), checkMicPermission()])`. Un al doilea apel care
  // aterizează în fereastra acelui await (~ms, dus-întors pe bridge-ul nativ) trece de gardă și
  // pornește un al doilea recorder. `sttSessionActiveRef` e o gardă SINCRONĂ, ridicată înainte de
  // orice await și coborâtă doar la închiderea sesiunii (endSub / errorSub / fundal / eroare).
  //   C3_SINGLE_SESSION_GATE: al doilea doStartListening() cât unul e activ → STT_REJECTED, return.
  //   C3_SESSION_CLEANUP: la fiecare sfârșit de sesiune (rezultat/eroare/timeout/fundal) sesiunea
  //     se marchează închisă (STT_SESSION_CLOSED) — nicio sesiune „fantomă" care ar bloca următoarea.
  // Ambele pe false → comportamentul de azi. (Există un `C3_SINGLE_SESSION_GATE` geamăn în
  // lib/agents/voiceAgent.ts — apărare în adâncime pentru cele 15+ căi de intrare; pune-le pe
  // amândouă pe false pentru revert complet.)
  const C3_SINGLE_SESSION_GATE = true;
  const C3_SESSION_CLEANUP = true;
  // Non-null = o sesiune STT e în zbor chiar acum (id-ul ei). Citit sincron la intrarea în
  // doStartListening ca gardă de reintrare peste fereastra de await.
  const sttSessionActiveRef = useRef<string | null>(null);

  // ── Runda C3-fix (2026-09-08) — sesiunea STT nu se închidea niciodată ────────────────────────
  // Dovedit în log: sesiunea js-…747825 pornește 09:15:47, captura se termină 09:15:50 cu
  // reason=stopped, dar STT_SESSION_CLOSED NU apare → sttSessionActiveRef rămâne ridicat pe veci
  // → fiecare încercare ulterioară e respinsă (STT_REJECTED reason=session_active) la fiecare 2s,
  // minute în șir. BENSON complet surd. Cauza reală: doStartListening() apela stopWakeScan()
  // ÎNAINTE de garda C3; când o captură `local` era deja în zbor, stopWakeScan() îi demonta
  // `sharedCaptureEndSub` (variabilă partajată, prin design) → event-ul de sfârșit al capturii
  // ajungea în gol → emitEnd() nu se declanșa → endSub nu rula → nimeni nu cobora garda.
  // Fix, în adâncime:
  //   1. garda C3 se mută în capul lui doStartListening (înainte de stopWakeScan) — un apel
  //      respins nu mai atinge nimic.
  //   2. sttSessionActiveRef se coboară DIRECT (closeSttSession) pe TOATE căile de sfârșit —
  //      result | error | stopped | no_speech | timeout | background — nu doar prin endSub.
  //   3. plasă de siguranță: dacă o sesiune e activă > STT_SESSION_MAX_MS, watchdog-ul o coboară
  //      forțat (STT_SESSION_WATCHDOG fired=true) și repornește ascultarea.
  //   4. voiceAgent.ts garantează emitEnd() în ≤500ms după orice stopRecognition(), chiar dacă
  //      onCaptureEnd nativ nu mai livrează nimic — ca bucla hands-free (nu doar garda) să reia.
  // Revert: STT_SESSION_MAX_MS = Number.POSITIVE_INFINITY dezarmează watchdog-ul; C3_* pe false.
  const STT_SESSION_MAX_MS = 30000;

  // ── Treapta 1 / C2 — listening does not self-heal ────────────────────────────────────────────
  // The listen loop has no supervisor: if it stops for any reason, only a screen tap brings it
  // back. A periodic check restarts whatever SHOULD be listening (command capture in conv mode,
  // passive wake scan otherwise). Revert: LISTEN_SELF_HEAL_MS = 0 disables the interval.
  const LISTEN_SELF_HEAL_MS = 2000;
  // Timestamp a pending confirmation gate was armed — a gate still open on foreground return after
  // this long is stale (BENSON was deaf in between, the user has moved on) and is cleared.
  const PENDING_STALE_MS = 45000;
  const gateArmedAtRef = useRef(0);

  // ── Runda E1 (2026-09-07) — BENSON acționează EXCLUSIV la comanda/atingerea utilizatorului ────
  // E1_USER_ONLY: nicio rostire, niciun salut, nicio întrebare de inițiere care nu urmează unei
  //   rostiri recunoscute a utilizatorului sau unei atingeri directe a lui. O rostire blocată de
  //   regula asta lasă log SPEAK_SUPPRESSED reason=no_user_command. Salutul de la pornire dispare;
  //   la fel rostirile la timer / la revenire în prim-plan / după auto-reparare (Guardian).
  //   Revert: E1_USER_ONLY = false → totul revine exact ca înainte.
  // E1_NO_SELF_FOREGROUND: BENSON nu se mai aduce singur în prim-plan din JS (la wake). Bula +
  //   inelul rămân ca indicatori. (Căile native au propriile constante — vezi ROUND_E1_REPORT.md.)
  //   Revert: E1_NO_SELF_FOREGROUND = false.
  // USER_ACTION_WINDOW_MS: cât timp după o acțiune-user o rostire mai e considerată „a userului"
  //   (o comandă cu STT lent + execuție poate dura > 60s, de-aia 90s).
  const E1_USER_ONLY = true;
  const E1_NO_SELF_FOREGROUND = true;
  const USER_ACTION_WINDOW_MS = 90000;

  // ── Runda E2 (2026-09-07) ────────────────────────────────────────────────────────────────────
  // E2_LISTEN_ON_OPEN: deschiderea aplicației / atingerea bulei = USER_TOUCH → ascultarea pornește
  //   INSTANT (nu prin self-heal la 2s). Fără chime, fără rostire. Log LISTEN_STARTED source=user_open.
  //   Nu încalcă E1-0 — sursa e atingerea directă. Revert: E2_LISTEN_ON_OPEN = false.
  // E2_BUBBLE_BAND: banda scrisă lângă bulă (text transcris + stare) cât BENSON e activ peste altă
  //   aplicație. Revert: E2_BUBBLE_BAND = false → banda nu mai apare niciodată.
  const E2_LISTEN_ON_OPEN = true;
  const E2_BUBBLE_BAND = true;
  // ── Runda E3 (2026-09-07) — banda lizibilă din mașină, fără ochelari ─────────────────────────
  // Fontul/lățimea/umbra sunt în modules/benson-overlay (BensonBubbleService.updateStatus).
  // ROUND_BUBBLE_STATE_DESYNC_FIX_1 — cât timp banda rămâne vizibilă după „GATA" e acum decis
  // NATIV (BensonBubbleService.TERMINAL_DISMISS_MS, ~2.5s), nu de un setTimeout aici — un timer JS
  // nu supraviețuiește suspendării RN pe fundal (dovedit live în alte runde ale acestei sesiuni).
  // E3_BAND_LINGER_MS rămâne doar ca document istoric al valorii vechi (6000ms) — nu mai e folosit.
  // SHADOW_MODE_1 (2026-09-14) — removed per explicit instruction: no decorative activation sound
  // on any listen-start (touch / manual open / wake word), and no fallback to the older chime
  // either. Was E3-3's procedurally-synthesised "microphone is open" SF tone (WakeSound.kt) with a
  // playWakeChime() fallback when disabled — both removed, not swapped for a third sound.
  function playListenStartSound() {
    // intentionally silent
  }
  // E3-2 — starea agentului → mișcarea punctelor din bulă. Doar LISTENING rotește; EXECUTING
  // pulsează; restul stă. Gated pe serviciul activ ca să nu pornim serviciul bulei degeaba.
  function pushBubbleMotion(state: string) {
    if (!serviceActiveRef.current) return;
    const motion = state === 'LISTENING' ? 'listening' : state === 'EXECUTING' ? 'executing' : 'static';
    try { setBubbleMotion(motion); } catch {}
  }
  // Ultima rostire transcrisă a utilizatorului — arătată în banda de lângă bulă.
  const lastUserTranscriptRef = useRef('');
  // Dacă banda e afișată acum — ca să nu pokăm serviciul nativ când n-avem nimic de ascuns.
  const bandVisibleRef = useRef(false);
  // Timer-ul de „mai ține GATA vizibil E3_BAND_LINGER_MS".
  const bandHideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Starea curentă → cuvântul scurt din bandă (E3: MAJUSCULE, gold). IDLE/BLOCKED/PAUSED → fără
  // etichetă → banda dispare (cu excepția lingerului de după GATA).
  const E2_STATE_LABEL: Partial<Record<'IDLE'|'LISTENING'|'THINKING'|'CONFIRMING'|'EXECUTING'|'DONE'|'ERROR'|'BLOCKED', string>> = {
    LISTENING: 'ASCULT',
    THINKING: 'AM ÎNȚELES',
    CONFIRMING: 'AM ÎNȚELES',
    EXECUTING: 'EXECUT',
    DONE: 'GATA',
    ERROR: 'GATA',
  };
  function clearBandHideTimer() {
    if (bandHideTimerRef.current) { clearTimeout(bandHideTimerRef.current); bandHideTimerRef.current = null; }
  }
  function hideBubbleBandNow() {
    clearBandHideTimer(); // vestigial (see below) — harmless no-op now that native owns dismiss timing
    if (!bandVisibleRef.current) return;
    bandVisibleRef.current = false;
    // ROUND_BUBBLE_STATE_DESYNC_FIX_1 — an explicit hide request. Native treats this identically
    // to its own auto-dismiss (dismissNow()) and is idempotent — a no-op if native already
    // auto-dismissed on its own by the time this arrives (the normal case: native's
    // TERMINAL_DISMISS_MS is always shorter than STATE_DONE_CLEAR_MS/STATE_ERROR_CLEAR_MS, so
    // this JS-side call essentially never beats native to it — it exists for the non-terminal
    // paths, e.g. leaving the foreground/silencing, where nothing else would ever hide the band).
    try { updateBubbleStatus('', '', false, false, Date.now()); } catch {}
  }
  // Împinge banda spre overlay-ul nativ. Se afișează DOAR cât BENSON NU e aplicația din prim-plan
  // (pe ecranul lui propriu, transcrierea e deja în chat).
  // ROUND_ASSISTANT_SESSION_UX_FIX_1 — this call is now ALWAYS non-terminal (`terminal=false`),
  // even for DONE/ERROR ("GATA"): reaching that state does NOT by itself mean the result is done
  // being read — BENSON may still be speaking it (a long reply easily outlasts the old fixed
  // ~2.5s dismiss, confirmed live: the HandyParken result vanished mid-speech). The ONLY place
  // that ever sends terminal=true now is scheduleResultDismiss() below, called once TTS for the
  // result has actually finished (or immediately if none was needed) — see endTtsBlock() and
  // finishHandledMission()'s ack-skip branch. Until then, this just keeps the card up via
  // native's long non-terminal safety-net, identically to LISTENING/THINKING/EXECUTING/CONFIRMING.
  function pushBubbleBand(state: string) {
    if (!E2_BUBBLE_BAND) return;
    const label = (E2_STATE_LABEL as Record<string, string | undefined>)[state];
    const canShow = !isForegroundRef.current && !silencedRef.current && serviceActiveRef.current;
    logAudioDiag('UI_STATE_JS', `state=${state} label=${label ?? ''} canShow=${canShow}`);

    if (label && canShow) {
      bandVisibleRef.current = true;
      if (state === 'DONE' || state === 'ERROR') logAudioDiag('RESULT_SHOW', `state=${state}`);
      try { updateBubbleStatus(label, lastUserTranscriptRef.current, true, false, Date.now()); } catch {}
      return;
    }

    // No label (IDLE/BLOCKED) or can't show right now (foreground/silenced/no service).
    hideBubbleBandNow();
  }

  // ROUND_ASSISTANT_SESSION_UX_FIX_1 — the ONE place a result is ever marked terminal. Called
  // once the result is truly done being read/spoken: from endTtsBlock() once real TTS for it has
  // finished (RESULT_DWELL_AFTER_TTS_MS — "do not dismiss while its TTS is still playing"), or
  // immediately from finishHandledMission()'s ack-already-spoken skip branch when no additional
  // TTS plays for the final message at all (RESULT_DWELL_NO_TTS_MS, longer — nothing else marks
  // the moment the user could start reading it). Native then owns the actual countdown
  // (BensonBubbleService's Handler.postDelayed, survives JS suspension) — this only decides WHEN
  // to start it and how long, not whether it fires.
  const RESULT_DWELL_AFTER_TTS_MS = 3000;
  const RESULT_DWELL_NO_TTS_MS = 4500;
  function scheduleResultDismiss(dwellMs: number) {
    const state = bensonStateRef.current;
    if (state !== 'DONE' && state !== 'ERROR') return; // nothing to dwell — not a result turn
    const label = (E2_STATE_LABEL as Record<string, string | undefined>)[state];
    const canShow = !isForegroundRef.current && !silencedRef.current && serviceActiveRef.current;
    if (!label || !canShow) return; // foreground / silenced / no overlay to dismiss anyway
    logAudioDiag('RESULT_READ_DWELL_START', `dwellMs=${dwellMs}`);
    try { updateBubbleStatus(label, lastUserTranscriptRef.current, true, true, Date.now(), dwellMs); } catch {}
  }
  // Date.now() al ultimei rostiri recunoscute / atingeri directe. Pornește 0 → la boot nicio
  // rostire nu e „a userului", deci salutul/ orice rostire de pornire e suprimată automat.
  const lastUserActionAtRef = useRef(0);
  const ackSpokenThisTurnRef = useRef(false);
  function noteUserAction() { lastUserActionAtRef.current = Date.now(); }
  function userActionRecent() { return Date.now() - lastUserActionAtRef.current <= USER_ACTION_WINDOW_MS; }
  // Backstop gate for every TTS path — returns true if this utterance must be suppressed by E1-0.
  function e1SuppressSpeak(): boolean {
    if (!E1_USER_ONLY) return false;
    if (userActionRecent()) return false;
    logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command');
    return true;
  }

  // ── Treapta 2 / C8 — explicit state machine ─────────────────────────────────────────────────
  // One authoritative, exclusive state. Every transition is logged (STATE from=… to=… detail=…),
  // so any later defect is reportable from the log alone. Terminal states auto-return to IDLE with
  // the transient screen (card + last reply) cleared, so stale text never lingers (obs. 16). An
  // EXECUTING that never terminates is force-failed by the watchdog with the phase name — never a
  // silent hang (obs. 8). This is a NEW layer on top of the existing listening/loading/speaking
  // refs (which stay, per anti-regression rule 2) — it observes the known transition points and
  // owns the auto-clear + watchdog only.
  // Reverts: EXEC_WATCHDOG_MS = Number.POSITIVE_INFINITY (no watchdog);
  //          STATE_DONE_CLEAR_MS / STATE_ERROR_CLEAR_MS = 0 (no auto-clear).
  const STATE_DONE_CLEAR_MS = 4000;
  const STATE_ERROR_CLEAR_MS = 6000;
  // Doc asked for 15000. Deviating (rule 4): the PROVEN WhatsApp call recipe (29.08 13:24) took
  // ~20s end to end, so a 15s ceiling would kill a working call. 30s still catches the real
  // ~60s freeze seen in r.txt/crash.txt. A true per-step watchdog needs recipe→JS step ticks
  // (bigger change, deferred).
  const EXEC_WATCHDOG_MS = 30000;
  type BensonState = 'IDLE' | 'LISTENING' | 'THINKING' | 'CONFIRMING' | 'EXECUTING' | 'DONE' | 'ERROR' | 'BLOCKED';
  const bensonStateRef = useRef<BensonState>('IDLE');
  const stateClearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const execWatchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const execPhaseRef = useRef('');

  // Best-effort: does a BENSON reply read as a failure? Used only to label DONE vs ERROR — the
  // auto-clear + watchdog do not depend on it being perfect.
  function isFailureReply(text: string): boolean {
    return /nu (am reușit|găsesc|am putut|pot)|n-am putut|could ?n['o]t|couldn't|i couldn't|opened_manual|nu am nicio aplicaț|nu este instalat|nu găsesc/i.test(text || '');
  }

  function setBensonState(next: BensonState, detail = '') {
    const prev = bensonStateRef.current;
    // Suppress a same-state re-entry (was noisy: repeated DONE→DONE from a second mission_result).
    // EXECUTING is exempt so a new phase re-arms the watchdog.
    if (prev === next && next !== 'EXECUTING') return;
    bensonStateRef.current = next;
    logAudioDiag('STATE', `from=${prev} to=${next}${detail ? ` detail=${detail}` : ''}`);
    // ROUND_ASSISTANT_SESSION_UX_FIX_1 — CONFIRM_WAIT_START/END bracket exactly the window the
    // overlay/session must stay alive for a YES/NO or clarification, regardless of how it
    // resolves (answer, cancel, or the orchestrator's own PENDING_DISAMBIGUATION_TIMEOUT_MS).
    if (next === 'CONFIRMING' && prev !== 'CONFIRMING') logAudioDiag('CONFIRM_WAIT_START', `detail=${detail}`);
    else if (prev === 'CONFIRMING' && next !== 'CONFIRMING') logAudioDiag('CONFIRM_WAIT_END', `to=${next} detail=${detail}`);

    if (stateClearTimerRef.current) { clearTimeout(stateClearTimerRef.current); stateClearTimerRef.current = null; }

    if (next === 'EXECUTING') {
      if (detail) execPhaseRef.current = detail;
      if (execWatchdogRef.current) clearTimeout(execWatchdogRef.current);
      if (Number.isFinite(EXEC_WATCHDOG_MS)) {
        execWatchdogRef.current = setTimeout(() => {
          execWatchdogRef.current = null;
          if (bensonStateRef.current !== 'EXECUTING') return;
          logAudioDiag('EXEC_WATCHDOG', `phase=${execPhaseRef.current || 'unknown'} timeoutMs=${EXEC_WATCHDOG_MS}`);
          setLoading(false); loadingRef.current = false;
          // ── C1 TASK 4 — a stuck resume_task is NOT a "Am rămas blocat" dead end. Clean IDLE
          // recovery + restart listening. A butler frozen mid-handoff must not be a resting state.
          if (C1_NO_SILENT_STALL && execPhaseRef.current === 'resume_task') {
            logAudioDiag('RESUME_FAILED', `reason=exec_watchdog elapsedMs=${EXEC_WATCHDOG_MS} recovered=idle`);
            resumeInFlightRef.current = null;
            try { setActiveCard(null); } catch {}
            try { setLastReply(''); } catch {}
            setBensonState('IDLE', 'resume_recover');
            try { resumeListeningAfterUnblock(); } catch {}
            return;
          }
          const stuck = replyLangRef.current.toLowerCase().startsWith('ro')
            ? `Am rămas blocat la „${execPhaseRef.current || 'un pas'}", ${getAddress()}. Am oprit.`
            : `I got stuck at "${execPhaseRef.current || 'a step'}", ${getAddress()}. Stopped.`;
          addMessage('benson', stuck);
          // E1-0: timer-triggered — no unsolicited speech. The stuck state is visible on screen and
          // in the log (EXEC_WATCHDOG above). Revert: E1_USER_ONLY = false.
          if (E1_USER_ONLY) logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=exec_watchdog');
          else speak(stuck);
          setBensonState('ERROR', 'exec_watchdog');
        }, EXEC_WATCHDOG_MS);
      }
    } else if (execWatchdogRef.current) {
      clearTimeout(execWatchdogRef.current); execWatchdogRef.current = null;
    }

    if (next === 'DONE' || next === 'ERROR') {
      const ms = next === 'DONE' ? STATE_DONE_CLEAR_MS : STATE_ERROR_CLEAR_MS;
      if (ms > 0) {
        stateClearTimerRef.current = setTimeout(() => {
          stateClearTimerRef.current = null;
          try { setActiveCard(null); } catch {}
          try { setLastReply(''); } catch {}
          setBensonState('IDLE', 'autoclear');
        }, ms);
      }
    }

    // E2-2 — reflect the state (and the last transcript) in the written band next to the bubble.
    // On DONE/ERROR the band shows "gata" for STATE_DONE_CLEAR_MS, then the auto-IDLE above clears it.
    pushBubbleBand(next);
    // E3-2 — reflect the state in the bubble's inner dots (rotate while LISTENING, pulse while
    // EXECUTING, still otherwise).
    pushBubbleMotion(next);
  }
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
  // A rolling list, not a single slot (product-owner-confirmed live 2026-08-23 regression): a
  // single "last spoken" slot only protects against echo of the MOST RECENT utterance. Confirmed
  // live that BENSON's own boot greeting ("BENSON online, Master. What can I do for you?") was
  // picked up by the mic and transcribed almost verbatim ("De skip benson online, master, what can
  // it do for you?", 73% word overlap) — well above the 60% self-echo threshold below — yet was
  // NOT rejected, because local Whisper transcription can now take well over a minute (worse since
  // the small-model upgrade), and something else got spoken in between, overwriting the single slot
  // before the delayed check ever ran. Keeping the last few spoken phrases (pruned by the same
  // ECHO_WINDOW_MS below) instead of just one closes that gap without changing the matching logic.
  const lastSpokenRef = useRef<Array<{ normalized: string; at: number }>>([]);
  // Circuit breaker for the self-sustaining "I did not quite catch that" loop (product-owner-
  // confirmed live 2026-08-25): BENSON says its own generic fallback reply, its own mic hears
  // that TTS played back through the speaker, Whisper mangles the echo differently each time
  // (real captures: "He died not Kitekehat, Master...", "nu-ti chintecati dat, master.") so
  // looksLikeSelfEcho's word-overlap/fingerprint check doesn't always catch it, the mangled text
  // still doesn't match any real command, Claude/OpenAI's OWN generic fallback ("I did not quite
  // catch that") gets spoken again, and the cycle repeats — confirmed live running 3+ times in a
  // row with the user unable to get a word in. Content-based detection (looksLikeSelfEcho) is
  // inherently unreliable here since the mangling varies every cycle; this instead watches the
  // MECHANISM directly — BENSON about to speak the exact same reply it just spoke — which is true
  // regardless of what garbled text triggered it. Independent of and complementary to
  // looksLikeSelfEcho.
  const repeatedReplyRef = useRef<{ text: string; count: number }>({ text: '', count: 0 });
  const REPEAT_LOOP_BREAK_THRESHOLD = 2;
  // Returns true if `text` is about to be spoken for at least the Nth time in a row and the
  // conversation loop should stop re-arming listening afterward instead of continuing the cycle.
  function noteSpokenAndCheckRepeatLoop(text: string): boolean {
    const norm = normalizeForEcho(text);
    if (repeatedReplyRef.current.text === norm) {
      repeatedReplyRef.current.count += 1;
    } else {
      repeatedReplyRef.current = { text: norm, count: 1 };
    }
    return repeatedReplyRef.current.count >= REPEAT_LOOP_BREAK_THRESHOLD;
  }
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
  // ROUND_CONTACT_IDENTITY_CONTINUITY_1 — a brain-classified action named/implied a person the
  // local ContextResolver could not resolve to exactly one identity ("Te referi la Hannah?" or a
  // short list). Holds enough to replay the SAME action once the user picks — never re-asks the
  // LLM, never re-executes anything until a candidate is actually chosen.
  const pendingPersonChoiceRef = useRef<{
    action: KnownAction; params: Record<string, string>; candidates: ResolutionCandidate[];
  } | null>(null);
  // Set immediately after a successful RESOLVED/NEEDS_CONFIRMATION-then-picked person resolution,
  // for logging/diagnostics only — the actual value used by execution is params.contact itself.
  const lastResolvedPersonRef = useRef<ResolvedPerson | null>(null);
  // ROUND_EMERGENCY_CORE_1 — a generic "ajutor" / "urgență" awaiting a spoken "Sun la 112?" reply.
  // Checked before every other gate so a stale mission/confirmation can never intercept it, and
  // one reprompt on UNKNOWN then a clean cancel.
  const pendingEmergencyConfirmRef = useRef(false);
  const emergencyRepromptRef = useRef(0);
  // ORCH-FIX-1 — consecutive UNKNOWN replies to an open confirmation gate. Reset to 0 when a gate
  // is resolved (YES/NO); at MAX_CONFIRM_REPROMPTS the gate is cancelled cleanly (no infinite loop).
  const confirmRepromptCountRef = useRef(0);
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
  setOpenAIKeyForStt(openaiKey || null);
  geminiKeyRef.current   = geminiKey;
  setGeminiKeyForStt(geminiKey || null);
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
    const volumeSub = addVolumeListener((level) => {
      lastAudioAtRef.current = Date.now();
      setMicVolume(level);
      if (listeningRef.current) { try { setMicLevel(level, true); } catch {} }
      const d = micCaptureDiagRef.current;
      if (d.active) {
        d.rmsSum += level; d.rmsCount += 1;
        if (level > d.peakRms) d.peakRms = level;
        if (d.firstRmsAboveThresholdAt < 0 && level >= MIC_RMS_THRESHOLD) d.firstRmsAboveThresholdAt = Date.now();
      }
    });
    const speechStartSub = addSpeechStartListener(() => {
      const d = micCaptureDiagRef.current;
      if (d.active && d.speechStartAt < 0) d.speechStartAt = Date.now();
    });
    const speechEndSub = addSpeechEndListener(() => {
      const d = micCaptureDiagRef.current;
      if (d.active && d.speechEndAt < 0) d.speechEndAt = Date.now();
    });
    // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — fires from the native foreground service's own Handler
    // timer, delivered as an event (works while backgrounded, unlike a JS setTimeout). The native
    // side already ignores a stale/superseded session id (STT_WATCHDOG_STALE_IGNORED); this check
    // is a second, cheap correctness guard, not the timing authority.
    const sttWatchdogSub = addSttWatchdogTimeoutListener((sessionId) => {
      if (sttSessionActiveRef.current !== sessionId) return;
      logAudioDiag('STT_SESSION_RECOVERED', `session=${sessionId}`);
      closeSttSession('timeout');
      try { stopRecognition(); } catch {}
      setListening(false); listeningRef.current = false;
      setTimeout(() => { try { resumeListeningAfterUnblock(); } catch {} }, 600);
    });
    // ROUND_TTS_WATCHDOG_NATIVE_1 — same idea as sttWatchdogSub above, for the TTS mic-ownership
    // hard timer. Reuses the exact same recovery handleTtsHardTimeout() already runs for the JS
    // timer — this is just a second, background-safe way to reach it.
    const ttsWatchdogSub = addTtsWatchdogTimeoutListener(() => {
      handleTtsHardTimeout('native_watchdog');
    });
    // URGENT_CONFIRMATION_NATIVE_1 — native one-shot YES/NO reply capture result. Entirely
    // survives BENSON being backgrounded (WhatsApp/etc. foreground) since the listening WINDOW
    // itself is timed natively, not by a JS timer. A stale result (confirmationId no longer the
    // pending one — e.g. the mission gate was already superseded/cancelled) is ignored, never
    // resurrecting an old mission.
    const confirmResultSub = addConfirmationResultListener((confirmationId, verdict, transcript) => {
      if (pendingConfirmationIdRef.current !== confirmationId) {
        logAudioDiag('CONFIRM_LISTEN_STALE_IGNORED', `confirmationId=${confirmationId} current=${pendingConfirmationIdRef.current ?? 'none'}`);
        return;
      }
      pendingConfirmationIdRef.current = null;
      try { setMicLevel(0, false); } catch {}
      logAudioDiag('CONFIRM_LISTEN_RESULT', `confirmationId=${confirmationId} verdict=${verdict} text="${transcript.slice(0, 60)}"`);
      if (!transcript.trim()) {
        // No reply heard (TIMEOUT/UNKNOWN with empty audio) — fall back to the existing generic
        // reprompt policy by resuming the ordinary hands-free loop, same as before this round.
        try { resumeListeningAfterUnblock(); } catch {}
        return;
      }
      // ROUND_CONFIRM_SELF_ECHO_1 — last-resort content-based guard (AEC + the native settle
      // window are the primary defense, see NativeConfirmationListener.kt): reuses the EXACT SAME,
      // already-tuned looksLikeSelfEcho() the JS recognizer path already relies on, instead of a
      // new weak overlap check. A rejected result must not reach handleIncomingText at all — that
      // naturally preserves whatever is pending (pendingMediaSelection/pendingDisambiguation/
      // pendingMissionTaskRef, none of which this file reaches into directly) since none of them
      // get consumed unless handleIncomingText runs. Re-arms the same confirmation listener once,
      // same shape as the original arm in endTtsBlock().
      if (looksLikeSelfEcho(transcript)) {
        logAudioDiag('CONFIRM_SELF_ECHO_REJECTED', `confirmationId=${confirmationId} text="${transcript.slice(0, 60)}"`);
        const nextId = `confirm-${Date.now()}`;
        pendingConfirmationIdRef.current = nextId;
        try { setMicLevel(0.3, true); } catch {}
        logAudioDiag('CONFIRM_LISTEN_ARM', `confirmationId=${nextId} timeoutMs=${CONFIRMATION_LISTEN_TIMEOUT_MS} source=self_echo_retry`);
        try { startConfirmationListening(nextId, CONFIRMATION_LISTEN_TIMEOUT_MS); } catch {}
        return;
      }
      // Reuses the EXACT SAME path a normal STT result takes (classifyConfirmation, pendingMissionTaskRef
      // consumption, resumePendingTask) — only the capture mechanism differs here.
      lastUserTranscriptRef.current = transcript;
      handleIncomingText(transcript, { viaVoice: true, utteranceBytes: -1 });
    });

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
      logMicCaptureDiag('final', transcript);
      // A real final arrived — cancel any pending partial-fallback grace timer from a prior 'end'.
      if (partialFallbackTimerRef.current) {
        clearTimeout(partialFallbackTimerRef.current);
        partialFallbackTimerRef.current = null;
        logAudioDiag('STT_PARTIAL_FALLBACK', `session=${sid} outcome=superseded_by_final`);
      }
      try { stopRecognition(); } catch {}
      setListening(false);
      listeningRef.current = false;
      closeSttSession('result'); // C3-fix — close directly, do not wait for endSub (may not fire)
      if (transcript) {
        // BENSON_STABILIZATION_1 — the authoritative gate: BENSON_OUTPUT can never become USER_INPUT.
        const gate = userMicGate(sid);
        if (!gate.ok) {
          logAudioDiag('STT_DROP', `session=${sid} reason=${gate.reason} text="${transcript.slice(0, 40)}"`);
        } else if (looksLikeSelfEcho(transcript)) {
          logAudioDiag('STT_DROP', `session=${sid} reason=SELF_ECHO_CONTENT text="${transcript.slice(0, 40)}"`);
        } else {
          logAudioDiag('USER_INPUT_ACCEPTED', `turnId=${sid} source=USER_MIC text="${transcript.slice(0, 60)}"`);
          // ROUND_WAKE_COMMAND_HANDOFF_FIX_1 — the follow-up utterance captured after
          // handleWakeDetected() armed listening (WAKE_COMMAND_ARMED) for a bare/control-only wake.
          if (wakeTriggeredRef.current) logAudioDiag('WAKE_COMMAND_CAPTURED', `text="${transcript.slice(0, 60)}"`);
          sessionGotResultRef.current = true;
          // E2-2 — show what was heard next to the bubble as soon as it lands (before dispatch/
          // assembly). handleIncomingText overwrites it with the final assembled text.
          lastUserTranscriptRef.current = transcript;
          pushBubbleBand(bensonStateRef.current);
          scheduleAssembledDispatch(transcript);
        }
      }
      // If empty transcript, 'end' will restart the loop
    });

    // error: don't restart here — 'end' always fires after and handles it
    const errorSub = addErrorListener((error) => {
      logAudioDiag('STT_ERROR', `session=${jsSttSessionIdRef.current} component=js_stt code=-1 name=${error}`);
      logMicCaptureDiag(`error_${error}`, '');
      if (error === 'aborted') { closeSttSession('stopped'); return; } // deliberate stop; endSub follows
      setListening(false);
      listeningRef.current = false;
      // C3-fix — close the session directly on EVERY error kind (endSub may never fire — the bug).
      closeSttSession(
        error === 'no-speech' || error === 'no_speech' ? 'no_speech'
        : error === 'stopped' ? 'stopped'
        : 'error',
      );
    });

    // ── KEY FIX: 'end' drives the hands-free loop ──────────────────────────
    // 'end' fires after every recognition session (result, error, or stop).
    // If convMode is on and nothing is processing/speaking, restart.
    const endSub = addEndListener(() => {
      const sid = jsSttSessionIdRef.current;
      if (listenWatchdogRef.current) { clearTimeout(listenWatchdogRef.current); listenWatchdogRef.current = null; }
      logAudioDiag('STT_STOPPED', `session=${sid} component=js_stt convMode=${convModeRef.current} wakeTriggered=${wakeTriggeredRef.current}`);
      logMicCaptureDiag('end_no_terminal', '');
      try { setMicLevel(0, false); } catch {}
      try { setSystemSoundsMuted(false); } catch {}
      setListening(false);
      listeningRef.current = false;
      // C3-fix — mark closed here too (idempotent: resultSub/errorSub usually got here first).
      // reason=result if a transcript landed, else stopped (endpointer / deliberate stop).
      closeSttSession(sessionGotResultRef.current ? 'result' : 'stopped');

      // See resultSub's partial-fallback comment above — this session ended with a captured
      // partial transcript that never got a true final callback. Do NOT treat it as final
      // immediately: wait PARTIAL_FALLBACK_GRACE_MS for a real final to still land (resultSub
      // clears this timer if one does), and only then dispatch the partial, logged distinctly as
      // STT_PARTIAL_FALLBACK. A self-echo partial is rejected right away (no grace, falls through).
      const fallback = lastPartialTranscriptRef.current;
      if (fallback && fallback.sessionId === sid && fallback.text.trim()) {
        lastPartialTranscriptRef.current = null;
        const fallbackText = fallback.text.trim();
        if (looksLikeSelfEcho(fallbackText)) {
          logAudioDiag('STT_PARTIAL_FALLBACK', `session=${sid} outcome=rejected_self_echo text="${fallbackText}"`);
        } else {
          const PARTIAL_FALLBACK_GRACE_MS = 900;
          if (partialFallbackTimerRef.current) clearTimeout(partialFallbackTimerRef.current);
          logAudioDiag('STT_PARTIAL_FALLBACK', `session=${sid} outcome=grace_started ms=${PARTIAL_FALLBACK_GRACE_MS} text="${fallbackText}"`);
          partialFallbackTimerRef.current = setTimeout(() => {
            partialFallbackTimerRef.current = null;
            // Stale if a new STT session has since started, or something is already processing.
            if (jsSttSessionIdRef.current !== sid || loadingRef.current) {
              logAudioDiag('STT_PARTIAL_FALLBACK', `session=${sid} outcome=dropped_stale`);
              return;
            }
            // BENSON_STABILIZATION_1 — same USER_MIC gate as the final path.
            const g = userMicGate(sid);
            if (!g.ok) {
              logAudioDiag('STT_DROP', `session=${sid} reason=${g.reason} source=partial_fallback text="${fallbackText.slice(0, 40)}"`);
              return;
            }
            logAudioDiag('STT_PARTIAL_FALLBACK', `session=${sid} outcome=accepted_no_final`);
            logAudioDiag('USER_INPUT_ACCEPTED', `turnId=${sid} source=USER_MIC text="${fallbackText.slice(0, 60)}" via=partial_fallback`);
            sessionGotResultRef.current = true;
            scheduleAssembledDispatch(fallbackText);
          }, PARTIAL_FALLBACK_GRACE_MS);
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
      // Nothing was captured this cycle and nothing is pending a reply.
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
          // E1-0: "Recunoaștere eșuată → zero reacție. Tăcere, nu mesaj de rezervă."
          if (E1_USER_ONLY) logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=stt_miss');
          else speak(`Nu am auzit nimic, ${getAddress()}. Mai încearcă o dată.`);
        }
        // Fixed 2026-08-23 (real bug, confirmed live): this used to ALWAYS call resumePassiveWake()
        // here, unconditionally — including while conversation mode was ON. That handed the mic to
        // the SEPARATE wake-scan loop (startLocalWakeLoop) instead of just continuing to listen,
        // which meant (a) "conversation mode" silently stopped being continuous — the user had to
        // say "Benson" again for every follow-up turn, defeating its entire purpose, and (b) the
        // wake-scan loop's own self-restart (setTimeout 150ms) then raced directly against conv
        // mode's reactive restarts for the same mic/whisper pipeline — confirmed in logcat as
        // interleaved WAKE_SCAN_START / STT_REQUESTED trigger=conversation_mode within the same
        // ~300ms window, on every single cycle. (The historical 2026-07-18 comment this replaces
        // predates startLocalWakeLoop entirely — it was written when "the passive hotword loop" and
        // "conversation mode's idle state" were the same native SpeechRecognizer loop; they are two
        // separate, independently-restarting subsystems now, and only one should ever own the mic
        // for a given mode.) While conv mode is on, keep listening directly — no wake word needed
        // for follow-up turns. Only hand off to wake-scan when conv mode is actually off.
        if (convModeRef.current && isForegroundRef.current) {
          setTimeout(() => {
            if (convModeRef.current && isForegroundRef.current && !loadingRef.current && !speakingRef.current && !listeningRef.current) {
              doStartListening();
            }
          }, 400);
        } else {
          try { resumePassiveWake(); } catch {}
        }
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
      // E2-1 — a bubble tap is a direct user touch: bring the app up AND start listening
      // immediately, no chime, no speech. Not blocked by E1-0 — the source is the tap itself.
      noteUserAction();
      bringToForeground();
      if (E2_LISTEN_ON_OPEN && !silencedRef.current) {
        logAudioDiag('LISTEN_STARTED', 'source=user_open');
        playListenStartSound(); // E3-3
        doStartListening();
      } else if (convModeRef.current) {
        doStartListening();
      }
    });

    // The native "Benson" hotword loop (inside BensonForegroundService) heard the word — it
    // already paused itself, woke the screen, brought the app to front, and shown the wake-ring
    // overlay. Pause is a no-op safety net (already paused). If the user said the command in the
    // same breath ("Benson, deschide Waze"), native hands back the tail — process it immediately
    // instead of starting a second, empty listening session; otherwise capture the command now.
    const wakeWordSub = addWakeWordDetectedListener((commandTail) => {
      nativeWakeEventAtRef.current = Date.now(); // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — for WAKE_TO_COMMAND_LATENCY
      logAudioDiag('WAKE_EVENT_RECEIVED_IN_JS', `commandTail="${commandTail}" source=native`);
      // ROUND_WAKE_NATIVE_TO_JS_ACK_1 — clears the native durable pending-wake flag (this IS the
      // ack) so the heartbeat-poll fallback below never re-delivers the same wake a second time.
      try { takePendingWakeCommand(); } catch {}
      handleWakeDetected(commandTail || '');
    });

    // ROUND_WAKE_STATE_BUG_1 — native heartbeat (~3 s). Executes even while BENSON is backgrounded
    // (it's an EVENT, not a JS timer). Re-arms the local wake loop when it should be scanning but
    // the frozen setTimeout(startLocalWakeLoop) chain left it stopped — the proven root cause of
    // "wake works sometimes / dead sometimes" after a background / app-launch transition.
    const wakePokeSub = addWakePokeListener(() => {
      // ROUND_WAKE_NATIVE_TO_JS_ACK_1 — diagnostic-only, unconditional: proves whether the JS side
      // of this event is ever actually invoked while backgrounded/screen-off. Every prior claim of
      // "the heartbeat channel is proven reliable" only ever checked NWW_HEALTH/WAKE_POKE, which
      // are NATIVE-side logs that fire regardless of whether sendEvent() ever reaches this
      // callback — never independently verified from the JS side until now.
      logAudioDiag('WAKE_POKE_JS_RECEIVED', `ts=${Date.now()}`);
      try {
        // Fallback consumption of a durable pending wake command that the live onWakeWordDetected
        // event failed to deliver. takePendingWakeCommand() is an atomic read+clear, so if the
        // live path already consumed it this is always a safe no-op (returns null).
        const pendingTail = takePendingWakeCommand();
        if (pendingTail !== null) {
          logAudioDiag('WAKE_PENDING_CONSUMED', `commandTail="${pendingTail}" source=heartbeat_fallback`);
          nativeWakeEventAtRef.current = Date.now();
          handleWakeDetected(pendingTail);
        }
        // URGENT_WAKE_FRESH_SESSION_1 — REVERTED (was URGENT_REPAIR_AND_ADVANCE_1's conv-mode
        // self-heal, added to recover a JS setTimeout retry that dies while backgrounded during a
        // WhatsApp confirmation wait). Proven live, with a smoking-gun log trace, that this was
        // actively harmful: convMode is ALWAYS on by design (see enterChatMode()), so this fired
        // on every single ~3s poke — including one instance that forcibly stopped NativeCloudWake
        // (WAKE_NATIVE_STOP) mid-VAD-capture (WAKE_VAD_BEGIN rms=2935 with no matching VAD_END —
        // the user's own "Benson" utterance, killed before cloud STT could even run) to force a
        // COMMAND_STT session instead. That confirmation-recovery job is now handled correctly and
        // more robustly by the native one-shot confirmation listener (URGENT_CONFIRMATION_NATIVE_1,
        // armed directly in endTtsBlock() — see startConfirmationListening below), which needs no
        // JS heartbeat backstop at all. Revert: re-add the staleness-gated block removed here.
        if (silencedRef.current || convModeRef.current || loadingRef.current || speakingRef.current) return;
        if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) return;
        if (Date.now() < micResumeAtRef.current) return;
        if (wakeEngineRef.current !== 'local') return;
        if (wakeScanningRef.current || listeningRef.current || wakeTriggeredRef.current) return;
        logAudioDiag('WAKE_SELF_HEAL_START', `reason=native_poke foreground=${isForegroundRef.current}`);
        try { startLocalWakeLoop(); } catch {}
        logAudioDiag(wakeScanningRef.current ? 'WAKE_SELF_HEAL_OK' : 'WAKE_SELF_HEAL_FAIL',
          `reason=native_poke scanning=${wakeScanningRef.current}`);
      } catch {}
    });

    // Resume hands-free listening when the user returns to BENSON after the app was
    // backgrounded — e.g. openApp/callContact/sendWhatsApp switched to another app and the
    // user came back. Without background mode's foreground service, Android suspends the
    // mic/JS timers while backgrounded, and nothing else restarts the loop on return —
    // conversation mode would otherwise sit "on" but dead until the user manually toggles it.
    const appStateSub = AppState.addEventListener('change', (next) => {
      isForegroundRef.current = next === 'active';

      if (next !== 'active') {
        // E2-2 — BENSON just went behind another app: surface the written band with whatever
        // state/transcript is current (it was hidden while BENSON's own screen was up).
        pushBubbleBand(bensonStateRef.current);
        // Round D (2026-08-31) — kill any TTS and its queue the instant we leave the foreground,
        // UNCONDITIONALLY. A network-TTS utterance (OpenAI/Gemini) can't play while backgrounded;
        // it was surfacing LATE on the next foreground return, so BENSON spoke a stale reply
        // ("Am deschis Waze" right after opening YouTube). Nothing spoken now survives a
        // background transition.
        try { stopSpeaking(); } catch {}
        try { stopOpenAITTS().catch(() => {}); } catch {}
        try { stopGeminiTTS().catch(() => {}); } catch {}
        endTtsBlock('background'); // C1 TASK 1 — never leave speakingRef raised on a bg transition
        // ROUND_WAKE_NATIVE_GENERIC_1 — release native wake ownership independent of conv-mode/
        // sttEngine. beginTtsBlock() calls nwOwner('TTS') UNCONDITIONALLY (any STT engine); the
        // conv-mode/local-engine branch just below only ever existed to hand the mic to the OLD
        // MicroWakeWord/legacy hotword loop and never ran while nativeWakeRef.current was false
        // (no native engine was ever actually armable before this round). Confirmed live on
        // 9c1464eb this round: without this, micOwner stayed stuck at "TTS" forever after any
        // backgrounding that interrupted a TTS utterance (NWW_HEALTH micOwner=TTS running=false
        // for 20+ seconds, native wake never re-arming) whenever the default 'local' STT engine
        // was selected, since sttEngineRef.current !== 'local' below is then always false.
        // Gated on nativeWakeRef.current so the untouched 'local'-engine-only path (leave the
        // current JS capture loop running, per the comment below) is completely unaffected when
        // no native engine is configured. Revert: delete this block.
        if (nativeWakeRef.current) { try { nwOwner('WAKE'); } catch {} }

        // Leaving the foreground (screen off, Home pressed, another app opened manually) — JS's
        // own conv-mode SpeechRecognizer session ('cloud'/'ondevice' engines) is not a safe mic
        // owner here (see isForegroundRef doc above: confirmed live 2026-07-18 it can silently die
        // with no recovery), so hand the mic back to the native, foreground-service-backed hotword
        // loop instead. Without this, BENSON went completely deaf until the user manually reopened
        // the app — the exact "always have to search for and open the app" complaint, since conv
        // mode being on by default meant JS grabbed the mic away from the native loop on every
        // launch and never gave it back on its own.
        //
        // The 'local' engine (the default) is DIFFERENT and exempt from this (product-owner-
        // directed 2026-08-24): it's the same AudioRecord+VAD capture, backed by the same
        // foreground service, either way — not the fragile SpeechRecognizer this caution was
        // written for. Stopping and hopping to the native hotword fallback here was leaving
        // BENSON silently unable to hear anything at all while backgrounded with conv mode on
        // (startLocalWakeLoop() itself no-ops whenever convModeRef is true, by design — see its
        // own comment — so this handoff produced neither engine actually listening). The floating
        // bubble is the visible cue this is happening: as long as it's up, BENSON should stay
        // awake and reachable without repeating the wake word, exactly like still being in
        // foreground — so for 'local', just leave the current capture loop running untouched.
        if (convModeRef.current && sttEngineRef.current !== 'local') {
          try { stopRecognition(); } catch {}
          setListening(false); listeningRef.current = false;
          closeSttSession('background'); // C3 — recognizer torn down here; free the gate
          try { resumePassiveWake(); } catch {}
        }
        return;
      }

      // E2-2 — BENSON's own screen is up again; the written band (only for the over-another-app
      // case) comes down. The transcript stays in the chat log instead. isForegroundRef is already
      // true here, so pushBubbleBand computes visible=false and hides it.
      pushBubbleBand('IDLE');

      // AUTO-RETURN-AFTER-CALL — this foreground transition was triggered by the native call-
      // lifecycle watcher (it verified a WhatsApp call ended and fired the explicit MainActivity
      // Intent). Re-arm WAKE mode ONLY: never conversation mode, never STT_REQUESTED
      // trigger=conversation_mode. Then return — skip every other 'active'-restart branch below.
      let callEndedReturn = false;
      try { callEndedReturn = consumeCallEndedReturnPending(); } catch {}
      if (callEndedReturn) {
        whatsappCallMicHoldUntilRef.current = 0;
        try { clearWhatsAppCallMicHold(); } catch {}
        if (convModeRef.current) { convModeRef.current = false; setConvMode(false); }
        wakeTriggeredRef.current = false;
        try { hideWakeRing(); } catch {}
        if (speakingRef.current || ttsHardTimerRef.current) { try { stopSpeaking(); } catch {} try { stopOpenAITTS().catch(() => {}); } catch {} endTtsBlock('interrupt'); }
        if (sttSessionActiveRef.current) { try { stopRecognition(); } catch {} setListening(false); listeningRef.current = false; closeSttSession('background'); }
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'call_ended_return');
        setTimeout(() => { if (!silencedRef.current) { try { resumePassiveWake(); } catch {} } }, 400);
        logAudioDiag('WAKE_MODE_RESTORED', 'reason=call_ended convMode=false');
        return;
      }

      // WA-CALL-STAYS-LIVE — the mic was held closed after placing a WhatsApp call so the call
      // kept the mic. The user being back on BENSON's screen means they are done with (or have
      // put on speaker) that call: lift the hold (JS ref + the native source of truth) so the
      // wake word / listening work again.
      if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) {
        logAudioDiag('MIC_HOLD', 'reason=whatsapp_call_live cleared=foreground_return');
        whatsappCallMicHoldUntilRef.current = 0;
        try { clearWhatsAppCallMicHold(); } catch {}
      }

      // ── C1: unconditional speaking-state reset on foreground return ──────────────────────────
      // A network-TTS onDone (or the device TTS callback) that never fired while backgrounded
      // leaves speakingRef stuck true; every restart below then no-ops and BENSON is deaf. The
      // user coming back to the app is an unambiguous "reset" signal — clear it hard.
      if (speakingRef.current || ttsHardTimerRef.current) {
        logAudioDiag('TTS_FORCE_UNBLOCK', 'reason=foreground_return');
        try { stopSpeaking(); } catch {}
        try { stopOpenAITTS().catch(() => {}); } catch {}
        endTtsBlock('interrupt');
      }

      // ── C3 — a single-session gate left set across a background gap (endSub never fired while JS
      // timers were suspended) would block every restart below (C1's resumeListeningAfterUnblock,
      // the self-heal, the endSub timers) — BENSON would return deaf. Returning to the foreground
      // is an unambiguous "sessions are over" signal; close it here.
      if (sttSessionActiveRef.current) {
        try { stopRecognition(); } catch {}
        setListening(false); listeningRef.current = false;
        closeSttSession('background');
      }

      // ── C1 TASK 3 — resume_task decoupled from the (frozen) activity thread ──────────────────
      // If a resume_task was in flight while backgrounded, the user coming back IS the "whichever
      // comes first" signal: speaking state is already reset above, and listening restarts below.
      // The awaited resumePendingTask() still resolves when the native action finishes (its own
      // handler runs then). If the state got stuck at EXECUTING/resume_task with nothing coming,
      // TASK 4's watchdog recovers it — never "Am rămas blocat".
      if (C1_RESUME_DECOUPLED && (resumeInFlightRef.current || (bensonStateRef.current === 'EXECUTING' && execPhaseRef.current === 'resume_task'))) {
        logAudioDiag('RESUME', `source=foreground_return state=${bensonStateRef.current}`);
        setLoading(false); loadingRef.current = false;
        setTimeout(() => { try { resumeListeningAfterUnblock(); } catch {} }, 250);
      }
      // C1: a confirmation gate still open after a long deaf gap is stale — the user has moved on.
      if (gateArmedAtRef.current && Date.now() - gateArmedAtRef.current > PENDING_STALE_MS) {
        if (pendingMissionTaskRef.current || pendingNoteActionRef.current || pendingVignetteRef.current) {
          logAudioDiag('STATE_CLEARED', `after=foreground_stale ageMs=${Date.now() - gateArmedAtRef.current}`);
          pendingMissionTaskRef.current = null;
          pendingNoteActionRef.current = null;
          pendingVignetteRef.current = null;
        }
        gateArmedAtRef.current = 0;
      }

      // Removed unconditional hideBubble() here (2026-08-25, product-owner-directed): the bubble
      // is now meant to stay visible ALWAYS — a permanent, always-there indicator that BENSON is
      // awake and reachable (tap it or say the wake word), not just a "way back" shown only while
      // another app has focus.
      // C1: restart listening on return even in wake-word (non-conv) mode — previously this handler
      // only ever restarted for conv mode, so after an action in wake mode BENSON stayed idle.
      if (!convModeRef.current) {
        if (!silencedRef.current && !wakeTriggeredRef.current && !loadingRef.current) {
          setTimeout(() => { if (!speakingRef.current) { try { resumePassiveWake(); } catch {} } }, 400);
        }
        return;
      }
      // A wake-word-triggered session (native bringActivityToFront() also fires this same
      // 'active' transition) already owns its own doStartListening() call via wakeWordSub —
      // skip here so the two don't race for the mic (confirmed live 2026-07-14: this was firing
      // its own doStartListening() ~500ms after the wake-word flow's, overlapping/killing both).
      if (loadingRef.current || speakingRef.current || listeningRef.current || wakeTriggeredRef.current) return;
      // E2-1 — opening the app is a direct user action: start listening NOW, not after a 500ms
      // grace timer and not via the 2s self-heal. No chime, no speech. Revert: E2_LISTEN_ON_OPEN=false.
      if (E2_LISTEN_ON_OPEN && convModeRef.current && !silencedRef.current) {
        logAudioDiag('LISTEN_STARTED', 'source=user_open');
        playListenStartSound(); // E3-3
        doStartListening();
        return;
      }
      setTimeout(() => {
        if (convModeRef.current && !loadingRef.current && !speakingRef.current && !listeningRef.current && !wakeTriggeredRef.current) {
          doStartListening();
        }
      }, 500);
    });

    // ── C2: listen-loop self-heal ────────────────────────────────────────────────────────────
    // Every LISTEN_SELF_HEAL_MS: if nothing is speaking/loading/in the TTS tail and the loop that
    // SHOULD be running isn't, restart it. Both restart paths self-guard against double-starts.
    const listenHealTimer = LISTEN_SELF_HEAL_MS > 0 ? setInterval(() => {
      // WA-LIFECYCLE-FIX-1 — consume a native "verified WhatsApp call ended" signal FIRST, before
      // any of the guards below. This path must work while BENSON is still backgrounded (no
      // AppState 'active' has fired): after a post-call service kill/revive the native
      // recoverWhatsAppCallLifecycle() finalizes CALL_ENDED and publishes the signal here.
      try {
        let sig = 0;
        try { sig = getWhatsAppCallEndedSignalAt() || 0; } catch {}
        let pending = false;
        try { pending = consumeCallEndedReturnPending(); } catch {}
        if (pending || (sig > 0 && sig !== lastCallEndedSignalRef.current)) {
          lastCallEndedSignalRef.current = sig || Date.now();
          const wasHeld = whatsappCallMicHoldUntilRef.current !== 0 || waCallMicHoldActiveSafe();
          whatsappCallMicHoldUntilRef.current = 0;
          try { clearWhatsAppCallMicHold(); } catch {}
          logAudioDiag('WA_CALL_ENDED_NATIVE', `via=self_heal sigAt=${sig} wasHeld=${wasHeld}`);
          logAudioDiag('WAKE_MODE_RESTORED', 'reason=call_ended_native');
          if (!silencedRef.current && !convModeRef.current) {
            try { resumePassiveWake(); } catch {}
          }
          logAudioDiag(
            'WAKE_RUNTIME',
            `serviceAlive=${serviceActiveRef.current} micHold=${waCallMicHoldActiveSafe()} ` +
              `wakeLoop=${wakeScanningRef.current} detectorActive=${!listeningRef.current && !wakeTriggeredRef.current && !speakingRef.current}`,
          );
        }
      } catch {}

      // WAKE HEALTH — refresh the async pieces (cheap, fire-and-forget) then emit the consolidated
      // line + drive the honest notification. Runs every tick, BEFORE the early-returns below so
      // health is reported even while speaking / loading.
      try { getA11yConnectionState().then((s) => { a11yBoundRef.current = s === 'enabled_connected' ? 'BOUND' : 'UNBOUND'; }).catch(() => {}); } catch {}
      try { Promise.resolve(isWakeWordEnabled()).then((e) => { wakeWordEnabledRef.current = e !== false; }).catch(() => {}); } catch {}
      try { emitWakeHealth(); } catch {}

      if (silencedRef.current || loadingRef.current) return;

      // BENSON_STABILIZATION_1 — state RECOVERING: a stuck speakingRef (a TTS onDone that never
      // fired) must never wedge the recognizer. If speaking has been "on" longer than the hard
      // watchdog window, force-clear it and re-arm.
      if (speakingRef.current) {
        const heldMs = ttsBlockStartedAtRef.current ? Date.now() - ttsBlockStartedAtRef.current : 0;
        if (heldMs > TTS_MAX_BLOCK_MS + 2000) {
          logAudioDiag('WAKE_SELF_HEAL_START', `reason=stale_speaking heldMs=${heldMs}`);
          logAudioDiag('AUDIO_STATE', 'from=TTS_SPEAKING to=RECOVERING');
          logAudioDiag('WAKE_SELF_HEAL_ACTION', 'action=clear_stale_speaking');
          endTtsBlock('watchdog');
          afterPromptRearm(convModeRef.current || wakeTriggeredRef.current);
          logAudioDiag('WAKE_SELF_HEAL_OK', 'reason=stale_speaking');
        }
        return;
      }
      if (Date.now() < micResumeAtRef.current) return; // TTS tail

      if (convModeRef.current) {
        if (!listeningRef.current && !wakeTriggeredRef.current) {
          logAudioDiag('LISTEN_HEALED', 'silent=true reason=conv_idle');
          doStartListening();
        }
        return;
      }
      // BENSON_STABILIZATION_1 — dropped the isForegroundRef gate: the local wake loop must
      // self-heal whether the RN Activity is foreground or backgrounded (as long as JS runs).
      if (serviceActiveRef.current && wakeEngineRef.current === 'local'
          && !wakeScanningRef.current && !listeningRef.current && !wakeTriggeredRef.current) {
        logAudioDiag('WAKE_SELF_HEAL_START', 'reason=wake_loop_stopped');
        logAudioDiag('WAKE_SELF_HEAL_ACTION', 'action=restart_local_wake_loop');
        try { resumePassiveWake(); } catch {}
        logAudioDiag(wakeScanningRef.current ? 'WAKE_SELF_HEAL_OK' : 'WAKE_SELF_HEAL_FAIL',
          wakeScanningRef.current ? 'reason=wake_loop_stopped' : 'reason=resume_no_effect');
      }
    }, LISTEN_SELF_HEAL_MS) : null;

    return () => {
      if (partialFallbackTimerRef.current) { clearTimeout(partialFallbackTimerRef.current); partialFallbackTimerRef.current = null; }
      if (listenHealTimer) clearInterval(listenHealTimer);
      endTtsBlock('stop'); // C1 TASK 1 — app teardown must not leave speakingRef raised
      if (ttsHardTimerRef.current) { clearTimeout(ttsHardTimerRef.current); ttsHardTimerRef.current = null; }
      if (stateClearTimerRef.current) { clearTimeout(stateClearTimerRef.current); stateClearTimerRef.current = null; }
      if (execWatchdogRef.current) { clearTimeout(execWatchdogRef.current); execWatchdogRef.current = null; }
      if (bandHideTimerRef.current) { clearTimeout(bandHideTimerRef.current); bandHideTimerRef.current = null; }
      if (sttSessionActiveRef.current) { try { cancelSttSessionWatchdog(sttSessionActiveRef.current); } catch {} }
      try { cancelTtsWatchdog(); } catch {}
      resultSub.remove(); errorSub.remove(); endSub.remove(); volumeSub.remove();
      speechStartSub.remove(); speechEndSub.remove(); sttWatchdogSub.remove(); ttsWatchdogSub.remove(); confirmResultSub.remove();
      if (pendingConfirmationIdRef.current) { try { cancelConfirmationListening(pendingConfirmationIdRef.current); } catch {} }
      stopReqSub.remove(); listenReqSub.remove(); bubbleTapSub.remove();
      wakeWordSub.remove(); wakePokeSub.remove(); appStateSub.remove();
    };
  }, []);

  // ── Stage 6: load device TTS voices once ──────────────────────────────────
  useEffect(() => {
    getAvailableVoices().then((vs) => {
      setVoices(vs);
      // Diagnostic (product-owner-directed 2026-08-23): expo-speech's Voice type carries no
      // gender/timbre metadata (identifier, name, quality, language only) — there is no way to
      // programmatically pick a "baritone"/butler-toned voice from code. Logged once per app start
      // so the real on-device voice list (name/identifier/quality per language) can be read via
      // `adb logcat -s BENSON_AUDIO` and picked by ear (Settings' voice Preview ▶ button), instead
      // of guessing from an identifier string.
      for (const target of ['ro-RO', 'en-GB', 'de-DE', 'en-US', 'ro', 'en', 'de']) {
        const matches = vs.filter((v) => v.language === target);
        if (matches.length) {
          logAudioDiag('VOICE_LIST', `lang=${target} count=${matches.length} voices=${matches.map((v) => `${v.name}(${v.identifier},${v.quality})`).join(' | ')}`);
        }
      }
      logAudioDiag('VOICE_LIST', `TOTAL count=${vs.length} allLanguages=${Array.from(new Set(vs.map((v) => v.language))).join(',')}`);
    }).catch(() => {});
  }, []);

  // Mission Governance (Waze/WhatsApp, Phase 1) — cold-start restore + resume status on return.
  // src/core/mission's store is AsyncStorage-backed, so a mission left WaitingConfirmation or
  // WaitingUser survives an app kill; this surfaces it instead of silently forgetting it.
  useEffect(() => {
    hydrateActiveMission().then((mission) => {
      if (mission && (mission.state === 'WaitingConfirmation' || mission.state === 'WaitingUser')) {
        addMessage('benson', mission.userMessage);
        // E1-0: cold-start restore — not a user command. Show it; do not speak it. The mission
        // stays persisted and resolves on the user's next utterance. Revert: E1_USER_ONLY = false.
        if (E1_USER_ONLY) logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=mission_hydrate');
        else speak(mission.userMessage);
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
        // E1-0: re-announce on foreground return is a system-callback trigger, not a user command.
        if (E1_USER_ONLY) { logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=waiting_user_return'); return; }
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
        // E1-0: poll-triggered (60s), not a user command → no unsolicited speech. The persistent
        // on-screen banner (accessibilityDown) + the tappable notification still alert the user.
        if (E1_USER_ONLY) logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=a11y_dropped');
        else speak('Serviciul de accesibilitate tocmai s-a dezactivat. Nu mai pot citi ecranul sau apăsa butoane până nu-l reactivezi din Setări.');
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
          // E1-0: periodic timer reminder — the banner stays up regardless; no spoken nag.
          if (E1_USER_ONLY) logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=a11y_reminder');
          else speak(`Reamintire blândă, ${getAddress()}: serviciul de accesibilitate e încă oprit. Când ai un moment, reactivează-l din Setări ca să pot ajuta din nou complet.`);
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

  // Wake word — the foreground service auto-starts ITS OWN native SpeechRecognizer-based hotword
  // loop the instant it starts (BensonForegroundService's own onCreate/onStartCommand), regardless
  // of which engine this app actually wants to use. wakeEngineRef defaults to 'local' (the working
  // Whisper-based path — see RMS_THRESHOLD fix), but nothing handed off to it at boot before this
  // fix: resumePassiveWake() was only ever called reactively, after a JS STT cycle's 'end' event —
  // which never happens if the native loop is the only thing running and stuck. Confirmed live
  // 2026-08-23: on a fresh install/launch, saying "Benson" did nothing because the native loop was
  // cycling BURST_WATCHDOG_TIMEOUT with no handoff ever triggered. Explicit resumePassiveWake()
  // right after the service starts makes the handoff happen at boot, not just reactively.
  useEffect(() => {
    if (phase !== 'chat') return;
    (async () => {
      if (silencedRef.current) return; // fully off — don't start the listening service
      const granted = await checkMicPermission();
      if (!granted) return;
      if (!serviceActiveRef.current) await startBackgroundService();
      // E2-1 — reaching the chat screen IS the user opening the app: in conv mode (the default),
      // start listening immediately, not via the 2s self-heal. resumePassiveWake() below still
      // handles wake-word-only mode. No chime, no speech. Revert: E2_LISTEN_ON_OPEN = false.
      if (E2_LISTEN_ON_OPEN && convModeRef.current && !silencedRef.current) {
        logAudioDiag('LISTEN_STARTED', 'source=user_open');
        playListenStartSound(); // E3-3
        doStartListening();
      }
      try { resumePassiveWake(); } catch {}
      // Confirmed live 2026-08-23: the FIRST resumePassiveWake() call above still loses the race —
      // startListeningService()/startBackgroundService() only fire-and-forget the native service
      // start; pauseHotword() then runs before the native service has actually reached its own
      // onCreate/onStartCommand (which unconditionally starts the native hotword loop), so there is
      // nothing to pause yet — it resolves as a no-op, and the native loop then starts moments
      // later, uncontrolled, regardless of wakeEngineRef being 'local'. A second, delayed call
      // catches it: by 900ms the native service has always finished starting on this device.
      setTimeout(() => { try { resumePassiveWake(); } catch {} }, 900);
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

      // E1-0: this runs after a native OxygenOS-kill resurrection — "după o auto-reparare", which
      // E1-0 forbids as a speech trigger, explicitly. No "BENSON a revenit online", no spoken
      // accessibility warning here. The visual accessibility banner (watchdog onStatus) still
      // covers the disabled-service case. Revert: E1_USER_ONLY = false.
      if (E1_USER_ONLY) {
        logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=guardian_recovery');
        return;
      }
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

    const [name, key, sl, sr, sp, ve, sc, sa, hist, fcts, bg, tk, vid, ok, gk, tp, cm, acm, cda, cdn, vig, rl, mp, se, ww, wn, hib] = await Promise.all([
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
      AsyncStorage.getItem('geminiKey'),
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
      AsyncStorage.getItem('bensonWakeName'),
      AsyncStorage.getItem('bensonHibernationEnabled'),
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
    if (gk) { setGeminiKey(gk); geminiKeyRef.current = gk; setGeminiKeyForStt(gk); }
    if (tp === 'openai' || tp === 'device' || tp === 'gemini') { setTtsProvider(tp); ttsProviderRef.current = tp; }
    if (mp === 'openai' || mp === 'claude' || mp === 'gemini') { setModelProvider(mp); modelProviderRef.current = mp; }
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
    // ROUND_WAKE_NATIVE_GENERIC_1 — restore the wake-name config and push it to native explicitly
    // (even when it's the default) so JS and native never disagree, same discipline as the
    // wake-word kill switch just above.
    const resolvedWakeName = (wn && wn.trim()) || 'Benson';
    setWakeNameState(resolvedWakeName);
    wakeNameRef.current = resolvedWakeName;
    try { setWakeName(resolvedWakeName); } catch {}
    // Hibernation kill switch — restore the visual toggle state and push it to native explicitly,
    // same discipline as the wake-word kill switch above. Default OFF (hib === 'true' required).
    const hibernationOn = hib === 'true';
    setHibernationEnabledState(hibernationOn);
    try { setHibernationEnabled(hibernationOn); } catch {}
    // Push the already-saved Groq STT credentials down to the native cloud wake loop so it can
    // transcribe an utterance without JS being alive — JS/expo-secure-store remains the sole
    // place the real secret is authored (settingsStore.ts); this is a runtime push only, same
    // class of mechanism as setPorcupineAccessKey. No-op (silently) when no Groq key is saved yet.
    getEngineConfig('stt', 'groq').then((cfg) => {
      if (cfg?.apiKey) {
        try {
          setNativeWakeCredentials(cfg.apiKey, cfg.baseUrl || GROQ_STT_DEFAULT_BASE_URL, cfg.model || GROQ_STT_DEFAULT_MODEL);
        } catch {}
      }
    }).catch(() => {});
    // DEV_STT_DEEPGRAM_1 — same runtime-push idiom, own SharedPreferences key (see saveApiKeys),
    // so the native confirmation listener has its Deepgram key on app launch too, not only after
    // a Settings save.
    getEngineConfig('stt', 'deepgram').then((cfg) => {
      if (cfg?.apiKey) { try { setConfirmationSttCredentials(cfg.apiKey); } catch {} }
    }).catch(() => {});
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

  // Round 2 / Task 5 — the single memory-write sink. Every path that persists a "fact" goes
  // through here, so the discipline is enforced in one place:
  //   1. The brain never writes on its own. A model-initiated write (the `remember` tool, threaded
  //      as onRememberFact) is refused outright — see the routeCommand call site below.
  //   2. A user-initiated write still passes checkMemoryWrite(): a line that would change BENSON's
  //      own behaviour ("de acum înainte nu mai cere confirmare", "ignore your rules", the German
  //      and English equivalents) is rejected, never stored. Memory holds facts about the user,
  //      never rules about BENSON.
  async function appendFact(fact: string, source: 'user' | 'model' = 'user') {
    if (source === 'model') {
      logAudioDiag('MEMORY_REJECTED', 'reason=model_initiated');
      return;
    }
    const verdict = checkMemoryWrite(fact); // logs MEMORY_REJECTED itself on a behaviour-change hit
    if (!verdict.allowed) return;
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
    if (role === 'user') {
      // ORCH-FIX-1: while a confirmation gate is open, handleIncomingText's gate logic owns the
      // next transition (EXECUTING on YES / CONFIRMING re-prompt on UNKNOWN / cancel on NO).
      // Pre-empting it with THINKING stranded the state on THINKING for an unrecognized reply.
      const confirmGateOpen =
        !!pendingVignetteRef.current || !!pendingNoteActionRef.current ||
        !!pendingMissionTaskRef.current || getActiveMission()?.state === 'WaitingConfirmation';
      if (!confirmGateOpen) setBensonState('THINKING');
      return;
    }
    if (role === 'benson') {
      setLastReply(text);
      // Only auto-classify the terminal state from a plain conversational reply (state still
      // THINKING). Mission/confirm paths set CONFIRMING / EXECUTING / DONE / ERROR explicitly and
      // must not be overridden here.
      if (bensonStateRef.current === 'THINKING') {
        setBensonState(isFailureReply(text) ? 'ERROR' : 'DONE', 'reply');
      }
    }
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

  const MAX_REMEMBERED_SPOKEN = 5;
  function rememberSpoken(text: string) {
    const now = Date.now();
    const pruned = lastSpokenRef.current.filter((e) => now - e.at <= ECHO_WINDOW_MS);
    pruned.push({ normalized: normalizeForEcho(text), at: now });
    lastSpokenRef.current = pruned.slice(-MAX_REMEMBERED_SPOKEN);
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
  // Widened to 120s (2026-08-23, same day as the ggml-small model upgrade): confirmed live that a
  // boot-greeting echo wasn't caught even by the rolling-list fix above, because the delay between
  // BENSON speaking and local Whisper finishing transcription was ~70s that same session (model
  // load + a 15s clip on the now-larger model) — past the old 60s window before the check ever ran.
  // 120s keeps healthy margin above that observed worst case.
  // E2-fix-1 (2026-09-07, product-owner-directed): cut to 3s. 120s + the substring check below
  // ("deschide youtube".includes("deschid")) turned E1-5's short ACK ("Deschid.") into a 2-minute
  // block on any real command that starts with the same words. If BENSON hasn't spoken in the last
  // 3s, the self-echo filter no longer applies at all. Revert: 120000.
  const ECHO_WINDOW_MS = 3000; // was 120000 (E2-fix-1) — earlier 60000 / 12000 / 6000
  // Timing-independent fingerprint of BENSON's OWN stock fallback line ("I did not quite catch
  // that, {address}", claudeAgent.ts/openaiAgent.ts) — product-owner-confirmed live 2026-08-24 as
  // a self-sustaining feedback loop: BENSON says this line when it doesn't understand something,
  // the mic re-hears its own voice, Whisper mangles it differently each time ("He did not catch
  // that master." / "E de notquente que é a chance da Master." / "He did not catch that, mas de.",
  // all real captures from the same live loop), and the word-overlap check above — which depends
  // on comparing against a specific recent spoken entry within a time window — missed roughly half
  // of these because the mangling varied too much turn to turn. Each miss triggered ANOTHER "I did
  // not quite catch that" reply, which the mic then re-heard again — three-plus rounds observed
  // live, each one a real spoken interruption exactly like the "no unsolicited speech" rule
  // forbids. Recognizing the phrase's own distinctive fragments directly — independent of timing,
  // staleness, or which exact entry is compared against — closes that gap structurally instead of
  // trying to tune the fuzzy-overlap threshold further.
  const SELF_ECHO_FINGERPRINTS = ['quite catch', 'did not catch', 'not catch that'];
  function looksLikeSelfEcho(transcript: string): boolean {
    const norm = normalizeForEcho(transcript);
    if (norm.length < 4) return false;
    if (SELF_ECHO_FINGERPRINTS.some((f) => norm.includes(f))) return true;
    const recent = lastSpokenRef.current;
    if (recent.length === 0) return false;
    const now = Date.now();
    return recent.some((last) => {
      if (now - last.at > ECHO_WINDOW_MS) return false;
      if (last.normalized.includes(norm) || norm.includes(last.normalized)) return true;
      const spokenWords = new Set(last.normalized.split(' ').filter((w) => w.length > 1));
      const heardWords = norm.split(' ').filter((w) => w.length > 1);
      if (spokenWords.size === 0 || heardWords.length === 0) return false;
      const overlap = heardWords.filter((w) => spokenWords.has(w)).length;
      return overlap / heardWords.length >= 0.6;
    });
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
      // Voice path — carry the byte size of the utterance that produced this transcript so an
      // empty-audio "da" can't satisfy a Confirmation Gate (see handleIncomingText / Task 2).
      if (assembled) handleIncomingText(assembled, { viaVoice: true, utteranceBytes: getLastUtteranceBytes() });
    }, FRAGMENT_ASSEMBLY_WINDOW_MS);
  }

  // Called at the very start of every TTS utterance: raise the speaking flag and hard-stop any
  // capture that is already running (command STT or the passive wake scan), so nothing records
  // while BENSON talks. Idempotent — safe if TTS chains sentence-by-sentence.
  function beginTtsBlock() {
    if (!speakingRef.current) { logAudioDiag('MIC_BLOCKED', 'reason=tts_speaking'); logAudioDiag('AUDIO_STATE', 'from=LISTENING to=TTS_SPEAKING'); }
    nwOwner('TTS'); // native wake must not hear BENSON's own voice
    speakingRef.current = true; setSpeaking(true);
    if (listeningRef.current || wakeScanningRef.current) {
      try { stopRecognition(); } catch {}
      try { stopWakeScan(); } catch {}
      listeningRef.current = false; setListening(false);
      wakeScanningRef.current = false;
      // URGENT_REPAIR_AND_ADVANCE_1 — proven live: stopRecognition() here does not reliably fire
      // a local-engine 'end' event when interrupted mid-capture, leaving sttSessionActiveRef stuck
      // (C3 gate) until the native watchdog's 30s timeout — long after a spoken "da" was missed.
      // closeSttSession() is idempotent, so this is a no-op if the session already closed normally.
      try { closeSttSession('stopped'); } catch {}
      try { setMicLevel(0, false); } catch {}
    }
    // C1 TASK 2 — hard watchdog on speakingRef: if no TTS callback ever fires (app backgrounded
    // mid-utterance), speakingRef is force-dropped after TTS_MAX_BLOCK_MS no matter what.
    ttsBlockStartedAtRef.current = Date.now();
    if (ttsHardTimerRef.current) clearTimeout(ttsHardTimerRef.current);
    if (C1_TTS_WATCHDOG && Number.isFinite(TTS_MAX_BLOCK_MS)) {
      ttsHardTimerRef.current = setTimeout(() => handleTtsHardTimeout('js_timer'), TTS_MAX_BLOCK_MS);
      // ROUND_TTS_WATCHDOG_NATIVE_1 — confirmed live 2026-09-15: this JS setTimeout (and
      // speakOnDevice()'s own per-call one) goes inert while BENSON is backgrounded — a TTS bind
      // failure left micOwner=TTS stranded for the full 45s generic OwnerWatchdog instead of
      // TTS_MAX_BLOCK_MS. This native, Handler-based watchdog mirrors armSttSessionWatchdog and
      // survives backgrounding; same timeout, not a duplicate/new value. handleTtsHardTimeout()
      // is idempotent, so whichever of the JS timer or this native one fires first wins.
      try { armTtsWatchdog(TTS_MAX_BLOCK_MS); } catch {}
    }
  }

  // ROUND_TTS_WATCHDOG_NATIVE_1 — shared by both the JS hard timer above and the native watchdog
  // event listener (set up alongside sttWatchdogSub): exact same recovery this codebase already
  // trusts for a stuck TTS block, just reachable now even while backgrounded. `source` is log-only.
  function handleTtsHardTimeout(source: 'js_timer' | 'native_watchdog') {
    // Idempotency guard: whichever of the JS timer / native watchdog fires first does the
    // recovery; the other is a no-op (same TTS block can only be legitimately ended once).
    // Prevents a second resumeListeningAfterUnblock() from re-arming generic WAKE and stealing
    // the mic back from a confirmation listener the first call may have just armed.
    if (!speakingRef.current && !ttsHardTimerRef.current) {
      logAudioDiag('TTS_WATCHDOG_FIRED', `forcedRelease=false alreadyHandled=true source=${source}`);
      return;
    }
    if (ttsHardTimerRef.current) { clearTimeout(ttsHardTimerRef.current); ttsHardTimerRef.current = null; }
    const elapsedMs = ttsBlockStartedAtRef.current ? Date.now() - ttsBlockStartedAtRef.current : TTS_MAX_BLOCK_MS;
    logAudioDiag('TTS_WATCHDOG_FIRED', `forcedRelease=true elapsedMs=${elapsedMs} source=${source}`);
    endTtsBlock('watchdog');
    resumeListeningAfterUnblock();
  }

  // C1 TASK 1 — called on EVERY TTS exit path. `reason` ∈ success|error|interrupt|background|stop
  // (plus 'watchdog' for the hard timer). Drops speakingRef, disarms the hard timer, arms the tail.
  // Nothing leaves speakingRef raised.
  type TtsEndReason = 'success' | 'error' | 'interrupt' | 'background' | 'stop' | 'watchdog';
  function endTtsBlock(reason: TtsEndReason = 'success') {
    const wasBlocking = speakingRef.current || !!ttsHardTimerRef.current || !!ttsBlockStartedAtRef.current;
    if (ttsHardTimerRef.current) { clearTimeout(ttsHardTimerRef.current); ttsHardTimerRef.current = null; }
    // ROUND_TTS_WATCHDOG_NATIVE_1 — every exit path from a TTS block cancels the native watchdog
    // too, not just the JS timer, so it never fires after a normal success/error/interrupt end.
    try { cancelTtsWatchdog(); } catch {}
    speakingRef.current = false; setSpeaking(false);
    ttsEndedAtRef.current = Date.now();
    micResumeAtRef.current = ttsEndedAtRef.current + TTS_TAIL_MS;
    ttsBlockStartedAtRef.current = 0;
    if (C1_TTS_END_ALL_PATHS && wasBlocking) { logAudioDiag('TTS_BLOCK_END', `reason=${reason}`); logAudioDiag('AUDIO_STATE', 'from=TTS_SPEAKING to=WAITING_USER_REPLY'); }
    // ROUND_ASSISTANT_SESSION_UX_FIX_1 — TTS for this turn just finished (or was cut short — an
    // interrupt/watchdog/background end still counts as "no longer speaking", so the overlay
    // isn't stuck waiting forever on a TTS that will never cleanly finish). If the state is
    // CURRENTLY a terminal result, this is the moment it becomes eligible for the readable dwell
    // — not whenever the state first flipped to DONE/ERROR, which could be well before a long
    // reply actually finishes being spoken (the HandyParken bug this round fixes).
    if (wasBlocking && (bensonStateRef.current === 'DONE' || bensonStateRef.current === 'ERROR')) {
      logAudioDiag('RESULT_TTS_DONE', `reason=${reason}`);
      scheduleResultDismiss(RESULT_DWELL_AFTER_TTS_MS);
    }
    // URGENT_CONFIRMATION_NATIVE_1 — a mission is waiting for a YES/NO reply (e.g. "Îl trimit?").
    // Arm the native one-shot listener instead of relying on the JS mic loop to re-arm itself:
    // proven live that BENSON backgrounded in WhatsApp leaves those JS retry timers inert, so a
    // spoken "da" was never even heard. The listening window itself is now native-timed.
    // ROUND_GENERIC_CONFIRMATION_FIX_1 — read-and-clear immediately so it can never leak into an
    // unrelated later TTS-end event; the WhatsApp mission-gate condition is untouched/unaffected.
    const wasDisambigReply = pendingDisambigReplyRef.current;
    pendingDisambigReplyRef.current = false;
    if (wasBlocking && (pendingMissionTaskRef.current || wasDisambigReply)) {
      const confirmationId = `confirm-${Date.now()}`;
      pendingConfirmationIdRef.current = confirmationId;
      logAudioDiag('CONFIRM_LISTEN_ARM', `confirmationId=${confirmationId} timeoutMs=${CONFIRMATION_LISTEN_TIMEOUT_MS} source=${wasDisambigReply ? 'disambiguation' : 'mission_gate'}`);
      try { setMicLevel(0.3, true); } catch {}
      try { startConfirmationListening(confirmationId, CONFIRMATION_LISTEN_TIMEOUT_MS); } catch {}
    }
  }

  // Restart whatever should be listening after a forced unblock / foreground return. Both
  // doStartListening() and startLocalWakeLoop() self-guard against double-starts.
  function resumeListeningAfterUnblock() {
    if (silencedRef.current) return;
    if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) {
      logAudioDiag('MIC_BLOCKED', 'reason=whatsapp_call_live');
      return;
    }
    if (convModeRef.current) { doStartListening(); return; }
    try { resumePassiveWake(); } catch {}
  }

  // ── BENSON_STABILIZATION_1 — the ONE guaranteed post-TTS re-arm. Called from EVERY prompt
  // branch (success / fail / cancel / timeout / ambiguity). Exactly one recognizer is re-armed
  // for the expected next state; never leaves wake_loop_stopped / speakingRef stuck.
  function afterPromptRearm(expectReply: boolean) {
    logAudioDiag('WAKE_REARM_AFTER_TTS', `expectReply=${expectReply} conv=${convModeRef.current} wakeTriggered=${wakeTriggeredRef.current}`);
    if (silencedRef.current) { logAudioDiag('WAKE_REARM_FAIL', 'reason=silenced'); return; }
    if (speakingRef.current) { endTtsBlock('watchdog'); }
    if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) {
      logAudioDiag('WAKE_REARM_FAIL', 'reason=whatsapp_call_mic_hold');
      return;
    }
    if (expectReply || convModeRef.current || wakeTriggeredRef.current) { try { doStartListening(); } catch {} }
    else { try { resumePassiveWake(); } catch {} }
    logAudioDiag('WAKE_REARM_OK', `mode=${(expectReply || convModeRef.current || wakeTriggeredRef.current) ? 'command' : 'wake'}`);
  }

  // BENSON_STABILIZATION_1 — is `transcript` (from STT session `sid`) a genuine USER_MIC turn, or
  // BENSON's own speech / a stale callback? A capture whose window overlapped TTS (or the tail
  // guard), or from a superseded session, is BENSON_OUTPUT and MUST be dropped.
  function userMicGate(sid: string): { ok: true } | { ok: false; reason: string } {
    if (sid !== jsSttSessionIdRef.current) return { ok: false, reason: 'STALE_SESSION' };
    if (speakingRef.current) return { ok: false, reason: 'TTS_ACTIVE' };
    const captureStarted = sttCaptureStartedAtRef.current;
    if (captureStarted > 0 && captureStarted <= ttsEndedAtRef.current + AUDIO_TAIL_GUARD_MS) {
      return { ok: false, reason: 'TTS_TAIL' };
    }
    return { ok: true };
  }

  function speakOnDevice(text: string, onFinished?: () => void) {
    // Watchdog: confirmed live 2026-07-30 — this device's system TTS engine can silently never
    // fire onDone/onError/onStopped at all (same category of unreliability already proven for its
    // SpeechRecognizer). Without a fallback, speakingRef stays stuck true forever, and
    // doStartListening()'s guard (`|| speakingRef.current`) then silently no-ops on every future
    // call — BENSON just goes deaf with zero log trace, since the guard returns before the first
    // log line.
    // 2026-08-28: the old `max(6000, words*110+4000)` fired at 6s for a 12-word confirmation
    // question ("Deschid WhatsApp, caut «X», aleg primul rezultat și apăs apelul vocal. Confirmi?")
    // — before it was even spoken — releasing speakingRef so the mic reopened mid-sentence and
    // BENSON heard its own question. System TTS on this device runs closer to ~400ms/word once
    // engine start-up and sentence pauses are counted; the watchdog is a last-resort safety net,
    // not a real timeout, so it is deliberately generous: never below 8s.
    let settled = false;
    const wordCount = text.trim().split(/\s+/).filter(Boolean).length;
    const watchdogMs = Math.max(8000, wordCount * 400 + 5000);
    const settle = (reason: TtsEndReason) => {
      if (settled) return;
      settled = true;
      if (watchdogTimer) clearTimeout(watchdogTimer);
      endTtsBlock(reason); onFinished?.();
    };
    const watchdogTimer = setTimeout(() => {
      logAudioDiag('TTS_WATCHDOG_TIMEOUT', `wordCount=${wordCount} timeoutMs=${watchdogMs}`);
      settle('error');
    }, watchdogMs);
    speakNow(text, {
      language: replyLangRef.current,
      pitch:    voicePitchRef.current,
      rate:     voiceRateRef.current,
      voice:    voiceIdRef.current || undefined,
      onDone:    () => settle('success'),
      onError:   () => settle('error'),
      onStopped: () => { if (!settled) { settled = true; clearTimeout(watchdogTimer); endTtsBlock('interrupt'); } },
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
    // E1-0 backstop — no speech that isn't downstream of a recent user command/touch. onFinished
    // is still called so a caller chaining doStartListening() in the callback keeps listening.
    if (e1SuppressSpeak()) { onFinished?.(); return; }
    rememberSpoken(text);
    bumpSessionKeepAwake();
    stopSpeaking();
    beginTtsBlock();
    if (ttsProviderRef.current === 'gemini' && geminiKeyRef.current) {
      speakWithGemini(
        text, geminiKeyRef.current, 'Kore',
        () => { endTtsBlock('success'); onFinished?.(); },
      ).catch(() => speakOnDevice(text, onFinished));
    } else if (ttsProviderRef.current === 'openai' && openaiKeyRef.current) {
      speakWithOpenAI(
        text, openaiKeyRef.current, 'onyx',
        () => { endTtsBlock('success'); onFinished?.(); },
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
    // E1-0 backstop — see e1SuppressSpeak / speakText.
    if (e1SuppressSpeak()) return;
    rememberSpoken(text);
    bumpSessionKeepAwake();
    // Fire-and-forget still blocks the mic for the duration + tail (previously it did NOT touch
    // speakingRef at all, so the mic stayed open through these replies — a real echo source).
    beginTtsBlock();
    if (ttsProviderRef.current === 'gemini' && geminiKeyRef.current) {
      speakWithGemini(text, geminiKeyRef.current, 'Kore', () => endTtsBlock('success'))
        .catch(() => speakOnDevice(text));
    } else if (ttsProviderRef.current === 'openai' && openaiKeyRef.current) {
      speakWithOpenAI(text, openaiKeyRef.current, 'onyx', () => endTtsBlock('success'), currentVoiceInstructions())
        .catch(() => speakOnDevice(text));
    } else {
      speakOnDevice(text);
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
    speakText(msg, () => afterPromptRearm(false));
  }

  // Conversation mode is on by default — no button needed. Called whenever the
  // app enters the main 'chat' screen, so it's always listening after the greeting.
  function enterChatMode(name: string, l: string, enabled: boolean, rate: number, pitch: number) {
    // If the user left BENSON in silent/off mode, respect it on boot: no greeting, no listening.
    if (silencedRef.current) { convModeRef.current = false; setConvMode(false); return; }
    convModeRef.current = true;
    setConvMode(true);
    if (backgroundModeRef.current) startBackgroundService();
    // E1-0: no greeting on app start — not a message, not a chat bubble, not TTS. BENSON just
    // enters silent listening; the phase='chat' useEffect + listen self-heal start the mic.
    if (E1_USER_ONLY) { logAudioDiag('SPEAK_SUPPRESSED', 'reason=no_user_command source=boot_greeting'); return; }
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

  // Battery-fix hibernation kill switch — the actual enforcement (stop the wake engine after 2h
  // idle with the screen off) lives entirely in native (BensonForegroundService.kt), gated on the
  // same SharedPreferences key this pushes to. This just persists the choice and syncs the
  // toggle's visual state, same shape as toggleWakeWord above.
  async function toggleHibernation(v: boolean) {
    setHibernationEnabledState(v);
    await AsyncStorage.setItem('bensonHibernationEnabled', v.toString());
    try { setHibernationEnabled(v); } catch {}
  }

  // ROUND_WAKE_NATIVE_GENERIC_1 — change the ONE authoritative wake name (default "Benson"),
  // pushed to native immediately so the native cloud wake loop uses it on its very next cycle.
  // Not a secret — plain AsyncStorage, like the other non-key settings on this screen.
  async function changeWakeName(v: string) {
    setWakeNameState(v);
    const resolved = v.trim() || 'Benson';
    wakeNameRef.current = resolved;
    await AsyncStorage.setItem('bensonWakeName', v);
    try { setWakeName(resolved); } catch {}
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
    const gk = geminiKey.trim();
    const grq = groqKey.trim();
    apiKeyRef.current = ak;
    tavilyKeyRef.current = tk;
    openaiKeyRef.current = ok;
    geminiKeyRef.current = gk;
    setGeminiKeyForStt(gk || null);
    await AsyncStorage.multiSet([
      ['anthropicKey', ak], ['tavilyKey', tk], ['openaiKey', ok], ['geminiKey', gk],
    ]);
    // Groq key goes to expo-secure-store (settingsStore), not AsyncStorage — only written when the
    // field is non-empty, so an untouched blank field never clears a previously saved key.
    if (grq) {
      try {
        await saveEngineConfig('stt', 'groq', { apiKey: grq });
        setSavedGroqMasked(maskApiKey(grq));
        setGroqKey('');
        // ROUND_WAKE_NATIVE_GENERIC_1 — re-push to native immediately so a key entered/rotated
        // while the app is open takes effect on the native cloud wake loop's very next cycle,
        // without needing an app restart.
        try { setNativeWakeCredentials(grq, GROQ_STT_DEFAULT_BASE_URL, GROQ_STT_DEFAULT_MODEL); } catch {}
      } catch {}
    }
    // DEV_STT_DEEPGRAM_1 — a SEPARATE credential push (own SharedPreferences key) from the Groq
    // one above, so the native confirmation listener switching to Deepgram never touches the
    // passive wake loop's (NativeCloudWake.kt) Groq credentials.
    const dg = deepgramKey.trim();
    if (dg) {
      try {
        await saveEngineConfig('stt', 'deepgram', { apiKey: dg });
        setSavedDeepgramMasked(maskApiKey(dg));
        setDeepgramKey('');
        try { setConfirmationSttCredentials(dg); } catch {}
      } catch {}
    }
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

  function previewGeminiVoice() {
    if (!geminiKeyRef.current) {
      addMessage('benson', `I need a Gemini API key to use that voice, ${getAddress()}.`);
      return;
    }
    stopGeminiTTS().catch(() => {});
    speakWithGemini(`This is how I sound, ${getAddress()}.`, geminiKeyRef.current, 'Kore')
      .catch(() => addMessage('benson', `I could not reach the Gemini voice service, ${getAddress()}.`));
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
      speakText(reply, () => afterPromptRearm(false));
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
      pendingVignetteRef.current = border; gateArmedAtRef.current = Date.now();
      setBensonState('CONFIRMING', 'vignette');
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
  // "wake up" as a standalone alternative wake phrase (product-owner-directed 2026-08-25) \u2014 until
  // now it only ever worked as a no-op SUFFIX after "Benson" (see WAKE_NOOP_SUFFIXES below); it
  // never worked alone as its own activation trigger. Same fuzzy tolerance rationale as "Benson"
  // above \u2014 this local Whisper setup regularly mis-hears short phrases.
  const WAKE_UP_VARIANTS = ['wake up', 'wakeup', 'weyk up', 'weck up'];
  function detectWakeWord(text: string): boolean {
    const norm = (text || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    if (!norm.trim()) return false;
    if (WAKE_VARIANTS.some((w) => norm.includes(w))) return true;
    if (WAKE_UP_VARIANTS.some((w) => norm.includes(w))) return true;
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
  // "Benson wake up" (product-owner-directed 2026-08-23) is meant to behave EXACTLY like bare
  // "Benson" \u2014 a pure activation phrase, not a command. Without this list, stripWakeWord would
  // extract "wake up" as a commandTail and hand it straight to handleIncomingText, which would
  // route it through the full orchestrator/LLM pipeline as if it were a real (nonsensical) command.
  const WAKE_NOOP_SUFFIXES = ['wake up', 'treze\u0219te-te', 'trezeste-te', 'trezire'];
  // ROUND_WAKE_COMMAND_HANDOFF_FIX_1 \u2014 the ONE place "is this text just a wake-control phrase, not
  // a real command" is decided, shared by stripWakeWord() (below, for the JS-local engine, which
  // extracts its own tail from a raw transcript) AND handleWakeDetected() (the single shared
  // post-wake handler every engine funnels through). Confirmed live: a NATIVE-sourced wake event
  // (legacy regex engine or NativeCloudWake) extracts its OWN commandTail independently and has no
  // knowledge of WAKE_NOOP_SUFFIXES \u2014 "Benson wake up" reached handleWakeDetected with
  // commandTail="wake up" and was dispatched as a real command ("AM \u00ceN\u021aELES: wake up", no action).
  // Reuses the SAME list \u2014 no second phrase table.
  function isWakeControlPhrase(text: string): boolean {
    const norm = text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[,.:;!?-]+$/, '').trim();
    return WAKE_NOOP_SUFFIXES.includes(norm);
  }
  // stripWakeWord: return whatever the user said AFTER "Benson" as the command tail (so
  // "Benson, deschide Waze" runs "deschide Waze" in one breath); empty if only the wake word (or a
  // no-op activation suffix like "wake up" \u2014 see WAKE_NOOP_SUFFIXES above).
  function stripWakeWord(text: string): string {
    const idx = text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').search(/bens|benz|bent|pens/);
    if (idx < 0) return '';
    const after = text.slice(idx).replace(/^[^\s]+[\s,.:;!?-]*/, '').trim(); // drop the wake token itself
    if (isWakeControlPhrase(after)) return '';
    return after;
  }

  // Single reusable wake handler — invoked by BOTH the native hotword listener AND the local
  // Whisper scan loop, so there is exactly ONE wake-handling path (no duplication).
  async function handleWakeDetected(commandTail: string) {
    if (silencedRef.current) return; // fully off — ignore wake events entirely
    // A recognized wake word IS a direct user action — everything downstream this turn is allowed
    // to speak (E1-0).
    noteUserAction();
    // E1-1: BENSON no longer brings its own Activity to the front on wake. The native side still
    // shows the bubble + wake ring (WindowManager overlays) as the visible "heard you" cue; the
    // user surfaces the full UI by tapping the bubble. Revert: E1_NO_SELF_FOREGROUND = false.
    if (!E1_NO_SELF_FOREGROUND) { try { bringToForeground(); } catch {} }
    // SHADOW_MODE_1 — no activation sound on wake detection; transitions straight into active
    // listening silently.
    logAudioDiag('LISTEN_STARTED', 'source=wake_word');
    stopWakeScan();
    wakeScanningRef.current = false;
    wakeTriggeredRef.current = true;
    bumpSessionKeepAwake();
    try { await pauseHotword(); } catch {}
    const rawTail = (commandTail || '').trim();
    // ROUND_WAKE_COMMAND_HANDOFF_FIX_1 — "Benson wake up" (or any WAKE_NOOP_SUFFIXES phrase) must
    // behave exactly like bare "Benson", regardless of which engine extracted the tail natively.
    const isControlOnly = !!rawTail && isWakeControlPhrase(rawTail);
    if (isControlOnly) logAudioDiag('WAKE_CONTROL_CONSUMED', `phrase="${rawTail}"`);
    const tail = isControlOnly ? '' : rawTail;
    if (tail) {
      logAudioDiag('WAKE_HANDOFF_TO_STT', 'mode=same_breath_command_tail');
      tap();
      handleIncomingText(tail, { viaVoice: true, utteranceBytes: getLastUtteranceBytes() });
    } else {
      // E1-0: bare "Benson" (or "Benson wake up") gets the non-verbal chime above and goes
      // straight to listening — no spoken "Te ascult." (that reads as BENSON initiating
      // conversation; "Tăcerea e implicită"). Revert: E1_USER_ONLY = false restores the prompt.
      logAudioDiag('WAKE_HANDOFF_TO_STT', E1_USER_ONLY ? 'mode=bare_wake_word_chime_then_listen' : 'mode=bare_wake_word_prompt_then_listen');
      logAudioDiag('WAKE_COMMAND_ARMED', `reason=${isControlOnly ? 'control_phrase_consumed' : 'bare_wake_word'}`);
      if (E1_USER_ONLY) {
        doStartListening();
      } else {
        const msg = WAKE_LISTENING_PROMPT[replyLangRef.current] || WAKE_LISTENING_PROMPT['en-GB'];
        speakText(msg, () => doStartListening());
      }
    }
  }

  // ── WAKE HEALTH DIAGNOSIS ────────────────────────────────────────────────────────────────────
  // The single truth about whether saying "Benson" can actually activate right now — computed
  // from real runtime refs, NOT the notification. `reason` is the FIRST broken stage (the ladder
  // mirrors startLocalWakeLoop's own guards). Also picks the honest notification body.
  function computeWakeHealth() {
    const now = Date.now();
    const micHold = waCallMicHoldActiveSafe() || whatsappCallMicHoldUntilRef.current > now;
    const audioReceiving = now - lastAudioAtRef.current < 3500;
    const engine = wakeEngineRef.current;
    const localScanning = engine === 'local' && wakeScanningRef.current;
    const commandListening = listeningRef.current;
    const transcript = lastUserTranscriptRef.current || '';
    let wakeMatch = false;
    try { wakeMatch = !!transcript && detectWakeWord(transcript); } catch {}

    // reason ladder — first hit wins.
    let reason = 'ok';
    if (!serviceActiveRef.current) reason = 'service_dead';
    else if (silencedRef.current) reason = 'silenced_by_user';
    else if (!wakeWordEnabledRef.current) reason = 'wake_word_disabled';
    else if (micHold) reason = 'whatsapp_call_mic_hold';
    else if (speakingRef.current) reason = 'tts_speaking';
    else if (now < micResumeAtRef.current) reason = 'tts_tail';
    else if (convModeRef.current) reason = commandListening ? 'conv_mode_listening' : 'conv_mode_idle';
    else if (engine !== 'local') reason = 'engine_native_state_unknown';
    else if (wakeTriggeredRef.current || loadingRef.current) reason = 'processing_command';
    else if (!localScanning && !commandListening) reason = 'wake_loop_stopped';
    else if (localScanning && !audioReceiving) reason = 'no_audio_frames';

    const listeningNow = localScanning || commandListening || reason === 'conv_mode_listening';
    const recognizer =
      listeningNow ? 'LISTENING'
      : (reason === 'no_audio_frames' ? 'ERROR' : 'STOPPED');
    // "activation READY" only when nothing is blocking AND the recognizer can take audio.
    const transient = reason === 'tts_speaking' || reason === 'tts_tail' || reason === 'processing_command' || reason === 'conv_mode_idle';
    const activation = (reason === 'ok' || reason === 'conv_mode_listening') ? 'READY' : (transient ? 'READY' : 'BLOCKED');

    // Honest notification body — "listening" ONLY when actually listening + receiving audio.
    let notifBody: string;
    if (listeningNow && audioReceiving && (reason === 'ok' || reason === 'conv_mode_listening')) notifBody = 'BENSON is listening.';
    else if (transient) notifBody = 'BENSON is listening.'; // brief, don't flap the notification
    else if (reason === 'whatsapp_call_mic_hold') notifBody = 'BENSON microphone blocked';
    else if (reason === 'silenced_by_user') notifBody = 'BENSON silenced — tap LISTEN';
    else if (reason === 'wake_word_disabled') notifBody = 'BENSON wake word off';
    else if (reason === 'service_dead') notifBody = 'BENSON wake inactive';
    else notifBody = 'BENSON wake inactive';

    // ROUND_WAKE_STATE_BUG_1 §4 — WAKE_READY is true ONLY when every wake precondition holds.
    const wakeReady =
      serviceActiveRef.current &&
      a11yBoundRef.current === 'BOUND' &&
      recognizer === 'LISTENING' &&
      !micHold &&
      !speakingRef.current &&
      audioReceiving &&
      activation === 'READY';

    return {
      service: serviceActiveRef.current ? 'ALIVE' : 'DEAD',
      a11y: a11yBoundRef.current,
      recognizer,
      micHold,
      speaking: speakingRef.current,
      audio: audioReceiving ? 'RECEIVING' : 'NONE',
      transcript: transcript.slice(0, 60),
      wakeMatch,
      activation,
      reason,
      wakeReady,
      notifBody,
    };
  }

  // Emit WAKE_HEALTH (deduped; at least every 30s) and push the honest notification body.
  function emitWakeHealth() {
    const h = computeWakeHealth();
    const line =
      `service=${h.service} a11y=${h.a11y} recognizer=${h.recognizer} micHold=${h.micHold} ` +
      `speaking=${h.speaking} audio=${h.audio} lastTranscript=${JSON.stringify(h.transcript)} wakeMatch=${h.wakeMatch} ` +
      `activation=${h.activation} WAKE_READY=${h.wakeReady} reason=${h.wakeReady ? 'ok' : h.reason}`;
    const now = Date.now();
    if (line !== wakeHealthLineRef.current || now - wakeHealthAtRef.current > 30000) {
      wakeHealthLineRef.current = line;
      wakeHealthAtRef.current = now;
      logAudioDiag('WAKE_HEALTH', line);
    }
    if (h.notifBody !== notifBodyRef.current && h.service === 'ALIVE') {
      notifBodyRef.current = h.notifBody;
      try { updateNotification('BENSON', h.notifBody); } catch {}
      logAudioDiag('WAKE_NOTIF_UPDATED', `body=${JSON.stringify(h.notifBody)} reason=${h.reason}`);
    }
  }

  // The free passive wake loop: one VAD-gated Whisper capture; if it heard "Benson" hand off to the
  // command flow, otherwise scan again. Self-restarting. Guarded so it never overlaps a command
  // capture, TTS, or an in-flight session.
  function startLocalWakeLoop() {
    if (silencedRef.current) return;
    if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) { logAudioDiag('MIC_BLOCKED', 'reason=whatsapp_call_live'); return; }
    if (wakeEngineRef.current !== 'local') return;
    if (!serviceActiveRef.current) return;
    if (wakeScanningRef.current) return;
    if (listeningRef.current || loadingRef.current || wakeTriggeredRef.current) return;
    // Mic stays shut while BENSON speaks and for TTS_TAIL_MS after — the wake scan is a capture too.
    if (speakingRef.current) { logAudioDiag('MIC_BLOCKED', 'reason=tts_speaking'); return; }
    const ttsTailLeft = micResumeAtRef.current - Date.now();
    if (ttsTailLeft > 0) { setTimeout(startLocalWakeLoop, ttsTailLeft + 20); return; }
    // Enforced here, at the single source, not just at each call site (product-owner-directed
    // 2026-08-23 fix) — conversation mode's own doStartListening self-restart loop and this wake
    // scan loop must never run concurrently; they'd race for the same mic/whisper pipeline. While
    // conv mode is on, BENSON is already continuously listening for the next command directly, so
    // scanning for the wake word on top of that is both redundant and actively harmful.
    if (convModeRef.current) return;
    wakeScanningRef.current = true;
    sttCaptureStartedAtRef.current = Date.now(); // BENSON_STABILIZATION_1 — capture-window start
    logAudioDiag('AUDIO_STATE', 'from=PROCESSING to=WAKE_LISTENING');
    logAudioDiag('STT_SESSION_START', `sessionId=wake-${sttCaptureStartedAtRef.current} mode=WAKE`);
    logAudioDiag('WAKE_SCAN_START', `engine=local lang=${langRef.current}`);
    startWakeScan(
      langRef.current,
      (text) => {
        wakeScanningRef.current = false;
        if (detectWakeWord(text)) {
          logAudioDiag('WAKE_SCAN_HIT', `text="${text}"`);
          handleWakeDetected(stripWakeWord(text));
        } else {
          // Diagnostic (product-owner-confirmed live 2026-08-25): a MISS here previously logged
          // nothing at all — every wake-scan cycle that failed to recognize "Benson" was a total
          // black box, with no way to tell whether the mic never heard real speech, Whisper
          // mangled it into something detectWakeWord's fuzzy match still couldn't catch, or the
          // word simply wasn't said in that window. Logging the actual transcribed text on every
          // miss (not just every hit) turns that black box into real evidence.
          logAudioDiag('WAKE_SCAN_MISS', `text="${text}"`);
          setTimeout(startLocalWakeLoop, 150); // not the wake word — keep listening
        }
      },
      () => {
        wakeScanningRef.current = false;
        logAudioDiag('WAKE_SCAN_IDLE', 'no_speech_or_capture_error');
        setTimeout(startLocalWakeLoop, 150);
      },
    );
  }

  // Hand the mic back to passive listening after a command/return. Chooses the engine: local
  // Whisper loop (free, works on this device) or the native hotword loop (fallback).
  function resumePassiveWake() {
    if (silencedRef.current) return;
    if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) { logAudioDiag('MIC_BLOCKED', 'reason=whatsapp_call_live'); return; }
    if (nativeWakeRef.current) {
      // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native TFLite engine owns passive wake; just release
      // the mic back to it. It re-arms itself inside the foreground service (survives suspension).
      nwOwner('WAKE');
      return;
    }
    if (wakeEngineRef.current === 'local') {
      try { pauseHotword(); } catch {} // make sure the native SpeechRecognizer loop is off
      startLocalWakeLoop();
    } else {
      try { resumeHotword(); } catch {}
    }
  }

  // C3-fix — the ONE place an STT session is marked closed. Idempotent: only the first call for a
  // live session logs STT_SESSION_CLOSED + drops the gate + disarms the session watchdog; a later
  // endSub/errorSub for the same session is a no-op. Called from EVERY end path so the gate can
  // never outlive its session (the "BENSON went permanently deaf" bug).
  function closeSttSession(reason: 'result' | 'error' | 'stopped' | 'no_speech' | 'timeout' | 'background') {
    const sid = sttSessionActiveRef.current;
    if (!sid) return;
    sttSessionActiveRef.current = null;
    // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — idempotent: a session already closed by this function
    // has sid=null above and returns before this runs, so the native watchdog is only ever
    // cancelled once per session, from whichever end path (result/error/timeout/...) gets there first.
    try { cancelSttSessionWatchdog(sid); } catch {}
    if (C3_SESSION_CLEANUP) logAudioDiag('STT_SESSION_CLOSED', `session=${sid} reason=${reason}`);
  }

  async function doStartListening() {
    if (silencedRef.current) return;
    // URGENT_CONFIRMATION_NATIVE_1 — a native confirmation capture owns the mic; the JS loop must
    // not compete with it (native already refuses to re-arm passive wake for the same reason).
    if (pendingConfirmationIdRef.current) { logAudioDiag('MIC_BLOCKED', 'reason=native_confirmation_active'); return; }
    if (whatsappCallMicHoldUntilRef.current > Date.now() || waCallMicHoldActiveSafe()) { logAudioDiag('MIC_BLOCKED', 'reason=whatsapp_call_live'); return; }
    if (listeningRef.current || loadingRef.current) return;
    // ── C3-fix — single-session gate, now FIRST (before stopWakeScan below, which tears down the
    // shared capture-end subscription a live `local` session depends on). A rejected call must
    // touch nothing.
    if (C3_SINGLE_SESSION_GATE && sttSessionActiveRef.current) {
      logAudioDiag('STT_REJECTED', `reason=session_active activeSession=${sttSessionActiveRef.current} rejectedSession=js-${Date.now()}`);
      return;
    }
    // Hard mic close while BENSON is speaking, and for TTS_TAIL_MS after the last word.
    if (speakingRef.current) {
      logAudioDiag('MIC_BLOCKED', 'reason=tts_speaking');
      if (bensonStateRef.current !== 'CONFIRMING' && bensonStateRef.current !== 'EXECUTING') setBensonState('BLOCKED', 'tts_speaking');
      return;
    }
    const ttsTailLeft = micResumeAtRef.current - Date.now();
    if (ttsTailLeft > 0) {
      logAudioDiag('MIC_BLOCKED', `reason=tts_tail deferMs=${ttsTailLeft}`);
      setTimeout(() => doStartListening(), ttsTailLeft);
      return;
    }
    if (ttsEndedAtRef.current) {
      logAudioDiag('MIC_RESUMED', `afterMs=${Date.now() - ttsEndedAtRef.current}`);
      ttsEndedAtRef.current = 0;
    }
    // Post-action mute net — hold off starting a capture until the window elapses (see
    // POST_ACTION_MUTE_MS). Re-enters via the same path once the remaining time is up.
    const muteLeft = postActionMuteUntilRef.current - Date.now();
    if (muteLeft > 0) {
      logAudioDiag('POST_ACTION_MUTE', `deferMs=${muteLeft}`);
      setTimeout(() => doStartListening(), muteLeft);
      return;
    }
    try { stopWakeScan(); } catch {}
    wakeScanningRef.current = false;
    bumpSessionKeepAwake();
    const trigger = wakeTriggeredRef.current ? 'wake_word' : convModeRef.current ? 'conversation_mode' : 'manual_tap';
    const sessionId = `js-${Date.now()}`;
    jsSttSessionIdRef.current = sessionId;
    sttCaptureStartedAtRef.current = Date.now(); // BENSON_STABILIZATION_1 — capture-window start
    nwOwner('COMMAND_STT'); // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native wake releases the mic
    if (nativeWakeRef.current && nativeWakeEventAtRef.current > 0) {
      logAudioDiag('WAKE_TO_COMMAND_LATENCY', `ms=${Date.now() - nativeWakeEventAtRef.current}`);
      nativeWakeEventAtRef.current = 0;
    }
    logAudioDiag('COMMAND_STT_START', `sessionId=${sessionId}`);
    logAudioDiag('AUDIO_STATE', `from=PROCESSING to=COMMAND_LISTENING`);
    logAudioDiag('STT_SESSION_START', `sessionId=${sessionId} mode=COMMAND`);
    // C3 — gate raised synchronously (the check itself is at the top of this function now). Set
    // whenever either C3 flag is on so cleanup logging works standalone.
    if (C3_SINGLE_SESSION_GATE || C3_SESSION_CLEANUP) {
      sttSessionActiveRef.current = sessionId;
      logAudioDiag('STT_SESSION_OPEN', `sessionId=${sessionId} owner=js_stt`);
      // ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — a session that never sees an end event (orphaned
      // native onCaptureEnd, hung recognizer, or BENSON backgrounded mid-session e.g. during the
      // WhatsApp write flow) is force-closed after STT_SESSION_MAX_MS. Timing lives in the native
      // foreground service (Handler.postDelayed), NOT a JS setTimeout — proven live that the JS
      // timer this replaced goes inert while backgrounded, leaving the gate stuck forever
      // (STT_REJECTED reason=session_active, minutes on end, only an app restart cleared it).
      if (Number.isFinite(STT_SESSION_MAX_MS)) {
        try { armSttSessionWatchdog(sessionId, STT_SESSION_MAX_MS); } catch {}
      }
    }
    // A new session supersedes any pending partial-fallback grace timer from the previous one.
    if (partialFallbackTimerRef.current) { clearTimeout(partialFallbackTimerRef.current); partialFallbackTimerRef.current = null; }
    sttTriggerRef.current = trigger;
    sessionGotResultRef.current = false;
    if (bensonStateRef.current === 'IDLE' || bensonStateRef.current === 'BLOCKED' || bensonStateRef.current === 'DONE' || bensonStateRef.current === 'ERROR') {
      // URGENT_WAKE_FRESH_SESSION_1 — root cause of the stale-bubble bug: pushBubbleBand() always
      // rendered lastUserTranscriptRef.current (the PREVIOUS turn's transcript, only ever
      // overwritten once a NEW one is captured), so a fresh wake's first LISTENING paint showed
      // whatever the user said last time. This is the one true "new session" boundary (coming
      // from IDLE/BLOCKED/a terminal result) — clearing here, before setBensonState/pushBubbleBand
      // fires below, leaves a mid-conversation re-listen (already LISTENING/THINKING/etc., not
      // this branch) untouched, exactly matching "fresh on new wake, not on every turn."
      lastUserTranscriptRef.current = '';
      setBensonState('LISTENING', trigger);
    }
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
      // Run alongside checkMicPermission() (Promise.all), not sequentially before it — confirmed
      // live 2026-07-17: awaiting these one after another added a real ~200-400ms mic-handover gap
      // on every single conversation-mode turn (pause hotword -> THEN check permission -> THEN
      // create+start the recognizer), during which a user who starts talking the instant BENSON
      // stops has the first word(s) of their command land in dead air with nothing recording yet —
      // caught live as "sună-o pe mama pe WhatsApp" transcribing as just "pe mama pe WhatsApp".
      // The two calls are independent (one pauses the OTHER recognizer, the other checks THIS
      // session's own permission) — running them concurrently costs whichever is slower, not both.
      //
      // checkMicPermission() (non-prompting), not requestMicPermission() (prompting) — product-
      // owner-confirmed live regression 2026-08-23: this runs on EVERY conversation-mode turn
      // (many times per minute), and calling the prompting requestPermissionsAsync() that often —
      // including while BENSON is backgrounded, running only as a foreground service — was
      // confirmed in logcat to repeatedly launch Android's own
      // GrantPermissionsActivity even though the permission was already granted, stealing window
      // focus from whatever app the user was actually using every single turn ("nu a reacționat
      // și s-a închis" — a system permission dialog stealing focus looks exactly like that from
      // the user's side). Only escalate to the prompting call in the rare case the permission
      // isn't actually granted (e.g. revoked externally) — the common case now never prompts.
      const [, currentlyGranted] = await Promise.all([
        pauseHotword().catch(() => {}),
        checkMicPermission(),
      ]);
      const granted = currentlyGranted || await requestMicPermission();
      logAudioDiag('STT_PRECHECK', `session=${sessionId} permission=${granted} component=js_stt`);
      if (!granted) {
        const noMicMsg = `I need microphone access to hear you, ${getAddress()}.`;
        addMessage('benson', noMicMsg);
        // Always spoken, not gated behind conv-mode/settings — a blind user has no other way to
        // learn why voice input stopped working, and reading text isn't an option for them.
        speak(noMicMsg);
        setConvMode(false); convModeRef.current = false;
        closeSttSession('error'); // C3 — never leave a phantom session over a denied mic
        return;
      }
      setListening(true); listeningRef.current = true;
      try { setSystemSoundsMuted(true); } catch {}
      micCaptureDiagRef.current = {
        active: true, sessionId, recorderCreateAt: Date.now(), sttStartCalledAt: -1,
        speechStartAt: -1, speechEndAt: -1, firstRmsAboveThresholdAt: -1,
        peakRms: 0, rmsSum: 0, rmsCount: 0,
      };
      logAudioDiag('RECORDER_CREATE', `session=${sessionId} component=js_stt engine=${sttEngineRef.current} lang=${langRef.current}`);
      startRecognition(langRef.current, sttEngineRef.current);
      micCaptureDiagRef.current.sttStartCalledAt = Date.now();
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
          closeSttSession('timeout'); // C3-fix — don't rely on endSub after a forced stop
        }
      }, sttEngineRef.current === 'local' ? 65000 : 12000);
    } catch (e) {
      logAudioDiag('STT_ERROR', `session=${sessionId} component=js_stt code=-1 name=JS_EXCEPTION message="${String(e)}"`);
      try { setSystemSoundsMuted(false); } catch {}
      setListening(false); listeningRef.current = false;
      closeSttSession('error'); // C3 — the start threw; release the gate so the retry runs
      if (convModeRef.current) setTimeout(() => doStartListening(), 2000);
    }
  }

  function doStopListening() {
    try { stopRecognition(); } catch {}
    try { setSystemSoundsMuted(false); } catch {}
    setListening(false); listeningRef.current = false;
    closeSttSession('stopped'); // C3-fix — a deliberate stop closes the session immediately
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
    closeSttSession('stopped'); // C3-fix — hard stop closes any live STT session
    try { await pauseHotword(); } catch {}
    setListening(false); listeningRef.current = false;
    // stop everything that makes sound
    try { stopSpeaking(); } catch {}
    try { stopOpenAITTS(); } catch {}
    endTtsBlock('stop'); // C1 TASK 1 — never leave speakingRef raised on a hard stop
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
    clearBandHideTimer(); // E3 — cancel any pending "GATA" linger
    bandVisibleRef.current = false; // E2-2 — the band went down with the bubble service
    logAudioDiag('SILENT_MODE', 'state=on');
  }

  async function exitSilentMode() {
    noteUserAction(); // reached only by an explicit user action (banner tap / notification / voice)
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
      endTtsBlock('interrupt'); // C1 TASK 1
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
    // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 / ROUND_WAKE_NATIVE_GENERIC_1 — if EITHER a native model
    // (MicroWakeWord) or native cloud STT credentials (NativeCloudWake) are available, native
    // wake is PRIMARY: the JS Whisper wake loop stays off (wakeEngineRef !== 'local' makes
    // startLocalWakeLoop a no-op) and JS only drives the mic-ownership handoff. Neither present ⇒
    // nothing changes (JS Whisper wake fallback stays primary, exactly as before this round).
    try {
      const nw = isNativeWakeAvailable();
      const nativeEngineActive = !!(nw?.model || nw?.cloud);
      nativeWakeRef.current = nativeEngineActive;
      logAudioDiag('WAKE_ENGINE', `select=${nw?.model ? 'MICROWAKEWORD_NATIVE' : nw?.cloud ? 'NATIVE_CLOUD' : 'JS_WHISPER_FALLBACK'} nativeModel=${!!nw?.model} nativeCloud=${!!nw?.cloud}`);
      if (nativeEngineActive) {
        wakeEngineRef.current = 'native';
        try { stopWakeScan(); } catch {}
        wakeScanningRef.current = false;
        nwOwner('WAKE');
      }
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
    noteUserAction(); // direct touch / voice command reached this
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
      speakText(msg, () => afterPromptRearm(false));
    } else {
      doStopListening();
      stopSpeaking(); stopOpenAITTS().catch(() => {}); endTtsBlock('interrupt'); // C1 TASK 1
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
    noteUserAction(); // direct touch
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
      speakText(reply, () => afterPromptRearm(false));
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

  // MISSION-FIX-1 — verbs that mark a concrete NEW imperative command (not a yes/no confirmation
  // reply, not filler). Text is the diacritic-stripped, lowercased normConfirm() form. If one of
  // these leads/appears in an UNKNOWN confirmation reply, the pending mission is superseded and the
  // utterance is routed as a fresh command (ROUND_MISSION_DIAG_REPORT.md → invariant 1).
  const NEW_COMMAND_VERB_RE =
    /(?:^|\s)(?:deschide|deschideti|deschid|intra|porneste|pornesc|pune|baga|reda|redau|cauta|caut|gaseste|gasesti|sun|suna|apeleaza|apelez|telefoneaza|navigheaza|navighez|du-ma|du ma|mergi|arata|trimite|scrie|opreste|opresti|inchide|revino|intreaba|citeste|verifica|adauga|sterge|seteaza|redenumeste|open|start|play|search|find|call|dial|stop|close|show|launch|navigate|go to)(?:\s|$)/;

  function looksLikeNewCommand(msg: string): boolean {
    if (classifyConfirmation(msg) !== 'UNKNOWN') return false; // never override a clear da/nu
    const t = normConfirm(msg);
    if (!t || t.split(/\s+/).length < 2) return false; // 1-word filler ("hmm", "poate", "?") — not a command
    if (NEW_COMMAND_VERB_RE.test(' ' + t + ' ')) return true;
    try {
      // classify() returns intent 'CHAT' (confidence 0) for anything it does not recognise as a
      // concrete command; any other intent is a real, routable user goal.
      const intent = parseCommandToActionRequest(msg, 'voice').intent;
      return intent !== 'CHAT' && intent !== 'HELP';
    } catch { return false; }
  }

  // If `msg` is a concrete new command, clear EVERY pending-confirmation representation (governed
  // mission → Superseded; JS refs → null; reprompt counter → 0) and return true so the caller lets
  // the SAME utterance fall through to normal routing. Returns false for a genuinely unclear reply.
  async function supersedeStaleConfirmationIfNewCommand(msg: string): Promise<boolean> {
    logAudioDiag('CONFIRM_UNKNOWN_CHECK_NEW_COMMAND', `text="${msg.slice(0, 60)}"`);
    if (!looksLikeNewCommand(msg)) return false;
    const gm = getActiveMission();
    const oldId = gm?.id ?? '-';
    let newIntent = '-';
    try { newIntent = parseCommandToActionRequest(msg, 'voice').intent; } catch {}
    logAudioDiag('CONFIRM_NEW_COMMAND_DETECTED', `oldMissionId=${oldId} newIntent=${newIntent}`);
    pendingMissionTaskRef.current = null;
    pendingNoteActionRef.current = null;
    pendingVignetteRef.current = null;
    confirmRepromptCountRef.current = 0;
    gateArmedAtRef.current = 0;
    if (gm && (gm.state === 'WaitingConfirmation' || gm.state === 'WaitingUser')) {
      try { await supersedeActiveMission('new_user_command'); }
      catch { try { await cancelActiveMission(); } catch {} }
    }
    logAudioDiag('MISSION_SUPERSEDED', `missionId=${oldId} reason=new_user_command`);
    return true;
  }

  // ── ROUND_EMERGENCY_CORE_1 ────────────────────────────────────────────────────────────────
  // Fire the native 112 route and speak the honest outcome. Native owns EMERGENCY_DIAL_START /
  // EMERGENCY_DIRECT_CALL_ALLOWED / EMERGENCY_LOCATION_AVAILABLE / EMERGENCY_SYSTEM_DIALER_OPENED /
  // EMERGENCY_ROUTE_FAIL; JS owns EMERGENCY_INTENT_DETECTED / _CONFIRM_REQUIRED / _CONFIRMED.
  async function routeEmergencyNow(): Promise<void> {
    try {
      const ctx = getEmergencyContext();
      if (ctx) logAudioDiag('EMERGENCY_CONTEXT_JS', `battery=${ctx.batteryPct} network=${ctx.network} location=${ctx.locationAvailable}`);
    } catch {}
    let res: { success: boolean; mode: string; reason: string | null };
    try {
      res = await routeEmergencyCall();
    } catch (e) {
      res = { success: false, mode: 'FAILED', reason: e instanceof Error ? e.message : String(e) };
    }
    logAudioDiag('EMERGENCY_ROUTE_RESULT', `success=${res.success} mode=${res.mode} reason=${res.reason ?? '-'}`);
    if (res.success && res.mode === 'DIRECT_CALL') {
      speakOrShow('Sun la 112.');
    } else if (res.success) {
      speakOrShow('Am deschis telefonul cu 112 pregătit. Apasă pe apel.');
    } else {
      speakOrShow('Nu am putut deschide telefonul pentru 112. Sună manual, te rog.');
    }
  }

  // Emergency gate — runs before every other gate in handleIncomingText. Returns 'handled' when
  // this turn was an emergency intent or a reply to the pending "Sun la 112?" prompt (caller must
  // stop), 'pass' otherwise.
  async function handleEmergencyTurn(msg: string): Promise<'handled' | 'pass'> {
    // (a) a generic-help confirmation is pending — this reply decides it.
    if (pendingEmergencyConfirmRef.current) {
      // An explicit "sună la 112" said now escalates straight through.
      if (classifyEmergencyIntent(msg) === 'EXPLICIT_112') {
        pendingEmergencyConfirmRef.current = false;
        emergencyRepromptRef.current = 0;
        logAudioDiag('EMERGENCY_CONFIRMED', 'via=explicit_escalation');
        await routeEmergencyNow();
        return 'handled';
      }
      const verdict = classifyConfirmation(msg);
      logAudioDiag('EMERGENCY_CONFIRM_CLASSIFY', `text="${msg.slice(0, 40)}" result=${verdict}`);
      if (verdict === 'YES') {
        pendingEmergencyConfirmRef.current = false;
        emergencyRepromptRef.current = 0;
        logAudioDiag('EMERGENCY_CONFIRMED', 'via=voice_yes');
        await routeEmergencyNow();
        return 'handled';
      }
      if (verdict === 'NO') {
        pendingEmergencyConfirmRef.current = false;
        emergencyRepromptRef.current = 0;
        logAudioDiag('EMERGENCY_ROUTE_FAIL', 'reason=user_cancelled');
        speakOrShow('Am anulat.');
        return 'handled';
      }
      // UNKNOWN — one reprompt, then clean cancel.
      if (emergencyRepromptRef.current >= 1) {
        pendingEmergencyConfirmRef.current = false;
        emergencyRepromptRef.current = 0;
        logAudioDiag('EMERGENCY_ROUTE_FAIL', 'reason=reprompt_exhausted');
        speakOrShow('Am renunțat la urgență. Dacă ai nevoie, spune „sună la 112".');
        return 'handled';
      }
      emergencyRepromptRef.current += 1;
      logAudioDiag('EMERGENCY_CONFIRM_REQUIRED', `reason=reprompt attempt=${emergencyRepromptRef.current}`);
      speakOrShow('Sun la 112? Spune da sau nu.');
      return 'handled';
    }

    // (b) fresh classification.
    const kind = classifyEmergencyIntent(msg);
    if (kind === 'EXPLICIT_112') {
      logAudioDiag('EMERGENCY_INTENT_DETECTED', `kind=explicit text="${msg.slice(0, 40)}"`);
      logAudioDiag('EMERGENCY_CONFIRMED', 'auto=true kind=explicit');
      await routeEmergencyNow();
      return 'handled';
    }
    if (kind === 'GENERIC_HELP') {
      logAudioDiag('EMERGENCY_INTENT_DETECTED', `kind=generic text="${msg.slice(0, 40)}"`);
      logAudioDiag('EMERGENCY_CONFIRM_REQUIRED', 'prompt=sun_la_112');
      pendingEmergencyConfirmRef.current = true;
      emergencyRepromptRef.current = 0;
      speakOrShow('Sun la 112?');
      return 'handled';
    }
    return 'pass';
  }

  // ORCH-FIX-1 — an unclear reply to an open confirmation gate: keep the pending action intact,
  // ask once, then a single listening session follows (no silent auto-listen loop).
  function confirmReprompt(type: string, reason: string = 'unclear_reply') {
    confirmRepromptCountRef.current += 1;
    logAudioDiag('CONFIRM_REPROMPT', `type=${type} reason=${reason} pendingPreserved=true attempt=${confirmRepromptCountRef.current}`);
    setLoading(false); loadingRef.current = false;
    setBensonState('CONFIRMING', 'confirm_reprompt');
    const lang = replyLangRef.current.toLowerCase();
    const rp = lang.startsWith('de')
      ? `Bitte bestätige mit ja oder nein, ${getAddress()}.`
      : lang.startsWith('en')
      ? `Please confirm with yes or no, ${getAddress()}.`
      : `Te rog confirmă cu da sau nu, ${getAddress()}.`;
    addMessage('benson', rp);
    speakText(rp, () => afterPromptRearm(true));
  }

  // ── Central message handler — routes through the Benson Core Orchestrator ──
  async function handleIncomingText(msg: string, opts?: { viaVoice?: boolean; utteranceBytes?: number }) {
    if (!msg || loadingRef.current) return;
    // ROUND_WAKE_COMMAND_HANDOFF_FIX_1 — captured ONCE at entry: whether this turn originated from
    // a wake trigger (same-breath tail, e.g. "Benson, deschide calculatorul" — CASE 3 — or a
    // follow-up utterance captured after a bare/control-only wake armed listening — CASE 4).
    // wakeTriggeredRef itself gets cleared by various completion paths DURING this turn's
    // processing, so a local snapshot is the only reliable way to still know at the end (used by
    // WAKE_COMMAND_RESULT below, next to the existing ORCHESTRATOR_HANDOFF_COMPLETED log).
    const wakeOriginated = wakeTriggeredRef.current;
    if (wakeOriginated) logAudioDiag('WAKE_COMMAND_DISPATCH', `text=${JSON.stringify(msg.slice(0, 60))}`);
    // ROUND_WA_WRITE_MESSAGE_PAYLOAD / CONTACT_DIAG — the exact transcript that entered routing,
    // before normalization/parsing. First stage of the payload trace.
    logAudioDiag('WA_PAYLOAD_RAW_STT', `text=${JSON.stringify(msg)} viaVoice=${!!opts?.viaVoice}`);
    // BENSON_STABILIZATION_1 — only text that passed the USER_MIC gate (or a typed submit) reaches
    // mission parsing. This is the single entry point.
    logAudioDiag('MISSION_INPUT', `turnId=${opts?.viaVoice ? jsSttSessionIdRef.current : 'typed'} text=${JSON.stringify(msg.slice(0, 60))}`);
    // E1-0 / E1-5 — a recognized utterance (voice) or typed submit (touch) is a direct user
    // command: this turn's replies are allowed to speak, and the immediate-ACK path is armed fresh.
    noteUserAction();
    ackSpokenThisTurnRef.current = false;
    // E2-2 — the final (assembled) command text is what the band shows.
    lastUserTranscriptRef.current = msg;
    pushBubbleBand(bensonStateRef.current);
    // E1-5 — the short ACK spoken the instant the Mission Orchestrator resolves the intent, in
    // parallel with the launch (Orchestrator fires this before the Android side effect). Voice
    // turns only; a typed command doesn't get spoken acks.
    const onMissionAck = opts?.viaVoice
      ? (t: string) => { logAudioDiag('ACK', `text="${t}"`); ackSpokenThisTurnRef.current = true; speak(t); }
      : undefined;
    bumpSessionKeepAwake();

    // Task 2 (2026-08-28): refuse a voice affirmative that came from empty/near-empty audio when a
    // Confirmation Gate is open — that "da" is a Whisper hallucination, not consent. The gate stays
    // pending and nothing executes. A refusal, a longer utterance, a typed reply, or a
    // SpeechRecognizer reply (utteranceBytes -1) are all exempt and fall through unchanged.
    // ORCH-FIX-1: use the robust classifier (was YES_PATTERN, which missed "Confirme.").
    if (opts?.viaVoice) {
      const gateOpen =
        !!pendingVignetteRef.current || !!pendingNoteActionRef.current || pendingEmergencyConfirmRef.current ||
        !!pendingMissionTaskRef.current || getActiveMission()?.state === 'WaitingConfirmation';
      if (gateOpen && classifyConfirmation(msg) === 'YES') {
        const uttBytes = opts.utteranceBytes ?? -1;
        if (uttBytes >= 0 && uttBytes < MIN_CONFIRM_BYTES) {
          logAudioDiag('CONFIRM_REJECTED', `reason=empty_audio bytes=${uttBytes}`);
          return;
        }
        logAudioDiag('CONFIRM_ACCEPTED', `bytes=${uttBytes}`);
      }
    }

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

    // ROUND_EMERGENCY_CORE_1 — explicit emergency intents are checked BEFORE every other gate, so
    // a stale mission / open confirmation / pending note can never intercept "sună la 112" or the
    // reply to "Sun la 112?". Not routed through the parser, the brain, or app governance.
    if (EMERGENCY_GATE_ENABLED) {
      const em = await handleEmergencyTurn(msg);
      if (em === 'handled') { setLoading(false); loadingRef.current = false; return; }
    }

    // A URL — open it immediately, no confirmation, no AI round-trip.
    const urlMatch = msg.match(URL_PATTERN);
    if (urlMatch) {
      const url = urlMatch[0].startsWith('http') ? urlMatch[0] : `https://${urlMatch[0]}`;
      await Linking.openURL(url).catch(() => {});
      setLoading(false); loadingRef.current = false;
      speakOrShow(`Opening that, ${getAddress()}.`);
      return;
    }

    // ROUND_CONTACT_IDENTITY_CONTINUITY_1 — a pending "Te referi la X?" from the local
    // ContextResolver, same priority tier as the other pending-choice gates below. Never re-asks
    // the LLM; only replays the ORIGINAL brain-classified action with the chosen identity.
    if (pendingPersonChoiceRef.current) {
      const pending = pendingPersonChoiceRef.current;
      let chosen: string | null = null;
      if (pending.candidates.length === 1) {
        const verdict = classifyConfirmation(msg);
        logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict} type=person_choice`);
        if (verdict === 'YES') chosen = pending.candidates[0].verifiedDisplayName;
        else if (verdict === 'NO') {
          pendingPersonChoiceRef.current = null;
          setLoading(false); loadingRef.current = false;
          setBensonState('IDLE', 'person_choice_cancelled');
          speakOrShow(`Am anulat, ${getAddress()}.`);
          return;
        }
      } else {
        const normMsg = msg.trim().toLowerCase();
        const matches = pending.candidates.filter((c) => normMsg.includes(c.verifiedDisplayName.toLowerCase()));
        if (matches.length === 1) chosen = matches[0].verifiedDisplayName;
      }
      pendingPersonChoiceRef.current = null;
      if (chosen) {
        const params = { ...pending.params, contact: chosen, contactName: chosen };
        const canonical = buildCanonicalCommand(pending.action, params, replyLangRef.current);
        logAudioDiag('CANONICAL', `text="${canonical}" source=person_choice_resolved`);
        if (canonical) {
          const contacts2 = await getLiveContacts();
          setBensonState('EXECUTING', `brain:${pending.action}`);
          const bridged = await runMission(canonical, { source: 'voice', contacts: contacts2, onAck: onMissionAck });
          logAudioDiag('ROUTE', `decision=command reason=person_choice_resolved handled=${bridged.handled}`);
          // finishHandledMission() isn't declared until later in this function (TDZ) — this
          // gate sits at the same early tier as the other pending-* gates above/below it, none of
          // which call it either. Minimal inline equivalent, same as those.
          const msgText = bridged.message && bridged.message.trim() ? bridged.message : `Nu am înțeles, ${getAddress()}. Spune din nou.`;
          const isConfirming = !!bridged.disambiguation || !!bridged.pendingTask || getActiveMission()?.state === 'WaitingConfirmation';
          setBensonState(isConfirming ? 'CONFIRMING' : (isFailureReply(msgText) ? 'ERROR' : 'DONE'), isConfirming ? 'mission_gate' : 'mission_result');
          addMessage('benson', msgText);
          setLoading(false); loadingRef.current = false;
          if (bridged.pendingTask) { pendingMissionTaskRef.current = bridged.pendingTask; gateArmedAtRef.current = Date.now(); }
          speakText(msgText, () => afterPromptRearm(true));
          return;
        }
      }
      // Not a recognized pick — falls through to normal handling below rather than looping forever.
    }

    // Context Engine – pending vignette confirmation takes priority
    // ORCH-FIX-1: classify FIRST, consume the ref only on a real decision.
    if (pendingVignetteRef.current) {
      const verdict = classifyConfirmation(msg);
      logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict}`);
      logAudioDiag('CONFIRM_PENDING', 'type=vignette present=true');
      if (verdict === 'YES') {
        const border = pendingVignetteRef.current;
        pendingVignetteRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CONSUME', 'type=vignette');
        logAudioDiag('CONFIRM_EXECUTE_START', 'type=vignette');
        let ok = true;
        try { await Linking.openURL(border.vignetteUrl); } catch { ok = false; }
        logAudioDiag('CONFIRM_EXECUTE_END', `type=vignette success=${ok}`);
        setLoading(false); loadingRef.current = false;
        setBensonState(ok ? 'DONE' : 'ERROR', 'confirm_result');
        speakOrShow(`Am deschis pagina pentru vinieta ${border.country}, ${getAddress()}.`);
        return;
      }
      if (verdict === 'NO') {
        pendingVignetteRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=vignette');
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_cancelled');
        speakOrShow(`Am anulat, ${getAddress()}.`);
        return;
      }
      if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) {
        pendingVignetteRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=vignette reason=reprompt_exhausted');
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_giveup');
        return;
      }
      confirmReprompt('vignette');
      return;
    }

    // Smart Voice Notepad – pending calendar/message confirmation takes priority
    // ORCH-FIX-1: classify FIRST, consume the ref only on a real decision.
    if (pendingNoteActionRef.current) {
      const verdict = classifyConfirmation(msg);
      logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict}`);
      logAudioDiag('CONFIRM_PENDING', 'type=note present=true');
      if (verdict === 'YES') {
        const note = pendingNoteActionRef.current;
        pendingNoteActionRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CONSUME', 'type=note');
        logAudioDiag('CONFIRM_EXECUTE_START', `type=note action=${note.actions.join('|')}`);
        setLoading(false); loadingRef.current = false;
        let ok = true;
        try {
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
        } catch { ok = false; }
        logAudioDiag('CONFIRM_EXECUTE_END', `type=note success=${ok}`);
        return;
      }
      if (verdict === 'NO') {
        pendingNoteActionRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=note');
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_cancelled');
        speakOrShow(`Am anulat, ${getAddress()}.`);
        return;
      }
      if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) {
        pendingNoteActionRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=note reason=reprompt_exhausted');
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_giveup');
        return;
      }
      confirmReprompt('note');
      return;
    }

    // Mission Orchestrator — pending mission task awaiting confirmation takes priority.
    // ORCH-FIX-1: classify FIRST; consume the ref only on YES; NO cancels (incl. the governed
    // mission); UNKNOWN keeps it and re-prompts (bounded, no infinite loop).
    if (pendingMissionTaskRef.current) {
      // ROUND_WA_REPLY_CONTEXT_1 — a pending task waiting for a dictated message body (BENSON just
      // asked "Ce să-i scriu?") is NOT a yes/no confirmation: the reply itself IS the missing data,
      // never classified, never treated as a new command. classifyConfirmation() would otherwise
      // misread a body like "nu mai vin" as a cancellation.
      const pendingTaskForBody = pendingMissionTaskRef.current.plan.tasks[pendingMissionTaskRef.current.taskIndex];
      const isAwaitingMessageBody = !!pendingTaskForBody?.input?.waAwaitingMessageBody;
      // RECOVERY_L9 (2026-09-13, IMPLEMENTED_ONLY — not device-tested) — a reply to "Îl trimit?"
      // that names a DIFFERENT contact ("nu Baby, Hannah" / "am spus Hannah") is a correction of the
      // recipient slot, not a yes/no. Only recognized when this task already typed a message and is
      // waiting on the send confirmation (waWriteMissionId set) — never during the message-body
      // question itself, never for a plain CALL confirmation, never a new mission.
      const isTypedAwaitingSend = pendingTaskForBody?.type === 'PREPARE_MESSAGE' && !isAwaitingMessageBody
        && typeof pendingTaskForBody?.input?.waWriteMissionId === 'string' && !!pendingTaskForBody.input.waWriteMissionId;
      const correctionMatch = isTypedAwaitingSend
        ? msg.match(/\bnu\b[^,]*,\s*(.+)$/i) ?? msg.match(/\bam spus\b\s+(.+)$/i) ?? msg.match(/\bm[ăa] refer la\b\s+(.+)$/i)
        : null;
      const correctedContact = correctionMatch?.[1]?.trim().replace(/[.!?]+$/, '') || null;
      logAudioDiag('WA_REPLY_RAW_STT', `missionId=${pendingMissionTaskRef.current.plan.id} expectedReplyType=${isAwaitingMessageBody ? 'MESSAGE_BODY' : (correctedContact ? 'CONTACT_CORRECTION' : 'CONFIRMATION')} rawText=${JSON.stringify(msg)}`);
      if (correctedContact) {
        const pending = pendingMissionTaskRef.current;
        pendingMissionTaskRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('WA_CONTACT_CORRECTION_DETECTED', `missionId=${pending.plan.id} newContact=${JSON.stringify(correctedContact)}`);
        setBensonState('EXECUTING', 'resume_task');
        let resumeResult: Awaited<ReturnType<typeof resumePendingTask>>;
        try {
          resumeResult = await resumePendingTask(pending, await getLiveContacts(), onMissionAck, undefined, correctedContact);
        } catch (e) {
          logAudioDiag('RESUME_FAILED', `reason=exception detail=${JSON.stringify(String(e)).slice(0, 120)} recovered=idle`);
          setLoading(false); loadingRef.current = false;
          setBensonState('IDLE', 'resume_recover');
          resumeListeningAfterUnblock();
          return;
        }
        setLoading(false); loadingRef.current = false;
        if (resumeResult.pendingTask) { pendingMissionTaskRef.current = resumeResult.pendingTask; gateArmedAtRef.current = Date.now(); }
        setBensonState(resumeResult.pendingTask ? 'CONFIRMING' : (isFailureReply(resumeResult.message) ? 'ERROR' : 'DONE'), 'resume_result');
        addMessage('benson', resumeResult.message);
        speakText(resumeResult.message, () => { if (resumeResult.pendingTask) doStartListening(); });
        return;
      }
      if (isAwaitingMessageBody) {
        // No separate normalization stage for this text — `msg` is already the single canonical
        // form by this point (BENSON_STABILIZATION_1's USER_MIC gate already ran upstream).
        logAudioDiag('WA_REPLY_NORMALIZED', `missionId=${pendingMissionTaskRef.current.plan.id} normalizedText=${JSON.stringify(msg)}`);
        logAudioDiag('WA_REPLY_CONTEXT_BEFORE', `missionId=${pendingMissionTaskRef.current.plan.id} contact=${JSON.stringify(pendingTaskForBody?.input?.waWriteContact ?? '')} message_body=null state=WAITING_MESSAGE_BODY`);
      }
      const verdict = isAwaitingMessageBody ? 'YES' : classifyConfirmation(msg);
      if (!isAwaitingMessageBody) logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict}`);
      logAudioDiag('CONFIRM_PENDING', 'type=mission present=true');
      if (verdict === 'NO') {
        pendingMissionTaskRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=mission');
        try { if (getActiveMission()) await cancelActiveMission(); } catch {}
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_cancelled');
        speakOrShow(`Am anulat, ${getAddress()}.`);
        return;
      }
      if (verdict === 'UNKNOWN') {
        // MISSION-FIX-1 — an UNKNOWN reply might be a brand-new command, not an unclear yes/no.
        // If so, drop this pending mission task and let the same utterance route normally.
        if (await supersedeStaleConfirmationIfNewCommand(msg)) {
          // fall through — do NOT return; normal routing below handles `msg`.
        } else if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) {
          pendingMissionTaskRef.current = null;
          confirmRepromptCountRef.current = 0;
          logAudioDiag('CONFIRM_CANCEL', 'type=mission reason=reprompt_exhausted');
          try { if (getActiveMission()) await cancelActiveMission(); } catch {}
          setLoading(false); loadingRef.current = false;
          setBensonState('IDLE', 'confirm_giveup');
          return;
        } else {
          confirmReprompt('mission', 'true_ambiguity');
          return;
        }
      }
      // verdict === 'YES' — capture, consume, execute exactly once. (MISSION-FIX-1: guarded
      // explicitly so an UNKNOWN reply that was superseded into a new command falls through here
      // instead of trying to resume a now-null pending task.)
      if (verdict === 'YES' && pendingMissionTaskRef.current) {
        const pending = pendingMissionTaskRef.current;
        pendingMissionTaskRef.current = null;
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CONSUME', 'type=mission');
        logAudioDiag('CONFIRM_EXECUTE_START', 'type=mission');
        setBensonState('EXECUTING', 'resume_task');
        // C1 TASK 3 — mark the resume "in flight" so the AppState 'active' handler can recover
        // cleanly if the app backgrounds (WhatsApp/Waze) mid-await and comes back.
        resumeInFlightRef.current = { startedAt: Date.now() };
        let resumeResult: Awaited<ReturnType<typeof resumePendingTask>>;
        try {
          resumeResult = await resumePendingTask(pending, await getLiveContacts(), onMissionAck, isAwaitingMessageBody ? msg : undefined);
        } catch (e) {
          // C1 TASK 4 — the resume threw (native rejected / state corrupt): no "Am rămas blocat",
          // clean IDLE recovery + restart listening.
          resumeInFlightRef.current = null;
          logAudioDiag('RESUME_FAILED', `reason=exception detail=${JSON.stringify(String(e)).slice(0, 120)} recovered=idle`);
          logAudioDiag('CONFIRM_EXECUTE_END', 'type=mission success=false');
          setLoading(false); loadingRef.current = false;
          if (C1_NO_SILENT_STALL) {
            setBensonState('IDLE', 'resume_recover');
            resumeListeningAfterUnblock();
            return;
          }
          throw e;
        }
        resumeInFlightRef.current = null;
        postActionMuteUntilRef.current = Date.now() + POST_ACTION_MUTE_MS;
        // C1 TASK 4 — a resume that finished with no message is a silent stall: recover to IDLE.
        if (C1_NO_SILENT_STALL && (!resumeResult || !resumeResult.message || !resumeResult.message.trim()) && !resumeResult?.pendingTask) {
          logAudioDiag('RESUME_FAILED', 'reason=empty_message recovered=idle');
          logAudioDiag('CONFIRM_EXECUTE_END', 'type=mission success=false');
          setLoading(false); loadingRef.current = false;
          setBensonState('IDLE', 'resume_recover');
          resumeListeningAfterUnblock();
          return;
        }
        setBensonState(
          resumeResult.pendingTask ? 'CONFIRMING' : (isFailureReply(resumeResult.message) ? 'ERROR' : 'DONE'),
          'resume_result',
        );
        logAudioDiag('RESUME', `source=action_done state=${bensonStateRef.current}`);
        logAudioDiag('CONFIRM_EXECUTE_END', `type=mission success=${!isFailureReply(resumeResult.message)}`);
        console.log('[MissionOrchestrator] resumed mission=', pending.plan.id, 'status=', resumeResult.plan?.status, 'message=', resumeResult.message);
        addMessage('benson', resumeResult.message);
        setLoading(false); loadingRef.current = false;
        if (resumeResult.pendingTask) { pendingMissionTaskRef.current = resumeResult.pendingTask; gateArmedAtRef.current = Date.now(); }
        // Same fire-and-forget speak() gap as the initial confirmation question above — a
        // multi-step mission's next confirmation ("da" to step 1, then a second "Confirmi?" for
        // step 2) would otherwise go silent the exact same way.
        const expectsReply2 = !!resumeResult.pendingTask;
        // WA-CALL-STAYS-LIVE — this resume just placed a WhatsApp voice call and it succeeded. The
        // native executor already verified the call screen is live and, by contract, does nothing
        // more. BENSON must now do nothing either: no result speech, no mic reopen, no wake-loop
        // restart (that concurrent mic grab kills the call). Hold the mic closed until BENSON is
        // foregrounded again or WA_CALL_MIC_HOLD_MS elapses.
        const resumedTask = pending.plan.tasks[pending.taskIndex];
        const wasWhatsAppVoiceCall =
          !!resumedTask &&
          resumedTask.type === 'PREPARE_MESSAGE' &&
          (resumedTask.input as { mode?: string } | undefined)?.mode === 'voice_call';
        if (WA_CALL_MIC_HOLD_MS > 0 && wasWhatsAppVoiceCall && !expectsReply2 && !isFailureReply(resumeResult.message)) {
          whatsappCallMicHoldUntilRef.current = Date.now() + WA_CALL_MIC_HOLD_MS;
          nwOwner('CALL'); // ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — suspend native wake for the call
          wakeTriggeredRef.current = false;
          try { hideWakeRing(); } catch {}
          logAudioDiag('SPEAK_SUPPRESSED', 'reason=whatsapp_call_live');
          logAudioDiag('MIC_HOLD', `reason=whatsapp_call_live untilMs=${WA_CALL_MIC_HOLD_MS}`);
          return;
        }
        const afterResumeSpeak = () => {
          if (expectsReply2) { doStartListening(); return; }
          if (convModeRef.current) { doStartListening(); return; }
          if (wakeTriggeredRef.current) {
            wakeTriggeredRef.current = false;
            try { hideWakeRing(); } catch {}
            try { resumePassiveWake(); } catch {}
          }
        };
        // E1-5 — "O sun." was already spoken in parallel with the WhatsApp/Waze side effect;
        // suppress the long final line on a clean success (still shown in the transcript above).
        if (ackSpokenThisTurnRef.current && !expectsReply2 && !isFailureReply(resumeResult.message)) {
          logAudioDiag('SPEAK_SUPPRESSED', 'reason=ack_already_spoken');
          afterResumeSpeak();
          // ROUND_ASSISTANT_SESSION_UX_FIX_1 — no additional TTS plays for this result (the ACK
          // already covered it); nothing else marks "the user could start reading this now".
          scheduleResultDismiss(RESULT_DWELL_NO_TTS_MS);
        } else {
          speakText(resumeResult.message, afterResumeSpeak);
        }
        return;
      }
      // (every verdict above returns — no fall-through)
    }

    // Mission Governance (Waze/WhatsApp, Phase 1) — pending confirmation / awaiting user
    // resolution takes priority, same position/pattern as the pending-* checks above. This also
    // catches confirmations from the Claude tool-use path (lib/agents/tools.ts), which has no
    // pendingMissionTaskRef of its own — the AsyncStorage-backed store is what lets that path be
    // confirmed on a later turn regardless of which one asked the question.
    // ORCH-FIX-1: same YES / NO / UNKNOWN classifier as the pending-* gates above.
    const governedMission = getActiveMission();
    if (governedMission?.state === 'WaitingConfirmation') {
      const verdict = classifyConfirmation(msg);
      logAudioDiag('CONFIRM_CLASSIFY', `text="${msg}" result=${verdict}`);
      logAudioDiag('CONFIRM_PENDING', 'type=governed present=true');
      if (verdict === 'YES') {
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CONSUME', 'type=governed');
        logAudioDiag('CONFIRM_EXECUTE_START', 'type=governed');
        setBensonState('EXECUTING', 'confirm_mission');
        const outcome = await confirmActiveMission(await getLiveContacts());
        postActionMuteUntilRef.current = Date.now() + POST_ACTION_MUTE_MS;
        logAudioDiag('CONFIRM_EXECUTE_END', `type=governed success=${!!outcome && !isFailureReply(outcome.message)}`);
        setLoading(false); loadingRef.current = false;
        setBensonState(!outcome || isFailureReply(outcome.message) ? 'ERROR' : 'DONE', 'confirm_result');
        if (outcome) { addMessage('benson', outcome.message); speak(outcome.message); }
        return;
      }
      if (verdict === 'NO') {
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=governed');
        await cancelActiveMission();
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_cancelled');
        speakOrShow(`Am anulat, ${getAddress()}.`);
        return;
      }
      // UNKNOWN — MISSION-FIX-1: is this a brand-new command rather than an unclear yes/no?
      // If so, mark the pending mission Superseded and let the SAME utterance route normally.
      if (await supersedeStaleConfirmationIfNewCommand(msg)) {
        // fall through — do NOT return; `governedMission` local is now stale, the block below
        // (`else if … WaitingUser`) is skipped, and normal routing handles `msg`.
      } else if (confirmRepromptCountRef.current >= MAX_CONFIRM_REPROMPTS) {
        confirmRepromptCountRef.current = 0;
        logAudioDiag('CONFIRM_CANCEL', 'type=governed reason=reprompt_exhausted');
        await cancelActiveMission();
        setLoading(false); loadingRef.current = false;
        setBensonState('IDLE', 'confirm_giveup');
        return;
      } else {
        confirmReprompt('governed', 'true_ambiguity');
        return;
      }
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

    // Build B — a "call a contact" utterance the deterministic parser will handle: run the SHARED
    // sanity checkpoint FIRST (same function the brain route uses, one physical impl in
    // lib/engines/actionSanity.ts), so "sună wake up" / a bare verb / foreign-script text never
    // reaches the Mission Orchestrator's contact resolution. BENSON asks who instead.
    if (CALL_PATTERN.test(msg)) {
      const cm = msg.match(/\b(?:call|sun[aăo]?|ruf|appelle)(?:-[oli]l?)?\s*(.*)$/i);
      const parsedContact = (cm?.[1] ?? '').replace(/^(?:o|l|le|îl|il|pe|la|lui)\s+/i, '').trim();
      if (parsedContact && !sanityCheckContactParam('parser', parsedContact, replyLangRef.current).ok) {
        const q = askWhoToCall(replyLangRef.current, getAddress());
        logAudioDiag('ROUTE', 'decision=clarify reason=bad_contact_param source=parser');
        addMessage('benson', q);
        setLoading(false); loadingRef.current = false;
        speakText(q, () => afterPromptRearm(true));
        return;
      }
    }

    // Mission Orchestrator (BENSON 21) — the deterministic upper brain. Round 2 / Build B keeps it
    // as the FAST PATH for obvious commands: whatever it recognizes here executes straight away,
    // unchanged. Anything it does NOT recognize (handled=false) now goes to the brain below instead
    // of the old English fallback — the deterministic parser no longer has the last word.
    logAudioDiag('ORCHESTRATOR_HANDOFF_REQUESTED', `text="${msg}"`);
    const contactsForMission = MAY_NEED_CONTACTS_PATTERN.test(msg) ? await getLiveContacts() : [];

    // Speaks a Mission Orchestrator result + wires the resume-listening / self-echo-loop logic.
    // Shared by the fast-path run and the brain-intent bridge run below so both behave identically.
    const finishHandledMission = (mr: Awaited<ReturnType<typeof runMission>>) => {
      console.log('[MissionOrchestrator] mission=', mr.plan?.id, 'status=', mr.plan?.status, 'message=', mr.message);

      // Round D — a handled mission with NO message must never leave BENSON silent.
      if (!mr.message || !mr.message.trim()) {
        const nf = replyLangRef.current.toLowerCase().startsWith('ro')
          ? `N-am înțeles, ${getAddress()}. Spune din nou.`
          : replyLangRef.current.toLowerCase().startsWith('de')
          ? `Ich habe das nicht verstanden, ${getAddress()}. Sag es noch mal.`
          : `I didn't catch that, ${getAddress()}. Say it again.`;
        setBensonState('ERROR', 'empty_mission_reply');
        addMessage('benson', nf);
        setLoading(false); loadingRef.current = false;
        speakText(nf, () => afterPromptRearm(true));
        return;
      }

      // Round D — a "which one?" proposal is a real pending state now (the orchestrator holds the
      // candidate list and routes the next utterance to it). Render it as CONFIRMING so it is not
      // auto-cleared off screen while the user decides.
      const isDisambig = !!mr.disambiguation;
      // ROUND_GENERIC_CONFIRMATION_FIX_1 — arm the native confirmation listener (via endTtsBlock,
      // below, once this TTS finishes) for a disambiguation question, same mechanism already
      // proven for WhatsApp's "Îl trimit?" gate. Set unconditionally (true or false) every call.
      pendingDisambigReplyRef.current = isDisambig;
      const isConfirming = isDisambig || !!mr.pendingTask || getActiveMission()?.state === 'WaitingConfirmation';
      setBensonState(
        isConfirming ? 'CONFIRMING' : (isFailureReply(mr.message) ? 'ERROR' : 'DONE'),
        isConfirming ? (isDisambig ? 'disambiguation' : 'mission_gate') : 'mission_result',
      );
      addMessage('benson', mr.message);
      setLoading(false); loadingRef.current = false;
      if (mr.pendingTask) { pendingMissionTaskRef.current = mr.pendingTask; gateArmedAtRef.current = Date.now(); }
      const expectsReply = !!mr.pendingTask || isDisambig || !mr.plan;
      const afterFinalSpeak = () => {
        if (noteSpokenAndCheckRepeatLoop(mr.message)) {
          if (wakeTriggeredRef.current) { wakeTriggeredRef.current = false; try { hideWakeRing(); } catch {} }
          try { resumePassiveWake(); } catch {}
          return;
        }
        if (expectsReply) { doStartListening(); return; }
        if (convModeRef.current) { doStartListening(); return; }
        if (wakeTriggeredRef.current) {
          wakeTriggeredRef.current = false;
          try { hideWakeRing(); } catch {}
          try { resumePassiveWake(); } catch {}
        }
      };
      // E1-5 — the short ACK ("Pornesc traseul." / "Deschid.") was already spoken in parallel with
      // the launch (see the onAck below). On a clean success, don't also speak the final line —
      // "confirmarea lungă de la final dispare". It still shows in the transcript (addMessage
      // above). A CONFIRMING result (needs the user's reply) or a failure is always spoken.
      if (ackSpokenThisTurnRef.current && !isConfirming && !isFailureReply(mr.message)) {
        logAudioDiag('SPEAK_SUPPRESSED', 'reason=ack_already_spoken');
        afterFinalSpeak();
        // ROUND_ASSISTANT_SESSION_UX_FIX_1 — no additional TTS plays for this result; mark it
        // readable now (the longer, no-TTS dwell — nothing else signals "shown to the user").
        scheduleResultDismiss(RESULT_DWELL_NO_TTS_MS);
      } else {
        speakText(mr.message, afterFinalSpeak);
      }
    };

    const missionResult = await runMission(msg, { source: 'voice', contacts: contactsForMission, onAck: onMissionAck });
    logAudioDiag('ORCHESTRATOR_HANDOFF_COMPLETED', `handled=${missionResult.handled} missionId=${missionResult.plan?.id ?? 'none'}`);
    // ROUND_WAKE_COMMAND_HANDOFF_FIX_1 — EXECUTION-stage outcome for a wake-originated command
    // (CASE 3/4). Deliberately scoped to this one call site (the mission/orchestrator action
    // path, which is what "deschide calculatorul" goes through) — not sprinkled across every one
    // of handleIncomingText's other early-return branches (URL/vignette/note/emergency), per
    // instruction not to touch neighboring systems blindly.
    if (wakeOriginated) logAudioDiag('WAKE_COMMAND_RESULT', `handled=${missionResult.handled} missionId=${missionResult.plan?.id ?? 'none'}`);
    if (missionResult.handled) {
      logAudioDiag('ROUTE', 'decision=command reason=mission_orchestrator');
      finishHandledMission(missionResult);
      return;
    }

    // ── Build B — brain as the conversation + intent route ────────────────────────────────────
    // Everything the fast path did not handle. The brain classifies the utterance against the
    // CLOSED KnownAction list and returns speak / clarify / action — it NEVER executes. Only active
    // when a CREIER/Groq key is configured; otherwise brainOut is null and the existing
    // claude/openai/gemini routeCommand path runs unchanged.
    const brainOut = await routeThroughBrain({
      utterance: msg,
      lang: replyLangRef.current,
      history: historyRef.current,
      facts: factsRef.current,
      screenText: SCREEN_READ_PATTERN.test(msg)
        ? (() => { try { const s = getLastScreenSnapshot(); return s ? JSON.stringify(s).slice(0, 4000) : undefined; } catch { return undefined; } })()
        : undefined,
    });
    if (brainOut) {
      if (brainOut.kind === 'action') {
        logAudioDiag('BRAIN_INTENT', `raw="${msg}" kind=action action=${brainOut.action} params=${JSON.stringify(brainOut.params)} confidence=${brainOut.confidence ?? '-'}`);
        const contact = contactParamOf(brainOut.action, brainOut.params);
        if (contact && !sanityCheckContactParam('brain', contact, replyLangRef.current).ok) {
          const q = askWhoToCall(replyLangRef.current, getAddress());
          logAudioDiag('ROUTE', 'decision=clarify reason=bad_contact_param source=brain');
          addMessage('benson', q);
          setLoading(false); loadingRef.current = false;
          speakText(q, () => afterPromptRearm(true));
          return;
        }
        // ROUND_WHATSAPP_REGRESSION_REVERT_1 (2026-09-13, device-log-proven) — resolvePerson()'s
        // fuzzy local-contact matching produced unrelated candidates ("Peter Pane Johannes
        // Günzel" for "Hana"), blocking plain CALL/WRITE commands that worked before today.
        // Per explicit instruction: the new resolver/fuzzy-matching code itself is NOT touched
        // (still intact below, dead), only disabled at this call site. Restores the pre-existing
        // behavior — the raw name the user said becomes the contact param unchanged. Revert:
        // PERSON_RESOLVE_ENABLED = true once the resolver's precision is fixed in its own round.
        const PERSON_RESOLVE_ENABLED = false;
        let effectiveParams = brainOut.params;
        if (PERSON_RESOLVE_ENABLED && brainOut.entities?.person && (brainOut.action === 'call_contact' || brainOut.action === 'send_whatsapp_message')) {
          const [verifiedIdentities, liveContactsForResolve] = await Promise.all([getVerifiedIdentities(), getLiveContacts()]);
          const resolution = resolvePerson({ personRef: brainOut.entities.person, verifiedIdentities, localContacts: liveContactsForResolve });
          logAudioDiag('PERSON_RESOLVE', `type=${brainOut.entities.person.referenceType} surface=${JSON.stringify(brainOut.entities.person.surfaceText)} status=${resolution.status}`);
          if (resolution.status === 'RESOLVED' && resolution.resolved) {
            lastResolvedPersonRef.current = resolution.resolved;
            effectiveParams = { ...brainOut.params, contact: resolution.resolved.verifiedDisplayName, contactName: resolution.resolved.verifiedDisplayName };
          } else if (resolution.status === 'NEEDS_CONFIRMATION' && resolution.candidates && resolution.candidates.length > 0) {
            pendingPersonChoiceRef.current = { action: brainOut.action, params: brainOut.params, candidates: resolution.candidates };
            const q = resolution.candidates.length === 1
              ? `Te referi la ${resolution.candidates[0].verifiedDisplayName}?`
              : `Te referi la ${resolution.candidates.map((c) => c.verifiedDisplayName).join(' sau ')}?`;
            logAudioDiag('ROUTE', 'decision=clarify reason=person_ambiguous source=context_resolver');
            addMessage('benson', q);
            setLoading(false); loadingRef.current = false;
            speakText(q, () => afterPromptRearm(true));
            return;
          }
          // UNRESOLVED — falls through unchanged: the raw surfaceText/contact param is used exactly
          // as before this round (no regression for a genuinely new/unverified name).
        } else if (brainOut.entities?.person?.surfaceText && (brainOut.action === 'call_contact' || brainOut.action === 'send_whatsapp_message') && !contactParamOf(brainOut.action, brainOut.params)) {
          // params.contact is empty but the brain named a person only via entities.person (its
          // OUTPUT_CONTRACT allows this) — with the resolver disabled above, use that raw name
          // directly, same as if the brain had put it in params.contact itself.
          const raw = brainOut.entities.person.surfaceText;
          effectiveParams = { ...brainOut.params, contact: raw, contactName: raw };
        }
        const canonical = buildCanonicalCommand(brainOut.action, effectiveParams, replyLangRef.current);
        logAudioDiag('CANONICAL', `text="${canonical}"`);
        if (canonical) {
          try {
            const pr = parseCommandToActionRequest(canonical, 'voice');
            logAudioDiag('PARSE_RESULT', `problemType=${pr.intent} params=${JSON.stringify(pr.parameters)}`);
          } catch { logAudioDiag('PARSE_RESULT', 'problemType=parse_error params={}'); }
          const contacts2 = MAY_NEED_CONTACTS_PATTERN.test(canonical) ? await getLiveContacts() : contactsForMission;
          setBensonState('EXECUTING', `brain:${brainOut.action}`);
          const bridged = await runMission(canonical, { source: 'voice', contacts: contacts2, onAck: onMissionAck });
          logAudioDiag('ROUTE', `decision=command reason=brain_intent handled=${bridged.handled}`);
          if (bridged.handled) { finishHandledMission(bridged); return; }
          // Brain classified a command the deterministic executor could not run — ask, don't guess.
          setBensonState('ERROR', 'brain_bridge_unhandled');
          const nf = replyLangRef.current.toLowerCase().startsWith('ro')
            ? `N-am putut duce asta la capăt, ${getAddress()}. Poți spune comanda altfel?`
            : replyLangRef.current.toLowerCase().startsWith('de')
            ? `Ich konnte das nicht ausführen, ${getAddress()}. Sag den Befehl bitte anders.`
            : `I couldn't carry that out, ${getAddress()}. Try saying the command differently.`;
          addMessage('benson', nf);
          setLoading(false); loadingRef.current = false;
          speakText(nf, () => afterPromptRearm(true));
          return;
        }
        // canonical === '' -> search_web / set_reminder: the deterministic executor doesn't own
        // those. Fall through to routeCommand() on the ORIGINAL utterance (its search / notepad
        // regex agents handle them). No return here.
        logAudioDiag('PARSE_RESULT', `problemType=delegated params="${msg}"`);
      } else if (brainOut.kind === 'clarify') {
        logAudioDiag('BRAIN_INTENT', `raw="${msg}" kind=clarify action=- params=- confidence=${brainOut.confidence ?? '-'}`);
        logAudioDiag('ROUTE', 'decision=conversation reason=brain_clarify');
        setBensonState('CONFIRMING', 'brain_clarify');
        addMessage('benson', brainOut.question);
        await appendHistory(msg, brainOut.question);
        setLoading(false); loadingRef.current = false;
        speakText(brainOut.question, () => afterPromptRearm(true));
        return;
      } else {
        logAudioDiag('BRAIN_INTENT', `raw="${msg}" kind=speak action=- params=- confidence=-`);
        logAudioDiag('ROUTE', 'decision=conversation reason=brain_speak');
        addMessage('benson', brainOut.text);
        await appendHistory(msg, brainOut.text);
        setLoading(false); loadingRef.current = false;
        speakText(brainOut.text, () => {
          if (noteSpokenAndCheckRepeatLoop(brainOut.text)) {
            if (wakeTriggeredRef.current) { wakeTriggeredRef.current = false; try { hideWakeRing(); } catch {} }
            try { resumePassiveWake(); } catch {}
            return;
          }
          if (convModeRef.current) { doStartListening(); return; }
          if (wakeTriggeredRef.current) {
            wakeTriggeredRef.current = false;
            try { hideWakeRing(); } catch {}
            try { resumePassiveWake(); } catch {}
          }
        });
        return;
      }
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
    // Set from inside the try block below once routeCommand resolves — declared here (not
    // `const result` inside try) so this callback, defined before that value exists, can still
    // read it by closure once it actually fires (after sentenceSpeaker.finish(), which only
    // happens after the assignment below).
    let lastReplyTextForLoopCheck = '';
    const sentenceSpeaker = (convModeRef.current || wakeTriggeredRef.current)
      ? createSentenceSpeaker(() => {
          // See repeatedReplyRef's doc comment — same repeat-fallback-loop breaker as the Mission
          // Orchestrator branch above, applied here to the Claude/OpenAI conversational path,
          // which is where the live-confirmed "I did not quite catch that" loop actually occurs.
          if (noteSpokenAndCheckRepeatLoop(lastReplyTextForLoopCheck)) {
            if (wakeTriggeredRef.current) { wakeTriggeredRef.current = false; try { hideWakeRing(); } catch {} }
            try { resumePassiveWake(); } catch {}
            return;
          }
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
        geminiKey: geminiKeyRef.current,
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
        // Task 5.1 — this is the model-initiated path (the `remember` tool). appendFact refuses it
        // and logs MEMORY_REJECTED reason=model_initiated; only an explicit user "remember that …"
        // (tryStoreFact, REMEMBER_PATTERN) actually writes.
        onRememberFact: (fact) => appendFact(fact, 'model'),
      }, (progress) => addMessage('benson', progress));
      lastReplyTextForLoopCheck = result.reply;

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
        pendingNoteActionRef.current = result.pendingNote; gateArmedAtRef.current = Date.now();
        setBensonState('CONFIRMING', 'note');
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
      const err = conversationFallbackLine(replyLangRef.current, getAddress());
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

              {/* Închide complet — moved here from the main screen's top bar (product-owner-directed
                  2026-08-23: it was easy to miss floating over the medallion). Left in the default
                  red dangerBtn styling (unlike the GOLD-overridden buttons above) since this is the
                  one genuinely destructive action on this screen — stops listening AND sound until
                  explicitly turned back on via the red banner it produces on the main screen. */}
              <TouchableOpacity style={[s.dangerBtn, { marginTop: 10 }]}
                onPress={() => { tap(); toggleSilence(); }}
                accessibilityLabel="Închide complet Benson — oprește ascultarea și sunetul" accessibilityRole="button">
                <Text style={s.dangerTxt}>{silenced ? 'Pornește Benson' : 'Închide complet'}</Text>
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
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder="Gemini key (AIza...) — free, best speech transcription" placeholderTextColor={MUTED}
                value={geminiKey} onChangeText={setGeminiKey} secureTextEntry />
              {/* Groq key (Round 2) — powers the STT section below. Stored via expo-secure-store
                  (settingsStore.saveEngineConfig), the same hardware-backed store as the Picovoice
                  key — never AsyncStorage. Saved by the same SAVE KEYS button as the others. Field
                  blanks after save; the masked hint shows what is currently stored. */}
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder={`Groq API key (gsk_...) — transcriere${savedGroqMasked !== '(none)' ? `  · salvat: ${savedGroqMasked}` : ''}`} placeholderTextColor={MUTED}
                value={groqKey} onChangeText={setGroqKey} secureTextEntry autoCapitalize="none" autoCorrect={false} />
              {/* DEV_STT_DEEPGRAM_1 (2026-09-16) — development-only STT while Groq's quota is
                  exhausted; powers BOTH main-command and confirmation transcription. Groq key
                  above stays the production credential, untouched. */}
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder={`Deepgram key (dev STT)${savedDeepgramMasked !== '(none)' ? `  · salvat: ${savedDeepgramMasked}` : ''}`} placeholderTextColor={MUTED}
                value={deepgramKey} onChangeText={setDeepgramKey} secureTextEntry autoCapitalize="none" autoCorrect={false} />
              <TouchableOpacity style={[s.btn, { marginTop: 10 }]} onPress={() => { tap(); saveApiKeys(); }}
                accessibilityLabel="Save API keys" accessibilityRole="button">
                <Text style={s.btnText}>SAVE KEYS</Text>
              </TouchableOpacity>

              {/* ROUND_WAKE_NATIVE_GENERIC_1 — the ONE authoritative wake-name config. Applied
                  live (no separate save button, not a secret): every change is persisted and
                  pushed to native immediately. Default "Benson". Needs the Groq key above — the
                  native background wake loop transcribes through the same Groq endpoint. */}
              <Text style={s.label}>NUME DE TREZIRE (background)</Text>
              <TextInput style={[s.input, { marginTop: 10 }]} placeholder="Benson" placeholderTextColor={MUTED}
                value={wakeName} onChangeText={changeWakeName} autoCapitalize="words" autoCorrect={false}
                accessibilityLabel="Wake name" />

              {/* STT — which engine transcribes voice. Groq (whisper-large-v3-turbo, cloud, needs
                  the Groq key above) is the default; "Benson local" is the offline on-device
                  Whisper reserve. Writes the same store voiceAgent.ts's transcribeAudio() reads. */}
              <Text style={s.label}>STT</Text>
              <View style={s.row}>
                <TouchableOpacity onPress={() => { tap(); changeSttNucleus('groq'); }}
                  style={[s.chip, sttNucleusId === 'groq' && s.chipActive]}
                  accessibilityLabel="Groq transcription" accessibilityRole="button"
                  accessibilityState={{ selected: sttNucleusId === 'groq' }}>
                  <Text style={[s.chipTxt, sttNucleusId === 'groq' && s.chipTxtActive]}>Groq (implicit)</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => { tap(); changeSttNucleus('local'); }}
                  style={[s.chip, sttNucleusId === 'local' && s.chipActive]}
                  accessibilityLabel="Benson local transcription" accessibilityRole="button"
                  accessibilityState={{ selected: sttNucleusId === 'local' }}>
                  <Text style={[s.chipTxt, sttNucleusId === 'local' && s.chipTxtActive]}>Benson local</Text>
                </TouchableOpacity>
                <TouchableOpacity style={[s.dangerBtn, { borderColor: GOLD, paddingVertical: 6, paddingHorizontal: 12 }]}
                  onPress={() => { tap(); runGroqTest(); }} disabled={groqTesting}
                  accessibilityLabel="Test Groq connection" accessibilityRole="button">
                  <Text style={[s.dangerTxt, { color: GOLD }]}>{groqTesting ? 'TEST…' : 'TEST'}</Text>
                </TouchableOpacity>
              </View>
              {groqTestStatus !== '' && (
                <Text style={[s.factLine, { color: groqTestStatus === 'OK' ? '#4dff88' : '#ff5c5c' }]}>{groqTestStatus}</Text>
              )}

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
                <TouchableOpacity onPress={() => toggleModelProvider('gemini')}
                  style={[s.chip, modelProvider === 'gemini' && s.chipActive]}
                  accessibilityLabel="Gemini chat model" accessibilityRole="button"
                  accessibilityState={{ selected: modelProvider === 'gemini' }}>
                  <Text style={[s.chipTxt, modelProvider === 'gemini' && s.chipTxtActive]}>Gemini</Text>
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
                <TouchableOpacity onPress={() => toggleTtsProvider('gemini')}
                  style={[s.chip, ttsProvider === 'gemini' && s.chipActive]}
                  accessibilityLabel="Gemini voice engine, Kore" accessibilityRole="button"
                  accessibilityState={{ selected: ttsProvider === 'gemini' }}>
                  <Text style={[s.chipTxt, ttsProvider === 'gemini' && s.chipTxtActive]}>Gemini (Kore)</Text>
                </TouchableOpacity>
                <TouchableOpacity style={s.previewBtn} onPress={previewGeminiVoice}
                  accessibilityLabel="Preview Gemini voice" accessibilityRole="button">
                  <Text style={s.previewTxt}>▶</Text>
                </TouchableOpacity>
              </View>
              <Text style={s.factLine}>
                OpenAI/Gemini voices cost per use and need internet; Benson falls back to the device voice automatically if unavailable.
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

              {/* Battery-fix hibernation kill switch (2026-09-18) */}
              <Text style={s.label}>HIBERNARE (ECONOMISIRE BATERIE)</Text>
              <Switch value={hibernationEnabled} onValueChange={toggleHibernation}
                trackColor={{ true: GOLD, false: MUTED }} thumbColor={NAVY}
                accessibilityLabel="Hibernation battery saving" accessibilityRole="switch" />
              <Text style={s.factLine}>
                After 2h with the screen off and the phone untouched, Benson stops listening for the wake word to save
                battery. Wakes up automatically when the phone moves, the screen turns on, or a Clock alarm is about to ring.
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
