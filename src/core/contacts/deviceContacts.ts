// BENSON Contacts — live device address book loader.
// The single place that touches expo-contacts to read the real address book. Every caller that
// needs contacts (WhatsApp tool, Mission Orchestrator's contacts search, CALL_CONTACT resolution)
// goes through this instead of each maintaining its own expo-contacts read — this file used to be
// duplicated privately inside whatsappTool.ts; extended here instead of parallel-built per the
// project's own engineering rule. Never persists anything: no AsyncStorage write, no caching layer
// — every call re-reads the OS address book fresh, since that's the actual source of truth Item 1
// requires ("live resolution at action time; numbers never persisted").

import * as Contacts from 'expo-contacts';
import type { TrustedContact } from './contactTypes';

export type ContactsPermissionState = 'granted' | 'denied' | 'undetermined';

function toPermissionState(status: Contacts.PermissionStatus, canAskAgain: boolean): ContactsPermissionState {
  if (status === Contacts.PermissionStatus.GRANTED) return 'granted';
  if (status === Contacts.PermissionStatus.DENIED && !canAskAgain) return 'denied';
  return status === Contacts.PermissionStatus.DENIED ? 'denied' : 'undetermined';
}

export async function getContactsPermissionState(): Promise<ContactsPermissionState> {
  const perm = await Contacts.getPermissionsAsync();
  return toPermissionState(perm.status, perm.canAskAgain);
}

// Caller is responsible for speaking WHY the permission is needed before calling this (Item 1
// requirement) — this function only performs the OS-level request/result, no UI/speech of its own.
export async function requestContactsPermission(): Promise<ContactsPermissionState> {
  const perm = await Contacts.requestPermissionsAsync();
  return toPermissionState(perm.status, perm.canAskAgain);
}

function toTrustedContact(c: Contacts.ExistingContact): TrustedContact | null {
  if (!c.name) return null;
  return {
    id: c.id,
    displayName: c.name,
    phoneNumbers: (c.phoneNumbers ?? []).map((p) => p.number).filter((n): n is string => Boolean(n)),
    emailAddresses: (c.emails ?? []).map((e) => e.email).filter((e): e is string => Boolean(e)),
  };
}

// Live device address book, mapped to TrustedContact. Returns [] (not an error) when permission
// isn't granted — callers already treat an empty list + a permission check as distinct concerns.
export async function loadDeviceContacts(): Promise<TrustedContact[]> {
  const granted = (await getContactsPermissionState()) === 'granted';
  if (!granted) return [];
  const { data } = await Contacts.getContactsAsync({
    fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Emails],
  });
  return data.map(toTrustedContact).filter((c): c is TrustedContact => c !== null);
}
