import AsyncStorage from '@react-native-async-storage/async-storage';

// Separate from lib/appPermissions.ts's ONBOARDING_DONE_KEY (which only gates the per-app
// permissions picker) — this key gates the system-permissions setup wizard, a distinct one-time
// flow that must run (and be re-checkable later from Settings) independently of it.
const SETUP_WIZARD_DONE_KEY = 'benson_setup_wizard_done_v1';

export async function isSetupWizardDone(): Promise<boolean> {
  return (await AsyncStorage.getItem(SETUP_WIZARD_DONE_KEY)) === 'true';
}

export async function setSetupWizardDone(): Promise<void> {
  await AsyncStorage.setItem(SETUP_WIZARD_DONE_KEY, 'true');
}
