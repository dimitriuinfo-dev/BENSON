import type { AnthropicMsg } from './types';

const STOPWORDS = new Set([
  'the', 'a', 'an', 'is', 'are', 'and', 'or', 'to', 'of', 'in', 'on', 'for', 'with',
  'that', 'this', 'i', 'you', 'he', 'she', 'it', 'we', 'they', 'my', 'your', 'his',
  'her', 'its', 'our', 'their', 'am', 'was', 'were', 'be', 'been', 'do', 'does', 'did',
  'have', 'has', 'had', 'not', 'but', 'so', 'what', 'when', 'where', 'how', 'why',
  'ce', 'sa', 'să', 'cu', 'de', 'la', 'pe', 'un', 'o', 'si', 'și', 'este', 'sunt',
  'ma', 'mă', 'te', 'se', 'din', 'ca', 'dar', 'sau', 'care', 'mai', 'foarte',
]);

function timeOfDay(): string {
  const h = new Date().getHours();
  if (h < 6)  return 'late night';
  if (h < 12) return 'morning';
  if (h < 18) return 'afternoon';
  if (h < 22) return 'evening';
  return 'night';
}

function extractTopics(history: AnthropicMsg[], limit = 5): string[] {
  const freq = new Map<string, number>();
  for (const m of history) {
    if (m.role !== 'user') continue;
    for (const raw of m.content.toLowerCase().match(/[a-zăâîșț]{4,}/gi) ?? []) {
      if (STOPWORDS.has(raw)) continue;
      freq.set(raw, (freq.get(raw) ?? 0) + 1);
    }
  }
  return [...freq.entries()]
    .filter(([, count]) => count > 1)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([word]) => word);
}

function inferStyle(history: AnthropicMsg[]): string | null {
  const userMsgs = history.filter(m => m.role === 'user');
  if (userMsgs.length < 3) return null;
  const avgLen = userMsgs.reduce((sum, m) => sum + m.content.length, 0) / userMsgs.length;
  return avgLen < 40 ? 'short, direct messages' : avgLen < 120 ? 'moderate-length messages' : 'long, detailed messages';
}

function extractMentionedNames(history: AnthropicMsg[], knownNames: string[]): string[] {
  if (!knownNames.length) return [];
  const mentioned = new Set<string>();
  for (const m of history) {
    for (const name of knownNames) {
      if (new RegExp(`\\b${name}\\b`, 'i').test(m.content)) mentioned.add(name);
    }
  }
  return [...mentioned];
}

// Learning Agent — mines the conversation history for lightweight patterns
// (recurring topics, message style, time of day, family mentions) and turns
// them into a short context block the Claude Agent folds into its system prompt.
export function buildLearnedContext(history: AnthropicMsg[], knownNames: string[] = []): string {
  const parts = [`Current time of day: ${timeOfDay()}.`];

  const style = inferStyle(history);
  if (style) parts.push(`User's typical message style: ${style}.`);

  const topics = extractTopics(history);
  if (topics.length) parts.push(`Recurring topics the user brings up: ${topics.join(', ')}.`);

  const names = extractMentionedNames(history, knownNames);
  if (names.length) parts.push(`Family members recently mentioned in conversation: ${names.join(', ')}.`);

  return parts.join(' ');
}
