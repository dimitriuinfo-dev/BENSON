import AsyncStorage from '@react-native-async-storage/async-storage';
import { APP_REGISTRY, type AppCategory } from './appRegistry';

const MEMORY_KEY   = 'benson_app_launcher_memory_v1';
const APPROVED_KEY = 'benson_app_launcher_approved_v1';

export async function loadLastUsedByCategory(): Promise<Partial<Record<AppCategory, string>>> {
  try {
    const raw = await AsyncStorage.getItem(MEMORY_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

export async function recordAppUsed(appId: string, category: AppCategory): Promise<void> {
  const current = await loadLastUsedByCategory();
  current[category] = appId;
  await AsyncStorage.setItem(MEMORY_KEY, JSON.stringify(current));
}

// Defaults to every registry app approved — Settings → Governed Apps lets the user revoke any.
export async function loadApprovedAppIds(): Promise<string[]> {
  try {
    const raw = await AsyncStorage.getItem(APPROVED_KEY);
    return raw ? JSON.parse(raw) : APP_REGISTRY.map(a => a.id);
  } catch {
    return APP_REGISTRY.map(a => a.id);
  }
}

export async function saveApprovedAppIds(ids: string[]): Promise<void> {
  await AsyncStorage.setItem(APPROVED_KEY, JSON.stringify(ids));
}
