// Structured visual content an agent can hand to the Content Canvas, alongside its spoken reply.
export type ContentCard =
  | { kind: 'weather'; place: string; tempC: number; description: string; precipitation: number }
  | { kind: 'media'; provider: 'youtube' | 'spotify'; title: string; query: string }
  | { kind: 'map'; destination: string; latitude: number; longitude: number; distanceKm?: number }
  | { kind: 'gallery'; source: 'web' | 'device'; query?: string; images: { url: string; title?: string }[] }
  | {
      kind: 'news';
      query: string;
      articles: { title: string; url: string; snippet: string; image?: string; source?: string }[];
    }
  | { kind: 'note'; noteType: 'calendar' | 'reminder' | 'message' | 'feedback'; summary: string }
  | { kind: 'todo'; items: { id: string; text: string; done: boolean }[] }
  // BENSON CONTACT+CALL round (2026-09-23) — a pending or in-progress call, shown before/while
  // dialing: real resolved name/number/channel, plus a photo when the OS has one. Data-only for
  // now — no visual card wired into ContentCanvas/BensonMainScreen yet (ContentCanvas isn't
  // currently rendered by the approved screen at all; wiring a visible card is a design decision
  // left to the app's own owner, not built here — see this round's report).
  | {
      kind: 'contact';
      name: string;
      phoneNumber: string;
      channel: 'phone' | 'whatsapp';
      photoUri?: string;
      status: 'confirm' | 'calling';
    };
