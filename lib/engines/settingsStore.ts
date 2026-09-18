// Persistence for the swappable STT / CREIER / VOCE engine configuration (Task 7's "NUCLEE"
// Settings section, Task 1's Settings-driven registry). `apiKey` values go through
// expo-secure-store — the same mechanism already used elsewhere in this app for the Picovoice
// AccessKey — never AsyncStorage, never a plain file, never a log line (ground rule 5: at most the
// last four characters ever appear in a log, via maskApiKey below). `baseUrl`/`model`/the selected
// engine id are not secrets and use AsyncStorage. Nothing here hardcodes a provider — every value
// is read back exactly as Settings wrote it.
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import type { EngineConfig } from './types';

export type EngineKind = 'stt' | 'llm' | 'tts';

const ASYNC_PREFIX = 'benson.engine.';
const SECURE_PREFIX = 'benson.engine.apiKey.';

function selectedKey(kind: EngineKind): string {
  return `${ASYNC_PREFIX}${kind}.selected`;
}
function baseUrlKey(kind: EngineKind, id: string): string {
  return `${ASYNC_PREFIX}${kind}.${id}.baseUrl`;
}
function modelKey(kind: EngineKind, id: string): string {
  return `${ASYNC_PREFIX}${kind}.${id}.model`;
}
function apiKeyKey(kind: EngineKind, id: string): string {
  return `${SECURE_PREFIX}${kind}.${id}`;
}

export async function getSelectedEngineId(kind: EngineKind, fallback: string): Promise<string> {
  const v = await AsyncStorage.getItem(selectedKey(kind));
  return v || fallback;
}

export async function setSelectedEngineId(kind: EngineKind, id: string): Promise<void> {
  await AsyncStorage.setItem(selectedKey(kind), id);
}

// Returns null when no apiKey is stored for (kind, id) — callers treat that as "not configured"
// (e.g. voiceAgent.ts's Groq tier is skipped entirely, falling through to the next STT tier, when
// this returns null).
export async function getEngineConfig(kind: EngineKind, id: string): Promise<EngineConfig | null> {
  const [baseUrl, model, apiKey] = await Promise.all([
    AsyncStorage.getItem(baseUrlKey(kind, id)),
    AsyncStorage.getItem(modelKey(kind, id)),
    SecureStore.getItemAsync(apiKeyKey(kind, id)).catch(() => null),
  ]);
  if (!apiKey) return null;
  return { baseUrl: baseUrl ?? '', model: model ?? '', apiKey };
}

export async function saveEngineConfig(
  kind: EngineKind,
  id: string,
  config: { baseUrl?: string; model?: string; apiKey?: string },
): Promise<void> {
  const writes: Promise<void>[] = [];
  if (config.baseUrl !== undefined) writes.push(AsyncStorage.setItem(baseUrlKey(kind, id), config.baseUrl));
  if (config.model !== undefined) writes.push(AsyncStorage.setItem(modelKey(kind, id), config.model));
  if (config.apiKey) writes.push(SecureStore.setItemAsync(apiKeyKey(kind, id), config.apiKey));
  await Promise.all(writes);
}

// For UI display and logging — never the key itself, at most its last four characters.
export function maskApiKey(key: string): string {
  if (!key) return '(none)';
  return key.length <= 4 ? '••••' : `••••${key.slice(-4)}`;
}
