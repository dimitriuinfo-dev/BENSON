import type { ReadChatResult } from '../mission/tools/whatsappTool';
import type { WaReadItem } from '../../../lib/agents/voiceAgent';

type Dependencies = {
  readChat: (name: string, count: number) => Promise<ReadChatResult>;
  notifications: () => string;
  formatNotifications: (items: WaReadItem[], force?: boolean, all?: boolean) => { text: string; spokenCount: number };
  formatChat: (name: string, messages: { sender: 'me' | 'them'; text: string }[]) => string;
  now?: () => number;
};
export type WhatsAppDialogueResult = { kind: 'read'; message: string; awaitingChoice?: boolean };

const fold = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const TTL_MS = 120_000;

export function createWhatsAppReadDialogue(deps: Dependencies) {
  let pending: { items: WaReadItem[]; at: number } | null = null;
  const now = deps.now ?? Date.now;

  return async (utterance: string): Promise<WhatsAppDialogueResult | null> => {
    const raw = utterance.trim().replace(/[.!?]+$/, '');
    const t = fold(raw).replace(/[-‐‑]/g, ' ').replace(/\s+/g, ' ');
    if (pending && now() - pending.at > TTL_MS) pending = null;

    const all = /^(?:citeste(?: mi| le)?\s+)?(?:pe\s+)?toate(?: mesajele)?$/.test(t);
    const last = /^(?:(?:citeste(?: mi)?|doar)\s+)?ultimul(?: mesaj)?$/.test(t);
    if (pending && (all || last)) {
      const items = last ? pending.items.slice(-1) : pending.items;
      pending = null;
      return { kind: 'read', message: deps.formatNotifications(items, true, true).text };
    }

    const specific = raw.match(/^(?:ce\s+mi[- ]a\s+scris|cite[șs]te(?:[- ]mi)?\s+(?:(?:ultimele\s+)?mesaj(?:ele|ul)|conversa[țt]ia)\s+(?:de\s+la|cu))\s+(.+?)(?:\s+pe\s+whatsapp)?$/i);
    const generic = /^(?:mai\s+)?citeste(?: mi)?\s+(?:(?:inca\s+)?o\s+data\s+)?(?:mesajele|mesajele noi|ultimele mesaje|notificarile)(?:\s+(?:de\s+)?pe\s+whatsapp)?$/.test(t);
    if (!specific && !generic) {
      pending = null;
      return null;
    }
    pending = null;
    try {
      if (specific) {
        const result = await deps.readChat(specific[1].trim(), 10);
        if (!result.ok) return { kind: 'read', message: result.result?.error || (result.reason.startsWith('accessibility')
          ? 'Nu pot citi ecranul. Verifică serviciul de accesibilitate BENSON din Setări.'
          : 'Nu am putut verifica și citi conversația. Spune numele complet al contactului.') };
        const messages = t.startsWith('ce mi a scris') ? result.messages.filter(m => m.sender === 'them') : result.messages;
        return { kind: 'read', message: deps.formatChat(result.displayName, messages) };
      }
      const data = deps.notifications();
      if (data === 'SECURITY_EXCEPTION') return { kind: 'read', message: 'Nu am acces la notificările WhatsApp. Activează accesul BENSON la notificări din Setări, sau cere conversația unui contact.' };
      const parsed: unknown = JSON.parse(data);
      if (!Array.isArray(parsed) || !parsed.every(item => item && typeof item.sender === 'string' && typeof item.text === 'string')) throw new Error('invalid_notifications');
      const items: WaReadItem[] = parsed.filter(item => item.text.trim());
      const result = deps.formatNotifications(items, t.startsWith('mai ') || t.includes('o data'));
      if (items.length > 4 && result.spokenCount === 0 && result.text.includes('pe toate')) pending = { items, at: now() };
      return { kind: 'read', message: result.text, awaitingChoice: !!pending };
    } catch {
      return { kind: 'read', message: 'Nu am reușit să citesc mesajele WhatsApp. Încearcă din nou.' };
    }
  };
}
