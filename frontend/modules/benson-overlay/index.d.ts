export declare function hasOverlayPermission(): boolean;
export declare function requestOverlayPermission(): void;
export declare function showBubble(): void;
export declare function hideBubble(): void;
export declare function addBubbleTappedListener(
  listener: () => void
): { remove: () => void };
export declare function showWakeRing(): void;
export declare function hideWakeRing(): void;
