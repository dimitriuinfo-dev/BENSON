import { Linking } from 'react-native';
import * as Location from 'expo-location';
import { launchApp as launchInstalledApp } from 'benson-app-registry';
import { logAudioDiag } from 'benson-foreground-service';
import { geocodePlace, fetchWeather, haversineKm } from '../contextEngine';
import { APP_REGISTRY, type AppEntry, type AppCategory } from '../appRegistry';
import { loadApprovedAppIds, loadLastUsedByCategory, recordAppUsed } from '../appLauncherMemory';
import { getAllowedApps } from '../appPermissions';
import { resolveAppQuery, listAppNames } from '../appIndex';
import type { ContentCard } from './contentTypes';

// Navigation-to-destination patterns
// "du[- ]?ma la"/"du[- ]?mă la" — not a literal hyphen, since STT drops the clitic hyphen
// ("du-mă" transcribed as "du mă"), same fix as commandParser.ts's NAVIGATE_PATTERNS.
export const NAV_PATTERN =
  /(?:navigate|navigheaza|navigiere|navigue|naviga|get me to|take me to|du[- ]?ma la|du[- ]?mă la|directions to|fahre zu|emmène-moi à)\s+(?:to\s+)?(.+)/i;

// "pune/deschide/rezervă/găsește/caută/find/search X pe/on/în Y" — a query aimed at a specific
// named app, e.g. "pune Breaking Bad pe Netflix", "rezervă masă italian vineri seara pe TheFork".
// Trigger words use word-stem matching (\w*) instead of enumerating exact suffixes — Romanian verb
// conjugations vary a lot ("caută"/"cauta"/"caute"/"căutăm"...) and an exact-suffix regex missed
// valid phrasings like "să caute" (subjunctive), silently falling through to a worse fallback.
// "find"/"search" added (2026-08-24, confirmed live bug): lib/agents/tools.ts's openApp handler
// templates Claude's query param as literally `find ${query} on ${appName}` — that English "find"
// was never in this trigger list, so EVERY query-prefilled openApp call from Claude's tool loop
// silently matched nothing here and fell through to a plain "open the app" with the search term
// dropped entirely, no matter what Claude asked for. Confirmed live: "open YouTube, [search] Inna"
// opened YouTube only, the search never happened, and nothing here ever surfaced the failure.
const SEARCH_IN_APP_PATTERN =
  /\b(?:play|pune|red[ăa]\w*|joacă|book|rezerv\w*|g[ăa]se\w*|caut\w*|find|search)\b\s+(.+?)\s+(?:on|pe|în|in)\s+(.+)$/i;

// "Find & pre-fill" intents that don't need the app named explicitly — Benson already knows
// which app handles hotels/restaurant tables/parking. Extraction stays lightweight (whatever
// follows the trigger word is passed straight through as the search text) — no date/time parser,
// matching the rest of this codebase's regex-heuristic style rather than real NLU.
const HOTEL_PATTERN      = /\b(?:find|găsește|gaseste)\s+(?:a\s+)?hotel\b\s*(?:in|în)?\s*(.*)/i;
const RESTAURANT_PATTERN = /\b(?:book|rezerv[ăa])\s+(?:a\s+)?(?:table|masă|masa)\b\s*(.*)/i;
const PARKING_PATTERN    = /\b(?:parking|parcare)\b\s*(.*)/i;

// Open-app patterns (generic "open X" — a specific app name, or a category phrase like
// "open something to watch")
export const OPEN_PATTERN =
  /\b(?:open|deschide|öffne|ouvre|launch|porneste|pornește|start)\b\s+(.+)/i;

const CATEGORY_KEYWORDS: [RegExp, AppCategory][] = [
  [/\b(watch|vizion|film|movie|show)\b/i, 'video'],
  [/\b(music|muzic|ascult|listen)\b/i, 'music'],
  [/\b(food|mâncare|mancare|eat|foame|hungry)\b/i, 'food'],
  [/\b(ride|taxi|masina|masină|drive)\b/i, 'transport'],
];

function matchCategory(phrase: string): AppCategory | null {
  for (const [pattern, category] of CATEGORY_KEYWORDS) {
    if (pattern.test(phrase)) return category;
  }
  return null;
}

function findAppByName(name: string): AppEntry | undefined {
  const n = name.toLowerCase().trim();
  return APP_REGISTRY.find(a => n.includes(a.name.toLowerCase()) || a.name.toLowerCase().includes(n));
}

// A curated-registry app is approved if either permission system says so — the older per-app
// toggle in Settings (`approved`/loadApprovedAppIds) OR the newer BENSON 4 App Permissions
// onboarding (dynamic, matched by packageName). Without this OR, an app the user approved through
// the newer, more visible onboarding screen was still silently refused because the curated
// registry's own approval list had never been touched — this was the actual break in "deschide
// WhatsApp doesn't do anything," not the agent/tool-use loop.
async function isApprovedAnywhere(entry: AppEntry, approved: string[]): Promise<boolean> {
  if (approved.includes(entry.id)) return true;
  if (!entry.packageName) return false;
  const dynamic = await getAllowedApps();
  return dynamic.some(a => a.packageName === entry.packageName);
}

// Category ("open something to watch", "muzică", ...) — NEVER auto-picks (directive 4). A choice
// the user already made once (loadLastUsedByCategory) is treated as a settled fact and opened
// directly; otherwise the candidates are proposed. Returns null so the caller can fall through to
// the device-index match when the category has no approved candidates at all.
async function proposeForCategory(
  category: AppCategory, approved: string[], address: string,
): Promise<LauncherResult | null> {
  const candidates = APP_REGISTRY.filter(a => a.category === category && approved.includes(a.id));
  if (!candidates.length) return null;
  const lastUsed = await loadLastUsedByCategory();
  const remembered = candidates.find(a => a.id === lastUsed[category]);
  if (remembered) {
    const opened = await openAppEntry(remembered);
    if (opened) {
      await recordAppUsed(remembered.id, remembered.category);
      logAudioDiag('APP_MATCH', `intent=category:${category} query="" candidates=1 chosen=${JSON.stringify(remembered.name)} asked=false`);
      return { reply: `Opening ${remembered.name}, ${address}.`, appId: remembered.id };
    }
  }
  const shortlist = candidates.slice(0, 3);
  logAudioDiag('APP_MATCH', `intent=category:${category} query="" candidates=${candidates.length} chosen=${JSON.stringify(shortlist.map(a => a.name).join('|'))} asked=true`);
  return {
    reply: shortlist.length === 1
      ? `Am găsit ${shortlist[0].name}. O deschid?`
      : `Am găsit mai multe: ${shortlist.map(a => a.name).join(', ')}. Pe care s-o deschid?`,
  };
}

// Primary resolution: the device's real launcher list (lib/appIndex — fuzzy, diacritic- and
// case-insensitive, over label AND package name). A concrete name opens directly; a close match
// is proposed; nothing similar is stated honestly. Never "nu găsesc aplicația", never a silent
// Play Store redirect.
async function resolveOpenViaIndex(phrase: string, address: string): Promise<LauncherResult> {
  const m = await resolveAppQuery(phrase, 'open');
  if (m.kind === 'exact') {
    try {
      if (launchInstalledApp(m.app.packageName)) return { reply: `Deschid ${m.app.appName}, ${address}.`, appId: m.app.packageName };
    } catch {}
    return { reply: `Am încercat să deschid ${m.app.appName}, dar n-a pornit, ${address}.` };
  }
  if (m.kind === 'single') return { reply: `Am găsit ${m.app.appName}. O deschid?` };
  if (m.kind === 'multiple') return { reply: `Am găsit mai multe: ${listAppNames(m.apps)}. Pe care s-o deschid?` };
  return { reply: `Nu am nicio aplicație instalată care să semene cu ${phrase}, ${address}.` };
}

// Three-tier open: known scheme (or search scheme with a query) → best-effort package-name
// launch via the special 'android-app://' URI (no package-visibility permission needed, the OS
// resolves it) → web/Play Store fallback. Never touches payment or a second factor — it only
// ever opens the app, the user takes it from there.
async function openAppEntry(entry: AppEntry, query?: string): Promise<boolean> {
  const target = query && entry.searchScheme ? `${entry.searchScheme}${encodeURIComponent(query)}` : entry.scheme;
  if (target) {
    const canOpen = await Linking.canOpenURL(target).catch(() => false);
    if (canOpen) { await Linking.openURL(target).catch(() => {}); return true; }
  }
  if (entry.packageName) {
    try {
      if (launchInstalledApp(entry.packageName)) return true;
    } catch {}
  }
  if (entry.fallbackUrl) {
    await Linking.openURL(entry.fallbackUrl).catch(() => {});
    return true;
  }
  return false;
}

export type LauncherResult = { reply: string; card?: ContentCard; appId?: string };

// Opens a specific registry app with a pre-filled search — used by the hotel/restaurant/parking
// triggers. Never anything beyond opening the app: the user reviews and pays there themselves.
async function openWithPrefill(
  appId: string, query: string, address: string, approved: string[],
): Promise<LauncherResult | null> {
  const entry = APP_REGISTRY.find(a => a.id === appId);
  if (!entry) return null;
  if (!(await isApprovedAnywhere(entry, approved))) {
    return { reply: `You haven't allowed me to open ${entry.name} yet, ${address}. Add it in Settings.` };
  }
  const opened = await openAppEntry(entry, query);
  if (!opened) return { reply: `I couldn't open ${entry.name}, ${address}.` };
  await recordAppUsed(entry.id, entry.category);
  return { reply: `Opening ${entry.name} for "${query}", ${address}. You'll review and pay there yourself.`, appId: entry.id };
}

async function tryFindAndPrefill(text: string, address: string, approved: string[]): Promise<LauncherResult | null> {
  const hotel = text.match(HOTEL_PATTERN);
  if (hotel) return openWithPrefill('booking', hotel[1].trim() || 'hotel', address, approved);

  const restaurant = text.match(RESTAURANT_PATTERN);
  if (restaurant) return openWithPrefill('thefork', restaurant[1].trim() || 'restaurant', address, approved);

  const parking = text.match(PARKING_PATTERN);
  if (parking) return openWithPrefill('easypark', parking[1].trim() || 'parking', address, approved);

  return null;
}

// App Launcher Agent — resolves navigation/app-open intents against the curated APP_REGISTRY and
// performs the Linking side effect. Returns null if the text doesn't match any launcher intent.
export async function launchApp(text: string, address: string): Promise<LauncherResult | null> {
  const lower = text.toLowerCase();

  // Navigation — special case, also produces the (static preview) map card. Uses a real
  // turn-by-turn deep link into Waze or Google Maps if the user has approved that specific app
  // (BENSON 4 App Permissions); otherwise falls back to the universal maps.google.com web URL,
  // which works with no app installed.
  const navMatch = lower.match(NAV_PATTERN);
  if (navMatch) {
    const dest = navMatch[1].trim();
    const allowedApps = await getAllowedApps();
    const wazeAllowed  = allowedApps.some(a => a.packageName === 'com.waze');
    const mapsAllowed  = allowedApps.some(a => a.packageName === 'com.google.android.apps.maps');
    if (wazeAllowed) {
      await Linking.openURL(`waze://?q=${encodeURIComponent(dest)}&navigate=yes`).catch(() => {});
    } else if (mapsAllowed) {
      await Linking.openURL(`google.navigation:q=${encodeURIComponent(dest)}`).catch(() => {});
    } else {
      await Linking.openURL(`https://maps.google.com/?q=${encodeURIComponent(dest)}`).catch(() => {});
    }

    let weatherNote = '';
    let card: ContentCard | undefined;
    const coords = await geocodePlace(dest);
    if (coords) {
      const weather = await fetchWeather(coords.latitude, coords.longitude);
      if (weather) weatherNote = ` Weather there: ${weather}.`;

      let distanceKm: number | undefined;
      try {
        const perm = await Location.requestForegroundPermissionsAsync();
        if (perm.granted) {
          const pos = await Location.getCurrentPositionAsync({});
          distanceKm = Math.round(
            haversineKm(pos.coords.latitude, pos.coords.longitude, coords.latitude, coords.longitude)
          );
        }
      } catch {}

      card = { kind: 'map', destination: dest, latitude: coords.latitude, longitude: coords.longitude, distanceKm };
    }
    return { reply: `Navigating to ${dest}, ${address}.${weatherNote}`, card };
  }

  // Phone dialer — universal scheme, not part of the governed registry.
  if (/\b(telefon|phone|dialer|apelator)\b/i.test(lower)) {
    await Linking.openURL('tel:').catch(() => {});
    return { reply: `Opening the phone dialer, ${address}.` };
  }

  const approved = await loadApprovedAppIds();

  const prefillResult = await tryFindAndPrefill(text, address, approved);
  if (prefillResult) return prefillResult;

  // "pune/rezervă/găsește X pe Y" — search inside a named registry app.
  const searchInApp = text.match(SEARCH_IN_APP_PATTERN);
  if (searchInApp) {
    const [, query, appName] = searchInApp;

    // "Google" isn't an app the user opens/governs like the others — it's a plain web search,
    // same trust class as the phone dialer, so no App Permissions gate.
    if (/\bgoogle\b/i.test(appName)) {
      await Linking.openURL(`https://google.com/search?q=${encodeURIComponent(query.trim())}`).catch(() => {});
      return { reply: `Searching Google for "${query.trim()}", ${address}.` };
    }

    const entry = findAppByName(appName);
    if (entry) {
      if (!(await isApprovedAnywhere(entry, approved))) {
        return { reply: `You haven't allowed me to open ${entry.name} yet, ${address}. Add it in Settings.` };
      }
      const opened = await openAppEntry(entry, query.trim());
      if (opened) {
        await recordAppUsed(entry.id, entry.category);
        return { reply: `Opening ${entry.name} for "${query.trim()}", ${address}.`, appId: entry.id };
      }
      // Known registry app, resolution failed at every tier (scheme, package launch, web
      // fallback) — say so honestly instead of falling through to Claude's tool-use loop, which
      // has no real device state and would otherwise improvise a guess (confirmed live: it
      // invented "needs an account configured first" for an app that was actually installed).
      return { reply: `I couldn't open ${entry.name} for "${query.trim()}", ${address}.` };
    }
  }

  // Plain "open X" — concrete app name or a category phrase.
  const openMatch = lower.match(OPEN_PATTERN);
  if (openMatch) {
    const phrase = openMatch[1].trim();

    // Curated registry is now ONLY an alias layer (deep-link schemes / known package). A hit the
    // user has approved opens through the scheme-aware path; every other case — unknown alias,
    // unapproved, or no alias at all — goes to the device index below, never to a dead end.
    const entry = findAppByName(phrase);
    if (entry && (await isApprovedAnywhere(entry, approved))) {
      const opened = await openAppEntry(entry);
      if (opened) {
        await recordAppUsed(entry.id, entry.category);
        return { reply: `Opening ${entry.name} for you, ${address}.`, appId: entry.id };
      }
      return { reply: `I couldn't open ${entry.name}, ${address}.` };
    }

    // Category phrase ("open something to watch", "muzică", ...) — propose, never auto-pick.
    // Falls through to the index when the category has no approved candidate.
    const category = matchCategory(phrase);
    if (category) {
      const proposal = await proposeForCategory(category, approved, address);
      if (proposal) return proposal;
    }

    // Primary path — the device's real launcher list (fuzzy, diacritic-insensitive).
    return resolveOpenViaIndex(phrase, address);
  }

  return null;
}
