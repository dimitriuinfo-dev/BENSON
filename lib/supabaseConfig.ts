// Shared Supabase project constants — the analytics table and the llm-proxy Edge Function
// (lib/agents/claudeAgent.ts, lib/agents/noteRouterAgent.ts) both live in this one project.
export const SUPABASE_URL = 'https://jxsnxfnybwkhxjdwtlrm.supabase.co';
export const SUPABASE_ANON_KEY = 'sb_publishable_cueFh_JpbHHHW5pKFSNdkA_rK9o-Xfw';
export const LLM_PROXY_URL = `${SUPABASE_URL}/functions/v1/llm-proxy`;
