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

/** Shrinks BENSON's own Activity into a real Android Picture-in-Picture window — only ever
 * affects BENSON itself, never another app. Returns false (no-op) below Android 8.0 (API 26). */
export function enterPipMode(): boolean;

/** Fires whenever BENSON's Activity enters or leaves real PiP. Same Activity, same React tree —
 * this is how JS knows to switch to a minimal logo-only layout while shrunk. */
export function addPipModeListener(
  listener: (event: { isInPip: boolean }) => void
): { remove(): void };
