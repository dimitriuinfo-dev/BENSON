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
  | { kind: 'todo'; items: { id: string; text: string; done: boolean }[] };
