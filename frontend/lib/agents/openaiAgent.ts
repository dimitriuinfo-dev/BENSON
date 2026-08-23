import { fetch as expoFetch } from 'expo/fetch';
import type { AnthropicMsg, Character, FamilyMember } from './types';
import { buildLearnedContext } from './learningAgent';
import { buildSystemPrompt } from './claudeAgent';
import { AGENT_TOOLS, executeTool, type ToolContext } from './tools';
import { OPENAI_URL, openaiHeaders } from '../llmConfig';

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
    const data = await res.json();
    return data.choices?.[0]?.message?.content || `I did not quite catch that, ${params.address}.`;
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
  return fullText || `I did not quite catch that, ${params.address}.`;
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
    const data = await res.json();
    const message = data.choices?.[0]?.message;
    const toolCalls: any[] = message?.tool_calls ?? [];

    if (toolCalls.length === 0) {
      const text = message?.content || `I did not quite catch that, ${params.address}.`;
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

  return `I'm having trouble completing that, ${params.address}.`;
}
