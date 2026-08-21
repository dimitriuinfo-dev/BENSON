// Raw fetch against Supabase's PostgREST layer — no @supabase/supabase-js, matching every other
// external API in this app (Anthropic, Tavily, open-meteo, Nominatim, YouTube are all plain fetch).
//
// One-time setup required in the Supabase SQL Editor for this project:
//
//   create table analytics_events (
//     id uuid default gen_random_uuid() primary key,
//     install_id uuid not null,
//     command_type text,
//     agent_used text,
//     success boolean,
//     language text,
//     app_opened text,
//     session_duration_seconds integer,
//     error_type text,
//     created_at timestamptz default now()
//   );
//   alter table analytics_events enable row level security;
//   create policy "anon can insert" on analytics_events for insert to anon with check (true);

import { SUPABASE_URL, SUPABASE_ANON_KEY as SUPABASE_KEY } from '../supabaseConfig';

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

export async function insertEvents(events: AnalyticsEvent[]): Promise<boolean> {
  try {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/analytics_events`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        apikey: SUPABASE_KEY,
        Authorization: `Bearer ${SUPABASE_KEY}`,
        Prefer: 'return=minimal',
      },
      body: JSON.stringify(events),
    });
    return res.ok;
  } catch {
    return false;
  }
}
