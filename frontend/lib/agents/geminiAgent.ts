import type { AnthropicMsg, Character, FamilyMember } from './types';
import { buildLearnedContext } from './learningAgent';
import { buildSystemPrompt } from './claudeAgent';
import { AGENT_TOOLS, executeTool, type ToolContext } from './tools';
import { logAudioDiag } from 'benson-foreground-service';
import { fetchWithTimeout } from './fetchWithTimeout';
import { conversationFallbackLine } from './fallbackLine';

// See fetchWithTimeout.ts's doc comment — a plain fetch() with no timeout can hang forever,
// silently freezing the conversation loop with no error and no fallback ever triggering.
const REQUEST_TIMEOUT_MS = 20000;

// Gemini as a third chat-model option (product-owner-directed 2026-08-25), alongside Claude
// (claudeAgent.ts) and OpenAI (openaiAgent.ts). Same buildSystemPrompt persona, same executeTool()
// — only the wire format and provider differ. Uses the classic models/{model}:generateContent REST
// endpoint (not the newer Interactions API) for consistency with geminiSTT.ts and because it's the
// long-documented, stable surface. gemini-3.7-flash — see geminiSTT.ts's model-choice comment:
// gemini-2.5/2.0/1.5-flash all 404'd live against a real key (confirmed via Google's own error
// body: "not found for API version v1beta... or not supported for generateContent" — these older
// generations are apparently retired as of this date), 3.7-flash is the current documented stable
// Flash model.
const GEMINI_MODEL = 'gemini-3.7-flash';
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;

const SENTENCE_SPLIT = /(?<=[.!?…])\s+/;
// Matches askClaudeWithTools/askOpenAIWithTools — a real phone-operator task needs several
// readScreen/act cycles; the loop still exits as soon as the model stops requesting tools.
const MAX_TOOL_ITERATIONS = 10;

// Gemini's function-declaration schema is a restricted subset of JSON Schema (OpenAPI-3.0-style)
// — confirmed live 2026-08-25 that `additionalProperties` (used by tools.ts's fillForm tool, e.g.
// `{ type: 'object', additionalProperties: { type: 'string' } }`) makes EVERY chat request fail
// with a 400 INVALID_ARGUMENT ("Unknown name \"additionalProperties\"... Cannot find field"),
// silently killing every Gemini-selected conversation turn regardless of what the user actually
// asked. Recursively strips unsupported keywords before sending, rather than editing tools.ts's
// shared schema (which Claude/OpenAI both accept fine as-is).
const UNSUPPORTED_SCHEMA_KEYS = new Set(['additionalProperties', '$schema', 'default']);
function sanitizeSchemaForGemini(schema: any): any {
  if (Array.isArray(schema)) return schema.map(sanitizeSchemaForGemini);
  if (schema && typeof schema === 'object') {
    const out: any = {};
    for (const [key, value] of Object.entries(schema)) {
      if (UNSUPPORTED_SCHEMA_KEYS.has(key)) continue;
      out[key] = sanitizeSchemaForGemini(value);
    }
    return out;
  }
  return schema;
}

function toGeminiTools() {
  return [{
    functionDeclarations: AGENT_TOOLS.map(t => ({
      name: t.name,
      description: t.description,
      parameters: sanitizeSchemaForGemini(t.input_schema),
    })),
  }];
}

// Gemini has no separate system-message role — the persona goes in a dedicated
// `systemInstruction` field, and conversation history maps 'assistant' -> 'model' (Gemini's own
// role name), not the Anthropic/OpenAI 'assistant'.
function toGeminiContents(messages: { role: string; content: any }[]) {
  return messages.map(m => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: typeof m.content === 'string' ? [{ text: m.content }] : m.content,
  }));
}

export async function askGeminiWithTools(params: {
  apiKey: string;
  character: Character;
  address: string;
  lang: string;
  facts: string[];
  messages: AnthropicMsg[];
  family?: FamilyMember[];
  drivingContext?: string;
  onSentence?: (sentence: string) => void;
  toolContext: ToolContext;
}): Promise<string> {
  const family = params.family ?? [];
  const learnedContext = buildLearnedContext(params.messages, family.map(f => f.name));
  const system = buildSystemPrompt(
    params.character, params.address, params.lang, params.facts,
    learnedContext, family, params.drivingContext ?? '', true,
  );
  const tools = toGeminiTools();

  let contents: any[] = toGeminiContents(params.messages);

  for (let i = 0; i < MAX_TOOL_ITERATIONS; i++) {
    const res = await fetchWithTimeout(GEMINI_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': params.apiKey },
      body: JSON.stringify({
        contents,
        tools,
        toolConfig: { functionCallingConfig: { mode: 'AUTO' } },
        systemInstruction: { parts: [{ text: system }] },
        generationConfig: { maxOutputTokens: 600 },
      }),
    }, REQUEST_TIMEOUT_MS);
    // Mirrors openaiAgent.ts's identical check — a failed request (invalid key, model not
    // enabled, quota) must not silently produce the generic "I did not quite catch that" text;
    // throwing lets orchestrator.ts fall back to Claude instead.
    if (!res.ok) {
      const bodyText = await res.text().catch(() => '');
      logAudioDiag('GEMINI_CHAT_ERROR_BODY', `status=${res.status} body=${bodyText.slice(0, 400)}`);
      throw new Error(`Gemini request failed: ${res.status}`);
    }
    const data = await res.json();
    logAudioDiag('GEMINI_CHAT_RESPONSE', JSON.stringify(data).slice(0, 500));
    const parts: any[] = data.candidates?.[0]?.content?.parts ?? [];
    const functionCalls = parts.filter(p => p.functionCall);

    if (functionCalls.length === 0) {
      const text = parts.map(p => p.text).filter(Boolean).join(' ') || conversationFallbackLine(params.lang, params.address);
      if (params.onSentence) {
        for (const sentence of text.split(SENTENCE_SPLIT)) {
          if (sentence.trim()) params.onSentence(sentence.trim());
        }
      }
      return text;
    }

    contents = [...contents, { role: 'model', parts }];

    const responseParts: any[] = [];
    for (const p of functionCalls) {
      const { name, args } = p.functionCall;
      console.log('[GeminiAgent]', 'function_call', 'name=', name, 'args=', JSON.stringify(args));
      const result = await executeTool(name, args, params.toolContext);
      // Same early-return-on-governed-action rule as askClaudeWithTools/askOpenAIWithTools (see
      // tools.ts's ToolOutcome doc comment) — relay the authoritative message verbatim instead of
      // feeding it back for another completion turn.
      if (typeof result !== 'string') {
        if (params.onSentence) {
          for (const sentence of result.text.split(SENTENCE_SPLIT)) {
            if (sentence.trim()) params.onSentence(sentence.trim());
          }
        }
        return result.text;
      }
      responseParts.push({ functionResponse: { name, response: { result } } });
    }
    contents = [...contents, { role: 'user', parts: responseParts }];
  }

  return conversationFallbackLine(params.lang, params.address);
}
