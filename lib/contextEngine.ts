import * as Location from 'expo-location';

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
    const res = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,precipitation`
    );
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

export type SevereWeatherAlert = { severe: boolean; description: string };

// WMO weather codes (Open-Meteo's `weather_code`) severe enough to warn about — thunderstorm,
// heavy/violent rain, freezing rain, heavy snow. Shared by two callers (product-owner-directed
// 2026-09-18): the navigation-destination warning below, and the hibernation danger-wake
// condition — one check, not two separate implementations.
const SEVERE_WEATHER_CODES = new Set([65, 66, 67, 75, 82, 86, 95, 96, 99]);
const SEVERE_WIND_KMH = 60;

function describeSevereWeatherCode(code: number): string {
  if (code === 95 || code === 96 || code === 99) return 'furtună cu descărcări electrice';
  if (code === 65 || code === 82) return 'ploaie torențială';
  if (code === 75 || code === 86) return 'ninsoare abundentă';
  if (code === 66 || code === 67) return 'ploaie înghețată';
  return 'vreme severă';
}

// Free, no-key severe-weather check for one point (lat/lon) — same Open-Meteo endpoint as
// fetchWeatherData, extended with weather_code + wind_speed_10m. Returns severe:false (not null)
// when the lookup succeeded but nothing is severe, so a caller can tell "checked, all clear"
// apart from "the check itself failed" (null).
export async function checkSevereWeather(lat: number, lon: number): Promise<SevereWeatherAlert | null> {
  try {
    const res = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=weather_code,wind_speed_10m`
    );
    const data = await res.json();
    const code: number | undefined = data.current?.weather_code;
    const windKmh: number | undefined = data.current?.wind_speed_10m;
    if (code === undefined) return null;
    const severeCode = SEVERE_WEATHER_CODES.has(code);
    const severeWind = typeof windKmh === 'number' && windKmh >= SEVERE_WIND_KMH;
    if (!severeCode && !severeWind) return { severe: false, description: '' };
    return {
      severe: true,
      description: severeCode ? describeSevereWeatherCode(code) : `vânt puternic (${Math.round(windKmh as number)} km/h)`,
    };
  } catch {
    return null;
  }
}

// Free, no-key place search via Nominatim (OpenStreetMap) — no device permission needed,
// unlike Location.geocodeAsync which is gated behind Android's location permission.
// Usage policy: identify with a User-Agent, keep to on-demand single lookups (not bulk queries).
export async function geocodePlace(query: string): Promise<{ latitude: number; longitude: number } | null> {
  try {
    const res = await fetch(
      `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(query)}`,
      { headers: { 'User-Agent': 'BENSON-Android/1.0 (voice assistant)' } }
    );
    const results: { lat: string; lon: string }[] = await res.json();
    if (!results.length) return null;
    return { latitude: parseFloat(results[0].lat), longitude: parseFloat(results[0].lon) };
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
