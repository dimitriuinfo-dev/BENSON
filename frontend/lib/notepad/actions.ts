import { Linking } from 'react-native';
import * as Notifications from 'expo-notifications';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { findContact } from '../agents/contactsAgent';
import type { ParsedNote } from '../agents/noteRouterAgent';
import type { FamilyMember } from '../agents/types';
import * as whatsappTool from '../../src/core/mission/tools/whatsappTool';

function toGCalDate(d: Date): string {
  return d.toISOString().replace(/[-:]/g, '').split('.')[0] + 'Z';
}

// Opens Google Calendar's own "create event" screen pre-filled — Benson never writes to the
// calendar directly, the user confirms/saves it themselves inside the real app.
export async function createCalendarEvent(note: ParsedNote, address: string): Promise<string> {
  const params = new URLSearchParams({ action: 'TEMPLATE', text: note.content });
  if (note.datetime) {
    const start = new Date(note.datetime);
    const end = new Date(start.getTime() + 60 * 60 * 1000);
    params.set('dates', `${toGCalDate(start)}/${toGCalDate(end)}`);
  }
  await Linking.openURL(`https://calendar.google.com/calendar/render?${params.toString()}`).catch(() => {});
  return `Opening Google Calendar for you to confirm, ${address}.`;
}

const REMINDER_HOURS_KEY = 'benson_reminder_hours_v1';

async function recordReminderHour(hour: number): Promise<void> {
  try {
    const raw = await AsyncStorage.getItem(REMINDER_HOURS_KEY);
    const hours: number[] = raw ? JSON.parse(raw) : [];
    await AsyncStorage.setItem(REMINDER_HOURS_KEY, JSON.stringify([...hours, hour].slice(-20)));
  } catch {}
}

// Lightweight habit heuristic (same frequency-counting style as lib/agents/learningAgent.ts) —
// defaults a dateless reminder to whichever hour the user has picked most often before.
async function preferredHour(): Promise<number> {
  try {
    const raw = await AsyncStorage.getItem(REMINDER_HOURS_KEY);
    const hours: number[] = raw ? JSON.parse(raw) : [];
    if (!hours.length) return 9;
    const freq = new Map<number, number>();
    for (const h of hours) freq.set(h, (freq.get(h) ?? 0) + 1);
    return [...freq.entries()].sort((a, b) => b[1] - a[1])[0][0];
  } catch {
    return 9;
  }
}

export async function setReminder(note: ParsedNote, address: string): Promise<string> {
  let date: Date;
  if (note.datetime) {
    date = new Date(note.datetime);
  } else {
    date = new Date();
    date.setDate(date.getDate() + 1);
    date.setHours(await preferredHour(), 0, 0, 0);
  }
  await recordReminderHour(date.getHours());
  await Notifications.scheduleNotificationAsync({
    content: { title: 'BENSON', body: note.content },
    trigger: { type: Notifications.SchedulableTriggerInputTypes.DATE, date },
  });
  return `Reminder set for ${date.toLocaleString()}, ${address}.`;
}

// Appends the note to the matching family member's profile (Family Engine, app/index.tsx) —
// returns the updated array for the caller to persist with its existing saveFamily()/setFamily().
export function saveFamilyNote(family: FamilyMember[], person: string, note: string): FamilyMember[] {
  const idx = family.findIndex(f => f.name.toLowerCase() === person.toLowerCase());
  if (idx === -1) return family;
  const updated = [...family];
  const existing = updated[idx].notes;
  updated[idx] = { ...updated[idx], notes: existing ? `${existing}; ${note}` : note };
  return updated;
}

// Resolves a phone number for `person` (quick contacts first, then the device address book) and
// opens WhatsApp (falling back to SMS) with the message pre-filled — never sends anything itself.
export async function sendMessageToPerson(
  person: string, message: string, address: string, quickContactPhone?: string,
): Promise<string> {
  let phone = quickContactPhone ?? null;
  if (!phone) {
    const contact = await findContact(person);
    if (contact && contact !== 'no-permission') phone = contact.phone;
  }
  if (!phone) return `I saved the note, but I couldn't find a number for ${person}, ${address}.`;

  // WhatsApp attempt goes through the same Tool Layer src/core/mission uses (no second,
  // independent wa.me/whatsapp:// URL builder) — SMS stays the local fallback when WhatsApp
  // itself can't be reached; this Notepad flow keeps its own existing pendingNoteAction
  // confirmation UX, unrelated to Waze/WhatsApp governance's confirmation gate.
  const result = await whatsappTool.openConversation(phone, message);
  if (result.outcome !== 'launch_failed') return `Opening a message to ${person}, ${address}.`;

  await Linking.openURL(`sms:${phone}?body=${encodeURIComponent(message)}`).catch(() => {});
  return `Opening a message to ${person}, ${address}.`;
}
