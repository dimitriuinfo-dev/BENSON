// ROUND_MEDIA_GOVERNANCE_1 / ROUND_ENTERTAINMENT_GOVERNANCE_1 — generic playback transport control.
//
// PRIORITY 1: Android's own MediaSession framework (benson-notification-listener's mediaControl/
// getPlaybackState) — deterministic, provider-agnostic, no chrome-noise/auto-hide-label problems.
// PRIORITY 3: Accessibility, only when no MediaSession exists or the action isn't supported.
// (PRIORITY 2 — a provider-specific deep link/API per action — is not implemented: none of the
// installed providers this round had a documented deterministic play/pause/stop deep link, and
// MediaSession already covers this reliably wherever a session exists — see the report.)
//
// This file knows nothing about "YouTube" or "Spotify" specifically — it operates on packageName
// strings handed to it by the orchestrator/search executors. Provider-specific search/select code
// lives in mediaSearchExecutor.ts / youtubeExecutor.ts, never here.

import { getActiveMediaSessions, mediaControl, getPlaybackState } from 'benson-notification-listener';
import { executeCommand } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';
import { bringBensonToForeground, waitForPackageForeground } from '../core/action-engine/androidActionExecutor';

const BENSON_PACKAGE = 'com.benson.butler';

// PlaybackState.STATE_* (android.media.session.PlaybackState) — only the ones this file checks.
export const STATE_NONE = 0;
export const STATE_STOPPED = 1;
export const STATE_PAUSED = 2;
export const STATE_PLAYING = 3;
export const STATE_BUFFERING = 6;
// Sentinels from the native layer (not real PlaybackState values):
export const STATE_NO_SESSION = -1;
export const STATE_LISTENER_DISABLED = -2;

async function nativeWait(ms: number): Promise<void> {
  try {
    await executeCommand({ steps: [{ action: 'wait', ms }] } as any);
  } catch {
    /* best-effort settle delay only */
  }
}

export function readPlaybackState(packageName?: string): { packageName: string | null; state: number } {
  try {
    return JSON.parse(getPlaybackState(packageName ?? ''));
  } catch {
    return { packageName: null, state: STATE_NO_SESSION };
  }
}

export function hasUsableMediaSession(packageName?: string): boolean {
  const s = readPlaybackState(packageName);
  return s.state !== STATE_NO_SESSION && s.state !== STATE_LISTENER_DISABLED;
}

// Multi-language accessibility fallback — same textContainsAny idiom as the YouTube search-icon
// match, generalized to transport controls. "Do not depend only on visible text" is honored by
// ALSO requiring clickable:true (a semantic/role signal), not a coordinate; a true icon-only
// button with no contentDescription at all is not reachable this way — that gap is disclosed in
// the report rather than papered over with a coordinate tap.
const ACCESSIBILITY_LABELS: Record<MediaAction, string[]> = {
  play: ['play', 'reda', 'redare', 'continua', 'continuă', 'continuare', 'abspielen', 'wiedergabe', 'fortsetzen', 'resume'],
  pause: ['pause', 'pauza', 'pauză', 'anhalten'],
  stop: ['stop', 'opreste', 'oprește', 'beenden'],
  next: ['next', 'urmatoarea', 'următoarea', 'weiter', 'nächster', 'nächstes'],
  previous: ['previous', 'anterioara', 'anterioară', 'zurück', 'vorheriger', 'voriges'],
};

export type MediaAction = 'play' | 'pause' | 'stop' | 'next' | 'previous';

async function accessibilityControl(action: MediaAction): Promise<boolean> {
  const hints = ACCESSIBILITY_LABELS[action];
  try {
    const r = (await executeCommand({
      steps: [{ action: 'click', match: { textContainsAny: hints, clickable: true }, timeoutMs: 2500 }],
    } as any)) as { success?: boolean };
    return r?.success === true;
  } catch {
    return false;
  }
}

// The one place every transport action goes through. Tries MediaSession first (Priority 1); a
// provider-specific deep link (Priority 2) is not implemented this round (see file header); falls
// back to Accessibility (Priority 3) only if no MediaSession action worked.
// ROUND_GENERIC_VISIBLE_ACTION_1 — exported so a generic "apasă <label>" command (missionOrchestrator.ts)
// can reuse the exact same MediaSession-first/accessibility-fallback dispatch without duplicating it.
export async function mediaAct(action: MediaAction, packageName?: string): Promise<boolean> {
  let ok = false;
  try {
    ok = mediaControl(packageName ?? '', action);
  } catch {
    ok = false;
  }
  logAudioDiag('MEDIA_ACT', `action=${action} package=${JSON.stringify(packageName ?? '')} mechanism=media_session success=${ok}`);
  if (ok) return true;
  const fallback = await accessibilityControl(action);
  logAudioDiag('MEDIA_ACT', `action=${action} package=${JSON.stringify(packageName ?? '')} mechanism=accessibility success=${fallback}`);
  return fallback;
}

export async function mediaPlay(packageName?: string): Promise<boolean> {
  return mediaAct('play', packageName);
}
export async function mediaResume(packageName?: string): Promise<boolean> {
  return mediaAct('play', packageName);
}
export async function mediaPause(packageName?: string): Promise<boolean> {
  return mediaAct('pause', packageName);
}
export async function mediaNext(packageName?: string): Promise<boolean> {
  return mediaAct('next', packageName);
}
export async function mediaPrevious(packageName?: string): Promise<boolean> {
  return mediaAct('previous', packageName);
}

// android.media.session.PlaybackState.ACTION_STOP — a documented, stable public constant
// (1 << 3), safe to hardcode: this is an OS API constant, not a magic number we invented.
const ACTION_STOP_BIT = 8;

async function verifyState(expected: number[], packageName: string | undefined, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const s = readPlaybackState(packageName);
    if (expected.includes(s.state)) return true;
    if (Date.now() >= deadline) return false;
    await nativeWait(300);
  }
}

export async function verifyPlaying(packageName?: string, timeoutMs = 3000): Promise<boolean> {
  return verifyState([STATE_PLAYING, STATE_BUFFERING], packageName, timeoutMs);
}
export async function verifyPaused(packageName?: string, timeoutMs = 3000): Promise<boolean> {
  return verifyState([STATE_PAUSED], packageName, timeoutMs);
}
export async function verifyStopped(packageName?: string, timeoutMs = 3000): Promise<boolean> {
  return verifyState([STATE_STOPPED, STATE_NONE, STATE_NO_SESSION], packageName, timeoutMs);
}

// CONFIRMED LIVE (2026-09-12) — verifyState()'s loop returns the instant it finds a MATCHING
// state, which is exactly right for "wait for X to become true" (verifyPlaying/verifyPaused/
// verifyStopped). Naively inverting it (`!await verifyPlaying(...)`) to mean "confirm playback
// STOPPED" is wrong: the very first read, taken milliseconds after issuing pause/stop, can still
// report the stale pre-action state (a real device run did exactly this — a `pause` call reported
// success, but the immediate readback still said PLAYING, so `!verifyPlaying` returned false
// instantly and cascaded into an unnecessary `stop` fallback). This dedicated check polls for the
// ABSENCE of PLAYING/BUFFERING and only concludes "still playing" after genuinely exhausting the
// timeout — the correct polarity for a negative condition.
async function verifyNotPlaying(packageName: string | undefined, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  await nativeWait(250); // let a just-issued transport command's state change propagate at all
  for (;;) {
    const s = readPlaybackState(packageName);
    if (s.state !== STATE_PLAYING && s.state !== STATE_BUFFERING) return true;
    if (Date.now() >= deadline) return false;
    await nativeWait(300);
  }
}

export interface MediaStopOutcome {
  ok: boolean;
  mechanism: 'stop' | 'pause';
  message: string;
}

// STOP semantics (per the round's explicit rule): prefer a true STOP if the active session's own
// PlaybackState.actions bitmask advertises it supports one; otherwise PAUSE. Either way, VERIFY
// the media is actually no longer playing before reporting anything — never say "oprit" merely
// because a click/transport call was accepted.
export async function stopMedia(packageName?: string): Promise<MediaStopOutcome> {
  const before = readPlaybackState(packageName);
  let actionsBitmask = 0;
  try {
    const sessions = JSON.parse(getActiveMediaSessions()) as Array<{ packageName: string; actions?: number }>;
    const match = packageName
      ? sessions.find((s) => s.packageName === packageName)
      : sessions.find((s) => s.packageName === before.packageName) ?? sessions[0];
    actionsBitmask = match?.actions ?? 0;
  } catch {
    actionsBitmask = 0;
  }
  const canStop = (actionsBitmask & ACTION_STOP_BIT) !== 0;
  const mechanism: 'stop' | 'pause' = canStop ? 'stop' : 'pause';
  await mediaAct(mechanism, packageName);
  // "Media is no longer playing" (the round's own verification bar) means NOT PLAYING/BUFFERING —
  // a PAUSE fallback landing on STATE_PAUSED is a legitimate, honest outcome, not a failure. A
  // narrower "must reach STOPPED/NONE" check (verifyStopped()) would falsely fail every provider
  // (like YouTube — confirmed live: its session's actions bitmask never advertises ACTION_STOP)
  // that only ever pauses. The message text (not this check) is what keeps "oprit" vs "pauză"
  // honest — see below.
  const noLongerPlaying = await verifyNotPlaying(packageName, 3000);
  if (noLongerPlaying) {
    return { ok: true, mechanism, message: mechanism === 'stop' ? 'Am oprit.' : 'Am pus pauză (aplicația nu are o oprire completă).' };
  }
  // Fall back the other way once before giving up honestly.
  const otherMechanism: 'stop' | 'pause' = mechanism === 'stop' ? 'pause' : 'stop';
  await mediaAct(otherMechanism, packageName);
  const stoppedAfterFallback = await verifyNotPlaying(packageName, 2500);
  return {
    ok: stoppedAfterFallback,
    mechanism: otherMechanism,
    message: stoppedAfterFallback
      ? (otherMechanism === 'stop' ? 'Am oprit.' : 'Am pus pauză.')
      : 'Am încercat să opresc redarea, dar nu pot confirma că s-a oprit.',
  };
}

export interface ReturnToBensonOutcome {
  ok: boolean;
  message: string;
}

// "oprește și revino la Benson" — stop/pause verified, THEN bring BENSON forward, THEN verify
// BENSON is actually foreground. Reuses the exact same bringBensonToForeground/
// waitForPackageForeground pair AppLauncherExecutor's RETURN_TO_BENSON already uses successfully
// today — no new foreground-bringing mechanism invented for media. Resuming listening afterward
// is not this function's job: the normal mission-completion path (already existing in
// app/index.tsx) does that once this function's message is spoken, same as any other mission.
export async function returnToBensonFromMedia(packageName?: string): Promise<ReturnToBensonOutcome> {
  const stopOutcome = await stopMedia(packageName);
  const outcome = bringBensonToForeground();
  if (!outcome.success) {
    return { ok: false, message: `${stopOutcome.message} Nu am putut reveni în Benson.` };
  }
  const fg = await waitForPackageForeground(BENSON_PACKAGE, 3000);
  if (!fg.reached) {
    return { ok: false, message: `${stopOutcome.message} Am încercat să revin în Benson, dar nu pot confirma.` };
  }
  return { ok: true, message: `${stopOutcome.message} Am revenit.` };
}
