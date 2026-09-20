// BENSON_GROUNDED_CONVERSATION_1 (2026-09-20) — thin adapter over the EXISTING Tavily call in
// lib/agents/searchAgent.ts. No duplicate HTTP logic, no key management (the caller supplies the
// key it already reads from Settings), no model choice — this module only fetches results and
// reports them, honestly, with source and fetch time. The caller (groundedAnswer.ts) decides how
// to phrase a reply from the results; this provider never invents content.
import { tavilySearch, type TavilyResult } from '../agents/searchAgent';

export type WebSearchResult =
  | { ok: true; results: TavilyResult[]; source: 'tavily'; fetchedAt: number }
  | { ok: false; reason: 'no_api_key' }
  | { ok: false; reason: 'provider_unavailable' };

export async function webSearch(query: string, isNews: boolean, tavilyKey: string): Promise<WebSearchResult> {
  if (!tavilyKey || !tavilyKey.trim()) return { ok: false, reason: 'no_api_key' };
  try {
    const { results } = await tavilySearch(query, tavilyKey, isNews);
    if (!results.length) return { ok: false, reason: 'provider_unavailable' };
    return { ok: true, results, source: 'tavily', fetchedAt: Date.now() };
  } catch {
    return { ok: false, reason: 'provider_unavailable' };
  }
}
