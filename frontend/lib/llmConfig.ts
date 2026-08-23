// Direct LLM provider endpoints. BENSON calls Anthropic / OpenAI directly from the device using the
// user's OWN key (entered in-app, stored on-device) — the previous Supabase Edge Function relay was
// removed because Emergent's mobile deploy stack (Expo + FastAPI + MongoDB) does not run Supabase
// Deno edge functions. Native fetch has no CORS restriction, so direct calls work fine on-device.

export const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages';
export const OPENAI_URL = 'https://api.openai.com/v1/chat/completions';
export const ANTHROPIC_VERSION = '2023-06-01';

export function anthropicHeaders(apiKey: string) {
  return {
    'Content-Type': 'application/json',
    'x-api-key': apiKey,
    'anthropic-version': ANTHROPIC_VERSION,
  };
}

export function openaiHeaders(apiKey: string) {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${apiKey}`,
  };
}
