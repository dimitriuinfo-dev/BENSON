import AsyncStorage from '@react-native-async-storage/async-storage';

// BENSON 4 — dynamic, full-device app permissions (via benson-app-registry's PackageManager
// enumeration). Deliberately separate from lib/appLauncherMemory.ts, which governs the older
// curated 28-app registry (lib/appRegistry.ts) — the two lists aren't merged, so an app can be
// approved in one system without affecting the other.
export type AppPermission = { packageName: string; appName: string; icon: string; allowed: boolean };

const PERMISSIONS_KEY = 'benson_app_permissions_v1';
const ONBOARDING_DONE_KEY = 'benson_app_onboarding_done_v1';

export async function loadAppPermissions(): Promise<AppPermission[]> {
  try {
    const raw = await AsyncStorage.getItem(PERMISSIONS_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export async function saveAppPermissions(list: AppPermission[]): Promise<void> {
  await AsyncStorage.setItem(PERMISSIONS_KEY, JSON.stringify(list));
}

export async function getAllowedApps(): Promise<AppPermission[]> {
  return (await loadAppPermissions()).filter(a => a.allowed);
}

export async function isOnboardingDone(): Promise<boolean> {
  return (await AsyncStorage.getItem(ONBOARDING_DONE_KEY)) === 'true';
}

export async function setOnboardingDone(): Promise<void> {
  await AsyncStorage.setItem(ONBOARDING_DONE_KEY, 'true');
}
