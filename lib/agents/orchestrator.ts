import type { AnthropicMsg, Character, FamilyMember } from './types';
import type { ContentCard } from './contentTypes';
import { CALL_PATTERN } from './contactsAgent';
import { buildActionRequest, execute as executeGoverned } from '../../src/core/mission';
import { launchApp } from './appLauncherAgent';
import { SEARCH_PATTERN, NEWS_PATTERN, runSearchAgent } from './searchAgent';
import { askClaudeWithTools } from './claudeAgent';
import { askOpenAIWithTools } from './openaiAgent';
import { askGeminiWithTools } from './geminiAgent';
import type { ToolContext } from './tools';
import { sanityCheckContactParam } from '../engines/actionSanity';
// Re-exported so existing importers (app/index.tsx) keep `import { conversationFallbackLine }
// from '../lib/agents/orchestrator'` working; the definition now lives in the dependency-free
// leaf module ./fallbackLine so the conversational agents can import it without a cycle.
import { conversationFallbackLine } from './fallbackLine';
export { conversationFallbackLine };

export type ModelProvider = 'claude' | 'openai' | 'gemini';
import { WEATHER_PATTERN, runWeatherAgent } from './weatherAgent';
import { TIME_PATTERN, LOCATION_PATTERN, runTimeAgent, runLocationAgent } from './deviceFactsAgent';
import { PLAY_PATTERN, runMediaAgent } from './mediaAgent';
import { GALLERY_PATTERN, runGalleryAgent } from './galleryAgent';
import { NOTEPAD_PATTERN, runNoteRouterAgent, isConfident, type ParsedNote } from './noteRouterAgent';
import { setReminder } from '../notepad/actions';
import { addTodoItem } from '../notepad/todoList';
import { addFeedbackItem } from '../notepad/feedbackList';

export type OrchestratorContext = {
  address: string;
  apiKey: string;
  openaiKey: string;
  geminiKey: string;
  tavilyKey: string;
  // Which chat model backs the tool-use fallback (default 'claude' when unset). Each provider is
  // called directly with the user's own key (lib/llmConfig.ts) — Supabase relay removed.
  modelProvider?: ModelProvider;
  character: Character;
  lang: string;
  facts: string[];
  history: AnthropicMsg[];
  family?: FamilyMember[];
  drivingContext?: string;
  // Fires per completed sentence as the Claude Agent's reply streams in — lets the
  // caller start TTS before the full response finishes generating.
  onSentence?: (sentence: string) => void;
  // Lets the Claude Agent's startCarMode tool actually flip Car Mode on — the toggle itself
  // lives in component state (app/index.tsx), which this module has no access to otherwise.
  onStartCarMode?: () => void;
  // Lets the Claude Agent's remember tool persist a fact — the same appendFact() used by the
  // REMEMBER_PATTERN regex path, threaded through since facts storage also lives in app/index.tsx.
  onRememberFact?: (fact: string) => Promise<void> | void;
};

const HAIKU_MODEL = 'claude-haiku-4-5';
// Rate-limit circuit breaker — see the doc comment at the routeCommand call sites below and
// voiceAgent.ts's identical mechanism. Once a chat provider 429s, skip it for this cooldown
// instead of re-proving the same guaranteed failure on every turn before falling back to Claude.
const RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000;
let openaiChatRateLimitedUntil = 0;
let geminiChatRateLimitedUntil = 0;
const SIMPLE_MAX_WORDS = 10;
// A short message can still be a "complex question" worth Sonnet/Opus-level reasoning —
// these markers (multi-language) keep it off the fast path even under the word cap.
const COMPLEX_QUESTION_PATTERN =
  /\b(why|how|what if|explain|compare|analy[sz]e|de ce|cum s[ăa]|explic[ăa]|compar[ăa]|warum|wie|pourquoi|comment)\b/i;

// Router rule: short, conversational turns (greetings, confirmations, thanks, simple
// commands) go to Haiku for latency; anything longer, a complex question, or deep into
// a long-context conversation stays on Sonnet/Opus.
function isSimpleConversational(text: string, historyLength: number): boolean {
  const words = text.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0 || words.length > SIMPLE_MAX_WORDS) return false;
  if (historyLength > 6) return false;
  if (COMPLEX_QUESTION_PATTERN.test(text)) return false;
  return true;
}

export type AgentName = 'contacts' | 'appLauncher' | 'search' | 'claude' | 'weather' | 'media' | 'gallery' | 'notepad' | 'time' | 'location';

export type OrchestratorResult = {
  agent: AgentName;
  reply: string;
  card?: ContentCard;
  // Set only when a calendar/message note needs confirmation before proceeding — the caller
  // (app/index.tsx) stashes this and checks the user's next reply, mirroring pendingVignetteRef.
  pendingNote?: ParsedNote;
  // Which registry app was actually opened (appLauncherAgent) — for analytics' app_opened field.
  appId?: string;
};

// Benson Core Orchestrator — decides which agent handles an incoming command:
// Contacts > Media (play X) > App Launcher (open/navigate) > Time > Location > Weather > Gallery >
// Search (news is a specialization of Search) > Claude Agent (default fallback, still has
// getCurrentDateTime/getLocation tools of its own for phrasings Time/Location's patterns miss).
export async function routeCommand(
  text: string,
  ctx: OrchestratorContext,
  onProgress?: (text: string) => void,
): Promise<OrchestratorResult> {
  if (CALL_PATTERN.test(text)) {
    // Routed through the same governed WhatsApp-calling path as the deterministic Mission
    // Orchestrator and Claude's tool-use loop (unified 2026-07-17) — this was, until now, a
    // FOURTH independent "call a contact" implementation (the old contactsAgent.ts: a naive
    // substring contact match + a raw Linking.openURL('tel:...'), the exact implicit intent that
    // pops Android's multi-dialer chooser on this phone), reached whenever the Mission
    // Orchestrator's own parser didn't recognize the phrasing — which is exactly the class of
    // inconsistency ("sometimes it works, sometimes it doesn't") reported live.
    // No \b right after the optional Romanian-diacritic vowel ([aăo]?) — JS's \b only recognizes
    // [A-Za-z0-9_] as "word" characters, so a boundary check immediately after "ă" doesn't fire
    // the way it looks like it should (same class of bug LEADING_BOUNDARY in commandParser.ts
    // documents), and confirmed live here: it silently truncated the match to "sun", leaving the
    // "ă" itself as the first character of the captured "contact name".
    const match = text.match(/\b(?:call|sun[aăo]?|ruf|appelle)(?:-[oli]l?)?\s*(.*)$/i);
    const contactName = (match?.[1] ?? '').replace(/^(?:o|l|le|îl|il|pe|la|lui)\s+/i, '').trim();
    // Build B — the same sanity checkpoint the brain route uses, here on the deterministic parser
    // route, immediately before the governed call (Confirmation Gate). "sună wake up" / a verb /
    // foreign-script text never reaches contact resolution — BENSON asks who instead.
    if (!sanityCheckContactParam('parser', contactName, ctx.lang).ok) {
      return {
        agent: 'contacts',
        reply: (ctx.lang || '').toLowerCase().startsWith('ro')
          ? `Pe cine să sun, ${ctx.address}?`
          : (ctx.lang || '').toLowerCase().startsWith('de')
          ? `Wen soll ich anrufen, ${ctx.address}?`
          : `Who should I call, ${ctx.address}?`,
      };
    }
    const request = buildActionRequest('whatsapp', 'placeCall', { contactName });
    const outcome = await executeGoverned(request, { confirmed: false });
    return { agent: 'contacts', reply: outcome.message };
  }

  // Only claim this as a YouTube/Spotify request if a provider was explicitly named, or there's
  // no leftover "on/pe X" clause in the query — otherwise "play X on <other app>" (e.g. Netflix)
  // falls through to the App Launcher's search-in-app handling instead.
  const playMatch = text.match(PLAY_PATTERN);
  if (playMatch && (playMatch[2] || !/\s+(?:on|pe)\s+/i.test(playMatch[1]))) {
    const result = await runMediaAgent(playMatch[1], playMatch[2], ctx.address);
    return { agent: 'media', ...result };
  }

  const launcherResult = await launchApp(text, ctx.address);
  if (launcherResult !== null) {
    return { agent: 'appLauncher', ...launcherResult };
  }

  // Deterministic, not tool-calling — see deviceFactsAgent.ts's doc comment for why.
  if (TIME_PATTERN.test(text)) {
    return { agent: 'time', ...runTimeAgent(ctx.lang, ctx.address) };
  }

  if (LOCATION_PATTERN.test(text)) {
    const result = await runLocationAgent(ctx.lang, ctx.address);
    return { agent: 'location', ...result };
  }

  if (WEATHER_PATTERN.test(text)) {
    const result = await runWeatherAgent(text, ctx.address, ctx.lang);
    return { agent: 'weather', ...result };
  }

  if (GALLERY_PATTERN.test(text)) {
    const result = await runGalleryAgent(text, ctx.address, ctx.tavilyKey);
    return { agent: 'gallery', ...result };
  }

  if (NOTEPAD_PATTERN.test(text)) {
    const note = await runNoteRouterAgent(text, ctx.apiKey);
    if (!note || !isConfident(note)) {
      return {
        agent: 'notepad',
        reply: (ctx.lang || '').toLowerCase().startsWith('ro')
          ? `Nu sunt sigur ce vrei să fac cu asta, ${ctx.address} — poți reformula?`
          : (ctx.lang || '').toLowerCase().startsWith('de')
          ? `Ich bin nicht sicher, was ich damit tun soll, ${ctx.address} — kannst du das anders formulieren?`
          : `I'm not sure what you'd like me to do with that, ${ctx.address} — could you rephrase?`,
      };
    }

    // Calendar/message need confirmation (or, for message, family-state access) that only the
    // caller has — hand the parsed note back untouched rather than acting on it here.
    if (note.actions.includes('create_calendar') || note.actions.includes('send_message')) {
      const question = note.actions.includes('create_calendar')
        ? `Should I add "${note.content}" to your calendar` +
          `${note.datetime ? ` for ${new Date(note.datetime).toLocaleString()}` : ''}, ${ctx.address}?`
        : `I've noted that for ${note.person ?? 'them'}. Want me to send it now, ${ctx.address}?`;
      return { agent: 'notepad', reply: question, pendingNote: note };
    }

    if (note.actions.includes('set_reminder')) {
      const reply = await setReminder(note, ctx.address);
      return { agent: 'notepad', reply, card: { kind: 'note', noteType: 'reminder', summary: note.content } };
    }

    if (note.actions.includes('add_todo')) {
      const items = await addTodoItem(note.content);
      return {
        agent: 'notepad',
        reply: `Added "${note.content}" to your list, ${ctx.address}.`,
        card: { kind: 'todo', items },
      };
    }

    if (note.actions.includes('save_feedback')) {
      await addFeedbackItem(note.content);
      return {
        agent: 'notepad',
        reply: `Thanks, I've saved that feedback, ${ctx.address}.`,
        card: { kind: 'note', noteType: 'feedback', summary: note.content },
      };
    }

    return { agent: 'notepad', reply: `Noted, ${ctx.address}.` };
  }

  const searchMatch = text.match(SEARCH_PATTERN);
  const isNewsQuery = NEWS_PATTERN.test(text);
  if (searchMatch || isNewsQuery) {
    const query = searchMatch ? searchMatch[1].trim() : text;
    if (!ctx.tavilyKey) {
      return {
        agent: 'search',
        reply: `I need a Tavily API key to search the web, ${ctx.address}. Add it in Settings.`,
      };
    }
    onProgress?.(`Searching the web for "${query}", ${ctx.address}.`);
    const result = await runSearchAgent({
      text, query,
      tavilyKey: ctx.tavilyKey,
      apiKey:    ctx.apiKey,
      character: ctx.character,
      address:   ctx.address,
      lang:      ctx.lang,
      facts:     ctx.facts,
      history:   ctx.history,
      family:    ctx.family,
      drivingContext: ctx.drivingContext,
    });
    return { agent: 'search', ...result };
  }

  const toolContext: ToolContext = {
    address: ctx.address,
    onStartCarMode: ctx.onStartCarMode,
    tavilyKey: ctx.tavilyKey,
    facts: ctx.facts,
    onRememberFact: ctx.onRememberFact,
  };
  const commonParams = {
    character: ctx.character,
    address:   ctx.address,
    lang:      ctx.lang,
    facts:     ctx.facts,
    family:    ctx.family,
    drivingContext: ctx.drivingContext,
    messages:  [...ctx.history, { role: 'user' as const, content: text }],
    onSentence: ctx.onSentence,
    toolContext,
  };
  const askClaudeFallback = () => askClaudeWithTools({
    ...commonParams,
    apiKey: ctx.apiKey,
    model: isSimpleConversational(text, ctx.history.length) ? HAIKU_MODEL : undefined,
  });
  const now = Date.now();
  let reply: string;
  if (ctx.modelProvider === 'openai') {
    // Rate-limit circuit breaker (product-owner-confirmed live 2026-08-25) — see voiceAgent.ts's
    // identical mechanism for the full rationale: skip a provider entirely for a cooldown once it
    // 429s, instead of re-proving the same guaranteed failure on every single turn before falling
    // back to Claude.
    if (now < openaiChatRateLimitedUntil) {
      reply = ctx.apiKey ? await askClaudeFallback() : conversationFallbackLine(ctx.lang, ctx.address);
    } else {
    try {
      reply = await askOpenAIWithTools({ ...commonParams, apiKey: ctx.openaiKey });
    } catch (e) {
      // Confirmed live 2026-08-24: an unconfigured/rate-limited OpenAI account (429, no billing
      // set up yet) made every ChatGPT-selected turn silently return the same unhelpful "I did
      // not quite catch that" — openaiAgent.ts now throws instead of swallowing that, so this can
      // fall back to Claude (the already-working, already-paid-for provider) rather than leaving
      // the user stuck on a dead provider until they notice and switch it back manually in
      // Settings. Only falls back when a Claude key actually exists — otherwise the OpenAI error
      // is real and there's nothing else to try.
      if (String(e).includes('429')) openaiChatRateLimitedUntil = Date.now() + RATE_LIMIT_COOLDOWN_MS;
      reply = ctx.apiKey ? await askClaudeFallback() : conversationFallbackLine(ctx.lang, ctx.address);
    }
    }
  } else if (ctx.modelProvider === 'gemini') {
    if (now < geminiChatRateLimitedUntil) {
      reply = ctx.apiKey ? await askClaudeFallback() : conversationFallbackLine(ctx.lang, ctx.address);
    } else {
    try {
      reply = await askGeminiWithTools({ ...commonParams, apiKey: ctx.geminiKey });
    } catch (e) {
      // Same fallback rule as OpenAI above — a bad/missing Gemini key or a disabled model
      // (confirmed live 2026-08-25: gemini-2.5-flash 404s against some keys) must not strand the
      // user on a dead provider.
      if (String(e).includes('429')) geminiChatRateLimitedUntil = Date.now() + RATE_LIMIT_COOLDOWN_MS;
      reply = ctx.apiKey ? await askClaudeFallback() : conversationFallbackLine(ctx.lang, ctx.address);
    }
    }
  } else {
    // Default provider is Claude. With no Anthropic key configured, don't let claudeAgent's own
    // hardcoded English "I did not quite catch that" surface — use the single fallback line
    // (Round 2 / Task 3). This is the last resort: the Build B brain and every other provider
    // path have already been tried or are unconfigured.
    reply = ctx.apiKey ? await askClaudeFallback() : conversationFallbackLine(ctx.lang, ctx.address);
  }
  return { agent: 'claude', reply };
}
