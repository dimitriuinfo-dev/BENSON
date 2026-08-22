import { Audio, InterruptionModeAndroid } from 'expo-av';

// ─────────────────────────────────────────────────────────────────────────────
// System-wide audio-focus safety (critical bug fix, 2026-06)
//
// Symptom: BENSON killed the audio of OTHER apps (video/radio) system-wide — sound cut after a
// fraction of a second and never came back without a full phone restart.
//
// Root cause: the playback audio mode was set to InterruptionModeAndroid.DoNotMix, which makes
// expo-av request AUDIOFOCUS_GAIN — permanent, EXCLUSIVE focus. Combined with
// staysActiveInBackground:true this focus was held for the whole app session (and re-grabbed on
// every wake chime / TTS), permanently starving every other app's playback. Stopping BENSON never
// released it — only killing the process (phone restart) did.
//
// Fix: use DuckOthers (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) — BENSON only briefly lowers other audio
// while it speaks/chimes, then focus is released and the other app's sound resumes on its own. Plus
// an explicit RELEASE mode used when BENSON goes silent/off, to guarantee focus is dropped without a
// restart even if something held it.
// ─────────────────────────────────────────────────────────────────────────────

const NORMAL_MODE = {
  allowsRecordingIOS: false,
  staysActiveInBackground: true, // butler must be able to speak while other apps are foreground
  interruptionModeAndroid: InterruptionModeAndroid.DuckOthers,
  shouldDuckAndroid: true,
  playThroughEarpieceAndroid: false,
};

// Same, but NOT active in background → forces expo-av to abandon audio focus so other apps' audio
// recovers immediately. Used when BENSON is silenced/stopped.
const RELEASED_MODE = {
  ...NORMAL_MODE,
  staysActiveInBackground: false,
};

export async function setNormalAudioMode(): Promise<void> {
  try { await Audio.setAudioModeAsync(NORMAL_MODE); } catch {}
}

export async function releaseAudioFocusMode(): Promise<void> {
  try { await Audio.setAudioModeAsync(RELEASED_MODE); } catch {}
}
