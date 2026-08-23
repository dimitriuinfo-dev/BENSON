import * as MediaLibrary from 'expo-media-library';
import type { ContentCard } from './contentTypes';

export const GALLERY_PATTERN = /\b(?:photos?|poze(?:le)?|pictures?)\b/i;

// "my photos" / "pozele mele" — device gallery. Anything else — web image search.
const POSSESSIVE_PATTERN = /\b(?:my|mele|ale mele|mes)\b/i;
const QUERY_PATTERN = /(?:of|cu|despre|de)\s+(.+?)\s*[?.!]*$/i;

// Gallery Agent — Benson picks the source: device photos for possessive phrasing,
// Tavily web image search otherwise.
export async function runGalleryAgent(
  text: string,
  address: string,
  tavilyKey: string,
): Promise<{ reply: string; card?: ContentCard }> {
  if (POSSESSIVE_PATTERN.test(text)) {
    const perm = await MediaLibrary.requestPermissionsAsync();
    if (!perm.granted) return { reply: `I need photo access to show your gallery, ${address}.` };

    const result = await MediaLibrary.getAssetsAsync({ mediaType: 'photo', first: 12 });
    const images = result.assets.map(a => ({ url: a.uri, title: a.filename }));
    if (!images.length) return { reply: `I couldn't find any photos on your device, ${address}.` };

    return {
      reply: `Here are your recent photos, ${address}.`,
      card: { kind: 'gallery', source: 'device', images },
    };
  }

  const qMatch = text.match(QUERY_PATTERN);
  const query = (qMatch ? qMatch[1] : text.replace(GALLERY_PATTERN, '')).trim() || 'photos';

  if (!tavilyKey) {
    return { reply: `I need a Tavily API key to search photos online, ${address}. Add it in Settings.` };
  }

  try {
    const res = await fetch('https://api.tavily.com/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ api_key: tavilyKey, query, include_images: true, max_results: 1 }),
    });
    const data = await res.json();
    const images = ((data.images || []) as string[]).slice(0, 12).map(url => ({ url }));
    if (!images.length) return { reply: `I couldn't find any photos of ${query}, ${address}.` };

    return {
      reply: `Here are some photos of ${query}, ${address}.`,
      card: { kind: 'gallery', source: 'web', query, images },
    };
  } catch {
    return { reply: `I couldn't reach the photo search service, ${address}.` };
  }
}
