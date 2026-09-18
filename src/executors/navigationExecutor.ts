// BENSON Action Engine — Navigation Executor.
// Builds a real turn-by-turn deep link (coordinates or address) into Waze or Google Maps.
// Kept deliberately separate from AppLauncherExecutor (no plain "just open the app" case here —
// every intent this executor handles is about getting somewhere, not just opening an icon).
//
// No text parsing, no contact resolution, no Saved Places / "acasă" alias resolution — those are
// later tasks. This executor only turns already-resolved destination parameters into a URL.

import { openDeepLink } from '../core/action-engine/androidActionExecutor';
import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import { successResult, notFoundResult, failedResult, unsupportedResult } from '../core/action-engine';
import { geocodePlace, checkSevereWeather } from '../../lib/contextEngine';

const LOG_TAG = '[NavigationExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const HANDLED_INTENTS: ActionIntent[] = ['NAVIGATE_TO_PLACE', 'OPEN_WAZE', 'OPEN_GOOGLE_MAPS'];

type PreferredApp = 'waze' | 'google_maps';

type Destination = {
  destinationLabel?: string;
  address?: string;
  latitude?: number;
  longitude?: number;
};

function extractDestination(parameters: Record<string, unknown>): Destination {
  return {
    destinationLabel: typeof parameters.destinationLabel === 'string' ? parameters.destinationLabel : undefined,
    address: typeof parameters.address === 'string' ? parameters.address : undefined,
    latitude: typeof parameters.latitude === 'number' ? parameters.latitude : undefined,
    longitude: typeof parameters.longitude === 'number' ? parameters.longitude : undefined,
  };
}

function hasCoordinates(dest: Destination): dest is Destination & { latitude: number; longitude: number } {
  return typeof dest.latitude === 'number' && typeof dest.longitude === 'number';
}

// Generic pronoun-style destinations that need Saved Places/Context Engine alias resolution
// (Phase B, not built yet) — "acasă" isn't a place name Waze/Maps can look up themselves, unlike
// "Sibiu". Anything not in this short list is assumed to be an actual place name and passed
// through to Waze/Maps' own resolution as-is.
const PRONOUN_DESTINATIONS = ['acasă', 'acasa', 'cabinet', 'birou', 'serviciu', 'muncă', 'munca'];

function isPronounDestination(label: string): boolean {
  return PRONOUN_DESTINATIONS.includes(label.toLowerCase().trim());
}

// Waze/Google Maps both resolve a plain place name themselves via `q=` — no local geocoding
// needed. A destinationLabel with no explicit address behind it yet (Context Engine/saved-place
// resolution is Phase B) is still a perfectly usable query string for them, so it's the fallback
// here rather than being treated as "not usable."
function queryText(dest: Destination): string | undefined {
  return dest.address ?? dest.destinationLabel;
}

// waze://?ll=<lat>,<lng>&navigate=yes  |  https://waze.com/ul?q=<place>&navigate=yes
function buildWazeUrl(dest: Destination): string | null {
  if (hasCoordinates(dest)) return `waze://?ll=${dest.latitude},${dest.longitude}&navigate=yes`;
  const text = queryText(dest);
  if (text) return `https://waze.com/ul?q=${encodeURIComponent(text)}&navigate=yes`;
  return null;
}

// google.navigation:q=<lat>,<lng>  |  google.navigation:q=<place>
function buildGoogleMapsUrl(dest: Destination): string | null {
  if (hasCoordinates(dest)) return `google.navigation:q=${dest.latitude},${dest.longitude}`;
  const text = queryText(dest);
  if (text) return `google.navigation:q=${encodeURIComponent(text)}`;
  return null;
}

// OPEN_WAZE/OPEN_GOOGLE_MAPS pin the app explicitly; NAVIGATE_TO_PLACE reads parameters.preferredApp,
// defaulting to Waze (matching the existing NAV_PATTERN branch's own precedence order).
function resolvePreferredApp(intent: ActionIntent, parameters: Record<string, unknown>): PreferredApp {
  if (intent === 'OPEN_WAZE') return 'waze';
  if (intent === 'OPEN_GOOGLE_MAPS') return 'google_maps';
  return parameters.preferredApp === 'google_maps' ? 'google_maps' : 'waze';
}

// Best-effort destination coordinates for the weather check only — never blocks or fails
// navigation itself, which already opened via the app's own place-name resolution (queryText)
// regardless of whether this succeeds.
async function resolveWeatherCoords(dest: Destination): Promise<{ latitude: number; longitude: number } | null> {
  if (hasCoordinates(dest)) return dest;
  const text = queryText(dest);
  if (!text) return null;
  try { return await geocodePlace(text); } catch { return null; }
}

// Severe-weather warning for the destination (product-owner-directed 2026-09-18) — shares
// checkSevereWeather with the hibernation danger-wake condition (lib/contextEngine.ts). Silent
// (no note appended) if the lookup fails or nothing is severe — never a false alarm, never a
// reason to delay or block the navigation that already started.
async function weatherWarningSuffix(dest: Destination): Promise<string> {
  const coords = await resolveWeatherCoords(dest);
  if (!coords) return '';
  const alert = await checkSevereWeather(coords.latitude, coords.longitude).catch(() => null);
  if (!alert?.severe) return '';
  return ` Atenție: ${alert.description} anunțată la destinație.`;
}

async function openNavigationUrl(requestId: string, appLabel: string, url: string, dest: Destination): Promise<ActionResult> {
  devLog('opening navigation URL', appLabel, url);
  const outcome = await openDeepLink(url);
  if (outcome.success) {
    const weatherNote = await weatherWarningSuffix(dest);
    return successResult(requestId, `Pornesc navigația spre destinație cu ${appLabel}.${weatherNote}`, {
      appOpened: appLabel,
      data: { url },
    });
  }
  return failedResult(requestId, `Could not start navigation in ${appLabel}.`, {
    errorCode: 'LINKING_OPEN_URL_ERROR',
    errorDetails: outcome.error,
  });
}

export const NavigationExecutor: Executor = {
  name: 'NavigationExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    devLog('execute', request.intent, request.parameters);

    if (!HANDLED_INTENTS.includes(request.intent)) {
      return unsupportedResult(request.id, `NavigationExecutor does not handle ${request.intent}.`);
    }

    const preferredApp = resolvePreferredApp(request.intent, request.parameters);
    const dest = extractDestination(request.parameters);
    devLog('preferredApp', preferredApp, 'destination', dest);

    // A pronoun-style destination ("acasă") needs Saved Places/Context Engine alias resolution,
    // which is explicitly out of scope here (Phase B) — anything else with a destinationLabel is
    // assumed to be a real place name and passed straight through to Waze/Maps' own resolution.
    const noUsableDestination =
      !hasCoordinates(dest) && !dest.address && (!dest.destinationLabel || isPronounDestination(dest.destinationLabel));
    if (noUsableDestination) {
      devLog('no usable destination');
      return notFoundResult(
        request.id,
        dest.destinationLabel
          ? `Nu știu încă cum să ajung la "${dest.destinationLabel}" — spune-mi o adresă concretă.`
          : 'Destination missing or unknown.',
      );
    }

    const appLabel = preferredApp === 'waze' ? 'Waze' : 'Google Maps';
    const url = preferredApp === 'waze' ? buildWazeUrl(dest) : buildGoogleMapsUrl(dest);
    devLog('generated URL', url);

    if (!url) return notFoundResult(request.id, 'Destination missing or unknown.');

    return openNavigationUrl(request.id, appLabel, url, dest);
  },
};
