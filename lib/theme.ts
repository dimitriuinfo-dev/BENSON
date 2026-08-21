// Shared palette — "Distinguished Butler" (2026-07-15, architect-directed, supersedes this same
// session's earlier bioluminescent/organic direction). Old-school science fiction refined into
// elegance: brushed metal, engraved hairlines, a chronometer gauge — a precision instrument, not
// a glowing organism. Background is ALWAYS this blue-grey slate family, never black/near-black;
// gold is an accent (hairlines, metallic details), never a background wash.
export const bg          = '#2E3742';
export const bgRaised    = '#37414E';
export const bgDeep      = '#262E38';
export const gold        = '#C9A24B';
export const goldBright  = '#E8C87A';
export const goldDeep    = '#8F7130';
export const green       = '#2E6B57';
export const greenBright = '#4E8F76';
export const text        = '#E9E4D8';
export const textDim     = '#9AA3AC';
export const line        = 'rgba(201,162,75,0.35)';

// Backward-compat aliases — every file that already imports these uppercase names (car mode
// screen, settings modal, other canvas cards not touched this pass) re-themes automatically
// without being edited directly. Mapped to the closest equivalent new token, not arbitrary values.
export const GOLD  = gold;
export const NAVY  = bg;
export const PANEL = bgRaised;
export const MUTED = textDim;
export const RED   = '#E05555';
export const GREEN = green;

// Dashboard mockup palette — components/dashboard/BottomDashboard.tsx and the app/index.tsx
// seal. Kept separate from the palette above (used by the older canvas cards) so this can match
// the reference mockup exactly without reflowing every existing card's colors.
//
// Exact flat colors per explicit user spec — no pixel-sampled/textured variants, no
// approximation. GOLD_ALT/GREEN_ALT are kept as aliases (not derived shades) so every call site
// that references either name renders the identical mandated color.
export const DASH_NAVY_TOP    = '#2A3B4C';
export const DASH_NAVY_BOTTOM = '#1E2B38';
export const DASH_PANEL       = '#1C2733';
export const DASH_GOLD        = '#D4AF37';
export const DASH_GOLD_ALT    = '#D4AF37';
export const DASH_GREEN       = '#1F5A45';
export const DASH_GREEN_ALT   = '#1F5A45';
export const DASH_TEXT        = '#F5F0E1';
