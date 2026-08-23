export interface BensonNode {
  id: string;
  viewId: string | null;
  text: string;
  contentDescription: string;
  className: string;
  clickable: boolean;
  editable: boolean;
  checkable: boolean;
  checked: boolean;
  bounds: { left: number; top: number; right: number; bottom: number };
}

export interface BensonScreenSnapshot {
  packageName: string;
  timestamp: number;
  nodes: BensonNode[];
}

export function isServiceEnabled(): Promise<boolean>;
export type AccessibilityConnectionState = 'disabled' | 'enabled_disconnected' | 'enabled_connected';
export function getConnectionState(): Promise<AccessibilityConnectionState>;
export function openAccessibilitySettings(): Promise<boolean>;
export function performClick(nodeId: string): Promise<boolean>;
export function performSetText(nodeId: string, value: string): Promise<boolean>;
export function fillForm(fields: Record<string, string>): Promise<number>;
export function goBack(): Promise<boolean>;
export function goHome(): Promise<boolean>;
export function openRecents(): Promise<boolean>;
export function getForegroundPackage(): string | null;
export function addScreenUpdateListener(
  listener: (event: { json: string }) => void
): { remove: () => void };
export function addForegroundChangeListener(
  listener: (event: { packageName: string }) => void
): { remove: () => void };

export interface WhatsAppCallResult {
  success: boolean;
  step: string;
  error: string | null;
}
export function placeWhatsAppCall(contactName: string, autoPressCall: boolean): Promise<WhatsAppCallResult>;
export function endWhatsAppCall(): Promise<WhatsAppCallResult>;
export function muteWhatsAppCall(): Promise<WhatsAppCallResult>;
export function pressWhatsAppSend(): Promise<WhatsAppCallResult>;
export function pressWhatsAppCallButton(): Promise<WhatsAppCallResult>;
export interface AutomationProfileResult {
  success: boolean;
  step: string;
  error: string | null;
}
export function runAutomationProfile(
  profileId: string,
  params?: Record<string, string>,
): Promise<AutomationProfileResult>;

// JS-driven step-list executor (2026-07-17, BensonCommandExecutor.kt) — every field on
// CommandMatch is optional and combined with logical AND; see the Kotlin doc comment for the
// exact semantics of each (wholeWord, clickableAncestor, maxTopPercent, etc.).
export interface CommandMatch {
  viewId?: string;
  viewIdContains?: string;
  textContains?: string;
  wholeWord?: boolean;
  clickable?: boolean;
  editable?: boolean;
  excludeSearchUi?: boolean;
  excludeAvatars?: boolean;
  clickableAncestor?: boolean;
  maxTopPercent?: number;
}
export type CommandStep =
  | { action: 'launch_app'; package: string }
  | { action: 'wait'; ms?: number }
  | { action: 'click'; match: CommandMatch; timeoutMs?: number; requirePackage?: string }
  | { action: 'set_text'; match: CommandMatch; text: string; timeoutMs?: number; requirePackage?: string }
  | { action: 'assert_present'; match: CommandMatch; timeoutMs?: number }
  | { action: 'assert_gone'; match: CommandMatch; timeoutMs?: number }
  | { action: 'assert_package'; package: string; timeoutMs?: number }
  | { action: 'back' }
  | { action: 'home' }
  | { action: 'return_to_benson' };
export interface CommandResult {
  success: boolean;
  stepIndex: number;
  action: string;
  // completed | not_found | ambiguous | blocked | tap_rejected | wrong_package | invalid | timeout
  status: string;
  detail: string | null;
}
export function executeCommand(command: { steps: CommandStep[] }): Promise<CommandResult>;

export function setWhatsAppAutomationActive(active: boolean): void;
