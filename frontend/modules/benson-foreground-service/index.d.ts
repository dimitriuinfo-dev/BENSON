export declare function startListeningService(title: string, body: string): void;
export declare function stopListeningService(): void;
export declare function addStopRequestedListener(
  listener: () => void
): { remove: () => void };
export declare function addListenRequestedListener(
  listener: () => void
): { remove: () => void };
export declare function addWakeWordDetectedListener(
  listener: (commandTail: string) => void
): { remove: () => void };
export declare function bringToForeground(): void;
export declare function isIgnoringBatteryOptimizations(): boolean;
export declare function requestIgnoreBatteryOptimizations(): void;
export declare function pauseHotword(): Promise<void>;
export declare function resumeHotword(): Promise<void>;
export declare function setSystemSoundsMuted(muted: boolean): void;
export declare function consumeRecoveryFlag(): Promise<boolean>;
export declare function getRecoveryEventsLog(): string;
export declare function isAudioDiagnosticsEnabled(): boolean;
export declare function setAudioDiagnosticsEnabled(enabled: boolean): void;
export declare function logAudioDiag(stage: string, fields?: string): void;
export declare function setSttLanguage(lang: string): void;
export declare function setPreferOnDeviceStt(enabled: boolean): void;
export declare function isOnDeviceSttSupported(): boolean;
export declare function setWakeWordEnabled(enabled: boolean): void;
export declare function isWakeWordEnabled(): Promise<boolean> | boolean;
export declare function setPorcupineAccessKey(key: string): void;
export declare function isPorcupineConfigured(): Promise<boolean> | boolean;
export declare function getPorcupineStatus(): Promise<{ hasKey: boolean; hasModel: boolean }> | { hasKey: boolean; hasModel: boolean };
export declare function getActiveWakeEngine(): Promise<string> | string;
