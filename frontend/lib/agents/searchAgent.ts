import type { AnthropicMsg, Character, FamilyMember } from './types';
import type { ContentCard } from './contentTypes';
import { askClaude } from './claudeAgent';

export const SEARCH_PATTERN =
  /\b(?:search for|search|look up|what'?s the latest(?: on| about)?|what is the latest(?: on| about)?|caut[ăa](?: pe internet)?(?: despre)?|ce e nou(?: cu| despre| la)?|suche(?: nach)?|recherche(?: sur)?)\b\s+(.+)/i;

export const NEWS_PATTERN = /\b(?:news|știri|stiri)\b/i;

export type TavilyResult = { title: string; content: string; url: string };

// Single Tavily call-site — shared by runSearchAgent below (regex-matched search/news commands)
// and the web_search tool (lib/agents/tools.ts, Claude's own tool-use loop for generic questions
// no regex catches, e.g. "cât e cursul euro azi?").
export async function tavilySearch(query: string, tavilyKey: string, isNews: boolean): Promise<{
  results: TavilyResult[];
  images: string[];
}> {
  const searchRes = await fetch('https://api.tavily.com/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      api_key:      tavilyKey,
      query,
      max_results:  5,
      search_depth: 'basic',
      topic:        isNews ? 'news' : 'general',
      include_images: isNews,
    }),
  });
  const searchData = await searchRes.json();
  return { results: searchData.results || [], images: searchData.images || [] };
}

// Search Agent — fetches live results from Tavily, then hands them to the Claude Agent to
// synthesize a spoken reply. News queries also get a structured card of articles.
export async function runSearchAgent(params: {
  text: string;
  query: string;
  tavilyKey: string;
  apiKey: string;
  character: Character;
  address: string;
  lang: string;
  facts: string[];
  history: AnthropicMsg[];
  family?: FamilyMember[];
  drivingContext?: string;
}): Promise<{ reply: string; card?: ContentCard }> {
  const isNews = NEWS_PATTERN.test(params.text);

  const { results, images } = await tavilySearch(params.query, params.tavilyKey, isNews);
  const context = results
    .map((r, i) => `[${i + 1}] ${r.title} — ${r.content} (${r.url})`)
    .join('\n');

  const messages: AnthropicMsg[] = [
    ...params.history,
    {
      role: 'user',
      content: `${params.text}\n\nWeb search results:\n${context || 'No results found.'}\n\n` +
        `Answer using these results. Be concise and natural — don't mention "search results" explicitly.`,
    },
  ];

  const reply = await askClaude({
    apiKey:    params.apiKey,
    character: params.character,
    address:   params.address,
    lang:      params.lang,
    facts:     params.facts,
    family:    params.family,
    drivingContext: params.drivingContext,
    messages,
  });

  if (!isNews || !results.length) return { reply };

  return {
    reply,
    card: {
      kind: 'news',
      query: params.query,
      articles: results.map((r, i) => ({
        title: r.title,
        url: r.url,
        snippet: r.content,
        image: images[i],
      })),
    },
  };
}
