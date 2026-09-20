// BENSON_GROUNDED_CONVERSATION_1 (2026-09-20) — thin adapter over the EXISTING Open-Meteo/
// Nominatim calls in lib/contextEngine.ts. No duplicate HTTP logic, no model choice, no
// independent interpretation of the user's words — this module only fetches data and reports
// it, honestly, with its source and the moment it was fetched. Callers decide what to say.
//
// Data-minimization (product-owner directive, 2026-09-20): coarse location only, read on demand,
// never cached to disk, never logged as raw coordinates, no location history kept anywhere.
import * as Location from 'expo-location';
import { logAudioDiag } from 'benson-foreground-service';
import { fetchWeatherData, fetchTomorrowForecast, geocodePlace, reverseGeocodePlace } from '../contextEngine';

export type WeatherQuery =
  | { kind: 'explicit_city'; city: string }
  | { kind: 'remembered_coords'; place: string; latitude: number; longitude: number }
  | { kind: 'device_location' };

// FIX_ROUND_2 (2026-09-20, product-owner-directed) — `place` is no longer resolved inside
// resolveLocation(): reverse-geocoding a device fix into a city NAME is a courtesy for phrasing
// ("la Cluj-Napoca"), not a dependency the temperature itself should ever wait on. Coordinates are
// the only thing getWeather() needs to proceed; the place name (when it must come from reverse
// geocoding) is fetched IN PARALLEL with the weather data below, never before it.
export type WeatherResolved = {
  place: string | null; // null only for device_location — filled in by getWeather() in parallel
  latitude: number;
  longitude: number;
  locationSource: 'explicit_city' | 'remembered_city' | 'device_last_known' | 'device_fresh_coarse';
};

export type WeatherResult =
  | {
      ok: true;
      place: string;
      latitude: number;
      longitude: number;
      current: { tempC: number; description: string; precipitationMm: number };
      tomorrow?: { tempMaxC: number; tempMinC: number; precipitationMm: number };
      source: 'open-meteo';
      fetchedAt: number;
    }
  | { ok: false; reason: 'city_not_found'; city: string }
  | { ok: false; reason: 'needs_location_permission' }
  | { ok: false; reason: 'needs_city' }
  | { ok: false; reason: 'provider_unavailable' };

// Location.LocationAccuracy.Low — a coarse fix is sufficient for weather-by-city; never requests
// a high-precision GPS lock. Reused verbatim if a fix under this age already exists (no new
// network/GPS activity at all in that case).
const RECENT_LOCATION_MAX_AGE_MS = 15 * 60 * 1000;

// Resolves WHERE to ask about, per the exact priority the product owner specified: explicit city
// named in the utterance > a city remembered from earlier in the SAME conversation ("și mâine?")
// > the device's own recent/coarse location > (caller asks the user for a city; this function
// never guesses one). Never touches AsyncStorage/SecureStore — resolution is entirely in-memory
// for the lifetime of this one call.
async function resolveLocation(query: WeatherQuery): Promise<WeatherResolved | { error: 'city_not_found' | 'needs_location_permission' | 'needs_city' }> {
  // FIX_ROUND_2 (2026-09-20, product-owner-directed) — "și mâine?" now carries the COORDINATES
  // already resolved for the FIRST weather question this conversation, not just the city name:
  // a follow-up no longer re-runs a Nominatim forward-geocode at all (zero network calls here).
  if (query.kind === 'remembered_coords') {
    return { place: query.place, latitude: query.latitude, longitude: query.longitude, locationSource: 'remembered_city' };
  }
  if (query.kind === 'explicit_city') {
    const coords = await geocodePlace(query.city);
    if (!coords) return { error: 'city_not_found' };
    return {
      place: query.city,
      latitude: coords.latitude,
      longitude: coords.longitude,
      locationSource: 'explicit_city',
    };
  }

  // device_location
  // BUG_INVESTIGATION_1 (2026-09-20) — wrapped: requestForegroundPermissionsAsync() can throw
  // when called with no foregrounded Activity (exactly BENSON's normal bubble-only-in-background
  // state, isSelfForeground=false, when a device-location weather query is asked). Previously
  // unguarded here — an uncaught throw at this point propagated out of getWeather()/
  // tryAnswerGrounded() entirely uncaught, skipping past the caller's own error handling and
  // landing in whatever OTHER try/catch happened to still be on the call stack (the legacy
  // routeCommand() path's, producing its unrelated generic fallback line) instead of this
  // provider's own honest needs_location_permission/provider_unavailable messages.
  logAudioDiag('WEATHER_PROVIDER_LOCATION_START', '');
  let granted: boolean;
  try {
    const perm = await Location.getForegroundPermissionsAsync();
    granted = perm.granted;
    if (!granted) {
      const req = await Location.requestForegroundPermissionsAsync();
      granted = req.granted;
    }
  } catch (e) {
    logAudioDiag('WEATHER_PROVIDER_ERROR', `stage=permission error="${String(e)}"`);
    return { error: 'needs_location_permission' };
  }
  logAudioDiag('WEATHER_PROVIDER_PERMISSION', `granted=${granted}`);
  if (!granted) return { error: 'needs_location_permission' };

  let pos: Awaited<ReturnType<typeof Location.getCurrentPositionAsync>> | null;
  let usedLastKnown = false;
  try {
    const recent = await Location.getLastKnownPositionAsync({ maxAge: RECENT_LOCATION_MAX_AGE_MS }).catch(() => null);
    usedLastKnown = recent !== null;
    pos = recent ?? await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low });
    logAudioDiag('WEATHER_PROVIDER_FIX', `source=${usedLastKnown ? 'last_known' : 'fresh_coarse'}`);
  } catch (e) {
    logAudioDiag('WEATHER_PROVIDER_ERROR', `stage=position error="${String(e)}"`);
    pos = null;
  }
  if (!pos) return { error: 'needs_city' };

  // FIX_ROUND_2 — place name resolution moved to getWeather() (parallel with the weather fetch),
  // not here. Coordinates are returned immediately.
  return {
    place: null,
    latitude: pos.coords.latitude,
    longitude: pos.coords.longitude,
    locationSource: usedLastKnown ? 'device_last_known' : 'device_fresh_coarse',
  };
}

// `includeTomorrow` — set when the utterance asks about tomorrow specifically ("și mâine?");
// current conditions are always included so a plain "ce vreme e" never needs a second round trip.
export async function getWeather(query: WeatherQuery, includeTomorrow: boolean): Promise<WeatherResult> {
  logAudioDiag('WEATHER_PROVIDER_START', `queryKind=${query.kind} includeTomorrow=${includeTomorrow}`);
  const resolved = await resolveLocation(query);
  if ('error' in resolved) {
    logAudioDiag('WEATHER_PROVIDER_RESULT', `ok=false reason=${resolved.error}`);
    if (resolved.error === 'city_not_found' && query.kind === 'explicit_city') {
      return { ok: false, reason: 'city_not_found', city: query.city };
    }
    return { ok: false, reason: resolved.error === 'needs_location_permission' ? 'needs_location_permission' : 'needs_city' };
  }

  // FIX_ROUND_2 (2026-09-20) — the temperature and the place NAME are logically independent given
  // coordinates: run them concurrently instead of the previous strict sequence (permission ->
  // position -> reverse-geocode -> THEN weather), which made the courtesy place-name lookup a
  // hard blocking dependency of the number the user actually asked for. Whichever finishes last
  // still bounds the whole call by CONTEXT_FETCH_TIMEOUT_MS (10s) — never the SUM of both.
  const [current, tomorrow, placeName] = await Promise.all([
    fetchWeatherData(resolved.latitude, resolved.longitude),
    includeTomorrow ? fetchTomorrowForecast(resolved.latitude, resolved.longitude) : Promise.resolve(undefined),
    resolved.place !== null ? Promise.resolve(resolved.place) : reverseGeocodePlace(resolved.latitude, resolved.longitude).catch(() => null),
  ]);

  if (!current) {
    logAudioDiag('WEATHER_PROVIDER_RESULT', 'ok=false reason=provider_unavailable stage=current');
    return { ok: false, reason: 'provider_unavailable' };
  }

  const place = placeName ?? 'locația ta';
  logAudioDiag('WEATHER_PROVIDER_RESULT', `ok=true locationSource=${resolved.locationSource} placeResolved=${!!placeName} hasTomorrow=${!!tomorrow}`);

  return {
    ok: true,
    place,
    latitude: resolved.latitude,
    longitude: resolved.longitude,
    current: { tempC: current.tempC, description: current.description, precipitationMm: current.precipitation },
    tomorrow: tomorrow ? { tempMaxC: tomorrow.tempMaxC, tempMinC: tomorrow.tempMinC, precipitationMm: tomorrow.precipitationMm } : undefined,
    source: 'open-meteo',
    fetchedAt: Date.now(),
  };
}
