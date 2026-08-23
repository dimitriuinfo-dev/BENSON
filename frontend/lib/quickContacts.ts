import AsyncStorage from '@react-native-async-storage/async-storage';

export type Channel = 'phone' | 'whatsapp' | 'sms' | 'email';

export type QuickContact = {
  id: string; // expo-contacts contact id
  name: string;
  phone?: string;
  email?: string;
  preferredChannel?: Channel;
};

export const MAX_QUICK_CONTACTS = 8;
const QUICK_CONTACTS_KEY = 'benson_quick_contacts_v1';

export async function loadQuickContacts(): Promise<QuickContact[]> {
  try {
    const raw = await AsyncStorage.getItem(QUICK_CONTACTS_KEY);
    if (!raw) return [];
    return JSON.parse(raw);
  } catch {
    return [];
  }
}

export async function saveQuickContacts(contacts: QuickContact[]): Promise<void> {
  await AsyncStorage.setItem(QUICK_CONTACTS_KEY, JSON.stringify(contacts));
}

export async function setPreferredChannel(contactId: string, channel: Channel): Promise<QuickContact[]> {
  const contacts = await loadQuickContacts();
  const updated = contacts.map(c => (c.id === contactId ? { ...c, preferredChannel: channel } : c));
  await saveQuickContacts(updated);
  return updated;
}
