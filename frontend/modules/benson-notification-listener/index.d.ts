export declare function isEnabled(): boolean;
export declare function openNotificationListenerSettings(): void;
/** ROUND_WA2_MESSAGE_READING_1 — JSON string of {sender, text, whenMs}[], oldest first, WhatsApp
 *  only, group-summary notifications excluded. "SECURITY_EXCEPTION" if the listener isn't
 *  actually connected right now (isEnabled() can be true while this is still momentarily
 *  false — the OS binder connection lags the Settings toggle). On-demand only — never call this
 *  except in direct response to an explicit user request to be read messages. */
export declare function getWhatsAppNotifications(): string;

/** ROUND_MEDIA_GOVERNANCE_1 — one entry per active OS media session. */
export interface MediaSessionInfo {
  packageName: string;
  /** PlaybackState.STATE_* (0=NONE,1=STOPPED,2=PAUSED,3=PLAYING,6=BUFFERING,7=ERROR,...) */
  state: number;
  /** PlaybackState.ACTIONS_* bitmask (which transport controls this session supports). */
  actions: number;
}
/** JSON-encoded MediaSessionInfo[], or "SECURITY_EXCEPTION" if the listener isn't enabled, or "[]". */
export declare function getActiveMediaSessions(): string;

export type MediaControlAction = 'play' | 'pause' | 'stop' | 'next' | 'previous';
/** packageName: '' = no filter (prefers the actively-playing session, else the first active one). */
export declare function mediaControl(packageName: string, action: MediaControlAction): boolean;

export interface PlaybackStateInfo {
  packageName: string | null;
  /** -1 = no active session, -2 = listener not enabled, otherwise PlaybackState.STATE_*. */
  state: number;
  /** ROUND_SPOTIFY_SELECT_2 — MediaMetadata.METADATA_KEY_TITLE/ARTIST, or null if unavailable. */
  title?: string | null;
  artist?: string | null;
}
/** JSON-encoded PlaybackStateInfo. packageName: '' = no filter. */
export declare function getPlaybackState(packageName: string): string;
