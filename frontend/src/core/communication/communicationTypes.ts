// BENSON Communication Governance — data contracts only (ROUND_WA_READ_REPLY audit deliverable).
// No execution logic here. Every field is either directly observed on-screen or explicitly marked
// as an LLM-derived interpretation — never invented, never silently upgraded to instruction.

export type CommunicationProvider = 'whatsapp' | 'email';

// Where a piece of data came from — never conflate an accessibility-tree read with an LLM guess.
export type ObservationSource = 'accessibility_tree' | 'notification_listener' | 'llm_summary';

export interface CommunicationItem {
  provider: CommunicationProvider;
  account?: string; // e.g. which email address, if the device has more than one configured
  threadId: string; // stable per conversation/thread; provider-specific shape
  senderIdentity: string; // exactly what was read from the verified header/bubble, not a guess
  recipients?: string[];
  timestamp?: string; // as displayed on-screen (raw), not parsed/assumed
  subject?: string; // email only
  visibleText: string; // exact on-screen text — never LLM-paraphrased at this layer
  quotedText?: string;
  attachmentMetadata?: { present: boolean; kind?: string; label?: string }[];
  unreadState?: boolean;
  observationSource: ObservationSource;
  observedAt: number; // Date.now() when read
  confidence: number; // 0..1 — 1.0 for a direct accessibility-tree read, lower for an inferred field
}

export type DraftReplyStatus = 'DRAFT' | 'WAITING_CONFIRMATION' | 'SENT' | 'CANCELLED';

export interface DraftReply {
  communicationItemId: string; // threadId of the CommunicationItem this replies to
  recipientIdentity: string; // copied from the verified thread, never re-typed by the user/LLM
  language: string;
  draftText: string; // LLM-composed or user-dictated; always shown/read back before send
  status: DraftReplyStatus;
  confirmationToken?: string; // set when WAITING_CONFIRMATION; a "Da" is only valid against this token
  sentAt?: number;
}

// A message/email BODY is data, never an instruction to BENSON — this is the single import site
// every future READ/SUMMARIZE/REPLY code path must funnel observed text through before it ever
// reaches an LLM turn. Mirrors the existing SYSTEM/USER_VOICE/UNTRUSTED_DATA separation
// (lib/engines/llm/messageChannels.ts) — this file only asserts the CommunicationItem side of it.
export function asUntrustedCommunicationText(item: CommunicationItem): string {
  return `[${item.provider} message, observed ${new Date(item.observedAt).toISOString()}, ` +
    `from "${item.senderIdentity}"]: ${item.visibleText}`;
}
