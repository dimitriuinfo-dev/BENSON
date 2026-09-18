// Analytics events are sent to BENSON's own FastAPI backend (MongoDB-backed). The previous Supabase
// Postgres (PostgREST) path was removed because Emergent's mobile deploy stack is Expo + FastAPI +
// MongoDB and does not run Supabase. Best-effort and privacy-gated upstream (eventLog only calls
// this with explicit consent): no-ops silently if no backend URL is configured (e.g. local dev).

export type AnalyticsEvent = {
  install_id: string;
  command_type: string;
  agent_used: string;
  success: boolean;
  language: string;
  app_opened?: string;
  session_duration_seconds?: number;
  error_type?: string;
};

const BACKEND_URL = process.env.EXPO_PUBLIC_BACKEND_URL;

export async function insertEvents(events: AnalyticsEvent[]): Promise<boolean> {
  if (!BACKEND_URL) return false;
  try {
    const res = await fetch(`${BACKEND_URL}/api/analytics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ events }),
    });
    return res.ok;
  } catch {
    return false;
  }
}
