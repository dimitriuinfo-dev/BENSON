import * as Location from 'expo-location';
import { fetchWithTimeout } from './agents/fetchWithTimeout';

// BUG_INVESTIGATION_1 (2026-09-20, device-proven) — every network call below used to be a plain
// `fetch()` with NO timeout at all. Device log: reverseGeocodePlace's fetch hung for 45+ seconds
// with zero error, zero result — the ONLY thing that eventually ended the turn was the unrelated
// 45s mic-owner-timeout watchdog force-recovering, 45s after the user asked "ce vreme va fi azi".
// This is the exact "no timeout on a cloud fetch" defect class already found and fixed today for
// Groq/Deepgram/the OpenAI brain (see lib/agents/fetchWithTimeout.ts's own history) — reusing that
// SAME proven helper here instead of a second bespoke one. 10s: generous for a plain geocode/
// weather JSON response, short enough that a genuine outage surfaces as an honest "can't reach it"
// reply in seconds, not tens of seconds.
const CONTEXT_FETCH_TIMEOUT_MS = 10000;

export type RoadType = 'city' | 'national' | 'highway';

// Road type from BENSON's own GPS speed reading — no third-party maps API involved.
export function classifyRoadType(speedKmh: number): RoadType {
  if (speedKmh > 100) return 'highway';
  if (speedKmh > 50) return 'national';
  return 'city';
}

export type BorderCrossing = {
  country: string;
  countryCode: string;
  name: string;
  latitude: number;
  longitude: number;
  vignetteUrl: string;
};

// Approximate coordinates of major road border crossings toward common
// vignette-requiring countries. Good enough for a "you're getting close"
// heads-up — not survey-grade geodesy, and not exhaustive.
export const BORDER_CROSSINGS: BorderCrossing[] = [
  { country: 'Austria',     countryCode: 'AT', name: 'Nickelsdorf (HU/AT)',    latitude: 47.9494, longitude: 17.1394, vignetteUrl: 'https://www.asfinag.at' },
  { country: 'Switzerland', countryCode: 'CH', name: 'St. Margrethen (AT/CH)', latitude: 47.4633, longitude: 9.6414,  vignetteUrl: 'https://www.viasuisse.ch' },
  { country: 'Czechia',     countryCode: 'CZ', name: 'Hatě (AT/CZ)',           latitude: 48.7856, longitude: 15.9928, vignetteUrl: 'https://edalnice.cz' },
  { country: 'Hungary',     countryCode: 'HU', name: 'Nădlac (RO/HU)',         latitude: 46.1667, longitude: 20.7500, vignetteUrl: 'https://ematrica.nemzetiutdij.hu' },
  { country: 'Slovenia',    countryCode: 'SI', name: 'Šentilj (AT/SI)',        latitude: 46.6742, longitude: 15.6469, vignetteUrl: 'https://evinjeta.dars.si' },
  { country: 'Bulgaria',    countryCode: 'BG', name: 'Giurgiu–Ruse (RO/BG)',   latitude: 43.8286, longitude: 25.9539, vignetteUrl: 'https://www.bgtoll.bg' },
];

export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function nearestBorder(lat: number, lon: number): { border: BorderCrossing; distanceKm: number } | null {
  let best: { border: BorderCrossing; distanceKm: number } | null = null;
  for (const b of BORDER_CROSSINGS) {
    const d = haversineKm(lat, lon, b.latitude, b.longitude);
    if (!best || d < best.distanceKm) best = { border: b, distanceKm: d };
  }
  return best;
}

export type WeatherData = { tempC: number; description: string; precipitation: number };

// Free, no-key weather lookup.
export async function fetchWeatherData(lat: number, lon: number): Promise<WeatherData | null> {
  try {
    const res = await fetchWithTimeout(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,precipitation`,
      {}, CONTEXT_FETCH_TIMEOUT_MS,
    );
    // FIX_ROUND_2 (2026-09-20, product-owner-directed) — an explicit HTTP-status check: a
    // provider error must never silently fall through as an empty/successful result. `t ===
    // undefined` below already caught this by accident for a JSON error body, but a non-2xx
    // response is now rejected explicitly, before the shape check, so the distinction is real and
    // not incidental.
    if (!res.ok) return null;
    const data = await res.json();
    const t = data.current?.temperature_2m;
    const precip = data.current?.precipitation;
    if (t === undefined) return null;
    return {
      tempC: t,
      precipitation: precip ?? 0,
      description: precip > 0 ? 'with precipitation' : 'clear',
    };
  } catch {
    return null;
  }
}

// Formatted-string variant — used for a destination the user told BENSON about via
// "navigate to X", never for a route read from another app.
export async function fetchWeather(lat: number, lon: number): Promise<string | null> {
  const d = await fetchWeatherData(lat, lon);
  if (!d) return null;
  return `${d.tempC}°C ${d.description}`;
}

// Free, no-key place search via Nominatim (OpenStreetMap) — no device permission needed,
// unlike Location.geocodeAsync which is gated behind Android's location permission.
// Usage policy: identify with a User-Agent, keep to on-demand single lookups (not bulk queries).
export async function geocodePlace(query: string): Promise<{ latitude: number; longitude: number } | null> {
  try {
    const res = await fetchWithTimeout(
      `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(query)}`,
      { headers: { 'User-Agent': 'BENSON-Android/1.0 (voice assistant)' } }, CONTEXT_FETCH_TIMEOUT_MS,
    );
    if (!res.ok) return null;
    const results: { lat: string; lon: string }[] = await res.json();
    if (!results.length) return null;
    return { latitude: parseFloat(results[0].lat), longitude: parseFloat(results[0].lon) };
  } catch {
    return null;
  }
}

// BENSON_GROUNDED_CONVERSATION_1 (2026-09-20) — symmetric reverse of geocodePlace above (same
// provider, same no-key/no-permission Nominatim endpoint), needed so a device-location weather
// answer can name the resolved city back to the user ("vremea la Cluj-Napoca") and so a
// same-conversation follow-up ("și mâine?") has a city name to remember instead of raw coordinates.
export async function reverseGeocodePlace(latitude: number, longitude: number): Promise<string | null> {
  try {
    const res = await fetchWithTimeout(
      `https://nominatim.openstreetmap.org/reverse?format=json&lat=${latitude}&lon=${longitude}&zoom=10`,
      { headers: { 'User-Agent': 'BENSON-Android/1.0 (voice assistant)' } }, CONTEXT_FETCH_TIMEOUT_MS,
    );
    if (!res.ok) return null;
    const data = await res.json();
    const a = data?.address;
    const name = a?.city || a?.town || a?.village || a?.municipality || a?.county;
    return typeof name === 'string' && name.trim() ? name.trim() : null;
  } catch {
    return null;
  }
}

// BENSON_GROUNDED_CONVERSATION_1 — "și mâine?" needs a forecast, not the current-conditions
// endpoint fetchWeatherData already covers. Same Open-Meteo base URL/no-key pattern, extended with
// the `daily` param instead of `current` and forecast_days=2 (today + tomorrow), reading index 1.
export type ForecastData = { tempMaxC: number; tempMinC: number; precipitationMm: number };
export async function fetchTomorrowForecast(lat: number, lon: number): Promise<ForecastData | null> {
  try {
    const res = await fetchWithTimeout(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
      `&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&forecast_days=2&timezone=auto`,
      {}, CONTEXT_FETCH_TIMEOUT_MS,
    );
    if (!res.ok) return null;
    const data = await res.json();
    const tMax = data.daily?.temperature_2m_max?.[1];
    const tMin = data.daily?.temperature_2m_min?.[1];
    const precip = data.daily?.precipitation_sum?.[1];
    if (tMax === undefined || tMin === undefined) return null;
    return { tempMaxC: tMax, tempMinC: tMin, precipitationMm: precip ?? 0 };
  } catch {
    return null;
  }
}

const BORDER_ALERT_KM  = 50;
const BORDER_RESET_KM  = 70;
const BREAK_REMINDER_MS = 2 * 60 * 60 * 1000;

export type ContextWatchHandle = { stop: () => void };

// Drives Car Mode's live context: road type (for reply-length tone), border
// proximity (for vignette alerts), and continuous-drive time (for break reminders).
// Independent of Car Mode auto-detection — runs whenever Car Mode is on, manual or auto.
export async function startContextWatch(params: {
  onRoadTypeChange?: (type: RoadType) => void;
  onBorderAlert?: (border: BorderCrossing, distanceKm: number) => void;
  onBreakReminder?: () => void;
}): Promise<ContextWatchHandle> {
  let lastRoadType: RoadType | null = null;
  let alertedBorderCode: string | null = null;
  const driveStart = Date.now();
  let breakFired = false;

  let locationSub: Location.LocationSubscription | null = null;
  try {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (perm.granted) {
      locationSub = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.Balanced, timeInterval: 10_000, distanceInterval: 50 },
        (loc) => {
          const speedKmh = (loc.coords.speed ?? 0) * 3.6;
          const roadType = classifyRoadType(speedKmh);
          if (roadType !== lastRoadType) {
            lastRoadType = roadType;
            params.onRoadTypeChange?.(roadType);
          }

          const near = nearestBorder(loc.coords.latitude, loc.coords.longitude);
          if (near && near.distanceKm <= BORDER_ALERT_KM) {
            if (alertedBorderCode !== near.border.countryCode) {
              alertedBorderCode = near.border.countryCode;
              params.onBorderAlert?.(near.border, near.distanceKm);
            }
          } else if (near && near.distanceKm > BORDER_RESET_KM) {
            alertedBorderCode = null;
          }

          if (!breakFired && Date.now() - driveStart >= BREAK_REMINDER_MS) {
            breakFired = true;
            params.onBreakReminder?.();
          }
        },
      );
    }
  } catch {}

  return { stop() { locationSub?.remove(); } };
}
