// Task 6 — Android's own system TTS via `expo-speech` (native TextToSpeech). Zero cost, fully
// offline, no network.
//
// Accent fix (2026-08-28): passing only `language` to Speech.speak() is not enough. When the
// engine has no voice that exactly matches the requested tag, Android does NOT error — it falls
// back to its default voice, which on this device is German. So Romanian text was being read with
// a German voice. The fix: enumerate the device voices once, and for every utterance pick a voice
// for the active language BY IDENTIFIER and pass `voice: <identifier>` alongside `language`. If
// there is genuinely no voice for the active language, we say so once — in that language — and do
// NOT read the reply with a foreign voice.
import * as Speech from 'expo-speech';
import { logAudioDiag } from 'benson-foreground-service';
import type { TtsVoice } from '../types';

// Full BCP-47 tag per language BENSON speaks — used to match Speech.Voice.language exactly first.
const PREFERRED_TAG: Record<string, string> = {
  ro: 'ro-RO',
  de: 'de-DE',
  en: 'en-GB',
};

// "voice missing" one-liner, spoken once when the active language has no on-device voice.
const NO_VOICE_LINE: Record<string, string> = {
  ro: 'Nu am o voce românească instalată pe acest telefon.',
  de: 'Auf diesem Telefon ist keine deutsche Stimme installiert.',
  en: 'There is no voice for this language installed on this phone.',
};

let voicesCache: Speech.Voice[] | null = null;
let voicesLogged = false;
// Per-language: identifier chosen (''), or explicit null once we've confirmed none exists — so the
// "voice missing" notice is spoken at most once per language per session.
const resolvedVoiceId: Record<string, string | null | undefined> = {};

function shortCode(lang: string): string {
  return (lang || '').split('-')[0].toLowerCase();
}

async function loadVoices(): Promise<Speech.Voice[]> {
  if (voicesCache) return voicesCache;
  voicesCache = await Speech.getAvailableVoicesAsync().catch(() => [] as Speech.Voice[]);
  if (!voicesLogged) {
    voicesLogged = true;
    const count = (code: string) =>
      voicesCache!.filter((v) => shortCode(v.language) === code).length;
    logAudioDiag('TTS_VOICES', `count=${voicesCache.length} ro=${count('ro')} de=${count('de')} en=${count('en')}`);
  }
  return voicesCache;
}

// Call ahead of first use (e.g. app init) so the voice list is enumerated and logged before the
// first reply needs speaking.
export async function preloadAndroidTtsVoices(): Promise<void> {
  await loadVoices();
}

// Returns the chosen voice identifier for a language, or null if the device has none for it.
async function pickVoiceId(lang: string): Promise<string | null> {
  const code = shortCode(lang);
  if (resolvedVoiceId[code] !== undefined) return resolvedVoiceId[code] ?? null;

  const voices = await loadVoices();
  const forLang = voices.filter((v) => shortCode(v.language) === code);
  if (forLang.length === 0) {
    resolvedVoiceId[code] = null;
    return null;
  }
  const tag = PREFERRED_TAG[code];
  const exact = tag ? forLang.filter((v) => v.language === tag) : [];
  const pool = exact.length > 0 ? exact : forLang;
  // Prefer an Enhanced-quality voice when the engine offers one for this language.
  const enhanced = pool.find((v) => v.quality === Speech.VoiceQuality.Enhanced);
  const chosen = (enhanced ?? pool[0]).identifier;
  resolvedVoiceId[code] = chosen;
  return chosen;
}

export async function speakAndroid(text: string, lang: string): Promise<void> {
  const code = shortCode(lang);
  const voiceId = await pickVoiceId(lang);

  if (!voiceId) {
    // No voice for the active language — never read the reply with a foreign voice. Say so once,
    // in the active language (best effort with whatever the engine has), and stop.
    logAudioDiag('TTS_LANG_MISSING', `lang=${lang}`);
    const notice = NO_VOICE_LINE[code] ?? NO_VOICE_LINE.en;
    return new Promise<void>((resolve) => {
      Speech.speak(notice, { language: lang, onDone: () => resolve(), onStopped: () => resolve(), onError: () => resolve() });
    });
  }

  logAudioDiag('TTS_VOICE', `lang=${lang} voice=${voiceId}`);
  return new Promise<void>((resolve, reject) => {
    Speech.speak(text, {
      language: lang,
      voice: voiceId,
      onDone: () => resolve(),
      onStopped: () => resolve(),
      onError: (error: Error) => reject(error),
    });
  });
}

export function createAndroidTtsVoice(): TtsVoice {
  return { id: 'android', speak: speakAndroid };
}

// Settings "TEST" button (Task 7) — actually speaks a short phrase; audible success/failure is
// the most honest test surface TTS has (there's no separate connectivity check for an on-device
// engine).
export async function testAndroidTts(lang: string): Promise<{ ok: true } | { ok: false; error: string }> {
  try {
    await speakAndroid(lang.startsWith('ro') ? 'Test BENSON.' : 'BENSON test.', lang);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}
