import * as Location from 'expo-location';
import { geocodePlace, fetchWeatherData, fetchWeatherForecast, describeWeatherCode } from '../contextEngine';
import type { ContentCard } from './contentTypes';

export const WEATHER_PATTERN = /\b(?:weather|vreme|vremea|wetter|météo|meteo)\b/i;

// Optional trailing place name — "weather in Paris", "vremea la Cluj", "wetter in Berlin".
const PLACE_PATTERN = /(?:\bin\b|\bat\b|\bla\b|\bîn\b|\bà\b)\s+([a-zA-ZăâîșțĂÂÎȘȚ\s\-]+?)\s*[?.!]*$/i;

// Day-offset detection (product-owner-directed 2026-09-18, real bug: this agent used to ALWAYS
// answer with right-now conditions, silently ignoring "mâine"/"tomorrow" in the phrase). 0=today.
const TOMORROW_PATTERN = /\b(mâine|maine|tomorrow|morgen)\b/i;
const DAY_AFTER_TOMORROW_PATTERN = /\b(poimâine|poimaine|day after tomorrow|übermorgen)\b/i;
const IN_N_DAYS_PATTERN = /\b(?:peste|în|in)\s+(\d+)\s*(?:zile|days|tagen?)\b/i;

// Multi-day forecast request — "următoarele 5 zile", "next 5 days", bare "5 zile" mention.
const NEXT_N_DAYS_PATTERN = /\b(?:următoarele|urmatoarele|next|nächsten)?\s*(\d+)\s*(?:zile|days|tage)\b/i;

function detectDayOffset(text: string): number {
  if (DAY_AFTER_TOMORROW_PATTERN.test(text)) return 2;
  if (TOMORROW_PATTERN.test(text)) return 1;
  const inN = text.match(IN_N_DAYS_PATTERN);
  if (inN) return Math.max(0, parseInt(inN[1], 10));
  return 0;
}

function detectMultiDayCount(text: string): number | null {
  const m = text.match(NEXT_N_DAYS_PATTERN);
  if (!m) return null;
  const n = parseInt(m[1], 10);
  return n >= 2 ? Math.min(n, 7) : null; // a single "1 day" mention isn't a forecast request
}

function localizedReply(lang: string, parts: { ro: string; de: string; en: string }): string {
  const l = (lang || '').toLowerCase();
  if (l.startsWith('ro')) return parts.ro;
  if (l.startsWith('de')) return parts.de;
  return parts.en;
}

function formatDayLabel(dateISO: string, dayOffset: number, lang: string): string {
  if (dayOffset === 0) return localizedReply(lang, { ro: 'azi', de: 'heute', en: 'today' });
  if (dayOffset === 1) return localizedReply(lang, { ro: 'mâine', de: 'morgen', en: 'tomorrow' });
  const d = new Date(dateISO);
  return new Intl.DateTimeFormat(lang || undefined, { weekday: 'long', day: 'numeric', month: 'long' }).format(d);
}

// Weather Agent — free, no-key open-meteo lookup for a named place or the user's current GPS fix.
// Supports right-now conditions, a specific future day, or a multi-day forecast — all localized
// to `lang` (previously hardcoded English regardless of the user's language, confirmed a real bug).
export async function runWeatherAgent(
  text: string,
  address: string,
  lang: string,
): Promise<{ reply: string; card?: ContentCard }> {
  const placeMatch = text.match(PLACE_PATTERN);
  const placeName = placeMatch ? placeMatch[1].trim() : null;

  let lat: number;
  let lon: number;
  let place: string;

  if (placeName) {
    const coords = await geocodePlace(placeName);
    if (!coords) {
      return { reply: localizedReply(lang, {
        ro: `Nu am găsit ${placeName}, ${address}.`,
        de: `Ich konnte ${placeName} nicht finden, ${address}.`,
        en: `I couldn't find ${placeName}, ${address}.`,
      }) };
    }
    lat = coords.latitude;
    lon = coords.longitude;
    place = placeName;
  } else {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (!perm.granted) {
      return { reply: localizedReply(lang, {
        ro: `Am nevoie de acces la locație ca să verific vremea, ${address}.`,
        de: `Ich brauche Standortzugriff, um das Wetter zu prüfen, ${address}.`,
        en: `I need location access to check the weather, ${address}.`,
      }) };
    }
    const pos = await Location.getCurrentPositionAsync({});
    lat = pos.coords.latitude;
    lon = pos.coords.longitude;
    place = localizedReply(lang, { ro: 'locația ta', de: 'deinem Standort', en: 'your location' });
  }

  const multiDayCount = detectMultiDayCount(text);
  if (multiDayCount) {
    const forecast = await fetchWeatherForecast(lat, lon, multiDayCount);
    if (!forecast?.length) {
      return { reply: localizedReply(lang, {
        ro: `Nu am putut obține prognoza acum, ${address}.`,
        de: `Ich konnte die Vorhersage gerade nicht abrufen, ${address}.`,
        en: `I couldn't get the forecast right now, ${address}.`,
      }) };
    }
    const lines = forecast.map((d, i) =>
      `${formatDayLabel(d.dateISO, i, lang)}: ${Math.round(d.tempMinC)}–${Math.round(d.tempMaxC)}°C, ${describeWeatherCode(d.code, lang)}`
    );
    return {
      reply: localizedReply(lang, {
        ro: `Prognoza pentru ${place}: ${lines.join('; ')}.`,
        de: `Vorhersage für ${place}: ${lines.join('; ')}.`,
        en: `Forecast for ${place}: ${lines.join('; ')}.`,
      }),
    };
  }

  const dayOffset = detectDayOffset(text);
  if (dayOffset === 0) {
    const weather = await fetchWeatherData(lat, lon);
    if (!weather) {
      return { reply: localizedReply(lang, {
        ro: `Nu am putut obține vremea acum, ${address}.`,
        de: `Ich konnte das Wetter gerade nicht abrufen, ${address}.`,
        en: `I couldn't get the weather right now, ${address}.`,
      }) };
    }
    return {
      reply: localizedReply(lang, {
        ro: `Sunt ${weather.tempC}°C ${placeName ? `în ${place}` : 'acum'}, ${weather.description === 'with precipitation' ? 'cu precipitații' : 'senin'}, ${address}.`,
        de: `Es sind ${weather.tempC}°C ${placeName ? `in ${place}` : 'gerade'}, ${address}.`,
        en: `It's ${weather.tempC}°C and ${weather.description} ${placeName ? `in ${place}` : 'right now'}, ${address}.`,
      }),
      card: {
        kind: 'weather',
        place,
        tempC: weather.tempC,
        description: weather.description,
        precipitation: weather.precipitation,
      },
    };
  }

  const forecast = await fetchWeatherForecast(lat, lon, dayOffset + 1);
  const day = forecast?.[dayOffset];
  if (!day) {
    return { reply: localizedReply(lang, {
      ro: `Nu am putut obține prognoza acum, ${address}.`,
      de: `Ich konnte die Vorhersage gerade nicht abrufen, ${address}.`,
      en: `I couldn't get the forecast right now, ${address}.`,
    }) };
  }
  const dayLabel = formatDayLabel(day.dateISO, dayOffset, lang);
  return {
    reply: localizedReply(lang, {
      ro: `${dayLabel.charAt(0).toUpperCase() + dayLabel.slice(1)} în ${place}: între ${Math.round(day.tempMinC)} și ${Math.round(day.tempMaxC)}°C, ${describeWeatherCode(day.code, lang)}, ${address}.`,
      de: `${dayLabel} in ${place}: zwischen ${Math.round(day.tempMinC)} und ${Math.round(day.tempMaxC)}°C, ${describeWeatherCode(day.code, lang)}, ${address}.`,
      en: `${dayLabel} in ${place}: between ${Math.round(day.tempMinC)} and ${Math.round(day.tempMaxC)}°C, ${describeWeatherCode(day.code, lang)}, ${address}.`,
    }),
    card: {
      kind: 'weather',
      place,
      tempC: day.tempMaxC,
      description: describeWeatherCode(day.code, lang),
      precipitation: day.precipitationSum,
    },
  };
}
