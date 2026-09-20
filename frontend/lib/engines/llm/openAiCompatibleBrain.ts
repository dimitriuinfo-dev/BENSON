// Task 3 — any OpenAI-/chat-completions-compatible endpoint (Groq, OpenRouter, a self-hosted
// server, etc.) as BENSON's "CREIER" (brain) nucleus. baseUrl, model, and apiKey come exclusively
// from Settings (see app/settings.tsx) — nothing here is hardcoded to one provider. No streaming
// this round: one complete response, then TTS.
import { fetchWithTimeout } from '../../agents/fetchWithTimeout';
import { logAudioDiag } from 'benson-foreground-service';
import { conversationFallbackLine } from '../../agents/fallbackLine';
import { composeWireMessages, parseBrainOutput } from './messageChannels';
import type { BrainOutput, ChatOpts, EngineConfig, LlmBrain, SystemTurn, Turn } from '../types';

// PHASE_A_PROTOCOL_AND_TIMEOUT (2026-09-19, product-owner-directed) — was 30000 with one retry on
// network failure (60s worst case — exactly what hung "intră în Netflix" for 60s on a Groq
// outage, forensically proven in scratchpad/abba_netflix_dump.txt). Per the round's explicit
// spec: a 3s hard ceiling, NO retry — a timeout must surface fast enough that BENSON can say "Nu
// am înțeles" and re-listen inside the same active session, never a second 30-60s wait.
//
// Late-response safety (verified, not assumed, before this edit): fetchWithTimeout.ts already
// races the real fetch() against a promise that rejects the INSTANT the native watchdog fires
// (CLOUD_FETCH_RACE_1, 2026-09-18) — this function's own `await` therefore always settles at the
// timeout, full stop, regardless of whether the underlying fetch ever notices the abort(). If the
// underlying fetch DOES resolve later, nothing here ever awaits or reads it again — no shared
// mutable state, no cache, no callback captures it — so a late response cannot retroactively
// speak, change session state, or execute anything. No new invalidation plumbing was needed.
const REQUEST_TIMEOUT_MS = 3000;
const MAX_OUTPUT_TOKENS = 250;

type WireMessage = { role: string; content: string };

// Resolved chat/completions URL. Trailing slashes on baseUrl are stripped — a stored
// "https://api.groq.com/openai/v1/" would otherwise produce a double-slash path that some
// gateways answer with a 404 that looks exactly like a missing-model 404.
export function chatCompletionsUrl(baseUrl: string): string {
  return `${(baseUrl || '').replace(/\/+$/, '')}/chat/completions`;
}

async function postChatCompletion(config: EngineConfig, messages: WireMessage[], opts?: ChatOpts): Promise<Response> {
  const body = JSON.stringify({
    model: config.model,
    messages,
    temperature: opts?.temperature ?? 0.4,
    max_tokens: MAX_OUTPUT_TOKENS,
  });
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${config.apiKey}` };
  const url = chatCompletionsUrl(config.baseUrl);
  // Diagnosis line (2026-08-28) — the resolved URL and model, never the key (it is only in the
  // Authorization header, never in the URL). Distinguishes an address 404 from a model 404.
  logAudioDiag('LLM_ENDPOINT', `url=${url} model=${config.model}`);
  // PHASE_A_PROTOCOL_AND_TIMEOUT — the automatic retry-on-network-failure that used to live here
  // is REMOVED. One OpenAI/CREIER request per user turn, maximum — see the constant doc above.
  return fetchWithTimeout(url, { method: 'POST', headers, body }, REQUEST_TIMEOUT_MS);
}

// Names the actual cause — key / connection / model / rate-limit — instead of a bare code, so the
// user knows what to fix. `detail` is either "HTTP <status>" (a real response) or a transport
// error string (fetchWithTimeout's "Request timed out …" or the platform's network message).
function errorMessage(lang: string, detail: string): string {
  const ro = lang.startsWith('ro');
  const httpMatch = /HTTP (\d{3})/.exec(detail);
  const status = httpMatch ? Number(httpMatch[1]) : 0;
  if (!status) {
    // no HTTP response at all -> transport
    return ro
      ? 'Nu am ajuns la creierul BENSON — pare o problemă de conexiune la internet.'
      : "Couldn't reach BENSON's brain — looks like a network/connection problem.";
  }
  if (status === 401 || status === 403) {
    return ro
      ? 'Cheia Groq pare invalidă sau fără acces — verific-o în Setări.'
      : 'The Groq key looks invalid or unauthorized — check it in Settings.';
  }
  if (status === 404) {
    return ro
      ? 'Modelul de conversație nu e disponibil pe acest cont Groq.'
      : "The chat model isn't available on this Groq account.";
  }
  if (status === 429) {
    return ro
      ? 'Groq e limitat temporar (prea multe cereri) — încearcă din nou în câteva minute.'
      : 'Groq is rate-limited right now — try again in a few minutes.';
  }
  return ro
    ? `Nu am putut contacta creierul BENSON acum (HTTP ${status}).`
    : `Couldn't reach BENSON's brain right now (HTTP ${status}).`;
}

// params logged: never the conversation content, never the key (ground rule 5) — only shape.
export async function chatOpenAiCompatible(
  system: SystemTurn,
  history: readonly Turn[],
  config: EngineConfig,
  opts?: ChatOpts,
  lang: string = 'ro',
): Promise<BrainOutput> {
  const messages = composeWireMessages(system, history);
  const promptChars = messages.reduce((n, m) => n + m.content.length, 0);
  logAudioDiag('LLM_REQUEST', `model=${config.model} promptChars=${promptChars}`);
  const startedAt = Date.now();

  let res: Response;
  try {
    res = await postChatCompletion(config, messages, opts);
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e);
    logAudioDiag('LLM_RESULT', `elapsedMs=${Date.now() - startedAt} chars=0 error="network"`);
    // Build B — a transport failure THROWS (rather than returning a spoken error), so the caller
    // (brainRouter.routeThroughBrain) treats it as "brain unavailable" and falls back to the
    // deterministic / existing provider path with its single fallback line, per Round 2 TASK 1.3.
    throw new Error(errorMessage(lang, detail));
  }
  if (!res.ok) {
    // Error body — truncated, and with any bearer token scrubbed (the body never normally carries
    // the key, but a mirrored request header in some proxies' error JSON could). Tells apart
    // "model does not exist / no access" from an address/auth problem.
    const rawBody = await res.text().catch(() => '');
    const body = rawBody.replace(/Bearer\s+[A-Za-z0-9._-]+/g, 'Bearer •••').slice(0, 400);
    logAudioDiag('LLM_ERROR', `status=${res.status} body=${JSON.stringify(body)}`);
    logAudioDiag('LLM_RESULT', `elapsedMs=${Date.now() - startedAt} chars=0 error=${res.status}`);
    throw new Error(errorMessage(lang, `HTTP ${res.status}`));
  }

  const data = await res.json();
  const content: string = data.choices?.[0]?.message?.content ?? '';
  logAudioDiag('LLM_RESULT', `elapsedMs=${Date.now() - startedAt} chars=${content.length}`);

  let parsedJson: unknown = null;
  try { parsedJson = JSON.parse(content); } catch { /* not JSON at all — handled below */ }
  const output = parseBrainOutput(parsedJson);
  if (output) return output;

  // Task 4.4 — the raw response is data, not authority: it never becomes an action just because
  // it looked confident. Anything that isn't a valid BrainOutput is spoken as plain text instead
  // of silently dropped (parseBrainOutput already logged BRAIN_REJECTED for the JSON-shaped-but-
  // invalid case above; this branch also covers "not JSON at all").
  return {
    kind: 'speak',
    text: content || conversationFallbackLine(lang),
  };
}

export function createOpenAiCompatibleBrain(config: EngineConfig, lang: string = 'ro'): LlmBrain {
  return {
    id: 'openai-compatible',
    chat(turns: Turn[], opts?: ChatOpts) {
      const system = turns.find((t): t is SystemTurn => t.role === 'SYSTEM');
      if (!system) throw new Error('chat() requires a SYSTEM turn built via buildSystemTurn()');
      const history = turns.filter((t) => t.role !== 'SYSTEM');
      return chatOpenAiCompatible(system, history, config, opts, lang);
    },
  };
}

// Settings "TEST LLM" button — a minimal real call, not a full BrainOutput round trip: just
// confirms baseUrl/model/apiKey actually reach a working chat/completions endpoint. Never reads
// or returns the key/Authorization header — only status/errorCode/latency, for the button to
// classify and display (SUCCESS/TIMEOUT/AUTH_ERROR/CREDIT_ERROR/NETWORK_ERROR/MODEL_ERROR).
// PHASE_A_PROTOCOL_AND_TIMEOUT (2026-09-19) — extended return shape (was {ok:true}|{ok:false,
// error:string}, zero existing callers, so widening it here is a safe no-op for anything else).
export type LlmConnectionTestResult =
  | { ok: true; status: number; latencyMs: number }
  | { ok: false; status?: number; errorCode?: string; error: string; latencyMs: number; timedOut: boolean };

export async function testLlmConnection(config: EngineConfig): Promise<LlmConnectionTestResult> {
  const startedAt = Date.now();
  try {
    const res = await postChatCompletion(config, [{ role: 'user', content: 'ping' }]);
    const latencyMs = Date.now() - startedAt;
    if (!res.ok) {
      // OpenAI-shaped error body: {"error":{"message":"...","type":"...","code":"insufficient_quota"}}
      // — read only `code` (never the message, which could echo request content back).
      const body = await res.json().catch(() => null);
      const errorCode = typeof body?.error?.code === 'string' ? body.error.code : undefined;
      return { ok: false, status: res.status, errorCode, error: `HTTP ${res.status}`, latencyMs, timedOut: false };
    }
    return { ok: true, status: res.status, latencyMs };
  } catch (e) {
    const latencyMs = Date.now() - startedAt;
    const message = e instanceof Error ? e.message : String(e);
    return { ok: false, error: message, latencyMs, timedOut: /timed out/i.test(message) };
  }
}
