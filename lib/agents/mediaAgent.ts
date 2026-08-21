import { Linking } from 'react-native';
import type { ContentCard } from './contentTypes';

// "play <query> on youtube/spotify" / "redă <query> pe youtube/spotify" — provider is optional,
// defaults to YouTube.
export const PLAY_PATTERN =
  /\b(?:play|reda|redă|pune|joacă)\b\s+(.+?)(?:\s+(?:on|pe)\s+(youtube|you\s*tube|spotify))?$/i;

// Media Agent — always hands off to the real app via deep link (Benson never embeds a player;
// Spotify's Web Playback SDK needs OAuth + Premium + browser DRM, and YouTube playback belongs to
// the YouTube app, not a re-implementation inside Benson).
export async function runMediaAgent(
  query: string,
  provider: string | undefined,
  address: string,
): Promise<{ reply: string; card?: ContentCard }> {
  const q = query.trim();
  const isSpotify = /spotify/i.test(provider ?? '');

  if (isSpotify) {
    const target = `spotify://search/${encodeURIComponent(q)}`;
    const canOpen = await Linking.canOpenURL(target).catch(() => false);
    await Linking.openURL(canOpen ? target : 'https://open.spotify.com/search/' + encodeURIComponent(q))
      .catch(() => {});
    return {
      reply: `Playing "${q}" on Spotify, ${address}.`,
      card: { kind: 'media', provider: 'spotify', title: q, query: q },
    };
  }

  const target = `youtube://results?search_query=${encodeURIComponent(q)}`;
  const canOpen = await Linking.canOpenURL(target).catch(() => false);
  await Linking.openURL(canOpen ? target : `https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`)
    .catch(() => {});
  return {
    reply: `Playing "${q}" on YouTube, ${address}.`,
    card: { kind: 'media', provider: 'youtube', title: q, query: q },
  };
}
