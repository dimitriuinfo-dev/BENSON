// Task 1 — the registry that picks a concrete engine implementation from what's configured in
// Settings, so nuclei can be swapped without a new build. No provider is hardcoded anywhere below
// except as a documented DEFAULT id/fallback — the actual choice always comes from
// settingsStore.ts, which app/settings.tsx writes to.
import { localSttEngine } from '../agents/localWhisperEngine';
import { fetchWithTimeout } from '../agents/fetchWithTimeout';
import { logAudioDiag } from 'benson-foreground-service';
import { getEngineConfig, getSelectedEngineId } from './settingsStore';
import { createGroqSttEngine } from './stt/groqStt';
import { createOpenAiCompatibleBrain } from './llm/openAiCompatibleBrain';
import { createAndroidTtsVoice } from './tts/androidTts';
import type { EngineConfig, LlmBrain, SttEngine, TtsVoice } from './types';

export const STT_ENGINE_IDS = ['groq', 'local'] as const;
export const LLM_ENGINE_IDS = ['openai-compatible'] as const;
export const TTS_ENGINE_IDS = ['android'] as const;

export const DEFAULT_STT_ENGINE_ID = 'groq';
export const DEFAULT_LLM_ENGINE_ID = 'openai-compatible';
export const DEFAULT_TTS_ENGINE_ID = 'android';

// Build B — when no dedicated CREIER engine is configured, the conversation brain reuses the Groq
// key (Groq's OpenAI-compatible /chat/completions serves these models). One key in Settings powers
// both STT and the brain; no separate CREIER UI needed for the common case.
export const GROQ_BRAIN_DEFAULT_BASE_URL = 'https://api.groq.com/openai/v1';
// 2026-08-28: on this account BOTH `llama-3.3-70b-versatile` AND `llama-3.1-8b-instant` returned
// HTTP 404 `model_not_found` (confirmed live in LLM_ERROR). No hardcoded model id is trustworthy,
// so resolveLlmBrain() below no longer trusts one blindly: it does a GET /models with the saved
// key, logs the full list once (LLM_MODELS), and picks the first entry the account actually
// exposes from GROQ_BRAIN_MODEL_PREFERENCE (or the first non-audio model otherwise). This
// constant is only the last-resort value if that discovery call itself fails (offline, etc.).
export const GROQ_BRAIN_DEFAULT_MODEL = 'llama-3.1-8b-instant';

// Preference order among Groq's chat models — the first one present in the live /models response
// wins. Best-first: a bigger general model makes a better butler brain than a small/instant one.
// 2026-08-29: this account's real /models list (logged live) had NONE of the llama-3.x ids and
// exposed openai/gpt-oss-{20b,120b}, groq/compound*, qwen/qwen3.*-27b, allam-2-7b — so gpt-oss-120b
// is the top realistic pick here, with 20b right behind. Anything not listed still gets caught by
// the "first non-audio id" fallback in discoverGroqBrainModel().
const GROQ_BRAIN_MODEL_PREFERENCE = [
  'llama-3.3-70b-versatile',
  'llama-3.1-70b-versatile',
  'openai/gpt-oss-120b',
  'openai/gpt-oss-20b',
  'qwen/qwen3-32b',
  'qwen/qwen3.8-27b',
  'qwen/qwen3.6-27b',
  'llama3-70b-8192',
  'llama-3.1-8b-instant',
  'llama3-8b-8192',
  'gemma2-9b-it',
  'deepseek-r1-distill-llama-70b',
];

export type LlmDiscoveryCause = 'ok' | 'key' | 'network' | 'model';
type LlmDiscoveryResult = { model: string; cause: 'ok' } | { model: null; cause: 'key' | 'network' | 'model' };

// Cached for the app session — one GET /models per launch, not per brain call. Once a model is
// resolved it is reused for the whole session; a hard failure is also cached (with its cause) so a
// broken key/network doesn't trigger a fresh GET on every single utterance.
let discovered: LlmDiscoveryResult | null = null;

// GET {baseUrl}/models with the saved key; pick a chat model the account can actually use.
// Never throws. `cause` tells the caller WHY it failed so the user hears the real reason:
//   key     -> HTTP 401/403 (the Groq key is rejected)
//   network -> request threw / timed out (no connectivity)
//   model   -> reached Groq, but the account exposes no usable chat model
async function discoverGroqBrainModel(baseUrl: string, apiKey: string): Promise<LlmDiscoveryResult> {
  if (discovered) return discovered;
  let res: Response;
  try {
    res = await fetchWithTimeout(
      `${baseUrl.replace(/\/+$/, '')}/models`,
      { headers: { Authorization: `Bearer ${apiKey}` } },
      15000,
    );
  } catch (e) {
    logAudioDiag('LLM_MODELS', `error=network detail=${JSON.stringify(String(e)).slice(0, 120)}`);
    discovered = { model: null, cause: 'network' };
    return discovered;
  }
  if (!res.ok) {
    const cause: LlmDiscoveryCause = res.status === 401 || res.status === 403 ? 'key' : 'model';
    logAudioDiag('LLM_MODELS', `error=${cause} http=${res.status}`);
    discovered = { model: null, cause: cause === 'key' ? 'key' : 'model' };
    return discovered;
  }
  const body = await res.json().catch(() => null);
  const ids: string[] = Array.isArray(body?.data)
    ? body.data.map((m: { id?: unknown }) => String(m?.id ?? '')).filter(Boolean)
    : [];
  logAudioDiag('LLM_MODELS', `count=${ids.length} list=${JSON.stringify(ids.join(','))}`);
  const isChatModel = (id: string) => !/whisper|tts|guard|embed|distil-whisper|playai|orpheus|compound/i.test(id);
  const chosen = GROQ_BRAIN_MODEL_PREFERENCE.find((p) => ids.includes(p)) ?? ids.find(isChatModel) ?? null;
  if (!chosen) {
    logAudioDiag('LLM_MODELS', `error=model count=${ids.length}`);
    discovered = { model: null, cause: 'model' };
    return discovered;
  }
  logAudioDiag('LLM_MODELS', `chosen=${chosen}`);
  discovered = { model: chosen, cause: 'ok' };
  return discovered;
}

// Last brain-model discovery cause this session — read by the conversation layer to phrase a
// failure ("cheia Groq" / "conexiune" / "modelul") instead of a generic line. 'ok' until a
// resolveLlmBrain() call actually attempts discovery.
export function lastLlmDiscoveryCause(): LlmDiscoveryCause {
  return discovered?.cause ?? 'ok';
}

// Task 2: Groq is the default/implicit STT tier; `local` remains the explicit offline-only
// reserve. Falls back to `local` when Groq is selected but has no apiKey configured yet, rather
// than throwing — BENSON should never go silent for a config gap it can route around.
export async function resolveSttEngine(): Promise<SttEngine> {
  const id = await getSelectedEngineId('stt', DEFAULT_STT_ENGINE_ID);
  if (id === 'local') return localSttEngine;
  const config = await getEngineConfig('stt', id);
  if (!config) return localSttEngine;
  return createGroqSttEngine(config);
}

// PHASE_A_PROTOCOL_AND_TIMEOUT (2026-09-19) — extracted out of resolveLlmBrain() so the Settings
// TEST LLM button (app/index.tsx) can show the resolved provider/endpoint/model and run
// testLlmConnection() against the SAME resolution resolveLlmBrain() itself uses, instead of
// re-implementing this fallback chain a second time. No behavior change to resolveLlmBrain() —
// it now just calls this and wraps the result.
export type ResolvedLlmConfigSource = 'creier' | 'groq_reuse';
export async function resolveLlmConfig(): Promise<{ config: EngineConfig; source: ResolvedLlmConfigSource } | null> {
  const id = await getSelectedEngineId('llm', DEFAULT_LLM_ENGINE_ID);
  const dedicated = await getEngineConfig('llm', id);
  if (dedicated) return { config: dedicated, source: 'creier' };
  // No dedicated CREIER config — fall back to the Groq key (see the defaults above). The model is
  // discovered from the live /models list rather than hardcoded, because known-good ids have
  // 404'd on this account.
  const groq = await getEngineConfig('stt', 'groq');
  if (!groq?.apiKey) return null;
  const baseUrl = groq.baseUrl || GROQ_BRAIN_DEFAULT_BASE_URL;
  const disc = await discoverGroqBrainModel(baseUrl, groq.apiKey);
  // Even on a discovery failure, still return a config (with the last-resort id) so the chat call
  // runs and surfaces the real HTTP error — lastLlmDiscoveryCause() carries the reason for the
  // conversation layer to phrase.
  return { config: { apiKey: groq.apiKey, baseUrl, model: disc.model ?? GROQ_BRAIN_DEFAULT_MODEL }, source: 'groq_reuse' };
}

// Returns null when no LLM engine is configured yet (no apiKey saved) — callers decide what to do
// with "no brain configured" (e.g. the Settings TEST button just reports it, rather than this
// module inventing a fallback provider).
export async function resolveLlmBrain(lang: string = 'ro'): Promise<LlmBrain | null> {
  const resolved = await resolveLlmConfig();
  if (!resolved) return null;
  return createOpenAiCompatibleBrain(resolved.config, lang);
}

export async function resolveTtsVoice(): Promise<TtsVoice> {
  // Only one TTS implementation exists this round (Task 6) — the id is still read from Settings
  // so a second implementation can be added later without touching this function's callers.
  await getSelectedEngineId('tts', DEFAULT_TTS_ENGINE_ID);
  return createAndroidTtsVoice();
}
