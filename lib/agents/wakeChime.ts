import { Audio } from 'expo-av';

// A short, gentle "ding" played the instant BENSON recognizes its wake word ("Benson"), so the
// user gets an immediate, non-verbal confirmation they were heard — before any TTS prompt or the
// command-capture session starts. Uses expo-av (same audio stack as openaiTTS.ts). The clip is a
// tiny bundled asset (assets/sounds/wake.wav, ~11KB) so there's zero network cost and no latency.
let sound: Audio.Sound | null = null;
let loading: Promise<void> | null = null;

async function load(): Promise<void> {
  if (sound) return;
  const { sound: s } = await Audio.Sound.createAsync(
    require('../../assets/sounds/wake.wav'),
    { shouldPlay: false, volume: 1.0 },
  );
  sound = s;
}

// Call ahead of time (e.g. at app init) so the first wake never pays the decode/create cost.
export function preloadWakeChime(): void {
  if (sound || loading) return;
  loading = load().catch(() => { /* fall back to lazy load on first play */ }).finally(() => { loading = null; });
}

// Fire-and-forget: never throws, never blocks the wake handoff. Rewinds so rapid repeats work.
export async function playWakeChime(): Promise<void> {
  try {
    if (!sound) await load();
    if (!sound) return;
    await sound.setPositionAsync(0);
    await sound.playAsync();
  } catch {
    // Audio focus contention / device quirks must never break the wake flow — stay silent.
  }
}
