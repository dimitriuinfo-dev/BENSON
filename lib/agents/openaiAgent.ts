import { fetch as expoFetch } from 'expo/fetch';
import type { AnthropicMsg, Character, FamilyMember } from './types';
import { buildLearnedContext } from './learningAgent';
import { buildSystemPrompt } from './claudeAgent';
import { AGENT_TOOLS, executeTool, type ToolContext } from './tools';
import { OPENAI_URL, openaiHeaders } from '../llmConfig';
import { fetchWithTimeout } from './fetchWithTimeout';
import { conversationFallbackLine } from './fallbackLine';

// See fetchWithTimeout.ts's doc comment — a plain fetch() with no timeout can hang forever,
// silently freezing the conversation loop with no error and no fallback ever triggering.
const REQUEST_TIMEOUT_MS = 20000;

// Mirrors lib/agents/claudeAgent.ts's two entry points (askClaude, askClaudeWithTools) with the
// identical onSentence streaming contract, so lib/agents/orchestrator.ts can branch on the
// Settings model-provider toggle without either call site knowing the difference. Same persona
// (buildSystemPrompt is shared, imported from claudeAgent.ts, not duplicated) and same
// executeTool() — only the wire format and the upstream provider differ.
export const GPT4O_MODEL = 'gpt-4o';

const SENTENCE_SPLIT = /(?<=[.!?…])\s+/;
// Raised 4 → 10 to match askClaudeWithTools: a real phone-operator task needs several
// readScreen/act cycles. The loop still exits as soon as the model stops requesting tools.
const MAX_TOOL_ITERATIONS = 10;

function toOpenAITools() {
  return AGENT_TOOLS.map(t => ({
    type: 'function' as const,
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  }));
}

export async function askOpenAI(params: {
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
  const model = params.model ?? GPT4O_MODEL;
  const headers = openaiHeaders(params.apiKey);
  const system = buildSystemPrompt(
    params.character, params.address, params.lang, params.facts,
    learnedContext, family, params.drivingContext ?? '',
  );
  const messages = [
    { role: 'system', content: system },
    ...params.messages.map(m => ({ role: m.role, content: m.content })),
  ];

  if (!params.onSentence) {
    const res = await fetch(OPENAI_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify({ model, max_tokens: 600, messages }),
    });
    // Confirmed live 2026-08-24: a failed request (429 quota/billing, 401 bad key, etc.) was
    // silently swallowed here — no res.ok check, so `data.choices` was just undefined and this
    // fell straight to the generic "I did not quite catch that" text on EVERY failure, with no
    // way for the caller to tell a real error from a genuinely unclear utterance, and no way to
    // fall back to another provider. Throwing lets orchestrator.ts's caller do that instead.
    if (!res.ok) throw new Error(`OpenAI request failed: ${res.status}`);
    const data = await res.json();
    return data.choices?.[0]?.message?.content || conversationFallbackLine(params.lang, params.address);
  }

  // Streaming path — expo/fetch exposes a real ReadableStream body, same as claudeAgent.ts.
  const res = await expoFetch(OPENAI_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({ model, max_tokens: 600, messages, stream: true }),
  });
  if (!res.ok || !res.body) {
    return askOpenAI({ ...params, onSentence: undefined });
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let pending = '';
  let fullText = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const jsonStr = line.slice(6).trim();
      if (!jsonStr || jsonStr === '[DONE]') continue;
      let evt: any;
      try { evt = JSON.parse(jsonStr); } catch { continue; }
      const chunk: string | undefined = evt.choices?.[0]?.delta?.content;
      if (chunk) {
        fullText += chunk;
        pending += chunk;
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

export async function askOpenAIWithTools(params: {
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
  const model = params.model ?? GPT4O_MODEL;
  const headers = openaiHeaders(params.apiKey);
  const system = buildSystemPrompt(
    params.character, params.address, params.lang, params.facts,
    learnedContext, family, params.drivingContext ?? '', true,
  );
  const tools = toOpenAITools();

  // Non-streaming per iteration, exactly like askClaudeWithTools — real-time streaming only
  // matters for the final reply, which is manually sentence-split into onSentence below, same
  // pattern the Claude tool loop already uses.
  let messages: any[] = [
    { role: 'system', content: system },
    ...params.messages.map(m => ({ role: m.role, content: m.content })),
  ];

  for (let i = 0; i < MAX_TOOL_ITERATIONS; i++) {
    const res = await fetch(OPENAI_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify({ model, max_tokens: 600, messages, tools, tool_choice: 'auto' }),
    });
    // See askOpenAI's identical check above — confirmed live this was the actual cause of every
    // OpenAI-selected dialogue turn silently returning "I did not quite catch that" instead of a
    // real answer, with a 429 (quota/billing) hidden underneath. Throwing here lets
    // orchestrator.ts fall back to Claude instead of repeating the same dead-end reply forever.
    if (!res.ok) throw new Error(`OpenAI request failed: ${res.status}`);
    const data = await res.json();
    const message = data.choices?.[0]?.message;
    const toolCalls: any[] = message?.tool_calls ?? [];

    if (toolCalls.length === 0) {
      const text = message?.content || conversationFallbackLine(params.lang, params.address);
      if (params.onSentence) {
        for (const sentence of text.split(SENTENCE_SPLIT)) {
          if (sentence.trim()) params.onSentence(sentence.trim());
        }
      }
      return text;
    }

    messages = [...messages, message];

    for (const call of toolCalls) {
      let input: any = {};
      try { input = JSON.parse(call.function?.arguments || '{}'); } catch {}
      console.log('[OpenAIAgent]', 'tool_call', 'name=', call.function?.name, 'input=', JSON.stringify(input));
      const result = await executeTool(call.function?.name, input, params.toolContext);
      // Same early-return-on-governed-action rule as askClaudeWithTools (see tools.ts's
      // ToolOutcome doc comment) — relay the authoritative message verbatim instead of feeding
      // it back for another completion turn.
      if (typeof result !== 'string') {
        if (params.onSentence) {
          for (const sentence of result.text.split(SENTENCE_SPLIT)) {
            if (sentence.trim()) params.onSentence(sentence.trim());
          }
        }
        return result.text;
      }
      messages = [...messages, { role: 'tool', tool_call_id: call.id, content: result }];
    }
  }

  return conversationFallbackLine(params.lang, params.address);
}
