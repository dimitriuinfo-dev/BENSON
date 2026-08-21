import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Network from 'expo-network';
import { insertEvents, type AnalyticsEvent } from './supabase';

export type Consent = 'accepted' | 'declined';

const CONSENT_KEY     = 'benson_analytics_consent_v1';
const INSTALL_ID_KEY  = 'benson_analytics_install_id_v1';
const QUEUE_KEY       = 'benson_analytics_queue_v1';
const LAST_UPLOAD_KEY = 'benson_analytics_last_upload_v1';
const MAX_QUEUE       = 500;
const FLUSH_INTERVAL_MS = 24 * 60 * 60 * 1000;

function uuidv4(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export async function getConsent(): Promise<Consent | null> {
  const raw = await AsyncStorage.getItem(CONSENT_KEY);
  return raw === 'accepted' || raw === 'declined' ? raw : null;
}

export async function setConsent(value: Consent): Promise<void> {
  await AsyncStorage.setItem(CONSENT_KEY, value);
}

async function getInstallId(): Promise<string> {
  let id = await AsyncStorage.getItem(INSTALL_ID_KEY);
  if (!id) {
    id = uuidv4();
    await AsyncStorage.setItem(INSTALL_ID_KEY, id);
  }
  return id;
}

async function loadQueue(): Promise<Omit<AnalyticsEvent, 'install_id'>[]> {
  try {
    const raw = await AsyncStorage.getItem(QUEUE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

async function saveQueue(items: Omit<AnalyticsEvent, 'install_id'>[]): Promise<void> {
  await AsyncStorage.setItem(QUEUE_KEY, JSON.stringify(items.slice(-MAX_QUEUE)));
}

// No-ops entirely unless the user has opted in — nothing is ever queued without consent.
export async function recordEvent(event: Omit<AnalyticsEvent, 'install_id'>): Promise<void> {
  if ((await getConsent()) !== 'accepted') return;
  const queue = await loadQueue();
  queue.push(event);
  await saveQueue(queue);
}

// Batches to Supabase at most once per 24h, and only over WiFi — called once, fire-and-forget,
// from app/index.tsx's boot init(). No-ops unless consent is accepted.
export async function tryFlush(): Promise<void> {
  if ((await getConsent()) !== 'accepted') return;

  const lastUpload = await AsyncStorage.getItem(LAST_UPLOAD_KEY);
  if (lastUpload && Date.now() - parseInt(lastUpload, 10) < FLUSH_INTERVAL_MS) return;

  const net = await Network.getNetworkStateAsync().catch(() => null);
  if (net?.type !== Network.NetworkStateType.WIFI) return;

  const queue = await loadQueue();
  if (!queue.length) return;

  const installId = await getInstallId();
  const ok = await insertEvents(queue.map(e => ({ ...e, install_id: installId })));
  if (ok) {
    await AsyncStorage.setItem(LAST_UPLOAD_KEY, Date.now().toString());
    await saveQueue([]);
  }
}
