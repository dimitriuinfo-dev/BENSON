import { OPENAI_URL, openaiHeaders } from '../llmConfig';

export const NOTEPAD_PATTERN =
  /\b(?:notează|noteaza|note that|amintește-mi|aminteste-mi|remind me|spune-i lui|tell)\b|(?:adaugă|adauga|add)\s+.+\s+(?:pe listă|pe lista|to (?:the )?list)|(?:vreau să adaugi|vreau sa adaugi|please add).+(?:în app|in app|to the app)/i;

export type NoteAction = 'create_calendar' | 'set_reminder' | 'send_message' | 'add_todo' | 'save_feedback';
export type NoteType = 'calendar' | 'reminder' | 'message' | 'todo' | 'feedback';

export type ParsedNote = {
  type: NoteType;
  person: string | null;
  datetime: string | null; // ISO 8601 or null
  content: string;
  actions: NoteAction[];
  confidence: number; // 0-1, self-reported
};

const CONFIDENCE_THRESHOLD = 0.5;

function buildSystemPrompt(): string {
  return `You are BENSON's note-routing classifier. The user dictated a free-form voice note. ` +
    `Classify it and extract structured details. Respond with ONLY a single JSON object, no prose, ` +
    `no markdown fences, matching exactly this shape:\n` +
    `{\n` +
    `  "type": "calendar" | "reminder" | "message" | "todo" | "feedback",\n` +
    `  "person": string or null,\n` +
    `  "datetime": ISO 8601 string or null,\n` +
    `  "content": string,\n` +
    `  "actions": array of one or more of "create_calendar" | "set_reminder" | "send_message" | "add_todo" | "save_feedback",\n` +
    `  "confidence": number between 0 and 1\n` +
    `}\n` +
    `Categories: "calendar" = an appointment/event to schedule; "reminder" = remind the user to do ` +
    `something later; "message" = a note meant for a specific family member, optionally to be sent ` +
    `to them; "todo" = a shopping or to-do list item; "feedback" = a suggestion/complaint about the ` +
    `BENSON app itself, for the developer.\n` +
    `If a date/time is mentioned, resolve it to an absolute ISO 8601 datetime relative to now ` +
    `(${new Date().toISOString()}). If none is mentioned, use null.\n` +
    `If the command is ambiguous or doesn't clearly fit any category, still return your best-guess ` +
    `JSON but set "confidence" below 0.5.`;
}

// NoteRouterAgent — the one agent in this codebase that leads with the model instead of a regex:
// NOTEPAD_PATTERN only decides "this is a notepad-style command," the model does the
// classification, because free-form multi-intent dictation isn't something regex can reliably
// parse. ROUND_INPUT_ROUTING_1 (2026-09-23) — was Anthropic (claude-sonnet-5), independent of
// `ctx.modelProvider` and untouched by the OpenAI-exclusive-Brain decision until now; switched to
// the same OpenAI endpoint/key as the rest of BENSON's Brain — no other provider left reachable
// from ordinary conversation/command routing.
export async function runNoteRouterAgent(text: string, apiKey: string): Promise<ParsedNote | null> {
  try {
    const res = await fetch(OPENAI_URL, {
      method: 'POST',
      headers: openaiHeaders(apiKey),
      body: JSON.stringify({
        model: 'gpt-4o-mini',
        max_tokens: 400,
        messages: [
          { role: 'system', content: buildSystemPrompt() },
          { role: 'user', content: text },
        ],
      }),
    });
    const data = await res.json();
    const raw = data.choices?.[0]?.message?.content ?? '';
    const jsonMatch = raw.match(/\{[\s\S]*\}/);
    if (!jsonMatch) return null;
    return JSON.parse(jsonMatch[0]);
  } catch {
    return null;
  }
}

export function isConfident(note: ParsedNote): boolean {
  return note.confidence >= CONFIDENCE_THRESHOLD;
}
