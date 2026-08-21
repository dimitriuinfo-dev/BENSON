import * as Location from 'expo-location';
import { geocodePlace, fetchWeatherData } from '../contextEngine';
import type { ContentCard } from './contentTypes';

export const WEATHER_PATTERN = /\b(?:weather|vreme|vremea|wetter|météo|meteo)\b/i;

// Optional trailing place name — "weather in Paris", "vremea la Cluj", "wetter in Berlin".
const PLACE_PATTERN = /(?:\bin\b|\bat\b|\bla\b|\bîn\b|\bà\b)\s+([a-zA-ZăâîșțĂÂÎȘȚ\s\-]+?)\s*[?.!]*$/i;

// Weather Agent — free, no-key open-meteo lookup for a named place or the user's current GPS fix.
export async function runWeatherAgent(
  text: string,
  address: string,
): Promise<{ reply: string; card?: ContentCard }> {
  const placeMatch = text.match(PLACE_PATTERN);
  const placeName = placeMatch ? placeMatch[1].trim() : null;

  let lat: number;
  let lon: number;
  let place: string;

  if (placeName) {
    const coords = await geocodePlace(placeName);
    if (!coords) return { reply: `I couldn't find ${placeName}, ${address}.` };
    lat = coords.latitude;
    lon = coords.longitude;
    place = placeName;
  } else {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (!perm.granted) return { reply: `I need location access to check the weather, ${address}.` };
    const pos = await Location.getCurrentPositionAsync({});
    lat = pos.coords.latitude;
    lon = pos.coords.longitude;
    place = 'your location';
  }

  const weather = await fetchWeatherData(lat, lon);
  if (!weather) return { reply: `I couldn't get the weather right now, ${address}.` };

  return {
    reply: `It's ${weather.tempC}°C and ${weather.description} ${placeName ? `in ${place}` : 'right now'}, ${address}.`,
    card: {
      kind: 'weather',
      place,
      tempC: weather.tempC,
      description: weather.description,
      precipitation: weather.precipitation,
    },
  };
}
