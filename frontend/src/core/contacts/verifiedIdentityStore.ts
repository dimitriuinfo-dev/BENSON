// ROUND_CONTACT_IDENTITY_CONTINUITY_1 — local, persisted memory of identities BENSON has itself
// verified on-screen (WhatsApp header/call-screen nameMatch=true). Never fed to the LLM (the LLM
// never sees contacts or phone numbers — see contextResolver.ts). Written ONLY by whatsappTool.ts
// at the exact moment a header verification succeeds; read by contextResolver.ts to correlate a
// phonetic STT variant ("Hana"/"Luhana") against a name BENSON has already confirmed for real.

import AsyncStorage from '@react-native-async-storage/async-storage';

export interface VerifiedIdentity {
  stableContactId?: string;
  normalizedPhoneNumber?: string;
  verifiedDisplayName: string;
  provider: 'whatsapp';
  observedAliases: string[]; // every spoken/STT surface form seen for this identity so far
  verificationSource: 'call_header' | 'write_header';
  verifiedAt: number;
  lastUsedAt: number;
  successfulUseCount: number;
}

const STORAGE_KEY = 'benson_verified_identities_v1';
const MAX_STORED = 100; // bounded — this is a working cache, not an address book replacement

let cache: VerifiedIdentity[] | null = null;

async function load(): Promise<VerifiedIdentity[]> {
  if (cache) return cache;
  try {
    const raw = await AsyncStorage.getItem(STORAGE_KEY);
    cache = raw ? (JSON.parse(raw) as VerifiedIdentity[]) : [];
  } catch {
    cache = [];
  }
  return cache;
}

async function persist(list: VerifiedIdentity[]): Promise<void> {
  cache = list;
  try { await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(list)); } catch { /* best-effort */ }
}

export async function getVerifiedIdentities(): Promise<VerifiedIdentity[]> {
  return [...(await load())];
}

// Called ONLY after a real on-screen header/call-screen verification succeeded (nameMatch=true).
// Merges into an existing identity (same normalizedPhoneNumber or stableContactId) instead of
// duplicating — the new alias is appended if not already known.
export async function recordVerifiedIdentity(input: {
  stableContactId?: string;
  normalizedPhoneNumber?: string;
  verifiedDisplayName: string;
  spokenAlias?: string;
  verificationSource: 'call_header' | 'write_header';
}): Promise<void> {
  const list = await load();
  const now = Date.now();
  const key = (v: VerifiedIdentity) =>
    (input.normalizedPhoneNumber && v.normalizedPhoneNumber === input.normalizedPhoneNumber) ||
    (input.stableContactId && v.stableContactId === input.stableContactId);
  const existing = list.find(key);
  if (existing) {
    existing.verifiedDisplayName = input.verifiedDisplayName;
    existing.verifiedAt = now;
    existing.lastUsedAt = now;
    existing.successfulUseCount += 1;
    if (input.spokenAlias && !existing.observedAliases.includes(input.spokenAlias)) {
      existing.observedAliases.push(input.spokenAlias);
    }
  } else {
    list.unshift({
      stableContactId: input.stableContactId,
      normalizedPhoneNumber: input.normalizedPhoneNumber,
      verifiedDisplayName: input.verifiedDisplayName,
      provider: 'whatsapp',
      observedAliases: input.spokenAlias ? [input.spokenAlias] : [],
      verificationSource: input.verificationSource,
      verifiedAt: now,
      lastUsedAt: now,
      successfulUseCount: 1,
    });
  }
  await persist(list.slice(0, MAX_STORED));
}

export async function touchLastUsed(verifiedDisplayName: string): Promise<void> {
  const list = await load();
  const found = list.find((v) => v.verifiedDisplayName === verifiedDisplayName);
  if (!found) return;
  found.lastUsedAt = Date.now();
  await persist(list);
}

// Test-only reset — never called from production code.
export function __resetForTests(): void {
  cache = [];
}
