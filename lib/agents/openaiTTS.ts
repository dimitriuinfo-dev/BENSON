import { Audio } from 'expo-av';
import { File, Paths } from 'expo-file-system';
import { setNormalAudioMode } from './audioMode';

let currentSound: Audio.Sound | null = null;
let audioModeSet = false;

// Uses a NON-exclusive (ducking) playback mode — see lib/agents/audioMode.ts for the full rationale.
// Previously this claimed DoNotMix (exclusive AUDIOFOCUS_GAIN), which permanently killed other apps'
// audio system-wide until a phone restart. DuckOthers only briefly lowers other audio while BENSON
// speaks, then releases focus so the other app resumes on its own.
async function ensurePlaybackAudioMode(): Promise<void> {
  if (audioModeSet) return;
  await setNormalAudioMode();
  audioModeSet = true;
}

// OpenAI TTS — optional cloud voice engine (opt-in via Settings). Falls back to
// expo-speech automatically at the call site if this throws (no key / no network).
export async function speakWithOpenAI(
  text: string,
  apiKey: string,
  voice: string,
  onDone?: () => void,
  instructions?: string,
): Promise<void> {
  const res = await fetch('https://api.openai.com/v1/audio/speech', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization:  `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model: 'gpt-4o-mini-tts',
      voice,
      input: text,
      ...(instructions ? { instructions } : {}),
    }),
  });
  if (!res.ok) throw new Error(`OpenAI TTS request failed: ${res.status}`);

  const buffer = await res.arrayBuffer();
  const file = new File(Paths.cache, `benson-tts-${Date.now()}.mp3`);
  file.create();
  file.write(new Uint8Array(buffer));

  await stopOpenAITTS();
  await ensurePlaybackAudioMode();
  const { sound } = await Audio.Sound.createAsync({ uri: file.uri }, { shouldPlay: true });
  currentSound = sound;
  sound.setOnPlaybackStatusUpdate((status) => {
    if (status.isLoaded && status.didJustFinish) {
      if (currentSound === sound) currentSound = null;
      // Awaiting unloadAsync before onDone (2026-07-09): expo-av holds DoNotMix audio focus
      // until the player actually stops/unloads, not just when playback reaches the end. Firing
      // onDone (which triggers the next STT session) before that settles was a real race — the
      // recognizer could start while BENSON's own TTS still held the mic/audio focus, one
      // concrete cause of "no speech detected" right after a wake-word prompt. This makes onDone
      // a genuine completion signal instead of an approximation.
      sound.unloadAsync().catch(() => {}).finally(() => onDone?.());
    }
  });
}

export async function stopOpenAITTS(): Promise<void> {
  if (currentSound) {
    const sound = currentSound;
    currentSound = null;
    try { await sound.stopAsync(); await sound.unloadAsync(); } catch {}
  }
}
