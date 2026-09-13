export interface InstalledApp {
  packageName: string;
  appName: string;
  /** base64 PNG data URI, e.g. "data:image/png;base64,..." — pass straight to <Image source={{ uri }} />. */
  icon: string;
}

export function getInstalledApps(): Promise<InstalledApp[]>;

/** Launches an installed app by package name. Returns false if the package isn't found/launchable. */
export function launchApp(packageName: string): boolean;

/** Checks whether a package is installed and launchable, without actually launching it. */
export function isPackageInstalled(packageName: string): boolean;

/** Places a direct phone call (Intent.ACTION_CALL) — no chooser, no dialer screen. Requires
 * CALL_PHONE already granted; returns false (and places no call) if it isn't. */
export function placeDirectCall(phoneNumber: string): boolean;

/** Checks whether CALL_PHONE is currently granted. */
export function hasCallPhonePermission(): boolean;

/** Opens a URI with an explicit target package (Intent.setPackage), bypassing the OS's default
 * app resolution. Returns false if that package can't handle the URI or isn't installed. */
export function openUriWithPackage(uri: string, packageName: string): boolean;

export type EmergencyIntentKind = 'NONE' | 'EXPLICIT_112' | 'GENERIC_HELP';
/** ROUND_EMERGENCY_CORE_1 — classify a spoken utterance against the closed emergency set.
 * Synchronous, no side effects. */
export function classifyEmergencyIntent(text: string): EmergencyIntentKind;

export interface EmergencyContextSnapshot {
  timestamp: number;
  /** 0–100, or -1 if unavailable. */
  batteryPct: number;
  /** 'wifi' | 'cellular' | 'other' | 'none' | 'unknown' */
  network: string;
  /** whether a last-known location fix already exists (no coordinates are exposed). */
  locationAvailable: boolean;
}
/** One-shot emergency context. Logged natively; never blocks the 112 route; no coordinates
 * cross the bridge. Null if the native context is unavailable. */
export function getEmergencyContext(): EmergencyContextSnapshot | null;

export interface EmergencyRouteResult {
  success: boolean;
  mode: 'DIRECT_CALL' | 'SYSTEM_DIALER' | 'FAILED';
  reason: string | null;
}
/** Routes 112 to the native Android telecom stack — a real placed call (ACTION_CALL) when
 * CALL_PHONE is granted, otherwise the system dialer pre-filled with 112 (ACTION_DIAL). Never
 * WhatsApp, never a third-party calling app, never Accessibility typing. */
export function routeEmergencyCall(): Promise<EmergencyRouteResult>;

export interface WhatsAppNativeCallProbeResult {
  contactFound: boolean;
  displayName: string | null;
  mimeFound: boolean;
  /** Data._ID of the voip.call row — for the report only; never persisted. */
  dataId: number | null;
  intentResolved: boolean;
  intentLaunched: boolean;
  /** "package/activity" that resolved the typed intent, or null. */
  resolverActivity: string | null;
  fail: string | null;
}
/** ROUND_WA_NATIVE_CALL_PROBE_1 — feasibility probe ONLY. `doLaunch === true` fires the typed
 * ContactsContract intent against com.whatsapp, which places a REAL call. Default false. */
export function probeWhatsAppNativeCall(
  contactName: string,
  doLaunch?: boolean,
): Promise<WhatsAppNativeCallProbeResult>;

/** Shrinks BENSON's own Activity into a real Android Picture-in-Picture window — only ever
 * affects BENSON itself, never another app. Returns false (no-op) below Android 8.0 (API 26). */
export function enterPipMode(): boolean;

/** Fires whenever BENSON's Activity enters or leaves real PiP. Same Activity, same React tree —
 * this is how JS knows to switch to a minimal logo-only layout while shrunk. */
export function addPipModeListener(
  listener: (event: { isInPip: boolean }) => void
): { remove(): void };
