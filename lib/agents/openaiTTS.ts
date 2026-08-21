import { Audio, InterruptionModeAndroid } from 'expo-av';
import { File, Paths } from 'expo-file-system';

let currentSound: Audio.Sound | null = null;
let audioModeSet = false;

// Explicitly claims playback audio focus (DoNotMix) instead of relying on the default —
// audit finding: if the STT recognizer hasn't fully released the mic/AudioRecord yet,
// playback can be ducked to near-silence or fail outright on some OEM audio stacks.
async function ensurePlaybackAudioMode(): Promise<void> {
  if (audioModeSet) return;
  try {
    await Audio.setAudioModeAsync({
      allowsRecordingIOS: false,
      staysActiveInBackground: true,
      interruptionModeAndroid: InterruptionModeAndroid.DoNotMix,
      shouldDuckAndroid: false,
      playThroughEarpieceAndroid: false,
    });
    audioModeSet = true;
  } catch {}
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
