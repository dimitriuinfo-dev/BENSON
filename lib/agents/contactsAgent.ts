import * as Contacts from 'expo-contacts';

// sun[aăo]? covers "sun"/"suna"/"sună"/"suno" (STT sometimes drops the diacritic or mishears the
// vowel) — the plain-ASCII "suna" alone never matches the correctly-accented "sună" real Romanian
// STT actually produces, which silently dropped every "sună-o pe X" call command before this fix.
export const CALL_PATTERN = /\b(call|sun[aăo]?|ruf|appelle)\b/i;

export type ContactLookup = { name: string; phone: string | null } | 'no-permission' | null;

// Shared device-contact lookup — used by "call X" here and by the Notepad's "message" action
// (lib/notepad/actions.ts) to resolve a family member's phone number for sending.
export async function findContact(name: string): Promise<ContactLookup> {
  const { status } = await Contacts.requestPermissionsAsync();
  if (status !== 'granted') return 'no-permission';
  const { data } = await Contacts.getContactsAsync({ fields: [Contacts.Fields.PhoneNumbers] });
  const match = data.find(c => c.name?.toLowerCase().includes(name.toLowerCase()));
  if (!match) return null;
  return { name: match.name ?? name, phone: match.phoneNumbers?.[0]?.number ?? null };
}
