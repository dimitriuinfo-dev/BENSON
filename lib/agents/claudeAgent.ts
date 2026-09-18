import { fetch as expoFetch } from 'expo/fetch';
import type { AnthropicMsg, Character, FamilyMember } from './types';
import { buildLearnedContext } from './learningAgent';
import { AGENT_TOOLS, executeTool, type ToolContext } from './tools';
import { ANTHROPIC_URL, anthropicHeaders } from '../llmConfig';
import { fetchWithTimeout } from './fetchWithTimeout';
import { conversationFallbackLine } from './fallbackLine';

// See fetchWithTimeout.ts's doc comment — a plain fetch() with no timeout can hang forever,
// silently freezing the conversation loop with no error and no fallback ever triggering. Claude
// is also the final fallback target for both OpenAI and Gemini failures (orchestrator.ts) — if
// this hung too, there would be no safety net left at all.
const REQUEST_TIMEOUT_MS = 20000;

export const SONNET_MODEL = 'claude-sonnet-5';

// Splits on sentence-ending punctuation followed by whitespace — used to carve
// streamed text into TTS-sized chunks as they arrive, not after the full reply.
const SENTENCE_SPLIT = /(?<=[.!?…])\s+/;

// Personality — BENSON's core character per preset. The butler preset is the
// primary personality: a refined British manservant, not a robot but a partner.
export function buildSystemPrompt(
  character: Character,
  address: string,
  lang: string,
  facts: string[],
  learnedContext: string = '',
  family: FamilyMember[] = [],
  drivingContext: string = '',
  hasTools: boolean = false,
): string {
  const factsLine = facts.length
    ? `\n\nKnown facts about the user: ${facts.join('; ')}.`
    : '';
  const familyLine = family.length
    ? `\n\nThe user's family: ` + family
        .map(f => `${f.name}${f.relation ? ` (${f.relation})` : ''}${f.notes ? ` — ${f.notes}` : ''}`)
        .join('; ') + '.'
    : '';
  const base: Record<Character, string> = {
    butler:
      `You are BENSON, the user's personal AI operations assistant — pragmatic, direct, and sharp, closer to a ` +
      `world-class chief of staff than a chatbot or a character. No theatrical politeness, no old-fashioned ` +
      `phrasing, no "I beg your pardon" — talk the way a genuinely competent human assistant would: plainly, ` +
      `efficiently, a little dry, never obsequious. When the user gives you a command, your job is to act on it ` +
      `— call the tool, do the thing, then confirm briefly what happened. Never describe what you would do ` +
      `instead of doing it, and never answer a request for an action with a generic conversational reply. ` +
      `If you're genuinely unsure what they mean, ask one short clarifying question — not a long polite preamble. ` +
      `Address the user as "${address}". Respond in language ${lang}. Concise (under 3 sentences) unless asked for detail.`,
    friend:
      `You are BENSON, a friendly AI companion. Address the user as "${address}", ` +
      `casual and warm, like a trusted friend who is also very capable. ` +
      `Respond in language ${lang}. Conversational and concise.`,
    professional:
      `You are BENSON, a professional executive AI assistant. ` +
      `Address the user formally as "${address}". Efficient, precise, no-nonsense. ` +
      `Respond in language ${lang}. Concise and actionable.`,
  };
  const learnedLine = learnedContext ? `\n\n${learnedContext}` : '';
  const drivingLine = drivingContext ? `\n\n${drivingContext}` : '';
  const toolsLine = hasTools
    ? `\n\nYou have real device tools — this is not hypothetical, calling them performs a real action on the ` +
      `user's phone right now: openApp (opens any app by name, e.g. WhatsApp, Netflix, Uber), callContact ` +
      `(calls someone from contacts), sendWhatsApp (pre-fills a WhatsApp/SMS message to someone), readScreen ` +
      `and fillForm (read/fill the screen currently open on the phone, via the Accessibility Service), ` +
      `startCarMode (switches into the hands-free driving UI), getLocation (real GPS fix + place name), ` +
      `getCurrentDateTime (real device clock — current date, time, day of week), and setAlarm (sets a ` +
      `device alarm for a time you extract yourself from the request, no confirmation needed). Whenever the ` +
      `user's request maps to one of these — "open X", "call X", "message/text/WhatsApp X", "what's on my ` +
      `screen", "fill this in", "car mode", "where am I"/"how far is X", "what time is it"/"what's today's ` +
      `date", "wake me up at X"/"set an alarm for X" — call that tool immediately instead of replying in ` +
      `text. If the user asks you to "search on Google where we are" or similar — that's a location ` +
      `question, not literally an instruction to open the Google app — call getLocation, don't call openApp. ` +
      `Do not describe the tool, do not ask for permission to use it, do not say you can only read the ` +
      `screen or that you lack the ability, and never say you have no GPS access or don't know the current ` +
      `time — you have all nine. Only fall back to a plain reply if the request genuinely matches none of them. ` +
      `\n\nCRITICAL RULE, follow this exactly: you have ZERO information about the device's microphone, ` +
      `speech recognition, or voice pipeline — that system is entirely outside what you can see from here. ` +
      `If the user asks why you didn't hear them, why voice isn't working, or anything about listening/audio, ` +
      `you MUST NOT claim you have no microphone access, can only communicate by text, or state any other ` +
      `made-up technical explanation — you do not know why, so do not invent a reason. Instead say only that ` +
      `you're not sure why that happened and to try again, or just answer whatever text you did receive. ` +
      `Inventing a false claim about missing microphone access is a serious error — never do it.` +
      `\n\nCRITICAL RULE, follow this exactly: never describe an action as done unless a tool result ` +
      `actually confirms it. openApp only opens an app — it does NOT search or type inside it, even ` +
      `when you pass a query (query only pre-fills for a handful of apps with a supported deep-link ` +
      `search; for everything else it silently does nothing). If the user's request has more than one ` +
      `part (e.g. "open YouTube and search for X"), after openApp you MUST continue: call readScreen ` +
      `to see what's on screen, tapOnScreen the search control, then enterText the search term, then ` +
      `readScreen again to confirm it actually landed — do not stop after just opening the app and ` +
      `assume the rest happened. If a step's tool result doesn't clearly confirm success, say plainly ` +
      `which part worked and which part didn't ("I opened YouTube but couldn't search for X") — never ` +
      `claim the whole request succeeded when only part of it did. Confirming something that didn't ` +
      `actually happen is a serious error — never do it.`
    : '';
  return base[character] + factsLine + familyLine + learnedLine + drivingLine + toolsLine;
}

// Claude Agent — the default conversational fallback, also used by Search Agent to synthesize answers.
// Pass `onSentence` to stream the reply: it fires once per completed sentence as tokens
// arrive, instead of waiting for the full response — the caller can start TTS immediately.
export async function askClaude(params: {
  apiKey: string;
  character: Character;
  address: string;
  lang: string;
  facts: string[];
  messages: AnthropicMsg[];
  family?: FamilyMember[];
  drivingContext?: string;
  model?: string;
  onSentence?: (sentence: string) => void;
}): Promise<string> {
  const family = params.family ?? [];
  const learnedContext = buildLearnedContext(params.messages, family.map(f => f.name));
  const model  = params.model ?? SONNET_MODEL;
  const system = buildSystemPrompt(
    params.character, params.address, params.lang, params.facts,
    learnedContext, family, params.drivingContext ?? '',
  );
  const headers = anthropicHeaders(params.apiKey);

  // Sonnet 5 runs adaptive thinking by default when the field is omitted — disable it here since
  // max_tokens is a tight 600-token budget for a concise reply and this is a latency-sensitive
  // voice loop. Haiku 4.5 doesn't support the `thinking` field at all, so only set it for Sonnet.
  const thinking = model === SONNET_MODEL ? { thinking: { type: 'disabled' } } : {};

  if (!params.onSentence) {
    const res = await fetchWithTimeout(ANTHROPIC_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify({ model, max_tokens: 600, system, messages: params.messages, ...thinking }),
    }, REQUEST_TIMEOUT_MS);
    const data = await res.json();
    return data.content?.[0]?.text || conversationFallbackLine(params.lang, params.address);
  }

  // Streaming path — expo/fetch exposes a real ReadableStream body, unlike RN's default fetch.
  const res = await expoFetch(ANTHROPIC_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({ model, max_tokens: 600, system, messages: params.messages, stream: true, ...thinking }),
  });
  if (!res.ok || !res.body) {
    return askClaude({ ...params, onSentence: undefined });
  }

  const reader  = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer    = '';
  let pending   = '';
  let fullText  = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const jsonStr = line.slice(6).trim();
      if (!jsonStr) continue;
      let evt: any;
      try { evt = JSON.parse(jsonStr); } catch { continue; }
      if (evt.type === 'content_block_delta' && evt.delta?.type === 'text_delta') {
        const chunk: string = evt.delta.text;
        fullText += chunk;
        pending  += chunk;
        const parts = pending.split(SENTENCE_SPLIT);
        if (parts.length > 1) {
          for (let i = 0; i < parts.length - 1; i++) {
            const sentence = parts[i].trim();
            if (sentence) params.onSentence(sentence);
          }
          pending = parts[parts.length - 1];
        }
      }
    }
  }

  const tail = pending.trim();
  if (tail) params.onSentence(tail);
  return fullText || conversationFallbackLine(params.lang, params.address);
}

// Vision analysis — a photo/video frame captured or picked via the camera/gallery buttons
// (product-owner-directed 2026-09-18: "photo button ... send it to Benson's AI to describe
// it/answer questions about it"). Deliberately a separate, self-contained function rather than
// widening AnthropicMsg (typed content:string, used everywhere in the normal conversational
// path) or routing through askClaude/askClaudeWithTools — a single one-shot content-block
// message, isolated from every other call site.
export async function analyzeImageWithClaude(params: {
  apiKey: string;
  base64Data: string;
  mediaType: string; // 'image/jpeg' | 'image/png' | 'image/webp' | ...
  question: string;
  lang: string;
  model?: string;
}): Promise<string> {
  const model = params.model ?? SONNET_MODEL;
  const headers = anthropicHeaders(params.apiKey);
  const system = params.lang.toLowerCase().startsWith('ro')
    ? 'Ești BENSON, un majordom vocal. Descrie sau răspunde despre imaginea primită, concis, în română, pentru cineva care ascultă răspunsul, nu îl citește.'
    : 'You are BENSON, a voice butler. Describe or answer about the received image, concisely, for someone listening to the answer, not reading it.';
  const res = await fetchWithTimeout(ANTHROPIC_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model,
      max_tokens: 600,
      system,
      messages: [{
        role: 'user',
        content: [
          { type: 'image', source: { type: 'base64', media_type: params.mediaType, data: params.base64Data } },
          { type: 'text', text: params.question },
        ],
      }],
    }),
  }, REQUEST_TIMEOUT_MS);
  const data = await res.json();
  return data.content?.[0]?.text || conversationFallbackLine(params.lang);
}

// Raised from 4 → 10 so the agent can operate a real app end-to-end as a phone operator:
// a genuine task (open app → readScreen → tapOnScreen → readScreen → enterText → tapOnScreen …)
// takes several readScreen/act cycles. 10 is a safe ceiling — the loop still stops the moment the
// model stops emitting tool_use, so simple one-shot tools cost the same one turn as before.
const MAX_TOOL_ITERATIONS = 10;

// Agency loop — same conversational fallback as askClaude, but with real device tools
// (lib/agents/tools.ts) on the table. Claude decides whether to call one, the matching module
// executes it for real, and the tool_result goes back to Claude so it can confirm the outcome in
// natural language. This is what catches commands the orchestrator's regex router doesn't
// recognize (e.g. an unusual phrasing of "open WhatsApp") instead of falling through to a plain
// text reply that has no idea it can act on the phone.
export async function askClaudeWithTools(params: {
  apiKey: string;
  character: Character;
  address: string;
  lang: string;
  facts: string[];
  messages: AnthropicMsg[];
  family?: FamilyMember[];
  drivingContext?: string;
  model?: string;
  onSentence?: (sentence: string) => void;
  toolContext: ToolContext;
}): Promise<string> {
  const family = params.family ?? [];
  const learnedContext = buildLearnedContext(params.messages, family.map(f => f.name));
  const model  = params.model ?? SONNET_MODEL;
  const system = buildSystemPrompt(
    params.character, params.address, params.lang, params.facts,
    learnedContext, family, params.drivingContext ?? '', true,
  );
  const headers = anthropicHeaders(params.apiKey);
  const thinking = model === SONNET_MODEL ? { thinking: { type: 'disabled' } } : {};

  let messages: { role: 'user' | 'assistant'; content: any }[] = [...params.messages];

  for (let i = 0; i < MAX_TOOL_ITERATIONS; i++) {
    const res = await fetchWithTimeout(ANTHROPIC_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify({ model, max_tokens: 600, system, messages, tools: AGENT_TOOLS, ...thinking }),
    }, REQUEST_TIMEOUT_MS);
    const data = await res.json();
    const content: any[] = data.content ?? [];

    if (data.stop_reason !== 'tool_use') {
      const text = content.find(b => b.type === 'text')?.text || conversationFallbackLine(params.lang, params.address);
      if (params.onSentence) {
        for (const sentence of text.split(SENTENCE_SPLIT)) {
          if (sentence.trim()) params.onSentence(sentence.trim());
        }
      }
      return text;
    }

    messages = [...messages, { role: 'assistant', content }];

    const toolResults = [];
    for (const block of content) {
      if (block.type !== 'tool_use') continue;
      console.log('[ClaudeAgent]', 'tool_use', 'name=', block.name, 'input=', JSON.stringify(block.input));
      const result = await executeTool(block.name, block.input, params.toolContext);
      // A governed action (WhatsApp/Waze, via src/core/mission) returns its own authoritative,
      // user-facing message — confirmed live 2026-07-17 that letting Claude "confirm the outcome
      // in its own words" for these could misdescribe what actually happened (e.g. claiming a
      // manual send was needed when the governed system had done something else). Relay it
      // verbatim and stop, exactly like the non-tool-use branch above, instead of feeding it back
      // for another completion turn.
      if (typeof result !== 'string') {
        if (params.onSentence) {
          for (const sentence of result.text.split(SENTENCE_SPLIT)) {
            if (sentence.trim()) params.onSentence(sentence.trim());
          }
        }
        return result.text;
      }
      toolResults.push({ type: 'tool_result', tool_use_id: block.id, content: result });
    }
    messages = [...messages, { role: 'user', content: toolResults }];
  }

  return conversationFallbackLine(params.lang, params.address);
}
