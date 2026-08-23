// BENSON Mission Governance — Waze Tool Layer.
// The ONLY place allowed to call Linking for Waze. Nothing outside this file may open a Waze
// deep link, a google.navigation: intent, or the browser-search fallback for a navigation
// request — src/core/mission/missionExecutor.ts is the only caller.
//
// Browser is not a BENSON capability in this phase (governance clarification 1) — the browser
// URL below exists solely as the final technical fallback INSIDE this tool's own cascade, never
// as a separate tool, command, or mission type.

import { Linking } from 'react-native';
import { isPackageInstalled } from '../../action-engine/androidActionExecutor';
import { waitForBackground } from '../appStateSignal';
import type { ToolCallResult } from '../missionTypes';

const LOG_TAG = '[WazeTool]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const WAZE_PACKAGE = 'com.waze';

// Package-identity check (see whatsappTool.ts for why this replaced Linking.canOpenURL) — kept
// informational-only here since the openNavigation/openSearch cascade below already falls back
// to a browser URL regardless of what this reports.
export async function isInstalled(): Promise<boolean> {
  return isPackageInstalled(WAZE_PACKAGE);
}

async function tryOpen(url: string): Promise<boolean> {
  try {
    await Linking.openURL(url);
    return true;
  } catch (err) {
    devLog('open threw', url, err instanceof Error ? err.message : err);
    return false;
  }
}

// launch_requested the instant openURL resolves without throwing — an accepted intent is the
// only claim BENSON can make on its own (governance clarification 2). AppState confirmation only
// ever UPGRADES this to app_switch_observed; its absence never downgrades to launch_failed.
async function attemptAndConfirm(via: string, url: string): Promise<ToolCallResult | null> {
  const accepted = await tryOpen(url);
  if (!accepted) return null;
  const wentBackground = await waitForBackground(3000);
  devLog('accepted', via, url, 'app_switch_observed=', wentBackground);
  return { outcome: wentBackground ? 'app_switch_observed' : 'launch_requested', via };
}

async function cascade(destination: string): Promise<ToolCallResult> {
  const enc = encodeURIComponent(destination);
  const attempts: { via: string; url: string }[] = [
    { via: 'waze_app', url: `waze://?q=${enc}&navigate=yes` },
    { via: 'waze_web', url: `https://waze.com/ul?q=${enc}&navigate=yes` },
    { via: 'google_navigation', url: `google.navigation:q=${enc}` },
    // Final technical fallback only — not a general browser capability.
    { via: 'browser_fallback', url: `https://www.google.com/maps/search/?api=1&query=${enc}` },
  ];

  for (const attempt of attempts) {
    const result = await attemptAndConfirm(attempt.via, attempt.url);
    if (result) return result;
  }
  return { outcome: 'launch_failed', error: 'All navigation fallbacks failed to launch.' };
}

export async function openNavigation(destination: string): Promise<ToolCallResult> {
  return cascade(destination);
}

export async function openSearch(query: string): Promise<ToolCallResult> {
  return cascade(query);
}

export async function openApp(): Promise<ToolCallResult> {
  const result = await attemptAndConfirm('waze_app', 'waze://');
  return result ?? { outcome: 'launch_failed', error: 'Waze is not installed.' };
}
