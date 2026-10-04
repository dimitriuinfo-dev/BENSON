import AsyncStorage from '@react-native-async-storage/async-storage';
import { isCallAudioBlocked, logAudioDiag, nativeWakeSetOwner } from 'benson-foreground-service';
import { updateBubbleStatus } from '../../modules/benson-overlay';
import { speakNow } from '../agents/voiceAgent';

let presentationId = 0;

/** Presents one headless wake result and returns microphone ownership to passive wake. */
export async function presentWakeResult(input: {
  text: string;
  transcript: string;
  lang: string;
  waiting?: boolean;
}): Promise<void> {
  const id = ++presentationId;
  const turnId = Date.now();
  try {
    if (isCallAudioBlocked()) {
      logAudioDiag('WAKE_RESULT_SUPPRESSED', 'reason=call_audio_active');
      return;
    }
    const muted = (await AsyncStorage.getItem('bensonMuted').catch(() => null)) === 'true';
    updateBubbleStatus(input.waiting ? 'confirmare' : 'am înțeles', input.transcript, true, false, turnId);
    if (!muted && input.text.trim()) {
      nativeWakeSetOwner('TTS');
      await new Promise<void>((resolve) => {
        let settled = false;
        let watchdog: ReturnType<typeof setTimeout>;
        const finish = () => {
          if (settled) return;
          settled = true;
          clearTimeout(watchdog);
          resolve();
        };
        watchdog = setTimeout(finish, 12000);
        try {
          speakNow(input.text, { language: input.lang || 'ro-RO', onDone: finish, onStopped: finish, onError: finish });
        } catch { finish(); }
      });
    }
    if (!input.waiting && id === presentationId && !isCallAudioBlocked()) {
      updateBubbleStatus(muted ? 'silențios' : 'gata', input.transcript, true, true, Date.now(), 8000);
    }
  } catch (error) {
    logAudioDiag('WAKE_RESULT_PRESENTATION_ERROR', `name=${(error as Error)?.name ?? 'Error'}`);
  } finally {
    try { if (!isCallAudioBlocked()) nativeWakeSetOwner('WAKE'); } catch {}
  }
}
