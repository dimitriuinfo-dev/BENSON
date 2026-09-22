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
  /** Present on on-demand snapshots (getScreenSnapshot); equals `timestamp`. */
  capturedAt?: number;
  /** Present on on-demand snapshots; nodes.length. */
  nodeCount?: number;
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
/** On-demand FRESH snapshot (reads the live tree now, retries 3×@150ms if empty). Returns a
 *  JSON string of BensonScreenSnapshot. Never the stale pushed cache. */
export function getScreenSnapshot(): Promise<string>;
export function addForegroundChangeListener(
  listener: (event: { packageName: string }) => void
): { remove: () => void };

export interface WhatsAppCallResult {
  success: boolean;
  step: string;
  error: string | null;
}
export function placeWhatsAppCall(contactName: string, autoPressCall: boolean): Promise<WhatsAppCallResult>;

export interface WhatsAppCallNativeResult {
  success: boolean;
  step: string;
  error: string | null;
  contact: string;
  elapsedMs: number;
  /** ROUND_VERIFIED_IDENTITY_BRIDGE_1 — set ONLY when a real on-screen header/call-screen name
   *  was read AND phonetically matched the expected contact; null/false otherwise (never guessed,
   *  never set on a failed/aborted/idempotent-shortcut path). */
  verifiedHeaderText: string | null;
  nameMatch: boolean;
}
/** WA-NATIVE-FINAL — the active WhatsApp voice-call executor. Runs the full sequence natively. */
export function runWhatsAppCallNative(contact: string): Promise<WhatsAppCallNativeResult>;

/** WA-FIX-4 — DIRECT-CONTACT-DEEPLINK. `phone` = normalised digits resolved locally from the
 * address book. Native opens whatsapp://send?phone=<phone>, verifies the conversation matches
 * `expectedName`, then reuses the proven call-button + verify sequence. No Chats search/scroll. */
export function runWhatsAppOpenConversationCall(phone: string, expectedName: string): Promise<WhatsAppCallNativeResult>;

/** ROUND_WA_GOVERNANCE_WRITE_1 — PHASE A. Opens the exact conversation for `phone`, verifies it is
 * `expectedName`, finds the compose field, types `message`, verifies the typed text. STOPS before
 * send. `missionId` keys persisted idempotency. step === 'TYPED_VERIFIED' on success. */
export function runWhatsAppOpenConversationType(
  phone: string, expectedName: string, message: string, missionId: string,
): Promise<WhatsAppCallNativeResult>;
/** PHASE B — call ONLY after explicit YES. Presses Send at most once for `missionId`, then verifies
 * the exact outgoing message appears. step === 'SENT_VERIFIED' on success. */
export function pressWhatsAppSendVerified(missionId: string, message: string): Promise<WhatsAppCallNativeResult>;
/** "<missionId>|<state>" of the pending WhatsApp write, or "|NOT_TYPED". */
export function getWhatsAppWriteState(): string;

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
  /** ROUND_YOUTUBE_GOVERNANCE_1 — OR-of-substrings, e.g. multi-language semantic labels. */
  textContainsAny?: string[];
  wholeWord?: boolean;
  clickable?: boolean;
  editable?: boolean;
  excludeSearchUi?: boolean;
  excludeAvatars?: boolean;
  clickableAncestor?: boolean;
  maxTopPercent?: number;
  /** ROUND_YOUTUBE_GOVERNANCE_1 — inverse of maxTopPercent; excludes chrome above this %. */
  minTopPercent?: number;
  /** ROUND_YOUTUBE_GOVERNANCE_1/2 — extract_list only: restrict collection to descendants of the
   *  first scrollable container found (a results list), excluding nav bars/banners/top cards. */
  withinScrollable?: boolean;
  /** ROUND_YOUTUBE_GOVERNANCE_2 — substring match on the node's Android widget class name (e.g.
   *  "SeekBar"), a structural signal that survives a player's controls auto-hiding their labels. */
  classNameContains?: string;
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
  | { action: 'return_to_benson' }
  /** ROUND_YOUTUBE_GOVERNANCE_1 — read-only: up to `limit` distinct visible labels matching
   *  `match`, top-to-bottom, into the result's itemsJson. Taps nothing. */
  | { action: 'extract_list'; match: CommandMatch; limit?: number }
  /** ROUND_YOUTUBE_GOVERNANCE_1 — presses ACTION_IME_ENTER on the currently-focused input
   *  (Android 11+; a keyboard "search"/"go" key rather than an on-screen button). */
  | { action: 'ime_action' }
  /** ROUND_SPOTIFY_GOVERNANCE_1 — types into whatever node currently holds Android's own input
   *  focus (AccessibilityNodeInfo.FOCUS_INPUT), instead of matching {editable:true} in the tree.
   *  Toolkit-agnostic: works even when the real field doesn't report isEditable the way a classic
   *  EditText does (confirmed live: a Compose-based search field). */
  | { action: 'set_text_on_focus'; text: string };
export interface CommandResult {
  success: boolean;
  stepIndex: number;
  action: string;
  // completed | not_found | ambiguous | blocked | tap_rejected | wrong_package | invalid | timeout
  status: string;
  detail: string | null;
  /** ROUND_YOUTUBE_GOVERNANCE_1 — JSON-encoded array of {label, top} from extract_list; "[]" otherwise.
   *  Optional so existing CommandResult literals elsewhere (predating this round) still type-check. */
  itemsJson?: string;
}
export function executeCommand(command: { steps: CommandStep[] }): Promise<CommandResult>;

// CALC1 (2026-09-22) — symbols already parsed by JS (lib/tools/toolRegistry.ts), e.g.
// ["√","9","="] for "radacina din 9". Native presses each button (real IDs discovered on
// device, see CalculatorRecipe.kt) and reads the real result display back.
export interface CalculatorRecipeOutcome {
  success: boolean;
  resultText: string | null;
  changed: boolean;
  failedStep: string | null;
  error: string | null;
}
export function runCalculatorRecipe(symbols: string[]): Promise<CalculatorRecipeOutcome>;

export function setWhatsAppAutomationActive(active: boolean): void;

/** WA-CALL-STAYS-LIVE — true while a WhatsApp call runWhatsAppCallNative just placed is still live
 *  (verified < 180s ago and not yet cleared). While true, BENSON's mic/wake entry points must not
 *  open the mic — a concurrent capture ends the fresh call on this device. Synchronous. */
export function whatsAppCallMicHoldActive(): boolean;
/** Lift the WhatsApp-call mic hold early — call when BENSON's Activity is foregrounded again. */
export function clearWhatsAppCallMicHold(): void;
/** AUTO-RETURN-AFTER-CALL — true once after the native watcher verified a call ended and fired the
 *  return Intent. Consume in AppState 'active' to re-arm WAKE mode only. */
export function consumeCallEndedReturnPending(): boolean;
/** WA-LIFECYCLE-FIX-1 — persisted "a verified WhatsApp call just ended" timestamp (ms; 0 = none). */
export function getWhatsAppCallEndedSignalAt(): number;
