// Pure credential-injecting relay for BENSON's chat calls — Claude and OpenAI both go through here so
// neither key ever lives in the client. Supabase's platform gateway already rejects requests without a
// valid apikey/Authorization (the shipped anon key) before this code runs, so no auth check needed here.
//
// Request body: { provider: 'anthropic' | 'openai', ...rest-of-provider-native-body }
// The 'provider' field is stripped; everything else is forwarded verbatim to the real API, and the
// response body is piped straight back (new Response(upstream.body, ...)) so streaming survives.
//
// Deploy: npx supabase functions deploy llm-proxy
// Secrets: npx supabase secrets set ANTHROPIC_API_KEY=... OPENAI_API_KEY=...

const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages';
const OPENAI_URL = 'https://api.openai.com/v1/chat/completions';

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') {
    return new Response('Method not allowed', { status: 405 });
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: 'invalid JSON body' }), { status: 400 });
  }

  const { provider, ...rest } = body;

  if (provider === 'anthropic') {
    const apiKey = Deno.env.get('ANTHROPIC_API_KEY');
    if (!apiKey) return new Response(JSON.stringify({ error: 'proxy misconfigured: no anthropic key' }), { status: 500 });

    const upstream = await fetch(ANTHROPIC_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify(rest),
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json' },
    });
  }

  if (provider === 'openai') {
    const apiKey = Deno.env.get('OPENAI_API_KEY');
    if (!apiKey) return new Response(JSON.stringify({ error: 'proxy misconfigured: no openai key' }), { status: 500 });

    const upstream = await fetch(OPENAI_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(rest),
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json' },
    });
  }

  return new Response(JSON.stringify({ error: 'unknown provider, expected anthropic|openai' }), { status: 400 });
});
