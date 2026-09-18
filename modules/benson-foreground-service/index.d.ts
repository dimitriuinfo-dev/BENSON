export declare function startListeningService(title: string, body: string): void;
export declare function stopListeningService(): void;
/** WAKE HEALTH — re-issue the ongoing notification with an honest body. No-op if the service
 *  isn't running; does not restart the service or hotword loop. */
export declare function updateNotification(title: string, body: string): void;
export declare function addStopRequestedListener(
  listener: () => void
): { remove: () => void };
export declare function addListenRequestedListener(
  listener: () => void
): { remove: () => void };
export declare function addWakeWordDetectedListener(
  listener: (commandTail: string) => void
): { remove: () => void };
/** ROUND_WAKE_NATIVE_TO_JS_ACK_1 — atomic take of a durable pending wake command; null = none pending. */
export declare function takePendingWakeCommand(): string | null;
/** ROUND_WAKE_STATE_BUG_1 — native heartbeat event (~3 s), executes while backgrounded; re-arm hook. */
export declare function addWakePokeListener(
  listener: () => void
): { remove: () => void };
/** ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native mic-ownership handoff for the on-device wake engine. */
export declare function nativeWakeSetOwner(
  owner: 'WAKE' | 'COMMAND_STT' | 'TTS' | 'CALL' | 'NONE'
): void;
export declare function isNativeWakeAvailable(): { model: boolean; cloud: boolean; running: boolean };
/** ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — native Handler timer; survives JS suspension while backgrounded. */
export declare function armSttSessionWatchdog(sessionId: string, timeoutMs: number): void;
export declare function cancelSttSessionWatchdog(sessionId: string): void;
export declare function addSttWatchdogTimeoutListener(
  listener: (sessionId: string) => void
): { remove: () => void };
/** ROUND_TTS_WATCHDOG_NATIVE_1 — native Handler timer for the TTS mic-ownership hard bound; survives JS suspension while backgrounded. */
export declare function armTtsWatchdog(timeoutMs: number): void;
export declare function cancelTtsWatchdog(): void;
export declare function addTtsWatchdogTimeoutListener(
  listener: () => void
): { remove: () => void };
/** URGENT_CONFIRMATION_NATIVE_1 — native one-shot YES/NO/UNKNOWN capture; survives backgrounding. */
export declare function startConfirmationListening(confirmationId: string, timeoutMs: number): void;
export declare function cancelConfirmationListening(confirmationId: string): void;
export declare function addConfirmationResultListener(
  listener: (confirmationId: string, verdict: 'YES' | 'NO' | 'UNKNOWN' | 'TIMEOUT', transcript: string) => void
): { remove: () => void };
/** ROUND_WAKE_NATIVE_GENERIC_1 — ONE authoritative wake-name config, native-persisted. Default "Benson". */
export declare function setWakeName(name: string): void;
export declare function getWakeName(): string | Promise<string>;
/** Pushes the active STT provider's credentials to the native cloud wake loop. Never logged. */
export declare function setNativeWakeCredentials(apiKey: string, baseUrl: string, model: string): void;
/** DEV_STT_DEEPGRAM_1 — separate Deepgram key push for the native confirmation listener only; never touches the wake loop's Groq credentials above. Never logged. */
export declare function setConfirmationSttCredentials(apiKey: string): void;
export declare function isNativeCloudWakeConfigured(): boolean | Promise<boolean>;
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
/** Battery-fix hibernation kill switch (2026-09-18) — default OFF until proven on device. */
export declare function setHibernationEnabled(enabled: boolean): void;
export declare function isHibernationEnabled(): Promise<boolean> | boolean;
/** Live state (not the toggle) — true only while actually hibernating right now. */
export declare function isHibernating(): Promise<boolean> | boolean;
/** JS-driven exit (severe-weather danger check). Same effect as the native motion/screen-on triggers. */
export declare function wakeFromHibernation(reason: string): void;
export declare function setPorcupineAccessKey(key: string): void;
export declare function isPorcupineConfigured(): Promise<boolean> | boolean;
export declare function getPorcupineStatus(): Promise<{ hasKey: boolean; hasModel: boolean }> | { hasKey: boolean; hasModel: boolean };
export declare function getActiveWakeEngine(): Promise<string> | string;
