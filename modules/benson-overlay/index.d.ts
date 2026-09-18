export declare function hasOverlayPermission(): boolean;
export declare function requestOverlayPermission(): void;
export declare function showBubble(): void;
export declare function hideBubble(): void;
export declare function addBubbleTappedListener(
  listener: () => void
): { remove: () => void };
/** Camera icon on the status card (2026-09-18) — same effect as the main-screen camera button. */
export declare function addBubbleCameraTappedListener(
  listener: () => void
): { remove: () => void };
export declare function showWakeRing(): void;
export declare function hideWakeRing(): void;
export declare function updateBubbleStatus(
  state: string,
  transcript: string,
  visible: boolean,
  terminal?: boolean,
  turnId?: number,
  dismissDelayMs?: number
): void;
export declare function setBubbleMotion(
  motion: 'static' | 'listening' | 'executing' | string
): void;
export declare function playWakeSound(): void;
/** URGENT_REPAIR_AND_ADVANCE_1 — real-RMS mic bars on the active status card. level: 0-1. */
export declare function setMicLevel(level: number, active: boolean): void;
