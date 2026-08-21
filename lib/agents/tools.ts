import { Linking } from 'react-native';
import * as Location from 'expo-location';
import { launchApp } from './appLauncherAgent';
import { fillForm, isServiceEnabled } from 'benson-accessibility';
import { getLastScreenSnapshot } from '../screenBridge';
import { buildActionRequest, execute as executeGoverned } from '../../src/core/mission';
import { tavilySearch } from './searchAgent';
import { createCalendarEvent } from '../notepad/actions';
import type { ParsedNote } from './noteRouterAgent';

// Wraps a governed (src/core/mission) action's own user-facing message so the agency loop
// (askClaudeWithTools) relays it verbatim instead of feeding it back to Claude for a second
// completion turn — confirmed live 2026-07-17: letting Claude "confirm the outcome in its own
// words" for these actions produced a reply ("...trebuie doar tu să apeși trimite") that
// misdescribed what the governed system had actually done/was asking for. The governed message
// is already the authoritative, honest, exact string (same one the deterministic Mission
// Orchestrator path shows) — nothing downstream should paraphrase it.
export type ToolOutcome = string | { text: string; final: true };
function finalResult(text: string): ToolOutcome {
  return { text, final: true };
}

// Tool definitions for Claude's tool-use loop (lib/agents/claudeAgent.ts → askClaudeWithTools).
// Each tool is a thin wrapper around a module that already exists and is exercised by the
// regex-based orchestrator — this just gives Claude itself a way to reach the same actions
// when a command's phrasing doesn't match any of the orchestrator's patterns.
export const AGENT_TOOLS = [
  {
    name: 'openApp',
    description:
      'Opens an app on the phone by name (e.g. "WhatsApp", "Netflix", "Uber"), optionally with a ' +
      'search query pre-filled inside it. Use this whenever the user asks to open, launch, start, ' +
      'or switch to an app — never claim you cannot open apps.',
    input_schema: {
      type: 'object',
      properties: {
        appName: { type: 'string', description: 'Name of the app to open.' },
        query: { type: 'string', description: 'Optional search text to pre-fill once the app opens.' },
      },
      required: ['appName'],
    },
  },
  {
    name: 'callContact',
    description: 'Calls a person from the phone\'s contacts by name. Use this when the user asks to call or phone someone.',
    input_schema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'The contact name to call.' },
      },
      required: ['name'],
    },
  },
  {
    name: 'sendWhatsApp',
    description:
      'Opens WhatsApp (falling back to SMS) with a message pre-filled to a named contact. The user ' +
      'still has to tap send themselves — this never sends automatically. Use this when the user asks ' +
      'to message, text, or WhatsApp someone.',
    input_schema: {
      type: 'object',
      properties: {
        person: { type: 'string', description: 'The contact name to message.' },
        message: { type: 'string', description: 'The message text to pre-fill.' },
      },
      required: ['person', 'message'],
    },
  },
  {
    name: 'readScreen',
    description:
      'Reads the most recent snapshot of whatever screen is currently open on the phone (requires ' +
      'the Accessibility Service, enabled from Settings). Use this when the user asks what is on ' +
      'screen right now, or before calling fillForm.',
    input_schema: { type: 'object', properties: {} },
  },
  {
    name: 'fillForm',
    description:
      'Fills editable fields on the current on-screen form using label-to-value pairs (requires the ' +
      'Accessibility Service). Never fills payment, card, or 2FA fields — those are always left to ' +
      'the user.',
    input_schema: {
      type: 'object',
      properties: {
        fields: {
          type: 'object',
          description: 'Map of field label (or partial label) to the value to type into it, e.g. {"email": "x@y.com"}.',
          additionalProperties: { type: 'string' },
        },
      },
      required: ['fields'],
    },
  },
  {
    name: 'startCarMode',
    description: 'Switches the app into Car Mode, the large hands-free driving UI. Use when the user says they are driving or asks for car/driving mode.',
    input_schema: { type: 'object', properties: {} },
  },
  {
    name: 'getLocation',
    description:
      'Gets the phone\'s current GPS location (city/area name and coordinates). Use this whenever ' +
      'the user asks where they are, or a location-relative question ("how far is home from here", ' +
      '"what\'s nearby") that NAV_PATTERN-style navigation doesn\'t cover. Never say you have no GPS ' +
      'access — call this instead.',
    input_schema: { type: 'object', properties: {} },
  },
  {
    name: 'getCurrentDateTime',
    description:
      'Gets the phone\'s current date and time (device clock, correct timezone). Use this for ' +
      'ANY question about the current time, date, or day of the week ("what time is it", "what\'s ' +
      'today\'s date", "what day is it"). Never say you don\'t have access to the time or need the ' +
      'user to tell you — call this instead.',
    input_schema: { type: 'object', properties: {} },
  },
  {
    name: 'setAlarm',
    description:
      'Sets a device alarm for the given time — no confirmation needed, just set it. Use for any ' +
      '"wake me up at X", "set an alarm for X", "alarm at X" phrasing, in any language. Extract the ' +
      'hour (0-23) and minutes (0-59) yourself from whatever the user said.',
    input_schema: {
      type: 'object',
      properties: {
        hour: { type: 'number', description: 'Hour in 24h format, 0-23.' },
        minutes: { type: 'number', description: 'Minutes, 0-59. Default 0 if not specified.' },
      },
      required: ['hour'],
    },
  },
  {
    name: 'web_search',
    description:
      'Searches the web for current or time-sensitive information (exchange rates, prices, news, ' +
      'facts you\'re not confident about). Use this for anything you can\'t answer confidently ' +
      'yourself — never say you don\'t have internet access, call this instead.',
    input_schema: {
      type: 'object',
      properties: { query: { type: 'string', description: 'What to search for.' } },
      required: ['query'],
    },
  },
  {
    name: 'navigate',
    description:
      'Starts turn-by-turn navigation (via Waze) to a destination. Use whenever the user asks to be ' +
      'taken, driven, or navigated somewhere, or to go to an address or place — never claim you ' +
      'cannot navigate.',
    input_schema: {
      type: 'object',
      properties: { destination: { type: 'string', description: 'Address or place name to navigate to.' } },
      required: ['destination'],
    },
  },
  {
    name: 'createCalendarEvent',
    description:
      'Opens Google Calendar with a new event pre-filled for the user to review and save — Benson ' +
      'never writes to the calendar directly. Use when the user asks to schedule something, add it ' +
      'to their calendar, or set up a meeting/appointment.',
    input_schema: {
      type: 'object',
      properties: {
        title: { type: 'string', description: 'Event title or description.' },
        datetime: { type: 'string', description: 'ISO 8601 date-time for the event start, if a specific time was given. Omit otherwise.' },
      },
      required: ['title'],
    },
  },
  {
    name: 'remember',
    description:
      'Saves a fact about the user for future conversations (preferences, ongoing situations, things ' +
      'they tell you to remember). Use when the user explicitly asks you to remember something, or ' +
      'shares something clearly worth recalling later.',
    input_schema: {
      type: 'object',
      properties: { fact: { type: 'string', description: 'The fact to remember, written concisely in the third person.' } },
      required: ['fact'],
    },
  },
  {
    name: 'recall',
    description:
      'Looks up facts Benson has previously remembered about the user, optionally filtered by topic. ' +
      'Use when the user asks what you remember or know about something.',
    input_schema: {
      type: 'object',
      properties: { topic: { type: 'string', description: 'Optional keyword to filter remembered facts by.' } },
    },
  },
] as const;

export type ToolContext = {
  address: string;
  onStartCarMode?: () => void;
  tavilyKey?: string;
  facts?: string[];
  onRememberFact?: (fact: string) => Promise<void> | void;
};

export async function executeTool(toolName: string, input: any, ctx: ToolContext): Promise<ToolOutcome> {
  switch (toolName) {
    case 'openApp': {
      // Waze/WhatsApp are governed end-to-end by src/core/mission (Phase 1) — Claude never opens
      // them directly, it only produces the request; MissionExecutor is the only thing that
      // touches Linking for these two apps. Every other app keeps using the existing launcher.
      const appNameLower = String(input?.appName ?? '').toLowerCase();
      if (appNameLower.includes('waze')) {
        const request = buildActionRequest('waze', 'openApp', {});
        const outcome = await executeGoverned(request, { confirmed: false });
        return finalResult(outcome.message);
      }
      if (appNameLower.includes('whatsapp')) {
        const request = buildActionRequest('whatsapp', 'openApp', {});
        const outcome = await executeGoverned(request, { confirmed: false });
        return finalResult(outcome.message);
      }
      const text = input?.query
        ? `find ${input.query} on ${input.appName}`
        : `open ${input?.appName ?? ''}`;
      const result = await launchApp(text, ctx.address);
      return result?.reply ?? `I don't have ${input?.appName} set up yet, ${ctx.address}.`;
    }
    case 'callContact': {
      // Routed through the SAME governed WhatsApp-calling path as the deterministic Mission
      // Orchestrator (src/core/orchestrator/missionPlanner.ts, unified 2026-07-17) instead of the
      // old contactsAgent.ts implementation — confirmed live that one used a naive substring
      // contact match and a raw Linking.openURL('tel:...'), which is exactly the implicit intent
      // that pops Android's multi-dialer chooser on this phone. Two different "call a contact"
      // implementations behind two different command phrasings was the actual root cause of the
      // inconsistent "sometimes it works, sometimes it doesn't" behavior.
      const request = buildActionRequest('whatsapp', 'placeCall', { contactName: input?.name ?? '' });
      const outcome = await executeGoverned(request, { confirmed: false });
      return finalResult(outcome.message);
    }
    case 'sendWhatsApp': {
      // Never calls sendMessageToPerson/Linking directly (governance clarification 3) — produces
      // an ActionRequest only; MissionExecutor validates, hard-gates confirmation, and executes.
      const message = typeof input?.message === 'string' ? input.message.trim() : '';
      const request = buildActionRequest(
        'whatsapp',
        message ? 'prepareMessage' : 'openContact',
        { contactName: input?.person ?? '', message },
      );
      const outcome = await executeGoverned(request, { confirmed: false });
      return finalResult(outcome.message);
    }
    case 'readScreen': {
      const enabled = await isServiceEnabled();
      if (!enabled) return `Accessibility Service isn't enabled, so I can't read the screen, ${ctx.address}.`;
      const snapshot = getLastScreenSnapshot();
      if (!snapshot) return 'No screen data is available yet.';
      return JSON.stringify(snapshot);
    }
    case 'fillForm': {
      const enabled = await isServiceEnabled();
      if (!enabled) return `Accessibility Service isn't enabled, so I can't fill the form, ${ctx.address}.`;
      const filled = await fillForm(input?.fields ?? {});
      return `Filled ${filled} field(s).`;
    }
    case 'startCarMode': {
      ctx.onStartCarMode?.();
      return 'Car Mode activated.';
    }
    case 'getLocation': {
      try {
        const perm = await Location.requestForegroundPermissionsAsync();
        if (!perm.granted) return `I don't have location permission, ${ctx.address}.`;
        const pos = await Location.getCurrentPositionAsync({});
        const { latitude, longitude } = pos.coords;
        const places = await Location.reverseGeocodeAsync({ latitude, longitude });
        const place = places[0];
        const placeName = place
          ? [place.city ?? place.subregion, place.region, place.country].filter(Boolean).join(', ')
          : null;
        return placeName
          ? `Currently near ${placeName} (${latitude.toFixed(4)}, ${longitude.toFixed(4)}).`
          : `Currently at ${latitude.toFixed(4)}, ${longitude.toFixed(4)}.`;
      } catch {
        return `I couldn't get a GPS fix, ${ctx.address}.`;
      }
    }
    case 'getCurrentDateTime': {
      // Device clock — no permission, no network, no per-service API integration (per standing
      // architecture doctrine: knowledge questions go through the LLM, but the current time isn't
      // a knowledge question, it's local device state Claude has no other way to know).
      const now = new Date();
      const formatted = new Intl.DateTimeFormat(undefined, {
        weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
        hour: '2-digit', minute: '2-digit',
      }).format(now);
      return `Current date and time: ${formatted}.`;
    }
    case 'setAlarm': {
      const hour = Math.max(0, Math.min(23, Math.round(Number(input?.hour) || 0)));
      const minutes = Math.max(0, Math.min(59, Math.round(Number(input?.minutes) || 0)));
      try {
        await Linking.sendIntent('android.intent.action.SET_ALARM', [
          { key: 'android.intent.extra.alarm.HOUR', value: hour },
          { key: 'android.intent.extra.alarm.MINUTES', value: minutes },
          { key: 'android.intent.extra.alarm.SKIP_UI', value: true },
        ]);
        const mm = minutes.toString().padStart(2, '0');
        return `Alarm set for ${hour}:${mm}, ${ctx.address}.`;
      } catch {
        return `I couldn't set that alarm, ${ctx.address}.`;
      }
    }
    case 'web_search': {
      const query = String(input?.query ?? '').trim();
      if (!query) return 'No search query given.';
      if (!ctx.tavilyKey) return `I don't have a web search key configured, ${ctx.address}.`;
      const { results } = await tavilySearch(query, ctx.tavilyKey, false);
      if (!results.length) return `No web results found for "${query}".`;
      return results.map((r, i) => `[${i + 1}] ${r.title} — ${r.content} (${r.url})`).join('\n');
    }
    case 'navigate': {
      const request = buildActionRequest('waze', 'openNavigation', { destination: input?.destination ?? '' });
      const outcome = await executeGoverned(request, { confirmed: false });
      return finalResult(outcome.message);
    }
    case 'createCalendarEvent': {
      const note: ParsedNote = {
        type: 'calendar',
        person: null,
        datetime: typeof input?.datetime === 'string' ? input.datetime : null,
        content: String(input?.title ?? ''),
        actions: ['create_calendar'],
        confidence: 1,
      };
      return createCalendarEvent(note, ctx.address);
    }
    case 'remember': {
      const fact = String(input?.fact ?? '').trim();
      if (!fact) return 'No fact given.';
      await ctx.onRememberFact?.(fact);
      return `Noted, ${ctx.address}.`;
    }
    case 'recall': {
      const topic = typeof input?.topic === 'string' ? input.topic.toLowerCase().trim() : '';
      const facts = ctx.facts ?? [];
      const filtered = topic ? facts.filter(f => f.toLowerCase().includes(topic)) : facts;
      if (!filtered.length) return topic ? `No remembered facts match "${topic}".` : 'No facts remembered yet.';
      return filtered.join('; ');
    }
    default:
      return `Unknown tool: ${toolName}`;
  }
}
