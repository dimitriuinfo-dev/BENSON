export declare function startCapture(): Promise<void>;
export declare function stopCapture(): Promise<void>;
export declare function addCaptureEndListener(
  listener: (filePath: string | null, reason: 'vad_silence' | 'max_duration' | 'no_speech' | 'stopped' | 'error' | 'unknown') => void
): { remove: () => void };
export declare function addVolumeChangeListener(
  listener: (level: number) => void
): { remove: () => void };
